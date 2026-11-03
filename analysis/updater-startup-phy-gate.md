# The skip-storage gate also bypasses identified USB PHY acquisition

Date: 2026-11-03. Offline original-ARM inspection and local Ghidra controls
of official 2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses belong to this updater. No camera access or firmware changes.

## Finding

Follow-up: [retail handoff USB state](main-handoff-usb-state.md) finds that
the inspected main preparation prefix and a separate controller helper preserve
supplied PHY/resource state. Four controls pass, but the earlier optional
callback and complete copy/execute transition remain unqualified.

The gate-zero path through startup consumer `0x400009c4` bypasses not only
its storage/file handling, but also the identified USB PHY/resource acquisition
helper `0x40075ea4`. That helper runs on the separate file-action return path
at `0x40001094`. Four bounded controls execute the original consumer together
with that original resource-acquisition code and confirm the distinction.

Thus the software path that avoids storage is not by itself a demonstrated
physical USB startup procedure. This does **not** prove USB is disabled on
that path: earlier initialization or inherited hardware state could supply
resources. Those remain unmeasured. The claim is about an identified call
being skipped, not the electrical state of the camera.

The [USB reset exchange](updater-usb-reset-exchange.md) can complete under
supplied state without a storage call. Its success does not fill this separate
physical-resource gap.

## The two routes through the consumer

| Supplied condition | Original path | Identified PHY/resource acquisition |
| --- | --- | --- |
| Gate word `0x10001300 == 0` | `0x400009d8` branches to `0x400010c0`, calling `0x40069374` | Skipped inside this consumer |
| Gate nonzero, existing registry, drive activation succeeds, file check reports missing | File-check error joins `0x40001094`, calls acquisition, performs register sequence, then reaches `0x400010c0` | Executed |

The second row deliberately includes supplied dependencies. Drive activation
can have storage side effects; the controls model its return and do not run
it. A missing file is **not** proposed as a safe way to launch USB: storage
initialization or mounting can precede the file check in real execution.

Original helper `0x40075ea4`:

1. Calls `0x40075dd8(1)`, with PHY reference count at `0x400ba450`.
2. Acquires resource bits 9/19 through `0x400662b0(0x80200, 0x80200)`.
3. Writes `0x3f` to USB peripheral byte `0x10005021`.

At count zero the subhelper performs the previously identified timed register
sequence at `0x10000380`/`0x10000383`; at a nonzero count it increments the
count without reprogramming those registers. Details and eight earlier
controls are in [USB resource acquisition](updater-usb-resource-bringup.md).
The new controls connect that helper to the original consumer rather than
modeling it wholesale as earlier return-flow controls did.

The startup RAM-clear interval `[0x400a2980,0x400cb980)` includes the software
PHY counter and generic resource counters. It does not by itself establish
that peripheral clocks/PHY hardware are off, nor that existing hardware users
are absent. Software counts and physical state must not be equated without
following the full entry path.

## Bounded search for other acquisition sites

An aligned direct B/BL scan of the complete updater finds:

- `0x40001094 -> 0x40075ea4`.
- `0x40075eac -> 0x40075dd8`.
- No additional direct B/BL callers of those helpers or companion
  `0x40075ed4` in this scan.

An exact aligned pointer-literal scan finds no words equal to either
acquisition helper address. This does not exclude computed pointers.

A 256-instruction backward constant-resolution search for direct stores into
`[0x10000380,0x10000388)` finds seven candidates. Original disassembly confirms
all seven belong to the known `0x40075dd8` helper: six acquisition stores and
one release store. No new candidate was recovered by this particular search.

This is **not** an exhaustive writer inventory. The scanner can miss writes
through unresolved pointers, branches/register transfers, Thumb, DMA, bulk
helpers or earlier code. Its unrelated main-image bulk-helper profile was
excluded from this updater result. These negative searches cannot prove that
no earlier initializer programs the PHY or a generic resource API.

Earlier startup `0x400013cc` calls platform/clock/timer/interrupt setup before
scheduling `0x4000125c`. Partial inspection of those helpers does not establish
a complete USB hardware dependency graph. No blanket read-only or no-USB-write
classification is assigned to their transitive/indirect callees here.

## Four original-instruction controls

[InspectUpdaterStartupPhyGate.java](scripts/InspectUpdaterStartupPhyGate.java)
combines gate 0/1 with an initial PHY reference count 0/2. Each starts at the
original consumer and stops before `0x40069374`:

| Gate | Initial PHY count | Acquisition calls | Final count | PHY sequence |
| --- | --- | --- | --- | --- |
| 0 | 0 | 0 | 0 | None |
| 0 | 2 | 0 | 2 | None |
| 1 | 0 | 1 | 1 | Original six-store sequence and six delay calls |
| 1 | 2 | 1 | 3 | No reprogramming; original count increment |

All four pass. Generic resource counts start at zero: the nonzero-gate cases
acquire both selected resources and write command mask `0x80200`; the zero-gate
cases preserve supplied command-register and controller-byte sentinels. The
existing registry-count getter executes original code against supplied count 1.
A chosen `D:\TEST.bin` mailbox and supplied missing-file result select the
nonzero-gate branch; those are fixtures, not card-preparation instructions.

Modeled helpers: configuration setters, drive activation, file check,
16-byte copy, logging, interrupt-mask/delay helpers, and `0x400874e8`.
The original consumer, PHY helper, resource manager and register stores execute
against synthetic memory. The platform continuation, backend initialization,
mount, filesystem, real clocks/PHY, USB, scheduler and camera never execute.
Loaded-image bytes are checked unchanged; source files are untouched.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterStartupPhyGate.java /private/tmp/updater-startup-phy-gate.txt
```

Evidence: [four controls](output/updater-startup-phy-gate.txt),
[static ranges/callers](output/updater-startup-phy-gate-static.txt),
[bounded store candidates and limitations](output/updater-phy-store-candidates.json).
No new REA or live observation is claimed.

The practical boundary is now more precise: storage isolation, initialized
software state and physical USB resource setup are distinct requirements.
None supplies the still-missing safe retail entry or independent cold recovery.
Further progress should follow the actual loader's inherited hardware/platform
state, rather than assuming that a zero startup gate is sufficient. Camera
reconnection is not needed for these findings.
