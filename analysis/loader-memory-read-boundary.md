# Loader memory reads: confirmed scheduling, unresolved safe access

Date: 2026-11-01. Offline original ARM emulation and static inspection only.
No camera connection, USB traffic, upload, mode switch or firmware change.

## Result

The existing application loader has a concrete memory-to-host transfer path.
Original-code controls now follow its request parser, transfer-message creation
and queue consumer up to the low-level USB transfer routine. This refines the
earlier [receiver assessment](xhalf-usb-ram-receiver.md), which left direction
selectors largely descriptive.

It is not a firmware-enforced bounded reader: the tested parser forwards an
MMIO address, an overflowing address/length pair and a zero length. A truncated
command can consume an old second word as its transfer length. These cases
stop before hardware access; they do not establish what any of those transfers
would do on the camera.

The reachability barrier remains unchanged. API mode 4 selects target 0,
which owns this vendor handler. Ordinary PTP mode 3 selects target 2 and has
a null vendor callback, with no fallback. The
[routing audit](retail-usb-control-routing.md) establishes that boundary.
This pass does not discover a temporary retail entry or independent recovery.

## Identity and execution scope

Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7 little-endian runtime base `0x40000000`. The existing local Ghidra
project was reused after the documented REA raw-image mapping limitation;
no new REA runtime observation is asserted.

## Original request and queue path

Receiver `0x401e1280` uses phase byte `0x40d79b21` and state object
`0x40fd0d50`. The controls begin with phase 2 and an explicitly supplied
command workspace. They feed synthetic setup/control data to the emulator,
not to a USB API.

For the inspected vendor request family `0xfd`:

1. A declared OUT payload moves the receiver to phase 3 and marks it busy.
   `0x401e19a4..c0` schedules receipt into the command workspace using
   transfer helper `0x401e0ea0`, selector 1.
2. After modeled control-payload delivery, `0x401e19c4` dispatches the stored
   request/index. Common parsing at `0x401e1b14..44` reads two little-endian
   words with original helper `0x4002116c`.
3. The memory-to-host branches use selector 2; the opposite direction uses
   selector 3. `0x401e0ea0` constructs queue-0 messages with the address,
   a second copy of the address and length. Selector 2 becomes event 11;
   selector 3 becomes event 9.
4. Original queue consumer `0x401e0b98` routes event 11 through
   `0x401dfaec` to low-level routine `0x40056ac0`, with exact address and
   length. Event 9 routes through `0x401df9cc` to `0x40056798`.
5. The emulator stops before either low-level routine executes. Static
   inspection confirms direction: `0x40056ac0` has CPU-source reads feeding
   the USB FIFO and DMA setup, whereas `0x40056798` receives toward memory.

The relevant branches are:

| Index | Parsed words | Transfer direction / next phase |
| --- | --- | --- |
| `0x04f2` | address, length | Memory toward host; phase 4 |
| `0x04f4` | length, address | Memory toward host; phase 4 |
| `0x04f5` | length, address | Host toward memory; phase 5; opposite-direction control only |

These are decoded firmware schemas, not a ready-to-run USB recipe. The
write-direction control creates metadata only; it never writes the source
fixture or runs the USB worker's hardware routine.

After a supplied completion invocation, phases 4/5 return to phase 2 and
clear busy. That demonstrates the receiver's completion branch, not actual
interrupt/queue timing, transfer success or error recovery. The emulation
does not generate a real transfer-completion event.

## Bounds and state changes

Seven parse/worker controls passed:

| Supplied input | Observed result before hardware |
| --- | --- |
| `04f2`, synthetic RAM address, 16 bytes | Event 11; exact address/length reaches send routine |
| `04f4`, same address/length in alternate order | Same memory-to-host boundary |
| `04f5`, same words | Event 9 reaches opposite-direction boundary |
| `04f2`, `0x10005000`, 16 bytes | Parser/consumer forward MMIO address without rejecting it |
| `04f2`, synthetic RAM address, zero bytes | Still schedules event 11 |
| `04f2`, `0xfffffff0`, 64 bytes | Overflowing interval forwarded without parser rejection |
| `04f2`, only four control bytes delivered | Uses supplied old length word 64 from workspace; schedules 64 bytes |

The short-command control initializes the command buffer's second word to
64 before delivery, then supplies only the address. It demonstrates a missing
minimum-length check on this path. It does not claim that 64 is the value in
any live workspace. The tests check complete synthetic source contents before
and after; target code never accesses the MMIO/overflow source locations.

An eighth control begins with no workspace and stops at allocator
`0x401e020c`, with requested size `0x80000` (512 KiB). The callback attempts
this allocation before processing even the memory-read request. Thus a read
request is not a passive state query: it also changes parser state, creates
messages and may allocate substantial RAM.

No storage writer is executed by these controls. That is not an audit of
every transfer helper, mode-entry action or subsequent hardware effect. A
raw read of registers can have side effects; downstream translation and DMA
handling are not qualified by a parser accepting an address.

## Remaining PTP candidates checked in this pass

The ordinary dispatcher does not turn these particular vendor-looking PTP
operations into general memory reads:

- `0x9601`: dispatch at `0x402e7bc4` requires an optional callback stored at
  `0x4136b650 + 0x14`; it is not itself a raw-memory implementation. The
  earlier bounded [registration census](retail-ptp-command-boundaries.md)
  found no installer for that optional callback among the inspected callers.
- `0x9602` and `0x9812`: both dispatch to `0x402ea6f4`. That handler first
  resolves a media-database object with `0x402e84a8`, constructs a filename
  and performs image-file processing before copying an internally obtained
  buffer to output. It does not use a supplied parameter directly as a RAM
  source. Full image-processing and transfer behavior remain unaudited.
- Standard partial-object handler `0x402eb058` likewise resolves an object
  ID through `0x402e84a8`, then prepares an existing file before applying its
  offset/length arguments. It does not establish arbitrary-file access.

These are static checks of selected candidates, not an exhaustive USB or
MTP-operation audit. No new PTP operations were added to the host reader.

## Reproduction and next gate

[`ReproduceLoaderMemoryRead.java`](scripts/ReproduceLoaderMemoryRead.java)
verifies the original image hash and executes the callback, LE32 helper,
transfer-message helper, message constructor and queue consumer. Modeled
boundaries are message allocation, queue submission, logs, delivery of the
declared control payload and scheduling of completion invocations. Actual
USB controller/DMA routines never execute. [Retained output](output/loader-memory-read.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceLoaderMemoryRead.java /private/tmp/loader-memory-read.txt
```

The useful next gate is a verified temporary entry to target 0, with qualified
entry/exit effects. The known service initializer attempts factory-data writes
and is not qualified as that entry. A future reader would also need strict
host-side direction/address/length checks, complete-command enforcement,
bounded allocation/timeout behavior and error handling.

A RAM snapshot could help establish live settings, but would still need
provenance and consistency checks and would not automatically back up
persistent storage. This application-level reader would also disappear if
the installed application could not boot, so it does not provide independent
recovery. No live memory read, RAM write, upload or service entry is justified
by these controls alone.
