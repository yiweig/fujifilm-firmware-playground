# Vendor SD F/W shell commands: the missing SD-boot creation path

Date: 2026-11-09. Offline command-table/disassembly review of the
official 2.00 main image. No camera access in this pass. This resolves
why the prior card test (FAT `MAIN.BIN` + updater) did not boot: the
vendor SD boot uses the **eSD reserved-block layout**, and the shell
has the commands that create and read it.

## Command table

The SD command table at `0x40df9484..0x40df958c` (12-byte rows:
`0, name, handler, help`):

| Command | Handler | Help / usage |
| --- | --- | --- |
| `sdr` | `0x40305acc` | Read a physical page from SD card |
| `sdw` | `0x4030594c` | Write a physical page from SD card |
| `sder` | `0x4030588c` | Erase a physical page from SD card |
| `rsvsdwr` | `0x40305808` | Write reserved block of SD |
| `rsvsdrd` | `0x40305778` | Read reserved block of SD |
| **`sdfww`** | `0x40305508` | **Write F/W into SD’s reserved blocks. `rsvfww <addr> <size>`** |
| `sdfwr` | `0x40305498` | Read F/W from SD’s reserved blocks. `rsvfwr <addr> <size>` |
| **`sdset`** | `0x403063fc` | **Allocate SD’s reserved blocks. `rsvset <size A> <size B> <size C> (bytes)`** |
| **`sdhdr`** | `0x40305490` | **Display SD’s reserved header. `rsvhdr`** |
| `sdrwt` | `0x403050e0` | SD device read/write test |
| **`sdboot`** | `0x4030543c` | **SD FW boot to an address** |
| `sdscan` / `sdautoscan` | `0x40306750` | SD driving phase-latch tuning |
| `sdlock` | `0x4030621c` | — |
| `sdSpeedSet` | `0x40306088` | — |

## Why the prior read/boot attempts crashed

The device-1 SD context at `0x40e4e564` is **null in the debug loader
shell**. The read/boot commands (`sdhdr`, `rsvsdrd`, `sdboot`) assume
it is populated and fault when it is not. It is initialized only by
`0x4004e9dc(1)`, which the shell reaches through the **write** commands
`sdset` and `sdfww` (or the normal boot/updater path). The prior session
correctly avoided the write commands; that is why the eSD layout was
never created or read.

## What the write commands do

- **`sdset <A> <B> <C>`** allocates the SD reserved blocks and builds
  the `eSD RSV` header. The sizes are bytes; the header fields
  (`asciiName`, `0xbeef`, `blkSize`, slot starts/lengths, `nrFw1`,
  type `0x32`, integrity fields) are the layout that `sdDetect` and
  `sdboot` consume. This is the vendor’s SD-card format step.
- **`sdfww <addr> <size>`** writes the F/W image from **DRAM** at
  `<addr>`, length `<size>`, into the SD reserved F/W slot. The source
  can be the loader’s staged payload buffer (the 14.9 MB main image can
  be placed at the bootquery buffer via `0x04f5`), so the image need not
  live in a FAT file.
- **`sdhdr`** displays the resulting reserved header (useful read-only
  verification).
- **`sdboot <addr>`** reads the F/W slot to `<addr>` and jumps there.
  The worker (`0x40345c80`) sets `r1 = state+0x1c` (slot-1 length),
  calls the slot reader `0x400778fc`, then `blx` the destination. The
  advertised “embedded/external” selector is stale; the second argument
  is discarded, and the worker branches even if the read fails.

## The SD-boot sequence this enables

1. Back up the test card (the `sdset` reserved-block allocation may
   reformat/overwrite the FAT/reserved areas).
2. In loader mode, use `sdset <A> <B> <C>` to create the eSD header and
   reserve the blocks. The NAND-header counts (487/45/261 blocks) are
   the reference sizes; the command takes bytes.
3. Stage the stock main image (or a test image) into DRAM via the
   loader’s `0x04f5` path (the bootquery buffer is 14.9 MB-capable).
4. Run `sdfww <addr> 0xe38400` to write the image into the SD F/W slot.
5. Run `sdhdr` to verify the header/slot fields.
6. Run `sdboot 0x40000000`; the worker reads the slot and jumps to the
   reset vector. This bypasses the `+0x200` staged-loader/NAND reload
   that blocked the `0x04f7` path.
7. Observe the stock main image’s reset startup from the card. A
   failure is a power cycle; the camera’s NAND is untouched.

The **cold-recovery** variant is the same card, read by the earlier
stage’s eSD path. That still needs the earlier-stage eSD backend/selector
(`0x10000058 & 0xe0` / boot strap); the `usbt` shell command
(`0x4031d498`, “Ready for Usb Boot ..”) remains the other recovery lead.

## Access and risk

- The commands are **shell commands**, so the APITEST character bridge
  (`0x401c1550`, `cmd>` prompt) or the handler-slot trampoline must
  reach the shell/task context. The handler context is riskier for the
  blocking SD read/write calls.
- `sdset`/`sdfww` write the **SD card**, not the camera’s NAND. The card
  is a test card; back it up. A shell crash/wedge is recovered by power
  cycle.
- `sdboot`’s read-failure branch jumps to the destination regardless;
  a failed read can crash the camera. Recover by power cycle.
- The prior `rsv-a-b-content-and-sd-cid` card had only the FAT
  `MAIN.BIN`; the eSD reserved layout was never created. That is why
  the read/boot commands faulted and why the FAT-file updater path
  could not use it.

## Boundary

The command table, handlers, help text, device-1 context dependency and
`sdboot` worker behavior are observed in the shipped image. The `sdset`
header/slot construction and the `sdhdr` verification are inferred from
the help/handler and the `eSD RSV` schema in
[recovery-entry-review.md](recovery-entry-review.md); the exact
reserved-size/total-block geometry for a 32 GB card is not yet
validated. The commands have not been run on the camera.
