# Section loader dependency: the earlier-stage low helpers are missing

Date: 2026-11-10. Static analysis of the main image's staged loader
`FUN_40000338` and the loader-mode SRAM dump. No camera access.

## Loader state

The loader state pointer at `0x40000790` points to `0x40000ad0`. The
static image initializes:

| Address | Value | Role |
| --- | --- | --- |
| `0x40000790` | `0x40000ad0` | loader state pointer |
| `0x40000798` | `0x40000b00` | boot-stage table (count 6, stage mask `0x98400500`) |
| `0x400007bc` | `0x00000b24` | low helper (cache/flush class) |
| `0x400007c4` | `0x00002700` | low helper (log/notify class) |
| `0x400007d4` | `0x00000b34` | the unrecovered low copy/handoff helper |
| `0x400007e0` | `0x00002d18` | low helper |
| `0x400007e8` | `0x00000788` | low helper (entry/return class) |

`func_0x400008e8` adds `*0x40000ad0` to a block index and calls the
function pointer at `0x3f000000 + 0x4e34`. In the loader-mode SRAM dump
that slot is **zero**, and the dump contains the SPBoot descriptor at
`0x3f00c000` and storage descriptor at `0x3f00c518`, but no low helper
code. The high-entropy SRAM pages are not an ARM code body.

## Consequence

`FUN_40000338` is a **consumer** of the earlier stage's low helpers. It
validates/copies the installed image using the SRAM header and the
boot-stage table, then relies on `0xb34`/the SRAM function slot for the
copy/handoff. The main image alone cannot run the staged loader; the
`0x04f7` stub and the RAM-pushed updater hit the same dependency.

This means a checksum-correct modified image **still cannot be booted**
through `+0x200` from the loader mode without the cold-boot earlier
stage. The checksum work is necessary but not sufficient.

## What is missing

- The low-memory functions `0xb24/0x2700/0xb34/0x2d18/0x788`.
- The SRAM function slot at `0x3f004e34` as populated during a cold
  boot.
- The earlier-stage/mask-ROM code that writes those values.

The loader's low-memory reads wedge, and the trampoline low copy wedged
at `0xb00`. The remaining read path is the vendor `usbt` read-only
protocol (`0x04f1`/`0x04f3`) or a mask-ROM/SPBoot dump.

## Static boundary

The section-loader/checksum model is now:

| Piece | State |
| --- | --- |
| Image byte-sum and header pair | decoded/verified |
| Boot-stage table `0x40000b00` | decoded (stage fills, not sections) |
| Staged loader `FUN_40000338` | decoded; consumes SRAM header + low helpers |
| Low helper code | missing (earlier stage/mask ROM) |
| RAM-image substitution via `+0x200` | not self-contained; blocked |
