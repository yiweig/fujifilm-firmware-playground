#!/usr/bin/env python3
"""Read selected metadata from the pinned V50 package; no execution or USB I/O."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from inspect_v50_flash_layout import checked, PACKAGE_SHA

ISP_SHA = '2cd3549aac079e1cffdbe6408f8a31c8127db56f635fb6398d26fbebf7c47aa0'
BASE = 0x40040000  # Source-research mapping, corroborated by startup VBAR write.


def word(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def inspect(package):
    if hashlib.sha256(package).hexdigest() != PACKAGE_SHA:
        raise ValueError('wrong pinned V50 package SHA256')
    if package[:14] != b'SUNP BURN FILE' or word(package, 0x10) != len(package):
        raise ValueError('unexpected package header')
    starts = [0x200] + [word(package, p) for p in range(0x14, 0x2c, 4)]
    nonzero = [p for p in starts if p]
    if nonzero != sorted(set(nonzero)) or nonzero[-1] >= len(package):
        raise ValueError('invalid package region order')
    isp = package[starts[0]:starts[1]]
    if hashlib.sha256(isp).hexdigest() != ISP_SHA:
        raise ValueError('wrong complete ISP slice')
    vector = word(isp, 0)
    if vector >> 24 != 0xea:
        raise ValueError('unexpected ARM reset branch')
    displacement = vector & 0xffffff
    if displacement & 0x800000:
        displacement -= 0x1000000
    reset_offset = 8 + 4 * displacement
    copy_start, copy_end = word(isp, 0x4a4), word(isp, 0x4a8)
    if not BASE <= copy_start < copy_end <= BASE + len(isp):
        raise ValueError('copy source outside retained ISP')
    copy = isp[copy_start - BASE:copy_end - BASE]
    device_offset = 0x8bf6c
    device = isp[device_offset:device_offset + 18]
    if device[:2] != b'\x12\x01' or word(isp, 0xe100) != BASE + device_offset:
        raise ValueError('unexpected descriptor/getter literal')
    offset1 = package[starts[1]:starts[2]]
    regions = []
    for index, start in enumerate(starts):
        if not start:
            continue
        end = next((p for p in starts[index + 1:] if p), len(package))
        regions.append({'source_label': 'offset' + str(index), 'start': hex(start),
                        'end_exclusive': hex(end), 'size': end - start,
                        'prefix_hex': package[start:start + 16].hex()})
    return {
        'package_sha256': PACKAGE_SHA, 'package_size': len(package),
        'regions': regions,
        'isp': {'package_start': '0x200', 'package_end_exclusive': hex(starts[1]),
                'size': len(isp), 'sha256': ISP_SHA, 'analysis_base': hex(BASE),
                'reset_offset': hex(reset_offset), 'reset_address': hex(BASE + reset_offset),
                'vbar_write_bytes': isp[0x370:0x37c].hex(),
                'vector_copy': {'source_start': hex(copy_start),
                                'source_end_exclusive': hex(copy_end),
                                'destination_start': '0x40000000',
                                'destination_end_exclusive': hex(0x40000000 + len(copy)),
                                'sha256': hashlib.sha256(copy).hexdigest()},
                'banner': {'format': '|  iCatch ISP Version [%s.%s]  |',
                           'arguments': [isp[0x836e4:0x836e8].decode(),
                                         isp[0x836e9:0x836ed].decode()],
                           'function': '0x4004fc30'},
                'stored_device_descriptor': {'file_offset_in_isp': hex(device_offset),
                                             'analysis_address': hex(BASE + device_offset),
                                             'hex': device.hex(),
                                             'vid': f'{struct.unpack_from("<H", device, 8)[0]:04x}',
                                             'pid': f'{struct.unpack_from("<H", device, 10)[0]:04x}',
                                             'getter': '0x4004e0b8'}},
        'offset1': {'size': len(offset1),
                    'nonzero_byte_count': sum(x != 0 for x in offset1),
                    'nonzero_bytes': [{'offset': hex(i), 'value': hex(x)}
                                      for i, x in enumerate(offset1) if x]},
        'limits': ['Selected bytes/literals under a pinned mapping, with code interpretation in the companion REA report.',
                   'An ISP executable is not an authenticated internal boot-ROM dump.',
                   'Stored USB identity does not prove live enumeration, retail entry, protocol or payload compatibility.',
                   'No X-half provider at low 0xb34 recovered; no complete ISP side-effect audit.']}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('package', type=Path)
    args = ap.parse_args()
    try:
        package = checked(args.package, PACKAGE_SHA, 15740152)
        print(json.dumps(inspect(package), indent=2))
    except (OSError, ValueError, struct.error) as error:
        ap.error(str(error))


if __name__ == '__main__':
    main()
