#!/usr/bin/env python3
"""NYC elevated station kit v2 - GENERATED assets (never hand-edit the output).

Rebuilt from scratch 2026-09-03 against Thomas's photos (Nereid Av, Prospect
Av, 30 Av, 25 Av, Westchester Sq): Dual-Contracts green steel, red standing-
seam gable roofs, lattice (Warren) truss friezes. One kit, one style.

Blocks so far:
  el_roof   - gable canopy: AXIS = ridge direction, any length, any width up
              to 7. Each cell knows its SIDE (which eave it slopes down to, or
              CROWN for the middle row of an odd width) and LEVEL (rows in
              from the nearest eave, 0..2); the Java block computes both by
              walking its crosswise neighbours. Level-0 cells hang the lattice
              frieze; open run ends close with a truss panel. Every cell has a
              rafter tie at x/z 7..9 on its underside, so a pids_pole (2 px,
              x/z 7..9) hung under any cell lands on steel.

ROTATION FACTS used here (re-derived, and matching the old kit's hard-won rule):
  element rotation +A about x with rescale:true maps a point (dy, dz) relative
  to the origin to (dy*cos - dz*sin, ...) then scales the non-axis components by
  1/cos(A): so a slab authored flat from z 0..16 lands on z 0..16 exactly and
  DESCENDS by 16*tan(A) toward +z. -A rises toward +z. The slab's top surface is
  skewed by thickness*tan(A) along z (inherent to any thick sloped slab), which
  is why neighbouring cells get a 0.1 px THICKNESS PARITY difference: the 0.6 px
  strip where two cells' surfaces overlap is then never coplanar.
"""

import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

# submodules `import gen_el2_assets`; when this file runs as __main__ make that
# name resolve to THIS module instance so WRITTEN/verify see their models
sys.modules.setdefault("gen_el2_assets", sys.modules[__name__])

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data/station_announcer")
MODELS = os.path.join(ASSETS, "models/block")
ITEM_MODELS = os.path.join(ASSETS, "models/item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
TEXTURES = os.path.join(ASSETS, "textures/block")
MOD = "station_announcer"

PITCH_DEG = 22.5
RISE = 16 * math.tan(math.radians(PITCH_DEG))     # 6.627 px per block of run
BASE = 6.0            # eave underside height at the eave line, level 0
DECK_T = 1.6          # roof sheet thickness
PARITY_T = 0.1        # thickness added on odd levels (see module docstring)
MAX_LEVEL = 2         # 7-wide max: BASE + 3*RISE + deck < 32

WRITTEN = []          # every model name written, for verify()


# =====================================================================
# texture helpers (RGBA everywhere - pngtool writes px[:4])
# =====================================================================

def canvas(n, colour):
    return [[tuple(colour) + ((255,) if len(colour) == 3 else ())] * n for _ in range(n)]


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, d):
    return (clamp(c[0] + d), clamp(c[1] + d), clamp(c[2] + d), 255)


def put(rows, x, y, c):
    n = len(rows)
    rows[y % n][x % n] = c


def write_png(name, rows):
    pngtool.write_png(os.path.join(TEXTURES, name + ".png"), rows)


GREEN = (58, 108, 88)        # Dual Contracts green, weathered
RED = (142, 54, 46)          # standing-seam roof red
CREAM = (214, 206, 186)


def tex_green(seed="green"):
    """Brushed steel paint, 32 px. Vertical tonal drift only (stacks cleanly);
    a rivet ladder in columns 1..2 for faces that want it (u 1..3)."""
    rng = random.Random(seed)
    rows = canvas(32, GREEN)
    for y in range(32):
        for x in range(32):
            d = rng.uniform(-7, 7) + 4 * math.sin(x * 0.9)
            put(rows, x, y, shade(GREEN, d))
    for y in range(2, 32, 4):
        put(rows, 1, y, shade(GREEN, 26))
        put(rows, 2, y, shade(GREEN, 10))
        put(rows, 1, y + 1, shade(GREEN, -6))
        put(rows, 2, y + 1, shade(GREEN, -22))
    return rows


def tex_roof_red():
    """Standing-seam sheet: seams every 8 texels as a lit ridge + shadow; the
    seams run along v (down the slope when the up face maps u=x, v=z)."""
    rng = random.Random("roof")
    rows = canvas(32, RED)
    for y in range(32):
        for x in range(32):
            put(rows, x, y, shade(RED, rng.uniform(-5, 5) + 3 * math.sin(y * 0.5)))
    for x in range(0, 32, 8):
        for y in range(32):
            put(rows, x, y, shade(RED, 30))
            put(rows, x + 1, y, shade(RED, -26))
            put(rows, x + 7, y, shade(RED, -10))
    return rows


def tex_soffit():
    """Cream beadboard underside (Thomas: paint the roof underside creme):
    4-texel boards, a groove + highlight each."""
    rng = random.Random("soffit")
    base = CREAM
    rows = canvas(32, base)
    for y in range(32):
        for x in range(32):
            put(rows, x, y, shade(base, rng.uniform(-4, 4)))
    for x in range(0, 32, 4):
        for y in range(32):
            put(rows, x, y, shade(base, -30))
            put(rows, x + 1, y, shade(base, 12))
    return rows


def tex_lattice():
    """Warren-truss frieze, 32 px CUTOUT: top and bottom chords (rows 0..3 and
    28..31) with a zigzag web of 3 px diagonals - one V per 16 texels, so it
    tiles along a run; a gusset dot where diagonals meet the chords."""
    rows = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    steel = shade(GREEN, 0)
    for y in list(range(0, 4)) + list(range(28, 32)):
        for x in range(32):
            put(rows, x, y, shade(GREEN, 12 if y in (0, 28) else (-10 if y in (3, 31) else 0)))
    # web: from (0,4) down to (8,28) up to (16,4) ... period 16
    for x in range(32):
        t = (x % 16) / 8.0            # 0..2 across one V
        yc = 4 + (t if t <= 1 else 2 - t) * 24
        for dy in (-1, 0, 1, 2):
            y = int(round(yc)) + dy
            if 3 < y < 28:
                put(rows, x, y, shade(GREEN, 10 if dy == -1 else (-14 if dy == 2 else 0)))
    for x in (0, 8, 16, 24):
        for y in (2, 3, 28, 29):
            put(rows, x, y, shade(GREEN, 24))
            put(rows, x + 1, y, shade(GREEN, 24))
    return rows


# =====================================================================
# element toolkit
# =====================================================================

def box(x0, y0, z0, x1, y1, z1, tex, uv=None, faces=None, rotation=None, shade_=None, cull=None):
    """An element with explicit uv on every drawn face. `faces` limits which
    faces exist; `uv` may be a dict per face or one list; defaults derive a
    face-sized window from the box so textures land 1:1 where possible."""
    names = faces or ["north", "south", "east", "west", "up", "down"]
    out = {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": {}}
    for f in names:
        if isinstance(uv, dict):
            w = uv.get(f)
        else:
            w = uv
        if w is None:
            if f in ("north", "south"):
                w = [x0, 16 - y1, x1, 16 - y0]
            elif f in ("east", "west"):
                w = [z0, 16 - y1, z1, 16 - y0]
            else:
                w = [x0, z0, x1, z1]
            w = [max(0, min(16, v)) for v in w]
            if w[0] == w[2]:
                w[2] = w[0] + 0.5
            if w[1] == w[3]:
                w[3] = w[1] + 0.5
        face = {"uv": w, "texture": tex if isinstance(tex, str) else tex.get(f, tex.get("*"))}
        if cull and f in cull:
            face["cullface"] = f
        out["faces"][f] = face
    if rotation:
        out["rotation"] = rotation
    if shade_ is False:
        out["shade"] = False
    return out


def mirror_z(el):
    """Mirror an element across z=8: geometry, rotation sign, north<->south."""
    e = json.loads(json.dumps(el))
    f, t = e["from"], e["to"]
    e["from"] = [f[0], f[1], 16 - t[2]]
    e["to"] = [t[0], t[1], 16 - f[2]]
    faces = {}
    for name, face in e["faces"].items():
        new = {"north": "south", "south": "north"}.get(name, name)
        if name in ("east", "west", "up", "down"):
            # u axis of these faces runs along z: flip it
            u0, v0, u1, v1 = face["uv"]
            face = dict(face, uv=[u1, v0, u0, v1])
        if "cullface" in face:
            face["cullface"] = {"north": "south", "south": "north"}.get(face["cullface"], face["cullface"])
        faces[new] = face
    e["faces"] = faces
    if "rotation" in e:
        r = dict(e["rotation"])
        o = r["origin"]
        r["origin"] = [o[0], o[1], 16 - o[2]]
        if r["axis"] == "x":
            r["angle"] = -r["angle"]
        e["rotation"] = r
    return e


NEAR_SIDE = set()   # model names whose elements are mirrored to the near (z 0..) side at write time


def model(name, elements, textures, parent=None, ao=None):
    if name in NEAR_SIDE:
        elements = [mirror_z(e) for e in elements]
    obj = {}
    if parent:
        obj["parent"] = parent
    if ao is False:
        obj["ambientocclusion"] = False
    obj["textures"] = {k: f"{MOD}:block/{v}" for k, v in textures.items()}
    obj["elements"] = elements
    os.makedirs(MODELS, exist_ok=True)
    with open(os.path.join(MODELS, name + ".json"), "w") as fh:
        json.dump(obj, fh, indent=1)
    WRITTEN.append(name)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=1)
        fh.write("\n")


# =====================================================================
# EL ROOF
# =====================================================================
#
# Authored for AXIS = x (ridge along x, slope across z), SIDE = pos, i.e. the
# roof DESCENDS toward +z: the eave (and frieze, at level 0) is at z = 16, the
# high edge at z = 0. `neg` is mirror_z of it. The crown rises from both
# boundaries to a ridge on the cell's centre line.

TEX_ROOF = {"green": "el2_green", "red": "el2_roof_red", "soffit": "el2_soffit",
            "lattice": "el2_lattice"}


def deck_slab(level, z0, z1, descend_to_pos, thick):
    """Sloped roof sheet over z0..z1 at the given level. The high edge sits at
    y = BASE + RISE*(level+1) (for a full-cell slab)."""
    run = z1 - z0
    rise = run * math.tan(math.radians(PITCH_DEG))
    low = BASE + RISE * level
    if descend_to_pos:
        # high edge fixed at z0 whatever the run (an overhang lowers the tip)
        y_high = low + RISE * (16.0 / 16.0) if z0 == 0 else low + rise
        origin_z = z0
        angle = PITCH_DEG
        yb = y_high
    else:
        origin_z = z0
        angle = -PITCH_DEG
        yb = low
    # uv clamped to the sprite: an overhang slab is 18 px long, and a v past
    # 16 samples the NEXT atlas sprite (with the down face's 90-degree
    # rotation that showed as a wired-glass slit along every cell edge)
    u0, u1 = max(0, z0), min(16, z1)
    el = box(0, yb, z0, 16, yb + thick, z1,
             {"up": "#red", "down": "#soffit", "*": "#green"},
             uv={"up": [0, u0, 16, u1], "down": [0, u0, 16, u1],
                 "north": [0, 8, 16, 8 + thick], "south": [0, 8, 16, 8 + thick],
                 "east": [u0, 8, u1, 8 + thick], "west": [u0, 8, u1, 8 + thick]},
             rotation={"origin": [8, yb, origin_z], "axis": "x", "angle": angle, "rescale": True})
    el["faces"]["down"]["rotation"] = 90
    return el


def rafter_tie():
    """Steel on the underside: HALF-TIES at both block edges (x 0..1 and
    15..16, crosswise) so neighbouring cells make one 2 px tie on the block-
    edge grid the wall posts sit on, plus a slimmer purlin along the ridge at
    the row centre (z 7..9) so a pids_pole at the cell centre still lands on
    steel."""
    g = "#green"
    return [
        box(0, 0, 0, 1, 1.6, 16, g, uv={"up": [0, 0, 1, 16], "down": [0, 0, 1, 16], "east": [0, 4, 16, 5.6],
                                        "west": [0, 4, 16, 5.6], "north": [0, 4, 1, 5.6], "south": [0, 4, 1, 5.6]},
            faces=["up", "down", "east", "north", "south"]),
        box(15, 0, 0, 16, 1.6, 16, g, uv={"up": [15, 0, 16, 16], "down": [15, 0, 16, 16], "east": [0, 4, 16, 5.6],
                                          "west": [0, 4, 16, 5.6], "north": [15, 4, 16, 5.6], "south": [15, 4, 16, 5.6]},
            faces=["up", "down", "west", "north", "south"]),
        box(0, 0.3, 7, 16, 1.5, 9, g, uv={"down": [0, 7, 16, 9], "north": [0, 4, 16, 5.2], "south": [0, 4, 16, 5.2],
                                          "up": [0, 7, 16, 9]},
            faces=["down", "north", "south", "up"]),
    ]


def frieze_pos():
    """Lattice frieze + eave chords hanging below the eave at z 14.5..16
    (pos side). Lattice is a two-sided cutout panel at z 15..15.4."""
    els = []
    # bottom chord and top chord (the eave beam the deck sits on)
    els.append(box(0, 0, 14.4, 16, 1.6, 16, "#green",
                   uv={"north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6], "up": [0, 0, 16, 1.6],
                       "down": [0, 0, 16, 1.6], "east": [0, 4, 1.6, 5.6], "west": [0, 4, 1.6, 5.6]},
                   faces=["north", "south", "down", "up", "east", "west"]))
    els.append(box(0, BASE - 1.6, 14.4, 16, BASE + 0.4, 16, "#green",
                   uv={"north": [0, 4, 16, 6], "south": [0, 4, 16, 6], "up": [0, 0, 16, 1.6],
                       "down": [0, 0, 16, 1.6], "east": [0, 4, 1.6, 6], "west": [0, 4, 1.6, 6]}))
    # lattice web between the chords, drawn on both faces
    els.append(box(0, 1.6, 15, 16, BASE - 1.6, 15.4, "#lattice",
                   uv={"north": [0, 4, 16, 12], "south": [0, 4, 16, 12]},
                   faces=["north", "south"]))
    # fascia board covering the sheet edge at the eave (2 px overhang past the block)
    els.append(box(0, BASE - 1.8, 18.2, 16, BASE + DECK_T + 0.2, 19.2, "#green",
                   uv={"north": [0, 6, 16, 9], "south": [0, 6, 16, 9], "up": [0, 0, 16, 1],
                       "down": [0, 0, 16, 1], "east": [0, 6, 1, 9], "west": [0, 6, 1, 9]}))
    return els


def end_panel(level, side, at_neg_x):
    """Truss panel closing an open run end: a lattice rectangle from the tie up
    to the LOW underside of this cell's deck, plus a sloped top chord under
    the deck. `side` in pos/neg/crown."""
    x0, x1 = (0, 1.2) if at_neg_x else (14.8, 16)
    els = []
    low = BASE + RISE * level
    top = low - 0.2 if side != "crown" else low - 0.2
    if top > 2.4:
        # v clamped at 0: a level-2 panel is taller than the sprite (atlas bleed otherwise)
        els.append(box(x0, 1.6, 1, x1, top, 15, "#lattice",
                       uv={"east": [1, max(0, 16 - top), 15, 14.4], "west": [1, max(0, 16 - top), 15, 14.4]},
                       faces=["east", "west"]))
    # sloped top chord(s) hugging the deck underside
    if side == "crown":
        for z0, z1, desc in ((0, 8, False), (8, 16, True)):
            els.append(chord(x0, x1, level, z0, z1, desc, half=True))
    else:
        els.append(chord(x0, x1, level, 0, 16, side == "pos"))
    # 2 px gable overhang: a deck strip outside the block, same profile
    sx0, sx1 = (-2, 0) if at_neg_x else (16, 18)
    if side == "crown":
        for z0, z1, desc in ((0, 8, False), (8, 16, True)):
            strip = deck_slab(level, z0, z1, desc, DECK_T)
            strip["from"][0], strip["to"][0] = sx0, sx1
            els.append(strip)
    else:
        strip = deck_slab(level, 0, 16, side == "pos", DECK_T)
        strip["from"][0], strip["to"][0] = sx0, sx1
        els.append(strip)
    # end post: at the eave for the level-0 wings, a king post under the peak
    # of a crown, nothing for interior rows (their web is inside the roof)
    if side == "crown":
        els.append(box(x0, 0, 7.4, x1, low + RISE / 2 - 0.6, 8.6, "#green", uv=[0, 0, 1.2, 16]))
    elif level == 0:
        z0, z1 = (14.8, 16) if side == "pos" else (0, 1.2)
        els.append(box(x0, 0, z0, x1, low - 0.2, z1, "#green", uv=[0, 0, 1.2, 16]))
    return els


def chord(x0, x1, level, z0, z1, descend_to_pos, half=False):
    run = z1 - z0
    rise = run * math.tan(math.radians(PITCH_DEG))
    low = BASE + RISE * level
    if descend_to_pos:
        yb = low + rise - 1.4
        angle = PITCH_DEG
    else:
        yb = low - 1.4
        angle = -PITCH_DEG
    return box(x0, yb, z0, x1, yb + 1.4, z1, "#green",
               uv={"east": [z0, 4, z1, 5.4], "west": [z0, 4, z1, 5.4], "up": [x0, z0, x1, z1],
                   "down": [x0, z0, x1, z1], "north": [x0, 4, x1, 5.4], "south": [x0, 4, x1, 5.4]},
               rotation={"origin": [8, yb, z0], "axis": "x", "angle": angle, "rescale": True})


def roof_models():
    tex = TEX_ROOF
    for level in range(MAX_LEVEL + 1):
        thick = DECK_T + (PARITY_T if level % 2 else 0)
        # --- pos (descends toward +z), then neg as its mirror
        pos = [deck_slab(level, 0, 19 if level == 0 else 16, True, thick), *rafter_tie()]
        if level == 0:
            pos += frieze_pos()
        model(f"el_roof_pos_{level}", pos, tex, ao=False)
        model(f"el_roof_neg_{level}", [mirror_z(e) for e in pos], tex, ao=False)
        # --- crown: two half slabs meeting at a ridge cap on the centre line
        crown = [deck_slab(level, -3 if level == 0 else 0, 8, False, thick),
                 deck_slab(level, 8, 19 if level == 0 else 16, True, thick), *rafter_tie()]
        peak = BASE + RISE * level + RISE / 2
        crown.append(box(0, peak + thick - 0.6, 6.6, 16, peak + thick + 1.0, 9.4, "#green",
                         uv={"up": [0, 6.6, 16, 9.4], "down": [0, 6.6, 16, 9.4],
                             "north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6],
                             "east": [6.6, 4, 9.4, 5.6], "west": [6.6, 4, 9.4, 5.6]}))
        if level == 0:
            crown += frieze_pos() + [mirror_z(e) for e in frieze_pos()]
        model(f"el_roof_crown_{level}", crown, tex, ao=False)
        # --- ridge half-caps for the even-width middle pair (pos at z 0..1, neg at z 15..16)
        for side in ("pos", "neg"):
            peak = BASE + RISE * (level + 1)
            cap = box(0, peak + thick - 0.6, 0, 16, peak + thick + 1.0, 1.4, "#green",
                      uv={"up": [0, 0, 16, 1.4], "down": [0, 0, 16, 1.4],
                          "north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6],
                          "east": [0, 4, 1.4, 5.6], "west": [0, 4, 1.4, 5.6]})
            model(f"el_roof_ridge_{side}_{level}", [cap if side == "pos" else mirror_z(cap)], tex)
        # --- run-end truss panels
        for side in ("pos", "neg", "crown"):
            for end, at_neg in (("neg", True), ("pos", False)):
                model(f"el_roof_end_{side}_{level}_{end}", end_panel(level, side, at_neg), tex)


def rot_y_el(el, quarter):
    """Rotate an UNROTATED element about the block centre by quarter*90 deg
    clockwise (a blockstate y rotation). Used to turn a frieze onto the x edge."""
    e = json.loads(json.dumps(el))
    assert "rotation" not in e
    for _ in range(quarter):
        f, t = e["from"], e["to"]
        e["from"] = [16 - t[2], f[1], f[0]]
        e["to"] = [16 - f[2], t[1], t[0]]
        m = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}
        faces = {}
        for name, face in e["faces"].items():
            face = dict(face)
            if "cullface" in face:
                face["cullface"] = m[face["cullface"]]
            faces[m[name]] = face
        e["faces"] = faces
    return e


def step_box(x0, z0, x1, z1, y_lo, y_hi):
    return box(x0, y_lo, z0, x1, y_hi, z1, {"up": "#red", "down": "#soffit", "*": "#green"},
               uv={"up": [x0, z0, x1, z1], "down": [x0, z0, x1, z1],
                   "north": [x0, 8, x1, 8 + min(8, y_hi - y_lo)], "south": [x0, 8, x1, 8 + min(8, y_hi - y_lo)],
                   "east": [z0, 8, z1, 8 + min(8, y_hi - y_lo)], "west": [z0, 8, z1, 8 + min(8, y_hi - y_lo)]})


def deck_slab_x(level, x0, x1, descend_to_pos, thick, z0, z1):
    """A deck piece sloping along X (rotation about z) over z0..z1: descending
    toward +x from its high edge at x0, or rising toward +x from `low` at x0.
    Right-hand rotation about z by +A RISES toward +x, so descending is -A."""
    s_ = math.tan(math.radians(PITCH_DEG))
    low = BASE + RISE * level
    if descend_to_pos:
        yb = low + (16 - x0) * s_
        angle = -PITCH_DEG
    else:
        yb = low
        angle = PITCH_DEG
    el = box(x0, yb, z0, x1, yb + thick, z1, {"up": "#red", "down": "#soffit", "*": "#green"},
             uv={"up": [z0, x0, z1, x1], "down": [z0, x0, z1, x1],
                 "north": [x0, 8, x1, 8 + thick], "south": [x0, 8, x1, 8 + thick],
                 "east": [z0, 8, z1, 8 + thick], "west": [z0, 8, z1, 8 + thick]},
             rotation={"origin": [x0, yb, 8], "axis": "z", "angle": angle, "rescale": True})
    el["faces"]["up"]["rotation"] = 90      # seams run down the x slope
    return el


def flat_square(x0, z0, x1, z1, y_top, thick):
    return box(x0, y_top - thick, z0, x1, y_top, z1, {"up": "#red", "down": "#soffit", "*": "#green"},
               uv={"up": [x0, z0, x1, z1], "down": [x0, z0, x1, z1],
                   "north": [x0, 8, x1, 8 + thick], "south": [x0, 8, x1, 8 + thick],
                   "east": [z0, 8, z1, 8 + thick], "west": [z0, 8, z1, 8 + thick]})


STRIP = 2.0   # hip/peak strip width: the sawtooth left along the hip line


def hip_steps(level):
    """Convex-corner hip = min(slab descending +z, slab descending +x), high
    corner at (0,0): the region z >= x is the +z slab, x >= z the +x slab.
    Built from 2 px strips - along x for the +z slab (each strip covers z
    from its own x onward), along z for the +x slab - so both faces are
    true 22.5-degree slopes and only 2 px squares remain along the diagonal."""
    thick = DECK_T + (PARITY_T if level % 2 else 0)
    s_ = math.tan(math.radians(PITCH_DEG))
    y_high = BASE + RISE * (level + 1)
    els = []
    n = int(16 / STRIP)
    for i in range(n):
        c0, c1 = STRIP * i, STRIP * (i + 1)
        if c1 < 16:
            a = deck_slab(level, c1, 16, True, thick)          # +z slab over z c1..16
            a["from"][0], a["to"][0] = c0, c1
            a["faces"]["up"]["uv"] = [c0, c1, c1, 16]
            a["faces"]["down"]["uv"] = [c0, c1, c1, 16]
            els.append(a)
            els.append(deck_slab_x(level, c1, 16, True, thick, c0, c1))   # +x slab over x c1..16
        els.append(flat_square(c0, c0, c1, c1, y_high - s_ * (c0 + STRIP / 2) + thick, thick))
    return els


def peak_steps(level):
    """Crown meeting crown = a four-facet pyramid: min of the two crowns.
    Same strip construction on both axes; the facets meet along the two
    diagonals, filled with 2 px squares."""
    thick = DECK_T + (PARITY_T if level % 2 else 0)
    s_ = math.tan(math.radians(PITCH_DEG))
    peak = BASE + RISE * level + RISE / 2
    els = []
    n = int(16 / STRIP)
    for i in range(n):
        c0, c1 = STRIP * i, STRIP * (i + 1)
        d = abs(c0 + STRIP / 2 - 8)
        lo_end, hi_start = 8 - d - STRIP / 2, 8 + d + STRIP / 2
        # crown along x (facets descend -z and +z) on strip x c0..c1
        if lo_end > 0.05:
            a = deck_slab(level, 0, lo_end, False, thick)
            a["from"][0], a["to"][0] = c0, c1
            a["faces"]["up"]["uv"] = [c0, 0, c1, lo_end]
            a["faces"]["down"]["uv"] = [c0, 0, c1, lo_end]
            els.append(a)
            b_ = deck_slab(level, hi_start, 16, True, thick)
            b_["from"][0], b_["to"][0] = c0, c1
            b_["faces"]["up"]["uv"] = [c0, hi_start, c1, 16]
            b_["faces"]["down"]["uv"] = [c0, hi_start, c1, 16]
            els.append(b_)
            # crown along z (facets descend -x and +x) on strip z c0..c1
            els.append(deck_slab_x(level, 0, lo_end, False, thick, c0, c1))
            els.append(deck_slab_x(level, hi_start, 16, True, thick, c0, c1))
        # diagonal squares
        y_top = peak - s_ * d + thick
        els.append(flat_square(c0, c0, c1, c1, y_top, thick))
        els.append(flat_square(c0, 16 - c1, c1, 16 - c0, y_top, thick))
    return els


def join_models():
    """Hip cells (authored: our slab descends +z, the perpendicular roof
    descends +x - high corner at x 0, z 0), mirrored for the other three
    orientations; peak cells; a frieze pair for level-0 peaks."""
    tex = TEX_ROOF
    for level in range(MAX_LEVEL + 1):
        base = hip_steps(level) + rafter_tie()
        if level == 0:
            base += frieze_pos() + [rot_y_el(e, 1) for e in frieze_pos()]   # eaves along +z and +x
        # names: hip_<side>_<along>_<level>: side pos = descends +z; along pos = perpendicular descends +x
        model(f"el_roof_hip_pos_pos_{level}", base, tex, ao=False)
        model(f"el_roof_hip_neg_pos_{level}", [mirror_z(e) for e in base], tex, ao=False)
        model(f"el_roof_hip_pos_neg_{level}", [mirror_x(e) for e in base], tex, ao=False)
        model(f"el_roof_hip_neg_neg_{level}", [mirror_x(mirror_z(e)) for e in base], tex, ao=False)
        model(f"el_roof_peak_{level}", peak_steps(level) + rafter_tie(), tex, ao=False)
    model("el_roof_frieze_pair", frieze_pos() + [mirror_z(e) for e in frieze_pos()], tex, ao=False)


def roof_blockstate():
    """axis x = authored; axis z = the same models turned y=90 (crosswise
    becomes x). Java maps neg/pos onto the world directions per axis."""
    parts = []
    for axis, rot in (("x", 0), ("z", 90)):
        for level in range(MAX_LEVEL + 1):
            for side in ("pos", "neg", "crown"):
                apply = {"model": f"{MOD}:block/el_roof_{side}_{level}"}
                if rot:
                    apply["y"] = rot
                # the plain slab, except where a hip or peak replaces it
                parts.append({"when": {"axis": axis, "side": side, "level": str(level),
                                       "join": "none|valley_pos|valley_neg"}, "apply": apply})
                if side != "crown":
                    for along in ("pos", "neg"):
                        hip = {"model": f"{MOD}:block/el_roof_hip_{side}_{along}_{level}"}
                        if rot:
                            hip["y"] = rot
                        parts.append({"when": {"axis": axis, "side": side, "level": str(level),
                                               "join": f"hip_{along}"}, "apply": hip})
                        # valley: our slab plus the perpendicular roof's slab (descending toward us).
                        # perpendicular descending model +x = the NEG side model turned y=90
                        # (y=90 maps model -z to model... world +x), descending -x = POS turned y=90
                        pmodel = "neg" if along == "pos" else "pos"
                        val = {"model": f"{MOD}:block/el_roof_{pmodel}_{level}", "y": (rot + 90) % 360}
                        parts.append({"when": {"axis": axis, "side": side, "level": str(level),
                                               "join": f"valley_{along}"}, "apply": val})
                else:
                    peak = {"model": f"{MOD}:block/el_roof_peak_{level}"}
                    if rot:
                        peak["y"] = rot
                    parts.append({"when": {"axis": axis, "side": side, "level": str(level), "join": "peak"},
                                  "apply": peak})
                    if level == 0:
                        for r in (rot, (rot + 90) % 360):
                            fp = {"model": f"{MOD}:block/el_roof_frieze_pair"}
                            if r:
                                fp["y"] = r
                            parts.append({"when": {"axis": axis, "side": side, "level": "0", "join": "peak"},
                                          "apply": fp})
                for end in ("neg", "pos"):
                    apply = {"model": f"{MOD}:block/el_roof_end_{side}_{level}_{end}"}
                    if rot:
                        apply["y"] = rot
                    parts.append({"when": {"axis": axis, "side": side, "level": str(level),
                                           f"end_{end}": "true"}, "apply": apply})
            for side in ("pos", "neg"):
                apply = {"model": f"{MOD}:block/el_roof_ridge_{side}_{level}"}
                if rot:
                    apply["y"] = rot
                parts.append({"when": {"axis": axis, "side": side, "level": str(level),
                                       "ridge": "true"}, "apply": apply})
    write_json(os.path.join(BLOCKSTATES, "el_roof.json"), {"multipart": parts})


def roof_item():
    els = [deck_slab(0, 0, 8, False, DECK_T), deck_slab(0, 8, 16, True, DECK_T), *rafter_tie()]
    els += frieze_pos() + [mirror_z(e) for e in frieze_pos()]
    model("el_roof_item", els, TEX_ROOF)
    write_json(os.path.join(ITEM_MODELS, "el_roof.json"), {
        "parent": f"{MOD}:block/el_roof_item",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -1, 0], "scale": [0.5, 0.5, 0.5]}}})
    write_json(os.path.join(DATA, "loot_tables/blocks/el_roof.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_roof"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_roof.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"R": {"item": "minecraft:red_terracotta"}, "I": {"item": "minecraft:iron_bars"},
                "D": {"item": "minecraft:green_dye"}},
        "pattern": ["RRR", "IDI"],
        "result": {"item": f"{MOD}:el_roof", "count": 6}})


# =====================================================================
# EL POST - the slim canopy column (+ station-named variant)
# =====================================================================
#
# 4 px built-up I-section (flanges face FACING, rivet ladder on them), base
# plate at the bottom of a stack, and at the top a cap beam along the facing
# axis with two 45-degree knee struts - the beam's top is flush with the
# block top, so a roof cell above sits on it (its rafter tie crosses the
# beam). Authored facing NORTH; the blockstate turns it per facing.

def post_shaft():
    g = "#green"
    return [
        box(6, 0, 6, 10, 16, 6.8, g, uv={"north": [0, 0, 4, 16], "south": [0, 0, 4, 16],
                                          "east": [4, 0, 4.8, 16], "west": [4, 0, 4.8, 16]},
            faces=["north", "south", "east", "west"]),
        box(6, 0, 9.2, 10, 16, 10, g, uv={"north": [0, 0, 4, 16], "south": [0, 0, 4, 16],
                                           "east": [4, 0, 4.8, 16], "west": [4, 0, 4.8, 16]},
            faces=["north", "south", "east", "west"]),
        box(7.4, 0, 6.8, 8.6, 16, 9.2, g, uv={"east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16]},
            faces=["east", "west"]),
    ]


def post_foot():
    g = "#green"
    return [
        box(4, 0, 4, 12, 1, 12, g, uv={"up": [4, 4, 12, 12], "north": [4, 15, 12, 16],
                                        "south": [4, 15, 12, 16], "east": [4, 15, 12, 16],
                                        "west": [4, 15, 12, 16]},
            faces=["up", "north", "south", "east", "west"]),
        box(5.4, 1, 5.4, 10.6, 3.2, 10.6, g, uv={"up": [5.4, 5.4, 10.6, 10.6], "north": [5, 12.8, 10.2, 15],
                                                  "south": [5, 12.8, 10.2, 15], "east": [5, 12.8, 10.2, 15],
                                                  "west": [5, 12.8, 10.2, 15]},
            faces=["up", "north", "south", "east", "west"]),
    ]


def post_cap():
    """Compact capital: a head plate along the facing axis flush with the block
    top (it meets the roof's rafter tie) and two short 45-degree knee struts.
    Stays within z 2.5..13.5 so it never pokes out past a roof eave."""
    g = "#green"
    els = [box(6.5, 14, 2.5, 9.5, 16, 13.5, g,
               uv={"up": [6.5, 2.5, 9.5, 13.5], "down": [6.5, 2.5, 9.5, 13.5], "east": [2.5, 4, 13.5, 6],
                   "west": [2.5, 4, 13.5, 6], "north": [6.5, 4, 9.5, 6], "south": [6.5, 4, 9.5, 6]})]
    for sign in (1, -1):
        els.append(box(7.3, 9, 7.3, 8.7, 16.07, 8.7, g,
                       uv={"north": [0, 4, 1.4, 11], "south": [0, 4, 1.4, 11],
                           "east": [4, 4, 5.4, 11], "west": [4, 4, 5.4, 11],
                           "up": [7.3, 7.3, 8.7, 8.7], "down": [7.3, 7.3, 8.7, 8.7]},
                       rotation={"origin": [8, 9, 8], "axis": "x", "angle": 45 * sign, "rescale": False}))
    return els


def post_plates():
    """Black name plates on both flange faces (text painted by the renderer)."""
    b = "#black"
    return [
        box(5, 8.5, 5.4, 11, 11.5, 6, b, uv=[0, 0, 6, 3], faces=["north", "up", "down", "east", "west"]),
        box(5, 8.5, 10, 11, 11.5, 10.6, b, uv=[0, 0, 6, 3], faces=["south", "up", "down", "east", "west"]),
    ]


def post_assets():
    tex = {"green": "el2_green", "black": "el2_black"}
    model("el_post_shaft", post_shaft(), tex, ao=False)
    model("el_post_foot", post_foot(), tex, ao=False)
    model("el_post_cap", post_cap(), tex, ao=False)
    model("el_post_plates", post_plates(), tex)
    model("el_post_item", post_shaft() + post_foot() + post_cap(), tex)
    for name, named in (("el_post", False), ("el_post_named", True)):
        parts = []
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            def ap(m):
                a = {"model": f"{MOD}:block/{m}"}
                if rot:
                    a["y"] = rot
                return a
            parts.append({"when": {"facing": facing}, "apply": ap("el_post_shaft")})
            parts.append({"when": {"facing": facing, "down": "false"}, "apply": ap("el_post_foot")})
            parts.append({"when": {"facing": facing, "up": "false"}, "apply": ap("el_post_cap")})
            if named:
                parts.append({"when": {"facing": facing}, "apply": ap("el_post_plates")})
        write_json(os.path.join(BLOCKSTATES, name + ".json"), {"multipart": parts})
        write_json(os.path.join(ITEM_MODELS, name + ".json"), {
            "parent": f"{MOD}:block/el_post_item",
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]}}})
        write_json(os.path.join(DATA, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_post.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["I", "D", "I"], "result": {"item": f"{MOD}:el_post", "count": 4}})
    write_json(os.path.join(DATA, "recipes/el_post_named.json"), {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:el_post"}, {"item": "minecraft:black_dye"}],
        "result": {"item": f"{MOD}:el_post_named", "count": 1}})


def tex_black():
    rng = random.Random("black")
    rows = canvas(32, (22, 22, 24))
    for y in range(32):
        for x in range(32):
            put(rows, x, y, shade((22, 22, 24), rng.uniform(-3, 3)))
    return rows


# =====================================================================
# EL RAILING - picket railing for the open platform (+ name-sign segment)
# =====================================================================
#
# Fence logic (block/RailingBlock): a post where the run bends, ends, or a
# lamp sits above, and a half-arm toward each connected side. Authored arm
# runs NORTH (z 0..8); the blockstate turns it. 20 px tall (1.25 m): top
# rail, thin mid rail, bottom rail 2 px off the floor, 1.2 px pickets on a
# 4 px pitch. The sign segment uses the same models; its black name panel
# is renderer-drawn between y 6 and 18 (StationDecorRenderer.paintRailingSign).


def post_stub(x0, x1):
    """Post continuation below the block (y -8..0) on the panel plane, with a
    bolted bracket plate against the platform's concrete edge face (z 0):
    the bottom course of a windscreen/railing bolted to the slab."""
    g = "#green"
    return [
        box(x0, -8, 13.6, x1, 0, 16, g, uv={"north": [0, 8, x1 - x0, 16], "south": [0, 8, x1 - x0, 16],
                                            "east": [4, 8, 6.4, 16], "west": [4, 8, 6.4, 16], "down": [x0, 13.6, x1, 16]},
            faces=["north", "south", "east", "west", "down"]),
        box(x0 - 0.8, -7, 16, x1 + 0.8, -1, 16.6, g, uv={"south": [0, 9, x1 - x0 + 1.6, 15], "north": [0, 9, x1 - x0 + 1.6, 15],
                                                        "east": [0, 9, 0.6, 15], "west": [0, 9, 0.6, 15],
                                                        "up": [0, 0, x1 - x0 + 1.6, 0.6], "down": [0, 0, x1 - x0 + 1.6, 0.6]}),
        box(x0 - 0.2, -5.6, 16.6, x0 + 0.6, -4.8, 17.2, g, uv=[0, 15, 0.8, 16]),
        box(x1 - 0.6, -5.6, 16.6, x1 + 0.2, -4.8, 17.2, g, uv=[0, 15, 0.8, 16]),
        box(x0 - 0.2, -2.6, 16.6, x0 + 0.6, -1.8, 17.2, g, uv=[0, 15, 0.8, 16]),
        box(x1 - 0.6, -2.6, 16.6, x1 + 0.2, -1.8, 17.2, g, uv=[0, 15, 0.8, 16]),
    ]


def rail_post_left():
    g = "#green"
    return [
        box(0, 0, 13.6, 2.4, 20, 16, g, uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                                            "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16]},
            faces=["north", "south", "east", "west"]),
        box(-0.6, 20, 13.0, 3.0, 21, 16.6, g, uv={"north": [4, 8, 7.6, 9], "south": [4, 8, 7.6, 9],
                                                  "east": [4, 8, 7.6, 9], "west": [4, 8, 7.6, 9],
                                                  "up": [0, 12.4, 3.6, 16], "down": [0, 12.4, 3.6, 16]}),
    ]


def rail_post_right_ext():
    """Right post reaching 2.4 px past the cell into the corner square outside a
    convex platform corner (CORNER_RIGHT): x 13.6..18.4."""
    g = "#green"
    return [
        box(13.6, 0, 13.6, 18.4, 20, 16, g, uv={"north": [0, 0, 4.8, 16], "south": [0, 0, 4.8, 16],
                                               "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16]},
            faces=["north", "south", "east", "west"]),
        box(13.0, 20, 13.0, 19.0, 21, 16.6, g, uv={"north": [4, 8, 10, 9], "south": [4, 8, 10, 9],
                                                   "east": [4, 8, 7.6, 9], "west": [4, 8, 7.6, 9],
                                                   "up": [0, 12.4, 6, 16], "down": [0, 12.4, 6, 16]}),
    ]


def rail_post_mid():
    """Under a lamp: a post at the block's mid-run point on the panel plane."""
    g = "#green"
    return [box(6.8, 0, 13.6, 9.2, 20, 16, g, uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                                                 "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16]},
                faces=["north", "south", "east", "west"])]


def rail_arm():
    """Rails + pickets across the block on the edge plane (z 13.6..16)."""
    g = "#green"
    els = [
        box(0, 18, 13.6, 16, 20, 16, g, uv={"north": [0, 4, 16, 6], "south": [0, 4, 16, 6], "up": [0, 13.6, 16, 16],
                                            "down": [0, 13.6, 16, 16]}, faces=["north", "south", "up", "down"]),
        box(0, 9.4, 14.1, 16, 10.4, 15.5, g, uv={"north": [0, 5, 16, 6], "south": [0, 5, 16, 6], "up": [0, 14.1, 16, 15.5],
                                                 "down": [0, 14.1, 16, 15.5]}, faces=["north", "south", "up", "down"]),
        box(0, 2, 13.8, 16, 3.2, 15.8, g, uv={"north": [0, 5, 16, 6.2], "south": [0, 5, 16, 6.2], "up": [0, 13.8, 16, 15.8],
                                              "down": [0, 13.8, 16, 15.8]}, faces=["north", "south", "up", "down"]),
    ]
    for x0 in (3.4, 7.4, 11.4):
        els.append(box(x0, 3.2, 14.2, x0 + 1.2, 18, 15.4, g,
                       uv={"north": [4, 0, 5.2, 14.8], "south": [4, 0, 5.2, 14.8],
                           "east": [4, 0, 5.2, 14.8], "west": [4, 0, 5.2, 14.8]},
                       faces=["north", "south", "east", "west"]))
    return els


def rail_return():
    """Corner return along the west edge (x 0..2.4, z 0..13.6)."""
    g = "#green"
    els = [
        box(0, 18, 0, 2.4, 20, 13.6, g, uv={"east": [0, 4, 13.6, 6], "west": [0, 4, 13.6, 6], "up": [0, 0, 2.4, 13.6],
                                            "down": [0, 0, 2.4, 13.6]}, faces=["east", "west", "up", "down"]),
        box(0.5, 9.4, 0, 1.9, 10.4, 13.6, g, uv={"east": [0, 5, 13.6, 6], "west": [0, 5, 13.6, 6], "up": [0, 0, 1.4, 13.6],
                                                 "down": [0, 0, 1.4, 13.6]}, faces=["east", "west", "up", "down"]),
        box(0.2, 2, 0, 2.2, 3.2, 13.6, g, uv={"east": [0, 5, 13.6, 6.2], "west": [0, 5, 13.6, 6.2], "up": [0, 0, 2, 13.6],
                                              "down": [0, 0, 2, 13.6]}, faces=["east", "west", "up", "down"]),
    ]
    for z0 in (1.4, 5.4, 9.4):
        els.append(box(0.6, 3.2, z0, 1.8, 18, z0 + 1.2, g,
                       uv={"north": [4, 0, 5.2, 14.8], "south": [4, 0, 5.2, 14.8],
                           "east": [4, 0, 5.2, 14.8], "west": [4, 0, 5.2, 14.8]},
                       faces=["north", "south", "east", "west"]))
    els.append(box(0, 0, 0, 2.4, 20, 2.4, g, uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                                                 "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16], "up": [0, 0, 2.4, 2.4]},
                   faces=["north", "south", "east", "west", "up"]))
    return els


def railing_assets():
    tex = {"green": "el2_green"}
    NEAR_SIDE.update({"el_railing_post_left", "el_railing_post_right", "el_railing_post_mid", "el_railing_arm",
                      "el_railing_item", "el_stub_left", "el_stub_right", "el_stub_mid"})
    model("el_stub_left", post_stub(0, 2.4), tex)
    model("el_stub_right", post_stub(13.6, 16), tex)
    model("el_stub_mid", post_stub(6.8, 9.2), tex)
    model("el_railing_post_left", rail_post_left(), tex)
    model("el_railing_post_right", [mirror_x(e) for e in rail_post_left()], tex)
    NEAR_SIDE.add("el_railing_post_right_ext")
    model("el_railing_post_right_ext", rail_post_right_ext(), tex)
    model("el_railing_post_mid", rail_post_mid(), tex)
    model("el_railing_arm", rail_arm(), tex)
    NEAR_SIDE.update({"el_railing_return_left", "el_railing_return_right"})
    model("el_railing_return_left", rail_return(), tex)
    model("el_railing_return_right", [mirror_x(e) for e in rail_return()], tex)
    model("el_railing_item", rail_post_left() + [mirror_x(e) for e in rail_post_left()] + rail_arm(), tex)
    for name in ("el_railing", "el_railing_sign"):
        parts = []
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            def ap(m):
                a = {"model": f"{MOD}:block/{m}"}
                if rot:
                    a["y"] = rot
                return a
            n = {"facing": facing, "corner_cell": "none"}
            parts.append({"when": dict(n), "apply": ap("el_railing_arm")})
            if name == "el_railing_sign":
                parts.append({"when": dict(n, left="false"), "apply": ap("el_railing_post_left")})
            else:
                parts.append({"when": dict(n), "apply": ap("el_railing_post_left")})
            parts.append({"when": dict(n, right="false", corner_right="false"), "apply": ap("el_railing_post_right")})
            parts.append({"when": dict(n, right="false", corner_right="true"), "apply": ap("el_railing_post_right_ext")})
            parts.append({"when": dict(n, lamp="true"), "apply": ap("el_railing_post_mid")})
            parts.append({"when": dict(n, bottom="true"), "apply": ap("el_stub_left")})
            parts.append({"when": dict(n, bottom="true", right="false"), "apply": ap("el_stub_right")})
            parts.append({"when": dict(n, bottom="true", lamp="true"), "apply": ap("el_stub_mid")})
            parts.append({"when": dict(n, inner_left="true"), "apply": ap("el_railing_return_left")})
            parts.append({"when": dict(n, inner_right="true"), "apply": ap("el_railing_return_right")})
            # a block in the diagonal corner cell: only the corner post
            parts.append({"when": {"facing": facing, "corner_cell": "right"}, "apply": ap("el_railing_post_right")})
            parts.append({"when": {"facing": facing, "corner_cell": "left"}, "apply": ap("el_railing_post_left")})
            parts.append({"when": {"facing": facing, "corner_cell": "right", "bottom": "true"}, "apply": ap("el_stub_right")})
            parts.append({"when": {"facing": facing, "corner_cell": "left", "bottom": "true"}, "apply": ap("el_stub_left")})
        write_json(os.path.join(BLOCKSTATES, name + ".json"), {"multipart": parts})
        write_json(os.path.join(ITEM_MODELS, name + ".json"), {
            "parent": f"{MOD}:block/el_railing_item",
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -1, 0], "scale": [0.6, 0.6, 0.6]}}})
        write_json(os.path.join(DATA, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_railing.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_bars"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["III", "IDI"], "result": {"item": f"{MOD}:el_railing", "count": 6}})
    write_json(os.path.join(DATA, "recipes/el_railing_sign.json"), {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:el_railing"}, {"item": "minecraft:black_dye"}],
        "result": {"item": f"{MOD}:el_railing_sign", "count": 1}})


# =====================================================================
# EL WALLS - thin windscreen courses for the roofed section
# =====================================================================
#
# Same fence logic as the railing (post at bends/ends, half-arm per side),
# 1.6 px thick panels in a green frame. Three courses, each self-framed so
# they stack in any order: cream board panel, wired-glass band (cutout), and
# the name band (renderer-drawn black board, StationDecorRenderer).

def wall_left_post(g="#green"):
    return [box(0, 0, 13.6, 2.4, 16, 16, g, uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                                                "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16],
                                                "up": [0, 13.6, 2.4, 16]},
                faces=["north", "south", "east", "west", "up"])]


def wall_right_post(g="#green"):
    return [mirror_x(e) for e in wall_left_post(g)]


def wall_right_post_ext(g="#green"):
    """Right post reaching 2.4 px past the cell into the corner square (CORNER_RIGHT)."""
    return [box(13.6, 0, 13.6, 18.4, 16, 16, g, uv={"north": [0, 0, 4.8, 16], "south": [0, 0, 4.8, 16],
                                                    "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16],
                                                    "up": [0, 13.6, 4.8, 16]},
                faces=["north", "south", "east", "west", "up"])]


def wall_return(panel_tex, glass=False, rail="#green", post="#green"):
    """Corner return along the WEST edge (x 0..2.4, z 0..13.6): joins this
    block's edge panel to a perpendicular run whose panel ends at our west
    edge. Mirror for the east edge. `rail` colours the rails (the all-cream
    and all-green courses paint their rails to match the panel)."""
    g = rail
    els = [
        box(0.2, 0, 0, 2.2, 1.6, 13.6, g, uv={"east": [0, 4, 13.6, 5.6], "west": [0, 4, 13.6, 5.6],
                                              "up": [0, 0, 2, 13.6], "down": [0, 0, 2, 13.6]},
            faces=["east", "west", "up", "down"]),
        box(0.2, 14.4, 0, 2.2, 16, 13.6, g, uv={"east": [0, 4, 13.6, 5.6], "west": [0, 4, 13.6, 5.6],
                                                "up": [0, 0, 2, 13.6], "down": [0, 0, 2, 13.6]},
            faces=["east", "west", "up", "down"]),
        box(0.6, 1.6, 0, 1.8, 14.4, 13.6, panel_tex, uv={"east": [0, 1.6, 13.6, 14.4], "west": [13.6, 1.6, 0, 14.4]},
            faces=["east", "west"]),
    ]
    if glass:
        els.append(box(0.4, 1.6, 6.2, 2.0, 14.4, 7.4, g, uv={"east": [0, 3.2, 1.2, 16], "west": [0, 3.2, 1.2, 16],
                                                             "north": [0, 3.2, 1.6, 16], "south": [0, 3.2, 1.6, 16]},
                       faces=["east", "west", "north", "south"]))
    els.append(box(0, 0, 0, 2.4, 16, 2.4, post, uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                                                    "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16], "up": [0, 0, 2.4, 2.4]},
                   faces=["north", "south", "east", "west", "up"]))
    return els


def wall_arm(panel_tex, glass=False, rail="#green"):
    """Panel + rails across the whole block at the far edge (z 13.6..16); the
    ends bury inside the posts (left always drawn, right at run ends), and a
    joint between two segments shows the neighbour's left post."""
    g = rail
    els = [
        box(0, 0, 13.8, 16, 1.6, 15.8, g, uv={"north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6],
                                              "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]},
            faces=["north", "south", "up", "down"]),
        box(0, 14.4, 13.8, 16, 16, 15.8, g, uv={"north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6],
                                                "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]},
            faces=["north", "south", "up", "down"]),
        box(0, 1.6, 14.2, 16, 14.4, 15.4, panel_tex, uv={"north": [0, 1.6, 16, 14.4], "south": [16, 1.6, 0, 14.4]},
            faces=["north", "south"]),
    ]
    if glass:
        # centre mullion + a stile at each side, so every pane is framed on all
        # four sides whatever the posts do (Thomas: windows get a full border).
        # The stiles sit inside the post footprint (x 0..2.4 / 13.6..16) so a
        # post simply swallows them; their faces are off the post's planes.
        for x0 in (7.4, 0.2, 14.6):
            els.append(box(x0, 1.6, 14.0, x0 + 1.2, 14.4, 15.6, g, uv={"north": [0, 3.2, 1.2, 16], "south": [0, 3.2, 1.2, 16],
                                                                       "east": [0, 3.2, 1.6, 16], "west": [0, 3.2, 1.6, 16]},
                           faces=["north", "south", "east", "west"]))
    return els


def wall_doorway_arm():
    """Header beam only (y 13..16 on the panel plane): the opening below is clear."""
    g = "#green"
    return [box(0, 13, 13.8, 16, 16, 15.8, g, uv={"north": [0, 4, 16, 7], "south": [0, 4, 16, 7],
                                                  "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]},
                faces=["north", "south", "up", "down"])]


def mirror_x(el):
    """Mirror an element across x=8: geometry and east<->west; flip u on faces whose u runs along x."""
    e = json.loads(json.dumps(el))
    f, t = e["from"], e["to"]
    e["from"] = [16 - t[0], f[1], f[2]]
    e["to"] = [16 - f[0], t[1], t[2]]
    faces = {}
    for name, face in e["faces"].items():
        new = {"east": "west", "west": "east"}.get(name, name)
        if name in ("north", "south", "up", "down"):
            u0, v0, u1, v1 = face["uv"]
            face = dict(face, uv=[u1, v0, u0, v1])
        faces[new] = face
    e["faces"] = faces
    if "rotation" in e:
        r = dict(e["rotation"])
        o = r["origin"]
        r["origin"] = [16 - o[0], o[1], o[2]]
        if r["axis"] == "z":
            r["angle"] = -r["angle"]
        e["rotation"] = r
    return e


def tex_cream():
    """Cream board-and-batten panel: 8 px boards, batten shadow, tiles per block."""
    rng = random.Random("cream")
    rows = canvas(32, CREAM)
    for y in range(32):
        for x in range(32):
            put(rows, x, y, shade(CREAM, rng.uniform(-5, 5) + 2 * math.sin(y * 0.4)))
    for x in range(0, 32, 8):
        for y in range(32):
            put(rows, x, y, shade(CREAM, -34))
            put(rows, x + 1, y, shade(CREAM, 14))
            put(rows, x + 7, y, shade(CREAM, -12))
    return rows


def tex_wired_glass():
    """Wired glass, cutout: mostly clear, a diamond wire grid, a glare run."""
    rows = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    wire = (96, 104, 100, 255)
    for y in range(32):
        for x in range(32):
            if (x + y) % 8 == 0 or (x - y) % 8 == 0:
                put(rows, x, y, wire)
    for x in range(32):
        for y in range(32):
            if 4 <= (x - y // 3) % 32 <= 6 and rows[y][x][3] == 0:
                put(rows, x, y, (220, 232, 236, 255))
    return rows


def tex_cream_ridged():
    """All-cream course: corrugated/beadboard cream sheet, 4 px ridges with a
    lit crest and a shadowed trough, tiles vertically (stacks cleanly)."""
    rng = random.Random("cream_ridged")
    rows = canvas(32, CREAM)
    for y in range(32):
        for x in range(32):
            phase = x % 4
            d = (14, 4, -12, -22)[phase] + rng.uniform(-3, 3)
            put(rows, x, y, shade(CREAM, d))
    return rows


def tex_green_panel():
    """All-green course: plain painted sheet, faint vertical drift, no rivets."""
    rng = random.Random("green_panel")
    rows = canvas(32, GREEN)
    for y in range(32):
        for x in range(32):
            put(rows, x, y, shade(GREEN, rng.uniform(-5, 5) + 3 * math.sin(x * 0.7)))
    return rows


def walls_assets():
    write_png("el2_cream", tex_cream())
    write_png("el2_wired_glass", tex_wired_glass())
    write_png("el2_cream_ridged", tex_cream_ridged())
    write_png("el2_green_panel", tex_green_panel())
    tex = {"green": "el2_green", "cream": "el2_cream", "glass": "el2_wired_glass",
           "creamr": "el2_cream_ridged", "gpanel": "el2_green_panel"}
    NEAR_SIDE.update({"el_wall_post_left", "el_wall_post_right", "el_wall_arm", "el_wall_glass_arm",
                      "el_wall_item", "el_wall_glass_item"})
    model("el_wall_post_left", wall_left_post(), tex)
    model("el_wall_post_right", wall_right_post(), tex)
    NEAR_SIDE.update({"el_wall_post_right_ext", "el_wall_cream_post_right_ext"})
    model("el_wall_post_right_ext", wall_right_post_ext(), tex)
    model("el_wall_cream_post_right_ext", wall_right_post_ext("#creamr"), tex)
    model("el_wall_arm", wall_arm("#cream"), tex)
    model("el_wall_glass_arm", wall_arm("#glass", glass=True), tex)
    NEAR_SIDE.update({"el_wall_return_left", "el_wall_return_right", "el_wall_glass_return_left",
                      "el_wall_glass_return_right"})
    model("el_wall_return_left", wall_return("#cream"), tex)
    model("el_wall_return_right", [mirror_x(e) for e in wall_return("#cream")], tex)
    model("el_wall_glass_return_left", wall_return("#glass", True), tex)
    model("el_wall_glass_return_right", [mirror_x(e) for e in wall_return("#glass", True)], tex)
    model("el_wall_item", wall_left_post() + wall_right_post() + wall_arm("#cream"), tex)
    model("el_wall_glass_item", wall_left_post() + wall_right_post() + wall_arm("#glass", True), tex)
    # all-cream (ridged) and all-green courses: panel + rails in one colour, shared green posts
    NEAR_SIDE.update({"el_wall_cream_arm", "el_wall_cream_item", "el_wall_cream_return_left", "el_wall_cream_return_right",
                      "el_wall_green_arm", "el_wall_green_item", "el_wall_green_return_left", "el_wall_green_return_right"})
    model("el_wall_cream_arm", wall_arm("#creamr", rail="#creamr"), tex)
    model("el_wall_cream_return_left", wall_return("#creamr", rail="#creamr", post="#creamr"), tex)
    model("el_wall_cream_return_right", [mirror_x(e) for e in wall_return("#creamr", rail="#creamr", post="#creamr")], tex)
    NEAR_SIDE.update({"el_wall_cream_post_left", "el_wall_cream_post_right"})
    model("el_wall_cream_post_left", wall_left_post("#creamr"), tex)      # Thomas: cream bars, not green
    model("el_wall_cream_post_right", wall_right_post("#creamr"), tex)
    model("el_wall_cream_item", wall_left_post("#creamr") + wall_right_post("#creamr") + wall_arm("#creamr", rail="#creamr"), tex)
    model("el_wall_green_arm", wall_arm("#gpanel", rail="#gpanel"), tex)
    model("el_wall_green_return_left", wall_return("#gpanel", rail="#gpanel"), tex)
    model("el_wall_green_return_right", [mirror_x(e) for e in wall_return("#gpanel", rail="#gpanel")], tex)
    model("el_wall_green_item", wall_left_post() + wall_right_post() + wall_arm("#gpanel", rail="#gpanel"), tex)
    NEAR_SIDE.update({"el_wall_doorway_arm", "el_wall_doorway_item"})
    model("el_wall_doorway_arm", wall_doorway_arm(), tex)
    model("el_wall_doorway_item", wall_left_post() + wall_right_post() + wall_doorway_arm(), tex)
    for name, arm, item in (("el_wall", "el_wall_arm", "el_wall_item"),
                            ("el_wall_glass", "el_wall_glass_arm", "el_wall_glass_item"),
                            ("el_wall_sign", "el_wall_arm", "el_wall_item"),
                            ("el_wall_cream", "el_wall_cream_arm", "el_wall_cream_item"),
                            ("el_wall_green", "el_wall_green_arm", "el_wall_green_item"),
                            ("el_wall_doorway", "el_wall_doorway_arm", "el_wall_doorway_item")):
        parts = []
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            def ap(m):
                a = {"model": f"{MOD}:block/{m}"}
                if rot:
                    a["y"] = rot
                return a
            n = {"facing": facing, "corner_cell": "none"}
            parts.append({"when": dict(n), "apply": ap(arm)})
            post = "el_wall_cream_post" if name == "el_wall_cream" else "el_wall_post"
            if name == "el_wall_sign":
                # the name board takes priority over posts: only run-end posts
                parts.append({"when": dict(n, left="false"), "apply": ap(post + "_left")})
            else:
                parts.append({"when": dict(n), "apply": ap(post + "_left")})
            parts.append({"when": dict(n, right="false", corner_right="false"), "apply": ap(post + "_right")})
            parts.append({"when": dict(n, right="false", corner_right="true"), "apply": ap(post + "_right_ext")})
            parts.append({"when": dict(n, bottom="true"), "apply": ap("el_stub_left")})
            parts.append({"when": dict(n, bottom="true", right="false"), "apply": ap("el_stub_right")})
            parts.append({"when": {"facing": facing, "corner_cell": "right"}, "apply": ap(post + "_right")})
            parts.append({"when": {"facing": facing, "corner_cell": "left"}, "apply": ap(post + "_left")})
            parts.append({"when": {"facing": facing, "corner_cell": "right", "bottom": "true"}, "apply": ap("el_stub_right")})
            parts.append({"when": {"facing": facing, "corner_cell": "left", "bottom": "true"}, "apply": ap("el_stub_left")})
            ret = {"el_wall_glass": "el_wall_glass_return", "el_wall_cream": "el_wall_cream_return",
                   "el_wall_green": "el_wall_green_return"}.get(name, "el_wall_return")
            if name == "el_wall_doorway":
                ret = None
            if ret:
                parts.append({"when": dict(n, inner_left="true"), "apply": ap(ret + "_left")})
                parts.append({"when": dict(n, inner_right="true"), "apply": ap(ret + "_right")})
        write_json(os.path.join(BLOCKSTATES, name + ".json"), {"multipart": parts})
        write_json(os.path.join(ITEM_MODELS, name + ".json"), {
            "parent": f"{MOD}:block/{item}",
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]}}})
        write_json(os.path.join(DATA, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_wall.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"P": {"item": "minecraft:birch_planks"}, "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["IPI", "IPI"], "result": {"item": f"{MOD}:el_wall", "count": 6}})
    write_json(os.path.join(DATA, "recipes/el_wall_glass.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"P": {"item": "minecraft:glass_pane"}, "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["IPI", "IPI"], "result": {"item": f"{MOD}:el_wall_glass", "count": 6}})
    write_json(os.path.join(DATA, "recipes/el_wall_cream.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"P": {"item": "minecraft:birch_planks"}, "I": {"item": "minecraft:iron_ingot"}, "W": {"item": "minecraft:white_dye"}},
        "pattern": ["IPI", "IWI"], "result": {"item": f"{MOD}:el_wall_cream", "count": 6}})
    write_json(os.path.join(DATA, "recipes/el_wall_green.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["IDI", "IDI"], "result": {"item": f"{MOD}:el_wall_green", "count": 6}})
    write_json(os.path.join(DATA, "recipes/el_wall_doorway.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["III", "I I"], "result": {"item": f"{MOD}:el_wall_doorway", "count": 4}})
    write_json(os.path.join(DATA, "recipes/el_wall_sign.json"), {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:el_wall"}, {"item": "minecraft:black_dye"}],
        "result": {"item": f"{MOD}:el_wall_sign", "count": 1}})


# =====================================================================
# EL LAMP - stackable pole + cone-hat head (the 25 Av / Nereid platform lamp)
# =====================================================================

def lamp_pole():
    k = "#green"
    return [box(6.8, 0, 13.6, 9.2, 16, 16, k, uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                                                 "east": [0, 0, 2.4, 16], "west": [0, 0, 2.4, 16]},
                faces=["north", "south", "east", "west"])]


def lamp_foot():
    k = "#green"
    return [box(4.5, 0, 11.3, 11.5, 2, 16, k, uv={"up": [4.5, 11.3, 11.5, 16], "north": [0, 14, 7, 16],
                                                 "south": [0, 14, 7, 16], "east": [0, 14, 4.7, 16],
                                                 "west": [0, 14, 4.7, 16]},
                faces=["up", "north", "south", "east", "west"]),
            box(5.6, 2, 12.4, 10.4, 3.4, 16, k, uv={"up": [5.6, 12.4, 10.4, 16], "north": [0, 12, 4.8, 13.4],
                                                    "south": [0, 12, 4.8, 13.4], "east": [0, 12, 3.6, 13.4],
                                                    "west": [0, 12, 3.6, 13.4]},
                faces=["up", "north", "south", "east", "west"])]


def lamp_head():
    """Cone-hat head centred over the edge-plane pole (z 14.8); the hat
    overhangs the block edge, which is fine (models may reach -16..32)."""
    k, w = "#green", "#lamp"
    cz = 14.8
    return [
        box(6.8, 0, 13.6, 9.2, 8, 16, k, uv=[0, 0, 2.4, 8], faces=["north", "south", "east", "west"]),
        box(2, 10, cz - 6, 14, 11, cz + 6, k, uv={"up": [2, 2, 14, 14], "down": [2, 2, 14, 14], "north": [0, 0, 12, 1],
                                                  "south": [0, 0, 12, 1], "east": [0, 0, 12, 1], "west": [0, 0, 12, 1]}),
        box(4, 11, cz - 4, 12, 12, cz + 4, k, uv={"up": [4, 4, 12, 12], "north": [0, 0, 8, 1], "south": [0, 0, 8, 1],
                                                  "east": [0, 0, 8, 1], "west": [0, 0, 8, 1]},
            faces=["up", "north", "south", "east", "west"]),
        box(6, 12, cz - 2, 10, 13, cz + 2, k, uv={"up": [6, 6, 10, 10], "north": [0, 0, 4, 1], "south": [0, 0, 4, 1],
                                                  "east": [0, 0, 4, 1], "west": [0, 0, 4, 1]},
            faces=["up", "north", "south", "east", "west"]),
        box(5, 8, cz - 3, 11, 10, cz + 3, w, uv={"down": [0, 0, 6, 6], "north": [0, 0, 6, 2], "south": [0, 0, 6, 2],
                                                 "east": [0, 0, 6, 2], "west": [0, 0, 6, 2]},
            faces=["down", "north", "south", "east", "west"], shade_=False),
    ]


def tex_lamp():
    rows = canvas(16, (255, 244, 208))
    for y in range(16):
        for x in range(16):
            d = -18 if (x in (0, 15) or y in (0, 15)) else 0
            put(rows, x, y, shade((255, 244, 208), d))
    return rows


def lamp_assets():
    write_png("el2_lamp", tex_lamp())
    tex = {"green": "el2_green", "lamp": "el2_lamp"}
    NEAR_SIDE.update({"el_lamp_pole", "el_lamp_foot", "el_lamp_head", "el_lamp_pole_item"})
    model("el_lamp_pole", lamp_pole(), tex)
    model("el_lamp_foot", lamp_foot(), tex)
    model("el_lamp_head", lamp_head(), tex)
    model("el_lamp_pole_item", lamp_pole() + lamp_foot(), tex)
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        a = {"model": f"{MOD}:block/el_lamp_pole"}
        b = {"model": f"{MOD}:block/el_lamp_foot"}
        if rot:
            a["y"] = rot
            b["y"] = rot
        parts.append({"when": {"facing": facing}, "apply": a})
        parts.append({"when": {"facing": facing, "down": "false"}, "apply": b})
    write_json(os.path.join(BLOCKSTATES, "el_platform_lamp.json"), {"multipart": parts})
    variants = {}
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        v = {"model": f"{MOD}:block/el_lamp_head"}
        if rot:
            v["y"] = rot
        variants[f"facing={facing}"] = v
    write_json(os.path.join(BLOCKSTATES, "el_platform_lamp_head.json"), {"variants": variants})
    for name, parent in (("el_platform_lamp", "el_lamp_pole_item"), ("el_platform_lamp_head", "el_lamp_head")):
        write_json(os.path.join(ITEM_MODELS, name + ".json"), {
            "parent": f"{MOD}:block/{parent}",
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]}}})
        write_json(os.path.join(DATA, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_platform_lamp.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:black_dye"}},
        "pattern": ["I", "D", "I"], "result": {"item": f"{MOD}:el_platform_lamp", "count": 4}})
    write_json(os.path.join(DATA, "recipes/el_platform_lamp_head.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "G": {"item": "minecraft:glowstone_dust"}},
        "pattern": ["III", " G "], "result": {"item": f"{MOD}:el_platform_lamp_head", "count": 2}})


# =====================================================================
# EL ROOF LIGHT - fluorescent fixture hung under a roof cell
# =====================================================================
#
# Placed in the block BELOW a roof cell: two drop rods to the roof's rafter
# tie and a 14 px fixture with a glowing tube. FACING = tube direction.

def roof_light():
    """Fixture running the full block along z (facing axis): two drop rods to
    the roof tie, a housing and the glowing tube. No end faces - the caps
    close the ends of a run."""
    k, w = "#black", "#lamp"
    return [
        box(7.5, 14, 7.5, 8.5, 16, 8.5, k, uv=[0, 0, 1, 2], faces=["north", "south", "east", "west"]),
        box(6, 12.4, 0, 10, 14, 16, k, uv={"up": [6, 0, 10, 16], "east": [0, 0, 16, 1.6], "west": [0, 0, 16, 1.6]},
            faces=["up", "east", "west"]),
        box(6.6, 11.4, 0, 9.4, 12.4, 16, w, uv={"down": [0, 0, 2.8, 16], "east": [0, 0, 16, 1],
                                                 "west": [0, 0, 16, 1]}, shade_=False,
            faces=["down", "east", "west"]),
    ]


def roof_light_cap():
    """End cap at z=0 (the 'front' end): closes housing and tube."""
    k, w = "#black", "#lamp"
    return [
        box(6, 12.4, 0, 10, 14, 0.6, k, uv={"north": [0, 0, 4, 1.6]}, faces=["north"]),
        box(6.6, 11.4, 0, 9.4, 12.4, 0.6, w, uv={"north": [0, 0, 2.8, 1]}, faces=["north"], shade_=False),
    ]


def roof_light_assets():
    tex = {"black": "el2_black", "lamp": "el2_lamp"}
    model("el_roof_light", roof_light(), tex)
    model("el_roof_light_cap_front", roof_light_cap(), tex)
    model("el_roof_light_cap_back", [mirror_z(e) for e in roof_light_cap()], tex)
    model("el_roof_light_item", roof_light() + roof_light_cap() + [mirror_z(e) for e in roof_light_cap()], tex)
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        def ap(m):
            a = {"model": f"{MOD}:block/{m}"}
            if rot:
                a["y"] = rot
            return a
        parts.append({"when": {"facing": facing}, "apply": ap("el_roof_light")})
        parts.append({"when": {"facing": facing, "front": "false"}, "apply": ap("el_roof_light_cap_front")})
        parts.append({"when": {"facing": facing, "back": "false"}, "apply": ap("el_roof_light_cap_back")})
    write_json(os.path.join(BLOCKSTATES, "el_roof_light.json"), {"multipart": parts})
    write_json(os.path.join(ITEM_MODELS, "el_roof_light.json"), {
        "parent": f"{MOD}:block/el_roof_light_item",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [0.8, 0.8, 0.8]}}})
    write_json(os.path.join(DATA, "loot_tables/blocks/el_roof_light.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_roof_light"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_roof_light.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "G": {"item": "minecraft:glowstone_dust"}},
        "pattern": ["III", "GGG"], "result": {"item": f"{MOD}:el_roof_light", "count": 3}})


# =====================================================================
# EL SIGN - black station-name board: hanging (centre rod onto the roof's
# rafter tie), standing (on a windscreen rail or the floor), wall.
# Plate boxes MUST match StationDecorRenderer.paintElNameBoard's table:
#   standing  x 1..15  y 6..13  z 7.4..8.6      hanging  y 5..12  same z
#   wall      x 1..15  y 5..12  z 13.8..15  (front only)
# =====================================================================

def sign_plate(y0, y1, z0, z1, faces=None):
    return box(1, y0, z0, 15, y1, z1, "#black", uv=[0, 0, 14, y1 - y0], faces=faces)


def sign_models():
    tex = {"black": "el2_black", "green": "el2_green"}
    standing = [sign_plate(6, 13, 7.4, 8.6),
                box(2, 0, 7.5, 3.2, 6, 8.5, "#green", uv=[0, 0, 1.2, 6]),
                box(12.8, 0, 7.5, 14, 6, 8.5, "#green", uv=[0, 0, 1.2, 6]),
                box(1, 13, 7.2, 15, 13.6, 8.8, "#green", uv=[0, 0, 14, 0.6])]
    hanging = [sign_plate(5, 12, 7.4, 8.6),
               box(7, 12, 7, 9, 16, 9, "#green", uv=[0, 0, 2, 4], faces=["north", "south", "east", "west"]),
               box(1, 12, 7.2, 15, 12.6, 8.8, "#green", uv=[0, 0, 14, 0.6])]
    wall = [sign_plate(5, 12, 13.8, 15, faces=["north", "up", "down", "east", "west"]),
            box(2, 6, 15, 3.2, 11, 16, "#green", uv=[0, 0, 1.2, 5], faces=["north", "east", "west", "up", "down"]),
            box(12.8, 6, 15, 14, 11, 16, "#green", uv=[0, 0, 1.2, 5], faces=["north", "east", "west", "up", "down"])]
    model("el_sign_standing", standing, tex)
    model("el_sign_hanging", hanging, tex)
    model("el_sign_wall", wall, tex)
    variants = {}
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for mount in ("standing", "hanging", "wall"):
            v = {"model": f"{MOD}:block/el_sign_{mount}"}
            if rot:
                v["y"] = rot
            variants[f"facing={facing},mount={mount}"] = v
    write_json(os.path.join(BLOCKSTATES, "el_sign.json"), {"variants": variants})
    write_json(os.path.join(ITEM_MODELS, "el_sign.json"), {
        "parent": f"{MOD}:block/el_sign_hanging",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]}}})
    write_json(os.path.join(DATA, "loot_tables/blocks/el_sign.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_sign"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write_json(os.path.join(DATA, "recipes/el_sign.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "B": {"item": "minecraft:black_dye"}, "P": {"item": "minecraft:paper"}},
        "pattern": ["IBI", "IPI"], "result": {"item": f"{MOD}:el_sign", "count": 2}})



# =====================================================================
# verify + main
# =====================================================================

RAIL_PROPS = {"facing": {"north", "east", "south", "west"}, "left": {"true", "false"},
              "corner_cell": {"none", "left", "right"},
              "inner_left": {"true", "false"}, "inner_right": {"true", "false"},
              "right": {"true", "false"}, "corner_left": {"true", "false"}, "corner_right": {"true", "false"},
              "lamp": {"true", "false"}, "bottom": {"true", "false"}}
WALL_PROPS = {"facing": {"north", "east", "south", "west"}, "left": {"true", "false"},
              "corner_cell": {"none", "left", "right"},
              "inner_left": {"true", "false"}, "inner_right": {"true", "false"},
              "right": {"true", "false"}, "corner_left": {"true", "false"}, "corner_right": {"true", "false"},
              "bottom": {"true", "false"}}
LIGHT_PROPS = {"facing": {"north", "east", "south", "west"}, "front": {"true", "false"},
               "back": {"true", "false"}}
POST_PROPS = {"facing": {"north", "east", "south", "west"}, "up": {"true", "false"},
              "down": {"true", "false"}}
ROOF_PROPS = {"axis": {"x", "z"}, "side": {"pos", "neg", "crown"}, "level": {"0", "1", "2"},
              "end_neg": {"true", "false"}, "end_pos": {"true", "false"},
              "ridge": {"true", "false"},
              "join": {"none", "hip_pos", "hip_neg", "valley_pos", "valley_neg", "peak"}}


def verify():
    problems = []
    import gen_el2_stairs
    import gen_el2_structure
    for block, props in [("el_roof", ROOF_PROPS), ("el_post", POST_PROPS), ("el_post_named", POST_PROPS),
                         ("el_railing", RAIL_PROPS), ("el_railing_sign", RAIL_PROPS),
                         ("el_wall", WALL_PROPS), ("el_wall_glass", WALL_PROPS), ("el_wall_sign", WALL_PROPS),
                         ("el_wall_cream", WALL_PROPS), ("el_wall_green", WALL_PROPS), ("el_wall_doorway", WALL_PROPS),
                         ("el_platform_lamp", POST_PROPS), ("el_roof_light", LIGHT_PROPS)] + gen_el2_stairs.VERIFY + gen_el2_structure.VERIFY:
        bs = json.load(open(os.path.join(BLOCKSTATES, block + ".json")))
        if "variants" in bs:
            for key, variant in bs["variants"].items():
                for kv in key.split(","):
                    k, v = kv.split("=")
                    if k not in props or v not in props[k]:
                        problems.append(f"{block} variant {k}={v} not a block property value")
                name = variant["model"].split("/")[-1]
                if not os.path.exists(os.path.join(MODELS, name + ".json")):
                    problems.append(f"missing model {name}")
            continue
        for part in bs["multipart"]:
            for k, v in part.get("when", {}).items():
                if k not in props or any(alt not in props[k] for alt in v.split("|")):
                    problems.append(f"{block} when {k}={v} not a block property value")
            name = part["apply"]["model"].split("/")[-1]
            if not os.path.exists(os.path.join(MODELS, name + ".json")):
                problems.append(f"missing model {name}")
    for name in WRITTEN:
        m = json.load(open(os.path.join(MODELS, name + ".json")))
        for el in m["elements"]:
            for c in el["from"] + el["to"]:
                if c < -16 or c > 32:
                    problems.append(f"{name}: coordinate {c} outside -16..32")
            for f, face in el["faces"].items():
                if "uv" not in face:
                    problems.append(f"{name}: face {f} without explicit uv")
                t = face["texture"]
                if t.startswith("#") and t[1:] not in m["textures"]:
                    problems.append(f"{name}: face {f} texture ref {t} unresolved")
    for t in ("el2_green", "el2_roof_red", "el2_soffit", "el2_lattice", "el2_black"):
        if not os.path.exists(os.path.join(TEXTURES, t + ".png")):
            problems.append(f"missing texture {t}")
    if problems:
        for p in problems:
            print("VERIFY:", p)
        sys.exit(1)
    print(f"verify ok: {len(WRITTEN)} models")


def main():
    write_png("el2_green", tex_green())
    write_png("el2_roof_red", tex_roof_red())
    write_png("el2_soffit", tex_soffit())
    write_png("el2_lattice", tex_lattice())
    write_png("el2_black", tex_black())
    post_assets()
    railing_assets()
    walls_assets()
    lamp_assets()
    roof_light_assets()
    sign_models()
    roof_models()
    join_models()
    roof_blockstate()
    roof_item()
    import gen_el2_stairs
    gen_el2_stairs.build()
    import gen_el2_structure
    gen_el2_structure.build()
    verify()


if __name__ == "__main__":
    main()
