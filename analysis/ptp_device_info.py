#!/usr/bin/env python3
"""Offline standard metadata decoders. No USB, session or transport backend."""
import argparse
import json
from pathlib import Path
import struct


def get_device_info_command():
    """Apple PTPPassThrough commandBuffer format; framework supplies wire ID."""
    return struct.pack('<IHHI', 12, 1, 0x1001, 0)


def decode_storage_ids(data):
    """Strict selected X-half standard metadata reply, at most three IDs."""
    if not 4 <= len(data) <= 16:
        raise ValueError('StorageIDs must contain 4..16 bytes')
    count = struct.unpack_from('<I', data)[0]
    if count > 3 or len(data) != 4 + count * 4:
        raise ValueError('StorageIDs count/length mismatch')
    return {'count': count, 'storage_ids': [f'{x:08x}' for (x,) in struct.iter_unpack('<I', data[4:])]}


def decode_storage_info(data):
    """Bounded StorageInfo decoder; validate but omit volume/description text."""
    if not 28 <= len(data) <= 1048:
        raise ValueError('StorageInfo must contain 28..1048 bytes')
    storage_type, filesystem, access, capacity, free, images = struct.unpack_from('<HHHQQI', data)
    offset = 26
    present = []
    for _ in range(2):
        if offset >= len(data):
            raise ValueError('Truncated StorageInfo string count')
        count = data[offset]
        offset += 1
        length = count * 2
        if length > len(data) - offset:
            raise ValueError('Truncated StorageInfo string')
        raw = data[offset:offset + length]
        offset += length
        if count:
            if raw[-2:] != b'\0\0':
                raise ValueError('StorageInfo string missing terminator')
            text = raw[:-2].decode('utf-16-le', errors='strict')
            if '\0' in text:
                raise ValueError('StorageInfo string contains embedded NUL')
            present.append(bool(text))
        else:
            present.append(False)
    if offset != len(data):
        raise ValueError('Trailing StorageInfo bytes')
    return {'storage_type_code': storage_type, 'filesystem_type_code': filesystem,
            'access_capability_code': access, 'maximum_capacity_bytes': capacity,
            'free_space_bytes': free, 'free_space_images': images,
            'description_present': present[0], 'volume_label_present': present[1],
            'text_fields_omitted': True}


def decode_device_info(data):
    """Decode dataset bytes (without a USB container). Do not expose serial."""
    if not 0 < len(data) <= 65536:
        raise ValueError('DeviceInfo must contain 1..65536 bytes')
    offset = 0

    def take(length):
        nonlocal offset
        if length > len(data) - offset:
            raise ValueError('Truncated DeviceInfo dataset')
        value = data[offset:offset + length]
        offset += length
        return value

    def number(fmt):
        return struct.unpack('<' + fmt, take(struct.calcsize('<' + fmt)))[0]

    def string():
        count = number('B')
        if not count:
            return ''
        value = take(count * 2)
        if value[-2:] != b'\0\0':
            raise ValueError('PTP string has no terminating NUL')
        text = value[:-2].decode('utf-16-le', errors='strict')
        if '\0' in text:
            raise ValueError('Embedded NUL in PTP string')
        return text

    def codes():
        count = number('I')
        raw = take(count * 2)
        return [f'{value:04x}' for (value,) in struct.iter_unpack('<H', raw)]

    result = {
        'standard_version': number('H'),
        'vendor_extension_id': number('I'),
        'vendor_extension_version': number('H'),
        'vendor_extension_description': string(),
        'functional_mode': number('H'),
    }
    for key in ('operations', 'events', 'device_properties',
                'capture_formats', 'image_formats'):
        result[key] = codes()
    for key in ('manufacturer', 'model', 'device_version'):
        result[key] = string()
    string()  # Consume/validate serial without returning or logging it.
    if offset != len(data):
        raise ValueError('Unexpected bytes after DeviceInfo dataset')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inspect-dataset', required=True, type=Path)
    args = parser.parse_args()
    with args.inspect_dataset.open('rb') as source:
        raw = source.read(65537)
    print(json.dumps(decode_device_info(raw), indent=2))
