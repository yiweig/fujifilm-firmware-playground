# RAM boot entry points: first static pass

Date: 2026-10-31. Camera unplugged; no USB requests or vendor executables run.
Target: official 2.00 main image, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
mapped at `0x40000000`, little-endian ARMv7.

## Findings

Two application diagnostic commands provide concrete leads, but neither
establishes independent recovery or working SD dual boot.

### `sdboot`: implementation contradicts help

The command-table row at `0x40df9544` holds the name pointer, callback
`0x4030543c`, and help pointer `0x40b521f0`. The help describes an
embedded/external selector followed by a boot address.

The wrapper parses two arguments and calls `0x40345c80`. The worker:

1. Saves argument 0 in `r5`.
2. Gets an SD/ISP state pointer through `0x40e4e564`.
3. If state `+0x38` is zero, prints `nrFwBlks = 0` and returns 1.
4. Otherwise overwrites `r1` with state `+0x1c`, sets `r2=r3=0`, and calls
   `0x400778fc` with argument 0 still in `r0`.
5. Saves the read result, then unconditionally executes `blx r5`.

The helper at `0x400778fc` treats `r0` as the destination buffer, rounds the
byte count in `r1` to sectors, derives a starting sector from the SD/ISP state,
and calls the SD read routine at `0x4004ddb0`. It returns 0 only when the
reported byte count equals the rounded request.

Consequently the second parsed argument is discarded before use; the first
argument becomes both the read destination and execution address. The worker
branches even if the read returns failure. The advertised help must not be
used as a device-test recipe. No filename lookup, cold-reset selector, or
safe handoff/cache sequence has been established here. This is an application
shell command and requires working firmware plus access to that shell.

### `usbt`: running-firmware USB transition

Command-table row `0x40dfc1b0` associates `usbt` with `0x4031d498`, which prints
`Ready for Usb Boot ..` and then `Host Suspend ..`. It:

- calls USB configuration/enable routines `0x4038d764` and `0x4038d6d0`;
- queries a mode through `0x401836ec` and requests mode `0x88` when necessary;
- calls `0x400dfe8c` and issues five calls to `0x40003dd4` with selectors
  `0xba`, `0x80`, `0x7c`, `0x87`, and `0x84`;
- selects USB configuration 4, reconnects/enables, delays, then calls the
  wrappers `0x401dfe4c` and `0x401dfe88`.

The meanings and persistent side effects of the mode/selectors remain unknown.
The last two routines dispatch through OS objects and `0x40026f00`; they do not
themselves establish an image upload or execution primitive. A branch/pointer
scan found the command-table reference but no direct ARM callers of the handler.
The message alone does not establish entry into mask ROM.

## Vendor protocol leads

The locally retained public V50 archive is pinned to repository revision
`dc437ced8fdc9564e39ca550af6a1d53b1037714`.
`drivers/Reflash_V2.6.zip` SHA-256:
`88fdb53627ff7c347f2a060e50a4de04b43b78516e83144c072de0d926a64969`.
These are archive metadata/configuration observations, not X-half protocol
verification.

`Download/USB_CAM5000.ini` declares distinct operations:

| Name | Request | Index | Declared payload length |
| --- | --- | --- | --- |
| BootMethod | `0x70` | `0` | `0x0b` |
| ISP_Dnload | `0xfd` | `0x04f1` | `0x10` |
| BulkDnload | `0xfd` | `0x04f3` | `0x08` |
| DramParameterWrite | `0x78` | `0x1c` | `0` |
| DramAddrGet | `0x79` | `1` | `8` |
| CodeBufSizeGet | `0x79` | `2` | `8` |
| RAWCWrite / firmware program | `0x78` | `0x11` | `8` or `16` |

The archive changelog explicitly distinguishes mask boot (`bootmethod=0`),
ISP boot (`3`), and firmware boot (`1` or `2`). This provides a useful host-side
classification to trace in `USB_CAM.dll`, but not a verified request suitable
for the camera's retail PTP interface. Request direction, transport, payload
layout, execution trigger, and automatic actions after connection still need
to be recovered from code.

The supplied `spca6350_profile.ini` selects platform 6350, SPI storage, a
`spca6350isp.bin` loader at `0xa0040000`, and normal ISP parameters at
`0xa7ff0000`. Those values belong to another platform/profile and must not be
used as X-half RAM addresses. The profile's prose says NAND while its numeric
storage setting says SPI, further limiting trust in generic labels.

## Next bounded investigation

1. Trace `USB_CAM.dll`'s boot-method query, upload, and execution flow; identify
   whether connection alone can erase/program or modify configuration.
2. Follow X-half USB configuration 4 to its descriptors and request dispatcher,
   and compare against the host protocol. Trace the five shutdown selectors.
3. Recover how the SD/ISP state is populated; identify what on-card header and
   sectors the diagnostic reader expects. Seek an independent early-boot reader.
4. Only after transport and side effects are established, define a first device
   test. A working stock-firmware upload route would enable experiments but
   still would not prove recovery after damaged internal flash.

No ready-to-run upload command or bootable SD layout has been established.

## Reproduce and sources

Use `analysis/fw.py disasm` for `4030543c..40305490`,
`40345c80..40345cd4`, `400778fc..40077980`, and
`4031d498..4031d548`; use `literals` for `40df9544..40df9550`
and `40dfc1b0..40dfc1c0`. All ranges are runtime coordinates, end exclusive.
Read archive members with `unzip -p`; extraction/execution is unnecessary for
the configuration observations above.

- [Pinned V50 repository](https://github.com/Linouth/iCatch-V50-Playground/tree/dc437ced8fdc9564e39ca550af6a1d53b1037714)
- [Pinned vendor-tool archive](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/drivers/Reflash_V2.6.zip)
- [RAM-loading assessment](ram-loading-assessment.md)
- [Existing fallback evidence](firmware-copy-and-fallback.md)
