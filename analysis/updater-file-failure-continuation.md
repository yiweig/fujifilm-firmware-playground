# File-action failures can return toward updater USB startup

Date: 2026-11-03. Offline original-ARM analysis, official 2.00 complete updater,
base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
All routine addresses here belong to that updater. No camera access or changes.

## Finding and correction

Follow-up: [USB resource bring-up](updater-usb-resource-bringup.md) traces the
formerly unnamed platform helper through original reference-count and register
code, with eight focused controls. Main-image USB initialization corroborates
an acquisition/PHY-clock bring-up role, without proving live USB availability.

The executing updater has a software path from a missing card file or a
returned file-loader error back toward its USB startup continuation. That
path calls platform helpers and changes registers; if those helpers return,
startup can reach `0x400007f4`, which prepares ISP and USB service machinery.

Earlier reports called helper `0x40075ea4` a **shutdown** boundary. That name
was too strong. Its original instructions have a return path, and the caller
continues with additional register writes. Its full hardware purpose remains
unqualified. It must not be treated as an identified power-off or halt routine.
Historical output retains its old label; the address and stop boundary remain
valid, and the new controls/report correct the interpretation.

This is useful evidence about a running updater's error handling. It is not
proof of live USB availability, hardware survival through the register changes,
or independent entry after the installed main image fails.

## Distinguish four paths

| Condition in the already executing updater | Observed control flow | What remains unknown |
| --- | --- | --- |
| Startup gate `0x10001300` is zero | Skips automatic file handling, calls `0x40069374`, then returns toward USB startup | Earlier producer and real platform-helper behavior |
| Supplied file check reports missing/error | Logs `file not exist`, calls platform helper, performs register sequence, returns toward USB startup if helpers return | Actual file check, hardware effects, successful enumeration |
| File/rawC loader returns an error | Logs `FW boot error`, then follows the same platform/return sequence | Complete loader and hardware execution; a nonreturning payload follows no such return path |
| Loaded image takes control and never returns | The copied stub does not reach its post-payload path | No watchdog/reset/independent fallback policy established here |

The prior [installed-image checksum controls](updater-installed-image-checks.md)
are separate: they establish a checksum-error branch for Firmware2. The new
return controls below model a **card-file boot helper returning failure**;
they do not execute a full Firmware2 failure or prove all failed installed
images reach this continuation.

The rawC `:NO_BOOT` record discussed in the
[selection trace](updater-firmware-selection.md) stops its selection callback
early. It does not cancel every subsequent automatic-file path in the outer
consumer. It is stored configuration data, not an established SD-card marker
or a safe command to hold the camera in USB mode.

## Follow the outer file consumer to its return

Consumer `0x400009c4` checks the constructed card pathname at `0x40000fec`.
A nonzero check result takes the log/error branch and joins `0x40001094`.
The non-ISP file boot branch calls `0x4000f270` at `0x40001084`, logs its
returned status, and joins the same location. The lowercase `.brn` ISP branch
has separate update behavior and is not executed in the new return controls.

At `0x40001094`, the consumer calls `0x40075ea4`. Original code afterward:

| Address | Operation |
| --- | --- |
| `0x400010a8` | Write byte 0 to `0x10005105` |
| `0x400010ac` | Write word 8 to `0x10000030` |
| `0x400010b0` | Write word 8 to `0x10000034` |
| `0x400010b8` | Write byte 2 to `0x10005100` |
| `0x400010bc` | Call `0x400874e8(0)` |
| `0x400010c0` | Call `0x40069374` |
| `0x400010e4..0x400010e8` | Restore stack/registers and return |

The startup caller's next instructions `0x40001394..0x40001398` restore
its saved frame and tail-call `0x400007f4`. As previously traced, that
continuation sets up a [command interpreter task](updater-command-task.md), selects USB target 0, registers the receiver,
creates USB tasks/queues and performs controller setup. None of that continuation
executes in the new controls; it is their final boundary.

Helper `0x40075ea4` calls `0x40075dd8(1)`, requests mask `0x00080200` through
`0x400662b0`, writes byte `0x3f` to `0x10005021` and returns. Its subhelper
uses a RAM reference count and, on a first-use branch, issues a timed byte
sequence at `0x10000380` and `0x10000383`. It has a return path if its delay
and platform helpers return. These observations do not assign an electrical
purpose, prove that USB remains powered, or make the real sequence safe to run
as an experiment. Helper `0x40069374` likewise performs peripheral operations
and can return; its full startup role remains unaudited.

## Three additional original-instruction controls

[ReproduceUpdaterBootMailbox.java](scripts/ReproduceUpdaterBootMailbox.java)
retains the original eight pathname/branch controls and adds three return-flow
cases. The latter start at the original startup call `0x40001390`, with its
saved stack frame supplied:

| Gate | Supplied pathname/check | Modeled boot result | Final boundary |
| --- | --- | --- | --- |
| Zero | No file path used | Not called | `0x400007f4` |
| Nonzero | `D:\TEST.bin`, check returns 1 | Not called | `0x400007f4` |
| Nonzero | `D:\TEST.bin`, check returns 0 | Loader returns 1 | `0x400007f4` |

All **11 controls pass**. The original consumer, startup return instructions
and post-platform register stores execute against synthetic emulator memory.
The latter stores' values are checked, and executable bytes remain unchanged.

Crucially, `0x40075ea4`, `0x40069374` and `0x400874e8` are **modeled as
returning**. The file boot helper is modeled as returning failure. File check,
storage readiness, drive probe, earlier configuration setters, copy and logging
are also modeled, as declared by the original harness. No actual platform
helper, USB startup, scheduler, loaded image, storage write or camera runs.
This establishes conditional software return flow, not a hardware success test.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-updater-full GHIDRA_PROJECT_NAME=FujiUpdaterFull \
  bash analysis/run-ghidra.sh -process card-updater-full.bin -noanalysis -readOnly \
  -postScript ReproduceUpdaterBootMailbox.java /private/tmp/updater-failure-continuation.txt
```

Evidence: [11 controls](output/updater-failure-continuation.txt) and
[static instruction ranges](output/updater-file-failure-continuation.txt).
No new REA or live observation is claimed.

The findings justify distinguishing a returned loader error from a permanent
halt. They still do not justify provoking a bad boot or flashing an image:
independent entry, physical hardware state and real recovery behavior remain
unverified. The camera can remain unplugged for this investigation.
