# Why `0x04f7` handoffs hang intermittently: bounded analysis

Date: 2026-11-05. Offline follow-up to the live record in
[ram-push-live.md](ram-push-live.md) and the dual-boot/main-push
attempts. Live tally at time of writing: **2 of 6 handoffs
completed** (both stock-updater payloads); 4 stalled in state 6
with the USB control path dead, the device still enumerated, and
recovery by OFF/unplug or battery pull. No storage was ever
written. This pass was time-boxed to two questions.

## Q1: Is the multi-core race the blocker?

The SoC is multi-core and the OS uses the extra cores — that part
is certain (reset spin-table on MPIDR at `0x40000ec0`, per-core
stacks, secondary entry at image `+0x60` = `0x4000148c`, an
SEV-release retry loop in OS code at `0x4030127c`, and the fault
handler's `CORE#0 DEAD` diagnostic).

But the race theory weakened under inspection:

- The updater's reset code handles secondaries **identically** to
  the main image's (same MPIDR test, same `0x100002f0` WFE mailbox,
  same release helper shape; updater `0x40000340`). Reset-level
  core handling therefore cannot explain a payload difference.
- In all four hangs the USB never dropped, i.e. the stub never
  jumped — the payload never ran. The apparent payload correlation
  (updater 2/3, main image 0/3) is small-sample and may be noise;
  it must not be read as "the main image crashes on entry" without
  a completed handoff to observe.

Verdict: cores are real; the race remains unproven and is no longer
the leading explanation.

## Q2: What does the mode-`0x88` park (and state 6 generally) do?

The preparation callback's park is a mode-manager transition with
**three timeout-abort points**, each ending in the
assert-then-abort pair (`FUN_400070d8` + `FUN_40007148`):

| Step | Function | Timeout |
| --- | --- | --- |
| Acquire mode resource | `FUN_4018323c` → `FUN_4002db24` | 30 s |
| Post transition request | `FUN_401833f8` → `FUN_4002a3c4` | 60 s |
| Wait for mode + ready flag `+0x34` | `FUN_40183768` poll @10 ms | ~20 s (2000 polls) |

If any expires, the calling task — the USB receiver task — dies
silently: control path dead, enumeration frozen, no drop. That
matches the park-on hangs exactly. With the park skipped
(callback zeroed, live-verified), two further hangs occurred, so
state 6 has more fragile points downstream: the storage-interface
header read (op 10, driver-mutex territory) and the helper's own
early acquisitions (resource 17 in `FUN_401eb1ec`). Which one fired
in any given attempt is **unknowable after the fact** — the task
dies without a trace we can read.

Common theme: state 6's preparation chain contains multiple
state-dependent waits that can kill the only task that could tell
us what happened. Intermittency is system state at push time.

## Engineering conclusion

Stop trying to make state 6 reliable; **remove its preparation
chain from the path**. The proven primitives suffice to invoke the
handoff helper `FUN_401eb1ec` directly via the handler hijack:

1. bootquery-alloc + download + read-back verify (all proven);
2. optionally refresh the SRAM boot block — *not* by replicating
   op 10 (it constructs the block; the first 16 KiB of storage is
   not equivalent), so initially keep the state-6 header refresh
   out and treat SRAM state as a controlled variable;
3. a call-trampoline (new; emulator-qualify the mechanics:
   arguments in r0–r3 + stack, target reached, handler forwarded
   when idle) that invokes the helper with
   `(A, B, size, 0x40000000, 2)`.

What this bypasses: the mode park (all three aborts), the op-10
storage read, and the receiver's state machine. What remains on
the path: the helper's own preparation (register/resource ops the
vendor flow also performs) and the stub — both payload-independent
and on the designed critical path.

Operational rule learned: a handoff that will complete does so in
≤60 s; after the abort timeouts nothing further can happen. Future
live attempts (if any) should declare a hang at 90 s instead of
waiting 6–8 minutes.

## Status / next

- **Direct-helper path built (2026-11-05):** trampoline v4
  (copy + direct-call modes) passed emulator qualification
  ([output/trampoline4-emulator.txt](output/trampoline4-emulator.txt),
  `scripts/ReproduceTrampoline4.java`), and the client gained
  `--direct-handoff` (alloc → download → read-back verify →
  arm v4 with `(A, B, size, 0x40000000, 2)` → one bootquery
  trigger). First live use should be the stock updater payload,
  then the main image. Live pushes remain paused until that run
  is explicitly approved.
- The updater-payload path (state 6, park on) remains the only
  proven route and succeeded 2 of 3 times; it is available if a
  one-off need justifies the coin flip, with the 90 s rule.
