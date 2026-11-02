# Wider search for cached USB-selection writers

Date: 2026-11-02. Offline static analysis only. Main 2.00 SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
No camera commands, setting changes, marker files or firmware modifications.

## Result

A broader constant-address search still identifies the five previously known
writers of cached USB-selection byte `0x40e6696d`. It did not establish a new
temporary retail entry. This strengthens a bounded writer inventory, not a
proof that no other writer exists or that changing shared settings is safe.

The earlier search centered on `MOVW #0x101d`, the field's offset from the
UI structure. The new search can also recognize split address arithmetic,
register moves and wider stores whose range overlaps the target byte.

| Store instruction | Context already established | Live-entry limitation |
| --- | --- | --- |
| `0x404d4e7c` | Clear during UI initialization | Broad startup initialization, not an isolated retail flag-clear command |
| `0x404d53cc` | Group-zero idle/event refresh | Startup-state handler, not shown reachable from an active PTP session |
| `0x405174c0` | Power-on refresh from shared cold-data RAM | Runs during initialization; no qualified host staging opportunity before it |
| `0x405180b8` | Reset-completion refresh | Known event producer follows RTC-backed state writes |
| `0x4051d608` | Internal `DPC` selector 31 | No qualified retail shell transport |

All five are actual byte stores to the target, checked against original-file
ARM disassembly and the existing caller reports. The initial clear resolves
to value zero; values derived from getter calls remain unknown to the new
static resolver. It does not pretend to execute those getters.

## Search scope and reproducibility

[`find_usb_flag_stores.py`](find_usb_flag_stores.py) rejects images with a
different hash because its target and helper addresses are specific to 2.00.
It scans aligned ARM encodings in `[0x40000000,0x40ab0000)` and resolves
common register definitions backward in bounded windows. Supported candidates
include STR/STRB, STRH, STRD and STM, with overlap checked against the target
byte. MOVW/MOVT, PC-relative literal loads, moves and common arithmetic can
produce an address. Caller-clobbered registers, unknown definitions and many
control-flow boundaries stop resolution; mutable-memory pointer loads are
not followed as if they were immutable literals.

Two runs, with 96- and 256-instruction lookback bounds, identify the same five
stores among 346,146 candidate store encodings. These are **raw encoding
counts**, including possible data, not verified executable store counts.
Instruction decoding and backward resolution are heuristic. No whole-program
control-flow graph or path-feasibility proof is constructed.

The search also considers direct B/BL encodings targeting main memcpy
`0x40008b8c` and memset `0x4000cdf0`. Of 11,595 candidate branch encodings,
both destination and length resolve for only 745 at the shorter bound and
766 at the longer bound. None of those resolved intervals overlaps the flag.
That leaves substantial unresolved coverage; it does **not** exclude a bulk
copy/clear writer. Other wrappers, indirect calls and dynamic sizes remain
outside this result. Destination-only candidates within 8 KiB below the flag
with unknown length were also recorded; that particular list was empty.

```sh
python3 analysis/find_usb_flag_stores.py > /private/tmp/usb-flag-store-search.json
python3 analysis/find_usb_flag_stores.py --window 256 \
  > /private/tmp/usb-flag-store-search-256.json
```

Retained outputs:
[96-instruction search](output/usb-flag-store-search.json),
[256-instruction search](output/usb-flag-store-search-256.json).

Nine synthetic encoding sanity controls also passed: a direct byte store,
split address calculation, overlapping word/halfword/doubleword/STM stores,
unknown-load invalidation, call-clobber invalidation and post-index old-base
addressing. These check the scanner, not execution of firmware or actual ARM
hardware. No new emulator reachability experiment was performed.

Recheck the actual writer instructions with `fw.py disasm` at
`404d4e48..404d4e94`, `404d53b8..404d53d4`, `40517454..405174cc`,
`405180a4..405180bc`, and `4051d5ec..4051d614`.

## Consequence

The underlying entry problem is unchanged. Factory PTP staging changes the
shared cold-data setting, while USB-entry decisions use the cached flag.
Known refreshes still require startup-state reachability, reset side effects
or shell access. Unplugging/reconnecting and waiting for idle must not be
presented as a demonstrated activation sequence. Shared-buffer reload and
incidental save hazards also remain.

No negative result here justifies widening a live memory-read range or
attempting a write. Relevant future targets are unresolved dynamic/bulk
writers or an actual retail route into a known refresh context, with the
entire intervening buffer lifetime accounted for. Independent cold recovery
is still a separate gap. No fresh REA or device-runtime evidence is claimed.
