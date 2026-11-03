# Updater storage initialization can reach NAND block erase

Date: 2026-11-03. Offline original-ARM inspection and local Ghidra controls of
official 2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses below belong to the updater. No camera access, real storage
operation, erase execution or firmware changes.

## Finding

The `diskMount` worker's startup type-4 message does no storage work in the
inspected branch. A **different type-2 request**, however, can call a NAND
mount/initialization callback. When it first constructs NAND mapping tables,
a spare-read result of exactly 1 takes a branch that requests block erase,
then retries the read if erase reports success.

This is a concrete potential persistent side effect of storage initialization,
without an explicit firmware-update command. It reinforces why running the
stock updater from RAM is not yet a qualified volatile experiment. It is not
evidence that the camera has taken this branch, that an erase succeeded, or
that ordinary startup always mounts this backend or enters this error case.

## Worker messages and request producer

Worker `0x4001c23c` receives on message service 14 through `0x40065f94`.
Message words are interpreted as type and logical storage identifier:

| Type | Observed behavior |
| --- | --- |
| 4 | Return to receive another message; no driver lookup/callback |
| 1 | Resolve logical identifier, call interface initialization `0x4001a89c`, then post type 2 for the resolved interface |
| 2 | Resolve identifier, post status `0x202` on service 1, call interface record `+0x3c`, then report success/error |

Resolution helper `0x4001a844` consults the runtime mapping at `0x400a39d0`;
record getter `0x4001adc8` indexes 100-byte interface records. The production
mapping is not measured by this pass.

The command initializer's `0x4000e574(0xffff)` path creates the worker and
posts type 4, as described in [command-task identification](updater-command-task.md).
That particular message does not itself request a mount or erase.

For other arguments, the same wrapper calls `0x4001c464`. This request helper
can post type 1 when an interface is not initialized, or type 2 when it is
initialized and has a mount callback. Special identifiers 0/7 use other
branches. Earlier reports' drive-probe helper `0x4000e5a4` calls synchronous
activation helper `0x4001c594`; that helper can initialize the interface and
call its `+0x3c` method directly. Neither activation API is established as a
read-only status query merely from its callers' return-value checks.

## Distinguish two callback structures

For the NAND backend registered by `0x4001be6c`:

| Structure and offset | Concrete callback |
| --- | --- |
| Generic 100-byte interface record `+0x38` | `0x4001bd44`, backend initialization |
| Generic interface record `+0x3c` | `0x4002bc1c`, mount/initialization continuation |
| Separate NAND geometry/state object `+0x34` | Block erase: `0x4003f740` or `0x4003e40c`, depending on profile |

These offsets belong to **different structures**. In particular, a generic
interface `+0x3c` mount callback is not the NAND object's `+0x34` erase slot.

`0x4002bc1c` obtains NAND state through `0x400301d8(1)`. If its `+0x74`
table buffer is null, it allocates buffers and calls `0x4002a248`. An existing
nonzero buffer skips that table-construction call. Later code also enters
filesystem activation, whose complete side effects remain unaudited.

## Spare-read failure to erase request

Routine `0x4002a248` is identified by its embedded diagnostic name
`nandTableMake` at `0x4008b5fb`. It caches the NAND object's `+0x28` read-spare
callback and `+0x34` erase callback on its stack.

The inspected branch is:

```text
4002a37c..4002a38c  call read-spare(state, selected_address, buffer)
4002a390..4002a394  result == 1 -> error branch
4002a3b0..4002a3cc  log "Blk[%03X] spare read fail" and diagnostic
4002a3d0..4002a3dc  call state +0x34 with the same selected address
4002a3e0..4002a3e4  nonzero erase result -> leave this path
4002a3e8..4002a3f8  on zero erase result, retry read-spare
```

Other read result values do not take that exact erase branch; this is not a
claim that every nonzero return or every table scan erases a block. The selected
on-camera address/range and all scan phases remain unknown. Do not infer a
whole-chip erase or a specific firmware/calibration region from this slice.

The erase role is supported by concrete callback installation and callers:

- Installers `0x4003ffd8` and `0x400316c8` write `0x4003f740` into NAND state
  `+0x34`; the 512-byte-page installer `0x4003e9b8` writes `0x4003e40c`.
- Both targets call low NAND routine `0x4006d3b4`, with retry behavior.
- That low routine contains the actual diagnostic
  `Error halNand_AFE() %d Block Erase Fail!`.
- An additional caller uses the same `+0x34` slot while traversing blocks in
  a routine identified by embedded name `nandEraseAll`, and logs
  `Block 0x%x Erase Fail`.

Thus the callback is not left as a guessed writer or inferred from a name
alone. These edges are static evidence; no low NAND operation executes here.

## Eight bounded original-instruction controls

[InspectUpdaterMountBoundaries.java](scripts/InspectUpdaterMountBoundaries.java)
executes selected original instructions with synthetic messages, mapping,
interface state and spare-read results:

- Four worker controls: type 4 returns to receive with no posts; type 1 stops
  before interface initialization; mapped type 2 posts `0x202` then stops
  before `0x4002bc1c`; unmapped type 2 reports `0x204` and returns to receive.
- One callback-installer control: original `0x4003ffd8` places `0x4003f740`
  in a synthetic NAND object's `+0x34` slot.
- Three table-construction controls: supplied read result 0 or 2 reaches
  metadata inspection without this erase request; result 1 reaches the actual
  erase entry with the expected state/address arguments, **stopping before
  its first instruction**.

All eight pass. Queue receive/post, logging and the spare-read result are
modeled; no erase result is modeled because the erase target never executes.
One interface-base global (`0x4009de2c`) is explicitly overridden as a RAM
fixture before execution. Loaded bytes must remain unchanged thereafter;
the source file is unchanged. Fixtures do not represent measured camera state.

No complete initialization, mount, filesystem operation, real NAND read/write,
MMIO, scheduler or camera execution occurs. The worker controls and erase-branch
controls are separate slices, not an end-to-end simulated mount.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterMountBoundaries.java /private/tmp/updater-mount-boundaries.txt
```

Evidence: [eight controls](output/updater-mount-boundaries.txt) and
[image-qualified static ranges](output/updater-mount-write-risk.txt).
No new REA or live observation is claimed.

Before any stock-updater RAM launch could be called volatile, storage activation
and its error handling would need to be prevented or qualified in addition to
the explicit update paths. This finding does not supply such a procedure or
resolve independent recovery entry. No camera reconnection is needed here.
