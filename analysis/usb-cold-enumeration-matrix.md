# Cold USB enumeration matrix: passive button/power variants

Observed 2026-11-04 local time (17:20–17:34 UTC). Live **host registry
notification recording only**, using
[usb_startup_observer.c](usb_startup_observer.c) as in the
[ordinary power-on observation](usb-normal-startup-observation.md). The
observer opens no device or interface and sends no USB requests. The user
performed all physical actions; the camera was never sent a command, and no
card marker, calibration entry, or firmware change was attempted.

## Result

Across all variants below, the only USB identity ever observed was the normal
retail `04cb:030a`. No `2aad:6500` (statically expected loader identity), no
other vendor identity, and no intermediate/removed/rearriving device was
recorded. This narrows, but does not close, cold recovery (roadmap C1): a
mask-ROM USB mode, if present on this body, is not triggered by any tested
form. Registry coverage limits from the earlier observation still apply:
a device that fails enumeration, never publishes an `IOUSBHostDevice` node,
or appears only electrically is outside this evidence.

## Sequence

Baseline: three unrelated devices, no Fujifilm/iCatch candidate. Observer
emitted `ready` before the user touched the camera.

| # | Physical procedure | Host observation | User-reported camera behavior |
| --- | --- | --- | --- |
| 1 | Plug USB in while off, wait, power on normally | No arrival while off; one `04cb:030a` arrival at 17:26:42 UTC | Green charging light while off; brief shooting UI, then USB screen |
| 2 | Camera off, hold shutter fully, plug USB in, keep holding 15 s, release | **No arrival at all** | Charging light briefly, then off (battery reported full); camera stayed off, display blank |
| 3 | USB attached, hold shutter fully, power on, keep holding 15 s | One `04cb:030a` arrival at 17:30:50 UTC | Normal USB screen |
| 4 | USB attached, hold PLAY, power on, keep holding 15 s | One `04cb:030a` arrival at 17:32:41 UTC | Brief shooting UI, then USB screen |
| 5 | Battery removed, wait, plug USB in, then flip power on (USB power only) | **No arrival at all** | Brief charging light; brief shooting UI with red battery icon, then camera turned off |
| — | Fujifilm DISP/BACK firmware-update chord | Not testable | The X half has no DISP/BACK button (see control inventory below) |

Terminations at 17:27:39, 17:32:27 and 17:33:57 UTC correspond to the user
powering off between tests. USB port location changed between test 1 and
later tests; per the standing warning, identity attribution rests on the
baseline delta plus user actions, not on port location.

## Interpretation

- **Off-state:** the powered-off camera charges over USB but presents no USB
  device, with or without the shutter held. Shutter + USB insertion does not
  wake a boot-ROM USB personality on this body (test 2).
- **Shutter-held power-on** reaches the ordinary USB mode unchanged
  (test 3). Combined with test 2, the V50-reported shutter+USB sequence is a
  bounded negative for the X half in both of its plausible forms.
- **PLAY alone** at power-on is also a negative; it does not meet the
  three-input calibration gate in any case
  ([service-mode candidate](service-mode-button-candidate.md)).
- **USB power without battery** momentarily runs the main application
  (shooting UI, red battery icon) and then shuts down, with no preceding or
  substitute USB enumeration (test 5). No VBUS-powered mask-ROM enumeration
  precedes the main firmware in this form.
- The user confirms the complete physical control set relevant here:
  shutter button, STILL/MOVIE switch, PLAY button, FLASH switch,
  playback/diptych lever, and exposure-compensation dial. There is no
  DISP/BACK button, so the classic Fujifilm firmware-update boot chord does
  not exist on this body.

## What was deliberately not tested

The one remaining statically identified boot combination — MOVIE + flash ON
+ PLAY held — is the calibration entry candidate. It was **not** tried: its
post-entry initialization can attempt a persistent factory-data save and can
schedule automatic calibration work depending on saved flags whose live
values are still unknown
([calibration-entry safety](calibration-entry-safety.md),
[service-entry writes](service-entry-initialization-writes.md)). It stays
gated on first reading `COLDDATA.BIN` through the factory-PTP candidate
([cold-data read assessment](ptp-cold-data-read-assessment.md)). SD-card
boot filenames were not guessed, and no update file was placed on the card.

## Status effect

C1 remains unproven. This session removes several cheap hypotheses (shutter
chords in both forms, PLAY alone, VBUS-only power) so they should not be
retried as-is. The next evidence step is the controlled-transport factory
read in the [RAM/recovery roadmap](ram-launch-and-recovery-roadmap.md),
not another enumeration variant. Raw JSONL/stderr remain local under the
ignored `analysis/output/usb-startup/` directory (`cold-matrix-1`).
