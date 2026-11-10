# Retraction: the eSD card does not boot the main firmware

Date: 2026-11-09. Live-camera control tests. This retracts the earlier
"eSD card boot works" result in
[esd-boot-2026-11-09.md](esd-boot-2026-11-09.md).

## Bottom line

The X half boots its internal NAND in every eSD-card configuration
tested. The camera does **not** execute the main image from the SD
reserved slot. The earlier black screens were caused by the **raw card
plus USB communication**, not by a card-image boot.

The `sdset`/`sdfww` reserved layout is the running firmware's SD
reserved-block format. The `+0x40 = 0x32` field is a slot/format type,
not a cold-boot selector.

## Evidence

1. **Stock entry, no USB.** The card had the eSD header (`0x0032`),
   slot 1 start `0xa2`, the stock image with the **original** first
   instruction `EA0003AE`, and the card was booted with **no USB
   cable**. The camera showed the normal UI and the "Card not
   initialized" banner. If the eSD stage entered the image at `+0x0`,
   the stock reset vector would have run; it did not.

2. **Entry patch, no USB.** The same card with `EA00007E`
   (`b +0x200`) also showed the normal UI. The entry patch made no
   boot-behavior difference once USB was removed.

3. **PTP markers never appeared.** The slot image was patched at the
   PTP model string (`X-HF1` -> `X-ES1`) and the PTP device version
   (`0100` -> `0900`). With the raw card booted and the card then
   removed, the live PTP `GetDeviceInfo` still reported:
   - `model: 'X-HF1'`
   - `device_version: '01.00'`
   The patched strings exist only in the card image; the running
   firmware did not use them.

4. **Type byte does not select a boot.** `+0x40 = 0x0000` and
   `+0x40 = 0x0032` both boot NAND with USB unplugged. The checksummed
   `0x0a32` form also boots NAND.

5. **Black screens track USB, not the image.** The NAND firmware with
   a raw card and `+0x40 = 0x0000` also black-screens when USB
   communication is enabled. The crash follows the raw card, not the
   eSD header or the slot image.

6. **Marker changes were never executed.** Patching the card image's
   strings caused no observed change in the running firmware. The
   earlier "marker black screen" was the USB cable.

## What the reserved layout actually is

The `sdset` builder (`0x403063fc` -> `0x40077450`) and `sdfww`
(`0x40305508`) create/read the SD reserved layout used by the running
firmware's SD driver (`sdDetect`, `sdboot`, `rsvsdrd`). The shell
`sdboot` worker reads only one 512-byte sector and jumps; it is a
loader jump, not a full-image boot. The presence of a valid `eSD RSV`
header on the card does not make the boot ROM select it.

The hybrid experiment confirmed this: adding a valid MBR/FAT at 32 MB
(beyond the eSD slot) removed the "Card not initialized" banner and
fixed the USB crash, but the camera still booted NAND. The eSD slot
was ignored. The alternate header at sector `0x40` did not change
that.

## USB crash and the hybrid card

- A raw eSD card (no MBR/FAT) at sector 0 makes the USB/PTP stack
  crash on communication: black screen, no enumeration, clicking.
- The same crash occurs on a **NAND boot** with the raw card and
  `+0x40 = 0x0000`, so it is not an eSD-boot artifact.
- A hybrid card fixes it: keep the eSD header at sector 0 but add a
  valid MBR partition table at `0x1be` and `0x55aa` at `0x1fe`,
  pointing to a FAT16 partition at LBA `0x10000` (32 MB), beyond the
  eSD slot. The firmware mounts the FAT and USB/PTP enumerates
  (`04cb:030a`).
- The MBR signature is also what makes the boot stage skip the eSD
  header: clearing `0x55aa` restores the "raw card" behavior (eSD
  header seen, FAT not mounted, USB crashes), but still does not boot
  the card image.

## Implications

- **The safe SD-boot path is still unproven.** There is no confirmed
  way to make the camera execute the card image.
- The earlier `0x04f7`/RAM-push and `usbt`/host-tool leads remain the
  relevant recovery directions.
- The `sdboot` shell command remains a one-sector loader jump, not a
  full-image boot.
- A hybrid card is useful as a **normal FAT card with the eSD header
  present**, but it boots NAND. It does not prove or provide card
  boot.

## Card state

The test card currently has:

- sector 0: eSD header `0x0032` (raw, no MBR signature),
- slot 1 at `0x510000`: stock image with `EA00007E` (entry patch) and
  the PTP model/version string patches,
- a FAT16 partition at 32 MB from the hybrid experiment (not mounted
  in the raw configuration).

To restore the loader/test card, reformat it and put the stock
`MAIN.BIN` and `EnableTest.txt` back. The camera's NAND is untouched.
