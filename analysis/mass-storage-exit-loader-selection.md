# Mode 4 selection on leaving legacy USB mass storage

Date: 2026-11-01. Offline analysis of official 2.00 main image, base
`0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
No camera access, USB transfers, marker files or persistent-setting changes.

## Finding

The unconditional mode-4 call at `0x405c88f0` does not establish a temporary
loader connection. It belongs to a helper used when leaving UI group `0x7000`.
The helper requests disconnection if the cached connection byte is nonzero,
then selects mode 4 without requesting reconnection. A separately supplied
mass-storage creation callback selects mode 2 before its connection requests.

This closes a gap in the earlier [mode-setter caller census](normal-usb-loader-entry.md).
It does not prove that a different subsequent event could never connect the
selected loader; no reachable retail sequence doing that was established.

## Original instructions and ownership

`LeaveUIGroups`, entry `0x404d5b9c`, dispatches a departing state whose upper
nibble is `0x7000` to helper `0x405c88c4` at call site `0x404d5c78`:

| Instruction | Effect |
| --- | --- |
| `0x405c88d0..0x405c88e8` | Read cached byte `0x40e6696b`; if nonzero call `0x4038d764(0)` and clear it |
| `0x405c88ec..0x405c88f0` | Unconditionally call mode setter `0x4038d6d0(4)` |
| `0x405c88f4..0x405c88fc` | Store 4 at UI byte `0x40e6696c` |
| `0x405c8900..0x405c8908` | Clear word `0x40e065d0` |
| `0x405c890c` | Return; no connect request |

The helper neither reads nor writes selection flag `0x40e6696d`. That bypasses
the selection test locally, but does not supply an externally reachable entry
or connection. The broader group-exit routine has other effects and is not
qualified as a safe standalone operation.

Static follow-through of the mode setter also separates selection from connect:
`0x4038d6d0(4)` calls `0x401b9fcc(0,1)` to select target 0 and configure RAM
descriptors through `0x401b94dc`, then `0x401ba32c(0)`. The latter stores bytes
at `0x40fc7c08` and `0x40fd0d0e`; its valid-argument path makes no further call.
These setter paths contain no call to the separate connection API `0x4038d764`.
This is code-path evidence, not observation of physical USB disconnect or a
controller-level safety audit.

Neighboring callback `0x405c8918` logs **`UIMode_UsbMassCreate`**, a genuine
embedded string at `0x40bfdc08` referenced via literal `0x405c8a74`. For event
`0xfb000001`, with the cable getter returning nonzero, it calls:

1. Environment helper `0x4000cd00`, then mode setter **`0x4038d6d0(2)`** at
   `0x405c8980` (target 1, mass storage).
2. USB configuration helper `0x4038ddb0` and **`0x4038d764(1)`** at
   `0x405c89b8`.
3. An optional configuration call depending on GPIO byte `0x1000904c` bit 5,
   a delay, then another **`0x4038d764(1)`** at `0x405c89f0`.

It records UI byte `+0x101c = 5` and cached connection `+0x101b = 1`.
With cable getter zero it instead posts `0xf2000030`, making no connection
request. Both branches set UI state `0x7000`. Neither changes selection flag
`+0x101d`. Event `0xf000000f` has a separate tail posting `0xf2000080`.

The nearby initializer `0x405c87bc` posts `0xfb000001` and sets state `0x7001`.
However, bounded aligned ARM B/BL and exact-pointer searches found no references
to either initializer `0x405c87bc` or callback `0x405c8918`. The inspected main
dispatcher `0x404db31c` has no `0x7000` group branch, although group exit retains
its cleanup branch. This suggests legacy or separately dispatched functionality;
it is **not proof of dead code**. Computed/indirect calls remain unresolved.

A separate bounded immediate census for `0x7000`/`0x7001` found state assignments
in these two mass-storage routines. Other hits at `0x4003fe9c`, `0x400449f4`,
`0x40074220`, `0x40312878`, `0x405c8d18` and `0x405fa7d8` were not additional
UI-state producers in their inspected contexts: several are comparisons or
arguments in other subsystems. The scan covered aligned ARM MOV/MOVW in the
first `0xab0000` image bytes; literal loads, copied values, Thumb and computed
writes were excluded. It is not a complete writer inventory.

## Offline controls and limits

[`ReproduceMassStorageExit.java`](scripts/ReproduceMassStorageExit.java) executes
the original helper followed by the explicitly supplied original callback and
event. Sixteen cases vary cached connection, selection flag, supplied cable
state and supplied GPIO bit 5. All pass:

- Exit selects mode 4, never requests `connect(1)`, clears cached connection,
  and leaves the selection flag unchanged.
- Cable-present callback selects mode 2 before both `connect(1)` requests.
- Cable-absent callback makes no connection request.
- Final UI state/bytes and the GPIO-dependent configuration-call count match
  the original branches.

The mode setter, connection API, environment helper, configuration, cable getter,
logs, delays and event posting are modeled. The fixture starts with selected
mode 2 and supplies the saved mode used only for logging. No USB controller,
filesystem implementation or physical GPIO executes. This is not continuous
startup, evidence of actual event delivery, a demonstrated UI transition,
or proof that a camera remains physically disconnected.

Output: [`output/mass-storage-exit.txt`](output/mass-storage-exit.txt).
Reproduce with the existing Ghidra 12.1.4 ARMv7 project:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceMassStorageExit.java /private/tmp/mass-storage-exit.txt
```

Local disassembly uses `python3 analysis/fw.py disasm START END`; relevant
ranges are `0x405c87bc..0x405c8aa0`, `0x404d5b9c..0x404d5cc0`,
`0x404db31c..0x404db500`, and the setter entries above. Focused pseudocode was
exported locally to `/private/tmp/usb-mass-transition.txt`. This continues the
documented Ghidra-project fallback; no new REA runtime evidence is claimed.

The unresolved requirement is still a reachable, temporary retail path into
connected target 0 without factory-data writes. This cleanup helper supplies
neither that sequence nor recovery if the main application fails to boot.
