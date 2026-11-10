# Vendor mask path boundary: the `usbt` state is not the mask ROM

Date: 2026-11-10. Live receiver-state read in the `usbt` vendor
personality plus static re-check of the same receiver's
`0x04f1`/`0x04f3` handlers. No payload was sent in this pass.

## Why the planned mask upload was stopped

The planned sequence was `0x04f3 [0x40000000, 0x0a2a00]` + updater
bytes, then `0x04f1 [0x40000000,0,0,1]` + the first `0x100` bytes.
That sequence is the Windows DLL's **mask-ROM** flow. In the
camera's `usbt` state the same vendor indices are implemented by
the **running main firmware**, and they do not mean the same
thing.

Live receiver state at `0x40fd0d50` after `usbt` (read-only
`0x04f2` read from macOS):

```text
40fd0d70  c0 57 e7 42   state+0x20 workspace B = 0x42e757c0
40fd0d78  70 0d fd 40   state+0x28 word0 = last read address
40fd0d7c  40 00 00 00   state+0x2c word1 = last read length
40fd0d80  00 00 00 00   state+0x30 first-use flag = 0
40fd0d84  00 00 00 00   state+0x34 staging buffer = 0
40fd0d88  00 00 00 00   state+0x38 staging size = 0
40fd0d8c  00 00 00 00   state+0x3c destination = 0
40fd0d90  3c a5 5d 40   state+0x40 prep callback = 0x405da53c
```

The flag is **zero**, and the preparation callback **is installed**.

## `0x04f3` is not a download on first use

Main-image handler `0x401e1bb4` (`0x04f3`):

1. `state+0x30 == 0` (first use): allocate `size`, call
   `0x40056798(buffer, size)` — a **storage read**, not a USB
   receive — verify through `0x4000dbec`, then
   `memcpy(0x3f000200, buffer, size)` and set `state+0x30 = 1`.
2. Only a later `0x04f3` with the flag already set schedules the
   bulk OUT to `word0` (the "code transfer" branch).

With the live flag at zero, a first `0x04f3` of 666,112 bytes
would read 666,112 bytes from storage and then copy them to
`0x3f000200`. That address is in the 64 KiB SRAM window; the
copy would run past `0x3f010000` and corrupt the running
firmware's SRAM. The host's 666 KiB bulk OUT would also arrive
with no receive scheduled.

## `0x04f1` is the same state-6 handoff

Handler `0x401e1b7c` only sets `state+0x2c = word3 << 8` and
enters state 6. `FUN_401e1f40` then runs:

1. the prep callback at `state+0x40` (live `0x405da53c`, the
   mode-`0x88` park with its 30 s/60 s/20 s abort points);
2. a 16 KiB op-10 storage read into `0x3f000000`;
3. `FUN_401eb1ec` → the copy stub → `0xb34` → `0x40000200` →
   jump.

This is the same sequence as `0x04f7`, already tallied at 7/15
completions and reassessed on 2026-11-09 as not demonstrably
executing the payload. The mask path therefore does **not**
bypass the `0xb34`/quiesce problem; it reuses it.

## `usbt` does not enter the mask ROM

`usbt` handler `0x4031d498` parks mode `0x88`, switches the USB
API mode (`FUN_4038d6d0(4)`, `FUN_4038d764(1)`) and returns to
the running firmware. It does not reboot, write a boot flag or
hand off to the earlier stage. The re-enumerated device keeps
the main firmware's `0x04f1`–`0x04f7` receiver and the
`0x405da53c` callback.

## Consequence

The vendor mask path is a cold-boot/mask-ROM flow. It is not
reachable from the `usbt` shell command, and replaying it against
the main-firmware receiver is both semantically wrong and
dangerous (SRAM overwrite on `0x04f3`, state-6 park on `0x04f1`).
The remaining software execution routes are:

- the main-firmware state-6 handoff (`0x04f7`), with the
  unresolved quiesce lottery; or
- a custom SD chainloader reached through the shell's
  `sdfww`/`sdboot` commands, which avoids state 6 entirely.

The true mask-ROM ISP remains a cold-boot/hardware path and stays
outside the current no-hardware boundary.
