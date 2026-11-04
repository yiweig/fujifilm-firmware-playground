# Advertised PTP operations versus firmware handlers

Date: 2026-11-02. **Offline only**: no camera session, PTP command, property
change, upload, reset or mode switch was performed in this pass. The prior
[live information reply](output/xhalf-device-info-2026-11-02.json) supplies the
26 advertised codes. That reply does not attest the installed Fujifilm release.

Later live follow-up: [standard StorageIDs read](standard-storage-ids-live.md)
submits one explicit `0x1004`, receives ID `00010001`, and completes session
close. The client now has two fixed metadata modes, one operation per run.
The historical offline handler review below retains its original scope.

A subsequent [StorageInfo read](standard-storage-info-live.md) succeeds for
`00010001` after delayed host authorization, reporting removable DCF media and
closing its session. This is a second added standard metadata operation, not
a firmware-memory or recovery interface.

## Result

All 26 advertised codes are mapped in
[ptp-advertised-operation-map.tsv](ptp-advertised-operation-map.tsv) to the
inspected **2.00 default dispatcher**. The ordinary media/property operations
and the remaining extended handlers examined here do not establish an entry
to RAM loading, SD boot or independent recovery. This is a bounded handler
review, not proof that every undocumented interface is absent.

The important name/implementation mismatch is `0x9812`: a common MTP name is
`UpdateDeviceFirmware`, but this firmware dispatches it to the **same
image-object-processing handler as `0x9602`**. A name in a host library or a
device's advertised list is not a description of the enabled camera behavior.
No packet was sent to test either code.

The dispatcher body, shared image handler body and bulk-description handler
body are byte-identical across the four extracted firmware versions. Two
specific dispatch pointers were independently checked in each version's
literal pool. Other callees, literal values, runtime registrations and the
installed camera image were not thereby proved identical.

## Evidence identity and precedence

Detailed addresses below refer to the official 2.00 main image, ARMv7
little-endian at `0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
The existing local Ghidra 12.1.4 project and original ARM disassembly were
used after the previously documented REA raw-image mapping limitation. REA
session inspection found no open target; no fresh REA decompilation or camera
runtime execution is claimed for this pass.

Central dispatcher `0x402e778c` checks dynamic operation handlers and alias
translation before its default branches. Consequently the TSV names the
default handler/boundary, **not a verified effective live target** for every
advertised opcode. The previous [registration census](retail-ptp-command-boundaries.md)
and [normal-file acquisition review](retail-ptp-file-acquisition.md) retain
their bounded coverage and callback/alias qualifications. The host client
remains restricted to explicit `GetDeviceInfo`; its allowlist was not expanded.

New pseudocode export:
[ptp-advertised-handlers.txt](output/ptp-advertised-handlers.txt).
It includes the dispatcher and selected extended handlers, not every callee
or every standard operation in the map. The map reuses earlier reviews for
storage, objects, properties and sessions.

## `0x9602` / `0x9812`: existing image-object processing

The comparisons for `0x9602` and `0x9812` select the common branch at
`0x402e7b08`, which loads the pointer at `0x402e7e24` and calls
`0x402ea6f4`.

That handler:

1. Checks at least four parameter bytes are available and resolves the first
   parameter as an existing media-database object through `0x402e84a8`.
2. Builds its path from database metadata, using `0x402e82fc` and `%s%s`.
3. In the selected API-mode-3 branch, opens/reads the existing image and invokes
   image-processing helpers. It allocates an initial `0x60000`-byte buffer,
   obtains an internal offset/size pair, seeks/reads an additional buffer and
   extracts dimensions from processing results.
4. Maps the internally obtained source and configured output buffer through
   `0x4002a7bc`, copies the processed bytes using `0x4007c7fc`, and updates
   response length at `0x4136b680` before returning a PTP response.

The source is not a caller-supplied RAM address or arbitrary pathname. The
inspected body does not invoke the known firmware-update/loader entry points.
Its image-processing callees and hardware effects were not fully audited;
this is neither proof of harmlessness nor a live-ready memory reader.
The earlier [selected memory-read review](loader-memory-read-boundary.md)
already identified the object boundary; this pass ties it to the observed
advertisement and verifies selected cross-version routing.

## Other extended operations

| Code | Inspected behavior | Limit relevant to recovery |
| --- | --- | --- |
| `9601` | Optional callback at `0x4136b664`, then a wait on `HostRespEvt`; setter `0x402e6924` only stores that callback | No live callback value or useful installer established. The earlier census found no selector-11 installer among its inspected wrapper callers. It is not itself a memory/boot implementation. |
| `9614` | `0x40331bac` obtains the advertised device-property list through `0x402e8b7c`, loops it via `0x40331b18`, and packs length-prefixed descriptions | Reuses the property table used by DeviceInfo; it does not by itself enumerate extra hidden settings or hardware registers. Complete property callback effects remain outside this pass. |
| `9801` | `0x402e3ce0` selects object-property metadata by format and serializes supported codes | Metadata list; no established loader transition. |
| `9802` | `0x402e2fe4` selects and serializes an object-property descriptor | Metadata descriptor; transitive metadata helpers not fully audited. |
| `9803` | `0x40331d28` requires an object and parent, obtains a property record, and serializes its value | The object identifier is not an arbitrary memory pointer. |
| `9805` | `0x402e3940` handles object/property/group/depth selectors, database lookup and traversal to build a property list | Complex traversal remains unaudited; no raw-memory read or recovery entry established. |

Standard mutating operations (`100b`, `100c`, `100d`, `100e`, `1016`) were
mapped to show the distinction between advertisement and suitability for a
read-only investigation. None was executed. The MTP `0x9812` name must not be
used to justify a payload upload.

Conventional PTP/MTP names were cross-checked against the primary-source
[libgphoto2 operation definitions](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h).
The inspected header was downloaded as source, not compiled or executed;
SHA-256 `b0911ed8578c8cdb8804763f33b575f1fb0d1cd58af4b3199c973c5737a574cd`.
These names are external labels, not Fujifilm symbols or proof of semantics.
For `9601`, `9602` and `9614`, the TSV uses descriptions inferred from the
inspected code rather than assigning an unverified vendor operation name.

## Cross-version comparison and reproduction

[compare_ptp_dispatch_versions.py](compare_ptp_dispatch_versions.py) verifies
all four extracted-image SHA-256 values, searches each complete image for a
unique exact instruction-body match, and checks the two selected dispatcher
literal pointers. Results:
[ptp-advertised-handler-version-comparison.json](output/ptp-advertised-handler-version-comparison.json).

| Firmware | Dispatcher | Shared `9602`/`9812` handler | `9614` handler |
| --- | --- | --- | --- |
| 1.01 | `402abed0` | `402aee38` | `402f6300` |
| 1.20 | `402abf68` | `402aeed0` | `402f6398` |
| 1.30 | `402acbf0` | `402afb58` | `402f7020` |
| 2.00 | `402e778c` | `402ea6f4` | `40331bac` |

The body lengths are 1,604, 968 and 248 bytes respectively. Literal pools are
excluded from byte-body equality; only the `9602`/`9812` and `9614` dispatch
pointer slots are separately asserted. This does not compare every version's
registration context, configuration or callees, and does not identify which
release is installed on the camera.

```sh
python3 analysis/compare_ptp_dispatch_versions.py
python3 analysis/fw.py disasm 0x402e7914 0x402e7a74
python3 analysis/fw.py literals 0x402e7df8 0x402e7e8c
python3 analysis/fw.py literals 0x402eaabc 0x402eab4c
```

The saved map was checked against the live JSON: exactly 26 unique rows, in
the observed order, with no missing or additional code. No new hardware tests
or synthetic firmware-execution controls were needed for this mapping.

## Next unresolved question

Ordinary host communication now works, but the barrier to recovery remains
**entry to the separate loader personality**, not recognition of a promising
PTP operation name. The inspected `9601` optional callback and dynamic
registration contexts are the remaining qualifications within this map;
the advertising list alone does not resolve them. Further live work should
have a specific expected read result and bounded handler evidence first.

Follow-up: [optional 9601 hook review](ptp-9601-hook-review.md) broadens the
writer search, accounts for a nearby transport copy and the completion API,
and inspects startup/teardown. It supports an unused-hook interpretation but
does not prove a zero live callback or establish loader entry. No camera needed.
