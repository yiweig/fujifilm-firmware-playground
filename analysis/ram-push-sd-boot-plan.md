# RAM-push firmware and SD dual boot: plan

Date: 2026-11-05. Goal statement from the user: push for RAM-pushed
firmware and/or SD dual booting first, then a confirmed recovery path.
This supersedes the Level-2-first sequencing in
[native-raw-strategy.md](native-raw-strategy.md) (that lane is parked,
not cancelled). Static basis:
[xhalf-usb-ram-receiver.md](xhalf-usb-ram-receiver.md),
[ram-handoff-copy-boundary.md](ram-handoff-copy-boundary.md),
[boot-info-block-and-updater-sdboot.md](boot-info-block-and-updater-sdboot.md),
[updater-firmware-selection.md](updater-firmware-selection.md).

## Why this architecture is attractive

The vendor ISP protocol already implements RAM-push: bootquery with a
size allocates staging, `0x04f3` downloads, and `0x04f7` (upload/run)
hands off — a SRAM-resident stub copies the payload to `0x40000000`
in ≤1 MiB DMA chunks, calls the low helper `0xb34`, and enters the
payload via its `+0x200` entry. The stock card updater is the ideal
first payload: it is *designed* to be loaded exactly this way (the
vendor FRM host flow does it), it is hash-pinned
(`4bdcdb06…`), and once running it can load-and-boot an image file
from SD. Chain:

```text
stock FW1 (internal, untouched)
  -> calibration entry -> USB loader (proven)
  -> RAM-push stock updater (0x04f7)          [P2]
  -> updater boots main image FILE from SD    [P3]
  -> power cycle                              [stock again]
```

If this chain works, a *modified* main image can later be SD-booted
the same way (P4) with the internal stock firmware as the permanent
fallback: **removing the card / power-cycling is the recovery**. That
would satisfy the recovery requirement for everything except an
actual flash — which this architecture may make unnecessary.

## What is already proven vs missing

Proven: loader entry, DRAM read/write, code execution in loader
context, shell, storage reads. The `0x04f7` path itself has never
been sent. R2 (the `0xb34` helper's bytes) is **not** a blocker for
using the path — the device executes that ROM helper natively on
every boot; we need predictability of the handoff, not the helper's
source. Missing: the state-6 completion audit in *our* context
(optional preparation callback's identity/effects in a calibration
boot, the storage/header read's effects, payload constraints), an
emulator rehearsal of the updater's startup under our boot state,
and the updater's file-boot format/command surface.

## Phases

**P0 — Offline audit of the `0x04f7` completion path (no camera).**
- Read the live loader state object in our DRAM dump (`0x40fd0d50`):
  is the optional preparation callback installed in a calibration
  boot, and if so which function (backlight/lens/clock preparation
  family)?
- Trace state 6 (`0x401e1f40`) end-to-end: storage/header read
  (what it reads, the 16 KiB SRAM overwrite at `0x3f000000`),
  helper `0x401eb1ec` parameter block (`0x3f00d000`), stub address
  translation and chunking, entry conditions.
- Derive the exact host sequence from the receiver parser and the
  retained host-DLL traces (bootquery sizing/allocation semantics,
  `0x04f3` first-use parameter processing to `0x3f000200`, transfer
  chunking), and the payload contract (load address, size limits,
  `+0x200` entry behavior for the updater: stamps gate `0x88888888`).
- Audit the updater's selection callback under our storage state:
  it can request `:DEF_FW2` after the rawC loop — Firmware2 is
  empty (zero-length load is rejected), but the six rawC records on
  this device are unread; obtain/identify the RawCBoot block
  (storage dump) before P2 so there are no surprises.
- Deliverable: exact command script, success observables, abort
  criteria, and a written prediction of the updater's behavior.

**P1 — Emulator rehearsal (no camera).**
Simulate the updater from its reset entry with fixtures matching
our boot (DRAM parameters already in SRAM, gate stamped by its
`+0x200` entry, RawCBoot from P0, no `firmware.brn` on the virtual
card). Predict: reaches its USB/service continuation without any
storage write. Reuse the `InspectUpdater*.java` emulator family.

**P2 — Live RAM-push of the stock updater. DONE 2026-11-05**
([ram-push-live.md](ram-push-live.md)): updater booted from RAM
and reached its USB service personality; exit was OFF switch
and/or USB unplug (delayed response; battery pull prepared but
not needed); normal boot verified afterwards. Two host-side
client bugs (size-field order, stale allowlists) were
wedged/aborted safely along the way.

**P3 — SD dual boot of the stock main image (attempted 2026-11-05;
file boot did not take).** Emulator controls passed for all
mailbox branches ([output/dual-boot-p3-emulator.txt](output/dual-boot-p3-emulator.txt)),
the mailbox write was verified live, and the RAM-push completed on
retry — but the updater settled into service mode instead of
booting `D:\MAIN.BIN`. Failure point not isolated (drive mapping
vs file-format expectations vs mailbox persistence across the
handoff); the first attempt that day also hung >8 min in the
state-6 preparation wait and the retry did not, for reasons not
identified. The SD-file path is **parked** with the question open.

**Reopened 2026-11-05 (night)** — see
[sdboot-diagnosis.md](sdboot-diagnosis.md): the loader accepts a
raw main image (emulator PASS, no content validation on the
plain-file path), and the leading hypothesis is now that the
volatile register mailbox was cleared by the hang-recovery
power cycle before the successful retry push. Next attempt
sets the mailbox and pushes in the same boot.

**P3′ — Direct RAM-push of the stock main image (current).**
Skip the updater for the boot step: download `firmware-main.bin`
itself into the bootquery buffer and `0x04f7` it at `0x40000000`.
Only proven primitives are used; the payload is the platform's own
DRAM image, designed for exactly this load address and entry. The
updater/SD path remains the autonomy option for later (booting
without a host), but the host-assisted push is the direct vehicle
for a modified image. Verify normal boot; power cycle restores
internal stock.

**P4 — SD boot of a modified main image.**
First modification must be inert-but-observable (e.g. a version
string), with any in-image checksums the file loader verifies
accounted for offline. Only after P3 is boring.

**P5 — Recovery confirmation, stated precisely.**
- For RAM/SD-booted payloads: demonstrate hang/crash → power cycle
  → internal stock boots and shoots. That *is* a confirmed recovery
  path for the dual-boot architecture.
- For a hypothetical future flash: C1 (ROM cold entry) remains a
  separate, still-open question. The FRM/mask-boot USB mode gets
  one more offline look (entry conditions in the public FRM
  artifacts vs the button/storage state we can produce); if it
  cannot be established, flashing stays off the table permanently
  and dual boot is the final architecture.

## Safety gates

- P0/P1 are offline; P2 is the first irreversible-in-boot step and
  needs explicit go-ahead after the P0 prediction is written.
- No storage writes at any phase. No `firmware.brn` on test cards.
  No vendor programming commands to a RAM-pushed updater.
- Every live phase keeps the established pattern: hash-pinned
  payloads, emulator qualification before first live use, one
  change per session, normal-use sanity check afterwards.
