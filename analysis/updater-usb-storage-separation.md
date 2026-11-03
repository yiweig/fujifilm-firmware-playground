# USB setup is separable from the updater's earlier storage activation

Date: 2026-11-03. Offline original-ARM inspection and four local Ghidra controls
of official 2.00 complete updater at `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses below refer to this updater. No camera access or firmware changes.

## Finding

Follow-up: [USB reset and callback state](updater-usb-reset-and-callback-state.md)
identifies boundary `0x4000ffa0` as `usbDclBusReset`: it queues reset work,
not a completed physical bring-up. Event registration preserves occupied
handlers; normal startup clears the relevant RAM. Five additional controls
resolve those branches without executing tasks or interrupts.

There is no storage-registry readiness check in the inspected USB continuation
and task/queue constructor. With either zero or three registered storage
interfaces supplied, original code selects USB target 0, registers the download
callbacks, requests two USB tasks and three queues, and reaches controller
bring-up `0x4000ffa0` under the same modeled service returns.

This gives a concrete boundary between USB service construction and the
previously traced [NAND initialization risk](updater-nand-initialization-risk.md).
It does **not** show that normal updater startup bypasses storage, that USB can
enumerate without storage on hardware, or that directly calling this boundary
from the running retail application is safe. The actual scheduler, allocator,
event registrar, task bodies and controller are not executed by these controls.

## The three entry situations are different

| Situation | What the inspected code establishes | Remaining boundary |
| --- | --- | --- |
| Normal startup through `0x4000125c` | Calls the automatic startup consumer before tail-calling USB continuation `0x400007f4` if it returns | Conditional backend registration, NAND initialization and file handling occur first |
| Gate `0x10001300 == 0` at the consumer | Skips the storage/file branches inside that consumer and returns toward USB through helper `0x40069374` | Gate producer/control, earlier helpers, real peripheral effects and USB runtime remain unqualified |
| Direct entry at continuation `0x400007f4` | Original USB registration/constructor code can reach controller setup with an empty registry under supplied services | This entry is a synthetic control boundary, not a demonstrated loader entry or live-call recipe |

The updater's special `+0x200` entry is **not** a skip-storage switch. Once
its readiness branch is satisfied, it writes `0x88888888` to `0x10001300` and
returns. If preserved through startup, the nonzero value enables the later
consumer's work and skips the DRAM restore branch. See the earlier
[handoff and gate-writer controls](ram-handoff-copy-boundary.md).
Changing or clearing that register is not proposed as a camera experiment.

An aligned direct B/BL scan finds the startup tail branch at `0x40001398`
as the direct reference to `0x400007f4`. This scan does not cover indirect,
Thumb, runtime-generated or earlier-loader references; it is not proof that
there is no independent entry elsewhere.

## Original USB construction path

Continuation `0x400007f4`:

1. Manipulates bit `0x10` of the peripheral byte at `0x10005104`.
2. Calls command-task setup `0x40009ca0`.
3. Calls `0x40010c70(0)`, selecting USB target 0 at `0x400a2ea8`.
4. Calls `0x4000f1ac` -> `0x40012100`, installing initializer `0x400115ec`
   and receiver `0x40011634` through original setter `0x40010e68`.
5. Calls `0x4000f028` -> `0x4001111c`, constructing USB services.
6. After that constructor returns, sets state through `0x40074cb4(1)` and
   restores the peripheral byte with bit `0x10` cleared. This final return
   segment is outside the controls, which stop during the constructor.

The original constructor requests:

| Resource | Original entry / shape |
| --- | --- |
| USB control task | Entry `0x4001a5ec`, allocated state size `0x11b4` |
| USB event task | Entry `0x40010e88`, allocated state size `0x11b4` |
| Two queues | Parameter 1 = 1, element size 12 |
| Additional queue through `0x4000ff24` | Parameter 1 = 1, element size 16 |
| Event registrations | IDs `0x10`, `0x11`, `0x12`, `0`, `1`, `2`, `9`, `0x0a`, `3`, `4` through `0x4007599c` |
| Controller bring-up | `0x4000ffa0(0)`, the final stop boundary |

`0x4000ff24` itself is an additional queue constructor, not controller bring-up.
The event registration helper is modeled as returning; its entire side-effect
set is not classified here. The control task's original entry receives queued
requests and calls `0x4001a338`; the event task first calls descriptor setup
`0x400108bc`, then receives events and calls `0x40010cc0`. Their complete
runtime behavior remains outside the constructor controls.

The registered download initializer `0x400115ec` sets receiver state 2 and can
release an existing scratch buffer. Registration alone does not call it or
prove that its buffers are valid. Target-0 descriptor/receiver work remains
as documented in [USB identity](updater-usb-identity.md) and
[parameter producer](updater-usb-parameter-producer.md).

The `Cmd` setup call must also be distinguished from the created command task.
Earlier inspection shows that task registers command tables and starts a
storage worker with an inert type-4 event; it also exposes write/update
commands. The new controls model `Cmd` setup and do not schedule that task or
its optional auxiliary console service. They do not prove all concurrent work
is safe or that console input cannot arrive.

## Four bounded controls

[InspectUpdaterUsbStorageBypass.java](scripts/InspectUpdaterUsbStorageBypass.java)
starts directly at original `0x400007f4`. The four cases combine supplied
registry count 0/3 with initial peripheral byte `0x00`/`0x10`.
Each starts with USB target 2, then checks that original code:

- Selects target 0 and installs the exact initializer/receiver addresses.
- Requests task entries `0x4001a5ec` and `0x40010e88` in order.
- Creates three queues and registers the ten expected event IDs in order.
- Leaves the supplied registry count unchanged.
- Reaches `0x4000ffa0` and stops before its first instruction.
- Leaves loaded-image bytes unchanged.

All four pass. `Cmd` setup, allocator, task creation, queue creation and event
registration are modeled. Original continuation, callback registration,
constructor branches, stack setup and RAM stores execute. Peripheral addresses
contain synthetic memory. No real controller, scheduler, task body, enumeration,
NAND/SD read/write, upload or camera executes.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterUsbStorageBypass.java /private/tmp/updater-usb-storage-bypass.txt
```

Evidence: [four controls](output/updater-usb-storage-bypass.txt),
[hash-qualified static ranges](output/updater-usb-storage-bypass-static.txt).
No new REA or live observation is claimed.

## What this changes for the recovery work

USB service construction is not visibly dependent on a completed NAND mount
in the inspected original caller/constructor instructions. That supports
investigating a temporary payload with a smaller, isolated initialization path.
It does not supply such a payload: controller initialization, runtime callbacks,
RTOS/memory state, entry transport and the low-address handoff helper remain
unqualified dependencies.

The next focused offline boundary is `0x4000ffa0` and the controller/event
initialization it triggers, especially calls that could reconnect storage or
invoke inherited callbacks. Separately, independent recovery entry remains
unresolved. These findings do not justify flashing or jumping into a function
on the connected camera. No reconnection is needed yet.
