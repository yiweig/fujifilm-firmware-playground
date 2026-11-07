# 🔴🟡🔴🟡 DISCLAIMER — READ THIS FIRST 🟡🔴🟡🔴

> ## ⚠️ THIS IS VIBE-CODED RESEARCH. THERE IS A REAL RISK OF BRICKING YOUR DEVICE. ⚠️
>
> **This repository is experimental firmware research produced with heavy AI
> assistance ("vibe coded"). It is not a product, not a guide, and not
> endorsed by Fujifilm or anyone else.**
>
> 🔴 **You can permanently brick your camera.** Firmware, service-mode, and
> RAM-loader experiments can corrupt firmware, factory calibration data, or
> other device-specific state. There is **no proven recovery procedure**
> for the Fujifilm X half. A failed experiment may leave you with a dead
> camera that only Fujifilm service — if anyone — can revive, possibly at
> significant cost, possibly never.
>
> 🟡 **No support whatsoever.** I offer **no support**: no help flashing,
> no help unbricking, no answers to "it doesn't work", no guarantees that
> any procedure here is accurate, complete, safe, or repeatable on your
> unit. Findings are specific to one camera on specific firmware versions
> and may be wrong.
>
> 🔴 **If you follow anything in this repo, you do so entirely at your own
> risk, on your own hardware, accepting that you may destroy it.** Do not
> attempt any of this on a camera you cannot afford to lose. If that is
> not acceptable, close this tab now.
>
> **No warranty of any kind, express or implied. No liability accepted.**

---

# Fujifilm X half firmware research

Static-analysis workspace for understanding the Fujifilm X half firmware,
documenting behavior, and evaluating low-risk modifications. Original firmware,
extracted binaries, camera image dumps, Ghidra databases, and machine-local tool
state are intentionally excluded from version control.

## Start here: handoff overview

Last overview refresh: **2026-11-06**. This is a research notebook and collection
of analysis tools, not a firmware patcher or a deployable camera modification.
Most firmware investigations are offline; live observations are explicitly
identified in their reports. A camera is not required for the static tools.

The original goals are to fix the secondary display showing Provia during
lever review, replace Auto ISO 3's ISO-12800 cap with ISO 6400 (and assess a
fourth preset), and explore RAW feasibility. The current priority is establishing
RAM execution and a confirmed recovery path before considering a modified flash.
The user excludes opening the camera, wiring UART or sending it to a repair lab.

Read these first:

1. [Current findings](analysis/current-findings.md) — the state of record for the
   RAM-push, SD-boot and recovery work: what is proven, what is blocked, and which
   earlier hypotheses have been corrected. Read this before any older report.
2. [Boot architecture review, piece 1](analysis/boot-architecture-review.md) — the
   boot-stage and handoff-contract map: which stage owns what, what state passes
   between stages, and why the SD failure is a stage/ownership problem.
3. [How the firmware fits together](analysis/architecture-guide.md) — a connected,
   beginner-oriented account of the package, startup, tasks, imaging, USB and storage.
4. [RAM-launch and recovery roadmap](analysis/ram-launch-and-recovery-roadmap.md) —
   the proposed launch path with evidence levels, ranked blockers, and which leads
   have already been examined. Temporary execution and independent recovery are
   separate outcomes; neither has been established end to end.
5. [Hardware/boot diagrams](analysis/hardware-and-boot-diagrams.md) and
   [memory map](analysis/memory-and-hardware-map.md) — physical/address-space
   orientation, with unresolved regions explicitly marked.

Recent task/clock traces narrow handoff preparation, but do not close the missing
retail loader entry or independent recovery. Next research should target evidence
for those access/earlier-stage gaps before automatically expanding another callee.

| Topic | Current finding | Start with |
| --- | --- | --- |
| Lever-review Provia label | Strong evidence of a 2.00 regression: an uninitialized cached selector remains zero, meaning Provia. Original ARM behavior reproduced offline; no camera fix installed. | [Lever findings](analysis/lever-provia-findings.md), [four-version comparison](analysis/firmware-1.30-vs-2.00.md) |
| Auto ISO | Replacing Auto 3's range code is a narrow candidate; ISO 6400 already exists. Adding another preset affects more settings/UI paths. No firmware bytes patched. | [Auto ISO findings](analysis/auto-iso-findings.md) |
| RAW | Engineering sensor-dump code exists; it does not establish production RAW/RAF support. | [RAW assessment](analysis/raw-support-assessment.md), [rcaraw handler](analysis/rcaraw-handler.md) |
| Normal camera communication | After macOS authorization, standard GetDeviceInfo and explicit close succeeded. All 26 advertised codes mapped offline; `9812` selects an image-object handler in all four images, not an established updater entry. PTP version `01.00` does not attest the installed release. | [Session findings](analysis/macos-device-info-session.md), [operation review](analysis/ptp-advertised-operation-review.md) |
| Optional `9601` hook | Wider offline search found only its known setter; neither registration nor completion appears in the ten known wrapper call sites. A nearby copy cannot reach it. An unused SDK hook is a hypothesis; live pointer state remains unknown. | [Hook review](analysis/ptp-9601-hook-review.md) |
| Startup RAM initialization | Stage worker requests hardware zero fills through `0x414fb780`, including the optional PTP hook's RAM. Six original-instruction controls validate request construction; actual hardware success remains unobserved. | [Staged RAM initialization](analysis/staged-boot-ram-initialization.md) |
| Earlier-stage storage handoff | A 36-byte SRAM descriptor supplies primitive pointers; the application translates six recognized values to NAND routines. Staged 16-bit checksums and an early CPU clear are identified. The provider's bytes and recovery selectors remain unknown. | [Storage handoff](analysis/staged-loader-storage-handoff.md) |
| Updater firmware selection | A conditional callback can request Firmware2. Its installed-region read checks the image's byte sum against two combined map fields; 11 offline controls pass. Installed Firmware2 contents and independent cold entry remain unknown. | [Selection trace](analysis/updater-firmware-selection.md), [image checks](analysis/updater-installed-image-checks.md) |
| Updater error path | Returned file errors can continue toward USB startup. The formerly mislabeled shutdown helper acquires USB resources and programs a PHY/PLL block; eight focused controls pass. Live enumeration remains unverified. | [Return path](analysis/updater-file-failure-continuation.md), [resource bring-up](analysis/updater-usb-resource-bringup.md) |
| Updater storage activation | NAND header parsing during initialization can reach the erase-capable table builder before a mount request. Five parser controls stop before construction; actual installed metadata is unknown. Stock-updater RAM execution is not proven volatile. | [Initialization risk](analysis/updater-nand-initialization-risk.md), [mount/write risk](analysis/updater-mount-write-risk.md) |
| RAM loader | Device-side USB transfer and copy/execute code identified. Temporary retail entry, full handoff effects and payload safety remain unqualified. | [Receiver](analysis/xhalf-usb-ram-receiver.md), [handoff](analysis/ram-handoff-copy-boundary.md) |
| Handoff preparation | Optional callback lifetime, sensor timeout, candidate mode exits, playback cleanup, mutex/wait cleanup and clock synchronization traced in bounded controls. Close errors can be ignored; lock release can reach task-resume work. Real callbacks, concurrent effects and the hardware clock core remain unresolved. | [Architecture guide](analysis/architecture-guide.md), [clock audit](analysis/idle-clock-switch-and-callbacks.md) |
| Temporary USB entry | Loader selection uses a cached flag. A wider search still finds the five known writers; none supplies a qualified live retail sequence. Shared-setting staging has reload/save hazards. | [Latest writer search](analysis/usb-selection-writer-search.md), [refresh reachability](analysis/usb-cache-refresh-reachability.md), [buffer lifetime](analysis/cold-buffer-lifetime.md) |
| Cold recovery | No verified X-half SD boot, independent USB recovery or cold-boot fallback procedure. Two firmware slots and updater safeguards do not prove recovery. | [Risk assessment](analysis/brick-risk-and-recovery.md), [entry review](analysis/recovery-entry-review.md) |
| Observed ordinary USB startup | One normal `04cb:030a` arrival during user-performed power-on; no intermediate identity/removal observed. Registry events cannot expose internal startup or failed enumeration. | [Live startup observation](analysis/usb-normal-startup-observation.md) |
| Emulated factory-read route | Original marker/registration code now connects to normal dispatch and response construction in one model. Eight checks pass, including full synthetic record reconstruction and failed-open stale-data behavior. | [Integrated emulation](analysis/ptpap-integrated-read-emulation.md) |
| UVC entry follow-up | Preliminary: an application debug event also reaches UVC callback setup. Its normal retail producer and any loader transition remain unqualified. | [Current trace](analysis/uvc-debug-event-followup.md) |
| Additional UI loader branches | Five more branches share the same cached-flag gate; all 1,280 narrow byte-value checks pass. No alternative retail transition established. | [UI gate comparison](analysis/remaining-ui-loader-gates.md) |
| Factory marker activation | Offline ARM controls: marker adds seven command registrations; removing it or closing a session preserves them. Startup requests a table-covering zero-fill, but physical revocation and host traffic remain unqualified. | [Activation review and draft experiment](analysis/ptpap-marker-activation-review.md) |
| Factory read availability | One fixed `1027(0)` probe returned `2005` (unsupported) in normal USB mode, then closed normally. No factory record acquired or SD marker created. | [Availability probe](analysis/factory-chunk-availability-probe.md) |
| Read-only vendor property | `GetDevicePropDesc(d220)` succeeded: UINT32, current 0, range 0–1. Static code suggests transfer/status bookkeeping; exact semantics unresolved. Session closed normally. | [Property investigation](analysis/d220-property-investigation.md) |
| Active standard metadata reads | GetStorageIDs returned `00010001`; GetStorageInfo for that ID reports removable DCF media, about 31.9 GB capacity. Both closed their sessions; no raw-memory or recovery access established. | [StorageIDs](analysis/standard-storage-ids-live.md), [StorageInfo](analysis/standard-storage-info-live.md) |
| Earlier-stage helper | Known table/copy paths do not recover `0x00000b34`; conditional mappings point to low physical memory, not the known SRAM blob/main-image offset. Authentic provider bytes/mapping are still needed; six controls narrow this boundary. | [Backing/artifact gap](analysis/low-helper-backing-boundary.md), [earlier helper trace](analysis/low-address-loader-helper.md) |

For orientation, read the [hardware/boot diagrams](analysis/hardware-and-boot-diagrams.md)
and [memory map](analysis/memory-and-hardware-map.md), then the report for the
specific question. The full index below preserves the investigation history.
Read follow-up/correction sections before reusing an older conclusion.

The [architecture guide](analysis/architecture-guide.md) synthesizes the model;
the [roadmap](analysis/ram-launch-and-recovery-roadmap.md) owns the consolidated
priorities. Detailed reports remain the source for addresses, evidence and
reproduction. These summaries add no new camera observations.

## Repository layout

Run the command examples from the repository root. A fresh clone contains the
tracked documentation, scripts and selected small evidence files; ignored
binaries and local analysis databases must be supplied or regenerated separately.

```text
README.md                       Overview, status, report index and tool setup
.gitignore                      Firmware, dumps, databases, caches and archive exclusions
FPUPDATE.DAT                    Official 2.00 package (local, ignored)
firmware-main.bin               Extracted 2.00 application/DRAM image (local, ignored)
firmware-code.bin               Historical partial extract (local, ignored; not the main target)
firmware/
  {1.01,1.20,1.30,2.00}/
    FPUPDATE.DAT                Official versioned packages (local, ignored)
    SHA256SUMS                  Tracked SHA-256 manifest for each version
analysis/
  *.md                          Findings, provenance, limitations and reproduction instructions
  *.py                          Offline parsers, searches, protocol models and checks
  hardware-register-blocks.tsv   Curated hardware register-block summary
  run-ghidra.sh                 Local headless Ghidra/Java launcher
  scripts/*.java                Ghidra queries, function preparation and ARM emulator controls
  output/                       Selected tracked evidence plus ignored generated artifacts
  project/, cache/, settings/   Machine-local Ghidra/tool state (ignored)
```

Root `*.gpr`/`*.rep` files, when present, are also ignored local Ghidra state.
Some working projects and comparison corpora were created under `/private/tmp`;
those paths in reports are provenance, not files available in a fresh clone.

Each version directory has a `SHA256SUMS` file in standard checksum format.
All four packages were rehashed and matched their previously recorded SHA-256
and size on 2026-11-02. To verify a local copy, run inside its version directory:

```sh
cd firmware/2.00
shasum -a 256 -c SHA256SUMS
```

The root-level 2.00 package remains the default input for existing analysis tools;
the versioned copy is `firmware/2.00/FPUPDATE.DAT`.

| Tool or file group | Purpose / boundary |
| --- | --- |
| [inventory_firmware.py](analysis/inventory_firmware.py) | Package regions, hashes, checksums and embedded filesystem/Linux inventory; emits JSON, not an installation image |
| [fw.py](analysis/fw.py) | Main-image disassembly, strings, literal pools and bounded caller searches; `--image PATH` selects a historical main image |
| [card.py](analysis/card.py) | Same style of inspection for the **complete 2.00 updater**; hash-checks it and can carve it from the local package |
| [fat16_read.py](analysis/fat16_read.py), [squashfs_list.py](analysis/squashfs_list.py) | Read packaged resources and Linux filesystem metadata without mounting them |
| [map_hardware.py](analysis/map_hardware.py), [find_usb_flag_stores.py](analysis/find_usb_flag_stores.py) | Heuristic address/store searches. Manually validate candidates; stale register tracking and missed dynamic paths limit completeness |
| [audit_public_boot_candidates.py](analysis/audit_public_boot_candidates.py) | Bounded audit of a separately supplied V50 comparison corpus; distinguishes real files from Git LFS placeholders |
| [inspect_v50_flash_layout.py](analysis/inspect_v50_flash_layout.py) | Hash-checked offline V50 external-flash layout parser; resource volumes, main image and unallocated trailing data, without inferring CPU-ROM mapping |
| [inspect_v50_package_isp.py](analysis/inspect_v50_package_isp.py) | Pinned V50 package inventory, ISP reset/copy literals, banner and stored USB identity; see the companion REA comparison for code evidence |
| [inspect_frm_bundle.py](analysis/inspect_frm_bundle.py) | Hash-checked FRM/driver inventory, selected configuration and keyboard/timer tables; no extraction, executable launch or device access |
| [compare_early_boot_selectors.py](analysis/compare_early_boot_selectors.py) | Four-version reset/early-shell selector comparison with hash-checked images and per-version producer/consumer addresses |
| [usb_startup_observer.c](analysis/usb_startup_observer.c) | macOS registry arrival/removal observer; numeric metadata only, no USB interface opens or camera commands; local captures ignored |
| [ptp_cold_reader.py](analysis/ptp_cold_reader.py), [ptp_usb_transport.py](analysis/ptp_usb_transport.py) | Restricted read-protocol model and adapter for an already supplied transport. No turnkey live discovery/acquisition client; success/checksum do not attest fresh storage data |
| [usb_registry_inventory.py](analysis/usb_registry_inventory.py), [read_usb_configuration.c](analysis/read_usb_configuration.c) | macOS USB metadata helpers with different access behavior; read [descriptor API limits](analysis/macos-descriptor-api.md) before considering device access |
| [run-imagecapture-inventory.sh](analysis/run-imagecapture-inventory.sh), [imagecapture_inventory.m](analysis/imagecapture_inventory.m) | Builds and launches a discovery-only Mac application; successfully identified the X-HF1 through Apple's service. No session-open or PTP command code; incidental framework traffic is unmeasured |
| [ptp_device_info.py](analysis/ptp_device_info.py) | Fixed GetDeviceInfo encoder, serial-redacting DeviceInfo decoder and bounded StorageIDs, StorageInfo and fixed `d220` descriptor decoders. PTP version fields do not independently attest the Fujifilm release |
| [run-imagecapture-device-info.sh](analysis/run-imagecapture-device-info.sh), [imagecapture_device_info.m](analysis/imagecapture_device_info.m) | One fixed read/probe per invocation: DeviceInfo, StorageIDs, StorageInfo for known ID `00010001`, descriptor for `d220`, or factory cached-chunk availability; `--build`/`--self-test` perform no camera access. Four metadata reads succeeded live; the factory probe returned unsupported and closed normally; [StorageInfo](analysis/standard-storage-info-live.md) needed delayed macOS authorization. Apple's catalog traffic is separate |
| [ptp-advertised-operation-map.tsv](analysis/ptp-advertised-operation-map.tsv), [compare_ptp_dispatch_versions.py](analysis/compare_ptp_dispatch_versions.py) | Offline mapping of all 26 observed advertised codes and hash-checked comparison of selected dispatcher/handler bodies across all four versions; no new camera command support |
| `analysis/test_*.py` | Offline protocol/transport/registry tests; no camera needed |
| `analysis/scripts/QueryFirmware.java`, `DecompileTargets.java`, `ExportIndex.java` | Focused database references, pseudocode and index exports; analysis metadata may change, firmware bytes do not |
| `analysis/scripts/Reproduce*.java` | Original-instruction emulator experiments with explicit fixtures/stubs and stop boundaries; read the owning report for exactly what executes |
| `analysis/output/*.txt`, `*.json`, `*.tsv` | Curated results, disassembly/pseudocode, inventories and evidence records. Some older REA exports are tracked despite newer ignore rules; presence does not make historical mappings current |

There is no single build command or complete camera emulator. Run only the
checks relevant to the chosen investigation. Typical offline starting commands:

```sh
python3 analysis/inventory_firmware.py FPUPDATE.DAT --output /tmp/xhalf-regions.json
python3 analysis/fw.py disasm 0x4043f078 0x4043f190
python3 analysis/card.py disasm 0x40000200 0x40000248
python3 analysis/find_usb_flag_stores.py --window 256
python3 analysis/ptp_cold_reader.py --simulate
python3 -m unittest discover -s analysis -p 'test_*.py'
```

These commands are offline. Disassembly helpers can create ignored ELF wrappers;
`card.py` can create its ignored binary extract. `fw.py` and `map_hardware.py`
use a machine-specific Xcode LLVM path. Python tools mainly use the standard
library; Ghidra experiments additionally need the Java/Ghidra setup below.

## Address conventions and evidence rules

- Always name the **artifact, version, hash and coordinate space**. DAT offsets,
  extracted-image offsets, CPU addresses and storage offsets are different.
  Most current runtime addresses refer to the 2.00 main image at `0x40000000`.
- The complete updater is a **separate executable at the same link base**.
  For 2.00 it occupies DAT `[0x200,0xa2c00)`, with reset body at image offset
  `0x340` / DAT `0x540`. The old `card-updater.bin` omitted the first `0x340`
  bytes; old exports mapping that shortened file at `0x40000000` are unreliable.
  Use [the mapping correction](analysis/updater-image-layout.md) and current
  `card.py`. Main-image `0x405e...`/`0x405f...` updater-library routines do not
  belong to the small updater merely because they perform updates.
- Confirm findings against original instructions. Ghidra `FUN_*` labels and
  analyst descriptions are not recovered vendor symbols; diagnostic-derived
  names should retain their stated provenance. Function entries sometimes
  precede a push prologue by one instruction.
- Offline emulator success proves behavior only for the supplied memory,
  modeled callees and stop boundary. It does not prove live reachability,
  hardware semantics, persistent-write safety or recovery. Do not stitch
  separately supplied UI states/events into an asserted camera procedure.
- Existing REA evidence is retained where useful. Later work uses local Ghidra
  and original-file LLVM disassembly after a recorded raw-image mapping issue.
  Check the artifact/mapping before reuse; do not imply a fresh REA or live
  observation when a result came from the local fallback.

For another AI: choose one bounded unresolved question, read its latest report
and linked script/output, and document the resulting evidence and limitations.
The current USB-entry gap concerns actual retail reachability of a flag refresh
while preserving shared RAM and avoiding incidental saves. Another isolated
flag/loader fixture or unchanged writer scan does not close it. Work on original
UI features can continue offline, but deployment remains gated on recovery.

## Current research notes

- [`analysis/first-raw-capture.md`](analysis/first-raw-capture.md)
  — **first RAW file captured**: `rcaraw 17 D: x` via the shell wrote a
  1,325,184-byte preview frame (file named `D`); decoded as 12-bit
  left-justified RGGB — block-demosaic render shows a real, recognizable
  scene. Bare `rcaraw` crashes the handler (garbage argv) — syntax was
  emulator-qualified after that. Next: full-frame selector 19 + DNG.
- [`analysis/apitest-shell-live.md`](analysis/apitest-shell-live.md)
  — APITEST bridge activated live: shell task woke, emitted `cmd>` as
  per-char records on EP `0x84`; console stream decoded — sensor is
  **Sony IMX283, RGGB, RAW_16, 4872×3672**. Lessons: drain EP `0x84`
  before `init(1)` or USB wedges; post-activation transfers are slow
  and can desync. Acknowledged command round-trip is next.
- [`analysis/apitest-shell-bridge-design.md`](analysis/apitest-shell-bridge-design.md)
  — APITEST (`0xfa`) shell bridge fully traced: init cases, char queue into
  the Cmd task, output hooks to a DRAM buffer; bootstrap gate to be
  bypassed by a volatile state-word write via the loader primitive.
  First live command planned is a deliberate unknown (`zztest`) toward
  `rcaraw 17` (preview RAW) as the first capture.
- [`analysis/loader-stage-copy-live.md`](analysis/loader-stage-copy-live.md)
  — trampoline v2 staged a full 64 KB SRAM image to the host (SPBoot header,
  DRAM params, the 36-byte earlier-stage descriptor at `0x3f00c518`); the
  subsequent low-memory (`0xb00`) CPU copy wedged the camera (battery-pull
  recovery, SRAM already safe). Low region remains unreadable.
- [`analysis/loader-hijack-test-live.md`](analysis/loader-hijack-test-live.md)
  — **first code execution**: handler slots patched to a scratch trampoline
  at `0x41100040`; it ran 13 times (counter in scratch), forwarding to the
  real handler throughout; slots restored/verified (brief CPU-cache lag
  observed, no DRAM mirror). Execution inside the live loader is proven
  without the destructive upload/run handoff.
- [`analysis/loader-write-test-live.md`](analysis/loader-write-test-live.md)
  — first loader write: 32-byte pattern to scratch `0x41100000` via `0x04f5`,
  read-back identical and stable over a 20 s hold (usbmon-captured).
  Read and write primitives now qualified on aligned DRAM / scratch range.
- [`analysis/dram-dump-mining.md`](analysis/dram-dump-mining.md)
  — from the DRAM dump: internal layout solved (512 MiB; headers 2 +
  reserved 487/45/261 = Firmware1 at block 795, length = image size) and
  **Firmware2 is empty** (start 2048, zero length/blocks/checksums) — no
  fallback copy exists on this unit. Scratch-RAM candidate identified in
  the 2.4 MiB zero gap for the first write/readback test.
- [`analysis/loader-memory-read-live.md`](analysis/loader-memory-read-live.md)
  — loader protocol live from the VM: boot query returns method 2; memory
  reads succeed for 32-byte-aligned DRAM (up to 64 KiB/transfer; full
  20 MiB DRAM dumped, SHA-256 recorded, cross-validated against the PTP
  record) and fail/hang for misaligned DRAM, SRAM, and low memory.
  Battery removal is the wedged-state reset. Repeat entry = record no-op.
- [`analysis/calibration-loader-entry-live.md`](analysis/calibration-loader-entry-live.md)
  — **live loader entry**: `EnableTest.txt` + MOVIE/flash-ON/PLAY-hold reaches
  USB API mode 4; `2aad:6500` enumerated, descriptors captured in the VM,
  no controls pressed, power-off exit. Post-entry record diff = exactly the
  predicted 3 bytes (`+0x04`, `+0x08`, checksum); camera normal afterwards.
  R1 established for temporary execution; cold recovery (C1) unchanged.
- [`analysis/uvc-debug-producers-trace.md`](analysis/uvc-debug-producers-trace.md)
  — all `f100000f`/`10e7` producers traced (DbgMsg thread, `dm` shell command,
  UVC service thread, handler self-posts); each requires a pre-existing
  shell/calibration/UVC context. No retail-reachable UVC trigger; lead closed.
- [`analysis/clbt-init-record-selection.md`](analysis/clbt-init-record-selection.md)
  — `InitClbtData` emulated with the live record as fixture: selector takes
  its no-candidate exit (no item auto-selected, item-20 exception cannot
  apply), and the save-boundary RAM diff is exactly `+0x04`/`+0x08`. With
  item 0 the scheduler queues only the menu-open event; no worker runs
  without physical confirmation.
- [`analysis/factory-ptp-live-acquisition.md`](analysis/factory-ptp-live-acquisition.md)
  — live factory-PTP activation through a UTM/Linux passthrough VM with
  usbmon evidence: marker activates `1027`, 1,024-byte factory record read
  twice and checksum-valid, restart without marker revokes access. Live
  values: `DisableTest=1`, `FgUsbMode=0x18` (normal-UI loader branch
  closed), `FgAutoClbt=0x18` (auto scheduling on), `0x168=0`.
- [`analysis/usb-cold-enumeration-matrix.md`](analysis/usb-cold-enumeration-matrix.md)
  — live passive enumeration matrix: shutter chords (both forms), PLAY alone,
  USB-only power without battery; only normal `04cb:030a` ever appeared, no
  `2aad:6500` or intermediate identity. No DISP/BACK button exists on this
  body. Calibration combo deliberately not tested; cold recovery still open.
- [`analysis/macos-imagecapture-ptp-route.md`](analysis/macos-imagecapture-ptp-route.md)
  — possible PTP route through Apple's camera service; SDK/session catalog
  boundary inspected, with no framework session or camera request performed.
- [`analysis/sd-command-reader-reachability.md`](analysis/sd-command-reader-reachability.md)
  — SD reader still lacks a known caller; original command-copy controls show
  an unchecked stack boundary, and optional shell startup is identified as Wdg.
- [`analysis/ui-bulk-writer-review.md`](analysis/ui-bulk-writer-review.md)
  — date/time property copy bounds exclude the cached USB flag; selected
  Ghidra coverage remains sparse and does not close the bulk-writer gap.
- [`analysis/usb-selection-writer-search.md`](analysis/usb-selection-writer-search.md)
  — wider cached-flag store search reproduces the five known writers;
  bulk-copy coverage remains partial and no new retail entry is established.
- [`analysis/public-boot-artifact-review.md`](analysis/public-boot-artifact-review.md)
  — bounded V50 artifact audit, missing LFS payloads, different-chip recovery
  reports and why none yet supplies the X-half low-helper implementation.
- [`analysis/low-address-loader-helper.md`](analysis/low-address-loader-helper.md)
  — second caller of low helper `0xb34`, stable earlier-stage API leads,
  and four original-ARM SRAM-copy controls across official firmware versions.
- [`analysis/ram-handoff-copy-boundary.md`](analysis/ram-handoff-copy-boundary.md)
  — original copy-stub/address-guard controls, special-entry gate writers,
  and why the updater handoff still cannot be treated as a harmless RAM test.
- [`analysis/updater-dram-handoff-and-register-writers.md`](analysis/updater-dram-handoff-and-register-writers.md)
  — application-to-updater DRAM parameter handoff, nine offline restore controls,
  software-written BootInfo status and limits of the filename-register search.
- [`analysis/updater-boot-register-consumer.md`](analysis/updater-boot-register-consumer.md)
  — packaged-updater boot-register/pathname consumer, eight original-ARM
  boundary controls and the distinction between BootInfo diagnostics and fallback.
- [`analysis/updater-firmware-selection.md`](analysis/updater-firmware-selection.md)
  — conditional storage callback, rawC configuration loop and final Firmware2
  request inside the executing updater; named selections and boot-status bit 1.
- [`analysis/updater-installed-image-checks.md`](analysis/updater-installed-image-checks.md)
  — two storage backends' installed-map metadata, Firmware2 region read and
  split checksum fields; 11 original-ARM controls stop before status writes.
- [`analysis/updater-loaded-image-handoff.md`](analysis/updater-loaded-image-handoff.md)
  — updater boot task, SRAM block, relocated copy/jump stub and broader address
  guards; 22 combined controls stop before the unresolved low helper or payload.
- [`analysis/updater-usb-parameter-producer.md`](analysis/updater-usb-parameter-producer.md)
  — updater USB receiver produces the preserved parameter fields; startup
  registers its handler, tasks and queues, but independent entry is unresolved.
- [`analysis/updater-usb-identity.md`](analysis/updater-usb-identity.md)
  — target-0 GET_DESCRIPTOR path, `2aad:6500` template and vendor endpoints;
  runtime packet-size mutation and offline descriptor extraction.
- [`analysis/updater-file-failure-continuation.md`](analysis/updater-file-failure-continuation.md)
  — returned file errors can reach the USB-startup boundary if platform helpers
  return; 11 controls and correction of the earlier "shutdown" label.
- [`analysis/updater-usb-resource-bringup.md`](analysis/updater-usb-resource-bringup.md)
  — error-path helper acquires USB resources and programs the PHY/PLL block;
  eight original-ARM controls and main-image corroboration, without live USB proof.
- [`analysis/updater-command-task.md`](analysis/updater-command-task.md)
  — corrects "ISP task" to the `Cmd` interpreter; separates table registration,
  storage-worker startup and explicit update commands from automatic file updating.
- [`analysis/updater-mount-write-risk.md`](analysis/updater-mount-write-risk.md)
  — storage activation can reach conditional NAND block erase during table
  construction; startup-message distinction and eight controls before storage calls.
- [`analysis/updater-startup-storage-routing.md`](analysis/updater-startup-storage-routing.md)
  — distinguishes NAND logical drive 1 from SD mount paths 2/5; traces immediate
  NAND initialization during startup registration, with eight bounded controls.
- [`analysis/updater-nand-initialization-risk.md`](analysis/updater-nand-initialization-risk.md)
  — low NAND initialization can reach erase-capable reserved-region table
  construction through header parsing; five controls stop before the table builder.
- [`analysis/updater-usb-storage-separation.md`](analysis/updater-usb-storage-separation.md)
  — USB target/task/queue construction reaches controller bring-up with an empty
  storage registry under modeled services; four controls, with real entry unresolved.
- [`analysis/updater-usb-reset-and-callback-state.md`](analysis/updater-usb-reset-and-callback-state.md)
  — refines that boundary to asynchronous `usbDclBusReset`; callback registration
  preserves occupied handlers, while normal startup clears their RAM; five controls.
- [`analysis/updater-usb-reset-exchange.md`](analysis/updater-usb-reset-exchange.md)
  — traces the target-0 reset/acknowledgement gate through original dispatchers;
  four controls, corrected pointer-queue model, and five repaired controls rerun.
- [`analysis/updater-startup-phy-gate.md`](analysis/updater-startup-phy-gate.md)
  — the gate-zero path also bypasses identified PHY acquisition; four controls
  and a bounded register-writer search, with inherited hardware state unresolved.
- [`analysis/main-handoff-usb-state.md`](analysis/main-handoff-usb-state.md)
  — retail preparation getters/resource acquisition and a separate controller
  helper preserve supplied PHY state; four controls, with full handoff unresolved.
- [`analysis/main-handoff-optional-callback.md`](analysis/main-handoff-optional-callback.md)
  — calibration-path registration of the pre-handoff callback; backlight/lens/idle
  call edges and five controls, with actual retail callback state unresolved.
- [`analysis/main-loader-callback-lifetime.md`](analysis/main-loader-callback-lifetime.md)
  — intended stage-23 startup clearing versus preservation by loader reset
  initialization; four controls and bounded writer-search limits.
- [`analysis/main-idle-mode-handoff.md`](analysis/main-idle-mode-handoff.md)
  — asynchronous idle transition, mode exits and conditional DRAM-clock changes;
  five guard/wait controls, with deeper storage/USB effects unresolved.
- [`analysis/main-sensor-and-mode-exits.md`](analysis/main-sensor-and-mode-exits.md)
  — identifies the sensor power-save timeout and all six candidate non-idle exits;
  15 bounded controls, with shared cleanup and clock effects still unaudited.
- [`analysis/playback-cleanup-before-handoff.md`](analysis/playback-cleanup-before-handoff.md)
  — identifies five playback tasks, readiness gates, ignored close errors and
  a runtime termination hook; 16 controls, with kernel/hook effects unresolved.
- [`analysis/termination-synchronization-hooks.md`](analysis/termination-synchronization-hooks.md)
  — mutex construction installs the termination hook; queue/event wait cleanup
  and ownership transfer reach scheduler boundaries in ten bounded controls.
- [`analysis/idle-clock-switch-and-callbacks.md`](analysis/idle-clock-switch-and-callbacks.md)
  — callback API, clock-table gate, display-event wait and error/cleanup order;
  15 bounded controls, with physical clock work and runtime callbacks unresolved.
- [`analysis/low-helper-backing-boundary.md`](analysis/low-helper-backing-boundary.md)
  — six mapping/copy controls distinguish low physical memory from known SRAM
  copies; records the missing provider artifact and a stopping point for this lead.
- [`analysis/public-entry-evidence-refresh.md`](analysis/public-entry-evidence-refresh.md)
  — current pinned public catalog/source review, MIPS softmod incompatibility,
  and an unsent request for exact-body entry/provider evidence.
- [`analysis/v50-flash-layout-and-provider-limits.md`](analysis/v50-flash-layout-and-provider-limits.md)
  — retained external-flash geometry and allocation review; trailing firmware-like
  bytes are unallocated, and no authenticated earlier-stage provider was recovered.
- [`analysis/v50-package-isp-comparison.md`](analysis/v50-package-isp-comparison.md)
  — REA startup and descriptor review of the separate V50 ISP 6500.0021;
  matching USB template strengthens SDK comparison, without establishing X-half entry or payload compatibility.
- [`analysis/frm-connection-and-programming-callers.md`](analysis/frm-connection-and-programming-callers.md)
  — FRM application callers link ISP preparation to a programming-capable worker;
  F5/F6 keyboard bindings and a progress timer are identified. The bulk-driver
  INF supports `2aad:6500` interface 5, but supplies no verified X-half entry.
- [`analysis/early-boot-selectors-across-versions.md`](analysis/early-boot-selectors-across-versions.md)
  — reset guard and early-shell selector across four releases; 1.30 adds a
  saved-state filter, reproduced in 1,056 bounded controls. A further 46 controls
  connect it to factory reboot requests and the USB-presence getter; no new physical entry established.
- [`analysis/early-power-source-provenance.md`](analysis/early-power-source-provenance.md)
  — sampled register sources, overlapping power-status bits and a zero-word
  control path; 500 bounded controls clarify why earlier zero-word fixtures are not full startup.
- [`analysis/usb-normal-startup-observation.md`](analysis/usb-normal-startup-observation.md)
  — live registry recording of ordinary power-on; one normal USB arrival,
  no observed intermediate identity, and explicit timing/coverage limits.
- [`analysis/standard-storage-ids-live.md`](analysis/standard-storage-ids-live.md)
  — one explicit standard storage-ID read succeeds and closes its session;
  fixed client expansion and strict reply validation, without vendor operations.
- [`analysis/standard-storage-info-live.md`](analysis/standard-storage-info-live.md)
  — fixed known-ID StorageInfo succeeds after delayed macOS authorization;
  removable DCF media, 31.9 GB reported capacity, and explicit session close.
- [`analysis/resident-file-update-path.md`](analysis/resident-file-update-path.md)
  — actual file-wrapper/parser controls reach the resident update engine;
  packaged updater bytes remain data, and cold recovery is still separate.
- [`analysis/updater-image-layout.md`](analysis/updater-image-layout.md)
  — recovered updater vector/header prefix, corrected complete-image mapping,
  historical coordinate caveats and remaining earlier-stage recovery gap.
- [`analysis/early-usb-startup-dependencies.md`](analysis/early-usb-startup-dependencies.md)
  — loader registration versus deferred USB setup, early-shell initialization
  gap, and null-initializer controls; independent recovery remains unproved.
- [`analysis/startup-usb-flag-ordering.md`](analysis/startup-usb-flag-ordering.md)
  — same-event initialization/refresh ordering, state-zero event rejection,
  known synchronous queue filter and 80 offline startup/queue controls.
- [`analysis/mass-storage-exit-loader-selection.md`](analysis/mass-storage-exit-loader-selection.md)
  — unconditional mode-4 cleanup call, mass-storage ownership and 16 original-ARM
  controls distinguishing mode selection from a loader connection.
- [`analysis/loader-memory-read-boundary.md`](analysis/loader-memory-read-boundary.md)
  — confirmed loader memory-to-host scheduling, parser bounds/state hazards,
  selected PTP alternatives and the unresolved temporary-entry gate.
- [`analysis/factory-read-log-routing.md`](analysis/factory-read-log-routing.md)
  — distinct character-output errors and RAM profiling notices, log-call and
  enqueue controls, dump effects and unresolved retail retrieval.
- [`analysis/retail-ptp-file-acquisition.md`](analysis/retail-ptp-file-acquisition.md)
  — normal storage lists, media filtering and object-ID controls; no direct
  cold-archive download established through the inspected retail file path.
- [`analysis/cold-save-error-propagation.md`](analysis/cold-save-error-propagation.md)
  — original save/flush controls: archive write errors can be masked by logical
  success; cold saves force immediate mode and need independent persistence checks.
- [`analysis/cold-resource-backing.md`](analysis/cold-resource-backing.md)
  — packaged cold-data archive, RAM-backed logical files, first-use loader
  controls and the distinction between record reads and physical storage reads.
- [`analysis/factory-read-failure-controls.md`](analysis/factory-read-failure-controls.md)
  — original wrapper/callback failure controls: old or mixed checksum-valid
  data can arrive with success replies; repeated reads cannot prove freshness.
- [`analysis/factory-record-validation.md`](analysis/factory-record-validation.md)
  — original ARM checksum controls, restricted-reader integrity checks, and
  remaining freshness, identity and recovery limits before a live acquisition.
- [`analysis/lever-provia-findings.md`](analysis/lever-provia-findings.md) — film
  simulation shown on the secondary display during lever playback.
- [`analysis/auto-iso-findings.md`](analysis/auto-iso-findings.md) — Auto ISO
  preset limits and the 12,800-to-6,400 modification hypothesis.
- [`analysis/ram-loading-assessment.md`](analysis/ram-loading-assessment.md) —
  recovery and RAM-loading considerations.
- [`analysis/ram-boot-entrypoints.md`](analysis/ram-boot-entrypoints.md) —
  `sdboot` argument/error hazards, the `usbt` transition, and vendor USB-loader
  protocol leads toward RAM execution and independent recovery.
- [`analysis/recovery-entry-review.md`](analysis/recovery-entry-review.md) —
  early shell startup conditions, eSD firmware-slot header provenance, and why
  neither yet establishes independent SD/USB recovery.
- [`analysis/remaining-ui-loader-gates.md`](analysis/remaining-ui-loader-gates.md)
  — five additional UI gates, 1,280 checks and explicit emulator stopping boundaries.
- [`analysis/normal-usb-loader-entry.md`](analysis/normal-usb-loader-entry.md) —
  calibration-free UI loader path, saved USB-setting gate, caller census and
  offline ARM reproduction; temporary retail activation remains unresolved.
- [`analysis/temporary-usb-override.md`](analysis/temporary-usb-override.md) —
  offline RAM-staging/flag-refresh composition, cached-state controls, SD export
  distinction and the broader UI path's RTC-write barrier.
- [`analysis/alternate-usb-refresh.md`](analysis/alternate-usb-refresh.md) —
  full alternate event-handler execution with explicit display/readiness
  prerequisites and controls; real entry and connection transitions remain open.
- [`analysis/usb-loader-sequence-continuity.md`](analysis/usb-loader-sequence-continuity.md)
  — continuous post-refresh event test, charging/status boundary, and missing
  state transitions between the separately demonstrated loader-entry pieces.
- [`analysis/usb-charge-path-completion.md`](analysis/usb-charge-path-completion.md)
  — modeled completion of that charging branch, Power IC charger classification,
  and why it does not itself supply the missing loader transition.
- [`analysis/idle-usb-refresh.md`](analysis/idle-usb-refresh.md) — shorter refresh
  path from state 1, idle-timeout event producer, saved-state audit and remaining
  transition into the loader-selecting UI branch.
- [`analysis/start-main-loader-gate.md`](analysis/start-main-loader-gate.md) —
  StartMain selects the loader from state 1 without a group transition;
  conditional offline composition, runtime-bit controls and event-timing gap.
- [`analysis/boot-usb-latch-and-start-producers.md`](analysis/boot-usb-latch-and-start-producers.md)
  — boot-time USB latch source and controls, initial-setup event producers,
  and RAM diagnostic prefix audit.
- [`analysis/cold-buffer-lifetime.md`](analysis/cold-buffer-lifetime.md) —
  conditional shared-buffer reloads, failed-read save-attempt boundary,
  cold-data checksum behavior and remaining lifetime qualification.
- [`analysis/ptp-ui-state-and-exit.md`](analysis/ptp-ui-state-and-exit.md) —
  PTP-state refresh controls through the application dispatcher, USB-out
  boundary, teardown/remount order and camera-mode return decision.
- [`analysis/usb-return-mode-reentry.md`](analysis/usb-return-mode-reentry.md) —
  shooting USB-entry and playback gate controls: both use the cached flag;
  changing cold-data RAM alone does not change the tested branch decisions.
- [`analysis/usb-cache-refresh-reachability.md`](analysis/usb-cache-refresh-reachability.md)
  — DPC debug-shell refresh, reset-completion producer and RTC writes,
  original-code controls, and the known cache-writer reachability inventory.
- [`analysis/retail-ptp-command-boundaries.md`](analysis/retail-ptp-command-boundaries.md)
  — operation/callback registrations, property and reset-code limits, and
  command-arrival notification controls in the ordinary PTP UI state.
- [`analysis/retail-usb-control-routing.md`](analysis/retail-usb-control-routing.md)
  — retail class/vendor control routing, null vendor callback, class-reset
  effects, assertion hang hazard and original-code routing controls.
- [`analysis/vendor-usb-loader.md`](analysis/vendor-usb-loader.md) — traced
  iCatch host-library RAM copy/upload/run paths, active connection setup, and
  the remaining X-half receiver and loader-side-effect questions.
- [`analysis/xhalf-usb-ram-receiver.md`](analysis/xhalf-usb-ram-receiver.md) —
  matched X-half USB loader requests, iCatch identity switching, parameter
  processing, and the RAM copy/execute handoff.
- [`analysis/xhalf-loader-mode-entry.md`](analysis/xhalf-loader-mode-entry.md) —
  factory loader-mode caller, runtime USB resource selection, persistent
  factory settings to avoid, and an unresolved SD command-file reader.
- [`analysis/shell-access-and-usb-lifecycle.md`](analysis/shell-access-and-usb-lifecycle.md)
  — UART shell input, the API-test USB character bridge, shell-only UVC
  registration, and conditional filesystem remounts during USB transitions.
- [`analysis/service-boot-selection.md`](analysis/service-boot-selection.md) —
  application boot-mask construction, factory flags, RTC-backed reboot
  selection, and packaged USB resources with loader-ID protection.
- [`analysis/service-mode-button-candidate.md`](analysis/service-mode-button-candidate.md)
  — PLAY/MOVIE/flash startup candidate, input-to-event mapping, offline ARM
  emulation, and the remaining DisableTest and calibration-safety gates.
- [`analysis/disabletest-override-assessment.md`](analysis/disabletest-override-assessment.md)
  — existing SD marker override, persistent `clbt entest` alternative, and
  the post-entry safety question that remains before testing.
- [`analysis/calibration-entry-safety.md`](analysis/calibration-entry-safety.md)
  — automatic confirmation sequence, offline execution to a calibration
  worker, persistent factory-data operations, and remaining live-test barriers.
- [`analysis/service-entry-initialization-writes.md`](analysis/service-entry-initialization-writes.md)
  — initialization attempts a whole cold-data save even with automatic
  calibration off; RTC wakeup control and before/after-USB ordering.
- [`analysis/ptpap-integrated-read-emulation.md`](analysis/ptpap-integrated-read-emulation.md)
  — connected original-ARM registration/dispatch/file-wrapper/response path;
  eight synthetic controls, exact modeled/stubbed boundary, no physical camera access.
- [`analysis/ptpap-marker-activation-review.md`](analysis/ptpap-marker-activation-review.md)
  — marker semantics, eleven original-ARM lifecycle checks, non-immediate
  revocation, intended startup table clear and a draft activation/deactivation test.
- [`analysis/factory-chunk-availability-probe.md`](analysis/factory-chunk-availability-probe.md)
  — one live `1027(0)` returns unsupported in normal USB mode; successful close,
  delayed host-authorization attempt retained, no marker/load/factory record.
- [`analysis/ptp-cold-data-read-assessment.md`](analysis/ptp-cold-data-read-assessment.md)
  — ordinary-PTP candidate for reading factory flags, exact read/write direction,
  offline chunk-read reproduction, and the ignored file-read-status limitation.
- [`analysis/ptp-reader-and-session-audit.md`](analysis/ptp-reader-and-session-audit.md)
  — restricted protocol client and simulation, session/USB-out traces,
  registration lifetime, and requirements for a future live transport.
- [`analysis/usb-transport-qualification.md`](analysis/usb-transport-qualification.md)
  — bounded bulk adapter, offline notification checks, cached registry inspection,
  and the remaining native-library/device-ownership requirements.
- [`analysis/macos-descriptor-api.md`](analysis/macos-descriptor-api.md)
  — inspected macOS descriptor-cache path, metadata-only helper, offline checks,
  and the unresolved possibility of descriptor traffic on a cache miss.
- [`analysis/raw-support-assessment.md`](analysis/raw-support-assessment.md) —
  production RAW support versus engineering sensor dumps.
- [`analysis/rcaraw-handler.md`](analysis/rcaraw-handler.md) — internal `rcaraw`
  diagnostic command behavior.
- [`analysis/xhalf-usb-identity.md`](analysis/xhalf-usb-identity.md) — verified
  USB identity and normal-mode PTP interface.
- [`analysis/ptp-and-service-command-paths.md`](analysis/ptp-and-service-command-paths.md)
  — separation of normal PTP, hidden factory PTP operations, and the UVC
  RCA/EUC shell-forwarding path.
- [`analysis/uvc-service-mode-activation.md`](analysis/uvc-service-mode-activation.md)
  — static trace from power-on calibration selection through `uvctestcmd`,
  `USBUVC.BIN`, and UVC service callback registration.
- [`analysis/memory-and-hardware-map.md`](analysis/memory-and-hardware-map.md) —
  initial SPCA6500 address-space, boot, RAM, MMIO, storage, and hardware map.
- [`analysis/hardware-and-boot-diagrams.md`](analysis/hardware-and-boot-diagrams.md)
  — visual hardware blocks, boot/update/recovery flow, and the distinct package,
  NAND, and CPU address spaces with confidence markings.
- [`analysis/update-package-and-linux.md`](analysis/update-package-and-linux.md)
  — `FPUPDATE.DAT` regions, Linux/OpenWrt inventory, and NAND partition layout.
- [`analysis/brick-risk-and-recovery.md`](analysis/brick-risk-and-recovery.md)
  — updater safeguards, failure modes, current SD-recovery status, and safety
  gates required before any modified-firmware flash.
- [`analysis/firmware-copy-and-fallback.md`](analysis/firmware-copy-and-fallback.md)
  — observed Firmware1 transaction behavior, preservation of Firmware2, and
  the still-unproven cold-boot fallback selector.
- [`analysis/dump-and-boot-log-acquisition-plan.md`](analysis/dump-and-boot-log-acquisition-plan.md)
  — ranked, read-only routes for acquiring UART boot logs, RAM/boot-ROM state,
  and internal flash, with safety gates for each route.
- [`analysis/official-firmware-versions.md`](analysis/official-firmware-versions.md)
  — official download provenance, hashes, and recovered historical X-half
  firmware URLs; binary artifacts remain ignored.
- [`analysis/firmware-1.30-vs-2.00.md`](analysis/firmware-1.30-vs-2.00.md)
  — verified 1.01/1.20/1.30/2.00 package comparison and version-specific traces of
  the lever-review film-simulation regression and Auto ISO mapping.
- [`analysis/spca6500-package-and-recovery-comparison.md`](analysis/spca6500-package-and-recovery-comparison.md)
  — matched SPHOST container slots, decoded HDR2 resource disks,
  Firmware1/Firmware2 write flow, and the bounded USB-recovery lead.
- [`analysis/fujimod-new-camera-onboarding.md`](analysis/fujimod-new-camera-onboarding.md)
  — FujiMod's public bring-up resources, current X half support gaps, and a
  beginner-safe research path for a new processor family.

## Artifact identity

The reports identify analyzed artifacts by SHA-256 where available. The binary
artifacts themselves must be supplied separately and must not be committed.

Known identities:

- `FPUPDATE.DAT`:
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`
- `firmware-main.bin`:
  `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
- complete updater, `analysis/output/card-updater-full.bin`:
  `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`
- analysis ELF wrapper:
  `7e5534e40adc45c14ca19b6d1ebd0ea730ccf1d13125eedcc35d802434133755`

## Ghidra import

Use Ghidra 12.1.4 and force the raw image to the ARMv7 language. The synthetic
ELF is useful for address-aware command-line disassembly, but Ghidra's automatic
ELF selection currently chooses `ARM:LE:32:v8` and misdecodes some privileged
ARMv7 startup instructions.

The launcher currently defaults to a Ghidra installation under
`~/Downloads/ghidra_12.1.4_PUBLIC` and a specific macOS Java installation.
Inspect [run-ghidra.sh](analysis/run-ghidra.sh) when moving to another machine:
`GHIDRA_INSTALL_DIR` is configurable, but the Java executable path is hardcoded.
Create a new project name for a fresh main-image import; do not overwrite an
existing analysis project just to reproduce this example.

```sh
mkdir -p analysis/project
GHIDRA_PROJECT_NAME=FujiMainHandoff bash analysis/run-ghidra.sh \
  -import firmware-main.bin \
  -processor ARM:LE:32:v7 -cspec default \
  -loader BinaryLoader -loader-baseAddr 0x40000000 \
  -loader-blockName firmware \
  -preScript SeedFirmware.java
```

`GHIDRA_INSTALL_DIR`, `GHIDRA_PROJECT_DIR`, and `GHIDRA_PROJECT_NAME` can
override the launcher defaults. Local project files and tool state stay ignored
by Git.

Previously used working projects include `/private/tmp/fuji-ghidra-v7/FujiV7`
for the main image and `/private/tmp/fuji-updater-full/FujiUpdaterFull` for the
complete updater. Reuse them only if present and matched to the expected bytes.
For example, with the existing main project:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceRamCopyBoundary.java /tmp/ram-copy-boundary.txt \
  "$PWD/analysis/output/card-updater-full.bin"
```

The complete updater needs its own raw ARMv7 import/project. Do not apply
`SeedFirmware.java` to it: that script seeds main-image addresses. Individual
reports provide the appropriate script arguments, fixtures and expected hashes.
Inspect both script output and Ghidra logs: a headless process can exit zero
while reporting a script error.

## Safety boundary

Continue offline by default. No verified modified-firmware installation or
independent recovery procedure has been established. The user excludes camera
disassembly, UART wiring and repair-lab acquisition. Do not flash, upload/run a
payload, enter calibration, stage factory settings or try guessed USB commands
as a routine verification step. Calibration initialization has a traced
whole-buffer factory-save attempt even when automatic calibration is off.
RAM-loaded updater code can still write flash later.

Previously measured ordinary USB identity is `04cb:030a`; loader identity
`2aad:6500` is statically expected, not a confirmed live enumeration. USB port
locations can change. Do not assume a current connection or identify a device
only by its port. Live acquisition needs its own qualified scope and transport;
even a read-looking connection/descriptor operation can have additional effects.
Use the [identity note](analysis/xhalf-usb-identity.md), transport reports and
owning handler audit before proposing one.

Keep original/historical firmware and device-specific captures out of git;
selected small reports and reproducible evidence belong here. Preserve the
distinction between observed bytes, inferred behavior and unresolved questions.
Update this overview when priorities or conclusions change, and add corrections
to older reports when later work changes their interpretation.
