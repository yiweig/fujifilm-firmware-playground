#!/usr/bin/env python3
"""End-to-end SD chainloader run.

Prerequisite: loader shell active (`cmd>`), SD card contains the
512-byte `STAGE.BIN`, and the stage was built for the same SRC/LEN.

Sequence:
  1. `sdfww D:\\STAGE.BIN`  -> eSD header + stage in reserved slot 0
  2. `sdhdr`                -> verify header
  3. stage `firmware-main.bin` at SRC via `0x04f5`, verify by read-back
  4. `sdboot 0x41070000`    -> read slot 0 (512 bytes), jump to stage
  5. stage copies SRC -> 0x40000000 and jumps to the image reset vector

Card writes only.  The camera's NAND is untouched.  A failure is
recovered by a power cycle.

Run inside the Linux passthrough VM.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import threading
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
    ap.add_argument("--file", default="STAGE.BIN")
    ap.add_argument("--image", type=Path,
                    default=HERE.parent.parent / "firmware-main.bin")
    ap.add_argument("--probe-only", action="store_true")
    ap.add_argument("--skip-sdfww", action="store_true")
    ap.add_argument("--stop-after-sdhdr", action="store_true")
    ap.add_argument("--skip-upload", action="store_true")
    ap.add_argument("--skip-verify", action="store_true")
    ap.add_argument("--marker", action="store_true",
                    help="patch the PTP model string X-HF1 -> X-ES1")
    args = ap.parse_args()

    payload = bytearray(args.image.read_bytes())
    if args.marker:
        off = 0xB929D8
        if payload[off:off + 5] != b"X-HF1":
            raise SystemExit("marker offset does not hold X-HF1")
        payload[off:off + 5] = b"X-ES1"
        print("marker: PTP model string X-HF1 -> X-ES1")
    payload = bytes(payload)
    want = hashlib.sha256(payload).hexdigest()
    print(f"image {args.image}: {len(payload)} bytes sha256 {want}")

    dev = x.open_device()
    try:
        loader = x.Loader(dev)

        if args.probe_only:
            d = read_mem(dev, args.src, 0x1000)
            nz = sum(1 for b in d if b)
            print(f"probe 0x{args.src:08x}: nonzero {nz}/4096 "
                  f"first {d[:32].hex(' ')}")
            return

        packets: list[tuple[int, bytes]] = []
        stop = threading.Event()

        def drain() -> None:
            while not stop.is_set():
                try:
                    data = bytes(dev.read(0x84, 512, timeout=300))
                except Exception:
                    continue
                if not data:
                    continue
                packets.append((0x84, data))
                text = loader._decode_log_records(data)
                print(f"[84] {data.hex()} {text!r}", flush=True)

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

        if not args.skip_sdfww:
            thread = threading.Thread(target=drain, daemon=True)
            thread.start()
            time.sleep(0.5)
            baseline = text().count("cmd>")
            print(f"baseline prompts: {baseline}", flush=True)

            send(rf"sdfww D:\{args.file}")
            ok = wait_prompt(baseline + 1, 180)
            print(f"sdfww prompt: {ok}", flush=True)
            if not ok:
                raise SystemExit("sdfww did not return; stopping")
            time.sleep(1)

            send("sdhdr")
            ok = wait_prompt(baseline + 2, 60)
            print(f"sdhdr prompt: {ok}", flush=True)
            stop.set()
            time.sleep(0.5)
            if not ok:
                raise SystemExit("sdhdr did not return; stopping")
            if args.stop_after_sdhdr:
                print("--- card prep complete; inspect the log above ---")
                return

        if not args.skip_upload:
            t0 = time.time()
            for off in range(0, len(payload), CHUNK):
                write_chunk(dev, args.src + off, payload[off:off + CHUNK])
                if (off // CHUNK) % 16 == 0:
                    print(f"  write 0x{off:x}/{len(payload):x}", flush=True)
            print(f"write done in {time.time() - t0:.1f}s")

            if not args.skip_verify:
                h = hashlib.sha256()
                t0 = time.time()
                for off in range(0, len(payload), READ_CHUNK):
                    h.update(read_mem(dev, args.src + off,
                                      min(READ_CHUNK, len(payload) - off)))
                    if (off // READ_CHUNK) % 16 == 0:
                        print(f"  read  0x{off:x}/{len(payload):x}",
                              flush=True)
                got = h.hexdigest()
                print(f"read-back sha256 {got} in {time.time() - t0:.1f}s")
                if got != want:
                    raise SystemExit("READ-BACK MISMATCH: do not sdboot")
                print("read-back verified")

        print(">>> sdboot 0x41070000", flush=True)
        loader.apitest_ctrl(b"sdboot 0x41070000\n")
        print("sdboot sent; waiting 30 s", flush=True)
        time.sleep(30)
        print("done; if the device did not re-enumerate, power cycle")
    finally:
        import usb.util
        try:
            usb.util.release_interface(dev, x.INTERFACE)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
