# Vendor USB loader: host DLL trace

Date: 2026-10-31. Static analysis only; camera unplugged and no Windows
executable or DLL loaded for execution.

## Artifact and analysis identity

Archive: public V50 repository revision
`dc437ced8fdc9564e39ca550af6a1d53b1037714`,
`drivers/Reflash_V2.6.zip`, SHA-256
`88fdb53627ff7c347f2a060e50a4de04b43b78516e83144c072de0d926a64969`.
Member: `Reflash_V2.6/Download/USB_CAM.dll`, 385,024 bytes, SHA-256
`60720ce7e246dd34f6d2cdbab5f21a26cf82d90669c056586b54431c2fe303a6`.
PE32 x86 image base `0x10000000`; REA/Ghidra 12.1.4.
Exported function names below are shipped symbols, including vendor spelling.

## Result

Follow-up: the [FRM application caller review](frm-connection-and-programming-callers.md)
confirms the library baseline and traces its GUI-side wrapper, a connection
routine and a set-size/Program All path. The latter can proceed from ISP
preparation to erase/format/program operations. Full startup/event passivity
remains unqualified; the bundled bulk-driver INF supports `2aad:6500` interface 5.

The library implements distinct RAM-copy and upload/run paths, separately
from explicit flash erase/program functions. This makes a deliberately limited
RAM uploader plausible for this vendor family. It does not establish that the
X half accepts the protocol, exposes the necessary interface, or can recover
through it without working installed firmware.

The export `FrmChcekIspCon` is **not passive**. Depending on the boot-method
response, it can apply a parameter file, reset the device, upload and run an
ISP loader, wait for reconnection, and read device memory. Do not invoke it as
an identity check.

## Boot-dependent connection flow

`FrmChcekIspCon` at `0x10007e10` first enumerates the selected Windows device
interface, opens the local ISP file, and queries the device. The query's first
response byte selects the boot path; bytes 1 through 4 form a little-endian
address. The query also supplies the ISP file size through the index/value
fields, so blindly copying the INI's zero defaults would omit part of the
observed operation.

| Reported boot method | Observed host action |
| --- | --- |
| 0 (mask boot in changelog) | optional parameter upload, then `FrmMaskDnloadIspCore` |
| 1 or 2, address zero | sends byte `0xa5` through SetSpecialData, requests reset, reconnects, optionally uploads parameters, then mask-loader path |
| 1 or 2, address nonzero | optional parameter upload, then `FrmDnloadRunIspCore` |
| 3 (ISP in changelog) | skips the main loader transition and performs the final memory read |

Some branches wait and reconnect before reading 256 bytes through `FRMBulkIn`.
There are additional PID-dependent branches, including a parameter upload
after entering ISP for PID 6350. This is generic multi-platform machinery;
these branches do not identify an X-half implementation.

`WriteSettingFile` (`0x1000ad30`) uploads a local parameter file to a caller
address with `FrmFile2Dram`, then polls device status. Its failure text says
`Boot Parameter fail, please reset chip!`. The host trace does not prove that
the device's interpretation is volatile or harmless; applying DRAM parameters
is an active operation even though the initial transfer targets RAM.

## RAM-copy versus upload/run

`FrmFile2Dram` (`0x1000ac80`) reads a local file and calls
`DnloadBuffer2Dram` (`0x10022c10`). That routine serializes:

```text
u32 little-endian destination address
u32 little-endian byte count
```

It sends this eight-byte setup through `VendorCommandBulkWrite64`, then sends
the file bytes through `0x10016470`. The latter opens the bulk device path,
uses `WriteFile` for full chunks and a final partial chunk, checks Windows
write success/counts, and reports progress. There is no separate execute call
in these host functions. Device-side handling still requires inspection.

`FrmDnloadRunIspCore` (`0x10007bb0`) sends a different setup containing:

```text
u32 little-endian upload address (argument 2)
u32 little-endian byte count (argument 3)
u32 little-endian entry address (argument 4, interpretation inferred from use)
```

It then sends the bytes through the same bulk transfer helper. No separate
post-transfer jump command appears here; the inferred execution step is part
of the device's upload/run handler. Entry-address semantics need device-side
corroboration.

`FrmMaskDnloadIspCore` (`0x10007c70`) first copies the file to RAM, calls
`Check_FW_Idle`, sends a 16-byte ISP setup `[address, 0, 0, 1]`, then sends
another `0x100` bytes from the beginning of the ISP file through
`FRMBulkOutWild`. The final transaction's device-side meaning is unresolved;
do not reduce this path to a generic memory jump.

## Configuration and Windows transport

The bundled INI declares `BulkDnload` as request `0xfd`, index `0x04f3`,
eight-byte setup; `FRMDnloadRunISP` as request `0xfd`, index `0x04f7`,
12-byte setup; and `ISP_Dnload` as request `0xfd`, index `0x04f1`,
16-byte setup. These request fields are configurable rather than universal
constants. `Load_IniFile` at `0x1001e770` populates command globals; for
example, stores near `0x1002110c` populate the upload/run field group at
`0x100584f0`.

`VendorCommandBulkRead` (`0x10019db0`) and `VendorCommandBulkWrite64`
(`0x1001a050`) first call a software-command interceptor (`0x10013f00`).
Ordinary forwarded requests use `DeviceIoControl` with code `0x22000c`.
The driver request carries the request/index/value fields, payload and length
pointers, and a direction byte: 1 for read, 0 for write. This is a Windows
driver ABI; it is not by itself a recovered libusb control-transfer recipe.
Some intercepted requests are handled by the host software instead.

The low-level interface-enumeration helper (`0x100199b0`) selects a GUID and
enumerates device paths. This is distinct from `FrmChcekIspCon`, which actively
prepares the device. GUI startup and the complete FRM application call graph
have not been audited, so the program cannot be assumed passive on launch.

## Flash-writing boundary and limits

Separate exports include `FrmNandFwProgram` (`0x10008350`),
`FrmNandTotalErase` (`0x100088d0`), `FrmNorByteProgram` (`0x10009b00`), and
`FrmNorChipErase` (`0x10009ac0`). The first two have explicit programming and
erase control paths. The INI assigns NAND programming request `0x78`/index
`0x0e` and total erase `0x78`/index `0x0d`.

The inspected RAM transfer functions do not directly call these erase/program
exports. This is a bounded host-code observation, not proof of no flash writes:
the uploaded loader's initialization, device command handlers, parameter
effects, and the FRM caller's subsequent actions remain unverified.

## Next work

The [X-half receiver follow-up](xhalf-usb-ram-receiver.md) now matches the
`0xfd` index family, target-0 dispatch, the boot query's RAM allocations,
and the copy/execute handoff. It also finds a first-use parameter-processing
branch in `0x04f3`, so the host RAM-copy name alone does not imply a generic
device memory write. Verified retail mode entry remains unresolved.

Trace the factory caller that selects X-half USB API mode 4 and establish
whether entry changes persistent settings. Finish auditing the receiver's
parameter processing, memory ranges, completion/error handling, and shutdown
helpers. Separately inspect the vendor loader's startup for automatic writes.
An X-half-specific receiver and RAM map are needed before preparing any live
upload. Independent recovery still needs a boot entry available without the
main installed application.

## Evidence

- Export inventory: `ev_f9bfb9bdb6edb3e3f6d081221b7a11444dcfe41a38a2c29e299d1f06753c369e`
- Connection flow: `ev_ae8fe2f75eba2acedc98654c762557687c39b815ad5a751f25554ec7924ed591`
- Upload/run: `ev_8a4e6d99344dcc41ee73c34f1373c1df61aee787531db75bec8323cab013fc8d`
- Mask-loader upload: `ev_b51526aed2a918206edc6d20c4dc79da46a2749f537971539fe7eb85940fe728`
- RAM transfer: `ev_0e25b6eb087d904a1d1ce1d1da0f0d0b91210863ea53e0bb2e9034ad59e0b269`
- Parameter-file application: `ev_088b98ce695f20f9528aaa2de53aca81f639f326f621cc6ecf4786728016f242`
- Bulk bytes: `ev_9337ff6ba7e54290fefc01f7b8d4ff6b03d1e8a64fbf99f5bc43f8fe3b4e7c25`
- NAND programming: `ev_7716111ab642144ddee893a1ae45cc8c4344c9efb2b9336c420a2dc718d39e32`
- NAND erase: `ev_f12f0e57664e54dda1493e8e75b81c88c5d8c5fde979802cadb3980d4987e282`
- Driver write/read: `ev_688b052a9b66ff9f76d2ee6c85db8165ceca8f23f9e1f9714c59abe614c26ff4`, `ev_151146a295c94df8ddc8303802aca9d4640f0918c6bd13a2bb849b0e287c5724`

Full REA evidence is retained locally as
`output/rea-vendor-usb-loader-evidence.json` (ignored by Git).
Pseudocode is recovered analysis, not original source or observed execution.
