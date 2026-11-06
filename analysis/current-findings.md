# Current findings: Fujifilm X half firmware playground

Date: 2026-11-05 (night). Consolidation of the RAM-push,
SD-boot, and recovery investigation. Detail lives in the linked
documents; this file is the state of record. No internal storage
was written at any point in this project; every live experiment
recovered by power cycle, and the camera is on stock firmware.

Objective: prove RAM-pushed firmware and/or SD dual boot, then a
confirmed recovery path. RAW support is the longer-term goal;
session RAW capture already works (calibration shell → DNG,
Lightroom-validated, `analysis/raw2dng.py`).

## What is proven

- **Loader access.** Calibration/debug entry, USB loader,
  DRAM/SRAM read/write, shell, storage reads, live boot-info:
  gate `0x12345678` stamped, Firmware1 selected (status
  `0x0100`). Firmware1 SHA-256 `80350bd2…` matches the official
  package. ([boot-info-block-and-updater-sdboot.md](boot-info-block-and-updater-sdboot.md))
- **RAM-pushed firmware works — for Fujifilm's own updater.**
  The stock card updater (666,112 bytes, SHA-256 `4bdcdb06…`)
  has booted from RAM twice via the `0x04f7` handoff and
  reached its USB service personality. Exit is OFF/unplug;
  normal boot verified afterwards.
  ([ram-push-live.md](ram-push-live.md))
- **Arbitrary invocation from the USB context.** Trampolines
  v4/v5 (emulator-qualified) call a chosen function from the
  handler hijack; the direct handoff reached the helper
  `FUN_401eb1ec` live (device dropped off USB at the trigger).
- **Recovery for everything attempted:** power cycle. Internal
  stock firmware is untouched by any RAM/SD path, so a normal
  boot always returns. This is a confirmed recovery path for
  the dual-boot architecture *if* a payload ever boots.

## The central blocker: state-6 preparation is a lottery

The `0x04f7` handoff (state 6, `FUN_401e1f40`) first runs a
preparation chain in the USB receiver task: the mode-`0x88`
park callback, the op-10 storage header read, then the helper.
The park is an SP5K mode-manager transition with three
timeout-abort points (resource acquire 30 s, request post
60 s, ready-flag poll ~20 s); any expiry assert-aborts the
receiver task silently. Two further hangs occurred with the
park skipped, in the storage read or the helper's early
acquisitions. Which point fires in a given attempt is
unknowable afterwards (the reporting task is the one that
dies). Completion therefore depends on system state at push
time — other tasks, mutexes, secondary cores — not on the
payload, which is hash-verified identical every time.

**Live tally for state 6: 2 completions in 9 attempts**
(updater payload 2/5, main image 0/4). Every stall has the
same signature: setup accepted, device still enumerated,
control dead, screen unchanged — failure in preparation,
before the stub jumps. ([state6-hang-analysis.md](state6-hang-analysis.md))

## Bypasses, each closed with evidence

- **Direct helper call (skip park + refresh):** reached the
  helper; the updater never came up, black screen. Something
  state 6 prepares is load-bearing.
- **SRAM boot-block refresh (op 10): eliminated as the cause.**
  The updater's startup reads nothing data-dependent from the
  refreshed range `0x3f000000–0x3f004000`; its only reader (the
  DRAM-parameter restore in `FUN_4000125c`) is skipped when the
  gate is stamped, and the handoff stamps it. Replicating the
  refresh is closed without a camera session.
  ([updater-sram-startup-q1.md](updater-sram-startup-q1.md))
- **Park from the hijack context:** deadlocks. The park is a
  modeSw *task* service (mutex, queue post, sleep-poll, ready
  flag at `0x40f80d28+0x34`); handler-context code cannot drive
  it (live result, one hang, recovered).
- **Shell-issued park:** does not exist. `clbt` is
  calibration-data commands; shell mode switches are capture
  side effects that leave the calibration personality.
- **Trampoline quiesce:** not credible. The helper already does
  config bytes, cache/MMU preparation, `cpsid i`, and GIC
  disable — but stops no task, core, or DMA master. The park's
  gap is secondary cores executing old text (and armed DMA)
  while the stub overwrites `0x40000000`. No in-firmware
  core-park primitive exists for a trampoline to invoke.
  ([quiesce-trace.md](quiesce-trace.md))

A costly detour is worth recording: several mid-day failures
were caused by a truncated trampoline embedded in the client
(188 of 192 bytes, one instruction word lost), not by the
camera or transport. Fixed in `dec0561`; the client now
refuses to write a trampoline whose SHA-256 does not match the
emulator-qualified binary.

## SD boot: well-formed, never yet executed

The chain is: RAM-push the updater → updater's consumer finds
a mailbox filename in boot-info registers → `FUN_4000f270`
loads that file from SD to `0x40000000` and boots it.

- **The loader accepts a raw main image.** Emulator execution
  of `FUN_4000f270("D:\MAIN.BIN", 0)` with the real
  `firmware-main.bin` reaches boot-task creation (size
  `0xe38400`, dest `0x40000000`); the plain-file path performs
  no content validation. File format is eliminated.
  ([sdboot-diagnosis.md](sdboot-diagnosis.md),
  [output/sdboot-loader-emulator.txt](output/sdboot-loader-emulator.txt))
- **Leading hypothesis for the original P3 failure:** the
  mailbox (`0x100040b9`, volatile registers) was set, the
  first push hung, and the recovery power cycle cleared it
  before the successful retry push — so the updater used the
  default `D:\firmware.brn`, found none, and took its service
  continuation. The record does not show a re-set in the retry
  boot.
- **Two corrected attempts (2026-11-05 night)** set and
  verified the mailbox (`MAIN.BIN`) in the same boot as the
  push, with the card verified byte-for-byte beforehand
  (FAT32, `MAIN.BIN` = `80350bd2…`, `EnableTest.txt` present,
  no `firmware.brn`). Both hung in state 6 before the updater
  ran. The mailbox hypothesis therefore remains **untested**:
  SD boot has still never executed under known-good inputs.

## What remains open

| Item | State |
| --- | --- |
| SD boot of stock main image | Untested under known-good inputs; blocked by state-6 lottery |
| RAM-pushed main image (stock or modified) | 0/4 via state 6; direct path lacks quiesce |
| Modified firmware by any route | No proven path |
| Flashed mod + cold recovery | Closed: ROM/FRM cold entry never established, so no recovery would exist |
| Mode `0x88` stop list, helper indirects | Partially traced; do not change any conclusion |

## Bottom line

RAM-push works for Fujifilm's updater; getting our own main
image running — by RAM, SD, or flash — has no proven path.
The blockers are identified rather than mysterious:
preparation intermittency (state 6), quiesce that only a
task-level service can perform, and an SD test that has yet to
get its updater running. The working deliverable remains
session RAW capture through the calibration shell.
