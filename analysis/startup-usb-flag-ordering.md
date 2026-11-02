# Startup ordering of the temporary zero USB flag

Date: 2026-11-01. Offline analysis of official 2.00 main image, runtime base
`0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
No camera access or firmware changes.

## Result

The initial zero in cached USB-selection flag `0x40e6696d` is overwritten from
the shared cold-data setting within the same startup event handler that clears
it. The normal application loop receives its next message after that handler
returns. While UI state is zero, the inspected group handler immediately
returns for both the USB event and `StartMain`.

The one nonzero message-filter callback found through the explicit setter is
a short PTP/IP filter. It returns a disposition without calling the UI
dispatcher. With that callback or a null filter, posting `StartMain` or the
USB event submits to the queue; it does not synchronously run their handlers.

These findings weaken the idea of exploiting the initial zero by timing a
cable connection. They **do not prove a race impossible**: startup changes
state to 1 before refreshing the flag, other tasks/interrupts are not modeled,
and the complete call tree between initialization and refresh is not audited.
No usable temporary loader-entry sequence was established.

## Same startup event, ordered assignments

Application dispatcher `0x40400d50` handles internal event `0x10` at
`0x40400f84`. In its inspected synchronous path:

| Call/instruction | Result relevant to the loader gate |
| --- | --- |
| `0x404010bc -> 0x404d4f50` | UI initialization wrapper |
| `0x404d4f90 -> 0x404d4e48` | Tail-call the field initializer |
| `0x404d4e58` | Set UI state `0x40e66958` to 0 |
| `0x404d4e7c` | Clear USB-selection flag `0x40e6696d` |
| `0x40401264 -> 0x40517454` | Call power-on processing, still inside event `0x10` |
| `0x40517488` | Change UI state to 1 |
| `0x405174a8 -> 0x40125284` | Read shared cold-data halfword `0x40e4df6c` |
| `0x405174c0` | Store the inverse of the getter in cached selection flag |
| `0x40517910` | On the normal completion branch, post `StartMain` (`0xf2000000`) |

The getter returns zero if the setting's low nibble is 8, otherwise 1. Thus
the cached byte becomes **1 for low nibble 8, 0 for other nibbles**, regardless
of its old value. This uses the current shared cold-data buffer; it is not
proof of physical-storage freshness or of the camera's actual saved value.
Intervening initialization and optional initial-setup branches are not executed
end to end by this investigation. The event `0x10` is an internal application
event, not a host USB request or a proposed command to send.

For directly delivered `0xf0000044` or `0xf2000000`, application dispatcher
`0x40400d50` forwards to `0x404db31c`, which selects group-zero handler
`0x404d51b0`. Its first state test, `0x404d51bc..0x404d51c4`, returns immediately
when state is zero, before event-specific work or the flag is consulted.
The zero flag alone therefore does not enable those events in that state.

## Queue handling and its synchronous exception

The outer application loop calls `0x40402574` at `0x400defd0`, then branches
back at `0x400defd4`. One iteration:

1. Receives a class-1 message through `0x4006f34c` at `0x4040259c`.
2. Runs a diagnostic wrapper and dispatches the message through `0x40400d50`
   at `0x404025f8`.
3. Runs the diagnostic wrapper again and returns at `0x40402608`.

This inspected loop does not fetch another message between the initializer
and the startup flag refresh. A blocking helper can let other tasks run;
that is distinct from this loop receiving and dispatching its next message.
Unknown nested dispatch inside unaudited helpers is not excluded.

Posting wrapper `0x4006f330` builds a four-word stack message and calls
`sysgMsgSend`, `0x4006993c`, for queue class 1. That sender **can** synchronously
call a filter at `0x40e6354c`, before RTOS enqueue helper `0x4002a3c4`.
A filter result with bit 0 set consumes the message; bit 0 clear lets enqueue
proceed. Any claim that posting is always passive must account for this callback.

The explicit filter setter is `0x40069b80`, wrapped by `0x4006f3d8`.
The bounded aligned ARM caller search found three callers of the wrapper:

| Call site | Value supplied |
| --- | --- |
| `0x405b9930` | Callback `0x405b9434`, via literal `0x405b9a38`; owner logs genuine embedded name `HcePTP_PTPIPInitial` |
| `0x405b9b30` | Zero, removing the filter |
| `0x405c93b4` | Zero, removing the filter |

Original callback `0x405b9434..0x405b9468` only compares the event word and
returns. It returns 1 for `0x6251000e`, `0x6251000f`, `0x6251001a` and
`0x6251001b`; otherwise it returns 2. It has no call or store instruction.
Consequently, it does not dispatch UI events early or alter the USB flag.
The search does not exclude direct pointer writes, indirect setter calls,
Thumb code or computed addressing. The actual installed filter was not read.

## Original-ARM controls

[`ReproduceStartupUsbGate.java`](scripts/ReproduceStartupUsbGate.java) validates
the image hash, then performs **80 independent supplied-state controls**:

- Four full application-dispatcher runs: state zero, two selection flags,
  each of `0xf0000044` and `0xf2000000`. All return with UI memory unchanged
  and no stubbed helper called.
- Sixty-four startup-prefix runs: all 16 setting low nibbles, two prior flags,
  and two supplied results from boot predicate `0x40045970`. Original startup
  instructions through the flag store, including original getter, set state 1
  and overwrite the flag as above. Supplied cold-data bytes remain unchanged.
- Twelve posting runs: null versus known PTP/IP filter, each of six events
  (the two startup/USB events and four filter-consumed controls). Original
  posting wrapper, sender and nonzero filter execute. Enqueue payloads and
  counts match; no path enters the UI dispatcher.

The prefix stops at `0x405174c4`, before remaining startup work. Clock/log/profile
calls, the boot predicate, conditional helper `0x4006f3e8` side effects, and
successful RTOS enqueue are modeled. In particular, the test does not audit
`0x4006f3e8`'s hardware-facing tail. Queue handles, callback pointer, cold-data
setting and initial RAM are supplied. The field initializer and the intervening
application startup are inspected statically, not included in a continuous
boot simulation. These controls do not establish actual scheduling, device
enumeration, temporary loader access or recovery.

Output: [`output/startup-usb-gate.txt`](output/startup-usb-gate.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceStartupUsbGate.java /private/tmp/startup-usb-gate.txt
```

Static checks used `analysis/fw.py disasm` for the addresses above and
`callers 0x40517454 0x404d4e48 0x404d4f50 0x4006f3d8 0x40069b80`.
Caller scans cover aligned ARM branches in the first `0xab0000` bytes plus
exact-pointer searches; they are not exhaustive call graphs. Local Ghidra
pseudocode export `/private/tmp/startup-order.txt` supports the inspection.
This continues the documented local Ghidra fallback; no new REA runtime
observation is claimed.

## Consequence for safe entry

An early USB event queued during initialization does not, by itself, preserve
the zero flag until it is handled. Ordinary startup explicitly refreshes the
selection before its normal `StartMain` post. We still need a reachable path
that preserves a temporary change and naturally delivers a loader-connecting
event, or a separate earlier-stage recovery mechanism. Neither follows from
the initial flag clear, and no timing-based camera experiment is proposed.
