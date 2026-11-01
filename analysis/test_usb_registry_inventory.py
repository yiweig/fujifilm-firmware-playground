"""Synthetic IORegistry fixtures only; never runs ioreg."""
import json
import contextlib
import io
import plistlib
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from usb_registry_inventory import inventory, main
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

    def test_active_configuration_and_owner(self):
        device = {'idVendor': 0x04cb, 'idProduct': 0x030a,
                  'kUSBCurrentConfiguration': 1, 'configuration': CONFIG,
                  'IORegistryEntryChildren': [{'IOObjectClass': 'AppleUSBHostInterfaceUserClient',
                                              'IOUserClientCreator': 'pid 123, ptpcamerad'}]}
        found = inventory([device])[0]
        self.assertEqual(found['endpoint_status'], 'cached descriptor parsed')
        self.assertEqual(found['interface_user_client_owners'], ['ptpcamerad'])

    def test_cli_includes_properties_and_interface_subtree(self):
        result = SimpleNamespace(stdout=plistlib.dumps([]))
        with patch('sys.argv', ['inventory', '--read-registry']), \
             patch('usb_registry_inventory.sys.platform', 'darwin'), \
             patch('usb_registry_inventory.subprocess.run', return_value=result) as run, \
             contextlib.redirect_stdout(io.StringIO()):
            main()
        command = run.call_args.args[0]
        self.assertIn('-l', command)
        self.assertEqual(command[command.index('-p')+1], 'IOService')
        self.assertEqual(command[command.index('-c')+1], 'IOUSBHostDevice')


if __name__ == '__main__':
    unittest.main()
