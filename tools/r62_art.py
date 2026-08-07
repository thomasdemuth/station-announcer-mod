#!/usr/bin/env python3
"""Drawn pixel art for the NYCT R62 exterior — palette, livery rules, motifs.

    python3 tools/r62_art.py            # write a palette/motif contact sheet

Shared by `tools/convert_openbve_r62.py` (the shell) and
`tools/gen_r62_doors.py` (the sliding leaves), so a leaf and the bodyside it
slides into come out of ONE palette at ONE set of livery heights. That sharing
is the point: the two polished belt rails run straight across every door
opening, and if the two generators carried their own copies of where they sit,
the rails would step at each aperture.

WHERE THE RASTER KIT COMES FROM
-------------------------------
`tools/pixel_kit.py` — the car-agnostic primitives, the 3x5 font, and the
whole MARK / FIND / CUT glazing mechanism (`glaze`, `glazing_rects`,
`open_glazing`, `color_key`), shared with `m7_art.py` and any future car.
(The kit originally lived inside `m7_art.py` and was imported from there
while the M7 awaited its in-game verdict; it was extracted on 2026-07-29.)
This module and the generators import from `pixel_kit` directly — nothing
here comes from, or is re-exported for, another car.

WHAT THE R62 ACTUALLY LOOKS LIKE (mid-1990s to early-2000s, post-graffiti)
--------------------------------------------------------------------------
Read off the donor's own photographic maps, which are exactly that era:

  * BARE BRUSHED STAINLESS, end to end. There is NO black window band — that is
    an R142/R143-and-later thing — and no painted livery of any kind. The car is
    one metal, shaded only by what the panels do to the light.
  * TWO POLISHED BELT RAILS run the full length below the windows, at donor y
    ~1.90 and ~1.47. They are the only bright things on the side and they are
    what makes the car read as an R62 rather than as a grey box.
  * NO MODELLED CORRUGATION AND NO TUMBLEHOME. The bodyside is a flat plane at
    |x| = 1.3109 and every rib, rail and rivet is paint. That is easier than the
    M7 in every way.
  * Windows are big rounded rectangles in thick POLISHED frames with a rivet
    run, and each has a horizontal muntin near the top (the hopper vent).
  * Doors are bi-parting, recessed 46 mm, each leaf carrying one tall rounded
    window.
  * A black band under the sill, and a dark underframe below that.

THE ONE RULE THAT MAKES THE BAY SYSTEM WORK
-------------------------------------------
⭐ **THE ELEVATION MAY ONLY SHADE VERTICALLY.** Every band this module paints on
the bodyside runs the FULL WIDTH of the canvas, so every column is
bit-identical and a bay cropped out of the middle tiles with its neighbours at
any pitch. Content that varies along the car (a window, a door, the sign
housing) is bay-local content drawn at its own donor z, not field shading.

That is the M7's rule, and before it the zebra boards' — which cost three
in-game iterations to learn. The R62 gets it for free because its prototype
really is shaded that way: longitudinal rails on a flat sheet.

UNITS
-----
Livery landmarks are in DONOR METRES above the rail, the one frame both
generators can map into their own texture space. Nothing here is in pixels;
every routine takes a canvas and a `row_of_y` and computes its own.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pngtool
from pixel_kit import (blit, canvas, disc, glaze, hband, hline, rect, ring,
                       rrect, text)


# =====================================================================
# PALETTE
# =====================================================================
#
# Flat, named and small. Every colour on this car comes from here. The R62 is
# ONE material — unpainted 301 stainless — so the palette is a tonal ladder
# rather than a set of hues, and the few non-grey entries are lamps and glass.

# --- brushed stainless, the bodyside's ladder (light at the top, as a vertical
# surface under a sky is).
STEEL_HI = (198, 201, 205)          # the cant rail, just under the roof
STEEL = (176, 179, 184)             # the main field, around the windows
STEEL_MID = (163, 166, 171)         # below the belt rails
STEEL_LO = (148, 151, 157)          # the lowest panel, above the sill
STEEL_DEEP = (128, 131, 137)        # in shadow: recesses, door pockets
SEAM = (112, 115, 121)              # a panel joint, one pixel

# --- polished stainless. The belt rails and the window frames are a DIFFERENT
# finish from the field, not just a lighter tone of it, so they get their own
# three steps with a hard specular top edge.
POLISH_HOT = (247, 249, 251)
POLISH = (228, 231, 235)
POLISH_LO = (176, 179, 184)
RIVET = (196, 199, 203)
RIVET_LO = (140, 143, 149)

# --- black. The under-sill band, the sign boxes, the door reveals.
BLACK_BAND = (26, 27, 29)
BLACK_SOFT = (44, 46, 49)
GASKET = (38, 40, 44)               # a window's rubber, inside its frame
GASKET_HI = (62, 65, 70)

# --- underframe and roof
UNDER = (86, 89, 94)
UNDER_LO = (58, 60, 64)
ROOF_STEEL = (168, 171, 175)
ROOF_RIB_HI = (190, 193, 197)
ROOF_RIB_LO = (146, 149, 154)
VENT_FRAME = (152, 155, 160)
VENT_DARK = (48, 44, 40)
VENT_LOUVRE = (96, 90, 84)

# --- lamps
LAMP_LENS = (255, 248, 224)
LAMP_LENS_HOT = (255, 255, 248)
LAMP_RED = (198, 44, 38)
LAMP_RED_HOT = (240, 92, 80)
LAMP_RIM = (104, 107, 112)
LAMP_WELL = (34, 35, 38)

# --- the sign boxes. Both the side rollsign and the front roundel are black
# housings with a lit field behind glass, and the UNLIT field has to read as a
# dark screen rather than as a hole.
SIGN_CASE = (22, 22, 24)
SIGN_CASE_HI = (52, 53, 56)
SIGN_FIELD = (10, 10, 12)

# --- glass. Alpha 76/255 is the M7's, verbatim, so the two custom trains'
# glazing reads as one material in a yard.
GLASS_RGBA = (196, 212, 222, 76)


# =====================================================================
# THE SALOON — the interior palette
# =====================================================================
#
# It lives HERE, beside the exterior's, for the same reason the belt-rail
# heights do: more than one generator draws the inside of this car.
# `convert_r62_interior.py` paints the lining, the ceiling and the seats, and
# `gen_r62_doors.py` paints the SALOON FACE of every sliding leaf — and a
# closed door has to read as part of the wall it is set into, exactly as its
# outboard face has to read as part of the bodyside.
#
# ⭐ THE R62's SALOON IS CREAM, NOT THE BROWN ITS PHOTOGRAPHS SAMPLE AS. The
# donor's interior maps are heavily under-exposed — its wall textures come out
# around #403020 — so sampling them would have produced a brown car. The user's
# reference photos and the real specimen are cream/eggshell walls and ceiling
# with ORANGE and YELLOW-TAN bucket seats, and that is what these are.
#
# Flat MTR house tones: a base, one highlight and one shade per material, and
# NO baked lighting. INTERIOR parts render CUTOUT_BRIGHT — fullbright — so a
# painted gradient reads as dirt rather than as light, and what little shading
# there is is MATERIAL shading.

INT_CREAM_HI = (238, 234, 222)      # the band under the ceiling
INT_CREAM = (226, 221, 206)         # the lining itself
INT_CREAM_LO = (206, 200, 184)      # below the windows, behind the seats
INT_CREAM_SEAM = (176, 170, 154)    # a 1 px joint between wall panels
INT_SKIRT = (138, 133, 122)         # the skirting, mostly behind the seats

INT_CEILING = (242, 240, 233)       # the crown
INT_CEILING_LO = (224, 221, 212)    # the soffit
INT_CEILING_SEAM = (198, 194, 184)
INT_LAMP = (255, 250, 232)          # the fluorescent strip in the cove
INT_LAMP_EDGE = (236, 228, 200)

INT_BAND = (232, 228, 216)          # the ad / strip-map band
INT_BAND_LIP = (198, 193, 180)      # its downstand lip
INT_AD_INK = (150, 146, 138)        # the ad cards' generic type
INT_AD_TINT = (206, 214, 220)       # ...and their generic block of colour

INT_MAP_CASE = (26, 26, 28)         # the strip map's own frame
INT_MAP_FACE = (238, 236, 230)      # the map card
INT_MAP_LINE = (36, 40, 48)         # the route line and its station rings
INT_MAP_TICK = (72, 78, 88)         # tick marks and interchange blobs
INT_MAP_DOT = (250, 250, 248)       # the light centre of a station ring
INT_MAP_FIELD = (10, 10, 12)        # a dark well an MTR display lights up
INT_MAP_FIELD_HI = (56, 57, 60)

INT_FLOOR = (58, 55, 51)            # the speckled saloon floor
INT_FLOOR_HI = (78, 74, 68)
INT_FLOOR_LO = (42, 40, 37)
INT_THRESHOLD = (96, 99, 104)       # the doorway's own plate

INT_SEAT_ORANGE = (198, 84, 40)     # the donor's "SeatRed" — a red-orange
INT_SEAT_ORANGE_HI = (226, 118, 62)
INT_SEAT_ORANGE_LO = (150, 56, 24)
INT_SEAT_TAN = (206, 150, 62)       # the donor's "SeatYellow" — a yellow-tan
INT_SEAT_TAN_HI = (232, 180, 96)
INT_SEAT_TAN_LO = (158, 108, 38)
INT_SEAT_SHADOW = (30, 28, 26)      # the well under a cantilevered bench

INT_CAB_WALL = (52, 56, 60)         # the half-cab's partition, saloon side
INT_CAB_WALL_HI = (74, 79, 84)
INT_CAB_DARK = (30, 32, 35)         # inside the cab
INT_CAB_DESK = (24, 25, 28)
INT_CAB_GAUGE = (218, 170, 62)
INT_CAB_SCREEN = (56, 132, 120)


# =====================================================================
# LIVERY LANDMARKS — donor metres above the rail
# =====================================================================
#
# Straight off the donor's own key heights. The window band's edges are here
# because the two belt rails are positioned RELATIVE to the glass, so if the
# glazing ever moves the rails follow rather than crossing it.

ROOF_EAVES_Y = 3.1074               # the top of the side; the roof takes over
CANT_RAIL_Y = 2.9260                # the panel joint above the windows
WINDOW_TOP_Y = 2.9260
WINDOW_BOT_Y = 2.1160
# ⭐ THERE ARE EXACTLY TWO POLISHED RAILS, NOT THREE. The donor's inventory
# lists a "belt_rail_upper" at 2.040..2.116, immediately under the glass, and
# the first cut drew it polished like the other two. Measured off the donor's
# own photograph — column tones at y 1.905 and 1.471 are bright and the band at
# 2.08 is not — it is a PANEL STEP, a joint between the window sheet and the
# one below it, and drawing it bright put a third rail on a car that has two.
# It stays as a named landmark because the seam is real; only its finish
# changed.
BELT_UPPER_Y = (2.0320, 2.1160)     # a panel step under the glass, NOT a rail
BELT_LOWER_Y = (1.8800, 1.9310)     # the upper polished rail
BELT_THIRD_Y = (1.4400, 1.4900)     # the lower polished rail
SILL_Y = 1.1100                     # the floor line
BLACK_BAND_Y = (0.9477, 1.1100)     # the black band under the sill

# ⭐ THIS IS THE ELEVATION'S OWN BOTTOM EDGE, AND THE BODYSIDE MUST STOP HERE.
# The donor's uv fit puts v = 1 at y 0.9477 and the drawn elevation inherits
# that, so a wall quad taken any lower samples v > 1. That is not a harmless
# overshoot: `wrap_face` sees a face spanning more than one repeat, falls back
# to per-vertex wrapping, and 1.004 becomes 0.004 — so the whole panel samples a
# single row of the cant rail and the car comes out featureless grey. It cost one
# render cycle to spot, and the bodyside looked *plausible* the whole time.
# Anything below this line is underframe, drawn by its own geometry.
SKIN_BOTTOM_Y = 0.9477


def livery_bands():
    """The bodyside's horizontal tone bands, top to bottom, in donor metres.

    Returns [(y_top, y_bottom, colour)]. Bands are FULL WIDTH by construction —
    that is the rule in the module docstring — so a caller only ever needs a y
    range and a colour, and cannot accidentally shade along the car.
    """
    return [
        (ROOF_EAVES_Y, CANT_RAIL_Y, STEEL_HI),
        (CANT_RAIL_Y, WINDOW_BOT_Y, STEEL),
        (WINDOW_BOT_Y, BELT_UPPER_Y[0], STEEL),
        (BELT_UPPER_Y[0], BELT_LOWER_Y[1], STEEL_MID),
        (BELT_LOWER_Y[1], BELT_LOWER_Y[0], POLISH),
        (BELT_LOWER_Y[0], BELT_THIRD_Y[1], STEEL_MID),
        (BELT_THIRD_Y[1], BELT_THIRD_Y[0], POLISH),
        (BELT_THIRD_Y[0], SILL_Y, STEEL_LO),
        (SILL_Y, SKIN_BOTTOM_Y, BLACK_BAND),
    ]


def livery_seams():
    """One-pixel lines, as (y, colour). Same frame as `livery_bands`.

    The specular top edge on each polished rail is what stops it reading as a
    stripe of paint: a real rail catches the sky along its upper radius and goes
    dark underneath, and one pixel each way is enough to say so at this density.
    """
    return [
        (ROOF_EAVES_Y, SEAM),
        (CANT_RAIL_Y, SEAM),
        (BELT_UPPER_Y[0], SEAM),
        (BELT_LOWER_Y[1], POLISH_HOT),
        (BELT_LOWER_Y[0], POLISH_LO),
        (BELT_THIRD_Y[1], POLISH_HOT),
        (BELT_THIRD_Y[0], POLISH_LO),
        (SILL_Y, SEAM),
    ]


def check_livery(window_top_y, window_bot_y):
    """The rails have to clear the glass, or bands overlap silently."""
    assert window_bot_y >= BELT_UPPER_Y[1] - 1e-6, (
        "the upper belt rail at y %.3f runs into the glazing at y %.3f"
        % (BELT_UPPER_Y[1], window_bot_y))
    assert window_top_y <= CANT_RAIL_Y + 1e-6, (
        "the cant-rail joint at y %.3f runs into the glazing at y %.3f"
        % (CANT_RAIL_Y, window_top_y))


def stainless_field(rows, row_of_y):
    """Paint the bodyside's tone bands and joint lines onto a canvas.

    `row_of_y` maps donor metres to a canvas row, so one routine serves the
    768 x 112 body elevation and a 40 x 96 door leaf even though their vertical
    mappings have completely different scales.
    """
    for (y_top, y_bot, colour) in livery_bands():
        hband(rows, row_of_y(y_top), row_of_y(y_bot), colour)
    for (y, colour) in livery_seams():
        hline(rows, row_of_y(y), colour)


# =====================================================================
# MOTIFS
# =====================================================================

def rivet_run(rows, x0, y, x1, colour=RIVET, pitch=5):
    """A horizontal row of rivet heads. Used along window frames."""
    x = int(round(x0))
    while x < x1:
        rect(rows, x, y, x + 1, y + 1, colour)
        x += pitch


def pane(rows, x0, y0, x1, y1, radius=4, frame=3, muntin=None):
    """An R62 passenger window: polished frame, gasket, marked glass.

    **x0..x1 / y0..y1 is THE GLASS**, and the frame is drawn OUTSIDE it — so a
    pane positioned straight from a donor aperture box cuts its hole exactly
    where the prototype's is, instead of a few pixels in from it.

    Four nested rounded rects: a bright outer frame with a lit top edge, a rivet
    run along it, a dark gasket, then the glass. The frame is the widest thing
    on this car's side and is what carries the "polished trim on a brushed
    sheet" reading that the R62 lives or dies by.

    `muntin` is a row for the horizontal glazing bar near the top — the R62's
    hopper vent — drawn INSIDE the glass at full alpha so `open_glazing` leaves
    it behind as a real bar across the aperture.
    """
    rrect(rows, x0 - frame, y0 - frame, x1 + frame, y1 + frame,
          radius + frame, POLISH_LO)
    rrect(rows, x0 - frame + 1, y0 - frame + 1, x1 + frame - 1, y1 + frame - 1,
          radius + frame - 1, POLISH)
    # the specular top edge, and a rivet run just inside the frame's outer face
    rect(rows, x0 - frame + 2, y0 - frame, x1 + frame - 2, y0 - frame + 1,
         POLISH_HOT)
    rivet_run(rows, x0 - frame + 2, int(round(y0 - frame + 1)), x1 + frame - 2)
    rivet_run(rows, x0 - frame + 2, int(round(y1 + frame - 2)), x1 + frame - 2,
              RIVET_LO)
    rrect(rows, x0 - 1, y0 - 1, x1 + 1, y1 + 1, radius + 1, GASKET)
    glaze(rows, x0, y0, x1, y1, radius, GLASS_RGBA)
    if muntin is not None:
        rect(rows, x0, muntin, x1, muntin + 2, GASKET_HI)
        rect(rows, x0, muntin, x1, muntin + 1, POLISH_LO)


def sign_housing(w, h, fields):
    """The side rollsign's face: a black case with recessed lit fields.

    `fields` is [(x0, y0, x1, y1)] in canvas pixels — the bullet and the two
    text lines. Each becomes a dark well with a lit lip, which is what an
    unlit sign looks like in a yard; the amber comes from MTR's DISPLAY text
    drawn on top, so nothing here may be bright.
    """
    rows = canvas(w, h, SIGN_CASE)
    rrect(rows, 0, 0, w, h, 3, SIGN_CASE_HI)
    rrect(rows, 1, 1, w - 1, h - 1, 3, SIGN_CASE)
    for (x0, y0, x1, y1) in fields:
        rect(rows, x0 - 1, y0 - 1, x1 + 1, y1 + 1, SIGN_CASE_HI)
        rect(rows, x0, y0, x1, y1, SIGN_FIELD)
    return rows


def roundel_box(rows, x0, y0, x1, y1):
    """The front route-sign box: a black bezel around a dark round well.

    Drawn as a SQUARE case with a circular well, because the thing that lands
    in it is MTR's `ROUTE_COLOR_ROUNDED` disc plus a `ROUTE_NUMBER` glyph —
    the NYC bullet. The well is round so that an unlit sign still reads as a
    bullet rather than as a black rectangle.
    """
    rrect(rows, x0, y0, x1, y1, 3, SIGN_CASE_HI)
    rrect(rows, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 3, SIGN_CASE)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    r = min(x1 - x0, y1 - y0) / 2.0 - 2
    disc(rows, cx, cy, r, SIGN_CASE_HI)
    disc(rows, cx, cy, r - 1, SIGN_FIELD)


def lamp_well(rows, cx, cy, r, red=False):
    """One lamp, PAINTED UNLIT: a bolted chrome ring round a dead lens.

    ⭐ ONE PER SIDE PER ROW. The first cut drew three, because the donor's
    inventory recorded builder 11's three vertex COLUMNS as three lamp centres;
    they are the columns of a single faceted disc. Three painted lenses per row
    per side is what the user saw as "the colored lens plus two spurious dark
    lens copies beside it".

    The lit lens is separate GEOMETRY that sits on top of this one (its own
    group, its own PartCondition, and only ever drawn when the lamp is actually
    burning), so what is painted here is the lamp OFF: a dark glass with a hint
    of its own colour and the chrome bezel the donor's Headlight1.png shows.
    """
    disc(rows, cx, cy, r, LAMP_RIM)
    ring(rows, cx, cy, r, r - 1.0, POLISH_LO)
    disc(rows, cx, cy, max(1.0, r - 2.2), LAMP_WELL)
    # a dead lens still carries its own tint, a couple of steps off black
    dead = ((LAMP_RED[0] // 3 + 12, LAMP_RED[1] // 4, LAMP_RED[2] // 4)
            if red else (58, 56, 48))
    disc(rows, cx, cy, max(1.0, r - 3.2), dead)
    # four bolts round the bezel, like the donor's photograph
    for dx, dy in ((-0.72, -0.72), (0.72, -0.72), (-0.72, 0.72), (0.72, 0.72)):
        rect(rows, cx + dx * (r - 1.4) - 0.5, cy + dy * (r - 1.4) - 0.5,
             cx + dx * (r - 1.4) + 1.0, cy + dy * (r - 1.4) + 1.0, RIVET)


def lamp_lens(size=32, red=False):
    """The LIT lens: a round glowing disc on a fully transparent field.

    ⭐ ROUND, NOT SQUARE, AND THAT IS THE POINT. The lit lens rides its own quad
    at CUTOUT_GLOWING, so alpha 0 is a real hole — a square texture on a square
    quad is why the first build's headlights read as red and cream RECTANGLES
    parked beside their own bezels. The disc is drawn to the quad's own edge,
    so the quad's size IS the lamp's diameter.
    """
    base = LAMP_RED if red else LAMP_LENS
    hot = LAMP_RED_HOT if red else LAMP_LENS_HOT
    rows = canvas(size, size, (0, 0, 0, 0))
    c = size / 2.0
    disc(rows, c, c, c, LAMP_RIM)
    disc(rows, c, c, c - size / 16.0, base)
    disc(rows, c - size * 0.09, c - size * 0.09, c * 0.52, hot)
    return rows


def chain_bar(length=16, thick=8):
    """The texture a safety-chain bar wears: dark links with a lit top edge.

    Runs ALONG u so a bar of any length samples the same pattern, and shades
    only with v — the bar is a square section and its four faces share one
    texture, so anything that reads as a column on the top face would read as a
    row on the side.
    """
    rows = canvas(length, thick, (34, 34, 36, 255))
    rect(rows, 0, 0, length, 1, (96, 98, 102, 255))
    rect(rows, 0, thick - 1, length, thick, (18, 18, 20, 255))
    for x in range(0, length, 4):
        rect(rows, x, 1, x + 1, thick - 1, (64, 66, 70, 255))
    return rows


def corner_strip(height, row_of_y, width=24, ribs=(4, 11, 18)):
    """The end's CORNER treatment: the side's tone ladder, ribbed, no content.

    ⭐ NOTHING HERE MAY VARY ALONG THE CAR, AND THAT IS A CORRECTNESS RULE, NOT
    A STYLE ONE. This texture wears the outermost facets of the nose and of the
    roof roll — the surfaces that turn hard onto the bodyside, are two or three
    pixels wide seen head on, and whose `mask_u` runs a hair outside [0, 1]. Any
    content here would be smeared across that turn; window and lamp fragments
    on exactly those facets are what the user reported. Because every band is
    full width, the strip cannot show a window no matter how its u is sampled,
    and `--check` proves the mask never reaches these facets at all.

    `row_of_y` is the END mask's own v map, so the strip's bands line up
    exactly with the mask's at the seam and the corner post reads continuous.
    """
    rows = canvas(width, height, STEEL_MID)
    stainless_field(rows, row_of_y)
    # the corner is in its own shade — it faces away from the sky
    for y in range(height):
        r, g, b, a = rows[y][0]
        rect(rows, 0, y, width, y + 1,
             (max(0, r - 10), max(0, g - 10), max(0, b - 10), a))
    # the roof band, above the eaves, so the roll continues the dome
    rect(rows, 0, 0, width, max(1, int(round(row_of_y(ROOF_EAVES_Y)))),
         ROOF_STEEL)
    hline(rows, row_of_y(ROOF_EAVES_Y), SEAM)
    # ...and the light-grey rib lines that give the corner its gauge
    for x in ribs:
        rect(rows, x, 0, x + 1, height, POLISH_LO)
        rect(rows, x + 1, 0, x + 2, height, SEAM)
    return rows


def brushed(rows, y0, y1, step=7):
    """A whisper of vertical tonal variation over a band, for brushed metal.

    ⭐ VARIES WITH Y ONLY. A per-pixel or per-column grain would break the
    tiling rule the whole bay system rests on, so "brushed" here means faint
    horizontal striations — which is also what a real brushed-then-rolled sheet
    shows, the grain running along the car.
    """
    y = int(round(y0))
    end = int(round(y1))
    while y < end:
        row = rows[y] if 0 <= y < len(rows) else None
        if row is not None:
            r, g, b, a = row[0]
            shade = (max(0, r - 5), max(0, g - 5), max(0, b - 5), a)
            rect(rows, 0, y, len(rows[0]), y + 1, shade)
        y += step


# =====================================================================
# CONTACT SHEET
# =====================================================================

def _contact_sheet(path):
    swatches = [(n, v) for n, v in sorted(globals().items())
                if n.isupper() and isinstance(v, tuple) and len(v) in (3, 4)
                and all(isinstance(c, int) for c in v)]
    cols = 6
    cw, ch = 64, 24
    rowsn = (len(swatches) + cols - 1) // cols
    sheet = canvas(cols * cw, rowsn * ch + 120, (24, 26, 30))
    for i, (name, value) in enumerate(swatches):
        x, y = (i % cols) * cw, (i // cols) * ch
        rect(sheet, x + 1, y + 1, x + cw - 1, y + ch - 9, value)
        text(sheet, x + 2, y + ch - 8, name[:15], (232, 234, 238), 1)
    base = rowsn * ch + 6
    rect(sheet, 4, base, 200, base + 100, STEEL)
    pane(sheet, 20, base + 10, 120, base + 60, radius=6, muntin=base + 18)
    blit(sheet, 210, base, sign_housing(120, 34,
                                        [(4, 4, 26, 30), (30, 4, 114, 15),
                                         (30, 18, 114, 30)]))
    roundel_box(sheet, 210, base + 42, 254, base + 86)
    for i, x in enumerate((285, 320)):
        lamp_well(sheet, x, base + 20, 13, red=(i == 0))
    blit(sheet, 272, base + 42, lamp_lens(26, red=True))
    blit(sheet, 306, base + 42, lamp_lens(26))
    blit(sheet, 344, base, chain_bar(48, 10))
    blit(sheet, 344, base + 16,
         corner_strip(80, lambda y: (3.6229 - y) / 2.5145 * 80))
    pngtool.write_png(path, sheet)
    return len(swatches)


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "r62_art_contact.png")
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else out)
    n = _contact_sheet(out)
    print("r62_art: %d palette entries -> %s" % (n, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
