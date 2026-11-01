# Service boot selection and retained state

Date: 2026-10-31. Static analysis of official firmware 2.00; camera not accessed.
Main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses are ARM runtime addresses, base `0x40000000`.

## Result

Calibration/loader startup depends on application-generated boot flags, saved
factory settings, and an SD-file size check. It is not an established immutable
recovery path. A factory reboot operation also writes a checksummed record
through RTC registers: avoiding NAND writes alone does not make it temporary.

The unresolved UVC/API-test selector still has no identified caller of its
setter. Its zero branch explicitly selects USB API mode 4 after broad
calibration setup. No safe retail activation procedure is established.

## Application construction of the boot-condition word

The initialized pointer at `0x40d6d014` contains `0x40e6bc84`.
`ProcessPowerOnEvent` reads its word at offset `+8`, i.e. `0x40e6bc8c`.
We now have a writer, not just an unexplained earlier-loader input:

```text
App_PreBootProc, 0x400df618, stage argument 1
  -> clears 32 bytes of pointed-to boot state, 0x400df694..98
  -> 0x400df12c builds a boot-condition mask
  -> stores the result at boot-state +8, 0x400df6a4
  -> ProcessPowerOnEvent later consumes that mask
```

`0x400df12c` logs `pwrSrc` and `BootKey`. It obtains a power-source word
through `0x4001c724` (a RAM-state getter), with fallback query `0x4001d880`.
It combines that with state at `0x40e4ae64`, saved settings, and inputs from
`0x40122ef4`. That input collector reads peripheral bytes at `0x1000a10e`,
`0x1000a10d`, `0x1000903c`, `0x100003a3`, plus software state. Some inputs
are inverted before packing. Physical button names and a valid button sequence
are not established by these bit operations.

Follow-up: [the button investigation](service-mode-button-candidate.md) maps
the relevant inputs to PLAY, MOVIE and flash ON, and reproduces the outer gate
in offline ARM emulation. Live timing and the later setting gate remain open.

One branch can return base mask `0x00300002` when power-source prerequisites
and saved-state bits `13..14 == 3` agree. Other inputs add bits to the result.
This does not imply a particular three-button combination.

## Saved factory settings and the SD marker

The shared cold-data buffer is `0x40e4df02`. Diagnostic printing at
`0x4051be18..2c` supplies the first two field names below:

| Cold-data offset | Name / role | Consumer |
| --- | --- | --- |
| `0x68`, halfword | `FgBootType` | `0x4012526c`: returns 1 when bit 3 is clear |
| `0x6a`, halfword | `FgUsbMode` | `0x40125284`: returns 1 when low nibble differs from 8 |
| `0x0a`, low byte of halfword | `DisableTest`, established by follow-up diagnostic trace | `0x405219a0`: reloads 1,024 bytes of `COLDDATA.BIN` and returns this byte |

The USB getter also stores whether bits `8..11` are nonzero in a RAM flag at
`0x40e7ae50`. These observations are not a complete cold-data specification.

Define `B` as boot-state `+8`, `T` as the boot-type getter, and `U` as the
USB-mode getter. The outer calibration decision in `0x40517454` accepts:

```c
((B & 0x00300002) == 0x00300002) || ((B & 2) != 0 && T != 0 && U != 0)
```

Inside that branch, zero cold-data byte `0x0a` or a successful marker check
can lead to events `0xf200000f` and `0xf2000012`, logged as `Start Clbt mode`.
Actual code repeats some setting/file checks; this is not a complete executable
replacement for its control flow.

Marker helper `0x40521984` passes `D:\EnableTest.txt` to `0x4015c52c` and
tests for a nonzero result. That function opens the file, gets its size,
closes it, and returns zero on open failure. An empty file therefore fails
this check. This is an analysis detail, not a card-test instruction: the
check remains nested behind boot conditions and leads into calibration.

Shell branches matching `pc_mode` and `test_mode` at `0x40682cc4..dd0`
rewrite `COLDDATA.BIN` through `0x40125e4c`, at calls `0x40682d28` and
`0x40682db0`. They are not temporary switches.

## RTC-backed reboot selection

State at `0x40e4ae64` has a backing record accessed by:

- `0x40041c44`: reads six bytes via `0x400414c4`, checks the sum of the
  first four against byte 5, and reconstructs a 32-bit word on success.
- `0x40041be8`: packs the word little-endian, zeroes byte 4, sets byte 5
  to the byte sum, and writes all six through `0x4004148c`.

Writing reaches `0x40028608`, then `0x400283a4`, then RTC register writer
`0x40057484`. The low-level writer uses `0x10000980..0x1000098a` and embeds
`[ERR rtc] reg write time out`. The read path reaches `0x40057524`.
This supports identifying RTC-backed boot state; retention duration and
behavior after battery removal are not established.

Factory reboot configuration `0x40523dd0` logs mode 4 as rebooting to
calibration. Its later worker `0x40523e94`, mode-4 branch `0x40523f0c`,
clears saved-state bit 9 and sets bits 13 and 14. The common tail clears
additional bits and calls `0x40041be8` at `0x40523f38` to write the record.
This explains a software-produced route toward the special startup mask.
It requires factory command access and changes retained state, so it does
not solve initial shell access or independent recovery.

## Calibration selects the application loader

At `0x405dd6a4`, calibration setup reads `0x413e6034` through `0x40613da8`.
If zero, it calls `0x405cbb84` to select `USBSTR.TXT` and set the USB release
value, calls `0x4038d6d0(4)` at `0x405dd6cc`, then connects through
`0x4038d764(1)` at `0x405dd6d4`. This is another explicit mode-4 caller,
in addition to `usbt` and `clbt usb`. Earlier in the routine, capture and
calibration state changes make this broader than a loader-only transition.

The selector's literal base is `0x413e6030`; getter/setter access offset 4.
Aligned branch and pointer scans find no caller of setter `0x40613db8`.
The memory-access census likewise identifies only that explicit store to
`0x413e6034`. Bulk initialization, computed writes and earlier-stage inputs
are not excluded. The selector's default cannot be assumed from absent callers.

## Packaged USB resources and loader-ID protection

The FAT16 resource disk A starts at package offset `0x000a3d20`. Direct
file reads, without mounting or modifying the image, give:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `RO_RES/USBHIDE.TXT` | 528 | `1717a2edb31d5f84fcb19c1008658397e6088d97b46cbab6d075763e95fe1075` |
| `RO_RES/USBSTR.TXT` | 514 | `960975e77787e32a7497c306fff11c5d28f073f8d96d3a64f9e1145039503ba4` |
| `RO_RES/USBUVC.BIN` | 801 | `1cb0b44e00dd3d8b550bd37eebfea4b9db5cc6e3f6239a8413f6bdca787f9675` |

Both text files specify VID `0x04cb`, PCCAM PID `0x030b`, MSDC/SIDC PID
`0x030a`; neither contains `USB_VNDR_PRODUCT_ID`. `USBHIDE.TXT` uses older
model text `FinePix HF10`, while `USBSTR.TXT` names `X-HF1`. These are
packaged templates, not evidence of a different physical camera.

Mode 4 sets protection flag `0x40fc79e4` via `0x401b94dc`. While set,
vendor-ID setter `0x401b9480` writes saved VID `0x40d79258` instead of the
active device VID. Product setter `0x401b9540` similarly redirects target-0
PID changes to saved PID `0x40d7925a`. Thus the active `2aad:6500` prediction
survives this resource-parser check. It is still not observed enumeration.

`USBUVC.BIN` begins with a configuration descriptor, not a device descriptor;
its first configuration declares total length `0x02fb` and four interfaces.
VID/PID cannot be read from its initial bytes. The short-name resource
directory also contains `COLD.BIN`; it is not assumed equivalent to the
camera's live `COLDDATA.BIN` or its individual settings.

## Reproduction and boundary

The DOS-8.3 inspector reads a regular firmware file without mounting it:

```sh
python3 analysis/fat16_read.py FPUPDATE.DAT --offset 0xa3d20 --path /RO_RES
python3 analysis/fat16_read.py FPUPDATE.DAT --offset 0xa3d20 --path /RO_RES/USBHIDE.TXT --text
```

Use `analysis/fw.py disasm`, `literals`, and `callers` for the addresses
above. Ghidra 12.1.4 ARMv7 pseudocode corroborated `400df12c`, `4012526c`,
and `40125284`; original instructions take precedence over imperfect types.
Local Ghidra was used after the documented REA raw-import mapping issue.
No additional early boot-ROM bytes were recovered.

The next useful evidence is an audited retail mode-entry route or boot-stage
artifact. This trace explains application calibration and retained reboot
selection; it does not justify an SD marker, settings change, or firmware flash.
