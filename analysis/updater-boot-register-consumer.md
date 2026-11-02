# Packaged updater consumes boot registers and chooses a file action

Date: 2026-11-02. Offline inspection and original-ARM controls of the complete
official 2.00 updater. SHA-256:
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
Addresses in this note belong to **card-updater-full.bin**, base `0x40000000`,
not the main camera firmware. No camera access or card preparation occurred.

## Finding

Follow-up: [updater firmware selection](updater-firmware-selection.md) traces a
different conditional startup branch that registers a storage callback. That
callback can request `:DEF_FW2` after examining six rawC configurations. This
is fallback logic inside an executing updater, not the missing cold-loader
selector or an independent recovery procedure.

The packaged updater has an automatic startup consumer of boot-register data:
it can construct a pathname and choose between ISP/update setup and a separate
file/rawC boot helper. This is more concrete than a filename string or shell
help message. However, the consumer runs **after the updater is already loaded
and executing**. It does not identify who loads the updater, who populates
these registers, or which physical condition could reach it on the X half.

The `Boot from bootinfo%d` diagnostic is a separate shell-command observation
of a status-register field. It is not a recovered automatic Firmware1/Firmware2
fallback selector. Six rawC boot-configuration entries also must not be equated
with the two firmware slots merely because both concern booting.

Eight supplied-state controls pass, stopping before ISP task/setup, boot loading
or shutdown. **There is still no independent cold-recovery procedure.**

## Startup consumer and register inputs

Updater initialization `0x4000125c` calls consumer `0x400009c4` at
`0x40001390`, after other setup. The consumer's relevant inputs include:

| Address | Observed use |
| --- | --- |
| `0x10001300`, word | Zero skips automatic file handling and reaches continuation helper `0x40069374`; nonzero permits further startup work |
| `0x10004054`, halfword | Getter `0x4006b194`; bit 7 set skips the preceding GPIO/timing path and proceeds to the pathname/configuration branch |
| `0x100040b4..0x100040b7` | GPIO/timing configuration consumed on the bit-7-clear path; physical keys and provenance unresolved |
| `0x100040b7` low nibble, `0x100040b8` | Additional configuration values passed to updater configuration setters; no physical storage names assigned here |
| `0x100040b9..0x100040c0` | Up to eight basename bytes copied into the pathname |
| `0x100040c1..0x100040c3` | Up to three extension bytes; an empty first extension byte supplies lowercase `brn` on the custom-name path |
| `0x10000058`, halfword | Mask `0xe0` affects storage/drive initialization and selection; the controls supply zero |

These are original MMIO accesses to the `0x10000000` peripheral range. Calling
the filename fields a boot mailbox describes their use by this consumer; it
does not establish register hardware names, retention across power loss or
whether a ROM, prior firmware, debugger or factory tool writes them.

The preceding branch with boot-status bit 7 clear decodes GPIO bank/bit and
polarity fields, samples inputs through `0x4006573c`, and considers a delay.
It has not been executed in the new controls or mapped to camera buttons.
It must not be turned into a button-combination recipe.

## Pathname and branch behavior

The consumer initially copies the 16-byte string `D:\firmware.brn` from
`0x4008f105`. It can then replace the basename/extension using the mailbox.
For the supplied ready-storage and successful drive-2 probe, original code
passes the constructed pathname to `0x40041728` at `0x40000fec`.
Its result zero permits the file-action branch; nonzero takes the cleanup
path. The file-check implementation itself is modeled, not audited here.

At `0x4000100c..0x40001038`, selection checks for lowercase `.brn` using a
pointer derived from the basename-length context:

- Matching branch calls ISP task/setup `0x40009ca0`, then wrapper `0x4000fc70`.
- Other branches call file/rawC boot helper `0x4000f270` with destination
  argument zero, which that helper replaces with `0x40000000`.

The branch is **not solely a string-suffix test**. Empty-mailbox fallback and
explicit mailbox basename `firmware`/extension `brn` produce the same final
pathname but different branch contexts in the reproduced paths. This is a
control-flow observation, not a claim that the fallback path is a bug or that
the two downstream helpers accept the same files.

The update wrapper `0x4000fc70` stores initialization marker `0x5a`, stages
the pathname and tail-calls file updater `0x400172ac`. This is the small
updater's own code, analogous to the main application's resident file-update
wrapper. Those downstream routines and any persistent operations are outside
the new emulator run. Renaming a package or placing a named file on a card
would not prove safe boot or entry into this consumer; no such action is proposed.

## Eight original-instruction controls

[`ReproduceUpdaterBootMailbox.java`](scripts/ReproduceUpdaterBootMailbox.java)
loads the hash-checked updater into Ghidra's ARM emulator. All MMIO addresses
refer to synthetic memory, with boot-status bit 7 set, configuration byte
`0x100040b8 = 1`, supplied storage readiness, and a successful drive-2 probe.

| Gate word | Basename / extension | Supplied file-check result | Actual pathname | Stop before |
| --- | --- | --- | --- | --- |
| 0 | empty / empty | unused | none | `0x40069374`, continuation |
| 1 | empty / empty | 0 | `D:\firmware.brn` | `0x4000f270`, file/rawC boot |
| 1 | `TEST` / empty | 0 | `D:\TEST.brn` | `0x40009ca0`, ISP setup |
| 1 | `TEST` / `bin` | 0 | `D:\TEST.bin` | `0x4000f270` |
| 1 | `TEST` / `BRN` | 0 | `D:\TEST.BRN` | `0x4000f270` |
| 1 | `FPUPDATE` / `DAT` | 0 | `D:\FPUPDATE.DAT` | `0x4000f270` |
| 1 | `TEST` / `brn` | 1 | `D:\TEST.brn` | `0x40075ea4`, cleanup/shutdown path |
| 1 | `firmware` / `brn` | 0 | `D:\firmware.brn` | `0x40009ca0`, ISP setup |

The original status getter, register reads, pathname loops and branch logic
execute. Copy, logging, configuration setters, storage readiness, drive probe
and file-check result are modeled. Loaded updater image bytes remain unchanged
in every case; emulated stack is separate. No stopped-at helper executes.
This is not whole updater startup, a real register writer, file acceptance,
flash behavior, a RAM handoff or live recovery evidence.

Output: [`output/updater-boot-mailbox.txt`](output/updater-boot-mailbox.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis \
  -postScript ReproduceUpdaterBootMailbox.java /private/tmp/updater-boot-mailbox.txt
```

That separate local project imports `analysis/output/card-updater-full.bin`
as `ARM:LE:32:v7`, BinaryLoader base `0x40000000`, with no auto-analysis.
The existing main-image project is not repurposed. The new project was created
for focused decompilation at entries `400009c4`, `4000f270`, `4000e098`,
`4001c78c` and `40001150`; export `/private/tmp/updater-boot-consumers.txt`.
Original-byte disassembly takes precedence over imperfect decompiler types.
No new REA or live result is claimed.

## What the BootInfo message actually shows

Follow-up: [register writers and SRAM handoff](updater-dram-handoff-and-register-writers.md)
identifies running updater code that writes these BootInfo status bits. The
automatic filename/configuration-register producer is still unidentified.
The [later handoff trace](ram-handoff-copy-boundary.md) also identifies gate
`0x10001300` writes in both images' special `+0x200` entries; it does not
identify the earlier cold loader or filename-register writer.

Command-table entry `0x4009d9d8` contains the name `dkboot`, handler
`0x4000e098`, and help pointer `0x40092d22`. With no arguments the handler:

1. Calls status getter `0x4006b194`, reading halfword `0x10004054`.
2. Extracts bits 4 through 6. If nonzero, prints `Boot from bootinfo%d`
   using that value minus 1 (`0x4000e0bc..0x4000e0d4`).
3. Loops over indices 0 through 5, calling `0x4001c78c` to obtain boot records
   and print their fields.

Thus the message reports an existing status value; this branch does not
select a firmware slot and reboot into it. The three-bit field's physical
origin and exact meanings for all encoded values remain unknown.

The same handler's argument-present branch fills a record and calls setter
`0x4001c958`; it is not an inspection-only command. No shell command was run
on the camera or emulated in this pass.

Record getter `0x4001c78c` is also running updater code. It obtains a storage
interface, allocates a 4 KiB buffer, checks magic `RawCBoot` and a 4 KiB
word-sum, then accesses one of six 672-byte entries beginning at `+0x40`.
It can try another copy after a failed validation. This identifies metadata
retrieval inside the executing updater; it does not establish immutable ROM
selection or automatic fallback between the installed main firmware slots.
Neither the storage callback nor this complete getter is emulated here.

Reproduce the static evidence with `analysis/card.py disasm` over
`400009c4..40001150`, `4000e098..4000e2cc`, `4001c78c..4001c958`, and
`4006b194..4006b1c8`; use `strings` for `bootinfo`, `RawCBoot`, and `firmware.brn`.

## What remains missing

The new evidence identifies an automatic file-action consumer and specific
boot-register inputs, but it begins after the packaged updater is executing.
It does not close the earlier-stage gap. We still need the writer/loader that
supplies those inputs and places this executable in RAM, plus its actual X-half
entry conditions and persistent effects.

These addresses are useful targets for a future authentic boot log or qualified
ROM/register dump. The camera's actual values and the physical register effects
were not measured. The synthetic values in the table are not a device recipe.
