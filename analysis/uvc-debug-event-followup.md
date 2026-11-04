# UVC callback registration: debug-event follow-up in progress

2026-11-04. Preliminary static findings, not a qualified retail entry. No camera,
USB request, SD marker, factory write or firmware modification in this pass.
Official 2.00 main SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`,
ARM little endian, base `40000000`. Original instructions were inspected with
`fw.py`; focused pseudocode used the private ELF-backed Ghidra project described
in [UI gate evidence](remaining-ui-loader-gates.md). No new REA result is claimed.

## Established so far

The earlier [UVC activation report](uvc-service-mode-activation.md) documents
calibration and shell activation. A direct-call follow-up identifies an additional
application-debug-event route to the same callback-registration wrapper:

```text
application event f100000f, parameter 10e7
  -> main dispatcher 40400d50
  -> selected branch 40402498 (first calls 4000f458 with argument 20 hex)
  -> 405e2a44, using the event parameter
  -> branch 405e2dac, UVC reconnect/setup
  -> 405e2e28 calls registration wrapper 405e67b8
  -> 4061b0bc registers EUC/UVC service callbacks
```

The event number is derived from main-dispatcher literal
`40401d30 = f1000013`, subtracting four through the original comparisons at
`40400f10..40400f28`. It is an internal event, not a PTP opcode or USB request.
The debug handler compares parameter 10e7 between the 10e6 and 10e8 cases;
its reconnect branch selects USB API mode **1**, the UVC path, not RAM-loader
mode 4. It selects internal `A:\\RO_RES\\USBUVC.BIN`, registers callbacks,
sets USB configuration and requests connection. Its startup/runtime helpers
have not been qualified as harmless or executed in an integrated control.

The known RCA/EUC service can forward a command string to the shell, but its
exposure still requires this separate USB personality. Normal PTP reachability
does not follow from finding the code.

## Producer search boundary

An aligned whole-image exact-word search found six f100000f literals:
`405227ec`, `405df83c`, `405e3a64`, `40613efc`, `40614084`, `40614774`.
A bounded aligned ARM PC-relative-load scan in the first ab0000 bytes linked
them to producer contexts. This does not exclude computed event construction,
indirect references or code outside that scan.

- `4052241c` contains a post of f100000f with parameter 10e7 at `405224c8`.
  The surrounding routine and its external activation remain under investigation.
- `405df684` stores supplied debug command/parameters in RAM rooted at
  `40f12590`, then posts the event at `405df800`; its callers remain to be traced.
- `40613f8c` includes a service-processing path posting a supplied command at
  `4061404c`; availability of that input is not yet established.
- Other inspected producers post parameters 100f, 10e0 or 10e5, and the debug
  handler itself posts 10e7 during other operations. None alone proves a normal
  physical trigger for the required event.

## Next question and limits

Trace the callers and input source of **4052241c and 405df684**, then determine
whether a normal retail action or already-qualified transport can cause
f100000f/10e7 without calibration entry or persistent writes. Do not assume that
posting an event in an emulator demonstrates host access to it.

**Closed 2026-11-04:** the [producer trace](uvc-debug-producers-trace.md)
identifies all producers — `DbgMsg_Thread` timer, `dm` shell command, UVC
service thread, handler self-posts — and every one requires a pre-existing
shell, calibration/debug, or UVC context. No retail-reachable producer
exists among them; this lead is a bounded negative.

Only after reachability is established should the UVC callback/request lifecycle
and an explicit route from that service to loader selection be qualified. This
is a promising software relationship to inspect, not a demonstrated RAM-boot
path, safe live request, ordinary webcam feature, or independent recovery route.
