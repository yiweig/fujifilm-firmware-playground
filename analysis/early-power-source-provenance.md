# Early power-source inputs: sampled registers, overlapping fields and a zero guard

Reviewed 2026-11-03. Offline original ARM inspection and local Ghidra p-code
controls across four main images. No camera access, physical input experiment,
RTC write, USB command, firmware change or new REA result.

## Finding

The early-shell selector's remaining power-source inputs can be traced to
concrete register samples in the application image:

- Bit 24 comes from bit 6 of `0x10401d18`, the address/bit also tested by the
  earlier reset guard. Access width and sampling time differ.
- Bit 15 is an **OR of two sources**: bit 0 of `0x10001304` and bit 5 of
  `0x10000021`. It is not established as a standalone USB-presence signal.
- The constructor writes `0xff` to `0x10000020` and `0x10000021` after reading
  their original bytes. Hardware semantics of these writes are unknown.
- If the entire constructed word is zero, this producer enters control-helper
  calls and then a self-loop, rather than returning through its ordinary path.

This refines the [earlier selector comparison](early-boot-selectors-across-versions.md).
The earlier tests' supplied power word zero was valid for an isolated selector
test, but does not represent a normal return from this preceding constructor.
That is a concrete boundary on those controls, not evidence that all states
with selector bits 24/15 clear are impossible.

## Constructor and sampling dataflow

The bootstrap call at `0x400039a8` identifies the constructor in each version;
the subsequent call at `0x400039ac` invokes the early-shell selector.

| Version | Constructor | Cached word |
| --- | --- | --- |
| 1.01 | `0x4001d6e0` | `0x40d8acac` |
| 1.20 | `0x4001d6e0` | `0x40d9212c` |
| 1.30 | `0x4001d6ec` | `0x40d9a6ac` |
| 2.00 | `0x4001d89c` | `0x40e4aeac` |

All coordinates use main-image base `0x40000000`. Hashes, sampling-helper
addresses and selected-byte digests are retained in the existing
[comparison JSON](output/early-boot-selector-comparison.json). All three
selected sampling-helper bodies are byte-identical across versions, although
their addresses move. Constructor controls execute each original image.

In 2.00, `0x400470c0` reads byte `0x100003a3`; `0x40047160` reads bytes
`0x10000021` and `0x10000020` and writes `0xff` back to both addresses;
`0x400471c4` reads word `0x10401d18` and extracts bit 6. The constructor also
reads byte `0x10001304` directly.

Let `R[a]` mean the sampled byte at `a`, and `D` the sampled word at
`0x10401d18`. The cached word is constructed as:

```text
P = R[0x100003a3]
  | (R[0x10000020] << 5)
  | (R[0x10000021] << 10)
  | (R[0x10001304] << 15)
  | (((D >> 6) & 1) << 24)
```

The packed fields overlap. In particular, byte `0x10000021` bit 5 shifts
into bit 15 and is ORed with byte `0x10001304` bit 0. Reading just the latter
would therefore miss a source of the selector's `B` bit. These dataflow labels
do not assign electrical meanings or physical button identities.

The constructor publishes the word to RAM; its function return register is
not the API for retrieving that entire word. The selector's later masked
getter loads the cached word. Source sampling and selector evaluation are
separate moments; the repeated sample of raw USB presence is separate again.

## Nonzero prerequisite and side-effect boundary

After storing the cached word, the constructor returns normally only when
it is nonzero. In the zero case, 2.00 calls `0x4004712c(0)` and then
`0x400470cc(0)`, followed by an unconditional self-branch. Those helpers
contain control-register stores. This pass does not identify their actual
power/reset effects or execute either helper in the emulator.

The test stops before the first helper. It does not fake a return past that
boundary or describe the self-loop as a USB-recovery wait. A real device could
react to the control writes before reaching the loop; that behavior is unknown.

Nonzero lower status bits can coexist with selector bits `A=B=0`. For example,
a supplied raw status byte `0x100003a3=1` with the other selected samples zero
produces `P=1`. This is a coherent software-path fixture, not an observed or
user-selectable camera condition. It preserves the distinction between
"whole word zero" and "the selector's two tested bits clear."

## Original-instruction validation

The `power-source` mode of [InspectEarlyBootSelectors.java](scripts/InspectEarlyBootSelectors.java)
runs each constructor and all three original sampling helpers. MMIO is backed
by supplied emulator RAM. CPU stores of `0xff` are checked as byte stores;
write-one-to-clear, acknowledgement and other hardware behavior are not modeled.

Per image, 32 producer cases yield 31 ordinary returns and one zero-word stop
at the control-helper boundary. Each nonzero word is then supplied to the
original selector with saved words `0`, `3`, `0x6000` and presence samples 1,
giving 93 continuations per image. The selector's RTC read and cached-power
getter are modeled as in the prior controls. This is a composition of slices,
not full bootstrap or interrupt/controller execution.

All **500 controls passed**. They check constructed cache values, both status
stores, normal-path stack balance and unchanged executable bytes. No actual
register samples, physical timing or method for selecting them was acquired.

Evidence: [controls](output/early-power-source-controls.txt),
[source-verified bounded disassembly](output/early-power-source-boundaries.txt),
[per-version metadata](output/early-boot-selector-comparison.json).

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript InspectEarlyBootSelectors.java /private/tmp/power-source-controls.txt "$PWD" power-source
```

## Stopping point

The source is now traced to sampled registers, rather than an unexplained
earlier-stage function pointer. Their physical meanings, what sets them and
their values during a real X-half boot remain unresolved. The same register
address appearing in reset and later startup does not establish that its
value stays constant between the two reads.

These findings give no safe way to choose the shell inputs, initialize the
missing USB infrastructure or boot independently of the main image. Further
progress on this lead needs register documentation or authentic boot-state
evidence; more synthetic combinations cannot supply either.
