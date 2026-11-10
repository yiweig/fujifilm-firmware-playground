#!/usr/bin/env python3
"""Read-only probe of the vendor BootMethod query in the usbt state.

The V50 USB_CAM INI declares:
  BootMethod_Request = 0x70
  BootMethod_Index   = 0x0
  BootMethod_Value   = 0x0
  BootMethod_Length  = 0xb

The vendor DLL fills the value with the ISP file size. This probe tries
zero and a few small values, and never writes to the device.
"""
from __future__ import annotations

import sys

VID, PID = 0x2AAD, 0x6500
TIMEOUT_MS = 3000


def main() -> None:
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

    candidates = [
        (0x0000, 0x0000, 0x0B),
        (0x0000, 0x0000, 0x05),
        (0x0001, 0x0000, 0x0B),
        (0x0200, 0x0000, 0x0B),
        (0x0000, 0x0000, 0x10),
    ]
    for wvalue, windex, length in candidates:
        try:
            data = bytes(dev.ctrl_transfer(0xC0, 0x70, wvalue, windex, length,
                                            timeout=TIMEOUT_MS))
            print(f"wValue=0x{wvalue:04x} wIndex=0x{windex:04x} len={length}: "
                  f"{len(data)} bytes {data.hex(' ')}")
            if len(data) >= 5:
                print(f"  method={data[0]} addr=0x{int.from_bytes(data[1:5], 'little'):08x}")
        except Exception as exc:
            print(f"wValue=0x{wvalue:04x} wIndex=0x{windex:04x} len={length}: "
                  f"{type(exc).__name__}: {exc}")

    try:
        usb.util.release_interface(dev, 5)
    except Exception:
        pass
    usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
