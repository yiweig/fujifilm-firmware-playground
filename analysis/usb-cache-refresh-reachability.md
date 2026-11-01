# USB cache-refresh callers: debug shell and reset completion

Date: 2026-11-01. Offline only; no camera or host USB access.
Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Runtime base `0x40000000`, little-endian ARMv7.

## Findings

Follow-up: [retail PTP command boundaries](retail-ptp-command-boundaries.md)
checks operation/property registrations and the command-arrival notification.
Those inspected surfaces do not provide the missing shell/refresh entry.

The remaining known refresh at `0x4051d5f4` belongs to internal shell command
`DPC`, selector 31. Its selected branch refreshes the cached USB-selection
flag without an explicit file or RTC write. Four original-ARM controls verify
the branch and distinguish adjacent selectors. This is useful code evidence,
but does not establish a way to invoke it through ordinary retail PTP.

The refresh at `0x405180a4` belongs to event `0xf2000023`, reset completion.
Its identified literal-based event producer is the broader cleanup
`0x405c7ff8`, after RTC-backed writes. That enclosing route does not qualify
as a RAM-only refresh mechanism. These findings close two specific caller
questions, not all possible activation/recovery routes.

## DPC handler and selector

The original shell command-table triple is:

| Address | Contents |
| --- | --- |
| `0x40e18088` | Pointer to string `DPC` at `0x40ce568d` |
| `0x40e1808c` | Pointer to description `DPC_Debug` at `0x40ce5691` |
| `0x40e18090` | Handler `0x4051d24c` |

This is **not** the adjacent `clbt` command, whose handler pointer is at
`0x40e1809c`. Earlier notes called the refresh factory-related; the command
table now gives its specific owner. `DPC_Debug !!` at `0x40bc76cb` is the
handler's own diagnostic string.

Entry `0x4051d24c` saves `r2` as its parsed-argument pointer, loads selector
from `[r2 + 4]`, bounds-checks against 50, then dispatches through table
`0x4051d26c`. Relevant selectors are:

| Selector | Table word | Destination and inspected effect |
| --- | --- | --- |
| 21 | `0x4051d2c0` | `0x4051d4d4`: reload cold file, change USB-setting nibble, save all 1,024 bytes through `0x40125e4c` at `0x4051d538` |
| 22 | `0x4051d2c4` | `0x4051d554`: reload, change boot-type nibble, save at `0x4051d5b8` |
| 30 | `0x4051d2e4` | `0x4051d5d4`: print USB and boot-type words already in RAM |
| 31 | `0x4051d2e8` | `0x4051d5ec`: refresh USB cache from RAM getter, print result, return |
| 32 | `0x4051d2ec` | `0x4051d614`: starts with cold-file reload; remaining mutations not qualified here |

Selector 31 executes getter `0x40125284`, stores its inverse at `0x40e6696d`,
and returns after logging. The getter also stores whether cold-word bits
8..11 are nonzero at `0x40e7ae50`. It does not read the cold file itself.
The diagnostic text `Init Param for USB feature!!` therefore does not mean
this branch reloads saved settings from storage.

### Original-code checks

[ReproduceDpcUsbRefresh.java](scripts/ReproduceDpcUsbRefresh.java) verifies
the image hash and executes the handler from its actual entry. It supplies
the parsed argument array, rather than running a shell parser or transport.
Only print function `0x40004064` is modeled as a return. A strict instruction
allowlist rejects any other unqualified callee.

Results: [dpc-usb-refresh.txt](output/dpc-usb-refresh.txt).

- Selector 31 with cold word `0x0114` changes cached flag 1 to 0.
- Selector 31 with cold word `0x0118` changes cached flag 0 to 1.
- Selector 30 prints but leaves a deliberately stale flag unchanged.
- Selector 32 reaches `0x40125d94`; the test stops **before** the file read.

Each test compares all image/global bytes `0x40000000..0x413fffff` against
the exact expected result. For selector 31 the only permitted changes are
the cached flag and getter side-effect byte; the other controls permit none.
The full cold-data fixture remains unchanged. Stack and supplied argument
storage live separately. No USB setter, file operation or RTC operation is
executed by these checks.

This establishes the handler's selected behavior, not access to its entry.
The [shell transport audit](shell-access-and-usb-lifecycle.md) identifies
UART input and a USB character bridge requiring the target-0 interface we
are still trying to reach. The qualified retail factory-PTP operations are
data/setting handlers, not a demonstrated shell bridge. The user's exclusion
of disassembly also excludes a UART-wiring experiment. No command-sending
client was added.

## Reset-completion event producer

Startup/event handler `0x40517b18` normalizes events by adding `0x0e000000`
and bounds-checks index `0x23`. Table word `0x40517bb8` selects refresh
`0x405180a4` for event `0xf2000023`. In group zero, fallback at
`0x404d5598..0x404d55b0` forwards to this handler only when state is 1;
state `0x0300` selects a different handler and other states return there.
Normal PTP state `0x8000` uses a different group dispatcher.

After the refresh, reset-completion code clears a runtime word at boot-state
offset `+0x10`, then either enters playback (`0x405180ec`) or falls into
`StartMain` (`0x405180f0`). Thus supplying the refresh event alone would not
be equivalent to executing only the flag store.

A whole-image search for exact little-endian event bytes found:

- Aligned word `0x405c81d0`, used by loads at `0x405c8164` and `0x405c8178`
  in cleanup `0x405c7ff8`.
- Unaligned hit `0x40b7702d`, formed across adjacent numeric table words
  (`0x000023f1`, `0x000023f2`); not accepted as an event producer.

The cleanup saves its input `r0/r1` in `r7/r8`. After its other work, it
conditionally posts `0xf2000023` when `r7` is nonzero. If `r8` is nonzero,
queued parameters are zero; otherwise they receive `r8`, also zero.
Before either posting branch, this same routine unconditionally reaches
RTC-backed save `0x40041be8` at `0x405c8118` and retained-state helper
`0x40041e5c` at `0x405c8124`, as well as graphics/object and state operations.
No full cleanup execution was attempted in this pass.

Aligned direct calls to cleanup found in the first `0xab0000` image bytes:

| Call site | Inspected argument relationship |
| --- | --- |
| `0x404d53b4` | Previously traced group-zero cleanup path |
| `0x405c82e4` | `r0=1, r1=1` |
| `0x405c8470` | Both 1 on inspected paths, including conditional branch from `0x405c83c4` |
| `0x405c8604` | Both loaded from `r4`; actual value not qualified here |
| `0x405c8750` | `r0=1, r1=1` |

These are static call relationships, not retail button recipes. Exact-literal
and aligned ARM searches do not exclude computed event construction, indirect
calls, Thumb code or an earlier stage outside this image.

## Known-writer inventory after this pass

| Writer | Reachability status |
| --- | --- |
| Startup refresh `0x405174a8..0x405174c0` | `ProcessPowerOnEvent`, called at `0x40401264`; no demonstrated opportunity to stage cold RAM before it during a retail PTP session |
| Group-zero refresh `0x404d53b8..0x404d53cc` | Known idle/event route; state 1 can avoid broader cleanup, but transition there from retail PTP with staged RAM is missing |
| Reset-completion refresh `0x405180a4..0x405180b8` | Event `0xf2000023`; identified producer follows RTC writes |
| DPC refresh `0x4051d5f4..0x4051d608` | Internal shell selector 31; selected behavior verified, retail transport missing |
| Initial clear `0x404d4e7c` | Inside `0x404d4e48`, tail-called by `0x404d4f50`; found caller `0x404010bc` in UI initialization. Broad initialization is not an isolated flag-clear API |

For the initial-clear chain, bounded direct-call scanning found only
`0x404d4f90 -> 0x404d4e48` and `0x404010bc -> 0x404d4f50`, with no exact
entry-pointer occurrences. The surrounding application code labels this
`InitUIPara`. This does not establish an externally repeatable initialization
event or an exploitable zero-flag timing window. Known writers are based on
the earlier bounded `MOVW #0x101d` census, not a proof that no other write
mechanism exists.

## Reproduction and remaining question

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceDpcUsbRefresh.java /private/tmp/dpc-usb-refresh.txt

python3 analysis/fw.py literals 0x40e18088 0x40e180a0
python3 analysis/fw.py disasm 0x4051d24c 0x4051d26c
python3 analysis/fw.py disasm 0x4051d5d4 0x4051d62c
python3 analysis/fw.py disasm 0x40517b18 0x40517bbc
python3 analysis/fw.py disasm 0x405c7ff8 0x405c81d4
python3 analysis/fw.py callers 0x405c7ff8 0x404d4e48 0x404d4f50
```

All identified writers now have an enclosing handler or initialization context.
None yet supplies a qualified retail RAM-loader entry. The remaining question
is how normal operation can reach one of those contexts while preserving the
temporary setting and avoiding unqualified persistence. Another isolated
refresh/loader composition would not answer it. Independent recovery also
remains unproven.

Original-image disassembly and the existing local Ghidra ARMv7 project were
reused after the documented REA raw-image mapping limitation. No fresh REA
runtime or camera evidence is claimed.
