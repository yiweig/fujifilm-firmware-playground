# Loader initialization preserves the pre-handoff callback

Date: 2026-11-03. Offline original ARM and four local Ghidra controls of
2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. All routine addresses below belong to MAIN, not updater.
No camera access or firmware changes.

## Finding

Registering the loader handlers and running target-0 initialization does not
clear the optional pre-handoff callback in the inspected paths. Four controls
preserve supplied null/nonzero callback values through registration and two
successive initializations, including optional release of an old RAM buffer.

The slot also lies inside an already identified **stage-23 zero-fill request**
during full startup. Thus full startup intends to clear it, while a loader/USB
reinitialization must not be assumed to do so. Neither the actual hardware fill
nor the live callback value has been observed.

This qualifies the [calibration callback finding](main-handoff-optional-callback.md):
if that callback has been installed, the examined reset initialization can leave
it available for a subsequent handoff. It does not prove calibration startup
runs in ordinary operation, or that every mode transition preserves the slot.

## Registration touches a different callback table

Main startup calls wrapper `0x40234a80` at `0x400f888c`. It tail-calls loader
registration `0x401e2104`, which supplies initializer `0x401e122c` and receiver
`0x401e1280` to callback-array setter `0x401ba258`.

That setter writes the target callback arrays rooted at `0x40fc7b78`, including
target-0 initializer at +0 and receiver at +0x54. These are separate from
receiver object `0x40fd0d50` and its optional handoff callback at +0x40
(`0x40fd0d90`). Executing the complete registration wrapper and setter preserves
the latter in both null/nonzero fixtures.

## What the reset initializer resets

The complete small initializer `0x401e122c`:

1. Sets receiver state byte `0x40d79b21` to 2.
2. If receiver object word +0 is nonzero, passes it through release wrapper
   `0x401e0718` and clears it.
3. Clears receiver byte +4.
4. Calls cleanup `0x401c1314`.

The cleanup operates on a different object, `0x40fc8270`. Depending on its
state, it can run additional shutdown/release helpers, clear pointers and reset
flags. The new controls supply this auxiliary object empty, so they execute
the original empty-state cleanup branch without scheduling or resource operations.
Active-object branches and their callees are not classified as callback-preserving
by these controls.

No direct store in the inspected initializer or cleanup body clears
`0x40fd0d90`. The original initializer therefore preserves the supplied slot
under the tested auxiliary-state conditions, including repeated invocation.
Afterward the original state-6 optional-call branch still reaches the supplied
callback when nonzero, and skips it when null.

## Full startup requests a zero fill covering the slot

The earlier [staged RAM initialization investigation](staged-boot-ram-initialization.md)
identified hardware-fill requests and checked their original construction.
Its recorded stage-23 interval is:

```text
requested zero fill: [0x40e6a240, 0x40fd1140)
callback word:       [0x40fd0d90, 0x40fd0d94)
```

The entire callback word lies inside that interval. This containing stage is
23, distinct from stage 28 containing the unrelated optional PTP hook at
`0x4136b664`. The new annotation reuses the existing hash-qualified interval
artifact and asserts the containment; no hardware fill is executed or simulated
in this pass.

The earlier worker controls verified fill-request construction, not actual RAM
zeroing. Hardware completion failures and later writers remain separate limits.
A successful full startup is intended to begin with this slot zero, but that is
not a measured live value or a guarantee across every possible loader entry.

## Bounded writer search

A 256-instruction backward constant-resolution scan finds one resolved direct
store to this four-byte slot: setter `0x401e20f8`, which writes the caller's
supplied pointer. It finds no overlapping known memcpy/memset candidate.

This negative bulk result does not negate the positive hardware-fill finding.
The search omits complete control flow, Thumb, runtime pointers, unresolved
copies and hardware DMA. It is not an exhaustive writer inventory or proof of
no later clearing. This is the same distinction that made the earlier staged
RAM-fill discovery useful.

## Four original-instruction controls

[InspectMainLoaderCallbackLifetime.java](scripts/InspectMainLoaderCallbackLifetime.java)
combines callback 0/`0x405da53c` with old scratch pointer absent/present:

| Phase in each control | Checked behavior |
| --- | --- |
| Original registration wrapper and callback-array setter | Correct target-0 initializer/receiver installed; optional callback unchanged |
| Two original initializer calls | Receiver state 2, scratch pointer/byte cleared; optional callback unchanged |
| Scratch release | Exactly one modeled allocator release if initially present; none on second initialization |
| Original optional-call branch afterward | Nonzero callback reaches its entry and stops; null skips to `0x401e1f58` |

All four pass. The empty-state auxiliary cleanup executes original instructions;
interrupt-mask and allocator-release helpers are modeled. The initializer changes
one declared loaded RAM-data byte `0x40d79b21` to 2; all other loaded-image bytes
are checked unchanged. Source binaries are untouched.

These are direct serial invocations, not an entire USB reset or mode transition.
No complete startup, hardware zero fill, active cleanup branches, RTOS, callback
body, controller, NAND/SD, payload or camera executes.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectMainLoaderCallbackLifetime.java /private/tmp/main-loader-callback-lifetime.txt
```

Evidence: [four controls](output/main-loader-callback-lifetime.txt),
[main-qualified static ranges](output/main-loader-callback-lifetime-static.txt),
[fill containment and bounded store search](output/main-loader-callback-initialization.json).
No new REA or live observation is claimed.

The callback now has an identified registration path and a qualified lifetime:
full startup requests clearing its RAM, while the examined loader initialization
preserves it. Its ordinary live value and the deeper calibration callback's
storage/USB effects remain unverified. The next useful offline audit is the
callback's idle-mode transition and its possible persistent-storage operations.
Independent RAM entry and cold recovery are still unresolved; camera reconnection
is not needed for these findings.

Follow-up: [idle-mode preparation](main-idle-mode-handoff.md) traces the queued
transition, current-mode exit and conditional clock-switch dependency. Five
controls stop before sensor writes/delays; deeper side effects remain unresolved.
