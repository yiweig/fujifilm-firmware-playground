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

- **`sdfww <file>`** is the one-command card creator. The handler
  opens its **first argument as a VFS file path** (e.g.
  `D:\MAIN.BIN`), reads the file into RAM, and — if the reserved
  header is not already allocated — calls the header builder
  `0x40077450(0x200000,0x200000,0x100000,0)`, i.e. **A = 2 MB,
  B = 2 MB, C = 1 MB**, then `FUN_4004e9dc(1)` to initialize the
  SD device-1 context. It parses the file's header bytes at
  `+0xa0`/`+0xb6`, sets the slot length/checksum fields, and writes
  the F/W to the SD reserved slot via `0x40077980`. The help text's
  `<addr> <size>` is stale; the second argument is only checked and
  logged, not used.
- **`sdset <A> <B> <C>`** is the manual allocation variant: the
  handler parses three sizes and calls the same header builder
  `0x40077450(A, B, C, ...)`; an optional file argument is read for a
  512-byte configuration. Use it only if the fixed 2/2/1 MB layout is
  not wanted.
- **`sdhdr`** displays the resulting reserved header (read-only
  verification).
- **`sdboot <addr>`** reads the F/W slot to `<addr>` and jumps there.
  The worker (`0x40345c80`) uses the slot-1 start at header `+0x14`,
  the length at `+0x1c`, and `blkSize` at `+0xc`; the slot reader
  `0x400778fc` converts them to SD sectors. The advertised
  embedded/external selector is stale; the second argument is
  discarded, and the worker branches even if the read fails.

## The SD-boot sequence this enables

1. Back up the test card. `sdfww` builds the eSD reserved layout at
   the start of the card; the FAT volume/`MAIN.BIN` will be
   overwritten. The camera’s NAND is untouched.
2. Enter loader mode and reach the shell (`cmd>`).
3. Run **`sdfww D:\MAIN.BIN`**. The shell reads the FAT file, builds
   the 2/2/1 MB reserved header, initializes SD device 1, and writes
   the image to the SD F/W slot.
4. Run **`sdhdr`** to verify the header/slot.
5. Run **`sdboot 0x40000000`**. The worker reads slot 1 to
   `0x40000000` and jumps to the reset vector, bypassing the `+0x200`
   staged-loader/NAND reload that blocked `0x04f7`.
6. Observe the stock main image’s reset startup from the card. A
   failure is a power cycle; the NAND is untouched.

The **cold-recovery** variant is the same card read by the earlier
stage’s eSD path. That still needs the earlier-stage eSD backend/
selector (`0x10000058 & 0xe0` / boot strap); the `usbt` shell command
(`0x4031d498`, “Ready for Usb Boot ..”) remains the other recovery lead.

## Access and risk

- The commands are **shell commands**, so the APITEST character bridge
  (`0x401c1550`, `cmd>` prompt) or the handler-slot trampoline must
  reach the shell/task context. The handler context is riskier for the
  blocking VFS/SD calls.
- `sdfww`/`sdset` write the **SD card**, not the camera’s NAND. The card
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

## Update 2026-11-09: the shell path was not needed — and the eSD boot was retracted

The live follow-up did not complete `sdfww`: the shell's SD read failed
at CMD18 (`R CRC Err1/2`), reduced the clock, and spent more than ten
minutes in phase/latch tuning. The card was unchanged after each
attempt.

A later card-level experiment appeared to boot the stock image from the
eSD slot, but control tests showed the camera was booting NAND in every
configuration; the black screens were a raw-card USB crash. See
[esd-card-boot-retraction-2026-11-09.md](esd-card-boot-retraction-2026-11-09.md).

`sdboot` is not a full-image loader: `0x40345c80` passes length 0 to
`0x400778fc` (one 512-byte sector) and uses `+0x1c` as a byte offset.
No shell command has been shown to make the camera execute the card
image.
