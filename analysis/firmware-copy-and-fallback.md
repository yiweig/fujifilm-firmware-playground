# Firmware1/Firmware2 and fallback assessment

This note asks whether the X half's second firmware region protects against a
bad Firmware1 update. It is based on static ARM analysis of the official 2.00
package. No camera command was sent and no flash operation was attempted.

## Bottom line

The official update path is deliberately transactional around Firmware1:

1. the X half UI enables the updater's Firmware1-checksum invalidation option;
2. the card updater changes Firmware1's stored checksum before writing it;
3. Firmware2 is left untouched by the ordinary official-package path;
4. a successful update restores Firmware1's original checksum metadata; and
5. an update error deliberately leaves Firmware1 marked invalid.

This is strong evidence that Firmware2 is intended to preserve a bootable or
recoverable firmware while Firmware1 is replaced. It is **not yet proof of
automatic cold-boot fallback**. The code that runs before either installed
firmware and chooses between them is not present in `FPUPDATE.DAT` or the
extracted main application. It is likely in the internal-flash boot stage or
mask ROM, neither of which we have dumped.

Current confidence:

| Claim | Confidence |
|---|---|
| The official UI enables Firmware1 checksum invalidation | observed |
| Firmware1 is invalidated before its write | observed |
| Firmware1 is restored only on successful completion | observed |
| The standard X half package path leaves Firmware2 unchanged | observed |
| Firmware2 is intended as a fallback/recovery copy | strong inference |
| Invalid Firmware1 automatically cold-boots Firmware2 | unknown |

## Official UI enables transaction mode

The firmware-update UI function at `0x40485d00` configures the generic card
updater immediately before starting it:

- command 19 with argument 0 reaches `0x405efd9c` and clears option bit 0;
- command 21 with argument 1 reaches `0x405efdbc` and sets option bit 1
  (mask `0x02`); and
- the UI then calls the X half adapter at `0x405ade18`, which invokes the
  file-based card-update path.

The bit set by command 21 gates the Firmware1 checksum transaction in the card
updater. This matters because the invalidation strings are not merely dormant
vendor-library text: the X half's actual update UI enables their control path.

## Firmware1 invalidation and restoration

The orchestration function at `0x405efddc` obtains the installed firmware-map
header through storage operation 10. With option mask `0x02` set it:

- reads Firmware1's 16-bit checksum field at map offset `0x42`;
- saves the original value;
- writes the value plus two while preserving the adjacent checksum field; and
- logs `damage FW1 checksum1 %04x -> %04x`.

The ordinary firmware writer then programs Firmware1 and optionally reads it
back for comparison. At the end:

- the zero-error path at `0x405f17c0` re-reads the map, writes back the saved
  checksum, and logs `restore FW1 checksum1 %04x`; while
- the nonzero-error path at `0x405f18c0` logs
  `keep damaged FW1 checksum` and does not restore it.

The related installed-image checker at `0x405efbe4` reads a firmware region,
computes a 16-bit byte sum using `0x40057d40`, and compares it with the stored
halfword selected for Firmware1 or Firmware2. This confirms that the fields are
integrity selectors, although static analysis does not show the earlier boot
stage consuming them.

## The official path preserves Firmware2

The package parser at `0x405f8fdc` allocates and zeroes a 640-byte state object,
then copies the 512-byte package header into its beginning. The Firmware2
update-enable field is at state offset `0x234`, beyond those copied 512 bytes,
so it remains zero unless an explicit optional API call changes it.

In orchestration:

- Firmware1 has package data at header/state offset `0x1c` and is written;
- Firmware2 is written only when state field `+0x234` equals 1; and
- zero selects the `keep original Firmware2` branch.

The official X half top-level header also has zero at file offset `0x2c`, where
the generic writer expects the start of a second firmware payload. In other
words, this package carries one main firmware payload and the normal X half UI
does not opt into the generic Firmware2 writer.

This corrects an earlier ambiguity: the presence of code capable of writing
Firmware2 does not mean the X half 2.00 update normally overwrites it.

## What this does and does not buy us

The sequence is consistent with a power-loss-safe A/B design:

```text
known Firmware2 retained
        |
invalidate Firmware1 checksum
        |
write and verify Firmware1
        |
success -> restore Firmware1 checksum
failure -> leave Firmware1 invalid
```

However, the missing arrow is decisive:

```text
reset -> validate Firmware1 -> if invalid, automatically boot Firmware2
```

We have not observed that selector. Firmware2 might be automatically selected,
selected only under a service condition, or used by a recovery stage that has
other prerequisites. Therefore it is a promising protection mechanism, not a
permission to flash experimental firmware.

## Safest next proof

The next useful artifact is a read-only dump of the camera's early boot and
firmware-map regions, including both installed firmware headers. From that we
can locate references to checksum offsets `0x42`/`0x44`, recover the selector,
and determine whether selection happens before mutable DRAM parameters and
other updateable state.

Until that dump exists, a non-destructive alternative is to identify a boot
log or documented storage API that reports which firmware index was selected.
We should not test fallback by corrupting Firmware1.

## Reproducibility

- All runtime addresses above are in the extracted card updater, linked at
  `0x40000000`; absolute string pointers confirm this. [`card.py`](card.py)
  provides address-aware read-only disassembly helpers for it.
- REA evidence for the corrected card-updater mapping and relevant strings is
  saved in
  [`output/rea-card-updater-linked-base-evidence.json`](output/rea-card-updater-linked-base-evidence.json).

