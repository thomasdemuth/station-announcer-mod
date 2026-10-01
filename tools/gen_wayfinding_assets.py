#!/usr/bin/env python3
"""Assets for the wayfinding markers (exit_marker, place_marker).

The blocks render NOTHING themselves (BlockRenderType.INVISIBLE; the pin is a
block entity renderer shown only while a revealing tool is held), so each
block model is particle-only. What the player sees in the inventory is a flat
map-pin icon: green with a doorway for exits, amber with a dot for places.

Generated — never hand-edit the outputs. Run from the repo root:
    python3 tools/gen_wayfinding_assets.py
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import pngtool  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "station_announcer")
DATA = os.path.join(ROOT, "data", "station_announcer")

BLOCKS = {
    # id: (pin colour, symbol, recipe dye)
    "exit_marker": ((30, 142, 62), "door", "minecraft:green_dye"),
    "place_marker": ((224, 162, 27), "dot", "minecraft:orange_dye"),
}


def pin_icon(color, symbol):
    """16x16 map pin: a disc on a point, dark outline, white symbol."""
    size = 16
    cx, cy, r = 7.5, 6.0, 5.2
    rows = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]

    def inside(x, y):
        px, py = x + 0.5, y + 0.5
        if (px - cx) ** 2 + (py - cy) ** 2 <= r * r:
            return True
        # the point: a triangle from the disc's lower half down to (cx, 15)
        if py >= cy and py <= 15.2:
            half = r * (15.2 - py) / (15.2 - cy) * 0.78
            return abs(px - cx) <= half
        return False

    dark = tuple(int(c * 0.45) for c in color)
    light = tuple(min(255, int(c * 1.25 + 25)) for c in color)
    for y in range(size):
        for x in range(size):
            if not inside(x, y):
                continue
            edge = any(not inside(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                rows[y][x] = dark + (255,)
            else:
                # soft top-left light
                t = max(0.0, 1.0 - math.hypot(x + 0.5 - (cx - 2), y + 0.5 - (cy - 2)) / 7.0)
                c = tuple(int(color[i] + (light[i] - color[i]) * t * 0.8) for i in range(3))
                rows[y][x] = c + (255,)

    white = (255, 255, 255, 255)
    if symbol == "door":
        # doorway frame with an open leaf, the universal "exit"
        for y in range(3, 9):
            rows[y][5] = white
            rows[y][10] = white
        for x in range(5, 11):
            rows[3][x] = white
        for y in range(4, 9):
            rows[y][8] = white
            rows[y][9] = white
    else:
        for y in range(4, 9):
            for x in range(5, 11):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= 5.0:
                    rows[y][x] = white
    return rows


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


def main():
    for block, (color, symbol, dye) in BLOCKS.items():
        tex = os.path.join(ASSETS, "textures", "item", block + ".png")
        os.makedirs(os.path.dirname(tex), exist_ok=True)
        pngtool.write_png(tex, pin_icon(color, symbol))
        write_json(os.path.join(ASSETS, "blockstates", block + ".json"),
                   {"variants": {"": {"model": "station_announcer:block/" + block}}})
        # particle-only: the block is INVISIBLE, the pin is a block entity renderer
        write_json(os.path.join(ASSETS, "models", "block", block + ".json"),
                   {"textures": {"particle": "station_announcer:item/" + block}})
        write_json(os.path.join(ASSETS, "models", "item", block + ".json"),
                   {"parent": "minecraft:item/generated",
                    "textures": {"layer0": "station_announcer:item/" + block}})
        write_json(os.path.join(DATA, "loot_tables", "blocks", block + ".json"), {
            "type": "minecraft:block",
            "pools": [{
                "rolls": 1,
                "entries": [{"type": "minecraft:item", "name": "station_announcer:" + block}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }],
        })
        write_json(os.path.join(DATA, "recipes", block + ".json"), {
            "type": "minecraft:crafting_shapeless",
            "category": "misc",
            "ingredients": [{"item": "minecraft:paper"}, {"item": dye}, {"item": "minecraft:compass"}],
            "result": {"item": "station_announcer:" + block, "count": 4},
        })
    print("wayfinding assets written:", ", ".join(BLOCKS))


if __name__ == "__main__":
    main()
