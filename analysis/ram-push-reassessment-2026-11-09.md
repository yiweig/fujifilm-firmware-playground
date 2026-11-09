# RAM-push reassessment: the post-handoff UI is the stock main firmware

Date: 2026-11-09 (later session). Live loader-mode experiments
with the stock updater and the stock main image, plus hang/marker
payloads. No persistent change; the camera boots normally.

## The result that forces the reassessment

After a **hang-patched updater** (`card-updater-hang340.bin`,
SHA-256 `0e6d143f…`, self-loop at `0x0/0x200/0x338/0x340`) was
pushed:

- the device re-enumerated as `2aad:6500`, 3 vendor interfaces
  0/2/5, `bcdDevice 2.00`;
- the loader bootquery **timed out**;
- the debug UI was **live** — PLAY opens the menu and the
  film/playback lever navigates it;
- it did **not** hang.

The updater image does not drive the display. A live debug menu
therefore means the **main firmware's UI task is running**, not the
pushed updater. The camera returned to the **stock main firmware's
calibration/debug mode**. The pushed updater never executed.

The same live-UI result was observed after the main-image pushes
and the 666 KB truncated payloads. The post-handoff personality is
the stock main firmware in calibration mode.

## Why the earlier updater conclusion was not unique

The prior `ram-push-live.md` identified the re-enumerated device as
the updater because it exposed 3 vendor interfaces and the
bootquery timed out, whereas the loader was believed to expose 6+
interfaces. Two corrections:

1. The live loader also exposes **3 interfaces** (0, 2, 5); the
   `6+` count belongs to a different mode. Interface count is not a
   discriminator.
2. The stock main firmware's calibration mode also exposes the
   same template and does not answer the loader bootquery. The
   timeout is not unique to the updater.

The only reliable discriminator is the **UI**: the updater does not
drive the display, so a responsive debug menu is the main firmware.
A pushed-payload marker in the UI (menu label, version string) is
the correct test, not descriptor shape or timing.

## Consequence for the project's "proven" claims

- **"RAM-pushed firmware works for Fujifilm's own updater" is not
  supported.** The observed service personality is consistent with
  the stock main firmware's calibration fallback. It must be
  re-tested with a UI marker in the pushed updater.
- **The `0x04f7` teardown drops USB, but the pushed payload does
  not execute** in these tests. The camera resets/falls back to the
  stock main firmware. The exact reset/fallback mechanism is not
  yet proven.
- **State-6 "completions" may be resets into calibration mode**,
  not payload boots. The 7/15 tally measured whether the receiver
  task survived long enough to drop USB, not whether the payload
  ran.
- **The direct-helper/trampoline "reached the helper" conclusion**
  rests on the same USB-drop/teardown signature and needs the same
  re-test.
- The SD file-boot and dual-boot roadmap assumed a working
  RAM-push; that premise is now open.

## What remains solid

- The loader's read/write/scratch/RAM-receiver behavior and its
  allowlists.
- The calibration/debug UI and its `BOOT TYPE`/`USB MODE` menu.
- The static boot-chain analysis, the DRAMPAR3 scripts, and the
  staged-loader storage contract.
- The camera's stock NAND firmware and recovery by power cycle.

## Next

1. Re-examine the `0x04f7` handler and the copy/jump path. Determine
   whether the payload is copied at all, whether the low helper
   `0x00000b34` returns/fails, and what resets the SoC.
2. Instrument the reset cause/boot-info block (`0x1000003c`,
   `0x10004054`, gate `0x10001300`) after a failed handoff; read it
   from a fresh loader entry. A reset flag/watchdog cause would
   confirm the fallback.
3. Re-test the updater with a **UI marker** (menu label or version
   string) rather than a descriptor/timing signature. If the marker
   appears, RAM execution is real; if not, the prior conclusion is
   retracted.
4. Re-test one direct-helper/trampoline call with a UI marker before
   relying on it for any boot path.

## Boundary

The live debug UI, the bootquery timeout, the 3-interface template
and the absence of a hang are observed. The conclusion that the
stock main firmware is running after these handoffs follows from
the updater's lack of display driving and the live UI. The exact
reset/fallback mechanism and whether any pushed payload ever
executes are not yet proven.
