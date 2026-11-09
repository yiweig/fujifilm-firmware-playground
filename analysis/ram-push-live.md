# RAM-push live: stock updater booted from RAM via `0x04f7`

Date: 2026-11-05. Phase P2 of
[ram-push-sd-boot-plan.md](ram-push-sd-boot-plan.md), after the
[P0 audit](ram-push-p0-audit.md) and P1 emulator PASS
([output/ram-push-p1-emulator.txt](output/ram-push-p1-emulator.txt)).
Explicit user go-ahead obtained (and re-obtained after the first
wedge). Payload: stock card updater
(`analysis/output/card-updater-full.bin`, 666,112 bytes, SHA-256
`4bdcdb06…`), loaded at `0x40000000`. **No persistent change was
observed**: no write was deliberately issued, the camera still boots
the same stock firmware, and every experiment recovered by power
cycle. The updater's own storage initialization is not proven
write-free, however — see the conditional NAND erase branch in
[brick-risk-and-recovery.md](brick-risk-and-recovery.md).

> **Correction (2026-11-09, later session).** The "updater is
> running" identification below is **not unique**. The live loader
> also exposes 3 interfaces (0, 2, 5), and the stock main firmware's
> calibration mode has the same template and the same bootquery
> timeout. In a later run the hang-patched updater did not hang and
> the debug UI stayed **live** — which the updater, which does not
> drive the display, cannot produce. The post-handoff personality is
> therefore the **stock main firmware's calibration fallback**, and
> the pushed updater did not execute. The RAM-push claim is under
> re-examination:
> [ram-push-reassessment-2026-11-09.md](ram-push-reassessment-2026-11-09.md).

## Attempts

1. **Wedged at bootquery-alloc.** First `--ram-push` run timed out
   on the allocation bootquery and left the receiver's control path
   dead (device still enumerated; frozen debug screen; battery-pull
   recovery). Root cause, found by disassembly of the setup parse
   (`0x401e12c0`) and combine (`0x401e1558`): the receiver computes
   `size = wValue | wIndex<<16`; the client had sent the halves
   swapped, so the device tried to allocate/zero **724 MiB**.
   Client fixed (correct order, 1 MiB payload cap, reply-address
   validation). No payload had been downloaded; `0x04f7` never sent.
2. **Aborted by our own guard (twice).** Allocation succeeded but
   returned heap buffers at `0x45cc2cc0` / `0x45d65740` — the loader
   heap arena sits far above the previously observed allocations.
   Pattern write/read over the full span in a probe verified real
   DRAM before the guard range was widened to `0x40000000..0x60000000`
   (read allowlist widened to match). The full download completed
   on the second of these attempts; the run then stopped at the
   read-back allowlist, still before `0x04f7`.
3. **Success.** Fresh allocation, full download (21 × 32 KiB),
   read-back SHA-256 matched the payload file, `0x04f7` setup
   accepted (12 bytes).

## Handoff observations

- USB observer (host): stock loader dropped at **19:01:04Z**;
  a `2aad:6500` device re-arrived at **19:02:07Z** (~60 s — handoff,
  updater storage init and USB init).
- The re-enumerated device is **not** the stock loader: it exposes
  **3 interfaces** (vendor class; interrupt EP `0x87`, bulk EP
  `0x81` with alt settings) instead of the loader's 6+, and a
  zero-size bootquery to it times out instead of answering method
  byte 2. This matches the updater's vendor service personality
  (the FRM/ISP device layout), i.e. **the RAM-pushed updater is
  running**.
- The LCD kept showing the calibration debug list
  ("Ver:2.0006 MODE:1" — that screen's normal header): the updater
  does not drive the display in this state, so the main firmware's
  last framebuffer persists. Screen content is therefore not a
  personality indicator here; USB is.
- **Exit (corrected after the user's clarification):** the OFF
  switch did not respond immediately; as the user went to unplug
  the USB cable the camera shut off — *before* any battery pull.
  So the battery pull was prepared but **not needed**: the shutdown
  coincided with the cable unplug (or with a delayed switch
  response completing at that moment). Whether the updater polls
  the switch slowly or treats USB disconnect as a shutdown trigger
  is unresolved; either way, exit from the updater service mode is
  "OFF switch and/or unplug", with battery pull only as fallback.
  Afterwards: normal boot, camera fully normal (shoot/playback
  verified by the user).

## Conclusions

- **RAM-pushed firmware is proven on this device**, end to end:
  allocation, download, read-back verification, `0x04f7` handoff,
  environment replacement, and clean recovery by power-off with
  no observed persistent change. The updater's storage
  initialization is not proven write-free.
- The two client wedges were host-side bugs (field order, stale
  allowlists), each diagnosed from the receiver's own code and
  fixed with guards; neither reached a device-side write.
- Next is P3 (offline first): qualify the running updater's
  command/USB surface and its file-boot format so the stock main
  image can be booted from an SD file — the dual-boot proof. The
  battery-pull exit and the ~60 s service-mode arrival time are
  now known operational parameters for that work.
