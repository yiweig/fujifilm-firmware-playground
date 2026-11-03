# Updater USB identity and descriptor path

Date: 2026-11-03. Offline inspection of official 2.00 complete updater,
base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
Addresses here are updater addresses, not main-application coordinates.
No camera access, enumeration, vendor requests or firmware modification.

## Finding

The updater's target-0 descriptor path leads to a stored device descriptor
with **VID:PID `2aad:6500`**, plus vendor interface 5 with bulk IN `0x82`,
bulk OUT `0x03` and interrupt IN `0x84`. This matches the vendor-loader lead
previously found in the main application. It is not a new observed X-half
USB mode; the prior measured normal-camera identity was `04cb:030a`.

The descriptor is connected to the updater's standard USB request handler.
Its fields are mutable: packet sizes, USB-version metadata and device-version
metadata can change through runtime getters. Therefore the stored bytes are
a recognition clue, not a complete or live transport configuration.

## Original stored descriptor

Device descriptor at `0x4009da30`:

```text
12 01 00 02 00 00 00 40 ad 2a 00 65 00 01 01 02 00 01
```

Its stored fields include USB version `0x0200`, EP0 packet size 64, VID
`0x2aad`, PID `0x6500`, device version `0x0100`, manufacturer/product string
indices 1/2, serial string index 0 and one configuration. These version words
must not be interpreted as the installed Fujifilm release number.

The adjacent configuration begins at `0x4009da42`, declares length 167 bytes
and three interface numbers (0, 2 and 5), with alternate settings for interface
2. Decode only that declared span; adjacent audio-related bytes are outside
this configuration's stored length.

| Vendor interface 5, alternate 0 | Descriptor address | Stored max-packet word |
| --- | --- | --- |
| Bulk IN `0x82` | `0x4009dad4` | 64 |
| Bulk OUT `0x03` | `0x4009dadb` | 64 |
| Interrupt IN `0x84` | `0x4009dae2` | 8 |

The interface's class byte is `0xff`. This table reports template data;
alternate settings and endpoint parameters must be obtained from actual
descriptors if a qualified live connection becomes available. A vendor ID and
product ID can be reused by other iCatch devices and cannot alone identify a
particular physical camera among several connected USB devices.

## Connection to the descriptor request path

The [startup registration report](updater-usb-parameter-producer.md) identifies
target 0 and the two task entries. The standard-request side uses
`0x4001a620`. Its GET_DESCRIPTOR request branch at `0x4001a64c..0x4001a6cc`
extracts descriptor type/index from the setup bytes and calls getter
`0x40010aa8`, through literal `0x4001a744`. It then bounds the response length
by the host's requested length and forwards the response to transfer helper
`0x4001a22c`.

Getter `0x40010aa8` obtains its base through `0x40010758`. That helper checks
selected target byte `0x400a2ea8`: zero returns descriptor pointer
`0x4009da30`; nonzero takes a diagnostic/null branch. The type-1 branch
returns that pointer and its descriptor length. Type 2 follows the configuration
lookup through `0x400107a4`. This is an actual software response path, not
just a search hit for `12 01` or vendor ID bytes.

For configuration requests, the getter calls `0x40010958` with packet-size
values obtained through runtime helpers. An other-speed configuration request
(type 7) can swap 64 and 512 before that update. Inside `0x40010958`, bulk
endpoint direction chooses which supplied size is written into its two packet
size bytes. This routine also writes the device descriptor's USB-version word
and tail-calls `0x400108a0`, which copies another runtime word's low half into
the device-version field. The full runtime sources and speed/controller state
are not measured here; do not freeze the template values into a client.

## Service setup is not observed enumeration

Following task/queue initialization, `0x4000ffa0` performs controller setup,
prepares a shared event object with event code 0 and sends it through
`0x40010f60`. It also queues code 3 through `0x4000fe84` and initializes
software state. The preceding `0x4000ff24` creates the queue used there.

This adds concrete deferred work to the earlier task-registration trace.
It does not prove that the controller has physically attached, that a host
has accepted any descriptors, or that the tasks processed those events on
the camera. We have not run the complete event/controller startup. The
USB download handler and the standard-request task remain software evidence
inside an **already executing updater**, not an independent cold USB loader.

## Reproduction and practical limit

[inspect_updater_usb_descriptors.py](inspect_updater_usb_descriptors.py)
hash-checks the complete updater, parses only the declared configuration span
and verifies descriptor bounds and interface/endpoint counts. It prints JSON
and has no USB access or output-file mutation logic:

```sh
python3 analysis/inspect_updater_usb_descriptors.py
```

Retained [template JSON](output/updater-usb-descriptors.json) and
[original-byte code ranges](output/updater-usb-identity.txt) reproduce this
finding. The parser ran successfully; no new emulator or live controls were
needed. This is original-file inspection, not new REA evidence.

If an independently justified entry eventually produces a candidate USB
connection, this identity and interface layout can help recognize it. They
do not justify sending vendor commands to arbitrary matching devices. Normal
camera reconnection alone would not resolve the missing earlier-stage entry,
and no reconnection is required for the current offline findings.
