# Bootup: reset, RTOS entry, boot-mode dispatch, and the main task

Date: 2026-11-08. Ghidra 12.1.4 + llvm-objdump on the official 2.00
main image (`firmware-main.bin`, SHA-256 `80350bd2…`, base
`0x40000000`). No camera access in this pass.

## 1. Reset vector: `0x40000ec0`

The reset is valid ARM code; Ghidra's decompiler trips on the
coprocessor move, so the assembly is the reliable view:

```text
40000ec0: mrc p15,0,r1,c0,c0,5      ; MPIDR
40000ec4: movw sp,#0x0000
40000ec8: movt sp,#0x3f01           ; sp = 0x3f010000
40000ecc: ands r1,r1,#3
40000ed0: sub  sp,sp,r1,lsl #11     ; per-core stack
...                                 ; IRQ/FIQ/ABT/UND stacks
40000ef0: bl   0x40001180           ; early cache/TLB init
40000ef4: bl   0x4000147c           ; MMU enable
...                                 ; zero BSS, fill 0xefefefef
40000f28: bl   0x400017c0           ; page-table/global setup
40000f34: mcr  p15,0,r3,c12,c0,0    ; VBAR = 0x40000000
...                                 ; core-0 path
40000fb0: ...                       ; boot parameter block 0x10000110
40000fd0: ...                       ; 0x100003a0 = 1, 0x10000060 = 2
4000102c: blx  0x4000103c           ; clock/PLL/DRAM setup
40001030: mov  r0,#0xf
40001034: b    0x400037e8           ; RTOS/main entry
```

Key points:

- The exception vectors are at **`0x40000000`** (VBAR), so the image
  base is also the vector table. The IRQ/exception path is
  `FUN_400037e8`.
- Per-core stacks are at `0x3f010000 - (core_id << 11)`.
- The early boot writes a **boot parameter block** at
  `0x10000110..0x1000011d`:
  `0,1,0x11,1,1,0,0,1,0,1,0,0,1` (bytes).
- It writes `0x100003a0 = 1`, `0x10000060 = 2`, then calls
  `FUN_4000103c` (clock/PLL/DRAM strap setup) and jumps to
  `0x400037e8`.

`FUN_40001180` checks `0x10000100`, calls `FUN_400010a0` (early
SRAM/register init: writes `0x5615` to `0x3f006000..0x3f00603c`,
zeroes a range, writes `0x3f006400..0x3f00643c`), invalidates the
TLB at `0` and `0x3f000000`, invalidates the I-cache, then calls
`FUN_4000114c(0)`.

`FUN_4000147c` enables the MMU:
Auxiliary Control `0x6040`, TTBR0, TTBCR `0x21`, Domain Access `1`,
Control `_UNK_40001478` (MMU/caches).

`FUN_4000103c` sets clock registers (`0x7c`, `0x380`, `4`), reads a
strap byte at `0x40001872`, computes `(strap & 0x1f) << 0x1a` (or
`0x38000000`), and writes the result to `0x400017bc + …`.

## 2. RTOS/main entry: `0x400037e8`

`FUN_400037e8` is the main entry and exception path. It:

1. Reads `0x10000058 & 3` (boot/mailbox status) and selects a clock
   gate mask (`0` if status==2, else `2`).
2. Enables clock gates:
   - `FUN_4000b608(mask, 0)`
   - `FUN_4000b61c(0xfe0e, 0)`
   - `FUN_4000b630(0xfe0e, 0)`
   These are reference-counted clock-gate enable/disable functions
   that update `0x10000044/0x48/0x4c/0x64/0x68/0x6c`.
3. Calls the boot-mode dispatch `FUN_401c015c` (via the
   `switchD_401c017c` tail call).
4. Initializes:
   - `FUN_40001840` — TLB invalidate + I-cache invalidate.
   - `FUN_4000219c` — GIC/interrupt controller + timer table
     (199 interrupts; sets `0x1000000` at `DAT_40003dd0+0x118`).
   - `FUN_4001f898` — heap: `0x200000` (2 MB), sets
     `DAT_40020c4c+4 = 0x200000`.
5. Sets the system timer (`*puRam4001f8d8`), enables IRQs, and enters
   the ThreadX scheduler loop at `_LAB_40e38080` (current-thread
   pointer). The scheduler saves/restores the full register set and
   context-switches.

## 3. Boot-mode dispatch: `FUN_401c015c`

The boot mode is a field in the SoC register block at
**`0x10006000 + 0x6c`**:

- `FUN_40155e78`: `mode = (*(u32*)(0x1000606c) & 0x7f) >> 4`
  (bits 4–6, values 0–7).
- `FUN_40155e8c`: `sub = *(u32*)(0x1000606c) & 3` (bits 0–1).
- `FUN_40155e58(mode, sub)`: writes `sub | (mode << 4)` back,
  preserving bits `0x8c`.

`FUN_401c015c`:

```c
mode = FUN_40155e78();
sub  = FUN_40155e8c();
if (sub != 3) {
    FUN_401564b4();              // boot cleanup/teardown
    switch (mode) {
    case 0: clear bit0 at 0x10006068; FUN_40080ac8(0,0,0); break;
    case 1: clear bit0 at 0x100060cc; FUN_40080ac8(1,0,0); break;
    case 2..5: clear bit0 at 0x100060bc; FUN_40080ac8(2,0,0); break;
    }
    FUN_40080ac8(...);           // clock/power domain enable
}
FUN_40155e58(mode, 0);           // write mode back, sub=0
clear bit1 at 0x10005000+0x305;
FUN_4000f7b0(4,0);               // boot status: 0x10000040 = 4
```

`FUN_40080ac8(module, sub, mode)` is the clock/power-domain
controller: it looks up a per-module table at `DAT_40080ca4`,
enables/disables the listed clock gates (`FUN_4000b608/b61c/b630`)
and writes a hardware register via `FUN_4002a760(*puVar1, 0xe3, …)`.
The mode values 0/1/2–5 therefore select different clock/power
domains at boot; sub-mode 3 skips the reconfiguration.

`FUN_4000f7b0(a,b)` is a tiny boot-status setter:

```c
if (a & ~b) *(u8*)0x10000040 = a & ~b;
if (b & a)  *(u8*)0x10000060 = b & a;
```

So the dispatch ends with **boot status 4** at `0x10000040`.

### Boot-mode clock/power table

`FUN_40080ac8` indexes the descriptor table at `0x40aeaf98`
(pointer at `0x40080ca4`) by `(module*2 + sub)`. The three boot-mode
configurations used by `FUN_401c015c` are:

| Boot mode | Table index | Descriptor | Gate/power values |
| --- | --- | --- | --- |
| 0 | 0 | `0x40d65a18` | `0x80001100`, `2`, `2`, `0x80000` |
| 1 | 2 | `0x40d659d0` | `0x100`, `0x40`, `4`, `0x60` |
| 2–5 | 4 | `0x40d65988` | `0x100`, `0x80`, `2`, `0x1800000` |

The other descriptors (`0x40d659f4`, `0x40d659ac`, `0x40d65a3c`,
`0x40d65a48`) are the other module/sub-mode entries. Each entry is
a `(type, mask, value)` clock-gate triple; `FUN_40080ac8` writes
them via `FUN_4002a760` and the refcounted clock-gate functions
`FUN_4000b608`/`b61c`/`b630`. The base pointer `DAT_40080cb0` is
`0x40d65968`; the exact register offsets are not yet decoded.

The boot-stage table at `0x40000b00` (`DAT_4000f4fc`) is a compact
record table with a 6-word header and 16/24-bit offset records; the
`FUN_4000f458` walker uses it to mark/notify stages. Its exact
record encoding is open.

## 4. First task / main task

At `0x40003b40` there is a small trampoline:

```text
40003b40: ldr r0, =0x40003b80     ; pointer to the app-init vector
40003b44: pop {r3-r11,lr}
40003b48: b   0x4001f8cc          ; ThreadX scheduler start
```

The data at `0x40003b78`:

```text
40003b78: 0x777c0ea8
40003b7c: 0xfe72bddf
40003b80: 0x40003498   ; app init / main task entry
```

`FUN_4001f8cc` saves the pointer in `*_UNK_4001f8d8`, performs the
scheduler entry sequence, and starts the ThreadX scheduler. The
first thread is the main task; its entry is **`FUN_40003498`**.

## 5. Application init: `FUN_40003498`

`FUN_40003498` is the main task. It:

1. Initializes clocks/power:
   `FUN_4000b6d4(0x11)`, `FUN_4000dff4`, `FUN_400043d8`.
2. Logs via `FUN_4000e164`.
3. Checks the hardware ID: `*(u32*)0x10000324 == 0xd8`; asserts
   otherwise.
4. Calls early init:
   `FUN_4000ef40` (event group/semaphore + 0xff07),
   `FUN_4000f39c` (0x700-byte buffer/queue),
   `FUN_4000f5b8` (0x60-byte buffer),
   `FUN_4000459c(0, …, 7)`.
5. Branches on `*DAT_4000374c` (boot mode/flag):
   - 0: `FUN_4000fb9c` (storage/NAND init), `FUN_4000f51c(1)`,
     `FUN_4000f458(4)`.
   - else: `FUN_4000f458(0x20)`, `FUN_4000f7b0(1,1)`.
6. Calls `FUN_4000fd40` (VFS/FAT init: `FUN_4002dab4`,
   `FUN_4002dcac`, `FUN_4002fe88`, `FUN_40030da4(0xb, …)`, sets
   `0x2f/0x68/100/0x61`), then `FUN_4000f51c(4)`, `FUN_4000f458(5)`,
   `FUN_4000f550(...)`.
7. Runs the long driver/task init sequence:
   `FUN_40012da0`, `FUN_40013638`, `FUN_400189bc`, `FUN_4001b17c`,
   `FUN_4001c4e4(0x11)`, `FUN_4000459c(0,…,8)`, `FUN_4001cd00`, …
   with stage markers (`FUN_4000f458`/`FUN_4000f51c`) for
   0xc/0xd/0x15/0x16/0x17/0x18/0x1d/0x20.
8. Calls `FUN_4000f688(0)` and returns.

## 6. Boot-stage event group

Boot stages are synchronized as **event-group bits**:

- `FUN_4000f004(stage, …)` calls
  `FUN_4002a760(*DAT_4000f058, 1 << (stage-1), 0, …)` — sets bit
  `stage-1`.
- `FUN_4000f458(stage)` marks a stage; it walks the stage bitmap at
  `DAT_4000f4fc` and calls the stage callback (`piVar5[5]`).
- `FUN_4000f51c(stage)` checks/logs a stage bit.
- `FUN_4000f550()` calls the stage callback pointer.

The app init uses these to publish progress through the boot
sequence; other tasks can wait on the same event group.

## What this establishes

- The full **reset-to-main** path: reset `0x40000ec0` → early
  cache/TLB/MMU → VBAR `0x40000000` → clock/PLL `0x4000103c` →
  RTOS entry `0x400037e8` → boot-mode dispatch `0x401c015c` →
  scheduler `0x4001f8cc` → main task `0x40003498`.
- The **boot mode** is a 3-bit field at `0x1000606c` bits 4–6, with
  a 2-bit sub-mode; modes 0/1/2–5 configure different clock/power
  domains via `FUN_40080ac8`.
- The **boot status** handshake: `0x10000040` receives 4 from the
  dispatch; `0x10000060` is the alternate status byte.
- The **main task** is `FUN_40003498`, reached through the
  `0x40003b40` trampoline and the descriptor at `0x40003b80`.
- The app init order: clocks → event group → storage/NAND →
  VFS/FAT → drivers/tasks/UI/imaging, with event-bit stage markers.

## 7. Boot-status and DRAM layout selection

`FUN_400007f4` reads the boot-status halfword at **`0x10004054`**
(base `0x10004000`, offset `0x54`). `FUN_4000081c` consumes it:

```c
status = FUN_400007f4();          // 0x10004054
if ((status & 2) == 0) {          // FW1 selected
    base = *(u32*)0x3f00001c;
    size = *(u32*)0x3f000014;
} else {                          // FW2 selected
    base = *(u32*)0x3f000020;
    size = *(u32*)0x3f000018;
}
blocksz = *(u32*)0x3f00000c;
layout[0] = (blocksz * size) >> 9; // total in 512-byte sectors
layout[1] = (base + 0x1ff) >> 9;   // base in sectors
layout[2] = blocksz >> 9;          // block size in sectors
layout[3] = 0;
```

The layout struct is at **`0x40000ad0`** (four words, zero in the
image, filled at boot). The live boot-status read was `0x0100`:
bit 1 clear → **Firmware1 selected**, bit 7 clear, bit 8 set.

`FUN_400008e8` is a related trampoline: it adds `*0x40000ad0` to
`param_1`, sets `r3 = 0x3f000000`, and calls a relocated function
pointer (the literal is `0x00004e34`, relocated by the loader).

The existing SRAM dump at `0x3f000000` contains **boot-stub ARM
code**, not the DRAM parameter block, so the live parameter values
at `0x3f00000c/0x14/0x18/0x1c/0x20` are not in that dump. The
boot-info page dump at `0x10004000` remains the best source for the
status halfword; the DRAM parameter values would need a targeted
read of the live SRAM/DRAM alias.

## 8. `sysAppInit` stage names

`FUN_40003498` is the `sysAppInit` function (`main.c`). It logs
`sysAppInit: %x` and marks each stage with
`FUN_4000459c(0, name, len)` / `FUN_4000e164`:

| Log marker | What runs around it |
| --- | --- |
| `devInit` | clocks/power, event group, storage/NAND (`FUN_4000fb9c`), VFS/FAT (`FUN_4000fd40`) |
| `devInit1` | ME tokens (`tokME`/`semMeDone`/`tokExclME`), MOE HAL (`HAL_MOE1_Eventflag`, `halMoe1ATrig`/`BTrig`), IME HAL (`HAL_IME_Eventflag`, `HAL_IME_A/B_DONE`), HVC encoder (`tokenHVC`/`evtHvcCh`/`mutHvc`, base `0x1000c000`), primary display/OSD (`devInitPrimary`, `_trd_stg`, low-power/charge paths) |
| `devInit1 OK` | after `FUN_4001cd00` (primary device/display/OSD init) |
| `devInit2` | `FUN_4001caf0` = `devInitSecondary`, boot stages 0xd/0xe/0xf/0x10 |
| `devInit2 OK` | after `FUN_4001caf0` |
| `modeswInit` | `FUN_400d3a5c` — audio state init (0x54-byte state) |
| `sysgRes` | `FUN_400d3c58` — audio memory pool (<0x400-byte entries, 0xc-byte records) |
| `cdspBootInit` | `FUN_400d3ecc`/`FUN_400d4074`/`FUN_400d4ae4` — CDSP/audio DSP boot: `0x1000d000`, 4 channels, per-channel DMA handles, sample table |
| `resDone` | after `FUN_4000f51c(0x17)` |
| `appInit` | `FUN_40080d6c` (audio hardware init, `hal_aud`/`audHwInUsed`, base `0x10006000`) + `FUN_400f86f4` (`appInit`, application/UI init, `MULTI_BOOT_STAGE_CMD`) |
| `appInit OK` | after `FUN_400f86f4` |
| `appInit 2 OK` | after `FUN_400f8914` (`appInitDelay`, deferred network/WiFi/BT init) |

The boot-stage event bits used through this sequence are
`1 << (stage-1)` for stages 5, 6, 9, 10, 0xb, 0xc, 0xd, 0x15, 0x16,
0x17, 0x18, 0x1d, 0x20.

The `FUN_400d*` block is the **CDSP/audio** init: `FUN_400d3a5c`
allocates the 0x54-byte audio state; `FUN_400d3c58` builds the audio
memory pool; `FUN_400d3ecc`/`FUN_400d4074`/`FUN_400d4ae4` bring up
the `0x1000d000` DSP block, four channels, per-channel DMA handles
and the sample table. `FUN_40080d6c` is the **audio hardware init**
(`hal_aud`/`audHwInUsed`, base `0x10006000`); `FUN_400f86f4` is
**`appInit`** (application/UI init, `MULTI_BOOT_STAGE_CMD`); and
`FUN_400f8914` is **`appInitDelay`** (deferred network/WiFi/BT
init). The non-zero branch (`*DAT_4000374c != 0`) runs
`FUN_401efc90` (debug/loader path) instead of the full
`devInit2`/app-init sequence.

## 9. Secondary-core AMP boot (CPU1)

`FUN_4000148c` is the CPU1 `amp_boot_main`:

- Reads MPIDR; core 0 logs `Enter error func` and returns.
- Logs `[CPU1]amp_boot_main`.
- Waits on the mailbox at **`0x40004000`** (core-specific base) and
  loops on the command word:
  - `0x20b` → `FUN_40007e58()`, log `Fail`, halt.
  - `0x208` → `FUN_4000826c()`, continue.
  - `0x200` → log `[CPU1]AMP BOOT ...`, proceed to boot.
  - `0x201` → log `[CPU1]wait cpu0 wakeup cpu1 to power on ...`,
    `WFE`/`DSB`, wait for `0x201`, then boot.
- Boot setup:
  - image base = `*(u32*)0x40e37c5c + 0x1000000` (the static word
    is a loader-patched placeholder, not the runtime base)
  - writes `*(u32*)base = base + 0x1006e40` (entry)
  - parameter block at `base+0x44` = entry + `0x1900`
  - magic at `base+0x40` = `0xd0b`
  - core config at `base+0x30`
  - builds **ATAGs** at the parameter block:
    - `ATAG_CORE` `0x54410001`
    - `ATAG_CMDLINE` `0x54410009`
  - command line:
    `console=ttyS0,115200 no_console_suspend=1 mem=%dM icatmem=%dM sharemem=%dM`
  - cache clean/invalidate via `FUN_40002bc4` for the image, ATAG
    block, entry+`0x8000`, and config
  - disables IRQ/FIQ, calls `FUN_400026f0`, writes `0x300` to the
    mailbox, disables the MMU/caches (CP15 control `& ~5`), sets the
    secondary stacks, then `blx entry` with `r0=0`, `r1=0xd0b`,
    `r2=ATAG`.
  - if the entry returns: log `Fail`, halt.

This is a classic **AMP Linux boot**: the ThreadX primary core starts
a secondary Linux image on CPU1 with ATAGs and a serial console. The
mailbox commands `0x200`/`0x201`/`0x208`/`0x20b` are the inter-core
boot protocol.

## 10. Staged loader entry (`+0x200`)

`FUN_40000338` is the main image's loader-facing `+0x200` entry
(`0x40000338`). It:

1. Calls `FUN_4000081c()` — the boot-status/layout selector.
2. Reads the section table from the SRAM-copied header
   (`_UNK_40000798`) and the loader state at `_UNK_40000790`.
3. Validates each section (checksum), copies it to its destination,
   zeroes the BSS, and waits for the hardware handshake
   `0x10000911 & 0xf0 == 0xe0`.
4. Jumps to the loaded entry (`pcVar12 = entry`).

Failure paths log and call the fallback (`_UNK_400007f0`); one
branch sets `0x1000003c = 1` (reboot). The section table uses the
same NAND header layout documented in
[boot-info-block-and-updater-sdboot.md](boot-info-block-and-updater-sdboot.md):
signature/type, block size, FW1/FW2 starts and lengths, checksums.

This is the **loader handoff** from the earlier stage: the earlier
stage loads the main image (or updater), and this entry validates the
sections against the header copy before transferring control.

## 11. Loader-facing `+0x200` stub and gate stamp

The `+0x200` entry is a small stub at `0x40000200`:

```text
40000200: mov  r0,#0x10000000
40000204: orr  r2,r0,#0x1000       ; r2 = 0x10001000
40000208: orr  r3,r0,#0x5100       ; r3 = 0x10005100
4000020c: mov  r1,#0x30
40000210: strb r1,[r3,#0x4]        ; 0x10005104 = 0x30
40000214: mov  r1,#0x1f
40000218: strb r1,[r0,#0x20]       ; 0x10000020 = 0x1f
4000021c: strb r1,[r0,#0x21]       ; 0x10000021 = 0x1f
40000220: movw r1,#0x5678
40000224: movt r1,#0x1234
40000228: str  r1,[r2,#0x300]      ; 0x10001300 = 0x12345678
4000022c: b    0x40000338          ; staged loader
```

So the main image's loader-facing entry stamps its identity
(`0x12345678`) into the gate word at `0x10001300`, sets
`0x10005104 = 0x30` and `0x10000020/0x21 = 0x1f`, then jumps to the
staged loader `0x40000338`. The updater's equivalent stamps
`0x88888888`; the earlier stage reads the gate to decide whether to
skip automatic file handling.

## 12. Boot-info handoff contract (earlier stage → updater/main)

The earlier stage (mask ROM/SPBoot) writes a small block at
`0x10004000`/`0x10001000`; the updater's `FUN_400009c4` consumes it:

| Field | Address | Meaning |
| --- | --- | --- |
| Gate | `0x10001300` | `0x12345678` = main image stamp; `0x88888888` = updater stamp; `0` = skip automatic file handling |
| Status | `0x10004054` | bit 1 = FW2 selected; bits 4–6 = image class; bit 7 = selection valid |
| Button timing | `0x100040b4..b8` | hold-time / DRAM parameter bytes; decoded and applied via `func_0x400165d0`/`0x400165e0`/`0x400166b8` |
| Mailbox | `0x100040b9..c0` | 8-char basename |
| Mailbox ext | `0x100040c1..c3` | 3-char extension |
| Backend | `0x10000058 & 0xe0` | `0x00/0x20/0x40` → NAND registration paths; `0x80/0xa0/0xc0` → other media |

Updater flow (`FUN_400009c4`):

- Reads the gate/status; if the gate is nonzero, decodes the button
  timing and DRAM parameters.
- Backend select: `0x00` → `func_0x4002bde8(1)`, `0x40` → `(2)`,
  `0x20` → `func_0x4001be6c`, `0x80` → `func_0x4001baa8`,
  `0xa0` → `func_0x4001d7f0`, `0xc0` → `func_0x4001dbe8`.
- Builds a pathname: drive 5 (`G:`) or 2 (`D:`), mailbox
  basename/extension, default `firmware.brn`.
- If the file exists: `.brn` → `func_0x4000fc70` (card update) and
  reboot (`0x1000003c = 1`); otherwise `FUN_4000f270(path,0)`
  load-and-boot.
- If no file: `func_0x40075ea4` (shutdown/continue).

The producer of the button/mailbox/DRAM fields is the earlier
stage/ROM; the main image does not reference
`0x100040b4`/`0x100040b9`/`0x10000058` directly. The main image
consumes only the boot-status halfword (`0x10004054`) and the gate
stamp (`0x10001300`).

## Open questions

1. The exact boot-mode table: which modes 0–5 correspond to which
   clock/power domain sets (`FUN_40080ac8` table at `DAT_40080ca4`).
2. The full app-init stage list and the driver/task init order
   (`FUN_40012da0`, `FUN_40013638`, `FUN_400189bc`, `FUN_4001b17c`,
   `FUN_4001c4e4`, `FUN_4001cd00`, …).
3. The descriptor at `0x40003b80`: the two words at +0/+4 and how
   the first thread context is consumed.
4. The boot-info block at `0x10004000` and the `0x10000058` status
   reads; how they relate to the boot mode and slot selection.
5. The secondary-core path `FUN_4000148c` (AMP boot mailbox at
   `0x40004000`, commands 0x200/0x201/0x208/0x20b).

## Boundary

The reset disassembly, addresses, boot-mode field, boot-status
writes, main-task pointer and app-init call sequence are observed in
the official image. The boot-mode table values, the full driver/task
order, and the live boot-info block contents are not yet observed.
