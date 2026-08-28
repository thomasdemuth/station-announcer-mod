#!/usr/bin/env python3
"""PNG sanity for the el textures: header size/colour type and an IDAT that
actually decompresses to width*height*channels + 1 filter byte per row.
(Validating with pngtool.read_png gives false negatives — limited decoder.)"""
import os, struct, zlib, sys

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "..", "src/main/resources/assets/station_announcer/textures/block")
CH = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}

bad = 0
for name in sorted(os.listdir(TEX)):
    if not name.startswith("el_") or not name.endswith(".png"):
        continue
    data = open(os.path.join(TEX, name), "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", name
    w, h, depth, ctype = struct.unpack(">IIBB", data[16:26])
    idat, i = b"", 8
    while i < len(data):
        ln, typ = struct.unpack(">I", data[i:i + 4])[0], data[i + 4:i + 8]
        if typ == b"IDAT":
            idat += data[i + 8:i + 8 + ln]
        i += 12 + ln
    raw = zlib.decompress(idat)
    want = h * (1 + w * CH[ctype] * depth // 8)
    ok = len(raw) == want and w == h
    print(f"{'ok ' if ok else 'BAD'} {name:28s} {w}x{h} ctype={ctype} raw={len(raw)}/{want}")
    bad += not ok
print("bad pngs:", bad)
sys.exit(1 if bad else 0)
