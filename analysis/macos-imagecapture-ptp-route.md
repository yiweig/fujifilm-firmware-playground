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
