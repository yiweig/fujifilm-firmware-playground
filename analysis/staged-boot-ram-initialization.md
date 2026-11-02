# Staged boot initializes the RAM containing the optional PTP hook

Date: 2026-11-02. Offline static inspection and original ARM slice controls.
Camera unplugged throughout; no camera app, USB command, actual DMA, flash
operation, settings change or firmware patch.

## Finding

The main image contains a previously unaccounted-for **hardware RAM-fill
path** that includes the optional `0x9601` callback slot. In the known 2.00
image, staged loader worker `0x4000f064` requests a zero fill of
`[0x40fd1140,0x413ab700)` during stage 28. Callback word
`[0x4136b664,0x4136b668)` lies inside it.

This refines the earlier statement that no initialization writer had been
found. The writer uses a hardware copy/fill engine rather than an ARM store
or either generic memcpy/memset helper previously scanned. A negative CPU
store search could not exclude it.

It provides a concrete **intended zero initialization** for that slot during
normal staged startup. It does not establish its actual live value: hardware
success, later writes and the installed image remain separate questions. In
particular, the worker does not enforce every hardware-request/completion
error result before reporting the stage. No loader-entry or recovery trigger
is established by this initialization path.

## Identity and evidence scope

Official 2.00 main image, ARMv7 little-endian, base `0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses below apply to this image only. Local Ghidra 12.1.4 and original
ARM bytes were used; no fresh REA or device runtime result is claimed.

The ordinary reset entry, platform/bootstrap routines and OS setup were
rechecked. Large decompilations of mixed privileged startup code emitted
bad-instruction/control-flow warnings and are not used as a proof of a full
boot graph. The positive fill finding is anchored in the focused worker,
literal table and original instructions below.

## Table-driven intervals

Worker `0x4000f064` reads table `0x40000b00`, verified by its literal at
`0x4000f374`. The header supplies count 6 and stage mask `0x98400500`.
Selecting successive set bits produces stage IDs `[9,11,23,28,29,32]`.
The first loop iteration skips the fill/load processing in this worker.
The subsequent iterations read boundary words from successive 16-byte rows,
round the boundaries upward to 64 bytes and schedule fills between them.

| Stage | Requested CPU interval, end exclusive | Length |
| --- | --- | --- |
| 11 | `40e68880..40e6a240` | `0x19c0` |
| 23 | `40e6a240..40fd1140` | `0x166f00` |
| **28** | **`40fd1140..413ab700`** | **`0x3da5c0`** |
| 29 | `413ab700..414da2c0` | `0x12ebc0` |
| 32 | `414da2c0..414fb780` | `0x214c0` |

The stage-28 lower bound derives from the table word at `0x40000b4c`
(`0x00fd1138`, rounded to `0x00fd1140`), and its upper bound from
`0x40000b5c` (`0x013ab6ec`, rounded to `0x013ab700`). The worker adds the
`0x40000000` CPU base before mapping the destination.

The final boundary word at `0x40000b7c` is `0x014fb780`, agreeing with the
separately identified allocator boundary `0x414fb780`. This gives that address
an additional concrete role: **end of the staged RAM-fill intervals**. It
still does not identify physical DRAM capacity or make the region a safe
payload/scratch destination. Follow-up [storage/entry analysis](staged-loader-storage-handoff.md)
identifies a preceding CPU clear on the loader-facing path:
`[0x40e37c00,0x40e68880)`. It covers the initial gap and overlaps the tail of
the file-backed image after the SRAM code has been copied from that location.
That path has not been established for every possible image-entry route.

Reproducible table extraction:
[inspect_boot_ram_fills.py](inspect_boot_ram_fills.py), with retained
[interval JSON](output/boot-ram-fill-intervals.json).

## Fill scheduling, completion and startup ordering

At `0x4000f1a8`, the worker calls address mapper `0x4002a7bc(start,length)`.
At `0x4000f1b8`, it calls `0x4002ab54(mapped_destination,0,length)`.
That wrapper constructs a hardware descriptor whose fields are destination,
source sentinel `0xc0000000`, byte count, zero and `0xffffffff`, then obtains
a channel through `0x4000e870`. After allocation it writes the requested fill
value to the channel register at `0x10003058 + channel*0x20` and starts it
through `0x4000ea90`. Thus the requested value is specifically zero, not a
request to copy unspecified source RAM. These are software-request semantics;
no hardware transfer was observed here.

While that operation is outstanding, the worker performs staged section-load
calls and cache-related work. At `0x4000f228` it calls completion/cleanup
wrapper `0x4002abd0`, which reaches `0x4000eeb4` to wait on the channel's
completion event, then `0x4000eb5c` for cleanup. It subsequently releases the
mapping and performs its stage validation callback before publishing the
cumulative stage event at `0x4000f2ec`.

The ordinary initialization task `0x40003498` starts this worker via
`0x4000f39c` at `0x4000354c`. Later, `0x400036e8` requests wait milestone 24
through `0x4000f458`, which reaches event waiter `0x4000f004`. That waiter
selects bit `24-1`. The worker's cumulative event after stage 23 contains
bits 0..22 and cannot satisfy it; stage 28 publishes bits 0..27 and can.
The startup task then proceeds to `0x400f86f4` and later waits at stages 29
and 32. This ties the containing fill to ordinary startup synchronization,
not merely to an unreferenced helper.

```mermaid
flowchart LR
    T[Initialization task starts stage worker] --> F[Stage 28 requests zero fill\nRAM includes PTP callback]
    F --> W[Completion wait / cleanup returns]
    W --> V[Stage validation callback]
    V --> E[Cumulative stage event]
    E --> A[Startup milestone 24 can proceed]
```

The diagram is a static software sequence, not an observed successful DMA.
An allocation failure can return `-1`; the worker does not check the returned
handle before continuing, and `0x4002abd0(-1)` returns without waiting.
The worker also does not check the completion wrapper's return value.
Consequently a released stage event is **not proof that the RAM was actually
zeroed**. Full reset/error behavior and controller reliability are outside
this result.

## Original-instruction controls

[InspectStagedRamFill.java](scripts/InspectStagedRamFill.java) verifies the
whole image hash, supplies the documented loop-register/stack state, and
executes original instructions in `0x4000f168..0x4000f1c8` plus the descriptor
wrapper `0x4002ab54..0x4002abd0`. Successful CPU-to-controller address mapping
and a 20-byte stack-only memset are explicitly modeled. Execution stops
**before `0x4000e870`**, so no channel/MMIO setup, storage operation, hardware
fill or entire boot is emulated.

Five controls reproduce the five intervals and original descriptor fields;
an equal-bound control confirms the branch skips the fill. All six passed.
A nonzero callback sentinel remains unchanged in these controls: the emulator
does not perform or fake the actual zero fill. This validates request
construction, not a zero live callback.

Evidence:
[worker pseudocode](output/boot-ram-fill-worker.txt),
[original ARM boundaries](output/boot-ram-fill-boundaries.txt),
[six control results](output/boot-ram-fill-controls.txt).

```sh
python3 analysis/inspect_boot_ram_fills.py
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectStagedRamFill.java /private/tmp/staged-ram-fill.txt
```

## Consequence for the optional hook and recovery

The [9601 hook review](ptp-9601-hook-review.md) now has a concrete initialization
path that requests zero across its slot, in addition to the absence of a
found registration/completion user. That strengthens the unused-SDK-hook
interpretation for an ordinary successful boot of the known image. It still
does not prove every later writer is absent, identify the installed release,
or warrant sending `0x9601` to the camera.

This staged loader is part of the running application. It is not independent
cold recovery and does not solve entry to the separate USB RAM-loader mode.
No camera connection is needed for the conclusions above.
