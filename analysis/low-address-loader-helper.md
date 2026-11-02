# Low-address helper: second caller and stable SRAM initialization code

Date: 2026-11-02. Offline original-image inspection and local Ghidra ARM
controls. No camera access, firmware edits, card preparation or flashing.

## Finding

`0x00000b34` is called not only by the USB RAM-copy stub, but also immediately
after early main-image code copies executable bytes into SRAM. Both callers
pass a destination address and byte count. **Code/cache synchronization is a
plausible working hypothesis**, not an identified function implementation.
Its actual bytes, complete effects and physical backing remain unavailable.

The second caller and SRAM blob are identical in the four official firmware
versions examined. This strengthens evidence of a stable earlier-stage ABI;
it does not show that those helpers are immutable ROM or accessible when the
installed main image is corrupt. This pass does not establish a recovery path.

## Second caller: a code copy into SRAM

In main image 2.00, the special `+0x200` entry branches to early loading
routine `0x40000338`. The identified slice `0x400005f0..0x4000063c`:

1. Reads literal source `0x40e37c00` from `0x400007d0`.
2. Copies words to SRAM `[0x3f000000, 0x3f000674)`.
3. Calls `0x00000b34(r0=0x3f000000, r1=0x674)` at `0x4000063c`.

The 1,652 copied bytes are code and literal pools. Their first instructions
read and write CP15 control state, with barriers and translation/cache
maintenance instructions. Later functions process `DRAMPAR3`-style records
and use SRAM addresses such as `0x3f00e2c0`. The blob is not simply a copy of
the firmware's first 1,652 bytes or a recovered implementation of the low
helper. The observed destination remains `0x3f000000`, not `0x00000000`.

The RAM handoff's other caller, at original `0x401ed134`, passes the uploaded
image destination and size after programming the copy engine. Two code-copy
contexts therefore support the synchronization hypothesis. They do not exclude
additional mapping, validation, shutdown or hardware effects inside the helper.
We must not replace it with a no-op or guessed cache routine in a live payload.

## Cross-version evidence

| Version | Main SHA-256 | SRAM blob source | Size |
| --- | --- | --- | --- |
| 1.01 | `64ca08d346075907bbec2dde2db50bc3a5507a93e5d7b2e5024d4a59599125f9` | `0x40d77a00` | `0x674` |
| 1.20 | `85d6d280605148d131c28f71b3560742310878340c6de5d1c05faf00d02a2c8f` | `0x40d7ee80` | `0x674` |
| 1.30 | `34034d7d1314485fbbd8167669a83dbea4d3dc80cc9ba3c87c7ded2c96aa9df6` | `0x40d87400` | `0x674` |
| 2.00 | `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923` | `0x40e37c00` | `0x674` |

Main-image interval `[0x338,0x790)` has identical bytes in all four versions:
SHA-256 `d545a151267c4916addd3e73dc7bee8d53fe0671cb13c689ef6a3cf4bf4dbc65`.
The blob's source literal lies outside that interval and changes as above.
All four source blobs are byte-identical:
SHA-256 `d84750d2654065b9908e6d7651af2bc1aa9ac517e7561b563e5cb97008b79a6b`.

[`ReproduceEarlySramCopy.java`](scripts/ReproduceEarlySramCopy.java) checks
each whole-image hash, shared code/body hashes, original literal bounds,
copied destination bytes, neighboring sentinel bytes, helper arguments and
unchanged main-image bytes. It executes the original copy slice separately
for each image. All four controls pass, stopping before `0x00000b34`.
No helper is modeled or executed; preceding validation/setup and copied SRAM
code do not execute. This is not whole early-loader emulation.

Output: [`output/early-sram-copy.txt`](output/early-sram-copy.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceEarlySramCopy.java /private/tmp/early-sram-copy.txt "$PWD"
```

## Neighboring low-address API calls

A bounded ARM literal-load/indirect-call scan of the main image finds these
additional calls in the early loading prefix. The listed instructions were
then checked in original disassembly. These are inferred API roles, not names
recovered from symbols or a ROM dump.

| Low target | Main call site(s) | Context / evidence limit |
| --- | --- | --- |
| `0x00000b24` | `0x4000050c`, `0x400006f8` | Buffer pointer and byte count after loading image ranges; possible data/cache operation, unresolved |
| `0x00000b34` | `0x4000063c`, `0x401ed134` | Copied executable destination and byte count; synchronization hypothesis above |
| `0x00002700` | `0x400005a8`, `0x400005bc`, `0x400005d8`, `0x40000720`, `0x4000073c`, `0x40000784` | Format-string arguments including `cksum: %04x != %04x`, `reboot`, `can't recovery`, `bk OK(%d)`; printf-like diagnostic role inferred |
| `0x00002d18` | `0x4000072c` | Return value subsequently printed with `bk OK(%d)`; actual operation unresolved |
| `0x00000788` | `0x4000075c` | Called before branching to main destination `0x40000000`; operation unresolved |
| `0x000031d8` | `0x4000078c` on error branch | Called after an error diagnostic; reset/halt/fallback implementation unknown |
| `0x00004e34` | `0x40000900` | Wrapper passes adjusted block index, count, destination and SRAM base; storage-loading role inferred, backend behavior unverified |

The scan recognizes ARM PC-relative word loads of values `0x100..0xffff`
followed within six instructions by `blx` through the same register. This is
a heuristic candidate search, not complete call-graph coverage or a proof
against computed addresses, register overwrites, Thumb code or indirect tables.
The complete updater yielded no matches with that bounded pattern; it still
contains the matching low-mapping helper noted below. Main pointer occurrence
`0x400007d4 = 0xb34` is thus a genuine second caller's literal, rather than
an unused table coincidence.

Diagnostic text is not proof that a fallback exists: for example, the local
`0x40000910` helper called after the checksum-error message simply returns 1.
The presence of `reboot`/`can't recovery` strings cannot substitute for tracing
the actual branch and low helper.

## Mapping evidence and limits

Main initialization `0x400012d4` programs CP15 with `0x3f00404a` and builds
tables beginning at `0x3f004000`. Common setup `0x400010a0` initially writes
`0x3f006001` to the first table word. Normal bootstrap later calls
`0x40001840` at `0x40003b34`; that helper zeroes the same word and performs
barriers and CP15 maintenance. RAM-handoff helper `0x4000186c` instead writes
`0x0001d406` there before the copied stub is entered.

The complete updater has a matching helper `0x400007c0`, using the same
table address and `0x0001d406` value. Its file/rawC boot helper calls it at
`0x4000f778`. This is evidence that low-address accessibility is deliberately
managed in more than one loading path. It does not establish which physical
device supplies the bytes or what the camera's actual mapping is at any time.

Arm's [translation-table decoder explanation](https://developer.arm.com/community/arm-community-blogs/b/tools-software-ides-blog/posts/developing-jython-scripts-for-ds-5-translation-table-decoder)
explains the distinction between virtual addresses, physical backing and
translation tables. That is architectural context only; it supplies no
SPCA6500 ROM layout or camera-specific validation. This report retains the
raw descriptor values rather than assigning an unverified ROM region.

No live register/table read or low-address memory read was attempted. No new
REA result is claimed; evidence uses original-file LLVM disassembly and the
existing local Ghidra ARM emulator. Reproduce with `fw.py disasm` over
`400005f0..40000640`, `40000790..4000081c`, `400008e8..40000918`,
`400010a0..4000114c`, `400012d4..400013b0`, `40001840..400018a0`,
and `40e37c00..40e38274`; updater helper via
`card.py disasm 400007c0 400007f4`.

## What this changes for recovery

There is now a stronger reason to investigate an earlier-stage low-address
service interface, and a precise, stable set of addresses to compare against
an authentic ROM/stage dump if one becomes available. But the entry and call
sites we recovered are still shipped main-image/updater code. We have not
recovered the earlier provider, proven that it can be reached without working
installed firmware, or qualified a safe way to read its bytes.

The bounded offline result narrows the uncertainty. It does not justify a
live jump, speculative low-address read or firmware flash. Further progress
on the helper itself needs its authentic implementation or an independently
validated platform artifact; more supplied-state tests cannot reveal absent
bytes or hardware behavior.
