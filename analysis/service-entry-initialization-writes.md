# Service entry: initialization saves cold data before automatic calibration

Date: 2026-11-01. Offline static inspection and original ARM emulation only.
No camera access, button experiment, SD marker, live write or firmware change.
Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7 little-endian runtime base `0x40000000`.

## Finding and correction to the earlier risk picture

The startup path does not merely risk writes if an automatic calibration
worker runs. `InitClbtData` (`0x405daa2c`) itself reaches a whole-buffer
`COLDDATA.BIN` save attempt, independently of `FgAutoClbt` being enabled.

The direct purpose is routine version bookkeeping: three fields labelled
`Hce version info` receive 2, 0, 6 in this build. This is not evidence that
initialization necessarily overwrites measured lens/sensor calibration values
or flashes firmware. Nevertheless, it writes the same 1,024-byte factory-data
file, rather than only those three fields on disk. A failed reload can leave
stale shared RAM as the source of that save attempt.

This corrects the narrower impression that disabling automatic calibration
would make entry read-only. The earlier conditional worker hazards remain;
they are additional to this initialization write. Successful persistence on
hardware has not been observed. All six new offline controls passed.

## Ordering relative to USB and automatic work

The inspected `EnterCalibrationMode` path (`0x405dd4f0`) has this order:

| Call site / range | Action and scope |
| --- | --- |
| `0x405dd564..0x405dd59c` | If GPIO `0x1000904c` bit 5 is set, call `MountStoreMemory(0)`; nonzero result logs `Mount NAND error` and calls `0x405aee70(0, ...)` |
| `0x405dd5a0` | `0x405dadb8`: conditional storage selection/folder creation, previously traced to `vfsMkdir` |
| `0x405dd5c4` | `0x40126800(0, boot_byte_1a)`: disable RTC wakeup; traced below |
| `0x405dd660..0x405dd680` | A display-status condition can queue `0xf2000083` and return before the later USB selection |
| `0x405dd6a4..0x405dd6dc` | Inspect UVC selector; zero branch selects API mode 4 at `0x405dd6cc` and connects USB at `0x405dd6d4` |
| `0x405dd724` | Call `InitClbtData`; this contains the new cold-data save attempt |
| `0x405dd79c` | Store UI state `0xe000` |
| `0x405dd7c4` onward | Later special-boot/automatic-work branches; ordinary scheduling calls `0x4051d918` at `0x405dd964` |

Thus an entry that continues through ordinary initialization can attempt the
save **after connecting USB but before the later automatic-work decision and
before setting the calibration UI state**. Earlier calls/branches must return
for that path to continue; not every attempted entry is guaranteed to reach
it. The early-return branch is not a demonstrated loader-only escape.
No end-to-end timing or host-command race has been qualified.

The mount-failure helper `0x405aee70` calls `0x40536348(1)` for input zero.
That routine uses `sp5kDcfFsInit`, `sp5kDcfFsFileOperate` selector 9, DCF
activation and status helpers. These names are supported by its diagnostics.
Exact on-media effects remain unaudited; this trace does **not** establish
automatic formatting merely from the mount-error message or helper name.
Folder creation and remount work are distinct from cold-data bookkeeping.

## Exact initialization save chain

```text
EnterCalibrationMode
  405dd724 -> InitClbtData (405daa2c)
    405daa5c -> 40125d98 -> 40125d94: reload shared cold buffer
    ... selection, UI/table initialization ...
    405dad24 -> 400de164: returns 2
    405dad2c -> 400de16c: returns 0
    405dad34 -> 400de174: returns 6
    405dad44 -> 40518c10:
      store halfwords at cold +4, +6, +8
      tail-call 40125e4c(path, 0, 1024, cold_buffer)
```

The cold buffer is `0x40e4df02`; the destination is
`A:\RO_RES\COLDDATA.BIN`. Getter routines are constant-return leaves.
The diagnostic following the save says `Hce version info`, not camera
firmware version; 2/0/6 must not be relabelled as the X-half release number.

`FgAutoClbt` is checked earlier in `InitClbtData` to choose an item; that
branch rejoins at `0x405daaf4`. It does not guard the save call at
`0x405dad44`. No comparison skips the save when these version fields already
match. The reload return status is not checked before this continuation.
The generic writer's checksum and lower file-write behavior are covered in
[cold-buffer lifetime](cold-buffer-lifetime.md).

### Four original-code save-boundary controls

The new script starts at the actual `InitClbtData` entry, not an interior
version-store slice. It executes the original selection, sorting, runtime-table
updates, constant getters and version-store helper. Battery query, six named
display/capture callees, print, stack-only zeroing, and file read are modeled.
Every other unexpected PC fails. It stops **before** `0x40125e4c` executes.

| Synthetic `FgAutoClbt` | Modeled reload | Observed save boundary source |
| --- | --- | --- |
| 0 | Success | File fixture with version halfwords replaced by 2/0/6 |
| 0 | Failure, returns 1 without replacing RAM | Existing RAM fixture with version halfwords replaced by 2/0/6 |
| 8 | Success | File fixture with version halfwords replaced by 2/0/6 |
| 8 | Failure, returns 1 without replacing RAM | Existing RAM fixture with version halfwords replaced by 2/0/6 |

Each case verifies exactly one reload, destination path, offset zero, length
1,024, source pointer, and the entire resulting cold buffer. A sentinel at
`+0x300` distinguishes successful reload (`0xaa`) from stale RAM (`0x55`).
The cached USB-selection flag remains unchanged. The test does not execute
the save/checksum helper, actual filesystem or calibration worker. A failed
read followed by a save attempt is not proof that a subsequent real write
would succeed or that corruption happened on the camera.

## Pre-USB RTC wakeup disable

Call `0x405dd5c4` always supplies enable argument zero to `0x40126800` on
the inspected path. The routine logs `Disable gsBoot.bRtcPwrOn` and:

1. Clears runtime byte `0x40e6bc24` (`boot structure +0x14`).
2. Calls `0x40041d14(0, ..., supplied_delay)` at `0x40126870`.
3. That zero-enable branch calls `0x40041550(2, 0)`.
4. The null-data path requests RTC register writes `0xd0=0`, then `0xc0=0`
   through `0x40057484`, and clears runtime byte `0x40e4ccc8`.

The register writer accesses the RTC peripheral at `0x10000980..0x1000098a`.
This is wakeup/alarm control, not the separate factory reboot-flag record
write or a demonstrated calibration-data update. The precise retention of
these register effects across power loss has not been established here.

Two controls execute the original zero-enable chain with delays 0 and 255,
supplied RTC initialization-ready state, and modeled lock/IRQ/unlock helpers.
The RTC writer is **intercepted**, recording both requested register/value
pairs and returning success without touching MMIO. Both produce the same
pair list and exactly the expected two runtime-byte changes across all
image/global RAM. No actual RTC write or initialization sequence was run.

## Reproduction and remaining qualification

Script: [ReproduceServiceEntryWrites.java](scripts/ReproduceServiceEntryWrites.java).
Results: [service-entry-writes.txt](output/service-entry-writes.txt).
The script verifies the original image hash before any execution.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceServiceEntryWrites.java /private/tmp/service-entry-writes.txt

python3 analysis/fw.py disasm 0x405dd4f0 0x405dd748
python3 analysis/fw.py disasm 0x405daa2c 0x405dad90
python3 analysis/fw.py disasm 0x40518c10 0x40518c38
python3 analysis/fw.py disasm 0x40126800 0x4012689c
python3 analysis/fw.py disasm 0x40041d14 0x40041da4
python3 analysis/fw.py disasm 0x40041550 0x400415b8
```

This is a partial audit of entry and initialization, not a complete safety
proof. Remaining unknowns include transitive capture/display callbacks and
hardware setup, mount-error filesystem effects, live factory values and read
freshness, worker suppression, shutdown/restart behavior and independent
recovery. The previously reproduced automatic confirmation can still reach
calibration workers when saved flags permit it. Disabling auto-calibration
alone does not remove the initialization commit found here.

The existing local Ghidra ARMv7 project and original-image disassembly were
reused after the documented REA raw-image mapping limitation. No new REA
runtime or device observation is claimed. No live button/marker test is
qualified by this pass.
