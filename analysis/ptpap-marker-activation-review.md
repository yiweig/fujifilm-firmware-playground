# EnablePTPAP marker: activation, cleanup and experiment boundary

2026-11-03. **Offline review only.** No camera commands, SD-card changes, factory
loads/writes, firmware patches or activation were performed in this pass. The
user confirmed installed firmware 2.00 during the preceding live probe.

## Finding

The inspected marker-controlled branch enables a factory command interface in
ordinary USB/PTP mode. It does not itself enter calibration or call a persistent
factory-data writer. Its direct additional work is logging and seven operation
registrations. Original ARM controls reproduce that branch and confirm that the
factory buffer remains unchanged under the stated models.

**Removing the marker is not an immediate undo.** Rerunning the marker check with
the file absent clears the flag but preserves existing command registrations.
The selected CloseSession handler also preserves them. A fresh application boot
has a concrete intended table-clearing path, but physical restart/revocation
has not been observed on the camera.

This narrows the activation risk; it does not qualify the current macOS helper
for factory-enabled use. That helper opens a cataloging session through Apple's
framework, whose additional commands have not been measured. Enabling the marker
exposes write-capable operations to that traffic and other camera clients.
Whether any such operation would actually be sent is unknown, not an observed
fault. A restricted explicit-command encoder does not constrain other clients.

## Source and scope

Official 2.00 main, ARM little endian, base `40000000`, SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Reused the local Ghidra 12.1.4 project and original ARM disassembly following the
documented REA raw-image mapping limitation; no new REA decompilation/runtime
result is claimed. The cached ELF text at file offset `100` was compared equal
to the complete raw image before instruction export.

Evidence:

- [Original instruction excerpts](output/ptpap-marker-instructions.txt).
- [Focused pseudocode](output/ptpap-marker-pseudocode.txt). FUN names are generated
  analysis labels; named diagnostics are distinguished in earlier reports.
- [Static facts and boot-fill coverage](output/ptpap-marker-static-facts.json).
- [Original ARM control output](output/ptpap-marker-lifecycle-controls.txt).
- [Bounded table-writer search](output/ptpap-table-writer-search.json).

## Exact marker semantics

`405219cc` passes the embedded path for `D:` and `EnablePTPAP.txt` to file-size
helper `4015c52c`, returning whether its result is nonzero. The embedded path has
a doubled backslash separator; exact bytes/address are retained in the facts JSON.
The file name is a root marker on the firmware's logical drive, not an executable
or command script. Its mapping to the user's physical card remains a prerequisite
for a physical experiment; a drive letter alone is not a physical-media proof.

The helper opens with argument `10`, queries size on a nonzero handle, closes,
and returns the size. No content-read, execute or delete call appears in this
helper. Failed open returns zero; empty-file size zero returns false; modeled
sizes 1 and 4096 return true. Modeled `ffffffff` also returns true: the wrapper
has no separate negative/error-size discrimination. This does not establish that
the real filesystem returns that sentinel on failure.

Only one exact ASCII basename occurrence was found in the main image, at
`40bc87f1`, with full path starting `40bc87ee`; its literal is at `405219e4`.
The existing exact-entry call/reference census identifies `405c9030` as this
helper's caller. These are bounded searches, not exclusion of computed references
or other firmware stages.

## What changes on activation

Ordinary USB UI entry `405c8f64` reaches the marker check after its mount/resource
checks. At `405c9030..405c9040` it writes the result to byte `40e139d8`, then calls
`Ptp_Init` at `405ca428`. The factory buffer and DisableTest are not changed by
this modeled marker/registration slice.

`Ptp_Init` first requests the same transfer-buffer configuration in both cases.
When the marker flag is nonzero, it calls selector `100` seven times, registering
`1021..1027` with callback `405ca67c`. Then both branches converge on the same
property callbacks, aliases, format/capability settings and name filter.
The comparison checks the used arguments to those common calls. Their complete
implementations are not executed in the differential control.

Beyond the tested slice, ordinary USB entry continues through `405cbc18`
(storage/mount-selection work), `4015c318(1,8)` (runtime mask and helper call),
`4015bf04`, connected-state setup and USB API selection `4038d6d0(3)`, then
UI-state initialization to `8000`. Those top-level calls occur independently
of the marker result in the inspected entry body. Their filesystem, hardware
and concurrent effects are not certified write-free. The known persistent
calibration-entry save is a different path; absence from this slice does not
prove every ordinary USB callee harmless.

| Exposed operation | Relevant implementation, not a generic PTP name |
| --- | --- |
| `1021` | Language field read/write branches |
| `1022` | User-ID field read/write branches |
| `1023` | Serial/barcode-related read, exact schema not fully qualified |
| `1024` | Serial-number setting write path |
| `1025(0)` | Request factory file load into RAM; ignores file-read failure |
| `1025(1)` | Persistent whole-record write plus retained/settings work |
| `1026` | Selected parameter zero returns three zeros, not read freshness |
| `1027` | Cached chunks; parameter high halfword selects read versus RAM write |

The earlier [command-path review](ptp-and-service-command-paths.md),
[read assessment](ptp-cold-data-read-assessment.md) and
[failure controls](factory-read-failure-controls.md) retain the detailed scope.
Names assigned to these numeric codes by another manufacturer's library or a
newer standard do not change this callback's implementation. The retained
libgphoto header has conventional names for some of the same codes; this is an
additional reason not to use a generic camera explorer against the enabled body.

The file-backed initial value at `40e139d8` is **1**, not zero. That is an image
initializer, not a live observation or evidence that the interface is always
active. The normal entry overwrites it with the marker result. Another inspected
`Ptp_Init` call at `404d6a9c` reuses the cached flag rather than checking the file
again; it follows mount/storage setup and USB disconnect/reconnect work. Do not
assume every reinitialization notices marker removal.

## Cleanup and restart

The registration table is `[4136b684,4136bcc4)`, 100 entries of 16 bytes. Actual
registration instructions search for a free handler/context slot; they do not
search for a duplicate opcode. The initializer ignores the returned status.
Two enabled initializations append two sets of seven entries in the control.
Repeated reconnects/reinitializations are therefore a poor trial-and-error method;
which physical events repeat this initializer is not fully qualified.

The marker-false branch skips registration and does not call the removal helper.
In the control, enabled initialization followed by a failed marker open changes
only the marker flag to zero, leaving the seven entries intact. The direct
CloseSession control clears its session/response-length words, preserving both
the table and the 1 KB factory buffer. It uses a null session allocation; allocated
cleanup and full application notifications are separate, previously reviewed
boundaries. The prior ordinary live sessions closed successfully, without marker
activation; that does not attest factory-enabled cleanup.

`LeaveUIDpsGroup` has UI/buffer/storage cleanup but no direct factory removal
call. Its full USB teardown, remounting and return-to-camera paths have not been
executed here. See [PTP UI exit](ptp-ui-state-and-exit.md). Removing a file,
closing a session, unplugging USB and restarting are separate events.

The [staged boot initialization](staged-boot-ram-initialization.md) requests a
stage-28 zero fill of `[40fd1140,413ab700)`, containing the **entire** registration
table. This is a concrete intended reset mechanism, replacing the earlier
unspecified table-reset gap. Hardware fill success, every boot/wake route and
actual post-restart table contents remain unobserved. A later unsupported probe
would test that request's availability in the new session, not independently
prove that every table entry was erased or establish recovery from broken firmware.

The bounded writer scan found no resolved table-overlapping direct store/bulk
helper calls; it even misses known indexed registration writes, so its negative
result is not a complete writer inventory. The one nearby variable-length copy
at `402e5a80` writes to `4136b640`; its valid-path remainder check expects 12 bytes,
ending before the table. It is packet-fragment handling, not an established table
clear. No uncontrolled packet was tested to explore its assertion path.

## Controls and what they establish

Eleven checks passed in [ReproducePtpMarkerLifecycle.java](scripts/ReproducePtpMarkerLifecycle.java):
four modeled marker sizes, failed-open behavior, empty-table disabled entry,
enabled-entry exact global delta, shared-configuration equivalence, removal/re-entry
preserving registrations, duplicate append, and selected CloseSession preserving
the table/factory buffer. The script checks the full original image SHA-256,
compares image/globals where specified and stops before unexpected callees.

Modeled calls: marker filesystem open/size/close, logging, common configuration
and callback installation, final close response. Original instructions execute
marker/size helpers, the entry slice, initializer, registration wrapper/table
writer and selected CloseSession body. This is not a whole USB startup simulation,
real filesystem test, concurrent-host test or physical reset/recovery result.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis -readOnly \
  -postScript ReproducePtpMarkerLifecycle.java /private/tmp/ptpap-lifecycle-controls.txt
```

## Proposed experiment, not executed or yet transport-qualified

The next physical experiment should test **activation and deactivation only**,
with no factory-file load or full record capture:

1. Establish control over all PTP traffic **before** connecting the factory-enabled
   camera. The current cataloging ImageCapture helper does not meet this condition.
   Qualify a transport/host arrangement with a restricted complete request sequence
   and no automatic imports, clock sync, capture, uploads or vendor enumeration.
2. With camera off and disconnected, use an external card reader to identify the
   correct card, preserve its contents, and add only root `EnablePTPAP.txt` with
   one ASCII byte `1`. Do not create EnableTest, FRMCheck, updater or calibration
   markers. Do not format the card or alter firmware.
3. Start normally, connect only to the qualified host, submit the same bounded
   `1027(0)` probe and close on its canonical reply. A five-parameter success reply
   is activation evidence; omit the unverified cached bytes. Unsupported means
   activation was not demonstrated: stop, without trying adjacent commands/offsets.
4. After the completed session, power off/disconnect, remove only the marker via
   the reader, and perform a fresh normal boot. Probe once more under the qualified
   transport. Compare against the existing `2005` baseline. A successful chunk
   reply here means deactivation was not demonstrated; stop further experiments.

Unexpected replies, framework errors, timeouts or unusual camera behavior require
stopping and preserving observations, not automatic retries/resets or service-button
experiments. The exact host/transport procedure is an unmet prerequisite, so these
steps are a review draft, not instructions to begin editing the card now.

If activation/deactivation are eventually demonstrated, a separately reviewed
`1025(0)` plus bounded chunk acquisition can follow. Even two matching checksum-valid
records do not prove fresh storage reads, a restorable backup or safe calibration
entry. No marker experiment establishes an independent recovery mechanism.
