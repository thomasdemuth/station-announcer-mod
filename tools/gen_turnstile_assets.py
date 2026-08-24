#!/usr/bin/env python3
"""Regenerates every turnstile asset: textures, models, blockstates, item
icons, loot tables and recipes for the fare-array family — `turnstile`,
`turnstile_exit`, `turnstile_heet` and `turnstile_cap`.

Run from anywhere:  python3 tools/gen_turnstile_assets.py [--preview DIR]

Design rules honoured here (learned elsewhere in this repo the hard way):
- Vertical-only shading on textures that tile across stacked blocks (the
  globe-pole lesson); the arm/tube cylinder gradients live in zones sampled
  by EXPLICIT uv slices, never auto-UV.
- Interior uv slices only — never let a face's window touch a sprite edge
  (the pink-line atlas-bleed lesson).
- No two elements share an exact plane unless the buried face is omitted
  (platform-barrier lesson); the cabinet plinth is proud and the body's
  down face is omitted against it.
- Every when-key in a blockstate is checked against the block's declared
  properties (the purple-box lesson) by verify() at the end of main().

The INDICATOR lamp is three separate models (off/go/stop) selected by the
blockstate — the upper model carries no lens at all, so the lamp never
z-fights itself.
"""

import argparse
import json
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
STEEL = (168, 170, 172, 255)
STEEL_LIT = (206, 208, 211, 255)
STEEL_BRIGHT = (228, 230, 233, 255)
STEEL_DARK = (128, 130, 133, 255)
STEEL_SHADOW = (96, 98, 101, 255)
KICK = (52, 53, 55, 255)
PILLAR = (16, 17, 18, 255)          # the black reader pillar
PILLAR_LIT = (36, 38, 40, 255)
SCREEN = (24, 34, 46, 255)
SCREEN_GLOW = (58, 92, 128, 255)
SLOT = (232, 232, 228, 255)
TAP = (222, 168, 24, 255)           # yellow tap target
TAP_DARK = (160, 118, 12, 255)
RUBBER = (28, 28, 30, 255)
RUBBER_LIT = (52, 52, 56, 255)
RED = (190, 24, 28, 255)
WHITE = (240, 240, 238, 255)
LAMP_OFF = (26, 30, 27, 255)
GO = (44, 168, 74, 255)
GO_CORE = (140, 245, 160, 255)
STOP = (186, 30, 32, 255)
STOP_CORE = (255, 128, 118, 255)


def brushed(rows, x0, y0, x1, y1, base=STEEL, seed=0):
    """Brushed stainless: fine vertical streaks, deterministic, tileable."""
    tones = [base, STEEL_LIT, base, STEEL_DARK, base, STEEL_LIT, base, base]
    for x in range(x0, x1):
        t = tones[(x * 7 + seed * 3) % len(tones)]
        for y in range(y0, y1):
            rows[y][x] = t


# ---------------------------------------------------------------- textures --
def tex_steel():
    """Generic brushed stainless, 32px, vertical-only variation."""
    rows = pk.canvas(32, 32, STEEL)
    brushed(rows, 0, 0, 32, 32)
    return rows


def tex_face(exit_variant):
    """The dressing texture. 32px. Layout (pixels):
    - x0..10  y0..20: reader-pillar FRONT, north/approach strip (10 model px tall)
    - x10..20 y0..20: reader-pillar front, south strip
    - x20..32 y0..20: pillar SIDE (plain black with a seam)
    - x0..32  y20..32: dark plinth/kick band (plinth + arm housing dress)
    The exit variant paints the white-on-red no-entry bar on the NORTH strip:
    that side is the wrong way through an exit-only lane."""
    rows = pk.canvas(32, 32, PILLAR)
    for sx in (0, 10):
        pk.rect(rows, sx, 0, sx + 10, 20, PILLAR)
        pk.rect(rows, sx, 0, sx + 1, 20, PILLAR_LIT)      # lit edge column
        pk.rect(rows, sx + 9, 0, sx + 10, 20, (10, 10, 11, 255))
        # screen window
        pk.rect(rows, sx + 2, 2, sx + 8, 6, SCREEN)
        pk.rect(rows, sx + 2, 2, sx + 8, 3, SCREEN_GLOW)
        # card slot
        pk.rect(rows, sx + 2, 8, sx + 8, 10, (8, 8, 9, 255))
        pk.rect(rows, sx + 3, 8, sx + 7, 9, SLOT)
        # yellow tap target
        pk.rect(rows, sx + 3, 12, sx + 7, 16, TAP_DARK)
        pk.rect(rows, sx + 4, 13, sx + 6, 15, TAP)
    if exit_variant:
        # no-entry roundel-bar over the north strip's screen zone
        pk.rect(rows, 1, 1, 9, 8, RED)
        pk.rect(rows, 2, 3, 8, 6, WHITE)
        # and the reader details only survive on the exit (south) side
        pk.rect(rows, 1, 8, 9, 20, PILLAR)
        pk.rect(rows, 0, 0, 1, 20, PILLAR_LIT)
        pk.rect(rows, 3, 12, 7, 16, TAP_DARK)
        pk.rect(rows, 4, 13, 6, 15, TAP)
    # pillar side zone
    pk.rect(rows, 20, 0, 32, 20, PILLAR)
    pk.rect(rows, 20, 0, 21, 20, PILLAR_LIT)
    # plinth/kick band
    pk.rect(rows, 0, 20, 32, 32, KICK)
    pk.rect(rows, 0, 20, 32, 22, (74, 75, 78, 255))
    pk.rect(rows, 0, 30, 32, 32, (34, 35, 36, 255))
    return rows


def tex_arm():
    """Arm tube shading. 32px. Rows are the cylinder gradient for the
    horizontal arm (sampled sideways-safe: full columns), columns 26..32 are
    the black rubber grip tip."""
    rows = pk.canvas(32, 32, STEEL)
    grad = [STEEL_BRIGHT, STEEL_LIT, STEEL_LIT, STEEL, STEEL, STEEL,
            STEEL_DARK, STEEL_SHADOW]
    for y in range(32):
        tone = grad[min(len(grad) - 1, y * len(grad) // 32)]
        for x in range(32):
            rows[y][x] = tone
    for x in range(26, 32):
        for y in range(32):
            rows[y][x] = RUBBER_LIT if y < 6 else RUBBER
    return rows


def tex_tube():
    """Overhead tube: bright crown rows, mid barrel, shadow bottom rows —
    faces take explicit slices at their own height (globe lesson)."""
    rows = pk.canvas(32, 32, STEEL)
    for y in range(32):
        if y < 5:
            tone = STEEL_BRIGHT
        elif y < 9:
            tone = STEEL_LIT
        elif y < 22:
            tone = STEEL
        elif y < 27:
            tone = STEEL_DARK
        else:
            tone = STEEL_SHADOW
        for x in range(32):
            rows[y][x] = tone
    return rows


SIGN_GREEN = (12, 96, 44, 255)
SIGN_GREEN_DARK = (8, 70, 32, 255)
SIGN_RED = (168, 22, 26, 255)
SIGN_RED_DARK = (128, 14, 18, 255)


def tex_sign(exit_variant):
    """The lane signboard hanging under the overhead rail (the long green
    'Entry' board in the 86 St photo). 32x16: rows 0..8 are the lettered
    front, rows 8..16 the plain back/edge colour."""
    base = SIGN_RED if exit_variant else SIGN_GREEN
    dark = SIGN_RED_DARK if exit_variant else SIGN_GREEN_DARK
    rows = pk.canvas(32, 32, base)            # square: MC rejects 32x16
    pk.rect(rows, 0, 7, 32, 8, dark)          # bottom shadow line of the front
    label = "EXIT" if exit_variant else "ENTRY"
    width = pk.text_width(label)
    total = width + 5                          # label + arrow
    x0 = (32 - total) // 2
    pk.text(rows, x0, 1, label, WHITE)
    ax = x0 + width + 2                        # down arrow, 3 px wide
    pk.rect(rows, ax + 1, 1, ax + 2, 5, WHITE)
    pk.rect(rows, ax, 4, ax + 3, 5, WHITE)
    rows[5][ax + 1] = WHITE
    return rows


def tex_lamp():
    """Lens sprite, 16px, three zones: rows 0..5 off, 5..10 go, 10..16 stop.
    Bright flat colour with a hot core — lenses, not lit geometry."""
    rows = pk.canvas(16, 16, LAMP_OFF)
    pk.rect(rows, 0, 5, 16, 10, GO)
    pk.rect(rows, 5, 6, 11, 9, GO_CORE)
    pk.rect(rows, 0, 10, 16, 16, STOP)
    pk.rect(rows, 5, 12, 11, 15, STOP_CORE)
    return rows


# lens uv windows (16-unit space), interior slices only
UV_LAMP = {"off": [5, 1, 11, 4], "go": [5, 6, 11, 9], "stop": [5, 11.5, 11, 14.5]}


def icon(kind):
    """Flat 16px item icon: front view of the unit."""
    rows = pk.canvas(16, 16, (0, 0, 0, 0))
    if kind == "heet":
        pk.rect(rows, 1, 1, 3, 16, STEEL_DARK)
        pk.rect(rows, 13, 1, 15, 16, STEEL_DARK)
        pk.rect(rows, 7, 1, 9, 16, (60, 62, 64, 255))
        for y in range(2, 15, 3):
            pk.rect(rows, 3, y, 7, y + 1, STEEL)
            pk.rect(rows, 9, y, 13, y + 1, STEEL)
        pk.rect(rows, 1, 0, 15, 1, STEEL_LIT)
    else:
        pk.rect(rows, 2, 4, 6, 16, STEEL)
        pk.rect(rows, 2, 4, 6, 5, STEEL_LIT)
        pk.rect(rows, 3, 0, 5, 4, PILLAR)
        pk.rect(rows, 3, 0, 5, 1, PILLAR_LIT)
        if kind == "exit":
            pk.rect(rows, 3, 1, 5, 3, RED)
            pk.rect(rows, 3, 2, 5, 3, WHITE)
        pk.rect(rows, 6, 8, 14, 10, STEEL_LIT)
        pk.rect(rows, 12, 8, 14, 10, RUBBER)
        pk.rect(rows, 6, 10, 8, 14, STEEL)
    return rows


# ------------------------------------------------------------------ models --
def wj(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


TEXTURES = {
    "steel": f"{MOD}:block/turnstile_steel",
    "face": f"{MOD}:block/turnstile_face",
    "tube": f"{MOD}:block/turnstile_tube",
    "arm": f"{MOD}:block/turnstile_arm",
    "lamp": f"{MOD}:block/turnstile_lamp",
    "sign": f"{MOD}:block/turnstile_sign",
    "sign_exit": f"{MOD}:block/turnstile_sign_exit",
    "particle": f"{MOD}:block/turnstile_steel",
}


def elem(frm, to, faces):
    return {"from": list(frm), "to": list(to), "faces": faces}


def f(tex, uv, cull=None):
    face = {"texture": "#" + tex, "uv": list(uv)}
    if cull:
        face["cullface"] = cull
    return face


def steel_box(frm, to, omit=(), seed_v=1.0):
    """A box skinned in generic brushed steel with interior slices sized to
    each face (1:1-ish texels, never touching the sprite edge)."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    w, h, d = x1 - x0, y1 - y0, z1 - z0
    def sl(a, b):
        a = min(a, 14.5)
        b = min(b, 14.5)
        v0 = min(seed_v, 15.25 - b)   # keep the window on the sprite
        return [0.75, v0, 0.75 + a, v0 + b]
    faces = {}
    if "north" not in omit: faces["north"] = f("steel", sl(w, h))
    if "south" not in omit: faces["south"] = f("steel", sl(w, h))
    if "east" not in omit: faces["east"] = f("steel", sl(d, h))
    if "west" not in omit: faces["west"] = f("steel", sl(d, h))
    if "up" not in omit: faces["up"] = f("steel", sl(w, d))
    if "down" not in omit: faces["down"] = f("steel", sl(w, d))
    return elem(frm, to, faces)


def model(name, elements):
    wj(os.path.join(ASSETS, "models/block", name + ".json"),
       {"parent": "minecraft:block/block", "textures": TEXTURES, "elements": elements})


def octo_tube(frm, to, axis, caps=()):
    """A pipe segment that reads ROUND: the core box plus a twin rotated 45
    degrees about its long axis (the standard octagonal-pole trick — the
    rotated faces cut through the core's corners, so nothing is coplanar).
    Side faces take the tube texture's barrel slice; caps only where asked
    (buried ends stay open)."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    length = {"x": x1 - x0, "y": y1 - y0, "z": z1 - z0}[axis]
    lu = min(14.5, length)
    side = f("tube", [1, 4.75, 1 + lu, 6])
    cap = f("tube", [2, 2.5, 3.9, 4.4])
    sides_by_axis = {
        "x": ("north", "south", "up", "down"),
        "y": ("north", "south", "east", "west"),
        "z": ("east", "west", "up", "down"),
    }
    faces = {name: side for name in sides_by_axis[axis]}
    for name in caps:
        faces[name] = cap
    core = elem(frm, to, faces)
    twin = elem(frm, to, {name: side for name in sides_by_axis[axis]})
    twin["rotation"] = {
        "origin": [(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2],
        "axis": axis, "angle": 45,
    }
    return [core, twin]


def write_cabinet():
    """Lower-half cabinet, north frame: silhouette unchanged (0..5 x, 1..15 z,
    15 tall) but built as plinth + body + stepped dome deck."""
    els = []
    # proud dark plinth; the body sits on it with its down face omitted
    els.append(elem([0, 0, 0.75], [5.25, 1.5, 15.25], {
        "north": f("face", [0, 13, 2.6, 13.75]),
        "south": f("face", [0, 13, 2.6, 13.75]),
        "east": f("face", [0, 12.6, 7.25, 13.35]),
        "west": f("face", [0, 12.6, 7.25, 13.35]),
        "up": f("face", [0, 10.5, 2.6, 12]),
        "down": f("face", [0, 14, 2.6, 15.75], cull="down"),
    }))
    els.append(steel_box([0.25, 1.5, 1], [5, 13, 15], omit=("down",)))
    # stainless deck: centre lid + two lower dome ends (inner faces buried)
    els.append(steel_box([0, 13, 3], [5, 15, 13], seed_v=3.0))
    els.append(steel_box([0, 13, 1], [5, 14.2, 3], omit=("south",), seed_v=5.0))
    els.append(steel_box([0, 13, 13], [5, 14.2, 15], omit=("north",), seed_v=5.0))
    model("turnstile_cabinet", els)


def write_arm():
    """The tripod arm cluster on the cabinet's lane side. Same pose as ever:
    pivot housing, horizontal arm with rubber tip, drop arm."""
    els = []
    els.append(steel_box([3.5, 9, 6.5], [6, 13, 9.5], seed_v=7.0))
    # horizontal arm: cylinder gradient rows; tip columns are the rubber grip
    arm_side = f("arm", [1, 2, 9.5, 3.5])
    els.append(elem([6, 10.5, 6.9], [14.5, 12, 9.1], {
        "north": arm_side, "south": arm_side,
        "east": f("arm", [13.5, 2, 15.5, 3.5]),
        "up": f("arm", [1, 0.5, 9.5, 2]),
        "down": f("arm", [1, 3.5, 9.5, 5]),
    }))
    els.append(elem([12.4, 10.4, 6.8], [14.6, 12.1, 9.2], {   # rubber tip sleeve
        "north": f("arm", [13.2, 8, 15.4, 9.7]),
        "south": f("arm", [13.2, 8, 15.4, 9.7]),
        "east": f("arm", [13.2, 8, 15.4, 9.7]),
        "west": f("arm", [13.2, 8, 15.4, 9.7]),
        "up": f("arm", [13.2, 8, 15.4, 9.7]),
        "down": f("arm", [13.2, 8, 15.4, 9.7]),
    }))
    # drop arm, slightly slimmer, steel
    els.append(steel_box([6, 6.5, 7.3], [7.8, 10.5, 8.7], seed_v=9.0))
    els.append(elem([5.9, 5.9, 7.2], [7.9, 6.6, 8.8], {      # rubber tip cap
        "north": f("arm", [13.2, 8, 15.4, 8.6]),
        "south": f("arm", [13.2, 8, 15.4, 8.6]),
        "east": f("arm", [13.2, 8, 15.4, 8.6]),
        "west": f("arm", [13.2, 8, 15.4, 8.6]),
        "down": f("arm", [13.2, 8, 15.4, 9.7]),
    }))
    model("turnstile_arm", els)


def pillar_elements(exit_variant):
    """Upper-half reader pillar. Front strips come from the face texture
    (north = approach strip, south = exit strip); no lens here — the lamp
    models own it."""
    tex = "face"
    els = []
    els.append(elem([0.5, 0, 4], [4.5, 10, 12], {
        "north": f(tex, [0.25, 0.25, 4.75, 9.75]),
        "south": f(tex, [5.25, 0.25, 9.75, 9.75]),
        "east": f(tex, [10.5, 0.5, 14.5, 9.5]),
        "west": f(tex, [10.5, 0.5, 14.5, 9.5]),
        "down": f(tex, [10.5, 0.5, 14.5, 4.5]),
    }))
    els.append(steel_box([0, 10, 3.5], [5, 11, 12.5], seed_v=2.0))
    # slim octagonal riser pipe up into the corner fitting at the rail plane
    # (photo: thin stanchion with a mounting collar at its foot and a cast
    # elbow where it turns into the rail). Down faces buried on the cap.
    els.append(steel_box([2.4, 11, 7.4], [3.6, 11.7, 8.6], omit=("down",), seed_v=13.0))
    els += octo_tube([2.5, 11.4, 7], [3.5, 21.9, 9], "y")
    els.append(steel_box([1.9, 21.8, 6.9], [4.1, 24, 9.1], omit=("up",), seed_v=13.5))
    return els


def lamp_models(prefix, boxes):
    """off/go/stop lens models: same boxes, different uv zone. `boxes` is a
    list of (frm, to, faces) where faces maps face name -> True; every listed
    face samples the zone window."""
    for state, uv in UV_LAMP.items():
        els = []
        for frm, to, sides in boxes:
            faces = {s: f("lamp", uv) for s in sides}
            els.append(elem(frm, to, faces))
        model(f"{prefix}_lamp_{state}", els)


def write_upper_and_lamps():
    model("turnstile_upper", pillar_elements(False))
    model("turnstile_upper_exit", pillar_elements(True))
    # lens sits proud of the pillar front and back, near the cap
    lamp_models("turnstile", [
        ([1.5, 7.6, 3.55], [3.5, 9.4, 4.1], ("north", "east", "west", "up", "down")),
        ([1.5, 7.6, 11.9], [3.5, 9.4, 12.45], ("south", "east", "west", "up", "down")),
    ])


def sign_plate(exit_variant):
    """The lane signboard hanging under the rail, centred over the walkway —
    the 86 St photo's green 'Entry' board (red EXIT on exit-only lanes)."""
    tex = "sign_exit" if exit_variant else "sign"
    front = f(tex, [0, 0, 16, 4])
    back = f(tex, [0, 4.25, 16, 7.75])
    edge = f(tex, [2, 4.5, 4, 7.5])
    return [elem([6, 19.8, 7.7], [14, 22.1, 8.3], {
        "north": front, "south": back,
        "east": edge, "west": edge, "down": f(tex, [2, 4.5, 15, 5.1]),
    })]


def write_tubes():
    """Overhead pipework, rebuilt from the 86 St photos: a slim octagonal
    rail on cast elbow fittings, buried at both ends inside the risers'
    corner fittings (which live in the upper/cap models at x 1.9..4.1), and
    the lane signboard hanging beneath. Cross-sections are inset 0.05 from
    the fittings' planes so nothing coplanar overlaps across models."""
    bridge = octo_tube([3.2, 22.05, 7.05], [18.8, 23.95, 8.95], "x")
    wj(os.path.join(ASSETS, "models/block/turnstile_tube_bridge.json"),
       {"parent": "minecraft:block/block", "textures": TEXTURES,
        "elements": bridge + sign_plate(False)})
    wj(os.path.join(ASSETS, "models/block/turnstile_tube_bridge_exit.json"),
       {"parent": "minecraft:block/block", "textures": TEXTURES,
        "elements": bridge + sign_plate(True)})
    # Row end: short rail out of the riser fitting, elbow fitting, drop pipe
    # with a closing flange — the photo's curled-down rail end.
    els = octo_tube([3.2, 22.05, 7.05], [7.1, 23.95, 8.95], "x")
    els.append(steel_box([6.9, 21.8, 6.9], [9.1, 24, 9.1], omit=("up",), seed_v=13.5))
    els += octo_tube([7.0, 18.9, 7.0], [9.0, 21.9, 9.0], "y")
    els.append(steel_box([6.8, 18.3, 6.8], [9.2, 19.0, 9.2], seed_v=13.0))
    wj(os.path.join(ASSETS, "models/block/turnstile_tube_end.json"),
       {"parent": "minecraft:block/block", "textures": TEXTURES, "elements": els})


def write_caps():
    """End cap: unchanged silhouette, plinth + dome polish to match."""
    els = [
        elem([0, 0, 0.75], [8.25, 1.5, 15.25], {
            "north": f("face", [0, 13, 4.1, 13.75]),
            "south": f("face", [0, 13, 4.1, 13.75]),
            "east": f("face", [0, 12.6, 7.25, 13.35]),
            "west": f("face", [0, 12.6, 7.25, 13.35]),
            "up": f("face", [0, 10.5, 4.1, 12]),
            "down": f("face", [0, 14, 4.1, 15.75], cull="down"),
        }),
        steel_box([0.25, 1.5, 1], [8, 14, 15], omit=("down",)),
        steel_box([0, 14, 2], [8, 16, 14], seed_v=3.0),
    ]
    model("turnstile_cap_lower", els)
    els = [
        steel_box([0, 0, 2], [8, 7, 14], seed_v=4.0),
        steel_box([1, 7, 4], [7, 9, 12], seed_v=6.0),
    ]
    els.append(steel_box([2.4, 9, 7.4], [3.6, 9.7, 8.6], omit=("down",), seed_v=13.0))
    els += octo_tube([2.5, 9.4, 7], [3.5, 21.9, 9], "y")
    els.append(steel_box([1.9, 21.8, 6.9], [4.1, 24, 9.1], omit=("up",), seed_v=13.5))
    model("turnstile_cap_upper", els)


def heet_sides(y0, y1, top_buried=False):
    """The two full-height side frames: corner posts + vertical bars, inset
    0.25 from the block edges so nothing sits on a boundary plane. With
    top_buried the up faces are omitted (the roof plate covers them)."""
    els = []
    post_omit = ("down",)                 # continues from / into the other half
    if top_buried:
        post_omit = post_omit + ("up",)
    elif y1 == 16:
        post_omit = ("up",)
    for x0, x1 in ((0.25, 1.75), (14.25, 15.75)):
        for z0, z1 in ((1, 2.5), (13.5, 15)):
            els.append(steel_box([x0, y0, z0], [x1, y1, z1], omit=post_omit, seed_v=2.5))
        for z in (4.4, 7.2, 10.0, 12.4):   # vertical infill bars
            els.append(steel_box([x0 + 0.15, y0, z], [x1 - 0.15, y1, z + 1.2],
                                 omit=("up", "down"), seed_v=8.0))
    return els


def heet_rotor(y_top):
    """Centre shaft + four comb wings (axis-aligned; the gate reads rotated
    just fine and voxel models cannot do 120 degrees). The shaft's ends are
    always buried (other half below, roof plate or the next block above)."""
    els = [steel_box([6.5, 0, 6.5], [9.5, y_top, 9.5], omit=("up", "down"), seed_v=10.0)]
    for y in (2.0, 5.2, 8.4, 11.6):
        if y + 1.2 > y_top:
            continue
        arm_o = ("up", "down")
        els.append(steel_box([9.5, y, 7.25], [13.9, y + 1.2, 8.75], seed_v=11.0))
        els.append(steel_box([2.1, y, 7.25], [6.5, y + 1.2, 8.75], seed_v=11.0))
        els.append(steel_box([7.25, y, 9.5], [8.75, y + 1.2, 13.9], seed_v=11.0))
        els.append(steel_box([7.25, y, 2.1], [8.75, y + 1.2, 6.5], seed_v=11.0))
    return els


def write_heet():
    lower = heet_sides(0, 16) + heet_rotor(16)
    model("turnstile_heet_lower", lower)
    upper = heet_sides(0, 13, top_buried=True) + heet_rotor(13)
    # roof plate sits directly on the frame; everything under it omits its
    # up face, so nothing z-fights and no daylight band shows above the bars
    upper.append(steel_box([0, 13, 0.75], [16, 15, 15.25], seed_v=3.5))
    model("turnstile_heet_upper", upper)
    # lenses on both approach faces of both posts (upper half, near the plate)
    lamp_models("turnstile_heet", [
        ([0.4, 10.5, 0.7], [1.6, 12, 1.25], ("north", "east", "west", "up", "down")),
        ([14.4, 10.5, 0.7], [15.6, 12, 1.25], ("north", "east", "west", "up", "down")),
        ([0.4, 10.5, 14.75], [1.6, 12, 15.3], ("south", "east", "west", "up", "down")),
        ([14.4, 10.5, 14.75], [15.6, 12, 15.3], ("south", "east", "west", "up", "down")),
    ])


# -------------------------------------------------------------- blockstates --
ROTS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def ap(mdl, rot):
    entry = {"model": f"{MOD}:block/{mdl}"}
    if rot:
        entry["y"] = rot
    return entry


def turnstile_blockstate(upper_model, bridge_model="turnstile_tube_bridge"):
    parts = []
    for facing, rot in ROTS:
        parts.append({"when": {"facing": facing, "half": "lower"}, "apply": ap("turnstile_cabinet", rot)})
        parts.append({"when": {"facing": facing, "half": "lower"}, "apply": ap("turnstile_arm", rot)})
        parts.append({"when": {"facing": facing, "half": "upper"}, "apply": ap(upper_model, rot)})
        for state in ("off", "go", "stop"):
            parts.append({"when": {"facing": facing, "half": "upper", "indicator": state},
                          "apply": ap(f"turnstile_lamp_{state}", rot)})
        parts.append({"when": {"facing": facing, "half": "upper", "right": "true"},
                      "apply": ap(bridge_model, rot)})
        parts.append({"when": {"facing": facing, "half": "upper", "right": "false"},
                      "apply": ap("turnstile_tube_end", rot)})
    return {"multipart": parts}


def cap_blockstate():
    parts = []
    for facing, rot in ROTS:
        parts.append({"when": {"facing": facing, "half": "lower"}, "apply": ap("turnstile_cap_lower", rot)})
        parts.append({"when": {"facing": facing, "half": "upper"}, "apply": ap("turnstile_cap_upper", rot)})
        parts.append({"when": {"facing": facing, "half": "upper", "right": "true"},
                      "apply": ap("turnstile_tube_bridge", rot)})
        parts.append({"when": {"facing": facing, "half": "upper", "right": "false"},
                      "apply": ap("turnstile_tube_end", rot)})
    return {"multipart": parts}


def heet_blockstate():
    parts = []
    for facing, rot in ROTS:
        parts.append({"when": {"facing": facing, "half": "lower"}, "apply": ap("turnstile_heet_lower", rot)})
        parts.append({"when": {"facing": facing, "half": "upper"}, "apply": ap("turnstile_heet_upper", rot)})
        for state in ("off", "go", "stop"):
            parts.append({"when": {"facing": facing, "half": "upper", "indicator": state},
                          "apply": ap(f"turnstile_heet_lamp_{state}", rot)})
    return {"multipart": parts}


# ---------------------------------------------------------------- data files --
def loot(block):
    return {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                            "name": f"{MOD}:{block}"}],
                   "conditions": [
                       {"condition": "minecraft:block_state_property",
                        "block": f"{MOD}:{block}", "properties": {"half": "lower"}},
                       {"condition": "minecraft:survives_explosion"}]}],
        "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
    }


def recipes():
    return {
        "turnstile_exit": {
            "type": "minecraft:crafting_shapeless",
            "category": "redstone",
            "ingredients": [{"item": f"{MOD}:turnstile"}, {"item": "minecraft:red_dye"}],
            "result": {"item": f"{MOD}:turnstile_exit"},
            "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
        },
        "turnstile_heet": {
            "type": "minecraft:crafting_shaped",
            "category": "redstone",
            "key": {"B": {"item": "minecraft:iron_bars"}, "T": {"item": f"{MOD}:turnstile"}},
            "pattern": ["BB", "TB", "BB"],
            "result": {"item": f"{MOD}:turnstile_heet"},
            "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
        },
    }


# ------------------------------------------------------------------- verify --
# The Java-side property sets — keep in sync with TurnstileBaseBlock (+
# TurnstileBlock's INDICATOR). One when-key naming a property the block lacks
# makes the client reject the whole file (the purple-box lesson).
PROPS = {
    "turnstile": {"facing": {"north", "south", "east", "west"},
                  "half": {"lower", "upper"}, "right": {"true", "false"},
                  "indicator": {"off", "go", "stop"}},
    "turnstile_cap": {"facing": {"north", "south", "east", "west"},
                      "half": {"lower", "upper"}, "right": {"true", "false"}},
}
PROPS["turnstile_exit"] = PROPS["turnstile"]
PROPS["turnstile_heet"] = PROPS["turnstile"]


def verify():
    for block, props in PROPS.items():
        path = os.path.join(ASSETS, "blockstates", block + ".json")
        data = json.load(open(path))
        for part in data["multipart"]:
            when = part.get("when", {})
            conds = when.get("OR", [when])
            for cond in conds:
                for key, value in cond.items():
                    assert key in props, f"{block}: unknown property {key}"
                    for v in str(value).split("|"):
                        assert v in props[key], f"{block}: bad value {key}={v}"
            mdl = part["apply"]["model"].split("/")[-1]
            mpath = os.path.join(ASSETS, "models/block", mdl + ".json")
            assert os.path.exists(mpath), f"{block}: missing model {mdl}"
            mdata = json.load(open(mpath))
            for el in mdata["elements"]:
                for fname, face in el["faces"].items():
                    assert "uv" in face, f"{mdl}: face {fname} missing explicit uv"
                    u0, v0, u1, v1 = face["uv"]
                    assert 0 <= min(u0, u1) and max(u0, u1) <= 16 and \
                        0 <= min(v0, v1) and max(v0, v1) <= 16, f"{mdl}: uv off sprite"
    print("verify: blockstates + models OK")


# --------------------------------------------------------------------- main --
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", metavar="DIR", help="also write upscaled texture sheets")
    args = parser.parse_args()

    texdir = os.path.join(ASSETS, "textures/block")
    textures = {
        "turnstile_steel": tex_steel(),
        "turnstile_face": tex_face(False),
        "turnstile_face_exit": tex_face(True),
        "turnstile_arm": tex_arm(),
        "turnstile_tube": tex_tube(),
        "turnstile_lamp": tex_lamp(),
        "turnstile_sign": tex_sign(False),
        "turnstile_sign_exit": tex_sign(True),
    }
    for name, rows in textures.items():
        pngtool.write_png(os.path.join(texdir, name + ".png"), rows)
    for name in ("turnstile_exit", "turnstile_heet"):
        pngtool.write_png(os.path.join(ASSETS, "textures/item", name + ".png"),
                          icon(name.split("_", 1)[1]))
        wj(os.path.join(ASSETS, "models/item", name + ".json"),
           {"parent": "minecraft:item/generated",
            "textures": {"layer0": f"{MOD}:item/{name}"}})

    write_cabinet()
    write_arm()
    write_upper_and_lamps()
    write_tubes()
    write_caps()
    write_heet()

    wj(os.path.join(ASSETS, "blockstates/turnstile.json"), turnstile_blockstate("turnstile_upper"))
    wj(os.path.join(ASSETS, "blockstates/turnstile_exit.json"),
       turnstile_blockstate("turnstile_upper_exit", "turnstile_tube_bridge_exit"))
    wj(os.path.join(ASSETS, "blockstates/turnstile_cap.json"), cap_blockstate())
    wj(os.path.join(ASSETS, "blockstates/turnstile_heet.json"), heet_blockstate())

    for block in ("turnstile_exit", "turnstile_heet"):
        wj(os.path.join(DATA, MOD, "loot_tables/blocks", block + ".json"), loot(block))
    for name, recipe in recipes().items():
        wj(os.path.join(DATA, MOD, "recipes", name + ".json"), recipe)

    # the exit upper model must dress with the exit face texture
    path = os.path.join(ASSETS, "models/block/turnstile_upper_exit.json")
    data = json.load(open(path))
    data["textures"] = dict(TEXTURES, face=f"{MOD}:block/turnstile_face_exit")
    wj(path, data)

    verify()

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        for name, rows in textures.items():
            pngtool.write_png(os.path.join(args.preview, name + ".png"),
                              pngtool.scale_nn(rows, 12, 12))
        print(f"previews in {args.preview}")


if __name__ == "__main__":
    main()
