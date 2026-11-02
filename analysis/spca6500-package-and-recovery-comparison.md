# SPCA6500 package and recovery comparison

This note compares the X half 2.00 update with public iCatch V50/SPCA6500
material. The work was static and read-only: no third-party executable was run,
no camera command was sent, and no update was attempted.

## Bottom line

The X half uses the established iCatch/Sunplus `SPHOST.BRN` family of update
containers, even though Fujifilm names its file `FPUPDATE.DAT`. The shared
seven-slot header, A/B resource disks, main-firmware mapping, and DRAM-parameter
slot are now directly matched.

This gives us a credible **recovery lead**, not a proven recovery method. Public
V50 work documents USB reflashing and an apparent low-level boot mode on some
cameras, while explicitly saying the same button sequence fails on another V50
camera. Nothing yet proves that the X half exposes the mode, which button/GPIO
selects it, or whether it remains available after the internal flash is damaged.

## Artifact identities

| Artifact | SHA-256 |
|---|---|
| X half 2.00 `FPUPDATE.DAT` | `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68` |
| Public Akaso V50 Pro SE `SPHOST.BRN` comparison image | `1f9db54f710d26af1be9f96a317f838fa52e0ca90225f602c45b287e93f2a2f9` |
| Public V50M product brief copy | `f508ff584f0afb5810cb096cd4da9bc20763de423b337d2897361c627f15f45e` |

The external repository was inspected at commit
[`dc437ced8fdc9564e39ca550af6a1d53b1037714`](https://github.com/Linouth/iCatch-V50-Playground/commit/dc437ced8fdc9564e39ca550af6a1d53b1037714).
It describes iCatch V50 as SPCA6500. iCatch's own V50 page independently
describes the V50/V50M product family and its dual 32-bit CPUs, imaging engines,
security engine, USB 2.0, and external Wi-Fi support.

## Shared seven-slot container

The first 0x200 bytes have this layout:

| Header offset | Meaning |
|---:|---|
| `0x00` | 16-byte `SUNP BURN FILE` identifier |
| `0x10` | declared package end, little-endian `u32` |
| `0x14..0x28` | offsets for slots 1 through 6; slot 0 is fixed at `0x200` |
| `0x2c` | zero in both compared packages; public parser calls it `crc`, but its role is unverified |
| `0x1fc` | nonzero checksum word in both packages |

The public notebook says the word at `0x1fc` is updated by a vendor
`sumpatch` utility. Direct comparison resolves its algorithm: zero the field,
sum every little-endian 32-bit word from offset zero to the declared package
end (zero-padding the last partial word), and store the two's complement. The
sum including the stored word is exactly zero in both packages. The notebook
also reports that a bad value did not block flashing on one Akaso body. That is
device-specific evidence and must not be generalized to the X half. The
apparent `0x2c` parser label remains unexplained; that field is zero in both
samples.

### X half integrity control-flow trace

Static ARM control flow confirms that the checksum is enforced by the X half's
main-image updater library (the addresses below belong to that image, not the
separate small updater executable):

- card-update orchestration begins at runtime address `0x405efddc` in the main
  image's copy of the updater;
- it reads the byte count from parsed-header field `+0x10`, which corresponds
  to package header offset `0x10` (`0x09e1d16a` in this update);
- it processes exactly that many bytes with helper `0x40057d68`, which adds
  aligned little-endian 32-bit words and packs any final one to three bytes in
  little-endian order;
- it accumulates the result modulo 2^32 and proceeds only when the result is
  zero; otherwise it prints `BRN file chksum err:%08x` and returns updater
  error 9.

This is the same algorithm independently derived from the bytes. It also
defines the check's boundary: the official file is `0x09e1d66a` bytes, but the
declared and checksummed region ends at `0x09e1d16a`. The final `0x500` bytes
are outside this updater checksum.

The camera application's earlier firmware-menu checks are weaker:

- `0x405ae02c` opens `D:\\FPUPDATE.DAT`, confirms `SUNP BURN FILE`, seeks to
  the slot-3/main-firmware offset from header offset `0x1c`, scans the first
  7,935 bytes for `FFSD_BRN:`, and requires project ID `KC36D10`;
- its message `cannot seek to signature offset` uses “signature” to mean that
  embedded firmware marker/slot, not a cryptographic signature; and
- `0x405ae61c`, titled `CheckFirmwareUpgradeCheckSum` by its own log text,
  opens and reads the whole file but leaves its sum variable at zero, prints
  `sum32=0x00000000`, and returns success unconditionally in this build.

Therefore the high-level checksum check is a stub, while the lower-level card
updater's additive checksum is active. Recalculating `0x1fc` is necessary for a
modified package to reach the write path. It is not sufficient evidence that a
modified flash is safe.

### The outer 0x500-byte trailer

Observed trailer structure begins with 256 high-entropy bytes, followed by the
fixed descending byte sequence `20 1f ... 02 01`, the string `0.001.000`, two
copies of the declared package length, small flags, and zero padding. The
descending 32-byte value is not the SHA-256 of the declared package, in either
byte order. Searches of both the main image and extracted card updater found no
copy of that sequence, no SHA-256 initial-constant table, and no standard
SHA-256 PKCS#1 DigestInfo prefix. The version string also does not occur in
either executable.

The official X half 1.20, 1.30, and 2.00 trailers sharpen this structure. Every
byte in the first `0x100`-byte block changes among the three releases. From
trailer offset `0x100` through `0x4ff`, only six byte positions vary: three
bytes in each of the two stored package-length copies (`0x134..0x136` and
`0x150..0x152`). Everything else in that `0x400`-byte suffix is identical.
This is consistent with a content-dependent 256-byte signature or opaque
producer value followed by a fixed metadata record, but does not identify the
algorithm or prove that the camera verifies it.

Official firmware 1.01 has no outer trailer at all: the file ends exactly at
the declared, checksummed package extent. The trailer was therefore added
after the first post-launch update and is not part of the invariant seven-slot
container structure.

No traced X half update function reads or validates the trailer: the menu
checker merely reads the full file into memory and returns success, and the
card updater stops at the declared end. This makes the trailer look like
tooling/signing metadata that this firmware build does not enforce. Its exact
producer and the meaning of the 256-byte block remain unknown, so “not enforced
by the traced path” must not be generalized to every boot stage or firmware
version.

| Slot | Public Akaso role | X half role | Confidence |
|---:|---|---|---|
| 0 | ISP/card-update bootloader | package header plus complete ARM updater at `0x200`; reset body at `0x540` | strong structural match; see [mapping correction](updater-image-layout.md) |
| 1 | unknown | `SUNP BURN HDR 1` | role unknown |
| 2 | FAT A/B resource images | HDR2 plus FAT16 A/B resource images | confirmed |
| 3 | main firmware mapped at `0x40000000` | SPCA6500 `DRAM CODE` mapped at `0x40000000` | confirmed |
| 4 | unused in the comparison image | `firstburn`, Linux zImage, and SquashFS | confirmed X half extension |
| 5 | calibration/resource metadata | `SUNP BURN HDR 3` resource-name table | related role likely; exact semantics unknown |
| 6 | `drampara-ddr.prm`, mapped at `0x47ff0000` | `DRAMPAR3` | strong role match; X half runtime address not independently proved |

## HDR2 is two resource disks

The X half's former “opaque” region is exactly decodable at its outer level:

| Range | Size | Contents |
|---|---:|---|
| `0x000a3c00..0x000a3d1f` | 288 bytes | `SUNP BURN HDR 2` control header |
| `0x000a3d20..0x078a3d1f` | 120 MiB | FAT16 resource image A; root entry `RO_RES` |
| `0x078a3d20..0x082a3d1f` | 10 MiB | FAT16 resource image B; root entry `USER_RES` |

Both boot sectors use OEM label `ICATCH`. The B image ends exactly at the
`firstburn` region. The reproducible parser records geometry, hashes, and root
entries in [`output/firmware-regions.json`](output/firmware-regions.json).

The public Akaso image uses the same HDR2+0x120 construction, with a FAT16 A
image followed by a smaller B image. Its B filesystem is FAT12, whereas both X
half images are FAT16. This is a format variation, not a contradiction.

## Firmware1/Firmware2 write path

The main image contains a copy of the card-update implementation with usable
runtime addresses. Static ARM disassembly establishes:

- orchestration function `0x405efddc` prints and selects the Firmware1 and
  Firmware2 paths;
- it calls the same writer at `0x405ef18c` with image index `0` for Firmware1
  and index `1` for Firmware2;
- Firmware2 is selected when parsed-package field `+0x234` equals 1;
- the writer rounds sizes to 512-byte boundaries, performs low-level write
  operation 6, optionally reads data back with operation 5, and compares the
  source and read-back buffers;
- a mismatch produces error 23, and the caller treats zero (and in one path 23)
  distinctly before proceeding; and
- the two copies use separate parsed offsets/sizes but share the same storage
  interface and verification loop.

The generic updater can write two separately selectable firmware regions, but
the X half 2.00 UI path does not normally do so. Its parsed Firmware2-enable
field remains zero, the top-level header has no second firmware payload at
offset `0x2c`, and orchestration follows `keep original Firmware2`. Meanwhile,
the UI explicitly enables a Firmware1 transaction mode that invalidates its
stored checksum before writing, restores it only on success, and leaves it
invalid on failure. This is strong fallback-intent evidence, but it still does
**not** prove that the boot loader automatically cold-boots Firmware2. See
[`firmware-copy-and-fallback.md`](firmware-copy-and-fallback.md).

## Public USB/boot evidence

The public V50 repository includes an iCatch Windows driver and vendor FRM
reflash package. Read-only inspection found:

- driver matches for `VID_2AAD&PID_6500` and iCatch's older `VID_04FC` family;
- separate composite, null, and bulk-camera interfaces;
- a `spca6350` profile describing SPI storage, an ISP image, a BRN image, and
  an ISP load address; and
- FRM change-log text that distinguishes `mask boot`, `ISP boot`, and firmware
  boot methods and discusses downloading DRAM parameters before the ISP image.

Those files are useful evidence that this tool family knows a mask-boot/USB ISP
workflow. They are not an X half driver or an X half profile. The X half's
normal observed USB identity remains Fujifilm `04CB:030A`, not one of those
iCatch IDs.

The public repository also repeats a shutter+USB+power button sequence that
made some cameras enumerate bulk/null interfaces for FRM. Its author explicitly
states the sequence did not work on an Akaso V50 Pro. The README calls this
“debug mode”; it does not demonstrate whether the enumerating code is immutable
mask ROM or flash-resident code. We therefore must not try that sequence on the
X half as if it were established recovery.

### Practical value for the USB-only X half route

The repository is particularly useful now that internal probing is out of
scope. Its Akaso resource inventory includes `RO_RES/USBUVC.BIN` and
`RO_RES/USBUVC_360.BIN`. The bundled vendor reflash package independently
contains another `RO_RES/6350/USBUVC.BIN`, plus:

- `USB_CAM.dll` and `USB_CAM5000.ini`, likely host-side camera transport;
- `FRM.exe` and `autofrm.exe`, the reflash clients;
- `spca6350isp.bin`, an ISP-stage loader;
- `SPC.bin`, a larger firmware/loader artifact;
- processor profiles and DRAM parameter files; and
- Windows bulk, null and camera-interface drivers.

These artifacts give us a three-way static comparison:

1. the X half's `USBUVC.BIN` and UVC/RCA handlers;
2. a second SPCA6500-family camera's UVC resource and main firmware; and
3. the vendor host program, driver declarations and ISP loader that speak the
   corresponding USB protocols.

The highest-value next work is to extract both camera UVC resources, identify
their format and descriptors, and match their callbacks or packet constants to
the host-side DLL/client. After that, static analysis of the reflash client can
separate identity/read commands from erase/program commands and reveal how the
ISP loader is uploaded. No Windows executable needs to be run.

This comparison can explain protocol structure and locate homologous X-half
code. It still cannot supply X-half DRAM parameters, flash geometry, button
chord, USB VID/PID or safe loader address. Those remain body-specific gates.

## Recovery confidence and next proof

Current classification: **yellow lead, not a safety net**.

What became more likely:

1. the X half inherited iCatch's card-ISP package and update architecture;
2. a USB ISP/mask-boot facility exists in the broader chipset tool family; and
3. the two firmware-copy write paths are real, not just unused strings.

What is still required before any modified flash:

1. find the X half's early boot-source GPIO/button check in immutable or
   pre-firmware code;
2. identify its recovery-mode USB VID/PID and prove which stage enumerates it;
3. obtain an X half-specific ISP loader/profile and verify its RAM address,
   DRAM parameters, and storage geometry;
4. demonstrate a harmless RAM-only action from cold power; and
5. prove boot fallback behavior independently of the update writer.

## Pinned public sources

- [iCatch official V50 product page](https://www.icatchtek.com/ProductContent/821b81be3f454b7d99c831dec5664ba9)
- [iCatch V50 Playground README](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/README.md)
- [SPHOST reversing notes](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/reversing/README.md)
- [SPHOST carving implementation](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/reversing/sptool/carve.py)
- [V50M product brief copy](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/icatchV50M.pdf)

