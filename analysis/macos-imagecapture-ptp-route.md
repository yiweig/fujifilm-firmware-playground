# ImageCaptureCore as a possible PTP transport

Date: 2026-11-02. Host SDK/documentation inspection only. No ImageCaptureCore
browser, camera session or PTP request was started. The separately completed
cached-registry observation is in [USB identity](xhalf-usb-identity.md).

## Finding

The camera is currently matched to normal PTP identity `04cb:030a`, with
`ptpcamerad` owning interface 0. An alternative to a custom exclusively claimed
libusb handle is to investigate Apple's existing Image Capture facility.
This is a promising host transport lead, not a demonstrated live protocol
client, loader entry, memory-dump API or recovery mechanism.

Apple documents
[`ICCameraDevice.requestSendPTPCommand`](https://developer.apple.com/documentation/imagecapturecore/iccameradevice/requestsendptpcommand(_:outdata:completion:))
for asynchronous PTP requests. Its delegate-based variant documents a
capability check for `ICCameraDeviceCanAcceptPTPCommands`. The installed
`ICCameraDevice.h` declares both variants. The block variant declares its
arguments as command bytes, optional outgoing data and a completion receiving
two data objects plus an error. Response/data ordering and actual command
container conventions must be verified before implementing a parser; parameter
names alone are not a wire-format specification.

Installed `ICDeviceBrowser.h` says the browser communicates asynchronously
with an Image Capture agent process. `ICDevice.h` exposes USB vendor/product
IDs and open/close-session methods. Together these suggest a possible route
through the existing system service. They do not establish device ownership,
which operations the agent automatically performs, or that no direct USB
transition occurs inside the framework.

## Session and catalog boundary

The installed `ICCameraDeviceDelegate` header says the ready-for-requests
notification follows content enumeration, and that opening a session is
required to enumerate contents and make the device ready for requests.
The camera class also exposes catalog-progress and media-content properties.
Thus opening a framework session must not be described as one isolated
GetDeviceInfo request or a proven bus-passive metadata check.

This pass does not establish whether a PTP command can be sent before catalog
completion, whether an agent session is reused, what initialization requests
are sent automatically, or how timeout/cancellation/close affect the camera.
Neither the browser nor session API was invoked merely to discover those
effects experimentally. No process was stopped and no driver was detached.

## Next concrete qualification

Before using this route, inspect the session/request lifecycle and command
format, then prepare an identity-filtered, bounded client with an explicit
allowlist. The first useful target would be standard device-information data,
not vendor/factory operations or arbitrary memory. It should avoid capture,
delete, clock/property changes, imports, resets and automatic recovery/retries.
Any incidental framework catalog requests must be accounted for separately.
The previous restricted factory reader is not a drop-in replacement: it
expects response-only exchanges, whereas device information has an incoming
data phase and this framework manages its own session/transaction lifecycle.

SDK files inspected under:

```text
/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk/System/Library/Frameworks/ImageCaptureCore.framework/Headers/
  ICCameraDevice.h
  ICDevice.h
  ICDeviceBrowser.h
```

This does not change the status of RAM loading or independent recovery.
Framework transport remains unqualified; no fresh REA or camera protocol
observation is claimed.

## Follow-up: live discovery succeeds from an application bundle

Later on 2026-11-02, a restricted helper was built and run. Unlike the initial
documentation-only pass above, this **did start ImageCaptureCore discovery**.
No explicit session, PTP request, import, capture, setting change, reset,
process termination or interface claim is implemented in the helper.
Framework/agent wire traffic was not captured or qualified, so this is not a
claim of bus-passive operation or zero incidental agent requests.

Direct command-line execution found no matching camera, both in the restricted
environment and with host access. Initializing Cocoa did not resolve that.
A contemporaneous cached-registry check still found the same `04cb:030a`
PTP interface owned by `ptpcamerad`.

Launching the **same executable as an application bundle via LaunchServices**
then produced one discovery callback and one matching device:

| Property | Observed value |
| --- | --- |
| Name | `X-HF1` |
| USB identity | `04cb:030a` |
| Transport | `ICTransportTypeUSB` |
| Accepts PTP commands | true |
| Client has open session | false |
| Device module | `/System/Library/Image Capture/Devices/PTPCamera.app` |
| Module version | `1.0` — host module version, **not camera firmware** |

Thus discovery through Apple's service works on this connection. A first
wrapper run with a manually pumped foundation run loop also returned no
callbacks. Switching to the normal `NSApplication.run` event loop, with an
eight-second timer and an event to wake the loop at shutdown, made the saved
wrapper succeed end to end with the same device properties above. Packaging
and application lifecycle are implicated, but their individual necessity and
discovery reliability across connections have not been established. Do not
repeat the unsuccessful bare-binary
invocation or infer USB absence from its empty result. No device serial,
UUID, media list or transient USB identifiers were retained.

Reproduce using [run-imagecapture-inventory.sh](run-imagecapture-inventory.sh):

```sh
bash analysis/run-imagecapture-inventory.sh --browse
```

This compiles [imagecapture_inventory.m](imagecapture_inventory.m), builds a
temporary background application and launches an eight-second discovery pass.
Its only accepted mode is `--browse`; it cannot open a session or send PTP.
No argument means usage only. The wrapper removes only its own temporary build
directory after exit. The final wrapper compiled with `-Wall -Wextra -Werror`,
discovered the camera, exited successfully and removed its temporary directory.

## Qualified command representation; protocol execution still pending

Apple's archived primary-source
[PTPPassThrough sample](https://developer.apple.com/library/archive/samplecode/PTPPassThrough/PTPPassThrough.zip)
was downloaded as source and inspected, **not built or executed**. ZIP SHA-256:
`3474abf9cb5dacd0bb9afcfa7f312e31b9306d030436395e38f6baffc4b34657`.
In `PTPProtocolHelpers.m`, `commandBuffer` (lines 259–272) builds a little-endian
12-byte header followed by parameters, with container type 1. Its comment
explicitly states the supplied transaction ID is ignored by ImageCaptureCore.
The sample's controller opens a session upon discovery before user commands;
it does not establish a supported session-free request route. The delegate
callback separates incoming data from response-container bytes, resolving the
block-argument naming ambiguity for a future delegate-based client.

[ptp_device_info.py](ptp_device_info.py) now supplies only a fixed `GetDeviceInfo`
command encoder and an **offline** dataset decoder. It has no live transport.
The decoder expects dataset bytes without a USB container; it validates and
omits the serial field. Three test methods passed, including every truncated
prefix of a synthetic dataset, oversized count/input, invalid string encoding
and terminator, trailing bytes and serial-output exclusion. These are host
parser checks, not firmware or camera tests. Dataset layout was cross-checked
against Ricoh's primary-source [GetDeviceInfo documentation](https://github.com/ricohapi/theta-api-specs/blob/main/theta-usb-api/operation/get_device_info.md);
that source supplies layout, not Fujifilm capability evidence.

Next: qualify a session-based, fixed-`0x1001` request and its framework catalog
activity/exit behavior. Device firmware version and advertised opcode list
remain unread. Discovery success does not establish RAM loading or recovery.

Later follow-up: [standard information session and macOS authorization](macos-device-info-session.md)
adds a separate fixed-command client and a bounded firmware handler review.
Live attempts still stopped at discovery, before session opening or explicit
PTP traffic. Agent logs show removable-volume authorization requests; the
successful earlier discovery runs had positive authorization replies. Thus
the application/event-loop explanation above was incomplete. A stable app
build is now retained locally, and the user's observation of the access prompt
is needed before repeating the live read.
