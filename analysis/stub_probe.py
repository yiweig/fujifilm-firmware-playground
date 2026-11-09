#!/usr/bin/env python3
"""Patch one call site in the running loader's handoff stub.

The `0x04f7` helper copies the 0x800-byte stub at 0x401ed070 into its
workspace before the handoff. Patching a call site in the running
loader's stub (before the push) therefore changes the copied stub.

Call sites:
  helper  0x401ed134  blx 0x00000b34(dest, size)
  entry   0x401ed150  blx 0x40000200 (payload +0x200)
  reset   0x401ed154  blx 0x40000000 (payload reset)

Replacing the site with `b .` hangs the camera if the stub reaches it.
Run one site per boot. Requires the camera in loader mode and the
Linux/pyusb VM.

Usage:
  python3 stub_probe.py helper --push card-updater-full.bin
  python3 stub_probe.py entry  --push card-updater-full.bin
  python3 stub_probe.py reset  --push card-updater-full.bin
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402

# (32-byte block base, offset in block, expected original instruction)
TARGETS = {
    "helper": (0x401ED120, 0x14, 0xE12FFF33),  # blx r3 -> 0x00000b34
    "entry": (0x401ED140, 0x10, 0xE12FFF33),   # blx r3 -> 0x40000200
    "reset": (0x401ED140, 0x14, 0xE12FFF34),   # blx r4 -> 0x40000000
}
SELF_LOOP = 0xEAFFFFFE


def write_block32(loader: x.Loader, block: int, data: bytes) -> None:
    """0x04f5 write of one 32-byte-aligned block, bypassing the scratch allowlist."""
    if block % 32 or len(data) != 32:
        raise SystemExit("block must be 32-byte aligned and exactly 32 bytes")
    payload = struct.pack("<II", 32, block)
    if loader.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, payload, timeout=x.TIMEOUT_MS) != 8:
        raise SystemExit("stub write setup: short control write")
    if loader.dev.write(x.EP_OUT, data, timeout=x.TIMEOUT_MS) != 32:
        raise SystemExit("stub write: short bulk OUT")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("site", choices=TARGETS)
    parser.add_argument("--push", metavar="PAYLOAD", help="optional payload to ram-push after patching")
    args = parser.parse_args()

    block, offset, expected = TARGETS[args.site]
    dev = x.open_device()
    try:
        loader = x.Loader(dev)
        data = bytearray(loader.read_mem(block, 32))
        got = struct.unpack_from("<I", data, offset)[0]
        if got != expected:
            raise SystemExit(
                f"unexpected instruction at {block + offset:#x}: {got:#010x} "
                f"(want {expected:#010x}); refusing to patch"
            )
        struct.pack_into("<I", data, offset, SELF_LOOP)
        write_block32(loader, block, bytes(data))
        check = loader.read_mem(block, 32)
        if check[offset : offset + 4] != struct.pack("<I", SELF_LOOP):
            raise SystemExit("stub patch read-back mismatch")
        print(
            f"patched {args.site} at {block + offset:#x}: "
            f"{expected:#010x} -> {SELF_LOOP:#010x}"
        )
        if args.push:
            loader.ram_push(args.push)
    finally:
        import usb.util

        usb.util.release_interface(dev, x.INTERFACE)
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
