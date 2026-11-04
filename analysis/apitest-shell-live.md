# APITEST shell bridge live: console active, sensor identified

Date: 2026-11-04. Loader session via the established entry; APITEST work
through `analysis/xhalf_loader_linux.py` in the UTM/Linux VM. Design:
[apitest-shell-bridge-design.md](apitest-shell-bridge-design.md).

## What worked

1. **Activation**: DRAM poke of APITEST mode word (`0x40fc8288`) to 2,
   then the `[0x01]` case ran `init(0)` — queue `0x41844900`, buffers
   `0x42e757c0`/`0x4186e700` allocated, mode 2 confirmed by state reads.
2. **First queue feed**: `zztest\n` chars were enqueued (callback count
   incremented) and found *unconsumed* in the queue ring
   (`7a 7a 74 65 73 74 0a` as word elements at `0x4184493c`). Diagnosis:
   `init(0)` had run first, so `init(1)` early-exited before installing
   the shell input/output hooks. Sequence matters.
3. **Corrected sequence** (after a wedged attempt that needed a battery
   pull — `init(1)`'s output-hook installation deadlocks if the host is
   not draining device output): teardown → poke mode 2 → `[0x00]` case
   with a **bulk/interrupt drain running** → full `init(1)`. The shell
   task woke and began emitting the **`cmd>`** prompt, one character per
   interrupt-IN record (`00 01 01 <char>`) on EP `0x84`.
4. **Console decoded**: EP `0x84` carries the firmware's diagnostic log
   plus shell output as 4-byte records. A 20-second capture included
   preview logs and a full system dump (threads/queues/heap) from shell
   activity.

## Sensor facts (from the console stream, verbatim)

```text
$unp_tg_sonyimx283_mipi-00.01.40: dev 0, strm 0 (0), cur:0x32
pv:32, snap:46, BAYER_RGGB, gate:on
sz(1624,408), ratio(4872,3672)
[Cfg]Raw_0 ... RAW_16, num:4
[Act]Raw_0, buf(1624,408,...) RAW_16
```

So: **Sony IMX283** (1-inch), **RGGB Bayer**, **RAW_16**, full ratio
**4872×3672**, preview raw 1624×408. This pins most of the DNG-conversion
parameters before we have a single file. The stream also hex-dumped a
live raw buffer (`dump[41c8ab40] sz 0x12c0`) with smooth 16-bit gradient
values — the preview pipeline's raw frames are exactly what `pvraw`
writes to disk.

## What did not work / hazards found

- **`init(1)` without a host drain wedges USB** (control-level; battery
  pull recovered). With a drain running, the same sequence succeeded.
  Rule: always drain EP `0x84` (and bulk IN) from before `init(1)` until
  session end.
- **Transfers get slow after shell activation**: control/bulk operations
  that took milliseconds began timing out at 3 s; some data arrived tens
  of seconds late. The receiver phase state can desync after a timed-out
  transfer (later `0xfa` returned a USB stall and the console went
  quiet). A dedicated console client is needed: one process owning the
  interface, EP `0x84` reader thread logging continuously, long
  (30 s) control timeouts, and **no `0x04f2` bulk reads while the shell
  is active** (they contend with the console machinery).
- Memory reads of the queue object confirmed char elements are 4-byte
  words; queue/hook state is observable via `0x40fc8270` when bulk reads
  do work.

## Status

The shell bridge **activates** and the Cmd task consumes queue input in
its own context (prompt emitted, system dump produced). A deliberate,
acknowledged command/response round-trip (`zz9`) could not be completed
before the receiver desynced — that is the next session's first task,
with the console client above. After that: `rcaraw` syntax probe, then
selector 17 (`pvraw 2 1 1`) for a first preview RAW file. All effects
this session were in one boot's RAM/OS state; card markers removed,
camera verified normal afterwards (usual check).
