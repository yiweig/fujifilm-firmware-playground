# V50 package ISP: a RAM program, not recovered boot ROM

Reviewed 2026-11-03. Offline package parsing and REA/Ghidra inspection of the
retained V50 comparison package. No camera access, USB commands, supplied-code
execution, flashing, mounting or new download.

## Finding

The package region that V50 research calls `offset0` / `spca6500isp.bin` is a
separate ARM ISP program. Its code prints **iCatch ISP Version 6500.0021**.
Startup corroborates the researcher's `0x40040000` RAM mapping, and its stored
18-byte USB device descriptor is identical to the X-half complete updater's
`2aad:6500` template.

This strengthens the common SDK/platform comparison. It does **not** supply a
retail X-half entry sequence, establish a cold recovery transport, authenticate
an internal boot-ROM dump, or qualify this V50 payload for an X half. The source
label "bootloader" must not silently become any of those claims.

This follows the [external-flash layout review](v50-flash-layout-and-provider-limits.md).
That review did not analyze this separate package executable's startup.

## Artifact and coordinates

Source: [V50 Playground, pinned dc437ced](https://github.com/Linouth/iCatch-V50-Playground/tree/dc437ced8fdc9564e39ca550af6a1d53b1037714).
The [research notebook](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/reversing/README.md)
labels package regions and proposes the ISP mapping; its carver was read, not
executed. Its modification instructions were not followed.

Input `reversing/v50ProSE/AKASO_V50 Pro SE_190315_V1/SPHOST.BRN`:
15,740,152 bytes, SHA-256
`1f9db54f710d26af1be9f96a317f838fa52e0ca90225f602c45b287e93f2a2f9`.

Complete ISP interval: package `[0x200, 0x91000)`, 593,408 bytes, SHA-256
`2cd3549aac079e1cffdbe6408f8a31c8127db56f635fb6398d26fbebf7c47aa0`.
No executable prefix was omitted. The package header is outside that interval.

For this ISP only, the analysis mapping is:

```text
analysis address = 0x40040000 + ISP file offset
analysis address = 0x40040000 + (package offset - 0x200)
```

This differs from the [X-half complete updater](updater-image-layout.md), linked
at `0x40000000`. Function addresses must not be reused between those binaries.

## Startup evidence

| Observation | Original-instruction evidence |
| --- | --- |
| Reset target | Initial ARM word `0xea0000be` branches from ISP offset zero to offset `0x300`, hence `0x40040300` under the selected mapping |
| Vector base | `0x40040370..0x40040378` constructs `0x40040000` in `r3` and writes CP15 VBAR; followed by ISB |
| Runtime setup | `0x400403f8` clears `[0x400d0e00,0x400eaa40)` using literal pointers, then invokes further setup helpers |
| Header/vector copy | `0x40040454..0x4004047c` copies source `[0x40040000,0x400402f8)` to `[0x40000000,0x400002f8)` using 32-bit stores |
| After-copy call | `0x40040480..0x40040490` passes destination `0x40000000` and length `0x2f8` to `0x400b2c90` |

The copied prefix is 760 bytes, SHA-256
`8805c62ceb5ef341e19921dbb91c38b78380046a02b16ee922541d749729f0f9`.
The destination is high memory and the copy ends before destination offset
`0xb34`. It does not populate physical/virtual low address `0x00000b34` or
establish an alias to it. The CPU's actual translation/remap context was not
acquired. No call or copied instruction is executed in this investigation.

The after-copy wrapper calls an internal routine then branches to cache
maintenance code with CP15 operations and barriers. This is useful comparison
structure, but not authentication of the X-half low helper's implementation.
The full ISP startup/hardware side effects have not been audited.

REA's reset-function body includes distant tail-connected ranges; the retained
assembly page is the provider's complete selected function body, not a claim
that every listed distant instruction is a contiguous reset instruction.

## ISP banner and USB template

At `0x4004fc30`, loads and indirect calls use a format string pointer from
`0x4004fc88` and arguments from `0x4004fc80/84`. Those pointers resolve to
`|  iCatch ISP Version [%s.%s]  |`, `6500`, and `0021`. This is a code-referenced
banner, rather than a filename-based version inference. It is not observed
output from a running camera.

At ISP file offset `0x8bf6c` / analysis address `0x400cbf6c`:

```text
12 01 00 02 00 00 00 40 ad 2a 00 65 00 01 01 02 00 01
```

The `0x4004e0b8` getter conditionally returns that descriptor pointer through
its literal at `0x4004e100`; another branch reports an error and returns zero.
The descriptor is therefore code-referenced, though successful live enumeration
is not demonstrated. Its bytes match the hash-verified X-half complete updater
template at `0x4009da30`; see [descriptor analysis](updater-usb-identity.md).
Matching identity does not establish command compatibility or cold boot access.

Strings elsewhere in the ISP include NAND erase, formatting, programming and
SD/SPI loader commands. They identify further investigation seeds, not a
verified command transport or a complete audit of when persistent writes occur.
No programmer or loader command was run.

## Adjacent package regions

The parser inventories all nonzero header-declared region starts. The adjacent
`offset1` range `[0x91000,0x92000)` has only 20 nonzero bytes: the 15-byte
`SUNP BURN HDR 1` marker and five isolated values. This pass does not assign
semantics to those values or identify the region as an executable/provider.
Other region roles retain the earlier notebook's evidence status; this is not
exhaustive disassembly or decompression of the entire package.

## Evidence and reproduction

Small tracked records:

- [Hash-checked package inventory and selected literal values](output/v50-package-isp.json).
- [REA assembly pages, original Evidence IDs and limitations](output/v50-package-isp-rea-pages.json).

REA 6.8.0 used Ghidra 12.1.4 with its generic little-endian ARM raw import;
caller-selected base `0x40040000`. The startup VBAR write and absolute pointers
corroborate that choice. It is not a measured CPU mapping. The complete local
Evidence bundle and reusable snapshot are ignored caches under
`analysis/output/rea-v50-isp-evidence.json` and `v50-isp.rea-snapshot`.
The native session was closed after export.

Reproduce the metadata with the separately retained pinned input:

```sh
python3 analysis/inspect_v50_package_isp.py \
  '/private/tmp/icatch-v50-dc437ced/reversing/v50ProSE/AKASO_V50 Pro SE_190315_V1/SPHOST.BRN'
```

For REA, take the hash-checked package slice `[0x200,0x91000)` into a new local
file, verify the ISP SHA above, and use `open_binary` with `format=raw-binary`,
`architecture=arm`, `entry_point=0x40040000`, `provider_id=ghidra`. Analyze the
listed functions and preserve their complete Evidence before closing. The
Python parser supplies selected bytes/metadata, not a fresh disassembly result.

Validation checked package/ISP hashes, reset displacement, literal-derived copy
extent, banner arguments, sparse header contents, wrong-artifact rejection and
the X-half descriptor comparison against the complete updater's known hash.
R1 temporary retail entry, R2 authentic low-memory provider and C1 cold recovery
remain unresolved in the [roadmap](ram-launch-and-recovery-roadmap.md).
