#!/usr/bin/env python3
"""List a SquashFS v4 directory tree without external tools.

This intentionally implements only the metadata needed for a read-only file
inventory. It supports zlib-compressed metadata, which is what the X half
update contains. It can read a filesystem embedded at an offset in a larger
container, so no carved binary is required.
"""

from __future__ import annotations

import argparse
import csv
import mmap
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path


SQUASHFS_MAGIC = 0x73717368
DIR_TYPES = {1, 8}
FILE_TYPES = {2, 9}
SYMLINK_TYPES = {3, 10}
INVALID_FRAGMENT = 0xFFFFFFFF
TYPE_NAMES = {
    1: "dir",
    2: "file",
    3: "symlink",
    4: "block",
    5: "char",
    6: "fifo",
    7: "socket",
    8: "dir",
    9: "file",
    10: "symlink",
    11: "block",
    12: "char",
    13: "fifo",
    14: "socket",
}


@dataclass(frozen=True)
class Directory:
    start_block: int
    offset: int
    size: int


class SquashFS:
    def __init__(self, data: mmap.mmap, base: int) -> None:
        self.data = data
        self.base = base
        fields = struct.unpack_from("<5I6H8Q", data, base)
        (
            magic,
            self.inode_count,
            self.mkfs_time,
            self.block_size,
            self.fragment_count,
            self.compression,
            self.block_log,
            self.flags,
            self.id_count,
            self.major,
            self.minor,
            self.root_inode,
            self.bytes_used,
            self.id_table_start,
            self.xattr_table_start,
            self.inode_table_start,
            self.directory_table_start,
            self.fragment_table_start,
            self.export_table_start,
        ) = fields
        if magic != SQUASHFS_MAGIC:
            raise ValueError(f"not a little-endian SquashFS image at 0x{base:x}")
        if (self.major, self.minor) != (4, 0):
            raise ValueError(f"unsupported SquashFS version {self.major}.{self.minor}")
        if self.compression != 1:
            raise ValueError(
                f"unsupported compression id {self.compression}; only zlib is implemented"
            )

    def metadata_block(self, table_start: int, block_offset: int) -> tuple[bytes, int]:
        position = self.base + table_start + block_offset
        header = struct.unpack_from("<H", self.data, position)[0]
        stored_size = header & 0x7FFF
        uncompressed = bool(header & 0x8000)
        payload = self.data[position + 2 : position + 2 + stored_size]
        decoded = bytes(payload) if uncompressed else zlib.decompress(payload)
        return decoded, block_offset + 2 + stored_size

    def data_block(self, block_offset: int, size_field: int) -> bytes:
        stored_size = size_field & 0x00FFFFFF
        if stored_size == 0:
            return bytes(self.block_size)
        payload = self.data[
            self.base + block_offset : self.base + block_offset + stored_size
        ]
        uncompressed = bool(size_field & 0x01000000)
        return bytes(payload) if uncompressed else zlib.decompress(payload)

    def metadata_stream(
        self, table_start: int, block_offset: int, inner_offset: int, size: int
    ) -> bytes:
        output = bytearray()
        current_block = block_offset
        current_inner = inner_offset
        while len(output) < size:
            decoded, next_block = self.metadata_block(table_start, current_block)
            if current_inner > len(decoded):
                raise ValueError(
                    f"metadata offset 0x{current_inner:x} exceeds block size 0x{len(decoded):x}"
                )
            take = min(size - len(output), len(decoded) - current_inner)
            output.extend(decoded[current_inner : current_inner + take])
            if take == 0 and len(output) < size:
                raise ValueError("empty metadata block while reading directory")
            current_block = next_block
            current_inner = 0
        return bytes(output)

    def inode_bytes(self, reference: int) -> tuple[bytes, int]:
        block_offset = reference >> 16
        inner_offset = reference & 0xFFFF
        decoded, _ = self.metadata_block(self.inode_table_start, block_offset)
        if inner_offset + 16 > len(decoded):
            raise ValueError(f"inode reference 0x{reference:x} falls outside metadata block")
        return decoded, inner_offset

    def inode_type(self, reference: int) -> int:
        decoded, offset = self.inode_bytes(reference)
        return struct.unpack_from("<H", decoded, offset)[0]

    def fragment_entry(self, fragment_index: int) -> tuple[int, int]:
        entry_offset = fragment_index * 16
        metadata_index = entry_offset // 8192
        inner_offset = entry_offset % 8192
        index_position = self.base + self.fragment_table_start + metadata_index * 8
        metadata_position = struct.unpack_from("<Q", self.data, index_position)[0]
        decoded, _ = self.metadata_block(metadata_position, 0)
        start_block, size_field, _unused = struct.unpack_from(
            "<QII", decoded, inner_offset
        )
        return start_block, size_field

    def read_file(self, reference: int) -> bytes:
        decoded, offset = self.inode_bytes(reference)
        inode_type = struct.unpack_from("<H", decoded, offset)[0]
        if inode_type == 2:
            start_block, fragment, fragment_offset, file_size = struct.unpack_from(
                "<IIII", decoded, offset + 16
            )
            block_list_offset = offset + 32
        elif inode_type == 9:
            (
                start_block,
                file_size,
                _sparse,
                _nlink,
                fragment,
                fragment_offset,
                _xattr,
            ) = struct.unpack_from("<QQQIIII", decoded, offset + 16)
            block_list_offset = offset + 56
        else:
            raise ValueError(f"inode 0x{reference:x} is not a regular file")

        full_blocks = file_size // self.block_size
        tail_size = file_size % self.block_size
        stored_blocks = full_blocks + (1 if tail_size and fragment == INVALID_FRAGMENT else 0)
        output = bytearray()
        data_position = start_block
        for index in range(stored_blocks):
            size_field = struct.unpack_from(
                "<I", decoded, block_list_offset + index * 4
            )[0]
            block = self.data_block(data_position, size_field)
            wanted = min(self.block_size, file_size - len(output))
            output.extend(block[:wanted])
            data_position += size_field & 0x00FFFFFF

        if fragment != INVALID_FRAGMENT and len(output) < file_size:
            fragment_start, fragment_size = self.fragment_entry(fragment)
            fragment_data = self.data_block(fragment_start, fragment_size)
            wanted = file_size - len(output)
            output.extend(fragment_data[fragment_offset : fragment_offset + wanted])

        if len(output) != file_size:
            raise ValueError(
                f"decoded 0x{len(output):x} bytes for file of size 0x{file_size:x}"
            )
        return bytes(output)

    def read_symlink(self, reference: int) -> str:
        decoded, offset = self.inode_bytes(reference)
        inode_type = struct.unpack_from("<H", decoded, offset)[0]
        if inode_type not in SYMLINK_TYPES:
            raise ValueError(f"inode 0x{reference:x} is not a symlink")
        _nlink, target_size = struct.unpack_from("<II", decoded, offset + 16)
        target = decoded[offset + 24 : offset + 24 + target_size]
        return target.decode("utf-8", "replace")

    def directory_inode(self, reference: int) -> Directory:
        decoded, offset = self.inode_bytes(reference)
        inode_type = struct.unpack_from("<H", decoded, offset)[0]
        if inode_type == 1:
            start_block, _nlink, size, inner, _parent = struct.unpack_from(
                "<IIHHI", decoded, offset + 16
            )
        elif inode_type == 8:
            _nlink, size, start_block, _parent, _index_count, inner, _xattr = (
                struct.unpack_from("<IIIIHHI", decoded, offset + 16)
            )
        else:
            raise ValueError(
                f"inode 0x{reference:x} is type {inode_type}, not a directory"
            )
        return Directory(start_block=start_block, offset=inner, size=size)

    def directory_entries(self, reference: int) -> list[tuple[str, int, int]]:
        directory = self.directory_inode(reference)
        raw = self.metadata_stream(
            self.directory_table_start,
            directory.start_block,
            directory.offset,
            directory.size,
        )
        entries: list[tuple[str, int, int]] = []
        cursor = 0
        while cursor + 12 <= len(raw):
            count_minus_one, inode_block, inode_base = struct.unpack_from(
                "<III", raw, cursor
            )
            cursor += 12
            count = count_minus_one + 1
            if count > 256:
                raise ValueError(f"implausible directory entry count {count}")
            for _ in range(count):
                if cursor + 8 > len(raw):
                    return entries
                inode_offset, inode_delta, inode_type, name_minus_one = struct.unpack_from(
                    "<HhHH", raw, cursor
                )
                cursor += 8
                name_size = name_minus_one + 1
                if cursor + name_size > len(raw):
                    return entries
                name = raw[cursor : cursor + name_size].decode("utf-8", "replace")
                cursor += name_size
                inode_number = inode_base + inode_delta
                del inode_number  # useful for validation later, not needed for traversal
                child_reference = (inode_block << 16) | inode_offset
                entries.append((name, inode_type, child_reference))
        return entries

    def walk(self) -> list[tuple[str, int, int]]:
        result: list[tuple[str, int, int]] = []
        seen: set[int] = set()

        def visit(path: str, reference: int) -> None:
            if reference in seen:
                return
            seen.add(reference)
            for name, inode_type, child_reference in self.directory_entries(reference):
                child_path = f"{path}/{name}" if path else name
                result.append((child_path, inode_type, child_reference))
                if inode_type in DIR_TYPES:
                    visit(child_path, child_reference)

        visit("", self.root_inode)
        return result

    def paths(self) -> dict[str, tuple[int, int]]:
        return {
            path: (inode_type, reference)
            for path, inode_type, reference in self.walk()
        }


def parse_int(value: str) -> int:
    return int(value, 0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("--offset", type=parse_int, default=0)
    parser.add_argument("--details", action="store_true")
    parser.add_argument(
        "--read",
        action="append",
        default=[],
        metavar="PATH",
        help="print a regular text file or symlink target; may be repeated",
    )
    parser.add_argument(
        "--inventory-output",
        type=Path,
        help="write the complete path/type/inode-reference inventory as TSV",
    )
    args = parser.parse_args()

    with args.path.open("rb") as handle:
        data = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            filesystem = SquashFS(data, args.offset)
            print(
                f"SquashFS {filesystem.major}.{filesystem.minor}: "
                f"{filesystem.inode_count} inodes, {filesystem.bytes_used} bytes, "
                f"block size {filesystem.block_size}"
            )
            paths = filesystem.paths()
            if args.inventory_output:
                args.inventory_output.parent.mkdir(parents=True, exist_ok=True)
                with args.inventory_output.open("w", newline="") as output:
                    writer = csv.writer(output, dialect="excel-tab")
                    writer.writerow(["path", "type", "inode_reference"])
                    for path, (inode_type, reference) in paths.items():
                        writer.writerow(
                            [
                                path,
                                TYPE_NAMES.get(inode_type, f"type-{inode_type}"),
                                f"0x{reference:016x}",
                            ]
                        )
                print(f"wrote {len(paths)} entries to {args.inventory_output}")
                return
            if args.read:
                for requested in args.read:
                    key = requested.lstrip("/")
                    if key not in paths:
                        raise ValueError(f"path not found: {requested}")
                    inode_type, reference = paths[key]
                    print(f"===== /{key} =====")
                    if inode_type in FILE_TYPES:
                        content = filesystem.read_file(reference)
                        sys.stdout.flush()
                        sys.stdout.buffer.write(content)
                        if not content.endswith(b"\n"):
                            print()
                    elif inode_type in SYMLINK_TYPES:
                        print(filesystem.read_symlink(reference))
                    else:
                        raise ValueError(f"path is not a file or symlink: {requested}")
            else:
                for path, (inode_type, reference) in paths.items():
                    if args.details:
                        print(
                            f"{TYPE_NAMES.get(inode_type, f'type-{inode_type}'):7s} "
                            f"0x{reference:016x} {path}"
                        )
                    else:
                        print(path)
        finally:
            data.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, struct.error, zlib.error) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
