"""Minimal dependency-free PNG read/write + crop/scale.

Used by tools/convert_openbve_m7.py to slice and re-tone the openBVE donor
textures. Rows are lists of (r, g, b, a) tuples; everything is 8-bit.
"""

import struct
import zlib


def read_png(path):
    """Return (w, h, rows) where rows is a list of lists of (r,g,b,a) tuples."""
    with open(path, "rb") as fh:
        data = fh.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos = 8
    idat = b""
    plte = None
    trns = None
    w = h = bitdepth = colortype = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            w, h, bitdepth, colortype, comp, filt, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "interlaced PNG not supported"
        elif typ == b"PLTE":
            plte = body
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
    raw = zlib.decompress(idat)
    chans = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    assert bitdepth == 8, f"bitdepth {bitdepth} unsupported"
    bpp = chans
    stride = w * bpp
    out = bytearray(stride * h)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                c = prev[i - bpp] if i >= bpp else 0
                b = prev[i]
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line

    rows = []
    for y in range(h):
        row = []
        base = y * stride
        for x in range(w):
            i = base + x * bpp
            if colortype == 6:
                row.append((out[i], out[i + 1], out[i + 2], out[i + 3]))
            elif colortype == 2:
                row.append((out[i], out[i + 1], out[i + 2], 255))
            elif colortype == 0:
                g = out[i]; row.append((g, g, g, 255))
            elif colortype == 4:
                g = out[i]; row.append((g, g, g, out[i + 1]))
            elif colortype == 3:
                idx = out[i]
                r, g, b = plte[idx * 3], plte[idx * 3 + 1], plte[idx * 3 + 2]
                a = trns[idx] if trns and idx < len(trns) else 255
                row.append((r, g, b, a))
        rows.append(row)
    return w, h, rows


def write_png(path, rows):
    h = len(rows)
    w = len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for px in row:
            raw += bytes(px[:4])
    comp = zlib.compress(bytes(raw), 6)

    def chunk(t, b):
        c = struct.pack(">I", len(b)) + t + b
        return c + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)

    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n")
        fh.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)))
        fh.write(chunk(b"IDAT", comp))
        fh.write(chunk(b"IEND", b""))


def crop(rows, x0, y0, x1, y1):
    return [r[x0:x1] for r in rows[y0:y1]]


def scale_nn(rows, fx, fy):
    out = []
    for y in range(len(rows) * fy):
        src = rows[y // fy]
        out.append([src[x // fx] for x in range(len(src) * fx)])
    return out
