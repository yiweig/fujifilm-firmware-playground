# Complete updater layout and correction of historical coordinates

Date: 2026-11-02. Offline package inspection only. No camera access, firmware
execution, card preparation or flashing.

## Finding

The package contains a complete RAM-linked updater executable with its own
vectors and startup code. It occupies **package `0x200..0xa2bff`**, linked at
`0x40000000`. Its reset vector branches to `0x40000340`, corresponding to
package `0x540`. The latter is the reset-body location, not the start of the
whole executable.

The previously extracted `card-updater.bin` started at package `0x540` and
therefore omitted **`0x340` bytes (832 bytes)**. Mapping that shortened artifact
at `0x40000000` produced shifted disassembly coordinates and unreliable
absolute-pointer interpretation. The complete artifact and helper are now
corrected. Historical binaries/evidence remain separate for provenance.

An older [fallback report](firmware-copy-and-fallback.md) also attributed
`0x405e...`/`0x405f...` function addresses to the small extracted updater.
Those are updater-library addresses in **firmware-main.bin**. They are valid
main-image findings, but must not be used as small-updater addresses or as
proof of a boot stage independent of the main application.

## Artifact identities and coordinates

Official package SHA-256:
`3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`.

| Artifact | Package interval, end exclusive | Size | Interpretation |
| --- | --- | --- | --- |
| Complete updater | `[0x200, 0xa2c00)` | 666,112 bytes | Vectors, image header, reset body, code and data |
| Historical shortened extract | `[0x540, 0xa2c00)` | 665,280 bytes | Complete updater with first `0x340` bytes removed |

Complete updater SHA-256:
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
Historical shortened SHA-256:
`e65576cadbd4db737e0dd2cfcff7395265ed4087fced6d267108f1af2718df51`.

For the complete updater:

```text
runtime address = 0x40000000 + (package offset - 0x200)
runtime address = 0x40000000 + complete-image file offset
```

Mapping the shortened body alone would instead require base `0x40000340`.
The complete image is preferred because it retains vectors and exception code.
These formulas are specific to this artifact; other package regions and the
main image have their own origins.

## Independent mapping checks

1. Package word `0x200` is ARM `0xea0000ce`, an unconditional branch whose
   target is package `0x540`. With the complete-image mapping, it is exactly
   `0x40000000 -> 0x40000340`.
2. Reset startup at runtime `0x400003b0..0x400003b8` builds `0x40000000` and
   writes it to CP15 VBAR. The complete image has exception vectors there;
   the historical mapping instead placed reset-body instructions there.
3. Three updater literal pools resolve to exact embedded diagnostics:

| Literal location | Stored pointer | Referenced bytes in complete image |
| --- | --- | --- |
| `0x40016c08` | `0x4009451a` | `SUNP BURN HDR 1` |
| `0x40017b80` | `0x40094799` | `damage FW1 checksum1 %04x -> %04x` plus newline |
| `0x400193c0` | `0x400949c7` | `[CardUpdate]keep original Firmware2.` plus newline |

Those stored pointers did not resolve to their corresponding strings when the
shortened extract was mapped at `0x40000000`. Similar-looking standalone
pointer/string coincidences elsewhere are insufficient to validate a mapping.
The checks above combine vector flow, startup configuration and updater-specific
references. They are static checks, not a measured runtime boot address.

The full image also retains the `DRAM CODE` marker at image offset `0x310`
(package `0x510`), exception stubs and the reset vector omitted by the earlier
extract. These are parts of the updater executable, not evidence of mask ROM.

## What the neighboring regions establish

The `SUNP BURN HDR 1` region is 4,096 bytes, but only **25 bytes are nonzero**
in this package. All nonzero words are confined to offsets through `0x80`:

| HDR1 offset | Nonzero value |
| --- | --- |
| `0x00..0x0f` | `SUNP BURN HDR 1` marker and terminator |
| `0x20` | `0x00000005` |
| `0x40` | `0xffffffff` |
| `0x74` | `0x07800000` |
| `0x78` | `0x00a00000` |
| `0x7c` | `0x04000000` |
| `0x80` | `0x00000001` |

The main-image parser `0x405f8fdc`, after validating the HDR1 marker, copies its
`+0x74..+0x7f` words into parser state at `+0x238` (`0x405f90c8..0x405f90d4`).
The first two values match the known 120 MiB and 10 MiB resource-image sizes.
It also copies selected control words. This supports configuration/header data,
not an additional substantial ARM boot executable hidden in this region.
No meaning is assigned to the remaining control words without consumer analysis.

The separate `DRAMPAR3` region contains 1,786 bytes and is not all padding.
Its vendor encoding and earlier-stage interpretation remain unresolved here;
it must not be treated as an independently bootable program merely because it
is needed around startup. Resource disks, Linux payloads and the large main
image retain their previously documented identities. A nested/transformed
early-stage payload elsewhere in those resources has not been exhaustively
excluded by this pass.

## Tool and documentation changes

- [`card.py`](card.py) uses `card-updater-full.bin` and
  `card-updater-full.elf`, validates the full-image hash, and can recreate the
  binary from the official package when absent. The older binary/ELF are not
  silently reused or overwritten.
- [`inventory_firmware.py`](inventory_firmware.py) now includes the complete
  updater size, hash, branch instruction and reset target in
  [`output/firmware-regions.json`](output/firmware-regions.json).
- Historical shortened-artifact REA records are retained as historical mapping
  attempts. Their quoted runtime addresses require revalidation against the
  complete image. This pass uses local original-byte analysis, not new REA
  observations.

Reproduce the checks:

```sh
python3 analysis/inventory_firmware.py
python3 analysis/card.py disasm 0x40000000 0x40000030
python3 analysis/card.py disasm 0x400003a8 0x400003c4
python3 analysis/card.py strings 'SUNP BURN HDR 1|damage FW1 checksum1|keep original Firmware2'
python3 analysis/fw.py disasm 0x405f9094 0x405f9118
```

Validation output: [`output/updater-layout-validation.txt`](output/updater-layout-validation.txt).
Package checksum/inventory regeneration and the mapping/hash checks passed.
These checks neither execute firmware nor test recovery on hardware.

## Recovery consequence and next bounded question

We have recovered the updater's omitted prefix and corrected its coordinates;
we have not identified the ROM or firmware-slot selector that loads it.
Having a program's own reset vector does not establish which earlier code
places it in RAM, supplies DRAM initialization, or chooses it after a bad flash.

The next useful trace is how the main application's file-update path hands
control to this complete updater: which bytes it loads, where it places them,
and what initialization it supplies. That can define the updater's prerequisites
and distinguish a main-assisted launch from a genuinely independent cold-boot
entry. Automatic Firmware2 fallback and removable-SD recovery remain unproved.
