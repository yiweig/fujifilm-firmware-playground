# Read-only dump and boot-log acquisition plan

This plan covers acquisition of the X half's early boot code, installed flash,
and cold-boot log without deliberately changing persistent camera state. It is
not yet an instruction to connect to unidentified board pads or send undocumented
USB commands.

## Owner constraints

The camera will not be disassembled, probed internally, or sent to a repair
lab. UART-pad capture and physical storage acquisition are therefore excluded
from the active plan. They remain documented below only to explain why they are
not being pursued. The active acquisition route is external USB, with SD-card
boot/logging considered only if a fully understood non-writing mechanism is
found.

## Recommendation

With unrestricted hardware access the routes would normally be ranked as
follows:

1. ask FujiMod/iCatch researchers for an existing X-half service-mode chord,
   UART pad map, or SPCA6500 read-only profile;
2. capture a **receive-only UART log** if suitable pads can be identified;
3. qualify an X-half-specific USB read path, beginning with a tiny known-memory
   read and only then progressing to full flash/state dumps; and
4. consider a physical flash-chip read only if the first two paths fail.

UART observation is the least invasive electrically after the pads and logic
voltage are known. USB is mechanically easier, but an unqualified service
command can write settings, calibration, or flash. A physical chip read risks
board damage and may need NAND ECC/OOB knowledge.

Under the owner constraints, proceed directly to Route B and do not execute a
request until its X-half-specific read-only semantics are proven statically.

## What each artifact would answer

| Artifact | Likely value | Important limitation |
|---|---|---|
| Cold-boot UART log | May print boot source, firmware index, checksum failure, or fallback decision | Logging may begin only after the selector, or be disabled |
| Internal-flash dump | Can contain the pre-main loader, Firmware1/Firmware2 headers and device-specific metadata | Requires exact geometry and a proven read-only reader |
| CPU-visible boot-ROM dump | Can reveal immutable startup and recovery behavior | ROM may be unmapped, protected, or return only zero/`0xff` |
| RAM dump after stock boot | Confirms loaded code/state and can expose retained boot information | Does not necessarily include earlier loader/ROM bytes |
| Physical storage dump | Independent of running firmware | Hardware risk; raw NAND may require spare/OOB/ECC and bad-block data |

## Route A: receive-only UART capture

### Why it is plausible

The shipped main image contains a real three-channel SPCA6500 peripheral-UART
implementation, UART pin-mux and baud-rate controls, and an interactive UART
shell. The card updater also contains messages such as `Boot from bootinfo%d`.
This proves UART support in the software family, but does **not** identify which
channel reaches a test pad or whether the earlier selector emits a log.

A comparable SPCA6500 board in the public iCatch V50 Playground exposes UART
pads. Its pad placement cannot be transferred to the X half.

### Hardware gates

Do not attach a USB-UART adapter to an unidentified pad. Before capture:

1. obtain clear, high-resolution photographs of both X-half PCB sides, ideally
   from a teardown or repair specialist rather than opening the only camera;
2. identify ground by continuity with the camera unpowered;
3. measure candidate-pad idle voltage with a high-impedance meter or scope;
4. determine whether the signalling is 1.8 V, 3.3 V, or something else; and
5. begin with a high-impedance logic analyzer or correctly rated receiver on
   **camera TX only** plus ground.

Never connect the adapter's VCC/5 V pin. Do not connect host TX to the camera
until a specific command protocol is understood. Avoid probing around power,
flash-drive, display, and sensor connectors with the battery installed.

### First capture

- Use a blank SD card with no updater file.
- Start capture before inserting the battery or switching on.
- Record a normal cold boot produced by removing and reinserting the battery;
  do not start with an undocumented button chord.
- Capture the raw logic waveform, not only decoded text, so baud and polarity
  can be changed offline.
- Try common rates such as 115200 only during offline decoding; do not assume a
  rate from another board.
- Repeat at least twice and compare the logs.

Useful evidence would include `BootInfo`, `Firmware1`, `Firmware2`, checksum,
priority, or storage-region messages. Absence of text is only a bounded negative:
it may be the wrong pad, wrong baud, disabled logging, or a selector that runs
before UART setup.

## Route B: qualified USB dump

### Current blocker

Normal X-half USB is observed as PTP `04CB:030A`. Static analysis found hidden
factory PTP operations and a separate UVC/RCA command-forwarding implementation,
but neither is a proven arbitrary read path in normal retail mode.

The internal shell behind the UVC/RCA path includes a `dump` command described
as reading memory/register ranges. It also contains explicitly mutating commands
such as `fill`, which is why exposing the shell is not by itself a safety proof.
The promising capability is present; the unresolved risk is how to enter the
mode and constrain the transport to the read-only command.

FujiMod's ff80 service/debug protocol supports memory operations for qualified
camera definitions. Its documentation explicitly makes command availability,
limits, memory maps, reader ABI, and transport firmware-specific. The public
catalog currently has no X-half flash-reader, boot-ROM extent, safe-state map,
or processor transport. Therefore another body's command numbers or addresses
must not be tried on the X half.

### Facts required before the first request

1. exact installed firmware identity and match to the analyzed image;
2. exact service-mode entry and the USB identity it produces;
3. request envelope and command dispatcher for that mode;
4. a read-only CPU-memory primitive with explicit bounds;
5. known readable RAM ranges that exclude MMIO and read-side-effect registers;
6. stock flash-reader entry, calling convention, lock/state requirements,
   storage geometry, and destination-buffer rules; and
7. battery-removal cleanup for any volatile access enable or RAM guard.

### Qualification ladder

Each rung must succeed and be reviewed before the next:

1. passive USB enumeration and descriptor capture;
2. documented identity/version query;
3. a very small read from known immutable bytes in the loaded stock image,
   compared byte-for-byte with firmware version 2.00;
4. repeat that read and require identical bytes;
5. bounded DRAM reads that avoid devices/MMIO;
6. a qualified CPU-visible boot-ROM read, repeated twice if constant;
7. a small update-backed control read through the stock storage reader; and
8. a complete flash dump, read twice in chunks, with per-chunk and whole-image
   SHA-256 hashes.

The dump manifest should preserve camera/firmware identity, address or physical
offset, exact length, transport, timestamp, read count, and hashes. Serial
numbers, calibration and identity regions should remain private by default.

The desired outputs are analogous to FujiMod's `flash.bin`,
`memory-dram.bin`, and `memory-boot-rom.bin`, but the X half must first gain the
catalog facts that make those operations available. FujiMod's current Dev Tools
design disables the operation when those facts are missing; that is the correct
behavior for this camera today.

## Route C: physical storage acquisition

This is a last resort. First identify the exact storage part and interface from
board photographs and markings. A serial NOR/SPI NAND chip may sometimes be
read with a correctly voltage-matched programmer while the camera is fully
unpowered, but in-circuit loading and back-powering must be assessed. Raw NAND
requires page, spare/OOB, ECC and bad-block handling; a flat logical read is not
an adequate backup.

Do not desolder or attach a clip merely to discover the chip type. A repair lab
with camera experience and proper ESD/rework equipment is preferable. A mask
ROM inside the SPCA6500 cannot be obtained by reading an external flash chip.

## SD-card logging status

The main firmware contains extensive logging and debug-file machinery, but no
safe retail marker has yet been proven to redirect the *early boot selector's*
output to SD. Creating guessed marker files can activate factory interfaces or
persistent writes. SD logging is therefore a static-analysis lead, not a current
acquisition recipe.

## Immediate next work

Before opening or connecting the camera, statically trace:

1. the retail startup calls that select UART channel, pin mux and baud rate;
2. whether the `BootInfo` messages use UART, an in-memory ring buffer, or both;
3. the USB-mode activation path and descriptor identity for its service mode;
4. the smallest read-only memory handler and its bounds; and
5. the stock storage operation used by the updater for firmware-map reads.

In parallel, ask FujiMod maintainers specifically for an X-half UART pad map,
service-mode chord/VID:PID, ff80 support status, and any private SPCA6500
flash-reader work. A verified answer to any one of those can eliminate a large
amount of risky trial and error.

## Related project notes

- [`fujimod-new-camera-onboarding.md`](fujimod-new-camera-onboarding.md)
- [`ptp-and-service-command-paths.md`](ptp-and-service-command-paths.md)
- [`ram-loading-assessment.md`](ram-loading-assessment.md)
- [`firmware-copy-and-fallback.md`](firmware-copy-and-fallback.md)
- [`spca6500-package-and-recovery-comparison.md`](spca6500-package-and-recovery-comparison.md)
