# Normal UI path to the USB RAM loader

Date: 2026-11-01. Offline disassembly, Ghidra pseudocode and ARM emulation.
No camera commands, marker files, saved-setting changes or USB transitions.
Target: official 2.00 main image, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
runtime base `0x40000000`, little-endian ARMv7.

## Finding

**A calibration-free application path to USB loader mode exists.** Normal UI
USB-event handlers can select API mode 4 and reconnect without calling
`EnterCalibrationMode`. The best traced instance is event `0xf0000044` delivered
to UI group `0x8000`.

Its practical barrier is a runtime byte derived from saved factory setting
`FgUsbMode`, rather than `DisableTest`. No temporary retail-accessible override
was established. Changing the saved setting would modify factory data; it is
not an appropriate first experiment. This path still requires the working main
application and does not establish independent recovery.

## Event, gate and saved setting

Dispatcher `0x404db31c` routes UI group `0x8000` to `0x404d665c`. That handler
recognizes `0xf0000044` via literal `0x404d6fa0`, branching to `0x404d6724`.
It consults these runtime fields:

| Address | Role |
| --- | --- |
| `0x40e66958` | UI state halfword (`0x40e65950 + 0x1008`) |
| `0x40e6696b` | Cached USB state byte (`+0x101b`) |
| `0x40e6696d` | USB selection flag byte (`+0x101d`) |
| `0x40123bf8` | USB/cable-state getter, supplied synthetically in emulation |

The log `IS_EN_USB_TEST_MODE = %d` receives **the inverse** of the flag at
`+0x101d`. Calling that byte itself an enabled-test flag would invert its role.
For Boolean values, original instructions show:

```text
flag == 0 -> 0x405cbb84(1), 0x4038d6d0(4), 0x4038d764(1)
flag == 1 -> return when cached USB state equals the getter;
             otherwise follow the ordinary USB-handling branch
```

The zero-flag branch at `0x404d6768` reaches mode-4 call `0x404d68b4`, then
the connection tail at `0x404d6aa0..0x404d6aac`. Helper `0x405cbb84` selects
the `USBSTR.TXT` resource path and configures the release descriptor through
`0x405cbb48`. API 4 selects target 0 and the expected loader identity as traced
in [the RAM receiver report](xhalf-usb-ram-receiver.md).

This is a direct-path trace, not proof that descriptor setup, controller
transitions, resource loading or pending I/O have no persistent side effects.
The [USB lifecycle audit](shell-access-and-usb-lifecycle.md) found conditional
filesystem remounts; those limits continue to apply.

Getter `0x40125284` reads halfword `0x40e4df02 + 0x6a` (`FgUsbMode`) and
returns zero when its low nibble equals 8, otherwise 1. It also stores whether
bits `8..11` are nonzero at runtime byte `0x40e7ae50`.

| Flag writer | Source |
| --- | --- |
| `0x405174a8..0x405174c0` | Power-on setup stores the inverse of that getter |
| `0x405180a4..0x405180b8` | Another power/event branch reloads the same setting |
| `0x404d53b8..0x404d53cc` | UI handler reloads the same setting |
| `0x4051d5f4..0x4051d608` | Factory-related handler reloads the same setting |
| `0x404d4e78..0x404d4e7c` | UI initialization clears the byte to zero |

Saved low nibble 8 therefore produces flag 1 (ordinary handling); other low
nibbles produce flag 0 (test/loader branch). The initial zero is **not** a
demonstrated startup race or button recipe. Initialization order and external
event reachability in that interval remain unproved. The camera's actual saved
word and runtime byte were not read in this pass.

The writer search checked every aligned ARM `MOVW` immediate `0x101d` in the
first `0xab0000` image bytes: 26 matches, including a diagnostic line-number
coincidence. Surrounding instructions and base pointers identify the writes
above. Exact pointers to `0x40e6696d` were absent. This does not exclude other
addressing forms, block copies, computed writes, Thumb or earlier-stage code.

No SD filename check appears in these assignments or the zero-flag UI branch.
The known `EnableTest.txt` and `EnablePTPAP.txt` checks belong to other paths
and do not establish an override here.

## Event source and other mode-setter callers

USB-related callback `0x404e0a20`, referenced by pointer `0x404e7788`, handles
event `0xf1000036`. It updates runtime state through callbacks and, when
`0x40123bf8()` is nonzero, posts `0xf0000044` through `0x4006f330` at
`0x404e0b08`. This ties the event to ordinary USB handling: it is not a vendor
request number or a PTP operation to send from the host.

`PwrPowerSaving` code also returns this event for a wakeup result, without
thereby changing the USB flag. Of five exact byte-pattern hits for the event,
the unaligned hit at `0x40155c5b` is in numeric data and was not accepted as an
event reference. Computed event construction and other delivery remain possible.

An aligned ARM B/BL scan found 31 direct branches to `0x4038d6d0`, no exact
pointer occurrences for that API entry, and one direct caller of lower setter
`0x401b9fcc` (inside this API). Thirteen sites explicitly pass mode 4:

| Call site(s) | Context |
| --- | --- |
| `0x4031d50c` | Internal `usbt` shell command |
| `0x404d4fec` | UI group `0xe000` handler; verified entry `0x404d4f9c` |
| `0x404d6200`, `0x404d68b4`, `0x404d7550`, `0x404d8804`, `0x404d9c7c`, `0x404daab4` | Other UI-group USB handling; `0x404d68b4` traced and emulated here |
| `0x405181d8` | Power/event handler, conditional on runtime USB state and zero flag |
| `0x40523654` | Internal `clbt usb` command |
| `0x405c88f0` | Helper called when leaving UI group `0x7000` |
| `0x405dd6cc` | Existing calibration entry |
| `0x406737a4` | Internal `sys` command, case 4; selects resource `USBTEST.TXT` |

Seventeen other direct sites pass modes 1, 2, 3 or 5. The remaining site,
`0x4031d57c`, passes a parsed value: shell command `usbms`, table entry
`0x40dfc180`, verified function entry **`0x4031d548`**. Its diagnostic string
is `[sp5kUsbModeSet] mode: %d`. This is another shell path, not a retail menu.
These scans do not establish the absence of all computed/indirect calls.

The `sys 4` filename `USBTEST.TXT` is selected through the resource API. It is
not evidence that a same-named SD marker executes this command. Likewise,
`0x405c88c4` is called by `LeaveUIGroups` (`0x404d5b9c`) for a departing
`0x7000` group; it is not a generic external mode-entry event.

Some routines begin one instruction before their push prologue, including
`0x404d4f9c` and `0x4031d548`. Starting at the push omits a literal load or
condition-code setup. Prologue-search labels are not verified function entries.

## Offline execution and reproduction

[`ReproduceNormalUsbLoaderGate.java`](scripts/ReproduceNormalUsbLoaderGate.java)
checks the original image hash, then executes original ARM instructions in
Ghidra's emulator. It never accesses the camera or host USB APIs.

The getter was executed for all 16 low-nibble values with high flag `0x100`;
only nibble 8 returned zero. Then the dispatcher and event handler ran for
`0xf0000044`, UI state `0x8000`, and eight Boolean flag/cached/cable combinations:

| Flag | Cached versus supplied cable state | Result |
| --- | --- | --- |
| 0 | All four combinations | Mode-setter boundary with `r0 == 4` |
| 1 | Equal (two cases) | Returned without mode setter |
| 1 | Different (two cases) | Ordinary USB branch boundary |

All 24 cases passed. Logs and descriptor helper `0x405cbb84` were stubbed;
the cable getter returned supplied values. Event delivery was supplied at the
dispatcher. Execution stops before mode selection or ordinary USB work. Queue
delivery, controller behavior, descriptor setup, resource loading, calibration,
USB lifecycle and scheduling are not validated. The assignments connecting
saved setting to runtime flag were inspected statically; these are separate
emulation stages, not a full startup simulation.

Compact output: [`output/normal-usb-loader-gate.txt`](output/normal-usb-loader-gate.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceNormalUsbLoaderGate.java /private/tmp/normal-usb-loader-gate.txt
```

Use `analysis/fw.py disasm` at the ranges above and `callers 0x4038d6d0
0x401b9fcc` for the census. Focused pseudocode for `404d665c`, `405c88c4`,
`40517b1c` and `40673548` was exported to `/private/tmp/normal-loader-entry.txt`
with the existing Ghidra 12.1.4 ARMv7 project. Original instructions resolve
ambiguous decompiler types. This uses the documented local-project fallback
after REA's raw-image mapping issue; no new REA result or live behavior is claimed.

## Next question

Search for a temporary retail-accessible writer/input that changes USB selection
without saving `COLDDATA.BIN`. Current direct commands (`usbms`, `sys 4`,
`clbt usb`) require shell access; known USB shell transports require a different
personality first. The startup clear is insufficient for an activation procedure.

If no temporary writer exists, reading the existing factory setting can tell us
which route the camera permits but cannot create an override. Nothing here
justifies changing saved settings, probing vendor requests on ordinary PTP, or
flashing firmware. Independent recovery remains a separate missing prerequisite.
