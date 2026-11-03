# Updater USB parameter producer and startup registration

Date: 2026-11-03. Offline static inspection of official 2.00 complete updater,
base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
Every routine address in this report belongs to that updater. No camera
connection, USB requests, card preparation or firmware edits occurred.

## Finding

Follow-up: [USB/storage separation](updater-usb-storage-separation.md) follows
original target registration and task/queue construction to the controller
bring-up boundary with an empty storage registry. Four bounded controls pass;
task bodies, controller and real entry remain unverified.

Follow-up: [updater USB identity](updater-usb-identity.md) connects its
GET_DESCRIPTOR path to the `2aad:6500` template and vendor endpoints. Runtime
packet-size mutation and the lack of observed enumeration remain explicit.

The two globals used as the last words of the updater's SRAM handoff are
fields of its **own USB download state**. A received-parameter branch writes
the allocated buffer pointer and received length there. The updater's startup
continuation also registers the USB handler and sets up its tasks/queues.

This connects a concrete producer to the previously traced
[loaded-image handoff](updater-loaded-image-handoff.md). It does not establish
that the globals are valid on every boot, that ordinary installed-image boots
populate them, or that the updater's USB service can be reached from normal
retail PTP mode. The updater must already be executing this startup path.

## State layout and parameter writes

USB receiver `0x40011634` uses state base `0x400a2efc`, loaded from literal
`0x40012044`. Relevant fields are:

| State offset | Address | Observed use |
| --- | --- | --- |
| `+0x18` | `0x400a2f14` | Workspace allocation used by the receiver's handoff |
| `+0x1c` | `0x400a2f18` | Transfer address/source |
| `+0x20` | `0x400a2f1c` | Transfer length |
| `+0x24` | `0x400a2f20` | Received parameter buffer pointer |
| `+0x28` | `0x400a2f24` | Received parameter byte length |
| `+0x2c` | `0x400a2f28` | Requested execution destination |
| `+0x30` | `0x400a2f2c` | Optional callback, set by `0x400120f0` |

After an OUT data stage, request `0xfd` reaches the index switch at
`0x40011c6c`. Index `0x04f3` selects `0x40011ce8`. A nonzero transfer length
is required before the branch continues. Byte `0x4009de05` (a separate
configuration/state object at `0x4009dd84 + 0x81`) selects the path printing
`[usbVNDRReq0xFD] Para` when clear, or `... Code` when set. This pass does not
identify that byte's producer or call it a first-use flag.

On the parameter branch:

1. An existing parameter pointer is passed to release helper `0x4007f830`.
2. Instructions `0x40011d1c..0x40011d30` copy the transfer length into state
   `+0x28`, allocate that length through `0x4007f6dc`, and save the result at
   `+0x24`. These resolve the previously unnamed producer fields.
3. Helper `0x40074e1c(pointer, length)` receives the data, then
   `0x4006811c(pointer, length)` processes the parameter records.
4. A nonzero processing result takes a branch that logs/dumps 512 bytes,
   clears `0x3f000200..0x3f003fff`, and copies the received length there.
5. A zero result releases the buffer and clears `+0x24`; this slice does not
   clear the saved length at `+0x28`.

`0x4006811c` iterates up to eight records through `0x40067d00` and
`0x400680c8`, accumulating spans for records those helpers accept. The parser
checks `DRAMPAR3` words. This supports the buffer's parameter-data role; it
is not a complete format/authentication or malformed-input safety audit.
Although a length is passed to the processing helper, its visible loop does
not use it as a bound on parsing the buffer. These branches are not proposed
as a harmless memory-write test.

Later, handoff instructions `0x40012328..0x40012354` copy those globals into
SRAM `0x3f00d00c` and `0x3f00d010`. The matching convention was already found
in the **main application's** USB receiver and the updater's startup restore
consumer; see [DRAM parameter handoff](updater-dram-handoff-and-register-writers.md).
The updater globals are not a recovered producer of the distinct storage
descriptor at `0x3f00c518`.

## A second caller of the handoff preparation

The earlier report followed the installed-image boot task's direct call to
`0x4001219c`. The USB receiver contains an additional **indirect** call.
Its state-6 branch `0x40011ff0..0x40012020`:

- Calls the optional state `+0x30` callback when nonzero.
- Supplies source, workspace, length and destination from `+0x1c`, `+0x18`,
  `+0x20` and `+0x2c`, with fifth argument 3.
- Loads `0x4001219c` from literal `0x400120e0` and calls it through `r12`.

Thus the updater's USB receiver and installed-image boot task share the same
preparation routine and relocated copy/jump stub. An aligned direct B/BL
search alone finds only the task's call and misses this receiver edge.
Both paths retain the unresolved `0x00000b34` and real-hardware boundaries.

The receiver also implements an IN request `0x70`: it returns byte 3, and
nonzero setup arguments cause allocation and clearing of a requested-size
buffer plus a separate `0x800` workspace allocation. As in the application's
similar query, a host operation called a query can change runtime state.
The meaning of response 3 is not assigned from this constant alone.

## Registration in an executing updater

Startup `0x4000125c` calls its boot-register consumer at `0x40001390`, then
tail-calls continuation `0x400007f4` at `0x40001398`, if the consumer returns.
The continuation:

1. Calls command-task setup `0x40009ca0`; see the [task identification](updater-command-task.md).
2. Selects USB target 0 through `0x40010c70(0)`.
3. Calls `0x4000f1ac`, a tail wrapper for registration `0x40012100`.
4. Calls wrapper `0x4000f028`, which tail-calls USB setup `0x4001111c`.
5. Calls setter `0x40074cb4(1)`, writing byte 1 to state `0x400ba37e`.

Registration `0x40012100` supplies initializer `0x400115ec` and receiver
`0x40011634` to callback-array setter `0x40010e68`. With target 0 selected,
the relevant initializer and receiver slots are `0x400a2eac` and
`0x400a2ebc`. USB dispatch code reads the selected target's callback array.
The initializer sets request state 2, clears a state byte, and can release an
existing scratch buffer. It does not establish valid parameter globals.

Setup `0x4001111c` allocates task state, creates tasks with entries
`0x4001a5ec` and `0x40010e88`, creates queues, registers event callbacks,
and calls `0x4000ffa0(0)`. Task `0x40010e88` obtains queued events and passes
them to dispatcher `0x40010cc0`. These static edges connect the handler to
service machinery. No scheduler, USB controller, host enumeration, endpoint
transfer, or successful upload has been observed or emulated in this pass.

The startup continuation creates the `Cmd` interpreter, which registers update
commands and starts a storage worker. That is separate from the outer automatic
file-update branch. Its USB machinery still does not establish that launching
the stock updater from RAM would be flash-free.

## Evidence and limits

[Original-byte evidence](output/updater-usb-parameter-producer.txt) records
the code ranges, literal strings and indirect target. Regenerate each listed
range with hash-checking `analysis/card.py disasm START END`.

This pass uses static instructions, not new synthetic controls or live data.
A trial of the existing bounded constant-store scanner missed even the known
parameter writes above: its straight-line resolver loses the base through
these branches/register transfers. An empty heuristic result must not be
treated as a writer inventory. The manually followed base-plus-offset stores
and call arguments establish the positive finding instead.

We have now identified a parameter producer, a second handoff call edge and
updater-side USB startup registration. Still missing are independent entry
into this executable, verified target/descriptor/controller behavior on the
camera, and the low helper's bytes/effects. No new REA result is claimed.
