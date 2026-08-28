#!/usr/bin/env python3
"""Quick-and-dirty offline renderer for vanilla block-model JSON: parses
elements (incl. single-axis rotations), projects them isometrically with a
painter's sort, flat-shades by face normal, colours by texture key. Only for
judging silhouettes/positions — not a texture preview."""
import json, math, os, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools"))
import pngtool

MODELS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/assets/station_announcer/models/block")

COLORS = {
    "steel": (176, 178, 181), "face": (26, 27, 29), "arm": (150, 152, 155),
    "tube": (196, 198, 201), "lamp": (60, 200, 90), "picto": (30, 150, 70),
    "sign": (16, 110, 50), "sign_exit": (180, 30, 34),
}

FACE_SHADE = {"up": 1.0, "north": 0.85, "south": 0.85, "east": 0.66, "west": 0.66, "down": 0.5}


def rot_point(p, rot):
    if not rot:
        return p
    ox, oy, oz = rot["origin"]
    a = math.radians(rot["angle"])
    c, s = math.cos(a), math.sin(a)
    x, y, z = p[0] - ox, p[1] - oy, p[2] - oz
    k = (1.0 / abs(c)) if rot.get("rescale") else 1.0
    if rot["axis"] == "x":
        y, z = (y * c - z * s) * k, (y * s + z * c) * k
    elif rot["axis"] == "y":
        x, z = (x * c + z * s) * k, (-x * s + z * c) * k
    else:
        x, y = (x * c - y * s) * k, (x * s + y * c) * k
    return (x + ox, y + oy, z + oz)


def faces_of(e, offset):
    x0, y0, z0 = e["from"]; x1, y1, z1 = e["to"]
    rot = e.get("rotation")
    C = {}
    for i, (x, y, z) in enumerate([(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),
                                   (x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]):
        p = rot_point((x, y, z), rot)
        C[i] = (p[0] + offset[0], p[1] + offset[1], p[2] + offset[2])
    quads = {"north": (0,1,2,3), "south": (5,4,7,6), "west": (4,0,3,7),
             "east": (1,5,6,2), "up": (3,2,6,7), "down": (4,5,1,0)}
    out = []
    for name, fc in e["faces"].items():
        tex = fc["texture"].lstrip("#")
        col = COLORS.get(tex, (200, 60, 200))
        sh = FACE_SHADE[name]
        out.append(([C[i] for i in quads[name]], tuple(int(c * sh) for c in col)))
    return out


def rot90xz(p, k):
    x, y, z = p
    for _ in range(k % 4):
        x, z = 16 - z, x
    return (x, y, z)


def render(model_files, out_png, size=640, yaw=35, pitch=28):
    quads = []
    for entry in model_files:
        fname, offset = entry[0], entry[1]
        k = entry[2] if len(entry) > 2 else 0
        m = json.load(open(os.path.join(MODELS, fname + ".json")))
        for e in m["elements"]:
            for q, col in faces_of(e, (0, 0, 0)):
                quads.append(([tuple(a + b for a, b in zip(rot90xz(p, k), offset)) for p in q], col))
    ya, pa = math.radians(yaw), math.radians(pitch)

    def proj(p):
        x, y, z = p
        # yaw about y then pitch about x, orthographic
        X = x * math.cos(ya) + z * math.sin(ya)
        Z = -x * math.sin(ya) + z * math.cos(ya)
        Y = y * math.cos(pa) - Z * math.sin(pa)
        D = y * math.sin(pa) + Z * math.cos(pa)
        return X, Y, D

    pts = [proj(p) for q, _ in quads for p in q]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    span = max(max(xs) - min(xs), max(ys) - min(ys)) or 1
    scale = (size - 40) / span
    ox, oy = min(xs), min(ys)

    img = [[(245, 245, 248, 255)] * size for _ in range(size)]
    order = sorted(range(len(quads)),
                   key=lambda i: sum(proj(p)[2] for p in quads[i][0]) / 4)
    for i in order:
        q, col = quads[i]
        pp = [proj(p) for p in q]
        poly = [((p[0] - ox) * scale + 20, size - ((p[1] - oy) * scale + 20)) for p in pp]
        # scanline fill
        ys_ = [p[1] for p in poly]
        for yy in range(max(0, int(min(ys_))), min(size - 1, int(max(ys_)) + 1)):
            xs_ = []
            n = len(poly)
            for k in range(n):
                (xa, yA), (xb, yB) = poly[k], poly[(k + 1) % n]
                if (yA <= yy < yB) or (yB <= yy < yA):
                    xs_.append(xa + (yy - yA) * (xb - xa) / (yB - yA))
            xs_.sort()
            for j in range(0, len(xs_) - 1, 2):
                for xx in range(max(0, int(xs_[j])), min(size - 1, int(xs_[j + 1]) + 1)):
                    img[yy][xx] = col + (255,)
    pngtool.write_png(out_png, img)
    print("wrote", out_png)


