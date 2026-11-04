# UVC debug event producers: all require a pre-existing debug context

Date: 2026-11-04. Offline static analysis only; camera off and disconnected.
Follows [uvc-debug-event-followup.md](uvc-debug-event-followup.md), which
identified the route `f100000f`/`10e7` -> UVC callback registration and left
its producers as the open question. Main image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARMv7, base `0x40000000`. Method: `fw.py` caller/literal scans plus
instruction inspection. No emulation or live work in this pass.

## Result

**No retail-reachable producer exists among the identified ones.** Every
path that posts `f100000f` with parameter `10e7` already requires the shell,
the calibration/debug UI, or the UVC personality itself. This lead is a
bounded negative and should not be retried as-is; it does not supply an
alternative to calibration entry for first access to a service shell.

## The producers, one by one

### 1. `DbgMsg_Thread` (`0x4052241c`) — a within-session mode switch

Identified by the adjacent literals `DbgMsg_Thread`, `evDbgMsg`,
`txDbgMsgMutex` (pool at `0x4051cfe4..ec`). The initializer `0x4051cf40`
creates the queue/mutex/thread lazily on first use. In its loop, when state
flag bit 0 (`[0x413d36c4+0x234]`) is set and elapsed time exceeds the stored
lifetime (`+0x248`/`+0x24c`, logged as `Mass pass time: %d Start:%d,
LifeTime:%d`), it calls USB disconnect `0x4038d764(0)` and posts
`f100000f`/`10e7` at `0x405224c8`.

But the initializer's only callers are the DbgMsg send-API wrappers
(`0x4051d088`, `0x4051d0e4`, `0x4051d144`, `0x4051d190`) and `0x40522158`,
whose callers are:

- the application debug-event handler `0x405e2a44` itself (the very handler
  the event feeds), and
- a calibration-end path `0x405f40b4` (called from the calibration item
  dispatcher `0x405db9ac` and as a tail of `EnterCalibrationMode`), which
  also writes a result flag via `0x405da664(6,4)`.

So the thread is created only after a debug/calibration context already
exists. Its timed post is a mode switch *inside* such a session (mass-storage
pass expiry -> reconnect as UVC), not a first entry from a normal boot. No
startup caller or pointer reference to the initializer was found.

### 2. Shell command `dm` (`0x4067322c`) — shell-bound

The command-table entry at `0x40e1801c` names it `dm` / `Deb_Message`. For
argument code 194 it copies the supplied values and tail-calls `0x405df684`,
which stores the debug command/parameters at `0x40f12590` and posts the
event at `0x405df800`. Its one direct caller is the debug-command dispatcher
`0x4067880c` (`customization/command.c` family, 136-entry name table plus
hardcoded `dm`/`wifiui`/`ddps` compares), registered as the callback word
at `0x412c702c` by `0x4067d3d8` and reached through the shell line parser
`appBkCmd` (`0x40296a60`, literal at `0x401efb18` in the shell task). All of
this presupposes shell access.

### 3. UVC service thread (`0x40613f8c`) — UVC-bound

This thread (entry pointer at `0x40614b34`) waits on a queue
(`0x4006e4a4`), copies a supplied service record, and posts the event with
the supplied parameter at `0x4061404c`. It is part of the UVC/EUC service
machinery itself: it can only run once the UVC personality it would help
activate is already up. Circular for first entry.

### 4. Debug handler self-posts

The remaining `f100000f` literals (`0x405e3a64`, `0x40614084`,
`0x40614774`) are inside the debug handler and UVC service functions —
downstream of the event, not independent triggers.

## Conclusion

The follow-up report's open question — *can a normal retail action or an
already-qualified transport cause `f100000f`/`10e7` without calibration or
persistent writes?* — is answered **no** for every identified producer.
Posting the event in an emulator would not change this. Route A is closed
as a bounded negative with the same status as the earlier entry leads in
the [roadmap](ram-launch-and-recovery-roadmap.md): reopen only if a new
producer or a new transport appears.

The remaining concrete entry candidate is therefore the calibration
observation, whose risk profile for this unit is now bounded by the
[record-fixture init emulation](clbt-init-record-selection.md): no item
auto-selected, only the menu-open event queued, and a predicted two-byte
record change (`+0x04`, `+0x08`, plus checksum) as its acceptance test.
