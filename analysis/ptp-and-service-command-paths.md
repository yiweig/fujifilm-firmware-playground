# PTP and service-command paths

## Scope and safety

This note records static analysis of the X half firmware's USB/PTP and internal
service-command paths. No undocumented command was sent to the connected camera.
The only runtime USB evidence used here is passive enumeration documented in
[`xhalf-usb-identity.md`](xhalf-usb-identity.md).

Analyzed artifacts:

- `FPUPDATE.DAT` SHA-256:
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`
- `firmware-main.bin` SHA-256:
  `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
- analysis ELF SHA-256:
  `7e5534e40adc45c14ca19b6d1ebd0ea730ccf1d13125eedcc35d802434133755`

Addresses below are virtual addresses in the analysis ELF.

## Conclusions

There are two separate mechanisms that should not be conflated:

1. The normal PIMA/PTP dispatcher has a dynamic table for custom PTP operation
   handlers. A hidden `EnablePTPAP.txt` marker enables seven factory-oriented
   operations, `0x1021` through `0x1027`. These operations manipulate language,
   identity, serial-number, barcode, and `COLDDATA.BIN` data. They are not an
   arbitrary command-shell bridge.
2. The internal shell dispatcher, which can invoke `rcaraw`, is reachable from
   an RCA/EUC "forward command" service. Static strings and source paths place
   that service in a UVC extension-unit request implementation, not in the PTP
   operation dispatcher.

The camera passively enumerated in its ordinary connected mode with one PTP
interface (USB class 6, subclass 1, protocol 1), not a UVC interface. Therefore
the current evidence does **not** show a way to invoke `rcaraw` through the
camera's ordinary PTP connection. The UVC service code may be dormant or require
a separate factory/calibration USB mode.

## Normal PTP operation dispatch

`sub_402e778c` is the central operation dispatcher associated with the embedded
`sidc/pima.c` implementation. It handles standard PTP operations and returns
response `0x2005` for unsupported operation codes.

Before its standard-operation switch, it calls `sub_402e75e8(opcode)` to look
for a dynamically registered handler. The relevant table is in RAM at
`0x4136b684`, with 100 entries of 16 bytes each:

- `sub_402e753c`: add/register an entry
- `sub_402e75a0`: remove an entry
- `sub_402e75e8`: look up the handler stored at entry offset `+8`
- `sub_402e7668`: look up the field stored at entry offset `+4`

This establishes that vendor operations can be added at runtime, but it does
not by itself say which ones are enabled in a retail boot.

## Hidden `EnablePTPAP.txt` operations

`sub_405219cc` tests for the exact path `D:\\EnablePTPAP.txt`. During startup,
`sub_405c8f64` stores the result in byte global `0x40e139d8` and then calls
`sub_405ca428`.

When the flag is nonzero, `sub_405ca428` registers PTP operation codes `0x1021`
through `0x1027`, all using the callback at `0x405ca67c`. Manual disassembly of
that callback shows a switch on the request operation code and branches dealing
with:

| Operation | Best current interpretation | Confidence |
| --- | --- | --- |
| `0x1021` | Language-setting read/write | derived from code and log strings |
| `0x1022` | User-ID setting read/write | derived from code and log strings |
| `0x1023` | Serial/barcode-related read | inferred; exact schema unresolved |
| `0x1024` | Serial-number setting write | derived from code and log strings |
| `0x1025` | Cold-data read/write, including `A:\\RO_RES\\COLDDATA.BIN` | derived from code and path string |
| `0x1026` | Status/response operation | inferred; exact meaning unresolved |
| `0x1027` | Chunked cold-data transfer, apparently in 16-byte pieces | inferred from buffer and offset handling |

The handler uses normal PTP responses including `0x2001` and `0x200A`. Those
numeric observations are stronger than any interpretation of their meaning in
this vendor context.

### Warning

Do not create `EnablePTPAP.txt` on a camera card merely to explore this path.
The enabled interface includes serial and calibration/cold-data writes. A bad
request could change persistent factory data even without flashing firmware.

## RCA/EUC shell-forwarding path

The firmware's shell dispatcher is `sub_401efacc`. `sub_40645900` is one of its
callers. That function handles RCA service records; service number `0xff` logs
`EUC_SERVNO_FWD_CMD` and forwards a supplied string to the shell dispatcher.
Other service numbers include trigger-snapshot and sensor-mode operations.

The path to this handler is:

```text
USB service callback sub_4061b004
  -> EUC category 0x1c, record ID 3
  -> RCA handler sub_40645900
  -> service number 0xff (forward command)
  -> shell dispatcher sub_401efacc
  -> registered command such as rcaraw
```

`sub_4061b0bc` registers the callbacks by calling `sub_4038ddb0` with USB API
selectors `0x500` and `0x501`. An earlier working hypothesis described
`sub_4038ddb0` as a generic event bus; decompiling the entire function corrected
that: it is a large USB API multiplexer and contains the source string
`sp5k_usb_api.c`.

The transport identification is supported by nearby shipped strings:

- `Hce/openpv/app_uvc_req_share.c`
- `UvcReq_ToPcProc_Rca>>> pSR->No=0x%X`
- `FromPcProc_Rca>>> pSR->No=0x%X`
- UVC extension-unit callback names such as `ClbtUvc_EUSetCur`

This is strong static evidence for a UVC extension-unit service protocol. It is
not evidence that the retail PTP interface accepts these packets.

## Reproducing the important static views

With the extracted `firmware-main.bin` present, the local helper can display the
indirect-only hidden PTP callback and its literal pool:

```sh
python3 analysis/fw.py disasm 405ca67c 405cad84
python3 analysis/fw.py literals 405cad84 405cae8c
```

Useful functions to open in REA, Hopper, or Ghidra:

- `sub_402e778c` — PIMA/PTP operation dispatcher
- `sub_402e753c` / `sub_402e75e8` — custom operation registration/lookup
- `sub_405219cc` — `EnablePTPAP.txt` test
- `sub_405ca428` — hidden-operation registration
- `0x405ca67c` — hidden-operation callback (may not be auto-created as a function)
- `sub_4061b004` — EUC USB callback dispatcher
- `sub_40645900` — RCA From-PC service handler
- `sub_401efacc` — internal shell command dispatcher

## Remaining questions

- What exact boot state exposes the UVC extension-unit interface?
- Is that state available on the X half without modifying persistent settings?
- What are the exact request/response structures for the EUC/RCA records?
- Can a documented or read-only factory handshake identify the mode without
  sending a state-changing command?

Those questions should be answered statically, from a service manual, or on a
recoverable RAM-loaded test environment before attempting live service traffic.
