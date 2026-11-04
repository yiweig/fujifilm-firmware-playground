#!/usr/bin/env python3
"""Bounded read-only layout review of the pinned public V50 SPI-flash dump.

All locations are FILE offsets. Header field roles are a shared-format hypothesis
cross-checked against independent filesystem geometry, not a CPU-ROM mapping.
Never mounts, extracts files, executes supplied code, or accesses a device.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import struct

DUMP_SHA = 'ef8ee75f9cd5a59c633b6b79ea0130c9605d3a86bfe2b9bbfc1498e51587fbf8'
PACKAGE_SHA = '1f9db54f710d26af1be9f96a317f838fa52e0ca90225f602c45b287e93f2a2f9'


def checked(path, expected, size=None):
    if not stat.S_ISREG(path.stat().st_mode):
        raise ValueError('input must be a regular retained file')
    if size is not None and path.stat().st_size != size:
        raise ValueError('unexpected retained file size')
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError('wrong retained artifact SHA256')
    return data


def word(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def volume(data, offset):
    boot = data[offset:offset + 512]
    bps = struct.unpack_from('<H', boot, 11)[0]
    spc, nf = boot[13], boot[16]
    reserved = struct.unpack_from('<H', boot, 14)[0]
    entries = struct.unpack_from('<H', boot, 17)[0]
    sectors = struct.unpack_from('<H', boot, 19)[0] or word(boot, 32)
    spf = struct.unpack_from('<H', boot, 22)[0]
    label = boot[54:62].decode('ascii')
    if (bps != 512 or not spc or spc & (spc - 1) or not reserved
            or nf != 2 or not spf or label not in ('FAT12   ', 'FAT16   ')
            or boot[510:512] != b'\x55\xaa'):
        raise ValueError('unsupported retained FAT geometry')
    end = offset + sectors * bps
    fat_start = offset + reserved * bps
    root = fat_start + nf * spf * bps
    first_data = root + ((entries * 32 + bps - 1) // bps) * bps
    if not offset < fat_start < root < first_data < end <= len(data):
        raise ValueError('FAT extent outside retained image')
    clusters = (end - first_data) // (spc * bps)
    fat = data[fat_start:fat_start + spf * bps]
    mirror = data[fat_start + spf * bps:fat_start + 2 * spf * bps]
    is12 = label.startswith('FAT12')
    if (is12 and not 0 < clusters < 4085) or (not is12 and not 4085 <= clusters < 65525):
        raise ValueError('FAT label/cluster count mismatch')
    if ((clusters + 2) * (3 if is12 else 4) + 1) // 2 > len(fat):
        raise ValueError('FAT is too short')
    free = 0
    for cluster in range(2, clusters + 2):
        if is12:
            i = cluster + cluster // 2
            value = int.from_bytes(fat[i:i + 2], 'little')
            value = (value >> 4 if cluster & 1 else value) & 0xfff
        else:
            value = struct.unpack_from('<H', fat, cluster * 2)[0]
        free += value == 0
    live = []
    for p in range(root, root + entries * 32, 32):
        row = data[p:p + 32]
        if row[0] == 0:
            break
        if row[0] == 0xe5 or row[11] == 0x0f or row[11] & 8:
            continue
        name = row[:8].decode('ascii', 'replace').rstrip()
        extension = row[8:11].decode('ascii', 'replace').rstrip()
        live.append(name + ('.' + extension if extension else ''))
    return {'start': hex(offset), 'end_exclusive': hex(end), 'type': label.strip(),
            'bytes_per_sector': bps, 'sectors_per_cluster': spc, 'sectors': sectors,
            'root_offset': hex(root), 'data_offset': hex(first_data),
            'fat_mirrors_equal': fat == mirror, 'cluster_count': clusters,
            'free_clusters': free, 'nonzero_cluster_entries': clusters - free,
            'allocation_count_basis': 'First FAT only; differing mirrors are not reconciled.',
            'root_live_short_names': live,
            'limit': 'Root metadata and allocation counts only; no recursion, file extraction or recovery of deleted content.'}


def inspect(dump, package):
    w = lambda p: word(dump, p)
    if dump[:8] != b'NAND RSV' or dump[0x4000:0x4008] != b'NAND RSV':
        raise ValueError('expected reserved-header markers')
    block, total = w(0x0c), w(0x24)
    header_blocks, a_blocks, b_blocks = w(0x28), w(0x2c), w(0x30)
    main_start, main_size = w(0x14) * block, w(0x1c)
    if block != 0x1000 or total * block != len(dump):
        raise ValueError('header geometry inconsistent with file size')
    a_start = header_blocks * block
    b_start = a_start + a_blocks * block
    if b_start + b_blocks * block != main_start:
        raise ValueError('resource extents do not meet selected firmware start')
    tail = main_start + w(0x38) * block
    volumes = [volume(dump, p) for p in (a_start, b_start, tail)]
    if [int(v['end_exclusive'], 16) for v in volumes] != [b_start, main_start, len(dump)]:
        raise ValueError('filesystem extents disagree with derived boundaries')
    if dump[main_start + 0x40:main_start + 0x48] != b'SPCA6500':
        raise ValueError('selected slot lacks expected platform marker')
    vector_candidates = [hex(m.start()) for m in re.finditer(rb'(?:[\x00-\xff]{3}\xea){7}', dump)
                         if m.start() % 4 == 0]
    erase_runs, start = [], None
    for p in range(0, len(dump), block):
        erased = dump[p:p + block] == b'\xff' * block
        if erased and start is None:
            start = p
        if not erased and start is not None:
            erase_runs.append([hex(start), hex(p)])
            start = None
    if start is not None:
        erase_runs.append([hex(start), hex(len(dump))])
    pkg_start, pkg_end = word(package, 0x1c), word(package, 0x24)
    if not 0 <= pkg_start < pkg_end <= len(package):
        raise ValueError('invalid selected update-package extent')
    image = dump[main_start:main_start + main_size]
    update_image = package[pkg_start:pkg_end]
    return {
        'source_repository': 'https://github.com/Linouth/iCatch-V50-Playground',
        'source_commit': 'dc437ced8fdc9564e39ca550af6a1d53b1037714',
        'dump_sha256': DUMP_SHA, 'dump_size': len(dump), 'package_sha256': PACKAGE_SHA,
        'coordinate_system': 'file offsets only; no CPU ROM/load mapping inferred',
        'header_field_hypothesis': {'block_size': block, 'total_blocks': total,
            'header_blocks': header_blocks, 'reserved_a_blocks': a_blocks,
            'reserved_b_blocks': b_blocks, 'reserved_c_blocks': w(0x34),
            'slot1_start_block': w(0x14), 'slot1_length': main_size,
            'slot1_reserved_blocks': w(0x38), 'slot2_start_block': w(0x18),
            'slot2_length': w(0x20), 'slot2_reserved_blocks': w(0x3c),
            'limit': 'Roles borrowed from related eSD/NAND schema and corroborated by actual region boundaries; not all header semantics/checksums decoded.'},
        'first_header_copy_difference_offsets': [hex(i) for i in range(0x4000)
                                                if dump[i] != dump[0x4000 + i]],
        'early_drampar_markers': [hex(m.start()) for m in re.finditer(b'DRAMPAR3', dump[:a_start])],
        'volumes': volumes,
        'selected_main': {'start': hex(main_start), 'declared_end_exclusive': hex(main_start + main_size),
            'reserved_end_exclusive': hex(tail), 'sha256_declared_bytes': hashlib.sha256(image).hexdigest(),
            'prefix_hex': image[:32].hex(),
            'markers': [image[0x40:0x4d].decode('ascii'), image[0x4e:0x57].decode('ascii')],
            'limit': 'DRAM image identification, not earlier-stage or CPU-ROM provenance.'},
        'package_main_comparison': {'start': hex(pkg_start), 'end_exclusive': hex(pkg_end),
            'sha256': hashlib.sha256(update_image).hexdigest(),
            'same_as_dump_prefix': image[:len(update_image)] == update_image,
            'same_length_as_declared_dump_main': len(image) == len(update_image)},
        'seven_aligned_arm_B_vector_encoding_candidates': vector_candidates,
        'all_ff_full_4k_runs': erase_runs,
        'result': 'Known layout resolves resource volumes and DRAM application. No authenticated low-address provider recovered; trailing free-cluster bytes are not a currently referenced boot executable.',
        'limits': ['Different camera/revision; no X-HF1 compatibility established.',
                   'FAT directory/allocation metadata does not assign provenance to unallocated bytes.',
                   'No extraction/decompression of all resources, general executable scan or complete code analysis.',
                   'Exact vector-encoding candidate search is not proof no other executable format exists.',
                   'No device operation, payload, vendor program, third-party script or emulator executes.']}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('corpus', type=Path)
    args = ap.parse_args()
    root = args.corpus / 'reversing/v50ProSE'
    try:
        dump = checked(root / 'winbond_dump.bin', DUMP_SHA, 16777216)
        package = checked(root / 'AKASO_V50 Pro SE_190315_V1/SPHOST.BRN', PACKAGE_SHA)
        print(json.dumps(inspect(dump, package), indent=2))
    except (OSError, ValueError, struct.error) as error:
        ap.error(str(error))


if __name__ == '__main__':
    main()
