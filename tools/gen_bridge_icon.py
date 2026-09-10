#!/usr/bin/env python3
"""Item icon + model for the Bridge Creator (a stone arch viaduct silhouette over water)."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
import pixel_kit as K
import pngtool

ROOT = os.path.join(os.path.dirname(__file__), "..", "src/main/resources/assets/station_announcer")
STONE = (168, 160, 150, 255)
STONE_D = (118, 112, 104, 255)
STONE_L = (206, 200, 190, 255)
WATER = (52, 108, 190, 255)
WATER_L = (92, 150, 220, 255)
RAIL = (70, 60, 50, 255)
GOLD = (255, 214, 60, 255)
GOLD_D = (120, 90, 20, 255)


def main():
    r = K.canvas(32, 32, (0, 0, 0, 0))
    # water
    K.rect(r, 0, 24, 32, 32, WATER)
    for x in range(1, 32, 6):
        K.rect(r, x, 26, x + 3, 27, WATER_L)
    # deck + parapet
    K.rect(r, 0, 6, 32, 12, STONE)
    K.rect(r, 0, 6, 32, 7, STONE_L)
    K.rect(r, 0, 11, 32, 12, STONE_D)
    K.rect(r, 0, 4, 32, 6, STONE_D)
    # rails on top
    K.rect(r, 0, 3, 32, 4, RAIL)
    for x in range(1, 32, 4):
        K.rect(r, x, 2, x + 1, 5, RAIL)
    # piers and arches: three arches
    K.rect(r, 0, 12, 32, 25, STONE)
    for cx in (5, 16, 27):
        # open arch: a rounded hole down to the water
        for y in range(13, 25):
            dy = y - 17
            half = 4 if dy >= 0 else int((16 - dy * dy) ** 0.5)
            K.rect(r, cx - half, y, cx + half + 1, y + 1, (0, 0, 0, 0))
    K.rect(r, 0, 12, 32, 13, STONE_D)
    # pier shading
    for px in (10, 21):
        K.rect(r, px, 13, px + 1, 25, STONE_D)
    # gear badge = "creator"
    K.rect(r, 26, 24, 31, 29, GOLD)
    K.rect(r, 27, 25, 30, 28, GOLD_D)
    icon_dir = os.path.join(ROOT, "textures/item")
    os.makedirs(icon_dir, exist_ok=True)
    pngtool.write_png(os.path.join(icon_dir, "bridge_creator.png"), [[K.rgba(px) for px in row] for row in r])
    with open(os.path.join(ROOT, "models/item/bridge_creator.json"), "w") as f:
        f.write('{\n  "parent": "minecraft:item/generated",\n  "textures": {\n    "layer0": "station_announcer:item/bridge_creator"\n  }\n}\n')
    print("wrote bridge_creator icon + model")


if __name__ == "__main__":
    main()
