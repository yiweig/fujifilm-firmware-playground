#!/usr/bin/env python3
"""Assemble and pack the 512-byte SD chainloader stage.

The stage is entered at 0x40000000 by the shell command
`sdboot 0x40000000` (one sector read from SD reserved slot 0).
It relocates its copy routine to 0x41100000, copies a pre-staged
main image from SRC to 0x40000000 with cache maintenance, and
jumps to the image reset vector.

Usage:
  python3 stage_build.py [--src 0x50000000] [--len 0xe38400] [--out stage.bin]
"""
from __future__ import annotations

import argparse
import struct
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def text_section(obj: Path) -> bytes:
    data = obj.read_bytes()
    if data[:4] == b"\xce\xfa\xed\xfe":
        hdr = 28  # 32-bit mach_header
    elif data[:4] == b"\xcf\xfa\xed\xfe":
        hdr = 32  # 64-bit mach_header
    else:
        raise SystemExit("not a little-endian Mach-O object")
    ncmds = struct.unpack_from("<I", data, 16)[0]
    off = hdr
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<II", data, off)
        if cmd == 0x1:  # LC_SEGMENT
            nsects = struct.unpack_from("<I", data, off + 48)[0]
            so = off + 56
            for _ in range(nsects):
                sectname = data[so:so + 16].rstrip(b"\0")
                if sectname == b"__text":
                    size, fileoff = struct.unpack_from("<II", data, so + 36)
                    return data[fileoff:fileoff + size]
                so += 68
        off += cmdsize
    raise SystemExit("no __text section")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=lambda s: int(s, 0), default=0x50000000)
    ap.add_argument("--len", type=lambda s: int(s, 0), default=0x00E38400)
    ap.add_argument("--reloc", type=lambda s: int(s, 0), default=0x41100000)
    ap.add_argument("--out", type=Path, default=HERE / "stage.bin")
    args = ap.parse_args()

    if args.src % 32 or args.len % 32 or args.reloc % 32:
        raise SystemExit("src/len/reloc must be 32-byte aligned")

    # The assembly has the constants baked in; regenerate a patched
    # copy for the requested values.
    src = (HERE / "stage.s").read_text()
    src = src.replace("=0x50000000", f"=0x{args.src:08x}")
    src = src.replace("=0x00e38400", f"=0x{args.len:08x}")
    src = src.replace("=0x41100000", f"=0x{args.reloc:08x}")
    with tempfile.TemporaryDirectory() as td:
        s = Path(td) / "stage.s"
        o = Path(td) / "stage.o"
        s.write_text(src)
        subprocess.run(["as", "-arch", "armv7", "-o", str(o), str(s)],
                       check=True)
        code = text_section(o)

    if len(code) > 512:
        raise SystemExit(f"stage is {len(code)} bytes, exceeds one sector")
    out = code + b"\x00" * (512 - len(code))
    args.out.write_bytes(out)
    print(f"stage {len(code)} bytes -> {args.out} (512 bytes, "
          f"src=0x{args.src:08x} len=0x{args.len:x} reloc=0x{args.reloc:08x})")


if __name__ == "__main__":
    main()
