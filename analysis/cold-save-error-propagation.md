# Cold-data save: logical success does not establish persistence

Date: 2026-11-01. Offline original ARM emulation with modeled storage only.
No camera access, USB request, factory-data modification or firmware patch.

## Finding

For the archive-backed path, the cold-data save wrapper can return success
even if opening the physical archive fails or writing it transfers only
part of the requested bytes. The logical member was updated in RAM, but
the underlying archive-write error is discarded before the save wrapper
checks the reported byte count.

This matters to recovery planning: a success reply, or even a matching read
back from the same resident archive, would not establish that a restore
reached persistent storage. It does not mean this camera has experienced
such a failure, or establish its likelihood or consequences on hardware.

The save wrapper also explicitly selects immediate-write mode. The deferred
flush mechanism is therefore not an established safety net for that save.
This further qualifies the [archive backing report](cold-resource-backing.md)
and [service initialization write](service-entry-initialization-writes.md).

## Original instruction path

Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7 little-endian runtime base `0x40000000`. The controls use the same
packaged archive identified in the backing report, SHA-256
`b878272f4cb92ded7aa31850d3f17fd8be16304b85982fbd0b03acbfe04f4f69`.

| Region | Behavior in the inspected path |
| --- | --- |
| `0x40125e70..74` | Cold save calls environment selector 20 with value 0 |
| `0x400bca2c..30` | Selector 20 routes to archive mode setter `0x4006ab18` |
| `0x4006ab2c..38` | Setting mode 0 first calls the flush routine, then stores mode 0 regardless of its results |
| `0x40125e98..c8` | Full-record case recomputes and stores the record checksum |
| `0x40125edc -> 0x4015b928 -> 0x4015b840` | Writes the logical record through the ordinary save wrapper |
| `0x4015b8ac -> 0x401408e8 -> 0x4006ae08` | Writes into a supplied archive-backed member handle |
| `0x4006ae90..bc` | Copies bytes into the resident member and advances its RAM cursor |
| `0x4006aec0..e8` | With mode 0, calls physical archive writer `0x4006a540` |
| `0x4006aeec -> 0x4006af0c` | Does not test the writer's result; returns member byte count from r4 |
| `0x4015b8b0..dc` | Sees the complete logical byte count, closes the member and returns success 0 |

`0x4006a540` itself detects an open failure and a short raw write. It opens
the archive with mode `0x100`, gets its length through `0x40011380`, writes
that many bytes from the resident archive using `0x40010330`, closes it,
and compares the transferred count with that length. The masking occurs in
its caller, not because the modeled raw operation was reported successful.

In this immediate path, the archive writer's failure does not set a dirty
flag. Calling the general flush function afterward while mode is still 0
returns without trying the write again.

Service entry independently calls selector 20/value 0 at `0x405dd504`.
These are write-related state changes, not a RAM-only loader override.

## Deferred mode is a separate path

Lower adapter `0x401408e8` used directly with manager mode 1 updates the
member and sets the archive's dirty byte without immediately writing it.
Flush routine `0x4006aaac` visits active dirty archives when mode is nonzero,
calls the archive writer, and clears the dirty byte only when that call
returns zero. This mechanism does retain a failed write for a possible later
flush while the mode and archive registration permit it.

It must not be confused with the full cold-data save wrapper, which forces
mode 0. If an archive was already dirty in mode 1, changing to mode 0 can
attempt to flush it before the new record is copied. A failed preflush
retains the dirty byte but does not prevent the mode change; a later ordinary
flush in mode 0 will skip it. This is a conditional global-state effect,
not an observed camera startup state.

Bounded aligned ARM caller scans found flush calls at `0x40041978`,
`0x40127698` and `0x4013f66c`, plus the mode setter. They do not establish
that all errors eventually get retried. No recovery procedure should rely
on an assumed shutdown retry from these call sites alone.

## Controls and their limits

[`ReproduceColdSave.java`](scripts/ReproduceColdSave.java) passed ten controls:

- Six full-save controls: initial mode 0 or 1, each with complete write,
  physical open failure, or a 200-byte transfer of a 1,216-byte archive.
  All execute the original checksum and save wrappers, force mode 0 and
  return success 0. Only the complete-write models leave the persistent
  byte array equal to the new archive. Failed cases record no dirty retry.
- Three separate lower-adapter controls in mode 1: saving returns 1,024
  bytes before physical I/O; a subsequent original flush clears dirty on
  success and preserves dirty on open/short-write failures.
- One initially dirty mode-1 control with open failures: both the preflush
  and new save attempt to open storage; the wrapper still returns success,
  mode becomes 0, and a later flush does not retry the remaining dirty archive.

Each control checks complete RAM and/or modeled-storage contents, API call
counts, mode and dirty state. The full-save controls also verify that the
input record receives the expected checksum. [Retained output](output/cold-save.txt).

The emulator supplies an existing archive-backed member handle. Logical
open/seek/close and handle classification are modeled, as are raw filesystem
APIs, copying, initialization, delays, logging and other environment calls.
Selector-20 dispatch is modeled by forwarding to the **original** mode setter;
the setter, flush, checksum, write adapters and archive-write wrapper execute
their original instructions. Firmware and filesystem images are not modified.
Modeled storage is an in-memory byte array.

This does not test a live USB command, real flash behavior, atomicity,
power-loss handling, close errors, storage durability or a verified restore.
The existing local Ghidra project was reused after the previously documented
REA raw-image mapping issue; no new REA runtime observation is asserted.

To reproduce, first extract `/RO_RES/COLD.BIN` as shown in the backing report:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceColdSave.java /private/tmp/cold-save.txt \
  /private/tmp/packaged-cold.bin
```

## Acquisition and recovery consequence

This pass establishes the save-error boundary; it does not find a safe raw
archive acquisition command. The restricted host reader remains unchanged
and cannot write. Future work must distinguish a resident-RAM copy from the
persistent archive and qualify a read path with explicit error handling.

For a future restore, an independent persistence check would be required;
the same logical-file read can simply return the modified resident bytes.
No live write/read-back experiment, mode toggle, factory command or service
entry is justified as a test of recovery by these offline results.

Follow-up: [normal PTP file acquisition](retail-ptp-file-acquisition.md)
examines an ordinary-filesystem read candidate. Its storage list omits `A:`,
the media filter excludes the cold-resource names in tested contexts, and
GetObject resolves database IDs rather than accepting arbitrary paths.
