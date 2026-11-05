# First RAW capture: preview frame written and decoded

Date: 2026-11-04. Via the [APITEST shell](apitest-shell-live.md) in a
loader/calibration session. Console transcript retained in the VM
(`~/console.log`, copied to `/private/tmp/console-pvraw.log`).

## Command and console result

Single console-client run, command `rcaraw 17 D: x` (syntax qualified in
[emulator controls](output/rcaraw-args.txt); a bare `rcaraw` crashes the
handler on a garbage `argv[2]` — data abort, no persistent effect, see
below):

```text
rcaraw 17 D: x
cmdCdspRcaRaw::
[0] 17
[1] D:
[2] x
rcaraw(pv) start...
Save pv raw frames
[cmdPrvRaw] argv:2, agc:3(2,1,1,)
Pv raw save mode [single]
Request save number [1]
Sensor-0, Size[1624 x 408] [16 Bit]
cmd>
```

The handler dispatches `pvraw 2 1 1` internally, as traced. Completion was
immediate at the prompt; the frame write finished asynchronously.

## The file

On the SD root after the session: a file literally named **`D`**
(the destination-argument parsing makes the drive letter the name),
1,325,184 bytes = **1624 × 408 × 2**, SHA-256
`38d288d9e41ec115b22ce99888f4cf401b0efaf625dc66d0635f41d96d7161b3`
(preserved at `/private/tmp/xhalf-pvraw-first.bin`; removed from the card
during cleanup).

Format, measured:

- 16-bit little-endian words, **every word's low nibble is zero** —
  i.e. 12-bit sensor data left-justified (`raw12 << 4`).
- Channel means (RGGB layout): R 2932, G ≈4760, B 1618 (in >>4 units),
  max 2909/4095 — a normally exposed, unsaturated frame.
- A 2×2-block demosaic render (812×204, simple /4 scaling) shows a
  recognizable, correctly structured desk scene (cables, monitors),
  rotated 90°, with the strong green cast expected of un-white-balanced
  linear data. **This is a genuine Bayer image from the camera's sensor**
  (Sony IMX283 per the console identification).

## The bare-command crash (for the record)

An earlier argument-less `rcaraw` in the same session lineage crashed
Core 0: `DABT FaultAddr 0x2000001f` at `strcmp` (`0x40021768`) from
`0x4031a88c`, because the handler reads `argv[0..2]` without an argc
check and the one-token invocation supplied a garbage pointer. No
calibration/firmware write occurred before the fault (it died parsing
arguments); recovery was a battery pull. Lesson built into the protocol:
never invoke shell commands without their full argument set, and
qualify syntax in the emulator first (as was done for this capture).

## Next

1. Still-capture RAW via selector 19 (`snap attr raw=1; snap cap`):
   full-frame 4864×3648 class output (console sizing), same
   pre-qualification discipline; check file name/size/format.
2. Characterize black level/CFA phase/orientation precisely; pair a
   same-scene JPEG for white balance and color reference.
3. Build the desktop RAW→DNG converter (open format, no RAF writer
   needed) and validate in a standard editor.
