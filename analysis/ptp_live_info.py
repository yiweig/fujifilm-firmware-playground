#!/usr/bin/env python3
"""Read-only standard PTP GetDeviceInfo from the live X half.

Linux/pyusb. Opens a PTP session, sends GetDeviceInfo (0x1001), reads
the data container and response container, decodes the dataset with the
offline decoder, and closes the session. No writes, no property sets.
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ptp_device_info import decode_device_info  # noqa: E402

VID, PID = 0x04CB, 0x030A
EP_OUT, EP_IN = 0x03, 0x82
TIMEOUT_MS = 3000
HEADER = struct.Struct("<IHHI")


def read_container(dev) -> bytes:
    raw = bytes(dev.read(EP_IN, 512, timeout=TIMEOUT_MS))
    if len(raw) < 12:
        raise SystemExit(f"short container: {len(raw)}")
    length = struct.unpack_from("<I", raw, 0)[0]
    while len(raw) < length:
        raw += bytes(dev.read(EP_IN, min(512, length - len(raw)), timeout=TIMEOUT_MS))
    if len(raw) != length:
        raise SystemExit(f"container length mismatch: {len(raw)} vs {length}")
    return raw


def command(opcode: int, params: tuple[int, ...], txn: int) -> bytes:
    body = struct.pack("<" + "I" * len(params), *params) if params else b""
    return HEADER.pack(12 + len(body), 1, opcode, txn) + body


def main() -> None:
    import usb.core
    import usb.util

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        raise SystemExit(f"camera {VID:04x}:{PID:04x} not found")
    try:
        if dev.is_kernel_driver_active(0):
            dev.detach_kernel_driver(0)
    except Exception:
        pass
    dev.set_configuration()
    usb.util.claim_interface(dev, 0)
    try:
        def exchange(opcode: int, params: tuple[int, ...], txn: int) -> bytes:
            dev.write(EP_OUT, command(opcode, params, txn), timeout=TIMEOUT_MS)
            return read_container(dev)

        r = exchange(0x1002, (1,), 0)
        code = HEADER.unpack_from(r)[2]
        print(f"OpenSession(1): code=0x{code:04x}")
        txn = 1
        if code == 0x201E:
            r = exchange(0x1003, (), txn)
            print(f"CloseSession(stale): code=0x{HEADER.unpack_from(r)[2]:04x}")
            txn += 1
            r = exchange(0x1002, (2,), txn)
            code = HEADER.unpack_from(r)[2]
            print(f"OpenSession(2): code=0x{code:04x}")
            txn += 1
        if code != 0x2001:
            raise SystemExit("OpenSession failed")

        r = exchange(0x1001, (), txn)
        txn += 1
        length, kind, code, actual = HEADER.unpack_from(r)
        print(f"data container: len={length} kind={kind} code=0x{code:04x} txn={actual}")
        if kind != 2:
            raise SystemExit("expected data container")
        info = decode_device_info(r[12:])
        for key in ("manufacturer", "model", "device_version",
                    "vendor_extension_description", "standard_version",
                    "vendor_extension_id", "vendor_extension_version",
                    "functional_mode"):
            print(f"{key}: {info[key]!r}")
        resp = exchange(0x1003, (), txn)
        print("CloseSession:", resp.hex(" "))
    finally:
        try:
            usb.util.release_interface(dev, 0)
        except Exception:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
