#!/usr/bin/env python3
"""MTA-style sign blocks (`mta_sign` full height, `mta_sign_half`): models,
blockstates, textures, item icons, loot, recipes. Never hand-edit the output.

The panel is a plain black plate; the sign itself (bullets, text, arrows) is
painted by StationDecorRenderer.paintMtaSign through SignLayout, over the
plate's front (and, for hanging/standing mounts, back) face. The PLATE TABLE
below is a three-way contract with the renderer and MtaSignBlock's outline
shapes - change all three together.

Authored FACING north (viewer on -z, +z is "behind"; model +x is the
viewer's LEFT). Run merging: LEFT = neighbour at -x, RIGHT = neighbour at +x;
hanger rods / standing posts only at run ends (left=false / right=false).

    size  mount     plate y     plate z    double-sided
    full  wall      0..16       15..16     no
    half  wall      4..12       15..16     no
    full  hanging   0..16       7..9       yes
    half  hanging   4..12       7..9       yes   (rods 12..16 at the ends)
    full  standing  0..16       7..9       yes
    half  standing  8..16       7..9       yes   (posts 0..8 at the ends)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_el2_assets as K  # noqa: E402
import pngtool  # noqa: E402

MOD = K.MOD

PLATES = {
    ("full", "wall"): (0, 16, 15, 16, False),
    ("half", "wall"): (4, 12, 15, 16, False),
    ("full", "hanging"): (0, 16, 7, 9, True),
    ("half", "hanging"): (4, 12, 7, 9, True),
    ("full", "standing"): (0, 16, 7, 9, True),
    ("half", "standing"): (8, 16, 7, 9, True),
}

BLOCK_ID = {"full": "mta_sign", "half": "mta_sign_half"}


# ---------------------------------------------------------------- textures

def tex_plate():
    rows = K.canvas(16, (16, 16, 18))
    for y in range(16):
        for x in range(16):
            # faint enamel sheen on the top rows, nothing that would repeat across a run
            d = 4 if y < 2 else 0
            K.put(rows, x, y, K.shade((16, 16, 18), d))
    return rows


def tex_steel():
    rows = K.canvas(16, (74, 76, 80))
    for y in range(16):
        for x in range(16):
            d = {0: -18, 1: -8, 6: 10, 7: 14, 8: 10, 14: -8, 15: -18}.get(x, 0)
            K.put(rows, x, y, K.shade((74, 76, 80), d))
    return rows


# ------------------------------------------------------------------ models

def plate(y0, y1, z0, z1, wall):
    faces = ["north", "up", "down", "east", "west"] if wall else None
    cull = ["south"] if not wall else None
    el = K.box(0, y0, z0, 16, y1, z1, "#plate", uv=[0, 16 - y1, 16, 16 - y0], faces=faces,
               cull=None)
    if wall:
        # back is against the wall: keep it but cull it, so a floating plate still closes
        el["faces"]["south"] = {"uv": [0, 16 - y1, 16, 16 - y0], "texture": "#plate", "cullface": "south"}
    # the run: end faces only exist where the neighbour does not continue the plate,
    # but a coplanar end face buried inside the neighbour never shows, so keep them
    return [el]


def rod(x0, y0, y1):
    return [K.box(x0, y0, 7.5, x0 + 1, y1, 8.5, "#steel", uv=[0, 0, 1, y1 - y0])]


def build_models():
    tex = {"plate": "mta_sign_plate", "steel": "mta_sign_steel"}
    for (size, mount), (y0, y1, z0, z1, double) in PLATES.items():
        K.model(f"mta_sign_{size}_{mount}", plate(y0, y1, z0, z1, mount == "wall"), tex)
    # end fittings
    K.model("mta_sign_half_hanging_rod_neg", rod(1.5, 12, 16), tex)
    K.model("mta_sign_half_hanging_rod_pos", rod(13.5, 12, 16), tex)
    K.model("mta_sign_half_standing_post_neg", rod(1.5, 0, 8), tex)
    K.model("mta_sign_half_standing_post_pos", rod(13.5, 0, 8), tex)
    # item models: a hanging plate with both rods / the full plate
    K.model("mta_sign_item", plate(0, 16, 7, 9, False), tex)
    K.model("mta_sign_half_item", plate(4, 12, 7, 9, False) + rod(1.5, 12, 16) + rod(13.5, 12, 16), tex)


def build_blockstates():
    for size, block in BLOCK_ID.items():
        parts = []
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            def ap(m):
                a = {"model": f"{MOD}:block/{m}"}
                if rot:
                    a["y"] = rot
                return a
            for mount in ("wall", "hanging", "standing"):
                parts.append({"when": {"facing": facing, "mount": mount},
                              "apply": ap(f"mta_sign_{size}_{mount}")})
            if size == "half":
                parts.append({"when": {"facing": facing, "mount": "hanging", "left": "false"},
                              "apply": ap("mta_sign_half_hanging_rod_neg")})
                parts.append({"when": {"facing": facing, "mount": "hanging", "right": "false"},
                              "apply": ap("mta_sign_half_hanging_rod_pos")})
                parts.append({"when": {"facing": facing, "mount": "standing", "left": "false"},
                              "apply": ap("mta_sign_half_standing_post_neg")})
                parts.append({"when": {"facing": facing, "mount": "standing", "right": "false"},
                              "apply": ap("mta_sign_half_standing_post_pos")})
        K.write_json(os.path.join(K.BLOCKSTATES, f"{block}.json"), {"multipart": parts})
        K.write_json(os.path.join(K.ITEM_MODELS, f"{block}.json"), {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{MOD}:item/{block}"}})
        K.write_json(os.path.join(K.DATA, f"loot_tables/blocks/{block}.json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
        K.write_json(os.path.join(K.DATA, f"recipes/{block}.json"), {
            "type": "minecraft:crafting_shaped", "category": "building",
            "key": {"I": {"item": "minecraft:iron_ingot"}, "B": {"item": "minecraft:black_dye"},
                    "P": {"item": "minecraft:paper"}},
            "pattern": ["IBI", "IPI"] if size == "full" else ["I I", "BPB"],
            "result": {"item": f"{MOD}:{block}", "count": 2}})


# -------------------------------------------------------------- item icons

BLACK_I = (18, 18, 20, 255)
WHITE_I = (240, 240, 240, 255)
STEEL_I = (120, 122, 126, 255)
RED_I = (238, 53, 46, 255)
GREEN_I = (0, 147, 60, 255)
CLEAR = (0, 0, 0, 0)


def icon(size):
    r = [[CLEAR] * 32 for _ in range(32)]

    def rect(x0, y0, x1, y1, c):
        for y in range(max(0, y0), min(32, y1)):
            for x in range(max(0, x0), min(32, x1)):
                r[y][x] = c

    def disc(cx, cy, rad, c):
        for y in range(32):
            for x in range(32):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= rad * rad:
                    r[y][x] = c

    if size == "full":
        rect(2, 3, 30, 29, BLACK_I)
        disc(9, 11, 4.2, RED_I)
        disc(19, 11, 4.2, GREEN_I)
        rect(5, 19, 21, 21, WHITE_I)
        rect(5, 24, 15, 26, WHITE_I)
        rect(24, 19, 28, 21, WHITE_I)     # arrow shaft
        rect(26, 17, 28, 23, WHITE_I)     # arrow head (blocky)
    else:
        rect(4, 2, 5, 10, STEEL_I)
        rect(27, 2, 28, 10, STEEL_I)
        rect(2, 10, 30, 24, BLACK_I)
        disc(9, 17, 4.2, RED_I)
        rect(16, 15, 26, 17, WHITE_I)
        rect(16, 19, 22, 20, WHITE_I)
    return r


def build_icons():
    icon_dir = os.path.join(K.ASSETS, "textures/item")
    os.makedirs(icon_dir, exist_ok=True)
    for size, block in BLOCK_ID.items():
        pngtool.write_png(os.path.join(icon_dir, f"{block}.png"), icon(size))


def main():
    K.write_png("mta_sign_plate", tex_plate())
    K.write_png("mta_sign_steel", tex_steel())
    build_models()
    build_blockstates()
    build_icons()
    print("mta sign assets written")


if __name__ == "__main__":
    main()
