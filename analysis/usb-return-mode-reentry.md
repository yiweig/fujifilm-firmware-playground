# USB return modes and the cached selection gate

Date: 2026-11-01. Offline original-byte analysis and ARM emulation only.
No camera commands, SD markers, settings writes or USB connection were used.
Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Image base `0x40000000`, little-endian ARMv7.

## Result

Both camera modes selected by the [PTP exit path](ptp-ui-state-and-exit.md)
have USB-entry code that selects the loader branch from the **cached UI flag**
at `0x40e6696d`, rather than reading the staged cold-data field afresh.
Ten offline controls reproduce that distinction, with different coverage for
the two modes as described below.

This excludes an implicit refresh at these particular USB-entry decisions.
It does not prove that all intervening mode initialization leaves the flag
unchanged. Staging the cold-data field, unplugging and reconnecting is still
not a qualified loader-entry procedure. The shared-buffer reload and save
hazards in [cold-buffer lifetime](cold-buffer-lifetime.md) also remain.

## Mode-entry boundary

The exit decision selects:

| Entry | Embedded diagnostic name | Inspected state stores |
| --- | --- | --- |
| `0x405768e0` | `EnterUIPlaybackGroup` | `0x1006` at `0x40576938` |
| `0x404250a4` | `EnterUIViewGroup` | `0x2000` at `0x40425628`, `0x40425774`, `0x404257b8` |

Names come from diagnostic strings referenced by these functions. The entry
bodies were decompiled in the existing ARMv7 Ghidra project and compared with
original instructions. No direct call to USB-setting getter `0x40125284`,
previously identified flag-refresh sites, or cold-buffer reload helper
`0x40125d98` was identified in these bodies. Their many callees and later
asynchronous events were not exhaustively audited; no transitive absence of
refreshes, reloads or writes is claimed.

Playback entry calls `0x40126e84` after obtaining a timestamp. Inspection of
`0x40126e84..0x40126f1c` identifies a RAM diagnostic-ring append using an index
and fixed-size string copy. That call is not itself a cold-settings reload.

## Shooting-mode USB entry

UI group `0x2000` dispatches to `0x404d7e7c`. Event `0xf0000044` branches from
`0x404d7ebc` to `0x404d825c`:

1. Request delay `0x4006a074(3, 200)`.
2. Read cached presence through `0x40123bf8`; return if zero.
3. Check `0x40530b50` (constant zero here), Wi-Fi-related byte `0x413cb560`,
   and optional UI-cleanup condition `0x413ca9fa`.
4. Load cached selection byte `gsUI + 0x101d` at `0x404d82b4` and again at
   `0x404d82c8`; branch on zero at `0x404d82d0`.

Zero selects `0x404d87f8`: call `0x405cbb84(1)`, then
`0x4038d6d0(4)`, then tail-call `0x4038d764(1)`. Nonzero takes the ordinary
path, whose first call is `0x4000f9d0(5, 0x100, 0)` at `0x404d82e0`.
Downstream charging/PTP work is not executed in these tests.

Presence getter `0x40123bf8` reads bit 5 at `0x40e7ab8c`, a runtime cache
distinct from physical USB detection at MMIO `0x1000517a`.

## Playback-mode USB entry

UI group `0x1000` dispatches to `0x404d973c`, including the load before its
push. USB-in handling begins at `0x404d999c`. An early check returns when
cached presence matches `gsUI + 0x101b` and the selection flag is nonzero.
Later guards cover Wi-Fi, sending, background processing, UI objects and
prior mode. The path calls `0x404d9700`, which invokes peripheral helpers
`0x40043264` and `0x4038ddb0`; these were not modeled as harmless returns.

After these guards/cleanup, `0x404d9a6c..0x404d9ac4` loads the same cached
selection byte and branches on zero to `0x404d9c70`. That branch calls
`0x405cbb84(1)`, `0x4038d6d0(4)` and `0x4038d764(1)`, matching the shooting
sequence. Nonzero reaches ordinary-path call `0x4000f9d0(5, 0x100, 0)` at
`0x404d9ad4`.

Playback tests deliberately start at the **interior gate** `0x404d9a6c`.
They do not qualify preceding guards, peripheral cleanup, event delivery or
reachability from a real playback session.

## Reproduced controls

Script: [ReproduceUsbReentryGates.java](scripts/ReproduceUsbReentryGates.java).
Retained results: [usb-reentry-gates.txt](output/usb-reentry-gates.txt).
The script verifies the image hash before executing original ARM instructions.

Each mode independently varies cached flag 0/1 and RAM `FgUsbMode`
`0x0114`/`0x0118` at `0x40e4df02 + 0x6a`:

| Cached flag | Cold field | Observed stop boundary, both gates |
| --- | --- | --- |
| 0 | `0x0114` | Before `0x405cbb84`, on the statically identified mode-4 branch |
| 0 | `0x0118` | Same boundary |
| 1 | `0x0114` | Before `0x4000f9d0`, on the ordinary branch |
| 1 | `0x0118` | Same boundary |

Getter `0x40125284` would return 1 for low nibble 4 and 0 for low nibble 8;
known refresh sites store its inverse. These fixtures include both matching
and deliberately stale field/flag combinations. Neither tested gate calls
the getter; the contrasting cold-field value has no effect.

Shooting tests start at application dispatcher `0x40400d50`, supply event
delivery and state `0x2000`, set Wi-Fi/optional-cleanup bytes to zero, and
execute the original group dispatcher and cached-presence getter. Two extra
shooting controls with cached presence zero and either flag return without
entering either downstream branch.

Only print `0x40004064` and delay `0x4006a074` are modeled as returns.
Playback supplies state `0x1006`. All ten cases verify image/global RAM
`0x40000000..0x413fffff` unchanged, excluding the separate synthetic stack.
Execution stops **before** either selected downstream call. It does not
execute peripheral preparation, USB mode setter, connection API or loader.
The positive boundary establishes branch selection, not successful RAM boot.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceUsbReentryGates.java /private/tmp/usb-reentry-gates.txt

python3 analysis/fw.py disasm 0x404d825c 0x404d82e4
python3 analysis/fw.py disasm 0x404d87f8 0x404d8818
python3 analysis/fw.py disasm 0x404d999c 0x404d9ad8
python3 analysis/fw.py disasm 0x404d9c70 0x404d9c90
```

## Remaining qualification

Full USB exit, remount and mode initialization have not been composed into
an execution test. Independent fixtures must not be combined into a claim
that real staged settings survive that lifecycle. The remaining entry
question is an accessible route to an actual cached-flag writer, including
the effects of its enclosing path. Another caller merely checking that flag
does not close the gap. Independent recovery remains unresolved.

Analysis reused local Ghidra and original-image disassembly after the
previously documented REA raw-image mapping limitation. No fresh REA or
device-runtime evidence is claimed.
