# Updater firmware selection and the remaining cold-loader gap

Date: 2026-11-02. Offline inspection of official 2.00. No camera access,
card preparation, firmware patch or persistent device operation.

The **complete updater**, `analysis/output/card-updater-full.bin`, has SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses below are updater addresses unless explicitly marked main.

## Finding

Follow-up: [installed image checks](updater-installed-image-checks.md) resolves
the metadata queries, storage-region read and split checksum fields, with 11
original-ARM controls. Actual installed Firmware2 contents remain unknown.

The running updater contains a registered boot-selection callback that examines
six rawC configuration records and, if its loop finishes, requests `:DEF_FW2`.
Its loader also explicitly distinguishes Firmware1 and Firmware2 and writes
boot-status bit 1 accordingly. This provides a concrete software connection
between named firmware selections and the bit used by the main staged loader.

It still does **not** establish automatic recovery after a bad main flash:
the updater must already be running, its callback must be reached, and the
requested image must load successfully. The earlier executable that can enter
this updater independently of a functioning application remains unrecovered.

## Registration and callback invocation

Startup consumer `0x400009c4`, examined in the
[boot-register report](updater-boot-register-consumer.md), conditionally reaches
`0x40000c68`. It loads callback address `0x40000888` from literal `0x40001100`
and calls wrapper `0x4000e558`. This tail-calls setter `0x4001c76c`, which stores
the callback at `0x400a3a78` (state `0x400a3a6c + 0x0c`).

This registration is conditional: startup gate word `0x10001300 == 0` bypasses
it, boot-status bit 7 set bypasses the preceding branch, and another branch
prints `skip load FW` rather than registering it. The GPIO/timing inputs have
not been mapped to physical buttons.

Getter `0x4001c77c` returns that stored pointer. Storage helpers contain
explicit nonzero checks and indirect calls through it, including
`0x4001ba54..0x4001ba64` and `0x4001be34..0x4001be44`. Thus the callback is
more than a string or an unreferenced function. This static evidence does not
show which helper or branch the production camera takes.

## What callback 0x40000888 does

The callback allocates a 60-byte buffer and iterates indices 0 through 5:

1. Calls selector interface `0x4000ed70(0x29, index, buffer)`.
2. A nonzero result skips that entry.
3. On success, exact string `:NO_BOOT` releases the buffer and returns early,
   without trying later records or the final Firmware2 selection.
4. Otherwise it interprets an optional `A:`, `B:`, `C:` or `D:` prefix through
   helper `0x4000e5a4`, then replaces the buffer with `:RAWCBOOT0`, increments
   its last digit by the index, and calls `0x4000f270(buffer, 0x40000000)`.
5. If the call returns, iteration continues; the callback does not branch on
   its return value here.

Selector `0x29`, at `0x4000ef84..0x4000efc0`, obtains a 672-byte record through
getter `0x4001c78c`, copies its first 60 bytes on success, and returns its
status. The underlying record validation is described in the earlier report.
These are six boot-configuration records, not six installed firmware slots.

If the loop completes, instructions `0x4000098c..0x400009a0` release the
buffer, load actual firmware string `:DEF_FW2` from `0x4008f044`, restore the
caller's registers and tail-call `0x4000f270` with destination `0x40000000`.
This is a fallback request inside the **running updater**. It is neither an
unconditional action on every startup nor evidence that the camera's cold
loader tries Firmware2 after Firmware1 fails.

## Named selections and the status bit

Loader helper `0x4000f270` compares the following embedded tokens:

| Actual token | Internal flags in r4 | Status bit 1 when the late status-write block is reached |
| --- | --- | --- |
| `:DEF_FW1` | `0x01000000` | Clear |
| `:DEF_FW2` | `0x02000000` | Set |
| `:MB_FW1` | `0x81000000` | Clear |
| `:MB_FW2` | `0x82000000` | Set |
| `:RAWCBOOT0` through `:RAWCBOOT5` | `0x10000000` through `0x60000000` | Clear |

Other paths include `:DEF_RAWC`, `:MB_RAWC` and ordinary file opening. These
tokens are internal API arguments, **not established SD-card filenames**.
The meaning of the `MB` abbreviation is not assigned here.

At `0x4000f4a0..0x4000f510`, firmware type 1 queries storage-interface
operation 4 with indices `0x0c` and `0x0e`; type 2 uses `0x0d` and `0x0f`.
Their results feed separate loader fields. Physical NAND offsets and contents
are not measured by this observation.

After intervening loading/checking branches, `0x4000fb24..0x4000fb3c` tests
`(flags & 0x07000000) == 0x02000000`, chooses value `0x82` if true or `0x80`
otherwise, and calls setter `0x4006b1a4` with mask `0x82`. That setter performs
the halfword update at `0x10004054`:

```text
status = (status & ~mask) | (value & mask)
```

It therefore sets bit 7 and selects bit 1, preserving other bits. This is a
late loader action, not a write immediately upon recognizing a token and not
proof the subsequent handoff succeeds. Separate mask `0x70` records the rawC
configuration index; mask `0x0c` elsewhere concerns different bits.

The main application's staged reader/checksum validator also consumes bit 1
at the same MMIO halfword; see [storage handoff](staged-loader-storage-handoff.md).
This is evidence of a shared selection convention, not of a complete cold
boot/failure policy.

## Bounded search for the earlier descriptor producer

The main image copies the 36-byte descriptor at `[3f00c518,3f00c53c)` into
its own state. A 256-instruction backward constant-resolution search found:

- Main: no resolved store or known copy/clear overlap with that SRAM interval.
- Updater: no resolved direct store overlap. Main-specific bulk-helper
  addresses are inapplicable to the updater and were excluded from this result.
- Main boot-status halfword `0x10004054`: one resolved store, `STRH` at
  `0x40057df4`, in generic masked setter `0x40057ddc`. The aligned direct
  B/BL and exact-pointer search found no references to that setter.

These negative searches do not prove the producer is absent. They omit Thumb,
unresolved pointer arithmetic/calls, other bulk helpers, DMA, runtime-written
code and earlier ROM/storage executables. Main scanning ends at `0x40ab0000`;
the full limitation strings are retained in the evidence JSON.

## Evidence and reproduction

Original-byte disassembly and string addresses:
[output/updater-firmware-selection.txt](output/updater-firmware-selection.txt).
The ranges in that file can be regenerated with `analysis/card.py disasm`;
it verifies the complete updater's hash before reading it.

Search outputs:
[main descriptor](output/sram-descriptor-writer-search.json),
[updater descriptor](output/updater-descriptor-store-search.json),
[main status](output/main-boot-status-writer-search.json).
For example:

```sh
python3 analysis/find_usb_flag_stores.py --target 0x3f00c518 --size 36 --window 256
python3 analysis/find_usb_flag_stores.py --target 0x10004054 --size 2 --window 256
python3 analysis/fw.py callers 0x40057ddc
```

For the updater-only direct-store result, import `scan` from
`analysis/find_usb_flag_stores.py`, supply the hash-checked complete updater
bytes and the same interval/window, and retain only the `hits` field. Its
main-image bulk-helper results must not be reused for this different image.

Local Ghidra decompilation of `0x40000888` incorrectly treated the allocator
release thunk as PIC construction and removed the final selection as
unreachable. That export is not used as evidence; the original ARM instructions
are authoritative here. No new REA, emulator or live-camera result is claimed.

The next useful boundary is the earlier executable supplying the SRAM
descriptor and selecting/loading the updater or main image. These findings
alone do not justify a flash or a new physical button experiment.
