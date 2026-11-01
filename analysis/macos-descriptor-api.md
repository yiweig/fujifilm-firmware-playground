# macOS configuration-descriptor retrieval

Investigation date: 2026-11-01. This is host-side static analysis and local
validation, not a camera runtime test. The camera can remain unplugged.

## Finding and purpose

The normal-mode registry observation identified the camera and its PTP interface,
but did not expose endpoint addresses. macOS's installed IOUSBLib provides a
configuration-descriptor path that does not require an exclusive `USBDeviceOpen`.
This offers a smaller next experiment than claiming the interface for bulk PTP.
It does not establish access to factory data or a recovery mechanism.

The installed SDK's `IOUSBLib.h` documentation for
`GetConfigurationDescriptorPtr` explicitly says the device need not be open.
Static inspection of the installed plugin agrees: plugin startup creates a
kernel user-client connection and fills its descriptor cache without calling
the exclusive-open method. `IOServiceOpen` is that kernel connection, and must
not be confused with exclusive `USBDeviceOpen`.

This is **not proven bus-passive**. The observed userspace code delegates copying
descriptors to kernel method selector 4; its current kernel implementation was
not recovered. Apple documents cache-miss `GET_DESCRIPTOR` retrieval for a
[related DriverKit configuration-descriptor API](https://developer.apple.com/documentation/usbdriverkit/iousbhostdevice/copyconfigurationdescriptor-6qgew).
That is a reason to allow for descriptor traffic, not proof that this legacy
IOUSBLib method has the same kernel implementation. Selector 4 is a kernel IPC
method number, not a USB request code.

## Artifact and evidence

Host: macOS 26.6.2, build 25G83. Inspected binary:

`/System/Library/Extensions/IOUSBHostFamily.kext/Contents/PlugIns/IOUSBLib.bundle/Contents/MacOS/IOUSBLib`

Universal binary SHA-256:
`3914767b2821899c42db6a3cdd2d96a8a1e9b18635a45b3cd9ad221ab6fc657c`

The arm64e slice was extracted with `lipo -thin arm64e` for REA/Ghidra analysis;
slice SHA-256:
`1d2b9949280a016b9a87e036377a7919672e82c27a5fc686f76a9618a9750196`.
Addresses below belong to that Mach-O slice, not camera firmware addresses.
The REA session was closed after exporting six complete evidence records to
ignored local file `analysis/output/rea-usb-descriptor-evidence.json`.

| Method/address | Observed behavior | REA evidence ID |
| --- | --- | --- |
| `GetConfigurationDescriptorPtr`, `0x1f70` | Checks initialization and index, fills cache if necessary, returns cached pointer; no exclusive-open check. | `ev_a9cca4ad5db011bb531a62726cd81201aec91650de268fc304b1e9d59852f2fe` |
| `CacheConfigDescriptor`, `0x1b3c` | Calls `IOConnectCallMethod` selector 4 first for four header bytes, then for the descriptor length; allocates and retains the result. Kernel behavior remains unresolved. | `ev_18762aabea85cf752abe32d7166db9a3805ea17344d9583e6b0845ad1226c043` |
| `start`, `0x1634` | Calls `IOServiceOpen`, copies registry properties, allocates the pointer table, and fills the descriptor cache during plugin creation. | `ev_467864baf335e3e13392f62af100498bda57a65225d06fc7333c6c8bfc1dc8e1` |
| `stop`, `0x1cc0` | Its indirect close path is conditional on the exclusive-open flag. | `ev_d2f8509d85ffb9007fdd77e84a901de47cbaba4f8e0e30ca7576ca7b5e2fc029` |
| Constructor, `0xeec` | Initializes exclusive-open, ready and cache state to zero. | `ev_44fecc537a7f293b183a70f837f916d767c5f4cc9403457d02f48055467dcbef` |
| Destructor, `0xfbc` | Frees cached descriptors, closes the kernel user-client connection, and releases retained objects. | `ev_9be6230c0b66fe8279dc4c237a7d8ae6d01a33520aa77eaee49278ab5b9a118f` |

The installed SDK header is under
`/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk/System/Library/Frameworks/IOKit.framework/Versions/A/Headers/usb/IOUSBLib.h`.
No current kernel selector implementation is asserted from historical Apple
source. The kernel collection symbol search did not establish it.

## Helper and validation

[`read_usb_configuration.c`](read_usb_configuration.c) implements only this
metadata path. Default invocation prints usage without accessing the registry.
`--self-test` uses synthetic descriptors only. A separate explicit mode,
`--read-descriptor REGISTRY_ENTRY_ID`, selects one exact registry node and checks
class `IOUSBHostDevice`, VID/PID `04cb:030a`, one configuration, and active
configuration 1 before creating the plugin.

It validates the returned configuration descriptor's header, total length
(9–4096 bytes), configuration value and descriptor-length walk before outputting
JSON with descriptor bytes. Endpoint interpretation remains the stricter job of
`Endpoints.from_configuration` in `ptp_usb_transport.py`.

It does not call device/interface exclusive-open, claim, reset,
`SetConfiguration`, `DeviceRequest`, or PTP methods. Plugin creation itself
populates the cache through the path described above. Failure exits without
retrying, detaching drivers, stopping `ptpcamerad`, or changing configuration.
An OS/plugin failure or unexpected device response remains possible; this is
not an end-to-end safety certification of macOS or the camera.

Local checks completed:

```sh
xcrun clang -std=c11 -Wall -Wextra -Werror -Wno-deprecated-declarations \
  analysis/read_usb_configuration.c -framework IOKit -framework CoreFoundation \
  -o /private/tmp/xhalf-usb-configuration
/private/tmp/xhalf-usb-configuration --self-test
```

Compilation succeeded; the synthetic valid, zero-length-entry and truncated
descriptor checks passed. No live descriptor mode was executed in this pass.
The registry inventory now includes `IORegistryEntryID` so a future fresh
observation can select the exact device rather than assuming a USB address.

## Next experiment and limits

With the camera normally connected, obtain a fresh registry inventory, verify the
single matching device and use that observation's registry entry ID for this
helper. No SD markers or service-button combination are needed. An entry ID is
temporary; do not reuse one after reconnecting. Inspect the descriptor and feed
it into the existing strict endpoint parser before designing interface ownership.

This step sends no factory PTP commands. It does not resolve `ptpcamerad`
ownership, factory-read freshness, factory registration lifetime, service-entry
calibration hazards, RAM loading, or independent recovery. Those remain the
separate constraints documented in the linked investigation reports.

## Completed live metadata check

Later on 2026-11-01, the user connected the camera normally. A fresh inventory
identified exactly one matching device and its current entry ID. The helper's
explicit descriptor-read mode succeeded; its 39-byte output passed the strict
endpoint parser. The measured endpoints and exact descriptor bytes are recorded
in [the USB identity report](xhalf-usb-identity.md#configuration-descriptor-read-2026-11-01).
This qualifies descriptor retrieval on this host/device connection, not bulk
PTP access or absence of underlying USB descriptor requests. No exclusive-open,
PTP, driver detach, service entry, or marker-file operation was performed.
