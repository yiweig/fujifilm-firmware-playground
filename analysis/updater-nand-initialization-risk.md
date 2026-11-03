# NAND initialization can build tables before USB startup

Date: 2026-11-03. Offline original-ARM inspection and local Ghidra controls of
official 2.00 complete updater, linked at `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All addresses belong to that updater. No camera access or firmware changes.

## Finding

Follow-up: [USB/storage separation](updater-usb-storage-separation.md) shows
that the later USB constructor reaches controller bring-up with an empty
registry under modeled services. This does not establish a safe entry or
bypass the earlier storage work in normal startup.

The low NAND initializer `0x4002b7fc` can reach erase-capable table construction
through its NAND header parser, **before an explicit mount request**:

```text
conditional startup backend registration
  -> 4001be6c: install NAND interface, immediately call initializer
  -> 4001bd44 -> 4002b7fc(1): low NAND initialization
  -> 4002f834: parse NAND header and configure reserved regions
  -> 4002fa98 -> 4002a248: nandTableMake for a nonempty reserved region
  -> specific spare-read result 1 can request NAND block erase
```

The earlier [mount/write risk](updater-mount-write-risk.md) therefore has an
additional startup route, independent of the worker's type-2 mount message.
This is conditional static reachability, with separate bounded parser and
read-error controls. It is not an observed erase, a full simulated startup,
or evidence that this camera's installed header enables that region.

## Low initializer: observed work and unaudited boundaries

The original body `0x4002b7fc..0x4002bad0` performs these stages:

| Stage | Evidence and scope |
| --- | --- |
| Reset RAM state | Obtain NAND object, save 168 bytes, release existing buffers at +0x6c/+0x74, clear/rebuild object |
| Configure controller and identify NAND | Calls peripheral helpers including `0x4006c150`; its inspected instructions issue command values `0xff` and `0x90` and read ID bytes. Full electrical/controller effects are not qualified |
| Select geometry/profile | Calls `0x4003d94c`, which decodes geometry and installs callbacks; profile return exactly 1 takes an early exit |
| Allocate buffers and install I/O methods | `0x4002be88` computes buffer sizes; `0x400309a0` installs methods at +0x48/+0x4c/+0x50; storage-framework setters receive state/callback pointers |
| Locate/read header | `0x40029f1c` scans spare metadata using +0x28; initializer then calls +0x2c at `0x4002b9d0` with a count of 32 and the header buffer |
| Interpret header | Calls `0x4002f834` at `0x4002b9d8`; that parser may build a reserved-region table |
| Finish bookkeeping | Error fallback updates RAM geometry, registers callbacks, accounts for configured capacity, releases controller state, optionally invokes callback from global `0x4002bb00` |

These are not all asserted to be read-only. Registering write/erase methods is
separate from invoking them. The low initializer's indirect +0x2c call uses
`0x4003f7f4` under the previously inspected profile; this pass does not audit
its complete indirect callback/controller graph. The spare-scan helper's exact
read result 1 logs and exits that helper, rather than taking the table builder's
erase branch. Those routines have different error handling.

The final optional callback and all alternate geometry profiles remain audit
boundaries. The concrete new persistent-change route is the parser's call to
`nandTableMake`; a complete proof about every low initializer side effect is
still unavailable.

## Header guards and reserved-region condition

Parser `0x4002f834` gets NAND state 1 and reserved-region state 99 through
`0x400301d8`. The inspected header guards are:

| Header field | Original check |
| --- | --- |
| Halfword +0x08 | Must equal `0xbeef` |
| Byte +0x40 | Must equal `0x32` |
| Word +0x24 | Must match NAND state's total-block word +0x80 |

A failed guard logs, dumps 768 bytes through `0x4007bf4c`, and returns 1.
The diagnostic for the second guard says `NAND header chksum err`, but this
particular instruction sequence compares one byte with a constant; it is
not evidence of a full header-checksum computation. Other validation elsewhere
is not excluded.

On the accepted path the parser copies 512 header bytes to a default-header
RAM buffer and derives region boundaries. Actual log strings identify the
region associated with header +0x34 as `nrRsvC`. In the simple path where
header +0x168/+0x180 are zero:

```text
reserved_state +0x54 = header[+0x28] + header[+0x2c] + header[+0x30]
reserved_state +0x58 = header[+0x34]
```

Other branches adjust these fields; the simple formula is not universal.
At `0x4002f9fc`, helper `0x4002f824` copies 44 bytes of NAND callbacks from
state +0x28 into reserved-state +0x28. This includes erase slot +0x34.
The parser releases old table buffers, then checks reserved-state +0x58:

- Zero skips table allocation/construction at `0x4002fa3c`.
- Nonzero allocates a table buffer, sets +0x74 and +0x70, and calls
  `0x4002a248(reserved_state)` at `0x4002fa98`.

The [previous erase-branch controls](updater-mount-write-risk.md) establish
that table construction can call the copied +0x34 erase method on one specific
spare-read error. The new caller supplies reserved-region state, so that finding
must not be described as inevitably erasing the whole NAND or a particular
firmware/calibration block. No installed header, actual block range, probability
of the error, or successful erase has been measured.

## Five bounded original-parser controls

[InspectUpdaterNandHeader.java](scripts/InspectUpdaterNandHeader.java) executes
the original parser, original state getter and callback-copy wrapper against
synthetic RAM. It does not execute the low initializer or real storage reads.

The supplied geometry has 1024 total blocks and a shift value of 6. Header
fields other than those listed below are zero. The preexisting default-header
buffer and a resolved erase callback pointer are supplied RAM fixtures.

| Supplied header | Observed boundary |
| --- | --- |
| Wrong signature | Return 1; one diagnostic dump; no table allocation |
| Wrong byte +0x40 | Return 1; one dump; no table allocation |
| Total blocks 1023 instead of 1024 | Return 1; one dump; no table allocation |
| Guards match, +0x34 = 0 | Return 0; no table allocation |
| Guards match, +0x34 = 4 | Allocate 40-byte table buffer; reach `0x4002a248` with reserved-state pointer; stop before its first instruction |

All five pass. The final case also checks the copied erase pointer and the
new +0x74/+0x70 buffer pointers. Copy helpers, allocation, logging and dumps
are modeled; no dump reads beyond the supplied header are performed by the
harness. The table builder, erase/program routines, NAND controller, MMIO and
camera never execute. Loaded-image bytes are unchanged and source files are
untouched. The parser's accepted branch is not proof that a chosen header is
a legitimate or safe on-camera configuration.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript InspectUpdaterNandHeader.java /private/tmp/updater-nand-header.txt
```

Evidence: [five controls](output/updater-nand-header.txt),
[hash-qualified static ranges](output/updater-nand-init-static.txt).
No new REA or live result is claimed.

## Implication for the recovery work

Preventing a worker mount message is insufficient to isolate storage effects:
NAND registration can already reach the table builder through initialization.
A future temporary experiment needs a qualified entry and payload that avoids
these startup paths, alongside independent recovery. This report supplies
neither a payload nor an on-camera procedure.

The next useful offline audit is what starts USB before or without storage
initialization, including earlier startup and the low-address handoff helper.
More synthetic header cases cannot establish the camera's actual NAND contents
or recover code absent from the extracted images. Camera reconnection is not
needed for this finding.
