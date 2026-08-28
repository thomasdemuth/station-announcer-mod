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
    """X-laced lattice web, 32px CUTOUT: 2-texel straps on both 45° diagonals,
    period 8 texels (divides 32 — tiles seamlessly along runs and up stacks).

    The sheet is sampled 1 uv unit per model pixel like el_steel, so a strap
    is ONE model pixel (~6 cm, a real lacing bar) on a 4 px (~25 cm) pitch.
    The old 16 px sheet drew 2 px straps on an 8 px pitch — half as many
    diamonds, twice as fat, which is what made lattice columns and the truss
    read as a solid dark panel instead of open lacing."""
    rows = pk.canvas(32, 32, (0, 0, 0, 0))
    for off in range(-32, 64, 8):
        for i in range(32):
            for w in range(2):
                for xx, yy in (((off + i + w) % 32, i), ((off - i + w) % 32, i)):
                    if 0 <= xx < 32:
                        rows[yy][xx] = p["base"] if w else p["dark"]
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


def _plate(y0, y1, half, tinted, up=None, down=None, riveted=False):
    """One square plate of a stepped base/cap, centred on the column axis.
    `half` is the half-width in model px; the four side faces take a slice
    the plate's own height so the sheet is never stretched, and `riveted`
    picks the sheet's horizontal rivet seam (texel rows 6..8 = uv v 3..4)
    so a base/cap plate shows its anchor bolts."""
    w = half * 2
    v0 = (4.0 - (y1 - y0) / 2) if riveted else 6.0
    side = f("body", [0.5, v0, 0.5 + w, v0 + (y1 - y0)], tinted)
    faces = {n: side for n in ("north", "south", "east", "west")}
    lid_uv = [0.5, 1.2, 0.5 + w, 1.2 + w]      # crosses both rivet seams
    if up is not None:
        faces["up"] = f("body", lid_uv, tinted, cull=up or None)
    if down is not None:
        faces["down"] = f("body", lid_uv, tinted, cull=down or None)
    return elem([8 - half, y0, 8 - half], [8 + half, y1, 8 + half], faces)


def column_foot(tinted):
    """The street base: a wide bolted BASE PLATE, a bolster above it and a
    shoe collar hugging the shaft. Three squared steps read as a bolted-down
    street column; the old two-step taper read as a table-leg flare."""
    return [
        _plate(0.0, 1.1, 5.8, tinted, up="", down="down", riveted=True),
        _plate(1.1, 2.4, 5.0, tinted, up="", riveted=True),
        _plate(2.4, 3.4, 4.2, tinted, up=""),
    ]


def column_cap(tinted):
    """The gusset cap: a collar, a bolster and a wide bearing plate the cross
    girder sits on. Flatter and wider than the foot (a cap spreads the load
    sideways, a base spreads it down) so the two never read as the same
    tapered flare stood on its head. The plate stops 0.1 px short of y16 —
    the girder's bottom flange face lives exactly on that plane."""
    return [
        _plate(12.6, 13.6, 4.2, tinted, down=""),
        _plate(13.6, 14.7, 5.0, tinted, down="", riveted=True),
        _plate(14.7, 15.9, 5.9, tinted, up="up", down="", riveted=True),
    ]


# --- the plate-girder / truss section (axis x, a run spans x 0..16) --------
# One block deep = 1 m: a real el longitudinal plate girder is ~1 m deep with
# a ~0.43 m flange, which is FLANGE_Z1-FLANGE_Z0 below.  The web is recessed
# 2.2 px behind the flange edges on both sides so the flange throws a shadow
# line the whole length of a run — without that the run is one flat apron and
# the whole el reads as a table (Thomas's complaint).
FLANGE_Z0, FLANGE_Z1 = 4.6, 11.4      # 6.8 px = 0.43 m flange width
WEB_Z0, WEB_Z1 = 7.0, 9.0             # 2.0 px = 0.125 m riveted web plate
RIB_Z0, RIB_Z1 = 5.8, 10.2            # stiffener angles, 1.2 proud of the web
BRACE_Z0, BRACE_Z1 = 6.1, 9.9         # gusset plate thickness (0.24 m)
FLANGE_H = 2.0
# stiffener stations: one at each block end (they pair up across a joint into
# the splice rib a real girder has there) plus two intermediate.  A 32 cm
# pitch is denser than the prototype's ~1 m, and deliberately so: at one
# model pixel = 6 cm, a single rib per metre leaves a 90 cm blank panel that
# reads as sheet metal.  Three panels per block is what makes a run read as
# a riveted girder instead of an apron.
RIB_X = (0.05, 5.15, 9.65, 14.75)
RIB_W = 1.2
# uv windows on el_steel (1 uv unit = 1 model px = 2 texels):
#   v 3..4 and v 12..13  horizontal rivet seams
#   u 13.5..14.5         the vertical rivet ladder
#   v 5..11              the clean band
RIVET_BAND = lambda h: [0.25, 3.5 - h / 2, 15.75, 3.5 + h / 2]
LADDER = lambda h: [13.35, 1.5, 14.55, 1.5 + h]


def girder_elements(tinted):
    """Riveted plate girder, axis x. Proud top and bottom flanges with a
    rivet row along each, a recessed web carrying the sheet's two horizontal
    rivet seams, and vertical stiffener angles standing off the web."""
    web = f("body", [0.25, 1.5, 15.75, 13.5], tinted)
    els = [elem([0, FLANGE_H, WEB_Z0], [16, 16 - FLANGE_H, WEB_Z1],
                {"north": web, "south": web})]
    for y0 in (0, 16 - FLANGE_H):
        lid = f("body", [0.25, 5, 15.75, 5 + (FLANGE_Z1 - FLANGE_Z0)], tinted)
        edge = f("body", RIVET_BAND(FLANGE_H), tinted)
        els.append(elem([0, y0, FLANGE_Z0], [16, y0 + FLANGE_H, FLANGE_Z1], {
            "north": edge, "south": edge,
            "up": f("body", lid["uv"], tinted, cull="up" if y0 else None),
            "down": f("body", lid["uv"], tinted, cull=None if y0 else "down"),
        }))
    h = 16 - 2 * FLANGE_H
    for x0 in RIB_X:
        side = f("body", [1, 1.5, 1 + (RIB_Z1 - RIB_Z0), 1.5 + h], tinted)
        els.append(elem([x0, FLANGE_H, RIB_Z0], [x0 + RIB_W, 16 - FLANGE_H, RIB_Z1], {
            "north": f("body", LADDER(h), tinted),
            "south": f("body", LADDER(h), tinted),
            "east": side, "west": side,
        }))
    return els


TRUSS_CH_H = 2.2                       # chord depth
TRUSS_Z0, TRUSS_Z1 = 5.4, 10.6         # chord (angle-pair) width
TRUSS_POST_X = (0.05, 7.4, 14.75)      # end posts + one at the panel point


def truss_elements(tinted):
    """Open lattice truss, axis x: chord angles top and bottom, vertical
    posts at the panel points and a see-through X-laced web between them.
    The chords are shallower than the plate girder's flanges and the web is
    11.6 px of lacing, so the section reads OPEN rather than as a dark panel
    with a picture frame around it."""
    els = []
    for y0 in (0, 16 - TRUSS_CH_H):
        lid = f("body", [0.25, 5, 15.75, 5 + (TRUSS_Z1 - TRUSS_Z0)], tinted)
        edge = f("body", RIVET_BAND(TRUSS_CH_H), tinted)
        els.append(elem([0, y0, TRUSS_Z0], [16, y0 + TRUSS_CH_H, TRUSS_Z1], {
            "north": edge, "south": edge,
            "up": f("body", lid["uv"], tinted, cull="up" if y0 else None),
            "down": f("body", lid["uv"], tinted, cull=None if y0 else "down"),
        }))
    h = 16 - 2 * TRUSS_CH_H
    lattice = f("lattice", [0, TRUSS_CH_H, 16, 16 - TRUSS_CH_H], tinted)
    els.append(elem([0, TRUSS_CH_H, 7.6], [16, 16 - TRUSS_CH_H, 8.4],
                    {"north": lattice, "south": lattice}))
    for x0 in TRUSS_POST_X:
        side = f("body", [1, 1.5, 4.2, 1.5 + h], tinted)
        els.append(elem([x0, TRUSS_CH_H, 6.6], [x0 + RIB_W, 16 - TRUSS_CH_H, 9.4], {
            "north": f("body", LADDER(h), tinted),
            "south": f("body", LADDER(h), tinted),
            "east": side, "west": side,
        }))
    return els


# --- knee braces ----------------------------------------------------------
# The old braces were 45°-rotated struts aimed INTO the column: everything
# with 4.7 <= x <= 11.3 was buried inside the column body, so only ~4 px of
# each one was ever visible — that is why they read as stubs.  They are now
# stepped GUSSET PLATES that live in the air BESIDE the column (the column
# occupies x 4.7..11.3 of the block below; x 0..4.7 and 11.3..16 are empty),
# deepest where they meet the column shaft and tapering out to nothing under
# the girder.  Axis-aligned boxes only, so the run's rotation is exact.
BRACE_STEPS = 6
BRACE_DROP = 9.0          # how far down the column shaft the gusset reaches
BRACE_TOP = 1.2           # buried inside the girder's bottom flange
BRACE_OUT = 0.1           # the gusset's outer tip, at the girder's block end
BRACE_IN = 5.3            # 0.6 past the column face at 4.7: it dies into it


def brace_elements(tinted):
    """A stepped gusset plate down each side of the column below, in the
    plane of the girder — the signature el knee brace. Six steps read as the
    photos' curved bracket at any sane render distance.

    Steps ABUT exactly rather than overlapping, and each one omits the face
    on its deeper side (that face is entirely buried in the next step), so
    no two faces of the plate are coplanar with overlapping footprints."""
    els = []
    w = (BRACE_IN - BRACE_OUT) / BRACE_STEPS
    for left in (True, False):
        for k in range(BRACE_STEPS):        # k = 0 at the tip, 5 at the column
            bottom = -1.0 - k * (BRACE_DROP - 1.0) / (BRACE_STEPS - 1)
            h = BRACE_TOP - bottom
            a, b = BRACE_OUT + k * w, BRACE_OUT + (k + 1) * w
            x0, x1 = (a, b) if left else (16 - b, 16 - a)
            outer, inner = ("west", "east") if left else ("east", "west")
            faces = {
                "north": f("body", [13.0, 0.4, 14.8, 0.4 + h], tinted),
                "south": f("body", [13.0, 0.4, 14.8, 0.4 + h], tinted),
                "down": f("body", [1, 5, 1 + w, 8.2], tinted),
                outer: f("body", [1, 0.4, 4.2, 0.4 + h], tinted),
            }
            if k == BRACE_STEPS - 1:        # the end that meets the column
                faces[inner] = f("body", [1, 0.4, 4.2, 0.4 + h], tinted)
            els.append(elem([x0, bottom, BRACE_Z0], [x1, BRACE_TOP, BRACE_Z1], faces))
    return els


# --- decks ----------------------------------------------------------------
# The stringers still reach the bottom of the block (the deck SITS on the
# girder below, it must not float), but they are now real riveted I-beams:
# proud top and bottom flanges with a recessed web and stiffener ribs on the
# same 0.05/5.15/9.65/14.75 rhythm as the girder.  A run therefore reads as
# two stacked beams, each with its own shadow lines, instead of one 2 m apron.
# The stringer flanges are inset 0.3 px INSIDE the girder's 4.6..11.4 flange
# below, so the girder's flange edge stays the proud line of the whole
# assembly and the deck course sits in its shadow instead of standing flush
# with it (flush is what fused the two into one two-metre apron).
STRINGER_Z = (4.9, 8.7)     # flange near edge; flange 2.4 wide, web 1.2
STRINGER_D = 8.0            # 0.5 m — a real stringer, not a second girder
FLANGE_T = 1.4
BEARING_X = ((0.05, 1.6), (14.4, 15.95))
DECK_LOW = 0.1              # never 0: the girder's top-flange face is at y16


def stringer_elements(top_y, ref="body"):
    """A pair of longitudinal I-beam stringers hung under the deck surface,
    landing on the girder below through a bearing block at each block end.

    The stringers used to be 12.8 px deep slabs running the block's whole
    height: stacked on a girder that made a 2 m unbroken green apron, which
    is the single biggest reason the el read as a table. They are now 8 px
    deep with proud flanges, and the 4.8 px below them is OPEN except for
    the bearing blocks — which pair up across a joint into the transverse
    floor beam a real el has at every panel point."""
    els = []
    y_low = top_y - STRINGER_D
    web_h = STRINGER_D - 2 * FLANGE_T
    for z0 in STRINGER_Z:
        for y0 in (y_low, top_y - FLANGE_T):
            edge = f(ref, [0.25, 3.5 - FLANGE_T / 2, 15.75, 3.5 + FLANGE_T / 2])
            lid = f(ref, [0.25, 5, 15.75, 7.4])
            els.append(elem([0, y0, z0], [16, y0 + FLANGE_T, z0 + 2.4],
                            {"north": edge, "south": edge, "up": lid, "down": lid}))
        web = f(ref, [0.25, 1.5, 15.75, 1.5 + web_h])
        els.append(elem([0, y_low + FLANGE_T, z0 + 0.6],
                        [16, top_y - FLANGE_T, z0 + 1.8],
                        {"north": web, "south": web}))
    # bearing blocks: the deck's feet on the girder's top flange
    seat = f(ref, [1, 1.5, 7.0, 1.5 + (y_low - DECK_LOW)])
    for x0, x1 in BEARING_X:
        els.append(elem([x0, DECK_LOW, STRINGER_Z[0] + 0.1],
                        [x1, y_low + 0.4, STRINGER_Z[1] + 2.3], {
            "north": f(ref, LADDER(y_low - DECK_LOW)),
            "south": f(ref, LADDER(y_low - DECK_LOW)),
            "east": seat, "west": seat,
            "down": f(ref, [1, 5, 1 + (x1 - x0), 11.0], cull="down"),
        }))
    return els


def deck_ties_elements():
    """Open tie deck. Ties on a 4 px (25 cm) pitch, 2.0 px wide, so half the
    deck is daylight from the street below; they overhang the stringers by
    ~3 px each side exactly as the real cantilevered ties do."""
    els = []
    tie_face = f("wood", [0.4, 12.8, 15.6, 16])
    tie_end = f("wood", [1, 12.8, 3, 16])
    for x0 in (0.6, 4.6, 8.6, 12.6):
        els.append(elem([x0, 12.8, 0.4], [x0 + 2.0, 16, 15.6], {
            "up": f("wood", [x0, 0.4, x0 + 2.0, 15.6]),
            "down": f("wood", [x0, 0.4, x0 + 2.0, 15.6]),
            "east": tie_face, "west": tie_face,
            "north": tie_end, "south": tie_end,
        }))
    return els + stringer_elements(12.7)


def deck_plate_elements():
    top = f("plate", [0.25, 0.25, 15.75, 15.75])
    edge = f("plate", [0.25, 6, 15.75, 7.75])
    els = [elem([0, 12.4, 0], [16, 16, 16], {
        "up": top, "down": f("plate", [0.25, 0.25, 15.75, 15.75]),
        "north": dict(edge, cullface="north"), "south": dict(edge, cullface="south"),
        "east": dict(edge, cullface="east"), "west": dict(edge, cullface="west"),
    })]
    return els + stringer_elements(12.3)


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
    """Classic windscreen sheet, 32 px in THREE zones the panel models slice
    (2 texels = 1 model px, so a zone's uv is texels/2):

      rows  0..16   beige board-and-batten panel — 8-texel (4 px) boards, a
                    2-texel batten with its own lit/shaded edge and the HARD
                    shadow it casts on the board beside it, then the board
                    field falling away into the next seam. That cast shadow
                    is what makes the battens read as proud strips rather
                    than as painted stripes.
      rows 16..28   wired glass: glaze field, 4-texel wire diamonds, green
                    mullions every 8 texels (one pane per 4 model px), a
                    glazing bead top and bottom, two glare streaks.
      rows 28..32   green frame rail — the proud transom between the two and
                    the stock the panel edges are trimmed with.

    Everything except the three zone boundaries varies per COLUMN only, so a
    run tiles along x and a stack never repeats a horizontal feature (each
    zone is sampled exactly once, by one element)."""
    g = PAINTS["green"]
    rows = pk.canvas(32, 32, CREAM)
    # 8-texel (4 px) board: a 2-texel batten, the hard shadow it casts, then
    # five texels of near-flat board. Making the batten narrow and the board
    # WIDE is what separates "proud strips on a panel" from "fluting".
    grain = [CREAM, (222, 212, 192), CREAM, (212, 202, 182)]
    strip = {0: (236, 226, 204), 1: (214, 204, 184), 2: (170, 160, 141),
             3: (198, 188, 168), 7: (192, 182, 162)}
    for x in range(32):
        t = strip.get(x % 8, grain[(x * 5) % len(grain)])
        for y in range(16):
            rows[y][x] = t

    # ---- glazing band -----------------------------------------------------
    # wired glass: 8-texel diamonds in a tone only just off the glaze (a
    # 4-texel grid in GLAZE_DARK came out as a chequerboard, not as wire)
    wire = tuple(int(a + (b - a) * 0.45) for a, b in zip(GLAZE, GLAZE_DARK))
    pk.rect(rows, 0, 16, 32, 28, GLAZE)
    for y in range(16, 28):
        for x in range(32):
            if (x + y) % 8 == 0 or (x - y) % 8 == 0:
                rows[y][x] = wire
    for x in range(32):                                  # pane sheen column
        if x % 16 in (5, 6):
            for y in range(16, 28):
                if rows[y][x] == GLAZE:
                    rows[y][x] = GLAZE_LIT
    for i in range(11):                                  # two glare streaks
        for x0 in (2, 18):
            xx, yy = x0 + i, 16 + i
            if xx < 32 and yy < 28:
                rows[yy][xx] = GLAZE_LIT if i % 2 else (226, 238, 242)
    for x in range(0, 32, 16):                           # mullions: 2 panes/block
        pk.rect(rows, x, 16, x + 1, 28, g["dark"])
        pk.rect(rows, x + 1, 16, x + 2, 28, g["base"])
    pk.rect(rows, 0, 16, 32, 17, g["dark"])              # glazing beads
    pk.rect(rows, 0, 27, 32, 28, g["shadow"])

    # ---- green frame stock ------------------------------------------------
    pk.rect(rows, 0, 28, 32, 32, g["base"])
    pk.rect(rows, 0, 28, 32, 29, g["lit"])
    pk.rect(rows, 0, 31, 32, 32, g["shadow"])
    for x in range(4, 32, 16):                           # bolt heads on the rail
        pk.rect(rows, x, 29, x + 2, 31, g["lit"])
        rows[29][x] = g["rivet"]
    return rows


def tex_corrugated(g):
    """Corrugated sheet, 32 px. The flute is a real WAVE over its 4-texel
    (2 px) period — crest, falling flank, trough, rising flank — not the
    sawtooth the first cut had (lit→base→dark→shadow then a jump straight
    back to lit, which read as painted stripes). Vertical-only, so it tiles
    along a run and up a stack."""
    rows = pk.canvas(32, 32, g["base"])
    wave = (g["lit"], g["base"], g["shadow"], g["dark"])
    for x in range(32):
        tone = wave[x % 4]
        if x % 16 == 10 and tone is g["lit"]:
            tone = g["base"]                    # a dulled crest = weathering
        for y in range(32):
            rows[y][x] = tone
    return rows


def tex_glass():
    """Modern windscreen glass, 32 px CUTOUT. Cutout alpha is BINARY (the
    layer discards a < 0.5), so the first cut — a field of alpha-60..90
    sheen pixels — discarded every pixel and the pane rendered completely
    invisible. Every visible pixel here is fully opaque: a pane border (the
    vanilla-glass trick, which also reads as the pane division of a glazed
    screen), corner gussets and two glare streaks; the field is empty."""
    rows = pk.canvas(32, 32, (0, 0, 0, 0))
    edge = (206, 220, 226, 255)
    edge_dark = (168, 186, 194, 255)
    glare = (238, 246, 250, 255)
    for i in range(32):
        rows[0][i] = rows[31][i] = edge_dark
        rows[i][0] = rows[i][31] = edge_dark
        rows[1][i] = rows[i][1] = edge
    # VERTICAL sheen columns, not diagonals: both faces of a pane are drawn,
    # and through the holes you see the far face MIRRORED — a diagonal streak
    # therefore crosses its own reflection and paints an X on every pane.
    for x in (7, 8, 22):
        for y in range(3, 29):
            rows[y][x] = glare if x != 8 else edge
    return rows


def tex_mesh():
    """Chain-link, 32 px CUTOUT: 45° diamonds on a 4-texel (2 px) period with
    1-texel straps, the two strand directions in different tones and the
    darker one broken at every crossing so the weave reads as over/under
    rather than as a printed grid (the platform-barrier lesson). Period 4
    divides 32, so diamonds continue across block joints."""
    rows = pk.canvas(32, 32, (0, 0, 0, 0))
    steel = (176, 179, 182, 255)
    dark = (126, 129, 132, 255)
    for y in range(32):
        for x in range(32):
            down = (x + y) % 4 == 0
            up = (x - y) % 4 == 0
            if down and up:
                rows[y][x] = steel          # crossing: the near strand wins
            elif down:
                rows[y][x] = steel
            elif up:
                rows[y][x] = dark
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


# --- the one z-scheme every screen part is authored against -----------------
# posts are the outermost thing on a screen and NOTHING else may reach their
# planes: the panel sits inside them, the glazing sits inside the panel plane,
# the rail and kick sit just inside the posts (0.05) so a post's open top and
# foot are buried under them at a stack's ends but no two faces are coplanar.
POST_Z = (6.9, 9.1)
CAP_Z = (6.95, 9.05)          # top rail + kick plate
TRANSOM_Z = (7.1, 8.9)        # the proud rail between panel and glazing
PANEL_Z = (7.35, 8.65)        # solid beige / corrugated sheet plane
GLAZE_Z = (7.55, 8.45)        # glass recessed behind the frame
PANE_Z = (7.65, 8.35)         # a bare glass / mesh pane


def windscreen_base_elements():
    """Classic screen, LOWER course (up=true): the solid beige panel. Real
    windscreens are solid to about chest height and glazed above, so which
    model a block draws follows its place in the stack — a 2-high run is
    solid, then apron+transom+glass."""
    face = f("screen", [0, 0.25, 16, 7.75])
    return [elem([0, 0, PANEL_Z[0]], [16, 16, PANEL_Z[1]],
                 {"north": face, "south": face})]


def windscreen_head_elements():
    """Classic screen, TOP course (up=false, and the whole of a 1-high one):
    beige apron, proud green transom rail, wired-glass band above it. The
    apron shares the base panel's plane exactly, so a base|head joint has no
    step and no open ends — the two boxes read as one sheet."""
    apron = f("screen", [0, 3.75, 16, 7.75])
    rail = f("screen", [0, 14.25, 16, 15.75])
    lid = f("screen", [0, 14.5, 16, 15.5])
    glass = f("screen", [0, 8.25, 16, 13.75])
    return [
        elem([0, 0, PANEL_Z[0]], [16, 7.4, PANEL_Z[1]],
             {"north": apron, "south": apron}),
        elem([0, 7.4, TRANSOM_Z[0]], [16, 8.8, TRANSOM_Z[1]],
             {"north": rail, "south": rail, "up": lid, "down": lid}),
        elem([0, 8.8, GLAZE_Z[0]], [16, 16, GLAZE_Z[1]],
             {"north": glass, "south": glass}),
    ]


def windscreen_corrugated_elements():
    sheet = f("corru", [0, 0.25, 16, 15.75])
    return [elem([0, 0, PANEL_Z[0]], [16, 16, PANEL_Z[1]],
                 {"north": sheet, "south": sheet})]


def windscreen_glass_elements():
    pane = f("glass", [0.25, 0.25, 15.75, 15.75])
    return [elem([0, 0, PANE_Z[0]], [16, 16, PANE_Z[1]],
                 {"north": pane, "south": pane})]


def windscreen_mesh_elements():
    pane = f("mesh", [0, 0, 16, 16])
    return [elem([0, 0, PANE_Z[0]], [16, 16, PANE_Z[1]],
                 {"north": pane, "south": pane})]


def screen_top_rail(ref):
    """Handrail capping a screen stack's top block (up=false). Inside the
    posts in z so it buries their open tops; no end faces, because the posts
    already cover its ends at a run end (the platform-barrier rule: only the
    posts may touch x=0/16)."""
    rail = f(ref, [0.25, 4.75, 15.75, 6] if ref == "body" else [1, 5, 9, 5.9])
    lid = f(ref, [0.25, 5, 15.75, 5.8] if ref == "body" else [1, 5.1, 9, 5.8])
    return [elem([0, 14.5, CAP_Z[0]], [16, 16, CAP_Z[1]],
                 {"north": rail, "south": rail, "up": lid, "down": lid})]


def screen_kick(ref):
    """Kick plate at a screen stack's foot (down=false)."""
    kick = f(ref, [0.25, 5, 15.75, 6.2] if ref == "body" else [1, 5, 9, 6])
    return [elem([0, 0, CAP_Z[0]], [16, 1.3, CAP_Z[1]], {
        "north": kick, "south": kick, "up": kick,
        "down": dict(kick, cullface="down"),
    })]


def round_bar_x(y0, y1, z0, z1, face, cap=None):
    """A pipe running along X read as ROUND: the core box plus a twin rotated
    45° about the run axis (the turnstile-tubing trick). The twin's faces cut
    across the core's corners, so the union reads octagonal and no two faces
    are ever coplanar. `cap` (optional) closes the pipe's ends."""
    cy, cz = (y0 + y1) / 2, (z0 + z1) / 2
    faces = {"north": face, "south": face, "up": face, "down": face}
    if cap:
        faces = dict(faces, east=cap, west=cap)
    # the twin is inset 0.08 along the run so its end caps can never be
    # coplanar with the core's (two coincident same-facing quads WOULD fight)
    return [elem([0.02, y0, z0], [15.98, y1, z1], dict(faces)),
            elem([0.1, y0, z0], [15.9, y1, z1], dict(faces),
                 rotation={"origin": [8, cy, cz], "axis": "x", "angle": 45})]


def round_post_y(x0, x1, z0, z1, y0, y1, face, cap=None):
    """The same trick for an upright: core plus a 45° twin about Y."""
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    faces = {n: face for n in ("north", "south", "east", "west")}
    if cap:
        faces = dict(faces, up=cap)
    return [elem([x0, y0, z0], [x1, y1, z1], dict(faces)),
            elem([x0, y0, z0], [x1, y1, z1], dict(faces),
                 rotation={"origin": [cx, (y0 + y1) / 2, cz], "axis": "y", "angle": 45})]


# pipe railing: rail centres at 14.3 px (0.89 m — the top of the block's own
# outline shape) and 7.6 px, i.e. a standard two-rail platform edge rail.
RAIL_TOP_C, RAIL_MID_C = 14.3, 7.6


def railing_old_elements():
    """Two-rail pipe railing, green — the open-platform edge rail. Both rails
    and the post are octagonal (round), post slimmer than the rails so its
    top buries inside the top rail instead of sharing a plane with it."""
    els = []
    face = f("body", [0.25, 4.75, 15.75, 6])
    cap = f("body", [1, 5, 2.2, 6.2])
    for c in (RAIL_TOP_C, RAIL_MID_C):
        els += round_bar_x(c - 0.6, c + 0.6, 7.4, 8.6, face, cap)
    post = f("body", [13.6, 0.5, 14.4, 7.5])
    els += round_post_y(7.5, 8.5, 7.5, 8.5, 0, RAIL_TOP_C, post)
    foot = f("body", [1, 5, 4, 6])
    els.append(elem([6.4, 0, 6.4], [9.6, 0.9, 9.6],
                    dict({n: foot for n in ("north", "south", "east", "west", "up")},
                         down=f("body", [1, 5, 4, 6], cull="down"))))
    return els


def railing_modern_elements():
    """Galvanized picket railing: round top rail, flat bottom rail, 0.9-px
    pickets on a 2-px pitch (6.9 cm gaps — inside the real 10 cm rule) and a
    heavier post at the block centre, i.e. one post per metre of run."""
    els = []
    face = f("galv", [0.25, 5, 15.75, 6.4])
    cap = f("galv", [1, 5, 2.4, 6.4])
    els += round_bar_x(RAIL_TOP_C - 0.7, RAIL_TOP_C + 0.7, 7.3, 8.7, face, cap)
    bottom = f("galv", [0.25, 5, 15.75, 5.9])
    els.append(elem([0, 1.6, 7.3], [16, 2.5, 8.7],
                    {"north": bottom, "south": bottom, "up": bottom, "down": bottom}))
    # pickets sample the CLEAN band (texel rows 10..22): the steel sheet's
    # vertical rivet ladder lives at u 13.5..14.5 and stippled every picket
    pick = f("galv", [1, 5, 1.8, 11])
    for x in range(1, 16, 2):
        if x in (7, 9):
            continue                      # the centre post stands here instead
        els.append(elem([x, 1.6, 7.6], [x + 0.9, RAIL_TOP_C, 8.4],
                        {n: pick for n in ("north", "south", "east", "west")}))
    post = f("galv", [13.4, 0.5, 14.6, 7.5])
    els.append(elem([7.2, 0, 7.2], [8.8, RAIL_TOP_C, 8.8],
                    {n: post for n in ("north", "south", "east", "west")}))
    return els


def canopy_post_shaft(ref="body"):
    """Slim square canopy post shaft (stacks; brackets cap the stack top)."""
    face = f(ref, [12.4, 0.25, 15.4, 15.75])
    return [elem([6.6, 0, 6.6], [9.4, 16, 9.4],
                 {n: face for n in ("north", "south", "east", "west")})]


def canopy_post_brackets(ref="body"):
    """The curved top brackets, drawn only on a stack's top block.

    Three STEPPED 45° struts per side (the girder knee-brace recipe) whose
    feet all land on the post shaft and whose heads fan out to x≈1.7/3.9/5.9:
    the staircase of heads reads as the photos' curved gusset, where the old
    single 2.4 px stick read as a nub. Every strut tops out at y16.0 exactly,
    so the bracket touches the canopy block above (its underside chord's down
    face is at that plane, facing the other way — back to back, never
    coplanar-same-facing). The z spans are NESTED (6.7/6.8/6.9) so the three
    struts' side faces never share a plane where they overlap."""
    els = []
    # (head x of the WEST strut, strut length, z inset) — outermost first
    steps = ((0.4, 7.6, 0.0), (2.6, 5.4, 0.1), (4.6, 3.2, 0.2))
    for x0, length, inset in steps:
        for west in (True, False):
            xa = x0 if west else 16 - x0 - 2.6
            angle = 45 if west else -45
            b = f(ref, [1, 4.75, 3.6, 10.5])
            els.append(elem([xa, 16.0 - length, 6.7 + inset],
                            [xa + 2.6, 16.0, 9.3 - inset], {
                "north": b, "south": b,
                "east": f(ref, [1, 4.75, 3.7, 10.5]),
                "west": f(ref, [1, 4.75, 3.7, 10.5]),
                "down": f(ref, [1, 5, 3.7, 6.3]),
            }, rotation={"origin": [xa + 1.3, 16.0, 8], "axis": "z", "angle": angle}))
    return els


def canopy_flat_elements(sheet="corru", ref="body"):
    """Flat corrugated canopy slab on its rafters (W 8th St style). Authored
    LOW in the block so a canopy placed directly above its posts touches
    them — the roof plane is the cell's floor, not its ceiling.

    The deck spans the whole cell and its four side faces sit exactly on the
    block boundary with cullface, so a FIELD of canopies is one unbroken
    sheet: neighbouring cells meet back to back (opposite normals never
    z-fight) and no interior seam is drawn twice.

    Underside: two CROSSWISE rafters on a pitch of 8 (which divides 16, so
    the rhythm is identical inside a cell and across a joint — the zebra
    lesson), plus the longitudinal centre purlin at x/z 7..9 that hanging
    blocks (the NYC PIDS' 2 px ceiling stub) land on. Members that span the
    cell omit their run-continuation end faces."""
    els = []
    els.append(elem([0, 1.4, 0], [16, 3.4, 16], {
        "up": f(sheet, [0, 0.25, 16, 15.75]),
        "down": f(sheet, [0, 0.25, 16, 15.75]),
        "north": f(ref, [1, 5, 9, 6], cull="north"),
        "south": f(ref, [1, 5, 9, 6], cull="south"),
        "east": f(ref, [1, 5, 9, 6], cull="east"),
        "west": f(ref, [1, 5, 9, 6], cull="west"),
    }))
    rafter = f(ref, [0.25, 5, 15.75, 6.2])
    for x0 in (3.2, 11.2):                     # crosswise rafters, pitch 8
        els.append(elem([x0, 0.3, 0], [x0 + 1.6, 1.4, 16],
                        {"east": rafter, "west": rafter, "down": rafter}))
    purlin = f(ref, [0.25, 5, 15.75, 6.4])     # centre purlin (hanging steel)
    els.append(elem([0, 0.0, 7.0], [16, 1.4, 9.0],
                    {"north": purlin, "south": purlin, "down": purlin}))
    return els


def canopy_fascia_elements(ref="body"):
    """Riveted edge girder at the canopy's open edge (separate model — the
    block applies it to every unconnected side automatically).

    It HANGS 1.4 px below the deck instead of standing proud of it: the old
    box ran y0..5.6 against a deck of y1.4..3.4, i.e. a 2.2 px parapet above
    the roof, which read as a tray. Inset 0.3 off the boundary so its outer
    face is never coplanar with the deck's own edge face, and its top face
    is omitted (buried inside the deck)."""
    face = f(ref, [0.25, 0.25, 15.75, 4.25])
    return [elem([0, 0.0, 0.3], [16, 3.2, 2.1],
                 {"north": face, "south": face,
                  "down": f(ref, [0.25, 5, 15.75, 6.8])})]


# ---- the gable family: ONE roof over any platform width ---------------------
# Authored frame: the RUN (ridge/axis) is x, so the crosswise direction — the
# platform's width — is z, and a cell's crosswise neighbours are north/south.
#
# The whole family is one continuous 22.5° pitch, and each cell draws the
# stretch of that profile its own connections imply:
#   0 crosswise neighbours  a single-row canopy: the classic steep 45° gable,
#                           eave (mid y 1.8) at each block edge, ridge 9.8.
#   1 crosswise neighbour   a WING: 22.5° from the eave at its open edge
#                           (mid 3.17, overhanging 1.6 px past the block) up
#                           to the shared boundary at 9.8.
#   2 crosswise neighbours  the CROWN: 22.5° from 9.8 at both boundaries to a
#                           ridge at 13.11 over the cell's centre line.
# So 2 rows meet in a ridge ON their shared boundary, and 3 rows are ONE roof
# whose ridge sits over the middle row with the eaves overhanging the platform
# edges — the Marcy Av read. (Wider than 3 repeats the crown bay, which is
# what a multi-bay train shed does; nothing ever gaps or z-fights, because
# every profile hands the next cell the same 9.8 boundary height.)
EAVE45 = 1.8            # 45° gable: sheet centre line where it crosses z=0/16
RIDGE45 = 9.8           # ... and over the centre line
EDGE22 = 9.8            # 22.5° family: sheet centre at a shared boundary
EAVE22 = 3.173          # ... at a wing's open edge  (9.8 − 16·tan22.5)
CROWN22 = 13.114        # ... over a crown cell's centre line (9.8 + 8·tan22.5)
OVERHANG = 1.6          # eaves reach this far past the block edge


def _roof_tie():
    """Bottom chord of the roof truss: the steel a hanging PIDS/sign stub
    (x/z 7..9) lands on, at the same height in every profile so a mixed run
    keeps one continuous chord line."""
    tie = f("body", [0.25, 5, 15.75, 6.2])
    return [elem([0, 0, 6.9], [16, 1.2, 9.1],
                 {"north": tie, "south": tie, "down": tie, "up": tie})]


def _sheet_faces():
    plane = f("roof", [0, 0.25, 16, 15.75])
    edge = f("roof", [0, 8.5, 16, 9.3])
    end = f("roof", [0, 8.5, 16, 9.4])
    return plane, edge, end


def canopy_gable_elements():
    """The single-row canopy: a real 45° gable, eaves on the block edges and
    a 1.6 px overhang with an eave board beyond them."""
    plane, edge, end = _sheet_faces()
    els = []
    for oz, angle in ((0.0, -45), (16.0, 45)):
        # the plane runs from the overhang tip (mid y 0.2) up to the ridge
        lo, hi = -OVERHANG * 1.41421, 8.0 * 1.41421
        z0, z1 = (oz + lo, oz + hi) if angle < 0 else (oz - hi, oz - lo)
        els.append(elem([0, EAVE45 - 0.8, z0], [16, EAVE45 + 0.8, z1], {
            "up": plane, "down": plane,
            "north": edge, "south": edge,
            "east": dict(end, cullface="east"), "west": dict(end, cullface="west"),
        }, rotation={"origin": [8, EAVE45, oz], "axis": "x", "angle": angle}))
        # eave board under the overhang tip (rotated with its plane; inset
        # 0.05 off the tip and kept under the sheet's top plane, so it shares
        # no face plane with the sheet it hangs from)
        b0, b1 = ((oz + lo + 0.05, oz + lo + 1.65) if angle < 0
                  else (oz - lo - 1.65, oz - lo - 0.05))
        els.append(elem([0, EAVE45 - 2.0, b0], [16, EAVE45 + 0.6, b1], {
            "down": edge, "north": edge, "south": edge,
        }, rotation={"origin": [8, EAVE45, oz], "axis": "x", "angle": angle}))
    ridge = f("roof", [0, 8.5, 16, 9.5])
    els.append(elem([0, RIDGE45 - 0.8, 6.8], [16, RIDGE45 + 0.8, 9.2],
                    {"north": ridge, "south": ridge, "up": ridge, "down": ridge}))
    return els + _roof_tie()


def canopy_gable_slope_elements():
    """A WING: the 22.5° stretch of the roof that rises from its open eave
    (authored SOUTH, overhanging past z=16) to the shared boundary at the
    connected side (authored NORTH). rescale keeps the sloped sheet's
    footprint exactly one block, so it tiles along the run."""
    plane, edge, end = _sheet_faces()
    mid = (EDGE22 + EAVE22) / 2.0            # sheet centre over z=8
    rot = {"origin": [8, mid, 8], "axis": "x", "angle": 22.5, "rescale": True}
    els = [elem([0, mid - 0.85, 0], [16, mid + 0.85, 16 + OVERHANG], {
        "up": plane, "down": plane,
        "north": edge, "south": edge,
        "east": dict(end, cullface="east"), "west": dict(end, cullface="west"),
    }, rotation=dict(rot))]
    els.append(elem([0, mid - 2.0, 16 + OVERHANG - 1.65],
                    [16, mid + 0.6, 16 + OVERHANG - 0.05],
                    {"down": edge, "north": edge, "south": edge},
                    rotation=dict(rot)))
    return els + _roof_tie() + _boundary_purlin(north=True)


def canopy_gable_crown_elements():
    """The CROWN: both crosswise sides connected, so the cell carries the
    apex — 22.5° up from 9.8 at each boundary to the ridge over its centre."""
    plane, edge, end = _sheet_faces()
    els = []
    for z0, z1, angle in ((0.0, 8.0, -22.5), (8.0, 16.0, 22.5)):
        els.append(elem([0, CROWN22 - 0.85, z0], [16, CROWN22 + 0.85, z1], {
            "up": plane, "down": plane,
            "north": edge, "south": edge,
            "east": dict(end, cullface="east"), "west": dict(end, cullface="west"),
        }, rotation={"origin": [8, CROWN22, 8], "axis": "x",
                     "angle": angle, "rescale": True}))
    ridge = f("roof", [0, 8.5, 16, 9.5])
    # ±1.0 (not ±0.85): the cap must swallow the two sheets' apex end faces,
    # which lie in one plane with the end truss's chord ends
    els.append(elem([0, CROWN22 - 1.0, 6.8], [16, CROWN22 + 1.0, 9.2],
                    {"north": ridge, "south": ridge, "up": ridge, "down": ridge}))
    return els + _roof_tie() + _boundary_purlin(north=True) + _boundary_purlin(north=False)


def _boundary_purlin(north):
    """Half of the purlin under a shared crosswise boundary (the ridge purlin
    where two wings meet, the slope-break purlin everywhere else). Each cell
    draws its own half and omits the face on the boundary plane, so the two
    halves butt without a coplanar pair."""
    p = f("body", [0.25, 5, 15.75, 6.4])
    z0, z1 = (0.0, 1.8) if north else (14.2, 16.0)
    faces = {"up": p, "down": p, "south" if north else "north": p}
    return [elem([0, EDGE22 - 2.5, z0], [16, EDGE22 - 0.9, z1], faces)]


def _truss(x0, chords, webs):
    """Open truss end plate: bottom chord + the given rafter chords + lattice
    web panels, all in a 1.2 px slice at x0 (the run end)."""
    chord = f("body", [0.25, 5, 15.75, 6.2])
    sides = ("north", "south", "east", "west", "up", "down")
    els = [elem([x0, 0.2, 0.4], [x0 + 1.2, 1.5, 15.6],
                {n: chord for n in sides})]
    for y0, y1, z0, z1, rot in chords:
        els.append(elem([x0, y0, z0], [x0 + 1.2, y1, z1],
                        {n: chord for n in sides}, rotation=rot))
    lat = f("lattice", [2, 2, 14, 10])
    for y0, y1, z0, z1 in webs:
        els.append(elem([x0 + 0.3, y0, z0], [x0 + 0.9, y1, z1],
                        {"east": lat, "west": lat}))
    return els


def gable_end_elements(x0=0.2):
    """Run end of a SINGLE-ROW canopy: the 45° open truss triangle."""
    chords = []
    for oz, angle in ((0.0, -45), (16.0, 45)):
        hi = 8.0 * 1.41421
        z0, z1 = (oz + 0.3, oz + hi) if angle < 0 else (oz - hi, oz - 0.3)
        chords.append((EAVE45 - 0.65, EAVE45 + 0.65, z0, z1,
                       {"origin": [x0 + 0.6, EAVE45, oz], "axis": "x", "angle": angle}))
    webs = [(1.4, 4.2, 2.2, 13.8), (4.2, 7.4, 5.0, 11.0)]
    return _truss(x0, chords, webs)


def gable_end_slope_elements(x0=0.2):
    """Run end of a WING: bottom chord + the 22.5° rafter chord, web stepped
    up under it (authored with the crown side to the NORTH, like the wing)."""
    mid = (EDGE22 + EAVE22) / 2.0
    rot = {"origin": [x0 + 0.6, mid, 8], "axis": "x", "angle": 22.5, "rescale": True}
    chords = [(mid - 0.65, mid + 0.65, 0.3, 15.7, rot)]
    webs = [(1.4, 6.2, 1.6, 7.6), (1.4, 3.6, 8.2, 14.2)]
    return _truss(x0, chords, webs)


def gable_end_crown_elements(x0=0.2):
    """Run end of a CROWN cell: the shallow 22.5° truss carrying the apex."""
    chords = []
    for z0, z1, angle in ((0.3, 8.0, -22.5), (8.0, 15.7, 22.5)):
        chords.append((CROWN22 - 0.65, CROWN22 + 0.65, z0, z1,
                       {"origin": [x0 + 0.6, CROWN22, 8], "axis": "x",
                        "angle": angle, "rescale": True}))
    webs = [(1.4, 8.0, 1.6, 14.4), (8.0, 11.4, 4.4, 11.6)]
    return _truss(x0, chords, webs)


def tex_board():
    """Matte black name board with a worn edge line."""
    rows = pk.canvas(16, 16, (18, 19, 21))
    pk.rect(rows, 0, 0, 16, 1, (34, 35, 38))
    pk.rect(rows, 0, 15, 16, 16, (10, 10, 12))
    return rows


def name_board_elements():
    """The black station-name board on two stubs (text comes from the BE
    renderer in game, like the named columns)."""
    plate = f("board", [0.5, 0.5, 15.5, 7.5])
    edge = f("board", [0.5, 4, 2, 7.5])
    els = [elem([1, 6, 7.4], [15, 13, 8.6], {
        "north": plate, "south": plate,
        "east": edge, "west": edge, "up": edge, "down": edge,
    })]
    for x0 in (2.5, 12.5):
        els.append(elem([x0, 0, 7.6], [x0 + 1.2, 6, 8.4],
                        {n: f("body", [13.6, 2, 14.4, 5])
                         for n in ("north", "south", "east", "west")}))
    return els


def platform_screen_blockstate(panel, post_left, post_right, rail, kick,
                               panel_base=None):
    """Merging run: panel always, LEFT post always (shared at each joint),
    RIGHT post only where the run ends — gate-wall rhythm. Screens also
    stack: the rail caps the top of a stack, the kick sits at its foot, and
    (classic screen only) `panel_base` is the solid panel every course BELOW
    the top draws, so a 2-high run is solid up to chest height and glazed
    above, the way the Marcy / Bay Pkwy screens are built."""
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        def ap(mdl):
            entry = {"model": f"{MOD}:block/{mdl}"}
            if rot:
                entry["y"] = rot
            return entry
        if panel_base:
            parts.append({"when": {"facing": facing, "up": "false"}, "apply": ap(panel)})
            parts.append({"when": {"facing": facing, "up": "true"},
                          "apply": ap(panel_base)})
        else:
            parts.append({"when": {"facing": facing}, "apply": ap(panel)})
        parts.append({"when": {"facing": facing}, "apply": ap(post_left)})
        parts.append({"when": {"facing": facing, "right": "false"}, "apply": ap(post_right)})
        parts.append({"when": {"facing": facing, "up": "false"}, "apply": ap(rail)})
        parts.append({"when": {"facing": facing, "down": "false"}, "apply": ap(kick)})
    return {"multipart": parts}


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


SIDES = ["north", "east", "south", "west"]


def turned(side, rot):
    """The world side an AUTHORED side lands on after a blockstate y rotation
    (y turns the model clockwise seen from above: north→east→south→west)."""
    return SIDES[(SIDES.index(side) + rot // 90) % 4]


def canopy_gable_blockstate():
    """One roof over any platform. Authored frame: run along x (crosswise =
    north/south, run ends = west/east); axis=z is the same thing turned 90°.

    Per cell, the crosswise connection COUNT picks the profile — none = the
    single-row 45° gable, one = a 22.5° wing rising to the shared boundary,
    two = the crown carrying the apex — and the matching open truss closes
    each open run end. Every profile hands the neighbour the same boundary
    height, so wings/crowns/gables always meet flush."""
    parts = []
    for axis, base in (("x", 0), ("z", 90)):
        def mdl(name, rot):
            entry = {"model": f"{MOD}:block/{name}"}
            if rot % 360:
                entry["y"] = rot % 360
            return entry

        cw_a, cw_b = turned("north", base), turned("south", base)
        ends = ((turned("west", base), base), (turned("east", base), base + 180))
        # --- roof profiles
        parts.append({"when": {"axis": axis, cw_a: "false", cw_b: "false"},
                      "apply": mdl("el_canopy_gable_roof", base)})
        parts.append({"when": {"axis": axis, cw_a: "true", cw_b: "false"},
                      "apply": mdl("el_canopy_gable_slope", base)})
        parts.append({"when": {"axis": axis, cw_a: "false", cw_b: "true"},
                      "apply": mdl("el_canopy_gable_slope", base + 180)})
        parts.append({"when": {"axis": axis, cw_a: "true", cw_b: "true"},
                      "apply": mdl("el_canopy_gable_crown", base)})
        # --- open run ends: the truss that matches this cell's profile
        for end, erot in ends:
            parts.append({"when": {"axis": axis, end: "false",
                                   cw_a: "false", cw_b: "false"},
                          "apply": mdl("el_canopy_gable_end", erot)})
            parts.append({"when": {"axis": axis, end: "false",
                                   cw_a: "true", cw_b: "true"},
                          "apply": mdl("el_canopy_gable_end_crown", erot)})
        # a wing's truss is not z-symmetric, so the two run ends need the two
        # authored x slices; the 180° turn then covers the mirrored wing.
        w_end, e_end = ends[0][0], ends[1][0]
        for end, cw, name, rot in (
                (w_end, cw_a, "el_canopy_gable_end_slope", base),
                (e_end, cw_b, "el_canopy_gable_end_slope", base + 180),
                (e_end, cw_a, "el_canopy_gable_end_slope_east", base),
                (w_end, cw_b, "el_canopy_gable_end_slope_east", base + 180)):
            other = cw_b if cw == cw_a else cw_a
            parts.append({"when": {"axis": axis, end: "false",
                                   cw: "true", other: "false"},
                          "apply": mdl(name, rot)})
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
    pm("el_windscreen_panel", windscreen_head_elements())
    pm("el_windscreen_panel_base", windscreen_base_elements())
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
    pm("el_canopy_gable_slope", canopy_gable_slope_elements())
    pm("el_canopy_gable_crown", canopy_gable_crown_elements())
    pm("el_canopy_gable_end", gable_end_elements())
    pm("el_canopy_gable_end_crown", gable_end_crown_elements())
    pm("el_canopy_gable_end_slope", gable_end_slope_elements())
    pm("el_canopy_gable_end_slope_east", gable_end_slope_elements(14.6))
    pm("el_name_board_model", name_board_elements())

    # blockstates
    for block, panel, silver, base in (
            ("el_windscreen", "el_windscreen_panel", False, "el_windscreen_panel_base"),
            ("el_windscreen_corrugated", "el_windscreen_corrugated_panel", False, None),
            ("el_windscreen_glass", "el_windscreen_glass_panel", True, None),
            ("el_windscreen_mesh", "el_windscreen_mesh_panel", True, None)):
        sv = "_silver" if silver else ""
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"),
             platform_screen_blockstate(panel, f"el_screen_post_left{sv}",
                                        f"el_screen_post_right{sv}",
                                        f"el_screen_rail{sv}", f"el_screen_kick{sv}",
                                        base))
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
         canopy_gable_blockstate())
    g.wj(os.path.join(assets_root, "blockstates", "el_name_board.json"),
         platform_simple_blockstate("el_name_board_model"))

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
    # railings are ElScreenBlocks too — they carry left/right/up/down even
    # though their blockstate keys on facing alone (a post every block IS the
    # look; there is nothing to share at a joint)
    **{b: {"facing": {"north", "south", "east", "west"},
           "left": {"true", "false"}, "right": {"true", "false"},
           "up": {"true", "false"}, "down": {"true", "false"}}
       for b in BLOCKS[20:22]},
    **{b: {"facing": {"north", "south", "east", "west"},
           "up": {"true", "false"}, "down": {"true", "false"}}
       for b in ("el_canopy_post", "el_canopy_post_silver")},
    **{b: {"axis": {"x", "z"},
           "north": {"true", "false"}, "south": {"true", "false"},
           "east": {"true", "false"}, "west": {"true", "false"}}
       for b in ("el_canopy_flat", "el_canopy_flat_silver", "el_canopy_gable")},
    "el_name_board": {"facing": {"north", "south", "east", "west"}},
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
