# Temporary USB selection: RAM staging and the refresh barrier

Date: 2026-11-01. Offline static analysis and original ARM emulation only.
No camera/USB access, SD markers, settings changes or live writes were performed.
Official 2.00 main image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Runtime base `0x40000000`, little-endian ARMv7.

## Finding

The existing factory PTP RAM-staging branch can change the **in-memory**
`FgUsbMode` field without executing its separate save-to-storage operation.
An offline composition of that staging branch, an explicitly supplied flag
refresh slice, and a supplied UI event reaches the mode-4 loader selector.

This is a candidate mechanism, **not a live activation procedure**. The cached
flag does not update immediately. The full UI route inspected before its
refresh includes RTC-backed state writes and other operations omitted by the
slice experiment. Safe retail reachability and absence of later saves remain
unproved. No camera test or host write client is prepared by this finding.

## Two different copies of the USB setting

The [normal UI loader gate](normal-usb-loader-entry.md) uses cached byte
`0x40e6696d`. Getter `0x40125284` instead reads `FgUsbMode` from shared
cold-data RAM `0x40e4df02 + 0x6a`. Initialization/refresh code calls that getter
and stores its inverse in the cached byte.

Consequently, modifying only the cold-data RAM field does not immediately
change the USB event decision. A refresh must happen before another
`0xf0000044` event can use the modified setting.

The factory callback at `0x405ca67c` implements separate operations:

| Branch | Effect in inspected code |
| --- | --- |
| `0x1027`, write direction | Copies 16 bytes from request parameter storage to cold-data RAM plus a 16-bit offset; no explicit storage write in this branch |
| `0x1025`, read direction | Reloads the 1,024-byte shared cold-data RAM buffer from its file; status is ignored |
| `0x1025`, commit direction | Writes the buffer to `COLDDATA.BIN`, writes RTC-backed state and performs further changes |

The write-direction copy is at `0x405cad44..0x405cad54`. Original instructions
mask the offset to 16 bits (`0x405caca0`). The shared buffer is not a private
PTP staging area: ordinary setting getters also consume it.

The firmware does not enforce a 1,024-byte bound here. Maximum start address is
`0x40e5df01`, and the final byte of a 16-byte copy can reach `0x40e5df10`.
The cached flag is farther away, at offset `0x18a6b` from the buffer base, so
this primitive cannot directly reach it with its existing 16-bit offset. This
range calculation is not a proposal to write beyond cold data; such writes
could corrupt unrelated runtime objects.

The callback's default completion can also report success for unsupported
directions. A success response does not establish that intended bytes changed.
The existing read-only host client continues to reject all write-direction
requests. No live PTP request was made here.

## Refresh sources and their limitations

The instruction slice `0x404d53b8..0x404d53d0` calls the getter and stores its
inverse in the cached byte. Within that slice, no file read, file write or RTC
write occurs. However, starting execution at that interior address in an
emulator supplies reachability that a real camera user does not have.

It belongs to UI-group-zero handler `0x404d51b0`. Event `0xf2000084` reaches
`0x404d52c8`, performs prior UI operations, and reaches the refresh. Some
substates take the path through `0x405c7ff8(0, 0)` at `0x404d53b4`.
That helper frees UI objects, changes runtime boot-state fields, and calls
**`0x40041be8` at `0x405c8118`** to save RTC-backed state. It also invokes
other RTC/state helpers and updates the UI state. This is not a RAM-only
wrapper around the refresh. The alternate `0x0301` substate bypasses that
particular helper, but has other prerequisites/callees and has not been
qualified as a reachable, safe sequence from normal PTP operation.

Another refresh at `0x405180a4` belongs to the handler with verified entry
**`0x40517b18`**, one instruction before its push prologue. The first
instruction adds `0x0e000000` to the event to normalize `0xf2000000..23`.
Index `0x23` selects this refresh. Its later log string names
`MSG_UI_START_ENTER_CUSTOM_RESET_END`; it must not be treated as a generic
host-callable refresh API. Any reset path has its own writes/prerequisites.

Other inspected getter callers include `0x40122910`, `0x40492368`,
`0x4054a010` and `0x405bb7b4`; a call to the cold-data getter alone does not
establish a store to the UI's cached byte. The previously identified direct
cached-flag writers remain the relevant refresh sites.

## File-hook checks

String/reference inspection found `D:\COLDDATA.BIN`, but the associated
routine `0x4051b6b8` constructs an **export**, not an automatic override:
it selects source `A:\RO_RES\COLDDATA.BIN` and destination
`D:\COLDDATA.BIN`, logs `Save [%s] to [%s]`, opens the source for reading,
opens/creates the destination with mode `0x25`, and copies chunks. This does
not show that placing a replacement cold-data file on the SD card will load
it into RAM.

Generic cold-data read wrapper `0x40125d94` reaches the resource/file reader;
`0x40125d98` is a convenience reload into the shared buffer. Inspected direct
callers of the convenience reload include `0x400df37c`, `0x400df8d4`,
`0x4040753c`, `0x4051d4c8` and `0x405daa5c`. A staged change is therefore
not guaranteed to survive an arbitrary startup/reinitialization path.

The read helper invokes `0x4015b5f8`, which on first use references
`A:\RO_RES\COLD.BIN`; this is not evidence of a `D:` SD override. Likewise,
`USBSTR.TXT`, `USBHIDE.TXT` and `USBTEST.TXT` resource selection is not an
activation marker. No temporary SD-file override was established in these
inspected paths. Other strings, computed paths or dormant file hooks are not
exhaustively excluded.

## Offline composition and controls

[`ReproduceTemporaryUsbSetting.java`](scripts/ReproduceTemporaryUsbSetting.java)
executes the original PTP callback with synthetic request parameters and RAM.
It verifies the firmware hash, rejects unexpected external callees, and stops
before the USB mode setter. File and RTC writers are never permitted callees.

1. Start with synthetic cold-data word `0x0118` and cached flag 1. Stage a
   16-byte block preserving all bytes except low byte `+0x6a`, changed to
   `0x14`. Verify the entire 1,024-byte buffer differs in exactly that byte.
2. Supply UI state `0x8000`, cable connected, cached cable connected, and event
   `0xf0000044`. It returns without loader selection: staging alone did not
   change the cached flag.
3. Explicitly execute refresh slice `0x404d53b8..0x404d53d0`, including the
   original getter. The flag becomes zero. Supply the same UI event: it reaches
   `0x4038d6d0` with mode 4, where execution stops.
4. Stage the original synthetic block and refresh again. The original full
   cold-data buffer, cached flag and ordinary event result are restored.

All three reported assertions passed. Output is retained in
[`output/temporary-usb-setting.txt`](output/temporary-usb-setting.txt).

Stubs are logging, memcpy/memset, PTP response construction, cable state and
descriptor setup. Event delivery and the interior refresh entry are supplied.
No mode setter, USB lifecycle, file I/O, RTC write or calibration code executes.
This does not validate transport, factory-handler registration, physical timing,
full UI transition, automatic saves, concurrent consumers, stale buffer handling
or real camera restoration. RAM restoration here happens before any actual
mode transition; restoration after a real USB handoff is not established.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting.txt
```

Focused pseudocode and refs were exported with `QueryFirmware.java` to
`/private/tmp/usb-override-cold-paths.txt`. Original instruction ranges can be
reproduced with `analysis/fw.py disasm`:

```text
405ca67c..405cad84   PTP callback, staging/commit separation
404d51b0..404d55dc   group-zero event routing and refresh
405c7ff8..405c81d8   broader UI cleanup and RTC save
40517b18..40517bbc   event normalization and jump table
405180a4..405180f0   reset-end refresh branch
4051b6b8..4051b93c   source-to-SD export routine and literals
40125d94..40125db4   shared-buffer reload wrapper
4015b5f8..4015b620   first-use COLD.BIN reference
```

The existing local Ghidra 12.1.4 ARMv7 project was used because of the previously
documented REA raw-image mapping issue. No new REA/runtime evidence is asserted.

## Next decision

The mechanism is more concrete than a guessed marker or boot combination, but
the crucial missing link is a safe real-world transition that refreshes the flag
while preserving RAM, avoiding unwanted saved-state changes and keeping factory
data out of later writes. A shared RAM copy is not automatically harmless: its
consumers can react immediately or a later path can save it.

The next bounded investigation is the alternate UI substate/wakeup route and
its preceding/following callees, rather than a live factory PTP write. A read-only
factory snapshot could qualify the starting state but does not resolve this
transition. Independent cold recovery remains unproved.
