# Calibration init with the live record: no item selected, two-byte save diff

Date: 2026-11-04. Offline original-ARM emulation only; the camera was off and
disconnected. The fixture is the 1,024-byte factory record acquired live the
same day ([factory-PTP acquisition](factory-ptp-live-acquisition.md),
SHA-256 `58efa22442bbb7524116d4d43fcd68e916c92951cc2f94aae6ab94a0947169af`,
kept outside git). Main image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARMv7, base `0x40000000`. Freshness caveat for the record applies as before.

## Question

Two offline answers were needed before calibration entry could even be
discussed for this unit:

1. Which calibration item (if any) does `InitClbtData` auto-select from this
   record — in particular, is it item 20 (AF LAMP), the one case where the
   scheduler queues its own confirmation?
2. Exactly which record bytes would the initialization save attempt change?

## Method

[`ReproduceClbtInitWithRecord.java`](scripts/ReproduceClbtInitWithRecord.java)
adapts the existing [service-entry writes](service-entry-initialization-writes.md)
controls: same admitted instruction ranges, same modeled callees (logging,
display/capture stubs, battery query, stack memset, file read), but the file
read returns the real acquired record and RAM starts with the same bytes.
Execution starts at the true `InitClbtData` entry `0x405daa2c` and stops
**before** the save helper `0x40125e4c`. Instrumentation distinguishes the
selector's exits: `0x405da9e8` (`mov r0,#0`) executes only on the
no-candidate path; the found path branches from `0x405da9d8` directly to the
epilogue. (An earlier run with coarser instrumentation reported both exits;
the distinguishing instruction resolves it — see output.)

A fresh Ghidra project was created for this pass
(`/private/tmp/fuji-ghidra-record/FujiRecord`, raw ARMv7 import at
`0x40000000`, no analysis) because the previous `/private/tmp` project's
marker file no longer exists. The script verifies the image hash itself, so
project state does not affect the result.
Output: [output/clbt-init-record.txt](output/clbt-init-record.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-record GHIDRA_PROJECT_NAME=FujiRecord \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript ReproduceClbtInitWithRecord.java \
  /private/tmp/clbt-init-record.txt /private/tmp/xhalf-colddata-2026-11-04.bin
```

## Results

- **No calibration item is auto-selected.** The selector `0x405da930` was
  called once and took its no-candidate exit; the item-7 shortcut was not
  taken. Stored selection `[0x413e3d98+4] = 0`, confirmation choice `= 0`,
  runtime table `+0xabc` (from record `+0x168`) `= 0`. Item 20 is not
  selected; its exception cannot apply.
- **Save-boundary RAM diff is exactly two bytes:** `+0x04: 01->02` and
  `+0x08: 01->06` (the forced `Hce version info` halfwords 2,0,6; `+0x06`
  already 0). The save helper itself never executed; the writer would
  additionally refresh the byte-sum checksum at `+0x3fe` in the saved file.
  So a persisted initialization save on this unit would differ from the
  acquired record at offsets `0x04`, `0x08`, and the checksum field only —
  a precise, checkable prediction for any later entry experiment.
- The cached USB-selection flag `0x40e6696d` is unchanged (1) by init.

## What this means for the queued events (instruction-traced)

With selection 0 and `FgAutoClbt` low nibble 8, the ordinary scheduler in
`EnterCalibrationMode` (`0x405dd964`, disassembled for this pass) queues
exactly one event, `0xf0000007`. It does **not** queue the confirming pair:
that requires selected item 20 (`0x405dd990`) or runtime `+0xabc != 0`
(`0x405dd9bc`), both false here. The `0xf0000007` handler at `0x405db078`
branches on item 0 to display/state helpers and sets UI state `0xe300` —
it opens the calibration menu; it does not dispatch a worker. Worker
execution additionally needs a nonzero item, a confirmation toggle, and
prerequisite checks (`0x405db7f4`), none of which occur without further
physical interaction.

Ordering on the entry path (from [service-entry writes](service-entry-initialization-writes.md),
rechecked at `0x405dd6a4`): USB API mode 4 is selected and connected
*before* `InitClbtData` runs, provided two earlier gates pass: the
display-status check at `0x405dd660` (nonzero queues `0xf2000083` and
returns before USB — live value unknown) and the UVC selector word at
`0x413e6034` (getter `0x40613da8`; no image initializer, so zero unless set
earlier in that boot — the mode-4 branch is the expected one, not proven).

## Remaining risk statement for a hypothetical entry observation

Bounded, not eliminated:

- The initialization save attempt is real and would persist the two-byte
  version change (plus checksum) if the archive write succeeds. It is
  small, predicted, and verifiable afterwards by re-reading the record
  through the factory-PTP path and diffing against the acquired backup.
  Reverting it, if ever desired, is *not* established (the `1025(1)` commit
  has its own side effects).
- Pre-USB entry work (conditional NAND mount/DCF folder creation,
  capture/display setup, RTC wakeup-disable register intents `d0=0`,`c0=0`)
  and transitive callees remain unaudited end-to-end (see prior reports).
- The display-status gate could silently route away from USB; the UVC
  selector could theoretically be nonzero. Both outcomes are observable
  and end with power-off; neither runs a calibration worker.
- Nothing here proves a clean exit beyond "power off from a designed menu
  state," and this path still runs inside the main firmware — it is not
  cold recovery.

A safer alternative remains open before any entry attempt: finish tracing
the UVC debug-event producers (`0x4052241c`, `0x405df684` in
[uvc-debug-event-followup.md](uvc-debug-event-followup.md)), which could
reach a shell without calibration. Calibration entry should be a deliberate
user decision with the diff prediction above as its acceptance test.
