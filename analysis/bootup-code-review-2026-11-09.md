# Bootup / `0x04f7` code review: what is confirmed, what is not

Date: 2026-11-09. Offline review of the official 2.00 main image
(SHA-256 `80350bd2…`) and the existing live evidence. No camera
access in this pass. The goal is to confirm or deny the bootup and
RAM-handoff model after the live UI showed the stock main firmware
running after every push.

## Verdict

The **request/copy sequence is confirmed in code**, but **payload
execution is not**. The handoff's runtime correctness depends on an
unrecovered earlier-stage helper at CPU address `0x00000b34` and on
the mapping/cache state around the hardware copy. The prior
"updater boots from RAM" conclusion is not supported.

The live debug UI is the stock main firmware's calibration UI. The
3-interface/`2aad:6500`/bootquery-timeout signature is shared by the
loader and the stock calibration mode; it does not identify the
updater.

## Confirmed: the `0x04f7` state-6 sequence

`FUN_401e1f40` (state 6) does, in code order:

1. Calls the preparation callback at state `+0x40` (the mode-`0x88`
   park in our loader boot).
2. Sets destination `0x40000000` when state `+0x3c` is zero.
3. Calls the storage-interface op-10 read into `B+0x800`.
4. Copies `0x4000` bytes to SRAM `0x3f000000`.
5. Calls the helper `FUN_401eb1ec(A, B, size, dest, 2)`.

`FUN_401eb1ec`:

- runs the address guards and cache preparation;
- copies the `0x800`-byte stub into workspace B;
- writes the parameter block at `0x3f00d000`:
  `[+0] = A`, `[+4] = size` (rounded even), `[+8] = dest`,
  `[+0xc] = DRAM-parameter pointer`, `[+0x10] = parameter size`;
- disables IRQs, calls the copied stub, and loops if it returns.

The stub at `0x401ed070`:

```text
0x401ed080  load source/size/dest from 0x3f00d000
0x401ed08c  guard source   (0x4002a7bc)
0x401ed0a0  guard dest     (0x4002a890)
0x401ed0a4  program copy engine (0x10003000..0x10003050)
0x401ed0d4..0x401ed118  copy in <=1 MiB chunks
0x401ed134  blx 0x00000b34(dest, size)
0x401ed138  normalize CPSR
0x401ed14c  if dest == 0x40000000: blx 0x40000200
0x401ed154  blx dest (0x40000000)
0x401ed158  b .   (if the payload returns)
```

The call order is therefore: **copy → low helper `0xb34` → payload
`+0x200` → payload reset vector**. The payload entry is *after* the
low helper. If the helper does not return, or if the CPU still sees
the old loader's code at `0x40000200`/`0x40000000`, the old loader's
stub runs instead.

## Confirmed: the old-loader fallback

If the pushed entry is not executed, the code already at
`0x40000000` is the running loader. Its `+0x200` stub stamps the gate
and branches to the staged loader at `0x40000338`. The staged loader:

- validates the section table from the SRAM-copied header;
- reads the selected firmware region **from NAND** and copies the
  sections into RAM;
- zeroes BSS, waits for `0x10000911 & 0xf0 == 0xe0`, and jumps to the
  loaded entry.

That path boots the stock FW1 image and produces the calibration/debug
UI (`EnableTest.txt` present). This is exactly what the live UI shows.
So the observed post-handoff state is the **old loader's stock
fallback**, not the pushed payload.

## The address guards pass for the live buffer

`0x40002b0c` returns `(byte[0x10015872] & 31) << 6`; the guard limit
is `(capacity + 0x400) << 20`. The DRAMPAR3 boot scripts set
`0x10015872 = 0x10`, giving a `0x80000000` limit. The live bootquery
buffer `0x45cc2cc0` and the 14.9 MB size are below that limit and
64-byte aligned, so the source/destination guards should pass. This
makes the guards an unlikely primary failure (the live register value
is still worth confirming with a CPU-copy read).

## The failure boundary: `0xb34`, mapping, and cache

The stub calls `0x00000b34(dest, size)` after the hardware copy. The
low helper is at CPU address `0x00000b34`; the maintained analysis
shows it maps to physical `0x00000b34`, not to the SRAM copy at
`0x3f000b34` or the main-image file offset. Its bytes and complete
effects are **not recovered**. The earlier work called it a
"plausible cache/memory synchronization" helper, but that is a
hypothesis, not an identified function.

The same helper is called on the normal boot path after the SRAM blob
copy (`0x4000063c`, dest `0x3f000000`, size `0x674`) and returns
there. That does not prove it performs the necessary instruction-cache
invalidate for a `0x40000000` destination or that the hardware-copy
address (bit 30 cleared) lands where the CPU's `0x40000000` mapping
reads.

The live result is consistent with either:

- the helper does not return / resets the SoC;
- the helper returns but the copied payload is not visible at the
  CPU's `0x40000200`/`0x40000000` (mapping or I-cache/TLB staleness);
- the copy engine writes the wrong physical range.

The stock fallback is the observable for all three.

## Prior claims: status

| Claim | Status |
| --- | --- |
| `0x04f7` calls a prep, copies a stub, programs a hardware copy, calls `0xb34`, then the payload entry | **Confirmed in code** |
| The payload is copied to `0x40000000` and executed | **Not confirmed at runtime**; live UI is stock |
| `0xb34` is a post-copy cache/memory sync | **Unproven**; implementation unavailable |
| The stock updater booted from RAM | **Not supported**; live UI is the stock main firmware |
| The 3-interface/bootquery-timeout identifies the updater | **Denied**; the loader/calibration mode shares it |
| State-6 7/15 "completions" are payload boots | **Not supported**; resets into calibration fallback |
| The staged loader re-reads stock sections from NAND | **Confirmed**; it is the fallback path |
| The reset/RTOS/staged-loader bootup model | **Static model confirmed**; earlier-stage/mask ROM unknown |

## Next tests

1. **Localize the stub.** Patch the running loader's stub at
   `0x401ed070` (copied to workspace B by `FUN_401eb1ec`) one call site
   at a time with `b .`:
   - `0x401ed134` (low-helper call): a hang means the stub reached the
     helper call; the copy/guards succeeded.
   - `0x401ed150` (`+0x200` call): a hang means the helper returned and
     the payload entry call is reached.
   - `0x401ed154` (reset call): a hang means the `+0x200` call returned.
   No hang at any site means the stub failed before that call.
2. **Read `0x10015872` live** through a CPU-copy/MMIO staging path (not
   the DMA `0x04f2` path) to confirm the capacity and guard.
3. **If the helper call is reached but the payload entry is not**, the
   failure is the helper/mapping/cache. The missing artifact is an
   authenticated earlier-stage low-address image covering
   `0x00000b34`; no supplied-state test can reveal its bytes.
4. **Any future execution claim must use a UI marker** (menu label,
   version string) — not a USB drop, descriptor shape, or timing.

## Boundary

The code sequence and the fallback path are observed in the shipped
main image and the existing live evidence. The runtime failure point
between the copy engine and the payload entry is not isolated. The
conclusion that the pushed payload does not execute rests on the live
UI (stock main firmware) and the shared signature, not on a direct
memory read of `0x40000000` after the handoff.
