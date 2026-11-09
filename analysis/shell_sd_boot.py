#!/usr/bin/env python3
"""Drive the already-active APITEST shell through the vendor SD F/W commands.

The shell must already be active (`cmd>` prompt) — run `--apitest-init`
first. This script keeps a drain open, sends the commands, and waits for
the `cmd>` prompt between them.

Sequence:
  sdfww D:\\MAIN.BIN   (build eSD header + write F/W to SD reserved slot)
  sdhdr               (display header)
  sdboot 0x40000000   (read slot, jump to reset vector)

The card is converted to the eSD raw layout; its FAT/MAIN.BIN is read
first and then overwritten. The camera's NAND is untouched.
"""
from __future__ import annotations

import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
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

        send(r"sdfww D:\MAIN.BIN")
        ok = wait_prompt(1, 120)
        print(f"sdfww prompt: {ok}", flush=True)
        time.sleep(2)

        send("sdhdr")
        ok = wait_prompt(2, 30)
        print(f"sdhdr prompt: {ok}", flush=True)
        time.sleep(1)

        send("sdboot 0x40000000")
        print("sdboot sent; waiting 30 s for the card boot...", flush=True)
        time.sleep(30)

        stop.set()
        print(f"total packets: {len(packets)}", flush=True)
        print("--- decoded tail ---")
        print(text()[-2000:], flush=True)
    finally:
        import usb.util

        usb.util.release_interface(dev, x.INTERFACE)
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
