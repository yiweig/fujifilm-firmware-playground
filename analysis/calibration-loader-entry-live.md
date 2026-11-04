# Calibration entry live: loader `2aad:6500` reached, cost = 3 predicted bytes

Date: 2026-11-04. First live entry into USB API mode 4 / target 0, following
the offline qualification in [record-fixture init emulation](clbt-init-record-selection.md)
and the [UVC producer closure](uvc-debug-producers-trace.md). The user
performed all physical actions and explicitly approved this experiment.
No loader-protocol request, calibration interaction, firmware change, or
settings write was sent. The only host USB activity beyond enumeration was
passing the device to the UTM/Linux VM and reading standard descriptors
(`lsusb -v`, kernel GET_DESCRIPTOR) inside it.

## Entry procedure used

1. Camera off. SD card received a one-byte `EnableTest.txt` in a card reader
   (the firmware's own override for `DisableTest=1`; see
   [override assessment](disabletest-override-assessment.md)). No
   `EnablePTPAP.txt` present during entry.
2. Card reinserted, USB plugged into the Mac, **STILL/MOVIE = MOVIE**,
   **flash = ON**, **PLAY held** through power-on (~15 s hold).
3. Host registry observer (`analysis/usb_startup_observer.c`) recorded the
   session; UTM VM stood by for passthrough.

## Observations

| UTC | Event |
| --- | --- |
| 21:37:08 | Observer ready, baseline 3 unrelated devices |
| ~21:39 | User reports a diagnostic screen: entries such as `MB/LCD: OK`, `Dark DPC: OK`, `BootType: OK`; another screen flashed too briefly to read |
| 21:39:08 | **`2aad:6500` arrived** on the host (registry + UTM: "Digital Camera") |
| 21:41:11 / 21:41:29 | Terminate/re-arrive pairs as the device was passed to the VM (first connect raced enumeration; second succeeded) |
| 21:42:01 | Final termination at user power-off |

The user pressed no control after entry and powered off within ~3 minutes.

Live descriptors ([output/loader-descriptors-live.txt](output/loader-descriptors-live.txt),
captured in the VM): `bcdDevice 2.00`, one configuration, 3 interfaces —
interface 0 (interrupt IN `0x87`, 8 B), interface 2 (isochronous IN `0x81`,
multiple alt settings), **interface 5 (bulk IN `0x82`, bulk OUT `0x03`,
interrupt IN `0x84`; 512 B bulk)**. This matches the statically traced
target-0 template in [the RAM receiver report](xhalf-usb-ram-receiver.md).
Loader identity `2aad:6500` is no longer "statically expected": it is a
measured enumeration on this unit.

The diagnostic screen content is a user observation, not a traced UI state;
the brief intermediate screen was not captured. No calibration worker
behavior (lens/sensor adjustment, progress display) was reported.

## Exit and verification (the acceptance test)

After power-off: `EnableTest.txt` was removed in the reader,
`EnablePTPAP.txt` restored, camera booted normally (STILL, flash OFF), and
the factory record was re-acquired through the VM transport
([live acquisition procedure](factory-ptp-live-acquisition.md)).

- Post-entry record SHA-256:
  `5b8060e604fe84f95f4d0de3e99866a955980824c3853ebb7abe19cc0cb4bc76`
  (checksum valid; copy at `/private/tmp/xhalf-colddata-after-entry-2026-11-04.bin`).
- **Diff vs the pre-entry backup: exactly 3 bytes** —
  `0x004: 01->02`, `0x008: 01->06`, `0x3fe: 2c->32` (writer checksum).
  All other 1,021 bytes identical. This matches the emulator prediction
  byte-for-byte, confirming that the initialization save persisted only
  the forced `Hce version info` (2,0,6) and that no calibration item ran
  or had its flags rewritten.

Cleanup: both markers removed, card restored to its original directories,
and the user confirms normal shooting, playback, and wireless phone
transfer afterwards.

## Status effect

- **R1 (retail-to-loader entry) is established for temporary execution**:
  card marker + button combination -> mode 4, with measured activation
  effects (two version bytes + checksum), measured deactivation (ordinary
  power-off; marker removal prevents re-entry), and a bounded in-mode
  dwell. It still depends on the installed main firmware and is therefore
  **not** cold recovery (C1 unchanged).
- The route is repeatable: `EnableTest.txt` + MOVIE/flash-ON/PLAY-hold.
  Each entry should be expected to leave the same record state (fields
  already read 2,0,6 after this entry, so a second entry's save should be
  a byte-level no-op — to be confirmed by the same diff method if repeated).
- **Next loader phase (not yet attempted):** speak the target-0 vendor
  protocol on interface 5 from the VM. Read-first ladder: `0x70`
  BootMethod with zero size (skips the allocation branch), then the
  `0x04f4`/`0x04f5` memory-read branches against known main-image bytes,
  then a dump of low memory around `0x00000b34` (R2's missing helper,
  present on hardware), then scratch-RAM write/readback. No upload/run
  (`0x04f7`) until those reads validate the receiver on this unit.
