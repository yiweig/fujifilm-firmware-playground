# Factory calibration/test menu (live photos, 2026-11-09)

Date: 2026-11-09. Photos of the stock main firmware's debug UI in
loader/calibration mode (`~/Downloads/xhalf-screenshots/IMG_4411..4420`).
The camera was not modified; the UI is the stock firmware's factory
service menu.

## Identity shown on screen

- Status header: `Ver:2.0006 MODE:1`, `LENS Ver:0.0.0`
- Menu pages: `2.0008` (calibration/parameter version)
- `Box No.:5F800978`, `TupVer:1`
- Board/unit string: `A0R58M000805`

## Menu map

| Photo | Page | Items |
| --- | --- | --- |
| 4411 | status | MB/LCD, AF Clbt, OB Clbt, WB/AGC, MsShut2R, MsShut1R, L/S_D DPC, Daylight, Dr DPC, FWB_R, FWB_B, WIFI, Dark DPC, FlashWB, **BootType**, **UsbMode**, Flash, L_D DPC, CSS_DPC, FWB_R, RCS, DUST — all `OK` |
| 4412 | CLBT INFO | FOCUS (Infinity), OB CLBT (BLACK), WB/AGC (LV10) |
| 4413 | test page | MsShut2R (LV10), MsShut8O (LV12), Light/S.S (BLACK), Dark DPC (LV10) |
| 4414 | test page | FLASH WB, MB/LCD, BU/RS, LANG. TYPE |
| 4415 | test page | AF/DEBUG/RAW, **BOOT TYPE**, **USB MODE**, AUTO CLBT |
| 4416 | dialog | `Disable` + green/gray bar + 5-bit value (`00001`/`00000`) |
| 4417 | test page | FLASH TEST, MTBF, SHOW UI ERR, **CAP RAW** |
| 4418 | test page | AF LAMP, FOCUS CAP, Lens Shad (LV10), Focus Test |
| 4419 | test page | Eye Sensor, G Sensor, UI Debug, AE Report |
| 4420 | test page | Func Check, TUP Clbt |

## String-table confirmation

The on-screen labels match the main image's calibration menu string
table at `0x40e13df0..0x40e15254`:

```text
0x40e13df0  "CLBT INFO"
0x40e146ce  "RAW TYPE"
0x40e14714  "BOOT TYPE"
0x40e14760  "Disable"
0x40e147c8  "USB MODE"
0x40e14820  "Disable"
0x40e1487c  "AUTO CLBT"
0x40e14930  "FLASH TEST"
0x40e14a98  "SHOW UI ERR"
0x40e14b4c  "CAP RAW"
0x40e14d68  "Lens Shad"
0x40e15254  "Tup Clbt"
```

The `BOOT TYPE` and `USB MODE` entries each carry a `Disable`
sub-entry. The table stores pointers to BSS values two bytes apart
(`0x40e4df6a`, `0x40e4df6c`, `0x40e4df6e`) and a small flag/preset
field. The 5-bit dialog is a **test disable/result bitfield**, not a
boot-mode or USB-personality selector.

## Interpretation

This is the stock firmware's **factory test/calibration menu**:

- `BootType: OK` and `UsbMode: OK` on the status page are **test
  results**, not the current boot/USB mode values.
- `BOOT TYPE` and `USB MODE` are **test categories**; their
  `Disable` dialog with the green/gray bar and 5 bits disables or
  records the test bits. It does not select the boot image or the
  USB personality.
- `CLBT INFO`, `AUTO CLBT`, `FOCUS`, `OB CLBT`, `WB/AGC`, the
  `MsShut*`/DPC pages, `FLASH WB`, `FLASH TEST`, `AF LAMP`,
  `Lens Shad`, `Eye Sensor`, `G Sensor`, `Func Check` and `TUP Clbt`
  are calibration/self-test pages.
- `CAP RAW` and `AF/DEBUG/RAW` are raw-capture/debug tests; they are
  the most interesting items for the original RAW goal.

The user toggled one `Disable` bit (`00001` ↔ `00000`). That changed
a test bit, not a boot mode. The camera still boots normally. Do not
change more calibration/test bits without identifying their target.

## Consequence for the boot work

The live menu does **not** provide a boot-type or USB-personality
switch. The earlier idea of using `USB MODE` to move the stock
firmware to the normal PTP personality is not supported by this UI.
The RAM-push blocker remains the unrecovered earlier-stage helper
`0x00000b34` and the mapping/cache path.

The menu does give a legitimate live surface for diagnostics and
calibration, and `CAP RAW` is a potential route back to the RAW goal.

## Boundary

The menu map and labels are observed on screen and matched to the
main image's string table. The exact semantics of the `Disable`
5-bit field (test result vs disable mask) are inferred from the
string table and the surrounding test pages; they are not confirmed
by a code-level trace of the editor.
