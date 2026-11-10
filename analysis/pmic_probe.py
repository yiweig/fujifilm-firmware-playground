#!/usr/bin/env python3
"""Read-only PMIC/charger register dump via the loader trampoline.

Installs TRAMP5, calls FUN_40043220(reg, scratch+3, 0, 1) for regs
0x00..0x25, and reads the returned byte. No I2C writes, no flash.
The function uses the main firmware's I2C bus 0x10 and the charger/PMIC
register helper.
"""
from __future__ import annotations

import hashlib
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402

SCRATCH = 0x41100200
SCRATCH_BYTE = SCRATCH + 3
PMIC_READ = 0x40043220


def main() -> None:
    dev = x.open_device()
    loader = x.Loader(dev)
    originals = []
    try:
        print("bootquery:", loader.bootquery().hex(), flush=True)
        tramp = bytes.fromhex(loader.TRAMP5_HEX)
        if len(tramp) != 188 or hashlib.sha256(tramp).hexdigest() != loader.TRAMP5_SHA256:
            raise SystemExit("trampoline self-check failed")
        loader.write_mem(loader.TRAMP_ADDR, tramp)
        loader.write_mem(loader.COUNTER_ADDR, bytes(32))
        if loader.read_mem(loader.TRAMP_ADDR, 32) != tramp[:32]:
            raise SystemExit("trampoline read-back mismatch")
        for block, off in loader.SLOT_BLOCKS:
            data = loader.read_mem(block, 32)
            if struct.unpack_from("<I", data, off)[0] != loader.HANDLER:
                raise SystemExit(f"slot 0x{block:08x} does not hold handler")
            originals.append(data)
        for (block, off), _ in zip(loader.SLOT_BLOCKS, originals):
            data = bytearray(loader.read_mem(block, 32))
            struct.pack_into("<I", data, off, loader.TRAMP_ADDR)
            loader._write_block32(block, bytes(data))
        print("trampoline installed", flush=True)

        for reg in range(0x26):
            loader.write_mem(SCRATCH, bytes(32))
            loader._trampoline_call(PMIC_READ, reg, SCRATCH_BYTE, 0, 1, 0)
            ok = loader._wait_call_done(10)
            data = loader.read_mem(SCRATCH, 32)
            print(f"reg 0x{reg:02x}: {data[3]:02x} done={ok}", flush=True)
            if not ok:
                print("call did not complete; stopping", flush=True)
                break
    finally:
        for (block, off), orig in zip(loader.SLOT_BLOCKS, originals):
            try:
                loader._write_block32(block, bytes(orig))
            except Exception as exc:
                print(f"restore 0x{block:08x} failed: {exc}", flush=True)
        try:
            import usb.util

            usb.util.release_interface(dev, x.INTERFACE)
            usb.util.dispose_resources(dev)
        except Exception:
            pass


if __name__ == "__main__":
    main()
