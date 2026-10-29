"""Append the raw literal values/strings to Ghidra's unanalysed Ram references."""
from pathlib import Path
import re, struct, sys

data = (Path(__file__).resolve().parent.parent / 'firmware-main.bin').read_bytes()
for line in Path(sys.argv[1]).read_text().splitlines():
    notes = []
    for name, addr in re.findall(r'(\b\w*Ram([0-9a-f]{8})\b)', line):
        off = int(addr, 16) - 0x40000000
        if not 0 <= off <= len(data) - 4:
            continue
        value, = struct.unpack_from('<I', data, off)
        note = f'{name}=0x{value:08x}'
        p = value - 0x40000000
        if 0 <= p < len(data):
            end = data.find(b'\0', p, min(p+240,len(data)))
            s = data[p:end] if end >= 0 else b''
            if s and all(32 <= c < 127 or c in (9,10,13,27) for c in s):
                note += ' ' + repr(s.decode('ascii'))
        notes.append(note)
    print(line + (' // ' + '; '.join(dict.fromkeys(notes)) if notes else ''))
