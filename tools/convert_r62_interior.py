#!/usr/bin/env python3
"""The NYCT R62's SALOON INTERIOR and half-cab, as an MTR .obj model.

    python3 tools/convert_r62_interior.py             # write model + textures
    python3 tools/convert_r62_interior.py --check     # cross-check everything
    python3 tools/convert_r62_interior.py --assemble DIR   # preview OBJs

WHAT THIS WRITES
----------------
    assets/station_announcer/models/vehicle/r62_interior.obj   one `g` per part
    assets/station_announcer/models/vehicle/r62_interior.mtl
    assets/station_announcer/textures/vehicle/r62/interior/*.png   DRAWN

Pipeline order — each step reads the one before it:

    convert_openbve_r62.py     the shell + exterior textures     (FIRST)
    convert_r62_interior.py    THIS                              (SECOND)
    gen_r62_doors.py           leaves, floors, displays          (THIRD)
    gen_r62_assets.py          properties + definitions          (FOURTH)
    gen_vehicle_index.py       the shared vehicle index          (FIFTH)
    convert_openbve_r62.py --check  /  convert_r62_interior.py --check  (LAST)

READ FIRST: `R62_NOTES.md` (this car's law) and `M7_CONVERSION_NOTES.md` (the
MTR .obj loader spec — units, axes, materials, the flipped-z composition, and
the family of .obj defects that force doors, floors, doorways and DISPLAY parts
into a companion .bbmodel). None of that is re-derived here.

⭐ THE INTERIOR OBEYS THE SAME ROTATIONAL SYMMETRY AS THE SHELL
----------------------------------------------------------------
The M7's interior could be modelled full-width and placed by positions-only
lists, because that car is mirror-symmetric and its 3+2 seating made the two
sides genuinely different. The R62 is different in BOTH respects:

  * it is 180-degree ROTATIONALLY symmetric, so a `positionsFlipped` entry
    supplies the other END of the car, not the other side; and
  * its two sides are the same — longitudinal benches, same pitch, same
    colours — EXCEPT at the ends, where the half-cab is diagonally opposite.

So every bay group here models BOTH SIDES at once and rides exactly the same
position definitions the shell does (`panel`, `door`, `end1`, `end2`). Nothing
is placed by a symmetric both-lists definition; that would draw each bay twice,
inside itself. The panel bay is placed once unflipped and once flipped, which
is also what puts ONE strip map on each side of the car at opposite ends —
before the second map board doubles it to four (see THE STRIP MAP below).

WHAT THE DONOR SUPPLIES, AND WHAT IT DOES NOT
----------------------------------------------
`Cars/R62 Exterior/Interior/InteriorB.b3d` — 57 builders, 13,496 vertices, 51
photographic textures. Like the shell, **it is not parsed at build time**: what
it supplies is a table of MEASUREMENTS, and `check_against_donor()` re-parses it
on `--check` and asserts every one of them still agrees. That keeps a 13k-vertex
parse out of three tools' import path and keeps the measurements honest.

⭐ **WHY InteriorB AND NOT ONE OF THE OTHER FOUR.** The pack ships five:

    InteriorA / A1   FullCab at one end, HalfCab at the other — the LEAD cars
    InteriorB        HalfCab at BOTH ends, diagonally opposite   <- ours
    InteriorC        InteriorB with FLAT benches (BenchRed/BenchYellow)
    InteriorD1       InteriorA with flat benches

Our `r62` vehicle has a half-cab at each end (that is what the shell models), so
InteriorB is the only one whose cab arrangement matches, and it is the one with
the BUCKET seats the user's reference photos show. InteriorC is the same car
re-seated; if the flat-bench variant is ever wanted it is a seat-section swap
here, not a second model.

NO DONOR PIXEL SHIPS. Every texture is drawn, in `r62_art`'s house style, from
the palette below. The donor's own interior photographs are heavily
under-exposed (its walls sample as #403020 brown), so sampling them would have
produced a brown car; the user's reference photos and the R62's real specimen
are CREAM/EGGSHELL walls, and that is what is drawn.

WHAT WAS SIMPLIFIED, AND WHY (the honest list)
-----------------------------------------------
⭐ **THE 666-FACE BUCKET SEATS.** Donor builders 53 (SeatYellow) and 56
(SeatRed) carry 666 faces EACH. Measured, what that geometry is:

  * a longitudinal bench of 0.87 m groups, each group 6 slabs of 0.145 m;
  * each slab is a 9-quad slice of the moulded bucket contour, and the contour
    varies by **+-15 mm across a slab** — 0.22 M units, a fifth of a pixel at
    this model's texel density;
  * three slabs make one 0.435 m bucket, so a 0.87 m group is TWO SEATS.

⭐⭐ **THE 2026-07-29 CUT DREW THE SEATS AND THE USER REJECTED THEM** ("fix the
interior of the r62 to have actual seats"), and the argument that produced them
was half right in a way worth keeping written down. It reasoned: a 15 mm
scallop is a fifth of a pixel here, therefore the bucket cannot be modelled,
therefore paint it. The premise is true; the conclusion does not follow,
because the 15 mm scallop is not what a rider reads as "a seat". What he reads
is the **step from one bucket's back to the divider beside it** — 270 mm, or
four whole M units, which is one of the largest features in the saloon. The
first cut spent its budget resolving the feature that could not be seen and
none on the one that could, and a painted scallop on a flat slab reads as a
poster of a bench.

And the slab was not even whole: `sweep`'s single `solid_point` cannot wind an
L-shaped section, so the CUSHION came out facing the floor and MTR culled it.
Half the bench was missing, and every check was green — see `sweep`.

So each seat is now real geometry: **shelf, leaning back, dished pan, nose, and
a divider rib at every boundary**, eight per bay per side, in three alternating
warm tones. 1,332 donor faces become 33 quads per bay per side, the model went
from 566 to ~1,340 triangles for a whole saloon, and the paint's job is now
material rather than count.

The rest, with the measurement that justified it:

  * **The poles are 1 M unit square, not round.** The donor spends 820 faces
    (builder 35) + 480 (36) + 200 (37) on cylinders 70 mm across. 70 mm is 1.04
    M units; a cylinder at one unit across is a square with wasted vertices.
    MTR's own canon is a degenerate box inflated 0.2 — 0.4 units — which the M7
    pass measured and rejected as "a scratch" at 2.5 cm. One unit it is.
  * **No window reveal.** The lining is at |x| 1.193 and the door pocket's
    corridor starts at 1.2299 (`r62_layout.LINING_MAX_X_M`): 37 mm, half an M
    unit. The M7 reached the same conclusion from the other direction.
  * **One ceiling profile for the whole car.** The donor drops its ceiling from
    the 3.323 crown to a flat 3.157 outboard of |z| 3.633, so its ad rack covers
    only the middle 7.3 m. A bay-repeating model cannot carry a feature that
    stops mid-bay (see `check`'s bay-tiling rule), and a continuous rack is
    what makes the strip map and the lighting run the length of the car. The
    end bays therefore keep the crown too.
  * **The seat COLOUR PHASE is mirrored about the car centre, not continued.**
    The donor alternates 0.87 m groups continuously from end to end, which is
    not 180-symmetric — so a flipped panel bay lands red where the donor has
    tan. The alternation is intact and the two colours are both present in
    every bay; only the phase of the far half differs, which is invisible
    without the donor open beside it. Measured and reported by `--check`.
  * **No under-seat heater grilles, no speaker grilles, no Kawasaki builder's
    plate, no emergency-brake valve, no route-map wall panel.** The wall map
    (donor builder 26, WallMap.png) is a photographic system map, and the
    user's decision put the map ON THE CEILING RACK as a live display instead.
  * The cab is a SILHOUETTE — see THE CAB below.

⭐ THE STRIP MAP — the one genuinely new mechanism (user decision: hybrid)
--------------------------------------------------------------------------
The R62's line map lives in the ad/light band above the windows (donor builder
43, `CeilingAdsLight.png`: a five-facet sloped soffit per side, sweeping from
the crown at |x| 0.843 down to the wall top at 1.193). Two racks, one per side —
measured, not assumed.

It is built as a HYBRID:

  * the HOUSING and the station ticks are **painted** — a horizontal line with
    tick marks and dot stations, era-styled, and deliberately carrying NO real
    station names, because MTR routes are the player's, not ours;
  * a route **bullet** (ROUTE_COLOR_ROUNDED + ROUTE_NUMBER, the New York
    roundel) and a **NEXT_STATION strip** are live MTR DISPLAY elements, inset
    into painted dark wells exactly as the exterior rollsign's fields are.

Displays cannot live on an .obj (M7_CONVERSION_NOTES), so the elements are in
`r62_doors.bbmodel` and are placed by `bbPanel` — the definition the exterior
side sign already uses. `gen_r62_doors` authors TWO sets, one per side of the
car, because a map you can only see from one seat is half a map; each set rides
both position lists, so a car carries FOUR live maps.

`check_displays()` here bakes the painted housing through the .obj's placement
transform and every display element through the .bbmodel's — which compose a
flipped entry with OPPOSITE signs — and requires the text to land on the
housing in world space. Nothing in game reports a miss; the text simply hangs
on a cream wall.

The band's own face is ONE M unit inboard of the lining and THREE M units tall,
and the map board stands one unit off that again: whole units, because MTR's own
corpus has no relief between 0.3 and 1 units thick.

THE CAB — and why it exists at all
-----------------------------------
⚠️ The M7's cab is invisible from outside, because that donor's windscreens are
painted onto an opaque photographic mask. **The R62's are not.** The shell's
`draw_end_mask` marks the windshield as glazing and `open_glazing` cuts it to a
real alpha-0 aperture with a translucent pane over it, so a player on the
platform looks straight into the cab. It therefore gets built — but as a
budget silhouette, not a room: a bulkhead, the partition that separates it from
the storm-door passage, a console desk with two instrument blocks, and the
driver's seat. Everything in it is emitted with BOTH windings, because it is
seen from in front (through the windshield) and from behind (through the cab
door's own window), and MTR culls backfaces unconditionally.

FRAMES
------
Identical to the shell's, and that is load-bearing: an M coordinate written
here and the same M coordinate written into `r62_doors.bbmodel` must land in
the same place, or the strip map's text floats off its board.

    donor   openBVE metres, x across, y up from the RAIL, z along the car
    M       1 unit = 1/16 block, y = 0 at the car FLOOR; bays centred on z = 0
    OBJ     obj = (-Mx, +My, -Mz) / 16, one OBJ unit = one BLOCK
"""

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bve_csv
import convert_openbve_r62 as B      # window positions and the shell's own maps
import pixel_kit as K
import pngtool
import r62_art as A
import r62_layout as L

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")
MODEL_DIR = os.path.join(OUR_NS, "models/vehicle")

# ⭐ ITS OWN SUBDIRECTORY, DELIBERATELY. `convert_openbve_r62.py` PRUNES every
# .png in `textures/vehicle/r62` that it did not itself write — that is how a
# texture dropped during an art iteration stops shipping — and it lists the
# directory rather than walking it. Interior art in the same folder would be
# deleted by the next shell build.
TEX_DIR = os.path.join(OUR_NS, "textures/vehicle/r62/interior")
TEX_ID = "station_announcer:textures/vehicle/r62/interior"
OBJ_NAME = "r62_interior.obj"
MTL_NAME = "r62_interior.mtl"

DONOR = os.path.join(os.path.dirname(B.DONOR_EXTERIOR), "..", "..", "Cars",
                     "R62 Exterior", "Interior", "InteriorB.b3d")
DONOR = os.path.normpath(os.path.join(
    "/Users/thomasdemuth/openBVE/Train/IRT/62/Kawasaki R62/Livonia Set/"
    "1983 - 1991  R62 [Livonia Set]/Cars/R62 Exterior/Interior/InteriorB.b3d"))

sx, sy, mx, my = L.sx, L.sy, L.mx, L.my


# ====================================================================
# MEASUREMENTS — every one of these is asserted against the donor by
# `check_against_donor()` when the pack is present.
# ====================================================================

FLOOR_Y = L.FLOOR_Y                      # 1.110 — the saloon floor, our y = 0
LINING_X = L.INT_LINING_X_M              # 1.193 — the lining plane
WALL_TOP_Y = L.INT_WALL_TOP_Y            # 3.086 — where the ceiling rack starts
WALL_BAND_Y = L.INT_WALL_BAND_Y          # 1.979 — the joint over the seat backs
CROWN_Y = L.INT_CROWN_Y                  # 3.323 — the ceiling crown
CROWN_HALF_X = L.INT_CROWN_HALF_X        # 0.843 — how far the crown stays flat

DOOR_HEAD_Y = L.DOOR_HEAD_Y              # 3.056 — the top of a door opening

# The seat, straight off donor builder 56's own contour ring (see the docstring
# for how 666 faces became five segments):
#   1.193/1.979  the top of the back, at the wall
#   1.054/1.588  the back-to-cushion junction        -> 1.054 is EXACTLY the
#   0.675/1.552  the cushion's aisle edge               donor's own value, and
#   0.675/1.484  the cushion's nose                     0.675 is exactly 10.0
#   1.193/1.440  the underside, back at the wall        M units.
SEAT_TOP_Y = WALL_BAND_Y                 # the back meets the wall's own joint
SEAT_BACK_X = 1.054                      # the back's front face
SEAT_PAN_Y = 1.552                       # the cushion's top
SEAT_NOSE_X = 0.675                      # the cushion's aisle edge
SEAT_PAN_BOT_Y = 1.478                   # under the cushion
SEAT_PITCH_M = 0.435                     # one bucket — 7.13 M units
SEAT_GROUP_M = 2 * SEAT_PITCH_M          # the donor's own 0.87 m colour group
SEAT_GROUP_UNITS = 14.0                  # ...at 2 seats x 7 whole M units
BUCKET_UNITS = SEAT_GROUP_UNITS / 2.0    # ONE seat — the pitch a run aims for

# ====================================================================
# ⭐ THE MOULDED SHELL AND ITS INSERTS (2026-07-30, the SECOND seat pass)
# ====================================================================
#
# THREE CUTS, AND EACH ONE'S ERROR IS WORTH KEEPING WRITTEN DOWN.
#
# ① (2026-07-29) ONE swept slab for the whole bench with the scallops PAINTED
#   on, argued from "a 15 mm moulding cannot be drawn at 16 units per metre".
#   True about the moulding, false about the seat: a painted scallop on a flat
#   slab reads as a poster of a bench. The user rejected it.
#
# ② (2026-07-30 a.m.) individual buckets with a DIVIDER RIB at every boundary —
#   one unit wide, one unit proud, and standing a further unit above the seat
#   top, in the seat's own colour. The user rejected that too, and in the exact
#   words that name the defect: ⭐ **"there are no armrests."** A coloured
#   member standing a whole unit above the cushion, at the boundary between two
#   seats, IS an armrest — that is what an armrest is. The rib was reasoned
#   about as "the feature a rider reads", which was right, and then drawn as
#   the wrong feature.
#
# ③ THIS ONE, straight off the user's close-up of the real bench. What is
#   actually there is ONE CONTINUOUS CREAM FIBREGLASS SHELL — a moulded frame
#   that runs the length of the bench — with a COLOURED INSERT dropped into
#   each seat position: a back panel and a pan, each with cream showing all
#   round it. Between two seats the shell has a GENTLE SCALLOP RIDGE, barely
#   proud, which is part of the moulding and not a member bolted onto it. The
#   cream is the frame; the colour is the upholstery inside the frame.
#
# So the parts are now:
#
#     the shell     the same four-segment section, swept CONTINUOUSLY along the
#                   whole bench (shelf . back . pan . nose) — and drawn as
#                   CREAM with the two coloured fields inside it
#     the inserts   PAINT on that sweep: a back panel and a pan panel, each
#                   inset SEAT_BORDER_UNITS from all four of the seat's edges,
#                   so every insert has a cream border and the shelf, the
#                   nose lip and the back/pan joint stay cream
#     the ridge     RIDGE_PROUD_UNITS — HALF a unit — of cream standing off the
#                   shell at every seat boundary, following the section all the
#                   way from the wall to under the nose
#
# ⭐ HALF A UNIT, AND THE M7'S "NOTHING BETWEEN 0.3 AND 1" SURVEY DOES NOT
# APPLY. That survey is about how thick a free-standing ELEMENT may be before
# it reads as a painted rectangle. A ridge is not free-standing: it is a step
# in a surface, and its neighbours on both sides are the same surface, so what
# makes it read is the CREAM against the colour, not the depth. One unit of it
# was an armrest; half a unit is a moulding.
BACK_LEAN_UNITS = 1.5                    # the back's top leans this far wallward
PAN_DISH_UNITS = 0.6                     # ...and the pan dips this far to the rear
RIDGE_UNITS = 1.0                        # a scallop ridge is one unit along the car
RIDGE_PROUD_UNITS = 0.5                  # ...and stands HALF a unit off the shell
SEAT_BORDER_UNITS = 0.45                 # cream, all round every coloured insert

# The lean is CENTRED on the donor's own 1.054 back plane, so the seat still
# occupies the volume the donor measured — it is a leaf inside that volume
# rather than a wall across it.
SEAT_BACK_TOP_X = LINING_X - mx(1.2)     # the back's top front edge
SEAT_BACK_FOOT_X = SEAT_BACK_TOP_X - mx(BACK_LEAN_UNITS)
SEAT_PAN_REAR_Y = my(sy(SEAT_PAN_Y) - PAN_DISH_UNITS)

# ⭐ THE RIDGE'S WALL END IS BURIED, exactly as the grab bar's is. A ridge that
# stopped at the lining would show its own end cap, which faces the wall and
# which no viewpoint in the saloon can see — `check`'s winding test reports
# that, correctly, as a face MTR draws as nothing. Running it 0.2 units PAST
# the lining costs one quad less and is still well inside `LINING_MAX_X_M`.
RIDGE_WALL_X = LINING_X + mx(0.2)

# ⭐ THE COLOUR ALTERNATES PER SEAT, IN THREE. The donor changes colour every
# 0.87 m — TWO seats — and `check_against_donor` still measures that, because
# it is a true fact about the donor. It is not what the user's reference
# photographs of the real car show: every bucket is its own warm tone, in a
# repeating orange / red-orange / amber pattern, and that is what is drawn.
# Three rather than two because two adjacent tones this close read as one
# colour with a seam; the third resets the eye every bucket.
SEAT_COLOURS = ("orange", "red", "amber")

# The bench gives up two units at its doorway end to the grab bar, so a panel
# bay's bench is 54 units and its eight buckets come out at 6.75 rather than a
# whole 7. That is 15 mm a seat — below the model's own texel — and it is what
# buys the bar somewhere to stand that is not inside the door opening.
BENCH_END_INSET = 2.0

# ⭐ THE CHROME END GRAB BAR — a bent tube, wall out, down, and back to the
# wall, exactly as the photographs show it capping each bench end. Its gauge is
# 1.4 units against the overhead rail's 1.2 and the stanchion's 1.0, and all
# three are centred on POLE_AISLE_X: three DIFFERENT gauges on one centreline
# is what lets a stanchion run up through the bar's corner and on through the
# rail without a single shared plane. Same rule as the rail-vs-pole one above,
# applied to a third member.
BAR_GAUGE = 1.4
BAR_STANDOFF = 0.3                       # units the tube stands off the end plate
# The tube's inboard end is BURIED IN THE LINING, half a unit past it — a tube
# that stopped at the wall would show its own end cap, which faces the wall and
# which no viewpoint in the saloon can see. `LINING_MAX_X_M` is the corridor
# the door leaves need and 0.2 units is well inside it.
BAR_WALL_X = LINING_X + mx(0.2)

# The under-seat heater duct (donor builders 4 / 7, y 1.153..1.435). Drawn as
# ONE recessed face a unit inboard of the lining, which is what is actually
# visible of it between the cushion's nose and the floor.
DUCT_X = L.INT_BAND_X_M                  # a unit inboard of the lining
DUCT_TOP_Y = SEAT_PAN_BOT_Y

# The poles. Donor builder 37 puts floor-to-ceiling stanchions on the car's
# CENTRELINE at |z| 1.45 and 3.25 (both inside a panel bay); builder 35 puts
# more at |x| 0.655, flanking every doorway. One M unit square — see the
# docstring.
POLE_HALF = mx(0.5)                      # half a unit
POLE_AISLE_X = 0.655                     # just inboard of the cushion's nose
CENTRE_POLE_Z_M = (1.45, 3.25)           # donor z, inside the +z panel bay
RAIL_Y = 3.200                           # the overhead longitudinal grab rail
# ⭐ THE RAIL IS FATTER THAN THE STANCHION THAT MEETS IT, and that is MTR's
# burial rule rather than a proportion. At the same gauge the two share their
# x planes exactly, so every place a pole meets the rail is a z-fight — which
# `coplanar_overlaps()` reported the moment it existed. 1.2 units against 1.0
# lets the pole run up THROUGH the rail with nothing coplanar anywhere, and a
# grab rail really is the heavier member.
RAIL_HALF = mx(0.6)
RAIL_DEPTH_UNITS = 1.2

# The end grab bar's own two x planes, on the same centreline as the pole and
# the rail and at neither of their gauges (see BAR_GAUGE above).
BAR_X0 = POLE_AISLE_X - mx(BAR_GAUGE / 2.0)      #  9.0 units
BAR_X1 = POLE_AISLE_X + mx(BAR_GAUGE / 2.0)      # 10.4 units
BAR_TOP_Y = SEAT_TOP_Y                           # the top run caps the seat back
BAR_TOP_BOT_Y = my(sy(SEAT_TOP_Y) - BAR_GAUGE)
BAR_LOW_TOP_Y = my(sy(SEAT_PAN_Y) + 1.2)         # the return, over the cushion
BAR_LOW_BOT_Y = my(sy(SEAT_PAN_Y) + 1.2 - BAR_GAUGE)

# The half-cab (donor builders 48 / 17: HalfCab.PNG at +x on the +z end,
# HalfCab1.png at -x on the -z end — diagonally opposite, exactly as the
# shell's windshield is).
#
# ⭐ THE PARTITION AND THE STORM DOOR'S OWN RECESS CHEEK ARE THE SAME PLANE, AND
# TYPING 0.417 FOR ONE AND READING 0.4168 FOR THE OTHER SHIPPED A Z-FIGHT.
# They were 0.003 M units apart — 0.2 mm in world — both facing inboard, and
# overlapping over the whole height of the recess: exactly the shimmering seam
# the user photographed beside the storm door. `coplanar_overlaps` compares
# plane VALUES at 1e-6 and 0.003 sails through, which is the lesson: a
# tolerance-based check cannot see two surfaces that are meant to be one and
# are not. The fix is to make them one — the partition beside the storm door IS
# the recess's cab-side cheek — and then to let the two DIVIDE the plane
# between them rather than both draw it (see `build_cab` and `storm_door`).
CAB_INNER_X = L.STORM_DOOR_HALF_X        # the partition beside the storm door
CAB_BULKHEAD_Z = 6.340                   # donor z — the wall facing the saloon
# The donor's cab ceiling is at 3.157, 71 mm above the saloon's wall top. Ours
# is the wall top instead, less 10 mm: a cab that stopped 71 mm short of the
# ceiling would leave a slot over its own bulkhead that a rider in the saloon
# can see straight through, and closing it would put the cab's ceiling in the
# same plane as the ad band's lip. 71 mm is 1.2 M units and nothing in the cab
# is that tall.
CAB_TOP_Y = L.INT_WALL_TOP_Y - 0.010

# The car's inner end: the transverse wall beside the storm door, and the storm
# door itself (donor builders 50 / 38 / 41 / 39).
# ⭐ THE LINING STOPS WHERE THE BODYSIDE DOES, and that is not a choice: the
# side elevation both models read spans donor z -7.1543..+7.1543 (u 1..0), so a
# lining quad taken any further has no texture under it — its u leaves the
# crop, and once the crop is an atlas cell it lands on some OTHER texture.
# `atlas_straddles` caught exactly that on the first build, with the end wall
# at the donor's own 7.216. Beyond this line the shell is nose, not side.
END_WALL_Z = L.ROOF_FRONT_Z              # 7.1543
STORM_Z = L.STORM_DOOR_Z                 # 7.277 — the leaf's own plane
STORM_HALF_X = L.STORM_DOOR_HALF_X       # 0.417
STORM_TOP_Y = L.STORM_DOOR_TOP_Y         # 3.067
STORM_WINDOW_X = L.STORM_DOOR_WINDOW_X
STORM_WINDOW_Y = L.STORM_DOOR_WINDOW_Y
# The interior face of the storm door stands off the shell's own leaf quad.
# Coplanar single-sided pairs are safe in game (MTR culls) but shimmer in any
# renderer that does not, which is the only way this model is looked at before
# it ships — the M7 learned that on its vestibule partitions.
STORM_STANDOFF_M = 0.010

# The doorway's own reveal, seen from INSIDE. The lining stops at |x| 1.193 and
# the sliding leaf's inboard plane is at 1.2399, so without a return the 47 mm
# between them is a slot straight through the car side: the shell's skin is
# single-sided and invisible from within. The return stops SHORT of the leaf,
# because the leaf slides through exactly that gap.
REVEAL_X = L.LINING_MAX_X_M              # 1.2299

# ...and it stops exactly on the corridor the exterior pass reserved, rather
# than 5 mm short of the leaf. The 10 mm that leaves uncovered is 0.15 M units,
# under a millimetre in world, and taking it would put interior geometry inside
# a corridor another agent's `--check` guards.
# ⭐ WHERE THE DOOR BAY'S FLOOR HAS TO STOP. The shell's threshold plate has its
# top face at the sill, spanning from `DONOR_HALF_X - 0.12` outward; a floor
# that overlapped it would put two coplanar quads in the doorway. Derived from
# the shell rather than typed, so the two cannot drift.
DOOR_FLOOR_HALF = L.DONOR_HALF_X - 0.12


# ====================================================================
# TEXTURE SPACE
# ====================================================================
#
# Densities are the shell's, so a texel is the same size inside and outside:
#   wall elevation   768 x 104 over 14.31 m x 1.976 m  ->  53.7 / 52.6 px/m
# Everything else is derived from the geometry it covers through `px()`.

WALL_TEX_W = 768
WALL_TEX_H = 104
PX_PER_UNIT = 4.0                        # 64 px per block on the small pieces


def px(units, quantum=2):
    """M units -> a texture dimension, rounded to a multiple of `quantum`."""
    n = int(round(units * PX_PER_UNIT / quantum)) * quantum
    return max(quantum, n)


def wall_px(z_m, side=+1):
    """donor z -> column in the interior wall elevation."""
    return L.u_of_z(z_m, side) * WALL_TEX_W


def wall_v(y_m):
    """donor y -> v on the wall elevation (image convention, 0 = the top)."""
    return min(1.0, max(0.0, (WALL_TOP_Y - y_m) / (WALL_TOP_Y - FLOOR_Y)))


def wall_row(y_m):
    return wall_v(y_m) * WALL_TEX_H


# ====================================================================
# PALETTE
# ====================================================================
#
# The R62's saloon in the user's reference era (mid-1990s to early-2000s):
# cream/eggshell walls and ceiling, ORANGE and YELLOW-TAN bucket seats in
# alternating groups, a dark speckled floor, stainless poles and door
# surrounds. Flat MTR house tones — a base, one highlight and one shade per
# material, and NO baked lighting: INTERIOR parts render CUTOUT_BRIGHT, so a
# painted gradient reads as dirt rather than as light.

CLEAR = (0, 0, 0, 0)                     # alpha 0 — a real hole in a CUTOUT

# ⭐ THE PALETTE ITSELF LIVES IN `r62_art`, NOT HERE. `gen_r62_doors.py` paints
# the SALOON FACE of every sliding leaf, and a closed door has to read as part
# of the wall it is set into — the same argument that put the belt-rail heights
# there for the outboard face. These are local names for readability only.
CREAM_HI, CREAM, CREAM_LO = A.INT_CREAM_HI, A.INT_CREAM, A.INT_CREAM_LO
CREAM_SEAM, WALL_SKIRT = A.INT_CREAM_SEAM, A.INT_SKIRT
CEILING, CEILING_LO, CEILING_SEAM = (A.INT_CEILING, A.INT_CEILING_LO,
                                     A.INT_CEILING_SEAM)
LAMP, LAMP_EDGE = A.INT_LAMP, A.INT_LAMP_EDGE
BAND, BAND_LIP, AD_INK, AD_TINT = (A.INT_BAND, A.INT_BAND_LIP, A.INT_AD_INK,
                                   A.INT_AD_TINT)
MAP_CASE, MAP_FACE, MAP_LINE = A.INT_MAP_CASE, A.INT_MAP_FACE, A.INT_MAP_LINE
MAP_TICK, MAP_DOT = A.INT_MAP_TICK, A.INT_MAP_DOT
MAP_FIELD, MAP_FIELD_HI = A.INT_MAP_FIELD, A.INT_MAP_FIELD_HI
FLOOR, FLOOR_HI, FLOOR_LO = A.INT_FLOOR, A.INT_FLOOR_HI, A.INT_FLOOR_LO
THRESHOLD = A.INT_THRESHOLD
SEAT_ORANGE, SEAT_ORANGE_HI, SEAT_ORANGE_LO = (A.INT_SEAT_ORANGE,
                                               A.INT_SEAT_ORANGE_HI,
                                               A.INT_SEAT_ORANGE_LO)
SEAT_TAN, SEAT_TAN_HI, SEAT_TAN_LO = (A.INT_SEAT_TAN, A.INT_SEAT_TAN_HI,
                                      A.INT_SEAT_TAN_LO)
SEAT_SHADOW = A.INT_SEAT_SHADOW

# ⭐ THE THIRD BUCKET TONE, THE SEAT SHELL AND THE UNDER-SEAT CABINET LIVE HERE
# AND NOT IN `r62_art`. That module's interior palette exists because
# `gen_r62_doors.py` paints the saloon face of the sliding leaves and has to
# match the lining; a door leaf has no seat on it, no shell round it and no
# cabinet under it, so nothing else in the pipeline can ever need these. Two
# agents own the other R62 tools this pass and a shared-module edit is the one
# change that would collide.
SEAT_MID = (214, 110, 44)                # the plain orange between red and amber
SEAT_MID_HI = (238, 144, 78)
SEAT_MID_LO = (166, 76, 28)

# ⭐ THE SEAT SHELL'S CREAM IS NOT THE LINING'S. Both are "cream", and if they
# were the same value the bench would dissolve into the wall behind it at every
# viewing angle — INTERIOR renders CUTOUT_BRIGHT, so there is no shading to
# separate two surfaces that share a colour. The real bench's fibreglass is a
# shade warmer and greyer than the painted lining, which is exactly the
# separation the model needs; this is that difference, drawn rather than lit.
SHELL = (214, 208, 192)                  # the moulded frame itself
SHELL_HI = (236, 231, 218)               # its lit crown — the nose lip, a ridge
SHELL_LO = (186, 180, 165)               # the shelf, which lies in the back's
SHELL_EDGE = (156, 151, 138)             # ...and a moulded joint line

DUCT_FACE = (46, 44, 42)                 # the recessed under-seat cabinet
DUCT_LIP = (72, 69, 65)                  # its lip, under the cushion
DUCT_SEAM = (30, 29, 27)
DUCT_LOUVRE = (62, 59, 56)               # the heater grille's louvres

# The three bucket tones, in the order a run cycles them. `red` and `amber` are
# the donor's own SeatRed / SeatYellow; `orange` is the tone between them.
SEAT_TONES = {
    "orange": (SEAT_MID, SEAT_MID_HI, SEAT_MID_LO),
    "red": (SEAT_ORANGE, SEAT_ORANGE_HI, SEAT_ORANGE_LO),
    "amber": (SEAT_TAN, SEAT_TAN_HI, SEAT_TAN_LO),
}

CAB_WALL, CAB_WALL_HI, CAB_DARK = (A.INT_CAB_WALL, A.INT_CAB_WALL_HI,
                                   A.INT_CAB_DARK)
CAB_DESK, CAB_GAUGE, CAB_SCREEN = (A.INT_CAB_DESK, A.INT_CAB_GAUGE,
                                   A.INT_CAB_SCREEN)

# The stainless the poles, door surrounds and kick plates are made of comes
# straight from the shell's palette, so a pole seen through an open door is the
# same metal as the car it is bolted into.
METAL = A.POLISH_LO
METAL_HI = A.POLISH
METAL_LO = A.STEEL_MID
DARK = A.BLACK_BAND


# ====================================================================
# MESH MODEL
# ====================================================================

class Group:
    """One MTR part: a named bag of faces, each face carrying its material."""

    def __init__(self, name):
        self.name = name
        self.faces = []                  # (material, [(x, y, z, u, v), ...])

    def add(self, material, verts, two_sided=False):
        vs = list(verts)
        self.faces.append((material, vs))
        if two_sided:
            self.faces.append((material, list(reversed(vs))))

    def bounds(self):
        if not self.faces:
            return None
        pts = [v for _m, vs in self.faces for v in vs]
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
    """Newell's method — right for the slightly non-planar soffit quads."""
    nx = ny = nz = 0.0
    n = len(vs)
    for i in range(n):
        x0, y0, z0 = vs[i][0], vs[i][1], vs[i][2]
        x1, y1, z1 = vs[(i + 1) % n][0], vs[(i + 1) % n][1], vs[(i + 1) % n][2]
        nx += (y0 - y1) * (z0 + z1)
        ny += (z0 - z1) * (x0 + x1)
        nz += (x0 - x1) * (y0 + y1)
    return (nx, ny, nz)


def quad(group, material, verts, outward, two_sided=False):
    """Emit one polygon, guaranteed to face `outward`.

    ⭐ THE WINDING IS COMPUTED, NOT ORDERED BY HAND — the shell's rule, and it
    matters more in here: an interior is a room, so its faces point in every
    direction and there is no "outward" to eyeball. Hand the direction it is
    meant to face and let the Newell normal settle the vertex order. `check`'s
    saloon-viewpoint test is the independent second opinion.
    """
    vs = list(verts)
    n = face_normal(vs)
    dot = sum(a * b for a, b in zip(n, outward))
    if abs(dot) < 1e-12 and max(abs(c) for c in n) < 1e-9:
        return
    group.add(material, vs[::-1] if dot < 0 else vs, two_sided=two_sided)


def quad_x(group, material, x_m, z0, z1, y_top, y_bot, uv, outward_x,
           two_sided=False):
    """A quad in a constant-x plane, corners (z0,top) (z1,top) (z1,bot) (z0,bot)."""
    x = sx(x_m)
    pts = ((z0, y_top), (z1, y_top), (z1, y_bot), (z0, y_bot))
    quad(group, material,
         [(x, sy(y), z, uv[i][0], uv[i][1]) for i, (z, y) in enumerate(pts)],
         (outward_x, 0.0, 0.0), two_sided)


def quad_z(group, material, z, corners_xy, uv, outward_z, two_sided=False):
    """A quad in a constant-z plane; `corners_xy` in donor metres."""
    quad(group, material,
         [(sx(x), sy(y), z, uv[i][0], uv[i][1])
          for i, (x, y) in enumerate(corners_xy)], (0.0, 0.0, outward_z),
         two_sided)


def quad_y(group, material, y_m, corners_xz, uv, outward_y, two_sided=False):
    """A quad in a constant-y plane; `corners_xz` is (donor x, M z)."""
    y = sy(y_m)
    quad(group, material,
         [(sx(x), y, z, uv[i][0], uv[i][1])
          for i, (x, z) in enumerate(corners_xz)], (0.0, outward_y, 0.0),
         two_sided)


def box(group, tex, x0, x1, y0, y1, z0, z1, two_sided=False, skip=()):
    """An axis-aligned box in donor metres (x, y) and M units (z).

    Takes a `Tex`, not a material name, and maps its own 0..1 through
    `Tex.uv` — every atlased coordinate has to, and a box is where it is
    easiest to forget.

    ⭐ `skip` OMITS FACES THAT CANNOT BE SEEN, and it is not an optimisation.
    A pole's top is buried in the ceiling and its bottom in the floor; a grab
    rail's two ends butt against the next bay's rail, so drawing them puts two
    coplanar quads at every seam down the car. MTR's own stock omits buried
    faces for exactly these two reasons (the conduit pipe's rule, arrived at in
    this repo the hard way), and `check`'s viewpoint test correctly reports an
    unviewable face as one MTR will draw as nothing at all.
    """
    material = tex.material
    c = [tex.uv(0.0, 0.0), tex.uv(1.0, 0.0), tex.uv(1.0, 1.0), tex.uv(0.0, 1.0)]
    xa, xb = min(x0, x1), max(x0, x1)
    ya, yb = min(y0, y1), max(y0, y1)
    za, zb = min(z0, z1), max(z0, z1)
    if "xmax" not in skip:
        quad_x(group, material, xb, za, zb, yb, ya, c, +1.0, two_sided)
    if "xmin" not in skip:
        quad_x(group, material, xa, za, zb, yb, ya, c, -1.0, two_sided)
    if "up" not in skip:
        quad_y(group, material, yb, [(xa, za), (xa, zb), (xb, zb), (xb, za)],
               c, +1.0, two_sided)
    if "down" not in skip:
        quad_y(group, material, ya, [(xa, za), (xa, zb), (xb, zb), (xb, za)],
               c, -1.0, two_sided)
    if "zmax" not in skip:
        quad_z(group, material, zb, [(xa, ya), (xb, ya), (xb, yb), (xa, yb)],
               c, +1.0, two_sided)
    if "zmin" not in skip:
        quad_z(group, material, za, [(xa, ya), (xb, ya), (xb, yb), (xa, yb)],
               c, -1.0, two_sided)


# ====================================================================
# TEXTURES
# ====================================================================
#
# Every texture is a cell in ONE atlas. MTR makes a draw batch per
# (group x material), so the alternative is ~20 batches per car for a saloon
# nobody looks at from outside. The price is that EVERY uv written for an
# atlased texture must go through `Tex.uv` — a raw 0..1 coordinate silently
# addresses the whole sheet instead, landing on some other texture — which is
# what `atlas_straddles()` in `check` exists to catch. That mistake is exactly
# the one the M7's interior shipped once.

ATLAS_PAD = 4


class Tex:
    """A texture the model can name: a crop, a cell in the atlas, or both."""

    def __init__(self, name, rows, src_w=None, x0=0, x1=None):
        self.name = name
        self.rows = rows
        self.src_w = src_w               # for crops: the ORIGINAL width
        self.x0 = x0
        self.x1 = x1 if x1 is not None else (src_w or len(rows[0]))
        self.atlas = None                # (u0, v0, du, dv) once packed
        self.atlas_name = None

    @property
    def w(self):
        return len(self.rows[0])

    @property
    def h(self):
        return len(self.rows)

    def uv(self, u, v):
        """A (u, v) in this texture's OWN space -> what the model must write."""
        if self.src_w is not None:
            u = (u * self.src_w - self.x0) / float(self.x1 - self.x0)
        if self.atlas is not None:
            u0, v0, du, dv = self.atlas
            return (u0 + u * du, v0 + v * dv)
        return (u, v)

    @property
    def material(self):
        return "r62_" + (self.atlas_name or self.name)

    @property
    def map_kd(self):
        return "%s/%s.png" % (TEX_ID, self.atlas_name or self.name)


def pack_atlas(members, name="int_atlas", width=1024):
    """Shelf-pack `members` into one image, filling each cell's uv remap.

    Cells are padded with replicated edge pixels so mipmapping cannot pull a
    neighbour's colour across a seam — the classic atlas artefact, and the only
    reason atlasing is riskier than one file per texture.
    """
    order = sorted(members, key=lambda t: -t.h)
    for t in order:
        need = t.w + 2 * ATLAS_PAD
        if need > width:
            width = 1 << (need - 1).bit_length()
    shelves, placed = [], []
    for t in order:
        need_w, need_h = t.w + 2 * ATLAS_PAD, t.h + 2 * ATLAS_PAD
        for shelf in shelves:
            if shelf[1] >= need_h and shelf[2] + need_w <= width:
                placed.append((t, shelf[2], shelf[0]))
                shelf[2] += need_w
                break
        else:
            y = shelves[-1][0] + shelves[-1][1] if shelves else 0
            shelves.append([y, need_h, need_w])
            placed.append((t, 0, y))
    height = shelves[-1][0] + shelves[-1][1] if shelves else 1
    height = 1 << (height - 1).bit_length()
    rows = [[(0, 0, 0, 0)] * width for _ in range(height)]
    for t, cx, cy in placed:
        x0, y0 = cx + ATLAS_PAD, cy + ATLAS_PAD
        for dy in range(-ATLAS_PAD, t.h + ATLAS_PAD):
            src = t.rows[min(t.h - 1, max(0, dy))]
            for dx in range(-ATLAS_PAD, t.w + ATLAS_PAD):
                rows[y0 + dy][x0 + dx] = src[min(t.w - 1, max(0, dx))]
        t.atlas = (x0 / float(width), y0 / float(height),
                   t.w / float(width), t.h / float(height))
        t.atlas_name = name
    return Tex(name, rows)


# ====================================================================
# DRAWN ART
# ====================================================================

def canvas(w, h, colour=CLEAR):
    return K.canvas(w, h, colour)


def noise(x, y, seed):
    """A deterministic 0..1 hash — a converter that draws a different floor
    every run is a converter whose output cannot be diffed."""
    h = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def speckle(rows, hi, lo, density=0.16, seed=1, cell=2):
    for y in range(0, len(rows), cell):
        for x in range(0, len(rows[0]), cell):
            n = noise(x // cell, y // cell, seed)
            if n < density:
                K.rect(rows, x, y, x + cell, y + cell, hi)
            elif n > 1.0 - density:
                K.rect(rows, x, y, x + cell, y + cell, lo)


# --- the lining ------------------------------------------------------------
#
# ⭐ ONE ELEVATION FOR THE WHOLE CAR SIDE, CROPPED PER BAY — and it reads the
# SAME u map as the shell's, so an interior window lands behind its exterior
# one by construction rather than by a second measurement. The M7 learned that
# the hard way: its typed WINDOW_U table had already drifted 50 mm from the
# body's own panes before the two were tied together.

APERTURE_INSET_Z = 0.070                 # donor metres, each end
APERTURE_INSET_Y = 0.055                 # ...and top and bottom


def apertures():
    """Every lining aperture, as (name, donor za, zb, y_lo, y_hi).

    DERIVED FROM THE SHELL. `convert_openbve_r62.ELEVATION_PANES` is where a
    window is; this insets it, so the lining reads as the window's interior
    frame and there is no angle from which a ray through the exterior aperture
    slips past the lining's edge into the wall cavity — where the door pocket
    is.
    """
    out = []
    for (name, za, zb, y_lo, y_hi, _r) in B.ELEVATION_PANES:
        out.append((name,
                    za + APERTURE_INSET_Z, zb - APERTURE_INSET_Z,
                    y_lo + APERTURE_INSET_Y, y_hi - APERTURE_INSET_Y))
    return out


def art_wall(keep=None):
    """The saloon lining, drawn once for the whole car side and then sliced.

    `keep` is a set of pane names; None draws them all. A bay passes what the
    shell's own `panes_in()` gave it — ⭐ A BAY MAY NEVER CARRY HALF A WINDOW,
    the same rule and the same reason as outside: half an alpha-0 aperture is a
    gash in the wall, not a window.
    """
    rows = canvas(WALL_TEX_W, WALL_TEX_H, CREAM)
    r = lambda y: int(round(wall_row(y)))

    K.hband(rows, 0, r(3.030), CREAM_HI)               # under the ceiling rack
    K.hline(rows, r(3.030), CREAM_SEAM)
    K.hband(rows, r(WALL_BAND_Y), r(SEAT_TOP_Y - 0.001), CREAM_LO)
    K.hline(rows, r(WALL_BAND_Y), CREAM_SEAM)          # the joint over the seats
    K.hband(rows, r(SEAT_PAN_BOT_Y), WALL_TEX_H, WALL_SKIRT)

    # Panel joints on the bay boundaries, which is where a real car's sheets
    # are butted — and drawn both sides of the elevation so that a crop's own
    # edge always ends on one.
    for z in (L.DONOR_BOUNDS[1], L.DONOR_BOUNDS[2], L.DONOR_BOUNDS[3],
              -L.DONOR_BOUNDS[1], -L.DONOR_BOUNDS[2], -L.DONOR_BOUNDS[3]):
        K.vline(rows, wall_px(z), 0, r(WALL_BAND_Y), CREAM_SEAM)

    for (name, za, zb, y_lo, y_hi) in apertures():
        if keep is not None and name not in keep:
            continue
        x0, x1 = sorted((wall_px(za), wall_px(zb)))
        y0, y1 = wall_row(y_hi), wall_row(y_lo)
        # The surround is drawn OUTSIDE the hole, so the hole is exactly the
        # aperture and the frame reads as the window's interior trim standing
        # in front of the shell's glass.
        K.rrect(rows, x0 - 4, y0 - 4, x1 + 4, y1 + 4, 6, METAL_LO)
        K.rrect(rows, x0 - 3, y0 - 3, x1 + 3, y1 + 3, 5, METAL)
        K.rect(rows, x0 - 3, y0 - 4, x1 + 3, y0 - 2, METAL_HI)
        K.rrect(rows, x0, y0, x1, y1, 3, CLEAR)        # the real hole

    # ⭐ THE BACK OF THE SIDE ROLLSIGN. The +x panel's glass stops under the
    # sign box (`rollsign_glass_top_m`), and what fills the gap on the inside is
    # the box's own lit rear panel — donor builders 30/31, at |x| 1.196, y
    # 2.605..2.889. It is the one warm thing in the saloon, and drawing it here
    # costs nothing: it is bay-local content on the crop that already exists.
    sz0, sz1 = sorted((wall_px(L.SIGN_BOX_Z_M[0]), wall_px(L.SIGN_BOX_Z_M[1])))
    sy0, sy1 = wall_row(2.889), wall_row(2.605)
    K.rect(rows, sz0, sy0 - 2, sz1, sy1 + 2, METAL_LO)
    K.rect(rows, sz0 + 2, sy0, sz1 - 2, sy1, (250, 236, 198))
    for i in range(1, 6):                              # the curtain's own bars
        x = sz0 + (sz1 - sz0) * i / 6.0
        K.vline(rows, x, sy0, sy1, (226, 208, 166))
    return rows


# --- the ceiling and its ad band ------------------------------------------

def ceiling_section():
    """The ceiling, walked from the band's top inboard to the crown.

    Two segments: a nearly-flat soffit and the flat crown. The donor's five
    facets are a smooth cove; two is what survives at 16 units per metre, and
    a stepped cove is what MTR's own stock has.
    """
    return [(L.INT_BAND_X_M, L.INT_BAND_Y_M[1], 0.00),
            (0.900, CROWN_Y - 0.013, 0.28),
            (0.000, CROWN_Y, 1.00)]


def band_section():
    """The ad / strip-map band: the lip under it, then its face.

    v 0 is the outboard edge of the lip (against the lining) and v 1 the top of
    the band, which is the order `sweep` walks it in. The lip is a full M unit
    deep — it IS the band's thickness — so the band reads as a box screwed to
    the top of the wall rather than as a painted stripe.
    """
    return [(LINING_X, WALL_TOP_Y, 0.00),
            (L.INT_BAND_X_M, WALL_TOP_Y, 0.22),
            (L.INT_BAND_X_M, L.INT_BAND_Y_M[1], 1.00)]


def art_ceiling():
    """u runs across ONE side's section, v along the bay — constant in v on
    purpose: a lengthwise feature would repeat at every bay seam.

    The soffit's share of u is the section's own v breakpoint, read out of
    `ceiling_section()` rather than typed, so the two cannot drift apart.
    """
    section = ceiling_section()
    w = px(profile_units(section))
    rows = canvas(w, 12, CEILING)
    edge = int(round(section[1][2] * w))
    K.rect(rows, 0, 0, edge, 12, CEILING_LO)            # the soffit
    K.rect(rows, edge, 0, edge + 1, 12, CEILING_SEAM)
    # ⭐ THE FLUORESCENT STRIP IS PAINT, NOT A HOUSING. The donor hangs a real
    # one in the cove (builders 22/42, y 3.086..3.157), and the M7 built its
    # own as swept boxes — but that car had a 1.5-unit-deep cove to hang them
    # in and this one has the ad band there instead. INTERIOR renders
    # fullbright, so a painted strip and a modelled one are the same brightness
    # in game; the only thing a box would add is a silhouette, and there is no
    # room for one between the band's top and the crown.
    K.rect(rows, 1, 0, max(2, edge - 1), 12, LAMP)
    K.rect(rows, 1, 0, max(2, edge - 1), 1, LAMP_EDGE)
    K.rect(rows, 1, 11, max(2, edge - 1), 12, LAMP_EDGE)
    return rows


def art_band(units, kind):
    """The band's face and lip: generic ad cards, and the lip beneath them.

    v 0..0.22 is the lip (seen from below), 0.22..1 the face. The cards are
    drawn as neutral blocks — this mod does not put readable advertising in a
    Minecraft train — and they repeat at a pitch that divides the bay, so a
    card is never cut in half at a seam.
    """
    w, h = px(units), px(L.INT_BAND_UNITS * 3.0)
    rows = canvas(w, h, BAND)
    lip = int(round(h * 0.22))
    K.rect(rows, 0, 0, w, lip, BAND_LIP)
    K.hline(rows, lip, CEILING_SEAM)
    if kind != "door":
        # Generic cards at a pitch that DIVIDES the bay, so a card is never cut
        # in half at a seam. Neutral blocks and three rules of type: this mod
        # does not put readable advertising inside a Minecraft train.
        cards = max(1, int(round(units / 14.0)))
        for i in range(cards):
            x0 = int(w * i / float(cards)) + 4
            x1 = int(w * (i + 1) / float(cards)) - 4
            if x1 - x0 < 8:
                continue
            K.rect(rows, x0, lip + 3, x1, h - 3, CEILING)
            K.rect(rows, x0, lip + 3, x1, lip + 4, CEILING_SEAM)
            K.rect(rows, x0 + 3, lip + 6, x1 - 3, int((lip + h) / 2), AD_TINT)
            for n in range(3):
                row = int((lip + h) / 2) + 3 + n * 3
                if row + 1 < h - 4:
                    K.rect(rows, x0 + 3, row, x1 - 3 - n * 4, row + 1, AD_INK)
    return rows


MAP_PX_PER_UNIT = 8.0                    # the strip map is read close up


def art_map(units):
    """⭐ THE STRIP MAP'S PAINTED FACE.

    Three fields, laid out by `r62_layout.map_*()` so the pixels a player sees
    and the canvas MTR lays text into are the SAME rectangle — MTR rounds a
    display element's size to whole model pixels and uses that integer as the
    canvas, so anything drawn from the un-snapped rectangle sits off-centre in
    its own window.

      * a dark well for the route bullet, at the car-centre end;
      * a dark well for the NEXT_STATION strip beside it;
      * and the PAINTED map: a route line with tick marks and station dots,
        deliberately generic. There are no station names on it, because the
        stations belong to whoever builds the railway.
    """
    z0, z1, y0, y1 = L.map_board()
    w = int(round(units * MAP_PX_PER_UNIT))
    h = int(round((y1 - y0) * MAP_PX_PER_UNIT))

    def to_px(a, b):
        return (int(round((a - z0) / (z1 - z0) * w)),
                int(round((b - z0) / (z1 - z0) * w)))

    rows = canvas(w, h, MAP_FACE)
    K.rect(rows, 0, 0, w, 2, MAP_CASE)                   # the board's own frame
    K.rect(rows, 0, h - 2, w, h, MAP_CASE)
    K.rect(rows, 0, 0, 2, h, MAP_CASE)
    K.rect(rows, w - 2, 0, w, h, MAP_CASE)

    for field in (L.map_bullet(), L.map_text()):
        fx0, fx1 = to_px(field[0], field[1])
        K.rect(rows, fx0 - 1, 1, fx1 + 1, h - 1, MAP_FIELD_HI)
        K.rect(rows, fx0, 2, fx1, h - 2, MAP_FIELD)

    # ⭐ THE PAINTED MAP HAS TO CARRY ITS OWN CONTRAST. The first cut drew white
    # station dots on a cream card, and at 8 px per M unit a 250-on-238 dot is
    # simply not there — the whole map read as a row of dark specks. A station
    # is therefore a DARK ring with a light centre, which is the way every real
    # strip map draws one and the only way it survives this few pixels.
    tx0, tx1 = to_px(L.map_ticks()[0], L.map_ticks()[1])
    line = int(round(h * 0.62))
    K.rect(rows, tx0 + 2, line, tx1 - 2, line + 3, MAP_LINE)   # the route line
    stops = 9
    for i in range(stops):
        x = tx0 + 6 + int(round((tx1 - tx0 - 13) * i / float(stops - 1)))
        terminal = i in (0, stops - 1)
        K.rect(rows, x - 1, line - 4, x + 2, line, MAP_TICK)   # the tick stem
        r = 4 if terminal else 3
        K.rect(rows, x - r, line - 4 - 2 * r, x + r + 1, line - 4, MAP_LINE)
        K.rect(rows, x - r + 1, line - 3 - 2 * r, x + r,
               line - 5, MAP_DOT)
        if terminal or i == stops // 2:                  # an interchange stop
            K.rect(rows, x - 2, line + 3, x + 3, line + 7, MAP_TICK)
            K.rect(rows, x - 1, line + 4, x + 2, line + 6, MAP_DOT)
    return rows


# --- the floor -------------------------------------------------------------

def art_floor(w, h):
    rows = canvas(w, h, FLOOR)
    speckle(rows, FLOOR_HI, FLOOR_LO, density=0.18, seed=7)
    return rows


def art_threshold(w, h):
    """The doorway's floor: the same speckle with a metal edge strip each side,
    which is what a real vestibule has and what tells a rider where to stand."""
    rows = art_floor(w, h)
    for x0, x1 in ((0, max(2, w // 12)), (w - max(2, w // 12), w)):
        K.rect(rows, x0, 0, x1, h, THRESHOLD)
        K.rect(rows, x0, 0, x1, 1, METAL_LO)
    return rows


# --- the seats -------------------------------------------------------------

def with_v(points):
    """(|x|, donor y) pairs -> (|x|, y, v), v proportional to ARC LENGTH.

    Typed v values drift the moment a section is re-shaped — the old bench's
    were 0.09 / 0.40 / 0.66 / 0.71 against a contour that no longer exists —
    and a v that does not track the fold puts the paint's own fold lines
    somewhere other than the geometry's. Measuring is free and cannot drift.
    """
    lengths = [0.0]
    for a, b in zip(points, points[1:]):
        lengths.append(lengths[-1]
                       + math.hypot(sx(b[0] - a[0]), sy(b[1]) - sy(a[1])))
    total = lengths[-1] or 1.0
    return [(p[0], p[1], d / total) for p, d in zip(points, lengths)]


def bucket_section():
    """ONE moulded bucket, walked from the wall to under the cushion's nose.

    ⭐ WALKED AROUND THE MATERIAL, so it needs no `solid_point` — see `sweep`.
    Four segments:

        shelf . back (leaning) . cushion (dished) . nose
    """
    return with_v([(LINING_X, SEAT_TOP_Y),
                   (SEAT_BACK_TOP_X, SEAT_TOP_Y),
                   (SEAT_BACK_FOOT_X, SEAT_PAN_REAR_Y),
                   (SEAT_NOSE_X, SEAT_PAN_Y),
                   (SEAT_NOSE_X, SEAT_PAN_BOT_Y)])


def duct_section():
    """The under-seat heater duct: a recessed face, a unit inboard of the
    lining, running the full bay behind the cantilevered bench."""
    return [(LINING_X, DUCT_TOP_Y, 0.00),
            (DUCT_X, DUCT_TOP_Y, 0.25),
            (DUCT_X, FLOOR_Y, 1.00)]


def mix(a, b, t):
    """Blend two palette colours. A bucket needs four tones, not three: base,
    a lit crown, a shaded flank and — the one the palette has no name for — the
    dish itself, which is base seen from inside a hollow."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


SEAT_PX_PER_UNIT = 8.0                   # twice the model's general density


def seat_px(units):
    """M units -> a seat texture dimension. ⭐ THE SEAT NEEDS ITS OWN DENSITY:
    the cream border round an insert is 0.45 units, which at the model's usual
    4 px per unit is not two pixels — and a border that rounds to one pixel is
    a border that disappears at the first mip level."""
    return max(2, int(round(units * SEAT_PX_PER_UNIT / 2.0)) * 2)


def art_bucket(base, hi, lo):
    """ONE seat position on the moulded shell. u runs across the seat, v walks
    `bucket_section`.

    ⭐ THE CANVAS IS CREAM AND THE COLOUR IS DROPPED INTO IT — that is the whole
    of the 2026-07-30 second pass, and it is the opposite of what the first two
    cuts drew. The shell is one continuous cream moulding; a seat is TWO
    coloured panels sitting in it, a back and a pan, each inset
    `SEAT_BORDER_UNITS` from every one of the seat's four edges. What is left
    cream is therefore: the shelf against the wall, a lip over the top of the
    back, the JOINT between the back panel and the pan (the real bench has
    two separate mouldings there, not one wrapped cushion), the front lip over
    the nose, and a margin at each end that the scallop ridge stands on.
    """
    section = bucket_section()
    v = [p[2] for p in section]
    arc = profile_units(section)
    w, h = seat_px(BUCKET_UNITS), seat_px(arc)
    rows = canvas(w, h, SHELL)
    r = lambda t: int(round(t * h))
    m = SEAT_BORDER_UNITS / arc                         # the border, in v
    u = max(1, int(round(SEAT_BORDER_UNITS * SEAT_PX_PER_UNIT)))
    dish = mix(base, lo, 0.30)

    # The shell's own form, in the only currency a fullbright texture has.
    K.hband(rows, 0, r(v[1]), SHELL_LO)                 # the shelf, in the shade
    K.hline(rows, r(v[1]), SHELL_EDGE)                  # ...and its moulded fold
    K.hline(rows, r(v[2]), SHELL_EDGE)                  # the back/pan joint line
    K.hline(rows, r(v[3]), SHELL_HI)                    # the nose lip's lit crown
    K.hband(rows, h - 1, h, SHELL_EDGE)

    # The two inserts. A dished field with a lit top edge and a shaded bottom
    # one is all the relief 8 px per unit will carry, and it is what a moulded
    # cushion seen under a fluorescent tube actually looks like.
    for y0, y1 in ((r(v[1] + m), r(v[2] - m)), (r(v[2] + m), r(v[3] - m))):
        if y1 - y0 < 3:
            continue
        K.rect(rows, u, y0, w - u, y1, base)
        K.rect(rows, u + 1, y0 + 1, w - u - 1, y1 - 1, dish)
        K.hline(rows, y0, hi, u, w - u)                 # the insert's lit edge
        K.hline(rows, y1 - 1, lo, u, w - u)
        for x in (u, w - u - 1):
            K.vline(rows, x, y0, y1, lo)
    return rows


def art_shell():
    """The cream fibreglass the scallop ridges and the bench ends are moulded
    from. Varies only with v — a ridge is swept, so any variation across u
    would show as a stripe down the middle of the moulding."""
    rows = canvas(8, 16, SHELL)
    K.hband(rows, 0, 2, SHELL_HI)
    K.hband(rows, 14, 16, SHELL_LO)
    K.hline(rows, 15, SHELL_EDGE)
    return rows


def art_bar():
    """The chrome end grab bar. Bright polished steel, varying only with v so
    a tube's four faces cannot show a seam — the pole texture's own rule."""
    rows = canvas(8, 8, METAL_HI)
    K.hband(rows, 0, 2, A.POLISH_HOT)
    K.hband(rows, 6, 8, METAL)
    return rows


def art_seat_end(w, h):
    """The cheek that closes a bench run against a doorway.

    ⭐ CREAM, NOT STAINLESS, AND THAT IS THE SAME CORRECTION AS THE RIDGES'.
    The shell is ONE moulding and its ends are part of it — the user's photo
    shows cream sides on the bench, with the chrome bar standing off in front
    of them. The stainless plate this replaces was reasoned about as "three
    grades of steel at a bench end", which was a careful argument about a
    member the real bench does not have.

    It carries a moulded recess rather than a bolted panel's edge trim, because
    that is what a fibreglass end cheek has, and the CHROME BAR in front of it
    is now the only bright metal in the assembly — which is where a real car's
    brightest metal is too.
    """
    rows = canvas(w, h, SHELL)
    K.rect(rows, 0, 0, w, 1, SHELL_HI)                  # the moulding's top edge
    K.rect(rows, 0, h - 1, w, h, SHELL_EDGE)
    inset = max(2, w // 7)
    K.rect(rows, inset, inset, w - inset, h - inset, SHELL_LO)
    K.rect(rows, inset, inset, w - inset, inset + 1, SHELL_EDGE)
    K.rect(rows, inset, h - inset - 1, w - inset, h - inset, SHELL_HI)
    return rows


def art_duct():
    """The under-seat cabinet the bench is cantilevered off. v 0 is the lip
    under the cushion, 1 the cabinet's own face.

    ⭐ DARK, NOT CREAM. It used to be painted as more wall, which put a light
    surface in the one place on the car that is always in shadow and left the
    bench looking like a shelf pinned to a flat wall. The photographs show a
    recessed dark cabinet, and a dark recess is also what gives the cushion's
    nose something to be read against.
    """
    rows = canvas(8, 16, DUCT_FACE)
    K.hband(rows, 0, 3, DUCT_LIP)                       # the lip, catching light
    K.hline(rows, 3, DUCT_SEAM)
    for y in range(6, 16, 3):                           # the grille's louvres
        K.rect(rows, 0, y, 8, y + 1, DUCT_LOUVRE)
    return rows


# --- metalwork -------------------------------------------------------------

def art_pole():
    """A stanchion's skin: u wraps it, v runs up. May only vary with v, or the
    seam between two of its four faces shows as a stripe."""
    rows = canvas(8, 16, METAL)
    K.hband(rows, 0, 1, METAL_HI)
    K.hband(rows, 15, 16, METAL_LO)
    for y in range(3, 15, 5):
        K.rect(rows, 0, y, 8, y + 1, METAL_HI)
    return rows


def art_reveal():
    """The doorway's interior return — a plain dark stainless cheek."""
    return K.solid(A.STEEL_DEEP, 8)


# --- the ends --------------------------------------------------------------

def art_end_wall(w, h):
    """The transverse wall beside the storm door: cream panels over a skirt."""
    rows = canvas(w, h, CREAM)
    K.hband(rows, 0, int(h * 0.05), CREAM_HI)
    K.hline(rows, int(h * 0.05), CREAM_SEAM)
    K.hband(rows, int(h * 0.44), int(h * 0.46), CREAM_SEAM)
    K.hband(rows, int(h * 0.46), int(h * 0.86), CREAM_LO)
    K.hband(rows, int(h * 0.86), h, WALL_SKIRT)
    K.vline(rows, w // 2, 0, int(h * 0.86), CREAM_SEAM)
    return rows


def art_storm_door(w, h):
    """The storm door seen from inside: a cream leaf with a real window hole
    and a kick plate. The hole matches the shell's own, so the view through it
    is the view through the car end rather than a painted rectangle."""
    rows = canvas(w, h, CREAM)
    K.hband(rows, 0, 2, CREAM_SEAM)
    K.hband(rows, int(h * 0.80), h, METAL_LO)           # the kick plate
    K.hline(rows, int(h * 0.80), METAL_HI)
    x0 = int(w * 0.16)
    x1 = w - x0
    y0 = int(h * 0.10)
    y1 = int(h * 0.44)
    K.rrect(rows, x0 - 3, y0 - 3, x1 + 3, y1 + 3, 5, METAL_LO)
    K.rrect(rows, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 4, METAL)
    K.rrect(rows, x0, y0, x1, y1, 3, CLEAR)
    K.rect(rows, x1 - 4, int(h * 0.52), x1 - 1, int(h * 0.62), METAL_HI)
    return rows


def art_cab_wall(w, h):
    """The half-cab's partition, from the SALOON side: a dark panel with the
    cab door's own window, which is the only way a rider sees the cab."""
    rows = canvas(w, h, CAB_WALL)
    K.hband(rows, 0, 2, CAB_WALL_HI)
    K.hband(rows, int(h * 0.86), h, CAB_DARK)
    x0, x1 = int(w * 0.52), int(w * 0.92)
    y0, y1 = int(h * 0.12), int(h * 0.40)
    K.rrect(rows, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 4, METAL_LO)
    K.rrect(rows, x0, y0, x1, y1, 3, (36, 42, 48))
    K.vline(rows, int(w * 0.48), 0, h, CAB_WALL_HI)     # the door's own edge
    K.rect(rows, int(w * 0.44), int(h * 0.46), int(w * 0.47),
           int(h * 0.54), METAL_HI)                     # its handle
    return rows


def art_cab_desk():
    """The console, seen through the windshield: instruments on a black desk."""
    rows = canvas(32, 16, CAB_DESK)
    K.rect(rows, 0, 0, 32, 2, (54, 56, 60))
    K.rect(rows, 3, 4, 11, 11, CAB_SCREEN)
    K.rect(rows, 4, 5, 10, 7, (96, 190, 176))
    K.rect(rows, 14, 4, 19, 9, CAB_GAUGE)
    K.rect(rows, 21, 4, 29, 7, (172, 44, 40))
    for x in range(21, 29, 3):
        K.rect(rows, x, 9, x + 2, 12, (78, 80, 86))
    return rows


def profile_units(section):
    """Arc length of a (|x|, donor y, v) section, in M units."""
    return sum(math.hypot(sx(b[0] - a[0]), sy(b[1]) - sy(a[1]))
               for a, b in zip(section, section[1:]))


def build_textures():
    """Draw every interior texture and pack them into one atlas."""
    tex, crops = {}, {}

    def wall_slice(key, bay, side):
        z_lo = bay.za if side > 0 else -bay.zb
        z_hi = bay.zb if side > 0 else -bay.za
        z_lo = max(z_lo, L.z_of_u(1.0))
        z_hi = min(z_hi, L.z_of_u(0.0))
        a, b = sorted((L.u_of_z(z_lo), L.u_of_z(z_hi)))
        x0 = max(0, int(math.floor(a * WALL_TEX_W)))
        x1 = min(WALL_TEX_W, int(math.ceil(b * WALL_TEX_W)))
        keep = B.panes_in(bay, side)
        signature = (x0, x1, tuple(sorted(keep)))
        if signature in crops:
            tex[key] = tex[crops[signature]]
            return
        crops[signature] = key
        rows = art_wall(keep)
        tex[key] = Tex(key, pngtool.crop(rows, x0, 0, x1, len(rows)),
                       WALL_TEX_W, x0, x1)

    for name, bay in (("panel", L.PANEL_BAY), ("door", L.DOOR_BAY),
                      ("end", L.END_BAY)):
        wall_slice("int_wall_%s_a" % name, bay, +1)
        wall_slice("int_wall_%s_b" % name, bay, -1)

    tex["int_ceiling"] = Tex("int_ceiling", art_ceiling())
    for name, units in (("panel", L.PANEL_UNITS), ("door", L.DOOR_UNITS),
                        ("end", L.END_UNITS)):
        tex["int_band_" + name] = Tex("int_band_" + name,
                                      art_band(units, name))
        tex["int_floor_" + name] = Tex(
            "int_floor_" + name,
            (art_threshold if name == "door" else art_floor)(
                px(sx(2 * LINING_X)), px(units)))
    tex["int_map"] = Tex("int_map", art_map(L.MAP_UNITS))

    for colour in SEAT_COLOURS:
        base, hi, lo = SEAT_TONES[colour]
        tex["int_seat_" + colour] = Tex("int_seat_" + colour,
                                        art_bucket(base, hi, lo))
    # ⭐ ONE SHELL TEXTURE FOR ALL THREE TONES, where there used to be three rib
    # textures. The ridge is part of the moulding, not part of the seat, so it
    # cannot take its colour from the seat beside it — and there were two seats
    # beside it, in different colours, which is the tell that the old rib was
    # modelled as the wrong thing.
    tex["int_shell"] = Tex("int_shell", art_shell())
    # ⭐ SIZED TO THE PANEL THAT SHIPS, not to the one that used to. The cheek
    # stopped at the bench soffit when the cabinet went dark under it, and a
    # texture still cut for a floor-to-seat panel puts its moulded recess and
    # its own proportions in the wrong place on a piece 40% shorter.
    tex["int_seat_end"] = Tex("int_seat_end", art_seat_end(
        px(sx(LINING_X - SEAT_NOSE_X)),
        px(sy(SEAT_TOP_Y) - sy(SEAT_PAN_BOT_Y))))
    tex["int_duct"] = Tex("int_duct", art_duct())
    tex["int_pole"] = Tex("int_pole", art_pole())
    tex["int_bar"] = Tex("int_bar", art_bar())
    tex["int_reveal"] = Tex("int_reveal", art_reveal())
    tex["int_end_wall"] = Tex("int_end_wall", art_end_wall(
        px(sx(LINING_X - STORM_HALF_X)), px(sy(CROWN_Y) - sy(FLOOR_Y))))
    tex["int_storm"] = Tex("int_storm", art_storm_door(
        px(sx(2 * STORM_HALF_X)), px(sy(STORM_TOP_Y) - sy(FLOOR_Y))))
    tex["int_cab_wall"] = Tex("int_cab_wall", art_cab_wall(
        px(sx(LINING_X - CAB_INNER_X)), px(sy(CAB_TOP_Y) - sy(FLOOR_Y))))
    tex["int_cab_desk"] = Tex("int_cab_desk", art_cab_desk())
    tex["int_cab_dark"] = Tex("int_cab_dark", K.solid(CAB_DARK, 8))
    tex["int_cab_seat"] = Tex("int_cab_seat", K.solid((44, 46, 52), 8))

    # Dedup by IDENTITY: two keys can name the same Tex (the door bay's two
    # sides are the same crop), and packing it twice would give it two atlas
    # cells and leave the second one dangling.
    tex["_atlas"] = pack_atlas(list({id(t): t for t in tex.values()}.values()))
    return tex


# ====================================================================
# GEOMETRY
# ====================================================================

def u_at_for(bay, tex, side):
    """A bay-local M z -> the wall elevation's own u, for one side."""
    def u_at(mz):
        return L.u_of_z(bay.donor_z(mz), side)
    return u_at


def wall_run(group, tex, bay, side, z_a, z_b, y_top, y_bot):
    """One flat panel of lining, on one side, facing INBOARD.

    ⭐ ONE QUAD, because the R62 has no tumblehome: the lining is a single
    vertical plane at |x| 1.193 from the floor to the ceiling rack. The M7
    needed a four-segment swept profile and a v table to go with it.
    """
    u_at = u_at_for(bay, tex, side)
    z0, z1 = bay.z(z_a), bay.z(z_b)
    v_top, v_bot = wall_v(y_top), wall_v(y_bot)
    uv = [tex.uv(u_at(z0), v_top), tex.uv(u_at(z1), v_top),
          tex.uv(u_at(z1), v_bot), tex.uv(u_at(z0), v_bot)]
    quad_x(group, tex.material, side * LINING_X, z0, z1, y_top, y_bot, uv,
           -float(side))


def sweep(group, tex, bay, z_a, z_b, section, solid_point, sides=(1.0, -1.0),
          u_along_bay=False, two_sided=False):
    """Sweep a (|x|, donor y, v) section along a donor z range, both sides.

    ⭐ THE WINDING IS DERIVED FROM `solid_point`, NOT FROM THE WALK DIRECTION.
    The M7's `extrude_run` takes the traversal order as the winding, which
    means every section has to be written the right way round and a reversed
    one is an invisible surface. Here each segment is handed the direction
    "away from the material" — a point INSIDE the wall, the seat or the
    ceiling void — and `quad()` settles the order. That removes the whole class
    of bug, and `check`'s saloon-viewpoint test still audits the result.

    ⭐⭐ ...AND `solid_point=None` IS THE CASE THAT ONE POINT CANNOT SERVE.
    A single interior point works for a lining, a duct or a ceiling, because
    each of those is a shell around one convex void. A SEAT is an L: the point
    that is inside the back is ABOVE the cushion, so the same rule that gets
    the back right turns the cushion upside down — which is exactly what the
    2026-07-29 bench shipped with, and under MTR's unconditional backface
    culling an upside-down cushion is not drawn at all. The bench read flat
    because half of it was missing.
        With `solid_point=None` the outward direction is the segment turned
    -90 degrees, i.e. the walk's own right-hand side, so a section walked
    consistently AROUND the material (this file's convention: wall first,
    finishing under the seat) is correct segment by segment with no interior
    point to pick at all. The saloon-viewpoint test still audits the result,
    and it is the reason this could never be trusted on its own.

    `u_along_bay` puts u on the bay axis and v on the section (the band and the
    seats, whose art runs along the car); otherwise u walks the section.
    """
    z0, z1 = sorted((bay.z(z_a), bay.z(z_b)))
    for side in sides:
        for (x0, y0, v0), (x1, y1, v1) in zip(section, section[1:]):
            dx, dy = sx(x1 - x0), sy(y1) - sy(y0)
            nx, ny = dy, -dx
            if solid_point is not None:
                px_m, py_m = solid_point
                to_solid = (sx(px_m - x0), sy(py_m) - sy(y0))
                if nx * to_solid[0] + ny * to_solid[1] > 0:
                    nx, ny = -nx, -ny
            if u_along_bay:
                a0, a1 = tex.uv(0.0, v0), tex.uv(0.0, v1)
                b1, b0 = tex.uv(1.0, v1), tex.uv(1.0, v0)
            else:
                a0, a1 = tex.uv(v0, 0.0), tex.uv(v1, 0.0)
                b1, b0 = tex.uv(v1, 1.0), tex.uv(v0, 1.0)
            quad(group, tex.material,
                 [(sx(side * x0), sy(y0), z0, a0[0], a0[1]),
                  (sx(side * x1), sy(y1), z0, a1[0], a1[1]),
                  (sx(side * x1), sy(y1), z1, b1[0], b1[1]),
                  (sx(side * x0), sy(y0), z1, b0[0], b0[1])],
                 (side * nx, ny, 0.0), two_sided)


def floor_run(group, tex, bay, z_a, z_b, half=LINING_X):
    z0, z1 = sorted((bay.z(z_a), bay.z(z_b)))
    c = [tex.uv(0.0, 0.0), tex.uv(0.0, 1.0), tex.uv(1.0, 1.0), tex.uv(1.0, 0.0)]
    quad_y(group, tex.material, FLOOR_Y,
           [(-half, z0), (-half, z1), (half, z1), (half, z0)], c, +1.0)


def ceiling_and_band(group, tex, bay, z_a, z_b, band_key):
    """Every bay carries the SAME ceiling, and that is a deliberate departure.

    The donor drops its ceiling to a flat 3.157 outboard of |z| 3.633 and so
    carries its ad rack over the middle 7.3 m only. A bay-repeating model
    cannot hold a feature that stops mid-bay — see `check`'s tiling rule — and
    a rack that runs the length of the car is what carries the strip map and
    the lighting. So the crown, the soffit and the band run end to end.
    """
    sweep(group, tex["int_ceiling"], bay, z_a, z_b, ceiling_section(),
          (LINING_X + 0.1, CROWN_Y + 0.2))
    sweep(group, tex[band_key], bay, z_a, z_b, band_section(),
          (LINING_X + 0.1, CROWN_Y + 0.2), u_along_bay=True)


# Where every strip-map board face lands, in BAY-LOCAL M units, recorded as it
# is built. `check_displays` needs the board's footprint and the atlas has
# folded every interior texture into one material, so there is no material name
# left to find it by — this is the log that replaces one.
MAP_BOARD_LOG = []


def map_board(group, tex, bay):
    """⭐ The strip map's painted housing, one M unit proud of the band.

    Four quads a side: the face, the soffit under it, and a return at each end.
    There is NO top return — it would face up, into the ceiling void, where
    nothing can see it, and `check`'s viewpoint test would (correctly) report
    it as a face MTR will draw as nothing at all.
    """
    z0, z1, y0, y1 = L.map_board()
    face_x = L.INT_BAND_X_M - mx(L.MAP_STANDOFF)
    band_x = L.INT_BAND_X_M
    t = tex["int_map"]
    full = [t.uv(0.0, 0.0), t.uv(1.0, 0.0), t.uv(1.0, 1.0), t.uv(0.0, 1.0)]
    edge = [t.uv(0.02, 0.02)] * 4
    for side in (+1.0, -1.0):
        # ⭐ THE ART HAS TO BE READ THE OTHER WAY ROUND ON THE -x SIDE, or the
        # map's bullet ends up at the wrong end of the car. Reversing a face's
        # WINDING does not mirror its texture; only swapping u does.
        uv = full if side > 0 else [full[1], full[0], full[3], full[2]]
        quad_x(group, t.material, side * face_x, z0, z1, my(y1), my(y0), uv,
               -side)
        quad_y(group, t.material, my(y0),
               [(side * face_x, z0), (side * face_x, z1),
                (side * band_x, z1), (side * band_x, z0)], edge, -1.0)
        for z, out in ((z1, +1.0), (z0, -1.0)):
            quad_z(group, t.material, z,
                   [(side * face_x, my(y0)), (side * band_x, my(y0)),
                    (side * band_x, my(y1)), (side * face_x, my(y1))],
                   edge, out)
        MAP_BOARD_LOG.append((sx(side * face_x), z0, z1, y0, y1))


def full_uv(t):
    """A texture's own four corners. ⭐ THROUGH `Tex.uv`, ALWAYS — a raw 0..1
    on an atlased texture addresses the whole sheet (`atlas_straddles`)."""
    return [t.uv(0.0, 0.0), t.uv(1.0, 0.0), t.uv(1.0, 1.0), t.uv(0.0, 1.0)]


def bucket(group, tex, bay, m0, m1, colour, side):
    """ONE moulded seat, swept between two bay-local M z values."""
    sweep(group, tex["int_seat_" + colour], bay, bay.donor_z(m0),
          bay.donor_z(m1), bucket_section(), None, sides=(side,),
          u_along_bay=True)


def offset_section(section, d):
    """A (|x|, y, v) section offset OUTWARD by `d` M units, mitred at the folds.

    ⭐ OUTWARD IS THE WALK TURNED -90 DEGREES — `sweep`'s own rule with
    `solid_point=None`, read from the same walk, so a ridge laid on a section is
    proud of it BY CONSTRUCTION. The alternative is a hand-written sign per
    segment, and a seat section changes plane four times: that is four chances
    to bury a moulding inside the cushion it is supposed to stand on.

    The fold offset is the true mitre, `d (n0+n1) / (1 + n0.n1)`, so the ridge
    keeps its depth THROUGH a corner instead of pinching to nothing there — the
    back/pan fold is 72 degrees, which a naive per-vertex offset would narrow
    by a fifth.
    """
    pts = [(sx(p[0]), sy(p[1])) for p in section]
    normals = []
    for a, b in zip(pts, pts[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy) or 1.0
        normals.append((dy / n, -dx / n))
    out = []
    for i, p in enumerate(pts):
        if i == 0 or i == len(pts) - 1:
            nx, ny = normals[0] if i == 0 else normals[-1]
            k = d
        else:
            a, b = normals[i - 1], normals[i]
            nx, ny = a[0] + b[0], a[1] + b[1]
            # A fold sharper than ~150 degrees would send the mitre to
            # infinity; nothing in this section is remotely that sharp, and the
            # clamp is here so a future section cannot silently explode.
            k = d / max(0.25, 1.0 + a[0] * b[0] + a[1] * b[1])
        out.append((mx(p[0] + nx * k), my(p[1] + ny * k), section[i][2]))
    return out


def shell_ridge(group, tex, bay, m_centre, side):
    """The moulded scallop between two seat positions.

    ⭐ WHAT THIS IS *NOT* IS THE POINT. It is not a divider, not a post and not
    an armrest — the user looked at the previous cut and said "there are no
    armrests", and they were right: a coloured member standing a whole unit
    above the cushion at a seat boundary is an armrest whatever it is called.
    This is HALF a unit of the shell's own cream, following the seat section
    from inside the wall to under the cushion's nose, which is what the
    fibreglass moulding on the real bench does.

    Emitted as a slab between the section and its outward offset:

        4 outer faces      the ridge's own crown, one per section segment
        8 side strips      the step down to the shell, at both z faces
        1 end cap          under the nose — the only free end it has

    The WALL end needs no cap: `RIDGE_WALL_X` is past the lining, so that face
    is inside the wall cavity where the lining hides it.
    """
    t = tex["int_shell"]
    uv = full_uv(t)
    base = list(bucket_section())
    base[0] = (RIDGE_WALL_X, base[0][1], base[0][2])
    proud = offset_section(base, RIDGE_PROUD_UNITS)
    z0 = m_centre - RIDGE_UNITS / 2.0
    z1 = m_centre + RIDGE_UNITS / 2.0
    s = side

    def pt(p, z, i):
        return (sx(s * p[0]), sy(p[1]), z, uv[i][0], uv[i][1])

    for i in range(len(base) - 1):
        a, b = base[i], base[i + 1]
        pa, pb = proud[i], proud[i + 1]
        dx, dy = sx(b[0] - a[0]), sy(b[1]) - sy(a[1])
        quad(group, t.material,
             [pt(pa, z0, 0), pt(pb, z0, 1), pt(pb, z1, 2), pt(pa, z1, 3)],
             (s * dy, -dx, 0.0))
        for z, out in ((z0, -1.0), (z1, +1.0)):
            quad(group, t.material,
                 [pt(a, z, 0), pt(b, z, 1), pt(pb, z, 2), pt(pa, z, 3)],
                 (0.0, 0.0, out))

    # The free end, under the nose: it faces the way the last segment was
    # walking, which on this section is straight down.
    a, b = base[-2], base[-1]
    dx, dy = sx(b[0] - a[0]), sy(b[1]) - sy(a[1])
    n = math.hypot(dx, dy) or 1.0
    quad(group, t.material,
         [pt(base[-1], z0, 0), pt(proud[-1], z0, 1),
          pt(proud[-1], z1, 2), pt(base[-1], z1, 3)],
         (s * dx / n, dy / n, 0.0))


def bench_run(group, tex, bay, m_a, m_b, side, phase=0):
    """Fill a bay-local M z span with the shell and the seats moulded into it.

    ⭐ THE PITCH IS FITTED, NOT TILED. A bench has to reach BOTH of its own
    ends exactly — one that stopped short of the cab bulkhead would show the
    lining through the gap, and one that overran it would sit inside the cab —
    so the count is the nearest whole number of seats to the nominal
    `BUCKET_UNITS` and the pitch is whatever that makes. This car's three runs
    come out at 6.75, 6.55 and 6.40 units against a nominal 7: a quarter of a
    unit is 15 mm at MTR's scale, well under one texel of the seat's own art.

    ⭐ AND THE SHELL IS NOW CONTINUOUS UNDER THE RIDGES, which the previous cut
    was not. It inset each bucket by half a rib and let the rib fill the gap —
    so the SHELF, which the rib also had to span, was a hole in the sweep. That
    worked only because the rib was a full-section prism; a ridge that is a
    slab laid ON the sweep would have left a 1 x 1.2-unit slot at every seat
    boundary, looking down past the seat back into the wall cavity. Tiling the
    seats edge to edge and laying the ridge over the seam removes the whole
    question, and it hides the colour change under the ridge for free.
    """
    n = max(1, int(round(abs(m_b - m_a) / BUCKET_UNITS)))
    pitch = (m_b - m_a) / n
    for i in range(n):
        colour = SEAT_COLOURS[(phase + i) % len(SEAT_COLOURS)]
        bucket(group, tex, bay, m_a + i * pitch, m_a + (i + 1) * pitch,
               colour, side)
        if i:
            shell_ridge(group, tex, bay, m_a + i * pitch, side)

    # The soffit — ONE quad for the whole run, which is what lets every pan
    # rib omit its own underside. The duct's lip carries on from `DUCT_X` to
    # the lining, so the two together close the bench from below.
    t = tex["int_duct"]
    flat = [t.uv(0.5, 0.06)] * 4
    quad_y(group, t.material, SEAT_PAN_BOT_Y,
           [(side * SEAT_NOSE_X, min(m_a, m_b)),
            (side * SEAT_NOSE_X, max(m_a, m_b)),
            (side * DUCT_X, max(m_a, m_b)), (side * DUCT_X, min(m_a, m_b))],
           flat, -1.0)
    return n


def bench_cap(group, tex, bay, m_z, facing, side):
    """The doorway end of a bench: the stainless plate, and the chrome bar.

    ⭐ THE BAR IS THE THING THE PHOTOGRAPHS SHOW AND THE OLD MODEL PAINTED. It
    is a bent tube — from the wall along the top of the seat back, down past
    the cushion's nose, and back to the wall over the cushion — in three boxes
    that meet at `BAR_X1` with only ONE face in that plane, because each
    horizontal run omits the end that butts the vertical one. Its 1.4-unit
    gauge, against the rail's 1.2 and the stanchion's 1.0, is what lets the
    stanchion at this same bench end run up through the bar's own corner.
    """
    # ⭐ THE CHEEK CLOSES THE SEAT, NOT THE WHOLE CAR SIDE. It used to run to
    # the floor, and a sheet 14 units tall at every bench end read from the
    # doorway as a locker door rather than as the end of a bench — there are
    # four of them per bay. It now stops at the soffit, and the void under the
    # cantilever is closed in the cabinet's own dark instead. Since 2026-07-30
    # it is CREAM: it is the end of the shell, not a plate bolted over it.
    t = tex["int_seat_end"]
    quad_z(group, t.material, m_z,
           [(side * SEAT_NOSE_X, SEAT_TOP_Y), (side * LINING_X, SEAT_TOP_Y),
            (side * LINING_X, SEAT_PAN_BOT_Y),
            (side * SEAT_NOSE_X, SEAT_PAN_BOT_Y)], full_uv(t), facing)
    d = tex["int_duct"]
    quad_z(group, d.material, m_z,
           [(side * SEAT_NOSE_X, SEAT_PAN_BOT_Y),
            (side * DUCT_X, SEAT_PAN_BOT_Y), (side * DUCT_X, FLOOR_Y),
            (side * SEAT_NOSE_X, FLOOR_Y)], [d.uv(0.5, 0.6)] * 4, facing)

    b = tex["int_bar"]
    z0 = m_z + facing * BAR_STANDOFF
    z1 = z0 + facing * BAR_GAUGE
    box(group, b, side * BAR_X0, side * BAR_X1, BAR_LOW_BOT_Y, BAR_TOP_Y,
        z0, z1)
    for y_lo, y_hi in ((BAR_TOP_BOT_Y, BAR_TOP_Y),
                       (BAR_LOW_BOT_Y, BAR_LOW_TOP_Y)):
        box(group, b, side * BAR_X1, side * BAR_WALL_X, y_lo, y_hi, z0, z1,
            skip=("xmin", "xmax"))


def bench_pole_z(m_z, facing):
    """The bay-local z of the stanchion that rises out of a bench end's bar."""
    return m_z + facing * (BAR_STANDOFF + BAR_GAUGE / 2.0)


def pole(group, tex, bay, x_m, z_m, y0, y1):
    """A stanchion: a 1 M unit square prism, floor to rail or floor to crown.

    ⭐ SQUARE, NOT ROUND, AND ONE UNIT ACROSS. The donor spends 1,500 faces on
    cylinders 70 mm wide; 70 mm is 1.04 M units, and a cylinder one unit across
    is a square with wasted vertices. MTR's own canon is a degenerate box
    inflated 0.2 — 0.4 units — which the M7 pass measured and rejected at this
    scale as "a scratch".
    """
    z = bay.z(z_m)
    half_z = sx(POLE_HALF)
    box(group, tex["int_pole"], x_m - POLE_HALF, x_m + POLE_HALF, y0, y1,
        z - half_z, z + half_z, skip=("up", "down"))


def rail_run(group, tex, bay, z_a, z_b):
    """The overhead longitudinal grab rail, one per side, running the whole bay.

    ⭐ IT MUST SPAN THE WHOLE BAY. A rail that stopped short would show a gap at
    every seam down the car; `check`'s tiling rule measures it rather than
    trusting this comment.
    """
    z0, z1 = sorted((bay.z(z_a), bay.z(z_b)))
    bottom = my(sy(RAIL_Y) - RAIL_DEPTH_UNITS)
    for side in (+1.0, -1.0):
        box(group, tex["int_pole"], side * (POLE_AISLE_X - RAIL_HALF),
            side * (POLE_AISLE_X + RAIL_HALF), bottom, RAIL_Y, z0, z1,
            skip=("up", "zmin", "zmax"))


# ====================================================================
# THE BAYS
# ====================================================================

TILING_LOG = []          # (kind, bay, donor z0, donor z1) — see `check`


def log_run(kind, bay, z_a, z_b):
    TILING_LOG.append((kind, bay, z_a, z_b))


def build_panel_bay(model, tex):
    """58 units of saloon: lining, windows, benches, ceiling, the strip map."""
    bay = L.PANEL_BAY
    g = model.group("int_panel")

    for side, key in ((+1, "int_wall_panel_a"), (-1, "int_wall_panel_b")):
        wall_run(g, tex[key], bay, side, bay.za, bay.zb, WALL_TOP_Y, FLOOR_Y)
    ceiling_and_band(g, tex, bay, bay.za, bay.zb, "int_band_panel")
    floor_run(g, tex["int_floor_panel"], bay, bay.za, bay.zb)
    map_board(g, tex, bay)
    rail_run(g, tex, bay, bay.za, bay.zb)
    for kind in ("wall", "ceiling", "band", "floor", "rail"):
        log_run(kind, bay, bay.za, bay.zb)

    # The benches: EIGHT individual buckets a side, cycling three warm tones,
    # filling the bay between its two doorways. The donor's own colour-group
    # boundaries land at 0.663 / 1.533 / 2.403 / 3.273 / 4.143 — 0.87 m apart,
    # i.e. two seats — but the user's photographs alternate per SEAT, and the
    # bench now gives up `BENCH_END_INSET` at each end to the grab bar.
    edge = bay.z(bay.za) + BENCH_END_INSET
    far = bay.z(bay.zb) - BENCH_END_INSET
    for side in (+1.0, -1.0):
        bench_run(g, tex, bay, edge, far, side)
        bench_cap(g, tex, bay, edge, -1.0, side)
        bench_cap(g, tex, bay, far, +1.0, side)
    sweep(g, tex["int_duct"], bay, bay.za, bay.zb, duct_section(),
          (LINING_X + 0.05, (FLOOR_Y + DUCT_TOP_Y) / 2.0))
    log_run("duct", bay, bay.za, bay.zb)

    # The poles. Donor builder 37's two centreline stanchions sit inside this
    # bay; builder 35's aisle pair flanks every doorway — and each of those now
    # rises out of the grab bar at the bench end it stands beside, which is
    # what the photographs show and what the three gauges were chosen for.
    for z_m in CENTRE_POLE_Z_M:
        pole(g, tex, bay, 0.0, z_m, FLOOR_Y, CROWN_Y)
    for mz in (bench_pole_z(edge, -1.0), bench_pole_z(far, +1.0)):
        for side in (+1.0, -1.0):
            pole(g, tex, bay, side * POLE_AISLE_X, bay.donor_z(mz),
                 FLOOR_Y, RAIL_Y)


def build_door_bay(model, tex):
    """21 units of doorway — which on this car is nothing but the aperture.

    The donor's bay boundaries ARE the opening's edges, so there is no lining
    in here below the head at all: a header strip, the reveal that closes the
    47 mm between the lining and the sliding leaf, the vestibule floor, and the
    ceiling carrying on overhead.
    """
    bay = L.DOOR_BAY
    g = model.group("int_door")
    z0, z1 = bay.z(bay.za), bay.z(bay.zb)

    for side, key in ((+1, "int_wall_door_a"), (-1, "int_wall_door_b")):
        wall_run(g, tex[key], bay, side, bay.za, bay.zb, WALL_TOP_Y,
                 DOOR_HEAD_Y)
    ceiling_and_band(g, tex, bay, bay.za, bay.zb, "int_band_door")
    floor_run(g, tex["int_floor_door"], bay, bay.za, bay.zb,
              half=DOOR_FLOOR_HALF)
    rail_run(g, tex, bay, bay.za, bay.zb)
    for kind in ("wall", "ceiling", "band", "floor", "rail"):
        log_run(kind, bay, bay.za, bay.zb)

    # ⭐ THE REVEAL, AND WHY IT STOPS SHORT OF THE LEAF. Without it the 47 mm
    # between the lining and the leaf's inboard plane is a slot straight
    # through the car side, because the shell's skin is single-sided and
    # invisible from within. It cannot reach the leaf either: the leaf slides
    # through exactly that gap, so it stops 5 mm short.
    t = tex["int_reveal"]
    uv = [t.uv(0.0, 0.0)] * 4
    for side in (+1.0, -1.0):
        quad_y(g, t.material, DOOR_HEAD_Y,
               [(side * LINING_X, z0), (side * LINING_X, z1),
                (side * REVEAL_X, z1), (side * REVEAL_X, z0)], uv, -1.0)
        for z, out in ((z1, -1.0), (z0, +1.0)):
            quad_z(g, t.material, z,
                   [(side * LINING_X, FLOOR_Y), (side * REVEAL_X, FLOOR_Y),
                    (side * REVEAL_X, DOOR_HEAD_Y),
                    (side * LINING_X, DOOR_HEAD_Y)], uv, out)


def build_end_bay(model, tex, blind=False):
    """One car end: a bench, the half-cab (or not), and the storm door.

    ⭐ AUTHORED AS THE +z END WITH THE CAB ON +x, which is exactly what the
    donor's +z end is (builder 48, HalfCab.PNG) and exactly what the shell's
    own end bay is authored as. `end1` places it unflipped and `end2` flipped,
    which turns it round into the -z end with the cab on -x — the diagonal
    opposition the prototype has, for free.

    `blind` is the `r62_middle` variant: no cab, so the bench and the lining
    run to the end wall on BOTH sides. A middle car in a long consist should
    not show a cab; no real R62 has such a car, and it is documented as the
    deliberate fiction it is in R62_NOTES.md.
    """
    bay = L.END_BAY
    g = model.group("int_end_blind" if blind else "int_end_cab")
    inner = bay.z(bay.za)                      # the doorway edge
    front = bay.z(END_WALL_Z)                  # the transverse end wall
    cab_z = bay.z(CAB_BULKHEAD_Z)

    # ⭐ THE LINING RUNS THE WHOLE BAY ON BOTH SIDES, INCLUDING BEHIND THE CAB,
    # and until 2026-07-30 it did not — it stopped at the cab bulkhead, on the
    # reasoning that the cab is a room and the saloon's wall ends where the room
    # begins. Three things were wrong with that:
    #
    #   * the cab then had NO outboard wall at all. A player on the platform
    #     looks through this car's windscreen — a real alpha-0 aperture, not a
    #     painted one — into a cab and straight out through the far side.
    #   * the donor's `end_a` window is at donor z 6.617..7.032, INSIDE the cab.
    #     The shell cuts that aperture in the bodyside whatever the interior
    #     does, so a window with no lining behind it was showing the wall cavity.
    #     Run the lining through and the same aperture becomes what it is on the
    #     real car: the half-cab's own side window.
    #   * ⭐ AND IT LEAKED DAYLIGHT INTO THE SALOON. The cab ceiling is 10 mm
    #     below the wall top (`CAB_TOP_Y`, and it has to be — see there), so
    #     between the two there was a 0.16-unit slot at the lining plane running
    #     the length of the cab. A rider at the end of the car looking UP saw
    #     sky through it, which is one of the two leaks the user reported.
    #
    # A lining is the car's own skin lining; it does not care what is built
    # against it.
    for side, key in ((+1, "int_wall_end_a"), (-1, "int_wall_end_b")):
        wall_run(g, tex[key], bay, side, bay.za, END_WALL_Z, WALL_TOP_Y,
                 FLOOR_Y)

    # ⭐ THE CEILING STOPS AT THE ROOF'S OWN LEADING EDGE, NOT AT THE BAY'S.
    # The shell's roof shell ends at donor `ROOF_FRONT_Z` and the bullnose
    # takes over; a ceiling run to the bay edge would poke out through the
    # nose. The seam that has to be exact is the -z one, against the door bay.
    ceiling_and_band(g, tex, bay, bay.za, L.ROOF_FRONT_Z, "int_band_end")
    floor_run(g, tex["int_floor_end"], bay, bay.za, STORM_Z)
    rail_run(g, tex, bay, bay.za, L.ROOF_FRONT_Z)
    for kind in ("wall", "ceiling", "band", "floor", "rail"):
        log_run(kind, bay, bay.za, L.ROOF_FRONT_Z)

    # The benches. ⭐ EACH SIDE IS ITS OWN RUN, because the cab side stops at
    # the cab bulkhead and the open side carries on to the end wall — and the
    # bulkhead's own plane is what the cab-side run is handed, so nothing can
    # poke into the cab. The bucket pitch is fitted per run (see `bench_run`),
    # which is what lets two runs of different length both land flush.
    start = inner + BENCH_END_INSET
    duct_solid = (LINING_X + 0.05, (FLOOR_Y + DUCT_TOP_Y) / 2.0)
    for side in (+1.0, -1.0):
        outer = blind or side < 0
        stop = END_WALL_Z if outer else CAB_BULKHEAD_Z
        sweep(g, tex["int_duct"], bay, bay.donor_z(start), stop,
              duct_section(), duct_solid, sides=(side,))
        bench_run(g, tex, bay, start, bay.z(stop), side)
        bench_cap(g, tex, bay, start, -1.0, side)
    # ⭐ ONLY THE DOORWAY END OF A BENCH GETS A PLATE. The other end dies into
    # the end wall or into the cab bulkhead, so a plate there is a quad buried
    # inside another quad — coplanar with it, and visible from nowhere a rider
    # can be. `check`'s viewpoint test does not catch it, because the cab's own
    # set legitimately includes a point outside the windshield; measuring the
    # geometry was what found it.

    # The transverse end wall, beside the storm door.
    t = tex["int_end_wall"]
    uv = [t.uv(0.0, 0.0), t.uv(1.0, 0.0), t.uv(1.0, 1.0), t.uv(0.0, 1.0)]
    for side in ((+1.0, -1.0) if blind else (-1.0,)):
        quad_z(g, t.material, front,
               [(side * STORM_HALF_X, CROWN_Y), (side * LINING_X, CROWN_Y),
                (side * LINING_X, FLOOR_Y), (side * STORM_HALF_X, FLOOR_Y)],
               uv, -1.0)

    # ⭐ AND THE SAME WALL OVER THE CAB — the second leak the user reported, and
    # the one that is only visible by LOOKING UP. The cab side gets no end wall
    # below its own ceiling, because the cab is in the way and a wall there
    # would cut the room in half at the driver's shoulder. Above `CAB_TOP_Y`
    # there is no cab, and until this quad existed there was nothing else
    # either: the interior ceiling deliberately stops at the roof's leading
    # edge (see above), so the volume between the cab's lid and the crown was
    # open forward into the bullnose — and a bullnose seen from inside is
    # backfaces, which MTR does not draw. Sky, from the end of the saloon.
    if not blind:
        quad_z(g, t.material, front,
               [(STORM_HALF_X, CROWN_Y), (LINING_X, CROWN_Y),
                (LINING_X, CAB_TOP_Y), (STORM_HALF_X, CAB_TOP_Y)],
               [t.uv(0.0, 0.0), t.uv(1.0, 0.0), t.uv(1.0, 0.12),
                t.uv(0.0, 0.12)], -1.0)

    storm_door(g, tex, bay, front)
    if not blind:
        build_cab(g, tex, bay, cab_z)

    # One aisle stanchion pair at the doorway end, the same as the panel bay's
    # — out of the grab bar at the bench end it stands beside.
    for side in (+1.0, -1.0):
        pole(g, tex, bay, side * POLE_AISLE_X,
             bay.donor_z(bench_pole_z(start, -1.0)), FLOOR_Y, RAIL_Y)


def storm_door(group, tex, bay, front):
    """The storm door from inside: its leaf, the header over it, and the two
    returns from the end wall out to the leaf's own plane."""
    t = tex["int_storm"]
    leaf_z = bay.z(STORM_Z - STORM_STANDOFF_M)
    uv = [t.uv(0.0, 0.0), t.uv(1.0, 0.0), t.uv(1.0, 1.0), t.uv(0.0, 1.0)]
    quad_z(group, t.material, leaf_z,
           [(-STORM_HALF_X, STORM_TOP_Y), (STORM_HALF_X, STORM_TOP_Y),
            (STORM_HALF_X, FLOOR_Y), (-STORM_HALF_X, FLOOR_Y)], uv, -1.0)

    e = tex["int_end_wall"]
    flat = [e.uv(0.0, 0.0)] * 4
    for side in (+1.0, -1.0):                          # the recess's returns
        quad_x(group, e.material, side * STORM_HALF_X, front, leaf_z,
               STORM_TOP_Y, FLOOR_Y, flat, -side)
    quad_y(group, e.material, STORM_TOP_Y,             # the header over it
           [(-STORM_HALF_X, front), (-STORM_HALF_X, leaf_z),
            (STORM_HALF_X, leaf_z), (STORM_HALF_X, front)], flat, -1.0)
    quad_z(group, e.material, front,
           [(-STORM_HALF_X, CROWN_Y), (STORM_HALF_X, CROWN_Y),
            (STORM_HALF_X, STORM_TOP_Y), (-STORM_HALF_X, STORM_TOP_Y)],
           flat, -1.0)


def build_cab(group, tex, bay, cab_z):
    """The half-cab, as a silhouette — see the module docstring for why it is
    built at all (the shell's windshield is a REAL aperture on this car) and
    why it is only a silhouette.

    Everything here is emitted with BOTH windings: the room is seen from in
    front through the windshield and from behind through the cab door's own
    window, and MTR culls backfaces unconditionally.
    """
    end = bay.z(STORM_Z)
    front = bay.z(END_WALL_Z)
    wall = tex["int_cab_wall"]
    uv = [wall.uv(0.0, 0.0), wall.uv(1.0, 0.0), wall.uv(1.0, 1.0),
          wall.uv(0.0, 1.0)]
    # The bulkhead the saloon looks at, and the partition beside the storm door.
    quad_z(group, wall.material, cab_z,
           [(CAB_INNER_X, CAB_TOP_Y), (LINING_X, CAB_TOP_Y),
            (LINING_X, FLOOR_Y), (CAB_INNER_X, FLOOR_Y)], uv, -1.0,
           two_sided=True)
    # ⭐ THE PARTITION STOPS AT THE END WALL, NOT AT THE STORM DOOR'S LEAF.
    # Past that plane the same surface is the storm door recess's own cheek,
    # which `storm_door` draws in the CREAM the rest of the recess is — and it
    # used to draw it there as well, 0.2 mm apart and facing the same way. The
    # two now divide the plane at `front` and only touch along that line.
    quad_x(group, wall.material, CAB_INNER_X, cab_z, front, CAB_TOP_Y, FLOOR_Y,
           uv, -1.0, two_sided=True)

    dark = tex["int_cab_dark"]
    flat = [dark.uv(0.0, 0.0)] * 4
    quad_y(group, dark.material, CAB_TOP_Y,            # the cab's own ceiling
           [(CAB_INNER_X, cab_z), (CAB_INNER_X, end),
            (LINING_X, end), (LINING_X, cab_z)], flat, -1.0)

    # The console, and the driver's seat behind it. Sizes are whole M units, so
    # they land on the game's own grid; the desk stops 2 units short of the
    # storm door's plane, which is the furthest forward anything may go before
    # the bullnose starts (`check` measures that on the assembled car).
    #
    # ⭐ THE SEAT IS ANCHORED TO THE BULKHEAD, NOT TO THE DESK, and measuring it
    # backwards from the desk put it THROUGH the bulkhead. `desk_z0 - 9` is
    # 1.6 units behind `cab_z`, so the driver's backrest stood entirely in the
    # SALOON — buried inside the passenger bench, invisible, paid for on every
    # frame, and its underside 0.038 units off the bench soffit, which is a
    # z-fight waiting for a camera angle. The cab is 15.4 units deep and the
    # seat is 8 of them: it has to be placed from the wall it is bolted to.
    desk_z1 = end - 2.0
    desk_z0 = desk_z1 - 6.0
    box(group, tex["int_cab_desk"], CAB_INNER_X + 0.06, LINING_X - 0.02,
        my(16.0), my(20.0), desk_z0, desk_z1, two_sided=True)
    # ⭐ AND THE STACKED BOXES SKIP THE FACES THEY BURY IN EACH OTHER. A
    # pedestal whose lid is the desk's underside, and a seat back whose foot is
    # the cushion's top, put two faces in one plane — and because everything in
    # the cab is two-sided, a "buried" pair here is a z-fight in BOTH
    # directions. The pedestal's own foot is the saloon floor, for the same
    # reason.
    box(group, dark, CAB_INNER_X + 0.10, LINING_X - 0.06,
        FLOOR_Y, my(16.0), desk_z0 + 1.0, desk_z1 - 1.0, two_sided=True,
        skip=("up", "down"))
    seat = tex["int_cab_seat"]
    seat_z0 = cab_z + 1.0
    box(group, seat, 0.72, 1.10, my(6.0), my(7.0),
        seat_z0 + 1.0, seat_z0 + 7.0, two_sided=True)
    box(group, seat, 0.72, 1.10, my(7.0), my(15.0),
        seat_z0, seat_z0 + 1.0, two_sided=True, skip=("down",))


def build_model():
    del TILING_LOG[:]
    del MAP_BOARD_LOG[:]
    tex = build_textures()
    model = Model()
    build_panel_bay(model, tex)
    build_door_bay(model, tex)
    build_end_bay(model, tex, blind=False)
    build_end_bay(model, tex, blind=True)
    return model, tex


def used_materials(model, tex):
    names = {m for g in model.groups.values() for m, _ in g.faces}
    out = {}
    for t in tex.values():
        if t.material in names:
            out[t.material] = t
    return out


# ====================================================================
# EMIT
# ====================================================================

def to_obj_vertex(x, y, z):
    """M units -> OBJ. One OBJ unit is one BLOCK.

    ⭐ THE HANDEDNESS FLIP, AND IT IS THE SHELL'S — `r62_layout.emit_x`, read
    rather than copied, because an interior that flips differently from the
    body it sits in puts the half-cab behind the wrong end of the windscreen.
    The old map here negated BOTH x and z: determinant +1, a 180-degree
    rotation, a faithful reproduction of the donor's LEFT-handed frame inside
    Minecraft's RIGHT-handed one — i.e. a mirrored car.

    Negating only z makes the map determinant -1, so every face's winding
    reverses with it and `write_obj` reverses each face's vertex order to put
    it back. That pair — mirror here, reverse there — is the whole flip, and
    `check_emitted_winding` is what proves the two halves of it still agree.
    """
    return (L.emit_x(x) / 16.0, y / 16.0, -z / 16.0)


def write_obj(model, path, mtl_name, mats, mtl_path_of):
    verts, vert_index, uvs, uv_index = [], {}, [], {}

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
            refs = []
            for (x, y, z, u, v) in vs:
                ox, oy, oz = to_obj_vertex(x, y, z)
                refs.append("%d/%d" % (vid(ox, oy, oz), tid(u, v)))
            # ⭐ THE MIRROR REVERSES WINDING, SO THE ORDER GOES BACK — the same
            # switch `to_obj_vertex` reads, rather than a second copy of the
            # answer. MTR culls backfaces unconditionally; without this every
            # face in the saloon is inside out and a rider sees an empty box.
            body.append("f " + " ".join(refs[::-1] if L.MIRROR_X else refs))

    out = ["# NYCT R62 interior — generated by tools/convert_r62_interior.py.",
           "# DO NOT EDIT: re-run the converter instead.",
           "mtllib %s" % mtl_name, ""]
    out += ["v %.5f %.5f %.5f" % p for p in verts]
    out += ["vt %.6f %.6f" % p for p in uvs]
    out.append("")
    out += body
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write("\n".join(out) + "\n")

    mtl = ["# Generated by tools/convert_r62_interior.py — do not edit.", ""]
    for material in sorted(mats):
        mtl += ["newmtl %s" % material, "Kd 1 1 1", "d 1",
                "map_Kd %s" % mtl_path_of(mats[material]), ""]
    with open(os.path.join(os.path.dirname(path), mtl_name), "w") as fh:
        fh.write("\n".join(mtl) + "\n")
    return len(verts), sum(len(g.faces) for g in model.groups.values())


def write_textures(tex, directory=None, prune=False):
    directory = directory or TEX_DIR
    os.makedirs(directory, exist_ok=True)
    written = set()
    for t in tex.values():
        if t.atlas is not None:
            continue                     # its pixels live in the sheet
        pngtool.write_png(os.path.join(directory, t.name + ".png"), t.rows)
        written.add(t.name)
    if prune:
        for name in sorted(os.listdir(directory)):
            if name.endswith(".png") and name[:-4] not in written:
                os.remove(os.path.join(directory, name))
                print("removed orphaned texture %s" % name)
    return sorted(written)


# ====================================================================
# ASSEMBLY / PREVIEW
# ====================================================================

PROPS = os.path.join(OUR_NS, "properties/vehicle")
DEFS = os.path.join(OUR_NS, "properties/definition")
DOORS_MODEL = os.path.join(MODEL_DIR, "r62_doors.bbmodel")

_INT = "r62_interior_%s.json"
VARIANTS = {
    "cab": ("r62.json", ["r62_interior_common.json", _INT % "cab_1",
                         _INT % "cab_2"]),
    "middle": ("r62.json", ["r62_interior_common.json", _INT % "blind_1",
                            _INT % "blind_2"]),
}


def leaf_preview(model, tex):
    """Bake the .bbmodel's INBOARD leaf planes into the preview, closed.

    ⭐ NOT SHIPPED, AND NOT SHIPPABLE — the leaves genuinely have to live in the
    .bbmodel, because MTR's .obj path offsets an animating door twice. This
    exists so a preview can be looked at from INSIDE a car whose doors are
    shut, which is the only way to check the one thing the leaf's interior art
    is for: that a closed door reads as part of the lining rather than as a
    hole. `convert_openbve_r62.leaf_preview` does the same job for the OUTBOARD
    planes, and from inside those are culled — so this is its other half.
    """
    if not (os.path.exists(DOORS_MODEL) and os.path.isdir(DEFS)):
        return
    with open(DOORS_MODEL) as fh:
        bb = json.load(fh)
    try:
        with open(os.path.join(DEFS, "r62.json")) as fh:
            defs = {d["name"]: d for d in json.load(fh)["positionDefinitions"]}
    except OSError:
        return
    res = bb["resolution"]
    by_uuid = {e["uuid"]: e for e in bb["elements"]}
    entries = [(float(e.get("z", 0.0)), flipped)
               for key, flipped in (("positions", False),
                                    ("positionsFlipped", True))
               for e in defs.get("bbDoor", {}).get(key, [])]
    group = model.group("door_leaf_preview")
    material = tex["doors_box"].material
    for outline in bb.get("outliner", []):
        name = outline.get("name", "")
        if not name.startswith("door_") or name.endswith("_exterior"):
            continue
        for uuid in outline.get("children", []):
            e = by_uuid.get(uuid)
            if e is None or e.get("uv_offset") is None:
                continue
            frm, to = e["from"], e["to"]
            dz = int(round(to[2] - frm[2]))
            dy = int(round(to[1] - frm[1]))
            u0, v0 = e["uv_offset"]
            # vanilla's EAST rect — the bb -x (inboard) face. Its u runs the
            # other way round in bb z than the outboard one's, which is the
            # whole reason the leaf art has to be symmetric.
            uu = ((u0 + 2 * dz) / res["width"], (u0 + dz) / res["width"])
            vv = ((v0 + dz) / res["height"], (v0 + dz + dy) / res["height"])
            for Z, flipped in entries:
                pts = [B.bb_place(frm[0], to[1], frm[2], 0.0, Z, flipped),
                       B.bb_place(frm[0], to[1], to[2], 0.0, Z, flipped),
                       B.bb_place(frm[0], frm[1], to[2], 0.0, Z, flipped),
                       B.bb_place(frm[0], frm[1], frm[2], 0.0, Z, flipped)]
                uvs = [(uu[1], vv[0]), (uu[0], vv[0]),
                       (uu[0], vv[1]), (uu[1], vv[1])]
                quad(group, material,
                     [p + uvs[i] for i, p in enumerate(pts)],
                     (-1.0 if pts[0][0] > 0 else 1.0, 0.0, 0.0))


def assemble(model, placements):
    out = Model()
    for (group, X, Z, flipped) in placements:
        src = model.groups.get(group)
        if src is None:
            continue
        dst = out.group(group)
        for material, vs in src.faces:
            dst.faces.append((material, [B.obj_place(x, y, z, X, Z, flipped)
                                         + (u, v) for (x, y, z, u, v) in vs]))
    return out


# ====================================================================
# CONSISTENCY
# ====================================================================

# ⭐ THE SALOON IS A ROOM, NOT A LINE (the M7's depth pass established this).
# Testing every single-sided face against ONE point on the car's axis is right
# for a lining and a ceiling and WRONG the moment anything has two flanks: a
# duct's face, a bench's underside and a pole's outboard face all correctly
# point away from the centreline. So a face passes as soon as ANY place a rider
# can actually be can see it, and fails only when every one of them is behind
# it — which is the definition of a face MTR draws as nothing at all.
SALOON_VIEWPOINTS = (
    (0.000, 2.750),                      # standing in the aisle
    (0.000, 2.100),                      # ...and sitting in it
    (0.900, 2.000),                      # a seat, each side
    (-0.900, 2.000),
    (0.000, 1.400),                      # low: a child, or a bag on the floor
)

# The cab's occupants are OFF the axis, and half of what is in there is seen
# from OUTSIDE through the windshield, so it gets its own set. Donor (x, y, z).
CAB_VIEWPOINTS = (
    (0.850, 2.400, 6.900),               # the driver
    (0.000, 2.600, 6.000),               # a rider at the cab door's window
    (0.850, 2.500, 8.400),               # ...and the platform, through the glass
    (0.000, 2.400, 7.600),
)


def facing_inboard(group, viewpoints=SALOON_VIEWPOINTS):
    """(seen, blind) counts of a group's SINGLE-sided faces."""
    twins = {tuple(tuple(v) for v in reversed(vs)) for _m, vs in group.faces}
    seen = blind = 0
    for _m, vs in group.faces:
        if tuple(tuple(v) for v in vs) in twins:
            continue                     # emitted two-sided; nothing to check
        n = face_normal(vs)
        c = [sum(v[i] for v in vs) / len(vs) for i in range(3)]
        best = -2.0
        for (vx, vy) in viewpoints:
            to_eye = (sx(vx) - c[0], sy(vy) - c[1], 0.0)
            scale = (math.sqrt(sum(a * a for a in n))
                     * math.sqrt(sum(a * a for a in to_eye))) or 1.0
            best = max(best, sum(n[i] * to_eye[i] for i in range(3)) / scale)
        if best > 0.05:
            seen += 1
        elif best < -0.05:
            blind += 1
    return seen, blind


def facing_visible(group, points):
    """(seen, blind) against 3-D points — the cab's version of the above."""
    twins = {tuple(tuple(v) for v in reversed(vs)) for _m, vs in group.faces}
    seen = blind = 0
    for _m, vs in group.faces:
        if tuple(tuple(v) for v in vs) in twins:
            continue
        n = face_normal(vs)
        c = [sum(v[i] for v in vs) / len(vs) for i in range(3)]
        ok = False
        for p in points:
            d = [p[i] - c[i] for i in range(3)]
            scale = (math.sqrt(sum(a * a for a in n))
                     * math.sqrt(sum(a * a for a in d))) or 1.0
            if sum(n[i] * d[i] for i in range(3)) / scale > 0.05:
                ok = True
                break
        if ok:
            seen += 1
        else:
            blind += 1
    return seen, blind


def convex_overlap(a, b, tol=1e-4):
    """Do two convex 2-D polygons share AREA? Separating-axis, both ways.

    ⭐ THIS USED TO BE AN AABB TEST AND THE SECOND SEAT PASS PROVED IT TOO
    COARSE. A scallop ridge closes its own step with FOUR strips in one
    constant-z plane, one per segment of the seat section; consecutive strips
    only touch along an edge, but an L-shaped pair's bounding boxes overlap
    massively — 144 phantom z-fights, none of them real, on a model whose
    geometry was correct. A test that cries wolf on a legitimate construction
    is a test that will be turned off.

    Separating axes are the edge normals of both polygons; a gap on ANY of them
    means no shared area. `tol` makes edge-to-edge contact — which is what every
    fold in a swept surface is — count as no overlap.
    """
    for poly in (a, b):
        n = len(poly)
        for i in range(n):
            (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
            ax, ay = y1 - y0, x0 - x1
            scale = math.hypot(ax, ay)
            if scale < 1e-12:
                continue
            ax, ay = ax / scale, ay / scale
            pa = [ax * px_ + ay * py_ for px_, py_ in a]
            pb = [ax * px_ + ay * py_ for px_, py_ in b]
            if min(pa) >= max(pb) - tol or min(pb) >= max(pa) - tol:
                return False
    return True


def coplanar_overlaps(group):
    """Axis-planar faces in one group that share a plane AND a footprint.

    ⭐ TWO THINGS HIDE BEHIND THIS ONE MEASUREMENT, and the interior found both.
    A pair facing OPPOSITE ways is a quad buried inside another quad — the
    bench end plate that died into the end wall, invisible from anywhere and
    paid for on every frame. A pair facing the SAME way is a z-fight, which is
    the defect this repo has spent more render cycles on than any other.

    Only axis-planar faces are considered, which is all of them here bar the
    ceiling soffit and the seat sweeps. The footprint test is `convex_overlap`,
    not a bounding box; see there for what that cost.

    ⭐⭐ AND IT ALSO REPORTS NEAR-COPLANAR PAIRS, WHICH IS THE 2026-07-30
    LESSON. Two surfaces meant to be ONE plane and typed as two numbers —
    `CAB_INNER_X = 0.417` against `STORM_DOOR_HALF_X = 0.4168` — sat 0.003 M
    units apart, both facing inboard, overlapping over the whole height of the
    storm door recess. That is a guaranteed shimmer in game, and comparing
    plane values at 1e-6 could not see it: it is not the same plane, it is
    ALMOST the same plane, which is strictly worse. Anything closer than
    `NEAR_PLANE` and facing the SAME way is reported.

    ⭐ AND `NEAR_PLANE` HAS TO BE UNDER 0.1, WHICH IS NOT OBVIOUS. This model's
    deliberate METALWORK BURIAL puts same-facing parallel faces exactly 0.1
    apart on purpose — the bar's 1.4-unit gauge, the rail's 1.2 and the
    stanchion's 1.0 are all centred on `POLE_AISLE_X`, which is the whole
    trick that lets a pole run up through both without sharing a plane. A
    threshold of 0.2 reports twenty of those and is worse than no check.
    0.05 sits an order of magnitude above the defect and well under the design.

    ⭐ TWO-SIDED FACES ARE DEDUPLICATED RATHER THAN SKIPPED. Skipping them is
    what let this pair through for two days: the cab partition is emitted
    two-sided, and a face excluded from the comparison cannot be found fighting
    anything. One of each reversed pair is kept, so a two-sided surface is
    compared against the rest of the car exactly once.
    """
    NEAR_PLANE = 0.05
    seen, faces = set(), []
    for _m, vs in group.faces:
        key = tuple(tuple(v) for v in vs)
        if tuple(reversed(key)) in seen:
            continue                     # its own twin: one surface, kept once
        seen.add(key)
        faces.append(vs)
    planar = []
    for vs in faces:
        for axis in range(3):
            vals = [v[axis] for v in vs]
            if max(vals) - min(vals) > 1e-6:
                continue
            other = [a for a in range(3) if a != axis]
            poly = [(v[other[0]], v[other[1]]) for v in vs]
            box2 = tuple((min(v[a] for v in vs), max(v[a] for v in vs))
                         for a in other)
            planar.append((axis, vals[0], box2, poly, face_normal(vs)[axis]))
            break
    out = []
    for i, (ax, val, box2, poly, n) in enumerate(planar):
        for (bx, bval, bbox2, bpoly, bn) in planar[i + 1:]:
            gap = abs(val - bval)
            if ax != bx or gap > NEAR_PLANE:
                continue
            if gap > 1e-6 and n * bn <= 0:
                continue                 # parallel, apart, and back to back
            if not all(box2[k][0] < bbox2[k][1] - 1e-4
                       and bbox2[k][0] < box2[k][1] - 1e-4 for k in (0, 1)):
                continue                 # the cheap reject, kept as a prefilter
            if not convex_overlap(poly, bpoly):
                continue
            if gap > 1e-6:
                out.append((ax, val, "%.3f units apart and facing the same way "
                                     "— two numbers for one plane" % gap))
            else:
                out.append((ax, val, "buried" if n * bn < 0 else "z-fighting"))
    return out


def atlas_straddles(model, tex):
    """Faces whose UVs leave the atlas cell they are supposed to be inside.

    Once a texture is an atlas cell, EVERY coordinate written for it has to go
    through `Tex.uv`; a raw 0..1 coordinate silently addresses the whole sheet
    and lands on some other texture. That is not a UV out of range, so the
    [0,1] check cannot see it — but a face overlapping two cells always is one,
    and a face overlapping none is one too.
    """
    cells, seen = {}, set()
    for t in tex.values():
        if t.atlas is None or id(t) in seen:
            continue
        seen.add(id(t))
        u0, v0, du, dv = t.atlas
        cells.setdefault("r62_" + t.atlas_name, []).append(
            (t.name, u0, v0, u0 + du, v0 + dv))
    out = []
    for name in model.order:
        for material, vs in model.groups[name].faces:
            if material not in cells:
                continue
            us = [v[3] for v in vs]
            ts = [v[4] for v in vs]
            bounds = (min(us), min(ts), max(us), max(ts))
            inside = [c[0] for c in cells[material]
                      if bounds[0] >= c[1] - 1e-6 and bounds[1] >= c[2] - 1e-6
                      and bounds[2] <= c[3] + 1e-6 and bounds[3] <= c[4] + 1e-6]
            if len(inside) != 1:
                out.append((name, material, bounds, len(inside)))
    return out


def uncovered(region, rects, holes=()):
    """Sub-rectangles of `region` that no rect and no declared hole covers.

    Everything here is (a0, a1, b0, b1). The grid is the sweep-line one — every
    rectangle edge, clipped to the region — so a cell is either wholly covered
    or wholly not, and testing its CENTRE is exact rather than sampled. That
    matters: the defects this exists for are slots a sixth of a unit wide, and
    no ray-casting or voxel test at a sane cost can resolve one of those.
    """
    a0, a1, b0, b1 = region
    xs = sorted({a0, a1} | {max(a0, min(a1, v))
                            for r in list(rects) + list(holes) for v in r[:2]})
    ys = sorted({b0, b1} | {max(b0, min(b1, v))
                            for r in list(rects) + list(holes) for v in r[2:]})
    out = []
    for x0, x1 in zip(xs, xs[1:]):
        for y0, y1 in zip(ys, ys[1:]):
            if x1 - x0 < 1e-6 or y1 - y0 < 1e-6:
                continue
            cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
            if any(r[0] - 1e-6 <= cx <= r[1] + 1e-6
                   and r[2] - 1e-6 <= cy <= r[3] + 1e-6
                   for r in list(rects) + list(holes)):
                continue
            out.append((x0, x1, y0, y1))
    return out


def plane_rects(faces, axis, value, axes, tol=1e-4):
    """Faces lying wholly in an axis plane, as rectangles over `axes`.

    ⭐ `axes` IS EXPLICIT BECAUSE THE IMPLICIT ORDER IS A TRAP. "The other two,
    in order" is (y, z) for an x plane and (x, y) for a z plane, so one caller
    of the two gets a transposed rectangle and the report reads plausibly —
    z values in the y column and a full side declared full of holes. Naming
    them costs one argument.
    """
    out = []
    for vs in faces:
        if any(abs(v[axis] - value) > tol for v in vs):
            continue
        out.append((min(v[axes[0]] for v in vs), max(v[axes[0]] for v in vs),
                    min(v[axes[1]] for v in vs), max(v[axes[1]] for v in vs)))
    return out


def check_enclosure(model, notes):
    """⭐ THE SALOON MUST HAVE NO HOLE IN IT — measured, on the ASSEMBLED car.

    THE TWO LEAKS THIS EXISTS FOR were both reported from inside the game as
    daylight, and NEITHER of the checks that were here could see one: the
    bay-tiling rule audits a run against its own bay's seams and is blind to a
    surface that stops early INSIDE a bay; `coplanar_overlaps` looks for
    surfaces that meet twice, not for surfaces that never meet at all.

      * the cab-side lining stopped at the cab bulkhead, so the whole outboard
        wall of the half-cab was missing and a 0.16-unit slot ran the length of
        it between the cab's ceiling and the saloon's wall top;
      * nothing closed the car end ABOVE the cab, so the volume between the
        cab's lid and the crown was open forward into the bullnose.

    ⭐ AND WHY THIS IS COVERAGE ARITHMETIC AND NOT RAY CASTING. The obvious
    instrument is to stand in the saloon and cast rays. It does not work: a
    0.16-unit slot seen from ten units away subtends 0.016 radians, and a
    uniform sphere fine enough to hit it reliably is order 10^5 directions per
    viewpoint. Both defects are, however, EXACTLY a rectangle missing from a
    plane — so the test is: take every face lying in one of the room's own
    boundary planes, and require their union, plus the openings that are
    supposed to be there, to cover the whole plane. That is exact, it is
    instant, and it names the missing rectangle in the report.

    Three planes carry the whole car:

        |x| = LINING_X       the two sides, floor to wall top, over the
                             BODYSIDE's length — beyond that the shell is nose
        |z| = the end wall   floor to crown, over the full width
        the storm door leaf  which has to fill the recess it stands in
    """
    problems = []
    lining, top = sx(LINING_X), sy(WALL_TOP_Y)
    crown, storm_x = sy(CROWN_Y), sx(STORM_HALF_X)
    storm_top = sy(STORM_TOP_Y)
    for label, (definition, properties) in sorted(VARIANTS.items()):
        placements, _ = B.load_placements(definition, properties)
        built = assemble(model, placements)
        faces = [vs for g in built.groups.values() for _m, vs in g.faces]

        # Where the ends are, read off the placements rather than assumed.
        ends = sorted({(-Z - L.END_BAY.z(END_WALL_Z)) if flipped
                       else (Z + L.END_BAY.z(END_WALL_Z))
                       for (g, X, Z, flipped) in placements
                       if g.startswith("int_end")})
        if len(ends) != 2:
            problems.append("%s: %d end bays placed, not 2" % (label, len(ends)))
            continue

        # ⭐ THE DOORWAYS ARE THE ONLY HOLE THE SIDE IS ALLOWED. They are a hole
        # in the GEOMETRY (the door bay carries no lining below its head at
        # all); the window apertures are holes in the TEXTURE, so the lining
        # still covers them and they are not subtracted here.
        doors = [((-Z if flipped else Z) - L.DOOR_UNITS / 2.0,
                  (-Z if flipped else Z) + L.DOOR_UNITS / 2.0,
                  0.0, sy(DOOR_HEAD_Y))
                 for (g, X, Z, flipped) in placements if g == "int_door"]

        for sign in (+1.0, -1.0):
            gaps = uncovered((ends[0], ends[1], 0.0, top),
                             plane_rects(faces, 0, sign * lining, (2, 1)),
                             doors)
            for (z0, z1, y0, y1) in gaps[:4]:
                problems.append(
                    "%s: the lining at x %+.1f has a hole z %.1f..%.1f, "
                    "y %.1f..%.1f — daylight through the car side"
                    % (label, sign * lining, z0, z1, y0, y1))
            if len(gaps) > 4:
                problems.append("%s: ...and %d more lining holes"
                                % (label, len(gaps) - 4))

        # ⭐ THE CAB'S FORWARD END IS AN OPENING, AND DECLARING IT IS THE POINT.
        # Below its own ceiling the half-cab is open toward the bullnose,
        # because that is where the WINDSCREEN is: a wall at the end plane
        # would stand between the driver and the glass the shell cuts as a real
        # aperture. It is declared per END and per SIDE — the flip puts the cab
        # on -x at the other end — so the blind variant, which has no cab, is
        # still required to close both of its ends completely.
        cab_at = {(-Z - L.END_BAY.z(END_WALL_Z)) if flipped
                  else (Z + L.END_BAY.z(END_WALL_Z)): (-1.0 if flipped else 1.0)
                  for (g, X, Z, flipped) in placements if g == "int_end_cab"}
        for end in ends:
            openings = [(-storm_x, storm_x, 0.0, storm_top)]     # the recess
            if end in cab_at:
                s = cab_at[end]
                openings.append((min(s * storm_x, s * lining),
                                 max(s * storm_x, s * lining),
                                 0.0, sy(CAB_TOP_Y)))
            gaps = uncovered((-lining, lining, 0.0, crown),
                             plane_rects(faces, 2, end, (0, 1)), openings)
            for (x0, x1, y0, y1) in gaps[:4]:
                problems.append(
                    "%s: the car end at z %+.1f has a hole x %.1f..%.1f, "
                    "y %.1f..%.1f — daylight into the nose"
                    % (label, end, x0, x1, y0, y1))
            # ...and the recess is only an opening because the leaf closes it.
            leaf = end + (1.0 if end > 0 else -1.0) * (
                L.END_BAY.z(STORM_Z - STORM_STANDOFF_M)
                - L.END_BAY.z(END_WALL_Z))
            if uncovered(openings[0], plane_rects(faces, 2, leaf, (0, 1))):
                problems.append("%s: the storm door leaf at z %+.1f does not "
                                "fill its own recess" % (label, leaf))

        # ⭐ AND THE BAYS THEMSELVES MUST ABUT. Every run inside a bay can reach
        # both of its seams and the car still show a slot at every joint, if the
        # DEFINITIONS space the bays wrongly — which is a different file, owned
        # by a different tool, and this is the only place the two meet.
        spans = sorted(((-Z if flipped else Z) - units / 2.0,
                        (-Z if flipped else Z) + units / 2.0)
                       for (g, X, Z, flipped) in placements
                       for units in [{"int_panel": L.PANEL_UNITS,
                                      "int_door": L.DOOR_UNITS}.get(
                                         g, L.END_BAY.units)])
        for (a, b) in zip(spans, spans[1:]):
            if abs(a[1] - b[0]) > 1e-6:
                problems.append("%s: bays %.2f..%.2f and %.2f..%.2f %s at "
                                "their seam"
                                % (label, a[0], a[1], b[0], b[1],
                                   "overlap" if a[1] > b[0] else "leave a gap"))
        if not problems:
            notes.append("%-7s enclosure: both sides covered over z %.1f..%.1f,"
                         " both ends covered to the crown, %d bays abutting"
                         % (label, ends[0], ends[1], len(spans)))
    return problems


def read_emitted(obj_path):
    """The SHIPPED .obj, parsed back: (vertices, [(group, [indices])])."""
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
    return verts, faces


def check_emitted_winding(obj_path, notes):
    """⭐ THE WINDING QUESTION, ASKED OF THE FILE THAT SHIPS.

    `check`'s saloon-viewpoint test audits the M-space model — the faces as
    `quad()` wound them. It is blind to everything `to_obj_vertex` and
    `write_obj` then do, and those two are exactly where the handedness flip
    lives. The shell proved that the hard way: when `emit_x` had its sense
    inverted, every face on the car came out reversed, both of its M-space
    winding checks stayed green, and the model would have rendered as NOTHING.
    A check that does not apply the game's own transform is not a check.

    The interior has no roof to test, so it uses the fact one floor below:
    **the ceiling must face DOWN.** Every horizontal face in the top quarter of
    the saloon is crown, ad-band lip, map-board soffit or grab-rail underside,
    and not one of them legitimately points at the sky — so a simple majority
    is decisive and no per-face table has to be kept in step with the model.
    """
    verts, faces = read_emitted(obj_path)
    if not verts:
        return ["emitted winding: %s has no vertices" % obj_path]
    y_lo = min(v[1] for v in verts)
    y_hi = max(v[1] for v in verts)
    cut = y_lo + 0.75 * (y_hi - y_lo)
    up = down = 0
    for _group, idx in faces:
        pts = [verts[i] for i in idx]
        n = face_normal([(p[0], p[1], p[2]) for p in pts])
        if abs(n[1]) <= max(abs(n[0]), abs(n[2])):
            continue                     # not a horizontal face
        if sum(p[1] for p in pts) / len(pts) < cut:
            continue                     # not up in the ceiling
        if n[1] < 0:
            down += 1
        else:
            up += 1
    if up > down:
        return ["emitted winding: %d of %d ceiling faces point UP — the saloon "
                "is inside out and MTR would draw nothing. Check "
                "r62_layout.emit_x against write_obj's face reversal"
                % (up, up + down)]
    notes.append("emitted winding: %d ceiling faces point down, %d up — the "
                 "shipped .obj is inward-facing, as a room must be"
                 % (down, up))
    return []


def check_cab_side(obj_path, notes):
    """⭐ THE HALF-CAB MUST LAND BEHIND THE WINDSCREEN, MEASURED ON THE .OBJ.

    The shell and the interior are two files that each apply their own copy of
    the handedness flip to their own authored geometry. Both are authored in
    donor terms with the cab on +x at the +z end, so they agree BY
    CONSTRUCTION — right up until one of them is changed and the other is not,
    which is exactly what happened on 2026-07-30 when the shell was flipped
    and this file was not. Nothing in game reports it: the player simply looks
    through the windscreen at a saloon, and at the driver from the platform on
    the other side.

    So it is measured, in world space, off the shipped .obj: the vertices that
    `int_end_cab` has and `int_end_blind` does not ARE the cab, and their x
    must carry the sign `r62_layout` gives the windscreen through the same
    `emit_x` the shell uses.

    ⭐ IT DELIBERATELY READS `emit_x` RATHER THAN ASSERTING A SIDE. Turning
    `MIRROR_X` off is r62_layout's documented escape hatch back to the
    2026-07-28 build, and it reverts BOTH models together — that is not the
    defect. The defect is one file carrying its own copy of the transform, and
    that is what this fails on: exercised over all four combinations of
    (mirror, face reversal), it and `check_emitted_winding` between them pass
    only the two that are self-consistent.
    """
    verts, faces = read_emitted(obj_path)
    by_group = {}
    for group, idx in faces:
        by_group.setdefault(group, set()).update(
            (round(verts[i][0], 4), round(verts[i][1], 4), round(verts[i][2], 4))
            for i in idx)
    cab = by_group.get("int_end_cab")
    blind = by_group.get("int_end_blind")
    if not cab or not blind:
        return ["cab side: the emitted .obj has no int_end_cab/int_end_blind"]
    only = [p[0] for p in (cab - blind) if abs(p[0]) > 1e-6]
    if not only:
        return ["cab side: int_end_cab carries nothing int_end_blind does not"]
    want = 1.0 if L.emit_x(sx(L.WINDSCREEN_X_M[0])) > 0 else -1.0
    agree = sum(1 for x in only if (x > 0) == (want > 0))
    if agree < 0.9 * len(only):
        return ["cab side: %d of %d cab-only vertices are on x %s, but the "
                "shell puts the windscreen on x %s — the driver would be "
                "behind the blank end"
                % (len(only) - agree, len(only),
                   "+" if want < 0 else "-", "+" if want > 0 else "-")]
    notes.append("the half-cab is on the same side as the shell's windscreen "
                 "(%d/%d cab-only vertices at x %s, through emit_x)"
                 % (agree, len(only), "+" if want > 0 else "-"))
    return []


def check_against_donor(notes):
    """Re-measure every baked number against InteriorB.b3d, when it is there.

    The shell does the same thing for the same reason: a table of measurements
    can silently stop matching its source, and a converter that does not parse
    its donor has no other way to notice.
    """
    problems = []
    if not os.path.exists(DONOR):
        notes.append("donor interior not present — its cross-check was skipped")
        return problems
    obj = bve_csv.parse(DONOR)
    pts = [v for b in obj.builders for v in b.vertices]
    ext = obj.extents()

    def near(name, got, want, tol):
        if abs(got - want) > tol:
            problems.append("donor %s is %.4f, this converter uses %.4f"
                            % (name, got, want))

    near("floor", ext["y"][0], FLOOR_Y, 0.002)
    near("ceiling crown", ext["y"][1], CROWN_Y, 0.002)
    # The lining plane: the single most common |x| among the wall builders.
    lining = max({round(abs(v.x), 3) for v in pts if abs(v.x) < 1.25},
                 key=lambda a: sum(1 for v in pts if abs(abs(v.x) - a) < 1e-3))
    near("lining |x|", lining, LINING_X, 0.002)

    by_tex = {}
    for b in obj.builders:
        if b.texture:
            by_tex.setdefault(b.texture, []).append(b)

    def span(texture, axis):
        bs = by_tex.get(texture)
        if not bs:
            return None
        vals = [getattr(v, axis) for b in bs for v in b.vertices]
        return (min(vals), max(vals))

    rack = span("CeilingAdsLight.png", "y")
    if rack is None:
        problems.append("donor builder 43 (CeilingAdsLight.png) is missing")
    else:
        near("ad rack band bottom", rack[0], WALL_TOP_Y, 0.002)
        near("ad rack band top", rack[1], CROWN_Y, 0.002)
        # A millimetre of slack: the wall builders stop at 3.086 and the rack
        # starts at 3.086244, so the two donor surfaces do not share an exact
        # plane and neither do ours.
        if not (rack[0] <= L.INT_BAND_Y_M[0] + 0.002
                and L.INT_BAND_Y_M[1] <= rack[1] + 0.002):
            problems.append("the strip-map band leaves the donor's rack band")
        notes.append("strip map sits in the donor's own rack band "
                     "(y %.3f..%.3f), which is TWO racks — one per side, five "
                     "facets each" % rack)

    seats = [b for b in obj.builders
             if b.texture in ("SeatRed.png", "SeatYellow.png")
             and len(b.faces) > 100]
    if len(seats) != 2:
        problems.append("expected two 666-face bucket-seat builders, found %d"
                        % len(seats))
    else:
        notes.append("bucket seats simplified %d + %d donor faces -> %d "
                     "modelled buckets a bay a side, %g units apart, in %d "
                     "alternating tones"
                     % (tuple(len(b.faces) for b in seats)
                        + (int(round((L.PANEL_UNITS - 2 * BENCH_END_INSET)
                                     / BUCKET_UNITS)),
                           BUCKET_UNITS, len(SEAT_COLOURS))))
        # ⭐ THE SEAT PITCH, MEASURED. The donor's bucket contour is cut into
        # slabs and the SLAB pitch is what its z clusters actually show; three
        # slabs make one bucket and two buckets make one colour group, which is
        # the chain this converter's `SEAT_PITCH_M` and `SEAT_GROUP_M` rest on.
        # Measuring the group directly does not work — consecutive groups are
        # one slab apart, so any run-merge swallows them all.
        zs = sorted({round(min(b.vertices[i].z for i in f.indices), 3)
                     for b in seats for f in b.faces
                     if min(b.vertices[i].x for i in f.indices) > 0})
        steps = {}
        for a, b in zip(zs, zs[1:]):
            d = round(b - a, 3)
            if d > 0.01:
                steps[d] = steps.get(d, 0) + 1
        if steps:
            slab = max(steps.items(), key=lambda kv: kv[1])[0]
            if abs(slab * 3.0 - SEAT_PITCH_M) > 0.01:
                problems.append("the donor's seat slab pitch is %.3f m, so a "
                                "bucket is %.3f and not the %.3f this "
                                "converter paints"
                                % (slab, slab * 3.0, SEAT_PITCH_M))
            notes.append("donor slab pitch %.3f m x3 = one %.3f m bucket "
                         "(%g M units), x6 = its own %.2f m two-seat colour "
                         "group — which this converter deliberately does NOT "
                         "follow: the user's photographs alternate per SEAT"
                         % (slab, slab * 3.0, BUCKET_UNITS, SEAT_GROUP_M))

    # The half-cab: diagonally opposite, and on +x at the +z end — which is the
    # end this converter authors, and the same diagonal the shell's windshield
    # is on.
    cab = [b for b in obj.builders if b.texture in ("HalfCab.PNG",
                                                    "HalfCab1.png")]
    if len(cab) != 2:
        problems.append("expected two half-cab builders, found %d" % len(cab))
    else:
        for b in cab:
            e = b.extents()
            if (e["z"][0] > 0) != (e["x"][0] > 0):
                problems.append("the donor's half-cab at z %.2f is not on the "
                                "same side this converter authors" % e["z"][0])
            near("half-cab bulkhead", min(abs(e["z"][0]), abs(e["z"][1])),
                 CAB_BULKHEAD_Z, 0.005)
            near("half-cab inboard partition",
                 min(abs(e["x"][0]), abs(e["x"][1])), CAB_INNER_X, 0.005)
    notes.append("donor re-verified: %d builders, %d vertices, lining |x| %.3f"
                 % (len(obj.builders), len(pts), lining))
    return problems


def check_displays(model, notes):
    """⭐ EVERY STRIP-MAP DISPLAY MUST LAND ON THE BOARD THIS FILE PAINTS.

    Two models, two DIFFERENT flipped-z compositions, and a display that has to
    be rotated 90 degrees to face inboard — so this is the check that would
    actually catch a sign error, and MTR reports nothing at all when one
    happens: the text simply hangs on a cream wall.

    The housing is baked through the .obj's placement transform and each
    element through the .bbmodel's, and the display's centre is then required
    to lie inside a board face's footprint in WORLD space.
    """
    problems = []
    if not (os.path.exists(DOORS_MODEL) and os.path.isdir(DEFS)):
        notes.append("r62_doors.bbmodel not present — the display placement "
                     "check was skipped")
        return problems
    with open(DOORS_MODEL) as fh:
        bb = json.load(fh)
    try:
        with open(os.path.join(DEFS, "r62.json")) as fh:
            defs = {d["name"]: d for d in json.load(fh)["positionDefinitions"]}
    except OSError:
        return problems

    # Where the painted boards land, in WORLD space, through the .obj's own
    # placement transform. The board's footprint comes out of `MAP_BOARD_LOG`
    # rather than out of the written model, because the atlas has folded every
    # interior texture into ONE material and there is no name left to find the
    # board's faces by.
    boards = []
    for (bx, bz0, bz1, by0, by1) in MAP_BOARD_LOG:
        for lst, flipped in (("positions", False), ("positionsFlipped", True)):
            for e in defs.get("panel", {}).get(lst, []):
                Z = float(e.get("z", 0.0))
                pts = [B.obj_place(bx, y, z, 0.0, Z, flipped)
                       for y in (by0, by1) for z in (bz0, bz1)]
                boards.append((min(p[0] for p in pts), max(p[0] for p in pts),
                               min(p[1] for p in pts), max(p[1] for p in pts),
                               min(p[2] for p in pts), max(p[2] for p in pts)))
    if not boards:
        problems.append("no strip-map board faces to check the displays against")
        return problems

    checked = 0
    for e in bb.get("elements", []):
        if not e["name"].startswith("int_map"):
            continue
        c = [(e["from"][i] + e["to"][i]) / 2.0 for i in range(3)]
        if e.get("rotation"):
            # The plate is authored square to the car and yawed into place, so
            # the centre has to be turned about its own origin exactly as MTR
            # will turn it before the position transform is applied.
            o = e.get("origin") or [0, 0, 0]
            th = math.radians(e["rotation"][1])
            dx, dz = c[0] - o[0], c[2] - o[2]
            c = [o[0] + dx * math.cos(th) + dz * math.sin(th), c[1],
                 o[2] - dx * math.sin(th) + dz * math.cos(th)]
        for lst, flipped in (("positions", False), ("positionsFlipped", True)):
            for entry in defs.get("bbPanel", {}).get(lst, []):
                Z = float(entry.get("z", 0.0))
                wx, wy, wz = B.bb_place(c[0], c[1], c[2], 0.0, Z, flipped)
                hit = any(bx0 - 2.0 <= wx <= bx1 + 2.0
                          and by0 - 1.0 <= wy <= by1 + 1.0
                          and bz0 - 1.0 <= wz <= bz1 + 1.0
                          for (bx0, bx1, by0, by1, bz0, bz1) in boards)
                checked += 1
                if not hit:
                    problems.append(
                        "display %r placed by bbPanel/%s lands at world "
                        "(%.1f, %.1f, %.1f), not on any strip-map board — its "
                        "text would hang on the wall" % (e["name"], lst,
                                                         wx, wy, wz))
    if checked and not problems:
        notes.append("%d strip-map display placements all land on a painted "
                     "board, through both models' own flipped-z composition"
                     % checked)
    return problems


def check(model, tex, obj_path):
    problems, notes = [], []
    mats = used_materials(model, tex)
    problems += check_against_donor(notes)

    for name in model.order:
        if not model.groups[name].faces:
            problems.append("group %r is empty" % name)

    straddling = atlas_straddles(model, tex)
    if straddling:
        by_group = {}
        for name, _m, _b, _n in straddling:
            by_group[name] = by_group.get(name, 0) + 1
        problems.append("%d face(s) are not inside one atlas cell (%s) — a raw "
                        "UV that skipped Tex.uv"
                        % (len(straddling),
                           ", ".join("%s x%d" % kv
                                     for kv in sorted(by_group.items()))))

    # WINDING. Every single-sided face in the saloon shells has to be visible
    # from somewhere a rider can be; under MTR's unconditional backface culling
    # one that is not is simply not there.
    for name in ("int_panel", "int_door", "int_end_cab", "int_end_blind"):
        group = model.groups.get(name)
        if group is None:
            problems.append("winding check: no group %r" % name)
            continue
        if name.startswith("int_end"):
            # ⭐ AN END BAY IS LOOKED AT FROM DOWN THE CAR, so the viewpoints
            # have to include somewhere BEYOND its own -z seam. Without that,
            # every -z-facing plate in it — the bench end against the doorway,
            # a stanchion's near face — reads as blind, because every point
            # inside the bay is behind them. The end bay's neighbour is a real
            # door bay and a rider really does stand there.
            pts = ([(sx(x), sy(y), L.END_BAY.z(z)) for (x, y, z)
                    in CAB_VIEWPOINTS]
                   + [(sx(vx), sy(vy), L.END_BAY.z(6.0))
                      for (vx, vy) in SALOON_VIEWPOINTS]
                   + [(sx(vx), sy(vy), -L.END_BAY.units / 2.0 - 20.0)
                      for (vx, vy) in SALOON_VIEWPOINTS])
            seen, blind = facing_visible(group, pts)
        else:
            seen, blind = facing_inboard(group)
        if blind:
            problems.append("group %r has %d face(s) that no viewpoint in the "
                            "saloon can see (%d seen) — MTR would draw them as "
                            "nothing" % (name, blind, seen))
        for axis, value, kind in coplanar_overlaps(group):
            problems.append("group %r: two faces share the %s = %.3f plane and "
                            "overlap (%s)"
                            % (name, "xyz"[axis], value, kind))

    # NO LINING SLICE MAY CARRY HALF A WINDOW, checked on the pixels that ship.
    seen_tex = set()
    for key, t in sorted(tex.items()):
        if not key.startswith("int_wall_") or t.name in seen_tex:
            continue
        seen_tex.add(t.name)
        w = len(t.rows[0])
        for col, edge in ((0, "left"), (w - 1, "right")):
            cut = sum(1 for row in t.rows if row[col][3] == 0)
            if cut:
                problems.append("%s: %d transparent pixel(s) on its %s edge — "
                                "a lining aperture is being cut by a bay "
                                "boundary" % (t.name, cut, edge))

    # The lining's apertures must sit INSIDE the shell's, or the wall cavity —
    # and the door pocket in it — shows round the edge of a window.
    for (name, za, zb, y_lo, y_hi) in apertures():
        src = [p for p in B.ELEVATION_PANES if p[0] == name][0]
        if not (min(src[1], src[2]) < min(za, zb)
                and max(za, zb) < max(src[1], src[2])):
            problems.append("lining aperture %r is not inside the shell's in z"
                            % name)
        if not (src[3] < y_lo and y_hi < src[4]):
            problems.append("lining aperture %r is not inside the shell's in y"
                            % name)
    notes.append("%d lining apertures, inset %.0f/%.0f mm inside the shell's — "
                 "and no inner pane: the body already tints, and a second "
                 "would double the milkiness"
                 % (len(apertures()), APERTURE_INSET_Z * 1000,
                    APERTURE_INSET_Y * 1000))

    # ⭐ BAY TILING. A run that does not reach both ends of a REPEATED bay shows
    # a gap at every repeat. The door bay repeats three times and the panel bay
    # twice, so both have to be exact; the end bay's outer end is free, because
    # it is the car end and the nose is what closes it.
    for kind, bay, z_a, z_b in TILING_LOG:
        a, b = sorted((bay.z(z_a), bay.z(z_b)))
        want_a = -bay.units / 2.0
        want_b = bay.units / 2.0
        if abs(a - want_a) > 1e-6:
            problems.append("the %s run in the %s bay starts at %.3f, not at "
                            "the %.3f seam" % (kind, bay.name, a, want_a))
        if bay is not L.END_BAY and abs(b - want_b) > 1e-6:
            problems.append("the %s run in the %s bay ends at %.3f, not at the "
                            "%.3f seam" % (kind, bay.name, b, want_b))
    kinds = sorted({k for k, bay, _a, _b in TILING_LOG if bay is L.DOOR_BAY})
    notes.append("the door bay carries %s, each spanning its full %g units"
                 % ("/".join(kinds), L.DOOR_BAY.units))

    problems += check_displays(model, notes)
    problems += check_enclosure(model, notes)
    problems += check_emitted_winding(obj_path, notes)
    problems += check_cab_side(obj_path, notes)

    # The written OBJ: UV range, materials declared, map_Kd resolvable.
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
    with open(os.path.join(os.path.dirname(obj_path), MTL_NAME)) as fh:
        for line in fh:
            if not line.startswith("map_Kd "):
                continue
            ident = line.split(None, 1)[1].strip()
            if ident.count(".") != 1:
                problems.append("map_Kd %r must contain exactly one dot" % ident)
            if ident != ident.lower():
                problems.append("map_Kd %r is not lowercase" % ident)
            rel = ident.split(":", 1)[1] if ":" in ident else None
            if rel and not os.path.exists(os.path.join(OUR_NS, rel)):
                problems.append("map_Kd %r has no file" % ident)

    # Group names <-> the properties files that bind them, both directions.
    declared = set()
    for model_path, prop in B.index_model_entries():
        if os.path.basename(model_path) != OBJ_NAME:
            continue
        with open(prop) as fh:
            for part in json.load(fh).get("parts", []):
                declared |= set(part["names"])
    if not declared:
        problems.append("the index binds no %s — run tools/gen_r62_assets.py "
                        "and tools/gen_vehicle_index.py" % OBJ_NAME)
    else:
        for name in sorted(declared - obj_groups):
            problems.append("properties name %r has no group in %s"
                            % (name, OBJ_NAME))
        for name in sorted(obj_groups - declared):
            problems.append("group %r is not bound by any properties file"
                            % name)

    # ⭐ POCKET CLEARANCE. An interior part reaching into the sliding leaves'
    # corridor would be sliced open every time a door opened, and the leaf
    # retracts PAST the door bay into whatever is next to it — so this is
    # measured on the assembled car, not per bay.
    tris = sum(len(vs) - 2 for g in model.groups.values() for _m, vs in g.faces)
    notes.append("%d groups, %d faces, %d triangles"
                 % (len(model.order),
                    sum(len(g.faces) for g in model.groups.values()), tris))
    notes.append("%d materials, one %dx%d atlas"
                 % (len(mats), tex["_atlas"].w, tex["_atlas"].h))

    pocket = sx(L.LINING_MAX_X_M)
    for label, (definition, properties) in sorted(VARIANTS.items()):
        placements, dupes = B.load_placements(definition, properties)
        for d in dupes:
            problems.append("%s: %r placed twice at z=%s flipped=%s"
                            % (label, d[0], d[2], d[3]))
        if not placements:
            problems.append("%s: no placements at all" % label)
            continue
        built = assemble(model, placements)
        pts = [v for g in built.groups.values() for _m, vs in g.faces for v in vs]
        zs = [p[2] for p in pts]
        xs = [abs(p[0]) for p in pts]
        notes.append("%-7s %2d placements, z %+7.1f..%+7.1f (car %d), "
                     "|x| max %.2f (pocket starts %.2f)"
                     % (label, len(placements), min(zs), max(zs), L.UNITS,
                        max(xs), pocket))
        if max(abs(min(zs)), abs(max(zs))) > L.UNITS / 2 + 1e-6:
            problems.append("%s: geometry reaches %.1f, past the %d-unit car "
                            "end" % (label, max(abs(min(zs)), abs(max(zs))),
                                     L.UNITS))
        if max(xs) > pocket + 1e-6:
            problems.append("%s: |x| reaches %.2f, inside the corridor the "
                            "sliding leaves need (%.2f)"
                            % (label, max(xs), pocket))
        # ...and nothing may reach past the storm door, which is where the
        # interior ends and the bullnose begins.
        front = max(abs(p[2]) for p in pts)
        limit = L.END_Z + L.END_BAY.z(L.STORM_DOOR_Z)
        if front > limit + 1e-6:
            problems.append("%s: geometry reaches z %.1f, past the storm door "
                            "at %.1f — it would be inside the nose"
                            % (label, front, limit))
    return problems, notes


# ==================================================================== main

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true",
                    help="cross-check the written model against the shell, the "
                         "donor and the properties files")
    ap.add_argument("--assemble", metavar="DIR",
                    help="bake every bay at its definition position into "
                         "whole-car preview OBJs in DIR, for render_obj.py")
    ap.add_argument("--with-shell", action="store_true",
                    help="with --assemble, fold the exterior shell into the "
                         "same preview so the through-the-door view is real")
    args = ap.parse_args()

    model, tex = build_model()
    mats = used_materials(model, tex)
    obj_path = os.path.join(MODEL_DIR, OBJ_NAME)

    if args.assemble:
        os.makedirs(args.assemble, exist_ok=True)
        doors_png = os.path.join(os.path.dirname(TEX_DIR), "doors_box.png")
        if os.path.exists(doors_png):
            tex["doors_box"] = Tex("doors_box", pngtool.read_png(doors_png)[2])
            leaf_preview(model, tex)
            mats = used_materials(model, tex)
        shell_model, shell_tex = (B.build_model() if args.with_shell
                                  else (None, None))
        all_mats = dict(mats)
        if shell_model is not None:
            # The door leaves live in the .bbmodel, so the shell's own preview
            # bakes them in for rendering only. Without them every doorway is a
            # hole and the one view this composite exists for — looking THROUGH
            # a closed car — is not available.
            doors_png = os.path.join(os.path.dirname(B.TEX_DIR), "r62",
                                     "doors_box.png")
            if os.path.exists(doors_png):
                shell_tex["doors_box"] = B.Tex("doors_box",
                                               pngtool.read_png(doors_png)[2])
                B.leaf_preview(shell_model, shell_tex)
            all_mats.update(B.used_materials(shell_model, shell_tex))
            B.write_textures(shell_tex, set(all_mats), args.assemble)
        write_textures(tex, args.assemble)
        for label, (definition, properties) in sorted(VARIANTS.items()):
            placements, _ = B.load_placements(definition, properties)
            built = assemble(model, placements)
            if "door_leaf_preview" in model.groups:
                built.group("door_leaf_preview").faces = list(
                    model.groups["door_leaf_preview"].faces)
            if shell_model is not None:
                shell_props = B.VARIANTS[label][1]
                sp, _ = B.load_placements(B.VARIANTS[label][0], shell_props)
                shell_built = B.assemble(shell_model, sp)
                if "door_leaf_preview" in shell_model.groups:
                    # Already in world space — `assemble` only walks the groups
                    # a definition names, so it copies across verbatim.
                    shell_built.group("door_leaf_preview").faces = list(
                        shell_model.groups["door_leaf_preview"].faces)
                for name in shell_built.order:
                    dst = built.group("shell_" + name)
                    dst.faces = list(shell_built.groups[name].faces)
            path = os.path.join(args.assemble, "r62_int_%s.obj" % label)
            nv, nf = write_obj(built, path, "r62_int_%s.mtl" % label, all_mats,
                               lambda t: t.name + ".png")
            print("%-26s %5d verts %5d faces" % (os.path.basename(path), nv, nf))
        path = os.path.join(args.assemble, "r62_int_bays.obj")
        nv, nf = write_obj(model, path, "r62_int_bays.mtl", mats,
                           lambda t: t.name + ".png")
        print("%-26s %5d verts %5d faces" % (os.path.basename(path), nv, nf))
        return 0

    if not args.check:
        nv, nf = write_obj(model, obj_path, MTL_NAME, mats, lambda t: t.map_kd)
        written = write_textures(tex, prune=True)
        print("r62_interior.obj: %d groups, %d verts, %d faces"
              % (len(model.order), nv, nf))
        print("r62_interior.mtl: %d materials" % len(mats))
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
