# Updater service read-only probe: can the RAM-pushed updater answer `0x04f2`?

Date: 2026-11-06. Offline preparation only; no camera access.
Follow-up to the P3 diagnosis: the updater ran with `MAIN.BIN`
verified and still settled into service mode, so the next useful
observable is the updater's own state, not another push.

## Why this is worth trying

The earlier P2/P3 sessions tried the `0x70` bootquery against the
RAM-pushed updater and it timed out. That is the **boot-method
query**, a different request from the `0xfd/0x04f2` DRAM read. The
updater's USB control task (`0x4001a338`) dispatches vendor message
types to:

| Type | Handler | Shape |
| --- | --- | --- |
| 9 | `0x4001059c` | takes `[msg+8]`, posts a type-10 message |
| 11 | `0x40010678` | takes `[msg+4]`, `[msg+8]`, calls `0x40075138` (USB send path) |
| 13 | `0x400106cc` | takes `[msg+4]`, `[msg+8]`, calls `0x40074aac` (USB receive path) |

Type 11 is the best candidate for the `0x04f2` read: `0x40075138`
programs the USB controller and sends a buffer, and the handler's
two words match the read payload `(address, length)`. Type 13 is
the likely `0x04f5` write; type 9 is the likely `0x04f3` download.
The mapping is a hypothesis, not a proven request table; the live
read of `0x40000000` is the test.

## The probe

The client's `--updater-probe` mode performs four read-only,
32-byte-aligned DRAM reads and stops at the first failure:

| Address | Length | Why |
| --- | --- | --- |
| `0x40000000` | `0x100` | reset/vector; compare with the stock updater's first `0x100` bytes. A match proves the service is the stock image and the read dialect works |
| `0x4009d6a0` | `0x20` | console/log configuration; `0x4009d6b0` gates the auxiliary console service |
| `0x400a2980` | `0x40` | log task state; buffer pointer at `0x400a29ac + 0x8` |
| `0x400ba7c0` | `0x40` | log state at `0x400ba7d8` (dump method and pointers) |

All four are DRAM. Do **not** use `0x04f2` for SRAM or MMIO
(`0x3f...`, `0x1000...`): the live record shows those sources wedge
the receiver ([loader-memory-read-live.md](loader-memory-read-live.md)).
The boot-info page must come from the loader's trampoline stage
before the push, not from the updater's read.

## Live result (2026-11-06): the updater does not answer `0x04f2`

The updater was RAM-pushed successfully and enumerated with its
3-interface service personality (interface 0, interface 2 alt
settings, interface 5 with 3 endpoints; descriptor saved outside git
as `/private/tmp/xhalf-updater-service-descriptors-2026-11-06.txt`,
SHA-256 `f0100172…`). The first probe read,
`0x40000000 + 0x100`, timed out at the control transfer; the probe
stopped as designed.

**The updater's service personality does not speak the loader's
`0x04f2` read family.** The `0x70` bootquery timeout was not a
one-off; the read-only debug-port idea is closed for this dialect.
The control path may be desynced after the timeout; recover by
power cycle. The next P3 diagnostic is the card/FAT test, not
another read attempt.

## Safety rules

- Read-only: no `0x04f5` writes, no `0x04f7` run, no storage or
  erase commands.
- One attempt per boot. If the first read times out, stop; later
  reads would run against a desynced receiver.
- The updater is already in service mode after a successful P3
  attempt; the camera will need a power cycle anyway. Do not send a
  second control command after a timeout.
- If the read works, save the returned blocks outside git and
  record the hashes.

## Interpretation

- **`0x40000000` matches the stock updater**: the service speaks
  the read dialect. Read the log state; if `0x400ba7d8` has a
  buffer pointer, read the aligned buffer and decode the text. The
  consumer's failure strings (`SD1 detect fail`, `SD2 detect fail`,
  `%s file open error`, `FW boot error`) would identify the P3
  branch directly.
- **`0x40000000` reads but the log state is zero**: the log service
  is not running in this personality (the `Cmd` task is only started
  by the `.brn` path or the `isp` command). The storage/mount state
  globals may still be readable; the drive-5/backend question is
  better answered by the loader's `0x10000058` read.
- **The first read times out**: the service does not speak the
  loader dialect. Fall back to the EP `0x84`/`0x87` drain or the
  loader-side `0x10000058` read; do not retry in the same boot.

## Boundary

This is a request-shape hypothesis plus a prepared client mode. No
camera was accessed, no updater command was sent, and no live result
is claimed. The `0x04f2` read family is proven on the main image's
loader, not on the updater's service personality.
