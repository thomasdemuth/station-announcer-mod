#!/usr/bin/env python3
"""The NYCT R62's bay layout and scale — ONE definition, shared by every tool.

    import r62_layout as L
    L.NORMAL.door.centres        # [-79.0, 0.0, 79.0]
    L.PANEL_BAY.z(2.403)         # donor metres -> M units, inside that bay

RUN IT to print the layout:  python3 tools/r62_layout.py

This is `tools/m7_layout.py`'s architecture, cloned deliberately: everything
dimensional is DERIVED from the donor's own measurements rather than typed
twice, `_verify()` runs on every import, and the three tools that have to agree
about a bay (`convert_openbve_r62.py`, `gen_r62_doors.py`, `gen_r62_assets.py`)
all import it instead of carrying copies.

UNITS AND FRAMES (the full story is in M7_CONVERSION_NOTES.md — read it)
-----------------------------------------------------------------------
donor   openBVE metres. x across, y up from the RAIL, z along the car.
M       "bbmodel frame": 1 unit = 1/16 block, y = 0 at the car FLOOR. Every
        number here that is not explicitly donor metres is M units, because M
        units are what `properties/definition/*.json` carries.
OBJ     obj = (-Mx, +My, -Mz) / 16.  One OBJ unit is one BLOCK.

⭐ THE ONE STRUCTURAL DIFFERENCE FROM THE M7: ROTATIONAL SYMMETRY
------------------------------------------------------------------
The R62 is 180-degree ROTATIONALLY symmetric about its centre, not mirror
symmetric. The side rollsign sits in the +z panel of the +x side and in the -z
panel of the -x side; the half-cab is likewise diagonally opposite end to end.
So the M7's "model x > 0 only and let positionsFlipped supply the other side"
rule DOES NOT APPLY. Every bay group here models BOTH SIDES AT ONCE, and a
`positionsFlipped` entry supplies the far END of the car, not the far side.

Consequences that fall straight out of that, and that the checks below guard:

  * A bay that is NOT 180-symmetric in its own content (the panel: rollsign on
    one side, window on the other; the end: cab on one side, roundel on the
    other) is placed ONCE unflipped and ONCE flipped, giving two per car.
  * A bay that IS symmetric (the door) is placed by `positions` alone, at all
    three openings.
  * Nothing is ever placed by a symmetric BOTH-lists definition, because that
    would draw every bay twice, once inside itself.

THE LAYOUT (16 blocks, user decision — no Mini variant)
--------------------------------------------------------
    end(38.5) | door(21) | panel(58) | door(21) | panel(58) | door(21) | end(38.5)
        = 256 units = 16 blocks
    ends +-108.75   doors 0, +-79   panels +-39.5

Seven bays against the M7's eleven, and three doors per side against two: this
is a 51'4" IRT car, not an 85' commuter coach.

The donor's own bay boundaries are +-7.780 / +-5.441 / +-4.172 / +-0.634, which
is where every one of those widths comes from.
"""

import math

# ===================================================================== scale
#
# ⭐ THE CROSS-SECTION IS DERIVED FROM MTR'S OWN R179, EXACTLY AS THE M7'S WAS.
# MTR draws rolling stock about 30% oversized on purpose, so a custom train only
# looks right if it is scaled to MTR's corpus rather than to metres. r179's BODY
# is x +-22.19 units = +-1.387 blocks for a 10'0" car; scaling that by the R62's
# 8'9" gives this car's half-width, and the donor's own 1.3109 m then fixes the
# metres-to-units factor.
#
# That lands at 14.813 units/m against the M7's 14.838 — 0.2% apart — so the two
# custom trains are at ONE cross-car scale and read correctly parked side by
# side. That agreement is a consequence, not a target: both were derived from
# r179 independently.
R179_HALF_BLOCKS = 1.387                 # MTR's r179 body half-width
R179_WIDTH_FT = 10.0                     # ...of a 10'0" B-division car
R62_WIDTH_FT = 8.75                      # 8'9" — an IRT A-division car
DONOR_HALF_X = 1.3109                    # donor metres: the bodyside plane

HALF_X_UNITS = R179_HALF_BLOCKS * (R62_WIDTH_FT / R179_WIDTH_FT) * 16.0
X_SCALE = HALF_X_UNITS / DONOR_HALF_X    # M units per donor metre, across

# ⭐ ONE VERTICAL SCALE, ABOVE AND BELOW THE FLOOR. The M7 needed a second,
# compressed factor below its floor because its donor carried 1.195 m of
# underframe that had to fit inside the 0.95 blocks between the floor and the
# rail. The R62's underframe bottom is only 0.529 m below its floor, which at
# the natural factor is 0.53 blocks — comfortably clear of the rail — so there
# is nothing to compress and no second scale to keep in step.
Y_SCALE = 1.0255 * 16.0                  # M units per donor metre (r179's)
FLOOR_Y = 1.110                          # donor y of the floor and door sill
LOWEST_Y = 0.581                         # donor y of the underframe's bottom
RAIL_UNITS = -16.0                       # M y of the rail (modelYOffset 1)


def sx(x_m):
    """donor x (metres) -> M units."""
    return x_m * X_SCALE


def sy(y_m):
    """donor y (metres above rail) -> M units, floor at 0, up positive."""
    return (y_m - FLOOR_Y) * Y_SCALE


# ⭐⭐ THE HANDEDNESS FLIP — WHY THE WHOLE CAR CAME OUT MIRRORED
# ---------------------------------------------------------------
# openBVE inherits Blitz3D's LEFT-handed frame: +x is to the RIGHT of a driver
# facing +z. Minecraft is RIGHT-handed (x east, y up, z south), where +x is to
# the LEFT of someone facing +z. Mapping the donor's axes across with matching
# signs is therefore a MIRROR, not a rotation — and `quad()` computes each
# face's winding from the direction it is told to face, so the mirror produced a
# perfectly wound, perfectly plausible, perfectly BACKWARDS car.
#
# On the M7 that was invisible: its shell is mirror-symmetric side to side. The
# R62 is not. Its half-cab and its side rollsign are on ONE side each, so the
# error shows as the thing the user reported from the platform: the windshield
# on the railfan's RIGHT and the route roundel on the LEFT, when the prototype
# — and the donor's own front photograph — have them the other way round.
#
# ⭐ THE FLIP IS APPLIED ONCE, AT EMIT, AND NOWHERE ELSE. Every authoring
# routine in every tool reasons in donor terms and keeps doing so; `sx` keeps
# the donor's sign. What changes is the last step out of the M frame:
#
#     convert_openbve_r62.to_obj_vertex     x no longer negated  (+ reversed
#     convert_r62_interior.to_obj_vertex     face order, because a mirror
#                                            reverses winding)
#     gen_r62_doors.mirror_element          x negated, from/to swapped,
#                                            element yaw negated
#
# Doing it in `sx` instead would mean auditing every `outward` argument in three
# converters; doing it in the definitions is impossible (positions cannot
# mirror). Doing it at emit is one transform per model format, and `--check`
# bakes both formats through their own transform and measures where the
# displays land, which is the guard that this stayed consistent.
#
# Turn it off and the car reverts to the 2026-07-28 build exactly.
MIRROR_X = True


def emit_x(units):
    """M x -> world x. THE handedness flip, and the only place it happens.

    ⭐ THE SENSE OF THIS FUNCTION IS LOAD-BEARING AND IT WAS ONCE INVERTED.
    ON (mirror) must NOT negate x: paired with the z negation every emitter
    already does, that is the determinant -1 map, and `write_obj` reverses each
    face to put the winding back. OFF must negate x, giving the determinant +1
    map of the 2026-07-28 build, which needs no reversal.

    Written the other way round — `-units if MIRROR_X` — ON silently became
    "the old geometry, plus a face reversal it does not need", i.e. a car that
    is not mirrored at all and is INSIDE OUT, and OFF became "mirrored geometry
    with no reversal", also inside out. Both settings were broken and nothing
    reported it, because `check_winding` runs on the M-space model and never
    sees this function. `check_emitted_winding` is the guard that now does.
    """
    return units if MIRROR_X else -units


# ========================================================== donor texture map
#
# Ext.png is a 3277 x 512 elevation of ONE car side, and BOTH sides use it —
# the -x side reads it mirrored. Fitted off the donor's own UVs in builder 0,
# to zero error:
#
#     +x side   u = -0.069888 z + 0.500001   =>  z(u) = 7.1543 - 14.3086 u
#     -x side   u = +0.069888 z + 0.499999   =>  u(-x, z) = 1 - u(+x, z)
#
# ⭐ THAT MIRRORING IS THE ROTATIONAL SYMMETRY, EXPRESSED IN TEXTURE SPACE.
# The image column that carries the rollsign is at u 0.286..0.381, which on the
# +x side is donor z +1.71..+3.06 and on the -x side is donor z -3.06..-1.71 —
# precisely where each side's rollsign is. So ONE elevation serves the whole car
# and no content has to be drawn twice; a bay simply takes TWO crops of it, one
# per side, at u(z) and u(-z).
Z_AT_U0 = 7.1543
Z_PER_U = -14.3086

# The elevation's vertical span, from the same fit (v is stored NEGATIVE in this
# donor, v_eff = v + 1; these are v_eff, image convention, v=0 is the TOP row).
SIDE_TEX_TOP_Y = 3.1074                  # donor y at v = 0 — the roof eaves
SIDE_TEX_BOT_Y = 0.9477                  # donor y at v = 1 — under the sill


def u_of_z(z_m, side=+1):
    """donor z -> u on the side elevation. `side` is +1 for +x, -1 for -x."""
    u = (z_m - Z_AT_U0) / Z_PER_U
    return u if side > 0 else 1.0 - u


def z_of_u(u, side=+1):
    return Z_AT_U0 + Z_PER_U * (u if side > 0 else 1.0 - u)


def side_v(y_m):
    """donor y -> v on the side elevation (image convention, 0 = top)."""
    return (SIDE_TEX_TOP_Y - y_m) / (SIDE_TEX_TOP_Y - SIDE_TEX_BOT_Y)


def side_y(v):
    return SIDE_TEX_TOP_Y - v * (SIDE_TEX_TOP_Y - SIDE_TEX_BOT_Y)


# ======================================================== the front / end map
#
# Front.png is 706 x 666 and covers the whole car end including the roof
# fillet. Fitted off builders 24/25:
#
#     -z end (builder 24)   u = +0.381430 x + 0.5
#     +z end (builder 25)   u = -0.381430 x + 0.5      (mirrored)
#     both                  v_eff = 1.440785 - 0.397682 y
#
# We author the +z end, so `mask_u` is builder 25's.
MASK_X_SPAN = 1.0 / 0.381430              # metres of car across u 0..1 (2.6216)
MASK_Y_TOP = 1.440785 / 0.397682          # donor y at v = 0  (3.6229)
MASK_Y_BOT = (1.440785 - 1.0) / 0.397682  # donor y at v = 1  (1.1084)


def mask_u(x_m):
    """donor x -> u on the end texture, authored for the +z end."""
    return 0.5 - x_m / MASK_X_SPAN


def mask_x_of_u(u):
    return (0.5 - u) * MASK_X_SPAN


def mask_v(y_m):
    return (MASK_Y_TOP - y_m) / (MASK_Y_TOP - MASK_Y_BOT)


def mask_y_of_v(v):
    return MASK_Y_TOP - v * (MASK_Y_TOP - MASK_Y_BOT)


# =========================================================== bay lengths
#
# The donor's own boundaries. Everything below is derived from these and from
# the 16-block car length; nothing is typed twice.
DONOR_BOUNDS = (7.780, 5.441, 4.172, 0.634)      # outward from the car centre
DONOR_END_M = DONOR_BOUNDS[0] - DONOR_BOUNDS[1]          # 2.339
DONOR_DOOR_M = DONOR_BOUNDS[1] - DONOR_BOUNDS[2]         # 1.269
DONOR_PANEL_M = DONOR_BOUNDS[2] - DONOR_BOUNDS[3]        # 3.538
DONOR_HALF_LEN = DONOR_BOUNDS[0]                         # 7.780
DONOR_LENGTH_M = 2.0 * DONOR_HALF_LEN                    # 15.560

BLOCKS = 16                              # user decision. NO mini variant.
UNITS = BLOCKS * 16                      # 256
Z_SCALE = UNITS / DONOR_LENGTH_M         # ~16.452 units/m

# Bay widths in M units. The door and the panel are their own donor slice
# rounded to a whole unit; the two ends then split what is left, evenly,
# because an asymmetric car end would move the coupler faces apart.
#
# ⭐ THE END COMES OUT AT 38.5, NOT AN INTEGER, AND THAT IS FINE. Position
# entries are doubles and an .obj bay has no integer constraint at all. The
# only integers MTR insists on are .bbmodel ELEMENT SIZES (it takes
# `Math.round(to - from)` as the size, and as the text canvas for a display),
# and every element in gen_r62_doors is sized off DOOR_UNITS / LEAF_* below,
# which are integers by construction.
DOOR_UNITS = round(DONOR_DOOR_M * Z_SCALE)               # 21
PANEL_UNITS = round(DONOR_PANEL_M * Z_SCALE)             # 58
END_UNITS = (UNITS - 3 * DOOR_UNITS - 2 * PANEL_UNITS) / 2.0     # 38.5

# ⭐ THE DOOR BAY IS EXACTLY THE APERTURE. The donor's bay boundaries ARE the
# door opening's edges (+-0.634 for the centre door), so unlike the M7 — whose
# door bay carried a flanking panel each side — this bay has no jamb in it at
# all. The jambs belong to the panels and ends either side. That is why the
# aperture below is simply the bay, and why the leaves tile it exactly.
DONOR_APERTURE_M = DONOR_DOOR_M
APERTURE_UNITS = DOOR_UNITS
DOOR_HEAD_Y = 3.056                      # donor y of the opening head
DOOR_SILL_Y = FLOOR_Y                    # donor y of the sill (= our y 0)
DOOR_CORNER_R_M = 0.166                  # the opening's corner radius
REVEAL_DEPTH_M = 0.046                   # how far the jamb steps inboard

# ⭐ UNEQUAL LEAVES, AND WHICH IS WHICH. The donor's two leaves are 0.617 m and
# 0.650 m and each retracts ~99% of its OWN width — the r179 ground truth
# exactly. Split the 21-unit aperture in that ratio and round, and they come out
# 10 and 11, which tile it with nothing left over.
#
# The donor's four door objects say which leaf sits where:
#     RightDoor1 (+x side, +z leaves) travels +0.612  -> the NARROW leaf
#     RightDoor2 (+x side, -z leaves) travels -0.642  -> the WIDE leaf
#     LeftDoor1  (-x side, -z leaves) travels -0.612  -> the NARROW leaf
#     LeftDoor2  (-x side, +z leaves) travels +0.642  -> the WIDE leaf
# i.e. narrow-at-+z on +x and narrow-at--z on -x: the rotational symmetry again,
# which is exactly why ONE leaf group serves both sides through the two lists.
DONOR_LEAF_NARROW_M = 0.617
DONOR_LEAF_WIDE_M = 0.650
LEAF_NARROW = int(round(APERTURE_UNITS * DONOR_LEAF_NARROW_M
                        / (DONOR_LEAF_NARROW_M + DONOR_LEAF_WIDE_M)))   # 10
LEAF_WIDE = APERTURE_UNITS - LEAF_NARROW                                # 11
# Where the two leaves meet, in bay-local M units. Falls out of the widths.
LEAF_MEET_Z = APERTURE_UNITS / 2.0 - LEAF_NARROW                        # +0.5

# R179's curve opens by |multiplier| - 0.5, so each leaf's multiplier is its own
# width plus a half, and the pair then clears the aperture exactly.
DOOR_MULTIPLIER_NARROW = LEAF_NARROW + 0.5                              # 10.5
DOOR_MULTIPLIER_WIDE = LEAF_WIDE + 0.5                                  # 11.5

# The leaf's own body, from the donor: 25 mm thick, its outer face 46 mm inboard
# of the skin, so it slides INTO the body like the M7's. Unlike the M7 there is
# NO TUMBLEHOME — the bodyside is a flat plane — so the pocket corridor is the
# same at every height and one flat plane per stage is enough. No band stack.
LEAF_X_EXTERIOR = DONOR_HALF_X - REVEAL_DEPTH_M          # 1.2649
LEAF_X_INTERIOR = LEAF_X_EXTERIOR - 0.025                # 1.2399
LEAF_HEIGHT_UNITS = int(round((DOOR_HEAD_Y - DOOR_SILL_Y) * Y_SCALE))   # 32

# ⭐ THE CORRIDOR THE INTERIOR PASS MAY NOT ENTER. An open leaf lies between the
# skin and wherever the lining ends up, and the interior is a LATER pass by
# another agent. Recording the number here, where that agent will read it, is
# the whole mitigation: `convert_openbve_r62.py --check` asserts the leaf is
# inboard of the skin, but nothing in this repo can yet assert it is outboard of
# a lining that does not exist.
LINING_MAX_X_M = LEAF_X_INTERIOR - 0.010                 # 1.2299


# ============================================================== bay centres

class BaySpan:
    """Where one kind of bay repeats along a car: its width and its centres."""

    def __init__(self, name, units, centres):
        self.name = name
        self.units = units
        self.centres = list(centres)

    def span(self, centre):
        return (centre - self.units / 2.0, centre + self.units / 2.0)

    def __repr__(self):
        return "BaySpan(%r, %g, %s)" % (self.name, self.units, self.centres)


class CarLayout:
    """One car length's worth of bay placement, walked in from the car end."""

    def __init__(self, label, units, end_units, door_units, panel_units):
        self.label = label
        self.units = units
        self.blocks = int(units // 16)
        half = units / 2.0

        end_centre = half - end_units / 2.0
        outer_door = half - end_units - door_units / 2.0
        panel_centre = half - end_units - door_units - panel_units / 2.0

        self.end = BaySpan("end", end_units, [-end_centre, end_centre])
        self.door = BaySpan("door", door_units,
                            [-outer_door, 0.0, outer_door])
        self.panel = BaySpan("panel", panel_units,
                             [-panel_centre, panel_centre])

    def __repr__(self):
        return ("CarLayout(%s: %d units / %d blocks, end %g, doors %s, "
                "panels %s)" % (self.label, self.units, self.blocks,
                                self.end.units, self.door.centres,
                                self.panel.centres))


# ------------------------------------------------------------- donor -> bay

class Bay:
    """A donor z-range mapped linearly onto a span of M units.

    Every bay here is built with za < zb AND ma < mb, so the map's determinant
    is positive and NO bay ever needs its winding reversed. That is a deliberate
    simplification over the M7, whose end bays ran backwards.
    """

    def __init__(self, name, units, z_donor_a, z_donor_b, m_a, m_b):
        self.name = name
        self.units = units
        self.za, self.zb = z_donor_a, z_donor_b
        self.ma, self.mb = m_a, m_b
        self.k = (m_b - m_a) / (z_donor_b - z_donor_a)
        self.flip_winding = self.k < 0

    def z(self, z_m):
        """donor z (metres) -> M units, local to the bay (centred on 0)."""
        return self.ma + (z_m - self.za) * self.k

    def donor_z(self, m_z):
        return self.za + (m_z - self.ma) / self.k

    def contains(self, z_m, slack=1e-6):
        lo, hi = min(self.za, self.zb), max(self.za, self.zb)
        return lo - slack <= z_m <= hi + slack

    @property
    def units_per_m(self):
        return abs(self.k)

    def __repr__(self):
        return "Bay(%r, %g u, donor %+.4f..%+.4f)" % (self.name, self.units,
                                                      self.za, self.zb)


def _bay(name, units, za, zb):
    return Bay(name, units, za, zb, -units / 2.0, units / 2.0)


# The three bays, each authored centred on local z = 0 and each mapping its own
# donor slice. The PANEL and the END are the +z ones — the ones that carry the
# rollsign and the +x cab respectively — because those are the instances placed
# unflipped, and authoring what you place is what keeps the signs readable.
PANEL_BAY = _bay("panel", PANEL_UNITS, DONOR_BOUNDS[3], DONOR_BOUNDS[2])
DOOR_BAY = _bay("door", DOOR_UNITS, -DONOR_BOUNDS[3], DONOR_BOUNDS[3])
END_BAY = _bay("end", END_UNITS, DONOR_BOUNDS[1], DONOR_BOUNDS[0])

NORMAL = CarLayout("normal", UNITS, END_UNITS, DOOR_UNITS, PANEL_UNITS)

# The +z placements, which is what the definitions carry: the -z copies come
# from `positionsFlipped` at the SAME z (an .obj flipped entry lands at MINUS
# z — see the FLIPPED-Z TRAP in M7_CONVERSION_NOTES.md).
END_Z = NORMAL.end.centres[1]            # +108.75
PANEL_Z = NORMAL.panel.centres[1]        # +39.5


# ======================================================= the side rollsign
#
# ⭐ THE HEADLINE FEATURE, AND THE REASON THE PANEL BAY IS NOT MIRRORED. The
# R62's side sign is a black box screwed over the TOP of one passenger window,
# with three lit fields behind glass: a route bullet at the car-centre end and
# two lines of destination text beside it. The donor models it as builder 20
# (the keyed cover) plus three scrolling curtain quads, and its measurements are
# what every number here is.
#
# It exists ONCE PER SIDE PER CAR: on the +x side in the +z panel, on the -x
# side in the -z panel. One drawn housing in the panel bay's +x wall therefore
# produces both, because the flipped placement of that bay turns it round.
SIGN_BOX_Z_M = (1.680, 3.070)            # donor z, +x side (builder 20)
SIGN_BOX_Y_M = (2.561, 2.942)            # donor y, bottom/top
# ⭐ ONE FULL M UNIT, NOT A THIRD OF ONE. The M7's depth pass surveyed all 16 of
# MTR's own vehicle models and found the minimum real element thickness is
# EXACTLY 1 unit — nothing in the corpus lives between 0.3 and 1. A 0.3-unit box
# rendered as a painted rectangle with a hairline edge; at 1 unit the housing
# reads as a box screwed onto the side, which is what it is.
SIGN_STANDOFF = 1.00                     # M units the box stands off the skin
SIGN_TEXT_STANDOFF = 1.50                # ...and the display text off the skin

# The three fields, from the cover texture's own keyed windows (512 x 256 px,
# mapped over the box). Bullet toward the CAR CENTRE, which is -z on the +x
# side, matching the donor's own curtain quads.
SIGN_BULLET_Z_M = (1.912, 2.158)
SIGN_BULLET_Y_M = (2.619, 2.875)
SIGN_LINE_Z_M = (2.185, 2.865)
SIGN_UPPER_Y_M = (2.743, 2.876)
SIGN_LOWER_Y_M = (2.609, 2.742)


def _element_span(z_m, y_m, bay):
    """A display plate's (z_lo, z_hi, y_lo, y_hi) in M units, whole-pixel.

    MTR takes `Math.round(to - from)` as BOTH the element size and the text
    canvas, so a fractional size silently rounds the layout out from under the
    art. Every display element in this model is therefore snapped to whole M
    units here, about the field's own centre, and the ART is drawn from the
    snapped result rather than from the donor box — that way the housing the
    player sees and the canvas MTR lays text into are the same rectangle.
    """
    z0, z1 = bay.z(z_m[0]), bay.z(z_m[1])
    y0, y1 = sy(y_m[0]), sy(y_m[1])
    cz, cy = (z0 + z1) / 2.0, (y0 + y1) / 2.0
    dz = max(1, int(round(z1 - z0)))
    dy = max(1, int(round(y1 - y0)))
    return (cz - dz / 2.0, cz + dz / 2.0, cy - dy / 2.0, cy + dy / 2.0)


def sign_bullet():
    return _element_span(SIGN_BULLET_Z_M, SIGN_BULLET_Y_M, PANEL_BAY)


def sign_upper():
    return _element_span(SIGN_LINE_Z_M, SIGN_UPPER_Y_M, PANEL_BAY)


def sign_lower():
    return _element_span(SIGN_LINE_Z_M, SIGN_LOWER_Y_M, PANEL_BAY)


# The passenger window the sign box is bolted over. Its glass stops where the
# housing starts, so what is left is a letterbox — which is exactly what the
# prototype looks like and what keeps the aperture from being drawn behind an
# opaque box.
ROLLSIGN_WINDOW_Z_M = (1.709, 3.059)     # donor z, +x side
SALOON_WINDOW_Z_M = (1.770, 3.128)       # donor z, -x side (the plain window)
WINDOW_Y_M = (2.116, 2.926)              # donor y, both
WINDOW_CORNER_R_M = 0.12


def rollsign_glass_top_m():
    """donor y where the +x panel's glass has to stop: under the sign box."""
    return SIGN_BOX_Y_M[0] - 0.006


# ======================================================= the interior shell
#
# Only what MORE THAN ONE tool needs lives here. The lining's own profiles,
# the seat section and the ceiling sweep are `convert_r62_interior.py`'s and
# stay there; what is below is shared with `gen_r62_doors.py`, which has to
# stand three DISPLAY plates on a housing that the interior converter paints.
# Same arrangement, and the same reason, as the side rollsign above.
#
# All four numbers are the donor's own (InteriorB.b3d):
#   lining plane            |x| 1.193      builders 2/14/15/16/25/26/44..49
#   wall band bottom/top    y 1.979/3.086  ...the same builders' y range
#   ceiling crown           y 3.323        builder 23 (Ceiling.png)
#   ad / light rack band    y 3.086..3.323 builder 43 (CeilingAdsLight.png),
#                                          |x| 0.843 (crown) .. 1.193 (wall)
INT_LINING_X_M = 1.193                   # the saloon lining, both sides
INT_WALL_TOP_Y = 3.086                   # where the wall stops and the rack starts
INT_WALL_BAND_Y = 1.979                  # the joint above the seat backs
INT_CROWN_Y = 3.323                      # the ceiling crown
INT_CROWN_HALF_X = 0.843                 # ...and how far out it stays flat

# ⭐ THE LINING MUST STAY INBOARD OF THE DOOR POCKET. `LINING_MAX_X_M` (§4) is
# the corridor the exterior pass reserved for an open leaf, and the donor's own
# lining is 37 mm inboard of it — so the R62 gets a real lining for free where
# the M7 had to fight for one. Nothing in the interior may cross it, which is
# also why there is no window reveal: 37 mm is half an M unit.
assert INT_LINING_X_M < LINING_MAX_X_M


def mx(units):
    """M units across the car -> donor metres. The inverse of `sx`."""
    return units / X_SCALE


def my(units):
    """M units above the floor -> donor metres. The inverse of `sy`."""
    return FLOOR_Y + units / Y_SCALE


# The ad / strip-map band: a vertical face standing ONE M unit inboard of the
# lining, in the bottom of the donor's rack band, THREE M units tall. Whole
# units because that is MTR's own minimum relief thickness (the M7's survey of
# all 16 of its vehicle models) and because three is what the exterior
# rollsign's two text lines already proved readable at this scale.
INT_BAND_X_M = INT_LINING_X_M - mx(1.0)
INT_BAND_UNITS = 3.0
INT_BAND_Y_M = (INT_WALL_TOP_Y, my(sy(INT_WALL_TOP_Y) + INT_BAND_UNITS))

# ⭐ THE STRIP MAP. The R62's line map lives in that band, and it is a HYBRID
# (user decision): the housing and the station ticks are PAINTED — generic,
# with no real station names — and two live MTR displays are inset into it, a
# route bullet at the car-centre end and a NEXT_STATION strip beside it.
#
# It exists on BOTH sides of the car, so `gen_r62_doors` authors two element
# sets (one per side) and each rides `bbPanel`'s two lists: four maps per car.
# The exterior rollsign gets away with one set because it IS one per side.
#
# Everything here is PANEL-BAY-LOCAL M units, not donor metres: the board is
# our design, not a donor measurement, and the bay is the frame both tools
# place it in.
MAP_UNITS = 44.0                         # the board's length, inside a 58 bay
MAP_STANDOFF = 1.00                      # M units the board stands off the band
MAP_TEXT_STANDOFF = 1.50                 # ...and the display text off the band
MAP_BULLET_UNITS = 3.0
MAP_TEXT_UNITS = 18.0
MAP_GAP = 1.0                            # painted bezel between the two fields


def map_board():
    """The strip map's painted housing: (z0, z1, y0, y1), panel-bay local."""
    y0, y1 = sy(INT_BAND_Y_M[0]), sy(INT_BAND_Y_M[1])
    return (-MAP_UNITS / 2.0, MAP_UNITS / 2.0, y0, y1)


def map_bullet():
    """The route bullet, at the board's CAR-CENTRE end (bay-local -z).

    Same convention as the side rollsign, and for the same reason: the reading
    order of a New York strip map starts at the bullet.
    """
    z0, z1, y0, y1 = map_board()
    return (z0 + MAP_GAP, z0 + MAP_GAP + MAP_BULLET_UNITS, y0, y1)


def map_text():
    """The NEXT_STATION strip, immediately beside the bullet."""
    z0, _z1, y0, y1 = map_board()
    a = z0 + 2 * MAP_GAP + MAP_BULLET_UNITS
    return (a, a + MAP_TEXT_UNITS, y0, y1)


def map_ticks():
    """What is left of the board for the PAINTED station-tick map."""
    _z0, z1, y0, y1 = map_board()
    return (map_text()[1] + MAP_GAP, z1 - MAP_GAP, y0, y1)


# ============================================================ the cab front
#
# ⭐ THE NOSE IS A REAL 3D BULLNOSE AND IT HAS TO BE SIMPLIFIED HARD. The donor
# models it with 252 faces over 79 distinct z planes. What that geometry
# actually IS, once measured, is much simpler than its face count:
#
#   * BELOW THE EAVES (y <= 3.107) z depends ONLY on |x| — a ruled bullnose,
#     0.381 m deep on the centreline, tapering back to the bodyside at the
#     corner. So a quad spanning [x0,x1] at CONSTANT y bounds is EXACTLY planar,
#     and a handful of them reproduce the plan curve with no faceting risk at
#     all. That is the M7's roof-cap lesson applied before it can bite: the
#     thing that read as a "facetted wedge" there was a non-planar fan.
#   * ABOVE THE EAVES the roof rolls forward over the nose. That surface is
#     doubly curved and is the one piece that cannot be planar, so it is built
#     the M7's way instead: as a FLAT CAP in a single z plane, closing the roof
#     dome's front. One plane, one normal, cannot facet.
#   * THE CENTRE IS A HOLE. There are no mask vertices between x -0.4168 and
#     +0.4168: that span is the storm-door recess, and the door itself sits
#     0.248 m back at z 7.277. So the nose has a real opening with real returns,
#     which is where most of the end's depth comes from.
#
# The knot list is the donor's OWN vertex ring, thinned to the points that keep
# the chord error under one M unit (about 60 mm at this scale). Dropping the two
# points at |x| 1.193 and 1.210 was tried and put the chord 13 mm inside a
# convex corner — visible as a flat spot where the side turns onto the nose — so
# they are back. `convert_openbve_r62.check_against_donor` measures the residual
# against the donor on every run rather than trusting this list.
NOSE_PLAN = [                            # (donor |x|, donor z) — measured
    (0.4168, 7.5246),
    (0.7122, 7.4920),
    (0.9707, 7.4342),
    (1.1566, 7.3752),
    (1.1930, 7.3558),
    (1.2098, 7.3370),
    (1.2280, 7.3110),
    (1.2650, 7.2580),
    (DONOR_HALF_X, 7.1543),
]
NOSE_EAVE_Y = 3.1074                     # donor y where the roof takes over
NOSE_SILL_Y = FLOOR_Y                    # donor y of the bottom of the mask
ROOF_FRONT_Z = 7.1543                    # donor z the roof shell stops at
ROOF_CROWN_Y = 3.6213

# ⭐ THE BROW — WHY THE FIRST CUT GREW A BEAK (user-reported, 2026-07-29).
# The first cut stopped the flat front face at the EAVES (3.1074) and ran a
# single straight chord from there to the roof's leading edge. Two things went
# wrong with that: the chord cuts across a surface the prototype rolls, and the
# meeting of a vertical face and a 36-degree chord is a hard CREASE whose apex
# sits 0.37 m forward at the centreline and pulls back to nothing at the corner
# — a chevron pointing at you. That is the "pointed centre prow".
#
# The donor says the flat face does not stop at the eaves at all: at the
# centreline it runs on to y 3.2007 and only then rolls. Measured (builder 25:
# for each |x|, the highest y still at that column's front-face z):
NOSE_BROW = [                            # (donor |x|, donor y the face ends)
    (0.0000, 3.2007),
    (0.3701, 3.1938),
    (0.5500, 3.1837),
    (0.7122, 3.1699),
    (0.8504, 3.1524),
    (0.9707, 3.1312),
    (1.0764, 3.1079),
    (1.1930, 3.0855),
    (1.2650, 3.0985),
    (DONOR_HALF_X, 3.1074),
]

# ...and the roll above it is a real curve, not a chord. Normalised off the
# donor's own centreline fillet and checked against its |x| 0.712 column, which
# agrees to about 0.03 in both parameters — self-similar enough for ONE profile
# to serve every station.
#     s = (y - brow) / (roof_y - brow)      0 at the brow, 1 at the roof edge
#     t = (face_z - z) / (face_z - ROOF_FRONT_Z)
FILLET_PROFILE = [                       # (s, t)
    (0.00, 0.00),
    (0.30, 0.09),
    (0.55, 0.23),
    (0.78, 0.42),
    (0.93, 0.69),
    (1.00, 1.00),
]

# ⭐ WHERE THE END STOPS WEARING THE FRONT MASK. Outboard of this the nose is
# turning hard onto the bodyside, its facets are a couple of pixels wide seen
# head on, and `mask_u` there is a hair OUTSIDE [0, 1] — `mask_u(DONOR_HALF_X)`
# is -2.8e-5, because the mask's own uv fit puts its edge at 1.310824 and the
# bodyside plane is at 1.3109. `wrap_face` then cannot shift the face into
# range, falls back to per-vertex wrapping, and a facet whose u should have run
# 0.000 -> 0.018 instead runs 1.000 -> 0.018 — the WHOLE mask squeezed into
# three pixels of corner. That is the window-and-lamp confetti the user saw on
# the end-corner curves. So the corner wears its own strip texture, whose
# content cannot vary along the car at all, and `--check` proves no face
# carrying the mask reaches past this.
CORNER_X = 1.1930

STORM_DOOR_HALF_X = 0.4168               # donor x of the recess, each side
STORM_DOOR_Z = 7.2770                    # donor z of the door leaf's face
STORM_DOOR_TOP_Y = 3.0670
STORM_DOOR_WINDOW_X = 0.2600             # donor |x| of its window, each side
STORM_DOOR_WINDOW_Y = (2.2600, 2.9400)
# The bezels the user asked for: a thin proud frame round the windshield and
# the roundel, and a proud round housing for each lamp. One M unit is MTR's own
# minimum relief (the M7's survey of all 16 of its vehicle models); 0.1 units is
# the house standoff for a face that must not z-fight what it sits on.
BEZEL_PROUD = 1.0                        # M units a window bezel stands proud
BEZEL_WIDTH = 1.5                        # M units wide, measured inward

# ⭐ THE SAFETY CHAINS. The donor draws them as a colour-keyed billboard
# (DriverChain.B3D, x +-0.44, y 1.50..2.51, z 7.55) over Chain.png, which is
# three CHEVRONS slung between the storm-door frame posts and dipping to a
# vertical keeper strap on the centreline. At this scale a keyed billboard is
# not an option — an alpha cutout two pixels wide dissolves — so each run is a
# real square-section bar. Thickness and count are taste calls; see R62_NOTES.
CHAIN_ANCHOR_X = 0.4168                  # the storm-door frame posts
CHAIN_YS = (2.400, 2.050, 1.700)         # donor y at the posts, per chain
CHAIN_SAG_M = 0.235                      # how far the apex hangs below them
CHAIN_THICK_UNITS = 1.0                  # M units square
CHAIN_PROUD_M = 0.020                    # ...in front of the storm-door leaf

# The anticlimber: the outermost 0.626 m of the car, a real beam under the sill.
ANTICLIMBER_Z_M = (7.1543, 7.7800)
ANTICLIMBER_Y_M = (0.9386, 1.1100)
ANTICLIMBER_HALF_X = 1.2900

# The arm buffers: the spring cylinders at the lower corners, flanking the
# anticlimber and projecting forward. NOT in the donor (its end is modelled from
# a head-on photograph, and they hide behind the gates) — these come from the
# user's own reference photo, so every number here is a taste call.
BUFFER_CENTRE_X = 1.0800                 # donor |x| of each buffer's axis
BUFFER_Y = 1.0243                        # ...its axis height (anticlimber mid)
BUFFER_RADIUS_M = 0.0850
BUFFER_PROUD_M = 0.1000                  # how far it projects past the beam
BUFFER_SIDES = 8                         # a Minecraft "cylinder" is an octagon

# The half-cab's windshield and, diagonally opposite it, the route-sign box.
# Authored for the +z end, where the cab is on +x.
WINDSCREEN_X_M = (0.6130, 1.1100)
WINDSCREEN_Y_M = (2.2220, 2.9920)
ROUNDEL_X_M = (-1.0770, -0.6240)         # the OTHER side, per the donor
ROUNDEL_Y_M = (2.3200, 2.8300)
ROUNDEL_STANDOFF = 0.30                  # M units the sign face stands proud
ROUNDEL_TEXT_STANDOFF = 0.60

# The small window each side carries near each end. Both sides have one: the
# donor's elevation is shared mirrored, so the cab's own side window and its
# opposite number are the same pixels.
END_WINDOW_Z_M = (6.617, 7.032)          # donor z, +x side
END_WINDOW_Y_M = (2.133, 2.896)

# ⭐ ONE LAMP PER SIDE PER ROW — FOUR PER END, NOT TWELVE (user-reported).
# The donor's builders 11 and 12 are each a faceted DISC per side, and the
# inventory recorded their vertex COLUMNS (0.609 / 0.696 / 0.783) as if they
# were three lamp centres. The first cut duly painted three lenses per row per
# side and lit only the outermost, so every lamp on the car's face wore two
# spurious dark copies beside it. Measured off those builders' own extents:
#
#     lens      |x| 0.5753..0.8173  ->  centre 0.6963, radius 0.1210
#     housing   |x| 0.5656..0.8270  ->  radius 0.1307   (builder 26 rim)
#     upper row y 1.762..2.004      ->  Headlight1.png, a RED marker lens
#     lower row y 1.328..1.570      ->  Headlight2.png, the WHITE headlight
#     lens face z 7.4677 against a nose at 7.4941       ->  26 mm PROUD
#
# The old figure (radius 0.075, 8 mm proud) made the lens 40% undersized AND
# left its inboard half buried in the nose, which slopes 33 mm across a lamp's
# own width — so the lit quad read as a clipped rectangle rather than a lamp.
LAMP_CENTRE_X = 0.6963
LAMP_LENS_R_M = 0.1210
LAMP_RIM_R_M = 0.1307
LAMP_ROW_UPPER_Y = (1.762, 2.004)        # the RED markers
LAMP_ROW_LOWER_Y = (1.328, 1.570)        # the WHITE headlights
LAMP_PROUD_M = 0.026                     # the lens face, past the nose
LAMP_SIDES = 8                           # facets round a lamp housing


def _lerp_table(table, a):
    """Linear interpolation over an ascending (key, value) table, clamped."""
    if a <= table[0][0]:
        return table[0][1]
    for (x0, y0), (x1, y1) in zip(table, table[1:]):
        if x0 - 1e-9 <= a <= x1 + 1e-9:
            t = 0.0 if x1 == x0 else (a - x0) / (x1 - x0)
            return y0 + (y1 - y0) * t
    return table[-1][1]


def nose_z(x_m):
    """donor z of the nose face at |x|, interpolated along NOSE_PLAN."""
    return _lerp_table(NOSE_PLAN, abs(x_m))


def brow_y(x_m):
    """donor y where the flat front face stops and the roof roll starts."""
    return _lerp_table(NOSE_BROW, abs(x_m))


def fillet_point(x_m, s_index):
    """One knot of the roof roll at |x|: (donor y, donor z).

    The profile is normalised, so the same table serves the centreline — where
    the roll is 0.42 m tall and 0.38 m deep — and the corner, where it has
    shrunk to nothing. `s_index` walks FILLET_PROFILE from the brow (0) to the
    roof's leading edge (len - 1).
    """
    s, t = FILLET_PROFILE[s_index]
    y0, y1 = brow_y(x_m), roof_y(x_m)
    z0, z1 = nose_z(x_m), ROOF_FRONT_Z
    return (y0 + (y1 - y0) * s, z0 + (z1 - z0) * t)


def roundel_plate():
    """The front roundel's display plate: (x_lo, x_hi, y_lo, y_hi) in M units.

    Snapped to whole units for the same reason the side sign's fields are, and
    made SQUARE, because a route bullet that is not square reads as a lozenge.
    """
    x0, x1 = sx(ROUNDEL_X_M[0]), sx(ROUNDEL_X_M[1])
    y0, y1 = sy(ROUNDEL_Y_M[0]), sy(ROUNDEL_Y_M[1])
    size = max(1, int(round(min(x1 - x0, y1 - y0))))
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    return (cx - size / 2.0, cx + size / 2.0, cy - size / 2.0, cy + size / 2.0)


# =================================================================== the roof
#
# Half the roof, crown outward, straight off donor builder 8. v runs 0.5 at the
# crown to 1.0 at the eaves; the other half is the mirror, which the bay builds
# for itself (this car models both sides of everything).
ROOF_PROFILE = [                         # (donor |x|, donor y, v)
    (0.0000, 3.6213, 0.500),
    (0.2346, 3.6188, 0.578),
    (0.4585, 3.6018, 0.648),
    (0.6431, 3.5602, 0.709),
    (0.7990, 3.5010, 0.772),
    (0.9407, 3.4321, 0.826),
    (1.0716, 3.3483, 0.884),
    (1.1883, 3.2489, 0.934),
    (1.2710, 3.1644, 0.975),
    (1.3109, 3.1074, 1.000),
]

# The four louvred roof vents, one per corner, read off Roof.png's plan. They
# sit inside the END bay at both ends, so the end bay owns one per side and the
# two placements give the car its four — the pane rule, applied to the roof.
#
# ⭐ THE DONOR DOES NOT MODEL THEM AT ALL. There is no vent geometry in
# Exterior.b3d: builder 8 is one smooth dome and the grilles are PAINTED into
# Roof.png. So every millimetre of relief here is our invention, and the first
# cut invented too much — 2 units proud on a footprint 35% wider than the
# painted grille read as a pair of packing crates on the roof (user-reported).
# The grille's own extent, measured off Roof.png's dark blocks through the
# roof's uv fit, is z 5.575..6.225 and |x| 0.930..1.219; ONE unit of relief is
# enough to catch a shadow and is the smallest MTR's corpus ever uses.
VENT_Z_M = (5.575, 6.225)                # donor z, inside the end bay
VENT_CENTRE_X = 1.0745                   # donor |x| of each vent's centre
VENT_HALF_X = 0.1700                     # ...and its half-width
VENT_HEIGHT_UNITS = 1.0                  # M units it stands off the roof


def roof_y(x_m):
    """donor y of the roof dome at |x|, off ROOF_PROFILE's own points."""
    a = abs(x_m)
    for (x0, y0, _v0), (x1, y1, _v1) in zip(ROOF_PROFILE, ROOF_PROFILE[1:]):
        if x0 - 1e-9 <= a <= x1 + 1e-9:
            t = 0.0 if x1 == x0 else (a - x0) / (x1 - x0)
            return y0 + (y1 - y0) * t
    return ROOF_PROFILE[-1][1]


def roof_v(x_m):
    a = abs(x_m)
    for (x0, _y0, v0), (x1, _y1, v1) in zip(ROOF_PROFILE, ROOF_PROFILE[1:]):
        if x0 - 1e-9 <= a <= x1 + 1e-9:
            t = 0.0 if x1 == x0 else (a - x0) / (x1 - x0)
            return v0 + (v1 - v0) * t
    return ROOF_PROFILE[-1][2]


# =================================================================== bogies
#
# The donor's own figure, and unlike the M7's there is no reason to depart from
# it: Extensions.cfg puts the axles at +-5.48 m on a 15.56 m car, which is
# 0.704 — and at that ratio MTR's generic 4-block truck still sits comfortably
# inboard of the coupler faces on a 16-block car.
BOGIE_RATIO_PROTOTYPE = 5.48 * 2.0 / DONOR_LENGTH_M      # 0.7044
BOGIE_RATIO = 0.704
BOGIE_HALF_BLOCKS = 2.0                  # mtr:bogie_1.bbmodel is z +-32 units


def bogie_position(blocks=BLOCKS):
    return blocks * BOGIE_RATIO / 2.0


def bogie_overhang_blocks(blocks=BLOCKS):
    return max(0.0, bogie_position(blocks) + BOGIE_HALF_BLOCKS - blocks / 2.0)


# ==================================================================== checks

def _verify():
    """Everything above is derived; this asserts it still lands where stated."""
    assert UNITS == 256 and BLOCKS == 16
    assert (DOOR_UNITS, PANEL_UNITS) == (21, 58), (DOOR_UNITS, PANEL_UNITS)
    assert END_UNITS == 38.5, END_UNITS
    assert 3 * DOOR_UNITS + 2 * PANEL_UNITS + 2 * END_UNITS == UNITS
    assert NORMAL.door.centres == [-79.0, 0.0, 79.0], NORMAL.door.centres
    assert NORMAL.panel.centres == [-39.5, 39.5], NORMAL.panel.centres
    assert NORMAL.end.centres == [-108.75, 108.75], NORMAL.end.centres
    assert NORMAL.end.span(END_Z) == (89.5, 128.0), NORMAL.end.span(END_Z)

    # The cross-section: half-width from r179, and within a whisker of the M7's
    # own units-per-metre so the two trains scale together.
    assert abs(HALF_X_UNITS - 19.418) < 1e-3, HALF_X_UNITS
    assert abs(X_SCALE - 14.813) < 1e-3, X_SCALE
    m7_x_scale = 1.456 / 1.57 * 16.0
    assert abs(X_SCALE - m7_x_scale) / m7_x_scale < 0.005, (X_SCALE, m7_x_scale)
    # Nothing may reach the rail, and the crown must land near r179's roof.
    assert sy(LOWEST_Y) > RAIL_UNITS + 4.0, sy(LOWEST_Y)
    assert 40.0 < sy(ROOF_CROWN_Y) < 44.0, sy(ROOF_CROWN_Y)

    # Bays must tile the car with no gap, in order, outward from the centre.
    edges = []
    for span, centres in ((NORMAL.door, NORMAL.door.centres),
                          (NORMAL.panel, NORMAL.panel.centres),
                          (NORMAL.end, NORMAL.end.centres)):
        for c in centres:
            edges.append(span.span(c))
    edges.sort()
    assert abs(edges[0][0] + UNITS / 2.0) < 1e-9, edges[0]
    assert abs(edges[-1][1] - UNITS / 2.0) < 1e-9, edges[-1]
    for a, b in zip(edges, edges[1:]):
        assert abs(a[1] - b[0]) < 1e-9, ("bays leave a gap at", a[1])

    # Every bay maps its own donor slice the same way round (k > 0), so no face
    # in this model ever needs its winding reversed.
    for bay in (PANEL_BAY, DOOR_BAY, END_BAY):
        assert not bay.flip_winding, bay.name
        assert abs(bay.units_per_m - Z_SCALE) / Z_SCALE < 0.01, \
            (bay.name, bay.units_per_m)

    # The doors: the leaves tile the aperture, each retracts about its own
    # width, and R179's |m| - 0.5 curve clears the opening with both open.
    assert LEAF_NARROW + LEAF_WIDE == APERTURE_UNITS
    assert LEAF_NARROW < LEAF_WIDE, (LEAF_NARROW, LEAF_WIDE)
    assert (DOOR_MULTIPLIER_NARROW - 0.5) + (DOOR_MULTIPLIER_WIDE - 0.5) \
        >= APERTURE_UNITS, "the leaves do not clear the aperture"
    for m in (DOOR_MULTIPLIER_NARROW, DOOR_MULTIPLIER_WIDE):
        assert abs(m - 0.5 - round(m - 0.5)) < 1e-9, m
    # The leaf is a POCKET door: strictly inboard of the skin at every height,
    # which on a car with no tumblehome is one comparison rather than a band
    # stack. And it has to leave the interior pass somewhere to put a lining.
    assert LEAF_X_INTERIOR < LEAF_X_EXTERIOR < DONOR_HALF_X
    assert LINING_MAX_X_M < LEAF_X_INTERIOR

    # The side rollsign: whole-pixel display plates, all three inside the box,
    # the box inside the panel bay, and the glass stopping under the housing.
    for name, (z0, z1, y0, y1) in (("bullet", sign_bullet()),
                                   ("upper", sign_upper()),
                                   ("lower", sign_lower())):
        for span in (z1 - z0, y1 - y0):
            assert abs(span - round(span)) < 1e-9, (name, span)
        lo, hi = PANEL_BAY.z(SIGN_BOX_Z_M[0]), PANEL_BAY.z(SIGN_BOX_Z_M[1])
        assert lo <= z0 and z1 <= hi, (name, "runs off the sign box in z")
        assert sy(SIGN_BOX_Y_M[0]) <= y0 and y1 <= sy(SIGN_BOX_Y_M[1]), \
            (name, "runs off the sign box in y")
    assert sign_upper()[2] >= sign_lower()[3], "the two text lines overlap"
    # ...and the whole box has to fit inside the panel bay, or the crop cuts it.
    for z in SIGN_BOX_Z_M:
        assert PANEL_BAY.contains(z), ("the sign box leaves the panel bay", z)
    assert rollsign_glass_top_m() < SIGN_BOX_Y_M[0]
    assert WINDOW_Y_M[0] < rollsign_glass_top_m() < WINDOW_Y_M[1]

    # The interior shell: the lining inboard of the reserved door-pocket
    # corridor, the ad band inside the donor's rack band, and the strip map's
    # three fields whole-unit, inside the board, inside the panel bay.
    assert INT_LINING_X_M < LINING_MAX_X_M < DONOR_HALF_X
    assert INT_BAND_X_M < INT_LINING_X_M
    assert abs(sx(INT_LINING_X_M - INT_BAND_X_M) - 1.0) < 1e-9
    assert INT_WALL_BAND_Y < INT_WALL_TOP_Y < INT_BAND_Y_M[1] < INT_CROWN_Y, \
        "the ad band must sit inside the donor's rack band"
    assert abs(sy(INT_BAND_Y_M[1]) - sy(INT_BAND_Y_M[0])
               - INT_BAND_UNITS) < 1e-9
    assert INT_CROWN_HALF_X < INT_BAND_X_M
    for units in (0.0, 7.5, 33.0):
        assert abs(sx(mx(units)) - units) < 1e-9
        assert abs(sy(my(units)) - units) < 1e-9
    bz0, bz1, by0, by1 = map_board()
    assert MAP_UNITS < PANEL_UNITS - 2, "the strip map fills its whole bay"
    for name, (z0, z1, y0, y1) in (("bullet", map_bullet()),
                                   ("text", map_text()),
                                   ("ticks", map_ticks())):
        for span in (z1 - z0, y1 - y0):
            assert abs(span - round(span)) < 1e-9, (name, span)
        assert bz0 <= z0 and z1 <= bz1, (name, "runs off the strip-map board")
        assert by0 <= y0 and y1 <= by1, (name, "runs off the board in y")
    assert map_bullet()[1] < map_text()[0] < map_text()[1] < map_ticks()[0], \
        "the strip map's three fields overlap"
    assert MAP_TEXT_STANDOFF > MAP_STANDOFF, \
        "the display text would be buried inside its own housing"

    # The two panel windows must each sit WHOLLY inside the panel bay — the
    # elevation is drawn once and cropped per bay, and a pane the crop cuts
    # becomes an unglazed slot (M7_CONVERSION_NOTES: "A BAY MAY NEVER CARRY
    # HALF A WINDOW"). Same rule, same reason, different car.
    for name, span in (("rollsign", ROLLSIGN_WINDOW_Z_M),
                       ("saloon", SALOON_WINDOW_Z_M)):
        for z in span:
            assert PANEL_BAY.contains(z), (name, "leaves the panel bay", z)
    for z in END_WINDOW_Z_M:
        assert END_BAY.contains(z), ("the end window leaves the end bay", z)

    # The nose: monotonic in both x and z (a bullnose that doubled back would
    # produce inside-out facets), and it must start where the bodyside stops.
    assert NOSE_PLAN[-1][0] == DONOR_HALF_X
    for (x0, z0), (x1, z1) in zip(NOSE_PLAN, NOSE_PLAN[1:]):
        assert x1 > x0 and z1 < z0, ("the nose plan doubles back", x0, x1)
    assert nose_z(0.0) == NOSE_PLAN[0][1]
    assert abs(nose_z(DONOR_HALF_X) - ROOF_FRONT_Z) < 1e-9
    assert STORM_DOOR_HALF_X == NOSE_PLAN[0][0], \
        "the storm-door recess and the nose's flat centre must be the same span"
    assert STORM_DOOR_Z < nose_z(0.0), "the storm door is not recessed"
    assert STORM_DOOR_WINDOW_X < STORM_DOOR_HALF_X
    # The end bay has to contain the whole nose AND the anticlimber.
    for z in (nose_z(0.0), ANTICLIMBER_Z_M[0], ANTICLIMBER_Z_M[1]):
        assert END_BAY.contains(z), ("the end bay does not reach", z)
    assert ANTICLIMBER_Z_M[1] == DONOR_HALF_LEN, "the beam is not the car end"

    # ⭐ THE BROW AND THE ROLL — the anti-beak invariants. The flat face must
    # stop ABOVE the eaves everywhere except at the corner, where it has to hand
    # over to the bodyside exactly; and the roll above it must be a monotone
    # curve that starts at the brow and lands on the roof's leading edge.
    assert NOSE_BROW[0][0] == 0.0 and NOSE_BROW[-1][0] == DONOR_HALF_X
    for (x0, _y0), (x1, _y1) in zip(NOSE_BROW, NOSE_BROW[1:]):
        assert x1 > x0, ("the brow table is not ascending", x0, x1)
    assert abs(brow_y(DONOR_HALF_X) - NOSE_EAVE_Y) < 1e-9, \
        "the brow must meet the bodyside's own top at the corner"
    assert brow_y(0.0) > NOSE_EAVE_Y, "the front face still stops at the eaves"
    for x in (0.0, 0.5, 0.9, 1.2):
        assert brow_y(x) < roof_y(x), ("the brow is above the roof at", x)
    assert FILLET_PROFILE[0] == (0.0, 0.0) and FILLET_PROFILE[-1] == (1.0, 1.0)
    for (s0, t0), (s1, t1) in zip(FILLET_PROFILE, FILLET_PROFILE[1:]):
        assert s1 > s0 and t1 > t0, "the roof roll doubles back"
    for x in (0.0, 0.7122, 1.2650):
        first, last = fillet_point(x, 0), fillet_point(x, len(FILLET_PROFILE) - 1)
        assert abs(first[0] - brow_y(x)) < 1e-9
        assert abs(first[1] - nose_z(x)) < 1e-9
        assert abs(last[0] - roof_y(x)) < 1e-9
        assert abs(last[1] - ROOF_FRONT_Z) < 1e-9

    # ⭐ THE MASK MAY ONLY BE ASKED FOR u IT ACTUALLY HAS. Outboard of CORNER_X
    # the corner strip takes over precisely because `mask_u` goes (just)
    # negative at the bodyside plane; assert that it has not, inboard of it.
    assert STORM_DOOR_HALF_X < CORNER_X < DONOR_HALF_X
    for x in (-CORNER_X, -1.0, 0.0, 1.0, CORNER_X):
        assert 0.0 <= mask_u(x) <= 1.0, ("the mask has no u at", x)
    assert mask_u(DONOR_HALF_X) < 0.0, \
        "mask_u no longer overshoots — the corner strip may be unnecessary"

    # The buffers and the chains have to sit on the things they are bolted to.
    assert ANTICLIMBER_Y_M[0] < BUFFER_Y < ANTICLIMBER_Y_M[1]
    assert BUFFER_CENTRE_X + BUFFER_RADIUS_M < ANTICLIMBER_HALF_X
    assert BUFFER_RADIUS_M * 2 < ANTICLIMBER_Y_M[1] - ANTICLIMBER_Y_M[0] + 0.06
    for y in CHAIN_YS:
        assert FLOOR_Y < y - CHAIN_SAG_M and y < STORM_DOOR_TOP_Y, \
            ("a safety chain leaves the storm-door opening", y)
    assert CHAIN_ANCHOR_X == STORM_DOOR_HALF_X

    # The windshield and the roundel are on OPPOSITE sides, which is the whole
    # reason the end bay is placed unflipped-and-flipped instead of mirrored.
    assert WINDSCREEN_X_M[0] > 0 and ROUNDEL_X_M[1] < 0, \
        "the cab and the roundel must be diagonally opposite"
    for x in WINDSCREEN_X_M + ROUNDEL_X_M:
        assert abs(x) < DONOR_HALF_X
    # ...and both must clear the storm-door recess in x.
    assert min(abs(x) for x in WINDSCREEN_X_M + ROUNDEL_X_M) > STORM_DOOR_HALF_X
    rx0, rx1, ry0, ry1 = roundel_plate()
    assert abs((rx1 - rx0) - (ry1 - ry0)) < 1e-9, "the roundel is not square"
    assert abs((rx1 - rx0) - round(rx1 - rx0)) < 1e-9, "roundel not whole units"
    assert sx(ROUNDEL_X_M[0]) - 0.5 <= rx0 and rx1 <= sx(ROUNDEL_X_M[1]) + 0.5

    # ⭐ ONE lamp per side per row, and it must fit between the storm-door
    # recess and the corner strip — a lamp that reached past CORNER_X would put
    # its own lens back on a facet the mask no longer paints.
    assert LAMP_CENTRE_X - LAMP_RIM_R_M > STORM_DOOR_HALF_X
    assert LAMP_CENTRE_X + LAMP_RIM_R_M < CORNER_X
    assert LAMP_LENS_R_M < LAMP_RIM_R_M
    assert ANTICLIMBER_Y_M[1] <= LAMP_ROW_LOWER_Y[0]
    assert LAMP_ROW_LOWER_Y[1] < LAMP_ROW_UPPER_Y[0]
    assert LAMP_ROW_UPPER_Y[1] < min(WINDSCREEN_Y_M[0], ROUNDEL_Y_M[0])
    for row in (LAMP_ROW_UPPER_Y, LAMP_ROW_LOWER_Y):
        assert abs((row[1] - row[0]) / 2.0 - LAMP_LENS_R_M) < 0.001, \
            ("a lamp row is not as tall as the lens is wide", row)
    # ⭐ THE LENS MUST CLEAR THE NOSE ACROSS ITS WHOLE WIDTH. The nose drops
    # 33 mm over a lamp's own diameter, so a flat lens quad set 8 mm off the
    # CENTRE — the first cut — buries its inboard half. 26 mm clears it.
    assert LAMP_PROUD_M > (nose_z(LAMP_CENTRE_X - LAMP_LENS_R_M)
                           - nose_z(LAMP_CENTRE_X)), \
        "the lit lens is still half inside the nose"

    # The roof: monotonic outward, and the vents inside one end bay.
    for (x0, y0, v0), (x1, y1, v1) in zip(ROOF_PROFILE, ROOF_PROFILE[1:]):
        assert x1 > x0 and y1 <= y0 and v1 > v0, ("roof profile", x0, x1)
    assert abs(ROOF_PROFILE[-1][0] - DONOR_HALF_X) < 1e-9
    assert abs(ROOF_PROFILE[-1][1] - NOSE_EAVE_Y) < 1e-9
    for z in VENT_Z_M:
        assert END_BAY.contains(z), ("a roof vent leaves the end bay", z)
    assert VENT_CENTRE_X + VENT_HALF_X < DONOR_HALF_X

    # Bogies inboard of the coupler faces, truck box included.
    assert 0.4 < BOGIE_RATIO < 0.95, BOGIE_RATIO
    assert abs(BOGIE_RATIO - BOGIE_RATIO_PROTOTYPE) < 0.01
    assert bogie_overhang_blocks() <= 1e-9, bogie_overhang_blocks()

    # The texture maps must invert.
    for z in (-7.0, 0.0, 3.5):
        for side in (+1, -1):
            assert abs(z_of_u(u_of_z(z, side), side) - z) < 1e-9
    for y in (1.2, 2.5, 3.0):
        assert abs(side_y(side_v(y)) - y) < 1e-9
        assert abs(mask_y_of_v(mask_v(y)) - y) < 1e-9
    for x in (-1.0, 0.0, 0.9):
        assert abs(mask_x_of_u(mask_u(x)) - x) < 1e-9
    assert abs(u_of_z(Z_AT_U0) - 0.0) < 1e-9
    # ⭐ The mirroring IS the rotational symmetry: the +x side at donor z and
    # the -x side at donor -z read the SAME texture column. Everything about
    # the one-elevation-serves-both-sides claim rests on this line.
    for z in (1.9, -4.4, 6.6):
        assert abs(u_of_z(z, +1) - u_of_z(-z, -1)) < 1e-9, z


_verify()


def main():
    print("R62 layout — all derived, all checked")
    print("  donor %.3f m over %d bays -> %.4f units/m along the car"
          % (DONOR_LENGTH_M, 7, Z_SCALE))
    print("  cross-section: half-width %.3f units (%.4f blk) from r179 x %g/%g"
          % (HALF_X_UNITS, HALF_X_UNITS / 16.0, R62_WIDTH_FT, R179_WIDTH_FT))
    print("  x %.4f units/m   y %.4f units/m (one scale, no compression)"
          % (X_SCALE, Y_SCALE))
    print("\n%s: %d units = %d blocks" % (NORMAL.label.upper(), UNITS, BLOCKS))
    for span in (NORMAL.end, NORMAL.door, NORMAL.panel):
        print("  %-6s %5g units  centres %s"
              % (span.name, span.units,
                 ", ".join("%+g" % c for c in span.centres)))
    print("\nbays (donor metres -> local units):")
    for bay in (PANEL_BAY, DOOR_BAY, END_BAY):
        print("  %-6s %5g u   donor %+8.4f..%+8.4f   %7.3f u/m"
              % (bay.name, bay.units, bay.za, bay.zb, bay.units_per_m))
    print("\ndoors: aperture %g units, leaves %d + %d, multipliers %+.1f / %+.1f"
          % (APERTURE_UNITS, LEAF_NARROW, LEAF_WIDE,
             DOOR_MULTIPLIER_NARROW, DOOR_MULTIPLIER_WIDE))
    print("       leaf %d units tall, meets at %+g, pocket x %.4f/%.4f m "
          "(skin %.4f)" % (LEAF_HEIGHT_UNITS, LEAF_MEET_Z, LEAF_X_EXTERIOR,
                           LEAF_X_INTERIOR, DONOR_HALF_X))
    print("       interior pass: keep the lining inboard of %.4f m"
          % LINING_MAX_X_M)
    print("\nside rollsign (panel-bay local units):")
    for name, f in (("bullet", sign_bullet()), ("upper", sign_upper()),
                    ("lower", sign_lower())):
        print("  %-7s z %+7.2f..%+7.2f  y %+6.2f..%+6.2f   %d x %d px"
              % (name, f[0], f[1], f[2], f[3],
                 round(f[1] - f[0]), round(f[3] - f[2])))
    rx0, rx1, ry0, ry1 = roundel_plate()
    print("front roundel  x %+7.2f..%+7.2f  y %+6.2f..%+6.2f   %d x %d px"
          % (rx0, rx1, ry0, ry1, round(rx1 - rx0), round(ry1 - ry0)))
    print("\nnose: %.3f m deep, %d facets per half, storm door recessed %.0f mm"
          % (nose_z(0.0) - ROOF_FRONT_Z, len(NOSE_PLAN) - 1,
             (nose_z(0.0) - STORM_DOOR_Z) * 1000))
    print("bogies: ratio %.3f (prototype %.4f) -> +-%.2f blk, overhang %.2f"
          % (BOGIE_RATIO, BOGIE_RATIO_PROTOTYPE, bogie_position(),
             bogie_overhang_blocks()))


if __name__ == "__main__":
    main()
