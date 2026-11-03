# Public entry/provider evidence refresh

Reviewed 2026-11-03. Read-only web research, public GitHub metadata and pinned
text-source inspection following the [low-helper backing boundary](low-helper-backing-boundary.md).
No camera access, firmware download, foreign code execution, driver installation,
private-community access or outbound message. No new REA/binary analysis.

## Result

This bounded search did not supply an authenticated X-HF1 low-memory provider,
service manual with a verified temporary entry, or independent cold recovery.
It does not prove such material is absent from the internet or private research.

The useful additions are a current pinned FujiMod catalog check, a concrete
architecture mismatch in an older softmod lead, and a separate researcher lead
that must not be mistaken for a demonstrated RAM/recovery result. The next
highest-value input remains exact-body entry/provider evidence.

## Current pinned repositories

| Repository | Checked HEAD | Date / disposition |
| --- | --- | --- |
| [FujiMod](https://github.com/justjake/fujimod/tree/122b82ea54ef4980035e484ed7dfee39c4d2cc18) | `122b82ea54ef4980035e484ed7dfee39c4d2cc18` | HEAD metadata dated 2026-11-03; inspect current catalog/recovery material |
| [V50 Playground](https://github.com/Linouth/iCatch-V50-Playground/tree/dc437ced8fdc9564e39ca550af6a1d53b1037714) | `dc437ced8fdc9564e39ca550af6a1d53b1037714` | Same HEAD as prior retained-corpus audit; those selected binaries were not re-searched |
| [spca-fun](https://github.com/mheistermann/spca-fun/tree/c1f2727499ad661d9b0d8879f37fe5e4b1561b4d) | `c1f2727499ad661d9b0d8879f37fe5e4b1561b4d` | HEAD dated 2016-05-30; inspect previously unreviewed softmod text |

The FujiMod recursive tree contains 3,196 entries and reports `truncated=false`.
The only `xhalf`-named file path is `catalog/bodies/xhalf/controls.json`.
There is no `catalog/bodies/xhalf/body.json`, no path under
`catalog/firmware/xhalf/`, and no `spca6500`-named path in that tree. The
identified SoC definition paths are for EXR II, X-Processor Pro, X-Processor 4
and X-Processor 5.

The release registry still lists `xhalf-200`, public 2.00, internal 2.0006,
and official update SHA-256
`3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`.
Release recognition is separate from a qualified hardware/firmware access profile.
The pinned recovery README describes other Fujifilm processor families and
supplies no SPCA6500 recipe in the reviewed text.

These are public path/selected-source findings, not exhaustive analysis of all
FujiMod source contents or proof about private/unpublished work. The earlier
[onboarding assessment](fujimod-new-camera-onboarding.md) remains historical at
its own pinned commit; this check does not silently redate that assessment.

Primary sources: [release registry](https://github.com/justjake/fujimod/blob/122b82ea54ef4980035e484ed7dfee39c4d2cc18/catalog/firmware-versions.generated.json),
[recovery README](https://github.com/justjake/fujimod/blob/122b82ea54ef4980035e484ed7dfee39c4d2cc18/crates/recovery/README.md),
[complete tree metadata](https://api.github.com/repos/justjake/fujimod/git/trees/122b82ea54ef4980035e484ed7dfee39c4d2cc18?recursive=1).

## Why the spca-fun softmod is not an X-half loader

The pinned `softmod/eken.ld` explicitly declares `OUTPUT_ARCH(mips)` and a
little-endian MIPS ELF format. The accompanying assembly uses MIPS registers
and instructions. Its usage notes assume access to existing debug commands
and firmware-specific addresses; they do not establish a retail entry mechanism
for the X half. The adjacent exploit README targets EKEN H9 firmware
`H9.05.10.2015` and gives that artifact's hash.

The X-half main image is ARMv7. Reusing this loader or its addresses would
therefore be an unsupported cross-architecture substitution. The source can
illustrate the general idea of a temporary application, but it is neither
compatible payload code nor authenticated implementation of low helper `0xb34`.
Nothing was compiled, executed or sent to the camera.

Primary sources: [linker script](https://github.com/mheistermann/spca-fun/blob/c1f2727499ad661d9b0d8879f37fe5e4b1561b4d/softmod/eken.ld),
[loader assembly](https://github.com/mheistermann/spca-fun/blob/c1f2727499ad661d9b0d8879f37fe5e4b1561b4d/softmod/apploader.s),
[usage notes](https://github.com/mheistermann/spca-fun/blob/c1f2727499ad661d9b0d8879f37fe5e4b1561b4d/softmod/HOW_TO_RUN.txt),
[target declaration](https://github.com/mheistermann/spca-fun/blob/c1f2727499ad661d9b0d8879f37fe5e4b1561b4d/eken-ftp-exploit/README.md).

## Separate X-half researcher lead

An inspected first-person comment by `StrangeComfortable98` in an X-T3 mod
thread claims to have found bytes enabling X-half RAW output and asks for help
repacking firmware. The inspected comment supplies no corresponding patch,
authenticated boot/provider dump or demonstrated recovery procedure. It is a
possible collaborator lead, not corroboration of a safe launch or completed RAW
implementation. No message was sent and no firmware was downloaded.

Source: [author's comment in the thread](https://www.reddit.com/r/fujifilm/comments/1wda2x6/xt3_running_classic_negative_eterna_bleach_bypass/).
This report paraphrases the claim and does not adopt it as a technical result.

## Search boundary and provenance

Keyword searches covered SPCA6500 boot-ROM/bootloader/ROM-dump terms, X-HF1
service-manual terms, FujiMod X-half recovery and X-half firmware research.
Many results were unrelated service manuals or ordinary owner/update guides;
none was promoted to exact-body recovery evidence merely because of a title.
Only selected primary research sources and repository material support the
technical dispositions above. No service-document listing was authenticated as
an X-half repair manual in this pass.

Seven small text/JSON files were fetched from the pinned commits into temporary
storage. Each file's length and Git blob SHA-1 were checked against its tree
entry; SHA-256 values are retained too. No fetched source was executed. The
curated evidence records metadata/our conclusions, not copies of third-party
source trees. Public web/raw fetching had transport/cache limitations, so system
curl with TLS verification was used for the metadata/text checks. That is not
a firmware acquisition or execution tool.

Evidence: [repository heads, selected verified files and dispositions](output/public-entry-evidence-refresh.json).
The prior [V50 audit](public-boot-artifact-review.md) retains its original scope:
two missing LFS payloads remain unacquired/uninspected. An unchanged upstream
HEAD does not mean every historical binary has been analyzed.

## Recommended next evidence request

The following is a draft for the user to share with the FujiMod veteran or
another researcher. It has **not** been posted or sent:

> We're studying the Fujifilm X half / X-HF1, whose official 2.00 main image
> identifies SPCA6500. We've located the application's USB RAM receiver, but
> don't have a verified temporary retail entry or independent cold recovery.
> Do you have an exact-body loader-entry reference, earlier-stage/low-memory
> artifact, or compatible service documentation? The copy/execute path calls
> low address `0x00000b34`; its inspected mappings do not point into our known
> SRAM copy. Provenance and the boot/address context would help us validate any
> artifact. We're keeping this non-invasive and haven't uploaded or flashed code.

The [backing report](low-helper-backing-boundary.md) specifies the fuller
artifact requirements. A reply identifying a documented body-specific route,
provider bytes or a new compatible artifact would give the next analysis a
concrete target. A general statement that RAM loading should be possible does
not supply those missing prerequisites.

R1/R2/C1 in the [roadmap](ram-launch-and-recovery-roadmap.md) remain unresolved.
Further blind command/button tests or repeated mapping controls would not close
this evidence gap. Camera reconnection is not needed for the result above.
