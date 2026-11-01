# DisableTest override assessment

Date: 2026-10-31. Static inspection only; camera not accessed, no card marker
created, no commands sent. Official firmware 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses below are ARM runtime addresses, base `0x40000000`.

## Finding

The firmware already implements an override for `DisableTest`: the startup
path accepts a successful size check on `D:\EnableTest.txt`. No firmware patch
or rewrite of the saved flag is needed to satisfy this particular gate.
This is a static control-flow finding, not a verified safe service-entry procedure.

The [startup control candidate](service-mode-button-candidate.md) must still
satisfy the outer boot-condition check. A marker alone does not establish
entry. The camera's live saved values, drive availability at this point, and
physical startup timing remain unmeasured.

## Exact check

At `0x4051755c`, startup calls `0x405219a0`, which reloads 1,024 bytes from
`A:\RO_RES\COLDDATA.BIN` into `0x40e4df02` and returns byte `+0x0a`.
The diagnostic name for the halfword at that offset is `DisableTest`.

- Zero at this first check branches to `0x40517590`.
- Nonzero calls marker helper `0x40521984` at `0x405175cc`.
- A nonzero marker result also branches to `0x40517590`.
- That branch posts `0xf200000f` and `0xf2000012`; the latter leads to
  calibration entry.

The helper loads path string `0x40bc87dc` through literal `0x4052199c`, calls
`0x4015c52c`, and normalizes a nonzero result to 1. The called helper:

1. Opens the path through `0x4013f4ac`, with mode argument `0x10`.
2. Returns zero if opening fails.
3. Otherwise gets its size through `0x4013f500`, closes through
   `0x4013f604`, and returns the size.

No file contents are read by this helper; an empty file fails the check.
Neither helper explicitly writes the saved flag. This does not prove that
filesystem internals or subsequent calibration setup have no persistent effects.
Startup repeats checks after a failed first marker check; the simple result
above assumes stable file/setting inputs, as in the earlier report.

## Persistent alternative: clbt entest

The `clbt` parser compares its argument against shipped string `entest`
(`0x40bc8f86`, pointer at `0x40523b44`). Its matching branch:

- Reads 1,024 bytes of `COLDDATA.BIN` through `0x40125d94` at `0x40522ee0`.
- Stores halfword zero at offset `+0x0a`, instruction `0x40522ef4`.
- Writes the 1,024-byte buffer back through `0x40125e4c` at `0x40522ef8`.

The neighboring `distest` branch stores halfword 1 at `0x40522f80` and writes
back at `0x40522f94`. These are persistent factory-setting operations, not
RAM-only overrides. They also require command access in the first place.
The marker route avoids this explicit settings rewrite and is the preferable
candidate for further investigation.

## Next safety question

The remaining uncertainty is what happens *after* the override, rather than
how to patch it. `EnterCalibrationMode` (`0x405dd4f0`) changes capture/display
state before selecting USB mode. Its automatic-work decision includes
`FgAutoClbt` (saved halfword `+0x6c`) through `0x4051d918`, followed by
calibration event posts. See the startup report for the exact conditional
branches already traced.

Before a live test, trace those events to their handlers and identify any
automatic calibration/storage writes, whether the trigger can be prevented
without modifying factory settings, and how normal operation is restored.
A volatile file override does not make everything it enables volatile.
No live marker test is endorsed by this finding.

## Reproduction

Use `analysis/fw.py disasm` on ranges `40517550 40517620`,
`40521984 405219cc`, `4015c52c 4015c560`, and `40522e8c 40522fa8`.
Inspect literals `40523b1c 40523b5c` for the parser's paths and argument
strings. Original instruction inspection corroborates the existing Ghidra
findings; no camera execution was observed.
