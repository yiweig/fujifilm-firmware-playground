# FujiMod resources for understanding a new camera

Reviewed against public FujiMod commit
`974e95dafab3b7a281e7453aad8ff029f7df2a58` (2026-10-30). This note maps
FujiMod's current resources into a beginner-oriented path for the X half. No
camera operations were performed.

Follow-up: the [2026-11-03 public evidence refresh](public-entry-evidence-refresh.md)
checks FujiMod at `122b82ea54ef4980035e484ed7dfee39c4d2cc18`. The complete tree
still lacks an X-half body definition/firmware access profile or SPCA6500-named
record. This is a separate pinned check; the assessment below retains its date.

## Short answer

FujiMod has the pieces of a new-camera workflow, but not a single public,
beginner-level guide that takes an unsupported processor from update file to a
safe first experiment. Its `Dev Tools` getting-started page is for a camera
whose firmware-specific facts have already been located. It explicitly disables
operations when the catalog lacks a flash reader, boot-ROM location, or
generation-specific transport.

The best public roadmap is encoded in FujiMod's camera progress model:

1. **Research:** register the update, unpack its overlays, export its code
   corpus, build a function index, locate firmware facts, and acquire a boot-ROM
   dump.
2. **Emulator:** reach the shooting screen, exercise menus, then grade stock
   features.
3. **Camera:** verify USB identity, capture a RAM/device-state snapshot, and
   establish remote menu control.
4. **Mods:** locate a mod target, build checked patches, and test features.
5. **SD card:** build an SD-boot image and prove it boots on the camera.

That is a development-progress model, not permission to flash in that order.
For this project, proven RAM-only execution and cold SD recovery must precede
installing a modified firmware.

## What FujiMod provides

### Catalog architecture

[`catalog/README.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/catalog/README.md)
separates knowledge into four layers:

- an SoC definition: CPU, memory/block map, interrupts, clocks, and buses;
- a body definition: storage geometry, sensor, lens, controls, displays, and
  physical wiring;
- a firmware definition: hashes, decoding/install layout, and generated facts;
- run configurations: the exact body/firmware and inputs for an emulator run.

This is a useful way to organize our own notes even before the camera can be
added to FujiMod.

### Firmware parsing

[`crates/firmware/README.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/crates/firmware/README.md)
documents FujiMod's body-independent decoder for the established Fujifilm
type-4/6/8 update formats, compressed streams, overlays, and outer headers. It
does not currently describe the X half's `SUNP BURN FILE` package.

### Cross-firmware code corpus

[`crates/corpus/README.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/crates/corpus/README.md)
describes how FujiMod indexes ARM32/AArch64 functions, strings, constants,
references, and call relationships across firmware versions. A known function
in one camera can help name or locate its relatives in another. The X half's
main ARMv7 image is compatible with the ARM32 side in principle, but the public
repository supplies no X half mappings, function boundaries, or corpus input.

### Emulator

[`crates/emulator/README.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/crates/emulator/README.md)
shows the catalog-driven emulator and its strict hardware models. Its documented
end-to-end boot command currently supports X70 1.10 and requires an exact update
and boot-ROM dump. It is a model for later validation, not a ready X half
emulator.

### Safe camera acquisition workflow

[`apps/web/src/dev-tools/GETTING-STARTED.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/apps/web/src/dev-tools/GETTING-STARTED.md)
defines a promising workflow for supported bodies:

1. enter service mode and enable access only in volatile RAM;
2. read the whole internal flash without erase/program operations;
3. dump RAM, boot ROM, and other catalog-approved readable state;
4. optionally persist debug mode; and
5. capture states for comparison.

Downloads carry addresses, sizes, identities, and hashes. Constant regions are
read twice. The important limitation is that every operation requires qualified
firmware facts; the guide is not a discovery mechanism for an unsupported body.

[`crates/workflows/README.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/crates/workflows/README.md)
documents the guards behind those operations and distinguishes volatile service
preparation from persistent debug-mode writes.

### SD recovery

[`crates/recovery/README.md`](https://github.com/justjake/fujimod/blob/974e95dafab3b7a281e7453aad8ff029f7df2a58/crates/recovery/README.md)
documents recovery/dual-boot cards for supported Fuji processor families. The
X half has no generated recovery recipe and SPCA6500 is not one of the listed
processor packages.

## Exact public status of the X half

The public catalog currently knows:

- product `X half`, public firmware `2.00`, internal version `2.0006`;
- official update SHA-256
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`;
- controls transcribed from the owner's manual.

It does **not** contain:

- an SPCA6500 SoC record;
- `catalog/bodies/xhalf/body.json`;
- `catalog/firmware/xhalf/2.00/firmware.json`;
- a public X half unpacked input/corpus;
- qualified bulk, service-mode, flash-reader, settings, or safe-state facts;
- an emulator configuration; or
- an SD recovery/green-LED recipe.

The generated status calls the processor `unassessed`, says its update format is
unsupported, and reports no safely readable boot-ROM extent. It also suggests
`<input> unpack xhalf-200`, but no corresponding public `<input>` command or
new-camera tutorial appears in the repository. This likely names an omitted or
private input pipeline; it is not a command we can presently run from the
public checkout.

## A beginner-safe X half starting path

### Stage 1: build a static artifact map

Do not begin by trying to understand every function or by finding a single
universal `main`. Embedded boot is a chain of programs. Start with a table of:

- package regions and hashes;
- which region is code, filesystem, resources, or parameters;
- file offsets versus runtime addresses;
- architecture and endianness per executable;
- known entry points and the evidence for each; and
- unresolved headers and integrity fields.

Most of this workspace's `update-package-and-linux.md`,
`memory-and-hardware-map.md`, and `firmware-regions.json` already serves this
stage.

### Stage 2: map the boot and update chains

Follow narrow questions rather than reading disassembly linearly:

```text
mask ROM -> storage/SD loader -> DRAM setup -> card updater
         -> firmware selection -> main application -> Linux
```

For each transition, record the source region, destination address, validation
rule, fallback behavior, and whether the updater can overwrite it. This directly
supports both understanding and brick-risk reduction.

### Stage 3: create a small function notebook

Use Ghidra/REA for one behavior at a time. Each entry should contain:

- address and artifact hash;
- observed strings/constants/callers/callees;
- a neutral provisional name;
- what is observed versus inferred; and
- the next fact that would confirm or disprove the hypothesis.

Good first subsystems are the updater's validation/write sequence, boot-source
selection, the USB descriptor/dispatch path, and the secondary-display playback
path. Avoid trying to name thousands of functions.

### Stage 4: identify a comparable firmware family

FujiMod's corpus approach is strongest when a related firmware supplies names
and known behavior. Search for another SPCA6500/Sunplus camera update or an
earlier X half release before assuming conventional X-Processor addresses or
protocols transfer. Cross-version comparison can reveal stable functions and
changed constants far faster than isolated decompilation.

### Stage 5: design read-only acquisition, but do not execute it yet

Translate FujiMod's service-capture contract to the X half only after statically
locating:

- the exact service-mode boot chord and USB identity;
- a volatile read gate and reset guard, if they exist;
- stock memory/flash read handlers and their bounds;
- a safely readable RAM extent and boot-ROM extent; and
- cleanup that is guaranteed to be discarded by battery removal.

Until those facts are qualified, FujiMod's Dev Tools should be treated as
unsupported for this body. Never substitute another model's addresses or
command table.

### Stage 6: recovery and RAM execution

Develop a harmless cold SD-boot marker and a RAM-only execution proof before an
installable patch. The first hardware experiments should demonstrate identity,
bounded reads, volatile cleanup, and recovery—not feature changes.

## Questions for the FujiMod maintainers

The public material leaves several questions that are best answered directly:

1. Is the tool represented as `<input> unpack xhalf-200` public, planned for
   publication, or an internal pipeline?
2. Has anyone begun a `SUNP BURN FILE`/SPCA6500 decoder or corpus export?
3. Is there a known SPCA6500 service manual, SD-boot filename/format, boot chord,
   or green-LED entry test?
4. Is any X half service/debug transport already qualified privately, and which
   operations are strictly read-only?
5. Is there another supported or in-progress Sunplus camera that should be used
   as the comparison/reference firmware?
6. What artifacts would be most useful to contribute without exposing a serial
   number, calibration data, or other device-specific information?

Those answers determine whether we should integrate with FujiMod's input
pipeline or continue building an independent SPCA6500 artifact map first.
