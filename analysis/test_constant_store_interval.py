"""Hand-encoded ARM controls for the scanner's configurable interval boundary."""
import struct
import unittest
from find_usb_flag_stores import BASE, scan


def words(*values):
    return struct.pack('<' + 'I' * len(values), *values)


def bl(site, target):
    return 0xeb000000 | (((target - (BASE + site + 8)) // 4) & 0xffffff)


class IntervalSearchTests(unittest.TestCase):
    def test_store_in_middle_of_word_interval(self):
        # LDR r3,[pc,#4]; STRB r0,[r3,#0x16]; BX lr; literal.
        data = words(0xe59f3004, 0xe5c30016, 0xe12fff1e, 0x4136b650)
        self.assertEqual(scan(data, 16, 0x4136b664, 1)[1], [])
        hits = scan(data, 16, 0x4136b664, 4)[1]
        self.assertEqual([h['address'] for h in hits], ['0x4136b666'])

    def test_branch_does_not_change_search_target(self):
        # LDR r4,[pc,#8]; BL memcpy; STR r5,[r4,#0x14]; BX lr; literal.
        data = words(0xe59f4008, bl(4, 0x40008b8c), 0xe5845014,
                     0xe12fff1e, 0x4136b650)
        hits = scan(data, 16, 0x4136b664, 4)[1]
        self.assertEqual([h['instruction'] for h in hits], ['0x40000008'])
        self.assertEqual(scan(data, 16, 0x40e6696d, 1)[1], [])

    def test_bulk_interval_overlap(self):
        # LDR r0,[pc,#8]; MOV r2,#0x40; BL memset; BX lr; literal.
        data = words(0xe59f0008, 0xe3a02040, bl(8, 0x4000cdf0),
                     0xe12fff1e, 0x4136b650)
        result = scan(data, 16, 0x4136b664, 4)
        self.assertEqual([h['size'] for h in result[3]], [64])
        self.assertEqual(scan(data, 16, 0x4136b690, 4)[3], [])

    def test_invalid_interval_rejected(self):
        for start, size in ((0, 0), (-1, 4), (0xffffffff, 2)):
            with self.assertRaises(ValueError):
                scan(b'', 16, start, size)

    def test_literal_loaded_bulk_call(self):
        # LDR r0,literal; MOV r2,#0x40; LDR r3,literal; BLX r3; BX lr.
        data = words(0xe59f000c, 0xe3a02040, 0xe59f3008, 0xe12fff33,
                     0xe12fff1e, 0x4136b650, 0x4000cdf0)
        result = scan(data, 16, 0x4136b664, 4)
        self.assertEqual([h['instruction'] for h in result[3]], ['0x4000000c'])
        self.assertEqual(result[2]['resolved_register_bulk_targets'], 1)


if __name__ == '__main__':
    unittest.main()
