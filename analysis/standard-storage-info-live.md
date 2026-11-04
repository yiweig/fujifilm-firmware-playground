# Standard StorageInfo: successful read after delayed authorization

2026-11-03 local time. User authorized continuing the small active-read sequence.
The camera remained connected normally after the successful
[StorageIDs pass](standard-storage-ids-live.md).

## Current result

After the user confirmed allowing the macOS prompt, one retry with the
**unchanged client** succeeded. It submitted exactly one explicit `0x1005`
request for `00010001`, received a valid 62-byte dataset with PTP success
`0x2001`, and completed explicit session close. The client had no open session
at exit. No further active request followed.

| Reported metadata | Value |
| --- | --- |
| Storage type | 4, standard label `RemovableRAM` |
| Filesystem type | 3, standard label `DCF` |
| Access capability | 0, standard label `ReadWrite` |
| Maximum capacity | 31,910,264,832 bytes, about 31.9 decimal GB |
| Free space | 31,539,298,304 bytes, about 31.5 decimal GB |
| Free-image count | 2,717, a reported estimate, not a tested capture count |
| Description / volume label | Description present, label absent; text omitted |

Evidence: [successful retry](output/xhalf-storage-info-2026-11-03-retry-01.json).
This is consistent with ordinary nominal-32-GB removable camera media. It does
not identify an internal firmware partition. PTP's `RemovableRAM` label names
a writable-storage category; it is not evidence of volatile CPU-RAM access.
`DCF` does not identify the underlying FAT/exFAT implementation, and the
advertised access capability is not a write test or raw-storage permission.

The retry's cached registry corroboration found one `04cb:030a` camera at
registry ID `4295356298`, location `0x00100000`. That differs from the earlier
connection, which is why the client discovers the device and rechecks its
identity rather than hard-coding a USB port/address. An initial registry helper
probe produced an invalid plist; subsequent registry reads succeeded. The
client independently enforced its unique-camera/model/session preconditions.

### First attempt and authorization timing

The first StorageInfo attempt **sent zero explicit PTP requests**. The client
received zero ImageCapture discovery callbacks, opened no session and returned
`no_unique_camera`. There is no StorageInfo reply, capacity result, physical
medium identification or camera-side rejection from this attempt.

Evidence: [sanitized attempt report](output/xhalf-storage-info-2026-11-03.json).
Separate cached-registry reads before and after still found one normal
`04cb:030a` X-half interface under the same registry ID `4295354588` and
location `0x01100000`, with `ptpcamerad` as owner. No USB interface was claimed
or driver detached by our code.

Selected host logs around the first attempt show `tccd` requesting
`kTCCServiceSystemPolicyRemovableVolumes` authorization for the research app,
including `AUTHREQ_PROMPTING`. The selected messages do not establish the
user's answer or completed authorization by themselves. The user subsequently
confirmed that the prompt appeared and was allowed several minutes later,
after being away from the computer. That exceeded the client's 60-second
discovery window. Success on the unchanged retry supports this host
authorization/timing explanation, not a camera firmware rejection. The
[earlier authorization investigation](macos-device-info-session.md) documents
the same distinction.

No automatic retry, reset, app-policy/permission change or additional camera
command followed the failed attempt automatically. Only after the user's
authorization clarification was the single retry above performed. Client and
wrapper hashes match across attempts; no TCC database, privacy setting,
driver or client-source change was made to obtain that result.

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

The successful retry now qualifies this specific normal metadata read. Both
attempts are preserved separately so the zero-command failure is not confused
with the subsequent successful camera operation. Raw datasets remain removed;
the framework reported 43 catalog items and transaction ID 141, neither of
which is a count of explicit requests or verified photos.
