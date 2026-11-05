# SD boot diagnosis: why P3 settled into service mode

Date: 2026-11-05 (night). Offline follow-up to P3 in
[ram-push-sd-boot-plan.md](ram-push-sd-boot-plan.md) (attempted
2026-11-05; updater settled into service mode instead of booting
`D:\MAIN.BIN`; failure point not isolated). No camera access in
this pass.

## Eliminated: file format

`scripts/ReproduceSdBootLoader.java` executes the updater's
loader `FUN_4000f270("D:\MAIN.BIN", 0)` as original instructions
with a mocked filesystem/interface
([output/sdboot-loader-emulator.txt](output/sdboot-loader-emulator.txt)):

- Tiny file and the real `firmware-main.bin` (14,910,464 bytes)
  both reach the success boundary: file read in full, boot
  status written (`0x80/0x82`), op-10 header read issued, state
  filled (size `0xe38400`, dest `0x40000000`), and the `boot`
  task created. The buffer spot-check matches the file bytes.
- File absent → `%s file open error`, loader returns, no task.

On the plain-file path the loader performs **no content
validation**: the byte-sum (`FUN_4006b108`) and word-sum
(`FUN_4006b130`) checksum comparisons guard only class-coded
sources (`:DEF_FW*`, `:MB_*`, `:RAWCBOOT*`) and installed-image
records. A raw DRAM image is an acceptable boot file. The P3
emulator controls stopped at the loader's entry; this run
closes the file-format question they left open.

## Established by code reading

- The consumer (`FUN_400009c4`) builds `D:\MAIN.BIN` from the
  default `D:\firmware.brn` buffer by overlaying the mailbox
  basename at offset 3 and the mailbox extension after a `.`;
  the drive letter is never rewritten. The fallback mount
  (drive 5, "SD2") therefore still checks `D:\` — a quirk, but
  `D:` is this product's SD drive (the main firmware itself
  uses `D:\EnablePTPAP.txt` and `D:\FPUPDATE.DAT`), so the
  primary mount (drive 2, "SD1") is the expected path.
- The filesystem open (`FUN_4004a9c0`) parses the drive letter
  into a drive object, so the letter is meaningful.
- The client `--set-mailbox` write is byte-correct (basename
  at `0x100040b9`, extension at `0x100040c1`, NUL-padded, and
  verified by re-staging in the same boot).

## Leading hypothesis: the mailbox did not survive to the push

The mailbox lives in SoC registers at `0x100040b9`. It is
volatile: a power cycle clears it. The P3 day's recorded
sequence is: mailbox write verified; first RAM-push hung
>8 min in state 6; recovery by power cycle; **retry push
completed** and the updater settled into service mode. The
record does not show the mailbox being set again in the retry
boot. With an empty mailbox the updater uses the default
`D:\firmware.brn`, finds no such file on our card (by design),
and falls into its USB/service continuation — exactly the
observed outcome, with no SD or format fault required.

## Lower-ranked candidates

- SD detect/mount failure inside the RAM-pushed updater
  (both "SD1 detect fail" and "SD2 detect fail" paths also end
  in the continuation). Not excluded; would produce the same
  observable.
- Card filesystem unsupported by the updater's FAT stack
  (card format from that day is not recorded).

## Next live attempt (one change, diagnostic by construction)

1. On a computer, verify the test card first: filesystem type
   recorded, `EnableTest.txt` present (debug entry), and
   `MAIN.BIN` at the root byte-identical to `firmware-main.bin`
   (SHA-256 `80350bd2…`). No `firmware.brn`.
2. In **one boot, with no power cycle in between**:
   `--set-mailbox MAIN BIN` (client verifies the write), then
   immediately `--ram-push` the stock updater. If state 6 hangs
   (90 s rule), that boot is over; any later boot restarts from
   the mailbox step.
3. Observe: updater service arrival, then within ~90 s either
   a second USB drop followed by main-firmware boot (normal
   UI / `04cb` enumeration) — success — or a stable service
   mode, which with (1) and (2) satisfied points at
   detect/mount inside the updater, the one remaining branch.
4. Exit and normal-boot sanity check as usual. No vendor
   commands to the updater; no storage writes.

## Live attempt 1 (2026-11-05, night): push hung; SD not reached

Card verified on the host beforehand: FAT32, `EnableTest.txt`
present, `MAIN.BIN` SHA-256 `80350bd2…`, no `firmware.brn`
(also re-verified clean after an unclean card removal; fsck
exit 0). In one boot: `--set-mailbox MAIN BIN` verified
(`mailbox now: MAIN.BIN`), then `--ram-push` of the updater:
download and read-back verified, `0x04f7` accepted. USB never
dropped; a bootquery probe after 90 s timed out with the
device still enumerated — the state-6 preparation hang, on the
updater payload this time. The updater never ran, so the
mailbox hypothesis remains **untested**. Recovery is the usual
power cycle, which also clears the mailbox: any retry restarts
from the mailbox step in a fresh boot.
