# Calibration entry: automatic work and persistent effects

Date: 2026-10-31. Official firmware 2.00, main image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARM runtime addresses, image base `0x40000000`. Static instruction inspection
and bounded offline emulation only. No camera access or SD marker creation.

## Result

The `EnableTest.txt` override is understood, but service entry is **not yet a
safe live experiment**. It can schedule confirmation events that reach a
calibration worker without a person pressing SET. Calibration handlers include
writes to the camera's saved factory data. The actual camera's controlling
values are unknown, so this is a conditional hazard, not a prediction that
every retail X half will automatically calibrate on entry.

The principal risk established here is unwanted calibration/settings changes.
This finding does not demonstrate an automatic firmware flash or prove that
service entry itself bricks the camera.

## Which startup branches schedule work?

`EnterCalibrationMode` at `0x405dd4f0` selects USB mode before its later
automatic-work decision. The ordinary non-custom path at `0x405dd964` calls
`0x4051d918`, whose exact test is:

```c
(cold_data_halfword_at_0x6c & 0xf) == 8   // FgAutoClbt
```

When true it queues `0xf0000007`. If the selected item is 20 (`AF LAMP`),
or runtime table halfword `+0xabc` is nonzero, it additionally queues
`0xf0000005` then another `0xf0000007`.

The table halfword is populated by `InitClbtData` at `0x405dab58..6c` from
saved cold-data halfword `+0x168`. This is distinct from `FgAutoClbt` itself.
The item's display label alone is not the explanation: the event handlers
below establish that these events can execute work.

Two other branches were checked:

- Exact boot words `0x204`, `0x404`, `0x804`, `0x1004` take the custom
  calibration branch, with additional conditions. These differ from the
  button candidate's emulated words `0x00300003` and `0x00300006`.
- A preceding call at `0x405dd7c4` could otherwise select item 9 and schedule
  work, but its callee `0x40045968` is literally `mov r0,#0; bx lr` in this
  image. That branch is inactive in the inspected build.

Changing the second saved halfword alone would not universally prevent work:
item 20 has the explicit exception. No independent SD-file switch to suppress
this scheduling was identified in the inspected decision path.

## What the queued events do

The UI substate halfword is `0x40e66958` (`0x40e65950 + 0x1008`).
`0x404db31c` dispatches states in the `0xe000` family to `0x404d4f9c`.
That function routes `0xe000` to `0x405daec8` and `0xe300` to `0x405db640`.
The selected item and confirmation choice are at `0x413e3d98 +4` and `+8`.

For an available nonzero calibration item, initially in state `0xe000`:

```text
0xf0000007 -> open selected item, clear choice/completion fields, set state e300
0xf0000005 -> toggle confirmation choice from 0 to 1
0xf0000007 -> execute selected item if prerequisites permit
```

The first transition is `0x405db078..118`. The choice toggle is
`0x405db6b4..704`. Execution checks choice and prerequisites at
`0x405db7f4..898`, then dispatches through the item switch at `0x405db8ac`.
Thus the automatically queued sequence can supply the confirmation itself.

There are real additional constraints: table `+0x2e` can prevent opening a
completed item; state `+0x0a` prevents duplicate execution; `0x405da76c`
checks failed prerequisites through each row's mask at `+0x28`.

Initialization `0x405daa2c` resets selection, reloads cold data and can choose
an item through `0x405da930`. That routine sorts candidates using their saved
high-byte priorities and selects one with flag bit 2 set. Another saved-flag
branch can choose item 7. The particular retail selection cannot be inferred
without the live cold data.

## Concrete persistent operations

| Operation | Evidence | Meaning / boundary |
| --- | --- | --- |
| Startup folder creation | Entry calls `0x405dadb8` at `0x405dd5a0`; calls `0x404e81d4` for `\\DCIM` and formatted DCF folder, then `0x40535430` -> `0x40320338` -> `0x40010d90` (shipped diagnostic name `vfsMkdir`) | Conditional on storage status; selected drive is D: or C:. Not a factory-calibration write, but entry cannot be called wholly read-only. |
| Calibration result flags | `0x405da664` reads cold data, changes the selected table row's pointed-to halfword, then tail-calls `0x40125e4c` at `0x405da6e8` for 1,024 bytes of `A:\RO_RES\COLDDATA.BIN` | Persistent write shared by calibration/menu handlers. |
| WB/AGC calibration example, item 3 | Switch calls `0x405d84f8(0)` at `0x405db98c`; that worker can call `0x405da664(6,4)` at `0x405d8708`, `0x405f3ce4` at `0x405d874c`, and `0x405f403c(3,1,result)` at `0x405d8784` | Conditional worker path reaches saved-data updates. The latter helper writes result flags through `0x405da664`; `0x405f3ce4` clears cold-data halfword `+0xae` and writes the buffer back. |
| BOOT TYPE menu, item 13 | `0x405f3984` changes a RAM mode flag, calls `0x40127bfc`, then `0x405da664(13,value)` at `0x405f3a2c` | The displayed “Enter Normal Mode” option is associated with a persistent boot-type change, not established as a temporary exit. |
| AUTO CLBT menu, item 15 | `0x405f3b48` -> `0x405da664(15,value)` at `0x405f3bc8`; table row 15 points to `0x40e4df6e` | Turning automatic calibration off through this menu changes saved `FgAutoClbt`, and is available only after entry. |

These examples are not a full inventory of all workers or all transitive
entry calls. The BOOT TYPE result does not rule out a separate safe power-off
or reboot path; that lifecycle has not been verified.

## Offline instruction-level check

[`scripts/ReproduceCalibrationEvents.java`](scripts/ReproduceCalibrationEvents.java)
verifies the image hash, executes the original ordinary scheduler slice
`0x405dd964..0x405dd9ec`, captures queued events, and feeds them sequentially
through the original dispatcher and confirmation handlers. It supplies
synthetic FOCUS item selection and saved settings, allows its prerequisite
checks to execute, and stops before the FOCUS worker's first instruction at
`0x405d5b34`.

Unlike the earlier input-sampling experiment, this check **stubs calls**.
The source and output list every stub address: display/logging, delay,
capture-setting calls, event posting and file reads. The read stub preserves
synthetic cold data. It does not execute real storage, simulate the event
queue implementation, validate worker behavior, or run all of startup.

All eight cases passed:

| Synthetic FgAutoClbt | Synthetic table `+0xabc` | Queued events | FOCUS worker reached |
| --- | --- | --- | --- |
| `0`, `4` | either 0 or 1 | none | no |
| `8`, `0x18` | 0 | `7` | no; confirmation screen |
| `8`, `0x18` | 1 | `7`, `5`, `7` | yes; stopped before worker execution |

Event values in this table omit the common `0xf0000000` prefix.
Actual item selection and saved values remain unknown.

Reproduce in the existing ARMv7 project:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceCalibrationEvents.java analysis/output/calibration-events.txt
```

The companion `DecompileTargets.java` exports explicitly supplied entries
without resetting all function definitions. Verify entry addresses from call
targets first: several routines begin with a comparison or load before PUSH.
Pseudocode was corroborated with original ARM instructions for conclusions
above. Generated outputs and Ghidra databases remain ignored.

## Remaining decision

The safe next research target is a read-only way to establish the camera's
`FgAutoClbt`, `+0x168`, and calibration-item flags **before** entering this
mode, or a verified way to suppress automatic work before it is queued.
No such retail-accessible route has been established yet. Merely arranging
to send a USB command quickly after enumeration would introduce a race and
does not resolve this safety question.

Follow-up (2026-11-01): [ordinary PTP factory-data reading](ptp-cold-data-read-assessment.md)
is now a stronger candidate. Its read callback has been reproduced offline
without calibration entry, but live reachability, lifecycle and read freshness
remain unverified.

Removing the marker can prevent a later marker-dependent entry; it cannot
undo calibration data already written. Safe shutdown/normal restart and the
remaining entry callees still require investigation. This service loader
also remains application code, not demonstrated recovery from a broken main
firmware image.
