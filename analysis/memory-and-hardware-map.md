# SPCA6500 memory and hardware map: first static baseline

This is a static, read-only map of the Fujifilm X half main firmware image. The
camera was unplugged throughout this investigation. It is intentionally a
baseline, not a completed datasheet: `observed` means the firmware names the
subsystem or source module; `derived` means a standard architectural pattern is
strong enough to identify it; `inferred` is a working hypothesis; and
`unknown` remains unlabelled.

## Artifact and platform identity

- `firmware-main.bin` SHA-256:
  `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
- Runtime import base: `0x40000000` (ARMv7, little-endian).
- Flat-image size: `0x00e38400` bytes, covering
  `0x40000000..0x40e383ff`.
- The bytes at `0x40000040` identify the platform as `SPCA6500 V000` and the
  image as `DRAM CODE`.
- Embedded paths identify the vendor stack as iCatch/SP5K and explicitly name
  `customization/arch/spca6500/dev_init.c`.

The ELF file used by REA and Ghidra is a synthetic wrapper around the flat
image. Its single section does **not** prove that all of this range is
executable, read-only, or one real hardware segment.

## Address-space overview

Follow-up: [low-address service interface](low-address-loader-helper.md)
identifies deliberately managed low-address access and several early-loader
call targets, including `0xb34`. Their backing memory and implementation are
unrecovered; these addresses are not a qualified ROM dump range.

Follow-up [updater boot-register analysis](updater-boot-register-consumer.md)
identifies uses of `0x10001300`, `0x10004054` and filename/configuration fields
`0x100040b4..0x100040c3` in the separately packaged updater. These are consumer
semantics, not verified hardware register names or physical button mappings.

| Address range | Present interpretation | Confidence |
|---|---|---|
| `0x10000000..0x1001efff` (sparse) | SPCA6500 SoC peripheral registers | observed |
| `0x10201000..0x1023cfff` (sparse) | ARM CoreSight debug/trace registers | derived/observed |
| `0x10401000..0x10402fff` | ARM GICv2 distributor and CPU interface | derived |
| `0x3f000000..0x3f00ffff` | on-chip SRAM used for page tables, exception state, copied routines/data, and per-core stacks | derived |
| `0x40000000..0x40e383ff` | extracted, initialized DRAM image: vectors, code, constants, and initialized data | observed |
| `0x40e38400..0x414fb77f` | unbacked runtime RAM below a confirmed memory/allocator ceiling; exact owners and contents remain unknown | derived/unknown |
| above that | heap, frame buffers, general DRAM, aliases, and true DRAM limit not yet established | unknown |

The packaged image ends at `0x40e38400`; its last non-zero byte is at
`0x40e38273`, followed by 396 bytes of zero padding. The primary startup path
passes the exact literal `0x414fb780` to the setter at `0x400017a0`, which stores
it at `0x40e4af04`. The later OS bootstrap at `0x40049db4` reads that global and
passes it to `0x4001f8dc` during memory initialization.

The routine at `0x400025b4` independently confirms the boundary's allocator
role. It initializes a cursor at `0x40e37c58` to `0x414fb780`, adds a signed
increment, rejects results above `0x414fb780`, and otherwise commits the new
cursor while returning its old value. With a negative increment it is a
downward-moving bump allocator starting at that ceiling. This establishes
`0x414fb780` as a runtime memory/allocator upper boundary, but not specifically
as a linker BSS end. The unbacked span below it is `0x006c3380` bytes (about
6.76 MiB); which portions are static storage, kernel memory, or another pool
remain unknown.

The earlier `0x414fb9fc` apparent access was rechecked and rejected. It came
from carrying a literal base through a loop after the register had been
overwritten, demonstrating why the static address scanner is only a candidate
generator.

## Boot and CPU initialization

The first seven words are ARM exception-vector branches. Reset branches from
`0x40000000` to `0x40000ec0`. That startup routine:

1. reads the ARM multiprocessor affinity register;
2. allocates per-core exception stacks below `0x3f010000`;
3. initializes cache/MMU state;
4. writes `0x40000000` to CP15 VBAR, making the vector table active;
5. uses `0x100002f0` as a secondary-core rendezvous/entry location; and
6. initializes the interrupt controller at `0x10401000/0x10402000`.

The word at image offset `0x60` is `0x4000148c`, a separate secondary-core
entry. The primary path calls the routine at `0x4000103c`, enters the broader
platform bootstrap at `0x400037e8`, and passes `0x40003498` onward as the main
initialization-task entry. That task brings up the OS/HAL layers and then the
camera subsystems.

The image therefore begins at a genuine runtime entry point, not merely at an
arbitrary function boundary. This is the application/DRAM-stage entry point,
however—not necessarily the first instruction executed after power-on.

Follow-up: [staged RAM initialization](staged-boot-ram-initialization.md)
identifies a table-driven hardware-fill worker at `0x4000f064`, launched by
the initialization task. It requests five zero-fill intervals spanning
`0x40e68880..0x414fb780`; the stage-28 interval includes the PTP callback.
Original-instruction controls validate interval/descriptor construction,
not actual DMA success. The worker does not enforce every hardware error
result. This resolves a substantial part of the initialization-path question,
but not complete BSS ownership. A further [loader-facing entry review](staged-loader-storage-handoff.md)
identifies a CPU clear of `0x40e37c00..0x40e68880` after the SRAM code copy,
covering that initial gap on the inspected path. The staged-loader storage
callbacks also consume a 36-byte earlier-stage SRAM descriptor and translate
six recognized low-address primitive values into application NAND routines;
the earlier provider itself is not recovered.
The allocator boundary remains unsuitable as a presumed safe write or
RAM-firmware upload boundary without ownership/runtime qualification.

## On-chip SRAM (`0x3f...`)

Startup writes translation-table descriptors in the `0x3f004000` and
`0x3f006000` areas. Exception handlers use `0x3f00f818`-area scratch storage,
and reset assigns per-core stacks below `0x3f010000`. Other code copies small
functions into `0x3f00e000`-area SRAM and executes them there. Consequently,
the `0x3f...` accesses are RAM—not a second MMIO bank.

## Peripheral register blocks

The curated register-block map is in
[`hardware-register-blocks.tsv`](hardware-register-blocks.tsv). The strongest
identifications are:

- `0x10004000`: NAND, SD, SDIO/UART peripheral complex;
- `0x10005000`: USB device and OHCI host controller;
- `0x10006000`: audio and ADC;
- `0x10008000`: JPEG codec;
- `0x10009000`: front-end sensor/LVDS interface;
- `0x1000a000`: display controller;
- `0x1000b000`: IME image engine;
- `0x1000c000`: H.264 codec;
- `0x10002000`: command-queue/CDSP auxiliary registers;
- `0x10011000`: command-queue interrupt/control registers;
- `0x1000d000`: the CDSP main block; and
- `0x10018000`: GPU/HWC.

These labels come from code that both references the address range and embeds a
matching source path such as `hal_jpeg/hal_jpeg_encode.c` or
`hal_front/hal_front_lvds.c`. The `MAC` name at `0x10007000` is preserved from
`hal_mac/hal_mac.c`; it must not yet be expanded to “Ethernet MAC.”

Compiled-in HAL modules additionally show support for GPIO, NAND, SD/SDIO,
UART, USB, audio, ADC, sensor serial interfaces, display/DSI, JPEG, H.264,
CDSP, GPU, GPE, IME, MOE, RTC, watchdog, clocks/PLL, and DRAM monitoring. A
compiled driver proves firmware capability, not by itself that every supported
peripheral is physically populated in this camera.

## Storage and ROM boundaries

The firmware contains a Linux boot-argument builder naming `icatchtek-nand`,
`kernelImg`, `rootfsImg`, `fastboot`, and `ubifs`, and it has active NAND/SD HAL
code. The package also contains a verified ARM zImage and SquashFS rootfs. See
[`update-package-and-linux.md`](update-package-and-linux.md) for the package and
NAND partition map.

No internal boot-ROM image has been identified in `firmware-main.bin`. The
`DRAM CODE` label and reset sequence instead indicate that an earlier immutable
or flash-resident stage loaded this image into DRAM. Static analysis can map
references to that earlier stage only if its bytes are also present elsewhere
in `FPUPDATE.DAT`; it cannot recover an SoC mask ROM that is absent from the
update package.

## Method and limitations

[`map_hardware.py`](map_hardware.py) disassembles the synthetic ELF, follows
short-lived PC-relative literal loads and `MOVW`/`MOVT` constants, and records
constants actually used as `LDR`/`STR` bases. It found 101,754 candidate
accesses across 644 pages. The compact raw census is
[`output/static-memory-pages.tsv`](output/static-memory-pages.tsv); the 8.8 MB
instruction-level file is reproducible and intentionally ignored by Git.

This scanner is deliberately only a candidate generator. ARM code/data
confusion, incomplete register tracking, calculated addresses, aliases, and
indirect register bases can respectively create false positives and false
negatives. Labels were promoted only after manual disassembly/REA correlation.

REA evidence is retained in two bundles because Hopper became unavailable once
during decompilation and had to be restarted:

- `output/rea-hardware-map-evidence-partial.json`
- `output/rea-hardware-map-evidence.json`

A later Ghidra 12.1.4/REA pass is retained in
`output/rea-ghidra-startup-memory-evidence.json`. REA's synthetic-ELF
auto-import selected `ARM:LE:32:v8`, which correctly mapped the image but
misdecoded ARMv7 banked-stack instructions such as the `MSR sp_irq` at
`0x40000ee0`. Startup conclusions therefore use a separate raw-binary Ghidra
import forced to `ARM:LE:32:v7:default`, plus LLVM disassembly for exact
instruction verification. The resulting focused decompilation is in
`output/startup-v7.txt`. This distinction matters: changing Ghidra versions did
not fix an incorrect language selection.

REA's firmware-region scanner could not be used on this macOS host because its
configured Binwalk workflow requires Linux. This does not affect the local
address-access census, but package/partition discovery remains a separate task.

## Next useful investigations

1. Name the remaining `0x10001`, `0x10003`, and `0x10017` blocks by
   tracing their callers to source-path/assert strings.
2. Use the `os mem` debug command or a RAM snapshot to identify the live owners
   below the confirmed `0x414fb780` allocator ceiling, locate who clears the
   unbacked span, and determine which callers move the downward cursor at
   `0x40e37c58`.
3. Decode the opaque `SUNP BURN HDR 2` bulk region and analyze the card updater
   for integrity and recovery behavior.
4. Build a register-level map only for the subsystem relevant to a proposed
   modification; a full register catalog is much larger and riskier to infer.
