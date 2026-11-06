# Updater SD path: live boot-status fixtures and the backend/drive-5 branch

Date: 2026-11-06. Offline original-instruction controls of the
official 2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
No camera access. Follow-up to
[sdboot-diagnosis.md](sdboot-diagnosis.md) after the mailbox
hypothesis was falsified and the drive-letter description was
corrected.

## Question

The P3 updater ran with `MAIN.BIN` verified in the same boot and
still settled into service mode. Two offline questions remained:

1. Does the **live calibration fixture** (status `0x0100`, button
   bytes `0x100040b4..b8` all zero, storage readiness unknown)
   change the consumer's path compared with the original mailbox
   emulator's `0x0080`/`0xb8=1`/readiness=1 fixture?
2. What does the **storage backend selector** `0x10000058 & 0xe0`
   do to the drive-2/drive-5 choice, and is there a fallback from
   drive 5 to drive 2?

## Method

[`scripts/ReproduceUpdaterSdPath.java`](scripts/ReproduceUpdaterSdPath.java)
runs the original consumer `FUN_400009c4` in the Ghidra ARM
emulator with supplied MMIO fixtures. It models only the
registration/initialization/log calls as returning 0, and supplies
the drive-probe and file-check results. It stops at the file-boot
boundary `0x4000f270`, the command-task boundary `0x40009ca0`, the
platform cleanup `0x40075ea4`, or the service continuation
`0x40069374`.

Ten controls pass ([output](output/updater-sdpath.txt)):

| status | backend | ready | drive2 | drive5 | file | probes | pathname | boundary |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `0100` | `00` | 1 | ok | ok | exists | `[2]` | `D:\MAIN.BIN` | `4000f270` |
| `0100` | `00` | 1 | fail | ok | exists | `[2,5]` | `G:\MAIN.BIN` | `4000f270` |
| `0100` | `00` | 1 | fail | fail | exists | `[2,5]` | none | `40075ea4` |
| `0100` | `80` | 1 | ok | ok | exists | `[5]` | `G:\MAIN.BIN` | `4000f270` |
| `0100` | `80` | 1 | ok | fail | exists | `[5]` | none | `40075ea4` |
| `0104` | `80` | 1 | ok | ok | exists | `[2]` | `D:\MAIN.BIN` | `4000f270` |
| `0100` | `40` | 1 | ok | ok | exists | `[2]` | `D:\MAIN.BIN` | `4000f270` |
| `0100` | `80` | 1 | ok | ok | absent | `[5]` | `G:\MAIN.BIN` | `40075ea4` |
| `0100` | `00` | 0 | ok | ok | exists | `[2]` | `D:\MAIN.BIN` | `4000f270` |
| `0100` | `80` | 0 | ok | ok | exists | `[5]` | `G:\MAIN.BIN` | `4000f270` |

## Findings

1. **The live status/button fixture does not change the post-init
   path.** With status `0x0100` (bit 7 clear) and zero button bytes,
   the consumer runs the button branch into `LAB_40000c68`, the
   storage-initialization block, and then the same drive/file path.
   The original emulator's `0x0080`/`0xb8=1` fixture skipped that
   branch but still reached the same file path. The emulator's PASS
   was therefore a valid post-init path control, but it did not
   exercise the live initialization calls; the new readiness=0
   controls do not change the path outcome either.
2. **Backend `0x80` selects drive 5 first and has no drive-2
   fallback.** With status bit 2 clear, `0x10000058 & 0xe0 == 0x80`
   takes `LAB_400010c8` and calls `func_0x4000e5a4(5)` directly. If
   drive 5 succeeds, the pathname prefix is rewritten to `G` and the
   file check is `G:\MAIN.BIN`. If drive 5 fails, the consumer goes
   to `LAB_40000f04` ("SD2 detect fail") and the platform
   cleanup/service continuation; it never tries drive 2. The same
   holds for backend `0xa0`/`0xc0` by the selector's other branches.
3. **Backend `0x00`/`0x20`/`0x40` tries drive 2 first.** On drive-2
   failure it logs "SD1 detect fail" and then tries drive 5; a
   successful drive-5 mount rewrites the prefix to `G`.
4. **Status bit 2 overrides the backend-`0x80` shortcut.** With
   status `0x0104` (bit 2 set), the drive-5-first condition is false
   and drive 2 is tried first.
5. **The live `0x10000058` value was the missing input.** If the
   backend is `0x80/0xa0/0xc0` and the live status is `0x0100`
   (bit 2 clear), the SD card on drive 2 is never tried. If the
   backend is `0x00/0x20/0x40`, the drive-2 path is used and the
   failure is downstream: mount, FAT, file open, or the boot
   handoff. The live read (below) settles this for the current
   unit.

## Live result (2026-11-06): backend is `0x00`, drive 5 is not the blocker

A read-only `--stage-bootinfo` session in a calibration boot staged
`0x10000000..+0x100`, `0x10004000..+0x100` and
`0x10001000..+0x304` via trampoline v3 and read them back:

```text
sysinfo: 0x10000058=0x0000 (backend=0x00)
bootinfo: status=0x0100 (bit2=False, bit7=False) buttons=0000000000 mailbox=<empty>.<empty>
gate 0x10001300=0x12345678
```

The sysinfo page is saved outside git as
`/private/tmp/xhalf-sysinfo-2026-11-06.bin` (SHA-256
`1b5c3a94…`); the bootinfo and gate pages are
`xhalf-bootinfo-2026-11-06.bin` (`e30e61aa…`) and
`xhalf-gate-2026-11-06.bin` (`c3a413f0…`, identical to the
2026-11-05 gate page).

**This eliminates the backend/drive-5 branch on this unit.** With
`0x10000058 & 0xe0 == 0x00` and status bit 2 clear, the consumer
takes the drive-2 path (`func_0x4000e5a4(2)`). The drive-5 branch
would only run if drive 2 failed; `G:` is a different logical drive,
and this pass does not establish which physical medium it maps to.
The P3 failure is downstream: the updater's
storage initialization, the SD1 mount, the FAT stack, the file open,
or the boot handoff.

The next diagnostic is the read-only updater probe after the next
successful push ([updater-service-read-probe.md](updater-service-read-probe.md)),
or a controlled card/FAT test. Do not spend another push on the
drive-5 hypothesis.

## What this does and does not establish

These are original consumer instructions with supplied MMIO values,
modeled mount/file results and modeled registration/init calls. They
do not execute the SD driver, FAT stack, file read, boot task, USB
startup, or any hardware. The `G:` path is a logical drive after a
successful `func_0x4000e5a4(5)`; this pass does not prove which
physical medium drive 5 is on this camera.

The read-only loader session is done: `--stage-bootinfo` copied
`0x10000000..+0x100` and printed `0x10000058 = 0x0000`. This unit's
backend is `0x00`, so the drive-2 path is used and the drive-5
branch is eliminated. The next live attempt should target the SD1
mount, the FAT stack, the file open, or the boot handoff; the
read-only updater probe is the prepared diagnostic.
