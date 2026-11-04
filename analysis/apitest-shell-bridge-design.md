# APITEST shell bridge: protocol trace and live design

Date: 2026-11-04. Offline static analysis (main image
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`).
Goal: a host-fed shell inside loader mode, as the safe route to the
engineering RAW commands. No camera access in this pass.

## How the bridge works (traced)

Target-0 receiver `0x401e1280` routes OUT `bRequest=0xfa` (phase 3) to the
APITEST callback `0x401c1550` **only when** the APITEST state word
`[0x40fc8270+0x18] == 2`. Callback arguments are
`(wValue, wIndex, wLength, payload_buffer)`:

| Case (wIndex=0) | Effect |
| --- | --- |
| `wValue=0, wLength=1, payload=[0x00]` | state := 1; `init(1)` — allocates queue/buffers, installs **shell-input callback** `0x401c0fd4` (queue-fed) plus output hooks |
| `wValue=0, wLength=1, payload=[0x01]` | state := 2; `init(0)` — allocates queue/buffers and output hooks, no input callback |
| `wValue=0, wLength=N>1` (or 1 with other byte) | enqueue the N payload bytes via `0x401c0f58` into the shell-input queue |
| `wValue=2` | teardown `0x401c1314` |
| `wValue=1` or `0xfff`, or any `wIndex!=0` | delegate to the registered `VC_Interpreter` (`[state+4]`, live value `0x40234cfc`) |

Shell output flows through hooks `0x401c1534` (putchar) → `0x401c14c8`
(string) → worker `0x401c1404`, which appends to a DRAM buffer referenced
by the APITEST state. The shell task (Cmd) consumes the queue in its own
context — commands never execute in USB dispatch context.

A separate bootstrap request exists in the receiver's default case:
`bRequest=0xdf, wIndex=0xffff, payload[0]=0xff` calls `init(0)` directly.

**Bootstrap paradox (static):** the only writer of `state+0x18 = 2` is the
callback itself, which the receiver only calls when the state already is
2. The vendor tool presumably resolved this through the forwarded-request
interpreter path (`0x401c17c4` → queue → `VC_Interpreter`), which was not
fully traced. We do not need to resolve it: with the loader write
primitive we can set the state word directly in DRAM (volatile) and then
use the designed cases. Live state inspection (`0x40fc8270` block reads)
gives per-step feedback the original tool never had.

## Live design (next session, in order, abort on any stall)

1. **Baseline:** loader entry; read APITEST state block (expect
   `+0x18=0`, interpreter pointer present, queue null).
2. **Memory-assisted activation:** read-modify-write the aligned block at
   `0x40fc8280` setting `+0x18=2`. Nothing else changed.
3. Send `[0x01]` case → `init(0)` runs in its designed context. Read
   state: queue/buffer pointers should now be allocated.
4. Send `[0x00]` case → state=1, `init(1)` installs the input callback.
5. Memory-write `+0x18=2` again (case in step 4 reset it to 1).
6. **Harmless first command:** feed `zztest\n` (wValue=0, wIndex=0,
   wLength=7). Wait 2 s. Read the state-referenced output buffer(s) via
   memory reads and search for shell response text (unknown-command
   reply/prompt). Also re-read state/queue fields. Success here proves
   input → shell-task execution → output, with a nonsense command that
   cannot change anything.
7. **RAW-command probe:** feed an argument-less/invalid `rcaraw` (or
   `pvraw`) line only if step 6 is clean, to capture its usage/error
   behavior. No capture yet.
8. Restore is power-off (all of this is DRAM/OS state in one boot);
   remove the card marker; verify normal operation.

## Why this is the safest shell route

- Command *execution* happens in the existing Cmd task, exactly as if
  typed on the vendor's own test jig; our USB-side code only queues
  characters through the firmware's own functions.
- First command is deliberately unknown (no handler, no side effects
  beyond printing an error).
- The eventual capture commands are the stock engineering ones
  ([rcaraw handler](rcaraw-handler.md)): selector **17** runs
  `pvraw 2 1 1` (preview raw, no shutter), selector **19** runs
  `snap attr raw=1; snap cap` (still capture). Preview first.

## Open items to resolve live (observationally)

- Exact output buffer location/length and whether output also requires
  the IN-side `0xfa` handling (setup-phase cases at `0x401e1684` copy
  tables for sub-selectors 3/12) — memory reads after step 6 settle it.
- Whether `init()` from dispatch context behaves (it is designed to be
  called from this callback, so this is its intended context).
- `rcaraw` argument requirements (destination/name arg) — from its
  error output and the handler trace, before any real capture.
