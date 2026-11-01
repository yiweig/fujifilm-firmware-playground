# X-half USB loader mode entry investigation

Date: 2026-10-31. Static analysis only; no camera commands or card changes.
Target: official 2.00 `firmware-main.bin`, SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses are ARM runtime addresses, image base `0x40000000`.

## Finding and practical limit

The second caller of USB API mode 4 belongs to the factory shell command
`clbt`, specifically its `usb` subcommand. The direct branch changes USB
runtime configuration and selects the loader personality. No explicit
persistent write was found in that branch or the inspected resource parser.
This is a bounded static finding, not a guarantee that the full USB transition
has no persistent side effects.

Follow-up: [shell access and USB lifecycle](shell-access-and-usb-lifecycle.md)
identifies conditional filesystem remounts in the common USB transition and
an API-test USB character bridge, still dependent on personality selection.

We still lack a verified way to invoke this shell from a normally booted retail
camera. This discovery therefore does not yet provide a usable RAM-boot
procedure or recovery from damaged installed firmware. See the matched
[USB RAM receiver](xhalf-usb-ram-receiver.md) for what mode 4 exposes.

Follow-up: [normal UI loader entry](normal-usb-loader-entry.md) establishes
additional calibration-free mode-4 branches in USB event handling. Their saved
factory USB-setting gate is reproduced offline; temporary retail activation
remains unresolved.

## Command identity and mode-4 branch

The command table at `0x40e18094` contains these three pointers:

| Field | Value | Contents |
| --- | --- | --- |
| command | `0x40ce569b` | `clbt` |
| description | `0x40ce56a0` | `ClbtCommand` |
| handler | `0x40522ba8` | factory command parser |

The help routine at `0x4051cdd8` includes the embedded string
`clbt usb [USBMode] [EnConnect]`. This is an internal shell interface, not a
documented PTP operation. Names above come from image strings; functional
descriptions below are analysis labels.

The handler matches `usb` around `0x405234a4`, obtains the numeric mode and
connection flag from the shell's parsed argument array, then follows:

| Location | Operation | Observed effect |
| --- | --- | --- |
| `0x405234c8` onward | `0x4038ddb0(0x100, 0x100)` | common USB configuration selector |
| `0x4052363c..40` | `0x4038d764(0)` | USB disconnect transition |
| `0x40523644..4c` | `0x4015ca94(0x50, "USBHIDE.TXT")` | selects resource filename in RAM |
| `0x40523650..54` | `0x4038d6d0(4)` | selects API-test/loader personality |
| `0x40523658` | log string | `SP5K_USB_MODE_APITEST!!` |
| shared tail, call at `0x40523698` | `0x4038d764(connection != 0)` | applies requested connection state |

The common selector `0x100` reaches `0x4038df0c`, then `0x401b9628`.
That setter stores the supplied word at `0x40d7925c` and returns zero.
The neighboring getter at `0x401b963c` puts its low two bytes into USB device
descriptor offsets 12 and 13, the `bcdDevice` field. Thus this setup call
sets a runtime descriptor value to `0x0100`; it is not a flash-write routine.

Mode values 1, 2, and 3 select PCCAM, MSDC, and SIDC branches, respectively.
Their additional storage/UVC setup must not be attributed to the mode-4
branch. In particular, the NAND-versus-SD selection visible in the MSDC
branch does not execute in case 4.

## What USBHIDE.TXT means here

`0x4015ca94` is a resource API dispatcher. Resource selector `0x50` reaches
`0x4018b960`, which copies 64 bytes of the prepared pathname into RAM at
`0x40d7369c` and returns zero. Relative names use the resource prefix
`A:\RO_RES\` obtained through `0x4018b0e4(2)`. This sets the pathname
`A:\RO_RES\USBHIDE.TXT`. The shipped buffer initially names
`A:\RO_RES\USBSTR.TXT`.

This call does not create a marker file or immediately load executable code.
It is not evidence that placing `USBHIDE.TXT` on the SD card enters this mode.

A later event in `0x401e0c30` (event 4, branch `0x401e0d18`) conditionally
passes the stored pathname to `0x4018b988`, guarded by runtime initialization
state at `0x40fd0d4d`. That parser opens the resource, obtains its size,
allocates a buffer, reads and closes the file, applies recognized fields, and
frees the buffer. Recognized fields include vendor/product IDs, manufacturer,
product and serial strings, and MSDC/PTP strings. Inspected setters update
descriptor data in RAM. No explicit file-write call was identified in this
parser. File-I/O wrappers and the entire USB reconnect lifecycle have not been
exhaustively audited.

## Similarly named subcommands that write saved data

The same factory handler contains two distinctly different operations:

| Subcommand | Data changed | Persistent-write call |
| --- | --- | --- |
| `boottype` | halfword at `COLDDATA.BIN` offset `0x68` | `0x40523020` |
| `usbmode` | halfword at `COLDDATA.BIN` offset `0x6a` | `0x405230ac` |

Both read 1,024 bytes from `A:\RO_RES\COLDDATA.BIN` through `0x40125d94`,
replace the halfword with `0x0004` or `0x0018` according to whether the numeric
argument is zero, and write the 1,024-byte block through `0x40125e4c`.
The functional meaning of those values is not established here.

These operations are unsuitable for an initial temporary loader-entry test.
The `usb` and `usbmode` names must not be treated as interchangeable.

## Another possible entry lead: command-file reader

Function `0x40475d8c` logs the embedded name `Audio_ReadCmdFromFile` and uses
the literal `D:\CMD_LIST.TXT` (pointer at `0x40475ff0`). It reads the file,
interprets delimiters including `@`, `#`, and `*`, and sends selected text
ranges to `0x401efd50` at `0x40475fac`. That wrapper copies the text into a
stack buffer and invokes the established shell dispatcher `0x401efacc`.

This is real command-file-to-shell code, but its reachability is unproved.
The current image scan found no aligned direct ARM B/BL caller and no exact
32-bit pointer occurrence for `0x40475d8c`. That negative result does not rule
out all indirect/computed calls, interior entry points, or other instruction
encodings. The code could also be unused in this build. No startup autoexec
behavior, safe file format, or retail activation route is established.
Do not turn this discovery into a card test until a caller is identified.

## Reproduction and next questions

Original-byte checks with `python3 analysis/fw.py`:

```sh
python3 analysis/fw.py literals 0x40e18094 0x40e180a0
python3 analysis/fw.py disasm 0x405234a4 0x405236ac
python3 analysis/fw.py literals 0x40523bd8 0x40523c0c
python3 analysis/fw.py disasm 0x4018b960 0x4018b988
python3 analysis/fw.py disasm 0x401b9628 0x401b9658
python3 analysis/fw.py disasm 0x40522fdc 0x405230c0
python3 analysis/fw.py callers 0x40475d8c
python3 analysis/fw.py disasm 0x40475d8c 0x40475ff4
python3 analysis/fw.py disasm 0x401efd50 0x401efd74
```

Focused pseudocode was corroborated using the existing Ghidra 12.1.4 ARMv7
project and `QueryFirmware.java` for `40522ba8`, `4018b960`, `4018b988`, and
`40475d8c`. Indirect-call prototypes in decompiler output are imperfect;
instruction arguments and literal pools take precedence. No fresh REA raw
import was attempted after the previously documented base-mapping issue.

The next useful work is to trace retail-reachable shell activation and the
USB reconnect callbacks. The command-file reader is an additional static
lead, not yet a better-supported route than the factory/UVC path. After an
entry route is established and audited, design a descriptor-only observation
before any RAM upload. Recovery independent of the main application remains
a separate requirement before considering modified flash firmware.
