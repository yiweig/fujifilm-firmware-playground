# Official X half firmware artifacts

This note records official download provenance for X-half firmware used in the
project. Firmware binaries are intentionally ignored by Git.

## Version 2.00

- Official URL:
  `https://dl.fujifilm-x.com/support/firmware/x-hf1-200-in9f30gq/FPUPDATE.DAT`
- Directory token: `in9f30gq`
- SHA-256:
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`

## Version 1.30

- Official URL:
  `https://dl.fujifilm-x.com/support/firmware/x-hf1-130-1qusdx6m/FPUPDATE.DAT`
- Directory token: `1qusdx6m`
- Discovery source: a stale official Netherlands X-half firmware page still
  displaying version 1.30 and retaining its direct download link.
- HTTP verification on 2026-10-30: status 200, `application/octet-stream`,
  `Content-Length: 163630698`, Amazon S3/CloudFront.
- Local ignored path: `firmware/1.30/FPUPDATE.DAT`
- Size: `163,630,698` bytes
- SHA-256:
  `72d20d9c1c5a2a18d7e5c1ace7916460cebbfe082b4c46551ffd574ec029040b`
- Static package inventory succeeds and its main image contains
  `FFSD_BRN:KC36D10 v1.0x30.0x00@HW_KC36D10_B0`, independently confirming
  that the served artifact is version 1.30 for the expected X-half project.

## Version 1.20

- Official URL:
  `https://dl.fujifilm-x.com/support/firmware/x-hf1-120-zv07xe22/FPUPDATE.DAT`
- Directory token: `zv07xe22`
- Discovery source: the archived TousLesDrivers entry `v_code=82531`, whose
  download redirect still retains the original `dl.fujifilm-x.com` target.
- HTTP verification on 2026-10-30: status 200, `binary/octet-stream`,
  `Content-Length: 163727242`, Amazon S3/CloudFront; upstream `Last-Modified`
  is 2025-07-18 02:45:33 GMT.
- Local ignored path: `firmware/1.20/FPUPDATE.DAT`
- Size: `163,727,242` bytes
- SHA-256:
  `a130ceae2c8cc4f8ac0734ab5761076d45a5aa0bf4be3406ebb49ac3aefaf2c9`
- Static package inventory succeeds, including the declared zero word-sum.
  Its main image contains
  `FFSD_BRN:KC36D10 v1.0x20.0x01@HW_KC36D10_B0`, independently confirming
  version 1.20 and the expected X-half hardware project.
- The main DRAM image starts at DAT offset `0x08ea3d20`, is `14,153,216`
  bytes, and has SHA-256
  `85d6d280605148d131c28f71b3560742310878340c6de5d1c05faf00d02a2c8f`.
- The embedded Linux build is 3.18.11 `#24`, dated 2025-06-09.

## Version 1.01

- Official URL:
  `https://dl.fujifilm-x.com/support/firmware/x-hf1-101-6pqx12qw/FPUPDATE.DAT`
- Directory token: `6pqx12qw`
- Discovery source: the archived TousLesDrivers entry `v_code=82036`, whose
  download redirect still retains the original `dl.fujifilm-x.com` target.
  No firmware bytes were obtained from the archive or another third party.
- HTTP verification on 2026-10-30: status 200, `binary/octet-stream`,
  `Content-Length: 162254474`, Amazon S3/CloudFront; upstream `Last-Modified`
  is 2025-06-06 08:27:38 GMT.
- Local ignored path: `firmware/1.01/FPUPDATE.DAT`
- Size: `162,254,474` bytes
- SHA-256:
  `a0276f25a4542e785a4fd5319b42fe6808b0209e4bdcde5d119f5b90b2e0777d`
- Static package inventory succeeds, including the declared zero word-sum.
  Its main image contains
  `FFSD_BRN:KC36D10 v1.0x01.0x00@HW_KC36D10_B0`, independently confirming
  version 1.01 and the expected X-half hardware project.
- The main DRAM image starts at DAT offset `0x08d43d20`, is `14,123,520`
  bytes, and has SHA-256
  `64ca08d346075907bbec2dde2db50bc3a5507a93e5d7b2e5024d4a59599125f9`.
- The embedded Linux build is 3.18.11 `#146`, dated 2025-04-02.

## Meaning of the eight-character token

The observed directory pattern is:

```text
x-hf1-<version>-<eight-character token>/FPUPDATE.DAT
```

The four observed tokens use lowercase letters and digits but have no visible
relationship to the version or file SHA-256. They should be treated as opaque
deployment slugs, not computable checksums. Eight base-36 characters would
allow about 2.8 trillion combinations, so blind enumeration is neither useful
nor appropriate.

Historical discovery should instead use old official regional pages, search
indexes, web archives, cached link metadata, or preserved download manifests.
The 1.30 URL was recovered from official page metadata; the 1.20 URL was
recovered from third-party archives' preserved redirects to the official
Fujifilm host. No token was guessed.

## Remaining versions

Fujifilm's official version history lists at least 1.00, 1.01, 1.20, 1.30 and
2.00. Version 1.00 shipped with the camera; its direct artifact has not been
located. Its URL version component is likely `100`, but its opaque token
remains unknown.

Do not install an older artifact on the camera. These copies are retained only
for static comparison.
