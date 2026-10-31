# Shell access and USB lifecycle

Date: 2026-10-31. Official firmware 2.00, main-image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Runtime base `0x40000000`; static analysis only, camera not accessed.

## Results

The application starts a shell task whose ordinary input uses UART support.
An additional API-test USB character bridge exists in the target-0 receiver
that implements the RAM loader. This adds a USB shell transport alongside
the previously traced UVC RCA service, but does not establish access through
the retail PTP connection.

The `sys` command's case 17 is a shell-only registration path, not a discovered
boot event. The USB transition also performs conditional filesystem remounts.
The direct `clbt usb` branch's RAM changes therefore do not prove that the
complete transition is free of storage side effects.

## Existing shell task and UART input

The function identified by its embedded `cmdTaskInit` string at `0x401efc90`
creates task `Cmd` with entry `0x401efc38`. Application startup at
`0x400f8750..58` selects a 16-KiB stack then calls it. Another call at
`0x4000360c` is behind an early-startup flag. Neither establishes a USB console.

The task reaches this input loop:

```text
0x401efb1c: obtain a line and dispatch it
  -> 0x401ef994: character input / line handling
     -> 0x4002a650: installed input callback
  -> 0x401efacc: shell parser and command dispatch
```

`0x4002a650` calls the pointer at `0x40e4b3b8`, or returns -1 if null.
The only direct setter call found for `0x4002a688` is at `0x4000e42c` in the
routine named `uartInit` by its own string. For channel zero it installs
`0x4000e5c8`. That wrapper uses an override at `0x40e49124` if set;
otherwise it calls the channel-zero UART receive path at `0x4000e55c`.

Another shell loop, `0x401efb48`, installs override `0x40296bb8` through
`0x4000e620`. This override polls status bit 1 at `0x10000911`, reads a byte
from `0x1000091c`, and translates CR to LF. Nearby `0x40296be4` drains the
same input registers. This is direct peripheral input, not a USB/TCP parser.
Exact board pin routing is unknown. The user has excluded disassembly;
this finding is not a proposed UART hardware test.

Reference scans cover aligned ARM branches and literal pointers, not every
possible computed write to these globals.

## `appUvcThreadCreate` is behind the shell

The command-table triple at `0x40e17ff8` contains:

| Field | Value | Meaning |
| --- | --- | --- |
| command | `0x40ce2adc` | `sys` |
| description | `0x40ce55c2` | `Deb_System` |
| handler | `0x40673548` | numeric subcommand switch |

The handler reads the selector from the shell numeric argument array at
offset `+4`, bounds-checks against 38, and uses jump table `0x40673570`.
Index 17 reaches `0x40673990`, calls `0x405e6828(0)`, and logs
`appUvcThreadCreate`.

As previously traced, `0x405e6828` registers `uvctestcmd`. The log does not
prove that a UVC thread starts here. Case 17 is not an application event ID;
executing `sys` already requires shell access. The separate calibration
caller remains as documented in [UVC activation](uvc-service-mode-activation.md).

## API-test USB character bridge

Code carrying the source-path string `vndr/usb_vndr_apitest.c` uses state
object `0x40fc8270`. Initializer `0x401c1130` creates `USB-APITEST-QUE`,
allocates 16-KiB and 64-KiB buffers, and, when its argument is nonzero,
installs input callback `0x401c0fd4` through `0x4000e5f8`. This setter
stores the UART wrapper's override and wakes channel-zero input. Additional
calls install output hooks (`0x401c1534`, `0x401c14c8`).

`0x401c0fd4` waits for a queue element and returns it as character input.
`0x401c0f58` enqueues a supplied character. Thus the connection to shell
parsing is a character stream through existing input handling, rather than
a direct call to `0x401efacc`.

The USB command callback begins at **`0x401c1550`**, four bytes before its
push instruction. The first instruction loads the state base into `r12`;
starting analysis at `0x401c1554` misses this dependency. Its index-zero path
can initialize bridge state from a one-byte control payload, enqueue supplied
bytes, or call teardown `0x401c1314`. Other indices delegate to an optional
callback. The complete protocol/state sequence is not audited for live use.

Target-0 receiver `0x401e1280` dispatches OUT request `0xfa` after data
arrival: comparison `0x401e1a2c`, branch `0x401e1e2c`. It calls
`0x401c1550` only when `0x401c1680()` returns 2. Another branch can initialize
the API-test subsystem via `0x401c1130(0)` at `0x401e1e98`. These operations
change state; they are not passive identification queries.

Target-0 initializer `0x401e122c` calls bridge teardown `0x401c1314`, which
clears active input/output hooks and frees its queue/buffers. Re-entry,
pending input, and interactions with other consumers still need auditing.

### Mode selection still matters

Registration `0x401e2104` installs `0x401e1280` as target 0's callback at
table offset `+0x54`. Central event dispatch at `0x401ba0d4..e4` indexes
that array using the current target byte at `0x40fc7b8c`. USB API mode 4
sets target zero; SIDC/PTP mode maps to target 2. This inspected dispatch
does not route the bridge simply because its code exists in the image.
Other possible dispatch paths are not exhaustively ruled out. Do not probe
request `0xfa` on the retail camera based on this finding.

## USB disconnect/reconnect audit

`0x4038d764` uses runtime state rooted at `0x4138e5c4`. Connection startup
initializes USB infrastructure through `0x401e024c` when needed, enables the
controller, records connection state, and creates synchronization objects.
Shutdown stops the personality, tears down infrastructure, and frees buffers.
Two inspected cleanup helpers are:

- `0x401df1b8`: conditionally releases a buffer through `0x40080420` and
  clears its pointer. That wrapper reaches `0x40020ecc` after an allocation check.
- `0x402e0d2c`: conditionally frees two pointers through `0x401e0718`, which
  reaches `0x40020ecc`, and clears the pointers.

Storage helper `0x4038ec7c` runs before connection startup (`0x4038d794`)
and near the end of shutdown (`0x4038d970`). It:

1. calls `0x40011fc4(0, 0)` to clear a runtime 15-byte LUN/mount filter;
2. walks a count and flag array at `0x4138e5e8`;
3. for each flagged entry, calls `0x4000fe24(id, 0)`, obtaining IDs from
   `0x40e0128c`.

`0x4000fe24` identifies itself through `vfsMount` and `mount fail!` strings
and calls the lower filesystem mount path at `0x4002fc68`. This is more than
a descriptor update. Active entries depend on prior runtime state. No
mount-induced write or calibration change has been demonstrated, but mount
callbacks, pending I/O, and controller teardown are not fully audited.
Absence of persistent side effects remains unproved.

## Next work and reproduction

The useful next targets are writers of boot/service selection state,
factory-resource initialization, and earlier boot-stage USB selection.
The SD command-file reader still lacks a known caller. The UVC path still
has unresolved activation prerequisites. A shell inside the loader personality
does not itself solve initial mode entry or independent recovery.

Use `analysis/fw.py disasm`, `literals`, and `callers` at the addresses above.
Focused Ghidra 12.1.4 ARMv7 pseudocode was exported with
`analysis/scripts/QueryFirmware.java` for `401efb1c`, `4031a820`, `40673548`,
`405cbb84`, `4038d764`, `401df1b8`, `402e0d2c`, `4038ec7c`, `401e024c`,
`4002a650`, `401efc90`, `4000e620`, `40517454`, and `405219a0`.
Disassembly resolves missing leaf functions and indirect targets. Existing
local Ghidra was used because of the documented REA raw-import mapping issue.
No live test script or card marker is prepared by this report.
