# DRAMPAR3: the DRAM-init scripts, recovered and decoded

Date: 2026-11-09. Offline analysis. Sources:

- NAND header blocks 0 and 1, payload at block offset `+0x200`
  (`/private/tmp/xhalf-nand-headers-2026-11-05.bin`,
  SHA-256 `5f1169c5…`; blocks 0 and 1 are byte-identical).
- `FPUPDATE.DAT` official 2.00, file range
  `0x09e1ca70..0x09e1d169` (1,786 bytes,
  SHA-256 `97cc52ee…`).
- `firmware-main.bin` official 2.00
  (SHA-256 `80350bd2…`), scripts at `0x40df85f4` and `0x40df8662`.
- SRAM dump (`/private/tmp/xhalf-sram-2026-11-04.bin`,
  SHA-256 `62e05564…`), parser at `0x3f0000a4`, wrapper at
  `0x3f00048c`.

No camera access. The decoded record stream is reproducible with
[`par3dram.py`](par3dram.py).

## Summary

The earlier-stage DRAM-init data was not missing. It is the
1,786-byte `DRAMPAR3` block in the NAND header and the update
package, and it is also the format the SRAM parser consumes.
This pass:

1. corrects the magic: the parser expects **`DRAMPAR3`**, not
   `PAR3DRAM`;
2. decodes every record type from the SRAM parser and verifies
   the script checksums;
3. identifies the boot-header scripts (cold and warm variants);
4. identifies the two main-image scripts and the SRAM-engage
   wrapper that runs them;
5. ties the main USB loader's `DRAMPAR3` parameter buffer to the
   same script location (`0x3f000200`) the updater restores.

## Correction: `DRAMPAR3`, not `PAR3DRAM`

The parser compares the first two 32-bit words of a script with
the literal pool at `0x3f000484` / `0x3f000488`:

```text
3f000484: "PAR3" (0x33524150)     ; loaded into r7
3f000488: "DRAM" (0x4d415244)     ; loaded into r8
3f0000cc: cmp r6, r8              ; word0 == "DRAM"
3f0000d0: cmpeq r0, r7            ; word1 == "PAR3"
```

So the on-disk/script header is **`DRAM` then `PAR3`**:
`44 52 41 4d 50 41 52 33`. The literal pool happens to spell
`PAR3DRAM` because the two expected words are stored in string
order, but the comparison order is the reverse.

The byte block at `0x40e38084` in the main image is **not** a
`PAR3DRAM` header with a `d1` version byte. It is the parser's
literal pool:

```text
40e38084: 50 41 52 33 44 52 41 4d   "PAR3DRAM"  (r7/r8 literals)
40e3808c: 64 31 9f e5               e59f3164    first wrapper instruction
```

The bytes `64 31` are the low half of the instruction word, not
the ASCII string `d1`. The wrapper starts at `0x40e3808c`, which
is exactly the SRAM wrapper address `0x3f00048c` minus the fixed
relocation delta.

The main-image search in the earlier pass only looked for
`PAR3DRAM`, so it missed the real scripts. `DRAMPAR3` appears
seven times in the main image; only two of those are valid
scripts. The rest are string/function literals.

## Script format

```text
+0x00  "DRAM"                          u32 0x4d415244
+0x04  "PAR3"                          u32 0x33524150
+0x08  u16 payload_len, u16 checksum
+0x0c  u16 version?, u16 reserved       (observed 0 for main scripts)
+0x10  records, payload_len bytes
```

The checksum is the 16-bit sum of little-endian halfwords over
`[+0x10, +0x0e + payload_len)`: the payload minus its final
2-byte terminator. This was verified against all five scripts
below; an off-by-two in the coverage fails every one.

Record header is a little-endian `u16`: type in bits 15..12,
address low bits in bits 11..0. Types 4–10 use
`address = base | (word & 0xfff)`. Types 2, 3 and 0 end the
script; types 12–15 are 2-byte no-ops.

| Type | Operation | Bytes |
| --- | --- | --- |
| 1 | set base = `0x10000000 \| ((word & 0x1f) << 12)` | 2 |
| 4 / 5 / 6 | write u8 / u16 / u32 | 3 / 4 / 6 |
| 7 | array write: u8 control, bits 7..6 width, bits 5..0 count−1 | 3 + data |
| 8 / 9 / 10 | poll u8 / u16 / u32: expected, mask, up to 50×257 retries | 4 / 6 / 10 |
| 11 | copy/delay/add: subtype `(word >> 8) & 0xf` | 2–10 |
| 11.1/2/3 | copy u8/u16/u32 from `+2` pointer to `+6` pointer | 10 |
| 11.4 | delay `u8 count × 27` CP15 timer ticks | 2 |
| 11.5 | `u8 [u32 +2] += u8 +6` | 7 |

## The scripts

| Script | Location | Payload | Records | Checksum | Role |
| --- | --- | ---: | ---: | ---: | --- |
| Boot cold | NAND `+0x200` | `0x356` | 169 | `0x6f8b` | full cold init |
| Boot tail | NAND `+0x566` | `0x18` | 9 | `0xde9f` | short post-init tail |
| Boot warm | NAND `+0x58e` | `0x35c` | 172 | `0x25fc` | warm/resume variant |
| Main A | `0x40df85f4` | `0x5e` | 15 | `0xf119` | PHY re-lock |
| Main B | `0x40df8662` | `0x1d4` | 87 | `0xe4fd` | controller/PHY re-init |

All five checksums pass. `Main A` ends exactly where `Main B`
begins (`0x40df8662`), and a table at `0x402f7748` contains both
pointers adjacently, consistent with a pair.

The boot header carries three scripts in one 1,786-byte payload.
The warm variant is the cold script minus its first five
operations, plus the tail sequence:

```text
cold:  reset/clock writes -> controller -> PHY -> ... -> clock tail
warm:                          controller -> PHY -> ... -> clock tail
                                                         ^ tail script
```

The small `0x566` script contains the same tail sequence
(`0x10000980/988/98a/984`, poll, clear) and one extra write,
`0x100004ff = 0x52`. It is likely a separately callable variant;
the exact selector is not observed.

### Register blocks

The labels are inferred from the access patterns, not from a
datasheet:

| Base | Observed use | Inference |
| --- | --- | --- |
| `0x10000000` | chip ID `0x10000324 = 0xd8`, reset/clock gates, polls | system/clock/reset |
| `0x10004000` | `0x10004930 = 3`, `0x10004030 = 3`, `0x10004071 = 0x0b`, `0x10004055 = 1` | clock/power |
| `0x10012000` | six repeated lane blocks `0x12490`, `0x12540`, `0x125f0`, `0x126a0`, `0x12750`, `0x12800`; `0x12304/08/20/24` status | DDR PHY |
| `0x10015000` | `0x15004` start/config, `0x15018` status polls, `0x15020/84`, `0x1528c..0x1540c` per-rank, `0x15810/4c/72`, `0x1593f` | DDR controller |

The five delays are all `180 × 27` timer ticks; the cold script
uses five, the main pair uses four. The cold script writes the
chip ID `0xd8` early, matching the main firmware's hardware-ID
check, which is a useful cross-check that this really is
pre-main-firmware code.

### DRAM identity

The main image names the parameter file directly:

```text
40b4cbda: "D:\EVB_LPDDR3_4G_MDP_400MHz.prm"
40b4cc16: "Please put dramparam into sd card to run this test!"
```

So the target is **LPDDR3, 4 Gbit (512 MiB), 400 MHz**, in the
EVB/MDP configuration. This is consistent with the controller and
PHY writes above; it is string evidence for the part class, not a
JEDEC timing decode. The same block is what the update package
ships, so the production unit uses the EVB parameter set.

## How the code is engaged

The SRAM dump's code block is a copy of the main image's tail
block:

| Main image | SRAM | Bytes |
| --- | --- | --- |
| `0x40e37c04..0x40e38275` | `0x3f000004..0x3f000675` | `0x671` |

The relocation delta is fixed: `0x1e37c00`. The first word
differs: the main image has `mrc p15, c1` at `0x40e37c00`, while
SRAM `0x3f000000` is zero (a one-word patch at the entry). From
`+4` onward the block matches byte-for-byte. The parser and
wrapper match exactly:

- parser: main `0x40e37ca4` = SRAM `0x3f0000a4` (0x3dc bytes);
- wrapper: main `0x40e3808c` = SRAM `0x3f00048c` (0x174 bytes);
- entry: main `0x40e381fc` = SRAM `0x3f0005fc`;
- the u16/u32 read helpers at `0x40e38234/0x40e3824c` are
  copied too.

The main image block starts with the three CP15/MMU helpers
(`0x40e37c00`, `0x40e37c34`, `0x40e37c74`): disable
MMU/caches, install a flat table, re-enable. Strings near the
script table name `halCpuSramEngage`, `mSramFuncEngagePtr`,
`SRE` and `SRX`, which matches this enter/exit pattern. The
block is a self-contained SRAM routine that can re-init DRAM with
the MMU and caches off.

The context at `0x3f00e2c0` is the call structure:

```text
+0x08  callback
+0x0c  script A pointer
+0x10  script B pointer
```

The entry at `0x3f0005fc` calls the wrapper with `r0=1`, which
runs `[ctx+0x10]` (script B), invokes `[ctx+0x8]` if nonzero,
then calls the wrapper with `r0=0`, which runs `[ctx+0xc]`
(script A). In the SRAM dump all three fields are zero because
the context was cleared after use. The main-image table makes the
pair explicit: `0x40df85f4` and `0x40df8662` are adjacent at
`0x402f7748`/`0x402f774c`.

This establishes the mechanism, not the runtime caller. The
exact code that fills `0x3f00e2c0` and calls `0x3f0005fc` is not
observed; the copy and the table are the evidence.

## The update-package block is the same bytes

`FPUPDATE.DAT` 2.00 contains exactly the three boot scripts:

```text
0x09e1ca70..0x09e1d169   1,786 bytes
SHA-256 97cc52ee2732967b8d8e4a0ac38ae44e4589120cf716ac39446f73e8e1f8858a
```

That is byte-identical to NAND block 0 payload `+0x200..+0x8fa`
(and block 1's copy). The earlier version comparison already
found this region byte-identical across 1.01, 1.20, 1.30 and
2.00, so the DRAM parameters are a stable structural block, not
per-release tuning.

This also connects the USB handoff work: the main loader receives
a `DRAMPAR3` buffer and copies it to `0x3f000200`; the updater's
startup restore checks and copies to the same address. That
address is the natural script pointer for the parser. The
"DRAM parameters" are the init script, not opaque calibration
data.

## What remains open

- The earlier-stage code that reads NAND header blocks 0/1,
  validates them, and selects/copies the script is still not
  recovered. We now have the data and the consumer; only the
  producer/selector before the main image is missing.
- The exact register-level meaning of the controller/PHY fields
  is not decoded. The script is recoverable and re-runnable in
  principle, but not yet a datasheet-level description.
- The small tail script's end quirk is observed but not explained:
  its last record starts one byte before the payload end and reads
  one byte into the next script's header. The parser's loop is
  byte-granular, and the checksum still validates. Treat the last
  two bytes as padding.
- The main pair's runtime caller and ordering are inferred from
  the table and the SRAM copy, not observed live.
- The mask ROM/SPBoot entry and the independent recovery path
  remain unchanged open items.

## Reproduce

```sh
python3 analysis/par3dram.py /private/tmp/xhalf-nand-headers-2026-11-05.bin
python3 analysis/par3dram.py firmware-main.bin
```

The decoder prints every record, the checksum result, and the
payload length. The boot-header decode is saved at
[`output/par3dram-boot-header-decode.txt`](output/par3dram-boot-header-decode.txt).

## Boundary

The five scripts, their checksums, their record semantics, the
main-image block copy, and the `PAR3DRAM`/`DRAMPAR3` distinction
are observed in the named artifacts. The register-block names,
the warm/cold roles, and the runtime call order are inferences
from access patterns and table/copy structure, not from a live
trace or a datasheet. No persistent change was made to the camera.
