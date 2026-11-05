"""Convert an X half engineering RAW dump (SD file `W`/`D`) to DNG.

Input format (measured, see analysis/full-frame-raw-capture.md):
  - frame at offset 0: little-endian u16 words, 12-bit data
    left-justified (raw12 << 4), RGGB Bayer
  - full-frame dumps (4864x3648) carry a 512-byte trailer starting
    with the ASCII magic {iCatCh}; preview dumps (1624x408) are bare

The DNG stores true 12-bit values (>>4) in 16-bit samples with
BlackLevel from a lens-covered capture (~5) and WhiteLevel 4095.
ColorMatrix1 is a generic IMX283-class daylight approximation and
AsShotNeutral defaults to a mid-tone gray-world estimate from the
frame itself; both are placeholders pending a color-chart calibration.

Usage:
  python3 raw2dng.py /private/tmp/xhalf-snap-exp235.bin out.dng
  python3 raw2dng.py --size 1624x408 preview.bin preview.dng
"""
import array
import struct
import sys
from pathlib import Path

FOOTER_MAGIC = b'{iCatCh}'


def read_frame(path: Path, size):
    data = path.read_bytes()
    if size is None:
        if data[-512:-504] != FOOTER_MAGIC or len(data) < 512:
            raise SystemExit('no {iCatCh} trailer; pass --size WxH')
        n = len(data) - 512
        for w, h in ((4864, 3648),):
            if w * h * 2 == n:
                size = (w, h)
                break
        if size is None:
            raise SystemExit(f'unexpected frame byte count {n}; pass --size')
    w, h = size
    need = w * h * 2
    if len(data) < need:
        raise SystemExit(f'file too small for {w}x{h}: {len(data)} < {need}')
    words = array.array('H')
    words.frombytes(data[:need])
    return w, h, words


def wb_estimate(words, w, h):
    """Gray-world on 2x2 blocks fully inside 200..3500 (unclipped mids)."""
    sr = sg = sb = n = 0
    for by in range(0, h - 1, 2):
        r0 = by * w
        r1 = r0 + w
        for bx in range(0, w - 1, 2):
            R = words[r0 + bx] >> 4
            G = ((words[r0 + bx + 1] >> 4) + (words[r1 + bx] >> 4)) // 2
            B = words[r1 + bx + 1] >> 4
            if 200 < R < 3500 and 200 < G < 3500 and 200 < B < 3500:
                sr += R; sg += G; sb += B; n += 1
    if n == 0:
        return (1.0, 1.0, 1.0), 0
    mr, mg, mb = sr / n, sg / n, sb / n
    return (mg / mr, 1.0, mg / mb), n  # WB multipliers for R, G, B


class Tiff:
    def __init__(self):
        self.entries = []  # (tag, type, values bytes as python list)
        self.data_start = 8

    def add(self, tag, typ, vals):
        self.entries.append((tag, typ, list(vals)))

    def build(self, strip_offset_placeholder=True):
        # types: 1 BYTE,2 ASCII,3 SHORT,4 LONG,5 RATIONAL,7 UNDEFINED,10 SRATIONAL
        sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8}
        ents = sorted(self.entries)
        n = len(ents)
        header = 8
        ifd_size = 2 + n * 12 + 4
        ext_off = header + ifd_size
        out = bytearray()
        out += b'II' + struct.pack('<HI', 42, 8)
        ifd = bytearray(struct.pack('<H', n))
        ext = bytearray()
        for tag, typ, vals in ents:
            raw = bytearray()
            for v in vals:
                if typ == 3:
                    raw += struct.pack('<H', v)
                elif typ == 4:
                    raw += struct.pack('<I', v)
                elif typ in (1, 7):
                    raw += struct.pack('<B', v)
                elif typ == 2:
                    raw += bytes([v]) if isinstance(v, int) else v.encode()
                elif typ == 5:
                    raw += struct.pack('<II', v[0], v[1])
                elif typ == 10:
                    raw += struct.pack('<ii', v[0], v[1])
                else:
                    raise ValueError(typ)
            cnt = len(vals)
            if len(raw) <= 4:
                field = bytes(raw) + b'\x00' * (4 - len(raw))
            else:
                field = struct.pack('<I', ext_off + len(ext))
                ext += raw
                if len(ext) % 2:
                    ext += b'\x00'
            ifd += struct.pack('<HHI', tag, typ, cnt) + field
        ifd += struct.pack('<I', 0)
        out += ifd + ext
        return bytes(out), (header + ifd_size + len(ext))


def rat(x, scale=10000):
    return (int(round(x * scale)), scale)


def main():
    args = sys.argv[1:]
    size = None
    if args[:1] == ['--size']:
        ws, hs = args[1].lower().split('x')
        size = (int(ws), int(hs))
        args = args[2:]
    if len(args) != 2:
        raise SystemExit(__doc__)
    src, dst = Path(args[0]), Path(args[1])
    w, h, words = read_frame(src, size)
    gains, nblocks = wb_estimate(words, w, h)
    print(f'frame {w}x{h}; WB multipliers R={gains[0]:.3f} G=1 B={gains[2]:.3f} '
          f'from {nblocks} mid-tone blocks')

    px = array.array('H', (v >> 4 for v in words))
    if sys.byteorder == 'big':
        px.byteswap()
    pix_bytes = px.tobytes()

    t = Tiff()
    # StripOffsets (tag 273) is patched after layout: reserve by adding
    # it with value 0 first, then rebuild with the real offset.
    def build_with(strip_off):
        t.entries.clear()
        t.add(254, 4, [0])
        t.add(256, 4, [w])
        t.add(257, 4, [h])
        t.add(258, 3, [16])
        t.add(259, 3, [1])
        t.add(262, 3, [32803])          # CFA
        t.add(271, 2, 'FUJIFILM\x00')
        t.add(272, 2, 'X-HF1\x00')      # EXIF model string of the X half
        t.add(274, 3, [8])              # Orientation: stored frame is 90 CW; view 90 CCW
        t.add(273, 4, [strip_off])
        t.add(277, 3, [1])
        t.add(278, 4, [h])
        t.add(279, 4, [len(pix_bytes)])
        t.add(284, 3, [1])
        t.add(33421, 3, [2, 2])         # CFARepeatPatternDim
        t.add(33422, 1, [0, 1, 1, 2])   # CFAPattern RGGB
        t.add(50706, 1, [1, 4, 0, 0])   # DNGVersion
        t.add(50707, 1, [1, 1, 0, 0])   # DNGBackwardVersion
        t.add(50708, 2, 'FUJIFILM X-HF1\x00')
        t.add(50710, 1, [0, 1, 2])      # CFAPlaneColor
        t.add(50711, 3, [1])            # CFALayout
        t.add(50713, 3, [2, 2])         # BlackLevelRepeatDim
        t.add(50714, 5, [(6, 1), (5, 1), (5, 1), (5, 1)])  # BlackLevel R,G1,G2,B
        t.add(50717, 4, [4095])         # WhiteLevel (50715/6 are BlackLevelDeltaH/V)
        t.add(50721, 10, [              # ColorMatrix1 (generic, D65)
            (737, 1000), (-256, 1000), (-84, 1000),
            (-395, 1000), (1182, 1000), (213, 1000),
            (-62, 1000), (186, 1000), (714, 1000)])
        t.add(50728, 5, [rat(1 / gains[0]), (1, 1), rat(1 / gains[2])])  # AsShotNeutral
        t.add(50778, 3, [21])           # CalibrationIlluminant1 = D65
        t.add(50829, 4, [0, 0, h, w])   # ActiveArea
        return t.build()

    head, strip_off = build_with(0)
    head, strip_off2 = build_with(strip_off)
    assert strip_off == strip_off2
    dst.write_bytes(head + pix_bytes)
    print(f'wrote {dst} ({len(head) + len(pix_bytes)} bytes), strip at {strip_off}')


if __name__ == '__main__':
    main()
