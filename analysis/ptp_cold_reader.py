"""Restricted X half 2.00 PTP read protocol; no USB backend or device discovery.

Run --simulate for an entirely local demonstration or --inspect FILE for a
previously acquired 1024-byte record. No generic commands or upload data phase.
The firmware cannot attest that its file read succeeded: freshness is unknown.
"""
from dataclasses import dataclass
import argparse
import hashlib
import json
from pathlib import Path
import struct
from typing import Protocol

HEADER = struct.Struct('<IHHI')
SIZE = 1024


class ProtocolError(ValueError):
    pass


class Transport(Protocol):
    def exchange(self, request: bytes) -> bytes:
        """Return exactly one complete PTP response; bound I/O in the backend.

        Implementations must not retry, upload a data phase, synchronize a clock,
        reset a device, clear stalls, or send any other command implicitly.
        No live implementation is supplied by this module.
        """
        ...


def command(opcode: int, params: tuple[int, ...], transaction: int) -> bytes:
    """Allowlist applies to the entire operation/parameter combination."""
    allowed = (
        (opcode == 0x1002 and params == (1,) and transaction == 0)
        or (opcode == 0x1003 and params == () and transaction > 0)
        or (opcode == 0x1025 and params == (0,) and transaction > 0)
        or (opcode == 0x1027 and len(params) == 1 and transaction > 0
            and type(params[0]) is int and 0 <= params[0] <= SIZE - 16
            and params[0] % 16 == 0)
    )
    if not allowed or type(transaction) is not int or not 0 <= transaction <= 0xffffffff:
        raise ProtocolError('Operation, parameter, or transaction outside read allowlist')
    return HEADER.pack(12 + 4 * len(params), 1, opcode, transaction) + struct.pack(
        '<' + 'I' * len(params), *params)


def response(packet: bytes, transaction: int, count: int) -> tuple[int, ...]:
    if not isinstance(packet, bytes) or not 12 <= len(packet) <= 32:
        raise ProtocolError('Response must be a complete 12-to-32-byte container')
    length, kind, code, actual = HEADER.unpack_from(packet)
    if length != len(packet) or kind != 3 or actual != transaction or (length - 12) % 4:
        raise ProtocolError('Invalid response length, type, or transaction')
    if code != 0x2001:
        raise ProtocolError(f'PTP response 0x{code:04x}; acquisition aborted')
    if length != 12 + 4 * count:
        raise ProtocolError('Unexpected response parameter count')
    return struct.unpack_from('<' + 'I' * count, packet, 12)


@dataclass(frozen=True)
class Capture:
    data: bytes

    def __post_init__(self):
        if len(self.data) != SIZE:
            raise ProtocolError('Factory record must contain exactly 1024 bytes')

    def summary(self) -> dict:
        auto, confirm = (struct.unpack_from('<H', self.data, n)[0] for n in (0x6c, 0x168))
        return {
            'bytes': SIZE,
            'sha256': hashlib.sha256(self.data).hexdigest(),
            'freshness': 'unknown: firmware ignores file-read errors',
            'DisableTest_low_byte': self.data[0x0a],
            'FgAutoClbt': auto,
            'ordinary_auto_scheduler_enabled': (auto & 15) == 8,
            'automatic_confirmation_setting_0x168': confirm,
            'service_entry_safe': 'not established; other startup branches/items remain',
        }


class ColdReader:
    """Single-use client: open, two matching reads, close, then release result.

    A transport/protocol error poisons the client: no retries or further I/O,
    including CloseSession after a possibly ambiguous transaction. Only the
    complete successful sequence closes automatically. A USB backend and live
    lifecycle qualification remain required before camera use.
    """
    def __init__(self, transport: Transport):
        self._transport = transport
        self._transaction = 0
        self._used = False

    def _request(self, opcode, params, count):
        transaction = self._transaction
        packet = command(opcode, params, transaction)
        self._transaction += 1
        return response(self._transport.exchange(packet), transaction, count)

    def _record(self):
        self._request(0x1025, (0,), 0)
        chunks = []
        for offset in range(0, SIZE, 16):
            words = self._request(0x1027, (offset,), 5)
            if words[0] != 0:
                raise ProtocolError('Unexpected chunk prefix')
            chunks.append(struct.pack('<4I', *words[1:]))
        return b''.join(chunks)

    def acquire(self) -> Capture:
        if self._used:
            raise ProtocolError('Client is single-use; acquisition already attempted')
        self._used = True
        self._request(0x1002, (1,), 0)
        first = self._record()
        second = self._record()
        if first != second:
            raise ProtocolError('Repeated records differ; result rejected')
        # Detect obvious invalid captures; passing this is not a freshness test.
        if first in (bytes(SIZE), b'\xff' * SIZE):
            raise ProtocolError('Uniform zero/ff record; result rejected')
        self._request(0x1003, (), 0)
        return Capture(first)


class SimulatedCamera:
    """Synthetic fixture only. Never opens files, a USB device, or a socket."""
    def __init__(self, fixture: bytes):
        if len(fixture) != SIZE:
            raise ValueError('Simulation fixture must be 1024 bytes')
        self.fixture = fixture
        self.requests = []

    def exchange(self, request):
        self.requests.append(request)
        length, kind, op, transaction = HEADER.unpack_from(request)
        params = struct.unpack_from('<' + 'I' * ((length - 12) // 4), request, 12)
        if request != command(op, params, transaction):
            raise ProtocolError('Simulation received an invalid command')
        values = (0, *struct.unpack_from('<4I', self.fixture, params[0])) if op == 0x1027 else ()
        return HEADER.pack(12 + 4 * len(values), 3, 0x2001, transaction) + struct.pack(
            '<' + 'I' * len(values), *values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--simulate', action='store_true', help='Use a synthetic camera in memory')
    mode.add_argument('--inspect', type=Path, help='Decode an existing 1024-byte record offline')
    args = parser.parse_args()
    if args.simulate:
        fixture = bytearray((i * 37 + 11) % 256 for i in range(SIZE))
        struct.pack_into('<H', fixture, 0x6c, 4)
        struct.pack_into('<H', fixture, 0x168, 0)
        transport = SimulatedCamera(bytes(fixture))
        result = ColdReader(transport).acquire().summary()
        result.update(source='SYNTHETIC SIMULATION, not camera evidence', requests=len(transport.requests))
    else:
        with args.inspect.open('rb') as stream:
            data = stream.read(SIZE + 1)
        result = Capture(data).summary()
        result['source'] = 'Existing file; provenance and firmware version not established by decoder'
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
