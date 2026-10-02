#!/usr/bin/env python3
"""Ramp rails (2026-10-02): the six rails for the 1:12 material ramp — four MTA
stainless handrails (standing / wall / double / floating) and two Station
Decoration (MSD-style) railings (glass / pickets).

Run from anywhere:  python3 tools/gen_ramp_rail_assets.py

The BLOCK models are computed in Java (client/material/RampRailGeometry on a
ShapeModel via a Fabric BlockStateResolver — a 4.76° slope is beyond JSON), so
there are deliberately NO blockstate files. This writes the two MSD textures
(the MTA rails reuse gen_stair_assets' handrail_steel), flat item icons and
models, loot, recipes, lang and pickaxe-tag entries. Shared files get entries
inserted as text, idempotently, via gen_gap_filler_assets' helpers.
"""

import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk
import gen_gap_filler_assets as shared

ASSETS = shared.ASSETS
DATA = shared.DATA
MOD = shared.MOD

RAILS = {
    "ramp_handrail_standing": "Ramp Handrail (Standing)",
    "ramp_handrail_wall": "Ramp Handrail (Wall)",
    "ramp_handrail_double": "Ramp Handrail (Double)",
    "ramp_handrail_floating": "Ramp Handrail (Floating)",
    "ramp_railing_glass": "Ramp Railing (Glass)",
    "ramp_railing_pickets": "Ramp Railing (Pickets)",
}

LANG = {
    **{f"block.{MOD}.{k}": v for k, v in RAILS.items()},
    "gui.station_announcer.ramp_rail_models": "Number of models: %s",
    "gui.station_announcer.ramp_rail_mode": "Shape: %s (%s/%s)",
    "gui.station_announcer.ramp_rail_hint": "Right-click in the air to change the shape",
    "gui.station_announcer.ramp_rail_hint_follow": "Follows the ramp below or beside it; click the left or right half of a block for the side",
    "gui.station_announcer.ramp_rail_shape.flat": "Level",
    "gui.station_announcer.ramp_rail_shape.start": "Ramp start (bottom extension)",
    "gui.station_announcer.ramp_rail_shape.slope": "Ramp",
    "gui.station_announcer.ramp_rail_shape.end": "Ramp end (top extension)",
    "gui.station_announcer.ramp_rail_shape.corner_outer": "Corner (outer)",
    "gui.station_announcer.ramp_rail_shape.corner_inner": "Corner (inner)",
}

CLEAR = (0, 0, 0, 0)
RAIL = (170, 173, 176, 255)
RAIL_LIT = (214, 217, 220, 255)
RAIL_DARK = (120, 123, 126, 255)
MSD = (223, 223, 233, 255)
MSD_LIT = (240, 240, 246, 255)
MSD_DARK = (176, 176, 188, 255)
GLASS = (196, 232, 244, 96)


# ------------------------------------------------------------------ textures --
def tex_steel():
    """32 px light brushed steel (MSD's railing tone): fine horizontal grain, no stripes."""
    rng = random.Random(41)
    rows = pk.canvas(32, 32, MSD)
    for y in range(32):
        drift = rng.randint(-4, 4)
        for x in range(32):
            g = drift + rng.randint(-3, 3)
            rows[y][x] = (max(0, min(255, MSD[0] + g)), max(0, min(255, MSD[1] + g)),
                          max(0, min(255, MSD[2] + g)), 255)
    return rows


def tex_glass():
    """16 px pale blue glazing, uniform (faces stretch it over the panel) with a faint grain."""
    rng = random.Random(7)
    rows = pk.canvas(16, 16, GLASS)
    for y in range(16):
        for x in range(16):
            g = rng.randint(-3, 3)
            rows[y][x] = (GLASS[0] + g, GLASS[1] + g, min(255, GLASS[2] + g), GLASS[3])
    return rows


# --------------------------------------------------------------------- icons --
def rail_y(x, base):
    """A gentle climb to the right across the icon."""
    return base - (x * 3) // 15


def icon(kind):
    rows = pk.canvas(16, 16, CLEAR)
    msd = kind in ("glass", "pickets")
    lit, mid, dark = (MSD_LIT, MSD, MSD_DARK) if msd else (RAIL_LIT, RAIL, RAIL_DARK)

    def rail(base, thick=2, x0=0, x1=16):
        for x in range(x0, x1):
            y = rail_y(x, base)
            rows[y][x] = lit
            for t in range(1, thick):
                rows[y + t][x] = mid if t < thick - 1 or thick == 2 else dark

    def post(x, top_base, y1=15):
        for y in range(rail_y(x, top_base) + 1, y1):
            rows[y][x] = mid
            rows[y][x + 1] = dark

    if kind == "standing":
        rail(6)
        rail(10, 1)
        post(3, 6)
        post(11, 6)
        pk.rect(rows, 2, 15, 6, 16, dark)
        pk.rect(rows, 10, 15, 14, 16, dark)
    elif kind == "wall":
        pk.rect(rows, 0, 0, 2, 16, (96, 98, 102, 255))
        rail(7)
        for x in (5, 12):
            y = rail_y(x, 7) + 2
            pk.rect(rows, 2, y + 1, x, y + 2, dark)
            rows[y][x] = mid
    elif kind == "double":
        rail(5)
        rail(9)
        post(7, 9)
        pk.rect(rows, 5, 15, 11, 16, dark)
    elif kind == "floating":
        rail(8, 3)
    elif kind == "glass":
        for x in range(1, 15):
            for y in range(rail_y(x, 5) + 2, 14):
                rows[y][x] = GLASS[:3] + (200,)
        rail(5, 2, 1, 15)
        pk.rect(rows, 1, 14, 15, 16, dark)
        for x in (0, 15):
            pk.rect(rows, x, rail_y(x, 5) - 1, x + 1, 16, mid)
    elif kind == "pickets":
        rail(4, 2)
        rail(13, 1)
        for x in range(2, 15, 3):
            for y in range(rail_y(x, 4) + 2, rail_y(x, 13)):
                rows[y][x] = mid
        for x in (0, 14):
            pk.rect(rows, x, rail_y(x, 4) - 1, x + 2, 16, dark)
    return rows


def main():
    pngtool.write_png(os.path.join(ASSETS, "textures/block/ramp_rail_steel.png"), tex_steel())
    pngtool.write_png(os.path.join(ASSETS, "textures/block/ramp_rail_glass.png"), tex_glass())
    for block in RAILS:
        kind = block.split("_")[-1]
        pngtool.write_png(os.path.join(ASSETS, f"textures/item/{block}.png"), icon(kind))
        shared.write_json(os.path.join(ASSETS, f"models/item/{block}.json"),
                          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{block}"}})
        shared.write_json(os.path.join(DATA, MOD, f"loot_tables/blocks/{block}.json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })
    # the MTA ramp rails convert 1:1 from the matching stair handrail (and back is not needed)
    for style in ("standing", "wall", "double", "floating"):
        shared.write_json(os.path.join(DATA, MOD, f"recipes/ramp_handrail_{style}.json"), {
            "type": "minecraft:crafting_shapeless",
            "category": "building",
            "ingredients": [{"item": f"{MOD}:subway_handrail_{style}"}, {"item": f"{MOD}:material_ramp"}],
            "result": {"item": f"{MOD}:ramp_handrail_{style}", "count": 1},
        })
    shaped = {
        "ramp_railing_glass": (["I I", "GGG"], {"I": "minecraft:iron_ingot", "G": "minecraft:glass_pane"}, 6),
        "ramp_railing_pickets": (["III", "BBB"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_bars"}, 6),
    }
    for name, (pattern, key, count) in shaped.items():
        shared.write_json(os.path.join(DATA, MOD, f"recipes/{name}.json"), {
            "type": "minecraft:crafting_shaped",
            "category": "building",
            "key": {k: {"item": v} for k, v in key.items()},
            "pattern": pattern,
            "result": {"item": f"{MOD}:{name}", "count": count},
        })
    lang_path = os.path.join(ASSETS, "lang/en_us.json")
    for key, value in LANG.items():
        shared.insert_before_last_brace(lang_path, json.dumps(key) + ":",
                                        f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}")
    shared.insert_into_tag(os.path.join(DATA, "minecraft/tags/blocks/mineable/pickaxe.json"),
                           [f"{MOD}:{b}" for b in RAILS])
    json.load(open(lang_path))
    print("ramp rail assets written")


if __name__ == "__main__":
    main()
