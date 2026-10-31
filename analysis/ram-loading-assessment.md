# X half RAM-loading assessment

Inspected public FujiMod source at commit `9e8370bc1ee2aaf64cc87da9f4aa0597db88dc0e`. Research only: no camera connection, USB requests, firmware installation, or execution of downloaded tools.

The Discord advice is a useful direction for reducing risk: load experimental code into volatile RAM instead of replacing the installed firmware. It does not establish an independent recovery path after flash corruption, and it does not by itself establish that the executed code or its setup avoids persistent writes.

## What the public source establishes

- `crates/usb/PROTOCOL.md` describes the ff80 service/debug protocol, including explicit CPU-memory reads and writes. It explicitly requires firmware-specific definitions; command availability and semantics are not universal.
- `crates/runtime-install/src/lib.rs` implements allocation, relocation/relinking, upload and readback comparison, cache synchronization, protection, activation, and rollback interfaces for runtime images. The caller supplies body-specific operations and addresses. This is evidence of runtime-module loading infrastructure, not proof of a complete X half firmware RAM-boot procedure.
- `crates/usb/src/operations/callback.rs` requires explicit callback slots, accepted original bytes, synchronization steps and trigger commands. Addresses cannot safely be copied from another model.
- The recursive public repository tree contains `catalog/bodies/xhalf/controls.json`, a physical-controls description sourced from the manual. It contains no `catalog/bodies/xhalf/body.json` or `catalog/firmware/xhalf/` profile. A ready-made X half loading profile was not found in this snapshot. This does not rule out private or unpublished work.
- `crates/workflows/src/debug_mode.rs` is explicitly a persistent debug-access workflow. Its existence demonstrates why entering a tool's "debug mode" must not automatically be treated as a volatile operation. It does not prove that this workflow is necessary for the X half.

## Risk distinction

If both setup and experimental code leave persistent firmware/configuration untouched, a restart should discard the RAM modification and resume stock firmware. That behavior still needs verification for this body. Running code in RAM does not prevent it from invoking flash writers, changing saved settings, corrupting calibration, or controlling hardware incorrectly.

RAM loading through the running stock firmware also depends on that firmware being operational. Success would not prove SD recovery or USB recovery from an already corrupted flash image.

## Next work before a device test

1. Establish the exact installed firmware version and match it to the analyzed image.
2. Locate and inspect the X half's USB service/debug command handlers in the firmware. Do not infer protocol compatibility from product branding or isolated strings.
3. Determine a temporary access path, including any side effects of entering and leaving that mode.
4. Establish RAM bounds, allocator ownership, callback invocation, instruction-cache synchronization, and concurrency requirements for this exact firmware.
5. Prepare a bounded first experiment with explicit expected bytes, readback, and cleanup. Begin with identity and validated reads, then a harmless RAM-only test before any UI patch. Do not overwrite the running main image with the extracted binary.
6. Audit the eventual patch and executed callees for persistent-write behavior. Review the Provia fix's metadata lifetime separately.

No hardware command sequence is recommended yet. No SD-boot support has been established.

## Follow-up: concrete application boot commands

The [RAM boot entry-point investigation](ram-boot-entrypoints.md) identifies
`sdboot` and `usbt` in the 2.00 diagnostic command table. The former discards
its advertised address argument and jumps even after a read error; the latter
changes USB and system mode but does not yet establish a loader protocol.
Neither command currently provides a verified test or independent recovery.
The vendor archive supplies distinct boot-method, upload, and flash-programming
operation declarations for the next host-DLL comparison.

Source links (pinned to inspected revision):

- [USB protocol](https://github.com/justjake/fujimod/blob/9e8370bc1ee2aaf64cc87da9f4aa0597db88dc0e/crates/usb/PROTOCOL.md)
- [Runtime installation](https://github.com/justjake/fujimod/blob/9e8370bc1ee2aaf64cc87da9f4aa0597db88dc0e/crates/runtime-install/src/lib.rs)
- [Callback invocation](https://github.com/justjake/fujimod/blob/9e8370bc1ee2aaf64cc87da9f4aa0597db88dc0e/crates/usb/src/operations/callback.rs)
- [Persistent debug-access workflow](https://github.com/justjake/fujimod/blob/9e8370bc1ee2aaf64cc87da9f4aa0597db88dc0e/crates/workflows/src/debug_mode.rs)
- [X half controls](https://github.com/justjake/fujimod/blob/9e8370bc1ee2aaf64cc87da9f4aa0597db88dc0e/catalog/bodies/xhalf/controls.json)
