#!/usr/bin/env python3
"""Offline renderer for vanilla block-model JSON: parses elements (incl.
single-axis rotations with rescale), projects them isometrically with a
painter's sort, flat-shades by face normal, colours by texture key.
Only for judging silhouettes/positions — not a texture preview.

Scene entries are (model_name, offset_px, [quarter_turns_y]); a quarter turn
is the blockstate's y rotation in units of 90°.
"""
import json, math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pngtool

MODELS = os.path.join(HERE, "..", "src/main/resources/assets/station_announcer/models/block")

COLORS = {
    "steel": (176, 178, 181), "face": (26, 27, 29), "arm": (150, 152, 155),
    "tube": (196, 198, 201), "lamp": (60, 200, 90), "picto": (30, 150, 70),
    "sign": (16, 110, 50), "sign_exit": (180, 30, 34),
    # el family
    "body": (40, 96, 66), "galv": (176, 178, 181), "roof": (150, 56, 50),
    "corru": (52, 112, 80), "corru_s": (166, 170, 174), "lattice": (36, 88, 60),
    "screen": (206, 196, 176), "glass": (168, 186, 192), "mesh": (150, 154, 158),
    "board": (22, 23, 25), "planks": (120, 92, 62), "house": (40, 96, 66),
    "housec": (206, 196, 176), "glaze": (168, 186, 192), "edge": (150, 150, 150),
    "soffit": (190, 180, 160), "exit": (22, 23, 25), "wood": (46, 38, 31),
    "plate": (66, 68, 71),
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


def faces_of(e):
    x0, y0, z0 = e["from"]; x1, y1, z1 = e["to"]
    rot = e.get("rotation")
    C = {}
    for i, (x, y, z) in enumerate([(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),
                                   (x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]):
        C[i] = rot_point((x, y, z), rot)
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
    """The blockstate's y rotation: k quarter turns clockwise from above."""
    x, y, z = p
    for _ in range(k % 4):
        x, z = 16 - z, x
    return (x, y, z)


def render(model_files, out_png, size=760, yaw=35, pitch=28, models_dir=None):
    quads = []
    mdir = models_dir or MODELS
    for entry in model_files:
        fname, offset = entry[0], entry[1]
        k = entry[2] if len(entry) > 2 else 0
        m = json.load(open(os.path.join(mdir, fname + ".json")))
        for e in m["elements"]:
            for q, col in faces_of(e):
                quads.append(([tuple(a + b for a, b in zip(rot90xz(p, k), offset))
                               for p in q], col))
    ya, pa = math.radians(yaw), math.radians(pitch)

    def proj(p):
        x, y, z = p
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
    # per-pixel depth buffer: a painter's sort on quad centroids puts thin
    # members that hide UNDER a deck on top of it (a false "seam"), and the
    # whole point of these previews is to trust what you see. The projection
    # is orthographic, so depth is affine in screen space — fit it per quad.
    zbuf = [[-1e9] * size for _ in range(size)]
    for q, col in quads:
        pp = [proj(p) for p in q]
        poly = [((p[0] - ox) * scale + 20, size - ((p[1] - oy) * scale + 20)) for p in pp]
        (x0_, y0_), (x1_, y1_), (x2_, y2_) = poly[0], poly[1], poly[2]
        d0, d1, d2 = pp[0][2], pp[1][2], pp[2][2]
        det = (x1_ - x0_) * (y2_ - y0_) - (x2_ - x0_) * (y1_ - y0_)
        if abs(det) < 1e-9:
            continue
        a = ((d1 - d0) * (y2_ - y0_) - (d2 - d0) * (y1_ - y0_)) / det
        b = ((d2 - d0) * (x1_ - x0_) - (d1 - d0) * (x2_ - x0_)) / det
        c = d0 - a * x0_ - b * y0_
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
                    d = a * xx + b * yy + c
                    if d < zbuf[yy][xx]:
                        continue
                    zbuf[yy][xx] = d
                    img[yy][xx] = col + (255,)
    pngtool.write_png(out_png, img)
    print("wrote", out_png)
