# Native-feeling RAW: integration levels and investigation plan

Date: 2026-11-05. Strategic synthesis after the first Lightroom-validated
DNG ([full-frame-raw-capture.md](full-frame-raw-capture.md)). No new
camera experiment; boot/recovery gates reference
[ram-launch-and-recovery-roadmap.md](ram-launch-and-recovery-roadmap.md)
(C1–C4) and [firmware-copy-and-fallback.md](firmware-copy-and-fallback.md).

## Where we are

Proven end-to-end: calibration/debug entry → shell → `aeset cap` +
`rcaraw 19` writes a full-frame sensor dump → `raw2dng.py` → DNG that
Lightroom opens with correct orientation/WB and near-correct default
exposure. All effects so far are RAM-only inside the healthy firmware;
nothing has been flashed, and Firmware2 is empty, so there is still no
cold-recovery path.

## Integration levels (pick the target consciously)

| Level | User experience | Persistence | Brick risk | Gated on |
| --- | --- | --- | --- | --- |
| 1. Host ceremony (today) | Debug entry per session; host converts | None | None beyond current practice | — |
| 2. **Session RAW mode** | Enter debug once, then shoot normally for the rest of the boot; RAW appears next to each JPEG on the card | Until power-off; stock after reboot | RAM-only, same class as today | Functionality lane below |
| 3. Persistent mod | Menu/setting toggle, survives reboot, indistinguishable from a factory feature | Flashed firmware | **Real** — a bad image likely bricks (Firmware2 empty, no proven cold entry) | Full boot/safety lane (C1–C4) |

Key point: **boot-procedure knowledge gates Level 3, not Level 2.**
Level 2 never writes internal storage, so the missing boot selector
does not block it. Level 3 must not start until the boot lane closes.

## Safety/boot lane (gates Level 3 only)

1. **Read-only internal-storage dump** — firmware map (both header
   blocks), Firmware1's 57 blocks, reserved areas, via a qualified
   storage path (shell reserved-storage readers or loader). This is
   the C3 backup prerequisite and verifies the DRAM-parsed layout
   against physical reality.
2. **Find the slot selector** — the code that consumes the Firmware1/
   Firmware2 checksum fields (`0x42/0x44`, `0x46/0x48`) before the
   main image runs. Not in `firmware-main.bin`. Candidates, in order:
   the standalone card-updater image (it already implements named
   `:DEF_FW2` loading with map checksum checks — see
   [updater-installed-image-checks.md](updater-installed-image-checks.md));
   staged SRAM remnants (we hold a full SRAM dump and the earlier-stage
   descriptor at `0x3f00c518`); internal-flash/mask-ROM stages, which
   may be unreachable non-invasively (opening the body and UART are
   excluded by project constraints).
3. **Cold-entry conditions** — how the card updater is launched at
   boot (persistent flag? SD file presence? Firmware1 checksum
   invalid?), traced statically. Explicitly **not** tested by damaging
   Firmware1's checksum on hardware.
4. **Package acceptance (C4)** — outer `FPUPDATE.DAT` validation
   fields, proven by a byte-identical no-op reconstruction in the
   emulator before any patched package is contemplated.

If step 2/3 cannot be closed non-invasively, Level 3 stays closed and
that is an acceptable final answer.

## Functionality lane (gates Level 2)

5. **Normal still-capture trace (offline)** — shutter → still mode
   (`0x11`) pipeline → `snapYuv2ExifJpgWrite`; locate where the raw
   buffer is alive, whether the `snap attr raw=1` state produces
   RAW+JPEG outside the calibration personality (our captures entered
   `OpenCalib50Mode` because calibration state was active), and
   whether calibration state can be exited within the same boot while
   keeping the effect.
6. **A persistent-in-boot effect, not a continuous hijack** — our
   proven code execution fires in USB-loader dispatch context. For
   shooting, the right shape is a small RAM patch (the variable/config
   backing the raw attribute or capture config), applied during the
   debug entry, after which the loader is left alone and the user
   shoots normally. Patch target identified by step 5; emulator-
   qualified before any live use, as with `rcaraw` syntax.
7. **File integration** — write `DSCF####.DNG` (or `.RAW` + sidecar
   conversion) next to the JPEG in `DCIM/101_FUJI`, reusing the JPEG's
   file number; camera playback will ignore the DNG (acceptable —
   Lightroom pairs by name); respect card-space and write-completion
   semantics of `icatImgFile`.
8. **Metadata quality** — orientation (done, tag 8), WB from a
   gray-card capture in a future session (companion-JPEG fitting is
   disproven: not the same frame), ColorMatrix from a color checker
   if available, BaselineExposure fine-tune (currently −1 EV,
   slightly hot).

> **Sequencing update (2026-11-05):** the user has reprioritized —
> RAM-pushed firmware / SD dual boot first, then a confirmed recovery
> path. That plan now lives in
> [ram-push-sd-boot-plan.md](ram-push-sd-boot-plan.md) and takes
> precedence; the Level-2 lane below is parked, not cancelled.

## Recommended sequence

- **Phase A (offline):** steps 5, 2, 7-design. No camera.
- **Phase B (next live sessions):** gray-card WB capture; test the
  Level-2 hypothesis end-to-end in one boot (calibration entry → set
  raw state / candidate patch → exit to normal shooting → shutter →
  inspect card). Even a negative result localizes the calibration
  coupling.
- **Phase C:** Level-2 prototype hardening (repeatability, exit
  cleanliness, normal-boot sanity after each session, as now).
- **Phase D (only if Phase A step 2 yields selector evidence):**
  storage backup (step 1), then re-evaluate whether Level 3 can ever
  be justified. Default answer remains no.
