# Playback cleanup before idle: readiness, task closure and ignored errors

Date: 2026-11-03. Offline original ARM inspection and 16 local Ghidra controls
of the 2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. Every address below belongs to MAIN, not updater. No camera
access or firmware modification; no new REA or live observation is claimed.

## Finding

The [mode-exit audit](main-sensor-and-mode-exits.md) identified shared cleanup
`0x401a519c` as the next boundary for candidate modes `0x41` / `0x42` toward
idle. Its first helper `0x401d8648(1)` waits for five playback states, then
closes five task descriptors whose actual names are `PBCTRL`, `MULTIPB`,
`QVLOAD`, `READFILE` and `DECODE`. Diagnostic strings include `pbTaskClose`
and source filename `pb_taskinit.c`, supporting a playback-task cleanup identity.

The individual close helper can return an error, but the five-task caller
ignores those results and returns zero. In a modeled QVLOAD delete-error case,
its pointer remains nonzero, yet the outer shared cleanup continues through
other resource releases and also returns zero. This is an identified software
error-propagation limitation, not an observed live failure, active-task proof,
use-after-free finding or permanent-brick demonstration.

No direct flash-write or USB-resource operation is identified in the inspected
outer cleanup bodies. The low termination routine can invoke a runtime hook,
and kernel/object-release implementations have wider effects than the modeled
calls. Therefore absence of persistent writes or USB effects across the full
path is still unresolved. The result does not qualify safe RAM execution or
independent recovery.

Follow-up: [termination synchronization hooks](termination-synchronization-hooks.md)
identifies the global hook as owned-mutex release and two per-task callbacks as
queue/event wait cleanup. Ten controls stop before scheduler/resume work; actual
runtime state and concurrent side effects remain unobserved.

## Five readiness conditions

Predicate `0x401deb70` calls five literal-address getters and compares word
+8 of each returned state object. It returns one only if all five match:

| Getter | State address | Required word +8 |
| --- | --- | --- |
| `0x401d3e1c` | `0x40fd06f4` | `0x502` |
| `0x401b6188` | `0x40fc781c` | `0x900` |
| `0x401d6418` | `0x40fd0758` | `0x602` |
| `0x401d6ac8` | `0x40fd078c` | `0x800` |
| `0x401d6e10` | `0x40fd07ac` | `0x700` |

A failed comparison returns zero immediately. These constants are required
states, not inferred status names or measured runtime values.

Batch closer `0x401d8648` with argument one loops on that predicate, calling
`0x4000c450(10)` while it is false. This loop has no timeout counter in the
inspected body; delay units and scheduling progress remain unverified. With
argument zero, a false predicate returns one immediately. A true predicate
reaches the same close sequence for either argument.

## Five descriptors and close behavior

The caller fetches these descriptors through literal-address getters, in order:

| Descriptor | Actual name | Getter |
| --- | --- | --- |
| `0x40d79cf0` | `PBCTRL` | `0x401e6870` |
| `0x40d79d14` | `MULTIPB` | `0x401e689c` |
| `0x40d79d38` | `QVLOAD` | `0x401e68c8` |
| `0x40d79d5c` | `READFILE` | `0x401e68f4` |
| `0x40d79d80` | `DECODE` | `0x401e6924` |

Descriptor +0 is a numeric ID, +4 the runtime task allocation pointer, +8 a
name pointer and +0x0c an entry pointer. The packaged zero at +4 is not a
measurement of a running camera's pointer. These records contain live RAM data.

Individual closer `0x401d8480(descriptor)`:

1. Loads descriptor+4 and calls task termination `0x40026e38`.
2. Reloads descriptor+4 and calls task deletion `0x40026ca0`.
3. If deletion returns nonzero, logs a failure, retains the pointer and returns
   `descriptor.ID | 0x87000200`.
4. If deletion returns zero and the pointer is nonzero, frees the allocation
   via `0x40020ecc`, clears descriptor+4 and returns zero.
5. If deletion returns zero but the pointer is null, logs that case and returns
   the same encoded error. The termination/delete calls precede this null check;
   this is not a verified safe null-pointer invocation recipe.

The termination result is not checked. The low implementations manipulate task
state/list fields; the termination/delete labels are inferred from those bodies,
the task-creation counterpart and the explicit `pbTaskClose` context. They are
not recovered source symbols.

Between successive calls to this closer, `0x401d8648` performs no test of the
return register and no aggregation of failures. After the fifth call it explicitly
sets r0 to zero and returns. A successful batch return therefore does not establish
successful closure of all five tasks.

## Outer cleanup continues after modeled failure

Outer routine `0x401a519c` calls the waiting batch closer, then:

- If object `0x40d74284 + 0x18` is nonzero, releases its +0x154 handle,
  frees the object and clears +0x18/+0x1c.
- Calls `0x401d46bc`, which conditionally releases handles at `0x40fd05d8`
  +0/+4, frees a pointer at +0x10c, and clears associated fields.
- Calls `0x401b61e8`, `0x401d6940`, `0x401d6cc4` and `0x401d8438`,
  whose inspected bodies conditionally call object release `0x400364c0`.
  The `0x401d6940` body also clears its handle field.
- Calls `0x401b6844` to conditionally free/clear a RAM pointer at
  `0x40fc7800 + 0x58`.
- Conditionally frees/clears `0x40fa5894 + 0`, clears its +4 word and
  byte `0x40d74284 + 0x280`, then returns zero.

These bodies narrow the work to playback tasks and associated RAM/kernel
objects. Calls to `0x400364c0`, `0x4002a444` and `0x40020ecc` are modeled
in the controls; actual scheduler/heap behavior and any side effects from other
running tasks are outside them. Static inspection of `0x400364c0` shows a kernel
object-delete call and conditional allocation free; it is not an arbitrary
per-camera storage-save callback in that wrapper.

In the failure-injection control, deletion of the third task returns 17. The
individual closer logs and returns `0x87000203`; the batch ignores this, closes
the remaining tasks and returns zero. The enclosing routine then reaches its
remaining releases/frees and final zero return. This does not establish that a
real failed deletion leaves a task running: termination is separately modeled
as returning success, and no scheduler or worker executes.

## Runtime hook in low termination remains open

Main task terminator `0x40026e38` first calls `0x40003fec(task)`, then reads
pointer `0x40e3811c`. If nonzero, it calls that pointer with the task argument
at `0x40026e58`. The actual hook value and its producers have not been resolved
in this pass. The kernel body later has another indirect call through task+0x68
on a conditional branch; that callback and the branch conditions also need audit.

The small initial helper checks task+0x90. A zero returns immediately; a nonzero
can change interrupt/FPU-related state and is outside the executed prefix controls.
The prefix controls supply zero there, and stop either at a supplied hook entry
or at `0x40026e5c`, before the privileged kernel body. They do not establish
what a real runtime hook does or that it is null after startup.

## Sixteen bounded original-instruction controls

[InspectPlaybackCleanup.java](scripts/InspectPlaybackCleanup.java) verifies
the main-image hash and supplies synthetic RAM:

| Controls | Original instruction coverage and checks |
| --- | --- |
| 6 readiness | All five required state words match, then one mismatch at each position; returns one/zero respectively |
| 2 individual closes | QVLOAD modeled delete return 0/17; checks zero versus encoded error, pointer clearing/retention and free call |
| 2 batch closes | All deletes modeled successful versus third delete returning 17; both call all five closers and return zero |
| 2 not-ready calls | Argument zero returns one; argument one stops before first delay with argument 10 |
| 2 outer cleanups | Empty optional resources with successful closes; supplied optional resources with third close failure; both complete original outer cleanup and return zero |
| 2 termination prefixes | Supplied hook zero/nonzero; stops before privileged kernel continuation or before hook execution |

All 16 pass. The readiness getters and descriptor getters execute original
instructions. Logging, task terminate/delete, allocator free and kernel object
release are modeled for the cleanup controls. Synthetic handles are opaque
sentinels; they are not valid live-camera resources or reconstructed heap state.
The separate termination-prefix controls execute original prefix instructions
and stop at the declared boundaries.

Loaded-image comparisons allow only expected task-pointer clearing and the
outer cleanup's declared RAM-data clears. Source binaries remain unchanged.
No real task termination/deletion, allocation release, hook body, privileged
continuation, playback worker, USB/NAND/SD, payload or camera executes. Modeled
error results establish caller behavior, not physical device failure behavior.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectPlaybackCleanup.java /private/tmp/playback-cleanup.txt
```

Evidence: [16 controls](output/playback-cleanup.txt) and
[hash-qualified original ranges, descriptors and literal strings](output/playback-cleanup-static.txt).
The existing ELF's embedded bytes were checked equal to the original main image;
literal pools remain data despite displayed instruction mnemonics.

## Remaining questions

The shared cleanup's purpose, readiness conditions, five-task close order and
failure propagation are now identified within the inspected path. We still lack
live state, complete termination-hook provenance/behavior, scheduling effects
and a full transitive storage/USB side-effect audit. The sensor-mode work and
idle entry's clock operations from earlier reports also remain separate gaps.

The next focused offline targets are the global termination hook's writers and
the task+0x68 callback; they can determine what termination adds beyond the
playback wrappers. A successful cleanup return alone must not be used as proof
of complete quiescence before a RAM payload. Camera reconnection is not needed.
