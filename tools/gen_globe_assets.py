#!/usr/bin/env python3
"""Regenerates the entrance-globe lamp textures and their models.

Run from anywhere:  python3 tools/gen_globe_assets.py

WHAT A GLOBE LAMP IS
--------------------
The cast-iron globe at the top of a subway entrance: a coloured glass orb
(green = the station is staffed around the clock, red = it is not) sitting on
a leafy crown on a fluted post. The orb is COLOURED ON TOP and burns
white-yellow underneath, where the lamp inside it is.

THE ROW LAYOUT (this is the whole trick)
----------------------------------------
The orb is five stacked boxes spanning y 7..16, and their side faces use
vanilla auto-UV — so a face at height y samples texture row 16 - y. That maps
the orb's height directly onto the texture's rows:

    rows 0..1   layer 5  (y 15..16)   crown of the orb
    rows 1..2   layer 4  (y 14..15)
    rows 2..6   layer 3  (y 10..14)   the widest part; the colour changes here
    rows 6..7.5 layer 2  (y 8.5..10)
    rows 7.5..9 layer 1  (y 7..8.5)   the glowing underside

So the texture is painted as a vertical gradient and the orb inherits it.

WHY THE UP/DOWN FACES NEED EXPLICIT UV
--------------------------------------
Auto-UV maps a horizontal face to the SQUARE region under its footprint —
u from x, v from z — so every up and down face sampled rows 4..12 regardless
of its height. That smeared the green-to-yellow transition across the top of
the orb (it rendered yellow) and put a band of it on each visible ring. Each
horizontal face therefore gets an explicit one-row slice at ITS OWN height,
taken from the middle columns where the texture is flat, so a ring reads as
one clean colour.

The crown pieces below the orb get the same treatment against
`globe_crown` (rim at row 9, leaf band 10..12, fluting 12..16).
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXTURES = os.path.join(ROOT, "src/main/resources/assets/station_announcer/textures/block")
MODELS = os.path.join(ROOT, "src/main/resources/assets/station_announcer/models/block")

SIZE = 16

# The two glass colours, and the lamp burning behind them.
GREEN = (0x24, 0x8E, 0x44)
RED = (0xC4, 0x2E, 0x2A)
GLOW_MID = (0xFF, 0xD9, 0x8C)     # where the colour has given way to lamplight
GLOW_PALE = (0xFF, 0xEE, 0xC4)
GLOW_HOT = (0xFF, 0xF9, 0xE6)     # the very bottom, straight at the bulb

#: Rows the orb's side faces read, top of the orb first.
ORB_ROWS = 10


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def lighten(color, t):
    return mix(color, (255, 255, 255), t)


def orb_row_color(row, color):
    """The orb's colour at texture row `row` (0 = top of the orb)."""
    if row <= 1:
        return lighten(color, 0.30 - 0.10 * row)   # light catches the crown
    if row <= 3:
        return color
    if row <= 5:
        return mix(color, GLOW_MID, (row - 3) / 2.0)  # glass gives way to lamplight
    if row <= 7:
        return mix(GLOW_MID, GLOW_PALE, (row - 5) / 2.0)
    return mix(GLOW_PALE, GLOW_HOT, min(1.0, (row - 7) / 2.0))


def globe_texture(color):
    """A vertical gradient: the lamp's colour on top, its light underneath.

    Shading varies VERTICALLY only except for a slight darkening of the two
    outermost columns, which rounds off the orb's side faces. The horizontal
    faces sample the middle columns, so that edge shading never reaches them.
    """
    rows = []
    for y in range(SIZE):
        # Below the orb's own rows nothing is sampled; carry the last colour on
        # so a stray sample reads as more glow rather than as a stripe.
        base = orb_row_color(min(y, ORB_ROWS - 1), color)
        row = []
        for x in range(SIZE):
            edge = min(x, SIZE - 1 - x)
            shade = 0.88 if edge == 0 else (0.94 if edge == 1 else 1.0)
            row.append(tuple(round(c * shade) for c in base) + (255,))
        rows.append(row)
    return rows


# ---------------------------------------------------------------- models

#: Orb boxes, bottom first: (from, to).
ORB = [
    ([6, 7, 6], [10, 8.5, 10]),
    ([5, 8.5, 5], [11, 10, 11]),
    ([4, 10, 4], [12, 14, 12]),
    ([5, 14, 5], [11, 15, 11]),
    ([6, 15, 6], [10, 16, 10]),
]

#: Crown boxes below the orb, bottom first.
CROWN = [
    ([6, 0, 6], [10, 4, 10]),
    ([4.5, 4, 4.5], [11.5, 6, 11.5]),
    ([5.5, 6, 5.5], [10.5, 7, 10.5]),
]


def band(row):
    """A one-row slice from the flat middle of the texture, for a horizontal face.

    Taken from texel CENTRE to texel centre (row + 0.5 onward) rather than
    edge to edge: a window that reaches a sprite's outer boundary samples past
    the last texel and interpolates into whatever is stitched beside it in the
    atlas, which is what put a pink line down the stop marker's bracket.
    """
    lo = min(max(row + 0.5, 0.5), 14.5)
    return [5, round(lo, 2), 11, round(lo + 1.0, 2)]


def element(box, texture):
    """One box: auto-UV on the sides, an own-height slice on the horizontal faces."""
    (x1, y1, z1), (x2, y2, z2) = box
    faces = {side: {"texture": texture} for side in ("north", "south", "east", "west")}
    # A face's height decides its colour: row 16 - y, the same mapping the
    # side faces get from auto-UV, so a ring matches the wall it sits on.
    faces["up"] = {"texture": texture, "uv": band(max(0.0, 16 - y2))}
    faces["down"] = {"texture": texture, "uv": band(min(15.0, 16 - y1))}
    return {"from": [x1, y1, z1], "to": [x2, y2, z2], "faces": faces}


def lamp_model(color_texture):
    elements = [element(box, "#crown") for box in CROWN]
    elements += [element(box, "#globe") for box in ORB]
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "crown": "station_announcer:block/globe_crown",
            "globe": "station_announcer:block/" + color_texture,
            "particle": "station_announcer:block/" + color_texture,
        },
        "elements": elements,
    }


def main():
    for name, color in (("globe_green", GREEN), ("globe_red", RED)):
        pngtool.write_png(os.path.join(TEXTURES, name + ".png"), globe_texture(color))
    for block, texture in (("globe_lamp_green", "globe_green"), ("globe_lamp_red", "globe_red")):
        with open(os.path.join(MODELS, block + ".json"), "w") as fh:
            json.dump(lamp_model(texture), fh, indent=2)
            fh.write("\n")
    print("globe lamps: 2 textures + 2 models regenerated")


if __name__ == "__main__":
    main()
