# Factory data read through ordinary PTP: candidate qualification

Date: 2026-11-01. Static analysis and offline emulation of official firmware
2.00 main image, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses are ARM runtime addresses, base `0x40000000`.
No camera connected, USB traffic sent, card marker created, or firmware modified.

## Finding

The existing factory PTP callback has a narrowly identifiable read path for
`A:\RO_RES\COLDDATA.BIN`. Unlike the calibration loader, its registration
occurs in the ordinary USB/PTP UI path. This is the strongest current candidate
for learning the camera's saved automatic-calibration flags before entering
calibration mode.

The callback-level read semantics are now reproduced offline. Retail
reachability, the complete USB lifecycle, and freshness of the returned file
data are not yet verified. This report does not authorize a live marker test.

## Enabling the interface is separate from calibration entry

`0x405219cc` passes `D:\EnablePTPAP.txt` to the same file-size helper used
by other markers. A nonzero file size produces true; empty or unopenable
files produce false. The helper does not inspect contents.

`EnterUIDpsGroup`, identified by its shipped log string, begins at
`0x405c8f64`. At `0x405c9030..40` it checks that marker, stores the result
in RAM byte `0x40e139d8`, and calls `Ptp_Init` (`0x405ca428`). It later sets
UI group `0x8000`, and has an explicit branch selecting USB API mode 3.
`0x405c9c18` enters this group from multiple ordinary UI-state handlers;
it is distinct from `EnterCalibrationMode` and group `0xe000`.

In `Ptp_Init`, the marker flag guards registration of operations
`0x1021..0x1027`, callback `0x405ca67c`, using `0x405e6868(0x100, op, 1, callback)`.
That wrapper reaches dynamic registration `0x402e753c`. The ordinary PIMA
dispatcher `0x402e778c` consults the table, then calls the registered callback
at `0x402e7848`, passing the request container address.

The inspected marker-dependent block registers callbacks; it does not call
calibration entry or explicitly rewrite a saved flag. A literal-pointer scan
found only `0x405c929c` and `0x405ca5bc` referencing `0x40e139d8`; computed
or aliased accesses are outside that negative search. This is not a complete
transitive audit of ordinary USB startup or filesystem internals.

## Exact callback operations

The callback reads opcode halfword at request `+6`, parameters at `+0x0c`
onward, and copies them into working state at `0x413e3308`. Relevant branches:

| Operation / parameter 1 | Effect observed in firmware | Persistent-write boundary |
| --- | --- | --- |
| `0x1025`, `0` | At `0x405cac24..44`, reads 1,024 bytes of `COLDDATA.BIN` into RAM `0x40e4df02`; returns `0x2001`, no response parameters | No explicit persistent writer in this branch |
| `0x1027`, high 16 bits zero | Low 16 bits are a byte offset; copies 16 bytes from that RAM buffer and returns five response parameters | RAM read only in this branch; no bounds check found |
| `0x1027`, high 16 bits `1` | Copies 16 bytes from staged request parameters into RAM buffer at the low-16-bit offset | RAM modification, not itself a file commit |
| `0x1025`, `1` | Writes all 1,024 bytes, then changes RTC-backed state, user settings, and other state | First persistent writer is `0x40125e4c` at `0x405cab48`; later `0x40041be8` writes retained RTC state |
| `0x1026`, `0` | Returns three zero parameters | Not evidence that a previous file read succeeded |

The chunk-read response is:

```text
response code: 0x2001
parameter count: 5
parameters: 0, word0, word1, word2, word3
```

The four words are the 16 bytes at the requested offset, interpreted
little-endian. They are PTP response parameters, not a separate bulk data
phase. `0x405cad04..2c` supplies them to `0x405e6ac0`, whose varargs wrapper
passes them to response-state setter `0x40334f34`.

Offsets for a bounded complete read are `0, 16, ... 1008` (`0x000..0x3f0`),
64 chunks. The callback itself does not enforce this range. This is a firmware
schema description, not a command sequence to run with a generic PTP console.

## Critical limitation: success does not establish freshness

The file-read wrapper `0x40125d94` tail-calls `0x4015ba48`. It opens with
argument `0x10`, checks file size, seeks, reads through `0x4013f598`, closes,
and returns an error result if the read fails or is short.

The PTP read branch ignores that result and reaches the common success reply.
A `0x2001` response therefore does not prove the RAM buffer was refreshed
from the file. Repeating reads and getting identical bytes alone would not
exclude stale RAM. No returned read-status value was found in `0x1026`.

The minimum flags sought are:

| File offset | Interpretation |
| --- | --- |
| `0x0a`, low byte | `DisableTest` |
| `0x6c`, halfword | `FgAutoClbt`; low nibble 8 enables ordinary automatic scheduling |
| `0x168`, halfword | Copied into runtime table `+0xabc`; nonzero can enable the automatic confirmation sequence |
| Per-item flag pointers | Needed to establish initial selection, completion and prerequisites; see calibration report |

A complete read would include device-specific factory information. Generated
captures must remain outside git, as with other camera dumps. The 1,024-byte
record is not a full flash or recovery backup.

## Offline reproduction

[`scripts/ReproducePtpColdRead.java`](scripts/ReproducePtpColdRead.java)
verifies the original image hash and executes the original callback at
`0x405ca67c`. It supplies synthetic PTP containers and a deterministic
1,024-byte fixture. Five external callees are stubbed: logging, file read,
memset, memcpy, and response collection. No USB stack, real filesystem,
startup, calibration worker, or persistent write is executed.

Results:

- `0x1025` with parameter 0 invokes exactly one correctly sized/path-checked
  synthetic read and returns the expected success response.
- All 64 chunk reads reconstruct the fixture byte-for-byte, and leave the
  cold-data RAM buffer unchanged.
- A negative-control request with `0x1025` parameter 1 reaches the persistent
  writer; emulation stops before executing it.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproducePtpColdRead.java analysis/output/ptp-cold-read.txt
```

Pseudocode exports for the callback, initialization, file reader and response
setter were cross-checked against ARM disassembly. Emulation validates only
the bounded handler behavior under these synthetic assumptions.

## Decision and remaining work

Prefer qualifying this ordinary-PTP path over activating calibration merely
to inspect flags. Neither a firmware patch nor the PLAY/MOVIE/flash combination
is required by the inspected PTP registration path.

Before a live experiment, finish the session/activation/exit audit, confirm
the camera's installed firmware identity, and prepare a reviewable client
that permits only the proven read operations with bounded parameters and
validates the response schema. The ignored file-read result still needs a
freshness strategy or an explicit limit on what the capture can establish.
No live USB client was created or executed in this pass.

No separate automatic-calibration suppression file was identified in the
previously inspected scheduling path. This candidate instead avoids that
mode entirely while gathering evidence. The calibration entry and safe-exit
questions remain open; see [the safety report](calibration-entry-safety.md).
