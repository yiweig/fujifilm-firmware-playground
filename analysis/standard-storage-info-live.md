# Fixed StorageInfo read: prepared, first attempt stopped at host discovery

2026-11-03 local time. User authorized continuing the small active-read sequence.
The camera remained connected normally after the successful
[StorageIDs pass](standard-storage-ids-live.md).

## Current result

The first StorageInfo attempt **sent zero explicit PTP requests**. The client
received zero ImageCapture discovery callbacks, opened no session and returned
`no_unique_camera`. There is no StorageInfo reply, capacity result, physical
medium identification or camera-side rejection from this attempt.

Evidence: [sanitized attempt report](output/xhalf-storage-info-2026-11-03.json).
Separate cached-registry reads before and after still found one normal
`04cb:030a` X-half interface under the same registry ID `4295354588` and
location `0x01100000`, with `ptpcamerad` as owner. No USB interface was claimed
or driver detached by our code.

Selected host logs around the attempt show `tccd` requesting
`kTCCServiceSystemPolicyRemovableVolumes` authorization for the research app,
including `AUTHREQ_PROMPTING`. The selected messages do not establish the
user's answer or completed authorization. The user was asked whether a prompt
appeared. This is evidence for a host-access boundary, not proof of denial or
a firmware error. The [earlier authorization investigation](macos-device-info-session.md)
documents the same distinction.

No automatic retry, reset, app-policy/permission change or additional camera
command followed the failed attempt. A further live attempt needs clarified
host authorization while preserving the prepared command boundary.

## Fixed request and response boundary

The client now adds exactly one fixed option,
`--read-storage-info-known`, alongside its two earlier standard read modes.
It has no arbitrary opcode or storage-ID input. This option encodes:

```text
PTP opcode:    1005 (GetStorageInfo)
Parameter 1:   00010001 (from the successful previous read)
Parameters:    exactly one
Outgoing data: none
```

The prepared command container is 16 bytes. This is a protocol storage ID,
not a raw sector, pointer or pathname. Reuse of the earlier ID does not attest
that any particular medium or driver slot is currently mapped to it.

On a valid reply, the normal success-close sequence is required. Structural
validation bounds the dataset to 28..1,048 bytes: a 26-byte numeric prefix and
two counted UTF-16 strings. The numeric fields are storage type, filesystem
type, access capability, maximum capacity, free space and free-image count.
Counts, boundaries, terminators and trailing bytes are checked. Text strings
are validated but omitted from the saved decoded report. The raw intermediate
dataset is removed by the wrapper.

Unknown/error/timeout replies stop without retries or resets. As before,
ImageCaptureCore may issue normal initialization/catalog queries; its wire
traffic is not constrained by our explicit operation count or captured here.

## Static handler review

Official 2.00 main SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARM little endian, linked at `0x40000000`. Default handler `0x402e90a4`:

- Requires the storage-ID parameter and rejects an unknown ID.
- Uses `0x40335308` to resolve the ID through an eight-entry RAM table; the
  numeric input is not dereferenced as a CPU address.
- Builds metadata using type/access helpers, media-status queries, cached
  description/volume strings and serializers.
- Queries application property callback `0xd608` for the image-count field;
  transitive callback and hardware effects are not fully audited here.
- Sets a PTP response; the inspected body does not invoke a firmware writer,
  formatter or loader entry point.

The metadata/string helpers inspected here copy cached strings out; nearby
setters were not invoked. This bounded review supports selecting the normal
read, not a complete no-side-effects certification or a proof of the effective
live handler/installed release.

Source-verified [instruction ranges](output/storage-info-handler-boundaries.txt)
record the handler, ID lookup and selected getters. The retained primary-source
libgphoto2 `ptp.h` defines the standard numeric labels; its hash remains
`b0911ed8578c8cdb8804763f33b575f1fb0d1cd58af4b3199c973c5737a574cd`.
No foreign host program or camera firmware was executed for this static review.

## Validation and use

The Objective-C client compiled with warnings treated as errors. Its native
self-test checks the fixed request's opcode/length/ID and dataset framing.
All five Python tests passed, including truncated StorageInfo prefixes/strings,
oversized counts, bad UTF-16/terminators, trailing bytes, private label exclusion
and preservation of the existing metadata decoders.

```sh
bash analysis/run-imagecapture-device-info.sh --self-test
python3 -m unittest discover -s analysis -p test_ptp_device_info.py -v
```

The live mode is an active camera session and should not be confused with
those offline checks:

```sh
bash analysis/run-imagecapture-device-info.sh --read-storage-info-known
```

This prepared operation supplies no raw-storage or memory access and does not
resolve RAM entry or recovery. A host-discovery failure must not be counted as
a tested camera opcode.
