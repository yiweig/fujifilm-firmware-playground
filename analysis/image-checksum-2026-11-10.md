# Firmware image checksum: decoded and verified

Date: 2026-11-10. Static analysis + Python verification. The stock
image's Firmware1 checksum is a **16-bit additive byte sum**, stored as
two halfwords in the NAND header whose sum is the total.

## Algorithms

| Function | Role |
| --- | --- |
| `0x40057d40` | 16-bit byte sum: `sum(data) & 0xffff` |
| `0x4006b130` | 32-bit additive word sum over little-endian words, with tail bytes shifted into the final partial word |

The updater's installed-image checker (`0x405efbe4` in the main image)
reads a firmware region, computes the 16-bit byte sum with `0x40057d40`,
and compares it with the stored halfword(s) for Firmware1 or Firmware2.
Firmware1 combines `+0x42` and `+0x44`:

```text
expected = (u16(header + 0x42) + u16(header + 0x44)) & 0xffff
```

## Stock verification

```text
firmware-main.bin size        14,910,464 (0xe38400)
byte_sum16                    0x77cb
header +0x42 / +0x44          0x3828 / 0x3fa3
pair sum                      0x77cb
```

Exact match. The updater invalidates Firmware1 by adding `2` to
`+0x42` (logged as `damage FW1 checksum1 %04x -> %04x`) and restores
the saved value only on a successful update.

## Repair

`analysis/fw_checksum.py` computes both sums and can repair a header:

```sh
python3 analysis/fw_checksum.py --image firmware-main.bin
python3 analysis/fw_checksum.py --image firmware-main.bin --header nand-header.bin --write repaired.bin
```

It keeps `+0x42` and sets `+0x44 = (byte_sum16(image) - +0x42) & 0xffff`
so the pair sum matches the modified image.

## Where the header is consumed

- The NAND header lives in block 0 (two 256 KiB copies). Writing NAND is
  still excluded.
- The staged loader (`FUN_40000338`, `+0x200` entry) consumes the
  **SRAM-copied header** at `0x3f000000`; `piRam40000798` is the boot
  stage table at `0x40000b00`, not the section table.
- The SRAM header copy is RAM, so a RAM-loaded modified image could patch
  `+0x42/+0x44` there without touching NAND. The remaining blocker is a
  safe boot/execute route for the modified image.

## File-loaded images

The updater's `FUN_4000f270` file boot uses the 32-bit word sum
(`FUN_4006b130(buffer, length)`) and compares it with the file record
field `+0x9c`, gated by `(file_record + 0x38) & 1`. A normal FAT file
may not set that gate, so the file boot may skip this check; the SD
mount/state remains the blocker for that path.

## Boundary

The 16-bit byte-sum equation and stock match are verified offline. The
header's own `+0x40` checksum (`0x9732`) coverage was not re-derived in
this pass (the 512 KiB NAND header dump is no longer in `/private/tmp`);
the saved reports record the value. No camera access or storage write
was performed.
