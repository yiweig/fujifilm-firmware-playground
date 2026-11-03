# Idle clock preparation: callback API, display synchronization and failure paths

Date: 2026-11-03. Offline original ARM inspection and 15 local Ghidra controls
of the 2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. Every address below belongs to MAIN, not updater. No camera
access or firmware modification; no new REA or live observation is claimed.

## Finding

The [idle-entry audit](main-idle-mode-handoff.md) identified a conditional DRAM
clock call and optional callbacks. This pass identifies their registration API,
the clock wrapper's table gate, its display-event synchronization, and selected
return/error paths.

Mode API selector 6 writes the before/after callback pair. The clock wrapper can
wait for a display-related event before reaching hardware routine `0x4001e7e4`.
A failed wait reaches an assertion path; a returned hardware result goes through
restoration of the prior event callback and semaphore cleanup. The idle entry
separately treats result 1 as an assertion condition before the after-callback
or cached-clock store.

Fifteen bounded controls validate these software boundaries. Event handling,
semaphores and hardware-clock results are supplied models; the hardware routine
is either stopped at entry or modeled as returning. No physical clock change
or complete clock switch is demonstrated. Callback values on the camera and
the full hardware/core side effects remain unresolved. No new persistent write
is established by this pass, and a safe RAM launch is still unqualified.

## Before/after callback registration

The actual variadic API entry is `0x40183944`, which saves r0..r3 before its
second prologue at `0x40183948`. For selector 6:

```text
r0 = 6
r1 = before_callback
r2 = after_callback

40183a5c  store before_callback at 0x40f80d28 + 8
40183a60  store after_callback  at 0x40f80d28 + 0x0c
```

The branch locks/unlocks mode state through `0x4018323c` / `0x4018331c`.
Two controls execute the complete variadic entry and setter branch, installing
a supplied nonzero pair from zero and clearing a supplied nonzero pair.
Lock/unlock operations are modeled; these are emulator controls, not live API
instructions for the user.

A bounded 256-instruction constant-address scan identifies both stores.
[Writer evidence](output/idle-clock-callback-writers.json) retains its search
bounds and limitations. This finds the setter, not a concrete application
registration of real callback functions. Actual callback values and later writers
remain unknown. A narrow preceding-instruction search among aligned direct API
calls did not establish a selector-6 registration caller; computed/indirect
arguments and paths outside that search remain possible.

## Idle entry's guard and call order

Entry `0x40182e9c` reads requested value from mode state+4, cached/prior value
from +0, and current value through getter `0x4000dde8`. That getter reads
`0x40d5dda0 + 0x80`. The entry also makes the previously identified
`0x400d3acc` and conditional `0x400d3954(0)` calls before the clock guard;
their complete effects are not audited here.

The clock/callback sequence is skipped if requested is zero, equals the current
value, or equals the cached/prior value. These are exact guard conditions, not
a demonstrated live stale-cache problem. On the active branch:

1. Calls `0x4001d8f0(-1)` and logs preparation. Its exact guard/resource
   semantics are not qualified here.
2. Calls before-callback if nonzero, with `(cached/prior, requested)`.
3. Calls `0x401a7ca8(requested,1)`.
4. If result equals 1, reaches diagnostic/assertion helpers `0x400070d8` /
   `0x40007148`, with actual message `dramClk sw to %d fail`.
5. If control proceeds, conditionally calls `0x4001da60` using the earlier
   helper result, then calls after-callback if nonzero, with the same arguments.
6. Stores requested to mode state+0 and logs current values.

The before-callback precedes even the clock wrapper's table validation, so a
wrapper rejection must not be generalized to absence of earlier callback effects.
The after-callback precedes the cached-value update. Controls stop at each
callback boundary and check those arguments and the still-old cached word.
The after-boundary control models before-callback and hardware-core success;
it does not execute arbitrary callback code.

The result-1 control stops before assertion execution; it shows the after-call
and cached-word update have not happened at that boundary. It does not prove
assertion behavior, rollback, or recovery after an actual partial hardware change.
Other possible result codes are not classified as errors/successes here.

## Clock table gate

Wrapper `0x401a7ca8(requested,flags)` calls original lookup
`0x4000dd58(requested,2,&out)` before its event/core work. The lookup scans
eight 16-byte records at `0x40d5dda0`, checks record validity/type and the
requested value, and returns a table word or zero. A zero result makes the
wrapper return 1 without event or core calls.

The controls supply two synthetic records and clock values 100/200/400/999.
They are branch fixtures, **not supported X half clock settings or measured
frequencies**. The packaged table bytes are zero; the actual populated table and
current clock state on the camera have not been acquired. Original lookup/getter
instructions execute in the controls; table initialization is not emulated.

## Display-related synchronization and callback restoration

After passing the table gate, the wrapper reads modeled MMIO bytes:

| Condition | Selected event index |
| --- | --- |
| `0x1000a020 & 1` nonzero | 3 |
| Otherwise `0x1000a021 & 1` nonzero | 11 |
| Both clear | 0; synchronization branch skipped |

If flags bit 0 is clear, the synchronization branch is also skipped. The idle
caller supplies flags 1. Index 3 takes precedence if both register bits are set.
The actual event/edge interpretation is supported by diagnostic name
`dispVdFall`; physical timing and live register values remain unknown.

With synchronization enabled, the wrapper:

1. Creates a semaphore via `0x40036440(name,0)` and stores its handle at
   `0x40fa58f0`.
2. Removes/saves the selected event callback through `0x400bd9f8(index)`.
3. Installs `0x401a7c98` through `0x400bd860(index,callback)`.
4. Waits through `0x4003648c(handle,1000)`.
5. On zero wait result, invokes hardware core `0x4001e7e4(requested,flags)`.
6. If the core returns, removes the temporary event callback, restores the saved
   callback, deletes the semaphore through `0x400364c0`, clears its handle slot,
   and returns the core's result.

The temporary callback `0x401a7c98` loads that handle and tail-calls
`0x400364ac(handle)`, supporting a signal/post operation. It is a different
callback from the optional mode before/after pair.

The event remove/install bodies operate on callback table `0x40e68a24` and
MMIO bank `0x1000a000`, with conditional interrupt-registration work. They are
inspected statically and modeled in the controls; no actual event replacement
or interrupt handling executes. The wait's units and real completion behavior
are unverified.

A nonzero wait result reaches diagnostic/assertion work before the core call.
The failure control stops at `0x400070d8`, with the semaphore handle still in
its slot and no core call. This intermediate stop is not a proven semaphore
leak: assertion behavior and any continuation have not executed. Semaphore
creation failure and concurrent/reentrant calls are also not qualified.

With modeled core result 0 **or** 1, original wrapper instructions reach event
restoration, modeled semaphore deletion and slot clearing, then return the
supplied result. This validates returned-result cleanup order, not physical
rollback of clocks or correct behavior of real synchronization helpers.

## Hardware core remains a separate audit

Core `0x4001e7e4` checks the current value and looks up current/target parameters.
Its nontrivial branch contains MMIO stores in clock register bank `0x10000000`,
IRQ/guard calls and lower helpers including `0x400490e4` / `0x400491b4`.
Some lower helper state is addressed at `0x3f00e2c0`; their allocation/copy and
hardware behavior are not reduced to a safe clock-switch recipe in this pass.

These are hardware configuration operations relevant to RAM execution. They
are not themselves identified firmware-flash or per-camera calibration writes.
The exact clocks affected, copied helper data/code provenance, ordering
requirements, runtime parameter table, and all transitive side effects remain
open. No argument based on the wrapper controls can substitute for that audit.

## Fifteen bounded controls

[InspectIdleClockSwitch.java](scripts/InspectIdleClockSwitch.java) checks the
main-image hash and combines:

- Two original API setter controls: install and clear the callback pair.
- Three idle guard controls: requested zero, cached value, or current value.
- Two idle callback stop controls: before and after, with exact arguments and
  no cached-clock commit before the after callback.
- One table-rejection control: missing synthetic target returns 1.
- Three core-entry stops: no event branch, event index 3, or event index 11.
- One modeled wait failure: stops before assertion/core execution.
- Two modeled core results: both restore the saved event and clear the semaphore
  slot after modeled deletion.
- One idle modeled core result 1: stops before assertion, after-callback and
  cached-word update.

All 15 pass. Logging, mode locks, guard calls, event registration, semaphore
operations and selected hardware-core/callback results are modeled explicitly.
MMIO bytes are synthetic emulator state, not acquired hardware values.
Loaded-image bytes remain unchanged after fixture setup; source binaries are
untouched. No physical clock/IRQ/display operation, arbitrary callback, assertion,
core, USB/NAND/SD, payload or camera executes.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectIdleClockSwitch.java /private/tmp/idle-clock-switch.txt
python3 analysis/find_usb_flag_stores.py --window 256 --target 0x40f80d30 --size 8
```

Evidence: [15 controls](output/idle-clock-switch.txt),
[hash-qualified ARM ranges and actual strings](output/idle-clock-switch-static.txt),
[bounded callback-writer search](output/idle-clock-callback-writers.json).
The existing disassembly ELF payload matches the original main image. Literal
pools remain data despite displayed instruction mnemonics.

## Remaining work

Callback setter identity, wrapper guard/synchronization and selected failure
boundaries are answered within these paths and synthetic states. The actual
before/after functions, populated clock table, hardware core and concurrent
side effects remain unresolved. Earlier sensor, backlight and lens work also
remain separate handoff dependencies.

Next trace the lower clock core's parameter/table initialization and helper
backing. That can narrow the hardware requirements for a temporary RAM launch,
while independent cold recovery remains a separate unproved path. The camera
can stay unplugged.
