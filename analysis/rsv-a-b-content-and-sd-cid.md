# Reserved A/B content and the SD CID binding

Date: 2026-11-07. Read-only dumps via the APITEST shell `nandpr` path,
plus one live `sddetect`/`sdspd`/`sdhdr` shell session. No storage
writes.

Inputs:

| File | Size | SHA-256 |
| --- | --- | --- |
| `/private/tmp/xhalf-nand-rsvA-full-2026-11-07.bin` | 127,664,128 | `90ebcff3325b8a62d86b32e3511be6acdb9ca750379cdf26e4f80f71737c59d0` |
| `/private/tmp/xhalf-nand-rsvB-full-2026-11-07.bin` | 11,796,480 | `0dbc49bf2e8ac0fe168c656e674d5f0e4aad54dfeeda582bb563734d0fbbf7a6` |
| `/private/tmp/xhalf-console-sddetect-2026-11-07.log` | 18 KB | live console log |

## Reserved A (blocks 2–488): `A:\RO_RES`

Block map (block size `0x40000`):

| Blocks | Content |
| --- | --- |
| 2 | `PHYTABLE` — 16 pages, one per 4 KiB, each with an incrementing `0x2d000`-family value |
| 3–175 (173) | calibration/resource tables (smooth gradient data, low-to-mid entropy) |
| 176 | erased |
| 177–320 (144) | resource data: UTF-16-style name tables, fonts, JPEG/TIFF assets, PNG, RIFF audio, `FUJIFILM` metadata |
| 321–347 (27) | erased |
| 348–353 (6) | data |
| 354 | erased |
| 355–356 (2) | data |
| 357 | zero |
| 358–359 (2) | data |
| 360 | erased |
| 361–467 (107) | high-entropy data (~7.9 bits/byte), compressed or encrypted resource blob |
| 468 | zero |
| 469 | data |
| 470 | zero |
| 471–488 (18) | data |

Searches across all 121.75 MiB found **no** `RawCBoot`, `:NO_BOOT`,
`:DEF_FW*`, `SPBoot>`, updater image, main-image copy, `firstburn`,
`DRAM CODE`, `DRAMPAR3`, or `SUNP BURN`.

The main firmware's string table identifies the A: drive as
`A:\RO_RES\` and lists hundreds of resource files there
(`AFECMD.BIN`, `AGC.BIN`, `CALIB\...`, `Font\...`, `IQ\...`,
`WAV\SHUTTER.WAV`, ...). The A dump's content matches a read-only
resource/calibration volume, not boot policy or a firmware slot.

## Reserved B (blocks 489–533): `B:\USER_RES` FAT16 volume

44 of 45 blocks are erased. Block 522 is a complete FAT16 volume:

- Boot sector: `E9 00 00`, OEM `ICATCH  `, `FAT16   `, `0x55AA`.
- Geometry: 512-byte sectors, 2 sectors/cluster, 1 reserved sector,
  2 FATs, 512 root entries, FAT size 40 sectors, 20,480 total sectors
  (10 MiB nominal).
- Root: `USER_RES` (directory), `SD_CID.DAT` (16 bytes).
- `USER_RES`: `LOG_DSC0.BIN` (84), `LOG_FMT0.BIN` (36),
  `UIDATA.BIN` (2432), `UIBTGPS.BIN` (121), `UIBTRES.BIN` (8300),
  `UIWPASS.BIN` (20), `RES_JPG` (empty directory).

Notable file contents:

- `SD_CID.DAT` = `03 53 44 53 4b 33 32 47 85 74 05 5f a2 01 93 3f`
  — a SanDisk `SDSK32G` CID (manufacturer `0x03`, OEM `SD`,
  product `SK32G`).
- `UIWPASS.BIN` = `DP83GG44NMFNRLeQiE9h` (Wi-Fi password).
- `UIBTRES.BIN` contains `iPhone_9692`; `UIBTGPS.BIN` contains
  `2026:07:03` and GPS/BT settings.
- `UIDATA.BIN` lists the UI resource names (`UIBOOT00.BIN`,
  `UIGENERA.BIN`, `UIOPEN.BIN`, ...).

The main firmware mounts this as `B:\USER_RES\` and reads
`B:\SD_CID.DAT`, `B:\SD_REM.DAT`, `B:\USER_RES\UISETUPO.BIN`, etc.

## Live SD detect: the inserted card matches `SD_CID.DAT`

The shell's `sddetect` command initialized the SD interface and read
the inserted card:

```text
CID:03 53 44 53 4b 33 32 47 85 74 05 5f a2 01 93 3f
Get RCA AA AA
CSD:40 0e 00 32 db 79 00 00 ed c8 7f 80 0a 40 40 e5
Identify SD
capability=30436 MB
CMD23 support:1
CMD20 support:1
SDHC
SD switch to 4 bit mode
SD speed C10 U1 V10 (discard:1)
Possible SD at 1.8V mode:1f
SD switch to 1.8V
####SD type = SDHC
Set 198MHz
switch to SDR104 Mode
...
SD detect  ---Pass
```

The CID is **byte-identical to `B:\SD_CID.DAT`**. The camera's
"specific card" binding is satisfied by the inserted SanDisk 32 GB
card. `sdspd` then reported `sdSpd = 6` (SDR104).

This is direct evidence that the SD hardware, card, CID binding and
main-firmware SD init sequence all work when `sddetect` is invoked.
It also means the updater's SD-mount failure is not a CID mismatch;
it is an init/state difference in the updater's SD path.

### `sdhdr` crash

`sddetect` succeeded, but `sdhdr` (the command that prints the SD
card's reserved `eSD RSV` header) crashed the shell:

```text
stop trace!
CORE#0 DEAD
Program dead @[40008bc4]e491c004 SP:417d80f8
Because(4) (DABT) data abort
FaultAddr[(nil)]
(epc)40008bc4 (lr)40345b1c
```

`sdhdr` dereferences the SD-header global pointer without a null
check; the pointer was null in this shell state. This is a
debug-shell crash, not a storage write. A power cycle recovers it.
It also means the SD reserved header is not loaded by `sddetect`
alone; another init/mount step must populate it.

### The debug shell cannot read the SD reserved header

Two further read-only attempts confirm the device-1 context problem:

- **`sdr <start> <count> <flags>`** (SD device 2) works and reads at
  54 MB/s, but it allocates a heap buffer and frees it before the
  shell prompt returns. Pulling that address back through the loader
  yielded 64 KiB of zeros; the data does not survive.
- **`rsvsdrd <start> <count> 0x41130000`** (SD device 1 reserved
  blocks) crashed the shell with the same null-pointer data abort
  (`r4=0`, fault at `0x400cc014`, LR `0x400ce3d0`).

The device-1 context (`0x40e4e564`) is only initialized by
`0x4004e9dc(1)`, which the shell reaches only through the **write**
commands `sdfww` (write F/W to SD reserved blocks) and `sdset`
(write SD settings), or through the normal boot/updater path. The
read commands (`sdhdr`, `rsvsdrd`, `sdboot`, `sdfwr`) assume it is
already populated. In the debug loader mode it is null, so those
commands fault before reading anything.

Consequences:

- The `eSD RSV` header is still unread.
- The SD-boot lead cannot be closed from this shell without either
  initializing device 1 (a RAM-only call to `0x4004e9dc(1)` or an
  updater session) or reading the card directly with a card reader.
- The card-reader route is read-only and avoids further shell
  crashes: dump the first/last regions of the 32 GB card and search
  for `eSD RSV` / `NAND RSV` / `0xBEEF`.

## SD card contents (card-reader dump, 2026-11-07)

The card was removed and read directly with the Mac's SD reader:
first 64 MiB SHA-256 `76f4b8b5…`, last 64 MiB SHA-256
`3b6a07d0…`. The last 64 MiB is entirely zero. The card has an
MBR at sector 0 and one FAT16 partition:

- Partition: type `0x06`, start LBA 2048, 2,929,688 sectors
  (1,500,000,256 bytes), volume label `XHALF`.
- Root directory: `DCIM` (two JPEGs in `101_FUJI`), `ACTIVITY`
  (empty), `.fseventsd` (macOS metadata), `EnableTest.txt`
  (1 byte, `x`), and **`MAIN.BIN`** (14,910,464 bytes).
- `MAIN.BIN` SHA-256 =
  `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
  — **byte-identical to the official 2.00 main firmware**.
- `EnableTest.txt` is the file the main firmware opens as
  `D:\EnableTest.txt` (the image also has `D:\EnablePTPAP.txt`,
  `D:\EnableIrisTest.txt`, `D:\EnableTest_PSAM.txt`). It is a
  factory/test enable flag.

The `eSD RSV` / `NAND RSV` / `PHYTABLE` / `firstburn` strings found
in the first 64 MiB are **inside `MAIN.BIN`** (firmware format
strings and rodata), not a separate raw header. The card does not
carry a standalone `eSD RSV` header in the first or last 64 MiB.

Implication: this is a **prepared SD file-boot card**. The updater's
boot consumer loads `D:\MAIN.BIN` (the emulator's
`loaderPath=D:\MAIN.BIN`); the card is the authorized one (CID
matches `SD_CID.DAT`) and the payload is present. The blocker is
the updater's SD mount (card state 0), not the card contents or the
CID. The main firmware's `sddetect` shows the card can initialize,
but it needs a power-recycle/re-detect; the updater's
`sdReMount(2,0)` may not perform that sequence.

## The updater's boot command table

The updater's `FUN_4000f270` parses a boot command string and maps it
to a boot mode:

| Command | Meaning (observed) |
| --- | --- |
| `:RAWCBOOT0`..`:RAWCBOOT5` | select RawCBoot record 0..5 |
| `:DEF_FW1`, `:DEF_FW2`, `:DEF_RAWC` | default firmware 1 / firmware 2 / rawC |
| `:MB_FW1`, `:MB_FW2`, `:MB_RAWC` | mailbox firmware 1 / 2 / rawC |
| `:NO_BOOT` | abort record / no boot |
| a file path (e.g. `D:\MAIN.BIN`) | load and boot a file |

`FUN_4001c78c(index, buffer, flag)` reads a 4 KiB `RawCBoot` block
from device 1 at offset 0 or one block size, checks the `RawCBoot`
magic, and copies one of six 672-byte records (`:RAWCBOOT0..5`).
The device table is runtime-initialized (BSS at `0x400a39d0`), so
"device 1" is not yet resolved statically. It is not the A, B or C
reserved area: the magic is absent from all three full dumps.

## What this changes

- **A is not the rawC home.** It is the `A:\RO_RES` factory
  resource/calibration volume. The full 121.75 MiB is now mapped.
- **B is not the rawC home.** It is the `B:\USER_RES` FAT16 volume;
  the only data block is block 522. It holds UI settings and the SD
  CID, not boot policy.
- **C is not the rawC home.** The full C dump is `PHYTABLE` +
  firstburn Linux only.
- **The SD card is the expected one and carries the stock
  firmware.** The live CID matches `SD_CID.DAT` exactly; `sddetect`
  passes at SDR104. The card-reader dump shows `MAIN.BIN` =
  official 2.00 firmware and `EnableTest.txt` = `x`. This is a
  prepared SD file-boot card, not a raw `eSD RSV` header.
- **The SD reserved header exists as a concept** (`sdhdr` prints
  `eSD RSV` with FW1/FW2/RSV counts) but is not loaded in the
  debug shell; `sdhdr` crashes on the null header pointer.
- **The updater's RawCBoot volume remains unresolved.** It is a raw
  device read, not a file, and it is not in A/B/C. The remaining
  candidates are a hidden logical volume, the C: DCIM volume, or the
  SD card's raw/reserved area.

## Open questions / next evidence

1. **What populates the SD reserved header?** Answer: the device-1
   context is created by `0x4004e9dc(1)`. The shell reaches that only
   through the write commands `sdfww`/`sdset` or the normal
   boot/updater path. The read commands assume it is already
   populated; in the debug shell it is null. A RAM-only call to
   `0x4004e9dc(1)` or an updater session could populate it safely.
2. **Read the card directly.** The lowest-risk next evidence is a
   card-reader dump of the first/last regions of the 32 GB card,
   searching for `eSD RSV` / `NAND RSV` / `0xBEEF`. This is read-only
   and avoids the null-context crashes. It reveals whether the card
   carries a firmware-slot header at all.
3. **Where is the RawCBoot block?** Resolve the updater's device
   table entry 1 at runtime (read `*(0x4009de2c)` and the
   `0x400a39d0` table in a live updater session), or test candidate
   volumes with a read-only 4 KiB read.
4. **What does the SD `eSD RSV` header contain?** If the SD card has
   FW1/FW2/RSV areas, `sdhdr`/`sdboot` may be the SD-boot path the
   updater was trying to use. The SD card's reserved area is a new,
   concrete SD-boot lead.
5. **Does `sdboot` boot from SD?** Its handler checks a callback at
   `*(SD_device+0x38)` and calls a supplied address; it is not yet
   clear whether it is a production SD boot or a factory test hook.
   Inspect the SD device structure after a successful header load.

## Boundary

The A/B maps, FAT16 listing, hashes and live CID/CSD are observed.
The interpretation of A as `RO_RES` is supported by the main
firmware's path strings. The SD card's FAT16 contents are observed:
`MAIN.BIN` is the official firmware and `EnableTest.txt` is the
test flag. No standalone `eSD RSV` header was found in the first or
last 64 MiB; the partition-end window is not yet read. The
`sdhdr` and `rsvsdrd` crashes are debug-shell null-pointer faults;
no storage write occurred.
