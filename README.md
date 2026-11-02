# Fujifilm X half firmware research

Static-analysis workspace for understanding the Fujifilm X half firmware,
documenting behavior, and evaluating low-risk modifications. Original firmware,
extracted binaries, camera image dumps, Ghidra databases, and machine-local tool
state are intentionally excluded from version control.

## Current research notes

- [`analysis/updater-boot-register-consumer.md`](analysis/updater-boot-register-consumer.md)
  — packaged-updater boot-register/pathname consumer, eight original-ARM
  boundary controls and the distinction between BootInfo diagnostics and fallback.
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
- analysis ELF wrapper:
  `7e5534e40adc45c14ca19b6d1ebd0ea730ccf1d13125eedcc35d802434133755`

## Ghidra import

Use Ghidra 12.1.4 and force the raw image to the ARMv7 language. The synthetic
ELF is useful for address-aware command-line disassembly, but Ghidra's automatic
ELF selection currently chooses `ARM:LE:32:v8` and misdecodes some privileged
ARMv7 startup instructions.

```sh
bash analysis/run-ghidra.sh \
  -import firmware-main.bin \
  -processor ARM:LE:32:v7 -cspec default \
  -loader BinaryLoader -loader-baseAddr 0x40000000 \
  -loader-blockName firmware -overwrite \
  -preScript SeedFirmware.java
```

`GHIDRA_INSTALL_DIR`, `GHIDRA_PROJECT_DIR`, and `GHIDRA_PROJECT_NAME` can
override the launcher defaults. Local project files and tool state stay ignored
by Git.

## Safety boundary

Static-analysis findings are not proof of runtime behavior. Do not flash a
modified firmware or send undocumented camera commands without an independently
validated integrity/recovery procedure. Live USB observations should begin with
passive enumeration and standard read-only protocol requests.
