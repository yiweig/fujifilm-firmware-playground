"""Bounded ARM constant-address store search; candidates need manual validation.

Tracks common register definitions backward within straight-line instruction
windows, including MOVW/MOVT and split ADD/SUB addressing. Unknown definitions,
control-flow boundaries and caller-clobbered registers stop resolution.
Not a whole-program writer inventory, control-flow analysis or safety proof.
"""
import argparse
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import struct

BASE = 0x40000000
TARGET = 0x40e6696d
IMAGE_SHA256 = '80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923'


def ror(value, shift):
    shift %= 32
    return ((value >> shift) | (value << ((32-shift) % 32))) & 0xffffffff


def scan(data, window):
    def word(p):
        return struct.unpack_from('<I', data, p)[0]

    def operand(w, p, floor):
        if w & (1 << 25):
            return ror(w & 255, ((w >> 8) & 15)*2)
        if w & 16:  # variable shifts and special encodings unresolved
            return None
        value = resolve(w & 15, p, floor)
        if value is None:
            return None
        shift, kind = (w >> 7) & 31, (w >> 5) & 3
        if kind == 0:
            return (value << shift) & 0xffffffff
        if kind == 1:
            return value >> (shift or 32)
        if kind == 2:
            signed = value if value < 0x80000000 else value-0x100000000
            return (signed >> (shift or 32)) & 0xffffffff
        return ror(value, shift) if shift else None  # RRX needs carry

    @lru_cache(maxsize=150000)
    def resolve(reg, before, floor):
        if reg == 15:
            return BASE + before + 8
        if reg >= 13:
            return None
        for p in range(before-4, max(floor, 0)-1, -4):
            w = word(p)
            cond, group = w >> 28, (w >> 25) & 7
            if cond == 15:
                return None
            if w & 0x0ffffff0 in (0x012fff10, 0x012fff30):
                if w & 0x0ffffff0 == 0x012fff10 or reg in (0, 1, 2, 3, 12):
                    return None
                continue
            if group == 5:
                if not w & (1 << 24) or reg in (0, 1, 2, 3, 12):
                    return None
                continue
            rn, rd = (w >> 16) & 15, (w >> 12) & 15
            if group in (2, 3):
                if ((w & (1 << 21)) or not w & (1 << 24)) and rn == reg:
                    return None
                if w & (1 << 20) and rd == reg:
                    if cond == 14 and group == 2 and rn == 15 and w & (1 << 24) and not w & (1 << 22):
                        q = p + 8 + ((w & 0xfff) if w & (1 << 23) else -(w & 0xfff))
                        return word(q) if 0 <= q <= len(data)-4 else None
                    return None
                continue
            if group == 4:
                if (w & (1 << 20) and w & (1 << reg)) or (w & (1 << 21) and rn == reg):
                    return None
                continue
            if group not in (0, 1):
                return None
            if group == 0 and w & 0x0e0000f0 == 0x000000b0:
                # Immediate/register STRH/LDRH. Non-writeback STRH preserves rn.
                if ((w & (1 << 21)) or not w & (1 << 24)) and rn == reg:
                    return None
                if w & (1 << 20) and rd == reg:
                    return None
                continue
            # Multiply/exclusive and other special encodings are barriers
            # for either plausible destination. This intentionally misses cases.
            if group == 0 and w & 0x90 == 0x90:
                if reg in (rn, rd):
                    return None
                continue
            if w & 0x0ff00000 in (0x03000000, 0x03400000):
                if rd != reg:
                    continue
                if cond != 14:
                    return None
                imm = ((w >> 4) & 0xf000) | (w & 0xfff)
                if w & 0x0ff00000 == 0x03000000:
                    return imm
                prior = resolve(reg, p, floor)
                return ((imm << 16) | (prior & 0xffff)) if prior is not None else None
            op = (w >> 21) & 15
            if op in (8, 9, 10, 11) and w & (1 << 20):
                continue  # TST/TEQ/CMP/CMN do not write a general register
            if rd != reg:
                continue
            if cond != 14:
                return None
            rhs = operand(w, p, floor)
            if rhs is None:
                return None
            if op == 13:
                return rhs
            if op == 15:
                return rhs ^ 0xffffffff
            lhs = resolve(rn, p, floor)
            if lhs is None:
                return None
            if op == 0: return lhs & rhs
            if op == 1: return lhs ^ rhs
            if op == 2: return (lhs-rhs) & 0xffffffff
            if op == 4: return (lhs+rhs) & 0xffffffff
            if op == 12: return lhs | rhs
            if op == 14: return lhs & (rhs ^ 0xffffffff)
            return None
        return None

    hits, candidates, bulk_hits, bulk_near = [], 0, [], []
    bulk_stats = {'direct_branch_encodings': 0, 'resolved_destination_and_size': 0}
    for p in range(0, min(len(data)-3, 0xab0000), 4):
        w = word(p)
        if w >> 28 == 15:
            continue
        floor = max(0, p-window*4)
        if w & 0x0e000000 == 0x0a000000:
            displacement = w & 0xffffff
            if displacement & 0x800000:
                displacement -= 0x1000000
            target = (BASE+p+8+4*displacement) & 0xffffffff
            if target in (0x40008b8c, 0x4000cdf0):
                bulk_stats['direct_branch_encodings'] += 1
                dst, size = resolve(0, p, floor), resolve(2, p, floor)
                row = {'instruction': f'0x{BASE+p:08x}', 'helper': f'0x{target:08x}',
                       'destination': None if dst is None else f'0x{dst:08x}', 'size': size}
                if dst is not None and size is not None:
                    bulk_stats['resolved_destination_and_size'] += 1
                    if dst <= TARGET < dst+size and dst+size <= 0x100000000:
                        bulk_hits.append(row)
                elif dst is not None and TARGET-0x2000 <= dst <= TARGET:
                    bulk_near.append(row)
        rn, rd = (w >> 16) & 15, (w >> 12) & 15
        kind = None
        if w & 0x0c100000 == 0x04000000:
            kind = 'STRB' if w & (1 << 22) else 'STR'
            width = 1 if w & (1 << 22) else 4
            delta = operand(w & ~(1 << 25), p, floor) if w & (1 << 25) else w & 0xfff
        elif w & 0x0e1000f0 in (0x000000b0, 0x000000f0):
            kind = 'STRH' if w & 0xf0 == 0xb0 else 'STRD'
            width = 2 if kind == 'STRH' else 8
            delta = (((w >> 4) & 0xf0) | (w & 15)) if w & (1 << 22) else resolve(w & 15, p, floor)
        elif w & 0x0e100000 == 0x08000000 and w & 0xffff:
            kind, width, delta = 'STM', (w & 0xffff).bit_count()*4, 0
        if kind is None:
            continue
        candidates += 1
        base = resolve(rn, p, floor)
        if base is None:
            continue
        if delta is None:
            continue
        address = base
        if kind == 'STM':
            address = (base + (4 if w & (1 << 24) else 0)) if w & (1 << 23) else (base-width+(0 if w & (1 << 24) else 4))
        elif w & (1 << 24):
            address = (base + (delta if w & (1 << 23) else -delta)) & 0xffffffff
        if address <= TARGET < address+width:
            hits.append({"instruction": f"0x{BASE+p:08x}", "word": f"0x{w:08x}",
                         "kind": kind, "address": f"0x{address:08x}", "width": width,
                         "value": resolve(rd, p, floor) if kind != 'STM' else None, "condition": w >> 28})
    return candidates, hits, bulk_stats, bulk_hits, bulk_near


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, default=Path('firmware-main.bin'))
    parser.add_argument('--window', type=int, default=96)
    args = parser.parse_args()
    if not 1 <= args.window <= 256:
        parser.error('window must be 1..256 instructions')
    data = args.image.read_bytes()
    if hashlib.sha256(data).hexdigest() != IMAGE_SHA256:
        parser.error('This target address/helper profile requires the official 2.00 main-image hash')
    candidates, hits, bulk_stats, bulk_hits, bulk_near = scan(data, args.window)
    print(json.dumps({"image_sha256": hashlib.sha256(data).hexdigest(),
                      "target": f"0x{TARGET:08x}", "window_instructions": args.window,
                      "scan_end_exclusive": f"0x{BASE+min(len(data),0xab0000):08x}",
                      "candidate_store_encodings": candidates, "hits": hits,
                      "bulk_call_statistics": bulk_stats, "bulk_overlap_hits": bulk_hits,
                      "nearby_bulk_destinations_with_unknown_size": bulk_near,
                      "limitations": "Aligned ARM STR/STRB/STRH/STRD/STM and direct B/BL to two known copy/clear helpers, including possible data encodings; bounded backward constant resolution; no CFG, Thumb, exclusive/VFP stores, other/indirect bulk helpers, mutable-pointer dereference or runtime evidence. Manually validate every hit; missed writes remain possible."}, indent=2))


if __name__ == '__main__':
    main()
