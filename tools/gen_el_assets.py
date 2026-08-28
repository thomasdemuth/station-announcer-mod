#!/usr/bin/env python3
"""Regenerates every NYC elevated-structure asset (EL_STATION_PLAN.md):
textures, models, blockstates, item models, loot and recipes for the el
family — slim solid/lattice columns, plate girder, lattice truss, tie and
plate decks, in green / galvanized / station-tinted paints.

Run from anywhere:  python3 tools/gen_el_assets.py [--out DIR]
--out writes the whole assets tree somewhere else (the preview flow renders
from a scratchpad copy before anything lands in the mod).

Design rules honoured (learned elsewhere in this repo the hard way):
- Interior uv slices only; textures vary per-column only where faces tile
  vertically across stacked blocks (globe-pole lesson).
- No two elements share an exact plane unless a face is omitted/culled;
  run-continuation end faces are omitted, panel stiffeners inset 0.05.
- Element rotations are single-axis, angle in {±45, ±22.5}.
- verify() checks every blockstate when-key against the Java properties
  (purple-box lesson) and every uv against the sprite.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")

def wpng(path, rows):
    """pngtool.write_png emits exactly px[:4] bytes per pixel against an RGBA
    header — an RGB 3-tuple silently corrupts the file (every el texture
    shipped purple once). Normalize every pixel to RGBA here, always."""
    pngtool.write_png(path, [[tuple(px) + (255,) * (4 - len(px)) for px in row]
                             for row in rows])

MOD = "station_announcer"

# ---------------------------------------------------------------- palettes --
# tone keys: base / lit / dark / shadow / rivet(highlight)
PAINTS = {
    "green": {  # Dual-Contracts dark green (Jamaica Ave / Bay Pkwy)
        "base": (33, 82, 56), "lit": (45, 100, 70), "dark": (24, 62, 42),
        "shadow": (16, 46, 30), "rivet": (60, 118, 86), "tint": False,
    },
    "silver": {  # galvanized rebuild steel
        "base": (172, 175, 178), "lit": (198, 201, 204), "dark": (146, 149, 152),
        "shadow": (116, 119, 122), "rivet": (216, 219, 222), "tint": False,
    },
    "station": {  # light grayscale, tinted by the station color provider
        "base": (200, 200, 200), "lit": (224, 224, 224), "dark": (170, 170, 170),
        "shadow": (138, 138, 138), "rivet": (240, 240, 240), "tint": True,
    },
}

WOOD = (46, 38, 31)
WOOD_LIT = (58, 48, 39)
WOOD_DARK = (34, 28, 22)
PLATE = (66, 68, 71)
PLATE_LIT = (84, 86, 90)
PLATE_DARK = (50, 52, 55)
PLATE_SHADOW = (38, 40, 42)


# ---------------------------------------------------------------- textures --
def tex_steel(p):
    """Painted riveted steel, 32px. Layout (texels):
    - whole sheet: per-column streaks (vertical-only, stack-safe)
    - rows 6..8 and 24..26: horizontal rivet lines, dot pitch 4 (girder webs
      sample full height and get their two rivet seams; column cores sample
      rows 9..23 and stay clean)
    - cols 27..29: a vertical rivet ladder, dot pitch 4 (the corner angles'
      outer faces sample this as their rivet row)"""
    rows = pk.canvas(32, 32, p["base"])
    tones = [p["base"], p["lit"], p["base"], p["dark"], p["base"], p["rivet"],
             p["base"], p["lit"], p["dark"], p["base"], p["base"], p["dark"]]
    for x in range(32):
        t = tones[(x * 7) % len(tones)]
        if x % 13 == 9:
            t = p["shadow"]
        elif (x * 5) % 17 == 3:
            t = p["dark"]
        for y in range(32):
            rows[y][x] = t
    for line_y in (6, 24):                     # horizontal rivet seams
        for x in range(0, 32, 4):
            pk.rect(rows, x + 1, line_y, x + 3, line_y + 2, p["rivet"])
            pk.rect(rows, x + 2, line_y + 1, x + 3, line_y + 2, p["shadow"])
    for y in range(0, 32, 4):                  # vertical rivet ladder
        pk.rect(rows, 27, y + 1, 29, y + 3, p["rivet"])
        pk.rect(rows, 28, y + 2, 29, y + 3, p["shadow"])
    return rows


def tex_lattice(p):
    """X-laced lattice web, 16px CUTOUT: 2px straps on both 45° diagonals,
    period 8 (divides 16 — tiles seamlessly along runs and up stacks)."""
    rows = pk.canvas(16, 16, (0, 0, 0, 0))
    for off in range(-16, 32, 8):
        for i in range(16):
            for w in range(2):
                for xx, yy in (((off + i + w) % 16, i), ((off - i + w) % 16, i)):
                    if 0 <= xx < 16:
                        tone = p["base"] if (i + w) % 4 else p["dark"]
                        rows[yy][xx] = tone
    return rows


def tex_deck_wood():
    """Creosote tie timber: near-black brown, per-column grain."""
    rows = pk.canvas(16, 16, WOOD)
    tones = [WOOD, WOOD_LIT, WOOD, WOOD_DARK, WOOD, (28, 23, 18), WOOD_DARK,
             WOOD, WOOD_LIT, WOOD_DARK]
    for x in range(16):
        t = tones[(x * 5) % len(tones)]
        for y in range(16):
            rows[y][x] = t
    return rows


def tex_deck_steel():
    """Dark plate-deck steel with a sparse rivet grid."""
    rows = pk.canvas(16, 16, PLATE)
    tones = [PLATE, PLATE_LIT, PLATE, PLATE_DARK, PLATE, PLATE]
    for x in range(16):
        t = tones[(x * 7) % len(tones)]
        for y in range(16):
            rows[y][x] = t
    for y in (2, 10):
        for x in (2, 10):
            pk.rect(rows, x, y, x + 2, y + 2, PLATE_LIT)
            rows[y + 1][x + 1] = PLATE_SHADOW
    return rows


# ------------------------------------------------------------------ models --
def f(tex, uv, tinted=False, cull=None, rot=None):
    face = {"texture": "#" + tex, "uv": list(uv)}
    if tinted:
        face["tintindex"] = 0
    if cull:
        face["cullface"] = cull
    if rot:
        face["rotation"] = rot
    return face


def elem(frm, to, faces, rotation=None, shade=None):
    e = {"from": list(frm), "to": list(to), "faces": faces}
    if rotation:
        e["rotation"] = rotation
    if shade is not None:
        e["shade"] = shade
    return e


class Gen:
    def __init__(self, assets_root, data_root):
        self.assets = assets_root
        self.data = data_root

    def wj(self, path, obj):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as fh:
            json.dump(obj, fh, indent=2)
            fh.write("\n")

    def model(self, name, textures, elements):
        self.wj(os.path.join(self.assets, "models/block", name + ".json"),
                {"parent": "minecraft:block/block", "textures": textures, "elements": elements})


# ----- shared geometry helpers (all authored for a NORTH/axis-x frame) -----
# steel windows (uv units on the 32px sheet; texel = 2 uv... uv 0..16 spans 32 texels)
def clean(w, h, tinted):
    """Plain steel slice from the clean band (texel rows 10..22)."""
    w = min(w, 12.5)
    h = min(h, 5.5)
    return lambda: f("body", [0.75, 5, 0.75 + w, 5 + h], tinted)


def steel_tex(paint):
    t = {"body": f"{MOD}:block/el_steel_{paint}",
         "particle": f"{MOD}:block/el_steel_{paint}"}
    return t


ANGLES = ((4.7, 4.7), (9.7, 4.7), (4.7, 9.7), (9.7, 9.7))


def column_angles(tinted):
    """The four proud corner angles with the vertical rivet ladder on their
    two outward faces."""
    els = []
    ladder = f("body", [13.6, 0.25, 14.4, 15.75], tinted)
    plain = f("body", [1, 4.75, 1.8, 12.75], tinted)
    for x0, z0 in ANGLES:
        outward_x = "west" if x0 < 8 else "east"
        outward_z = "north" if z0 < 8 else "south"
        faces = {}
        for name in ("north", "south", "east", "west"):
            faces[name] = ladder if name in (outward_x, outward_z) else plain
        els.append(elem([x0, 0, z0], [x0 + 1.6, 16, z0 + 1.6], faces))
    return els


def column_core(tinted):
    """Recessed web between the angles — samples the clean band only."""
    face = f("body", [0.25, 4.5, 3.05, 12.5], tinted)
    return [elem([5.2, 0, 5.2], [10.8, 16, 10.8],
                 {n: face for n in ("north", "south", "east", "west")})]


def column_lattice(tinted):
    """Four see-through lacing planes between the angles."""
    els = []
    for horiz in (True, False):
        for near in (True, False):
            face = f("lattice", [5.2, 0, 10.8, 16], tinted)
            if horiz:  # planes facing north/south
                z = 4.9 if near else 10.6
                els.append(elem([5.2, 0, z], [10.8, 16, z + 0.5],
                                {"north": face, "south": face}))
            else:
                x = 4.9 if near else 10.6
                els.append(elem([x, 0, 5.2], [x + 0.5, 16, 10.8],
                                {"east": face, "west": face}))
    return els


def column_foot(tinted):
    return [
        elem([3.6, 0, 3.6], [12.4, 1.3, 12.4], {
            "north": f("body", [1, 5, 5.4, 5.65], tinted),
            "south": f("body", [1, 5, 5.4, 5.65], tinted),
            "east": f("body", [1, 5, 5.4, 5.65], tinted),
            "west": f("body", [1, 5, 5.4, 5.65], tinted),
            "up": f("body", [1, 5, 5.4, 9.4], tinted),
            "down": f("body", [1, 5, 5.4, 9.4], tinted, cull="down"),
        }),
        elem([4.4, 1.3, 4.4], [11.6, 2.5, 11.6], {
            "north": f("body", [1, 6, 4.6, 6.6], tinted),
            "south": f("body", [1, 6, 4.6, 6.6], tinted),
            "east": f("body", [1, 6, 4.6, 6.6], tinted),
            "west": f("body", [1, 6, 4.6, 6.6], tinted),
            "up": f("body", [1, 5.5, 4.6, 9.1], tinted),
        }),
    ]


def column_cap(tinted):
    return [
        elem([4.4, 13.5, 4.4], [11.6, 14.7, 11.6], {
            "north": f("body", [1, 6, 4.6, 6.6], tinted),
            "south": f("body", [1, 6, 4.6, 6.6], tinted),
            "east": f("body", [1, 6, 4.6, 6.6], tinted),
            "west": f("body", [1, 6, 4.6, 6.6], tinted),
            "down": f("body", [1, 5.5, 4.6, 9.1], tinted),
        }),
        elem([3.6, 14.7, 3.6], [12.4, 16, 12.4], {
            "north": f("body", [1, 5, 5.4, 5.65], tinted),
            "south": f("body", [1, 5, 5.4, 5.65], tinted),
            "east": f("body", [1, 5, 5.4, 5.65], tinted),
            "west": f("body", [1, 5, 5.4, 5.65], tinted),
            "up": f("body", [1, 5, 5.4, 9.4], tinted, cull="up"),
            "down": f("body", [1, 5, 5.4, 9.4], tinted),
        }),
    ]


def girder_elements(tinted):
    """Riveted plate girder, axis x: full-height web with its two rivet seams
    (the sheet's full-height window), flanges, panel stiffeners inset 0.05."""
    web = f("body", [0.25, 0.25, 15.75, 15.75], tinted)
    els = [elem([0, 1.2, 6.7], [16, 14.8, 9.3], {"north": web, "south": web})]
    for y0, y1 in ((0, 1.2), (14.8, 16)):
        cull = "down" if y0 == 0 else "up"
        els.append(elem([0, y0, 5.5], [16, y1, 10.5], {
            "north": f("body", [1, 5, 9, 5.6], tinted),
            "south": f("body", [1, 5, 9, 5.6], tinted),
            "up": f("body", [1, 5, 9, 7.5], tinted, cull=cull if cull == "up" else None),
            "down": f("body", [1, 5, 9, 7.5], tinted, cull=cull if cull == "down" else None),
        }))
    for x0 in (0.05, 14.9):
        els.append(elem([x0, 1.2, 6.3], [x0 + 1.05, 14.8, 9.7], {
            "north": f("body", [13.6, 1, 14.4, 7.8], tinted),
            "south": f("body", [13.6, 1, 14.4, 7.8], tinted),
            "east": f("body", [1, 5, 2.7, 11.8], tinted),
            "west": f("body", [1, 5, 2.7, 11.8], tinted),
        }))
    return els


def truss_elements(tinted):
    """Open lattice truss, axis x: chords + see-through diamond web."""
    els = []
    for y0, y1 in ((0.5, 3.1), (12.9, 15.5)):
        els.append(elem([0, y0, 6.3], [16, y1, 9.7], {
            "north": f("body", [1, 5, 9, 6.3], tinted),
            "south": f("body", [1, 5, 9, 6.3], tinted),
            "up": f("body", [1, 5, 9, 6.7], tinted),
            "down": f("body", [1, 5, 9, 6.7], tinted),
        }))
    lattice = f("lattice", [0, 3, 16, 13], tinted)
    els.append(elem([0, 2.9, 7.75], [16, 13.1, 8.25], {"north": lattice, "south": lattice}))
    for x0 in (0.05, 14.65):
        els.append(elem([x0, 0.5, 6.5], [x0 + 1.3, 15.5, 9.5], {
            "north": f("body", [13.6, 1, 14.4, 8.5], tinted),
            "south": f("body", [13.6, 1, 14.4, 8.5], tinted),
            "east": f("body", [1, 5, 2.5, 12.5], tinted),
            "west": f("body", [1, 5, 2.5, 12.5], tinted),
        }))
    return els


def brace_elements(tinted):
    """The knee braces: a pair of big 45° gusset struts from the girder's
    underside down the column's sides (elements below y0 are legal), each
    doubled with a shorter inner strut so the pair reads as the photos'
    curved gusset rather than a stick."""
    els = []
    for outer, angle in ((True, 45), (False, -45)):
        # three stepped struts per side — the stepped pair reads as the
        # photos' big CURVED gusset, not a stick (Thomas: beefier)
        # steps overlap 0.2 so no two strut faces share a rotated plane
        for length, x_off, width in ((11.0, 0.0, 3.0), (8.0, 2.8, 3.0), (5.0, 5.6, 3.0)):
            x0 = (0.4 + x_off) if outer else (16 - 0.4 - x_off - width)
            ox = x0 + width / 2
            strut = f("body", [1, 4.25, 4, 12.25], tinted)
            els.append(elem([x0, 1.6 - length, 6.7], [x0 + width, 1.6, 9.3], {
                "north": strut, "south": strut,
                "east": f("body", [1, 4.25, 4.2, 12.25], tinted),
                "west": f("body", [1, 4.25, 4.2, 12.25], tinted),
            }, rotation={"origin": [ox, 1.6, 8], "axis": "z", "angle": angle}))
    return els


def deck_ties_elements():
    els = []
    tie_face = f("wood", [0.5, 1, 15.5, 4.2])
    tie_side = f("wood", [1, 5, 4.2, 8.2])
    for x0 in (0.6, 4.6, 8.6, 12.6):
        els.append(elem([x0, 12.8, 0.5], [x0 + 2.4, 16, 15.5], {
            "up": f("wood", [0.5, 1, 15.5, 3.4]), "down": f("wood", [0.5, 4, 15.5, 6.4]),
            "east": tie_face, "west": tie_face,
            "north": tie_side, "south": tie_side,
        }))
    # deep longitudinal stringer girders reaching the bottom of the block,
    # so the deck SITS on the cross girder below instead of floating
    for z0 in (3.4, 10.6):
        stringer = f("body", [0.25, 0.25, 15.75, 12.5])
        els.append(elem([0, 0, z0], [16, 12.8, z0 + 2.0], {
            "north": stringer, "south": stringer,
            "down": f("body", [0.25, 5, 15.75, 7], cull="down"),
        }))
    return els


def deck_plate_elements():
    top = f("plate", [0.25, 0.25, 15.75, 15.75])
    edge = f("plate", [0.25, 6, 15.75, 7.75])
    els = [elem([0, 12.4, 0], [16, 16, 16], {
        "up": top, "down": f("plate", [0.25, 0.25, 15.75, 15.75]),
        "north": dict(edge, cullface="north"), "south": dict(edge, cullface="south"),
        "east": dict(edge, cullface="east"), "west": dict(edge, cullface="west"),
    })]
    for z0 in (3.4, 10.6):
        stringer = f("body", [0.25, 0.25, 15.75, 12.2])
        els.append(elem([0, 0, z0], [16, 12.4, z0 + 2.0],
                        {"north": stringer, "south": stringer,
                         "down": f("body", [0.25, 5, 15.75, 7], cull="down")}))
    return els


# ---------------------------------------------------- platform (phase 2) ----
# Preview-only for now (--platform-preview): models + textures so Thomas can
# judge the passenger-level pieces before they become real blocks.

CREAM = (216, 206, 186)
CREAM_DARK = (196, 186, 166)
GLAZE = (168, 186, 192)
GLAZE_LIT = (196, 212, 216)
GLAZE_DARK = (122, 140, 148)
ROOF_RED = (128, 42, 38)
ROOF_RED_LIT = (150, 56, 50)
ROOF_RED_DARK = (104, 32, 30)


def tex_windscreen():
    """Classic windscreen sheet, 32px: rows 0..14 beige panel with batten
    shadows, rows 14..24 wired-glass glazing with mullions, rows 24..32
    green frame rails."""
    g = PAINTS["green"]
    rows = pk.canvas(32, 32, CREAM)
    grain = [CREAM, (222, 212, 192), CREAM, (206, 196, 176), CREAM, CREAM_DARK,
             CREAM, (210, 200, 180)]
    for x in range(32):
        t = grain[(x * 5) % len(grain)]
        for y in range(14):
            rows[y][x] = t
        if x % 8 == 0:
            pk.rect(rows, x, 0, x + 1, 14, (188, 178, 158))
        if x % 8 == 1:
            pk.rect(rows, x, 0, x + 1, 14, CREAM_DARK)
    pk.rect(rows, 0, 12, 32, 14, CREAM_DARK)
    pk.rect(rows, 0, 14, 32, 24, GLAZE)
    for pane in range(0, 32, 8):
        for i in range(8):          # diagonal glare streak per pane
            y = 15 + (i * 8) // 8
            if y < 23:
                rows[y][pane + 7 - i] = GLAZE_LIT
        pk.rect(rows, pane + 2, 20, pane + 7, 23, GLAZE_DARK)
    for x in range(0, 32, 8):
        pk.rect(rows, x, 14, x + 1, 24, g["dark"])
    pk.rect(rows, 0, 14, 32, 15, GLAZE_DARK)
    pk.rect(rows, 0, 24, 32, 32, g["base"])
    pk.rect(rows, 0, 24, 32, 25, g["lit"])
    pk.rect(rows, 0, 31, 32, 32, g["shadow"])
    return rows


def tex_corrugated(g):
    """Corrugated sheet, period 4 with a full shading cycle per flute and an
    occasional weather-streak flute (vertical-only: tiles along runs)."""
    rows = pk.canvas(32, 32, g["base"])
    for x in range(32):
        m = x % 4
        tone = (g["lit"], g["base"], g["dark"], g["shadow"])[m]
        if x % 16 == 9:
            tone = g["shadow"]
        for y in range(32):
            rows[y][x] = tone
    return rows


def tex_glass():
    """Modern windscreen glass, 16px cutout: clear with a sheen streak."""
    rows = pk.canvas(16, 16, (0, 0, 0, 0))
    for i in range(16):
        x = (i + 4) % 16
        rows[i][x] = (222, 232, 236, 90)
        rows[i][(x + 1) % 16] = (206, 220, 226, 60)
    for i in (0, 15):
        pass  # edges stay open; the frame is geometry
    return rows


def tex_mesh():
    """Chain-link, 16px cutout: 45° diamonds, strap 1px, period 4."""
    rows = pk.canvas(16, 16, (0, 0, 0, 0))
    steel = (150, 152, 155, 255)
    dark = (118, 120, 122, 255)
    for off in range(-16, 32, 4):
        for i in range(16):
            for xx, tone in (((off + i) % 16, steel), ((off - i) % 16, dark)):
                if 0 <= xx < 16:
                    rows[i][xx] = tone
    return rows


def tex_roof_red():
    """Standing-seam roof, seam every 8 (vertical-only)."""
    rows = pk.canvas(32, 32, ROOF_RED)
    shades = [ROOF_RED_LIT, ROOF_RED_DARK, ROOF_RED, ROOF_RED,
              (120, 39, 36), ROOF_RED, (112, 35, 33), ROOF_RED_DARK]
    for x in range(32):
        tone = shades[x % 8]
        if x % 32 == 21:
            tone = (92, 30, 28)
        for y in range(32):
            rows[y][x] = tone
    return rows


PLATFORM_TEX = {
    "screen": f"{MOD}:block/el_windscreen",
    "corru": f"{MOD}:block/el_corrugated",
    "corru_s": f"{MOD}:block/el_corrugated_silver",
    "lattice": f"{MOD}:block/el_lattice_green",
    "glass": f"{MOD}:block/el_glass",
    "mesh": f"{MOD}:block/el_mesh",
    "roof": f"{MOD}:block/el_roof_red",
    "body": f"{MOD}:block/el_steel_green",
    "galv": f"{MOD}:block/el_steel_silver",
    "board": f"{MOD}:block/el_board",
    "particle": f"{MOD}:block/el_steel_green",
}


def windscreen_panel_elements():
    """Classic: beige panel below, wired-glass band above. FULL BLOCK HEIGHT
    now — screens stack into a tall wall (the old 14.6-px screen read as a
    fence); the separate rail/kick models cap the stack's top and foot."""
    return [
        elem([0, 0, 7.3], [16, 10.2, 8.7], {
            "north": f("screen", [0, 0.25, 16, 6.75]),
            "south": f("screen", [0, 0.25, 16, 6.75]),
        }),
        elem([0, 10.2, 7.5], [16, 16, 8.5], {
            "north": f("screen", [0, 7.1, 16, 11.9]),
            "south": f("screen", [0, 7.1, 16, 11.9]),
        }),
    ]


def windscreen_corrugated_elements():
    sheet = f("corru", [0, 0.25, 16, 13])
    return [elem([0, 0, 7.4], [16, 16, 8.6], {"north": sheet, "south": sheet})]


def windscreen_glass_elements():
    pane = f("glass", [0, 0, 16, 16])
    return [elem([0, 0, 7.7], [16, 16, 8.3], {"north": pane, "south": pane})]


def windscreen_mesh_elements():
    pane = f("mesh", [0, 0, 16, 16])
    return [elem([0, 0, 7.8], [16, 16, 8.2], {"north": pane, "south": pane})]


def screen_top_rail(ref):
    """Handrail capping a screen stack's top block (up=false)."""
    rail = f(ref, [0.25, 4.75, 15.75, 6] if ref == "body" else [1, 5, 9, 5.9])
    return [elem([0, 14.6, 7.0], [16, 16, 9.0],
                 {"north": rail, "south": rail, "up": rail, "down": rail})]


def screen_kick(ref):
    """Kick plate at a screen stack's foot (down=false)."""
    kick = f(ref, [0.25, 5, 15.75, 6.2] if ref == "body" else [1, 5, 9, 6])
    return [elem([0, 0, 7.05], [16, 1.2, 8.95], {
        "north": kick, "south": kick, "up": kick,
        "down": dict(kick, cullface="down"),
    })]


def railing_old_elements():
    """Two-rail pipe railing, green — the open-platform edge rail."""
    els = []
    for y0 in (13.2, 6.6):
        rail = f("body", [0.25, 4.75, 15.75, 6], False)
        els.append(elem([0, y0, 7.2], [16, y0 + 1.6, 8.8],
                        {"north": rail, "south": rail, "up": rail, "down": rail}))
    post = f("body", [13.6, 0.5, 14.4, 7.5])
    els.append(elem([7, 0, 7.1], [9, 13.2, 8.9],
                    {n: post for n in ("north", "south", "east", "west")}))
    return els


def railing_modern_elements():
    """Galvanized picket railing, pitch 2."""
    els = []
    top = f("galv", [0.25, 5, 15.75, 6.4])
    els.append(elem([0, 13.6, 7.0], [16, 15.0, 9.0],
                    {"north": top, "south": top, "up": top, "down": top}))
    bottom = f("galv", [0.25, 5, 15.75, 5.9])
    els.append(elem([0, 1.6, 7.3], [16, 2.5, 8.7],
                    {"north": bottom, "south": bottom, "up": bottom, "down": bottom}))
    pick = f("galv", [13.7, 1, 14.3, 6.7])
    for x in range(1, 16, 2):
        els.append(elem([x, 1.6, 7.6], [x + 0.9, 13.6, 8.4],
                        {n: pick for n in ("north", "south", "east", "west")}))
    return els


def canopy_post_shaft(ref="body"):
    """Slim square canopy post shaft (stacks; brackets cap the stack top)."""
    face = f(ref, [12.4, 0.25, 15.4, 15.75])
    return [elem([6.6, 0, 6.6], [9.4, 16, 9.4],
                 {n: face for n in ("north", "south", "east", "west")})]


def canopy_post_brackets(ref="body"):
    """The curved top brackets, drawn only on a stack's top block."""
    els = []
    for x0, angle in ((9.0, -45), (4.6, 45)):
        b = f(ref, [1, 4.75, 2.3, 9.5])
        els.append(elem([x0, 10.4, 6.9], [x0 + 2.4, 15.8, 9.1], {
            "north": b, "south": b,
            "east": f(ref, [1, 4.75, 2.4, 9.5]),
            "west": f(ref, [1, 4.75, 2.4, 9.5]),
        }, rotation={"origin": [x0 + 1.2, 15.8, 8], "axis": "z", "angle": angle}))
    return els


def canopy_flat_elements(sheet="corru", ref="body"):
    """Flat corrugated canopy slab with under-ribs (W 8th St style). Authored
    LOW in the block so a canopy placed directly above its posts touches
    them — the roof plane is the cell's floor, not its ceiling."""
    els = []
    els.append(elem([0, 1.4, 0], [16, 3.4, 16], {
        "up": f(sheet, [0, 0.25, 16, 15.75]),
        "down": f(sheet, [0, 0.25, 16, 15.75]),
        "north": f(ref, [1, 5, 9, 6], cull="north"),
        "south": f(ref, [1, 5, 9, 6], cull="south"),
        "east": f(ref, [1, 5, 9, 6], cull="east"),
        "west": f(ref, [1, 5, 9, 6], cull="west"),
    }))
    # ribs at the eaves plus a centre purlin: hanging blocks (the NYC PIDS'
    # 2 px ceiling stub at x/z 7..9) land exactly on the purlin's underside
    for z0 in (2.5, 7.2, 13.5):
        rib = f(ref, [0.25, 5, 15.75, 6.2])
        els.append(elem([0, 0, z0], [16, 1.4, z0 + 1.6],
                        {"north": rib, "south": rib, "down": rib}))
    return els


def canopy_fascia_elements(ref="body"):
    """Riveted edge girder hanging at the canopy's platform edge (separate
    model — the real block places it on unconnected edges automatically)."""
    face = f(ref, [0.25, 0.25, 15.75, 4.25])
    return [elem([0, 0, 0.4], [16, 5.6, 2.0],
                 {"north": face, "south": face,
                  "up": f(ref, [0.25, 5, 15.75, 5.8]),
                  "down": f(ref, [0.25, 5, 15.75, 5.8])})]


def canopy_gable_elements():
    """Peaked standing-seam roof, REBUILT at a real 45° pitch (the first cut
    rose 4.6 px and read as a flat slab with a kink): eave boards at y≈1,
    two 45° planes climbing to a ridge cap at y≈9.5. Still authored low so
    it lands on posts, still one continuous gable along a run."""
    els = []
    plane = f("roof", [0, 0.25, 16, 15.75])
    edge = f("roof", [0, 8.5, 16, 9.3])
    for z0, z1, angle, oz in ((0.7, 11.0, -45, 0.7), (5.0, 15.3, 45, 15.3)):
        els.append(elem([0, 1.0, z0], [16, 2.6, z1], {
            "up": plane, "down": plane,
            "north": edge, "south": edge,
        }, rotation={"origin": [8, 1.8, oz], "axis": "x", "angle": angle}))
    ridge = f("roof", [0, 8.5, 16, 9.5])
    els.append(elem([0, 8.2, 6.8], [16, 9.9, 9.2],
                    {"north": ridge, "south": ridge, "up": ridge, "down": ridge}))
    # eave boards closing the roof edges
    board = f("roof", [0, 8.6, 16, 9.4])
    for z0 in (0, 14.8):
        els.append(elem([0, 0.6, z0], [16, 2.2, z0 + 1.2],
                        {"north": board, "south": board, "down": board, "up": board}))
    # the truss tie chord under the ridge — real gables have one, and it is
    # the steel a hanging PIDS/sign stub lands on
    tie = f("body", [0.25, 5, 15.75, 6.2])
    els.append(elem([0, 0, 6.9], [16, 1.2, 9.1],
                    {"north": tie, "south": tie, "down": tie, "up": tie}))
    return els


def gable_end_elements():
    """Open truss triangle closing a gable run's end (authored WEST),
    following the rebuilt 45° pitch: bottom chord, two 45° rafter chords
    meeting under the ridge, tall lattice web."""
    els = []
    chord = f("body", [0.25, 5, 15.75, 6.2])
    els.append(elem([0.2, 0.2, 0.8], [1.4, 1.5, 15.2],
                    {n: chord for n in ("north", "south", "east", "west", "up", "down")}))
    for z0, z1, angle, oz in ((0.9, 10.9, -45, 0.9), (5.1, 15.1, 45, 15.1)):
        els.append(elem([0.2, 1.3, z0], [1.4, 2.6, z1],
                        {n: chord for n in ("north", "south", "east", "west", "up", "down")},
                        rotation={"origin": [0.8, 1.95, oz], "axis": "x", "angle": angle}))
    lat = f("lattice", [2, 2, 14, 10])
    els.append(elem([0.5, 1.4, 3.2], [1.1, 6.4, 12.8], {"east": lat, "west": lat}))
    return els


def tex_board():
    """Matte black name board with a worn edge line."""
    rows = pk.canvas(16, 16, (18, 19, 21))
    pk.rect(rows, 0, 0, 16, 1, (34, 35, 38))
    pk.rect(rows, 0, 15, 16, 16, (10, 10, 12))
    return rows


# The name board's PLATE PLANE is shared with the block-entity renderer
# (StationDecorRenderer.paintElNameBoard paints the letters onto it), so these
# three numbers are a CONTRACT — change them here and there together:
#
#   mount     plate box (model px)                 top   front  faces
#   standing  x 1..15  y 6..13  z 7.4..8.6          13     7.4   both
#   hanging   x 1..15  y 5..12  z 7.4..8.6          12     7.4   both
#   wall      x 1..15  y 5..12  z 13.8..15.0        12    13.8   front only
#
# Every mount keeps the plate 14 px wide and 7 px tall so the renderer's canvas
# (56 x 28 units at 64 units/block) and its text sizing never change; only the
# plate's top y and front z move.
NAME_BOARD_PLATE = {
    "standing": (6, 13, 7.4, 8.6),
    "hanging": (5, 12, 7.4, 8.6),
    "wall": (5, 12, 13.8, 15.0),
}


def name_board_elements(mount="standing"):
    """The black station-name board (text comes from the BE renderer in game,
    like the named columns). Three mounts, picked from the clicked face:
    STANDING on two stubs (a windscreen top rail or any floor), HANGING from
    two ceiling stubs, WALL flush on the wall behind two standoff brackets."""
    y0, y1, z0, z1 = NAME_BOARD_PLATE[mount]
    plate = f("board", [0.5, 0.5, 15.5, 7.5])
    edge = f("board", [0.5, 4, 2, 7.5])
    stub = f("body", [13.6, 2, 14.4, 5])
    faces = {"north": plate, "east": edge, "west": edge, "up": edge, "down": edge}
    if mount != "wall":
        faces["south"] = plate          # double-sided board, read from either side
    else:
        faces["south"] = edge           # 1 px of dark plate against the wall
    els = [elem([1, y0, z0], [15, y1, z1], faces)]
    if mount == "standing":
        for x0 in (2.5, 12.5):          # legs down to whatever it stands on
            els.append(elem([x0, 0, 7.6], [x0 + 1.2, y0, 8.4],
                            {n: stub for n in ("north", "south", "east", "west")}))
    elif mount == "hanging":
        for x0 in (2.5, 12.5):          # hangers up into the ceiling
            els.append(elem([x0, y1, 7.6], [x0 + 1.2, 16, 8.4],
                            {n: stub for n in ("north", "south", "east", "west")}))
    else:
        # Standoff brackets: their front ends are BURIED inside the plate
        # (14.6 < 15.0) so no face is coplanar with the plate's back, and the
        # face against the wall is omitted (vanilla-fence precedent).
        for x0 in (3.0, 11.8):
            els.append(elem([x0, y0 + 1, 14.6], [x0 + 1.2, y1 - 1, 16],
                            {n: stub for n in ("east", "west", "up", "down")}))
    return els


def platform_screen_blockstate(panel, post_left, post_right, rail, kick):
    """Merging run: panel always, LEFT post always (shared at each joint),
    RIGHT post only where the run ends — gate-wall rhythm. Screens also
    stack: the rail caps the top of a stack, the kick sits at its foot."""
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        def ap(mdl):
            entry = {"model": f"{MOD}:block/{mdl}"}
            if rot:
                entry["y"] = rot
            return entry
        parts.append({"when": {"facing": facing}, "apply": ap(panel)})
        parts.append({"when": {"facing": facing}, "apply": ap(post_left)})
        parts.append({"when": {"facing": facing, "right": "false"}, "apply": ap(post_right)})
        parts.append({"when": {"facing": facing, "up": "false"}, "apply": ap(rail)})
        parts.append({"when": {"facing": facing, "down": "false"}, "apply": ap(kick)})
    return {"multipart": parts}


def name_board_blockstate():
    """facing x mount (12 states). FACING is the direction the board's front
    reads toward: away from the placer for standing/hanging, out of the wall
    for the wall mount (the clicked face)."""
    variants = {}
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for mount, mdl in (("standing", "el_name_board_model"),
                           ("hanging", "el_name_board_hanging"),
                           ("wall", "el_name_board_wall")):
            entry = {"model": f"{MOD}:block/{mdl}"}
            if rot:
                entry["y"] = rot
            variants[f"facing={facing},mount={mount}"] = entry
    return {"variants": variants}


def platform_simple_blockstate(panel):
    """Facing-rotated single model (railings, posts, name board)."""
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        entry = {"model": f"{MOD}:block/{panel}"}
        if rot:
            entry["y"] = rot
        parts.append({"when": {"facing": facing}, "apply": entry})
    return {"multipart": parts}


def canopy_flat_blockstate(slab, fascia):
    parts = [{"when": {"axis": "x"}, "apply": {"model": f"{MOD}:block/{slab}"}},
             {"when": {"axis": "z"}, "apply": {"model": f"{MOD}:block/{slab}", "y": 90}}]
    for side, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        entry = {"model": f"{MOD}:block/{fascia}"}
        if rot:
            entry["y"] = rot
        parts.append({"when": {side: "false"}, "apply": entry})
    return {"multipart": parts}


def canopy_gable_blockstate(roof, end):
    parts = [{"when": {"axis": "x"}, "apply": {"model": f"{MOD}:block/{roof}"}},
             {"when": {"axis": "z"}, "apply": {"model": f"{MOD}:block/{roof}", "y": 90}}]
    # the end plate closes open RUN ends only (along the ridge axis)
    for axis, side, rot in (("x", "west", 0), ("x", "east", 180),
                            ("z", "north", 90), ("z", "south", 270)):
        entry = {"model": f"{MOD}:block/el_canopy_gable_end"}
        if rot:
            entry["y"] = rot
        parts.append({"when": {"axis": axis, side: "false"}, "apply": entry})
    return {"multipart": parts}


def build_platform(g, assets_root):
    texdir = os.path.join(assets_root, "textures/block")
    wpng(os.path.join(texdir, "el_board.png"), tex_board())
    wpng(os.path.join(texdir, "el_windscreen.png"), tex_windscreen())
    wpng(os.path.join(texdir, "el_corrugated.png"), tex_corrugated(PAINTS["green"]))
    wpng(os.path.join(texdir, "el_corrugated_silver.png"), tex_corrugated(PAINTS["silver"]))
    wpng(os.path.join(texdir, "el_glass.png"), tex_glass())
    wpng(os.path.join(texdir, "el_mesh.png"), tex_mesh())
    wpng(os.path.join(texdir, "el_roof_red.png"), tex_roof_red())

    def pm(name, els):
        g.model(name, PLATFORM_TEX, els)

    # panels (posts split out so runs share them)
    pm("el_windscreen_panel", windscreen_panel_elements())
    pm("el_windscreen_corrugated_panel", windscreen_corrugated_elements())
    pm("el_windscreen_glass_panel", windscreen_glass_elements())
    pm("el_windscreen_mesh_panel", windscreen_mesh_elements())
    for ref, suffix in (("body", ""), ("galv", "_silver")):
        for x0, side in ((0.0, "left"), (14.4, "right")):
            face = f(ref, [1, 4.75, 1.8, 12.75])
            pm(f"el_screen_post_{side}{suffix}",
               [elem([x0, 0, 6.9], [x0 + 1.6, 16, 9.1],
                     {n: face for n in ("north", "south", "east", "west")})])
    pm("el_screen_rail", screen_top_rail("body"))
    pm("el_screen_rail_silver", screen_top_rail("galv"))
    pm("el_screen_kick", screen_kick("body"))
    pm("el_screen_kick_silver", screen_kick("galv"))
    pm("el_railing_pipe_panel", railing_old_elements())
    pm("el_railing_modern_panel", railing_modern_elements())
    pm("el_canopy_post_shaft", canopy_post_shaft("body"))
    pm("el_canopy_post_shaft_silver", canopy_post_shaft("galv"))
    pm("el_canopy_post_brackets", canopy_post_brackets("body"))
    pm("el_canopy_post_brackets_silver", canopy_post_brackets("galv"))
    pm("el_canopy_post_item", canopy_post_shaft("body") + canopy_post_brackets("body"))
    pm("el_canopy_post_item_silver", canopy_post_shaft("galv") + canopy_post_brackets("galv"))
    pm("el_canopy_flat_slab", canopy_flat_elements("corru", "body"))
    pm("el_canopy_flat_slab_silver", canopy_flat_elements("corru_s", "galv"))
    pm("el_canopy_fascia", canopy_fascia_elements("body"))
    pm("el_canopy_fascia_silver", canopy_fascia_elements("galv"))
    pm("el_canopy_gable_roof", canopy_gable_elements())
    pm("el_canopy_gable_end", gable_end_elements())
    pm("el_name_board_model", name_board_elements("standing"))
    pm("el_name_board_hanging", name_board_elements("hanging"))
    pm("el_name_board_wall", name_board_elements("wall"))

    # blockstates
    for block, panel, silver in (("el_windscreen", "el_windscreen_panel", False),
                                 ("el_windscreen_corrugated", "el_windscreen_corrugated_panel", False),
                                 ("el_windscreen_glass", "el_windscreen_glass_panel", True),
                                 ("el_windscreen_mesh", "el_windscreen_mesh_panel", True)):
        sv = "_silver" if silver else ""
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"),
             platform_screen_blockstate(panel, f"el_screen_post_left{sv}",
                                        f"el_screen_post_right{sv}",
                                        f"el_screen_rail{sv}", f"el_screen_kick{sv}"))
    g.wj(os.path.join(assets_root, "blockstates", "el_railing_pipe.json"),
         platform_simple_blockstate("el_railing_pipe_panel"))
    g.wj(os.path.join(assets_root, "blockstates", "el_railing_modern.json"),
         platform_simple_blockstate("el_railing_modern_panel"))
    for block, shaft, brackets in (
            ("el_canopy_post", "el_canopy_post_shaft", "el_canopy_post_brackets"),
            ("el_canopy_post_silver", "el_canopy_post_shaft_silver",
             "el_canopy_post_brackets_silver")):
        parts = [{"apply": {"model": f"{MOD}:block/{shaft}"}}]
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            entry = {"model": f"{MOD}:block/{brackets}"}
            if rot:
                entry["y"] = rot
            parts.append({"when": {"facing": facing, "up": "false"}, "apply": entry})
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"),
             {"multipart": parts})
    g.wj(os.path.join(assets_root, "blockstates", "el_canopy_flat.json"),
         canopy_flat_blockstate("el_canopy_flat_slab", "el_canopy_fascia"))
    g.wj(os.path.join(assets_root, "blockstates", "el_canopy_flat_silver.json"),
         canopy_flat_blockstate("el_canopy_flat_slab_silver", "el_canopy_fascia_silver"))
    g.wj(os.path.join(assets_root, "blockstates", "el_canopy_gable.json"),
         canopy_gable_blockstate("el_canopy_gable_roof", "el_canopy_gable_end"))
    g.wj(os.path.join(assets_root, "blockstates", "el_name_board.json"),
         name_board_blockstate())

    # item models: screens show panel + both posts, the rest their base model
    for block, panel, silver in (("el_windscreen", "el_windscreen_panel", False),
                                 ("el_windscreen_corrugated", "el_windscreen_corrugated_panel", False),
                                 ("el_windscreen_glass", "el_windscreen_glass_panel", True),
                                 ("el_windscreen_mesh", "el_windscreen_mesh_panel", True)):
        g.wj(os.path.join(assets_root, "models/item", block + ".json"),
             {"parent": f"{MOD}:block/{panel}"})
    for block, mdl in (("el_railing_pipe", "el_railing_pipe_panel"),
                       ("el_railing_modern", "el_railing_modern_panel"),
                       ("el_canopy_post", "el_canopy_post_item"),
                       ("el_canopy_post_silver", "el_canopy_post_item_silver"),
                       ("el_canopy_flat", "el_canopy_flat_slab"),
                       ("el_canopy_flat_silver", "el_canopy_flat_slab_silver"),
                       ("el_canopy_gable", "el_canopy_gable_roof"),
                       ("el_name_board", "el_name_board_model")):
        g.wj(os.path.join(assets_root, "models/item", block + ".json"),
             {"parent": f"{MOD}:block/{mdl}"})


# -------------------------------------------------------------- blockstates --
def column_blockstate(shaft, foot, cap):
    return {"multipart": [
        {"apply": {"model": f"{MOD}:block/{shaft}"}},
        {"when": {"down": "false"}, "apply": {"model": f"{MOD}:block/{foot}"}},
        {"when": {"up": "false"}, "apply": {"model": f"{MOD}:block/{cap}"}},
    ]}


def girder_blockstate(base, brace):
    parts = []
    for axis, rot in (("x", 0), ("z", 90)):
        entry = {"model": f"{MOD}:block/{base}"}
        brace_entry = {"model": f"{MOD}:block/{brace}"}
        if rot:
            entry["y"] = rot
            brace_entry["y"] = rot
        parts.append({"when": {"axis": axis}, "apply": entry})
        parts.append({"when": {"axis": axis, "braced": "true"}, "apply": brace_entry})
    return {"multipart": parts}


def deck_blockstate(base):
    return {"variants": {
        "axis=x": {"model": f"{MOD}:block/{base}"},
        "axis=z": {"model": f"{MOD}:block/{base}", "y": 90},
    }}


# ------------------------------------------------------------------ data ----
BLOCKS = [
    "el_column", "el_column_silver", "el_column_station",
    "el_column_named", "el_column_named_station",
    "el_lattice_column", "el_lattice_column_silver", "el_lattice_column_station",
    "el_girder", "el_girder_silver", "el_girder_station",
    "el_truss", "el_truss_silver", "el_truss_station",
    "el_deck_ties", "el_deck_plate",
    "el_windscreen", "el_windscreen_corrugated", "el_windscreen_glass",
    "el_windscreen_mesh", "el_railing_pipe", "el_railing_modern",
    "el_canopy_post", "el_canopy_post_silver",
    "el_canopy_flat", "el_canopy_flat_silver", "el_canopy_gable",
    "el_name_board",
]

PROPS = {
    **{b: {"up": {"true", "false"}, "down": {"true", "false"},
           "facing": {"north", "south", "east", "west"}}
       for b in BLOCKS[:8]},
    **{b: {"axis": {"x", "z"}, "braced": {"true", "false"}} for b in BLOCKS[8:14]},
    **{b: {"axis": {"x", "z"}} for b in BLOCKS[14:16]},
    **{b: {"facing": {"north", "south", "east", "west"},
           "left": {"true", "false"}, "right": {"true", "false"},
           "up": {"true", "false"}, "down": {"true", "false"}}
       for b in BLOCKS[16:20]},
    **{b: {"facing": {"north", "south", "east", "west"},
           "left": {"true", "false"}, "right": {"true", "false"}}
       for b in BLOCKS[20:22]},
    **{b: {"facing": {"north", "south", "east", "west"},
           "up": {"true", "false"}, "down": {"true", "false"}}
       for b in ("el_canopy_post", "el_canopy_post_silver")},
    **{b: {"axis": {"x", "z"},
           "north": {"true", "false"}, "south": {"true", "false"},
           "east": {"true", "false"}, "west": {"true", "false"}}
       for b in ("el_canopy_flat", "el_canopy_flat_silver", "el_canopy_gable")},
    "el_name_board": {"facing": {"north", "south", "east", "west"},
                      "mount": {"standing", "wall", "hanging"}},
}

RECIPES = {
    "el_column_silver": {"type": "minecraft:crafting_shaped", "category": "building",
                         "key": {"I": {"item": "minecraft:iron_ingot"}},
                         "pattern": ["I", "I", "I"],
                         "result": {"item": f"{MOD}:el_column_silver", "count": 4}},
    "el_lattice_column_silver": {"type": "minecraft:crafting_shaped", "category": "building",
                                 "key": {"B": {"item": "minecraft:iron_bars"}},
                                 "pattern": ["B", "B", "B"],
                                 "result": {"item": f"{MOD}:el_lattice_column_silver", "count": 4}},
    "el_girder_silver": {"type": "minecraft:crafting_shaped", "category": "building",
                         "key": {"I": {"item": "minecraft:iron_ingot"}},
                         "pattern": ["III", " I ", "III"],
                         "result": {"item": f"{MOD}:el_girder_silver", "count": 8}},
    "el_truss_silver": {"type": "minecraft:crafting_shaped", "category": "building",
                        "key": {"I": {"item": "minecraft:iron_ingot"},
                                "B": {"item": "minecraft:iron_bars"}},
                        "pattern": ["III", "BBB", "III"],
                        "result": {"item": f"{MOD}:el_truss_silver", "count": 8}},
    "el_deck_ties": {"type": "minecraft:crafting_shaped", "category": "building",
                     "key": {"I": {"item": "minecraft:iron_ingot"},
                             "L": {"item": "minecraft:dark_oak_log"}},
                     "pattern": ["LLL", "III"],
                     "result": {"item": f"{MOD}:el_deck_ties", "count": 6}},
    "el_deck_plate": {"type": "minecraft:crafting_shaped", "category": "building",
                      "key": {"I": {"item": "minecraft:iron_ingot"}},
                      "pattern": ["III", "III"],
                      "result": {"item": f"{MOD}:el_deck_plate", "count": 6}},
}
# paint/name conversions, all shapeless 1:1
for silver, green, station in (("el_column_silver", "el_column", "el_column_station"),
                               ("el_lattice_column_silver", "el_lattice_column",
                                "el_lattice_column_station"),
                               ("el_girder_silver", "el_girder", "el_girder_station"),
                               ("el_truss_silver", "el_truss", "el_truss_station")):
    RECIPES[green] = {"type": "minecraft:crafting_shapeless", "category": "building",
                      "ingredients": [{"item": f"{MOD}:{silver}"},
                                      {"item": "minecraft:green_dye"}],
                      "result": {"item": f"{MOD}:{green}"}}
    RECIPES[station] = {"type": "minecraft:crafting_shapeless", "category": "building",
                        "ingredients": [{"item": f"{MOD}:{silver}"},
                                        {"item": "minecraft:white_dye"}],
                        "result": {"item": f"{MOD}:{station}"}}
RECIPES["el_column_named"] = {"type": "minecraft:crafting_shapeless", "category": "building",
                              "ingredients": [{"item": f"{MOD}:el_column"},
                                              {"item": "minecraft:black_dye"}],
                              "result": {"item": f"{MOD}:el_column_named"}}
RECIPES.update({
    "el_windscreen": {"type": "minecraft:crafting_shaped", "category": "building",
                      "key": {"P": {"item": "minecraft:glass_pane"},
                              "I": {"item": "minecraft:iron_ingot"}},
                      "pattern": ["PP", "II"],
                      "result": {"item": f"{MOD}:el_windscreen", "count": 4}},
    "el_windscreen_corrugated": {"type": "minecraft:crafting_shaped", "category": "building",
                                 "key": {"I": {"item": "minecraft:iron_ingot"}},
                                 "pattern": ["II", "II"],
                                 "result": {"item": f"{MOD}:el_windscreen_corrugated", "count": 4}},
    "el_windscreen_glass": {"type": "minecraft:crafting_shaped", "category": "building",
                            "key": {"P": {"item": "minecraft:glass_pane"},
                                    "I": {"item": "minecraft:iron_ingot"}},
                            "pattern": ["P", "P", "I"],
                            "result": {"item": f"{MOD}:el_windscreen_glass", "count": 2}},
    "el_windscreen_mesh": {"type": "minecraft:crafting_shaped", "category": "building",
                           "key": {"B": {"item": "minecraft:iron_bars"}},
                           "pattern": ["BB", "BB"],
                           "result": {"item": f"{MOD}:el_windscreen_mesh", "count": 4}},
    "el_railing_pipe": {"type": "minecraft:crafting_shaped", "category": "building",
                        "key": {"I": {"item": "minecraft:iron_ingot"}},
                        "pattern": ["I I", "III"],
                        "result": {"item": f"{MOD}:el_railing_pipe", "count": 4}},
    "el_railing_modern": {"type": "minecraft:crafting_shaped", "category": "building",
                          "key": {"B": {"item": "minecraft:iron_bars"},
                                  "I": {"item": "minecraft:iron_ingot"}},
                          "pattern": ["BIB"],
                          "result": {"item": f"{MOD}:el_railing_modern", "count": 4}},
    "el_canopy_post_silver": {"type": "minecraft:crafting_shaped", "category": "building",
                              "key": {"I": {"item": "minecraft:iron_ingot"}},
                              "pattern": ["I", "I"],
                              "result": {"item": f"{MOD}:el_canopy_post_silver", "count": 4}},
    "el_canopy_post": {"type": "minecraft:crafting_shapeless", "category": "building",
                       "ingredients": [{"item": f"{MOD}:el_canopy_post_silver"},
                                       {"item": "minecraft:green_dye"}],
                       "result": {"item": f"{MOD}:el_canopy_post"}},
    "el_canopy_flat_silver": {"type": "minecraft:crafting_shaped", "category": "building",
                              "key": {"I": {"item": "minecraft:iron_ingot"},
                                      "B": {"item": "minecraft:iron_bars"}},
                              "pattern": ["III", " B "],
                              "result": {"item": f"{MOD}:el_canopy_flat_silver", "count": 4}},
    "el_canopy_flat": {"type": "minecraft:crafting_shapeless", "category": "building",
                       "ingredients": [{"item": f"{MOD}:el_canopy_flat_silver"},
                                       {"item": "minecraft:green_dye"}],
                       "result": {"item": f"{MOD}:el_canopy_flat"}},
    "el_canopy_gable": {"type": "minecraft:crafting_shaped", "category": "building",
                        "key": {"I": {"item": "minecraft:iron_ingot"}},
                        "pattern": [" I ", "III"],
                        "result": {"item": f"{MOD}:el_canopy_gable", "count": 4}},
    "el_name_board": {"type": "minecraft:crafting_shapeless", "category": "building",
                      "ingredients": [{"item": "minecraft:iron_ingot"},
                                      {"item": "minecraft:iron_ingot"},
                                      {"item": "minecraft:black_dye"}],
                      "result": {"item": f"{MOD}:el_name_board"}},
})
RECIPES["el_column_named_station"] = {
    "type": "minecraft:crafting_shapeless", "category": "building",
    "ingredients": [{"item": f"{MOD}:el_column_station"},
                    {"item": "minecraft:black_dye"}],
    "result": {"item": f"{MOD}:el_column_named_station"}}


def loot(block):
    return {"type": "minecraft:block",
            "pools": [{"rolls": 1,
                       "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]}


# -------------------------------------------------------------------- main --
def build(assets_root, data_root):
    g = Gen(assets_root, data_root)
    texdir = os.path.join(assets_root, "textures/block")
    os.makedirs(texdir, exist_ok=True)
    for paint, p in PAINTS.items():
        wpng(os.path.join(texdir, f"el_steel_{paint}.png"), tex_steel(p))
        wpng(os.path.join(texdir, f"el_lattice_{paint}.png"), tex_lattice(p))
    wpng(os.path.join(texdir, "el_deck_wood.png"), tex_deck_wood())
    wpng(os.path.join(texdir, "el_deck_steel.png"), tex_deck_steel())

    for paint, p in PAINTS.items():
        tinted = p["tint"]
        tex = steel_tex(paint)
        tex_l = dict(tex, lattice=f"{MOD}:block/el_lattice_{paint}")
        g.model(f"el_column_shaft_solid_{paint}", tex,
                column_core(tinted) + column_angles(tinted))
        g.model(f"el_column_shaft_lattice_{paint}", tex_l,
                column_lattice(tinted) + column_angles(tinted))
        g.model(f"el_column_foot_{paint}", tex, column_foot(tinted))
        g.model(f"el_column_cap_{paint}", tex, column_cap(tinted))
        g.model(f"el_girder_{paint}", tex, girder_elements(tinted))
        g.model(f"el_truss_{paint}", tex_l, truss_elements(tinted))
        g.model(f"el_girder_brace_{paint}", tex, brace_elements(tinted))
        # combined item models (shaft + foot + cap, the iron-column pattern)
        g.model(f"el_column_item_solid_{paint}", tex,
                column_core(tinted) + column_angles(tinted)
                + column_foot(tinted) + column_cap(tinted))
        g.model(f"el_column_item_lattice_{paint}", tex_l,
                column_lattice(tinted) + column_angles(tinted)
                + column_foot(tinted) + column_cap(tinted))
    deck_tex = {"wood": f"{MOD}:block/el_deck_wood",
                "body": f"{MOD}:block/el_steel_green",
                "particle": f"{MOD}:block/el_deck_wood"}
    g.model("el_deck_ties_model", deck_tex, deck_ties_elements())
    plate_tex = {"plate": f"{MOD}:block/el_deck_steel",
                 "body": f"{MOD}:block/el_steel_green",
                 "particle": f"{MOD}:block/el_deck_steel"}
    g.model("el_deck_plate_model", plate_tex, deck_plate_elements())

    # blockstates
    paint_of = {"": "green", "_silver": "silver", "_station": "station",
                "_named": "green", "_named_station": "station"}
    for suffix, paint in paint_of.items():
        g.wj(os.path.join(assets_root, "blockstates", f"el_column{suffix}.json"),
             column_blockstate(f"el_column_shaft_solid_{paint}",
                               f"el_column_foot_{paint}", f"el_column_cap_{paint}"))
    for suffix in ("", "_silver", "_station"):
        paint = paint_of[suffix]
        g.wj(os.path.join(assets_root, "blockstates", f"el_lattice_column{suffix}.json"),
             column_blockstate(f"el_column_shaft_lattice_{paint}",
                               f"el_column_foot_{paint}", f"el_column_cap_{paint}"))
        g.wj(os.path.join(assets_root, "blockstates", f"el_girder{suffix}.json"),
             girder_blockstate(f"el_girder_{paint}", f"el_girder_brace_{paint}"))
        g.wj(os.path.join(assets_root, "blockstates", f"el_truss{suffix}.json"),
             girder_blockstate(f"el_truss_{paint}", f"el_girder_brace_{paint}"))
    g.wj(os.path.join(assets_root, "blockstates", "el_deck_ties.json"),
         deck_blockstate("el_deck_ties_model"))
    g.wj(os.path.join(assets_root, "blockstates", "el_deck_plate.json"),
         deck_blockstate("el_deck_plate_model"))

    # item models
    for suffix, paint in paint_of.items():
        g.wj(os.path.join(assets_root, "models/item", f"el_column{suffix}.json"),
             {"parent": f"{MOD}:block/el_column_item_solid_{paint}"})
    for suffix in ("", "_silver", "_station"):
        paint = paint_of[suffix]
        g.wj(os.path.join(assets_root, "models/item", f"el_lattice_column{suffix}.json"),
             {"parent": f"{MOD}:block/el_column_item_lattice_{paint}"})
        g.wj(os.path.join(assets_root, "models/item", f"el_girder{suffix}.json"),
             {"parent": f"{MOD}:block/el_girder_{paint}"})
        g.wj(os.path.join(assets_root, "models/item", f"el_truss{suffix}.json"),
             {"parent": f"{MOD}:block/el_truss_{paint}"})
    g.wj(os.path.join(assets_root, "models/item", "el_deck_ties.json"),
         {"parent": f"{MOD}:block/el_deck_ties_model"})
    g.wj(os.path.join(assets_root, "models/item", "el_deck_plate.json"),
         {"parent": f"{MOD}:block/el_deck_plate_model"})

    # loot + recipes
    for block in BLOCKS:
        g.wj(os.path.join(data_root, MOD, "loot_tables/blocks", block + ".json"), loot(block))
    for name, recipe in RECIPES.items():
        g.wj(os.path.join(data_root, MOD, "recipes", name + ".json"), recipe)

    build_platform(g, assets_root)
    import gen_el_phase3
    gen_el_phase3.build_final(g, assets_root, data_root, loot)
    PROPS.update(gen_el_phase3.PROPS3)
    verify(assets_root)


def verify(assets_root):
    for block, props in PROPS.items():
        data = json.load(open(os.path.join(assets_root, "blockstates", block + ".json")))
        if "variants" in data:
            entries = [(dict(p.split("=") for p in key.split(",")) if key else {}, e)
                       for key, e in data["variants"].items()]
        else:
            entries = [(part.get("when", {}), part["apply"]) for part in data["multipart"]]
        for when, entry in entries:
            for name, value in when.items():
                assert name in props and value in props[name], f"{block}: {name}={value}"
            mdl = entry["model"].split("/")[-1]
            mpath = os.path.join(assets_root, "models/block", mdl + ".json")
            assert os.path.exists(mpath), f"{block}: missing model {mdl}"
            for el in json.load(open(mpath))["elements"]:
                for fname, face in el["faces"].items():
                    assert "uv" in face, f"{mdl}: {fname} missing uv"
                    u0, v0, u1, v1 = face["uv"]
                    assert 0 <= min(u0, u1) and max(u0, u1) <= 16 \
                        and 0 <= min(v0, v1) and max(v0, v1) <= 16, f"{mdl}: uv off sprite"
                rot = el.get("rotation")
                if rot:
                    assert rot["angle"] in (-45, -22.5, 22.5, 45), f"{mdl}: bad angle"
    print("verify: el blockstates + models OK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", metavar="DIR",
                        help="write the tree here instead of the mod sources")
    args = parser.parse_args()
    if args.out:
        assets_root = os.path.join(args.out, "assets", MOD)
        data_root = os.path.join(args.out, "data")
    else:
        assets_root = os.path.join(ROOT, "src/main/resources/assets", MOD)
        data_root = os.path.join(ROOT, "src/main/resources/data")
    build(assets_root, data_root)


if __name__ == "__main__":
    main()
