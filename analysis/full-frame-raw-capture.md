# Full-frame RAW capture (selector 19): real capture, blown exposure

Date: 2026-11-04. Via the [APITEST shell](apitest-shell-live.md) in a
loader/calibration session, following the
[preview-frame success](first-raw-capture.md). Analysis of the resulting
file was completed offline after the live session.

## Live result

Single console-client run, command `rcaraw 19 D: x` (dispatches
`snap attr raw=1; snap cap; snap attr raw=0`). Console highlights
(transcript preserved at `/private/tmp/console-snap.log`):

```text
exp#0 fif load A:\RO_RES\EXPOSURE.BIN
----- Enter OpenCalib50Mode, Item:0 ---------
AWB CapG:1023 Num 252
psInfo->uiSizeX = 4864
psInfo->uiSizeY = 3648
uiRawFormat = 4
icatImgFile Write W...(0x605a6500,4864x3648, 35487744bytes) - OK
```

A file named **`W`** appeared on the SD root, plus a 640×480 companion
JPEG `DSCF1049.JPG`. The reference scene JPEG taken in normal mode
minutes earlier (`DSCF1048.JPG`, 1/125 s, f/2.8, ISO 1600, normally
exposed: mean RGB 119/102/78, median luminance 115) confirms the scene
and lighting were ordinary.

## File format — solved

`W` is 35,488,256 bytes = 4864×3648×2 + 512, SHA-256 `8ae9c81c…`,
preserved at `/private/tmp/xhalf-snap-first.bin`. The extra 512 bytes
are a **trailer**, not a header, so the frame starts at offset 0:

- Last 512 bytes begin with the ASCII magic `{iCatCh}`, then u16
  fields: `9728` (line jump in bytes = 4864×2, matching the console),
  `3648`, `4864`, `3648`; the remaining ~480 bytes are zero.
- Frame data: little-endian u16 words, 12-bit left-justified
  (`raw12 << 4`), same packing as the preview frame.

## The frame is genuinely saturated — not a parsing error

- 17,737,468 of 17,744,128 words (99.96%) are `0xFFF0` (full-scale
  4095). Only 236 distinct values occur in the whole file.
- The companion `DSCF1049.JPG` is **100% pure white** (every one of
  its 307,200 pixels is 255,255,255). Its EXIF carries ISO 100 but no
  exposure time or f-number, unlike the normal-mode reference.
- The only non-saturated structure: 6,660 words, 85% of them at
  B-channel Bayer sites (odd row, odd column) in columns ~2300–2430,
  values 1867–4087 — a faint remnant in the least-sensitive channel.
  R and both G channels are at full scale everywhere (per-channel
  means are 4095.0).

So the still-capture pipeline itself produced a blown frame. Decoding
(offset, stride, packing, CFA) is not the problem.

## Why: calibration capture, not AE capture

`snap cap` inside the calibration personality does not take a normal
auto-exposed photo. The console shows it entering
`OpenCalib50Mode, Item:0` — the daylight-AWB factory calibration
capture (`Set ITEM_DAYLIGHT_AWB AWB gain`, `AWB CapG:1023`) — with the
exposure loaded from the resource table `A:\RO_RES\EXPOSURE.BIN`
(`exp#0`), i.e. fixed factory calibration exposure/gain intended for a
standardized light source, not the scene-metered exposure the preview
path (and `pvraw`) uses. Offline checks confirm there is no gentler
variant to select instead:

- `rcaraw` (`0x4031a820`) implements only selectors 17 (preview) and
  19 (still); all other selectors fall through and do nothing
  ([rcaraw-handler.md](rcaraw-handler.md), decompilation this session).
- `snap` subcommands are only `attr cap captest dztest zoom abort job
  dumpjob`; `snap attr` keys (`fast= bkpv= auto= burst= redo= speed=
  chgraw= size= dual= sep= ime= qual= …`) contain no exposure, gain,
  or ISO control.

## The missing control: `aeset`

The CDSP shell also registers **`aeset`** (handler `0x4031872c`,
decompiled to [output/aeset-handler-decomp.txt](output/aeset-handler-decomp.txt)).
Its built-in help strings define:

```text
aeset                          info dump: sensor modes, PV/Cap exp+agc, index limits
aeset aeon / aeoff             enable / disable AE
aeset on / off                 TestMode all on/off
aeset pv #exp #agc #iris       set preview TestMode exposure indices
aeset cap #exp #agc            set capture TestMode exposure indices
aeset exp #idx #sensormode     look up one exposure-table entry (VCcount, 100 µs units)
aeset agc #idx #sensormode     look up one gain-table entry
aeset explist #sensormode      list exposure index range for a sensor mode
aeset agclist #sensormode      list gain index range for a sensor mode
```

Handler semantics verified in the decompilation: bare `aeset` and
`aeset cap` are read-only info dumps (explicit `argc` checks — no
bare-invocation crash hazard of the `rcaraw` kind); `aeset cap <exp>
<agc>` enables capture TestMode (`4011d120(…,1,1)`) and applies the
indices (`4011d098`), with `0x7fffffff` as the leave-unchanged
sentinel for omitted trailing arguments. Indices are positions in the
sensor's exposure/gain tables (snap sensor mode is 46 per the console
identification), so the table lookups must be read live before picking
values.

## Next live session (designed, not yet run)

One loader entry, console client, in order:

1. `aeset` — record current PV/Cap exp/agc indices and limits.
2. `aeset explist 46` and `aeset agclist 46` — index ranges for the
   snap sensor mode; `aeset exp <idx> 46` spot lookups to convert a
   candidate index to an exposure time.
3. `aeset cap <low exp> <low agc>` — short exposure, low gain.
4. `rcaraw 19 D: x` — capture; expect a new `W` (it overwrites).
5. Exit, inspect the card offline as before.

Success criterion: per-channel means well below 4095 with scene
structure present. A too-dark frame is also a pass for control
purposes (it proves the indices govern the calibration capture) and
brackets the usable range. Fallback if TestMode indices are ignored
by the calibration item: reduce light physically (dim scene) — the
preview path proves the sensor/AE side is healthy.
