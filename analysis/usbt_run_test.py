#!/usr/bin/env python3
"""RAM-only upload/run test in the vendor usbt state.

Sequence:
  1. BootMethod query 0xC0/0x70, 11 bytes, wValue = payload length.
     The response is [method, addr32, ...]; method 2 + a 0x41xxxxxx
     address means the vendor upload/run path is available.
  2. FRMDnloadRunISP control OUT 0x40/0xFD, wIndex 0x04f7, 12-byte
     setup [upload_addr, size, entry_addr].
  3. Payload over bulk OUT EP 0x03.
  4. Re-query the boot method to see whether the stub is still alive.

Default payload is 0xE12FFF1E (`bx lr`), so a call-style entry returns
immediately. No flash, erase, or NAND access.
"""
from __future__ import annotations

import argparse
import struct
import sys
import time

VID, PID = 0x2AAD, 0x6500
EP_OUT = 0x03
TIMEOUT_MS = 3000


def boot_query(dev, value: int, length: int = 0x0B) -> bytes:
    return bytes(dev.ctrl_transfer(0xC0, 0x70, value & 0xFFFF, 0, length,
                                    timeout=TIMEOUT_MS))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload-hex", default="1eff2fe1",
                    help="payload bytes as hex (default bx lr)")
    ap.add_argument("--entry", type=lambda s: int(s, 0), default=None,
                    help="entry address; default upload address")
    args = ap.parse_args()

    payload = bytes.fromhex(args.payload_hex)
    if not 4 <= len(payload) <= 0x1000:
        raise SystemExit("payload must be 4..4096 bytes")

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
        reply = boot_query(dev, len(payload))
        print(f"boot query (size={len(payload)}): {reply.hex(' ')}")
        if len(reply) < 5:
            raise SystemExit("short boot query reply")
        method = reply[0]
        addr = int.from_bytes(reply[1:5], "little")
        print(f"method={method} upload_addr=0x{addr:08x}")
        if not (0x40000000 <= addr < 0x60000000):
            raise SystemExit("upload address is not in the 0x40..0x5f RAM window; stop")

        entry = args.entry if args.entry is not None else addr
        setup = struct.pack("<III", addr, len(payload), entry)
        print(f"FRMDnloadRunISP setup: {setup.hex(' ')} (entry=0x{entry:08x})")
        sent = dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F7, setup, timeout=TIMEOUT_MS)
        print(f"setup sent: {sent}/{len(setup)}")
        written = dev.write(EP_OUT, payload, timeout=TIMEOUT_MS)
        print(f"payload written: {written}/{len(payload)}")

        time.sleep(1.0)
        try:
            reply2 = boot_query(dev, 0)
            print(f"post-run boot query: {reply2.hex(' ')}")
        except Exception as exc:
            print(f"post-run boot query failed: {type(exc).__name__}: {exc}")
    finally:
        try:
            usb.util.release_interface(dev, 5)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
