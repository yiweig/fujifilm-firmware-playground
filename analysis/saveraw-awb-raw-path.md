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

### 2. The trigger: the `flash` shell command

The shell command table at `0x40e17e50` has a 12-byte entry:

| Field | Value |
| --- | --- |
| handler | `0x4060fedc` |
| name | `0x40ce534d` = `flash` |
| description | `0x40ce5353` = `Flash Debug Func` |

`FUN_4060fedc` is a debug dispatcher. It reads the first argument at
`*(param_3 + 4)` and switches on it. **Case `0x3B`** calls
`FUN_40203014()` (`0x406103fc`), i.e. `flash 0x3b` runs the
`AWB_CheckRawFile` check.

Other entries in the same table include `cap` (`Deb_CaptureStill`),
`sensor`, `pvrawsave` (`Deb_PvRawSave`), `pvyuvsave`, `pvrawsize`,
`menu`, `fd`, and more. This is a factory/debug shell command set.

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

## What this means

- **This is a stock RAW capture feature**, not a firmware patch. It
  writes the AWB raw frame to the SD card's `DCIM` directory.
- The gate is a file: `D:\SaveRaw.txt` at the root of the SD card.
- The trigger is the debug-shell command `flash 0x3b` (handler
  `0x4060fedc`, case `0x3B`).
- The AWB pipeline then calls the save function during normal
  capture, so the file should appear next to the JPEG.
- The raw format is selected by the capture path's `param_3`
  (8-bit / 16-bit / 12-bit packed); the filename records width,
  height and stride, so the file can be parsed without guessing.

## Open questions

1. **Does `flash 0x3b` work in the APITEST/debug shell, and does the
   flag persist into a normal capture?** The flag is in RAM at
   `0x41311cd2`; the debug shell and the camera OS share the main
   firmware. Test: put `SaveRaw.txt` on the card, enter debug mode,
   run `flash 0x3b`, read the flag, then run `cap` or take a normal
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
`flash 0x3b` trigger is a shell command in the debug firmware; its
availability and persistence in a normal capture are the next live
question.
