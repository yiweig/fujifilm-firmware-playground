#!/usr/bin/env python3
"""Decode the 2.00 staged-loader RAM-fill bounds. Offline; no hardware access."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent.parent
EXPECTED = '80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923'
BASE, CALLBACK = 0x40000000, 0x4136b664


def inspect():
    raw = (ROOT / 'firmware-main.bin').read_bytes()
    if hashlib.sha256(raw).hexdigest() != EXPECTED:
        raise ValueError('Expected official 2.00 main image')
    if struct.unpack_from('<I', raw, 0xf374)[0] != BASE + 0xb00:
        raise ValueError('Unexpected staged-loader table pointer')
    table = struct.unpack_from('<48I', raw, 0xb00)
    stages = [bit + 1 for bit in range(32) if table[1] & (1 << bit)]
    if table[0] != 6 or len(stages) != table[0]:
        raise ValueError('Unexpected stage count/mask')
    previous = (table[11] + 63) & ~63
    initial_clear = {
        'entry_slice': '40000684..400006c4',
        'cpu_start': f'{BASE + table[7]:08x}',
        'cpu_end_exclusive': f'{BASE + previous:08x}',
        'length': previous - table[7],
        'scope': 'CPU stores on the loader-facing 40000338 path, after SRAM-code copy; not established for every possible entry route.',
    }
    fills = []
    for ordinal, stage in enumerate(stages):
        if ordinal == 0:
            continue  # Worker skips fill/load processing on its first iteration.
        field = 0xb00 + 4 * (ordinal * 4 + 11)
        end = (table[ordinal * 4 + 11] + 63) & ~63
        if not 0 <= previous < end < BASE:
            raise ValueError('Invalid RAM-fill offset bounds')
        fills.append({
            'stage': stage, 'table_end_field': f'{BASE + field:08x}',
            'cpu_start': f'{BASE + previous:08x}',
            'cpu_end_exclusive': f'{BASE + end:08x}',
            'length': end - previous, 'requested_fill_value': 0,
            'contains_callback_word': BASE + previous <= CALLBACK and CALLBACK + 4 <= BASE + end,
        })
        previous = end
    if sum(f['contains_callback_word'] for f in fills) != 1:
        raise ValueError('No unique containing interval')
    return {
        'image_sha256': EXPECTED, 'table': '40000b00', 'worker': '4000f064',
        'stage_mask': f'{table[1]:08x}', 'stage_ids': stages,
        'first_iteration_skips_fill': True, 'callback': f'{CALLBACK:08x}',
        'initial_cpu_clear': initial_clear,
        'fills': fills,
        'scope': 'Static table/argument derivation, not observed hardware writes; address translation, DMA completion and later mutations remain separate qualifications.',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(inspect(), indent=2) + '\n'
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end='')
