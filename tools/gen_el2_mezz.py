#!/usr/bin/env python3
"""El kit v2 - MEZZANINE / STATION HOUSE (2026-09-25). Called from
gen_el2_assets.main(); never hand-edit the output.

A NYC el mezzanine (the control house under the tracks) is a steel-framed
room hung between the street and the track deck. With the platform and stair
families it was the missing piece between the street and the trains:

  el_mezzanine_floor   walkable slab for any elevated floor (mezzanine,
                       stair landing, footbridge): 4 px concrete deck on two
                       steel joists (AXIS = joist direction), corrugated pan
                       underneath - the underside is what the street sees.
                       Every side with no floor beside it (EDGE_*) hangs a
                       green channel fascia just OUTSIDE the cell, so it is
                       flush with an el wall course standing in the next cell
                       (the wall panel sits on that cell's near edge).
  el_ceiling           the room's ceiling, placed in the cell above the top
                       wall course: a 2 px pressed-metal panel at the bottom
                       of the cell, and on open sides the fascia continues
                       the wall plane up with a small cornice. Full-cube
                       collision (so StairFamily.underCeiling sees it and
                       stair walls under it become triangle walls).
  el_ceiling_light     the same ceiling with a flush troffer under the panel
                       (luminance 15). Joins plain ceiling cells.

Wall plane of a finished station house, bottom to top, all at the floor
edge line: floor fascia -> wall courses (cream / window / cream ...) ->
ceiling fascia + cornice. The window course is el_wall_window (walls
family, gen_el2_assets.walls_assets).

Fascia models are authored for the NORTH edge (outside the cell at z -0.8..0)
and turned by the blockstate (y=90 -> east, 180 -> south, 270 -> west);
corner caps fill the 0.8 px square where two fascias meet at a convex corner.
"""

import math
import os
import random

import gen_el2_assets as K
from gen_el2_stairs import wbox, icon_canvas, irect, iline, GREEN_I, GREEN_D, PLATE_I, CLEAR

MOD = K.MOD
G = "#green"

CREAM_I = (214, 206, 186, 255)
CREAM_D = (176, 166, 144, 255)
CONC_I = (150, 150, 146, 255)
CONC_D = (118, 118, 114, 255)
LIGHT_I = (240, 244, 236, 255)

FLOOR_TOP = 12.0      # slab 12..16
JOIST = (8.0, 12.0)   # joist y range
FASCIA_LO = 8.0       # floor fascia bottom
EDGES = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
CORNERS = (("north", "east", 0), ("east", "south", 90), ("south", "west", 180), ("west", "north", 270))


# ---------------------------------------------------------------------------
# textures
# ---------------------------------------------------------------------------

def tex_corrugated():
    """Corrugated steel pan (floor underside): green-grey ribs every 4 texels."""
    rng = random.Random("corr")
    base = (92, 110, 100)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            d = (16, 6, -10, -18)[x % 4] + rng.uniform(-3, 3)
            K.put(rows, x, y, K.shade(base, d))
    return rows


def tex_ceiling():
    """Pressed-metal ceiling, cream: 8-texel square coffers with a lit upper
    edge and a shadowed lower edge, so a room of cells reads as one tin ceiling."""
    rng = random.Random("ceiling")
    base = (222, 216, 198)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            K.put(rows, x, y, K.shade(base, rng.uniform(-3, 3)))
    for i in range(0, 32, 8):
        for t in range(32):
            K.put(rows, i, t, K.shade(base, -26))
            K.put(rows, t, i, K.shade(base, -26))
            K.put(rows, i + 1, t, K.shade(base, 10))
            K.put(rows, t, i + 1, K.shade(base, 10))
        for t in range(32):
            if t % 8 in (3, 4):
                pass
    for cy in range(0, 32, 8):
        for cx in range(0, 32, 8):
            for d in (3, 4):
                K.put(rows, cx + d, cy + d, K.shade(base, -12))
    return rows


def tex_troffer():
    """Flush fluorescent troffer: bright diffuser, a louvre grid, thin frame."""
    rows = K.canvas(16, LIGHT_I)
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            louvre = x % 5 == 2 or y % 8 == 7
            c = (170, 172, 168, 255) if edge else ((214, 220, 214, 255) if louvre else LIGHT_I)
            rows[y][x] = c
    return rows


def tex_window_glass():
    """Old sash-window glass, cutout: clear with two faint glare streaks and a
    grimy bottom corner - reads as glass without a translucent layer."""
    rows = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            s = (x + y) % 32
            if s in (6, 7) or s == 11:
                rows[y][x] = (206, 222, 228, 255)
    for y in range(26, 32):
        for x in range(0, 32 - (y - 26) * 3):
            if (x * 7 + y * 3) % 5 == 0:
                rows[y][x] = (120, 124, 118, 255)
    return rows


# ---------------------------------------------------------------------------
# models
# ---------------------------------------------------------------------------

def floor_core():
    """Slab + two joists along x (AXIS = x)."""
    els = [K.box(0, FLOOR_TOP, 0, 16, 16, 16, {"up": "#top", "down": "#pan", "*": "#concrete"},
                 uv={"up": [0, 0, 16, 16], "down": [0, 0, 16, 16],
                     "north": [0, 0, 16, 4], "south": [0, 0, 16, 4], "east": [0, 0, 16, 4], "west": [0, 0, 16, 4]},
                 cull=["north", "south", "east", "west", "up"])]
    for z0 in (3.0, 11.0):
        # I-joist: web + bottom flange, ends buried in the neighbour (no end faces)
        els.append(K.box(0, JOIST[0] + 1.2, z0 + 0.6, 16, JOIST[1], z0 + 1.4, G,
                         uv={"north": [0, 4, 16, 6.8], "south": [0, 4, 16, 6.8]}, faces=["north", "south"]))
        els.append(K.box(0, JOIST[0], z0, 16, JOIST[0] + 1.2, z0 + 2.0, G,
                         uv={"north": [0, 4, 16, 5.2], "south": [0, 4, 16, 5.2], "up": [0, 0, 16, 2],
                             "down": [0, 0, 16, 2]}, faces=["north", "south", "up", "down"]))
    return els


def floor_fascia():
    """North-edge channel: face plate just outside the cell + bottom flange lip."""
    return [K.box(0, FASCIA_LO, -0.8, 16, 15.9, 0, G,
                  uv={"north": [0, 0.1, 16, 8], "up": [0, 0, 16, 0.8], "down": [0, 0, 16, 0.8]},
                  faces=["north", "up", "down"]),
            K.box(0, FASCIA_LO, 0, 16, FASCIA_LO + 1.2, 2.4, G,
                  uv={"south": [0, 4, 16, 5.2], "down": [0, 0, 16, 2.4], "up": [0, 0, 16, 2.4]},
                  faces=["south", "down", "up"])]


def floor_corner():
    """Fills the 0.8 px square between the north and east fascias (convex corner)."""
    return [K.box(16, FASCIA_LO, -0.8, 16.8, 15.9, 0, G,
                  uv={"north": [0, 0.1, 0.8, 8], "east": [0, 0.1, 0.8, 8], "up": [0, 0, 0.8, 0.8],
                      "down": [0, 0, 0.8, 0.8]},
                  faces=["north", "east", "up", "down"])]


def ceiling_core():
    return [K.box(0, 0, 0, 16, 2, 16, {"down": "#panel", "*": "#cream"},
                  uv={"down": [0, 0, 16, 16], "up": [0, 0, 16, 16]}, faces=["down", "up"])]


def ceiling_fascia():
    """North edge: plate continuing the wall plane up to the cell top + a
    projecting cornice (y 13.5..16) - the station house's crown."""
    return [K.box(0, 0, -0.8, 16, 13.5, 0, G,
                  uv={"north": [0, 2.5, 16, 16], "down": [0, 0, 16, 0.8]},
                  faces=["north", "down"]),
            K.box(0, 13.5, -2.4, 16, 16, 0, G,
                  uv={"north": [0, 0, 16, 2.5], "down": [0, 0, 16, 2.4], "up": [0, 0, 16, 2.4]},
                  faces=["north", "down", "up"]),
            # cream band just under the cornice, on the plate (a painted frieze)
            K.box(0, 10.5, -0.9, 16, 12.5, -0.8, "#cream",
                  uv={"north": [0, 3.5, 16, 5.5]}, faces=["north"])]


def ceiling_corner():
    return [K.box(16, 0, -0.8, 16.8, 13.5, 0, G,
                  uv={"north": [0, 2.5, 0.8, 16], "east": [0, 2.5, 0.8, 16], "down": [0, 0, 0.8, 0.8]},
                  faces=["north", "east", "down"]),
            K.box(16, 13.5, -2.4, 18.4, 16, 0, G,
                  uv={"north": [0, 0, 2.4, 2.5], "east": [0, 0, 2.4, 2.5], "down": [0, 0, 2.4, 2.4],
                      "up": [0, 0, 2.4, 2.4]},
                  faces=["north", "east", "down", "up"]),
            K.box(16, 10.5, -0.9, 16.9, 12.5, 0, "#cream",
                  uv={"north": [0, 3.5, 0.9, 5.5], "east": [0, 3.5, 0.9, 5.5]}, faces=["north", "east"])]


def troffer():
    return [K.box(4, -0.5, 2, 12, 0, 14, "#light",
                  uv={"down": [0, 0, 16, 16], "north": [0, 0, 8, 1], "south": [0, 0, 8, 1],
                      "east": [0, 0, 12, 1], "west": [0, 0, 12, 1]},
                  faces=["down", "north", "south", "east", "west"], shade_=False)]


def edge_blockstate(name, core, core_rot_axis, fascia, corner, extra=None):
    parts = []
    if core_rot_axis:
        parts.append({"when": {"axis": "x"}, "apply": {"model": f"{MOD}:block/{core}"}})
        parts.append({"when": {"axis": "z"}, "apply": {"model": f"{MOD}:block/{core}", "y": 90}})
    else:
        parts.append({"apply": {"model": f"{MOD}:block/{core}"}})
    if extra:
        parts.append({"apply": {"model": f"{MOD}:block/{extra}"}})
    for side, rot in EDGES:
        a = {"model": f"{MOD}:block/{fascia}"}
        if rot:
            a["y"] = rot
        parts.append({"when": {f"edge_{side}": "true"}, "apply": a})
    for s1, s2, rot in CORNERS:
        a = {"model": f"{MOD}:block/{corner}"}
        if rot:
            a["y"] = rot
        parts.append({"when": {f"edge_{s1}": "true", f"edge_{s2}": "true"}, "apply": a})
    K.write_json(os.path.join(K.BLOCKSTATES, name + ".json"), {"multipart": parts})


# ---------------------------------------------------------------------------
# icons (flat 32 px sprites, like the rest of the v2 kit)
# ---------------------------------------------------------------------------

def icon_floor():
    r = icon_canvas()
    irect(r, 2, 10, 30, 15, CONC_I)
    irect(r, 2, 10, 30, 11, (176, 176, 170, 255))
    irect(r, 1, 15, 31, 17, GREEN_D)
    for x0 in (6, 22):
        irect(r, x0, 15, x0 + 4, 22, GREEN_I)
        irect(r, x0 - 1, 21, x0 + 5, 23, GREEN_D)
    irect(r, 1, 10, 3, 18, GREEN_I)
    irect(r, 29, 10, 31, 18, GREEN_I)
    return r


def icon_ceiling(lit):
    r = icon_canvas()
    irect(r, 2, 8, 30, 12, GREEN_I)
    irect(r, 1, 6, 31, 8, GREEN_D)
    irect(r, 3, 12, 29, 15, CREAM_I)
    for x in range(3, 29, 6):
        irect(r, x, 12, x + 1, 15, CREAM_D)
    if lit:
        irect(r, 9, 15, 23, 17, LIGHT_I)
        for y in range(18, 26, 2):
            for x in range(8, 25, 4):
                r[y][x + (y // 2) % 2] = (250, 244, 190, 255)
    return r


def icon_window():
    r = icon_canvas()
    irect(r, 3, 3, 29, 29, GREEN_I)
    for (x0, y0) in ((5, 5), (17, 5), (5, 17), (17, 17)):
        irect(r, x0, y0, x0 + 10, y0 + 10, (170, 196, 206, 255))
        iline(r, x0 + 2, y0 + 7, x0 + 7, y0 + 2, (226, 238, 242, 255))
    irect(r, 3, 27, 29, 29, GREEN_D)
    return r


def build():
    K.write_png("el2_corrugated", tex_corrugated())
    K.write_png("el2_ceiling", tex_ceiling())
    K.write_png("el2_troffer", tex_troffer())
    tex = {"green": "el2_green", "concrete": "platform_concrete_floor_side",
           "top": "platform_concrete_floor_top_c0_0", "pan": "el2_corrugated",
           "cream": "el2_cream", "panel": "el2_ceiling", "light": "el2_troffer"}
    K.model("el_mezzanine_floor", floor_core(), tex)
    K.model("el_mezzanine_floor_fascia", floor_fascia(), tex)
    K.model("el_mezzanine_floor_corner", floor_corner(), tex)
    K.model("el_ceiling", ceiling_core(), tex)
    K.model("el_ceiling_fascia", ceiling_fascia(), tex)
    K.model("el_ceiling_corner", ceiling_corner(), tex)
    K.model("el_ceiling_troffer", troffer(), tex)
    edge_blockstate("el_mezzanine_floor", "el_mezzanine_floor", True,
                    "el_mezzanine_floor_fascia", "el_mezzanine_floor_corner")
    edge_blockstate("el_ceiling", "el_ceiling", False, "el_ceiling_fascia", "el_ceiling_corner")
    edge_blockstate("el_ceiling_light", "el_ceiling", False, "el_ceiling_fascia", "el_ceiling_corner",
                    extra="el_ceiling_troffer")
    from gen_el2_structure import loot, icon
    for name in ("el_mezzanine_floor", "el_ceiling", "el_ceiling_light"):
        loot(name)
    icon("el_mezzanine_floor", icon_floor())
    icon("el_ceiling", icon_ceiling(False))
    icon("el_ceiling_light", icon_ceiling(True))
    K.write_json(os.path.join(K.DATA, "recipes/el_mezzanine_floor.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"C": {"item": "minecraft:smooth_stone_slab"}, "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["CCC", "I I"], "result": {"item": f"{MOD}:el_mezzanine_floor", "count": 6}})
    K.write_json(os.path.join(K.DATA, "recipes/el_ceiling.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "W": {"item": "minecraft:white_dye"}},
        "pattern": ["III", " W "], "result": {"item": f"{MOD}:el_ceiling", "count": 6}})
    K.write_json(os.path.join(K.DATA, "recipes/el_ceiling_light.json"), {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:el_ceiling"}, {"item": "minecraft:glowstone_dust"}],
        "result": {"item": f"{MOD}:el_ceiling_light", "count": 1}})


EDGE_PROPS = {"edge_north": {"true", "false"}, "edge_east": {"true", "false"},
              "edge_south": {"true", "false"}, "edge_west": {"true", "false"}}
VERIFY = [("el_mezzanine_floor", dict(EDGE_PROPS, axis={"x", "z"})),
          ("el_ceiling", EDGE_PROPS), ("el_ceiling_light", EDGE_PROPS)]
