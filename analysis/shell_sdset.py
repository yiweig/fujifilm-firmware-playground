#!/usr/bin/env python3
"""Run the vendor `sdset` reserved-block formatter through the live shell.

`sdset A B C D` calls the eSD header builder and writes the reserved header
to the card.  It does not read a firmware file, so it is much shorter
than `sdfww` and avoids the 14 MB VFS read that failed at CMD18.

The shell must already be active (`cmd>`), i.e. run `--apitest-init`
first.  The card is converted to the raw eSD layout; its FAT/MBR is
overwritten.  The camera's NAND is untouched.

Usage:
  python3 shell_sdset.py [A B C D]
"""
from __future__ import annotations

import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as x  # noqa: E402


def main() -> None:
    args = sys.argv[1:] or ["0x200000", "0x200000", "0x100000", "0"]
    command = "sdset " + " ".join(args)

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
        time.sleep(1.0)

        def text() -> str:
            return "".join(loader._decode_log_records(d) for _, d in packets)

        baseline = text().count("cmd>")
        print(f"baseline prompts: {baseline}", flush=True)

        def wait_prompt(count: int, timeout_s: float) -> bool:
            deadline = time.time() + timeout_s
            while time.time() < deadline:
                if text().count("cmd>") >= count:
                    return True
                time.sleep(0.5)
            return False

        print(f">>> {command}", flush=True)
        loader.apitest_ctrl((command + "\n").encode())
        ok = wait_prompt(baseline + 1, 300)
        print(f"sdset prompt: {ok}", flush=True)

        if ok:
            print(">>> sdhdr", flush=True)
            loader.apitest_ctrl(b"sdhdr\n")
            ok2 = wait_prompt(baseline + 2, 60)
            print(f"sdhdr prompt: {ok2}", flush=True)

        stop.set()
        decoded = text()
        Path("/tmp/shellsdset-text.txt").write_text(decoded)
        print("--- decoded ---", flush=True)
        print(decoded[-8000:], flush=True)
    finally:
        import usb.util

        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
