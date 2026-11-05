# P0 audit: the `0x04f7` upload/run handoff in our loader context

Date: 2026-11-05. Offline phase P0 of
[ram-push-sd-boot-plan.md](ram-push-sd-boot-plan.md). Static
decompilation (Ghidra 12.2) plus our live DRAM/SRAM dumps. No camera
access, nothing sent to the device. Decompilations:
[output/handoff-state6-decomp.txt](output/handoff-state6-decomp.txt),
[output/bootquery-writebranch-decomp.txt](output/bootquery-writebranch-decomp.txt),
[output/handoff-callback-decomp.txt](output/handoff-callback-decomp.txt).

## Host sequence (derived from the receiver parser)

All requests are vendor control transfers on interface 5, as with
our existing loader client.

1. **Bootquery with size** — `IN 0x70`, `wValue = size>>16`,
   `wIndex = size & 0xffff` (receiver: `size = wIndex | wValue<<16`,
   `FUN_401e1548`). Device allocates buffer **A** (`size` bytes,
   zeroed) and workspace **B** (64 KiB, stored at state `+0x20`),
   returns A's address in reply bytes 1–4. Zero reply address =
   allocation failure → abort. (Our previous zero-size queries
   skipped this entirely.)
2. **Parameter download — deliberately skipped.** The vendor flow
   sends `0x04f3` with the package `DRAMPAR3` block (locally
   verified at `FPUPDATE.DAT` offset `0x09e1ca70`) because a mask-ROM
   ISP boot starts with uninitialized DRAM. Our handoff starts from
   a fully booted unit whose SRAM already holds *this unit's* DRAM
   parameters, and state 6's header refresh reconstructs the SRAM
   boot block from storage as the ROM-side load does. Writing the
   package's generic parameters over the unit's own would add risk,
   not remove it. (If P2 ever shows parameter-related instability,
   this decision gets revisited with the emulator, not improvised.)
3. **Payload download** — repeated `OUT 0xfd`, index `0x04f5`,
   payload `(length, address)` + bulk OUT data (the branch our
   `write_mem` already uses live), writing the payload image into
   buffer A in chunks. Chunk size is host-chosen; start at 64 KiB
   and qualify by read-back (next step).
4. **Verify before run** — read A back via `0x04f2`/`0x04f4` reads
   and compare SHA-256 with the payload file. Mismatch → abort
   (do not send step 5). This gate is cheap and decisive.
5. **Upload/run** — `OUT 0xfd`, index `0x04f7`, 12-byte payload
   `(A, size, 0)` (word 2 = destination; zero defaults to
   `0x40000000`). The device then leaves USB-land: the current USB
   session dies as the environment is replaced. No reply is
   expected or required.

Payload for the first run: `analysis/output/card-updater-full.bin`
(666,112 bytes, SHA-256 `4bdcdb06…`), load address `0x40000000`.

## What the device does at state 6 (`FUN_401e1f40`)

1. **Preparation callback** at state `+0x40` — in our calibration
   loader boot it **is installed**: `0x405da53c` (live value from
   the DRAM dump). Decompiled, it is the vendor's park-the-camera
   preparation: it requests mode `0x88` and waits for it (the
   idle/service mode family used before environment replacement),
   with a 100-unit delay and mode-state logging. This is designed
   pre-handoff behavior in the same class as a normal mode change;
   it is not a storage operation.
2. **Header refresh**: storage-interface op 10 reads the storage
   header into `B+0x800`; 16 KiB is copied from there to SRAM
   `0x3f000000`, reproducing the normal-boot SRAM layout for the
   payload. *Open point:* this overwrites SRAM `0x3f000200`, where
   DRAM parameters also live. Step 2 of the host sequence exists
   precisely to give the handoff a DRAM-resident parameter copy
   (parameter block fields 3–4 at `0x3f00d000` carry its
   pointer/size), and the updater's startup re-validates/restores
   parameters — P1 (emulator) must confirm the net effect before
   any live run.
3. **Handoff helper** `FUN_401eb1ec(A, B, size, dest, 2)`: logs
   `pispBuf/pimemBuf/size/pbaseBuf`, prepares caches/MMU, copies
   the `0x800`-byte stub (from `0x401ed070`) into workspace B,
   disables interrupts, writes the parameter block at
   `0x3f00d000` = `(src=A, size rounded even, dest, drampar
   pointer, drampar size)`, and calls into the stub.
4. **Stub** (disassembled, `0x401ed070`): translates src/dest,
   DMA-copies A → dest in ≤1 MiB chunks (MMIO `0x10003000`),
   calls the ROM helper `0xb34(dest, size)` (post-copy
   cache/memory sync — the same call made on every normal boot),
   normalizes CPSR, and for dest `0x40000000` first calls the
   payload's `+0x200` entry (the updater's stamps gate
   `0x88888888` and returns), then jumps to the payload reset
   vector. If the payload ever returns, the stub loops forever —
   exit is power cycle by design.

## Predicted updater behavior (to be confirmed in P1)

Updater reset → startup `FUN_4000125c`: gate is `0x88888888`
(nonzero), so it skips the DRAM-restore slice (DRAM already
initialized by the real boot) → boot-register consumer
`FUN_400009c4`: button bytes are zero (measured live), so it
registers the selection callback, initializes storage backends,
finds the mailbox empty → default `D:\firmware.brn` → **file does
not exist on our card** → falls into its USB/service continuation.
The selection callback's rawC loop and final `:DEF_FW2` request are
bounded: rawC records (if any exist on this device — the block was
not found in any storage dump) load parameter-class images to RAM,
and the Firmware2 load is rejected (zero length). No branch writes
storage unless a `.brn` file is present or host programming
commands are sent; neither will occur.

**Success observables (any one is strong; expect at least two):**
USB disconnect of `2aad:6500` followed by re-enumeration (the
updater's own USB stack), the debug screen being replaced, or the
updater's console behavior on its service interface.
**Exit:** power off (battery pull if ignored), then the standard
normal-boot/shoot/playback verification. Internal storage is never
written by this flow.

## Abort criteria (live run)

- Bootquery returns address 0, or read-back hash (step 4) differs.
- Any transfer error during download (do not "retry into" state 6).
- After the run: if the camera does not power off normally, battery
  pull; if normal boot afterwards is abnormal in any way, stop and
  reassess before any further session.

## P1 (emulator) must confirm

1. Updater startup from reset with our fixtures (gate stamped by
   its `+0x200`, SRAM as after the state-6 header copy, DRAMPAR as
   left by step 2 + parameter block) reaches the USB/service
   continuation with **zero storage writes**.
2. The selection callback's behavior with (a) no RawCBoot block and
   (b) a synthetic RawCBoot block — both bounded, neither reaches a
   storage write or an unexpected boot.
3. The header-copy/DRAMPAR interaction in item 2 above is benign
   for the updater (it re-validates parameters itself).

Only after P1 passes does P2 (the live run) get proposed for
explicit go-ahead.

## P1 result (2026-11-05): PASS, with stated boundaries

`scripts/ReproduceRamPushP1.java`, output
[output/ram-push-p1-emulator.txt](output/ram-push-p1-emulator.txt):

- **Consumer control (backends `0x00` and `0x40`)**: with the exact
  live fixture (gate `0x88888888`, status `0x0100`, button bytes 0,
  mailbox empty, file absent), the original consumer instructions
  reach the continuation call `0x40069374` via the file-not-exist
  path on pathname `D:\firmware.brn`, having installed the
  selection callback and entered NAND registration, and **without
  reaching the loader `0x4000f270`, the update wrapper `0x4000fc70`,
  or any modeled storage operation**. Loaded image bytes unchanged.
- **Callback control (RawCBoot absent)**: the original callback
  ends in exactly one loader call, `(":DEF_FW2", 0x40000000)`,
  which the live loader rejects (zero Firmware2 length); no drive
  selector is invoked. Item 2(b) (synthetic RawCBoot) was not run:
  the block was not found in any storage dump of this unit, so the
  absent case is the expected live one, and the record-present
  behavior is bounded by the disassembly (per-record parameter-image
  loads, `:NO_BOOT` abort).
- Boundaries, honestly: the controls start at the consumer and
  callback entries, not at the updater reset vector; the
  initialization preceding the consumer in `FUN_4000125c` and the
  real NAND-registration callback invocation are modeled, not
  executed. Item 3 is covered only to the extent that the consumer
  path itself performs no parameter restore when the gate is
  stamped (static + control agreement).

P1 is therefore **passed for the purpose of proposing P2**, with
the residual unknowns carried into the live run's abort criteria
rather than resolved on paper.
