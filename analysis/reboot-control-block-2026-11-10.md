# Reboot control block and the working `clbt reboot` command

Date: 2026-11-10. Live loader-shell result. The main firmware exposes
a clean software reboot through the factory command table:

```text
clbt reboot <mode> <time>
```

Observed from the APITEST shell (`cmd>`) in loader mode. No NAND or card
writes; the command powers off or reboots the running firmware.

## Control block

The reboot request is a BSS structure at `0x413d3988`:

| Offset | Meaning |
| --- | --- |
| `+0x00` | reboot flag (byte; set to 1) |
| `+0x08` | mode word (0..4) |
| `0x40e0e254` | countdown halfword (`time`, 0 = immediate) |

The mode setter is `0x40523dd0(mode, time)`; it prints the selected
message and stores `mode` at `+0x8` and `time` at `0x40e0e254`. The
plain `reboot` command handler (`0x40523cb0`) sets the flag but calls
the pre-reboot function `0x405e6fa0` first. In the shell context that
call hung the CPU (loader debug UI left on screen, USB bootquery dead);
the `clbt reboot` path is the safe one.

## Mode encoding

The mode executor (`0x40523e94`) writes an RTC/backup boot-mode register at
`0x40e4ae64` through the RTC helpers `0x40041c44` (read, with checksum)
and `0x40041be8` (write, with checksum). The final alarm/countdown is set
by `0x40041d14`, and `FUN_40041550(2)` triggers the power/reset.

| Mode | Register change | Boot target field |
| --- | --- | --- |
| 1 | set bit 9 (`0x200`) | power-off / no auto-reboot |
| 2 | clear bit 9; `bfi` bits 13–14 = 1 | View |
| 3 | clear bit 9; `bfi` bits 13–14 = 2 | Playback |
| 4 | clear bit 9; `orr #0x6000` → bits 13–14 = 3 | Clbt |

Modes 2/3/4 are distinct values in the same 2-bit field. The running
debug/loader firmware maps both 2 and 3 to the same green debug UI in
this card configuration, so the Playback/Clbt difference is not visible
here; a boot stage that honors the field would distinguish them.

## Why modes 3 and 4 both show the green UI

The test card contains `D:\EnableTest.txt`, the factory/test enable flag
the firmware opens at boot. That flag selects the debug/calibration
personality; the button combo is how loader mode is entered when the
card is set up. With the test flag present, modes 3 and 4 target the
debug/test firmware's Playback/Clbt slots, so both land in the same
green UI without the buttons. Mode 2 targets the retail View app, which
re-enumerates as `04cb:030a`; that is the visible difference.

The 3-vs-4 bitfield difference is real (field 2 vs 3), but the test-card
firmware does not expose a visible Playback-vs-Clbt distinction. A boot
without `EnableTest.txt` would be needed to see the retail mapping.

## Observed modes

| Command | Result |
| --- | --- |
| `clbt reboot 1 0` | clean power-off; green charge LED; power button boots normally |
| `clbt reboot 2 0` | full automatic reboot; shooting screen briefly, then normal USB PTP (`04cb:030a`) |
| `clbt reboot 3 0` | rebooted to the **debug/loader green UI** (`2aad:6500`); observably the same as mode 4 |
| `clbt reboot 4 0` | rebooted to the **debug/loader green UI** (`2aad:6500`); observably the same as mode 3 |

Mode 2 is the normal “reboot to View” path and is the safe way to leave
loader/debug mode without a battery pull. The executor has distinct
register bits for modes 3 and 4, but both produced the same observable
debug/loader green UI in this test; do not rely on 3 vs 4 to select a
different screen. The command range is 0–4:
the help string says `mode: 0~4`, the setter bounds-checks `mode <= 4`,
and the executor has the four defined cases. Mode 0 is the no-op /
“Out of control.. do nothing!!” path.

## Shell activation

The command was sent from the Linux/pyusb VM:

```sh
python3 xhalf_loader_linux.py --apitest-init
python3 xhalf_loader_linux.py --apitest-send "clbt reboot 2 0"
```

The `--apitest-init` state read still times out after the prompt is
installed; the `cmd>` prompt appears in the EP 0x84 drain and the
command is accepted. On macOS the same pyusb control-OUT (`0xFA`)
timed out even with `cmd>` visible, so the VM remains the reliable
shell path.

## Boundary

- This is a running-firmware reboot command, not an earlier-stage
  reset. It cannot recover the `usbt` RAM-upload hang, where the
  shell is not running.
- The command does not write NAND or the SD card. Mode 1 powers off;
  modes 2–4 reboot.
- Mode 4 is a factory/calibration mode; its stability and USB identity
  are not yet measured.
