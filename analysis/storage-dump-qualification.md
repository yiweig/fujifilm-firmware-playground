# Storage dump qualification: `rsvhdr` + `nandpr` (offline)

Date: 2026-11-05. Static qualification for a read-only internal-storage
dump, following [boot-info-block-and-updater-sdboot.md](boot-info-block-and-updater-sdboot.md)
step 2. Decompilations:
[output/nand-read-handlers-decomp.txt](output/nand-read-handlers-decomp.txt),
[output/nand-core-read-decomp.txt](output/nand-core-read-decomp.txt).
No camera access in this pass.

## The commands (CDSP shell, NAND cluster at `0x40df8fd4`)

The internal storage is driven through the platform "NAND" device
object (`FUN_400cbf18(1)`; `disk_cmd_rawc.c`-family driver, vtable
reads at `+0x2c` ECC / `+0x38` raw-page, info at `+0x8c` page shift).
Relevant shell commands, all with explicit `argc` guards (no
bare-invocation crash pattern of the `rcaraw` kind):

| Command | Handler | Semantics (decompiled) |
| --- | --- | --- |
| `rsvhdr` | `0x402ffcb0` | **Read-only print.** Gets the NAND info struct (`FUN_400cbf18(-1)` → parsed header buffer), checks signature `0xBEEF` at `+8`, prints the same fields we decoded from the DRAM copy (counts at `+0x28..+0x3c`, checksums at `+0x40..+0x46`, FW fields at `+0x24/+0x4c`). `rsvhdr 1` prints bad-block tables; `rsvhdr 2` dumps the DRAM-parameter struct (`+0x200`, `0x3e00` bytes) |
| `nandpr <sect> <nrSect> <dramAddr> [noECC]` | `0x40300040` → core `0x401846a0` | Reads `nrSect` 512-byte sectors starting at `sect` into `dramAddr`. Defaults: nrSect 1; `dramAddr` allocated if omitted (we always pass it). 4th arg nonzero selects the raw-page path; omitted/0 selects the ECC path (vtable `+0x2c`). A cache operation (`0x4000b608`) follows the read, so a host DRAM read afterwards is coherent |
| `rsvfwr <dramAddr> <size>` | `0x402ffcdc` | Reads the driver's two firmware regions (offsets/sizes computed from info-struct fields) to DRAM. Interesting later; region math is less transparent than `nandpr`, so not the first tool |
| `rsvrd` | `0x403009f8` | Decompilation is too mangled to qualify confidently; **not used** |

Argument parsing is `strtoul` base-0 (`FUN_4004b428`): counts are
decimal, the DRAM address must be `0x`-prefixed hex.

## Live plan (phased)

**Phase 1 — validate the chain (one loader entry):**
1. Console: `rsvhdr` — expect the header fields to match the DRAM
   decode (FW1 start 795, length `0xe38400`, checksums
   `0x3828/0x3fa3`, FW2 zero). Pure prints.
2. Console: `nandpr 0 8 0x41122000` — sectors 0–7 (4 KiB) into the
   scratch staging buffer (ECC path).
3. Exit console client; loader client `--read 0x41122000 0x1000` —
   fetch the staging buffer. Expect `NAND RSV` + `0xBEEF` at the
   start and identity with the first 512 bytes the earlier stage
   copied to SRAM (which we hold in the SRAM dump) — a three-way
   cross-check (storage ↔ SRAM ↔ DRAM copies of the header).

**Phase 2 — full backup (only if Phase 1 is clean):** scripted loop
over `nandpr` chunks into a ≥512 KiB staging area with loader
read-back per chunk: header blocks (sectors 0–1023), Firmware1
(sector 407040 = block 795 × 512, length 29,122 sectors), then
reserved areas as capacity allows. Every chunk lands on the host
before the next is issued; a stall costs only the in-flight chunk.

## Risks and boundaries

- `nandpr` is a read at the driver level; the write commands in the
  same cluster (`nandpw`, `rsvwr`, `nander`, `nandbad`, `rsvfww`,
  `sdfw*`) are **not** qualified and must not be sent. The shell has
  no confirmation prompts — command strings are sent verbatim, so
  the client invocations must be exact.
- The DRAM destination is the only writable target and is restricted
  to the established scratch staging addresses.
- Reading via ECC path returns the data area as the boot path sees
  it (what a backup needs). Bad-block handling inside the driver is
  not yet characterized; Phase 1's header cross-check plus per-chunk
  hashes are the verification, and a chunk that disagrees with the
  SRAM/DRAM copies stops the plan.
- If a read wedges the shell/USB (the known failure mode), recovery
  is the battery pull; nothing persistent has been written.
