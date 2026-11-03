# X half firmware-update brick risk and recovery assessment

This assessment is based on static inspection of the official X half 2.00
`FPUPDATE.DAT`, its embedded card updater, and the public FujiMod recovery tree
at commit `974e95dafab3b7a281e7453aad8ff029f7df2a58` (2026-10-30). No camera
commands were sent and no update was attempted.

For a consolidated explanation and current research priorities, read the
[architecture guide](architecture-guide.md) and
[RAM-launch/recovery roadmap](ram-launch-and-recovery-roadmap.md). The roadmap
separates temporary execution from independent cold recovery and ranks the
remaining blockers; the detailed risk findings below retain their original scope.

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

Follow-up on volatile experiments: [shared cold-data lifetime](cold-buffer-lifetime.md)
shows why changing a shared settings buffer in RAM is not yet a qualified
RAM-only procedure. Conditional reloads and later whole-buffer save attempts
must be accounted for separately from the initial staging operation.

Follow-up on USB experiments: [retail control routing](retail-usb-control-routing.md)
finds that unknown class requests reaching the registered SIDC handler call an
assertion routine with a terminal self-loop after MMIO/diagnostic work. This
can hang the running software; it is not evidence of a permanent brick or a
tested recovery method. Arbitrary control-request scans are therefore not a
qualified low-risk alternative to flashing. All associated checks were offline.

Follow-up on service entry: [initialization writes](service-entry-initialization-writes.md)
finds that calibration initialization attempts to save all of `COLDDATA.BIN`
for version bookkeeping even with automatic calibration disabled. This is
not proof of harmful calibration changes or a firmware flash, but it rules
out treating that setting alone as making the button-entry route read-only.

Follow-up on the packaged updater: [file-failure continuation](updater-file-failure-continuation.md)
identifies conditional return paths from failed file actions toward its USB
startup. The helper previously labeled shutdown has a return path, but its
hardware effects and live USB availability remain unverified. This is evidence
inside an executing updater; it does not establish entry when the main firmware
cannot boot or change the recommendation against modified flashing.

The [subsequent resource trace](updater-usb-resource-bringup.md) identifies
that helper as reference-counted USB-resource acquisition with PHY/PLL bring-up,
supported by eight original-ARM controls and main-image USB code. Actual
controller availability and independent recovery entry remain unverified.

The [command-task identification](updater-command-task.md) also corrects the
earlier "ISP task" label: that task is the `Cmd` interpreter, whose creation
does not itself request flashing. The earlier automatic card-file update path,
registered write commands and incompletely audited storage-worker effects still
prevent treating stock-updater RAM execution as proven write-free.

The [storage activation follow-up](updater-mount-write-risk.md) identifies a
concrete additional risk: a NAND table-construction branch can attempt block
erase after a specific spare-read error, without an explicit update command.
The startup type-4 worker message avoids that work, but other activation paths
can reach it. Eight offline controls stop before initialization or erase;
the branch has not been observed on the camera.

The [startup routing follow-up](updater-startup-storage-routing.md) separates
file-drive activation (logical 2/5, shared SD mount routine) from NAND (logical
1). Earlier conditional startup branches can register NAND and immediately
initialize it, including a possible firmware-selection callback. Avoiding the
worker's mount request alone does not bypass these earlier actions. Eight
additional offline controls verify mapping and stop before backend execution.

The [low NAND initializer audit](updater-nand-initialization-risk.md) identifies
a path through the NAND header parser to the same erase-capable table builder
for a nonempty reserved region, before a mount request. Five original-parser
controls reach or avoid that boundary using supplied headers; table construction
never executes. This connects the risk to conditional startup registration,
without showing that the camera's actual stored metadata enables the branch.

The later [USB service constructor](updater-usb-storage-separation.md) has no
storage-readiness check in its inspected caller/constructor instructions.
Four controls reach controller bring-up with empty or populated registry state,
using modeled services and no scheduled tasks. This supports separating USB
from storage in a future payload; it is not a validated entry or safe runtime.
The stock updater's special +0x200 entry sets the startup gate nonzero and
must not be mistaken for a skip-storage mode.

The [USB reset/callback audit](updater-usb-reset-and-callback-state.md) refines
that boundary to `usbDclBusReset`, which queues work without waiting for it.
The event registrar keeps occupied callback slots; normal startup clears the
table's RAM, so calling USB setup directly would need qualified initialization
and memory ownership. Five offline controls verify those branches without
executing callbacks, interrupts or tasks. Physical USB readiness remains unknown.

The [reset acknowledgement audit](updater-usb-reset-exchange.md) completes
the original target-0 reset/acknowledgement handlers with serial message
delivery. Four controls show the request gate releasing without a NAND/SD
call in that path; they model queues and allocation and do not run task loops.
It also repairs earlier USB queue decoding and reruns those five controls.
This narrows the asynchronous dependency without validating physical USB or entry.

The [startup PHY/gate controls](updater-startup-phy-gate.md) show that gate zero
also bypasses the identified USB PHY/resource acquisition inside the consumer.
Four controls compare that path with the modeled file-error return path, which
executes the real acquisition instructions against synthetic memory. Inherited
hardware state remains unknown; storage bypass is not a sufficient USB launch
procedure. A bounded writer search found only the known PHY sequence, without
proving other writers absent.

The [retail handoff audit](main-handoff-usb-state.md) narrows the upstream
state question: the inspected early preparation acquires resource 17 and
preserves supplied USB PHY/count state, while a separate later helper changes
controller byte `0x10005104`. Four controls cover those two slices only.
An earlier optional callback, IRQ/GIC changes, the copy/execute transition and
unresolved low helper prevent inferring preserved physical USB or a safe return.

The [optional handoff callback investigation](main-handoff-optional-callback.md)
identifies a calibration-path producer through USB API selector `0x600`.
Its callback performs backlight-related, lens-close and idle preparation through
deeper helpers whose complete storage/USB effects are unaudited. Five controls
verify registration, optional dispatch/skip and modeled call order; ordinary
retail callback presence remains unknown. These are not live calibration tests.

The [callback lifetime audit](main-loader-callback-lifetime.md) finds intended
full-startup clearing through the stage-23 RAM-fill request, while the tested
loader registration/reset initializer preserves supplied callback values.
Four controls cover empty auxiliary cleanup state only. This separates full
boot from USB reinitialization; it does not measure the callback or qualify
active cleanup branches and deeper mode-transition/storage effects.

## Safeguards observed in the official updater

The complete ARMv7 card updater is 666,112 bytes at package offset `0x200`
(SHA-256 `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`).
Its reset body starts at package `0x540`. The older 665,280-byte extraction
omitted the vector/header prefix; see the [layout correction](updater-image-layout.md)
before using historical small-updater addresses.
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

Control flow now proves this is a transaction marker in the X half's ordinary
UI update path: the UI enables checksum invalidation, the updater changes
Firmware1's stored checksum before programming, restores it only on success,
and deliberately leaves it invalid on failure. The same official path keeps
Firmware2 unchanged. What remains inferred is the next boot stage's response
to invalid Firmware1.

These mechanisms reduce corruption risk; they do not make modified firmware
safe. In particular:

- Firmware2 is preserved while Firmware1 is transactionally updated, which is
  strong evidence of fallback intent. Automatic cold-boot selection of
  Firmware2 is still unproved because that earlier selector is absent.
- Backup/recovery messages do not establish what is backed up, where it is
  stored, or whether recovery runs after loss of power.
- Read-back verification detects a bad write only after writing has begun.
- The updater can update early-boot data such as DRAM parameters and `rawC`.
  Damage there may prevent higher-level recovery code from starting.
- The former `SUNP BURN HDR 2` unknown is now bounded as a 0x120-byte header
  followed by 120 MiB and 10 MiB FAT16 A/B resource images. The outer
  1,280-byte trailer remains semantically unresolved, but it lies outside the
  range checked by the traced updater and no traced update path validates it.
  The header checksum at `0x1fc` is a zero-sum of little-endian 32-bit words
  through the declared package end and is actively enforced by the embedded
  card updater. That alone is not a reliable modified-package procedure.

The portable evidence is
[`output/rea-card-updater-safety-evidence.json`](output/rea-card-updater-safety-evidence.json).
Addresses in that bundle are offsets in the extracted card updater, not runtime
addresses in the main firmware.

The control-flow update and corrected linked address base are documented in
[`firmware-copy-and-fallback.md`](firmware-copy-and-fallback.md), with additional
REA evidence in
[`output/rea-card-updater-linked-base-evidence.json`](output/rea-card-updater-linked-base-evidence.json).

## Package-integrity path now traced

The X half has three relevant preflight stages:

1. The camera application at `0x405ae02c` checks the container magic, seeks to
   the main-firmware slot, and requires the `FFSD_BRN:` marker and project ID
   `KC36D10`. Its phrase `signature offset` refers to this firmware marker, not
   an RSA-style signature.
2. Its nominal whole-file checksum function at `0x405ae61c` is a no-op in this
   build: it reads the file, prints a zero sum, and returns success.
3. The main-image updater library at `0x405efddc` independently adds all 32-bit words
   through the header's declared end and aborts unless the modulo-2^32 result is
   zero. This is the real package integrity gate before its write machinery.

The official file has a 0x500-byte trailer beyond that declared end. It has a
signature-like 256-byte block and metadata, but no traced X half update routine
consumes it. Static searches found no standard SHA-256/RSA verification
artifacts tied to it. This is negative evidence, not proof that cryptographic
validation is impossible elsewhere.

Comparison of official 1.20, 1.30, and 2.00 packages shows that all 256 bytes
of the signature-like block vary by release, while the following 1,024-byte
metadata suffix is identical except for two copies of the declared package
length. That makes the trailer's layout much less likely to be incidental, but
still does not reveal a verifier in the traced installation path.

Official firmware 1.01 ends exactly at its declared, checksummed package end
and has no `0x500`-byte trailer. This proves the trailer was not an inherent
requirement of the original X-half update format. It does not establish whether
newer installed firmware added an otherwise-unseen policy requiring trailers
on incoming updates.

Practical consequence: a main-application patch appears technically
repackable by changing only the intended bytes and recomputing the word at
`0x1fc`; there is no demonstrated cryptographic signing barrier in the traced
path. The dominant risk is therefore no longer “we cannot make a package the
camera will accept.” It is “the camera may accept our package and write a bad
image without a proven independent recovery route.” That is the more dangerous
failure mode and does not change the no-flash recommendation.

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

## SPCA6500 USB recovery lead

A public V50/SPCA6500 reverse-engineering repository uses the same seven-slot
`SUNP BURN FILE` format and includes iCatch FRM tools and drivers. Its files
distinguish mask boot, ISP boot, and firmware boot, and its driver recognizes
SPCA6500 bulk/null USB interfaces. A reported shutter+USB+power sequence reaches
that mode on some cameras, but the repository explicitly says it fails on an
Akaso V50 Pro. The mechanism is therefore chipset-family evidence, not an X
half procedure and not proof that the path survives damaged flash.

This improves recovery status from “no concrete lead” to **plausible but
unproven**. It does not clear the cold-recovery safety gate. Details and pinned
sources are in
[`spca6500-package-and-recovery-comparison.md`](spca6500-package-and-recovery-comparison.md).

## Failure modes

| Failure point | Likely result | Present confidence |
|---|---|---|
| Modified package fails an outer check before writes | Update rejected; camera probably remains usable | medium |
| Power loss or media/read error after writes start | Firmware1 remains deliberately invalid; Firmware2 is preserved, but automatic selection is unproved and another region may be partial | medium |
| Validly repacked but buggy main application | Camera may fail normal boot while early updater/recovery could survive | low; recovery entry unproven |
| Damage to DRAM parameters, `rawC`, reserved headers, or early loader data | Failure before normal recovery UI; possible service/board-level recovery | medium |
| Incorrect handling of device-specific settings/calibration | Camera-specific data loss even if generic firmware is restorable | low-to-medium; backup scope unknown |

The key distinction is between a **soft brick**—normal firmware is broken but
an independent updater still starts—and a **hard recovery failure** where the
code or parameters required to reach that updater are also damaged. We do not
yet know where the X half's firmware-update entry point resides relative to the
regions the updater can overwrite.

The [optional handoff idle transition](main-idle-mode-handoff.md) also depends
on sensor configuration, asynchronous mode exit/entry and conditional DRAM-clock
changes. Five offline controls verify only guards and stop boundaries. No new
persistent write is established by that pass, but the full callback remains
unqualified for a write-free RAM launch or a guaranteed return.

The [sensor/exit follow-up](main-sensor-and-mode-exits.md) identifies that
sensor setting as a power-save timeout with a conditional mode request. Candidate
exits can enter shared cleanup or clock-related helpers. Fifteen offline controls
stop at these boundaries; no live write-free guarantee follows from them.

The [playback cleanup audit](playback-cleanup-before-handoff.md) finds that
five-task cleanup can return zero even when a modeled task deletion failed; the
outer cleanup continues with shared resource release. Sixteen offline controls
verify caller behavior, not actual shutdown. Runtime termination hooks and kernel
effects remain unaudited, so a zero cleanup return cannot establish quiescence.

The [termination-hook follow-up](termination-synchronization-hooks.md) identifies
mutex release and queue/event wait cleanup. A supplied contended mutex transfers
ownership and reaches a task-resume helper; ten offline controls stop before
scheduling. Task cleanup therefore does not establish global quiescence or what
other tasks might do after their locks become available.

The [idle clock audit](idle-clock-switch-and-callbacks.md) adds a display-event
wait and assertion boundary before hardware clock work. Returned core results
restore event/semaphore state in modeled controls; they do not prove physical
clock rollback. The real parameter table, callbacks and lower clock core remain
unqualified for a temporary RAM launch.

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

Follow-up (2026-11-01): [service initialization](service-entry-initialization-writes.md)
attempts a factory-data save even with automatic calibration disabled.
[Archive backing](cold-resource-backing.md) and
[save-error controls](cold-save-error-propagation.md) show why a successful
logical read/write reply does not establish persistent backup or restore:
reads can use resident RAM, and archive write failures can be masked. An
independent persistence check and a separate recovery path remain necessary.
