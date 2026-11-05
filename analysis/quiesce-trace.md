# Quiesce trace: what the park stops, and whether a trampoline can do it

Date: 2026-11-05 (night). Offline only; no camera access.
Follow-up to [updater-sram-startup-q1.md](updater-sram-startup-q1.md),
which eliminated the SRAM refresh as the direct handoff's
missing preparation. Remaining suspect: the mode-`0x88` park's
quiesce of the running firmware before the stub overwrites
`0x40000000`. Question: what does the park actually stop, what
does the handoff helper `FUN_401eb1ec` already stop, and could
the handler-hijack trampoline perform the missing quiesce
itself before calling the helper?

## What the park is

The park is an SP5K middleware mode switch (`sp5k_modesw.c`,
state struct at `0x40f80d28`). The callback `0x405da53c` and the
generic utility `FUN_4015bf04` both reduce to: if not already in
mode `0x88`, request the transition (`FUN_4018381c`: take the
mode resource mutex, build and post a message to the modeSw
task, clear the ready flag at state `+0x34`), then poll every
10 ms until the current mode is `0x88` and the modeSw task sets
the ready flag (`FUN_40183768`, ~20 s bound, assert-abort on
expiry). The transition itself — stopping and starting
middleware tasks and driver hooks per mode tables — executes
asynchronously in the modeSw task, not in the requester. The
callback's two extra prefix calls (`FUN_40205fc4(0)`,
`FUN_405e6fa0()`) are UI/state preparation, not the quiesce.

Consequence already proven live: driving this from the
handler-hijack context does not complete
([state6-hang-analysis.md](state6-hang-analysis.md) Addendum 4).
The mechanism needs a requester that can take mutexes, post to
a queue with 60 s timeout semantics, and sleep-poll — task
machinery. That state 6's own callback sometimes completes it
from the receiver task (2/3 updater) shows the difference is
context, not the function invoked.

## What the helper already stops

`FUN_401eb1ec` (indirect targets resolved from its literal
pool, `0x401eb3ac–0x401eb418`): logs its arguments, writes
platform configuration bytes (`FUN_40046ca0`, `FUN_40046c44`,
`FUN_40055e88`), performs cache/MMU preparation
(`FUN_4000b608(0x20000,0x20000)`, `FUN_4000186c` page-table
store + CP15 maintenance), copies the stub into the workspace,
executes `cpsid i`, disables the GIC via `FUN_40002374(0)`
(0xffffffff to the interrupt-disable registers), writes the
parameter block at `0x3f00d000`, and calls the stub.

It does **not**: stop or suspend any task, signal secondary
cores (no mailbox `0x100002f0` write, no SEV), stop any DMA
master (USB, SD, display, sensor), or wait for anything.

## The gap

In the parked state, middleware tasks are stopped; secondary
cores have no runnable work and sit in the OS idle loop. The
helper's GIC disable then freezes them there while the stub
DMA-copies the payload over the old text and the payload reset
later releases secondaries through its own mailbox protocol.

In the direct handoff, secondaries are actively executing old
firmware text across `0x40000000` while the stub's DMA engine
overwrites it, and bus-mastering peripherals remain armed.
Either can corrupt the payload or scribble through fault
handlers whose code is also being overwritten. This is
consistent with the observed signature: clean trigger, USB
teardown begun, payload never comes up, black screen.

## Can a trampoline quiesce instead?

Not credibly, on current evidence:

- The quiesce is not a function; it is an asynchronous,
  task-driven transition with mutex, queue, and sleep
  semantics. The hijack context demonstrably cannot drive even
  its synchronous front end (`FUN_4015bf04`, Addendum 4).
- No in-firmware "park secondary cores" primitive was found
  that handler code could invoke. An SGI would enter the old
  OS's IRQ handler and return to running tasks; there is no
  CPU-offline call on this path.
- A non-blocking queue-post to modeSw (skipping the mutex and
  the polling wait, checking the ready flag from later handler
  invocations) is conceivable but unbuilt: the message format
  is constructed inside `FUN_4018381c` under the resource
  mutex, and a mutex-free post has not been identified. Even
  built, it would only reproduce state 6's park while skipping
  op 10 — it does not create a new capability, and op 10 is
  not a proven blocker (Q1).

## Conclusion

The missing preparation is task-ecosystem quiesce, available
only through the modeSw service, which the hijack context
cannot drive and a trampoline cannot synthesize. The
trampoline-quiesce line is closed. The modified-firmware path
therefore rests entirely on state 6's own sequence — 2/3 for
the updater, 0/4 for the main image — with no identified way
to improve its odds from outside.

Residual unknowns: mode `0x88`'s exact stop list (modeSw
descriptor tables not traced to leaf hooks); whether parked
secondaries specifically idle in WFI; the precise roles of
the callback's two prefix functions. None of these changes
the conclusion, because the blocker is the driving context,
not the stop list's contents.
