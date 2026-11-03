# USB reset: control/target acknowledgement and pointer queues

Date: 2026-11-03. Offline original ARM and local Ghidra controls of official
2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses belong to the updater. No camera access or firmware changes.

## Finding

The queued reset can complete a software acknowledgement exchange without a
NAND/SD call in the inspected target-0 path. Control handler `0x4001a338` gates
ordinary request dispatch, posts reset work to the target handler, and waits
for a type-1 acknowledgement to clear that gate. Target handler `0x40010cc0`
invokes the selected initializer, obtains configuration-descriptor attributes,
and posts the acknowledgement.

For download target 0, initializer `0x400115ec` resets receiver state and can
release a previous RAM buffer. Four new original-instruction controls complete
or bound this exchange with explicit serial message delivery. They do not run
an RTOS, task loops, interrupts, physical USB or storage.

This resolves a specific asynchronous dependency left by the
[reset/callback report](updater-usb-reset-and-callback-state.md). It does not
prove that reset finishes on hardware, that all USB requests are harmless, or
that a safe RAM-entry/recovery procedure exists.

## Correction: USB queues contain message pointers

The earlier reset harness treated the argument to the USB queue-post helper
as a message address. In fact, `0x40010f60` stores the message pointer in a
stack word and passes the **address of that word** to `0x400826b4`. Queue
receive wrapper `0x40011018` obtains a pointer-sized item and returns that
message pointer to the task. This is different from the DCL notification queue,
where `0x4000fe84` posts the integer value itself.

The previous reset fixture allocated its message at `0x42100000`; reading the
low halfword of that pointer happened to produce 0, masking the mistaken
message-type assertion. The repaired harness allocates at `0x42100080` and
dereferences the queued pointer before reading its type. Its original five
controls were rerun successfully; the retained output is replaced with the
corrected run. The reset/registrar branch conclusions remain supported by
these corrected controls, without claiming the earlier assertion was sufficient.

Queue constructor `0x400825a0(name, words_per_item, capacity)` also clarifies
the earlier size labels: it computes

```text
buffer_bytes = (words_per_item << 2) * capacity
allocation_bytes = buffer_bytes + 56
```

USB queues use one word per item and capacities 12, 12 and 16. Their data
buffers are therefore 48, 48 and 64 bytes, excluding the 56-byte control area.
The previous report's labels of 12/16 as element byte sizes were incorrect.
This is original allocation arithmetic, not an external RTOS identification.
The separately allocated USB message object is 12 bytes and is distinct from
the one-word queue item pointing to it.

## Exact reset exchange

| Step | Original code | Result |
| --- | --- | --- |
| Control receives halfword type 0 | `0x4001a338`, branch `0x4001a35c` | Set gate byte `0x400a39cc` to 1, allocate 12-byte message, post its pointer to channel 1 with type 0 |
| Ordinary control work while gate is 1 | `0x4001a3b0..0x4001a3bc` | Return before the ordinary request-type switch |
| Target receives type 0 | `0x40010cc0`, branch `0x40010d20` | Log reset, invoke initializer selected by target byte `0x400a2ea8` |
| Target-0 initializer | `0x400115ec` | Set receiver state byte `0x4009dd84` to 2; release nonzero scratch pointer at `0x400a2efc`; clear pointer and following state byte |
| Target completes reset handling | `0x40010d5c..0x40010dac` | Obtain target-0 configuration descriptor, inspect attribute bit `0x40`, pass 0/2 to `0x40075470`, allocate type-1 message, post pointer to channel 0 |
| Control receives type 1 | `0x4001a388` | Clear another control-state byte, set RAM option 0 through `0x4007574c`, run `0x40074610`, signal through `0x40075768`, clear gate byte to 0 |

Type 0/1 is recognized before the gate check. This lets the acknowledgement
release a gate that suppresses ordinary setup-request work. The supplied
sequence is a serial ordering chosen to exercise those branches, not an
observed scheduler or race analysis.

Helper `0x40075470` ORs its argument into peripheral byte `0x10005100`.
The original configuration descriptor has attribute byte `0xc0`, so the
inspected target-0 reset passes value 2. Helper `0x40074610` resets a RAM
flag and writes controller bytes `0x10005008 = 7`, `0x1000500a = 7`,
`0x1000500b = 0x27`, and `0x10005310 = 0xff`. Helper `0x40075768` sets
RAM byte `0x400ba388` to 1. These register stores execute only against
synthetic emulator memory here; physical effects are not validated.

The target initializer is selected at runtime. A target-2 fixture with an
inert callback sentinel stops at that sentinel, demonstrating that the reset
is not hardwired to the download initializer. No storage role is assigned to
that synthetic target-2 pointer or to an unexamined real target's initializer.

## Four controls and repaired previous controls

[InspectUpdaterUsbResetExchange.java](scripts/InspectUpdaterUsbResetExchange.java)
executes original dispatch handlers directly, manually delivering posted
messages between them:

- Complete target-0 exchange with no previous scratch buffer: gate 1 then 0,
  receiver state 2, reset/ack posts in order and no release calls.
- Same exchange with a supplied scratch buffer: exactly that buffer is passed
  to the modeled release helper and its pointer is cleared.
- Type-4 setup message while the gate is 1: returns without parsing the setup
  or allocating/posting target work.
- Type-0 target message with target 2 selected: reaches that target's supplied
  callback and stops before executing it; download initializer is not called.

All four pass. Allocation, free, logging and queue post are modeled; the
original control/target handlers, descriptor lookup, target-0 initializer,
acknowledgement branch and small register helpers execute. The only changed
loaded-image byte is the declared **RAM data field** `0x4009dd84`, from initial
receiver state 1 to 2. All other loaded bytes are checked unchanged. The source
binary on disk is untouched; this state change is not a firmware patch.

The handlers are invoked directly rather than through their infinite task
loops. Normal task message disposal, queue scheduling/failures and concurrent
interrupts are outside these controls. No low NAND/SD routine, flash writer,
upload, controller readiness or live camera executes.

The repaired [previous harness](scripts/InspectUpdaterUsbResetCallbacks.java)
also passes all five controls with pointer-aware USB message decoding and an
allocation address whose low halfword is nonzero. That rerun checks the
previously masked distinction, rather than merely renaming its output.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterUsbResetExchange.java /private/tmp/updater-usb-reset-exchange.txt \
  -postScript InspectUpdaterUsbResetCallbacks.java /private/tmp/updater-usb-reset-callbacks.txt
```

Evidence: [four new controls](output/updater-usb-reset-exchange.txt),
[five repaired controls](output/updater-usb-reset-callbacks.txt),
[hash-qualified static ranges](output/updater-usb-reset-exchange-static.txt).
No new REA or live observation is claimed.

The reset acknowledgement path inspected here does not add a NAND requirement
to USB construction. Physical resource setup, safe entry and independent
recovery remain separate gaps. The next useful offline question is whether
normal startup's earlier peripheral setup supplies the USB resources omitted
by the gate-zero path. The camera can remain unplugged.
