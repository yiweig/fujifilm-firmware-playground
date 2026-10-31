# X half Auto ISO 3 maximum investigation

## Outcome

Static analysis strongly supports replacing Auto ISO 3's maximum of ISO 12800
with ISO 6400 using one ARM instruction change. No firmware or update-container
bytes were modified during this investigation.

Official firmware 1.30 uses the same semantic mapping: Auto 1, 2, and 3 pass
range codes `3`, `5`, and `7`, while range code `6` already means ISO 6400.
The behavior therefore predates 2.00 rather than being introduced by it. Exact
1.30 coordinates are recorded in
[the version comparison](firmware-1.30-vs-2.00.md).

## Artifact identity and address mapping

- Original update: `FPUPDATE.DAT`, SHA-256
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`
- Extracted executable region: `firmware-main.bin`, SHA-256
  `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
- REA analysis wrapper: `analysis/output/firmware-main.elf`, SHA-256
  `7e5534e40adc45c14ca19b6d1ebd0ea730ccf1d13125eedcc35d802434133755`
- REA/Hopper mapped `.text` at `0x40000000..0x40E38400`.
- REA read the first 32 mapped bytes as
  `ae0300ea890000ea900000ea9b0000eaa60000eab20000ea200600ea04e04ee2`,
  confirming the expected extraction mapping.

REA evidence: `ev_52344cf8529b73f87f3ef7dced15cdd204034aec6ce5d3f7689b78f0521aad61`,
`ev_45d05e2ca8c602fc210d0ebea483874fce2ddf7cbb2458eb27e8fafab3583962`.

## Preset-to-range mapping

The function at `0x4043F078` maps the selected ISO-mode value to either a
fixed ISO code or an Auto ISO maximum-range code. It subtracts one from the
selector and dispatches through a 21-entry jump table.

For the first three selector values, the observed behavior is:

| Stored selector | Preset | Range code passed to `0x402405DC` |
|---:|---|---:|
| `0` | Auto 1 | `3` |
| `1` | Auto 2 | `5` |
| `2` | Auto 3 | `7` |

The function at `0x402405DC` maps range codes `1..7` in order to maximum ISO
values `200, 400, 800, 1600, 3200, 6400, 12800`. Its relevant branches are:

- `0x40240640`: code `5` -> `MAX ISO 3200`
- `0x40240648`: code `6` -> `MAX ISO 6400`
- `0x40240650`: code `7` -> `MAX ISO 12800`

This matches the camera's observed Auto 2 and Auto 3 behavior and establishes
that ISO 6400 is already a supported range value.

REA evidence: `ev_758a5494b2fa4e34a16ee350e56a994b2c8d940e11df457d40d57bb3f6324ec4`,
`ev_83fcb83d9c466eeff7c666d757d387f17b166fe4a942ad6fdd47492ff8741c13`,
`ev_adcebcd84f1d87e3bfb5f1494fb74e50d065c94ab1abe8e22f928cb97e6347a5`,
`ev_a3e3d5445c835c5ec622e504ad780b1d61151695519d2db04b156afd5231d4a2`.

## Candidate change

At `0x4043F180`, Auto 3 loads range code `7`:

```asm
0x4043F178  mov r0, #5       ; Auto 2 -> ISO 3200
0x4043F17C  b   0x4043F184
0x4043F180  mov r0, #7       ; Auto 3 -> ISO 12800
0x4043F184  bl  0x402405DC
```

The candidate replacement is:

```asm
0x4043F180  mov r0, #6       ; Auto 3 -> ISO 6400
```

The original instruction bytes are `07 00 A0 E3`; the replacement would be
`06 00 A0 E3`. This is a one-byte semantic change (`07` to `06`). The verified
coordinates are:

| Artifact | Offset | Original bytes |
|---|---:|---|
| `firmware-main.bin` | `0x0043F180` | `07 00 A0 E3` |
| analysis ELF wrapper | `0x0043F280` | `07 00 A0 E3` |
| `FPUPDATE.DAT` | `0x09422EA0` | `07 00 A0 E3` |

REA evidence: `ev_cbacdf077e85faa8b6e6dc79afbc56a384528686eae295c7eb415642699a4849`,
`ev_c6c0a66e0a94e98ec3d6ace6788c65dd680c89a18a60984bfe607c13f37ba28d`.

## Call-path corroboration

REA found six direct caller procedures for `0x4043F078`. Several load a byte
from offset `+5` of a settings structure immediately before the call. One path
at `0x4046F3CC` explicitly loads selector `2` before calling the mapper at
`0x4046F3D0`. This supports the conclusion that the function is the central
application path for the stored ISO-mode selector, including Auto 3.

REA evidence: `ev_e726fcc7c5ff479a25a5367c197f0bce25d5e9cc5bf771943b49d95741ae964d`,
`ev_3ed17075887287daf80e4f6f6a94bd74cb3f5bfca03357b9ce30df18f472a34a`.

## Confidence and remaining risks

The logic-level feasibility is high: the firmware already supports ISO 6400,
and the candidate changes only Auto 3's range-code immediate. Auto 1, Auto 2,
fixed ISO selections, stored preset IDs, and Auto 3 icon resources are outside
the changed instruction.

This is static evidence. It does not prove runtime behavior on the camera or
that every indirect call path was recovered by Hopper. More critically, a
direct edit to `FPUPDATE.DAT` may invalidate a container checksum, signature,
or loader integrity check. Packing, integrity repair, RAM-only validation, and
camera installation remain separate investigations. No flashable image has
been produced.
