# DRAM dump mining: slot layout solved, Firmware2 is empty

Date: 2026-11-04. Offline analysis of the live 20 MiB DRAM dump from
[loader-memory-read-live.md](loader-memory-read-live.md)
(SHA-256 `0662762803867a5762b6045a43cd1e6d3505dd9a962f238911b0d2548d1ccf5e`,
dump preserved outside git). No camera access in this pass.

## Internal storage layout (from the live `NAND RSV` header)

The staged eSD header the running system parsed is resident at DRAM
`0x40000c80`. Decoded with the schema from
[recovery-entry-review.md](recovery-entry-review.md):

| Field | Value |
| --- | --- |
| Signature / type | `0xbeef` / `0x32` |
| Block size | `0x40000` (256 KiB) |
| Total blocks | 2048 (**512 MiB** device) |
| Headers | 2 blocks |
| Reserved A / B / C | 487 / 45 / 261 blocks |
| **Firmware1 start** | **block 795** (byte offset `0x0c6c0000`) |
| Firmware1 length | `0xe38400` — exactly the packaged main-image size |
| Firmware1 blocks | 57 (795 + 57 = 852) |
| Firmware1 checksum fields | `0x3828`, `0x3fa3` |
| **Firmware2 start / length / blocks / checksums** | **2048 (= total blocks) / 0 / 0 / 0** |

The layout sums exactly: 2 + 487 + 45 + 261 = 795. Blocks after Firmware1
(852..2048) hold the remaining package regions (resources, Linux, UBIFS);
their assignment was not decoded here.

**Firmware2 is empty on this unit.** Its start is the end-of-device
sentinel and every size/checksum field is zero. Consequences:

- The updater's "Firmware2 is preserved" behavior (see
  [brick-risk-and-recovery.md](brick-risk-and-recovery.md)) preserves an
  empty slot on this camera. There is no second firmware copy for any
  cold-boot selector to fall back to, even if one exists.
- A corrupted Firmware1 therefore has no on-device backup image. This
  hardens the standing rule: **no flashing without an independent,
  proven cold-recovery path**, which still does not exist.
- A future per-device backup (roadmap C3) needs, at minimum: the 2 header
  blocks, reserved areas A/B/C, Firmware1's 57 blocks, and the
  post-852 layout — read through a qualified storage path, not the
  `COLDDATA.BIN` record (which is a separate 1 KiB factory record).

## Runtime RAM map and a scratch candidate

Nonzero runtime data beyond the image is a few hundred small heap/state
objects ending by `0x413d5f55`; nothing but zeros from there to
`0x41400000`. Largest zero gaps in the loader-mode snapshot:

| Gap | Size |
| --- | --- |
| `0x4106a198..0x412c26d8` | 2,401 KiB |
| `0x40fd2613..0x4106a190` | 607 KiB |
| `0x40e83bdd..0x40f0512c` | 517 KiB |
| `0x413187a5..0x4136b658` | 332 KiB |

A zero gap at one instant is not proof of permanent disuse (heap arenas can
be reserved-but-untouched). For a first write/readback test, the least-bad
candidate is a small aligned spot deep inside the largest gap (e.g.
`0x41100000`), written and read back within seconds in the same loader
session, then re-read to confirm no other agent touched it. That qualifies
a scratch area empirically instead of by assumption.

Other dump anchors: loader state `0x40fd0d50` is populated (workspace and
transfer fields); the image's first-page section table at `0x400007a0`
contains the SRAM/low destinations already known from the staged-loader
trace (`0x3f000674`, `0x3f00e000`, `0x00000b24`, `0x00000b34`,
`0x00002700`, `0x00002d18`); 116 aligned SRAM pointers exist in DRAM
structures for future chasing. The dump does not contain the low helper's
bytes or SRAM contents.

## Next

1. Loader write/readback at the scratch candidate via the `0x04f5`
   host-to-memory branch (same alignment discipline as reads), in a fresh
   entry, with before/after reads. First persistent-effect-free write
   rung; still no upload/run.
2. Mine the 116 SRAM pointers / staged-descriptor copies for an SRAM
   alias or earlier-stage remnants in DRAM.
3. C1 stays open. If anything, the empty Firmware2 slot raises the bar.
