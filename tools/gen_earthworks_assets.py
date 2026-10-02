#!/usr/bin/env python3
"""Earthworks tools (2026-10-02): Embankment Creator, Trench Creator, ROW
Clearer — item icons and models, recipes, lang. The items are plain MTR
node-clicking items; their screen and engine are Java (EarthworksScreen /
EarthworksBuilder). Shared files get entries inserted as text, idempotently.

Run from anywhere:  python3 tools/gen_earthworks_assets.py
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk
import gen_gap_filler_assets as shared

ASSETS = shared.ASSETS
DATA = shared.DATA
MOD = shared.MOD

LANG = {
    "item.station_announcer.embankment_creator": "Embankment Creator",
    "item.station_announcer.trench_creator": "Trench Creator",
    "item.station_announcer.row_clearer": "ROW Clearer",
    "tooltip.station_announcer.embankment_creator": "Slope %s · fills up to %s blocks high",
    "tooltip.station_announcer.trench_creator": "Slope %s · cuts up to %s blocks deep",
    "tooltip.station_announcer.row_clearer": "Corridor ±%s past the tracks · %s blocks high",
    "tooltip.station_announcer.earthworks.use": "Click two connected rail nodes to build · sneak-click a block to use it",
    "commands.station_announcer.earthworks.hold_tool": "Hold an Embankment Creator, Trench Creator or ROW Clearer",
    "msg.station_announcer.earthworks.busy": "Still working on your last build — wait for it to finish",
    "msg.station_announcer.earthworks.failed": "Earthworks failed — see the server log",
    "msg.station_announcer.earthworks.too_big": "Too big: over %s blocks would change. Build a shorter stretch, or lower the height / depth limits.",
    "msg.station_announcer.earthworks.bad_materials": "Unknown blocks in the mix (skipped): %s",
    "msg.station_announcer.earthworks.nothing": "%s: nothing to do here",
    "msg.station_announcer.earthworks.done": "%s: removed %s blocks, placed %s",
    "msg.station_announcer.earthworks.trees": "%s trees felled",
    "msg.station_announcer.earthworks.skipped_bridge": "~%s blocks of track left too high (a bridge's job)",
    "msg.station_announcer.earthworks.skipped_tunnel": "~%s blocks of track left too deep (a tunnel's job)",
    "msg.station_announcer.earthworks.kept": "%s non-natural blocks left in place",
    "msg.station_announcer.earthworks.progress": "Earthworks: %s%% (%s / %s blocks)",
    "msg.station_announcer.earthworks.undone": "Undid %s block changes",
    "msg.station_announcer.earthworks.picked": "%s → %s (replaces the mix)",
    "msg.station_announcer.earthworks.picked_add": "%s added to %s",
}

CLEAR = (0, 0, 0, 0)
GRASS = (92, 150, 58, 255)
GRASS_DARK = (64, 112, 40, 255)
DIRT = (134, 96, 67, 255)
DIRT_DARK = (102, 72, 50, 255)
STONE = (128, 128, 128, 255)
STONE_DARK = (96, 96, 96, 255)
GRAVEL = (150, 142, 136, 255)
STEEL = (200, 202, 206, 255)
TIE = (110, 80, 52, 255)
LOG = (110, 84, 52, 255)
LEAF = (60, 120, 46, 255)
RED = (200, 50, 40, 255)


def icon_embankment():
    rows = pk.canvas(16, 16, CLEAR)
    for y in range(6, 16):
        half = 4 + (y - 6) * 0.45
        for x in range(16):
            d = abs(x - 7.5)
            if d > half:
                continue
            if y == 6:
                rows[y][x] = GRAVEL
            elif d > half - 1.3:
                rows[y][x] = GRASS if (x + y) % 2 else GRASS_DARK
            else:
                rows[y][x] = DIRT if (x + y) % 3 else DIRT_DARK
    pk.rect(rows, 4, 5, 12, 6, TIE)
    pk.rect(rows, 4, 4, 12, 5, STEEL)
    return rows


def icon_trench():
    rows = pk.canvas(16, 16, CLEAR)
    for y in range(2, 16):
        for x in range(16):
            depth = abs(x - 7.5)
            if depth < 2 + (y - 2) * 0 and y < 12:
                continue
            cut = 1.5 + (12 - y) * 0.55
            if y < 12 and depth < cut:
                continue
            rows[y][x] = STONE if (x * 3 + y) % 4 else STONE_DARK
            if y == 2 or (y < 12 and depth < cut + 1):
                rows[y][x] = GRASS if y == 2 else STONE_DARK
    pk.rect(rows, 5, 11, 11, 12, TIE)
    pk.rect(rows, 5, 10, 11, 11, STEEL)
    return rows


def icon_clearer():
    rows = pk.canvas(16, 16, CLEAR)
    pk.disc(rows, 5, 5, 4, LEAF)
    pk.rect(rows, 4, 8, 6, 15, LOG)
    pk.rect(rows, 1, 15, 15, 16, GRASS_DARK)
    # axe
    pk.line(rows, 9, 15, 14, 7, (130, 92, 56, 255), 1)
    pk.rect(rows, 12, 4, 16, 8, STEEL)
    pk.line(rows, 1, 1, 10, 10, RED, 1)
    return rows


ICONS = {
    "embankment_creator": icon_embankment,
    "trench_creator": icon_trench,
    "row_clearer": icon_clearer,
}

RECIPES = {
    "embankment_creator": (["DRD", "DSD"], {"D": "minecraft:dirt", "R": "minecraft:rail", "S": "minecraft:iron_shovel"}),
    "trench_creator": (["CRC", "CPC"], {"C": "minecraft:cobblestone", "R": "minecraft:rail", "P": "minecraft:iron_pickaxe"}),
    "row_clearer": (["SRS", " A "], {"S": "minecraft:oak_sapling", "R": "minecraft:rail", "A": "minecraft:iron_axe"}),
}


def main():
    for item, fn in ICONS.items():
        pngtool.write_png(os.path.join(ASSETS, f"textures/item/{item}.png"), fn())
        shared.write_json(os.path.join(ASSETS, f"models/item/{item}.json"),
                          {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{MOD}:item/{item}"}})
    for item, (pattern, key) in RECIPES.items():
        shared.write_json(os.path.join(DATA, MOD, f"recipes/{item}.json"), {
            "type": "minecraft:crafting_shaped",
            "category": "tools",
            "key": {k: {"item": v} for k, v in key.items()},
            "pattern": pattern,
            "result": {"item": f"{MOD}:{item}", "count": 1},
        })
    lang_path = os.path.join(ASSETS, "lang/en_us.json")
    for key, value in LANG.items():
        shared.insert_before_last_brace(lang_path, json.dumps(key) + ":",
                                        f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}")
    json.load(open(lang_path))
    print("earthworks assets written")


if __name__ == "__main__":
    main()
