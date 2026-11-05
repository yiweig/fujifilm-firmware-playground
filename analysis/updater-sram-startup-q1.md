# Q1: does updater startup read the op-10 refreshed SRAM range?

Date: 2026-11-05 (night). Offline only; no camera access.
Question from the bounded sprint after
[state6-hang-analysis.md](state6-hang-analysis.md) Addenda 3–5:
the direct handoff skipped both the mode-`0x88` park and the
op-10 SRAM boot-block refresh (16 KiB copied to `0x3f000000` at
`FUN_401e1f40` line 24 of
[output/handoff-state6-decomp.txt](output/handoff-state6-decomp.txt)).
If updater startup data-dependently reads that range, replicating
the refresh could revive the direct path. If not, that line is
dead and the park is the only missing preparation.

Target: `analysis/output/card-updater-full.bin`, SHA-256
`4bdcdb06…`, base `0x40000000`. Captured runtime SRAM:
`/private/tmp/xhalf-sram-2026-11-04.bin` (SHA-256 `62e05564…`),
taken in debug mode via the stage-copy trampoline
([loader-stage-copy-live.md](loader-stage-copy-live.md)).

## Method

- Whole-image scan for every aligned word in
  `0x3f000000–0x3f010000` (literal pools and data pointers), plus
  `movw/movt`-formed SRAM addresses in the full disassembly.
- Disassembly of the reset path `0x40000340`, C-runtime entry
  `0x400013cc`, page-table builder `0x400005c4`, and the
  `+0x200` gate stamper `0x40000200`.
- Existing decompilations of `FUN_4000125c` / `FUN_400009c4`
  ([output/updater-boot-consumer-decomp.txt](output/updater-boot-consumer-decomp.txt))
  and classification of every `0x3f000200` call site by caller
  (Ghidra 12.2, project `UpdaterQ2` in `/private/tmp`).

## Findings

1. **The updater image contains no reference to `0x3f000000`
   itself** and exactly one data address inside the refreshed
   range: `0x3f000200` (the DRAM-parameter block), at 10 sites.
   The only other low-SRAM values are the page-table constants
   `0x3f00404a/4000/43fc/4410/4fbc/4ffc`, stack top
   `sp = 0x3f010000` (`movt` at `0x40000348`), the handoff block
   `0x3f00d000`, and two float constants (`0x3f0000f8`,
   `0x3f001000` in a data table — not addresses).

2. **The sole startup-path reader of the refreshed range is the
   DRAM-parameter restore in `FUN_4000125c`**, and it runs only
   when the gate word at `0x10001300` is zero. The handoff stub
   calls the payload `+0x200` entry first, which stamps
   `0x88888888` there (`0x40000238–0x40000244`), then jumps to
   the reset vector — so on any handoff, stamped or direct, the
   restore is skipped. The emulator controls for exactly this
   branch already passed (gate nonzero → copied=false;
   [output/updater-dram-restore.txt](output/updater-dram-restore.txt)).

3. **Everything else startup touches is outside the refresh
   range or write-only.** The page-table builder
   (`0x400005c4`) stores literal templates at `0x3f004000+` —
   at/after the refresh end `0x3f004000`, never read back. Stacks
   sit at `0x3f010000` downward. The boot-register bytes the
   consumer decodes (`FUN_400009c4`) are at peripheral
   `0x10001000+0xb4..` — SoC register space
   ([memory-and-hardware-map.md](memory-and-hardware-map.md)),
   not SRAM, and untouched by the refresh.

4. **All other `0x3f000200` sites are programming/service
   machinery**, reachable only after the updater is already up:
   the SD and SPI ISP file-programming handlers
   (`FUN_40005b64`, `FUN_4000b594`), the NAND header builder
   (`FUN_40025480`, via `FUN_4006811c` record parse), the storage
   interface dispatcher (`FUN_40012d70`, ops 7/`0x1c`), a
   parameter snapshot helper (`0x40068478`), and handoff staging
   writes (`0x40011d84`). None is callable before the USB/service
   continuation `FUN_40069374`.

5. **Dump comparison (for completeness):** at debug runtime,
   SRAM `0x200` holds main-image early code, not `DRAMPAR3` —
   dump bytes `[0, 0x674)` equal the main image slice at
   `0x40e37c00` except the first word. Pages `0x1000` are zero;
   `0x2000`/`0x3000` hold only a `-CPI` signature at `+4`. This
   state is what the direct handoff left in place, and per
   findings 2–4 the updater startup never reads it.

## Conclusion

**The op-10 refresh is not the updater's missing preparation.**
Replicating its 16 KiB construction (the former "Option C")
would not be expected to change the direct-handoff outcome;
that line is closed without a camera session. By elimination,
the direct-handoff failure is attributable to the other skipped
step — the park's quiesce of the running firmware (tasks,
secondary cores, DMA) before the stub overwrites `0x40000000` —
or to helper-context differences not yet isolated. The park
remains unreachable from every context we have (USB task:
deadlocks; shell: no such command).

Boundaries: this is static analysis plus the earlier emulator
controls; indirect function-pointer calls inside
`FUN_401eb1ec` are unresolved, and a computed SRAM base formed
by arithmetic from a non-SRAM literal cannot be excluded by the
scan alone, though no such pattern was found. The main image's
own startup SRAM consumption (the eventual payload) is a
separate question — its only `0x3f000200` references are a
snapshot helper (`0x4000df48`) and state 6's refresh itself —
and is not answered here.
