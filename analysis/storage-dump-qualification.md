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

## Live result (2026-11-05): chain validated, Firmware1 backed up

Executed with `analysis/xhalf_nand_dump_linux.py` (shell `nandpr` into
staging `0x41130000`, loader read-back in 64 KiB sub-chunks,
512 KiB per chunk, per-chunk SHA-256 logged). One operational lesson:
re-running the full APITEST activation in the same boot did not
produce a prompt within 25 s (bridge was already active); the dump
script's `--continue` mode (assume live bridge) is the correct
second-run path, as with the console client.

Results:

1. **`rsvhdr`** printed the driver's header struct: `nrHdr 2`,
   `nrRsvA/B/C 487/45/261`, `nrFw1 57`, `nrFw2 0`, checksums
   `38706/14376/16291/0` (`0x9732/0x3828/0x3fa3/0`), `tolblk 2048` —
   matching the offline DRAM decode field-for-field.
2. **Header sectors (0–1023, 512 KiB)** dumped; the first 512 bytes
   are byte-identical to the parsed copy in the DRAM dump
   (`NAND RSV` + `0xBEEF`). Storage ↔ running-system cross-check
   passes.
3. **Firmware1 (sector 407040, 29,122 sectors, 14,910,464 bytes)**
   dumped. **SHA-256
   `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
   — byte-identical to the official package's main DRAM image**
   (and to the local `firmware-main.bin`). The installed firmware
   on this unit is pristine, and the backup is cryptographically
   verified against an independent source.
4. **Reserved-area heads (first 512 KiB each):** A and C both begin
   with repeating `PHYTABLE` table pages (the NAND layer's physical
   tables; payloads, if any, sit behind them — no updater-image
   blocks matched in the probed ranges). B's head is uniformly
   `0xFF` (erased).

Artifacts (preserved outside git in `/private/tmp`):
`xhalf-nand-headers-2026-11-05.bin` (`5f1169c5…`),
`xhalf-nand-fw1-2026-11-05.bin` (`80350bd2…`),
`xhalf-nand-rsvA-head-2026-11-05.bin` (`5184e2a5…`),
`xhalf-nand-rsvB-head-2026-11-05.bin` (`043e238a…`),
`xhalf-nand-rsvC-head-2026-11-05.bin` (`25c36bc1…`).

Remaining for a complete C3 backup: full Reserved A/C (the A/B
contents likely mirror package regions we already hold; C is the
interesting one for updater residency), the post-Firmware1 range
(blocks 852–2047, mostly package filesystems), and identification
of what lives behind the `PHYTABLE` pages. The dump tool makes
these mechanical now (~4 min per 15 MB observed). The SD marker
was intentionally left in place after this session for near-term
reuse; normal camera operation is unaffected (debug entry still
requires the switch/button combination).
