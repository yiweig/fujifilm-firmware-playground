# USB reset notification and callback-state requirements

Date: 2026-11-03. Offline original ARM and local Ghidra controls of official
2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses are updater addresses. No camera access or firmware changes.

## Finding and naming refinement

The [USB/storage separation controls](updater-usb-storage-separation.md) stopped
at `0x4000ffa0`, labeled broadly as controller bring-up. Its embedded diagnostic
at `0x400938bb` identifies it more specifically as **`usbDclBusReset`**.
It resets controller/software state and queues internal notifications; its
return does not prove completed USB initialization or enumeration.

The other previously modeled boundary, event registrar `0x4007599c`, stores a
new handler only if the requested slot is empty. An occupied slot keeps its
old callback and returns that pointer. The original USB constructor does not
check this return. Calling the constructor with inherited RAM state therefore
cannot be assumed to replace all handlers.

Normal updater startup has an explicit RAM-clear loop covering this table.
This is a dependency for direct-entry/reentry experiments, not evidence that
ordinary startup leaves stale callbacks. Five bounded controls establish reset
notification and registrar branch behavior. They do not execute a full boot,
interrupt, controller response, USB task or real queue.

## What usbDclBusReset actually requests

Routine `0x4000ffa0` follows these inspected calls:

| Call / operation | Observed effect |
| --- | --- |
| `0x40083560` | Diagnostic boundary, modeled in controls |
| `0x4007574c(1)` | Store RAM option 1 at `0x400ba378 + 0x0c` |
| `0x400744d8` | Write controller bytes/words in `0x10005000` block and a RAM flag; no storage API in its inspected body |
| `0x40082788` | Queue operation on handle stored at `0x400a2cdc`; modeled |
| `0x4000fe84(3)` | Queue an internal word-valued notification 3 on that handle |
| `0x40011520(0)` | Allocate a 12-byte message; original caller overwrites its initial type with 0 |
| `0x40010f60(0, message)` | Queue type-0 message through USB channel 0's handle at `0x400a2edc + 8` |
| Final RAM stores | Reset DCL fields +8/+0x0c/+0x10/+0x14/+4; log and return |

The word notification 3 and halfword message type 0 belong to **different
queues/formats**. They are not interchangeable with storage-worker message
types. On return code 11 from its first queue-post attempt, `0x4000fe84`
removes one queued item and retries once. This is original control flow with
supplied queue returns, not a measurement of actual queue capacity/semantics.

The reset code does not itself wait for those notifications to be processed.
It contains no call to the previously identified NAND initializer or table
builder within these inspected bodies. Queued and interrupt-driven work still
needs separate analysis; a returning reset routine is not proof of a storage-free
runtime.

Channel-0 messages are consumed by USB control task `0x4001a5ec`. For type 0,
handler `0x4001a338` sets its state flag, allocates another message and posts
type 0 to channel 1. The event task `0x40010e88` consumes channel 1 and calls
`0x40010cc0`. Its type-0 branch invokes the currently selected target's
initializer. With target 0 registered, that is `0x400115ec`, which sets receiver
state 2 and can release an old scratch buffer. This propagation is a static
trace; the five new controls do not schedule or execute these tasks.

The reset routine's inspected body does not call the earlier
[PHY/clock acquisition helper](updater-usb-resource-bringup.md) `0x40075ea4`.
That helper's known direct caller is the automatic consumer's cleanup/return
path. This does not prove clocks are unavailable on a gate-zero launch:
earlier platform state and indirect initialization remain unresolved. It does
mean reaching `0x4000ffa0` alone cannot certify that physical USB resources
are ready.

## Event registrar: install only into an empty slot

Registrar `0x4007599c(event_id, requested_handler)` uses state base
`0x400ba38c` and slot `base + 8 + 4*event_id`:

1. Enter a critical section and read the existing slot.
2. If nonzero, leave the critical section and return that existing pointer.
   It does not overwrite the slot, increment the registration count or update
   event masks on that branch.
3. Otherwise, on first-registration state it installs top dispatcher
   `0x400757e4` at state +0 and registers trampoline `0x400757b0` through
   `0x4007f288(0x83, trampoline)`, guarded by count and once-only state.
4. Increment count at +0xbc, store the requested handler, and update the
   selected peripheral mask/status bytes under `0x10005000`.
5. Leave the critical section and return the previous slot value, zero.

Constructor `0x4001111c` invokes this registrar for ten events through an
indirect call; it does not inspect the returned pointer after each call.
A previous constructor control that modeled registration as successful
therefore proved **registration requests**, not real handler replacement.

Trampoline `0x400757b0` calls nonzero state +0 and then nonzero state +4.
Dispatcher `0x400757e4` reads enabled event status and calls corresponding
nonzero slots. This is executable callback dispatch, not just a table of
unused addresses. Existing pointers are not assigned a storage role here;
there is no claim that a specific retained callback writes NAND.

## Normal startup clears the callback RAM

The original startup loop at `0x4000043c..0x40000468` loads these literal bounds:

```text
start = 0x400a2980
end   = 0x400cb980  (exclusive)
```

It writes zero words from start to end before the subsequent helper call.
This interval includes event state `0x400ba38c`, its count/once-only fields,
and USB/DCL task state used above. Static bounds establish that normal execution
of this loop clears those locations. The new tests do not execute complete
startup or prove that memory ownership, interrupts and other cores are safe
when calling internal routines directly.

Clearing arbitrary memory or callback slots on a running camera is not a
proposed fix. A live retail application has different state/layout and active
users; sharing numerical addresses across images does not make them compatible.

## Five bounded controls

[InspectUpdaterUsbResetCallbacks.java](scripts/InspectUpdaterUsbResetCallbacks.java)
executes original reset/registration instructions with synthetic RAM and
peripheral memory:

| Control | Checked result |
| --- | --- |
| Reset, normal queue returns | Word notification 3 to internal queue; type-0 message to USB channel 0; return |
| Reset, first post returns 11 | Remove one item, retry notification 3, then post USB type 0; return |
| Registrar, count zero and empty event-0 slot | Installs requested handler and top dispatcher; requests IRQ registration once |
| Registrar, count three and empty slot | Installs handler and increments count; preserves existing top dispatcher; no new IRQ registration |
| Registrar, occupied slot | Preserves/returns old pointer; no count increment, mask update or IRQ registration |

All five pass. Queue operations, logging, allocation, critical-section helpers
and IRQ registration are modeled. Original controller register stores execute
against synthetic bytes only. The occupied-slot fixture uses inert synthetic
addresses; no callback executes. Loaded-image bytes remain unchanged.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterUsbResetCallbacks.java /private/tmp/updater-usb-reset-callbacks.txt
```

Evidence: [five controls](output/updater-usb-reset-callbacks.txt),
[hash-qualified static ranges](output/updater-usb-reset-callbacks-static.txt).
No new REA or live observation is claimed.

The useful conclusion is that isolated USB setup needs deliberately initialized
software state and qualified physical resource setup. These findings provide
neither a safe retail-to-updater entry nor independent cold recovery. The next
offline dependency is the asynchronous reset/target-task path and the platform
initialization needed before it. No camera reconnection is needed yet.
