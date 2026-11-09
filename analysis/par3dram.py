#!/usr/bin/env python3
"""Decode DRAMPAR3 record scripts (earlier-stage DRAM/PLL init).

Format derived from the parser at 0x3f0000a4 in the SRAM dump:

  +0x00  "DRAM"                (u32 0x4d415244)
  +0x04  "PAR3"                (u32 0x33524150)
  +0x08  u16 payload_len, u16 checksum
  +0x0c  u16 version?, u16 reserved
  +0x10  records, payload_len bytes
  checksum = sum of u16 words over [0x10, 0x0e + payload_len)
             (the payload minus its final 2-byte terminator)

Record = u16 header: bits 15..12 type, bits 11..0 address low bits.
Types:
  0        end (parser returns without error)
  1        set base = 0x10000000 | ((word & 0x1f) << 12)
  2,3      end
  4        u8  write: +2 value                       (3 bytes)
  5        u16 write: +2 value                       (4 bytes)
  6        u32 write: +2 value                       (6 bytes)
  7        array write: +2 ctl (bits7..6 size, bits5..0 count-1), data after
  8        poll u8:  +2 expected, +3 mask             (4 bytes)
  9        poll u16: +2 expected, +4 mask             (6 bytes)
  10       poll u32: +2 expected, +6 mask             (10 bytes)
  11       copy/delay/add, subtype = (word >> 8) & 0xf:
             1 u8 copy:  +2 src ptr, +6 dst ptr        (10 bytes)
             2 u16 copy                              (10 bytes)
             3 u32 copy                              (10 bytes)
             4 delay: u8 count -> 27*count timer ticks (2 bytes)
             5 u8 add:  +2 addr ptr, +6 addend         (7 bytes)
  12..15   no-op padding                                 (2 bytes)

Addresses for types 4-10 are base | (word & 0xfff).
"""

from __future__ import annotations

import struct
import sys
from dataclasses import dataclass, field

MAGIC0 = 0x4D415244  # "DRAM"
MAGIC1 = 0x33524150  # "PAR3"

NAMES = {
    0: "end",
    1: "base",
    2: "end",
    3: "end",
    4: "w8",
    5: "w16",
    6: "w32",
    7: "array",
    8: "poll8",
    9: "poll16",
    10: "poll32",
    11: "copy",
    12: "nop",
    13: "nop",
    14: "nop",
    15: "nop",
}


@dataclass
class Record:
    offset: int
    raw: int
    type: int
    addr: int
    detail: str = ""
    data: bytes = b""


@dataclass
class Script:
    offset: int
    payload_len: int
    checksum: int
    checksum_ok: bool
    words12: tuple[int, int]
    records: list[Record] = field(default_factory=list)
    error: str | None = None
    note: str | None = None


def u16(b: bytes, o: int) -> int:
    return struct.unpack_from("<H", b, o)[0]


def u32(b: bytes, o: int) -> int:
    return struct.unpack_from("<I", b, o)[0]


def checksum(b: bytes, off: int, payload_len: int) -> int:
    """Sum of u16 words over the payload, minus its final 2-byte terminator.

    The parser starts the sum at +0x10 and stops at +0x0e+payload_len,
    so the last record word is excluded. Verified against the boot header.
    """
    end = off + 0x0E + payload_len
    total = 0
    pos = off + 0x10
    while pos < end:
        total = (total + u16(b, pos)) & 0xFFFF
        pos += 2
    return total


def decode(b: bytes, off: int) -> Script:
    if len(b) < off + 0x10:
        raise ValueError("short buffer")
    if u32(b, off) != MAGIC0 or u32(b, off + 4) != MAGIC1:
        raise ValueError(f"no DRAMPAR3 at {off:#x}")
    payload_len = u16(b, off + 8)
    csum = u16(b, off + 10)
    words12 = (u16(b, off + 12), u16(b, off + 14))
    end = off + 0x10 + payload_len
    if end > len(b):
        return Script(off, payload_len, csum, False, words12, error="truncated")
    ok = checksum(b, off, payload_len) == csum
    s = Script(off, payload_len, csum, ok, words12)

    base = 0x10000000
    p = off + 0x10
    while p < end:
        raw = u16(b, p)
        rtype = (raw >> 12) & 0xF
        addr = base | (raw & 0xFFF)
        rec = Record(p - off, raw, rtype, addr)
        s.records.append(rec)
        if rtype == 0 or rtype in (2, 3):
            rec.detail = "end"
            p = end
            break
        elif rtype == 1:
            base = 0x10000000 | ((raw & 0x1F) << 12)
            rec.addr = base
            rec.detail = f"base={base:#010x}"
            p += 2
        elif rtype == 4:
            rec.detail = f"[{addr:#010x}] = {b[p + 2]:#04x}"
            rec.data = b[p : p + 3]
            p += 3
        elif rtype == 5:
            rec.detail = f"[{addr:#010x}] = {u16(b, p + 2):#06x}"
            rec.data = b[p : p + 4]
            p += 4
        elif rtype == 6:
            rec.detail = f"[{addr:#010x}] = {u32(b, p + 2):#010x}"
            rec.data = b[p : p + 6]
            p += 6
        elif rtype == 7:
            ctl = b[p + 2]
            size = (ctl >> 6) & 0x3
            count = (ctl & 0x3F) + 1
            width = {0: 1, 1: 2, 2: 4, 3: 4}[size]
            vals = []
            q = p + 3
            for _ in range(count):
                if width == 1:
                    vals.append(b[q])
                elif width == 2:
                    vals.append(u16(b, q))
                else:
                    vals.append(u32(b, q))
                q += width
            rec.detail = (
                f"array[{addr:#010x}] n={count} width={width} "
                + " ".join(f"{v:#x}" for v in vals[:8])
                + (" ..." if count > 8 else "")
            )
            rec.data = b[p:q]
            p = q
        elif rtype == 8:
            rec.detail = f"poll8 [{addr:#010x}] & {b[p + 3]:#04x} == {b[p + 2]:#04x}"
            rec.data = b[p : p + 4]
            p += 4
        elif rtype == 9:
            rec.detail = (
                f"poll16 [{addr:#010x}] & {u16(b, p + 4):#06x} == {u16(b, p + 2):#06x}"
            )
            rec.data = b[p : p + 6]
            p += 6
        elif rtype == 10:
            rec.detail = (
                f"poll32 [{addr:#010x}] & {u32(b, p + 6):#010x} == {u32(b, p + 2):#010x}"
            )
            rec.data = b[p : p + 10]
            p += 10
        elif rtype == 11:
            sub = (raw >> 8) & 0xF
            if sub == 4:
                rec.detail = f"delay {b[p + 1]} * 27 ticks"
                rec.data = b[p : p + 2]
                p += 2
            elif sub == 5:
                rec.detail = f"u8 [{u32(b, p + 2):#010x}] += {b[p + 6]:#04x}"
                rec.data = b[p : p + 7]
                p += 7
            elif sub in (1, 2, 3):
                width = {1: 1, 2: 2, 3: 4}[sub]
                rec.detail = (
                    f"copy{width * 8} {u32(b, p + 2):#010x} -> {u32(b, p + 6):#010x}"
                )
                rec.data = b[p : p + 10]
                p += 10
            else:
                rec.detail = f"copy subtype {sub} (unhandled)"
                p += 2
        else:
            rec.detail = "nop"
            p += 2
    if p != end:
        if p > end and p - end <= 2:
            s.note = (
                f"last record crosses payload end by {p - end} byte(s); "
                "the firmware parser reads into the next header"
            )
        else:
            s.error = f"ended at {p - off:#x}, expected {end - off:#x}"
    return s


def find_scripts(b: bytes) -> list[int]:
    hits = []
    start = 0
    while True:
        i = b.find(b"DRAMPAR3", start)
        if i < 0:
            return hits
        hits.append(i)
        start = i + 1


def main() -> None:
    path = sys.argv[1]
    b = open(path, "rb").read()
    for off in find_scripts(b):
        try:
            s = decode(b, off)
        except Exception as exc:  # noqa: BLE001
            print(f"{off:#x}: not a script: {exc}")
            continue
        print(
            f"\n=== {path} @ {off:#x} len={s.payload_len:#x} "
            f"checksum={s.checksum:#06x} ok={s.checksum_ok} "
            f"words12={s.words12} records={len(s.records)}"
        )
        if not s.checksum_ok:
            print("    checksum mismatch: likely a literal string, not a script")
        if s.error:
            print(f"    ERROR: {s.error}")
        if s.note:
            print(f"    NOTE: {s.note}")
        for r in s.records:
            print(f"  +{r.offset:04x} {r.type:2d} {NAMES[r.type]:7s} {r.detail}")


if __name__ == "__main__":
    main()
