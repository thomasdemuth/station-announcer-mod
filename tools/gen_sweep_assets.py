#!/usr/bin/env python3
"""Structure Sweeper (2026-10-21, STRUCTURE_SWEEP_PLAN.md): Template Wand and
Structure Sweeper item icons + models, recipes, lang. The items' behaviour is
Java (Sweep / SweepMapper / SweepScreen). Lang is upserted, idempotent.

Run from anywhere:  python3 tools/gen_sweep_assets.py
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk
import atmo_common as common

ASSETS = common.ASSETS
DATA = common.DATA
MOD = common.MOD

LANG = {
    "item.station_announcer.sweep_wand": "Template Wand",
    "item.station_announcer.structure_sweeper": "Structure Sweeper",
    "gui.station_announcer.sweep.save_title": "Save template",
    "gui.station_announcer.sweep.mode.repeat": "Repeat",
    "gui.station_announcer.sweep.mode.stretch": "Stretch to fit",
    "gui.station_announcer.sweep.mode.once": "Once",
    "tooltip.station_announcer.sweep_wand.box": "Box %s × %s × %s",
    "tooltip.station_announcer.sweep_wand.use": "Right-click two corners (a block, or the air where you stand)",
    "tooltip.station_announcer.sweep_wand.clear": "Sneak-right-click the air to start over",
    "tooltip.station_announcer.structure_sweeper.none": "none",
    "tooltip.station_announcer.structure_sweeper.template": "Template: %s · %s",
    "tooltip.station_announcer.structure_sweeper.use": "Click two connected rail nodes to preview it there (first → second)",
    "msg.station_announcer.sweep.not_allowed": "Only creative players and operators can use the sweeper tools",
    "msg.station_announcer.sweep.too_big": "Too big: %s long × %s wide × %s tall (max %s × %s × %s)",
    "msg.station_announcer.sweep.axis_outside": "The rail cell must be inside the box",
    "msg.station_announcer.sweep.corner1": "Corner 1 at %s %s %s — now the opposite corner (a block, or the air where you stand)",
    "msg.station_announcer.sweep.corner2": "Box %s × %s × %s",
    "msg.station_announcer.sweep.empty": "That box has no blocks in it",
    "msg.station_announcer.sweep.no_track": "Pick a track first: click two rail nodes with the sweeper",
    "msg.station_announcer.sweep.track_too_long": "That track is too long to sweep in one go (max %s blocks)",
    "msg.station_announcer.sweep.axis": "Rail cell set · forward = %s",
    "msg.station_announcer.sweep.dir.north": "north",
    "msg.station_announcer.sweep.dir.east": "east",
    "msg.station_announcer.sweep.dir.south": "south",
    "msg.station_announcer.sweep.dir.west": "west",
    "msg.station_announcer.sweep.cleared": "Selection cleared",
    "msg.station_announcer.sweep.wand_hint": "Click a corner of the structure to start · sneak-right-click the air to clear",
    "msg.station_announcer.sweep.no_template": "Pick a template first (right-click the air)",
    "msg.station_announcer.sweep.missing_template": "Template \"%s\" no longer exists — pick another",
    "msg.station_announcer.sweep.nothing": "%s: nothing to build here",
    "msg.station_announcer.sweep.done": "Swept %s: placed %s blocks, cleared %s · %s copies",
    "msg.station_announcer.sweep.stretched": "each copy %s%% of its length",
    "msg.station_announcer.sweep.deleted": "Deleted template %s",
    "msg.station_announcer.sweep.incomplete": "Select both corners and the rail cell first",
    "msg.station_announcer.sweep.bad_name": "Give the template a name",
    "msg.station_announcer.sweep.save_failed": "Could not save the template — see the server log",
    "msg.station_announcer.sweep.saved": "Saved template %s (%s long × %s wide × %s tall, %s blocks)",
    "msg.station_announcer.sweep.replaced": "Replaced template %s (%s long × %s wide × %s tall, %s blocks)",
    "msg.station_announcer.sweep.using": "Sweeper now uses %s",
    "commands.station_announcer.sweep.player_only": "Only a player can run this",
    "commands.station_announcer.sweep.hold_tool": "Hold a Structure Sweeper",
    "commands.station_announcer.sweep.hold_wand": "Hold a Template Wand with a complete selection",
    "commands.station_announcer.sweep.bad_facing": "Facing must be north, east, south or west",
    "commands.station_announcer.sweep.list": "%s sweep templates:",
    "commands.station_announcer.sweep.unknown": "No template called %s",
    "commands.station_announcer.sweep.exported": "Exported %s to %s",
    "commands.station_announcer.sweep.import_failed": "Could not import %s",
    "commands.station_announcer.sweep.imported": "Imported template %s",
    # the shared applier now serves the sweeper too
    "msg.station_announcer.earthworks.progress": "Building: %s%% (%s / %s blocks)",
}

CLEAR = (0, 0, 0, 0)
WOOD = (122, 88, 54, 255)
WOOD_DARK = (88, 62, 38, 255)
CYAN = (70, 210, 255, 255)
CYAN_DARK = (30, 140, 190, 255)
WHITE = (240, 240, 240, 255)
STEEL = (200, 202, 206, 255)
STEEL_DARK = (130, 132, 138, 255)
STONE = (150, 146, 140, 255)
STONE_DARK = (104, 100, 96, 255)
PIER = (178, 120, 84, 255)
PIER_DARK = (128, 82, 58, 255)


def icon_wand():
    rows = pk.canvas(16, 16, CLEAR)
    # handle, bottom-left to the middle
    for i in range(9):
        x, y = 1 + i, 14 - i
        rows[y][x] = WOOD
        if x + 1 < 16:
            rows[y][x + 1] = WOOD_DARK
    # dashed selection box top-right with a cyan corner marker
    for x in range(8, 16):
        if x % 2 == 0:
            rows[1][x] = WHITE
            rows[7][x] = WHITE
    for y in range(1, 8):
        if y % 2 == 1:
            rows[y][8] = WHITE
            rows[y][15] = WHITE
    pk.rect(rows, 10, 3, 14, 7, CYAN)
    pk.rect(rows, 11, 4, 13, 6, CYAN_DARK)
    return rows


def icon_sweeper():
    rows = pk.canvas(16, 16, CLEAR)
    # an arched deck curving up to the right
    import math
    pts = []
    for i in range(40):
        a = math.pi * (1.0 + 0.5 * i / 39)  # 180° → 270°
        x = 15 + 13 * math.cos(a)
        y = 15 + 13 * math.sin(a)
        pts.append((x, y))
    for x, y in pts:
        xi, yi = int(round(x)), int(round(y))
        if 0 <= xi < 16 and 0 <= yi < 16:
            rows[yi][xi] = STEEL
            if yi + 1 < 16:
                rows[yi + 1][xi] = STONE
            if yi + 2 < 16:
                rows[yi + 2][xi] = STONE_DARK
    # piers under the deck
    for px in (4, 9):
        top = None
        for y in range(16):
            if rows[y][px] == STONE_DARK:
                top = y
                break
        if top is not None:
            for y in range(top, 16):
                rows[y][px] = PIER
                if px + 1 < 16:
                    rows[y][px + 1] = PIER_DARK
    # cyan sparkle: "pasted"
    rows[2][2] = CYAN
    rows[1][2] = CYAN_DARK
    rows[3][2] = CYAN_DARK
    rows[2][1] = CYAN_DARK
    rows[2][3] = CYAN_DARK
    return rows


ICONS = {"sweep_wand": icon_wand, "structure_sweeper": icon_sweeper}

RECIPES = {
    "sweep_wand": (["  A", " S ", "S  "], {"A": "minecraft:amethyst_shard", "S": "minecraft:stick"}),
    "structure_sweeper": (["BRB", " C "], {"B": "minecraft:stone_bricks", "R": "minecraft:rail", "C": "minecraft:compass"}),
}


def main():
    for item, fn in ICONS.items():
        pngtool.write_png(os.path.join(ASSETS, f"textures/item/{item}.png"), fn())
        common.write_json(os.path.join(ASSETS, f"models/item/{item}.json"),
                          {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{MOD}:item/{item}"}})
    for item, (pattern, key) in RECIPES.items():
        common.write_json(os.path.join(DATA, MOD, f"recipes/{item}.json"), {
            "type": "minecraft:crafting_shaped",
            "category": "tools",
            "key": {k: {"item": v} for k, v in key.items()},
            "pattern": pattern,
            "result": {"item": f"{MOD}:{item}", "count": 1},
        })
    common.upsert_lang(LANG)
    print("sweep assets written")


if __name__ == "__main__":
    main()
