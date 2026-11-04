# Handler-pointer hijack live: scratch code executed by the loader

Date: 2026-11-04. First code execution on the camera. Loader entry via the
established `EnableTest.txt` + MOVIE/flash-ON/PLAY-hold route; all traffic
from `analysis/xhalf_loader_linux.py` (`--hijack-test`) inside the UTM/Linux
passthrough VM, usbmon-captured. The trampoline's semantics were proven
beforehand in the ARM emulator
([output/trampoline-check.txt](output/trampoline-check.txt),
`scripts/ReproduceTrampoline.java`): counter increment, `r0`–`r3` restored,
balanced stack, control reaching the original handler.

## Design

- Scratch (from the [dump mining](dram-dump-mining.md) zero gap):
  trampoline at `0x41100040`, counter at `0x41100100`.
- Trampoline (40 B ARM): push `r0,r1`; increment the counter word;
  pop `r0,r1`; `bx` to the original handler `0x401e1280`. It preserves
  all handler arguments and returns to the handler's own caller via the
  handler's normal return.
- Targets: the two target-0 handler slots holding `0x401e1280` in the USB
  callback table (column-major layout from `0x401ba258`: categories
  `+0x2c` at `0x40fc7ba4` and `+0x54` at `0x40fc7bcc`), patched by
  read-modify-write of their aligned 32-byte blocks — the only
  non-scratch addresses the client's hijack mode may write, hardcoded.
- Trigger: ordinary boot queries. Restore is in a `finally` block and
  verified by read-back before the mode exits.

## Result

- Slots verified as `0x401e1280`, patched, two boot queries issued:
  **counter = 9**. The running loader dispatched its setup handler into
  scratch-resident code roughly 4–5 times per control transfer (phases),
  each time continuing into the real handler — boot queries kept working
  while the hook was installed.
- Slots restored and read-back-verified as originals. Counter rose by 4
  more during the immediate verification query (to **13**), then froze:
  the CPU kept dispatching through the patched pointer value briefly
  after DRAM restoration (cache-line retention), then reverted to the
  original handler. A full post-test DRAM dump
  (SHA-256 `80641cfd8f32cbc5e026013e7144953c0c4881c5d68497d28615317ca5575829`,
  outside git) confirms: slot words = `0x401e1280`, counter = 13,
  **no mirrored copy of the trampoline address anywhere in DRAM** — the
  lag was cache, not a second table.
- Total trampoline executions: **13**, with zero observed malfunction.
  All changes were DRAM-only; session ended by power-off, card markers
  removed, camera returned to normal use.

## Meaning

- **Execution primitive proven (R4 groundwork)**: arbitrary (here trivial)
  code can run inside the live loader environment without replacing it,
  without the `0x04f7` destructive handoff, and without touching the
  unreadable low helper `0x00000b34`. The hook is reversible in DRAM
  (with a brief cache-lag caveat: assume a restored pointer may keep
  firing for a few transfers; the trampoline must remain valid and
  forwarding until well after restore, and scratch must not be reused
  until a counter freeze is observed).
- The handler fires per USB phase, so any host request is a reliable,
  host-triggered execution context with the handler's arguments
  (`r0` = setup packet pointer) available to a future payload.
- Next design space: a scratch routine that does useful read-only work
  in handler context (e.g. copy SRAM/low-memory bytes into a DRAM
  staging buffer for `0x04f2` readout — the CPU can read what the DMA
  sender cannot), still forwarding to the original handler. That would
  close R2 with hardware-true bytes. Cache maintenance and task-context
  limits (this runs in USB dispatch context) must be designed for.
