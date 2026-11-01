# Service-mode startup controls: candidate identified offline

Date: 2026-10-31. Official firmware 2.00; main-image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7, little-endian, runtime base `0x40000000`. Camera not accessed.

## Result and scope

The strongest candidate startup state is **MOVIE selected, flash switch ON,
and the dedicated PLAY button held during startup**. The original firmware's
input sampler and bit-remapping instructions give these controls exactly the
three bits required by the outer calibration gate.

This identifies the controls, not a confirmed retail service-mode procedure.
The later `DisableTest`/SD-file check can still prevent entry. The precise
physical timing, the camera's live factory settings, and the safety of entering
calibration have not been validated. No button experiment or SD marker was
performed or prepared.

## Three inputs, three boot-mask bits

The original sampler is `0x40122ef4`; the boot remapper is the tail of
`0x400df12c`, beginning at `0x400df3f0`.

| Physical control / state | Electrical observation | Sampled mask bit | Boot-condition bit |
| --- | --- | ---: | ---: |
| PLAY held | `0x1000903c` bit 5 low | `0x00004000` | `0x00000002` |
| STILL/MOVIE at MOVIE | GPIO bank 4, pin 14 low; register `0x100009d0` bit 14 | `0x00000010` | `0x00100000` |
| Flash switch ON | `0x1000a10d` bit 5 high | `0x00000080` | `0x00200000` |

The combined contribution is `0x00300002`. Other power-source and input bits
can remain set: the consumer tests `(boot & 0x00300002) == 0x00300002`,
not equality of the entire boot word. A retained reboot selection can also
produce the special mask through another branch; this is not the only route.

### PLAY identification

`0x40122f74..8c` samples `0x1000903c` bit 5, inverts it, and inserts it
at sampled-mask bit 14. Normal key processing at `0x40123590..a8` maps
that bit to event `0xf000000f` through `0x40123170`.

The shipped diagnostic message table row at `0x40e0e31c` contains:

```text
id 0x0b, name MSG_KEY_PLAYBACK, press 0xf000000f, release 0xf000002f
```

This establishes a playback-button input. Fujifilm's
[parts manual](https://app.fujifilm-dsc.com/en-int/manual/x-hf1/about_this_camera/parts/index.html)
identifies a dedicated PLAY button separately from the frame advance lever.
The lever-review path studied earlier is `MSG_KEY_L2`; it should not be used
as the meaning of this PLAY input.

### MOVIE identification and polarity

At `0x40122f20..48`, the sampler calls `0x40043454(4,14)`, negates its
boolean result and inserts sampled-mask bit 4. The GPIO wrapper calls
`0x4000f98c`; table entry `0x40adc604` selects `0x4002bb38`, which reads
`0x100009d0` and masks it with the supplied bit mask.

Two independent application views identify the control:

- `0x406739d8..fc` prints the cached video state and the inverted GPIO
  reading with the string `IS_VIDEO_BUTTON_PRESSED` / `IS_IO_KEY_MVS_PRESSED`.
- At `0x40401ae0..1b24`, a mismatch with a low GPIO reading posts
  `0xf0000012`. The view-main dispatcher comparison at `0x4041abcc..d0`
  sends that event to `0x4041b6d4`, which sets the cached video flag to 1
  and later logs `SWITCH to VIDEO mode` at `0x4041b73c..40`.
  The opposite event clears the flag at `0x4041bad0..d4` and logs
  `SWITCH to STILL mode` at `0x4041bccc..d0`.

This supports MOVIE, rather than just an unspecified active switch position.
The owner manual names the physical control STILL/MOVIE.

### Flash identification and polarity

`0x40122f50..5c` copies `0x1000a10d` bit 5 into sampled-mask bit 7.
Normal processing at `0x401235c8..e0` maps a set bit to `0xf0000014`;
release processing at `0x40123440..60` maps it to `0xf0000034`.
Message-table rows `0x40e0e2ec` and `0x40e0e2dc` name those events
`MSG_KEY_SWITCH_FLASH` and `MSG_KEY_SWITCH_FLASH_RELEASE`.

The normal view initialization at `0x4046edd4..f4` maps a high reading to
flash-mode byte value 2 and a low reading to 5. The flash-ON handler at
`0x4042118c..a0` logs `SWITCH to Flash ON` and stores value 2; its OFF
counterpart at `0x404211e4..f8` logs `SWITCH to Flash Off` and stores 5.
This independently corroborates the high/ON interpretation.

### Shutter inputs are separate

The same sampler reads `0x1000a10e` bits 0 and 1 as the two shutter stages.
Normal message generation maps bit 0 of that register to `MSG_KEY_SNAP1`
and bit 1 to `MSG_KEY_SNAP2`, after inversion and repacking. The AFC-monitor
log at `0x40407940..64` explicitly labels the bit-1 register reading as
`IS_S2KEY_PRESSED`. Neither shutter stage supplies the three bits above.
The offline experiment leaves both shutter inputs released.

## Timing and remaining startup gate

The identified startup sampling occurs during `App_PreBootProc` stage 1:
`0x400df6a0` calls the boot-mask builder and `0x400df6a4` stores its result
at `0x40e6bc8c`. `ProcessPowerOnEvent` later uses this saved snapshot at
`0x405174e0..fc`. It does not resample these three controls at that comparison.

There is no demonstrated post-startup hold-duration gesture in this path.
Inputs need to be active when preboot sampling occurs, but exact milliseconds,
required hold duration, power-switch versus PLAY-wake ordering, and debounce
behavior have not been established. Earlier power-source branches can alter
or bypass the remapping tail and are outside the bounded emulation below.

After the outer gate passes, `0x4051755c` calls `0x405219a0`, which reloads
1,024 bytes of `A:\RO_RES\COLDDATA.BIN` and reads its byte at offset `0x0a`.
Diagnostic code `0x4051bcc0..c8` prints the halfword at offset `0x0a` using
the shipped name **`DisableTest`**. Thus startup tests that field's low byte.

If the byte is zero, the inspected branch posts the calibration events.
Otherwise it checks `D:\EnableTest.txt`; this helper requires a nonzero
file size. With stable nonzero `DisableTest` and a missing/empty file, this
path falls back rather than posting calibration entry. Actual code repeats
some checks, so this summary assumes their inputs remain stable.

The camera's live field value is unknown. The hardware combination passes the
outer mask check without requiring a rewrite of `FgBootType`/`FgUsbMode`, but
does not bypass `DisableTest`. No marker-file test is recommended here.

Follow-up: [the override assessment](disabletest-override-assessment.md)
confirms the marker's size-only check and distinguishes it from `clbt entest`,
which persistently clears the saved flag. Post-entry safety remains unresolved.

## Bounded execution of the original ARM code

[`scripts/ReproduceServiceBootSelection.java`](scripts/ReproduceServiceBootSelection.java)
copies the original image into Ghidra's software emulator, verifies its SHA-256,
and supplies synthetic RAM and peripheral-register values. It executes:

1. `0x400df3f0` through `0x400df474`, including the real input sampler,
   GPIO wrapper, dispatch table and low-level GPIO read;
2. the outer gate at `0x405174e0`, stopping before its accepted branch's
   setup or at the rejected-path boundary.

No executed call is stubbed, and all MMIO accesses go to emulator memory.
The earlier power-source code, SD/cold-data reads, calibration entry and USB
transition are not executed. Synthetic `FgBootType=8` and `FgUsbMode=8`
disable the alternative factory-settings route through the outer gate;
these are test inputs, not measurements of this camera.

All eight input combinations were tested with each supplied base reason 1
and 4 (16 executions). Representative results:

| PLAY | MOVIE | Flash ON | Sampled input | Boot word, base 1 | Outer gate |
| --- | --- | --- | --- | --- | --- |
| no | no | no | `0x00000000` | `0x00000001` | rejected |
| yes | yes | no | `0x00004010` | `0x00100003` | rejected |
| yes | no | yes | `0x00004080` | `0x00200003` | rejected |
| no | yes | yes | `0x00000090` | `0x00300001` | rejected |
| yes | yes | yes | `0x00004090` | `0x00300003` | accepted |

All 16 matched the expected mapping; with base 4, all-three produced
`0x00300006` and was also accepted. This validates instruction-level dataflow,
not physical runtime behavior or the full startup procedure.

Reproduce using the existing ARMv7 project:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceServiceBootSelection.java analysis/output/service-button-emulation.txt
```

Use an absolute output path if running Ghidra from another working directory.
The script leaves firmware/program bytes unchanged. The report is generated
locally; the source script and this curated finding are tracked.

## What happens after entry: audit remains partial

Calibration event `0xf2000012` reaches `0x405183fc` and tail-calls
`EnterCalibrationMode` at `0x405dd4f0`. Before selecting USB mode, it changes
capture/display state, requests 16-bit raw data and installs preview callbacks.
Its later zero-UVC-selector branch explicitly connects USB API mode 4.

Automatic calibration behavior is conditional. A branch recognizes exact boot
words `0x204`, `0x404`, `0x804` and `0x1004`; these are not the emulated
combination's `0x00300003`/`0x00300006`. The other path calls `0x4051d918`,
which tests whether `FgAutoClbt` (cold-data halfword `+0x6c`) has low nibble 8.
If true, it posts calibration-work events, depending also on current item and
calibration state. It is therefore unsafe to characterize entry as simply
opening a passive menu. This does not prove that the discovered combination
automatically runs a calibration on this particular retail camera.

Not all transitive hardware and storage operations have been audited, and
there is no verified exit/recovery test. Identification is substantially
stronger now, but it is still insufficient to endorse a live test.

## Question status

| Question | Status |
| --- | --- |
| Which controls contribute the special mask? | identified with firmware cross-checks |
| What polarity/position? | MOVIE, flash ON, PLAY held, supported statically |
| When sampled? | preboot stage identified; physical timing unresolved |
| What saved setting gates entry? | `DisableTest` low byte plus SD-file check identified; live values unknown |
| What executes on entry? | stateful calibration/USB setup identified; complete side-effect audit unresolved |
| Safe, confirmed retail procedure? | not established |
