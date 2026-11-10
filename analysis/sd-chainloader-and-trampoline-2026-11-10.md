# SD chainloader and trampoline stage: live negative results

Date: 2026-11-10. Live loader-mode experiments with the restored FAT32
card and the 512-byte chainloader stage. Every attempt recovered by a
power cycle (battery pull where the switch did not respond). The
camera's NAND was not written.

## What was attempted

1. **SD path (`sdset` / `sdfww` / `sdboot`).**
   - The 32 GB SDHC card fails the loader's SD speed switch:
     `[sdSpeedModeSet]: not allowed 3->0`, `SD Speed Switch Fail!`,
     `sdReDetect err retry left:2/1/0`, then `eSD header fail`.
   - `sdfww D:\MAIN.BIN` previously failed reading the 14.9 MB file at
     CMD18. A 512-byte `STAGE.BIN` read was not reached because `sdset`
     stalls in the same SD re-detect.
   - The card is a poor match for the loader's SD driver at high speed;
     the shell SD write/read path is not usable for the chainloader.

2. **Handler trampoline (TRAMP5) stage.**
   - `trampoline v5 installed (idle)` and read-back succeeded; the
     handler slots held `0x401e1280` before patching.
   - A `bx lr` probe at `0x41180000` and at `0x41100200` never returned
     an observable `DONE=3`; the bootquery trigger immediately killed
     the control path (`USBTimeoutError` / `libusb I/O error`).
   - Running the 512-byte stage at `0x41070000`, `0x41180000`, and
     `0x41100200` through the trampoline produced the same signature:
     trigger accepted, control path dead, device remained enumerated as
     `2aad:6500`, no USB drop or re-enumeration. This is consistent with
     the hijack context killing the receiver before/at the call, not
     with a payload boot.

3. **`0x04f7` handoff with the stage itself as payload.**
   - Image staged at `0x44000000` (14.9 MB, write-only; sample
     verified). Bootquery-alloc 512 bytes at `0x4183ca00`, stage
     written and read-back verified.
   - `skip_prep` cleared the mode-`0x88` callback
     (`0x405da53c -> 0`), then `0x04f7 [0x4183ca00, 0x200, 0x41100200]`
     was accepted (12 bytes).
   - State 6 then hung in the storage-header read: control path timed
     out, device still enumerated. This is the same state-6 lottery
     documented for the full-updater pushes (7/15 completions), not a
     stage-specific failure.

## Interpretation

- The SD-card driver incompatibility blocks the only documented
  one-sector `sdboot` entry on this card.
- The trampoline can install and read back, but the handler-hijack
  trigger kills the receiver control path before any payload execution
  can be observed. `bx lr` returning cannot be confirmed because the
  control path is already dead; the stage never caused a USB drop.
- The `0x04f7` state-6 handoff still hangs in its preparation storage
  read even with the park callback cleared. A 512-byte payload does not
  change the failure mode.
- No software route tested here executed the staged image. The
  remaining boundaries are the same: earlier-stage/mask-ROM helpers,
  a compatible SD card for `sdboot`, or hardware access.

## Recovery

Every attempt ended with a power cycle; the camera booted normally
afterwards. With `EnableTest.txt` on the card it returns to loader mode;
removing the flag/card returns it to retail. The NAND is untouched.

## Files

- `analysis/sd_chainloader/` — stage, build/upload/run tooling, README.
- `analysis/sd_chainloader/run_stage_04f7.py` — 0x04f7 stage runner.
- `analysis/usbt-mask-boundary-2026-11-10.md` — mask-path boundary.
