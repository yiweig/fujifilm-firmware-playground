# SD boot diagnosis: why P3 settled into service mode

Date: 2026-11-05 (night), corrected 2026-11-06. Offline follow-up to
P3 in [ram-push-sd-boot-plan.md](ram-push-sd-boot-plan.md)
(attempted 2026-11-05; updater settled into service mode instead of
booting `D:\MAIN.BIN`; failure point not isolated). No camera access
in this pass.

## Correction (2026-11-06): the mailbox hypothesis is falsified

The original version of this note made "the mailbox did not survive
to the push" the leading hypothesis. The live record contradicts
that. In the 2026-11-05 retry at 19:31, the mailbox was written and
verified (`mailbox now: MAIN.BIN`, page bytes
`004d41494e0000000042494e01f00000`) in the same boot as a successful
`0x04f7` handoff; the updater re-enumerated as its service
personality and settled into service mode. That is exactly the
construction the hypothesis required, and the file boot still did
not take. The mailbox is therefore not the explanation for the P3
outcome.

The record does show the mailbox being re-set in the retry boot: the
retry command was `--set-mailbox MAIN BIN` followed by
`--ram-push ~/updater.bin`, and the mailbox verification preceded
the push. The later 23:50 and 00:06 attempts never reached the
updater, so they add no evidence either way; they do not reopen the
question.

What remains, in order:

1. **Storage backend / drive selection.** The consumer chooses
   drive 2 or drive 5 from `0x10000058 & 0xe0` and boot-status
   bit 2. On a successful drive-5 activation it rewrites the
   pathname prefix to `G`, not `D`. The live value of
   `0x10000058` has never been read, and the P3 emulator only
   models the drive-2 probe. If the backend is `0x80/0xa0/0xc0`
   and bit 2 is clear (the live calibration status is `0x0100`),
   the updater tries `G:\MAIN.bin` first and may never look at the
   SD card. This is a deterministic candidate, not a lottery.
2. **The updater's storage initialization.** The live calibration
   boot has status `0x0100` (bit 7 clear) and button bytes
   `0x100040b4..b8` all zero; that path runs the button branch
   into `LAB_40000c68` and the storage-initialization block. The
   mailbox emulator sets `0x10004054=0x80` and `0x100040b8=1`,
   which skips that branch, and supplies storage readiness = 1.
   Its PASS is a conditional path control, not a model of the
   live path.
3. **SD detect/mount inside the RAM-pushed updater.** Both the
   "SD1 detect fail" and "SD2 detect fail" paths end in the
   service continuation.
4. **Card filesystem unsupported by the updater's FAT stack.**
   The card was FAT32 and the main firmware read it, but the
   updater's FAT stack is a separate build and was not exercised
   by the mocked-filesystem loader emulator.

The file-format question remains closed (below): the loader accepts
a raw main image.

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

- The consumer (`FUN_400009c4`) builds the pathname from the
  default `D:\firmware.brn` buffer by overlaying the mailbox
  basename at offset 3 and the mailbox extension after a `.`.
  **Correction:** the drive letter is not immutable. The primary
  drive-2 mount leaves the default `D` prefix; the fallback
  drive-5 mount, on success, writes `G` at
  `0x400010c8..0x400010d8` before rebuilding the pathname
  ([updater-startup-storage-routing.md](updater-startup-storage-routing.md)
  records the same). The filesystem open (`FUN_4004a9c0`) parses
  the drive letter into a drive object, so the distinction is
  meaningful. `D:` is this product's SD drive in the primary
  mount; `G:` is a different logical drive.
- The client `--set-mailbox` write is byte-correct (basename
  at `0x100040b9`, extension at `0x100040c1`, NUL-padded, and
  verified by re-staging in the same boot).

## What the record actually shows

The 19:31 retry is the decisive run:

```text
trampoline v3 installed
mailbox now: MAIN.BIN (page bytes b8..c8: 004d41494e0000000042494e01f00000)
slots restored
read-back sha256 4bdcdb06...
read-back verified. Sending 0x04f7 upload/run NOW ...
0x04f7 setup sent (12 bytes); waiting for the device to drop off USB...
```

The host observer then recorded the handoff and the updater's
service-mode arrival. The updater ran with a provably set mailbox and
still did not boot `D:\MAIN.BIN`. The remaining failure is inside the
updater's storage/path/boot handling, not in the mailbox write.

## Live attempt 1 (2026-11-05, night): push hung; SD not reached

Card verified on the host beforehand: FAT32, `EnableTest.txt`
present, `MAIN.BIN` SHA-256 `80350bd2…`, no `firmware.brn`
(also re-verified clean after an unclean card removal; fsck
exit 0). In one boot: `--set-mailbox MAIN BIN` verified
(`mailbox now: MAIN.BIN`), then `--ram-push` of the updater:
download and read-back verified, `0x04f7` accepted. No handoff was
observed; a bootquery probe after 90 s timed out with the
device still enumerated — the state-6 preparation hang, on the
updater payload this time. The updater never ran. These attempts
add no evidence about the mailbox; the 19:31 run already tested
it. Recovery is the usual power cycle, which also clears the
mailbox: any retry restarts from the mailbox step in a fresh boot.

## Live attempt 2 (2026-11-05, night): push hung again

Identical construction in a fresh boot: mailbox verified as
`MAIN.BIN`, updater download/read-back verified, `0x04f7`
accepted, no handoff observed, bootquery probe timed out past 90 s
with the device still enumerated. Second consecutive state-6
hang; live attempts stopped for the night per the stated
budget. These attempts never reached the updater, so they add no
evidence about the mailbox; the 19:31 run already falsified the
volatility hypothesis. Running tally for state 6: updater 2
successes / 3 stalls, main image 0/4.

## Emulator scope correction (2026-11-06)

The mailbox emulator's fixtures are not the live calibration boot:

| Input | Emulator (`ReproduceUpdaterBootMailbox.java`) | Live calibration boot |
| --- | --- | --- |
| `0x10004054` status | `0x0080` (bit 7 set) | `0x0100` (bit 7 clear) |
| `0x100040b4..b8` button bytes | `0xb8 = 1` | all zero |
| Storage readiness | supplied `1` | unknown; init path runs |
| Drive probe | drive 2 only (`r0 != 2` aborts) | drive 2 or 5 by backend |

With bit 7 set, the emulator skips the button branch and goes
straight to `LAB_40000d0c`; the live path takes the button branch
into `LAB_40000c68` and the storage-initialization block. The
emulator's PASS is therefore a path control for the post-init
branch, not a model of the live startup. This is consistent with
[updater-startup-storage-routing.md](updater-startup-storage-routing.md)'s
note that the earlier fixtures deliberately avoided the
initialization block.

## Next live attempt (revised)

1. In a loader session, stage `0x10000000..+0x100` (read-only) and
   read `0x10000058`, `0x10004054`, `0x100040b4..b8`, and the
   post-extension boot-info bytes. Use the trampoline stage, not
   `0x04f2`: direct SRAM/MMIO reads wedge the receiver
   ([loader-memory-read-live.md](loader-memory-read-live.md)).
   The existing `stage_bootinfo` already copies the `0x10004000`
   and `0x10001000` pages; extend it with the `0x10000000` page.
2. Rerun the mailbox emulator with the live fixtures: status
   `0x0100`, button bytes zero, storage readiness 0 (or model the
   init), and backend values `0x00/0x20/0x40/0x80/0xa0/0xc0`
   with both drive-2 and drive-5 probes.
3. If the backend points at drive 5, fix that before another push.
   If it points at drive 2, the next live attempt should target
   mount/FAT/file-open; the mailbox is already set correctly.
4. Only then run another mailbox + updater push. If the updater
   comes up, treat its service personality as a read-only debug
   port: the `0x04f2` family may work even though the `0x70`
   bootquery timed out. A read of `0x40000000` verifies the
   dialect; aligned reads of the log state (`0x400ba7d8`) and log
   task (`0x400a29ac`) would expose the failure reason. This is
   read-only: no `0x04f5` writes, no `0x04f7` run, and no storage
   or erase commands to the updater.
