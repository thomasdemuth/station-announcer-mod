#!/usr/bin/env python3
"""Generates every asset for the NYC subway stair family: `subway_stairs`
(modern: mesh risers, diamond-plate treads with a grip nosing), and
`subway_stairs_old` (dirty concrete), the black `subway_stair_divider`
stringer, and the eight-mode `subway_handrail`.

Run from anywhere:  python3 tools/gen_stair_assets.py [--preview DIR]

Rules honoured (learned elsewhere in this repo):
- Explicit uv on every face; interior slices only (atlas-bleed lesson).
- The mesh riser texture is CUTOUT — subway_stairs is registered with
  getCutoutMipped() in StationAnnouncerClient.
- Up faces map v toward +z, so the tread texture's grip band is drawn at
  the BOTTOM rows of the sampled window to land on the step's leading edge.
- Yellow safety ends are separate baked MODELS (plain/bottom/top/both)
  picked by blockstate — never overlay quads (coplanar z-fighting).
- Diagonal geometry is element rotation 45 with rescale, the octagonal
  tube is the core + 45-rotated twin (turnstile tubing's trick).
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data")
MOD = "station_announcer"

# ---------------------------------------------------------------- palette --
GALV = (158, 161, 163, 255)
GALV_LIT = (192, 195, 197, 255)
GALV_DARK = (122, 125, 127, 255)
GALV_SHADOW = (92, 95, 97, 255)
GRIP_DARK = (66, 68, 70, 255)
YELLOW = (222, 186, 24, 255)
YELLOW_LIT = (240, 208, 60, 255)
YELLOW_DARK = (170, 138, 12, 255)
CONC = (128, 118, 104, 255)         # old dirty brown-grey concrete
CONC_LIT = (146, 137, 124, 255)
CONC_DARK = (104, 95, 83, 255)
CONC_GRIME = (78, 71, 62, 255)
BLACK_STEEL = (30, 31, 33, 255)
BLACK_STEEL_LIT = (52, 54, 57, 255)
RAIL = (170, 173, 176, 255)
RAIL_LIT = (214, 217, 220, 255)
RAIL_DARK = (120, 123, 126, 255)
HOLE = (0, 0, 0, 0)


def speckle(rows, x0, y0, x1, y1, colour, salt, density=7):
    """Deterministic grime specks (no RNG — reproducible builds)."""
    for y in range(y0, y1):
        for x in range(x0, x1):
            if (x * 31 + y * 17 + salt) % 97 < density:
                rows[y][x] = colour


def tex_tread(yellow, old):
    """32x32. Rows 0..16: unused backing. Rows 16..32: the tread's top-face
    band — plate pattern with the serrated grip strip in rows 26..32 (the
    window's bottom = the step's leading edge). Yellow variants paint the
    leading half as the safety nosing."""
    base = CONC if old else GALV
    rows = pk.canvas(32, 32, base)
    if old:
        speckle(rows, 0, 0, 32, 32, CONC_DARK, 3, 16)
        speckle(rows, 0, 0, 32, 32, CONC_GRIME, 11, 8)
        speckle(rows, 0, 0, 32, 32, CONC_LIT, 23, 10)
    else:
        # diamond-plate lugs: short 45-degree dashes, alternating direction
        for y in range(0, 32, 4):
            for x in range(0, 32, 4):
                phase = ((x + y) // 4) % 2
                for i in range(3):
                    xx = x + i
                    yy = y + (i if phase else 2 - i)
                    if xx < 32 and yy < 32:
                        rows[yy][xx] = GALV_LIT
                        if yy + 1 < 32:
                            rows[yy + 1][xx] = GALV_DARK
    if yellow:
        # the end steps are painted yellow ACROSS THE WHOLE TREAD (Thomas:
        # "extend to the edge of the block") — nothing grey survives
        for y in range(32):
            for x in range(32):
                rows[y][x] = YELLOW
        speckle(rows, 0, 0, 32, 32, YELLOW_DARK, 7, 12 if old else 6)
        speckle(rows, 0, 0, 32, 32, YELLOW_LIT, 19, 8)
    # grip strip: fine serration on the leading edge rows 27..32 — one dark
    # tick column in four over the base tone (the first cut's 2:2 near-black
    # zebra read as a barcode from any distance)
    base_tone = YELLOW if yellow else (CONC if old else GALV)
    tick = YELLOW_DARK if yellow else (CONC_GRIME if old else GRIP_DARK)
    edge_lit = YELLOW_LIT if yellow else (CONC_LIT if old else GALV_LIT)
    for y in range(27, 32):
        for x in range(32):
            rows[y][x] = tick if x % 4 == 0 else base_tone
    for x in range(32):
        rows[27][x] = edge_lit if x % 4 else tick
    return rows


def tex_riser(kind):
    """32x32 riser texture. kind: mesh (cutout), mesh_yellow (painted solid),
    old (solid dirty), old_yellow."""
    if kind == "mesh":
        rows = pk.canvas(32, 32, HOLE)
        # galvanized frame border + woven mesh with 2px holes on a 4px grid
        for y in range(32):
            for x in range(32):
                border = x < 2 or x >= 30 or y < 3 or y >= 29
                strand = (x % 4) < 2 or (y % 4) < 2
                if border:
                    rows[y][x] = GALV_SHADOW if (x + y) % 7 else GALV_DARK
                elif strand:
                    # mid-dark weave so the mesh reads as metal, not plaid
                    rows[y][x] = GALV_DARK if (x % 4) < 2 and (y % 4) < 2 else \
                        (GALV if (y % 4) < 2 else GALV_SHADOW)
        return rows
    base = YELLOW if "yellow" in kind else (CONC_DARK if kind.startswith("old") else GALV)
    rows = pk.canvas(32, 32, base)
    if kind == "old":
        speckle(rows, 0, 0, 32, 32, CONC_GRIME, 5, 18)
        speckle(rows, 0, 0, 32, 32, CONC, 13, 12)
    elif kind == "old_yellow":
        speckle(rows, 0, 0, 32, 32, YELLOW_DARK, 9, 16)
        speckle(rows, 0, 0, 32, 32, CONC_GRIME, 27, 7)
    elif kind == "mesh_yellow":
        # painted mesh: the weave shows through the paint as shading
        for y in range(32):
            for x in range(32):
                if not (x < 2 or x >= 30 or y < 3 or y >= 29) and \
                        not ((x % 4) < 2 or (y % 4) < 2):
                    rows[y][x] = YELLOW_DARK
        speckle(rows, 0, 0, 32, 32, YELLOW_LIT, 15, 8)
    return rows


def tex_side(old):
    """Stringer side panel: vertical-streak steel or dirty concrete."""
    rows = pk.canvas(32, 32, CONC if old else GALV)
    if old:
        speckle(rows, 0, 0, 32, 32, CONC_DARK, 4, 14)
        speckle(rows, 0, 0, 32, 32, CONC_GRIME, 21, 9)
    else:
        tones = [GALV, GALV_LIT, GALV, GALV_DARK, GALV, GALV, GALV_LIT, GALV]
        for x in range(32):
            t = tones[(x * 5) % len(tones)]
            for y in range(32):
                rows[y][x] = t
    return rows


def tex_divider():
    rows = pk.canvas(32, 32, BLACK_STEEL)
    tones = [BLACK_STEEL, BLACK_STEEL_LIT, BLACK_STEEL, BLACK_STEEL,
             (24, 25, 27, 255), BLACK_STEEL]
    for x in range(32):
        t = tones[(x * 3) % len(tones)]
        for y in range(32):
            rows[y][x] = t
    return rows


def tex_handrail():
    """Tube barrel gradient (turnstile-tube layout: bright crown, shadow
    keel) — faces slice at their own height."""
    rows = pk.canvas(32, 32, RAIL)
    for y in range(32):
        if y < 5:
            t = RAIL_LIT
        elif y < 22:
            t = RAIL
        else:
            t = RAIL_DARK
        for x in range(32):
            rows[y][x] = t
    return rows


def icon_handrail():
    rows = pk.canvas(16, 16, HOLE)
    pk.rect(rows, 1, 4, 15, 6, RAIL)
    pk.rect(rows, 1, 4, 15, 5, RAIL_LIT)
    pk.rect(rows, 7, 6, 9, 15, RAIL_DARK)
    pk.rect(rows, 5, 15, 11, 16, RAIL_DARK)
    return rows


# ------------------------------------------------------------------ models --
def wj(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


def f(tex, uv):
    return {"texture": "#" + tex, "uv": list(uv)}


def elem(frm, to, faces, rotation=None):
    out = {"from": list(frm), "to": list(to), "faces": faces}
    if rotation:
        out["rotation"] = rotation
    return out


def model(name, textures, elements):
    wj(os.path.join(ASSETS, "models/block", name + ".json"),
       {"parent": "minecraft:block/block", "textures": textures, "elements": elements})


def solid_stair_elements(yellow_bottom, yellow_top, x0=0, x1=16):
    """Old-style solid concrete steps, ascending toward NORTH. The x range is
    parameterized so the divider reuses it for its side wings."""
    w = x1 - x0
    els = []
    els.append(elem([x0, 0, 8], [x1, 8, 16], {
        "up": f("tread_b" if yellow_bottom else "tread", [0.25, 8.25, 0.25 + min(w, 15.5), 15.75]),
        "south": f("riser_b" if yellow_bottom else "riser", [0.25, 8.5, 0.25 + min(w, 15.5), 15.5]),
        "east": f("side", [0.25, 8.25, 8, 15.75]),
        "west": f("side", [8, 8.25, 15.75, 15.75]),
        "down": f("side", [0.25, 8.25, 0.25 + min(w, 15.5), 15.75]),
    }))
    els.append(elem([x0, 8, 0], [x1, 16, 8], {
        "up": f("tread_t" if yellow_top else "tread", [0.25, 8.25, 0.25 + min(w, 15.5), 15.75]),
        "south": f("riser_t" if yellow_top else "riser", [0.25, 8.5, 0.25 + min(w, 15.5), 15.5]),
        "north": f("side", [0.25, 0.25, 0.25 + min(w, 15.5), 8]),
        "east": f("side", [8, 0.25, 15.75, 8]),
        "west": f("side", [0.25, 0.25, 8, 8]),
    }))
    els.append(elem([x0, 0, 0], [x1, 8, 8], {
        "north": f("side", [0.25, 8.25, 0.25 + min(w, 15.5), 15.75]),
        "east": f("side", [0.25, 8.25, 8, 15.75]),
        "west": f("side", [8, 8.25, 15.75, 15.75]),
        "down": f("side", [0.25, 0.25, 0.25 + min(w, 15.5), 8]),
    }))
    return els


def thin_stair_elements(yellow_bottom, yellow_top, x0=0, x1=16):
    """Modern FLOATING steel steps (Thomas: see through the mesh from the
    back, 1 px deep stairs): each step is a 1 px tread pan plus a 1 px mesh
    riser panel; no filler, no soffit — daylight underneath. Collision stays
    the solid stair shape, so walking is unchanged. Buried faces (riser tops
    under tread pans) are omitted, never coplanar-drawn."""
    w = x1 - x0
    wu = 0.25 + min(w, 15.5)
    edge = f("side", [0.25, 8.25, 1.25, 15.75])
    els = []
    # lower tread pan (top surface at y8)
    els.append(elem([x0, 7, 8], [x1, 8, 16], {
        "up": f("tread_b" if yellow_bottom else "tread", [0.25, 8.25, wu, 15.75]),
        "down": f("side", [0.25, 8.25, wu, 15.75]),
        "north": f("side", [0.25, 8.25, wu, 9.25]),
        "south": f("side", [0.25, 8.25, wu, 9.25]),
        "east": edge, "west": edge,
    }))
    # lower riser mesh at the leading edge (top buried under the pan)
    els.append(elem([x0, 0, 15], [x1, 7, 16], {
        "north": f("riser_b" if yellow_bottom else "riser", [0.25, 8.5, wu, 15.5]),
        "south": f("riser_b" if yellow_bottom else "riser", [0.25, 8.5, wu, 15.5]),
        "down": f("side", [0.25, 8.25, wu, 9.25]),
        "east": edge, "west": edge,
    }))
    # upper tread pan (top surface at y16)
    els.append(elem([x0, 15, 0], [x1, 16, 8], {
        "up": f("tread_t" if yellow_top else "tread", [0.25, 8.25, wu, 15.75]),
        "down": f("side", [0.25, 8.25, wu, 15.75]),
        "north": f("side", [0.25, 8.25, wu, 9.25]),
        "south": f("side", [0.25, 8.25, wu, 9.25]),
        "east": edge, "west": edge,
    }))
    # mid riser mesh directly under the upper pan's leading edge: top fully
    # buried under the pan (z0..8 covers z7..8) and the south face at z8 is
    # coplanar-continuous with the lower pan's north face — a straddled riser
    # (z7.5..8.5) left a 0.5px strip of omitted top face EXPOSED past the pan,
    # which read as a see-through slit under every tread
    els.append(elem([x0, 8, 7], [x1, 15, 8], {
        "north": f("riser_t" if yellow_top else "riser", [0.25, 8.5, wu, 15.5]),
        "south": f("riser_t" if yellow_top else "riser", [0.25, 8.5, wu, 15.5]),
        "down": f("side", [0.25, 8.25, wu, 9.25]),
        "east": edge, "west": edge,
    }))
    return els


def write_stairs(prefix, old):
    """Four baked variants: plain / bottom / top / both."""
    def textures(yellow_bottom, yellow_top):
        base = {
            "tread": f"{MOD}:block/{prefix}_tread",
            "riser": f"{MOD}:block/{prefix}_riser",
            "side": f"{MOD}:block/{prefix}_side",
            "particle": f"{MOD}:block/{prefix}_side",
        }
        base["tread_b"] = f"{MOD}:block/{prefix}_tread_yellow" if yellow_bottom else base["tread"]
        base["riser_b"] = f"{MOD}:block/{prefix}_riser_yellow" if yellow_bottom else base["riser"]
        base["tread_t"] = f"{MOD}:block/{prefix}_tread_yellow" if yellow_top else base["tread"]
        base["riser_t"] = f"{MOD}:block/{prefix}_riser_yellow" if yellow_top else base["riser"]
        return base
    builder = solid_stair_elements if old else thin_stair_elements
    for suffix, yb, yt in (("plain", False, False), ("bottom", True, False),
                           ("top", False, True), ("both", True, True)):
        model(f"{prefix}_{suffix}", textures(yb, yt), builder(yb, yt))


DIV_TEX = {"steel": f"{MOD}:block/stair_divider_steel",
           "particle": f"{MOD}:block/stair_divider_steel"}


def steel_faces(z0, z1):
    lu = 0.25 + min(z1 - z0, 15.5)
    return {
        "east": f("steel", [0.25, 4.5, lu, 11.5]),
        "west": f("steel", [0.25, 4.5, lu, 11.5]),
        "up": f("steel", [0.25, 7.25, lu, 8.75]),
        "down": f("steel", [0.25, 7.25, lu, 8.75]),
        "north": f("steel", [7.25, 4.5, 8.75, 11.5]),
        "south": f("steel", [7.25, 4.5, 8.75, 11.5]),
    }


def beam(z0, z1):
    """A stretch of the diagonal. Rotated 45 with rescale about the block
    center, an authored z range [z0,z1] lands on the matching stretch of the
    corner-to-corner diagonal: the full 0..16 beam overshoots BOTH block
    corners by 3.5 px (that overshoot is what bridges mid-run blocks), a
    3.5..16 beam ends flush at the TOP face, a 0..12.5 one flush at the
    floor — the clean ends Thomas asked for."""
    return elem([7.25, 4.5, z0], [8.75, 11.5, z1], steel_faces(z0, z1),
                rotation={"origin": [8, 8, 8], "axis": "x", "angle": 45, "rescale": True})


def box(frm, to):
    return elem(frm, to, steel_faces(frm[2], to[2]))


def write_divider():
    """Four beam variants (mid/bottom/top/both) and the stair-extension
    wings the LEFT/RIGHT properties switch on."""
    cap = box([7.25, 11, 0], [8.75, 16, 7.5])       # level top landing cap
    foot = box([7.25, 0, 9], [8.75, 6, 16])         # vertical foot to the floor
    model("subway_stair_divider_mid", DIV_TEX, [beam(0, 16)])
    model("subway_stair_divider_bottom", DIV_TEX, [beam(0, 12.5), foot])
    model("subway_stair_divider_top", DIV_TEX, [beam(3.5, 16), cap])
    model("subway_stair_divider_both", DIV_TEX,
          [beam(3.5, 12.5), json.loads(json.dumps(cap)), json.loads(json.dumps(foot))])
    # Side wings: the neighbouring stair's steps carried to the plate. Left =
    # west of a north-ascending run; right is the mirrored copy.
    def wing_textures(prefix):
        t = {"tread": f"{MOD}:block/{prefix}_tread", "riser": f"{MOD}:block/{prefix}_riser",
             "side": f"{MOD}:block/{prefix}_side", "particle": DIV_TEX["particle"]}
        t["tread_b"] = t["tread_t"] = t["tread"]
        t["riser_b"] = t["riser_t"] = t["riser"]
        return t
    for style, builder in (("modern", thin_stair_elements), ("old", solid_stair_elements)):
        prefix = "subway_stairs" if style == "modern" else "subway_stairs_old"
        left = builder(False, False, 0, 7.25)
        model(f"subway_stair_divider_wing_{style}_left", wing_textures(prefix), left)
        right = [mirror_x(json.loads(json.dumps(e))) for e in left]
        model(f"subway_stair_divider_wing_{style}_right", wing_textures(prefix), right)


HR_TEX = {"rail": f"{MOD}:block/handrail_steel",
          "particle": f"{MOD}:block/handrail_steel"}


def tube(frm, to, axis, rotation=None):
    """Octagonal tube: core + 45-rotated twin about its own long axis. When
    the whole tube is itself rotated (slopes), only the core rotates — the
    twin would need compound rotation vanilla doesn't have, so slopes read
    square; at 2 px nobody can tell."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    length = {"x": x1 - x0, "y": y1 - y0, "z": z1 - z0}[axis]
    lu = min(15.5, length)
    side = f("rail", [0.25, 8, 0.25 + lu, 10])
    cap = f("rail", [2, 2, 4, 4])
    sides_by_axis = {
        "x": ("north", "south", "up", "down", "east", "west"),
        "y": ("north", "south", "east", "west", "up", "down"),
        "z": ("east", "west", "up", "down", "north", "south"),
    }
    names = sides_by_axis[axis]
    faces = {n: side for n in names[:4]}
    faces[names[4]] = cap
    faces[names[5]] = cap
    core = elem(frm, to, faces, rotation)
    if rotation:
        return [core]
    twin = elem(frm, to, {n: side for n in names[:4]},
                {"origin": [(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2],
                 "axis": axis, "angle": 45})
    return [core, twin]


def post(x, z, y0, y1):
    return elem([x - 1, y0, z - 1], [x + 1, y1, z + 1], {
        "north": f("rail", [0.25, 8, 2.25, 10]),
        "south": f("rail", [0.25, 8, 2.25, 10]),
        "east": f("rail", [0.25, 8, 2.25, 10]),
        "west": f("rail", [0.25, 8, 2.25, 10]),
    })


def foot(x, z):
    return elem([x - 2, 0, z - 2], [x + 2, 1, z + 2], {
        "north": f("rail", [0.25, 12, 4.25, 12.5]),
        "south": f("rail", [0.25, 12, 4.25, 12.5]),
        "east": f("rail", [0.25, 12, 4.25, 12.5]),
        "west": f("rail", [0.25, 12, 4.25, 12.5]),
        "up": f("rail", [0.25, 12, 4.25, 14]),
    })


def slope_rotation(cy):
    return {"origin": [8, cy, 8], "axis": "x", "angle": 45, "rescale": True}


def slope_tube(x0, x1, z0, z1):
    """A sloped rail stretch: authored [z0,z1] at rail height, rotated 45
    rescale about (*, 14.5, 8) so a center-line point maps to
    y = 22.5 - z: a full 0..16 slope runs 6.5 -> 22.5 (chaining one block up
    per block forward), and the transitions' half-slopes meet the flat rail
    (center 14.5) exactly at the block middle."""
    return tube([x0, 13.5, z0], [x1, 15.5, z1], "z",
                {"origin": [(x0 + x1) / 2, 14.5, 8], "axis": "x", "angle": 45, "rescale": True})


def rail(x0, x1, z0, z1):
    return tube([x0, 13.5, z0], [x1, 15.5, z1], "z")


def xrail(x0, x1, z0, z1):
    return tube([x0, 13.5, z0], [x1, 15.5, z1], "x")


def crossarm(y0=12.3, z0=7, z1=9):
    return elem([3, y0, z0], [13, y0 + 1.2, z1], {
        "north": f("rail", [3, 8, 13, 9.2]),
        "south": f("rail", [3, 8, 13, 9.2]),
        "up": f("rail", [3, 8, 13, 10]),
        "down": f("rail", [3, 8, 13, 10]),
        "east": f("rail", [7, 8, 9, 9.2]),
        "west": f("rail", [7, 8, 9, 9.2]),
    })


def wall_bracket(z, y):
    """Arm + fixing plate reaching into the EAST wall (x16) at height y."""
    return [elem([13.8, y - 0.75, z - 1], [16, y + 0.75, z + 1], {
                "north": f("rail", [0.25, 8, 2.5, 9.5]),
                "south": f("rail", [0.25, 8, 2.5, 9.5]),
                "up": f("rail", [0.25, 8, 2.5, 10]),
                "down": f("rail", [0.25, 8, 2.5, 10]),
                "west": f("rail", [7, 8, 9, 9.5]),
            }),
            elem([15.25, y - 2, z - 1.5], [16, y + 2, z + 1.5], {
                "north": f("rail", [0.25, 8, 1, 12]),
                "south": f("rail", [0.25, 8, 1, 12]),
                "up": f("rail", [0.25, 8, 3.25, 8.75]),
                "down": f("rail", [0.25, 8, 3.25, 8.75]),
                "west": f("rail", [0.25, 8, 3.25, 12]),
            })]


def north_wall_bracket(x, y=13.75):
    """Arm + plate into the NORTH wall (z0), for the flat wall rail."""
    return [elem([x - 1, y - 0.75, 0], [x + 1, y + 0.75, 3], {
                "east": f("rail", [0.25, 8, 3.25, 9.5]),
                "west": f("rail", [0.25, 8, 3.25, 9.5]),
                "up": f("rail", [0.25, 8, 2.25, 11]),
                "down": f("rail", [0.25, 8, 2.25, 11]),
                "south": f("rail", [0.25, 8, 2.25, 9.5]),
            }),
            elem([x - 1.5, y - 1.75, 0], [x + 1.5, y + 2.25, 0.75], {
                "east": f("rail", [0.25, 8, 1, 12]),
                "west": f("rail", [0.25, 8, 1, 12]),
                "up": f("rail", [0.25, 8, 3.25, 8.75]),
                "down": f("rail", [0.25, 8, 3.25, 8.75]),
                "south": f("rail", [0.25, 8, 3.25, 12]),
            })]


def west_wall_bracket(z, y=13.75):
    return [mirror_x(json.loads(json.dumps(e))) for e in wall_bracket(z, y)]


def handrail_models():
    """style -> variant -> element list. Authored facing NORTH: rails run
    along z, slopes ascend north, corners enter from the south and turn.
    corner_right variants are mirrored copies of corner_left."""
    out = {}

    def corner_single(x_rail=(7, 9)):
        x0, x1 = x_rail
        return (rail(x0, x1, x0, 16) + xrail(0, x1, x0, x1))

    # ---- floating ----
    floating = {
        "flat": rail(7, 9, 0, 16),
        "slope": slope_tube(7, 9, 0, 16),
        "slope_bottom": rail(7, 9, 8, 16) + slope_tube(7, 9, 0, 8),
        "slope_top": rail(7, 9, 0, 8) + slope_tube(7, 9, 8, 16),
        "corner_left": corner_single(),
    }
    out["floating"] = floating

    # ---- standing: floating + post/foot ----
    out["standing"] = {
        "flat": rail(7, 9, 0, 16) + [post(8, 8, 1, 13.5), foot(8, 8)],
        "slope": slope_tube(7, 9, 0, 16) + [post(8, 8, 0, 13.5), foot(8, 8)],
        "slope_bottom": rail(7, 9, 8, 16) + slope_tube(7, 9, 0, 8)
            + [post(8, 12, 1, 13.5), foot(8, 12)],
        "slope_top": rail(7, 9, 0, 8) + slope_tube(7, 9, 8, 16)
            + [post(8, 4, 1, 13.5), foot(8, 4)],
        "corner_left": corner_single() + [post(8, 8, 1, 13.5), foot(8, 8)],
    }

    # ---- double: twin rails, center post; transitions close in a U ----
    out["double"] = {
        "flat": rail(3, 5, 0, 16) + rail(11, 13, 0, 16)
            + [crossarm(), post(8, 8, 1, 12.3), foot(8, 8)],
        "slope": slope_tube(3, 5, 0, 16) + slope_tube(11, 13, 0, 16)
            + [crossarm(11.8), post(8, 8, 0, 11.8), foot(8, 8)],
        # bottom of the run: level out, then the twin rails wrap around in a
        # U at the downhill end (the photos' curled center-rail end)
        "slope_bottom": rail(3, 5, 8, 14) + rail(11, 13, 8, 14)
            + slope_tube(3, 5, 0, 8) + slope_tube(11, 13, 0, 8)
            + xrail(3, 13, 13.5, 15.5)
            + [post(8, 11, 1, 13.5), foot(8, 11)],
        "slope_top": rail(3, 5, 2, 8) + rail(11, 13, 2, 8)
            + slope_tube(3, 5, 8, 16) + slope_tube(11, 13, 8, 16)
            + xrail(3, 13, 0.5, 2.5)
            + [post(8, 5, 1, 13.5), foot(8, 5)],
        "corner_left": rail(3, 5, 12, 16) + xrail(0, 5, 11, 13)
            + rail(11, 13, 4, 16) + xrail(0, 13, 3, 5)
            + [post(8, 8, 1, 13.5), foot(8, 8)],
    }

    # ---- wall: flat/corners hug the wall FACING points into; slopes run
    # beside the EAST wall (mirror model swaps to west) ----
    slope_brk = wall_bracket(4, 18.5) + wall_bracket(12, 10.5)
    out["wall"] = {
        "flat": xrail(0, 16, 3, 5) + north_wall_bracket(4) + north_wall_bracket(12),
        "slope": slope_tube(13, 15, 0, 16) + slope_brk,
        "slope_bottom": rail(13, 15, 8, 16) + slope_tube(13, 15, 0, 8)
            + wall_bracket(4, 18.5) + wall_bracket(12, 14.5),
        "slope_top": rail(13, 15, 0, 8) + slope_tube(13, 15, 8, 16)
            + wall_bracket(4, 14.5) + wall_bracket(12, 10.5),
        "corner_left": xrail(3, 16, 3, 5) + rail(3, 5, 5, 16)
            + north_wall_bracket(12) + west_wall_bracket(12),
    }

    for style, variants in out.items():
        variants["corner_right"] = [mirror_x(json.loads(json.dumps(e)))
                                    for e in variants["corner_left"]]
    return out


def write_handrails():
    models = handrail_models()
    for style, variants in models.items():
        for variant, els in variants.items():
            model(f"subway_handrail_{style}_{variant}", HR_TEX, els)
    # the wall style's sloped variants mirror to put brackets on the other side
    for variant in ("slope", "slope_bottom", "slope_top"):
        mirrored = [mirror_x(json.loads(json.dumps(e)))
                    for e in models["wall"][variant]]
        model(f"subway_handrail_wall_{variant}_mirror", HR_TEX, mirrored)


def mirror_x(element):
    """Mirror about x=8: geometry flips, east/west face NAMES swap, uv stays
    as authored (screen-oriented — the gate lesson). Rotations flip sign only
    for axes whose sense mirrors (y and z axes; x-axis rotations are
    symmetric under an x mirror)."""
    x0 = 16 - element["to"][0]
    x1 = 16 - element["from"][0]
    element["from"][0] = x0
    element["to"][0] = x1
    faces = element["faces"]
    east = faces.pop("east", None)
    west = faces.pop("west", None)
    if east:
        faces["west"] = east
    if west:
        faces["east"] = west
    rot = element.get("rotation")
    if rot:
        rot["origin"][0] = 16 - rot["origin"][0]
        if rot["axis"] in ("y", "z"):
            rot["angle"] = -rot["angle"]
    return element


# -------------------------------------------------------------- blockstates --
ROTS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def ap(mdl, rot):
    entry = {"model": f"{MOD}:block/{mdl}"}
    if rot:
        entry["y"] = rot
    return entry


def stairs_blockstate(prefix):
    variants = {}
    for facing, rot in ROTS:
        for bottom in ("true", "false"):
            for top in ("true", "false"):
                suffix = ("both" if bottom == "true" and top == "true"
                          else "bottom" if bottom == "true"
                          else "top" if top == "true" else "plain")
                variants[f"facing={facing},bottom={bottom},top={top}"] = \
                    ap(f"{prefix}_{suffix}", rot)
    return {"variants": variants}


def divider_blockstate():
    parts = []
    for facing, rot in ROTS:
        for bottom in ("true", "false"):
            for top in ("true", "false"):
                suffix = ("both" if bottom == "true" and top == "true"
                          else "bottom" if bottom == "true"
                          else "top" if top == "true" else "mid")
                parts.append({"when": {"facing": facing, "bottom": bottom, "top": top},
                              "apply": ap(f"subway_stair_divider_{suffix}", rot)})
        for side, prop in (("left", "left"), ("right", "right")):
            for style in ("modern", "old"):
                parts.append({"when": {"facing": facing, prop: style},
                              "apply": ap(f"subway_stair_divider_wing_{style}_{side}", rot)})
    return {"multipart": parts}


HANDRAIL_VARIANTS = ("flat", "slope", "slope_bottom", "slope_top",
                     "corner_left", "corner_right")


def handrail_blockstate(style):
    variants = {}
    for facing, rot in ROTS:
        for variant in HANDRAIL_VARIANTS:
            for mirror in ("true", "false"):
                mdl = f"subway_handrail_{style}_{variant}"
                if style == "wall" and mirror == "true" and variant.startswith("slope"):
                    mdl += "_mirror"
                variants[f"facing={facing},variant={variant},mirror={mirror}"] = ap(mdl, rot)
    return {"variants": variants}


# --------------------------------------------------------------- data files --
def loot(block):
    return {"type": "minecraft:block",
            "pools": [{"rolls": 1,
                       "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]}


RECIPES = {
    "subway_stairs": {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "B": {"item": "minecraft:iron_bars"}},
        "pattern": ["I  ", "IB ", "IIB"],
        "result": {"item": f"{MOD}:subway_stairs", "count": 4},
    },
    "subway_stairs_old": {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"C": {"item": "minecraft:gray_concrete"}},
        "pattern": ["C  ", "CC ", "CCC"],
        "result": {"item": f"{MOD}:subway_stairs_old", "count": 4},
    },
    "subway_stair_divider": {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:black_dye"}},
        "pattern": ["I", "D", "I"],
        "result": {"item": f"{MOD}:subway_stair_divider", "count": 4},
    },
    "subway_handrail_floating": {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["III"],
        "result": {"item": f"{MOD}:subway_handrail_floating", "count": 6},
    },
    "subway_handrail_wall": {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:subway_handrail_floating"},
                        {"item": "minecraft:iron_nugget"}],
        "result": {"item": f"{MOD}:subway_handrail_wall"},
    },
    "subway_handrail_standing": {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:subway_handrail_floating"},
                        {"item": "minecraft:iron_ingot"}],
        "result": {"item": f"{MOD}:subway_handrail_standing"},
    },
    "subway_handrail_double": {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": f"{MOD}:subway_handrail_floating"},
                        {"item": f"{MOD}:subway_handrail_floating"},
                        {"item": "minecraft:iron_ingot"}],
        "result": {"item": f"{MOD}:subway_handrail_double"},
    },
}

# ------------------------------------------------------------------- verify --
PROPS = {
    "subway_stairs": {"facing": {"north", "south", "east", "west"},
                      "bottom": {"true", "false"}, "top": {"true", "false"}},
    "subway_stair_divider": {"facing": {"north", "south", "east", "west"},
                             "bottom": {"true", "false"}, "top": {"true", "false"},
                             "left": {"none", "modern", "old"},
                             "right": {"none", "modern", "old"}},
    **{f"subway_handrail_{style}": {"facing": {"north", "south", "east", "west"},
                                    "mirror": {"true", "false"},
                                    "variant": set(("flat", "slope", "slope_bottom",
                                                    "slope_top", "corner_left",
                                                    "corner_right"))}
       for style in ("wall", "standing", "double", "floating")},
}
PROPS["subway_stairs_old"] = PROPS["subway_stairs"]


def verify():
    for block, props in PROPS.items():
        data = json.load(open(os.path.join(ASSETS, "blockstates", block + ".json")))
        if "variants" in data:
            entries = [(dict(p.split("=") for p in key.split(",")), entry)
                       for key, entry in data["variants"].items()]
        else:
            entries = [(part["when"], part["apply"]) for part in data["multipart"]]
        for when, entry in entries:
            for name, value in when.items():
                assert name in props and value in props[name], f"{block}: {name}={value}"
            mdl = entry["model"].split("/")[-1]
            mpath = os.path.join(ASSETS, "models/block", mdl + ".json")
            assert os.path.exists(mpath), f"{block}: missing model {mdl}"
            for el in json.load(open(mpath))["elements"]:
                for fname, face in el["faces"].items():
                    assert "uv" in face, f"{mdl}: {fname} missing uv"
                    u0, v0, u1, v1 = face["uv"]
                    assert 0 <= min(u0, u1) and max(u0, u1) <= 16 \
                        and 0 <= min(v0, v1) and max(v0, v1) <= 16, f"{mdl}: uv off sprite"
    print("verify: blockstates + models OK")


# --------------------------------------------------------------------- main --
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", metavar="DIR")
    args = parser.parse_args()

    texdir = os.path.join(ASSETS, "textures/block")
    textures = {
        "subway_stairs_tread": tex_tread(False, False),
        "subway_stairs_tread_yellow": tex_tread(True, False),
        "subway_stairs_riser": tex_riser("mesh"),
        "subway_stairs_riser_yellow": tex_riser("mesh_yellow"),
        "subway_stairs_side": tex_side(False),
        "subway_stairs_old_tread": tex_tread(False, True),
        "subway_stairs_old_tread_yellow": tex_tread(True, True),
        "subway_stairs_old_riser": tex_riser("old"),
        "subway_stairs_old_riser_yellow": tex_riser("old_yellow"),
        "subway_stairs_old_side": tex_side(True),
        "stair_divider_steel": tex_divider(),
        "handrail_steel": tex_handrail(),
    }
    for name, rows in textures.items():
        pngtool.write_png(os.path.join(texdir, name + ".png"), rows)

    write_stairs("subway_stairs", False)
    write_stairs("subway_stairs_old", True)
    write_divider()
    write_handrails()

    wj(os.path.join(ASSETS, "blockstates/subway_stairs.json"), stairs_blockstate("subway_stairs"))
    wj(os.path.join(ASSETS, "blockstates/subway_stairs_old.json"), stairs_blockstate("subway_stairs_old"))
    wj(os.path.join(ASSETS, "blockstates/subway_stair_divider.json"), divider_blockstate())
    for style in ("wall", "standing", "double", "floating"):
        wj(os.path.join(ASSETS, f"blockstates/subway_handrail_{style}.json"),
           handrail_blockstate(style))

    for block, parent in (("subway_stairs", "subway_stairs_both"),
                          ("subway_stairs_old", "subway_stairs_old_both"),
                          ("subway_stair_divider", "subway_stair_divider_mid")):
        wj(os.path.join(ASSETS, "models/item", block + ".json"),
           {"parent": f"{MOD}:block/{parent}"})
    for style in ("wall", "standing", "double", "floating"):
        wj(os.path.join(ASSETS, f"models/item/subway_handrail_{style}.json"),
           {"parent": f"{MOD}:block/subway_handrail_{style}_flat"})

    for block in PROPS:
        wj(os.path.join(DATA, MOD, "loot_tables/blocks", block + ".json"), loot(block))
    for name, recipe in RECIPES.items():
        wj(os.path.join(DATA, MOD, "recipes", name + ".json"), recipe)

    verify()

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        for name, rows in textures.items():
            pngtool.write_png(os.path.join(args.preview, name + ".png"),
                              pngtool.scale_nn(rows, 8, 8))
        print(f"previews in {args.preview}")


if __name__ == "__main__":
    main()
