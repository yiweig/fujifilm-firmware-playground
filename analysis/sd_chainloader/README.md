# SD chainloader: boot a RAM-staged image through `sdboot`

Date: 2026-11-10. Offline build; live run pending.

## Design

The shell's `sdboot <addr>` reads one sector from SD reserved slot 0
and jumps. It is a one-sector chainloader by design, not a full-image
loader. The stage below uses it as an entry point only:

```text
host USB 0x04f5  ->  stage full image at 0x50000000 (high DRAM)
sdfww STAGE.BIN  ->  eSD header + 512-byte stage in SD slot 0
sdboot 0x41070000 -> read slot sector to 0x41070000, jump
stage            -> copy 0x50000000..+0xe38400 -> 0x40000000,
                    clean/invalidate caches, jump to reset vector
```

Why `0x41070000` and not `0x40000000`:

- `sdboot 0x40000000` would overwrite the running firmware's exception
  vectors before the stage even starts.
- `0x41070000` is inside the largest zero DRAM gap in the loader-mode
  dump (`0x4106a198..0x412c26d8`); the trampoline experiments already
  executed code in this region.
- Running the stage outside the destination also lets the copy loop
  survive until the final jump.

This path avoids both blockers of the vendor receiver:

- no `0x04f3` first-use state (storage read + SRAM `0x3f000200` copy);
- no state-6 park/handoff (`0x405da53c`), no `0xb34` call.

## Files

| File | Role |
| --- | --- |
| `stage.s` | ARM source for the 512-byte stage |
| `stage_build.py` | assemble + extract + pad to 512 bytes |
| `stage.bin` | built stage (128 bytes code, zero padded) |
| `stage_upload.py` | standalone 0x04f5 upload + read-back verify |
| `shell_sd_stage.py` | `sdfww`/`sdhdr`/`sdboot` driver |
| `run_chainloader.py` | end-to-end: card prep, upload, verify, `sdboot` |
| `firmware-main-ramtest.bin` | optional PTP-marker image (not needed; use `--marker`) |

## Prerequisites

- Camera in loader mode, shell at `cmd>`.
- SD card contains `STAGE.BIN` (the 512-byte `stage.bin`).
- Camera attached to the Linux VM (`2aad:6500`).

## Runbook

1. **Probe the staging address (read-only):**

   ```sh
   cd /home/xhalf
   python3 run_chainloader.py --probe-only --src 0x50000000
   ```

   A mapped region returns data (usually zeros). If it times out, try
   `0x48000000` / `0x58000000` and rebuild the stage with
   `stage_build.py --src <addr>`.

2. **Prepare the card** (writes the card's start, destroys its FAT):

   ```sh
   python3 run_chainloader.py --stop-after-sdhdr
   ```

   Inspect the `sdfww`/`sdhdr` log. `sdfww` must return to `cmd>`
   without an SD read error. If it fails at CMD18, stop; the card is
   partially overwritten and must be restored.

3. **Stage + verify + boot:**

   ```sh
   python3 run_chainloader.py --skip-sdfww --marker
   ```

   `--marker` patches the PTP model string `X-HF1` -> `X-ES1` in the
   staged image. If the RAM image boots and the PTP stack enumerates,
   the model string is the unambiguous marker.

4. **Observe.** Success is any of:
   - the loader `2aad:6500` drops and the camera re-enumerates as PTP
     `04cb:030a` with model `X-ES1`;
   - the debug UI is replaced / the camera returns to a normal screen;
   - USB activity consistent with a fresh firmware start.

   Failure is the usual hang; power cycle. The NAND is untouched.

## Risks and limits

- **Other cores.** The stage disables interrupts on the current core
  only. If secondary cores are executing the old image while it is
  overwritten, they can fault. At the shell prompt they are expected
  to be idle, but this is the main remaining risk. No quiesce is
  performed.
- **Staging region ownership.** `0x50000000` is above every observed
  allocation, but the true DRAM use map is incomplete. A framebuffer
  or DMA buffer overlap would show as a display glitch or a hang.
- **`sdfww` small-file path.** The earlier 14.9 MB `sdfww` failed at
  CMD18. A 512-byte file should use a single-sector read; this is not
  yet proven live.
- **Image scope.** This boots the *stock* main image (optionally with
  the PTP marker). A modified image of the same size can be used, but
  the reset path may still reload sections from NAND; that is a later
  question.
