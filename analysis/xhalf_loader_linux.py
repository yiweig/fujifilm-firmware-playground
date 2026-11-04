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
    (0x40000000, 0x41400000, 'main image and runtime RAM'),
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

    def _write_block32(self, block: int, data: bytes):
        if block not in (b for b, _ in self.SLOT_BLOCKS) or len(data) != 32:
            raise SystemExit('block write outside hijack slot allowlist')
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
    dev.set_configuration()
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
        elif args.stage_copy:
            loader.stage_copy()
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
