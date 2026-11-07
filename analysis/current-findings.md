# Current findings: Fujifilm X half firmware playground

Date: 2026-11-05 (night); corrected 2026-11-06. Consolidation of
the RAM-push, SD-boot, and recovery investigation. Detail lives in
the linked documents; this file is the state of record. No
persistent change has been observed: every live experiment
recovered by power cycle and the camera still boots the same stock
firmware hash. That is an observation, not a proof that the
updater's storage initialization performed no write; the risk
assessment records a conditional NAND erase branch and does not
treat stock-updater RAM execution as proven write-free
([brick-risk-and-recovery.md](brick-risk-and-recovery.md)).

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

**Live tally for state 6: 7 completions in 15 attempts**
(updater payload 7/11, main image 0/4). Every stall has the
same signature: setup accepted, device still enumerated,
control dead, screen unchanged — failure in preparation,
before the stub jumps. ([state6-hang-analysis.md](state6-hang-analysis.md))

Caveat added 2026-11-06: the "before the stub jumps" part is an
inference from enumeration and control timeouts, not a measured
payload entry. The host-side observer logs for the later
main-image pushes show termination/arrival events at 15–20 s;
they may be VM passthrough events, but the interface count and
descriptor after the event were never captured. Until that is
done, treat main-image 0/4 as "did not come up", not as "never
ran". The updater's 7/11 and the main image's 0/4 may not share
one failure mode.

One more unproven variable, recorded 2026-11-06: the updater push
that hung that day ran after the camera was already in debug mode
and was attached to the VM with `utmctl usb connect`. The 19:31
successful run likely had the passthrough attached before/at boot.
The post-boot attach may reset the USB/receiver state in a way that
makes the state-6 park hang. The next attempt (same day) had the
camera auto-attached at boot and **succeeded** — the updater
enumerated with its 3-interface service personality. That is one
data point in favour of the attach-order hypothesis, not a
conclusion. The next attempt should keep the passthrough attached
before/at boot.

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
- **The original mailbox hypothesis is falsified.** The earlier
  P3 retry at 19:31 wrote and verified `MAIN.BIN` in the same
  boot as a successful `0x04f7` handoff; the updater
  re-enumerated as its service personality and settled into
  service mode. That is the construction the hypothesis required,
  and the file boot still did not take. The remaining candidates
  were the drive-5/backend branch, the updater's storage
  initialization, SD mount/FAT, and the file-boot path; the live
  read below eliminated the backend branch, leaving the storage
  init, SD1 mount/FAT, and the file boot.
  ([sdboot-diagnosis.md](sdboot-diagnosis.md))
- **Drive-letter/backend correction.** The drive-5 fallback, on
  success, rewrites the pathname prefix to `G` at
  `0x400010c8..0x400010d8`; it does not still check `D:`. The
  live read (2026-11-06) is `0x10000058 = 0x0000`, backend
  `0x00`, so this unit takes the drive-2 path. The drive-5
  branch only runs if drive 2 fails, and `G:` is a different
  logical drive. Live-fixture controls confirm backend `0x80`
  would have no drive-2 fallback, but that is not this unit's
  value. The remaining P3 candidates are the updater's storage
  init, the SD1 mount, the FAT stack, the file open, and the
  boot handoff. ([updater-sd-path-backend.md](updater-sd-path-backend.md))
- **Two later corrected attempts (2026-11-05 night)** set and
  verified the mailbox in the same boot as the push, but both
  hung in state 6 before the updater ran. They add no evidence
  either way; the 19:31 run is the known-good-input test and it
  failed downstream. SD boot has therefore been attempted under
  known-good inputs, but has not executed.
- **The updater ran again on 2026-11-06** (3-interface service
  personality, descriptor hash `f0100172…`). The read-only
  `0x04f2` probe of `0x40000000` timed out: the service does not
  speak the loader read dialect. The read-only debug-port idea is
  closed; the next P3 diagnostic is the card/FAT test, not another
  read attempt. ([updater-service-read-probe.md](updater-service-read-probe.md))
- **Card/FAT test (2026-11-06) failed.** A 1.5 GB FAT16 volume
  (MBR, 32 KB clusters) with `EnableTest.txt` + `MAIN.BIN` at the
  root was tested; the push succeeded (3-interface updater), but
  the updater still settled into service mode and never booted the
  main image. A 20 s EP 0x84 drain captured 0 packets. Card
  format/volume is eliminated; the remaining candidates are the
  updater's SD driver/controller state after the RAM handoff, the
  live storage init, the file open, and the boot handoff.
  ([sdboot-diagnosis.md](sdboot-diagnosis.md))
- **SD re-mount patch (2026-11-06) failed.** The updater payload
  was patched to call `0x4001f1d0(2,0)` (`sdReMount`/`sdReDetect`)
  before the mount wrapper, with a 32-byte stub in the loader
  scratch gap. The patched payload read back clean (`sha256
  6f11c808…`), the stub was verified, and `0x04f7` was accepted;
  the updater still settled into service mode after 90 s. The
  branch/stub executed, so the re-mount returned but did not make
  the SD mount succeed. **The re-mount hypothesis is not
  sufficient.** The remaining suspects are the deeper SD
  controller/CMD state or the SDHC card protocol.
  ([updater-sd-mount-trace.md](updater-sd-mount-trace.md))
- **SD init/config patch (2026-11-06) failed.** A follow-up
  `--sd-init-first` patch called the full SD init
  `0x4006ed88(0)`, including the `0x4007155c(0)` controller
  clock/bus-width configuration, before the mount wrapper. The
  patched push succeeded and the updater ran, but it still settled
  into service mode after 90 s. The init/config is not the missing
  piece; the remaining path is the SD command/response state
  (`0x10004364`/`0x10004365`, `0x4007284c`/`0x40072914`) or the
  SDHC protocol.
- **Live SD status (2026-11-06): the card state is 0, not ready.**
  The read-only `--stage-bootinfo` captured
  `0x10004364=0x15` (bit5 clear), `0x10004365=0x00` (low nibble 0)
  and `0x1000436a=0x00`. The config registers are plausible
  (`0x10004360=0x40`, `0x10004362=0x0200`, `0x10004165/185=0x10`),
  but the main firmware leaves the controller card state at 0. The
  updater's `0x400715b4` ready gate treats low nibble 0 as
  not-ready and returns before the command sequence; `0x40072914`
  then blocks on a completion that never posts. The mount failure
  is the ready-gate state, not the controller config. Next: patch
  the `0x400715c8` no-card branch, or bypass the updater with the
  main firmware's shell `read` path.
  ([updater-sd-mount-trace.md](updater-sd-mount-trace.md))
- **SD ready-gate patch (2026-11-06) failed.** A `--sd-ready-gate`
  payload NOP'd the low-nibble-0 no-card branch at `0x400715c8`;
  the patched push succeeded (`sha256 363950f1…`, read-back
  verified, `0x04f7` accepted) but the updater still settled into
  service mode after 90 s. The gate is not the mount path's gate,
  or the `0x40072914` wait still blocks before the command sequence
  completes. The mount path likely uses the driver state field
  `0x400ba140+id*4+0x120` rather than the hardware `0x10004365`
  ready gate. The cheap software gates are exhausted; the
  remaining SD-adjacent path is the main firmware shell `read`
  architecture, or a pivot to Level 2 RAW.

## Earlier stage / storage (2026-11-06)

- **`0x3f00c000` is the SPBoot USB descriptor block, not a register
  script.** The SRAM page contains the `SPBoot>` prompt, a
  `2aad:6500` device descriptor, a 167-byte configuration with
  3 interfaces, interface 5 (`0x82/0x03/0x84`), and the
  `iCatch  CA6500A` product string. The same descriptor layout is
  used by the updater's stored template (`0x4009da30`). The block is
  not in the package, main image, updater, or probed NAND; it is
  likely ROM/earlier-stage resident. This is a new cold recovery/ISP
  lead: the earlier stage has a vendor USB personality the FRM host
  tool could talk to. Entry conditions and command protocol remain
  unqualified. ([storage-and-spboot-review.md](storage-and-spboot-review.md))
- **NAND header parsed.** Two byte-identical 256 KiB header copies;
  Firmware1 length is in 256-byte units (`0xE384 * 0x100 =
  0xE38400`); reserved counts 487/45/261; FW1 start 795, blocks 57;
  FW2 start 2048, length/blocks 0; checksums `0x3828/0x3FA3`. The
  header table area (`0x62..0x8f8`) and the `0x6FA`/`0x5859` fields
  are not decoded.
- **Storage descriptor parsed.** The 36-byte block at `0x3f00c518`
  has block size `0x40000` at `+0x18` and the recognized NAND
  primitives `0x4654`/`0x421c` at `+0x1c/+0x20`; the first `0x18`
  bytes are a packed geometry header whose field map is unresolved.
- **Updater residency in reserved C is closed negative (2026-11-06).**
  The full C range was dumped read-only: 68,419,584 bytes,
  SHA-256 `a0cdbeeb…`. Content: block 534 `PHYTABLE`; blocks
  535–693 erased; block 694 `firstburn` manifest; blocks 695–747
  firstburn Linux payload (13.9 MB); blocks 748–794 erased. No
  updater image, main-image copy, `RawCBoot`, `:DEF_FW`, `SPBoot>`,
  `DRAM CODE`, or `DRAMPAR3` anywhere in the 65.25 MiB. The updater
  must arrive via SD or the FRM/ISP host flow; there is no resident
  updater in C for a ROM→updater cold path. The firstburn region is
  13.9 MB, not the earlier 13–24 MB estimate.
  ([storage-dump-qualification.md](storage-dump-qualification.md))
- **RawCBoot is not in C.** The getter `0x4001c78c` reads a logical
  storage volume at offset 0 or one block size; the magic is absent
  from C, so the rawC volume is likely in reserved A or another
  logical area. A full A dump (121.75 MB) or a targeted rawC-volume
  read is the next storage step.

## What remains open

| Item | State |
| --- | --- |
| SD boot of stock main image | Updater ran with the mailbox set (19:31 and 2026-11-06) but did not boot the file; mailbox eliminated. Live backend is `0x00` (drive 2), so the drive-5 branch is eliminated too. `0x04f2` read probe timed out; 1.5 GB FAT16 card test still settled into service mode, so card format/volume is eliminated. The `sdReMount(2,0)` pre-call, the full SD init/config (`0x4006ed88(0)` + `0x4007155c(0)`), and the `0x400715c8` ready-gate NOP all failed. Live status shows `0x10004365=0x00` (card state 0); the mount path likely uses the driver state field, not the hardware gate. Remaining: the main firmware shell `read` architecture, or Level 2 RAW pivot; SDHC protocol is secondary. Retries blocked by state-6 prep |
| RAM-pushed main image (stock or modified) | 0/4 via state 6; direct path lacks quiesce. Post-event descriptor not captured, so prep-hang vs post-handoff crash is unresolved |
| Modified firmware by any route | No proven path |
| Flashed mod + cold recovery | No proven recovery. New lead: the earlier stage's SPBoot `2aad:6500` USB descriptor block in SRAM suggests a host-assisted cold/ISP entry; entry conditions and protocol are unqualified. Firmware2 empty, slot selector unknown |
| Mode `0x88` stop list, helper indirects | Partially traced; do not change any conclusion |

## Bottom line

RAM-push works for Fujifilm's updater; getting our own main
image running — by RAM, SD, or flash — has no proven path.
The blockers are identified rather than mysterious:
preparation intermittency (state 6), an SD file-boot path that
fails after the updater runs (the mailbox is no longer a
candidate), and a main-image push whose post-handoff state is
not yet instrumented. Reserved C is fully mapped (`PHYTABLE` head
plus firstburn Linux only; no resident updater, main-image copy, or
RawCBoot), and the earlier stage has a `2aad:6500` SPBoot USB
template — a recovery lead, not a proven entry. The working
deliverable remains session RAW capture through the calibration
shell.
