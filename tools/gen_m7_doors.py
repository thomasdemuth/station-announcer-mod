#!/usr/bin/env python3
"""Generates the M7 door leaves as a companion .bbmodel — the sliding doors.

    python3 tools/convert_openbve_m7.py     # body model + textures   (FIRST)
    python3 tools/gen_m7_doors.py           # THIS: the door leaves   (SECOND)
    python3 tools/gen_m7_assets.py          # properties + index      (THIRD)
    python3 tools/convert_openbve_m7.py --check    # cross-check all three

WHAT THIS WRITES
----------------
    assets/station_announcer/models/vehicle/m7_doors.bbmodel
    assets/station_announcer/textures/vehicle/m7/doors_box.png

WHY THE LEAVES ARE NOT IN THE .obj  (this file exists because of a real bug)
---------------------------------------------------------------------------
The M7 body is an .obj and renders correctly, but MTR 4.0.5's OBJ path does
NOT animate doors correctly. No MTR vehicle ships as .obj, so that path is
unexercised upstream. Two defects, both read out of the 4.0.5 bytecode and
both seen in game:

 1. DOUBLE OFFSET. `ModelPropertiesPart.lambda$writeCache$12` bakes the
    position offset into the door part's own ObjModelWrapper
    (`addObjModelPosition` -> `ObjModelWrapper.addTransformation`) and then
    stores the SAME offset again on the `PartDetails`. At render time
    `lambda$renderNormal$25` translates by `PartDetails.x/y/z` before queueing
    that already-offset mesh, so an animating leaf jumps by its whole
    definition offset. In game, on the 20-block car it was 88/16 = 5.5 blocks:
    "the leaves pop open about 5 blocks from the train". On the 26-block car
    the same defect would throw them 112/16 = 7 blocks.
    The .bbmodel path does not have this: `lambda$writeCache$1` bakes the
    door's animated copy at (0,0,0) and the render-time translate supplies the
    entire offset exactly once.

 2. NO DOORWAYS. The OBJ `writeCache` overload takes no floors/doorways sets
    at all (compare the .bbmodel overload, which takes two `ObjectArraySet
    <Box>`), so `type: DOORWAY` and `type: FLOOR` parts in an .obj contribute
    nothing. `VehicleResource` therefore logs, as our own dev client did,
    `[m7_cab_3] No floors or doorways found in vehicle models` and synthesises
    a fallback: one car-length floor plus a 1-block doorway box every block
    along BOTH sides. Door side-binding is geometric — `mapDoors` gives every
    door instance the NEAREST doorway box and `RenderVehicles` only opens the
    boxes that `canOpenDoors` accepts — so with the leaves' own boxes displaced
    by defect 1 the matching went wrong and both sides animated.

So the leaves move to the .bbmodel path that 423 MTR vehicles prove, and the
.obj keeps the aperture, the rubber reveal, the surround and the roof.

AND SO DO THE FLOOR AND DOORWAY BOXES (2026-07-28). Defect 2 was left riding
the synthesized fallback for one release, which is why passengers could board
anywhere along the car. This model now ships them itself: `doorway_box` at
each of the four openings and four FLOOR plates that tile the walkable car.
BOTH HALVES MUST ALWAYS SHIP TOGETHER — the fallback only fires when floors
AND doorways are both empty, so deleting one silently deletes the effect of
the other. `convert_openbve_m7.py --check` asserts both exist and that both
live in a .bbmodel.

They cost nothing to draw, because they are never drawn:
`ModelPropertiesPart.render` switches on PartType and only NORMAL and DISPLAY
have a case — FLOOR, DOORWAY and SEAT fall through to `default: return`. That
is also why the .obj's old `*_floor` and `doorway` parts were dead weight
twice over (wrong model format to register, wrong PartType to draw) and have
been deleted from it; the visible floor is the interior model's, and the one
piece of that geometry worth keeping — the sill strip from the saloon floor's
edge out to the bodyside — survives as the .obj's `door_threshold`, re-typed
NORMAL so that it finally draws.

THE FOUR ONE-SIDED GROUPS
-------------------------
Each leaf is a group that appears in exactly ONE of `positions` /
`positionsFlipped`, which is what makes its side unambiguous:

    group          positions    positionsFlipped   doorZMultiplier
    door_front_a   [{z:+112}]   []                 +24
    door_front_b   []           [{z:+112}]         -24
    door_rear_a    [{z:-112}]   []                 -24
    door_rear_b    []           [{z:-112}]         +24

(+-112 and +-24 are the 26-block car's; every one of them is read from
tools/m7_layout.py, and the mini's +-37 works identically.)

Measured composition for the .bbmodel path (bytecode, MTR 4.0.5):
`OptimizedModel$MaterialGroup.lambda$addCube$0` and
`ModelPropertiesPart.lambda$renderNormal$25` both do `translate(t)` and THEN
`rotateY(180)` when flipped, i.e. world = R*v + t, and `addBox` agrees
(unflipped keeps x and negates the stored box z; flipped does the opposite,
and the stored box z is itself -bb_z). So, with a leaf modelled symmetric
about bb z = 0 and at bb x > 0:

    unflipped {z:Z}  ->  centre at vehicle z = Z/16, on the +x side
      flipped {z:Z}  ->  centre at vehicle z = Z/16, on the -x side

Both land at +Z — this is the opposite of the .obj path, where a flipped entry
lands at MINUS Z (see the FLIPPED-Z TRAP in convert_openbve_m7.py). That is
why `door_rear_b` carries {z:-112} and not {z:+112}.

The multiplier sign is `copySign(|curve|, m * (flipped ? -1 : +1))` applied to
the translation, i.e. in vehicle space, so the effective slide direction is
`sign(m)` unflipped and `-sign(m)` flipped. Every leaf must retract towards
its own end of the car, so the effective direction must match `sign(Z)`:

    front_a  +24 unflipped -> +z   at z=+112  OK
    front_b  -24 flipped   -> +z   at z=+112  OK
    rear_a   -24 unflipped -> -z   at z=-112  OK
    rear_b   +24 flipped   -> -z   at z=-112  OK

`convert_openbve_m7.py --check` re-derives that table from the shipped JSON.

The four .obj apertures are 4-fold symmetric (both sides x both ends, from the
`door` definition's symmetric both-lists), so this is immune to the unknown
sign relating the .obj authoring frame to vehicle space: whichever way it goes,
each leaf lands on an aperture and retracts towards the nearer end.

THE DISPLAYS THIS MODEL ALSO CARRIES
------------------------------------
MTR 4.0.5 cannot render a `type: DISPLAY` part on an .obj at all (see below),
so every screen on this train is an element in THIS file:

    group                        type          definition   where
    front_destination_display    DESTINATION   bbEnd1/2     cab ends only
    front_route_color            ROUTE_COLOR   bbEnd1/2     cab ends only
    interior_next_station        NEXT_STATION  bbPartition  4 per car
    side_destination_display     DESTINATION   bbSideSign   4 per car

The side sign is the one that is NOT x-symmetric — it is authored on the +x
bodyside and rotated to aim at it — so its definition's `positionsFlipped`
entries are doing real work: a flip is a 180-degree turn about Y, which is
exactly the transform the SHELL uses to put its one-sided bodyside (and the
housing painted on it) on the other side of the car. `verify_side_sign()`
bakes all four placements and proves they coincide.

BOX UV — THE PART THAT HAS TO BE EXACT
--------------------------------------
`BlockbenchElement.setModelPart` calls `ModelPartExtension.setTextureUVOffset`
and then vanilla `ModelPartBuilder.cuboid(...)`, so the unwrap is vanilla's
`ModelPart$Cuboid` and **per-face `uv` dictionaries are ignored** — only
`uv_offset` + the element's size decide where the art is sampled from.

Element sizes are `Math.round(to - from)` INTEGERS (positions may be
fractional), so a leaf is 1 x 33 x 24 units on the 26-block car, never 32.5
tall — and the leaf's z size is rounded UP off the aperture, see LEAF_DZ.

For offset (u,v) and size (dx,dy,dz) vanilla lays out, in texture units:

    down   [u+dz     , u+dz+dx  ] x [v   , v+dz     ]
    up     [u+dz+dx  , u+dz+2dx ] x [v   , v+dz     ]
    WEST   [u        , u+dz     ] x [v+dz, v+dz+dy  ]
    NORTH  [u+dz     , u+dz+dx  ] x [v+dz, v+dz+dy  ]
    EAST   [u+dz+dx  , u+2dz+dx ] x [v+dz, v+dz+dy  ]
    SOUTH  [u+2dz+dx , u+2dz+2dx] x [v+dz, v+dz+dy  ]

`setModelPart` builds the cuboid at (-to.x, -to.y, +from.z), so model-part x
and y are NEGATED against the .bbmodel frame and z is not. Therefore vanilla
WEST is the bb +x face (OUTBOARD, the one a passenger on the platform sees)
and vanilla EAST is the bb -x face (INBOARD). Verified against MTR's own
r179.png: `door_left_exterior` (uv_offset 0,136, dx0 dy21 dz12) carries its
door art in [0,12]x[148,169] — the WEST rect — and `door_left`'s interior box
(uv_offset 52,294, dx1 dy21 dz12) carries its art in [65,77]x[306,327], the
EAST rect, with the outboard rect left blank behind the exterior plane.

Vertical: the WEST/EAST quads put texture v = v+dz at the cuboid's minimum
model y, which is the bb TOP — art is authored upright, no flip.
Horizontal: the WEST rect's left edge is bb max z and the EAST rect's left
edge is bb min z, which for viewers outside and inside respectively is each
one's own left. Art goes in unmirrored on both.

MIRRORING: vanilla mirrors the cuboid when `mirror` is set, and MTR passes
`mirror = !shade || mirror_uv`. `BlockbenchElementSchema` defaults `shade` to
TRUE and `mirror_uv` to false, so the default is NOT mirrored — but this file
writes both keys explicitly rather than trusting a default.

RESOLUTION: `resolution` in the .bbmodel is the divisor for the UV numbers;
the PNG itself may be any multiple of it (MTR's own r179 declares 368 and
ships 1472, a 4x). This model declares the two box-UV footprints side by side
(98 x 57 at the 26-block car's leaf size, derived, not typed) and ships 8x
that, so the art is at 8 texels per model unit instead of 1.
"""

import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import convert_openbve_m7 as B
import m7_art as A
import pixel_kit as K
import m7_layout as L
import pngtool

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")
MODEL_PATH = os.path.join(OUR_NS, "models/vehicle/m7_doors.bbmodel")
TEX_PATH = os.path.join(OUR_NS, "textures/vehicle/m7/doors_box.png")

DONOR = A.DONOR

# THE BODY CONVERTER IS IMPORTED ON PURPOSE. A door leaf is a piece of the
# bodyside that happens to slide: the blue belt stripe, the panel seams and
# the stainless tones have to be the ones the bodyside is drawing, at the same
# heights, or the livery steps at every opening. `B` is only ever asked for
# those — `side_glazing_span()` and the donor's leaf measurements — never for
# geometry. The import is cheap: convert_openbve_m7 parses nothing at import
# time. Run order is unchanged (body first, then this).

# ------------------------------------------------------- pocket geometry
#
# All in .bbmodel units (1/16 block), y = 0 at the car floor, the same frame
# the body .obj and the interior .obj are authored in. z comes from
# tools/m7_layout.py — the SAME door bay the body converter cuts the aperture
# with, so the leaf resizes with the car.
#
# THE LEAF IS A POCKET DOOR (user request, 2026-07-28). It slides INTO the
# body, in the cavity between the exterior skin and the interior lining, not
# along the outside. Closed it fills the aperture, recessed just behind the
# rubber reveal; open it disappears behind the opaque bodyside.
#
# THAT CAVITY IS ~1.48 UNITS DEEP AND IT LEANS. The M7 has tumblehome: the
# bodyside is |x| 22.26 at the sill, bulges to 23.30 at the waist and comes
# back to 22.05 at the head, and the lining tracks it 1.48 inboard. So the
# corridor a leaf may occupy at EVERY height at once is only
#
#     [ max lining over the leaf , min skin over the leaf ]  =  0.23 units
#
# — far too thin for a 1-unit box, and a single flat plane in it would sit
# 1.3 units behind the skin at the waist and 0.1 behind it at the head, which
# reads as a door hung at the wrong angle. So the leaf is a STACK OF FLAT
# PLANES, one per height band, each centred in its own band's corridor: it
# tracks the tumblehome in steps and keeps a comfortable clearance on both
# sides. `pocket_bands()` derives the whole thing by MEASURING the two shipped
# .obj artifacts, so it re-derives itself if either model changes.
#
# Two planes per band, not one: the outboard plane is EXTERIOR stage (world
# light — it is what the platform sees through the aperture) and the inboard
# plane is INTERIOR stage (full bright — it is what a rider sees through the
# saloon's own door opening). Without the split the leaf is either a black
# panel in a lit saloon at night or a glowing one seen from the platform.
# This is r179's structure; ours differs only in that both are planes.

LEAF_DZ = int(math.ceil(abs(L.DOOR_BAY.z(L.DOOR_APERTURE_A)
                            - L.DOOR_BAY.z(L.DOOR_APERTURE_B))))
LEAF_DY = 33            # 2.06 blocks, covers the 32.41-unit opening height
LEAF_Z0 = -LEAF_DZ / 2.0
LEAF_Y0 = 0.0

# The generator raises rather than shipping a leaf that clips the body or the
# saloon, so these two are the real contract with the other two converters.
MIN_CORRIDOR = 0.60     # units of skin-to-lining room a band must have
STAGE_SPLIT = 0.40      # of the corridor, between the EXTERIOR and INTERIOR planes
MAX_BANDS = 8

BODY_OBJ = os.path.join(OUR_NS, "models/vehicle/m7.obj")
INTERIOR_OBJ = os.path.join(OUR_NS, "models/vehicle/m7_interior.obj")

# The DOORWAY box and the FLOOR plates. MTR only reads floors and doorways out
# of a .bbmodel — an .obj contributes NEITHER (see the docstring) — and it
# synthesizes a fallback when both sets are empty, which is what used to let
# passengers board anywhere along the car. These retire that fallback.
#
# They are DATA, not scenery: `ModelPropertiesPart.render` switches on
# PartType and only NORMAL and DISPLAY have a case — FLOOR, DOORWAY and SEAT
# fall through to `default: return`, so none of these boxes is ever drawn.
# That is also why they need no texture and why their uv_offset is (0, 0).
DOORWAY_X = (20.0, 24.0)          # inboard of the lining out past the skin,
                                  # the same span MTR's own fallback used
FLOOR_HALF_X = 21                 # just inside the lining, integer size 42

# PNG texels per model unit. 3 puts the leaf at 48 px per block, the same
# density band the bodyside elevation is drawn at (40) and the range MTR's own
# R179/R211 art sits in. It also makes the atlas EXACT: a band's blit rect is
# (band height) x TEX_SCALE texels and the art is drawn at LEAF_DY x TEX_SCALE
# rows, so `band_slice` hands `blit` a tile that is already the right size and
# the nearest-neighbour resample is the identity. Change this and that stops
# being true — the art will still land, just resampled.
TEX_SCALE = 3


# --------------------------------------------------------------- measuring

def load_obj(path):
    """group -> faces, in the M frame (obj = (-Mx, +My, -Mz) / 16)."""
    if not os.path.exists(path):
        raise SystemExit("%s is missing — run its converter first (the order is "
                         "body, interior, doors, assets)" % os.path.basename(path))
    verts, groups, current = [], {}, None
    for line in open(path):
        if line.startswith("v "):
            x, y, z = (float(t) for t in line.split()[1:4])
            verts.append((-16.0 * x, 16.0 * y, -16.0 * z))
        elif line.startswith("g "):
            current = line[2:].strip()
        elif line.startswith("f ") and current:
            groups.setdefault(current, []).append(
                [verts[int(t.split("/")[0]) - 1] for t in line.split()[1:]])
    return groups


def extreme_x(faces, ylo, yhi, pick):
    """|x| extreme of a face set within a height band.

    Edges are interpolated, not just sampled at vertices: these models only
    carry vertices at the cross-section's knee points, so a band can easily
    contain none at all and still be crossed by the surface.
    """
    values = []
    for face in faces:
        n = len(face)
        for i in range(n):
            (x0, y0, _), (x1, y1, _) = face[i], face[(i + 1) % n]
            if ylo - 1e-6 <= y0 <= yhi + 1e-6:
                values.append(abs(x0))
            for edge_y in (ylo, yhi):
                if (y0 - edge_y) * (y1 - edge_y) <= 0 and abs(y1 - y0) > 1e-9:
                    t = (edge_y - y0) / (y1 - y0)
                    values.append(abs(x0 + (x1 - x0) * t))
    return pick(values) if values else None


def band_split(count):
    """`count` bands over the leaf, as equal as integer heights allow."""
    base, extra = divmod(LEAF_DY, count)
    heights = [base + (1 if i >= count - extra else 0) for i in range(count)]
    edges, y = [0], 0
    for h in heights:
        y += h
        edges.append(y)
    return list(zip(edges, edges[1:]))


def pocket_bands():
    """The leaf, as (ylo, yhi, x_exterior, x_interior) height bands.

    Measured off the shipped artifacts rather than off either converter's
    constants, so this tracks whatever those two actually emit:

      skin    max |x| per height of the body's `window_exterior` — a bay with
              no aperture and no reveal, so its widest surface at a given
              height IS the bodyside. (Measuring the DOOR bay instead would
              pick up the reveal stepping inboard and read far too narrow.)
      lining  max |x| of anything in the interior model. The side wall is the
              widest thing in there by a margin, so no filtering is needed,
              and taking the max over every bay is deliberately conservative:
              an open leaf slides out of the door bay and into the end bay.
    """
    body = load_obj(BODY_OBJ)
    interior = load_obj(INTERIOR_OBJ)
    if "window_exterior" not in body:
        raise SystemExit("m7.obj has no window_exterior group to measure")
    skin_faces = body["window_exterior"]
    lining_faces = [f for faces in interior.values() for f in faces]

    for count in range(1, MAX_BANDS + 1):
        bands, ok = [], True
        for (ylo, yhi) in band_split(count):
            # The skin is sampled across the band, not just at its edges: it
            # is piecewise linear with knees inside a band.
            skin = min(extreme_x(skin_faces, y, y, max)
                       for y in [ylo + i * (yhi - ylo) / 40.0 for i in range(41)])
            lining = extreme_x(lining_faces, ylo, yhi, max)
            corridor = skin - lining
            if corridor < MIN_CORRIDOR:
                ok = False
                break
            centre, gap = (skin + lining) / 2.0, corridor * STAGE_SPLIT / 2.0
            bands.append((ylo, yhi, round(centre + gap, 4), round(centre - gap, 4),
                          round(corridor, 4)))
        if ok:
            return bands
    raise SystemExit(
        "no band split up to %d gives every band %.2f units of skin-to-lining "
        "room — the body or the interior moved, and a pocket door no longer "
        "fits between them" % (MAX_BANDS, MIN_CORRIDOR))


BANDS = pocket_bands()

LEAVES = ("door_front_a", "door_front_b", "door_rear_a", "door_rear_b")


# ------------------------------------------------------------------ box uv

def box_uv_rects(u, v, dx, dy, dz):
    """Vanilla ModelPart$Cuboid's unwrap, in texture units.

    Keys are in the .bbmodel frame: `outboard` is bb +x (vanilla WEST) and
    `inboard` is bb -x (vanilla EAST) — see the module docstring for why the
    two are swapped relative to the direction names.
    """
    return {
        "down": (u + dz, v, u + dz + dx, v + dz),
        "up": (u + dz + dx, v, u + dz + 2 * dx, v + dz),
        "outboard": (u, v + dz, u + dz, v + dz + dy),
        "zmin": (u + dz, v + dz, u + dz + dx, v + dz + dy),
        "inboard": (u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy),
        "zmax": (u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy),
    }


def footprint(dx, dy, dz):
    return 2 * dz + 2 * dx, dz + dy


# Every leaf plane is dx = 0 and dz = LEAF_DZ, so a band's footprint depends
# only on its height — and the EXTERIOR and INTERIOR planes of a band are the
# same size carrying the same art, so they SHARE one uv_offset. The atlas is
# therefore one footprint per band, laid left to right.
BAND_UV, _u = [], 0
for _ylo, _yhi, _xe, _xi, _c in BANDS:
    BAND_UV.append((_u, 0))
    _u += footprint(0, _yhi - _ylo, LEAF_DZ)[0]
RES_W = _u
RES_H = max(footprint(0, b[1] - b[0], LEAF_DZ)[1] for b in BANDS)


# ----------------------------------------------------------------- texture

def resample(rows, w, h):
    """Nearest-neighbour resample to exactly (w, h). Deterministic, and the
    reason the verification pass can compare pixels for exact equality."""
    sh, sw = len(rows), len(rows[0])
    return [[rows[y * sh // h][x * sw // w] for x in range(w)] for y in range(h)]


def band_slice(art, ylo, yhi):
    """The rows of a full-height door skin that belong to one height band.

    Image row 0 is the TOP of the leaf and y = 0 is its BOTTOM, so the band
    runs from the top of the image downwards by (1 - yhi/LEAF_DY).
    """
    h = len(art)
    r0 = int(round(h * (1.0 - yhi / float(LEAF_DY))))
    r1 = int(round(h * (1.0 - ylo / float(LEAF_DY))))
    return art[max(0, r0):max(r0 + 1, r1)]


# ---------------------------------------------------------------- leaf art
#
# THE LEAF WINDOW IS A REAL HOLE (art pass 2026-07-28). A .bbmodel cannot
# carry a translucent material — MTR's Blockbench path has no place to put a
# `#interior_translucent` flag — so the saloon's glazing trick is not
# available here. What IS available is CUTOUT, which both of a leaf's render
# stages use, so the window is cut clean through BOTH planes and reads as an
# open window into the vestibule. That is why the two planes' art has to be
# drawn symmetric about the leaf's centre: the box unwrap gives the outboard
# rect and the inboard rect OPPOSITE handedness in bb z (see BOX UV above), so
# only a symmetric leaf is guaranteed to have its two holes line up in space.

# The leaf is drawn ONCE at full size and sliced into bands. Drawing it at
# exactly the atlas's own density makes every band's resample the identity —
# see the TEX_SCALE note.
LEAF_ART_W = LEAF_DZ * TEX_SCALE
LEAF_ART_H = LEAF_DY * TEX_SCALE


def leaf_row_of_y(y_m):
    """donor metres above the rail -> row in the leaf art.

    The box unwrap is linear in the leaf's own height, so this is too: the
    leaf spans 0..LEAF_DY model units and `sy` is the same donor->unit map the
    bodyside uses. Sharing `sy` is what puts the belt stripe at exactly the
    height the bodyside puts it at, on both sides of every opening.
    """
    return LEAF_ART_H * (1.0 - L.sy(y_m) / float(LEAF_DY))


def leaf_window_rect():
    """The leaf's glazing, from Puerta.png's own alpha-156 mark.

    Returns (x0, y0, x1, y1) in art pixels, CENTRED horizontally. The donor's
    box is 0.164 in from one edge and 0.179 from the other; squaring that up
    costs 1 px of a 47 px window and buys the guarantee above.
    """
    (u0, u1, v0, v1) = A.donor_glazing("Puerta.png", 400)[0]
    half = (u1 - u0) / 2.0 * LEAF_ART_W
    cx = LEAF_ART_W / 2.0
    # Puerta.png's v runs 0 at the door head (donor y 3.270) to 1 at the sill
    # (1.295), through the leaf's own profile — the same table the .obj's
    # LEAF_PTS carries, so invert that rather than assuming it is linear.
    y_top = B.invert_profile([(y, x, v) for (x, y, v) in B.LEAF_PTS], v0)
    y_bot = B.invert_profile([(y, x, v) for (x, y, v) in B.LEAF_PTS], v1)
    return (cx - half, leaf_row_of_y(y_top), cx + half, leaf_row_of_y(y_bot))


def draw_leaf(interior_side):
    """One face of a leaf: the bodyside's livery, or the vestibule's lining.

    The platform sees `interior_side=False` — stainless, the blue belt stripe
    running through at exactly the bodyside's height, a leading-edge shadow.
    A rider sees `interior_side=True` — a plain lining panel with a dark kick
    plate, no livery, because the inside of a door is not painted.

    Both get the SAME window hole at the SAME pixels, which is the whole
    point: aligned holes read as an open window, misaligned ones read as a
    hole into the thickness of the door.
    """
    win_top, win_bot = B.side_glazing_span()
    if interior_side:
        rows = K.canvas(LEAF_ART_W, LEAF_ART_H, A.END_PANEL)
        K.hband(rows, leaf_row_of_y(A.STRIPE_BOT_Y), LEAF_ART_H, A.END_PANEL_LO)
        K.hband(rows, leaf_row_of_y(A.DOOR_SILL_Y + 0.28), LEAF_ART_H, A.DIAPHRAGM)
    else:
        rows = K.canvas(LEAF_ART_W, LEAF_ART_H, A.STEEL)
        A.stainless_field(rows, leaf_row_of_y, win_top, win_bot)

    x0, y0, x1, y1 = leaf_window_rect()
    # Surround, chamfer, then the hole. Same three nested rounded rects
    # `m7_art.pane` uses, with alpha 0 in place of marked glass — a leaf is
    # CUTOUT on both stages, so a hole is all there is to work with.
    K.rrect(rows, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 5, A.GASKET_HI)
    K.rrect(rows, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 4, A.GASKET)
    K.rrect(rows, x0, y0, x1, y1, 3, (0, 0, 0, 0))

    # The leaf's own edges. Without them the leaf blends into the bodyside so
    # completely (which is the point of drawing both from one palette) that
    # there is no door left to see: a dark edge with a lit chamfer just inside
    # is what a near-flush pocket door actually looks like.
    for x, inner in ((0, 1), (LEAF_ART_W - 1, LEAF_ART_W - 2)):
        K.vline(rows, x, 0, LEAF_ART_H, A.GASKET)
        K.vline(rows, inner, 0, LEAF_ART_H,
                A.END_POST if interior_side else A.STEEL_HI)
    K.rect(rows, 0, LEAF_ART_H - 1, LEAF_ART_W, LEAF_ART_H, A.GASKET)
    if not interior_side:
        # The pocket-side grab recess, at hand height.
        K.rect(rows, 2, leaf_row_of_y(2.35), 4, leaf_row_of_y(2.10), A.STEEL_LO)
    return rows


def load_leaf_art():
    """(outboard art, inboard art), both LEAF_ART_W x LEAF_ART_H, drawn.

    Kept under the old name because `verify()` uses it to re-derive the band
    partition off the same rows the atlas was built from.
    """
    return draw_leaf(False), draw_leaf(True)


def blit(canvas, rect, art):
    """Draw `art` into a model-unit rect, resampled to the rect's texel size."""
    x0, y0, x1, y1 = (int(round(c * TEX_SCALE)) for c in rect)
    if x1 <= x0 or y1 <= y0:
        return None
    tile = resample(art, x1 - x0, y1 - y0)
    for y in range(y0, y1):
        canvas[y][x0:x1] = tile[y - y0]
    return (x0, y0, x1, y1), tile


def build_texture():
    exterior, interior = load_leaf_art()
    # The atlas background. Only the leaf's zero-width edge faces and the
    # unused corners ever sample it, so one flat stainless is right — and it
    # is the bodyside's own tone, not something measured off a photograph.
    base = K.rgba(A.STEEL)
    canvas = [[base] * (RES_W * TEX_SCALE) for _ in range(RES_H * TEX_SCALE)]
    placed = {}
    for i, (ylo, yhi, _xe, _xi, _c) in enumerate(BANDS):
        u, v = BAND_UV[i]
        rects = box_uv_rects(u, v, 0, yhi - ylo, LEAF_DZ)
        placed["band%d.outboard" % i] = blit(canvas, rects["outboard"],
                                             band_slice(exterior, ylo, yhi))
        placed["band%d.inboard" % i] = blit(canvas, rects["inboard"],
                                            band_slice(interior, ylo, yhi))
    return canvas, placed, base


# ----------------------------------------------------------------- bbmodel

def uuid_for(index):
    """Deterministic, so re-running produces a byte-identical model."""
    return "m7d0e%03d-0001-0002-0003-%012d" % (index, index)


def element(name, uuid, frm, to, uv_offset):
    dx = int(round(to[0] - frm[0]))
    dy = int(round(to[1] - frm[1]))
    dz = int(round(to[2] - frm[2]))
    r = box_uv_rects(uv_offset[0], uv_offset[1], dx, dy, dz)
    # `faces` is written for Blockbench's sake only — MTR reads uv_offset and
    # the element size and nothing else. The names are Blockbench's, whose x
    # axis is mirrored against vanilla's model space, so its `east` is the
    # vanilla WEST rect (checked against r179.bbmodel).
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
        "name": name,
        "box_uv": True,
        "rescale": False,
        "locked": False,
        "render_order": "default",
        "allow_mirror_modeling": True,
        "from": list(frm),
        "to": list(to),
        # Explicit rather than defaulted: MTR computes the vanilla `mirror`
        # flag as (!shade || mirror_uv), and mirroring would swap the two big
        # faces and flip the art.
        "shade": True,
        "mirror_uv": False,
        "autouv": 0,
        "color": 0,
        "inflate": 0,
        "origin": [0, 0, 0],
        "uv_offset": list(uv_offset),
        "faces": faces,
        "type": "cube",
        "uuid": uuid,
    }


# ⭐ THE DESTINATION DISPLAYS LIVE IN THIS FILE, NOT IN THE .obj.
#
# MTR 4.0.5 CANNOT RENDER A `type: DISPLAY` PART ON AN .obj MODEL. Verified in
# bytecode 2026-07-28 (full write-up in M7_CONVERSION_NOTES.md), two
# independent blockers:
#
#   1. `ModelPropertiesPart.lambda$writeCache$13` — the per-position callback
#      of the .obj `writeCache` overload — opens with
#      `if (type != PartType.NORMAL) return;`. A DISPLAY part is dropped before
#      anything happens, so it registers nothing AND its mesh is never even
#      added to a material group. The .bbmodel overload switches on the type
#      instead and routes DISPLAY to `lambda$writeCache$3`, the sole writer of
#      `displayPartDetailsList`. On an .obj that list stays empty forever, and
#      all four display renderers end in `displayPartDetailsList.forEach(...)`
#      — a silent no-op, no log line.
#   2. Even without blocker 1 there is nothing to draw ON: `ModelDisplayPart`
#      is constructed in exactly one place in the jar
#      (`DynamicVehicleModel.lambda$new$1`, reachable only from the
#      BlockbenchModel constructor) and filled by `BlockbenchElement
#      .setModelPart` purely from an element's `from`/`to`/`origin`/`rotation`.
#      An OBJ mesh has no cuboid from/to, and there is no bounding-box
#      fallback.
#
# Same family of defect as the door leaves and the FLOOR/DOORWAY parts: MTR's
# .obj path is unexercised upstream. So the displays join the leaves here, in
# the model format that works, and are bound only in the CAB-end properties.
#
# GEOMETRY CONVENTION, read out of r179 (the whole reference corpus — MTR ships
# five DISPLAY parts and they are all r179's):
#   * a display element is a ZERO-THICKNESS plate: from[2] == to[2].
#   * MTR takes the text plane to be the element's MINIMUM-Z face, and the text
#     faces -Z and is read from the -Z side. Our end bays are authored with
#     their outward face at local -z, so a plate on the cab mask faces out with
#     no rotation at all — exactly as r179's `front_route_number_display`
#     (from [-4,35,-26], to [4,39,-26]) sits on its own -Z nose.
#   * width = round(to[0]-from[0]) and height = round(to[1]-from[1]), in whole
#     model pixels, and those integers ARE the text canvas — so the sizes in
#     m7_layout.SIGN_* are chosen to round exactly.
#   * NO `uv_offset` key at all, and `visibility: false` — both copied from
#     r179: a DISPLAY part never draws geometry, so it has no UV and Blockbench
#     hides it. ⭐ OMIT the key; writing `null` throws inside MTR's parser (see
#     `display_element`).
DISPLAYS = (
    # (element name, (y_lo, y_hi) in M units, suffix, cab bay)
    ("front_destination_display", L.SIGN_DEST_Y, "", L.CAB_BAY),
    ("front_route_color", L.SIGN_COLOR_Y, "", L.CAB_BAY),
    ("front_destination_display_mini", L.SIGN_DEST_Y, "_mini", L.CAB_BAY_MINI),
    ("front_route_color_mini", L.SIGN_COLOR_Y, "_mini", L.CAB_BAY_MINI),
)


# The exterior side destination sign. ONE element, FOUR signs: `bbSideSign`
# carries both door centres in BOTH position lists, and a flipped .bbmodel
# instance is a 180-degree turn about Y — which is exactly the transform that
# takes the +x bodyside to the -x one. See side_sign_placements().
SIDE_DISPLAY = "side_destination_display"


def side_display_element(name, uuid):
    """A destination display AIMED AT THE CAR SIDE, r179's way.

    ⭐ A DISPLAY element's text always faces its own -Z. To aim one at the
    bodyside you ROTATE it, and r179 is the only worked example in MTR's
    corpus: `side_destination_display_1` is `from [11.5, 27.25, 0.1]` to
    `[27.5, 30.25, 0.1]`, `origin [21.5, 13, 0]`, `rotation [0, -90, 6]`, with
    a mirrored twin at `origin [-21.5, ...]` and `rotation [0, 90, -6]`.

    ⭐ THE YAW DECODE, and the trap in it. A rotation acts on coordinates
    RELATIVE TO THE ORIGIN, so what matters is `from - origin`, never `from`:

        x' = dx*cos(yaw) + dz*sin(yaw)      z' = -dx*sin(yaw) + dz*cos(yaw)

    At yaw -90 that is x' = -dz and z' = +dx, so for the +x side:
      * the element's authored X becomes the ALONG-CAR axis. Its width — the
        integer MTR rounds out of `to[0]-from[0]` and uses as the text canvas —
        is therefore the sign's LENGTH.
      * the authored Z becomes -x, so a NEGATIVE dz is OUTBOARD. r179 uses
        dz = +0.1 and lands 0.1 INBOARD of its origin, because its sign is
        behind a window; ours is on the skin, so ours is `-STANDOFF`.
      * the -Z normal turns into +X. Outward.

    Both offsets are therefore measured FROM THE ORIGIN, which is why this
    element is authored r179's way: origin z = 0 with the along-car position
    carried in `from[0]`/`to[0]`, and `from[2] = to[2] = -STANDOFF`. Writing
    the along-car centre into `origin[2]` instead (the obvious-looking
    arrangement) makes dz = standoff - centre — 16.5 units of it — and the
    plate lands that far INSIDE the car, with nothing logged and no text
    visible from outside. `side_sign_placements()` is the guard.

    ⭐ NO ROLL, deliberately, and this is the one place we do NOT copy r179.
    Its +-6 is the tumblehome of a body that leans 6 degrees; the M7's side
    leans 3.1 degrees, and over a 2-unit-tall plate that is 0.11 units of
    lean — less than the standoff. Leaving the roll at zero keeps the element
    out of Blockbench's Euler-composition question (which of Y and Z is
    applied first changes where a rolled plate lands, and there is no second
    worked example in the jar to settle it), and `m7_layout.side_sign_skin_x`
    takes the skin's WIDEST point over the plate's height so the whole plate
    is proud of the body without one.

    Authored for the +x side ONLY. The -x side is the same element through
    `positionsFlipped`, which is both the one-sided-list idiom the leaves use
    and the only arrangement that puts the sign on the panel the bodyside
    actually paints its housing on at BOTH doors — see side_sign_placements().
    """
    half = L.SIDE_SIGN_HALF_LEN
    y_lo, y_hi = L.SIDE_SIGN_Y
    ox = L.side_sign_skin_x()               # +x side; the flip supplies -x
    centre = L.DOOR_BAY.z(L.SIDE_SIGN_Z_M)  # bay-local, along the car
    el = display_element(name, uuid,
                         (ox + centre - half, y_lo, -L.SIDE_SIGN_STANDOFF),
                         (ox + centre + half, y_hi, -L.SIDE_SIGN_STANDOFF))
    el["origin"] = [ox, (y_lo + y_hi) / 2.0, 0.0]
    el["rotation"] = [0, -90, 0]
    return el


def side_sign_placements(el, door_centres):
    """Decode where the shipped side-sign element ACTUALLY lands, on all four
    corners of the car, through MTR's own composition rules.

    Returns one dict per placement: the side it ended up on, the constant x of
    its plate, the along-car span of the text, and the direction the text
    faces. Everything is read back out of the element as written, so this
    catches an origin/from mix-up (which puts the plate inside the car) or a
    definition whose flipped list lands the sign on the panel the bodyside
    never painted.

    The two composition rules, both established in M7_CONVERSION_NOTES.md:
      .bbmodel  translate(t) THEN rotateY(180) when flipped, i.e. world = R*v+t
                — `ModelPropertiesPart.lambda$renderDisplay$41` does exactly
                that for a DISPLAY part, the same order as `lambda$addCube$0`
                does for geometry.
      .obj      the opposite order, world = R*(v+t), which is why the shell's
                flipped entries land at MINUS z.
    """
    yaw = math.radians(el["rotation"][1])
    cos, sin = math.cos(yaw), math.sin(yaw)
    ox, _oy, oz = el["origin"]

    def turn(p):
        dx, dz = p[0] - ox, p[2] - oz
        return (ox + dx * cos + dz * sin, p[1], oz - dx * sin + dz * cos)

    out = []
    for flipped in (False, True):
        for t in door_centres:
            corners = []
            for p in (el["from"], el["to"]):
                x, y, z = turn(p)
                if flipped:
                    x, z = -x, -z
                corners.append((x, y, z + t))
            nx, _ny, nz = turn((ox + 0.0, 0.0, oz - 1.0))   # the -Z normal
            nx, nz = nx - ox, nz - oz
            if flipped:
                nx, nz = -nx, -nz
            out.append({
                "door": t,
                "flipped": flipped,
                "x": corners[0][0],
                "x_far": corners[1][0],
                "y": tuple(sorted((corners[0][1], corners[1][1]))),
                "z": tuple(sorted((corners[0][2], corners[1][2]))),
                "normal": (nx, nz),
            })
    return out


def display_element(name, uuid, frm, to):
    """A flat text-anchor plate. Deliberately NOT `element()`: box UV would be
    meaningless on a zero-thickness plate and MTR never reads it for a DISPLAY
    part, so this matches r179's own shape instead — no uv_offset, no faces,
    hidden in Blockbench.

    ⭐ The `uv_offset` KEY IS OMITTED, never written as null (fixed 2026-07-30).
    An earlier revision wrote `"uv_offset": None` believing it copied r179. It
    does not: across MTR's 30+ vehicle .bbmodels, 6771 elements carry a real
    list and 682 OMIT the key — ZERO write null. A JSON null becomes gson's
    `JsonNull`, which survives `ReaderBase.unpackValue`'s Java-null guard and
    then throws `IllegalStateException: Not a JSON Array: null` out of
    `BlockbenchElementSchema.updateData`'s `iterateLongArray`. It is caught and
    logged with a full stack trace, so the geometry still loads, but every
    model build spams the render thread. Omitting the key is a clean no-op.
    """
    return {
        "name": name,
        "box_uv": True,
        "rescale": False,
        "locked": False,
        "render_order": "default",
        "allow_mirror_modeling": True,
        "from": list(frm),
        "to": list(to),
        "shade": True,
        "mirror_uv": False,
        "autouv": 0,
        "color": 0,
        "inflate": 0,
        "origin": [0, 0, 0],
        # NO "uv_offset" KEY — see the docstring. Never write null here.
        "faces": {},
        "visibility": False,
        "type": "cube",
        "uuid": uuid,
    }


def group(name, index, *children):
    return {
        "name": name,
        "origin": [0, 0, 0],
        "color": 0,
        "uuid": "m7dg%03dg-0001-0002-0003-%012d" % (index, index),
        "export": True,
        "mirror_uv": False,
        "isOpen": False,
        "locked": False,
        "visibility": True,
        "autouv": 0,
        "children": list(children),
    }


# The FLOOR plates, in bay-local units. Each one stops where its bay stops, so
# laid end to end they tile the whole walkable car with no step between bays.
# The end plate is NOT symmetric — the nose and the diaphragm eat the outer
# few units — which is exactly why its definitions cannot be the .obj's
# `end1`/`end2` (see gen_m7_assets.py: bbEnd* carry the .bbmodel's own sign).
def floor_plates():
    end_in, end_out = -29.0, L.END_UNITS / 2.0
    mini_in, mini_out = -22.5, L.MINI_END_UNITS / 2.0
    return (
        ("floor_window", -L.WINDOW_UNITS / 2.0, L.WINDOW_UNITS / 2.0),
        ("floor_door", -L.DOOR_UNITS / 2.0, L.DOOR_UNITS / 2.0),
        ("floor_end", end_in, end_out),
        ("floor_end_mini", mini_in, mini_out),
    )


def build_model():
    elements, outliner, index = [], [], 0

    def add(name, frm, to, uv_offset=(0, 0)):
        uuid = uuid_for(len(elements))
        elements.append(element(name, uuid, frm, to, uv_offset))
        return uuid

    for leaf in LEAVES:
        # The bare name is the INTERIOR-stage group (the inboard plane, what a
        # rider sees); `_exterior` is the outboard one the platform sees.
        for stage in ("", "_exterior"):
            kids = []
            for i, (ylo, yhi, x_ext, x_int, _c) in enumerate(BANDS):
                x = x_ext if stage else x_int
                kids.append(add("%s%s_b%d" % (leaf, stage, i),
                                (x, ylo, LEAF_Z0), (x, yhi, LEAF_Z0 + LEAF_DZ),
                                BAND_UV[i]))
            outliner.append(group(leaf + stage, len(outliner), *kids))

    # The aperture, as data. One group placed by the `door` definition's four
    # entries, so each of the four openings gets its own box and `mapDoors`
    # can bind every leaf to the one on its own side at its own end.
    outliner.append(group("doorway_box", len(outliner),
                          add("doorway", (DOORWAY_X[0], 0.0, LEAF_Z0),
                              (DOORWAY_X[1], 0.0, LEAF_Z0 + LEAF_DZ))))
    for name, z0, z1 in floor_plates():
        outliner.append(group(name, len(outliner),
                              add(name, (-FLOOR_HALF_X, 0.0, z0),
                                  (FLOOR_HALF_X, 0.0, z1))))

    # The cab destination sign. One group per element, because a properties
    # part binds by GROUP name and the two displays need different types.
    half = L.SIGN_HALF_X
    for name, (y_lo, y_hi), _suffix, bay in DISPLAYS:
        z = L.sign_plane_z(bay)
        uuid = uuid_for(len(elements))
        elements.append(display_element(name, uuid,
                                        (-half, y_lo, z), (half, y_hi, z)))
        outliner.append(group(name, len(outliner), uuid))

    # ⭐ THE EXTERIOR SIDE DESTINATION SIGNS, in the letterboard beside each
    # door. ONE element covers all four (two per side): it is authored for the
    # +x side and its definition `bbSideSign` lists both door centres in BOTH
    # position lists, so the flip — a 180-degree turn about Y — supplies the
    # -x side already facing outward and already on the panel the bodyside
    # painted its housing on. Both lengths use the same element, because the
    # Mini keeps the door bay at full size and only its centres move.
    uuid = uuid_for(len(elements))
    elements.append(side_display_element(SIDE_DISPLAY, uuid))
    outliner.append(group(SIDE_DISPLAY, len(outliner), uuid))

    # ⭐ THE INTERIOR NEXT-STOP SCREEN, over each vestibule aisle.
    #
    # ONE element serves all FOUR partitions in a car. It is authored on the
    # partition whose saloon face looks at -z, which is a DISPLAY element's
    # natural direction, and its definition (`bbPartition`) carries BOTH
    # `positions` and `positionsFlipped` at both door centres: the flip is a
    # 180-degree turn about Y, so it lands the same plate on the two
    # partitions whose saloon face looks the other way, already facing the
    # right direction. The element is x-symmetric, so the flip's x mirror is
    # a no-op. Housing, standoff and size all come from m7_layout.PIS_*, which
    # convert_m7_interior draws the housing from.
    pz = L.pis_plane_z()
    uuid = uuid_for(len(elements))
    elements.append(display_element(
        "interior_next_station", uuid,
        (-L.PIS_SCREEN_HALF_X, L.PIS_SCREEN_Y[0], pz),
        (L.PIS_SCREEN_HALF_X, L.PIS_SCREEN_Y[1], pz)))
    outliner.append(group("interior_next_station", len(outliner), uuid))

    return {
        "meta": {"format_version": "4.10", "model_format": "modded_entity",
                 "box_uv": True},
        "name": "m7_doors",
        "model_identifier": "",
        "visible_box": [1, 1, 0],
        "resolution": {"width": RES_W, "height": RES_H},
        "elements": elements,
        "outliner": outliner,
        "textures": [],
    }


# ------------------------------------------------------------ verification

def verify(placed, base):
    """Decode what was actually written and prove every art rectangle landed
    where the box-UV formulas say it does.

    This is the only mechanical guard on the layout: MTR ignores per-face uv
    dictionaries, so a wrong rect would show up as stainless where the door
    should be, in game, with nothing logged.
    """
    w, h, rows = pngtool.read_png(TEX_PATH)
    problems = []
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
    # The band slices must PARTITION the source art — contiguous, no overlap,
    # covering every row. A per-rect pixel compare cannot catch a slicer that
    # drops or repeats a strip, because each rect would still match its own
    # (wrong) slice. Checked on row indices, so a plain stainless band does
    # not read as a mis-aim the way a "is this row flat" heuristic did.
    art = load_leaf_art()[0]
    rows_seen, height = [], len(art)
    for (ylo, yhi, _xe, _xi, _c) in BANDS:
        r0 = int(round(height * (1.0 - yhi / float(LEAF_DY))))
        r1 = int(round(height * (1.0 - ylo / float(LEAF_DY))))
        rows_seen.append((r0, r1))
    rows_seen.sort()
    if rows_seen[0][0] != 0 or rows_seen[-1][1] != height:
        problems.append("band slices cover source rows %d..%d of %d"
                        % (rows_seen[0][0], rows_seen[-1][1], height))
    for a, b in zip(rows_seen, rows_seen[1:]):
        if a[1] != b[0]:
            problems.append("band slices overlap or gap at source row %d" % a[1])
    if not any(len(set(t[1][len(t[1]) // 2])) > 2 for t in placed.values() if t):
        problems.append("no band carries any art at all")

    # Bands must tile the leaf exactly, with no overlap and no missing strip.
    edges = [BANDS[0][0]] + [b[1] for b in BANDS]
    if edges != sorted(edges) or edges[0] != 0 or edges[-1] != LEAF_DY:
        problems.append("bands %s do not tile 0..%d" % (edges, LEAF_DY))
    for i in range(len(BANDS) - 1):
        if BANDS[i][1] != BANDS[i + 1][0]:
            problems.append("band %d and %d leave a gap" % (i, i + 1))
    return problems


def verify_side_sign():
    """Bake the side sign's four placements and prove every one of them lands
    on a housing the bodyside actually painted, facing out, proud of the skin.

    Nothing in game would report a miss: a display whose plate ends up inside
    the car, on the wrong panel or facing inward renders text that is simply
    never visible from anywhere a player stands, and MTR logs nothing. So the
    three independent facts are checked against three independent sources:

      WHERE      the placements are decoded from the SHIPPED element through
                 the .bbmodel composition rule, and compared against the
                 housing positions re-derived from the donor through the
                 .obj's (opposite) rule. The two models have to agree, and
                 they are computed by different arithmetic.
      HOW PROUD  the skin is measured off the body converter's OWN
                 `SIDE_PROFILE`, not the two-knot copy in m7_layout, so the
                 two cannot drift apart silently.
      HOW BIG    the text canvas is MTR's `Math.round(to - from)`, recomputed
                 here from the JSON.
    """
    problems, lines = [], []
    with open(MODEL_PATH) as fh:
        elements = json.load(fh)["elements"]
    found = [e for e in elements if e["name"] == SIDE_DISPLAY]
    if len(found) != 1:
        return (["%d %r element(s) in the model, expected exactly 1"
                 % (len(found), SIDE_DISPLAY)], lines)
    el = found[0]

    want_w = int(round(2 * L.SIDE_SIGN_HALF_LEN))
    want_h = int(round(L.SIDE_SIGN_Y[1] - L.SIDE_SIGN_Y[0]))
    got_w = int(round(el["to"][0] - el["from"][0]))
    got_h = int(round(el["to"][1] - el["from"][1]))
    if (got_w, got_h) != (want_w, want_h):
        problems.append("the side sign's text canvas is %dx%d, expected %dx%d"
                        % (got_w, got_h, want_w, want_h))
    if abs(el["to"][2] - el["from"][2]) > 1e-9:
        problems.append("the side sign is not a zero-thickness plate")

    def skin_x(y_units):
        """|x| of the bodyside at this height, from the body's own profile."""
        y_m = L.FLOOR_Y + y_units / L.Y_SCALE_ABOVE
        return B.interp_profile(B.SIDE_PROFILE, y_m)[0] * L.X_SCALE

    plane = L.side_sign_plane_x()
    centre = L.DOOR_BAY.z(L.SIDE_SIGN_Z_M)
    for label, doors in (("normal", L.NORMAL.door.centres),
                         ("mini", L.MINI.door.centres)):
        # Where the SHELL put its housings. Unflipped .obj entries land at
        # local + t and flipped ones at -(local + t) — the opposite order to
        # the .bbmodel, and the whole reason this check exists.
        want = {+1: sorted(centre + t for t in doors),
                -1: sorted(-(centre + t) for t in doors)}
        seen = {+1: [], -1: []}
        for p in sorted(side_sign_placements(el, doors),
                        key=lambda p: (p["flipped"], p["door"])):
            side = 1 if p["x"] > 0 else -1
            z0, z1 = p["z"]
            seen[side].append((z0 + z1) / 2.0)
            if abs(p["x"] - p["x_far"]) > 1e-9:
                problems.append("%s: the sign's plate is not a plane of "
                                "constant x (%.3f vs %.3f)"
                                % (label, p["x"], p["x_far"]))
            if abs(abs(p["x"]) - plane) > 1e-9:
                problems.append("%s: the sign sits at |x| %.3f, not the "
                                "%.3f m7_layout puts its plane at"
                                % (label, abs(p["x"]), plane))
            if abs(p["normal"][1]) > 1e-9 or p["normal"][0] * side <= 0:
                problems.append("%s: the sign's text faces (%.3f, %.3f) — not "
                                "outward" % (label, p["normal"][0],
                                             p["normal"][1]))
            if abs((z1 - z0) - 2 * L.SIDE_SIGN_HALF_LEN) > 1e-9:
                problems.append("%s: the sign spans %.3f units along the car, "
                                "not %.3f" % (label, z1 - z0,
                                              2 * L.SIDE_SIGN_HALF_LEN))
            if tuple(p["y"]) != tuple(L.SIDE_SIGN_Y):
                problems.append("%s: the sign spans y %s, not %s"
                                % (label, p["y"], L.SIDE_SIGN_Y))
            for y in L.SIDE_SIGN_Y:
                if abs(p["x"]) <= skin_x(y):
                    problems.append("%s: the sign is buried in the bodyside "
                                    "at y %g (|x| %.3f vs skin %.3f)"
                                    % (label, y, abs(p["x"]), skin_x(y)))
        for side in (+1, -1):
            if len(seen[side]) != len(doors):
                problems.append("%s: %d sign(s) on the %sx side, expected %d"
                                % (label, len(seen[side]),
                                   "+" if side > 0 else "-", len(doors)))
            for got, expect in zip(sorted(seen[side]), want[side]):
                if abs(got - expect) > 1e-9:
                    problems.append("%s: a %sx sign is centred at z %.3f but "
                                    "the bodyside painted its housing at %.3f"
                                    % (label, "+" if side > 0 else "-",
                                       got, expect))
        lines.append("  side sign %-6s +x at z %s   -x at z %s   |x| %.2f "
                     "(skin %.2f..%.2f), %dx%d canvas"
                     % (label,
                        ", ".join("%+.1f" % z for z in sorted(seen[+1])),
                        ", ".join("%+.1f" % z for z in sorted(seen[-1])),
                        plane, skin_x(L.SIDE_SIGN_Y[1]),
                        skin_x(L.SIDE_SIGN_Y[0]), got_w, got_h))
    return problems, lines


def verify_elements(model):
    """⭐ REGRESSION GUARD (2026-07-30). A JSON null in `uv_offset` is NOT the
    same as an absent key: gson turns it into JsonNull, which slips past
    `ReaderBase.unpackValue`'s null check and then throws
    `IllegalStateException: Not a JSON Array: null` inside MTR's
    `BlockbenchElementSchema.updateData`. MTR's own 30+ vehicle models write a
    list 6771 times and OMIT the key 682 times — never null. See
    `display_element`."""
    return ["element %r writes `uv_offset: null` — OMIT the key instead; null "
            "throws inside MTR's bbmodel parser" % e["name"]
            for e in model["elements"]
            if "uv_offset" in e and e["uv_offset"] is None]


def main():
    canvas, placed, base = build_texture()
    os.makedirs(os.path.dirname(TEX_PATH), exist_ok=True)
    pngtool.write_png(TEX_PATH, canvas)

    model = build_model()
    os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
    with open(MODEL_PATH, "w") as fh:
        json.dump(model, fh, indent=1)
        fh.write("\n")

    problems = verify(placed, base) + verify_elements(model)
    print("m7_doors.bbmodel: %d groups, %d elements, resolution %dx%d"
          % (len(model["outliner"]), len(model["elements"]), RES_W, RES_H))
    print("doors_box.png:    %dx%d (%d texels/unit), leaf %d wide x %d tall in "
          "%d pocket bands" % (RES_W * TEX_SCALE, RES_H * TEX_SCALE, TEX_SCALE,
                               LEAF_DZ, LEAF_DY, len(BANDS)))
    for i, (ylo, yhi, x_ext, x_int, corridor) in enumerate(BANDS):
        print("  band %d  y %2d..%2d   x  ext %6.3f  int %6.3f   corridor %.3f"
              % (i, ylo, yhi, x_ext, x_int, corridor))
    print("  doorway_box x %g..%g, floor plates %s"
          % (DOORWAY_X[0], DOORWAY_X[1],
             ", ".join("%s %g..%g" % p for p in floor_plates())))
    sign_problems, sign_lines = verify_side_sign()
    problems += sign_problems
    for line in sign_lines:
        print(line)
    for p in problems:
        print("PROBLEM: %s" % p)
    print("box-UV + placement verification: %d problems" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
