#!/usr/bin/env python3
"""Drain the live shell console, optionally send one command when `cmd>` appears.

The shell must already be active.  This is a read-only drain by default;
with `--send`, it sends the command once a prompt is seen, then drains.

Usage:
  python3 shell_drain.py [seconds]
  python3 shell_drain.py --send sdhdr [seconds]
"""
from __future__ import annotations

import argparse
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--send", default=None)
    ap.add_argument("seconds", nargs="?", type=float, default=120.0)
    args = ap.parse_args()

    dev = x.open_device()
    try:
        loader = x.Loader(dev)
        packets: list[tuple[int, bytes]] = []
        stop = threading.Event()

        def drain() -> None:
            while not stop.is_set():
                for ep in (x.EP_IN, 0x84):
                    try:
                        data = bytes(dev.read(ep, 512, timeout=300))
                    except Exception:
                        continue
                    if data:
                        packets.append((ep, data))

        thread = threading.Thread(target=drain, daemon=True)
        thread.start()

        def text() -> str:
            return "".join(loader._decode_log_records(d) for _, d in packets)

        deadline = time.time() + args.seconds
        sent = False
        while time.time() < deadline:
            time.sleep(1.0)
            if args.send and not sent and "cmd>" in text():
                print(f">>> {args.send}", flush=True)
                loader.apitest_ctrl((args.send + "\n").encode())
                sent = True

        stop.set()
        decoded = text()
        Path("/tmp/shell-drain.txt").write_text(decoded)
        print("--- decoded tail ---", flush=True)
        print(decoded[-6000:], flush=True)
    finally:
        import usb.util

        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
