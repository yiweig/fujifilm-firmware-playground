# FRM connection callers and programming boundary

Reviewed 2026-11-03. Offline PE inspection with REA/Ghidra and read-only
inspection of retained ZIP members. No Windows program execution, driver
installation, camera access, vendor requests, firmware upload or flashing.

## Result

The library's RAM-upload flow was already documented in the
[2026-10-31 host trace](vendor-usb-loader.md). This pass confirmed that baseline,
then investigated the previously unaudited **FRM application callers**.

FRM contains a worker that checks/prepares the ISP connection and then invokes
the Program All routine. The latter can issue total erase, reserved-space
allocation, formatting and firmware programming. A successful "Check ISP Core"
step is therefore not a stopping boundary for that worker. Another
connection-oriented routine prepares the ISP, selects storage and queries its
version, but invokes additional helpers whose complete effects remain unaudited.
Neither route qualifies the GUI as a read-only discovery tool.

The bundled bulk-driver INF explicitly supports `2aad:6500`, interface 5.
That supports a compatible *interface-family* lead despite the bundled FRM
profile selecting SPCA6350. It does not establish how the X half enters this
interface, what its pre-ISP cold USB identity is, or which payload is suitable.

## Artifact identity and address spaces

Pinned corpus: V50 Playground commit
`dc437ced8fdc9564e39ca550af6a1d53b1037714`. Original archives stay unchanged.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `drivers/Reflash_V2.6.zip` | 8,790,842 | `88fdb53627ff7c347f2a060e50a4de04b43b78516e83144c072de0d926a64969` |
| `Download/FRM.exe` member | 512,000 | `c5be8958bc694fe0b95a505d05fc475d8279df5f5a065570090aa3a78b392365` |
| `Download/USB_CAM.dll` member | 385,024 | `60720ce7e246dd34f6d2cdbab5f21a26cf82d90669c056586b54431c2fe303a6` |
| `drivers/iCatch PC Driver.zip` | 295,566 | `0e6d79b2c3e957d097678d03d316bd2cb754f05dd3ab375b9c700761b9414014` |

The first archive's member paths have prefix `Reflash_V2.6/`.
FRM is PE32 x86 at base `0x00400000`; USB_CAM is PE32 x86 at `0x10000000`.
These are host analysis addresses, not ARM camera addresses. REA 6.8.0 used
Ghidra 12.1.4. No accompanying Windows libraries were executed or installed.

## What the Windows side expects

The DLL enumerator `0x100199b0` selects a Windows interface GUID and calls its
enumeration helper. The default GUID bytes at `0x100496d0` decode to
`cb519fa1-e908-11d2-aadd-0060673487b7`; other selectors choose other GUIDs.
This is interface discovery, not a chip-ID attestation or a retail-to-loader
transition. The selected path is subsequently opened through `CreateFileA`.

The retained `iCatch PC Driver/Icatchai.inf` contains the hardware match
`USB\VID_2AAD&PID_6500&MI_05` and a bulk-device description naming the KV
series. It also lists other chip identities. The README's KX wording should
not be treated as an exact descriptor or exclusive chip identity.
The INF's broad support does not make the supplied SPCA6350 ISP compatible
with SPCA6500. The `.sys` driver's implementation was not analyzed here.

The default USB_CAM INI has older `04fc:5380` strings and `ID_NUMBER=0`;
the FRM user/profile files instead select platform 6350 and
`spca6350isp.bin`, with core address `0xa0040000` and normal ISP parameter
address `0xa7ff0000`. These files are multi-generation configuration material,
not an authenticated X-half profile. No address was substituted into a client.

## Application call paths newly inspected

### Shared connection wrapper: FRM `0x00413580`

The import IAT at `0x00446838` points to `FrmChcekIspCon`; local thunk
`0x00436598` is called at `0x004135d0`. Its enclosing wrapper chooses ISP and
optional parameter filenames plus addresses from a settings object, calls the
DLL connection routine, then calls `FrmStorageTypeSelect` on success.

The connection routine's name is a shipped export with the vendor's spelling.
The wrapper/function names beginning `FUN_` are Ghidra-generated, not symbols
from the original source. The wrapper is shared by several callers.

### Connection-oriented routine: FRM `0x0042e240`

This routine locates `USB_CAM5000.INI`, loads it, calls the connection wrapper,
and then requests the ISP version. It also calls UI/setup helpers such as
`0x00424010` whose transitive effects were not audited. The routine is identified
by its calls and status messages; its complete UI-event binding was not recovered.

The library call it makes can upload parameters, query/allocate loader memory,
reset/reconnect, and upload/run ISP code depending on the boot-method response.
Those actions were already established by the host trace and X-half receiver
follow-up. A dialog that says "connected" is not merely descriptor enumeration.

### Set-size/programming worker: FRM `0x004358f0`

This worker reports "Check ISP Core", calls `0x00413580`, and, if that returns
zero, calls `0x0042afa0`. It subsequently emits status including "Set Size OK"
or "Program All Error". Caller `0x00435440` updates reserve sizes from dialog
fields and invokes the worker after its own checks.

The inspected branch therefore links connection preparation directly to a
programming-capable operation. This is a static reachable branch with conditions,
not a claim that all FRM connections or ordinary GUI startup take it.

### Program All routine: FRM `0x0042afa0`

Under its full-programming branch (object field `+0x1c0 == 0`) and successful
checks, the code reaches:

1. `FrmNandTotalErase` at call site `0x0042b172`.
2. `FrmRsvAllocate`, then storage-dependent detection and size checks.
3. Conditional `FrmNandFormat` at `0x0042b2a0`, followed by `FrmUserDiskFormat`.
4. Later detection and `FrmNandFwProgram` at `0x0042b3ce`.
5. Resource updating and conditional RawC/backup writing and read verification.

Skipping an erase or firmware branch is not proof of a write-free operation;
other resource-write paths remain. The report does not recommend any GUI flag
combination or claim exhaustive persistent-effect coverage.

### Startup boundary

Dialog initialization `0x00433320` loads the transport INI, constructs UI
objects and invokes further setup. This pass did not close every event,
virtual-call, timer or helper edge. It **does not establish either automatic
erasure on launch or passivity on launch**. FRM and its drivers remain unrun.

## Existing upload evidence: confirmed, not a new entry mechanism

The refreshed DLL inspection agrees with the old report:

| Configured command family | Host action | Remaining limit |
| --- | --- | --- |
| IN request `0x70` | Boot classification; file size is placed in index/value fields | X-half handler allocates RAM for nonzero size; not a passive identity query |
| OUT `0xfd`, index `0x04f3` | Address/length setup, then chunked bulk data | X-half first use processes parameters; not an unconditional generic write |
| OUT `0xfd`, index `0x04f7` | Three-word upload-address/length/destination setup, then bulk data | Device-side completion performs the handoff; not an independent cold entry |
| Mask path, `0xfd/0x04f1` | After upload and idle check, sends `[address,0,0,1]`, then 256 bytes | Does not establish mask-ROM origin or a verified X-half recovery sequence |

The request fields are loaded from INI into globals. Their globals are not
all file-backed constants, so reading arbitrary DLL offsets as fixed commands
would be incorrect. Forwarded operations use the Windows driver's IOCTL
`0x22000c`; that ABI does not independently recover its USB setup packets.

See [host flow](vendor-usb-loader.md), [X-half receiver](xhalf-usb-ram-receiver.md)
and [low-helper boundary](low-helper-backing-boundary.md). The raw upload
helpers do not directly call explicit erase exports, but that does not establish
safe payload initialization, valid DRAM parameters or harmless device handling.

## Evidence and reproduction

- [Pinned archive/member metadata and selected configuration lines](output/frm-bundle-inventory.json).
- [Complete selected FRM pseudocode pages, import records and Evidence IDs](output/frm-connection-caller-rea-pages.json).

The selected-page export retains provider limitations and marks pseudocode as
derived analysis. Full bundles and snapshots are ignored local caches:
`rea-frm-dll-refresh-evidence.json`, `frm-usb-dll.rea-snapshot`,
`rea-frm-connection-evidence.json` and `frm-connection.rea-snapshot`, all under
`analysis/output/`. Both native sessions were closed after export.

Reproduce the bounded member inventory without extraction:

```sh
python3 analysis/inspect_frm_bundle.py /private/tmp/icatch-v50-dc437ced
```

For REA, extract only the named FRM/DLL members as data to a new scratch
directory, verify their hashes above, open each with Ghidra, and inspect the
listed function entries and IAT/thunk references. Do not launch them. Some
resolved instruction references lie outside Ghidra-defined functions; two
additional application caller sites were not decompiled and are not used to
claim startup/event reachability.

The missing input is still a verified X-half entry or authentic earlier-stage
provider. The driver match tells us what an existing loader interface may look
like; it does not tell us how to make the camera expose it. No reconnection or
live upload follows from these findings.
