"""Read the complete official 2.00 updater's USB template; no USB access.

Reports stored descriptor values, not observed enumeration or runtime speed.
The firmware mutates packet sizes and other descriptor fields at runtime.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct


EXPECTED = '4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976'
BASE = 0x40000000
DEVICE = 0x4009da30


def inspect(data):
    digest = hashlib.sha256(data).hexdigest()
    if digest != EXPECTED:
        raise ValueError('Requires the complete official 2.00 updater; wrong SHA-256')
    offset = DEVICE - BASE
    device = data[offset:offset + 18]
    if device[:2] != b'\x12\x01':
        raise ValueError('Unexpected device descriptor')
    config_offset = offset + 18
    total = struct.unpack_from('<H', data, config_offset + 2)[0]
    config = data[config_offset:config_offset + total]
    if len(config) != total or config[:2] != b'\x09\x02':
        raise ValueError('Invalid stored configuration boundary')
    interfaces = []
    current = None
    cursor = 0
    while cursor < total:
        length, kind = config[cursor:cursor + 2]
        if length < 2 or cursor + length > total:
            raise ValueError('Invalid descriptor length')
        descriptor = config[cursor:cursor + length]
        address = BASE + config_offset + cursor
        if kind == 4:
            if length != 9:
                raise ValueError('Unexpected interface descriptor size')
            current = {'address': hex(address), 'number': descriptor[2],
                       'alternate': descriptor[3], 'declared_endpoints': descriptor[4],
                       'class': hex(descriptor[5]), 'subclass': descriptor[6],
                       'protocol': descriptor[7], 'endpoints': []}
            interfaces.append(current)
        elif kind == 5:
            if current is None or length < 7:
                raise ValueError('Endpoint without valid interface')
            current['endpoints'].append({
                'descriptor_address': hex(address), 'address': hex(descriptor[2]),
                'direction': 'IN' if descriptor[2] & 0x80 else 'OUT',
                'transfer_type': ['control', 'isochronous', 'bulk', 'interrupt'][descriptor[3] & 3],
                'raw_wMaxPacketSize': struct.unpack_from('<H', descriptor, 4)[0],
                'interval': descriptor[6]})
        cursor += length
    for interface in interfaces:
        if interface['declared_endpoints'] != len(interface['endpoints']):
            raise ValueError('Interface endpoint count mismatch')
    if len({i['number'] for i in interfaces}) != config[4]:
        raise ValueError('Configuration interface count mismatch')
    return {'artifact_sha256': digest, 'source': 'complete official 2.00 updater',
            'scope': 'Stored template only; runtime getters modify packet sizes and fields. No camera measurement.',
            'device_descriptor_address': hex(DEVICE), 'device_descriptor_hex': device.hex(),
            'vid': f'{struct.unpack_from("<H", device, 8)[0]:04x}',
            'pid': f'{struct.unpack_from("<H", device, 10)[0]:04x}',
            'raw_bcdUSB': f'{struct.unpack_from("<H", device, 2)[0]:04x}',
            'raw_bcdDevice': f'{struct.unpack_from("<H", device, 12)[0]:04x}',
            'ep0_max_packet_size': device[7], 'serial_string_index': device[16],
            'configuration': {'address': hex(BASE + config_offset), 'total_length': total,
                              'number_of_interfaces': config[4], 'interfaces': interfaces}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, default=Path('analysis/output/card-updater-full.bin'))
    args = parser.parse_args()
    print(json.dumps(inspect(args.image.read_bytes()), indent=2))
