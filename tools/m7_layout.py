#!/usr/bin/env python3
"""The LIRR M7's bay layout and scale — ONE definition, shared by every tool.

    import m7_layout as L
    L.NORMAL.window.centres      # [-75, -45, -15, 15, 45, 75]
    L.NORMAL.door.centres        # [-112, 112]
    L.WINDOW_BAY.z(3.545)        # donor metres -> M units, inside that bay

WHY THIS MODULE EXISTS
----------------------
Three things have to agree about where a bay starts and stops: the body
converter (`tools/convert_openbve_m7.py`), the interior converter
(`tools/convert_m7_interior.py`) and the position definitions that repeat them
(`tools/gen_m7_assets.py`). They agreed by copy-paste until the car was resized,
which is exactly the moment copy-paste stops agreeing. Everything dimensional
now lives here, is derived rather than typed twice, and is self-checked at
import (see `_verify()` — it runs on every import and raises on a mismatch).

The body converter adopts this module in the resize pass; until it does, it
carries its own copy of these numbers at the OLD 20/10-block sizes and the two
WILL disagree. That is expected and temporary.

RUN IT to print the layout:  python3 tools/m7_layout.py

UNITS AND FRAMES (the full story is in M7_CONVERSION_NOTES.md — read it)
-----------------------------------------------------------------------
donor   openBVE metres. x across, y up from the RAIL, +z = the cab end.
M       "bbmodel frame": 1 unit = 1/16 block, y = 0 at the car FLOOR. Every
        number in this module that is not explicitly donor metres is M units,
        because M units are what `properties/definition/*.json` carries.
OBJ     obj = (-Mx, +My, -Mz) / 16.  One OBJ unit is one BLOCK.

THE LAYOUT (user decision, 2026-07-28 — the car grew 20 -> 26 blocks)
---------------------------------------------------------------------
    normal  416 units = 26 blocks
        end(74) | door(44) | window(30) x6 | door(44) | end(74)
        windows +-15/+-45/+-75   doors +-112   ends +-171 (span 134..208)

    mini    240 units = 15 blocks
        end(61) | door(44) | window(30) | door(44) | end(61)
        window 0                doors +-37    ends +-89.5 (span 59..120)

Bay centres are DERIVED by walking the bay list inward from the car end, never
typed in. The brief this was built from quoted the normal door centre as +-104,
which does not survive that walk (74 + 44/2 measured from 208 is 112) and does
not agree with the end span 134..208 that the same brief gives; 112 is the
number that makes every other stated figure true, so 112 is what is used. The
mini figures in the brief (+-37, +-89.5) all reproduce exactly.

At 26 blocks the car is very nearly true scale: the four verified donor slices
that make up a side (cab 4.8379 + door 2.7121 + 6 x window 1.8325 + door 2.7121
+ rear 4.3736) sum to 25.631 m against a 25.65 m prototype, so 416 units over
that is 16.23 units/m and the whole car maps at ONE scale instead of the ~23%
squeeze the 20-block car needed. The two end bays are the exception: they still
carry a slice that is longer than their share (the nose and the diaphragm have
to fit inside the car end), so they compress by a further ~10%.
"""

import math

# --------------------------------------------------------------------- scale
#
# Cross-section scale is UNCHANGED by the resize — only z moved. See Part 3 of
# M7_CONVERSION_NOTES.md for where each factor comes from.
X_SCALE = 1.456 / 1.57 * 16.0            # M units per donor metre, across
Y_SCALE_ABOVE = 1.0255 * 16.0            # M units per donor metre, above floor
FLOOR_Y = 1.295                          # donor y of the door sill = our y 0
LOWEST_Y = 0.10                          # donor y of the lowest underframe
Y_SCALE_BELOW = (0.95 * 16.0) / (FLOOR_Y - LOWEST_Y)


def sx(x_m):
    """donor x (metres) -> M units."""
    return x_m * X_SCALE


def sy(y_m):
    """donor y (metres above rail) -> M units, floor at 0, up positive."""
    d = y_m - FLOOR_Y
    return d * (Y_SCALE_ABOVE if d >= 0 else Y_SCALE_BELOW)


# ------------------------------------------------------------- donor texture
#
# LAT.png (exterior) and latint/latint2.png (interior) are both 2048 px
# elevations of the same car side and share ONE u -> z map, measured off the
# donor's own UVs in both files:  z(u) = 12.500 - 25.020 u.  That is what lets
# an interior bay be cut with the same u range as the exterior bay it lines.
Z_AT_U0 = 12.500
Z_PER_U = -25.020


def u_of_z(z_m):
    return (z_m - Z_AT_U0) / Z_PER_U


def z_of_u(u):
    return Z_AT_U0 + Z_PER_U * u


# --------------------------------------------------------------- bay lengths
#
# The verified donor slices (M7_CONVERSION_NOTES.md Part 2). Their sum is what
# sets the car's z scale, so they are the input and 416 is the consequence.
DONOR_CAB_M = 4.8379
DONOR_DOOR_M = 2.7121
DONOR_WINDOW_M = 1.8325
DONOR_REAR_M = 4.3736
WINDOW_COUNT = 6

DONOR_SIDE_M = (DONOR_CAB_M + DONOR_DOOR_M + WINDOW_COUNT * DONOR_WINDOW_M
                + DONOR_DOOR_M + DONOR_REAR_M)          # 25.6307 m

BLOCKS_NORMAL = 26
BLOCKS_MINI = 15
UNITS_NORMAL = BLOCKS_NORMAL * 16                        # 416
UNITS_MINI = BLOCKS_MINI * 16                            # 240

Z_SCALE = UNITS_NORMAL / DONOR_SIDE_M                    # ~16.231 units/m

# Bay widths in M units. window/door are Z_SCALE applied to their own donor
# slice and rounded; the two ends then split whatever is left, evenly, because
# an asymmetric car end would move the coupler faces apart.
WINDOW_UNITS = round(DONOR_WINDOW_M * Z_SCALE)           # 30
DOOR_UNITS = round(DONOR_DOOR_M * Z_SCALE)               # 44
END_UNITS = (UNITS_NORMAL - WINDOW_COUNT * WINDOW_UNITS
             - 2 * DOOR_UNITS) // 2                      # 74

# Mini keeps ONE window bay and both door bays at full size — the door has to
# stay the size of a door — so its ends absorb the whole difference.
MINI_END_UNITS = (UNITS_MINI - WINDOW_UNITS - 2 * DOOR_UNITS) // 2   # 61

# Door leaf travel: 1.45 m (A.Animated), and R179's curve opens by
# |multiplier| - 0.5, so round UP to clear the aperture.
DOOR_TRAVEL_M = 1.45
DOOR_MULTIPLIER = int(math.ceil(DOOR_TRAVEL_M * DOOR_UNITS / DONOR_DOOR_M))   # 24


# ---------------------------------------------------------------- bay centres

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

    def __init__(self, label, units, end_units, windows):
        self.label = label
        self.units = units
        self.blocks = units // 16
        half = units / 2.0

        end_centre = half - end_units / 2.0
        door_centre = half - end_units - DOOR_UNITS / 2.0
        inner = half - end_units - DOOR_UNITS          # |z| where windows stop

        centres = []
        for i in range(windows):
            centres.append(-inner + WINDOW_UNITS * (i + 0.5))
        centres = [c if abs(c) > 1e-9 else 0.0 for c in centres]

        self.window = BaySpan("window", WINDOW_UNITS, centres)
        self.door = BaySpan("door", DOOR_UNITS, [-door_centre, door_centre])
        self.end = BaySpan("end", end_units, [-end_centre, end_centre])
        self.saloon_half = inner                       # windows span +-this

        # Seat rows sit on ONE pitch across the whole window zone — the donor's
        # own 0.85 m becomes 15 units here, which is exactly half a window bay,
        # so two rows land in every bay and the rhythm never breaks at a seam.
        self.seat_pitch = WINDOW_UNITS / 2.0
        rows = []
        z = -inner + self.seat_pitch / 2.0
        while z < inner - 1e-9:
            rows.append(round(z, 3))
            z += self.seat_pitch
        # Plus whatever fits in the end bays, inboard of the tightest end wall
        # (the cab bulkhead) with half a seat's depth to spare.
        z = self.door.centres[1] + DOOR_UNITS / 2.0 + self.seat_pitch / 2.0
        while z + SEAT_DEPTH_UNITS / 2.0 <= end_centre + end_units / 2.0 - CAB_BULKHEAD_INSET:
            rows.append(round(z, 3))
            rows.append(round(-z, 3))
            z += self.seat_pitch
        self.seat_rows = sorted(rows)

    @property
    def seat_rows_forward(self):
        """Rows in the -z half. Their occupants face +z (donor asiento*.csv)."""
        return [z for z in self.seat_rows if z < 0]

    @property
    def seat_rows_reverse(self):
        """Rows in the +z half, facing -z (donor asiento*180.csv)."""
        return [z for z in self.seat_rows if z > 0]

    def __repr__(self):
        return ("CarLayout(%s: %d units / %d blocks, end %g, door %s, "
                "windows %s)" % (self.label, self.units, self.blocks,
                                 self.end.units, self.door.centres,
                                 self.window.centres))


# ------------------------------------------------------------- donor -> bay
#
# Every bay maps its OWN donor z range linearly onto its OWN unit span, so two
# bays may compress differently without the seam between them moving. Assembled,
# the car is a collage of donor slices, not one uniform squeeze.

class Bay:
    """A donor z-range mapped linearly onto a span of M units."""

    def __init__(self, name, units, z_donor_a, z_donor_b, m_a, m_b):
        self.name = name
        self.units = units
        self.za, self.zb = z_donor_a, z_donor_b
        self.ma, self.mb = m_a, m_b
        self.k = (m_b - m_a) / (z_donor_b - z_donor_a)
        # donor -> M scales x and y positively, so the sign of dz/dz IS the
        # determinant of the whole map: negative means every face copied
        # through this bay needs its winding reversed.
        self.flip_winding = self.k < 0

    def z(self, z_m):
        """donor z (metres) -> M units, local to the bay (centred on 0)."""
        return self.ma + (z_m - self.za) * self.k

    def donor_z(self, m_z):
        return self.za + (m_z - self.ma) / self.k

    def contains(self, z_m, slack=1e-6):
        lo, hi = min(self.za, self.zb), max(self.za, self.zb)
        return lo - slack <= z_m <= hi + slack

    def clamp(self, z_m):
        lo, hi = min(self.za, self.zb), max(self.za, self.zb)
        return min(hi, max(lo, z_m))

    @property
    def units_per_m(self):
        return abs(self.k)

    def truncated(self, name, units):
        """The OUTER `units` of this bay, at the same units/metre.

        This is how the Mini's end pieces are made: not a separate, differently
        squashed end, but a straight crop of the full one, so the two lengths
        share a scale and the crop point is derived from the unit budget rather
        than picked.
        """
        span_m = units / self.units_per_m
        zb = self.za + math.copysign(span_m, self.zb - self.za)
        return Bay(name, units, self.za, zb, -units / 2.0, units / 2.0)


# The window bay: LAT/latint px 733..883, one saloon window dead-centre.
WINDOW_BAY = Bay("window", WINDOW_UNITS, 3.5450, 1.7130,
                 -WINDOW_UNITS / 2.0, WINDOW_UNITS / 2.0)

# The door bay is centred on the REAR opening rather than on the notes' pixel
# slice: the car's two real openings are 47 mm out of symmetry and one bay has
# to serve both, so it is squared up here.
DOOR_APERTURE_CENTRE = -6.6475
DOOR_APERTURE_A = -5.935                 # donor z, saloon side of the opening
DOOR_APERTURE_B = -7.360                 # donor z, other side
_DOOR_HALF_M = (DOOR_UNITS / 2.0) / (DOOR_UNITS / DONOR_DOOR_M)
DOOR_BAY = Bay("door", DOOR_UNITS,
               DOOR_APERTURE_CENTRE + _DOOR_HALF_M,
               DOOR_APERTURE_CENTRE - _DOOR_HALF_M,
               -DOOR_UNITS / 2.0, DOOR_UNITS / 2.0)

# Both end bays are authored with their OUTWARD face at local -z (r179's
# convention): `end1` places the group unflipped and `end2` places it flipped,
# which turns the same geometry round to face the other end of the car.
# The outer donor limit is the last thing that must not cross the car end —
# the snowplough tip forward, the diaphragm aft.
CAB_BAY = Bay("cab", END_UNITS, 12.851, 7.662, -END_UNITS / 2.0, END_UNITS / 2.0)
GANGWAY_BAY = Bay("gangway", END_UNITS, -12.9651, -8.146,
                  -END_UNITS / 2.0, END_UNITS / 2.0)
CAB_BAY_MINI = CAB_BAY.truncated("cab_mini", MINI_END_UNITS)
GANGWAY_BAY_MINI = GANGWAY_BAY.truncated("gangway_mini", MINI_END_UNITS)

# The equipment raft is one 13 m elevation that does not tile, so it is placed
# once per car instead of being split across bays. Its length is a fraction of
# the car rather than a donor slice.
UNDERFRAME_BAY = Bay("underframe", 0.0, 6.5, -6.5, 0.0, 0.0)   # rebuilt below
UNDERFRAME_BAY = Bay("underframe", round(UNITS_NORMAL * 0.52), 6.5, -6.5,
                     -round(UNITS_NORMAL * 0.52) / 2.0,
                     round(UNITS_NORMAL * 0.52) / 2.0)
_UF_MINI = round(UNITS_MINI * 0.46)
UNDERFRAME_BAY_MINI = Bay("underframe_mini", _UF_MINI,
                          _UF_MINI / 2.0 / UNDERFRAME_BAY.units_per_m,
                          -_UF_MINI / 2.0 / UNDERFRAME_BAY.units_per_m,
                          -_UF_MINI / 2.0, _UF_MINI / 2.0)

# Interior landmarks the layout has to know about, because they decide how much
# saloon an end bay actually has: the cab bulkhead (AInterior builder 11/13)
# and the gangway end wall (builders 23/24).
CAB_BULKHEAD_Z = 10.200                  # donor z
CAB_RECESS_Z = 11.300                    # donor z, back of the cab-door recess
GANGWAY_WALL_Z = -12.690                 # donor z
SALOON_WALL_END_Z = (-12.520, 11.300)    # donor z range of AInterior's LATERAL

# How far inboard of an end bay's outer edge the cab bulkhead sits, in units —
# used to decide how many seat rows an end bay can hold. Derived, not typed.
CAB_BULKHEAD_INSET = (CAB_BAY.za - CAB_BULKHEAD_Z) * CAB_BAY.units_per_m

# ------------------------------------------------- the cab destination sign
#
# ⭐ ADDED 2026-07-28 (user request). Neither the prototype nor the donor has a
# front destination sign, so its position is ours to choose — and it lives here
# rather than in either converter because THREE tools have to agree on it:
#
#   convert_openbve_m7.py  paints the recessed backing box on the cab mask
#   gen_m7_doors.py        emits the DISPLAY elements that carry the text
#   gen_m7_assets.py       binds those elements in the cab-end properties
#
# It cannot be one tool's business because **MTR 4.0.5 cannot render a DISPLAY
# part on an .obj model at all** (verified in bytecode — see
# M7_CONVERSION_NOTES). The text has to come from the companion .bbmodel while
# the backing art comes from the .obj, so the two are generated by different
# tools and must land in the same place to the pixel.
#
# ⭐ WHERE IT SITS (MOVED 2026-07-28, second pass, user request). The first cut
# put it BETWEEN the windshields, above the cab access door. It now sits in the
# black band ALONG THE TOP of the mask, just above the windshields and centred
# on the car — which is where a real destination sign goes.
#
# That band is TIGHT, and the two things bounding it are what every number
# below is derived from:
#
#   BELOW  the windshield gaskets. The side screens' glass tops out at donor
#          y 3.183 and `A.pane` draws two more pixels of surround above that
#          (~0.045 m on this texture). The sign dips slightly under that line
#          and gets away with it ONLY because it is narrower than the gap
#          BETWEEN the screens — see SIGN_WINDSCREEN_INNER_X_M.
#   ABOVE  the roofline at 3.400, where the flat mask gives way to the roof
#          cap. The cap is 0.17 m further back (donor z 12.500 against the
#          mask's 12.670), so a plate straying above 3.400 would leave its text
#          floating a visible 0.17 m proud of the surface. Do not let it.
#
# Together that is about 3.2 M units for BOTH displays, which is why the
# destination is 2 units tall and not 3.
#
# Half-width is authored in M UNITS, not donor metres, because MTR takes the
# element's size as WHOLE PIXELS (`Math.round(to - from)`) and uses that
# integer as the text canvas — 8.0 gives exactly 16. The donor figure is
# derived from it for the art, never the other way round.
SIGN_HALF_X = 8.0                # M units -> element width 16, as r179's
SIGN_BOX_Y_M = (3.199, 3.399)    # donor y of the painted backing, bottom/top
SIGN_PLANE_Z_M = 12.690          # donor z: 0.02 m proud of the cab door face

# The two display elements, in M units (y above the floor). Sized so that
# MTR's `Math.round(to - from)` gives whole pixels — it uses those integers as
# the text canvas, so a fractional size silently rounds the layout.
#
# STACKED, not side by side, and x-SYMMETRIC on purpose: a `positionsFlipped`
# entry turns the element 180 degrees about Y, so anything off-centre in x
# comes out mirrored at the other cab. Stacked and centred, both cabs read
# identically. 16 wide x 2 tall is a wide, short LED strip — the right shape
# for a SCROLLING destination, and exactly r179's destination width.
SIGN_DEST_Y = (31.4, 33.4)       # 2 units tall: the destination text
SIGN_COLOR_Y = (33.5, 34.5)      # 1 unit: the route-colour bar above it

# The windshields the band sits on top of, recorded so the numbers above can be
# read without the donor to hand. `--check` in the body converter re-derives
# both from M7frente.png's OWN glazing boxes and fails if the sign fouls them,
# so these are documentation, not the authority.
SIGN_WINDSCREEN_GLASS_TOP_M = 3.183
SIGN_WINDSCREEN_INNER_X_M = 0.617


SIGN_HALF_X_M = SIGN_HALF_X / X_SCALE    # donor metres, for the mask art


def sign_plane_z(bay):
    """M-frame z of the sign plane inside `bay` (CAB_BAY or CAB_BAY_MINI)."""
    return bay.z(SIGN_PLANE_Z_M)


# -------------------------------------------- the exterior side destination
#
# ⭐ ADDED 2026-07-28 (user request: side destination signs "positioned and
# looking like the real M7's").
#
# WHAT THE REAL M7 ACTUALLY CARRIES (researched 2026-07-28 — read this before
# "correcting" the position). The LIRR M7's only exterior side signage is an
# ILLUMINATED NUMBER BOARD: a backlit white plate with black car numerals,
# roughly 2.2:1, in the letterboard at the CAB end beside the marker lights
# (clearly legible in the Port Washington photo of 7828 in the session
# scratchpad, and the feature Wikipedia/Grokipedia single out as the M7's
# difference from Metro-North's M7A). It is NOT amber, NOT dot-matrix, and it
# shows a car number, not a destination. The car that does carry amber LED
# side destination signs is the M7's LIRR successor, the M9 ("digital
# destination signs front and sides"). The Bombardier M-7 spec sheet's side
# elevation puts every small letterboard fitting in the same band.
#
# So the brief's "like the real M7's amber LED side signs" describes something
# the prototype does not have, and the honest reading — agreed with what the
# front sign already did, which the donor also lacks — is: put it where a real
# LIRR EMU carries side signage (the letterboard, beside a door), give it the
# amber-on-black LED look of the M9 and of MTR's own r179, and say so here.
#
# WHERE, and why the bay system decides it:
#
#   * IT MAY NOT GO IN A WINDOW BAY. That bay repeats SIX times per side, so
#     one housing drawn there becomes six signs.
#   * IT MAY NOT GO IN AN END BAY. A trailer has two gangway ends and no cab
#     end, so an end-bay sign would miss whole cars — and the brief is that
#     every car shows them. (This is also why it cannot sit where the real
#     number board does: that spot only exists on a cab car.)
#   * That leaves the DOOR BAY, which every car has twice per side. Inside it
#     the aperture takes the middle, so the sign goes on a flanking panel: the
#     0.644 m of blank side between the opening and the bay's own edge. It
#     takes the panel the hazard triangle and its label do NOT use — those are
#     drawn on the other flank, and the two never meet.
#   * VERTICALLY it sits in the letterboard, just above the window head
#     (2.777) and well under the cant rail (3.400) — high on the side, which
#     is where a real one is, and clear of the door head so the housing never
#     crosses the aperture.
#
# The result is two per side, four per car, on every variant including Mini
# (the door centres are the only thing that differs, and both lengths take
# them from the same list).
#
# ⭐ AND THE SIGNS ARE NOT MIRROR-SYMMETRIC ABOUT THE CAR CENTRE. They cannot
# be: the door bay is TRANSLATED to both door centres and only the SIDES are
# mirrored, so an off-centre feature in that bay sits toward the car centre at
# one door and toward the car end at the other — and the two sides of the car
# are 180-degree rotations of each other. The hazard decals beside every door
# already behave exactly this way, and so does every bay-repeat model MTR
# ships. Making the sign symmetric would mean a second door-bay group and a
# second texture for four rectangles; it is not worth it, and no real train is
# symmetric about its own centre anyway (this one has a cab at one end).
# SIZE IS CAPPED BY THE PANEL, not chosen. The flanking panel is 0.644 m, so
# with a 30 mm bezel each side the longest whole-unit screen that fits is 9;
# 8 leaves 45 mm of stainless at each end and lands the sign at 0.49 x 0.12 m,
# a 4:1 LED strip — the aspect the real article has, and the shape a marquee
# wants. Both figures MUST be whole M units: MTR takes `Math.round(to - from)`
# as the text canvas, so a fractional size silently rounds the layout out from
# under the art. `_verify()` and the body converter's --check both guard it.
SIDE_SIGN_HALF_LEN = 4.0         # M units -> element width 8, along the car
SIDE_SIGN_Y = (25.0, 27.0)       # M units -> element height 2
SIDE_SIGN_MARGIN_M = 0.030       # donor metres of housing outside the screen
SIDE_SIGN_STANDOFF = 0.30        # M units outboard of the skin (20 mm)
# Donor z the housing is centred on: the middle of the flanking panel between
# the door opening and the bay's own edge, derived from the bay and the
# aperture rather than typed.
SIDE_SIGN_Z_M = (DOOR_BAY.za + DOOR_APERTURE_A) / 2.0


def side_skin_x(y_units):
    """M-frame |x| of the bodyside at a height in M units above the floor.

    The two knots are the body converter's own `SIDE_PROFILE` cant rail and
    waist; the converter's `--check` re-measures the sign against the real
    profile, so this stays a convenience, never the authority.
    """
    y_m = FLOOR_Y + y_units / Y_SCALE_ABOVE
    top_y, top_x = 3.400, 1.480
    waist_y, waist_x = 1.900, 1.570
    t = (y_m - top_y) / (waist_y - top_y)
    return sx(top_x + t * (waist_x - top_x))


def side_sign_skin_x():
    """M-frame |x| of the bodyside at the sign's WIDEST height.

    The side leans out as it goes down, so the bottom edge is the widest point
    the plate has to clear; taking it here means one flat plate at one x is
    proud of the skin over its whole height with no roll. r179 leans its side
    displays 6 degrees to match a much stronger tumblehome; ours is 3.1
    degrees over a 2-unit-tall plate — 0.11 units of lean — so the plate is
    left square and the standoff simply absorbs it. That also keeps the
    element out of Blockbench's Euler-order question entirely.
    """
    return side_skin_x(min(SIDE_SIGN_Y))


def side_sign_plane_x():
    """M-frame x of the text plane: outboard of the skin by the standoff."""
    return side_sign_skin_x() + SIDE_SIGN_STANDOFF


# ------------------------------------------- the interior next-stop display
#
# ⭐ ADDED 2026-07-28 (user request: "next stop displays on the interior,
# positioned and looking like the real M7").
#
# WHERE THE REAL ONE IS. The donor answers this itself: `BInterior.csv` builder
# 75 maps `destino.png` onto a 0.70 x 0.30 m panel at donor y 2.600..2.900,
# mounted on a TRANSVERSE surface (constant z = -3.800) and facing +z, i.e.
# down the saloon. On the B car that surface is the inboard end wall of the
# lavatory module (builder 74, `BAÑO.png`, z -5.955..-3.810) — a bulkhead. Our
# interior is converted from AInterior, which has NO lavatory, so the mounting
# had to move to the equivalent transverse bulkhead that A DOES have: the
# vestibule partition, over its aisle opening. That is also where the unit sits
# on the real car (over the end doors) and where `art_partition` already
# reserved a signage plate.
#
# WHAT IT LOOKS LIKE. Read straight off `destino.png` (221x79): a light grey
# housing with a near-black LED window inset in its UPPER portion (u
# 0.041..0.932, v 0.038..0.456 = 0.624 x 0.125 m of the 0.70 x 0.30 panel), a
# row of three bolt heads along the top edge, and plain housing below. The
# screen is a 5:1 wide strip — a marquee, which is why the display scrolls.
#
# Sizes are quantised so MTR's `Math.round(to - from)` gives whole pixels.
# The band this has to live in is the partition's solid head, between its
# glazed opening's head rail and the ceiling. The rail is 2 px of the partition
# art (~0.049 m), so the usable band is 3.199..3.400 — 0.20 m, or 3.3 M units.
# A 2-unit screen inside a 0.194 m housing leaves ~0.036 m of bezel all round,
# which is what makes it read as the donor's unit rather than as a sticker.
PIS_HEAD_RAIL_TOP_M = 3.199      # donor y: above the partition's head rail
PIS_SCREEN_HALF_X = 6.0          # M units -> element width 12
PIS_SCREEN_Y = (31.9, 33.9)      # M units -> element height 2
PIS_BOX_HALF_X_M = 0.434         # donor x of the drawn housing, each side
PIS_BOX_Y_M = (3.202, 3.396)     # donor y of the drawn housing, bottom/top

# The housing is a DECAL QUAD with its own texture, not pixels on the
# partition's sheet — exactly as the donor does it (destino.png is a separate
# 221x79 image for a 0.70 x 0.30 m panel, ~316 px/m, eight times the density of
# the partition art). Drawn into the partition's own texture the whole unit was
# 32 x 8 px and its bezel rounded away to nothing.
PIS_DECAL_STANDOFF = 0.12        # M units: the housing quad, off the skin
PIS_STANDOFF = 0.30              # M units: the text plane, off the skin

# The vestibule partition's thickness. It LIVES HERE rather than in the
# interior converter because two tools now need it: that converter builds the
# screen, and gen_m7_doors has to place a display element just proud of the
# skin it ends up with.
PARTITION_THICK_M = 1.0 / X_SCALE        # one M unit, skin to skin


def pis_plane_z():
    """Door-bay-local M z of the next-stop screen's plane.

    Derived from the partition the screen is mounted on, so it tracks the
    aperture and the screen thickness instead of being typed. The a-partition
    sits at the aperture's saloon edge with its saloon face at -z, so the
    element goes one skin-half plus a decal standoff further out and faces -z
    — which is a DISPLAY element's natural direction, no rotation needed.

    ONE element covers all four partitions in a car: bound to a definition
    carrying BOTH `positions` and `positionsFlipped` at both door centres, the
    flip turns it round to face the saloon on the two partitions whose saloon
    side is +z. See `bbPartition` in gen_m7_assets.py.
    """
    skin = DOOR_BAY.z(DOOR_APERTURE_A) - sx(PARTITION_THICK_M / 2.0)
    return skin - PIS_STANDOFF


# ------------------------------------------------------------------- bogies
#
# ⭐ THE KNOB THE USER TUNES. Truck centres as a fraction of the coupler-face
# length, so `bogie{1,2}Position = ±length * BOGIE_RATIO / 2` follows a resize
# with no edit and BOTH car lengths (normal and mini) get it for free.
#
#   prototype  59'6" truck centres over 85'0" between couplers = 0.700
#   shipped    0.76  — USER PREFERENCE (2026-07-28, from in-game): at the
#              prototype figure the trucks read as sitting too far under the
#              middle of the car, so they were pushed outboard toward the ends.
#              MTR's bogies are also visually shorter than a real M7 truck,
#              which makes the true-scale inset look larger than it is.
#
# Raise it to move the trucks further toward the car ends, lower it to pull
# them in. Sanity ceiling: at 1.0 the truck centre sits on the coupler face.
# Resulting positions: normal (26 blk) ±9.88, mini (15 blk) ±5.70.
BOGIE_RATIO_PROTOTYPE = 59.5 / 85.0      # 0.700 — what the real car does
BOGIE_RATIO = 0.76                       # what we ship (user preference)

BOGIE_HALF_BLOCKS = 2.0                  # mtr:bogie_1.bbmodel is z +-32 units


def bogie_position(blocks):
    """Outboard truck centre, in BLOCKS, for a car of `blocks` length."""
    return blocks * BOGIE_RATIO / 2.0


def bogie_overhang_blocks(blocks):
    """How far the truck box pokes past the coupler face (0 = clean)."""
    return max(0.0, bogie_position(blocks) + BOGIE_HALF_BLOCKS - blocks / 2.0)

# One seat unit is 0.5 m of donor z (asiento*.csv), at the car's own z scale.
SEAT_DEPTH_M = 0.5
SEAT_DEPTH_UNITS = SEAT_DEPTH_M * Z_SCALE
SEAT_BENCH_X = {"wide": -0.740, "narrow": 0.945}   # donor x of each bench centre


NORMAL = CarLayout("normal", UNITS_NORMAL, END_UNITS, WINDOW_COUNT)
MINI = CarLayout("mini", UNITS_MINI, MINI_END_UNITS, 1)

# The end bays are placed by a single-entry list at -half + units/2 and turned
# round by the flipped list; both lengths use the same rule.
NORMAL_END_Z = NORMAL.end.centres[0]     # -171
MINI_END_Z = MINI.end.centres[0]         # -89.5


# ------------------------------------------------------------------- checks

def _verify():
    """Everything above is derived; this asserts it still lands where stated."""
    assert UNITS_NORMAL == 416 and UNITS_MINI == 240
    assert (WINDOW_UNITS, DOOR_UNITS, END_UNITS) == (30, 44, 74), \
        (WINDOW_UNITS, DOOR_UNITS, END_UNITS)
    assert MINI_END_UNITS == 61, MINI_END_UNITS
    assert (WINDOW_COUNT * WINDOW_UNITS + 2 * DOOR_UNITS
            + 2 * END_UNITS) == UNITS_NORMAL
    assert WINDOW_UNITS + 2 * DOOR_UNITS + 2 * MINI_END_UNITS == UNITS_MINI
    assert NORMAL.window.centres == [-75, -45, -15, 15, 45, 75], \
        NORMAL.window.centres
    assert NORMAL.door.centres == [-112, 112], NORMAL.door.centres
    assert NORMAL.end.centres == [-171, 171], NORMAL.end.centres
    assert NORMAL.end.span(171) == (134, 208), NORMAL.end.span(171)
    assert MINI.window.centres == [0], MINI.window.centres
    assert MINI.door.centres == [-37, 37], MINI.door.centres
    assert MINI.end.centres == [-89.5, 89.5], MINI.end.centres
    assert MINI.end.span(89.5) == (59, 120), MINI.end.span(89.5)
    assert DOOR_MULTIPLIER == 24, DOOR_MULTIPLIER
    # Bogies must stay inboard of the coupler faces at both lengths, and the
    # truck must not overhang the car end (MTR's bogie_1 is ~4 blocks long).
    # The sign has to sit in the clear band on the mask, and its elements have
    # to be whole M pixels or MTR rounds the text canvas out from under them.
    assert abs(2 * SIGN_HALF_X - round(2 * SIGN_HALF_X)) < 1e-9, SIGN_HALF_X
    assert abs(sx(SIGN_HALF_X_M) - SIGN_HALF_X) < 1e-9, SIGN_HALF_X_M
    for lo, hi in (SIGN_DEST_Y, SIGN_COLOR_Y):
        assert abs((hi - lo) - round(hi - lo)) < 1e-9, (lo, hi)
        assert SIGN_BOX_Y_M[0] < (lo / Y_SCALE_ABOVE + FLOOR_Y), (lo, hi)
        assert (hi / Y_SCALE_ABOVE + FLOOR_Y) < SIGN_BOX_Y_M[1], (lo, hi)
    assert SIGN_DEST_Y[1] <= SIGN_COLOR_Y[0], "displays overlap"
    # Nothing may cross the roofline: above it the surface steps 0.17 m back to
    # the roof cap and the text would float clear of the nose.
    assert SIGN_BOX_Y_M[1] <= 3.400, "the sign runs past the roofline"
    # The sign now lives ABOVE the windshields, so it clears them IN X, not in
    # Y — it is narrower than the gap between the two screens, gaskets
    # included. (The old constraint was the cab DOOR window's gasket; that is
    # 0.15 m below the sign now and no longer binds.) The body converter's
    # --check re-derives both bounds from the donor and is the real guard.
    assert SIGN_HALF_X_M < SIGN_WINDSCREEN_INNER_X_M - 0.046, \
        "the sign is wide enough to foul the windshield gaskets"

    # The exterior side sign: whole-pixel element, and the housing has to fit
    # ENTIRELY on the door bay's car-centre flanking panel — it is drawn on the
    # side elevation, which is cropped per bay, so the bay-whole rule that
    # governs the windows governs this too.
    assert abs(2 * SIDE_SIGN_HALF_LEN - round(2 * SIDE_SIGN_HALF_LEN)) < 1e-9
    assert abs((SIDE_SIGN_Y[1] - SIDE_SIGN_Y[0])
               - round(SIDE_SIGN_Y[1] - SIDE_SIGN_Y[0])) < 1e-9
    _half_m = SIDE_SIGN_HALF_LEN / DOOR_BAY.units_per_m + SIDE_SIGN_MARGIN_M
    _lo, _hi = SIDE_SIGN_Z_M - _half_m, SIDE_SIGN_Z_M + _half_m
    assert DOOR_BAY.za >= _hi and _lo >= DOOR_APERTURE_A, \
        ("the side sign's housing runs off its panel", _lo, _hi)
    # Proud of the skin at BOTH ends of the plate, not just the widest one:
    # the side leans, so a plate that clears the bottom edge could still be
    # buried at the top if the standoff ever shrank below the lean.
    for _y in SIDE_SIGN_Y:
        assert side_sign_plane_x() > side_skin_x(_y), \
            ("the side sign is inside the skin at y", _y)
    # ...and it must clear the window head below and the cant rail above.
    assert 2.777 < FLOOR_Y + SIDE_SIGN_Y[0] / Y_SCALE_ABOVE
    assert FLOOR_Y + SIDE_SIGN_Y[1] / Y_SCALE_ABOVE < 3.400
    # One screen-length of slack must remain: at 9 units the bezels touch the
    # aperture and the bay seam, and at 10 the housing is cut by the crop.
    assert 2 * SIDE_SIGN_HALF_LEN <= 9, "the side sign no longer fits its panel"

    # The interior next-stop unit: whole-pixel element, screen inside its drawn
    # housing, and the housing inside the partition's solid band above the
    # glazed head (donor y 3.150) and below its top (3.400).
    assert abs(2 * PIS_SCREEN_HALF_X - round(2 * PIS_SCREEN_HALF_X)) < 1e-9
    assert abs((PIS_SCREEN_Y[1] - PIS_SCREEN_Y[0])
               - round(PIS_SCREEN_Y[1] - PIS_SCREEN_Y[0])) < 1e-9
    assert PIS_SCREEN_HALF_X / X_SCALE < PIS_BOX_HALF_X_M, "screen wider than its housing"
    assert PIS_BOX_Y_M[0] < PIS_SCREEN_Y[0] / Y_SCALE_ABOVE + FLOOR_Y
    assert PIS_SCREEN_Y[1] / Y_SCALE_ABOVE + FLOOR_Y < PIS_BOX_Y_M[1]
    assert PIS_HEAD_RAIL_TOP_M <= PIS_BOX_Y_M[0] and PIS_BOX_Y_M[1] <= 3.400, \
        "the next-stop housing fouls the partition's head rail or its ceiling"
    # ...and the text must stand off the housing, which stands off the skin.
    skin = DOOR_BAY.z(DOOR_APERTURE_A) - sx(PARTITION_THICK_M / 2.0)
    assert 0 < PIS_DECAL_STANDOFF < PIS_STANDOFF
    assert pis_plane_z() < skin - PIS_DECAL_STANDOFF < skin

    assert 0.4 < BOGIE_RATIO < 0.95, BOGIE_RATIO
    # MTR's bogie_1 is z +-32 units = +-2 blocks, so at ratios above
    # 1 - 4/blocks its box pokes past the coupler face. That is cosmetic
    # (couplingPadding is 0 and the gangway covers the joint), so it is
    # reported by main() rather than raised — but the normal car must stay
    # clean, since that is the one that gets looked at.
    assert bogie_overhang_blocks(BLOCKS_NORMAL) <= 0.0 + 1e-9, \
        bogie_overhang_blocks(BLOCKS_NORMAL)
    # No bay may reach past the car end.
    for bay, layout in ((CAB_BAY, NORMAL), (GANGWAY_BAY, NORMAL),
                        (CAB_BAY_MINI, MINI), (GANGWAY_BAY_MINI, MINI)):
        assert bay.units == layout.end.units, (bay.name, bay.units)
    # The truncated mini ends must keep the full end's scale exactly.
    for full, mini in ((CAB_BAY, CAB_BAY_MINI), (GANGWAY_BAY, GANGWAY_BAY_MINI)):
        assert abs(full.units_per_m - mini.units_per_m) < 1e-9
    # Seat rows must clear both the door bays and the tightest end wall.
    for layout in (NORMAL, MINI):
        d0, d1 = layout.door.span(layout.door.centres[1])
        inner_end = layout.end.span(layout.end.centres[1])[1] - CAB_BULKHEAD_INSET
        for z in layout.seat_rows:
            lo, hi = abs(z) - SEAT_DEPTH_UNITS / 2, abs(z) + SEAT_DEPTH_UNITS / 2
            assert not (lo < d1 and hi > d0), (layout.label, z, "in a door bay")
            assert hi <= inner_end + 1e-6, (layout.label, z, "past the bulkhead")
        assert layout.seat_rows == sorted(layout.seat_rows)
        assert [-z for z in layout.seat_rows_forward][::-1] == \
            layout.seat_rows_reverse, layout.seat_rows


_verify()


def main():
    print("M7 layout — %s" % ("all derived, all checked",))
    print("  donor side %.4f m over %d bays -> %.4f units/m"
          % (DONOR_SIDE_M, WINDOW_COUNT + 4, Z_SCALE))
    for layout in (NORMAL, MINI):
        print("\n%s: %d units = %d blocks" % (layout.label.upper(),
                                              layout.units, layout.blocks))
        for span in (layout.end, layout.door, layout.window):
            print("  %-7s %3g units  centres %s"
                  % (span.name, span.units,
                     ", ".join("%+g" % c for c in span.centres)))
        print("  seats   %.4g units deep, pitch %g, rows %s"
              % (SEAT_DEPTH_UNITS, layout.seat_pitch,
                 ", ".join("%+g" % z for z in layout.seat_rows)))
    print("\nbays (donor metres -> local units):")
    for bay in (WINDOW_BAY, DOOR_BAY, CAB_BAY, GANGWAY_BAY,
                CAB_BAY_MINI, GANGWAY_BAY_MINI):
        print("  %-13s %3g u   donor %+8.4f..%+8.4f   %6.3f u/m   %s"
              % (bay.name, bay.units, bay.za, bay.zb, bay.units_per_m,
                 "winding flipped" if bay.flip_winding else ""))
    print("\ndoor: aperture %+.4f..%+.4f donor, travel %.2f m -> "
          "doorZMultiplier %+d" % (DOOR_APERTURE_B, DOOR_APERTURE_A,
                                   DOOR_TRAVEL_M, DOOR_MULTIPLIER))
    print("cab bulkhead sits %.1f units inboard of the end bay's outer edge"
          % CAB_BULKHEAD_INSET)
    print("bogies: ratio %.3f (prototype %.3f)" % (BOGIE_RATIO,
                                                   BOGIE_RATIO_PROTOTYPE))
    for blocks in (BLOCKS_NORMAL, BLOCKS_MINI):
        over = bogie_overhang_blocks(blocks)
        print("  %2d blk -> +-%.2f blk%s"
              % (blocks, bogie_position(blocks),
                 "   (truck box overhangs the coupler face by %.2f blk)" % over
                 if over > 1e-9 else ""))


if __name__ == "__main__":
    main()
