#!/usr/bin/env python3
"""Offline isometric renderer for the el STRUCTURE set (item set 1).

Derived from the scratchpad render_blockmodel.py used for the fare gates:
parses vanilla block-model JSON (elements + single-axis rotations), projects
orthographically with a painter's sort, flat-shades by face normal, colours
by texture key.  Cut-out textures (the lattice) are drawn STIPPLED so the
"is it actually open?" question can be judged from the render.

Usage:  python3 el_set1_renders/render_el_set1.py [before|after]
"""
import json, math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, os.path.join(ROOT, "tools"))
import pngtool

MODELS = os.environ.get(
    "EL_MODELS",
    os.path.join(ROOT, "src/main/resources/assets/station_announcer/models/block"))

COLORS = {
    "body": (33, 82, 56), "galv": (172, 175, 178),
    "lattice": (28, 70, 48),
    "wood": (46, 38, 31), "plate": (66, 68, 71),
    "screen": (216, 206, 186), "corru": (33, 82, 56), "roof": (128, 42, 38),
    "board": (18, 19, 21),
}
STIPPLE = {"lattice"}

FACE_SHADE = {"up": 1.0, "north": 0.85, "south": 0.85,
              "east": 0.66, "west": 0.66, "down": 0.5}


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
    for i, (x, y, z) in enumerate([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                                   (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]):
        C[i] = rot_point((x, y, z), rot)
    quads = {"north": (0, 1, 2, 3), "south": (5, 4, 7, 6), "west": (4, 0, 3, 7),
             "east": (1, 5, 6, 2), "up": (3, 2, 6, 7), "down": (4, 5, 1, 0)}
    out = []
    for name, fc in e["faces"].items():
        tex = fc["texture"].lstrip("#")
        col = COLORS.get(tex, (200, 60, 200))
        sh = FACE_SHADE[name]
        # Minecraft shades every north-facing quad identically, so relief only
        # reads in game through the TEXTURE window a face samples. Mark the two
        # riveted windows of el_steel (u>=12.5 = the vertical rivet ladder,
        # v around 3..4 = a horizontal rivet seam) so the render shows where
        # the rivets land instead of one flat green.
        u0, v0, _, v1 = fc.get("uv", [0, 0, 16, 16])
        if tex in ("body", "galv"):
            if u0 >= 12.5:
                sh *= 1.22
            elif min(v0, v1) <= 4.6 <= max(v0, v1) and abs(v1 - v0) < 3:
                sh *= 1.12
        out.append(([C[i] for i in quads[name]],
                    tuple(min(255, int(c * sh)) for c in col), tex in STIPPLE))
    return out


def rot90xz(p, k):
    x, y, z = p
    for _ in range(k % 4):
        x, z = 16 - z, x
    return (x, y, z)


def render(entries, out_png, size=760, yaw=35, pitch=28, bg=(245, 245, 248)):
    quads = []
    for entry in entries:
        fname, offset = entry[0], entry[1]
        k = entry[2] if len(entry) > 2 else 0
        m = json.load(open(os.path.join(MODELS, fname + ".json")))
        for e in m["elements"]:
            for q, col, stip in faces_of(e):
                quads.append(([tuple(a + b for a, b in zip(rot90xz(p, k), offset))
                               for p in q], col, stip))
    ya, pa = math.radians(yaw), math.radians(pitch)

    def proj(p):
        x, y, z = p
        X = x * math.cos(ya) + z * math.sin(ya)
        Z = -x * math.sin(ya) + z * math.cos(ya)
        Y = y * math.cos(pa) - Z * math.sin(pa)
        D = y * math.sin(pa) + Z * math.cos(pa)
        return X, Y, D

    pts = [proj(p) for q, _, _ in quads for p in q]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    span = max(max(xs) - min(xs), max(ys) - min(ys)) or 1
    scale = (size - 40) / span
    ox, oy = min(xs), min(ys)

    img = [[bg + (255,)] * size for _ in range(size)]
    order = sorted(range(len(quads)),
                   key=lambda i: sum(proj(p)[2] for p in quads[i][0]) / 4)
    for i in order:
        q, col, stip = quads[i]
        pp = [proj(p) for p in q]
        poly = [((p[0] - ox) * scale + 20, size - ((p[1] - oy) * scale + 20)) for p in pp]
        ys_ = [p[1] for p in poly]
        for yy in range(max(0, int(min(ys_))), min(size - 1, int(max(ys_)) + 1)):
            xs_ = []
            n = len(poly)
            for k2 in range(n):
                (xa, yA), (xb, yB) = poly[k2], poly[(k2 + 1) % n]
                if (yA <= yy < yB) or (yB <= yy < yA):
                    xs_.append(xa + (yy - yA) * (xb - xa) / (yB - yA))
            xs_.sort()
            for j in range(0, len(xs_) - 1, 2):
                for xx in range(max(0, int(xs_[j])), min(size - 1, int(xs_[j + 1]) + 1)):
                    if stip and ((xx + yy) % 3 or (xx * 2 + yy) % 5 == 0):
                        continue
                    img[yy][xx] = col + (255,)
    pngtool.write_png(out_png, img)
    print("wrote", os.path.basename(out_png))


B = 16  # one block in model px


def column_stack(x, z, height, solid=True, paint="green"):
    """Bottom block gets the foot, top block the cap (ColumnBlock up/down)."""
    kind = "solid" if solid else "lattice"
    out = []
    for i in range(height):
        y = i * B
        out.append((f"el_column_shaft_{kind}_{paint}", (x, y, z)))
        if i == 0:
            out.append((f"el_column_foot_{paint}", (x, y, z)))
        if i == height - 1:
            out.append((f"el_column_cap_{paint}", (x, y, z)))
    return out


def bent(deck="ties", truss=False, paint="green", bays=5, colspacing=4, tall=3,
         solid=True):
    """A stretch of el: two column stacks, a girder run over them (braced
    where a column is below), and a deck course on top."""
    els = []
    top = tall * B
    for c in (0, colspacing):
        els += column_stack(c * B, 0, tall, solid=solid, paint=paint)
    beam = ("el_truss_" if truss else "el_girder_") + paint
    for i in range(bays):
        els.append((beam, (i * B, top, 0)))
        if i in (0, colspacing):
            els.append((f"el_girder_brace_{paint}", (i * B, top, 0)))
    for i in range(bays):
        els.append((f"el_deck_{deck}_model", (i * B, top + B, 0)))
    return els


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "after"
    o = lambda n: os.path.join(HERE, f"{tag}_{n}.png")

    # 3/4 aerial of a whole stretch — the "does it read like Jamaica Ave" shot
    render(bent(), o("bent_3q"), yaw=32, pitch=24)
    # street level, looking up from below the structure
    render(bent(), o("bent_street"), yaw=28, pitch=-22)
    # straight-on side elevation: the apron/table test
    render(bent(), o("bent_side"), yaw=0, pitch=6)
    # truss version + plate deck
    render(bent(deck="plate", truss=True), o("truss_plate_3q"), yaw=32, pitch=24)
    render(bent(deck="plate", truss=True), o("truss_street"), yaw=28, pitch=-22)
    # the light "Marcy Av" build: lattice columns + truss + tie deck
    render(bent(truss=True, solid=False, tall=4), o("lattice_bent"), yaw=30, pitch=18)
    # single-block close-ups
    render([("el_girder_green", (0, 0, 0)), ("el_girder_green", (16, 0, 0)),
            ("el_girder_green", (32, 0, 0))], o("girder_close"), yaw=18, pitch=10)
    render([("el_truss_green", (0, 0, 0)), ("el_truss_green", (16, 0, 0)),
            ("el_truss_green", (32, 0, 0))], o("truss_close"), yaw=18, pitch=10)
    render(column_stack(0, 0, 3) + [("el_girder_green", (0, 48, 0)),
                                    ("el_girder_brace_green", (0, 48, 0)),
                                    ("el_girder_green", (16, 48, 0)),
                                    ("el_girder_green", (-16, 48, 0))],
           o("brace_close"), yaw=12, pitch=8)
    render(column_stack(0, 0, 3, solid=False)
           + column_stack(40, 0, 3, solid=True),
           o("columns"), yaw=25, pitch=12)
    # deck seen from directly beneath (the street view that matters)
    render([(f"el_deck_ties_model", (i * B, 0, 0)) for i in range(3)],
           o("deck_ties_under"), yaw=20, pitch=-40)
    render([(f"el_deck_plate_model", (i * B, 0, 0)) for i in range(3)],
           o("deck_plate_under"), yaw=20, pitch=-40)


if __name__ == "__main__":
    main()
