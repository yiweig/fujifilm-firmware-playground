#!/usr/bin/env python3
"""Run the 512-byte stage through the vendor 0x04f7 handoff.

The stage is the 0x04f7 payload itself:

  bootquery-alloc(512) -> write stage -> read back
  0x04f7 [buffer, 512, 0x41100200]

State 6 then copies the stage to 0x41100200 and jumps to it.  The
mode-0x88 preparation callback is cleared first (`skip_prep`), so the
handoff goes straight to the storage read and the copy stub.

The full image must already be staged at 0x44000000 (stage_upload.py).
A failure is recovered by a power cycle; NAND is untouched.
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
    dev = x.open_device()
    try:
        loader = x.Loader(dev)
        loader.ram_push(str(HERE / "stage.bin"), skip_prep=True,
                        dest=0x41100200)
    finally:
        import usb.util
        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
