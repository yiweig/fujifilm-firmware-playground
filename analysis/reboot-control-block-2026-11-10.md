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
