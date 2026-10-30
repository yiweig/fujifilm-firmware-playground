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
- [`analysis/update-package-and-linux.md`](analysis/update-package-and-linux.md)
  — `FPUPDATE.DAT` regions, Linux/OpenWrt inventory, and NAND partition layout.

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

## Safety boundary

Static-analysis findings are not proof of runtime behavior. Do not flash a
modified firmware or send undocumented camera commands without an independently
validated integrity/recovery procedure. Live USB observations should begin with
passive enumeration and standard read-only protocol requests.
