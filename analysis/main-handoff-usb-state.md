# Retail loader preparation preserves supplied PHY state in inspected slices

Date: 2026-11-03. Offline original ARM and local Ghidra controls of official
2.00 **main image**, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
base `0x40000000`. Every routine address below belongs to MAIN, not the
updater. No camera access or firmware changes.

## Finding

Follow-up: [optional handoff callback](main-handoff-optional-callback.md)
identifies calibration-path registration of callback `0x405da53c` through USB
API selector `0x600`. It calls backlight-related, lens-close and idle helpers;
five bounded controls establish registration/dispatch without running those
helpers. Ordinary retail callback state remains unobserved.

Main USB loader preparation `0x401eb1ec` is not an identified full USB power-down
sequence. Its inspected early helpers retrieve boot/configuration fields and
write them back; its explicit resource request acquires bit 17, separate from
identified USB resource bits 9/19. A later helper changes controller byte
`0x10005104` but does not itself release PHY or those resource references.

Four controls execute the original early preparation and the later controller
helper as **two separate slices**. Supplied PHY register bytes, USB resource
counts and PHY count remain unchanged in both. This makes inheritance of
existing USB hardware state a plausible explanation for a subsequent updater
path that skips explicit PHY acquisition. It does not establish that the full
handoff preserves that state or produces functioning USB.

The [updater gate/PHY finding](updater-startup-phy-gate.md) remains unchanged:
gate zero skips that consumer's identified acquisition. The new result narrows
what the inspected retail preparation does beforehand; actual physical state
and the complete transition remain unknown.

## The early helpers are getters and boot-field writes

Original instructions `0x401eb234..0x401eb278`:

| Main helper | Argument / result | Inspected behavior |
| --- | --- | --- |
| `0x4000c30c` | 0 | CPU interrupt-mask helper; modeled in controls |
| `0x40046ca0` | `0xffffffff` | Returns low three bits of byte `0x10004071`; this out-of-range argument avoids the setter branch |
| `0x4001d45c` | No new argument needed | Returns word from RAM `0x40e4ae94` |
| `0x40057dcc` | No new argument needed | Returns boot-status halfword `0x10004054` |
| `0x40046c44` | Previous low three bits; RAM word; status bits 2/3 | Writes their low bytes to `0x10004071`, `0x10004070`, `0x1000407b` |

These are concrete read/write effects, not a shutdown inferred from proximity
to a loader. The write-back is not an exact full-byte restoration: only the
previous low three bits are written to `0x10004071`, discarding its upper bits.
Electrical meanings of the fields are not assigned here.

The next explicit resource call at `0x401eb2f4` is
`0x4000b608(0x20000, 0x20000)`. Original wrapper/resource manager
`0x4000b48c` selects bank 0 and increments byte count 17 at
`0x40e48e2d + 17`. On zero-to-one it writes mask `0x20000` to command
register `0x10000064`. It is acquisition, not release of USB bits 9/19.
The actual embedded diagnostic names the source `hal_pwr/hal_phase_clk_set.c`.
This does not supply a complete hardware name for bit 17.

## Later controller operation and interrupt state

After intervening MMU/cache/workspace-copy preparation, main calls
`0x40055e88(1)` at `0x401eb354`. Its complete small body performs:

```text
byte[0x10005104] = (old_byte & 0x8f) | 0x30
```

That is a controller control-byte transition. Without a register manual or
runtime evidence it must not be labeled a confirmed disconnect, PHY power-off,
or safe host-session preservation. The helper itself does not call the known
PHY/resource release routines or change their counters.

The caller then disables IRQs and calls `0x40002374(0)`, whose original body
writes `0xffffffff` to seven words in each of GIC ranges
`0x10401180..0x10401198` and `0x10401280..0x10401298`.
This matters for later event delivery even if PHY clocks remain running.
That CPU/GIC sequence is static evidence here, outside the new controls.

The main PHY counter is at `0x40e4b220`; the updater uses a different software
counter at `0x400ba450`. They are not a shared reference-count object merely
because both images use the same hardware. Updater startup clears its own RAM
state. A cleared software count does not establish a powered-down physical PHY.

## Important boundaries before and after the slices

The receiver's state-6 branch at main `0x401e1f40` invokes an optional callback
from `0x40fd0d50 + 0x40` **before** preparation `0x401eb1ec`.
Setter `0x401e20f4` stores that pointer; its production value and full effects
are not resolved by these controls. The branch also calls an interface method
with operation 10 before copying preserved data to SRAM. Those operations are
not classified as read-only or USB-preserving here.

After the inspected prefix, MMU/cache work, copied stub, hardware copy,
unresolved helper `0x00000b34`, and payload entry remain separate boundaries.
The earlier [handoff report](ram-handoff-copy-boundary.md) describes them.
No conclusion about surviving physical USB may be extended across an unknown
callback/helper or the payload's own initialization.

This preparation also lacks a normal successful return to the original
application: after the workspace call returns, it restores interrupt-mask
state through its helper and loops forever at `0x401eb3a8`. That static
observation is not a recovery mechanism and does not imply a returning payload
leaves a functional camera.

## Four bounded controls

[InspectMainHandoffUsbState.java](scripts/InspectMainHandoffUsbState.java)
starts at original main preparation `0x401eb1ec`, with supplied arguments and
boot/configuration state. It stops before MMU helper `0x4000186c` executes.
A second invocation executes only later helper `0x40055e88(1)` on that fixture;
intervening preparation is deliberately not simulated.

Four cases combine resource-17 count 0/2 with controller byte `0x08`/`0xff`:

- The prefix writes expected boot fields `0x03`, `0x34`, `0x02` from supplied
  getters, and increments resource 17. It emits acquisition mask `0x20000`
  only for the zero initial count; the release-command sentinel is unchanged.
- Supplied USB resource counts at indices 9/19, main PHY counter 1, PHY bytes
  `0x10000380..0x10000387` and USB byte `0x10005021 = 0x3f` stay unchanged.
- The separate later helper changes controller byte `0x08 -> 0x38` or
  `0xff -> 0xbf`, preserving those same supplied PHY/resource values.
- Loaded-image bytes are checked unchanged in both slices.

All four pass. Only logging and the interrupt-mask helper are modeled in the
executed prefix. The original register getters/writer, resource manager and
controller-byte helper execute against synthetic memory. The initial enabled
USB state is supplied, not an observation of the camera. No actual IRQ/MMU/cache
shutdown, DMA, optional callback, low helper, payload, physical USB, storage or
camera executes. Source binaries are untouched.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectMainHandoffUsbState.java /private/tmp/main-handoff-usb-state.txt
```

Evidence: [four controls](output/main-handoff-usb-state.txt),
[main-image-qualified static ranges](output/main-handoff-usb-state-static.txt).
No new REA or live observation is claimed.

The next concrete upstream question is the state-6 optional callback's producer
and whether its selected value changes USB/resource state before this prefix.
Independent RAM entry and cold recovery remain unresolved. The camera can stay
unplugged; this is not a live handoff procedure.
