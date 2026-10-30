#!/usr/bin/env python3
"""Mine statically resolved ARM memory accesses from the extracted firmware.

This is intentionally conservative. It follows PC-relative literal loads and
MOVW/MOVT constant construction for a short instruction window, then records
only constants that are actually used as memory bases by LDR/STR-family
instructions. It does not execute the firmware or claim that an address is
MMIO merely because it is accessed.
"""

from __future__ import annotations

import argparse
import collections
import csv
import re
import struct
import subprocess
from dataclasses import dataclass
from pathlib import Path


DEFAULT_OBJDUMP = Path(
    "/Applications/Xcode.app/Contents/Developer/Toolchains/"
    "XcodeDefault.xctoolchain/usr/bin/llvm-objdump"
)

LINE_RE = re.compile(
    r"^\s*([0-9a-fA-F]+):\s+[0-9a-fA-F]+\s+([a-zA-Z][a-zA-Z0-9.]*)\s*(.*?)\s*$"
)
LITERAL_RE = re.compile(
    r"^(r(?:1[0-5]|[0-9])),\s*\[pc,[^]]*\].*?@\s*(0x[0-9a-fA-F]+)"
)
MOV_RE = re.compile(r"^(r(?:1[0-5]|[0-9])),\s*#(-?(?:0x[0-9a-fA-F]+|[0-9]+))")
MEM_BASE_RE = re.compile(r"\[(r(?:1[0-5]|[0-9]))(?:,\s*#(-?(?:0x[0-9a-fA-F]+|[0-9]+)))?")


@dataclass
class KnownValue:
    value: int
    source_kind: str
    source_address: int
    ttl: int = 20


def parse_int(value: str) -> int:
    return int(value, 0)


def width_for(mnemonic: str) -> int | None:
    op = mnemonic.lower().split(".", 1)[0]
    if not (op.startswith("ldr") or op.startswith("str")):
        return None
    if op.endswith("b") or op.endswith("sb"):
        return 1
    if op.endswith("h") or op.endswith("sh"):
        return 2
    if op.endswith("d"):
        return 8
    return 4


def region_for(address: int, base: int, image_end: int) -> str:
    if base <= address < image_end:
        return "loaded-image"
    if address < base:
        return "below-image"
    return "above-image"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--firmware", type=Path, default=Path("firmware-main.bin"))
    parser.add_argument(
        "--elf", type=Path, default=Path("analysis/output/firmware-main.elf")
    )
    parser.add_argument("--base", type=parse_int, default=0x40000000)
    parser.add_argument("--objdump", type=Path, default=DEFAULT_OBJDUMP)
    parser.add_argument("--output-dir", type=Path, default=Path("analysis/output"))
    args = parser.parse_args()

    data = args.firmware.read_bytes()
    image_end = args.base + len(data)
    known: dict[str, KnownValue] = {}
    rows: list[dict[str, object]] = []

    command = [str(args.objdump), "-d", "--mcpu=cortex-a7", str(args.elf)]
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, text=True)
    assert proc.stdout is not None

    for raw_line in proc.stdout:
        match = LINE_RE.match(raw_line)
        if not match:
            continue
        instruction_address = int(match.group(1), 16)
        mnemonic = match.group(2).lower()
        operands = match.group(3)

        for register in list(known):
            known[register].ttl -= 1
            if known[register].ttl <= 0:
                del known[register]

        literal = LITERAL_RE.match(operands) if mnemonic == "ldr" else None
        if literal:
            register = literal.group(1)
            literal_address = int(literal.group(2), 16)
            offset = literal_address - args.base
            if 0 <= offset <= len(data) - 4:
                value = struct.unpack_from("<I", data, offset)[0]
                known[register] = KnownValue(value, "literal", instruction_address)
            continue

        move = MOV_RE.match(operands)
        if move and mnemonic in {"mov", "movw", "movt"}:
            register = move.group(1)
            immediate = parse_int(move.group(2)) & 0xFFFFFFFF
            if mnemonic == "movt":
                prior = known.get(register)
                low = prior.value & 0xFFFF if prior else 0
                known[register] = KnownValue(
                    ((immediate & 0xFFFF) << 16) | low,
                    "movw/movt" if prior else "movt",
                    prior.source_address if prior else instruction_address,
                )
            else:
                known[register] = KnownValue(immediate, mnemonic, instruction_address)
            continue

        width = width_for(mnemonic)
        memory = MEM_BASE_RE.search(operands) if width else None
        if memory:
            base_register = memory.group(1)
            value = known.get(base_register)
            if value and base_register != "r15":
                displacement = parse_int(memory.group(2)) if memory.group(2) else 0
                effective = (value.value + displacement) & 0xFFFFFFFF
                access = "read" if mnemonic.startswith("ldr") else "write"
                rows.append(
                    {
                        "address": f"0x{effective:08x}",
                        "page": f"0x{effective & ~0xfff:08x}",
                        "access": access,
                        "width": width,
                        "instruction": f"0x{instruction_address:08x}",
                        "base_value": f"0x{value.value:08x}",
                        "base_register": base_register,
                        "source_kind": value.source_kind,
                        "source_instruction": f"0x{value.source_address:08x}",
                        "region": region_for(effective, args.base, image_end),
                        "mnemonic": mnemonic,
                    }
                )

        if mnemonic in {"bl", "blx"}:
            for register in ("r0", "r1", "r2", "r3", "r12"):
                known.pop(register, None)

    return_code = proc.wait()
    if return_code:
        raise SystemExit(f"llvm-objdump exited with status {return_code}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    access_path = args.output_dir / "static-memory-accesses.tsv"
    fields = [
        "address",
        "page",
        "access",
        "width",
        "instruction",
        "base_value",
        "base_register",
        "source_kind",
        "source_instruction",
        "region",
        "mnemonic",
    ]
    with access_path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fields, dialect="excel-tab")
        writer.writeheader()
        writer.writerows(rows)

    grouped: dict[tuple[str, str], list[dict[str, object]]] = collections.defaultdict(list)
    for row in rows:
        grouped[(str(row["page"]), str(row["region"]))].append(row)

    page_path = args.output_dir / "static-memory-pages.tsv"
    page_fields = [
        "page",
        "region",
        "accesses",
        "reads",
        "writes",
        "unique_addresses",
        "min_address",
        "max_address",
        "sample_addresses",
        "sample_instructions",
    ]
    with page_path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, page_fields, dialect="excel-tab")
        writer.writeheader()
        for (page, region), items in sorted(grouped.items()):
            addresses = sorted({int(str(item["address"]), 16) for item in items})
            instructions = sorted({str(item["instruction"]) for item in items})
            writer.writerow(
                {
                    "page": page,
                    "region": region,
                    "accesses": len(items),
                    "reads": sum(item["access"] == "read" for item in items),
                    "writes": sum(item["access"] == "write" for item in items),
                    "unique_addresses": len(addresses),
                    "min_address": f"0x{addresses[0]:08x}",
                    "max_address": f"0x{addresses[-1]:08x}",
                    "sample_addresses": ",".join(f"0x{x:08x}" for x in addresses[:8]),
                    "sample_instructions": ",".join(instructions[:8]),
                }
            )

    print(f"image: 0x{args.base:08x}-0x{image_end - 1:08x} ({len(data)} bytes)")
    print(f"recorded accesses: {len(rows)}")
    print(f"pages: {len(grouped)}")
    print(access_path)
    print(page_path)


if __name__ == "__main__":
    main()
