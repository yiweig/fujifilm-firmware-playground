# Boot USB latch and StartMain producer audit

Date: 2026-11-01. Offline analysis only; camera not accessed. Official 2.00
main image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARM runtime coordinates, image base `0x40000000`.

## Result

The previously unnamed `StartMain` status bit has an identified source:
the application samples raw USB presence during early startup and sets bit 0
of its boot-state byte. The sampler does not clear that bit when USB is
absent. This identifies a boot-time latch, not a fresh presence check in
`StartMain`. Later writers and real connection behavior still matter.

The ten non-startup/non-wakeup MOV-immediate matches for `StartMain` belong
to nine initial-setup handlers. They explain how startup can resume after
setup screens, but do not establish a safe way to revisit that path after a
temporary USB-setting change. An event post is not a host-accessible command.

The `StartMain` prefix previously stubbed as `0x400410bc` traces to RAM
diagnostic buffering. Its bounded synchronous call chain contains no file,
RTC or flash-storage API call. This narrows one uncertainty; it does not
qualify the surrounding startup sequence or asynchronous diagnostic consumers.

## Identified boot-state source

The initialized word at `0x40d6d014` is `0x40e6bc84`, already identified as
the boot-state structure in [service boot selection](service-boot-selection.md).
The loader condition reads its first byte. This is distinct from the
boot-condition word at structure offset `+8` used for calibration selection.

`App_PreBootProc` (`0x400df618`), stage 1, clears 32 bytes of that structure
at `0x400df694..0x400df698`, constructs the `+8` word, then calls the sampler
`0x400df530` at `0x400df6a8`.

| Original instructions | Effect |
| --- | --- |
| `0x400df534` calls `0x40123d44` | Read byte `0x1000517a`, return its bit 0 |
| `0x400df53c` | If presence is zero, skip the assignment below |
| `0x400df540..0x400df560` | Call no-op `0x401250e4`; clear bit 4 of boot-state byte `+1`, set bit 0 of byte `+0` |
| `0x400df564..0x400df58c` | A second predicate could clear byte-0 bit 0, but its callee `0x40123c0c` returns constant zero in this image |
| `0x400df590..0x400df5a8` | Another possible flag update is disabled by constant-zero `0x40123d58` |
| `0x400df5ac..0x400df5d0` | GPIO `0x1000904c` bit 5 clear sets byte-1 bit 3; the test supplies bit 5 set |
| `0x400df5d4..0x400df5f0` | Another predicate is constant-zero `0x40123d7c` |
| `0x400df5f4..0x400df608` | Report byte-1 bit 6 through a diagnostic call |

Consequently, for this helper alone:

```text
new boot-byte-0 bit 0 = old bit 0 OR sampled USB presence
```

The preceding full-structure clear makes the initial bit zero in the inspected
early-startup sequence. The old-bit-one cases in the reproduction are controls
for the sampler's behavior, not evidence of retained state across power cycles.
This bit is ordinary RAM; it must not be confused with the RTC-backed reboot
record. No live boot-state bytes were read.

## Original-instruction reproduction

[ReproduceTemporaryUsbSetting.java](scripts/ReproduceTemporaryUsbSetting.java)
adds four initial-bit/presence combinations. Each uses the initialized image
pointer rather than substituting a pointer to a synthetic runtime object.
The 32 pointed-to bytes are supplied, as are the two MMIO inputs. Only the
sampler's final diagnostic call is stubbed; its getters and flag operations
execute original instructions. Assertions check all 32 resulting bytes and
the full staged cold-data buffer.

Each instance then sets raw presence to zero and delivers `StartMain` using
the unchanged sampled boot-state structure. Loader selection follows the
latched bit. This composition retains the previous `StartMain` prerequisites:
power-record low bits zero, null optional callback table, stubbed diagnostic
prefix and resource setup, explicit event delivery, and a stop before the
mode setter. It is not full early-startup execution or live enumeration.

All eighteen reported PASS groups passed (the two new groups include four
sampler cases and their four continuations). Retained output:
[temporary-usb-setting-boot-latch.txt](output/temporary-usb-setting-boot-latch.txt).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting-boot-latch.txt
```

## StartMain producer classification

The prior immediate-value census found twelve MOV-to-r0 sites. The startup
post at `0x40517910` and the separate `0x405aeed8` handler containing
`0x405af0b4` remain separate from the following ten sites:

| Handler entry | MOV site(s) before the event post | Context evidence |
| --- | --- | --- |
| `0x4047aa98` | `0x4047ac5c` | Callback pointer `0x4047ec48` in initializer labeled `SetupMenuPowerOnOpGuideInit` |
| `0x4047ec4c` | `0x4047ee30` | Callback pointer `0x4047f034` in initializer labeled `SetupMenuPowerOnFilmCamModeInit` |
| `0x4047f038` | `0x4047f1d4` | Embedded `SetupMenuPowerOnAutoPowerOffTempHandler` |
| `0x4047f278` | `0x4047f42c` | Embedded `SetupMenuPowerOnAutoPowerOffTempConfirmHandler` |
| `0x4047faac` | `0x4047fc10` | Embedded `SetupMenuPowerOnActivityRecordHandler` |
| `0x4048004c` | `0x404801e4` | Embedded `SetupMenuPowerOnAppHandler` |
| `0x4048043c` | `0x4048062c` | Embedded `SetupMenuPowerOnDateTimeHandler` |
| `0x4048259c` | `0x404827d8` | Embedded `SetupMenuPowerOnAreaHandler` |
| `0x40483048` | `0x4048321c`, `0x4048337c` | Embedded `SetupMenuPowerOnLanguageHandler` |

These are embedded firmware strings or callback-registration relationships,
not invented original symbol names. The entries were checked against original
instructions before explicit Ghidra decompilation.

The inspected completion tails clean up display objects, set UI state 1,
post companion event `0xf00000a3`, then post `0xf2000000`. Other pending-setup
flags can instead select another setup screen. The broader handler and all
queued companion effects must be included in any future live qualification.
Neither the existence of the tail nor reaching its final post proves that
preceding setup work preserved cold settings.

For example, the date/time handler has direct calls to `0x40041ab4` at
`0x404805c0` and `0x40480694` (verify with the range below). That helper stages
calendar fields and calls `0x40041550(0, calendar)`; the latter has distinct
calendar and RTC-register operations. This is a time-setting subsystem, not
the same six-byte factory reboot record. These broader effects are a reason
to audit the selected branch, not evidence that every setup completion writes
factory data or that ordinary date/time changes would brick a camera.

Ghidra pseudocode also shows graphics callbacks, resource releases and setup
state changes. No complete persistent-write or reload exclusion has been
established for the nine handlers. They are not proposed as camera instructions.

## Diagnostic prefix audit

```text
400410bc(channel, text)
  -> 400326a4(channel + 6, text)
     -> 40008cac(text)                   string length
     -> 4000459c(channel + 6, text, len) RAM record append
```

The append routine's descriptor array is `0x40d5dcc4`, stride 20 bytes.
For the observed channel argument 0, the effective record channel is 6.
It checks the channel, buffer pointer and flags, handles available space,
updates descriptor indices, writes timestamp/length metadata, and copies
the text. Its helper calls are:

- `0x4000c30c`: save/change interrupt-enable state using CPU status registers;
- `0x4000cc48`, `0x4000cc80`, `0x4000cd40`: timestamp helpers on different
  formatting branches (the last is the same timer subsystem; not a storage API);
- `0x40008b8c`: memory copy into the descriptor-selected buffer.

The timestamp-format branch at `0x40004714..0x40004734` can also read the CPU
counter directly. There is no indirect call in the inspected append body
`0x4000459c..0x400047e0`. No synchronous storage call was identified in this
prefix chain. This does not establish that a later task cannot export logs,
or that corrupt descriptor pointers are safe. The emulator still stubs this
prefix; the new evidence for it is static.

## Reproduce static checks and remaining work

```sh
python3 analysis/fw.py literals 0x40d6d014 0x40d6d018
python3 analysis/fw.py disasm 0x400df530 0x400df60c
python3 analysis/fw.py disasm 0x400df68c 0x400df6d4
python3 analysis/fw.py disasm 0x40123bf8 0x40123c14
python3 analysis/fw.py disasm 0x40123d44 0x40123d84
python3 analysis/fw.py disasm 0x400326a4 0x400326cc
python3 analysis/fw.py disasm 0x4000459c 0x400047e4
python3 analysis/fw.py disasm 0x4048043c 0x404807f0
```

Local Ghidra and original-byte disassembly were reused after the documented
REA raw-firmware mapping limitation. No new REA runtime evidence is claimed.

The remaining problem is entering or repeating a suitable application path
**after** temporary settings can be staged, without a reload discarding them
or a save retaining them. The latch source and diagnostic prefix are better
understood, but event timing, real callback-table behavior, later boot-bit
updates, incidental persistence and actual USB-loader activation remain open.
Independent recovery from a bad flash is still unproved.
