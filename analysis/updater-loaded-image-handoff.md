# Updater-to-loaded-image handoff

Date: 2026-11-02. Offline original-byte analysis and local Ghidra controls of
official 2.00. Updater SHA-256:
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses here belong to the **complete updater**, base `0x40000000`,
unless explicitly labeled main. The camera remained unplugged.

## Result

Follow-up: [USB parameter producer](updater-usb-parameter-producer.md)
identifies the two globals as received-parameter pointer/length fields in the
updater's own USB state. It also finds the receiver's indirect call to the same
handoff preparation and its registration in the running updater's startup.

After the [installed-image check](updater-installed-image-checks.md), the
updater can prepare a task, copy header data into SRAM, relocate a small
copy-and-jump stub into a workspace, and transfer the loaded image with a
hardware copy engine. That stub calls unresolved low helper `0x00000b34`
before entering the new image. It uses the same five-word SRAM handoff and
special `+0x200` convention previously found in the application's USB loader.

This extends the known RAM execution convention in the opposite direction:
running updater to installed firmware. It does not recover the earlier loader,
provide external entry into the updater, or prove a safe live RAM launch.

The updater's address guards also differ materially from the main image's:
they check alignment and top address bits, without consulting the RAM-capacity
register used by the main wrappers. Passing them does not establish real RAM
capacity, ownership, aliasing or safe source/destination overlap.

## State and task boundary

The load helper's state is at `0x400a2cb8`:

| Offset | Observed use |
| --- | --- |
| `+0x00` | Selection flags |
| `+0x04` | 64 KiB scratch allocation; first `0x800` bytes later hold the copied stub |
| `+0x10` | Buffer holding the loaded image bytes |
| `+0x14` | Image byte length |
| `+0x18` | Requested destination, normally `0x40000000` |
| `+0x1c` | Synchronization handle |
| `+0x20` | Created task handle |

Immediately after setting boot-status bits, the helper calls storage operation
10 with destination `scratch + 0x800` and argument `-1`. It logs `Header read
error` if the result is nonzero, but the shown branch continues toward task
creation. Header failure therefore is not an immediate abort at this point;
later behavior and successful boot are not established by this observation.

Task creation at `0x4000fb94..0x4000fbac` supplies entry `0x4000f1b0` from
literal `0x4000fc68`; the helper subsequently waits on its synchronization
handle. We have not emulated the scheduler or a complete task lifecycle.

Task `0x4000f1b0` masks interrupt state, performs platform preparation and
copies **16 KiB** from `scratch + 0x800` to `0x3f000000`. For rawC-related
flags it may additionally write `0x3f0001f8` and `0x3f0001fc`. The default
Firmware2 flags do not take that rawC-specific write branch.

The task then calls `0x4001219c(image_buffer, scratch, image_length,
destination, 3)`. Fifth argument 3 selects the actual diagnostic string
`Boot 3`; it must not be interpreted as a firmware-slot number.

## Preparation and five-word SRAM block

Helper `0x4001219c` logs its arguments and changes interrupt/platform state.
It calls register helpers with masks `0x08000000`, `0x00010000` and `0x200`;
the full hardware meaning and shutdown coverage of those helpers are not
qualified here. It reads a configuration byte at `0x10004071`, obtains another
platform value and writes configuration bytes at `0x10004070`, `0x10004071`
and `0x1000407b`. No physical pin/clock names are assigned to those fields.

It prepares `0x800` bytes of workspace through `0x4007e8cc`, converts the
aligned source containing stub `0x400123dc` through `0x4007e958`, preserves
the source's offset within its 64-byte line and copies the stub into that
workspace through `0x40068ed0`. It then performs cache maintenance, updates
byte `0x10005104` to `(old & 0x8f) | 0x30`, executes `cpsid i`, and writes
`0xffffffff` to seven words in each of the two GIC ranges at
`0x10401180..0x10401198` and `0x10401280..0x10401298`.

Instructions `0x40012328..0x40012354` write:

| SRAM word | Value |
| --- | --- |
| `0x3f00d000` | Loaded image source buffer |
| `0x3f00d004` | Image size, rounded up to even |
| `0x3f00d008` | Requested execution destination |
| `0x3f00d00c` | Existing value at updater global `0x400a2f20` |
| `0x3f00d010` | Existing value at updater global `0x400a2f24` |

At `0x40012358` it calls the workspace. The last two fields occupy the same
positions as the application's preserved parameter pointer/size, but their
updater producers and actual values are not established by this pass. They
must not be described as validated parameters merely from matching positions.

The earlier 16 KiB SRAM copy ends at `0x3f004000`, so it cannot produce the
separate 36-byte storage descriptor at `0x3f00c518` identified previously.

## Relocated stub and final entry

Stub `0x400123dc` reads source, length and destination from the SRAM block.
Its source wrapper `0x4007e840` and destination wrapper `0x4007e8cc` require
a 64-byte-aligned start whose top two bits are `01`, and the same top bits
for the 32-bit expression `start + length - 1`. They invoke cache helpers
and return `start & ~0x40000000`. There is no measured-capacity test in these
updater wrappers. Zero-size behavior depends on the supplied start address;
it is not an upload or payload acceptance rule.

The stub programs `0x10003048` (converted source), `0x1000304c` (converted
destination), and `0x10003050` (chunk size minus one), then writes byte 1
to `0x10003000` and `0x10003040`. Chunks are at most 1 MiB. It polls bit 0
of `0x10003000` without an explicit timeout or separate error check here.
It also sets bit `0x40` at `0x10000064`, clears bit 0 at `0x10003008`, and
clears a **halfword** at `0x10003044`; the main stub instead clears a word.
Hardware electrical/completion semantics remain unmeasured.

After the last chunk, `0x4001249c..0x400124a0` calls `0x00000b34` with the
original destination and size. These bytes/backing remain unknown; this is
the same missing helper as in the application's copy path. We stop before it.

Original instructions afterward mask CPSR control bits with `0x7f`. If the
destination is `0x40000000`, they call the **new image's** `0x40000200` entry
first, and call the destination itself only if that returns. An entry that
does not return prevents the second call. The known main-image `+0x200`
entry writes gate `0x12345678` and branches to `0x40000338`; the packaged
updater's different special entry can return. A returned payload ultimately
leaves the stub in an infinite loop.

The image's identity and startup effects therefore matter even when all bytes
were loaded into RAM. This is not a flash-free or recovery guarantee.

## Controls and remaining boundary

[ReproduceRamCopyBoundary.java](scripts/ReproduceRamCopyBoundary.java) now
retains its original 11 controls and adds nine updater copy-boundary controls
plus two updater descriptor controls. All **22 pass**:

- The relocated updater stub constructs the expected one-chunk and split-chunk
  commands and preserves the original low-helper arguments.
- Unaligned and A-range inputs stop before assertions. With a supplied 256 MiB
  capacity fixture, `0x50000000` is rejected by the main wrappers but accepted
  by the updater wrappers. This demonstrates the guard difference, not actual
  memory availability at that address.
- The halfword access preserves an upper-halfword sentinel at `0x10003044`.
- Even size 32 remains 32; odd size 33 becomes 34 in the five-word descriptor,
  with synthetic values for the last two globals. These stop before the
  workspace call.

All controls verify unchanged loaded executable bytes. Cache helpers and
copy-engine completion are modeled; no payload bytes are moved by a real or
emulated DMA engine. Low helper, payload entry, actual MMIO/CP15 operations,
flash and whole startup never execute. The complete preparation routine and
task's header copy are static observations, not whole-path emulation.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript ReproduceRamCopyBoundary.java /private/tmp/ram-copy-updater-boundary.txt \
  "$PWD/analysis/output/card-updater-full.bin"
```

Evidence: [22 control results](output/ram-copy-updater-boundary.txt) and
[updater static ranges](output/updater-loaded-image-handoff.txt). The script
uses the main project's ARM language but separately hash-checks and loads the
updater for its updater cases. No new REA or live result is claimed.

The remaining barriers are the low helper's implementation, real workspace
ownership/cache state and hardware-copy behavior, the loaded image's startup
effects, and independent entry into an executing loader. This pass confirms
more of the software convention without closing any of those live boundaries.
