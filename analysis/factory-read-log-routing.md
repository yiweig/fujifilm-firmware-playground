# Factory-read logs: separate errors and profiling notices

Date: 2026-11-01. Offline inspection and original ARM emulation only. No
camera connection, USB request, log activation, SD marker or firmware change.

## Result

No read-only retail USB route for obtaining the file-read errors was
established. Errors use ordinary character output, whose default setup is
UART. Start/end notices use a separate RAM profiling channel.

The notices do not establish success: the original wrapper emits the same
start/end calls on successful and failed reads. Errors go elsewhere. Even
a successful logical-file read can copy from the resident archive without
reading storage. Reliable log collection and correlation with a complete
physical archive read would both be required to establish freshness.

This investigates the specific lead proposed after the
[normal download audit](retail-ptp-file-acquisition.md), not every possible
vendor diagnostic or logging transport.

## Evidence identity

Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses use ARMv7 little-endian runtime base `0x40000000`. The existing
local Ghidra project was reused after the documented REA raw-image mapping
limitation; no new REA runtime observation is claimed.

## Read-wrapper log calls

| Event | Format address / text | Called path |
| --- | --- | --- |
| Start | `0x40b06ddd`, `***fast boot DataLoad S %s` | `0x400410cc(0, format, path)` |
| End, including failure | `0x40b06df8`, `******fast boot DataLoad E %s` | Same profiling path |
| Open error | `0x40b06d16`, `ui open err (%s)` | Print `0x40004064` |
| File-size error | `0x40b06cf8`, `ui size exceed (%s) %d,%d,%d` | Same print path |
| Short read | `0x40b06ce6`, `ui read err (%s)` | Same print path |

The [failure controls](factory-read-failure-controls.md) now assert these
formats and the profiling channel argument. Across two reads per case, all
five cases emit start/end/start/end. The three modeled failures add their
respective error-format calls, while the factory callback still returns
success. Actual formatting, delivery and retention are not executed in these
controls. [Retained output](output/ptp-read-log-calls.txt).

## Character output

`0x40004064` passes the format/varargs to `0x40020050` with output target
`0xffffffff`. Character helper `0x4001fcac` sends that target to
`0x4000718c`, which has an optional mirror at `0x40e48c04` and an override
at `0x40e48c08`. Otherwise `0x40007164` uses the default at `0x40e48c00`.

Channel-zero UART initialization installs `0x4000e284` as that default via
`0x40007228`, at `0x4000e424`. It reaches UART transmission through
`0x4000e1b4` when configured, with another optional override. This corroborates
the [UART shell analysis](shell-access-and-usb-lifecycle.md); the user's
no-disassembly constraint excludes a UART-pad experiment.

The inspected print call does not also enqueue errors in profiling channel
6. Optional mirrors mean this is not proof that errors are never retained
elsewhere. Aligned ARM B/BL scans over the first `0xab0000` bytes and
whole-image exact-pointer scans found no direct call or entry pointer to
mirror setter `0x40007210`. Override setter `0x400071e4` has numerous callers
and a stored pointer, but these do not establish an active retail USB mirror.
Computed calls, inline assignments and other modules remain outside that census.

## RAM profiling notices

`0x400410cc` formats at most 79 bytes into an 80-byte buffer, then calls
`0x4000459c` with channel `argument + 6`. The reader supplies 0, selecting
channel 6. Metadata are at `0x40d5dcc4 + 20 * channel`.

The packaged channel-6 entry is zero. Early startup calls initializer
`0x4001cd00` at `0x400035e8`; that initializer requests a 32,768-byte
channel-6 buffer through `0x400410b4` at `0x4001cd14`. Configurator
`0x4000442c` selects ring behavior. These are static startup facts, not
observed live allocation or retention. A ring can overwrite older records.

Four controls of original enqueue function `0x4000459c` passed:

| Supplied state | Result |
| --- | --- |
| Unconfigured channel | Returns 1; no payload copied |
| Ring with space | Returns 0; correct timestamp/header/text stored |
| Full non-overwrite buffer | Returns 1; no payload copied |
| Busy channel | Returns 1; no payload copied |

The non-overwrite case is an alternative configuration, not the claimed
startup ring setting. Locks, timestamp and memcpy are modeled; complete
buffer contents are checked. Wraparound, actual concurrency and camera state
are not reproduced. [Script](scripts/ReproduceProfileLog.java),
[retained output](output/profile-log.txt).

## Dumping is not passive observation

Wrapper `0x40041124` adds 6 and calls dump function `0x40004898`. One
found caller is the diagnostic branch at `0x40673a3c`, printing the shipped
name `sp5kProfLogDump`. Its existence does not supply a retail USB command.

Dump output uses `0x40004bf4`, which normally emits characters through
`0x4000718c`; optional callbacks redirect strings. Setters `0x40004c90` and
`0x40004cb4` affect output destinations, not USB registration by themselves.

The dump also sets a busy flag and, in non-ring mode, clears read/write
positions after completion. A pending flag can trigger cleanup `0x400047e4`.
It is not a guaranteed non-destructive snapshot. No dump was triggered on
the camera.

The [measured USB interface](xhalf-usb-identity.md) is PTP with three
endpoints, not an observed serial console. Earlier registration and control
routing audits supply no qualified retail log-reader entry point. These
bounded findings do not exclude every embedded vendor route.

## Reproduction and remaining gap

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproducePtpColdRead.java /private/tmp/ptp-read-log-calls.txt
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceProfileLog.java /private/tmp/profile-log.txt
```

Seven existing callback/failure controls pass with added log-call assertions,
and four new enqueue controls pass. Host-reader code and command allowlist
are unchanged. No live log backend was added.

Further acquisition work needs an actually reachable read-only interface
to persistent storage or a bounded RAM snapshot with established provenance.
Generic end-of-read logs cannot substitute for it. Independent recovery and
verified restore remain unestablished.
