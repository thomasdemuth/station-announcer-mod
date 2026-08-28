#!/usr/bin/env python3
"""El phase 3 (mezzanine + street + platform details) — the real generator
module, invoked from gen_el_assets.build(); `--out DIR` still writes a
standalone preview tree for offline renders. See EL_STATION_PLAN.md."""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk
from gen_el_assets import (PAINTS, f, elem, Gen, wpng, tex_lattice,
                           CREAM, CREAM_DARK, GLAZE, GLAZE_LIT, GLAZE_DARK,
                           ROOF_RED, ROOF_RED_LIT, ROOF_RED_DARK)

MOD = "station_announcer"
G = PAINTS["green"]

HOUSE_GREEN = (52, 92, 64)
HOUSE_GREEN_LIT = (66, 110, 78)
HOUSE_GREEN_DARK = (40, 74, 50)
HOUSE_GREEN_SHADOW = (30, 58, 40)
HOUSE_CREAM = (214, 202, 176)
HOUSE_CREAM_LIT = (228, 216, 190)
HOUSE_CREAM_DARK = (192, 180, 156)
HOUSE_CREAM_SHADOW = (168, 156, 134)
PLANK = (118, 104, 88)
PLANK_LIT = (134, 120, 102)
PLANK_DARK = (98, 86, 72)
PLANK_GAP = (72, 62, 52)
CONCRETE = (148, 146, 140)
CONCRETE_LIT = (162, 160, 154)
CONCRETE_DARK = (128, 126, 121)
TACTILE = (222, 168, 24)
TACTILE_DARK = (188, 138, 16)
SOFFIT = (186, 178, 160)
SOFFIT_LINE = (160, 152, 136)
BLACK = (18, 19, 21)
WHITE = (240, 240, 238)
LAMP_GLOW = (255, 236, 190)


# ---------------------------------------------------------------- textures --
def tex_house(base, lit, dark, shadow):
    """Board-and-batten siding, 32px: 8-px boards with a proud batten strip —
    vertical boards, per-column only, stack-safe."""
    rows = pk.canvas(32, 32, base)
    for x in range(32):
        m = x % 8
        tone = base
        if m in (0, 1):
            tone = lit                     # the batten catches light
        elif m == 2:
            tone = shadow                  # shadow beside the batten
        elif m == 5:
            tone = dark
        for y in range(32):
            rows[y][x] = tone
    return rows


def tex_glazing():
    """Wired glass you can actually see through: transparent field, thin
    diamond wire grid, sparse glare pixels. CUTOUT layer — visible pixels
    are opaque, everything else is empty (the vanilla-glass approach)."""
    rows = pk.canvas(16, 16, (0, 0, 0, 0))
    WIRE = (88, 96, 100, 255)
    for y in range(16):
        for x in range(16):
            if (x + y) % 8 == 0 or (x - y) % 8 == 0:
                rows[y][x] = WIRE
    for i in range(4):
        rows[2 + i][11 + i if 11 + i < 16 else 15] = (222, 230, 234, 255)
    rows[12][3] = (222, 230, 234, 255)
    rows[13][4] = (222, 230, 234, 255)
    return rows


def tex_planks():
    """Weathered platform planking, per-column boards + gaps."""
    rows = pk.canvas(16, 16, PLANK)
    tones = [PLANK, PLANK_LIT, PLANK, PLANK_DARK, PLANK, PLANK_LIT, PLANK_DARK]
    for x in range(16):
        t = tones[(x * 3) % len(tones)]
        if x % 4 == 3:
            t = PLANK_GAP
        for y in range(16):
            rows[y][x] = t
    return rows


def tex_concrete_edge():
    """Platform edge top, 16px: concrete with the yellow tactile strip in
    rows 0..5 (the track side edge)."""
    rows = pk.canvas(16, 16, CONCRETE)
    for x in range(16):
        t = (CONCRETE, CONCRETE_LIT, CONCRETE, CONCRETE_DARK)[(x * 5) % 4]
        for y in range(16):
            rows[y][x] = t
    pk.rect(rows, 0, 0, 16, 5, TACTILE)
    for x in range(0, 16, 2):
        pk.rect(rows, x, 1, x + 1, 4, TACTILE_DARK)   # truncated domes read
    pk.rect(rows, 0, 5, 16, 6, CONCRETE_DARK)
    return rows


def tex_soffit():
    """Beadboard mezzanine ceiling: fine per-column lines."""
    rows = pk.canvas(16, 16, SOFFIT)
    for x in range(16):
        t = SOFFIT_LINE if x % 4 == 0 else (SOFFIT if x % 4 != 2 else (196, 188, 170))
        for y in range(16):
            rows[y][x] = t
    return rows


def tex_exit():
    """EXIT sign sheet, 32px, four 8-row zones: plain / right / left / down
    arrows. White on black."""
    rows = pk.canvas(32, 32, BLACK)
    for zone, arrow in ((0, None), (8, "right"), (16, "left"), (24, "down")):
        pk.rect(rows, 0, zone, 32, zone + 1, (34, 35, 38))
        pk.rect(rows, 0, zone + 7, 32, zone + 8, (10, 10, 12))
        label_w = pk.text_width("EXIT")
        total = label_w + (5 if arrow else 0)
        x0 = (32 - total) // 2
        if arrow == "left":
            pk.text(rows, x0 + 5, zone + 1, "EXIT", WHITE)
            ax = x0
            pk.rect(rows, ax, zone + 3, ax + 4, zone + 4, WHITE)
            rows[zone + 2][ax + 1] = WHITE
            rows[zone + 4][ax + 1] = WHITE
        else:
            pk.text(rows, x0, zone + 1, "EXIT", WHITE)
            if arrow == "right":
                ax = x0 + label_w + 1
                pk.rect(rows, ax, zone + 3, ax + 4, zone + 4, WHITE)
                rows[zone + 2][ax + 2] = WHITE
                rows[zone + 4][ax + 2] = WHITE
            elif arrow == "down":
                ax = x0 + label_w + 1
                pk.rect(rows, ax + 1, zone + 1, ax + 2, zone + 5, WHITE)
                rows[zone + 4][ax] = WHITE
                rows[zone + 4][ax + 3] = WHITE
                rows[zone + 5][ax + 1] = WHITE
                rows[zone + 5][ax + 2] = WHITE
    return rows


TEX = {
    "house": f"{MOD}:block/el_house_green",
    "housec": f"{MOD}:block/el_house_cream",
    "glaze": f"{MOD}:block/el_glazing",
    "planks": f"{MOD}:block/el_planks",
    "edge": f"{MOD}:block/el_platform_edge",
    "soffit": f"{MOD}:block/el_soffit",
    "exit": f"{MOD}:block/el_exit_sign",
    "body": f"{MOD}:block/el_steel_green",
    "galv": f"{MOD}:block/el_steel_silver",
    "lattice": f"{MOD}:block/el_lattice_green",
    "roof": f"{MOD}:block/el_roof_red",
    "screen": f"{MOD}:block/el_windscreen",
    "corru": f"{MOD}:block/el_corrugated",
    "board": f"{MOD}:block/el_board",
    "particle": f"{MOD}:block/el_steel_green",
}

SLOPE = {"origin": [8, 8, 8], "axis": "x", "angle": 45, "rescale": True}

# ---------------------------------------------------------------------------
# THE 45° SLOPE FRAME (ElSlopeBlock pieces — stair side + stair canopy).
#
# A rescaled +45 rotation about x through (8,8,8) maps an authored point
# (y_a, z_a) to  world y = y_a − z_a + 8 ,  world z = y_a + z_a − 8.
# Two consequences drive every number below:
#
#   * ALONG the slope,  u = z_a − 8  (independent of y_a): each block covers
#     u ∈ [−8, 8] and the next block up-slope (one up, one −z) is exactly
#     Δu = −16, so any element spanning z_a 0..16 tiles a flight of ANY
#     length with no gap and no overlap. Elements that must chain therefore
#     span the full authored z; only mid-block detail may not.
#   * ACROSS the slope,  v = y_a  is the perpendicular height, but measured
#     VERTICALLY at a fixed world z a point sits at  y = 2·v − z. So one
#     authored unit of y is TWO pixels of apparent height above the stairs.
#
# Against `subway_stairs` (two 8 px steps, same ascent-toward-FACING rule):
#     v = 8  → the stair's soffit line (its re-entrant corners, y = 16 − z)
#     v = 12 → the stair's NOSING line (y = 24 − z, the walking surface)
# so the vertical height above the treads of anything on the slope is
# 2·(v − 12) px. The screen's rail lands at v 19.5 → 15 px ≈ 0.94 m, and
# the stringer bottom at v 5.6 → 12.8 px below the nosings.
# ---------------------------------------------------------------------------
NOSING_V = 12.0          # authored y of the stair nosing line
SOFFIT_V = 8.0           # authored y of the stair soffit line


def stair_side(ref="body", sheet="corru"):
    """45° side screen beside a stair run (Prospect Av: a panelled screen in
    a distinct steel frame, stringer under the steps, handrail on top).

    Frame, in v (see the slope frame note above):
      stringer 5.6..8.4  — the beam under the steps, top on the soffit line
      panel    8.0..18.2 — corrugated screen, ends buried in stringer/rail
      mid rail 12.4..13.4 — proud rail right at tread level
      top rail 18.0..19.5 — the handrail cap, 0.94 m over the nosings
      pilaster — one per block, mid-block, proud of the panel both sides
    Every member but the pilaster spans z 0..16, so the run chains."""
    panel = f(sheet, [0, 0.25, 16, 10.45])
    steel = f(ref, [0, 5, 16, 6.4])
    cap = f(ref, [0, 5, 16, 6.2])
    post = f(ref, [1, 4.75, 3.4, 11.2])
    # run-end caps: an authored z=0 face lands exactly on the neighbour's
    # z=16 face, so these are back-to-back inside the run (never visible,
    # never coplanar-from-one-side) and close the flight at both ends.
    endp = f(sheet, [0, 0.25, 2.5, 10.45])
    ends = f(ref, [0, 5, 3, 6.4])
    els = [
        # stringer: top face sits on the soffit line, panel foot buried in it
        elem([6.6, 5.6, 0], [9.4, 8.4, 16],
             {"east": steel, "west": steel, "up": cap, "down": cap,
              "north": ends, "south": ends},
             rotation=dict(SLOPE)),
        # the screen panel: butts the stringer top and the rail underside
        # exactly (no up/down face of its own — those two members' lids are the
        # only quads in those planes, so nothing is coplanar and nothing gaps)
        elem([6.9, 8.4, 0], [9.1, 18.0, 16],
             {"east": panel, "west": panel, "north": endp, "south": endp},
             rotation=dict(SLOPE)),
        # mid rail at tread level, 0.2 proud of the panel — no run-end cap, its
        # own would overlap the panel's in the z=0/16 planes
        elem([6.7, 12.4, 0], [9.3, 13.4, 16],
             {"east": cap, "west": cap, "up": cap, "down": cap},
             rotation=dict(SLOPE)),
        # handrail cap
        elem([6.5, 18.0, 0], [9.5, 19.5, 16],
             {"east": cap, "west": cap, "up": cap, "down": cap,
              "north": ends, "south": ends},
             rotation=dict(SLOPE)),
        # pilaster: perpendicular to the slope, ends buried in rail/stringer
        elem([6.3, 6.0, 6.8], [9.7, 19.2, 9.2],
             {n: post for n in ("north", "south", "east", "west", "up", "down")},
             rotation=dict(SLOPE)),
    ]
    return els


def stair_canopy(ref="body"):
    """45° red standing-seam roof descending with the flight.

      plane   v 7.2..8.8  — red seam sheet over a green corrugated soffit
      seams   v 8.8..10.0 — raised ribs running DOWN the slope: one straddling
                            each x boundary (so a wide roof gets one seam per
                            joint, never a doubled ridge) plus a mid seam
      rafter  x 0..1.6    — under-rib at the x boundary, reads from the street
      eave    z 14.9..16  — cross purlin hugging the DOWN-slope boundary: a
                            purlin at every joint AND the fascia at the foot
      tongue  z −2..0     — up-slope flashing, 0.2 inset all round so it is
                            buried inside the next block's plane when the run
                            continues; at the top of the flight it tucks under
                            an el_canopy_flat placed one up / one −z, closing
                            the wedge that used to be an open hole.
    Everything that must chain spans z 0..16."""
    top = f("roof", [0, 0, 16, 16])
    under = f("corru", [0, 0, 16, 16])
    seam = f("roof", [0, 8.4, 16, 9.4])
    rib = f(ref, [0, 5, 16, 6.4])
    endr = f("roof", [0, 7, 16, 8.6])
    rake = f("roof", [0, 7, 16, 8.6])
    # every closing face here is coincident with the neighbour's matching face
    # (opposite normals, buried between two solids) — so the run reads closed
    # at its rake edges and at both ends instead of showing a hollow slot
    els = [elem([0, 7.2, 0], [16, 8.8, 16],
                {"up": top, "down": under, "north": endr, "south": endr,
                 "east": rake, "west": rake},
                rotation=dict(SLOPE))]
    for x0, x1 in ((-0.7, 0.7), (7.3, 8.7)):        # standing seams
        els.append(elem([x0, 8.8, 0], [x1, 10.0, 16],
                        {"up": seam, "east": seam, "west": seam},
                        rotation=dict(SLOPE)))
    els.append(elem([0, 5.4, 0], [1.6, 7.2, 16],    # down-slope rafter
                    {"down": rib, "east": rib, "west": rib},
                    rotation=dict(SLOPE)))
    els.append(elem([0, 5.6, 14.9], [16, 7.2, 16],  # cross purlin / foot eave
                    {"down": rib, "north": rib, "south": rib},
                    rotation=dict(SLOPE)))
    els.append(elem([0.2, 7.4, -2], [15.8, 8.6, 0],  # up-slope flashing tongue
                    {"up": top, "down": under, "north": rib,
                     "east": rake, "west": rake},
                    rotation=dict(SLOPE)))
    return els


def portal_post_shaft(ref="body"):
    """3.6 px cast portal post — deliberately beefier than the 2.8 px
    el_canopy_post (and exactly the footprint EL_POST_SHAPE already
    declares), with a proud corner bead on each arris."""
    face = f(ref, [12.4, 0.25, 15.4, 15.75])
    bead = f(ref, [13.6, 0.25, 14.4, 15.75])
    els = [elem([6.2, 0, 6.2], [9.8, 16, 9.8],
                {n: face for n in ("north", "south", "east", "west")})]
    for x0 in (5.9, 9.4):
        els.append(elem([x0, 0, 6.6], [x0 + 0.7, 16, 9.4],
                        {n: bead for n in ("north", "south", "east", "west")}))
    for z0 in (5.9, 9.4):
        els.append(elem([6.6, 0, z0], [9.4, 16, z0 + 0.7],
                        {n: bead for n in ("north", "south", "east", "west")}))
    return els


def portal_post_foot(ref="body"):
    """Street plinth at the bottom of a portal-post stack (down=false)."""
    face = f(ref, [1, 5, 12, 6.6])
    return [
        elem([5.2, 0, 5.2], [10.8, 1.8, 10.8],
             {n: face for n in ("north", "south", "east", "west")} | {"up": face}),
        elem([5.7, 1.8, 5.7], [10.3, 3.0, 10.3],
             {n: face for n in ("north", "south", "east", "west")} | {"up": face}),
    ]


def portal_post_bracket(ref="body"):
    """The capital at the top of a portal-post stack: a collar, then a PAIR of
    filigree scroll brackets (one each side along the header axis, the
    el_canopy_post convention — FACING picks the run AXIS, so a portal reads
    the same whichever way the builder was looking). Each bracket is a 1.2 px
    45° knee brace under a slim top arm with a cutout lattice spandrel between
    them — delicate lace, not a solid gusset."""
    collar = f(ref, [1, 5, 5.6, 6.4])
    arm = f(ref, [1, 5, 8, 6.2])
    brace = f(ref, [1, 5, 8, 6.2])
    lat = f("lattice", [0, 0, 8, 6])
    els = [elem([5.8, 13.2, 5.8], [10.2, 14.2, 10.2],
                {n: collar for n in ("north", "south", "east", "west")}
                | {"up": collar, "down": collar})]
    for right in (True, False):
        # top arm, reaching out under the header
        x0, x1 = (9.6, 16.0) if right else (0.0, 6.4)
        els.append(elem([x0, 14.4, 7.3], [x1, 15.7, 8.7],
                        {n: arm for n in ("north", "south", "up", "down",
                                          "east", "west")}))
        # 45° knee brace: authored along x, rotated about z about its OUTER
        # end so it runs down and back into the post shaft. The authored bar
        # is 8.8 long because a 45° turn without rescale projects to
        # 8.8·cos45 = 6.2 px — exactly post face → arm end.
        bx0, bx1 = (6.8, 15.6) if right else (0.4, 9.2)
        origin = [15.6 if right else 0.4, 14.6, 8]
        els.append(elem([bx0, 14.0, 7.4], [bx1, 15.2, 8.6],
                        {n: brace for n in ("north", "south", "up", "down",
                                            "east", "west")},
                        rotation={"origin": origin, "axis": "z",
                                  "angle": 45 if right else -45}))
        # cutout lattice spandrel filling the corner over the brace
        lx0, lx1 = (12.2, 15.6) if right else (0.4, 3.8)
        els.append(elem([lx0, 11.8, 7.9], [lx1, 14.4, 8.1],
                        {"north": lat, "south": lat}))
    return els


def portal_header(ref="body"):
    """The ornamental frieze spanning a portal opening — a merging run along x
    (no end faces, so segments butt seamlessly; the posts cap the run ends).

    Stacked inside the block's declared 5.4..15.6 × z 6.8..9.2 envelope:
      cornice   13.9..15.6, widest — an el_name_board placed in the cell
                ABOVE stands its two legs straight on this cap
      frieze    11.2..13.9
      lattice    6.6..11.4, cutout filigree band (ends buried in the members)
      chord      5.4..6.8, the bottom steel rail
    A 2-post stack under this leaves 2 blocks + 5.4 px = 2.34 m of clear
    opening, which is the 2.2–2.5 m the entrance photos show."""
    cornice = f(ref, [0, 4.9, 16, 6.6])
    beam = f(ref, [0, 5, 16, 7.7])
    latt = f("lattice", [0, 2, 16, 9])
    rail = f(ref, [0, 5, 16, 6.4])
    # run-end caps (back-to-back against the neighbour inside a run, so they
    # never z-fight; without them a header run showed hollow ends)
    endc = f(ref, [0, 4.9, 2.4, 6.6])
    endb = f(ref, [0, 5, 2, 7.7])
    endr = f(ref, [0, 5, 2, 6.4])
    return [
        elem([0, 13.9, 6.8], [16, 15.6, 9.2],
             {"north": cornice, "south": cornice, "up": cornice, "down": cornice,
              "east": endc, "west": endc}),
        elem([0, 11.2, 7.0], [16, 13.9, 9.0],
             {"north": beam, "south": beam, "down": beam,
              "east": endb, "west": endb}),
        elem([0, 6.6, 7.7], [16, 11.4, 8.3], {"north": latt, "south": latt}),
        elem([0, 5.4, 7.0], [16, 6.8, 9.0],
             {"north": rail, "south": rail, "up": rail, "down": rail,
              "east": endr, "west": endr}),
    ]


def full_cube(ref):
    face = f(ref, [0, 0, 16, 16])
    return [elem([0, 0, 0], [16, 16, 16], {
        "north": dict(face, cullface="north"), "south": dict(face, cullface="south"),
        "east": dict(face, cullface="east"), "west": dict(face, cullface="west"),
        "up": dict(face, cullface="up"), "down": dict(face, cullface="down"),
    })]


def house_wall(ref):
    return full_cube(ref)


def window_wall(ref):
    """A REAL window: sill, lintel and jambs of the wall material framing an
    opening, with a see-through wired-glass pane (cutout) in the middle.
    Both faces identical; outer faces cull against full neighbours."""
    def w(uv, cull=None):
        face = f(ref, uv)
        if cull:
            face["cullface"] = cull
        return face
    els = [
        elem([0, 0, 0], [16, 3, 16], {                     # sill
            "north": w([0, 13, 16, 16], "north"), "south": w([0, 13, 16, 16], "south"),
            "east": w([0, 13, 16, 16], "east"), "west": w([0, 13, 16, 16], "west"),
            "up": w([0, 0, 16, 16]), "down": w([0, 0, 16, 16], "down"),
        }),
        elem([0, 13, 0], [16, 16, 16], {                   # lintel
            "north": w([0, 0, 16, 3], "north"), "south": w([0, 0, 16, 3], "south"),
            "east": w([0, 0, 16, 3], "east"), "west": w([0, 0, 16, 3], "west"),
            "up": w([0, 0, 16, 16], "up"), "down": w([0, 0, 16, 16]),
        }),
        elem([0, 3, 0], [2, 13, 16], {                     # west jamb
            "north": w([0, 3, 2, 13], "north"), "south": w([14, 3, 16, 13], "south"),
            "west": w([0, 3, 16, 13], "west"), "east": w([0, 3, 16, 13]),
        }),
        elem([14, 3, 0], [16, 13, 16], {                   # east jamb
            "north": w([14, 3, 16, 13], "north"), "south": w([0, 3, 2, 13], "south"),
            "east": w([0, 3, 16, 13], "east"), "west": w([0, 3, 16, 13]),
        }),
        # the glass: centre pane, ends buried inside the frame (no up/down/
        # east/west faces — they would be coplanar with the reveals)
        elem([2, 3, 7.25], [14, 13, 8.75], {
            "north": f("glaze", [2, 3, 14, 13]),
            "south": f("glaze", [2, 3, 14, 13]),
        }),
    ]
    return els


def platform_edge():
    """Full cube: concrete top with the yellow tactile strip along the NORTH
    (track) edge, riveted girder fascia on the north face."""
    top = f("edge", [0, 0, 16, 16])
    fascia = f("body", [0.25, 0.25, 15.75, 15.75])
    side = f("edge", [0, 6, 16, 16])
    return [elem([0, 0, 0], [16, 16, 16], {
        "up": top,
        "north": dict(fascia, cullface="north"),
        "south": dict(side, cullface="south"),
        "east": dict(side, cullface="east"), "west": dict(side, cullface="west"),
        "down": f("body", [0.25, 5, 15.75, 8], cull="down"),
    })]


def soffit():
    """Ceiling slab at the top of the block."""
    face = f("soffit", [0, 0, 16, 16])
    edge = f("soffit", [0, 6.5, 16, 8])
    return [elem([0, 13, 0], [16, 16, 16], {
        "down": face, "up": dict(face, cullface="up"),
        "north": edge, "south": edge, "east": edge, "west": edge,
    })]


def exit_sign():
    """Hanging EXIT sign: plate on a stub, plain zone (arrow variants are
    uv-zone swaps in the real block)."""
    plate = f("exit", [0, 0.25, 16, 3.75])
    edge = f("exit", [0, 0.5, 1, 3.5])
    return [
        elem([7.3, 12.5, 7.6], [8.7, 16, 8.4],
             {n: f("body", [13.6, 2, 14.4, 5]) for n in ("north", "south", "east", "west")}),
        elem([2, 5.5, 7.5], [14, 12.5, 8.5], {
            "north": plate, "south": plate,
            "east": edge, "west": edge, "up": edge, "down": edge,
        }),
    ]


def _cone_shade(ref, rings, glow_box):
    """A stepped cone/teardrop shade: rings widening downward, each one
    overlapping 0.2 up INTO the ring above so no two lids are coplanar (the
    upper ring's down face is omitted, the lower ring's up face is drawn and
    exposed only as the visible step). Ends with the lit bulb underneath."""
    shade = f(ref, [1, 4.75, 5.6, 6.4])
    els = []
    for i, (x0, x1, y0, y1, z0, z1) in enumerate(rings):
        faces = {n: shade for n in ("north", "south", "east", "west")}
        faces["up"] = shade
        if i == len(rings) - 1:
            faces["down"] = shade                 # only the rim shows a lid
        els.append(elem([x0, y0, z0], [x1, y1, z1], faces))
    gx0, gy0, gz0, gx1, gy1, gz1 = glow_box
    glow = {"texture": "#exit", "uv": [13, 4.4, 15, 5.4], "shade": False}
    els.append(elem([gx0, gy0, gz0], [gx1, gy1, gz1],
                    {n: dict(glow)
                     for n in ("north", "south", "east", "west", "down")},
                    shade=False))
    return els


def lamp_gooseneck(ref="body"):
    """Ceiling-hung goose-neck (Prospect Av street canopy / mezzanine soffit):
    canopy flange, stem, a 45° elbow, the horizontal reach and a stepped cone
    shade with the bulb under it. Stays inside EL_GOOSENECK_SHAPE
    (x 5.5..10.5, y 6..16, z 5.5..15.5)."""
    steel = f(ref, [13.6, 2, 14.4, 5])
    plate = f(ref, [1, 5, 4.6, 6])

    def box(a, b, tex=None):
        t = tex or steel
        return elem(a, b, {n: t for n in ("north", "south", "east", "west",
                                          "up", "down")})
    els = [
        box([6.2, 15.2, 6.2], [9.8, 16.0, 9.8], plate),      # ceiling flange
        box([7.2, 12.6, 7.2], [8.8, 15.4, 8.8]),             # stem
    ]
    # 45° elbow: +45 about x descends toward +z, so the bar leaves the stem at
    # (y 13.4, z 8.0) and arrives at (y 10.85, z 10.55) — the arm's centre
    els.append(elem([7.2, 12.6, 8.0], [8.8, 14.2, 11.6],
                    {n: steel for n in ("north", "south", "east", "west",
                                        "up", "down")},
                    rotation={"origin": [8, 13.4, 8.0], "axis": "x",
                              "angle": 45}))
    els.append(box([7.2, 10.0, 10.0], [8.8, 11.6, 14.0]))    # horizontal reach
    els += _cone_shade(ref, [
        (7.0, 9.0, 8.6, 10.3, 12.2, 14.2),
        (6.4, 9.6, 7.8, 8.8, 11.6, 14.8),
        (5.8, 10.2, 7.0, 8.0, 11.0, 15.4),
    ], (6.8, 6.2, 12.0, 9.2, 7.2, 14.4))
    return els


def lamp_post_pole(ref="body"):
    """Cast lamppost shaft. The collar rides EVERY block of a stack (the block
    has no up/down state), so it is a slim moulded joint ring rather than a
    street base — a band that reads as a repeat, not a mistake."""
    steel = f(ref, [13.6, 0.25, 14.4, 15.75])
    ring = f(ref, [1, 5, 4.8, 5.8])
    return [elem([6.9, 0, 6.9], [9.1, 16, 9.1],
                 {n: steel for n in ("north", "south", "east", "west")}),
            elem([6.3, 0, 6.3], [9.7, 0.8, 9.7],
                 {n: ring for n in ("north", "south", "east", "west")}
                 | {"up": ring})]


def lamp_post_head(ref="body"):
    """The lamppost's top: shaft stub, moulded collar, a 45° elbow arm and the
    same stepped cone shade. Inside EL_LAMP_HEAD_SHAPE (y 0..9, z 5.5..15.5)."""
    steel = f(ref, [13.6, 2, 14.4, 8])
    collar = f(ref, [1, 5, 5.2, 6])
    els = [elem([6.9, 0, 6.9], [9.1, 6.6, 9.1],
                {n: steel for n in ("north", "south", "east", "west")}),
           elem([6.3, 6.6, 6.3], [9.7, 7.6, 9.7],
                {n: collar for n in ("north", "south", "east", "west")}
                | {"up": collar})]
    # 45° elbow rising out of the collar (−45 about x ASCENDS toward +z),
    # then the horizontal arm
    arm = f(ref, [1, 4.75, 3.6, 6])
    els.append(elem([7.2, 7.5, 8.4], [8.8, 8.9, 10.4],
                    {n: arm for n in ("north", "south", "east", "west",
                                      "up", "down")},
                    rotation={"origin": [8, 8.2, 10.4], "axis": "x",
                              "angle": -45}))
    els.append(elem([7.2, 7.6, 10.0], [8.8, 8.8, 13.4],
                    {n: arm for n in ("north", "south", "east", "west",
                                      "up", "down")}))
    els += _cone_shade(ref, [
        (7.0, 9.0, 6.9, 8.0, 11.4, 13.6),
        (6.6, 9.4, 6.1, 7.1, 10.8, 14.0),
        (5.9, 10.1, 5.3, 6.3, 10.2, 14.6),
    ], (6.9, 4.5, 11.2, 9.1, 5.5, 13.6))
    return els


def gable_truss_end():
    """The open truss triangle for gable run ends (replaces the stepped
    plate): bottom chord, two 22.5° sloped chords, lattice web."""
    els = []
    chord = f("body", [0.25, 5, 15.75, 6.2])
    els.append(elem([0.2, 0.2, 0.8], [1.4, 1.5, 15.2],
                    {n: chord for n in ("north", "south", "east", "west", "up", "down")}))
    for z0, angle, oz in ((0.8, -22.5, 1.6), (8.0, 22.5, 14.4)):
        els.append(elem([0.2, 1.3, z0], [1.4, 2.6, z0 + 7.2],
                        {n: chord for n in ("north", "south", "east", "west", "up", "down")},
                        rotation={"origin": [0.8, 1.9, oz], "axis": "x", "angle": angle}))
    lat = f("lattice", [2, 4, 14, 8])
    els.append(elem([0.5, 1.4, 3.4], [1.1, 3.2, 12.6], {"east": lat, "west": lat}))
    return els


# ----------------------------------------------------------------- final ----
ROTS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def facing_variants(mdl):
    return {"variants": {f"facing={facing}": ({"model": f"{MOD}:block/{mdl}", "y": rot}
                                              if rot else {"model": f"{MOD}:block/{mdl}"})
                         for facing, rot in ROTS}}


def exit_sign_models(g):
    """Eight models: (ceiling|wall) x four arrow zones. The plate's back face
    flips u so the lettering reads correctly from both sides."""
    zones = {"none": 0, "right": 4, "left": 8, "down": 12}
    for arrow, v0 in zones.items():
        front = f("exit", [0, v0 + 0.25, 16, v0 + 3.75])
        back = f("exit", [16, v0 + 0.25, 0, v0 + 3.75])
        edge = f("exit", [0, v0 + 0.5, 1, v0 + 3.5])
        stub = {n: f("body", [13.6, 2, 14.4, 5])
                for n in ("north", "south", "east", "west")}
        g.model(f"el_exit_sign_hang_{arrow}", TEX, [
            elem([7.3, 12.5, 7.6], [8.7, 16, 8.4], dict(stub)),
            elem([2, 5.5, 7.5], [14, 12.5, 8.5], {
                "north": front, "south": back,
                "east": edge, "west": edge, "up": edge, "down": edge,
            }),
        ])
        g.model(f"el_exit_sign_wall_{arrow}", TEX, [
            elem([2, 4.5, 14.4], [14, 12, 15.4], {
                "north": front,
                "east": edge, "west": edge, "up": edge, "down": edge,
            }),
        ])


BLOCKS3 = ["el_stair_side", "el_stair_canopy", "el_portal_post", "el_portal_header",
           "el_house_wall_green", "el_house_wall_green_window",
           "el_house_wall_cream", "el_house_wall_cream_window",
           "el_wood_platform", "el_platform_edge", "el_soffit",
           "el_exit_sign", "el_lamp_gooseneck", "el_lamp_post", "el_lamp_head"]

FACINGS4 = {"north", "south", "east", "west"}
PROPS3 = {
    "el_stair_side": {"facing": FACINGS4}, "el_stair_canopy": {"facing": FACINGS4},
    "el_portal_post": {"facing": FACINGS4, "up": {"true", "false"}, "down": {"true", "false"}},
    "el_portal_header": {"facing": FACINGS4},
    "el_house_wall_green": {}, "el_house_wall_green_window": {},
    "el_house_wall_cream": {}, "el_house_wall_cream_window": {},
    "el_wood_platform": {}, "el_soffit": {},
    "el_platform_edge": {"facing": FACINGS4},
    "el_exit_sign": {"facing": FACINGS4, "mount": {"ceiling", "wall"},
                     "arrow": {"none", "right", "left", "down"}},
    "el_lamp_gooseneck": {"facing": FACINGS4},
    "el_lamp_post": {"facing": FACINGS4}, "el_lamp_head": {"facing": FACINGS4},
}

RECIPES3 = {
    "el_stair_side": {"type": "minecraft:crafting_shapeless", "category": "building",
                      "ingredients": [{"item": f"{MOD}:el_windscreen_corrugated"},
                                      {"item": "minecraft:iron_ingot"}],
                      "result": {"item": f"{MOD}:el_stair_side", "count": 2}},
    "el_stair_canopy": {"type": "minecraft:crafting_shapeless", "category": "building",
                        "ingredients": [{"item": f"{MOD}:el_canopy_flat"},
                                        {"item": "minecraft:red_dye"}],
                        "result": {"item": f"{MOD}:el_stair_canopy", "count": 2}},
    "el_portal_post": {"type": "minecraft:crafting_shapeless", "category": "building",
                       "ingredients": [{"item": f"{MOD}:el_canopy_post"},
                                       {"item": "minecraft:iron_bars"}],
                       "result": {"item": f"{MOD}:el_portal_post"}},
    "el_portal_header": {"type": "minecraft:crafting_shapeless", "category": "building",
                         "ingredients": [{"item": f"{MOD}:el_girder"},
                                         {"item": "minecraft:iron_bars"}],
                         "result": {"item": f"{MOD}:el_portal_header", "count": 2}},
    "el_house_wall_green": {"type": "minecraft:crafting_shapeless", "category": "building",
                            "ingredients": [{"item": "minecraft:oak_planks"}] * 4
                                           + [{"item": "minecraft:green_dye"}],
                            "result": {"item": f"{MOD}:el_house_wall_green", "count": 4}},
    "el_house_wall_cream": {"type": "minecraft:crafting_shapeless", "category": "building",
                            "ingredients": [{"item": "minecraft:oak_planks"}] * 4
                                           + [{"item": "minecraft:white_dye"}],
                            "result": {"item": f"{MOD}:el_house_wall_cream", "count": 4}},
    "el_house_wall_green_window": {"type": "minecraft:crafting_shapeless", "category": "building",
                                   "ingredients": [{"item": f"{MOD}:el_house_wall_green"},
                                                   {"item": "minecraft:glass_pane"}],
                                   "result": {"item": f"{MOD}:el_house_wall_green_window"}},
    "el_house_wall_cream_window": {"type": "minecraft:crafting_shapeless", "category": "building",
                                   "ingredients": [{"item": f"{MOD}:el_house_wall_cream"},
                                                   {"item": "minecraft:glass_pane"}],
                                   "result": {"item": f"{MOD}:el_house_wall_cream_window"}},
    "el_wood_platform": {"type": "minecraft:crafting_shapeless", "category": "building",
                         "ingredients": [{"item": "minecraft:spruce_planks"}] * 3
                                        + [{"item": "minecraft:iron_nugget"}],
                         "result": {"item": f"{MOD}:el_wood_platform", "count": 4}},
    "el_platform_edge": {"type": "minecraft:crafting_shapeless", "category": "building",
                         "ingredients": [{"item": "minecraft:stone"},
                                         {"item": "minecraft:yellow_dye"},
                                         {"item": "minecraft:iron_ingot"}],
                         "result": {"item": f"{MOD}:el_platform_edge", "count": 2}},
    "el_soffit": {"type": "minecraft:crafting_shapeless", "category": "building",
                  "ingredients": [{"item": "minecraft:oak_planks"},
                                  {"item": "minecraft:oak_planks"},
                                  {"item": "minecraft:white_dye"}],
                  "result": {"item": f"{MOD}:el_soffit", "count": 4}},
    "el_exit_sign": {"type": "minecraft:crafting_shapeless", "category": "building",
                     "ingredients": [{"item": "minecraft:iron_ingot"},
                                     {"item": "minecraft:black_dye"}],
                     "result": {"item": f"{MOD}:el_exit_sign", "count": 2}},
    "el_lamp_gooseneck": {"type": "minecraft:crafting_shapeless", "category": "building",
                          "ingredients": [{"item": "minecraft:iron_ingot"},
                                          {"item": "minecraft:iron_nugget"},
                                          {"item": "minecraft:glowstone_dust"}],
                          "result": {"item": f"{MOD}:el_lamp_gooseneck", "count": 2}},
    "el_lamp_post": {"type": "minecraft:crafting_shaped", "category": "building",
                     "key": {"N": {"item": "minecraft:iron_nugget"},
                             "I": {"item": "minecraft:iron_ingot"}},
                     "pattern": ["N", "I", "I"],
                     "result": {"item": f"{MOD}:el_lamp_post", "count": 2}},
    "el_lamp_head": {"type": "minecraft:crafting_shapeless", "category": "building",
                     "ingredients": [{"item": "minecraft:iron_ingot"},
                                     {"item": "minecraft:glowstone_dust"},
                                     {"item": "minecraft:green_dye"}],
                     "result": {"item": f"{MOD}:el_lamp_head"}},
}


def write_textures(texdir):
    green_wall = tex_house(HOUSE_GREEN, HOUSE_GREEN_LIT, HOUSE_GREEN_DARK, HOUSE_GREEN_SHADOW)
    cream_wall = tex_house(HOUSE_CREAM, HOUSE_CREAM_LIT, HOUSE_CREAM_DARK, HOUSE_CREAM_SHADOW)
    wpng(os.path.join(texdir, "el_house_green.png"), green_wall)
    wpng(os.path.join(texdir, "el_house_cream.png"), cream_wall)
    wpng(os.path.join(texdir, "el_glazing.png"), tex_glazing())
    wpng(os.path.join(texdir, "el_planks.png"), tex_planks())
    wpng(os.path.join(texdir, "el_platform_edge.png"), tex_concrete_edge())
    wpng(os.path.join(texdir, "el_soffit.png"), tex_soffit())
    wpng(os.path.join(texdir, "el_exit_sign.png"), tex_exit())


def write_models(g):
    # Silver-paint LAMP variants. The models are written but no blockstate
    # references them yet — registering el_lamp_gooseneck_silver /
    # el_lamp_post_silver / el_lamp_head_silver is a Java change (see
    # EL_SET4_REPORT.md); verify() only walks models a blockstate names, so
    # these ride along harmlessly until then.
    for name, els in (
            ("el_lamp_gooseneck_model_silver", lamp_gooseneck("galv")),
            ("el_lamp_post_pole_silver", lamp_post_pole("galv")),
            ("el_lamp_post_head_silver", lamp_post_head("galv")),
            ("el_lamp_post_item_silver", lamp_post_pole("galv"))):
        g.model(name, TEX, els)
    for name, els in (
            ("el_stair_side_model", stair_side()),
            ("el_stair_canopy_model", stair_canopy()),
            ("el_portal_post_shaft", portal_post_shaft()),
            ("el_portal_post_foot", portal_post_foot()),
            ("el_portal_post_bracket", portal_post_bracket()),
            ("el_portal_header_model", portal_header()),
            ("el_house_wall_green", house_wall("house")),
            ("el_house_wall_green_window", window_wall("house")),
            ("el_house_wall_cream", house_wall("housec")),
            ("el_house_wall_cream_window", window_wall("housec")),
            ("el_wood_platform_model", full_cube("planks")),
            ("el_platform_edge_model", platform_edge()),
            ("el_soffit_model", soffit()),
            ("el_lamp_gooseneck_model", lamp_gooseneck()),
            ("el_lamp_post_pole", lamp_post_pole()),
            ("el_lamp_post_head", lamp_post_head()),
            ("el_portal_post_item",
             portal_post_shaft() + portal_post_foot() + portal_post_bracket()),
            ("el_lamp_post_item", lamp_post_pole())):
        g.model(name, TEX, els)
    exit_sign_models(g)


def build_final(g, assets_root, data_root, loot):
    write_textures(os.path.join(assets_root, "textures/block"))
    write_models(g)

    # blockstates
    for block, mdl in (("el_stair_side", "el_stair_side_model"),
                       ("el_stair_canopy", "el_stair_canopy_model"),
                       ("el_portal_header", "el_portal_header_model"),
                       ("el_platform_edge", "el_platform_edge_model"),
                       ("el_lamp_gooseneck", "el_lamp_gooseneck_model"),
                       ("el_lamp_post", "el_lamp_post_pole"),
                       ("el_lamp_head", "el_lamp_post_head")):
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"), facing_variants(mdl))
    for block, mdl in (("el_house_wall_green", "el_house_wall_green"),
                       ("el_house_wall_green_window", "el_house_wall_green_window"),
                       ("el_house_wall_cream", "el_house_wall_cream"),
                       ("el_house_wall_cream_window", "el_house_wall_cream_window"),
                       ("el_wood_platform", "el_wood_platform_model"),
                       ("el_soffit", "el_soffit_model")):
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"),
             {"variants": {"": {"model": f"{MOD}:block/{mdl}"}}})
    parts = [{"apply": {"model": f"{MOD}:block/el_portal_post_shaft"}},
             {"when": {"down": "false"},
              "apply": {"model": f"{MOD}:block/el_portal_post_foot"}}]
    for facing, rot in ROTS:
        entry = {"model": f"{MOD}:block/el_portal_post_bracket"}
        if rot:
            entry["y"] = rot
        parts.append({"when": {"facing": facing, "up": "false"}, "apply": entry})
    g.wj(os.path.join(assets_root, "blockstates", "el_portal_post.json"), {"multipart": parts})
    exit_bs = {}
    for facing, rot in ROTS:
        for mount in ("ceiling", "wall"):
            for arrow in ("none", "right", "left", "down"):
                kind = "hang" if mount == "ceiling" else "wall"
                entry = {"model": f"{MOD}:block/el_exit_sign_{kind}_{arrow}"}
                if rot:
                    entry["y"] = rot
                exit_bs[f"facing={facing},mount={mount},arrow={arrow}"] = entry
    g.wj(os.path.join(assets_root, "blockstates", "el_exit_sign.json"), {"variants": exit_bs})

    # item models
    items = {"el_stair_side": "el_stair_side_model", "el_stair_canopy": "el_stair_canopy_model",
             "el_portal_post": "el_portal_post_item", "el_portal_header": "el_portal_header_model",
             "el_house_wall_green": "el_house_wall_green",
             "el_house_wall_green_window": "el_house_wall_green_window",
             "el_house_wall_cream": "el_house_wall_cream",
             "el_house_wall_cream_window": "el_house_wall_cream_window",
             "el_wood_platform": "el_wood_platform_model",
             "el_platform_edge": "el_platform_edge_model", "el_soffit": "el_soffit_model",
             "el_exit_sign": "el_exit_sign_hang_none",
             "el_lamp_gooseneck": "el_lamp_gooseneck_model",
             "el_lamp_post": "el_lamp_post_item", "el_lamp_head": "el_lamp_post_head"}
    for block, mdl in items.items():
        g.wj(os.path.join(assets_root, "models/item", block + ".json"),
             {"parent": f"{MOD}:block/{mdl}"})

    for block in BLOCKS3:
        g.wj(os.path.join(data_root, MOD, "loot_tables/blocks", block + ".json"), loot(block))
    for name, recipe in RECIPES3.items():
        g.wj(os.path.join(data_root, MOD, "recipes", name + ".json"), recipe)


# ---------------------------------------------------------------- preview ---
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    assets = os.path.join(args.out, "assets", MOD)
    g = Gen(assets, os.path.join(args.out, "data"))
    os.makedirs(os.path.join(assets, "textures/block"), exist_ok=True)
    write_textures(os.path.join(assets, "textures/block"))
    write_models(g)
    print("phase-3 preview tree written")


if __name__ == "__main__":
    main()
