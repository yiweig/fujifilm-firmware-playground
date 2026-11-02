# Optional PTP 9601 hook: broader writer and lifecycle review

Date: 2026-11-02. The user confirmed the camera was unplugged. This pass was
entirely offline: no camera/USB access, host camera-app launch, card marker,
firmware patch, settings write or mode experiment.

## Finding

The broader search still found only the known explicit setter for the
`0x9601` callback. The sole newly identified nearby copy is bounded to 12 bytes
on its continuing path and cannot reach the callback slot. Neither registration
nor completion is used by the ten known application calls to the wrapper.

Taken together, this is evidence for an **unused optional SDK hook**, not a
discovered loader command. That interpretation is an inference. We have not
proved that the callback is always zero: the complete initialization of this
unbacked RAM region, every indirect writer and earlier firmware stages remain
unrecovered. The camera is not needed to establish the bounded findings below,
and this pass does not justify probing `0x9601` live.

## Artifact and address identity

Main image: official 2.00 ARMv7 little-endian, base `0x40000000`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Callback slot: `[0x4136b664,0x4136b668)`, or PTP state base `0x4136b650 + 0x14`.
It lies beyond the file-backed image end `0x40e38400`. We do not read zero bytes
from the image and present those as its runtime initial value.

Existing local Ghidra 12.1.4 metadata/pseudocode and original ARM disassembly
were reused. No new REA or device runtime evidence is claimed.

## Registration and completion are both unconnected in the inspected callers

Setter `0x402e6924` stores its argument into the callback slot at `0x402e6928`.
The only found aligned ARM direct caller is `0x405e6e54`, wrapper
`0x405e6bc4`, **selector 11**. Exact pointer-byte searches of the full main
image found no stored pointer to the setter or wrapper.

The companion completion helper is `0x402e6934`. When an event handle exists
at `0x4136b668`, it submits vendor response code `0xa601`, with three parameters,
through `0x40334f34`, then signals the event through `0x4002a790(handle,1,0)`.
Its only found direct caller is the same wrapper's **selector 1**.

The ten found application call sites use selectors:
`2,2,2,12,14,9,7,8,12,2`. None selects either 11 or 1. All are fixed immediate
values at those inspected sites, not unresolved arguments.
See [caller census](output/ptp-9601-wrapper-callers.json). This extends the
earlier registration-only observation to the matching completion path.

The `0x9601` dispatcher branch (`0x402e7bc4..0x402e7c6c`) checks the callback,
creates `HostRespEvt`, passes three command words to the callback and waits for
the event before cleanup. With no callback it skips that work and clears the
current data length. This branch does **not** itself identify a loader handler,
and it should not be described as guaranteed to return a particular success or
unsupported code when no callback is installed.

## Broader write/copy search

The existing scanner [find_usb_flag_stores.py](find_usb_flag_stores.py) now
accepts an address interval and also resolves constant-register BX/BLX targets
for its two known copy/clear helpers. These were necessary extensions: a word
callback can receive partial stores, and SDK code often loads a helper pointer
from a literal before BLX rather than using a direct BL.

At a 256-instruction lookback, the scan of aligned ARM encodings in
`[0x40000000,0x40ab0000)` reported:

| Search result | Count / finding |
| --- | --- |
| Candidate store encodings | 346,146, including possible data |
| Resolved overlapping stores | One: known setter `0x402e6928`, value supplied as argument |
| Direct B/BL encodings to the two helpers | 11,595 |
| BX/BLX register encodings considered | 51,463, including returns/data |
| Register targets resolved to the two helpers | 229 |
| Helper calls with resolved destination and size | 786 |
| Resolved bulk intervals overlapping the callback | Zero |
| Nearby destination with unresolved size | One: `0x402e5a80`, destination `0x4136b640` |

Result: [ptp-9601-writer-search.json](output/ptp-9601-writer-search.json).
This is a heuristic candidate search, not a whole-program proof. In particular,
unresolved incoming/computed pointers, other copy wrappers, Thumb, exclusive/
VFP stores and earlier stages remain outside coverage. The sparse Ghidra
database likewise supplies references, not a complete writer census.

## The nearby copy does not install this hook

At `0x402e5a80`, the firmware calls memcpy `0x40008b8c` with destination
`0x4136b640` and a dynamic remainder length in `r5`. Original instructions at
`0x402e5a50..0x402e5a68` require that length to equal **12** before continuing
to the copy. Otherwise they call error routine `0x401c04e0`.

That error routine logs, calls `0x40021a08`, and contains an unconditional
self-loop at `0x401c050c` if the callee returns. Thus its ordinary ARM path
does not return to memcpy with an oversized length. Hardware behavior of the
error helper is not asserted or tested.

The continuing copy writes `[0x4136b640,0x4136b64c)`. Reaching the callback
would require at least 37 bytes from that destination. It is a transport
fragment buffer preceding the PTP state, not evidence of a callback installer.
No malformed input or error condition was sent to the camera.
See [original ARM boundaries](output/ptp-9601-boundaries.txt).

## Startup and teardown inspected

Wrapper entry `0x406016a0` calls `0x4018d1d8`, PTP initializer `0x402e7100`,
storage/database setup `0x402e71a8`, and PTP task setup `0x402e64b0` on its
nonzero branch. The zero branch reaches teardown `0x402e6368` and
`0x4018d208`.

`0x402e7100` clears/reinitializes session, database/property resources and
other PTP state, and conditionally re-enables capture when its separate
capture callback is present. It does not explicitly install or clear the
`+0x14` host-response callback in its inspected body. Teardown reaches that
initializer again and registers transport hooks through `0x402e6314`; the
registered `0x401c0590` function is the **device-string setter**, not the
`0x9601` callback setter. Equal offsets such as `+0x14` in other structures
must not be mistaken for this callback.

This inspection does not establish the entire transitive initialization
graph or a cold-boot initial callback value. In particular, the
[memory-map report](memory-and-hardware-map.md) still records the unresolved
ownership/clearing of unbacked RAM. The inference that a missing installer
implies a zero pointer is deliberately not made.

Evidence: [qualified context export](output/ptp-9601-qualified-context.txt),
[selected references](output/ptp-9601-references.txt). Read the original ARM
where decompiler signatures or structure types are uncertain.

## Verification / reproduction

```sh
python3 analysis/find_usb_flag_stores.py --target 0x4136b664 --size 4 --window 256
python3 -m unittest discover -s analysis -p test_constant_store_interval.py -v
python3 analysis/fw.py callers 0x402e6924 0x402e6934 0x405e6bc4
python3 analysis/fw.py disasm 0x402e5a44 0x402e5a8c
python3 analysis/fw.py disasm 0x401c04e0 0x401c051c
```

Five hand-encoded ARM controls passed for interval overlap, a call that must
not change the search target, direct and literal-loaded bulk calls, and
invalid intervals. The default 96-instruction USB-byte search still returns
the same five store hits as its retained historical result, with no resolved
bulk overlap. These checks validate the changed scanner, not camera behavior.

The proposed callback investigation is **partially resolved**: its two API
ends and the newly found nearby copy are accounted for, and no installer or
loader target emerged. Complete absence and live pointer state remain unknown.
The useful recovery barrier is still entry to the separate loader personality.

Follow-up: [staged boot RAM initialization](staged-boot-ram-initialization.md)
finds the previously missed hardware zero-fill request covering this slot.
It strengthens intended-zero initialization for successful ordinary boot;
it does not observe a live zero or prove later writes absent. The CPU-store
search above did not cover hardware fill engines.
