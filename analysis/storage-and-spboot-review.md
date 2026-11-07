# Architecture review, piece 2: storage, slots, and the SPBoot USB lead

Date: 2026-11-06. Offline investigation of existing dumps; no camera
access. This is the second piece of the holistic review. It adds new
evidence about the earlier stage and storage, and corrects one
long-standing misidentification.

Inputs (all local):
- `/private/tmp/xhalf-sram-2026-11-04.bin` (64 KiB SRAM, SHA-256
  `62e05564…`)
- `/private/tmp/xhalf-nand-headers-2026-11-05.bin` (512 KiB, two
  header blocks, SHA-256 `5f1169c5…`)
- `/private/tmp/xhalf-nand-fw1-2026-11-05.bin` (14,910,464 bytes,
  SHA-256 `80350bd2…`)
- Reserved-area heads/probes and the package.

## Finding 1: `0x3f00c000` is the SPBoot USB descriptor block, not a register script

The SRAM page at `0x3f00c000` was previously described as a "sparse
register-init record set" (SPBoot script) with an unknown record
format. It is actually a **USB descriptor block with a `SPBoot>`
prompt and product string**:

```text
0x3f00c000: "SPBoot>\0"
0x3f00c008: 12 01 00 02 00 00 00 40 ad 2a 00 65 ...
            device descriptor: USB 2.00, EP0 64,
            VID 0x2aad, PID 0x6500, bcdDevice 0x0100
0x3f00c01a: 09 02 a7 00 03 01 00 c0 32
            configuration: 167 bytes, 3 interfaces, 0xc0, 100 mA
0x3f00c023: interface 0, vendor, interrupt IN 0x87 (16 bytes)
0x3f00c033: interface 1, vendor, isochronous IN 0x88, alt 0..6
0x3f00c0a3: interface 5, vendor, 3 endpoints:
            bulk IN 0x82 (64), bulk OUT 0x03 (64),
            interrupt IN 0x84 (8)
0x3f00c10d: "iCatch  CA6500A" (product string fragment)
```

This is the **same descriptor layout as the updater's stored
template** at `0x4009da30` (VID/PID `2aad:6500`, 167-byte config,
3 interfaces, interface 5 with `0x82/0x03/0x84`). It is the earlier
stage's USB boot personality.

The `SPBoot>` string and descriptor block are **not present** in
the package, the main image, the updater, or the probed NAND
ranges. A search for the device-descriptor bytes finds only the
main image's loader template (`0xd77e14`) and the updater's
(`0x9da30`). The SPBoot template is therefore likely ROM/earlier-
stage resident, copied to SRAM at boot; its bytes are not in the
package.

### Why this matters

This is a concrete **cold recovery/ISP lead**. The earlier stage has
a `2aad:6500` USB personality with the same vendor interface 5 the
FRM/ISP host tool supports. If the camera can be made to enter the
SPBoot USB mode (failed boot, button condition, or a ROM decision),
the vendor host flow could potentially recover it. The project has
treated the earlier stage as having no USB evidence; the descriptor
block contradicts that.

It is a lead, not a proof: the entry conditions, the SPBoot command
protocol, and whether it accepts the FRM host flow are unqualified.
The `SPBoot>` prompt suggests a console, but no command table is in
this SRAM page.

## Finding 2: the NAND header, parsed

The two header blocks are byte-identical (`0x0` and `0x40000`,
full 256 KiB each). Header fields:

| Offset | Value | Meaning |
| --- | --- | --- |
| `0x00` | `NAND RSV` | signature |
| `0x08` | `0x0000BEEF` | magic |
| `0x0c` | `0x00040000` | block size |
| `0x10` | `0x000006FA` | unresolved (1786) |
| `0x14` | `0x0000031B` | Firmware1 start block (795) |
| `0x18` | `0x00000800` | Firmware2 start block (2048) |
| `0x1c` | `0x0000E384` | Firmware1 length / 256 = `0xE38400` |
| `0x20` | `0` | Firmware2 length |
| `0x24` | `0x00000800` | total blocks (2048) |
| `0x28` | `2` | header blocks |
| `0x2c` | `487` | reserved A blocks |
| `0x30` | `45` | reserved B blocks |
| `0x34` | `261` | reserved C blocks |
| `0x38` | `57` | Firmware1 blocks |
| `0x3c` | `0` | Firmware2 blocks |
| `0x40` | `0x9732` | header checksum |
| `0x42` | `0x3828` | Firmware1 checksum |
| `0x44` | `0x3FA3` | Firmware1 second checksum |
| `0x46`/`0x48` | `0` | Firmware2 checksum pair |
| `0x4a` | `0x5859` | unresolved |
| `0x4c` | `0x1000` | small field |
| `0x50` | `16` | small field |
| `0x54` | `5` | small field |
| `0x58` | `1` | small field |
| `0x5c` | `0x01D601D4` | small field |
| `0x60` | `0xFFFF` | small field |
| `0x160` | `2` | small field |
| `0x174` | `5` | small field |

New details versus the earlier decode:
- The Firmware1 length field is in **256-byte units**
  (`0xE384 * 0x100 = 0xE38400`), not a raw byte count.
- The two header copies are full 256 KiB mirrors, byte-identical,
  not just the first 512 bytes.
- The header block's non-`0xFF` table area runs from `0x62` to
  about `0x8f8`; the rest of the 256 KiB block is erased. The table
  format (bad-block map or reserved map) is not yet decoded.
- The `0x6FA` and `0x5859` fields remain unresolved.

Firmware1 is byte-identical to the package's main image
(`80350bd2…`), confirming the installed firmware is pristine.

## Finding 3: the 36-byte storage descriptor

At `0x3f00c518`, after the SPBoot USB block:

```text
+00: 00dc0098   +04: 00019026   +08: 02001000
+0c: 00080080   +10: 00400005   +14: 00000800
+18: 00040000   +1c: 00004654   +20: 0000421c
```

- `+0x18 = 0x00040000` is the block size; `+0x1c = 0x4654` and
  `+0x20 = 0x421c` are the two recognized NAND primitive pointers
  translated by the main image's `0x40029314`.
- The first `0x18` bytes look like a packed geometry header
  (`0x1000`, `0x0200`, `0x0080`, `0x0008`, `0x4000`, `0x0005`,
  `0x0800` appear as subfields), but the exact field map is not
  decoded.
- The descriptor shares the SRAM page with the SPBoot USB block;
  they are separate structures written by the earlier stage.

## Finding 4: updater residency remains a bounded negative

The updater image is **not** in the probed NAND ranges (headers,
reserved A/B/C heads, selected C blocks, post-FW1 probes), and its
strings are absent. Reserved C holds the **firstburn Linux payload**
(`firstburn` manifest + zImage), not the updater. The package
contains the updater at DAT offset `0x200..0xa2bff`; the cold source
that would run it is still unknown.

The new SPBoot USB descriptor block suggests one possibility: the
earlier stage can enumerate as `2aad:6500` and the FRM/ISP host flow
can upload/run the updater. That is a host-assisted cold path, not a
standalone recovery. The other possibility remains the SD package
run by the resident engine in the main firmware.

## What this changes in the stage model

- Stage 0 is not "no USB evidence." It has a **USB descriptor
  template** and a `SPBoot>` prompt in SRAM; the earlier stage has a
  vendor USB personality. The stage-0 box in
  [boot architecture review, piece 1](boot-architecture-review.md)
  should be updated from "unknown" to "USB descriptor block observed;
  entry/behavior unknown."
- The `0x3f00c000` handoff-contract entry is the **SPBoot USB
  descriptor block**, not a register script. The storage descriptor
  at `0x3f00c518` is separate.
- The recovery lane has a new, concrete lead: the SPBoot `2aad:6500`
  USB mode. It does not yet establish a recovery procedure.

## Open questions / next evidence

1. **SPBoot entry conditions**: what makes the earlier stage enter
   the `SPBoot>` USB mode? Failed main-image boot, a button/GPIO
   condition, a storage flag, or a host request? This is the key
   recovery question.
2. **SPBoot command protocol**: does the descriptor set come with a
   console/ISP command table? The `SPBoot>` prompt suggests one, but
   the SRAM page does not contain a command table. The ROM bytes are
   unavailable; a live attempt would need to enumerate the SPBoot
   mode.
3. **NAND header tables**: decode the `0x62..0x8f8` table area
   (bad-block map vs reserved map) and the `0x6FA`/`0x5859` fields.
4. **Full reserved C dump**: the unprobed blocks between the firstburn
   probes; confirm updater residency and the firstburn boundaries.
5. **Storage descriptor geometry**: decode the first `0x18` bytes and
   compare with the main image's `0x40029e04`/`0x40029314` field
   usage.
6. **Cold updater launch**: if the SPBoot USB mode is host-assisted,
   the FRM host flow becomes the cold recovery/update path; if not,
   the SD package path remains.

## Boundary

The SPBoot descriptor block, NAND header, and storage descriptor are
observed in local dumps. The earlier-stage USB behavior, entry
conditions, and command protocol are inferred or unknown. The
updater-residency negative is bounded by the probed ranges. No
camera access, no storage writes.
