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

## Reserved A/B and SD CID (2026-11-07)

- **Full Reserved A dump: `A:\RO_RES`, not rawC.** 127,664,128
  bytes, SHA-256 `90ebcff3…`. Block 2 `PHYTABLE`; blocks 3–175
  calibration/resource tables; blocks 177–320 resource data (fonts,
  JPEG/PNG, RIFF audio, `FUJIFILM` metadata); blocks 361–467 a
  27.4 MB high-entropy blob. No `RawCBoot`, updater, main-image copy,
  `SPBoot>`, firstburn or DRAM parameters. The main firmware's paths
  (`A:\RO_RES\...`) identify this as the read-only resource volume.
  ([rsv-a-b-content-and-sd-cid.md](rsv-a-b-content-and-sd-cid.md))
- **Full Reserved B dump: `B:\USER_RES` FAT16 volume.** 11,796,480
  bytes, SHA-256 `0dbc49bf…`. 44/45 blocks erased; block 522 is a
  FAT16 volume with root `USER_RES` + `SD_CID.DAT` and files
  `LOG_DSC0.BIN`, `LOG_FMT0.BIN`, `UIDATA.BIN`, `UIBTGPS.BIN`,
  `UIBTRES.BIN`, `UIWPASS.BIN`, empty `RES_JPG`. No boot policy.
- **SD CID binding is satisfied.** `SD_CID.DAT` is a SanDisk
  `SDSK32G` CID (`03 53 44 53 4b 33 32 47 85 74 05 5f a2 01 93 3f`).
  A live shell `sddetect` read the inserted card as the **same CID**,
  CSD `40 0e 00 32 db 79 00 00 ed c8 7f 80 0a 40 40 e5`, SDHC
  30,436 MB, SDR104, 198 MHz, `SD detect ---Pass`. The updater's
  SD-mount failure is therefore not a CID mismatch; it is an
  init/state difference in the updater's SD path.
- **The SD card is a prepared SD file-boot card.** Card-reader
  dump: MBR + one FAT16 `XHALF` partition (1.5 GB), containing
  `MAIN.BIN` (14,910,464 bytes, SHA-256 `80350bd2…` — **the official
  2.00 main firmware**), `EnableTest.txt` (1 byte, `x`), two JPEGs,
  and macOS `.fseventsd` metadata. The last 64 MiB of the card is
  zero. The `eSD RSV`/`NAND RSV` strings in the first 64 MiB are
  inside `MAIN.BIN`, not a standalone header. The updater's
  `D:\MAIN.BIN` payload is present and stock; the blocker is the
  updater's SD mount (card state 0), not the card contents or CID.
  ([rsv-a-b-content-and-sd-cid.md](rsv-a-b-content-and-sd-cid.md))
- **SD reserved header is a live concept but not readable in the
  debug shell.** `sdhdr` prints an `eSD RSV` header with
  FW1/FW2/RSV counts; in this session its global pointer was null
  and the command crashed the shell (data abort at `0x40008bc4`,
  LR `0x40345b1c`). `rsvsdrd` (read SD device-1 reserved blocks)
  crashed the same way (`r4=0`, fault `0x400cc014`, LR `0x400ce3d0`);
  `sdr` (device 2) works but frees its heap buffer before the
  readback, yielding zeros. The device-1 context is created by
  `0x4004e9dc(1)`, reached only via the write commands
  `sdfww`/`sdset` or the normal boot/updater path. Debug-shell
  faults, no writes; power cycle recovers. The lowest-risk next
  evidence is a card-reader dump of the card's first/last regions.
- **RawCBoot is not in A, B or C.** The updater's boot command table
  is decoded (`:RAWCBOOT0..5`, `:DEF_FW1/2/RAWC`, `:MB_FW1/2/RAWC`,
  `:NO_BOOT`); `FUN_4001c78c` reads a 4 KiB block from a raw
  device 1 at offset 0 or one block size. The device table is
  runtime-initialized; the volume remains unresolved. Remaining
  candidates: a hidden logical volume, the C: DCIM volume, or the
  SD card's raw/reserved area.

## Built-in AWB RAW dump (2026-11-08)

- **The stock firmware has a file-gated RAW dump in the AWB capture
  path.** `D:\SaveRaw.txt` on the SD card makes `AWB_CheckRawFile`
  (`0x40203014`) set a RAM flag at `0x41311cd2`. The AWB capture
  paths (`FUN_4049f0c8`/`FUN_4049faa8`) call `FUN_402ce16c`, which
  then calls `FUN_402cdad8` to write
  `D:\DCIM\RAW<width>x<height>_<stride>.RAW` alongside the normal
  capture.
- **Trigger:** the debug shell's `v3a` command (View 3A; table entry
  `0x40e17e48`, handler `0x4060fedc`) case `0x3B` calls
  `AWB_CheckRawFile`. The same 71-entry debug table exposes `cap`
  (`Deb_CaptureStill`), `pvrawsave`, `pvyuvsave`, `pvrawsize`,
  `sensor`, `flash`, and other factory debug hooks.
- **Other RAW hooks are debug dumps:** `pvrawsave` writes
  `D:\INVALID_%04dX%04d_%04d_%04d.RAW` (the `0xff` fallback);
  `FUN_406421f8` also has `SEARCH`/`SHARPNESS`/`BLUR` formats.
  `pvyuvsave` writes `D:\%04d.YUV`. The `v3a` 3A cluster (cases
  `0x28`–`0x35`) is AE/AWB debug, including `D:\MEV.bin`; case
  `0x3B` is the SaveRaw gate.
- **Capture path:** `cap` → `FUN_4043f7d8` → `FUN_4043f618`; the
  capture task `FUN_404a0124` → `FUN_4049ff5c` registers
  `FUN_4049fcb8`/`FUN_4049fe1c` for events `0xe`/`0xf`, which call
  `FUN_4049faa8` → `FUN_402ce16c` → `FUN_402cdad8`.
- **Direct debug controls:** the view/capture table at `0x40e17c80`
  has `raw` (`0x406769a0`, toggles event `0xe` → `ctx+0xec`),
  `yuv` (`0x40676950`, event `0x2c` → `ctx+0xf0`), and `vqawb`
  (`0x402cca48`, whose case `0x28` calls `FUN_402ce1ec(arg1)` and
  sets the SaveRaw flag **without** needing `SaveRaw.txt`). A direct
  sequence is `vqawb 0x28 1` + `raw 1` + `cap`.
- **Format:** stride = width for `param_3==0`, width*2 for `param_3==4`
  (16-bit), width*3/2 otherwise (12-bit packed); the filename records
  width, height and stride.
- This is a stock feature, not a patch. It is the most direct RAW
  lead so far; the next test is `SaveRaw.txt` + `v3a 0x3b` + `cap`
  (or a normal capture). Details:
  [saveraw-awb-raw-path.md](saveraw-awb-raw-path.md).

## Runtime task inventory (2026-11-08)

- **RTOS: ThreadX Cortex-A7/GNU G5.7.5.2.** The image contains the
  Express Logic banner and `ros_thread.c`; the shell exposes
  `os task`, `os stack`, and `os nice`.
- **Task creation:** `FUN_4000c378` (low-level), `FUN_4004118c`
  (named wrapper, 0x1000 minimum stack), `FUN_405f1f04` (named
  wrapper with logging). The first argument is the task name, the
  second the entry function.
- **`os` introspection shell:** `os task`, `os mem`, `os que`,
  `os sem`, `os evt`, `os mut`, `os tmr`, `os stack`, `os all`,
  `os nice <name> <lvl>`, `os prof`, `os proc`, `os dir`. `os task`
  walks the ThreadX list at `DAT_4000628c`; next `+0x88`, name
  `+0x28`, priority `+0x2c`, state `+0x30`, timeout `+0x4c`; print
  `[%p](pri%2u) %-16s[%5s]timeout%ld`; states `Ready`, `Comp`,
  `Term`, `Susp`, `Slee`, `WQu`, `WSem`, `WEvt`, `WBlk`, `WByte`,
  `IO`, `File`, `TCP`, `WMut`, `???`. `os stack` walks the same
  list; `os nice` changes priority by name.
- **Direct tasks:** `AutoFocus Process`, `FastPBEnterViewGroupThread`,
  `PB Effect Thread`, `SlideWindowThread`, `pLoadMainImageThread`,
  `PB_NxNLoadThread`, `PB_ListDateLoadThread`, `PB_NxNDrawThread`,
  `PB_NxNMoveThread`, `PB_FadeEffectThread`, `uvctestcmd`, plus
  dynamic IPC tasks.
- **Named-wrapper tasks:** `Ability WiFi Message Queue Thread`,
  `Ability WiFi EMI Test`, `create_rtos_ipc`/`WiFiMonitor_IPC`.
- **Task families from ~700 strings:** boot/power, sensor/3A,
  preview/capture (`ISR`/`Cap`/`Cdsp`/`IQ`/`Capture`/`JPG`/`Store`),
  RAW/YUV save (`saveRaw_*`, `saveYuv_*`, `savePrv*`), USB
  (`usbControlTask`, `usbTargetTask`, `usbDPCTask`, UVC send tasks),
  storage/mount (`diskMountTask`, `MountThread`), UI/display,
  playback, WiFi/BT/dual-OS.
- **RAW/YUV save task family:** `saveRaw_SaveFrm_Task_Sensor0..3`
  copy frames into a 32 MB (`0x2000000`) staging buffer;
  `saveRaw_SaveFile_Task_Sensor0..3` write
  `%c:\DISP_S0..3_%03d.raw` / `.yuv` in 32 MB chunks, 15 chunks per
  file, then roll the file number. These are display/ISP frame
  dumps, separate from the AWB `RAW...RAW` path. The `%c:` drive
  and raw/YUV choice come from a config block.
  ([runtime-task-inventory.md](runtime-task-inventory.md))

## Bootup reset-to-main (2026-11-08)

- **Reset `0x40000ec0`**: MPIDR, per-core stacks at
  `0x3f010000 - (core<<11)`, early cache/TLB (`FUN_40001180`), MMU
  (`FUN_4000147c`), BSS zero, page table (`FUN_400017c0`),
  **VBAR = `0x40000000`**, core-0 boot path, boot parameter block at
  `0x10000110`, clock/PLL `FUN_4000103c`, jump to `0x400037e8`.
- **RTOS/main entry `0x400037e8`**: reads `0x10000058 & 3`, enables
  clock gates (`FUN_4000b608/b61c/b630`), boot-mode dispatch
  `FUN_401c015c`, GIC/timer (`FUN_4000219c`), TLB/cache
  (`FUN_40001840`), 2 MB heap (`FUN_4001f898`), then the ThreadX
  scheduler (`0x4001f8cc`).
- **Boot mode** is a 3-bit field at `0x1000606c` bits 4–6 plus a
  2-bit sub-mode (bits 0–1). Modes 0/1/2–5 configure clock/power
  domains via `FUN_40080ac8`; sub-mode 3 skips reconfiguration. The
  dispatch writes the mode back, clears `0x10005000+0x305` bit 1,
  and sets boot status `0x10000040 = 4`.
- **Main task**: trampoline `0x40003b40` loads `0x40003b80`
  (app-init vector `0x40003498`) and jumps to the scheduler start
  `0x4001f8cc`. The data at `0x40003b78` is
  `777c0ea8 fe72bddf 40003498`.
- **App init `FUN_40003498`**: clocks/power, HW ID check
  (`0x10000324 == 0xd8`), event group/semaphores
  (`FUN_4000ef40`/`FUN_4000f39c`/`FUN_4000f5b8`), storage/NAND
  (`FUN_4000fb9c`), VFS/FAT (`FUN_4000fd40`), then the
  driver/task/UI/imaging init sequence with boot-stage event bits
  (`FUN_4000f458`/`FUN_4000f51c`, bits `1 << (stage-1)`).
- **Boot-stage event group**: `FUN_4000f004` sets stage bits via
  `FUN_4002a760`; `FUN_4000f458` marks, `FUN_4000f51c` checks/logs,
  `FUN_4000f550` runs the stage callback.
- **Boot-status/layout selection:** `FUN_400007f4` reads the
  halfword at `0x10004054`; `FUN_4000081c` uses bit 1 to select the
  FW1 (`0x3f000014/0x1c`) or FW2 (`0x3f000018/0x20`) DRAM
  parameters and fills the layout struct at `0x40000ad0` with total
  size, base, block size (in 512-byte sectors). Live status
  `0x0100` → FW1 selected, bit 8 set. The SRAM dump at
  `0x3f000000` contains boot-stub code, not the parameter block.
- **`sysAppInit` stages:** `devInit`, `devInit1`, `devInit1 OK`,
  `devInit2`, `devInit2 OK`, `modeswInit`, `sysgRes`,
  `cdspBootInit`, `resDone`, `appInit`, `appInit OK`,
  `appInit 2 OK`. `devInit1` covers ME/MOE/IME/HVC/primary
  display; `devInit2` is `devInitSecondary` (`FUN_4001caf0`);
  `modeswInit` is the audio state (`FUN_400d3a5c`), `sysgRes` the
  audio memory pool (`FUN_400d3c58`), `cdspBootInit` the
  CDSP/audio DSP boot (`FUN_400d3ecc`/`FUN_400d4074`/`FUN_400d4ae4`,
  `0x1000d000`, 4 channels); `appInit` is `FUN_40080d6c` (audio
  hardware, `hal_aud`) + `FUN_400f86f4` (`appInit`, application/UI);
  `appInitDelay` is `FUN_400f8914` (deferred network/WiFi/BT). The
  non-zero `*DAT_4000374c` branch runs `FUN_401efc90` (debug/loader
  path).
- **Secondary-core AMP boot:** `FUN_4000148c` is CPU1's
  `amp_boot_main`. It waits on the mailbox at `0x40004000` for
  `0x200`/`0x201`/`0x208`/`0x20b`, builds ATAGs
  (`ATAG_CORE` `0x54410001`, `ATAG_CMDLINE` `0x54410009`) with
  `console=ttyS0,115200 no_console_suspend=1 mem=... icatmem=...
  sharemem=...`, disables the MMU/caches, and jumps to the
  secondary Linux image. The image base at `0x40e37c5c` is a
  loader-patched placeholder.
- **Staged loader entry `0x40000338` (`+0x200`):** calls
  `FUN_4000081c`, validates the section table from the SRAM-copied
  header (`_UNK_40000798`), copies sections, zeroes BSS, waits for
  `0x10000911 & 0xf0 == 0xe0`, and jumps to the loaded entry.
  Failure paths call the fallback (`_UNK_400007f0`); one branch sets
  `0x1000003c = 1` (reboot).
- **Loader-facing `+0x200` stub (`0x40000200`):** writes
  `0x10005104=0x30`, `0x10000020/0x21=0x1f`, the gate
  `0x10001300=0x12345678`, then `b 0x40000338` (staged loader).
  The updater's equivalent stamps `0x88888888`; the earlier stage
  reads the gate to decide whether to skip automatic file handling.
- **Boot-info handoff contract:** gate `0x10001300`
  (`0x12345678` main / `0x88888888` updater / `0` skip), status
  `0x10004054` (bit 1 FW2, bits 4–6 image class, bit 7 valid),
  button/DRAM `0x100040b4..b8`, mailbox `0x100040b9..c3`, backend
  `0x10000058 & 0xe0`. The updater's `FUN_400009c4` decodes these
  and either card-updates a `.brn` or load-and-boots the file.
- **Clock/power table:** `FUN_40080ac8` uses `(type, mask, value)`
  triples: mode 0 `(1,0x80001100,2),(2,0x80000,0)`; mode 1
  `(1,0x100,0x40),(4,0x60,0)`; modes 2–5
  `(1,0x100,0x80),(2,0x1800000,0)`. `FUN_40058890`/`FUN_4002a760`
  are reference-counted gate managers with pending requests and
  timeouts; the controller pointer at `0x40d65968` and mode array at
  `0x40e66c94` are runtime-initialized.
- **Other clock descriptors:** index 1
  `(1,0xb100,1),(2,0x40000,0)`; index 3
  `(1,0x100,0x20),(4,0x90,0)`; index 5 null; indices 6/7 point
  into an overlapping data blob at `0x40d65a3c`/`0x40d65a48`
  (`0x0f0f0f0f` patterns, byte tables), not a clock-gate list;
  consumed by `FUN_400822c4` (I2S audio config, `hal_aud`).
- Details: [bootup-reset-and-rtos.md](bootup-reset-and-rtos.md).

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
