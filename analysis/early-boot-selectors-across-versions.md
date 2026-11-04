# Early application boot selectors across four firmware versions

Reviewed 2026-11-03. Offline original-byte comparison and local Ghidra 12.1.4
p-code controls. No camera access, physical input experiment, settings change,
firmware patch, downgrade, real hardware execution or fresh REA result.

## Result

The earliest selected main-image reset guard is identical in all four versions.
Its failing branch repeatedly writes a control register; it does not branch
into a USB receiver. A later early-shell selector differs: **1.30 and 2.00
reject saved-word bits `0x6000` that 1.01 and 1.20 do not test**. Version 2.00
also moves one success-state flag within its RAM state structure.

This refines the existing [recovery entry review](recovery-entry-review.md).
It does not recover a physical button combination or cold-recovery route.
The early shell still depends on the installed application, uses UART input,
and skips a normal startup branch containing loader registration; see
[early USB dependencies](early-usb-startup-dependencies.md).

The comparison found a real software change, not evidence that an older
version offers an accessible recovery mode. Actual hardware/retained input
values and a safe way to acquire or select them remain unknown.

## Scope and identities

All addresses refer to ARM little-endian main images linked at `0x40000000`.
The hashes and per-version addresses are in the
[comparison JSON](output/early-boot-selector-comparison.json). Inputs are the
same four hash-checked main images used by the prior version investigations.

This is a bounded inventory of reset, its selected guard, the bootstrap call
to the early-shell selector, and the selector's producer/consumer. It is not
an exhaustive traversal of every bootstrap helper or an immutable reset-ROM
dump. The earlier stage that initially loads this main image is still missing.

| Version | Early-shell selector | Code bytes, excluding literal pool | State base | Saved-word `0x6000` rejection | Extra success byte |
| --- | --- | ---: | --- | --- | --- |
| 1.01 | `0x4001cf2c` | 204 | `0x40d8ac50` | No | state `+0x19` |
| 1.20 | `0x4001cf2c` | 204 | `0x40d920d0` | No | state `+0x19` |
| 1.30 | `0x4001cf2c` | 216 | `0x40d9a650` | Yes | state `+0x19` |
| 2.00 | `0x4001d0dc` | 216 | `0x40e4ae50` | Yes | state `+0x0d` |

The 1.01/1.20 selector instruction bodies are byte-identical; their RAM literal
values differ. Between 1.30 and 2.00, the only selector instruction-word
differences are the record-reader BL displacement and the success-byte store
offset. Literal pools, helper implementations and surrounding boot code remain
separate evidence. Full reset `[0x40000ec0,0x40001038)` is byte-identical in all four.

## Selected earliest paths and their limits

| Path / check | What the available bytes establish | Recovery implication |
| --- | --- | --- |
| Reset's processor-affinity branches | Core-local stack setup and secondary-core dispatch through the header/rendezvous | CPU startup machinery, not a proven external entry |
| Primary reset guard `[0x40000f7c,0x40000fb8)` | Reads bit 6 at `0x10401d18` and a byte at `0x100003a2` | Control-register branch described below; no loader selection in this slice |
| Bootstrap call at `0x400039ac` | Calls the per-version selector listed above and stores its result bit | Selects a main-image shell branch, not earlier ROM |
| Initialization task `0x400035fc..0x40003618` | Tests that same runtime flag; nonzero calls the shell, zero continues normal initialization | Already known UART/interface dependency gap |
| Staged storage descriptor / slot state | Consumes earlier-stage SRAM metadata and storage fields | Provider/selection provenance is still missing; not a removable-SD boot recipe |
| Later application boot keys / calibration | Physical input and saved-setting gates already mapped in earlier work | Later than these slices; factory writes remain a barrier |

The last two rows link existing findings, not newly executed paths:
[staged storage handoff](staged-loader-storage-handoff.md),
[service boot selection](service-boot-selection.md),
[button candidate](service-mode-button-candidate.md).

### Reset control-register guard

If `(read8(0x10401d18) & 0x40) != 0`, or the subsequently checked byte
`read8(0x100003a2)` is nonzero, the inspected path stores 1 at `0x100003a0`
and continues. Otherwise it repeatedly stores 4 there using a backward branch.
The loop does not reread the inputs. It must not be described as a USB polling
loop or assigned a physical power/button meaning from this code alone.

The controls stop at the repeated-loop observation or immediately after the
continue-store, before later initialization. Effects of those MMIO writes on
real hardware are not modeled. No device would be deliberately put in this
state by the investigation.

### Early-shell selection conditions

Define `A` as power-source bit `0x01000000`, `B` as power-source bit `0x8000`,
`S` as the saved 32-bit word at state `+0x14`, and `H1/H2` as bit 0 of the
first/second reads of `0x1000517a`. Not every path performs the second read.
These names describe dataflow, not physical controls.

The branch rejects when `A` is set and either `S&3` is 1 or 2, or `S&0x800`
is nonzero. Subject to passing that check, a successful candidate is:

```text
(H1 && !B) || (H2 && A && ((S & 3) == 3))
```

From 1.30 onward, candidate success additionally requires `(S & 0x6000) == 0`.
The additional ARM instructions load state `+0x15`, test byte mask `0x60`
and branch to rejection when nonzero. The field's policy meaning is not assigned.

On success the selector sets state `+0x18` and the extra byte listed above to
1, and samples bit 0 at `0x100003a3` into state `+0x21`. It returns 1.
On ordinary rejection it returns 0; the entry clears `+0x18` in either case.
The return from the saved-record helper is not checked. A supplied failure
case therefore shows why a record-read error must not be assumed to force
normal startup: the caller can still use whatever word is already present.
Actual RAM contents after a hardware error were not observed.

The bootstrap's result pointer and the task's consumer pointer agree in all
four images. The consumer calls each version's shell implementation at
`0x4000360c`; the normal initialization call remains at `0x40003700`.

## Original-instruction controls

[InspectEarlyBootSelectors.java](scripts/InspectEarlyBootSelectors.java) hashes
each original image and executes its actual guard/selector instructions with
synthetic stack, RAM and MMIO bytes. It supplies the record-reader result and
power-getter output at their call boundaries. It does not execute those helpers,
the RTC driver, UART shell, USB stack or the complete boot sequence.

Per version:

- 256 stable-input combinations cover the two power bits, four low saved-word
  modes, `0x800`, both `0x6000` bits and the sampled hardware bit.
- Three changed-sample cases check that the first and second MMIO reads are
  distinct and that one path bypasses the second read.
- One modeled failed-record case retains supplied `S=0`; acceptance illustrates
  the unchecked helper result, not a claim about live retained RAM.
- Four reset-guard cases check continue versus repeated-store behavior.

All **1,056 controls passed**. The stable grid accepts 44 cases per older image
and 11 per newer image. These are counts in a deliberately selected grid, not
probabilities of entering a real camera mode. Selector stack balance, helper
arguments, selected state writes and unchanged executable bytes are checked.

Evidence: [control results](output/early-boot-selector-controls.txt),
[bounded disassembly](output/early-boot-selector-boundaries.txt),
[version comparison](output/early-boot-selector-comparison.json).
The disassembly's ELF payloads were checked against the complete source images;
literal pools are excluded from the selector instruction listings.

Reproduce from the repository root with the ignored images/project supplied:

```sh
python3 analysis/compare_early_boot_selectors.py
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectEarlyBootSelectors.java /private/tmp/early-boot-selector-controls.txt "$PWD"
```

This narrows application-level startup behavior. It supplies no safe way to
select the inputs, initialize the early shell's missing USB infrastructure,
or recover when the main image cannot run. Authentic earlier-stage bytes or
exact-body entry documentation remain necessary for those unresolved questions.
