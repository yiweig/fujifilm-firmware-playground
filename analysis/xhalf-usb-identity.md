# Fujifilm X half USB identity

Observed on macOS on 2026-10-29 with the camera powered on in its normal USB
mode. These observations were passive: USB registry/descriptors were read, but
no PTP commands or vendor-specific requests were sent.

## Stable identity

| Field | Value |
| --- | --- |
| USB vendor ID | `0x04CB` (decimal 1227, FUJIFILM Corporation) |
| USB product ID | `0x030A` (decimal 778) |
| Product string | `USB PTP` |
| USB version/speed | USB 2.0 high-speed, 480 Mb/s |
| Configurations | 1 |

## Exposed interface

| Field | Value |
| --- | --- |
| Interface number | 0 |
| Interface class | 6 (Still Image) |
| Interface subclass | 1 |
| Interface protocol | 1 (PTP) |
| Endpoint count | 3 |
| macOS owner | `ptpcamerad` |

Normal USB mode exposed no separate mass-storage volume or USB serial/debug
interface. The three PTP endpoints are likely bulk-in, bulk-out, and an event
endpoint, but their individual descriptors were not captured in this pass.

## Physical confirmation

The device was identified using an unplug/replug comparison:

1. With the camera connected, `04CB:030A` appeared as `USB PTP` and was claimed
   by macOS `ptpcamerad`.
2. When only the camera was unplugged, `04CB:030A` and its PTP interface
   disappeared while the other attached USB hubs, keyboard, and video adapter
   remained.
3. Reconnecting the camera restored `04CB:030A` with the same interface class,
   protocol, speed, and endpoint count.

This establishes that `04CB:030A` is the connected X half, rather than one of
the other USB devices.

## Transient values

USB location IDs, registry object IDs, device addresses, session IDs, and the
PID of `ptpcamerad` can change after reconnection and must not be used as stable
camera identifiers. The camera's USB serial descriptor was observed but is not
recorded here.
