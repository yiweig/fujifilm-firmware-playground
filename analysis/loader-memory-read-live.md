# Loader protocol live: boot query and DRAM memory reads

Date: 2026-11-04 (later session, after [live entry](calibration-loader-entry-live.md)).
Loader reached again via `EnableTest.txt` + MOVIE/flash-ON/PLAY-hold; the
user additionally observed (phone recording) a briefly flashed `CLBT INFO`
screen listing `FOCUS (Infinity)`, `OB CLBT (BLACK)`, `WB/AGC (LV10)` before
the diagnostic list screen. All loader traffic ran from
[`xhalf_loader_linux.py`](xhalf_loader_linux.py) inside the UTM/Linux
passthrough VM (interface 5, bulk IN `0x82` / OUT `0x03`), with usbmon
captures for the early exchanges. The client encodes only the `0x70` boot
query and the `0x04f2` memory-read branch; no write/upload/execute operation
exists in it. Exit between sessions was power-off or battery removal.

## Boot-method query

`0x70` IN with `wValue=wIndex=0` (zero size, skipping the allocation
branch) returned method byte **2** (running firmware, matching the static
trace at `0x401e1548`). Bytes 1–4 are stale buffer content on this path
(`0xffffffff` in fresh sessions, later `0x10400000`) and must not be
interpreted as an address for a zero-size query.

## Memory-read behavior: the working rule

Evidence across four loader sessions (usbmon retained in the VM for the
first exchanges):

| Request | Result |
| --- | --- |
| DRAM `0x40000000` len 16 | **OK**, bytes match packaged image |
| DRAM `0x40000020`, `0x40000040`, `0x40000060` len 16 | **OK** |
| DRAM `0x40000100` len 256; `0x40000200` len 4096; many len-65536 chunks | **OK** |
| DRAM `0x40000010`, `0x40000030` len 16 (repeated) | Bulk-IN **timeout** (receiver recovers for control transfers; later aligned reads succeed) |
| SRAM `0x3f000000` len 16/256 | Timeout, then **control-level wedge** |
| Low memory `0x00000000` len 8192; `0x00000b00` len 16 | Timeout / wedge |

Working rule (mechanism unresolved): **single reads succeed when the
address is 32-byte-aligned DRAM; 16-mod-32 DRAM offsets bulk-timeout;
SRAM and low-memory sources hang the USB task** (presumably the sender's
DMA source restrictions — its routine at `0x40056ac0` has both a CPU FIFO
branch and a DMA branch using `addr & 31`). This refines the offline
parserfinding that the receiver forwards any address: acceptance by the
parser does not mean the hardware transfer completes.

Recovery behavior: after bulk timeouts, control transfers (boot query)
answer again after the device-side timeout, and aligned reads succeed.
After SRAM/low-memory attempts, the receiver stops answering entirely and
the camera ignores the power switch (software-handled) and USB unplug;
**battery removal is the reliable reset**. When the receiver is not
wedged, the power switch turns the camera off normally in this mode.
Failed reads caused no persistent effect: the post-session record check
below and normal operation confirm it.

## Full DRAM dump

Using 64 KiB single transfers at aligned addresses (pre-submitting the
bulk read in a thread before the setup), the complete span
`0x40000000..0x41400000` (20 MiB) was read in one loader session without a
failure (~110 KiB/s, 320 transfers):

- SHA-256 `0662762803867a5762b6045a43cd1e6d3505dd9a962f238911b0d2548d1ccf5e`
- Copies: VM `~/dram-full.bin`, Mac
  `/private/tmp/xhalf-dram-full-2026-11-04.bin` (outside git).
- Image region differs from the packaged image in 9,139 of 14,606,848
  bytes (0.06%), concentrated in the loader-written header block
  (`0xa00..0x1000`, containing an `NAND RSV` header) and runtime-modified
  data — the dump is faithful, and code matches.
- Cross-validation: the factory record bytes inside this dump at
  `0x40e4df02` hash to `5b8060e6…`, exactly the record acquired through
  the independent factory-PTP path after entry #1. Two unrelated read
  paths agree byte-for-byte.
- Live calibration selection `[0x413e3d9c]` in the dump is 0, as emulated.

## Repeat entry is a record no-op

After this session's entry (#2 overall), the factory record was re-read
through the PTP path: **byte-identical** to the post-entry-#1 record
(`5b8060e6…`). The version fields already read 2,0,6, so the repeated
initialization save changed nothing, as predicted. (Procedure note: on a
fresh boot the first `OpenSession` once returned `0x201e`; a subsequent
open/probe/close cycle followed by acquisition succeeded.)

## Status and next

- R1's receiver is now usable for **aligned-DRAM reads at up to 64 KiB per
  transfer** — enough to dump all runtime RAM in minutes. SRAM and low
  memory remain unreadable through `0x04f2`; R2's helper bytes are still
  not in hand. Offline analysis of the DRAM dump (retained boot info,
  function-pointer tables, staged-descriptor copies) is the next source
  to mine before risking other branches.
- The `0x04f5` host-to-memory branch, APITEST shell bridge (`0xfa`), and
  upload/run (`0x04f7`) remain untouched. Write/readback experiments need
  a scratch-RAM candidate identified from the dump first.
