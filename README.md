# Fujifilm X half firmware research

Static-analysis workspace for understanding the Fujifilm X half firmware,
documenting behavior, and evaluating low-risk modifications. Original firmware,
extracted binaries, camera image dumps, Ghidra databases, and machine-local tool
state are intentionally excluded from version control.

## Current research notes

- [`analysis/lever-provia-findings.md`](analysis/lever-provia-findings.md) — film
  simulation shown on the secondary display during lever playback.
- [`analysis/auto-iso-findings.md`](analysis/auto-iso-findings.md) — Auto ISO
  preset limits and the 12,800-to-6,400 modification hypothesis.
- [`analysis/ram-loading-assessment.md`](analysis/ram-loading-assessment.md) —
  recovery and RAM-loading considerations.
- [`analysis/ram-boot-entrypoints.md`](analysis/ram-boot-entrypoints.md) —
  `sdboot` argument/error hazards, the `usbt` transition, and vendor USB-loader
  protocol leads toward RAM execution and independent recovery.
- [`analysis/vendor-usb-loader.md`](analysis/vendor-usb-loader.md) — traced
  iCatch host-library RAM copy/upload/run paths, active connection setup, and
  the remaining X-half receiver and loader-side-effect questions.
- [`analysis/xhalf-usb-ram-receiver.md`](analysis/xhalf-usb-ram-receiver.md) —
  matched X-half USB loader requests, iCatch identity switching, parameter
  processing, and the RAM copy/execute handoff.
- [`analysis/xhalf-loader-mode-entry.md`](analysis/xhalf-loader-mode-entry.md) —
  factory loader-mode caller, runtime USB resource selection, persistent
  factory settings to avoid, and an unresolved SD command-file reader.
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
