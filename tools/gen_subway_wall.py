#!/usr/bin/env python3
"""Regenerates the subway tile-wall set: textures, models, blockstates, loot, recipes.

Run from anywhere:  python3 tools/gen_subway_wall.py

WHAT THIS IS
------------
The BMT/IND platform wall: a field of small white tiles, a horizontal band in
the station's colour edged with a darker line of the same colour, black paint
below platform level where the wall faces the track, and the little black name
tablets with one letter per tile.

THE TILE GRID
-------------
Four tiles to a block, drawn at 32 px so a tile is 8 px with a 1 px grout line
— an eighth of the tile, where a 16 px texture would force a heavy quarter.
Every texture here uses that same grid and tiles seamlessly, so a wall of any
size reads as one continuous field with no per-block variants and no edge
pieces.

THE BAND IS GREYSCALE ON PURPOSE
--------------------------------
It is tinted with the MTR station's colour at render time (the mechanism the
station columns use), and tinting MULTIPLIES, so the band texture is painted
in light greys.

The band block is THREE stacked elements: the middle two tile rows carry the
tint, and the top and bottom rows are real white tile with no tint index at
all. That is why the frame around the band matches the wall exactly — it IS
the wall's tile, not a tinted approximation of it — and it is also why the
band comes out two tiles tall however the wall around it is built.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data")

# 32 px to a block, so a tile is 8 px and its grout line is 1 px — an eighth
# of the tile rather than the quarter a 16 px texture forces. Block textures
# may be any square size; this is the cheapest way to get a thin grout line.
SIZE = 32
TILE = 8          #: pixels per tile — still four tiles to a block
GROUT = 1         #: grout line width, in pixels

#: A deterministic wobble so no two tiles are exactly alike. Hashed from the
#: tile's coordinates rather than random, so regenerating gives the same wall.
def wobble(tx, ty, spread, salt=0):
    return ((tx * 73856093) ^ (ty * 19349663) ^ (salt * 83492791)) % (2 * spread + 1) - spread


def tiled(face, grout, spread=6, grime=0.0, salt=0):
    """A field of tiles on the 4 px grid.

    `grime` darkens the lower rows, the way a platform wall actually weathers.
    """
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            on_grout = (x % TILE) < GROUT or (y % TILE) < GROUT
            base = grout if on_grout else face
            shade = wobble(x // TILE, y // TILE, spread, salt) if not on_grout else 0
            dim = 1.0 - grime * (y / (SIZE - 1.0))
            row.append(tuple(max(0, min(255, round((c + shade) * dim))) for c in base) + (255,))
        rows.append(row)
    return rows


def band_texture():
    """Light grey tiles, tinted to the station's colour at render time.

    Only the middle two tile rows of this texture are ever sampled: the band
    model frames them with real white tile above and below, so the frame is
    the wall's own tile rather than a tinted approximation of it.
    """
    return tiled((205, 205, 205), (176, 176, 176), spread=7, salt=3)


#: The no-clearance band's stripe period, in texture pixels. Two rules from the
#: zebra board, and they are not negotiable: the period must DIVIDE the texture
#: width or the diagonal breaks at every block seam, and the slope must be a
#: true 45 degrees (one pixel across per row) or it steps and reads as a
#: zigzag. At 32 px that gives two full stripes across a block.
STRIPE_PERIOD = 16

STRIPE_WHITE = (233, 231, 226)
STRIPE_RED = (176, 42, 38)


def stripe_texture():
    """Diagonal red-and-white hazard stripes, seamless in both directions.

    The 45 degree slope shifts the pattern by exactly `SIZE` pixels over the
    texture's height, and SIZE is a whole number of periods, so the pattern
    also meets itself top to bottom.
    """
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            # Shifting by +y is what tilts the stripes; the red half is a
            # little narrower than the white, as on the real markings.
            red = ((x + y) % STRIPE_PERIOD) < STRIPE_PERIOD * 7 // 16
            # No shading of any kind: the band is painted, not tiled, and any
            # banding here would break the stripe into steps.
            row.append(STRIPE_RED + (255,) if red else STRIPE_WHITE + (255,))
        rows.append(row)
    return rows


def stripe_top_texture():
    """The band's top and bottom: plain painted concrete, no stripes."""
    return [[STRIPE_WHITE + (255,) for _ in range(SIZE)] for _ in range(SIZE)]


TEXTURES = {
    "no_clearance_stripe": stripe_texture,
    "no_clearance_stripe_top": stripe_top_texture,
    # A working platform wall: off-white, grubbier towards the bottom. The
    # grout is only a little darker than the face — a quarter of the texture is
    # grout at this tile size, so heavy lines read as a grid, not as tile.
    "subway_tile_white": lambda: tiled((238, 236, 230), (198, 195, 187), spread=5, grime=0.06),
    # The same wall after a rebuild.
    "subway_tile_white_clean": lambda: tiled((247, 247, 243), (216, 215, 209), spread=3, salt=1),
    # Painted black: the tile grid still shows through the paint.
    "subway_tile_dark": lambda: tiled((28, 28, 30), (18, 18, 20), spread=3, salt=2),
    "subway_tile_band": band_texture,
}


# ------------------------------------------------------------------ models

def cube(texture, tint=False):
    """A plain full cube; every face culled against its neighbour."""
    faces = {}
    for side in ("north", "south", "east", "west", "up", "down"):
        face = {"texture": "#all", "cullface": side}
        if tint:
            face["tintindex"] = 0
        faces[side] = face
    return {
        "parent": "minecraft:block/block",
        "textures": {"all": "station_announcer:block/" + texture,
                     "particle": "station_announcer:block/" + texture},
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}],
    }


#: How tall the no-clearance band stands, in model pixels. Shy of a full block
#: so it reads as a painted band set into the wall rather than another course
#: of it; the block still occupies its whole space (see StripeBlock).
STRIPE_HEIGHT = 15


def stripe_model():
    faces = {}
    for side in ("north", "south", "east", "west"):
        # Auto-UV: a 15 px tall face samples rows 1..16, so the stripes stay
        # 1:1 with their texels instead of being squashed onto a short face.
        faces[side] = {"texture": "#side", "cullface": side}
    faces["up"] = {"texture": "#top"}
    faces["down"] = {"texture": "#top", "cullface": "down"}
    return {
        "parent": "minecraft:block/block",
        "textures": {"side": "station_announcer:block/no_clearance_stripe",
                     "top": "station_announcer:block/no_clearance_stripe_top",
                     "particle": "station_announcer:block/no_clearance_stripe"},
        "elements": [{"from": [0, 0, 0], "to": [16, STRIPE_HEIGHT, 16], "faces": faces}],
    }


#: How deep the alcove is cut into the wall, in model pixels: half a block.
ALCOVE_DEPTH = 8


def _box(f, t, texture):
    return {"from": f, "to": t,
            "faces": {s: {"texture": texture}
                      for s in ("north", "south", "east", "west", "up", "down")}}


def alcove(interior):
    """A niche cut into the wall: back panel and two jambs, open at the front.

    Authored facing north — the opening looks toward -Z — and rotated by the
    blockstate. Nothing closes the top or bottom, so alcoves stacked two high
    make one niche a player can stand in.

    `interior` is the texture the recess is lined with: white tile, as though
    the tiling simply carries on into the niche, or black paint.
    """
    return {
        "parent": "minecraft:block/block",
        "textures": {"in": "station_announcer:block/" + interior,
                     "particle": "station_announcer:block/" + interior},
        "elements": [
            # Back of the niche. Its own back face is against the block behind.
            _box([0, 0, ALCOVE_DEPTH], [16, 16, 16], "#in"),
            # Jambs: the thickness of the wall the niche is cut through.
            _box([0, 0, 0], [1, 16, ALCOVE_DEPTH], "#in"),
            _box([15, 0, 0], [16, 16, ALCOVE_DEPTH], "#in"),
        ],
    }


#: The band's coloured middle, in model pixels: tile rows 1 and 2 of four.
BAND_BOTTOM = 4
BAND_TOP = 12


def band_model():
    """Two tile rows of tinted band framed by two rows of real white tile.

    Three stacked elements rather than one cube, because a quad carries a
    single tint index: the frame must be untinted to stay white. Auto-UV puts
    each element on the right rows of its own texture (a face at model height y
    samples row 16 - y), so the tile grid runs straight through the join.
    """
    def slab(y1, y2, texture, tint):
        faces = {}
        for side in ("north", "south", "east", "west"):
            face = {"texture": texture, "cullface": side}
            if tint:
                face["tintindex"] = 0
            faces[side] = face
        # Only the outermost slabs have a lid worth drawing; the faces where
        # the three meet are buried and would just be wasted quads.
        if y2 == 16:
            faces["up"] = {"texture": texture, "cullface": "up"}
        if y1 == 0:
            faces["down"] = {"texture": texture, "cullface": "down"}
        return {"from": [0, y1, 0], "to": [16, y2, 16], "faces": faces}

    return {
        "parent": "minecraft:block/block",
        "textures": {"band": "station_announcer:block/subway_tile_band",
                     "tile": "station_announcer:block/subway_tile_white",
                     "particle": "station_announcer:block/subway_tile_band"},
        "elements": [
            slab(0, BAND_BOTTOM, "#tile", False),
            slab(BAND_BOTTOM, BAND_TOP, "#band", True),
            slab(BAND_TOP, 16, "#tile", False),
        ],
    }


def rotated_variants(model):
    """The four horizontal rotations of a north-authored model."""
    variants = {}
    for i, facing in enumerate(("north", "east", "south", "west")):
        entry = {"model": "station_announcer:block/" + model}
        if i:
            entry["y"] = i * 90
        variants["facing=" + facing] = entry
    return variants


# ------------------------------------------------------------------- data

def loot(block):
    return {
        "type": "minecraft:block",
        "pools": [{"rolls": 1,
                   "entries": [{"type": "minecraft:item", "name": "station_announcer:" + block}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
    }


def recipe(result, count, pattern, key):
    return {
        "type": "minecraft:crafting_shaped",
        "pattern": pattern,
        "key": key,
        "result": {"id": "station_announcer:" + result, "count": count},
        "fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": ["mtr"]}],
    }


#: Every block in the set. This list drives the item models and loot tables,
#: so a block left off it registers fine and then drops nothing when broken.
BLOCKS = [
    "subway_tile_wall", "subway_tile_wall_clean", "subway_tile_band",
    "subway_tile_dark", "subway_tile_alcove", "subway_tile_alcove_dark",
    "subway_name_tablet", "no_clearance_stripe",
]


def write(path, obj):
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


def main():
    for name, build in TEXTURES.items():
        pngtool.write_png(os.path.join(ASSETS, "textures/block", name + ".png"), build())

    models = os.path.join(ASSETS, "models/block")
    write(os.path.join(models, "subway_tile_wall.json"), cube("subway_tile_white"))
    write(os.path.join(models, "subway_tile_wall_clean.json"), cube("subway_tile_white_clean"))
    write(os.path.join(models, "subway_tile_dark.json"), cube("subway_tile_dark"))
    write(os.path.join(models, "subway_tile_band.json"), band_model())
    write(os.path.join(models, "no_clearance_stripe.json"), stripe_model())
    write(os.path.join(models, "subway_tile_alcove.json"), alcove("subway_tile_white"))
    write(os.path.join(models, "subway_tile_alcove_dark.json"), alcove("subway_tile_dark"))
    # The tablet block IS a white tile block; the tablet itself is drawn by the
    # block entity renderer, so it can carry text and overflow its own block.
    write(os.path.join(models, "subway_name_tablet.json"), cube("subway_tile_white"))

    states = os.path.join(ASSETS, "blockstates")
    for block in ("subway_tile_wall", "subway_tile_wall_clean", "subway_tile_band",
                  "subway_tile_dark", "no_clearance_stripe"):
        write(os.path.join(states, block + ".json"),
              {"variants": {"": {"model": "station_announcer:block/" + block}}})
    for block in ("subway_tile_alcove", "subway_tile_alcove_dark"):
        write(os.path.join(states, block + ".json"), {"variants": rotated_variants(block)})
    # The tablet's row lives in the blockstate (the renderer reads it), but the
    # model is the same white tile whichever row is chosen.
    tablet = {}
    for row in range(4):
        for facing, entry in rotated_variants("subway_name_tablet").items():
            tablet["row=%d,%s" % (row, facing)] = entry
    write(os.path.join(states, "subway_name_tablet.json"), {"variants": tablet})

    items = os.path.join(ASSETS, "models/item")
    for block in BLOCKS:
        write(os.path.join(items, block + ".json"),
              {"parent": "station_announcer:block/" + block})

    for block in BLOCKS:
        write(os.path.join(DATA, "station_announcer/loot_tables/blocks", block + ".json"), loot(block))

    write(os.path.join(DATA, "station_announcer/recipes/subway_tile_wall.json"),
          recipe("subway_tile_wall", 8, ["TTT", "TQT", "TTT"],
                 {"T": {"item": "minecraft:white_concrete"}, "Q": {"item": "minecraft:quartz"}}))
    write(os.path.join(DATA, "station_announcer/recipes/subway_tile_wall_clean.json"),
          recipe("subway_tile_wall_clean", 8, ["TTT", "TQT", "TTT"],
                 {"T": {"item": "station_announcer:subway_tile_wall"}, "Q": {"item": "minecraft:water_bucket"}}))
    write(os.path.join(DATA, "station_announcer/recipes/subway_tile_band.json"),
          recipe("subway_tile_band", 8, ["TTT", "TDT", "TTT"],
                 {"T": {"item": "station_announcer:subway_tile_wall"}, "D": {"item": "minecraft:green_dye"}}))
    write(os.path.join(DATA, "station_announcer/recipes/subway_tile_dark.json"),
          recipe("subway_tile_dark", 8, ["TTT", "TDT", "TTT"],
                 {"T": {"item": "station_announcer:subway_tile_wall"}, "D": {"item": "minecraft:black_dye"}}))
    write(os.path.join(DATA, "station_announcer/recipes/subway_tile_alcove.json"),
          recipe("subway_tile_alcove", 4, ["TTT", "T  ", "TTT"],
                 {"T": {"item": "station_announcer:subway_tile_wall"}}))
    write(os.path.join(DATA, "station_announcer/recipes/subway_tile_alcove_dark.json"),
          recipe("subway_tile_alcove_dark", 4, ["TTT", "T  ", "TTT"],
                 {"T": {"item": "station_announcer:subway_tile_dark"}}))
    write(os.path.join(DATA, "station_announcer/recipes/no_clearance_stripe.json"),
          recipe("no_clearance_stripe", 8, ["WRW", "RWR", "WRW"],
                 {"W": {"item": "minecraft:white_concrete"}, "R": {"item": "minecraft:red_concrete"}}))
    write(os.path.join(DATA, "station_announcer/recipes/subway_name_tablet.json"),
          recipe("subway_name_tablet", 4, ["TTT", "TIT", "TTT"],
                 {"T": {"item": "station_announcer:subway_tile_wall"}, "I": {"item": "minecraft:ink_sac"}}))

    print("subway wall: %d textures, %d blocks" % (len(TEXTURES), len(BLOCKS)))


if __name__ == "__main__":
    main()
