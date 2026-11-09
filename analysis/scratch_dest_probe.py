#!/usr/bin/env python3
"""Scratch-destination handoff probe.

Instead of patching the loader's code (the code page wedges when written
from the handler context), this redirects the `0x04f7` destination to
scratch `0x41130000` and patches the payload's first instruction to
`b .`. The handoff stub copies the payload to the scratch, calls
`0x00000b34(dest, size)`, skips the `+0x200` call because dest is not
0x40000000, and calls `blx dest`. If the copy and helper return, the
camera hangs at the self-loop. If the stock UI returns, the copy or
helper failed.

Usage (camera in loader mode, Linux/pyusb VM):
  python3 scratch_dest_probe.py [--payload card-updater-scratchhang.bin] [--dest 0x41130000]

The probes are RAM-only. A hang is recovered by OFF + unplug.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402

SELF_LOOP = bytes.fromhex("feffffea")
BASE_IMAGE = Path("card-updater-full.bin")


def ensure_payload(path: Path) -> None:
    if not path.exists():
        source = BASE_IMAGE.read_bytes()
        if source[0:4] != bytes.fromhex("ce0000ea"):
            raise SystemExit(f"unexpected first instruction in {BASE_IMAGE}: {source[0:4].hex()}")
        data = bytearray(source)
        data[0:4] = SELF_LOOP
        path.write_bytes(bytes(data))
        print(f"created {path} from {BASE_IMAGE} (first instruction -> b .)")
    data = path.read_bytes()
    if data[0:4] != SELF_LOOP:
        raise SystemExit(f"{path} first instruction is not b . ({data[0:4].hex()})")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--payload", default="card-updater-scratchhang.bin")
    parser.add_argument("--dest", default="0x41130000")
    args = parser.parse_args()

    payload = Path(args.payload)
    ensure_payload(payload)
    dest = int(args.dest, 0)
    if dest == 0x40000000:
        raise SystemExit("use a non-0x40000000 destination for this probe")

    dev = x.open_device()
    try:
        loader = x.Loader(dev)
        print(f"scratch-destination handoff: payload={payload} dest=0x{dest:08x}")
        loader.ram_push(str(payload), dest=dest)
    finally:
        import usb.util

        usb.util.release_interface(dev, x.INTERFACE)
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
