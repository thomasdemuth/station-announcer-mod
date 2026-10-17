#!/usr/bin/env python3
"""Regenerates the CONSTRUCTION PROPS family (ATMOSPHERE_PLAN.md §1E) -
textures, block models, blockstates, item models/icons, loot tables, lang
names and mineable tags.

    python3 tools/gen_construction_assets.py            # write everything + verify()
    python3 tools/gen_construction_assets.py --verify   # only check what is on disk
    python3 tools/gen_construction_assets.py --preview DIR   # also render offline previews

Never hand-edit the output; change this file and re-run it. It owns (and wipes
on every run) textures/block/construction/ and models/block/construction/.

CONVENTIONS
-----------
* Every model is authored for FACING=north (front towards -z) or, for the
  axis blocks, for axis=x; the blockstate turns it with y = 0/90/180/270.
  Facing-relative LEFT = the -x side of the north model (ZebraBoardBlock's
  rule: right = facing.rotateYClockwise()).
* Every face carries an EXPLICIT uv. It is vanilla's auto-uv for the element
  (so textures are drawn in model space, 1 px = 4 texels at 64 px), shifted
  by whole blocks into 0..16 for elements that leave the cell, and nudged off
  the sprite edge when it is a sliver (atlas bleed). Signs and stripes get
  hand-made windows.
* Shared joints (barricade posts, sawhorse legs, barrier interlocks, wall
  battens) are drawn by ONE block: the one on the WORLD-negative side of
  the joint. In a north model the left joint is world -x, so "left joint"
  applies for facing north|east and "right joint" for south|west.
* Overlapping coplanar faces are only allowed when both use the same
  texture with auto-uv (then every fragment samples the same texel - the
  fight is invisible).
"""
import json
import math
import os
import shutil
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import atmo_common as AC  # noqa: E402
import pixel_kit  # noqa: E402

ROOT = AC.ROOT
ASSETS = AC.ASSETS
MOD = AC.MOD
TEX_DIR = os.path.join(ASSETS, "textures/block/construction")
MODEL_DIR = os.path.join(ASSETS, "models/block/construction")
JAVA_DIR = os.path.join(ROOT, "src/main/java/com/stationannouncer/atmosphere")

BLOCKS = ["traffic_cone", "wet_floor_sign", "barricade", "police_barricade", "plastic_barrier",
          "caution_tape", "construction_wall", "plywood_board", "scaffold", "sidewalk_shed",
          "sidewalk_shed_post", "sidewalk_shed_light", "work_light", "caged_work_lamp"]

LANG = {
    "traffic_cone": "Traffic Cone",
    "wet_floor_sign": "Wet Floor Sign",
    "barricade": "Type III Barricade",
    "police_barricade": "NYPD Police Barricade",
    "plastic_barrier": "Water-Filled Plastic Barrier",
    "caution_tape": "Caution Tape",
    "construction_wall": "MTA Construction Wall",
    "plywood_board": "Plywood Board",
    "scaffold": "Tube Scaffold",
    "sidewalk_shed": "Sidewalk Shed",
    "sidewalk_shed_post": "Sidewalk Shed Post",
    "sidewalk_shed_light": "Sidewalk Shed Light",
    "work_light": "Tripod Work Light",
    "caged_work_lamp": "Caged Work Lamp",
}

TAGS = {
    "pickaxe": ["traffic_cone", "wet_floor_sign", "plastic_barrier", "scaffold", "sidewalk_shed_post",
                "sidewalk_shed_light", "work_light", "caged_work_lamp"],
    "axe": ["barricade", "police_barricade", "construction_wall", "plywood_board", "sidewalk_shed"],
    "hoe": ["caution_tape"],
}

# Mirror of the Java properties (verify() also greps the Java for the names).
FACING4 = ["north", "east", "south", "west"]
BOOL = ["true", "false"]
PROPS = {
    "traffic_cone": {"count": ["1", "2", "3"]},
    "wet_floor_sign": {"facing": FACING4, "variant": ["wet_floor", "caution", "closed"]},
    "barricade": {"facing": FACING4, "left": BOOL, "right": BOOL},
    "police_barricade": {"facing": FACING4, "left": BOOL, "right": BOOL},
    "plastic_barrier": {"facing": FACING4, "left": BOOL, "right": BOOL},
    "caution_tape": {"axis": ["x", "z"], "left": ["none", "tape", "wall", "post"],
                     "right": ["none", "tape", "wall", "post"], "double": BOOL},
    "construction_wall": {"facing": FACING4, "variant": ["plain", "notice", "no_admittance"],
                          "left": BOOL, "right": BOOL, "up": BOOL, "down": BOOL},
    "plywood_board": {"facing": FACING4, "variant": ["plain", "post_no_bills", "no_trespassing"]},
    "scaffold": {"facing": FACING4, "east": BOOL, "south": BOOL, "up": BOOL, "down": BOOL, "deck": BOOL},
    "sidewalk_shed": {"north": BOOL, "east": BOOL, "south": BOOL, "west": BOOL, "axis": ["x", "z"]},
    "sidewalk_shed_post": {"up": BOOL, "down": BOOL, "axis": ["x", "z"]},
    "sidewalk_shed_light": {},
    "work_light": {"facing": FACING4},
    "caged_work_lamp": {"hanging": BOOL},
}
JAVA_FILES = {
    "traffic_cone": "TrafficConeBlock.java", "wet_floor_sign": "WetFloorSignBlock.java",
    "barricade": "ConstructionBarrierBlock.java", "police_barricade": "ConstructionBarrierBlock.java",
    "plastic_barrier": "ConstructionBarrierBlock.java", "caution_tape": "CautionTapeBlock.java",
    "construction_wall": "ConstructionWallBlock.java", "plywood_board": "PlywoodBoardBlock.java",
    "scaffold": "ScaffoldFrameBlock.java", "sidewalk_shed": "SidewalkShedBlock.java",
    "sidewalk_shed_post": "SidewalkShedPostBlock.java", "sidewalk_shed_light": "ShedLightBlock.java",
    "work_light": "WorkLightBlock.java", "caged_work_lamp": "CagedWorkLampBlock.java",
}
Y_ROT = {"north": 0, "east": 90, "south": 180, "west": 270}

CONE_STEP = 2.5          # TrafficConeBlock.STACK_STEP


def tex_id(name):
    return f"{MOD}:block/construction/{name}"


def model_id(name):
    return f"{MOD}:block/construction/{name}"


# =====================================================================
# TEXTURE TOOLKIT (numpy float RGBA, arr[y, x])
# =====================================================================

TEXTURES = {}


def canvas(size, rgb, alpha=255):
    a = np.zeros((size, size, 4), dtype=np.float64)
    a[..., 0], a[..., 1], a[..., 2] = rgb
    a[..., 3] = alpha
    return a


def vnoise(size, cellx, celly, seed):
    """Tileable value noise in 0..1, smooth-stepped."""
    rng = np.random.default_rng(seed)
    nx, ny = max(1, round(size / cellx)), max(1, round(size / celly))
    g = rng.random((ny, nx))
    xs = np.arange(size) * nx / size
    ys = np.arange(size) * ny / size
    x0 = np.floor(xs).astype(int)
    y0 = np.floor(ys).astype(int)
    fx = xs - x0
    fy = ys - y0
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    x1, y1 = (x0 + 1) % nx, (y0 + 1) % ny
    x0, y0 = x0 % nx, y0 % ny
    a = g[np.ix_(y0, x0)]
    b = g[np.ix_(y0, x1)]
    c = g[np.ix_(y1, x0)]
    d = g[np.ix_(y1, x1)]
    top = a + (b - a) * fx[None, :]
    bot = c + (d - c) * fx[None, :]
    return top + (bot - top) * fy[:, None]


def fbm(size, seed, octaves):
    """Sum of (cellx, celly, amplitude) octaves, centred on 0."""
    out = np.zeros((size, size))
    for i, (cx, cy, amp) in enumerate(octaves):
        out += (vnoise(size, cx, cy, seed * 31 + i) - 0.5) * amp
    return out


def shade(arr, factor):
    """Multiply rgb by a per-pixel factor array (or scalar)."""
    arr[..., :3] *= np.asarray(factor)[..., None] if np.ndim(factor) else factor
    return arr


def rect(arr, x0, y0, x1, y1, rgb, alpha=None):
    x0, y0 = max(0, int(round(x0))), max(0, int(round(y0)))
    x1, y1 = min(arr.shape[1], int(round(x1))), min(arr.shape[0], int(round(y1)))
    if x1 <= x0 or y1 <= y0:
        return
    arr[y0:y1, x0:x1, :3] = rgb
    if alpha is not None:
        arr[y0:y1, x0:x1, 3] = alpha


def blend_mask(arr, mask, rgb, strength=1.0):
    m = np.clip(mask * strength, 0, 1)[..., None]
    arr[..., :3] = arr[..., :3] * (1 - m) + np.asarray(rgb, dtype=np.float64) * m


def disc_mask(size, cx, cy, r, soft=0.7):
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    d = np.hypot(xx - cx, yy - cy)
    return np.clip((r - d) / soft + 0.5, 0, 1)


def line_mask(size, x0, y0, x1, y1, width):
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    px, py = xx - x0, yy - y0
    dx, dy = x1 - x0, y1 - y0
    L2 = dx * dx + dy * dy or 1e-9
    t = np.clip((px * dx + py * dy) / L2, 0, 1)
    d = np.hypot(px - t * dx, py - t * dy)
    return np.clip((width / 2 - d) / 0.6 + 0.5, 0, 1)


def poly_mask(size, pts):
    """Crisp polygon fill (pixel centres inside)."""
    from PIL import ImageDraw
    img = Image.new("L", (size, size), 0)
    ImageDraw.Draw(img).polygon(pts, fill=255)
    return np.asarray(img, dtype=np.float64) / 255.0


def save_tex(name, arr):
    TEXTURES[name] = arr
    out = np.clip(np.round(arr), 0, 255).astype(np.uint8)
    path = os.path.join(TEX_DIR, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(out).save(path)


def save_item_tex(name, arr):
    out = np.clip(np.round(arr), 0, 255).astype(np.uint8)
    path = AC.tex_path(name, "item")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(out).save(path)


def material(size, rgb, seed, amp=0.08, fine=0.05, cells=None):
    """A soft, non-dithered material: base colour * (1 + low/mid/fine noise)."""
    a = canvas(size, rgb)
    cells = cells or [(size / 2, size / 2, amp), (size / 6, size / 6, amp * 0.6), (2, 2, fine)]
    shade(a, 1 + fbm(size, seed, cells))
    return a


# =====================================================================
# PIXEL FONTS
# =====================================================================

F57 = {
    "A": ".###.|#...#|#...#|#####|#...#|#...#|#...#",
    "B": "####.|#...#|#...#|####.|#...#|#...#|####.",
    "C": ".###.|#...#|#....|#....|#....|#...#|.###.",
    "D": "###..|#..#.|#...#|#...#|#...#|#..#.|###..",
    "E": "#####|#....|#....|####.|#....|#....|#####",
    "F": "#####|#....|#....|####.|#....|#....|#....",
    "G": ".###.|#...#|#....|#.###|#...#|#...#|.####",
    "H": "#...#|#...#|#...#|#####|#...#|#...#|#...#",
    "I": "###|.#.|.#.|.#.|.#.|.#.|###",
    "J": "..###|...#.|...#.|...#.|#..#.|#..#.|.##..",
    "K": "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#",
    "L": "#....|#....|#....|#....|#....|#....|#####",
    "M": "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
    "N": "#...#|##..#|##..#|#.#.#|#..##|#..##|#...#",
    "O": ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
    "P": "####.|#...#|#...#|####.|#....|#....|#....",
    "Q": ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#",
    "R": "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
    "S": ".####|#....|#....|.###.|....#|....#|####.",
    "T": "#####|..#..|..#..|..#..|..#..|..#..|..#..",
    "U": "#...#|#...#|#...#|#...#|#...#|#...#|.###.",
    "V": "#...#|#...#|#...#|#...#|#...#|.#.#.|..#..",
    "W": "#...#|#...#|#...#|#.#.#|#.#.#|##.##|#...#",
    "X": "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#",
    "Y": "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..",
    "Z": "#####|....#|...#.|..#..|.#...|#....|#####",
    "0": ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.",
    "1": ".#.|##.|.#.|.#.|.#.|.#.|###",
    "2": ".###.|#...#|....#|...#.|..#..|.#...|#####",
    "3": "####.|....#|....#|.###.|....#|....#|####.",
    "4": "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
    "5": "#####|#....|####.|....#|....#|#...#|.###.",
    "6": ".###.|#....|#....|####.|#...#|#...#|.###.",
    "7": "#####|....#|...#.|..#..|..#..|..#..|..#..",
    "8": ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
    "9": ".###.|#...#|#...#|.####|....#|....#|.###.",
    "'": "#|#|.|.|.|.|.",
    "-": "...|...|...|###|...|...|...",
    ".": ".|.|.|.|.|.|#",
    ",": "..|..|..|..|..|.#|#.",
    "!": "#|#|#|#|#|.|#",
    "/": "....#|...#.|...#.|..#..|.#...|.#...|#....",
    " ": "..|..|..|..|..|..|..",
}
F35 = {k: tuple(v) for k, v in pixel_kit._FONT.items()}
F35[" "] = ("00", "00", "00", "00", "00")
F35["'"] = ("1", "1", "0", "0", "0")
F35["N"] = ("1001", "1101", "1011", "1001", "1001")      # pixel_kit's 3-wide N/M read as each other
F35["M"] = ("10001", "11011", "10101", "10001", "10001")
F35["W"] = ("10001", "10001", "10101", "11011", "10001")


def glyph(ch, font):
    if font == "57":
        g = F57.get(ch.upper(), F57[" "])
        return [row for row in g.split("|")]
    g = F35.get(ch.upper(), F35[" "])
    return [row.replace("1", "#").replace("0", ".") for row in g]


def text_width(s, font="57", scale=1, track=1, bold=False):
    w = 0
    for i, ch in enumerate(s):
        w += (len(glyph(ch, font)[0]) + (1 if bold else 0)) * scale
        if i:
            w += track * scale
    return w


def text_mask(size, x, y, s, font="57", scale=1, track=1, bold=False):
    """Crisp mask of `s` with its top-left at (x, y) in texels."""
    m = np.zeros((size, size))
    cx = int(round(x))
    y = int(round(y))
    for i, ch in enumerate(s):
        if i:
            cx += track * scale
        rows = glyph(ch, font)
        gw = len(rows[0])
        for gy, row in enumerate(rows):
            for gx, bit in enumerate(row):
                if bit != "#":
                    continue
                for b in range(2 if bold else 1):
                    x0 = cx + (gx + b) * scale
                    y0 = y + gy * scale
                    if 0 <= y0 < size and x0 + scale > 0 and x0 < size:
                        m[y0:min(size, y0 + scale), max(0, x0):min(size, x0 + scale)] = 1
        cx += (gw + (1 if bold else 0)) * scale
    return m


def draw_text(arr, x, y, s, rgb, font="57", scale=1, track=1, bold=False):
    blend_mask(arr, text_mask(arr.shape[0], x, y, s, font, scale, track, bold), rgb)


def draw_text_centred(arr, cx, y, s, rgb, font="57", scale=1, track=1, bold=False):
    w = text_width(s, font, scale, track, bold)
    draw_text(arr, cx - w / 2.0, y, s, rgb, font, scale, track, bold)
    return w


# =====================================================================
# MODEL TOOLKIT
# =====================================================================

FACES = ("north", "south", "east", "west", "up", "down")
MODELS = {}


def auto_uv(face, f, t):
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0], "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0], "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
        "up": [x0, z0, x1, z1], "down": [x0, 16 - z1, x1, 16 - z0],
    }[face]


def _fit_pair(a, b):
    lo, hi = min(a, b), max(a, b)
    span = hi - lo
    if span > 16:
        lo, hi = 0.0, 16.0
    else:
        k = math.floor(lo / 16.0)
        lo -= 16 * k
        hi -= 16 * k
        if hi > 16 + 1e-9:
            lo, hi = 16 - span, 16
    if span < 1:            # sliver: keep it off the sprite edge
        if lo < 1:
            lo, hi = lo + 1, hi + 1
        elif hi > 15:
            lo, hi = lo - 1, hi - 1
    lo, hi = round(lo, 4), round(hi, 4)
    return (lo, hi) if a <= b else (hi, lo)


def fit_uv(uv):
    u0, u1 = _fit_pair(uv[0], uv[2])
    v0, v1 = _fit_pair(uv[1], uv[3])
    return [u0, v0, u1, v1]


def el(f, t, tex, faces=FACES, skip=(), uv=None, texmap=None, rot=None, shade_on=True, cull=None):
    """One element. tex = default texture key; texmap = per-face texture keys;
    uv = per-face explicit windows (used as given); others = fitted auto-uv."""
    f = [round(v, 4) for v in f]
    t = [round(v, 4) for v in t]
    out = {"from": f, "to": t, "faces": {}}
    for face in faces:
        if face in skip:
            continue
        key = (texmap or {}).get(face, tex)
        if key is None:
            continue
        if uv and face in uv:
            u = [round(v, 4) for v in uv[face]]
        else:
            u = fit_uv(auto_uv(face, f, t))
        fc = {"texture": "#" + key, "uv": u}
        if cull and face in cull:
            fc["cullface"] = cull[face]
        out["faces"][face] = fc
    if rot:
        axis, angle, origin = rot
        out["rotation"] = {"origin": [round(v, 4) for v in origin], "axis": axis, "angle": angle}
    if not shade_on:
        out["shade"] = False
    return out


def model(name, textures, elements, particle=None, ambient=True):
    tx = {k: tex_id(v) for k, v in textures.items()}
    tx["particle"] = tex_id(particle) if particle else next(iter(tx.values()))
    m = {"parent": "minecraft:block/block", "textures": tx, "elements": elements}
    if not ambient:
        m["ambientocclusion"] = False
    MODELS[name] = m
    path = os.path.join(MODEL_DIR, name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(m, fh, indent=1)
        fh.write("\n")
    return name


def mx(v):
    return 16 - v


def box_lr(side, x0, y0, z0, x1, y1, z1):
    """A box authored for the LEFT side, mirrored to the right (x -> 16-x)."""
    if side == "left":
        return [x0, y0, z0], [x1, y1, z1]
    return [mx(x1), y0, z0], [mx(x0), y1, z1]


def swap_ew(faces, side):
    if side == "left":
        return faces
    sw = {"east": "west", "west": "east"}
    return tuple(sw.get(f, f) for f in faces)


def write_blockstate(block, obj):
    AC.write_json(os.path.join(ASSETS, "blockstates", block + ".json"), obj)


def apply(name, y=0, weight=None, uvlock=False):
    a = {"model": model_id(name)}
    if y:
        a["y"] = y
    if weight is not None:
        a["weight"] = weight
    return a


def item_model(name, parent_model, gui_scale=0.625, gui_y=0.0, ground=0.25, rot=(30, 225, 0)):
    obj = {"parent": model_id(parent_model), "display": {
        "gui": {"rotation": list(rot), "translation": [0, gui_y, 0], "scale": [gui_scale] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [ground] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [gui_scale * 0.8] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4] * 3},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4] * 3},
    }}
    AC.write_json(os.path.join(ASSETS, "models/item", name + ".json"), obj)


# =====================================================================
# 1. TRAFFIC CONE
# =====================================================================

# 28" cone: 1 px ~ 5 cm. 6" upper and 4" lower reflective collars, 3.5" orange tip.
CONE_TIERS = [1.2, 2.4, 3.6, 4.8, 6.1, 7.4, 8.4, 9.4, 10.4, 11.4, 12.4, 13.4, 14.4, 15.2]
CONE_BANDS = [(7.4, 9.4), (10.4, 13.4)]      # white reflective collars (local y)
CONE_TOP = 15.2


def cone_r(y):
    return 3.0 + (y - 1.2) * (0.8 - 3.0) / (CONE_TOP - 1.2)


def in_band(y0, y1):
    return any(b0 <= y0 + 1e-6 and y1 <= b1 + 1e-6 for b0, b1 in CONE_BANDS)


def cone_textures():
    orange = (244, 98, 22)
    for var, dirt in (("a", 0.0), ("b", 1.0)):
        a = material(32, orange, 11 if var == "a" else 12, amp=0.05, fine=0.03)
        # moulded vertical sheen: lighter in the middle of each face
        xs = np.arange(32) + 0.5
        shade(a, (1 + 0.05 * np.cos((xs - 16) / 16 * math.pi * 0.9))[None, :] * np.ones((32, 1)))
        rows = np.arange(32) + 0.5
        for b0, b1 in CONE_BANDS:
            v0, v1 = (16 - b1) * 2, (16 - b0) * 2
            band = (rows >= v0) & (rows < v1)
            sheet = canvas(32, (246, 246, 240))
            shade(sheet, 1 + fbm(32, 40 + int(b0), [(8, 2, 0.06), (2, 2, 0.03)]))
            a[band] = sheet[band]
            # a hairline shadow under each collar's lower edge (it is a sleeve)
            r = int(v1)
            if r < 32:
                shade(a[r:r + 1], 0.8)
        if dirt:
            # road grime: darker toward the base, scuffs, a smear on the sheeting
            low = np.clip((rows - 22) / 10, 0, 1) ** 1.4
            g = fbm(32, 77, [(6, 3, 1.0), (2, 2, 0.4)])
            k = 1 - 0.32 * low[:, None] * (0.75 + g)
            shade(a, k)
            smear = np.clip(fbm(32, 78, [(10, 4, 1.4)]) - 0.15, 0, 1)
            blend_mask(a, smear, (120, 104, 92), 0.45)
        save_tex(f"cone_body_{var}", a)
    flat = canvas(16, (236, 96, 22))
    save_tex("cone_top_orange", flat)
    save_tex("cone_top_white", canvas(16, (232, 232, 226)))
    tip = canvas(16, (230, 92, 20))
    blend_mask(tip, disc_mask(16, 8, 8, 0.9, 0.5), (60, 30, 14))
    save_tex("cone_tip", tip)
    base = material(32, (36, 36, 38), 21, amp=0.10, fine=0.06)
    # moulded rim on the base's top face (auto-uv x/z 3..13 -> texels 6..26)
    yy, xx = np.mgrid[0:32, 0:32] + 0.5
    edge = np.minimum(np.minimum(xx - 6, 26 - xx), np.minimum(yy - 6, 26 - yy))
    shade(base, np.where((edge >= 0) & (edge < 1.2), 1.35, 1.0))
    scuff = np.clip(fbm(32, 22, [(5, 5, 1.2)]) - 0.3, 0, 1)
    blend_mask(base, scuff, (78, 76, 74), 0.5)
    save_tex("cone_base", base)


def cone_tier_elements(lift, y0, y1, side_tex, up_tex):
    a = cone_r((y0 + y1) / 2)
    b = a * 0.4142
    c = 8.0
    v = [16 - (y1), 16 - (y0)]      # local-y uv rows (sheeting collars line up)
    Y0, Y1 = y0 + lift, y1 + lift
    els = []

    def side_uv(face, f, t):
        u = auto_uv(face, f, t)
        return [u[0], v[0], u[2], v[1]]

    rects = [((c - a, Y0, c - b), (c + a, Y1, c + b), ("east", "west")),
             ((c - b, Y0, c - a), (c + b, Y1, c + a), ("north", "south"))]
    for rot in (None, ("y", 45, (8, Y0, 8))):
        for f, t, faces in rects:
            uv = {fc: side_uv(fc, f, t) for fc in faces}
            uv["up"] = fit_uv(auto_uv("up", f, t))
            els.append(el(f, t, side_tex, faces=faces + ("up",), uv=uv, texmap={"up": up_tex}, rot=rot))
    return els


def cone_single(lift, tex_side, full=True):
    els = []
    L = lift
    base_faces = ("north", "south", "east", "west", "up", "down")
    cull = {"down": "down"} if lift == 0 else None
    els.append(el([3.5, L, 4.1], [12.5, L + 1.2, 11.9], "base", faces=base_faces, cull=cull))
    for z0, z1, out in ((3.5, 4.1, "north"), (11.9, 12.5, "south")):
        fs = (out, "east", "west", "up", "down")
        els.append(el([4.1, L, z0], [11.9, L + 1.2, z1], "base", faces=fs, cull=cull))
    els.append(el([4.6, L + 1.2, 4.6], [11.4, L + 1.6, 11.4], "base", faces=("north", "south", "east", "west", "up")))
    tiers = list(zip(CONE_TIERS[:-1], CONE_TIERS[1:]))
    if not full:
        tiers = [t for t in tiers if t[0] < CONE_STEP]
    for i, (y0, y1) in enumerate(tiers):
        top = i == len(CONE_TIERS) - 2
        up = "tip" if (top and full) else ("white" if in_band(y0, y1) else "orange")
        els += cone_tier_elements(L, y0, y1, tex_side, up)
    return els


def build_cone():
    cone_textures()
    variants = {}
    for var in ("a", "b"):
        tx = {"side": f"cone_body_{var}", "base": "cone_base", "orange": "cone_top_orange",
              "white": "cone_top_white", "tip": "cone_tip"}
        for n in (1, 2, 3):
            els = []
            for k in range(n):
                els += cone_single(k * CONE_STEP, "side", full=(k == n - 1))
            model(f"traffic_cone_{n}_{var}", tx, els, particle=f"cone_body_{var}")
    for n in (1, 2, 3):
        lst = []
        for var, w in (("a", 3), ("b", 2)):
            for y in (0, 90, 180, 270):
                lst.append(apply(f"traffic_cone_{n}_{var}", y=y, weight=w))
        variants[f"count={n}"] = lst
    write_blockstate("traffic_cone", {"variants": variants})
    item_model("traffic_cone", "traffic_cone_1_a", gui_scale=0.7, gui_y=-1.0)


# =====================================================================
# 2. WET FLOOR SIGN
# =====================================================================

WFS_W = 11.0                 # leaf width (x 2.5..13.5)
WFS_U = 11.5                 # legend window width in uv units (46 texels)
WFS_L = WFS_W * 16 / WFS_U   # leaf length along the slant (keeps texels square)
WFS_HINGE = WFS_L * math.cos(math.radians(22.5))
YELLOW = (250, 206, 24)
INK = (22, 20, 18)


def slipping_man(arr, ox, oy, s, rgb):
    """The ISO-style slipping figure inside a ~26x24 box at (ox, oy), scale s.
    Drawn as soft strokes, then thresholded: crisp pixels, no grey fringe."""
    acc = np.zeros(arr.shape[:2])

    def L(x0, y0, x1, y1, w):
        np.maximum(acc, line_mask(arr.shape[0], ox + x0 * s, oy + y0 * s, ox + x1 * s, oy + y1 * s, w * s), out=acc)
    np.maximum(acc, disc_mask(arr.shape[0], ox + 15.5 * s, oy + 3.2 * s, 2.4 * s, 0.5), out=acc)  # head
    L(14.5, 6.5, 9.5, 13.0, 3.2)      # torso leaning back
    L(13.5, 8.0, 19.5, 6.0, 1.8)      # arm flung up
    L(12.5, 8.5, 7.0, 10.5, 1.8)      # other arm back
    L(9.5, 13.0, 16.5, 16.5, 2.2)     # thigh kicked forward
    L(16.5, 16.5, 21.0, 13.5, 2.0)    # shin up (foot slipping)
    L(9.5, 13.0, 7.5, 19.0, 2.2)      # standing leg
    L(7.5, 19.0, 4.5, 20.5, 1.8)      # foot
    L(1.0, 22.5, 24.0, 22.5, 1.6)     # floor line
    for k in range(3):                # wet-floor ripples
        L(12.5 + k * 3.6, 20.4, 14.3 + k * 3.6, 19.2, 1.3)
    blend_mask(arr, (acc > 0.45).astype(np.float64), rgb)


def warning_triangle(arr, cx, top, h, rgb, inner_rgb):
    w = h * 1.15
    outer = poly_mask(arr.shape[0], [(cx, top), (cx + w / 2, top + h), (cx - w / 2, top + h)])
    i = 2.2
    inner = poly_mask(arr.shape[0], [(cx, top + i * 1.9), (cx + w / 2 - i * 1.7, top + h - i),
                                     (cx - w / 2 + i * 1.7, top + h - i)])
    blend_mask(arr, outer, rgb)
    blend_mask(arr, inner, inner_rgb)


def wfs_legend(variant):
    a = material(64, YELLOW, 50 + len(variant), amp=0.035, fine=0.02)
    W = 46
    # moulded rim, darker toward the foot, rubber foot strip
    rect(a, 0, 0, W, 1, (226, 176, 16))
    rect(a, 0, 0, 1, 64, (226, 176, 16))
    rect(a, W - 1, 0, W, 64, (214, 166, 14))
    rect(a, 0, 61, W, 64, (58, 56, 52))
    rows = np.arange(64)
    shade(a, (1 - 0.10 * np.clip((rows - 40) / 21, 0, 1))[:, None])
    cx = W / 2
    if variant == "wet_floor":
        draw_text_centred(a, cx, 4, "CAUTION", INK)
        warning_triangle(a, cx, 13, 29, INK, YELLOW)
        slipping_man(a, cx - 11.5, 21.0, 0.88, INK)
        draw_text_centred(a, cx, 45, "WET", INK)
        draw_text_centred(a, cx, 53, "FLOOR", INK)
    elif variant == "caution":
        rect(a, 1, 3, W - 1, 14, INK)
        draw_text_centred(a, cx, 5, "CAUTION", YELLOW)
        warning_triangle(a, cx, 18, 32, INK, YELLOW)
        rect(a, cx - 1.5, 28, cx + 1.5, 42, INK)
        rect(a, cx - 1.5, 44, cx + 1.5, 47, INK)
        rect(a, 1, 54, W - 1, 58, INK)
    else:
        draw_text_centred(a, cx, 4, "CLOSED", INK)
        draw_text_centred(a, cx, 13, "FOR", INK)
        draw_text_centred(a, cx, 22, "CLEANING", INK)
        # mop and bucket pictogram
        blend_mask(a, line_mask(64, 13, 33, 25, 50, 1.6), INK)                   # handle
        blend_mask(a, poly_mask(64, [(22, 49), (29, 49), (30, 53), (21, 53)]), INK)   # mop head
        for k in range(5):
            blend_mask(a, line_mask(64, 22 + k * 1.8, 53, 21.5 + k * 1.9, 56, 0.9), INK)
        blend_mask(a, poly_mask(64, [(27, 43), (37, 43), (35.5, 56), (28.5, 56)]), INK)  # bucket
        blend_mask(a, poly_mask(64, [(28.6, 45), (35.4, 45), (34.9, 47), (29.1, 47)]), YELLOW)
        blend_mask(a, line_mask(64, 26.5, 43, 37.5, 43, 1.0), INK)
    a[:, W:, :3] = a[:, W - 1:W, :3]      # pad the unused right part
    save_tex(f"wfs_{variant}", a)


def build_wet_floor_sign():
    for v in ("wet_floor", "caution", "closed"):
        wfs_legend(v)
    plastic = material(32, YELLOW, 61, amp=0.04, fine=0.02)
    # inner face: moulded stiffening ribs (rows of the inner side map to the leaf length)
    ribs = plastic.copy()
    for r in (6, 16, 26):
        shade(ribs[r:r + 2], 0.82)
        shade(ribs[r + 2:r + 3], 1.08)
    save_tex("wfs_plastic", plastic)
    save_tex("wfs_inner", ribs)
    handle = canvas(32, YELLOW)
    shade(handle, 1 + fbm(32, 62, [(8, 8, 0.05)]))
    yy, xx = np.mgrid[0:32, 0:32] + 0.5
    slot = (np.abs(yy - 5.0) < 2.2) & (np.abs(xx - 16) < 8.5)
    slot |= (np.hypot(xx - 7.5, yy - 5.0) < 2.2) | (np.hypot(xx - 24.5, yy - 5.0) < 2.2)
    handle[slot, 3] = 0
    rim = (~slot) & ((np.abs(yy - 5.0) < 3.0) & (np.abs(xx - 16) < 9.3))
    shade(handle, np.where(rim, 0.85, 1.0))
    save_tex("wfs_handle", handle)

    hy = WFS_HINGE
    y_lo = hy - WFS_L
    for v in ("wet_floor", "caution", "closed"):
        tx = {"legend": f"wfs_{v}", "plastic": "wfs_plastic", "inner": "wfs_inner", "handle": "wfs_handle"}
        legend_uv = [0, 0, WFS_U, 16]
        els = []
        # front leaf (north), bottom swung toward -z
        f, t = [2.5, y_lo, 7.15], [13.5, hy, 8.0]
        els.append(el(f, t, "plastic", uv={"north": legend_uv, "south": [0, 0, 11, 16]},
                      texmap={"north": "legend", "south": "inner"},
                      rot=("x", 22.5, (8, hy, 8)), skip=("up",)))
        # back leaf (south)
        f, t = [2.5, y_lo, 8.0], [13.5, hy, 8.85]
        els.append(el(f, t, "plastic", uv={"south": legend_uv, "north": [0, 0, 11, 16]},
                      texmap={"south": "legend", "north": "inner"},
                      rot=("x", -22.5, (8, hy, 8)), skip=("up",)))
        # hinge housing + carry handle
        els.append(el([2.1, hy - 0.9, 7.0], [13.9, hy + 0.6, 9.0], "plastic"))
        hw, hh = 6.0, 2.6
        huv = [0, 0, 16, 16 * hh / hw]
        els.append(el([5.0, hy + 0.6, 7.45], [11.0, hy + 0.6 + hh, 8.55], "handle",
                      uv={"north": huv, "south": huv}, texmap={"down": None}))
        model(f"wet_floor_sign_{v}", tx, els, particle="wfs_plastic")
    variants = {}
    for fc in FACING4:
        for v in ("wet_floor", "caution", "closed"):
            variants[f"facing={fc},variant={v}"] = apply(f"wet_floor_sign_{v}", y=Y_ROT[fc])
    write_blockstate("wet_floor_sign", {"variants": variants})
    item_model("wet_floor_sign", "wet_floor_sign_wet_floor", gui_scale=0.62, gui_y=-0.5, rot=(25, 200, 0))


# =====================================================================
# 3-5. BARRIERS IN RUNS (Type III, NYPD sawhorse, plastic barrier)
# =====================================================================

def run_blockstate(block, base_parts, left_end, right_end, left_joint, right_joint):
    """Multipart for a ConstructionBarrierBlock. *_end apply when that side is
    free, *_joint when joined AND this block owns the joint (world-negative side)."""
    parts = []
    for fc in FACING4:
        y = Y_ROT[fc]
        for name in base_parts:
            if isinstance(name, list):
                parts.append({"when": {"facing": fc}, "apply": [dict(apply(n, y=y), weight=w) for n, w in name]})
            else:
                parts.append({"when": {"facing": fc}, "apply": apply(name, y=y)})
        for name in left_end:
            parts.append({"when": {"facing": fc, "left": "false"}, "apply": apply(name, y=y)})
        for name in right_end:
            parts.append({"when": {"facing": fc, "right": "false"}, "apply": apply(name, y=y)})
        if fc in ("north", "east"):
            for name in left_joint:
                parts.append({"when": {"facing": fc, "left": "true"}, "apply": apply(name, y=y)})
        else:
            for name in right_joint:
                parts.append({"when": {"facing": fc, "right": "true"}, "apply": apply(name, y=y)})
    write_blockstate(block, {"multipart": parts})


ORANGE = (240, 102, 26)
WHITE = (240, 240, 234)


def barricade_textures():
    s = canvas(64, WHITE)
    yy, xx = np.mgrid[0:64, 0:64]
    orange = ((xx + yy) % 16) < 8          # 45 deg, 4 px period (divides 16 -> seamless runs)
    s[orange, :3] = ORANGE
    shade(s, 1 + fbm(64, 81, [(32, 8, 0.05), (4, 4, 0.03)]))
    # sheeting edge: a slightly darker hairline at the top and bottom of every board window
    save_tex("barricade_stripes", s)
    save_tex("barricade_edge", material(32, (226, 224, 216), 82, amp=0.06))
    post = material(32, (232, 230, 222), 83, amp=0.06)
    rows = np.arange(32)
    shade(post, (1 - 0.35 * np.clip((rows - 20) / 12, 0, 1) ** 1.5)[:, None])
    save_tex("barricade_post", post)
    skid = material(32, (70, 72, 74), 84, amp=0.12)
    save_tex("barricade_skid", skid)
    body = material(16, (24, 24, 26), 85, amp=0.08)
    save_tex("barricade_lamp_body", body)
    lens = canvas(16, (255, 176, 20))
    d = disc_mask(16, 8, 8, 7.6, 0.6)
    lens[..., 3] = np.where(d > 0.5, 255, 0)
    yy, xx = np.mgrid[0:16, 0:16] + 0.5
    r = np.hypot(xx - 8, yy - 8)
    shade(lens, 1 + 0.12 * np.cos(r * 2.2))           # fresnel rings
    blend_mask(lens, disc_mask(16, 6, 6, 2.2, 1.2), (255, 236, 160), 0.8)
    blend_mask(lens, np.clip(r - 6.6, 0, 1) * (d > 0.5), (40, 40, 40), 0.9)  # black bezel ring
    save_tex("barricade_lens", lens)


def build_barricade():
    barricade_textures()
    tx = {"stripes": "barricade_stripes", "edge": "barricade_edge", "post": "barricade_post",
          "skid": "barricade_skid", "lamp": "barricade_lamp_body", "lens": "barricade_lens"}
    rails = []
    for y0 in (5.0, 12.0, 19.0):
        f, t = [0, y0, 7.4], [16, y0 + 3.0, 8.6]
        rails.append(el(f, t, "edge", faces=("north", "south", "up", "down"),
                        texmap={"north": "stripes", "south": "stripes"}))
    model("barricade_rails", tx, rails, particle="barricade_stripes")
    for side in ("left", "right"):
        x0 = -0.8 if side == "left" else 15.2
        els = []
        f, t = [x0, 0.0, 6.8], [x0 + 1.6, 23.4, 9.2]
        els.append(el(f, t, "post", faces=("north", "south", "east", "west", "up")))
        els.append(el([x0 - 0.1, 0, 1.6], [x0 + 1.7, 1.2, 14.4], "skid", cull={"down": "down"}))
        for zc, ang in ((4.6, -45), (11.4, 45)):
            els.append(el([x0 + 0.3, 2.9, zc - 2.6], [x0 + 1.3, 3.8, zc + 2.6], "skid",
                          rot=("x", ang, (x0 + 0.8, 3.35, zc))))
        model(f"barricade_post_{side}", tx, els, particle="barricade_post")
        lamp = [el([x0 - 0.5, 23.4, 7.1], [x0 + 2.1, 25.2, 8.9], "lamp"),
                el([x0 - 0.4, 25.2, 7.55], [x0 + 2.0, 27.6, 8.45], "lamp",
                   uv={"north": [0, 0, 16, 16], "south": [0, 0, 16, 16]},
                   texmap={"north": "lens", "south": "lens"}, skip=("down",))]
        model(f"barricade_lamp_{side}", tx, lamp, particle="barricade_lamp_body")
    run_blockstate("barricade", ["barricade_rails"],
                   ["barricade_post_left", "barricade_lamp_left"], ["barricade_post_right", "barricade_lamp_right"],
                   ["barricade_post_left"], ["barricade_post_right"])
    # item: a lone barricade (rails + both posts + lamps)
    els = []
    for n in ("barricade_rails", "barricade_post_left", "barricade_post_right", "barricade_lamp_left"):
        els += MODELS[n]["elements"]
    model("barricade_item", tx, els, particle="barricade_stripes")
    item_model("barricade", "barricade_item", gui_scale=0.48, gui_y=-2.0)


NYPD_BLUE = (22, 50, 118)


def build_police_barricade():
    blue = material(32, NYPD_BLUE, 91, amp=0.07, fine=0.04,
                    cells=[(32, 4, 0.08), (16, 2, 0.06), (2, 2, 0.04)])      # wood grain along x
    save_tex("police_blue", blue)
    rail = material(64, NYPD_BLUE, 92, amp=0.06, fine=0.04, cells=[(64, 6, 0.08), (16, 2, 0.05), (2, 2, 0.04)])
    # the board face uses rows 0..16 (4 px high); lettering in two lines of the 3x5 face
    white = (236, 238, 240)
    draw_text_centred(rail, 32, 2, "POLICE LINE", white, font="35")
    draw_text_centred(rail, 32, 9, "DO NOT CROSS", white, font="35")
    # wear: paint chips on the lettering, darker along the bottom edge
    chips = np.clip(fbm(64, 93, [(3, 3, 1.6)]) - 0.45, 0, 1)
    blend_mask(rail, chips, NYPD_BLUE, 0.9)
    shade(rail[13:16], 0.85)
    save_tex("police_rail", rail)
    bracket = material(16, (150, 154, 156), 94, amp=0.08)
    save_tex("police_bracket", bracket)
    tx = {"rail": "police_rail", "blue": "police_blue", "bracket": "police_bracket"}
    rail_el = el([0, 10.6, 7.4], [16, 14.6, 8.6], "blue", faces=("north", "south", "up", "down"),
                 uv={"north": [0, 0, 16, 4], "south": [0, 0, 16, 4]},
                 texmap={"north": "rail", "south": "rail"})
    model("police_barricade_rail", tx, [rail_el], particle="police_blue")
    L = 10.6 / math.cos(math.radians(22.5))
    for side in ("left", "right"):
        x0 = -0.6 if side == "left" else 14.8
        els = []
        for ang in (22.5, -22.5):
            els.append(el([x0, 10.6 - L, 7.45], [x0 + 1.2, 10.6, 8.55], "blue", skip=("up",),
                          rot=("x", ang, (x0 + 0.6, 10.6, 8))))
        els.append(el([x0 + 0.1, 3.6, 5.3], [x0 + 1.1, 4.5, 10.7], "blue"))           # spreader
        els.append(el([x0 - 0.2, 9.8, 7.1], [x0 + 1.4, 11.0, 8.9], "bracket"))        # leg bracket
        model(f"police_barricade_legs_{side}", tx, els, particle="police_blue")
    run_blockstate("police_barricade", ["police_barricade_rail"],
                   ["police_barricade_legs_left"], ["police_barricade_legs_right"],
                   ["police_barricade_legs_left"], ["police_barricade_legs_right"])
    els = []
    for n in ("police_barricade_rail", "police_barricade_legs_left", "police_barricade_legs_right"):
        els += MODELS[n]["elements"]
    model("police_barricade_item", tx, els, particle="police_blue")
    item_model("police_barricade", "police_barricade_item", gui_scale=0.6, gui_y=0.5)


PB_ORANGE = (238, 104, 28)
# Plastic "jersey" barrier, half-depths from the centre line: a wide toe, a 45 deg
# chamfer up to the tall flat body, a 45 deg shoulder to the narrow crown.
PB_TOE = (0.0, 2.0, 3.9)
PB_BODY = (2.0, 12.0, 2.7)
PB_CROWN = (12.0, 13.6, 1.7)
PB_FEET = ((1.0, 4.0), (6.5, 9.5), (12.0, 15.0))


def plastic_textures():
    for var, (base, seed) in (("a", (PB_ORANGE, 101)), ("b", ((226, 118, 54), 102))):
        s = material(64, base, seed, amp=0.05, fine=0.025)
        yy, xx = np.mgrid[0:64, 0:64] + 0.5

        def panel(x0, x1, y0, y1, depth=0.88):
            r0, r1 = (16 - y1) * 4, (16 - y0) * 4
            c0, c1 = x0 * 4, x1 * 4
            inside = (xx > c0) & (xx < c1) & (yy > r0) & (yy < r1)
            shade(s, np.where(inside, depth, 1.0))
            shade(s, np.where(inside & (yy < r0 + 1.5), 0.8, 1.0))        # shadow under the upper lip
            shade(s, np.where(inside & (yy > r1 - 1.5), 1.12, 1.0))       # lit lower lip
            shade(s, np.where(inside & (xx < c0 + 1.2), 0.9, 1.0))
            shade(s, np.where(inside & (xx > c1 - 1.2), 1.06, 1.0))
        panel(2.0, 14.0, 3.6, 9.4)                     # big moulded side panel
        panel(5.5, 10.5, 10.0, 11.4, depth=0.55)       # hand-hold slot
        # mould seam at both ends of the unit
        shade(s[:, 0:1], 0.72)
        shade(s[:, 63:64], 0.72)
        if var == "b":
            dirt = np.clip(fbm(64, 103, [(16, 6, 1.2), (4, 4, 0.5)]) + (yy / 64 - 0.6), 0, 1)
            blend_mask(s, dirt, (120, 96, 80), 0.35)
            scuff = np.clip(fbm(64, 104, [(6, 2, 1.6)]) - 0.4, 0, 1)
            blend_mask(s, scuff, (250, 214, 180), 0.4)
        save_tex(f"pbarrier_side_{var}", s)
        top = material(32, tuple(min(255, c * 1.06) for c in base), seed + 10, amp=0.05)
        save_tex(f"pbarrier_top_{var}", top)
    save_tex("pbarrier_dark", material(16, (64, 40, 26), 105, amp=0.1))
    cap = material(16, (40, 40, 42), 106, amp=0.1)
    blend_mask(cap, disc_mask(16, 8, 8, 5.5) - disc_mask(16, 8, 8, 4.5), (80, 80, 82))
    save_tex("pbarrier_cap", cap)


def pb_profile(x0, x1, inset=0.0, feet=True, faces_x=True, top_extra=0.0):
    """Elements of the jersey profile between x0 and x1 (inset shrinks every half-depth).
    End faces (east/west) are plain plastic: the side texture's panels belong on the long faces."""
    els = []
    _el = globals()["el"]

    def el(f, t, tex, **kw):  # noqa: E306 - local wrapper: plain ends
        tm = dict(kw.pop("texmap", None) or {})
        tm.setdefault("east", "top")
        tm.setdefault("west", "top")
        return _el(f, t, tex, texmap=tm, **kw)
    ends = () if faces_x else ("east", "west")
    t0, t1, thd = PB_TOE
    hd = thd - inset
    if feet:
        for f0, f1 in PB_FEET:
            a, b = max(x0, f0), min(x1, f1)
            if b > a:
                els.append(el([a, t0, 8 - hd], [b, t1, 8 + hd], "side", texmap={"up": "top", "down": "dark"},
                              skip=ends, cull={"down": "down"}))
    else:
        els.append(el([x0, t0, 8 - hd], [x1, t1, 8 + hd], "side", texmap={"up": "top", "down": "dark"},
                      skip=ends, cull={"down": "down"}))
    b0, b1, bhd = PB_BODY
    bh = bhd - inset
    els.append(el([x0, b0, 8 - bh], [x1, b1, 8 + bh], "side", texmap={"up": "top", "down": "dark"}, skip=ends))
    # 45 deg toe chamfer: a diamond on the toe/body corner, its outer face is the slope
    d = (hd - bh) / math.sqrt(2) * 1.0
    for zc in (8 - bh, 8 + bh):
        els.append(el([x0, b0 - d, zc - d], [x1, b0 + d, zc + d], "side", texmap={"up": "top", "down": "top"},
                      skip=ends, rot=("x", 45, (x0, b0, zc))))
    c0, c1, chd = PB_CROWN
    ch = chd - inset
    els.append(el([x0, c0, 8 - ch], [x1, c1 + top_extra, 8 + ch], "side", texmap={"up": "top", "down": "dark"},
                  skip=ends + ("down",)))
    d = (bh - ch) / math.sqrt(2)
    for zc in (8 - ch, 8 + ch):
        els.append(el([x0, c0 - d, zc - d], [x1, c0 + d, zc + d], "top", skip=ends, rot=("x", 45, (x0, c0, zc))))
    return els


def build_plastic_barrier():
    plastic_textures()
    for var in ("a", "b"):
        tx = {"side": f"pbarrier_side_{var}", "top": f"pbarrier_top_{var}", "dark": "pbarrier_dark",
              "cap": "pbarrier_cap"}
        els = pb_profile(1.0, 15.0)
        els.append(el([4.5, 13.6, 7.0], [6.5, 14.1, 9.0], "cap", faces=("north", "south", "east", "west", "up")))
        model(f"plastic_barrier_core_{var}", tx, els, particle=f"pbarrier_side_{var}")
    tx = {"side": "pbarrier_side_a", "top": "pbarrier_top_a", "dark": "pbarrier_dark"}
    for side in ("left", "right"):
        # free end: the profile carried on a little, inset - a rounded-off moulded end
        els = []
        for e in pb_profile(0.35, 1.0, inset=0.45, feet=False):
            els.append(e if side == "left" else mirror_el(e))
        model(f"plastic_barrier_end_{side}", tx, els, particle="pbarrier_side_a")
        # joint: a waisted neck straddling the boundary, two proud interlock knuckles and the pin
        x0 = -1.0
        els = pb_profile(x0, x0 + 2.0, inset=0.75, feet=False, faces_x=False)
        for y0, y1 in ((3.0, 5.6), (7.6, 10.2)):
            els.append(el([x0 + 0.25, y0, 8 - PB_BODY[2] - 0.25], [x0 + 1.75, y1, 8 + PB_BODY[2] + 0.25], "side",
                          texmap={"up": "top", "down": "dark"}))
        els.append(el([x0 + 0.5, 13.0, 7.5], [x0 + 1.5, 14.5, 8.5], "dark", skip=("down",)))
        if side == "right":
            els = [mirror_el(e) for e in els]
        model(f"plastic_barrier_joint_{side}", tx, els, particle="pbarrier_side_a")
    run_blockstate("plastic_barrier", [[("plastic_barrier_core_a", 3), ("plastic_barrier_core_b", 2)]],
                   ["plastic_barrier_end_left"], ["plastic_barrier_end_right"],
                   ["plastic_barrier_joint_left"], ["plastic_barrier_joint_right"])
    els = []
    for n in ("plastic_barrier_core_a", "plastic_barrier_end_left", "plastic_barrier_end_right"):
        els += MODELS[n]["elements"]
    model("plastic_barrier_item", {"side": "pbarrier_side_a", "top": "pbarrier_top_a", "dark": "pbarrier_dark",
                                   "cap": "pbarrier_cap"}, els, particle="pbarrier_side_a")
    item_model("plastic_barrier", "plastic_barrier_item", gui_scale=0.62)


# =====================================================================
# 6. CAUTION TAPE
# =====================================================================

TAPE_Y = 12.0           # upper strand bottom (2 px band)
TAPE_LOW = 4.5          # lower strand bottom
TAPE_YELLOW = (252, 214, 30)


def tape_textures():
    t = np.zeros((64, 64, 4))
    band = canvas(64, TAPE_YELLOW)
    shade(band, 1 + fbm(64, 111, [(16, 4, 0.05), (2, 2, 0.02)]))
    # rows 4..12 = printed band (v 1..3): CAUTION every 8 px (32 texels)
    t[4:12] = band[4:12]
    for x in (2, 34):
        m = text_mask(64, x, 5, "CAUTION", font="35")
        m[:4] = 0
        m[12:] = 0
        blend_mask(t, m, (18, 18, 16))
    shade(t[4:5], 0.92)
    shade(t[11:12], 0.88)
    # rows 24..40 = plain tape (knots), rows 48..60 = silver duct tape
    t[24:40] = band[24:40]
    shade(t[24:40], 1 + fbm(64, 112, [(4, 2, 0.12)])[24:40])
    duct = canvas(64, (168, 170, 172))
    shade(duct, 1 + fbm(64, 113, [(2, 8, 0.10), (8, 8, 0.06)]))
    t[48:60] = duct[48:60]
    save_tex("caution_tape", t)
    # item icon: a roll of tape
    ic = np.zeros((16, 16, 4))
    yy, xx = np.mgrid[0:16, 0:16] + 0.5
    ring = (np.hypot(xx - 7, yy - 7) < 6.2) & (np.hypot(xx - 7, yy - 7) > 2.6)
    ic[ring] = (240, 196, 24, 255)
    shade(ic, np.where(ring & ((xx - 7) + (yy - 7) > 3), 0.85, 1.0))
    edge = ring & (np.hypot(xx - 7, yy - 7) > 5.4)
    shade(ic, np.where(edge, 0.75, 1.0))
    for ang in (0.6, 2.2, 3.9, 5.4):
        x, y = 7 + math.cos(ang) * 4.3, 7 + math.sin(ang) * 4.3
        ic[int(y), int(x), :3] = (30, 30, 28)
    tail = (xx > 8) & (xx < 15.5) & (yy > 10.5) & (yy < 13) & ~ring
    ic[tail] = (250, 210, 30, 255)
    ic[11, 9, :3] = ic[11, 12, :3] = (30, 30, 28)
    save_item_tex("caution_tape", ic)


PRINT_V = (1.0, 3.0)
PLAIN_V = (6.0, 10.0)


def fit_period(a, b, period):
    """Shift a u window into 0..16 by whole PERIODS (keeps a repeating print in phase)."""
    lo = min(a, b)
    k = math.floor(lo / period)
    a, b = a - k * period, b - k * period
    while max(a, b) > 16 + 1e-9:
        a, b = a - period, b - period
    assert min(a, b) >= -1e-9, (a, b)
    return round(a, 4), round(b, 4)


def tape_band(x0, x1, y, rot=None):
    """A zero-thickness printed strip (both faces), u follows world x, print period 8 px."""
    f, t = [x0, y, 8], [x1, y + 2, 8]
    uv = {}
    for face in ("north", "south"):
        u = auto_uv(face, f, t)
        u0, u1 = fit_period(u[0], u[2], 8)
        uv[face] = [u0, PRINT_V[0], u1, PRINT_V[1]]
    return el(f, t, "tape", faces=("north", "south"), uv=uv, rot=rot)


def tape_end(end, side, y):
    """Elements for one end of a strand at height y (authored for the left / -x end)."""
    els = []
    if end in ("tape", "wall"):
        x0 = 0.0 if end == "tape" else 0.08
        els.append(tape_band(x0, 3.5, y))
        if end == "wall":
            plain = [2, PLAIN_V[0], 3, PLAIN_V[0] + 2.8]
            els.append(el([0.08, y - 0.4, 7.4], [0.5, y + 2.4, 8.6], "tape",
                          uv={f: plain for f in FACES}, faces=("north", "south", "east", "up", "down")))
            duct = [4, 12, 7, 15]
            els.append(el([0.02, y - 0.9, 6.7], [0.14, y + 2.9, 9.3], "tape",
                          uv={f: duct for f in FACES}, faces=("east", "north", "south", "up", "down")))
    elif end == "post":
        els.append(tape_band(-8.0, 3.5, y))
        plain = [1, PLAIN_V[0], 3.6, PLAIN_V[0] + 2.8]
        els.append(el([-9.3, y - 0.35, 6.7], [-6.7, y + 2.35, 9.3], "tape", uv={f: plain for f in FACES}))
        tail = [6, PLAIN_V[0], 8, PLAIN_V[0] + 3]
        els.append(el([-8.2, y - 3.1, 9.32], [-6.4, y - 0.35, 9.32], "tape",
                      uv={"north": tail, "south": tail}, faces=("north", "south"), rot=("z", 22.5, (-7.3, y - 0.35, 9.32))))
    else:  # loose end: the last 3 px droop
        els.append(tape_band(0.3, 3.5, y, rot=("z", 45, (3.5, y + 1, 8))))
    if side == "right":
        els = [reprint(mirror_el(e)) for e in els]
    return els


def reprint(e):
    """After mirroring, a printed tape strip gets its u windows recomputed from
    its new x range (the print must stay in phase with the neighbouring cells)."""
    for face in ("north", "south"):
        fc = e["faces"].get(face)
        if fc and fc["uv"][1] == PRINT_V[0]:
            u = auto_uv(face, e["from"], e["to"])
            u0, u1 = fit_period(u[0], u[2], 8)
            fc["uv"] = [u0, PRINT_V[0], u1, PRINT_V[1]]
    return e


def mirror_el(e):
    """Mirror an element x -> 16-x (faces east<->west, z rotations negated).
    u windows are kept as authored: u is screen-oriented, so a mirrored face
    still reads left-to-right (the gen_gate_assets lesson)."""
    f, t = e["from"], e["to"]
    out = {"from": [round(16 - t[0], 4), f[1], f[2]], "to": [round(16 - f[0], 4), t[1], t[2]], "faces": {}}
    sw = {"east": "west", "west": "east"}
    for name, fc in e["faces"].items():
        out["faces"][sw.get(name, name)] = dict(fc)
    if "rotation" in e:
        r = dict(e["rotation"])
        r["origin"] = [round(16 - r["origin"][0], 4), r["origin"][1], r["origin"][2]]
        if r["axis"] in ("y", "z"):
            r["angle"] = -r["angle"]
        out["rotation"] = r
    if "shade" in e:
        out["shade"] = e["shade"]
    return out


def build_caution_tape():
    tape_textures()
    tx = {"tape": "caution_tape"}
    model("caution_tape_core", tx, [tape_band(3.5, 12.5, TAPE_Y)], particle="caution_tape")
    model("caution_tape_core_low", tx, [tape_band(3.5, 12.5, TAPE_LOW)], particle="caution_tape")
    for side in ("left", "right"):
        for end in ("none", "tape", "wall", "post"):
            model(f"caution_tape_{side}_{end}", tx, tape_end(end, side, TAPE_Y), particle="caution_tape")
            model(f"caution_tape_{side}_{end}_low", tx, tape_end(end, side, TAPE_LOW), particle="caution_tape")
    parts = []
    for axis, y in (("x", 0), ("z", 90)):
        parts.append({"when": {"axis": axis}, "apply": apply("caution_tape_core", y=y)})
        parts.append({"when": {"axis": axis, "double": "true"}, "apply": apply("caution_tape_core_low", y=y)})
        for side in ("left", "right"):
            for end in ("none", "tape", "wall", "post"):
                parts.append({"when": {"axis": axis, side: end}, "apply": apply(f"caution_tape_{side}_{end}", y=y)})
                parts.append({"when": {"axis": axis, side: end, "double": "true"},
                              "apply": apply(f"caution_tape_{side}_{end}_low", y=y)})
    write_blockstate("caution_tape", {"multipart": parts})
    AC.item_flat("caution_tape")


# =====================================================================
# 7. CONSTRUCTION WALL (MTA blue hoarding)
# =====================================================================

MTA_BLUE = (30, 74, 152)
PAINT_WHITE = (240, 242, 244)


def plywood_grain(size, seed, base=(204, 168, 116), contrast=1.0, vertical=True):
    """Rotary-cut softwood veneer: broad, irregular cathedral figure (soft bands,
    never a regular corduroy), long fibres, tone patches, a knot or two."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    across = xx if vertical else yy

    def stretched(cells):
        o = fbm(size, seed, cells)
        return o if vertical else o.T
    field = across + stretched([(size / 2, size, 26.0), (size / 4, size / 2, 9.0), (size / 8, size / 4, 3.0)])
    period = size / 5.0
    phase = (field / period) % 1.0
    late = np.exp(-((phase - 0.5) ** 2) / 0.012)                  # narrow latewood line
    early = 0.5 + 0.5 * np.cos(phase * 2 * math.pi)               # broad soft band
    vary = np.clip(0.55 + stretched([(size / 2, size / 2, 1.2)]), 0.15, 1.0)
    a = canvas(size, base)
    shade(a, 1 - contrast * vary * (0.16 * late + 0.06 * early))
    shade(a, 1 + stretched([(size, size, 0.10), (1.5, 10, 0.05), (3, 16, 0.04)]))
    rng = np.random.default_rng(seed)
    for _ in range(rng.integers(1, 3)):
        kx, ky = rng.uniform(8, size - 8, 2)
        r = rng.uniform(1.4, 2.4) * size / 64
        blend_mask(a, disc_mask(size, kx, ky, r * 1.6, 1.4), tuple(c * 0.86 for c in base), 0.6)
        blend_mask(a, disc_mask(size, kx, ky, r, 0.8), (128, 90, 52), 0.75)
        blend_mask(a, disc_mask(size, kx, ky, r * 0.45, 0.6), (92, 60, 32), 0.8)
    return a


def nails(arr, spots, rgb=(70, 66, 62)):
    for x, y in spots:
        blend_mask(arr, disc_mask(arr.shape[0], x, y, 0.75, 0.4), rgb, 0.9)


def blue_paint(seed, variant_tone=0.0):
    a = canvas(64, MTA_BLUE)
    grain = plywood_grain(64, seed, contrast=1.0)
    lum = grain[..., :3].mean(axis=2)
    shade(a, 1 + (lum - lum.mean()) / 255 * 0.35)          # grain ghosting through the paint
    shade(a, 1 + fbm(64, seed + 3, [(16, 64, 0.05), (2, 2, 0.025)]) + variant_tone)  # roller marks
    nails(a, [(x, y) for x in (2, 62) for y in range(5, 64, 12)] + [(30, y) for y in range(8, 64, 16)],
          rgb=(22, 50, 104))
    rows = np.arange(64)
    shade(a, (1 - 0.07 * np.clip((rows - 56) / 8, 0, 1))[:, None])   # scuffed foot
    return a


def build_construction_wall():
    plain = blue_paint(121)
    save_tex("cwall_plain", plain)

    notice = blue_paint(122)
    rect(notice, 4, 4, 60, 5, PAINT_WHITE)
    for i, line in enumerate(("WE'RE", "IMPROVING", "YOUR", "STATION")):
        draw_text_centred(notice, 32, 9 + i * 9, line, PAINT_WHITE)
    rect(notice, 4, 46, 60, 47, (250, 170, 40))
    draw_text_centred(notice, 32, 50, "THANK YOU FOR", PAINT_WHITE, font="35")
    draw_text_centred(notice, 32, 56, "YOUR PATIENCE", PAINT_WHITE, font="35")
    save_tex("cwall_notice", notice)

    na = blue_paint(123)
    rect(na, 2, 5, 62, 59, (30, 30, 30))                     # plate border
    rect(na, 3, 6, 61, 58, (246, 246, 242))
    rect(na, 3, 6, 61, 26, (200, 30, 36))
    draw_text_centred(na, 32, 8, "NO", PAINT_WHITE)
    draw_text_centred(na, 32, 17, "ADMITTANCE", PAINT_WHITE)
    draw_text_centred(na, 32, 30, "AUTHORIZED", (24, 24, 24))
    draw_text_centred(na, 32, 39, "PERSONNEL", (24, 24, 24))
    draw_text_centred(na, 32, 48, "ONLY", (24, 24, 24))
    nails(na, [(4.5, 7.5), (59.5, 7.5), (4.5, 56.5), (59.5, 56.5)], rgb=(120, 120, 120))
    save_tex("cwall_no_admittance", na)

    trim = canvas(32, MTA_BLUE)
    shade(trim, 1 + fbm(32, 124, [(4, 32, 0.06), (2, 2, 0.03)]))
    save_tex("cwall_trim", trim)
    base = material(32, (46, 48, 52), 125, amp=0.1)
    save_tex("cwall_base", base)
    back = plywood_grain(64, 126, base=(208, 174, 120))
    stamp = text_mask(64, 6, 26, "APA", font="35") + text_mask(64, 6, 32, "RATED 15/32", font="35")
    blend_mask(back, np.clip(stamp, 0, 1) * (0.12 + 0.18 * np.clip(fbm(64, 127, [(3, 3, 2.0)]) + 0.5, 0, 1)),
               (70, 60, 90))                                   # faded mill stamp
    save_tex("cwall_back", back)
    stud = plywood_grain(32, 128, base=(214, 186, 132), vertical=True, contrast=0.6)
    save_tex("cwall_stud", stud)

    tx = {"paint": "cwall_plain", "trim": "cwall_trim", "base": "cwall_base", "back": "cwall_back",
          "stud": "cwall_stud"}
    for v in ("plain", "notice", "no_admittance"):
        sheet = el([0, 0, 13.0], [16, 16, 14.0], "back", faces=("north", "south"),
                   texmap={"north": "face"})
        model(f"construction_wall_sheet_{v}", dict(tx, face=f"cwall_{v}"), [sheet], particle=f"cwall_{v}")
    model("construction_wall_stud", tx, [el([7.25, 0, 14.0], [8.75, 16, 15.85], "stud",
                                            faces=("south", "east", "west"))], particle="cwall_stud")
    model("construction_wall_cap", tx, [el([0, 15.2, 12.6], [16, 16, 15.9], "trim",
                                           faces=("north", "south", "up", "down"))], particle="cwall_trim")
    model("construction_wall_base", tx, [
        el([0, 0, 12.6], [16, 1.3, 14.0], "base", faces=("north", "up")),
        el([0, 0, 14.0], [16, 1.5, 15.9], "stud", faces=("south", "up"))], particle="cwall_base")
    for side in ("left", "right"):
        bx = -0.6 if side == "left" else 15.4
        model(f"construction_wall_batten_{side}", tx, [
            el([bx, 0, 12.75], [bx + 1.2, 16, 13.0], "trim", faces=("north", "east", "west")),
            el([bx + 0.0, 0, 14.0], [bx + 1.2, 16, 15.85], "stud", faces=("south", "east", "west"))],
            particle="cwall_trim")
        f, t = box_lr(side, 0.02, 0, 12.5, 1.3, 16, 15.98)
        model(f"construction_wall_trim_{side}", tx, [
            el(f, t, "trim", faces=("north", "south", "east", "west", "up"))], particle="cwall_trim")
    parts = []
    for fc in FACING4:
        y = Y_ROT[fc]
        for v in ("plain", "notice", "no_admittance"):
            parts.append({"when": {"facing": fc, "variant": v}, "apply": apply(f"construction_wall_sheet_{v}", y=y)})
        parts.append({"when": {"facing": fc}, "apply": apply("construction_wall_stud", y=y)})
        parts.append({"when": {"facing": fc, "up": "false"}, "apply": apply("construction_wall_cap", y=y)})
        parts.append({"when": {"facing": fc, "down": "false"}, "apply": apply("construction_wall_base", y=y)})
        parts.append({"when": {"facing": fc, "left": "false"}, "apply": apply("construction_wall_trim_left", y=y)})
        parts.append({"when": {"facing": fc, "right": "false"}, "apply": apply("construction_wall_trim_right", y=y)})
        if fc in ("north", "east"):
            parts.append({"when": {"facing": fc, "left": "true"}, "apply": apply("construction_wall_batten_left", y=y)})
        else:
            parts.append({"when": {"facing": fc, "right": "true"}, "apply": apply("construction_wall_batten_right", y=y)})
    write_blockstate("construction_wall", {"multipart": parts})
    els = []
    for n in ("construction_wall_sheet_notice", "construction_wall_stud", "construction_wall_cap",
              "construction_wall_base", "construction_wall_trim_left", "construction_wall_trim_right"):
        els += MODELS[n]["elements"]
    model("construction_wall_item", dict(tx, face="cwall_notice"), els, particle="cwall_notice")
    item_model("construction_wall", "construction_wall_item", gui_scale=0.62, rot=(30, 160, 0))


# =====================================================================
# 8. PLYWOOD BOARD
# =====================================================================

def stencil(arr, cx, y, s, rgb, scale=2, seed=0):
    """Spray-stencilled block letters: bridges cut through every counter, soft overspray."""
    m = text_mask(64, cx - text_width(s, "57", scale, 1) / 2.0, y, s, "57", scale, 1)
    # stencil bridges: every closed bowl is held by ties - cut each horizontal bar of
    # the closed letters at the glyph's centre column
    x = int(round(cx - text_width(s, "57", scale, 1) / 2.0))
    for ch in s:
        rows = glyph(ch, "57")
        gw = len(rows[0])
        if ch in "OBDPRQA08690":
            mid = x + (gw * scale) // 2
            for r, row in enumerate(rows):
                if row.count("#") >= 3 and row[gw // 2] == "#":
                    m[y + r * scale:y + (r + 1) * scale, mid - 1:mid + 1] = 0
        x += (gw + 1) * scale
    over = np.clip(fbm(64, seed, [(2, 2, 1.0)]) + 0.2, 0, 1)
    from PIL import ImageFilter
    halo = np.asarray(Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.1)),
                      dtype=np.float64) / 255
    blend_mask(arr, halo * over, rgb, 0.35)
    blend_mask(arr, m * (0.82 + 0.18 * over), rgb)


def spray(arr, x, y, s, rgb, scale, seed, slant=0.0):
    """Hand-sprayed letters: each glyph pixel a soft dab with jitter, a few drips."""
    rng = np.random.default_rng(seed)
    cx = x
    size = arr.shape[0]
    total = np.zeros((size, size))
    for ch in s:
        rows = glyph(ch, "57")
        jx, jy = rng.normal(0, 0.35, 2)
        for gy, row in enumerate(rows):
            for gx, bit in enumerate(row):
                if bit == "#":
                    px = cx + gx * scale + jx + slant * (6 - gy) * scale * 0.18
                    py = y + gy * scale + jy
                    total = np.maximum(total, disc_mask(size, px + scale / 2, py + scale / 2, scale * 0.78, 0.9))
        if rng.random() < 0.45:
            dx = cx + rng.integers(0, len(rows[0])) * scale + scale / 2
            dy0 = y + 7 * scale
            total = np.maximum(total, line_mask(size, dx, dy0 - 1, dx, dy0 + rng.uniform(2, 6), 0.9) * 0.9)
        cx += (len(rows[0]) + 1) * scale
    blend_mask(arr, total, rgb)


def build_plywood_board():
    grains = {}
    for g, seed in (("a", 131), ("b", 132), ("c", 133)):
        p = plywood_grain(64, seed)
        nails(p, [(x, y) for x in (2.5, 61.5) for y in (4, 22, 42, 60)] + [(32, 3), (32, 61)])
        rows = np.arange(64)
        weather = np.clip(fbm(64, seed + 1, [(32, 16, 1.0)]) + rows[:, None] / 64 * 0.6 - 0.2, 0, 1)
        blend_mask(p, weather, (150, 128, 100), 0.35)            # rain-greyed
        grains[g] = p
        save_tex(f"ply_plain_{g}", p)
    for g in ("a", "b"):
        p = grains[g].copy()
        stencil(p, 32, 6, "POST", (26, 24, 22), seed=140)
        stencil(p, 32, 24, "NO", (26, 24, 22), seed=141)
        stencil(p, 32, 42, "BILLS", (26, 24, 22), seed=142)
        save_tex(f"ply_post_no_bills_{g}", p)
        q = grains["c" if g == "a" else "a"].copy()
        spray(q, 20, 12, "NO", (196, 28, 30), 2, seed=150 + ord(g), slant=0.3)
        spray(q, 0.6, 38, "TRESPASSING", (196, 28, 30), 1, seed=152 + ord(g), slant=0.2)
        save_tex(f"ply_no_trespassing_{g}", q)
    edge = material(32, (206, 170, 116), 134, amp=0.05)
    for r in range(0, 32, 2):                               # plies seen on edge (1 px = 2 texels)
        shade(edge[r:r + 1], 0.8 if (r // 2) % 2 else 1.06)
    save_tex("ply_edge_h", edge)                            # plies run along u
    save_tex("ply_edge_v", np.transpose(edge, (1, 0, 2)).copy())   # plies run along v

    edge_v = [6, 0, 8, 16]
    edge_h = [0, 6, 16, 8]
    for v, texs in (("plain", ["a", "b", "c"]), ("post_no_bills", ["a", "b"]), ("no_trespassing", ["a", "b"])):
        for g in texs:
            tx = {"face": f"ply_{v}_{g}", "edge": "ply_edge_h", "edgev": "ply_edge_v"}
            e = el([0, 0, 15], [16, 16, 16], "edge",
                   uv={"east": edge_v, "west": edge_v, "up": edge_h, "down": edge_h},
                   texmap={"north": "face", "south": "face", "east": "edgev", "west": "edgev"},
                   cull={"south": "south"})
            model(f"plywood_board_{v}_{g}", tx, [e], particle=f"ply_{v}_{g}")
    variants = {}
    for fc in FACING4:
        for v, texs in (("plain", ["a", "b", "c"]), ("post_no_bills", ["a", "b"]), ("no_trespassing", ["a", "b"])):
            variants[f"facing={fc},variant={v}"] = [apply(f"plywood_board_{v}_{g}", y=Y_ROT[fc], weight=1) for g in texs]
    write_blockstate("plywood_board", {"variants": variants})
    item_model("plywood_board", "plywood_board_post_no_bills_a", gui_scale=0.62, rot=(30, 160, 0))


# =====================================================================
# 9. TUBE SCAFFOLD
# =====================================================================

def build_scaffold():
    tube = canvas(32, (158, 164, 168))
    xs = np.arange(32) + 0.5
    shade(tube, (1 + 0.10 * np.cos((xs % 8 - 4) / 4 * math.pi))[None, :] * np.ones((32, 1)))
    shade(tube, 1 + fbm(32, 161, [(4, 16, 0.08), (2, 2, 0.04)]))
    spang = np.clip(fbm(32, 162, [(3, 3, 1.5)]) - 0.35, 0, 1)            # galvanizing spangle
    blend_mask(tube, spang, (190, 196, 198), 0.5)
    save_tex("scaf_tube", tube)
    clamp = material(16, (104, 108, 110), 163, amp=0.12)
    save_tex("scaf_clamp", clamp)
    cap = material(16, (236, 196, 30), 164, amp=0.06)
    save_tex("scaf_cap", cap)
    plate = material(16, (120, 122, 124), 165, amp=0.10)
    save_tex("scaf_plate", plate)
    plank = plywood_grain(64, 166, base=(184, 150, 100), vertical=False, contrast=0.8)
    rows = np.arange(64)
    shade(plank, 1 + fbm(64, 167, [(64, 8, 0.12)]))
    blend_mask(plank, np.clip(fbm(64, 168, [(12, 6, 1.4)]) - 0.2, 0, 1), (120, 112, 100), 0.45)  # mud & paint
    plank[:, 0:3, :3] = (150, 154, 156)                     # steel end bands
    plank[:, 61:64, :3] = (150, 154, 156)
    save_tex("scaf_plank", plank)

    tx = {"tube": "scaf_tube", "clamp": "scaf_clamp", "cap": "scaf_cap", "plate": "scaf_plate",
          "plank": "scaf_plank"}
    corners = {"nw": (0, 0), "ne": (16, 0), "sw": (0, 16), "se": (16, 16)}
    for name, (cx, cz) in corners.items():
        model(f"scaffold_std_{name}", tx, [
            el([cx - 0.8, 0, cz - 0.8], [cx + 0.8, 16, cz + 0.8], "tube", faces=("north", "south", "east", "west")),
            el([cx - 1.05, 12.1, cz - 1.05], [cx + 1.05, 15.5, cz + 1.05], "clamp")], particle="scaf_tube")
        model(f"scaffold_cap_{name}", tx, [
            el([cx - 0.95, 15.5, cz - 0.95], [cx + 0.95, 16.6, cz + 0.95], "cap", skip=("down",))], particle="scaf_cap")
        model(f"scaffold_base_{name}", tx, [
            el([cx - 1.6, 0, cz - 1.6], [cx + 1.6, 0.4, cz + 1.6], "plate", cull={"down": "down"}),
            el([cx - 0.5, 0.4, cz - 0.5], [cx + 0.5, 1.6, cz + 0.5], "clamp", skip=("down", "up"))],
            particle="scaf_plate")
    # ledgers (along x, N/S edges) under transoms (along z, W/E edges)
    model("scaffold_ledger_n", tx, [el([0, 12.4, -0.7], [16, 13.8, 0.7], "tube",
                                       faces=("north", "south", "up", "down"))], particle="scaf_tube")
    model("scaffold_ledger_s", tx, [el([0, 12.4, 15.3], [16, 13.8, 16.7], "tube",
                                       faces=("north", "south", "up", "down"))], particle="scaf_tube")
    model("scaffold_transom_w", tx, [el([-0.7, 13.8, 0], [0.7, 15.2, 16], "tube",
                                        faces=("east", "west", "up", "down"))], particle="scaf_tube")
    model("scaffold_transom_e", tx, [el([15.3, 13.8, 0], [16.7, 15.2, 16], "tube",
                                        faces=("east", "west", "up", "down"))], particle="scaf_tube")
    # diagonal brace on the facing (north) side, clamped to the outer face of the standards
    d = 10.6
    model("scaffold_brace", tx, [
        el([8 - d, 7.4, -2.05], [8 + d, 8.6, -0.85], "tube", faces=("north", "south", "up", "down", "east", "west"),
           rot=("z", 45, (8, 8, -1.45))),
        el([-0.4, 0.4, -2.3], [1.6, 2.4, -0.8], "clamp"),
        el([14.4, 13.6, -2.3], [16.4, 15.6, -0.8], "clamp")], particle="scaf_tube")
    planks = []
    zs = [(0.9, 4.3), (4.5, 7.9), (8.1, 11.5), (11.7, 15.1)]
    for i, (z0, z1) in enumerate(zs):
        f, t = [0.3, 15.0, z0], [15.7, 16.0, z1]
        uv = {"up": [0, 4 * i, 16, 4 * i + 3.4], "down": [0, 4 * i, 16, 4 * i + 3.4]}
        planks.append(el(f, t, "plank", uv=uv))
    model("scaffold_deck", tx, planks, particle="scaf_plank")

    parts = []
    parts.append({"apply": apply("scaffold_std_nw")})
    parts.append({"when": {"east": "false"}, "apply": apply("scaffold_std_ne")})
    parts.append({"when": {"south": "false"}, "apply": apply("scaffold_std_sw")})
    parts.append({"when": {"east": "false", "south": "false"}, "apply": apply("scaffold_std_se")})
    parts.append({"when": {"up": "false"}, "apply": apply("scaffold_cap_nw")})
    parts.append({"when": {"up": "false", "east": "false"}, "apply": apply("scaffold_cap_ne")})
    parts.append({"when": {"up": "false", "south": "false"}, "apply": apply("scaffold_cap_sw")})
    parts.append({"when": {"up": "false", "east": "false", "south": "false"}, "apply": apply("scaffold_cap_se")})
    parts.append({"when": {"down": "false"}, "apply": apply("scaffold_base_nw")})
    parts.append({"when": {"down": "false", "east": "false"}, "apply": apply("scaffold_base_ne")})
    parts.append({"when": {"down": "false", "south": "false"}, "apply": apply("scaffold_base_sw")})
    parts.append({"when": {"down": "false", "east": "false", "south": "false"}, "apply": apply("scaffold_base_se")})
    parts.append({"apply": apply("scaffold_ledger_n")})
    parts.append({"apply": apply("scaffold_transom_w")})
    parts.append({"when": {"south": "false"}, "apply": apply("scaffold_ledger_s")})
    parts.append({"when": {"east": "false"}, "apply": apply("scaffold_transom_e")})
    for fc in FACING4:
        parts.append({"when": {"facing": fc}, "apply": apply("scaffold_brace", y=Y_ROT[fc])})
    parts.append({"when": {"OR": [{"up": "false"}, {"deck": "true"}]}, "apply": apply("scaffold_deck")})
    write_blockstate("scaffold", {"multipart": parts})
    els = []
    for n in ("scaffold_std_nw", "scaffold_std_ne", "scaffold_std_sw", "scaffold_std_se", "scaffold_ledger_n",
              "scaffold_ledger_s", "scaffold_transom_w", "scaffold_transom_e", "scaffold_brace", "scaffold_deck",
              "scaffold_base_nw", "scaffold_base_ne", "scaffold_base_sw", "scaffold_base_se"):
        els += MODELS[n]["elements"]
    model("scaffold_item", tx, els, particle="scaf_tube")
    item_model("scaffold", "scaffold_item", gui_scale=0.55)


# =====================================================================
# 10-12. SIDEWALK SHED, POST, LIGHT
# =====================================================================

SHED_GREEN = (40, 72, 48)


def build_sidewalk_shed():
    ply = canvas(64, SHED_GREEN)
    g = plywood_grain(64, 171)
    lum = g[..., :3].mean(axis=2)
    shade(ply, 1 + (lum - lum.mean()) / 255 * 0.45)
    shade(ply, 1 + fbm(64, 172, [(64, 16, 0.08), (2, 2, 0.03)]))
    nails(ply, [(x, y) for x in (2, 62) for y in range(4, 64, 10)], rgb=(28, 50, 34))
    streak = np.clip(fbm(64, 173, [(4, 64, 1.3)]) - 0.2, 0, 1) * (np.arange(64)[:, None] / 64)
    blend_mask(ply, streak, (30, 44, 34), 0.35)                # rain streaks down the parapet
    save_tex("shed_ply", ply)
    top = canvas(64, (58, 80, 58))
    shade(top, 1 + fbm(64, 174, [(32, 32, 0.12), (8, 8, 0.08), (2, 2, 0.04)]))
    blend_mask(top, np.clip(fbm(64, 175, [(10, 10, 1.4)]) - 0.25, 0, 1), (92, 90, 76), 0.5)   # grit, leaves
    shade(top, np.where((np.arange(64) % 32 == 0)[None, :], 0.75, 1.0))                     # sheet joints
    save_tex("shed_top", top)
    steel = material(32, (34, 62, 42), 176, amp=0.08, fine=0.04)
    save_tex("shed_steel", steel)

    tx = {"ply": "shed_ply", "top": "shed_top", "steel": "shed_steel"}
    # The shed cell sits ON the posts: beams at the bottom of the cell, the deck on
    # them (walkable at y 5), the plywood parapet standing up to y 15.5.
    model("sidewalk_shed_deck", tx, [el([0, 3.4, 0], [16, 5.0, 16], "ply", faces=("up", "down"),
                                        texmap={"up": "top"})], particle="shed_ply")
    beams = []
    for zc in (4.0, 12.0):
        beams.append(el([0, 0.6, zc - 0.4], [16, 3.4, zc + 0.4], "steel", faces=("north", "south")))
        beams.append(el([0, 0.0, zc - 1.3], [16, 0.6, zc + 1.3], "steel", faces=("north", "south", "up", "down")))
    model("sidewalk_shed_beams", tx, beams, particle="shed_steel")
    PH = 15.5
    model("sidewalk_shed_parapet_n", tx, [el([0, 0.05, 0.05], [16, PH - 0.4, 1.0], "ply"),
                                          el([0, PH - 0.8, -0.25], [16, PH, 1.3], "steel", skip=("east", "west")),
                                          el([0, 0, -0.25], [16, 5.2, 0.3], "steel", skip=("east", "west"))],
          particle="shed_ply")
    model("sidewalk_shed_parapet_s", tx, [el([0, 0.05, 15.0], [16, PH - 0.4, 15.95], "ply"),
                                          el([0, PH - 0.8, 14.7], [16, PH, 16.25], "steel", skip=("east", "west")),
                                          el([0, 0, 15.7], [16, 5.2, 16.25], "steel", skip=("east", "west"))],
          particle="shed_ply")
    model("sidewalk_shed_parapet_w", tx, [el([0.05, 0.05, 0], [1.0, PH - 0.4, 16], "ply"),
                                          el([-0.25, PH - 0.8, 0], [1.3, PH, 16], "steel", skip=("north", "south")),
                                          el([-0.25, 0, 0], [0.3, 5.2, 16], "steel", skip=("north", "south"))],
          particle="shed_ply")
    model("sidewalk_shed_parapet_e", tx, [el([15.0, 0.05, 0], [15.95, PH - 0.4, 16], "ply"),
                                          el([14.7, PH - 0.8, 0], [16.25, PH, 16], "steel", skip=("north", "south")),
                                          el([15.7, 0, 0], [16.25, 5.2, 16], "steel", skip=("north", "south"))],
          particle="shed_ply")
    parts = [{"apply": apply("sidewalk_shed_deck")},
             {"when": {"axis": "x"}, "apply": apply("sidewalk_shed_beams")},
             {"when": {"axis": "z"}, "apply": apply("sidewalk_shed_beams", y=90)}]
    for d, n in (("north", "n"), ("south", "s"), ("west", "w"), ("east", "e")):
        parts.append({"when": {d: "false"}, "apply": apply(f"sidewalk_shed_parapet_{n}")})
    write_blockstate("sidewalk_shed", {"multipart": parts})
    els = []
    for n in ("sidewalk_shed_deck", "sidewalk_shed_beams", "sidewalk_shed_parapet_s", "sidewalk_shed_parapet_e"):
        els += MODELS[n]["elements"]
    model("sidewalk_shed_item", tx, els, particle="shed_ply")
    item_model("sidewalk_shed", "sidewalk_shed_item", gui_scale=0.62)

    # --- post
    pad = plywood_grain(32, 177, base=(150, 120, 86), contrast=0.7)
    save_tex("shed_pad", pad)
    bolt = material(16, (86, 90, 88), 178, amp=0.1)
    save_tex("shed_bolt", bolt)
    tx = {"steel": "shed_steel", "pad": "shed_pad", "bolt": "shed_bolt"}
    model("sidewalk_shed_post_column", tx, [
        el([6.0, 0, 6.0], [6.8, 16, 10.0], "steel", faces=("north", "south", "east", "west")),
        el([9.2, 0, 6.0], [10.0, 16, 10.0], "steel", faces=("north", "south", "east", "west")),
        el([6.8, 0, 7.6], [9.2, 16, 8.4], "steel", faces=("north", "south"))], particle="shed_steel")
    base = [el([2.5, 0, 2.5], [13.5, 0.8, 13.5], "pad", cull={"down": "down"}),
            el([4.0, 0.8, 4.0], [12.0, 1.3, 12.0], "steel", skip=("down",))]
    for bx in (4.8, 10.6):
        for bz in (4.8, 10.6):
            base.append(el([bx, 1.3, bz], [bx + 0.6, 1.8, bz + 0.6], "bolt", skip=("down",)))
    model("sidewalk_shed_post_base", tx, base, particle="shed_steel")
    cap = [el([3.0, 15.2, 5.85], [13.0, 16.0, 10.15], "steel"),
           el([3.0, 13.4, 7.6], [7.0, 14.2, 8.4], "steel", rot=("z", -45, (5.0, 13.8, 8))),
           el([9.0, 13.4, 7.6], [13.0, 14.2, 8.4], "steel", rot=("z", 45, (11.0, 13.8, 8)))]
    model("sidewalk_shed_post_cap", tx, cap, particle="shed_steel")
    parts = []
    for axis, y in (("x", 0), ("z", 90)):
        parts.append({"when": {"axis": axis}, "apply": apply("sidewalk_shed_post_column", y=y)})
        parts.append({"when": {"axis": axis, "down": "false"}, "apply": apply("sidewalk_shed_post_base", y=y)})
        parts.append({"when": {"axis": axis, "up": "false"}, "apply": apply("sidewalk_shed_post_cap", y=y)})
    write_blockstate("sidewalk_shed_post", {"multipart": parts})
    els = []
    for n in ("sidewalk_shed_post_column", "sidewalk_shed_post_base", "sidewalk_shed_post_cap"):
        els += MODELS[n]["elements"]
    model("sidewalk_shed_post_item", tx, els, particle="shed_steel")
    item_model("sidewalk_shed_post", "sidewalk_shed_post_item")


def light_textures():
    bulb = canvas(16, (255, 236, 176))
    yy, xx = np.mgrid[0:16, 0:16] + 0.5
    blend_mask(bulb, disc_mask(16, 8, 8, 5.5, 4.0), (255, 252, 236), 0.9)
    blend_mask(bulb, line_mask(16, 5, 6, 11, 6, 0.8), (255, 190, 90), 0.8)          # filament glow
    save_tex("lamp_bulb", bulb)
    cage = np.zeros((16, 16, 4))
    wire = (60, 62, 60, 255)
    for x in (0, 4, 8, 12):
        cage[:, x:x + 1] = wire
    cage[0:1] = wire
    cage[15:16] = wire
    cage[7:8] = wire
    save_tex("lamp_cage", cage)
    sock = material(16, (34, 30, 28), 181, amp=0.08)
    for r in (3, 7, 11):
        shade(sock[r:r + 1], 1.3)
    save_tex("lamp_socket", sock)
    save_tex("lamp_box", material(16, (60, 84, 64), 182, amp=0.08))
    save_tex("cord_orange", material(16, (236, 112, 24), 183, amp=0.06))
    save_tex("lamp_chrome", material(16, (150, 154, 158), 184, amp=0.14,
                                     cells=[(16, 4, 0.18), (4, 4, 0.08), (2, 2, 0.04)]))
    save_tex("lamp_rubber", material(16, (30, 30, 30), 185, amp=0.08))
    save_tex("lamp_yellow", material(16, (240, 192, 26), 186, amp=0.05))


def build_shed_light():
    tx = {"box": "lamp_box", "sock": "lamp_socket", "bulb": "lamp_bulb", "cage": "lamp_cage", "cord": "cord_orange"}
    els = [el([5.8, 15.3, 5.8], [10.2, 16.0, 10.2], "box", skip=("up",)),
           el([7.6, 13.6, 7.6], [8.4, 15.3, 8.4], "box", faces=("north", "south", "east", "west")),
           el([6.8, 11.6, 6.8], [9.2, 13.6, 9.2], "sock", skip=("up",)),
           el([6.5, 8.0, 6.5], [9.5, 11.6, 9.5], "bulb", skip=("up",), shade_on=False),
           el([7.1, 7.3, 7.1], [8.9, 8.0, 8.9], "bulb", skip=("up",), shade_on=False),
           el([5.5, 6.6, 5.5], [10.5, 12.2, 10.5], "cage", uv={f: [0, 0, 16, 16] for f in FACES})]
    model("sidewalk_shed_light", tx, els, particle="lamp_box")
    write_blockstate("sidewalk_shed_light", {"variants": {"": apply("sidewalk_shed_light")}})
    item_model("sidewalk_shed_light", "sidewalk_shed_light", gui_scale=0.9, gui_y=-2.5)


def build_work_light():
    lens = canvas(32, (250, 250, 240))
    yy, xx = np.mgrid[0:32, 0:32] + 0.5
    for cx in range(4, 32, 5):
        for cy in range(4, 32, 5):
            blend_mask(lens, disc_mask(32, cx, cy, 1.4, 0.6), (255, 255, 255), 0.9)
            blend_mask(lens, disc_mask(32, cx, cy, 2.2, 0.8) - disc_mask(32, cx, cy, 1.5, 0.6), (222, 226, 220), 0.6)
    save_tex("wl_lens", lens)
    fins = canvas(32, (30, 30, 32))
    for c in range(0, 32, 3):
        fins[:, c:c + 1, :3] = (62, 62, 64)
    save_tex("wl_fins", fins)
    tx = {"y": "lamp_yellow", "k": "lamp_rubber", "lens": "wl_lens", "fins": "wl_fins", "cord": "cord_orange",
          "steel": "scaf_tube"}
    L = 12.0 / math.cos(math.radians(22.5))
    sp = L * math.sin(math.radians(22.5))
    els = [el([7.0, 11.0, 7.0], [9.0, 12.8, 9.0], "k")]
    for axis, ang in (("z", -22.5), ("z", 22.5), ("x", -22.5)):
        els.append(el([7.55, 12 - L, 7.55], [8.45, 12, 8.45], "steel", skip=("up",), rot=(axis, ang, (8, 12, 8))))
    for fx, fz in ((8 - sp, 8), (8 + sp, 8), (8, 8 + sp)):
        els.append(el([fx - 0.8, 0, fz - 0.8], [fx + 0.8, 0.7, fz + 0.8], "k", cull={"down": "down"}))
    els += [el([7.3, 12.8, 7.3], [8.7, 19.0, 8.7], "steel", faces=("north", "south", "east", "west")),
            el([7.0, 18.6, 7.0], [9.0, 19.6, 9.0], "k"),
            el([8.9, 18.7, 7.6], [10.0, 19.5, 8.4], "y"),
            el([7.6, 19.6, 7.6], [8.4, 22.0, 8.4], "steel", faces=("north", "south", "east", "west")),
            el([4.2, 21.6, 7.4], [11.8, 22.4, 8.6], "k"),
            el([4.2, 22.4, 7.4], [5.0, 25.6, 8.6], "k", skip=("down",)),
            el([11.0, 22.4, 7.4], [11.8, 25.6, 8.6], "k", skip=("down",))]
    hr = ("x", -22.5, (8, 24.9, 8))
    els += [el([5.05, 22.8, 6.6], [10.95, 27.0, 9.4], "y", texmap={"south": "fins"}, rot=hr),
            el([5.5, 23.25, 6.45], [10.5, 26.55, 6.6], "k", faces=("north", "east", "west", "up", "down"),
               uv={"north": [0, 0, 16, 16 * 3.3 / 5.0]}, texmap={"north": "lens"}, rot=hr, shade_on=False),
            el([5.8, 27.0, 7.6], [6.6, 28.2, 8.4], "k", rot=hr),
            el([9.4, 27.0, 7.6], [10.2, 28.2, 8.4], "k", rot=hr),
            el([5.8, 28.2, 7.6], [10.2, 28.9, 8.4], "k", rot=hr)]
    els += [el([8.75, 0.5, 8.45], [9.25, 21.6, 8.95], "cord", faces=("north", "south", "east", "west")),
            el([8.75, 0, 8.95], [9.25, 0.5, 16], "cord", skip=("south", "down"))]
    model("work_light", tx, els, particle="lamp_yellow")
    write_blockstate("work_light", {"variants": {f"facing={fc}": apply("work_light", y=Y_ROT[fc]) for fc in FACING4}})
    item_model("work_light", "work_light", gui_scale=0.42, gui_y=-3.0)


def build_caged_work_lamp():
    tx = {"chrome": "lamp_chrome", "bulb": "lamp_bulb", "cage": "lamp_cage", "cord": "cord_orange",
          "k": "lamp_rubber", "y": "lamp_yellow"}
    hang = [el([7.7, 15.0, 7.7], [8.3, 16.0, 8.3], "k", faces=("north", "south", "east", "west")),
            el([7.7, 15.4, 8.3], [8.3, 16.0, 9.3], "k", faces=("north", "south", "east", "west", "down")),
            el([7.6, 12.4, 7.6], [8.4, 15.0, 8.4], "cord", faces=("north", "south", "east", "west")),
            el([6.9, 9.8, 6.9], [9.1, 12.4, 9.1], "k", skip=()),
            el([6.5, 9.2, 6.5], [9.5, 9.8, 9.5], "y"),
            el([5.6, 9.0, 5.6], [10.4, 9.25, 10.4], "chrome"),
            # half shield inside the cage: back plate + two wings (a U round the bulb)
            el([6.2, 4.0, 6.0], [9.8, 8.9, 6.4], "chrome"),
            el([6.0, 4.2, 6.4], [6.4, 8.7, 8.0], "chrome"),
            el([9.6, 4.2, 6.4], [10.0, 8.7, 8.0], "chrome"),
            el([6.8, 4.8, 6.8], [9.2, 8.8, 9.2], "bulb", skip=("up",), shade_on=False),
            el([7.4, 4.3, 7.4], [8.6, 4.8, 8.6], "bulb", skip=("up",), shade_on=False),
            el([5.8, 3.5, 5.8], [10.2, 9.0, 10.2], "cage", uv={f: [0, 0, 16, 16] for f in FACES})]
    model("caged_work_lamp_hanging", tx, hang, particle="lamp_chrome")
    lie = [el([3.0, 0.2, 5.8], [8.4, 4.6, 10.2], "cage", cull={"down": "down"}, uv={f: [0, 0, 16, 16] for f in FACES}),
           el([3.6, 1.2, 6.8], [7.8, 3.6, 9.2], "bulb", shade_on=False),
           el([3.4, 3.6, 6.2], [8.0, 4.0, 9.8], "chrome"),
           el([3.6, 2.0, 6.0], [7.8, 3.6, 6.4], "chrome"),
           el([3.6, 2.0, 9.6], [7.8, 3.6, 10.0], "chrome"),
           el([8.4, 0.8, 6.2], [8.7, 4.0, 9.8], "chrome"),
           el([8.7, 1.0, 6.5], [9.3, 4.0, 9.5], "y"),
           el([9.3, 1.3, 6.9], [12.0, 3.5, 9.1], "k"),
           el([12.0, 1.9, 7.6], [13.0, 2.7, 8.4], "cord"),
           el([12.6, 0.0, 7.6], [16.0, 0.8, 8.4], "cord", skip=("east",), cull={"down": "down"})]
    model("caged_work_lamp_lying", tx, lie, particle="lamp_chrome")
    write_blockstate("caged_work_lamp", {"variants": {
        "hanging=true": apply("caged_work_lamp_hanging"),
        "hanging=false": [apply("caged_work_lamp_lying", y=y, weight=1) for y in (0, 90, 180, 270)]}})
    item_model("caged_work_lamp", "caged_work_lamp_hanging", gui_scale=0.85, gui_y=-1.0)


# =====================================================================
# SHARED FILES, LOOT, VERIFY
# =====================================================================

def write_shared():
    AC.upsert_lang({f"block.{MOD}.{b}": LANG[b] for b in BLOCKS})
    for tool, ids in TAGS.items():
        AC.insert_into_tag(f"minecraft/tags/blocks/mineable/{tool}.json", [f"{MOD}:{b}" for b in ids])
    for b in BLOCKS:
        if b == "traffic_cone":
            continue
        AC.loot_self(b)
    entry = {"type": "minecraft:item", "name": f"{MOD}:traffic_cone", "functions": []}
    for n in (2, 3):
        entry["functions"].append({"function": "minecraft:set_count", "count": n, "add": False,
                                   "conditions": [{"condition": "minecraft:block_state_property",
                                                   "block": f"{MOD}:traffic_cone", "properties": {"count": str(n)}}]})
    AC.write_json(os.path.join(AC.DATA, MOD, "loot_tables/blocks/traffic_cone.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


def iter_applies(bs):
    if "variants" in bs:
        for key, ap in bs["variants"].items():
            yield key, None, ap
    for part in bs.get("multipart", []):
        yield None, part.get("when"), part["apply"]


def when_pairs(when):
    if when is None:
        return
    if "OR" in when:
        for w in when["OR"]:
            yield from when_pairs(w)
        return
    if "AND" in when:
        for w in when["AND"]:
            yield from when_pairs(w)
        return
    for k, v in when.items():
        for vv in str(v).split("|"):
            yield k, vv


def verify():
    errors = []
    java_src = {}
    for b in BLOCKS:
        p = os.path.join(JAVA_DIR, JAVA_FILES[b])
        java_src[b] = open(p).read() if os.path.exists(p) else ""
        if not java_src[b]:
            errors.append(f"{b}: missing Java {JAVA_FILES[b]}")
        for prop, values in PROPS[b].items():
            src = java_src[b]
            needles = [f'"{prop}"', f"Properties.{prop.upper()}"]
            if prop == "facing":
                needles += ["Properties.HORIZONTAL_FACING", "extends FacingDecorBlock"]
            if prop == "axis":
                needles += ["Properties.HORIZONTAL_AXIS"]
            if not any(n in src for n in needles):
                errors.append(f"{b}: property '{prop}' not found in {JAVA_FILES[b]}")
            for v in values:
                if v in ("true", "false") or prop in ("facing", "axis", "count", "north", "east", "south", "west"):
                    continue
                if f'"{v}"' not in src:
                    errors.append(f"{b}: value {prop}={v} not found in {JAVA_FILES[b]}")
    for b in BLOCKS:
        path = os.path.join(ASSETS, "blockstates", b + ".json")
        try:
            bs = json.load(open(path))
        except Exception as ex:  # noqa: BLE001
            errors.append(f"{b}: blockstate unreadable ({ex})")
            continue
        props = PROPS[b]
        for key, when, ap in iter_applies(bs):
            if key is not None and key != "":
                for kv in key.split(","):
                    k, v = kv.split("=")
                    if k not in props or v not in props[k]:
                        errors.append(f"{b}: variant key {k}={v} not a property value")
            for k, v in when_pairs(when):
                if k not in props or v not in props[k]:
                    errors.append(f"{b}: when-key {k}={v} not a property value")
            for a in (ap if isinstance(ap, list) else [ap]):
                mid = a["model"]
                rel = mid.split(":", 1)[1]
                if not os.path.exists(os.path.join(ASSETS, "models", rel + ".json")):
                    errors.append(f"{b}: model {mid} missing")
        if not os.path.exists(os.path.join(ASSETS, "models/item", b + ".json")):
            errors.append(f"{b}: item model missing")
        if not os.path.exists(os.path.join(AC.DATA, MOD, "loot_tables/blocks", b + ".json")):
            errors.append(f"{b}: loot table missing")
    for fn in os.listdir(MODEL_DIR):
        m = json.load(open(os.path.join(MODEL_DIR, fn)))
        tx = m.get("textures", {})
        for k, ref in tx.items():
            rel = ref.split(":", 1)[1]
            if not os.path.exists(os.path.join(ASSETS, "textures", rel + ".png")):
                errors.append(f"{fn}: texture {ref} missing")
        for e in m.get("elements", []):
            for c in e["from"] + e["to"]:
                if not -16 <= c <= 32:
                    errors.append(f"{fn}: coordinate {c} outside -16..32")
            if "rotation" in e and e["rotation"]["angle"] not in (-45, -22.5, 0, 22.5, 45):
                errors.append(f"{fn}: bad rotation angle {e['rotation']['angle']}")
            for face, fc in e["faces"].items():
                if fc["texture"][1:] not in tx:
                    errors.append(f"{fn}: face texture {fc['texture']} undefined")
                if any(not 0 <= u <= 16 for u in fc["uv"]):
                    errors.append(f"{fn}: uv {fc['uv']} outside 0..16")
    for fn in os.listdir(TEX_DIR):
        img = Image.open(os.path.join(TEX_DIR, fn))
        if img.size[0] != img.size[1] or img.size[0] not in (16, 32, 64):
            errors.append(f"{fn}: texture size {img.size} not square 16/32/64")
        if img.mode != "RGBA":
            errors.append(f"{fn}: not RGBA")
    lang = json.load(open(os.path.join(ASSETS, "lang/en_us.json")))
    for b in BLOCKS:
        if f"block.{MOD}.{b}" not in lang:
            errors.append(f"{b}: lang missing")
    tagged = set()
    for tool in ("pickaxe", "axe", "hoe", "shovel"):
        p = os.path.join(AC.DATA, "minecraft/tags/blocks/mineable", tool + ".json")
        if os.path.exists(p):
            tagged |= {v if isinstance(v, str) else v.get("id") for v in json.load(open(p))["values"]}
    for b in BLOCKS:
        if f"{MOD}:{b}" not in tagged:
            errors.append(f"{b}: not in a mineable tag")
    if errors:
        print("VERIFY FAILED:")
        for e in errors:
            print("  " + e)
        sys.exit(1)
    print(f"verify OK: {len(BLOCKS)} blocks, {len(os.listdir(MODEL_DIR))} models, {len(os.listdir(TEX_DIR))} textures")


def main():
    if "--verify" in sys.argv:
        verify()
        return
    for d in (TEX_DIR, MODEL_DIR):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    build_cone()
    build_wet_floor_sign()
    build_barricade()
    build_police_barricade()
    build_plastic_barrier()
    build_caution_tape()
    build_construction_wall()
    build_plywood_board()
    build_scaffold()
    build_sidewalk_shed()
    light_textures()
    build_shed_light()
    build_work_light()
    build_caged_work_lamp()
    write_shared()
    verify()


if __name__ == "__main__":
    main()
