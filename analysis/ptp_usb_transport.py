"""Bounded bulk transport for the restricted PTP reader; no device-opening CLI.

The native adapter requires an already opened, exclusively claimed and
identity-verified libusb handle. This module never discovers/opens/claims a
device, changes configuration, detaches drivers, resets USB, or clears stalls.
Native I/O has not been tested against the camera. See the transport report.
"""
import ctypes
from dataclasses import dataclass
from pathlib import Path
import struct
import sys
import time

from ptp_cold_reader import HEADER, ProtocolError, command


@dataclass(frozen=True)
class Endpoints:
    bulk_in: int
    bulk_out: int
    packet_size: int

    @classmethod
    def from_configuration(cls, data: bytes, active_configuration: int):
        """Accept only one interface, alt 0, class 6/1/1 and three endpoints.

        Endpoint addresses are discovered from the descriptor, never guessed.
        Reject unsupported descriptor layouts rather than choosing a fallback.
        """
        if not 9 <= len(data) <= 4096 or data[:2] != b'\x09\x02':
            raise ProtocolError('Invalid configuration descriptor')
        if struct.unpack_from('<H', data, 2)[0] != len(data) or data[4] != 1:
            raise ProtocolError('Configuration size/interface count mismatch')
        if not active_configuration or data[5] != active_configuration:
            raise ProtocolError('Descriptor is not for the already active configuration')
        interfaces, endpoints = [], []
        offset = 9
        while offset < len(data):
            length, kind = data[offset:offset+2] if offset + 2 <= len(data) else (0, 0)
            if length < 2 or offset + length > len(data):
                raise ProtocolError('Truncated or invalid descriptor entry')
            part = data[offset:offset+length]
            if kind == 4:
                if length != 9 or tuple(part[2:8]) != (0, 0, 3, 6, 1, 1):
                    raise ProtocolError('Unqualified PTP interface/alternate setting')
                interfaces.append(part)
            elif kind == 5:
                if len(interfaces) != 1 or length != 7:
                    raise ProtocolError('Endpoint outside expected interface')
                address, attr, size = part[2], part[3], struct.unpack_from('<H', part, 4)[0]
                if not address & 15 or address & 0x70:
                    raise ProtocolError('Invalid endpoint address')
                endpoints.append((address, attr & 3, size))
            else:
                raise ProtocolError('Unqualified descriptor entry type')
            offset += length
        if len(interfaces) != 1 or len(endpoints) != 3 or len({e[0] for e in endpoints}) != 3:
            raise ProtocolError('Expected exactly three distinct endpoints')
        incoming = [e for e in endpoints if e[1] == 2 and e[0] & 0x80]
        outgoing = [e for e in endpoints if e[1] == 2 and not e[0] & 0x80]
        events = [e for e in endpoints if e[1] == 3 and e[0] & 0x80]
        if len(incoming) != 1 or len(outgoing) != 1 or len(events) != 1:
            raise ProtocolError('Expected bulk IN/OUT and interrupt IN')
        if incoming[0][2] not in (64, 512) or outgoing[0][2] != incoming[0][2]:
            raise ProtocolError('Unqualified bulk maximum packet size')
        return cls(incoming[0][0], outgoing[0][0], incoming[0][2])


class UsbTransferError(IOError):
    def __init__(self, status, transferred):
        super().__init__(f'libusb transfer failed: status={status}, transferred={transferred}')
        self.status, self.transferred = status, transferred


class LibusbPipe:
    """Only libusb_bulk_transfer is bound; caller retains handle ownership."""
    def __init__(self, library, handle: int, endpoints: Endpoints):
        if type(handle) is not int or handle <= 0:
            raise ValueError('An already verified/claimed native handle is required')
        self.library, self.handle, self.endpoints = library, handle, endpoints
        transfer = library.libusb_bulk_transfer
        transfer.argtypes = [ctypes.c_void_p, ctypes.c_ubyte,
                              ctypes.POINTER(ctypes.c_ubyte), ctypes.c_int,
                              ctypes.POINTER(ctypes.c_int), ctypes.c_uint]
        transfer.restype = ctypes.c_int

    @classmethod
    def from_macos_library(cls, path: Path, handle: int, endpoints: Endpoints):
        if sys.platform != 'darwin' or not path.is_absolute() or not path.is_file():
            raise ValueError('Supply an existing absolute macOS libusb library path')
        return cls(ctypes.CDLL(str(path)), handle, endpoints)

    def _transfer(self, endpoint, data, size, timeout_ms):
        if not 1 <= timeout_ms <= 2000 or not 1 <= size <= 512:
            raise ValueError('Unbounded transfer rejected')
        array = ctypes.c_ubyte * size
        buffer = array.from_buffer_copy(data) if data is not None else array()
        transferred = ctypes.c_int()
        status = self.library.libusb_bulk_transfer(
            self.handle, endpoint, buffer, size, ctypes.byref(transferred), timeout_ms)
        if status != 0:
            # Even a timeout may have transferred bytes; never resend automatically.
            raise UsbTransferError(status, transferred.value)
        if not 0 <= transferred.value <= size:
            raise ProtocolError('Invalid native transfer count')
        return bytes(buffer[:transferred.value])

    def write(self, data, timeout_ms):
        return len(self._transfer(self.endpoints.bulk_out, data, len(data), timeout_ms))

    def read(self, timeout_ms):
        # Whole USB packet capacity avoids requesting just a 12/32-byte PTP header.
        return self._transfer(self.endpoints.bulk_in, None, self.endpoints.packet_size, timeout_ms)


class BulkTransport:
    """One allowlisted command, one response, at most two seconds total.

    An exception permanently stops this transport. A full caller identity and
    interface-ownership preflight is required before constructing a native pipe.
    """
    def __init__(self, pipe, *, clock=time.monotonic):
        self.pipe, self.clock, self.failed = pipe, clock, False

    def exchange(self, request):
        if self.failed:
            raise ProtocolError('Transport stopped after a previous failure')
        self.failed = True
        if not isinstance(request, bytes) or len(request) not in (12, 16):
            raise ProtocolError('Not an allowlisted PTP command frame')
        length, kind, opcode, transaction = HEADER.unpack_from(request)
        params = struct.unpack_from('<I', request, 12) if len(request) == 16 else ()
        if kind != 1 or length != len(request) or command(opcode, params, transaction) != request:
            raise ProtocolError('Not an allowlisted PTP command frame')
        deadline = self.clock() + 2

        def remaining():
            ms = int((deadline - self.clock()) * 1000)
            if ms <= 0:
                raise TimeoutError('PTP transaction deadline exceeded')
            return min(2000, ms)

        if self.pipe.write(request, remaining()) != len(request):
            raise ProtocolError('Partial USB command write; no retry')
        packet = bytearray()
        for _ in range(32):
            chunk = self.pipe.read(remaining())
            if not isinstance(chunk, bytes) or not chunk or len(packet) + len(chunk) > 32:
                raise ProtocolError('Empty, oversized or coalesced USB response')
            packet.extend(chunk)
            if len(packet) >= 4:
                size = struct.unpack_from('<I', packet)[0]
                if size not in (12, 32) or len(packet) > size:
                    raise ProtocolError('Unqualified response frame length')
                if len(packet) == size:
                    from ptp_cold_reader import response
                    count = 5 if opcode == 0x1027 else 0
                    response(bytes(packet), transaction, count)
                    self.failed = False
                    return bytes(packet)
        raise ProtocolError('Too many response fragments')
