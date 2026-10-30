#!/usr/bin/env python3
"""Inventory verified regions in the Fujifilm X half FPUPDATE.DAT package."""

from __future__ import annotations

import argparse
import hashlib
import json
import mmap
import re
import struct
import zlib
from datetime import datetime, timezone
from pathlib import Path


ZIMAGE_MAGIC = b"\x18\x28\x6f\x01"
FDT_MAGIC = b"\xd0\x0d\xfe\xed"
ELF_MAGIC = b"\x7fELF"
UBI_MAGIC = b"UBI#"
SQUASHFS_MAGIC = b"hsqs"
GZIP_MAGIC = b"\x1f\x8b\x08"


def sha256_range(data: mmap.mmap, start: int, size: int) -> str:
    digest = hashlib.sha256()
    end = start + size
    chunk_size = 1024 * 1024
    for position in range(start, end, chunk_size):
        digest.update(data[position : min(position + chunk_size, end)])
    return digest.hexdigest()


def all_offsets(data: mmap.mmap, needle: bytes) -> list[int]:
    result: list[int] = []
    position = 0
    while True:
        position = data.find(needle, position)
        if position < 0:
            return result
        result.append(position)
        position += 1


def region(name: str, start: int, end: int, basis: str, data: mmap.mmap) -> dict:
    return {
        "name": name,
        "start": f"0x{start:08x}",
        "end_exclusive": f"0x{end:08x}",
        "size": end - start,
        "sha256": sha256_range(data, start, end - start),
        "basis": basis,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("firmware", type=Path, nargs="?", default=Path("FPUPDATE.DAT"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("analysis/output/firmware-regions.json"),
    )
    args = parser.parse_args()

    with args.firmware.open("rb") as handle:
        data = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            if data[:14] != b"SUNP BURN FILE":
                raise SystemExit("not a SUNP BURN FILE package")

            declared_end, hdr1, hdr2, main_start, linux_start, hdr3, drampar = (
                struct.unpack_from("<7I", data, 0x10)
            )
            actual_size = len(data)
            if data[hdr1 : hdr1 + 16] != b"SUNP BURN HDR 1\0":
                raise SystemExit("HDR1 offset does not point to SUNP BURN HDR 1")
            if data[hdr2 : hdr2 + 16] != b"SUNP BURN HDR 2\0":
                raise SystemExit("HDR2 offset does not point to SUNP BURN HDR 2")
            if data[hdr3 : hdr3 + 16] != b"SUNP BURN HDR 3\0":
                raise SystemExit("HDR3 offset does not point to SUNP BURN HDR 3")
            if data[drampar : drampar + 8] != b"DRAMPAR3":
                raise SystemExit("DRAM parameter offset does not point to DRAMPAR3")

            manifest_text = bytes(data[linux_start : linux_start + 0x200]).split(
                b"\0", 1
            )[0]
            manifest_lines = manifest_text.decode("ascii").splitlines()
            if manifest_lines[0] != "firstburn" or len(manifest_lines) < 5:
                raise SystemExit("Linux region does not begin with the expected firstburn manifest")
            kernel_size = int(manifest_lines[1])
            rootfs_size = int(manifest_lines[2])
            manifest_value_3 = int(manifest_lines[3])
            manifest_value_4 = int(manifest_lines[4])

            zimage_start = data.find(ZIMAGE_MAGIC, linux_start, main_start) - 0x24
            if zimage_start < linux_start:
                raise SystemExit("ARM zImage header not found in Linux region")
            zimage_magic, zimage_load, zimage_end = struct.unpack_from(
                "<III", data, zimage_start + 0x24
            )
            if zimage_magic != 0x016F2818:
                raise SystemExit("invalid ARM zImage magic")
            zimage_size = zimage_end - zimage_load
            if zimage_size != kernel_size:
                raise SystemExit("zImage header size does not match firstburn manifest")

            squashfs_start = data.find(SQUASHFS_MAGIC, zimage_start + zimage_size, main_start)
            if squashfs_start < 0:
                raise SystemExit("SquashFS superblock not found")
            squash_fields = struct.unpack_from("<5I6H8Q", data, squashfs_start)
            squash_inode_count = squash_fields[1]
            squash_mkfs_time = squash_fields[2]
            squash_block_size = squash_fields[3]
            squash_compression = squash_fields[5]
            squash_major = squash_fields[9]
            squash_minor = squash_fields[10]
            squash_bytes_used = squash_fields[12]
            if squash_bytes_used != rootfs_size:
                raise SystemExit("SquashFS byte count does not match firstburn manifest")

            gzip_start = data.find(GZIP_MAGIC, zimage_start, zimage_start + zimage_size)
            kernel_version = None
            decompressed_kernel_size = None
            decompressed_fdt_offsets: list[str] = []
            if gzip_start >= 0:
                inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
                kernel = inflater.decompress(
                    data[gzip_start : zimage_start + zimage_size]
                ) + inflater.flush()
                decompressed_kernel_size = len(kernel)
                version_match = re.search(rb"Linux version [^\x00\n]+", kernel)
                if version_match:
                    kernel_version = version_match.group().decode("ascii", "replace")
                cursor = 0
                while True:
                    cursor = kernel.find(FDT_MAGIC, cursor)
                    if cursor < 0:
                        break
                    decompressed_fdt_offsets.append(f"0x{cursor:x}")
                    cursor += 1

            main_size = hdr3 - main_start
            report = {
                "artifact": {
                    "path": str(args.firmware),
                    "size": actual_size,
                    "sha256": sha256_range(data, 0, actual_size),
                    "format": "SUNP BURN FILE",
                    "declared_end": f"0x{declared_end:08x}",
                    "trailer_size": actual_size - declared_end,
                },
                "header_offsets": {
                    "hdr1": f"0x{hdr1:08x}",
                    "hdr2": f"0x{hdr2:08x}",
                    "linux_region": f"0x{linux_start:08x}",
                    "main_dram_image": f"0x{main_start:08x}",
                    "hdr3": f"0x{hdr3:08x}",
                    "drampar3": f"0x{drampar:08x}",
                },
                "firstburn_manifest": {
                    "kernel_size": kernel_size,
                    "rootfs_size": rootfs_size,
                    "value_3_unknown": manifest_value_3,
                    "value_4_unknown": manifest_value_4,
                },
                "kernel": {
                    "format": "ARM zImage",
                    "start": f"0x{zimage_start:08x}",
                    "size": zimage_size,
                    "header_load": f"0x{zimage_load:08x}",
                    "header_end": f"0x{zimage_end:08x}",
                    "gzip_start": f"0x{gzip_start:08x}" if gzip_start >= 0 else None,
                    "decompressed_size": decompressed_kernel_size,
                    "version": kernel_version,
                    "embedded_fdt_offsets": decompressed_fdt_offsets,
                    "sha256": sha256_range(data, zimage_start, zimage_size),
                },
                "rootfs": {
                    "format": f"SquashFS {squash_major}.{squash_minor}",
                    "start": f"0x{squashfs_start:08x}",
                    "size": squash_bytes_used,
                    "inode_count": squash_inode_count,
                    "block_size": squash_block_size,
                    "compression_id": squash_compression,
                    "created_utc": datetime.fromtimestamp(
                        squash_mkfs_time, timezone.utc
                    ).isoformat(),
                    "sha256": sha256_range(data, squashfs_start, squash_bytes_used),
                },
                "main_dram_image": {
                    "start": f"0x{main_start:08x}",
                    "size": main_size,
                    "runtime_base": "0x40000000",
                    "sha256": sha256_range(data, main_start, main_size),
                },
                "signatures": {
                    "elf_offsets": [f"0x{x:08x}" for x in all_offsets(data, ELF_MAGIC)],
                    "fdt_offsets": [f"0x{x:08x}" for x in all_offsets(data, FDT_MAGIC)],
                    "ubi_offsets": [f"0x{x:08x}" for x in all_offsets(data, UBI_MAGIC)],
                    "squashfs_offsets": [
                        f"0x{x:08x}" for x in all_offsets(data, SQUASHFS_MAGIC)
                    ],
                },
                "regions": [
                    region("package header and updater", 0, hdr1, "top-level header pointer", data),
                    region("SUNP BURN HDR 1", hdr1, hdr2, "top-level header pointer", data),
                    region("HDR2 opaque bulk payload", hdr2, linux_start, "top-level header pointer", data),
                    region("firstburn manifest/padding", linux_start, zimage_start, "manifest and zImage magic", data),
                    region("ARM zImage", zimage_start, zimage_start + zimage_size, "zImage header and manifest", data),
                    region("kernel-to-rootfs padding", zimage_start + zimage_size, squashfs_start, "adjacent verified regions", data),
                    region("SquashFS rootfs", squashfs_start, squashfs_start + squash_bytes_used, "superblock and manifest", data),
                    region("rootfs-to-main padding", squashfs_start + squash_bytes_used, main_start, "adjacent verified regions", data),
                    region("SPCA6500 DRAM CODE", main_start, hdr3, "top-level pointers and embedded header", data),
                    region("SUNP BURN HDR 3", hdr3, drampar, "top-level header pointer", data),
                    region("DRAMPAR3 and declared tail", drampar, declared_end, "top-level header pointer", data),
                    region("outer trailer", declared_end, actual_size, "actual size exceeds declared end", data),
                ],
            }

            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
            print(f"wrote {args.output}")
            print(f"kernel: 0x{zimage_start:08x}, {zimage_size} bytes")
            print(f"rootfs: 0x{squashfs_start:08x}, {squash_bytes_used} bytes")
            print(f"main:   0x{main_start:08x}, {main_size} bytes")
            if kernel_version:
                print(kernel_version)
        finally:
            data.close()


if __name__ == "__main__":
    main()
