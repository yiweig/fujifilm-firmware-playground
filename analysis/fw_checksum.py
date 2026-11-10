#!/usr/bin/env python3
"""Compute/repair the X half firmware image checksums.

Two algorithms are used by the main image/updater:

- byte_sum16(data)  -> 16-bit byte sum (updater checker 0x40057d40)
- word_sum32(data)  -> 32-bit additive word sum with a shifted tail
                       (file-loader checker 0x4006b130)

The installed Firmware1 expected value is:

    (u16(header + 0x42) + u16(header + 0x44)) & 0xffff

The updater invalidates Firmware1 by adding 2 to +0x42 and restores it
on success.  To repair a modified image, set one of +0x42/+0x44 so the
pair sum equals byte_sum16(image).

Usage:
  python3 fw_checksum.py --image firmware-main.bin
  python3 fw_checksum.py --image firmware-main.bin --header nand-header.bin
  python3 fw_checksum.py --image firmware-main.bin --header nand-header.bin --write out.bin
"""
from __future__ import annotations

import argparse
import struct
from pathlib import Path


def byte_sum16(data: bytes) -> int:
    return sum(data) & 0xFFFF


def word_sum32(data: bytes) -> int:
    s = 0
    n = len(data) // 4
    for (w,) in struct.iter_unpack("<I", data[: n * 4]):
        s = (s + w) & 0xFFFFFFFF
    for i, b in enumerate(data[n * 4:]):
        s = (s + (b << ((i & 3) * 8))) & 0xFFFFFFFF
    return s


def header_pair(header: bytes) -> int:
    a = struct.unpack_from("<H", header, 0x42)[0]
    b = struct.unpack_from("<H", header, 0x44)[0]
    return (a + b) & 0xFFFF


def repair_header(header: bytearray, image: bytes) -> tuple[int, int]:
    """Set +0x42/+0x44 so their sum equals the image byte sum.

    Keep +0x42 and put the remainder in +0x44. Returns (new42, new44).
    """
    total = byte_sum16(image)
    a = struct.unpack_from("<H", header, 0x42)[0]
    b = (total - a) & 0xFFFF
    struct.pack_into("<H", header, 0x44, b)
    return a, b


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True, type=Path)
    ap.add_argument("--header", type=Path)
    ap.add_argument("--write", type=Path)
    args = ap.parse_args()

    image = args.image.read_bytes()
    print(f"image {args.image}: {len(image)} bytes")
    print(f"byte_sum16 0x{byte_sum16(image):04x}")
    print(f"word_sum32 0x{word_sum32(image):08x}")

    if args.header:
        header = bytearray(args.header.read_bytes())
        if len(header) < 0x50:
            raise SystemExit("header shorter than 0x50")
        print(f"header pair before 0x{header_pair(header):04x}")
        a, b = repair_header(header, image)
        print(f"header pair after  0x{header_pair(header):04x} (+0x42=0x{a:04x} +0x44=0x{b:04x})")
        if args.write:
            args.write.write_bytes(header)
            print(f"wrote {args.write}")


if __name__ == "__main__":
    main()
