# Alternate USB-flag refresh: full handler and prerequisite controls

Date: 2026-11-01. Offline analysis only; camera not accessed. Official 2.00
main image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses are ARMv7 runtime coordinates, base `0x40000000`.

## Result

The alternate `0x0301` UI-state branch really bypasses the known RTC-writing
cleanup. The emulator now enters through the normal UI dispatcher, executes
the event handler and refresh, then returns with the staged cold-data RAM
unchanged. It no longer needs to jump directly into the interior refresh slice
for this particular experiment.

This is conditional evidence. UI state `0x0301`, resource readiness, an
already-hidden display condition, and five simulated graphics operations were
supplied. **A safe retail transition into those prerequisites is still missing.**
The experiment does not establish an end-to-end RAM-only activation procedure,
safe display behavior on hardware, or independent recovery.

## Inspected branch and callees

Event `0xf2000084`, UI state `0x0301`, dispatches via `0x404db31c` to
`0x404d51b0`. The branch begins at `0x404d52c8` and:

1. Calls `0x401575a4`, which tests byte `0x40f129c8`. Zero takes an
   initialization/wait path through `0x4000f458(0x20)` and repeated delay calls.
2. Calls `0x40530b50` (returns zero) and `0x40530b60` (immediately returns).
3. Tests the exact UI state at `0x404d52e0..0x404d52e8`. State `0x0301`
   selects `0x401fe6bc`, `0x40200a34` and `0x401fcf64`, writes UI state 1,
   then branches directly to refresh `0x404d53b8`.
4. Executes `0x40125284` and stores its inverse in cached flag `0x40e6696d`,
   returning through the no-op at `0x40530b64`.

This bypasses `0x405c7ff8`, whose RTC write at `0x405c8118` was established in
[the preceding report](temporary-usb-override.md).

| Callee | Observed role and remaining limit |
| --- | --- |
| `0x401fe6bc` | Logs `HIDE_IMAGE_OSD`; returns immediately when runtime byte `0x412c281c` is nonzero. Otherwise reaches additional graphics calls, including `0x402032c0`. The successful full-path experiment supplies the fast-return condition. |
| `0x40200a34` | Leaf routine stores display-related state in RAM at `0x40d7bcf8`, `0x40d7bcfc`, `0x412c2880`, `0x412c2884`, `0x412c2888`. No calls or direct storage/MMIO operations in its instruction body. |
| `0x401fcf64` | Calls `0x401d86b0` five times with an object base ORed with `0x14..0x10`, second argument zero. These five calls are simulated. |
| `0x401d86b0` | Resolves graphics objects, acquires/releases graphics synchronization and changes object state. The `gfx_semaphore.c` string at `0x40186d44` supports this classification. Its lower paths include `0x4019a76c` and `0x401af890`; they are outside the emulator's admitted path and not fully audited here. |

The five operations should not be called timers: graphics-object lookup and
semaphore code provide better evidence of their subsystem. No claim is made
that every possible lower graphics path is free of persistent side effects.

## Expanded emulator and controls

[`ReproduceTemporaryUsbSetting.java`](scripts/ReproduceTemporaryUsbSetting.java)
still checks the original image hash and retains the previous staging,
interior-refresh and restoration assertions. Its new full-handler experiment
starts at `0x404db31c` with:

- UI state `0x0301` and event `0xf2000084`;
- resource-ready byte `0x40f129c8 = 1`;
- display fast-return byte `0x412c281c = 1`;
- synthetic graphics-object base `0x10000000` at `0x412c26e4`;
- synthetic cold-data word `0x0114`, staged earlier through the original PTP
  RAM-copy branch.

The only external calls simulated during the successful full-handler refresh
are the five `0x401d86b0` operations. Their exact arguments and count are
checked. Original instructions execute the dispatcher, readiness getter,
display fast return, display-state leaf, object-operation wrapper, setting
getter, flag store and return. No log, file, RTC or mode-setter call is admitted
in that successful path. It returns with UI state 1, cached flag zero, and the
full 1,024-byte staged cold-data RAM unchanged.

Negative controls stop before additional effects:

| Supplied change | Observed boundary |
| --- | --- |
| Display fast-return byte zero | Enters display cleanup and stops at its log call, before the remaining display work |
| Resource-ready byte zero | Reaches `0x4000f458` initialization/wait path and stops |
| UI state `0x0300`, with UI byte `+0x1025` set | Reaches broader cleanup `0x405c7ff8` and stops before it executes |

The `+0x1025` value is supplied to select that control's branch; no live value
is assumed. The display and readiness controls do not claim those paths are
necessarily dangerous; they demonstrate additional unqualified prerequisites.

All seven assertions, including the three earlier composition controls, passed.
Output: [`output/temporary-usb-setting-alternate.txt`](output/temporary-usb-setting-alternate.txt).
The earlier output file remains a historical record of the smaller experiment.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting-alternate.txt
```

## Reachability remains separate

The current Ghidra database reported 216 write references to UI field
`0x40e66958`. A bounded inspection of the 56 bytes preceding each write,
together with immediate-`0x0301` searches, did not identify a qualified normal
control sequence that sets this state and preserves the staged setting.
Literal matches in property-building code were not accepted as UI-state writes
merely because the numeric value matched. Computed writes, wider dataflow,
descriptor-driven state changes and incomplete database analysis remain gaps.

The experiment supplies event delivery and prerequisites; it does not run the
entry transition, task scheduling, resource readiness, physical display,
factory PTP registration/transport, or later setting saves. Returning to state
1 also differs from directly supplying state `0x8000` for the subsequent loader
event in the earlier experiment. The connection transition between those states
must still be traced; the experiments must not be presented as one continuous
real-camera sequence.

The next useful target is a concrete producer of UI state `0x0301` and its
transition back into connected USB handling, including any cold-data reloads
or later persistence. No card marker, button combination, PTP write or firmware
flash is warranted by this conditional branch result.

## Evidence and reproduction

Original-byte `analysis/fw.py disasm` ranges (end exclusive):

```text
404db31c..404db3dc   UI dispatcher
404d51b0..404d55dc   event handler, state branch, refresh and literals
401575a4..401575e8   readiness wait
40530b50..40530b68   leaf hooks
401fe6bc..401fe724   hide-display fast return and additional calls
40200a34..40200a6c   display-state RAM leaf
401fcf64..401fcfc4   five object operations
401d86b0..401d8700   object-operation wrapper
40186400..4018689c   object lookup and literals
40186c70..40186d48   graphics synchronization and source string
```

Focused Ghidra exports are retained locally at
`/private/tmp/usb-refresh-alternate.txt` and
`/private/tmp/usb-refresh-state-writers.txt`. The existing local Ghidra 12.1.4
ARMv7 project was used after the previously documented REA mapping issue.
No live runtime or fresh REA evidence is asserted.
