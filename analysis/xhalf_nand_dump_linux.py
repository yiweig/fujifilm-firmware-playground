"""Read-only internal-storage dump via the APITEST shell (Linux, VM only).

Activates the shell like xhalf_console_linux.py, then loops:
  shell: nandpr <sect> <nrSect> 0x41130000   (ECC path, into DRAM staging)
  host:  read staging back via the loader 0x04f2 path and append to OUT

Qualification: analysis/storage-dump-qualification.md. Read commands
only; the staging buffer is the established scratch gap. Nothing is
written to storage.

Usage:
  python3 xhalf_nand_dump_linux.py START_SECT TOTAL_SECTORS OUT.bin [CHUNK_SECTORS]
"""
import hashlib
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as L
from xhalf_console_linux import Console

L.TIMEOUT_MS = 30000
STAGE = 0x41130000
SECTOR = 512


def main():
    if sys.platform != 'linux':
        raise SystemExit('Linux only')
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    argv = sys.argv[1:]
    cont = bool(argv and argv[0] == '--continue')
    if cont:
        argv = argv[1:]
    start_sect = int(argv[0], 0)
    total = int(argv[1], 0)
    out = Path(argv[2])
    chunk_sectors = int(argv[3], 0) if len(argv) > 3 else 1024
    chunk_bytes = chunk_sectors * SECTOR
    if chunk_bytes > 0x80000:
        raise SystemExit('chunk staging capped at 512 KiB')

    loader = L.Loader(L.open_device())
    console = Console(loader)
    try:
        if cont:
            print('--- continue mode: bridge assumed active')
            console.thread.start()
            time.sleep(2)
            done_prompt = True
        else:
            done_prompt = False
            # Activation sequence (apitest-shell-live.md): teardown, poke
            # mode 2, drain running, [0x00] init(1), poke mode 2 again.
            print('--- baseline'); loader.apitest_state()
            try:
                loader.apitest_ctrl(b'\x00', wvalue=2)
                print('--- teardown sent')
            except Exception as exc:
                print(f'--- teardown skipped ({type(exc).__name__})')
            loader._apitest_set_mode2()
            print('--- mode=2 poked'); loader.apitest_state()
            console.thread.start()
            loader.apitest_ctrl(b'\x00')
            print('--- [0x00] init(1) sent')
            loader._apitest_set_mode2()
            print('--- mode=2 poked again')
            if console.wait_for(b'cmd>', 25) < 0:
                raise SystemExit('no cmd> prompt; aborting before any storage command')

        done = 0
        out.write_bytes(b'')
        while done < total:
            n = min(chunk_sectors, total - done)
            sect = start_sect + done
            cmd = f'nandpr {sect} {n} 0x{STAGE:08x}'
            since = console.mark()
            loader.apitest_ctrl(cmd.encode() + b'\n')
            if console.wait_for(b'cmd>', 60, since) < 0:
                raise SystemExit(f'no prompt after {cmd!r} at sector {sect}; '
                                 f'{done}/{total} sectors already saved to {out}')
            transcript = console.tail(since)
            if 'Rd sect' not in transcript:
                raise SystemExit(f'unexpected reply to {cmd!r}: {transcript[:400]!r}')
            tmp = Path('nand-chunk.bin')
            loader.read_chunked(STAGE, n * SECTOR, str(tmp), 0x10000)
            data = tmp.read_bytes()
            if len(data) != n * SECTOR:
                raise SystemExit(f'short staging read at sector {sect}: {len(data)}')
            with out.open('ab') as fh:
                fh.write(data)
            done += n
            print(f'[{done}/{total}] sector {sect} n={n} '
                  f'chunk sha256 {hashlib.sha256(data).hexdigest()[:16]}', flush=True)
        blob = out.read_bytes()
        print(f'dump complete: {out} {len(blob)} bytes sha256 {hashlib.sha256(blob).hexdigest()}')
    finally:
        console.stop.set()
        import usb.util
        usb.util.release_interface(loader.dev, L.INTERFACE)
        usb.util.dispose_resources(loader.dev)


if __name__ == '__main__':
    main()
