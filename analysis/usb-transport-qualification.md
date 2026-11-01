# USB transport preparation and session-notification follow-up

Date: 2026-11-01. Official firmware 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Firmware addresses are runtime ARM addresses at base `0x40000000`.
All firmware execution and transport tests in this pass were offline.
No camera commands, driver detach, USB enumeration/claim, process termination,
card marker creation, dependency installation or live inventory was performed.

## What is ready

- [`ptp_usb_transport.py`](ptp_usb_transport.py) implements bounded bulk
  framing and a native libusb bulk-call adapter for the restricted reader.
- [`usb_registry_inventory.py`](usb_registry_inventory.py) can inspect a saved
  IORegistry plist or read cached macOS IORegistry metadata. It never opens USB.
- Two session notifications were reproduced through original ARM UI handlers
  under explicitly synthetic USB startup state.
- All 17 host-side tests pass, including the full 132-command reader through
  the new bulk framing layer. No native library was loaded by these tests.

This is not an end-to-end live USB client. The native adapter requires an
already opened, identity-verified, exclusively claimed handle. Discovery,
firmware/model identity verification, ownership acquisition and handle release
remain separate, unimplemented integration steps. There is no camera-access
CLI in the transport module.

## Session notifications: bounded original-code execution

The PIMA dispatcher submits `0x62510018` after OpenSession and `0x62510019`
after CloseSession through `sysgMsgSend` (`0x4006993c`). Queue class 1 is used;
the sender can invoke a filter stored at `0x40e6354c` before enqueueing.
The explicit setter is `0x40069b80`, reached through `0x4006f3d8`.
`0x4006f34c` receives class-1 messages and is used by the application loop.

Application loop `0x40402574` receives a message, calls diagnostic wrapper
`0x403ffe58`, dispatches through `0x40400d50`, then calls that wrapper again.
The main dispatcher forwards unhandled notifications to `0x404db31c`.
With UI state `0x8000` at `0x40e66958`, the route is:

```text
40400d50 -> 404db31c -> 404d665c -> 405c9718 -> return
```

`0x405c9718` is named `UIMode_DpsCreateHandler` by its shipped diagnostic.
Neither session notification matches its work branches. The inspected
diagnostic wrapper includes conditional logging and RAM bookkeeping; its
complete execution is excluded from the experiment below.

[`scripts/ReproducePtpSessionNotifications.java`](scripts/ReproducePtpSessionNotifications.java)
verifies the firmware hash and supplies each notification directly to
`0x40400d50`. It zero-initializes synthetic runtime RAM, sets state `0x8000`,
and executes the original instruction path above. Only logging function
`0x40004064` is stubbed. Unexpected execution outside the inspected handlers
fails the experiment.

Both cases returned, made two logging calls each, and left image/global memory
`0x40000000..0x413fffff` unchanged. The emulated stack is outside that range.
No file, RTC or calibration worker was executed on those paths.

This does not model the queue/filter, concurrent events, diagnostic wrappers,
other UI substates, startup, USB teardown or physical hardware. In particular,
it does not establish that a connected retail camera remains in `0x8000`.
The narrower session-notification question is substantially better supported;
the complete lifecycle is still not certified read-only.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproducePtpSessionNotifications.java analysis/output/ptp-notifications.txt
```

## Transport boundary

The bulk adapter accepts only a pre-existing native handle and descriptor-derived
endpoints. Its only bound native function is `libusb_bulk_transfer`. It has no
discovery, open, claim, detach, configuration/alternate-setting change, halt
clear, reset or release operation. Caller-supplied handle identity and ownership
are therefore prerequisites, not properties established by this adapter.

Endpoint parsing requires an already active configuration and the observed
interface shape: interface 0, alternate 0, class/subclass/protocol 6/1/1,
two bulk endpoints and one interrupt-IN endpoint. Bulk directions and addresses
come from the descriptor; packet size must be 64 or 512 bytes. Unsupported,
ambiguous or malformed configurations are rejected. No endpoint addresses
have yet been measured from this camera; those in tests are synthetic.

The transport validates the read allowlist again before any native call. Each
exchange has a two-second total deadline. It requires a complete command write,
assembles a bounded 12/32-byte PTP response from fragments, and checks its schema
and transaction ID. Empty, oversized, coalesced or malformed replies stop the
transport. It never resends a partial write or recovers a stalled endpoint.

Native API choices were checked against primary libusb documentation:
[bulk I/O](https://libusb.sourceforge.io/api-1.0/group__libusb__syncio.html) and
[device handling](https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html).
The former explicitly requires checking transferred-byte counts even on timeout;
the adapter retains that count in its exception and does not retry. The latter
separates claiming, driver detachment and configuration changes; none is hidden
inside this adapter. This documents the implementation choice, not macOS runtime
qualification or proof about every OS backend effect.

No libusb library was found at the inspected standard Homebrew/local paths or
by `ctypes.util.find_library('usb-1.0')`. Native camera testing will require an
available library and a separately reviewed ownership/integration procedure.
Nothing was installed. The existing macOS `ptpcamerad` ownership observation
remains relevant; it was not stopped or detached.

## Cached registry inspection

The next useful physical check is metadata-only:

```sh
python3 analysis/usb_registry_inventory.py --read-registry
```

The corrected tool runs only
`/usr/sbin/ioreg -a -l -p IOService -r -c IOUSBHostDevice` and parses its plist output. It
prints matching device nodes for `04cb:030a`, selected interface metadata,
location/address if available, and a hash of the serial string when present.
It excludes raw serial strings and unrelated devices. It does not create a
USB handle or send PTP/control/bulk requests.

If a cached raw configuration descriptor and active-configuration value are
available, the parser derives an endpoint plan. Otherwise it explicitly reports
`not established`; absence of cached descriptors is not evidence that endpoints
are missing on the camera. A separate reviewed descriptor-read method may then
be needed. A VID/PID match alone is not final device identity verification.

The tool was tested only on synthetic plist data in this pass. It was not run
against the current registry. The camera must be connected for a useful fresh
registry observation; OS activity on attachment is outside the tool's control.
No calibration marker or special startup combination is required for this check.

Follow-up: [the 2026-11-01 identity observation](xhalf-usb-identity.md)
records the completed registry-only check. It confirmed the interface and
`ptpcamerad` ownership, but no endpoint descriptors were exposed. It also
records the `ioreg -l` correction; there are now 19 passing host test methods.

## Tests and remaining work

```sh
python3 -m unittest discover -s analysis -p 'test_*.py' -v
```

17 test methods cover protocol safety, descriptor rejection, packet fragmentation,
partial writes, timeout counts/deadlines, rejection before native I/O, integration
with the complete reader, and serial/unrelated-device redaction. The ctypes ABI
test supplies a fake callable; it is not a native libusb or camera test.

Next: obtain cached descriptors/ownership observations with the camera attached,
then finish device/firmware preflight and ownership handling using those facts.
Do not create `EnablePTPAP.txt` or `EnableTest.txt` for that metadata check.
Factory-read freshness, registration revocation/reset and physical error recovery
remain limitations described in the earlier reports. No firmware flash or
service-mode experiment is implied by this transport preparation.
