# Idle-event USB flag refresh and saved-state audit

Date: 2026-11-01. Offline analysis only; camera not accessed. Official 2.00
main image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARM addresses use image base `0x40000000`.

## Result and correction to the previous research priority

Finding a producer of UI state `0x0301` is **not necessary for the flag-refresh
step**. The same event handler has a shorter path from UI state `0x0001`.
With its resource-ready byte set, it refreshes the cached USB selection flag
without calling the display/object cleanup or the known RTC-writing cleanup.
This corrects the overly narrow next-step framing in the preceding notes.

There is also a concrete event producer: the handler labeled by the embedded
log `MSG_KEY_IDLE_TIME_OUT` posts the refresh event when its suppression byte
is zero. This is a firmware event relationship, not yet a timed recipe for
the physical camera. No live commands were sent.

## Original instruction flow

The UI state halfword is `0x40e66958` (`0x40e65950 + 0x1008`). The USB
selection byte is `0x40e6696d` (`+0x101d`).

| Input and condition | Original instructions | Result |
| --- | --- | --- |
| Event `0xf0000071` in group 0, nonzero state | `0x404d51c8..0x404d51d0` | Select idle-timeout branch `0x404d5290` |
| Suppression byte `+0x102d` nonzero | `0x404d5298..0x404d52a4` | Return without posting |
| Suppression byte zero, state other than `0x0300` | `0x404d52a8..0x404d52c4` | Post `0xf2000084` through `0x4006f330`, other three arguments zero |
| Suppression byte zero, state `0x0300` | Same branch, alternate literal | Post `0xf2000080` instead |
| Delivered `0xf2000084` | `0x404d5258..0x404d5260` | Enter `0x404d52c8` |
| Ready byte `0x40f129c8` nonzero | `0x401575a4` | Skip initialization/wait |
| State `0x0001` | `0x404d52dc..0x404d530c` | Unequal to both `0x0301` and `0x0300`; branch directly to refresh |
| Refresh | `0x404d53b8..0x404d53d4` | Call getter `0x40125284`, store its logical inverse at `+0x101d`, return via no-op `0x40530b64` |

The two other hooks called before the state comparison, `0x40530b50` and
`0x40530b60`, respectively return zero and immediately return in this image.
State zero returns at the beginning of the group-0 handler. These observations
do not generalize the path to arbitrary states outside that dispatch group.

## Offline reproduction

The expanded [script](scripts/ReproduceTemporaryUsbSetting.java) retains the
previous ten checks and adds four:

- `+0x102d = 1` suppresses the idle-timeout queue post.
- With state 1 and `+0x102d = 0`, the supplied idle-timeout event posts
  `0xf2000084`, leaving the cold-data buffer, UI state and USB flag unchanged.
- Explicitly delivering that posted event, with resource-ready supplied,
  refreshes the USB flag from 1 to 0. State remains 1, all 1024 synthetic
  cold-data bytes are preserved, the display-active byte remains zero and
  no display/object calls occur.
- A subsequent supplied USB-presence event, with presence 1 and connection
  status 1, returns without selecting the loader or entering charge control.

All fourteen checks passed. Retained output:
[temporary-usb-setting-idle.txt](output/temporary-usb-setting-idle.txt).
The script checks the image hash before execution. Unexpected callees fail
the experiment. The queue call is captured, then its event is explicitly
delivered; the real timer and queue scheduler are not modeled. Readiness,
state 1, suppression-byte value, shared settings and peripheral inputs are
synthetic. The new state-1 test starts with the same staged RAM contents as
the prior factory-copy test; it is not a live settings write.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting-idle.txt
```

## Saved-state paths investigated

The following observations explain several dynamic UI stores. They do not
form a complete proof that state `0x0301` is unreachable.

| Saved value | Producer | Consumers and consequence |
| --- | --- | --- |
| Halfword `0x413d5ed0` | `0x40532530`, identified by `EnterViewQVMode` string; stores incoming `r0` at `0x40532598` | Restored at `0x405330cc`, `0x40533368`, `0x405333b0` |
| Halfword `0x413d3c90` | `0x405c9c18` saves incoming `r0`; `0x404d9bb0` copies current state, with special `0x1012 -> 0x2010` substitution at `0x404d9bcc` | Restore helper `0x405c9bb8` copies it back at `0x405c9bd8` |

All three found direct callers of `0x40532530` load its `r0` from the current
UI state (`0x4040ce7c`, `0x4040d224`, `0x40411b10`). All seven found direct
callers of `0x405c9c18` likewise supply the current state, or the saved state
at `0x404d9c40`. Call sites are `0x404d61a8`, `0x404d6888`, `0x404d7520`,
`0x404d8728`, `0x404d9c4c`, `0x404daa84`, `0x40518578`.
These are preservation/restoration mechanisms, not identified creators of
`0x0301`. Indirect or computed callers remain outside this census.

The exact entry of the nearby `PwrPowerSaving` routine is `0x4053086c`.
The aligned ARM B/BL scan over the first `0xab0000` image bytes found no
incoming branch to that address; a whole-image exact-pointer search found
none either. This supports considering dormant vendor code as a hypothesis,
but does not rule out computed calls, alternate instruction sets or unseen
code. The no-op hooks alone do not establish whether power saving is absent
from the camera.

Reproduce these bounded searches with:

```sh
python3 analysis/fw.py callers 0x4053086c 0x40532530 0x405c9c18
python3 analysis/fw.py disasm 0x404d51b0 0x404d53d8
python3 analysis/fw.py disasm 0x40532530 0x405325a0
python3 analysis/fw.py disasm 0x405c9bb8 0x405c9c54
```

Existing local Ghidra and original-byte disassembly were used because of the
previously documented REA raw-firmware mapping limitation. No new REA runtime
observation is claimed.

## Remaining gap

Follow-up: [StartMain loader gate](start-main-loader-gate.md) demonstrates a
different event that reaches loader selection from state 1 itself. A group
transition is therefore not required for every candidate composition; actual
event timing and safe repeatability remain unresolved.

This improves the refresh hypothesis but still does not connect state 1 to
the loader-selecting UI branch. In the new continuous test, the next USB event
returns with state 1. Actual idle-event generation, suppression-byte lifetime,
resource readiness and incidental persistence of staged cold settings also
remain unverified. Independent cold recovery remains unproved.

The next useful offline trace is therefore the transition from state 1 into
the USB UI state that consumes the refreshed flag, together with the normal
idle-event producer and suppression-byte writers. There is no reason to
require `0x0301` as an intermediate step for that investigation.
