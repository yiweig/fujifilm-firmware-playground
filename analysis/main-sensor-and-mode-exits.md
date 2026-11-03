# Sensor power-save timeout and candidate mode exits before handoff

Date: 2026-11-03. Offline original ARM inspection and 15 local Ghidra controls
of the 2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. Every address below belongs to MAIN, not updater. No camera
access or firmware modification; no new REA or live observation is claimed.

## Finding and scope

The [idle-mode audit](main-idle-mode-handoff.md) stopped before sensor setter
`0x4010e11c(0,100)`. That setter's actual diagnostic is
`[#sen%d]power save timeout %x`, supporting a sensor power-save timeout setting.
It writes 100 to a RAM word, but a conditional branch also requests sensor-mode
work and synchronization. The timeout's units and live state are unresolved.

All six previously identified non-idle provider candidates now have resolved
exit pointers. Toward idle, two call a shared cleanup routine, two can call
clock-related helpers, and two return directly. This narrows the remaining
side-effect audit; it does not prove which provider is installed or which mode
is active during a real handoff.

The 15 controls validate setter guards, two deeper stop boundaries, and each
candidate exit toward idle. They do not execute the unaudited cleanup, sensor
reconfiguration, clock or hardware calls. No new persistent write is identified
in this pass; persistent-storage and USB effects across those callees remain
unresolved. No RAM launch or independent recovery procedure is qualified.

## Sensor timeout store and conditional mode request

Original setter `0x4010e11c(sensor,value)`:

1. Stores `value` at `0x40d70844 + 0x4c + sensor*4`. For sensor zero this is
   `0x40d70890`, and the caller supplies decimal 100 (`0x64`).
2. Reads status byte at `0x40e77fc0 + 0x604 + sensor` and the descriptor pointer
   from table `0x40afbe88 + sensor*4`.
3. Only if status is 3 and descriptor byte +0xac is zero, calls
   `0x4010df60(sensor,0)`.
4. Logs the sensor number and supplied timeout.

The deeper call's second argument is **zero**, loaded from descriptor+0xac;
it is not the supplied timeout 100. The original instructions preserve the
sensor number in r0 across this branch. The timeout word is resident main-image
RAM data, not an identified flash/MMIO address. A RAM store alone does not
establish how later consumers might persist or use it.

Actual deeper diagnostics include `.sen modeN req %d %x`, `.sen modeN%x>%x`
and a repeated-power-saving message. These support sensor-mode request semantics,
but do not identify exact electrical/power behavior or a completed hardware change.

For this setter's zero-mode branch, `0x4010df60` first calls
`0x40113960(sensor,2)`. That helper uses an object handle at
`0x40e7923c + 0x20 + sensor*4`, checks execution-context helpers and calls
`0x4002a760` with arguments `(handle,2,0,output_pointer)` plus a stacked
`0xffffffff`. Its source literal is `flow/sensor_os.c`. This is a
synchronization dependency; its blocking/timeout behavior is not fully audited.

After that call, the inspected path models interrupt-mask helper
`0x4000c30c(0)` and stores requested mode zero at descriptor+0xad. With the
active-channel mask byte `0x40e77fc0 + 0x48c`:

- Supplied mask zero reaches `0x4010de08(sensor)` before executing it.
- Supplied mask one first reaches `0x40114504(0)` before executing it.

Static inspection of the continuation shows conditional channel calls,
`0x4010de08`, and flag operations through `0x401131f8` / `0x40113150`.
Those wrappers call `0x4002a790` with an object handle, a mask/complement and
operation selector. Their transitive effects are outside the completed audit.
Neither the zero-mode request nor the timeout store is evidence of a NAND write.

## Candidate provider-to-exit mapping

Registration wrappers in `[0x40232bb4,0x40232c3c)` supply provider pointers to
setter `0x401a7f14`. The provider bodies install these exit pointers at record+4:

| Index / mode ID | Provider candidate | Exit | Inspected behavior for target index 0 (idle) |
| --- | --- | --- | --- |
| 0 / `0x88` | Default initializer | `0x40182e8c` | Returns zero |
| 1 / `0x41` | `0x402dbd1c` | `0x402dbc80` | Calls shared cleanup `0x401a519c`, then returns zero if cleanup returns |
| 2 / `0x42` | `0x402dbc58` | `0x402dbbc0` | Same shared cleanup call |
| 3 / `0x11` | `0x402db4e4` | `0x402db8fc` | If mode-state+0x30 is nonzero, returns zero; if zero, reaches `0x400d3aec(1,4,0x20)` and then further work |
| 4 / `0x13` | `0x402dbb98` | `0x402dbad8` | Returns zero |
| 5 / `0x21` | `0x402dbd94` | `0x402dbd68` | Reaches `0x400d3aec(1,4,0x20)`, then `0x400d3954(0)` if it returns |
| 6 / `0x90` | `0x402dc030` | `0x402dbdc8` | Returns zero |

These numeric mode IDs are not assigned semantic names beyond already supported
idle. Provider registration execution, later overwrites and live table contents
are unobserved. Mapping does not prove normal startup always installs all of them.
The worker dispatch convention—r0 current record, r1 target index—comes from the
prior idle audit. Direct default/returning exits have no calls or stores in their
inspected bodies; that statement does not cover the worker or target idle entry.

For mode `0x11` with mode-state+0x30 zero and target index zero, the path skips
the special target-3/4 branches and reaches the clock-related helpers. It later
checks word `0x41318610 + 0x0c`, may call `0x400d3aec` again, and can clear
that word. Its deeper helper effects remain unresolved.

## Shared cleanup is still a material audit boundary

Routine `0x401a519c` calls `0x401d8648(1)`, can free RAM/resource objects,
and calls `0x401d46bc`, `0x401b61e8`, `0x401d6940`, `0x401d6cc4`,
`0x401d8438` and `0x401b6844`, before more RAM cleanup.

Its first helper waits on `0x401deb70`, delaying with `0x4000c450(10)` while
that getter returns zero, then invokes several getter/worker pairs. This shows
another return dependency, not a proven finite wait or a safe shutdown recipe.
The helper names are not guessed from addresses; exact subsystem identities
and transitive storage/USB effects remain to be established.

The exit controls stop at `0x401a519c` before its first instruction. The static
inspection of that body therefore must not be described as an emulated successful
cleanup. Likewise, the mode `0x11` / `0x21` controls stop before the first
clock-related helper. The conditional clock switch in the target idle entry
remains a separate dependency after any of these exits.

## Fifteen original-instruction controls

[InspectMainSensorAndModeExits.java](scripts/InspectMainSensorAndModeExits.java)
checks the main-image hash and uses supplied RAM state:

- Five sensor-setter controls: status/current-mode pairs `(2,0)`, `(2,0x10)`,
  `(3,0)`, `(3,0x10)` and `(4,0)`. Every case stores timeout 100. Only `(3,0)`
  reaches synchronization helper `0x40113960(0,2)`, stopping before it executes.
- Two deeper sensor controls for `(3,0)`, with active-channel mask zero/one.
  The initial synchronization and interrupt-mask helper are modeled as returning.
  Original instructions reach the corresponding `0x4010de08` / `0x40114504`
  boundary with r0 zero and requested descriptor byte zero.
- Eight exit controls: seven candidate indices, plus a second mode `0x11` case
  to exercise both values zero/one of mode-state+0x30. They return or stop at
  the first unaudited callee shown in the table above.

All 15 pass. Logging is modeled. Synthetic descriptor pointers/state are supplied
before comparison; among the loaded-image bytes, the timeout word is the sole
permitted execution change. Source files are untouched. No real synchronization,
interrupt change, sensor reconfiguration, cleanup callee, mode worker, clock
switch, USB controller, NAND/SD, payload or camera executes.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectMainSensorAndModeExits.java /private/tmp/main-sensor-mode-exits.txt
```

Evidence: [15 controls](output/main-sensor-mode-exits.txt) and
[hash-qualified ARM ranges and actual strings](output/main-sensor-mode-exits-static.txt).
The static evidence generator verified the disassembly ELF's embedded payload
matches the original main image. Literal pools remain data despite displayed
instruction mnemonics.

## Status and next useful boundary

Sensor-setting identity and candidate exit mapping are answered within these
static paths. Their guards/first-call boundaries have bounded execution evidence.
Actual provider installation, live state and complete sensor/cleanup/clock
side effects remain unresolved. The backlight and lens branches of the enclosing
callback are also still only partially audited.

The next focused audit is shared cleanup `0x401a519c`, especially its initial
synchronization and indirect work, plus the registered clock callbacks from the
idle entry. No camera reconnection is needed for that work. These results do not
change the recommendation to establish a verified RAM path and independent cold
recovery before modified flashing.
