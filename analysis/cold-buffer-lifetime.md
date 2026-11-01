# Shared cold-data lifetime: reload and incidental save boundaries

Date: 2026-11-01. Official 2.00 main-image SHA-256:
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
Offline analysis and emulation only; no camera access or storage writes.
ARM runtime addresses use base `0x40000000`.

## Finding

Temporary staging modifies a shared settings buffer, not an isolated upload
buffer. Two concrete paths illustrate why its lifetime must be qualified:

| Path, if reached under its conditions | Observed original-code behavior |
| --- | --- |
| Charging-related callback reload | A successful file read replaces staged cold RAM without refreshing the cached USB-selection flag |
| Unrelated field update after a failed reload | Proceeds to the whole-buffer write call with the staged USB byte still in the outgoing buffer |

The second result is a **save attempt boundary**, not an observed persistent
change: the emulator stops before the writer. A real write could fail too.
Neither outcome establishes a bricking probability, an actual read failure
on this camera, or that the field updater runs during ordinary shutdown.

No additional safe loader-entry route was established. In particular, the
remaining `StartMain` producer at `0x405aeed8` had no incoming aligned ARM
B/BL or exact stored pointer in the bounded scans. Its embedded messages
describe mount/format/card-related power-off behavior. Computed calls and
other addressing forms are not excluded; it must not be treated as a live
retail entry merely because its code can post `StartMain`.

## Shared reload in the callback task

Task loop `0x404076bc` waits through `0x4006e4a4`. If byte `0x40e4ae5d`
is nonzero, it calls `0x40407398`. Its registered task pointer is stored at
`0x4040777c`; the nearby shipped name is `IsrCallback`. This is a task body
handling callback work, not proof that the entire function executes in a
hardware interrupt context.

In the inspected callback branch:

1. `0x4015b170` performs LED housekeeping. The focused test stubs this call.
2. Raw USB presence must be nonzero for the tested path. Other GPIO inputs
   choose branches; the test supplies the path to `0x4040750c`.
3. Counter `0x413acad8` (`0x413aca60 + 0x78`) gates further work. If nonzero
   on entry, it decrements and returns. **The subtraction does not set ARM
   flags**, so entry value 1 still returns after becoming zero. On the next
   invocation with zero, the code resets it to 10 and continues. No duration
   in seconds is inferred from this count.
4. Nonzero word `0x40e06660` (`0x40e06590 + 0xd0`) selects shared-buffer
   reload at `0x4040753c -> 0x40125d98 -> 0x4015ba48`.
5. The next instruction, `0x40407540`, enters charging-status work. The test
   stops there. Later instructions clear the reload-selection word at
   `0x40407574`; their full charging effects are outside this reproduction.

The packaged initial value of `0x40e06660` is 1, but its value when a host
could stage settings is unknown. This does not establish repeated reloads or
a race exploitable by timing USB requests. A successful read would replace
the 1024-byte buffer at `0x40e4df02`; no assignment to USB-selection byte
`0x40e6696d` occurs in the tested path. Buffer restoration alone therefore
does not prove that loader-selection behavior has been restored.

## Unrelated field update can carry staged bytes to a save attempt

Function `0x4029de10` takes a value and executes:

```text
40125d94("A:\\RO_RES\\COLDDATA.BIN", 0, 1024, sharedColdRAM)
sharedColdRAM halfword at +0x3b4 = incoming value
40125e4c("A:\\RO_RES\\COLDDATA.BIN", 0, 1024, sharedColdRAM)
```

The read's return status is not tested. The original instructions store the
new field at `0x4029de38` and tail-call the writer at `0x4029de50`.
One found direct caller is `0x40201fa4`, following a ten-value average in an
OSD/display routine. The precise user-level meaning and reachability of that
measurement branch remain unqualified; it is not described as a shutdown hook.

Two modeled controls execute the complete wrapper up to the writer boundary:

- Successful read replaces the staged buffer with the modeled file, then
  updates `+0x3b4`. The staged USB byte is gone from the outgoing buffer.
- Read failure without copying leaves the staged buffer intact, then updates
  `+0x3b4`. The staged USB byte remains in the outgoing whole-buffer write.

The real reader returns 0 on success and 1 on failure. Its open-failure branch
at `0x4015ba7c..0x4015ba80 -> 0x4015bb08` does not perform the copy into the
requested destination. The test models that outcome; partial reads and actual
file I/O are not emulated. The full writer never executes in the test.

The static writer audit adds a relevant integrity detail. `0x40125e4c`
compares the destination path with the cold-data path. For a matching full
1024-byte write at offset zero, it recomputes a byte sum over the first 1022
bytes using `0x4015bd58`, stores the resulting 8-bit value as a halfword at
`+0x3fe`, then calls the file writer `0x4015b928`.
Consequently, an unchanged old checksum is not an established barrier against
an accidental full-buffer save. This is a cold-data checksum, not a firmware
package signature or a bypass of the firmware updater's integrity checks.

## Offline checks

The expanded [script](scripts/ReproduceTemporaryUsbSetting.java) retains all
previous cases and adds four reported groups: successful/failed field reload,
successful/failed callback reload, nonzero-counter control and zero
reload-selection control. All 22 reported PASS groups passed.
Output: [temporary-usb-setting-lifetime.txt](output/temporary-usb-setting-lifetime.txt).

The field wrapper uses modeled file results and stops before persistence.
The callback starts at its real function entry with supplied MMIO, counter
and selection word, stubs the LED housekeeping, and stops before charging
APIs. An alternate control stops at `0x40124f3c` before executing that helper.
Assertions compare the entire cold-data buffer, not only the USB field.
Neither test establishes complete task scheduling or physical peripheral effects.

```sh
GHIDRA_PROJECT_DIR=/private/tmp/fuji-ghidra-v7 GHIDRA_PROJECT_NAME=FujiV7 \
  bash analysis/run-ghidra.sh -process firmware-main.bin -noanalysis \
  -postScript ReproduceTemporaryUsbSetting.java /private/tmp/temporary-usb-setting-lifetime.txt

python3 analysis/fw.py disasm 0x4040750c 0x40407578
python3 analysis/fw.py disasm 0x404076bc 0x4040771c
python3 analysis/fw.py disasm 0x4029de10 0x4029de5c
python3 analysis/fw.py disasm 0x40125e4c 0x40125f00
python3 analysis/fw.py disasm 0x4015bd58 0x4015bd80
python3 analysis/fw.py callers 0x405aeed8 0x405aee98
```

Original bytes and the existing local Ghidra project were used after the
documented REA raw-image mapping limitation. No new REA/runtime observation
is asserted. Ghidra's reference query for `0x40e06660` lists only the callback
read and clear; its known reference gaps prevent treating that as exhaustive.

## Research consequence

The shared-buffer staging candidate still needs both entry qualification and
lifetime qualification. A prospective sequence must account for successful
reloads that discard the change, failed reloads followed by saves that could
include it, and the cached UI flag's separate lifetime. A read-back immediately
after staging would not rule out later changes. The specific paths here are
conditional examples, not a complete census of all saves and reloads.

The next useful work is to map the retail PTP session's actual UI state and
its event/cleanup ordering, particularly the transition that ends USB mode,
rather than trying the speculative sequence live. Independent cold recovery
and a safe RAM-loader activation procedure remain unproved.
