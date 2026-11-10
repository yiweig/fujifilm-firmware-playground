#!/usr/bin/env python3
"""Rename the test flag on the card, then reboot to mode 3 (Playback).

The loader shell must be reachable. `apitest_init` installs the shell;
the state read still times out after the prompt appears. The attr
handler uses opt bit 0 for rename.
"""
import time
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
    dev = x.open_device()
    loader = x.Loader(dev)
    try:
        try:
            loader.apitest_init()
        except Exception as exc:
            print(f"init ended: {type(exc).__name__}: {exc}", flush=True)
        time.sleep(1.0)
        try:
            loader.apitest_send(r"attr D:\EnableTest.txt 0 1 D:\EnableTest.bak")
        except Exception as exc:
            print(f"attr ended: {type(exc).__name__}: {exc}", flush=True)
        time.sleep(2.0)
        try:
            loader.apitest_send("clbt reboot 3 0")
        except Exception as exc:
            print(f"reboot ended: {type(exc).__name__}: {exc}", flush=True)
        time.sleep(5.0)
    finally:
        import usb.util

        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
