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
- The original inference was that in all four hangs the USB never
  dropped, i.e. the stub never jumped and the payload never ran.
  **Corrected 2026-11-06:** that inference is weaker than the
  original wording. The host-side observer logs for the later
  main-image pushes
  (`output/usb-startup/main-push-1.jsonl`, `main-push-3.jsonl`)
  show termination/arrival events at 15–20 s after the push.
  They may be VM passthrough events, but the interface count and
  descriptor after the event were never captured. Treat the
  main-image 0/4 as "did not come up", not as "never ran"; the
  prep-hang and post-handoff-crash explanations are not yet
  separated. The apparent payload correlation (updater 2/3,
  main image 0/3) is small-sample and may be noise.

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

## Addendum (2026-11-05, later): transport fragility in the client sequence

The first `--direct-handoff` live attempts exposed a *separate,
earlier* failure that cost several boots: after a
`bootquery-alloc`, a small number of small bulk-OUT writes
(trampoline/control-block writes via `0x04f5`) leaves the next
control setup unanswered (timeout, once an immediate EP0 stall).
The identical write pattern without a preceding alloc — and the
alloc → bulk-download → bulk-readback flow itself (ram-push) —
are both proven reliable over many sessions. Bisects narrowed it
to the alloc + small-writes combination; the exact receiver-side
mechanism was not isolated, and in-boot retries after a stall
make it worse (dangling bulk state), so **one attempt per boot**
is now a hard rule for this client family.

Mitigation (built, not yet run live): `--direct-handoff` gains a
fixed-staging mode that never calls `bootquery-alloc`: payload
staging at `0x41130000` and helper workspace at `0x411E0000`,
both inside the verified 2.4 MiB scratch gap (updater payload
666 KiB fits; the 14.9 MB main image still needs the alloc path
later). Sequence uses only never-failed patterns in order:
install trampoline (small writes) → bulk download → bulk
read-back → one arm write → one trigger.

## Addendum 2 (2026-11-05, evening): root cause of the "transport
fragility" — a truncated trampoline in the client

The direct-handoff deaths were **not** transport fragility.
usbmon ground truth (failing run): every transfer completed
cleanly, including the slot-patch write; the *next* setup was
simply never answered. Diffing the client's embedded
`TRAMP4_HEX` against the emulator-qualified binary showed the
client copy was **188 bytes, missing one instruction word**
(`cmp r1, #0` at offset `0x18`, dropped when the hex was split
into string chunks by hand). Every later instruction therefore
executed 4 bytes early with stale pc-relative offsets: the
trampoline loaded its "control block" pointer from the helper's
address literal, incremented a word of the real helper's code on
each invocation, and branched on garbage — killing the USB task
on the first setup dispatched through a patched slot. The
emulator had validated the correct `.bin`; the client's embedded
copy was never compared. (The identical transcription method had
produced a correct `TRAMP3_HEX` — verified intact — which is why
stage sessions never failed.)

Fix: embedded bytes regenerated from the qualified binary,
verified byte-identical by SHA-256, and the client now refuses
to write the trampoline unless its embedded copy matches the
recorded hash (`dec0561`). The alloc/timeout/VM-degradation
theories from earlier today are all **retracted** — the sequence
is sound; one boot's earlier 4-byte-read stall remains a minor
open nit, nothing more.

## Addendum 3 (2026-11-05, evening): direct handoff reaches the
helper; payload doesn't come up

With the corrected trampoline, `--direct-handoff --fixed-staging`
ran cleanly end to end: install, download, read-back verified,
armed, trigger — and the **device dropped off USB at the
trigger**, i.e. the helper executed its teardown. But the
updater never re-enumerated (P2 took ~60 s; we waited >4 min)
and the screen went black (in P2 the stale debug framebuffer
persisted). So the helper was reached in a state where the
payload couldn't complete startup. The two state-6 steps the
direct path deliberately skipped are the suspects, in order:

1. **Mode park (callback).** Unparked tasks/cores of the old
   firmware are still live when the payload starts; the
   updater's storage/task init may conflict with them.
2. **SRAM boot-block refresh (op 10).** Runtime-mutated SRAM
   boot structures are what the payload's startup parses.

Next design (offline first): park via the APITEST shell — a
mode-switch issued from the Cmd task, where the transition
cannot deadlock against the USB receiver — then direct-trigger
in the same boot. No live attempts until that is designed and
its mode-switch command is identified in the shell tables.

## Addendum 4 (2026-11-05, evening): park from USB context is a
dead end (live result)

Trampoline v5 (call target from control block; emulator PASS,
`scripts/ReproduceTrampoline5.java`) invoked the clean park
utility `FUN_4015bf04` from the handler-hijack context. The
trigger setup was accepted; control then stayed dead for 80+ s
(park's own worst case is ~50 s of timeouts) and the device
needed power recovery. **A mode-`0x88` transition cannot be
driven from the USB receiver task** — it deadlocks against that
task or aborts it. This retroactively explains part of the
state-6 flakiness: its preparation callback runs in exactly
this context, so its success depends on whether the transition
happens to complete without needing the receiver task.

Remaining park lever: issue a mode switch from the **Cmd shell
task** (where `rcaraw`'s `0x11` switches demonstrably complete),
then push. Whether the right switch is a `clbt`-family command
or a mode change that merely exits calibration state is an
offline question. Live pushes stay paused until that design
exists on paper.

Checked: the `clbt` factory command (`0x40522ba8`) has no
mode/idle subcommand — its subcommands are calibration-data
operations (`aflockdump afctrl info barcodeset boxsnset entest
distest boottype usbmode modeltypeset colddata dbgbufget
dbgbufset adc usbid usb`), several of which write persistent
data. CDSP shell mode switches exist only as capture side
effects (`snap`/`rcaraw` to `0x11`/`0x13`), and the camera
remains in the calibration personality afterwards. **No
shell-issued park has been found.** The park therefore remains
available only through state 6's own callback, with its
observed intermittency.

## Addendum 5 (2026-11-05, night): main-image state-6 push, 0/4

One fresh-boot `--ram-push` of stock `firmware-main.bin`
(14,910,464 bytes, SHA-256 `80350bd2…`), park left on, no
`--skip-prep`, no direct handoff. Alloc at `0x45cc2cc0`,
read-back matched, `0x04f7` setup accepted (12 bytes). USB
never dropped. Control was dead on a bootquery probe past 90 s
(`USBTimeoutError`), device still enumerated as `2aad:6500`.
Same signature as the prior three: the stub never jumped, so
the payload never ran. **Corrected 2026-11-06:** the "never
jumped" part is an inference from enumeration and a dead control
path, not a measured payload entry. The observer log for this
push shows a 15 s termination/arrival; the post-event descriptor
was not captured. Keep the result as "did not come up". The screen stayed on the debug list and
never changed; normal boot was confirmed after OFF/unplug.
Main-image state 6 is now **0/4**;

## Addendum 6 (2026-11-05, night): the SRAM refresh is not the
updater's missing preparation (offline)

[updater-sram-startup-q1.md](updater-sram-startup-q1.md):
updater startup reads nothing data-dependent from the
op-10 refreshed range `0x3f000000–0x3f004000` when the gate is
stamped (the only reader, the DRAM-parameter restore in
`FUN_4000125c`, is gate-skipped; all other `0x3f000200` sites
are post-startup programming machinery). Replicating the
refresh (former Option C) is therefore **closed**. The
direct-handoff failure is left attributable to the park's
quiesce — unreachable from every available context — or to
unisolated helper-context differences.

## Addendum 7 (2026-11-05, night): quiesce traced; trampoline
replication not credible (offline)

[quiesce-trace.md](quiesce-trace.md): the park is an SP5K
modeSw task-driven transition (mutex + queue post + sleep
poll; ready flag at `0x40f80d28+0x34`). The helper already
does config bytes, cache/MMU preparation, `cpsid i`, and GIC
disable — but stops no task, core, or DMA master. The gap is
secondaries executing old text (and armed DMA) while the stub
overwrites `0x40000000`. A handler-context trampoline cannot
drive the modeSw machinery (proven live, Addendum 4) and no
in-firmware core-park primitive exists for it to invoke. The
trampoline-quiesce line is **closed**; state 6's own sequence
remains the only path, at its observed odds.
updater on the same path remains **2/3**. A fourth silent hang
does not isolate which preparation abort fired. No retry in
that boot.

## Addendum 8 (2026-11-06): post-boot USB attach attempt hung

One fresh-boot updater push with the mailbox verified and the card
in place. The camera had been attached to the VM with
`utmctl usb connect` **after** it was already in debug mode. The
payload downloaded and read back clean; `0x04f7` was accepted; no
drop occurred, and a bootquery probe past 90 s timed out with the
device still enumerated as `2aad:6500`. The updater never ran, so
the read-only updater probe was not reachable. Tally is now
updater 2/6, main image 0/4.

This is a data point, not an isolation: the attach order is a
candidate variable because the 19:31 successful run likely had the
passthrough attached before/at boot, while this attempt attached a
already-running loader. The next attempt should attach the
passthrough before entering debug mode and keep it attached through
the push.
