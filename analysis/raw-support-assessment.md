# X half RAW capture feasibility assessment

## Bottom line

The X half firmware can acquire and write unprocessed sensor buffers through
engineering/debug paths. Exposing those buffers for off-camera conversion is
plausible. Adding polished, user-facing Fujifilm RAF or DNG capture is a much
larger project and is not supported by evidence of a dormant production RAW
feature in this firmware.

No firmware or camera state was modified during this investigation.

## Observed raw-capture infrastructure

REA found a debug handler at `0x4031A820` associated with the literal
`cmdCdspRcaRaw`. One branch constructs and executes this command sequence:

```text
snap attr raw=1
snap cap
<wait approximately 2.5 seconds>
snap attr raw=0
```

This is direct evidence that the shipped executable contains an engineering
route for requesting a raw still capture. It is not evidence that this command
is exposed through the retail UI or reachable without service/debug mode.

REA evidence:

- Handler instructions: `ev_85076aff8f639f8cef3bfa9e609ccb4f182a0d3687ff97b6254787e5aa802477`
- `raw=1` and `raw=0` references:
  `ev_2ffd32a74cecdb4d5c54c2a4d3163f26ee25920f1eadf8ee2831758e353c5d95`,
  `ev_d1f9f31f22d2df3b4ab2e9b0d371233c2caee5362fc0f8d9a29604e482c5b7a4`
- Command-name string: `ev_3f2ca1e5420493e680a327fce035508191b6c1a6003f157ed61b9679f82391a7`

The same executable contains dedicated raw-frame/file queues and tasks, plus
debug/calibration file names such as:

```text
%s\DCIM\RAW%d_%dx%d_%d.RAW
D:\DCIM\RAW.RAW
```

It also contains declarations for 8-, 12-, and 16-bit capture-raw formats and
logs reporting raw dimensions, buffer sizes, bit depth, and write progress.
These facts show that the platform knows how to materialize sensor buffers as
engineering `.RAW` dumps. They do not establish a documented file layout or a
consumer-compatible container.

REA evidence:

- Raw-format strings: `ev_04cbe63531bcf7162c14a0c8520be50d5ac483fc4fd6050b47b45d17dcda363a`
- File-name format: `ev_46362a0c3ed4c82dc968d0ec07a777d532b016d3c56305095e9c126405d29033`
- Calibration raw path: `ev_3405d239577eb33c8bb8da85e71cff483caf4e9926608b01bac6d099f65f45fb`
- Raw file-task strings: `ev_c13e4df812a497579d28d304c2a236a81fd66c9abd755bfb1d24dcd939566d6`
- File-task function instructions: `ev_35c3c256c2681e5d4624f7675cad071f9649d7579e0118b73eee1b73bebc7fb3`

## Observed production photo path

The normal still-image path contains a `snapYuv2ExifJpgWrite` operation and DCF
file-name formats ending in `.JPG`, for example:

```text
%c:\DCIM\%u%s\%s%04u.JPG
```

This indicates the production path converts the processed YUV image into an
Exif JPEG and registers it with the camera's DCF storage layer.

REA evidence:

- JPEG/Exif writer string and references:
  `ev_1b6fbd21036f911c446a424c4891e0c87e67d2b999143e04d56294a79bafa822`,
  `ev_2728122df697e3213996c14cfb57371b3f87360bc27007eda99c35cfd0926451`
- Writer function instructions:
  `ev_d606110832fdf819a9bce075197d95e4d0c40b2ac9457df4fbc9d7c82ea6004a`
- DCF JPEG path and references:
  `ev_8e5129309db9536e14101f8202c886ee8a22aa1e838f53328850cf07800b0a750`,
  `ev_fe8c1b4958eeb1e16b248a5f1c97b5f7a90a59186b7e1b93de6c4168cf7a717d`

## Bounded negative findings

REA's typed-string inventory of the main ARM executable found:

- no `.RAF` file extension;
- no `.DNG` or DNG reference;
- no `RAW+JPEG` or `JPEG+RAW` label;
- no obvious RAW-development or RAW-conversion feature label.

A separate printable-string scan of the complete `FPUPDATE.DAT` container also
found no `.RAF`, `.DNG`, `RAW+JPEG`, or `JPEG+RAW` literal. Incidental `RAF`
substrings were parts of unrelated AF/debug text or random data.

These are bounded negative searches. Compressed resources, dynamically built
strings, unidentified code, or indirect calls could conceal additional support.
The findings therefore do not prove that a RAF writer is absent. Combined with
the explicit JPEG production path and engineering `.RAW` paths, they make a
simple hidden-feature toggle unlikely.

REA evidence:

- `.RAF`: `ev_4efc9df0c26beaef9805cf1f3b351b96482609211296bf41de84218ec9fc7299`
- DNG: `ev_b9082642f2b3e0469f8672ffad49c120de9b9711c8f053b784b48f799c4dd7ce`
- `JPEG+RAW`: `ev_e368409e4eafb3bcc906bf24558ba2869dafc0c692d868f5ef47f2e9599edd74`
- `RAW+JPEG`: `ev_5e92864c0c9992e6d43872bacd5b388ba3ae634d7495d6b6a73e90cbd83cad73`

## Feasibility by goal

### Engineering sensor dump

Feasibility: medium to high at the firmware-logic level.

The capture and file-writing machinery already exists. Work would include
finding a safe way to invoke it from normal shooting, choosing a stable file
name, confirming buffer lifetime and write completion, and handling errors and
card capacity. The first validation should use a RAM-only hook or existing
debug command, not a flashed patch.

### Sensor dump plus desktop DNG conversion

Feasibility: medium and the most practical route to editable files.

After obtaining a sample dump, a desktop converter would need to determine:

- active dimensions, row stride, and padding;
- packing and actual bit depth;
- CFA layout and orientation;
- black and white levels;
- bad-pixel treatment and optical-black margins;
- color matrices and lens metadata.

JPEG Exif can supply exposure and lens metadata while the raw dump supplies
pixel data. DNG is preferable to inventing a RAF encoder because DNG is openly
documented and broadly supported.

### Native in-camera RAF or DNG

Feasibility: low to medium; substantial implementation work.

This would require a container writer, preview/thumbnail generation, metadata,
DCF integration, file numbering, card-space accounting, playback behavior,
USB/Wi-Fi transfer behavior, UI/settings persistence, and robust handling of
interrupted writes. A Fuji-compatible RAF writer also requires reverse
engineering the relevant RAF metadata and sensor payload conventions. Current
evidence does not show that production implementation waiting behind one flag.

## Safest next investigation

1. Compare this firmware with a closely related Fuji body on the same platform
   that supports RAF. Shared functions could reveal whether a production writer
   was removed, retained but disconnected, or never linked.
2. Trace how the `cmdCdspRcaRaw` handler is registered and whether it can be
   invoked through an existing non-persistent service interface.
3. Establish RAM-only execution and recovery before invoking it on the camera.
4. Capture one engineering `.RAW` file and characterize it offline.
5. Only then design a normal-shutter hook and desktop DNG converter.

This route separates the highest-value experiment—proving that the camera can
save a usable sensor dump—from the much riskier work of altering the normal
capture and storage pipeline.
