#!/usr/bin/env python3
"""Build a raw eSD boot card image from the stock main firmware.

The card layout is the vendor `sdset` layout observed on the live camera:

  0x000000  eSD header (block 0, 32 KiB)
  0x020000  reserved A (2 MiB)
  0x220000  reserved B (2 MiB)
  0x420000  reserved C (1 MiB)
  0x510000  firmware slot 1 (block 0xa2)

Header fields follow the observed `sdset` output plus the `sdfww` slot
fields: slot-1 start +0x14, slot-2 start +0x18, slot-1 length +0x1c,
nrFw1 +0x38, type 0x32 at +0x40.  The vendor header writer's additive
checksum (0x4008b2a8 -> 0x40057d40) is applied at +0x40/+0x4a.

`--entry-200` patches the image's first branch from the reset vector
(+0xec0) to the DRAM-init entry (+0x200), matching the proven `0x04f7`
handoff entry.  This is a test variant: it changes only the first
instruction of the firmware copy on the card.

The output is a prefix image only (header + reserved area + slot 1);
writing it to the start of the card does not touch anything beyond
0x510000 + 456 * 0x8000.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FW = ROOT / "firmware-main.bin"

FW_SIZE = 14_910_464
FW_SHA256 = "80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"
BLK = 0x8000
SLOT1_BLOCK = 0xA2
NR_FW1 = (FW_SIZE + BLK - 1) // BLK          # 456
SLOT2_BLOCK = SLOT1_BLOCK + NR_FW1           # 0x26a
CARD_BLOCKS = 0x0EDC90                       # observed sdset +0x24

ENTRY_200 = 0xEA00007E  # b +0x200 from pc 0


def vendor_header_checksum(block: bytes) -> tuple[int, int]:
    """Return (halfword at +0x40, checksum at +0x4a)."""
    h = bytearray(block)
    h[0x40:0x42] = b"\0\0"
    h[0x4A:0x4C] = b"\0\0"
    s = sum(h) & 0xFFFF
    lo = s & 0xFF
    hi = (s >> 8) & 0xFF
    t = (lo + hi + 0x100) & 0xFF00
    at40 = ((t + s) & 0xFF00) | 0x32
    r2 = (t - (at40 >> 8) - lo) & 0xFFFF
    r3 = (r2 >> 1) & 0xFFFF
    r2 = (r2 + (r3 << 8)) & 0xFFFF
    at4a = (r2 - r3) & 0xFFFF
    return at40, at4a


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--entry-200", action="store_true")
    ap.add_argument("--marker", action="store_true")
    ap.add_argument("--raw-type", action="store_true")
    ap.add_argument("--out", default="analysis/output/esd-boot-card.bin")
    args = ap.parse_args()

    fw = bytearray(FW.read_bytes())
    if len(fw) != FW_SIZE or hashlib.sha256(fw).hexdigest() != FW_SHA256:
        raise SystemExit("firmware-main.bin is not the expected stock image")
    if args.entry_200:
        struct.pack_into("<I", fw, 0, ENTRY_200)
    if args.marker:
        replacements = {
            0xB929A5: (b"Digital Camera", b"XHALF-ESD-CAM "),
            0xB929B8: (
                b"FUJIFILM DIGITAL CAMERA FINEPIX X-HF1",
                b"XHALF-ESD-IMAGE" + b" " * 22,
            ),
            0xBB7305: (b"FUJIFILM-X-HF1", b"XHALF-ESD-XHF1"),
        }
        for off, (old, new) in replacements.items():
            if fw[off:off + len(old)] != old or len(new) != len(old):
                raise SystemExit(f"marker patch mismatch at 0x{off:x}")
            fw[off:off + len(new)] = new
        print("marker strings patched")

    header = bytearray(0x4000)
    header[0:8] = b"eSD RSV\x00"
    struct.pack_into("<H", header, 0x08, 0xBEEF)
    struct.pack_into("<I", header, 0x0C, BLK)
    struct.pack_into("<I", header, 0x14, SLOT1_BLOCK)
    struct.pack_into("<I", header, 0x18, SLOT2_BLOCK)
    struct.pack_into("<I", header, 0x1C, FW_SIZE)
    struct.pack_into("<I", header, 0x24, CARD_BLOCKS)
    struct.pack_into("<I", header, 0x28, 2)
    struct.pack_into("<I", header, 0x2C, 0x40)  # A = 2 MiB
    struct.pack_into("<I", header, 0x30, 0x40)  # B = 2 MiB
    struct.pack_into("<I", header, 0x34, 0x20)  # C = 1 MiB
    struct.pack_into("<I", header, 0x38, NR_FW1)
    struct.pack_into("<I", header, 0x3C, 0)
    header[0x40] = 0x32
    if args.raw_type:
        struct.pack_into("<H", header, 0x40, 0x0032)
        struct.pack_into("<H", header, 0x4A, 0)
        print("header: raw type 0x0032, +4a=0")
    else:
        at40, at4a = vendor_header_checksum(header)
        struct.pack_into("<H", header, 0x40, at40)
        struct.pack_into("<H", header, 0x4A, at4a)
        print(f"header: +40=0x{at40:04x} +4a=0x{at4a:04x}")

    total = SLOT1_BLOCK * BLK + NR_FW1 * BLK
    image = bytearray(total)
    image[0:0x4000] = header
    image[SLOT1_BLOCK * BLK:SLOT1_BLOCK * BLK + FW_SIZE] = fw

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(image)
    print(f"wrote {out} ({total} bytes)")
    print(f"sha256 {hashlib.sha256(image).hexdigest()}")
    print(f"first instruction 0x{struct.unpack_from('<I', fw, 0)[0]:08x}")
    print(f"slot1 block 0x{SLOT1_BLOCK:x} offset 0x{SLOT1_BLOCK * BLK:x}")


if __name__ == "__main__":
    main()
