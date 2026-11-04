# Factory cached-chunk read: normal USB availability probe

2026-11-03. The user confirmed installed firmware **2.00**, authorized continued
small reviewed reads, and subsequently confirmed clicking Allow after a host
permission timeout. Firmware identity is user-reported, not attested by USB.
This pass tests availability of one existing factory read handler. It does not
enable service mode, create an SD marker, load a file, or acquire a factory record.


Follow-up 2026-11-03: the [marker activation review](ptpap-marker-activation-review.md)
adds eleven original-ARM checks. Marker detection adds seven registrations without
changing the factory buffer in the modeled entry slice; removal/re-entry and
CloseSession preserve existing registrations. A stage-28 startup zero-fill covers
the entire table, providing an intended reset mechanism, not observed physical
revocation. The current ImageCapture client can generate unmeasured framework traffic
and is not yet qualified for factory-enabled operation. No marker was created;
the report includes a draft activation/deactivation test and its transport gap.

## Request selection and static boundary

The [factory-read assessment](ptp-cold-data-read-assessment.md) identifies a
potential path to factory flags. Before attempting its load-plus-chunk sequence,
this experiment selects only **`1027(00000000)`**, the first cached chunk.
The usual marker is `D:\EnablePTPAP.txt`, checked before conditional registration
of `1021..1027`. Absence from DeviceInfo's advertised operations alone does not
prove an operation is unavailable; this probe tests the current live session.
No marker was created, removed or inspected on the card by this experiment.

Official 2.00 main SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARM little endian at `40000000`. The known callback is `405ca67c`.
At `405cac90..405cacc4`, high parameter bits zero select a copy **from** cached
factory RAM `40e4df02 + low16(parameter)` **to** a stack response. Parameter
zero fixes the offset to zero and takes the read branch. The adjacent high-word
one branch writes the RAM buffer; it is not constructible by the new fixed mode.
`1025(0)` would request a file load, and `1025(1)` persists data: neither is sent.
Common callback code updates request bookkeeping in RAM and logs; this is not
a claim of no internal activity or a complete audit of indirect live effects.

The default dispatcher returns `2005` for an unregistered, unaliased `1027`.
Dynamic registrations and aliases precede default dispatch; a reply cannot
attest the internal function pointer. Operation-arrival notifications are a
separate previously reviewed UI path; see the
[retail command review](retail-ptp-command-boundaries.md).

This pass reuses the existing correctly mapped Ghidra project and ARM disassembly
because of the documented REA raw-image mapping limitation. REA session inspection
reported no open target; no new REA target execution is claimed. An attempted
exact body match against 1.01/1.20/1.30 found no exact match for the selected
2.00 callback/chunk instruction bytes. This does not prove semantic differences;
no cross-version equivalence is claimed. Live selection waited for the user's
explicit 2.00 confirmation.

## Offline control and client boundary

Added an original-instruction control to `ReproducePtpColdRead.java`: initialize
a synthetic cached record, call `1027(0)` **without a preceding load**, verify
the five response parameters reconstruct exactly its first 16 bytes, verify
no file-read call occurred, and compare the full 1,024-byte cached buffer
unchanged. It passed, together with the existing load/reconstruction and failure
controls. [Retained output](output/factory-chunk-probe-controls.txt). This executes
the original callback under modeled logging/copy/response routines; no camera,
real filesystem, USB controller or persistent writer executes in emulation.

The existing ImageCapture helper adds `--probe-factory-chunk`. Each invocation
constructs exactly one fixed 16-byte command container: type 1, opcode `1027`,
framework transaction placeholder zero, sole parameter zero, no outgoing data.
No arbitrary offset/opcode/mode input, read loop or automatic retry is exposed.
The existing `ptp_cold_reader.py` remains an offline-only acquisition model.

Expected complete replies, with no separate data phase:

| Reply | Required framing | Meaning |
| --- | --- | --- |
| `2001` | Exactly 32 bytes, response type 3, five parameters, first parameter zero | Matches cached-chunk reply shape; 16 returned bytes are omitted and not interpreted |
| `2005` | Exactly 12 bytes, response type 3, no parameters | Current request rejected as operation unsupported |

Both are completed probe outcomes followed by a normal session-close request.
This is a deliberate exception to the former stop-on-every-error-response rule:
a canonical, unambiguous `2005` is an expected probe result. Framework NSError,
malformed replies, unexpected codes/data phases and timeouts stop without further
explicit commands, retries or resets. `status: ok` means the probe and close
completed, not that a factory read was supported; inspect `factory_probe_outcome`.

Any returned factory words are omitted immediately: no raw factory bytes,
factory flags, serial or record hash are printed/saved by this mode. Even a
matching success reply would not establish fresh, checksum-valid factory data.
The existing helper checks one `04cb:030a`, X-HF1 model, PTP capability and session
state before sending. ImageCaptureCore may perform additional initialization and
catalog traffic; our explicit request count is not a wire transaction count.

Native tests passed for exact fixed request bytes, both expected replies,
every truncated/extra-length reply and malformed length/type/code/prefix or
unexpected data phase. The helper compiled with warnings as errors; shell syntax
passed. Eleven offline factory-reader tests and six metadata-decoder tests passed.
These tests validate the selected host boundaries, not physical camera safety.

## Host permission timeout

[First attempt](output/xhalf-factory-chunk-probe-2026-11-03.json) reached the
60-second discovery boundary with **zero discovery callbacks, zero explicit
requests and no opened session**. The user reported returning late to the access
popup and then explicitly confirmed clicking Allow. The first attempt is not a
camera opcode test or camera rejection. A single unchanged-client retry was
then launched. Both source and wrapper hashes are recorded for comparison.

Cached IORegistry before the retry found exactly one `04cb:030a` at entry
`4295356298`, location `00100000`, owned by `ptpcamerad`. Registry reads do not
open the camera or send PTP requests. The client independently checks identity.

## Live result after Allow

The [unchanged-client retry](output/xhalf-factory-chunk-probe-2026-11-03-retry-01.json)
received one camera discovery callback, opened a session, completed catalog
initialization and submitted **one explicit `1027(0)`**. The reply was exactly
12 bytes, response type 3, code **`2005` OperationNotSupported**, no parameters
and no data phase. The session-close callback completed successfully; the
client had no open session at exit. Source/wrapper hashes match the first attempt.

This is a completed **negative availability result**, unlike the first attempt's
host discovery failure. It is consistent with conditional factory registration
being absent in the present normal USB session, but does not read the registration
flag or prove the reason. It does not prove that the camera lacks this handler,
that every factory opcode is unavailable, or that marker activation is safe.
No factory bytes were returned and no subsequent live request was sent.
Transaction ID 145 does not count explicit requests; catalog count 43 does not
attest photo count. Our scripts requested no SD-marker, settings, firmware or factory-data write,
and did not change driver ownership or host permission configuration. The user
allowed macOS access. Framework/firmware side effects are not fully measured.

**Next qualification target:** the exact `EnablePTPAP.txt` activation path and
its complete entry/exit effects. Activation is a separate experiment, exposes
write-capable neighboring commands and can leave registrations installed beyond
CloseSession. Neither this negative result nor the restricted request encoder
qualifies that lifecycle. Do not try adjacent opcodes, guessed offsets or
calibration entry as a substitute for reviewing activation. A future record
capture still needs an explicit freshness limitation and is not a restore path.

## Reproduction

Offline only:

```sh
bash analysis/run-imagecapture-device-info.sh --self-test
python3 -m unittest discover -s analysis -p test_ptp_cold_reader.py -v
python3 -m unittest discover -s analysis -p test_ptp_device_info.py -v
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript ReproducePtpColdRead.java /private/tmp/factory-probe-controls.txt
```

The following is an **active** session, not an offline test. Do not run it on
a different model/firmware or mistake it for an activation/acquisition procedure:

```sh
bash analysis/run-imagecapture-device-info.sh --probe-factory-chunk
```
