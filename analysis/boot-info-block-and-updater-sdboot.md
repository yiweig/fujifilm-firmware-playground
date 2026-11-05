# Boot-info block, updater SD-boot branch, and the remaining selector gap

Date: 2026-11-05. Static pass (Ghidra 12.2 decompilation of the card
updater; REA MCP was reachable but its Ghidra provider is not
configured in the server environment — `GHIDRA_INSTALL_DIR` unset —
so no REA evidence is used here). Decompilation:
[output/updater-boot-consumer-decomp.txt](output/updater-boot-consumer-decomp.txt).
No camera access.

## The boot-info block (newly consolidated)

Both the updater and the main image consume a small block of
registers/state at `0x1000_xxxx`, written before either image runs:

| Address | Content | Evidence |
| --- | --- | --- |
| `0x10001300` | Gate word. `0` = skip automatic file handling and go straight to the USB/service continuation. The main image's `+0x200` entry writes `0x12345678` here; the updater's writes `0x88888888` — each image stamps its identity when entered via the loader-facing entry | updater `FUN_4000125c`/`FUN_400009c4` (decomp lines 27, 840s); main-image `+0x200` behavior per [ram-handoff-copy-boundary.md](ram-handoff-copy-boundary.md) |
| `0x10004054` | Boot-status halfword. Bit 1 set = Firmware2 selected (written by the updater's loader `FUN_4000f270` via boot-register writer `0x4006b1a4`); bit 7 set skips the button/GPIO path in the updater's consumer | [updater-firmware-selection.md](updater-firmware-selection.md), decomp `FUN_400009c4` line 29 (`func_0x4006b194() & 0x80`) |
| `0x100040b4..0x100040b8` | Button/GPIO timing fields. The updater decodes hold-time fields from these bytes ("Button pressed for %d/%d ms", "skip load FW") and waits/compares accordingly | decomp `FUN_400009c4` lines 30–196 |
| `0x100040b6..0x100040b8` (fields) | DRAM-parameter encodings, decoded and applied via `0x400165d0/0x400165e0/0x400166b8` during updater startup | decomp lines 198–265 |
| `0x100040b9..0x100040c0` / `0x100040c1..0x100040c3` | Filename mailbox: 8-char basename + 3-char extension that replaces the default boot file name | literals `0x40001118`/`0x4000111c` = `0x100040b9`/`0x100040c1` |
| `0x10000058 & 0xe0` | Storage backend select (`0x00/0x20/0x40` → NAND registration path; `0x80/0xa0/0xc0` other media) | decomp lines 38–69 |

**Producer of this block: still unknown.** Nothing in the main image
or updater writes the button timings, mailbox, or DRAM fields; they
are inputs. The writers are the earlier stage (mask ROM / SPBoot) or,
in the ISP/FRM host flow, the vendor host tool. This is the same
missing producer as the SRAM descriptor at `0x3f00c518`.

## The updater contains an SD-boot branch (mechanism, not yet a recovery)

Updater startup (`FUN_4000125c` → boot-register consumer
`FUN_400009c4`), when the gate is nonzero:

1. Decodes button timings; if a button condition holds, registers the
   firmware-selection callback (`0x40000888`, the rawC/Firmware2
   selection logic) and initializes storage.
2. Builds a pathname: default **`D:\firmware.brn`**, replaced by the
   mailbox filename if present; drive falls back from 2 to 5
   (prefix becomes `G`) on mount failure.
3. If the file exists (`0x40041728`):
   - extension `.brn` → card-update wrapper `0x4000fc70` (the
     transactional Firmware1 writer), then "reboot" (`0x1000003c = 1`);
   - **any other existing file → `FUN_4000f270(path, 0)`: load that
     file to `0x40000000`, verify, and boot it** (the same loader used
     for `:DEF_FW1/:DEF_FW2`, including checksum gating and the
     SRAM-stub + `0xb34` handoff).

So an image-file boot from SD exists *inside the updater*. What is
still missing is everything before it: **who loads the updater image
itself at cold boot, from where, under what conditions.** Candidates
remain mask ROM loading it from a reserved storage area, or the
ROM loading it from SD directly. Neither is observed.

## Storage header, fully decoded (from the live DRAM copy)

The 512-byte `NAND RSV` header at DRAM `0x40000c80` (from our dump)
contains only: signature/type, block size `0x40000`, total blocks
2048, header blocks 2, reserved counts 487/45/261, Firmware1
start 795 / length `0xe38400` / blocks 57, Firmware2 start 2048 /
length 0 / blocks 0, checksums — FW1 `0x3828`@`+0x42` + `0x3fa3`@`+0x44`,
FW2 pair zero at `+0x46/+0x48` — plus small fields at
`+0x4c..+0x60` (`0x1000`, 16, 5, 1, `0x01d601d4`, `0xffff`) and
`+0x160=2`, `+0x174=5`; the rest is zero. No third firmware region
and no updater region is described here. Reserved-area sizes are
consistent with package regions (A: 487 blk ≈ 120 MiB resource image
A; B: 45 blk ≈ kernel+rootfs ≈ 11 MiB; C: 261 blk, contents unknown,
plausibly including the updater/DRAM-parameter/rawC areas) — a
hypothesis to test with a storage dump, not a mapping.

## Updated boot-chain model

```text
mask ROM / SPBoot (bytes unavailable, non-invasive boundary)
  |  reads storage header, loads an image (main FW1 or updater),
  |  writes boot-info block (0x10001xxx/0x10004xxx), SPBoot script
  |  and descriptor into SRAM, stamps nothing itself
  v
image +0x200 entry stamps gate (main 0x12345678 / updater 0x88888888)
  |  staged loader validates sections against SRAM-copied header,
  |  slot chosen by boot-status bit 1; failure logs "FW%d fail"
  |  with no established bank switch
  +--> main firmware (normal / calibration / loader personalities)
  +--> updater: button/mailbox consumer -> optional SD file boot
       (firmware.brn update, or load-and-boot arbitrary image file)
       -> rawC loop -> :DEF_FW2 installed-image load
```

## What would move this forward, ranked

1. **(Live, read-only)** Stage the boot-info pages with the proven
   CPU-copy trampoline in a loader session: copy
   `0x10004000..0x10004100` and `0x10001000..0x10001400` to DRAM and
   read them back. Gives the actual gate/status/button values the
   earlier stage wrote for a real boot of this unit. (MMIO reads via
   CPU; the SRAM staging precedent applies. No storage writes.)
2. **(Live, read-only, after offline qualification)** Dump internal
   storage: header blocks, Firmware1 (C3 backup), and probe reserved
   area C for an updater image (reset-vector pattern / `DRAM CODE`).
   If the updater is resident in storage, a ROM→updater cold path is
   physically plausible; if it is not, the updater can only arrive
   via SD/ISP and the recovery model changes.
3. **(Offline)** Decode the SPBoot register script at SRAM
   `0x3f00c000` (record format) — it may enumerate the earlier
   stage's load operations (sources/destinations), i.e. the ROM's
   load plan, even though the ROM code itself is unavailable.
4. **(Offline)** Identify the RawCBoot configuration's storage
   location (getter `0x4001c78c`, six 672-byte records, `RawCBoot`
   magic + word-sum) — it is boot-policy data the selector consumes;
   its location may reveal which storage area the early stage owns.

Still excluded, unchanged: testing fallback by damaging Firmware1,
any internal-storage write, opening the body / UART.
