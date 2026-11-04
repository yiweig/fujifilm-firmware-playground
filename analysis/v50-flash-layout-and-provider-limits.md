# V50 external-flash layout and earlier-provider limits

Reviewed 2026-11-03. Offline parsing of an already retained comparison artifact;
no new download, camera access, mounting, supplied-code execution or REA result.

## Result

The retained V50 external SPI-flash dump has a coherent layout: reserved headers
and DRAM-parameter records, two resource filesystems, a DRAM application image,
and a trailing FAT12 volume. This pass did **not** recover an authenticated
earlier-stage executable supplying the X-half low-memory helper `0x00000b34`.

Firmware-like bytes in the trailing volume are unallocated according to both
FAT copies, with an empty root directory. They could be remnants, but their
origin is unknown. They do not establish another active firmware slot, boot ROM
or recovery path. This is a bounded layout review, not proof that all possible
executable formats or resources have been exhausted.

The new work extends the [earlier artifact audit](public-boot-artifact-review.md),
which checked provenance and selected raw constants rather than filesystem
geometry. It does not change the [X-half provider requirement](low-helper-backing-boundary.md).

## Inputs and coordinates

Repository: [V50 Playground at dc437ced](https://github.com/Linouth/iCatch-V50-Playground/tree/dc437ced8fdc9564e39ca550af6a1d53b1037714).
The [hardware note](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/images/Akaso%20M50%20Pro%20SE/README.md)
describes an external Winbond flash-chip dump, not an internal CPU-ROM dump.
These are another camera's artifacts; X-HF1 compatibility is not established.

| Artifact under `reversing/v50ProSE/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `winbond_dump.bin` | 16,777,216 | `ef8ee75f9cd5a59c633b6b79ea0130c9605d3a86bfe2b9bbfc1498e51587fbf8` |
| `AKASO_V50 Pro SE_190315_V1/SPHOST.BRN` | 15,740,152 | `1f9db54f710d26af1be9f96a317f838fa52e0ca90225f602c45b287e93f2a2f9` |

All offsets below are **file offsets**, with exclusive ends. No CPU mapping is
inferred from them. The source repository's bootloader/DRAM labels are useful
leads, not independent authentication of X-half boot-ROM contents.

## Recovered layout

| Range | Interpretation and independent checks |
| --- | --- |
| `[0x0, 0x8000)` | Two 16 KiB reserved-header regions with `NAND RSV` and repeated `DRAMPAR3` markers |
| `[0x8000, 0x508000)` | 5 MiB FAT16 resource volume; root contains `RO_RES` |
| `[0x508000, 0x588000)` | 512 KiB FAT12 resource volume; root contains `UDF`, `USER_RES` |
| `[0x588000, 0xe77800)` | Header-declared application image; ARM branch prefix, `SPCA6500 V000` and `DRAM CODE` markers |
| `[0xe77800, 0xe78000)` | Remaining space in the application's reserved extent; not assumed erased |
| `[0xe78000, 0x1000000)` | Trailing FAT12 volume, empty root and all data clusters marked free |

The header interpretation follows the related eSD/NAND schema already studied
in the X-half firmware. Block size `0x1000`, total blocks `0x1000`, eight header
blocks, resource extents of `0x500` and `0x80` blocks, application start block
`0x588`, and reserved length `0x8f0` blocks agree with these boundaries. Each FAT
boot sector independently supplies geometry ending at the next derived region.
This agreement supports field roles; it does not decode every field or checksum.

The two 16 KiB header regions differ at relative offsets `0x41`, `0x4a` and
`0x4b`. This pass does not select an authoritative copy or assign a checksum
algorithm. DRAM-parameter markers alone do not identify executable early code.

## Allocation evidence and limits

| Volume start | Data clusters | Free / nonzero entries in first FAT | FAT copies equal? |
| --- | ---: | --- | --- |
| `0x8000` | 5,083 | 752 / 4,331 | No |
| `0x508000` | 985 | 969 / 16 | Yes |
| `0xe78000` | 3,083 | 3,083 / 0 | Yes |

Counts cover data-cluster entries, excluding the two reserved FAT entries. The
first volume's copies differ; counts use the first copy without reconciliation
or repair. This does not establish which copy reflects the camera's intended
state. No file chains from that volume were used as trusted executable evidence.

The trailing volume has root offset `0xe7aa00` and data offset `0xe7ea00`.
Both allocation tables agree that all 3,083 data clusters are free. An earlier
progress message gave 3,072 from a partial count; the full geometry corrects it.
Firmware-like strings in those clusters are not referenced files in this FAT
state. A raw scan cannot assign their original version, load address or purpose.
No deleted-file recovery, image extraction or filesystem modification was done.

## Main image and package comparison

The declared flash application is `0x8ef800` bytes, SHA-256
`736318865a79aefcb1966381ba291bc9bba2e07061fefcfed3165ffe0a02985a`.
The retained update package's selected main range is
`[0x612120, 0xf01720)`, length `0x8ef600`, SHA-256
`38c289af809bab856a3c7f6d4233838c7204737df9679472b9b870126df217b7`.
The package image is 512 bytes shorter and differs from the same-length flash
prefix. Being retained in the same directory does not establish identical builds.

A scan for seven aligned ARM unconditional-branch encodings finds candidates
only inside the selected application extent. Internal hits can be jump tables;
the pattern is neither a general executable detector nor proof that no code
exists elsewhere. No complete disassembly, decompression or resource traversal
was attempted in this pass.

## Reproduction and next evidence

Run against the separately retained pinned corpus:

```sh
python3 analysis/inspect_v50_flash_layout.py /private/tmp/icatch-v50-dc437ced \
  > /private/tmp/v50-flash-layout.json
```

Committed evidence: [v50-flash-layout.json](output/v50-flash-layout.json).
The script requires the two source hashes and checks header/FAT boundary
agreement. Validation also used the existing independent `fat16_read.Fat16`
reader to confirm the first volume's extent, root, data start, cluster count and
root names; wrong-hash rejection was checked. Both source hashes still match.

For X-half R2, the missing input remains authentic earlier-stage/low-memory
bytes with provenance and mapping context. This external flash layout supplies
neither those bytes nor a safe way to obtain them. R1 temporary retail entry and
C1 independent cold recovery also remain open in the [roadmap](ram-launch-and-recovery-roadmap.md).
