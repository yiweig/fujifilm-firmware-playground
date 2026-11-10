# usbt reset-payload attempt: negative result

Date: 2026-11-10. Live vendor `usbt` experiment. The main firmware's
`clbt reboot`/RTC path does not work from the SPBoot upload/run stub.

## What was sent

After entering `usbt` and re-enumerating as `2aad:6500` interface 5:

- BootMethod query `0xC0/0x70`, 11 bytes, `wValue = 36` returned
  method 2 and upload address `0x417b8c00`.
- `0x40/0xFD`, `wIndex 0x04f7`, 12-byte setup
  `[0x417b8c00, 36, 0x417b8c00]`, then 36 bytes over bulk OUT `0x03`.
- Payload called the main firmware's RTC/reboot functions:
  `0x40523dd0(2, 0)` then `0x40523e94()`.

## Result

The setup and payload transfers were accepted. The post-run
BootMethod query timed out. The USB device remained enumerated as
`2AAD:6500` (same location), but the vendor stub no longer responded.
The camera needed a battery pull.

## Interpretation

The main firmware's `clbt reboot`/RTC path depends on state and
task/clock context that the `usbt` handler's host-suspend has stopped.
Calling `0x40523dd0`/`0x40523e94` from the SPBoot payload context
hangs; it is not a safe reset.

This is the same failure class as the earlier `bx lr` and
`0x40000200` payloads: the stub executes the entry and does not
return, and the camera has no independent reset once the CPU is hung.
The vendor RAM execution primitive remains real, but a safe reset or
return payload is still missing.

## Next candidates

- A direct PMIC/RTC power/reset register write that does not depend on
  the main firmware's task state (needs the PMIC register map and a
  safe way to test it).
- A payload that re-enters the boot stub's command loop or the main
  firmware's reset vector with the correct CPU/memory state.
- Static recovery of the earlier-stage `0xb34`/SPBoot code to find the
  stub's return/reset path.

The NAND was not written; the battery pull recovered the camera.
