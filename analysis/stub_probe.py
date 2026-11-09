#!/usr/bin/env python3
"""Patch one call site in the running loader's handoff stub.

The `0x04f7` helper copies the 0x800-byte stub at 0x401ed070 into its
workspace before the handoff. Patching a call site in the running
loader's stub (before the push) therefore changes the copied stub.

The patch is applied with the handler-slot trampoline v3 (CPU stores),
not the `0x04f5` bulk write: a direct write to the stub's code address
wedges the receiver.

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
STUB_STAGE = 0x41130000  # fixed scratch source for the 32-byte block


def patch_with_trampoline(loader: x.Loader, block: int, patched: bytes) -> None:
    """Copy `patched` to `block` using the handler-slot trampoline v3."""
    loader.write_mem(loader.TRAMP_ADDR, bytes.fromhex(loader.TRAMP3_HEX))
    loader.write_mem(loader.COUNTER_ADDR, bytes(32))
    if loader.read_mem(loader.TRAMP_ADDR, 32) != bytes.fromhex(loader.TRAMP3_HEX)[:32]:
        raise SystemExit("trampoline read-back mismatch; abort")

    originals = []
    for slot, off in loader.SLOT_BLOCKS:
        data = loader.read_mem(slot, 32)
        if struct.unpack_from("<I", data, off)[0] != loader.HANDLER:
            raise SystemExit("handler slot does not hold the expected handler; abort")
        originals.append(data)

    try:
        for (slot, off), _ in zip(loader.SLOT_BLOCKS, originals):
            data = bytearray(loader.read_mem(slot, 32))
            struct.pack_into("<I", data, off, loader.TRAMP_ADDR)
            loader._write_block32(slot, bytes(data))
        print("trampoline v3 installed")

        loader.write_mem(STUB_STAGE, patched)
        if loader.read_mem(STUB_STAGE, 32) != patched:
            raise SystemExit("staged block read-back mismatch; abort")

        loader._set_stage(STUB_STAGE, block, 32)
        loader.bootquery()
        if not loader._wait_done():
            raise SystemExit(
                "stub copy did not complete (DONE!=2); camera may need a power cycle"
            )
        check = loader.read_mem(block, 32)
        if check != patched:
            raise SystemExit("stub patch read-back mismatch; abort")
        print(f"patched stub block 0x{block:08x} via trampoline")
    finally:
        try:
            for (slot, _), orig in zip(loader.SLOT_BLOCKS, originals):
                loader._write_block32(slot, orig)
            print("slots restored")
        except Exception as exc:  # noqa: BLE001
            print(f"WARNING: slot restore failed: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("site", choices=TARGETS)
    parser.add_argument("--push", metavar="PAYLOAD", help="optional payload to ram-push after patching")
    args = parser.parse_args()

    block, offset, expected = TARGETS[args.site]
    dev = x.open_device()
    try:
        loader = x.Loader(dev)
        current = bytearray(loader.read_mem(block, 32))
        got = struct.unpack_from("<I", current, offset)[0]
        if got != expected:
            raise SystemExit(
                f"unexpected instruction at {block + offset:#x}: {got:#010x} "
                f"(want {expected:#010x}); refusing to patch"
            )
        struct.pack_into("<I", current, offset, SELF_LOOP)
        print(
            f"target {args.site} at {block + offset:#x}: "
            f"{expected:#010x} -> {SELF_LOOP:#010x}"
        )
        patch_with_trampoline(loader, block, bytes(current))
        if args.push:
            loader.ram_push(args.push)
    finally:
        import usb.util

        usb.util.release_interface(dev, x.INTERFACE)
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
