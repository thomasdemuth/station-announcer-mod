#!/usr/bin/env python3
"""Drawn pixel art for the LIRR M7 exterior — palette, livery rules, motifs.

    python3 tools/m7_art.py            # write a palette/primitive contact sheet

Shared by `tools/convert_openbve_m7.py` (the body shell) and
`tools/gen_m7_doors.py` (the sliding leaves), so a leaf and the bodyside it
slides into are drawn from ONE palette, at ONE set of livery heights. That
sharing is the whole point of the module: the blue belt stripe crosses the
door opening, and if the two generators each carried their own copy of where
it sits, the stripe would step at every aperture.

WHY THIS EXISTS AT ALL (2026-07-28 art pass)
--------------------------------------------
The first M7 build sampled the openBVE donor's photographic textures directly.
That reads as a photo pasted onto a Minecraft model: JPEG mush, per-pixel
noise, tonal steps at every bay seam, and detail far below the resolution a
player ever sees. Everything visible on the car is now DRAWN here instead —
flat colours, hard edges, MTR's own R179/R211 house style at ~40-48 px per
block. The donor is still the authority on *where* things are (window
positions, the cab's uv map, the door aperture), never on what they look like.

THE THREE RULES THIS MODULE ENFORCES
------------------------------------
1. **Shading varies VERTICALLY only.** The bodyside is drawn as horizontal
   tone bands running the full length of the canvas, so every column is
   bit-identical. That is what lets a 30-unit window bay be cropped out of the
   middle of the elevation and repeated six times with no seam — the same
   property the donor's synthetic saloon wall had, and the same lesson the
   mod's zebra boards cost three in-game iterations to learn. Anything that
   varies along the car (a window, a decal, the cab) is bay-local content, not
   field shading.
2. **No photographic noise.** Two or three flat greys with hard boundaries
   read as brushed stainless at Minecraft's texel density; a per-pixel gradient
   reads as dirt.
3. **Glazing is MARKED, not cut.** Every pane this module draws is filled at
   `GLAZE_ALPHA` (156 — the donor's own convention for the pixels openBVE was
   told to blend). Downstream, `glazing_rects()` finds those regions and turns
   them into real geometry, and `open_glazing()` then zeroes them to cut the
   aperture. Marking rather than hand-typing rectangles is what keeps the glass
   quads on the holes: art and geometry come out of the same pixels. It works
   identically on donor art and on art drawn here.

The car-agnostic RASTER KIT that used to live here — the drawing primitives,
the 3x5 font, and the whole MARK / FIND / CUT glazing mechanism — is
`tools/pixel_kit.py` now, shared with `r62_art.py` and any future car. This
module keeps only what is M7: the palette, the livery tables, the donor
access, and the LIRR motifs.

UNITS
-----
Livery landmarks are in DONOR METRES above the rail — the one frame both
generators can map into their own texture space (the bodyside elevation maps
y through `SIDE_PROFILE`'s v table, a door leaf maps it through `LEAF_PTS`).
Nothing here is in pixels; every drawing routine takes a canvas and computes
its own.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pngtool
from pixel_kit import (GLYPH_H, blit, canvas, disc, glaze, glazing_rects,
                       hband, hline, line, rect, rgba, rrect, text,
                       text_centred, text_width)

DONOR = ("/Users/thomasdemuth/openBVE/Train/LIRR & MNCR Bombardier M7 EMU Pack "
         "V1.1/Long Island Rail Road M7/2 Car M7/Model")


# =====================================================================
# PALETTE
# =====================================================================
#
# Flat, named, and small. Every colour on the car comes from this block — if a
# tone is not here it does not belong on the model. Values are RGB triples;
# the raster kit accepts RGB or RGBA and defaults alpha to 255.

# --- stainless steel bodyside. Five steps, used as horizontal bands.
STEEL_HI = (208, 212, 217)          # cant rail / above the windows
STEEL = (186, 191, 197)             # the main field
STEEL_MID = (168, 173, 180)         # below the belt
STEEL_LO = (148, 153, 161)          # the tumblehome, already shaded by normals
STEEL_DARK = (124, 129, 137)        # skirt / under the sill
SEAM = (104, 109, 117)              # panel joint lines, one pixel

# --- window band
GASKET = (34, 36, 41)               # the rubber surround a pane sits in
GASKET_HI = (58, 61, 68)            # its outboard chamfer, one pixel

# --- LIRR identity
LIRR_BLUE = (0, 61, 122)
LIRR_BLUE_HI = (22, 90, 160)
LIRR_BLUE_LO = (0, 40, 84)

# --- decals
SAFETY_YELLOW = (233, 188, 40)
SAFETY_YELLOW_LO = (176, 138, 20)
DECAL_DARK = (34, 31, 22)
DECAL_WHITE = (232, 234, 238)

# --- cab
CAB_BLACK = (30, 32, 36)            # the mask
CAB_BLACK_HI = (48, 51, 57)         # its edge highlight
CAB_ORANGE = (216, 138, 24)
CAB_ORANGE_HI = (242, 170, 50)
CAB_ORANGE_LO = (166, 98, 12)
WIPER = (20, 21, 24)
ANTICLIMBER = (120, 124, 131)
ANTICLIMBER_LO = (80, 84, 91)

# --- lamps
LAMP_LENS = (255, 247, 218)
LAMP_LENS_HOT = (255, 255, 246)
LAMP_RED = (206, 42, 36)
LAMP_RIM = (92, 96, 103)

# --- US flag
FLAG_RED = (176, 38, 54)
FLAG_WHITE = (238, 239, 243)
FLAG_BLUE = (36, 54, 116)

# --- roof
ROOF_STEEL = (152, 157, 163)
ROOF_RIB_HI = (172, 177, 183)
ROOF_RIB_LO = (128, 133, 140)
HVAC = (140, 145, 152)
HVAC_LO = (96, 100, 107)
HVAC_GRILLE = (64, 68, 74)

# --- gangway end
END_PANEL = (176, 181, 188)
END_PANEL_LO = (150, 155, 162)
END_POST = (196, 201, 207)
DIAPHRAGM = (44, 46, 51)

# --- glass. Shared VERBATIM with tools/convert_m7_interior.py's partition
# glazing so the cab reads as one material through the windscreen and through
# the bulkhead behind it. Alpha 76/255 keeps what is behind clearly legible
# while still giving the pane a sheen.
GLASS_RGBA = (198, 214, 224, 76)

# The GLAZE_ALPHA marker (156) and the glazing mechanism live in
# tools/pixel_kit.py — see rule 3 in the module docstring.


# =====================================================================
# LIVERY LANDMARKS — donor metres above the rail
# =====================================================================
#
# The bodyside's tone bands and the blue belt stripe. Both generators map
# these into their own texture space, which is what keeps the stripe level
# across a door opening. The window band itself is NOT here: it is derived
# from the donor's own glazing (see `side_glazing()`), because the windows are
# where the prototype put them and nothing about them should be typed.

CANT_RAIL_Y = 3.400                 # top of the side (the roof turns over)
UPPER_SEAM_Y = 3.100                # panel joint above the windows. Clears the
                                    # cab access door's window, whose head is
                                    # the highest glass on the car at y 2.981.
STRIPE_TOP_Y = 2.060                # the blue belt stripe, just under the glass
STRIPE_BOT_Y = 1.850
LOWER_SEAM_Y = 1.560                # panel joint below the belt
DOOR_SILL_Y = 1.295                 # the floor line
SKIRT_Y = 1.150                     # below this the side is skirt, not body


def livery_bands(window_top_y, window_bot_y):
    """The bodyside's horizontal tone bands, top to bottom, in donor metres.

    Returns [(y_top, y_bottom, colour)]. Bands are FULL WIDTH by construction
    — that is rule 1 — so the caller only ever needs a y range and a colour.
    The two window heights come in from the donor's own glazing so the band
    edges track the glass instead of being typed against it.
    """
    return [
        (CANT_RAIL_Y, UPPER_SEAM_Y, STEEL_HI),
        (UPPER_SEAM_Y, window_top_y + 0.06, STEEL),
        (window_top_y + 0.06, window_bot_y - 0.06, STEEL),
        (window_bot_y - 0.06, STRIPE_TOP_Y, STEEL_MID),
        (STRIPE_TOP_Y, STRIPE_BOT_Y, LIRR_BLUE),
        (STRIPE_BOT_Y, LOWER_SEAM_Y, STEEL_MID),
        (LOWER_SEAM_Y, DOOR_SILL_Y, STEEL_LO),
        (DOOR_SILL_Y, SKIRT_Y, STEEL_DARK),
        (SKIRT_Y, 0.0, STEEL_DARK),
    ]


def livery_seams(window_bot_y):
    """One-pixel joint lines, as (y, colour). Same frame as `livery_bands`."""
    return [
        (UPPER_SEAM_Y, SEAM),
        (STRIPE_TOP_Y, LIRR_BLUE_HI),
        (STRIPE_BOT_Y, LIRR_BLUE_LO),
        (LOWER_SEAM_Y, SEAM),
        (DOOR_SILL_Y, SEAM),
    ]


def check_livery(window_top_y, window_bot_y):
    """The stripe has to clear the glass, or the bands overlap silently."""
    assert window_bot_y > STRIPE_TOP_Y + 0.02, (
        "the blue belt stripe at y %.3f runs into the saloon glazing, which "
        "the donor puts at y %.3f" % (STRIPE_TOP_Y, window_bot_y))
    assert window_top_y < UPPER_SEAM_Y, (
        "the upper panel seam at y %.3f runs into the glazing at y %.3f"
        % (UPPER_SEAM_Y, window_top_y))


# =====================================================================
# DONOR ACCESS — positions only, never appearance
# =====================================================================

_DONOR_CACHE = {}


def donor_rows(name):
    """Read a donor PNG once. Used ONLY to derive where things are: window
    boxes, uv maps, aperture spans. No donor pixel reaches the shipped
    exterior any more except on the underframe, which is out of scope."""
    if name not in _DONOR_CACHE:
        _DONOR_CACHE[name] = pngtool.read_png(os.path.join(DONOR, name))[2]
    return _DONOR_CACHE[name]


def donor_glazing(name, min_px=64):
    """The donor's own marked panes in `name`, as uv boxes."""
    return glazing_rects(donor_rows(name), min_px)


# =====================================================================
# SHARED MOTIFS
# =====================================================================

def stainless_field(rows, row_of_y, window_top_y, window_bot_y):
    """Paint the bodyside's tone bands and joint lines onto a canvas.

    `row_of_y` maps donor metres to a canvas row, so the same routine serves
    the 1024x96 body elevation and a 72x99 door leaf even though their
    vertical mappings are completely different curves.
    """
    check_livery(window_top_y, window_bot_y)
    for (y_top, y_bot, colour) in livery_bands(window_top_y, window_bot_y):
        hband(rows, row_of_y(y_top), row_of_y(y_bot), colour)
    for (y, colour) in livery_seams(window_bot_y):
        hline(rows, row_of_y(y), colour)


def pane(rows, x0, y0, x1, y1, radius=2, frame=2):
    """A window: rubber surround, a one-pixel outboard chamfer, marked glass.

    **x0..x1 / y0..y1 is the GLASS**, and the surround is drawn OUTSIDE it.
    That way a pane can be positioned straight from a donor glazing box and
    the aperture this eventually cuts lands exactly where the prototype's did,
    instead of two pixels in from it.

    Three nested rounded rects. The chamfer is what stops a pane reading as a
    black sticker — it gives the surround a lit edge the way a real gasket
    catches light — and it is one pixel because at 40 px/block two would be a
    frame.
    """
    rrect(rows, x0 - frame, y0 - frame, x1 + frame, y1 + frame,
          radius + frame, GASKET_HI)
    rrect(rows, x0 - frame + 1, y0 - frame + 1, x1 + frame - 1, y1 + frame - 1,
          radius + frame - 1, GASKET)
    glaze(rows, x0, y0, x1, y1, radius, GLASS_RGBA)


def us_flag(w, h):
    """A clean pixel US flag: 7 red / 6 white stripes and a star field.

    Stars are a quantised grid rather than shapes — below about 20 px wide a
    drawn five-point star is a smudge, and a regular dot grid reads as stars
    at every size.
    """
    rows = canvas(w, h, FLAG_WHITE)
    for i in range(13):
        y0 = int(round(h * i / 13.0))
        y1 = int(round(h * (i + 1) / 13.0))
        if i % 2 == 0:
            rect(rows, 0, y0, w, y1, FLAG_RED)
    cw, ch = int(round(w * 0.42)), int(round(h * 7 / 13.0))
    rect(rows, 0, 0, cw, ch, FLAG_BLUE)
    step = max(2, cw // 5)
    for y in range(1, ch - 1, step):
        for x in range(1, cw - 1, step):
            rows[y][x] = rgba(FLAG_WHITE)
    return rows


def hazard_label(w, h):
    """The simplified yellow safety decal that repeats beside every door."""
    rows = canvas(w, h, SAFETY_YELLOW)
    rect(rows, 0, 0, w, 1, SAFETY_YELLOW_LO)
    rect(rows, 0, h - 1, w, h, SAFETY_YELLOW_LO)
    for i in range(1, h - 1, 2):
        rect(rows, 1, i, w - 1, i + 1, DECAL_DARK)
    return rows


def hazard_triangle(size):
    """High-voltage triangle: yellow field, dark border, a bolt as one stroke."""
    rows = canvas(size, size, (0, 0, 0, 0))
    for i in range(size):
        half = int(round((i + 1) * size / (2.0 * size)))
        rect(rows, size // 2 - half, i, size // 2 + half + 1, i + 1,
             DECAL_DARK if (i == size - 1 or half == 0) else SAFETY_YELLOW)
    # border
    for i in range(size):
        half = int(round((i + 1) * size / (2.0 * size)))
        for x in (size // 2 - half, size // 2 + half):
            if 0 <= x < size:
                rows[i][x] = rgba(DECAL_DARK)
    rect(rows, 0, size - 1, size, size, DECAL_DARK)
    line(rows, size // 2 + 1, size // 3, size // 2 - 1, size // 2, DECAL_DARK)
    line(rows, size // 2 - 1, size // 2, size // 2 + 1, size - 3, DECAL_DARK)
    return rows


def lirr_wordmark(w, h, disc_colour=LIRR_BLUE, ink=LIRR_BLUE):
    """MTA roundel + a two-line "LONG ISLAND / RAIL ROAD" in the 3x5 font.

    Transparent background: the side logo is its own thin slab of geometry
    standing 20 mm proud of the skin, so everything that is not ink has to
    disappear rather than paint a rectangle onto the bodyside.
    """
    rows = canvas(w, h, (0, 0, 0, 0))
    # The roundel is sized off the TEXT's needs, not off the canvas height:
    # the wordmark is the part that has to stay legible at one block long, so
    # it gets the width and the disc takes what is left. At 0.42*h the disc
    # still reads as a circle and "LONG ISLAND" fits at double scale.
    r = h * 0.42
    cx, cy = 1 + r, h / 2.0
    disc(rows, cx, cy, r, disc_colour)
    text_centred(rows, cx, cy - 2.5, "MTA", DECAL_WHITE, max(1, int(h // 14)))
    x = int(round(cx + r + 3))
    scale = 1
    while text_width("LONG ISLAND", scale + 1) <= w - x - 1 and scale < 4:
        scale += 1
    gap = max(1, scale)
    total = GLYPH_H * scale * 2 + gap
    y = int(round((h - total) / 2.0))
    text(rows, x, y, "LONG ISLAND", ink, scale)
    text(rows, x, y + GLYPH_H * scale + gap, "RAIL ROAD", ink, scale)
    return rows


# =====================================================================
# CONTACT SHEET
# =====================================================================

def _contact_sheet(path):
    """A quick visual index of the palette and the primitives, for eyeballing
    a colour without running the whole pipeline."""
    swatches = [(n, v) for n, v in sorted(globals().items())
                if n.isupper() and isinstance(v, tuple) and len(v) in (3, 4)
                and all(isinstance(c, int) for c in v)]
    cols = 6
    cw, ch = 64, 24
    rowsn = (len(swatches) + cols - 1) // cols
    sheet = canvas(cols * cw, rowsn * ch + 96, (24, 26, 30))
    for i, (name, value) in enumerate(swatches):
        x, y = (i % cols) * cw, (i // cols) * ch
        rect(sheet, x + 1, y + 1, x + cw - 1, y + ch - 9, value)
        text(sheet, x + 2, y + ch - 8, name[:15], DECAL_WHITE, 1)
    base = rowsn * ch + 4
    blit(sheet, 4, base, us_flag(38, 20))
    blit(sheet, 48, base, hazard_label(10, 14))
    blit(sheet, 64, base, hazard_triangle(14))
    blit(sheet, 84, base, lirr_wordmark(128, 40))
    pane(sheet, 220, base, 268, base + 26, 3)
    pngtool.write_png(path, sheet)
    return len(swatches)


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "m7_art_contact.png")
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else out)
    n = _contact_sheet(out)
    print("m7_art: %d palette entries -> %s" % (n, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
