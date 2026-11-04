#!/usr/bin/env python3
"""Compare pinned early application entry/selector slices, without device I/O."""
import hashlib
import json
import struct

from compare_ptp_dispatch_versions import IMAGES, ROOT

BASE = 0x40000000


def word(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def branch(data, offset, link=True):
    w = word(data, offset)
    if (w & 0x0f000000) != (0x0b000000 if link else 0x0a000000):
        raise ValueError('expected ARM branch encoding')
    displacement = w & 0xffffff
    if displacement & 0x800000:
        displacement -= 0x1000000
    return BASE + offset + 8 + 4 * displacement


def compare():
    records, bodies = {}, {}
    for version, (path, expected) in IMAGES.items():
        data = (ROOT / path).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('wrong image SHA256: ' + version)
        entry = branch(data, 0x39ac) - BASE
        ends = [p + 4 for p in range(entry, entry + 0x180, 4)
                if word(data, p) == 0xe8bd807c]
        if not ends or ends[0] - entry not in (0xcc, 0xd8):
            raise ValueError('unexpected early selector boundary')
        end = ends[0]
        load = word(data, entry + 4)
        if load & 0xfffff000 != 0xe59f5000:
            raise ValueError('unexpected selector state literal load')
        pool = entry + 12 + (load & 0xfff)
        if pool != end or word(data, pool + 4) != 0x10005000:
            raise ValueError('unexpected selector pool')
        body = data[entry:end]
        bodies[version] = body
        if word(data, 0x374c) != word(data, 0x3b54):
            raise ValueError('selector producer/consumer flag pointers differ')
        gate = data[0xf7c:0xfb8]
        reset = data[0xec0:0x1038]
        records[version] = {
            'image_sha256': expected,
            'reset_vector_target': hex(branch(data, 0, False)),
            'reset_body_sha256': hashlib.sha256(reset).hexdigest(),
            'reset_guard_range': ['0x40000f7c', '0x40000fb8'],
            'reset_guard_sha256': hashlib.sha256(gate).hexdigest(),
            'selector_call': '0x400039ac', 'selector': hex(BASE + entry),
            'selector_end_exclusive': hex(BASE + end), 'selector_code_bytes': len(body),
            'selector_code_sha256': hashlib.sha256(body).hexdigest(),
            'state_base': hex(word(data, pool)),
            'record_reader': hex(branch(data, entry + 0x1c)),
            'power_getter': hex(branch(data, entry + 0x28)),
            'saved_bits_13_14_filter_encoding': body[0xa4:0xb0].hex() if len(body) == 0xd8 else None,
            'successful_auxiliary_flag_offset': hex(word(data, entry + (0xb4 if len(body) == 0xd8 else 0xa8)) & 0xfff),
            'selector_result_flag': hex(word(data, 0x3b54)),
            'selector_consumer_range': ['0x400035fc', '0x4000361c'],
            'early_shell_call': hex(branch(data, 0x360c)),
            'normal_initialization_call': hex(branch(data, 0x3700)),
            'bootstrap_calls_before_selector': [
                {'site': hex(BASE + o), 'target': hex(branch(data, o))}
                for o in (0x399c, 0x39a0, 0x39a4, 0x39a8, 0x39ac)],
        }
    newer_a, newer_b = bodies['1.30'], bodies['2.00']
    return {'scope': 'Pinned reset guard, early-shell producer/consumer and selected bootstrap calls. Not an exhaustive boot graph or boot-ROM inventory.',
            'versions': records,
            'comparisons': {
                'reset_body_identical_all_four': len({r['reset_body_sha256'] for r in records.values()}) == 1,
                'guard_identical_all_four': len({r['reset_guard_sha256'] for r in records.values()}) == 1,
                'selector_code_1_01_equals_1_20': bodies['1.01'] == bodies['1.20'],
                'selector_code_differences_1_30_vs_2_00': [
                    {'relative_offset': hex(p), '1.30': hex(word(newer_a, p)), '2.00': hex(word(newer_b, p))}
                    for p in range(0, len(newer_a), 4) if newer_a[p:p + 4] != newer_b[p:p + 4]],
            },
            'limits': ['Hardware bit names and physical button meanings not established by these slices.',
                       'No live retained-state values, earlier-stage bytes or qualified loader entry.',
                       'Selector controls separately execute original ARM with supplied state and modeled record/power reads.']}


if __name__ == '__main__':
    print(json.dumps(compare(), indent=2))
