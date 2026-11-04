# Remaining UI loader branches: shared gate and emulator boundaries

2026-11-04. Offline original-ARM controls only. No camera requests, memory writes
to hardware, card markers, settings changes or firmware patches.

## Result

Five additional UI branches that call loader-mode selection all test the same
cached byte, `40e6696d` (`UI base 40e65950 + 101d`). Executing each branch's
original load/compare/conditional jump for **all 256 byte values** selects the
loader-preparation block only for zero. All **1,280** narrow checks passed;
the supplied UI RAM remains unchanged during these three-instruction controls.

This closes a specific gap in the earlier mode-setter caller census. Merely
choosing another inspected UI group does not supply a different gate at these
sites. It is not proof that no alternative path exists anywhere in firmware,
and does not establish a retail method to change that cached byte temporarily.

| Internal UI group | Verified handler entry | Gate slice entry | Loader-preparation target | Mode-4 call |
| --- | --- | --- | --- | --- |
| `a000` | `404d5d80` | `404d605c` | `404d61f4` | `404d6200` |
| `3000` | `404d7054` | `404d73f8` | `404d7544` | `404d7550` |
| `2000` | `404d7e7c` | `404d82c8` | `404d87f8` | `404d8804` |
| `1000` | `404d973c` | `404d9abc` | `404d9c70` | `404d9c7c` |
| `c000` | `404da78c` | `404da960` | `404daaa8` | `404daab4` |

Entries come from original branch destinations in group dispatcher `404db31c`,
not a push-prologue heuristic. Several start four bytes before their push;
omitting that first instruction produces invalid context. The three-instruction
controls deliberately supply the established UI base and field-offset registers;
they do not reproduce entry into those blocks.

The previously tested `8000` branch has the same flag condition. In supplied
`e000` state, the prefix test reaches mode 4 for either Boolean flag value, as
expected for the already identified service/calibration context. That context
has a separate unsafe-entry problem; it is not a newly discovered ordinary
retail bypass. See [service initialization writes](service-entry-initialization-writes.md).

## Why the larger emulator test stopped

An initial experiment incorrectly expected zero flag to lead directly to the
mode setter in every supplied UI group. The `1000` prefix first called
`40583b30`, outside that test's admitted code. The test stopped before that
callee and failed the overly broad expectation. This was a local test/model
boundary, not a camera refusal or platform approval/safety rejection.

That helper is a three-instruction RAM getter: it returns the byte at
`413dc9d8 + 40` (`413dca18`). Its original instructions were then admitted,
with explicitly zero-initialized synthetic global RAM. No helper success was
invented or hardware effect modeled for it.

The expanded inventory supplies event `f0000044` to the original group dispatcher
for seven group states, both cached-flag values, cached-connection values and
supplied cable values: **56 prefix observations**, not 56 whole-handler proofs.
Unknown callees are still boundaries, and no mode setter executes.

| Group | First boundary/result under these fixtures |
| --- | --- |
| `1000`, `c000` | Return for flag 1 with equal cached/supplied connection; otherwise stop before `40043264` |
| `2000` | Stop before delay helper `4006a074` |
| `3000`, `a000` | Stop before raw presence getter `40123d44` |
| `8000` | Flag 0 reaches mode-4 boundary; flag 1 returns or stops before ordinary USB work `4000f9d0` |
| `e000` | Mode-4 boundary for either Boolean flag |

Those earlier calls must not be treated as harmless merely to make a complete
simulation run. Their values and effects can matter to a physical route. The
narrow gate proof answers the current question without certifying those prefixes.
Logging, descriptor helper `405cbb84` and cached presence getter `40123bf8` are
modeled in the prefix inventory; raw presence and later peripheral work stop it.

## Evidence and reproduction

Official 2.00 main SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `40000000`. The earlier temporary Ghidra project was unavailable after the
session interruption. A new private project imported the existing generated ELF
with processor `ARM:LE:32:v7`, compiler spec `default`, no auto-analysis. ELF text
at file offset `100` was verified equal to the entire raw main. The script also
verifies the full image hash from mapped program memory before its controls.
This continues local Ghidra/p-code analysis; no fresh REA result is claimed.

- [Script](scripts/ReproduceUiLoaderRouteMatrix.java).
- [Control output](output/ui-loader-route-matrix.txt).
- [Gate instructions and bounded branch predecessors](output/ui-loader-gate-instructions.txt).
- [Artifact/import identity](output/ui-loader-route-evidence.json).

Once the temporary project exists:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ui-route-nov4 GHIDRA_PROJECT_NAME=UiRouteNov4 \
  bash analysis/run-ghidra.sh -process firmware-main.elf -noanalysis -readOnly \
  -postScript ReproduceUiLoaderRouteMatrix.java /private/tmp/ui-loader-route-final.txt
```

For a missing project, create a fresh private project directory and substitute
`-import analysis/output/firmware-main.elf -processor ARM:LE:32:v7 -cspec default`
for `-process ... -readOnly`. This edits only local analysis metadata. The generated
ELF must first be compared against the hash-qualified raw main as above.

## Consequence and next lead

The original R1 problem remains: a reachable, understood transition from retail
operation to connected loader mode without persistent factory-data changes.
The five branch controls do not close that problem. Continuing into unrelated
peripheral helpers would mostly expand runtime-effects knowledge, not supply
a new activation mechanism.

One concrete separate entry question remains in the earlier
[PTP/UVC service overview](ptp-and-service-command-paths.md): `4061b0bc` registers
factory-service callbacks through USB selectors `500/501`. Trace its actual
callers and configuration gates to determine whether those callbacks are reachable
outside calibration/service entry. Only then investigate whether a particular
request can reach loader selection. Existing UVC service strings do not establish
either connection, and ordinary PTP does not accept that protocol by implication.
No UVC request or USB-mode change is proposed by this report.
