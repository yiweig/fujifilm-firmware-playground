#!/usr/bin/env python3
"""Build a raw eSD card prefix containing the 512-byte chainloader stage.

Layout (vendor `sdset` geometry, A=2 MiB, B=2 MiB, C=1 MiB):

  0x000000  eSD header block
  0x020000  reserved A
  0x220000  reserved B
  0x420000  reserved C
  0x510000  slot 1 (block 0xa2), stage sector

Write the output to the start of the SD card (raw device), then run
`sdset 0x200000 0x200000 0x100000` in the loader shell to initialize
the SD device-1 context, and `sdboot 0x41070000`.

The card's FAT is overwritten.  The camera's NAND is untouched.

Usage:
  python3 build_stage_card.py [--stage stage.bin] [--out stage-card.bin]
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from build_esd_card import BLK, CARD_BLOCKS, vendor_header_checksum  # noqa: E402

SLOT1_BLOCK = 0xA2
SLOT1_OFF = SLOT1_BLOCK * BLK


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", type=Path, default=HERE / "stage.bin")
    ap.add_argument("--out", type=Path, default=HERE / "stage-card.bin")
    args = ap.parse_args()

    stage = args.stage.read_bytes()
    if len(stage) != 512:
        raise SystemExit("stage must be exactly 512 bytes")

    header = bytearray(0x4000)
    header[0:8] = b"eSD RSV\x00"
    struct.pack_into("<H", header, 0x08, 0xBEEF)
    struct.pack_into("<I", header, 0x0C, BLK)
    struct.pack_into("<I", header, 0x14, SLOT1_BLOCK)
    struct.pack_into("<I", header, 0x18, SLOT1_BLOCK + 1)
    struct.pack_into("<I", header, 0x1C, 512)
    struct.pack_into("<I", header, 0x24, CARD_BLOCKS)
    struct.pack_into("<I", header, 0x28, 2)
    struct.pack_into("<I", header, 0x2C, 0x40)  # A = 2 MiB
    struct.pack_into("<I", header, 0x30, 0x40)  # B = 2 MiB
    struct.pack_into("<I", header, 0x34, 0x20)  # C = 1 MiB
    struct.pack_into("<I", header, 0x38, 1)
    struct.pack_into("<I", header, 0x3C, 0)
    at40, at4a = vendor_header_checksum(header)
    struct.pack_into("<H", header, 0x40, at40)
    struct.pack_into("<H", header, 0x4A, at4a)

    image = bytearray(SLOT1_OFF + 512)
    image[0:len(header)] = header
    image[SLOT1_OFF:SLOT1_OFF + 512] = stage

    args.out.write_bytes(image)
    print(f"wrote {args.out} ({len(image)} bytes)")
    print(f"header +40=0x{at40:04x} +4a=0x{at4a:04x}")
    print(f"stage at 0x{SLOT1_OFF:x}, block 0x{SLOT1_BLOCK:x}")
    print(f"sha256 {hashlib.sha256(image).hexdigest()}")


if __name__ == "__main__":
    main()
