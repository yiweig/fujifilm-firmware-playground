import struct
import unittest

from ptp_device_info import decode_device_info, get_device_info_command


def ptp_string(value):
    raw = value.encode('utf-16-le') + b'\0\0'
    return bytes([len(raw) // 2]) + raw


def fixture():
    return (struct.pack('<HIH', 100, 0, 100) + b'\0' + struct.pack('<H', 0)
            + struct.pack('<IHH', 2, 0x1001, 0x1002) + struct.pack('<I', 0) * 4
            + b''.join(ptp_string(v) for v in
                       ('Synthetic manufacturer', 'Synthetic model', 'test', 'PRIVATE-SERIAL')))


class DeviceInfoTests(unittest.TestCase):
    def test_command_and_redacted_decode(self):
        self.assertEqual(get_device_info_command().hex(), '0c0000000100011000000000')
        result = decode_device_info(fixture())
        self.assertEqual(result['operations'], ['1001', '1002'])
        self.assertEqual(result['device_version'], 'test')
        self.assertNotIn('PRIVATE-SERIAL', repr(result))
        self.assertNotIn('serial', result)

    def test_all_truncations_and_trailing_data_rejected(self):
        raw = fixture()
        for length in range(len(raw)):
            with self.subTest(length=length), self.assertRaises(ValueError):
                decode_device_info(raw[:length])
        with self.assertRaises(ValueError):
            decode_device_info(raw + b'\0')

    def test_bad_counts_terminator_and_utf16(self):
        raw = bytearray(fixture())
        raw[11:15] = struct.pack('<I', 0xffffffff)
        with self.assertRaises(ValueError):
            decode_device_info(raw)
        for broken in (fixture()[:-2] + b'xx',
                       struct.pack('<HIH', 100, 0, 100) + b'\2\0\xd8\0\0',
                       b'x' * 65537):
            with self.assertRaises(ValueError):
                decode_device_info(broken)


if __name__ == '__main__':
    unittest.main()
