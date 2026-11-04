# Ordinary power-on: live USB registry observation

Observed 2026-11-03 local time (2026-11-04 UTC). This is a live **host registry
notification recording**, not a USB packet capture, internal boot log or
register dump. The camera was connected and powered on normally by the user.

## Result

The observer recorded exactly one post-baseline USB-device arrival:
**Fujifilm `04cb:030a`**, matching the previously identified normal camera mode.
No intermediate device identity, removal or rearrival was observed during
this recording. A subsequent cached-registry read corroborated the same node
and its normal still-image/PTP interface.

The user reported a USB label/icon on the camera and no other unusual behavior.
That is consistent with the normal USB interface observed on the Mac. It does
not establish any internal boot stage or service-mode transition.

This adds actual external startup evidence, but does not expose a RAM-loader
or independent recovery entry. Absence from these events does not prove that
such an entry is absent from the camera.

## Sequence and attribution

Before recording, the camera was to remain unplugged and off. A cached
registry check found no normal `04cb:030a` device. The observer registered for
first-match and termination notifications, drained its initial device baseline,
and emitted `ready`. Its five baseline devices contained no Fujifilm/iCatch
candidate vendor IDs (`04cb`, `2aad`, `04fc`).

Only after verifying readiness, the user was instructed to:

1. Plug in the camera while off.
2. Wait ten seconds, then turn it on normally without holding other buttons.
3. Leave it connected/on for thirty seconds and report completion.

The user reported completion with the screen behavior above. Other USB devices
were to remain unchanged. Attribution uses that baseline change, known normal
identity and user action together; the VID/PID alone is not an exclusive
physical-device identifier.

| Recorded UTC time | Observation |
| --- | --- |
| `2026-11-04T02:51:15.746Z` | Observer ready; five baseline devices |
| `2026-11-04T02:55:59.965Z` | One arrival: `04cb:030a`, registry ID `4295354226`, location `0x01100000` |
| `2026-11-04T02:58:16.473+00:00` | Host requested observer stop after completion/reporting |

The arrival's `elapsed_s=284.220735` is time since the observer started,
including the user's preparation time. **It is not camera boot duration.**
There are no independent timestamps marking cable insertion or the power
switch action, so this capture cannot precisely separate off/charging and
powered-on intervals. No internal startup ordering is inferred from the gap.

The corroborating cached registry reported interface 0, class 6, subclass 1,
protocol 1, three endpoints, and `ptpcamerad` as interface user-client owner.
It was the same registry ID/location as the arrival. No interface was opened
by our tools. Descriptor version fields were not interpreted as the installed
Fujifilm firmware release.

## Collector and access boundary

[usb_startup_observer.c](usb_startup_observer.c) uses IOKit matching notifications
for `IOUSBHostDevice`. It records arrival/removal time, numeric VID/PID, registry
ID, location and USB address. Serial strings and device names are not emitted.
Initial unrelated-device numeric records stay in the ignored local capture;
the committed summary excludes their identities.

The collector does not create USB user clients, open devices/interfaces, claim
endpoints, issue device requests, change configuration, send PTP/vendor commands,
upload code or access camera storage. Ordinary macOS enumeration/framework
traffic is separate and unmeasured; this is not a claim that the bus was silent.

The sandbox's first registry read failed; the authorized host-context registry
read then succeeded. The observer likewise ran with approved host registry
access. Compilation used `-Wall -Wextra -Werror` successfully. Both notification
subscriptions succeeded, the baseline was drained and readiness verified before
the user was told to connect. Runtime stderr remained empty.

The collector had a 900-second limit. It was stopped earlier using SIGINT to
the single process with the exact owned observer command. The exec session
confirmed exit 130, expected for that explicit stop; the log flushed after
each record. There is no fabricated `ended` event. The observer is no longer
running.

## Evidence and reproduction

Committed [curated JSON](output/usb-normal-startup-observation.json) includes
capture/source/binary hashes, the one arrival, corroborating interface fields,
user report and limits. Raw JSONL, stderr and stop metadata remain local under
ignored `analysis/output/usb-startup/`. This is not new REA evidence.

To build the observer on macOS:

```sh
xcrun clang -std=c11 -Wall -Wextra -Werror analysis/usb_startup_observer.c \
  -framework IOKit -framework CoreFoundation \
  -o /private/tmp/xhalf-usb-startup-observer
```

A separately authorized observation can run that host utility with a duration
between 30 and 1,800 seconds and redirect stdout to a **new** capture file.
Verify `ready` and the baseline before instructing user actions. Record action
markers if precise plug/power timing is needed. Do not interpret unobserved
transient devices as proof that no lower-level USB activity occurred.

Coverage is limited to devices published as `IOUSBHostDevice` in the host
registry. Failed enumeration, activity too early to publish such a node,
electrical link events and internal firmware execution are outside coverage.
This run supplies no reason to send loader commands to the normal PTP device.
