# `cmdCdspRcaRaw` / `rcaraw` handler

Static analysis target: `analysis/output/firmware-main.elf` (ARM image mapped at
`0x40000000`). No handler or camera command was executed.

## Identification

The debug-command table registers the command name `rcaraw` with callback
`sub_4031a820`. The callback prints `cmdCdspRcaRaw::` and each argument before
parsing them.

## Observed behavior

- Argument 0 is parsed as an integer operation selector.
- Argument 1 is copied into a global destination/name buffer. A suffix separated
  by `:` can select an uppercase drive letter; otherwise the drive defaults to
  `D`. The exact user-facing syntax and downstream filename construction remain
  unresolved.
- If argument 2 is exactly `be`, arguments 3 and 4 are parsed as numeric bounds.
  When both are nonzero and the first exceeds the second, the handler enables a
  separate "multiple" configuration path.

Selector `19` (`0x13`) is the still-capture path. In its ordinary branch the
handler changes to/waits for mode `0x11`, delays 200 ms, and dispatches these
diagnostic-shell commands:

1. `snap attr raw=1`
2. `snap cap`
3. wait 2500 ms
4. `snap attr raw=0`

It then resets the temporary destination state and waits for mode `0x11` again.

Selector `17` (`0x11`) is the preview-RAW path. It performs the same mode setup,
then dispatches `pvraw 2 1 1`, waits 2500 ms, and cleans up.

The `be`/multiple branch for selector 19 does not dispatch `snap cap` in this
handler. It reads two capture-related dimensions and submits nine selector/value
configuration calls to a lower-level subsystem. Their semantic names have not
been recovered, so interpreting `be` as burst, bracketing, or exposure bounds is
only a hypothesis.

## Interpretation

`cmdCdspRcaRaw` is an orchestration wrapper, not the RAW encoder/writer. It
temporarily selects an output destination, puts the camera in the expected
capture mode, enables a RAW capture attribute, triggers a capture through the
existing diagnostic shell, then disables the attribute and resets state. File
creation and sensor-buffer serialization happen downstream.

This is strong evidence for an engineering sensor-dump facility, but not for
consumer RAF support. No RAF/DNG container writer was identified in the earlier
RAW-support pass. The likely output is a camera-internal `.RAW`/Bayer dump whose
dimensions, bit packing, black level, and CFA layout still need to be determined
from a sample.

## Why it matters

If the retail camera exposes the registered diagnostic console, this command may
offer a way to obtain a RAW sample without modifying or flashing firmware. The
next safe investigation is to identify the console transport and command syntax
statically, then test access using a recovery/RAM-loading setup before invoking
capture commands.
