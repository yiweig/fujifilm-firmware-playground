# Cold-data backing: logical record, RAM archive and physical storage

Date: 2026-11-01. Offline original-firmware inspection, package extraction and
ARM emulation only. No camera access, file mutation on a camera or USB request.

## Result and refinement of earlier descriptions

`A:\RO_RES\COLDDATA.BIN` is a logical filename. It can resolve to a member
of the `COLD.BIN` archive retained in RAM, rather than a separate on-media
file. A successful logical-record read therefore need not reread physical
storage at all. The earlier wrapper-failure controls remain valid, but an
independent success result from that wrapper alone would still not prove a
fresh read from persistent storage.

The official 2.00 package contains a `COLD.BIN` archive with three members,
including a 1,024-byte `COLDDATA.BIN`. This establishes an official packaged
baseline, **not the values saved on this individual camera**. It also means
that the one logical record is not the entire archive; capturing that record
alone is not a complete backup of all factory data or camera storage.

No read-success signal available through the restricted PTP command set was
established. No live acquisition or recovery procedure is qualified by this work.

## Artifact evidence

Official package SHA-256:
`3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`.
FAT A starts at package offset `0xa3d20`. `/RO_RES/COLD.BIN` is 1,216 bytes,
SHA-256 `b878272f4cb92ded7aa31850d3f17fd8be16304b85982fbd0b03acbfe04f4f69`.
Its first 16 bytes are `0000f6130000b8044649463103000000`.

| Member | Offset within COLD.BIN | Bytes |
| --- | --- | --- |
| `CLBTAF.BIN` | 64 | 64 |
| `COLDDATA.BIN` | 128 | 1,024 |
| `COLDDATF.BIN` | 1,152 | 1 |

The shipped `COLDDATA.BIN` member has SHA-256
`b307b6df3c8dd5feb449bd4c937a840fcccdcf2d264d02a0772bfc7c726c19fb`.
Its Hce fields are 0/0/0, `DisableTest` low byte is 0, `FgAutoClbt` is
`0x18`, and the confirmation halfword at `+0x168` is zero. Its stored checksum
is zero while the computed byte sum is 241. It therefore fails our current
record-integrity check. This does not establish that the camera's live record
has an invalid checksum, that its service gate is open, or that its automatic
calibration settings match these packaged values. Do not substitute this
member for a camera backup or recalculate its checksum to make it acceptable.

Hashes, offsets and selected packaged fields are retained in
[`packaged-cold-resource.json`](output/packaged-cold-resource.json).

## First-use initialization and source selection

Addresses below refer to official 2.00 main-image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARMv7 little-endian runtime base `0x40000000`.

1. Before opening the requested record, wrapper `0x4015ba48` calls
   `0x4015b5f8`. That helper checks byte `0x40f12bb0`, sets it to 1 on first
   use, and tail-calls `0x4006a9b0("A:\\RO_RES\\COLD.BIN")`.
   The latch is set before resource opening and is not cleared in this helper
   if opening fails. Subsequent calls skip that initialization attempt. This
   is not a claim that no other code can clear the latch.
2. `0x4006a9b0` supplies mode 0 to manager `0x4006a8a0`. The manager reserves
   a RAM slot, calls `0x4006a750`, and stores its returned archive pointer.
   Registry base is `0x40e63594`; archive slots begin at `+0x100`, stride
   `0x28`. The pointer is at slot `+0x20`, with path and prefix bookkeeping.
3. Loader `0x4006a750` first tries indexed-resource lookup `0x4008086c`.
   A present valid archive is used directly. A present invalid archive is
   rejected without falling through to a physical-file retry.
4. If lookup misses, it opens the physical archive with mode `0x10`, obtains
   its size, allocates memory, reads into it, checks the header and closes.
   The return count of the raw read at `0x4006a804` is ignored. Validator
   `0x4006a6f8` checks header structure and table extent, not member checksums
   or whether all requested bytes were transferred.

The direct resource index used by lookup is at `0x4098b200`; the shipped
table has 114 names. Neither `COLD.BIN` nor `COLDDATA.BIN` occurs among those
names. This favors the physical-archive branch for the first-use load with
that shipped index, but does not observe the runtime index or exclude other
initialization and modifications on a camera.

## Logical-file adapters and persistence implications

`0x4013f4ac` resolves a logical file in this order:

- Direct indexed-resource open through `0x400806c4`.
- Registered archive-member open through `0x4006ab48`.
- Ordinary filesystem open through `0x4001019c` if neither supplied a handle.

The archive lookup matches directory prefixes, then calls `0x4006a668` to
locate the member. `FIF1` names occupy 12 bytes, sizes are 32-bit, and member
data are aligned to 64 bytes. Original lookup instructions resolve the actual
packaged `COLDDATA.BIN` to archive pointer plus 128, with size 1,024.

For archive-backed handles, size is obtained through `0x4006ad64`, reads
through `0x4006ad80` copy from the resident member and advance a RAM cursor,
and close through `0x4006af18` clears the member handle. This close is not
an archive reload. A repeated wrapper read can consequently succeed while
copying from the same retained archive.

There is a related refinement to the earlier **whole-buffer save** findings:
the 1,024-byte save request describes the logical record, not necessarily the
physical write unit. For an archive-backed write, `0x4006ae08` updates member
memory; manager mode 0 reaches `0x4006a540` at `0x4006aee8`. That helper opens
the registered archive path with mode `0x100` and writes from the archive
buffer using the file length returned by `0x40011380` for that opened handle.
Thus the requested physical write length comes from the archive file, rather
than the 1,024-byte logical-record request. Nonzero manager modes
have additional comparison/dirty handling. Physical effects and the runtime
mode on this camera have not been tested. This is not proof that every
logical-record save always writes an archive, or that a write succeeds.

## Offline controls

[`ReproduceColdResource.java`](scripts/ReproduceColdResource.java) verifies
the hashes of both original inputs and executes the original loader, header
validator and member lookup. Five loader controls passed:

| Modeled source | Result |
| --- | --- |
| Valid indexed archive | Returns its pointer; no raw-file calls |
| Invalid indexed archive | Rejects it; no raw-file retry |
| Index miss, complete file read | Returns allocated archive; member matches packaged bytes |
| Index miss, file open fails | Returns zero |
| Index miss, only 64 bytes read | Accepts header/table; member points into untouched allocation bytes |

The last case deliberately initializes allocation memory to `a5` and copies
only the actual archive's first 64 bytes. It shows the missing read-count
check, not a checksum-valid camera backup. A valid checksum is not expected
for those `a5` bytes.

Two additional first-use controls model resource opening returning zero or a
nonzero handle. In both cases the latch remains 1 and a second helper call
skips opening. All seven controls passed; [retained output](output/cold-resource.txt).

The loader controls model indexed-resource lookup, raw file open/size/read/
close, allocation, logging and string comparison. The separate first-use
controls model the resource-manager call itself. They do not execute the
complete manager, logical-file adapter or physical storage implementation.
The source-selection and write-path statements above are static inspection,
not observed camera execution. The existing local Ghidra project was reused
after the documented REA raw-image mapping limitation; no new REA runtime
observation is asserted.

Extract the archive without mounting or modifying the package, then run:

```sh
python3 - <<'PY'
import mmap, sys
from pathlib import Path
sys.path.insert(0, 'analysis')
from fat16_read import Fat16
with open('FPUPDATE.DAT', 'rb') as f:
    with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as image:
        _, data = Fat16(image, 0xa3d20).lookup('/RO_RES/COLD.BIN')
Path('/private/tmp/packaged-cold.bin').write_bytes(data)
PY
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceColdResource.java /private/tmp/cold-resource.txt \
  /private/tmp/packaged-cold.bin
```

## Log/status lead and remaining question

The wrapper's failure messages use print function `0x40004064`, which
forwards formatting/output to `0x40020050`. Its start/end notices instead use
`0x400410cc`, which formats a bounded string and submits it to channel helper
`0x4000459c`. This establishes separate logging paths, not a read-only USB
method for collecting either. Output routing, retention, availability through
the retail interface and correlation with a specific physical read remain
unqualified. A start/end notice alone does not prove successful loading.

The next question is how to identify and acquire the camera's persistent
archive, including its origin and error status, without modifying it. A
validated full archive would still cover only the identified members; it
would not supply independent boot recovery or prove a complete calibration
backup. No SD marker, service-button experiment or RAM-write freshness
challenge is justified by these findings.

Follow-up: [save-error propagation](cold-save-error-propagation.md) confirms
that archive-backed saves can report success despite physical open/short-write
failures. The full cold-data save wrapper explicitly selects mode 0, so the
separate deferred flush mechanism must not be assumed to protect it.

The [logging follow-up](factory-read-log-routing.md) traces the start/end
notices and error messages separately. Neither establishes a successful
physical archive read or a safe retail USB log retrieval method.
