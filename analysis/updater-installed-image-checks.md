# What the updater reads and checks for Firmware2

Date: 2026-11-02. Official 2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All routine addresses in this report belong to this updater, not the main
application. Offline only; the camera remained unplugged.

## Result

The `:DEF_FW2` request identified in the
[selection trace](updater-firmware-selection.md) obtains an installed image's
length and expected checksum through a storage interface, reads region index
1 into RAM, and checks a 16-bit sum of the image's bytes. This path reads
installed storage; it does not open `FPUPDATE.DAT` or a named SD-card file.

Both implemented storage backends expose the same firmware metadata fields.
This is a concrete load/check convention, but the camera's actual metadata
and Firmware2 bytes remain unavailable. Whether Firmware2 contains a small
recovery application or another complete camera application is still unknown.

Eleven original-ARM controls pass using synthetic memory. They do not run a
storage read, boot task, MMIO operation or firmware handoff.

## Resolve the storage callbacks before assigning metadata meanings

The loader obtains its storage interface through `0x4001a874(1)` and calls
its `+0x60` callback. Registration code builds 100-byte interface records:

| Registration routine | Operation dispatcher | Operation 4 getter | Operation 5 reader |
| --- | --- | --- | --- |
| `0x4001baa8` | `0x4001b810` | `0x400264f8` | `0x40025958` |
| `0x4001be6c` | `0x4001bb80` | `0x4002dee0` | `0x4002c88c` |

Both registrations populate stack-record offset `0x60` with the dispatcher
before calling record-registration helper `0x4001aba0`. Dispatchers recover
the variadic arguments and forward operation 4's first argument to the getter.
The table does not establish which backend the actual X half selects.

The first getter reads a map pointer held at `0x400a3c94`. The second obtains
a map through `0x400301d8(-1)` and geometry through `0x400301d8(1)`. The map
provider can allocate a 512-byte cached buffer. Merely obtaining its pointer
is not evidence of a fresh storage acquisition or valid populated metadata.

## Metadata layout used by both backends

Offsets below are relative to the installed map buffer, **not the DAT package
header**. Field names describe observed use, not recovered vendor type names.

| Operation 4 index | Returned value |
| --- | --- |
| `0x0c` | Firmware1 byte length: 32-bit map field `+0x1c` |
| `0x0d` | Firmware2 byte length: 32-bit map field `+0x20` |
| `0x0e` | Firmware1 expected sum: `(u16(+0x42) + u16(+0x44)) & 0xffff` |
| `0x0f` | Firmware2 expected sum: `(u16(+0x46) + u16(+0x48)) & 0xffff` |

The two readers also distinguish region index 0 from a nonzero index: map
`+0x14` supplies the former region's base; map `+0x18` supplies the latter.
The first reader converts that base using map geometry and 512-byte sectors.
The second uses geometry and an additional block traversal helper before
calling its read primitive. These are not measured physical NAND addresses,
and neither complete reader has been emulated in this pass.

This connects the Firmware1 transaction described in
[the earlier assessment](firmware-copy-and-fallback.md) to the loader's total
checksum: changing map halfword `+0x42` by two also changes Firmware1's combined
expected sum by two modulo 65536, if the other component stays unchanged.
That explains why the transaction can deliberately make the installed image
fail this check. It still does not identify an earlier cold-failure selector.

## Read and checksum gate in the loader

For `:DEF_FW2`, internal flags are `0x02000000`. The loader:

1. Queries image length with index `0x0d` and expected sum with index `0x0f`
   at `0x4000f4e8..0x4000f510`.
2. Rejects a zero image length on the common allocation path. It rounds the
   requested read size up to 512 bytes and prepares RAM buffers.
3. At `0x4000faa8..0x4000facc`, calls storage operation 5 with a destination
   buffer, rounded read length, region index 1 and offset zero.
4. For this positive-flags path, read result zero reaches checksum computation;
   a nonzero result goes to the error branch. Other token types have additional
   branches and must not inherit this simplified description.
5. Calls `0x4006b108(buffer, original_image_length)`, which sums bytes and
   returns the low 16 bits. Alignment padding is outside this checksum span.
6. Compares that result against the expected sum. A mismatch branches to the
   checksum-error log at `0x4000fafc`; a match reaches `0x4000fb24`, the status
   write described in the selection report.

The success-side code still has subsequent header acquisition, task creation
and a handoff. Passing this slice does not prove the complete image will boot.
The byte sum is an integrity check, not a test of the program's correctness.
An intentionally modified image can pass it and still crash. Consequently,
even a future proof of checksum-failure fallback would not establish recovery
from every bad modification or a valid-checksum boot failure.

## Original-instruction controls

[InspectUpdaterFirmwareChecks.java](scripts/InspectUpdaterFirmwareChecks.java)
loads and verifies the complete updater, then runs:

- Eight getter controls: indices `0x0c..0x0f` through both backends, including
  a pair of checksum components whose sum wraps at 16 bits. Only the second
  getter's state-pointer helper is modeled, using explicit synthetic buffers.
- Three late-gate controls, entered immediately after the storage call would
  return: supplied success plus matching sum, success plus mismatching sum,
  and failure. The original byte-sum function executes on four fixture bytes.
  All stop before status writes, error logging or downstream work.

Every control also verifies that executable bytes remain unchanged. The
fixtures are not installed-camera measurements. No storage backend, actual
MMIO, flash, boot task, image execution or whole startup executes.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterFirmwareChecks.java /private/tmp/updater-firmware-checks.txt
```

Evidence: [11 control results](output/updater-firmware-checks.txt) and
[original-byte static ranges](output/updater-installed-image-checks.txt).
Ranges in the latter are reproducible with hash-checking `analysis/card.py`.
This is local Ghidra evidence; no new REA or live result is claimed.

The next code boundary is boot task `0x4000f1b0` and handoff helper
`0x4001219c`. Separately, independent recovery still requires the earlier
loader's entry conditions and actual installed image/map contents. These
offline results require no camera reconnection.
