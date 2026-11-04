# Staged copy live: full SRAM obtained, low-memory copy wedges

Date: 2026-11-04. Trampoline v2 (emulator-verified,
`scripts/ReproduceTrampoline.java` family; v2 output retained with the
session notes) installed through the established handler slots. It counts
invocations, and on host command copies a source region into a DRAM
staging buffer, then forwards to the original handler `0x401e1280`:

- CMD=1: SRAM `0x3f000000..0x3f010000` → staging `0x41110000`
- CMD=2: low `0x00000b00..0x00000c00` → staging `0x41121000`

Staging order was deliberate: SRAM (expected safe) first and saved to the
host before the riskier low copy was armed.

## Result

- **SRAM copy succeeded.** `sram-staged.bin`, 65,536 bytes, SHA-256
  `62e05564a19ae3068f75276a1e545bf8c621b3757cc1fecfbb5a9d33833da22c`
  (VM + `/private/tmp/xhalf-sram-2026-11-04.bin`). Contents are genuine:
  MMU descriptor patterns at the base, DRAM-parameter marker `PAR3DRAMd1`
  at `0x3f000484`, an `SPBoot>` header at `0x3f00c000` with register-init
  tables, and `iCatch  CA6500A` at `0x3f00c10d`.
- The 36-byte earlier-stage descriptor is now in hand at SRAM
  `0x3f00c518`: words `00dc0098 00019026 02001000 00080080 00400005
  00000800 00040000 00004654 0000421c` (`+0x18 = 0x40000` matches the NAND
  block size from the [slot-layout dump](dram-dump-mining.md)).
- **Low copy wedged the camera.** After CMD=2 was armed, the triggering
  boot query timed out at control level; slot restore also timed out.
  CPU data reads starting at `0x00000b00` fault or spin in this context
  (the abort path does not return usefully). As designed, no data was
  lost: SRAM was already on the host. Recovery was battery removal —
  with the firmware hung, the software power switch cannot act (see
  [write-test session](loader-write-test-live.md) for the healthy case).
  Card markers were removed afterwards; the camera returned to normal.

## Interpretation

- The CPU-in-dispatch-context copy technique works for SRAM and is the
  right tool for regions the DMA sender cannot source. It produced the
  first SRAM image of this device without opening it.
- Low memory (`0xb00` window) is not safely readable this way either.
  The fault may be in the vector/low hole before `0xb34`, or in reading
  that region as data at all. A narrower attempt at exactly
  `0xb34` (a few words) is possible but has the same battery-pull cost
  per failure and is not on the critical path: the hijack execution
  primitive does not need the helper's bytes, and only the avoidable
  `0x04f7` handoff calls it.
- R2 is therefore narrowed to "helper implementation not recovered; all
  other earlier-stage artifacts (descriptor, SPBoot SRAM state, slot
  layout) now in hand" rather than fully closed.
