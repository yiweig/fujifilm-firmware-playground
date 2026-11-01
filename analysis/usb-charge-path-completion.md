# Post-refresh charging branch: completion and charger classification

Date: 2026-11-01. Offline static analysis and original ARM emulation only.
No camera commands, USB transactions, physical peripheral writes or card changes.
Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Runtime base `0x40000000`, little-endian ARMv7.

## Finding

The synchronous charging branch reached by a post-refresh USB event returns
to its caller without selecting the RAM loader or posting a UI event in the
executed instruction path. The staged cold-data RAM, cached USB flag and UI
state remain unchanged in the emulator. This closes the direct branch that the
[continuity experiment](usb-loader-sequence-continuity.md) previously stopped
before executing.

Power IC read/write, indicator behavior and delays are modeled. Their physical
effects, asynchronous callbacks, interrupts and later polling are not executed.
Consequently this is not proof that no later event can reach a loader. It does
show that the inspected synchronous branch is not the missing UI transition.

## Executed branch

For the synthetic inputs used by the earlier experiment (UI state 1, USB
presence set, status byte zero), event `0xf0000044` takes:

```text
0x404d54c4: delay, presence test and status predicate
  -> 0x40124eec(0)
     -> 0x40124b74(0): Charge Disable branch
        -> read Power IC register 6
        -> masked Power IC update: mask 8, value 0, fourth argument 32
     -> delay helper (3, 1)
     -> 0x40124e94(1): indicator request
  -> return
```

The Power IC calls are `0x40043220` and `0x40043318`. The latter performs
a read/modify/write/verification sequence through lower helpers at
`0x4006cdf4` and `0x4006d124`, with device selector `0x10`. This is not a
file or firmware programming API. The exact physical chip, bus address,
register documentation and reset/persistence semantics have not been identified
from these arguments alone. The delay's wall-clock units and fourth update
argument's full contract remain unassigned.

The `Charge Disable...` branch also clears runtime byte `0x40e7ae41`.
The indicator wrapper uses `ledId:%d` and reaches LED-control routines
`0x40159da0` / `0x40159c44`. The indicator API is simulated as one observed
request, not treated as fully audited hardware behavior.

No main-UI event-post call, USB mode setter, cold-data file reload/write,
RTC write or calibration call executes in this bounded modeled path. It
returns with UI state 1; it does not supply the connected state `0x8000` used
by the earlier positive loader-selection experiment.

## Power IC charger classification

Function `0x40124a38` reads register `0x0c` through `0x40043220` and extracts
bits `4..6`. Its diagnostic strings identify the result:

| Extracted value | Shipped diagnostic label | Stored status at `0x40e7ae40` |
| --- | --- | --- |
| 0 | `PowerIC_Charger_type_NONE` | 0 |
| 1 | `PowerIC_Charger_type_SDP` | 3 |
| 2 | `PowerIC_Charger_type_CDP` | 4 |
| Other values | `PowerIC_Charger_type_DCP` branch | 2 |

SDP/CDP/DCP are the firmware's own labels. In ordinary USB terminology they
refer to standard downstream, charging downstream and dedicated charging
ports. This does not identify which USB charging specification this camera
implements or establish its current measured charger classification.

The predicate at `0x40124b08` returns nonzero for stored values 1 and 2.
This table does not cover every writer or semantic meaning of the shared
status byte. A related detailed status reader at `0x401247f8` inspects several
other Power IC registers and runtime flags; its output must not be conflated
with this charger's three-bit classification.

Changing ports may affect charger classification as well as transient USB
addresses. No evidence here shows that changing ports overrides `FgUsbMode`
or activates the RAM loader. Existing device-identity checks still need a
fresh registry entry ID after reconnection.

## Emulator result and limits

The extended [`ReproduceTemporaryUsbSetting.java`](scripts/ReproduceTemporaryUsbSetting.java)
retains the previous stop-before-charge test and adds a completion test on the
same synthetic staged state. It supplies register-6 read value 8, admits only
the expected masked update `(6, 8, 0, 32)`, and records the indicator argument 1.
The original callback, charging switch, status getters and return execute.

Assertions require:

- exactly one modeled Power IC update and one indicator request;
- charging-enable RAM byte cleared;
- full staged cold-data buffer unchanged;
- USB selection flag zero and UI state 1 retained;
- return to caller without mode-4 selection.

All ten assertions across the expanded suite passed. Output:
[`output/temporary-usb-setting-charge.txt`](output/temporary-usb-setting-charge.txt).
Unexpected callees remain errors; no queue or mode setter is silently modeled
as success in this completion test. A modeled peripheral return is not a
real device observation, and a later interrupt/polling path is outside scope.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting-charge.txt
```

Original-byte `analysis/fw.py disasm` ranges (end exclusive):

```text
40124a38..40124ae4   Power IC charger-type classification and label pointers
40124b08..40124b2c   status predicate
40124b74..40124d88   charge control switch, hardware helper calls and strings
40124e94..40124f34   indicator and charging wrapper
40043220..40043264   Power IC read wrapper
40043318..400433f0   masked update/verification helper
40159c44..40159d10   indicator timing
40159da0..40159e40   indicator state
```

The existing local image/project and original instructions were reused after
the documented REA raw-image mapping issue. No fresh REA or live runtime
evidence is claimed.

## Research consequence

Follow-up: [idle-event refresh](idle-usb-refresh.md) removes the need to reach
`0x0301` for the refresh step. The missing transition into a loader-selecting
UI state remains relevant; the priority below records the earlier hypothesis.

Repeatedly delivering the same USB event after the conditional refresh is
not an established way to finish RAM-loader entry. The unresolved parts remain
an actual producer of UI state `0x0301`, any later UI transition that consumes
the refreshed flag, and assurance that staged shared settings cannot be saved
incidentally. Independent cold recovery remains a separate missing artifact.

The disassembly helper now rejects out-of-image or non-word-aligned ranges
before invoking the disassembler or printing literals. This followed an
oversized end-address typo during the offline inspection. Rejection of both
invalid cases and continued valid bounded disassembly were checked locally;
no firmware bytes were changed.
