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

## Follow-up observation, 2026-11-01

After the user connected and normally powered on the camera, cached IORegistry
inspection found one device node matching `04cb:030a`. No PTP session, USB
interface claim, driver detach or camera command was performed.

| Field | Observed cached value |
| --- | --- |
| Active configuration | 1 (`kUSBCurrentConfiguration` and interface `bConfigurationValue`) |
| Interface / alternate setting | 0 / 0 |
| Class / subclass / protocol | 6 / 1 / 1 |
| Endpoint count | 3 |
| Interface user-client owner | `ptpcamerad` |
| `bcdUSB` | `0x0200` |
| `bcdDevice` | `0x0200` |

`bcdDevice` is a USB device-release field; this observation does not independently
establish the installed camera firmware version. No negotiated-speed conclusion
was drawn from the numeric `Device Speed` registry field in this pass.

No raw configuration descriptor or endpoint-address properties were found in
the inspected camera/device/interface subtrees. The endpoint addresses and
packet sizes therefore remain unmeasured. No fallback endpoint addresses were
guessed. Transient location, address and process IDs, raw serial strings and
serial-derived hashes are not recorded in this report.

The inventory tool initially omitted `ioreg -l`, which returned node structure
without USB properties and falsely yielded no matches. It now reads the
`IOService` subtree for `IOUSBHostDevice` with `-a -l -r -c`; this includes
interface entries absent from the inspected `IOUSB` plane. Regression tests
cover property inclusion, active-configuration handling and owner extraction.
The complete host-side suite now has 19 passing test methods.

The SDK header `IOKit/usb/IOUSBLib.h`, documentation for
`GetConfigurationDescriptorPtr`, states that the device need not be open to
retrieve the configuration-descriptor pointer. This is a promising separate
metadata route, but it was not executed: not requiring exclusive open alone
does not prove all current plugin initialization/caching behavior is free of
USB bus requests. The installed implementation and lifecycle need inspection
before treating it as equivalent to the completed cached-registry check.

## Configuration descriptor read, 2026-11-01

After the user reconnected the camera (possibly on another port), a fresh
inventory found exactly one `04cb:030a` device with active configuration 1 and
interface 0 owned by `ptpcamerad`. The fresh registry entry ID was used only for
this observation; location/address/entry IDs are not stable across connections.

The reviewed native helper successfully retrieved configuration index 0 without
exclusive device open, interface claiming, PTP operations, driver detachment,
or stopping `ptpcamerad`. This was an actual metadata API call, not just cached
registry inspection. Whether macOS issued USB descriptor traffic is unobserved.
See [the API investigation](macos-descriptor-api.md) for the inspected path.

The returned 39-byte descriptor was:

```text
0902270001010080fa090400000306010100070582020002000705030200020007058403080001
```

| Field | Observed descriptor value |
| --- | --- |
| Configuration value / interfaces | 1 / 1 |
| Interface / alternate setting | 0 / 0 |
| Class / subclass / protocol | 6 / 1 / 1 |
| Bulk IN (camera to host) | `0x82`, maximum packet 512 bytes |
| Bulk OUT (host to camera) | `0x03`, maximum packet 512 bytes |
| Interrupt IN (events) | `0x84`, maximum packet 8 bytes, interval field 1 |

The exact bytes passed `Endpoints.from_configuration(raw, 1)`, producing
`Endpoints(bulk_in=130, bulk_out=3, packet_size=512)`. The interrupt endpoint
details were decoded from its final seven-byte descriptor. No endpoint transfer
was attempted; ownership and native bulk transport remain unqualified. No
service mode, SD markers, factory reads, or camera storage writes were used.
