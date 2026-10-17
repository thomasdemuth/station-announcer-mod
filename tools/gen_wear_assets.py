#!/usr/bin/env python3
"""Regenerates the WEAR VARIANTS family (ATMOSPHERE_PLAN.md §1 D, phase 5):
damaged copies of the station tile walls and platform floors.

    python3 tools/gen_wear_assets.py                  # write everything + verify()
    python3 tools/gen_wear_assets.py --verify         # only check what is on disk
    python3 tools/gen_wear_assets.py --preview DIR    # also write preview sheets into DIR

Never hand-edit the output. This file OWNS (and wipes on every run)
textures/block/wear/ and models/block/wear/, plus the blockstates, item models
and loot tables of the ids below. It never writes anything of the original
blocks: it IMPORTS gen_subway_wall and gen_floor_assets and calls their texture
functions, so the base of every damaged texture is bit-for-bit the same drawing
as the block it stands in for (same grid, same palette, same grime gradient).

THE BLOCKS (all plain full opaque cubes - WearBlocks.java)
----------------------------------------------------------
  subway_tile_wall_cracked            hairline cracks (some run on into the next
                                      tile), chipped corners showing the biscuit
  subway_tile_wall_missing            fallen tiles: combed mortar bed, shadowed top
                                      lip, broken grout, one half-broken tile
  subway_tile_wall_patched            mismatched replacements (cream / grey / glossy
                                      white / one set crooked) in crude cement grout
  subway_tile_wall_stained            dirty grout, brown drip trails from the top edge,
                                      a grimy lower third
  platform_tile_floor_cracked         cracked / chipped / one missing floor tile
  platform_concrete_floor_N_cracked   N = 1..4 (ids follow the originals'
                                      platform_concrete_floor_N[_clean]); cracks and
                                      saw-cut patches of darker, newer concrete

RULES THAT KEEP A DAMAGED BLOCK INVISIBLE AS A "DIFFERENT MATERIAL"
-------------------------------------------------------------------
* WALLS NEVER ROTATE. Drips must run down, and the wall's grime gradient runs
  top to bottom. Variety is FOUR separate textures per block, and each model
  permutes them over the four sides (north = t[k], east = t[k+1], ...) so a
  corner block never shows the same damage on two faces. Up/down faces use the
  original wall texture.
* Wall damage stays inside the block. A wall tile never straddles a block (the
  grout is column/row 0), so any tile can be damaged; a crack may run on into
  the NEXT tile only when that tile is in the same block - a crack cut by the
  block edge would stop dead against an undamaged neighbour.
* FLOOR TILES rotate freely like the originals (the grout is a centred pair, see
  gen_floor_assets.GROUT), but the outer ring of tiles straddles the block edge
  and its other half belongs to the neighbour - damage only ever lands on the
  5x5 INNER tiles, a set that maps onto itself under every rotation.
* CONCRETE keeps the originals' joint machinery: the same ConcreteFloorBlock
  class (joint_north / joint_west from the world grid) and the same joint
  drawing, drawn LAST so a crack or patch never shows over the groove. Cracks
  and patches stay a margin clear of the edges (a crack cut by the block edge
  would end against a crack-free neighbour), and the same variant seed is used
  for all four joint combinations - a joint only ADDS a groove.
* Stains are NOT a uniform wash: the dirty grout follows a noise field and the
  drips, so the block's outline never shows as a darker square in the wall.
"""

import argparse
import json
import math
import os
import random
import shutil
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import atmo_common as AC          # noqa: E402
import gen_floor_assets as GF     # noqa: E402
import gen_subway_wall as GW      # noqa: E402

MOD = "station_announcer"
ASSETS = AC.ASSETS
TEX_DIR = os.path.join(ASSETS, "textures/block/wear")
MODEL_DIR = os.path.join(ASSETS, "models/block/wear")
BS_DIR = os.path.join(ASSETS, "blockstates")
ITEM_DIR = os.path.join(ASSETS, "models/item")
CONCRETE_JAVA = os.path.join(AC.ROOT, "src/main/java/com/stationannouncer/block/ConcreteFloorBlock.java")
WEAR_JAVA = os.path.join(AC.ROOT, "src/main/java/com/stationannouncer/atmosphere/WearBlocks.java")

SLABS = (1, 2, 3, 4)
WALL_KINDS = ("cracked", "missing", "patched", "stained")
WALL_WEIGHTS = (3, 3, 2, 2)          # per side-permutation model
TILE_FLOOR_WEIGHTS = (4, 3, 3, 2)    # per texture, before rotation
CONCRETE_WEIGHTS = (4, 3, 2, 2)      # per variant, inside each joint combination

WALL_ORIGINAL = f"{MOD}:block/subway_tile_white"
TILE_FLOOR_SIDE = f"{MOD}:block/platform_tile_floor_side"
CONCRETE_SIDE = f"{MOD}:block/platform_concrete_floor_side"

LANG = {
    "subway_tile_wall_cracked": "Subway Tile Wall (Cracked)",
    "subway_tile_wall_missing": "Subway Tile Wall (Missing Tiles)",
    "subway_tile_wall_patched": "Subway Tile Wall (Patched)",
    "subway_tile_wall_stained": "Subway Tile Wall (Stained)",
    "platform_tile_floor_cracked": "Tiled Platform Floor (Cracked)",
}
for _n in SLABS:
    LANG[f"platform_concrete_floor_{_n}_cracked"] = f"Concrete Platform Floor ({_n}-Block Slabs, Cracked)"
BLOCK_IDS = list(LANG)


# =====================================================================
# RASTER HELPERS (numpy float RGBA, no wrapping: damage stays inside)
# =====================================================================

def to_np(rows):
    return np.array([[list(p[:3]) + [255] for p in r] for r in rows], dtype=np.float64)


def inside(img, x, y):
    return 0 <= x < img.shape[1] and 0 <= y < img.shape[0]


def blend(img, x, y, col, t):
    x, y = int(round(x)), int(round(y))
    if inside(img, x, y) and t > 0:
        img[y, x, :3] += (np.array(col[:3], dtype=np.float64) - img[y, x, :3]) * min(1.0, t)


def mulpx(img, x, y, f):
    x, y = int(round(x)), int(round(y))
    if inside(img, x, y):
        img[y, x, :3] *= f


def setpx(img, x, y, col):
    x, y = int(round(x)), int(round(y))
    if inside(img, x, y):
        img[y, x, :3] = col[:3]


def jit(col, rng, amt):
    d = rng.uniform(-amt, amt)
    return tuple(c + d for c in col[:3])


def smooth_noise(size, cells, rng):
    """Tileable value noise in [0, 1]; `cells` must divide `size`."""
    g = np.array([[rng.random() for _ in range(cells)] for _ in range(cells)])
    step = size // cells
    out = np.zeros((size, size))
    for y in range(size):
        y0, ty = (y // step) % cells, GF.smoothstep((y % step) / step)
        for x in range(size):
            x0, tx = (x // step) % cells, GF.smoothstep((x % step) / step)
            a = g[y0, x0] * (1 - tx) + g[y0, (x0 + 1) % cells] * tx
            b = g[(y0 + 1) % cells, x0] * (1 - tx) + g[(y0 + 1) % cells, (x0 + 1) % cells] * tx
            out[y, x] = a * (1 - ty) + b * ty
    return out


def save(img, name):
    os.makedirs(TEX_DIR, exist_ok=True)
    Image.fromarray(np.clip(np.round(img), 0, 255).astype(np.uint8)).save(
        os.path.join(TEX_DIR, name + ".png"))


def walk_line(rng, x0, y0, x1, y1, wander=0.6):
    """A jagged 1 px path from (x0,y0) to (x1,y1): integer points, 8-connected."""
    pts = []
    x, y = float(x0), float(y0)
    n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
    off = 0.0
    for i in range(n + 1):
        t = i / max(1, n)
        off += rng.uniform(-wander, wander)
        off = max(-1.4, min(1.4, off))
        dx, dy = x1 - x0, y1 - y0
        ln = math.hypot(dx, dy) or 1
        nx, ny = -dy / ln, dx / ln
        px = x0 + dx * t + nx * off
        py = y0 + dy * t + ny * off
        p = (int(round(px)), int(round(py)))
        if not pts or pts[-1] != p:
            pts.append(p)
    return pts


# =====================================================================
# TILE WALL (32 px, 4x4 tiles of 7 px, grout at x % 8 == 0)
# =====================================================================

WSIZE = GW.SIZE                  # 32
WT = GW.TILE                     # 8
WALL_GROUT = (198, 195, 187)
BISCUIT = (208, 199, 182)        # the ceramic body under the glaze
BISCUIT_EDGE = (160, 152, 138)
CRACK = (104, 100, 94)
MORTAR = (142, 138, 128)


def wall_base():
    return to_np(GW.TEXTURES["subway_tile_white"]())


def wtile(tx, ty):
    """Interior pixel box of wall tile (tx, ty), inclusive."""
    return tx * WT + 1, ty * WT + 1, tx * WT + WT - 1, ty * WT + WT - 1


def is_grout(x, y):
    return x % WT == 0 or y % WT == 0


def hairline(img, rng, pts, strength=0.72):
    """Draw a crack path: a thin grey line, faint at both ends, a touch darker
    where it crosses grout (the joint opened there)."""
    n = len(pts)
    for i, (x, y) in enumerate(pts):
        end = min(i, n - 1 - i)
        t = strength * (0.45 if end == 0 else 0.75 if end == 1 else 1.0)
        blend(img, x, y, jit(CRACK, rng, 5), t)
        if is_grout(x, y):
            mulpx(img, x, y, 0.9)


def straight(rng, x0, y0, x1, y1):
    """A nearly straight 8-connected path with one or two single-pixel kinks."""
    return walk_line(rng, x0, y0, x1, y1, wander=0.18)


def wall_crack(img, rng, tx, ty, run_on=0):
    """A hairline across tile (tx, ty), corner-ish to the far edge. With
    `run_on` it carries on through that many further tiles (same block only)."""
    x0, y0, x1, y1 = wtile(tx, ty)
    dirx = 1 if tx < 2 else -1
    diry = 1 if ty < 2 else -1
    sx = x0 if dirx > 0 else x1
    sy = rng.randint(y0, y1)
    length = 7 + 8 * run_on
    ex = sx + dirx * length
    ey = sy + diry * rng.randint(2, 4 + 4 * run_on)
    ex = max(1, min(WSIZE - 2, ex))
    ey = max(1, min(WSIZE - 2, ey))
    hairline(img, rng, straight(rng, sx, sy, ex, ey))


def wall_chip(img, rng, tx, ty, corner=None, size=None):
    """A corner knocked off a tile: pale biscuit where the glaze went, a
    darker broken-glaze edge along the break."""
    x0, y0, x1, y1 = wtile(tx, ty)
    corner = corner if corner is not None else rng.randrange(4)
    size = size or rng.choice((3, 3, 4))
    cx, sx = (x0, 1) if corner in (0, 2) else (x1, -1)
    cy, sy = (y0, 1) if corner in (0, 1) else (y1, -1)
    for i in range(size):
        for j in range(size - i):
            setpx(img, cx + sx * i, cy + sy * j, jit(BISCUIT, rng, 5))
    for i in range(size + 1):
        blend(img, cx + sx * i, cy + sy * (size - i), BISCUIT_EDGE, 0.55)
    # the grout at that corner usually goes with it
    blend(img, cx - sx, cy - sy, MORTAR, 0.6)


def mortar_px(rng, y):
    c = jit(MORTAR, rng, 6)
    if (y // 2) % 2 == 0:                         # combed thin-set ridges, 2 px
        c = tuple(v + 8 for v in c)
    return c


def shade_recess(img, cells):
    """A shallow recess lit from above: the tile edge above throws a shadow,
    the sides are a little darker, the bottom ledge catches light."""
    for (x, y) in cells:
        if (x, y - 1) not in cells:
            mulpx(img, x, y, 0.66)
        elif (x, y - 2) not in cells:
            mulpx(img, x, y, 0.84)
        if (x - 1, y) not in cells:
            mulpx(img, x, y, 0.88)
        if (x + 1, y) not in cells:
            mulpx(img, x, y, 0.94)
        if (x, y + 1) not in cells:
            mulpx(img, x, y, 1.07)


def wall_mortar_hole(img, rng, x0, y0, x1, y1, broken_sides=1):
    """A fallen tile: the mortar bed over a box (inclusive). The grout ring
    stays on most sides; on `broken_sides` of them it crumbled away too."""
    cells = {(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)}
    sides = ["top", "bottom", "left", "right"]
    rng.shuffle(sides)
    for side in sides[:broken_sides]:
        if side in ("top", "bottom"):
            yy = y0 - 1 if side == "top" else y1 + 1
            ring = [(x, yy) for x in range(x0, x1 + 1)]
        else:
            xx = x0 - 1 if side == "left" else x1 + 1
            ring = [(xx, y) for y in range(y0, y1 + 1)]
        # mostly gone, a stub or two of grout survives
        for p in ring:
            if rng.random() < 0.8 and 0 <= p[0] < WSIZE and 0 <= p[1] < WSIZE:
                cells.add(p)
    for (x, y) in cells:
        setpx(img, x, y, mortar_px(rng, y))
    shade_recess(img, cells)
    return cells


def wall_patch_tile(img, rng, tx, ty, colour, gloss=False, crooked=False):
    x0, y0, x1, y1 = wtile(tx, ty)
    cement = (170, 166, 156)
    # crude grout: the ring around the tile re-pointed in grey cement, unevenly
    for x in range(x0 - 1, x1 + 2):
        for y in range(y0 - 1, y1 + 2):
            if (x in (x0 - 1, x1 + 1) or y in (y0 - 1, y1 + 1)) and 0 <= x < WSIZE and 0 <= y < WSIZE:
                setpx(img, x, y, jit(cement, rng, 9))
    ox, oy = (rng.choice((-1, 1)), rng.choice((-1, 1))) if crooked else (0, 0)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            sx, sy = x - ox, y - oy               # a crooked tile, set a pixel off
            if not (y0 <= sy <= y1 and x0 <= sx <= x1):
                setpx(img, x, y, jit(cement, rng, 9))   # the fat joint it leaves
                continue
            c = jit(colour, rng, 1.5 if gloss else 4)
            if gloss:
                # a glossy tile: a soft vertical gradient and a bright diagonal glint
                c = tuple(v - 3 * (sy - y0) / 6.0 for v in c)
                if (sx - x0) + (sy - y0) in (2, 3) and sx - x0 <= 3:
                    c = tuple(min(255, v + 4) for v in (255, 255, 255))
            setpx(img, x, y, c)
    # grout smeared over the tile's own edge in places (wider, wobbly joints)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if (x in (x0, x1) or y in (y0, y1)) and rng.random() < 0.3:
                blend(img, x, y, jit(cement, rng, 6), 0.7)
    # and haze on the neighbours where it was wiped off badly
    for _ in range(rng.randint(2, 4)):
        side = rng.randrange(4)
        if side == 0:
            px, py = rng.randint(x0, x1 - 2), y0 - 2
        elif side == 1:
            px, py = rng.randint(x0, x1 - 2), y1 + 2
        elif side == 2:
            px, py = x0 - 2, rng.randint(y0, y1 - 2)
        else:
            px, py = x1 + 2, rng.randint(y0, y1 - 2)
        for k in range(rng.randint(2, 3)):
            qx, qy = (px + k, py) if side < 2 else (px, py + k)
            if 0 <= qx < WSIZE and 0 <= qy < WSIZE and not is_grout(qx, qy):
                blend(img, qx, qy, cement, 0.28)


def wall_cracked(v):
    rng = random.Random(f"wear/wall/cracked/{v}")
    img = wall_base()
    if v == 0:      # one long crack through three tiles, two chips
        wall_crack(img, rng, 0, 1, run_on=2)
        wall_chip(img, rng, 2, 3, 1)
        wall_chip(img, rng, 3, 0, 2)
    elif v == 1:    # two cracks, one running on, one chip
        wall_crack(img, rng, 3, 2, run_on=1)
        wall_crack(img, rng, 1, 0)
        wall_chip(img, rng, 0, 3, 0, 4)
    elif v == 2:    # mostly chips, a short crack
        wall_chip(img, rng, 1, 1, 3, 4)
        wall_chip(img, rng, 2, 1, 2, 3)
        wall_chip(img, rng, 3, 3, 0)
        wall_crack(img, rng, 0, 2)
    else:           # an impact star, a crack, a chip
        x0, y0, x1, y1 = wtile(2, 1)
        cx, cy = x0 + 3, y0 + 3
        for a in (0.5, 2.0, 3.4, 4.9):
            a += rng.uniform(-0.25, 0.25)
            r = rng.uniform(3.5, 5.5)
            pts = [(x, y) for x, y in straight(rng, cx, cy, cx + math.cos(a) * r, cy + math.sin(a) * r)]
            hairline(img, rng, pts, 0.8)
        setpx(img, cx, cy, BISCUIT_EDGE)
        setpx(img, cx + 1, cy, BISCUIT)
        wall_crack(img, rng, 0, 3, run_on=1)
        wall_chip(img, rng, 3, 2, 1)
    return img


def wall_missing(v):
    rng = random.Random(f"wear/wall/missing/{v}")
    img = wall_base()
    if v == 0:      # one tile gone, a chipped neighbour
        wall_mortar_hole(img, rng, *wtile(1, 1), broken_sides=1)
        wall_chip(img, rng, 2, 1, 0)
        wall_chip(img, rng, 1, 2, 1, 3)
    elif v == 1:    # two side by side (they share the grout line: one box)
        x0, y0, _, y1 = wtile(1, 2)
        _, _, x1, _ = wtile(2, 2)
        wall_mortar_hole(img, rng, x0, y0, x1, y1, broken_sides=2)
        wall_chip(img, rng, 1, 1, 2)
        wall_crack(img, rng, 3, 2)
    elif v == 2:    # one gone, the one under it half broken away
        wall_mortar_hole(img, rng, *wtile(2, 0), broken_sides=1)
        x0, y0, x1, y1 = wtile(2, 1)
        cut = [y0 + rng.randint(2, 3)]
        for _ in range(x1 - x0):
            cut.append(max(y0 + 1, min(y0 + 5, cut[-1] + rng.choice((-1, 0, 0, 1)))))
        cells = set(p for p in ((x, y) for x in range(x0, x1 + 1) for y in range(y0 - 1, y1 + 1))
                    if p[1] < cut[p[0] - x0])
        for (x, y) in cells:
            setpx(img, x, y, mortar_px(rng, y))
        shade_recess(img, cells | {(x, y0 - 2) for x in range(x0, x1 + 1)})
        for i, x in enumerate(range(x0, x1 + 1)):
            blend(img, x, cut[i], BISCUIT_EDGE, 0.6)     # broken glaze edge of the stub
        wall_chip(img, rng, 0, 3, 1)
    else:           # two scattered
        wall_mortar_hole(img, rng, *wtile(3, 0), broken_sides=1)
        wall_mortar_hole(img, rng, *wtile(0, 2), broken_sides=0)
        wall_chip(img, rng, 2, 3, 0)
    return img


def wall_patched(v):
    rng = random.Random(f"wear/wall/patched/{v}")
    img = wall_base()
    cream = (236, 229, 210)
    grey = (210, 210, 206)
    glossy = (252, 252, 251)
    putty = (226, 221, 207)
    plans = [
        [((1, 1), cream, False, False), ((2, 1), cream, False, False)],
        [((2, 2), grey, False, False), ((0, 3), glossy, True, False)],
        [((1, 2), glossy, True, False), ((3, 0), putty, False, True)],
        [((0, 0), grey, False, True), ((1, 0), cream, False, False), ((2, 3), glossy, True, False)],
    ][v]
    for (tx, ty), colour, gloss, crooked in plans:
        wall_patch_tile(img, rng, tx, ty, colour, gloss, crooked)
    return img


def drip(img, rng, dirt, x, top, length, width, col, rim, t0):
    """A water trail from (x, top) down: a seep at the source, a trail that
    fades and narrows, faint tide-line rims, a heavier drop where it stopped."""
    for dx in range(-2, 3):
        blend(img, x + dx, top, rim, t0 * 0.8 * (1 - abs(dx) / 3.0))
    cx = float(x)
    w = width
    for i in range(length):
        y = top + i
        if y >= WSIZE:
            break
        if rng.random() < 0.06:
            cx += rng.choice((-1, 1))
        if w > 1 and i > length * 0.55:
            w = 1
        f = t0 * (1.0 - 0.6 * i / length) * rng.uniform(0.85, 1.0)
        for k in range(w):
            blend(img, cx + k, y, col, f)
            if 0 <= int(cx + k) < WSIZE:
                dirt[y, int(cx + k)] += 1.0
        blend(img, cx - 1, y, rim, f * 0.22)
        blend(img, cx + w, y, rim, f * 0.22)
        if is_grout(int(cx), y):                    # it soaks along each grout course
            for d in (2, 3):
                blend(img, cx - d + 1, y, col, f * 0.35 / d)
                blend(img, cx + w + d - 2, y, col, f * 0.35 / d)
    end = top + length
    if end < WSIZE - 1:
        for dy in range(2):
            for k in range(w + 1):
                blend(img, cx + k - (1 if w == 1 else 0), end + dy, rim, t0 * (0.5 - 0.15 * dy))


def wall_stained(v):
    rng = random.Random(f"wear/wall/stained/{v}")
    img = wall_base()
    noise = smooth_noise(WSIZE, 4, rng) * 0.6 + smooth_noise(WSIZE, 8, rng) * 0.4
    dirt = np.zeros((WSIZE, WSIZE))       # where water ran / hands rubbed: the grout dirties there
    water = ((146, 124, 94), (124, 102, 74))
    rust = ((140, 92, 52), (116, 74, 40))
    lower = 0.0
    if v == 0:      # three drips of different lengths
        drip(img, rng, dirt, 6, 0, 25, 2, *water, 0.50)
        drip(img, rng, dirt, 15, 0, 13, 1, *water, 0.40)
        drip(img, rng, dirt, 26, 8, 16, 1, *water, 0.42)   # seeps from a grout course
    elif v == 1:    # the grimy lower third, no drips
        lower = 1.0
    elif v == 2:    # a drip and the lower third
        drip(img, rng, dirt, 21, 0, 20, 2, *water, 0.48)
        drip(img, rng, dirt, 9, 16, 10, 1, *water, 0.34)
        lower = 0.7
    else:           # a rust streak from a bracket above
        drip(img, rng, dirt, 13, 0, 31, 3, *rust, 0.55)
        drip(img, rng, dirt, 18, 0, 12, 1, *rust, 0.35)

    if lower:
        # rubbed by bags and shoulders: blotchy SMUDGES (not a band) building up
        # over the bottom third, easing off toward the bottom edge and the side
        # edges, so the block never ends in a hard dark line against the
        # course below or its neighbours
        for y in range(WSIZE):
            h = (y - 18) / 8.0
            if h <= 0:
                continue
            band = GF.smoothstep(min(1.0, h)) * (1.0 - 0.45 * max(0.0, (y - 27) / 4.0))
            for x in range(WSIZE):
                side = min(1.0, (min(x, WSIZE - 1 - x) + 1) / 5.0)
                smudge = min(1.0, max(0.0, (noise[y, x] - 0.25) * 2.2))
                g = lower * band * side * (0.03 + 0.16 * smudge)
                img[y, x, :3] *= (1.0 - g)
                img[y, x, 2] *= (1.0 - g * 0.4)     # warm, greasy grey
                dirt[y, x] += band * side * smudge * 0.5

    # Grout dirt: ONLY where water ran or hands rubbed (spread a little), plus
    # a faint noise-driven tint. A uniformly darker grid outlined the block
    # as a different material in the first pass.
    spread = dirt.copy()
    for _ in range(2):
        s = spread.copy()
        s[:, 1:] += spread[:, :-1] * 0.5
        s[:, :-1] += spread[:, 1:] * 0.5
        s[1:, :] += spread[:-1, :] * 0.3
        spread = s / 1.6
    for y in range(WSIZE):
        for x in range(WSIZE):
            if is_grout(x, y):
                d = 0.10 * noise[y, x] + 0.55 * min(1.0, spread[y, x])
                blend(img, x, y, (138, 126, 106), d)
    return img


WALL_BUILDERS = {"cracked": wall_cracked, "missing": wall_missing,
                 "patched": wall_patched, "stained": wall_stained}


# =====================================================================
# TILED FLOOR (96 px, 6 tiles of 14 px, grout centred pair - gen_floor_assets)
# =====================================================================

FS = GF.SIZE                      # 96
FP = GF.PITCH                     # 16
FTILE = GF.TILE_DIRTY
FLOOR_CRACK = (66, 63, 58)
FLOOR_MORTAR = (118, 112, 102)
FLOOR_CHIP = (132, 126, 117)     # the rough body where a corner came off


def ftile(k, j):
    """Interior box of INNER floor tile (k, j), k, j in 0..4 - inclusive."""
    x0 = FP * k + GF.GROUT[1] + 1
    y0 = FP * j + GF.GROUT[1] + 1
    return x0, y0, x0 + FP - 3, y0 + FP - 3


def floor_crack_path(img, rng, pts, strong=0.9, wide=True):
    """A crack ~2 px wide at 96 px: a dark core with a softer second pixel on
    the side away from the light, and a faint shadow beyond it."""
    n = len(pts)
    for i, (x, y) in enumerate(pts):
        end = min(i, n - 1 - i)
        t = strong * (0.45 if end == 0 else 0.75 if end == 1 else 1.0)
        blend(img, x, y, jit(FLOOR_CRACK, rng, 5), t)
        if wide and end > 1:
            if i + 1 < n:
                dx, dy = pts[i + 1][0] - x, pts[i + 1][1] - y
            else:
                dx, dy = x - pts[i - 1][0], y - pts[i - 1][1]
            ox, oy = (0, 1) if abs(dx) >= abs(dy) else (1, 0)
            blend(img, x + ox, y + oy, jit(FLOOR_CRACK, rng, 5), t * 0.5)
            mulpx(img, x + 2 * ox, y + 2 * oy, 0.94)
            mulpx(img, x - ox, y - oy, 0.96)


def floor_chip(img, rng, k, j, corner, size):
    """A corner broken off a floor tile: rough grey body, darker broken rim."""
    x0, y0, x1, y1 = ftile(k, j)
    cx, sx = (x0, 1) if corner in (0, 2) else (x1, -1)
    cy, sy = (y0, 1) if corner in (0, 1) else (y1, -1)
    edge = [size + rng.choice((-1, 0, 0, 1)) for _ in range(size + 2)]
    cells = set()
    for i in range(size + 2):
        for jj in range(size + 2):
            if i + jj <= edge[min(i, len(edge) - 1)]:
                cells.add((cx + sx * i, cy + sy * jj))
    # the grout at the corner goes with it
    cells |= {(cx - sx, cy - sy), (cx - sx, cy), (cx, cy - sy)}
    for (x, y) in cells:
        setpx(img, x, y, jit(FLOOR_CHIP, rng, 8))
    for (x, y) in cells:
        if any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            mulpx(img, x, y, 0.78)


def floor_hole(img, rng, x0, y0, x1, y1):
    """A tile gone: the mortar bed, with the grout ring partly crumbled and the
    recess shaded by the surrounding tile edges."""
    cells = {(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)}
    for x in range(x0 - 2, x1 + 3):
        for y in (y0 - 1, y0 - 2, y1 + 1, y1 + 2):
            if rng.random() < 0.4:
                cells.add((x, y))
    for y in range(y0, y1 + 1):
        for x in (x0 - 1, x0 - 2, x1 + 1, x1 + 2):
            if rng.random() < 0.4:
                cells.add((x, y))
    for (x, y) in cells:
        c = jit(FLOOR_MORTAR, rng, 9)
        if rng.random() < 0.14:
            c = jit((92, 88, 80), rng, 6)          # grit and dirt in the bed
        setpx(img, x, y, c)
    for (x, y) in cells:
        if any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            mulpx(img, x, y, 0.66)
        elif any((x + dx, y + dy) not in cells for dx, dy in ((2, 0), (-2, 0), (0, 2), (0, -2))):
            mulpx(img, x, y, 0.82)
    return cells


def floor_star(img, rng, k, j, arms=5):
    """A shattered tile: cracks from an impact point out to the tile edges."""
    x0, y0, x1, y1 = ftile(k, j)
    cx, cy = (x0 + x1) // 2 + rng.randint(-2, 2), (y0 + y1) // 2 + rng.randint(-2, 2)
    base = rng.uniform(0, 6.283)
    for a in range(arms):
        ang = base + a * 6.283 / arms + rng.uniform(-0.3, 0.3)
        r = rng.uniform(8, 11)
        pts = [(x, y) for x, y in walk_line(rng, cx, cy, cx + math.cos(ang) * r, cy + math.sin(ang) * r, 0.3)
               if x0 <= x <= x1 and y0 <= y <= y1]
        floor_crack_path(img, rng, pts, 0.85, wide=a % 2 == 0)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if abs(dx) + abs(dy) < 2:
                setpx(img, cx + dx, cy + dy, jit(FLOOR_CHIP, rng, 6))
    mulpx(img, cx, cy - 1, 0.8)


def floor_cracked(v):
    rng = random.Random(f"wear/floor/tile/{v}")
    img = to_np(GF.tile_top(FTILE, f"wear/floor/tile/base/{v}",
                            dict(stains=2, blots=1, scuffs=2, gum=0)))
    if v == 0:
        # a settlement crack running straight across three tiles, a chip
        y = ftile(0, 2)[1] + rng.randint(4, 9)
        pts = walk_line(rng, ftile(0, 2)[0] + 3, y, ftile(3, 2)[2] - 2, y + rng.randint(-6, 6), 0.35)
        floor_crack_path(img, rng, pts)
        floor_chip(img, rng, 4, 0, 3, 5)
        floor_chip(img, rng, 1, 4, 0, 4)
    elif v == 1:
        # one tile missing outright, its neighbours chipped and cracked
        floor_hole(img, rng, *ftile(2, 2))
        floor_chip(img, rng, 1, 2, 1, 5)
        floor_chip(img, rng, 3, 1, 2, 4)
        x0, y0, x1, y1 = ftile(2, 3)
        floor_crack_path(img, rng, walk_line(rng, x0 + 3, y0, x1 - 2, y1, 0.4))
    elif v == 2:
        # a shattered tile, and two chips elsewhere
        floor_star(img, rng, 1, 3)
        floor_chip(img, rng, 3, 0, 0, 5)
        floor_chip(img, rng, 4, 4, 2, 4)
        x0, y0, x1, y1 = ftile(3, 3)
        floor_crack_path(img, rng, walk_line(rng, x0, y0 + 4, x1, y0 + 9, 0.3))
    else:
        # a tile cracked into pieces with one piece gone, and a second crack
        x0, y0, x1, y1 = ftile(3, 2)
        mid_x = (x0 + x1) // 2 + rng.randint(-1, 1)
        cells = set()
        for y in range(y0, y1 + 1):
            edge = mid_x + int(round(2 * math.sin((y - y0) * 0.7)))
            for x in range(edge, x1 + 1):
                if y >= (y0 + y1) // 2 - 1 + (x - edge) // 3:
                    cells.add((x, y))
        for (x, y) in cells:
            setpx(img, x, y, jit(FLOOR_MORTAR, rng, 9))
        for (x, y) in cells:
            if any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                mulpx(img, x, y, 0.68)
        floor_crack_path(img, rng, walk_line(rng, x0, (y0 + y1) // 2 + 2, mid_x, (y0 + y1) // 2 - 1, 0.4))
        floor_crack_path(img, rng, walk_line(rng, mid_x, y0, mid_x + 1, (y0 + y1) // 2 - 1, 0.4))
        xa, ya, xb, yb = ftile(1, 1)
        xc, yc, xd, yd = ftile(1, 2)
        floor_crack_path(img, rng, walk_line(rng, xa + 2, ya, xd - 3, yd, 0.4))
        floor_chip(img, rng, 0, 4, 1, 5)
    return img


# =====================================================================
# CONCRETE (96 px, joints inside the NORTH / WEST edges - gen_floor_assets)
# =====================================================================

PATCH = (126, 125, 121)      # newer concrete: darker, finer, fewer pebbles
SAW = (84, 82, 78)
CRACK_C = (66, 64, 60)
MARGIN = 7                   # nothing within this many px of the block edge


def concrete_patch(img, rng, x0, y0, x1, y1):
    """A saw-cut repair: darker, finer-grained newer concrete with its own
    mottling and air pockets, a crisp dark cut line, and slurry haze where the
    finisher's float ran over the old slab."""
    lf = smooth_noise(FS, 12, rng) * 0.6 + smooth_noise(FS, 24, rng) * 0.4
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            c = jit(PATCH, rng, 7)
            if rng.random() < 0.08:
                c = jit(c, rng, 16)                 # sand flecks
            img[y, x, :3] = np.array(c) * (0.93 + 0.12 * lf[y, x])
    for _ in range(int((x1 - x0) * (y1 - y0) / 60)):   # a few air pockets
        mulpx(img, rng.randint(x0 + 1, x1 - 1), rng.randint(y0 + 1, y1 - 1), rng.uniform(0.7, 0.85))
    # trowel arcs: faint lighter swirls inside the patch
    for _ in range(rng.randint(2, 3)):
        cx, cy = rng.randint(x0, x1), rng.randint(y0, y1)
        r = rng.uniform(6, 12)
        a0 = rng.uniform(0, 6.283)
        for i in range(int(r * 2)):
            a = a0 + i / r
            px, py = cx + math.cos(a) * r, cy + math.sin(a) * r
            if x0 < px < x1 and y0 < py < y1:
                blend(img, px, py, (150, 149, 145), 0.25)
    for x in range(x0, x1 + 1):                     # the saw cut, and a hair of lit lip outside it
        blend(img, x, y0, SAW, 0.85)
        blend(img, x, y1, SAW, 0.85)
        blend(img, x, y0 - 1, (172, 170, 165), 0.22)
        mulpx(img, x, y1 + 1, 0.93)
    for y in range(y0, y1 + 1):
        blend(img, x0, y, SAW, 0.85)
        blend(img, x1, y, SAW, 0.85)
        blend(img, x0 - 1, y, (172, 170, 165), 0.18)
        mulpx(img, x1 + 1, y, 0.94)
    # slurry haze bleeding past the cut on one or two sides
    for _ in range(rng.randint(2, 4)):
        side = rng.randrange(4)
        L = rng.randint(6, 14)
        for i in range(L):
            for d in (2, 3):
                if side < 2:
                    xs = x0 + (i * (x1 - x0)) // L
                    blend(img, xs, (y0 - d) if side == 0 else (y1 + d), (122, 120, 116), 0.16 / (d - 1))
                else:
                    ys = y0 + (i * (y1 - y0)) // L
                    blend(img, (x0 - d) if side == 2 else (x1 + d), ys, (122, 120, 116), 0.16 / (d - 1))


def concrete_crack(img, rng, x, y, ang, length, width=2, branch=True):
    """A slab crack: the heading wanders but is pulled back toward its original
    direction (real cracks run roughly straight between restraints), a dark
    core ~2 px wide that tapers at both ends, spalled shoulders."""
    lo, hi = MARGIN, FS - 1 - MARGIN
    heading = ang
    pts = []
    for i in range(length):
        ang += rng.uniform(-0.38, 0.38) + (heading - ang) * 0.18
        nx, ny = x + math.cos(ang), y + math.sin(ang)
        if not (lo <= nx <= hi and lo <= ny <= hi):
            break
        x, y = nx, ny
        pts.append((x, y, ang))
    n = len(pts)
    for i, (px, py, a) in enumerate(pts):
        taper = min(1.0, (n - i) / 12.0, (i + 2) / 5.0)
        ox, oy = -math.sin(a), math.cos(a)
        blend(img, px, py, jit(CRACK_C, rng, 6), 0.9 * taper)
        if width >= 2 and taper > 0.6:
            blend(img, px + ox * 0.9, py + oy * 0.9, jit(CRACK_C, rng, 6), 0.6 * taper)
        mulpx(img, px - ox * 1.3, py - oy * 1.3, 1.0 - 0.10 * taper)
        mulpx(img, px + ox * 2.1, py + oy * 2.1, 1.0 - 0.07 * taper)
        if rng.random() < 0.06 * taper:
            blend(img, px - ox * 1.7, py - oy * 1.7, (182, 180, 174), 0.5)
    if branch and n > 20:
        for _ in range(rng.randint(1, 2)):
            px, py, a = pts[rng.randint(n // 4, 3 * n // 4)]
            concrete_crack(img, rng, px, py, a + rng.choice((-1, 1)) * rng.uniform(0.6, 1.1),
                           rng.randint(10, 20), width=1, branch=False)
    return pts


def concrete_joints(img, rng, north, west):
    """Exactly gen_floor_assets.concrete_top's joint drawing (palette and widths)."""
    dark, lip = GF.CONCRETE_DIRTY["joint"]
    n_dark = n_lip = int(round(2 * GF.S / 2))
    if north:
        for x in range(FS):
            for i in range(n_dark):
                blend(img, x, i, GF.shade(dark, rng.uniform(-5, 5)), 0.92)
            for i in range(n_lip):
                blend(img, x, n_dark + i, GF.shade(lip, rng.uniform(-5, 5)), 0.80)
    if west:
        for y in range(FS):
            for i in range(n_dark):
                blend(img, i, y, GF.shade(dark, rng.uniform(-5, 5)), 0.92)
            for i in range(n_lip):
                blend(img, n_dark + i, y, GF.shade(lip, rng.uniform(-5, 5)), 0.80)


def concrete_cracked(v, north, west):
    seed = f"wear/floor/concrete/{v}"
    base_profile = GF.CONCRETE_PROFILES[[0, 1, 0, 2][v]]
    img = to_np(GF.concrete_top(GF.CONCRETE_DIRTY, seed, base_profile, False, False))
    rng = random.Random(seed + "/damage")
    if v == 0:
        # one long wandering crack with a branch or two
        concrete_crack(img, rng, rng.randint(10, 16), rng.randint(26, 70), rng.uniform(-0.35, 0.35), 90)
    elif v == 1:
        # a saw-cut patch, and a crack running off its corner (they always start there)
        x0, y0 = rng.randint(16, 30), rng.randint(14, 26)
        x1, y1 = x0 + rng.randint(26, 36), y0 + rng.randint(20, 30)
        concrete_patch(img, rng, x0, y0, x1, y1)
        concrete_crack(img, rng, x1 + 1, y1 + 1, rng.uniform(0.5, 1.0), 40)
    elif v == 2:
        # two cracks meeting: a shrinkage Y
        cx, cy = rng.randint(40, 56), rng.randint(40, 56)
        for a in (rng.uniform(-2.9, -2.3), rng.uniform(-0.9, -0.3), rng.uniform(1.2, 1.9)):
            concrete_crack(img, rng, cx, cy, a, rng.randint(26, 40), branch=False)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            blend(img, cx + dx, cy + dy, CRACK_C, 0.6)
    else:
        # a big old patch and a small newer one, a short crack between them
        concrete_patch(img, rng, 12, 52, 44, 82)
        concrete_patch(img, rng, 58, 16, 74, 30)
        concrete_crack(img, rng, 45, 50, -0.75, 28, branch=False)
    concrete_joints(img, random.Random(seed + "/joints"), north, west)
    return img


# =====================================================================
# MODELS / BLOCKSTATES / DATA
# =====================================================================

_models = {}


def wtex(name):
    return f"{MOD}:block/wear/{name}"


def side_cube(sides, up, down, particle):
    """A full cube with its own texture per side; every face culled."""
    tex = {"n": sides[0], "e": sides[1], "s": sides[2], "w": sides[3],
           "up": up, "down": down, "particle": particle}
    faces = {
        "north": {"texture": "#n", "cullface": "north"},
        "east": {"texture": "#e", "cullface": "east"},
        "south": {"texture": "#s", "cullface": "south"},
        "west": {"texture": "#w", "cullface": "west"},
        "up": {"texture": "#up", "cullface": "up"},
        "down": {"texture": "#down", "cullface": "down"},
    }
    for f in faces.values():
        f["uv"] = [0, 0, 16, 16]
    return {"parent": "minecraft:block/block", "textures": tex,
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}]}


def write_all(preview_dir=None):
    for d in (TEX_DIR, MODEL_DIR):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    textures = {}

    # ---- walls: 4 textures per kind, 4 side-permutation models
    for kind in WALL_KINDS:
        block = f"subway_tile_wall_{kind}"
        names = []
        for v in range(4):
            name = f"wall_{kind}_{v}"
            textures[name] = WALL_BUILDERS[kind](v)
            names.append(name)
        entries = []
        for k in range(4):
            sides = [wtex(names[(k + i) % 4]) for i in range(4)]
            m = f"wall_{kind}_m{k}"
            _models[m] = side_cube(sides, WALL_ORIGINAL, WALL_ORIGINAL, sides[0])
            entries.append({"model": f"{MOD}:block/wear/{m}", "weight": WALL_WEIGHTS[k]})
        AC.write_json(os.path.join(BS_DIR, block + ".json"), {"variants": {"": entries}})
        AC.item_block(block, f"wear/wall_{kind}_m0")

    # ---- tile floor: 4 textures x 4 rotations, like the original
    entries = []
    for v in range(4):
        name = f"tile_floor_cracked_{v}"
        textures[name] = floor_cracked(v)
        m = f"tile_floor_cracked_m{v}"
        t = wtex(name)
        _models[m] = side_cube([TILE_FLOOR_SIDE] * 4, t, TILE_FLOOR_SIDE, TILE_FLOOR_SIDE)
        for y in (0, 90, 180, 270):
            e = {"model": f"{MOD}:block/wear/{m}", "weight": TILE_FLOOR_WEIGHTS[v]}
            if y:
                e["y"] = y
            entries.append(e)
    AC.write_json(os.path.join(BS_DIR, "platform_tile_floor_cracked.json"), {"variants": {"": entries}})
    AC.item_block("platform_tile_floor_cracked", "wear/tile_floor_cracked_m1")

    # ---- concrete: the originals' joint_north/joint_west combos, 4 variants each, no rotation
    variants = {}
    for combo in range(4):
        north, west = bool(combo & 1), bool(combo & 2)
        entries = []
        for v in range(4):
            name = f"concrete_cracked_c{combo}_{v}"
            textures[name] = concrete_cracked(v, north, west)
            m = f"concrete_cracked_c{combo}_m{v}"
            _models[m] = side_cube([CONCRETE_SIDE] * 4, wtex(name), CONCRETE_SIDE, CONCRETE_SIDE)
            entries.append({"model": f"{MOD}:block/wear/{m}", "weight": CONCRETE_WEIGHTS[v]})
        variants[f"joint_north={str(north).lower()},joint_west={str(west).lower()}"] = entries
    for n in SLABS:
        block = f"platform_concrete_floor_{n}_cracked"
        AC.write_json(os.path.join(BS_DIR, block + ".json"), {"variants": variants})
        AC.item_block(block, "wear/concrete_cracked_c3_m1")

    for name, img in textures.items():
        save(img, name)
    for name, m in _models.items():
        AC.write_json(os.path.join(MODEL_DIR, name + ".json"), m)

    for block in BLOCK_IDS:
        AC.loot_self(block)
    AC.upsert_lang({f"block.{MOD}.{b}": n for b, n in LANG.items()})
    AC.insert_into_tag("minecraft/tags/blocks/mineable/pickaxe.json", [f"{MOD}:{b}" for b in BLOCK_IDS])
    print(f"wear: {len(textures)} textures, {len(_models)} models, {len(BLOCK_IDS)} blocks")
    if preview_dir:
        previews(textures, preview_dir)


# =====================================================================
# VERIFY
# =====================================================================

def java_props():
    """Blockstate properties per block id, read from the Java sources so a
    mismatch with the blockstate files is caught here rather than in game."""
    with open(CONCRETE_JAVA) as fh:
        cj = fh.read()
    with open(WEAR_JAVA) as fh:
        wj = fh.read()
    concrete = {}
    for p in ("joint_north", "joint_west"):
        if f'BooleanProperty.of("{p}")' in cj:
            concrete[p] = {"true", "false"}
    props = {}
    for b in BLOCK_IDS:
        if b.startswith("platform_concrete_floor_"):
            assert "new ConcreteFloorBlock(" in wj, "WearBlocks no longer uses ConcreteFloorBlock"
            props[b] = concrete
        else:
            props[b] = {}
    return props


def verify():
    errors = []
    props = java_props()
    with open(os.path.join(ASSETS, "lang/en_us.json")) as fh:
        lang = json.load(fh)
    with open(os.path.join(AC.DATA, "minecraft/tags/blocks/mineable/pickaxe.json")) as fh:
        tag = json.load(fh)["values"]

    def check_model(ref, where):
        path = os.path.join(ASSETS, "models", ref.split(":")[1] + ".json")
        if not os.path.exists(path):
            errors.append(f"{where}: missing model {ref}")
            return
        with open(path) as fh:
            m = json.load(fh)
        for t in m.get("textures", {}).values():
            if t.startswith("#"):
                continue
            if not os.path.exists(os.path.join(ASSETS, "textures", t.split(":")[1] + ".png")):
                errors.append(f"{ref}: missing texture {t}")
        for el in m.get("elements", []):
            if any(not 0 <= c <= 16 for c in el["from"] + el["to"]):
                errors.append(f"{ref}: element outside 0..16")
            for fname, f in el["faces"].items():
                if any(not 0 <= c <= 16 for c in f.get("uv", [0, 0, 16, 16])):
                    errors.append(f"{ref}: {fname} uv {f['uv']}")
                if f["texture"][1:] not in m["textures"]:
                    errors.append(f"{ref}: {fname} texture {f['texture']} undefined")

    for b in BLOCK_IDS:
        try:
            with open(os.path.join(BS_DIR, b + ".json")) as fh:
                bs = json.load(fh)
        except Exception as ex:   # noqa: BLE001
            errors.append(f"{b}: blockstate unreadable: {ex}")
            continue
        want = props[b]
        seen = set()
        for key, apply in bs["variants"].items():
            kv = dict(p.split("=") for p in key.split(",") if p)
            for k, val in kv.items():
                if k not in want:
                    errors.append(f"{b}: variant key {k} is not a property of the Java block")
                elif val not in want[k]:
                    errors.append(f"{b}: {k}={val} is not a legal value")
            if set(kv) != set(want):
                errors.append(f"{b}: variant '{key}' does not name every property {sorted(want)}")
            seen.add(tuple(sorted(kv.items())))
            for e in (apply if isinstance(apply, list) else [apply]):
                if e.get("y", 0) not in (0, 90, 180, 270) or "x" in e:
                    errors.append(f"{b}: bad rotation {e}")
                if b.startswith("subway_tile_wall") and e.get("y"):
                    errors.append(f"{b}: walls must never rotate (drips run down)")
                if b.startswith("platform_concrete") and e.get("y"):
                    errors.append(f"{b}: concrete must not rotate (joints are world-aligned)")
                check_model(e["model"], b)
        n_states = 1
        for vals in want.values():
            n_states *= len(vals)
        if len(seen) != n_states:
            errors.append(f"{b}: {len(seen)} variant keys for {n_states} states")
        item = os.path.join(ITEM_DIR, b + ".json")
        if not os.path.exists(item):
            errors.append(f"{b}: missing item model")
        else:
            with open(item) as fh:
                check_model(json.load(fh)["parent"], b + " item")
        if not os.path.exists(os.path.join(AC.DATA, MOD, "loot_tables/blocks", b + ".json")):
            errors.append(f"{b}: missing loot table")
        if f"block.{MOD}.{b}" not in lang:
            errors.append(f"{b}: missing lang")
        if f"{MOD}:{b}" not in tag:
            errors.append(f"{b}: missing pickaxe tag")
    for fn in os.listdir(TEX_DIR):
        img = Image.open(os.path.join(TEX_DIR, fn))
        w, h = img.size
        if w != h or w not in (16, 32, 64, 96) or img.mode != "RGBA":
            errors.append(f"{fn}: {img.size} {img.mode}")
        if w % 16:
            errors.append(f"{fn}: not a multiple of 16 (no mipmaps)")
    # the damaged textures must share their original's size (same grid)
    for fn in os.listdir(TEX_DIR):
        size = Image.open(os.path.join(TEX_DIR, fn)).size[0]
        want_size = GW.SIZE if fn.startswith("wall_") else GF.SIZE
        if size != want_size:
            errors.append(f"{fn}: {size} px, original is {want_size}")
    if errors:
        print("\n".join(errors))
        raise SystemExit(f"verify FAILED ({len(errors)})")
    print("verify OK")


# =====================================================================
# PREVIEWS - flat sheets (exact pixels, seams visible) + one 3D render
# =====================================================================

def _load(rel):
    return np.asarray(Image.open(os.path.join(ASSETS, "textures/block", rel + ".png")).convert("RGBA"),
                      dtype=np.float64)


def _hash(x, z, salt=0):
    """splitmix64 of the position - stands in for vanilla's per-position pick."""
    h = (x * 0x9E3779B97F4A7C15 + z * 0xC2B2AE3D27D4EB4F + salt * 0x165667B19E3779F9) & 0xFFFFFFFFFFFFFFFF
    h = ((h ^ (h >> 30)) * 0xBF58476D1CE4E5B9) & 0xFFFFFFFFFFFFFFFF
    h = ((h ^ (h >> 27)) * 0x94D049BB133111EB) & 0xFFFFFFFFFFFFFFFF
    return (h ^ (h >> 31)) & 0x7FFFFFFF


def _paste(sheet, tex, bx, by, scale):
    n = tex.shape[0]
    big = np.kron(tex, np.ones((scale, scale, 1)))
    sheet[by * n * scale:(by + 1) * n * scale, bx * n * scale:(bx + 1) * n * scale] = big


def _save_sheet(sheet, path):
    Image.fromarray(np.clip(sheet, 0, 255).astype(np.uint8)).save(path)
    print("preview:", path)


def previews(textures, folder):
    os.makedirs(folder, exist_ok=True)
    original = _load("subway_tile_white")

    # 6x4 wall: originals with each damaged kind swapped in, plus one sheet per kind
    layout = ["..C..S", ".M..P.", "S..C.M", ".P.S.."]
    kinds = {"C": "cracked", "M": "missing", "P": "patched", "S": "stained"}
    sheet = np.zeros((4 * 32 * 4, 6 * 32 * 4, 4))
    for by, row in enumerate(layout):
        for bx, ch in enumerate(row):
            tex = original if ch == "." else textures[f"wall_{kinds[ch]}_{_hash(bx, by) % 4}"]
            _paste(sheet, tex, bx, by, 4)
    _save_sheet(sheet, os.path.join(folder, "wall_mixed_6x4.png"))
    for kind in WALL_KINDS:
        sheet = np.zeros((4 * 32 * 4, 6 * 32 * 4, 4))
        for by in range(4):
            for bx in range(6):
                on = (bx + by) % 2 == 0
                tex = textures[f"wall_{kind}_{(bx + by * 3) % 4}"] if on else original
                _paste(sheet, tex, bx, by, 4)
        _save_sheet(sheet, os.path.join(folder, f"wall_{kind}_checker.png"))

    # tile floor: 8x8 of the original's weighted variants with cracked ones mixed in
    tile_orig = [_load(f"platform_tile_floor_top_{i}") for i in range(GF.TILE_VARIANTS)]
    sheet = np.zeros((8 * FS, 8 * FS, 4))
    for z in range(8):
        for x in range(8):
            h = _hash(x, z)
            rot = (h >> 3) % 4
            if h % 5 == 0:
                tex = textures[f"tile_floor_cracked_{(h >> 5) % 4}"]
            else:
                tex = tile_orig[(h >> 5) % GF.TILE_VARIANTS]
            _paste(sheet, np.rot90(tex, rot), x, z, 1)
    _save_sheet(sheet, os.path.join(folder, "floor_tile_mixed_8x8.png"))

    # concrete, 3-block slabs: originals with cracked ones mixed in, joints on the grid
    for slab in (3, 2):
        sheet = np.zeros((9 * FS, 9 * FS, 4))
        for z in range(9):
            for x in range(9):
                combo = (1 if z % slab == 0 else 0) | (2 if x % slab == 0 else 0)
                h = _hash(x, z, 7)
                if h % 4 == 0:
                    tex = textures[f"concrete_cracked_c{combo}_{(h >> 4) % 4}"]
                else:
                    tex = _load(f"platform_concrete_floor_top_c{combo}_{GF.CONCRETE_DIRTY_TABLE[(h >> 4) % 8]}")
                _paste(sheet, tex, x, z, 1)
        _save_sheet(sheet, os.path.join(folder, f"floor_concrete{slab}_mixed_9x9.png"))

    # one textured 3D scene: wall course on a mixed floor
    try:
        import render_scene as R
    except ImportError:
        return
    def floor_cube(top, side):
        return side_cube([f"{MOD}:block/{side}"] * 4, f"{MOD}:block/{top}",
                         f"{MOD}:block/{side}", f"{MOD}:block/{side}")

    def floor_block(x, z, salt):
        h = _hash(x, z, salt)
        if z <= 2:
            if h % 4 == 0:
                return (f"wear/tile_floor_cracked_m{(h >> 4) % 4}", (x, 0, z), (h >> 2) % 4)
            return (floor_cube(f"platform_tile_floor_top_{(h >> 4) % GF.TILE_VARIANTS}",
                               "platform_tile_floor_side"), (x, 0, z), (h >> 2) % 4)
        combo = (1 if z % 3 == 0 else 0) | (2 if x % 3 == 0 else 0)
        if h % 3 == 0:
            return (f"wear/concrete_cracked_c{combo}_m{(h >> 4) % 4}", (x, 0, z), 0)
        return (floor_cube(f"platform_concrete_floor_top_c{combo}_{GF.CONCRETE_DIRTY_TABLE[(h >> 4) % 8]}",
                           "platform_concrete_floor_side"), (x, 0, z), 0)

    # wall course standing at the back (z = 6) of a tile + concrete floor; the
    # camera looks at the wall's north face over the floor
    scene = []
    for by, row in enumerate(layout):
        for bx, ch in enumerate(row):
            model = "subway_tile_wall" if ch == "." else f"wear/wall_{kinds[ch]}_m{_hash(bx, by) % 4}"
            scene.append((model, (bx, 4 - by, 6), 0))
    for z in range(6):
        for x in range(6):
            scene.append(floor_block(x, z, 3))
    R.render(scene, os.path.join(folder, "scene_3d.png"), yaw=200, pitch=24, size=1100)
    print("preview:", os.path.join(folder, "scene_3d.png"))
    floor = [floor_block(x, z, 5) for z in range(9) for x in range(9)]
    R.render(floor, os.path.join(folder, "scene_floor_3d.png"), yaw=200, pitch=55, size=1100)
    print("preview:", os.path.join(folder, "scene_floor_3d.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true", help="only check what is on disk")
    ap.add_argument("--preview", metavar="DIR", help="also write preview sheets into DIR")
    args = ap.parse_args()
    if not args.verify:
        write_all(args.preview)
    verify()


if __name__ == "__main__":
    main()
