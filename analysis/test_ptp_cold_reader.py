"""Protocol safety tests using synthetic packets only; no USB dependency."""
import struct
import unittest

from ptp_cold_reader import (
    Capture, ColdReader, HEADER, ProtocolError, SimulatedCamera, SIZE, command,
)


FIXTURE = bytes((i * 37 + 11) % 256 for i in range(SIZE))


class AlteredCamera(SimulatedCamera):
    def __init__(self, change, at=5):
        super().__init__(FIXTURE)
        self.change, self.at = change, at

    def exchange(self, request):
        result = super().exchange(request)
        return self.change(result) if len(self.requests) == self.at else result


class ReaderTests(unittest.TestCase):
    def test_complete_sequence(self):
        camera = SimulatedCamera(FIXTURE)
        capture = ColdReader(camera).acquire()
        self.assertEqual(capture.data, FIXTURE)
        self.assertEqual(len(camera.requests), 132)
        decoded = []
        for i, packet in enumerate(camera.requests):
            length, kind, op, tid = HEADER.unpack_from(packet)
            self.assertEqual(tid, i)
            self.assertEqual(kind, 1)
            self.assertEqual(length, len(packet))
            decoded.append((op, struct.unpack_from('<I', packet, 12)[0] if length == 16 else None))
        expected_record = [(0x1025, 0)] + [(0x1027, offset) for offset in range(0, 1024, 16)]
        self.assertEqual(decoded, [(0x1002, 1)] + expected_record * 2 + [(0x1003, None)])
        self.assertIn('unknown', capture.summary()['freshness'])
        self.assertNotEqual(capture.summary()['service_entry_safe'], True)

    def test_forbidden_requests(self):
        for op, params in [
            (0x1025, (1,)), (0x1027, (0x10000,)), (0x1027, (0x10060,)),
            (0x1027, (-16,)), (0x1027, (1,)), (0x1027, (0x3f1,)),
            (0x1027, (1024,)), (0x1027, (0x60, 0)), (0x1027, ()),
            (0x100c, ()), (0x1016, (0x5011,)), (0x1024, (0,)),
            (0x1026, (0,)), (0x1002, (2,)), (0x1003, (1,)),
        ]:
            with self.subTest(op=op, params=params), self.assertRaises(ProtocolError):
                command(op, params, 0 if op == 0x1002 else 1)
        for tid in [-1, 0x100000000]:
            with self.assertRaises(ProtocolError):
                command(0x1025, (0,), tid)

    def test_bad_responses_abort_without_more_commands(self):
        def overwrite(packet, offset, fmt, value):
            result = bytearray(packet)
            struct.pack_into(fmt, result, offset, value)
            return bytes(result)
        changes = {
            'wrong transaction': lambda p: overwrite(p, 8, '<I', 999),
            'data phase': lambda p: overwrite(p, 4, '<H', 2),
            'PTP error': lambda p: overwrite(p, 6, '<H', 0x2005),
            'false size': lambda p: overwrite(p, 0, '<I', 4096),
            'nonzero prefix': lambda p: overwrite(p, 12, '<I', 1),
            'short frame': lambda p: p[:-1],
            'coalesced frame': lambda p: p + p,
            'missing parameter': lambda p: overwrite(p[:-4], 0, '<I', len(p)-4),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                camera = AlteredCamera(change)
                reader = ColdReader(camera)
                with self.assertRaises(ProtocolError):
                    reader.acquire()
                self.assertEqual(len(camera.requests), 5)
                with self.assertRaises(ProtocolError):
                    reader.acquire()
                self.assertEqual(len(camera.requests), 5)

    def test_timeout_is_not_retried(self):
        class TimeoutCamera(SimulatedCamera):
            def exchange(self, packet):
                self.requests.append(packet)
                raise TimeoutError('synthetic timeout')
        camera = TimeoutCamera(FIXTURE)
        reader = ColdReader(camera)
        with self.assertRaises(TimeoutError):
            reader.acquire()
        with self.assertRaises(ProtocolError):
            reader.acquire()
        self.assertEqual(len(camera.requests), 1)

    def test_changed_snapshot_rejected(self):
        class ChangingCamera(SimulatedCamera):
            def exchange(self, packet):
                if len(self.requests) == 66:
                    self.fixture = bytes([self.fixture[0] ^ 1]) + self.fixture[1:]
                return super().exchange(packet)
        camera = ChangingCamera(FIXTURE)
        with self.assertRaisesRegex(ProtocolError, 'differ'):
            ColdReader(camera).acquire()
        self.assertEqual(len(camera.requests), 131)  # no automatic close after failure

    def test_uniform_records_rejected(self):
        for data in [bytes(SIZE), b'\xff' * SIZE]:
            with self.assertRaisesRegex(ProtocolError, 'Uniform'):
                ColdReader(SimulatedCamera(data)).acquire()

    def test_close_failure_does_not_release_capture(self):
        camera = AlteredCamera(lambda p: p[:-1], at=132)
        with self.assertRaises(ProtocolError):
            ColdReader(camera).acquire()
        self.assertEqual(len(camera.requests), 132)

    def test_decoder_size_and_flags(self):
        for length in [0, 1023, 1025]:
            with self.assertRaises(ProtocolError):
                Capture(bytes(length))
        data = bytearray(FIXTURE)
        struct.pack_into('<H', data, 0x6c, 0x18)
        struct.pack_into('<H', data, 0x168, 1)
        summary = Capture(bytes(data)).summary()
        self.assertTrue(summary['ordinary_auto_scheduler_enabled'])
        self.assertEqual(summary['automatic_confirmation_setting_0x168'], 1)


if __name__ == '__main__':
    unittest.main()
