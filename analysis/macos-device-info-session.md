# Standard device-information client and macOS authorization

Date: 2026-11-02. Follow-up to [ImageCaptureCore discovery](macos-imagecapture-ptp-route.md).
This work does not establish a RAM loader or independent recovery procedure.

Later extension: the [standard StorageIDs live pass](standard-storage-ids-live.md)
adds a second fixed mode to the same stable application. Each invocation still
submits only one selected metadata read; the original information mode and
historical observations below retain their scope. The new live pass sent
`0x1004`, received one ID and completed explicit session close.

The following [known-ID StorageInfo attempt](standard-storage-info-live.md)
adds a third fixed mode but received no host discovery callbacks and sent no
explicit request. Removable-volume authorization is again a relevant host
boundary; no camera rejection or new successful opcode is claimed.

The user then confirmed allowing the prompt several minutes after it appeared.
An unchanged single retry succeeded with `0x1005` and completed session close;
the StorageInfo report preserves both attempts and the reported media metadata.

## Client boundary

[imagecapture_device_info.m](imagecapture_device_info.m) is a separate client
from the discovery-only helper. Its sole explicit PTP operation is standard
`GetDeviceInfo` (`0x1001`), with no parameters or outgoing data. It uses the
delegate API and command-container representation from Apple's archived
PTPPassThrough sample. The framework manages wire transaction IDs; the returned
ID is recorded, not compared against our placeholder zero.

The client discovers local cameras, requires exactly one `04cb:030a` camera
named `X-HF1` with PTP capability and no already-open client session, opens a
framework session, waits for complete catalog readiness, sends one information
request, validates a 12-byte successful response and bounded incoming dataset,
then requests close and waits for its callback. It rechecks the retained
device object's IDs and session before sending. The USB port/address is not
hard-coded. Only aggregate catalog item count is recorded, without names,
paths, thumbnails or photo contents.

Apple's SDK states that opening a session permits content enumeration.
**The framework's initialization/catalog traffic is not constrained by our
one-command allowlist and has not been captured on the wire.** This is ordinary
Image Capture access, not an isolated USB packet or an audited full OS stack.
No capture, import, delete, upload, property/clock setter, reset, vendor opcode,
factory marker or driver detach is implemented. No process is killed.

The discovery timeout is 60 seconds to permit a macOS authorization prompt;
session/catalog, command and close stages have 30/10/10-second deadlines.
Failure stops without retries, resets or additional explicit cleanup commands.
If an error occurs after opening, session closure is not guaranteed: framework
behavior on client exit is not qualified. A positive result requires the
explicit close callback, not simply receiving a dataset.

The dataset is written exclusively to a private temporary file with mode 0600.
The wrapper decodes it using [ptp_device_info.py](ptp_device_info.py), omits
the serial field, prints the summary and removes its own capture directory.
The raw dataset is neither printed nor retained in the repository.

## Reproduce / prepare without camera access

```sh
# Compile and package only; does not launch or access a camera:
bash analysis/run-imagecapture-device-info.sh --build

# Live standard session, after checking the boundaries above:
bash analysis/run-imagecapture-device-info.sh --read-device-info
```

The wrapper retains its application at
`analysis/output/macos-device-info/XHalfDeviceInfo.app` (ignored). It rebuilds
only when its source/script hash changes or its executable is missing. A stable
path avoids continually deleting/recreating the requesting application; it
does not guarantee that macOS will preserve authorization across builds.

The helper compiled with `-Wall -Wextra -Werror`. Usage without the explicit
read mode returns before initializing AppKit. Three offline decoder test
methods pass; these are not live session or camera tests.

## Static firmware handler review

Artifact: official 2.00 main image, ARMv7 little-endian at `0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Local Ghidra 12.1.4 decompilation and original ARM inspection were used; no
fresh REA runtime observation or firmware emulation is claimed.

The ordinary dispatcher compares opcode `0x1001` at `0x402e7944..4c`, selects
the pointer at `0x402e7dfc`, and reaches handler `0x402e8b94`. It builds a
DeviceInfo dataset in the allocated RAM buffer from capability tables rooted
at `0x4136c79c` and five indexed device strings. It sets reply length at
`0x4136b680` and success through `0x40334f34`. The inspected callees
`0x401cafa0` / `0x401cafb8` serialize little-endian values into the supplied
buffer; `0x401c051c` copies indexed device strings. No flash/factory/RTC write
appears in these bodies. This is bounded static evidence for the known image,
not a guarantee about the installed camera image or all OS-generated requests.

Pseudocode: [ptp-device-info-handler.txt](output/ptp-device-info-handler.txt).
Existing [session handler review](ptp-reader-and-session-audit.md) and
[PTP UI/lifecycle analysis](ptp-ui-state-and-exit.md) retain their limits;
ordinary Open/CloseSession handlers have RAM allocation/free effects, and
application callbacks are a separate boundary.

## First live attempt and privacy diagnosis

The initial information-client launch used a temporary app and an eight-second
discovery timeout. It returned `no_unique_camera`, with zero explicit PTP
commands, no opened session, no completed catalog and no dataset. A subsequent
discovery-only run also received zero discovery callbacks. Cached IORegistry
still showed exactly one `04cb:030a` PTP interface owned by `ptpcamerad`.

Focused host logs then showed the camera agent requesting
`kTCCServiceSystemPolicyRemovableVolumes` access for `XHalfDeviceInfo`:
the preflight reply was unknown, and no completed authorization reply appeared
before that helper exited. Earlier successful discovery launches had received
a reply containing `result=true`. The logs therefore implicate authorization
as well as application lifecycle. The previous explanation that packaging or
the event loop alone fixed discovery was incomplete. Other unrelated App
Intents XPC errors do not explain the camera authorization boundary.

No privacy database was modified, no TCC permissions were reset and no private
bypass entitlement was used. macOS user authorization is distinct from tool
sandbox escalation. The user was asked whether the access prompt was visible.
The stable application and longer discovery deadline were then prepared for
one further attempt. See the result below; do not infer firmware rejection
from an empty browser result.

## Stable-app attempt result

The subsequent 60-second discovery attempt also returned `no_unique_camera`,
with zero callbacks, zero explicit PTP commands, no opened session and no
dataset. This does **not** prove that authorization was denied; the user's
observation of the macOS prompt was still pending. No session success,
firmware version, advertised opcode list or close-session behavior has been
observed. Do not run repeated attempts until the prompt/authorization state
has been clarified. A normal reconnect is not yet justified as the cure for
this host-side boundary, and no camera button experiment is needed here.

## Successful read after user authorization

The user subsequently confirmed allowing macOS access. One retry using the
unchanged stable app then completed successfully. Sanitized result:
[xhalf-device-info-2026-11-02.json](output/xhalf-device-info-2026-11-02.json).

Observed callbacks/results:

- One discovered `FUJIFILM` / `X-HF1`, USB identity `04cb:030a`.
- Session opened and catalog-complete callback received; aggregate count 43
  reported catalog items. This count is not a verified photo count.
- Exactly one explicit passthrough operation, `0x1001`, returned success
  `0x2001` and a 251-byte dataset. The framework-supplied returned transaction
  ID was 141; this does not count or identify preceding framework requests.
- The explicit close-session callback completed without error, and the client
  reported `hasOpenSession=false` at exit.
- Offline decoding succeeded. The serial field was omitted; the private raw
  dataset was removed by the wrapper. No raw capture or photo data is retained.

The reply advertises 26 operation codes, nine events and seven device
properties. The previously identified marker-dependent factory range
`0x1021..0x1027` is absent from the advertised list. This is consistent with
ordinary retail operation; advertisement is not proof of hidden-handler
absence, successful handling, safe semantics, loader entry or recovery.
No operation from that range or any other advertised code was probed.

The reported device-version string is **`01.00`**. It is not recorded as a
confirmed installed Fujifilm release. In the inspected 2.00 image,
`0x4018d4bc..0x4018d4f4` recognizes configuration key
`PTPIP_DEVICE_VERSION` (literal `0x40b0dacf`) and passes the parsed value to
string setter `0x401c0590` with index 3. That setter populates the string
table rooted at `0x40fc7df0`, which the DeviceInfo handler reads through
`0x401c051c`. Thus the field has a configuration-driven population path;
its relationship to the camera menu's firmware version is not established.
The source configuration value itself has not been recovered in this pass.
Relevant ARM excerpts: [version configuration path](output/ptp-device-version-config.txt).

This demonstrates one successful ordinary framework session/read/close cycle
on the connected camera after user authorization. No wire capture or complete
framework traffic audit occurred. No camera-setting command, factory enable,
RAM write/upload, firmware update, reset or recovery experiment was requested
by our client. Recovery and temporary firmware loading remain unconfirmed.

Next: correlate the observed operation list against existing static handlers
and their callback registrations before considering another live request.
Actual Fujifilm release identity still needs independent confirmation; do not
treat `01.00`, the host module's `1.0`, or USB `bcdDevice=0x0200` as that proof.

Follow-up: [advertised-operation review](ptp-advertised-operation-review.md)
maps all 26 codes offline and checks selected handler bodies/routing across
four versions. No additional live operations were performed.
