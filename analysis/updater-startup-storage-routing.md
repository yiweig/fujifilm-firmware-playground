# Storage activation before updater USB startup

Date: 2026-11-03. Offline original-ARM inspection and bounded local Ghidra
controls of official 2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
Every address below belongs to that updater. No camera access or firmware changes.

## Finding

The automatic-file consumer can activate storage **before** USB startup and
before the command interpreter's inert type-4 worker message. Its explicit
file-drive requests select logical drive 2, with fallback to 5. The original
registration code maps those identifiers to wrappers of the shared routine
identified by embedded diagnostic `sdVfsMount`, not to the NAND mount callback.

Separately, earlier startup branches can register the NAND backend (logical
identifier 1), immediately initialize it, and invoke the previously registered
firmware-selection callback. Thus the no-op worker message does not eliminate
startup storage or automatic boot activity. Conversely, the drive-2/5 calls
alone do not establish that the NAND mapping-table erase branch is reached.

This resolves part of the routing uncertainty in the
[mount/write-risk report](updater-mount-write-risk.md). It does not qualify a
write-free updater launch, an independent entry method or recovery.

## Original registration establishes distinct backends

Registrar `0x4001aba0` copies a 100-byte record into an available RAM slot and
calls `0x4001a7c8(record_index, logical_identifier)` to install the mapping at
`0x400a39d0`. Registration of identifier 1 additionally maps aliases 0 and 7
to its record. Record indices depend on registration order; identifiers and
indices must not be treated as interchangeable.

| Registration routine | Logical identifier | Record +0x38 initializer | Record +0x3c mount callback |
| --- | --- | --- | --- |
| `0x4001b4e4` | 2 | `0x4001b354` | `0x4001b4dc`, tail-call `0x4001dfb4(2)` |
| `0x4001b744` | 5 | `0x4001b5b4` | `0x4001b73c`, tail-call `0x4001dfb4(5)` |
| `0x4001be6c` | 1, aliases 0/7 | `0x4001bd44` | `0x4002bc1c`, NAND table/mount continuation |

Routine `0x4001dfb4` contains a diagnostic pointer to actual string
`sdVfsMount` at `0x4008b458`. Identifiers 2 and 5 select different 152-byte
state records at `0x400a3aa0`; this pass does not assign them to two physical
slots or independently audit the complete SD driver. Both initializers call
`0x40021d78` with their respective identifier. The shared mount routine can
enter filesystem activation; it is not established as read-only.

NAND registration is more than installing callbacks: after the registrar
returns, `0x4001bf08` calls `0x4001bd44` immediately. That initializer calls
low NAND initialization `0x4002b7fc(1)`. On success it can retrieve rawC
configuration, configure regions, and invoke the callback returned by
`0x4001c77c`, through the indirect call at `0x4001be44`.
The callback's [selection behavior](updater-firmware-selection.md) is already
traced; it can request rawC images and finally `:DEF_FW2` if its loop finishes.
Full low NAND initialization and subsequent region setup are not classified
as write-free by this pass.

## Startup paths and their conditions

Consumer `0x400009c4` is called at startup `0x40001390`. If it returns, the
startup routine tail-calls USB/service continuation `0x400007f4`.

| Inspected path | Storage action before that continuation |
| --- | --- |
| Gate word `0x10001300 == 0` | Branches directly to `0x400010c0`; skips storage branches inside this consumer |
| Conditional selection path `0x40000c68` | Installs selection callback, initializes a three-record interface registry, registers drives 2/5, then selects another backend from boot configuration |
| Automatic-file preparation `0x40000e24` with registry count zero | Initializes a three-record registry and selects a backend, then registers drives 2/5 |
| Automatic-file preparation with registry count nonzero | Skips that registration block; existing state determines routing |
| File-drive activation `0x40000ed4..0x40000ed8` | Calls synchronous activation `0x4000e5a4(2)` before pathname/file checking |
| Fallback `0x400010c8..0x400010cc` | Calls the same activation API with identifier 5; success changes pathname prefix to `G` |

The backend selector uses `read16(0x10000058) & 0xe0`. In both registration
blocks, values `0x00`, `0x20`, and `0x40` reach NAND registration
`0x4001be6c`; `0x00`/`0x40` first call `0x4002bde8` with different arguments.
Other selector values choose other registration routines or log an error.
These are supplied hardware/configuration values in static control flow, not
measured values from this camera and not a physical-button recipe.

The first path registers drives 2/5 **before** the selected backend; the
second does so **after** it. Both orders preserve logical identifiers through
the mapping. The getter `0x4001ad68`, previously modeled as a storage-readiness
result in mailbox controls, simply reads the registered-record count at
`0x400a3a28 + 8`. A nonzero result is not proof that every backend is mounted.
Those earlier fixtures deliberately avoided the initialization block; they
remain conditional path controls, not complete startup tests.

Gate zero skips these actions in this consumer only. Earlier startup helpers,
USB initialization, concurrent tasks and future commands remain outside this
classification. No way to safely select that gate on hardware is established.

## Synchronous activation and the NAND special case

`0x4000e5a4` tail-calls `0x4001c594`. For identifiers other than special 0/7,
this routine resolves the RAM mapping, checks initialization state, may call
generic initializer `0x4001a89c`, then invokes record `+0x3c`.

The generic initializer has an important special case: when record word 0 is
1 (the NAND record), it marks the record initialized and returns without
calling record `+0x38`. This is consistent with NAND registration having
already called its initializer directly. It does **not** make NAND activation
inert: synchronous activation subsequently calls NAND `+0x3c`, the table/mount
routine containing the previously documented conditional erase path.

No claim is made that selecting drive 2 or 5 automatically invokes that NAND
callback. The production registry state, backend selector and complete indirect
call graph have not been observed on the camera.

## Eight bounded controls

[InspectUpdaterStorageRouting.java](scripts/InspectUpdaterStorageRouting.java)
executes original instructions against a supplied three-slot RAM registry:

- Three registration controls install drive 2, drive 5, then NAND in distinct
  slots, checking logical mappings and initializer/mount pointers. NAND stops
  before its immediate backend initializer.
- Five synchronous controls check NAND with unset initialization state, drives
  2/5 with unset state, and drives 2/5 with initialized state. NAND reaches its
  mount entry after generic bookkeeping; uninitialized drives 2/5 stop before
  generic initialization; initialized drives 2/5 reach their own mount entries.

All eight pass. Record copying is modeled for exactly 100 bytes. The registry,
record-base global and initialization status are synthetic; registration order
is chosen to exercise mapping, not asserted as the production sequence. The
NAND registration is intentionally left incomplete at its initializer boundary;
later controls reuse the already-installed RAM record, not a successfully
initialized device. No backend initialization, mount callback, filesystem,
MMIO or storage I/O executes. Loaded-image bytes are checked unchanged after
the declared record-base fixture override; the source binary is untouched.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterStorageRouting.java /private/tmp/updater-storage-routing.txt
```

Evidence: [controls](output/updater-storage-routing.txt),
[hash-qualified static ranges](output/updater-storage-routing-static.txt).
The aligned direct B/BL caller scan retained there does not cover indirect calls,
Thumb code or runtime-created references. No new REA or live observation is claimed.

The next offline boundary is the low NAND initializer and the storage-selection
callback it can invoke. Avoiding an explicit update command or a worker mount
request alone does not prevent those earlier startup actions. The camera can
remain unplugged.
