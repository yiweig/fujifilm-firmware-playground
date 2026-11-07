"""Read-only SD sector dump via the APITEST shell (Linux, VM only).

Two modes:
  sdr <start> <count> <flags>       -> SD device 2, heap buffer, parsed address
  --rsv <start> <count>             -> SD device 1 reserved blocks into the
                                       fixed staging buffer 0x41130000

The second mode uses `rsvsdrd <start> <count> 0x41130000`, which writes to
the established scratch gap; the buffer is then pulled back through the
loader's 0x04f2 path. Read-only: no SD writes, no storage writes.

Usage:
  python3 xhalf_sd_read_linux.py START COUNT OUT.bin [FLAGS]
  python3 xhalf_sd_read_linux.py --rsv START COUNT OUT.bin

Assumes the APITEST shell bridge is already active.
"""
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as L
from xhalf_console_linux import Console

L.TIMEOUT_MS = 30000
SECTOR = 512
STAGE = 0x41130000


def main():
    if sys.platform != 'linux':
        raise SystemExit('Linux only')
    argv = sys.argv[1:]
    rsv = bool(argv and argv[0] == '--rsv')
    if rsv:
        argv = argv[1:]
    if len(argv) < 3:
        raise SystemExit(__doc__)
    start = int(argv[0], 0)
    count = int(argv[1], 0)
    out = Path(argv[2])
    flags = int(argv[3], 0) if len(argv) > 3 else 0

    loader = L.Loader(L.open_device())
    console = Console(loader)
    try:
        print('--- continue mode: bridge assumed active')
        console.thread.start()
        time.sleep(2)
        since = console.mark()
        if rsv:
            cmd = f'rsvsdrd {start} {count} 0x{STAGE:08x}'
        else:
            cmd = f'sdr {start} {count} {flags}'
        loader.apitest_ctrl(cmd.encode() + b'\n')
        print(f'>>> sent {cmd!r}')
        if console.wait_for(b'cmd>', 90, since) < 0:
            raise SystemExit(f'no prompt after {cmd!r}; partial output: '
                             f'{console.tail(since)[:400]!r}')
        transcript = console.tail(since)
        print(transcript[:800])
        if rsv:
            addr = STAGE
        else:
            m = re.search(r'dram\[0x([0-9a-fA-F]+)\]', transcript)
            if not m:
                raise SystemExit(f'no dram buffer address in output: {transcript[:400]!r}')
            addr = int(m.group(1), 16)
        print(f'--- buffer at 0x{addr:08x}, reading {count * SECTOR} bytes')
        tmp = Path('sd-chunk.bin')
        loader.read_chunked(addr, count * SECTOR, str(tmp), 0x10000)
        data = tmp.read_bytes()
        if len(data) != count * SECTOR:
            raise SystemExit(f'short read: {len(data)} != {count * SECTOR}')
        out.write_bytes(data)
        print(f'wrote {out} {len(data)} bytes head={data[:64].hex()}')
    finally:
        console.stop.set()
        import usb.util
        usb.util.release_interface(loader.dev, L.INTERFACE)
        usb.util.dispose_resources(loader.dev)


if __name__ == '__main__':
    main()
