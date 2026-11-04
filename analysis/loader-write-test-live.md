# Loader write test live: scratch RAM write/readback passes

Date: 2026-11-04. Loader entry via the established
`EnableTest.txt` + MOVIE/flash-ON/PLAY-hold route. All traffic from
`analysis/xhalf_loader_linux.py` inside the UTM/Linux passthrough VM.
The write capability is a new client mode; it did not exist during the
read-only sessions.

## Guardrails in the client

- Only the `0x04f5` host-to-memory branch is implemented (payload
  `length,address` LE, then bulk OUT on EP `0x03`). No `0x04f3`
  parameter-processing branch, no upload/run (`0x04f7`), no other index.
- Host-side write allowlist is exactly the 2.4 MiB zero gap from the
  [DRAM dump mining](dram-dump-mining.md)
  (`0x4106a198..0x412c26d8`), 32-byte-aligned, max 256 bytes per write.
  MMIO, SRAM, low memory, the image, and all runtime structures are
  refused by the client before any USB traffic.
- The test address was `0x41100000`, deep inside that gap.

## Sequence and result (single loader session, usbmon-captured)

1. Boot query `0x70` (zero size): method byte 2, as before.
2. Read `0x41100000` (32 B): all zeros.
3. Write 32-byte pattern `a5acb3ba…70777e` (`(i*7+0xa5)&0xff`) via one
   control setup + one bulk-OUT transfer.
4. Read back: byte-identical.
5. Hold 20 s with the loader idle; re-read: byte-identical.

usbmon shows the expected control/bulk-IN/bulk-OUT exchange and nothing
else. The pattern lives only in DRAM; the session ended by power-off, so
it is gone by construction. (Confirming zeros at this address on a future
entry is a cheap volatility re-check, not yet done.)

## Meaning

- The read **and** write primitives of the target-0 receiver are now
  qualified on this unit, restricted to aligned DRAM and, for writes,
  to one empirically stable scratch address range.
- Combined with the [full DRAM dump](loader-memory-read-live.md), the
  environment for a first payload is mapped. The remaining gap before
  any execution attempt is a payload design that either coexists with
  the running loader environment or uses the receiver's own upload/run
  handoff (`0x04f7`) with its copy stub — whose SRAM/low-helper calls
  (`0x00000b34`) still cannot be read through the `0x04f2` path.
- No persistent state was touched: the pattern was DRAM-only, the card
  markers were removed afterwards, and the entry's factory-record effect
  was already proven byte-identical on repeat entries.

## Next candidates

1. Execute something trivial without the destructive handoff: options
   include locating a callable function-pointer slot in the loader/USB
   state that can be aimed at a small scratch-resident routine and later
   restored, or accepting the `0x04f7` handoff with a payload that
   immediately returns/loops safely. Both need design work offline
   before the next camera session.
2. SRAM contents: still unreadable via `0x04f2` (DMA source restriction).
   Possible detours: the 116 SRAM pointers in the DRAM dump, USB
   descriptor staging buffers, or the APITEST bridge (`0xfa`) shell if
   it can be reached inside this same mode-4 session.
