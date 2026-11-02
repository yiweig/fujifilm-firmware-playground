# Retail PTP command registrations, properties and UI notifications

Date: 2026-11-01. Offline only; no camera commands, USB discovery or markers.
Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7 little-endian runtime base `0x40000000`.

## Result

Follow-up: [retail file acquisition](retail-ptp-file-acquisition.md) traces
the normal download surface, storage enumeration and media filtering, with
17 offline controls. It establishes no direct cold-archive acquisition route.

Follow-up: [retail USB control routing](retail-usb-control-routing.md)
separates control-endpoint requests from PTP packets, verifies the empty retail
vendor slot, and traces the distinct class-reset and assertion paths.

The inspected ordinary PTP command/property surface does not supply a route
to debug shell `DPC` or the cached USB-selection refresh. The direct-call
registration census identifies only the seven previously known factory
operation registrations, conditional on their marker flag. Ordinary property
callbacks do not expose USB selection. Generic command-arrival notification
records an operation value as data; it does not dispatch that value as a UI
event in the tested PTP state.

Fourteen original-code controls passed. This is a bounded negative result,
not proof that every USB request, indirect registration or callback chain is
inaccessible. In particular, USB control-endpoint requests are a separate
surface from PTP operation packets.

## Registration census

Aligned ARM B/BL scans cover the first `0xab0000` bytes; exact entry-pointer
searches cover the whole image. Raw results are retained in
[ptp-registration-callers.txt](output/ptp-registration-callers.txt).

Operation-table registration `0x402e753c` has one found direct caller,
`0x405e6934`, inside wrapper `0x405e6868`. Selector `0x100` adds/removes
an operation according to its enable argument. The wrapper's other selectors
configure unrelated settings; calling this wrapper does not itself imply
registration of a new PTP operation.

The wrapper's inspected caller contexts are:

| Context | Inspected selectors and significance |
| --- | --- |
| `Ptp_Init`, `0x405ca428` | Seven selector-`0x100` registrations for `0x1021..0x1027`, callback `0x405ca67c`, conditional on byte `0x40e139d8` |
| Same initializer | Selector `0x101` aliases `0x100c -> 0x900c` and `0x100d -> 0x900d`; other selectors configure formats, capabilities and buffers |
| Ordinary USB entry / USB UI | Selector `0x206` configures storage identity; literal `External Memory` is an argument, not a command callback |
| `clbt` shell handler | Inspected call at `0x40523628` likewise uses `0x206` |
| `0x405b98dc` / `0x405b9a70` | Selectors `0x20f`, `0x20e`, `0x20c` for buffers/capabilities; no selector-`0x100` registration in these bodies |

No exact pointer to `0x405e6868` or `0x402e753c` was found. This census does
not exclude computed calls, alternative addressing, other instruction sets,
external modules or an earlier boot stage.

Callback/event wrapper `0x405e6bc4` has ten found direct callers. In ordinary
`Ptp_Init`, it installs:

| Selector | Callback | Inspected body |
| --- | --- | --- |
| 7 | `0x405ca274` | Property descriptions/values for `0x5001`, `0x500c`, `0x5011`, special read of `0xd608` |
| 8 | `0x405c9efc` | Setter branches only for `0x500c` and `0x5011` |
| 9 | `0x405c9e08` | Checks count/status/card conditions, sets response `0x2001` or `0x2013` |
| 12 | `0x405c9c80` | Name/path filtering with `DCIM`, `FPATR`, AVI and drive-separator checks |

Selectors 7/8 reach `0x403341f8`, storing property callbacks in RAM at
`0x4138dea0` and `+0x10`. The filter is installed through `0x402e68e0`.
No direct call to the shell parser or known USB-cache writer appears in these
four callback bodies. Complete transfer semantics and every transitive callee
were not audited here; the filter is not evidence of arbitrary-file upload,
execution, or a safe upload procedure.

The other wrapper callers use selector 2 to emit events, or selector 12
to install/remove another filter. None of these ten inspected direct call
sites selects 11, the wrapper's optional callback setter `0x402e6924` for
the separately handled operation `0x9601`. Absence from this bounded census
does not prove that callback is always null on a real camera.

## Property setter: no USB-setting branch

Callback `0x405c9efc` compares its first argument with `0x500c` and `0x5011`.
Every other value returns at `0x405c9f14`, before accessing the supplied data.

- `0x500c` updates a property shadow and white-balance-related UI fields,
  then tail-calls `0x404432a4`. It is not a USB-selection field setter.
- `0x5011` copies/parses date/time data into UI state, then invokes
  retained-clock helper `0x40041ab4` at `0x405ca008` and `0x40127d00`.
  This is not a harmless cache-refresh command.
- `0xd608` has a special read branch in the description callback, but no
  matching setter branch. A property appearing in a getter does not establish
  that it can be written.

The six callback controls supply IDs `0x001f` (31), `0x101d`, `0xd608`,
`0x0114`, `0x500c` and `0x5011`. The first four return with all image/global
RAM unchanged. Known-property controls stop before the first mutation branch
(`0x405ca018`) or date/time copy helper (`0x40008b8c`). They verify branch
selection, not successful settings changes. They enter the callback directly;
the complete PTP validation and data-transfer path are not supplied by this test.

## Reset codes and command-arrival notification

Central dispatcher `0x402e778c` performs dynamic handler lookup and alias
resolution before its standard switch. With no overriding dynamic handler or
alias for the operation, codes `0x1010` and `0x1017` follow the inspected
unsupported branch `0x402e7d2c`, which sets response `0x2005`. The known
ordinary registrations above do not override those two codes. This is static
branch analysis, not a live response or complete reset-transaction emulator.

Before the standard switch, the dispatcher submits notification
`0x6251001a` with the operation value through `0x4006993c` at `0x402e7910`.
Thus even an unsupported operation can have an application notification;
an unsupported response alone would not prove absence of side effects.

In UI group `0x8000`, that notification selects `0x404d6d2c`:

1. Conditionally processes a resume latch. Its helper `0x401247f0` is a
   constant-zero leaf in this image; additional RAM conditions still matter.
2. Stores the message parameter as a word at `0x413cb55c`
   (`0x413ca9f8 + 0xb64`).
3. Conditionally clears runtime word `0x40e065b0` and byte `0x413e3195`.
4. Returns. This branch does not call `DPC`, post the parameter as another UI
   event, or refresh the USB-selection flag.

Eight controls deliver this notification through full application dispatcher
`0x40400d50` with UI state `0x8000`, both cached-flag values, resume latch
`0x40e7ae4a=1`, and optional runtime word `0x40e065b0=0`. Parameters are 31,
`0x1010`, `0xf2000023` and `0xf2000084`. Each case changes exactly the
last-operation word, preserving the USB cache and full staged cold buffer.
Only the print function is modeled as a return; no other callee is admitted.

The two `0xf200....` parameters are synthetic internal stress controls:
PTP operation fields are 16-bit and cannot encode those full values. These
tests do not construct or propose a packet carrying a 32-bit operation code.

## Evidence and limits

[ReproduceRetailPtpBoundaries.java](scripts/ReproduceRetailPtpBoundaries.java)
checks the original image hash, enforces allowed PC ranges, and compares
image/global RAM `0x40000000..0x413fffff` against each exact expected result.
Stack/input fixtures live outside that region. Results:
[retail-ptp-boundaries.txt](output/retail-ptp-boundaries.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceRetailPtpBoundaries.java /private/tmp/retail-ptp-boundaries.txt

python3 analysis/fw.py callers 0x402e753c 0x405e6868 0x405e6bc4
python3 analysis/fw.py disasm 0x405ca428 0x405ca5dc
python3 analysis/fw.py disasm 0x405c9efc 0x405ca0b8
python3 analysis/fw.py disasm 0x402e79b8 0x402e79f8
python3 analysis/fw.py disasm 0x402e7d2c 0x402e7d60
python3 analysis/fw.py disasm 0x404d6d2c 0x404d6db0
```

The existing local Ghidra ARMv7 project and original-image disassembly were
reused following the documented REA raw-image mapping limitation. REA session
inspection reported no open target; no fresh REA binary-analysis or runtime
evidence is claimed. No shell invocation, property mutation, reset request,
USB mode switch, RTC/file operation or camera interaction was performed.

The next distinct entry surface is the common USB control-endpoint dispatcher
while the camera remains in its retail PTP personality. It must be separated
from both PTP bulk operation packets and the already-known target-0 loader
requests; finding a request handler somewhere in the image does not establish
that the retail mode installs it. Safe loader activation and independent
recovery remain unresolved.
