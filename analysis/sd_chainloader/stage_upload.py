#!/usr/bin/env python3
"""Stage the main image in high DRAM through the vendor USB receiver.

Writes `firmware-main.bin` to SRC (default 0x50000000) with the
`0x04f5` host-to-memory branch, then reads it back with `0x04f2`
and compares SHA-256.  Run inside the Linux passthrough VM with the
camera in the loader shell state (2aad:6500).

No flash/NAND access.  A failure is recovered by a power cycle.

Usage:
  python3 stage_upload.py [--src 0x50000000] [--file firmware-main.bin]
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import xhalf_loader_linux as x  # noqa: E402

CHUNK = 0x8000
READ_CHUNK = 0x10000
TIMEOUT_MS = 8000


def write_chunk(dev, addr: int, data: bytes) -> None:
    setup = struct.pack("<II", len(data), addr)
    sent = dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, setup,
                             timeout=TIMEOUT_MS)
    if sent != len(setup):
        raise SystemExit(f"setup short write {sent}/{len(setup)}")
    n = dev.write(x.EP_OUT, data, timeout=TIMEOUT_MS)
    if n != len(data):
        raise SystemExit(f"bulk short write {n}/{len(data)} at 0x{addr:08x}")


def read_mem(dev, addr: int, length: int) -> bytes:
    import threading
    payload = struct.pack("<II", addr, length)
    result = {}

    def reader():
        try:
            out = bytearray()
            while len(out) < length:
                c = bytes(dev.read(x.EP_IN, length - len(out),
                                   timeout=TIMEOUT_MS))
                if not c:
                    break
                out += c
            result["data"] = bytes(out)
        except Exception as exc:  # noqa: BLE001
            result["error"] = exc

    t = threading.Thread(target=reader, daemon=True)
    t.start()
    time.sleep(0.35)
    sent = dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F2, payload,
                             timeout=TIMEOUT_MS)
    if sent != len(payload):
        raise SystemExit(f"read setup short write {sent}/{len(payload)}")
    t.join(TIMEOUT_MS / 1000 + 3)
    if "error" in result:
        raise result["error"]
    data = result.get("data", b"")
    if len(data) != length:
        raise SystemExit(f"read stalled at {len(data)}/{length} "
                         f"@0x{addr:08x}")
    return data


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=lambda s: int(s, 0), default=0x50000000)
    ap.add_argument("--file", type=Path,
                    default=HERE.parent.parent / "firmware-main.bin")
    ap.add_argument("--no-verify", action="store_true")
    args = ap.parse_args()

    payload = args.file.read_bytes()
    want = hashlib.sha256(payload).hexdigest()
    print(f"payload {args.file}: {len(payload)} bytes sha256 {want}")
    if len(payload) % 32:
        raise SystemExit("payload not 32-byte aligned")

    dev = x.open_device()
    try:
        t0 = time.time()
        for off in range(0, len(payload), CHUNK):
            part = payload[off:off + CHUNK]
            write_chunk(dev, args.src + off, part)
            if (off // CHUNK) % 16 == 0:
                done = off + len(part)
                print(f"  write 0x{done:x}/{len(payload):x}", flush=True)
        print(f"write done in {time.time() - t0:.1f}s")

        if args.no_verify:
            return
        h = hashlib.sha256()
        t0 = time.time()
        for off in range(0, len(payload), READ_CHUNK):
            h.update(read_mem(dev, args.src + off,
                              min(READ_CHUNK, len(payload) - off)))
            if (off // READ_CHUNK) % 16 == 0:
                print(f"  read  0x{off:x}/{len(payload):x}", flush=True)
        got = h.hexdigest()
        print(f"read-back sha256 {got} in {time.time() - t0:.1f}s")
        if got != want:
            raise SystemExit("READ-BACK MISMATCH: do not sdboot")
        print("read-back verified")
    finally:
        import usb.util
        usb.util.release_interface(dev, x.INTERFACE)
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
