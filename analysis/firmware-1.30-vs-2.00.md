# X half firmware 1.30 versus 2.00

## Outcome

The official 1.30 image provides strong evidence that the lever-review Provia
problem is a firmware 2.00 regression. The 1.30 and 2.00 callers both clear a
temporary photo record and copy the same 30-byte capture fragment before
drawing the secondary-screen film label. In 1.30 the renderer resolves the
film choice directly from playback metadata. In 2.00 a new cached-selector
field was added, but this caller leaves it at zero; zero is interpreted as the
already-resolved Provia selector.

The Auto ISO behavior is different: 1.30 and 2.00 use the same mapping. Auto 3
has selected the ISO-12800 range code since at least 1.30, and the adjacent
ISO-6400 range code already exists in both releases. Replacing Auto 3 remains a
narrow change; adding a fourth preset remains a multi-path settings/UI change.

This is static analysis only. No firmware was modified or installed on a
camera.

## Artifact identity

| Release | Official `FPUPDATE.DAT` SHA-256 | Size | Main image SHA-256 |
| --- | --- | ---: | --- |
| 1.30 | `72d20d9c1c5a2a18d7e5c1ace7916460cebbfe082b4c46551ffd574ec029040b` | 163,630,698 | `34034d7d1314485fbbd8167669a83dbea4d3dc80cc9ba3c87c7ded2c96aa9df6` |
| 2.00 | `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68` | 165,795,434 | `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923` |

Both main images were loaded as little-endian ARMv7 raw binaries at
`0x40000000`. The complete verified 1.30 package inventory is in
[`output/firmware-regions-1.30.json`](output/firmware-regions-1.30.json); the
corresponding 2.00 inventory is in
[`output/firmware-regions.json`](output/firmware-regions.json).

## Package-level comparison

The high-level container layout through the Linux-region start is stable. Both
packages satisfy the same modulo-2^32 little-endian word-sum rule, with a sum
of zero over the declared package extent.

| Region | 1.30 | 2.00 | Result |
| --- | ---: | ---: | --- |
| Package header/updater | 666,624 bytes | 666,624 bytes | changed |
| `SUNP BURN HDR 1` | 4,096 bytes | 4,096 bytes | byte-identical |
| HDR2 control header | 288 bytes | 288 bytes | byte-identical |
| FAT16 resource image A | 125,829,120 bytes | 125,829,120 bytes | changed |
| FAT16 resource image B | 10,485,760 bytes | 10,485,760 bytes | byte-identical |
| ARM zImage | 2,170,880 bytes | 2,084,976 bytes | changed |
| SquashFS rootfs | 8,104,968 bytes | 9,497,344 bytes | changed |
| Main DRAM application | 14,187,520 bytes | 14,910,464 bytes | changed; +722,944 bytes |
| `SUNP BURN HDR 3` | 2,384 bytes | 2,384 bytes | byte-identical |
| `DRAMPAR3` and declared tail | 1,786 bytes | 1,786 bytes | byte-identical |
| Outer trailer | 1,280 bytes | 1,280 bytes | changed |

The Linux kernel remains version 3.18.11. The embedded build strings identify
1.30 as build `#154` from 2025-10-14 and 2.00 as build `#638` from 2026-08-14.
The root filesystem grew by 1,392,376 bytes and two inodes. These observations
bound where changes occurred but do not establish flash partition addresses.

## Lever-review regression

### Firmware 1.30

The `MSG_KEY_L2` string is at `0x40ACC484`; its lever handler reaches the
following sequence:

```asm
403eedf0  mov  r1, r6
403eedf4  mov  r2, #0x1a8
403eedf8  add  r0, sp, #0x148
403eedfc  bl   0x4000cdf0       ; memset(record, 0, 0x1a8)
403eee00  ldr  r1, [r5]
403eee04  add  r2, sp, #0x148
403eee08  add  r0, r2, #0xde
403eee0c  add  r1, r1, #0xca
403eee10  mov  r2, #0x1e
403eee14  bl   0x40008b8c       ; memcpy(record + 0xde, capture + 0xca, 30)
...
403eee3c  add  r1, sp, #0x148
403eee40  mov  r0, #1
403eee44  bl   0x404fb2b0       ; film/filter label renderer
```

The 1.30 renderer at `0x404FB2B0` does not read a cached selector near the end
of this temporary record. It uses `record[0xc0]` as a condition and derives the
film/filter index from the global playback-metadata object reached through its
global state pointer. It then sends that index to the secondary renderer.

### Firmware 2.00

The equivalent 2.00 caller clears a slightly larger `0x1ac`-byte record and
performs the same 30-byte copy. Its renderer calls the helper at `0x40561F64`.
That helper reads `record[0x1a0]` and resolves playback metadata only when the
field equals `0x29`, the explicit “unknown” sentinel. Because the lever caller
zeroes the record and never writes `0x29`, the helper returns immediately and
zero is rendered as film index zero, Provia.

| Role | 1.30 RAM | 1.30 DAT offset | 2.00 RAM | 2.00 DAT offset |
| --- | ---: | ---: | ---: | ---: |
| Clear temporary record | `0x403EEDFC` | `0x09272B1C` | `0x4041CD34` | `0x09400A54` |
| Copy 30-byte capture fragment | `0x403EEE14` | `0x09272B34` | `0x4041CD4C` | `0x09400A6C` |
| Call film/filter renderer | `0x403EEE44` | `0x09272B64` | `0x4041CD7C` | `0x09400A9C` |
| Renderer entry | `0x404FB2B0` | `0x0937EFD0` | `0x405621D4` | `0x09545EF4` |

This comparison strengthens the candidate fix: initialize the 2.00 selector
to `0x29` before the renderer call, restoring the metadata-resolution behavior
that 1.30 performed directly. It still does not prove that the global metadata
object has been refreshed for the just-captured image at that instant; that
runtime-state question remains the final logic-level validation gate.

## Auto ISO comparison

In 1.30, the mapper at `0x404684EC` subtracts one from the stored selection and
dispatches through a jump table. Its three Auto cases are:

```asm
404685e4  mov r0, #3           ; Auto 1
404685ec  mov r0, #5           ; Auto 2
404685f4  mov r0, #7           ; Auto 3
404685f8  bl  0x402125b4       ; range-code mapper
```

The range-code mapper contains both `MAX ISO 6400` and `MAX ISO 12800` and maps
codes 6 and 7 respectively. Version 2.00 preserves the same 3/5/7 Auto mapping
at `0x4043F078`; its Auto 3 immediate is at `0x4043F180`.

| Candidate | 1.30 | 2.00 |
| --- | --- | --- |
| Auto 3 instruction | `0x404685F4`, DAT `0x092EC314` | `0x4043F180`, DAT `0x09422EA0` |
| Original instruction | `mov r0, #7` (`07 00 A0 E3`) | `mov r0, #7` (`07 00 A0 E3`) |
| Replacement hypothesis | `mov r0, #6` (`06 00 A0 E3`) | `mov r0, #6` (`06 00 A0 E3`) |

Only 2.00 is relevant to a current-camera modification. The 1.30 coordinate is
recorded as corroborating history, not as a proposed downgrade or flash target.

## Evidence and limitations

REA/Ghidra 12.1.4 analyzed 28,904 procedures in the 1.30 raw ARM image.
Relevant retained evidence includes:

- procedure inventory: `ev_436b02a05de13a4c5e0aa8923dad7d6975f34f446e57a450e80cdf7c6e5c4527`
- lever-handler function and instructions: `ev_6ca2bea5cb9c2413a4205001225192feec1943ad46485f07c00811834e6ab7d2`
- 1.30 film renderer: `ev_fa4a01ebcdf08c8abddd9f599c20b781aa7279a38619270932cc971006a704f8`
- 1.30 Auto ISO mapper: `ev_f84340f2c033bdcb864c511ed311296a0fa6d1993c6b98fce2b0a5d22ca99f46`

The full REA evidence export and provider snapshot are intentionally ignored as
large, reproducible local analysis artifacts. Ghidra function boundaries are
imperfect in several jump-table-heavy routines; the conclusions above rely on
the observed ARM instructions, call references, and verified file mappings,
not on generated function names.

Neither comparison repairs the package checksum, establishes whether another
signature is enforced, or provides a safe recovery route. Those remain
mandatory before producing or installing any modified package.
