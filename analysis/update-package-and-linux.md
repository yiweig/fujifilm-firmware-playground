# FPUPDATE.DAT package, Linux image, and flash layout

This report inventories the Fujifilm X half update package without executing it
or communicating with the camera. The camera was unplugged throughout.

## Artifact identity

- `FPUPDATE.DAT` SHA-256:
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`
- File size: `165,795,434` bytes (`0x09e1d66a`).
- Container identifier: `SUNP BURN FILE`.
- The container header declares an end at `0x09e1d16a`, followed by a
  `0x500`-byte outer trailer.

The machine-readable region inventory, including per-region hashes, is
[`output/firmware-regions.json`](output/firmware-regions.json). It is generated
by [`inventory_firmware.py`](inventory_firmware.py).

## Verified package regions

| File range | Size | Interpretation | Verification |
|---|---:|---|---|
| `0x00000000..0x000a2bff` | `666,624` | top-level header and card updater | top-level pointer and updater strings/code |
| `0x000a2c00..0x000a3bff` | `4,096` | `SUNP BURN HDR 1` | exact header pointer/string |
| `0x000a3c00..0x082a3d1f` | `136,315,168` | `SUNP BURN HDR 2` plus opaque bulk payload | exact boundary; internal format unresolved |
| `0x082a3d20..0x082c3d1f` | `131,072` | `firstburn` manifest and padding | manifest text; aligned zImage follows |
| `0x082c3d20..0x084c0d8f` | `2,084,976` | ARM Linux zImage | zImage header and matching manifest size |
| `0x084c0d90..0x086c3d1f` | `2,109,328` | kernel partition padding | between verified payloads |
| `0x086c3d20..0x08fd281f` | `9,497,344` | SquashFS root filesystem | superblock and matching manifest size |
| `0x08fd2820..0x08fe3d1f` | `70,912` | alignment padding | between verified payloads |
| `0x08fe3d20..0x09e1c11f` | `14,910,464` | SPCA6500 `DRAM CODE` | header pointer and embedded image header |
| `0x09e1c120..0x09e1ca6f` | `2,384` | `SUNP BURN HDR 3` resource-name table | exact header pointer/string |
| `0x09e1ca70..0x09e1d169` | `1,786` | `DRAMPAR3` and declared tail | exact header pointer/string |
| `0x09e1d16a..0x09e1d669` | `1,280` | outer trailer | actual size minus declared end |

The 130 MiB HDR2 payload has long high-entropy and low-entropy runs. Entropy
cannot distinguish encryption from compression, and the region also contains
sparse areas, so it remains deliberately labelled **opaque**, not “encrypted.”

No ELF, UBI, or flattened-device-tree magic was found in the package. The only
SquashFS superblock is at `0x086c3d20`.

## Linux kernel

The zImage header declares a size of `0x001fd070` (`2,084,976`) bytes, exactly
matching the first `firstburn` manifest value. Its gzip payload decompresses to
`3,486,368` bytes and reports:

```text
Linux version 3.18.11 (oscar_lin@RDFW-SRV13232)
(gcc version 4.8.3 (OpenWrt/Linaro GCC 4.8-2014.04 unknown))
#638 PREEMPT Fri Aug 14 10:47:30 CST 2026
```

The kernel contains SPCA6500 platform code, NAND, UART, GPIO, I2C, SPI, SDIO,
USB, UBI, SquashFS, and fastboot-related support. Neither the package nor the
decompressed kernel contains an FDT magic header. The best present inference is
that this Linux build uses SPCA6500 board/platform code and ATAG-style boot data,
not an appended device tree. An encoded object hidden inside HDR2 cannot be
excluded until that format is decoded.

## Root filesystem

The root filesystem is SquashFS 4.0, zlib-compressed, with:

- 858 inodes including the root directory;
- 857 inventoried child paths: 71 directories, 594 regular files, and 192
  symlinks;
- 128 KiB data blocks; and
- creation time `2026-08-14 02:47:36 UTC`.

The full path/type/reference list is
[`output/rootfs-inventory.tsv`](output/rootfs-inventory.tsv). The repository's
[`squashfs_list.py`](squashfs_list.py) reads this filesystem directly from the
update package and can display selected files without carving or mounting it.

The userspace identifies itself as:

- OpenWrt Chaos Calmer 15.05;
- target `spca6500/SBC`;
- uClibc 0.9.33.2; and
- BSP marker `LINXBSP-REV-35140-3`.

Notable components include BusyBox, UBI utilities, Broadcom BCM43456C5 Wi-Fi
firmware/drivers, `hostapd`, `wpa_supplicant`, an AMP filesystem service, and
SPCA6500 kernel modules.

The shipped configuration has a passwordless root entry and starts
`/usr/sbin/autologin` on `ttyS0` at 115200 baud. This is useful evidence when
evaluating a future recovery-console strategy, but it does not identify the
camera's UART pads or prove that the production board exposes them. Default
network configuration names `eth0`, `usb0` (`192.168.2.1`), and `wlan0`
(`192.168.77.1`); configuration presence likewise does not prove those
interfaces are active in normal camera mode.

## NAND partition construction

The `firstburn` manifest contains:

```text
firstburn
2084976
9497344
4194304
33554432
```

The first two values are the exact kernel and SquashFS payload sizes. Static
analysis of the boot-argument builder at runtime address `0x40007644` shows the
last two values are used as the allocated kernel and rootfs partition sizes:

| Partition | Size | NAND offset | Basis |
|---|---:|---:|---|
| reserved/header area | 128 KiB | `0x00000000` | kernel begins at fixed `0x20000` |
| `kernelImg` | 4 MiB | `0x00020000` | firstburn capacity and boot-argument assembly |
| `rootfsImg` | 32 MiB | `0x00420000` | firstburn capacity and boot-argument assembly |
| `fastboot` | 16 MiB | `0x02420000` | fixed constant in boot-argument assembly |
| `ubifs` | runtime-derived | `0x03420000` | follows fixed fastboot partition |

The generated command line mounts `/dev/mtdblock1` as SquashFS and supplies
these partitions to the `icatchtek-nand` driver. The final UBIFS size depends on
a runtime configuration value, so total NAND capacity is not yet established.

Some rootfs scripts contain an older or alternate seven-partition table in
comments. That table should not be confused with the four-partition command
line actually constructed by this firmware path.

## Implications for the broader memory map

The update contains two substantial execution environments:

1. the SPCA6500 `DRAM CODE` image mapped at runtime to `0x40000000`; and
2. a separate Linux/OpenWrt kernel and root filesystem loaded from NAND.

The internal mask boot ROM is still absent. The card updater, NAND boot stage,
or another early loader must validate and place these payloads before the DRAM
image's reset vector runs. The updater at the start of the package is therefore
the next likely place to investigate integrity checks and recovery behavior.

## Reproduction

```sh
python3 analysis/inventory_firmware.py
python3 analysis/squashfs_list.py FPUPDATE.DAT \
  --offset 0x086c3d20 \
  --inventory-output analysis/output/rootfs-inventory.tsv
python3 analysis/squashfs_list.py FPUPDATE.DAT \
  --offset 0x086c3d20 \
  --read /etc/openwrt_release \
  --read /etc/inittab
```

All offsets are package file offsets. They are not flash-write addresses unless
the NAND table above explicitly says so.
