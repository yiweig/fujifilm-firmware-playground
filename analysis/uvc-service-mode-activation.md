# UVC service-mode activation

## Scope

This report traces how the firmware reaches the UVC extension-unit service that
can forward RCA/EUC commands to the internal shell. The work is static only: no
undocumented request was sent to the camera.

Analyzed ELF SHA-256:
`7e5534e40adc45c14ca19b6d1ebd0ea730ccf1d13125eedcc35d802434133755`.
Addresses below are virtual addresses in that analysis image.

## Result

The service is not a hidden operation on the normal PTP interface. It belongs
to a separate calibration/UVC startup path:

```text
power-on calibration checks
  -> event 0xF2000012
  -> EnterCalibrationMode
  -> UVC/API-test selector
  -> register `uvctestcmd`
  -> load internal A:\RO_RES\USBUVC.BIN
  -> register UVC + EUC/RCA callbacks
  -> disconnect and reconnect USB in UVC mode
```

The remaining unresolved piece is what sets the UVC/API-test selector. A setter
exists, but this firmware image contains no direct call or stored pointer to it.
That suggests the value may be supplied by a boot loader, dynamically loaded
factory component, hardware/service state, or code absent from this image.
These are hypotheses, not an identified writer. The follow-up
[service boot trace](service-boot-selection.md) identifies the surrounding
boot mask's application writer, RTC-backed reboot state, and the zero-selector
branch's explicit USB API mode-4 selection.

## 1. Power-on calibration event

`sub_40517454` identifies itself through the shipped log string
`ProcessPowerOnEvent`. It first checks boot/mount state, including the mask:

```c
(*0x40e6bc8c & 0x300002) == 0x300002
```

Inside that branch it can post event `0xF2000012`, accompanied by the log
`Start Clbt mode`. The decision includes:

- `sub_405219a0()`, which refreshes state and reads byte `0x40e4df0c`;
- `sub_40521984()`, which tests for `D:\EnableTest.txt`;
- additional storage/mount checks.

`EnableTest.txt` is therefore involved in entering calibration mode, but the
file check is nested behind other state. Static analysis does not establish
that placing this file on an SD card is sufficient, nor that doing so is safe.
The helper checks nonzero file size; an empty file fails this check.
The [button follow-up](service-mode-button-candidate.md) identifies the
special mask's PLAY/MOVIE/flash inputs and names cold-data offset `0x0a`
as the low byte of `DisableTest`. This still leaves live settings and safe
calibration entry unverified.

## 2. Calibration-event dispatch

The dispatcher entry at `0x40517b18` normalizes an `0xF2000000`-family event and
uses a jump table. Table index 18 corresponds to `0xF2000012` and reaches code at
`0x405183fc`, which performs setup and tail-branches to `sub_405dd4f0`.

`sub_405dd4f0` logs `EnterCalibrationMode` and performs extensive calibration
setup. Among other actions it selects 16-bit raw data, changes capture/display
state, registers preview callbacks, manipulates calibration logic, and may
execute selected calibration items. This is a stateful factory path, not a
harmless diagnostic menu.

## 3. UVC versus API-test selection

Within `sub_405dd4f0`, the relevant branch is:

```c
if (sub_40613da8() != 0) {
    sub_405e6828();
    log("[Clbt] UVC interface start...");
} else {
    sub_405cbb84(1, 1, 0, ...);
    ...
    log("[Clbt] API Test mode interface start...");
}
```

`sub_40613da8` returns the word at `0x413e6034`. The adjacent four-instruction
routine at `0x40613db8` stores its argument to that word. A complete scan of
direct ARM branch targets and literal pointers found no references to the
setter. Consequently:

- the getter and setter are observed;
- the value's source is unknown;
- it would be unsafe to assume the retail firmware can select UVC merely from
  an SD-card marker.

## 4. `uvctestcmd` and USB reconnection

`sub_405e6828` registers the shell command `uvctestcmd` with handler
`sub_405e67e4`. The command is also registered by case 17 of `sub_40673548`,
which logs `appUvcThreadCreate`. Further tracing identifies this as the `sys`
shell command's numeric subcommand, not an application startup event. This
route already requires shell access; the log alone does not prove that it
creates a thread. See [shell access and USB lifecycle](shell-access-and-usb-lifecycle.md).

The `uvctestcmd` handler performs the UVC transition:

1. disables or disconnects the current USB interface;
2. returns capture state to idle;
3. loads `A:\RO_RES\USBUVC.BIN` through the firmware's USB-resource API;
4. calls `sub_405e67b8` to register callbacks;
5. enables/reconnects USB.

The `A:` path is an internal read-only resource path in the firmware. It is not
the removable-card marker used by `EnableTest.txt`.

`sub_405e67b8` registers a USB callback set through selector `0x200`, then calls
`sub_4061b0bc`. The latter registers the UVC extension-unit/EUC callbacks,
including the path previously traced as:

```text
sub_4061b004
  -> RCA handler sub_40645900
  -> EUC service 0xff (`EUC_SERVNO_FWD_CMD`)
  -> shell dispatcher sub_401efacc
```

This explains how a factory UVC connection could invoke commands such as
`rcaraw`; it does not make them available through retail PTP.

## Safety assessment

Do not test `EnableTest.txt`, the calibration event, or `uvctestcmd` on the
camera yet. This route:

- changes the USB personality;
- enters calibration code that changes sensor/capture state;
- exposes file, serial, calibration, and shell operations;
- has no verified recovery procedure on the X half;
- has an unresolved selector whose initialization may be external to this
  firmware image.

The fact that the normal camera enumerated only as USB PTP is consistent with
this service path being inactive in ordinary retail operation.

## Next static questions

1. Identify every writer to the boot/mount state used by `ProcessPowerOnEvent`.
2. Determine whether `0x413e6034` is initialized by an earlier firmware stage or
   a dynamically loaded factory module.
3. Trace who dispatches case 17 (`appUvcThreadCreate`) to `sub_40673548`.
4. Recover the descriptor identity from `USBUVC.BIN` so a future passive USB
   enumeration can recognize the correct mode without sending service traffic.
5. Establish RAM loading or another verified recovery mechanism before any
   live test.

REA evidence is preserved in
`analysis/output/rea-uvc-activation-evidence.json`. Instruction-level checks
can be reproduced with `analysis/fw.py disasm`, `callers`, and `literals`.
