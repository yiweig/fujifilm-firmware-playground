#!/usr/bin/env python3
"""Compare selected PTP instruction bodies and dispatch pointers, offline only."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent.parent
BASE = 0x40000000
IMAGES = {
    '1.01': ('analysis/output/firmware-main-1.01.bin', '64ca08d346075907bbec2dde2db50bc3a5507a93e5d7b2e5024d4a59599125f9'),
    '1.20': ('analysis/output/firmware-main-1.20.bin', '85d6d280605148d131c28f71b3560742310878340c6de5d1c05faf00d02a2c8f'),
    '1.30': ('analysis/output/firmware-main-1.30.bin', '34034d7d1314485fbbd8167669a83dbea4d3dc80cc9ba3c87c7ded2c96aa9df6'),
    '2.00': ('firmware-main.bin', '80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923'),
}
REGIONS = (
    ('dispatcher', 0x402e778c, 0x402e7dd0),
    ('image_handler', 0x402ea6f4, 0x402eaabc),
    ('bulk_description_handler', 0x40331bac, 0x40331ca4),
)


def compare():
    images = {}
    for version, (path, expected) in IMAGES.items():
        raw = (ROOT / path).read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError(f'Wrong image hash: {version}')
        images[version] = raw
    main = images['2.00']
    result = {
        'scope': 'Exact instruction-body matches; literal pools and transitive callees excluded except for the two explicitly checked dispatch pointers. No camera access.',
        'versions': {},
    }
    for version, raw in images.items():
        row = {'sha256': IMAGES[version][1], 'regions': {}}
        for name, start, end in REGIONS:
            body = main[start - BASE:end - BASE]
            at = raw.find(body)
            if at < 0 or raw.find(body, at + 1) >= 0:
                raise ValueError(f'No unique exact body match: {version} {name}')
            row['regions'][name] = {
                'address': f'{BASE + at:08x}', 'bytes': len(body),
                'body_sha256': hashlib.sha256(body).hexdigest(),
            }
        dispatcher = int(row['regions']['dispatcher']['address'], 16)
        for label, pool, target in (
            ('9602_9812', 0x402e7e24, 'image_handler'),
            ('9614', 0x402e7e84, 'bulk_description_handler'),
        ):
            location = dispatcher + pool - 0x402e778c
            value = struct.unpack_from('<I', raw, location - BASE)[0]
            if value != int(row['regions'][target]['address'], 16):
                raise ValueError(f'Unexpected dispatch pointer: {version} {label}')
            row[label] = {'pointer_location': f'{location:08x}', 'target': f'{value:08x}'}
        result['versions'][version] = row
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    text = json.dumps(compare(), indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end='')
