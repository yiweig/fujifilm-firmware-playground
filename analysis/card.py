"""Read-only helpers for the extracted card updater.

Addresses are runtime addresses.  The updater is linked at 0x40000000; this is
confirmed by its absolute pointers to strings and data in the extracted image.
"""

from pathlib import Path
import re
import struct
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
DATA = (ROOT / "analysis/output/card-updater.bin").read_bytes()
BASE = 0x40000000


def word(address: int) -> int:
    return struct.unpack_from("<I", DATA, address - BASE)[0]


def string(address: int) -> str:
    if not BASE <= address < BASE + len(DATA):
        return ""
    position = address - BASE
    end = DATA.find(b"\0", position, min(position + 240, len(DATA)))
    if end < 0:
        return ""
    value = DATA[position:end]
    if value and all(32 <= byte < 127 or byte in (9, 10, 13, 27) for byte in value):
        return repr(value.decode("ascii"))
    return ""


def prologue(address: int) -> int:
    for candidate in range(address & ~3, max(BASE, address - 0x10000), -4):
        if word(candidate) & 0xFFFF4000 == 0xE92D4000:
            return candidate
    return 0


def ensure_elf() -> Path:
    output = ROOT / "analysis/output/card-updater.elf"
    if output.exists():
        return output
    names = b"\0.text\0.shstrtab\0"
    data_offset = 256
    names_offset = data_offset + len(DATA)
    sections_offset = (names_offset + len(names) + 3) & ~3
    header = struct.pack(
        "<16sHHIIIIIHHHHHH",
        b"\x7fELF\x01\x01\x01" + bytes(9),
        2,
        40,
        1,
        BASE,
        0,
        sections_offset,
        0x5000000,
        52,
        0,
        0,
        40,
        3,
        2,
    )
    sections = bytes(40)
    sections += struct.pack(
        "<IIIIIIIIII", 1, 1, 6, BASE, data_offset, len(DATA), 0, 0, 4, 0
    )
    sections += struct.pack(
        "<IIIIIIIIII", 7, 3, 0, 0, names_offset, len(names), 0, 0, 1, 0
    )
    output.write_bytes(
        header
        + bytes(data_offset - len(header))
        + DATA
        + names
        + bytes(sections_offset - names_offset - len(names))
        + sections
    )
    return output


command = sys.argv[1]
if command == "callers":
    targets = {int(value, 16) for value in sys.argv[2:]}
    for address in range(BASE, BASE + len(DATA) - 4, 4):
        instruction = word(address)
        if instruction & 0x0E000000 != 0x0A000000:
            continue
        offset = instruction & 0xFFFFFF
        if offset & 0x800000:
            offset -= 0x1000000
        target = (address + 8 + offset * 4) & 0xFFFFFFFF
        if target in targets:
            print(
                f"{address:08x} -> {target:08x} "
                f"{'BL' if instruction & 0x01000000 else 'B'} "
                f"preceding_prologue={prologue(address):08x}"
            )
elif command == "strings":
    pattern = re.compile(sys.argv[2], re.I)
    for match in re.finditer(rb"[\x20-\x7e\t\r\n]{7,200}\0", DATA):
        value = match.group()[:-1].decode()
        if pattern.search(value):
            address = BASE + match.start()
            pointer = struct.pack("<I", address)
            hits = [f"{BASE + hit.start():08x}" for hit in re.finditer(re.escape(pointer), DATA)]
            print(f"{address:08x} {value!r} pointers={hits}")
elif command == "literals":
    start, end = (int(value, 16) for value in sys.argv[2:4])
    for address in range(start, end, 4):
        value = word(address)
        print(f"{address:08x}: {value:08x} {string(value)}")
elif command == "disasm":
    start, end = (int(value, 16) for value in sys.argv[2:4])
    subprocess.run(
        [
            "/Applications/Xcode.app/Contents/Developer/Toolchains/"
            "XcodeDefault.xctoolchain/usr/bin/llvm-objdump",
            "-d",
            "--mcpu=cortex-a7",
            f"--start-address={start}",
            f"--stop-address={end}",
            str(ensure_elf()),
        ],
        check=True,
    )
else:
    raise SystemExit(f"unknown command: {command}")
