# RAM-push: stock main image reaches a post-handoff USB personality

Date: 2026-11-09. Live session via the loader mode
(`EnableTest.txt` + MOVIE/flash-ON/PLAY-hold), UTM/Linux VM,
`analysis/xhalf_loader_linux.py` in `/home/xhalf`. Payload:
official 2.00 `firmware-main.bin`, 14,910,464 bytes,
SHA-256 `80350bd2…`. No persistent change observed; the camera
booted normally afterwards.

## Control: stock updater

The same session first repeated the proven updater push
(`card-updater-full.bin`, 666,112 bytes, SHA-256 `4bdcdb06…`):

- bootquery before push: method byte 2 (loader);
- download and read-back verified;
- `0x04f7` accepted; the VM lost the camera at **18:11:43Z**;
- the camera re-enumerated at **18:13:12Z** (~89 s) with
  `2aad:6500`, `bcdDevice 2.00`, 3 vendor interfaces 0/2/5;
- bootquery on the new device **timed out** — the updater service
  personality;
- the device dropped after the timeout. Power-cycle recovered.

This is the successful-handoff reference signature. It also
corrects an earlier assumption: the loader and the updater expose
the **same interface template** (0/2/5, bulk `0x82/0x03`,
interrupt `0x84`). The discriminator is the bootquery response,
not the interface count.

## Main image

Same sequence with `firmware-main.bin`:

| UTC | Event |
| --- | --- |
| 18:21:54 | observer/loader baseline, `2aad:6500` |
| ~18:23–18:24 | download + read-back; SHA-256 matched `80350bd2…` |
| ~18:24 | `0x04f7` accepted (12 bytes) |
| 18:24:32 | VM sees **no** `2aad:6500` — loader dropped |
| 18:25:37 | `2aad:6500` re-enumerated, `bcdDevice 2.00`, 3 interfaces |
| 18:25:45 | bootquery **timed out** |
| 18:26:17 | device gone again |

User observation: the screen showed the **debug view**; the power
switch did not respond, but flipping OFF and unplugging powered
the camera off (no battery pull). Normal operation afterwards.

## Interpretation

- **This was not a state-6 prep hang.** The loader left USB at the
  `0x04f7` trigger, so the handoff executed.
- The old loader's code at `0x40000000` is overwritten by the new
  image. Its USB stack cannot keep running; a re-enumeration must
  come from the new environment (or a reboot).
- The re-enumerated personality is the `2aad:6500` vendor
  template, and the screen stayed on the debug view. That is
  consistent with the **main image booting into its
  calibration/debug mode** (the `EnableTest.txt` override is
  present) rather than the normal camera app.
- The bootquery timeout is expected in that mode: the loader API
  is selected by the button combination, which is not held after
  the handoff. The timeout is not proof that the loader is dead.
- Caveat: the debug view could be the old framebuffer. The
  re-enumeration plus the overwritten loader is strong evidence
  that new code ran, but this run did not positively distinguish
  a live calibration UI from a stale screen.

This is the strongest main-image execution evidence so far; it
reclassifies the previous `0/4` from "did not come up" to
"handoff executes; the post-handoff personality is not the loader
API".

## Next confirmation

Repeat the stock-main-image push while watching the screen and
using only read-first probes:

1. Watch for a display re-init (blank/flicker/redraw) at the
   handoff.
2. After re-enumeration, try `--read 0x40000000 16` and
   `--read 0x10001300 16` **before** `--bootquery`; a successful
   `0x04f2` read proves the receiver is up, and the gate value
   distinguishes the main-image stamp (`0x12345678`) from the
   updater (`0x88888888`).
3. Press MENU/arrow on the debug view; a live UI confirms the
   main image's tasks are running.

If confirmed, the next goal is the **normal app** rather than
calibration mode: the post-handoff boot sees `EnableTest.txt`, so
removing that marker after loader entry (or using a card without
it for the new image's boot) is the obvious lever.

## Evidence

- `analysis/output/usb-startup/control-updater-1.{jsonl,log,desc.txt,bootquery.txt}`
- `analysis/output/usb-startup/main-push-2026-11-09.{jsonl,log,desc.txt,bootquery.txt}`
- Bootquery timeout transcript:
  `main-push-2026-11-09-bootquery.txt`

## Boundary

The handoff execution and the re-enumeration are observed. The
claim that the main image (rather than a reboot or a stale loader)
produced the new personality is an inference from the overwritten
loader region and the observed timing; it is not yet confirmed by
a live marker. No persistent change was observed.
