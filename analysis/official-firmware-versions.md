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

## Meaning of the eight-character token

The observed directory pattern is:

```text
x-hf1-<version>-<eight-character token>/FPUPDATE.DAT
```

The two observed tokens use lowercase letters and digits but have no visible
relationship to the version or file SHA-256. They should be treated as opaque
deployment slugs, not computable checksums. Eight base-36 characters would
allow about 2.8 trillion combinations, so blind enumeration is neither useful
nor appropriate.

Historical discovery should instead use old official regional pages, search
indexes, web archives, cached link metadata, or preserved download manifests.
The 1.30 URL was recovered from official page metadata this way; it was not
guessed.

## Remaining versions

Fujifilm's official version history lists at least 1.00, 1.01, 1.20, 1.30 and
2.00. Direct artifacts for 1.00, 1.01 and 1.20 have not yet been located. Their
version components are likely `100`, `101` and `120`, but their opaque tokens
remain unknown.

Do not install an older artifact on the camera. These copies are retained only
for static comparison.
