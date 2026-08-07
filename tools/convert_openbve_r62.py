#!/usr/bin/env python3
"""Converts the openBVE NYCT R62 donor into MTR's .obj vehicle model.

    python3 tools/convert_openbve_r62.py            # write model + textures
    python3 tools/convert_openbve_r62.py --check    # cross-check everything
    python3 tools/convert_openbve_r62.py --assemble DIR   # whole-car previews

WHAT THIS WRITES
----------------
    assets/station_announcer/models/vehicle/r62.obj      one `g` group per part
    assets/station_announcer/models/vehicle/r62.mtl      one material per texture
    assets/station_announcer/textures/vehicle/r62/*.png  the exterior, DRAWN

Pipeline order — each step reads the one before it:

    convert_openbve_r62.py     the shell + textures          (FIRST)
    gen_r62_doors.py           the leaves, floors, displays   (SECOND)
    gen_r62_assets.py          properties + definitions       (THIRD)
    gen_vehicle_index.py       the shared vehicle index       (FOURTH)
    convert_openbve_r62.py --check                            (LAST)

READ `M7_CONVERSION_NOTES.md` FIRST. It holds the empirically-established MTR
OBJ-loader spec — units, axes, materials, the flipped-z composition, the R179
door curve, and the family of .obj defects that force doors, floors, doorways
and DISPLAY parts into a companion .bbmodel. None of it is re-derived here.
`R62_NOTES.md` holds what is specific to THIS car.

THE THREE COORDINATE FRAMES (identical to the M7's — that is the point)
------------------------------------------------------------------------
donor   openBVE metres. x across, y up from the RAIL, z along the car.
M       the "bbmodel frame": 1 unit = 1/16 block, y = 0 at the car FLOOR. This
        is what everything below is authored in, because it is the frame the
        JSON `positions` live in. Bay groups are centred on z = 0.
OBJ     what gets written: obj = (-Mx, +My, -Mz) / 16, one OBJ unit = one BLOCK.

⭐ THE M FRAME IS SHARED WITH THE .bbmodel, AND THAT IS LOAD-BEARING HERE.
`to_obj_vertex` exists precisely so that an M coordinate written into this .obj
and the same M coordinate written into `r62_doors.bbmodel` land in the same
place in the world. The M7 could afford not to care — its apertures were 4-fold
symmetric, so a sign error was invisible — but the R62's side rollsign and front
roundel each exist on ONE side only, so the .obj housing and the .bbmodel
display that has to sit on it agree or the sign floats on bare stainless.
`--check` bakes both through their (different!) placement transforms and
measures the result rather than trusting this paragraph.

    .obj       unflipped(Z): z -> z + Z, x kept      flipped(Z): z -> -z - Z, x -> -x
    .bbmodel   unflipped(Z): z -> z + Z, x kept      flipped(Z): z -> -z + Z, x -> -x

⭐ ROTATIONAL SYMMETRY, NOT MIRROR SYMMETRY
--------------------------------------------
The R62 is 180-degree rotationally symmetric about its centre. The rollsign sits
in the +z panel of the +x side and the -z panel of the -x side; the half-cab is
diagonally opposite end to end. So EVERY BAY GROUP HERE MODELS BOTH SIDES, and a
`positionsFlipped` entry supplies the other END of the car rather than the other
side. The M7's "model x > 0 and mirror" rule does not apply and must not be
reintroduced. Full reasoning in `tools/r62_layout.py`.

THE ART IS DRAWN, NOT PHOTOGRAPHED
-----------------------------------
Every pixel that ships comes out of `tools/r62_art.py`'s palette. The donor
supplies geometry, positions and uv maps; not one donor pixel reaches the model.
The one rule that makes the bay system work — the elevation may only shade
VERTICALLY — is enforced by `r62_art.stainless_field` drawing full-width bands
and is guarded by `--check` comparing columns of the shipped crops.

WINDING
-------
openBVE and OBJ agree on CCW-from-outside, and MTR culls backfaces
unconditionally. Every bay in `r62_layout` maps its donor slice with a POSITIVE
determinant, so unlike the M7 nothing here ever needs its winding reversed —
but a faceted bullnose is exactly the kind of geometry where a hand-ordered quad
goes in backwards unnoticed, so `quad()` takes the outward direction it is
supposed to face and reverses itself if it does not. `--check` then sums face
normals per group as an independent second opinion.
"""

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bve_csv
import coupler as CPL
import pngtool
import pixel_kit as K
import r62_art as A
import r62_layout as L

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")
MODEL_DIR = os.path.join(OUR_NS, "models/vehicle")
TEX_DIR = os.path.join(OUR_NS, "textures/vehicle/r62")
TEX_ID = "station_announcer:textures/vehicle/r62"
OBJ_NAME = "r62.obj"
MTL_NAME = "r62.mtl"

DONOR = ("/Users/thomasdemuth/openBVE/Train/IRT/62/Kawasaki R62/Livonia Set/"
         "1983 - 1991  R62 [Livonia Set]")
DONOR_EXTERIOR = os.path.join(DONOR, "Cars/R62 Exterior/Exterior.b3d")

# Where the donor's nose stops being a ruled surface and folds back to meet the
# bodyside. Inboard of this the facets reproduce it to a couple of millimetres;
# outboard, `L.NOSE_PLAN` ramps a 65 mm step that is 3 cm wide. See
# `check_against_donor`, which is where this is actually used and explained.
NOSE_FOLD_X = 1.2400

sx = L.sx
sy = L.sy

# --------------------------------------------------------------- texture space
#
# Densities are chosen for MTR's own texel band (~40-48 px per block, R179/R211
# territory), not for the donor's 3277 x 512 photograph:
#
#   side   768 x 112  over 14.31 m x 2.17 m  ->  53.7 / 51.7 px per metre
#   roof   768 x 128  over 14.31 m x 2.92 m  ->  53.7 / 43.8 px per metre
#   front  192 x 176  over  2.62 m x 2.51 m  ->  73.2 / 70.0 px per metre
#
# Square texels within a few percent everywhere, which is what keeps a drawn
# rectangle from reading as a stretched one. The front is denser on purpose:
# the nose carries the smallest details on the car (lamp clusters, the roundel
# bezel) and is the surface a player walks up to.
SIDE_TEX_W = 768
SIDE_TEX_H = 112
ROOF_TEX_W = 768
ROOF_TEX_H = 128
MASK_TEX_W = 192
MASK_TEX_H = 176
SIGN_TEX_W = 160
SIGN_TEX_H = 44
VENT_TEX = 48
# The corner strip shares the mask's HEIGHT (so its bands line up with the
# mask's at the seam) and is narrow, because nothing on it varies along u
# except three rib lines.
CORNER_TEX_W = 24
# The lit lens. 32 px over a 0.242 m lamp is 132 px/m — denser than the mask,
# because a disc drawn at the mask's own density would have visible stair steps
# on the one part of the car a player walks up to and looks at.
LAMP_TEX = 32

# The drawn elevation is a quarter the donor's width, so the smallest pane on it
# (an end window, 22 x 39 px) is still 40x this.
GLAZING_MIN_PX = 64


def side_px(z_m, side=+1):
    """donor z -> column in the side elevation, for one side of the car."""
    return L.u_of_z(z_m, side) * SIDE_TEX_W


def side_row(y_m):
    """donor y -> row in the side elevation.

    Clamped, because the livery's bands are written as open-ended ranges: the
    skirt runs "to the bottom" and the header "to the roof", and neither should
    have to know where the elevation happens to stop.
    """
    return min(1.0, max(0.0, L.side_v(y_m))) * SIDE_TEX_H


def mask_px(x_m, y_m):
    return (L.mask_u(x_m) * MASK_TEX_W, L.mask_v(y_m) * MASK_TEX_H)


# ==================================================================== mesh model

class Group:
    """One MTR part: a named bag of faces, each face carrying its material."""

    def __init__(self, name):
        self.name = name
        self.faces = []          # (material, [(x, y, z, u, v), ...])

    def add(self, material, verts):
        self.faces.append((material, list(verts)))

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


def face_normal(vs):
    """Newell's method — correct for the slightly non-planar fillet quads."""
    nx = ny = nz = 0.0
    n = len(vs)
    for i in range(n):
        x0, y0, z0 = vs[i][0], vs[i][1], vs[i][2]
        x1, y1, z1 = vs[(i + 1) % n][0], vs[(i + 1) % n][1], vs[(i + 1) % n][2]
        nx += (y0 - y1) * (z0 + z1)
        ny += (z0 - z1) * (x0 + x1)
        nz += (x0 - x1) * (y0 + y1)
    return (nx, ny, nz)


def quad(group, material, verts, outward):
    """Emit one quad, guaranteed to face `outward`.

    ⭐ THE WINDING IS COMPUTED, NOT ORDERED BY HAND. MTR culls backfaces
    unconditionally, so a reversed quad is simply not there — and this model's
    nose is a dozen facets whose "obvious" vertex order changes sign halfway
    round the bullnose. Handing each one the direction it is meant to face and
    letting the Newell normal settle the order removes an entire class of bug
    that costs a render cycle to notice. `--check` still sums normals per group
    as an independent second opinion, so this cannot quietly agree with itself.

    Degenerate quads (a facet that has collapsed to a line) are dropped rather
    than emitted with a zero normal.
    """
    vs = list(verts)
    n = face_normal(vs)
    dot = sum(a * b for a, b in zip(n, outward))
    if abs(dot) < 1e-12 and max(abs(c) for c in n) < 1e-9:
        return
    group.add(material, vs[::-1] if dot < 0 else vs)


def quad_x(group, material, x_m, z0, z1, y_top, y_bot, uv, outward_x):
    """A quad in a constant-x plane. `uv` is [(u,v)] for the four corners in
    the order (z0,y_top), (z1,y_top), (z1,y_bot), (z0,y_bot)."""
    x = sx(x_m)
    pts = ((z0, y_top), (z1, y_top), (z1, y_bot), (z0, y_bot))
    quad(group, material,
         [(x, sy(y), z, uv[i][0], uv[i][1]) for i, (z, y) in enumerate(pts)],
         (outward_x, 0.0, 0.0))


def quad_z(group, material, z, corners_xy, uv, outward_z):
    """A quad in a constant-z plane; `corners_xy` in donor metres."""
    quad(group, material,
         [(sx(x), sy(y), z, uv[i][0], uv[i][1])
          for i, (x, y) in enumerate(corners_xy)], (0.0, 0.0, outward_z))


def quad_y(group, material, y_m, corners_xz, uv, outward_y):
    """A quad in a constant-y plane; `corners_xz` is (donor x, M z)."""
    y = sy(y_m)
    quad(group, material,
         [(sx(x), y, z, uv[i][0], uv[i][1])
          for i, (x, z) in enumerate(corners_xz)], (0.0, outward_y, 0.0))


def box(group, material, x0, x1, y0, y1, z0, z1, uv=(0.0, 0.0, 1.0, 1.0)):
    """An axis-aligned box in donor metres (x, y) and M units (z)."""
    u0, v0, u1, v1 = uv
    c = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
    xa, xb = min(x0, x1), max(x0, x1)
    ya, yb = min(y0, y1), max(y0, y1)
    za, zb = min(z0, z1), max(z0, z1)
    quad_x(group, material, xb, za, zb, yb, ya, c, +1.0)
    quad_x(group, material, xa, za, zb, yb, ya, c, -1.0)
    quad_y(group, material, yb, [(xa, za), (xa, zb), (xb, zb), (xb, za)], c, +1.0)
    quad_y(group, material, ya, [(xa, za), (xa, zb), (xb, zb), (xb, za)], c, -1.0)
    quad_z(group, material, zb, [(xa, ya), (xb, ya), (xb, yb), (xa, yb)], c, +1.0)
    quad_z(group, material, za, [(xa, ya), (xb, ya), (xb, yb), (xa, yb)], c, -1.0)


# ================================================================= texture set

class Tex:
    """A texture that will be written out, plus its u-remap."""

    def __init__(self, name, rows, src_w=None, x0=0, x1=None):
        self.name = name
        self.rows = rows
        self.src_w = src_w
        self.x0 = x0
        self.x1 = x1 if x1 is not None else (src_w or len(rows[0]))
        self.flag = ""           # shader flag, e.g. "#interior_translucent"
        self.glazing = []        # uv boxes of the marked panes, full-texture

    def u(self, u_full):
        if self.src_w is None:
            return u_full
        return (u_full * self.src_w - self.x0) / float(self.x1 - self.x0)

    @property
    def material(self):
        # The flag rides IN the material name, and when an .mtl exists MTR looks
        # the FULL name up, so `newmtl` has to carry it verbatim too.
        return "r62_" + self.name + self.flag

    @property
    def map_kd(self):
        return "%s/%s.png" % (TEX_ID, self.name)


# ====================================================================
# DRAWN ART
# ====================================================================
#
# The donor is consulted for POSITIONS only — where its apertures are, what uv
# map its end mask uses, how deep its reveal is — never for pixels.
#
# ⭐ ONE ELEVATION SERVES BOTH SIDES OF THE WHOLE CAR. That is not a saving, it
# is the rotational symmetry made concrete: the -x side reads the same image
# mirrored (`L.u_of_z(z, -1) == L.u_of_z(-z, +1)`), so the image column that
# carries the rollsign on the +x side at +z is the same column that carries it
# on the -x side at -z. A bay simply takes TWO crops, one per side.

# The apertures, in donor z on the +x SIDE READING of the elevation. Everything
# on the elevation is placed from this table, and `panes_in()` filters it per
# crop so no bay can ever carry half a window.
ELEVATION_PANES = (
    # (name, donor z lo, donor z hi, donor y lo, donor y hi, corner radius m)
    ("end_a", 6.617, 7.032, L.END_WINDOW_Y_M[0], L.END_WINDOW_Y_M[1], 0.09),
    ("rollsign", L.ROLLSIGN_WINDOW_Z_M[0], L.ROLLSIGN_WINDOW_Z_M[1],
     L.WINDOW_Y_M[0], L.rollsign_glass_top_m(), L.WINDOW_CORNER_R_M),
    ("saloon", -L.SALOON_WINDOW_Z_M[1], -L.SALOON_WINDOW_Z_M[0],
     L.WINDOW_Y_M[0], L.WINDOW_Y_M[1], L.WINDOW_CORNER_R_M),
    ("end_b", -7.032, -6.617, L.END_WINDOW_Y_M[0], L.END_WINDOW_Y_M[1], 0.09),
)

PANE_FRAME_PX = 3            # keep in step with `A.pane`'s `frame=` default


def pane_span_m(pane):
    """The donor z a pane occupies INCLUDING its drawn frame."""
    margin = (PANE_FRAME_PX + 1) / float(SIDE_TEX_W) * abs(L.Z_PER_U)
    return (pane[1] - margin, pane[2] + margin)


def panes_in(bay, side):
    """Names of the panes that fit WHOLLY inside `bay` on `side`.

    ⭐ A BAY MAY NEVER CARRY HALF A WINDOW. The elevation is drawn once and
    cropped per bay, so a pane straddling a boundary gets cut in two — and the
    half inside the crop is still an alpha-marked pane, so `open_glazing`
    punches it through as a real aperture while the glass quad (which requires
    containment at BOTH ends) refuses to build. The result is an unglazed slot
    with a doubled-looking frame beside it. The M7 shipped that bug once; this
    is the same mechanical fix, applied before it could happen here.

    Panes are stored in the +x reading, so the -x side's crop is tested against
    the MIRRORED bay — which is exactly the rotational symmetry again.
    """
    lo_z, hi_z = (bay.za, bay.zb) if side > 0 else (-bay.zb, -bay.za)
    out = set()
    for pane in ELEVATION_PANES:
        a, b = pane_span_m(pane)
        if lo_z - 1e-6 <= a and b <= hi_z + 1e-6:
            out.add(pane[0])
    return out


def draw_side_elevation(keep=None):
    """The whole car side, drawn: 768 x 112, glazing MARKED not cut.

    `keep` is a set of pane names; None draws them all. A bay passes what
    `panes_in()` gave it — see that function for why.
    """
    rows = K.canvas(SIDE_TEX_W, SIDE_TEX_H, A.STEEL)
    A.stainless_field(rows, side_row)
    A.brushed(rows, side_row(A.CANT_RAIL_Y), side_row(A.BELT_UPPER_Y[1]))
    A.brushed(rows, side_row(A.BELT_THIRD_Y[0]), side_row(A.SILL_Y))

    # Panel joints. They land on the BAY BOUNDARIES, which is where a real car's
    # sheets are butted, and are drawn on both sides of the elevation so a
    # crop's own edge always ends on one.
    for z in (L.DONOR_BOUNDS[1], L.DONOR_BOUNDS[2], L.DONOR_BOUNDS[3],
              -L.DONOR_BOUNDS[1], -L.DONOR_BOUNDS[2], -L.DONOR_BOUNDS[3]):
        x = side_px(z)
        K.vline(rows, x, side_row(A.ROOF_EAVES_Y), side_row(A.SILL_Y), A.SEAM)

    # The windows, at the donor's own boxes. `A.pane` draws its frame OUTSIDE
    # the box it is given, so the glass — and therefore the hole and the glass
    # quad derived from it — lands exactly where the prototype's does.
    for (name, za, zb, y_lo, y_hi, radius) in ELEVATION_PANES:
        if keep is not None and name not in keep:
            continue
        x0, x1 = sorted((side_px(za), side_px(zb)))
        y0, y1 = side_row(y_hi), side_row(y_lo)
        r = radius / abs(L.Z_PER_U) * SIDE_TEX_W
        # The hopper vent's glazing bar, a fifth of the way down the big panes.
        muntin = None if name.startswith("end") else int(y0 + (y1 - y0) * 0.20)
        A.pane(rows, x0, y0, x1, y1, radius=int(round(r)), frame=PANE_FRAME_PX,
               muntin=muntin)

    # ⭐ THE SIDE ROLLSIGN'S HOUSING FOOTPRINT. The housing itself is a real
    # proud box wearing its own texture (`draw_sign_face`), so what is drawn
    # here is only the shadow it casts on the skin and the bolt line under it —
    # the box hides everything else. Drawing the box's own face here instead
    # would have put it 20 mm behind where it belongs and left the display text
    # floating in front of nothing.
    hx0, hx1 = sorted((side_px(L.SIGN_BOX_Z_M[0]), side_px(L.SIGN_BOX_Z_M[1])))
    hy0, hy1 = side_row(L.SIGN_BOX_Y_M[1]), side_row(L.SIGN_BOX_Y_M[0])
    # ⭐ THE SHADOW MAY NOT REACH BELOW THE BOX. The window's polished frame is
    # drawn OUTSIDE its glass and therefore runs up under the housing's bottom
    # edge, which is exactly how the prototype looks — the sign is bolted onto
    # the top of the window. A shadow two rows deep painted over that frame and
    # opened a band of bare stainless between the sign and the glass that has no
    # business being there.
    K.rect(rows, hx0 - 1, hy0 - 1, hx1 + 1, hy1, A.STEEL_DEEP)

    # The three door openings get a header trim and a sill band. The aperture
    # itself is cut GEOMETRICALLY (the door bay is nothing but aperture — see
    # r62_layout), so anything drawn inside it would only ever show through the
    # reveal as a floating rectangle.
    for centre in (0.0, 4.8065, -4.8065):
        x0, x1 = sorted((side_px(centre - L.DONOR_APERTURE_M / 2.0),
                         side_px(centre + L.DONOR_APERTURE_M / 2.0)))
        K.rect(rows, x0 - 2, side_row(L.DOOR_HEAD_Y) - 1, x1 + 2,
               side_row(L.DOOR_HEAD_Y) + 1, A.SEAM)
        # the door-open indicator strip above each opening
        K.rect(rows, x0 + 4, side_row(3.098), x1 - 4, side_row(3.064),
               A.BLACK_SOFT)
        K.rect(rows, x0 + 4, side_row(3.098), x1 - 4, side_row(3.098) + 1,
               A.POLISH_LO)
        # the threshold band under the sill
        K.rect(rows, x0 - 2, side_row(A.SILL_Y), x1 + 2,
               side_row(A.SILL_Y) + 2, A.STEEL_DEEP)
    return rows


def draw_roof_plan():
    """The roof, in plan: 768 x 128, v across the car, crown at v = 0.5.

    Roof ribs run ALONG the car, so in this plan they are HORIZONTAL bands —
    constant in u, and therefore perfectly tiling. That is the same property
    the bodyside's tone bands have and it is not a coincidence: it is why the
    R62's roof could be drawn at all without a per-bay texture.
    """
    rows = K.canvas(ROOF_TEX_W, ROOF_TEX_H, A.ROOF_STEEL)
    half = ROOF_TEX_H // 2
    for i in range(half):
        y = half + i
        t = i % 5
        colour = (A.ROOF_RIB_HI if t == 0 else
                  A.ROOF_RIB_LO if t == 3 else A.ROOF_STEEL)
        K.rect(rows, 0, y, ROOF_TEX_W, y + 1, colour)
    K.rect(rows, 0, half - 1, ROOF_TEX_W, half + 2, A.ROOF_RIB_HI)   # crown cap
    K.rect(rows, 0, ROOF_TEX_H - 3, ROOF_TEX_W, ROOF_TEX_H, A.SEAM)  # the eaves
    for i in range(half):
        rows[half - 1 - i] = list(rows[half + i])
    return rows


def draw_vent():
    """A louvred roof vent: a bright frame around a dark grille.

    Its own texture rather than a corner of the roof plan, because the plan is
    cropped per bay by u and a vent is placed once per corner — it wants its
    whole u range, and it wants to be denser than the roof it sits on.
    """
    rows = K.canvas(VENT_TEX, VENT_TEX, A.VENT_FRAME)
    K.rect(rows, 2, 2, VENT_TEX - 2, VENT_TEX - 2, A.VENT_DARK)
    for y in range(5, VENT_TEX - 5, 4):
        K.rect(rows, 5, y, VENT_TEX - 5, y + 2, A.VENT_LOUVRE)
        K.rect(rows, 5, y, VENT_TEX - 5, y + 1, A.VENT_FRAME)
    K.rect(rows, 0, 0, VENT_TEX, 2, A.POLISH_LO)
    for x in (3, VENT_TEX - 5):
        for y in (3, VENT_TEX - 5):
            K.rect(rows, x, y, x + 2, y + 2, A.RIVET)
    return rows


def draw_sign_face():
    """The side rollsign housing's outboard face, with its three dark wells.

    The wells are placed from `r62_layout`'s SNAPPED display rectangles, not
    from the donor's raw boxes: MTR rounds a display element's size to whole
    model pixels and uses that integer as the text canvas, so the well a player
    sees and the canvas MTR lays text into have to be the same rectangle or the
    text sits off-centre in its own window.
    """
    z0, z1 = L.PANEL_BAY.z(L.SIGN_BOX_Z_M[0]), L.PANEL_BAY.z(L.SIGN_BOX_Z_M[1])
    y0, y1 = sy(L.SIGN_BOX_Y_M[0]), sy(L.SIGN_BOX_Y_M[1])

    def to_px(fz0, fz1, fy0, fy1):
        return (round((fz0 - z0) / (z1 - z0) * SIGN_TEX_W),
                round((y1 - fy1) / (y1 - y0) * SIGN_TEX_H),
                round((fz1 - z0) / (z1 - z0) * SIGN_TEX_W),
                round((y1 - fy0) / (y1 - y0) * SIGN_TEX_H))

    fields = [to_px(*f) for f in (L.sign_bullet(), L.sign_upper(),
                                  L.sign_lower())]
    return A.sign_housing(SIGN_TEX_W, SIGN_TEX_H, fields)


def draw_end_mask(blind=False):
    """The car end: bare stainless, storm door, and (unless blind) the cab.

    Drawn in DONOR METRES through `L.mask_u`/`L.mask_v`, which is the uv map
    fitted off the donor's own front builders — so the art lands on the faceted
    nose without a single pixel coordinate being typed.

    ⭐ THE R62's FRONT IS NOT BLACK. Every NYCT car from the R142 on wears a
    black mask and it is tempting to give this one the same; the R62 in the
    mid-1990s is bare brushed stainless with a black bezel around the route
    sign and nothing else. The only dark things on this end are the sign box,
    the lamp wells and the shadow under the anticlimber.

    `blind` drops everything that makes an end a CAB — the windshield, the
    roundel box, the lamp clusters — and puts plain panelled stainless in their
    place. That is the whole difference between the two vehicles, and it is a
    texture swap rather than a second nose, because the shell is identical.
    """
    rows = K.canvas(MASK_TEX_W, MASK_TEX_H, A.STEEL)

    def rect_m(x_a, y_a, x_b, y_b, colour):
        (pa, va), (pb, vb) = mask_px(x_a, y_a), mask_px(x_b, y_b)
        K.rect(rows, min(pa, pb), min(va, vb), max(pa, pb), max(va, vb), colour)

    # The roof fillet band, above the eaves, in roof tones so it continues the
    # dome rather than the bodyside.
    rect_m(-1.40, L.MASK_Y_TOP, 1.40, L.NOSE_EAVE_Y, A.ROOF_STEEL)
    K.rect(rows, 0, L.mask_v(L.NOSE_EAVE_Y) * MASK_TEX_H - 1, MASK_TEX_W,
           L.mask_v(L.NOSE_EAVE_Y) * MASK_TEX_H + 1, A.SEAM)

    # The corner posts, where the bodyside wraps round onto the end. They are
    # what stops the nose reading as a flat card.
    for sign in (-1.0, 1.0):
        rect_m(sign * 1.20, L.NOSE_EAVE_Y, sign * 1.40, A.SILL_Y,
               A.STEEL_MID)
        rect_m(sign * 1.24, L.NOSE_EAVE_Y, sign * 1.26, A.SILL_Y,
               A.POLISH_LO)

    # The storm-door recess. Its returns are real geometry; what is drawn here
    # is the door leaf itself, which sits 248 mm back, plus the shadow the
    # recess casts on its own reveal.
    rect_m(-L.STORM_DOOR_HALF_X, L.STORM_DOOR_TOP_Y, L.STORM_DOOR_HALF_X,
           A.SILL_Y, A.STEEL_MID)
    for sign in (-1.0, 1.0):
        rect_m(sign * L.STORM_DOOR_HALF_X, L.STORM_DOOR_TOP_Y,
               sign * (L.STORM_DOOR_HALF_X - 0.030), A.SILL_Y, A.STEEL_DEEP)
    rect_m(-L.STORM_DOOR_HALF_X, L.STORM_DOOR_TOP_Y, L.STORM_DOOR_HALF_X,
           L.STORM_DOOR_TOP_Y - 0.030, A.STEEL_DEEP)
    # its window, marked as glazing so the same mechanism cuts it
    (wx0, wy0) = mask_px(-L.STORM_DOOR_WINDOW_X, L.STORM_DOOR_WINDOW_Y[1])
    (wx1, wy1) = mask_px(L.STORM_DOOR_WINDOW_X, L.STORM_DOOR_WINDOW_Y[0])
    A.pane(rows, min(wx0, wx1), wy0, max(wx0, wx1), wy1, radius=5, frame=2)
    # ⭐ THE SAFETY CHAINS ARE NO LONGER PAINTED HERE. Two straight dark lines
    # across the leaf read as panel joints, which is exactly what they looked
    # like; they are real bars now, slung in front of the recess by
    # `safety_chains()`, and a chain drawn on the leaf as well would show
    # through between them. The door's own grab handle stays.
    (hx, hy) = mask_px(0.200, 2.10)
    K.rect(rows, hx, hy, hx + 2, hy + 10, A.POLISH_LO)

    # The end's panel joints and the black band under the sill.
    rect_m(-1.40, A.SILL_Y, 1.40, A.BLACK_BAND_Y[0], A.BLACK_BAND)
    rect_m(-1.40, A.BLACK_BAND_Y[0], 1.40, L.MASK_Y_BOT, A.UNDER_LO)
    for y in (2.926, 2.116):
        rect_m(-1.40, y, 1.40, y - 0.012, A.SEAM)

    if blind:
        # A blind end is the same shell with the cab features left off: two
        # blank panels where the windshield and the route sign would be, framed
        # so they read as deliberate plating rather than as missing art.
        for (x_a, x_b) in ((L.WINDSCREEN_X_M[0], L.WINDSCREEN_X_M[1]),
                           (L.ROUNDEL_X_M[0], L.ROUNDEL_X_M[1])):
            rect_m(x_a, L.WINDSCREEN_Y_M[1], x_b, L.WINDSCREEN_Y_M[0],
                   A.STEEL_MID)
            rect_m(x_a, L.WINDSCREEN_Y_M[1], x_b,
                   L.WINDSCREEN_Y_M[1] - 0.012, A.POLISH_LO)
            rect_m(x_a, L.WINDSCREEN_Y_M[0] + 0.012, x_b,
                   L.WINDSCREEN_Y_M[0], A.SEAM)
        return rows

    # The half-cab's windshield, marked as glazing. A wiper is drawn over it at
    # full alpha, so `open_glazing` leaves it behind as a sliver of mask inside
    # the aperture — which, with the glass quad sitting proud, is where a wiper
    # belongs.
    (px0, py0) = mask_px(L.WINDSCREEN_X_M[0], L.WINDSCREEN_Y_M[1])
    (px1, py1) = mask_px(L.WINDSCREEN_X_M[1], L.WINDSCREEN_Y_M[0])
    wx0, wx1 = sorted((px0, px1))
    A.pane(rows, wx0, py0, wx1, py1, radius=6, frame=3)
    K.line(rows, wx0 + (wx1 - wx0) * 0.68, py0 + 3,
           wx0 + (wx1 - wx0) * 0.28, py1 - 4, A.BLACK_SOFT, width=2)
    K.line(rows, wx0 + (wx1 - wx0) * 0.28, py1 - 4,
           wx0 + (wx1 - wx0) * 0.40, py1 - 1, A.BLACK_SOFT, width=2)

    # The route-sign box, diagonally opposite the cab. Only the BACKING is
    # drawn: the bullet is an MTR ROUTE_COLOR_ROUNDED disc with a ROUTE_NUMBER
    # over it, carried by r62_doors.bbmodel, and DISPLAY parts cannot live on
    # an .obj at all (M7_CONVERSION_NOTES). The box below and the elements
    # there are placed from the same `r62_layout` rectangle.
    (rx0, ry0) = mask_px(L.ROUNDEL_X_M[0], L.ROUNDEL_Y_M[1])
    (rx1, ry1) = mask_px(L.ROUNDEL_X_M[1], L.ROUNDEL_Y_M[0])
    A.roundel_box(rows, min(rx0, rx1), ry0, max(rx0, rx1), ry1)

    # ⭐ THE LAMPS: ONE PER SIDE PER ROW, PAINTED UNLIT. Four per end. The
    # donor's builders 11/12 are one faceted disc each; reading their three
    # vertex columns as three centres is what put two spurious dark lenses
    # beside every lamp on the car's face. What is painted is the lamp OFF —
    # the bolted chrome bezel and a dead lens; the LIT lens is its own geometry
    # in `headlights` / `tail_lights`, drawn only when the lamp is burning.
    r = L.LAMP_RIM_R_M / L.MASK_X_SPAN * MASK_TEX_W
    for sign in (-1.0, 1.0):
        for row_y, red in ((L.LAMP_ROW_UPPER_Y, True),
                           (L.LAMP_ROW_LOWER_Y, False)):
            cy = (row_y[0] + row_y[1]) / 2.0
            cx, cyp = mask_px(sign * L.LAMP_CENTRE_X, cy)
            A.lamp_well(rows, cx, cyp, r, red=red)
    return rows


def draw_corner():
    """The end's corner strip: the side's tone ladder, ribbed, content-free.

    Its v map is the END MASK's, so its bands land on the mask's at the seam.
    See `r62_art.corner_strip` for why this texture exists at all — the short
    version is that the facets it wears are the ones `mask_u` cannot address.
    """
    return A.corner_strip(MASK_TEX_H,
                          lambda y: L.mask_v(y) * MASK_TEX_H,
                          width=CORNER_TEX_W)


def draw_apron():
    """The anticlimber beam: a dark ribbed plate.

    One texture for all six of the box's faces, which is why it is a plain band
    pattern rather than a plan: the top of this beam is the end platform a
    passenger walks across, its front is what another car's beam meets, and both
    have to read as the same rough steel from any angle.
    """
    rows = K.canvas(64, 32, A.UNDER)
    # Bands only, never a comb: this one texture wraps the beam's top, front and
    # ends, so anything that reads as a column on one face reads as a row on the
    # next. Horizontal is the only pattern that survives that.
    K.rect(rows, 0, 0, 64, 3, A.STEEL_DEEP)
    K.rect(rows, 0, 3, 64, 4, A.STEEL_MID)
    K.rect(rows, 0, 14, 64, 16, A.UNDER_LO)
    K.rect(rows, 0, 28, 64, 32, A.UNDER_LO)
    return rows


def build_textures():
    """Draw the exterior and slice it per bay per side.

    The side and the roof share ONE u map, so a single u range cuts a bay's
    wall and its roof together; crops are on whole pixels and `Tex.u()` carries
    the fractional remainder, so no geometry moves to suit a crop.
    """
    # Drawn once with EVERY pane, purely to measure. The glass quads built into
    # each bay come off THIS canonical draw, so they stay in one coordinate
    # space even though each bay's own slice omits some panes.
    all_glazing = K.glazing_rects(draw_side_elevation(), GLAZING_MIN_PX)
    roof_plan = draw_roof_plan()
    tex = {}
    crops = {}

    def side_slice(key, bay, side):
        """One (bay, side) wall crop, plus the bay's roof crop the first time.

        Two bays that happen to want the same columns AND the same pane set —
        which the door bay's two sides do, being symmetric — share one texture
        and cost nothing.
        """
        z_lo = bay.za if side > 0 else -bay.zb
        z_hi = bay.zb if side > 0 else -bay.za
        z_lo = max(z_lo, L.z_of_u(1.0))
        z_hi = min(z_hi, L.z_of_u(0.0))
        a, b = sorted((L.u_of_z(z_lo), L.u_of_z(z_hi)))
        x0 = max(0, int(math.floor(a * SIDE_TEX_W)))
        x1 = min(SIDE_TEX_W, int(math.ceil(b * SIDE_TEX_W)))
        keep = panes_in(bay, side)
        signature = (x0, x1, tuple(sorted(keep)))
        if signature in crops:
            tex[key] = tex[crops[signature]]
            return
        crops[signature] = key
        rows = K.open_glazing(draw_side_elevation(keep))
        t = Tex(key, pngtool.crop(rows, x0, 0, x1, len(rows)),
                SIDE_TEX_W, x0, x1)
        t.glazing = all_glazing
        tex[key] = t

    def roof_slice(key, bay):
        a, b = sorted((L.u_of_z(bay.za), L.u_of_z(min(bay.zb, L.z_of_u(0.0)))))
        x0 = max(0, int(math.floor(a * ROOF_TEX_W)))
        x1 = min(ROOF_TEX_W, int(math.ceil(b * ROOF_TEX_W)))
        signature = (x0, x1)
        if signature in crops:
            tex[key] = tex[crops[signature]]
            return
        crops[signature] = key
        tex[key] = Tex(key, pngtool.crop(roof_plan, x0, 0, x1, len(roof_plan)),
                       ROOF_TEX_W, x0, x1)

    for name, bay in (("panel", L.PANEL_BAY), ("door", L.DOOR_BAY),
                      ("end", L.END_BAY)):
        side_slice("side_%s_a" % name, bay, +1)
        side_slice("side_%s_b" % name, bay, -1)
        roof_slice("roof_%s" % name, bay)

    for blind in (False, True):
        key = "front_blind" if blind else "front"
        mask = draw_end_mask(blind)
        t = Tex(key, K.open_glazing(mask))
        t.glazing = K.glazing_rects(mask, GLAZING_MIN_PX)
        tex[key] = t

    # The coupler's cast steel. Drawn by tools/coupler.py so this car and the
    # M7 get byte-identical pixels — one coupler design, one plate.
    tex["coupler"] = Tex("coupler", CPL.draw_plate(K))

    tex["sign"] = Tex("sign", draw_sign_face())
    tex["apron"] = Tex("apron", draw_apron())
    tex["vent"] = Tex("vent", draw_vent())
    tex["corner"] = Tex("corner", draw_corner())
    tex["chain"] = Tex("chain", A.chain_bar(16, 8))
    # ⭐ CUTOUT_GLOWING, NOT TRANSLUCENT_GLOWING. A lit lens is a round disc on
    # a transparent field, and `#light` is the flag that makes alpha 0 a real
    # hole while still lighting the quad. `always_on_light` would alpha-BLEND it
    # and write no depth, which on a lamp bolted to a curved nose means the
    # bezel behind it bleeds through its own lens.
    for name, red in (("lamp_white", False), ("lamp_red", True)):
        tex[name] = Tex(name, A.lamp_lens(LAMP_TEX, red=red))
        tex[name].flag = "#light"

    # The glass. INTERIOR_TRANSLUCENT is the only stage that alpha-blends, and
    # a `#` flag in the material name beats the part's renderStage, so these
    # quads sit in an EXTERIOR group and still come out as real tinted glass.
    tex["glass"] = Tex("glass", K.solid(A.GLASS_RGBA))
    tex["glass"].flag = "#interior_translucent"

    # SetColor-style flat swatches, so the whole model stays on one code path
    # (every material has a map_Kd) instead of mixing in the MTL's Kd/d path.
    for name, rgb in (("reveal", A.STEEL_DEEP),
                      ("threshold", A.UNDER),
                      ("underframe", A.UNDER),
                      ("underframe_lo", A.UNDER_LO),
                      ("black", A.BLACK_BAND),
                      ("case", A.SIGN_CASE),
                      ("default", A.STEEL)):
        tex[name] = Tex(name, K.solid(rgb))
    return tex


# ====================================================================
# GEOMETRY
# ====================================================================

def u_at_for(bay, tex, side):
    def u_at(mz):
        return tex.u(L.u_of_z(bay.donor_z(mz), side))
    return u_at


def side_wall(group, tex, bay, side, z_a, z_b, y_top, y_bot):
    """One flat panel of bodyside, on one side of the car.

    ⭐ ONE QUAD, because the R62 has NO TUMBLEHOME: the bodyside is a single
    vertical plane at |x| = 1.3109 from the sill to the eaves. The M7 needed a
    four-segment swept profile here and a matching v table to go with it.
    """
    u_at = u_at_for(bay, tex, side)
    z0, z1 = bay.z(z_a), bay.z(z_b)
    v_top, v_bot = L.side_v(y_top), L.side_v(y_bot)
    uv = [(u_at(z0), v_top), (u_at(z1), v_top),
          (u_at(z1), v_bot), (u_at(z0), v_bot)]
    quad_x(group, tex.material, side * L.DONOR_HALF_X, z0, z1, y_top, y_bot,
           uv, float(side))


def side_glass(group, material, bay, side, tex):
    """Translucent panes over the apertures `open_glazing` cut in this bay.

    Boxes come in as full-elevation uv and are filtered by the bay itself, so a
    pane lands in whichever bay contains the donor z it was drawn at and
    nowhere else. Inset 20 mm behind the skin: the aperture is a hole in a
    single-sided sheet, so a coplanar pane would be the first thing its edge
    cuts into.
    """
    made = 0
    inset = 0.020
    for (u0, u1, v0, v1) in tex.glazing:
        za, zb = sorted((L.z_of_u(u0), L.z_of_u(u1)))
        lo, hi = (bay.za, bay.zb) if side > 0 else (-bay.zb, -bay.za)
        if not (lo - 1e-6 <= za and zb <= hi + 1e-6):
            continue
        y_hi, y_lo = L.side_y(v0), L.side_y(v1)
        z0 = bay.z(za if side > 0 else -zb)
        z1 = bay.z(zb if side > 0 else -za)
        quad_x(group, material, side * (L.DONOR_HALF_X - inset),
               min(z0, z1), max(z0, z1), y_hi, y_lo,
               [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)], float(side))
        made += 1
    return made


def roof_shell(group, tex, bay, z_a, z_b):
    """The roof dome across the whole car, one quad per profile segment."""
    z0, z1 = bay.z(z_a), bay.z(z_b)
    u_at = u_at_for(bay, tex, +1)
    for sign in (-1.0, 1.0):
        for (x0, y0, v0), (x1, y1, v1) in zip(L.ROOF_PROFILE,
                                              L.ROOF_PROFILE[1:]):
            a, b = sign * x0, sign * x1
            va = v0 if sign > 0 else 1.0 - v0
            vb = v1 if sign > 0 else 1.0 - v1
            quad(group, tex.material, [
                (sx(a), sy(y0), z0, u_at(z0), va),
                (sx(a), sy(y0), z1, u_at(z1), va),
                (sx(b), sy(y1), z1, u_at(z1), vb),
                (sx(b), sy(y1), z0, u_at(z0), vb),
            ], (sign * 0.15, 1.0, 0.0))


def roof_pad(group, tex, bay, z_a, z_b, x_centre, half_x, height_units):
    """A roof vent: a raised panel that FOLLOWS the dome, plus its walls.

    ⭐ IT FOLLOWS THE ROOF INSTEAD OF SITTING ON IT AS A FLAT BOX. The dome
    drops 0.25 m across a vent's own 0.46 m width, so a flat-bottomed box would
    float clear of the roof on its inboard edge by more than its own height.
    The M7's HVAC rafts got away with a flat bottom because they are 2.3 m wide
    and MTR buries the abutment; a vent this small has nowhere to hide it.

    Every quad here is planar by construction: the lid's segments have their
    two x edges at fixed y and z, and each end wall is emitted one profile
    segment at a time in a constant-z plane.
    """
    z0, z1 = bay.z(z_a), bay.z(z_b)
    material = tex.material
    lo, hi = abs(x_centre) - half_x, abs(x_centre) + half_x
    lift = height_units / L.Y_SCALE
    xs = [lo] + [x for (x, _y, _v) in L.ROOF_PROFILE if lo < x < hi] + [hi]

    for sign in (-1.0, 1.0):
        for a, b in zip(xs, xs[1:]):
            ya, yb = L.roof_y(a) + lift, L.roof_y(b) + lift
            ua = (a - lo) / (hi - lo)
            ub = (b - lo) / (hi - lo)
            # the lid
            quad(group, material, [
                (sx(sign * a), sy(ya), z0, ua, 0.0),
                (sx(sign * a), sy(ya), z1, ua, 1.0),
                (sx(sign * b), sy(yb), z1, ub, 1.0),
                (sx(sign * b), sy(yb), z0, ub, 0.0),
            ], (sign * 0.15, 1.0, 0.0))
            # the two end walls, one segment at a time
            for z, out in ((z1, +1.0), (z0, -1.0)):
                quad(group, material, [
                    (sx(sign * a), sy(ya), z, ua, 0.0),
                    (sx(sign * b), sy(yb), z, ub, 0.0),
                    (sx(sign * b), sy(L.roof_y(b)), z, ub, 1.0),
                    (sx(sign * a), sy(L.roof_y(a)), z, ua, 1.0),
                ], (0.0, 0.0, out))
        # the outer and inner flanks
        for x, out in ((hi, +1.0), (lo, -1.0)):
            quad(group, material, [
                (sx(sign * x), sy(L.roof_y(x) + lift), z0, 0.0, 0.0),
                (sx(sign * x), sy(L.roof_y(x) + lift), z1, 1.0, 0.0),
                (sx(sign * x), sy(L.roof_y(x)), z1, 1.0, 1.0),
                (sx(sign * x), sy(L.roof_y(x)), z0, 0.0, 1.0),
            ], (sign * out, 0.0, 0.0))


def underframe(group, tex, tex_lo, bay, z_a, z_b, cap_outer=False):
    """The equipment box below the floor, and the pan that closes the body.

    Uniform along the car — donor builder 9 is one box from end to end — so it
    tiles by construction and every bay simply builds its own share, with NO end
    caps between bays: two adjacent caps would be coplanar and z-fight. Only the
    outermost bay caps its box, and only on the side that faces the coupling.
    """
    z0, z1 = sorted((bay.z(z_a), bay.z(z_b)))
    half = 1.1106
    top, bottom = A.SKIN_BOTTOM_Y, L.LOWEST_Y
    uv = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
    for sign in (-1.0, 1.0):
        quad_x(group, tex.material, sign * half, z0, z1, top, bottom, uv, sign)
    # The floor pan: what you see looking under the car from a platform.
    quad_y(group, tex_lo.material, top,
           [(-L.DONOR_HALF_X, z0), (-L.DONOR_HALF_X, z1),
            (L.DONOR_HALF_X, z1), (L.DONOR_HALF_X, z0)],
           [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)], -1.0)
    quad_y(group, tex_lo.material, bottom,
           [(-half, z0), (-half, z1), (half, z1), (half, z0)],
           [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)], -1.0)
    if cap_outer:
        quad_z(group, tex_lo.material, z1,
               [(-half, bottom), (half, bottom), (half, top), (-half, top)],
               [(0.0, 0.0)] * 4, +1.0)


# --------------------------------------------------------------------- bays

def build_panel_bay(model, tex):
    """The inter-door panel: ONE aperture per side, and they are DIFFERENT.

    ⭐ THIS BAY IS WHY THE WHOLE MODEL IS BUILT BOTH-SIDES-AT-ONCE. On the +x
    side it carries the side ROLLSIGN over a letterbox window; on the -x side,
    a full passenger window. They are not mirror images, they are a 180-degree
    rotation of each other — so the bay is modelled with both and repeated with
    `positionsFlipped`, which turns it round and puts each feature on the other
    side at the other end. See r62_layout's module docstring.
    """
    bay = L.PANEL_BAY
    ext = model.group("panel_exterior")
    for side, key in ((+1, "side_panel_a"), (-1, "side_panel_b")):
        t = tex[key]
        side_wall(ext, t, bay, side, bay.za, bay.zb, A.ROOF_EAVES_Y,
                  A.SKIN_BOTTOM_Y)
        side_glass(ext, tex["glass"].material, bay, side, t)
    roof_shell(model.group("panel_exterior"), tex["roof_panel"], bay,
               bay.za, bay.zb)
    underframe(ext, tex["underframe"], tex["underframe_lo"], bay,
               bay.za, bay.zb)

    # The rollsign housing: a real box standing 0.30 units off the skin, its
    # outboard face wearing the sign texture and its returns a flat dark case.
    # The DISPLAY elements that light it live in r62_doors.bbmodel and are
    # placed from the SAME r62_layout rectangles this is built from.
    z0, z1 = bay.z(L.SIGN_BOX_Z_M[0]), bay.z(L.SIGN_BOX_Z_M[1])
    y0, y1 = L.SIGN_BOX_Y_M
    x_in = L.DONOR_HALF_X
    x_out = L.DONOR_HALF_X + L.SIGN_STANDOFF / L.X_SCALE
    case = tex["case"].material
    quad_x(ext, tex["sign"].material, x_out, z0, z1, y1, y0,
           [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)], +1.0)
    for z, out in ((z1, +1.0), (z0, -1.0)):
        quad_z(ext, case, z, [(x_in, y0), (x_out, y0), (x_out, y1), (x_in, y1)],
               [(0.0, 0.0)] * 4, out)
    for y, out in ((y1, +1.0), (y0, -1.0)):
        quad_y(ext, case, y, [(x_in, z0), (x_in, z1), (x_out, z1), (x_out, z0)],
               [(0.0, 0.0)] * 4, out)


def build_door_bay(model, tex):
    """One door bay — which on this car is nothing but the aperture.

    The donor's own bay boundaries ARE the opening's edges, so there is no
    flanking panel here at all: just the header above, the sill band below, the
    rubber reveal round the hole, and a threshold plate. Both sides, because
    every bay in this model is both sides.
    """
    bay = L.DOOR_BAY
    ext = model.group("door_exterior")
    z0, z1 = bay.z(bay.za), bay.z(bay.zb)
    reveal = tex["reveal"].material

    for side, key in ((+1, "side_door_a"), (-1, "side_door_b")):
        t = tex[key]
        side_wall(ext, t, bay, side, bay.za, bay.zb,
                  A.ROOF_EAVES_Y, L.DOOR_HEAD_Y)          # over the head
        side_wall(ext, t, bay, side, bay.za, bay.zb,
                  A.SILL_Y, A.SKIN_BOTTOM_Y)      # under the sill

        # The reveal: the skin stepped 46 mm inboard around the opening. Two
        # jambs facing into it plus a soffit; the floor closes the bottom, so
        # the donor has no sill piece here and neither does this.
        x_out = side * L.DONOR_HALF_X
        x_in = side * (L.DONOR_HALF_X - L.REVEAL_DEPTH_M)
        for z, out in ((z1, -1.0), (z0, +1.0)):
            quad_z(ext, reveal, z,
                   [(x_in, L.DOOR_SILL_Y), (x_out, L.DOOR_SILL_Y),
                    (x_out, L.DOOR_HEAD_Y), (x_in, L.DOOR_HEAD_Y)],
                   [(0.0, 0.0)] * 4, out)
        quad_y(ext, reveal, L.DOOR_HEAD_Y,
               [(x_in, z0), (x_in, z1), (x_out, z1), (x_out, z0)],
               [(0.0, 0.0)] * 4, -1.0)

        # The threshold. MTR puts a 1x1 step plate under every doorway in its
        # corpus, projecting about half a unit past the skin; without it you
        # see the floor stop in mid air from a platform.
        drop = 1.0 / L.Y_SCALE
        proud = 0.5 / L.X_SCALE
        inner = side * (L.DONOR_HALF_X - 0.12)
        outer = side * (L.DONOR_HALF_X + proud)
        box(ext, tex["threshold"].material, inner, outer,
            L.DOOR_SILL_Y - drop, L.DOOR_SILL_Y, z0, z1)

    roof_shell(ext, tex["roof_door"], bay, bay.za, bay.zb)
    underframe(ext, tex["underframe"], tex["underframe_lo"], bay,
               bay.za, bay.zb)


def nose_xs():
    """The x samples the whole end is built on, corner boundary included.

    NOSE_PLAN's knots and ROOF_PROFILE's, unioned, plus `CORNER_X` — which is
    already a NOSE_PLAN knot, and is asserted to be a member here so that the
    mask/corner material change always lands on a facet EDGE. A material change
    in the middle of a facet would need the facet split, and a facet split that
    nobody notices is how a seam appears.
    """
    xs = sorted({x for (x, _z) in L.NOSE_PLAN}
                | {x for (x, _y, _v) in L.ROOF_PROFILE}
                | {L.CORNER_X})
    assert L.CORNER_X in xs
    return xs


def face_uv(material_is_mask, x_m, y_m):
    """uv for one point of the end shell, in whichever texture wears it.

    ⭐ THE CORNER STRIP IS ADDRESSED BY |x| ALONE AND CARRIES NO CONTENT. That
    is what makes it impossible for a window or a lamp to appear on the turn —
    the failure the user reported. Its u is the fraction of the corner span, so
    the three rib lines stay put; its v is the mask's own, so its tone bands
    meet the mask's at the seam.
    """
    if material_is_mask:
        return (L.mask_u(x_m), L.mask_v(y_m))
    t = (abs(x_m) - L.CORNER_X) / (L.DONOR_HALF_X - L.CORNER_X)
    return (min(1.0, max(0.0, t)), L.mask_v(y_m))


def nose_face(group, mask_material, corner_material, bay, y_bot):
    """The flat front face: from the sill up to the BROW, both halves.

    ⭐ EACH FACET IS EXACTLY PLANAR AND THAT IS THE WHOLE DESIGN. Below the
    brow the donor's nose depends on |x| ONLY — measured, 79 z planes but one
    ruled surface — so a quad spanning [x0,x1] between two CONSTANT heights
    lies in a single plane and gets a single normal.

    ⭐ AND IT STOPS AT THE BROW, NOT AT THE EAVES. `L.NOSE_BROW` runs the flat
    face on above the eaves — 0.09 m at the centreline, tapering to nothing at
    the corner — because that is where the donor's own front face stops, and
    because stopping it at the eaves is what gave the first build a beak: a
    vertical face meeting a straight chord in a crease whose apex stuck 0.37 m
    forward on the centreline. The facet is no longer rectangular (its top edge
    slopes), so it is no longer exactly planar — but brow_y moves 7 mm across
    the widest facet against a 0.29 m span, which is a tenth of the fillet's
    own residual and far below anything that can read as faceting.
    """
    xs = nose_xs()
    for sign in (-1.0, 1.0):
        for a, b in zip(xs, xs[1:]):
            # ⭐ THE RECESS IS A HOLE AND THIS FACE MUST NOT PLATE OVER IT.
            # `nose_xs` unions NOSE_PLAN's knots with ROOF_PROFILE's, and the
            # roof is sampled from the CENTRELINE out — so it contributes x 0.0
            # and 0.2346, two facets per side that lie wholly inside the
            # storm-door opening. Drawn, they seal the pocket at the nose plane:
            # the deepest relief on the car flattens out, and the safety chains
            # slung across the recess disappear behind them. Only the window
            # showed through, because the glazing mechanism cuts that as a real
            # alpha hole through whatever is in front of it, which is exactly
            # the kind of partial symptom that reads as an art bug.
            # `storm_door()` owns this span: its returns, soffit, header, leaf.
            if b <= L.STORM_DOOR_HALF_X + 1e-9:
                continue
            mask = b <= L.CORNER_X + 1e-9
            material = mask_material if mask else corner_material
            xa, xb = sign * a, sign * b
            ya, yb = L.brow_y(a), L.brow_y(b)
            za, zb = bay.z(L.nose_z(a)), bay.z(L.nose_z(b))
            quad(group, material, [
                (sx(xa), sy(ya), za) + face_uv(mask, xa, ya),
                (sx(xb), sy(yb), zb) + face_uv(mask, xb, yb),
                (sx(xb), sy(y_bot), zb) + face_uv(mask, xb, y_bot),
                (sx(xa), sy(y_bot), za) + face_uv(mask, xa, y_bot),
            ], (sign * 0.4, 0.0, 1.0))


def nose_fillet(group, mask_material, corner_material, bay):
    """The roll from the brow up onto the roof dome's front edge.

    ⭐ THE ONE SURFACE ON THIS CAR THAT CANNOT BE PLANAR, AND NOW THE ONE THAT
    IS ACTUALLY CURVED. The first cut spanned it with a SINGLE ruled strip —
    one straight chord from the brow to the roof's leading edge — which is
    where the "beak" came from: the chord and the flat face below it meet at an
    angle, and that crease projects 0.37 m forward at the centreline and
    nothing at all at the corner, so it reads as a prow pointing at you.

    It is now `len(FILLET_PROFILE) - 1` rings deep, following the donor's own
    normalised roll, so the brow is a soft break and the surface arrives at the
    roof tangentially. Every ring is still a ruled strip between two x samples,
    so every quad's out-of-plane residual stays a fraction of a degree — five
    times smaller than the single-chord version's, because each quad is now a
    fifth as tall.
    """
    xs = nose_xs()
    rings = len(L.FILLET_PROFILE) - 1
    for sign in (-1.0, 1.0):
        for a, b in zip(xs, xs[1:]):
            mask = b <= L.CORNER_X + 1e-9
            material = mask_material if mask else corner_material
            xa, xb = sign * a, sign * b
            for i in range(rings):
                ya0, za0 = L.fillet_point(a, i)
                ya1, za1 = L.fillet_point(a, i + 1)
                yb0, zb0 = L.fillet_point(b, i)
                yb1, zb1 = L.fillet_point(b, i + 1)
                quad(group, material, [
                    (sx(xa), sy(ya1), bay.z(za1)) + face_uv(mask, xa, ya1),
                    (sx(xb), sy(yb1), bay.z(zb1)) + face_uv(mask, xb, yb1),
                    (sx(xb), sy(yb0), bay.z(zb0)) + face_uv(mask, xb, yb0),
                    (sx(xa), sy(ya0), bay.z(za0)) + face_uv(mask, xa, ya0),
                ], (sign * 0.2, 0.7, 0.7))


def storm_door(group, material, glass_material, bay):
    """The centre door and its recess — the deepest relief on the car.

    There are no mask vertices between x -0.4168 and +0.4168 BELOW the door's
    own head in the donor: that span is a real opening, with the leaf 248 mm
    behind the nose face. So the recess gets its two returns and a soffit, and
    the leaf gets its own plane with a glazed window.

    ⭐ THE HEADER IS NOT OPTIONAL NOW THE BROW EXISTS. The flat front face used
    to stop at the eaves, and the recess reached the same height, so nothing
    was missing above the door. With the face running on to `brow_y` there is a
    band between the door head (3.067) and the brow (3.201 at the centreline)
    that the facets skip — 0.13 m of daylight straight into the car. The donor
    has a face there, at the nose's own z, and so does this.
    """
    half = L.STORM_DOOR_HALF_X
    face_z = bay.z(L.nose_z(0.0))
    door_z = bay.z(L.STORM_DOOR_Z)
    top, sill = L.STORM_DOOR_TOP_Y, L.DOOR_SILL_Y

    # the header across the recess, between the door head and the brow
    quad_z(group, material, bay.z(L.nose_z(0.0)),
           [(-half, top), (half, top), (half, L.brow_y(0.0)),
            (-half, L.brow_y(0.0))],
           [(L.mask_u(-half), L.mask_v(top)), (L.mask_u(half), L.mask_v(top)),
            (L.mask_u(half), L.mask_v(L.brow_y(0.0))),
            (L.mask_u(-half), L.mask_v(L.brow_y(0.0)))], +1.0)

    for sign in (-1.0, 1.0):
        quad_x(group, material, sign * half, door_z, face_z, top, sill,
               [(L.mask_u(sign * half), L.mask_v(top)),
                (L.mask_u(sign * half), L.mask_v(top)),
                (L.mask_u(sign * half), L.mask_v(sill)),
                (L.mask_u(sign * half), L.mask_v(sill))], -sign)
    quad_y(group, material, top,
           [(-half, door_z), (-half, face_z), (half, face_z), (half, door_z)],
           [(L.mask_u(0.0), L.mask_v(top))] * 4, -1.0)

    quad_z(group, material, door_z,
           [(-half, sill), (half, sill), (half, top), (-half, top)],
           [(L.mask_u(-half), L.mask_v(sill)), (L.mask_u(half), L.mask_v(sill)),
            (L.mask_u(half), L.mask_v(top)), (L.mask_u(-half), L.mask_v(top))],
           +1.0)
    # Its window: a translucent pane just proud of the leaf, over the hole the
    # glazing mechanism cut.
    wy0, wy1 = L.STORM_DOOR_WINDOW_Y
    wx = L.STORM_DOOR_WINDOW_X
    quad_z(group, glass_material, door_z + 0.30,
           [(-wx, wy0), (wx, wy0), (wx, wy1), (-wx, wy1)],
           [(0.0, 1.0), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)], +1.0)


def safety_chains(group, tex, bay):
    """The chains slung across the storm-door recess, as real bars.

    ⭐ THE DONOR DRAWS THEM AS A COLOUR-KEYED BILLBOARD AND WE CANNOT. Chain.png
    over DriverChain.B3D is three chevrons and a keeper strap, cut out of a
    quad. At this scale a chain link is a third of a pixel: an alpha cutout
    would dissolve entirely, and the first cut's fallback — two straight dark
    lines painted on the leaf — read as panel joints, which is what they are
    shaped like.

    So each run is a square-section BAR: one M unit thick, mitred at the keeper
    strap in the middle so the pair makes the V the photograph shows. Six bars
    plus a strap per end, and they sit 20 mm in front of the leaf so they cast
    against it rather than z-fighting it.
    """
    half = L.CHAIN_ANCHOR_X
    t = L.CHAIN_THICK_UNITS / 2.0 / L.X_SCALE          # half thickness, metres
    ty = L.CHAIN_THICK_UNITS / 2.0 / L.Y_SCALE
    z_face = bay.z(L.STORM_DOOR_Z + L.CHAIN_PROUD_M)
    material = tex.material
    for y in L.CHAIN_YS:
        low = y - L.CHAIN_SAG_M
        for sign in (-1.0, 1.0):
            # one straight run from the post down to the keeper. A quad in the
            # z plane plus a matching one a bar's thickness in front gives the
            # run a body from any angle without a swept box per segment.
            for dz in (-t, +t):
                quad_z(group, material, z_face + dz * L.Z_SCALE,
                       [(sign * half, y - ty), (0.0, low - ty),
                        (0.0, low + ty), (sign * half, y + ty)],
                       [(0.0, 1.0), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)],
                       +1.0 if dz > 0 else -1.0)
    # the keeper strap: one vertical bar down the centreline, holding the Vs
    box(group, material, -t, t, min(L.CHAIN_YS) - L.CHAIN_SAG_M - ty,
        max(L.CHAIN_YS) + ty,
        z_face - t * L.Z_SCALE, z_face + t * L.Z_SCALE,
        uv=(0.0, 0.0, 1.0, 1.0))


def anticlimber(group, tex, bay):
    """The end beam: the outermost 0.626 m of the car, under the sill.

    A real box, MTR's own idiom for a cab-end anticlimber, and the thing that
    gives a coupled pair of R62s the deep shadowed gap between them. Its top
    face IS the end platform a passenger would walk across.
    """
    z0, z1 = bay.z(L.ANTICLIMBER_Z_M[0]), bay.z(L.ANTICLIMBER_Z_M[1])
    y0, y1 = L.ANTICLIMBER_Y_M
    box(group, tex.material, -L.ANTICLIMBER_HALF_X, L.ANTICLIMBER_HALF_X,
        y0, y1, z0, z1)


def arm_buffers(group, tex, bay):
    """The spring buffers at the lower corners, projecting past the beam.

    An octagonal barrel on its side — MTR's whole corpus builds a cylinder as
    an octagon, and at 0.17 m diameter anything finer is invisible. Its axis is
    the beam's own mid-height, so it reads as bolted through the anticlimber
    rather than stuck onto it, and its cap is a flat disc of the same plate.

    NOT IN THE DONOR (see r62_layout) — every number is from the user's photo,
    so radius and projection are tuned in one place and rendered as options.
    """
    z_face = L.ANTICLIMBER_Z_M[1]
    r = L.BUFFER_RADIUS_M
    ry = r * L.X_SCALE / L.Y_SCALE                # keep it round, not oval
    cy = L.BUFFER_Y
    n = L.BUFFER_SIDES
    material = tex.material
    for sign in (-1.0, 1.0):
        cx = sign * L.BUFFER_CENTRE_X
        ring = [(cx + r * math.cos(2 * math.pi * (i + 0.5) / n),
                 cy + ry * math.sin(2 * math.pi * (i + 0.5) / n))
                for i in range(n)]
        z0, z1 = bay.z(z_face - r), bay.z(z_face + L.BUFFER_PROUD_M)
        for (xa, ya), (xb, yb) in zip(ring, ring[1:] + ring[:1]):
            nx, ny = (xa + xb) / 2.0 - cx, (ya + yb) / 2.0 - cy
            quad(group, material, [
                (sx(xa), sy(ya), z0, 0.0, 0.0),
                (sx(xa), sy(ya), z1, 1.0, 0.0),
                (sx(xb), sy(yb), z1, 1.0, 1.0),
                (sx(xb), sy(yb), z0, 0.0, 1.0),
            ], (nx, ny, 0.0))
        quad_z(group, material, z1,
               [(x, y) for x, y in ring[:4]], [(0.0, 0.0)] * 4, +1.0)
        quad_z(group, material, z1,
               [ring[0], ring[3], ring[4], ring[7]], [(0.0, 0.0)] * 4, +1.0)
        quad_z(group, material, z1,
               [(x, y) for x, y in ring[4:]], [(0.0, 0.0)] * 4, +1.0)


# ------------------------------------------------------------------- coupler
#
# The design is `tools/coupler.py` — shared verbatim with the M7, in M units.
# What is this car's alone is where the assembly roots and which way its
# knuckle points.
#
# ⭐ THE ROOT IS MEASURED, NOT PICKED. `underframe()` closes its equipment box
# with `cap_outer` at the end bay's own outer limit (donor `ROOF_FRONT_Z`), so
# there is a real face for the coupler to come out of, and everything inboard
# of it is inside an opaque box. That distance is 10.30 units, which would put
# the yoke exactly half buried — the look we want — but leaves only 0.3 units
# of drawbar between the yoke and the head, so `coupler.MIN_ROOT_D` forbids it.
# Taking the larger of the two roots the yoke 0.7 units deeper and still shows
# 2.3 units of its mouth through the cap, with a whole unit of drawbar clear.
# (The M7 has no such box at its ends and has to pick a number instead; that
# asymmetry is why the shared module takes the root as a parameter.)
COUPLER_CAP_D = L.END_BAY.units / 2.0 - L.END_BAY.z(L.ROOF_FRONT_Z)
COUPLER_ROOT_D = max(CPL.MIN_ROOT_D, COUPLER_CAP_D)

# ⭐ THE END BAY FACES OUTWARD AT LOCAL +z — the opposite of the M7's, and not
# negotiable: a bay whose donor->local map has negative determinant produces a
# MIRRORED car (see build_end_bay's docstring). `check_winding` asserts it on
# every run by summing the end groups' z-normals.
END_OUTWARD_MZ = +1


def coupler_knuckle_sign():
    """Which authored x is the coupler's own RIGHT, looking out of an end bay.

    ⭐ READ OFF THE HANDEDNESS FLIP, never typed. `r62_layout.emit_x` is the
    single place this train states how M x reaches the world, and turning
    `MIRROR_X` off is a documented escape hatch — so the knuckle side has to
    follow it, exactly as `check_cab_side` in the interior converter follows it
    rather than asserting a side. Get this wrong and both knuckles at a joint
    land on the SAME side of the centreline, where they interpenetrate, and
    nothing in game reports it.
    """
    return CPL.knuckle_sign(outward_mz=END_OUTWARD_MZ,
                            emit_x_sign=1 if L.emit_x(1.0) > 0 else -1)


def coupler_assembly(group, tex, bay):
    """The coupler under one car end, reaching the coupling plane.

    The bay's outer edge IS the coupling plane: `couplingPadding` is 0 on both
    vehicles (gen_r62_assets), so MTR butts the next car's end face straight
    against this one's. `d` therefore counts inboard from `+units/2` — the sign
    is the whole difference between this car's end bay and the M7's.

    It sits UNDER the anticlimber, not through it: the beam occupies M y
    -2.81..0 and the coupler -8..-4, so the two never meet, and the buffers
    that flank the beam are at |x| 16 units against the coupler's 4.
    """
    plane = bay.units / 2.0

    def add_box(material, x0, x1, y0, y1, z0, z1):
        # coupler.py speaks M units throughout; this file's `box` takes donor
        # metres across and up. `sy` is a single linear factor on this car
        # (r62_layout: no underfloor compression), so the inverse is a divide.
        box(group, material, x0 / L.X_SCALE, x1 / L.X_SCALE,
            L.FLOOR_Y + y0 / L.Y_SCALE, L.FLOOR_Y + y1 / L.Y_SCALE, z0, z1)

    CPL.emit(add_box, tex.material, COUPLER_ROOT_D, coupler_knuckle_sign(),
             lambda d: plane - d)


def window_bezel(group, tex, bay, x_m, y_m):
    """A proud frame round one of the cab's two windows.

    ⭐ THE FRONT'S CHARACTER IS DEPTH, NOT PAINT (user brief). The windshield
    and the roundel both sit in RAISED bezels on the prototype; drawn flat they
    read as decals on a slab. One M unit proud, one and a half wide, on the
    nose's own local z — which varies across the frame, so each of the four
    sides takes the nose depth at its own x and the frame follows the curve
    instead of standing off it at one end.

    ⭐ THE BANDS SAMPLE THE MASK AT THEIR OWN POSITION, NOT 0..1 (user-reported
    "duplicated lamp art"). The first cut gave every band and every return the
    full-texture uv [0,1]x[0,1], which squeezes the ENTIRE front image — both
    windows, all four lamp wells — into a frame a pixel or two wide. That is
    what put the "two spurious dark copies" beside every lamp: they are not
    copies of the lamp geometry at all, they are the whole mask reproduced in
    miniature inside each bezel band and each collar facet. Positional uv makes
    a band read as the stainless it is cut from, and lets the relief do the
    work. `lamp_housing` had the same defect and the same fix.

    ⭐ AND THE FRAME IS CLAMPED TO `CORNER_X`. The windshield reaches donor
    |x| 1.1100 and a 0.101 m bezel would put its outboard jamb at 1.2113 —
    past the corner boundary, where mask uv are outside [0,1] and `wrap_face`
    smears the whole texture. Clamping costs 18 mm on one edge of one frame and
    keeps "no mask outboard of CORNER_X" a rule with no exceptions.
    """
    proud = L.BEZEL_PROUD / L.Z_SCALE
    w = L.BEZEL_WIDTH / L.X_SCALE
    h = L.BEZEL_WIDTH / L.Y_SCALE
    x0, x1 = min(x_m), max(x_m)
    y0, y1 = min(y_m), max(y_m)
    material = tex.material

    def clamp(x):
        return max(-L.CORNER_X, min(L.CORNER_X, x))

    # the four faces of the frame, each a flat band standing off the nose
    bands = ((clamp(x0 - w), x0, y0 - h, y1 + h),    # inboard/outboard jambs
             (x1, clamp(x1 + w), y0 - h, y1 + h),
             (x0, x1, y1, y1 + h),                   # head
             (x0, x1, y0 - h, y0))                   # sill
    for (a, b, c, d) in bands:
        zc = L.nose_z((a + b) / 2.0) + proud
        corners = ((a, c), (b, c), (b, d), (a, d))
        quad_z(group, material, bay.z(zc), corners,
               [face_uv(True, x, y) for (x, y) in corners], +1.0)
        # the frame's own returns, so it reads as a box and not as a decal
        for x, out in ((a, -1.0), (b, +1.0)):
            quad_x(group, material, x, bay.z(L.nose_z(x)), bay.z(zc), d, c,
                   [face_uv(True, x, d), face_uv(True, x, d),
                    face_uv(True, x, c), face_uv(True, x, c)], out)


def lamp(group, material, bay, x_m, y_m):
    """The LIT lens: a round glowing disc on a quad clear of the nose.

    ⭐ 26 mm PROUD, NOT 8. The nose drops 33 mm across a lamp's own diameter,
    so the first cut's 8 mm — measured at the lens CENTRE — left the inboard
    half of every lens buried in the surface it was meant to sit on. That, plus
    a square texture on a square quad, is why the headlights read as clipped
    rectangles beside their own bezels rather than as lamps.

    The texture is round with a transparent field and the material carries
    `#light` (CUTOUT_GLOWING), so the quad's corners are a real hole and the
    disc's edge is the lamp's edge.
    """
    r = L.LAMP_LENS_R_M
    ry = r * L.X_SCALE / L.Y_SCALE
    z = bay.z(L.nose_z(x_m) + L.LAMP_PROUD_M)
    quad_z(group, material, z,
           [(x_m - r, y_m - ry), (x_m + r, y_m - ry),
            (x_m + r, y_m + ry), (x_m - r, y_m + ry)],
           [(0.0, 1.0), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)], +1.0)


def lamp_housing(group, tex, bay, x_m, y_m):
    """The round boss a lamp is bolted into: an octagonal collar on the nose.

    Always drawn, lit or not — it is part of the shell, and it is what stops a
    switched-off lamp being a flat painted circle. The rim's own front face
    wears the mask (which paints the bezel and the dead lens at exactly this
    place), so the collar and the paint are one object.

    ⭐ THE COLLAR FACETS SAMPLE THE MASK POSITIONALLY. They used the full
    texture, 0..1 across each facet — and a collar facet is about two pixels
    wide, so each of the eight reproduced the WHOLE front of the car, lamps and
    all, in miniature. Eight tiny masks ringing every lamp is what the user saw
    and reported as "the lens plus two spurious dark copies"; it was never a
    duplicated lamp, it was the mask smeared onto its own bezel. `window_bezel`
    carried the identical defect. Positional uv makes the collar read as the
    stainless around it, which is what a bolted rim actually looks like.
    """
    r = L.LAMP_RIM_R_M
    ry = r * L.X_SCALE / L.Y_SCALE
    n = L.LAMP_SIDES
    ring = [(x_m + r * math.cos(2 * math.pi * (i + 0.5) / n),
             y_m + ry * math.sin(2 * math.pi * (i + 0.5) / n))
            for i in range(n)]
    for (xa, ya), (xb, yb) in zip(ring, ring[1:] + ring[:1]):
        za = bay.z(L.nose_z(xa))
        zb = bay.z(L.nose_z(xb))
        zf = bay.z(L.nose_z(x_m) + L.LAMP_PROUD_M)
        nx, ny = (xa + xb) / 2.0 - x_m, (ya + yb) / 2.0 - y_m
        ua, va = face_uv(True, xa, ya)
        ub, vb = face_uv(True, xb, yb)
        quad(group, tex.material, [
            (sx(xa), sy(ya), za, ua, va),
            (sx(xa), sy(ya), zf, ua, va),
            (sx(xb), sy(yb), zf, ub, vb),
            (sx(xb), sy(yb), zb, ub, vb),
        ], (nx, ny, 0.2))


def build_end_bay(model, tex, blind=False):
    """One car end. Both ends of both vehicles are this same shell.

    ⭐ AUTHORED AS THE +z END, cab on +x and route sign on -x, which is exactly
    what the donor's +z end is. `end1` places it unflipped at +108.75 and `end2`
    places it flipped, which turns it round into the -z end with the cab on -x
    and the sign on +x — the diagonal opposition the prototype has, for free.

    `blind` is the `r62_middle` variant: the same geometry with a mask that has
    no cab features, and no lamps. A middle car in a long consist should not
    show a cab face; a real R62 has no such car, so this is a deliberate
    fiction and it is documented as one in R62_NOTES.md.
    """
    bay = L.END_BAY
    suffix = "_blind" if blind else ""
    ext = model.group("end%s_exterior" % suffix)
    mask = tex["front_blind" if blind else "front"]

    # The side walls run from the bay's inner edge out to where the bodyside
    # stops and the nose takes over.
    for side, key in ((+1, "side_end_a"), (-1, "side_end_b")):
        t = tex[key]
        side_wall(ext, t, bay, side, bay.za, L.ROOF_FRONT_Z,
                  A.ROOF_EAVES_Y, A.SKIN_BOTTOM_Y)
        side_glass(ext, tex["glass"].material, bay, side, t)
    roof_shell(ext, tex["roof_end"], bay, bay.za, L.ROOF_FRONT_Z)
    underframe(ext, tex["underframe"], tex["underframe_lo"], bay,
               bay.za, L.ROOF_FRONT_Z, cap_outer=True)

    # The two roof vents this end owns, one per corner. Placed once here and
    # twice by the definitions gives the car its four.
    for sign in (-1.0, 1.0):
        roof_pad(ext, tex["vent"], bay, L.VENT_Z_M[0], L.VENT_Z_M[1],
                 sign * L.VENT_CENTRE_X, L.VENT_HALF_X, L.VENT_HEIGHT_UNITS)

    corner = tex["corner"].material
    nose_face(ext, mask.material, corner, bay, L.NOSE_SILL_Y)
    nose_fillet(ext, mask.material, corner, bay)
    storm_door(ext, mask.material, tex["glass"].material, bay)
    safety_chains(ext, tex["chain"], bay)
    anticlimber(ext, tex["apron"], bay)
    arm_buffers(ext, tex["apron"], bay)
    # The coupler, below the beam. NOT mirrored and NOT symmetric — the knuckle
    # is on one side by design, which is what lets two of them interlock rather
    # than collide (coupler.py). It is above `blind`'s early return because a
    # blind end couples exactly like a cab end; that is the whole point of the
    # variant.
    coupler_assembly(ext, tex["coupler"], bay)

    if blind:
        return

    # The two window bezels and the four lamp housings — the front's relief.
    # All of it is shell, so it lives in `end_exterior` and is drawn whether or
    # not a lamp happens to be burning.
    window_bezel(ext, mask, bay, L.WINDSCREEN_X_M, L.WINDSCREEN_Y_M)
    window_bezel(ext, mask, bay, L.ROUNDEL_X_M, L.ROUNDEL_Y_M)
    for sign in (-1.0, 1.0):
        for row in (L.LAMP_ROW_UPPER_Y, L.LAMP_ROW_LOWER_Y):
            lamp_housing(ext, mask, bay, sign * L.LAMP_CENTRE_X,
                         sum(row) / 2.0)

    # The windshield's glass, over the aperture `open_glazing` cut. Boxes come
    # from the art, so the pane cannot drift from the hole.
    for (u0, u1, v0, v1) in mask.glazing:
        x0, x1 = sorted((L.mask_x_of_u(u0), L.mask_x_of_u(u1)))
        y0, y1 = sorted((L.mask_y_of_v(v0), L.mask_y_of_v(v1)))
        if abs(x0) < L.STORM_DOOR_HALF_X:
            continue                     # the storm door's own window; done
        quad_z(ext, tex["glass"].material,
               bay.z(L.nose_z((x0 + x1) / 2.0) + 0.020),
               [(x0, y0), (x1, y0), (x1, y1), (x0, y1)],
               [(0.0, 1.0), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)], +1.0)

    # ⭐ THE LIT LENSES, AND THE VERDICT ON WHETHER CONDITIONS REACH THEM.
    # `PartCondition` DOES work on a NORMAL part of an .obj — verified in 4.0.5
    # bytecode, both render paths: `ModelPropertiesPart.lambda$addObjModelPosition$46`
    # keys the baked `ObjModelWrapper` map by `this.condition` before
    # `RenderStage`, and `VehicleResource.lambda$queue$23` gates each condition
    # bucket through `matchesCondition` on the way to `MainRenderer`; the
    # un-optimised `ModelPropertiesPart.render` tests it in its first four
    # instructions. So the lamps stay HERE, in the .obj, and do NOT move to the
    # doors .bbmodel — that would have been the unexercised-path family for no
    # reason. What was broken was the lens itself: undersized, square, and half
    # buried in the nose. See `lamp()`.
    heads = model.group("headlights")
    tails = model.group("tail_lights")
    for sign in (-1.0, 1.0):
        lamp(heads, tex["lamp_white"].material, bay, sign * L.LAMP_CENTRE_X,
             sum(L.LAMP_ROW_LOWER_Y) / 2.0)
        lamp(tails, tex["lamp_red"].material, bay, sign * L.LAMP_CENTRE_X,
             sum(L.LAMP_ROW_UPPER_Y) / 2.0)


def build_model():
    tex = build_textures()
    model = Model()
    build_panel_bay(model, tex)
    build_door_bay(model, tex)
    build_end_bay(model, tex, blind=False)
    build_end_bay(model, tex, blind=True)
    return model, tex


def used_materials(model, tex):
    names = {m for g in model.groups.values() for m, _ in g.faces}
    return {t.material: t for t in tex.values() if t.material in names}


# ====================================================================
# EMIT
# ====================================================================

def to_obj_vertex(x, y, z):
    """M units -> OBJ. One OBJ unit is one BLOCK.

    ⭐ x IS NO LONGER NEGATED, AND THAT IS THE HANDEDNESS FLIP (r62_layout's
    `MIRROR_X`). The old map negated both x and z — a 180-degree rotation,
    determinant +1 — which faithfully reproduced the donor's LEFT-handed frame
    inside Minecraft's RIGHT-handed one, i.e. a mirrored car: windshield on the
    railfan's right, roundel on his left, the opposite of the prototype and of
    the donor's own front photograph.

    Negating only z makes the map determinant -1, so the winding of every face
    reverses with it; `write_obj` emits each face's vertices in reverse order to
    put it back. That pair — mirror here, reverse there — is the whole flip.
    """
    return (L.emit_x(x) / 16.0, y / 16.0, -z / 16.0)


def bake_v(v):
    """No flip. MTR's .obj path samples v in IMAGE convention (v = 0 is the TOP
    texture row) — an in-game finding on the M7 that cost a rig cycle, and the
    reason `flipTextureV` stays unset in the index. The donor's own UVs are the
    same convention once its negative v is unwrapped, so everything agrees."""
    return v


def wrap01(t):
    return t if 0.0 <= t <= 1.0 else t - math.floor(t)


def wrap_face(values):
    """Bring a whole FACE's coordinates into [0,1] TOGETHER, not one at a time.

    Wrapping per vertex collapses a face whose u runs exactly -1.0 -> 0.0 onto a
    single texture column — the bug that made one of the M7's decals silently
    invisible. Shifting the face by one integer keeps its width.
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
            # ⭐ THE MIRROR REVERSES WINDING, SO THE ORDER GOES BACK. MTR culls
            # backfaces unconditionally; without this every face on the car
            # would be inside out and the model would render as nothing at all.
            # `to_obj_vertex` is the only thing that decides, so this reads the
            # same switch rather than carrying its own copy of the answer.
            body.append("f " + " ".join(refs[::-1] if L.MIRROR_X else refs))

    out = ["# NYCT R62 — generated by tools/convert_openbve_r62.py from the",
           "# openBVE donor. DO NOT EDIT: re-run the converter instead.",
           "mtllib %s" % mtl_name, ""]
    out += ["v %.5f %.5f %.5f" % p for p in verts]
    out += ["vt %.6f %.6f" % p for p in uvs]
    out.append("")
    out += body
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write("\n".join(out) + "\n")

    mtl = ["# Generated by tools/convert_openbve_r62.py — do not edit.", ""]
    for material in sorted(mats):
        mtl += ["newmtl %s" % material, "Kd 1 1 1", "d 1",
                "map_Kd %s" % mtl_path_of(mats[material]), ""]
    with open(os.path.join(os.path.dirname(path), mtl_name), "w") as fh:
        fh.write("\n".join(mtl) + "\n")
    return len(verts), sum(len(g.faces) for g in model.groups.values())


def write_textures(tex, used, directory=None):
    directory = directory or TEX_DIR
    os.makedirs(directory, exist_ok=True)
    written = set()
    for t in tex.values():
        if t.material not in used and t.name != "default":
            continue
        if t.name in written:
            continue
        pngtool.write_png(os.path.join(directory, t.name + ".png"), t.rows)
        written.add(t.name)
    return sorted(written)


# ====================================================================
# ASSEMBLY / PREVIEW — and the positions sanity check
# ====================================================================

PROPS = os.path.join(OUR_NS, "properties/vehicle")
DEFS = os.path.join(OUR_NS, "properties/definition")
DOORS_MODEL = os.path.join(MODEL_DIR, "r62_doors.bbmodel")
INDEX = os.path.join(RES, "assets/mtr/mtr_custom_resources.json")


def load_placements(definition, properties):
    with open(os.path.join(DEFS, definition)) as fh:
        by_name = {d["name"]: d for d in json.load(fh)["positionDefinitions"]}
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


def obj_place(x, y, z, X, Z, flipped):
    """The .obj placement transform, measured — M7_CONVERSION_NOTES Part 1."""
    return (-x - X, y, -z - Z) if flipped else (x - X, y, z + Z)


def bb_place(x, y, z, X, Z, flipped):
    """The .bbmodel placement transform. NOTE THE SIGN DIFFERENCE: for a
    flipped entry the bbmodel path composes R*v + t and the .obj path composes
    R*(v + t), so one lands at +Z and the other at -Z."""
    return (-x + X, y, -z + Z) if flipped else (x + X, y, z + Z)


def assemble(model, placements):
    out = Model()
    for (group, X, Z, flipped) in placements:
        src = model.groups.get(group)
        if src is None:
            continue
        dst = out.group(group)
        for material, vs in src.faces:
            dst.faces.append((material, [obj_place(x, y, z, X, Z, flipped)
                                         + (u, v) for (x, y, z, u, v) in vs]))
    return out


def leaf_preview(model, tex):
    """Bake the .bbmodel's leaf planes into the .obj model, for previews only.

    ⭐ NOT SHIPPED, AND NOT SHIPPABLE. The leaves genuinely have to live in the
    .bbmodel — MTR's .obj path offsets an animating door twice — so this exists
    purely so `--assemble` can render a car with its doors SHUT. Without it every
    preview shows three holes per side and there is no way to check the single
    thing the leaf art exists for: that the three polished belt rails run
    straight across a closed opening without stepping.

    It reads the shipped .bbmodel and reproduces vanilla's box unwrap for the
    outboard face, so what gets rendered is the texture MTR will sample, not a
    re-derivation of it. Placement goes through `bb_place` — the .bbmodel's own
    transform, which is NOT the .obj's — so the preview also exercises that.
    """
    if not (os.path.exists(DOORS_MODEL) and os.path.exists(DEFS)):
        return
    with open(DOORS_MODEL) as fh:
        bb = json.load(fh)
    res = bb["resolution"]
    by_uuid = {e["uuid"]: e for e in bb["elements"]}
    try:
        with open(os.path.join(DEFS, "r62.json")) as fh:
            defs = {d["name"]: d
                    for d in json.load(fh)["positionDefinitions"]}
    except OSError:
        return
    entries = [(float(e.get("z", 0.0)), flipped)
               for key, flipped in (("positions", False),
                                    ("positionsFlipped", True))
               for e in defs.get("bbDoor", {}).get(key, [])]
    group = model.group("door_leaf_preview")
    material = tex["doors_box"].material
    for outline in bb.get("outliner", []):
        name = outline.get("name", "")
        if not name.endswith("_exterior") or not name.startswith("door_"):
            continue
        for uuid in outline.get("children", []):
            e = by_uuid.get(uuid)
            if e is None or e.get("uv_offset") is None:
                continue
            frm, to = e["from"], e["to"]
            dz = int(round(to[2] - frm[2]))
            dy = int(round(to[1] - frm[1]))
            u0, v0 = e["uv_offset"]
            # vanilla's WEST rect — the bb +x (outboard) face.
            uu = (u0 / res["width"], (u0 + dz) / res["width"])
            vv = ((v0 + dz) / res["height"],
                  (v0 + dz + dy) / res["height"])
            for Z, flipped in entries:
                pts = [bb_place(frm[0], to[1], frm[2], 0.0, Z, flipped),
                       bb_place(frm[0], to[1], to[2], 0.0, Z, flipped),
                       bb_place(frm[0], frm[1], to[2], 0.0, Z, flipped),
                       bb_place(frm[0], frm[1], frm[2], 0.0, Z, flipped)]
                uvs = [(uu[1], vv[0]), (uu[0], vv[0]),
                       (uu[0], vv[1]), (uu[1], vv[1])]
                quad(group, material,
                     [p + uvs[i] for i, p in enumerate(pts)],
                     (1.0 if pts[0][0] > 0 else -1.0, 0.0, 0.0))


_DOORS = "r62_doors.json"
VARIANTS = {
    "cab": ("r62.json", ["r62_common.json", "r62_cab_1.json",
                         "r62_cab_2.json", _DOORS]),
    "middle": ("r62.json", ["r62_common.json", "r62_blind_1.json",
                            "r62_blind_2.json", _DOORS]),
}


# ====================================================================
# CONSISTENCY
# ====================================================================

def _resolve(identifier):
    if not identifier.startswith("station_announcer:"):
        return None
    return os.path.join(OUR_NS, identifier.split(":", 1)[1])


def index_model_entries():
    """(model path, properties path) for every R62 model our index stacks.

    The PAIRING matters: a vehicle carries several models and each is bound by
    its OWN properties file, so cross-checking names against the union would let
    a typo in one be covered by a group in another — and would flag every M7
    part against the R62's model.
    """
    if not os.path.exists(INDEX):
        return []
    with open(INDEX) as fh:
        index = json.load(fh)
    out = []
    for vehicle in index.get("vehicles", []):
        if not vehicle.get("id", "").startswith("r62"):
            continue
        for entry in vehicle.get("models", []):
            model = _resolve(entry.get("modelResource", ""))
            prop = _resolve(entry.get("modelPropertiesResource", ""))
            if model and prop and os.path.exists(model) and os.path.exists(prop):
                if (model, prop) not in out:
                    out.append((model, prop))
    return out


def model_group_names(path, obj_groups, bb_groups):
    if path == os.path.join(MODEL_DIR, OBJ_NAME):
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


def check_winding(model, notes):
    """Every outward surface must actually face outward.

    ⭐ A NET-NORMAL SUM PER GROUP IS NOT A WINDING CHECK. The first version of
    this summed each group's y-normals and expected positive, on the reasoning
    that the roof dominates — and it went red the moment the underframe grew a
    full-width floor pan, which is a larger downward-facing area than the roof
    is upward. A sum over a group whose faces point in every direction measures
    nothing about any individual face.

    So this identifies surfaces whose correct normal is KNOWN from their own
    coordinates, and checks each one:

      * a face whose vertices all sit on the bodyside plane must face away from
        the car's axis. That is the surface a reversed quad would make
        invisible from a platform, and it is 60% of what a player ever sees.
      * a face whose vertices all sit on the underframe's bottom must face down.
      * an end group's faces must face outward on balance, which for a group
        that IS one end of the car is a meaningful sum: the nose, the roof cap
        and the anticlimber front all point the same way.
    """
    problems = []
    skin = sx(L.DONOR_HALF_X)
    floor = sy(L.LOWEST_Y)
    checked = 0
    for name in model.order:
        group = model.groups[name]
        for _m, vs in group.faces:
            xs = [v[0] for v in vs]
            ys = [v[1] for v in vs]
            n = face_normal(vs)
            if all(abs(abs(x) - skin) < 0.02 for x in xs) \
                    and max(xs) - min(xs) < 0.02:
                checked += 1
                if n[0] * xs[0] <= 0:
                    problems.append("%s: a bodyside face at x %+.2f faces "
                                    "inward" % (name, xs[0]))
            elif all(abs(y - floor) < 0.02 for y in ys):
                checked += 1
                if n[1] >= 0:
                    problems.append("%s: an underframe bottom face points up"
                                    % name)
    for name in ("end_exterior", "end_blind_exterior"):
        group = model.groups.get(name)
        if group is None:
            problems.append("winding check: no group %r" % name)
            continue
        total = sum(face_normal(vs)[2] for _m, vs in group.faces)
        if total <= 0:
            problems.append("group %r faces inward on balance (z sum %.1f) — "
                            "the end bays are authored outward at local +z"
                            % (name, total))
    notes.append("winding: %d bodyside and underframe faces verified "
                 "individually, both end groups outward on balance" % checked)
    return problems


def check_emitted_winding(obj_path, notes):
    """⭐ THE SAME QUESTION, ASKED OF THE FILE THAT SHIPS.

    `check_winding` above audits the M-space model — the faces as `quad()`
    wound them. It is blind to everything `to_obj_vertex` and `write_obj` then
    do, and those two are exactly where the handedness flip lives. So when
    `emit_x` had its sense inverted, every face on the car came out reversed,
    both winding checks stayed green, and the model would have rendered as
    NOTHING in game (MTR culls backfaces unconditionally) — the same shape of
    trap as the display check that measured an unrotated centre.

    This one parses the emitted .obj and asks the one question whose answer is
    known from the coordinates alone: **the roof must face up.** Every
    horizontal face in the top quarter of the car is either roof or vent lid;
    there is nothing up there that legitimately points down, so a simple
    majority is decisive and no per-face table has to be maintained.
    """
    verts, faces, group = [], [], None
    for line in open(obj_path):
        tok = line.split()
        if not tok:
            continue
        if tok[0] == "v":
            verts.append(tuple(float(t) for t in tok[1:4]))
        elif tok[0] == "g":
            group = tok[1]
        elif tok[0] == "f":
            faces.append((group, [int(t.split("/")[0]) - 1 for t in tok[1:]]))
    if not verts:
        return ["emitted winding: %s has no vertices" % obj_path]
    y_hi = max(v[1] for v in verts)
    y_lo = min(v[1] for v in verts)
    cut = y_lo + 0.75 * (y_hi - y_lo)

    up = down = 0
    for _group, idx in faces:
        pts = [verts[i] for i in idx]
        n = [0.0, 0.0, 0.0]
        for a, b in zip(pts, pts[1:] + pts[:1]):
            n[0] += (a[1] - b[1]) * (a[2] + b[2])
            n[1] += (a[2] - b[2]) * (a[0] + b[0])
            n[2] += (a[0] - b[0]) * (a[1] + b[1])
        if abs(n[1]) <= max(abs(n[0]), abs(n[2])):
            continue                     # not a horizontal face
        if sum(p[1] for p in pts) / len(pts) < cut:
            continue                     # not up on the roof
        if n[1] > 0:
            up += 1
        else:
            down += 1
    if down > up:
        return ["emitted winding: %d of %d roof faces point DOWN — the whole "
                "car is inside out and MTR would draw nothing. Check "
                "r62_layout.emit_x against write_obj's face reversal"
                % (down, up + down)]
    notes.append("emitted winding: %d roof faces point up, %d down — the "
                 "shipped .obj is outward" % (up, down))
    return []


def check_corner_carries_no_mask(model, tex, notes):
    """⭐ NO END-MASK CONTENT MAY REACH THE CORNER CURVE (user-reported).

    Outboard of `CORNER_X` the nose is turning hard onto the bodyside and its
    facets are a couple of pixels wide seen head on. Worse, `mask_u` there is a
    hair OUTSIDE [0,1] — the mask's uv fit puts its edge at 1.310824 against a
    bodyside plane at 1.3109 — so `wrap_face` cannot shift the face into range,
    falls back to per-vertex wrapping, and a facet whose u should run
    0.000->0.018 instead runs 1.000->0.018. That drags the ENTIRE mask into
    three pixels of corner, which is the window-and-lamp confetti the user
    photographed on the end curves.

    `r62_layout` has claimed since the fix that "--check proves no face
    carrying the mask reaches past this". It did not: no such check existed.
    This is it, and it is a material test rather than a uv test because the
    remedy is a material — the corner wears `corner_strip`, whose content
    cannot vary along the car at all, so a corner facet that is still on the
    mask is exactly the defect no matter what its uv happen to be.
    """
    limit = sx(L.CORNER_X) + 1e-6
    masks = {tex[k].material for k in ("front", "front_blind") if k in tex}
    problems, checked = [], 0
    for name in ("end_exterior", "end_blind_exterior"):
        group = model.groups.get(name)
        if group is None:
            continue
        for material, vs in group.faces:
            if material not in masks:
                continue
            checked += 1
            worst = max(abs(v[0]) for v in vs)
            if worst > limit:
                problems.append(
                    "%s: a face on %s reaches |x| %.2f, past the corner "
                    "boundary at %.2f — the mask is being smeared onto the "
                    "end curve" % (name, material, worst, limit))
    if not problems:
        notes.append("corner curves: %d mask faces all stop at |x| <= %.2f; "
                     "the curves wear the ribbed corner strip alone"
                     % (checked, limit))
    return problems


def check_storm_door_recess(model, notes):
    """⭐ THE STORM-DOOR POCKET MUST BE A HOLE, NOT A PICTURE OF ONE.

    The recess is the deepest relief on this car and the single biggest piece
    of its sculpted character, and it is also the easiest thing in the model to
    lose without noticing: anything drawn at the nose plane across the centre
    span seals it, and the result still looks like a plausible R62 front,
    because the mask paints a door there anyway. That is how it shipped once —
    `nose_xs()` inherits the roof's centreline samples, so `nose_face` was
    laying two facets per side straight over the opening, and the only clue was
    the safety chains going missing.

    So: no face may sit at the nose plane inside the recess span, between the
    sill and the door head. The leaf, its returns and its soffit are all well
    behind it and the header is above it, so the test is a clean one.

    ⭐ THE SILL BOUND IS NOT DECORATION. The recess is a doorway and starts at
    the floor; everything under the floor on the centreline is underframe, and
    since 2026-07-30 that includes the COUPLER, whose head and yoke are
    constant-z faces 4 units wide sitting squarely inside the recess span. They
    are 4 to 8 units below the sill and half a metre from the nose plane, so
    they never actually tripped this — but a check that would have gone red if
    the coupler had been rooted 2 units differently is a check waiting to
    misfire, so the band it looks in is now stated rather than implied.
    """
    half = sx(L.STORM_DOOR_HALF_X)
    head = sy(L.STORM_DOOR_TOP_Y)
    sill = sy(L.NOSE_SILL_Y)
    for name in ("end_exterior", "end_blind_exterior"):
        group = model.groups.get(name)
        if group is None:
            continue
        for _material, vs in group.faces:
            xs_ = [v[0] for v in vs]
            ys_ = [v[1] for v in vs]
            zs_ = [v[2] for v in vs]
            if max(zs_) - min(zs_) > 0.02:
                continue                 # not a face in a single z plane
            if max(xs_) > half - 1e-6 or min(xs_) < -half + 1e-6:
                continue                 # reaches outside the opening
            if max(ys_) < sill + 1e-6:
                continue                 # under the floor: underframe, not door
            if min(ys_) > head - 1e-6:
                continue                 # up in the header band
            nose = L.END_BAY.z(L.nose_z(0.0))
            if abs(zs_[0] - nose) < 0.5:
                return ["%s: a face at the nose plane (z %.2f) spans the storm-"
                        "door recess at x %.1f..%.1f — the pocket is sealed and "
                        "the chains are behind it" % (name, zs_[0],
                                                      min(xs_), max(xs_))]
    notes.append("storm-door recess: open — nothing plates it at the nose plane")
    return []


def check_emitted_sign_side(obj_path, notes):
    """⭐ THE TWO MODELS MUST AGREE ABOUT WHICH SIDE OF THE CAR IS +x.

    `check_displays` below bakes the .obj housing from the M-SPACE model, so it
    silently assumes the emit stage leaves x alone. For most of this car's life
    that assumption was false — `to_obj_vertex` negated x — and the rollsign
    housing and its four display plates sat on OPPOSITE SIDES of the car while
    the check reported twelve happy placements. Nothing in game says a word: the
    sign box is blank stainless and the text hangs in the air on the far side.

    So this reads the two SHIPPED files and compares the one number that cannot
    be fudged — the sign housing's x in `r62.obj` against the side display's x
    in `r62_doors.bbmodel`. Same sign or the model is wrong. It is deliberately
    a file-to-file test: that is the only place the emit conventions of two
    different generators can be seen at the same time.
    """
    if not os.path.exists(DOORS_MODEL):
        return []
    verts, housing, material, group = [], [], None, None
    for line in open(obj_path):
        tok = line.split()
        if not tok:
            continue
        if tok[0] == "v":
            verts.append(tuple(float(t) for t in tok[1:4]))
        elif tok[0] == "g":
            group = tok[1]
        elif tok[0] == "usemtl":
            material = tok[1]
        elif tok[0] == "f" and group == "panel_exterior" \
                and material == "r62_sign":
            housing += [verts[int(t.split("/")[0]) - 1] for t in tok[1:]]
    if not housing:
        return ["emitted sign side: no r62_sign faces in panel_exterior"]
    obj_x = sum(p[0] for p in housing) / len(housing)

    with open(DOORS_MODEL) as fh:
        elements = json.load(fh).get("elements", [])
    plate = [e for e in elements if e.get("name") == "side_bullet_color"]
    if not plate:
        return ["emitted sign side: no side_bullet_color in the doors model"]
    origin = plate[0].get("origin") or plate[0]["from"]
    bb_x = float(origin[0])

    if obj_x * bb_x <= 0:
        return ["emitted sign side: the .obj sign housing is at x %+.2f blocks "
                "but the .bbmodel display is at x %+.1f units — opposite sides "
                "of the car. r62_layout.emit_x and gen_r62_doors disagree about "
                "the handedness flip" % (obj_x, bb_x)]
    notes.append("emitted sign side: housing x %+.2f blocks and display x %+.1f "
                 "units are on the same side" % (obj_x, bb_x))
    return []


def check_against_donor(notes):
    """⭐ RE-DERIVE THE BAKED CONSTANTS FROM THE DONOR AND REPORT DRIFT.

    Unlike the M7 converter, this one does NOT parse the donor at build time.
    It has nothing to copy: the R62's bodyside is a flat plane, its nose is a
    ruled surface, and every one of its features is REBUILT rather than
    transformed — so what the donor supplies is a table of measurements, and a
    table belongs in `r62_layout.py` where three tools can read it without
    anyone waiting on a 5,856-vertex parse.

    The cost of baking measurements is that they can silently stop matching
    their source. This closes that: when the donor is present, `--check` parses
    it and asserts the numbers still agree. When it is absent (another machine,
    a CI box) the build still works and this simply reports that it was skipped.
    """
    problems = []
    if not os.path.exists(DONOR_EXTERIOR):
        notes.append("donor not present — the baked measurements were not "
                     "re-verified this run (%s)" % DONOR_EXTERIOR)
        return problems
    obj = bve_csv.parse(DONOR_EXTERIOR)
    ext = obj.extents()
    checks = [
        ("half width", max(abs(ext["x"][0]), abs(ext["x"][1])), 1.3423, 0.001),
        ("half length", max(abs(ext["z"][0]), abs(ext["z"][1])),
         L.DONOR_HALF_LEN, 0.001),
        ("roof crown", ext["y"][1], L.ROOF_CROWN_Y, 0.001),
        ("underframe bottom", ext["y"][0], L.LOWEST_Y, 0.001),
    ]
    for label, got, want, tol in checks:
        if abs(got - want) > tol:
            problems.append("donor %s is %.4f, r62_layout says %.4f"
                            % (label, got, want))

    side = obj.builders[0]
    skin = max(abs(round(v.x, 4)) for v in side.vertices)
    if abs(skin - L.DONOR_HALF_X) > 1e-4:
        problems.append("donor bodyside plane is |x| %.4f, r62_layout says %.4f"
                        % (skin, L.DONOR_HALF_X))
    # The u -> z and v -> y fits the whole texture system rests on.
    plus = [v for v in side.vertices if v.x > 0 and v.u is not None]
    worst_u = max(abs(v.u - L.u_of_z(v.z)) for v in plus)
    worst_v = max(abs((v.v + 1.0) - L.side_v(v.y)) for v in plus)
    if worst_u > 1e-4 or worst_v > 1e-4:
        problems.append("the side elevation's uv map has drifted from the "
                        "donor (u by %.5f, v by %.5f)" % (worst_u, worst_v))

    # ⭐ THE NOSE MUST REALLY BE A RULED SURFACE, or the whole faceting design
    # is wrong. Every donor mask vertex below the eaves has to lie on the z(|x|)
    # curve `L.nose_z` claims — sampled on the +z mask, builder 25.
    #
    # Two spans are excluded, and both exclusions are the SIMPLIFICATION being
    # declared rather than a fudge:
    #   * |x| inboard of the storm-door recess, which is a hole, not a surface.
    #   * |x| outboard of NOSE_FOLD_X, where the donor does not ramp to the
    #     bodyside but steps: it runs forward to z 7.258, doubles back to
    #     7.1925 and only then meets the side at 7.1543 — a 65 mm corner return
    #     three centimetres wide. NOSE_PLAN replaces that with a straight ramp,
    #     which at this car's scale is one M unit of chamfer instead of a
    #     three-quad fold nobody can see.
    mask = obj.builders[25]
    worst_nose, worst_at, ruled = 0.0, None, 0
    for v in mask.vertices:
        if v.y > L.NOSE_EAVE_Y - 1e-3:
            continue
        if not (L.STORM_DOOR_HALF_X - 1e-3 <= abs(v.x) <= NOSE_FOLD_X):
            continue
        ruled += 1
        d = abs(v.z - L.nose_z(v.x))
        if d > worst_nose:
            worst_nose, worst_at = d, (round(v.x, 4), round(v.y, 4),
                                       round(v.z, 4))
    if worst_nose > 0.010:
        problems.append("the nose is not the ruled surface the facets assume: "
                        "a donor vertex at %s is %.0f mm off L.nose_z"
                        % (worst_at, worst_nose * 1000))
    notes.append("donor re-verified: bodyside |x| %.4f, uv map within %.5f, "
                 "nose ruled to %.0f mm over %d of %d mask vertices (the outer "
                 "%.0f mm corner fold is deliberately ramped)"
                 % (skin, max(worst_u, worst_v), worst_nose * 1000, ruled,
                    len(mask.vertices), (L.DONOR_HALF_X - NOSE_FOLD_X) * 1000))
    return problems


def joint_px():
    """Columns of the full elevation that carry a drawn bay-boundary joint."""
    out = set()
    for z in L.DONOR_BOUNDS[1:] + tuple(-b for b in L.DONOR_BOUNDS[1:]):
        out.add(int(round(side_px(z))))
    return out


def field_columns(tex):
    """Crop-local columns of `tex` that are pure FIELD, in ascending order.

    ⭐ THE POINT OF THE EXCLUSION. The elevation draws a one-pixel panel joint on
    every bay boundary, and a crop's own edges therefore always land within a
    pixel of one — so a naive "every column must match" test flags the joints
    themselves, which are bay-local content that repeats identically in every
    copy of the bay. What the rule actually forbids is the FIELD varying along
    the car, so the joints (and the pixel either side of one, since the crop
    boundary rounds) come out of the sample.
    """
    joints = joint_px()
    width = len(tex.rows[0])
    bad = {p + d - tex.x0 for p in joints for d in (-1, 0, 1)}
    return [c for c in range(width) if c not in bad]


def check_coupler(model, notes):
    """Everything about the coupler that this car, and not `coupler.py`, owns.

    ⭐ WHY EACH ONE. The shared module promises a shape and an envelope; it
    knows nothing about an R62. Every bound below lives somewhere else:

      * the ANTICLIMBER beam is directly above the coupler and full width. A
        coupler that reached into it would be drawn inside the one piece of
        geometry a player at a coupled joint is actually looking at.
      * the UNDERFRAME FLOOR PAN is below it. Anything hanging past that is
        hanging past the whole car, toward the rail.
      * the ARM BUFFERS flank the beam and PROJECT PAST the coupling plane
        (`BUFFER_PROUD_M`), which is the one other thing on this car that
        does. They must stay clear of the coupler across the car, or the two
        inventions collide at the corner where nobody would think to look.
      * the END BAY. A coupler rooted deeper than the bay is long gets sliced
        by the crop — the pane rule, applied to the underframe.
      * the JOINT itself, which is arithmetic on the shared table.

    Measured on the M-space model rather than on constants, so a member that
    moved in `coupler.py` is measured where it actually landed.
    """
    problems = []
    group = model.groups.get("end_exterior")
    if group is None:
        return ["coupler check: no end_exterior group"]
    material = "r62_coupler"
    faces = [vs for m, vs in group.faces if m == material]
    if not faces:
        return ["coupler check: no faces on %s in end_exterior" % material]
    pts = [v for vs in faces for v in vs]
    top = max(p[1] for p in pts)
    bottom = min(p[1] for p in pts)
    widest = max(abs(p[0]) for p in pts)
    plane = L.END_BAY.units / 2.0
    outermost = max(p[2] for p in pts)
    innermost = min(p[2] for p in pts)

    if top >= sy(L.ANTICLIMBER_Y_M[0]) - 1e-6:
        problems.append("the coupler reaches M y %+.2f, into the anticlimber "
                        "beam whose underside is %+.2f"
                        % (top, sy(L.ANTICLIMBER_Y_M[0])))
    if bottom <= sy(L.LOWEST_Y) + 1e-6:
        problems.append("the coupler hangs to M y %+.2f, below the underframe "
                        "floor pan at %+.2f" % (bottom, sy(L.LOWEST_Y)))
    if widest >= sx(L.BUFFER_CENTRE_X - L.BUFFER_RADIUS_M) - 1e-6:
        problems.append("the coupler reaches |x| %.2f, into the arm buffers "
                        "which start at %.2f"
                        % (widest, sx(L.BUFFER_CENTRE_X - L.BUFFER_RADIUS_M)))
    if innermost < -plane + 1e-6:
        problems.append("the coupler roots past the end bay's inner seam")

    j = CPL.coupled_pair(COUPLER_ROOT_D, coupler_knuckle_sign())
    if abs((outermost - plane) - CPL.KNUCKLE_PROUD) > 1e-6:
        problems.append("the coupler reaches %.2f units past the coupling "
                        "plane, not the %.2f coupler.py promises"
                        % (outermost - plane, CPL.KNUCKLE_PROUD))
    if j["lateral"] <= 0:
        problems.append("two coupled couplers interpenetrate by %.1f units "
                        "across the car — the knuckle offset is wrong"
                        % -j["lateral"])
    if j["head_gap"] <= 0:
        problems.append("a knuckle reaches into the other car's coupler head "
                        "by %.1f units" % -j["head_gap"])
    if j["plane_max"] <= 0:
        problems.append("something other than the knuckle crosses the "
                        "coupling plane")

    # The blind end must carry the same coupler — a middle car couples at both
    # ends, and `build_end_bay` returns early for `blind` right after it.
    blind = model.groups.get("end_blind_exterior")
    n_blind = len([1 for m, _vs in blind.faces if m == material]) if blind else 0
    if n_blind != len(faces):
        problems.append("the blind end has %d coupler faces against the cab "
                        "end's %d" % (n_blind, len(faces)))

    notes.append("coupler axis at donor y %.3f m (M %+.1f), %.0f mm below the "
                 "anticlimber, rooted %.1f units inboard (the underframe cap "
                 "is at %.1f); %.0f mm of clearance to the arm buffers"
                 % (L.FLOOR_Y + CPL.CENTRE_Y / L.Y_SCALE, CPL.CENTRE_Y,
                    (sy(L.ANTICLIMBER_Y_M[0]) - top) / L.Y_SCALE * 1000,
                    COUPLER_ROOT_D, COUPLER_CAP_D,
                    (sx(L.BUFFER_CENTRE_X - L.BUFFER_RADIUS_M) - widest)
                    / L.X_SCALE * 1000))
    notes.append("coupled joint: knuckles interleave %.1f units and clear each "
                 "other by %.1f across the car; every other member stops %.1f "
                 "short of the plane"
                 % (j["overlap"], j["lateral"], j["plane_max"]))
    return problems


def check(model, tex, obj_path):
    problems, notes = [], []
    mats = used_materials(model, tex)
    problems += check_against_donor(notes)

    # 1. every group carries geometry, and winding points the right way.
    for name in model.order:
        if not model.groups[name].faces:
            problems.append("group %r is empty" % name)
    problems += check_winding(model, notes)
    problems += check_emitted_winding(obj_path, notes)
    problems += check_emitted_sign_side(obj_path, notes)
    problems += check_storm_door_recess(model, notes)
    problems += check_corner_carries_no_mask(model, tex, notes)
    problems += check_coupler(model, notes)
    notes.append("%d groups, %d faces"
                 % (len(model.order),
                    sum(len(g.faces) for g in model.groups.values())))

    # 2. the written OBJ: UV range, materials all declared.
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

    # 3. map_Kd targets resolve to files that exist, one dot, lowercase.
    with open(os.path.join(os.path.dirname(obj_path), MTL_NAME)) as fh:
        for line in fh:
            if line.startswith("map_Kd "):
                ident = line.split(None, 1)[1].strip()
                if ident.count(".") != 1:
                    problems.append("map_Kd %r must contain exactly one dot" % ident)
                if not ident.startswith("station_announcer:"):
                    problems.append("map_Kd %r is not namespaced" % ident)
                if ident != ident.lower():
                    problems.append("map_Kd %r is not lowercase" % ident)
                rel = ident.split(":", 1)[1]
                if not os.path.exists(os.path.join(OUR_NS, rel)):
                    problems.append("map_Kd %r has no file" % ident)

    # 4. group names <-> properties `names`, both directions, PER MODEL.
    bb_groups, elements = set(), {}
    if os.path.exists(DOORS_MODEL):
        with open(DOORS_MODEL) as fh:
            bb = json.load(fh)
        bb_groups = {o["name"] for o in bb.get("outliner", [])
                     if isinstance(o, dict)}
        elements = {e["uuid"]: e for e in bb.get("elements", [])}
        for outline in bb.get("outliner", []):
            kids = [c for c in outline.get("children", []) if isinstance(c, str)]
            if not kids:
                problems.append("bbmodel group %r has no elements"
                                % outline["name"])
            for uuid in kids:
                e = elements.get(uuid)
                if e is None:
                    problems.append("bbmodel group %r references a missing "
                                    "element" % outline["name"])
                    continue
                for axis in range(3):
                    span = e["to"][axis] - e["from"][axis]
                    if abs(span - round(span)) > 1e-9:
                        problems.append("bbmodel element %r has non-integer "
                                        "size on axis %d (%.3f)"
                                        % (e["name"], axis, span))
        notes.append("r62_doors.bbmodel: %d groups, %d elements, resolution "
                     "%dx%d" % (len(bb_groups), len(elements),
                                bb["resolution"]["width"],
                                bb["resolution"]["height"]))
    else:
        problems.append("r62_doors.bbmodel is missing — run tools/gen_r62_doors.py")

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
                    multipliers.setdefault(group, set()).add(
                        part["doorZMultiplier"])
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
        problems.append("the index binds no R62 models — run "
                        "tools/gen_r62_assets.py and tools/gen_vehicle_index.py")
    if multipliers.keys() - bb_groups:
        problems.append("door multipliers on non-bbmodel groups %s — MTR's "
                        ".obj path offsets animating doors twice"
                        % sorted(multipliers.keys() - bb_groups))

    # 5. the door leaves. Each leaf group rides BOTH position lists (which is
    #    what puts it on both sides, the rotational symmetry again), and its
    #    multiplier has to send it toward its OWN end of its own opening in
    #    both. Signs are re-derived from the shipped JSON, never assumed.
    problems += check_doors(door_defs, multipliers, notes)

    # 5b. THE POCKET. The leaf slides INTO the body, so it must be strictly
    #     inboard of the skin — one comparison, because there is no tumblehome
    #     — and it must cover the aperture and clear it when open.
    problems += check_pocket(elements, multipliers, notes)

    # 5c/5d. FLOOR, DOORWAY and DISPLAY parts have to live in a .bbmodel and
    #        nowhere else, and MTR synthesizes a fallback whenever floors AND
    #        doorways are both empty — so shipping one without the other
    #        silently disables the other's effect too.
    kinds, displays = {}, set()
    for model_path, prop in index_model_entries():
        with open(prop) as fh:
            for part in json.load(fh).get("parts", []):
                if part.get("type") in ("FLOOR", "DOORWAY"):
                    kinds.setdefault(part["type"], set()).add(
                        (os.path.basename(model_path), tuple(part["names"])))
                if part.get("type") == "DISPLAY":
                    displays.add((os.path.basename(model_path),
                                  tuple(part["names"])))
    for kind in ("FLOOR", "DOORWAY"):
        entries = kinds.get(kind, set())
        if not entries:
            problems.append("no %s part anywhere — MTR will fall back to "
                            "synthesizing them down the whole car" % kind)
        for source, names in sorted(entries):
            if not source.endswith(".bbmodel"):
                problems.append("%s part %s is in %s; MTR reads %s boxes only "
                                "from a .bbmodel" % (kind, list(names), source,
                                                     kind))
    if kinds.get("FLOOR") and kinds.get("DOORWAY"):
        notes.append("%d FLOOR + %d DOORWAY part(s), all .bbmodel — the "
                     "synthesized fallback is off"
                     % (len(kinds["FLOOR"]), len(kinds["DOORWAY"])))
    for source, names in sorted(displays):
        if not source.endswith(".bbmodel"):
            problems.append("DISPLAY part %s is in %s; MTR 4.0.5 cannot render "
                            "a display on an .obj at all" % (list(names), source))
    if displays:
        notes.append("%d DISPLAY part(s), all .bbmodel" % len(displays))

    # 5e. ⭐ THE DISPLAYS HAVE TO LAND ON THE HOUSINGS THIS FILE PAINTS, IN
    #     WORLD SPACE, AFTER PLACEMENT. Two models, two DIFFERENT flipped-z
    #     compositions, and one sign per side that is not symmetric in x — so
    #     this is the check that would actually catch the mistake, and nothing
    #     in game would report it: the text would simply hang on bare steel.
    problems += check_displays(model, elements, notes)

    # 5f. NO BAY MAY CARRY HALF A WINDOW, checked on the pixels that ship: an
    #     aperture is an alpha-0 hole, so "a hole touching the crop's first or
    #     last column" is exactly "a window the crop cut".
    seen_tex = set()
    for key, t in sorted(tex.items()):
        if not key.startswith("side_") or t.material not in mats:
            continue
        if t.name in seen_tex:
            continue
        seen_tex.add(t.name)
        w = len(t.rows[0])
        for col, edge in ((0, "left"), (w - 1, "right")):
            cut = sum(1 for row in t.rows if row[col][3] == 0)
            if cut:
                problems.append("%s: %d transparent pixel(s) on its %s edge — "
                                "a window is being cut by a bay boundary"
                                % (t.name, cut, edge))

    # 5g. ⭐ THE ELEVATION MAY ONLY SHADE VERTICALLY, IN THE FIELD.
    #
    #     The door bay is the one that REPEATS — three times per side — so it is
    #     the one where a field that varies along the car would show as a step
    #     at every opening. Checked in the belt band, which is the tallest
    #     stretch of pure field on this car and the one carrying the three
    #     polished rails a step would be most visible on.
    #
    #     The crop's FIRST AND LAST COLUMNS are excluded, and deliberately: the
    #     elevation draws a panel joint on every bay boundary, so a crop's own
    #     edges always carry one. That is bay-local content at a fixed position
    #     within the bay — it repeats identically in all three openings and is
    #     what a real butt joint between two sheets looks like — not field
    #     shading. The distinction is the whole rule: content may vary along the
    #     car, the field may not.
    band = range(int(side_row(A.BELT_UPPER_Y[1])),
                 int(side_row(A.BELT_THIRD_Y[0])))
    for key in ("side_door_a", "side_door_b"):
        t = tex.get(key)
        if t is None or t.material not in mats:
            continue
        cols = field_columns(t)
        if not cols:
            problems.append("%s has no field columns left to check" % key)
            continue
        bad = 0
        for y in band:
            row = t.rows[y]
            if any(row[c] != row[cols[0]] for c in cols):
                bad += 1
        if bad:
            problems.append("%s: %d row(s) of the belt band vary along the car "
                            "— the door bay repeats 3x and would step"
                            % (key, bad))
    #     ...and the seam between two adjacent bays must land on the same field
    #     tone, or the joint between two sheets reads as a tonal step instead.
    for a_key, b_key in (("side_door_a", "side_panel_a"),
                         ("side_panel_a", "side_end_a")):
        a, b = tex.get(a_key), tex.get(b_key)
        if a is None or b is None:
            continue
        ac, bc = field_columns(a), field_columns(b)
        if not ac or not bc:
            continue
        mismatched = sum(1 for y in band
                         if a.rows[y][ac[0]] != b.rows[y][bc[-1]])
        if mismatched:
            problems.append("%s and %s do not agree on the field tone at their "
                            "shared seam (%d row(s))"
                            % (a_key, b_key, mismatched))

    # 6. positions resolve, nothing is placed twice, the car is the right size.
    for label, (definition, properties) in sorted(VARIANTS.items()):
        placements, dupes = load_placements(definition, properties)
        for d in dupes:
            problems.append("%s: %r placed twice at z=%s flipped=%s"
                            % (label, d[0], d[2], d[3]))
        if not placements:
            problems.append("%s: no placements at all" % label)
            continue
        built = assemble(model, placements)
        zs = [v[2] for g in built.groups.values() for _m, vs in g.faces
              for v in vs]
        notes.append("%-7s %3d placements, z %+7.1f..%+7.1f (car is %d)"
                     % (label, len(placements), min(zs), max(zs), L.UNITS))
        if max(abs(min(zs)), abs(max(zs))) > L.UNITS / 2 + 2.0:
            problems.append("%s: geometry reaches %.1f, past the %d-unit car "
                            "end" % (label, max(abs(min(zs)), abs(max(zs))),
                                     L.UNITS))
        # ⭐ ROTATIONAL SYMMETRY, MEASURED. Exactly one side rollsign housing
        # per side of the car, at opposite ends — the single claim the whole
        # both-sides-in-one-group design rests on.
        signs = [g for g in built.groups.values()]
        boxes = []
        for g in signs:
            for material, vs in g.faces:
                if material != tex["sign"].material:
                    continue
                boxes.append((sum(v[0] for v in vs) / len(vs),
                              sum(v[2] for v in vs) / len(vs)))
        if len(boxes) != 2:
            problems.append("%s: %d side rollsign faces, want exactly 2 (one "
                            "per side)" % (label, len(boxes)))
        elif boxes[0][0] * boxes[1][0] > 0 or boxes[0][1] * boxes[1][1] > 0:
            problems.append("%s: the two side rollsigns are not diagonally "
                            "opposite (%s)" % (label, boxes))
        else:
            notes.append("%-7s rollsigns at x %+.1f z %+.1f and x %+.1f z %+.1f "
                         "— rotationally symmetric"
                         % (label, boxes[0][0], boxes[0][1],
                            boxes[1][0], boxes[1][1]))
    return problems, notes


def check_doors(door_defs, multipliers, notes):
    """The leaves: both lists, and a slide toward each leaf's own end.

    ⭐ THE R62's LEAF PAIR IS ROTATIONALLY SYMMETRIC, SO ONE GROUP SERVES BOTH
    SIDES. On the +x side the narrow leaf sits at +z of the meeting point; on
    the -x side it sits at -z. A `positionsFlipped` entry is a 180-degree turn,
    which supplies exactly that — so each leaf group appears in BOTH lists,
    where the M7's four leaves each appeared in only one. The multiplier's
    effective direction is sign(m) unflipped and -sign(m) flipped, and both have
    to point at the leaf's own end of its own opening.
    """
    problems = []
    for definition in sorted(os.listdir(DEFS)) if os.path.isdir(DEFS) else []:
        if not definition.startswith("r62"):
            continue
        with open(os.path.join(DEFS, definition)) as fh:
            by_name = {d["name"]: d
                       for d in json.load(fh)["positionDefinitions"]}
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
            got = multipliers[group]
            if len(got) > 1:
                problems.append("%s: leaf %r has conflicting multipliers %s"
                                % (definition, group, sorted(got)))
                continue
            m = list(got)[0]
            for key, flipped in (("positions", False),
                                 ("positionsFlipped", True)):
                if not d.get(key):
                    problems.append("%s: leaf %r has an empty %s — it would "
                                    "appear on one side of the car only"
                                    % (definition, group, key))
            # The leaf's own z within its bay decides which way it must go, and
            # a flip reverses BOTH the leaf's position and the slide, so the
            # requirement is the same in either list.
            offset = 1.0 if m > 0 else -1.0
            if abs(m) - 0.5 < L.LEAF_NARROW - 1e-9:
                problems.append("%s: leaf %r opens %.1f, less than the %d units "
                                "of leaf it has to clear"
                                % (definition, group, abs(m) - 0.5,
                                   L.LEAF_NARROW))
            notes.append("%-9s leaf %-12s m %+5.1f -> slides %s unflipped, %s "
                         "flipped" % (definition, group, m,
                                      "+z" if offset > 0 else "-z",
                                      "-z" if offset > 0 else "+z"))
    return problems


def check_pocket(elements, multipliers, notes):
    """The leaf covers the aperture, clears it when open, and stays inboard."""
    problems = []
    planes = [e for e in elements.values() if e["from"][0] == e["to"][0]
              and e.get("uv_offset") is not None]
    if not planes or not multipliers:
        return problems
    skin = L.sx(L.DONOR_HALF_X)
    widths = []
    for e in planes:
        x = abs(e["from"][0])
        if x >= skin:
            problems.append("leaf plane at x %.3f is not inboard of the %.3f "
                            "bodyside — an open leaf would poke through it"
                            % (x, skin))
        if x <= L.sx(L.LINING_MAX_X_M):
            problems.append("leaf plane at x %.3f crosses the corridor reserved "
                            "for the interior lining (%.3f)"
                            % (x, L.sx(L.LINING_MAX_X_M)))
        widths.append(abs(e["to"][2] - e["from"][2]))
    covered = max(widths) + min(widths)
    if covered < L.APERTURE_UNITS - 1e-6:
        problems.append("the two leaves cover %.1f of the %g-unit aperture"
                        % (covered, L.APERTURE_UNITS))
    # One entry per DISTINCT multiplier: every leaf is bound twice (an INTERIOR
    # plane and an EXTERIOR one carrying the same multiplier, r179's structure),
    # so counting parts instead of leaves would double the travel.
    travel = sum(abs(m) - 0.5
                 for m in {abs(m) for ms in multipliers.values() for m in ms})
    if travel < L.APERTURE_UNITS - 1e-6:
        problems.append("R179 travel totals %.1f, less than the %g-unit "
                        "aperture" % (travel, L.APERTURE_UNITS))
    notes.append("pocket: leaves %s units over a %g aperture, total travel "
                 "%.1f, %.2f units of clearance to the skin"
                 % ("+".join("%g" % w for w in sorted(set(widths))),
                    L.APERTURE_UNITS, travel,
                    skin - max(abs(e["from"][0]) for e in planes)))
    return problems


def check_displays(model, elements, notes):
    """⭐ Every DISPLAY element must land on the housing this file paints.

    Both are baked through their OWN placement transform — the .obj's and the
    .bbmodel's, which differ in the sign they give a flipped entry — and the
    display's centre is then required to lie inside a housing face's footprint
    in WORLD space. That is the only check that can catch a flipped-z sign
    error between two models, and MTR reports nothing at all when it happens.
    """
    problems = []
    if not elements or not os.path.exists(DEFS):
        return problems
    try:
        with open(os.path.join(DEFS, "r62.json")) as fh:
            defs = {d["name"]: d
                    for d in json.load(fh)["positionDefinitions"]}
    except OSError:
        return problems

    def world_boxes(group_name, predicate, definition):
        out = []
        d = defs.get(definition, {})
        group = model.groups.get(group_name)
        if group is None:
            return out
        for material, vs in group.faces:
            if not predicate(material, vs):
                continue
            for lst, flipped in (("positions", False),
                                 ("positionsFlipped", True)):
                for e in d.get(lst, []):
                    Z = float(e.get("z", 0.0))
                    pts = [obj_place(v[0], v[1], v[2], 0.0, Z, flipped)
                           for v in vs]
                    out.append((min(p[0] for p in pts), max(p[0] for p in pts),
                                min(p[1] for p in pts), max(p[1] for p in pts),
                                min(p[2] for p in pts), max(p[2] for p in pts)))
        return out

    sign_faces = world_boxes("panel_exterior",
                             lambda m, vs: m.endswith("_sign"), "panel")
    # The roundel's housing is painted INTO the end mask, so its footprint is
    # the layout rectangle rather than a distinguishable face — take it from
    # the same source gen_r62_doors places the elements from.
    rx0, rx1, ry0, ry1 = L.roundel_plate()
    roundel_faces = []
    for definition, lst, flipped in (("end1", "positions", False),
                                     ("end2", "positionsFlipped", True)):
        for e in defs.get(definition, {}).get(lst, []):
            Z = float(e.get("z", 0.0))
            zc = L.END_BAY.z(L.nose_z((L.ROUNDEL_X_M[0] + L.ROUNDEL_X_M[1]) / 2.0))
            pts = [obj_place(x, y, zc, 0.0, Z, flipped)
                   for x in (rx0, rx1) for y in (ry0, ry1)]
            roundel_faces.append(
                (min(p[0] for p in pts), max(p[0] for p in pts),
                 min(p[1] for p in pts), max(p[1] for p in pts),
                 min(p[2] for p in pts), max(p[2] for p in pts)))

    # ...and where the .bbmodel puts each display element.
    #
    # ⭐ THE ELEMENT'S OWN ROTATION HAS TO BE APPLIED FIRST, and the first cut
    # of this check did not — which is why it passed a shipped model whose side
    # rollsign bullet was 4.5 units INSIDE the car. A display plate is authored
    # square and yawed into place about its `origin`, and MTR's own transform is
    # world = origin + R.(v - origin) (`BlockbenchElement.setModelPart` hands
    # the anchor to a ModelPart whose pivot is -origin and whose rotation is
    # -ry; decoded in gen_r62_doors.side_display_element). A check on the
    # UNROTATED centre measures a point the game never draws anything at.
    targets = {"side_bullet_color": sign_faces,
               "side_bullet_number": sign_faces,
               "side_route_number": sign_faces,
               "side_destination": sign_faces,
               "front_route_color": roundel_faces,
               "front_route_number": roundel_faces}
    bb_defs = {"side_bullet_color": "bbPanel", "side_bullet_number": "bbPanel",
               "side_route_number": "bbPanel", "side_destination": "bbPanel",
               "front_route_color": ("bbEnd1", "bbEnd2"),
               "front_route_number": ("bbEnd1", "bbEnd2")}
    checked = 0
    for e in elements.values():
        name = e["name"]
        if name not in targets:
            continue
        want = targets[name]
        names = bb_defs[name]
        names = (names,) if isinstance(names, str) else names
        cx = (e["from"][0] + e["to"][0]) / 2.0
        cy = (e["from"][1] + e["to"][1]) / 2.0
        cz = (e["from"][2] + e["to"][2]) / 2.0
        rotation = e.get("rotation") or [0.0, 0.0, 0.0]
        if rotation[1]:
            ox, _oy, oz = (e.get("origin") or [0.0, 0.0, 0.0])
            theta = math.radians(rotation[1])
            dx, dz = cx - ox, cz - oz
            cx = ox + dx * math.cos(theta) + dz * math.sin(theta)
            cz = oz - dx * math.sin(theta) + dz * math.cos(theta)
        for dname in names:
            d = defs.get(dname, {})
            for lst, flipped in (("positions", False),
                                 ("positionsFlipped", True)):
                for entry in d.get(lst, []):
                    Z = float(entry.get("z", 0.0))
                    wx, wy, wz = bb_place(cx, cy, cz, 0.0, Z, flipped)
                    hit = any(bx0 - 1.5 <= wx <= bx1 + 1.5
                              and by0 - 1.0 <= wy <= by1 + 1.0
                              and bz0 - 1.5 <= wz <= bz1 + 1.5
                              for (bx0, bx1, by0, by1, bz0, bz1) in want)
                    checked += 1
                    if not hit:
                        problems.append(
                            "display %r placed by %s/%s lands at world "
                            "(%.1f, %.1f, %.1f), not on any housing this "
                            "converter paints — the text would hang on bare "
                            "stainless" % (name, dname, lst, wx, wy, wz))
    if checked and not problems:
        notes.append("%d display placements all land on their painted housing, "
                     "through both models' own flipped-z composition" % checked)
    return problems


# ==================================================================== main

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true",
                    help="cross-check the written model against the properties")
    ap.add_argument("--assemble", metavar="DIR",
                    help="bake every bay at its definition position into "
                         "whole-car preview OBJs in DIR, for render_obj.py")
    args = ap.parse_args()

    model, tex = build_model()
    mats = used_materials(model, tex)
    obj_path = os.path.join(MODEL_DIR, OBJ_NAME)

    if args.assemble:
        os.makedirs(args.assemble, exist_ok=True)
        # The leaf preview needs the door atlas as an ordinary material. It is
        # added to the texture set and the model ONLY on this path, so the
        # shipped .obj never sees it — `--check` would otherwise report a group
        # no properties file binds, correctly.
        doors_png = os.path.join(TEX_DIR, "doors_box.png")
        if os.path.exists(doors_png):
            tex["doors_box"] = Tex("doors_box",
                                   pngtool.read_png(doors_png)[2])
            leaf_preview(model, tex)
        mats = used_materials(model, tex)
        write_textures(tex, set(mats), args.assemble)
        for label, (definition, properties) in sorted(VARIANTS.items()):
            placements, _ = load_placements(definition, properties)
            built = assemble(model, placements)
            # The leaves are already in world space — `assemble` only walks the
            # groups a definition names, so they are copied across verbatim.
            if "door_leaf_preview" in model.groups:
                built.group("door_leaf_preview").faces = list(
                    model.groups["door_leaf_preview"].faces)
            path = os.path.join(args.assemble, "r62_%s.obj" % label)
            nv, nf = write_obj(built, path, "r62_%s.mtl" % label, mats,
                               lambda t: t.name + ".png")
            print("%-24s %5d verts %5d faces" % (os.path.basename(path), nv, nf))
        return 0

    if not args.check:
        nv, nf = write_obj(model, obj_path, MTL_NAME, mats, lambda t: t.map_kd)
        written = write_textures(tex, set(mats))
        # Prune anything this converter no longer emits. Without it a texture
        # dropped during an art iteration stays in the jar forever, and the
        # first symptom is a shipped file nothing references. `doors_box.png`
        # is the one file in here owned by another generator.
        keep = set(written) | {"doors_box"}
        for name in sorted(os.listdir(TEX_DIR)):
            if name.endswith(".png") and name[:-4] not in keep:
                os.remove(os.path.join(TEX_DIR, name))
                print("removed orphaned texture %s" % name)
        print("r62.obj: %d groups, %d verts, %d faces"
              % (len(model.order), nv, nf))
        print("r62.mtl: %d materials" % len(mats))
        print("textures: %d -> %s" % (len(written),
                                      os.path.relpath(TEX_DIR, ROOT)))

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
    raise SystemExit(main())
