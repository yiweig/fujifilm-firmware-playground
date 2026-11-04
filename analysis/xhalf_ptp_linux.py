"""Restricted Linux/pyusb transport for the X half factory-PTP read candidate.

Linux only. This host is expected to have exclusive USB access (e.g. a VM with
the camera passed through), so no other client can interleave traffic.

Modes:
  --probe            OpenSession, one 1027(0) chunk-read probe, CloseSession.
                     Reports ACTIVATED (0x2001 + 5 params) or NOT ACTIVATED
                     (0x2005). This is the marker-activation test; it discards
                     the returned bytes and issues no load or write operation.
  --acquire OUT.bin  Full read-only acquisition via ptp_cold_reader.ColdReader
                     (1025(0) + 64 x 1027 reads, twice, checksum-validated),
                     saving the 1024-byte record to OUT.bin outside git.

No other operations exist in this client. Encoding/allowlist come from
ptp_cold_reader.command; the probe parses the raw response itself only so it
can distinguish 0x2005 (not activated) from a successful chunk reply.
"""
import argparse
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ptp_cold_reader import ColdReader, ProtocolError, command  # noqa: E402

VID, PID = 0x04CB, 0x030A
EP_OUT, EP_IN = 0x03, 0x82
TIMEOUT_MS = 3000
HEADER = struct.Struct('<IHHI')


class UsbTransport:
    def __init__(self, dev):
        self.dev = dev

    def exchange(self, request: bytes) -> bytes:
        written = self.dev.write(EP_OUT, request, timeout=TIMEOUT_MS)
        if written != len(request):
            raise ProtocolError(f'short bulk OUT write: {written}/{len(request)}')
        raw = bytes(self.dev.read(EP_IN, 512, timeout=TIMEOUT_MS))
        if len(raw) < 12:
            raise ProtocolError(f'short bulk IN read: {len(raw)} bytes')
        length = struct.unpack_from('<I', raw)[0]
        while len(raw) < length:
            raw += bytes(self.dev.read(EP_IN, 512, timeout=TIMEOUT_MS))
        if len(raw) != length:
            raise ProtocolError(f'coalesced/oversized reply: {len(raw)} vs {length}')
        return raw


def open_device():
    import usb.core
    import usb.util
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        raise SystemExit(f'camera {VID:04x}:{PID:04x} not found (passthrough active?)')
    try:
        if dev.is_kernel_driver_active(0):
            dev.detach_kernel_driver(0)
    except Exception:
        pass
    dev.set_configuration()
    usb.util.claim_interface(dev, 0)
    return dev


def probe(transport: UsbTransport) -> int:
    transport.exchange(command(0x1002, (1,), 0))
    raw = transport.exchange(command(0x1027, (0,), 1))
    length, kind, code, txn = HEADER.unpack_from(raw)
    # Exit codes: 0 = ACTIVATED, 10 = NOT ACTIVATED, 2 = unexpected/stop.
    result = 2
    if code == 0x2005 and length == 12:
        print('NOT ACTIVATED: 1027(0) -> 2005 OperationNotSupported')
        result = 10
    elif code == 0x2001 and kind == 3 and txn == 1 and length == 32:
        result = 0
        params = struct.unpack_from('<5I', raw, 12)
        print(f'ACTIVATED: 1027(0) -> 2001, 5 params, prefix={params[0]} (chunk bytes discarded)')
    else:
        print(f'UNEXPECTED: code=0x{code:04x} len={length} kind={kind} txn={txn}; stop, do not retry')
        result = 2
    transport.exchange(command(0x1003, (), 2))
    print('session closed')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--probe', action='store_true')
    mode.add_argument('--acquire', type=Path)
    args = parser.parse_args()
    if sys.platform != 'linux':
        raise SystemExit('Linux only: run inside the passthrough VM/host')
    dev = open_device()
    transport = UsbTransport(dev)
    try:
        if args.probe:
            raise SystemExit(probe(transport))
        capture = ColdReader(transport).acquire()
        args.acquire.write_bytes(capture.data)
        import json
        print(json.dumps(capture.summary(), indent=2))
        print(f'record saved to {args.acquire} (keep outside git)')
    finally:
        import usb.util
        usb.util.release_interface(dev, 0)
        usb.util.dispose_resources(dev)


if __name__ == '__main__':
    main()
