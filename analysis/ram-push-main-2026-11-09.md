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
the stock labels.** So either the pushed entry stub never executed
(handoff/copy failure), or it executed but the reset path still ends
up with stock rodata.

## Entry-hang discriminator

A third payload (`firmware-main-hang.bin`, SHA-256 `e23f1bb8…`)
replaces the first instruction at `0x40000000`, `0x40000200`,
`0x40000338` and `0x40000ec0` with `b .` (self-loop). If the handoff
copies the payload and calls any of those entries, the camera hangs
with no USB re-enumeration. If the stock calibration UI appears
instead, the pushed entry did not execute and the handoff/copy path
is the blocker — not the staged loader.

## Interpretation

- **This was not a state-6 prep hang.** The loader left USB at the
  `0x04f7` trigger, so the handoff executed.
- The handoff calls the pushed image's `+0x200` stub. That stub
  stamps the gate and jumps to the staged loader at `0x40000338`,
  which re-reads the selected firmware region **from NAND** into
  RAM. The pushed entry stub executes, but the application
  sections are the stock NAND copy.
- **The marker test proves this.** The pushed copy's
  `RAM-BOOT!`/`RAM-USB!` labels did not appear; the live menu
  showed the stock labels. The strings exist only in the main
  image, not in the resource images, so this is not a
  resource-copy artifact. The running rodata is stock.
- The re-enumerated `2aad:6500` personality and the live debug UI
  are the stock main image running from RAM after the staged
  loader's NAND reload — not our patched payload's sections.
- The bootquery timeout is expected in calibration/debug mode: the
  loader API is selected by the button combination, which is not
  held after the handoff. The timeout is not proof that the loader
  is dead.
- The previous `0/4` reclassifies to: **the handoff executes; the
  pushed entry stub runs; the staged loader then replaces the
  application sections with the stock NAND copy.** Pushing a
  modified main image requires bypassing or redirecting that
  staged-loader copy.

## Next

The live debug menu exposes two useful controls:

- **`USB MODE`** — likely the runtime USB personality selector.
  Switching it away from the loader/calibration personality may
  make the main image enumerate as the normal PTP device
  (`0x04CB:0x030A`) without a reboot.
- **`BOOT TYPE`** — likely the boot-mode selector. Treat this as
  potentially persistent; read the current value before changing
  anything.

The `Disable` dialog with the 5-bit value is a service toggle; do
not change more bits until its target is identified. A power-cycle
still returns the camera to the stock NAND firmware.

The entry-hang payload is the current discriminator: a hang means
the pushed entry executed; stock UI means the handoff/copy did not
execute it. The live menu's `USB MODE`/`BOOT TYPE` controls remain
available for the later normal-app work.

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
