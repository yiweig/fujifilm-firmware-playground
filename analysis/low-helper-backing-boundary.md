# Low helper backing: mapping audit and the missing artifact

Date: 2026-11-03. Bounded offline follow-up to the
[low-address helper investigation](low-address-loader-helper.md) and
[RAM/recovery roadmap](ram-launch-and-recovery-roadmap.md). Six local Ghidra
controls plus original ARM inspection. No camera access, live memory read,
firmware modification or new REA observation.

## Result and stopping point

The identified main/updater mapping and SRAM-copy paths do **not** recover the
implementation of CPU address `0x00000b34`. They do let us narrow an ambiguity:
under the inspected short-descriptor configuration, that low address translates
to physical `0x00000b34`, not to copied SRAM at `0x3f000b34` or packaged main
bytes at `0x40000b34`.

That is an architectural interpretation of constructed descriptors, not a live
translation result or identification of boot ROM. The SoC may provide or remap
physical low memory through machinery not recovered here. The existing files
and these identified paths still lack authenticated provider bytes.

This meets the bounded pass's stopping condition. Another supplied-state test
cannot reveal the helper instructions. Further work on its implementation needs
new backing/provider evidence or a specific previously unexamined mapping edge.
This is not proof that every opaque/compressed package region lacks a copy.

## Artifact identity

| Image, linked at `0x40000000` | SHA-256 |
| --- | --- |
| Main | `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923` |
| Complete updater | `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976` |

Addresses below belong to MAIN unless explicitly qualified as updater.
The prior four-version byte-identical SRAM-copy result remains supporting
evidence; this pass does not repeat every historical firmware control.

## Mapping interpretation

Main setup `0x400012d4` writes TTBR0 value `0x3f00404a` and invokes table
construction `0x400010a0`. Later its call to `0x40001240` includes an explicit
TTBCR write of `0x21` at `0x400012b8`. The observed configuration is consistent
with short descriptors, N=1, TTBR0 table base `0x3f004000`, and low addresses
using that table. Other mapping/context-restore paths exist, so this is not a
claim that every entry route retains these register values.

For the architectural interpretation: first-level type `01` selects a second
level; a large-page descriptor maps 64 KiB, while a section maps 1 MiB. The
address-base fields retain the low page/section offset. TTBCR controls table
selection and walk behavior. These rules come from Arm's
[ARMv7-A/R Architecture Reference Manual, B3.5 and TTBCR](https://documentation-service.arm.com/static/5f8daeb7f86e16515cdb8c4e).
They do not identify which device supplies a physical address.

The controls execute original table-building stores and apply only address
arithmetic afterward. They do not implement permissions, domain/access faults,
TLBs, FCSE, security state, stage-2 translation, SoC remaps or actual CP15 effects.

| Stage | Constructed first-level word at `0x3f004000` | Conditional address result for `0xb34` |
| --- | --- | --- |
| Initial table builder | `0x3f006001` -> second-level table `0x3f006000`; first 16 words `0x00005615` | 64 KiB mapping to physical `0x00000b34` |
| Main low-entry clear `0x40001840` | zero | Fault descriptor in a fresh walk |
| Main handoff enable `0x4000186c` | `0x0001d406` | 1 MiB mapping to physical `0x00000b34` |
| Complete updater enable `0x400007c0` | `0x0001d406` | Same descriptor interpretation; actual updater context not acquired |

The table builder separately installs `0x3f006401` at the entry for the SRAM
region, with large-page descriptors based at physical `0x3f000000`.
Consequently its conditional translation of `0x3f000b34` remains `0x3f000b34`.
The low and SRAM mappings are distinct in these constructed tables.

Clearing a table word is not itself proof that stale TLB/cache state has been
removed on hardware. The helpers contain barriers/CP15 maintenance; controls
record and skip those operations rather than simulating their physical effect.

## The known copies do not supply low helper bytes

| Copy | What is supplied / traced | Why it does not resolve the helper |
| --- | --- | --- |
| Early executable SRAM copy | Main `[0x40e37c00,0x40e38274)` -> SRAM `[0x3f000000,0x3f000674)` | Ends before SRAM offset `0xb34`; neither mapping above aliases low CPU `0xb34` to this SRAM region |
| Receiver header/workspace copy | Main `0x401e1fac..0x401e1fbc` copies `0x4000` bytes to SRAM `0x3f000000` after interface operation 10 | Includes SRAM offset `0xb34`, but source bytes are runtime interface data, not a captured provider; its high SRAM destination is still distinct from the low mapping |
| Uploaded image hardware copy | Source/destination/chunk convention already traced | The code still calls low `0xb34` before the new image entry; it does not establish a producer for that helper |

The new early-copy control runs the original copy instructions, verifies the
1,652 copied bytes and helper arguments, and checks that a supplied sentinel
at SRAM+`0xb34` remains unchanged. It stops before the low helper. The copied
blob SHA-256 remains
`d84750d2654065b9908e6d7651af2bc1aa9ac517e7561b563e5cb97008b79a6b`.

A raw DAT offset `0xb34`, main offset `0xb34`, and CPU address `0xb34` are
three different coordinates. No traced mapping justifies treating any convenient
file offset as the missing implementation. Nor does finding ordinary cache
maintenance elsewhere establish byte/function equivalence with this helper.

## What additional artifact is needed

A useful target is an **authenticated X-HF1 low-address provider image**, preferably
covering at least `[0x00000000,0x00010000)`. That includes the known neighboring
service-entry addresses through `0x4e34`; function lengths and dependencies may
require more. This is an artifact specification, not a qualified live read range.

The artifact must include:

- Exact camera/platform and installed-version identity, acquisition provenance,
  boot/USB state, byte count and SHA-256.
- Whether addresses are file offsets, CPU virtual addresses or physical addresses.
- For a CPU-memory capture, relevant SCTLR/TTBR0/TTBR1/TTBCR values and table
  entries, plus processor/security context and any relevant additional translation
  or SoC-remap evidence. Otherwise its relation to low physical memory is unknown.
- Enough surrounding code/data to follow dependencies; a plausible instruction
  sequence at offset `0xb34` alone is not adequate provenance.

An alternative is an authentic earlier-stage image with validated load/link
mapping that identifies the helper and its dependencies. A documented X-half
service/boot contract could help locate or obtain that evidence. A stock update
package, another camera's flash dump, and a generic iCatch programmer are not
interchangeable with it.

No safe acquisition route for that artifact is established. The existing
application memory-reader parser lacks enforced safety bounds and loader entry
is unresolved. This report does not propose guessed reads, factory entry,
opening the body or UART access. The user excludes invasive acquisition.
Sources: [memory-reader boundary](loader-memory-read-boundary.md),
[prior public-artifact review](public-boot-artifact-review.md).

## Six controls and reproduction

[InspectLowHelperMapping.java](scripts/InspectLowHelperMapping.java) checks
both image hashes and performs:

1. Original main table construction; checks low and SRAM translations remain distinct.
2. Construction plus original low-entry clear; checks a fault descriptor.
3. Construction plus original handoff enable; checks section identity mapping.
4. Original MOV/MCR slice supplying TTBCR `0x21`, stopping before function return.
5. Original early SRAM copy, stopped at helper entry, with copied bytes and
   SRAM+`0xb34` sentinel checked.
6. Original complete-updater low-entry enable with supplied SRAM table memory.

All six pass. CP15 writes and barriers are recorded/skipped, not applied to
an emulated MMU. All loaded-image bytes remain unchanged. No low-helper
instructions, physical MMU/cache operations, USB/storage operation, payload
or camera executes. These controls validate construction and conditional address
arithmetic, not the real processor's final translation or low-memory contents.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectLowHelperMapping.java /private/tmp/low-helper-mapping.txt \
  "$PWD/analysis/output/card-updater-full.bin"
```

Evidence: [six controls](output/low-helper-mapping.txt),
[qualified original instruction ranges](output/low-helper-mapping-static.txt),
[mapping/copy summary and artifact requirements](output/low-helper-backing-summary.json).
The existing disassembly ELF payload was checked equal to the original main.
No external binary or new firmware was downloaded in this pass; Arm reference
material was consulted for descriptor semantics, not camera-specific ROM identity.

## Roadmap consequence

R2 is narrower: the identified mapping/copy paths do not expose a hidden alias
to helper bytes already located in the known SRAM blob or main image. R2 is
still **unresolved pending provider evidence**, not completed. R1 temporary retail
loader access and C1 independent cold recovery also remain open.

Stop expanding this particular mapping hypothesis without new evidence. The
next useful work is to obtain credible earlier-stage/service material or identify
a genuinely new qualified access route within the user's constraints. Repeating
these supplied-state mapping controls cannot supply the missing implementation.
