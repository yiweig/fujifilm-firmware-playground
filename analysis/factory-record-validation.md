# Factory-record validation before any service experiment

Date: 2026-11-01. Offline firmware inspection, original ARM emulation and
synthetic host tests only. No camera record has been acquired in this work.

## Result

The restricted reader now checks the factory record's checksum as well as
size, nonuniform contents and equality across two reads. This closes a missing
integrity check; it does not qualify a live backup or service-mode entry.

The motivation is the [service initialization save](service-entry-initialization-writes.md):
even with automatic calibration disabled, initialization attempts to save the
whole factory record. Knowing only the automatic-calibration flag is insufficient
to justify a button experiment.

## Firmware evidence

Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Addresses use the ARMv7 little-endian runtime base `0x40000000`.

The [earlier writer audit](cold-buffer-lifetime.md) identified the full-record
case of `0x40125e4c`. At `0x40125e98`–`0x40125ea4` it calls `0x4015bd58`
with length `0x3fe`; at `0x40125ec8` it stores the result as a halfword at
record offset `0x3fe`. The checksum leaf sums unsigned bytes modulo 256.
Thus a record written through this path has:

```text
LE16(record[1022:1024]) == sum(record[0:1022]) modulo 256
```

The stored high byte must be zero. This is an integrity convention for
`COLDDATA.BIN`, not the firmware package's signature or checksum.

[`ReproduceColdChecksum.java`](scripts/ReproduceColdChecksum.java) executes the
original leaf directly in Ghidra's ARM emulator, without stubs or writer calls.
Four vectors passed: zero bytes, 1,022 bytes of `ff`, sequential bytes, and a
single `80` byte, producing 0, 2, 3 and 128 respectively. The final two bytes
were deliberately `a5/5a` to verify their exclusion. Input memory remained
unchanged. [Retained output](output/cold-checksum.txt).

Reproduce against the existing correctly mapped project:

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceColdChecksum.java /private/tmp/cold-checksum.txt
```

These controls establish the checksum leaf's modeled behavior, not actual
file persistence, a physical read or successful recovery.

## Reader changes and verification

`Capture` now accepts only immutable, exactly 1,024-byte records, rejects
uniform zero/ff data, and verifies the complete stored checksum halfword.
The production decoder rejects invalid records; only synthetic fixtures
generate checksums. Summaries explicitly distinguish checksum validity,
freshness, firmware identity and service-entry safety. The three Hce version
fields are exposed as bookkeeping values, not a camera firmware release.

The successful request sequence remains 132 requests: open, load plus 64
chunks twice, close. The outgoing allowlist is unchanged. An invalid matching
record aborts at request 131 before CloseSession, following the existing
no-further-I/O error policy; the reader cannot be reused to retry.
There is still no live discovery/open/claim backend or automatic activation.

All 22 host test methods passed:

```sh
python3 -m unittest discover -s analysis -p 'test_*.py' -v
python3 analysis/ptp_cold_reader.py --simulate
```

New controls reject matching records with corruption in the body or either
checksum byte, verify no close/retry follows rejection, reject mutable input,
and demonstrate that compensating byte changes can pass this weak checksum.
The transport integration fixture also has a valid synthetic checksum.

## Remaining acquisition gates

- **Freshness:** the traced factory read ignores file-read errors. Two equal,
  checksum-valid copies can therefore still be old shared RAM. Independent
  evidence of a successful file read remains missing.
- **Identity:** the record and its Hce fields do not establish the attached
  camera model or firmware release. Device/firmware preflight remains open.
- **Ownership and lifecycle:** the measured USB descriptor is known, but
  native exclusive access, safe activation, registration revocation, error
  handling and physical exit still require qualification. See the
  [transport report](usb-transport-qualification.md).
- **Recovery:** even a trustworthy record capture would not demonstrate a
  way to restore it after a failed boot. Independent recovery remains unproved.

A checksum-valid record must not be described as an authenticated, fresh or
restorable backup on this evidence. No service buttons or SD markers should
be tried merely because this validation now passes in simulation.

Follow-up: [file-read failure controls](factory-read-failure-controls.md)
execute the original file-read wrapper and callback together with modeled
lower APIs. They confirm indistinguishable success replies for stale data,
and demonstrate a checksum-valid mixed record after a modeled short read.
