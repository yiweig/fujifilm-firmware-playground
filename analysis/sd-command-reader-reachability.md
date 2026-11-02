# SD command reader: reachability and command-copy boundary

Date: 2026-11-02. Offline original-byte analysis and local Ghidra ARM controls
of official main 2.00, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
No camera commands, card files, firmware modifications or live shell execution.

## Result

The SD command reader is real, but there is still no established retail caller.
An expanded whole-image search did not supply one. Its command-dispatch wrapper
also has an unchecked fixed-size stack copy, reproduced offline. These findings
do not justify testing guessed command files on a camera.

The optional helper called from ordinary shell-task initialization is a
watchdog-task creator, not evidence of an SD autoexec reader. This clarifies a
previously unresolved startup detail without claiming that every shell
initializer/callee has now been exhaustively audited.

## Reader identity and caller search

`0x40475d8c` is the actual reader entry, supported by its push instruction and
separate preceding leaf return. It logs `Audio_ReadCmdFromFile` and obtains
`D:\CMD_LIST.TXT` through literal `0x40475ff0 -> 0x40ba6281`.

The reader first checks file size (`0x4015c52c` at `0x40475dec`); zero size
returns 1 without entering its open/read branch. A nonzero result leads to
open mode `0x10`, byte reads/seeks, parsing of `@`, `#` and `*`, and later
calls to `0x401efd50` with allocated file-selected text at `0x40475fac`.
The file I/O, parser prerequisites, indexing bounds and entire reader are not
emulated here. No safe script format or card-test recipe is established.

The new raw search covers the complete 14,910,464-byte main image rather than
only the usual first `0xab0000` bytes. It finds:

- Zero outside aligned ARM B/BL candidates into reader instruction interval
  `[0x40475d8c,0x40475fe0)`; internal branches are excluded.
- Zero aligned words equal to entry `0x40475d8c` or its low-bit-set form.
- Zero aligned MOVW candidates for low halfwords `0x5d8c`, `0x5d8d` or
  `0x5d90` (entry/low-bit form/next instruction).

These are bounded negative searches, not proof of dead code. Computed or
interior pointers, relative tables, transformed data, Thumb calls and earlier
stages can evade them. The full image also contains data encodings; this scan
does not construct a complete executable control-flow graph. Merely placing
the named file on SD does not establish invocation of this function.

## Command wrapper and observed copy hazard

Reader call `0x40475fac` reaches wrapper `0x401efd50`. Original instructions:

```text
401efd50  save caller LR on stack
401efd54  reserve 132 bytes below that saved LR
401efd58..60  copy input string to SP through 40008d10
401efd64..68  call shell parser 401efacc with that copied string
```

Copy helper `0x40008d10` copies bytes including the terminating NUL until a
zero byte is encountered. No length argument or clamp is used. A 131-character
string plus NUL fits the reserved area. A 132-character string puts its NUL
into the adjacent saved-return slot. The wrapper is used by other firmware
callers too; this observation does not prove any particular external input can
reach it, or that existing callers normally pass oversized strings.

[`ReproduceCommandCopyBoundary.java`](scripts/ReproduceCommandCopyBoundary.java)
executes the original wrapper and original copy helper with synthetic dummy
strings of lengths 0, 7, 131 and 132. All four checks pass. They verify the
complete copy, saved-return-slot bytes, neighboring guards and unchanged
loaded firmware. The 132 case confirms the terminating-NUL overwrite.

Every case stops **before** shell parser `0x401efacc`. No parser, actual shell
command, corrupted return, file operation or hardware routine executes. No
copy helper is stubbed. This is a concrete offline boundary observation, not
a live exploit, recovery route or physical-camera failure measurement.

## Optional shell-startup task is Wdg

In `cmdTaskInit` (`0x401efc90`), a nonzero configuration byte at `0x40d7aba8`
leads to `0x401ef13c(config_word_at_40d7abac)` at `0x401efd24`.

`0x401ef13c` reads that configuration, checks a task handle, chooses a timeout
argument, and creates a task through `0x4000c378`. Its literal task name is
`Wdg` (`0x401ef1dc -> 0x40b188f7`) and task entry is `0x401eedf4`.
That entry configures timeout behavior, waits and checks activity; its
callback `0x401eee6c` logs and enters assertion handling. No pathname or call
to the SD reader appears in these inspected bodies.

The shipped configuration byte is 1 and the following word is `0x003d0900`;
these are image defaults, not a measured live camera configuration. The
watchdog behavior and timing were not emulated or exercised on hardware.

## Reproduce and remaining gap

Output: [command-copy-boundary.txt](output/command-copy-boundary.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript ReproduceCommandCopyBoundary.java /private/tmp/command-copy-boundary.txt

python3 analysis/fw.py disasm 0x40475d84 0x40475ff4
python3 analysis/fw.py disasm 0x401efd50 0x401efd74
python3 analysis/fw.py disasm 0x40008d10 0x40008d28
python3 analysis/fw.py disasm 0x401ef13c 0x401ef1e4
python3 analysis/fw.py disasm 0x401eedf4 0x401eeeac
```

The headless script completed without reported script errors. Evidence comes
from original-file disassembly and the local Ghidra emulator, not fresh REA
or live runtime observation. The missing reader caller/retail trigger remains
the decisive gap. Independent recovery and safe temporary loader activation
are still unproved; neither a filename nor a watchdog startup call fills it.
