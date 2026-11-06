"""Restricted Linux/pyusb client for the X half target-0 loader (2aad:6500).

Linux only; run inside the passthrough VM. The client covers the proven
read-only loader requests, the scratch-only write path, the
emulator-qualified trampolines, and the RAM-push/direct-handoff
experiments. Address/length are allowlisted host-side; the firmware
parser itself applies no bounds, so the allowlist is the safety boundary.

Read-only modes: --bootquery, --read, --read-chunked, --stage-copy,
--stage-bootinfo, --updater-probe, --apitest-*.
Write/execute modes: --write-test (scratch), --hijack-test (handler slots),
--set-mailbox (boot-info mailbox), --ram-push (payload handoff),
--direct-handoff (helper call). Read the linked reports before using these
live; they replace the running environment or patch handler slots.

Receiver basis: analysis/xhalf-usb-ram-receiver.md and
analysis/loader-memory-read-boundary.md (2.00 main image).
Setup packet: bmRequestType, bRequest, wValue, wIndex, wLength.
"""
import argparse
import hashlib
import struct
import sys
from pathlib import Path

VID, PID = 0x2AAD, 0x6500
INTERFACE = 5
EP_IN = 0x82
EP_OUT = 0x03
TIMEOUT_MS = 3000

# Host-side write allowlist: only the 2.4 MiB zero gap found in the
# 2026-11-04 loader-mode DRAM dump (analysis/dram-dump-mining.md).
WRITE_ALLOWED = (0x4106A198, 0x412C26D8)
WRITE_MAX = 256

# Host-side read allowlist: (start, end) CPU ranges with a reason to read.
ALLOWED = [
    (0x00000000, 0x00010000, 'low memory / earlier-stage helper area'),
    (0x3F000000, 0x3F010000, 'SRAM'),
    (0x40000000, 0x60000000, 'main image, runtime RAM, and heap (loader arena seen at 0x45cc2cc0)'),
]
MAX_LEN = 0x10000


def check_range(addr: int, length: int):
    if not (1 <= length <= MAX_LEN):
        raise SystemExit(f'length {length} outside 1..{MAX_LEN}')
    for start, end, _ in ALLOWED:
        if start <= addr and addr + length <= end:
            return
    raise SystemExit(f'address/length 0x{addr:08x}+0x{length:x} outside allowlist')


class Loader:
    def __init__(self, dev):
        self.dev = dev

    def bootquery(self) -> bytes:
        data = bytes(self.dev.ctrl_transfer(0xC0, 0x70, 0, 0, 5, timeout=TIMEOUT_MS))
        if len(data) != 5:
            raise SystemExit(f'bootquery: expected 5 bytes, got {len(data)}: {data.hex()}')
        return data

    def read_mem(self, addr: int, length: int) -> bytes:
        check_range(addr, length)
        payload = struct.pack('<II', addr, length)
        # Pre-submit the bulk read before the setup: live failures suggested
        # the device-side sender may only briefly wait for a pending host URB.
        import threading
        result = {}

        def reader():
            try:
                out = bytearray()
                while len(out) < length:
                    chunk = bytes(self.dev.read(EP_IN, length - len(out), timeout=TIMEOUT_MS))
                    if not chunk:
                        break
                    out += chunk
                result['data'] = bytes(out)
            except Exception as exc:  # surfaced in main thread
                result['error'] = exc

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        import time
        time.sleep(0.5)  # let the URB reach the device first
        sent = self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F2, payload, timeout=TIMEOUT_MS)
        if sent != len(payload):
            raise SystemExit(f'read setup: short control write {sent}')
        thread.join(TIMEOUT_MS / 1000 + 2)
        if 'error' in result:
            raise result['error']
        data = result.get('data', b'')
        if len(data) != length:
            raise SystemExit(f'bulk IN stalled at {len(data)}/{length} bytes')
        return data

    def write_mem(self, addr: int, data: bytes):
        """0x04f5 host-to-memory: payload (length,address), then bulk OUT."""
        start, end = WRITE_ALLOWED
        if not (start <= addr and addr + len(data) <= end):
            raise SystemExit(f'write 0x{addr:08x}+0x{len(data):x} outside scratch allowlist')
        if addr % 32 or not (1 <= len(data) <= WRITE_MAX):
            raise SystemExit('write must be 32-byte aligned, 1..256 bytes')
        payload = struct.pack('<II', len(data), addr)
        sent = self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, payload, timeout=TIMEOUT_MS)
        if sent != len(payload):
            raise SystemExit(f'write setup: short control write {sent}')
        written = self.dev.write(EP_OUT, data, timeout=TIMEOUT_MS)
        if written != len(data):
            raise SystemExit(f'bulk OUT stalled at {written}/{len(data)} bytes')

    TRAMP_ADDR = 0x41100040
    COUNTER_ADDR = 0x41100100
    HANDLER = 0x401E1280
    # Slot blocks (32B, aligned) containing the target-0 handler words for
    # categories +0x2c and +0x54: (block_addr, word_offset_in_block).
    SLOT_BLOCKS = [(0x40FC7BA0, 4), (0x40FC7BC0, 12)]
    TRAMP_WORDS = [0xE92D0003, 0xE3000100, 0xE3440110, 0xE5901000, 0xE2811001,
                   0xE5801000, 0xE8BD0003, 0xE301C280, 0xE344C01E, 0xE12FFF1C]

    APITEST_STATE = 0x40FC8270

    def _write_block32(self, block: int, data: bytes):
        allowed = [b for b, _ in self.SLOT_BLOCKS] + [0x40FC8280]
        if block not in allowed or len(data) != 32:
            raise SystemExit('block write outside allowlist')
        payload = struct.pack('<II', 32, block)
        sent = self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, payload, timeout=TIMEOUT_MS)
        if sent != len(payload):
            raise SystemExit('slot setup: short control write')
        if self.dev.write(EP_OUT, data, timeout=TIMEOUT_MS) != 32:
            raise SystemExit('slot write: short bulk OUT')

    TRAMP2_HEX = ('0f002de988009fe5001090e5011081e2001080e57c009fe5001090e5000051e3'
                  '1800001a70209fe5002092e5010052e30200000a020052e30600000a110000ea'
                  '0110a0e3001080e53f04a0e34c109fe50128a0e3040000ea0110a0e3001080e5'
                  '0b0ca0e338109fe5012ca0e3043090e4043081e4042052e2fbffff1a14009fe5'
                  '0210a0e3001080e50f00bde814c09fe51cff2fe1000110410401104108011041'
                  '000011410010124180121e40')
    SRAM_STAGE = 0x41110000
    LOW_STAGE = 0x41121000

    def _ctrl(self):
        return bytearray(self.read_mem(self.COUNTER_ADDR, 64))

    def _set_cmd(self, cmd):
        block = self._ctrl()
        struct.pack_into('<I', block, 4, 0)       # DONE = 0
        struct.pack_into('<I', block, 8, cmd)     # CMD
        self.write_mem(self.COUNTER_ADDR, bytes(block))

    def _wait_done(self, timeout_s=20):
        import time
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            done = struct.unpack_from('<I', self._ctrl(), 4)[0]
            if done == 2:
                return True
            time.sleep(0.5)
        return False

    def apitest_state(self):
        data = self.read_mem(self.APITEST_STATE, 32)
        words = struct.unpack('<8I', data)
        names = ['queue', 'interpreter', 'buf8', 'flag_c', 'buf10', 'flag14', 'mode18', 'count1c']
        print('apitest state:', {n: f'0x{v:08x}' for n, v in zip(names, words) if v or n in ('mode18',)})
        return words

    def _apitest_set_mode2(self):
        block_addr = 0x40FC8280
        data = bytearray(self.read_mem(block_addr, 32))
        struct.pack_into('<I', data, 0x40FC8288 - block_addr, 2)
        payload = struct.pack('<II', 32, block_addr)
        if self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, payload, timeout=TIMEOUT_MS) != 8:
            raise SystemExit('state setup: short control write')
        if self.dev.write(EP_OUT, bytes(data), timeout=TIMEOUT_MS) != 32:
            raise SystemExit('state write: short bulk OUT')

    def apitest_ctrl(self, payload: bytes, wvalue: int = 0):
        sent = self.dev.ctrl_transfer(0x40, 0xFA, wvalue, 0, payload, timeout=TIMEOUT_MS)
        if sent != len(payload):
            raise SystemExit(f'apitest: short control write {sent}/{len(payload)}')

    def _start_drain(self):
        """Continuously read bulk-IN/interrupt so device output can flow."""
        import threading
        packets = []
        stop = threading.Event()

        def run():
            while not stop.is_set():
                for ep in (EP_IN, 0x84):
                    try:
                        d = bytes(self.dev.read(ep, 512, timeout=300))
                        if d:
                            packets.append((ep, d))
                            print(f'  [drain] EP{ep:#x} len={len(d)}: {d[:80]!r}', flush=True)
                    except Exception:
                        pass

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        return stop, packets

    LOG_EP = 0x84

    @staticmethod
    def _decode_log_records(data: bytes) -> str:
        """Best-effort decode of the firmware's 4-byte log records."""
        chars = []
        for i in range(0, len(data) - 3, 4):
            rec = data[i:i + 4]
            if rec[0] == 0 and rec[1] == 1 and rec[2] == 1:
                b = rec[3]
                chars.append(chr(b) if 32 <= b < 127 or b in (10, 13) else '.')
        return ''.join(chars)

    def _start_log_drain(self):
        """Continuously drain EP 0x84 (firmware diagnostic log) during a
        push. Reads only the interrupt endpoint, never the payload bulk
        endpoint, so it cannot consume download/read-back data."""
        import threading
        packets = []
        stop = threading.Event()

        def run():
            while not stop.is_set():
                try:
                    d = bytes(self.dev.read(self.LOG_EP, 512, timeout=300))
                    if d:
                        packets.append(d)
                        text = self._decode_log_records(d)
                        print(f'  [log] {d.hex()}' + (f'  {text!r}' if text else ''),
                              flush=True)
                except Exception:
                    pass

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        return stop, packets

    def apitest_init(self):
        # Correct order learned live: init(0)-first makes init(1) early-exit
        # before installing the shell input/output hooks. So: teardown (if
        # reachable), poke mode 2, [0x00] -> full init(1), poke mode 2.
        print('--- baseline'); self.apitest_state()
        try:
            self.apitest_ctrl(b'\x00', wvalue=2)
            print('--- teardown sent')
        except Exception as exc:
            print(f'--- teardown not reachable ({type(exc).__name__}); continuing')
        self._apitest_set_mode2()
        print('--- mode forced to 2 (DRAM poke)'); self.apitest_state()
        stop, packets = self._start_drain()
        try:
            self.apitest_ctrl(b'\x00')
            print('--- after [0x00] init(1) case'); self.apitest_state()
            self._apitest_set_mode2()
            print('--- mode forced to 2 again'); self.apitest_state()
            import time
            time.sleep(3)
        finally:
            stop.set()
        print(f'drain captured {len(packets)} packet(s) during init')

    def apitest_send(self, text: str):
        stop, packets = self._start_drain()
        try:
            data = text.encode() + b'\n'
            self.apitest_ctrl(data)
            print(f'sent {len(data)} bytes: {data!r}')
            import time
            time.sleep(4)
        finally:
            stop.set()
        print(f'drain captured {len(packets)} packet(s) after send')

    def apitest_output(self, length=0x1000):
        words = self.apitest_state()
        buf = words[4]  # state+0x10: 16 KiB output buffer from init
        if not buf:
            raise SystemExit('no output buffer allocated yet')
        data = self.read_mem(buf, length)
        Path('apitest-output.bin').write_bytes(data)
        print(f'output buffer 0x{buf:08x} first {length} bytes saved to apitest-output.bin')
        text = ''.join(chr(b) if 32 <= b < 127 or b in (10, 13) else '.' for b in data)
        print(text[:2000])

    # Trampoline v3: parameterized copier. Control block at COUNTER_ADDR:
    # +0 counter, +4 DONE, +8 CMD (nonzero=run), +0xc SRC, +0x10 DST, +0x14 LEN.
    # Emulator-qualified: scripts/ReproduceTrampoline3.java (TRAMPOLINE3 PASS).
    TRAMP3_HEX = ('0f002de958009fe5001090e5011081e2001080e5041090e5000051e3'
                  '0d00001a081090e5000051e30a00000a0110a0e3041080e50c1090e5'
                  '102090e5143090e504c091e404c082e4043053e2fbffff1a0210a0e3'
                  '041080e50f00bde804c09fe51cff2fe10001104180121e40')
    BOOTINFO_STAGE = 0x41122000
    GATE_STAGE = 0x41123000
    SYSINFO_STAGE = 0x41125000
    SDSTATUS_STAGE = 0x41126000
    SDCONFIG_STAGE = 0x41127000

    def _set_stage(self, src, dst, length):
        if length <= 0 or length % 4:
            raise SystemExit('stage length must be a positive multiple of 4')
        block = self._ctrl()
        struct.pack_into('<I', block, 4, 0)        # DONE = 0
        struct.pack_into('<I', block, 8, 1)        # CMD = run
        struct.pack_into('<I', block, 0xC, src)    # SRC
        struct.pack_into('<I', block, 0x10, dst)   # DST
        struct.pack_into('<I', block, 0x14, length)  # LEN
        self.write_mem(self.COUNTER_ADDR, bytes(block))

    def stage_bootinfo(self):
        """Install trampoline v3; stage boot-info (0x10004000), gate
        (0x10001000) and system-info (0x10000000) pages to DRAM, then
        read them back via the loader. The system-info page carries
        the storage backend selector at 0x10000058. MMIO sources: if a
        copy faults, DONE never reaches 2 and the camera may need a
        battery pull (same contract as the low copy)."""
        self.write_mem(self.TRAMP_ADDR, bytes.fromhex(self.TRAMP3_HEX))
        self.write_mem(self.COUNTER_ADDR, bytes(32))
        if self.read_mem(self.TRAMP_ADDR, 32) != bytes.fromhex(self.TRAMP3_HEX)[:32]:
            raise SystemExit('trampoline read-back mismatch; abort')
        originals = []
        for block, off in self.SLOT_BLOCKS:
            data = self.read_mem(block, 32)
            if struct.unpack_from('<I', data, off)[0] != self.HANDLER:
                raise SystemExit('slot does not hold handler; abort')
            originals.append(data)
        try:
            for (block, off), _ in zip(self.SLOT_BLOCKS, originals):
                data = bytearray(self.read_mem(block, 32))
                struct.pack_into('<I', data, off, self.TRAMP_ADDR)
                self._write_block32(block, bytes(data))
            print('trampoline v3 installed')
            stages = [
                (0x10000000, self.SYSINFO_STAGE, 0x100, 'sysinfo-staged.bin'),
                (0x10004000, self.BOOTINFO_STAGE, 0x100, 'bootinfo-staged.bin'),
                (0x10001000, self.GATE_STAGE, 0x304, 'gate-staged.bin'),
                (0x10004300, self.SDSTATUS_STAGE, 0x100, 'sdstatus-staged.bin'),
                (0x10004100, self.SDCONFIG_STAGE, 0x100, 'sdconfig-staged.bin'),
            ]
            for src, dst, length, name in stages:
                self._set_stage(src, dst, length)
                self.bootquery()
                if not self._wait_done():
                    raise SystemExit(
                        f'stage 0x{src:08x} did not complete (DONE!=2); '
                        'previously saved stages are safe; battery pull may be needed')
                data = self.read_mem(dst, length)
                Path(name).write_bytes(data)
                print(f'staged 0x{src:08x}..+0x{length:x} -> {name}: {data[:32].hex()}')
            # Decode the fields that decide the updater's SD path.
            sysinfo = Path('sysinfo-staged.bin').read_bytes()
            bootinfo = Path('bootinfo-staged.bin').read_bytes()
            gate = Path('gate-staged.bin').read_bytes()
            sdstatus = Path('sdstatus-staged.bin').read_bytes()
            sdconfig = Path('sdconfig-staged.bin').read_bytes()
            backend = struct.unpack_from('<H', sysinfo, 0x58)[0]
            status = struct.unpack_from('<H', bootinfo, 0x54)[0]
            buttons = bootinfo[0xB4:0xB9]
            name = bootinfo[0xB9:0xC1].split(b'\x00')[0].decode(errors='replace')
            ext = bootinfo[0xC1:0xC4].split(b'\x00')[0].decode(errors='replace')
            gate_word = struct.unpack_from('<I', gate, 0x300)[0]
            sd64, sd65, sd6a = sdstatus[0x64], sdstatus[0x65], sdstatus[0x6A]
            print(f'sysinfo: 0x10000058=0x{backend:04x} (backend=0x{backend & 0xE0:02x})')
            print(f'bootinfo: status=0x{status:04x} (bit2={bool(status & 4)}, '
                  f'bit7={bool(status & 0x80)}) buttons={buttons.hex()} '
                  f'mailbox={name or "<empty>"}.{ext or "<empty>"}')
            print(f'gate 0x10001300=0x{gate_word:08x}')
            print(f'sdstatus: 0x10004364=0x{sd64:02x} (bit5={bool(sd64 & 0x20)}, '
                  f'bit7={bool(sd64 & 0x80)}) 0x10004365=0x{sd65:02x} '
                  f'(low={sd65 & 0xF}) 0x1000436a=0x{sd6a:02x} (low={sd6a & 0xF})')
            print(f'sdconfig: 0x10004360=0x{sdstatus[0x60]:02x} '
                  f'0x10004361=0x{sdstatus[0x61]:02x} '
                  f'0x10004362=0x{struct.unpack_from("<H", sdstatus, 0x62)[0]:04x} '
                  f'0x10004165=0x{sdconfig[0x65]:02x} 0x10004185=0x{sdconfig[0x85]:02x}')
        finally:
            try:
                for (block, _), orig in zip(self.SLOT_BLOCKS, originals):
                    self._write_block32(block, orig)
                print('slots restored')
            except Exception as exc:
                print(f'WARNING: slot restore failed: {exc}')

    MAILBOX_STAGE = 0x41124000

    def set_mailbox(self, name: str, ext: str):
        """Write the boot-info filename mailbox (0x100040b9..0x100040c3)
        via trampoline v3, preserving surrounding bytes, and verify by
        re-staging. Emulator-qualified branch behavior:
        scripts/ReproduceDualBootP3.java (DUAL-BOOT P3 PASS)."""
        import re as _re
        if not _re.fullmatch(r'[A-Z0-9]{1,8}', name) or not _re.fullmatch(r'[A-Z0-9]{1,3}', ext):
            raise SystemExit('mailbox name/ext must be uppercase 8.3')
        self.write_mem(self.TRAMP_ADDR, bytes.fromhex(self.TRAMP3_HEX))
        self.write_mem(self.COUNTER_ADDR, bytes(32))
        if self.read_mem(self.TRAMP_ADDR, 32) != bytes.fromhex(self.TRAMP3_HEX)[:32]:
            raise SystemExit('trampoline read-back mismatch; abort')
        originals = []
        for block, off in self.SLOT_BLOCKS:
            data = self.read_mem(block, 32)
            if struct.unpack_from('<I', data, off)[0] != self.HANDLER:
                raise SystemExit('slot does not hold handler; abort')
            originals.append(data)
        try:
            for (block, off), _ in zip(self.SLOT_BLOCKS, originals):
                data = bytearray(self.read_mem(block, 32))
                struct.pack_into('<I', data, off, self.TRAMP_ADDR)
                self._write_block32(block, bytes(data))
            print('trampoline v3 installed')
            # Read current page, patch mailbox bytes in the staged copy.
            self._set_stage(0x10004000, self.BOOTINFO_STAGE, 0x100)
            self.bootquery()
            if not self._wait_done():
                raise SystemExit('bootinfo stage did not complete; battery pull may be needed')
            page = bytearray(self.read_mem(self.BOOTINFO_STAGE, 0x100))
            page[0xB9:0xC1] = name.encode().ljust(8, b'\x00')
            page[0xC1:0xC4] = ext.encode().ljust(3, b'\x00')
            # Copy back only 0xb8..0xc3 (preserves neighbours at both ends).
            self.write_mem(self.MAILBOX_STAGE, bytes(page[0xB8:0xC4]))
            self._set_stage(self.MAILBOX_STAGE, 0x100040B8, 12)
            self.bootquery()
            if not self._wait_done():
                raise SystemExit('mailbox write did not complete; battery pull may be needed')
            # Verify.
            self._set_stage(0x10004000, self.BOOTINFO_STAGE, 0x100)
            self.bootquery()
            if not self._wait_done():
                raise SystemExit('verify stage did not complete')
            check = self.read_mem(self.BOOTINFO_STAGE, 0x100)
            got_name = check[0xB9:0xC1].split(b'\x00')[0].decode()
            got_ext = check[0xC1:0xC4].split(b'\x00')[0].decode()
            print(f'mailbox now: {got_name}.{got_ext} (page bytes b8..c8: {check[0xB8:0xC8].hex()})')
            if (got_name, got_ext) != (name, ext):
                raise SystemExit('mailbox verify mismatch; do not proceed to ram-push')
        finally:
            try:
                for (block, _), orig in zip(self.SLOT_BLOCKS, originals):
                    self._write_block32(block, orig)
                print('slots restored')
            except Exception as exc:
                print(f'WARNING: slot restore failed: {exc}')

    def stage_copy(self):
        """Install trampoline v2; CMD=1 stages SRAM, CMD=2 stages low 256B."""
        self.write_mem(self.TRAMP_ADDR, bytes.fromhex(self.TRAMP2_HEX))
        self.write_mem(self.COUNTER_ADDR, bytes(32))
        if self.read_mem(self.TRAMP_ADDR, 32) != bytes.fromhex(self.TRAMP2_HEX)[:32]:
            raise SystemExit('trampoline read-back mismatch; abort')
        originals = []
        for block, off in self.SLOT_BLOCKS:
            data = self.read_mem(block, 32)
            if struct.unpack_from('<I', data, off)[0] != self.HANDLER:
                raise SystemExit('slot does not hold handler; abort')
            originals.append(data)
        try:
            for (block, off), _ in zip(self.SLOT_BLOCKS, originals):
                data = bytearray(self.read_mem(block, 32))
                struct.pack_into('<I', data, off, self.TRAMP_ADDR)
                self._write_block32(block, bytes(data))
            print('trampoline v2 installed')
            self._set_cmd(1)
            self.bootquery()
            if not self._wait_done():
                raise SystemExit('SRAM copy did not complete (DONE!=2); camera may need battery pull')
            self.read_chunked(self.SRAM_STAGE, 0x10000, 'sram-staged.bin', 0x10000)
            print('SRAM staged and saved to sram-staged.bin')
            self._set_cmd(2)
            self.bootquery()
            if not self._wait_done():
                raise SystemExit('low copy did not complete (DONE!=2); SRAM data already saved; battery pull may be needed')
            data = self.read_mem(self.LOW_STAGE, 0x100)
            Path('low-b00.bin').write_bytes(data)
            print('low 0xb00..0xc00 staged and saved to low-b00.bin:', data[:32].hex())
        finally:
            try:
                for (block, _), orig in zip(self.SLOT_BLOCKS, originals):
                    self._write_block32(block, orig)
                print('slots restored')
            except Exception as exc:
                print(f'WARNING: slot restore failed: {exc}')

    def bootquery_alloc(self, size: int) -> int:
        """Bootquery with a payload size: device allocates the staging
        buffer and returns its address (0 = allocation failure).
        Receiver size encoding is wValue=low16, wIndex=high16
        (0x401e1558: size = wValue | wIndex<<16). Getting this wrong
        wedged the receiver live on 2026-11-05 (it tried to zero
        724 MiB); the 1 MiB cap below is the guard against recurrence."""
        if not (0 < size <= 0x1000000):
            raise SystemExit('payload size outside 1..16 MiB guard')
        data = bytes(self.dev.ctrl_transfer(0xC0, 0x70, size & 0xFFFF,
                                            (size >> 16) & 0xFFFF, 5, timeout=TIMEOUT_MS))
        if len(data) != 5:
            raise SystemExit(f'bootquery-alloc: expected 5 bytes, got {data.hex()}')
        addr = struct.unpack_from('<I', data, 1)[0]
        # Live 2026-11-05: the loader heap arena sits at 0x45cc2cc0 on
        # this boot (write/read pattern-verified over the full span);
        # DRAM addresses here span 0x40000000..0x60000000.
        if addr and not (0x40000000 <= addr < 0x60000000):
            raise SystemExit(f'bootquery returned implausible buffer 0x{addr:08x}; abort')
        return addr

    def _write_payload(self, addr: int, data: bytes, expect_base: int, expect_size: int):
        """0x04f5 host-to-memory, restricted to the bootquery buffer."""
        if not (expect_base <= addr and addr + len(data) <= expect_base + expect_size):
            raise SystemExit('payload write outside bootquery buffer')
        if not (1 <= len(data) <= 0x8000):
            raise SystemExit('payload write must be 1..0x8000 bytes')
        payload = struct.pack('<II', len(data), addr)
        sent = self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, payload, timeout=TIMEOUT_MS)
        if sent != len(payload):
            raise SystemExit(f'payload setup: short control write {sent}')
        if self.dev.write(EP_OUT, data, timeout=TIMEOUT_MS) != len(data):
            raise SystemExit('payload bulk OUT stalled')

    STATE_BLOCK = 0x40FD0D80  # loader state +0x30..; callback word at +0x40 (block +0x10)
    EXPECTED_CALLBACK = 0x405DA53C

    def skip_prep(self):
        """Zero the optional state-6 preparation callback (mode-0x88
        park). Its 20 s wait can abort the receiver task and hang the
        handoff (observed live twice); NULL is a designed configuration
        (FUN_401e1f40 checks). Identity-checked against the known
        callback so an unexpected state layout aborts instead."""
        block = bytearray(self.read_mem(self.STATE_BLOCK, 32))
        current = struct.unpack_from('<I', block, 0x10)[0]
        print(f'preparation callback currently 0x{current:08x}')
        if current != self.EXPECTED_CALLBACK:
            raise SystemExit('unexpected callback pointer; abort (state layout changed?)')
        struct.pack_into('<I', block, 0x10, 0)
        payload = struct.pack('<II', 32, self.STATE_BLOCK)
        if self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F5, payload, timeout=TIMEOUT_MS) != 8:
            raise SystemExit('state-block setup: short control write')
        if self.dev.write(EP_OUT, bytes(block), timeout=TIMEOUT_MS) != 32:
            raise SystemExit('state-block write: short bulk OUT')
        check = struct.unpack_from('<I', self.read_mem(self.STATE_BLOCK, 32), 0x10)[0]
        if check != 0:
            raise SystemExit('callback did not clear; abort')
        print('preparation callback cleared (park skipped for this boot)')

    SD_REMOUNT_STUB = 0x411F0000
    SD_REMOUNT_PATCH = 0x4000E5A4

    @staticmethod
    def _sd_remount_stub() -> bytes:
        """Stub: call the updater's sdReMount/sdReDetect(2,0), then
        resume the original mount wrapper FUN_4001c594 with r0=2.
        Branch encodings verified against the updater image."""
        words = [
            0xE92D4000,  # push {lr}
            0xE3A00002,  # mov r0, #2
            0xE3A01000,  # mov r1, #0
            0xEBB8BC6F,  # bl 0x4001f1d0
            0xE3A00002,  # mov r0, #2
            0xE8BD4000,  # pop {lr}
            0xEAB8B15D,  # b 0x4001c594
            0xE320F000,  # nop padding
        ]
        return b''.join(struct.pack('<I', w) for w in words)

    @staticmethod
    def _sd_init_stub() -> bytes:
        """Stub: call the updater's SD init/configure 0x4006ed88(0),
        then resume the original mount wrapper FUN_4001c594 with r0=2.
        This includes the controller clock/bus-width configuration."""
        words = [
            0xE92D4000,  # push {lr}
            0xE3A00000,  # mov r0, #0
            0xEBB9FB5E,  # bl 0x4006ed88
            0xE3A00002,  # mov r0, #2
            0xE8BD4000,  # pop {lr}
            0xEAB8B15E,  # b 0x4001c594
            0xE320F000,  # nop padding
            0xE320F000,  # nop padding
        ]
        return b''.join(struct.pack('<I', w) for w in words)

    def ram_push(self, payload_path: str, skip_prep: bool = False, settle_s: int = 0,
                 drain_log: bool = False, sd_remount_first: bool = False,
                 sd_init_first: bool = False):
        """P2 of analysis/ram-push-sd-boot-plan.md: download a payload
        into the bootquery buffer, verify it by read-back, then issue
        the 0x04f7 upload/run handoff (dest 0x40000000). The handoff
        replaces the running environment; no reply is expected.
        With drain_log=True, EP 0x84 is drained during the whole run so
        the firmware's assert/log output is captured if a preparation
        wait aborts the receiver task."""
        payload = Path(payload_path).read_bytes()
        if sd_remount_first and sd_init_first:
            raise SystemExit('choose one of --sd-remount-first / --sd-init-first')
        sd_patch = sd_remount_first or sd_init_first
        if sd_patch:
            if len(payload) < self.SD_REMOUNT_PATCH + 4 - 0x40000000:
                raise SystemExit('payload too small for the SD patch')
            payload = bytearray(payload)
            off = self.SD_REMOUNT_PATCH - 0x40000000
            struct.pack_into('<I', payload, off, 0xEA478695)  # b 0x411f0000
            payload = bytes(payload)
            label = 'SD init' if sd_init_first else 'SD remount'
            print(f'{label} patch: 0x4000e5a4 -> b 0x411f0000')
        size = len(payload)
        want = hashlib.sha256(payload).hexdigest()
        print(f'payload {payload_path}: {size} bytes sha256 {want}')
        log_stop = None
        log_packets = []
        if drain_log:
            log_stop, log_packets = self._start_log_drain()
            print('log drain started on EP 0x84')
        try:
            if skip_prep:
                self.skip_prep()
            base = self.bootquery_alloc(size)
            if base == 0:
                raise SystemExit('bootquery allocation failed (address 0); abort')
            print(f'bootquery buffer at 0x{base:08x}')
            chunk = 0x8000
            for off in range(0, size, chunk):
                part = payload[off:off + chunk]
                # 0x04f5 requires 32-byte alignment of address and length
                # handling: all chunks except possibly the last are 0x8000.
                self._write_payload(base + off, part, base, size)
                print(f'  downloaded 0x{off + len(part):x}/0x{size:x}', flush=True)
            tmp = Path('ram-push-readback.bin')
            self.read_chunked(base, size, str(tmp), 0x10000)
            got = hashlib.sha256(tmp.read_bytes()).hexdigest()
            print(f'read-back sha256 {got}')
            if got != want:
                raise SystemExit('read-back mismatch; aborting before 0x04f7')
            print('read-back verified.')
            if sd_patch:
                stub = self._sd_init_stub() if sd_init_first else self._sd_remount_stub()
                self.write_mem(self.SD_REMOUNT_STUB, stub)
                check = self.read_mem(self.SD_REMOUNT_STUB, 32)
                if check != stub:
                    raise SystemExit('SD patch stub read-back mismatch; abort')
                print(f'SD patch stub installed at 0x{self.SD_REMOUNT_STUB:08x}')
            if settle_s:
                import time
                print(f'settling {settle_s}s before run...')
                time.sleep(settle_s)
                print(f'pre-run bootquery: {self.bootquery().hex()}')
            print('Sending 0x04f7 upload/run NOW '
                  '(device will leave USB as the environment is replaced)...')
            try:
                sent = self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F7,
                                              struct.pack('<III', base, size, 0),
                                              timeout=TIMEOUT_MS)
                print(f'0x04f7 setup sent ({sent} bytes); waiting for the device to drop off USB...')
            except Exception as exc:
                print(f'0x04f7 transfer ended with {type(exc).__name__}: {exc} '
                      '(expected if the handoff already started)')
        finally:
            if log_stop is not None:
                import time
                time.sleep(0.5)
                log_stop.set()
                print(f'log drain captured {len(log_packets)} packet(s)')

    # Trampoline v4: v3 copier + CMD=2 direct call of the handoff helper
    # FUN_401eb1ec(arg0..arg3, stack arg4) from control block fields.
    # Emulator-qualified: scripts/ReproduceTrampoline4.java (PASS).
    TRAMP4_HEX = ('0f002de9a8009fe5001090e5011081e2001080e5041090e5000051e3'
                  '2100001a081090e5010051e30200000a020051e30c00000a1b0000ea'
                  '0110a0e3041080e50c1090e5102090e5143090e504c091e404c082e4'
                  '043053e2fbffff1a0210a0e3041080e50f0000ea0110a0e3041080e5'
                  '101090e5142090e5183090e51cc090e508d04de200c08de50c0090e5'
                  '24c09fe53cff2fe108d08de214009fe50310a0e3041080e5ffffffea'
                  '0f00bde808c09fe51cff2fe100011041ecb11e4080121e40')
    TRAMP4_SHA256 = 'ca23a7057d3001ea2f30aac5d1bcf464cb9b9d59d0b9ded50ba2f37728995644'
    LOADER_STATE = 0x40FD0D50

    # Trampoline v5: v4 with call target taken from control +0x20.
    # Emulator-qualified: scripts/ReproduceTrampoline5.java (PASS).
    TRAMP5_HEX = ('0f002de9a8009fe5001090e5011081e2001080e5041090e5000051e3'
                  '2100001a081090e5010051e30200000a020051e30c00000a1b0000ea'
                  '0110a0e3041080e50c1090e5102090e5143090e504c091e404c082e4'
                  '043053e2fbffff1a0210a0e3041080e50f0000ea0110a0e3041080e5'
                  '101090e5142090e5183090e51cc090e508d04de200c08de520c090e5'
                  '0c0090e53cff2fe108d08de214009fe50310a0e3041080e5ffffffea'
                  '0f00bde804c09fe51cff2fe10001104180121e40')
    TRAMP5_SHA256 = 'b7fd40e7c63ee30a4f907d96990950c5df3655b55c517054724f553456c57b5d'
    HANDOFF_HELPER = 0x401EB1EC
    PARK_FUNCTION = 0x4015BF04

    def _trampoline_call(self, target, a0, a1, a2, a3, stack_arg):
        ctrl = self._ctrl()
        struct.pack_into('<I', ctrl, 4, 0)          # DONE
        struct.pack_into('<I', ctrl, 8, 2)          # CMD = call
        struct.pack_into('<I', ctrl, 0xC, a0)
        struct.pack_into('<I', ctrl, 0x10, a1)
        struct.pack_into('<I', ctrl, 0x14, a2)
        struct.pack_into('<I', ctrl, 0x18, a3)
        struct.pack_into('<I', ctrl, 0x1C, stack_arg)
        struct.pack_into('<I', ctrl, 0x20, target)
        self.write_mem(self.COUNTER_ADDR, bytes(ctrl))
        try:
            self.bootquery()
        except Exception as exc:
            print(f'call trigger ended with {type(exc).__name__} (may still be running)')

    def _wait_call_done(self, timeout_s=45):
        import time
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            done = struct.unpack_from('<I', self._ctrl(), 4)[0]
            if done == 3:
                return True
            time.sleep(1)
        return False

    FIXED_STAGE = 0x41130000
    FIXED_WORKSPACE = 0x411E0000

    def direct_handoff(self, payload_path: str, fixed: bool = False, park_first: bool = False):
        """Bypass state 6: download payload, verify, then invoke the
        handoff helper FUN_401eb1ec directly via trampoline v4.
        See analysis/state6-hang-analysis.md. With fixed=True, no
        bootquery-alloc is used: fixed scratch staging/workspace
        (payload must fit 0x41130000..0x411E0000)."""
        payload = Path(payload_path).read_bytes()
        size = len(payload)
        want = hashlib.sha256(payload).hexdigest()
        print(f'payload {payload_path}: {size} bytes sha256 {want}')
        if fixed:
            if size > self.FIXED_WORKSPACE - self.FIXED_STAGE:
                raise SystemExit('payload too large for fixed staging')
            base, workspace = self.FIXED_STAGE, self.FIXED_WORKSPACE
        else:
            base = self.bootquery_alloc(size)
            if base == 0:
                raise SystemExit('bootquery allocation failed (address 0); abort')
            # NOTE: tiny (4-byte) bulk reads stall this receiver; read a block.
            workspace = struct.unpack_from('<I', self.read_mem(self.LOADER_STATE, 64), 0x20)[0]
            if not (0x40000000 <= workspace < 0x60000000):
                raise SystemExit('implausible workspace pointer; abort')
        print(f'payload buffer 0x{base:08x}, handoff workspace 0x{workspace:08x}')
        # Install v4 BEFORE the bulk storm: repeated bulk transfers can
        # desync this receiver and kill later control transfers (seen
        # live). With CMD=0 the installed trampoline only forwards, so
        # an abort anywhere below still leaves a safe state.
        tramp = bytes.fromhex(self.TRAMP5_HEX)
        if len(tramp) != 188 or hashlib.sha256(tramp).hexdigest() != self.TRAMP5_SHA256:
            raise SystemExit('embedded trampoline v5 bytes failed self-check; abort')
        self.write_mem(self.TRAMP_ADDR, tramp)
        self.write_mem(self.COUNTER_ADDR, bytes(32))
        if self.read_mem(self.TRAMP_ADDR, 32) != tramp[:32]:
            raise SystemExit('trampoline read-back mismatch; abort')
        originals = []
        for block, off in self.SLOT_BLOCKS:
            data = self.read_mem(block, 32)
            if struct.unpack_from('<I', data, off)[0] != self.HANDLER:
                raise SystemExit('slot does not hold handler; abort')
            originals.append(data)
        for (block, off), _ in zip(self.SLOT_BLOCKS, originals):
            data = bytearray(self.read_mem(block, 32))
            struct.pack_into('<I', data, off, self.TRAMP_ADDR)
            self._write_block32(block, bytes(data))
        print('trampoline v5 installed (idle, CMD=0)')
        if park_first:
            print('parking camera via FUN_4015bf04 (mode 0x88)...')
            self._trampoline_call(self.PARK_FUNCTION, 0, 0, 0, 0, 0)
            if not self._wait_call_done(45):
                raise SystemExit('park call did not complete in 45 s (DONE!=3); aborting before handoff')
            print('park complete (DONE=3)')
        chunk = 0x8000
        for off in range(0, size, chunk):
            part = payload[off:off + chunk]
            self._write_payload(base + off, part, base, size)
        print(f'downloaded 0x{size:x} bytes')
        tmp = Path('direct-handoff-readback.bin')
        self.read_chunked(base, size, str(tmp), 0x10000)
        got = hashlib.sha256(tmp.read_bytes()).hexdigest()
        print(f'read-back sha256 {got}')
        if got != want:
            raise SystemExit('read-back mismatch; aborting before handoff')
        print(f'calling helper(0x{base:08x}, 0x{workspace:08x}, 0x{size:x}, 0x40000000, 2) via trampoline...')
        self._trampoline_call(self.HANDOFF_HELPER, base, workspace, size, 0x40000000, 2)
        print('helper call triggered (device should drop off USB if handoff started)')

    def updater_probe(self):
        """Read-only probe of the RAM-pushed updater's service personality.
        The 0x70 bootquery timed out on the updater, but the 0x04f2 DRAM
        read family may still work (updater handler 0x40010678 -> send
        path 0x40075138). Only aligned DRAM addresses: SRAM/MMIO reads
        wedge the receiver. No writes, no run requests."""
        blocks = [
            (0x40000000, 0x100, 'updater reset/vector'),
            (0x4009d6a0, 0x20, 'log/console config'),
            (0x400a2980, 0x40, 'log task state'),
            (0x400ba7c0, 0x40, 'log state'),
        ]
        for addr, length, label in blocks:
            try:
                data = self.read_mem(addr, length)
            except BaseException as exc:
                print(f'{label} 0x{addr:08x}+0x{length:x}: FAILED '
                      f'{type(exc).__name__}: {exc}')
                print('stopping probe; later reads would run against a desynced receiver')
                return
            print(f'{label} 0x{addr:08x}+0x{length:x}: {data.hex()}')
            text = ''.join(chr(b) if 32 <= b < 127 or b in (10, 13) else '.'
                           for b in data)
            print(f'  ascii: {text}')

    def drain_only(self, seconds: int):
        """Read-only: drain EP 0x84 for the given number of seconds and
        print the firmware log records. Sends no control requests, so it
        is safe against the updater's service personality (which does not
        answer the loader 0x04f2 read)."""
        import time
        stop, packets = self._start_log_drain()
        print(f'draining EP 0x84 for {seconds}s...')
        try:
            time.sleep(seconds)
        finally:
            stop.set()
        print(f'captured {len(packets)} packet(s)')

    def hijack_test(self):
        tramp = b''.join(struct.pack('<I', w) for w in self.TRAMP_WORDS)
        # Stage trampoline + zeroed counter in scratch (normal write path).
        self.write_mem(self.TRAMP_ADDR, tramp)
        self.write_mem(self.COUNTER_ADDR, bytes(32))
        originals = []
        for block, off in self.SLOT_BLOCKS:
            data = self.read_mem(block, 32)
            word = struct.unpack_from('<I', data, off)[0]
            if word != self.HANDLER:
                raise SystemExit(f'slot in block 0x{block:08x} is 0x{word:08x}, expected handler; abort')
            originals.append(data)
        count_mid = None
        try:
            for (block, off), _orig in zip(self.SLOT_BLOCKS, originals):
                data = bytearray(self.read_mem(block, 32))
                struct.pack_into('<I', data, off, self.TRAMP_ADDR)
                self._write_block32(block, bytes(data))
            print('slots patched to trampoline; triggering via 2 bootqueries')
            self.bootquery()
            self.bootquery()
            count_mid = struct.unpack_from('<I', self.read_mem(self.COUNTER_ADDR, 32))[0]
            print(f'counter after triggers: {count_mid}')
        finally:
            for (block, _off), orig in zip(self.SLOT_BLOCKS, originals):
                self._write_block32(block, orig)
            print('slots restored')
        for (block, off), orig in zip(self.SLOT_BLOCKS, originals):
            now = self.read_mem(block, 32)
            if now != orig:
                raise SystemExit(f'slot block 0x{block:08x} restore mismatch')
        self.bootquery()
        count_end = struct.unpack_from('<I', self.read_mem(self.COUNTER_ADDR, 32))[0]
        print(f'counter after restore + 1 bootquery: {count_end}')
        if not count_mid:
            raise SystemExit('HIJACK DID NOT FIRE (counter 0); restore verified')
        if count_end != count_mid:
            raise SystemExit('counter changed after restore; restore ineffective')
        print(f'HIJACK TEST PASS: handler intercepted {count_mid} time(s), trampoline executed, restore effective')

    def write_test(self, addr: int, hold_s: int):
        """Read, write pattern, read back, hold, re-read. One session."""
        import time
        pattern = bytes((i * 7 + 0xA5) & 0xFF for i in range(32))
        before = self.read_mem(addr, 32)
        print(f'before 0x{addr:08x}: {before.hex()}')
        self.write_mem(addr, pattern)
        print(f'wrote pattern:      {pattern.hex()}')
        back = self.read_mem(addr, 32)
        print(f'read-back:          {back.hex()}')
        if back != pattern:
            raise SystemExit('READ-BACK MISMATCH')
        print('read-back match. holding', hold_s, 's...')
        time.sleep(hold_s)
        again = self.read_mem(addr, 32)
        print(f'after hold:         {again.hex()}')
        if again != pattern:
            raise SystemExit('PATTERN CHANGED DURING HOLD (another agent touched it)')
        print('WRITE TEST PASS: pattern stable across hold')

    def read_chunked(self, addr: int, length: int, out_path: str, chunk: int = 16):
        """Sequential small reads, appended to OUT as they complete.

        Live evidence 2026-11-04: a 16-byte read succeeds; 256-byte and
        8192-byte single reads deliver nothing and wedge the receiver.
        """
        # Total span must lie inside one allowed range; per-chunk caps apply
        # in read_mem. (check_range's MAX_LEN is per single transfer.)
        if not any(s <= addr and addr + length <= e for s, e, _ in ALLOWED):
            raise SystemExit(f'span 0x{addr:08x}+0x{length:x} outside allowlist')
        done = 0
        with open(out_path, 'wb') as fh:
            while done < length:
                n = min(chunk, length - done)
                data = self.read_mem(addr + done, n)
                fh.write(data)
                fh.flush()
                done += n
                print(f'  chunk 0x{addr + done - n:08x} +0x{n:x} ok ({done}/{length})', flush=True)
        return done


def open_device():
    import usb.core
    import usb.util
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        raise SystemExit(f'loader {VID:04x}:{PID:04x} not found (entry + passthrough done?)')
    try:
        if dev.is_kernel_driver_active(INTERFACE):
            dev.detach_kernel_driver(INTERFACE)
    except Exception:
        pass
    try:
        active = dev.get_active_configuration()
    except Exception:
        active = None
    if active is None:
        dev.set_configuration()
    # NOTE: set_configuration on an already-configured loader makes the
    # device re-run target init, which tears down the APITEST bridge.
    # Never reconfigure when a configuration is already active.
    usb.util.claim_interface(dev, INTERFACE)
    return dev


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--bootquery', action='store_true')
    mode.add_argument('--read', nargs=3, metavar=('ADDR', 'LEN', 'OUT'))
    mode.add_argument('--read-chunked', nargs=4, metavar=('ADDR', 'LEN', 'OUT', 'CHUNK'))
    mode.add_argument('--write-test', nargs=2, metavar=('ADDR', 'HOLD_S'))
    mode.add_argument('--hijack-test', action='store_true')
    mode.add_argument('--stage-copy', action='store_true')
    mode.add_argument('--stage-bootinfo', action='store_true')
    mode.add_argument('--updater-probe', action='store_true')
    mode.add_argument('--drain-only', type=int, metavar='SEC')
    mode.add_argument('--ram-push', metavar='PAYLOAD')
    mode.add_argument('--direct-handoff', metavar='PAYLOAD')
    mode.add_argument('--set-mailbox', nargs=2, metavar=('NAME', 'EXT'))
    parser.add_argument('--skip-prep', action='store_true',
                        help='with --ram-push: zero the mode-0x88 preparation callback first')
    parser.add_argument('--settle', type=int, default=0, metavar='SEC',
                        help='with --ram-push: idle wait + bootquery between verify and run')
    parser.add_argument('--drain-log', action='store_true',
                        help='with --ram-push: drain EP 0x84 diagnostic log during the run')
    parser.add_argument('--sd-remount-first', action='store_true',
                        help='with --ram-push: patch the updater mount wrapper to call sdReMount(2,0) first')
    parser.add_argument('--sd-init-first', action='store_true',
                        help='with --ram-push: patch the updater mount wrapper to call SD init 0x4006ed88(0) first')
    parser.add_argument('--fixed-staging', action='store_true',
                        help='with --direct-handoff: use fixed scratch staging, no bootquery-alloc')
    parser.add_argument('--park-first', action='store_true',
                        help='with --direct-handoff: park to mode 0x88 via trampoline call first')
    mode.add_argument('--apitest-init', action='store_true')
    mode.add_argument('--apitest-state', action='store_true')
    mode.add_argument('--apitest-send', metavar='TEXT')
    mode.add_argument('--apitest-output', action='store_true')
    args = parser.parse_args()
    if sys.platform != 'linux':
        raise SystemExit('Linux only: run inside the passthrough VM/host')
    dev = open_device()
    try:
        loader = Loader(dev)
        if args.bootquery:
            data = loader.bootquery()
            method, addr = data[0], struct.unpack_from('<I', data, 1)[0]
            print(f'bootquery reply: {data.hex()}')
            print(f'boot method byte: {method} (2 = running firmware, per host-DLL classification)')
            print(f'address word: 0x{addr:08x} (0 expected for zero-size query)')
        elif args.apitest_init:
            loader.apitest_init()
        elif args.apitest_state:
            loader.apitest_state()
        elif args.apitest_send is not None:
            loader.apitest_send(args.apitest_send)
        elif args.apitest_output:
            loader.apitest_output()
        elif args.stage_copy:
            loader.stage_copy()
        elif args.stage_bootinfo:
            loader.stage_bootinfo()
        elif args.updater_probe:
            loader.updater_probe()
        elif args.drain_only:
            loader.drain_only(args.drain_only)
        elif args.ram_push:
            loader.ram_push(args.ram_push, skip_prep=args.skip_prep,
                            settle_s=args.settle, drain_log=args.drain_log,
                            sd_remount_first=args.sd_remount_first,
                            sd_init_first=args.sd_init_first)
        elif args.direct_handoff:
            loader.direct_handoff(args.direct_handoff, fixed=args.fixed_staging,
                                  park_first=args.park_first)
        elif args.set_mailbox:
            loader.set_mailbox(args.set_mailbox[0], args.set_mailbox[1])
        elif args.hijack_test:
            loader.hijack_test()
        elif args.write_test:
            addr_s, hold_s = args.write_test
            loader.write_test(int(addr_s, 0), int(hold_s, 0))
        elif args.read_chunked:
            addr_s, len_s, out_path, chunk_s = args.read_chunked
            addr, length, chunk = int(addr_s, 0), int(len_s, 0), int(chunk_s, 0)
            if not (1 <= chunk <= MAX_LEN):
                raise SystemExit('bad chunk size')
            loader.read_chunked(addr, length, out_path, chunk)
            data = Path(out_path).read_bytes()
            print(f'read-chunked 0x{addr:08x} len 0x{length:x}: sha256 {hashlib.sha256(data).hexdigest()}')
            print(f'saved to {out_path} (keep outside git)')
        else:
            addr_s, len_s, out_path = args.read
            addr, length = int(addr_s, 0), int(len_s, 0)
            data = loader.read_mem(addr, length)
            Path(out_path).write_bytes(data)
            print(f'read 0x{addr:08x} len 0x{length:x}: sha256 {hashlib.sha256(data).hexdigest()}')
            print(f'first 32 bytes: {data[:32].hex()}')
            print(f'saved to {out_path} (keep outside git)')
    finally:
        import usb.util
        usb.util.release_interface(dev, INTERFACE)
        usb.util.dispose_resources(dev)


if __name__ == '__main__':
    main()
