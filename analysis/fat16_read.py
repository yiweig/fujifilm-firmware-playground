#!/usr/bin/env python3
"""Read FAT16 resources inside a regular firmware file without mounting it.

Uses DOS 8.3 names only; long-name records are skipped. Prints JSON metadata
and, with --text, decoded contents. Never opens a device or writes the image.
"""
import argparse
import hashlib
import json
import mmap
from pathlib import Path
import stat
import struct


class Fat16:
    def __init__(self, image, offset):
        self.image = image
        self.offset = offset
        boot = self.read(offset, 512)
        self.bps = struct.unpack_from('<H', boot, 11)[0]
        self.spc = boot[13]
        reserved = struct.unpack_from('<H', boot, 14)[0]
        count = boot[16]
        self.entries = struct.unpack_from('<H', boot, 17)[0]
        sectors = struct.unpack_from('<H', boot, 19)[0] or struct.unpack_from('<I', boot, 32)[0]
        self.spf = struct.unpack_from('<H', boot, 22)[0]
        if (boot[54:62] != b'FAT16   ' or boot[510:512] != b'\x55\xaa'
                or self.bps not in (512, 1024, 2048, 4096)
                or not self.spc or self.spc & (self.spc - 1)
                or not reserved or not count or not self.spf or not self.entries):
            raise ValueError('unsupported FAT16 boot sector')
        self.end = offset + sectors * self.bps
        self.fat = offset + reserved * self.bps
        self.root = self.fat + count * self.spf * self.bps
        self.data = self.root + ((self.entries * 32 + self.bps - 1) // self.bps) * self.bps
        self.cluster_size = self.bps * self.spc
        self.cluster_count = (self.end - self.data) // self.cluster_size
        if not 4085 <= self.cluster_count < 65525 or self.end > len(image):
            raise ValueError('invalid FAT16 extent or cluster count')
        if (self.cluster_count + 2) * 2 > self.spf * self.bps:
            raise ValueError('FAT too small for declared volume')

    def read(self, start, size):
        if start < 0 or size < 0 or start + size > len(self.image):
            raise ValueError('read outside firmware file')
        return self.image[start:start + size]

    def chain(self, cluster):
        parts, seen = [], set()
        while cluster < 0xfff8:
            if not 2 <= cluster < self.cluster_count + 2 or cluster in seen:
                raise ValueError('invalid or cyclic FAT chain')
            seen.add(cluster)
            parts.append(self.read(self.data + (cluster - 2) * self.cluster_size, self.cluster_size))
            cluster = struct.unpack('<H', self.read(self.fat + cluster * 2, 2))[0]
        return b''.join(parts)

    @staticmethod
    def directory(raw):
        for position in range(0, len(raw), 32):
            entry = raw[position:position + 32]
            if len(entry) < 32 or entry[0] == 0:
                break
            if entry[0] == 0xe5 or entry[11] == 0x0f or entry[11] & 8:
                continue
            name = entry[:8].decode('ascii', 'replace').rstrip()
            extension = entry[8:11].decode('ascii', 'replace').rstrip()
            yield {'name': name + ('.' + extension if extension else ''),
                   'directory': bool(entry[11] & 16),
                   'cluster': struct.unpack_from('<H', entry, 26)[0],
                   'size': struct.unpack_from('<I', entry, 28)[0]}

    def lookup(self, path):
        raw = self.read(self.root, self.entries * 32)
        components = path.strip('/').split('/') if path.strip('/') else []
        for index, component in enumerate(components):
            if component in ('.', '..'):
                raise ValueError('use an absolute resource path without dot components')
            entry = next((e for e in self.directory(raw) if e['name'].upper() == component.upper()), None)
            if entry is None:
                raise ValueError('resource not found: ' + component)
            if not entry['directory'] and index != len(components) - 1:
                raise ValueError('intermediate component is not a directory')
            raw = self.chain(entry['cluster']) if entry['directory'] or entry['size'] else b''
            if not entry['directory']:
                if len(raw) < entry['size']:
                    raise ValueError('file chain shorter than directory size')
                return entry, raw[:entry['size']]
        return {'directory': True}, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    parser.add_argument('--offset', required=True, type=lambda value: int(value, 0))
    parser.add_argument('--path', default='/')
    parser.add_argument('--text', action='store_true')
    args = parser.parse_args()
    if not stat.S_ISREG(args.firmware.stat().st_mode):
        parser.error('input must be a regular firmware file')
    try:
        with args.firmware.open('rb') as handle, mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as image:
            entry, raw = Fat16(image, args.offset).lookup(args.path)
            if entry['directory']:
                result = {'path': args.path, 'entries': list(Fat16.directory(raw))}
            else:
                result = {'path': args.path, 'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                if args.text:
                    result['text'] = raw.decode('utf-8', 'replace')
            print(json.dumps(result, indent=2))
    except (ValueError, OSError, struct.error) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
