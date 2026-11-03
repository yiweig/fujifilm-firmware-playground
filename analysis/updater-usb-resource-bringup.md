# Updater error-path helper acquires USB resources

Date: 2026-11-03. Offline original-ARM inspection and local Ghidra controls.

- Complete updater SHA-256: `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
- Main image SHA-256: `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.

Both images use base `0x40000000`; addresses below are **updater** addresses
unless explicitly labeled main. No camera access or firmware modification.

## Finding

Follow-up: [startup gate and PHY acquisition](updater-startup-phy-gate.md)
executes this helper inside the original automatic consumer. Four controls
confirm that gate zero skips it, while the modeled missing-file return path
acquires resources. Inherited hardware state and physical USB remain unknown.

Helper `0x40075ea4`, reached after automatic file-action errors, acquires
reference-counted resources and programs USB-related registers. A main-image
counterpart participates in USB initialization, and a diagnostic function
named by the embedded string `halPllUsbPhyGet` reads the same clock/PHY block.
USB resource acquisition with PHY/PLL bring-up is therefore a strongly
supported interpretation. The earlier shutdown label was incorrect.

Eight focused controls execute the original helper and its resource manager,
modeling only interrupt-mask and delay helpers. They confirm the software
state transitions and register writes. This reduces the uncertainty around
one helper previously modeled wholesale in the
[failure-continuation controls](updater-file-failure-continuation.md).
It does not execute a complete USB startup or establish physical USB operation.

## First-use sequence and reference count

Wrapper `0x40075ea4` calls `0x40075dd8(1)`. That subhelper uses word
`0x400ba450` as a reference count. With a nonzero input:

- If the count is zero, it runs the register/delay sequence below.
- It then increments the reference count and returns.
- If already nonzero, it skips the sequence and only increments the count.

The opposite input decrements the counter and writes zero to `0x10000380`
when the resulting count reaches zero. Underflow handling and all callers
are not qualified by the successful controls.

| Register write | Next argument to delay helper `0x4007a730` |
| --- | --- |
| byte `0x10000380 = 0x10` | 2 |
| byte `0x10000380 = 0x12` | 2 |
| byte `0x10000380 = 0x13` | 5 |
| byte `0x10000380 = 0x03` | 17 |
| byte `0x10000380 = 0x01` | 56 |
| byte `0x10000383 = 0x03` | 56 |

These are literal delay arguments, not verified elapsed times or units. No
PLL lock/status poll appears in this subhelper; returning does not demonstrate
that a real oscillator/PLL or USB PHY is ready.

## Resource mask and controller register

The wrapper next calls `0x400662b0(0x00080200, 0x00080200)`, then writes
byte `0x3f` to `0x10005021`. The established memory map attributes
`0x10005000..0x10005fff` to USB device/OHCI controller code.

`0x400662b0` forwards to generic resource manager `0x40066134` with bank 0,
mask `0x00080200` and acquisition bits `0x00080200`. That manager iterates
the selected bits, maintaining byte reference counts at bank base
`0x400b5d70` (32 counts per bank):

- This mask selects indices 9 and 19, addresses `0x400b5d79` and `0x400b5d83`.
- Acquisition increments each count; a zero-to-one transition contributes
  that bit to a command written to `0x10000064` for bank 0.
- Release decrements the count; a one-to-zero transition contributes its bit
  to a command written to `0x10000044` for bank 0.
- Existing nonzero users suppress the corresponding first-acquire command;
  remaining users suppress the final-release command.

These are observed software resource semantics. Clock-gate command behavior
is an inference; exact hardware names/electrical meanings for bits 9 and 19
are not assigned without a register manual or live evidence. The emulator
treats command registers as ordinary memory, not real write-one hardware.

Companion `0x40075ed4` clears `0x10005021` and releases the same two resources.
Unlike the main counterpart, this updater function does **not** call the
PHY/PLL reference-count helper with zero. It is not a complete inverse of
`0x40075ea4`, and is not proposed as a cleanup procedure.

## Main-image corroboration

Main helper `0x40025088` performs the same acquisition outline:
calls `0x40024f64(1)`, acquires mask `0x00080200` through `0x4000b608`,
and writes `0x3f` to `0x10005021`. Main release helper `0x400250b8` clears
that byte, releases the mask and calls `0x40024f64(0)`.

The main USB setup path `0x4038d764`, on its input-1 branch, calls
`0x40025088` at `0x4038d798`, then controller setup `0x400251ac(1)`.
Its error branch calls the release helper. This establishes an acquisition/
initialization role in a separate USB startup context.

Main `0x40024f64` uses the same first-use reference-count pattern and
`0x10000380` sequence. It also selects additional clock presets and uses
different delays; the two routines are **not byte-identical**. Do not substitute
one for the other or infer identical clock requirements.

Main diagnostic getter `0x40339a0c` reads control/divider fields at
`0x10000380..0x10000386` and computes a frequency-like value. Its assertion
branch at `0x40339aec` loads the actual string pointer `0x40b5a26f`, containing
`halPllUsbPhyGet`. This is an embedded firmware name, not an invented symbol.
It supports the USB PHY PLL attribution of the register block without supplying
a full register specification or actual camera clock measurement.

## Eight original-instruction controls

[InspectUpdaterUsbHardware.java](scripts/InspectUpdaterUsbHardware.java)
executes the original updater wrapper/subhelper/resource-manager instructions
against synthetic MMIO, stack and reference-count memory. Four acquisition
fixtures combine PHY/PLL reference counts 0 or 2 with resource counts 0 or 2.
Each is followed by the companion resource release, for **eight controls**.

All pass. They check the first-use byte-write sequence, delay arguments,
reference-count increments/decrements, acquisition/release command words,
controller byte, preserved control-register bytes when already acquired, and unchanged
executable bytes. The companion's retained PHY/PLL reference count is also
checked explicitly.

Only IRQ-mask helper `0x4007da98` and delay helper `0x4007a730` are modeled as
returning. There is no real waiting, clock, PHY, USB controller, scheduler,
flash or camera operation. Counter fixtures are not measured boot state.
No new REA result is claimed.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterUsbHardware.java /private/tmp/updater-usb-hardware.txt
```

Evidence: [eight controls](output/updater-usb-hardware.txt) and
[image-qualified static ranges](output/updater-usb-resource-bringup.txt).

This strengthens the interpretation that returned file errors lead toward
USB resource bring-up rather than an intentional halt. Still unverified are
the full peripheral sequence, actual attachment/enumeration and independent
entry into the updater when the main firmware cannot boot. No new live test
or firmware flash is justified by these software controls alone.
