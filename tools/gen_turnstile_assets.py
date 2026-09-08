#!/usr/bin/env python3
"""Fare-array family v2 (2026-09-06, from-scratch rebuild): every texture,
model, blockstate, item icon and loot table for `turnstile`, `turnstile_exit`,
`turnstile_cap` and `turnstile_heet`.

Run from anywhere:  python3 tools/gen_turnstile_assets.py [--preview DIR]

Built to the real hardware (see TURNSTILE_V2.md for the photo/drawing
research). Scale is 1 block = 1 m (1 px = 6.25 cm), everything authored in
the NORTH frame: an ENTERING rider walks toward -z, the unpaid side is +z,
the cabinet is on the rider's RIGHT (x 11..16), the lane is x 0..11 and the
next unit's cabinet closes it at x = 0. The MOVING parts (tripod, HEET rotor)
are NOT here — TurnstileRenderer draws them from the block entity.

Contracts honoured (each learned the hard way elsewhere in this repo):
- every face carries an explicit uv inside 0..16 (auto-UV bleeds the atlas
  for elements outside the block; `face_uv()` reproduces vanilla's auto-UV
  and wraps it back onto the sprite);
- steel textures vary VERTICALLY only (stacked/adjacent faces sampling the
  same window never band);
- no two elements share a plane unless the buried face is omitted;
- verify() checks every blockstate when-key against the Java property sets
  and every model face's uv.
"""

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data")
MOD = "station_announcer"

# ---------------------------------------------------------------- palette --
STEEL = (172, 174, 178, 255)
STEEL_LIT = (204, 206, 210, 255)
STEEL_BRIGHT = (226, 228, 232, 255)
STEEL_DARK = (136, 138, 142, 255)
STEEL_SHADOW = (104, 106, 110, 255)
PLINTH = (62, 64, 67, 255)
PLINTH_LIT = (84, 86, 90, 255)
BLACK = (14, 15, 16, 255)
PANEL = (28, 29, 31, 255)
WHITE = (242, 242, 240, 255)
GREEN = (0, 147, 60, 255)
GREEN_LIT = (70, 214, 110, 255)
GREEN_CORE = (190, 255, 200, 255)
RED = (204, 22, 30, 255)
RED_LIT = (255, 70, 70, 255)
RED_CORE = (255, 200, 200, 255)
AMBER = (240, 170, 30, 255)
AMBER_CORE = (255, 230, 150, 255)
LAMP_OFF = (40, 44, 42, 255)
DISPLAY = (16, 22, 18, 255)
DISPLAY_GREEN = (80, 230, 120, 255)
OMNY_BLUE = (60, 120, 255, 255)
POSTER_BLUE = (0, 57, 166, 255)
POSTER_YELLOW = (252, 204, 10, 255)
CLEAR = (0, 0, 0, 0)


# --------------------------------------------------------------- textures --
def brushed(rows, x0, y0, x1, y1, base=STEEL, lit=STEEL_LIT, dark=STEEL_DARK,
            bright=STEEL_BRIGHT, seam=STEEL_SHADOW, seed=0):
    """#4 satin stainless: per-COLUMN tones only (vertical grain), so a face
    sampling any window of it never bands against its neighbours."""
    tones = [base, lit, base, dark, base, bright, base, lit, base, base, dark, base, lit]
    for x in range(x0, x1):
        t = tones[(x * 7 + seed * 3) % len(tones)]
        if (x * 11 + seed) % 17 == 5:
            t = seam
        for y in range(y0, y1):
            rows[y][x] = t


def tex_steel():
    rows = pk.canvas(32, 32, STEEL)
    brushed(rows, 0, 0, 32, 32)
    return rows


def tex_steel_dark():
    rows = pk.canvas(32, 32, PLINTH)
    brushed(rows, 0, 0, 32, 32, base=PLINTH, lit=PLINTH_LIT, dark=(50, 52, 55, 255),
            bright=(96, 98, 102, 255), seam=(40, 42, 44, 255), seed=3)
    return rows


def tex_flat():
    """Grain-free steel: the drum canopy lids (eight rotated slabs share those
    planes, any grain would show eight seams) and the renderer's tubes."""
    return pk.canvas(32, 32, (184, 186, 190, 255))


def tex_perf():
    """16-gauge perforated sheet: 1-texel holes on a staggered 2-texel grid
    (2 texels per model px — the drawing's 1/4 in holes on 7/16 centres are
    sub-pixel, this is the coarsest pattern that still reads as perforation)."""
    rows = pk.canvas(32, 32, STEEL)
    brushed(rows, 0, 0, 32, 32, base=(160, 162, 166, 255), seed=5)
    for y in range(32):
        for x in range(32):
            if (x + (y // 2) * 1) % 2 == 0 and y % 2 == 0:
                rows[y][x] = CLEAR
    return rows


def seams(rows, cols=(), rowlines=(), colour=(120, 122, 126, 255), x0=0, x1=32, y0=0, y1=32):
    """Panel seam lines (1 texel) — the only non-vertical detail steel gets;
    seams sit where panel joints are on the real cabinet."""
    for c in cols:
        pk.vline(rows, c, y0, y1, colour)
    for r in rowlines:
        pk.hline(rows, r, colour, x0, x1)


def tex_cabinet():
    """Cabinet SIDE faces (auto-UV, 2 texels/px): brushed stainless with the
    real cabinet's panel joints — a horizontal seam at y 12 (row 8) and
    verticals at z 4 / z 12 — plus a few fastener dots."""
    rows = tex_steel()
    seams(rows, cols=(8, 24), rowlines=(8,))
    for (x, y) in ((4, 4), (28, 4), (4, 28), (28, 28), (16, 12)):
        rows[y][x] = (110, 112, 116, 255)
    return rows


def tex_cabinet_end():
    """Approach end (south face, texels u 22..32 v 0..30): a recessed label
    plate low on the face (the "Special entry" plate spot) and a seam."""
    rows = tex_steel()
    brushed(rows, 22, 0, 32, 32, seed=2)
    seams(rows, rowlines=(8,), x0=22, x1=32)
    pk.rect(rows, 24, 18, 30, 26, (118, 120, 124, 255))
    pk.rect(rows, 25, 19, 29, 25, (150, 152, 156, 255))
    pk.rect(rows, 25, 20, 29, 21, (40, 120, 60, 255))
    return rows


def tex_recess():
    """The dark mechanism cover recessed into the lane side under the tripod."""
    rows = pk.canvas(32, 32, (26, 27, 29, 255))
    brushed(rows, 0, 0, 32, 32, base=(26, 27, 29, 255), lit=(34, 35, 38, 255), dark=(20, 21, 23, 255),
            bright=(40, 41, 44, 255), seam=(16, 17, 18, 255), seed=11)
    return rows


def tex_reader():
    """Reader unit on the cabinet top. OMNY tablet face at u 0..8 v 0..12
    (black, blue corner lights, tap glyph); reader box top at u 16..32
    v 24..32 (MetroCard swipe track with arrows, yellow decal)."""
    rows = tex_steel()
    pk.rect(rows, 0, 0, 8, 12, PANEL)
    pk.rect(rows, 1, 1, 7, 11, (10, 12, 20, 255))
    for (x, y) in ((1, 1), (6, 1), (1, 10), (6, 10)):
        rows[y][x] = OMNY_BLUE
        rows[y][x + (1 if x == 1 else -1)] = OMNY_BLUE
        rows[y + (1 if y == 1 else -1)][x] = OMNY_BLUE
    pk.rect(rows, 3, 4, 5, 8, WHITE)
    rows[6][4] = (10, 12, 20, 255)
    # swipe track
    pk.rect(rows, 16, 24, 32, 32, (150, 152, 156, 255))
    pk.rect(rows, 17, 27, 31, 29, (40, 42, 46, 255))
    for x in (19, 23, 27):
        rows[26][x] = BLACK
        rows[30][x] = BLACK
    pk.rect(rows, 28, 25, 31, 27, POSTER_YELLOW)
    return rows


def _indicator_face(rows, u0, v0, label="ENTRY"):
    """Indicator stack on a 20 x 36 texel face (4 texels/px, 5 x 9 px):
    display window, two dark lamp discs, label plate, arrow disc, LCD."""
    brushed(rows, u0, v0, u0 + 20, v0 + 36, seed=4)
    cx = u0 + 10
    pk.rect(rows, u0 + 2, v0 + 1, u0 + 18, v0 + 7, BLACK)          # display window
    pk.rect(rows, u0 + 3, v0 + 2, u0 + 17, v0 + 6, DISPLAY)
    for cy in (v0 + 10, v0 + 16):                                  # lamps (dark at rest)
        pk.disc(rows, cx, cy, 3, (60, 62, 66, 255))
        pk.disc(rows, cx, cy, 2, LAMP_OFF)
    pk.rect(rows, u0 + 1, v0 + 20, u0 + 19, v0 + 26, BLACK)         # label plate
    pk.text_centred(rows, cx, v0 + 21, label[:5], WHITE, 1, 1)
    pk.disc(rows, cx, v0 + 30, 3, GREEN)                            # arrow disc
    for i in range(3):
        rows[v0 + 29 + i][cx - 1 + i] = (240, 120, 40, 255)
    rows[v0 + 31][cx + 1] = (240, 120, 40, 255)
    rows[v0 + 30][cx + 1] = (240, 120, 40, 255)
    pk.rect(rows, u0 + 4, v0 + 34, u0 + 16, v0 + 36, DISPLAY)       # LCD strip


def _exit_face(rows, u0, v0):
    """Paid-side face: black EXIT label + the green disc with the orange arrow."""
    brushed(rows, u0, v0, u0 + 20, v0 + 36, seed=6)
    cx = u0 + 10
    pk.rect(rows, u0 + 1, v0 + 8, u0 + 19, v0 + 14, BLACK)
    pk.text_centred(rows, cx, v0 + 9, "EXIT", WHITE, 1, 1)
    pk.disc(rows, cx, v0 + 22, 5, GREEN)
    for i in range(5):
        rows[v0 + 19 + i][cx - 2 + i] = (240, 120, 40, 255)
        rows[v0 + 20 + i][cx - 2 + i] = (240, 120, 40, 255)
    pk.rect(rows, cx, v0 + 23, cx + 3, v0 + 25, (240, 120, 40, 255))
    pk.rect(rows, cx + 1, v0 + 21, cx + 3, v0 + 25, (240, 120, 40, 255))


def _no_entry_face(rows, u0, v0):
    """Unpaid-side face of an exit-only lane: fixed red no-entry roundel."""
    brushed(rows, u0, v0, u0 + 20, v0 + 36, seed=7)
    cx = u0 + 10
    pk.rect(rows, u0 + 1, v0 + 8, u0 + 19, v0 + 14, BLACK)
    pk.text_centred(rows, cx, v0 + 9, "EXIT", WHITE, 1, 1)
    pk.disc(rows, cx, v0 + 22, 5, RED)
    pk.rect(rows, cx - 3, v0 + 21, cx + 4, v0 + 24, WHITE)


def tex_pylon(exit_variant):
    """64 px (4 texels/px). Pylon body is x 11..16, y 0..9, z 0..4 in the
    upper model: south (+z, unpaid) face = texels u 44..64 v 28..64,
    north (paid) face = u 0..20 v 28..64."""
    rows = pk.canvas(64, 64, STEEL)
    brushed(rows, 0, 0, 64, 64, seed=1)
    if exit_variant:
        _no_entry_face(rows, 44, 28)
        _indicator_face(rows, 0, 28, label="EXIT")
    else:
        _indicator_face(rows, 44, 28)
        _exit_face(rows, 0, 28)
    return rows


def tex_heet_pylon():
    """HEET indicator pylon, 64 px at 2 texels/px: face art u 0..8 v 0..44
    (4 x 22 px): display, ENTRY label, two lamp discs."""
    rows = pk.canvas(64, 64, STEEL)
    brushed(rows, 0, 0, 64, 64, seed=8)
    pk.rect(rows, 1, 4, 7, 9, BLACK)
    pk.rect(rows, 2, 5, 6, 8, DISPLAY)
    pk.rect(rows, 1, 12, 7, 18, BLACK)
    pk.rect(rows, 2, 14, 6, 16, WHITE)          # too small for text: a white bar
    pk.disc(rows, 4, 24, 3, (60, 62, 66, 255))
    pk.disc(rows, 4, 24, 2, LAMP_OFF)
    pk.disc(rows, 4, 33, 3, (60, 62, 66, 255))
    pk.disc(rows, 4, 33, 2, LAMP_OFF)
    return rows


def tex_lamp():
    """Lit lamp/display plates, 32 px, sampled by EXPLICIT uv windows:
    discs in rows 0..8 (green 0..8, red 8..16, amber 16..24, transparent
    24..32) and 16 x 6 display strips: GO (u0 v8), STOP (u16 v8),
    WAIT (u0 v14), blank lit (u16 v14). Transparent elsewhere (cutout)."""
    rows = pk.canvas(32, 32, CLEAR)
    for i, (c, core) in enumerate(((GREEN_LIT, GREEN_CORE), (RED_LIT, RED_CORE), (AMBER, AMBER_CORE))):
        pk.disc(rows, i * 8 + 4, 4, 4, c)
        pk.disc(rows, i * 8 + 4, 3, 1, core)
    # check on green, cross on red
    for k in range(3):
        rows[4 + k][2 + k] = WHITE if k < 2 else WHITE
    rows[6][4] = WHITE
    rows[5][5] = WHITE
    rows[4][6] = WHITE
    for k in range(5):
        rows[2 + k][10 + k] = WHITE
        rows[2 + k][14 - k] = WHITE
    for (u, v, s, col) in ((0, 8, "GO", DISPLAY_GREEN), (16, 8, "STOP", RED_LIT),
                           (0, 14, "WAIT", AMBER), (16, 14, "", DISPLAY_GREEN)):
        pk.rect(rows, u, v, u + 16, v + 6, DISPLAY)
        if s:
            pk.text_centred(rows, u + 8, v + 1, s, col, 1, 1)
    return rows


def tex_sign():
    """HEET drum band, 64 px: black band rows 0..12 (6 px x 2 texels) with
    ENTRY and a down arrow, centred on u 32; the five drum front faces take
    consecutive 11.94-texel windows of it (see heet_drum())."""
    rows = pk.canvas(64, 64, BLACK)
    brushed(rows, 0, 12, 64, 64, seed=9)
    pk.rect(rows, 0, 0, 64, 12, BLACK)
    pk.text_centred(rows, 27, 1, "ENTRY", WHITE, 2, 1)
    # down arrow at u 49..57
    pk.rect(rows, 52, 1, 55, 8, WHITE)
    for i in range(4):
        pk.rect(rows, 50 + i, 6 + i, 57 - i, 7 + i, WHITE)
    return rows


# ------------------------------------------------------------------ icons --
def icon(kind):
    rows = pk.canvas(16, 16, CLEAR)
    if kind == "heet":
        pk.rect(rows, 1, 0, 15, 3, STEEL_LIT)           # drum
        pk.rect(rows, 3, 1, 13, 2, BLACK)
        pk.rect(rows, 2, 3, 3, 16, STEEL_DARK)          # posts
        pk.rect(rows, 13, 3, 14, 16, STEEL_DARK)
        pk.rect(rows, 7, 3, 9, 16, STEEL_DARK)          # spindle
        for y in range(4, 16, 2):
            pk.rect(rows, 3, y, 7, y + 1, STEEL_LIT)
            pk.rect(rows, 9, y, 13, y + 1, STEEL_LIT)
        return rows
    if kind == "cap":
        pk.rect(rows, 9, 0, 12, 16, STEEL)              # post
        pk.rect(rows, 4, 6, 14, 16, STEEL_LIT)          # end panel
        pk.rect(rows, 4, 15, 14, 16, PLINTH)
        pk.rect(rows, 10, 0, 11, 16, STEEL_BRIGHT)
        return rows
    # turnstile / exit: pylon, cabinet, hood, tripod arm
    pk.rect(rows, 9, 6, 15, 16, STEEL)                  # cabinet
    pk.rect(rows, 9, 15, 15, 16, PLINTH)
    pk.rect(rows, 10, 7, 14, 12, WHITE)                 # poster
    pk.rect(rows, 10, 7, 14, 8, POSTER_BLUE)
    pk.rect(rows, 11, 0, 14, 6, STEEL_LIT)              # pylon
    pk.rect(rows, 12, 1, 13, 2, DISPLAY)
    pk.disc(rows, 12, 4, 1, RED if kind == "exit" else GREEN_LIT)
    pk.rect(rows, 1, 7, 9, 8, STEEL_BRIGHT)             # blocking arm
    pk.rect(rows, 8, 6, 10, 9, STEEL_DARK)              # hub
    for i in range(4):                                  # down-forward arm
        pk.rect(rows, 6 - i, 8 + i, 7 - i, 9 + i, STEEL_LIT)
    return rows


# ------------------------------------------------------------------ models --
def wj(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


TEX = {
    "steel": f"{MOD}:block/ts_steel",
    "dark": f"{MOD}:block/ts_steel_dark",
    "perf": f"{MOD}:block/ts_perf",
    "cabinet": f"{MOD}:block/ts_cabinet",
    "cabinet_end": f"{MOD}:block/ts_cabinet_end",
    "recess": f"{MOD}:block/ts_recess",
    "reader": f"{MOD}:block/ts_reader",
    "pylon": f"{MOD}:block/ts_pylon",
    "pylon_exit": f"{MOD}:block/ts_pylon_exit",
    "heet_pylon": f"{MOD}:block/ts_heet_pylon",
    "lamp": f"{MOD}:block/ts_lamp",
    "sign": f"{MOD}:block/ts_sign",
    "flat": f"{MOD}:block/ts_flat",
    "particle": f"{MOD}:block/ts_steel",
}

FACES = ("down", "up", "north", "south", "west", "east")


def _wrap(u0, v0, u1, v1):
    """Shift a uv window by multiples of 16 so it lies inside the sprite; a
    window wider than the sprite is clamped (only the uniform steel ever is)."""
    def fix(a, b):
        lo, hi = min(a, b), max(a, b)
        if hi - lo >= 16:
            return (0.0, 16.0) if a <= b else (16.0, 0.0)
        shift = -16 * math.floor(lo / 16)
        lo, hi = lo + shift, hi + shift
        if hi > 16:
            lo, hi = lo - 16, hi - 16
            if lo < 0:  # straddles a seam: slide onto the sprite
                hi -= lo
                lo = 0.0
        return (lo, hi) if a <= b else (hi, lo)
    (u0, u1), (v0, v1) = fix(u0, u1), fix(v0, v1)
    return [round(u0, 4), round(v0, 4), round(u1, 4), round(v1, 4)]


def face_uv(face, frm, to):
    """Vanilla auto-UV for `face` of the box frm..to, wrapped onto the sprite."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    if face == "down":
        return _wrap(x0, 16 - z1, x1, 16 - z0)
    if face == "up":
        return _wrap(x0, z0, x1, z1)
    if face == "north":
        return _wrap(16 - x1, 16 - y1, 16 - x0, 16 - y0)
    if face == "south":
        return _wrap(x0, 16 - y1, x1, 16 - y0)
    if face == "west":
        return _wrap(z0, 16 - y1, z1, 16 - y0)
    return _wrap(16 - z1, 16 - y1, 16 - z0, 16 - y0)


def box(frm, to, tex="steel", omit=(), override=None, rotation=None, shade=None, cull=None):
    """One element with auto-UV steel on every face; `override` maps a face
    name to (tex, uv) for painted faces; `omit` drops buried faces."""
    faces = {}
    for face in FACES:
        if face in omit:
            continue
        if override and face in override:
            t, uv = override[face]
            faces[face] = {"texture": "#" + t, "uv": uv}
        else:
            faces[face] = {"texture": "#" + tex, "uv": face_uv(face, frm, to)}
        if cull and face in cull:
            faces[face]["cullface"] = cull[face]
    el = {"from": list(frm), "to": list(to), "faces": faces}
    if rotation:
        el["rotation"] = rotation
    if shade is not None:
        el["shade"] = shade
    return el


def rot(origin, axis, angle, rescale=False):
    r = {"origin": list(origin), "axis": axis, "angle": angle}
    if rescale:
        r["rescale"] = True
    return r


def model(name, elements, extra_tex=None):
    textures = dict(TEX)
    if extra_tex:
        textures.update(extra_tex)
    wj(os.path.join(ASSETS, "models/block", name + ".json"),
       {"textures": textures, "elements": elements})


def plate(frm, to, face, tex, uv):
    """Painted decal plate: only its outward face draws, unshaded."""
    faces = {face: {"texture": "#" + tex, "uv": uv}}
    return {"from": list(frm), "to": list(to), "faces": faces, "shade": False}


# --------------------------------------------------------------- low unit --
# Cabinet x 11..16 (right of the rider), lane x 0..11, unpaid side +z.
def chamfer_x(x0, x1, y_top, z_edge, run, depth):
    """45-deg chamfer cutting the corner at (y_top, z_edge + run): a box authored
    along +z from z_edge, `depth` below y_top, rotated +45 about x at the
    edge, so its outer face is the slope and the rest is buried."""
    length = run * math.sqrt(2)
    return box((x0, y_top - depth, z_edge), (x1, y_top, z_edge + length), "steel",
               rotation=rot((x0, y_top, z_edge), "x", 45))


def cabinet_body(recessed):
    """Low stainless body x 11..16, full block long, chamfered front-top
    corner (z 12..16). `recessed`: dark mechanism cover set into the lane
    side between stainless stiles/rails (tripod side); the end panel is plain."""
    els = [box((11.4, 0, 0.4), (15.6, 1, 15.6), "dark", omit=("up",), cull={"down": "down"})]
    if recessed:
        els += [
            # main body behind the recess (its west face IS the dark cover)
            box((11.6, 1, 0), (16, 16, 12), "cabinet", omit=("south",),
                override={"west": ("recess", [0, 0, 16, 15])}),
            box((11, 1, 0), (11.6, 16, 3), "steel", omit=("east", "south")),        # rear stile
            box((11, 1, 13), (11.6, 12, 16), "steel", omit=("east", "north")),      # front stile
            box((11, 11, 3), (11.6, 16, 13), "steel", omit=("east",)),              # top rail
            box((11, 1, 3), (11.6, 2, 13), "steel", omit=("east",)),                # sill
        ]
    else:
        els += [box((11, 1, 0), (16, 16, 12), "cabinet", omit=("south",))]
    # front lower block under the chamfer, its end face carrying the label plate
    els.append(box((11.6 if recessed else 11, 1, 12), (16, 12, 16), "cabinet", omit=("north", "up"),
                   override={"south": ("cabinet_end", [11, 4, 16, 15])}))
    els.append(chamfer_x(11, 16, 16, 12, 4, 3.2))
    return els


def reader_elements():
    """Reader unit mid-top (UPPER model: the cabinet lid is y 0): box with the
    MetroCard track on its lid and an OMNY tablet leaning back on its
    approach face, plus the swipe rib along the lane edge."""
    return [
        box((11.6, 0, 6), (15.4, 2.4, 10), "steel", omit=("down",),
            override={"up": ("reader", [8, 12, 16, 16])}),
        box((12.2, 0.3, 9.7), (14.8, 3.5, 10.3), "dark", omit=("down",),
            override={"south": ("reader", [0, 0, 4, 6])},
            rotation=rot((13.5, 0.3, 10), "x", -22.5)),
        box((11.7, 0, 2), (12.9, 0.8, 6), "steel", omit=("down",)),
    ]


def pylon_elements(tex, indicator=True):
    """Indicator column at the paid end: x 11..16, z 0..4, vertical face to
    y 9, flat top to y 11 with a chisel chamfer toward the approach, and an
    octagonal collar the arch pipe rises from (the renderer draws the pipe)."""
    faces = {"south": (tex, [11, 7, 16, 16]), "north": (tex, [0, 7, 5, 16])} if indicator else None
    els = [
        box((11, 0, 0), (16, 9, 4), "steel", omit=("up",), override=faces),
        box((11, 9, 0), (16, 11, 2), "steel", omit=("down", "south")),
        box((11, 9, 2), (16, 9.6, 4), "steel", omit=("down", "north", "up")),
        chamfer_x(11, 16, 11, 2, 2, 1.6),
        # collar: octagon r 1.4 about (13.5, ., 1.4)
        box((12.1, 11, 0), (14.9, 14, 2.8), "steel", omit=("down",)),
        box((12.1, 11, 0), (14.9, 14, 2.8), "steel", omit=("down",),
            rotation=rot((13.5, 11, 1.4), "y", 45)),
    ]
    return els


def lamp_models(prefix, face_z, face):
    """Lit-state models on the pylon face (upper model coords): GO lights the
    upper disc + GO display, STOP the lower disc + STOP display, WAIT the
    amber display. Disc centres y 6.5 / 5.0 match tex_pylon's dark discs."""
    def disc(cy, u0):
        r = 0.78
        if face == "south":
            frm, to = (13.5 - r, cy - r, face_z), (13.5 + r, cy + r, face_z + 0.06)
        else:
            frm, to = (13.5 - r, cy - r, face_z - 0.06), (13.5 + r, cy + r, face_z)
        return plate(frm, to, face, "lamp", [u0, 0, u0 + 4, 4])

    def display(u0, v0):
        if face == "south":
            frm, to = (11.6, 7.3, face_z), (15.4, 8.7, face_z + 0.06)
        else:
            frm, to = (11.6, 7.3, face_z - 0.06), (15.4, 8.7, face_z)
        return plate(frm, to, face, "lamp", [u0, v0, u0 + 8, v0 + 3])

    states = {
        "go": [disc(6.5, 0), display(0, 4)],
        "stop": [disc(5.0, 4), display(8, 4)],
        "wait": [display(0, 7)],
    }
    for state, els in states.items():
        model(f"{prefix}_{state}", els)


def write_low_unit():
    model("ts_cabinet", cabinet_body(True))
    model("ts_upper", reader_elements() + pylon_elements("pylon"))
    model("ts_upper_exit", reader_elements() + pylon_elements("pylon_exit"))
    lamp_models("ts_lamp", 4.0, "south")      # entry lane: indicator toward the unpaid side
    lamp_models("ts_lamp_exit", 0.0, "north")  # exit lane: indicator toward the paid side


def write_cap():
    """Array end: the same stainless body and column without recess, readers
    or indicator; its collar receives the last lane's arch."""
    model("ts_cap_lower", cabinet_body(False))
    model("ts_cap_upper", pylon_elements("pylon", indicator=False))


# ------------------------------------------------------------------- HEET --
# Lane block: sheet cage, hub at x = 16 (the boundary), rider walks x ~ 10.
# Comb block = east neighbour (x 16..32 in this frame): post, comb bars,
# indicator pylon. Rotor (BER) has R 11 curved wings; drum canopy R 15.
HEET_HUB = (16.0, 8.0)
SHEET_R = 13.5
DRUM_R = 15.0
BAR_PITCH = 2.4
BAR_Y0 = 3.6
BAR_COUNT = 12


def sheet_panels(y0, y1, tex="perf", thick=0.4, r=SHEET_R):
    """Five flat panels of a 16-gon around the hub covering 112.5 deg on the
    -x side: authored tangent at 180 deg, rotated about y by 22.5 steps."""
    half = r * math.tan(math.radians(11.25))
    x = HEET_HUB[0] - r
    els = []
    for ang in (-45, -22.5, 0, 22.5, 45):
        els.append(box((x - thick / 2, y0, HEET_HUB[1] - half), (x + thick / 2, y1, HEET_HUB[1] + half),
                       tex, omit=("up",) if y1 == 16 else (),
                       rotation=rot((HEET_HUB[0], 0, HEET_HUB[1]), "y", ang)))
    return els


def heet_drum(y0, y1):
    """16-gon drum canopy R 15 about the hub: 5 full-diameter slabs authored
    along x rotated -45..45 plus 3 along z rotated -22.5..22.5. The five
    south-facing sides of the along-x slabs carry the ENTRY band."""
    half = DRUM_R * math.tan(math.radians(11.25))   # 2.98
    cx, cz = HEET_HUB
    els = []
    face_w = 2 * half * 2 / 4.0   # face width in uv units on the 64 px sign (2 texels/px, 4 texels/unit)
    for ang in (-45, -22.5, 0, 22.5, 45):
        i = int(round(ang / 22.5))
        u0 = 8 + (i - 0.5) * face_w
        u1 = 8 + (i + 0.5) * face_w
        els.append(box((cx - DRUM_R, y0, cz - half), (cx + DRUM_R, y1, cz + half), "steel",
                       override={"south": ("sign", [round(u0, 3), 0, round(u1, 3), 3]),
                                 "up": ("flat", [0, 0, 16, 6]), "down": ("flat", [0, 0, 16, 6])},
                       rotation=rot((cx, 0, cz), "y", ang)))
    for ang in (-22.5, 0, 22.5):
        els.append(box((cx - half, y0, cz - DRUM_R), (cx + half, y1, cz + DRUM_R), "steel",
                       override={"up": ("flat", [0, 0, 6, 16]), "down": ("flat", [0, 0, 6, 16])},
                       rotation=rot((cx, 0, cz), "y", ang)))
    return els


def comb_bars(y_lo, y_hi, y_shift):
    """Fixed comb bars from the post toward the hub, at half-pitch offset from
    the rotor bars so the wings interleave when they sweep through."""
    els = []
    for k in range(BAR_COUNT):
        y = BAR_Y0 + k * BAR_PITCH + BAR_PITCH / 2
        if y_lo <= y < y_hi:
            yy = y - y_shift
            els.append(box((19.5, yy - 0.5, 7.5), (29, yy + 0.5, 8.5), "steel", omit=("east",)))
    return els


def heet_pylon(y0, y1, y_shift, uv_v0, uv_v1):
    return box((28, y0 - y_shift, 2), (32, y1 - y_shift, 5), "steel",
               override={"south": ("heet_pylon", [0, uv_v0, 2, uv_v1])})


def write_heet():
    cx, cz = HEET_HUB
    model("ts_heet_lane_lower", sheet_panels(1, 16) + [
        box((cx - 2, 0, cz - 2), (cx + 2, 1, cz + 2), "dark"),             # bearing plate
        box((cx - 5, 0, cz - 5), (cx + 5, 0.4, cz + 5), "dark", omit=("down",)),  # floor plate
    ])
    model("ts_heet_lane_upper",
          sheet_panels(0, 10) + sheet_panels(10, 11, tex="steel", thick=1.0) + [
              box((0, 16, 6.5), (16, 18, 9.5), "steel", omit=("east",)),      # beam, west half
          ] + heet_drum(18, 24))
    model("ts_heet_comb_lower", [
        box((28, 0, 6), (32, 1, 10), "dark", omit=("up",)),                    # foot
        box((29, 1, 7), (31, 16, 9), "steel", omit=("up",)),                   # post
        heet_pylon(8, 16, 0, 7, 11),
    ] + comb_bars(0, 16, 0))
    model("ts_heet_comb_upper", [
        box((29, 0, 7), (31, 16, 9), "steel", omit=("up", "down")),
        box((16, 16, 6.5), (32, 18, 9.5), "steel", omit=("west",)),           # beam, east half
        heet_pylon(16, 30, 16, 0, 7),
        box((27.8, 14, 1.8), (32, 14.6, 5.2), "dark", omit=("down",)),        # pylon cap
    ] + comb_bars(16, 32, 16))
    # HEET lamps: on the pylon's south face (z = 5) of comb_upper.
    # Face is 4 px wide x 14 px tall here (world y 16..30); discs at pylon
    # texel rows 24 and 33 of the 44-row art => world y 30 - 12 = 18, 30 - 16.5 = 13.5
    # (lower block) — so put both discs in the upper model's range instead.
    def disc(cy, u0):
        r = 0.9
        return plate((30 - r, cy - r, 5.0), (30 + r, cy + r, 5.06), "south", "lamp", [u0, 0, u0 + 4, 4])

    def display(u0, v0):
        return plate((28.4, 11.5, 5.0), (31.6, 12.8, 5.06), "south", "lamp", [u0, v0, u0 + 8, v0 + 3])
    model("ts_heet_lamp_go", [disc(4.5, 0), display(0, 4)])
    model("ts_heet_lamp_stop", [disc(0.5, 4), display(8, 4)])
    model("ts_heet_lamp_wait", [display(0, 7)])


# ------------------------------------------------------------- blockstates --
YROT = {"north": 0, "east": 90, "south": 180, "west": 270}


def ap(mdl, facing):
    a = {"model": f"{MOD}:block/{mdl}"}
    if YROT[facing]:
        a["y"] = YROT[facing]
    return a


def turnstile_blockstate(upper_model, lamp_prefix):
    parts = []
    for facing in YROT:
        parts.append({"when": {"facing": facing, "half": "lower"}, "apply": ap("ts_cabinet", facing)})
        parts.append({"when": {"facing": facing, "half": "upper"}, "apply": ap(upper_model, facing)})
        for state in ("go", "stop", "wait"):
            parts.append({"when": {"facing": facing, "half": "upper", "indicator": state},
                          "apply": ap(f"{lamp_prefix}_{state}", facing)})
    return {"multipart": parts}


def cap_blockstate():
    parts = []
    for facing in YROT:
        parts.append({"when": {"facing": facing, "half": "lower"}, "apply": ap("ts_cap_lower", facing)})
        parts.append({"when": {"facing": facing, "half": "upper"}, "apply": ap("ts_cap_upper", facing)})
    return {"multipart": parts}


def heet_blockstate():
    parts = []
    for facing in YROT:
        for part in ("lane_lower", "lane_upper", "comb_lower", "comb_upper"):
            parts.append({"when": {"facing": facing, "part": part}, "apply": ap(f"ts_heet_{part}", facing)})
        for state in ("go", "stop", "wait"):
            parts.append({"when": {"facing": facing, "part": "comb_upper", "indicator": state},
                          "apply": ap(f"ts_heet_lamp_{state}", facing)})
    return {"multipart": parts}


# ------------------------------------------------------------ loot / recipe --
def loot(block, prop, value):
    return {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block}"}],
                   "conditions": [
                       {"condition": "minecraft:block_state_property",
                        "block": f"{MOD}:{block}", "properties": {prop: value}},
                       {"condition": "minecraft:survives_explosion"}]}],
        "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
    }


def recipes():
    return {
        "turnstile_exit": {
            "type": "minecraft:crafting_shapeless", "category": "redstone",
            "ingredients": [{"item": f"{MOD}:turnstile"}, {"item": "minecraft:red_dye"}],
            "result": {"item": f"{MOD}:turnstile_exit"},
            "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
        },
        "turnstile_heet": {
            "type": "minecraft:crafting_shaped", "category": "redstone",
            "key": {"B": {"item": "minecraft:iron_bars"}, "T": {"item": f"{MOD}:turnstile"}},
            "pattern": ["BB", "TB", "BB"],
            "result": {"item": f"{MOD}:turnstile_heet"},
            "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
        },
    }


# ------------------------------------------------------------------- verify --
# Java-side property sets — keep in sync with TurnstileBaseBlock / TurnstileBlock
# / TurnstileHeetBlock. One when-key naming a property the block lacks makes
# the client reject the whole file (the purple-box lesson).
FACINGS = {"north", "south", "east", "west"}
BOOL = {"true", "false"}
PROPS = {
    "turnstile": {"facing": FACINGS, "half": {"lower", "upper"}, "join": BOOL, "open": BOOL,
                  "indicator": {"off", "go", "stop", "wait"}},
    "turnstile_cap": {"facing": FACINGS, "half": {"lower", "upper"}},
    "turnstile_heet": {"facing": FACINGS,
                       "part": {"lane_lower", "lane_upper", "comb_lower", "comb_upper"},
                       "open": BOOL, "indicator": {"off", "go", "stop", "wait"}},
}
PROPS["turnstile_exit"] = PROPS["turnstile"]


def verify():
    seen_models = set()
    for block, props in PROPS.items():
        path = os.path.join(ASSETS, "blockstates", block + ".json")
        data = json.load(open(path))
        for part in data["multipart"]:
            when = part.get("when", {})
            for cond in when.get("OR", [when]):
                for key, value in cond.items():
                    assert key in props, f"{block}: unknown property {key}"
                    for v in str(value).split("|"):
                        assert v in props[key], f"{block}: bad value {key}={v}"
            mdl = part["apply"]["model"].split("/")[-1]
            mpath = os.path.join(ASSETS, "models/block", mdl + ".json")
            assert os.path.exists(mpath), f"{block}: missing model {mdl}"
            seen_models.add(mdl)
            mdata = json.load(open(mpath))
            for el in mdata["elements"]:
                for fname, face in el["faces"].items():
                    assert "uv" in face, f"{mdl}: face {fname} missing explicit uv"
                    u0, v0, u1, v1 = face["uv"]
                    assert 0 <= min(u0, u1) and max(u0, u1) <= 16 and \
                        0 <= min(v0, v1) and max(v0, v1) <= 16, f"{mdl}: uv off sprite {face['uv']}"
                    tex = face["texture"][1:]
                    assert tex in mdata["textures"], f"{mdl}: unknown texture key {tex}"
                if "rotation" in el:
                    assert el["rotation"]["angle"] in (-45, -22.5, 0, 22.5, 45), f"{mdl}: bad angle"
                for a, b in zip(el["from"], el["to"]):
                    assert -16 <= a <= 32 and -16 <= b <= 32, f"{mdl}: element out of range"
    print(f"verify: blockstates + {len(seen_models)} models OK")


# --------------------------------------------------------------------- main --
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", metavar="DIR", help="also write upscaled texture sheets")
    args = parser.parse_args()

    texdir = os.path.join(ASSETS, "textures/block")
    textures = {
        "ts_steel": tex_steel(),
        "ts_steel_dark": tex_steel_dark(),
        "ts_perf": tex_perf(),
        "ts_flat": tex_flat(),
        "ts_cabinet": tex_cabinet(),
        "ts_cabinet_end": tex_cabinet_end(),
        "ts_recess": tex_recess(),
        "ts_reader": tex_reader(),
        "ts_pylon": tex_pylon(False),
        "ts_pylon_exit": tex_pylon(True),
        "ts_heet_pylon": tex_heet_pylon(),
        "ts_lamp": tex_lamp(),
        "ts_sign": tex_sign(),
    }
    for name, rows in textures.items():
        pngtool.write_png(os.path.join(texdir, name + ".png"), rows)
    for name, kind in (("turnstile", "turnstile"), ("turnstile_exit", "exit"),
                       ("turnstile_heet", "heet"), ("turnstile_cap", "cap")):
        pngtool.write_png(os.path.join(ASSETS, "textures/item", name + ".png"), icon(kind))
        wj(os.path.join(ASSETS, "models/item", name + ".json"),
           {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}})

    write_low_unit()
    write_cap()
    write_heet()

    wj(os.path.join(ASSETS, "blockstates/turnstile.json"), turnstile_blockstate("ts_upper", "ts_lamp"))
    wj(os.path.join(ASSETS, "blockstates/turnstile_exit.json"),
       turnstile_blockstate("ts_upper_exit", "ts_lamp_exit"))
    wj(os.path.join(ASSETS, "blockstates/turnstile_cap.json"), cap_blockstate())
    wj(os.path.join(ASSETS, "blockstates/turnstile_heet.json"), heet_blockstate())

    for block in ("turnstile", "turnstile_exit", "turnstile_cap"):
        wj(os.path.join(DATA, MOD, "loot_tables/blocks", block + ".json"), loot(block, "half", "lower"))
    wj(os.path.join(DATA, MOD, "loot_tables/blocks/turnstile_heet.json"),
       loot("turnstile_heet", "part", "lane_lower"))
    for name, recipe in recipes().items():
        wj(os.path.join(DATA, MOD, "recipes", name + ".json"), recipe)

    verify()

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        for name, rows in textures.items():
            pngtool.write_png(os.path.join(args.preview, name + ".png"), pngtool.scale_nn(rows, 8, 8))
        print(f"previews in {args.preview}")


if __name__ == "__main__":
    main()
