#!/usr/bin/env python3
"""Material ramps / stairs + curved platform edges (2026-09-25): item models,
the creator icon, loot, recipes, lang and pickaxe-tag entries.

Run from anywhere:  python3 tools/gen_material_assets.py

The BLOCK models of material_ramp, material_stairs and curved_platform_edge
are computed in Java (client/material/ShapeModel via Fabric BlockStateResolvers:
a 1:12 slope, any block's texture, a cut along a curve — nothing JSON can say),
so there are deliberately NO blockstate files for them, and the two material
item models are resolved in code too (they wear the stack's material).
Shared files (en_us.json, the pickaxe tag) get entries inserted as text,
idempotently, via gen_gap_filler_assets' helpers.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk
import gen_gap_filler_assets as shared

ROOT = shared.ROOT
ASSETS = shared.ASSETS
DATA = shared.DATA
MOD = shared.MOD

LANG = {
    "block.station_announcer.material_ramp": "Ramp (ADA 1:12)",
    "block.station_announcer.material_ramp.named": "%s Ramp",
    "block.station_announcer.material_stairs": "Stairs (any texture)",
    "block.station_announcer.material_stairs.named": "%s Stairs",
    "block.station_announcer.curved_platform_edge": "Curved Platform Edge",
    "item.station_announcer.curved_platform_creator": "Curved Platform Creator",
    "tooltip.station_announcer.material.pick": "Right-click the air: choose the texture",
    "tooltip.station_announcer.material.copy": "Sneak-right-click any block: copy its texture (a placed ramp/stair: retexture it)",
    "tooltip.station_announcer.material.ramp": "ADA 1:12: one block up every 12 blocks. Place in a line and the segments continue; the 13th is a level landing",
    "tooltip.station_announcer.curved_platform_creator": "Stand on the platform and click the platform track's two nodes: the edge is cut to follow the curve",
    "tooltip.station_announcer.curved_platform_creator.undo": "Right-click the air: undo the last build · sneak-right-click the air: filler mode",
    "block.station_announcer.curved_gap_filler": "Curved Gap Filler (Union Square)",
    "block.station_announcer.curved_gap_filler_loop": "Curved Gap Filler (South Ferry Loop)",
    "msg.station_announcer.curved_platform.mode.0": "Builds: curved edges only",
    "msg.station_announcer.curved_platform.mode.1": "Builds: curved edges + Union Square gap fillers",
    "msg.station_announcer.curved_platform.mode.2": "Builds: curved edges + South Ferry gap fillers",
    "msg.station_announcer.material.not_full_block": "%s is not a full block",
    "msg.station_announcer.material.picked": "Texture: %s",
    "msg.station_announcer.material.palette_full": "This world already uses %s textures for ramps and stairs; reuse one of them",
    "msg.station_announcer.material.retextured": "Retextured %s block(s) to %s",
    "msg.station_announcer.curved_platform.side": "Stand on the platform side of the track (not on it) to build",
    "msg.station_announcer.curved_platform.built": "Curved platform: %s edge blocks placed, %s trimmed",
    "msg.station_announcer.curved_platform.nothing_to_undo": "Nothing to undo",
    "msg.station_announcer.curved_platform.undone": "Undid %s block changes",
    "gui.station_announcer.material.title": "%s: texture",
    "gui.station_announcer.material.title_block": "Retexture: %s",
    "gui.station_announcer.material.search": "Search blocks",
    "gui.station_announcer.material.cat.all": "All",
    "gui.station_announcer.material.cat.stone": "Stone",
    "gui.station_announcer.material.cat.wood": "Wood",
    "gui.station_announcer.material.cat.concrete": "Concrete",
    "gui.station_announcer.material.cat.terracotta": "Terracotta",
    "gui.station_announcer.material.cat.wool": "Wool",
    "gui.station_announcer.material.cat.glass": "Glass",
    "gui.station_announcer.material.cat.station": "Station",
    "gui.station_announcer.material.cat.other": "Other",
    "gui.station_announcer.material.footer": "%s blocks · click twice or Enter to apply · sneak-click a block in the world to copy it",
    "gui.station_announcer.material.recent": "Recent",
    "gui.station_announcer.material.whole_run": "Whole connected run",
    "gui.station_announcer.material.apply": "Apply",
}


def write_json(path, obj):
    shared.write_json(path, obj)


def creator_icon():
    """16 px: concrete platform with a yellow edge curving past a rail."""
    clear = (0, 0, 0, 0)
    rows = pk.canvas(16, 16, clear)
    concrete = (150, 150, 146, 255)
    concrete_dark = (118, 118, 114, 255)
    yellow = (236, 188, 22, 255)
    rail = (110, 84, 60, 255)
    steel = (190, 192, 196, 255)
    import math
    for y in range(16):
        for x in range(16):
            # a circle arc centred at (-6, 22): platform inside, track outside
            r = math.hypot(x + 6, y - 22)
            if r < 17:
                rows[y][x] = concrete if (x + y) % 5 else concrete_dark
            elif r < 18.6:
                rows[y][x] = yellow
            elif 20.2 < r < 21.2 or 23.2 < r < 24.2:
                rows[y][x] = steel
            elif 21.2 <= r <= 23.2 and (x + y) % 3 == 0:
                rows[y][x] = rail
    return rows


def main():
    # curved edge: item looks like the straight platform edge, drops itself
    write_json(os.path.join(ASSETS, "models/item/curved_platform_edge.json"),
               {"parent": f"{MOD}:block/platform_edge_item"})
    pngtool.write_png(os.path.join(ASSETS, "textures/item/curved_platform_creator.png"), creator_icon())
    write_json(os.path.join(ASSETS, "models/item/curved_platform_creator.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/curved_platform_creator"}})
    for block in ("curved_platform_edge", "curved_gap_filler", "curved_gap_filler_loop"):
        write_json(os.path.join(DATA, MOD, f"loot_tables/blocks/{block}.json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })
    # curved fillers: the straight fillers' icons (plate half out) read right in a slot
    write_json(os.path.join(ASSETS, "models/item/curved_gap_filler.json"), {"parent": f"{MOD}:item/gap_filler"})
    write_json(os.path.join(ASSETS, "models/item/curved_gap_filler_loop.json"), {"parent": f"{MOD}:item/gap_filler_loop"})
    recipes = {
        "material_ramp": {"pattern": ["  S", " SS", "SSS"], "key": {"S": "minecraft:smooth_stone_slab"}, "count": 6},
        "material_stairs": {"pattern": ["S  ", "SS ", "SSS"], "key": {"S": "minecraft:smooth_stone"}, "count": 4},
        "curved_platform_edge": {"pattern": ["EE"], "key": {"E": f"{MOD}:platform_edge"}, "count": 2},
        "curved_gap_filler": {"pattern": ["F"], "key": {"F": f"{MOD}:gap_filler"}, "count": 1},
        "curved_gap_filler_loop": {"pattern": ["F"], "key": {"F": f"{MOD}:gap_filler_loop"}, "count": 1},
        "curved_platform_creator": {"pattern": [" Y ", "YSY", " R "],
                                    "key": {"Y": "minecraft:yellow_dye", "S": f"{MOD}:platform_edge", "R": "minecraft:rail"},
                                    "count": 1},
    }
    for name, r in recipes.items():
        write_json(os.path.join(DATA, MOD, "recipes", name + ".json"), {
            "type": "minecraft:crafting_shaped",
            "category": "building",
            "key": {k: {"item": v} for k, v in r["key"].items()},
            "pattern": r["pattern"],
            "result": {"item": f"{MOD}:{name}", "count": r["count"]},
        })
    lang_path = os.path.join(ASSETS, "lang/en_us.json")
    for key, value in LANG.items():
        shared.insert_before_last_brace(lang_path, json.dumps(key) + ":",
                                        f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}")
    shared.insert_into_tag(os.path.join(DATA, "minecraft/tags/blocks/mineable/pickaxe.json"),
                           [f"{MOD}:material_ramp", f"{MOD}:material_stairs", f"{MOD}:curved_platform_edge",
                            f"{MOD}:curved_gap_filler", f"{MOD}:curved_gap_filler_loop"])
    json.load(open(os.path.join(ASSETS, "lang/en_us.json")))
    print("material + curved platform assets written")


if __name__ == "__main__":
    main()
