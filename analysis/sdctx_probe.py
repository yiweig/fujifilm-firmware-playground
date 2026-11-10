#!/usr/bin/env python3
"""Read-only probe of the live SD device-1 context used by sdboot."""
from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
    dev = x.open_device()
    try:
        loader = x.Loader(dev)
        raw = loader.read_mem(0x40E4E564, 32)
        print("device-1 global:", raw.hex(" "))
        ptr = struct.unpack_from("<I", raw, 0)[0]
        print(f"context pointer: 0x{ptr:08x}")
        if not (0x40000000 <= ptr < 0x60000000):
            print("implausible context pointer; stop")
            return
        ctx = loader.read_mem(ptr, 0x80)
        for off in range(0, 0x80, 16):
            words = struct.unpack_from("<4I", ctx, off)
            print(f"+{off:02x}: " + " ".join(f"{w:08x}" for w in words))
    finally:
        import usb.util

        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
