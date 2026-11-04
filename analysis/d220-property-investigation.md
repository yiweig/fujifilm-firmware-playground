# Property d220: descriptor and status bookkeeping

2026-11-03. Investigates one advertised property without writing it. This is
separate from proving a loader entry, RAM execution or independent recovery.

## Static evidence

Official 2.00 main SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARM little endian, base `0x40000000`. Existing Ghidra 12.1.4 project plus
original ARM instructions were used, continuing the documented REA raw-image
mapping fallback. No new REA execution evidence is claimed. Before disassembly,
the cached ELF text at file offset `0x100` was compared equal to the entire raw
main image. [Instruction excerpts](output/d220-descriptor-boundaries.txt) and
[pseudocode](output/d220-descriptor-pseudocode.txt) preserve this pass's scope.
Generated FUN names are analysis labels, not recovered source names.

The default dispatcher routes `GetDevicePropDesc` (`1014`) to `40331cb8`.
It also routes `GetDevicePropValue` (`1015`) for `d220` to this descriptor
handler. Dynamic registrations/aliases precede default dispatch; these static
addresses do not prove the effective live handler or installed firmware release.

| Component | Inspected behavior |
| --- | --- |
| `40331b18` | Looks up property, refreshes descriptor, computes size and serializes into an existing bounded output buffer; returns `200a` if absent |
| `40333ff0` | Searches RAM table at `[4138dea0+8]`, count at `+0xc`, stride `0xa4c`; the property ID is compared, not dereferenced |
| `403336d0..40333768` | Initializes `d220`: datatype `0006` UINT32, GetSet `0` read-only, form `1` range, factory/current `0`, minimum `0`, maximum `1`, step `0` |
| `40333c1c` / `40332efc` | UINT32 refresh invokes the registered property callback; if its returned count is zero, leaves the cached descriptor alone |
| `405ca274` | Known retail callback handles `5001`, `500c`, `5011`, `d608`; unrecognized `d220` returns count zero, with no property-specific action |
| `40331614` / `40331890` | Standard descriptor size/serialization: property, type, access, default, current, form, then range; expected `d220` length 26 bytes |
| `403312e4` / `403313b0` | Tracks an object-related transfer length and accumulated progress, resets bookkeeping on completion, and updates cached `d220` current value to one on the selected completion/error path |
| `40331414` | Handler selected for `1015(d212)` reads cached `d220` status to build a small response; not invoked in this investigation |

The read-only access flag does not mean no internal code can change the value.
The range has step zero, so do not infer a useful writable increment from it.
Serializer order is minimum, maximum, step; internal range containers are at
`+424`, `+630`, `+83c` respectively, despite the initializer filling `+83c`
before `+630`.

**Inference:** `d220` looks like SDK transfer/status bookkeeping, not a loader
selector. Its precise application meaning, timing and complete producer/consumer
coverage remain unresolved. Generic libraries assign the same vendor code to
unrelated Nikon properties; that name is not evidence for this camera.

## Fixed live request boundary

The client adds `--read-d220-desc`: exactly one explicit `1014` request with
parameter `0000d220`, no outgoing data. There is no arbitrary property, opcode
or value input. Success requires response `2001`, a 26-byte descriptor with
matching property/type/access/form, successful private dataset save and session
close. Python independently decodes those fixed fields. Unexpected replies stop
without automatic retry/reset. Neither `1015(d212)` nor a setter is sent.

ImageCaptureCore may issue initialization/catalog traffic beyond the explicit
command count. That traffic is not measured here. Static callback review is
bounded and cannot certify every live indirect target or hardware side effect.
The raw transient dataset is removed; only decoded numeric metadata is retained.

Validation: Objective-C compiled with `-Wall -Wextra -Werror`; native offline
self-tests passed including fixed request encoding and descriptor schema/length
rejections. Six Python tests passed, including every truncated descriptor,
trailing bytes, changed property/type/access/form and existing metadata decoders.

```sh
bash analysis/run-imagecapture-device-info.sh --self-test
python3 -m unittest discover -s analysis -p test_ptp_device_info.py -v
```

## Live observation

One invocation succeeded, with exactly one explicit `1014(d220)`, PTP response
`2001`, 26 dataset bytes and confirmed session close; no open client session
remained at exit. [Sanitized result](output/xhalf-d220-desc-2026-11-03.json)
records source/wrapper hashes. Cached IORegistry found one `04cb:030a` at
registry entry `4295356298`, location `00100000`; ImageCapture independently
checked unique model, identity, capability and session state before submitting.

| Field | Live value |
| --- | --- |
| Datatype | UINT32 (`0006`) |
| GetSet | 0, read-only |
| Factory default / current | 0 / 0 |
| Form | 1, range |
| Minimum / maximum / step | 0 / 1 / 0 |

This agrees with the static descriptor shape and initializer. It does not
establish which events change the status, whether reads ever acknowledge it
through other paths, or whether it has a useful role outside transfer handling.
No deliberate transfer/status mutation was tested. Framework transaction ID
143 is not a count of our requests, and catalog count 43 is not an attested
photo count. No subsequent live command was sent in this pass.

## Remaining question

The strongest next offline question is who calls `403312e4` and `403313b0`,
and whether `1015(d212)` consumes/acknowledges anything or only reports it.
That can name this status more accurately and close this lead; it is not yet
a promising route through the temporary-loader entry gate. Do not promote it
to a recovery mechanism merely because it is vendor-specific.
