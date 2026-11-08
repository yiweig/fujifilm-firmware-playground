# `D:\SaveRaw.txt` and the built-in AWB RAW dump

Date: 2026-11-08. Ghidra 12.1.4 headless decompilation of the official
2.00 main image (`firmware-main.bin`, SHA-256 `80350bd2…`, loaded at
`0x40000000`). No camera access in this pass.

## Summary

The stock firmware has a **built-in RAW dump in the AWB capture
path**, gated by a file on the SD card:

```text
D:\SaveRaw.txt exists
  -> AWB_CheckRawFile sets a RAM flag at 0x41311cd2
  -> the AWB pipeline calls FUN_402ce16c
  -> if the flag is set, FUN_402cdad8 writes
     D:\DCIM\RAW<width>x<height>_<stride>.RAW
```

The file is written alongside the normal capture, not as a separate
firmware mode. This is a stock feature, not a patch.

## The chain, with addresses

### 1. The check: `FUN_40203014` (`AWB_CheckRawFile`)

At `0x40203014`:

```c
printf("AWB_CheckRawFile S\n");
iVar1 = FUN_4013f4ac("D:\\SaveRaw.txt", 0x10);
if (iVar1 != 0) {
    FUN_402ce1ec(1);          // set flag
    FUN_4013f604(iVar1);      // close
    printf("Set AWB Save RAW.\n");
    return 0;
}
FUN_402ce1ec();               // clear flag
printf("AWB_CheckRawFile E\n");
return 1;
```

- The path literal `D:\SaveRaw.txt` is at `0x40b1c8bf`; the function
  references it at `0x40203020`.
- `FUN_4013f4ac(path, 0x10)` is the file-open/exists call (mode
  `0x10`).
- `FUN_402ce1ec(param)` writes the flag byte at
  `*(0x41311c08 + 0xca) = 0x41311cd2`.

### 2. The trigger: the `v3a` shell command

The shell command table at `0x40e17e48` has a 12-byte entry:

| Field | Value |
| --- | --- |
| name | `0x40ce5341` = `v3a` |
| help | `0x40ce5345` = `View 3A` |
| handler | `0x4060fedc` |

`FUN_4060fedc` is the **View 3A** debug dispatcher. It reads the first
argument at `*(param_3 + 4)` and switches on it. **Case `0x3B`** calls
`FUN_40203014()` (`0x406103fc`), i.e. `v3a 0x3b` runs the
`AWB_CheckRawFile` check.

(The adjacent `flash` entry at `0x40e17e54` is a different command,
handler `0x40611780`; it is not the SaveRaw trigger.)

Other entries in the same table include `cap` (`Deb_CaptureStill`,
handler `0x406799f4`), `pvrawsave` (`Deb_PvRawSave`, `0x406764a8`),
`pvyuvsave` (`Deb_PvYUVSave`, `0x406764a4`), `pvrawsize`
(`Deb_PvRawSizeSet`, `0x406764f4`), `sensor`, `menu`, `fd`, and more.
This is a factory/debug shell command set.

### 3. The consumer: `FUN_402ce16c`

At `0x402ce16c`:

```c
FUN_402cddb4(auStack_64);
FUN_402cd798(auStack_98);
FUN_402cdf5c(auStack_98, param_1, param_3, param_4);
FUN_402b68a8(auStack_98, &local_a0);
if (*(char *)(DAT_402ce1e8 + 0xca) != 0) {
    FUN_402cdad8(param_1, param_2, param_3);
}
```

`DAT_402ce1e8` is `0x41311c08`; `+0xca` is the same flag. The flag
read is at `0x402ce1c4`.

The AWB capture paths call this function:

- `FUN_4049f0c8` at `0x4049f20c`
- `FUN_4049faa8` at `0x4049fc74`

Both build a descriptor from `param_4`:

```c
local_2c = param_4[0];       // width
local_28 = param_4[1];       // height
local_24 = width;            // stride
if (param_3 != 0) {
    local_24 = width * 2;
    if (param_3 != 4) local_24 = width * 3 >> 1;
}
local_20 = param_2;          // data pointer
local_1c = ...;              // format selector from param_4[0x13] & 0x7f
```

So `param_3` selects the pixel layout:

| `param_3` | Stride | Likely format |
| --- | --- | --- |
| `0` | `width` | 8-bit RAW |
| `4` | `width * 2` | 16-bit RAW |
| other | `width * 3 / 2` | 12-bit packed RAW |

### 4. The writer: `FUN_402cdad8`

At `0x402cdad8`:

```c
uVar3 = *param_3;                       // width
if (param_2 != 0) {
    if (param_2 != 4) uVar3 = (uVar3 * 3 & 0x1ffff) >> 1;
    else uVar3 = uVar3 << 1;
}
uVar3 &= 0xffff;
FUN_4000ceb0(auStack_40, "%s\\DCIM\\RAW%d_%dx%d_%d.RAW", "D:",
             counter, *param_3, param_3[1], uVar3);
printf("WB Raw X:%d Y:%d J:%d path:%s\n", ...);
iVar2 = FUN_4013f4ac(auStack_40, 0x25);  // create/write
if (iVar2 != 0) {
    FUN_401408e8(iVar2, param_1, param_3[1] * uVar3);
    FUN_4013f604(iVar2);
}
(*(char *)(0x40df7878 + 5))++;
```

- Filename format at `0x40b37d3d`:
  `%s\DCIM\RAW%d_%dx%d_%d.RAW`
- Drive prefix at `0x40b51aac`: `D:`
- Log format at `0x40b37d58`: `WB Raw X:%d Y:%d J:%d path:%s\n`
- Counter base `0x40df7878`, byte `+5`.

The write size is `height * stride`, i.e. the full RAW frame.

### 5. The capture callback registration

The `cap` command (`FUN_406799f4`) calls the capture API
`FUN_4043f7d8`, which calls `FUN_4043f618` (capture setup) and
`FUN_4043f458`. The capture-task setup `FUN_404a0124` calls
`FUN_4049ff5c`, which registers the AWB callbacks:

```c
thunk_FUN_401ad970(0, 0xe, FUN_4049fcb8);
thunk_FUN_401ad970(0, 0xf, FUN_4049fe1c);
```

`FUN_4049fcb8` and `FUN_4049fe1c` both call `FUN_4049faa8` with the
RAW buffer and descriptor; `FUN_4049faa8` calls `FUN_402ce16c`,
which calls `FUN_402cdad8` when the SaveRaw flag is set. The path is
asynchronous: `cap` sets up the capture, and the capture task invokes
the callbacks.

### 6. The other RAW/debug hooks

The debug command table at `0x40e17dc4` has 71 entries. The
RAW/capture-related ones are:

| Command | Handler | Behavior |
| --- | --- | --- |
| `pvrawsize` | `0x406764f4` | sets preview RAW size via `FUN_40183944(0x11,0x11,0x80000001,...)` |
| `pvrawsave` | `0x406764a8` | retrieves the preview buffer via `FUN_40183dfc(0x111,...)`, then `FUN_406423a0` → `FUN_406421f8(0xff)` |
| `pvyuvsave` | `0x406764a4` | `FUN_406423a8`, writes `D:\%04d.YUV` |
| `cap` | `0x406799f4` | still-capture trigger |
| `sensor` | `0x40679aac` | sensor mode switch test |
| `v3a` | `0x4060fedc` | View 3A debug dispatcher |
| `flash` | `0x40611780` | flash debug (unrelated to SaveRaw) |

The preview RAW writer `FUN_406421f8` uses these filenames:

| Mode | Filename |
| --- | --- |
| `0` | `D:\SEARCH_%04d_%04d.RAW` |
| `1` | `D:\SHARPNESS_%04d_%04d.RAW` |
| `2` | `D:\BLUR_%04d_%04d.RAW` |
| `0xff` | `D:\INVALID_%04dX%04d_%04d_%04d.RAW` |

`pvrawsave` uses the `0xff` (INVALID) mode, so it is a debug
fallback, not the normal RAW path. The YUV save writes
`D:\%04d.YUV` with size `height * width * 2`.

The `v3a` dispatcher has 53 cases. Cases `0x28`–`0x35` are the 3A
debug cluster:

- `0x28`–`0x2b`: AE/AWB stat getters and setters.
- `0x2c`–`0x2f`: 3A stat getters (`+0x6a8`, `+0x6ac`, `+0x6aa`, `+0x6ae`).
- `0x30`: AE/AWB lock/assign control.
- `0x31`: sets EvBias (`Set EvBias %d`).
- `0x32`–`0x34`: misc 3A getters.
- `0x35`: `AE_WriteMEVCheckFile`, writes a 12-byte Tv/Av/Sv record to `D:\MEV.bin`.
- `0x3B`: `AWB_CheckRawFile` (the SaveRaw gate).

## Decompilation artifacts

- [v3a-dispatcher-decomp.txt](output/v3a-dispatcher-decomp.txt) — the 53-case `v3a` dispatcher.
- [saveraw-check-decomp.txt](output/saveraw-check-decomp.txt) — `AWB_CheckRawFile`.
- [debug-raw-hooks-decomp.txt](output/debug-raw-hooks-decomp.txt) — `pvrawsize`/`pvrawsave`/`pvyuvsave`/`cap`/`sensor`/`flash`.
- [preview-raw-writers-decomp.txt](output/preview-raw-writers-decomp.txt) — preview RAW/YUV writers and buffer getter.
- [v3a-3a-cluster-decomp.txt](output/v3a-3a-cluster-decomp.txt) — the 3A debug cluster.
- [awb-raw-callers-decomp.txt](output/awb-raw-callers-decomp.txt) — the AWB capture callers.
- [capture-callback-decomp.txt](output/capture-callback-decomp.txt) — the capture dispatcher and callback registration.

## What this means

- **This is a stock RAW capture feature**, not a firmware patch. It
  writes the AWB raw frame to the SD card's `DCIM` directory.
- The gate is a file: `D:\SaveRaw.txt` at the root of the SD card.
- The trigger is the debug-shell command `v3a 0x3b` (handler
  `0x4060fedc`, case `0x3B`).
- The AWB pipeline then calls the save function during normal
  capture, so the file should appear next to the JPEG.
- The raw format is selected by the capture path's `param_3`
  (8-bit / 16-bit / 12-bit packed); the filename records width,
  height and stride, so the file can be parsed without guessing.

## Open questions

1. **Does `v3a 0x3b` work in the APITEST/debug shell, and does the
   flag persist into a normal capture?** The flag is in RAM at
   `0x41311cd2`; the debug shell and the camera OS share the main
   firmware. Test: put `SaveRaw.txt` on the card, enter debug mode,
   run `v3a 0x3b`, read the flag, then run `cap` or take a normal
   photo.
2. **What exactly does `cap` (`Deb_CaptureStill`, handler
   `0x40679aac`) do in the debug shell?** If it triggers the same
   capture path, the RAW file should appear without leaving debug
   mode.
3. **What is the actual `param_3` value during a normal capture?**
   The filename's third number (stride) tells us; `width*2` implies
   16-bit, `width` 8-bit, `width*3/2` 12-bit packed.
4. **Is the RAW frame Bayer or already processed?** The AWB context
   suggests a raw Bayer frame, but the pipeline may have applied
   some processing. The first captured file answers this.
5. **Other debug commands.** The same table exposes `pvrawsave`
   (`Deb_PvRawSave`), `pvyuvsave`, `pvrawsize`, `sensor`, `menu`,
   `fd`, etc. These are additional stock capture/debug hooks worth
   mapping.

## Boundary

The decompilation is from the official image; addresses and string
literals are observed. The flag behavior, capture-path `param_3`
values, and file contents are not yet observed on hardware. The
`v3a 0x3b` trigger is a shell command in the debug firmware; its
availability and persistence in a normal capture are the next live
question.
