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

**Confirmation (second run, same payload):** the debug view is
**live**. Pressing PLAY opens the debug menu and the
film/playback lever navigates it; menu entries include
**`BOOT TYPE`** and **`USB MODE`**. A `Disable` item opens a
dialog with a green/gray bar and a 5-bit value (`00001`/`00000`)
that the lever toggles. This is the main image's own UI and input
task running from RAM, not a stale framebuffer and not the old
loader. The main image boot reaches calibration/debug mode.

## The marker test and the staged-loader reload

A patched copy of the same image changed the calibration menu labels
`BOOT TYPE` → `RAM-BOOT!` and `USB MODE` → `RAM-USB!` (same length,
SHA-256 `60e819af…`). The live menu after the push still showed the
**stock labels**. The package check rules out a resource copy: the
two strings exist only in the main image (`FPUPDATE.DAT` offset
`0x09df8434` / `0x09df84e8`, i.e. main-image `0x40e14714` /
`0x40e147c8`).

The explanation is the loader-facing entry. The handoff calls
`0x40000200` first; that stub stamps the gate and branches to the
**staged loader** at `0x40000338`. The staged loader validates the
section table and loads the selected firmware region **from NAND
storage** into RAM (`staged-loader-storage-handoff.md`). So the
handoff executes the pushed entry stub, but the application sections
are re-read from NAND. The live menu is the stock image loaded from
NAND, not the pushed payload's rodata.

This also means the earlier "main image boots from RAM" result is
narrower than it looked: the **entry stub** is pushed and executed,
but the app sections are stock NAND content. The main image's UI is
running from RAM, but it is not our patched copy.

## Direct-boot bypass

The next test removes the staged-loader reload. A copy of the stock
image (`firmware-main-direct.bin`, SHA-256 `2c64a814…`) changes:

- `0x4000022c`: `b 0x40000338` → `b 0x40000000` (skip the staged
  loader and run the pushed image's reset vector directly);
- the two menu labels to `RAM-BOOT!` / `RAM-USB!`.

The stub's gate writes are kept. **Result: the live menu still showed
the stock labels.** Combined with the entry-hang result, the pushed
entry never executed; the direct-bypass branch was never reached.

## Entry-hang discriminator

A third payload (`firmware-main-hang.bin`, SHA-256 `e23f1bb8…`)
replaces the first instruction at `0x40000000`, `0x40000200`,
`0x40000338` and `0x40000ec0` with `b .` (self-loop). If the handoff
copies the payload and calls any of those entries, the camera hangs
with no USB re-enumeration.

**Result: no hang.** The live debug UI stayed on screen the whole
time and the camera re-enumerated. The pushed entry did not execute.
The blocker is the `0x04f7` copy/handoff for the main-image payload,
not the staged loader or the reset path.

The updater push works at 666 KB; the main image is 14.9 MB. The
next test truncates the main image to 666,112 bytes and keeps the
self-loop at the entry (`firmware-main-hang-666k.bin`, SHA-256
`f701b30f…`).

**Result: no hang.** The camera re-enumerated with the stock UI
again. Size is therefore not the sole blocker; the handoff does not
execute the main-image payload even at the updater's size.

## Critical control: does the handoff execute *any* payload?

The stock updater's successful service personality could, in
principle, be the stock updater booted from NAND after a failed
handoff rather than the pushed copy. A control is required:
`card-updater-hang.bin` (SHA-256 `e8f6596d…`) is the 666,112-byte
updater with the same self-loop at `0x0/0x200/0x338/0xec0`.

**Result: no hang; the post-handoff bootquery timed out**, i.e. the
updater service personality came up. But the updater's reset vector
at `0x40000000` branches to **`0x40000340`**, which the patch missed.
So this run does not yet prove the handoff failed to execute the
updater: the pushed updater may have run from `0x340`.

A corrected control is prepared: `card-updater-hang340.bin`
(SHA-256 `0e6d143f…`) patches `0x0/0x200/0x338/0x340`. A hang means
the pushed updater executes at `0x340`; the updater service
appearing again means the payload is a fallback/not executing.

**Result: no hang.** The device re-enumerated, the bootquery timed
out, and the debug UI was **live** (PLAY/lever responds). The
updater does not drive the display, so a live debug menu is the
**stock main firmware's calibration mode**, not the updater. The
pushed updater did not execute.

This overturns the earlier "updater boots from RAM" conclusion:
the 3-interface/bootquery-timeout signature is shared by the stock
calibration mode and the loader, so it was never a unique updater
marker. The 7/15 state-6 "completions" are resets into this
calibration fallback, not demonstrated payload boots. See
[ram-push-reassessment-2026-11-09.md](ram-push-reassessment-2026-11-09.md).

The same caveat applies to the main-image hang tests: they patched
`0x0/0x200/0x338/0xec0`, but the main image's RTOS entry
(`0x400037e8`) and app-init vector (`0x40003498`) were not patched.
Those entries must be included before concluding that the main
image never executes.

## Interpretation

- **This is not a state-6 prep hang.** The loader left USB at the
  `0x04f7` trigger, so the handoff teardown ran.
- **The earlier "pushed entry does not execute" conclusion is not
  final.** The hang payloads patched `0x0/0x200/0x338/0xec0`, but
  the updater's real entry is `0x40000340`, and the main image's
  RTOS/app entries are `0x400037e8`/`0x40003498`. Those addresses
  were not covered.
- The updater service personality came up after the
  `0x0/0x200/0x338/0xec0` hang patch, but that run missed `0x340`;
  the pushed updater may have executed from `0x340`.
- The stock labels in the marker/direct runs show the app rodata is
  stock, but the direct path also depends on the actual entry. The
  staged-loader-reload explanation is still open.
- The next controls are the corrected updater entry (`0x340`) and
  the main image with `0x37e8`/`0x3498` included.

## Next

1. `card-updater-hang340.bin` (SHA-256 `0e6d143f…`) patches
   `0x0/0x200/0x338/0x340`. A hang means the pushed updater
   executes at `0x340`; the updater service appearing again means
   the payload is a fallback or not executing.
2. A full main-image hang payload that includes `0x400037e8` and
   `0x40003498`. A hang means the pushed main image executes at one
   of those entries; no hang means the payload is rejected before
   execution.

The live debug menu's `USB MODE`/`BOOT TYPE` controls remain
available for the later normal-app work, but they are downstream of
getting the pushed entry to execute.

## Evidence

- `analysis/output/usb-startup/control-updater-1.{jsonl,log,desc.txt,bootquery.txt}`
- `analysis/output/usb-startup/main-push-2026-11-09.{jsonl,log,desc.txt,bootquery.txt}`
- Bootquery timeout transcript:
  `main-push-2026-11-09-bootquery.txt`

## Boundary

The handoff execution, the re-enumeration and the **live debug UI**
are observed. The marker test shows the running application rodata
is the stock image: the pushed entry stub executes, but the staged
loader reloads the application sections from NAND. The pushed
application sections are not demonstrated. The normal application
and a modified image are not demonstrated. The `Disable` toggle
was changed on screen; whether it writes a persistent calibration
field is not yet established, and the camera still boots the
stock NAND firmware normally. No other persistent change was
observed.
