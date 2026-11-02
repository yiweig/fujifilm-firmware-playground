"""Offline USB-boundary tests. No native library is loaded and no USB is opened."""
import ctypes
import struct
import unittest

from ptp_cold_reader import ColdReader, HEADER, ProtocolError, SimulatedCamera, command
from ptp_usb_transport import BulkTransport, Endpoints, LibusbPipe, UsbTransferError


CONFIG = bytes.fromhex(
    '090227000101008032' '090400000306010100'
    '07058102000200' '07050202000200' '07058303100010')


class Pipe:
    def __init__(self, parts, written=None):
        self.parts = list(parts)
        self.writes, self.reads, self.written = [], 0, written

    def write(self, request, timeout):
        self.writes.append(request)
        assert 0 < timeout <= 2000
        return len(request) if self.written is None else self.written

    def read(self, timeout):
        self.reads += 1
        assert 0 < timeout <= 2000
        return self.parts.pop(0)


class TransportTests(unittest.TestCase):
    def test_descriptor_selection(self):
        endpoints = Endpoints.from_configuration(CONFIG, 1)
        self.assertEqual(endpoints, Endpoints(0x81, 2, 512))
        for offset, value in [(2, 38), (4, 2), (5, 2), (12, 1), (14, 255), (20, 2), (26, 0x81)]:
            data = bytearray(CONFIG)
            data[offset] = value
            with self.subTest(offset=offset), self.assertRaises(ProtocolError):
                Endpoints.from_configuration(bytes(data), 1)
        for invalid in [CONFIG[:-1], CONFIG+b'\x00', CONFIG[:20], b'']:
            with self.assertRaises(ProtocolError):
                Endpoints.from_configuration(invalid, 1)

    def test_fragmented_response(self):
        reply = HEADER.pack(12, 3, 0x2001, 0)
        pipe = Pipe([reply[:2], reply[2:7], reply[7:]])
        self.assertEqual(BulkTransport(pipe).exchange(command(0x1002, (1,), 0)), reply)
        self.assertEqual(pipe.reads, 3)
        self.assertEqual(len(pipe.writes), 1)

    def test_failed_transfers_do_not_retry(self):
        for parts, written in [([b''], None), ([b'X'*33], None), ([], 5),
                               ([HEADER.pack(12, 2, 0x2001, 0)], None),
                               ([HEADER.pack(12, 3, 0x2001, 9)], None)]:
            pipe = Pipe(parts, written)
            transport = BulkTransport(pipe)
            with self.assertRaises(ProtocolError):
                transport.exchange(command(0x1002, (1,), 0))
            reads = pipe.reads
            with self.assertRaises(ProtocolError):
                transport.exchange(command(0x1002, (1,), 0))
            self.assertEqual((len(pipe.writes), pipe.reads), (1, reads))

    def test_write_is_rejected_at_transport_boundary(self):
        pipe = Pipe([])
        request = HEADER.pack(16, 1, 0x1025, 1) + struct.pack('<I', 1)
        with self.assertRaises(ProtocolError):
            BulkTransport(pipe).exchange(request)
        self.assertFalse(pipe.writes)

    def test_transaction_deadline(self):
        clock = iter([0, 0, 3])
        pipe = Pipe([])
        with self.assertRaises(TimeoutError):
            BulkTransport(pipe, clock=lambda: next(clock)).exchange(command(0x1002, (1,), 0))
        self.assertEqual((len(pipe.writes), pipe.reads), (1, 0))

    def test_full_reader_through_bulk_framing(self):
        fixture = bytearray((i*37+11) % 256 for i in range(1024))
        struct.pack_into('<H', fixture, 0x3fe, sum(fixture[:0x3fe]) & 0xff)
        fixture = bytes(fixture)
        camera = SimulatedCamera(fixture)

        class CameraPipe:
            def write(self, request, timeout):
                reply = camera.exchange(request)
                self.parts = [reply[:5], reply[5:]]
                return len(request)

            def read(self, timeout):
                return self.parts.pop(0)

        result = ColdReader(BulkTransport(CameraPipe())).acquire()
        self.assertEqual(result.data, fixture)
        self.assertEqual(len(camera.requests), 132)

    def test_native_abi_counts_and_timeout(self):
        class Transfer:
            def __init__(self):
                self.calls, self.status = [], 0

            def __call__(self, handle, endpoint, buffer, length, count, timeout):
                self.calls.append((handle, endpoint, length, timeout))
                n = 8 if self.status else length
                ctypes.cast(count, ctypes.POINTER(ctypes.c_int))[0] = n
                if endpoint & 0x80:
                    for i in range(n):
                        buffer[i] = i % 256
                return self.status

        class Library:
            libusb_bulk_transfer = Transfer()

        library = Library()
        pipe = LibusbPipe(library, 123, Endpoints(0x81, 2, 512))
        self.assertEqual(pipe.write(b'X'*16, 1000), 16)
        self.assertEqual(len(pipe.read(1000)), 512)
        library.libusb_bulk_transfer.status = -7
        with self.assertRaises(UsbTransferError) as caught:
            pipe.write(b'X'*16, 1000)
        self.assertEqual(caught.exception.transferred, 8)
        self.assertEqual([c[1] for c in library.libusb_bulk_transfer.calls], [2, 0x81, 2])


if __name__ == '__main__':
    unittest.main()
