"""Read-only helpers for an extracted ARM image; addresses are runtime addresses.

Pass ``--image PATH`` before the command to inspect a historical main image.
Without it, the helper keeps its original behavior and reads firmware-main.bin.
"""
from pathlib import Path
import re, struct, sys, subprocess

ROOT = Path(__file__).resolve().parent.parent
ARGS = sys.argv[1:]
if ARGS[:1] == ['--image']:
    if len(ARGS) < 3:
        raise SystemExit('usage: fw.py [--image PATH] COMMAND [ARGS...]')
    IMAGE = Path(ARGS[1]).resolve()
    ARGS = ARGS[2:]
else:
    IMAGE = ROOT / 'firmware-main.bin'
DATA = IMAGE.read_bytes()
BASE = 0x40000000
def word(a): return struct.unpack_from('<I', DATA, a - BASE)[0]
def string(a):
    if not BASE <= a < BASE + len(DATA): return ''
    p=a-BASE; end=DATA.find(b'\0', p, min(p+240,len(DATA)))
    if end<0:return ''
    s=DATA[p:end]
    return repr(s.decode('ascii')) if s and all(32<=c<127 or c in (9,10,13,27) for c in s) else ''
def prolog(a):
    for p in range(a&~3,max(BASE,a-0x10000),-4):
        if word(p)&0xffff4000==0xe92d4000:return p
    return 0

cmd=ARGS[0]
if cmd=='callers':
    targets={int(a,16) for a in ARGS[1:]}
    for p in range(BASE,BASE+min(len(DATA),0xab0000)-4,4):
        w=word(p)
        if w&0x0e000000!=0x0a000000:continue
        off=w&0xffffff
        if off&0x800000:off-=0x1000000
        target=(p+8+off*4)&0xffffffff
        if target in targets:
            print(f'{p:08x} -> {target:08x} {"BL" if w&0x01000000 else "B"} preceding_prologue={prolog(p):08x}')
    for target in targets:
        hits=[f'{BASE+m.start():08x}' for m in re.finditer(re.escape(struct.pack('<I',target)),DATA)]
        print(f'{target:08x} pointer occurrences: {hits}')
elif cmd=='strings':
    pat=re.compile(ARGS[1],re.I)
    for m in re.finditer(rb'[\x20-\x7e\t\r\n]{7,200}\0',DATA):
        s=m.group()[:-1].decode()
        if pat.search(s):
            a=BASE+m.start()
            hits=[f'{BASE+x.start():08x}' for x in re.finditer(re.escape(struct.pack('<I',a)),DATA)]
            print(f'{a:08x} {s!r} pointers={hits}')
elif cmd=='literals':
    start,end=(int(a,16) for a in ARGS[1:3])
    for p in range(start,end,4):
        w=word(p); print(f'{p:08x}: {w:08x} {string(w)}')
elif cmd=='disasm':
    start,end=(int(a,16) for a in ARGS[1:3])
    out=ROOT/'analysis/output'/f'{IMAGE.stem}.elf'
    if not out.exists():
        names=b'\0.text\0.shstrtab\0'; off=256; noff=off+len(DATA); shoff=(noff+len(names)+3)&~3
        h=struct.pack('<16sHHIIIIIHHHHHH',b'\x7fELF\x01\x01\x01'+bytes(9),2,40,1,BASE,0,shoff,0x5000000,52,0,0,40,3,2)
        sh=bytes(40)+struct.pack('<IIIIIIIIII',1,1,6,BASE,off,len(DATA),0,0,4,0)+struct.pack('<IIIIIIIIII',7,3,0,0,noff,len(names),0,0,1,0)
        out.write_bytes(h+bytes(off-len(h))+DATA+names+bytes(shoff-noff-len(names))+sh)
    subprocess.run(['/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/llvm-objdump','-d','--mcpu=cortex-a7',f'--start-address={start}',f'--stop-address={end}',str(out)],check=True)
