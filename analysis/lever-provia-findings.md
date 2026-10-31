# Provia label during lever review

The firmware's `MSG_KEY_L2` review path contains a concrete initialization defect that explains the reported symptom. It clears a temporary photo record to zero and passes that record to the film-label renderer without initializing its film/filter selector. Zero is a valid selector for Provia. The renderer consequently skips metadata resolution and draws the Provia asset.

Comparison with official firmware 1.20 and 1.30 strongly identifies this as a
2.00 regression. Both older versions have the same lever-review pattern—clear
a temporary record, copy 30 bytes, and draw the film label—but their renderer
resolves the film choice directly from playback metadata. Version 2.00 enlarged
the record, added the selector-at-`+0x1a0` shortcut, and failed to initialize
that new field in this caller. See
[the version comparison](firmware-1.30-vs-2.00.md).

This was verified against the ARM instructions and reproduced with Ghidra's emulator. It has not been tested on the camera. The identification of the firmware's L2 action with the physical lever follows its behavior and diagnostic messages; the electrical button mapping was not independently traced.

## The relevant instructions

All addresses in the RAM column assume `firmware-main.bin` imported at `0x40000000`, ARM little-endian 32-bit v7. DAT offsets refer to the original `FPUPDATE.DAT`.

| Role | RAM address | DAT file offset |
| --- | --- | --- |
| `UIMode_ViewMain` entry, name supported by diagnostics | `0x4041AB6C` | `0x093FE88C` |
| L2 branch, logs `MSG_KEY_L2` at `0x4041C8FC` | `0x4041C8CC` | `0x094005EC` |
| Clear temporary photo record | `0x4041CD34` | `0x09400A54` |
| Copy 30 bytes into another part of that record | `0x4041CD4C` | `0x09400A6C` |
| Call `PB_DrawFilmSimuFilter` with that record | `0x4041CD7C` | `0x09400A9C` |
| Metadata-to-selector helper, analyst description | `0x40561F64` | `0x09545C84` |
| `PB_DrawFilmSimuFilter`, name supported by diagnostics | `0x405621D4` | `0x09545EF4` |
| Tail call to secondary film renderer | `0x4056226C` | `0x09545F8C` |
| `ViewMenuOsdSubDrawFilmMenu`, name supported by diagnostics | `0x402294D4` | `0x0920D1F4` |
| Select Provia resource for index zero | `0x402295CC` | `0x0920D2EC` |
| Comparison: playback initialization writes unknown selector | `0x4055E3B4` | `0x095420D4` |

The decisive caller sequence is:

```asm
4041cd28  mov  r1, r6          ; r6 = 0 on this successful path
4041cd2c  mov  r2, #0x1ac
4041cd30  add  r0, sp, #0x14c
4041cd34  bl   0x4000cdf0      ; memset(record, 0, 428)

4041cd38  ldr  r1, [r5]       ; psCapture
4041cd3c  add  r2, sp, #0x14c
4041cd40  add  r0, r2, #0xde
4041cd44  add  r1, r1, #0xca
4041cd48  mov  r2, #0x1e
4041cd4c  bl   0x40008b8c      ; memcpy(record + 0xde, psCapture + 0xca, 30)
...
4041cd74  add  r1, sp, #0x14c  ; same temporary record
4041cd78  mov  r0, #1
4041cd7c  bl   0x405621d4      ; PB_DrawFilmSimuFilter(1, record)
```

The copy fills offsets `[0xde, 0xfc)`. The film/filter selector at offset `0x1a0` is outside this range and remains zero. The intervening calls configure drawing buffers; they do not receive the record. The code temporarily sets playback `eEnterMode` to 5 so the renderer chooses the secondary-screen path, then restores the previous mode.

Inside `PB_DrawFilmSimuFilter`, the metadata helper is called at `0x40562214`. That helper starts with:

```asm
40561f64  ldrb r3, [r0, #0x1a0]
40561f68  cmp  r3, #0x29
40561f6c  bxne lr
```

In other words, metadata is consulted only when the selector equals `0x29` (41, outside the valid 0–40 selector range). With a zeroed record the helper returns immediately, treating zero as an already resolved value.

For selector zero, tables used by `PB_DrawFilmSimuFilter` choose the film renderer with film index zero. `ViewMenuOsdSubDrawFilmMenu` selects the string `A:\RO_RES\PNG\FIC\FSIM\PRO.PNG` through the literal at `0x402296B0`.

The normal playback initialization routine at `0x4055E32C` explicitly stores `0x29` into record offset `0x1a0` at `0x4055E3B4–0x4055E3B8`. The lever-review path omits this initialization.

## Simplified reconstruction

These are explanatory variable names, not recovered source declarations:

```c
// L2 / last-image review
memset(&temporary_photo, 0, 0x1ac);
memcpy((byte *)&temporary_photo + 0xde, (byte *)capture + 0xca, 30);
// temporary_photo.effect_selector at +0x1a0 is still zero.
draw_photo_effect(true, &temporary_photo);

// Inside the renderer's metadata helper:
if (photo->effect_selector != 0x29)
    return;  // zero looks like a valid, already resolved Provia selection

photo->effect_selector = resolve_from_playback_metadata();
```

The main image and its label take separate paths. Later in the same L2 branch the firmware uses capture buffers for the image and performs a slide animation. Its diagnostics include `bL2Available`, `L2 Key Buffer Fail`, and `psCapture->nL2Slide2PBCnt`. This explains how the photograph can be correct while the separate secondary-screen graphic is wrong.

## Reproduction

[ReproduceLeverSelection.java](scripts/ReproduceLeverSelection.java) executes the original instructions starting at `0x4041CD28`, including the real memory-clear and copy routines, through resource selection at `0x40229628`.

It supplies synthetic capture/playback RAM and metadata. Logging and three display-setup calls are stubbed. It stops before actual graphics rendering. This is a bounded reproduction of the selection defect, not a camera emulator.

| Supplied metadata | Original caller | Experiment: set `record[0x1a0] = 0x29` before drawing |
| --- | --- | --- |
| Provia | `PRO.PNG` | `PRO.PNG` |
| Velvia | `PRO.PNG` | `VE.PNG` |
| Classic Chrome | `PRO.PNG` | `CC.PNG` |
| Acros | `PRO.PNG` | `AR_S.PNG` |

All eight executions passed. The experiment changes emulator RAM only. See [the execution output](output/lever-emulation.txt).

To repeat from the repository root using the existing analysis project:

```sh
bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceLeverSelection.java analysis/output/lever-emulation.txt
```

## What a fix still needs

Setting the unknown-selector value demonstrates the immediate defect, but is not yet a complete fix. The resolver reads a global playback-metadata object via the pointer at `0x40E0F51C`. We must establish whether this object contains the most recently captured photo's metadata during lever review. It could instead be empty or left over from an earlier playback operation.

The correct behavior requires populating the selector from the last captured image, including Acros variants and filters, even if the user changes shooting settings before pressing the lever. The next investigation should trace how capture-time metadata is saved and whether the lever path refreshes the playback metadata. Simply showing the current shooting setting could produce a different wrong label.

No flashable patch was produced. Container integrity/signature behavior and auto-ISO changes remain outside this focused investigation.

## Provenance and earlier leads

`FUN_...` names in Ghidra are generated address labels. The descriptive names above come from actual diagnostic strings, not an original symbol table:

- `UIMode_ViewMain`: string at `0x40B900CE`; referenced via literal `0x4041BB6C` within the handler.
- `MSG_KEY_L2`: string at `0x40B92E9F`, pointer at `0x4041BC4C`.
- `PB_DrawFilmSimuFilter`: string at `0x40BD599E`. The code loads `0x40BD5984` and adds `0x1a`, which is why searching for a literal pointer to the exact name found nothing.
- `ViewMenuOsdSubDrawFilmMenu`: string starts at `0x40B21ED8`, pointer at `0x402296A4`.

The earlier `ViewOsdSubDrawFilmSimulatio` string is real, including its truncated spelling. However, its routine at `0x4043B580` is called by the `dosd` diagnostic command handler at `0x4066C888`. That caller is not the L2 review path. The PATORONE selector at `0x404323A4` belongs to film-camera-mode graphics and is also not the defect identified here.

Supporting files:

- [Instruction and literal extracts](output/lever-evidence.asm.txt).
- [Source-file hashes and mapping verification](output/lever-source-verification.json).
- [Annotated main-handler decompilation](output/view-main.annotated.txt), around line 800 onward.
- [Annotated playback decompilation](output/playback-film.annotated.txt).

The decompilations contain imperfect function boundaries and may inline unrelated tail-called code. The conclusions above use the verified instructions and emulated execution, rather than trusting those artifacts alone.

The extracted main image is DAT `[0x08FE3D20, 0x09E1C120)`, loaded at `0x40000000`. Therefore `DAT_offset = RAM_address - 0x3701C2E0` for this image. This is a file-to-runtime mapping, not a flash programming address.
