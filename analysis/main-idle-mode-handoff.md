# Idle-mode preparation before optional RAM handoff

Date: 2026-11-03. Offline original ARM inspection and five local Ghidra controls
of the 2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. All addresses below belong to MAIN, not updater. No camera
access or firmware modification. No new REA or live observation is claimed.

## Finding

The [optional pre-handoff callback](main-handoff-optional-callback.md) ends in
idle wrapper `0x4015bf04`. Its non-idle path invokes sensor configuration,
requests an asynchronous mode change, and waits. The worker calls the current
mode's exit handler before the target entry handler. The default idle entry
can change DRAM clock configuration and invoke additional callbacks.

This identifies more dependencies before the copy/execute boundary. It does
**not** establish a persistent write on this path, nor demonstrate their
absence: the sensor setter, other modes' exit handlers, clock routines and
indirect callbacks have not been fully audited. The actual callback value,
current mode and clock state on the camera remain unknown.

Five bounded controls verify guards and stop boundaries only. They do not
execute the asynchronous transition or establish a safe RAM launch.

## Wrapper, mode mapping and asynchronous request

Mode-ID table `0x40b0c90a` contains seven bytes:

| Record index | Mode ID |
| --- | --- |
| 0 | `0x88` (idle, supported by wrapper diagnostics) |
| 1 | `0x41` |
| 2 | `0x42` |
| 3 | `0x11` |
| 4 | `0x13` |
| 5 | `0x21` |
| 6 | `0x90` |

Mode state is rooted at `0x40f80d28`: +0x18 holds the current record index,
+0x34 the separate completion/readiness flag, and +0x14 the allocated record
table pointer. Getter `0x40183744` translates index to mode ID; predicate
`0x4015be34` compares that ID with its argument.

Wrapper `0x4015bf04` returns immediately with 1 if the current ID is `0x88`.
That early branch does not inspect readiness. Otherwise it:

1. Calls sensor-configuration multiplexer `0x40184de4(0,0,0,100)`.
   Selector zero dispatches through `0x401ad30c` to `0x4010e11c(0,100)`.
   The actual source string is `sp5k_sensor_cfg.c`. The setter has RAM stores
   and a conditional deeper call; these controls stop before its execution.
   The precise sensor/hardware meaning of these arguments remains unresolved.
2. Calls `0x4018381c(0x88)`, which locks mode state, resolves target index 0,
   clears readiness, unlocks and passes a two-word message to `0x401833f8`.
   An additional helper `0x400591f8(1)` and diagnostic/error paths also occur;
   this request routine is inspected statically, not executed by these controls.
3. Calls waiter `0x40183768(0x88)` and logs completion if it returns.

The message sender uses queue handle state+0x1c and `0x4002a3c4` with timeout
argument `0xea60`. Initializer `0x40183004` constructs the queue with arguments
`(name,2,8)` and task entry `0x401834cc`. Literal strings identify `cmdModesw`
and `5kmodeswTask`. A submitted request is distinct from a finished transition.

## Worker dispatch and candidate exit handler

The initializer allocates seven records of 28 bytes. Each initially gets entry
`0x40182e9c`, exit `0x40182e8c`, and other default fields. It then calls
provider wrappers for indices 1 through 6; index 0 retains its defaults in this
initializer. These wrappers use registration pointers at `0x40fa58f4`.
Setter `0x401a7f14(index,pointer)` installs a provider for an index up to 6.
This is an initialization trace, not an exhaustive later-writer inventory.

In the worker, the inspected transition slice performs:

```text
401835ec  BLX current_record.exit(current_record, target_index)
40183600  BLX target_record.entry(target_record, old_current_index)
40183620  store target_index into mode_state.current_index
```

The exit pointer is record+4 and entry pointer record+0. Consequently the
current mode matters; default idle entry alone does not describe the transition.

A concrete registration candidate is wrapper `0x40232c2c`, which registers
provider `0x402dc030` for index 6 / mode `0x90`. That provider installs entry
`0x402dbee8` and exit `0x402dbdc8`. The latter is just three instructions:
return zero if target index is zero, otherwise return one. It makes no calls
or stores. **If this provider is installed and unchanged**, its exit toward
idle is simple. This is not proof that mode `0x90` is active at handoff, that
the provider ran, or that every other mode exit is equally simple. No semantic
name such as USB mode is assigned from the numeric ID alone.

## Default idle entry can change clocks

Default entry `0x40182e9c` reads the requested clock value from mode state+4
and another clock-state value from +0. It also calls `0x4000dde8`,
`0x400d3acc` and conditionally `0x400d3954(0)`. Those calls are not reduced
to exact hardware semantics in this pass.

When the requested value is nonzero and differs from both compared values,
it reaches a clock-switch sequence. This may invoke mode-state callback +8,
then calls `0x401a7ca8(requested,1)`, may invoke callback +0x0c, and stores the
requested value to mode state+0. Actual diagnostics include:

- `dramClk sw to %d fail \n`
- `clkSwDone,dram=%d,cpu=%d`
- In `0x401a7ca8`: `dramCSet:%u:%u`, `dramclk_set.c`,
  `#S#dclkSw#N#:%u:%u` and `#E#dclkSw#N#:%u:%u,%d`.

Thus a conditional DRAM-clock configuration operation is supported by both
call flow and strings. This is relevant before copying/executing code from
RAM. It does not show that the camera takes the branch, establish clock units
or values, or prove that the hardware transition succeeds. The lower routine
contains additional calls and waits; it and both optional callbacks remain
outside the controls and the completed side-effect audit.

## Readiness and return conditions

Waiter `0x40183768` repeatedly reads the current mode through locked getter
`0x40183710`. It returns only when the requested ID matches **and** state+0x34
is nonzero. Otherwise it calls delay `0x4000c450(10)` and increments a counter.
After 2,000 failed checks it reaches diagnostic/assertion helpers. If those
return, the code loops back. Delay units and assertion behavior are not
established here; this is not a guaranteed finite return or safe recovery timeout.
Locking, queue delivery and mode callbacks introduce additional dependencies.

The wrapper's already-idle branch bypasses this waiter. The controls show that
it returns with readiness either zero or one. This is a limit on what that
wrapper establishes, not a demonstrated live race or firmware bug.

## Five bounded original-instruction controls

[InspectMainIdleTransition.java](scripts/InspectMainIdleTransition.java) checks
the main-image hash and supplies mode index/readiness in synthetic RAM:

| Control | Result |
| --- | --- |
| Wrapper, idle ID, readiness 0 | Immediate return 1; no transition or waiter |
| Wrapper, idle ID, readiness 1 | Immediate return 1; no transition or waiter |
| Wrapper, mode `0x41`, readiness 0 | Stops at `0x4010e11c(0,100)` before sensor setter executes |
| Standalone waiter, idle ID, readiness 0 | Stops before first delay with argument 10 |
| Standalone waiter, idle ID, readiness 1 | Returns without a delay |

All five pass and all loaded-image bytes remain unchanged. Logging and lock /
unlock calls are modeled as returning. No sensor setter, queue, task worker,
clock switch, indirect callback, real delay, USB controller, NAND/SD, payload
or camera executes. Source binaries are untouched. The timeout and the entire
transition are not emulated.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectMainIdleTransition.java /private/tmp/main-idle-transition.txt
```

Evidence: [five controls](output/main-idle-transition.txt) and
[hash-qualified main-image ranges and literal strings](output/main-idle-transition-static.txt).
Literal pools within disassembly ranges are data, even when printed as instructions.

## Remaining investigation

The callback [lifetime trace](main-loader-callback-lifetime.md) remains relevant:
loader reset alone need not clear it. Next resolve provider installation and
exits for the other possible current modes, the sensor setter's deeper branch,
and the clock routine / registered clock callbacks. Persistent-storage and USB
resource side effects remain open across that full call graph. The backlight
and lens portions of the enclosing callback also remain only partially audited.
A RAM entry path and an independent cold recovery method are still unqualified.
