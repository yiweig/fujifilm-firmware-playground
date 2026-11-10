#!/usr/bin/env python3
"""Vendor mask-loader upload of the official card updater.  ** DO NOT RUN **

SUPERSEDED 2026-11-10: this is the Windows DLL's mask-ROM flow.  In the
camera's `usbt` state the same vendor indices are handled by the running
main firmware, where the first `0x04f3` is a storage read + memcpy to
`0x3f000200` (the live first-use flag is 0), not a download.  A 666 KiB
first `0x04f3` would overrun the 64 KiB SRAM window, and `0x04f1` enters
the same state-6 park/handoff as `0x04f7`.  See
`analysis/usbt-mask-boundary-2026-11-10.md`.  Kept for protocol provenance.

Sequence from USB_CAM.dll FrmMaskDnloadIspCore / DnloadBuffer2Dram:

  1. 0x40/0xFD, wIndex 0x04f3, 8-byte setup [dest, size]
     then the file bytes over bulk OUT 0x03.
  2. 0x40/0xFD, wIndex 0x04f1, 16-byte setup [dest, 0, 0, 1]
     then the first 0x100 bytes over bulk OUT 0x03.

The destination is 0x40000000 (the updater's link base). This path does
not call the main firmware's 0x04f7/0xb34 handoff.

No flash command is sent. The camera's NAND is untouched; a failure is
recovered by a battery pull.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import time
from pathlib import Path

VID, PID = 0x2AAD, 0x6500
EP_OUT = 0x03
TIMEOUT_MS = 5000

ROOT = Path(__file__).resolve().parent.parent
UPDATER = ROOT / "analysis/output/card-updater-full.bin"
UPDATER_SHA = "4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", type=lambda s: int(s, 0), default=0x40000000)
    ap.add_argument("--file", type=Path, default=UPDATER)
    args = ap.parse_args()

    payload = args.file.read_bytes()
    sha = hashlib.sha256(payload).hexdigest()
    print(f"payload {args.file}: {len(payload)} bytes sha256 {sha}")
    if args.file == UPDATER and sha != UPDATER_SHA:
        raise SystemExit("card updater hash mismatch; abort")

    import usb.core
    import usb.util

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        raise SystemExit("camera 2aad:6500 not found")
    try:
        if dev.is_kernel_driver_active(0):
            dev.detach_kernel_driver(0)
    except Exception:
        pass
    dev.set_configuration()
    try:
        usb.util.claim_interface(dev, 5)
    except Exception as exc:
        print(f"claim interface 5: {exc}")

    try:
        setup = struct.pack("<II", args.dest, len(payload))
        sent = dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F3, setup, timeout=TIMEOUT_MS)
        print(f"BulkDnload setup {setup.hex(' ')} sent {sent}/{len(setup)}")
        chunk = 0x10000
        written = 0
        for off in range(0, len(payload), chunk):
            part = payload[off:off + chunk]
            n = dev.write(EP_OUT, part, timeout=TIMEOUT_MS)
            written += n
            print(f"  payload 0x{written:x}/{len(payload):x}", flush=True)
        print(f"payload written {written}/{len(payload)}")

        isp = struct.pack("<IIII", args.dest, 0, 0, 1)
        sent = dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F1, isp, timeout=TIMEOUT_MS)
        print(f"ISP_Dnload setup {isp.hex(' ')} sent {sent}/{len(isp)}")
        n = dev.write(EP_OUT, payload[:0x100], timeout=TIMEOUT_MS)
        print(f"first 0x100 bytes written {n}/256")

        print("mask upload sent; waiting 8 s for the updater...")
        time.sleep(8)
    finally:
        try:
            usb.util.release_interface(dev, 5)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
