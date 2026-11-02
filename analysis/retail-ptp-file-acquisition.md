# Normal PTP downloads: no straightforward cold-archive acquisition route

Date: 2026-11-01. Offline static inspection and original ARM emulation only.
No camera connection, USB command, marker, firmware or factory-data change.

## Result

Normal PTP file download uses an object ID in a media database; it does not
accept an arbitrary file path. In the inspected implementation the normal
storage list omits internal drive `A:`, and the installed media filter rejects
the cold-resource directory and filenames in the tested contexts. This pass
does not establish a route to download the persistent `COLD.BIN` archive.

There is a useful distinction from the factory-record protocol: ordinary
download preparation reaches filesystem open `0x4001019c`, bypassing the
logical archive-member adapter `0x4013f4ac`. However, it first requires an
existing database node. The supplied PTP parameter is a node ID, not a path
or raw pointer that can be substituted for one.

This is a bounded result about the inspected retail path. It is not an
exhaustive security audit or proof that every vendor operation, alternative
database producer or indirect registration is unavailable.

## Evidence identity and request mapping

Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7 little-endian runtime base `0x40000000`. Local Ghidra was reused after
the previously documented REA raw-image mapping issue; no new REA runtime
observation is claimed.

Central dispatcher `0x402e778c` selects these handlers from its literal table:

| Operation | Handler | Role in this investigation |
| --- | --- | --- |
| `0x1004` | `0x402e8f30` | Enumerates normal storage IDs |
| `0x1005` | `0x402e90a4` | Storage information |
| `0x1007` | `0x402e93bc` | Returns IDs of existing database objects |
| `0x1008` | `0x402e9a38` | Metadata for a database object |
| `0x1009` | `0x402eae10` | Resolves an object ID and prepares download |

```mermaid
flowchart LR
  A[Storage enumeration] --> B[Filtered media database]
  B --> C[Numeric object ID]
  C --> D[Database lookup]
  D --> E[Path reconstructed from node and parents]
  E --> F[Filesystem open]
```

The diagram describes the inspected flow, not a live transfer or complete
audit of filesystem and USB effects.

## Storage enumeration

Helper `0x401b892c` supplies slots `[0, 1, 2, 6]`. Name lookup
`0x401b8adc` uses a shipped table at `0x40b1376c`: slot 0 is `A:`, slot 1
is `C:`, slot 2 is `D:`, and slot 6 is `H:`. This is a firmware mapping,
not observation that all these media are mounted on the camera.

`GetStorageIDs` skips enumeration index 0 before checking media availability
or returning a storage ID. The original-code control supplies IDs for all
four slots and models the remaining media as available. It returns only
the supplied IDs for slots 1/2/6, with success `0x2001`.

Absence from the advertised list alone does not prove that every explicit
storage-ID query is rejected. The initial database setup is also relevant:
`0x402e6544` has a special slot-0 branch at `0x402e6604..34` that creates
`DDISCVRY.DPS` bookkeeping and returns, instead of following its ordinary
filesystem scan branch. This observation is static; the complete initializer
is not emulated by the controls below.

## Database filtering

`Ptp_Init` installs callback `0x405c9c80` through selector 12 and setter
`0x402e68e0`; the callback pointer is stored at `0x4136b650`. See the
[registration audit](retail-ptp-command-boundaries.md).

Initial scanner `0x402e6544` calls this filter at `0x402e67a8`, passing
directory status, entry name and parent node's name. A nonzero return
excludes the entry. Refresh scanner `0x402e6b28` does the same at
`0x402e6d5c`, before adding a new node. Their filesystem enumeration and
complete scheduling are not reproduced in this pass.

Original filter instructions, including original name validators
`0x40534b30`/`0x40534b84` and string comparisons, produce:

| Entry / parent | Directory? | Result |
| --- | --- | --- |
| `RO_RES` / `A:` | Yes | Exclude |
| `USER_RES` / `B:` | Yes | Exclude |
| `DCIM` / `D:` | Yes | Permit |
| `DCIM` / `A:` | Yes | Permit: the filter itself is not a drive whitelist |
| `FPATR` / `D:` | Yes | Permit |
| `100_FUJI` / `DCIM` | Yes | Permit |
| `COLD.BIN` / `RO_RES` | No | Exclude |
| `COLDDATA.BIN` / `RO_RES` | No | Exclude |
| `COLD.BIN` / `DCIM` | No | Exclude |
| `DSCF0001.JPG` / `100_FUJI` | No | Permit |
| `DSCF0001.AVI` / `100_FUJI` | No | Exclude |
| `NOTDCF.BIN` / `100_FUJI` | No | Exclude |

These cases do not establish a rule for every filename or alternate filter.
They do show that simply finding a file on storage, or copying this archive
under its existing name into a familiar directory, is not enough to establish
that the inspected scan will expose it. No such copy was performed.

## Download preparation and handle lookup

`0x402eae10` reads its first parameter as a 32-bit object ID. Lookup
`0x402e84a8` walks nodes rooted at `0x4136bd28` and compares each node's
stored ID at `+4`; the input is not directly dereferenced as a node pointer.
ID 1 has a separate alias helper, outside the handle controls in this pass.

When lookup fails, the handler replies `0x2009` with zero data length. When
a non-buffered node exists, it calls preparation helper `0x402eab4c`. That
helper reconstructs the directory path with `0x402e82fc`, formats the full
filename from node data, and opens through `0x401b8d08`, which reaches
`0x4001019c`. A failed open supplies `0x2013`. Transfer reads, completion,
partial-object variants and the complete error lifecycle remain unaudited.

The controls supply a one-node synthetic database with ID 77. Values
`0x40e4df02` (the known factory RAM address), `0x444c4f43` (ASCII `COLD`
interpreted little-endian) and 123 are absent. All receive `0x2009` before
file preparation. ID 77 resolves the node, and execution stops before
`0x402eab4c`. A supplied synthetic node is a positive lookup control, not
evidence that an internal-file node can be created through retail USB.

## Reproduction and limits

[`ReproducePtpFileBoundary.java`](scripts/ReproducePtpFileBoundary.java)
passed 17 original-code controls: one storage-list case, twelve filter cases,
three unknown-ID cases and one known-ID control. [Retained output](output/ptp-file-boundary.txt).

The filter's validators and string comparisons, storage enumerator, handler
branches and object-lookup queue execute original instructions. Modeled
helpers are strlen, media availability, allocation/zero/free and response
submission. No filesystem is opened; the positive download case stops
before preparation. The tests also check queue allocation/free counts and
response data length. They are not a complete scanner or transfer emulation.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproducePtpFileBoundary.java /private/tmp/ptp-file-boundary.txt
```

The restricted host reader and its operation allowlist remain unchanged.
No storage-ID guessing, invalid-handle probing, filename manipulation or
upload was attempted on the camera.

The acquisition gap remains: establish a reachable read-only path to the
persistent archive, with explicit errors and a verified source. Existing
factory reads can return cached bytes; existing saves can mask persistence
failures. Normal media download does not resolve that gap on this evidence.
