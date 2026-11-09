# PAR3DRAM: earlier-stage DRAM-init code recovered from SRAM

Date: 2026-11-09. Offline analysis of the existing 64 KiB SRAM dump
(`/private/tmp/xhalf-sram-2026-11-04.bin`, SHA-256 `62e05564…`) and
the official main image (`firmware-main.bin`, SHA-256 `80350bd2…`).
No camera access.

## The find

The SRAM dump at `0x3f000000` is not just data. It contains ARM code:
a CP15/MMU/cache setup followed by a **record-script interpreter**.
Its literal pool at `0x3f000484` holds the ASCII magic
**`PAR3DRAM`** (`50 41 52 33 44 52 41 4d`). The same magic and a
`d1` version byte appear in the main image at **`0x40e38084`**:

```text
40e38084: 50 41 52 33 44 52 41 4d 64 31   "PAR3DRAMd1"
40e3808e: e59f3164 ...                    the wrapper code
```

The SRAM at `0x3f00048c` is the **same wrapper code** as the main
image at `0x40e3808e`. So:

- The **parser** (`0x3f0000a4..0x3f000480`) is earlier-stage code
  resident in SRAM.
- The **wrapper** (`0x3f00048c..0x3f0005fc`) exists both in SRAM
  and in the main image at `0x40e38084`.
- The wrapper calls the parser with script pointers from a context
  at `0x3f00e2c0` (`[+0xc]` and `[+0x10]`).

## What the parser does

The parser reads a 16-bit record stream whose header is
`PAR3DRAM`. The record type is `(word >> 12) & 0xf`; the dispatch
table at `0x3f000170` implements:

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

## What is still missing

- The **script data** is not in the SRAM dump. The magic appears
  only in the parser's literal pool; the context at `0x3f00e2c0`
  is all zero in this dump, and the `-CPI` pages at
  `0x3f002000`/`0x3f003000` are headers plus zeros.
- The **mask ROM** bytes that load and call the parser are still
  unavailable.
- The full earlier-stage loader (storage selection, image
  validation, SPBoot USB entry) is still not in the dump.

## Does this answer "can we get the bytes"?

Partially. The earlier-stage **DRAM-init parser and wrapper are
recoverable** from the SRAM dump plus the main image. The remaining
bytes are the mask ROM and the script data.

Routes to the rest, ranked by fit with the no-hardware constraints:

1. **Loader ROM read (read-only, no hardware).** The existing
   `0x04f2` memory-read primitive may be able to read the ROM
   mapping. Try candidate bases (`0x00000000`, `0x1f000000`,
   `0x10000000`, and the SPBoot SRAM alias) with a single-word read;
   if one succeeds, dump the range and search for `PAR3DRAM` and
   the SPBoot strings.
2. **SPBoot USB entry (behavioral).** If the earlier stage
   enumerates as `2aad:6500`, the vendor ISP/FRM protocol may allow
   memory read/upload. Entry conditions and protocol are still
   unknown; the descriptor block at `0x3f00c000` is the lead.
3. **Sibling-device boot ROM.** The SPCA6500 is used in other
   cameras (e.g. Akaso V50 Pro). A public ROM/bootloader dump from a
   sibling device could reveal the same boot-ROM family and the
   PAR3 script source.
4. **Hardware routes (excluded).** JTAG/SWD, UART, glitching,
   desoldering the NAND/SoC would obtain the bytes but violate the
   project constraints and risk the camera.

## Boundary

The parser/wrapper code and the `PAR3DRAM` magic are observed in the
SRAM dump and the main image. The script data, mask ROM, and SPBoot
entry/protocol are not observed. The parser's record semantics are
derived from the disassembly; the exact script contents and the
earlier-stage call order remain unknown.
