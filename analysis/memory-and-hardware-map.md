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

| Address range | Present interpretation | Confidence |
|---|---|---|
| `0x10000000..0x1001efff` (sparse) | SPCA6500 SoC peripheral registers | observed |
| `0x10201000..0x1023cfff` (sparse) | ARM CoreSight debug/trace registers | derived/observed |
| `0x10401000..0x10402fff` | ARM GICv2 distributor and CPU interface | derived |
| `0x3f000000..0x3f00ffff` | on-chip SRAM used for page tables, exception state, copied routines/data, and per-core stacks | derived |
| `0x40000000..0x40e383ff` | extracted, initialized DRAM image: vectors, code, constants, and initialized data | observed |
| `0x40e38400..at least 0x414fb9fc` | runtime static storage/BSS and globals not backed by bytes in the extracted image | derived |
| above that | heap, frame buffers, general DRAM, aliases, and true DRAM limit not yet established | unknown |

The last static address is a lower-bound observation, not a declaration that
`0x414fb9fc` is the BSS end. A lone apparent access above it was rejected as a
likely disassembly/data false positive.

## Boot and CPU initialization

The first seven words are ARM exception-vector branches. Reset branches from
`0x40000000` to `0x40000ec0`. That startup routine:

1. reads the ARM multiprocessor affinity register;
2. allocates per-core exception stacks below `0x3f010000`;
3. initializes cache/MMU state;
4. writes `0x40000000` to CP15 VBAR, making the vector table active;
5. uses `0x100002f0` as a secondary-core rendezvous/entry location; and
6. initializes the interrupt controller at `0x10401000/0x10402000`.

The image therefore begins at a genuine runtime entry point, not merely at an
arbitrary function boundary. This is the application/DRAM-stage entry point,
however—not necessarily the first instruction executed after power-on.

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

REA's firmware-region scanner could not be used on this macOS host because its
configured Binwalk workflow requires Linux. This does not affect the local
address-access census, but package/partition discovery remains a separate task.

## Next useful investigations

1. Name the remaining `0x10001`, `0x10003`, and `0x10017` blocks by
   tracing their callers to source-path/assert strings.
2. Recover the startup linker tables to determine exact initialized-data, BSS,
   heap, and DRAM boundaries.
3. Decode the opaque `SUNP BURN HDR 2` bulk region and analyze the card updater
   for integrity and recovery behavior.
4. Build a register-level map only for the subsystem relevant to a proposed
   modification; a full register catalog is much larger and riskier to infer.
