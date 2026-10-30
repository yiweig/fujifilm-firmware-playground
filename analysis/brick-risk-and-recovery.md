# X half firmware-update brick risk and recovery assessment

This assessment is based on static inspection of the official X half 2.00
`FPUPDATE.DAT`, its embedded card updater, and the public FujiMod recovery tree
at commit `974e95dafab3b7a281e7453aad8ff029f7df2a58` (2026-10-30). No camera
commands were sent and no update was attempted.

## Bottom line

Flashing a modified package is **not an acceptable first experiment yet**. The
official updater contains meaningful integrity, read-back, retry, and backup
machinery, but no independent recovery route has been demonstrated for the X
half. A bad package could reach components needed before the normal firmware or
Linux environment starts, including firmware copies, resource data, `rawC`,
DRAM parameters, and NAND reserved-area metadata.

The safest order is:

1. establish a truly volatile RAM-only execution path;
2. establish a cold SD/boot-ROM recovery path for this exact body;
3. acquire and verify a complete device-specific flash backup; and only then
4. consider a minimal installable patch.

Until at least an independent cold-boot recovery path is proven, assume a failed
flash may require board-level access or manufacturer service.

## Safeguards observed in the official updater

The package starts with a 665,280-byte ARMv7 card updater (package offset
`0x540`; SHA-256
`e65576cadbd4db737e0dd2cfcff7395265ed4087fced6d267108f1af2718df51`).
REA/Ghidra string evidence shows that it has paths for:

- rejecting package checksum, size, and storage-type mismatches;
- selecting whether to update or retain Firmware1 and Firmware2;
- read-back verification of firmware, resource files, `rawC`, and DRAM
  parameters;
- retrying the card ISP operation; and
- saving, verifying, recovering, and detecting missing backup files.

It also contains these messages:

```text
damage FW1 checksum1 %04x -> %04x
restore FW1 checksum1 %04x
keep damaged FW1 checksum
```

The likely interpretation is a transaction marker: invalidate Firmware1 before
programming and restore its checksum only after a successful update. That would
reduce the chance of silently booting a partially written Firmware1 image. This
is an inference from string context, not yet a proven control-flow trace.

These mechanisms reduce corruption risk; they do not make modified firmware
safe. In particular:

- The presence of Firmware1 and Firmware2 does **not** establish automatic
  fallback. Boot selection and failover behavior remain unknown.
- Backup/recovery messages do not establish what is backed up, where it is
  stored, or whether recovery runs after loss of power.
- Read-back verification detects a bad write only after writing has begun.
- The updater can update early-boot data such as DRAM parameters and `rawC`.
  Damage there may prevent higher-level recovery code from starting.
- The package's outer 1,280-byte trailer and the large `SUNP BURN HDR 2`
  region remain undecoded. A reliable modified-package integrity/repacking
  procedure does not yet exist.

The portable evidence is
[`output/rea-card-updater-safety-evidence.json`](output/rea-card-updater-safety-evidence.json).
Addresses in that bundle are offsets in the extracted card updater, not runtime
addresses in the main firmware.

## Current status of SD recovery

Current public FujiMod includes a well-specified SD recovery-card system. For
supported processor families it uses boot-ROM mechanisms to load an OS image
into RAM, and it is intended both for dual boot and recovery after a bad flash.
Its documentation also explicitly excludes booting with zeroed or scrambled
internal storage, so even supported recovery is not a universal cure for every
kind of flash damage.

The current generated recovery catalog contains no `xhalf-200` recipe. The
firmware catalog knows the official X half 2.00 hash, but catalog generation
reports that the X half input is missing, and the recovery framework lists EXR,
EXR II/X-Processor Pro, X-Processor 4, and X-Processor 5 packaging—not the
X half's SPCA6500/Sunplus package. Therefore FujiMod demonstrates that SD
recovery is possible on other Fuji bodies, not that it currently works on this
one.

The relevant pinned public sources are:

- [FujiMod recovery design](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/crates/recovery/README.md)
- [generated recovery recipes](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/catalog/recovery/recipes.generated.json)
- [firmware catalog containing `xhalf-200`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/catalog/firmware-versions.generated.json)
- [catalog-generation status](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/catalog/generation.generated.json)

## Failure modes

| Failure point | Likely result | Present confidence |
|---|---|---|
| Modified package fails an outer check before writes | Update rejected; camera probably remains usable | medium |
| Power loss or media/read error after writes start | Firmware1 may remain deliberately invalid or another region may be partial | medium |
| Validly repacked but buggy main application | Camera may fail normal boot while early updater/recovery could survive | low; recovery entry unproven |
| Damage to DRAM parameters, `rawC`, reserved headers, or early loader data | Failure before normal recovery UI; possible service/board-level recovery | medium |
| Incorrect handling of device-specific settings/calibration | Camera-specific data loss even if generic firmware is restorable | low-to-medium; backup scope unknown |

The key distinction is between a **soft brick**—normal firmware is broken but
an independent updater still starts—and a **hard recovery failure** where the
code or parameters required to reach that updater are also damaged. We do not
yet know where the X half's firmware-update entry point resides relative to the
regions the updater can overwrite.

## Required safety gates before flashing

Treat each item as a gate, not merely a convenience:

1. **RAM-only proof.** Identify the exact X half service path, upload location,
   cache synchronization, activation, and cleanup. Confirm that entering the
   mode and running the test do not write flash, settings, or calibration.
2. **Cold recovery proof.** Starting from power removed and reinserted, boot a
   harmless diagnostic or stock image from SD without relying on the normal
   installed firmware. A green-LED-at-entry diagnostic is preferable because
   it proves boot-ROM loading before the main OS starts.
3. **Device-specific backup.** Dump all NAND/reserved regions, Firmware1,
   Firmware2, `rawC`, DRAM parameters, bad-block/remapping metadata, settings,
   and calibration. Read them twice, compare them, and keep hashes and an
   offline copy. A stock update file is not a substitute for per-camera data.
4. **Recovery rehearsal.** Prove that the recovery card boots the stock image
   and can access the exact backup/restore path. Do not test recovery by first
   corrupting internal flash.
5. **Package proof.** Decode every integrity field touched by a modification
   and demonstrate a byte-for-byte no-op unpack/repack of the official update.
   Keep the official updater, headers, Linux image, resources, `rawC`, and DRAM
   parameters unchanged unless a specific change requires otherwise.
6. **Minimal patch.** Patch only the necessary bytes in the main application,
   with expected-old-byte checks and a reproducible diff. Test the same logic
   via RAM first.
7. **Update conditions.** Use a verified official base hash, a freshly tested
   SD card, a fully charged healthy battery plus the manufacturer-supported
   stable power arrangement, and never interrupt an update. These precautions
   reduce ordinary write failure but do not compensate for a bad package.

## Best next investigation

The highest-value next step is not another application feature trace. It is to
map the immutable-to-mutable boot chain:

```text
mask boot ROM -> SD/update loader -> DRAM initialization -> card updater
              -> Firmware1/Firmware2 selection -> main application/Linux
```

For each arrow we need its storage location, validation rule, overwrite scope,
and fallback behavior. In parallel, an X half recovery recipe needs evidence
for the boot-ROM SD filenames/format and an entry marker that can be tested
without writing internal storage.
