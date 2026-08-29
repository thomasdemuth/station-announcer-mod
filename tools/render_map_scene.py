#!/usr/bin/env python3
"""Rasterise a System Map+ scene (screen-space polylines exported from the live page)
into a PNG, so the drawn geometry can be inspected without a compositing browser."""
import json, sys, zlib, struct, math

def load(path):
    with open(path) as f:
        return json.load(f)

def render(scene, out, scale=0.5, pad=0):
    W = int(scene["W"] * scale); H = int(scene["H"] * scale)
    paper = (246, 243, 236)
    buf = [[paper[0], paper[1], paper[2]] for _ in range(W * H)]

    def px(x, y, rgb, a=1.0):
        xi, yi = int(x), int(y)
        if 0 <= xi < W and 0 <= yi < H:
            i = yi * W + xi
            for c in range(3):
                buf[i][c] = int(buf[i][c] * (1 - a) + rgb[c] * a)

    def disc(cx, cy, r, rgb, a=1.0):
        r2 = r * r
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                d2 = (x - cx) ** 2 + (y - cy) ** 2
                if d2 <= r2:
                    px(x, y, rgb, a)
                elif d2 <= (r + 1) ** 2:                     # cheap edge feather
                    px(x, y, rgb, a * max(0.0, r + 1 - math.sqrt(d2)))

    def hexrgb(h):
        h = h.lstrip("#")
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

    def stroke(pts, rgb, w):
        r = max(0.6, w / 2)
        for i in range(len(pts) - 1):
            (x0, y0), (x1, y1) = pts[i], pts[i + 1]
            seg = math.hypot(x1 - x0, y1 - y0)
            steps = max(1, int(seg / max(0.4, r * 0.5)))
            for s in range(steps + 1):
                t = s / steps
                disc(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r, rgb)

    for ln in scene["lines"]:
        pts = [(p[0] * scale, p[1] * scale) for p in ln["p"]]
        stroke(pts, hexrgb(ln["hex"]), max(1.6, ln["w"] * scale))

    for g in scene["glyphs"]:
        cx, cy = g["x"] * scale, g["y"] * scale
        r = (7.5 if g["main"] else 6.0) * scale * 1.6
        if g.get("cap") and g["n"] >= 3:
            disc(cx, cy, r * 1.45, (28, 31, 35)); disc(cx, cy, r * 1.45 - 2.4 * scale * 1.6, (255, 255, 255))
        elif g.get("cap"):
            # capsule ACROSS the bundle: long axis perpendicular to the track direction
            dx, dy = g["dir"]
            nx, ny = -dy, dx
            span = r * 1.5
            for t in (-1, -0.5, 0, 0.5, 1):
                disc(cx + nx * span * t, cy + ny * span * t, r, (28, 31, 35))
            for t in (-1, -0.5, 0, 0.5, 1):
                disc(cx + nx * span * t, cy + ny * span * t, r - 2.4 * scale * 1.6, (255, 255, 255))
        elif g.get("full", True):
            disc(cx, cy, r, (28, 31, 35)); disc(cx, cy, r - 2.4 * scale * 1.6, (255, 255, 255))
        else:                                                # express-skipped: open ring
            disc(cx, cy, r * 0.78, (28, 31, 35)); disc(cx, cy, r * 0.78 - 1.7 * scale * 1.6, paper)

    raw = b"".join(b"\x00" + bytes(v for p in buf[y * W:(y + 1) * W] for v in p) for y in range(H))
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))
    with open(out, "wb") as f:
        f.write(png)
    print(f"{out}: {W}x{H}, {len(scene['lines'])} lines, {len(scene['glyphs'])} glyphs")

if __name__ == "__main__":
    render(load(sys.argv[1]), sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 0.5)
