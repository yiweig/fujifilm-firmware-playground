# PMIC register probe via loader trampoline: negative result

Date: 2026-11-10. Live loader-mode experiment. The PMIC/charger I2C
helper cannot be called from the loader handler context.

## What was attempted

- Installed the handler-slot trampoline (TRAMP5) in loader mode.
- Called `FUN_40043220(reg, scratch+3, 0, 1)` for PMIC/charger register
  `0x00`, intending to read registers `0x00..0x25` read-only.
- `FUN_40043220` uses the main firmware's I2C bus `0x10`, a mutex
  (`FUN_4001d1dc(0x10)`), and the low-level read helper
  `FUN_4006cdf4`.

## Result

The first call (`reg 0x00`) never completed. The bootquery trigger
timed out, `_wait_call_done` could not read the control block, and the
handler-slot restore also timed out. The camera needed a power cycle /
battery pull. No I2C write was issued.

## Interpretation

The PMIC/charger I2C path depends on the main firmware's task/mutex
state. The loader handler context does not own that state, so the call
blocks. The same dependency explains why the RTC/reboot functions
(`0x40523dd0`/`0x40523e94`/`0x40041550`) hung when called from the
`usbt` payload.

A direct PMIC/power register write from the loader or `usbt` context is
not viable without a proper task context or the PMIC/board register
map. Direction 1 is a dead end in the current tooling.

## Remaining safe directions

- Static image segment loader/checksum work (feature/RAW enablement).
- Static recovery of the earlier-stage `0xb34`/SPBoot reset path.
- Stop live execution experiments; the camera has no independent
  recovery and each blocked call costs a battery pull.

The NAND was not written; the card was not modified.
