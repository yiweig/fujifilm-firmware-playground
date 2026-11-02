# Factory read failures: success replies can contain old or mixed data

Date: 2026-11-01. Offline original ARM emulation with modeled file APIs only.
No camera connection, USB traffic, storage access or firmware modification.

## Finding

The factory PTP replies do not distinguish a successful file read from
certain failed reads. Executing the original file-read wrapper and PTP
callback together confirms that open failures and insufficient file size can
produce exactly the same response codes and parameters as a successful read
of the old record. Repeating the read and checking its checksum cannot resolve
that ambiguity.

A short read can also return a mixture of newly read bytes and old RAM.
One deliberately constructed control produces a mixed record with a valid
checksum, distinct from both the original RAM record and the modeled disk
record. This demonstrates a possibility, not how often it happens on hardware.

This is stronger evidence for the freshness limit already noted in the
[reader assessment](ptp-cold-data-read-assessment.md) and
[validation report](factory-record-validation.md). It does not establish an
additional safe acquisition route.

## Instruction trace

Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
ARMv7 little-endian runtime base `0x40000000`.

`0x40125d94` branches to the file-read wrapper at `0x4015ba48`:

| Instruction region | Behavior in the inspected wrapper |
| --- | --- |
| `0x4015ba78..80` | Opens through `0x4013f4ac`; zero handle takes the error path without modifying the destination |
| `0x4015ba84..9c` | Gets file size through `0x4013f500`; rejects offset or requested end beyond that size |
| `0x4015bab4..c4` | Seeks through `0x40150e20`, reads through `0x4013f598` directly into the caller's buffer |
| `0x4015bac8..dc` | Preserves returned byte count, closes the handle, compares count to requested length |
| `0x4015bb14..34` | Returns 1 on the modeled errors, 0 on a complete read |

There is no rollback or destination clearing in this wrapper on a short read.
Behavior inside the lower read API itself is not emulated; the short-read
control models a conventional partial copy and returned count.

The factory callback calls the wrapper at `0x405cac44` and immediately
branches at `0x405cac48` to the common success reply. At `0x405cad64` it
replaces the return value with `0x2001`. The read-status value is not included
in that reply. The chunk operation copies the shared buffer into response
parameters at `0x405cacc4`, without checking how the file read ended.

The adjacent `0x1026` handling at `0x405cac4c..7c` also supplies no saved
file-read status: parameter 0 returns three zero parameters, parameter 1
reaches the ordinary success response, and other values return `0x200a`.
No change to the host command allowlist was made to probe this.

The wrapper has error-log calls using the strings `ui open err`,
`ui size exceed` and `ui read err`. Those logs are a separate possible
observation source, not values present in the inspected PTP replies. Their
external availability and correlation with a physical read remain unknown.

## Reproduction and scope

[`ReproducePtpColdRead.java`](scripts/ReproducePtpColdRead.java) retains the
two baseline controls and adds five cases. Each case executes two load
requests and 64 chunk requests after each load, reconstructs the full record,
checks the complete expected buffer and validates its checksum.

| Case | Modeled file behavior | Actual wrapper return | Returned record |
| --- | --- | --- | --- |
| 0 | Complete read of old record | 0 | Old record |
| 1 | Open fails; modeled disk differs from old RAM | 1 | Old record; all response codes/parameters identical to case 0 |
| 2 | Reported file size 1,023 bytes | 1 | Old record; all response codes/parameters identical to case 0 |
| 3 | Copies and returns only 16 bytes | 1 | New prefix and old suffix; checksum valid |
| 4 | Complete read of changed record | 0 | Changed record |

The old record is a synthetic byte pattern with a valid checksum. The changed
record has compensating byte changes in both its prefix and suffix. Each
pair preserves the byte sum, so the mixed short-read result also passes.
This collision is intentional and does not estimate random corruption risk.

All seven controls passed. [Retained output](output/ptp-cold-read-freshness.txt).
Success/failure comparisons concern callback response codes and parameter
values; USB framing, transaction management and hardware timing are outside
this emulator test.

The new cases execute original instructions in the callback and
`0x40125d94`/`0x4015ba48` wrapper. They model open, size, seek, read and close
APIs; logs, copy/zero and the response setter are also stand-ins. First-use
helper `0x4015b5f8` is stubbed. Its transitive effects are not qualified by
these tests. The baseline still stubs the whole file-read wrapper, and its
write control stops before executing the persistent writer.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproducePtpColdRead.java /private/tmp/ptp-cold-read-freshness.txt
```

The existing local Ghidra project and original bytes were reused after the
previously documented REA raw-image mapping limitation. No new REA runtime
observation is claimed.

## Decision for further work

Do not try to prove freshness by overwriting the shared buffer and rereading:
other paths can save that buffer, making such a challenge a persistence risk.
Do not enable service mode just to obtain a purported backup; initialization
itself attempts a whole-record save.

The next useful qualification is an independent read-success signal or a
separate acquisition path with explicit error reporting. The first-use
resource helper, native USB ownership and activation/exit still need review.
No independently verified recovery path has been established.
