# Public iCatch boot artifacts: bounded comparison review

Date: 2026-11-02. Read-only review of retained V50 comparison files and public
research/manufacturer pages. No additional firmware downloads, vendor program
execution, driver installation, camera commands or disassembly.

## Result

This pass did not identify authentic X-half/SPCA6500 low-helper bytes or an
independent, non-invasive recovery entry. It found useful comparison material,
but nothing that qualifies calling `0x00000b34` or launching another camera's
programmer on the X half. The helper's code/cache-synchronization role remains
a hypothesis from its X-half callers, not a matched implementation.

Follow-up: [public entry/provider refresh](public-entry-evidence-refresh.md) checks
new FujiMod metadata, confirms the same upstream V50 HEAD, and reads older MIPS
softmod source. No newly verified X-half provider or recovery route was obtained.

## Retained V50 corpus

The previously acquired [V50 repository](https://github.com/Linouth/iCatch-V50-Playground/tree/dc437ced8fdc9564e39ca550af6a1d53b1037714)
is pinned to `dc437ced8fdc9564e39ca550af6a1d53b1037714`. The repository's
[hardware note](https://github.com/Linouth/iCatch-V50-Playground/blob/dc437ced8fdc9564e39ca550af6a1d53b1037714/images/Akaso%20M50%20Pro%20SE/README.md)
identifies its Winbond artifact as an external flash-chip dump. That is not
an authenticated CPU boot-ROM dump or a CPU address-space snapshot.

| Retained artifact | Size | SHA-256 / observation |
| --- | --- | --- |
| `reversing/v50ProSE/winbond_dump.bin` | 16,777,216 | `ef8ee75f9cd5a59c633b6b79ea0130c9605d3a86bfe2b9bbfc1498e51587fbf8` |
| Akaso V50 Pro SE `SPHOST.BRN` | 15,740,152 | `1f9db54f710d26af1be9f96a317f838fa52e0ca90225f602c45b287e93f2a2f9` |
| `drivers/Reflash_V2.6.zip` | 8,790,842 | `88fdb53627ff7c347f2a060e50a4de04b43b78516e83144c072de0d926a64969` |
| `fw/RunCam5_Firmware_V1.2.2.zip` | 132 | Git LFS pointer, not an available ZIP payload |
| `reversing/offset3.gzf` | 133 | Git LFS pointer, not an available Ghidra database |

Raw byte searches of the first two files find no little-endian words encoding
`0xb24`, `0xb34` or `0x4e34`. This does **not** prove those implementations or
related behavior are absent: values can be computed, transformed or supplied
by another stage. It only fails to provide a matching literal-based lead.

The flash dump begins with `NAND RSV`, with another such header at file
offset `0x4000`. It contains a `SPCA6500`/`DRAM CODE` image header at
`0x588040`/`0x58804e`. Neither the whole flash file nor a raw file offset
`0xb34` should be mapped blindly as the CPU's low-address ROM.

The update package contains a separate DRAM-stage marker at `0x61216e` and
the strings `cpuinv`/`cpuwb` at `0x7f37c`/`0x7f383`. The dump lacks those exact
command strings. This is comparison data, not a match to the X-half low helper.
Both files contain the descriptor value `0x0001d406` at other offsets; a
shared descriptor alone does not establish identical mapping or ROM contents.

ZIP member inventory identifies `Reflash_V2.6/Download/spca6350isp.bin`
(626,048 bytes). Its name differs from SPCA6500. The bundle's generic host
software and driver support must not be read as payload compatibility for
every supported device. No ISP binary was run or uploaded.

The missing LFS payloads were **not searched** and were not fetched in this
pass. Their pointer metadata, payload OIDs and declared sizes are preserved
in the audit output. This is a selected retained-file review, not an exhaustive
search of every firmware archive, upstream branch, LFS object or public dump.

Reproduce using the read-only script:

```sh
python3 analysis/audit_public_boot_candidates.py /private/tmp/icatch-v50-dc437ced \
  > /private/tmp/public-boot-candidate-audit.json
```

Committed output: [public-boot-candidate-audit.json](output/public-boot-candidate-audit.json).
The corpus is not copied into git. The script hashes files, searches raw bytes
and inventories selected ZIP names; it does not decompress or execute members.

## Other primary research and manufacturer guidance

A [firsthand Lenovo ThinkSmart V37M recovery report](https://blog.kitor.eu/lenovo-thinksmart-cam-unbricking-after-a-failed-firmare-upda)
describes a low-address SP-Boot environment, bootloader inspection, and recovery
using a separate USB connection and V37-specific ISP. It supplies useful search
terms and comparison behavior, but no authenticated SPCA6500/X-half ROM image.
Its chip, wiring and recovery access differ from this camera. It is not an
X-half procedure, and the author's reported use of a read/GET operation first
enters ISP; the UI label does not establish a passive read.

The report links [RunCam's official Thumb Pro-W recovery instructions](https://support.runcam.com/hc/en-us/articles/10430391918103-How-to-flash-Thumb-Pro-W-NEW-VERSION-BIGGER-FOV-via-computer-when-it-could-not-be-turned-on).
Those instructions require opening that camera, shorting specific pins and
using the vendor programmer. The page exposes driver/programmer archive links,
but those archives were not downloaded in this pass. This does not satisfy the
user's non-disassembly constraint or establish compatible X-half pin locations,
USB entry or payload safety.

The linked [spca-fun project](https://github.com/mheistermann/spca-fun) explicitly
targets the SPCA6350/EKEN H9. The reviewed root/doc/firmware-analysis listings
provide format tooling and research leads, not a newly located X-half ROM dump.
Its different target prevents treating it as an authenticated implementation
of the X-half low-address API. No code from it was executed or incorporated.

## Consequence and next evidence needed

This review adds provenance and prevents three mistaken shortcuts: treating an
external flash image as CPU ROM, treating an LFS placeholder as inspected
binary content, or treating a generic vendor-tool bundle as an X-half recovery
kit. It does not change the no-flashing recommendation.

Useful new evidence would be a documented exact-platform boot artifact,
an authenticated SPCA6500 ROM dump with mapping/revision provenance, or a
qualified read-only acquisition path on the X half. None was obtained here.
Further offline work can instead return to auditing temporary retail USB entry
and the conditions required for a bounded read; unrelated recovery procedures
do not fill that gap. No fresh REA or live-camera evidence is claimed.
