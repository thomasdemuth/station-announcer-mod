#!/usr/bin/env python3
"""El kit v2 - STRUCTURE (street -> deck). Called from gen_el2_assets.main();
never hand-edit the output.

The stack, street up (the level rule was probed on the rig: an MTR rail
node cell and the platform floor beside it share one y, so the deck the
rails lie on is ONE BLOCK BELOW the platform floor):

  el_street_column[_lattice]  10 px built-up box column (4 riveted corner
                              angles + web plates / X-laced see-through
                              panels); concrete pedestal + base plate at
                              the stack bottom, flared bearing cap at the top
  el_girder_plate             1-block riveted plate girder (AXIS): flanges
                              8 px, recessed web, stiffener angles; over a
                              column it grows the KNEE BRACES - and the two
                              girder cells beside it draw the braces' outer
                              halves, so a brace spans a block and a half
  el_track_deck               open tie deck (AXIS = travel): two I-beam
                              stringers, ties on top, daylight between; the
                              rail node goes on its top face
  el_plate_deck               closed riveted steel pan on the same
                              stringers: the platform framing - the concrete
                              floor sits on it, its underside is what the
                              street sees

Authored for AXIS = x (a run spans x 0..16); the blockstate turns axis z
by y=90 (model +x -> world +z, so "neg" = west for x and north for z).
"""

import os
import random

import gen_el2_assets as K
from gen_el2_stairs import wbox, icon_canvas, irect, iline, ipara, GREEN_I, GREEN_D, PLATE_I, CLEAR

MOD = K.MOD
G = "#green"
S = "#steel"

# column footprint
CX0, CX1 = 3.0, 13.0          # 10 px = 0.62 m built-up column
ANG = 2.0                     # corner angle leg
# girder section
FL_Z0, FL_Z1 = 4.0, 12.0      # flange 8 px = 0.5 m
FL_T = 2.0
WEB_Z0, WEB_Z1 = 7.0, 9.0
RIB_X = (0.1, 7.4, 14.7)      # stiffener angles: both block ends (pair into a splice) + mid
RIB_W = 1.2
RIB_Z0, RIB_Z1 = 5.2, 10.8
# knee brace: centre line from the column face (x -3, y -12) to the girder
# underside one block out (x 9, y 0) - a 45-degree gusset, split at the cell
# boundary between the braced cell (x 13..16 of it) and its neighbour (x 0..9)
BR_DROP = 12.0
BR_T = 2.4                    # diagonal bar thickness
BR_Z0, BR_Z1 = 6.0, 10.0
STEP = 1.5
# deck
STR_Z = ((2.5, 5.5), (10.5, 13.5))   # stringer flanges (3 px), one under each rail
STR_FL = 1.2
STR_BOT = 6.0


# ---------------------------------------------------------------------------
# textures
# ---------------------------------------------------------------------------

def tex_steel():
    """Riveted green steel, 32 px: brushed base, a HORIZONTAL rivet row at
    texel rows 2..3 and 28..29 (uv v 1..1.5 / 14..14.5) and a VERTICAL rivet
    ladder at columns 2..3 and 28..29 (u 1..1.5 / 14..14.5), rivets on a
    4-texel pitch. Faces pick a window that puts a row where they want it;
    the clean band is rows/cols 5..26."""
    rng = random.Random("steel")
    rows = K.canvas(32, K.GREEN)
    for y in range(32):
        for x in range(32):
            K.put(rows, x, y, K.shade(K.GREEN, rng.uniform(-6, 6) + 3 * (0.5 - ((x * 7 + y * 3) % 5) / 5)))

    def rivet(x, y):
        K.put(rows, x, y, K.shade(K.GREEN, 30))
        K.put(rows, x + 1, y, K.shade(K.GREEN, 12))
        K.put(rows, x, y + 1, K.shade(K.GREEN, -4))
        K.put(rows, x + 1, y + 1, K.shade(K.GREEN, -26))

    for i in range(0, 32, 4):
        rivet(i + 1, 2)
        rivet(i + 1, 28)
        rivet(2, i + 1)
        rivet(28, i + 1)
    return rows


def tex_pan():
    """Riveted plate pan (the platform deck's underside): green sheet with a
    rivet grid on an 8-texel pitch and faint seam lines every 16."""
    rng = random.Random("pan")
    rows = K.canvas(32, K.GREEN)
    for y in range(32):
        for x in range(32):
            K.put(rows, x, y, K.shade(K.GREEN, rng.uniform(-5, 5)))
    for x in range(32):
        K.put(rows, x, 0, K.shade(K.GREEN, -18))
        K.put(rows, x, 16, K.shade(K.GREEN, -18))
        K.put(rows, 0, x, K.shade(K.GREEN, -18))
        K.put(rows, 16, x, K.shade(K.GREEN, -18))
    for y in range(3, 32, 8):
        for x in range(3, 32, 8):
            K.put(rows, x, y, K.shade(K.GREEN, 28))
            K.put(rows, x + 1, y, K.shade(K.GREEN, 10))
            K.put(rows, x, y + 1, K.shade(K.GREEN, -6))
            K.put(rows, x + 1, y + 1, K.shade(K.GREEN, -24))
    return rows


def tex_tie():
    """Creosoted timber tie, 32 px: near-black brown with grain along x."""
    rng = random.Random("tie")
    base = (58, 46, 36)
    rows = K.canvas(32, base)
    for y in range(32):
        streak = rng.uniform(-10, 10)
        for x in range(32):
            K.put(rows, x, y, K.shade(base, streak + rng.uniform(-6, 6) + (6 if (x * 3 + y) % 11 == 0 else 0)))
    return rows


def tex_concrete():
    rng = random.Random("pedestal")
    base = (146, 142, 134)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            K.put(rows, x, y, K.shade(base, rng.uniform(-9, 9)))
    return rows


def tex_lace():
    """X lacing for the lattice column, CUTOUT. The panel between two corner
    angles is 6 px wide and one block tall, windowed at uv [0,0,6,16] =
    texels 0..12 x 0..32: one X per 16 rows, bars 2 texels, a rivet dot
    where the bars meet the angles."""
    rows = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        t = (y % 16) / 15.0                 # 0..1 down one X
        for xc in (t * 11, (1 - t) * 11):
            for dx in (0, 1):
                x = int(round(xc)) + dx
                if 0 <= x < 12:
                    K.put(rows, x, y, K.shade(K.GREEN, 8 if dx == 0 else -14))
    for y in (0, 15, 16, 31):
        for x in (0, 1, 10, 11):
            K.put(rows, x, y, K.shade(K.GREEN, 26))
    return rows


# ---------------------------------------------------------------------------
# column
# ---------------------------------------------------------------------------

def column_angles():
    """Four proud corner angles, rivet ladders on both outward faces."""
    els = []
    for x0 in (CX0, CX1 - ANG):
        for z0 in (CX0, CX1 - ANG):
            out_x = "west" if x0 < 8 else "east"
            out_z = "north" if z0 < 8 else "south"
            els.append(wbox(x0, 0, z0, x0 + ANG, 16, z0 + ANG, S, faces=[out_x, out_z],
                            uv={out_x: [0.5, 0, 2.5, 16], out_z: [0.5, 0, 2.5, 16]}))
    return els


def column_core():
    """Web plates recessed 1 px behind the angle faces + a batten plate at mid height."""
    els = [wbox(CX0 + 1, 0, CX0 + 1, CX1 - 1, 16, CX1 - 1, S, faces=["north", "south", "east", "west"],
                uv={f: [4, 0, 12, 16] for f in ("north", "south", "east", "west")})]
    for f, (a, b) in (("north", (CX0 + 0.5, CX0 + 1)), ("south", (CX1 - 1, CX1 - 0.5)),
                      ("west", (CX0 + 0.5, CX0 + 1)), ("east", (CX1 - 1, CX1 - 0.5))):
        if f in ("north", "south"):
            els.append(wbox(CX0 + ANG, 5, a, CX1 - ANG, 11, b, S, faces=[f, "up", "down"],
                            uv={f: [4, 0.5, 10, 6.5], "up": [4, 0, 10, 0.5], "down": [4, 0, 10, 0.5]}))
        else:
            els.append(wbox(a, 5, CX0 + ANG, b, 11, CX1 - ANG, S, faces=[f, "up", "down"],
                            uv={f: [4, 0.5, 10, 6.5], "up": [0, 4, 0.5, 10], "down": [0, 4, 0.5, 10]}))
    return els


def column_lacing():
    """Four see-through X-laced panels just inside the angle faces."""
    els = []
    a, b = CX0 + ANG, CX1 - ANG
    for z0 in (CX0 + 0.4, CX1 - 0.8):
        els.append(wbox(a, 0, z0, b, 16, z0 + 0.4, "#lace", faces=["north", "south"],
                        uv={"north": [0, 0, 6, 16], "south": [0, 0, 6, 16]}))
    for x0 in (CX0 + 0.4, CX1 - 0.8):
        els.append(wbox(x0, 0, a, x0 + 0.4, 16, b, "#lace", faces=["east", "west"],
                        uv={"east": [0, 0, 6, 16], "west": [0, 0, 6, 16]}))
    return els


def plate(half, y0, y1, tex=S, riveted=True, faces=None):
    """A square plate centred on the column axis; side faces windowed on the
    steel's rivet row when riveted."""
    w = 2 * half
    v0 = 0.5 if riveted else 5.0
    side = [0.5, v0, 0.5 + min(15, w), v0 + (y1 - y0)]
    uv = {"north": side, "south": side, "east": side, "west": side,
          "up": [8 - half, 8 - half, 8 + half, 8 + half], "down": [8 - half, 8 - half, 8 + half, 8 + half]}
    return wbox(8 - half, y0, 8 - half, 8 + half, y1, 8 + half, tex, faces=faces, uv=uv)


def column_foot():
    """Street base: concrete pedestal, bolted base plate, shoe collar."""
    return [plate(7, 0, 3, "#concrete", riveted=False, faces=["north", "south", "east", "west", "up"]),
            plate(6.5, 3, 4.2, faces=["north", "south", "east", "west", "up"]),
            plate(5.6, 4.2, 5.6, faces=["north", "south", "east", "west", "up"])]


def column_cap():
    """Bearing cap: collar, bolster, wide bearing plate 0.1 short of the
    girder's bottom flange plane (y 16 = the girder cell's y 0)."""
    return [plate(5.6, 10.4, 11.8, faces=["north", "south", "east", "west", "down"]),
            plate(6.5, 11.8, 13.4, faces=["north", "south", "east", "west", "down"]),
            plate(7.5, 13.4, 15.9, faces=["north", "south", "east", "west", "down", "up"])]


# ---------------------------------------------------------------------------
# girder
# ---------------------------------------------------------------------------

# diagonal runs: a cell on a 45-degree run is 16*sqrt2 = 22.63 px long along
# the run, so its members are authored from -3.31 to 19.31 and turned -45 about
# y at the cell centre (-45 in Minecraft maps model +x to world south-east = the
# "xz" run - the offline renderer has the opposite sign; "zx" is the
# same model under the blockstate's y=90). Along-run neighbours then meet end
# to end and crosswise neighbours (22.63 apart) abut, so a diagonal ribbon
# tiles into a continuous rotated grid. Elements may not carry two rotations,
# so the diagonal knee brace is a stepped plate without the 45-degree bar.
DIAG = 16 * 2 ** 0.5
DX0, DX1 = 8 - DIAG / 2, 8 + DIAG / 2


def diag(el):
    """Turn a world-frame element onto the south-east diagonal (-45 about y at the cell centre)."""
    e = dict(el)
    e["rotation"] = {"origin": [8, 8, 8], "axis": "y", "angle": -45, "rescale": False}
    return e


def girder_body(x0=0.0, x1=16.0, ribs=RIB_X):
    els = []
    L = x1 - x0
    for y0, v in ((0.0, 13.5), (16 - FL_T, 0.5)):
        # flange: side faces carry the steel's rivet row
        els.append(wbox(x0, y0, FL_Z0, x1, y0 + FL_T, FL_Z1, S,
                        uv={"north": [0, v, min(16, L), v + FL_T], "south": [0, v, min(16, L), v + FL_T],
                            "up": [0, 4, min(16, L), 12], "down": [0, 4, min(16, L), 12],
                            "east": [4, 5, 12, 5 + FL_T], "west": [4, 5, 12, 5 + FL_T]}))
    # end faces close a run end; at a joint they are buried in the next cell's member
    els.append(wbox(x0, FL_T, WEB_Z0, x1, 16 - FL_T, WEB_Z1, S, faces=["north", "south", "east", "west"],
                    uv={"north": [0, 2, min(16, L), 14], "south": [0, 2, min(16, L), 14],
                        "east": [4, 2, 6, 14], "west": [4, 2, 6, 14]}))
    for rx in ribs:
        els.append(wbox(rx, FL_T, RIB_Z0, rx + RIB_W, 16 - FL_T, RIB_Z1, S,
                        faces=["north", "south", "east", "west"],
                        uv={"north": [0.5, 2, 1.7, 14], "south": [0.5, 2, 1.7, 14],
                            "east": [4, 2, 4 + (RIB_Z1 - RIB_Z0), 14], "west": [4, 2, 4 + (RIB_Z1 - RIB_Z0), 14]}))
    return els


def brace_bar(x0, y0, length):
    """45-degree diagonal bar rising toward +x from (x0, y0)."""
    return wbox(x0, y0 - BR_T / 2, BR_Z0, x0 + length, y0 + BR_T / 2, BR_Z1, S,
                faces=["north", "south", "up", "down"],
                uv={"north": [0, 0.5, min(16, length), 0.5 + BR_T], "south": [0, 0.5, min(16, length), 0.5 + BR_T],
                    "up": [0, 4, min(16, length), 8], "down": [0, 4, min(16, length), 8]}) | {
        "rotation": {"origin": [x0, y0, 8], "axis": "z", "angle": 45, "rescale": False}}


def brace_steps(x0, x1, line_at, step=STEP, top=0.4):
    """Gusset web between the bar and the girder underside: steps whose
    bottoms ride 0.6 above the diagonal (the bar buries the sawtooth)."""
    els = []
    x = x0
    while x < x1 - 0.05:
        xe = min(x1, x + step)
        yb = line_at(x) + 0.6
        els.append(wbox(x, yb, WEB_Z0, xe, top, WEB_Z1, S, faces=["north", "south", "down", "east", "west"],
                        uv={"north": [4, 2, 4 + (xe - x), 2 + min(12, top - yb)],
                            "south": [4, 2, 4 + (xe - x), 2 + min(12, top - yb)],
                            "east": [4, 2, 6, 2 + min(12, top - yb)], "west": [4, 2, 6, 2 + min(12, top - yb)],
                            "down": [4, 4, 4 + (xe - x), 6]}))
        x = xe
    return els


def brace_stair(x0, x1, line_at):
    """Diagonal-run stand-in for the 45-degree bar (an element cannot turn
    about two axes): a staircase of 1.5 px boxes riding the line, wider in
    z than the web so it reads as the bar's flange."""
    els = []
    x = x0
    while x < x1 - 0.05:
        xe = min(x1, x + STEP)
        yc = line_at(x + STEP / 2)
        els.append(wbox(x, yc - BR_T / 2 - 0.4, BR_Z0, xe, yc + BR_T / 2 + 0.4, BR_Z1, S,
                        uv={"north": [0, 0.5, xe - x, 0.5 + BR_T + 0.8], "south": [0, 0.5, xe - x, 0.5 + BR_T + 0.8],
                            "up": [0, 4, xe - x, 8], "down": [0, 4, xe - x, 8],
                            "east": [4, 4, 8, 4 + BR_T + 0.8], "west": [4, 4, 8, 4 + BR_T + 0.8]}))
        x = xe
    return els


def brace_here(root=13.0, cell_end=16.0, bar=True):
    """The braced cell's own part: the gusset roots on both column faces (the
    column below fills x 3..13 on a straight run, 0.93..15.07 measured along a
    diagonal), reaching 12 px down. Line: y = x - root - 12."""
    line = lambda x: x - root - BR_DROP
    reach = cell_end - root
    if bar:
        els = [brace_bar(root, -BR_DROP, reach * 2 ** 0.5)]
    else:
        els = brace_stair(root, cell_end, line)
    els += brace_steps(root, cell_end, line)
    mirror = [K.mirror_x(e) for e in els] if cell_end == 16.0 else [mirror_about(e, 8.0) for e in els]
    return els + mirror


def brace_neg(cell_start=0.0, root=13.0, cell_end=16.0, bar=True):
    """The cell beyond a braced cell along the run: the brace continues from
    the shared boundary up to the girder underside 12 px past the root."""
    # in our frame the root sits at root - (cell_end - cell_start) (the previous cell)
    r = root - (cell_end - cell_start)
    line = lambda x: x - r - BR_DROP
    x_top = r + BR_DROP
    if bar:
        els = [brace_bar(cell_start, line(cell_start), (x_top - cell_start) * 2 ** 0.5)]
    else:
        els = brace_stair(cell_start, x_top, line)
    els += brace_steps(cell_start, x_top, line)
    return els


def mirror_about(el, cx):
    """mirror_x about an arbitrary x (the diagonal cell is centred on 8 but spans -3.31..19.31)."""
    e = K.mirror_x(el)
    d = 2 * cx - 16
    e["from"][0] += d
    e["to"][0] += d
    if "rotation" in e:
        e["rotation"]["origin"][0] += d
    return e


# ---------------------------------------------------------------------------
# decks
# ---------------------------------------------------------------------------

def stringers(top, cap_up=True, x0=0.0, x1=16.0):
    """Two deep riveted I-beam stringers along x, y 0.1..top, sitting straight
    on the cross girder's top flange (bearing blocks at every joint were
    tried first and read as a forest of stubs under the deck), plus a
    mid-span diaphragm channel between them."""
    els = []
    y_lo = 0.1
    L = x1 - x0
    for z0, z1 in STR_Z:
        for y0 in (y_lo, top - STR_FL):
            faces = ["north", "south", "up", "down", "east", "west"]
            if y0 > y_lo and not cap_up:
                faces.remove("up")
            els.append(wbox(x0, y0, z0, x1, y0 + STR_FL, z1, S, faces=faces,
                            uv={"north": [0, 0.6, min(16, L), 0.6 + STR_FL], "south": [0, 0.6, min(16, L), 0.6 + STR_FL],
                                "up": [0, 4, min(16, L), 7], "down": [0, 4, min(16, L), 7],
                                "east": [4, 5, 7, 5 + STR_FL], "west": [4, 5, 7, 5 + STR_FL]}))
        zm = (z0 + z1) / 2
        h = top - y_lo - 2 * STR_FL
        els.append(wbox(x0, y_lo + STR_FL, zm - 0.6, x1, top - STR_FL, zm + 0.6, S,
                        faces=["north", "south", "east", "west"],
                        uv={"north": [0, 2, min(16, L), 2 + min(12, h)], "south": [0, 2, min(16, L), 2 + min(12, h)],
                            "east": [4, 2, 5.2, 2 + min(12, h)], "west": [4, 2, 5.2, 2 + min(12, h)]}))
        # stiffener angles at the block ends (pair into a splice rib across a joint)
        for rx in (x0 + 0.1, x1 - 1.3):
            els.append(wbox(rx, y_lo + STR_FL, zm - 1.1, rx + RIB_W, top - STR_FL, zm + 1.1, S,
                            faces=["north", "south", "east", "west"],
                            uv={"north": [0.5, 2, 1.7, 2 + min(12, h)], "south": [0.5, 2, 1.7, 2 + min(12, h)],
                                "east": [4, 2, 6.2, 2 + min(12, h)], "west": [4, 2, 6.2, 2 + min(12, h)]}))
    # diaphragm reaches INTO both webs (a stop at the flange edge left a slit beside each web)
    zw0 = (STR_Z[0][0] + STR_Z[0][1]) / 2 + 0.6
    zw1 = (STR_Z[1][0] + STR_Z[1][1]) / 2 - 0.6
    els.append(wbox(7, y_lo + 3, zw0, 9, top - 3, zw1, S, faces=["east", "west", "up", "down"],
                    uv={"east": [4, 4, 4 + (zw1 - zw0), 4 + min(12, top - y_lo - 6)],
                        "west": [4, 4, 4 + (zw1 - zw0), 4 + min(12, top - y_lo - 6)],
                        "up": [4, 4, 6, 4 + (zw1 - zw0)], "down": [4, 4, 6, 4 + (zw1 - zw0)]}))
    return els


def ties(x0=0.0, x1=16.0, z0=0.0, z1=16.0, count=4):
    """Timber ties across the full cell (they continue into the next cell
    over), 2 px tall on the stringers' top flanges, `count` per cell."""
    els = []
    pitch = (x1 - x0) / count
    for k in range(count):
        tx = x0 + pitch * (k + 0.5) - 1
        w = min(16, z1 - z0)
        els.append(wbox(tx, 14, z0, tx + 2, 16, z1, "#tie",
                        uv={"up": [0, 0, w, 2], "down": [0, 0, w, 2], "east": [0, 6, w, 8], "west": [0, 6, w, 8],
                            "north": [0, 6, 2, 8], "south": [0, 6, 2, 8]}))
        # rotate the long faces so the grain runs along the tie
        for f in ("up", "down", "east", "west"):
            els[-1]["faces"][f]["rotation"] = 90
    return els


def pan(x0=0.0, x1=16.0, z0=0.0, z1=16.0, cull=True):
    """Closed riveted deck pan: diamond plate on top (concrete goes over it),
    rivet grid below, cullface sides so runs never show internal faces."""
    w, d = min(16, x1 - x0), min(16, z1 - z0)
    el = wbox(x0, 13, z0, x1, 16, z1, {"up": "#plate", "down": "#pan", "*": S},
              uv={"up": [0, 0, w, d], "down": [0, 0, w, d], "north": [0, 5, w, 8], "south": [0, 5, w, 8],
                  "east": [0, 5, d, 8], "west": [0, 5, d, 8]})
    if cull:
        for f in ("north", "south", "east", "west"):
            el["faces"][f]["cullface"] = f
    return [el]


# ---------------------------------------------------------------------------
# icons
# ---------------------------------------------------------------------------

def icon_column(lattice=False):
    r = icon_canvas()
    irect(r, 2, 27, 30, 31, (146, 142, 134, 255))
    irect(r, 5, 24, 27, 27, GREEN_I)
    irect(r, 4, 1, 28, 4, GREEN_I)
    irect(r, 7, 4, 25, 6, GREEN_D)
    irect(r, 10, 6, 22, 24, GREEN_I if not lattice else CLEAR)
    irect(r, 10, 6, 13, 24, GREEN_D)
    irect(r, 19, 6, 22, 24, GREEN_D)
    if lattice:
        for y0 in (6, 12, 18):
            iline(r, 13, y0, 19, y0 + 6, GREEN_I)
            iline(r, 19, y0, 13, y0 + 6, GREEN_I)
    else:
        for y in range(8, 24, 4):
            irect(r, 11, y, 12, y + 1, (120, 170, 150, 255))
            irect(r, 20, y, 21, y + 1, (120, 170, 150, 255))
    return r


def icon_girder():
    r = icon_canvas()
    irect(r, 1, 6, 31, 9, GREEN_I)
    irect(r, 1, 23, 31, 26, GREEN_I)
    irect(r, 1, 9, 31, 23, GREEN_D)
    for x in (3, 15, 27):
        irect(r, x, 9, x + 2, 23, GREEN_I)
    for x in range(3, 30, 4):
        irect(r, x, 7, x + 1, 8, (120, 170, 150, 255))
        irect(r, x, 24, x + 1, 25, (120, 170, 150, 255))
    return r


def icon_track_deck():
    r = icon_canvas()
    irect(r, 1, 12, 31, 15, GREEN_I)
    irect(r, 1, 17, 31, 20, GREEN_I)
    for x in range(2, 31, 6):
        irect(r, x, 8, x + 3, 24, (58, 46, 36, 255))
    return r


def icon_plate_deck():
    r = icon_canvas()
    irect(r, 1, 8, 31, 13, PLATE_I)
    for y in range(8, 13, 3):
        for x in range(2, 30, 4):
            irect(r, x + ((y // 3) % 2), y + 1, x + 2 + ((y // 3) % 2), y + 2, (196, 199, 201, 255))
    irect(r, 1, 13, 31, 16, GREEN_D)
    irect(r, 4, 16, 8, 24, GREEN_I)
    irect(r, 24, 16, 28, 24, GREEN_I)
    return r


def icon_structure_creator():
    r = icon_canvas()
    irect(r, 2, 6, 30, 10, GREEN_I)
    irect(r, 6, 10, 10, 30, GREEN_I)
    irect(r, 22, 10, 26, 30, GREEN_I)
    iline(r, 10, 14, 14, 10, GREEN_D, 2)
    iline(r, 21, 10, 17, 14, GREEN_D, 2)
    for x in range(3, 30, 4):
        irect(r, x, 3, x + 2, 6, (58, 46, 36, 255))
    irect(r, 26, 24, 31, 29, (255, 214, 60, 255))
    irect(r, 27, 25, 30, 28, (120, 90, 20, 255))
    return r


# ---------------------------------------------------------------------------
# assets
# ---------------------------------------------------------------------------

COLUMN_PROPS = {"facing": {"north", "east", "south", "west"}, "up": {"true", "false"}, "down": {"true", "false"}}
GIRDER_PROPS = {"axis": {"x", "z", "xz", "zx"}, "braced": {"true", "false"}, "brace_neg": {"true", "false"},
                "brace_pos": {"true", "false"}}
DECK_PROPS = {"axis": {"x", "z", "xz", "zx"}}
VERIFY = [("el_street_column", COLUMN_PROPS), ("el_street_column_lattice", COLUMN_PROPS),
          ("el_girder_plate", GIRDER_PROPS), ("el_track_deck", DECK_PROPS), ("el_plate_deck", DECK_PROPS)]


def loot(name):
    K.write_json(os.path.join(K.DATA, "loot_tables/blocks", name + ".json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


def icon(name, rows):
    import pngtool
    from gen_el2_stairs import ICON_DIR
    os.makedirs(ICON_DIR, exist_ok=True)
    pngtool.write_png(os.path.join(ICON_DIR, name + ".png"), rows)
    K.write_json(os.path.join(K.ITEM_MODELS, name + ".json"),
                 {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}})


def build():
    K.write_png("el2_steel", tex_steel())
    K.write_png("el2_pan", tex_pan())
    K.write_png("el2_tie", tex_tie())
    K.write_png("el2_concrete", tex_concrete())
    K.write_png("el2_lace", tex_lace())
    tex = {"green": "el2_green", "steel": "el2_steel", "concrete": "el2_concrete", "lace": "el2_lace",
           "tie": "el2_tie", "pan": "el2_pan", "plate": "el2_plate"}

    # columns
    K.model("el_street_column_shaft", column_angles() + column_core(), tex)
    K.model("el_street_column_lattice_shaft", column_angles() + column_lacing(), tex)
    K.model("el_street_column_foot", column_foot(), tex)
    K.model("el_street_column_cap", column_cap(), tex)
    for name, shaft in (("el_street_column", "el_street_column_shaft"),
                        ("el_street_column_lattice", "el_street_column_lattice_shaft")):
        parts = [{"apply": {"model": f"{MOD}:block/{shaft}"}},
                 {"when": {"down": "false"}, "apply": {"model": f"{MOD}:block/el_street_column_foot"}},
                 {"when": {"up": "false"}, "apply": {"model": f"{MOD}:block/el_street_column_cap"}}]
        K.write_json(os.path.join(K.BLOCKSTATES, name + ".json"), {"multipart": parts})
        loot(name)
    icon("el_street_column", icon_column())
    icon("el_street_column_lattice", icon_column(True))
    K.write_json(os.path.join(K.DATA, "recipes/el_street_column.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_block"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["I", "D", "I"], "result": {"item": f"{MOD}:el_street_column", "count": 6}})
    K.write_json(os.path.join(K.DATA, "recipes/el_street_column_lattice.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_bars"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["I", "D", "I"], "result": {"item": f"{MOD}:el_street_column_lattice", "count": 6}})

    # girder
    K.model("el_girder_plate_body", girder_body(), tex)
    K.model("el_girder_brace_here", brace_here(), tex)
    K.model("el_girder_brace_neg", brace_neg(), tex)
    K.model("el_girder_brace_pos", [K.mirror_x(e) for e in brace_neg()], tex)
    # diagonal run: 22.63 px members, column root measured along the diagonal
    DROOT = 8 + 5 * 2 ** 0.5
    ribs_d = (DX0 + 0.1, 7.4, DX1 - 1.3)
    K.model("el_girder_plate_body_diag", [diag(e) for e in girder_body(DX0, DX1, ribs_d)], tex)
    K.model("el_girder_brace_here_diag", [diag(e) for e in brace_here(DROOT, DX1, bar=False)], tex)
    neg_d = brace_neg(DX0, DROOT, DX1, bar=False)
    K.model("el_girder_brace_neg_diag", [diag(e) for e in neg_d], tex)
    K.model("el_girder_brace_pos_diag", [diag(mirror_about(e, 8.0)) for e in neg_d], tex)
    parts = []
    for axis, rot, suf in (("x", 0, ""), ("z", 90, ""), ("xz", 0, "_diag"), ("zx", 90, "_diag")):
        def ap(m):
            a = {"model": f"{MOD}:block/{m}{suf}"}
            if rot:
                a["y"] = rot
            return a
        parts.append({"when": {"axis": axis}, "apply": ap("el_girder_plate_body")})
        parts.append({"when": {"axis": axis, "braced": "true"}, "apply": ap("el_girder_brace_here")})
        parts.append({"when": {"axis": axis, "brace_neg": "true"}, "apply": ap("el_girder_brace_neg")})
        parts.append({"when": {"axis": axis, "brace_pos": "true"}, "apply": ap("el_girder_brace_pos")})
    K.write_json(os.path.join(K.BLOCKSTATES, "el_girder_plate.json"), {"multipart": parts})
    loot("el_girder_plate")
    icon("el_girder_plate", icon_girder())
    K.write_json(os.path.join(K.DATA, "recipes/el_girder_plate.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["III", "IDI", "III"], "result": {"item": f"{MOD}:el_girder_plate", "count": 6}})

    # decks
    K.model("el_track_deck", stringers(14.0) + ties(), tex)
    K.model("el_plate_deck", stringers(13.0, cap_up=False) + pan(), tex)
    K.model("el_track_deck_diag", [diag(e) for e in stringers(14.0, x0=DX0, x1=DX1) + ties(DX0, DX1, DX0, DX1, 6)], tex)
    K.model("el_plate_deck_diag", [diag(e) for e in stringers(13.0, cap_up=False, x0=DX0, x1=DX1)
                                   + pan(DX0, DX1, DX0, DX1, cull=False)], tex)
    for name in ("el_track_deck", "el_plate_deck"):
        K.write_json(os.path.join(K.BLOCKSTATES, name + ".json"), {"variants": {
            "axis=x": {"model": f"{MOD}:block/{name}"},
            "axis=z": {"model": f"{MOD}:block/{name}", "y": 90},
            "axis=xz": {"model": f"{MOD}:block/{name}_diag"},
            "axis=zx": {"model": f"{MOD}:block/{name}_diag", "y": 90}}})
        loot(name)
    icon("el_structure_creator", icon_structure_creator())
    icon("el_track_deck", icon_track_deck())
    icon("el_plate_deck", icon_plate_deck())
    K.write_json(os.path.join(K.DATA, "recipes/el_track_deck.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "W": {"item": "minecraft:dark_oak_slab"}},
        "pattern": ["WWW", "I I"], "result": {"item": f"{MOD}:el_track_deck", "count": 6}})
    K.write_json(os.path.join(K.DATA, "recipes/el_plate_deck.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["III", "I I"], "result": {"item": f"{MOD}:el_plate_deck", "count": 6}})
