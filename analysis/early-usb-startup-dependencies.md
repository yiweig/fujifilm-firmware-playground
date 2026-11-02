# Early USB startup still depends on main-image initialization

Date: 2026-11-01. Official 2.00 main image, runtime base `0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Offline disassembly, local Ghidra pseudocode, and original-ARM dispatcher controls.
No camera access, USB transfers, firmware writes or service-mode experiments.

## Finding

Loader callback registration occurs in lower-level main-image startup, before
the camera UI. It does not itself select the loader, create its USB tasks or
connect the camera. The inspected normal connection path creates the USB task
infrastructure on demand, then performs separate controller configuration.

The previously found early command-shell branch **skips the normal startup
routine containing that callback registration**. With an empty callback table,
the inspected USB reset/init event reaches an assertion boundary, not a
fallback that registers the loader. This is an additional initialization gap
for the early-shell idea; it is not proof that every possible shell-assisted
initialization sequence fails.

All traced functions are in the main firmware image. Moving earlier within
that image does not establish a recovery service independent of it. A separate
ROM USB loader may exist, but these bytes do not establish its entry condition
or behavior. Nothing here supports a new button or cable-timing experiment.

## Callback registration and the skipped branch

The inspected normal startup call chain is:

```text
40003498
  40003608: early-shell selector == 0 -> normal branch
  40003700 -> 400f86f4
    400f8888 -> 40234a7c -> 402e0e34   other USB personality registration
    400f888c -> 40234a80 -> 401e2104   target-0 loader registration
    400f8890 -> 40234a84 -> 401bd000   target-3 registration
    400f8898 -> 40234a8c(0)           further USB/PTP initialization
```

These addresses refer to actual original instructions. The names target 0 and
target 3 describe array indices, not UI USB API-mode numbers.

The early-shell branch instead executes `0x401efc90` at `0x4000360c`, calls
`0x4000f688(0)` and branches at `0x40003618` directly to common tail
`0x40003740`. It skips the call at `0x40003700`. The bounded aligned ARM
caller census finds only this normal-startup chain to wrapper `0x40234a80`
and registration entry `0x401e2104`; indirect/computed calls remain possible.

Registration routine `0x401e2104` calls `0x401ba258` with target 0 and pointers
to initializer `0x401e122c` and vendor callback `0x401e1280`. The original
registration function only stores seven callback words in arrays rooted at
`0x40fc7b78`. It does not invoke those pointers or write selected-target byte
`0x40fc7b8c`. Registration controls now supply targets 0 and 2 **before**
registration and verify that it preserves the chosen target.

Follow-up: [shell-startup watchdog and SD reader](sd-command-reader-reachability.md)
identifies the optional call from `cmdTaskInit` to `0x401ef13c` as creating
task `Wdg`, not as an SD command-file reader. The separate SD reader still
lacks a known retail caller.

The early shell's complete task and any other command-file behavior are not
audited in this pass. No claim is made about actual retained callback-table
contents, undocumented earlier registrations or whether a shell user could
explicitly initialize missing subsystems. UART access remains outside the
user's no-disassembly constraint. The earlier shell branch is still part of
the installed main image, not a recovered boot-ROM entry.

## Deferred task setup and controller work

The separate connection API `0x4038d764(1)` first checks its connected flag
at `0x4138e5c8`. Its inspected initialization route includes storage helper
`0x4038ec7c` and hardware helpers; their complete effects remain governed by
the [USB lifecycle audit](shell-access-and-usb-lifecycle.md).

At `0x4038d7c0..0x4038d7d0`, if initialization byte `0x4138e5c9` is zero,
it calls `0x401e024c`, then sets that byte to 1. A separate earlier predicate
can instead take another return path, so creating the infrastructure is not
unconditional on every connection request.

Static inspection of `0x401e024c` identifies:

| Part | Evidence |
| --- | --- |
| Four task allocations | Sizes `0x11b4`, `0x21b4`, `0x21b4`, `0x9b4` through `0x401e020c`; task entries include `0x401e0e6c`, `0x401ba2b8`, `0x401ba2f8`, `0x401c1ac8` |
| Queue/pool setup | Queue creation via `0x4002a310`, pools through `0x40070cf8`; allocation failures reach diagnostic assertions |
| Interrupt/event callbacks | Installs pointers through `0x40008f28`, including USB callbacks `0x401df264` and `0x401df3d0` |
| Controller initialization | Indirect call at `0x401e0638` resolves to `0x40055dec`, which branches to `0x40055d50` |
| Initial work scheduling | Calls message constructor `0x401bd1fc(0)`, stores event halfword 0, submits to queue 0 through `0x401e0090` |

`0x40055d50` writes USB MMIO in the `0x10005000` range and runtime state;
it is not mask-ROM code. Queue-0 consumer `0x401e0b98`, event 0, marks a
runtime byte and forwards event 0 through helper `0x401e0b64` to queue 1.
Neither inspected initializer routine registers target-0 callbacks itself.

After task setup, the connection path separately reaches `0x4005717c`,
`0x40055e88(0)` and `0x40056594(1)` at `0x4038d820..0x4038d830`.
The last helper only stores its argument in RAM byte `0x40e4e81e`; calling
that helper alone a hardware-enable operation would overstate its body.
`0x40055e88` actually updates MMIO byte `0x10005104`. Exact electrical behavior
and host enumeration were not measured here.

The guard at `0x4038d694` starts with `CMP r0,0`, before the push at
`0x4038d698`. Pseudocode starting at the push inherits an unexplained condition
flag and is not a complete function. Original bytes take precedence.
The local database entry was repaired with
[`FixUsbGuardEntry.java`](scripts/FixUsbGuardEntry.java); corrected pseudocode
is retained locally at `/private/tmp/usb-guard-correct-entry.txt`. This changes
function metadata only, not firmware instructions.

## Reset/init dispatch requires an existing initializer

Queue-1 dispatcher `0x401ba030` recognizes event 0, logs `USB reset`, then
tail-calls `0x401b9ebc`. That helper reads the selected target byte, fetches
its registered initializer, and checks for null. Missing initializer reaches
assertion helper `0x401bd278` with source line `0xb3`, before the indirect call.
With a registered initializer, it calls that pointer before proceeding to
descriptor processing and more queued work.

This is internal USB message event 0, not a host opcode. It does not install
or discover a fallback loader when the callback table is empty.

[`ReproduceRetailUsbControl.java`](scripts/ReproduceRetailUsbControl.java) now
adds four original-instruction controls to its previous sixteen routing cases:

| Supplied target | Initializers registered? | Stop boundary |
| --- | --- | --- |
| 0 | No, supplied zero table | `0x401bd278`, null-initializer assertion |
| 0 | Yes, original registration executed | `0x401e122c`, loader initializer |
| 2 | No, supplied zero table | `0x401bd278`, null-initializer assertion |
| 2 | Yes, original registration executed | `0x402e4f80`, PTP initializer |

All **20 cases pass**. The four new cases preserve the full supplied global
memory snapshot and make no allocation or enqueue calls. Only logging is
modeled on those paths. Execution stops before either initializer or assertion
helper executes. Thus the test shows the original dispatch decision, not an
actual camera assertion/crash, hardware reset or initialized USB connection.
The zero table and selected target are fixtures; their actual early-shell
values have not been measured. Full `0x401e024c` task setup and queue delivery
are static traces, not emulated or observed startup behavior in this pass.

Output: [`output/early-usb-registration-controls.txt`](output/early-usb-registration-controls.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceRetailUsbControl.java /private/tmp/early-usb-registration-controls.txt
```

Static checks used `analysis/fw.py disasm`, `literals` and `callers` at the
addresses above. Caller scans cover aligned ARM branches in the first
`0xab0000` image bytes and exact stored pointers, not all indirect calls or
Thumb code. Local Ghidra export `/private/tmp/early-usb-init.txt` supports the
inspection, except for the guard-entry caveat above. This continues the
documented local-project fallback; no new REA runtime evidence is claimed.

## Recovery implication

We have distinguished registration, task setup, selected-personality
initialization and connection. None supplies evidence of an independently
reachable recovery path. The missing evidence remains the stage **before**
the main image: an exact-body boot-ROM/stage-loader artifact, a documented
boot contract, or observed early-boot state. The application receiver cannot
establish that guarantee by itself, even if it runs before the camera UI.
