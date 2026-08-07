#!/usr/bin/env python3
"""Converts the openBVE LIRR M7 donor's SALOON INTERIOR and CABS into MTR .obj.

    python3 tools/convert_m7_interior.py            # write model + textures
    python3 tools/convert_m7_interior.py --check    # self-checks only
    python3 tools/convert_m7_interior.py --assemble DIR   # whole-car previews

WHAT THIS WRITES
----------------
    assets/station_announcer/models/vehicle/m7_interior.obj   one `g` per part
    assets/station_announcer/models/vehicle/m7_interior.mtl
    assets/station_announcer/textures/vehicle/m7/int_atlas.png
                                                 int_cab_atlas.png
                                                 int_glass.png

The bay geometry comes from `tools/m7_layout.py`, which is the single source of
truth the body converter also adopts — change a dimension THERE, never here.
Read `M7_CONVERSION_NOTES.md` first: the MTR .obj loader's units, axes, UV
convention and flipped-position trap were expensive to establish and this file
assumes every one of them.

THE ART IS DRAWN, NOT SAMPLED (2026-07-28, user decision)
---------------------------------------------------------
The first cut of this converter sliced the donor's photographic interior
textures straight into an atlas. It read as a photo pasted into Minecraft, so
every pixel is now PAINTED here, in the mod's own house style — the same method
`tools/gen_zebra_assets.py` and `tools/gen_pipe_assets.py` use for the station
blocks. Flat MTR tones, one highlight and one shade per material, and no baked
lighting: INTERIOR parts render CUTOUT_BRIGHT, so a gradient reads as dirt, not
as light. `PX_PER_UNIT` sets the density (2.5 px per M unit = 40 px/block) and
every texture's size is DERIVED from the geometry it covers, so a resize in
m7_layout.py re-proportions the art instead of stretching it.

The donor is still the authority on GEOMETRY — the CSVs are parsed exactly as
before — and on where a window sits along the car (`WINDOW_U`, measured off its
elevation so an interior window lands behind its exterior one). Only the pixels
changed. Nothing in this file opens a donor PNG any more.

Consequences worth knowing:
* Three textures instead of twelve: one saloon atlas, one cab atlas, and the
  translucent glass (which needs its own file because its material carries the
  `#interior_translucent` flag). Three draw batches, not twelve.
* 512x512 + 256x256 instead of 1024x2048 + 512x1024 — about a tenth of the
  pixels, and every one of them at the density the model can actually show.
* ⚠️ Atlasing the wall elevations turned a latent bug live: `wall_run` used to
  write the wall profile's own v STRAIGHT into the OBJ, which is correct for a
  standalone file and addresses the whole sheet once the file is a cell. Every
  coordinate written for an atlased texture must go through `Tex.uv`.
  `atlas_straddles` (in `check`) now fails the build on any face that leaves
  its cell, which is the general form of that mistake.
* The ad card no longer carries the donor pack's credit panel — see `poster`.

IT REPLACES THE BODY'S PLACEHOLDER LINING
-----------------------------------------
`convert_openbve_m7.py` synthesises a bare lining shell into groups `window`,
`door`, `end_cab`, `end_gangway` (+`_mini`) so the car is not see-through. Those
must be dropped from the properties files when this model is wired in: the
placeholder ceiling is a flat plate at donor y 3.350 and the real one is a coved
ceiling whose crown is at 3.400, so the placeholder would sit BELOW and hide it.
Every group here is prefixed `int_` so the two can coexist in the same index
while that swap happens.

EVERY INTERIOR PART IS FULL WIDTH AND PLACED UNFLIPPED
------------------------------------------------------
The body models one side and mirrors it with `positionsFlipped`. The interior
cannot: the M7 seats 3+2, so the two sides of the car are genuinely different.
Modelling full width and placing with positions-only lists costs nothing (the
mirrored half was going to be drawn either way) and it sidesteps the flipped-z
composition trap entirely. The two END groups are the one exception — an end is
not symmetric in z, so they keep the body's `end1` unflipped / `end2` flipped
convention, authored with their outward face at local -z.

WHAT WAS SIMPLIFIED (and why)
-----------------------------
* Seat armrests (asiento*.csv builders 4/5) are dropped: 12 of each seat's 22
  quads for a 40 mm box that is 0.037 blocks across in game.
* The eight EMERGENCY EXIT decals (AInterior 35-42) sit at absolute z values
  that no repeating bay can carry, and are 92x32 px. Dropped.
* BInterior.csv's lavatory is out of scope (v1 is the A car).
* Both sides of the car get the same drawn wall elevation; the donor's two
  side textures differ only by a printed decal on one window.
* Windows are OPAQUE glazing, not holes. Keying them would make every window a
  hole through the car, because the exterior skin is single-sided and invisible
  from inside. Drawn as a slate-blue two-tone so they read as glass rather than
  as a void.
* End panels and the cab's nose door are drawn CONSTANT along u. The donor
  mirrors them with negative UVs, and `wrap01` folds -1 to 0, so the u range
  each one actually samples differs per builder and is sometimes a single
  column. Horizontal banding is the one thing every mapping gets right.

THE CABS (added 2026-07-28)
---------------------------
`minicabina.csv` — NOT `minicabina2.csv`. The two model the same room; the "2"
copy is the trailing cab with most of its `SetColor` lines overwritten to
0,0,0, i.e. deliberately blacked out. The leading copy keeps the real
grey-blue (56,73,80) console tones, and it is the one whose cab door is CLOSED
(minicabina2 has the leaf swung into the room at x +0.400).

The cab is donor z 11.300..12.670, which lands in the OUTBOARD ~20 of an end
bay's 74 units (61 for the mini) — see `build_cab`. It is one more group,
`int_cab`(`_mini`), placed at exactly the positions `int_end_cab`(`_mini`) uses,
because it is the same end. `int_end_cab` therefore no longer caps the cab-door
recess with a dark panel: the cab's own door leaf IS that cap now.

⚠️ WHAT THE CAB IS AND IS NOT VISIBLE THROUGH (measured, 2026-07-28)
The brief for this pass assumed the body's windshields are cut-out holes. They
are NOT. `textures/vehicle/m7/frente.png` has zero transparent pixels anywhere
near the windscreens (the only alpha in it is the two headlight circles), and
`lat_cab.png` has none at all — the donor's M7frente.png is a photographic dark
mask with the glass PAINTED ON, and the body converter passes it through. So
nothing of this cab can be seen from outside the car, in any view.

What it IS seen through is the cab door: `puertacab1/2.png` carry a blue-keyed
window (u 0.24..0.74, v 0.14..0.39 → x +0.21..-0.19, y 2.58..3.11 m), so a
rider standing in the saloon looking forward sees the cab through it. That view
looks at the cab FROM BEHIND, which is the wrong side of most console surfaces,
so the console/panel cluster is emitted with both windings (`CAB_TWO_SIDED`).
The lining, floor, ceiling and front mask are single-sided: their visible side
faces the cab either way.

If the cab should ever read from outside, the fix is in the BODY converter
(key the windscreen rectangles of M7frente.png), not here.
"""

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bve_csv
import convert_openbve_m7 as B     # window positions only — see saloon_apertures()
import m7_layout as L
import pngtool

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")
MODEL_DIR = os.path.join(OUR_NS, "models/vehicle")
TEX_DIR = os.path.join(OUR_NS, "textures/vehicle/m7")
TEX_ID = "station_announcer:textures/vehicle/m7"

DONOR = ("/Users/thomasdemuth/openBVE/Train/LIRR & MNCR Bombardier M7 EMU Pack "
         "V1.1/Long Island Rail Road M7/2 Car M7/Model")

sx, sy = L.sx, L.sy

# ------------------------------------------------------------------ profiles
#
# Straight off AInterior.csv builders 1/2 (LATERAL). The lining is a flat-ish
# panel INBOARD of the body skin, not the skin pulled in: |x| 1.380 at the cant
# rail, bulging to 1.470 at the tumblehome's widest, back to 1.400 at the sill.
# v is the latint.png row fraction from the TOP, the donor's own convention,
# which MTR's obj path shares — so it passes through untouched.
WALL_PROFILE = [                 # (|x|, donor y, v)
    (1.380, 3.400, 0.00),
    (1.470, 1.900, 0.63),
    (1.460, 1.700, 0.72),
    (1.400, 1.370, 0.83),
    (1.400, 1.000, 1.00),        # below the floor; the floor hides it
]

# AInterior builder 19: the coved ceiling, +x to -x, u across the car.
CEILING_PROFILE = [              # (x, donor y, u)
    (1.410, 3.100, 0.00),
    (1.300, 3.250, 0.05),
    (1.150, 3.350, 0.10),
    (0.950, 3.400, 0.15),
    (-0.950, 3.400, 0.85),
    (-1.150, 3.350, 0.90),
    (-1.300, 3.250, 0.95),
    (-1.410, 3.100, 1.00),
]

# AInterior builders 16/18: the shallower cove that carries the EMERGENCY EXIT
# header over each doorway, in place of the deep cove.
DOOR_COVE_PROFILE = [            # (|x|, donor y, v)
    (1.150, 3.400, 0.00),
    (1.250, 3.200, 0.80),
    (1.390, 3.200, 1.00),
]

# AInterior builder 3 (PROTECTOR): the vestibule partition, full width, with
# u measured on the +x half (the -x half is 1-u).
PARTITION_PROFILE = [            # (|x|, donor y, u at +x, v)
    (1.400, 1.295, 0.020, 1.000),
    (1.400, 1.370, 0.020, 0.950),
    (1.460, 1.700, 0.002, 0.900),
    (1.470, 1.900, 0.000, 0.720),
    (1.380, 3.400, 0.030, 0.000),
]

FLOOR_HALF = 1.400               # donor |x| the floor reaches — the sill width
CEILING_CROWN = 3.400
DOOR_HEAD_Y = 3.200              # donor y where the door cove meets the wall

# Overhead grab equipment (AInterior 43-74). One bracket + one barred panel per
# side per bay; the donor's bracket pitch is 1.85 m and a window bay is 1.83 m,
# so one per bay reproduces the real rhythm exactly.
GRAB_BRACKET = (0.850, 1.400, 2.940, 3.140)      # |x0|, |x1|, y0, y1
GRAB_PANEL = (0.880, 1.380, 3.080, 2.970)        # |x_hi_edge|.. -> sloped strip

# Where the seat benches sit across the car (A.Animated). The M7 is 3+2: the
# three-abreast bench is 1.30 m wide at donor x -0.740, the two-abreast one
# 0.95 m at +0.945. That asymmetry is the whole reason the interior is modelled
# full width instead of being mirrored.
BENCH_WIDE = ("asiento3.csv", "asiento3180.csv", -0.740, 3)
BENCH_NARROW = ("asiento2.csv", "asiento2180.csv", 0.945, 2)
SEAT_CENTRE_M = 0.25             # donor z the seat meshes are centred on
SEAT_PAN_Y = 1.520               # donor y of the seat cushion, for SEAT markers
SEAT_SKIP_BUILDERS = (4, 5)      # armrests — see the module docstring
SEAT_BACK_BUILDER = 2            # asiento*.csv: the back shell, behind builder 1
BENCH_HALF_M = {3: 0.650, 2: 0.475}   # donor half-width, by seats abreast

# The advertising card on the vestibule wall, and how big it hangs.
POSTER_W_M = 0.470               # along z
POSTER_H_M = 0.855
POSTER_MID_Y = 2.350
POSTER_PROUD = 0.012             # metres inboard of the wall, so it cannot fight

GLASS_PROUD = 0.014              # metres the partition glazing stands off
GLASS_RGBA = (198, 214, 224, 76)
# The donor draws each partition twice, at the same z, with different art per
# side. MTR culls backfaces so only one can ever be visible and coplanar is
# safe there — but nothing else in this project relies on culling to avoid
# z-fighting, and an offline render (which does not cull) turns the pair into
# static. Half a millimetre of separation costs nothing and removes the class.
#
# ⭐ SUPERSEDED BY REAL THICKNESS (2026-07-28 depth pass): the partition is now
# PARTITION_THICK_M apart skin to skin, with real returns round the aisle
# opening, so the two skins are nowhere near coplanar. The constant survives as
# the minimum separation the returns are allowed to collapse to.
PARTITION_SPLIT = 0.0015


# ============================================================== DEPTH PASS ===
#
# ⭐ 2026-07-28, user request: "add depth to make everything look more real and
# polished, but still minecrafty". Everything below is RELIEF — real stepped
# boxes where the interior previously had paint on a flat sheet. House style is
# MTR's own (read off `r179.bbmodel`: `lower_wall` is a 1-unit-thick box,
# `seat_back_1` 2 units, `window_light_3` a 2x3-unit box proud of the wall), so
# these are chunky 1-2 unit steps, never smooth curves.
#
# ⚠️ THE SALOON IS FULL. Every number here was chosen against a measured
# clearance, not picked, because the donor's 3+2 seating leaves almost nothing
# between the furniture and the lining. `interior_clearances()` in `check`
# re-measures every one of them on the geometry that ships, so a later change to
# a seat position or a bay size cannot silently push two of these into each
# other. What the measurements say (donor metres, at the window band):
#
#   lining at the window sill (y 2.181)   |x| 1.4532
#   the 2-abreast bench's outboard edge   |x| 1.4200      -> 33 mm of wall
#   the overhead panel's inboard edge     |x| 0.8800
#   the body-envelope check's own ceiling |x| 1.4800  (= sx 21.96 units)
#
# THE ONE THING THAT COULD NOT BE DONE, and why (it was in the brief):
# ⭐ NO WINDOW SILL OR REVEAL. Both directions are blocked, by 33 mm and 27 mm:
#   * INBOARD (a proud sill ledge): the aperture's bottom edge is at y 2.181,
#     which is 219 mm BELOW the top of a seat back (y 2.400) — the 3+2 benches
#     overlap the windows vertically. A ledge there has 33 mm before it saws
#     through the 2-abreast bench at every seat row. 33 mm is half an M unit;
#     at 1/16 block it would not be visible, let alone Minecrafty.
#   * OUTBOARD (a recessed reveal into the 100 mm skin-to-lining cavity): the
#     lining's widest point is |x| 1.470 and `check` caps the whole interior at
#     |x| 1.480 — 10 mm. The cap could be refined to the body's real side
#     profile (1.52..1.56 in the window band) to unlock ~70 mm, but the cavity
#     IS the sliding door's pocket, and `--check` already reports an OPEN leaf
#     covering 3.3 units of the gangway-end pane, so a reveal on those panes
#     would be sliced by the leaf every time a door opened.
# The head trim rail below is what the wall gets instead: it lands in the one
# free band on the lining (y 2.79..2.91, between the window head and the grab
# bracket), it runs the whole car, and it is a full unit deep because there is
# nothing there to hit.

# MTR'S OWN MEASUREMENTS (surveyed across all 16 of its vehicle bbmodels,
# 2026-07-28) — this is the house style every number below is quantised to:
#   * the minimum real element thickness is EXACTLY 1 unit. Nothing in MTR's
#     corpus lives between 0.3 and 1 except the two door-plane offsets. So a
#     relief is a whole unit deep or it is a decal, never 0.6 of one.
#   * a decal is a FLAT QUAD standing 0.1 off its panel (79 uses). Marker and
#     head lights are done that way; there is not one proud box among them.
#   * ceiling lights: 0.1-0.2 proud of the ceiling, or a 3x1 proud box.
#   * luggage racks: a 2-thick fascia plus a 1-thick shelf.
#   * poles and handrails: a degenerate box inflated 0.2, i.e. 0.4 square.
#   * abutting parts are BURIED 0.05-0.4 into their neighbour, never coplanar.
#   * ⭐ there is no window-sill ledge box anywhere in MTR's corpus. A sill is
#     an angle change in the wall profile — which is exactly what this car's
#     tumblehome already is.
UNIT_M_X = 1.0 / L.X_SCALE       # one M unit, in donor metres, across the car
UNIT_M_Y = 1.0 / L.Y_SCALE_ABOVE # ...and vertically
BURY_M = 0.010                   # ~0.15 units of overlap at every abutment

# --- the ceiling's fluorescent strips, as real housings hanging below the crown
# MTR's 3x1 proud box, verbatim: 3.4 units across, 1 unit deep. The crown is
# flat from |x| 0.950 inboard so a box here touches nothing, and the only
# neighbour is the overhead grab gear, whose fascia starts at |x| 0.846.
LAMP_BOX_X = (0.580, 0.810)      # donor |x|, inboard..outboard edge
LAMP_DROP_M = 1.0 * UNIT_M_Y     # below CEILING_CROWN

# --- the continuous trim rail above the windows (this is the wall's depth)
# MTR gets wall relief from profile kinks, not from stepped panels, and this
# car's tumblehome already has four. What it does NOT have is anything in the
# one free band on the lining — between the window head (y 2.685) and the grab
# bracket (2.940) — so the M7's real cant-rail trim goes there, at the house
# minimum of one full unit deep. It is the only relief on the side wall, and
# the measured reason there is no second one is in the block above.
TRIM_Y = (2.800, 2.910)          # donor y, bottom..top of the rail's face
TRIM_DEPTH_M = 1.0 * UNIT_M_X    # inboard of the lining

# --- the overhead luggage rack gets MTR's own fascia
# The donor's rack is a single sloped strip whose inboard edge is a knife. The
# house form is "2-thick fascia + 1-thick shelf": the strip is the shelf, this
# is the fascia. Chosen over a round grab tube (the other candidate) because a
# 0.4-unit pole is 2.5 cm in world and reads as a scratch, while a 1x1.5-unit
# downstand catches the eye the length of the car.
FASCIA_X = 0.880                 # donor |x| — the panel's own inboard edge
FASCIA_TOP = 2.970               # donor y — ...at its own height
FASCIA_THICK_M = 1.0 * UNIT_M_X
FASCIA_DROP_M = 1.5 * UNIT_M_Y

# --- the seats
# MTR's canonical seat is a 1-thick cushion and a 1-thick back; the r179
# longitudinal bench uses 2. One unit it is. Headrests are a separate box per
# seat in MTR's transverse seats, which at 16 rows x 5 seats would cost more
# than every other relief in this file put together — so the existing painted
# scallop keeps doing that job and the top cap is merely SPLIT at it, which
# buys the same silhouette for 3 quads a row instead of 23.
SEAT_BACK_T = 1.1 * UNIT_M_X     # donor metres of backrest thickness
SEAT_NOTCH_M = 0.090             # the gap between two headrests
SEAT_NOTCH_V = 0.075             # ...and how far down the art cuts it
SEAT_RETURN_Y = 1.850            # below this the donor's own side panel covers

# --- the vestibule partition
# Skin to skin. Lives in m7_layout now — gen_m7_doors needs it too, to
# stand the next-stop display just proud of the skin this builds.
PARTITION_THICK_M = L.PARTITION_THICK_M

# ⭐ THE AD CARDS STAY FLAT, deliberately. A framed card was costed at 64
# triangles and dropped: MTR treats every poster, decal and sign in its corpus
# as a flat quad 0.1 off its panel, and this one already stands 0.012 m (0.18
# units) proud. Boxing it would be the one fitting in the car that disagrees
# with all 79 of MTR's.


# ----------------------------------------------------------------------- cab
#
# See the module docstring for why this is minicabina.csv and not minicabina2.

CAB_CSV = "minicabina.csv"
CAB_OUTER_Z = 12.670             # donor z of the cab's outermost surface

# The donor cab's front wall sits on EXACTLY the plane the body's own front
# mask occupies (both are the donor's z 12.500..12.670 splay), and its rear
# bulkhead on exactly the plane AInterior's cab-door recess ends at. Coplanar
# pairs are safe in game — MTR culls backfaces and only one of each pair ever
# faces the camera — but they shimmer in any renderer that does not cull, which
# is the only way this model gets looked at before it ships. So the whole cab
# is squeezed a hair inboard about its rear bulkhead, and the two transverse
# panels that would still land on a saloon-side plane get their own standoff.
CAB_INSET_M = 0.015              # the outermost surface, pulled off the skin
CAB_BULKHEAD_STANDOFF_M = 0.004  # the cab face of the saloon bulkhead
CAB_DOOR_STANDOFF_M = 0.010      # the door leaf, off the back of its recess
# The leaf is drawn twice at one z with different art per side, exactly like
# the vestibule partitions — same reasoning as PARTITION_SPLIT, same fix.
CAB_DOOR_SPLIT_M = 0.0015

# FRENTE/FRENTE2 are authored on the BODY's cross-section (|x| out to 1.570),
# not the lining's — the donor never translated them the way it translated the
# cab's own LATERAL strips. Pulling their outboard vertices in by the same
# 0.100 the lining got lands them on WALL_PROFILE exactly (1.48->1.38,
# 1.57->1.47, 1.56->1.46, 1.50->1.40), so the front meets the side with no step
# and nothing in the cab reaches the body skin.
CAB_NOSE_PULL_M = 0.100
CAB_NOSE_PULL_FROM = 1.0         # |x| at or beyond which a vertex is "outboard"

CAB_NOSE_BUILDERS = (5, 6)                      # FRENTE, FRENTE2
CAB_BULKHEAD_BUILDERS = (11, 13)                # ATRAS izquierda / derecha
# PUERTA DE CABINA: 25 is the skin that faces the cab, 26 the one that faces
# the saloon, so 25 is the one that stands slightly further into the cab.
CAB_DOOR_BUILDERS = {25: +CAB_DOOR_SPLIT_M, 26: -CAB_DOOR_SPLIT_M}

# Builders this converter does not copy:
#   0    ABAJO      floor — donor UVs tile 0..5, and it is 1.500 wide against
#                   the saloon's 1.400 sill. Rebuilt by `cab_floor`.
#   1,2  LATERAL    the lining — identical to WALL_PROFILE, so `wall_run` draws
#                   it instead and it continues the saloon's wall seamlessly.
#   3    CABINA     a puertaint skin in the door plane; builders 25/26 are the
#                   real leaf and would z-fight it.
#   19,20 portavasos  a 100 mm cup holder for 16 faces.
#   37,38 armrests    the same call the saloon seats already make.
#   39,40 parasoles   the sun visors. 0.600 x 0.550 m blades at z 12.30/12.40,
#                     y 2.700..3.250 — i.e. hung across the top of the
#                     windshield, right where a driver in the cab seat looks
#                     out. One single-sided face each, so they are a flat
#                     slab of CAB_DESK with no edge to read them by, and
#                     in game they simply blank the forward view (reported
#                     2026-07-30). Dropped; the openBVE cab never had a
#                     camera behind them.
CAB_SKIP = (0, 1, 2, 3, 19, 20, 37, 38, 39, 40)

# The console/panel cluster, emitted with both windings — see the docstring's
# visibility note: the only view into this room is from BEHIND it.
CAB_TWO_SIDED = (7, 8, 9, 10, 12, 14, 15, 16, 17, 18, 21, 22, 23, 24, 42, 43)

# Builders that get a planar unwrap instead of a flat swatch (`plane_uv`).
# Only the desk: it is the one UV-less surface big enough, and square-on
# enough to the driver, to be worth drawing on.
CAB_PROJECTED = (17,)

# builder -> texture key. Everything the donor drew with SetColor becomes a
# flat swatch; everything it drew with LoadTexture keeps its own art and UVs.
CAB_MATERIAL = {
    4: "cab_nose_door", 5: "cab_front", 6: "cab_front",
    7: "cab_panel", 8: "cab_panel", 9: "cab_panel", 10: "cab_panel",
    11: "cab_panel", 12: "cab_panel", 13: "cab_panel",
    14: "cab_panel", 15: "cab_panel_dark",
    16: "cab_panel", 17: "cab_desk", 18: "cab_panel",
    21: "cab_panel", 22: "cab_console", 23: "cab_panel_dark", 24: "cab_panel",
    25: "cab_door_cab", 26: "cab_door_saloon",
    27: "cab_panel", 28: "cab_panel", 29: "cab_ceiling",
    30: "cab_light", 31: "cab_light",
    32: "cab_panel", 33: "cab_panel",
    34: "seat_side", 35: "seat_narrow", 36: "seat_side",
    41: "driver_seat", 42: "cab_console", 43: "cab_console",
}

CAB_SIDE_END_Z = 12.500          # donor z where the lining meets the front
CAB_FLOOR_NOSE_Z = 12.670        # donor z of the floor's centre point
CAB_FLOOR_NOSE_HALF = 0.370      # donor |x| the nose narrows the floor to
CAB_CEILING_HALF = 1.480         # donor |x| of TECHO, = the body's roof line


# ------------------------------------------------------------------ mesh model

class Group:
    """One MTR part: a named bag of faces, each face carrying its material."""

    def __init__(self, name):
        self.name = name
        self.faces = []          # (material, [(x, y, z, u, v), ...])

    def add(self, material, verts, two_sided=False, reverse=False):
        vs = list(verts)
        if reverse:
            vs = list(reversed(vs))
        self.faces.append((material, vs))
        if two_sided:
            self.faces.append((material, list(reversed(vs))))

    def quad(self, material, p0, p1, p2, p3, **kw):
        self.add(material, [p0, p1, p2, p3], **kw)

    def bounds(self):
        pts = [v for _m, vs in self.faces for v in vs]
        if not pts:
            return None
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
    """Newell's method — the tumblehome makes plenty of non-planar quads."""
    nx = ny = nz = 0.0
    for i in range(len(vs)):
        x0, y0, z0 = vs[i][0], vs[i][1], vs[i][2]
        j = (i + 1) % len(vs)
        x1, y1, z1 = vs[j][0], vs[j][1], vs[j][2]
        nx += (y0 - y1) * (z0 + z1)
        ny += (z0 - z1) * (x0 + x1)
        nz += (x0 - x1) * (y0 + y1)
    return nx, ny, nz


# ---------------------------------------------------------------- textures
#
# Every texture is either a standalone PNG or a cell in ONE atlas. The rule is
# mechanical: a donor image whose UVs all land inside [0,1] can be atlased; one
# the donor TILES (the floors, the ceiling, the wall elevations) cannot, because
# the loader's UVs have to stay in [0,1] and a tiled cell would bleed into its
# neighbours. Atlasing matters because MTR makes one draw batch per
# (group x material), and the interior would otherwise add ~25 of them.

ATLAS_PAD = 4


class Tex:
    """A texture the model can name: standalone, or a cell inside the atlas."""

    def __init__(self, name, rows, src_w=None, x0=0, x1=None):
        self.name = name
        self.rows = rows
        self.src_w = src_w                       # for crops: the ORIGINAL width
        self.x0 = x0
        self.x1 = x1 if x1 is not None else (src_w or len(rows[0]))
        self.atlas = None                        # (u0, v0, du, dv) once packed
        self.atlas_name = None                   # which atlas packed it
        self.atlas_tex = None                    # ...and that atlas's own Tex
        self.flag = ""                           # e.g. "#interior_translucent"

    @property
    def w(self):
        return len(self.rows[0])

    @property
    def h(self):
        return len(self.rows)

    def uv(self, u, v):
        """donor (u, v) -> the (u, v) this model must write."""
        u = wrap01(u)
        v = wrap01(v)
        if self.src_w is not None:
            u = (u * self.src_w - self.x0) / float(self.x1 - self.x0)
        if self.atlas is not None:
            u0, v0, du, dv = self.atlas
            return (u0 + u * du, v0 + v * dv)
        return (u, v)

    @property
    def material(self):
        if self.atlas is not None:
            return "m7_" + self.atlas_name
        return "m7_" + self.name + self.flag

    @property
    def map_kd(self):
        return "%s/%s.png" % (TEX_ID, self.atlas_name or self.name)


def wrap01(t):
    """openBVE leans on texture repeat; several donor builders use negative u
    to mirror a panel. Wrapping reproduces the mirror and keeps every UV this
    file writes inside [0,1], which the loader requires."""
    if 0.0 <= t <= 1.0:
        return t
    return t - math.floor(t)


# ------------------------------------------------------------------- palette
#
# Every colour the interior uses, once, by name. Flat MTR house tones: a base,
# at most one highlight and one shade per material, and NO baked lighting —
# INTERIOR parts render CUTOUT_BRIGHT (fullbright), so a painted gradient reads
# as dirt rather than as light. What little shading there is is MATERIAL
# shading: a cove is a shade darker than the crown because it is a different
# panel, not because less light reaches it.
#
# The hues are sampled from the donor art this pass replaces, so the car keeps
# its LIRR identity: warm light-grey lining, dark slate end panels and cab, a
# near-black speckled floor, navy 3+2 seating.

CLEAR = (0, 0, 0, 0)                     # alpha 0 — a real hole in a CUTOUT

WALL_HI = (232, 231, 227)                # cant-rail strip under the ceiling
WALL = (208, 207, 202)                   # the lining itself
WALL_LO = (184, 183, 178)                # below-window panel
WALL_SEAM = (160, 159, 154)              # the 1 px joint between wall panels
WALL_SKIRT = (108, 110, 112)             # skirting, mostly hidden by the floor
FRAME = (150, 154, 158)                  # window surrounds, grab rails, trim
FRAME_HI = (188, 192, 196)
GLASS = (74, 88, 104)                    # saloon glazing seen from inside
GLASS_HI = (104, 122, 140)

CEILING = (238, 238, 236)                # the coved ceiling crown
CEILING_LO = (216, 217, 216)             # the cove flanks
CEILING_SEAM = (196, 197, 197)
LAMP = (255, 251, 232)                   # fluorescent strip
LAMP_EDGE = (236, 228, 198)              # its diffuser lip
LAMP_RECESS = (196, 196, 192)            # the ceiling slot the housing hangs in

FLOOR = (62, 63, 66)                     # saloon floor base
FLOOR_HI = (78, 80, 84)                  # 2-tone speckle, light fleck
FLOOR_LO = (50, 51, 54)                  # ...and dark fleck
VESTIBULE = (60, 66, 74)                 # the studded vestibule floor
VESTIBULE_HI = (86, 93, 102)

SLATE = (54, 70, 80)                     # end panels, bulkheads, cab doors
SLATE_HI = (72, 90, 102)
SLATE_LO = (40, 52, 60)

NAVY = (28, 52, 100)                     # LIRR seat moquette
NAVY_HI = (44, 74, 132)                  # its single highlight tone
NAVY_LO = (18, 34, 68)
SEAT_SHELL = (158, 162, 168)             # the grey seat-back shell
SEAT_SHELL_LO = (126, 130, 137)
SEAT_SHELL_EDGE = (104, 108, 115)

METAL = (176, 180, 184)                  # stanchions, brackets, rack bars
METAL_LO = (132, 137, 142)

SIGN_RED = (176, 30, 34)                 # EMERGENCY EXIT
SIGN_WHITE = (244, 244, 244)

# The next-stop unit over each vestibule aisle, sampled from the donor's own
# `destino.png`: a light grey housing (its field is 160,158,154) carrying a
# near-black LED window. The amber comes from MTR's display text, not from
# here — this is the unlit screen, so it stays black.
PIS_CASE = (160, 158, 154)               # the housing's own grey
PIS_CASE_HI = (184, 182, 178)
PIS_CASE_LO = (126, 124, 121)
PIS_BEZEL = (58, 58, 58)                 # the screen's surround
PIS_SCREEN = (12, 11, 13)                # the unlit LED window
PIS_BOLT = (198, 196, 192)               # the three fixings along the top

CAB_WALL = (46, 52, 58)                  # the cab's charcoal front bulkhead
CAB_WALL_HI = (62, 70, 78)
CAB_DESK = (26, 27, 30)                  # the console desk
CAB_SCREEN = (34, 58, 62)                # a display block on it
CAB_SCREEN_HI = (86, 168, 156)
CAB_GAUGE = (218, 170, 62)               # amber gauge/indicator blocks

AD_FRAME = (58, 60, 64)                  # the ad card's own frame
AD_CREAM = (238, 235, 226)
AD_TEAL = (32, 118, 128)
AD_ORANGE = (222, 122, 48)
AD_INK = (150, 150, 152)
AD_NAVY = (24, 40, 78)


# --------------------------------------------------------------- pixel drawing
#
# The repo's house method: every texture in this mod is drawn by Python, not
# sampled (see tools/gen_zebra_assets.py, tools/gen_pipe_assets.py). Rows are
# lists of (r, g, b, a), the same shape pngtool reads and writes.
#
# TARGET DENSITY. `PX_PER_UNIT` is px per M unit, so 16 x that is px per block.
# 2.5 puts every surface at 40 px/block, which is the density MTR's own NYCT
# stock is drawn at; sizes below are DERIVED from the geometry they cover
# rather than typed, so a resize in m7_layout.py re-proportions the art.

PX_PER_UNIT = 2.5


def px(units, quantum=2):
    """M units -> a texture dimension, rounded to a multiple of `quantum`."""
    n = int(round(units * PX_PER_UNIT / quantum)) * quantum
    return max(quantum, n)


def profile_units(profile, x_index=0, y_index=1):
    """Arc length of a (x, y, ...) donor cross-section profile, in M units."""
    total = 0.0
    for i in range(len(profile) - 1):
        a, b = profile[i], profile[i + 1]
        total += math.hypot(sx(b[x_index] - a[x_index]),
                            sy(b[y_index]) - sy(a[y_index]))
    return total


def fold_units(points, units_per_m):
    """Length of a folded (donor y, donor z) section, in M units.

    Seats, the driver's seat and the console desk are all one strip folded
    through several planes, and their donor UVs run along that fold — so the
    height their art needs is the fold's length, not its bounding box.
    """
    return sum(math.hypot(sy(b[0]) - sy(a[0]), (b[1] - a[1]) * units_per_m)
               for a, b in zip(points, points[1:]))


def canvas(w, h, colour=CLEAR):
    return [[rgba(colour)] * w for _ in range(h)]


def rgba(colour):
    return tuple(colour) + (255,) * (4 - len(colour))


def box(rows, x0, y0, x1, y1, colour):
    """Fill [x0, x1) x [y0, y1), clipped. Fractions in 0..1 are NOT accepted —
    every caller works in pixels so that the art is exact at its own size."""
    c = rgba(colour)
    for y in range(max(0, int(y0)), min(len(rows), int(y1))):
        row = rows[y]
        for x in range(max(0, int(x0)), min(len(row), int(x1))):
            row[x] = c


def band(rows, y0, y1, colour):
    box(rows, 0, y0, len(rows[0]), y1, colour)


def outline(rows, x0, y0, x1, y1, colour, weight=1):
    box(rows, x0, y0, x1, y0 + weight, colour)
    box(rows, x0, y1 - weight, x1, y1, colour)
    box(rows, x0, y0, x0 + weight, y1, colour)
    box(rows, x1 - weight, y0, x1, y1, colour)


def noise(x, y, seed):
    """A deterministic 0..1 hash. Deterministic because a converter that draws
    a different floor every run is a converter whose output cannot be diffed."""
    h = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def speckle(rows, colour_hi, colour_lo, density=0.14, seed=1, cell=2):
    """A sparse two-tone dither. `cell` keeps the flecks square-ish so a mild
    UV stretch between bays reads as texture, not as streaks."""
    for y in range(0, len(rows), cell):
        for x in range(0, len(rows[0]), cell):
            n = noise(x // cell, y // cell, seed)
            if n < density:
                box(rows, x, y, x + cell, y + cell, colour_hi)
            elif n > 1.0 - density:
                box(rows, x, y, x + cell, y + cell, colour_lo)


# A 3x5 pixel alphabet — the only text in the interior is the EMERGENCY EXIT
# header, which lands 1.4 blocks wide and is read from arm's length. Anything
# smaller than this got drawn as a shape instead.
FONT_3X5 = {
    " ": "000000000000000", "A": "010101111101101", "B": "110101110101110",
    "C": "011100100100011", "D": "110101101101110", "E": "111100110100111",
    "F": "111100110100100", "G": "011100101101011", "H": "101101111101101",
    "I": "111010010010111", "J": "001001001101010", "K": "101101110101101",
    "L": "100100100100111", "M": "101111111101101", "N": "101111111111101",
    "O": "010101101101010", "P": "110101110100100", "Q": "010101101111011",
    "R": "110101110101101", "S": "011100010001110", "T": "111010010010010",
    "U": "101101101101011", "V": "101101101010010", "W": "101101111111101",
    "X": "101101010101101", "Y": "101101010010010", "Z": "111001010100111",
}


def text_width(s):
    return max(0, len(s) * 4 - 1)


def text(rows, x, y, s, colour):
    for i, ch in enumerate(s.upper()):
        glyph = FONT_3X5.get(ch)
        if glyph is None:
            continue
        for gy in range(5):
            for gx in range(3):
                if glyph[gy * 3 + gx] == "1":
                    box(rows, x + i * 4 + gx, y + gy,
                        x + i * 4 + gx + 1, y + gy + 1, colour)


def solid(colour, size=8):
    """A flat swatch. Always 4 channels: pngtool writes rows verbatim, so a
    3-tuple here produces a short scanline and an unreadable PNG.

    Swatches must stay UNIFORM. The donor's `SetColor` builders carry no UVs at
    all, so every one of their vertices samples texel (0, 0) — art on a swatch
    would be invisible. Where a cab surface deserves art it gets a planar
    unwrap instead (see `plane_uv`).
    """
    return canvas(size, size, colour)


def pack_atlas(members, name="int_atlas", width=512):
    """Shelf-pack `members` into one image, filling each cell's UV remap.

    Cells are padded with replicated edge pixels so mipmapping cannot pull a
    neighbour's colour across a seam — the classic atlas artefact, and the only
    reason atlasing is riskier than one file per texture.

    Since the art is drawn rather than sampled, EVERYTHING that can be atlased
    is: the wall elevations and the floors used to be standalone files because
    the donor's were too big to pack, and at the drawn density they are not.
    That leaves the interior with three textures in total (saloon atlas, cab
    atlas, glass) where it had twelve, i.e. three draw batches per group set.

    There are still TWO atlases, not one. The cab's art is used by exactly two
    groups (`int_cab`, `int_cab_mini`) and the saloon's by all the others, so
    keeping them apart means a trailer car never binds the cab sheet.
    """
    order = sorted(members, key=lambda t: -t.h)
    shelves = []                       # [y, height, cursor_x]
    placed = []
    for t in order:
        need_w, need_h = t.w + 2 * ATLAS_PAD, t.h + 2 * ATLAS_PAD
        if need_w > width:
            width = 1 << (need_w - 1).bit_length()
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
            sy_ = min(t.h - 1, max(0, dy))
            src = t.rows[sy_]
            for dx in range(-ATLAS_PAD, t.w + ATLAS_PAD):
                sx_ = min(t.w - 1, max(0, dx))
                rows[y0 + dy][x0 + dx] = src[sx_]
        t.atlas = (x0 / float(width), y0 / float(height),
                   t.w / float(width), t.h / float(height))
        t.atlas_name = name
    sheet = Tex(name, rows)
    for t in order:
        t.atlas_tex = sheet
    return sheet


# ------------------------------------------------------------------- the art
#
# One function per surface. Each is handed the pixel size its own geometry
# implies (see `build_textures`) and paints in that space, so nothing is
# authored at a resolution the model cannot show.

WALL_W = 1024                    # the whole car side, ~40 px/block

# ⭐ THE SALOON WINDOWS ARE REAL HOLES IN THE LINING (2026-07-28).
#
# They used to be PAINTED: an opaque slate-blue rectangle where each window is.
# That decision came from the era when the windows were opaque on the outside
# too, and it survived the glazing rework — so with the body's panes now
# translucent, what a player on the platform saw through the glass was the
# lining ACROSS the car with a blue rectangle painted on it. It read as a
# frosted panel, not as a window (user-reported, with a screenshot).
#
# The lining now carries a real alpha-0 aperture at every window. INTERIOR
# parts render CUTOUT_BRIGHT, so alpha 0 is a hole exactly as it is on the
# EXTERIOR skin, and the line of sight is: translucent pane -> hole in the near
# lining -> the saloon -> hole in the far lining -> its pane -> daylight. Which
# is what a real train does.
#
# NO INNER PANE, deliberately. The body already puts a translucent pane over
# every aperture (m7_glass, alpha 76 = 30% coverage). A second pane 0.10 m
# behind it would composite to 1 - (1-0.30)^2 = 51% coverage — the saloon would
# read half-milky instead of lightly tinted, which is the exact "doubled
# milkiness" the pane is there to avoid. It would also put two nearly
# coincident TRANSLUCENT_BRIGHT quads in the same line of sight, which MTR has
# no depth-sort guarantee for. One pane, on the body, is right; a passenger
# looking out still sees it, because it is still between them and the outside.

# The lining's aperture is INSET inside the exterior one, by more than the
# 0.10 m cavity between skin and lining is deep. Two things come out of that:
# the lining reads as the window's interior frame (which is what the prototype
# has), and there is no angle from which a ray through the exterior aperture
# can slip past the lining's edge into the wall cavity — so the cavity, and
# anything parked in it, stays out of sight.
LINING_APERTURE_INSET_Y = 0.055      # donor metres, top and bottom
LINING_APERTURE_INSET_Z = 0.070      # donor metres, each end


def wall_v_of_y(y_m):
    """donor y -> the wall elevation's own v, off WALL_PROFILE's v column."""
    p = WALL_PROFILE
    for i in range(len(p) - 1):
        y0, y1 = p[i][1], p[i + 1][1]
        if min(y0, y1) - 1e-9 <= y_m <= max(y0, y1) + 1e-9:
            t = 0.0 if y0 == y1 else (y_m - y0) / (y1 - y0)
            return p[i][2] + (p[i + 1][2] - p[i][2]) * t
    return p[0][2] if y_m > p[0][1] else p[-1][2]


def saloon_apertures():
    """Every lining aperture, as (donor z_a, z_b, donor y_top, y_bot).

    DERIVED FROM THE EXTERIOR, not measured again. The body converter owns
    where a window is — it draws the panes off the donor's alpha-156 marks and
    cuts the skin at exactly those boxes — so the lining takes the same list
    and insets it. That is the only way the two can be guaranteed to register;
    the previous typed WINDOW_U/WINDOW_V table was a second measurement of the
    same thing and had already drifted a little in y.

    Importing the body converter is the dependency direction that makes sense
    (the lining lines the body, not the other way round) and it is cheap — that
    module does no work at import time.
    """
    out = []
    for (u0, u1, v0, v1) in B.side_panes():
        za, zb = sorted((L.z_of_u(u0), L.z_of_u(u1)))
        y_top, y_bot = B.side_y(v0), B.side_y(v1)
        out.append((za + LINING_APERTURE_INSET_Z,
                    zb - LINING_APERTURE_INSET_Z,
                    y_top - LINING_APERTURE_INSET_Y,
                    y_bot + LINING_APERTURE_INSET_Y))
    return out


def apertures_in(bay, margin_m=0.10):
    """Indices of the apertures that fit WHOLLY inside `bay`, frame included.

    Same rule, and the same reason, as the body's `panes_in`: the elevation is
    drawn once and cropped per bay, so an aperture that straddles a bay
    boundary would be sliced in half — and half an alpha-0 hole in the lining
    is a gash, not a window. A window exists wholly inside its bay or not at
    all. `margin_m` covers the drawn surround, which sits outside the hole.
    """
    out = set()
    for i, (za, zb, _yt, _yb) in enumerate(saloon_apertures()):
        if bay.contains(za + margin_m) and bay.contains(zb - margin_m):
            out.add(i)
    return out


def art_wall_elevation(seams, keep=None):
    """The saloon lining, drawn once for the whole car side and then sliced.

    Drawn whole rather than per bay because the window pitch, the bay seams and
    the end bays all have to agree, and they only can if they are laid out in
    one coordinate space. `seams` are elevation columns to mark with a panel
    joint — the caller passes each slice's left edge, so every repeated bay
    shows exactly one joint at its start and none at its end (a joint at both
    would double up where two bays meet).

    `keep` is a set of indices into `saloon_apertures()`; None draws them all.
    Each bay passes what `apertures_in` gave it — see that function.
    """
    h = px(profile_units(WALL_PROFILE))
    rows = canvas(WALL_W, h, WALL)
    v = lambda t: int(round(t * h))

    band(rows, 0, v(0.055), WALL_HI)                 # cant rail under the cove
    band(rows, v(0.055), v(0.065), WALL_SEAM)
    band(rows, v(0.700), v(0.712), WALL_SEAM)        # duct capping
    band(rows, v(0.712), v(0.790), WALL_LO)          # the heater duct itself
    band(rows, v(0.860), h, WALL_SKIRT)              # skirting, under the floor

    for x in seams:
        box(rows, x, 0, x + 1, v(0.860), WALL_SEAM)

    for i, (za, zb, y_top, y_bot) in enumerate(saloon_apertures()):
        if keep is not None and i not in keep:
            continue
        x0, x1 = sorted((int(round(L.u_of_z(za) * WALL_W)),
                         int(round(L.u_of_z(zb) * WALL_W))))
        top = v(wall_v_of_y(y_top))
        bot = v(wall_v_of_y(y_bot))
        # The surround is drawn OUTSIDE the hole, so the hole itself is exactly
        # the aperture `saloon_apertures` derived and the frame reads as the
        # window's interior trim standing in front of the body's glass.
        box(rows, x0 - 3, top - 3, x1 + 3, bot + 3, FRAME)
        box(rows, x0 - 2, top - 2, x1 + 2, top, FRAME_HI)       # lit head
        box(rows, x0 - 3, bot + 1, x1 + 3, bot + 3, FRAME_HI)   # the sill
        box(rows, x0, top, x1, bot, CLEAR)                      # the real hole
    return rows


def ceiling_u_of_x(x_m):
    """donor x -> the ceiling elevation's own u, off CEILING_PROFILE."""
    p = CEILING_PROFILE
    for i in range(len(p) - 1):
        x0, x1 = p[i][0], p[i + 1][0]
        if min(x0, x1) - 1e-9 <= x_m <= max(x0, x1) + 1e-9:
            t = 0.0 if x0 == x1 else (x_m - x0) / (x1 - x0)
            return p[i][2] + (p[i + 1][2] - p[i][2]) * t
    return p[0][2] if x_m > p[0][0] else p[-1][2]


def art_ceiling():
    """The coved ceiling across the car: u spans CEILING_PROFILE, v runs along
    the bay. Constant in v on purpose — a lengthwise feature would repeat at
    every bay seam, and the twin fluorescents are lengthwise anyway.

    The two lamp bands are drawn as a DARK SLOT rather than a lit strip: since
    the depth pass the fluorescents are real housings hanging below the crown
    (`lamp_run`), so this band is the recess they hang out of and nothing but a
    sliver of it is ever seen. Painting it bright would put a glowing halo round
    every housing. Its columns are derived from LAMP_BOX_X so the two cannot
    drift apart.
    """
    w, h = px(profile_units(CEILING_PROFILE)), 16
    rows = canvas(w, h, CEILING)
    u = lambda t: int(round(t * w))
    box(rows, 0, 0, u(0.15), h, CEILING_LO)          # the two cove flanks
    box(rows, u(0.85), 0, w, h, CEILING_LO)
    box(rows, u(0.15), 0, u(0.15) + 1, h, CEILING_SEAM)
    box(rows, u(0.85) - 1, 0, u(0.85), h, CEILING_SEAM)
    for side in (1.0, -1.0):                         # the two housing recesses
        a, b = sorted(ceiling_u_of_x(side * x) for x in LAMP_BOX_X)
        box(rows, u(a) - 1, 0, u(b) + 1, h, CEILING_SEAM)
        box(rows, u(a), 0, u(b), h, LAMP_RECESS)
    box(rows, u(0.5), 0, u(0.5) + 1, h, CEILING_SEAM)      # centre panel joint
    return rows


def art_lamp():
    """One fluorescent housing, unwrapped across its own section.

    v runs OUTBOARD flank -> the lit underside -> INBOARD flank, which is the
    order `lamp_run` walks the section in. The flanks are a shade down from the
    diffuser because INTERIOR parts render fullbright: with no lighting to do
    it, the only thing that tells a flank from the face it turns off is the
    material change. Same reason the trim rail and the grab rail carry a band
    per face rather than one flat colour.
    """
    rows = canvas(8, 16, LAMP)
    band(rows, 0, 3, LAMP_EDGE)
    band(rows, 3, 4, LAMP_RECESS)
    band(rows, 12, 13, LAMP_RECESS)
    band(rows, 13, 16, LAMP_EDGE)
    return rows


def art_trim():
    """The window-head trim rail: v 0 = its top soffit, 1 = its underside.

    Three bands for three faces. The underside is the darkest because it is the
    one a standing rider never sees lit in a real car, and it is the band that
    makes the rail read as a rail rather than as a painted stripe.
    """
    rows = canvas(8, 16, FRAME_HI)
    band(rows, 0, 3, FRAME)                          # the top, against the wall
    band(rows, 3, 4, WALL_HI)                        # its bright leading arris
    band(rows, 12, 13, WALL_HI)
    band(rows, 13, 16, WALL_SEAM)                    # the shaded underside
    return rows


def art_fascia():
    """The luggage rack's downstand: v 0 is its outboard flank, 1 the inboard.

    The inboard face is the one the whole car looks at, so it takes the bright
    brushed tone and the two turns take the shade — again a material change,
    because a fullbright INTERIOR part gets no help from the light.
    """
    rows = canvas(8, 16, METAL)
    band(rows, 0, 5, METAL_LO)                       # outboard, over the seats
    band(rows, 5, 6, FRAME_HI)
    band(rows, 6, 11, METAL_LO)                      # the underside
    band(rows, 11, 12, FRAME_HI)
    return rows


def art_seat_edge():
    """The cut edge of a seat back: navy on the cushion side, shell grey behind.

    v 0 is the front (moquette) face, v 1 the back (shell) face, so the same
    8x16 strip serves the headrest caps and the aisle-side returns and each one
    changes colour where the real seat does.
    """
    rows = canvas(8, 16, NAVY_LO)
    band(rows, 0, 2, NAVY)
    band(rows, 7, 8, SEAT_SHELL_EDGE)
    band(rows, 8, 16, SEAT_SHELL_LO)
    return rows


def art_floor(w, h):
    rows = canvas(w, h, FLOOR)
    speckle(rows, FLOOR_HI, FLOOR_LO, density=0.07, seed=7)
    return rows


def art_floor_vestibule(w, h):
    """The vestibule's studded rubber: a stud grid, not a speckle."""
    rows = canvas(w, h, VESTIBULE)
    for y in range(2, h - 1, 6):
        for x in range(2, w - 1, 6):
            box(rows, x, y, x + 2, y + 2, VESTIBULE_HI)
    return rows


def y_to_v(y_m):
    """donor y -> the partition's own v. The inverse of `v_to_y`."""
    table = [(p[3], p[1]) for p in PARTITION_PROFILE]
    for i in range(len(table) - 1):
        v0, y0 = table[i]
        v1, y1 = table[i + 1]
        if min(y0, y1) - 1e-9 <= y_m <= max(y0, y1) + 1e-9:
            t = 0.0 if y0 == y1 else (y_m - y0) / (y1 - y0)
            return v0 + (v1 - v0) * t
    return table[-1][0]


# The vestibule screen's openings, in donor metres. The glazed band is what
# `glass_panes` MEASURES back out of the drawn texture, so these two numbers
# decide where the glass quads land; the aisle is a plain hole from the head
# rail down to the kick plate, and `glass_panes` tells the two apart by exactly
# that — a light is closed off by the waist rail at PARTITION_BAND_Y[0], the
# aisle is not. Keep the waist rail spanning the full width for that reason.
PARTITION_BAND_Y = (2.250, 3.150)
PARTITION_AISLE_U = (0.340, 0.660)
PARTITION_PIER_U = (0.315, 0.685)
PARTITION_GLAZE_U = (0.040, 0.960)


def art_partition(vestibule_side):
    w = px(sx(2.0 * PARTITION_PROFILE[3][0]))
    h = px(sy(PARTITION_PROFILE[-1][1]) - sy(PARTITION_PROFILE[0][1]))
    face = WALL if not vestibule_side else WALL_LO
    rows = canvas(w, h, face)
    u = lambda t: int(round(t * w))
    v = lambda t: int(round(y_to_v(t) * h))

    top, bot = v(PARTITION_BAND_Y[1]), v(PARTITION_BAND_Y[0])
    band(rows, 0, top, WALL_HI if not vestibule_side else WALL)
    band(rows, top - 2, top, FRAME)                          # the head rail
    box(rows, u(PARTITION_GLAZE_U[0]), top,
        u(PARTITION_GLAZE_U[1]), bot, CLEAR)                 # the glazed band
    for c in PARTITION_PIER_U:                               # aisle jambs
        box(rows, u(c) - 2, top, u(c) + 2, h, FRAME)
    band(rows, bot, bot + 2, FRAME)                          # the waist rail
    box(rows, u(PARTITION_AISLE_U[0]), top,
        u(PARTITION_AISLE_U[1]), h, CLEAR)                   # the aisle
    box(rows, 0, h - 3, w, h, WALL_SKIRT)                    # base kick rail

    # ⭐ NOTHING IS DRAWN OVER THE AISLE ANY MORE. There used to be a plain
    # signage plate here — SLATE one side, red the other — chosen because "at
    # 0.3 blocks tall nothing readable fits". What goes there now is the real
    # next-stop unit, and it is a DECAL QUAD with its own texture (`art_pis`,
    # placed by `pis_panel`), because at this sheet's ~37 px/m the whole
    # housing was 32 x 8 px and its bezel rounded away to nothing. The donor
    # made the same call: `destino.png` is a separate image at ~316 px/m.
    return rows


# The next-stop unit's own texture. Deliberately oversampled — the same trick
# the side lettering uses, and the same one MTR's r179 pulls (368 declared,
# 1472 shipped). At 0.868 x 0.194 m this is ~147 px/m against the partition
# sheet's 37, which is the whole reason it is a separate image.
PIS_TEX_W, PIS_TEX_H = 128, 29


def art_pis():
    """The next-stop display's housing, from the donor's own `destino.png`.

    That image (221x79, BInterior builder 75) is a light grey housing carrying
    a near-black LED window inset in its upper portion — measured at u
    0.041..0.932, v 0.038..0.456 — with a row of three bolt heads along the top
    edge. This reproduces the parts that read at our scale: the housing, its
    lit top edge and shadowed bottom, the bezel, the window, and the bolts.

    The screen stays BLACK. The amber is MTR's display text, drawn over it by
    the DISPLAY part in m7_doors.bbmodel; painting amber here would show
    through as a lit screen on a train that is sitting in a depot.
    """
    rows = canvas(PIS_TEX_W, PIS_TEX_H, PIS_CASE)
    box(rows, 0, 0, PIS_TEX_W, 2, PIS_CASE_HI)              # lit top edge
    box(rows, 0, PIS_TEX_H - 2, PIS_TEX_W, PIS_TEX_H, PIS_CASE_LO)
    box(rows, 0, 0, 2, PIS_TEX_H, PIS_CASE_HI)              # and the returns
    box(rows, PIS_TEX_W - 2, 0, PIS_TEX_W, PIS_TEX_H, PIS_CASE_LO)

    # The window, at the fraction of the housing the donor's own is.
    sx0 = int(round(PIS_TEX_W * 0.045))
    sx1 = int(round(PIS_TEX_W * 0.955))
    sy0 = int(round(PIS_TEX_H * 0.24))
    sy1 = int(round(PIS_TEX_H * 0.86))
    box(rows, sx0 - 1, sy0 - 1, sx1 + 1, sy1 + 1, PIS_BEZEL)
    box(rows, sx0, sy0, sx1, sy1, PIS_SCREEN)
    for t in (0.18, 0.5, 0.82):                             # three fixings
        cx = int(round(PIS_TEX_W * t))
        box(rows, cx - 2, 3, cx + 2, 6, PIS_BOLT)
    return rows


# Where the donor's own seat UVs fold the strip: backrest, lower backrest,
# cushion top, cushion nose (asiento*.csv builder 1 — measured, not chosen).
SEAT_V = (0.34, 0.72, 0.95)


def art_seat_front(seats, w, h, span_m):
    rows = canvas(w, h, NAVY)
    v = lambda t: int(round(t * h))
    band(rows, v(0.06), v(0.30), NAVY_HI)                # upper back panel
    band(rows, v(SEAT_V[0]) - 1, v(SEAT_V[0]) + 1, NAVY_LO)
    band(rows, v(SEAT_V[1]), v(SEAT_V[2]), NAVY_HI)      # the cushion top
    band(rows, v(SEAT_V[2]), h, NAVY_LO)                 # its front nose
    for i in range(1, seats):                            # dividers + grab bars
        x = int(round(i * w / float(seats)))
        box(rows, x - 1, 0, x + 1, v(SEAT_V[1]), NAVY_LO)
        box(rows, x - 1, v(SEAT_V[1]), x, v(SEAT_V[2]), NAVY_LO)  # over the pan
        box(rows, x - 1, v(0.02), x + 1, v(0.14), METAL)
    box(rows, 0, 0, 1, v(SEAT_V[1]), NAVY_LO)
    box(rows, w - 1, 0, w, v(SEAT_V[1]), NAVY_LO)
    scallop(rows, seats, w, span_m)
    return rows


def art_seat_back(seats, w, h, span_m):
    rows = canvas(w, h, SEAT_SHELL)
    v = lambda t: int(round(t * h))
    band(rows, v(0.04), v(0.07), SEAT_SHELL_LO)          # the shoulder line
    band(rows, v(0.56), v(0.72), SEAT_SHELL_LO)          # the shell's base
    for i in range(seats + 1):                           # one moulding per seat
        x = int(round(i * w / float(seats)))
        box(rows, max(0, x - 1), 0, min(w, x + 1), v(0.72), SEAT_SHELL_EDGE)
    scallop(rows, seats, w, span_m)
    return rows


def scallop(rows, seats, w, span_m):
    """Cut the top of the bench between seats, so it reads as separate headrests.

    The same cut on the front and the back art, so the two silhouettes agree —
    both faces start at donor y 2.400, i.e. at texture row 0 — AND the same cut
    the top caps are split at (`seat_back_edges`), which is why its width is
    SEAT_NOTCH_M in donor metres rather than a pixel count: the geometry and
    the art have to be the same notch or the cap bridges it.

    Only the INTERIOR boundaries are cut. The old version also nicked both ends
    of the bench, which took a bite out of the outer headrests for no reason
    once there were caps to line up with.
    """
    h = len(rows)
    notch = max(2, int(round(SEAT_NOTCH_M / span_m * w)))
    deep = max(2, int(round(SEAT_NOTCH_V * h)))
    for i in range(1, seats):
        x = int(round(i * w / float(seats)))
        box(rows, x - notch // 2, 0, x - notch // 2 + notch, deep, CLEAR)


def art_seat_side(w, h):
    """The bench end panel. u runs back->front along the seat, v top->bottom."""
    rows = canvas(w, h, SEAT_SHELL)
    box(rows, 0, h - 2, w, h, SEAT_SHELL_LO)
    box(rows, 0, 0, w, 1, SEAT_SHELL_LO)
    for y in range(h):                                   # chamfer the front top
        cut = int(round((h * 0.40) * (1.0 - y / float(max(1, h - 1)))))
        box(rows, w - cut, y, w, y + 1, CLEAR)
    return rows


def art_rack_mesh(w, h):
    """The barred panel over the seats: u along the car, v across the strip."""
    rows = canvas(w, h, CLEAR)
    band(rows, 0, 2, METAL_LO)                           # outboard rail
    band(rows, h - 2, h, METAL_LO)                       # inboard rail
    for x in range(1, w - 1, 6):
        box(rows, x, 2, x + 2, h - 2, METAL)
    return rows


def art_bracket(w, h, deep):
    """A grab-rail bracket: u 0 inboard -> 1 outboard, v 0 top -> 1 bottom."""
    rows = canvas(w, h, CLEAR)
    v = lambda t: int(round(t * h))
    box(rows, 0, v(0.30), w, v(0.62), METAL)             # the arm
    box(rows, w - 4, 0, w, h, METAL_LO)                  # its wall plate
    box(rows, 0, v(0.05), 3, v(0.95 if deep else 0.80), METAL)   # rail clamp
    return rows


EXIT_LABEL = "EMERGENCY EXIT"


def art_exit_header():
    """The EMERGENCY EXIT band over a doorway.

    v 0..0.80 is the sloped cove face, 0.80..1 the little soffit ledge. This is
    the one place in the interior where pixel text survives: the band lands
    1.4 blocks wide, so a 3x5 alphabet is legible from inside the car. It is
    also the one texture drawn ABOVE the house density — the size follows the
    label, because a header whose legend does not fit is just a red smear.
    """
    w = max(text_width(EXIT_LABEL) + 8, px(1.425 * L.DOOR_BAY.units_per_m))
    h = max(16, px(profile_units(DOOR_COVE_PROFILE)))
    rows = canvas(w, h, WALL_HI)
    v = lambda t: int(round(t * h))
    band(rows, v(0.80), h, WALL_LO)
    box(rows, 2, v(0.08), w - 2, v(0.74), SIGN_RED)
    text(rows, (w - text_width(EXIT_LABEL)) // 2, v(0.08) + 2, EXIT_LABEL,
         SIGN_WHITE)
    return rows


def art_poster(w, h):
    """A generic advert. The donor's card was the pack's own credit panel;
    that is REMOVED (user decision, 2026-07-28) and nothing here depicts a real
    brand, product or person."""
    rows = canvas(w, h, AD_CREAM)
    outline(rows, 0, 0, w, h, AD_FRAME)
    box(rows, 2, 2, w - 2, int(h * 0.44), AD_TEAL)
    cx, cy, r = w // 2, int(h * 0.23), max(2, w // 5)
    for y in range(cy - r, cy + r + 1):                  # a flat sun/disc motif
        d = int(round(math.sqrt(max(0.0, r * r - (y - cy) ** 2))))
        box(rows, cx - d, y, cx + d, y + 1, AD_CREAM)
    box(rows, 2, int(h * 0.44), w - 2, int(h * 0.48), AD_ORANGE)
    for i in range(3):                                   # abstract copy lines
        y = int(h * 0.56) + i * 4
        box(rows, 4, y, w - 4 - i * 3, y + 2, AD_INK)
    box(rows, 2, h - 7, w - 2, h - 3, AD_NAVY)
    return rows


def art_poster_route(w, h):
    """The other card: an LIRR-style vertical route strip."""
    rows = canvas(w, h, AD_CREAM)
    outline(rows, 0, 0, w, h, AD_FRAME)
    box(rows, 2, 2, w - 2, 7, AD_NAVY)
    box(rows, 4, 4, w - 6, 5, AD_CREAM)
    x = w // 2 - 1
    box(rows, x, 10, x + 2, h - 4, AD_NAVY)              # the line
    for i in range(5):                                   # the stops
        y = 12 + i * ((h - 20) // 4)
        box(rows, x - 2, y, x + 4, y + 3, AD_NAVY)
        box(rows, x - 1, y + 1, x + 3, y + 2, AD_CREAM)
        box(rows, x + 6, y, w - 3, y + 2, AD_INK)        # its label block
    return rows


def art_end_panel(h, jamb=False):
    """A bulkhead / car-end panel. Deliberately CONSTANT along u.

    The donor drew these with mirrored UVs (u -1..0), and `wrap01` folds -1 to
    0, so the u range a panel actually samples differs per builder and is
    sometimes a single column. Banding it horizontally makes every one of those
    mappings produce the same, correct panel.
    """
    rows = canvas(8, h, SLATE)
    v = lambda t: int(round(t * h))
    band(rows, 0, v(0.05), SLATE_LO)                     # against the cove
    band(rows, v(0.05), v(0.08), SLATE_HI)
    band(rows, v(0.60 if jamb else 0.63), v(0.66), SLATE_HI)     # waist rail
    band(rows, v(0.92), h, SLATE_LO)                     # skirt
    return rows


# ------------------------------------------------------------------- cab art
#
# The donor's own u/v maps for the cab, read off minicabina.csv. Both are
# linear, and both are needed to put an aperture where the geometry expects it.
#   M7frenteint: u = 0.507 - 0.3167 * x   (car centre at u 0.507)
#                v = 0.18 + (3.40 - y) / 2.35 * 0.82
CAB_FRONT_U0, CAB_FRONT_DU = 0.507, -0.3167
CAB_FRONT_V0, CAB_FRONT_DV = 0.18, 0.82 / 2.35
CAB_WINDSCREEN = (0.20, 1.42, 2.55, 3.30)      # x0, x1, y0, y1 (donor metres)
CAB_DOOR_WINDOW = (0.24, 0.74, 0.14, 0.39)     # u0, u1, v0, v1 — see docstring

# What the WHOLE front texture spans, by inverting those two maps: the donor
# only ever samples u 0.01..0.99 and v 0.18..1.00 of it, so drawing it at the
# geometry's own size would put the art at the wrong density.
CAB_FRONT_W_M = 1.0 / abs(CAB_FRONT_DU)
CAB_FRONT_TOP_Y = 3.40 + CAB_FRONT_V0 / CAB_FRONT_DV
CAB_FRONT_BOTTOM_Y = 3.40 - (1.0 - CAB_FRONT_V0) / CAB_FRONT_DV


def art_cab_front():
    w = px(sx(CAB_FRONT_W_M))
    h = px(sy(CAB_FRONT_TOP_Y) - sy(CAB_FRONT_BOTTOM_Y))
    rows = canvas(w, h, CAB_WALL)
    ux = lambda x: int(round((CAB_FRONT_U0 + CAB_FRONT_DU * x) * w))
    vy = lambda y: int(round((CAB_FRONT_V0 + CAB_FRONT_DV * (3.40 - y)) * h))
    x0, x1, y0, y1 = CAB_WINDSCREEN
    for sign in (1.0, -1.0):
        a, b = sorted((ux(sign * x0), ux(sign * x1)))
        t, u = vy(y1), vy(y0)
        box(rows, a, t, b, u, CAB_WALL_HI)               # the screen surround
        box(rows, a + 2, t + 2, b - 2, u - 2, CLEAR)
    box(rows, 0, vy(2.10), w, vy(1.95), CAB_WALL_HI)     # the dash shelf
    box(rows, 0, vy(1.30), w, h, SLATE_LO)               # the toe recess
    return rows


def art_cab_door(w, h, saloon_side):
    """The cab door leaf. Its window is a real hole in both skins — it is the
    only thing the cab is ever seen through."""
    rows = canvas(w, h, SLATE)
    u = lambda t: int(round(t * w))
    v = lambda t: int(round(t * h))
    u0, u1, v0, v1 = CAB_DOOR_WINDOW
    box(rows, u(u0), v(v0), u(u1), v(v1), SLATE_HI)
    box(rows, u(u0) + 2, v(v0) + 2, u(u1) - 2, v(v1) - 2, CLEAR)
    box(rows, 0, 0, w, 2, SLATE_LO)
    box(rows, 0, v(0.90), w, h, SLATE_LO)                # kickplate
    box(rows, u(0.06), v(0.50), u(0.20), v(0.54), METAL)   # the handle
    if saloon_side:                                      # a crew-only plate
        box(rows, u(0.36), v(0.60), u(0.64), v(0.70), SIGN_WHITE)
        box(rows, u(0.40), v(0.63), u(0.60), v(0.65), SIGN_RED)
    return rows


def art_nose_door(h):
    """The emergency end door at the nose. Constant along u for the same reason
    as the end panels: its donor UVs fold to a single column."""
    rows = canvas(8, h, WALL)
    v = lambda t: int(round(t * h))
    band(rows, 0, 2, FRAME)
    band(rows, v(0.14), v(0.39), GLASS)                  # the window, painted
    band(rows, v(0.14) - 1, v(0.14) + 1, FRAME)
    band(rows, v(0.39) - 1, v(0.39) + 1, FRAME)
    band(rows, v(0.52), v(0.56), METAL)                  # the crash bar
    band(rows, v(0.90), h, WALL_LO)
    return rows


def art_driver_seat(w, h):
    rows = canvas(w, h, CLEAR)
    v = lambda t: int(round(t * h))
    box(rows, 2, v(0.03), w - 2, v(0.70), NAVY_LO)       # the back
    box(rows, 3, v(0.08), w - 3, v(0.34), NAVY)
    box(rows, 0, v(0.70), w, h, NAVY_LO)                 # the pan
    box(rows, 2, v(0.74), w - 2, v(0.94), NAVY)
    return rows


def art_cab_desk():
    """The console face: one screen block, two gauges, a switch bank.

    Reached by a planar unwrap (`plane_uv`) because the donor drew this builder
    with `SetColor` and no UVs at all. u is donor x (0 inboard, 1 outboard) and
    v is donor y from the top, and the driver-facing face occupies v 0..0.86 of
    it — so everything drawn here stays above that line. The height is the true
    length of that sloped face, grossed up by the share of v it takes.
    """
    face = fold_units([(2.400, 12.350), (2.120, 12.635)],
                      L.CAB_BAY.units_per_m)
    w = px(sx(1.350 - 0.580))
    h = px(face * (2.400 - 2.075) / (2.400 - 2.120))
    rows = canvas(w, h, CAB_DESK)
    u = lambda t: int(round(t * w))
    v = lambda t: int(round(t * h))
    box(rows, u(0.34), v(0.16), u(0.72), v(0.60), CAB_SCREEN)
    box(rows, u(0.37), v(0.22), u(0.69), v(0.30), CAB_SCREEN_HI)
    box(rows, u(0.37), v(0.36), u(0.58), v(0.44), CAB_SCREEN_HI)
    box(rows, u(0.06), v(0.22), u(0.16), v(0.44), CAB_GAUGE)     # two gauges
    box(rows, u(0.20), v(0.22), u(0.30), v(0.44), CAB_GAUGE)
    box(rows, u(0.78), v(0.22), u(0.96), v(0.36), METAL_LO)      # switch bank
    box(rows, u(0.78), v(0.44), u(0.96), v(0.58), SLATE_HI)
    box(rows, 0, v(0.72), w, v(0.80), SLATE_LO)          # the desk's front lip
    return rows


def build_textures():
    """Draw and pack every interior texture. Returns {key: Tex}.

    Nothing here reads a donor image any more — this is the repo's house
    method, the same one every station-decor texture in the mod uses. The donor
    CSVs are still the authority on GEOMETRY and on where a window sits along
    the car; only the pixels are ours.
    """
    tex = {}

    # --- the wall elevation, cut with the SAME u ranges as the exterior body,
    # so an interior window lands behind its exterior one.
    cuts, bays = {}, {}
    for key, bay, ua, ub in (
            ("window", L.WINDOW_BAY,
             L.u_of_z(L.WINDOW_BAY.za), L.u_of_z(L.WINDOW_BAY.zb)),
            ("door", L.DOOR_BAY,
             L.u_of_z(L.DOOR_BAY.za), L.u_of_z(L.DOOR_BAY.zb)),
            ("cab", L.CAB_BAY, 0.0, L.u_of_z(L.CAB_BAY.zb)),
            ("rear", L.GANGWAY_BAY, L.u_of_z(L.GANGWAY_BAY.zb), 1.0)):
        a, b = sorted((ua, ub))
        cuts[key] = (max(0, int(math.floor(a * WALL_W))),
                     min(WALL_W, int(math.ceil(b * WALL_W))))
        bays[key] = bay
    # The Mini crops both end bays, and a crop can cut an aperture the full bay
    # cleared. Its slices are drawn separately but cropped to the SAME columns
    # as the full ones, so `Tex.uv()` is unchanged and only the pixels differ —
    # exactly the arrangement the body converter uses for `lat_*_mini`.
    for key, bay in (("cab_mini", L.CAB_BAY_MINI),
                     ("rear_mini", L.GANGWAY_BAY_MINI)):
        cuts[key] = cuts[key[:-5]]
        bays[key] = bay

    seams = [x0 for key, (x0, _x1) in cuts.items() if not key.endswith("_mini")]
    for key, (x0, x1) in cuts.items():
        full = key[:-5] if key.endswith("_mini") else None
        keep = apertures_in(bays[key])
        if full is not None and keep == apertures_in(bays[full]):
            tex["wall_" + key] = tex["wall_" + full]     # identical: share it
            continue
        wall = art_wall_elevation(seams, keep)
        tex["wall_" + key] = Tex("int_wall_" + key,
                                 pngtool.crop(wall, x0, 0, x1, len(wall)),
                                 WALL_W, x0, x1)

    # --- surfaces stretched over a whole bay: one texture per bay kind, drawn
    # at that bay's own aspect so the grain does not change at a seam.
    floor_w = px(sx(2.0 * FLOOR_HALF))
    tex["ceiling"] = Tex("int_ceiling", art_ceiling())
    tex["floor"] = Tex("int_floor", art_floor(floor_w, px(L.WINDOW_UNITS)))
    tex["floor_vestibule"] = Tex(
        "int_floor_vestibule", art_floor_vestibule(floor_w, px(L.DOOR_UNITS)))

    tex["part_saloon"] = Tex("int_partition_saloon", art_partition(False))
    tex["part_vestibule"] = Tex("int_partition_vestibule", art_partition(True))

    # --- glazing over the partition apertures. TRANSLUCENT_BRIGHT via the
    # material name, which beats the part's renderStage (only a CUTOUT source
    # shader is overridden), so it can share a group with everything else. It
    # stays a file of its own for exactly that reason: a flagged material needs
    # its own map_Kd.
    glass = Tex("int_glass", solid(GLASS_RGBA))
    glass.flag = "#interior_translucent"
    tex["glass"] = glass

    # The seat strip's own fold, off asiento*.csv builder 1 — the four bands
    # its UVs run through (backrest, lower backrest, cushion top, nose).
    seat_h = px(fold_units([(2.400, 0.000), (2.000, 0.000), (1.600, 0.100),
                            (1.600, 0.500), (1.520, 0.500)], L.Z_SCALE))
    panel_h = px(sy(3.400) - sy(1.295))
    poster_size = (px(POSTER_W_M * L.DOOR_BAY.units_per_m),
                   px(POSTER_H_M * L.Y_SCALE_ABOVE))
    saloon = {
        "seat_narrow": art_seat_front(2, px(sx(0.95)), seat_h, 0.95),
        "seat_narrow_back": art_seat_back(2, px(sx(0.95)), seat_h, 0.95),
        "seat_wide": art_seat_front(3, px(sx(1.30)), seat_h, 1.30),
        "seat_wide_back": art_seat_back(3, px(sx(1.30)), seat_h, 1.30),
        "seat_side": art_seat_side(px(0.461 * L.Z_SCALE),
                                   px(sy(1.850) - sy(1.520))),
        "bracket": art_bracket(px(sx(0.55)), px(sy(3.14) - sy(2.94)), False),
        "bracket2": art_bracket(px(sx(0.55)), px(sy(3.24) - sy(2.94)), True),
        "mesh": art_rack_mesh(px(L.WINDOW_UNITS),
                              px(math.hypot(sx(0.50), sy(3.08) - sy(2.97)))),
        "header": art_exit_header(),
        "cab_left": art_end_panel(panel_h),
        "cab_right": art_end_panel(panel_h),
        "cab_jamb": art_end_panel(panel_h, jamb=True),
        "end_left": art_end_panel(panel_h),
        "end_right": art_end_panel(panel_h),
        "pis": art_pis(),
        "poster": art_poster(*poster_size),
        "poster_route": art_poster_route(*poster_size),
        "dark": solid((22, 24, 26)),
        # --- the depth pass's own surfaces. All tiny: they wear one band per
        # face of a swept section, and the section is at most 0.1 m across, so
        # PX_PER_UNIT would ask for 2 px. 8x16 is the smallest that gives each
        # face its own band with a 1 px arris between.
        "lamp": art_lamp(),
        "trim": art_trim(),
        "fascia": art_fascia(),
        "seat_edge": art_seat_edge(),
        "part_edge": solid(FRAME),
    }
    cabin = {
        "cab_front": art_cab_front(),
        "cab_door_cab": art_cab_door(px(sx(0.80)), panel_h, False),
        "cab_door_saloon": art_cab_door(px(sx(0.80)), panel_h, True),
        "cab_nose_door": art_nose_door(panel_h),
        "driver_seat": art_driver_seat(
            px(sx(0.50)),
            px(fold_units([(2.470, 11.400), (1.850, 11.500), (1.850, 11.950)],
                          L.CAB_BAY.units_per_m))),
        "cab_desk": art_cab_desk(),
        "cab_panel": solid(SLATE),
        "cab_panel_dark": solid(SLATE_LO),
        "cab_console": solid(CAB_DESK),
        "cab_ceiling": solid(CEILING),
        "cab_light": solid(LAMP),
    }
    for source in (saloon, cabin):
        for key, rows in source.items():
            tex[key] = Tex("int_" + key, rows)
    tex["_atlas"] = pack_atlas([tex[k] for k in saloon]
                               + [tex["ceiling"], tex["floor"],
                                  tex["floor_vestibule"], tex["part_saloon"],
                                  tex["part_vestibule"]]
                               # dedup by identity: a mini slice whose aperture
                               # set matches the full one IS the same Tex, and
                               # packing it twice would give it two atlas cells
                               # and leave the second one dangling.
                               + list({id(tex["wall_" + k]): tex["wall_" + k]
                                       for k in cuts}.values()))
    tex["_cab_atlas"] = pack_atlas([tex[k] for k in cabin],
                                   "int_cab_atlas", 256)
    return tex


# ------------------------------------------------------- geometry primitives
#
# Faces are built CCW as seen FROM THE SIDE THAT MUST BE VISIBLE — which for an
# interior is nearly always inboard. That survives to the world unchanged: the
# M->OBJ map negates x and z (a 180 degree turn, determinant +1) and the loader
# negates y and z (another), so handedness never flips. MTR culls backfaces
# unconditionally, so a wrong winding is an invisible surface, not a dark one.

def wall_run(group, tex, bay, zd0, zd1, y_min=None, y_max=None):
    """The saloon lining on BOTH sides, over a donor z range, facing inboard.

    BOTH coordinates go through `tex.uv`. This used to pass the profile's own v
    straight through, which was invisible while the wall elevations were
    standalone files and wrong the moment they became atlas cells: v 0..1 then
    means the whole sheet, so the lower half of every wall sampled whatever cell
    happened to sit below it. `check` now enforces the general rule.
    """
    z0, z1 = bay.z(zd0), bay.z(zd1)
    lo, hi = min(z0, z1), max(z0, z1)
    u_lo = L.u_of_z(bay.donor_z(lo))
    u_hi = L.u_of_z(bay.donor_z(hi))
    pts = clip_profile(WALL_PROFILE, y_min, y_max)
    for side in (1.0, -1.0):
        for i in range(len(pts) - 1):
            x0, y0, v0 = pts[i]
            x1, y1, v1 = pts[i + 1]
            a0, a1 = tex.uv(u_lo, v0), tex.uv(u_lo, v1)
            b1, b0 = tex.uv(u_hi, v1), tex.uv(u_hi, v0)
            quad = [(sx(side * x0), sy(y0), lo, a0[0], a0[1]),
                    (sx(side * x1), sy(y1), lo, a1[0], a1[1]),
                    (sx(side * x1), sy(y1), hi, b1[0], b1[1]),
                    (sx(side * x0), sy(y0), hi, b0[0], b0[1])]
            group.add(tex.material, quad, reverse=(side < 0))


def extrude_run(group, tex, bay, zd0, zd1, section, mirror=True, reverse=False):
    """Sweep a (|x|, donor y, v) cross-section along a donor z range.

    This is `wall_run`'s body with the u mapping made explicit: u runs 0..1
    along the bay instead of being read off the car-long elevation, because
    every section swept here wears a texture of its own that repeats per bay.
    It is the whole depth pass's workhorse — the lamp housings, the window-head
    trim rail and the grab rail are all one open polyline swept down the car.

    ⭐ THE SECTION MUST START AND END ON THE SURFACE IT SITS ON, and the sweep
    must span the WHOLE bay: a relief that stops short of a bay boundary shows
    a gap at every repeat, and one that overshoots doubles up. That is the same
    law the panes obey, arrived at from the other direction — see
    `interior_clearances()`, which measures it rather than trusting it.

    `mirror` sweeps the same section at -x too. `reverse` flips the winding of
    every quad; which way round a given section needs to be is decided by the
    order its points are written in, and `check`'s facing test is what says so.
    """
    z0, z1 = bay.z(zd0), bay.z(zd1)
    lo, hi = min(z0, z1), max(z0, z1)
    for side in ((1.0, -1.0) if mirror else (1.0,)):
        for i in range(len(section) - 1):
            x0, y0, v0 = section[i]
            x1, y1, v1 = section[i + 1]
            a0, a1 = tex.uv(0.0, v0), tex.uv(0.0, v1)
            b1, b0 = tex.uv(1.0, v1), tex.uv(1.0, v0)
            quad = [(sx(side * x0), sy(y0), lo, a0[0], a0[1]),
                    (sx(side * x1), sy(y1), lo, a1[0], a1[1]),
                    (sx(side * x1), sy(y1), hi, b1[0], b1[1]),
                    (sx(side * x0), sy(y0), hi, b0[0], b0[1])]
            group.add(tex.material, quad, reverse=(reverse != (side < 0)))


def lamp_section():
    """The fluorescent housing, walked outboard flank -> underside -> inboard.

    Both ends run BURY_M up past the ceiling crown rather than stopping on it:
    MTR buries every abutment 0.05-0.4 into its neighbour, and an edge that
    lands exactly on the plane it grows out of is the one arrangement that can
    show a hairline of background between the two.
    """
    y = CEILING_CROWN - LAMP_DROP_M
    top = CEILING_CROWN + BURY_M
    x_in, x_out = LAMP_BOX_X
    # Walked INBOARD flank first. `extrude_run` takes the traversal direction as
    # the winding, so the order of these four points is the whole reason the
    # underside faces down; walking the other way gives a housing MTR draws as
    # a hole. `check`'s facing test is what caught it, and what keeps it caught.
    return [(x_in, top, 1.00),
            (x_in, y, 0.78),
            (x_out, y, 0.22),
            (x_out, top, 0.00)]


def trim_section():
    """The window-head trim rail: off the wall, along its face, back to it."""
    y0, y1 = TRIM_Y
    return [(wall_x_at(y1) + BURY_M, y1, 0.00),
            (wall_x_at(y1) - TRIM_DEPTH_M, y1, 0.22),
            (wall_x_at(y0) - TRIM_DEPTH_M, y0, 0.78),
            (wall_x_at(y0) + BURY_M, y0, 1.00)]


def fascia_section():
    """The luggage rack's downstand fascia, hung off the shelf's inboard edge.

    Walked outboard face -> underside -> inboard face. The top of each flank
    runs up INTO the sloped shelf (the shelf rises 0.110 m over the 0.5 m out
    to the wall, so it crosses both flanks), which is the burial rule again.
    """
    half = FASCIA_THICK_M / 2.0
    bot = FASCIA_TOP - FASCIA_DROP_M
    return [(FASCIA_X - half, FASCIA_TOP + BURY_M, 1.00),
            (FASCIA_X - half, bot, 0.70),
            (FASCIA_X + half, bot, 0.30),
            (FASCIA_X + half, FASCIA_TOP + BURY_M, 0.00)]


def aimed_face(group, tex, verts, direction):
    """Add a polygon wound so its outward normal points along `direction`.

    The depth pass adds a lot of small returns — window jambs, seat-back edges,
    housing flanks — whose correct winding depends on which side of the car,
    which end of a bench, and which way a bay's z map runs. Getting one of them
    wrong costs an invisible face and a render to notice, so nothing here is
    wound by inspection: state which way it must face and let Newell decide.
    `flat_face` is the horizontal special case of this, kept because "up" reads
    better than "(0, 1, 0)" at its call sites.
    """
    vs = list(verts)
    n = face_normal(vs)
    if sum(n[i] * direction[i] for i in range(3)) < 0:
        vs.reverse()
    group.add(tex.material, vs)


def clip_profile(profile, y_min, y_max):
    """Cut a (|x|, y, v) profile to a y band, interpolating the new ends."""
    if y_min is None and y_max is None:
        return list(profile)
    out = []
    for i in range(len(profile) - 1):
        a, b = profile[i], profile[i + 1]
        seg = [a, b]
        for bound, keep_above in ((y_min, True), (y_max, False)):
            if bound is None:
                continue
            lo, hi = min(seg[0][1], seg[1][1]), max(seg[0][1], seg[1][1])
            if lo < bound < hi:
                t = (bound - seg[0][1]) / (seg[1][1] - seg[0][1])
                cut = tuple(seg[0][j] + (seg[1][j] - seg[0][j]) * t
                            for j in range(3))
                seg = [seg[0], cut] if (seg[0][1] > bound) == keep_above else [cut, seg[1]]
        ok = True
        for p in seg:
            if y_min is not None and p[1] < y_min - 1e-9:
                ok = False
            if y_max is not None and p[1] > y_max + 1e-9:
                ok = False
        if ok and abs(seg[0][1] - seg[1][1]) > 1e-9:
            if not out:
                out.append(seg[0])
            out.append(seg[1])
    return out


def ceiling_run(group, tex, bay, zd0, zd1, profile=None):
    """The coved ceiling over a donor z range, facing down into the saloon."""
    z0, z1 = bay.z(zd0), bay.z(zd1)
    lo, hi = min(z0, z1), max(z0, z1)
    pts = profile or CEILING_PROFILE
    for i in range(len(pts) - 1):
        x0, y0, u0 = pts[i]
        x1, y1, u1 = pts[i + 1]
        a = tex.uv(u0, 0.0)
        b = tex.uv(u1, 1.0)
        group.quad(tex.material,
                   (sx(x0), sy(y0), lo, a[0], a[1]),
                   (sx(x0), sy(y0), hi, a[0], b[1]),
                   (sx(x1), sy(y1), hi, b[0], b[1]),
                   (sx(x1), sy(y1), lo, b[0], a[1]))


def door_cove(group, tex, bay, zd0, zd1):
    """The shallower cove that carries the EMERGENCY EXIT header over a door."""
    z0, z1 = bay.z(zd0), bay.z(zd1)
    lo, hi = min(z0, z1), max(z0, z1)
    for side in (1.0, -1.0):
        # As with the posters: reversing the winding does not mirror the art,
        # so the EMERGENCY EXIT legend needs u swapped for the far side.
        ua, ub = (0.0, 1.0) if side > 0 else (1.0, 0.0)
        for i in range(len(DOOR_COVE_PROFILE) - 1):
            x0, y0, v0 = DOOR_COVE_PROFILE[i]
            x1, y1, v1 = DOOR_COVE_PROFILE[i + 1]
            a0 = tex.uv(ua, v0)
            a1 = tex.uv(ub, v1)
            quad = [(sx(side * x0), sy(y0), lo, a0[0], a0[1]),
                    (sx(side * x0), sy(y0), hi, a1[0], a0[1]),
                    (sx(side * x1), sy(y1), hi, a1[0], a1[1]),
                    (sx(side * x1), sy(y1), lo, a0[0], a1[1])]
            group.add(tex.material, quad, reverse=(side > 0))


def floor_run(group, tex, bay, zd0, zd1, half=FLOOR_HALF):
    """The saloon floor over a donor z range, facing up. One texture per bay."""
    z0, z1 = bay.z(zd0), bay.z(zd1)
    lo, hi = min(z0, z1), max(z0, z1)
    y = sy(L.FLOOR_Y)
    c = [tex.uv(0.0, 0.0), tex.uv(0.0, 1.0), tex.uv(1.0, 1.0), tex.uv(1.0, 0.0)]
    group.quad(tex.material,
               (sx(-half), y, lo, c[0][0], c[0][1]),
               (sx(-half), y, hi, c[1][0], c[1][1]),
               (sx(half), y, hi, c[2][0], c[2][1]),
               (sx(half), y, lo, c[3][0], c[3][1]))


def grab_run(group, tex_panel, tex_bracket, bay, zd0, zd1):
    """One barred overhead panel per side plus a bracket at the bay's -z end."""
    z0, z1 = bay.z(zd0), bay.z(zd1)
    lo, hi = min(z0, z1), max(z0, z1)
    x_out, x_in, y_out, y_in = GRAB_PANEL
    for side in (1.0, -1.0):
        a = tex_panel.uv(0.0, 0.0)
        b = tex_panel.uv(1.0, 1.0)
        group.add(tex_panel.material,
                  [(sx(side * x_out), sy(y_in), lo, a[0], b[1]),
                   (sx(side * x_in), sy(y_out), lo, a[0], a[1]),
                   (sx(side * x_in), sy(y_out), hi, b[0], a[1]),
                   (sx(side * x_out), sy(y_in), hi, b[0], b[1])],
                  two_sided=True)
        bx0, bx1, by0, by1 = GRAB_BRACKET
        c = [tex_bracket.uv(0.0, 1.0), tex_bracket.uv(0.0, 0.0),
             tex_bracket.uv(1.0, 0.0), tex_bracket.uv(1.0, 1.0)]
        group.add(tex_bracket.material,
                  [(sx(side * bx0), sy(by0), lo, c[0][0], c[0][1]),
                   (sx(side * bx0), sy(by1), lo, c[1][0], c[1][1]),
                   (sx(side * bx1), sy(by1), lo, c[2][0], c[2][1]),
                   (sx(side * bx1), sy(by0), lo, c[3][0], c[3][1])],
                  two_sided=True)


def plane_uv(builder):
    """A planar unwrap for a donor builder that carries NO texture coordinates.

    Every `SetColor` builder in the donor is UV-less, so all of its vertices
    read texel (0, 0) and can only ever wear a flat swatch. Projecting donor x
    onto u and donor y onto v (both normalised over the builder's own bounding
    box, v measured from the top like every other v here) gives those surfaces
    a real unwrap, which is what lets the console desk carry a screen and
    gauges instead of being a black rectangle. Normalised, so a UV can never
    leave [0, 1].
    """
    xs = [v.x for v in builder.vertices]
    ys = [v.y for v in builder.vertices]
    x0, x1 = min(xs), max(xs)
    y0, y1 = min(ys), max(ys)
    dx = (x1 - x0) or 1.0
    dy = (y1 - y0) or 1.0
    return lambda v: ((v.x - x0) / dx, (y1 - v.y) / dy)


def donor_faces(group, tex, builder, bay, only=None, skip=(), warp=None,
                two_sided=False, project=False):
    """Copy a donor mesh builder's faces through a bay's z map.

    Reverses the winding when the bay's z map does: openBVE and OBJ agree on
    CCW-from-the-visible-side, and a bay whose donor z runs backwards has a
    negative determinant.

    `warp` is an optional (x, y, z) -> (x, y, z) nudge applied in DONOR space,
    before the bay's map. Donor space is where the numbers that need nudging
    live — a standoff is quoted in millimetres off a donor plane, not in units.
    `two_sided` forces both windings on a builder the donor drew one-sided.
    `project` replaces the donor's (absent) UVs with a planar unwrap.
    """
    unwrap = plane_uv(builder) if project else None
    for index, face in enumerate(builder.faces):
        if only is not None and index not in only:
            continue
        if index in skip:
            continue
        vs = []
        for vi in face.indices:
            v = builder.vertices[vi]
            x, y, z = (v.x, v.y, v.z) if warp is None else warp(v.x, v.y, v.z)
            du, dv = (unwrap(v) if unwrap is not None
                      else (0.0 if v.u is None else v.u,
                            0.0 if v.v is None else v.v))
            u, t = tex.uv(du, dv)
            vs.append((sx(x), sy(y), bay.z(z), u, t))
        group.add(tex.material, vs, two_sided=(two_sided or face.two_sided),
                  reverse=bay.flip_winding)


# --------------------------------------------------------------- the bays

RELIEF_LOG = []                   # (kind, bay, donor z0, donor z1) — see `check`


def relief_run(group, tex, bay, zd0, zd1, lamps=True, trim=True, fascia=False):
    """Every longitudinal relief the depth pass adds, over one z range.

    The lamp housings and the trim rail run the WHOLE car and are emitted
    together, because a bay that drew one and not the other would show the
    other stopping in mid-air at the seam. The rack fascia is different: it
    belongs to the overhead shelf, which only the window bays carry, so it
    starts and stops with it — the way a real rack stops at a vestibule.
    `interior_clearances()` re-measures both of those claims on the assembled
    car rather than trusting this comment.
    """
    for on, kind, section in ((lamps, "lamp", lamp_section()),
                              (trim, "trim", trim_section()),
                              (fascia, "fascia", fascia_section())):
        if not on:
            continue
        extrude_run(group, tex[kind], bay, zd0, zd1, section)
        # Logged, not inferred: once these are in the atlas they all share one
        # material and one group, so `check` could not tell them apart in the
        # written OBJ. The log is how `interior_clearances` re-measures the
        # bay-tiling rule on what was actually emitted.
        RELIEF_LOG.append((kind, bay, zd0, zd1))


def build_window_bay(model, tex):
    """30 units of plain saloon: lining, coved ceiling, floor, grab rails."""
    bay = L.WINDOW_BAY
    g = model.group("int_window")
    wall_run(g, tex["wall_window"], bay, bay.za, bay.zb)
    ceiling_run(g, tex["ceiling"], bay, bay.za, bay.zb)
    floor_run(g, tex["floor"], bay, bay.za, bay.zb)
    grab_run(g, tex["mesh"], tex["bracket"], bay, bay.za, bay.zb)
    relief_run(g, tex, bay, bay.za, bay.zb, fascia=True)


def build_door_bay(model, tex):
    """44 units centred on a doorway: two partitions and the vestibule.

    The aperture is the donor's own (-7.360 .. -5.935), which is exactly what
    the body converter cuts out of the exterior skin, so the interior stops
    where the real hole starts and the sliding leaf shows through it.
    """
    bay = L.DOOR_BAY
    g = model.group("int_door")
    a, b = L.DOOR_APERTURE_A, L.DOOR_APERTURE_B          # -5.935, -7.360

    # Wall strips outboard of the aperture, full profile; over the aperture only
    # the piece above the door head, which closes the gap between the cove's
    # outer edge and the exterior skin.
    wall_run(g, tex["wall_door"], bay, bay.za, a)
    wall_run(g, tex["wall_door"], bay, b, bay.zb)
    wall_run(g, tex["wall_door"], bay, a, b, y_min=DOOR_HEAD_Y)

    ceiling_run(g, tex["ceiling"], bay, bay.za, a)
    ceiling_run(g, tex["ceiling"], bay, b, bay.zb)
    # Over the doorway the deep cove gives way to the flat centre panel plus
    # the header cove, exactly as AInterior 16/17/18 do.
    ceiling_run(g, tex["ceiling"], bay, a, b,
                profile=[(1.150, CEILING_CROWN, 0.10),
                         (-1.150, CEILING_CROWN, 0.90)])
    door_cove(g, tex["header"], bay, a, b)

    floor_run(g, tex["floor_vestibule"], bay, bay.za, bay.zb)

    # The lamp housings run the bay end to end — the ceiling does. The trim
    # rail cannot: it sits at y 2.80..2.91, i.e. straight across the doorway,
    # so it stops at each jamb and picks up on the far side. That is what a
    # real cant-rail trim does at a door, and it keeps both bay seams filled.
    relief_run(g, tex, bay, bay.za, bay.zb, trim=False)
    relief_run(g, tex, bay, bay.za, a, lamps=False)
    relief_run(g, tex, bay, b, bay.zb, lamps=False)

    # The partitions. Each has the saloon on one side and the vestibule on the
    # other; the bay's z map is decreasing, so the aperture's donor -5.935 edge
    # is the one at local -z.
    partition(g, tex, bay.z(a), saloon_dir=-1.0)
    partition(g, tex, bay.z(b), saloon_dir=+1.0)
    # The next-stop unit over each aisle, facing the saloon. Its TEXT is an MTR
    # DISPLAY part in m7_doors.bbmodel — MTR cannot draw a display on an .obj —
    # placed from the same m7_layout.PIS_* constants this housing is.
    pis_panel(g, tex, bay.z(a), saloon_dir=-1.0)
    pis_panel(g, tex, bay.z(b), saloon_dir=+1.0)

    # Two different cards, one either side of the doorway, so a vestibule does
    # not read as the same poster printed four times.
    poster(g, tex, bay, (bay.za + a) / 2.0, "poster")
    poster(g, tex, bay, (b + bay.zb) / 2.0, "poster_route")


def partition(group, tex, z_local, saloon_dir):
    """A full-width vestibule screen, drawn from both sides with its own art.

    ⭐ IT HAS REAL THICKNESS since the depth pass. The two skins used to sit
    PARTITION_SPLIT (1.5 mm) apart — far enough not to z-fight in an offline
    render, near enough that the screen was a sheet of paper you walked
    through. They are now a whole M unit apart, and the aisle opening is lined
    with returns, so the thing reads as a bulkhead with a doorway in it. The
    returns are the point: the jambs are the part of this screen a rider passes
    within arm's length of, twice a journey.
    """
    half_t = PARTITION_THICK_M / 2.0
    for direction, key in ((saloon_dir, "part_saloon"),
                           (-saloon_dir, "part_vestibule")):
        t = tex[key]
        z_skin = z_local + direction * sx(half_t)
        for i in range(len(PARTITION_PROFILE) - 1):
            x0, y0, u0, v0 = PARTITION_PROFILE[i]
            x1, y1, u1, v1 = PARTITION_PROFILE[i + 1]
            # A quad ordered (-x, y_lo) -> (+x, y_lo) -> (+x, y_hi) -> (-x, y_hi)
            # faces +z; reverse it for the other side.
            corners = [(-x0, y0, 1.0 - u0, v0), (x0, y0, u0, v0),
                       (x1, y1, u1, v1), (-x1, y1, 1.0 - u1, v1)]
            vs = []
            for (x, y, u, v) in corners:
                uu, vv = t.uv(u, v)
                vs.append((sx(x), sy(y), z_skin, uu, vv))
            group.add(t.material, vs, reverse=(direction < 0))
    # The glazing sits in the MIDDLE of the frame now that the frame has one.
    # It used to stand GLASS_PROUD off a pair of near-coincident skins, which
    # was the only way to keep it off them; a real reveal is a better answer to
    # the same problem and it is where a real window in a screen actually is.
    for (x0, x1, y0, y1) in glass_panes(tex):
        t = tex["glass"]
        c = [t.uv(0.0, 1.0), t.uv(1.0, 1.0), t.uv(1.0, 0.0), t.uv(0.0, 0.0)]
        group.add(t.material,
                  [(sx(x0), sy(y0), z_local, c[0][0], c[0][1]),
                   (sx(x1), sy(y0), z_local, c[1][0], c[1][1]),
                   (sx(x1), sy(y1), z_local, c[2][0], c[2][1]),
                   (sx(x0), sy(y1), z_local, c[3][0], c[3][1])],
                  two_sided=True)

    # The aisle opening's reveal: two jambs and a head, spanning the screen's
    # own thickness. Three quads for the one part of this bulkhead a rider
    # walks through, which is the best triangles-per-read ratio in the file.
    t = tex["part_edge"]
    c = [t.uv(0.0, 0.0), t.uv(0.0, 1.0), t.uv(1.0, 1.0), t.uv(1.0, 0.0)]
    za, zb = z_local - sx(half_t), z_local + sx(half_t)
    head_y = PARTITION_BAND_Y[1]
    jambs = sorted(u_to_x(u) for u in PARTITION_AISLE_U)
    for x in jambs:
        aimed_face(group, t,
                   [(sx(x), sy(L.FLOOR_Y), za, c[0][0], c[0][1]),
                    (sx(x), sy(head_y), za, c[1][0], c[1][1]),
                    (sx(x), sy(head_y), zb, c[2][0], c[2][1]),
                    (sx(x), sy(L.FLOOR_Y), zb, c[3][0], c[3][1])],
                   # each jamb faces across the doorway, i.e. toward x = 0
                   (-1.0 if x > 0 else 1.0, 0.0, 0.0))
    aimed_face(group, t,
               [(sx(jambs[0]), sy(head_y), za, c[0][0], c[0][1]),
                (sx(jambs[1]), sy(head_y), za, c[1][0], c[1][1]),
                (sx(jambs[1]), sy(head_y), zb, c[2][0], c[2][1]),
                (sx(jambs[0]), sy(head_y), zb, c[3][0], c[3][1])],
               (0.0, -1.0, 0.0))


_GLASS_CACHE = []


def glass_panes(tex):
    """Find the partition's two side glazing apertures in the keyed texture.

    Measured rather than typed: the donor draws the openings as blue, so the
    alpha-zero rectangles ARE the glass, and the partition's own UV table turns
    them back into model coordinates.

    ⭐ THE AISLE IS AN ALPHA-ZERO RECTANGLE TOO, and it sits in the middle of
    the sheet. The first cut split the image in half and took min..max of each
    half's transparent columns, which swallowed the aisle's half: every pane
    ran from the outer glazing edge all the way to x = 0, so the two of them
    met as ONE sheet of glass spanning the car's full interior width, drawn
    two-sided at BOTH faces of BOTH vestibule screens. Sighting down the aisle
    you then looked through four full-width glass walls in a row — reported
    2026-07-30 as "interior glass panels across the entire traincar".

    So: group the transparent columns into contiguous runs of equal vertical
    extent, and keep only the runs the waist rail closes off below. A light
    stops at the rail; the aisle runs on past it to the kick plate. That
    discriminator is the screen's own construction, so it survives any move of
    PARTITION_AISLE_U / PARTITION_PIER_U / PARTITION_GLAZE_U.
    """
    if _GLASS_CACHE:
        return _GLASS_CACHE
    rows = tex["part_saloon"].rows
    h, w = len(rows), len(rows[0])
    waist = int(round(y_to_v(PARTITION_BAND_Y[0]) * h))
    runs = []                                  # [first col, last col + 1, span]
    for x in range(w):
        ys = [y for y in range(h) if rows[y][x][3] == 0]
        if not ys:
            continue
        span = (min(ys), max(ys))
        if span[1] > waist:                    # reaches past the rail: the aisle
            continue
        if runs and runs[-1][1] == x and runs[-1][2] == span:
            runs[-1][1] = x + 1
        else:
            runs.append([x, x + 1, span])
    for (x0, x1, (y0, y1)) in runs:
        # u -> x from the partition's own bottom-row mapping (0.02 .. 0.98
        # across +-1.400); v -> y by inverting its v column.
        _GLASS_CACHE.append((u_to_x(x1 / w), u_to_x(x0 / w),
                             v_to_y((y1 + 1) / h), v_to_y(y0 / h)))
    return _GLASS_CACHE


def u_to_x(u):
    return (0.5 - u) * (2.0 * PARTITION_PROFILE[0][0] / (1.0 - 2 * 0.020))


def x_to_u(x_m):
    """Inverse of `u_to_x`. The partition's art runs +x at u=0."""
    return 0.5 - x_m / (2.0 * PARTITION_PROFILE[0][0] / (1.0 - 2 * 0.020))


def v_to_y(v):
    table = [(p[3], p[1]) for p in PARTITION_PROFILE]
    for i in range(len(table) - 1):
        v0, y0 = table[i]
        v1, y1 = table[i + 1]
        if min(v0, v1) - 1e-9 <= v <= max(v0, v1) + 1e-9:
            t = 0.0 if v0 == v1 else (v - v0) / (v1 - v0)
            return y0 + (y1 - y0) * t
    return table[-1][1]


def poster(group, tex, bay, zd_centre, key="poster"):
    """An advertising card on the vestibule wall, standing proud of the lining.

    The donor's card was the pack's own BVEStation credit panel. It is GONE
    (user decision, 2026-07-28) — the frame is where an M7's ad frame really
    is, but what hangs in it is now drawn here: two neutral, fictional cards
    with no brand, product, person or credit text on either.
    """
    t = tex[key]
    half_z = POSTER_W_M / 2.0 * bay.units_per_m
    z = bay.z(zd_centre)
    y0, y1 = POSTER_MID_Y - POSTER_H_M / 2.0, POSTER_MID_Y + POSTER_H_M / 2.0
    for side in (1.0, -1.0):
        x0 = wall_x_at(y0) - POSTER_PROUD
        x1 = wall_x_at(y1) - POSTER_PROUD
        # Reversing the winding turns the quad round but does NOT mirror its
        # art, so the far wall's copy would read backwards. Swap u instead.
        a, b = (0.0, 1.0) if side > 0 else (1.0, 0.0)
        c = [t.uv(a, 1.0), t.uv(b, 1.0), t.uv(b, 0.0), t.uv(a, 0.0)]
        group.add(t.material,
                  [(sx(side * x0), sy(y0), z - half_z, c[0][0], c[0][1]),
                   (sx(side * x0), sy(y0), z + half_z, c[1][0], c[1][1]),
                   (sx(side * x1), sy(y1), z + half_z, c[2][0], c[2][1]),
                   (sx(side * x1), sy(y1), z - half_z, c[3][0], c[3][1])],
                  reverse=(side < 0))


def pis_panel(group, tex, z_local, saloon_dir):
    """The next-stop unit's housing, on a partition's SALOON face.

    A flat quad standing `PIS_DECAL_STANDOFF` off the skin — MTR's decal idiom
    (79 uses across its corpus, every one a flat quad ~0.1 proud, never a
    proud box). The MTR DISPLAY part that draws the text sits a little further
    out again, at `PIS_STANDOFF`, and is placed from the same constants by
    tools/gen_m7_doors.py.

    Single-sided, and only on the saloon face: the real unit is single-sided,
    and a rider in the vestibule is looking through the aisle at the opposite
    partition's screen anyway.
    """
    t = tex["pis"]
    z = z_local + saloon_dir * (sx(PARTITION_THICK_M / 2.0) + L.PIS_DECAL_STANDOFF)
    x0, x1 = -L.PIS_BOX_HALF_X_M, L.PIS_BOX_HALF_X_M
    y0, y1 = L.PIS_BOX_Y_M
    # u runs with +x on the saloon side and has to swap for the face that
    # looks the other way, exactly as `poster` does — reversing a winding
    # turns a quad round but does not mirror what is printed on it.
    a, b = (1.0, 0.0) if saloon_dir > 0 else (0.0, 1.0)
    c = [t.uv(a, 1.0), t.uv(b, 1.0), t.uv(b, 0.0), t.uv(a, 0.0)]
    group.add(t.material,
              [(sx(x0), sy(y0), z, c[0][0], c[0][1]),
               (sx(x1), sy(y0), z, c[1][0], c[1][1]),
               (sx(x1), sy(y1), z, c[2][0], c[2][1]),
               (sx(x0), sy(y1), z, c[3][0], c[3][1])],
              reverse=(saloon_dir < 0))


def wall_x_at(y_m):
    for i in range(len(WALL_PROFILE) - 1):
        x0, y0, _ = WALL_PROFILE[i]
        x1, y1, _ = WALL_PROFILE[i + 1]
        if min(y0, y1) - 1e-9 <= y_m <= max(y0, y1) + 1e-9:
            t = 0.0 if y0 == y1 else (y_m - y0) / (y1 - y0)
            return x0 + (x1 - x0) * t
    return WALL_PROFILE[-1][0]


# ----------------------------------------------------------------- the seats
#
# ⭐ THE BACKREST HAS THICKNESS since the depth pass (2026-07-28). The donor
# models a bench as ONE folded zero-thickness strip (builder 1, the moquette)
# with a second strip behind it (builder 2, the grey shell) that meets it
# EXACTLY at the top edge — so a seat back was a sheet of paper, and looking
# along a row of them from the aisle, which is how a passenger sees this car,
# the backs were edge-on and effectively invisible. MTR's canonical seat is a
# 1-thick cushion and a 1-thick back, so the shell is pushed back one unit and
# the resulting open top and open ends are closed.
#
# The shove is DERIVED, not signed by hand: the reversed benches
# (asiento*180.csv) are the same mesh turned round, so "behind" is +z for half
# the car and -z for the other half, and `back_shell_shift` reads which from
# the geometry.


def skin_ladder(builder, x_target):
    """A builder's (donor y, donor z) fold, taken down one edge of the bench.

    Both seat strips are drawn as a ladder of paired vertices at +-half; taking
    the +x rail and sorting it top-down gives the silhouette the side returns
    have to follow, without hard-coding any of the donor's fold heights.
    """
    pts = [(v.y, v.z) for v in builder.vertices if abs(v.x - x_target) < 1e-6]
    return sorted(pts, key=lambda p: -p[0])


def ladder_z(ladder, y_m):
    for (y0, z0), (y1, z1) in zip(ladder, ladder[1:]):
        if min(y0, y1) - 1e-9 <= y_m <= max(y0, y1) + 1e-9:
            t = 0.0 if y0 == y1 else (y_m - y0) / (y1 - y0)
            return z0 + (z1 - z0) * t
    return ladder[-1][1]


def bench_half(obj):
    return max(abs(v.x) for b in obj.builders for v in b.vertices)


def back_shell_shift(obj):
    """How far, and which way, to push the back shell to give it thickness.

    Sign from the mesh: the shell is behind the sitter, the cushion's front
    edge is in front, so the shell moves AWAY from the cushion. Reading it off
    the geometry is what lets one code path serve both the forward-facing and
    the reversed benches.
    """
    half = bench_half(obj)
    back = skin_ladder(obj.builders[SEAT_BACK_BUILDER], half)
    front = skin_ladder(obj.builders[1], half)
    return math.copysign(SEAT_BACK_T, back[0][1] - front[-1][1])


def seat_back_edges(group, tex, obj, bench_x, seats, shift):
    """Close the top and the aisle end of a backrest that now has thickness.

    TOP: one cap per seat, not one per bench. The gap between caps lines up
    with the painted scallop in `art_seat_front`/`art_seat_back`, so the notch
    stays a real hole through the whole backrest instead of being bridged — the
    cheap stand-in for MTR's per-seat headrest boxes, at 3 extra quads a row
    rather than 23.

    END: the aisle side only. The window side sits 20-33 mm off the lining and
    is never seen; the aisle side is what a standing rider's eye runs along.
    """
    t = tex["seat_edge"]
    half = bench_half(obj)
    back = skin_ladder(obj.builders[SEAT_BACK_BUILDER], half)
    front = skin_ladder(obj.builders[1], half)
    top_y = back[0][0]
    z_front = front[0][1]
    z_back = z_front + shift

    def zc(z_m):
        return (z_m - SEAT_CENTRE_M) * L.Z_SCALE

    c = [t.uv(0.0, 0.0), t.uv(0.0, 1.0), t.uv(1.0, 1.0), t.uv(1.0, 0.0)]
    gap = SEAT_NOTCH_M / 2.0
    for i in range(seats):
        x0 = -half + 2.0 * half * i / seats + (gap if i else 0.0)
        x1 = -half + 2.0 * half * (i + 1) / seats - (gap if i + 1 < seats else 0.0)
        flat_face(group, t,
                  [(sx(bench_x + x0), sy(top_y), zc(z_front), c[0][0], c[0][1]),
                   (sx(bench_x + x0), sy(top_y), zc(z_back), c[1][0], c[1][1]),
                   (sx(bench_x + x1), sy(top_y), zc(z_back), c[2][0], c[2][1]),
                   (sx(bench_x + x1), sy(top_y), zc(z_front), c[3][0], c[3][1])],
                  up=True)

    edge = -half if bench_x > 0 else half          # the end nearer the aisle
    x = bench_x + edge
    aimed_face(group, t,
               [(sx(x), sy(top_y), zc(z_front), c[0][0], c[0][1]),
                (sx(x), sy(top_y), zc(z_back), c[1][0], c[1][1]),
                (sx(x), sy(SEAT_RETURN_Y),
                 zc(ladder_z(back, SEAT_RETURN_Y) + shift), c[2][0], c[2][1]),
                (sx(x), sy(SEAT_RETURN_Y),
                 zc(ladder_z(front, SEAT_RETURN_Y)), c[3][0], c[3][1])],
               (-1.0 if bench_x > 0 else 1.0, 0.0, 0.0))


def build_seats(model, tex, donor_cache):
    """One 3+2 row per group, authored centred on z = 0.

    Two groups, not one: the donor turns the seats round at mid-car so every
    passenger faces the middle, and that is reproduced by placing `int_seat_fwd`
    in the -z half and `int_seat_rev` in the +z half.
    """
    material_of = {
        "asiento2.png": "seat_narrow", "asiento2back.png": "seat_narrow_back",
        "asiento3.png": "seat_wide", "asiento3back.png": "seat_wide_back",
        "lateral.png": "seat_side",
    }
    for group_name, which in (("int_seat_fwd", 0), ("int_seat_rev", 1)):
        g = model.group(group_name)
        for csv_fwd, csv_rev, bench_x, seats in (BENCH_WIDE, BENCH_NARROW):
            name = (csv_fwd, csv_rev)[which]
            obj = donor_cache.setdefault(
                name, bve_csv.parse_csv(os.path.join(DONOR, name)))
            shift = back_shell_shift(obj)
            for b in obj.builders:
                if b.index in SEAT_SKIP_BUILDERS:
                    continue
                key = material_of.get(b.texture)
                if key is None:
                    continue
                t = tex[key]
                dz = shift if b.index == SEAT_BACK_BUILDER else 0.0
                for face in b.faces:
                    vs = []
                    for vi in face.indices:
                        v = b.vertices[vi]
                        u, tv = t.uv(0.0 if v.u is None else v.u,
                                     0.0 if v.v is None else v.v)
                        vs.append((sx(v.x + bench_x), sy(v.y),
                                   (v.z + dz - SEAT_CENTRE_M) * L.Z_SCALE,
                                   u, tv))
                    g.add(t.material, vs, two_sided=face.two_sided)
            seat_back_edges(g, tex, obj, bench_x, seats, shift)

    # SEAT-typed marker planes. PartType.SEAT is INERT in MTR 4.0.5 — its switch
    # map never assigns it a case, so the geometry is dropped and no box is
    # produced — but MTR's own london_underground_s7 declares exactly this shape
    # (a flat plate over the seating area), so declaring it costs nothing and
    # means the right thing if a later MTR implements it.
    zone = model.group("int_seat_zone")
    t = tex["dark"]
    y = sy(SEAT_PAN_Y)
    half = L.SEAT_DEPTH_UNITS / 2.0
    for csv_fwd, _csv_rev, bench_x, _seats in (BENCH_WIDE, BENCH_NARROW):
        obj = donor_cache[csv_fwd]
        xs = [v.x for b in obj.builders for v in b.vertices]
        x0, x1 = min(xs) + bench_x, max(xs) + bench_x
        c = [t.uv(0, 0), t.uv(0, 1), t.uv(1, 1), t.uv(1, 0)]
        zone.quad(t.material,
                  (sx(x0), y, -half, c[0][0], c[0][1]),
                  (sx(x0), y, half, c[1][0], c[1][1]),
                  (sx(x1), y, half, c[2][0], c[2][1]),
                  (sx(x1), y, -half, c[3][0], c[3][1]))


# -------------------------------------------------------------- the end bays
#
# Authored with the OUTWARD face at local -z, so `end1` (unflipped) and `end2`
# (flipped) put the same geometry at opposite ends of the car, both facing out.
# A flipped instance also mirrors x, which is why no seats live in these groups:
# a 3+2 saloon cannot be mirrored. Seat rows in the end bays are placed by the
# common seat definitions instead, which are never flipped.

def build_gangway_end(model, tex, donor, bay, suffix=""):
    g = model.group("int_end_gangway" + suffix)
    inner = bay.zb
    wall_from = bay.clamp(L.SALOON_WALL_END_Z[0])          # -12.520
    wall_run(g, tex["wall_rear" + suffix], bay, wall_from, inner)
    ceiling_run(g, tex["ceiling"], bay, bay.clamp(-12.320), inner)
    floor_run(g, tex["floor"], bay, wall_from, inner)
    # Each relief follows the surface it belongs to, so both stop exactly where
    # the saloon does and neither hangs over the gangway opening.
    relief_run(g, tex, bay, bay.clamp(-12.320), inner, trim=False)
    relief_run(g, tex, bay, wall_from, inner, lamps=False)

    # The end wall itself: a splayed reveal from the gangway opening out to the
    # side lining (AInterior 23/24). The opening is left EMPTY on purpose —
    # MTR draws its own gangway through it and a door would block the corridor.
    for index, key in ((23, "end_right"), (24, "end_left")):
        b = donor.builders[index]
        if all(bay.contains(v.z) for v in b.vertices):
            donor_faces(g, tex[key], b, bay)
    b = donor.builders[27]                                  # ceiling infill
    if all(bay.contains(v.z) for v in b.vertices):
        donor_faces(g, tex["dark"], b, bay)


def build_cab_end(model, tex, donor, bay, suffix=""):
    g = model.group("int_end_cab" + suffix)
    inner = bay.zb
    wall_to = bay.clamp(L.SALOON_WALL_END_Z[1])             # 11.300
    wall_run(g, tex["wall_cab" + suffix], bay, wall_to, inner)
    ceiling_run(g, tex["ceiling"], bay, wall_to, inner)
    floor_run(g, tex["floor"], bay, wall_to, inner)
    # Both reliefs stop at the cab bulkhead; the cab is a different room and
    # carries neither (its own ceiling and lining are the donor's).
    relief_run(g, tex, bay, wall_to, inner)

    # Cab bulkhead + the recess that holds the cab door.
    for index, key in ((11, "cab_left"), (13, "cab_right"),
                       (12, "cab_jamb"), (14, "cab_jamb")):
        b = donor.builders[index]
        if all(bay.contains(v.z) for v in b.vertices):
            donor_faces(g, tex[key], b, bay)
    # The recess used to be capped here with a flat dark panel, because the cab
    # was out of scope. It is not any more: `int_cab` puts the real door leaf in
    # that opening, and a cap would hide it. `int_cab` is placed at exactly the
    # positions this group is (see `placements`), so the saloon is never open to
    # nothing — but the two groups now have to travel together.


# --------------------------------------------------------------------- cab

def cab_z(z_m):
    """Squeeze donor z toward the bulkhead so nothing lands on the body skin.

    Fixed end: the cab-door recess (11.300), which the saloon side already
    draws and must keep meeting. Moving end: the nose, pulled `CAB_INSET_M`
    inboard. Everything between rides along, so no joint inside the cab opens.
    """
    span = CAB_OUTER_Z - L.CAB_RECESS_Z
    return L.CAB_RECESS_Z + (z_m - L.CAB_RECESS_Z) * (span - CAB_INSET_M) / span


def cab_warp(index):
    """The donor-space nudge for one cab builder (see the constants block)."""
    pull = CAB_NOSE_PULL_M if index in CAB_NOSE_BUILDERS else 0.0
    standoff = (CAB_DOOR_STANDOFF_M + CAB_DOOR_BUILDERS[index]
                if index in CAB_DOOR_BUILDERS else
                CAB_BULKHEAD_STANDOFF_M if index in CAB_BULKHEAD_BUILDERS
                else 0.0)
    if not pull and not standoff:
        return lambda x, y, z: (x, y, cab_z(z))

    def warp(x, y, z):
        if pull and abs(x) >= CAB_NOSE_PULL_FROM:
            x -= math.copysign(pull, x)
        return (x, y, cab_z(z + standoff))
    return warp


def flat_face(group, tex, verts, up):
    """A horizontal polygon, wound so it faces up (or down) whatever I typed.

    The cab's floor and ceiling are irregular hexagons that follow the nose, and
    getting the winding of one of those right by inspection is a coin flip that
    only shows up as an invisible surface three renders later. Newell already
    knows the answer, so ask it.
    """
    vs = list(verts)
    ny = face_normal(vs)[1]
    if (ny < 0) == bool(up):
        vs.reverse()
    group.add(tex.material, vs)


def cab_floor(group, tex, bay):
    """The cab floor: the saloon's own sill width, narrowing at the nose."""
    y = sy(L.FLOOR_Y)
    back = bay.z(L.CAB_RECESS_Z)
    front = bay.z(cab_z(CAB_SIDE_END_Z))
    nose = bay.z(cab_z(CAB_FLOOR_NOSE_Z))
    c = [tex.uv(0, 0), tex.uv(0, 1), tex.uv(1, 1), tex.uv(1, 0)]
    flat_face(group, tex,
              [(sx(-FLOOR_HALF), y, back, c[0][0], c[0][1]),
               (sx(-FLOOR_HALF), y, front, c[1][0], c[1][1]),
               (sx(FLOOR_HALF), y, front, c[2][0], c[2][1]),
               (sx(FLOOR_HALF), y, back, c[3][0], c[3][1])], up=True)
    h = CAB_FLOOR_NOSE_HALF
    flat_face(group, tex,
              [(sx(-FLOOR_HALF), y, front, c[0][0], c[0][1]),
               (sx(-h), y, nose, c[1][0], c[1][1]),
               (sx(h), y, nose, c[2][0], c[2][1]),
               (sx(FLOOR_HALF), y, front, c[3][0], c[3][1])], up=True)


def build_cab(model, tex, cab, bay, suffix=""):
    """The driving cab, forward of the saloon bulkhead.

    Lining, floor and ceiling are drawn by this file rather than copied from the
    donor so they continue the saloon's own surfaces exactly (the donor cab is
    100 mm wider than its own saloon and tiles its floor 5x). Everything else is
    the donor's, through `cab_warp`.
    """
    g = model.group("int_cab" + suffix)

    # Side lining: WALL_PROFILE is bit-for-bit the donor cab's own LATERAL
    # section, so this is a continuation of the saloon wall, not a new one.
    wall_run(g, tex["wall_cab" + suffix], bay, L.CAB_RECESS_Z,
             cab_z(CAB_SIDE_END_Z))
    cab_floor(g, tex["floor"], bay)

    for b in cab.builders:
        if b.index in CAB_SKIP:
            continue
        key = CAB_MATERIAL.get(b.index)
        if key is None:
            continue
        if not all(bay.contains(cab_z(v.z)) for v in b.vertices):
            continue
        donor_faces(g, tex[key], b, bay, warp=cab_warp(b.index),
                    two_sided=b.index in CAB_TWO_SIDED,
                    project=b.index in CAB_PROJECTED)


# ------------------------------------------------------------------ emitting

def to_obj_vertex(x, y, z):
    """M units -> OBJ. One OBJ unit is one BLOCK, and x and z are negated."""
    return (-x / 16.0, y / 16.0, -z / 16.0)


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
                # v passes through: MTR's obj path samples v in IMAGE
                # convention (v=0 = the top row), same as the donor's own UVs.
                refs.append("%d/%d" % (vid(ox, oy, oz), tid(u, v)))
            body.append("f " + " ".join(refs))

    out = ["# LIRR M7 saloon interior — generated by",
           "# tools/convert_m7_interior.py from the openBVE donor.",
           "# DO NOT EDIT: re-run the converter instead.",
           "mtllib %s" % mtl_name, ""]
    out += ["v %.5f %.5f %.5f" % p for p in verts]
    out += ["vt %.6f %.6f" % p for p in uvs]
    out.append("")
    out += body
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write("\n".join(out) + "\n")

    mtl = ["# Generated by tools/convert_m7_interior.py — do not edit.", ""]
    for material in sorted(mats):
        mtl += ["newmtl %s" % material, "Kd 1 1 1", "d 1",
                "map_Kd %s" % mtl_path_of(mats[material]), ""]
    with open(os.path.join(os.path.dirname(path), mtl_name), "w") as fh:
        fh.write("\n".join(mtl) + "\n")
    return len(verts), sum(len(g.faces) for g in model.groups.values())


def used_materials(model, tex):
    names = {m for g in model.groups.values() for m, _ in g.faces}
    out = {}
    for t in tex.values():
        if t.material in names and t.material not in out:
            out[t.material] = t.atlas_tex if t.atlas is not None else t
    return out


def write_textures(mats, directory=None, prune=False):
    """Write every texture the model actually uses.

    `prune` deletes the `int_*.png` this converter no longer emits. It exists
    because the art pass folded twelve standalone files into two atlases, and a
    stale int_wall_window.png left in the jar is a texture nothing references
    and nobody notices.
    """
    directory = directory or TEX_DIR
    os.makedirs(directory, exist_ok=True)
    written = []
    for t in mats.values():
        pngtool.write_png(os.path.join(directory, t.name + ".png"), t.rows)
        written.append(t.name)
    if prune:
        keep = {name + ".png" for name in written}
        for name in sorted(os.listdir(directory)):
            if name.startswith("int_") and name.endswith(".png") \
                    and name not in keep:
                os.remove(os.path.join(directory, name))
    return sorted(set(written))


def build_model():
    del RELIEF_LOG[:]             # so a second build does not double the log
    donor = bve_csv.parse_csv(os.path.join(DONOR, "AInterior.csv"))
    cab = bve_csv.parse_csv(os.path.join(DONOR, CAB_CSV))
    tex = build_textures()
    model = Model()
    build_window_bay(model, tex)
    build_door_bay(model, tex)
    build_seats(model, tex, {})
    build_cab_end(model, tex, donor, L.CAB_BAY)
    build_cab_end(model, tex, donor, L.CAB_BAY_MINI, "_mini")
    build_cab(model, tex, cab, L.CAB_BAY)
    build_cab(model, tex, cab, L.CAB_BAY_MINI, "_mini")
    build_gangway_end(model, tex, donor, L.GANGWAY_BAY)
    build_gangway_end(model, tex, donor, L.GANGWAY_BAY_MINI, "_mini")
    return model, tex


# ------------------------------------------------------ assembly / preview
#
# The same transform MTR applies, so a preview render doubles as the check on
# every position sign. Measured, not assumed — see convert_openbve_m7.py's
# docstring for the harness that established it.
#
#     unflipped(X, Z): (x, y, z) -> ( x - X, y,  z + Z)
#       flipped(X, Z): (x, y, z) -> (-x - X, y, -z - Z)

def placements(layout, end_kinds):
    """Where every interior group goes, for one car length and end pair.

    This mirrors what the report asks gen_m7_assets.py to emit, so the preview
    and the shipped definitions cannot drift without this file noticing.
    """
    out = []
    for z in layout.window.centres:
        out.append(("int_window", 0.0, z, False))
    for z in layout.door.centres:
        out.append(("int_door", 0.0, z, False))
    for z in layout.seat_rows_forward:
        out.append(("int_seat_fwd", 0.0, z, False))
        out.append(("int_seat_zone", 0.0, z, False))
    for z in layout.seat_rows_reverse:
        out.append(("int_seat_rev", 0.0, z, False))
        out.append(("int_seat_zone", 0.0, z, False))
    suffix = "" if layout is L.NORMAL else "_mini"
    end_z = layout.end.centres[0]
    for end, kind in zip((1, 2), end_kinds):
        out.append(("int_end_%s%s" % (kind, suffix), 0.0, end_z, end == 2))
        if kind == "cab":
            # Same position, same flip: the cab IS that end. Splitting it into
            # its own part only buys the properties files the option of a cab
            # car whose far end is a gangway, which is exactly what m7_cab_1 is.
            out.append(("int_cab" + suffix, 0.0, end_z, end == 2))
    return out


def assemble(model, places):
    out = Model()
    for (group, X, Z, flipped) in places:
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


VARIANTS = {
    "full_cab1": (L.NORMAL, ("cab", "gangway")),
    "full_trailer": (L.NORMAL, ("gangway", "gangway")),
    "full_cab3": (L.NORMAL, ("cab", "cab")),
    "mini_cab1": (L.MINI, ("cab", "gangway")),
}


# ---------------------------------------------------------------- consistency

# The shell groups: every single-sided face in these must face the saloon, or a
# rider sees straight through it. Seat and marker groups are excluded — a seat's
# own surfaces face away from the seat, not toward the aisle.
SHELL_GROUPS = ("int_window", "int_door", "int_end_cab", "int_end_gangway",
                "int_end_cab_mini", "int_end_gangway_mini")

# ⭐ THE SALOON IS A ROOM, NOT A LINE (2026-07-28, depth pass).
#
# `facing_inboard` used to test every single-sided face against ONE point: the
# car's axis, at the face's own z, at EYE_Y. That is right for a lining and a
# ceiling, which is all there was — but the moment a relief has two flanks, one
# of them correctly faces OUTBOARD and the single-point test calls it inside
# out. The lamp housing's outboard flank is seen by anyone in a window seat;
# the rack fascia's is seen from across the car.
#
# So the test now asks whether ANY of a handful of places a rider can actually
# be can see the face. It is strictly weaker than the old one, and it still
# catches everything the old one did: an inside-out lining faces |x| 1.47
# outward and every viewpoint here is inboard of that, so it fails from all of
# them. Points are (donor x, donor y) only — z is taken from the face itself,
# exactly as before, which is what keeps the vestibule partitions (whose
# normals are +-z) out of the test rather than failing half of them.
EYE_Y = 2.0                       # donor y — kept: `check` still reports it
SALOON_VIEWPOINTS = (
    (0.000, 2.915),               # standing in the aisle
    (0.000, 2.300),               # ...and sitting in it
    (1.050, 2.300),               # a window seat, each side
    (-1.050, 2.300),
    (0.000, 1.900),               # low: a child, or a bag on the floor
)

# The cab cannot use the saloon's test: its occupants are OFF the car axis, so
# "faces the centreline" is simply false there — the driver sits at donor
# x +0.98 and the console's outboard flanks correctly face further outboard
# still. Each single-sided cab face is required instead to be visible from at
# least one place a person can actually be. Donor coordinates.
CAB_VIEWPOINTS = (
    (0.980, 2.550, 11.700),       # the driver's eye
    (-0.945, 2.100, 11.550),      # the crew bench, facing the front
    (0.000, 2.850, 10.400),       # a rider in the saloon, at the door window
    (0.000, 2.600, 12.100),       # someone standing mid-cab, facing back
)


def facing_visible(group, points):
    """(visible, blind) counts of the group's SINGLE-sided faces.

    A face counts as visible if its outward normal points toward any of
    `points`. Under MTR's unconditional backface culling a face that faces away
    from every viewpoint is not dark — it is not there at all, which is the
    failure this catches.
    """
    reversed_faces = {tuple(tuple(v) for v in reversed(vs))
                      for _m, vs in group.faces}
    visible = blind = 0
    for _m, vs in group.faces:
        if tuple(tuple(v) for v in vs) in reversed_faces:
            continue
        n = face_normal(vs)
        c = [sum(v[i] for v in vs) / len(vs) for i in range(3)]
        scale = math.sqrt(sum(x * x for x in n)) or 1.0
        if any(sum(n[i] * (p[i] - c[i]) for i in range(3))
               / (scale * (math.sqrt(sum((p[i] - c[i]) ** 2 for i in range(3)))
                           or 1.0)) > 0.05 for p in points):
            visible += 1
        else:
            blind += 1
    return visible, blind


def facing_inboard(group, viewpoints=SALOON_VIEWPOINTS):
    """(inward, outward) counts of the group's SINGLE-sided faces.

    MTR culls backfaces unconditionally, so this is the check that matters and
    a summed-normal heuristic is not it: a lining and a ceiling point in
    opposite directions and both are right. Each face is tested against the
    places a rider can be (SALOON_VIEWPOINTS — see the note there), taken at
    the face's own z. Two-sided faces (the donor's AddFace2, emitted here as a
    face plus its exact reverse) are skipped, as are faces whose normal is
    perpendicular to every test direction, i.e. the vestibule partitions.

    A face is INWARD as soon as one viewpoint sees it, and OUTWARD only when
    every viewpoint is behind it — which is the definition of a face MTR will
    draw as nothing at all.
    """
    reversed_faces = {tuple(tuple(v) for v in reversed(vs)) for _m, vs in group.faces}
    inward = outward = 0
    for _m, vs in group.faces:
        if tuple(tuple(v) for v in vs) in reversed_faces:
            continue
        n = face_normal(vs)
        c = [sum(v[i] for v in vs) / len(vs) for i in range(3)]
        cosines = []
        for (px_m, py_m) in viewpoints:
            to_eye = (sx(px_m) - c[0], sy(py_m) - c[1], 0.0)
            scale = math.sqrt(sum(x * x for x in n)) * math.sqrt(
                sum(x * x for x in to_eye)) or 1.0
            cosines.append(sum(n[i] * to_eye[i] for i in range(3)) / scale)
        if max(cosines) > 0.05:
            inward += 1
        elif max(cosines) < -0.05:
            outward += 1
    return inward, outward


# The pocket the sliding door leaves occupy (|x| 22.1..23.1, from
# tools/gen_m7_doors.py). An interior part reaching into it would be sliced open
# every time a door opened. The saloon lining's widest point is 21.81 — the
# tumblehome's bulge, present in every bay — so the real headroom is 0.29 units
# and the margin below has to be smaller than that to mean anything.
DOOR_POCKET_INNER_X = 22.1        # M units
DOOR_POCKET_MARGIN = 0.15


def body_edges(group_prefixes):
    """Every EDGE of the body shell's end bays, in M units, x folded to |x|.

    Read out of the shipped m7.obj rather than assumed: the nose narrows, and
    the only authority on how much is the geometry the body converter wrote.
    Edges (not faces) because both questions asked of this are silhouette
    questions — how wide is the shell at this z, how far forward at this |x| —
    and a silhouette is where edges cross the query value.
    """
    path = os.path.join(MODEL_DIR, "m7.obj")
    if not os.path.exists(path):
        return {}
    verts, out, current = [], {}, None
    with open(path) as fh:
        for line in fh:
            if line.startswith("v "):
                x, y, z = (float(p) for p in line.split()[1:4])
                verts.append((-x * 16.0, y * 16.0, -z * 16.0))
            elif line.startswith("g "):
                current = line[2:].strip()
            elif line.startswith("f ") and current:
                for prefix in group_prefixes:
                    if current != prefix:
                        continue
                    idx = [int(t.split("/")[0]) - 1 for t in line.split()[1:]]
                    ring = out.setdefault(prefix, [])
                    for i in range(len(idx)):
                        a = verts[idx[i]]
                        b = verts[idx[(i + 1) % len(idx)]]
                        ring.append(((abs(a[0]), a[1], a[2]),
                                     (abs(b[0]), b[1], b[2])))
    return out


def _cross(edges, axis, value, take):
    """max/min of `take` over every edge crossing `axis` == `value`."""
    best = None
    for a, b in edges:
        lo, hi = min(a[axis], b[axis]), max(a[axis], b[axis])
        if not (lo - 1e-9 <= value <= hi + 1e-9):
            continue
        t = 0.0 if hi - lo < 1e-9 else (value - a[axis]) / (b[axis] - a[axis])
        got = a[take] + (b[take] - a[take]) * t
        best = got if best is None else (max(best, got) if take == 0
                                         else min(best, got))
    return best


def atlas_straddles(model, tex):
    """Faces whose UVs leave the atlas cell they are supposed to be inside.

    Once a texture is an atlas cell, EVERY coordinate written for it has to go
    through `Tex.uv`; a raw 0..1 coordinate silently addresses the whole sheet
    instead, and what it lands on is some other texture. That is not a UV out of
    range, so the [0,1] check cannot see it — but a face that overlaps two cells
    always is one, and a face that overlaps none is one too. Reported per group
    so the offending helper is obvious.
    """
    cells, seen = {}, set()
    # Keyed by IDENTITY: two keys can name the same Tex (a mini wall slice
    # whose apertures match the full one shares its object), and listing its
    # cell twice would make every face inside it "in 2 cells" and be reported.
    for t in tex.values():
        if t.atlas is None or id(t) in seen:
            continue
        seen.add(id(t))
        u0, v0, du, dv = t.atlas
        cells.setdefault("m7_" + t.atlas_name, []).append(
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


# The interior's own envelope: the same |x| ceiling `check` applies to the whole
# model, quoted in donor metres so a section can be tested before it is swept.
INTERIOR_HALF_M = 1.480


def interior_clearances():
    """Re-measure every clearance the depth pass created. (problems, notes).

    ⭐ WHY THIS EXISTS. Adding relief to this car is not a matter of taste, it
    is a packing problem: the donor's 3+2 benches leave 33 mm between a seat
    back and the lining, and the overhead gear leaves 47 mm between a lamp
    housing and the rack fascia. Every depth constant in this file was chosen
    against one of those gaps, and none of them is visible from the code that
    uses it — so a later change to a bench position, a bay size or a profile
    would close one silently and the only symptom would be two surfaces
    flickering through each other in game. This turns all of it into arithmetic
    that runs on every build.

    It also enforces the BAY-TILING rule on the new geometry: a longitudinal
    relief must span its whole bay, because a repeat that stops 1 mm short
    shows a gap at every seam down the car.
    """
    problems, notes = [], []

    def gap(name, have, need=0.0):
        if have < need:
            problems.append("%s: %.0f mm — needs %.0f" % (name, have * 1000,
                                                          need * 1000))
        return have

    # --- overhead: the lamp housing, the rack fascia and the rack itself
    lamp_out = max(LAMP_BOX_X)
    fascia_in = FASCIA_X - FASCIA_THICK_M / 2.0
    notes.append("lamp housing to rack fascia %.0f mm across"
                 % (gap("lamp housing vs rack fascia",
                        fascia_in - lamp_out, 0.020) * 1000))
    gap("lamp housing vs the ceiling crown it hangs from",
        LAMP_DROP_M, 1.0 * UNIT_M_Y)
    # The fascia hangs off the rack's inboard edge, so the sloped shelf must
    # actually cross it — that is the burial rule, measured.
    shelf_x_in, shelf_x_out, shelf_y_out, shelf_y_in = GRAB_PANEL
    slope = (shelf_y_out - shelf_y_in) / (shelf_x_out - shelf_x_in)
    at_flank = shelf_y_in + slope * (FASCIA_THICK_M / 2.0)
    gap("the rack shelf must cross the fascia's outboard flank",
        (FASCIA_TOP + BURY_M) - at_flank, 0.001)

    # --- the trim rail: the one free band on the lining, and it is FULL
    y0, y1 = TRIM_Y
    # Only the windows the rail actually passes: `apertures_in` would also hand
    # back the cab's side window, which is in the cab bay's z range but on the
    # CAB's own wall segment, past the bulkhead the rail stops at.
    heads = [a[2] for kind, _bay, zd0, zd1 in RELIEF_LOG if kind == "trim"
             for a in saloon_apertures()
             if min(zd0, zd1) - 1e-6 <= min(a[0], a[1])
             and max(a[0], a[1]) <= max(zd0, zd1) + 1e-6]
    if heads:
        gap("trim rail vs the window head below it", y0 - max(heads), 0.020)
    gap("trim rail vs the ad card below it",
        y0 - (POSTER_MID_Y + POSTER_H_M / 2.0), 0.020)
    gap("trim rail vs the grab bracket above it",
        GRAB_BRACKET[2] - y1, 0.005)
    gap("trim rail vs the tallest seat back",
        y0 - 2.400, 0.100)
    notes.append("trim rail sits in a %.0f mm band, %.0f mm deep, with %.0f mm "
                 "clear below and %.0f above"
                 % ((y1 - y0) * 1000, TRIM_DEPTH_M * 1000,
                    (y0 - (POSTER_MID_Y + POSTER_H_M / 2.0)) * 1000,
                    (GRAB_BRACKET[2] - y1) * 1000))

    # --- nothing may poke out through the lining's own envelope
    for kind, section in (("lamp", lamp_section()), ("trim", trim_section()),
                          ("fascia", fascia_section())):
        widest = max(abs(p[0]) for p in section)
        gap("the %s section vs the interior envelope" % kind,
            INTERIOR_HALF_M - widest, 0.0)

    # --- the seats, now that their backs are 1 unit thick
    pitch_m = L.NORMAL.seat_pitch / L.Z_SCALE
    donor_depth = 0.500 + SEAT_BACK_T
    gap("legroom between seat rows", pitch_m - donor_depth, 0.150)
    for _csv, _rev, bench_x, seats in (BENCH_WIDE, BENCH_NARROW):
        outer = abs(bench_x) + BENCH_HALF_M[seats]
        gap("bench at x %+.3f vs the lining at seat-top height" % bench_x,
            wall_x_at(2.400) - outer, 0.015)
        notes.append("the %d-abreast bench leaves %.0f mm to the lining — this "
                     "is why there is no window sill" % (seats,
                     (wall_x_at(2.181) - outer) * 1000))
    notes.append("seat backs %.0f mm thick, %.0f mm of legroom between rows"
                 % (SEAT_BACK_T * 1000, (pitch_m - donor_depth) * 1000))

    # --- BAY TILING. A relief that does not reach both ends of the bay it is
    # drawn in shows a gap at every repeat. The window bay is the one repeated
    # six times, so it is the one that has to be exact.
    for kind, bay, zd0, zd1 in RELIEF_LOG:
        if bay is not L.WINDOW_BAY:
            continue
        a, b = sorted((bay.z(zd0), bay.z(zd1)))
        if abs(a + bay.units / 2.0) > 1e-6 or abs(b - bay.units / 2.0) > 1e-6:
            problems.append("the %s relief spans %.3f..%.3f of a %g-unit window "
                            "bay — it must reach both seams"
                            % (kind, a, b, bay.units))
    kinds = sorted({k for k, bay, _a, _b in RELIEF_LOG if bay is L.WINDOW_BAY})
    notes.append("window bay carries %s, each spanning the full %g units"
                 % ("/".join(kinds), L.WINDOW_BAY.units))
    return problems, notes


def _body_aperture(index):
    """The body's own aperture `index`, as (donor z_a, z_b, u0, u1)."""
    u0, u1, _v0, _v1 = B.side_panes()[index]
    return (L.z_of_u(u0), L.z_of_u(u1), u0, u1)


def check(model, tex, obj_path):
    problems, notes = [], []
    mats = used_materials(model, tex)

    p, n = interior_clearances()
    problems += p
    notes += n

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

    # NO LINING SLICE MAY CARRY HALF A WINDOW. Same rule and same reason as
    # the body's `panes_in` guard: the elevation is drawn once and cropped per
    # bay, and half an alpha-0 aperture is a gash in the wall, not a window.
    # Checked on the pixels that ship — a hole touching the slice's first or
    # last column IS a window the crop cut — so it holds however the art is
    # drawn. `apertures_in()` is what keeps it true.
    for key, t in sorted(tex.items()):
        if not key.startswith("wall_"):
            continue
        w = len(t.rows[0])
        for col, edge in ((0, "left"), (w - 1, "right")):
            cut = sum(1 for row in t.rows if row[col][3] == 0)
            if cut:
                problems.append(
                    "%s: %d transparent pixel(s) on its %s edge — a lining "
                    "aperture is being cut by a bay boundary"
                    % (t.name, cut, edge))

    # The lining's apertures must sit INSIDE the body's, or the wall cavity —
    # and the door pocket in it — becomes visible round the edge of a window.
    for i, (za, zb, y_top, y_bot) in enumerate(saloon_apertures()):
        eza, ezb, _u0, _u1 = _body_aperture(i)
        if not (min(eza, ezb) < min(za, zb) and max(za, zb) < max(eza, ezb)):
            problems.append("lining aperture %d is not inside the body's "
                            "aperture in z" % i)
        ey_top, ey_bot = B.side_y(B.side_panes()[i][2]), B.side_y(B.side_panes()[i][3])
        if not (y_bot > ey_bot and y_top < ey_top):
            problems.append("lining aperture %d is not inside the body's "
                            "aperture in y" % i)
    notes.append("%d lining apertures, inset %.0f/%.0f mm inside the body's — "
                 "no inner glass pane (the body's already tints; a second "
                 "would double the milkiness)"
                 % (len(saloon_apertures()), LINING_APERTURE_INSET_Z * 1000,
                    LINING_APERTURE_INSET_Y * 1000))

    for name in model.order:
        if not model.groups[name].faces:
            problems.append("group %r is empty" % name)
    for name in SHELL_GROUPS:
        group = model.groups.get(name)
        if group is None:
            problems.append("winding check: no group %r" % name)
            continue
        inward, outward = facing_inboard(group)
        if outward:
            problems.append("group %r has %d face(s) pointing OUT of the "
                            "saloon (%d in)" % (name, outward, inward))

    for name, bay in (("int_cab", L.CAB_BAY), ("int_cab_mini", L.CAB_BAY_MINI)):
        group = model.groups.get(name)
        if group is None:
            problems.append("winding check: no group %r" % name)
            continue
        points = [(sx(x), sy(y), bay.z(z)) for (x, y, z) in CAB_VIEWPOINTS]
        seen, blind = facing_visible(group, points)
        if blind:
            problems.append("group %r has %d face(s) visible from nowhere in "
                            "the cab (%d seen)" % (name, blind, seen))

    tris = sum(len(vs) - 2 for g in model.groups.values() for _m, vs in g.faces)
    notes.append("%d groups, %d faces, %d triangles"
                 % (len(model.order),
                    sum(len(g.faces) for g in model.groups.values()), tris))
    sheets = sorted({t.atlas_name for t in tex.values() if t.atlas is not None})
    notes.append("%d materials (%d cells folded into %d atlas%s: %s)"
                 % (len(mats),
                    sum(1 for t in tex.values() if t.atlas is not None),
                    len(sheets), "" if len(sheets) == 1 else "es",
                    ", ".join("%s %dx%d" % (n, tex["_atlas" if n == "int_atlas"
                                                 else "_cab_atlas"].w,
                                            tex["_atlas" if n == "int_atlas"
                                                else "_cab_atlas"].h)
                              for n in sheets)))

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

    mtl_path = os.path.join(os.path.dirname(obj_path), "m7_interior.mtl")
    if os.path.exists(mtl_path):
        with open(mtl_path) as fh:
            for line in fh:
                if not line.startswith("map_Kd "):
                    continue
                ident = line.split(None, 1)[1].strip()
                if ident.count(".") != 1:
                    problems.append("map_Kd %r must have exactly one dot" % ident)
                if ident != ident.lower():
                    problems.append("map_Kd %r is not lowercase" % ident)
                rel = ident.split(":", 1)[1] if ":" in ident else None
                if rel and not os.path.exists(os.path.join(OUR_NS, rel)):
                    problems.append("map_Kd %r has no file" % ident)

    # Nothing may reach past the car end, poke through the body skin, or sit
    # below the floor where a rider would see it.
    for label, (layout, ends) in sorted(VARIANTS.items()):
        built = assemble(model, placements(layout, ends))
        pts = [v for g in built.groups.values() for _m, vs in g.faces for v in vs]
        zs = [p[2] for p in pts]
        xs = [abs(p[0]) for p in pts]
        drawn = sum(len(vs) - 2 for g in built.groups.values()
                    for _m, vs in g.faces)
        notes.append("%-13s %3d placements, %5d tris drawn, z %+7.1f..%+7.1f "
                     "(car %d), |x| max %.1f"
                     % (label, len(placements(layout, ends)), drawn,
                        min(zs), max(zs), layout.units, max(xs)))
        if max(abs(min(zs)), abs(max(zs))) > layout.units / 2 + 1e-6:
            problems.append("%s: geometry reaches %.1f, past the car end"
                            % (label, max(abs(min(zs)), abs(max(zs)))))
        if max(xs) > sx(1.480) + 1e-6:
            problems.append("%s: |x| reaches %.2f, outside the body skin"
                            % (label, max(xs)))

        # Nothing may stand in a door pocket. The interior has no business
        # near one anyway — this is a tripwire for the day it does.
        worst = 0.0
        for lo_z, hi_z in (layout.door.span(c) for c in layout.door.centres):
            for g in built.groups.values():
                for _m, vs in g.faces:
                    for p in vs:
                        if lo_z - 1e-6 <= p[2] <= hi_z + 1e-6:
                            worst = max(worst, abs(p[0]))
        if worst > DOOR_POCKET_INNER_X - DOOR_POCKET_MARGIN:
            problems.append("%s: |x| reaches %.2f inside a door bay, into the "
                            "sliding leaves' pocket at %.1f"
                            % (label, worst, DOOR_POCKET_INNER_X))
        notes.append("%-13s door bays clear to |x| %.1f (pocket starts %.1f)"
                     % (label, worst, DOOR_POCKET_INNER_X))

    # The cab has to fit inside the body's own narrowing nose, and the body is
    # the authority on where that is.
    shells = body_edges(("end_cab_exterior", "end_cab_exterior_mini"))
    for name, shell in (("int_cab", "end_cab_exterior"),
                        ("int_cab_mini", "end_cab_exterior_mini")):
        group = model.groups.get(name)
        edges = shells.get(shell)
        if group is None or not edges:
            notes.append("no body shell %r to check %r against" % (shell, name))
            continue
        tight_x = tight_z = None
        for _m, vs in group.faces:
            for (x, y, z, _u, _v) in vs:
                limit = _cross(edges, 2, z, 0)          # widest |x| at this z
                if limit is not None:
                    tight_x = min(limit - abs(x),
                                  tight_x if tight_x is not None else 1e9)
                front = _cross(edges, 0, abs(x), 2)     # nose z at this |x|
                if front is not None:
                    tight_z = min(z - front,
                                  tight_z if tight_z is not None else 1e9)
        if tight_x is not None and tight_x < 0:
            problems.append("%s: pokes %.2f units through the body side"
                            % (name, -tight_x))
        if tight_z is not None and tight_z < 0:
            problems.append("%s: pokes %.2f units through the nose"
                            % (name, -tight_z))
        notes.append("%-13s clears the body shell by %.2f units sideways, "
                     "%.2f forward" % (name, tight_x or 0.0, tight_z or 0.0))
    return problems, notes


# -------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true",
                    help="run the self-checks against the written model")
    ap.add_argument("--assemble", metavar="DIR",
                    help="bake every bay at its layout position into whole-car "
                         "preview OBJs in DIR, for tools/render_obj.py")
    args = ap.parse_args()

    model, tex = build_model()
    mats = used_materials(model, tex)
    obj_path = os.path.join(MODEL_DIR, "m7_interior.obj")

    if args.assemble:
        os.makedirs(args.assemble, exist_ok=True)
        write_textures(mats, args.assemble)
        for label, (layout, ends) in sorted(VARIANTS.items()):
            built = assemble(model, placements(layout, ends))
            path = os.path.join(args.assemble, "m7_int_%s.obj" % label)
            nv, nf = write_obj(built, path, "m7_int_%s.mtl" % label, mats,
                               lambda t: t.name + ".png")
            print("%-28s %5d verts %5d faces" % (os.path.basename(path), nv, nf))
        # The bays on their own, for looking at one at a time.
        path = os.path.join(args.assemble, "m7_int_bays.obj")
        nv, nf = write_obj(model, path, "m7_int_bays.mtl", mats,
                           lambda t: t.name + ".png")
        print("%-28s %5d verts %5d faces" % (os.path.basename(path), nv, nf))
        return 0

    if not args.check:
        nv, nf = write_obj(model, obj_path, "m7_interior.mtl", mats,
                           lambda t: t.map_kd)
        written = write_textures(mats, prune=True)
        print("m7_interior.obj: %d groups, %d verts, %d faces"
              % (len(model.order), nv, nf))
        print("m7_interior.mtl: %d materials" % len(mats))
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
    sys.exit(main())
