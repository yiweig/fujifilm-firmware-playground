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
