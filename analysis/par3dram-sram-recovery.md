# PAR3DRAM: earlier-stage DRAM-init code recovered from SRAM

Date: 2026-11-09. Offline analysis of the existing 64 KiB SRAM dump
(`/private/tmp/xhalf-sram-2026-11-04.bin`, SHA-256 `62e05564…`) and
the official main image (`firmware-main.bin`, SHA-256 `80350bd2…`).
No camera access.

> **Correction (2026-11-09, later pass).** This note kept the
> parser's literal-pool spelling and missed the actual script
> magic. The parser compares word 0 with `DRAM` and word 1 with
> `PAR3`, so the scripts begin **`DRAMPAR3`**, not `PAR3DRAM`.
> The bytes at `0x40e38084` are the parser's `PAR3`/`DRAM`
> literal pool followed immediately by the wrapper at
> `0x40e3808c`; there is no `d1` version byte. The script data
> was not missing: the NAND header blocks and the update package
> contain it, and the two main-image scripts are valid
> `DRAMPAR3` records. See
> [par3dram-script-recovery.md](par3dram-script-recovery.md) for
> the corrected format, checksums, decoded records, and the
> SRAM-engage mechanism.

## The find

The SRAM dump at `0x3f000000` is not just data. It contains ARM code:
a CP15/MMU/cache setup followed by a **record-script interpreter**.
Its literal pool at `0x3f000484` holds the ASCII string
**`PAR3DRAM`** (`50 41 52 33 44 52 41 4d`), which supplies the
parser's two expected words. The same literal pool appears in the
main image at **`0x40e38084`**, immediately followed by the wrapper:

```text
40e38084: 50 41 52 33 44 52 41 4d   "PAR3" word, "DRAM" word
40e3808c: 64 31 9f e5               e59f3164, wrapper entry
```

There is no `d1` version byte; `64 31` are the low bytes of the
first instruction. The SRAM at `0x3f00048c` is the **same wrapper
code** as the main image at `0x40e3808c`. So:

- The **parser** (`0x3f0000a4..0x3f000480`) is earlier-stage code
  resident in SRAM.
- The **wrapper** (`0x3f00048c..0x3f0005fc`) exists both in SRAM
  and in the main image at `0x40e3808c`.
- The wrapper calls the parser with script pointers from a context
  at `0x3f00e2c0` (`[+0xc]` and `[+0x10]`).

## What the parser does

The parser reads a 16-bit record stream whose header is
`DRAMPAR3` (word 0 `DRAM`, word 1 `PAR3`). The record type is
`(word >> 12) & 0xf`; the dispatch table at `0x3f000170`
implements:

| Type / case | Operation |
| --- | --- |
| base set (`0x1ac`) | `r10 = 0x10000000 | ((word & 0x1f) << 12)` |
| byte write (`0x1bc`) | `*(u8*)addr = value` |
| halfword write (`0x1d4`) | `*(u16*)addr = value` |
| word write (`0x1f4`) | `*(u32*)addr = value` |
| array write (`0x210`) | 1/2/4-byte elements, count in bits 0–5 |
| poll byte (`0x288`) | read/mask/compare, up to 50 retries |
| poll halfword (`0x2d4`) | same, 16-bit |
| poll word (`0x324`) | same, 32-bit |
| copy (`0x374`) | byte/halfword/word copy (types 1–5) |
| delay (`0x404`) | CP15 generic-timer wait |
| add byte (`0x450`) | `*(u8*)addr += value` |

This is a **DRAM/PLL register initialization engine**: set a base,
write register values, poll ready bits, delay, copy calibration
data. The `PAR3` name matches the earlier "DRAMPAR3" lead.

## What was still missing at the time (now partly resolved)

- The **script data** is not in the SRAM dump. The magic appears
  only in the parser's literal pool; the context at `0x3f00e2c0`
  is all zero in this dump, and the `-CPI` pages at
  `0x3f002000`/`0x3f003000` are headers plus zeros.
  **Later correction:** the scripts are not missing. They are in
  the NAND header blocks 0/1 at `+0x200` and in the update package
  at `0x09e1ca70`, as `DRAMPAR3`, not `PAR3DRAM`. See
  [par3dram-script-recovery.md](par3dram-script-recovery.md).
- The **mask ROM** bytes that load and call the parser are still
  unavailable.
- The full earlier-stage loader (storage selection, image
  validation, SPBoot USB entry) is still not in the dump.

## Does this answer "can we get the bytes"?

Yes, for the script bytes: they were already in hand. The NAND
header payload and the update package both contain the full
1,786-byte script block, and the two main-image scripts are valid
`DRAMPAR3` records. The parser and wrapper are recoverable from
the SRAM dump plus the main image. What remains unavailable is
the mask ROM/SPBoot code that reads, selects, and calls the
scripts, not the scripts themselves.

Routes to the remaining code, ranked by fit with the no-hardware
constraints:

1. **Loader ROM read (read-only, no hardware).** The existing
   `0x04f2` memory-read primitive may be able to read the ROM
   mapping. Try candidate bases (`0x00000000`, `0x1f000000`,
   `0x10000000`, and the SPBoot SRAM alias) with a single-word read;
   if one succeeds, dump the range and search for `DRAMPAR3` and
   the SPBoot strings.
2. **SPBoot USB entry (behavioral).** If the earlier stage
   enumerates as `2aad:6500`, the vendor ISP/FRM protocol may allow
   memory read/upload. Entry conditions and protocol are still
   unknown; the descriptor block at `0x3f00c000` is the lead.
3. **Sibling-device boot ROM.** The SPCA6500 is used in other
   cameras (e.g. Akaso V50 Pro). A public ROM/bootloader dump from a
   sibling device could reveal the same boot-ROM family and the
   script consumer.
4. **Hardware routes (excluded).** JTAG/SWD, UART, glitching,
   desoldering the NAND/SoC would obtain the bytes but violate the
   project constraints and risk the camera.

## Boundary

The parser/wrapper code and the `PAR3DRAM` literal pool are
observed in the SRAM dump and the main image. The script bytes are
observed in the NAND header and the update package; their record
semantics are derived from the disassembly. The mask ROM and
SPBoot entry/protocol are not observed, and the earlier-stage
selector/call order remains unknown.
