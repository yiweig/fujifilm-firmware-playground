# Restricted PTP reader and session lifecycle

Date: 2026-11-01. Firmware 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses are ARM runtime addresses at base `0x40000000`.
Static Ghidra/ARM inspection and host-side simulation only. No camera access,
card marker, USB command, configuration change or firmware modification.

## Result

A restricted protocol client now exists in
[`ptp_cold_reader.py`](ptp_cold_reader.py). It constructs only session-management
and qualified factory-data read requests and validates their replies. Its CLI
has only local simulation and offline file inspection. **There is no live USB
backend, device discovery, or camera connection command.** The protocol core
is ready for review; a live acquisition procedure is not yet qualified.

The standard session-close handler frees RAM allocations and clears session
state. USB unplug/return-to-camera behavior is a separate, broader path.
Session close does not revoke the factory interface. Returned data remains
of unknown freshness because the firmware discards its file-read error.

## Standard OpenSession and CloseSession

The normal operation dispatcher `0x402e778c` selects these handlers:

| Request | Dispatch | Callee / observed effects |
| --- | --- | --- |
| `0x1002` OpenSession | `0x402e7a7c..b0`, literal `0x402e7e00` | `0x402e8d60`: rejects session ID zero, changes RAM session state, allocates/zeros a `0x4e20`-byte buffer as needed, sets a response |
| `0x1003` CloseSession | `0x402e7ab4..dc`, literal `0x402e7e08` | `0x402e8e80`: frees populated entries in that buffer, frees it, clears RAM at `0x4136b680` and `0x4136b674`, sets success response |

The close handler reaches free routine `0x4006f454` and response setter
`0x40334f34`. No explicit factory-data, firmware, or RTC write was found in
that handler. This does not classify every application callback as read-only.
The dispatcher additionally submits notifications `0x62510018` and
`0x62510019` through its callback after OpenSession/CloseSession; their complete
application-level effects remain outside this bounded handler audit.

The earlier factory callback `0x405ca67c` also contains cases for standard
session opcodes, but marker-dependent registration installs it for
`0x1021..0x1027`. Those dormant-looking cases are not evidence that standard
OpenSession always executes the factory callback.

## USB disconnect is distinct from session close

Follow-up: [PTP UI state and exit](ptp-ui-state-and-exit.md) tests the main
dispatcher through the disconnect boundary and maps teardown, remounting and
the return-mode decision. Its complete physical effects remain unqualified.

The ordinary USB UI handler `0x404d665c` recognizes event `0xf0000045`, with
shipped log `MSG_USB_OUT`. At `0x404d68bc..e8`, it checks `0x40123bf8` and
returns early if USB still appears plugged in. Its unplug branch then:

- Disconnects USB through `0x4038d764(0)` at `0x404d68f0`.
- Calls `LeaveUIDpsGroup`, `0x405c92c8`, at `0x404d68f4`.
- Clears USB-related RAM state and branches toward `0x404250a4` or
  `0x405768e0` according to prior UI/boot state.

`LeaveUIDpsGroup` frees two RAM buffers, performs conditional synchronization
cleanup, refreshes storage status through `0x405365fc`, changes the current
storage selection through `0x4053573c`, and changes display/capture state.
The inspected cleanup helpers `0x40536fac` and `0x405339e4` free memory and
perform synchronization operations respectively. The return branches lead
into substantial camera UI initialization; this is not a full audit of every
transitive operation. Earlier USB-lifecycle work also found conditional
filesystem remounting in the USB connection wrapper.

Consequently, an offline test of CloseSession does not establish a tested
safe unplug/restart lifecycle for the factory-enabled configuration.

## Registration lifetime

`Ptp_Init` (`0x405ca428`) registers the seven factory operations when RAM byte
`0x40e139d8` is nonzero. Its zero branch skips registration; it does not
explicitly remove previously installed entries.

The entries live in the dynamic RAM table at `0x4136b684`. Registration
`0x402e753c` finds an empty slot, writes the opcode and callback fields, and
does not deduplicate an existing opcode. Removal exists at `0x402e75a0`;
bounded aligned-call scanning found its caller in the general wrapper
`0x405e6868`, not a direct factory-interface shutdown sequence.

The inspected CloseSession handler clears session fields, not this table.
The marker flag is refreshed on entering the USB UI group, not continuously
polled by each factory read request. Thus marker removal, session close, or a
USB reconnect must not be described as proven immediate revocation. A full
startup/reset lifecycle for the table remains to be established. The reader
does not attempt to revoke registrations using guessed commands.

## Client behavior and limits

The permitted outgoing sequence is fixed:

```text
OpenSession: opcode 1002, session ID 1, transaction 0
  twice:
    load record: opcode 1025, parameter 0
    read chunks: opcode 1027, offsets 0,16,...,1008
CloseSession: opcode 1003, no parameters
```

There are 132 requests on the successful path, with transaction IDs 0..131.
No generic operation console, data-upload phase, property setter, clock sync,
capture, device reset, recovery command, or factory write is implemented.
The outgoing encoder rejects every operation/parameter combination outside
the allowlist, including the dangerous `1025(1)` and `1027(0x10000|offset)`.

Responses must be complete PTP response containers with matching length,
type, transaction ID, success code and exact parameter count. Chunk replies
require five parameters with a zero prefix. The two records must match;
uniform all-zero/all-ff records are rejected. This catches some failures but
**does not prove freshness**. The summary always retains the freshness limit
and does not infer safe calibration entry from the decoded flags.

Each client is single-use. Any malformed reply, PTP error, timeout, changed
record, invalid record or failed close aborts without returning a capture.
It sends no automatic retries or extra cleanup commands after an error.
In particular, an ambiguous transfer does not trigger an automatic reset or
CloseSession. Recovery from such a live failure is still a lifecycle question;
the simulator does not establish how the physical camera behaves then.

The `Transport` interface is an explicit integration boundary, not a claim
that an arbitrary injected implementation is safe. A future USB backend must
bound reads/timeouts and preserve the single-transaction framing contract,
without adding implicit commands. It must also handle exclusive interface
ownership: the previously observed macOS owner was `ptpcamerad`. No process
was stopped and no driver was detached in this investigation.

## Validation and local use

```sh
python3 analysis/ptp_cold_reader.py --simulate
python3 -m unittest discover -s analysis -p 'test_ptp_cold_reader.py' -v
python3 analysis/ptp_cold_reader.py --inspect /path/to/existing-record.bin
```

The simulation clearly labels its output synthetic. Inspection requires
exactly 1,024 bytes and cannot verify the file's provenance or firmware version.
No raw factory record or serial data is printed by the summary.

Eight test methods passed, covering exact successful command sequencing,
forbidden writes/offsets, malformed and error replies, transaction mismatch,
timeout without retry, inconsistent/uniform records, failed close, and flag
decoding. These complement the prior original-ARM callback emulation; they
test the host protocol core, not the camera or a USB backend.

## Remaining gates before a live read

Finish the application notification/reset lifecycle, capture the exact USB
endpoint descriptors, qualify a macOS transport and device/firmware identity
check, and review a concrete activation/acquisition/exit procedure. No live
backend should be added by silently treating the simulation as camera proof.
Freshness must remain an explicit uncertainty unless an independent evidence
source establishes a successful file read; repeated equal data is insufficient.

The original project objective is still RAM execution plus a verified recovery
path. A factory-record capture would inform safe service entry; it would not
establish recovery or make firmware flashing safe.

Follow-up: [transport preparation and notification reproduction](usb-transport-qualification.md)
adds original-code checks for both session notifications in state `0x8000`,
a bounded bulk adapter, and a cached-registry inspection tool. Native I/O,
device identity/ownership and the full physical lifecycle remain unqualified.
