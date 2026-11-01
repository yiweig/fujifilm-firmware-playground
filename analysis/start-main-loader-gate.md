# StartMain can select the loader without changing UI groups

Date: 2026-11-01. Offline analysis only; no camera access. Official 2.00
main image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARM runtime coordinates, base `0x40000000`.

## Finding

Follow-up: [boot USB latch and producer audit](boot-usb-latch-and-start-producers.md)
identifies the runtime bit's source, reproduces its use without substituting
a pointer, and audits the diagnostic prefix and initial-setup producers.

A UI-group transition is not required for every loader-selection path.
In state `0x0001`, event `0xf2000000` reaches the startup handler's
`StartMain` branch. This branch selects USB API mode 4 if an additional
runtime status bit is set and the cached USB-selection flag is zero.

The offline emulator now composes the previous idle-event refresh with this
event in the same unchanged state 1 and reaches the mode-4 setter boundary.
It stops before executing that setter. This is a stronger conditional code
path, **not an established live entry or recovery procedure**. The event and
several prerequisites remain supplied, and actual USB activation is untested.

In particular, state 1 participates in startup handling. It must not be
equated with the camera's stable picture-taking state merely because its
number is small. The earlier state-1 USB-event result was accurate, but that
one event does not exhaust state 1's loader possibilities.

## Original-byte trace

| Location | Observation |
| --- | --- |
| `0x404db31c` | Dispatcher routes state 1 to group-0 handler `0x404d51b0` |
| `0x404d5598..0x404d55b0` | Otherwise-unhandled event in exact state 1 delegates to `0x40517b18` |
| `0x40517b18..0x40517b2c` | Adds `0x0e000000` to the event, then dispatches indices 0 through 35; `0xf2000000` becomes index 0 |
| Table word `0x40517b2c` | Index 0 selects `0x405180f0` |
| `0x405180f0..0x40518128` | Prefix API `0x400410bc(0,"StartMain")`, clock/log calls, then `0x40126930(17,0,1)` |
| `0x4051812c..0x405181a0` | If byte `0x40e4ae64 & 3` equals 1, run a separate physical-presence/charging branch; other values skip it |
| `0x405181a4..0x405181b4` | Dereference pointer at `0x40d6d014`; test bit 0 of the pointed-to first byte |
| `0x405181b8..0x405181c8` | If that bit is set, test USB-selection byte `0x40e6696d`; nonzero skips loader selection |
| `0x405181cc..0x405181e0` | Zero flag: resource setup `0x405cbb84(1)`, USB mode setter `0x4038d6d0(4)`, connect `0x4038d764(1)` |
| `0x405181e4..0x405181e8`, `0x40518538..0x40518548` | Continue by posting `0xf2000010` with three zero arguments |

The runtime status bit is deliberately left unnamed here. Its use in USB
startup is observed; it has not been established as a live cable detector.
It is distinct from the raw USB presence MMIO bit and the cached getter.

The prefix API `0x400410bc` adds 6 to its first argument and tail-calls
`0x400326a4`. Its complete effects were not audited. The emulator stubs this
call with exact argument checks; it does not establish that the real prefix
has no storage or other side effects. The helper `0x40126930` executes
original instructions with its optional table pointer at `0x40e7af10` set
to zero; a populated callback table remains outside this reproduction.

## Event provenance and timing limit

This is an internal UI event, not a PTP request to send from a computer.
The startup routine at `0x40517454` explicitly sets UI state 1 at
`0x40517488`, refreshes the USB flag from the shared cold-data getter at
`0x405174a8..0x405174c0`, and later posts `0xf2000000` at `0x40517910`.
The intervening initialization is not reproduced end to end.

The event is encoded as ARM immediate `MOV r0,#0xf2000000`, rather than a
literal-pool word. Searching only exact pointer bytes would miss this
producer. Twelve aligned ARM MOV-to-r0 instructions with that value were
found in the first `0xab0000` image bytes:

```text
4047ac5c 4047ee30 4047f1d4 4047f42c 4047fc10 404801e4
4048062c 404827d8 4048321c 4048337c 4051790c 405af0b4
```

These are value matches, not twelve qualified, safe entry recipes. One
additional inspected example (`0x404827b4..0x404827e8`) sets state 1 and
posts this event, but its full caller and prior effects remain unaudited.
The startup producer alone does not show that the idle refresh occurs before
it, that a host can communicate during that interval, or that it can safely
be repeated after shared settings have been staged. No timing race is claimed.

## Idle-suppression investigation

The bounded aligned ARM MOVW scan found fourteen immediate-`0x102d` sites in
the first `0xab0000` bytes:

```text
404d5298 404d6938 404da350 404da380 404dacec 404dae68 404fb838
404fb8c0 404fbb00 405184c0 4051850c 40537d3c 405af184 405dd704
```

The inspected UI sites read `+0x102d`; no qualified writer was identified in
these windows. The `0x40537d3c` match is a different use: it supplies a field
identifier to the descriptor-building function `0x405373d4`. That function
appends records to an output buffer; it is not an assignment to
`0x40e65950 + 0x102d`. Numerical equality does not make it a flag writer.

The existing Ghidra reference query likewise yielded reads, with incomplete
coverage (it omits at least the known read at `0x404d529c`). No absence claim
is based on that query. Initialization through a whole-structure clear,
aliases, other addressing forms and earlier-stage code remain possible.
The byte's actual value and lifetime on the camera remain unknown.

## Reproduction and controls

[ReproduceTemporaryUsbSetting.java](scripts/ReproduceTemporaryUsbSetting.java)
preserves its prior fourteen PASS checks and adds two reported groups:

1. After idle refresh, the same state-1 instance receives supplied
   `0xf2000000` and reaches mode 4 with the runtime bit set. The cold-data
   buffer, cached USB flag and UI state remain unchanged at the boundary.
2. Four fresh Boolean controls select mode 4 only when the flag is zero and
   the runtime bit is one. The other combinations post `0xf2000010` and
   return; they do not execute the queued continuation.

All sixteen reported groups passed. Output:
[temporary-usb-setting-start-main.txt](output/temporary-usb-setting-start-main.txt).
The new path supplies an isolated runtime pointer/bit, byte
`0x40e4ae64 = 0`, optional callback table pointer zero and the logging-only
`gbPwrOnToViewMode` input zero. It stubs the prefix API, clock/log and
resource setup. Unexpected callees fail; the USB mode setter never executes.
The low-two-bits-equal-1 charging branch is excluded, not proved harmless.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting-start-main.txt

python3 analysis/fw.py disasm 0x404d5598 0x404d55bc
python3 analysis/fw.py disasm 0x40517b18 0x40517b30
python3 analysis/fw.py disasm 0x405180f0 0x405181ec
python3 analysis/fw.py disasm 0x405178cc 0x40517914
```

Existing local Ghidra and original-byte disassembly were reused after the
documented REA raw-image mapping issue. This does not claim a fresh REA
observation, device runtime observation, or complete startup execution.

## Research consequence

We can now connect the refreshed flag to a loader-selection boundary without
inventing a UI-group change. The next gap is whether there is a repeatable,
nonpersistent route that both preserves staged settings and naturally delivers
this event, with the actual runtime prerequisites satisfied. The normal
startup refresh could instead read unstaged or reloaded settings; the idle
event's timing remains unqualified. Broad initialization/reset paths must not
be treated as safe merely because they can post `StartMain`.

Before considering live activation, trace the relevant event producers and
the runtime status bit's source, plus incidental saves/reloads and the real
prefix API. Independent cold recovery remains a separate missing guarantee.
