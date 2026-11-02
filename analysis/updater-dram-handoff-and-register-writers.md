# DRAM parameter handoff and bounded boot-register writer search

Date: 2026-11-02. Offline only, official 2.00 images:

- Main image SHA-256: `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
- Complete updater SHA-256: `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.

Both images use base `0x40000000`; identical numerical addresses must be
qualified by image. No camera commands, card preparation or firmware changes.

## Result

Follow-up: [copy boundary and special-entry gate writers](ram-handoff-copy-boundary.md)
shows that the updater's special `+0x200` entry writes a nonzero gate. If it
persists, that skips the restore branch below. The matching handoff fields
must not be taken as proof that this USB launch executes the restore branch.

The main application's USB loader writes a parameter-buffer pointer and size
into an SRAM handoff block. The packaged updater contains startup code that
reads those exact fields and restores a `DRAMPAR3` buffer into SRAM. This
connects a previously unnamed part of the application handoff to a concrete
consumer in the updater. It supports a RAM-launch initialization convention;
it does **not** demonstrate a working upload, persistent-write-free payload,
or recovery when the installed application cannot run.

Separately, the executing updater can write the status bits behind its
`Boot from bootinfo%d` diagnostic. That message is not exclusive evidence of
an immutable bootloader decision. The writer of the automatic startup
filename/configuration registers remains unidentified.

## Application producer and updater consumer

The main USB receiver state starts at `0x40fd0d50`. On first-use request
`0xfd`, index `0x04f3`, code `0x401e1bb4..0x401e1c88` allocates a parameter
buffer, saves its address at state `+0x34` (`0x40fd0d84`), and the received
length at `+0x38` (`0x40fd0d88`). It receives data using `0x40056798`, then
calls parameter-processing helper `0x4000dbec`.

That helper tries up to eight records through `0x4000d7d0` and `0x4000db98`,
summing accepted record spans until a failure. The parser checks the two
words spelling `DRAMPAR3` (`0x4d415244`, `0x33524150`). This is structured
parameter processing, not an unconditional raw-memory write or an audited
payload authentication procedure. The total transfer length is not used as
a bound by `0x4000dbec` itself. Its full malformed-input behavior and helper
checks are not established here.

A nonzero result takes the path that clears `0x3f000200..0x3f003fff` and
copies the received parameter buffer there. A zero result frees the buffer
and clears its pointer. The first-use flag is set on both branches. This
does not establish that every malformed or partial record is rejected.

Later, main handoff helper `0x401eb1ec`, at `0x401eb37c..0x401eb394`, writes:

| SRAM address | Application value | Observed updater use |
| --- | --- | --- |
| `0x3f00d000` | Uploaded code source | Copy-stub input, previously traced |
| `0x3f00d004` | Code length rounded up to even | Copy-stub input |
| `0x3f00d008` | Code destination | Copy-stub input |
| `0x3f00d00c` | Parameter pointer from `0x40fd0d84` | Updater reads at `0x400012c8` |
| `0x3f00d010` | Parameter size from `0x40fd0d88` | Updater reads at `0x400012cc` |

Before this handoff, main code `0x401e1fa8..0x401e1fbc` copies a 16 KiB
header buffer into `0x3f000000`, overlapping the parameter location. Restoring
the preserved buffer after the jump is consistent with that overlap. This
explains the apparent purpose; actual handoff execution was not observed.

## Updater startup restore conditions

In the complete updater, startup `0x4000125c` reaches this slice:

1. `0x10001300 != 0`: skip the restore section.
2. Otherwise compare the first eight bytes at `0x3f000200` with `DRAMPAR3`.
   A match skips restoration; this branch does not validate the remaining data.
3. Otherwise read source and size from the handoff block above. Require both
   nonzero, size at most `0x3e00`, and a four-byte-aligned source.
4. Check the top nibble of both source and the 32-bit sum `source + size`:
   clearing nibble bit 1 must leave 8. Thus nibble 8 or A passes. These checks
   alone do not establish actual RAM capacity, ownership or physical aliases.
5. Compare eight source bytes with `DRAMPAR3`. On a match copy `size` bytes to
   `0x3f000200`, then zero the remaining `0x3e00 - size` bytes.

The original strings include `Restore DRAM Param`, `DRAM Param error`, and
`DRAM Param OK`. The error branch logs and calls a diagnostic helper before
continuing; it is not a demonstrated fail-closed boot validation boundary.
This section copies parameter **data**. It does not by itself establish DRAM
register programming, firmware validation or flash writes. No minimum size
of eight is checked before the eight-byte source comparison.

Nine supplied-state controls in
[`ReproduceUpdaterDramRestore.java`](scripts/ReproduceUpdaterDramRestore.java)
execute original ARM instructions `0x40001298..0x40001388`. They cover the
nonzero gate, existing SRAM header, successful restores from supplied 8/A
address ranges, zero/oversize lengths, unaligned/wrong-range pointers and
wrong magic. Complete destination bytes, zero padding and unchanged
executable bytes are checked. Compare/copy/clear/log/diagnostic helpers are
modeled. All cases stop before subsequent initialization `0x40068d7c`.

Output: [`output/updater-dram-restore.txt`](output/updater-dram-restore.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis \
  -postScript ReproduceUpdaterDramRestore.java /private/tmp/updater-dram-restore.txt
```

This is a slice with supplied memory, not a full startup or a physical RAM
alias test. Main allocator address representation, cache/MMU transitions,
the low-address handoff helper and actual USB entry remain unresolved.

## Software writer of BootInfo status

Updater setter `0x4006b1a4` reads and writes halfword `0x10004054`:

```text
new = (old & ~mask) | (value & mask)
```

In its file/rawC boot helper `0x4000f270`, the path at
`0x4000fa20..0x4000fa2c` calls this setter with mask `0x70` and value
`((r4 >> 28) & 7) << 4`. The named `:RAWCBOOT0` through `:RAWCBOOT5`
branches encode values 1 through 6 in that flag field. These are the same
status bits that the `dkboot` no-argument diagnostic extracts and prints
minus one. Later code also uses the setter with mask `0x82`.

This establishes that running updater code can populate the diagnostic
field. It does not exclude an earlier writer, establish when a real camera
took this branch, or equate the six rawC entries with Firmware1/Firmware2.
Neither the loader helper nor the status writer was run on hardware.

## Filename writer search and its limits

The heuristic `map_hardware.py` scan was run on both images into temporary
directories, filtering accesses to `0x10001300` and
`0x100040b4..0x100040c3`. It did not establish a writer of these fields.
The updater hits were known consumers plus false apparent byte stores at
`0x40000f98`, `0x40000fa0` and `0x40000fa8`. Original instructions recompute
the base from the stack at `0x40000f8c..0x40000f90`; the stores build a local
pathname. They are not register writes.

The mapper tracks a limited set of literal/MOV-derived values and does not
fully propagate arithmetic or invalidate every modified register. Therefore
its output is a lead generator, and an empty writer search is **not** proof
of absence. Writes through computed addresses, indirect helpers, another
image or earlier ROM code remain possible. Broad generated map artifacts
were not changed. Reproduce with:

```sh
python3 analysis/map_hardware.py --output-dir /private/tmp/boot-main-map
python3 analysis/map_hardware.py --firmware analysis/output/card-updater-full.bin \
  --elf analysis/output/card-updater-full.elf --output-dir /private/tmp/boot-updater-map
```

Static evidence is from original-file LLVM disassembly (`fw.py`, `card.py`)
and local Ghidra's ARM emulator; no fresh REA or live-camera result is claimed.
The missing earlier-stage loader/register producer remains a recovery gap.
