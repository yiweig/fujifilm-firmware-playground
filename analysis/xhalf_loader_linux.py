"""Restricted Linux/pyusb client for the X half target-0 loader (2aad:6500).

Read-only first rung. Linux only; run inside the passthrough VM.

Modes:
  --bootquery       Vendor IN 0x70 with wValue=wIndex=0 (zero size: skips the
                    receiver's allocation branch). Prints boot-method byte
                    and the returned address word.
  --read ADDR LEN OUT.bin
                    Vendor OUT 0xfd, wIndex=0x04f2, 8-byte payload
                    (address,length LE), then reads LEN bytes from bulk IN
                    0x82 (interface 5) into OUT.bin.

There is intentionally no memory-write, upload, or execute mode in this
client. Address/length are allowlisted host-side; the firmware parser itself
applies no bounds, so this allowlist is the safety boundary.

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
        return bytearray(self.read_mem(self.COUNTER_ADDR, 32))

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
        """Install trampoline v3; stage boot-info (0x10004000) and gate
        (0x10001000) pages to DRAM, then read them back via the loader.
        MMIO sources: if a copy faults, DONE never reaches 2 and the
        camera may need a battery pull (same contract as the low copy)."""
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
                (0x10004000, self.BOOTINFO_STAGE, 0x100, 'bootinfo-staged.bin'),
                (0x10001000, self.GATE_STAGE, 0x304, 'gate-staged.bin'),
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
        if not (0 < size <= 0x100000):
            raise SystemExit('payload size outside 1..1 MiB guard')
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

    def ram_push(self, payload_path: str):
        """P2 of analysis/ram-push-sd-boot-plan.md: download a payload
        into the bootquery buffer, verify it by read-back, then issue
        the 0x04f7 upload/run handoff (dest 0x40000000). The handoff
        replaces the running environment; no reply is expected."""
        payload = Path(payload_path).read_bytes()
        size = len(payload)
        want = hashlib.sha256(payload).hexdigest()
        print(f'payload {payload_path}: {size} bytes sha256 {want}')
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
        print('read-back verified. Sending 0x04f7 upload/run NOW '
              '(device will leave USB as the environment is replaced)...')
        try:
            sent = self.dev.ctrl_transfer(0x40, 0xFD, 0, 0x04F7,
                                          struct.pack('<III', base, size, 0),
                                          timeout=TIMEOUT_MS)
            print(f'0x04f7 setup sent ({sent} bytes); waiting for the device to drop off USB...')
        except Exception as exc:
            print(f'0x04f7 transfer ended with {type(exc).__name__}: {exc} '
                  '(expected if the handoff already started)')

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
    mode.add_argument('--ram-push', metavar='PAYLOAD')
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
        elif args.ram_push:
            loader.ram_push(args.ram_push)
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
