# RAM handoff: address conversion, copy commands and special entry

Date: 2026-11-02. Offline investigation of official 2.00 images, with local
Ghidra original-instruction controls. No camera access or firmware changes.

Main SHA-256: `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Complete updater SHA-256: `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
Both use base `0x40000000`; addresses below are main-image addresses unless
explicitly qualified as updater.

## Result and correction to the previous handoff interpretation

The loader converts its application addresses into hardware-copy addresses
by clearing bit 30, after alignment/range guards and cache operations. It
programs a copy engine, then calls unresolved helper `0x00000b34` before the
loaded entry. Eleven offline controls confirm the copy-command metadata and
two special-entry gate writes. They do not execute a hardware copy or payload.

The special `+0x200` entry in the **updater** writes `0x88888888` to gate
`0x10001300` and returns. If that value survives the subsequent startup, it
**skips** the DRAM restore branch traced in the previous report and permits
the later automatic-file consumer. Thus the matching preserved parameter
fields establish an initialization convention, but not that the USB launch
path actually uses that restore branch. The earlier report must not be read
as a demonstrated end-to-end RAM boot.

Running a stock updater from RAM would also require auditing what its entry
does afterward. RAM residence does not make an updater's later flash-writing
behavior harmless. No payload or live-test recipe is proposed.

## Preparation before the copied stub

Main `0x401eb1ec` prepares the handoff; its receiver caller supplies fifth
argument 2. Relevant operations, in instruction order:

| Call/site | Observed operation |
| --- | --- |
| `0x4000c30c(0)` | Changes CPSR interrupt-mask state and saves the prior state |
| `0x40046ca0`, `0x4001d45c`, `0x40057dcc`, `0x40046c44` | Platform operations not fully audited here; do not assume complete core/task shutdown |
| `0x4000b608(0x20000,0x20000)` | Calls common helper `0x4000b48c` with selector 0; full semantics outside this pass |
| `0x4000186c` | Stores `0x0001d406` at translation-table location `0x3f004000`, then performs barriers and CP15 maintenance |
| `0x4002a890(workspace,0x800)` | Destination address guard/cache operation; returns address with bit 30 cleared |
| `0x4002a964(stub & ~63)` | Aligned source-address guard and bit-30 clearing |
| `0x4007c7fc` | Copies `0x800` bytes of the stub, preserving its original within-cache-line offset |
| `0x400029d8(workspace,0x800)` | Data/instruction cache-maintenance sequence |
| `0x40055e88(1)` | Updates byte `0x10005104`: `(old & 0x8f) | 0x30` |
| `cpsid i`; `0x40002374(0)` | Disables IRQs and writes `0xffffffff` to seven words in each of two GIC register ranges (`0x10401180..198`, `0x10401280..298`) |
| `0x401eb37c..394` | Writes the five-word SRAM parameter block, rounding odd code lengths up to even |
| `0x401eb398` | Branches to the workspace copy of the stub |

Startup code already uses `0x3f004000` for translation tables. Here the
changed entry, barriers and low-address call are evidence of a mapping
dependency; they do not identify the backing bytes at `0x00000b34` as ROM.
The file word at main image `0x40000b34` is part of a data table, not a
justification for treating the low helper as that image offset. The low
helper's bytes, mapping and side effects remain unknown.

The stub is copied to an application-provided workspace, not necessarily
SRAM. Its stack remains inherited until later code changes it. Actual buffer
ownership, overlap, stack survival, other cores and DMA users remain material
unknowns. This pass does not execute the preparation sequence as a whole.

## Original address guards

The copied stub at original `0x401ed070` reads SRAM `0x3f00d000`:
source, rounded size and destination. It calls `0x4002a7bc(source,size)` and
`0x4002a890(destination,size)` before programming the engine.

For the normal non-assert path, each wrapper requires:

- A 64-byte-aligned starting address at least `0x40000000`.
- Starting address and the 32-bit `address + size - 1` below
  `0x40000000 + (capacity << 20)` and at least `0x40000000`.
- Capacity is obtained by original helper `0x40002b0c` as
  `(byte[0x10015872] & 31) << 6`, in the units used by that range formula.

The source wrapper invokes cache helper `0x400029c8`; the destination wrapper
invokes `0x400029c0`. Both then return `address & ~0x40000000`. The embedded
source pathname is `wrapper/os_wrap_cache.c`.

This is a guarded address-conversion convention, not a general MMU translation
walk. The numerical relation between application `0x4...` pointers and the
previous report's updater-accepted `0x8...`/`0xa...` parameter addresses is not
resolved by these helpers. Capacity is a supplied register in the controls,
not a measured camera RAM size. The guards do not establish ownership or
non-overlap of any apparently in-range memory.

## Copy engine commands and final boundary

The stub sets bit `0x40` in word `0x10000064`, clears bit 0 in byte
`0x10003008`, and writes zero to word `0x10003044`. For each chunk it writes:

| Address | Value |
| --- | --- |
| `0x10003048` | Converted source address |
| `0x1000304c` | Converted destination address |
| `0x10003050` | Chunk length minus one |
| byte `0x10003000` | 1 |
| byte `0x10003040` | 1 |

Chunks are at most `0x100000` bytes. The code repeatedly reads bit 0 of byte
`0x10003000` until it is set, then advances both addresses and subtracts the
chunk length. There is no explicit timeout or separate error check in this
loop. Register electrical semantics and real completion are not validated.

After the loop, the stub writes byte `0x10003000 = 1` again and calls
`0x00000b34(original_destination, original_size)`. The new emulator stops
**before** this call. Original instructions after that call mask CPSR control
bits with `0x7f`, and, for original destination `0x40000000`, call
`0x40000200` before calling the destination itself. If the payload returns,
the stub loops forever.

The special entry belongs to the **newly copied image**, not necessarily the
old application. For the packaged updater, `0x40000200..244` checks supplied
hardware status, can enter a loop writing `0x100003a0 = 4`, or writes gate
`0x10001300 = 0x88888888` and returns. The main image's different `+0x200`
entry writes `0x12345678` to the same gate and branches to `0x40000338`.
These are concrete gate writers missed by the earlier heuristic scan.
The filename/configuration writer remains unidentified.

## Eleven bounded controls

[`ReproduceRamCopyBoundary.java`](scripts/ReproduceRamCopyBoundary.java)
loads hash-checked images and copies the original stub plus literal pool to
synthetic workspace `0x42000000`. It executes the relocated code, original
address wrappers and original capacity getter with a supplied capacity field
of 4, giving a 256 MiB range in those checks. Cache helpers are modeled.

| Fixture | Observed boundary/commands |
| --- | --- |
| Zero size, destination `0x40000000` | Stops before assert diagnostic `0x400070d8`; `destination + size - 1` falls below range |
| Zero size, interior destination `0x40010000` | Reaches low-helper boundary with no copy chunks |
| Size 1, source `0x41000000`, destination `0x40000000` | One command: converted `0x01000000 -> 0`, length 1 |
| Size `0x100000`, same addresses | One 1 MiB command |
| Size `0x100002`, same addresses | 1 MiB command then 2-byte command; both addresses advance correctly |
| Unaligned source | Stops before assert diagnostic, no commands |
| Source at supplied upper boundary `0x50000000` | Stops before assert diagnostic, no commands |
| Unaligned destination | Stops before assert diagnostic, no commands |
| Source `0xa1000000` | Stops before assert diagnostic, no commands |
| Main image special `+0x200` | Writes `0x12345678`; stops before `0x40000338` |
| Updater special `+0x200`, readiness bit supplied set | Writes `0x88888888`; returns |

The size-1 and zero-length cases enter the stub directly, bypassing the
preparation routine's even-size rounding and receiver validation. They are
boundary observations, not a claim that USB requests produce these states.
For the special-entry controls the GIC readiness byte is supplied as `0x40`.

The copy-engine registers contain ordinary emulator memory; completion is
supplied and no payload bytes are actually copied. Assertions stop before
their diagnostic helper rather than modeling an assertion as a successful
return. Low helper, loaded payload, CP15 hardware and persistent operations
never execute. All copy controls verify unchanged loaded application bytes.

Output: [`output/ram-copy-boundary.txt`](output/ram-copy-boundary.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceRamCopyBoundary.java /private/tmp/ram-copy-boundary.txt \
  "$PWD/analysis/output/card-updater-full.bin"
```

Reproduce static instruction windows with `fw.py disasm` at
`401eb1ec..401eb41c`, `401ed070..401ed174`, `4002a7bc..4002a9e8`,
`4000186c..400018a0`, `40002374..4000240c`, and `40000200..40000230`;
use `card.py disasm 40000200 40000248` for the updater's different special entry.

## Remaining barrier

We now know the argument convention, conversion guards, copy-command format
and special-entry markers. Still missing are the actual low-helper bytes,
qualified cache/mapping behavior and buffer ownership across the overwrite,
the payload's complete startup/persistent effects, and a verified temporary
retail USB entry. None establishes cold recovery if the installed main
firmware is broken. No fresh REA or live-camera evidence is claimed.
