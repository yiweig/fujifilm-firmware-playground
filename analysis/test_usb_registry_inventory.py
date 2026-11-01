"""Synthetic IORegistry fixtures only; never runs ioreg."""
import json
import unittest

from usb_registry_inventory import inventory
from test_ptp_usb_transport import CONFIG


class InventoryTests(unittest.TestCase):
    def test_matching_redaction_and_descriptor(self):
        tree = [{'IORegistryEntryChildren': [
            {'idVendor': 0x1234, 'idProduct': 1, 'USB Serial Number': 'OTHER-SECRET'},
            {'idVendor': 0x04cb, 'idProduct': 0x030a, 'USB Serial Number': 'CAMERA-SECRET',
             'bConfigurationValue': 1, 'locationID': 123,
             'configuration': CONFIG, 'IORegistryEntryChildren': [
                 {'idVendor': 0x04cb, 'idProduct': 0x030a,
                  'bInterfaceNumber': 0, 'bInterfaceClass': 6, 'bNumEndpoints': 3}]},
        ]}]
        found = inventory(tree)
        self.assertEqual(len(found), 1)
        output = json.dumps(found)
        self.assertNotIn('SECRET', output)
        self.assertEqual(found[0]['endpoint_plans'], [{'bulk_in': 129, 'bulk_out': 2, 'packet_size': 512}])

    def test_no_guess_or_assumption_of_active_configuration(self):
        device = {'idVendor': 0x04cb, 'idProduct': 0x030a, 'configuration': CONFIG}
        self.assertEqual(inventory([device])[0]['endpoint_status'], 'not established')
        self.assertEqual(inventory([]), [])


if __name__ == '__main__':
    unittest.main()
