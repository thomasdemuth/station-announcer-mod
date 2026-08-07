#!/usr/bin/env python3
"""The R62's sliding leaves, floors, doorways and displays — a .bbmodel.

    python3 tools/convert_openbve_r62.py    # the shell + textures   (FIRST)
    python3 tools/gen_r62_doors.py          # THIS                   (SECOND)
    python3 tools/gen_r62_assets.py         # properties + defs      (THIRD)
    python3 tools/gen_vehicle_index.py      # the shared index       (FOURTH)
    python3 tools/convert_openbve_r62.py --check                     (LAST)

WHAT THIS WRITES
----------------
    assets/station_announcer/models/vehicle/r62_doors.bbmodel
    assets/station_announcer/textures/vehicle/r62/doors_box.png

WHY ANY OF THIS IS NOT IN THE .obj
-----------------------------------
MTR 4.0.5's .obj path is unexercised upstream — no MTR vehicle ships as .obj —
and it has a family of defects that all bite the same way: silently. Established
on the M7 (bytecode plus two rig cycles), written up in M7_CONVERSION_NOTES.md,
and NOT re-derived here:

  1. DOOR LEAVES. `ModelPropertiesPart.lambda$writeCache$12` bakes a door part's
     position offset into its own mesh AND stores it again on the PartDetails,
     which the renderer translates by — so an animating leaf jumps by its whole
     definition offset. On this car that would be 79/16 = 4.9 blocks.
  2. FLOOR and DOORWAY. The .obj `writeCache` overload is not even handed the
     floors/doorways sets, so those part types contribute nothing and MTR
     synthesises one car-length floor plus a doorway box every block down BOTH
     sides — which is what lets passengers board through the bodyside.
  3. DISPLAY. That overload's per-position callback opens with
     `if (type != PartType.NORMAL) return;`, and `ModelDisplayPart` can only be
     built from a Blockbench element's from/to. A DISPLAY part on an .obj draws
     nothing and logs nothing.

⭐ FLOORS AND DOORWAYS MUST ALWAYS SHIP TOGETHER: the synthesized fallback fires
when BOTH sets are empty, so deleting one silently deletes the other's effect.
`convert_openbve_r62.py --check` asserts both exist and that both are .bbmodel.

⭐ THE R62's LEAVES: TWO GROUPS, NOT FOUR
------------------------------------------
The M7 needed four one-sided leaf groups because its two leaves per opening were
not related by any symmetry. The R62's ARE: the car is 180-degree rotationally
symmetric, so on the +x side the narrow leaf sits at +z of the meeting point and
on the -x side it sits at -z. A `positionsFlipped` entry is exactly that
180-degree turn — so ONE group per leaf, in BOTH lists, covers both sides.

    group        positions            positionsFlipped     doorZMultiplier
    door_narrow  [-79, 0, +79]        [-79, 0, +79]        +10.5
    door_wide    [-79, 0, +79]        [-79, 0, +79]        -11.5

Composition for the .bbmodel path (bytecode-measured on the M7, MTR 4.0.5):
`OptimizedModel$MaterialGroup.lambda$addCube$0` and
`ModelPropertiesPart.lambda$renderNormal$25` both translate and THEN rotate 180
about Y when flipped, i.e. world = R*v + t. So for a leaf authored at bb z
[a, b]:

    unflipped {z:Z}  ->  world z [Z+a, Z+b],  x kept
      flipped {z:Z}  ->  world z [Z-b, Z-a],  x negated

The narrow leaf is authored at bb z +0.5..+10.5, so it lands outboard-of-centre
at +z unflipped and at -z flipped — one leaf group, both sides, each retracting
towards its own end. The multiplier's effective direction is `sign(m)` unflipped
and `-sign(m)` flipped (`copySign(|curve|, m * (flipped ? -1 : +1))`), which is
the same reversal, so ONE sign is correct in both lists.

Every one of those claims is re-derived from the shipped JSON by
`convert_openbve_r62.py --check` rather than trusted from this docstring.

⭐ THE POCKET IS TRIVIAL ON THIS CAR
-------------------------------------
The M7's leaf had to be a stack of five flat planes because its bodyside has
tumblehome and the skin-to-lining corridor leans by more than a leaf's
thickness. The R62's bodyside is a FLAT PLANE at |x| 1.3109 — no tumblehome at
all — so the corridor is the same at every height and ONE plane per render stage
is exact. Two planes per leaf: the outboard one EXTERIOR (world light, what the
platform sees through the opening) and the inboard one INTERIOR (full bright,
what a rider sees). That is r179's structure.

The interior is a LATER pass by another agent, so there is no lining to measure
against yet. `r62_layout.LINING_MAX_X_M` reserves the corridor and `--check`
asserts the leaf stays outboard of it — which is the strongest guarantee
available before the lining exists.

BOX UV
------
`BlockbenchElement.setModelPart` uses vanilla `ModelPart$Cuboid`'s unwrap and
**ignores per-face `uv` dicts** — only `uv_offset` plus the element's size decide
where art is sampled. Sizes are `Math.round(to - from)` INTEGERS. For offset
(u,v) and size (dx,dy,dz), in texture units:

    down   [u+dz    , u+dz+dx  ] x [v   , v+dz   ]
    up     [u+dz+dx , u+dz+2dx ] x [v   , v+dz   ]
    WEST   [u       , u+dz     ] x [v+dz, v+dz+dy]   <- bb +x, the OUTBOARD face
    NORTH  [u+dz    , u+dz+dx  ] x [v+dz, v+dz+dy]
    EAST   [u+dz+dx , u+2dz+dx ] x [v+dz, v+dz+dy]   <- bb -x, the INBOARD face
    SOUTH  [u+2dz+dx, u+2dz+2dx] x [v+dz, v+dz+dy]

Every leaf plane here has dx = 0, so its footprint is 2*dz wide by dz+dy tall and
the two big rects sit side by side. `shade` and `mirror_uv` are written
explicitly rather than defaulted, because MTR computes vanilla's `mirror` flag as
`(!shade || mirror_uv)` and a mirrored cuboid swaps those two rects.

⭐ THE LEAF WINDOW IS A REAL HOLE, SO THE ART MUST BE SYMMETRIC. A .bbmodel has
nowhere to put a translucent shader flag, so the window is cut clean through both
planes — and the box unwrap gives the outboard and inboard rects OPPOSITE
handedness in bb z. Only a leaf whose art is symmetric about its own centre is
guaranteed to have its two holes line up in space.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import convert_openbve_r62 as B
import pngtool
import pixel_kit as K
import r62_art as A
import r62_layout as L

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")
MODEL_PATH = os.path.join(OUR_NS, "models/vehicle/r62_doors.bbmodel")
TEX_PATH = os.path.join(OUR_NS, "textures/vehicle/r62/doors_box.png")

# ---------------------------------------------------------------- geometry
#
# All in .bbmodel units (1/16 block), y = 0 at the car floor — the SAME M frame
# `convert_openbve_r62.py` authors its .obj in, which is what lets a display
# element land on a housing built by the other tool. See that file's docstring.

LEAF_DY = L.LEAF_HEIGHT_UNITS                 # 32
LEAF_MEET = L.LEAF_MEET_Z                     # +0.5
LEAF_X_OUT = L.sx(L.LEAF_X_EXTERIOR)          # the platform-facing plane
LEAF_X_IN = L.sx(L.LEAF_X_INTERIOR)           # the saloon-facing plane

# (group, dz, z0, multiplier). z0 is the leaf's own bb z start.
LEAVES = (
    ("door_narrow", L.LEAF_NARROW, LEAF_MEET, +L.DOOR_MULTIPLIER_NARROW),
    ("door_wide", L.LEAF_WIDE, LEAF_MEET - L.LEAF_WIDE, -L.DOOR_MULTIPLIER_WIDE),
)

# The DOORWAY box: inboard of where the lining will be, out past the skin. It is
# DATA, never drawn — `ModelPropertiesPart.render` switches on PartType and only
# NORMAL and DISPLAY have a case, so FLOOR, DOORWAY and SEAT fall through to
# `default: return`. That is also why these need no texture.
DOORWAY_X = (17.0, 21.0)
FLOOR_HALF_X = 18                              # integer size 36, inside the skin

# PNG texels per model unit. 4 puts the leaf at 64 px per block, a little denser
# than the bodyside elevation (54) because a door is the thing a player stands
# in front of, and it keeps every blit an exact integer rectangle.
TEX_SCALE = 4


def leaf_row_of_y(y_m, height):
    """donor metres above the rail -> row in a leaf's art.

    The box unwrap is linear in the leaf's own height, so this is too. It shares
    `L.sy` with the bodyside, which is what puts the belt rails at exactly the
    height the bodyside puts them, on both sides of every opening.
    """
    return height * (1.0 - L.sy(y_m) / float(LEAF_DY))


# ------------------------------------------------------------------ box uv

def box_uv_rects(u, v, dx, dy, dz):
    """Vanilla ModelPart$Cuboid's unwrap, in texture units.

    Keys are in the .bbmodel frame: `outboard` is bb +x (vanilla WEST) and
    `inboard` is bb -x (vanilla EAST) — the two are swapped relative to the
    direction names because `setModelPart` builds the cuboid at (-to.x, -to.y,
    +from.z). Verified against MTR's own r179.png on the M7.
    """
    return {
        "down": (u + dz, v, u + dz + dx, v + dz),
        "up": (u + dz + dx, v, u + dz + 2 * dx, v + dz),
        "outboard": (u, v + dz, u + dz, v + dz + dy),
        "zmin": (u + dz, v + dz, u + dz + dx, v + dz + dy),
        "inboard": (u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy),
        "zmax": (u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy),
    }


# One footprint per leaf, laid left to right. Both planes of a leaf are the same
# size carrying the same art, so they SHARE one uv_offset.
LEAF_UV, _u = {}, 0
for _name, _dz, _z0, _m in LEAVES:
    LEAF_UV[_name] = (_u, 0)
    _u += 2 * _dz
RES_W = _u
RES_H = max(_dz + LEAF_DY for _n, _dz, _z, _m in LEAVES)


# ---------------------------------------------------------------- leaf art

def leaf_window(dz, height, width):
    """The leaf's glazing rect, in art pixels, CENTRED.

    ⭐ SYMMETRY IS NOT COSMETIC HERE. The window is cut through both planes and
    the two box-UV rects have opposite handedness in bb z, so an off-centre
    window would put the outboard hole and the inboard hole in different places
    and the leaf would read as a hole into its own thickness. The donor's own
    window is 0.183 in from one edge and 0.234 from the other; squaring that up
    costs about a pixel and buys the guarantee.
    """
    inset = width * 0.185
    # The donor's ExtDoors.png puts the glass at donor y 2.125..2.932 — the same
    # band as the bodyside's windows, which is why the door reads as continuous
    # with the side when it is closed.
    return (inset, leaf_row_of_y(2.932, height),
            width - inset, leaf_row_of_y(2.125, height))


def draw_leaf(dz, interior_side):
    """One face of a leaf: the bodyside's livery, or the vestibule's lining.

    The platform sees `interior_side=False` — brushed stainless with the three
    polished belt rails running through at exactly the bodyside's heights, so a
    closed door disappears into the car side the way the prototype's does.

    ⭐ A RIDER SEES THE LINING, NOT STAINLESS (2026-07-29, when the saloon
    landed). The first cut painted the saloon face grey, because there was no
    saloon to match it to; against `convert_r62_interior.py`'s cream lining a
    grey door reads as a hole in the wall. It now takes the SAME cream, the
    SAME panel joint over the seat backs and the same skirting tone as the
    lining it closes — out of `r62_art`'s interior palette, which is exactly
    why that palette is shared rather than local to the interior converter.
    This is the belt-rail argument applied to the other face of the same leaf.

    Both get the SAME hole at the SAME pixels.
    """
    w, h = dz * TEX_SCALE, LEAF_DY * TEX_SCALE

    def row_of_y(y_m):
        return leaf_row_of_y(y_m, h)

    if interior_side:
        rows = K.canvas(w, h, A.INT_CREAM)
        K.hband(rows, 0, row_of_y(3.030), A.INT_CREAM_HI)
        K.hline(rows, row_of_y(3.030), A.INT_CREAM_SEAM)
        K.hband(rows, row_of_y(1.979), h, A.INT_CREAM_LO)
        K.hline(rows, row_of_y(1.979), A.INT_CREAM_SEAM)
        K.hband(rows, row_of_y(1.478), h, A.INT_SKIRT)
    else:
        rows = K.canvas(w, h, A.STEEL)
        A.stainless_field(rows, row_of_y)
        A.brushed(rows, row_of_y(A.CANT_RAIL_Y), row_of_y(A.BELT_UPPER_Y[1]))

    x0, y0, x1, y1 = leaf_window(dz, h, w)
    # Frame, gasket, then the hole — the same nested rounded rects `A.pane`
    # uses, with alpha 0 where it would mark glass. A leaf is CUTOUT on both of
    # its render stages, so a hole is all there is to work with.
    K.rrect(rows, x0 - 3, y0 - 3, x1 + 3, y1 + 3, 8, A.POLISH_LO)
    K.rrect(rows, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 7, A.POLISH)
    K.rrect(rows, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 6, A.GASKET)
    K.rrect(rows, x0, y0, x1, y1, 5, (0, 0, 0, 0))

    # The leaf's own edges. Without them a closed door vanishes into the
    # bodyside completely — which is what drawing both from one palette does —
    # and there is no door left to see. A dark leading edge with a lit chamfer
    # just inside is what a near-flush pocket door actually looks like.
    for x, inner in ((0, 1), (w - 1, w - 2)):
        K.vline(rows, x, 0, h, A.INT_CREAM_SEAM if interior_side else A.GASKET)
        K.vline(rows, inner, 0, h,
                A.POLISH_LO if interior_side else A.POLISH)
    K.rect(rows, 0, h - 2, w, h,
           A.INT_THRESHOLD if interior_side else A.BLACK_BAND)
    K.rect(rows, 0, 0, w, 1, A.INT_CREAM_SEAM if interior_side else A.SEAM)
    return rows


def resample(rows, w, h):
    sh, sw = len(rows), len(rows[0])
    return [[rows[y * sh // h][x * sw // w] for x in range(w)] for y in range(h)]


def blit(canvas, rect, art):
    x0, y0, x1, y1 = (int(round(c * TEX_SCALE)) for c in rect)
    if x1 <= x0 or y1 <= y0:
        return None
    tile = resample(art, x1 - x0, y1 - y0)
    for y in range(y0, y1):
        canvas[y][x0:x1] = tile[y - y0]
    return (x0, y0, x1, y1), tile


def build_texture():
    base = K.rgba(A.STEEL)
    canvas = [[base] * (RES_W * TEX_SCALE) for _ in range(RES_H * TEX_SCALE)]
    placed = {}
    for name, dz, _z0, _m in LEAVES:
        u, v = LEAF_UV[name]
        rects = box_uv_rects(u, v, 0, LEAF_DY, dz)
        placed["%s.outboard" % name] = blit(canvas, rects["outboard"],
                                            draw_leaf(dz, False))
        placed["%s.inboard" % name] = blit(canvas, rects["inboard"],
                                           draw_leaf(dz, True))
    return canvas, placed


# ----------------------------------------------------------------- bbmodel

def uuid_for(index):
    """Deterministic, so re-running produces a byte-identical model."""
    return "r62d0e%03d-0001-0002-0003-%012d" % (index, index)


def element(name, uuid, frm, to, uv_offset):
    dx = int(round(to[0] - frm[0]))
    dy = int(round(to[1] - frm[1]))
    dz = int(round(to[2] - frm[2]))
    r = box_uv_rects(uv_offset[0], uv_offset[1], dx, dy, dz)
    # `faces` is written for Blockbench's sake only — MTR reads uv_offset and
    # the element size and nothing else. The names are Blockbench's, whose x
    # axis is mirrored against vanilla's model space, so its `east` is the
    # vanilla WEST rect.
    faces = {
        "north": {"uv": list(r["zmin"]), "texture": 0},
        "east": {"uv": list(r["outboard"]), "texture": 0},
        "south": {"uv": list(r["zmax"]), "texture": 0},
        "west": {"uv": list(r["inboard"]), "texture": 0},
        "up": {"uv": [r["up"][0], r["up"][3], r["up"][2], r["up"][1]],
               "texture": 0},
        "down": {"uv": list(r["down"]), "texture": 0},
    }
    return {
        "name": name, "box_uv": True, "rescale": False, "locked": False,
        "render_order": "default", "allow_mirror_modeling": True,
        "from": list(frm), "to": list(to),
        # Explicit rather than defaulted: MTR computes vanilla's `mirror` flag
        # as (!shade || mirror_uv), and a mirror swaps the two big faces.
        "shade": True, "mirror_uv": False,
        "autouv": 0, "color": 0, "inflate": 0, "origin": [0, 0, 0],
        "uv_offset": list(uv_offset), "faces": faces,
        "type": "cube", "uuid": uuid,
    }


def display_element(name, uuid, frm, to, origin=None, rotation=None):
    """A flat text-anchor plate, r179's own shape.

    Deliberately NOT `element()`: box UV is meaningless on a zero-thickness
    plate and MTR never reads it for a DISPLAY part, so this copies r179 —
    no `uv_offset` key, no faces, hidden in Blockbench.

    ⭐ The `uv_offset` KEY IS OMITTED, never written as null (fixed 2026-07-30).
    An earlier revision wrote `"uv_offset": None` believing it copied r179. It
    does not: across MTR's 30+ vehicle .bbmodels, 6771 elements carry a real
    list and 682 OMIT the key — ZERO write null. A JSON null becomes gson's
    `JsonNull`, which survives `ReaderBase.unpackValue`'s Java-null guard and
    then throws `IllegalStateException: Not a JSON Array: null` out of
    `BlockbenchElementSchema.updateData`'s `iterateLongArray`. It is caught and
    logged with a full stack trace, so the geometry still loads, but every
    model build spams the render thread. Omitting the key is a clean no-op.

    GEOMETRY CONVENTION, read out of r179 (MTR's whole DISPLAY corpus):
      * a display element is a ZERO-THICKNESS plate: from[2] == to[2].
      * the text plane is the element's MINIMUM-Z face; the text FACES -Z and is
        read from the -Z side.
      * width = round(to[0]-from[0]) and height = round(to[1]-from[1]), in whole
        model pixels, and those integers ARE the text canvas — which is why
        every rectangle here comes out of `r62_layout`'s snapped helpers.
    """
    out = {
        "name": name, "box_uv": True, "rescale": False, "locked": False,
        "render_order": "default", "allow_mirror_modeling": True,
        "from": list(frm), "to": list(to),
        "shade": True, "mirror_uv": False,
        "autouv": 0, "color": 0, "inflate": 0,
        "origin": list(origin or [0, 0, 0]),
        # NO "uv_offset" KEY — see the docstring. Never write null here.
        "faces": {}, "visibility": False,
        "type": "cube", "uuid": uuid,
    }
    if rotation:
        out["rotation"] = list(rotation)
    return out


def group(name, index, *children):
    return {
        "name": name, "origin": [0, 0, 0], "color": 0,
        "uuid": "r62dg%03dg-0001-0002-0003-%012d" % (index, index),
        "export": True, "mirror_uv": False, "isOpen": False, "locked": False,
        "visibility": True, "autouv": 0, "children": list(children),
    }


# ⭐ THE FLOOR PLATES. Each stops where its bay stops, so laid end to end they
# tile the whole walkable car with no step. The END plate is NOT symmetric — the
# nose and the storm-door recess eat its outer end — which is exactly why its
# definitions cannot be the .obj's `end1`/`end2`: the two model formats compose
# a flipped entry with opposite signs, so `bbEnd*` carry the .bbmodel's own.
def floor_plates():
    end_out = L.END_BAY.z(L.STORM_DOOR_Z)
    return (
        ("floor_panel", -L.PANEL_UNITS / 2.0, L.PANEL_UNITS / 2.0),
        ("floor_door", -L.DOOR_UNITS / 2.0, L.DOOR_UNITS / 2.0),
        ("floor_end", -L.END_UNITS / 2.0,
         -L.END_UNITS / 2.0 + int(end_out + L.END_UNITS / 2.0)),
    )


# ⭐ THE DISPLAYS.
#
# THE SIDE ROLLSIGN, aimed at the car side, r179's way. A DISPLAY element's text
# always faces its own -Z, so aiming one outboard means ROTATING it, and r179 is
# the only worked example in MTR's corpus: `side_destination_display_1` is
# `origin [21.5, 13, 0]`, `rotation [0, -90, 6]`, with a mirrored twin at
# `origin [-21.5, ...]`, `rotation [0, 90, -6]`.
#
# Decoding the -90 yaw (about the origin, so relative coordinates):
#     x' = -dz        z' = dx
# which means, for the +x side:
#   * the element's authored X becomes the ALONG-CAR axis, so its width — the
#     integer MTR uses as the text canvas — is the sign's LENGTH.
#   * the authored Z becomes -x, so a NEGATIVE authored z is OUTBOARD.
#   * the -Z normal turns into +X. Outward.
#
# ⭐ NO ROLL. r179's +-6 is the tumblehome of a body that leans 6 degrees; the
# R62's bodyside is dead vertical, so there is nothing to lean into and the
# element stays square — which also keeps it out of Blockbench's Euler-order
# question, for which the jar contains no second worked example.
#
# ONE element per field serves BOTH sides: `bbPanel` carries both position
# lists, and the flip is the same 180-degree turn that puts the whole panel bay
# on the other side at the other end.
SIDE_DISPLAYS = (
    # (name, field getter, standoff order, displayType, options)
    ("side_bullet_color", L.sign_bullet, 0, "ROUTE_COLOR_ROUNDED", ()),
    ("side_bullet_number", L.sign_bullet, 1, "ROUTE_NUMBER",
     ("UPPER_CASE",)),
    ("side_route_number", L.sign_upper, 0, "ROUTE_NUMBER",
     ("UPPER_CASE", "CYCLE_LANGUAGES", "SINGLE_LINE", "SCROLL_NORMAL")),
    ("side_destination", L.sign_lower, 0, "DESTINATION",
     ("UPPER_CASE", "CYCLE_LANGUAGES", "SINGLE_LINE", "SCROLL_NORMAL")),
)

# ⭐ THE FRONT ROUNDEL — AND THE ONE THING IN THIS MODEL THAT IS NOT PRECEDENTED.
#
# The end bay is authored with its OUTWARD face at local +z (which is what keeps
# the car's chirality faithful to the donor — see r62_layout), but a DISPLAY
# plate's text faces its own -Z. So the roundel's elements carry
# `rotation: [0, 180, 0]` to turn them round, and MTR ships no 180-degree
# display rotation to calibrate against: r179 only ever uses +-90.
#
# The mitigation is in WHAT IS ON THE PLATE, not in the plate. If a 180 yaw were
# to mirror the text's advance direction, a multi-character string would read
# backwards — so nothing multi-character goes here. The roundel is a
# ROUTE_COLOR_ROUNDED disc (no handedness at all) with a ROUTE_NUMBER over it,
# and an NYC route bullet is one or two characters. A single centred glyph reads
# identically either way round.
#
# This is the one item on the in-game checklist in R62_NOTES.md.
FRONT_DISPLAYS = (
    ("front_route_color", 0, "ROUTE_COLOR_ROUNDED", ()),
    ("front_route_number", 1, "ROUTE_NUMBER", ("UPPER_CASE",)),
)

# ⭐ THE INTERIOR STRIP MAP — the one display family that faces INWARD.
#
# The R62's line map lives in the ad band above the windows, and the user's
# decision made it a HYBRID: `convert_r62_interior.py` paints the housing and
# the generic station ticks, and these three elements are the live inserts — a
# route bullet at the car-centre end, and a NEXT_STATION strip beside it.
#
# ⭐ TWO SETS, ONE PER SIDE, AND THAT IS NOT SYMMETRY WASTE. `bbPanel`'s flip
# turns an element authored on +x into one on -x AT THE OTHER END of the car,
# so a single set gives two maps diagonally opposite — a rider on the wrong
# side of the aisle would never see one. The `_a` set is authored on +x and the
# `_b` set on -x; together with the two lists that is four maps, one on each
# side of each panel bay.
#
# THE YAW. A DISPLAY plate's text faces its own -Z, so an inboard-facing sign
# has to be rotated, and r179's side pair is the worked example: yaw -90 turns
# the -Z normal into +X. Decoding it (x' = dx cos + dz sin, z' = -dx sin + dz
# cos) gives, for the two yaws this file uses:
#
#     yaw -90   normal -> +x (outboard on +x, INBOARD on -x)
#               authored x -> +z          authored z -> -x
#     yaw +90   normal -> -x (INBOARD on +x)
#               authored x -> -z          authored z -> +x
#
# So the `_a` set (on +x, facing inboard) takes +90 and the `_b` set takes -90,
# each with a NEGATIVE authored z to stand the plate off its own board — the
# same sign convention the exterior rollsign uses, because in both cases a
# negative authored z is "away from the surface, in the direction the text
# faces". The along-car centre goes in from[0]/to[0] with origin[2] = 0
# (r179's arrangement — putting it in origin[2] instead lands the plate inside
# the car, invisible, with nothing logged: it cost the M7 a build).
#
# Both signs are re-derived from the shipped JSON by
# `convert_r62_interior.py --check`, which bakes the painted board through the
# .obj's placement transform and each element through the .bbmodel's — the two
# compose a flipped entry with OPPOSITE signs — and requires the text to land
# on the board.
MAP_DISPLAYS = (
    # (name, field getter, standoff order, displayType)
    ("int_map_bullet_color", L.map_bullet, 0, "ROUTE_COLOR_ROUNDED"),
    ("int_map_bullet_number", L.map_bullet, 1, "ROUTE_NUMBER"),
    ("int_map_next", L.map_text, 0, "NEXT_STATION"),
)

DISPLAY_STEP = 0.30          # M units between two stacked plates


def side_display_element(name, uuid, field, order):
    """One field of the side rollsign, aimed outboard from the +x skin.

    ⭐ THE ALONG-CAR CENTRE GOES IN from[0]/to[0] AND origin[2] STAYS 0. This is
    the trap M7_CONVERSION_NOTES documents and this file walked into anyway
    (fixed 2026-07-29, found while wiring the interior strip map). A rotated
    element's offsets are relative to `origin`, so writing the along-car centre
    into `origin[2]` makes `dz = standoff - centre`, and yaw -90 turns dz into
    -x: the plate lands `centre` units away from the car side, in or out
    depending on the sign. Measured on the JSON this file used to write:

        side_bullet_color   centre_z -6.03  ->  world x 14.9  (4.5 INSIDE the
                                                 skin, and inside the lining)
        side_route_number   centre_z +2.00  ->  world x 22.9  (2.5 proud of a
                                                 housing face at 20.4)

    Nothing in game reports it; the text is simply not on the sign. r179's own
    arrangement is the fix — `origin [21.5, 13, 0]`, the along-car span in
    from[0]/to[0] — and it is what this now does:

        dx = from[0] - origin[0]  ->  world z = origin[2] + dx
        dz = from[2] - origin[2]  ->  world x = origin[0] - dz

    so a NEGATIVE authored z stands the plate proud of the skin, which is what
    `stand` is.
    """
    z0, z1, y0, y1 = field()
    half = (z1 - z0) / 2.0
    centre_z = (z0 + z1) / 2.0
    skin = L.sx(L.DONOR_HALF_X)
    stand = -(L.SIGN_TEXT_STANDOFF + order * DISPLAY_STEP)
    return display_element(name, uuid,
                           (skin + centre_z - half, y0, stand),
                           (skin + centre_z + half, y1, stand),
                           origin=[skin, (y0 + y1) / 2.0, 0],
                           rotation=[0, -90, 0])


def map_display_element(name, uuid, field, order, side):
    """One field of the interior strip map, aimed INBOARD from a map board.

    See MAP_DISPLAYS above for the yaw decode. The two sides take opposite
    yaws, so the along-car span is written the opposite way round on each; the
    width — which is what MTR rounds into the text canvas — comes out positive
    either way.
    """
    z0, z1, y0, y1 = field()
    half = (z1 - z0) / 2.0
    centre_z = (z0 + z1) / 2.0
    board = side * L.sx(L.INT_BAND_X_M)
    stand = -(L.MAP_TEXT_STANDOFF + order * DISPLAY_STEP)
    if side > 0:                       # yaw +90: world z = origin.z - dx
        x_from, x_to = board - centre_z - half, board - centre_z + half
        yaw = 90
    else:                              # yaw -90: world z = origin.z + dx
        x_from, x_to = board + centre_z - half, board + centre_z + half
        yaw = -90
    return display_element(name, uuid, (x_from, y0, stand), (x_to, y1, stand),
                           origin=[board, (y0 + y1) / 2.0, 0],
                           rotation=[0, yaw, 0])


def front_display_element(name, uuid, order):
    """The route roundel on the cab front, turned to face the car's local +z."""
    x0, x1, y0, y1 = L.roundel_plate()
    z = L.END_BAY.z(L.nose_z((L.ROUNDEL_X_M[0] + L.ROUNDEL_X_M[1]) / 2.0))
    z += L.ROUNDEL_TEXT_STANDOFF + order * DISPLAY_STEP
    return display_element(name, uuid, (x0, y0, z), (x1, y1, z),
                           origin=[(x0 + x1) / 2.0, (y0 + y1) / 2.0, z],
                           rotation=[0, 180, 0])


def build_model():
    elements, outliner = [], []

    def add(name, frm, to, uv_offset=(0, 0)):
        uuid = uuid_for(len(elements))
        elements.append(element(name, uuid, frm, to, uv_offset))
        return uuid

    for name, dz, z0, _m in LEAVES:
        # The bare name is the INTERIOR-stage plane (what a rider sees) and
        # `_exterior` is the outboard one the platform sees, exactly as r179
        # names its pair.
        for suffix, x in (("", LEAF_X_IN), ("_exterior", LEAF_X_OUT)):
            uuid = add(name + suffix, (x, 0.0, z0), (x, LEAF_DY, z0 + dz),
                       LEAF_UV[name])
            outliner.append(group(name + suffix, len(outliner), uuid))

    # The aperture, as data. One group placed by `bbDoor`'s six entries — three
    # openings in each list — so `mapDoors` can give every leaf the box on its
    # own side at its own opening, which is what makes only the platform side
    # open.
    outliner.append(group("doorway_box", len(outliner),
                          add("doorway", (DOORWAY_X[0], 0.0, -L.DOOR_UNITS / 2.0),
                              (DOORWAY_X[1], 0.0, L.DOOR_UNITS / 2.0))))
    for name, z0, z1 in floor_plates():
        outliner.append(group(name, len(outliner),
                              add(name, (-FLOOR_HALF_X, 0.0, z0),
                                  (FLOOR_HALF_X, 0.0, z1))))

    # One group per display element, because a properties part binds by GROUP
    # name and each field needs its own displayType.
    for name, field, order, _type, _opts in SIDE_DISPLAYS:
        uuid = uuid_for(len(elements))
        elements.append(side_display_element(name, uuid, field, order))
        outliner.append(group(name, len(outliner), uuid))
    for name, order, _type, _opts in FRONT_DISPLAYS:
        uuid = uuid_for(len(elements))
        elements.append(front_display_element(name, uuid, order))
        outliner.append(group(name, len(outliner), uuid))
    # The interior strip map: one set per side, so a car ends up with four.
    for name, field, order, _type in MAP_DISPLAYS:
        for suffix, side in (("_a", +1.0), ("_b", -1.0)):
            uuid = uuid_for(len(elements))
            elements.append(map_display_element(name + suffix, uuid, field,
                                                order, side))
            outliner.append(group(name + suffix, len(outliner), uuid))

    return {
        "meta": {"format_version": "4.10", "model_format": "modded_entity",
                 "box_uv": True},
        "name": "r62_doors",
        "model_identifier": "",
        "visible_box": [1, 1, 0],
        "resolution": {"width": RES_W, "height": RES_H},
        "elements": elements,
        "outliner": outliner,
        "textures": [],
    }


# ------------------------------------------------------------ verification

def verify(placed):
    """Decode what was actually written and prove every art rectangle landed
    where the box-UV formulas say it does.

    This is the only mechanical guard on the layout: MTR ignores per-face uv
    dictionaries, so a wrong rect shows up in game as stainless where the door
    should be, with nothing logged.
    """
    problems = []
    w, h, rows = pngtool.read_png(TEX_PATH)
    if (w, h) != (RES_W * TEX_SCALE, RES_H * TEX_SCALE):
        problems.append("texture is %dx%d, expected %dx%d"
                        % (w, h, RES_W * TEX_SCALE, RES_H * TEX_SCALE))
        return problems
    for key, entry in sorted(placed.items()):
        if entry is None:
            problems.append("%s: nothing placed" % key)
            continue
        (x0, y0, x1, y1), tile = entry
        bad = sum(1 for y in range(y0, y1) for x in range(x0, x1)
                  if rows[y][x] != tile[y - y0][x - x0])
        if bad:
            problems.append("%s: %d px of %dx%d differ from the source art"
                            % (key, bad, x1 - x0, y1 - y0))
    # The two leaves' footprints must not overlap in the atlas.
    spans = []
    for name, dz, _z, _m in LEAVES:
        u, _v = LEAF_UV[name]
        spans.append((u, u + 2 * dz))
    spans.sort()
    for a, b in zip(spans, spans[1:]):
        if a[1] > b[0]:
            problems.append("leaf uv footprints overlap at u %g" % a[1])

    # ⭐ THE TWO HOLES IN A LEAF MUST LINE UP IN SPACE. The outboard and inboard
    # rects have opposite handedness in bb z, so this compares the outboard art
    # against its own mirror — which is what the inboard face effectively is.
    for name, dz, _z, _m in LEAVES:
        art = draw_leaf(dz, False)
        for row in art:
            if [px[3] == 0 for px in row] != [px[3] == 0 for px in row[::-1]]:
                problems.append("%s: the leaf window is not symmetric about the "
                                "leaf's centre — its two holes would not line "
                                "up" % name)
                break

    # The leaves must tile the aperture exactly, and meet where the layout says.
    total = sum(dz for _n, dz, _z, _m in LEAVES)
    if total != L.APERTURE_UNITS:
        problems.append("the leaves total %d units against a %g aperture"
                        % (total, L.APERTURE_UNITS))
    edges = sorted((z0, z0 + dz) for _n, dz, z0, _m in LEAVES)
    if edges[0][1] != edges[1][0]:
        problems.append("the leaves gap or overlap at bb z %g" % edges[0][1])
    if abs(edges[0][0] + L.APERTURE_UNITS / 2.0) > 1e-9 or \
            abs(edges[-1][1] - L.APERTURE_UNITS / 2.0) > 1e-9:
        problems.append("the leaves do not span the aperture (%s)" % edges)

    # Every element MTR will read has to have integer sizes, or it silently
    # renders at a size the JSON does not say — and for a display, lays its
    # text into a canvas of that wrong size.
    model = build_model()
    for e in model["elements"]:
        for axis in range(3):
            span = e["to"][axis] - e["from"][axis]
            if abs(span - round(span)) > 1e-9:
                problems.append("element %r has non-integer size on axis %d "
                                "(%.4f)" % (e["name"], axis, span))
    names = {o["name"] for o in model["outliner"]}
    if len(names) != len(model["outliner"]):
        problems.append("duplicate group names in the outliner")
    # ⭐ REGRESSION GUARD (2026-07-30). A JSON null here is NOT the same as an
    # absent key: gson turns it into JsonNull, which slips past
    # `ReaderBase.unpackValue`'s null check and then throws
    # `IllegalStateException: Not a JSON Array: null` inside MTR's
    # `BlockbenchElementSchema.updateData`. MTR's own 30+ vehicle models write a
    # list 6771 times and OMIT the key 682 times — never null. See
    # `display_element`.
    for e in model["elements"]:
        if "uv_offset" in e and e["uv_offset"] is None:
            problems.append("element %r writes `uv_offset: null` — OMIT the key "
                            "instead; null throws inside MTR's bbmodel parser"
                            % e["name"])
    return problems


def main():
    canvas, placed = build_texture()
    os.makedirs(os.path.dirname(TEX_PATH), exist_ok=True)
    pngtool.write_png(TEX_PATH, canvas)

    model = build_model()
    os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
    with open(MODEL_PATH, "w") as fh:
        json.dump(model, fh, indent=1)
        fh.write("\n")

    problems = verify(placed)
    print("r62_doors.bbmodel: %d groups, %d elements, resolution %dx%d"
          % (len(model["outliner"]), len(model["elements"]), RES_W, RES_H))
    print("doors_box.png:     %dx%d (%d texels/unit)"
          % (RES_W * TEX_SCALE, RES_H * TEX_SCALE, TEX_SCALE))
    for name, dz, z0, m in LEAVES:
        print("  %-12s %2d x %2d units at bb z %+g..%+g, multiplier %+.1f "
              "(opens %.1f)" % (name, dz, LEAF_DY, z0, z0 + dz, m, abs(m) - 0.5))
    print("  leaf planes at x %.3f (exterior) / %.3f (interior), skin %.3f"
          % (LEAF_X_OUT, LEAF_X_IN, L.sx(L.DONOR_HALF_X)))
    print("  doorway x %g..%g, floors %s"
          % (DOORWAY_X[0], DOORWAY_X[1],
             ", ".join("%s %g..%g" % p for p in floor_plates())))
    for name, field, order, kind, _o in SIDE_DISPLAYS:
        z0, z1, y0, y1 = field()
        print("  %-19s %-21s %2d x %2d px" % (name, kind,
                                              round(z1 - z0), round(y1 - y0)))
    x0, x1, y0, y1 = L.roundel_plate()
    for name, order, kind, _o in FRONT_DISPLAYS:
        print("  %-19s %-21s %2d x %2d px (yaw 180)"
              % (name, kind, round(x1 - x0), round(y1 - y0)))
    for name, field, order, kind in MAP_DISPLAYS:
        z0, z1, y0, y1 = field()
        print("  %-19s %-21s %2d x %2d px x2 (interior, yaw +-90)"
              % (name, kind, round(z1 - z0), round(y1 - y0)))
    for p in problems:
        print("PROBLEM: %s" % p)
    print("box-UV verification: %d problems" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
