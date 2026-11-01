"""Read cached macOS USB registry metadata for 04cb:030a; never open USB.

--read-registry runs ioreg only. --plist parses a saved ioreg plist offline.
Raw serial strings and unrelated devices are excluded from the output.
Missing endpoint descriptors are reported, never replaced by guessed addresses.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys

from ptp_cold_reader import ProtocolError
from ptp_usb_transport import Endpoints


def nodes(tree):
    if isinstance(tree, list):
        for child in tree:
            yield from nodes(child)
    elif isinstance(tree, dict):
        yield tree
        yield from nodes(tree.get('IORegistryEntryChildren', []))


def inventory(tree):
    result = []
    for device in nodes(tree):
        if (device.get('idVendor'), device.get('idProduct')) != (0x04cb, 0x030a):
            continue
        if type(device.get('bInterfaceNumber')) is int:
            continue
        item = {'vendor': '04cb', 'product': '030a', 'interfaces': [],
                'endpoint_plans': [], 'source': 'cached IORegistry; no camera commands sent'}
        for key in ['IORegistryEntryID', 'locationID', 'USB Address', 'bNumConfigurations', 'bConfigurationValue',
                    'kUSBCurrentConfiguration', 'bcdUSB', 'bcdDevice', 'Device Speed']:
            if type(device.get(key)) is int:
                item[key] = device[key]
        serial = device.get('USB Serial Number')
        if isinstance(serial, str):
            item['serial_sha256'] = hashlib.sha256(serial.encode()).hexdigest()
        for child in nodes(device):
            interface = {key: child[key] for key in [
                'bInterfaceNumber', 'bAlternateSetting', 'bInterfaceClass',
                'bInterfaceSubClass', 'bInterfaceProtocol', 'bNumEndpoints', 'bConfigurationValue',
            ] if type(child.get(key)) is int}
            if 'bInterfaceNumber' in interface:
                item['interfaces'].append(interface)
            if child.get('IOObjectClass') == 'AppleUSBHostInterfaceUserClient':
                creator = child.get('IOUserClientCreator', '')
                match = re.fullmatch(r'pid \d+, (.+)', creator) if isinstance(creator, str) else None
                if match:
                    owners = item.setdefault('interface_user_client_owners', [])
                    if match[1] not in owners:
                        owners.append(match[1])
            # Parse descriptor-shaped cached byte values without publishing arbitrary data.
            for value in child.values():
                if isinstance(value, bytes) and value[:2] == b'\x09\x02':
                    try:
                        config = device.get('bConfigurationValue', device.get('kUSBCurrentConfiguration'))
                        plan = Endpoints.from_configuration(value, config)
                    except (ProtocolError, TypeError):
                        continue
                    entry = {'bulk_in': plan.bulk_in, 'bulk_out': plan.bulk_out,
                             'packet_size': plan.packet_size}
                    if entry not in item['endpoint_plans']:
                        item['endpoint_plans'].append(entry)
        item['endpoint_status'] = 'cached descriptor parsed' if item['endpoint_plans'] else 'not established'
        result.append(item)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--read-registry', action='store_true')
    mode.add_argument('--plist', type=Path)
    args = parser.parse_args()
    if args.read_registry:
        if sys.platform != 'darwin':
            parser.error('IORegistry inspection requires macOS')
        try:
            raw = subprocess.run(['/usr/sbin/ioreg', '-a', '-l', '-p', 'IOService',
                                  '-r', '-c', 'IOUSBHostDevice'], check=True,
                                 capture_output=True, timeout=15).stdout
        except subprocess.CalledProcessError as error:
            raise SystemExit('Cached registry read failed: ' + error.stderr.decode(errors='replace').strip())
        except subprocess.TimeoutExpired:
            raise SystemExit('Cached registry read timed out')
    else:
        raw = args.plist.read_bytes()
    found = inventory(plistlib.loads(raw))
    print(json.dumps({'matching_devices': found, 'count': len(found)}, indent=2))


if __name__ == '__main__':
    main()
