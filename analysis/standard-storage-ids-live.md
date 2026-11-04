# Standard GetStorageIDs: first additional active metadata read

Observed 2026-11-03 local time. User authorized starting with reviewed reads,
then connected the camera normally and confirmed readiness. This pass submitted
one explicit PTP operation through ImageCaptureCore; no vendor commands,
property setters, payload uploads, captures, deletes or resets were implemented.

## Result

Standard `GetStorageIDs` (`0x1004`, no parameters, no outgoing data) succeeded:

| Observation | Value |
| --- | --- |
| Matched device | Unique `04cb:030a`, model `X-HF1` |
| Explicit passthrough requests | 1, opcode `1004` |
| PTP response | `0x2001` success, validated 12-byte response container |
| Returned data | 8-byte StorageIDs dataset |
| Storage count / ID | 1 / `0x00010001` |
| Session lifecycle | Opened, catalog ready, explicitly closed successfully |
| Client session at exit | Closed |

Evidence: [sanitized live result](output/xhalf-storage-ids-2026-11-03.json).
The framework reported 43 catalog items; this is not a verified photo count.
Its returned transaction ID was 141, not a count of our explicit requests.

This establishes a working live storage-enumeration operation. It does not
expose arbitrary files, raw sectors, RAM or boot ROM. The numeric ID is opaque;
it is not a CPU address and is not mapped to a physical medium or firmware
drive letter from this reply alone. No GetStorageInfo or object download was
sent explicitly in this pass.

## Why this operation was selected

The previous live DeviceInfo dataset advertised `0x1004`. The existing
[storage-path review](retail-ptp-file-acquisition.md) inspected its default
handler at `0x402e8f30` in the hash-verified 2.00 image and reproduced its
enumeration behavior offline. It skips enumeration index zero before returning
available storage IDs; helper `0x401b892c` supplies four slots, giving at most
three returned IDs in that implementation.

The handler builds numeric metadata and a response, using media-status and
ID lookup helpers rather than a caller-supplied address or path. No explicit
firmware/factory-write path appears in that inspected body. This is bounded
static evidence, not an audit of every transitive OS/storage effect. The
installed release, dynamic registration and exact live handler remain separate
from this standard-format response.

## Restricted client expansion

The existing stable `XHalfDeviceInfo.app` now supports two fixed modes:
`--read-device-info` or `--read-storage-ids`. Each invocation submits exactly
one explicit operation. There is no arbitrary opcode/parameter entry point.
The old information mode retains its command and serial-redacting decoder.

The new mode uses the same unique-device/model/session preconditions, retained
object identity recheck, session/catalog readiness, deadlines and success-close
callback. Its outgoing container is 12 bytes, opcode `0x1004`, with no parameters
and `outData:nil`. It accepts a successful response only when the dataset has
a complete 32-bit count followed by exactly that many 32-bit IDs, count 0..3,
and total length 4..16 bytes. Unknown or malformed results stop without retries,
resets or guessed cleanup commands.

The reply was written to a private temporary file, decoded and removed by the
wrapper. Thus `dataset_saved=true` in the report describes the intermediate
file, while `raw_dataset_retained=false` describes the final state. The report
contains no image names, paths, image content or raw serial string.

ImageCaptureCore may enumerate media and issue its own initialization/catalog
requests. That framework traffic was not observed on the wire and is outside
our one-explicit-command count. The result does not prove absence of all
camera-internal bookkeeping or persistent effects. No claim of a bus-wide
read-only certification is made.

## Validation and reproduction

Before the live attempt, Objective-C compilation passed with warnings treated
as errors. The native self-test checks the two fixed command encodings and
StorageIDs framing boundaries; all four Python decoder tests passed, including
truncation, excessive counts, trailing bytes and DeviceInfo serial exclusion.

```sh
bash analysis/run-imagecapture-device-info.sh --self-test
python3 -m unittest discover -s analysis -p test_ptp_device_info.py -v
```

The user-confirmed connection was corroborated by a fresh cached-registry
read before launching the client. One live invocation then completed; no
retry or additional active operation followed. Source hashes are preserved
in the live result, alongside the explicit operation and response summary.

For a separately authorized repeat with the camera connected normally:

```sh
bash analysis/run-imagecapture-device-info.sh --read-storage-ids
```

This is an active camera session, unlike `--build` and `--self-test`. macOS
access authorization is separate from shell sandbox permissions. The existing
[session/access report](macos-device-info-session.md) explains the framework
and authorization boundaries. This first added read succeeded, but does not
qualify other vendor requests or remove the unresolved RAM-entry/recovery gap.
