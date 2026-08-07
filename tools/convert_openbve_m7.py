#!/usr/bin/env python3
"""Converts the openBVE LIRR M7 donor into MTR's .obj vehicle model.

    python3 tools/convert_openbve_m7.py          # write model + textures
    python3 tools/convert_openbve_m7.py --check  # cross-check against the properties
    python3 tools/convert_openbve_m7.py --assemble out.obj   # preview whole car

WHAT THIS WRITES
----------------
    assets/station_announcer/models/vehicle/m7.obj      one `g` group per MTR part
    assets/station_announcer/models/vehicle/m7.mtl      one material per texture
    assets/station_announcer/textures/vehicle/m7/*.png  the exterior, DRAWN

`tools/gen_m7_assets.py` writes the properties, position definitions and the
index that bind to the group names emitted here. Run this first, then that.

READ `M7_CONVERSION_NOTES.md` BEFORE CHANGING ANYTHING HERE. The loader
behaviour it documents was expensive to establish and is not re-derivable from
this file.

THE ART IS DRAWN, NOT PHOTOGRAPHED  (art pass, 2026-07-28)
----------------------------------------------------------
Everything on the outside of this car except the underframe is drawn in
python from `tools/m7_art.py`'s palette — flat colours, hard edges, ~40 px per
block, MTR's own R179/R211 density. The donor is still the authority on WHERE
things are (its window boxes, its cab uv map, its aperture spans) and it still
supplies the underframe (the equipment raft and the underfloor grilles), but no
donor pixel reaches the bodyside, the roof, the ends, the lettering or the
lamps. See the DRAWN ART section for the one rule that makes the bay system
work — the elevation may only shade vertically.

THE SNOWPLOUGH IS GONE, AND THE COUPLERS ARE NEW  (2026-07-30, user request)
---------------------------------------------------------------------------
Donor builder 46 (QUITANIEVE, a 5-segment plough on Quitanieves.png, donor
z 12.30..12.85) hung under each cab end and read as "this weird snow plow
object" — an LIRR M7 in Baker City has no business wearing one. The builder,
its texture and the clearance note that measured it against the coupler face
are all removed; the ANTICLIMBER above it is untouched.

In its place, and at both ends of every variant, the car now carries a real
coupler: `tools/coupler.py` holds the one design both this train and the R62
use, and `coupler_assembly()` below is the six lines that place it. The
donor's own coupler cluster (builders 30-42 at the cab end, 47-53 at the
gangway) is NOT converted — it is drawbars 71 mm across with billboard chains,
which is a tenth of an M unit — but it is what fixed the height and the
proportions. See `coupler.py`'s docstring for the coupling-plane arithmetic.

THE SALOON WINDOWS ARE REAL GLASS  (same pass)
-----------------------------------------------
The mechanism the cab windscreens proved is now general. Every pane in this
model — windscreens, cab door, saloon windows, gangway door — is DRAWN at
alpha 156, found by `glazing_rects` and cut by `open_glazing`, and the boxes
that came out of that same pass become the translucent quads
(`saloon_glass` / `windscreen`, material `m7_glass#interior_translucent`) that
sit over the holes. Art and geometry come out of the same pixels, so a window
cannot drift from its aperture. The door LEAVES are the exception and cannot
use it: a .bbmodel has nowhere to put a shader flag, so their windows are cut
straight through both planes — see tools/gen_m7_doors.py.

THE THREE COORDINATE FRAMES
---------------------------
donor   openBVE metres. x across (+x right), y up from the RAIL, +z = cab end.
M       the "bbmodel frame": x across, y up, y=0 at the car FLOOR, 1 unit =
        1/16 block. This is what everything below is authored in, because it is
        the frame the JSON `positions` live in. Bay groups are centred on z=0.
OBJ     what actually gets written: obj = (-Mx, +My, -Mz) / 16.
        1 OBJ unit = 1 BLOCK (not 1/16 block — that trap is why the divide is
        here and nowhere else), and x and z are negated relative to M.

MEASURED, not assumed (harness: <scratchpad>/objloader/test/T5.java, which runs
MTR 4.0.5's own OptimizedModel$ObjModel):

  * loadModel() negates y and z. An OBJ vertex (1,0,1) comes back as (1,0,-1).
    That is the load-time 180-degree rotation about X, and it is why authoring
    y-up here ends up y-up in the world.
  * addTransformation() does `applyTranslation(t)` and THEN, if flipped,
    `applyRotation(Y, 180)`. So a flipped instance composes as R*(v+t), NOT
    R*v+t like the .bbmodel path does.

    => IN THE M FRAME:  unflipped(Z): (x,y,z) -> ( x, y,  z + Z)
                          flipped(Z): (x,y,z) -> (-x, y, -z - Z)

    A `positionsFlipped` entry therefore lands its group at MINUS z/16, the
    opposite of the .bbmodel path. Symmetric position sets (r179's windows at
    -80/0/+80) are immune; anything asymmetric is not. The only one left in
    this model is the end:
      end2        positionsFlipped [{z:-132}]                      ->      +132
    `assemble()` below implements exactly the transform above, so the preview
    render doubles as the check on those signs.
    (The door leaves USED to be the other case. They are not in this model any
    more — MTR's .obj path cannot animate doors; see build_door_bay() and
    tools/gen_m7_doors.py. Their definitions are written for the .bbmodel
    composition, which is the opposite of the one above.)

WINDING
-------
openBVE faces are CCW-from-outside, same as OBJ. donor->M scales x and y
positively, so the sign of dz/d(donor z) IS the determinant: a bay whose map
reverses z must have its face winding reversed too, or the whole thing renders
inside out under MTR's unconditional backface culling. `Bay.flip_winding`
carries that, derived rather than hand-set.
"""

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bve_csv
import coupler as CPL
import m7_art as A
import pixel_kit as K
import m7_layout as L
import pngtool

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")
MODEL_DIR = os.path.join(OUR_NS, "models/vehicle")
TEX_DIR = os.path.join(OUR_NS, "textures/vehicle/m7")
TEX_ID = "station_announcer:textures/vehicle/m7"

DONOR = A.DONOR

# --------------------------------------------------------------------- scale
#
# EVERY DIMENSION NOW COMES FROM tools/m7_layout.py. It is the single source of
# truth for the car's scale and bay layout, shared with the interior converter
# and cross-checked against gen_m7_assets.py's definitions; it self-verifies on
# import. Change the car size THERE, never here.
#
# What it gives this file, unchanged in meaning from when they lived here:
#   x: the donor's +-1.57 m half-width becomes +-1.456 blocks, which is MTR's
#      own r179 body half-width (1.387 blk) scaled by the M7's 10'6" over its
#      10'0". MTR draws rolling stock about 30% oversized on purpose; matching
#      r179 is what keeps the M7 the right size NEXT TO one.
#   y: above the floor, r179's vertical factor (1.0255 blk/m) — roof crown
#      lands at 2.72 blk. Below the floor the donor's 1.195 m of underframe is
#      compressed into 0.95 blk (the rail is at -1 blk once modelYOffset raises
#      the model). Nothing may go below -1.
#   z: NOT global. Each bay maps its own donor slice onto its own unit span, so
#      a 30-unit window bay and a 74-unit end bay can carry different
#      compressions without the seams moving.
#
# The cross-section is untouched by the 20 -> 26 block resize; only z moved.
X_SCALE = L.X_SCALE
Y_SCALE_ABOVE = L.Y_SCALE_ABOVE
FLOOR_Y = L.FLOOR_Y
LOWEST_Y = L.LOWEST_Y
Y_SCALE_BELOW = L.Y_SCALE_BELOW

CAR_UNITS = float(L.UNITS_NORMAL)        # 416 — the 26-block car
MINI_UNITS = float(L.UNITS_MINI)         # 240 — the 15-block car

sx = L.sx                                # donor x (metres) -> M units
sy = L.sy                                # donor y (metres above rail) -> M units


def unsy(units):
    """M units back to donor metres. `sy` is PIECEWISE — a second, compressed
    factor below the floor — so its inverse has to be, and a plain divide by
    Y_SCALE_ABOVE is wrong by 27% for anything under the car. Used by
    `coupler_assembly`, which is authored in M units like the shared design."""
    return FLOOR_Y + units / (Y_SCALE_ABOVE if units >= 0 else Y_SCALE_BELOW)


def lerp(a, b, t):
    return a + (b - a) * t


def interp_profile(profile, y):
    """Linearly interpolate a (y, x, v) profile table at donor y."""
    for i in range(len(profile) - 1):
        y0, x0, v0 = profile[i]
        y1, x1, v1 = profile[i + 1]
        lo, hi = min(y0, y1), max(y0, y1)
        if lo - 1e-9 <= y <= hi + 1e-9:
            t = 0.0 if y0 == y1 else (y - y0) / (y1 - y0)
            return lerp(x0, x1, t), lerp(v0, v1, t)
    raise ValueError("y %.3f outside profile" % y)


def invert_profile(profile, v):
    """The other way round: a v back to a donor y. Used to turn a glazing box
    measured in texture space into the height its glass pane has to sit at."""
    for i in range(len(profile) - 1):
        y0, _x0, v0 = profile[i]
        y1, _x1, v1 = profile[i + 1]
        lo, hi = min(v0, v1), max(v0, v1)
        if lo - 1e-9 <= v <= hi + 1e-9:
            t = 0.0 if v0 == v1 else (v - v0) / (v1 - v0)
            return lerp(y0, y1, t)
    raise ValueError("v %.3f outside profile" % v)


# ------------------------------------------------------------------- profiles
#
# Straight off donor builders 10/15 (LATERAL/LATERAL2) and 16 (TECHO). The side
# is a 4-quad strip with tumblehome; v is the LAT.png row fraction from the TOP,
# the donor's own convention — which MTR's obj loader shares, so bake_v() passes
# it through unchanged (see its docstring for the in-game finding).
SIDE_PROFILE = [                 # (donor y, |x|, v)
    (3.400, 1.480, 0.00),        # cant rail
    (1.900, 1.570, 0.63),        # widest point
    (1.700, 1.560, 0.72),
    (1.370, 1.500, 0.83),
    (1.000, 1.500, 1.00),        # bottom of the skirt
]

# ============================================================== DEPTH PASS ===
#
# ⭐ 2026-07-28, user request: real relief where the exterior was paint. Sized
# to MTR's own corpus (surveyed across all 16 of its vehicle bbmodels):
#   * the minimum real element thickness is EXACTLY 1 unit — nothing in MTR
#     lives between 0.3 and 1 — so every step here is a whole M unit.
#   * roof HVAC is REAL BOXES, 2-7 units tall, based on the roof plane. MTR
#     never paints it, and this car did.
#   * a decal is a flat quad 0.1 proud. The marker and head lamps are already
#     built that way (`lamp()`, 8 mm) and are deliberately left alone.
#   * door thresholds are a 1x1 box projecting ~0.5 past the skin.
# What was considered and NOT done is at the bottom of this block.
UNIT_M_X = 1.0 / X_SCALE         # one M unit in donor metres, across the car
UNIT_M_Y = 1.0 / Y_SCALE_ABOVE   # ...and vertically

# --- the roof's drip rail. One unit proud of the bodyside and one unit deep,
# which is the smallest step MTR's corpus contains. It is the only relief that
# runs the WHOLE car, so it is in the profile itself rather than in a bay: a
# swept cross-section tiles by construction, which is what the pane rule asks
# of everything that repeats.
DRIP_X = SIDE_PROFILE[0][1] + UNIT_M_X               # 1.547
DRIP_Y = SIDE_PROFILE[0][0] + UNIT_M_Y               # 3.461

# Half the roof, centre outward. v runs 0.5 (crown) to 1.0 (cant rail); the
# other half is the mirror, which positionsFlipped supplies for free.
#
# The last three points are the DRIP RAIL, and the order they are written in is
# what makes them face the right way: `strip` turns a dx>0/dy<0 step into an
# outward face, a dx=0/dy<0 step into a vertical outward face, and a dx<0/dy=0
# step into a DOWNWARD one — so "out and down, straight down, back inboard"
# comes out as the top, the fascia and the soffit of an overhanging gutter,
# with no winding argument anywhere.
ROOF_PROFILE = [                 # (|x|, donor y, v)
    (0.00, 3.95, 0.50),
    (0.30, 3.93, 0.60),
    (0.75, 3.87, 0.75),
    (1.05, 3.79, 0.85),
    (1.30, 3.68, 0.95),
    (1.40, 3.60, 0.95),
    (1.45, 3.50, 0.95),
    (DRIP_X, DRIP_Y, 0.965),     # out over the bodyside
    (DRIP_X, SIDE_PROFILE[0][0], 0.985),      # the fascia
    (SIDE_PROFILE[0][1], SIDE_PROFILE[0][0], 1.00),   # the soffit, back to the
]                                                     # point the side starts at

# --- the roof HVAC rafts, one per end. Two stacked boxes, MTR's own idiom:
# a base raft on the roof and a taller condenser hump on top of it, over the
# outboard part of the raft. Heights are quoted above the CROWN because that
# is the silhouette a raft has to clear to be seen at all.
HVAC_BASE_HALF_X = 1.150         # donor |x| of the base raft
HVAC_HUMP_HALF_X = 0.720
HVAC_BASE_Y = 3.95 + 1.0 * UNIT_M_Y
HVAC_HUMP_Y = 3.95 + 3.0 * UNIT_M_Y
HVAC_HUMP_FRAC = 0.55            # the outboard share of the raft it covers

# --- the door threshold. It was already a plate reaching past the skin; it is
# now a box with a real tread, a nosing and an underside.
THRESHOLD_DROP_M = 1.0 * UNIT_M_Y
THRESHOLD_PROUD_M = 0.5 * UNIT_M_X

# ⭐ WHAT THIS PASS DELIBERATELY LEFT FLAT, and why:
#   * THE DOOR LEAF'S WINDOW GASKET. MTR draws every door-leaf window as
#     texture; there is not one recessed leaf window in its corpus, and the
#     leaves are pocket doors that slide through a 0.23-unit corridor between
#     the skin and the lining (see gen_m7_doors.pocket_bands) — relief on a
#     leaf is exactly the thing that corridor has no room for.
#   * THE MARKER AND HEAD LAMPS. Already flat quads 8 mm proud of the mask,
#     which IS the house form (0.025..0.1); a proud bezel box would be the
#     only one in the family.
#   * THE BODYSIDE BELT RAIL. The blue band is livery, not a rail: the M7 is
#     smooth painted stainless there, so a step would be inventing a feature.
#     It would also cost two quads on EVERY side run — six window bays, both
#     door bays' four strips each, both ends — for a line the tumblehome
#     already shades.
#   * THE DESTINATION SIGN BOX. Another agent owns that band this session.

UNDERBODY_Y = 1.000              # donor y of the black underbody plate
UNDERBODY_HALF = 1.500           # donor |x| it reaches

# The doorway, from donor builders 54-57 (GOMA NEGRA PUERTA) and PuertaR1.csv.
# The aperture is 1.425 m along z and the two real openings are 47 mm out of
# symmetry, so the door bay is centred on the rear one to square them up. The
# leaf sits 50 mm inboard of the body surface and travels 1.45 m (A.Animated) —
# both are carried by LEAF_PTS and DOOR_MULTIPLIER rather than as constants here.
DOOR_TOP = 3.270                 # donor y of the opening head
DOOR_SILL = FLOOR_Y              # donor y of the opening sill (= our y 0)
REVEAL_DEPTH = 0.100             # metres the rubber reveal steps inboard

# How far inboard of the body surface the interior wall sits. This file no
# longer BUILDS that wall — tools/convert_m7_interior.py does, off AInterior —
# but the floor and doorway plates still have to stop at it, so the inset and
# the height the vestibule lining reaches stay here.
LINING_INSET = 0.060             # metres inboard of the body surface
LINING_TOP = 3.300               # donor y where the lining meets the ceiling


# -------------------------------------------------------------- texture space
#
# THE ELEVATION AND THE PLAN ARE DRAWN, NOT PHOTOGRAPHED (art pass 2026-07-28).
# What is kept from the donor is the MAPPING, not the pixels: `u` is exactly
# linear in donor z, z(u) = 12.500 - 25.020*u, and one `u` serves both the side
# elevation and the roof plan, which is what lets ONE slice range cut a bay's
# wall and its roof together. Every bay group and every UV in this file is
# written against that map, so redrawing the art at a new resolution is a
# matter of changing these two widths and nothing else.
#
# Sizes are chosen for MTR's own texel density (~40 px per block, R179/R211
# territory), not for the donor's 2048x256 photograph:
#   side  1024 x 96  over 25.02 m x 2.40 m  ->  40.9 / 40.0 px per metre
#   roof  1024 x 128 over 25.02 m x 2.96 m  ->  40.9 / 43.2 px per metre
# Square texels within a couple of percent, which is what keeps a drawn
# rectangle from reading as a stretched one.
SIDE_TEX_W = 1024
SIDE_TEX_H = 96
ROOF_TEX_W = 1024
ROOF_TEX_H = 128
u_of_z = L.u_of_z
z_of_u = L.z_of_u


def side_px(z_m):
    """donor z (metres) -> column in the side elevation."""
    return u_of_z(z_m) * SIDE_TEX_W


def side_row(y_m):
    """donor y (metres above rail) -> row in the side elevation.

    Goes through SIDE_PROFILE's own v column rather than through a linear fit,
    so a height drawn here lands on exactly the texture row the geometry
    samples at that height. (The donor's table happens to be within 2% of
    linear, which is why this was easy to get away with not doing.)

    Clamped, because the livery's bands are written as open-ended ranges: the
    skirt runs "to the bottom" and the door header "to the roof", and neither
    should have to know where the profile happens to stop.
    """
    lo, hi = SIDE_PROFILE[-1][0], SIDE_PROFILE[0][0]
    return interp_profile(SIDE_PROFILE, min(hi, max(lo, y_m)))[1] * SIDE_TEX_H


def side_y(v):
    """The inverse of `side_row`, in v rather than rows."""
    return invert_profile(SIDE_PROFILE, v)


# The palette and livery tables live in tools/m7_art.py, because
# tools/gen_m7_doors.py draws the sliding leaves from the same palette at the
# same livery heights; the raster kit and the mark/find/cut glazing mechanism
# live in tools/pixel_kit.py. Aliased here so the call sites below read the
# way they always did.
color_key = K.color_key
solid = K.solid
open_glazing = K.open_glazing
glazing_rects = K.glazing_rects

# `row_means` / `retone` are gone with the donor photography. They existed to
# pull LAT.png's photographic cab-end 396 px onto the synthetic saloon wall's
# vertical gradient at the bay joint. The elevation is now drawn as one canvas
# in `draw_side_elevation()`, so there is no tonal step left to correct.


# ------------------------------------------------------------------ mesh model

class Group:
    """One MTR part: a named bag of faces, each face carrying its material."""

    def __init__(self, name):
        self.name = name
        self.faces = []          # (material, [(x, y, z, u, v), ...])

    def add(self, material, verts, two_sided=False, reverse=False):
        """verts: M-frame (x, y, z) plus (u, v) in donor top-down texture space.

        `reverse` flips the winding, for bays whose donor->M map negates z.
        `two_sided` is openBVE's AddFace2: MTR culls backfaces unconditionally,
        so a double-sided donor face has to become two real faces.
        """
        vs = list(verts)
        if reverse:
            vs = list(reversed(vs))
        self.faces.append((material, vs))
        if two_sided:
            self.faces.append((material, list(reversed(vs))))

    def quad(self, material, p0, p1, p2, p3, **kw):
        self.add(material, [p0, p1, p2, p3], **kw)

    def extend(self, other, dz=0.0):
        for material, vs in other.faces:
            self.faces.append((material,
                               [(x, y, z + dz, u, v) for (x, y, z, u, v) in vs]))

    def bounds(self):
        if not self.faces:
            return None
        pts = [v for _, vs in self.faces for v in vs]
        return (min(p[0] for p in pts), max(p[0] for p in pts),
                min(p[1] for p in pts), max(p[1] for p in pts),
                min(p[2] for p in pts), max(p[2] for p in pts))


class Model:
    def __init__(self):
        self.groups = {}
        self.order = []

    def group(self, name):
        if name not in self.groups:
            self.groups[name] = Group(name)
            self.order.append(name)
        return self.groups[name]


# ---------------------------------------------------------------------- bays
#
# end(74) | door(44) | window(30)x6 | door(44) | end(74) = 416 units = 26 blocks
#   window centres  +-15, +-45, +-75     door centres  +-112     end  +-171
# Mini (240 units) cannot fit two full ends (2*(74+44) = 236 leaves no saloon),
# so its ends are the OUTER 61 units of the same bay, cropped at the same
# units/metre:  end +-89.5, door +-37, one window at 0.
#
# ALL OF IT IS DERIVED IN tools/m7_layout.py — the bay widths fall out of the
# donor slice lengths and the car length, and that module self-verifies on
# import. Nothing dimensional is typed here.
#
# Every bay is authored CENTRED ON z = 0 and repeated by the definitions. The
# end bays are the exception in one respect: they are authored with their
# OUTWARD face at local -z (r179's convention), which is what lets `end1`
# (unflipped, z -171) and `end2` (flipped, z -171) place the same geometry at
# opposite ends of the car both facing outwards.

Bay = L.Bay
WINDOW_BAY = L.WINDOW_BAY
DOOR_BAY = L.DOOR_BAY
CAB_BAY, CAB_BAY_MINI = L.CAB_BAY, L.CAB_BAY_MINI
GANGWAY_BAY, GANGWAY_BAY_MINI = L.GANGWAY_BAY, L.GANGWAY_BAY_MINI
UNDERFRAME_BAY, UNDERFRAME_BAY_MINI = L.UNDERFRAME_BAY, L.UNDERFRAME_BAY_MINI


def _bay_donor_z(bay, mz):
    return bay.donor_z(mz)


# ----------------------------------------------------------- geometry helpers
#
# Faces are built CCW-as-seen-from-outside in the M frame. That survives to the
# world unchanged: M->OBJ negates x and z (a 180-degree turn, determinant +1)
# and loadModel negates y and z (another one), so handedness never flips.

def strip(group, material, pts, z_lo, z_hi, u_at):
    """Extrude a cross-section along z.

    `pts` is [(donor x, donor y, v), ...] ordered so that consecutive points run
    with dx > 0 and dy < 0 — i.e. top-to-bottom for a side, centre-to-edge for a
    roof. Both cases then come out facing outwards with the same vertex order,
    which is why there is one helper and not two.
    """
    for i in range(len(pts) - 1):
        x0, y0, v0 = pts[i]
        x1, y1, v1 = pts[i + 1]
        group.quad(material,
                   (sx(x0), sy(y0), z_lo, u_at(z_lo), v0),
                   (sx(x0), sy(y0), z_hi, u_at(z_hi), v0),
                   (sx(x1), sy(y1), z_hi, u_at(z_hi), v1),
                   (sx(x1), sy(y1), z_lo, u_at(z_lo), v1))


def flat_z(group, material, y_m, x0_m, x1_m, z_lo, z_hi, uv, up=True):
    """A horizontal quad, facing up or down."""
    y = sy(y_m)
    a = (sx(x0_m), y, z_lo, uv[0][0], uv[0][1])
    b = (sx(x0_m), y, z_hi, uv[1][0], uv[1][1])
    c = (sx(x1_m), y, z_hi, uv[2][0], uv[2][1])
    d = (sx(x1_m), y, z_lo, uv[3][0], uv[3][1])
    group.quad(material, *((a, b, c, d) if up else (a, d, c, b)))


def plane_z(group, material, z, corners, uv, outward_pos_z=True):
    """A quad in a constant-z plane. `corners` is [(x,y)] CCW seen from +z."""
    vs = [(sx(x), sy(y), z, uv[i][0], uv[i][1])
          for i, (x, y) in enumerate(corners)]
    group.add(material, vs if outward_pos_z else list(reversed(vs)))


def box(group, material, x0, x1, y0, y1, z0, z1, uv=(0.0, 0.0, 1.0, 1.0)):
    """An axis-aligned box in donor metres/M z units, all six faces outward."""
    u0, v0, u1, v1 = uv
    X0, X1 = sx(min(x0, x1)), sx(max(x0, x1))
    Y0, Y1 = sy(min(y0, y1)), sy(max(y0, y1))
    Z0, Z1 = min(z0, z1), max(z0, z1)
    c = [(u0, v1), (u0, v0), (u1, v0), (u1, v1)]

    def q(p0, p1, p2, p3):
        group.quad(material, *[(p[0], p[1], p[2], c[i][0], c[i][1])
                               for i, p in enumerate((p0, p1, p2, p3))])
    q((X1, Y0, Z0), (X1, Y1, Z0), (X1, Y1, Z1), (X1, Y0, Z1))    # +x
    q((X0, Y0, Z1), (X0, Y1, Z1), (X0, Y1, Z0), (X0, Y0, Z0))    # -x
    q((X0, Y1, Z0), (X0, Y1, Z1), (X1, Y1, Z1), (X1, Y1, Z0))    # +y
    q((X0, Y0, Z1), (X0, Y0, Z0), (X1, Y0, Z0), (X1, Y0, Z1))    # -y
    q((X1, Y0, Z1), (X1, Y1, Z1), (X0, Y1, Z1), (X0, Y0, Z1))    # +z
    q((X0, Y0, Z0), (X0, Y1, Z0), (X1, Y1, Z0), (X1, Y0, Z0))    # -z


def donor_faces(group, material, builder, bay, tex=None, only=None):
    """Copy a donor mesh builder's faces into a group through a bay's z map.

    Reverses the winding when the bay's z map does (Bay.flip_winding), because
    openBVE and OBJ agree on CCW-from-outside and MTR culls backfaces always.
    """
    for index, face in enumerate(builder.faces):
        if only is not None and index not in only:
            continue
        vs = []
        for vi in face.indices:
            v = builder.vertices[vi]
            u = 0.0 if v.u is None else v.u
            t = 0.0 if v.v is None else v.v
            if tex is not None:
                u = tex.u(u)
            vs.append((sx(v.x), sy(v.y), bay.z(v.z), u, t))
        group.add(material, vs, two_sided=face.two_sided,
                  reverse=bay.flip_winding)


# --------------------------------------------------------------- texture set

class Tex:
    """A texture that will be written out, plus its u-remap."""

    def __init__(self, name, rows, src_w=None, x0=0, x1=None):
        self.name = name
        self.rows = rows
        self.src_w = src_w
        self.x0 = x0
        self.x1 = x1 if x1 is not None else (src_w or len(rows[0]))
        self.flag = ""           # shader flag, e.g. "#interior_translucent"
        self.glazing = []        # uv boxes of the donor's blended areas

    def u(self, u_full):
        if self.src_w is None:
            return u_full
        return (u_full * self.src_w - self.x0) / float(self.x1 - self.x0)

    @property
    def material(self):
        # The flag rides IN the material name, and when an .mtl exists MTR
        # looks the FULL name up, so `newmtl` has to carry it verbatim too.
        return "m7_" + self.name + self.flag

    @property
    def map_kd(self):
        return "%s/%s.png" % (TEX_ID, self.name)


def _px(u, width):
    return u * width


# ======================================================================
# DRAWN ART
# ======================================================================
#
# Everything a player can see on the outside of this train, except the
# underframe, is drawn here from tools/m7_art.py's palette. The donor is
# consulted for POSITIONS only — where its windows are, what uv map its cab
# mask uses, how wide its door aperture is — never for pixels.
#
# The one rule that makes the bay system work: the side elevation and the roof
# plan may only shade VERTICALLY. Every column of the field is identical, so a
# 30-unit window bay cropped out of the middle repeats six times with no seam,
# exactly as the donor's synthetic saloon wall did. Content that varies along
# the car (windows, doors, decals, the roof's HVAC) is drawn at its own donor
# z and lands in whichever bay contains it.

# The saloon glazing, straight off LAT.png's own alpha-156 marks: ten panes —
# the cab access door's, one short cab-end window, six saloon windows and two
# at the gangway end. min_px rejects the donor's stray blend pixels.
SIDE_GLAZING_MIN_PX = 400
# The same test applied to the DRAWN elevation, which is a quarter the donor's
# width and a third its height: the smallest pane on it (the cab access door's,
# 16 x 26 px) is still six times this.
DRAWN_GLAZING_MIN_PX = 64

# The two door openings, from the GOMA NEGRA reveal (donor builders 54-57).
# Drawn on the elevation as a header, jambs and a sill strip; the aperture
# itself is cut geometrically in build_door_bay(), so what is drawn inside it
# only ever shows through the reveal.
DOOR_OPENINGS = ((L.DOOR_APERTURE_B, L.DOOR_APERTURE_A),      # rear  -7.360..-5.935
                 (6.030, 7.455))                              # front

# The two roof equipment rafts, in donor z. Read off techo.png's plan (the fan
# grilles sit in the outer 3 m of each end), which is also the only part of
# the roof that is not uniform along the car.
HVAC_Z = ((12.30, 9.20), (-9.20, -12.32))

# The cab mask's uv map, verified against donor builders 0/1/2/9 (and 20/21/
# 22/25 for the gangway end, which mirrors u because it is seen from -z).
# `mask_uv_map()` re-fits the same thing from the geometry at build time; these
# are what the ART is drawn against, and --check compares the two.
MASK_TEX_W, MASK_TEX_H = 144, 128
MASK_X_SPAN = 3.217              # metres of car width across u 0..1
MASK_Y_TOP, MASK_Y_BOT = 3.400, 1.050      # v 0.18 and v 1.00
MASK_V_AT_TOP = 0.18             # above this the art is the roof fillet
MASK_FILLET_TOP = 3.950          # donor y at v = 0

# The destination sign's unlit face. Near-black rather than the mask's own
# black so the box reads as a recessed screen even with no route set; the
# DISPLAY text drawn over it comes from m7_doors.bbmodel. Geometry is in
# tools/m7_layout.py — three tools share it, see SIGN_HALF_X_M there.
SIGN_FACE = (8, 8, 10)


def mask_u(x_m, mirror=False):
    """donor x -> u on the cab (or, mirrored, the gangway) end texture."""
    return 0.5 + (x_m if mirror else -x_m) / MASK_X_SPAN


def mask_v(y_m):
    """donor y -> v on an end texture, across both the face and the fillet."""
    if y_m <= MASK_Y_TOP:
        return MASK_V_AT_TOP + ((MASK_Y_TOP - y_m) * (1.0 - MASK_V_AT_TOP)
                                / (MASK_Y_TOP - MASK_Y_BOT))
    return MASK_V_AT_TOP * (MASK_FILLET_TOP - y_m) / (MASK_FILLET_TOP - MASK_Y_TOP)


def mask_x_of_u(u):
    """Inverse of `mask_u` (unmirrored)."""
    return (0.5 - u) * MASK_X_SPAN


def mask_y_of_v(v):
    """Inverse of `mask_v`, over the mask face (v >= MASK_V_AT_TOP)."""
    if v >= MASK_V_AT_TOP:
        return MASK_Y_TOP - ((v - MASK_V_AT_TOP) * (MASK_Y_TOP - MASK_Y_BOT)
                             / (1.0 - MASK_V_AT_TOP))
    return MASK_FILLET_TOP - v * (MASK_FILLET_TOP - MASK_Y_TOP) / MASK_V_AT_TOP


# One texture pixel of the mask, in donor metres, along each axis. Used to give
# a drawn pane's SURROUND its real footprint — `A.pane` draws it outside the
# glass box, so a box that merely clears the glass can still clip the gasket.
MASK_PX_X = MASK_X_SPAN / MASK_TEX_W
MASK_PX_Y = ((MASK_Y_TOP - MASK_Y_BOT) / (1.0 - MASK_V_AT_TOP)) / MASK_TEX_H


def _mask_px(x_m, y_m, mirror=False):
    return (mask_u(x_m, mirror) * MASK_TEX_W, mask_v(y_m) * MASK_TEX_H)


def side_glazing_span():
    """(top y, bottom y) of the donor's saloon glass, in donor metres.

    The bodyside's tone bands are laid out against this rather than against
    typed heights, so the belt stripe and the panel seams track the windows.
    """
    rects = A.donor_glazing("LAT.png", SIDE_GLAZING_MIN_PX)
    tops = [side_y(v0) for (_u0, _u1, v0, _v1) in rects]
    bots = [side_y(v1) for (_u0, _u1, _v0, v1) in rects]
    return max(tops), min(bots)


def side_panes():
    """The donor's saloon panes, indexed. Index i is the same pane in every
    derived list — the drawn elevation, `panes_in()` and `saloon_glass`'s
    boxes are all built from THIS list in THIS order."""
    return A.donor_glazing("LAT.png", SIDE_GLAZING_MIN_PX)


def panes_in(bay):
    """Indices of the panes that fit WHOLLY inside `bay`, frame included.

    ⭐ WHY THIS EXISTS (user-reported 2026-07-28: "a narrow fragment of window
    beside the doors", plus a doubled frame at the same seam). The elevation is
    drawn once for the whole car and cropped per bay, so a pane whose donor z
    straddles a bay boundary was cut in half by the crop. The half that landed
    in the bay was still an alpha-156 mark, so `open_glazing` punched it
    through as a real aperture — while `saloon_glass` (which requires
    `bay.contains` at BOTH ends) refused to build a pane for it. The result was
    an unglazed slot beside every door, with the pane's gasket right beside it
    reading as a second window frame.

    The rule is now: **a pane exists wholly inside its bay or not at all.** A
    bay draws only the panes this returns, so the crop can never cut one, and
    the art and the glass quads are driven by the same test.

    The frame margin matters: `A.pane` draws its gasket OUTSIDE the glass box,
    so a pane whose glass just fits can still have its surround clipped.
    """
    margin_m = (PANE_FRAME_PX + 1) / float(SIDE_TEX_W) * abs(L.Z_PER_U)
    out = set()
    for i, (u0, u1, _v0, _v1) in enumerate(side_panes()):
        za, zb = z_of_u(u0), z_of_u(u1)
        lo, hi = min(za, zb) - margin_m, max(za, zb) + margin_m
        if bay.contains(lo) and bay.contains(hi):
            out.add(i)
    return out


# `A.pane`'s default surround width, in pixels of the drawn elevation. Used to
# widen the containment test above; keep it in step with the `frame=` argument
# every call below passes (they all take the default).
PANE_FRAME_PX = 2


def draw_side_elevation(keep=None):
    """The whole car side, drawn: 1024 x 96, glazing MARKED not cut.

    Layout, top to bottom: cant rail highlight, an upper panel seam, the
    stainless field the windows sit in, the blue LIRR belt stripe just under
    the glass, a lower seam, the tumblehome, and the skirt. All of it full
    width — see the section header for why that is not negotiable.

    `keep` is a set of indices into `side_panes()`; None draws them all. A bay
    passes the set `panes_in()` gave it, which is what stops a crop cutting a
    window in half — see that function. Everything else on the elevation is
    either constant along the car or lands well inside one bay, so only the
    panes need the treatment.
    """
    rows = K.canvas(SIDE_TEX_W, SIDE_TEX_H, A.STEEL)
    glazing = side_panes()
    win_top, win_bot = side_glazing_span()
    A.stainless_field(rows, side_row, win_top, win_bot)

    # Windows, at the donor's own boxes. `A.pane` draws the surround OUTSIDE
    # the box it is given, so the glass — and therefore the hole and the glass
    # quad derived from it — lands exactly where the prototype's did.
    for i, (u0, u1, v0, v1) in enumerate(glazing):
        if keep is not None and i not in keep:
            continue
        A.pane(rows, u0 * SIDE_TEX_W, v0 * SIDE_TEX_H,
               u1 * SIDE_TEX_W, v1 * SIDE_TEX_H, radius=3)

    # The two door openings: header over the aperture, a sill band under it,
    # and the safety decals that repeat beside every door on the prototype.
    for (za, zb) in DOOR_OPENINGS:
        x0, x1 = sorted((side_px(za), side_px(zb)))
        # A dark line at the head and the yellow platform-edge strip at the
        # sill. No header PANEL: everything between them is aperture, cut
        # geometrically in build_door_bay(), so painting it only shows through
        # the reveal as a floating rectangle.
        K.rect(rows, x0 - 2, side_row(DOOR_TOP), x1 + 2,
               side_row(DOOR_TOP) + 1, A.SEAM)
        K.rect(rows, x0 - 2, side_row(DOOR_SILL), x1 + 2,
               side_row(DOOR_SILL - 0.10), A.SAFETY_YELLOW)
        K.rect(rows, x0 - 2, side_row(DOOR_SILL - 0.10), x1 + 2,
               side_row(DOOR_SILL - 0.10) + 1, A.SAFETY_YELLOW_LO)
        K.blit(rows, int(x1 + 4), int(side_row(2.66)), A.hazard_triangle(11))
        K.blit(rows, int(x1 + 5), int(side_row(2.34)), A.hazard_label(8, 11))

    # ⭐ THE SIDE DESTINATION SIGN's housing, on the door bay's flanking panel —
    # the one OPPOSITE the hazard decals above, so the two never meet. Only the
    # REAR opening gets one: that is the one the door bay is centred on and
    # therefore the only one any crop actually samples (the front opening's art
    # is drawn above for symmetry and lands in the gap between two bays, where
    # nothing reads it). The bay repeats to both doors and both sides, so this
    # one rectangle becomes the four signs `m7_doors.bbmodel` lights up; which
    # of the two panels it lands on depends on the door, and that is expected —
    # see m7_layout.SIDE_SIGN_* for why symmetry is not on offer here.
    #
    # Drawn as a dark aperture in the stainless, in the SAME gasket tones the
    # windows use, so it reads as a member of the family rather than a sticker.
    # The amber comes from MTR's DISPLAY text — see m7_layout.SIDE_SIGN_* — so
    # the screen itself stays black, the way an unlit sign in a depot is.
    sx0, sx1 = sorted((side_px(L.SIDE_SIGN_Z_M
                               - L.SIDE_SIGN_HALF_LEN / L.DOOR_BAY.units_per_m),
                       side_px(L.SIDE_SIGN_Z_M
                               + L.SIDE_SIGN_HALF_LEN / L.DOOR_BAY.units_per_m)))
    sy0 = side_row(FLOOR_Y + L.SIDE_SIGN_Y[1] / L.Y_SCALE_ABOVE)
    sy1 = side_row(FLOOR_Y + L.SIDE_SIGN_Y[0] / L.Y_SCALE_ABOVE)
    bez = L.SIDE_SIGN_MARGIN_M * abs(SIDE_TEX_W / L.Z_PER_U)
    K.rect(rows, sx0 - bez, sy0 - bez, sx1 + bez, sy1 + bez, A.GASKET_HI)
    K.rect(rows, sx0 - bez + 1, sy0 - bez + 1, sx1 + bez - 1, sy1 + bez - 1,
           A.GASKET)
    K.rect(rows, sx0, sy0, sx1, sy1, SIGN_FACE)

    # The US flag. Placed in the blank cab-end panel — derived as the gap
    # between the cab access door's window and the first saloon window, which
    # is the only wide unbroken panel the prototype has and where it carries
    # the flag.
    gap_a, gap_b = glazing[0][1], glazing[1][0]
    cx = (gap_a + gap_b) / 2.0 * SIDE_TEX_W
    cy = (side_row(win_top) + side_row(win_bot)) / 2.0
    flag_w = int(round((gap_b - gap_a) * SIDE_TEX_W * 0.44))
    K.blit(rows, int(cx - flag_w / 2.0), int(cy - flag_w / 3.8),
           A.us_flag(flag_w, int(round(flag_w / 1.9))))
    return rows


def draw_roof_plan():
    """The roof, in plan: 1024 x 128, v across the car, crown at v = 0.5.

    Only v 0.5..1.0 is ever sampled — the roof group models the +x half and
    the definitions mirror it — but the plan is drawn symmetric anyway so it
    reads as a roof in any preview and survives a future full-width part.
    """
    rows = K.canvas(ROOF_TEX_W, ROOF_TEX_H, A.ROOF_STEEL)
    half = ROOF_TEX_H // 2

    # Ribs run ALONG the car, so in this plan they are horizontal bands: still
    # constant in u, still perfectly tiling. Period 4 rows, three tones.
    for i in range(half):
        y = half + i
        t = i % 4
        colour = (A.ROOF_RIB_HI if t == 0 else
                  A.ROOF_STEEL if t in (1, 3) else A.ROOF_RIB_LO)
        K.rect(rows, 0, y, ROOF_TEX_W, y + 1, colour)
    K.rect(rows, 0, half, ROOF_TEX_W, half + 2, A.ROOF_RIB_HI)      # crown cap
    # The cant rail, widened to 5 rows in the depth pass so the drip rail's
    # three new profile points (v 0.965..1.0) all land inside it — the gutter
    # is a shadow line and has to be drawn as one.
    K.rect(rows, 0, ROOF_TEX_H - 5, ROOF_TEX_W, ROOF_TEX_H, A.SEAM)

    # ⭐ THE HVAC IS NOT PAINTED HERE ANY MORE (depth pass, 2026-07-28). It was
    # a flat plan — two fan discs, a ribbed intake and a panel outline — printed
    # on a smooth dome, and it read as exactly that from every raised view.
    # MTR paints roof equipment on none of its 16 vehicles; it builds boxes. So
    # the raft is real geometry now (`hvac_raft`), wearing its own texture
    # (`draw_hvac`), and the plan underneath it is plain ribbed roof. HVAC_Z is
    # still the authority on WHERE, and is now read by the geometry instead.

    for i in range(half):
        rows[half - 1 - i] = list(rows[half + i])
    return rows


# The HVAC texture's two bands: the raft LID in the top rows, the flanks in the
# bottom ones. Split here so the art and the unwrap cannot disagree.
HVAC_TEX_W, HVAC_TEX_H = 128, 64
HVAC_LID_V = (0.00, 0.62)        # v at the raft's outer edge .. at its centre
HVAC_SIDE_V = (0.66, 1.00)       # v at the top of a flank .. at its bottom


def draw_hvac():
    """The roof raft: a lid plan across the top rows, a flank band below.

    ⭐ THE LID ART IS THE +x HALF ONLY, and is mirrored by `add_mirrored`
    exactly as the roof and the cab mask are. That is why there is not a fan
    disc in it: a circle centred on the car's axis would be drawn as a
    half-disc here and would have to meet its mirror image to the pixel, and
    every longitudinal feature in this file is a band for the same reason. What
    the raft carries instead is what mirrors cleanly and what MTR's own roof
    equipment carries — a bordered access panel, a run of condenser louvres and
    a lengthways seam.

    v = 0 is the raft's OUTER edge and v = HVAC_LID_V[1] its centreline, so the
    louvres run along the car and read the same on both halves.
    """
    rows = K.canvas(HVAC_TEX_W, HVAC_TEX_H, A.HVAC)
    lid_h = int(HVAC_LID_V[1] * HVAC_TEX_H)

    K.rect(rows, 0, 0, HVAC_TEX_W, 2, A.HVAC_LO)             # the outer arris
    K.rect(rows, 0, lid_h - 2, HVAC_TEX_W, lid_h, A.HVAC_LO)  # the centre seam

    # Condenser louvres over the outboard half of the raft, then a bolted
    # access panel over the rest. Both are bands in v, so both mirror.
    split = int(HVAC_TEX_W * HVAC_HUMP_FRAC)
    for x in range(2, split - 2, 4):
        K.rect(rows, x, 4, x + 2, lid_h - 4, A.HVAC_GRILLE)
    K.rect(rows, split + 2, 3, HVAC_TEX_W - 3, lid_h - 3, A.HVAC_LO)
    K.rect(rows, split + 4, 5, HVAC_TEX_W - 5, lid_h - 5, A.HVAC)
    for x in (split + 6, HVAC_TEX_W - 8):                    # corner bolts
        for y in (7, lid_h - 9):
            K.rect(rows, x, y, x + 2, y + 2, A.HVAC_GRILLE)

    # The flank band: darker, with one rib so a long flank is not a flat slab.
    K.rect(rows, 0, int(HVAC_SIDE_V[0] * HVAC_TEX_H), HVAC_TEX_W, HVAC_TEX_H,
           A.HVAC_LO)
    mid = int((HVAC_SIDE_V[0] + HVAC_SIDE_V[1]) / 2.0 * HVAC_TEX_H)
    K.rect(rows, 0, mid, HVAC_TEX_W, mid + 1, A.HVAC_GRILLE)
    return rows


def draw_cab_mask(donor):
    """The cab end: black mask, orange band, anticlimber, marked windscreens.

    Drawn in DONOR METRES through `mask_u`/`mask_v`, which is the uv map the
    donor baked into builders 0/1/2/9 — so the art lands on the geometry
    without a single pixel coordinate being typed. The windscreens come from
    the donor's own glazing boxes and the headlight holes from the geometry of
    the 3D lamps that show through them (builders 17/18), for the same reason.
    """
    rows = K.canvas(MASK_TEX_W, MASK_TEX_H, A.CAB_BLACK)

    def box(x_a, y_a, x_b, y_b, colour):
        (pa, va), (pb, vb) = _mask_px(x_a, y_a), _mask_px(x_b, y_b)
        K.rect(rows, min(pa, pb), min(va, vb), max(pa, pb), max(va, vb), colour)

    # Roof fillet, then the mask, then the body's stainless corner posts where
    # the sides wrap round onto the end.
    box(-1.75, MASK_FILLET_TOP, 1.75, MASK_Y_TOP, A.CAB_BLACK_HI)
    box(-1.75, MASK_Y_TOP, 1.75, 2.05, A.CAB_BLACK)
    for sign in (-1.0, 1.0):
        box(sign * 1.42, MASK_FILLET_TOP, sign * 1.75, 1.05, A.STEEL_MID)
        box(sign * 1.47, MASK_FILLET_TOP, sign * 1.75, 1.05, A.STEEL_HI)

    # The cab door, centre: builder 1 is the flat panel between x -0.370 and
    # +0.370, and it reads as a door only if its two edges are drawn.
    box(-0.370, MASK_Y_TOP, 0.370, 1.44, A.CAB_BLACK_HI)
    for sign in (-1.0, 1.0):
        box(sign * 0.370, MASK_Y_TOP, sign * 0.345, 1.44, A.CAB_BLACK)
    (hx, hy) = _mask_px(0.300, 2.55)
    K.rect(rows, hx, hy, hx + 2, hy + 9, A.STEEL_LO)

    # The orange band. The prototype's marker and head lamps live in it, and
    # the 3D lamp quads build_cab_end() adds sit just proud of these housings.
    box(-1.47, 2.016, 1.47, 1.438, A.CAB_ORANGE)
    box(-1.47, 2.016, 1.47, 2.000, A.CAB_ORANGE_HI)
    box(-1.47, 1.455, 1.47, 1.438, A.CAB_ORANGE_LO)
    for sign in (-1.0, 1.0):
        for (x_m, y_m, lens) in ((0.820, 1.805, A.LAMP_LENS),
                                 (1.100, 1.830, A.LAMP_RED)):
            cx, cy = _mask_px(sign * x_m, y_m)
            K.disc(rows, cx, cy, 5.0, A.CAB_BLACK)
            K.disc(rows, cx, cy, 3.6, A.LAMP_RIM)
            K.disc(rows, cx, cy, 2.4, lens)

    # Anticlimber and coupler pocket, below the band.
    box(-1.47, 1.438, 1.47, 1.050, A.ANTICLIMBER)
    box(-1.47, 1.438, 1.47, 1.400, A.ANTICLIMBER_LO)
    box(-0.55, 1.330, 0.55, 1.100, A.ANTICLIMBER_LO)
    for i in range(-6, 7):
        box(i * 0.22 - 0.02, 1.395, i * 0.22 + 0.02, 1.060, A.ANTICLIMBER_LO)

    # NO ROOF-LAMP HOLES any more (user decision, 2026-07-28). This used to
    # punch two alpha-0 discs through the fillet for donor builders 17/18 to
    # show through; both the lamps and the holes are gone, so the fillet stays
    # closed. Nothing samples those rows at all now — `roof_cap()` took the
    # only geometry that did — but they are still drawn, so a future part that
    # lands up there gets mask, not a hole.

    # The destination sign. The prototype has none and neither does the donor,
    # so this is an addition: a recessed black box with a steel bezel in the
    # band between the cab door's window head and the roofline, which is the
    # only clear full-height space on the mask. It is only the BACKING — the
    # text is an MTR DISPLAY part, and DISPLAY parts do not work on an .obj
    # (see M7_CONVERSION_NOTES), so the display elements live in
    # m7_doors.bbmodel and are placed to land exactly on this box.
    sign_lo, sign_hi = L.SIGN_BOX_Y_M
    box(-L.SIGN_HALF_X_M, sign_hi, L.SIGN_HALF_X_M, sign_lo, A.STEEL_LO)
    box(-L.SIGN_HALF_X_M + 0.020, sign_hi - 0.012,
        L.SIGN_HALF_X_M - 0.020, sign_lo + 0.012, SIGN_FACE)

    # Windscreens, from the donor's own alpha-156 boxes, plus wipers. The
    # wipers are drawn LAST and at full alpha, so `open_glazing` leaves them
    # behind as slivers of mask inside the aperture — which, with the glass
    # quad sitting proud of the mask, puts them where a wiper belongs.
    for (u0, u1, v0, v1) in A.donor_glazing("M7frente.png"):
        A.pane(rows, u0 * MASK_TEX_W, v0 * MASK_TEX_H,
               u1 * MASK_TEX_W, v1 * MASK_TEX_H, radius=3)
    for (u0, u1, v0, v1) in A.donor_glazing("M7frente.png"):
        if (u1 - u0) * MASK_TEX_W < 20:
            continue                        # the cab door's window has no wiper
        x0, x1 = u0 * MASK_TEX_W, u1 * MASK_TEX_W
        y0, y1 = v0 * MASK_TEX_H, v1 * MASK_TEX_H
        K.line(rows, x0 + (x1 - x0) * 0.62, y0 + 2,
               x0 + (x1 - x0) * 0.30, y1 - 3, A.WIPER, width=2)
        K.line(rows, x0 + (x1 - x0) * 0.30, y1 - 3,
               x0 + (x1 - x0) * 0.42, y1 - 1, A.WIPER, width=2)
    return rows


def draw_gangway_end():
    """The gangway end: stainless panels, corner posts, a door with a HOLE.

    The centre panel (donor builder 21) is mapped with NEGATIVE u, i.e.
    mirrored, so everything drawn between x -0.370 and +0.370 is drawn
    symmetric about the centreline — otherwise the door window would land off
    to one side in game and nowhere near where it is drawn.
    """
    rows = K.canvas(MASK_TEX_W, MASK_TEX_H, A.END_PANEL)

    def box(x_a, y_a, x_b, y_b, colour):
        (pa, va), (pb, vb) = (_mask_px(x_a, y_a, True), _mask_px(x_b, y_b, True))
        K.rect(rows, min(pa, pb), min(va, vb), max(pa, pb), max(va, vb), colour)

    box(-1.75, MASK_FILLET_TOP, 1.75, MASK_Y_TOP, A.END_PANEL_LO)   # fillet
    box(-1.75, MASK_Y_TOP, 1.75, 3.180, A.END_PANEL_LO)             # header
    box(-1.75, 3.180, 1.75, 3.165, A.SEAM)
    box(-1.75, 2.180, 1.75, 2.168, A.SEAM)                  # mid panel joint
    box(-1.75, 1.560, 1.75, 1.320, A.END_PANEL_LO)          # lower panel
    box(-1.75, 1.560, 1.75, 1.548, A.SEAM)
    box(-1.75, 1.320, 1.75, 1.050, A.DIAPHRAGM)             # threshold shadow
    for sign in (-1.0, 1.0):                                # corner posts
        box(sign * 1.30, MASK_FILLET_TOP, sign * 1.75, 1.32, A.END_POST)
        box(sign * 1.30, MASK_FILLET_TOP, sign * 1.32, 1.32, A.SEAM)
        box(sign * 1.44, MASK_FILLET_TOP, sign * 1.46, 1.32, A.END_PANEL_LO)
        # The jumper / trainline receptacle boxes, one each side of the door.
        # One flat block with a lit lid: a drawn "border" here is 1 px wide
        # and rounds asymmetrically, which reads as an L rather than a box.
        box(sign * 0.66, 1.880, sign * 0.94, 1.600, A.DIAPHRAGM)
        box(sign * 0.66, 1.880, sign * 0.94, 1.840, A.END_POST)

    # The gangway door. Symmetric by construction, with a real hole for its
    # window — this is the end another car connects to, so seeing through is
    # right. The hole is the donor's blue key, re-derived as a centred box of
    # the same size.
    box(-0.370, MASK_Y_TOP, 0.370, 1.320, A.END_PANEL)
    for sign in (-1.0, 1.0):
        box(sign * 0.370, MASK_Y_TOP, sign * 0.352, 1.320, A.SEAM)
    keyed = K.color_key(A.donor_rows("M7Atras.png"), [(0, 0, 255)])
    hole = _keyed_box(keyed)
    if hole is not None:
        u0, u1, v0, v1 = hole
        half_u = (u1 - u0) / 2.0
        K.rrect(rows, (0.5 - half_u) * MASK_TEX_W - 2, v0 * MASK_TEX_H - 2,
                (0.5 + half_u) * MASK_TEX_W + 2, v1 * MASK_TEX_H + 2, 6, A.GASKET)
        K.rrect(rows, (0.5 - half_u) * MASK_TEX_W, v0 * MASK_TEX_H,
                (0.5 + half_u) * MASK_TEX_W, v1 * MASK_TEX_H, 5, (0, 0, 0, 0))
    (hx, hy) = _mask_px(0.300, 2.30, True)
    K.rect(rows, hx, hy, hx + 2, hy + 8, A.STEEL_LO)
    return rows


def _keyed_box(rows):
    """The bounding uv box of the largest fully transparent region in `rows`.

    Used to lift the gangway door's window out of the donor's blue key without
    hand-typing it. Returns None when the art has no keyed area at all.
    """
    h, w = len(rows), len(rows[0])
    seen = [[False] * w for _ in range(h)]
    best = None
    for y0 in range(h):
        for x0 in range(w):
            if seen[y0][x0] or rows[y0][x0][3] != 0:
                continue
            stack, pts = [(x0, y0)], []
            seen[y0][x0] = True
            while stack:
                cx, cy = stack.pop()
                pts.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if (0 <= nx < w and 0 <= ny < h and not seen[ny][nx]
                            and rows[ny][nx][3] == 0):
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            if best is None or len(pts) > best[0]:
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                best = (len(pts), (min(xs) / float(w), (max(xs) + 1) / float(w),
                                   min(ys) / float(h), (max(ys) + 1) / float(h)))
    return None if best is None else best[1]


def draw_lamp_lens(size=16):
    """The 3D roof headlights' lens (donor builders 17/18, a 10-face cylinder).

    u wraps around the cylinder and v runs along it, so the art may only vary
    with v or the seam shows: a bright core between two rim bands.
    """
    rows = K.canvas(size, size, A.LAMP_LENS)
    K.rect(rows, 0, 0, size, 2, A.LAMP_RIM)
    K.rect(rows, 0, size - 2, size, size, A.LAMP_RIM)
    K.rect(rows, 0, 2, size, 4, A.LAMP_LENS_HOT)
    K.rect(rows, 0, size - 5, size, size - 2, (232, 220, 186))
    return rows


def build_textures(donor):
    """Draw the exterior, slice it per bay, keep the donor's underframe.

    Returns {key: Tex}. The four side/roof slices share one u map, so one
    range cuts a bay's wall and its roof together; crops are on whole pixels
    and Tex.u() carries the fractional remainder, so no geometry moves to suit
    a crop.
    """
    def load(fn):
        return A.donor_rows(fn)

    # Drawn once with EVERY pane, purely to measure. The panes built into each
    # bay land exactly on the apertures they fill, and because the boxes come
    # off this canonical draw they stay in one coordinate space even though
    # each bay's own slice omits some of them. Same order as the cab mask's,
    # and the same mechanism — see pixel_kit.glazing_rects.
    side_glazing = glazing_rects(draw_side_elevation(), DRAWN_GLAZING_MIN_PX)
    techo = draw_roof_plan()
    tex = {}

    def pair(key, bay, u0, u1, roof_from=None):
        """One bay's wall + roof slice, cut from the same u range.

        The wall is drawn FOR THIS BAY (omitting any pane the crop would cut —
        see `panes_in`), then cropped; the roof carries no panes so it is cut
        from one shared plan. `roof_from` lets a truncated variant reuse the
        full bay's crop window, which keeps `Tex.u()` identical and means the
        geometry never has to know a mini texture exists.
        """
        a, b = sorted((u0, u1))
        lx0, lx1 = int(math.floor(_px(a, SIDE_TEX_W))), int(math.ceil(_px(b, SIDE_TEX_W)))
        lx0, lx1 = max(0, lx0), min(SIDE_TEX_W, lx1)
        lat = open_glazing(draw_side_elevation(panes_in(bay)))
        t = Tex("lat_" + key, pngtool.crop(lat, lx0, 0, lx1, len(lat)),
                SIDE_TEX_W, lx0, lx1)
        # The FULL-texture glazing list, not the slice's: the bay builders
        # filter it by `Bay.contains`, which is the only place that knows
        # which panes belong to which bay — and which agrees with `panes_in`
        # by construction, so art and glass can never disagree.
        t.glazing = side_glazing
        tex["lat_" + key] = t

        if roof_from is not None:
            tex["techo_" + key] = tex["techo_" + roof_from]
            return
        rx0, rx1 = int(math.floor(_px(a, ROOF_TEX_W))), int(math.ceil(_px(b, ROOF_TEX_W)))
        rx0, rx1 = max(0, rx0), min(ROOF_TEX_W, rx1)
        tex["techo_" + key] = Tex("techo_" + key,
                                  pngtool.crop(techo, rx0, 0, rx1, len(techo)),
                                  ROOF_TEX_W, rx0, rx1)

    pair("window", WINDOW_BAY, u_of_z(WINDOW_BAY.za), u_of_z(WINDOW_BAY.zb))
    pair("door", DOOR_BAY, u_of_z(DOOR_BAY.za), u_of_z(DOOR_BAY.zb))
    pair("cab", CAB_BAY, 0.0, u_of_z(CAB_BAY.zb))
    pair("rear", GANGWAY_BAY, u_of_z(GANGWAY_BAY.zb), 1.0)

    # The Mini crops both end bays, and a crop can cut a pane the full bay
    # cleared — the gangway end's inner window is the real case: whole at 26
    # blocks, 85% of it at 15. The mini slice is therefore drawn separately but
    # cropped to the SAME u window as the full one, so `Tex.u()` is unchanged
    # and only the pixels differ. Identical pane sets share the full texture,
    # which is why the cab end costs nothing.
    for key, full, bay in (("cab", CAB_BAY, CAB_BAY_MINI),
                           ("rear", GANGWAY_BAY, GANGWAY_BAY_MINI)):
        if panes_in(bay) == panes_in(full):
            tex["lat_" + key + "_mini"] = tex["lat_" + key]
            tex["techo_" + key + "_mini"] = tex["techo_" + key]
            continue
        src = tex["lat_" + key]
        pair(key + "_mini", bay, src.x0 / float(SIDE_TEX_W),
             src.x1 / float(SIDE_TEX_W), roof_from=key)

    mask = draw_cab_mask(donor)
    tex["frente"] = Tex("frente", open_glazing(mask))
    tex["frente"].glazing = glazing_rects(mask)

    # The windscreens' and saloon windows' glass. INTERIOR_TRANSLUCENT is the
    # only stage that alpha-blends, and the flag in the material name beats the
    # part's renderStage, so these quads can sit in an EXTERIOR group and still
    # come out as real tinted glass.
    tex["glass"] = Tex("glass", solid(GLASS_RGBA))
    tex["glass"].flag = "#interior_translucent"

    # The roof rafts' own sheet. Its own file rather than a corner of the roof
    # plan, because the plan is cropped per bay by u and the raft is not — it
    # is placed once per end and wants its whole u range.
    tex["hvac"] = Tex("hvac", draw_hvac())

    tex["atras"] = Tex("atras", draw_gangway_end())
    tex["luz"] = Tex("luz", draw_lamp_lens())
    # The side lettering. Its geometry is 1.1 m x 0.35 m (donor builders 70/71),
    # so 128 x 40 is a 3.2:1 canvas on a 3.14:1 slab and about 2.4x the model's
    # texel density — deliberate oversampling, the same trick MTR's own r179
    # pulls (368 declared, 1472 shipped). Lettering is where it pays.
    tex["logo"] = Tex("logo", A.lirr_wordmark(128, 40))

    # KEPT FROM THE DONOR (user decision): the underframe. The equipment raft
    # and the underfloor grilles are seen edge-on from a metre off the ground
    # and carry no livery, so redrawing them buys nothing.
    #
    # ⭐ Quitanieves.png (the snowplough) is NO LONGER LOADED — builder 46 left
    # the model on 2026-07-30 and its texture went with it. `main()` prunes the
    # orphaned plough.png by name.
    BLUE = [(0, 0, 255)]
    tex["grille"] = Tex("grille", color_key(load("parrilla.png"), BLUE))
    tex["metal"] = Tex("metal", solid(A.DIAPHRAGM))

    # The coupler's cast steel. Drawn by tools/coupler.py so this car and the
    # R62 get byte-identical pixels — one coupler design, one plate.
    tex["coupler"] = Tex("coupler", CPL.draw_plate(K))

    cofres = load("Cofres.png")
    tex["cofres"] = Tex("cofres", cofres)
    cw = len(cofres[0])
    cx0 = int(round((6.5 - 4.3) / 13.0 * cw))
    cx1 = int(round((6.5 + 4.3) / 13.0 * cw))
    tex["cofres_mini"] = Tex("cofres_mini",
                             pngtool.crop(cofres, cx0, 0, cx1, len(cofres)),
                             cw, cx0, cx1)

    # SetColor-only donor builders become tiny solid swatches, which keeps the
    # whole model on one code path (every material has a map_Kd) instead of
    # mixing in the MTL's Kd/d flat-tint path.
    for name, rgb in (("reveal", A.GASKET),
                      ("underbody", (14, 15, 17)),
                      ("frame", A.DIAPHRAGM),
                      ("threshold", A.ANTICLIMBER_LO),
                      ("bracket", A.STEEL_LO),
                      ("floor", (68, 70, 73)),
                      ("lamp_white", A.LAMP_LENS_HOT),
                      ("lamp_red", A.LAMP_RED),
                      ("default", A.STEEL)):
        tex[name] = Tex(name, solid(rgb))
    return tex


# ------------------------------------------------------------- shared bay kit

SIDE_PTS = [(x, y, v) for (y, x, v) in SIDE_PROFILE]
INTERIOR_HALF = SIDE_PROFILE[0][1] - LINING_INSET     # widest the floor may be


def u_at_for(bay, tex):
    def u_at(mz):
        return tex.u(u_of_z(_bay_donor_z(bay, mz)))
    return u_at


def side_wall(group, tex, bay, zd0, zd1, pts=None):
    z0, z1 = bay.z(zd0), bay.z(zd1)
    strip(group, tex.material, pts or SIDE_PTS,
          min(z0, z1), max(z0, z1), u_at_for(bay, tex))


def roof_shell(group, tex, bay, zd0, zd1):
    z0, z1 = bay.z(zd0), bay.z(zd1)
    strip(group, tex.material, ROOF_PROFILE,
          min(z0, z1), max(z0, z1), u_at_for(bay, tex))


def roof_y_at(x_m):
    """donor y of the roof dome at |x|, off ROOF_PROFILE's own points."""
    p = ROOF_PROFILE
    for i in range(len(p) - 1):
        x0, x1 = p[i][0], p[i + 1][0]
        if min(x0, x1) - 1e-9 <= abs(x_m) <= max(x0, x1) + 1e-9:
            t = 0.0 if x0 == x1 else (abs(x_m) - x0) / (x1 - x0)
            return p[i][1] + (p[i + 1][1] - p[i][1]) * t
    return p[-1][1]


def hvac_tier(group, material, bay, span, half_x, y_top, y_bot):
    """One box of a roof raft: lid, outer flank and two ends. +x half only.

    Everything here is authored for x 0..half_x and mirrored by
    `add_mirrored`, like the roof and the cab mask, so there is no inboard
    flank — the two halves meet on the centreline and the join is internal.

    ⭐ THE BOTTOM IS FLAT AND THE ROOF IS NOT. `y_bot` is the dome's height at
    the raft's OUTER edge, i.e. its lowest point under the box, so the flanks
    land exactly on the roof there and the end walls run on BELOW the dome
    everywhere inboard of that. That buried strip is the point: MTR buries
    every abutment rather than matching a curve, and a box that tried to follow
    this dome would need a quad per ROOF_PROFILE segment per end, seven times
    the triangles for a join nobody can see.
    """
    z_lo, z_hi = sorted((bay.z(span[0]), bay.z(span[1])))
    v_out, v_mid = HVAC_LID_V
    s_hi, s_lo = HVAC_SIDE_V

    flat_z(group, material, y_top, 0.0, half_x, z_lo, z_hi,
           [(0.0, v_mid), (1.0, v_mid), (1.0, v_out), (0.0, v_out)], up=True)

    # The outer flank. Top point first with dy < 0 and dx = 0 is what `strip`
    # turns into an outward-facing vertical quad; written out here because the
    # uv wanted is the flank band, not the roof's own u map.
    group.quad(material,
               (sx(half_x), sy(y_top), z_lo, 0.0, s_hi),
               (sx(half_x), sy(y_top), z_hi, 1.0, s_hi),
               (sx(half_x), sy(y_bot), z_hi, 1.0, s_lo),
               (sx(half_x), sy(y_bot), z_lo, 0.0, s_lo))

    for z, outward in ((z_hi, True), (z_lo, False)):
        plane_z(group, material, z,
                [(0.0, y_bot), (half_x, y_bot), (half_x, y_top), (0.0, y_top)],
                [(0.0, s_lo), (1.0, s_lo), (1.0, s_hi), (0.0, s_hi)],
                outward_pos_z=outward)


def hvac_raft(group, tex, bay):
    """Both tiers of whichever roof raft lands in `bay`, or nothing.

    HVAC_Z is still the authority on where the equipment sits; the bay decides
    which raft it owns by containing it, exactly the way `saloon_glass` decides
    which panes are its. Both rafts are wholly inside an end bay at both car
    lengths, so no raft is ever cut by a bay boundary — the pane rule, applied
    to the roof.
    """
    material = tex.material
    for span in HVAC_Z:
        if not (bay.contains(span[0]) and bay.contains(span[1])):
            continue
        hvac_tier(group, material, bay, span, HVAC_BASE_HALF_X,
                  HVAC_BASE_Y, roof_y_at(HVAC_BASE_HALF_X))
        # The hump sits on the base's lid, over the OUTBOARD share of it: on
        # the prototype the condenser stack is at the car end and the flat part
        # is the plenum, and putting it outboard also keeps it away from the
        # bay seam, which is inboard.
        za, zb = span
        hump_zb = za + (zb - za) * HVAC_HUMP_FRAC
        hvac_tier(group, material, bay, (za, hump_zb), HVAC_HUMP_HALF_X,
                  HVAC_HUMP_Y, HVAC_BASE_Y)


def underbody(group, tex, z0, z1):
    """The black plate that closes the bottom of the body between the bogies."""
    flat_z(group, tex.material, UNDERBODY_Y, 0.0, UNDERBODY_HALF,
           min(z0, z1), max(z0, z1), [(0.5, 0.5)] * 4, up=False)


# Donor metres the saloon pane sits INBOARD of the skin. The window is a hole
# in a single-sided skin, so the pane has to be behind it or it would be the
# first thing the aperture's edge cuts into; 20 mm is deep enough to read as a
# recessed window and still well clear of the interior lining at 60 mm.
SALOON_GLASS_INSET = 0.020


def saloon_glass(group, material, bay, rects):
    """Translucent panes over the apertures `open_glazing` cut in this bay.

    Boxes come in as full-elevation uv and are filtered by the bay itself, so
    a pane lands in whichever bay contains the donor z it was drawn at and
    nowhere else — the window bay gets one, the gangway end gets two, the cab
    end gets its short window and the cab access door's.

    The quad follows the tumblehome (x is a function of y), which keeps it
    planar and parallel to the skin instead of poking through it at the sill.
    Winding matches `strip`'s: upper edge first, min z first, which is +x
    outward and independent of whether the bay's z map is reversed.
    """
    made = 0
    for (u0, u1, v0, v1) in rects:
        za, zb = z_of_u(u0), z_of_u(u1)
        if not (bay.contains(za) and bay.contains(zb)):
            continue
        y_hi, y_lo = side_y(v0), side_y(v1)
        x_hi = interp_profile(SIDE_PROFILE, y_hi)[0] - SALOON_GLASS_INSET
        x_lo = interp_profile(SIDE_PROFILE, y_lo)[0] - SALOON_GLASS_INSET
        z0, z1 = sorted((bay.z(za), bay.z(zb)))
        group.quad(material,
                   (sx(x_hi), sy(y_hi), z0, 0.0, 0.0),
                   (sx(x_hi), sy(y_hi), z1, 1.0, 0.0),
                   (sx(x_lo), sy(y_lo), z1, 1.0, 1.0),
                   (sx(x_lo), sy(y_lo), z0, 0.0, 1.0))
        made += 1
    return made


def profile_between(y_top, y_bot):
    """The body cross-section between two donor heights, top point first."""
    ys = [y_top] + [y for (y, _x, _v) in SIDE_PROFILE if y_bot < y < y_top] + [y_bot]
    out = []
    for y in ys:
        x, v = interp_profile(SIDE_PROFILE, y)
        out.append((x, y, v))
    return out


# ------------------------------------------------------------------ the bays

def build_window_bay(model, tex):
    bay = WINDOW_BAY
    z0, z1 = bay.z(bay.za), bay.z(bay.zb)
    ext = model.group("window_exterior")
    side_wall(ext, tex["lat_window"], bay, bay.za, bay.zb)
    saloon_glass(ext, tex["glass"].material, bay, tex["lat_window"].glazing)
    underbody(ext, tex["underbody"], z0, z1)
    roof_shell(model.group("roof"), tex["techo_window"], bay, bay.za, bay.zb)
    # No `window` group and no `window_floor`: the saloon lining AND the
    # visible floor are the interior model's job now.
    # See THE INTERIOR IS A SEPARATE MODEL in this file's docstring.


# The leaf, straight off PuertaR1.csv: (|x|, donor y, v), head first so it runs
# in the same direction the strip helper wants.
LEAF_PTS = [(1.437, 3.270, 0.00),
            (1.520, 1.900, 0.70),
            (1.510, 1.700, 0.75),
            (1.450, 1.370, 0.85),
            (1.450, 1.295, 1.00)]
LEAF_U0_Z = -7.360           # donor z where Puerta.png u = 0
LEAF_U1_Z = -5.935           # donor z where Puerta.png u = 1


def build_door_bay(model, tex):
    """One door bay: wall with a real hole, rubber reveal, and one sliding leaf.

    The donor has no hole — the doorway is painted on LAT.png and the leaf is a
    decal over it — so the opening is cut here from the GOMA NEGRA reveal's own
    footprint (builders 54-57), which is what actually defines the aperture.
    """
    bay = DOOR_BAY
    z_a, z_b = bay.z(bay.za), bay.z(bay.zb)
    z_lo, z_hi = min(z_a, z_b), max(z_a, z_b)
    o0, o1 = sorted((bay.z(LEAF_U0_Z), bay.z(LEAF_U1_Z)))   # opening, M units
    lat, roof = tex["lat_door"], tex["techo_door"]
    u_at = u_at_for(bay, lat)

    ext = model.group("door_exterior")
    above = profile_between(SIDE_PROFILE[0][0], DOOR_TOP)
    below = profile_between(DOOR_SILL, SIDE_PROFILE[-1][0])
    jamb = profile_between(DOOR_TOP, DOOR_SILL)
    strip(ext, lat.material, above, z_lo, z_hi, u_at)          # over the head
    strip(ext, lat.material, below, z_lo, z_hi, u_at)          # under the sill
    strip(ext, lat.material, jamb, z_lo, o0, u_at)             # beside it
    strip(ext, lat.material, jamb, o1, z_hi, u_at)
    underbody(ext, tex["underbody"], z_lo, z_hi)

    # Reveal: the body surface stepped 0.1 m inboard around the aperture. Two
    # jambs facing into the opening plus a soffit; the donor has no sill piece
    # (the floor closes it) and neither does this.
    rev = tex["reveal"].material
    for z, outward in ((o0, True), (o1, False)):
        for i in range(len(jamb) - 1):
            xt, yt, _ = jamb[i]
            xb, yb, _ = jamb[i + 1]
            plane_z(ext, rev, z,
                    [(xb - REVEAL_DEPTH, yb), (xb, yb), (xt, yt),
                     (xt - REVEAL_DEPTH, yt)],
                    [(0.5, 0.5)] * 4, outward_pos_z=outward)
    xh = jamb[0][0]
    flat_z(ext, rev, DOOR_TOP, xh - REVEAL_DEPTH, xh, o0, o1,
           [(0.5, 0.5)] * 4, up=False)

    roof_shell(model.group("roof_door"), roof, bay, bay.za, bay.zb)

    # The sill strip, from the saloon floor's edge out to the bodyside. It used
    # to be `doorway`, `type: DOORWAY` — which drew nothing (MTR renders only
    # NORMAL and DISPLAY parts) and registered nothing (an .obj feeds MTR no
    # doorway boxes at all). Renamed and re-typed so it finally draws; the box
    # MTR actually reads is `doorway_box` in m7_doors.bbmodel.
    #
    # ⭐ IT IS A BOX since the depth pass, not a plate. MTR puts a 1x1 threshold
    # under every doorway in its corpus, projecting about half a unit past the
    # skin, and this was a zero-thickness plate whose nosing simply had no
    # edge — from a platform you saw the floor stop in mid-air. The box drops a
    # whole unit below the sill, so it reads as a step plate with an underside.
    #
    # It sits ENTIRELY BELOW the door leaf (whose lowest plane is the sill at
    # donor y 1.295, which is this box's TOP), so it cannot foul the pocket
    # corridor however far the leaf travels — `check` measures that rather than
    # assuming it.
    # +x half only, like every other side part in this bay — `positionsFlipped`
    # supplies the other one, so a full-width box here would ship two.
    box(model.group("door_threshold"), tex["floor"].material,
        INTERIOR_HALF,
        interp_profile(SIDE_PROFILE, DOOR_SILL)[0] + THRESHOLD_PROUD_M,
        DOOR_SILL - THRESHOLD_DROP_M, DOOR_SILL, o0, o1)

    # No `door` group either — the vestibule lining comes from the interior
    # model's own int_door bay, built from AInterior rather than synthesized.

    # THE LEAVES ARE NOT BUILT HERE. They used to be — four .obj groups, one
    # per stage per opening — and they were wrong in game: MTR 4.0.5 offsets an
    # animating .obj door part twice (once baked into the mesh, once at render
    # time), so a leaf jumped 5.5 blocks clear of the car, and .obj parts feed
    # MTR no DOORWAY boxes, which is what binds a leaf to one side. They now
    # live in `tools/gen_m7_doors.py` as a companion .bbmodel — read its
    # docstring before reinstating anything here. LEAF_PTS above is kept as the
    # donor's own measurement of the leaf, which that generator's flat cuboid
    # is derived from.


# ---------------------------------------------------------------- end bays
#
# End bays are FULL WIDTH and placed by a single-list definition, exactly as
# r179 does. That is not decoration: an end is not symmetric in z, so it cannot
# be mirrored by a positionsFlipped entry the way a window bay can. Both ends
# are the SAME geometry, authored with the outward face at local -z; `end1`
# places it unflipped and `end2` places it flipped, which turns it round.

def add_mirrored(dst, src):
    """Add a half-width group and its x-mirror. Mirroring reverses winding."""
    for material, vs in src.faces:
        dst.faces.append((material, vs))
        dst.faces.append((material,
                          [(-x, y, z, u, v) for (x, y, z, u, v) in reversed(vs)]))


# The glazing tint. Lives in tools/m7_art.py and is shared verbatim with
# tools/convert_m7_interior.py's partition glass, so the cab reads as one
# material through the windscreen and through the bulkhead behind it. Alpha
# 76/255 keeps the console clearly legible from outside while still giving the
# pane a sheen.
GLASS_RGBA = A.GLASS_RGBA
# Donor metres the pane stands OUTBOARD of the mask. The mask is a ruled
# surface whose triangles wander about 10 mm off cab_face_z(), so 30 mm clears
# it everywhere; going inboard instead would fight the interior model's own
# nose skin, which sits in very nearly the same plane.
GLASS_PROUD = 0.030


def mask_uv_map(builder):
    """Solve the cab mask's linear uv<->space map from the donor's own verts.

    M7frente.png is mapped u across the car and v down it, both exactly linear
    (u = 0.5 - x/3.217, v = 1.366 - y/2.866 as the donor happens to have drawn
    it). Fitting it here instead of typing it means the glass panes follow the
    art if the donor is ever re-sliced. Returns (x_of_u, y_of_v).
    """
    vs = builder.vertices
    ax, bx = min(vs, key=lambda v: v.x), max(vs, key=lambda v: v.x)
    ay, by = min(vs, key=lambda v: v.y), max(vs, key=lambda v: v.y)
    du = (bx.u - ax.u) / (bx.x - ax.x)
    dv = (by.v - ay.v) / (by.y - ay.y)
    return (lambda u: ax.x + (u - ax.u) / du,
            lambda v: ay.y + (v - ay.v) / dv)


def windscreen(group, material, bay, rect, x_of_u, y_of_v):
    """One pane of glass over an aperture cut out of the cab mask.

    Authored as its own quad rather than by re-materialling part of the mask:
    the mask is a fan of triangles round the centre door and every one of them
    would have had to be split. The pane follows the same ruled surface the
    mask does (z depends on |x| only, so the quad stays planar) and sits just
    proud of it, which puts the painted wipers and pane surround BEHIND the
    glass — where a wiper belongs — with no coplanar faces anywhere.
    """
    u0, u1, v0, v1 = rect
    x0, x1 = sorted((x_of_u(u0), x_of_u(u1)))
    y0, y1 = sorted((y_of_v(v0), y_of_v(v1)))

    def corner(x, y, u, v):
        return (sx(x), sy(y), bay.z(cab_face_z(x) + GLASS_PROUD), u, v)

    # CCW seen from +z, then reversed: end bays face outwards at local -z.
    vs = [corner(x0, y0, 0.0, 1.0), corner(x1, y0, 1.0, 1.0),
          corner(x1, y1, 1.0, 0.0), corner(x0, y1, 0.0, 0.0)]
    group.add(material, list(reversed(vs)))


def cab_face_z(x_m):
    """donor z of the cab front at a given |x| — it is a ruled surface from the
    perimeter (z 12.50) to the centre door plane (z 12.67)."""
    a = min(1.5, max(0.37, abs(x_m)))
    return 12.67 - (a - 0.37) / (1.5 - 0.37) * 0.17


def lamp(group, material, bay, x_m, y_m, half_w=0.090, half_h=0.075):
    """A marker lamp: a small quad just proud of the cab face.

    The M7's low lamps are painted into M7frente.png; these sit on top of them
    so a render stage can make them glow without lighting the whole mask.
    """
    z = bay.z(cab_face_z(x_m) + 0.008)
    plane_z(group, material, z,
            [(x_m - half_w, y_m - half_h), (x_m + half_w, y_m - half_h),
             (x_m + half_w, y_m + half_h), (x_m - half_w, y_m + half_h)],
            [(0.05, 0.95), (0.95, 0.95), (0.95, 0.05), (0.05, 0.05)],
            outward_pos_z=False)   # end bays always face outwards at local -z


# Donor z of the roof's front edge — where `roof_shell` stops at the cab end,
# and therefore the plane the cap has to close.
ROOF_CAP_Z = 12.500


def roof_cap(group, tex, bay):
    """The front closure of the roof dome, above the cab mask. +x half.

    ⭐ REPLACES DONOR BUILDER 9 (user-reported 2026-07-28: "the weird triangle
    thing" on the nose). That builder was a 13-face TRIANGLE FAN radiating from
    the two top corners of the cab door — which sit 0.17 m FORWARD of every
    other vertex in it, so the fan was not even planar. Thirteen long thin
    triangles, each getting its own flat normal (the converter emits no `vn`),
    read in game as a facetted wedge; and it sampled the cab mask's top rows,
    so the wedge was black where the roof around it is steel.

    Rebuilt here as a flat strip in the roof's own plane: one quad per
    ROOF_PROFILE segment, between the crown arc and the mask's top edge at
    donor y 3.400. Every vertex is at donor z 12.500, so the whole cap is ONE
    plane with ONE normal and cannot facet.

    ⭐ IT IS TEXTURED FROM THE CAB MASK, NOT THE ROOF (user decision,
    2026-07-28, second pass). The first rebuild also re-materialled it to the
    roof plan, which turned the whole forward-facing band above the
    windshields into grey roof steel; the user wants the black mask wrapping up
    over the top of the nose the way it did before. So the cap samples
    M7frente.png through the mask's OWN uv map (`mask_u`/`mask_v`), whose rows
    above v = MASK_V_AT_TOP are exactly the roof fillet band — black in the
    middle, with the body's stainless corner posts at the outside, which is
    what the fillet is. The roof PLAN keeps its ribs where it is actually roof:
    `roof_shell` runs from donor z 12.500 back down the car and is untouched.

    Built +x only and mirrored by `add_mirrored`, like the rest of the cab end.
    `add_mirrored` copies uv unchanged, which is right here because the fillet
    art is symmetric about x = 0.

    The last profile segment ends ON y 3.400 and so degenerates to a triangle;
    emitting it as a quad with two coincident points would leave a zero-area
    triangle in the mesh, so it is dropped.
    """
    z = bay.z(ROOF_CAP_Z)
    base = MASK_Y_TOP                     # donor y the mask's top edge sits at

    def vert(x_m, y_m):
        return (sx(x_m), sy(y_m), z, mask_u(x_m), mask_v(y_m))

    for i in range(len(ROOF_PROFILE) - 1):
        x0, y0 = ROOF_PROFILE[i][0], ROOF_PROFILE[i][1]
        x1, y1 = ROOF_PROFILE[i + 1][0], ROOF_PROFILE[i + 1][1]
        if abs(x1 - x0) < 1e-9 or y0 <= base + 1e-9:
            continue
        # CCW seen from +z, then reversed: end bays face outwards at local -z,
        # the same convention `windscreen` and the donor faces use.
        vs = [vert(x0, base), vert(x1, base)]
        if y1 > base + 1e-9:
            vs.append(vert(x1, y1))
        vs.append(vert(x0, y0))
        group.add(tex.material, list(reversed(vs)))


# --- the anticlimber, as a real plate instead of a painted band
#
# MTR's own cab ends carry an anticlimber as a box below the floor with rotated
# corner pieces; this car had it PAINTED on the mask — a grey band with ribs
# and a coupler pocket, flat on a nose that is itself a ruled surface. The
# plate below replaces it with geometry.
#
# ⭐ ITS FACE IS FLAT WHILE THE NOSE IS NOT, deliberately: a real anticlimber is
# a straight steel beam bolted across a curved end, so it stands 1.2 units
# proud on the centreline and 3.7 at the corners. Following `cab_face_z`
# instead would have cost a quad per ruled segment per face and made the beam
# curved, which no anticlimber is.
ANTICLIMBER_Y = (1.050, 1.438)   # donor y — exactly the band the mask paints
ANTICLIMBER_HALF_X = 1.470
ANTICLIMBER_FRONT_Z = 12.750     # clear of the mask's 12.670 centre plane
ANTICLIMBER_BACK_Z = 12.450      # ...and buried behind its 12.500 corners


def anticlimber(group, tex, bay):
    """The cab end's anticlimber beam. +x half; `add_mirrored` does the rest.

    ⭐ IT WEARS THE MASK'S OWN PIXELS. The plate sits directly in front of the
    band `draw_cab_mask` already paints — ribs, coupler pocket and all — so its
    face is unwrapped through `mask_u`/`mask_v` and samples exactly the texels
    it covers. No new texture, no second measurement, and the art cannot drift
    from the geometry because it IS the art the geometry hides. The returns
    take one dark texel out of the band's own bottom edge.

    `add_mirrored` copies uv unchanged, which is right here for the same reason
    it is right for `roof_cap`: everything drawn in this band is symmetric
    about x = 0.
    """
    material = tex.material
    y0, y1 = ANTICLIMBER_Y
    x1 = ANTICLIMBER_HALF_X
    z_front, z_back = bay.z(ANTICLIMBER_FRONT_Z), bay.z(ANTICLIMBER_BACK_Z)
    z_lo, z_hi = sorted((z_front, z_back))
    dark = (mask_u(0.0), mask_v(1.420))          # the band's own shadow line

    plane_z(group, material, z_front,
            [(0.0, y0), (x1, y0), (x1, y1), (0.0, y1)],
            [(mask_u(0.0), mask_v(y0)), (mask_u(x1), mask_v(y0)),
             (mask_u(x1), mask_v(y1)), (mask_u(0.0), mask_v(y1))],
            outward_pos_z=False)                 # end bays face out at local -z

    for y, up in ((y1, True), (y0, False)):
        flat_z(group, material, y, 0.0, x1, z_lo, z_hi, [dark] * 4, up=up)

    group.quad(material,
               (sx(x1), sy(y1), z_lo, dark[0], dark[1]),
               (sx(x1), sy(y1), z_hi, dark[0], dark[1]),
               (sx(x1), sy(y0), z_hi, dark[0], dark[1]),
               (sx(x1), sy(y0), z_lo, dark[0], dark[1]))


# ------------------------------------------------------------------- coupler
#
# The design is `tools/coupler.py` — shared verbatim with the R62, in M units.
# What is this car's alone is the two numbers below and the sign derivation.
#
# ⭐ ROOT_D IS A CHOICE HERE, NOT A MEASUREMENT, and the M7 is the reason the
# shared module takes it as a parameter at all. The R62's coupler disappears
# into a closed underframe box and its root is simply that box's outer face.
# This car has no such box at the ends: `underbody()` closes the body with a
# flat plate at donor y 1.000 that stops at donor z 12.500, and forward of that
# there is open air all the way to the coupling plane. So nothing hides a yoke,
# and the number is picked instead — 15 units puts the draft-gear pocket under
# the body where a real one sits, with 5 units of drawbar showing in front of
# it. It must stay clear of `coupler.MIN_ROOT_D`.
COUPLER_ROOT_D = 15.0


def coupler_knuckle_sign():
    """Which authored x is the coupler's own RIGHT, looking out of an end bay.

    ⭐ READ OFF THIS FILE'S OWN TWO CONVENTIONS, never typed — the R62's
    "a check that does not apply the transform is not a check" lesson. The
    outward direction comes from `WINDING_EXPECT`, which is the single place
    this file states that its end bays face out at local -z (and `--check`
    proves it against the emitted faces); the x sign comes from
    `to_obj_vertex` itself. Get either wrong and both knuckles at a joint land
    on the SAME side of the centreline, where they interpenetrate — and
    nothing in game reports it, because a coupler is 3 units of dark grey.
    """
    return CPL.knuckle_sign(
        outward_mz=WINDING_EXPECT["end_cab_exterior"][1],
        emit_x_sign=1 if to_obj_vertex(1.0, 0.0, 0.0)[0] > 0 else -1)


def coupler_assembly(group, tex, bay):
    """The coupler under one car end, projecting to the coupling plane.

    The bay's OUTER edge is the coupling plane — a bay of `units` authored
    centred on z = 0 and placed at the car end puts its outer face exactly at
    half the car's length, which is where MTR butts the next car (padding 0).
    So `d` counts inboard from `-units/2`, and this one line is the whole
    difference between this car's end bays and the R62's.
    """
    plane = -bay.units / 2.0

    def add_box(material, x0, x1, y0, y1, z0, z1):
        # coupler.py speaks M units throughout; this file's `box` takes donor
        # metres across and up, so `sx`/`sy` are undone here and nowhere else.
        # ⭐ `sy` is PIECEWISE (a second, compressed factor below the floor —
        # see m7_layout), so the inverse has to be too. Dividing by
        # Y_SCALE_ABOVE would have put the coupler 27% too far below the car.
        box(group, material, x0 / X_SCALE, x1 / X_SCALE,
            unsy(y0), unsy(y1), z0, z1)

    CPL.emit(add_box, tex.material, COUPLER_ROOT_D, coupler_knuckle_sign(),
             lambda d: plane + d)


def build_cab_end(model, tex, donor, bay, suffix=""):
    lat, roof = tex["lat_cab" + suffix], tex["techo_cab" + suffix]
    ext = model.group("end_cab_exterior" + suffix)

    # LAT.png stops at donor z 12.500 — past that is the nose, which the mask
    # covers — so the wall runs from the bay's inner limit out to there.
    half = Group("tmp")
    side_wall(half, lat, bay, 12.500, bay.zb)
    saloon_glass(half, tex["glass"].material, bay, lat.glazing)
    underbody(half, tex["underbody"], bay.z(12.500), bay.z(bay.zb))
    roof_shell(half, roof, bay, 12.500, bay.zb)
    hvac_raft(half, tex["hvac"], bay)
    anticlimber(half, tex["frente"], bay)
    # The cap is MASK-textured, not roof-textured — the black wraps over the
    # top of the nose. See roof_cap()'s docstring.
    roof_cap(half, tex["frente"], bay)
    add_mirrored(ext, half)

    # The coupler. NOT mirrored — the knuckle is on one side by design, which
    # is what lets two of them interlock instead of collide (coupler.py).
    coupler_assembly(ext, tex["coupler"], bay)

    # The mask and the underfloor grilles. Both are already full width in the
    # donor, so they go in as they are.
    #
    # NOT builder 9 any more — that was the roof cap, a non-planar triangle fan
    # textured off the mask; `roof_cap()` above rebuilds it flat, in the roof's
    # own steel. See that function.
    #
    # ⭐ AND NOT BUILDER 46 (2026-07-30, user request): that was QUITANIEVE, the
    # snowplough, a 5-segment blade slung under the anticlimber from donor z
    # 12.301 to 12.851 — i.e. right up to the coupling plane, which is why the
    # old clearance note had to point out that two coupled cars' plough tips
    # touched. The user reported it as "this weird snow plow object"; it is
    # gone, along with Quitanieves.png. The anticlimber above it stays.
    for index, key in ((0, "frente"), (1, "frente"), (2, "frente"),
                       (74, "grille"), (75, "grille")):
        donor_faces(ext, tex[key].material, donor.builders[index], bay,
                    tex=tex[key])
    # Glass in the three apertures open_glazing() just cut: both windscreens
    # and the cab door's window. Boxes come from the art, the uv map from the
    # mask's own vertices, so nothing here is a measured constant.
    x_of_u, y_of_v = mask_uv_map(donor.builders[0])
    for rect in tex["frente"].glazing:
        windscreen(ext, tex["glass"].material, bay, rect, x_of_u, y_of_v)

    # The LIRR side logos only survive on the full-length car; the mini end is
    # truncated inboard of donor z 8.9 and simply does not reach them.
    for index in (70, 71):
        b = donor.builders[index]
        if all(bay.contains(v.z) for v in b.vertices):
            donor_faces(ext, tex["logo"].material, b, bay, tex=tex["logo"])

    # THE TWO ROOF LAMPS ARE GONE (user decision, 2026-07-28). Donor builders
    # 17/18 were 10-sided lamp prisms poking out of the roof fillet at donor y
    # 3.65, and `draw_cab_mask` punched alpha-0 discs in the mask so they could
    # show through. They read as two floating circles on the nose. The lamps
    # that matter — the head and marker lamps in the orange band — are drawn on
    # the mask and lit by the `lamp()` quads below, and they stay.
    lights = model.group("headlights" + suffix)
    for sign in (-1.0, 1.0):
        lamp(lights, tex["lamp_white"].material, bay, sign * 0.820, 1.805)
    tail = model.group("tail_lights" + suffix)
    for sign in (-1.0, 1.0):
        lamp(tail, tex["lamp_red"].material, bay, sign * 1.100, 1.830)

    # No `end_cab` group: the cab-end saloon and its bulkhead are the interior
    # model's int_end_cab, off AInterior's own geometry.


def build_gangway_end(model, tex, donor, bay, suffix=""):
    lat, roof = tex["lat_rear" + suffix], tex["techo_rear" + suffix]
    ext = model.group("end_gangway_exterior" + suffix)

    half = Group("tmp")
    side_wall(half, lat, bay, -12.520, bay.zb)
    saloon_glass(half, tex["glass"].material, bay, lat.glazing)
    underbody(half, tex["underbody"], bay.z(-12.520), bay.z(bay.zb))
    roof_shell(half, roof, bay, -12.520, bay.zb)
    hvac_raft(half, tex["hvac"], bay)
    add_mirrored(ext, half)

    # Rear mask + its roof fillet. Builder 21's blue-keyed door window becomes a
    # real hole, which is right: this is the end a gangway connects to.
    for index in (20, 21, 22, 25):
        donor_faces(ext, tex["atras"].material, donor.builders[index], bay,
                    tex=tex["atras"])
    # UNIDAD DE ARTICULACION — the diaphragm: top rail, side rails, frame,
    # threshold and the two overhead brackets.
    for index, key in ((58, "frame"), (59, "frame"), (60, "frame"),
                       (61, "metal"), (62, "metal"), (63, "metal"),
                       (64, "threshold"), (65, "bracket"), (66, "bracket"),
                       (69, "reveal")):
        b = donor.builders[index]
        if all(bay.contains(v.z) for v in b.vertices):
            donor_faces(ext, tex[key].material, b, bay, tex=tex[key])

    # The coupler, identical to the cab end's. A married pair couples
    # gangway-to-gangway (cab_1 + cab_2, cabs facing outward), so THIS is the
    # joint a player actually stands next to.
    coupler_assembly(ext, tex["coupler"], bay)

    outer_z, inner_z = bay.z(-12.450), bay.z(bay.zb)
    # No `end_gangway` group: the gangway-end saloon and its end wall are the
    # interior model's int_end_gangway.


def build_underframe(model, tex, bay, name, tex_key):
    """The COFRES equipment raft, as a box spanning the bay.

    NOT copied from donor builder 45: that cube is 13 m long, and the mini car's
    raft has to be shorter, so it would run straight past the car end. Building
    it to the bay instead means each length gets a raft that fits and a crop of
    Cofres.png sized to match — the texture is one 13 m elevation and does not
    tile, so cropping is the only way to shorten it honestly.
    """
    group = model.group(name)
    material = tex[tex_key].material
    x, y0, y1 = 0.900, 0.250, 1.050
    z0, z1 = min(bay.ma, bay.mb), max(bay.ma, bay.mb)
    box(group, material, -x, x, y0, y1, z0, z1)
    # Re-do the two long sides with u running the length of the raft, so the
    # equipment elevation reads along the car instead of being stretched.
    group.faces = [f for f in group.faces
                   if not all(abs(abs(v[0]) - sx(x)) < 1e-6 for v in f[1])]
    for side in (-1.0, 1.0):
        a = (sx(side * x), sy(y0), z0, 0.0, 1.0)
        b = (sx(side * x), sy(y1), z0, 0.0, 0.0)
        c = (sx(side * x), sy(y1), z1, 1.0, 0.0)
        d = (sx(side * x), sy(y0), z1, 1.0, 1.0)
        group.add(material, [a, b, c, d] if side > 0 else [d, c, b, a])


# ------------------------------------------------------------------- emitting

def to_obj_vertex(x, y, z):
    """M units -> OBJ. One OBJ unit is one BLOCK, and x and z are negated."""
    return (-x / 16.0, y / 16.0, -z / 16.0)


def bake_v(v):
    """No flip. IN-GAME FINDING (2026-07-28): MTR's obj path samples v in IMAGE
    convention (v=0 = top texture row), same as the donor's own UVs — the first
    build baked 1-v here and every texture rendered upside down on an upright
    car (windows low, cab mask inverted; roof unaffected because its art is
    symmetric). Donor v passes through untouched, flipTextureV stays unset."""
    return v


def wrap01(t):
    """openBVE relies on texture repeat; donor builder 21 mirrors its panel with
    NEGATIVE u (-0.385/-0.615). Wrapping reproduces that mirror exactly and
    keeps every UV this file writes inside [0,1]."""
    if 0.0 <= t <= 1.0:
        return t
    return t - math.floor(t)


def wrap_face(values):
    """Bring a whole FACE's coordinates into [0,1] together, not one at a time.

    BUG THIS FIXES (found 2026-07-28, and it had made a decal invisible since
    the first build): the LIRR side logo's -x copy is donor builder 71, which
    mirrors itself with u running 0.0 -> -1.0. Wrapped per VERTEX, -1.0 and 0.0
    both land on 0.0 and the quad collapses to a single texture column — which
    for a logo whose left column is transparent means the logo simply never
    draws. Nothing logs; it is just not there. (Builder 21's -0.385/-0.615 pair
    survived per-vertex wrapping by luck: neither end is an exact integer.)

    Shifting the face by ONE integer keeps its width: -1.0..0.0 becomes
    0.0..1.0, reversed, which is the mirror openBVE draws. Falls back to the
    per-vertex wrap when a face genuinely spans more than one repeat, which
    nothing in this model does.
    """
    lo, hi = min(values), max(values)
    if hi - lo <= 1.0 + 1e-9:
        k = -math.floor(lo)
        if -1e-9 <= lo + k and hi + k <= 1.0 + 1e-9:
            return [v + k for v in values]
    return [wrap01(v) for v in values]


def write_obj(model, path, mtl_name, mats, mtl_path_of):
    verts, vert_index = [], {}
    uvs, uv_index = [], {}

    def vid(x, y, z):
        key = (round(x, 5), round(y, 5), round(z, 5))
        if key not in vert_index:
            verts.append(key)
            vert_index[key] = len(verts)
        return vert_index[key]

    def tid(u, v):
        key = (round(u, 6), round(v, 6))
        if key not in uv_index:
            uvs.append(key)
            uv_index[key] = len(uvs)
        return uv_index[key]

    body = []
    for name in model.order:
        group = model.groups[name]
        if not group.faces:
            continue
        body.append("g %s" % name)
        current = None
        for material, vs in group.faces:
            if material != current:
                body.append("usemtl %s" % material)
                current = material
            us = wrap_face([p[3] for p in vs])
            vsv = wrap_face([p[4] for p in vs])
            refs = []
            for i, (x, y, z, _u, _v) in enumerate(vs):
                ox, oy, oz = to_obj_vertex(x, y, z)
                refs.append("%d/%d" % (vid(ox, oy, oz),
                                       tid(us[i], bake_v(vsv[i]))))
            body.append("f " + " ".join(refs))

    out = ["# LIRR M7 — generated by tools/convert_openbve_m7.py from the",
           "# openBVE donor. DO NOT EDIT: re-run the converter instead.",
           "mtllib %s" % mtl_name, ""]
    out += ["v %.5f %.5f %.5f" % p for p in verts]
    out += ["vt %.6f %.6f" % p for p in uvs]
    out.append("")
    out += body
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write("\n".join(out) + "\n")

    mtl = ["# Generated by tools/convert_openbve_m7.py — do not edit.", ""]
    for material in sorted(mats):
        mtl += ["newmtl %s" % material, "Kd 1 1 1", "d 1",
                "map_Kd %s" % mtl_path_of(mats[material]), ""]
    with open(os.path.join(os.path.dirname(path), mtl_name), "w") as fh:
        fh.write("\n".join(mtl) + "\n")
    return len(verts), sum(len(g.faces) for g in model.groups.values())


def write_textures(tex, used, directory=None):
    directory = directory or TEX_DIR
    os.makedirs(directory, exist_ok=True)
    written = []
    for t in tex.values():
        if t.material not in used and t.name != "default":
            continue
        pngtool.write_png(os.path.join(directory, t.name + ".png"), t.rows)
        written.append(t.name)
    return sorted(written)


def build_model():
    donor = bve_csv.parse_csv(os.path.join(DONOR, "A.csv"))
    tex = build_textures(donor)
    model = Model()
    build_window_bay(model, tex)
    build_door_bay(model, tex)
    build_cab_end(model, tex, donor, CAB_BAY)
    build_cab_end(model, tex, donor, CAB_BAY_MINI, "_mini")
    build_gangway_end(model, tex, donor, GANGWAY_BAY)
    build_gangway_end(model, tex, donor, GANGWAY_BAY_MINI, "_mini")
    build_underframe(model, tex, UNDERFRAME_BAY, "underframe", "cofres")
    build_underframe(model, tex, UNDERFRAME_BAY_MINI,
                     "underframe_mini", "cofres_mini")
    return model, tex


def used_materials(model, tex):
    names = {m for g in model.groups.values() for m, _ in g.faces}
    return {t.material: t for t in tex.values() if t.material in names}


# ------------------------------------------------------- assembly / preview
#
# This is the positions sanity check as much as it is the preview: it applies
# the SAME transform MTR does (measured, see the module docstring), reading the
# real properties and definition files, so a wrong flipped-z sign shows up as a
# bay in the wrong place rather than as a surprise in game.

PROPS = os.path.join(OUR_NS, "properties/vehicle")
DEFS = os.path.join(OUR_NS, "properties/definition")
# The door leaves are the one part of the vehicle this file does NOT build —
# --check parses them out of their .bbmodel so the name cross-validation still
# covers the whole vehicle. tools/gen_m7_doors.py owns it.
DOORS_MODEL = os.path.join(MODEL_DIR, "m7_doors.bbmodel")


def load_placements(definition, properties):
    with open(os.path.join(DEFS, definition)) as fh:
        by_name = {d["name"]: d for d in json.load(fh)["positionDefinitions"]}
    # A group may legitimately appear twice under DIFFERENT conditions — r179
    # lists tail_lights for both ON_ROUTE_* and AT_DEPOT — so only a repeat
    # within one condition is a real double-draw. Placement itself is deduped
    # regardless, so the preview draws each instance once.
    seen, placed, out, dupes = set(), set(), [], []
    for prop in properties:
        with open(os.path.join(PROPS, prop)) as fh:
            data = json.load(fh)
        for part in data.get("parts", []):
            condition = part.get("condition", "NORMAL")
            for group in part["names"]:
                for dname in part.get("positionDefinitions", []):
                    d = by_name.get(dname)
                    if d is None:
                        continue
                    for key, flipped in (("positions", False),
                                         ("positionsFlipped", True)):
                        for e in d.get(key, []):
                            item = (group, float(e.get("x", 0.0)),
                                    float(e.get("z", 0.0)), flipped)
                            if (item, condition) in seen:
                                dupes.append(item)
                            seen.add((item, condition))
                            if item not in placed:
                                placed.add(item)
                                out.append(item)
    return out, dupes


def assemble(model, placements):
    """Bake every placement into one model, in the M frame.

        unflipped(X, Z): (x, y, z) -> ( x - X, y,  z + Z)
          flipped(X, Z): (x, y, z) -> (-x - X, y, -z - Z)

    Both are rotations, so no winding fix is needed either way.
    """
    out = Model()
    for (group, X, Z, flipped) in placements:
        src = model.groups.get(group)
        if src is None:
            continue
        dst = out.group(group)
        for material, vs in src.faces:
            if flipped:
                dst.faces.append((material, [(-x - X, y, -z - Z, u, v)
                                             for (x, y, z, u, v) in vs]))
            else:
                dst.faces.append((material, [(x - X, y, z + Z, u, v)
                                             for (x, y, z, u, v) in vs]))
    return out


# preview name -> (definition, the properties files the index stacks).
# m7_doors.json is listed so the double-placement check covers the leaves, but
# their groups live in the .bbmodel, so `assemble` skips them: the --assemble
# preview renders a doorless car by design.
_DOORS = "m7_doors.json"
VARIANTS = {
    "full_cab1": ("m7.json", ["m7_common.json", "m7_cab_1.json", "m7_end_2.json", _DOORS]),
    "full_cab3": ("m7.json", ["m7_common.json", "m7_cab_1.json", "m7_cab_2.json", _DOORS]),
    "full_trailer": ("m7.json", ["m7_common.json", "m7_end_1.json", "m7_end_2.json", _DOORS]),
    "mini_cab1": ("m7_mini.json", ["m7_common.json", "m7_cab_1.json", "m7_end_2.json", _DOORS]),
}


# --------------------------------------------------------------- consistency

def _resolve(identifier):
    """`station_announcer:models/vehicle/x.obj` -> a real path, or None if the
    resource belongs to another namespace (MTR's bogies)."""
    if not identifier.startswith("station_announcer:"):
        return None
    return os.path.join(OUR_NS, identifier.split(":", 1)[1])


def index_model_entries():
    """(model path, properties path) for every model the M7's vehicles stack.

    The pairing matters: a vehicle carries several models — the body .obj, the
    door .bbmodel, and whatever else gets added — and each one is bound by its
    OWN properties file. Cross-checking names against the union would let a
    typo in one model be covered by a group in another, and would flag every
    part of a model that this file does not build.

    ⭐ AND IT IS FILTERED TO `m7*` VEHICLES (2026-07-28, when the R62 arrived).
    `mtr_custom_resources.json` is now composed from every train this mod ships
    (tools/gen_vehicle_index.py), so reading it whole pulled the R62's door
    groups into this file's door-sign table and reported four leaves that "must
    sit in exactly one list" — a rule that is the M7's, not the R62's, whose
    rotationally symmetric leaf pair deliberately rides both. Nine false
    failures, none of them about the M7. Nothing this file WRITES changed.
    """
    path = os.path.join(RES, "assets/mtr/mtr_custom_resources.json")
    if not os.path.exists(path):
        return []
    with open(path) as fh:
        index = json.load(fh)
    out = []
    for vehicle in index.get("vehicles", []):
        if not vehicle.get("id", "").startswith("m7"):
            continue
        for entry in vehicle.get("models", []):
            model = _resolve(entry.get("modelResource", ""))
            prop = _resolve(entry.get("modelPropertiesResource", ""))
            if model and prop and os.path.exists(model) and os.path.exists(prop):
                if (model, prop) not in out:
                    out.append((model, prop))
    return out


def model_group_names(path, obj_groups, bb_groups):
    """The part names a model file offers. The two this script already parsed
    are passed in; anything else (the interior .obj, say) is read here."""
    if path == os.path.join(MODEL_DIR, "m7.obj"):
        return set(obj_groups)
    if path == DOORS_MODEL:
        return set(bb_groups)
    if path.endswith(".bbmodel"):
        with open(path) as fh:
            data = json.load(fh)
        return {o["name"] for o in data.get("outliner", []) if isinstance(o, dict)}
    names = set()
    with open(path) as fh:
        for line in fh:
            if line.startswith("g "):
                names.add(line[2:].strip())
    return names


def _x_at_height(edges, ylo, yhi):
    """max |x| of a set of (x, y) polylines within a height band.

    Edges are INTERPOLATED, not merely sampled at their vertices: both models
    only carry vertices at the cross-section's knee points, so a band can
    easily contain none at all and still be crossed by the surface. Taking the
    MAX is what makes this immune to the door bay's rubber reveal, which steps
    inboard of the skin and would wreck a min-based measurement.
    """
    best = None
    for poly in edges:
        n = len(poly)
        for i in range(n):
            (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
            if ylo - 1e-6 <= y0 <= yhi + 1e-6:
                best = abs(x0) if best is None else max(best, abs(x0))
            for edge_y in (ylo, yhi):
                if (y0 - edge_y) * (y1 - edge_y) <= 0 and abs(y1 - y0) > 1e-9:
                    t = (edge_y - y0) / (y1 - y0)
                    x = abs(x0 + (x1 - x0) * t)
                    best = x if best is None else max(best, x)
    return best


def body_half_width(group, y):
    """The bodyside's |x| at height y, from a group of this model.

    Deliberately a SECOND, independent implementation of what
    tools/gen_m7_doors.py measures when it places the pocket: that one reads
    m7.obj's `window_exterior` off disk, this one reads whichever group it is
    handed out of the in-memory model. Two routes to the same number is the
    point — a measurement bug in one shows up as a --check failure.
    """
    edges = [[(v[0], v[1]) for v in vs] for _m, vs in group.faces]
    return _x_at_height(edges, y, y)


def interior_extent(path):
    """-> f(ylo, yhi) = max |x| of anything in the interior model in that band.

    The side wall is the widest thing in there by a margin, so no filtering is
    needed. Taking the max over EVERY bay is deliberate: an open leaf slides
    out of the door bay and into the end bay, so it has to clear both.
    """
    verts, edges = [], []
    with open(path) as fh:
        for line in fh:
            if line.startswith("v "):
                x, y, _z = (float(t) for t in line.split()[1:4])
                verts.append((-16.0 * x, 16.0 * y))       # obj -> M frame
            elif line.startswith("f "):
                edges.append([verts[int(t.split("/")[0]) - 1]
                              for t in line.split()[1:]])
    return lambda ylo, yhi: _x_at_height(edges, ylo, yhi)


def face_normal(vs):
    """Newell's method — robust for the non-planar quads the tumblehome makes."""
    nx = ny = nz = 0.0
    for i in range(len(vs)):
        x0, y0, z0 = vs[i][0], vs[i][1], vs[i][2]
        x1, y1, z1 = vs[(i + 1) % len(vs)][0], vs[(i + 1) % len(vs)][1], vs[(i + 1) % len(vs)][2]
        nx += (y0 - y1) * (z0 + z1)
        ny += (z0 - z1) * (x0 + x1)
        nz += (x0 - x1) * (y0 + y1)
    return nx, ny, nz


# Group -> (axis, expected sign). Mirrored groups cancel in x and y, which is
# why the end bays are checked on z: their masks must face local -z.
WINDING_EXPECT = {
    "window_exterior": (0, +1), "roof": (1, +1),
    "door_exterior": (0, +1), "roof_door": (1, +1),
    "end_cab_exterior": (2, -1), "end_gangway_exterior": (2, -1),
    "end_cab_exterior_mini": (2, -1), "end_gangway_exterior_mini": (2, -1),
}


def exterior_clearances():
    """Re-measure everything the depth pass added. (problems, notes).

    ⭐ WHY. Each of the four new reliefs is bounded by something it cannot see
    from where it is defined: the roof rafts by the end bay they have to fit
    inside at BOTH car lengths, the drip rail by the body's own widest point,
    the door threshold by the sliding leaf's lowest plane, the anticlimber by
    the coupler face. Every one of those is a number in another module or
    another file, so none of them would fail loudly — a raft cut by a bay
    boundary just renders half a raft, and a threshold that crept up into the
    leaf's band would saw through a door only when it opened. Measured here,
    they fail on the build instead.
    """
    problems, notes = [], []

    # --- the roof rafts. THE PANE RULE, APPLIED TO THE ROOF: a raft lives
    # wholly inside one end bay or it gets sliced by the crop, exactly as a
    # half-drawn window would. Checked at both lengths, because the Mini's ends
    # are truncated and the truncation is what would cut one.
    for bay in (CAB_BAY, GANGWAY_BAY, CAB_BAY_MINI, GANGWAY_BAY_MINI):
        owned = [s for s in HVAC_Z if bay.contains(s[0]) and bay.contains(s[1])]
        if len(owned) != 1:
            problems.append("%s owns %d HVAC raft(s); each end bay must own "
                            "exactly one, wholly inside it" % (bay.name, len(owned)))
            continue
        za, zb = owned[0]
        for z in (bay.z(za), bay.z(zb)):
            if abs(z) > bay.units / 2.0 + 1e-6:
                problems.append("%s: its HVAC raft reaches %.1f, past the bay's "
                                "own %g units" % (bay.name, z, bay.units))
    if HVAC_BASE_Y <= ROOF_PROFILE[0][1]:
        problems.append("the HVAC base lid is at or below the roof crown — it "
                        "would be invisible")
    if HVAC_HUMP_Y <= HVAC_BASE_Y or HVAC_HUMP_HALF_X >= HVAC_BASE_HALF_X:
        problems.append("the HVAC hump does not sit on the base raft")
    notes.append("roof rafts %.0f/%.0f units above the crown, %.2f/%.2f m half "
                 "wide, one per end bay at both lengths"
                 % ((HVAC_BASE_Y - ROOF_PROFILE[0][1]) * Y_SCALE_ABOVE,
                    (HVAC_HUMP_Y - ROOF_PROFILE[0][1]) * Y_SCALE_ABOVE,
                    HVAC_BASE_HALF_X, HVAC_HUMP_HALF_X))

    # --- the drip rail must overhang the bodyside without changing the car's
    # silhouette, i.e. stay inboard of the widest point of the body.
    widest = max(x for (_y, x, _v) in SIDE_PROFILE)
    if DRIP_X > widest:
        problems.append("the drip rail reaches |x| %.3f, wider than the body's "
                        "own %.3f — it would set the car's width" % (DRIP_X, widest))
    if DRIP_X - SIDE_PROFILE[0][1] < UNIT_M_X - 1e-9:
        problems.append("the drip rail overhangs less than one M unit")
    notes.append("drip rail overhangs the cant rail by %.1f units and still "
                 "clears the body's widest point by %.0f mm"
                 % ((DRIP_X - SIDE_PROFILE[0][1]) * X_SCALE,
                    (widest - DRIP_X) * 1000))

    # --- the door threshold. It projects into the band the pocket doors slide
    # through in x, and the ONLY reason that is safe is that it is entirely
    # below the leaf. `LEAF_PTS` is the donor's own measurement of the leaf, so
    # this compares against the leaf itself rather than against a copy of it.
    leaf_bottom = min(y for (_x, y, _v) in LEAF_PTS)
    if DOOR_SILL > leaf_bottom + 1e-9:
        problems.append("the door threshold's tread (y %.3f) is inside the "
                        "leaf's band (from y %.3f) — it would foul the pocket"
                        % (DOOR_SILL, leaf_bottom))
    skin = interp_profile(SIDE_PROFILE, DOOR_SILL)[0]
    notes.append("door threshold %.1f units deep, %.1f proud of the skin, "
                 "top %.0f mm below the leaf"
                 % (THRESHOLD_DROP_M * Y_SCALE_ABOVE,
                    THRESHOLD_PROUD_M * X_SCALE, (leaf_bottom - DOOR_SILL) * 1000))

    # --- the anticlimber has to be proud of the mask everywhere and still
    # inboard of the coupler face, which is the end bay's outer donor limit.
    front, back = ANTICLIMBER_FRONT_Z, ANTICLIMBER_BACK_Z
    if front <= max(cab_face_z(0.0), cab_face_z(ANTICLIMBER_HALF_X)):
        problems.append("the anticlimber is not proud of the cab mask")
    if back >= min(cab_face_z(0.0), cab_face_z(ANTICLIMBER_HALF_X)):
        problems.append("the anticlimber's returns are not buried in the mask")
    if front > CAB_BAY.za + 1e-9:
        problems.append("the anticlimber reaches donor z %.3f, past the coupler "
                        "face at %.3f" % (front, CAB_BAY.za))
    if ANTICLIMBER_Y[1] > 1.438 + 1e-9 or ANTICLIMBER_Y[0] < 1.050 - 1e-9:
        problems.append("the anticlimber leaves the band the mask paints for it")
    notes.append("anticlimber stands %.1f units proud on the centreline, %.1f "
                 "at the corner, %.0f mm inboard of the coupling plane"
                 % ((front - cab_face_z(0.0)) * X_SCALE,
                    (front - cab_face_z(ANTICLIMBER_HALF_X)) * X_SCALE,
                    (CAB_BAY.za - front) * 1000))

    # --- the coupler. Bounded by four things it cannot see from coupler.py:
    # this car's underbody plate above it, the anticlimber in front of and
    # above it, the end bays it has to fit inside at BOTH lengths, and the
    # neighbouring car's coupler across the coupling plane.
    p, n = coupler_clearances()
    problems += p
    notes += n
    return problems, notes


def coupler_clearances():
    """Everything about the coupler that this car, and not `coupler.py`, owns.

    ⭐ WHY EACH ONE. The shared module promises a shape and an envelope; it
    knows nothing about an M7. Every bound below lives in a different file:

      * the UNDERBODY PLATE (`UNDERBODY_Y`) is the black pan that closes the
        bottom of the body. A coupler that reached above it would be inside the
        car, drawn through the floor.
      * the ANTICLIMBER is the one piece of geometry the coupler shares its
        end with, and the only thing between them is 0.9 units of air.
      * the BAY. A coupler rooted deeper than the bay is long gets sliced by
        the crop exactly as a half-drawn window would — the pane rule, applied
        to the underframe — and the Mini's ends are 13 units shorter, so it is
        the length that would fail first.
      * the JOINT. `coupled_pair` is arithmetic on the shared table; what is
        checked here is that this car's plane really is the bay's outer edge,
        which is what makes that arithmetic apply at all.
    """
    problems, notes = [], []
    boxes = CPL.parts(COUPLER_ROOT_D, coupler_knuckle_sign())
    top = max(y1 for (_n, _x0, _x1, _y0, y1, _d0, _d1) in boxes)
    bottom = min(y0 for (_n, _x0, _x1, y0, _y1, _d0, _d1) in boxes)
    deepest = max(d1 for (*_r, d1) in boxes)
    widest = max(max(abs(x0), abs(x1))
                 for (_n, x0, x1, _y0, _y1, _d0, _d1) in boxes)

    if top >= sy(UNDERBODY_Y) - 1e-9:
        problems.append("the coupler reaches M y %+.1f, at or above the "
                        "underbody plate at %+.1f — it would be inside the car"
                        % (top, sy(UNDERBODY_Y)))
    if top >= sy(ANTICLIMBER_Y[0]) - 1e-9:
        problems.append("the coupler reaches M y %+.1f, into the anticlimber "
                        "beam which starts at %+.1f"
                        % (top, sy(ANTICLIMBER_Y[0])))
    if bottom <= L.sy(L.LOWEST_Y) + 1e-9:
        problems.append("the coupler hangs to M y %+.1f, below the car's own "
                        "lowest underframe at %+.1f"
                        % (bottom, L.sy(L.LOWEST_Y)))
    for bay in (CAB_BAY, GANGWAY_BAY, CAB_BAY_MINI, GANGWAY_BAY_MINI):
        if deepest > bay.units - 1e-9:
            problems.append("the coupler roots %.1f units inboard, past the "
                            "whole %g-unit %s bay" % (deepest, bay.units, bay.name))
    if widest * 2 > sx(2 * UNDERBODY_HALF):
        problems.append("the coupler is wider than the underframe it hangs from")

    # The joint. Two cars share ONE plane (couplingPadding 0 in
    # gen_m7_assets.vehicle), so this is what a player sees between them.
    j = CPL.coupled_pair(COUPLER_ROOT_D, coupler_knuckle_sign())
    if j["lateral"] <= 0:
        problems.append("two coupled couplers interpenetrate by %.1f units "
                        "across the car — the knuckle offset is wrong"
                        % -j["lateral"])
    if j["head_gap"] <= 0:
        problems.append("a knuckle reaches into the other car's coupler head "
                        "by %.1f units" % -j["head_gap"])
    if j["plane_max"] <= 0:
        problems.append("something other than the knuckle crosses the coupling "
                        "plane")
    notes.append("coupler axis at donor y %.3f m (M %+.1f), %.0f mm below the "
                 "anticlimber, rooted %.0f units inboard; projects %.1f units "
                 "past the coupling plane"
                 % (unsy(CPL.CENTRE_Y), CPL.CENTRE_Y,
                    (ANTICLIMBER_Y[0] - unsy(top)) * 1000, COUPLER_ROOT_D,
                    CPL.KNUCKLE_PROUD))
    notes.append("coupled joint: knuckles interleave %.1f units and clear each "
                 "other by %.1f across the car; every other member stops %.1f "
                 "short of the plane"
                 % (j["overlap"], j["lateral"], j["plane_max"]))
    return problems, notes


def check(model, tex, obj_path):
    problems, notes = [], []
    mats = used_materials(model, tex)

    p, n = exterior_clearances()
    problems += p
    notes += n

    # 1. every group carries geometry, and winding points the right way
    for name in model.order:
        if not model.groups[name].faces:
            problems.append("group %r is empty" % name)
    for name, (axis, sign) in WINDING_EXPECT.items():
        group = model.groups.get(name)
        if group is None:
            problems.append("winding check: no group %r" % name)
            continue
        total = 0.0
        for _m, vs in group.faces:
            total += face_normal(vs)[axis]
        if total * sign <= 0:
            problems.append("group %r winding looks inverted (axis %d sum %.1f)"
                            % (name, axis, total))
    notes.append("%d groups, %d faces, winding verified on %d groups"
                 % (len(model.order),
                    sum(len(g.faces) for g in model.groups.values()),
                    len(WINDING_EXPECT)))

    # 2. the written OBJ: UV range, group set, materials all declared
    obj_groups, obj_mats, bad_uv = set(), set(), 0
    with open(obj_path) as fh:
        for line in fh:
            if line.startswith("g "):
                obj_groups.add(line[2:].strip())
            elif line.startswith("usemtl "):
                obj_mats.add(line[7:].strip())
            elif line.startswith("vt "):
                if any(not (-1e-6 <= float(p) <= 1 + 1e-6)
                       for p in line.split()[1:3]):
                    bad_uv += 1
    if bad_uv:
        problems.append("%d UVs outside [0,1]" % bad_uv)
    for m in obj_mats - set(mats):
        problems.append("usemtl %s has no material" % m)

    # 3. map_Kd targets resolve to files that exist
    mtl_path = os.path.join(os.path.dirname(obj_path), "m7.mtl")
    with open(mtl_path) as fh:
        for line in fh:
            if line.startswith("map_Kd "):
                ident = line.split(None, 1)[1].strip()
                if ident.count(".") != 1:
                    problems.append("map_Kd %r must contain exactly one dot" % ident)
                if not ident.startswith("station_announcer:"):
                    problems.append("map_Kd %r is not namespaced" % ident)
                rel = ident.split(":", 1)[1]
                if not os.path.exists(os.path.join(OUR_NS, rel)):
                    problems.append("map_Kd %r has no file" % ident)
                if ident != ident.lower():
                    problems.append("map_Kd %r is not lowercase" % ident)

    # 4. group names <-> properties `names`, both directions, across BOTH
    #    models: the body is this .obj, the door leaves are the companion
    #    .bbmodel that tools/gen_m7_doors.py writes.
    bb_groups, elements = set(), {}
    if os.path.exists(DOORS_MODEL):
        with open(DOORS_MODEL) as fh:
            bb = json.load(fh)
        bb_groups = {o["name"] for o in bb.get("outliner", []) if isinstance(o, dict)}
        elements = {e["uuid"]: e for e in bb.get("elements", [])}
        for outline in bb.get("outliner", []):
            kids = [c for c in outline.get("children", []) if isinstance(c, str)]
            if not kids:
                problems.append("bbmodel group %r has no elements" % outline["name"])
            for uuid in kids:
                e = elements.get(uuid)
                if e is None:
                    problems.append("bbmodel group %r references a missing element"
                                    % outline["name"])
                    continue
                # Vanilla rounds each dimension to an int; a fractional size
                # would silently render at a different size than the JSON says.
                for axis in range(3):
                    span = e["to"][axis] - e["from"][axis]
                    if abs(span - round(span)) > 1e-9:
                        problems.append("bbmodel element %r has non-integer size "
                                        "on axis %d (%.3f)" % (e["name"], axis, span))
        notes.append("m7_doors.bbmodel: %d groups, %d elements, resolution %dx%d"
                     % (len(bb_groups), len(elements),
                        bb["resolution"]["width"], bb["resolution"]["height"]))
    else:
        problems.append("m7_doors.bbmodel is missing — run tools/gen_m7_doors.py")
    model_groups = obj_groups | bb_groups

    # A vehicle stacks several models, each with its OWN properties file, so
    # the pairing has to come from the index — checking every m7*.json against
    # every model would flag the interior's part names against the body and
    # vice versa. Bogies (mtr: namespace) are MTR's, not ours.
    declared_by_model = {}
    multipliers, door_defs = {}, {}
    for model_path, prop in index_model_entries():
        names = declared_by_model.setdefault(model_path, set())
        with open(prop) as fh:
            data = json.load(fh)
        for part in data.get("parts", []):
            for group in part["names"]:
                names.add(group)
                if "doorZMultiplier" in part:
                    multipliers.setdefault(group, set()).add(part["doorZMultiplier"])
                    for d in part.get("positionDefinitions", []):
                        door_defs.setdefault(group, set()).add(d)

    for model_path, declared in sorted(declared_by_model.items()):
        groups = model_group_names(model_path, obj_groups, bb_groups)
        label = os.path.basename(model_path)
        for group in sorted(declared - groups):
            problems.append("%s: properties name %r has no group in it"
                            % (label, group))
        for group in sorted(groups - declared):
            problems.append("%s: group %r is not bound by any properties file"
                            % (label, group))
    if not declared_by_model:
        problems.append("the index binds no models — run tools/gen_m7_assets.py")
    if multipliers.keys() - bb_groups:
        problems.append("door multipliers on non-bbmodel groups %s — MTR's .obj "
                        "path offsets animating doors twice"
                        % sorted(multipliers.keys() - bb_groups))

    # 5. the door leaves: four one-sided groups, each retracting towards its
    #    own end of the car.
    #
    #    The .bbmodel path composes a placement as R*v + t, so BOTH lists land
    #    a z-symmetric group at +z/16; the side comes from which list it is in.
    #    The multiplier is applied to that same translation, so the effective
    #    slide direction is sign(m) unflipped and -sign(m) flipped, and it must
    #    point at the end the leaf sits nearest.
    for definition in sorted(os.listdir(DEFS)):
        if not definition.startswith("m7"):
            continue
        with open(os.path.join(DEFS, definition)) as fh:
            by_name = {d["name"]: d for d in json.load(fh)["positionDefinitions"]}
        seen_sides = {}
        for group, defs in sorted(door_defs.items()):
            if len(defs) != 1:
                problems.append("%s: leaf %r uses %d position definitions"
                                % (definition, group, len(defs)))
                continue
            d = by_name.get(list(defs)[0])
            if d is None:
                problems.append("%s: leaf %r has no definition %r"
                                % (definition, group, list(defs)[0]))
                continue
            entries = [(e, False) for e in d.get("positions", [])]
            entries += [(e, True) for e in d.get("positionsFlipped", [])]
            if len(entries) != 1:
                problems.append("%s: leaf %r resolves to %d placements, want 1 "
                                "(a leaf must sit in exactly one list)"
                                % (definition, group, len(entries)))
                continue
            entry, flipped = entries[0]
            z = float(entry.get("z", 0.0))
            got = multipliers[group]
            if len(got) > 1:
                problems.append("%s: leaf %r has conflicting multipliers %s"
                                % (definition, group, sorted(got)))
                continue
            slide = list(got)[0] * (-1 if flipped else 1)
            if slide * z <= 0:
                problems.append("%s: leaf %r at z=%+g slides %+g — away from its "
                                "own end of the car" % (definition, group, z, slide))
            seen_sides.setdefault((flipped, z > 0), []).append(group)
        for key, groups in sorted(seen_sides.items()):
            if len(groups) != 2:      # the INTERIOR box and its EXTERIOR skin
                problems.append("%s: %d leaf groups at flipped=%s z>0=%s (%s)"
                                % (definition, len(groups), key[0], key[1], groups))
        if len(seen_sides) != 4:
            problems.append("%s: leaves cover %d of the 4 (side, end) corners"
                            % (definition, len(seen_sides)))
        else:
            notes.append("%-13s 4 door leaves, one per side x end, signs OK"
                         % definition)

    # 5b. THE POCKET. The leaf slides INTO the body, in the cavity between the
    #     exterior skin and the interior lining, so the invariants are:
    #     it covers the aperture, the travel clears the aperture, and at every
    #     height it is strictly INBOARD of the skin (or it would poke through
    #     the bodyside when open) and strictly OUTBOARD of the lining (or it
    #     would poke into the saloon). The middle one used to be the opposite
    #     — "proud of the body" — back when the leaf slid along the outside.
    #
    #     Compared on MAGNITUDES: the sign relating the .obj authoring frame to
    #     the .bbmodel one is not pinned down (and does not need to be — the
    #     apertures are 4-fold symmetric), but the sizes must agree.
    planes = [e for e in elements.values() if e["from"][0] == e["to"][0]]
    if planes and multipliers:
        aperture = model.groups.get("door_threshold")
        jamb = model.groups.get("door_exterior")
        interior_path = os.path.join(MODEL_DIR, "m7_interior.obj")
        if aperture and jamb and os.path.exists(interior_path):
            az = max(abs(v[2]) for _m, vs in aperture.faces for v in vs)
            half = max(max(abs(e["from"][2]), abs(e["to"][2])) for e in planes)
            # R179's curve opens by |multiplier| - 0.5 (an initial jog, then a
            # constant slide from v >= 0.6) — M7_CONVERSION_NOTES.md part 1.
            travel = max(abs(m) for ms in multipliers.values() for m in ms) - 0.5
            if half < az:
                problems.append("leaf half-width %.2f does not cover the %.2f "
                                "aperture" % (half, az))
            # Travel is measured against the APERTURE, not against the leaf.
            # The leaf is deliberately wider (integer rounding is UP so it laps
            # its jambs instead of leaving a slit), and what has to clear when
            # the door opens is the hole — a leaf whose trailing edge is still
            # over the jamb at full open is a door, not a fault.
            if travel < 2 * az:
                problems.append("R179 travel %.2f does not clear the %.2f "
                                "aperture" % (travel, 2 * az))

            lining = interior_extent(interior_path)
            worst_skin = worst_lining = None
            for e in planes:
                x = abs(e["from"][0])
                ylo, yhi = e["from"][1], e["to"][1]
                skin = min(body_half_width(jamb, y)
                           for y in [ylo + i * (yhi - ylo) / 20.0
                                     for i in range(21)])
                lin = lining(ylo, yhi)
                if skin is None or lin is None:
                    continue
                if x >= skin:
                    problems.append("leaf plane at x %.3f (y %g..%g) is not "
                                    "inboard of the %.3f bodyside — an open "
                                    "leaf would poke through it"
                                    % (x, ylo, yhi, skin))
                if x <= lin:
                    problems.append("leaf plane at x %.3f (y %g..%g) is not "
                                    "outboard of the %.3f interior lining — it "
                                    "would poke into the saloon"
                                    % (x, ylo, yhi, lin))
                gap_out, gap_in = skin - x, x - lin
                worst_skin = gap_out if worst_skin is None else min(worst_skin, gap_out)
                worst_lining = gap_in if worst_lining is None else min(worst_lining, gap_in)
            if worst_skin is not None:
                notes.append("pocket leaf %.1f wide over a %.2f aperture, "
                             "travel %.2f, %d planes, tightest clearance "
                             "%.3f to the skin / %.3f to the lining"
                             % (2 * half, 2 * az, travel, len(planes),
                                worst_skin, worst_lining))

    # 5c. the DOORWAY and FLOOR boxes have to exist, in the .bbmodel and
    #     nowhere else. MTR only reads them from a .bbmodel, and it synthesizes
    #     a fallback (one car-length floor, a doorway box every block down both
    #     sides) whenever floors AND doorways are both empty — so shipping one
    #     without the other silently disables the other one too.
    kinds = {}
    for model_path, prop in index_model_entries():
        with open(prop) as fh:
            for part in json.load(fh).get("parts", []):
                if part.get("type") in ("FLOOR", "DOORWAY"):
                    kinds.setdefault(part["type"], set()).add(
                        (os.path.basename(model_path), tuple(part["names"])))
    for kind in ("FLOOR", "DOORWAY"):
        entries = kinds.get(kind, set())
        if not entries:
            problems.append("no %s part anywhere — MTR will fall back to "
                            "synthesizing them down the whole car" % kind)
        for source, names in sorted(entries):
            if not source.endswith(".bbmodel"):
                problems.append("%s part %s is in %s; MTR reads %s boxes only "
                                "from a .bbmodel" % (kind, list(names), source, kind))
    if kinds.get("FLOOR") and kinds.get("DOORWAY"):
        notes.append("%d FLOOR + %d DOORWAY part(s), all .bbmodel — the "
                     "synthesized fallback is off"
                     % (len(kinds["FLOOR"]), len(kinds["DOORWAY"])))

    # 5d. DISPLAY parts must live in a .bbmodel and nowhere else. MTR 4.0.5's
    #     .obj writeCache returns immediately for any part whose type is not
    #     NORMAL, and `ModelDisplayPart` can only be built from a Blockbench
    #     element's from/to — so a DISPLAY part on the .obj is silently
    #     invisible, with nothing logged. Exactly the FLOOR/DOORWAY trap.
    displays = set()
    for model_path, prop in index_model_entries():
        with open(prop) as fh:
            for part in json.load(fh).get("parts", []):
                if part.get("type") == "DISPLAY":
                    displays.add((os.path.basename(model_path),
                                  tuple(part["names"])))
    for source, names in sorted(displays):
        if not source.endswith(".bbmodel"):
            problems.append("DISPLAY part %s is in %s; MTR 4.0.5 cannot render "
                            "a display on an .obj at all" % (list(names), source))
    if displays:
        notes.append("%d DISPLAY part(s), all .bbmodel" % len(displays))

    #     ...and each display element has to land ON the sign box this file
    #     paints on the cab mask. Three tools have to agree about one rectangle
    #     (m7_layout owns it, this file paints it, gen_m7_doors places the
    #     element), and nothing in game would report a mismatch — the text
    #     would just float on plain black.
    # Only the CAB-FRONT displays are checked against the mask — the interior
    # next-stop screens are the interior converter's business and are guarded
    # by m7_layout._verify() plus that file's own housing checks.
    named = {n for _src, names in displays for n in names
             if n.startswith("front_")}
    with open(DOORS_MODEL) as fh:
        for el in json.load(fh).get("elements", []):
            if el["name"] not in named:
                continue
            frm, to = el["from"], el["to"]
            box_lo, box_hi = L.SIGN_BOX_Y_M
            y_lo = FLOOR_Y + min(frm[1], to[1]) / L.Y_SCALE_ABOVE
            y_hi = FLOOR_Y + max(frm[1], to[1]) / L.Y_SCALE_ABOVE
            x_hi = max(abs(frm[0]), abs(to[0])) / L.X_SCALE
            if not (box_lo <= y_lo and y_hi <= box_hi
                    and x_hi <= L.SIGN_HALF_X_M + 1e-9):
                problems.append("display element %r (donor x +-%.3f, y "
                                "%.3f..%.3f) is not inside the sign box "
                                "painted on the mask (x +-%.3f, y %.3f..%.3f)"
                                % (el["name"], x_hi, y_lo, y_hi,
                                   L.SIGN_HALF_X_M, box_lo, box_hi))
            # ...and it must sit PROUD of the mask, or the text draws inside it.
            bay = CAB_BAY_MINI if el["name"].endswith("_mini") else CAB_BAY
            if frm[2] > bay.z(cab_face_z(x_hi)) - 1e-9:
                problems.append("display element %r is not proud of the cab "
                                "mask" % el["name"])

    #     The SIDE destination sign is the same three-tool problem on a
    #     different surface: this file paints the housing on the bodyside,
    #     m7_layout owns the rectangle and gen_m7_doors carries the element.
    #     The element is ROTATED (yaw -90) to aim at the side, so the two
    #     offsets that matter are read out of it the way MTR reads them —
    #     along-car from `from[0]-origin[0]`, outboard from `-(from[2]-
    #     origin[2])` — and compared with the housing in DONOR metres, which
    #     is the frame the art is drawn in. gen_m7_doors' `verify_side_sign()`
    #     is the other half: it bakes all four placements.
    side_named = {n for _src, names in displays for n in names
                  if n.startswith("side_")}
    with open(DOORS_MODEL) as fh:
        for el in json.load(fh).get("elements", []):
            if el["name"] not in side_named:
                continue
            frm, to, org = el["from"], el["to"], el["origin"]
            za = DOOR_BAY.donor_z(frm[0] - org[0])
            zb = DOOR_BAY.donor_z(to[0] - org[0])
            z_lo, z_hi = sorted((za, zb))
            y_lo = FLOOR_Y + min(frm[1], to[1]) / L.Y_SCALE_ABOVE
            y_hi = FLOOR_Y + max(frm[1], to[1]) / L.Y_SCALE_ABOVE
            box_lo = L.SIDE_SIGN_Z_M - L.SIDE_SIGN_HALF_LEN / DOOR_BAY.units_per_m
            box_hi = L.SIDE_SIGN_Z_M + L.SIDE_SIGN_HALF_LEN / DOOR_BAY.units_per_m
            if (abs(z_lo - box_lo) > 1e-6 or abs(z_hi - box_hi) > 1e-6
                    or abs(y_lo - (FLOOR_Y + L.SIDE_SIGN_Y[0]
                                   / L.Y_SCALE_ABOVE)) > 1e-6
                    or abs(y_hi - (FLOOR_Y + L.SIDE_SIGN_Y[1]
                                   / L.Y_SCALE_ABOVE)) > 1e-6):
                problems.append("display element %r (donor z %.3f..%.3f, y "
                                "%.3f..%.3f) is not on the side sign housing "
                                "painted at z %.3f..%.3f"
                                % (el["name"], z_lo, z_hi, y_lo, y_hi,
                                   box_lo, box_hi))
            # The housing, bezel included, must stay on the flanking panel:
            # the elevation is cropped per bay, so a housing that ran past the
            # bay seam would be cut in half exactly as a straddling window is.
            bez = L.SIDE_SIGN_MARGIN_M
            if not (L.DOOR_APERTURE_A <= z_lo - bez and z_hi + bez <= DOOR_BAY.za):
                problems.append("the side sign housing (donor z %.3f..%.3f + "
                                "%.0f mm of bezel) runs off the door bay's "
                                "flanking panel (%.3f..%.3f)"
                                % (z_lo, z_hi, bez * 1000,
                                   L.DOOR_APERTURE_A, DOOR_BAY.za))
            # ...and the plate must stand proud of the skin at BOTH its ends —
            # the side leans, so the bottom edge is the widest point it clears.
            plate = (org[0] - frm[2]) / L.X_SCALE
            for y_m in (y_lo, y_hi):
                if plate <= interp_profile(SIDE_PROFILE, y_m)[0]:
                    problems.append("display element %r is buried in the "
                                    "bodyside at donor y %.3f (%.4f vs %.4f m)"
                                    % (el["name"], y_m, plate,
                                       interp_profile(SIDE_PROFILE, y_m)[0]))
            notes.append("side sign housing donor z %.3f..%.3f, y %.3f..%.3f, "
                         "%.0f mm proud of the skin, %.0f mm of panel left "
                         "each side"
                         % (z_lo, z_hi, y_lo, y_hi,
                            (plate - interp_profile(SIDE_PROFILE, y_lo)[0])
                            * 1000,
                            min(z_lo - bez - L.DOOR_APERTURE_A,
                                DOOR_BAY.za - z_hi - bez) * 1000))

    #     The sign now sits in the band ABOVE the windshields, so the thing it
    #     has to clear is the windscreen gaskets, not the cab door's. Derived
    #     from the donor's OWN glazing boxes (plus the surround `A.pane` draws
    #     outside them) rather than from the constants in m7_layout, so the
    #     guard survives a re-slice of the donor.
    sign_x, (sign_lo, sign_hi) = L.SIGN_HALF_X_M, L.SIGN_BOX_Y_M
    for (u0, u1, v0, v1) in tex["frente"].glazing:
        xa, xb = sorted((mask_x_of_u(u0), mask_x_of_u(u1)))
        ya, yb = sorted((mask_y_of_v(v0), mask_y_of_v(v1)))
        xa, xb = xa - 3 * MASK_PX_X, xb + 3 * MASK_PX_X
        ya, yb = ya - 3 * MASK_PX_Y, yb + 3 * MASK_PX_Y
        if (xa < sign_x and -sign_x < xb) and (ya < sign_hi and sign_lo < yb):
            problems.append("the destination sign box (x +-%.3f, y %.3f..%.3f) "
                            "overlaps a mask pane at x %.3f..%.3f y %.3f..%.3f "
                            "— it would bite a notch out of that gasket"
                            % (sign_x, sign_lo, sign_hi, xa, xb, ya, yb))
    notes.append("sign box clears every mask pane; %.0f mm of x margin to the "
                 "windscreen gaskets"
                 % ((L.SIGN_WINDSCREEN_INNER_X_M - 3 * MASK_PX_X - sign_x)
                    * 1000))

    # 5e. NO BAY MAY CARRY HALF A WINDOW. The elevation is drawn once and
    #     cropped per bay, so a pane straddling a boundary used to be cut in
    #     two: the half inside the crop was still an alpha-156 mark, so
    #     `open_glazing` punched it through, while `saloon_glass` refused to
    #     glaze it — an unglazed slot beside every door. `panes_in()` is the
    #     fix; this is the guard that stops it coming back if the layout,
    #     the donor slices or the pane art ever move.
    #     Checked on the PIXELS THAT SHIP, not on the geometry: an aperture is
    #     an alpha-0 hole, so "a hole touching the slice's first or last
    #     column" is exactly "a window the crop cut", and it stays true however
    #     the art is drawn.
    for key, t in sorted(tex.items()):
        if not key.startswith("lat_") or t.material not in mats:
            continue
        w = len(t.rows[0])
        for col, edge in ((0, "left"), (w - 1, "right")):
            cut = sum(1 for row in t.rows if row[col][3] == 0)
            if cut:
                problems.append(
                    "%s: %d transparent pixel(s) on its %s edge — a window is "
                    "being cut by a bay boundary" % (t.name, cut, edge))

    # 5f. Where an OPEN door leaf ends up, against the windows, in world units.
    #     Reported, not failed: the leaf lives in the cavity BETWEEN skin and
    #     lining, so it occludes a window whether or not the lining has an
    #     aperture there — the see-through windows neither cause it nor can fix
    #     it. Shrinking the travel would leave the doorway partly blocked and
    #     narrowing the window would move it off its donor position, so it is
    #     left alone. Watch the number: if it grows, something moved.
    leaf_lo, leaf_hi = sorted((DOOR_BAY.z(LEAF_U0_Z), DOOR_BAY.z(LEAF_U1_Z)))
    for door_z in L.NORMAL.door.centres:
        travel = math.copysign(L.DOOR_MULTIPLIER - 0.5, door_z)   # R179 curve
        open_lo = door_z + leaf_lo + travel
        open_hi = door_z + leaf_hi + travel
        for bay, end_z, flipped in ((GANGWAY_BAY, L.NORMAL.end.centres[0], False),
                                    (GANGWAY_BAY, L.NORMAL.end.centres[1], True),
                                    (CAB_BAY, L.NORMAL.end.centres[0], False),
                                    (CAB_BAY, L.NORMAL.end.centres[1], True)):
            for i, (u0, u1, _v0, _v1) in enumerate(side_panes()):
                if i not in panes_in(bay):
                    continue
                local = [bay.z(z_of_u(u0)), bay.z(z_of_u(u1))]
                world = sorted(end_z + (-v if flipped else v) for v in local)
                over = min(world[1], open_hi) - max(world[0], open_lo)
                if over > 1e-6:
                    notes.append(
                        "an OPEN leaf covers %.1f of the %.1f units of %s "
                        "pane %d at z %+.0f (it is in the wall cavity, in "
                        "front of the lining — not a lining-aperture defect)"
                        % (over, world[1] - world[0], bay.name, i, world[0]))

    # 6. positions resolve, nothing is placed twice, the car is the right length
    for label, (definition, properties) in sorted(VARIANTS.items()):
        placements, dupes = load_placements(definition, properties)
        for d in dupes:
            problems.append("%s: %r placed twice at z=%s flipped=%s"
                            % (label, d[0], d[2], d[3]))
        if not placements:
            problems.append("%s: no placements at all" % label)
            continue
        built = assemble(model, placements)
        zs = [v[2] for g in built.groups.values() for _m, vs in g.faces for v in vs]
        want = MINI_UNITS if "mini" in label else CAR_UNITS
        notes.append("%-13s %3d placements, z %+7.1f..%+7.1f (car is %d)"
                     % (label, len(placements), min(zs), max(zs), want))
        if max(abs(min(zs)), abs(max(zs))) > want / 2 + 2.0:
            problems.append("%s: geometry reaches %.1f, past the %d-unit car end"
                            % (label, max(abs(min(zs)), abs(max(zs))), want))
    return problems, notes


# -------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true",
                    help="cross-check the written model against the properties")
    ap.add_argument("--assemble", metavar="DIR",
                    help="bake every bay at its definition position into "
                         "whole-car preview OBJs in DIR, for tools/render_obj.py")
    args = ap.parse_args()

    model, tex = build_model()
    mats = used_materials(model, tex)
    obj_path = os.path.join(MODEL_DIR, "m7.obj")

    if args.assemble:
        os.makedirs(args.assemble, exist_ok=True)
        # The textures go in beside the preview and the map_Kd lines are bare
        # filenames: render_obj.py resolves real paths rather than the
        # namespaced identifiers the shipped model uses, and an .mtl path
        # cannot contain a space anyway (this project's own directory does).
        write_textures(tex, set(mats), args.assemble)
        for label, (definition, properties) in sorted(VARIANTS.items()):
            placements, _ = load_placements(definition, properties)
            built = assemble(model, placements)
            path = os.path.join(args.assemble, "m7_%s.obj" % label)
            nv, nf = write_obj(built, path, "m7_%s.mtl" % label, mats,
                               lambda t: t.name + ".png")
            print("%-28s %5d verts %5d faces" % (os.path.basename(path), nv, nf))
        return 0

    if not args.check:
        nv, nf = write_obj(model, obj_path, "m7.mtl", mats, lambda t: t.map_kd)
        written = write_textures(tex, set(mats))
        # Orphaned textures. `puerta`/`lining` went when the door leaves left
        # this model (their skins are baked into doors_box.png by
        # gen_m7_doors.py); `plough` went with donor builder 46 on 2026-07-30.
        # Named rather than glob-pruned — that directory also holds textures
        # this file does not own.
        for stale in ("puerta.png", "lining.png", "plough.png"):
            path = os.path.join(TEX_DIR, stale)
            if os.path.exists(path):
                os.remove(path)
                print("removed orphaned texture %s" % stale)
        print("m7.obj: %d groups, %d verts, %d faces" % (len(model.order), nv, nf))
        print("m7.mtl: %d materials" % len(mats))
        print("textures: %d -> %s" % (len(written), os.path.relpath(TEX_DIR, ROOT)))

    if not os.path.exists(obj_path):
        print("nothing to check: %s does not exist" % obj_path)
        return 1
    problems, notes = check(model, tex, obj_path)
    for note in notes:
        print("  . %s" % note)
    for problem in problems:
        print("  ! %s" % problem)
    print("%d problem(s)" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
