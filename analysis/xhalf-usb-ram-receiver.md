# X-half USB RAM loader receiver

Date: 2026-10-31. Static analysis of official firmware 2.00; camera unplugged.
Main image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
All addresses are ARM runtime addresses with image offset zero at `0x40000000`.

## Result

Follow-up: [memory-read boundary](loader-memory-read-boundary.md) executes
the original read parser and queue consumer to the pre-hardware transfer
boundary. Direction is confirmed; unsafe address/length metadata and short
commands are accepted in controls. Retail entry remains unresolved.

The X half contains the request family used by the inspected iCatch host DLL,
including RAM transfer and an upload/copy/execute handoff. This is concrete
device-side evidence for a RAM-loading lead. The unresolved practical barrier
is reaching the appropriate USB personality through a verified temporary
retail-camera entry path. This code runs within the main application; it does
not prove an independent mask-ROM recovery mechanism.

## USB API mode 4 and target 0

The seven-byte mode table at `0x40b74837` is:

```text
ff 03 01 02 00 01 04
```

`0x4038d6d0` uses the API mode as an index. Mode 4 therefore selects target 0,
and calls `0x401b9fcc(0, 1)`. The second parameter is true specifically for
API mode 4. `0x401b9fcc` records the selected target and forwards that flag to
`0x401b94dc`.

The latter function saves the existing descriptor VID/PID and sets them to
`0x2aad:0x6500` when the flag is true. The descriptor at `0x40d77e14` also
contains those IDs in the shipped image. Passing false restores saved IDs.
This is a statically expected identity, not an observed X-half enumeration.
The normal measured retail USB identity remains `04cb:030a`.

The adjacent shipped configuration template has vendor-class interfaces,
including interface 5 with bulk IN `0x82`, bulk OUT `0x03`, and interrupt IN
`0x84` (64-byte bulk packet declarations in this template). Other interfaces
include interrupt IN `0x87` and alternate isochronous IN `0x81` settings.
These are parsed template bytes, not confirmed active descriptors; dynamic
descriptor construction and speed-specific changes still need tracing.

`0x401e2104` registers target 0's initializer (`0x401e122c`) and request
handler (`0x401e1280`) through `0x401ba258`. The registration is reached by
`0x40234a80`, called from startup code at `0x400f888c`. The selected target's
handler is dispatched through the callback arrays rooted at `0x40fc7b78`.
This ties the loader request handler to the target selected by mode 4.

Follow-up: [early startup dependencies](early-usb-startup-dependencies.md)
separates that pointer registration from deferred task setup and connection,
and finds an initialization gap in the early-shell alternative. Registration
alone is not an early-boot USB service or independent recovery.

## Matched vendor requests

`0x401e1280` is a state machine using byte `0x40d79b21` and state object
`0x40fd0d50`. It parses the standard eight-byte setup packet. After an OUT
request's data arrives, request `0xfd` reaches the index switch at
`0x401e1b00`. Its seven entries are `0x04f1..0x04f7`.

| Index | Observed branch | Interpretation |
| --- | --- | --- |
| `0x04f1` | `0x401e1b7c` | additional ISP setup, including a fourth-word length shifted by 8 |
| `0x04f2` | `0x401e1ba0` | schedules transfer in direction selector 2 |
| `0x04f3` | `0x401e1bb4` | parameter/code download; allocates and processes a parameter buffer on first use, code transfer on subsequent use |
| `0x04f4` | `0x401e1ca8` | alternate memory transfer setup, length then address |
| `0x04f5` | `0x401e1ce4` | alternate memory transfer setup, length then address |
| `0x04f6` | default/error | not implemented by this switch |
| `0x04f7` | `0x401e1d20` | upload/run path: resets three lower-level channels, reads a third word as destination, schedules transfer and enters state 6 |

Common parsing reads payload word 0 into state `+0x28` and word 1 into
`+0x2c`, except the explicit alternate-format `0x04f4/0x04f5` paths.
Helper `0x4002116c` performs unaligned little-endian word loads. For `0x04f7`
the third word becomes `+0x3c`. This matches the host DLL's three-word
upload/run setup. No USB traffic has been used to validate it.

An important complication: `0x04f3` is not an unconditional raw-memory copy
in this application. A first-use flag at state `+0x30` routes it through
parameter processing, with a staging buffer and possible copy to `0x3f000200`.
Consequently the host routine named `DnloadBuffer2Dram` must not be assumed
to be a safe general memory write without matching the receiver state.

## Boot-method query changes runtime state

The IN `0x70` branch at `0x401e1548` sets response byte 0 to 2, consistent
with the host library's running-firmware boot classification. If the combined
setup value/index is nonzero, it interprets that combined value as a size,
allocates that many bytes, zeroes them, allocates another `0x10000` bytes and
stores the latter pointer at state `+0x20`. It returns the first allocation's
address in response bytes 1 through 4 when both allocations succeed.

Thus the host's use of the ISP file size in the query is meaningful. This
request is not a passive identity read: it allocates memory and changes loader
state. A zero-size query skips the allocation branch, but retail-interface
reachability and transport are still unverified.

## Upload/run completion and RAM stub

State 6 reaches `0x401e1f40`. It invokes an optional callback, supplies default
destination `0x40000000` if `+0x3c` is zero, performs a storage/header read
through an indirect storage interface, copies `0x4000` bytes of header data to
`0x3f000000`, then calls:

```text
0x401eb1ec(state[+0x28], state[+0x20], state[+0x2c], state[+0x3c], 2)
```

The helper logs its arguments as `pispBuf`, `pimemBuf`, `size`, and `pbaseBuf`.
It prepares an `0x800`-byte code stub based on `0x401ed070`, adjusts processor
state, disables interrupts, writes a parameter block at `0x3f00d000`, then
branches into the prepared stub.

The parameter block contains source pointer, rounded-even length, destination,
and a saved DRAM-parameter buffer pointer/size. The latter two fields now have
a matching [packaged-updater startup consumer](updater-dram-handoff-and-register-writers.md);
this is an offline dataflow match, not a demonstrated launch.
The stub at `0x401ed070` translates source/destination
addresses, programs MMIO at `0x10003000` to copy data in chunks of at most
`0x100000` bytes, performs a low-address helper call at `0x00000b34`, and jumps
to the destination. Destination `0x40000000` gets an additional call through
`0x40000200` first. It loops if the loaded program returns.

This establishes a RAM copy and execution handoff. It is a destructive handoff
to the running software environment, rather than a small callback that returns
to normal shooting. The meaning of every shutdown/cache/low-address helper,
the storage/header read, transfer error handling, and allowable RAM ranges
still require auditing before a live test.

## What remains unresolved

- A verified temporary entry into API mode 4 from a retail boot. `usbt` is an
  internal shell command, so invoking it depends on access not yet established.
  The second application call site at `0x40523650..0x40523654` belongs to
  the factory `clbt` command's `usb` subcommand. Its direct branch appears
  to change runtime configuration; retail shell access is still unresolved.
  See [loader mode entry](xhalf-loader-mode-entry.md) for the trace and the
  neighboring subcommands that explicitly write saved settings.
- Exact descriptors/endpoints and how the vendor Windows driver's IOCTL maps
  to USB transactions for this receiver.
- First-use parameter processing and the uploaded program's initialization:
  neither can be assumed free of persistent writes or hardware side effects.
- A boot path that remains reachable without the main installed firmware.
  The application receiver alone cannot supply that recovery guarantee.

Next work should audit mode-entry callers and the remaining handoff helpers,
then design a minimal reversible mode-identification test if the evidence
supports one. No payload or device-test command is ready yet.

## Reproducibility and analysis limitations

The existing Ghidra 12.1.4 ARMv7 project at `/private/tmp/fuji-ghidra-v7`
provided focused pseudocode through `QueryFirmware.java`; no fresh REA raw
import was used after the prior bridge mapping problem. The target's large
request handler lacks an auto-created function in that cached database, so
its switch and dataflow were followed in original-file LLVM ARM disassembly.
The handoff function itself has focused Ghidra pseudocode corroboration.

Reproduce instruction windows with `analysis/fw.py disasm`:

- `4038d6d0..4038d764`: API mode mapping
- `401b94dc..401b9540`: descriptor identity switching
- `401e2104..401e2144`: target-0 callback registration
- `401e1280..401e20ec`: request state machine and literal pool
- `401eb1ec..401eb41c`: copy/execute preparation
- `401ed070..401ed174`: RAM copy stub and destination jump

Use `analysis/fw.py literals` to resolve the listed literal pools. These
findings are static instructions and inferred roles, not runtime observations.
