# Task termination hooks release mutexes and clean up waits

Date: 2026-11-03. Offline original ARM inspection and ten local Ghidra controls
of the 2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. All addresses below belong to MAIN, not updater. No camera
access or firmware changes; no new REA or live observation is claimed.

## Finding

The [playback cleanup audit](playback-cleanup-before-handoff.md) left two
termination callbacks unresolved. Both now have concrete kernel-related
producers, within the paths identified here:

- Mutex construction installs global hook `0x4008c1d8` at `0x40e3811c`.
  This hook walks the terminating task's owned-mutex list and calls mutex
  release. It can transfer ownership to a waiter and reach a task-resume routine.
- Queue-wait and event-wait paths install per-task callbacks at task+0x68.
  Two identified callbacks remove the task from the corresponding wait list.

Ten bounded controls verify registration, selected mutex-release cases, and
termination through those queue/event callbacks. They stop before scheduler or
resume helpers. The identified callbacks perform kernel synchronization work;
no direct NAND/SD write or USB-resource operation is identified in these inspected
branches. This does not classify every possible callback, inheritance branch,
concurrent task or later scheduler effect as write-free.

For RAM-handoff safety, closing playback tasks still does not establish global
quiescence: releasing their locks can make work in another task eligible to
proceed. That is an inference from ownership transfer and the resume call edge,
not an observed camera wakeup. Independent recovery and safe RAM entry remain
unqualified.

## Positive producer for the global hook

Mutex constructor `0x40079cdc(object,name,inheritance_option)` initializes a
52-byte object, writes magic `0x4d555445` (`MUTE`) and joins a global list.
At `0x40079d14` it writes the constant `0x4008c1d8` into `0x40e3811c`.
The guarded wrapper `0x40059290` tail-calls this constructor at `0x40059394`.
These are descriptive inferred roles, not recovered source symbol names.

The new controls execute the constructor with both an empty created-object list
and a one-object list. It installs the same hook even when the supplied previous
hook is nonzero and different. This shows an unconditional assignment in the
constructor, not that the camera has necessarily reached it at a particular
handoff or that no later routine can replace the hook.

The bounded 256-instruction writer scan identifies that store as one resolved
positive write to the slot. Exact pointer occurrences of `0x40e3811c` also
include its consumers in termination `0x40026e38` and task-return wrapper
`0x4004a06c`. In the latter, the task entry returns before the hook is called.
Thus this is a general task-lifecycle mechanism, not an identified camera-specific
firmware-launch callback.

The earlier [RAM initialization interval](staged-boot-ram-initialization.md)
records an initial CPU clear of `[0x40e37c00,0x40e68880)`. It contains the whole
hook word `[0x40e3811c,0x40e38120)`. That is intended clearing on the previously
identified loader-facing startup route, followed by the identified constructor
assignment when it executes. This reuses prior static evidence; it does not
measure live initialization, prove coverage of all boot routes, or establish
that the packaged bytes at this RAM address equal the eventual hook value.

The [writer-search record](output/termination-hook-writers.json) retains its
bounds and the CPU-clear containment annotation. The negative part is not an
exhaustive writer inventory: indirect/computed writes, unresolved copies,
hardware fills, Thumb and other omitted instruction forms remain possible.

## Owned-mutex release hook

Hook `0x4008c1d8(task)` increments global word `0x40e38118`, then loops on
`task+0x1ac`. For each mutex at that head:

1. Sets mutex+8 to one, collapsing its recursive acquisition count for release.
2. Calls mutex-release routine `0x40079ee0(mutex)`.
3. Reads the owned-list head again.

When the head becomes zero it decrements the global word and returns. The
release routine detaches mutexes from the owner list using task+0x1a8/+0x1ac
and mutex list links. There are additional priority/inheritance and waiter
branches; those are not all executed by the controls. If list state or release
behavior prevents progress, this loop has no separate iteration bound in the
inspected body. The emulator itself has a bounded instruction count.

Three hook controls supply:

| Supplied state | Original-instruction result |
| --- | --- |
| No owned mutex | Returns; global word restored to supplied value 7 |
| One owned mutex, count 7, no waiter, inheritance disabled | Forces count one, releases it, clears owner/head/count and returns; global word restored to 7 |
| One owned mutex and one waiter, inheritance disabled | Detaches old owner, assigns mutex to waiter, clears waiter's cleanup callback, then reaches `0x40049e20(waiter)` |

The contended case stops before `0x40049e20` executes and before the enclosing
hook returns. The supplied global word is 9 at that intermediate boundary;
this is not a leaked-counter finding because the rest of the path is unexecuted.
The task-resume interpretation follows the caller's wait-list removal/ownership
transfer and other kernel callers; no physical scheduling is observed.

No inherited-priority branch, multiple-owned-mutex scenario or concurrent list
mutation is qualified by these controls. The exact live ownership graph remains
unknown. Mutex release itself can invoke additional priority/scheduling helpers
on branches outside the executed cases.

## Per-task callbacks have wait-cleanup producers

Two concrete assignment paths:

| Producer slice | Stored callback at task+0x68 | Associated state |
| --- | --- | --- |
| Queue wait around `0x40057f28..0x40057fb4`, store `0x40057f50` | `0x40079740` | Writes task state 5, queue pointer at +0x6c and queue wait-list links |
| Event wait around `0x400587ec..0x40058870`, store `0x40058810` | `0x400797fc` | Writes task state 7, object pointer at +0x6c and event wait-list links |

The queue callback checks object magic `0x51554555`, callback nonzero, object
pointer nonzero and wait count. Its active branch clears task+0x68, decrements
the wait count and unlinks it. The event callback similarly checks magic
`0x4456444e`, clears the callback and removes the task from an event wait list.
These magic constants and data-flow edges support the kernel object roles;
no original source names are asserted.

The callbacks can also reach resume-related work when called while the task is
still in the corresponding wait state. During the inspected explicit-termination
path, `0x40026e38` has already changed that task's state to 2 before invoking
task+0x68 at `0x40026ecc`. In the two supplied cases, the callbacks therefore
unlink the wait without taking their state-5/state-7 resume branch.

Other task+0x68 assignments exist throughout kernel/object implementations.
These two positive examples are not an exhaustive callback inventory, nor proof
that playback tasks are always waiting on these particular object types.
Producers were inspected statically; the termination controls supply their
identified callback pointers and wait-list state directly.

## Explicit termination and scheduler boundaries

Five controls enter original `0x40026e38(task)` with initial task state
0, 1, 2, 5 or 7 and global hook set to the identified mutex-release hook.
Task+0x90 is supplied zero, so `0x40003fec` returns without its FPU/interrupt
cleanup branch. The embedded timer has no active list links, so original helper
`0x40057220` returns through its empty-state path.

- State 0 reaches `0x4004a180(task)` after state/counter updates and stops.
- States 1/2 bypass per-task cleanup and reach `0x40049fc8`, then stop.
- State 5 with the identified queue callback removes a supplied single queue
  wait; state 7 with the event callback removes a supplied single event wait.
  Both clear task+0x68, clear the object wait head/count and reach `0x40049fc8`.

The global hook executes original instructions in all five cases, with an empty
owned-mutex list. The scheduler routines themselves never execute. These controls
do not finish actual task termination or demonstrate successful task deletion;
the previous ignored-deletion-error finding still applies.

## Ten controls and explicit models

[InspectTerminationSynchronization.java](scripts/InspectTerminationSynchronization.java)
checks the main-image hash. It combines two constructor, three hook and five
termination controls described above. All ten pass.

MRS/CPSID/MSR instructions used for IRQ state are explicitly modeled: the saved
value is supplied zero, and masking/restoration are skipped. This avoids implying
real privileged execution or interrupt behavior. Arithmetic, pointer stores,
list operations, branches and included helper calls execute original instructions.
There is no scheduler, interrupt delivery, task worker or concurrency model.

Only declared loaded-RAM data changes are allowed: the hook assignment and
expected changes of `0x40e38118`. Synthetic object/task fields are outside the
loaded image; source binaries remain untouched. No actual IRQ/FPU operation,
scheduler/resume helper, USB/NAND/SD, payload or camera executes.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectTerminationSynchronization.java /private/tmp/termination-synchronization.txt
python3 analysis/find_usb_flag_stores.py --window 256 --target 0x40e3811c --size 4
```

Evidence: [ten controls](output/termination-synchronization.txt),
[hash-qualified original ranges](output/termination-synchronization-static.txt),
[bounded writer search and clear containment](output/termination-hook-writers.json).
The existing disassembly ELF's payload was verified equal to the original main;
literal pools remain data despite displayed instruction mnemonics.

## Remaining work

The identified global-hook producer and two per-task callback producers are
answered within static paths and supplied-state controls. Actual callback/list
contents, other wait types, priority inheritance and concurrent side effects
remain unresolved. The complete handoff still includes the earlier sensor-mode
and clock operations plus the outer backlight/lens work.

The next useful handoff-specific target is the idle clock-switch routine and its
registered callbacks. This pass narrows the playback cleanup mystery without
turning task closure into proof of a globally stopped or write-free system.
Camera reconnection is not needed for that offline work.
