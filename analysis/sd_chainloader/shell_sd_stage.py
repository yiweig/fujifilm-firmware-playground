#!/usr/bin/env python3
"""Drive `sdfww` (512-byte stage) and `sdboot` through the live shell.

Prerequisite: the shell is already active (`cmd>`), the full main image
has been staged in DRAM (`stage_upload.py`), and the SD card contains
`STAGE.BIN` (the 512-byte chainloader).  `sdfww` reads the small file,
builds the eSD header and writes the stage to reserved slot 0; it
overwrites the card's FAT.  `sdboot 0x41070000` reads the slot and jumps.

Run inside the Linux passthrough VM.

Usage:
  python3 shell_sd_stage.py [--file STAGE.BIN]
"""
from __future__ import annotations

import argparse
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", default="STAGE.BIN")
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
                    if not data:
                        continue
                    packets.append((ep, data))
                    text = loader._decode_log_records(data)
                    print(f"[{ep:#04x}] {data.hex()} {text!r}", flush=True)

        thread = threading.Thread(target=drain, daemon=True)
        thread.start()
        time.sleep(0.5)

        def text() -> str:
            return "".join(loader._decode_log_records(d) for _, d in packets)

        def wait_prompt(count: int, timeout_s: float) -> bool:
            deadline = time.time() + timeout_s
            while time.time() < deadline:
                if text().count("cmd>") >= count:
                    return True
                time.sleep(0.5)
            return False

        def send(command: str) -> None:
            print(f">>> {command}", flush=True)
            loader.apitest_ctrl((command + "\n").encode())

        baseline = text().count("cmd>")
        print(f"baseline prompts: {baseline}", flush=True)

        send(rf"sdfww D:\{args.file}")
        ok = wait_prompt(baseline + 1, 180)
        print(f"sdfww prompt: {ok}", flush=True)
        if not ok:
            print("sdfww did not return to prompt; stopping before sdboot",
                  flush=True)
            return
        time.sleep(1)

        send("sdhdr")
        ok = wait_prompt(baseline + 2, 60)
        print(f"sdhdr prompt: {ok}", flush=True)
        time.sleep(1)

        send("sdboot 0x41070000")
        print("sdboot sent; waiting 30 s for the stage/image...", flush=True)
        time.sleep(30)
        stop.set()
        print("--- decoded tail ---", flush=True)
        print(text()[-4000:], flush=True)
    finally:
        import usb.util
        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
