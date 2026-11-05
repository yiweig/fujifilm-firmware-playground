"""Dedicated APITEST shell console for the X half loader (Linux, VM only).

One process owns interface 5: an EP 0x84 reader thread decodes the
4-byte console records (00 01 01 <char>) into a timestamped log file,
and commands are fed through the APITEST queue with 30 s timeouts.
No 0x04f2 bulk reads are issued after the shell wakes (they contend
with the console machinery).

Usage:
  python3 xhalf_console_linux.py 'zz9'              # one command, 25 s listen
  python3 xhalf_console_linux.py 'rcaraw' 'pvraw'   # sequential commands

Activation follows the live-qualified sequence in
analysis/apitest-shell-live.md: DRAM-poke mode 2, [0x00] init(1) case
with the drain already running, poke mode 2 again. Exit is camera
power-off; this client deliberately does not teardown on exit.
"""
import struct
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import xhalf_loader_linux as L

L.TIMEOUT_MS = 30000  # post-activation transfers are slow
LOG = Path('console.log')


class Console:
    def __init__(self, loader: L.Loader):
        self.loader = loader
        self.dev = loader.dev
        self.text = bytearray()
        self.cond = threading.Condition()
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._reader, daemon=True)

    def _reader(self):
        while not self.stop.is_set():
            try:
                d = bytes(self.dev.read(0x84, 512, timeout=500))
            except Exception:
                continue
            chars = bytes(d[i + 3] for i in range(0, len(d) - 3, 4))
            if chars:
                with self.cond:
                    self.text += chars
                    self.cond.notify_all()
                with LOG.open('ab') as fh:
                    fh.write(chars)

    def wait_for(self, needle: bytes, timeout_s: float, since: int = 0) -> int:
        deadline = time.time() + timeout_s
        with self.cond:
            while True:
                idx = self.text.find(needle, since)
                if idx >= 0:
                    return idx
                remaining = deadline - time.time()
                if remaining <= 0:
                    return -1
                self.cond.wait(remaining)

    def mark(self) -> int:
        return len(self.text)

    def tail(self, since: int) -> str:
        return bytes(self.text[since:]).decode('ascii', 'replace')


def main():
    if sys.platform != 'linux':
        raise SystemExit('Linux only')
    commands = sys.argv[1:]
    cont = bool(commands and commands[0] == '--continue')
    if cont:
        commands = commands[1:]
    if not commands:
        raise SystemExit('give at least one shell command to send')
    LOG.write_bytes(b'')
    loader = L.Loader(L.open_device())
    console = Console(loader)
    try:
        if cont:
            print('--- continue mode: bridge assumed active')
            console.thread.start()
            time.sleep(2)
        else:
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
                print('WARNING: no cmd> prompt seen in 25 s; continuing anyway')
            else:
                print('--- cmd> prompt observed')
        for cmd in commands:
            since = console.mark()
            loader.apitest_ctrl(cmd.encode() + b'\n')
            print(f'>>> sent {cmd!r}')
            idx = console.wait_for(b'cmd>', 30, since)
            transcript = console.tail(since)
            print(f'--- output for {cmd!r} ({len(transcript)} chars):')
            print(transcript[:4000])
            if idx < 0:
                print('WARNING: no following prompt within 30 s')
    finally:
        console.stop.set()
        import usb.util
        usb.util.release_interface(loader.dev, L.INTERFACE)
        usb.util.dispose_resources(loader.dev)
        print(f'console log saved to {LOG} ({LOG.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
