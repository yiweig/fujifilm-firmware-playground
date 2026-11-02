# UI-related bulk copies: property-buffer separation and coverage limits

Date: 2026-11-02. Offline static analysis only, official 2.00 main image
SHA-256 `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
No camera commands, settings changes or firmware edits.

## Finding

The inspected date/time property copy does not write the cached USB-selection
flag. Its fixed destination is a different RAM object, and even the full
unsigned-byte length range cannot span the flag. Its paired getter copies
into the caller's response buffer. The setter also calls the retained-clock
subsystem; it is not a harmless USB-refresh mechanism.

This narrows one dynamic-length bulk-copy candidate. It does **not** resolve
the general bulk-writer gap or establish a temporary retail loader entry.
The earlier property audit already identified the clock side effect; this
pass adds exact copy bounds and explains why sharing a function with UI-base
references does not mean the copy targets the UI structure.

## Candidate selection and an important tool limitation

The historical `output/functions.tsv` contains function min/max extents that
can span many unrelated instructions or disjoint body ranges. Intersecting
those simple intervals with literal loads and bulk calls produces misleading
associations. It was not used as a definitive ownership map.

[`InspectUiBulkCalls.java`](scripts/InspectUiBulkCalls.java) instead queries
the current local Ghidra database's actual containing functions and supplements
its reference metadata with original ARM literal-load and B/BL encodings.
It selects functions that both reference UI base `0x40e65950` and have a
reference/candidate branch to memcpy `0x40008b8c` or memset `0x4000cdf0`.
It verifies the image hash before exporting pseudocode, with oversized-body
and decompilation-failure reporting. It does not create functions or run code.

In the inspected local project, selection produced five entries:
`0x404d7058`, `0x404d7e7c`, `0x405c9efc`, `0x405ca274`, `0x405ca67c`.
All five decompiled, but decompilation alone does not validate the recovered
body/flow or every inferred type. Original instructions decide the concrete
claims below.

Coverage is sparse: the raw scan found **1,426 UI-base-load candidates and
11,572 bulk-branch candidates with no containing function** in this database.
These include possible data encodings and are not verified executable counts.
The project is a focused analysis workspace, not a complete function map.
The five selected entries must not be described as all bulk-copy callers or
used to conclude that no other writer exists. Cached Ghidra metadata and the
historical TSV also must not be treated as equivalent coverage.

## Exact date/time setter bounds

Property setter `0x405c9efc`, branch for ID `0x5011`, performs:

```text
405c9f18  r4 = literal[405ca0a0] = 40e139dc
405c9f1c  r1 = incoming_data + 4
405c9f20  r2 = unsigned_byte[r4 + 6]
405c9f24  r0 = r4 + 7
405c9f28  memcpy(r0, r1, r2)
```

The shipped byte at `0x40e139e2` is 16. Even allowing any value of that byte,
the destination interval is contained in `[0x40e139e3,0x40e13ae2)` (maximum
255 bytes). It cannot overlap USB-selection byte `0x40e6696d`. No assumption
about a successful PTP transfer or current device length byte is needed for
this fixed-destination upper bound. This does not establish source bounds,
valid date parsing or the safety of malformed requests.

The same function separately loads UI base `0x40e65950` from literal
`0x405ca0a4`, writes calendar halfwords at offsets `0xff2..0xffc`, and calls
`0x40041ab4` at `0x405ca008`, then `0x40127d00`. Those effects remain separate
from the property shadow copy and do not supply a USB-flag refresh.

Description/getter `0x405ca274`, ID `0x5011`, obtains the same property-shadow
base from literal `0x405ca3f4` and copies from `shadow + 7` to the incoming
response pointer plus 12. It is not a fixed-address write into the UI object.
The real response-buffer ownership/transport is not reproduced here.

Other exported UI paths contain stack clears and clears of an object at
`0x413b9d54` with size `0x214` (literals `0x4040c02c` and `0x4040dedc`).
That particular fixed interval also does not overlap the flag. These examples
are not a transitive audit of every branch or lower-level callee. The factory
callback remains subject to the separate shared-buffer staging/save analysis.

## Reproduction and evidence

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectUiBulkCalls.java /private/tmp/ui-bulk-calls-expanded.txt

python3 analysis/fw.py disasm 0x405c9efc 0x405ca0b8
python3 analysis/fw.py literals 0x405ca0a0 0x405ca0b0
python3 analysis/fw.py literals 0x405ca3f4 0x405ca3fc
python3 analysis/fw.py disasm 0x405ca12c 0x405ca224
```

Compact selection counts, export hash, verified literals and copy bounds:
[`output/ui-bulk-call-review.json`](output/ui-bulk-call-review.json).
The large pseudocode export stays in temporary local storage. The headless
script completed without reported script errors; original literal words and
the bound arithmetic were checked independently from the decompiler.
No new emulation or fresh REA/live-camera result is claimed.

This pass does not change the recovery/activation assessment. Remaining
dynamic or indirect writes, missing function definitions and actual retail
reachability still require investigation. Repeating only this same selected
database query would not close those gaps.
