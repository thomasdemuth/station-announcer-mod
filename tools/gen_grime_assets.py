#!/usr/bin/env python3
"""Regenerates the GRIME DECAL family (ATMOSPHERE_PLAN.md §2.2) - textures,
block models, multipart blockstates, item icons/models, loot tables, lang
names and mineable tags.

    python3 tools/gen_grime_assets.py            # write everything + verify()
    python3 tools/gen_grime_assets.py --verify   # only check what is on disk

Never hand-edit the output; change this file and re-run it. It owns (and wipes
on every run) textures/block/grime/ and models/block/grime/.

THE BLOCKS
----------
Nine vanilla MULTIFACE blocks (GrimeDecalBlock extends MultifaceGrowthBlock,
the glow lichen / sculk vein machinery). Their six boolean properties
north/east/south/west/up/down say which SIDE OF THE CELL carries a decal:
`down` is the floor (top of the block below), `up` the ceiling, `north` the
wall to the north. A kind that does not allow a face has no property for it
(the superclass only adds allowed ones) - so the blockstate must never name it
(verify() checks; one unknown when-key = whole file rejected = purple boxes).

    grime_dust, grime_soot, grime_water_stain, cobweb_corner   all six faces
    grime_rust_streak, grime_efflorescence                     walls + up
    grime_puddle, grime_gum, grime_oil                         down only

GEOMETRY
--------
One zero-thickness quad per face, 0.1 px off the surface (vanilla glow lichen's
offset, proven against z-fighting under Sodium/Iris; less starts to shimmer at
range). Only the face pointing INTO the cell is emitted - the back of a decal is
always against its wall. No cullface (the quad is inside the air cell, so a
cullface would cull it exactly when the wall is solid). Ambient occlusion stays
ON, unlike lichen: the quad sits a hair above the surface, so vanilla/Sodium
sample the same light/AO corners the surface itself gets - a dust decal in a
dark corner is darkened like the floor under it (with AO off it glowed).

  floor  : plane y=0.1,  face "up",    uv [0,0,16,16]  -> texture row 0 = NORTH, col 0 = WEST
  ceiling: plane y=15.9, face "down",  uv [0,16,16,0]  -> the same mapping seen from below
  wall   : plane z=0.1,  face "south", uv [0,0,16,16]  -> row 0 = TOP, col 0 = viewer's LEFT
  The other three walls are the north model turned by the blockstate's y
  (90 = east, 180 = south, 270 = west); a y turn never tips "down", so streaks
  and drips run DOWN on every wall. Floors/ceilings also get y turns for variety.
  "_m" models mirror u.
  cobweb chamfer: a sheet across the wall/ceiling edge (45 deg about x).

RANDOM VARIETY WITHOUT CODE
---------------------------
Each face's multipart `apply` is a weighted LIST (texture variant x mirror x
y turn); vanilla and Sodium pick per position. Multipart reseeds the SAME
random for every selector of a block (Random.create(l) per part), so lists of
equal total weight pick the same index: the six faces' lists get different
totals (+d on the first entry) so a corner's two walls don't come out twins.

CONTEXT FROM THE CELL ITSELF
----------------------------
A blockstate cannot see neighbours, but it can see the cell's OTHER faces.
So for dust, the floor decal's build-up hugs whichever walls of the same cell
also carry dust (edge / corner / corridor / U / ring textures turned to fit),
and a dusty wall above a dusty floor gets a base band instead of smudges. Soot
on a wall under a sooty ceiling gets a soot line along the top. Cobwebs anchor
toward the neighbouring wall / into the corner. The Weathering Tool therefore
gets corner build-up just by switching on the floor AND wall faces of a cell.

TEXTURE RULES (from gen_floor_assets.py, plus decal ones)
--------------------------------------------------------
* Soft alpha, never a hard pixel blob, never a 1-px dither.
* Noise: the lowest octave quiet, a fine octave present (hides the value-noise
  lattice).
* Organic outlines = radius modulated by angular harmonics + squash + tilt.
* Alpha fades to 0 before the texture border - EXCEPT the deliberate band
  textures (dust against a wall, soot under a ceiling): along their band they
  reach the left/right borders with a FIXED profile so a row of them is one
  continuous band, and the band's own edge sits on the wall it belongs to.
  Drip/rust streaks may start at the top border (they come from above).
* Transparent pixels keep the decal's colour (no black halos in the mips).
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import atmo_common as AC  # noqa: E402

MOD = "station_announcer"
ASSETS = AC.ASSETS
TEX_DIR = os.path.join(ASSETS, "textures/block/grime")
MODEL_DIR = os.path.join(ASSETS, "models/block/grime")
BS_DIR = os.path.join(ASSETS, "blockstates")
ITEM_TEX_DIR = os.path.join(ASSETS, "textures/item")

HORIZ = ["north", "east", "south", "west"]
ALL6 = HORIZ + ["up", "down"]
OFF = 0.1                       # px off the surface

# id -> (allowed faces, render, mineable tool, lang name)
KINDS = {
    "grime_dust": (ALL6, "translucent", "shovel", "Dust Build-up"),
    "grime_soot": (ALL6, "translucent", "shovel", "Soot and Brake Dust"),
    "grime_water_stain": (ALL6, "translucent", "pickaxe", "Water Stain"),
    "grime_rust_streak": (HORIZ + ["up"], "translucent", "pickaxe", "Rust Streak"),
    "grime_efflorescence": (HORIZ + ["up"], "translucent", "pickaxe", "Efflorescence"),
    "grime_puddle": (["down"], "translucent", "shovel", "Puddle"),
    "grime_gum": (["down"], "translucent", "shovel", "Blackened Gum"),
    "grime_oil": (["down"], "translucent", "shovel", "Oil Stain"),
    "cobweb_corner": (ALL6, "cutout", "hoe", "Corner Cobweb"),
}


# =====================================================================
# NOISE / SHAPE HELPERS (numpy, arrays indexed [row y, col x])
# =====================================================================

def smooth(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def grid(n):
    c = np.arange(n) + 0.5
    return np.meshgrid(c, c)          # X, Y


def vnoise(n, cx, cy, rng):
    """Non-tiling value noise in [-1, 1] with cx x cy cells."""
    g = np.random.default_rng(rng.randrange(1 << 32)).uniform(-1, 1, size=(cy + 1, cx + 1))
    X, Y = grid(n)
    fx, fy = X / n * cx, Y / n * cy
    x0, y0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
    x0, y0 = np.clip(x0, 0, cx - 1), np.clip(y0, 0, cy - 1)
    tx, ty = smooth(fx - x0), smooth(fy - y0)
    a = g[y0, x0] * (1 - tx) + g[y0, x0 + 1] * tx
    b = g[y0 + 1, x0] * (1 - tx) + g[y0 + 1, x0 + 1] * tx
    return a * (1 - ty) + b * ty


def fbm(n, rng, octs=((2, 2, 0.25), (4, 4, 0.8), (8, 8, 0.7), (16, 16, 0.45))):
    """Fractal noise ~[-1, 1]. Quiet lowest octave, fine top octave."""
    tot = np.zeros((n, n))
    for cx, cy, a in octs:
        tot += vnoise(n, cx, cy, rng) * a
    return tot / sum(a for _, _, a in octs) * 1.6


def window(n, margin):
    X, Y = grid(n)
    return smooth(np.minimum(X, n - X) / margin) * smooth(np.minimum(Y, n - Y) / margin)


def blob(n, rng, cx, cy, r, soft=2.0, squash=(0.72, 1.0), harm=1.0, rag=0.0, tilt=None):
    """(mask 0..1, d/edge) for one lumpy organic outline."""
    X, Y = grid(n)
    t = rng.uniform(0, math.tau) if tilt is None else tilt
    sq = rng.uniform(*squash)
    dx, dy = X - cx, Y - cy
    u = dx * math.cos(t) + dy * math.sin(t)
    v = (-dx * math.sin(t) + dy * math.cos(t)) / sq
    d = np.hypot(u, v) + 1e-6
    ang = np.arctan2(v, u)
    hs = [(rng.uniform(0.10, 0.22) * harm, rng.randint(2, 3), rng.uniform(0, math.tau)),
          (rng.uniform(0.05, 0.13) * harm, rng.randint(4, 5), rng.uniform(0, math.tau)),
          (rng.uniform(0.02, 0.07) * harm, rng.randint(6, 9), rng.uniform(0, math.tau))]
    edge = r * (1.0 + sum(a * np.sin(k * ang + p) for a, k, p in hs))
    if rag:
        edge = edge + rag * fbm(n, rng, ((4, 4, 0.5), (8, 8, 0.8), (16, 16, 0.6)))
    m = smooth((edge - d) / soft + 0.5)
    return m, d / np.maximum(edge, 0.5)


def ring_profile(ratio, at, width):
    """Soft line at ratio == at."""
    return np.exp(-((ratio - at) / width) ** 2)


def over(base_c, base_a, c, a):
    """Composite layer (c, a) over (base_c, base_a). c: (n,n,3) or rgb tuple."""
    c = np.broadcast_to(np.asarray(c, dtype=float), base_c.shape)
    a3 = a[..., None]
    out_a = a + base_a * (1 - a)
    num = c * a3 + base_c * (base_a * (1 - a))[..., None]
    out_c = np.where(out_a[..., None] > 1e-6, num / np.maximum(out_a, 1e-6)[..., None], c)
    return out_c, out_a


def lerp_col(c0, c1, t):
    c0, c1 = np.asarray(c0, float), np.asarray(c1, float)
    return c0 + (c1 - c0) * np.clip(t, 0, 1)[..., None]


def finish(col, alpha):
    """RGBA uint8; transparent pixels keep their colour (mip bleed)."""
    a = np.clip(alpha, 0, 1)
    rgba = np.zeros(col.shape[:2] + (4,), dtype=np.uint8)
    rgba[..., :3] = np.clip(np.round(col), 0, 255)
    rgba[..., 3] = np.round(a * 255)
    return rgba


def band_profile(n, rng, reach, border_reach, strength=1.0, lumps=True):
    """Dense along row 0, decaying into the cell. Along x the profile is FIXED
    at both borders (exp(-y / border_reach), the same for every texture of a
    family, so a row of them is one continuous band) and wanders toward
    `reach` in the middle. Returns density 0..1."""
    X, Y = grid(n)
    lat = smooth(np.minimum(X, n - X) / (n * 0.3))           # 0 at the borders
    wander = vnoise(n, 3, 1, rng) * 0.55 + vnoise(n, 7, 1, rng) * 0.3
    lam = border_reach + (reach * (1 + wander) - border_reach) * lat
    dens = np.exp(-(Y / np.maximum(lam, 0.8)) ** 1.5) * strength
    if lumps:   # little dust bunnies sitting against the wall, mid-cell only
        for _ in range(rng.randint(1, 3)):
            m, _ = blob(n, rng, rng.uniform(n * 0.3, n * 0.7), rng.uniform(reach * 0.6, reach * 1.3),
                        rng.uniform(2.0, 4.0), soft=2.5, rag=0.6)
            dens = np.maximum(dens, m * 0.7 * strength)
    grain = 1 + 0.18 * fbm(n, rng, ((4, 4, 0.4), (8, 8, 0.7), (16, 16, 0.6))) * lat
    dens = dens * grain
    dens = dens * smooth((n - Y) / 4.0)                      # gone before the far edge
    return np.clip(dens, 0, 1)


def rot_tex(a, k):
    """Rotate a texture array so row 0 (north) goes k quarter turns CLOCKWISE
    (north->east->south->west), matching a blockstate y turn of the floor."""
    return np.rot90(a, -k, axes=(0, 1))


# =====================================================================
# TEXTURE PAINTERS - one function per kind, each returns {name: rgba}
# =====================================================================

N = 32


def dust_textures(seed=11):
    rng = random.Random(seed)
    out = {}
    light, dark = (98, 90, 80), (38, 34, 30)

    seamless = window(N, N * 0.3)      # band textures: no per-texture noise at the borders

    def paint(dens, max_a=0.62, band=False):
        if band:
            max_a = max_a * 1.25
        k = seamless if band else 1.0
        tone = fbm(N, rng) * 0.22 * k
        col = lerp_col(light, dark, dens * 0.95 + tone)
        speck = fbm(N, rng, ((8, 8, 0.4), (16, 16, 0.8))) * 0.14 * k
        return finish(col, np.clip(dens * max_a * (1 + speck), 0, 1))

    def film(r_lo, r_hi, strength, count=(1, 2)):
        d = np.zeros((N, N))
        for _ in range(rng.randint(*count)):
            m, _ = blob(N, rng, rng.uniform(7, 25), rng.uniform(7, 25), rng.uniform(r_lo, r_hi),
                        soft=rng.uniform(5, 8), rag=2.0)
            d = np.maximum(d, m * strength * rng.uniform(0.7, 1.0))
        return d

    def scuffs(count, strength):
        d = np.zeros((N, N))
        ang = rng.uniform(0, math.pi)
        for _ in range(count):
            m, _ = blob(N, rng, rng.uniform(7, 25), rng.uniform(7, 25), rng.uniform(2.5, 5.5),
                        soft=2.2, squash=(0.25, 0.45), tilt=ang + rng.uniform(-0.5, 0.5), rag=0.6)
            d = np.maximum(d, m * strength * rng.uniform(0.6, 1.0))
        return d

    def clump():
        m, _ = blob(N, rng, rng.uniform(9, 23), rng.uniform(9, 23), rng.uniform(4, 6.5), soft=4, rag=1.2)
        return m * rng.uniform(0.45, 0.6)

    # open floor: soft films, scuffs and the odd clump - deliberately UNEQUAL so a
    # field of them reads as uneven grime, not as one stamp per block
    blotches = [
        film(9, 13, 0.42),
        film(6, 10, 0.48, (2, 3)),
        np.maximum(film(8, 12, 0.22), scuffs(3, 0.45)),
        np.maximum(film(5, 8, 0.2), clump()),
        scuffs(rng.randint(2, 4), 0.5),
        np.maximum(film(10, 14, 0.18), np.maximum(clump(), scuffs(2, 0.35))),
    ]
    for i, d in enumerate(blotches):
        d = d * (1 + 0.3 * fbm(N, rng))
        out[f"dust_blotch_{i}"] = paint(np.clip(d, 0, 1) * window(N, 3))

    def edge():
        return band_profile(N, rng, rng.uniform(5.5, 10.0), 7.0)

    for i in range(4):
        out[f"dust_edge_{i}"] = paint(edge(), band=True)
    for i in range(3):          # corner: north + west bands + a heap in the corner
        d = np.maximum(edge(), rot_tex(edge(), 3))
        m, _ = blob(N, rng, rng.uniform(0, 3), rng.uniform(0, 3), rng.uniform(9, 13), soft=5, rag=1.5)
        d = np.clip(np.maximum(d, m), 0, 1)
        out[f"dust_corner_{i}"] = paint(d, band=True)
    for i in range(2):          # corridor: north + south
        out[f"dust_opposite_{i}"] = paint(np.maximum(edge(), rot_tex(edge(), 2)), band=True)
    for i in range(2):          # U: north + west + south (open to the east)
        out[f"dust_three_{i}"] = paint(np.maximum.reduce([edge(), rot_tex(edge(), 3), rot_tex(edge(), 2)]), band=True)
    for i in range(2):
        out[f"dust_ring_{i}"] = paint(np.maximum.reduce([rot_tex(edge(), k) for k in range(4)]), band=True)
    X, Y = grid(N)
    for i in range(4):          # wall base band (the band at the BOTTOM row) + faint wisps above
        d = np.flipud(band_profile(N, rng, rng.uniform(4.0, 8.0), 5.5, lumps=False))
        wisp = np.clip(0.5 + 0.5 * vnoise(N, 9, 2, rng), 0, 1) ** 2 * np.clip((Y - 12) / 20, 0, 1) * 0.25
        d = np.maximum(d, wisp * seamless)
        out[f"dust_wallbase_{i}"] = paint(np.clip(d, 0, 1), 0.55, band=True)

    def smudge():
        # dirt on a wall: soft, faint, running down a little (hands, splash, rain of dust)
        d = film(5, 9, 0.22)
        for _ in range(rng.randint(1, 3)):
            m, _ = blob(N, rng, rng.uniform(7, 25), rng.uniform(8, 22), rng.uniform(3, 6),
                        soft=3, squash=(0.3, 0.5), tilt=math.pi / 2 + rng.uniform(-0.15, 0.15), rag=0.8)
            grad = np.clip(1.2 - np.abs(Y - 14) / 16, 0.3, 1)
            d = np.maximum(d, m * grad * rng.uniform(0.3, 0.5))
        streak = np.clip(vnoise(N, 12, 2, rng) * 0.5 + 0.5, 0, 1)
        d = d * (0.65 + 0.6 * streak)
        return np.clip(d, 0, 1) * window(N, 3)

    for i in range(5):
        out[f"dust_smudge_{i}"] = paint(smudge(), 0.55)
    return out


def cloud(rng, coverage, octs, margin=2.0, shape_r=(15, 21)):
    """Irregular soft coverage spread over most of the cell (not one central
    blob): thresholded noise inside a big offset lumpy outline. A field of
    these reads as uneven grime, not as one stamp per block."""
    nz = fbm(N, rng, octs)
    d = np.clip(0.45 + 0.42 * nz + coverage, 0, 1) ** 1.3
    m, _ = blob(N, rng, rng.uniform(10, 22), rng.uniform(10, 22), rng.uniform(*shape_r), soft=7, rag=2.5)
    return np.clip(d * m, 0, 1) * window(N, margin)


def soot_textures(seed=23):
    rng = random.Random(seed)
    out = {}
    lo, hi = (40, 37, 34), (14, 13, 13)
    X, Y = grid(N)

    def paint(dens, max_a=0.7):
        col = lerp_col(lo, hi, dens * 0.9 + fbm(N, rng) * 0.15)
        return finish(col, np.clip(dens * max_a, 0, 1))

    def streaky(cells_x, cells_y):
        return np.clip(0.5 + 0.5 * (vnoise(N, cells_x, cells_y, rng) * 0.6
                                    + vnoise(N, cells_x * 2, cells_y * 2, rng) * 0.4), 0, 1)

    iso = ((2, 2, 0.35), (4, 4, 0.9), (8, 8, 0.45), (16, 16, 0.15))
    for i in range(4):          # floor: brake-dust smears, streaky along one axis
        along_x = i % 2 == 0
        octs = ((2, 3, 0.5), (3, 7, 0.8), (6, 14, 0.3)) if along_x else ((3, 2, 0.5), (7, 3, 0.8), (14, 6, 0.3))
        d = cloud(rng, rng.uniform(-0.15, 0.15), octs)
        out[f"soot_floor_{i}"] = paint(d, 0.55)
    for i in range(4):          # wall: vertical soot runs, heavier toward the top
        d = cloud(rng, rng.uniform(-0.2, 0.05), ((2, 2, 0.4), (5, 2, 0.8), (10, 4, 0.3), (16, 8, 0.15)))
        d = d * np.clip(1.15 - Y / 40, 0.35, 1)
        out[f"soot_wall_{i}"] = paint(d, 0.52)
    for i in range(3):          # soot line under a sooty ceiling: band at row 0
        d = band_profile(N, rng, rng.uniform(5, 9), 6.5, lumps=False)
        d = d * (1 + (streaky(8, 2) - 0.5) * 0.5 * window(N, N * 0.3))
        out[f"soot_topband_{i}"] = paint(np.clip(d, 0, 1), 0.62)
    for i in range(5):          # ceiling: mottled soot, a faint grain along one axis
        d = cloud(rng, rng.uniform(-0.05, 0.3), iso)
        d = d * (0.85 + 0.25 * (streaky(10, 2) if i % 2 else streaky(2, 10)))
        out[f"soot_ceiling_{i}"] = paint(np.clip(d, 0, 1), 0.68)
    return out


def tide(ratio, at, inner, outer):
    """A tide mark: darkest right at `at`, a soft gradient inward, a crisp
    outer edge - the way a dried water line looks."""
    d = ratio - at
    return np.where(d < 0, np.exp(d / inner), np.exp(-(d / outer) ** 2))


def tide_marks(rng, n, pale=False):
    """Irregular leak rings: faint fill, a brown tide line, fainter inner lines."""
    stain_fill = (186, 160, 104) if not pale else (160, 148, 124)
    line = (120, 86, 46) if not pale else (122, 106, 86)
    col = np.zeros((n, n, 3)) + stain_fill
    a = np.zeros((n, n))
    rings = rng.choice([1, 1, 2, 3])
    cx0, cy0 = rng.uniform(n * 0.38, n * 0.62), rng.uniform(n * 0.38, n * 0.62)
    for k in range(rings):
        r = rng.uniform(8.5, 13) if rings == 1 else rng.uniform(4.5, 8.5)
        lim = r * 1.15 + 2.5
        cx = min(max(cx0 + (rng.uniform(-7, 7) if k else 0), lim), n - lim)
        cy = min(max(cy0 + (rng.uniform(-7, 7) if k else 0), lim), n - lim)
        m, ratio = blob(n, rng, cx, cy, r, soft=1.0, rag=0.8)
        fill = m * (0.10 + 0.12 * ratio)                    # a bit darker toward the edge
        col, a = over(col, a, stain_fill, fill * (0.7 if pale else 1.0))
        lines = tide(ratio, 0.97, 0.10, 0.035) * 0.62
        for at, st in ((rng.uniform(0.6, 0.78), 0.3), (rng.uniform(0.3, 0.48), 0.18)):
            if rng.random() < 0.8:
                lines = np.maximum(lines, tide(ratio, at, 0.07, 0.03) * st)
        halo = np.clip(1 - (ratio - 1) / 0.2, 0, 1) * (ratio > 1) * 0.07
        lines = lines * (0.75 + 0.35 * np.clip(0.5 + 0.5 * fbm(n, rng), 0, 1))
        col, a = over(col, a, line, np.clip((lines + halo) * (0.7 if pale else 1.0), 0, 1))
    return finish(col, a * window(n, 2))


def water_textures(seed=37):
    rng = random.Random(seed)
    out = {}
    for i in range(5):
        out[f"water_ceiling_{i}"] = tide_marks(rng, N)
    for i in range(3):
        out[f"water_floor_{i}"] = tide_marks(rng, N, pale=True)
    X, Y = grid(N)
    fill_c, line_c = (184, 154, 100), (118, 86, 46)
    for i in range(5):
        # a seep stain hanging from the top (or from a crack): faint fill whose
        # lower edge is a scalloped tide line, with longer runs where it dripped
        col = np.zeros((N, N, 3)) + fill_c
        a = np.zeros((N, N))
        from_top = i < 3
        y_top = 0.0 if from_top else rng.uniform(4, 10)
        if from_top:
            lat = smooth(np.minimum(X, N - X) / 8)
            depth = 3.0 + lat * (rng.uniform(3, 9) + 3 * vnoise(N, 4, 1, rng))
            x_lo, x_hi = -1, N + 1
        else:
            cx, half = rng.uniform(13, 19), rng.uniform(7, 11)
            x_lo, x_hi = cx - half, cx + half
            inside = np.clip(1 - np.abs(X - cx) / half, 0, 1)
            depth = rng.uniform(6, 11) * np.sqrt(inside) + 1.5 * vnoise(N, 4, 1, rng) * inside
        for _ in range(rng.randint(2, 4)):                 # runs: deeper fingers of the stain
            rx = rng.uniform(max(4, x_lo + 2), min(28, x_hi - 2))
            rw = rng.uniform(1.2, 2.6)
            rl = rng.uniform(9, 27 - y_top)
            depth = np.maximum(depth, rl * np.exp(-((X - rx) / rw) ** 2) * (1 - 0.15 * np.abs(X - rx) / rw))
        bottom = y_top + depth
        rel = (Y - y_top) / np.maximum(depth, 0.5)        # 0 at the top, 1 on the tide line
        inside = (Y >= y_top) & (X > x_lo) & (X < x_hi)
        body = (rel <= 1) * inside
        col, a = over(col, a, fill_c, np.clip(body * (0.17 + 0.1 * rel), 0, 1))
        line = tide(rel, 1.0, 0.12, 0.05) * inside * smooth(depth / 2.5)
        line = line * (0.7 + 0.4 * np.clip(0.5 + 0.5 * fbm(N, rng), 0, 1))
        col, a = over(col, a, line_c, np.clip(line * 0.55, 0, 1))
        if not from_top:                                   # seeping out of the wall: fades in at the top
            a = a * smooth((Y - y_top + 0.5) / 3.0)
        win = smooth(np.minimum(X, N - X) / 2.5)
        if from_top:        # the seep line itself carries on into the next block
            win = np.maximum(win, (Y < 4) * 1.0)
        win = win * smooth((N - Y) / 2.5)
        out[f"water_wall_{i}"] = finish(col, a * win)
    return out


def rust_textures(seed=41):
    rng = random.Random(seed)
    out = {}
    X, Y = grid(N)
    core_c, edge_c, src_c = (158, 74, 30), (184, 110, 58), (96, 48, 22)
    for i in range(6):
        col = np.zeros((N, N, 3)) + edge_c
        a = np.zeros((N, N))
        top_source = i >= 4         # rust coming off a girder/ledge above
        streaks = rng.randint(1, 2) if not top_source else rng.randint(2, 3)
        for _ in range(streaks):
            sx = rng.uniform(9, 23)
            sy = 0.0 if top_source else rng.uniform(2.5, 5.5)
            length = rng.uniform(16, 30 - sy)
            w0 = rng.uniform(1.2, 2.6)
            wob = vnoise(N, 1, 3, random.Random(rng.random())) * rng.uniform(0.4, 1.2)
            t = np.clip((Y - sy) / length, 0, 1)
            w = w0 * (1 + 0.9 * t)                                      # widens as it runs
            dx = np.abs(X - (sx + wob))
            striae = 0.6 + 0.5 * np.clip(0.5 + 0.5 * vnoise(N, 14, 2, random.Random(rng.random())), 0, 1)
            fade = (1 - t) ** 1.3 * (Y >= sy - 0.5)
            body = smooth((w - dx) / 1.4 + 0.5) * fade * striae
            halo = smooth((w + 2.5 - dx) / 2.5) * fade * 0.3
            col, a = over(col, a, edge_c, np.clip(halo * 0.5, 0, 1))
            col, a = over(col, a, core_c, np.clip(body * 0.62, 0, 1))
            for _ in range(rng.randint(0, 2)):                           # thin side drips
                ox = sx + rng.choice([-1, 1]) * rng.uniform(w0 + 0.8, w0 + 3.5)
                oy = sy + rng.uniform(1, 6)
                ln = rng.uniform(5, 14)
                tt = np.clip((Y - oy) / ln, 0, 1)
                thin = np.exp(-((X - ox) / 0.55) ** 2) * (1 - tt) * (Y >= oy)
                col, a = over(col, a, core_c, np.clip(thin * 0.45, 0, 1))
            if not top_source:      # the weeping bolt / crack at the source
                m, _ = blob(N, rng, sx, sy, rng.uniform(1.4, 2.4), soft=1.8)
                col, a = over(col, a, src_c, m * 0.7)
        if top_source:
            lat = smooth(np.minimum(X, N - X) / 4)
            ledge = np.exp(-Y / 2.2) * 0.32 * lat * (0.7 + 0.4 * fbm(N, rng))
            col, a = over(col, a, src_c, np.clip(ledge, 0, 1))
        win = smooth(np.minimum(X, N - X) / 3) * smooth((N - Y) / 3)
        out[f"rust_wall_{i}"] = finish(col, a * win)
    for i in range(3):              # ceiling bloom
        col = np.zeros((N, N, 3)) + edge_c
        a = np.zeros((N, N))
        m, ratio = blob(N, rng, rng.uniform(12, 20), rng.uniform(12, 20), rng.uniform(6, 10), soft=3, rag=1.4)
        pit = np.clip(0.5 + 0.5 * fbm(N, rng, ((8, 8, 0.6), (16, 16, 0.8))), 0, 1)
        col, a = over(col, a, edge_c, m * 0.35)
        col, a = over(col, a, core_c, m * np.clip(1.15 - ratio, 0, 1) * 0.6 * (0.6 + 0.6 * pit))
        mc, _ = blob(N, rng, rng.uniform(13, 19), rng.uniform(13, 19), rng.uniform(1.8, 3.2), soft=1.5)
        col, a = over(col, a, src_c, mc * 0.75)
        out[f"rust_ceiling_{i}"] = finish(col, a * window(N, 3))
    return out


def efflo_textures(seed=53):
    rng = random.Random(seed)
    out = {}
    X, Y = grid(N)
    white, cream, grey = (240, 237, 228), (222, 214, 196), (200, 198, 192)

    def crust(region, rng_, thresh=0.05, max_a=0.88):
        """White mineral crust inside `region` (0..1): noise thresholded softly,
        sitting on a damp tan halo, with a faint shadow under its lower edges
        so it reads as a deposit (and still shows on white tile)."""
        nz = fbm(N, rng_, ((4, 4, 0.5), (8, 8, 0.9), (16, 16, 0.7)))
        hard = smooth((nz + 0.25 - (1 - region) * 1.0 - thresh) / 0.6) * region
        damp = np.clip(region * 1.3, 0, 1) * 0.13
        col = np.zeros((N, N, 3)) + (168, 156, 132)
        a = damp * (0.8 + 0.4 * np.clip(0.5 + 0.5 * fbm(N, rng_), 0, 1))
        below = np.roll(hard, 1, axis=0)
        below[0] = 0
        shadow = np.clip(below - hard, 0, 1)
        col, a = over(col, a, (120, 114, 104), shadow * 0.25)
        powder = smooth(region) ** 1.4 * 0.38 * (0.75 + 0.5 * np.clip(0.5 + 0.5 * fbm(N, rng_), 0, 1))
        col, a = over(col, a, (232, 230, 222), np.clip(powder, 0, 1))
        tone = np.clip(0.5 + 0.5 * fbm(N, rng_), 0, 1)
        ccol = lerp_col(lerp_col(white, cream, tone * 0.8), grey, np.clip(hard * 0.3 * tone, 0, 1))
        col, a = over(col, a, ccol, np.clip(hard * max_a, 0, 1))
        return col, a

    for i in range(5):
        region = np.zeros((N, N))
        if i < 2:                   # spreading down from a mortar joint / crack line
            y0 = rng.uniform(5, 12)
            wob = vnoise(N, 3, 1, random.Random(rng.random())) * 1.5
            below = np.clip(1 - (Y - (y0 + wob)) / rng.uniform(10, 16), 0, 1) * (Y > y0 + wob - 1.5)
            region = np.maximum(region, smooth(below * 1.4))
            for _ in range(rng.randint(2, 4)):                   # white runs
                rx = rng.uniform(5, 27)
                ln = rng.uniform(6, 14)
                run = np.exp(-((X - rx) / rng.uniform(0.8, 1.6)) ** 2) * np.clip(1 - (Y - y0) / (ln + 8), 0, 1) * (Y > y0)
                region = np.maximum(region, run * 0.9)
        else:                       # patchy blooms
            for _ in range(rng.randint(2, 3)):
                m, _ = blob(N, rng, rng.uniform(10, 22), rng.uniform(10, 22), rng.uniform(5, 10), soft=6, rag=2.0,
                            squash=(0.5, 0.8), tilt=math.pi / 2 + rng.uniform(-0.3, 0.3))
                region = np.maximum(region, m * rng.uniform(0.7, 1.0))
        col, a = crust(region, random.Random(rng.random()))
        out[f"efflo_wall_{i}"] = finish(col, a * window(N, 3))
    for i in range(3):              # ceiling: a bloom around a hairline crack
        region = np.zeros((N, N))
        ang = rng.uniform(0, math.pi)
        c, s_ = math.cos(ang), math.sin(ang)
        wob = vnoise(N, 3, 3, random.Random(rng.random())) * 2.5
        dist = np.abs((X - 16) * s_ - (Y - 16) * c + wob)
        along = np.abs((X - 16) * c + (Y - 16) * s_)
        reach = rng.uniform(9, 13)
        line = np.clip(1 - along / reach, 0, 1)
        region = np.maximum(region, np.clip(1 - dist / rng.uniform(5, 8), 0, 1) * smooth(line * 2))
        for _ in range(rng.randint(1, 2)):
            t = rng.uniform(-reach * 0.6, reach * 0.6)
            m, _ = blob(N, rng, 16 + c * t, 16 + s_ * t, rng.uniform(6.5, 10), soft=6, rag=2.0)
            region = np.maximum(region, m * 0.9)
        col, a = crust(smooth(region), random.Random(rng.random()))
        crack = np.exp(-(dist / 0.45) ** 2) * smooth(line * 3) * 0.55
        col, a = over(col, a, (78, 72, 64), crack)
        out[f"efflo_ceiling_{i}"] = finish(col, a * window(N, 3))
    return out


def puddle_textures(seed=61):
    rng = random.Random(seed)
    out = {}
    X, Y = grid(N)
    for i in range(5):
        col = np.zeros((N, N, 3)) + (34, 38, 44)
        a = np.zeros((N, N))
        mask = np.zeros((N, N))
        ratio = np.full((N, N), 9.0)
        lobes = 1 if i < 2 else rng.randint(2, 3)
        for _ in range(lobes):
            r = rng.uniform(10, 12.5) if lobes == 1 else rng.uniform(6, 9)
            lim = r + 2
            cx = rng.uniform(lim, N - lim) if lobes > 1 else rng.uniform(14, 18)
            cy = rng.uniform(lim, N - lim) if lobes > 1 else rng.uniform(14, 18)
            m, rt = blob(N, rng, cx, cy, r, soft=1.2, rag=0.3, squash=(0.8, 1.0))
            mask = np.maximum(mask, m)
            ratio = np.minimum(ratio, rt)
        damp = np.clip(1 - (ratio - 1) / 0.18, 0, 1) * (ratio > 0.97) * 0.16     # damp halo
        col, a = over(col, a, (44, 44, 44), damp)
        depth = np.clip(1.1 - ratio, 0, 1)
        col, a = over(col, a, lerp_col((46, 52, 60), (24, 28, 36), depth), mask * (0.38 + 0.14 * depth))
        # reflection of the station lights: a broad soft sheen across one side
        ang = rng.uniform(0, math.tau)
        side = ((X - 16) * math.cos(ang) + (Y - 16) * math.sin(ang)) / 12
        sheen = smooth(side * 1.4 + 0.2) * smooth(1 - ratio * 1.1) * 0.32
        col, a = over(col, a, (168, 180, 196), np.clip(sheen, 0, 1))
        rim = tide(ratio, 0.95, 0.09, 0.04) * mask
        col, a = over(col, a, (196, 204, 214), np.clip(rim * 0.32, 0, 1))
        out[f"puddle_{i}"] = finish(col, a * window(N, 2))
    return out


def gum_textures(seed=71):
    n = 64
    rng = random.Random(seed)
    out = {}
    X, Y = grid(n)
    plans = [5, 9, 3, 7, 12]
    for i, count in enumerate(plans):
        col = np.zeros((n, n, 3)) + (64, 60, 56)
        a = np.zeros((n, n))
        cluster = (rng.uniform(20, 44), rng.uniform(20, 44)) if i in (1, 4) else None
        placed = []
        tries = 0
        while len(placed) < count and tries < 400:
            tries += 1
            r = rng.uniform(1.8, 4.6)
            if cluster:
                cx, cy = cluster[0] + rng.gauss(0, 10), cluster[1] + rng.gauss(0, 10)
            else:
                cx, cy = rng.uniform(6, n - 6), rng.uniform(6, n - 6)
            m_ = r * 2.4 + 1
            if not (m_ < cx < n - m_ and m_ < cy < n - m_):
                continue
            if any(math.hypot(cx - px, cy - py) < r + pr + 2 for px, py, pr in placed):
                continue
            placed.append((cx, cy, r))
            m, ratio = blob(n, rng, cx, cy, r, soft=1.1, squash=(0.7, 1.0), harm=0.65)
            halo = np.clip(1 - (ratio - 1) / 0.6, 0, 1) * (ratio > 1) * 0.16
            col, a = over(col, a, (70, 66, 60), halo)
            tone = rng.uniform(-8, 10)
            core = np.clip(1.25 - ratio * 1.1, 0, 1)
            body_col = lerp_col((104, 98, 90), (60, 56, 52), core)
            col, a = over(col, a, body_col + tone, m * (0.55 + 0.3 * core))
        out[f"gum_{i}"] = finish(col, a)
    return out


def oil_textures(seed=83):
    rng = random.Random(seed)
    out = {}
    X, Y = grid(N)
    body_c, edge_c, spread_c = (34, 29, 24), (18, 15, 12), (52, 46, 40)

    def stain(col, a, cx, cy, r, strength=1.0):
        m, ratio = blob(N, rng, cx, cy, r, soft=2.2, rag=1.4)
        spread = np.clip(1 - (ratio - 1) / 0.45, 0, 1) * (ratio > 0.9) * 0.16 * strength
        col, a = over(col, a, spread_c, spread)
        body = m * (0.36 + 0.16 * np.clip(0.5 + 0.5 * fbm(N, rng), 0, 1)) * strength
        col, a = over(col, a, body_c, np.clip(body, 0, 1))
        col, a = over(col, a, edge_c, np.clip(tide(ratio, 0.92, 0.18, 0.06) * m * 0.32 * strength, 0, 1))
        sheen = np.exp(-(((X - cx) * 0.5 - (Y - cy) * 0.85 + rng.uniform(-3, 3)) / 2.6) ** 2)
        col, a = over(col, a, (82, 80, 78), np.clip(sheen * m * np.clip(1 - ratio, 0, 1) * 0.2, 0, 1))
        return col, a

    def drops(col, a, count, cx, cy, spread, rmax):
        for _ in range(count):
            r = rng.uniform(0.9, rmax)
            x, y = cx + rng.gauss(0, spread), cy + rng.gauss(0, spread)
            if not (r + 3 < x < N - r - 3 and r + 3 < y < N - r - 3):
                continue
            m, ratio = blob(N, rng, x, y, r, soft=1.0, harm=0.5)
            col, a = over(col, a, body_c, m * (0.45 + 0.2 * ring_profile(ratio, 0.85, 0.25)))
        return col, a

    for i in range(5):
        col = np.zeros((N, N, 3)) + body_c
        a = np.zeros((N, N))
        if i == 3:                  # a line of drips from a leaking car
            ang = rng.uniform(0, math.pi)
            for k in range(6):
                t = (k - 2.5) / 2.5 + rng.uniform(-0.12, 0.12)
                col, a = drops(col, a, rng.randint(1, 2), 16 + math.cos(ang) * t * 11,
                               16 + math.sin(ang) * t * 11, 0.8, 2.4)
        elif i == 4:                # stain with a spatter of drops round it
            col, a = stain(col, a, rng.uniform(13, 19), rng.uniform(13, 19), rng.uniform(5.5, 7.5))
            col, a = drops(col, a, 7, 16, 16, 7.5, 1.6)
        else:
            lobes = 1 if i in (0, 2) else 2
            for _ in range(lobes):
                r = rng.uniform(8.5, 11.5) if lobes == 1 else rng.uniform(5.5, 8)
                col, a = stain(col, a, rng.uniform(r * 0.5 + 9, 23 - r * 0.5), rng.uniform(r * 0.5 + 9, 23 - r * 0.5),
                               r, rng.uniform(0.75, 1.0))
        out[f"oil_{i}"] = finish(col, a * window(N, 2.5))
    return out


# ---------------------------------------------------------------- cobwebs (cutout)

def _line(img, x0, y0, x1, y1, colour, rng, break_p=0.0):
    """One-pixel Bresenham strand into an RGBA float array (cutout: fully
    opaque). break_p = chance per pixel of a short gap (a broken thread)."""
    n = img.shape[0]
    x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    gap = 0
    while True:
        if break_p and gap == 0 and rng.random() < break_p:
            gap = rng.randint(2, 3)
        if gap > 0:
            gap -= 1
        elif 0 <= x0 < n and 0 <= y0 < n:
            img[y0, x0, :3] = colour
            img[y0, x0, 3] = 255
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def _sagged(img, p1, p2, toward, sag, colour, rng, break_p=0.02):
    """A thread from p1 to p2 bowing by `sag` (fraction) toward point `toward`, in 3 pieces."""
    pts = []
    for t in (0.0, 1 / 3, 2 / 3, 1.0):
        x = p1[0] + (p2[0] - p1[0]) * t
        y = p1[1] + (p2[1] - p1[1]) * t
        bow = sag * 4 * t * (1 - t)
        pts.append((x + (toward[0] - x) * bow, y + (toward[1] - y) * bow))
    for q1, q2 in zip(pts, pts[1:]):
        _line(img, q1[0], q1[1], q2[0], q2[1], colour, rng, break_p)


WEB = (214, 214, 208)


def _strand_colour(rng):
    v = rng.uniform(-16, 6)
    return (WEB[0] + v, WEB[1] + v, WEB[2] + v)


def _web_from(img, rng, ax, ay, a0, a1, spokes, rmax, rings, sag=0.22, hole_p=0.2):
    """A radial web anchored at (ax, ay): spokes between angles a0..a1, then
    capture threads sagging toward the anchor, with torn-out holes."""
    angs = sorted(a0 + (a1 - a0) * ((k + rng.uniform(-0.25, 0.25)) / max(1, spokes - 1)) for k in range(spokes))
    lens = [rmax * rng.uniform(0.75, 1.0) for _ in angs]
    for ang, ln in zip(angs, lens):
        _line(img, ax, ay, ax + math.cos(ang) * ln, ay + math.sin(ang) * ln, _strand_colour(rng), rng, 0.008)
    for rr in rings:
        c = _strand_colour(rng)
        for k in range(len(angs) - 1):
            if rng.random() < hole_p:
                continue
            r1, r2 = rr * rng.uniform(0.9, 1.1), rr * rng.uniform(0.9, 1.1)
            if r1 > lens[k] or r2 > lens[k + 1]:
                continue
            p1 = (ax + math.cos(angs[k]) * r1, ay + math.sin(angs[k]) * r1)
            p2 = (ax + math.cos(angs[k + 1]) * r2, ay + math.sin(angs[k + 1]) * r2)
            _sagged(img, p1, p2, (ax, ay), sag, c, rng)


def _dust_clumps(img, rng, count):
    """A few grey specks of caught dust on the threads."""
    ys, xs = np.nonzero(img[..., 3] > 0)
    if len(xs) == 0:
        return
    for _ in range(count):
        k = rng.randrange(len(xs))
        g = rng.uniform(140, 165)
        img[ys[k], xs[k]] = (g, g - 2, g - 8, 255)


def cobweb_textures(seed=97):
    rng = random.Random(seed)
    out = {}
    n = 32

    def blank():
        img = np.zeros((n, n, 4))
        img[..., :3] = WEB
        return img

    def rings(first, count, grow):
        r, out_ = first, []
        for _ in range(count):
            out_.append(r)
            r *= grow
        return out_

    for i in range(4):              # anchored in the top-left corner
        img = blank()
        _web_from(img, rng, 0, 0, 0.05, math.pi / 2 - 0.05, rng.randint(5, 6), rng.uniform(24, 31),
                  rings(rng.uniform(7.5, 9.5), rng.randint(4, 5), rng.uniform(1.3, 1.4)))
        _dust_clumps(img, rng, rng.randint(1, 3))
        out[f"cobweb_corner_{i}"] = img.astype(np.uint8)
    for i in range(3):              # anchored along the top edge (row 0)
        img = blank()
        for ax in rng.sample([7, 12, 19, 25], 2):
            _web_from(img, rng, ax + rng.uniform(-1.5, 1.5), 0, 0.3, math.pi - 0.3, rng.randint(4, 5),
                      rng.uniform(13, 18), rings(rng.uniform(5.5, 7), 3, 1.4))
        _dust_clumps(img, rng, rng.randint(1, 3))
        out[f"cobweb_edge_{i}"] = img.astype(np.uint8)
    for i in range(3):              # chamfer sheet: row 0 on the ceiling, last row on the wall
        img = blank()
        lo, hi = rng.uniform(2, 6), rng.uniform(26, 30)
        for _ in range(rng.randint(6, 8)):      # crossing threads, ceiling line to wall line
            t, b_ = rng.uniform(lo, hi), rng.uniform(lo, hi)
            if abs(t - b_) < 3:
                b_ = min(hi, max(lo, b_ + rng.choice([-1, 1]) * rng.uniform(5, 10)))
            _line(img, t, 0, b_, n - 1, _strand_colour(rng), rng, 0.01)
        for _ in range(rng.randint(2, 3)):      # loose threads strung along the sheet
            y = rng.uniform(8, 26)
            _sagged(img, (lo + rng.uniform(0, 4), y - rng.uniform(2, 5)), (hi - rng.uniform(0, 4), y - rng.uniform(2, 5)),
                    (16, n), rng.uniform(0.15, 0.3), _strand_colour(rng), rng, 0.03)
        _dust_clumps(img, rng, rng.randint(2, 4))
        out[f"cobweb_sheet_{i}"] = img.astype(np.uint8)
    for i in range(3):              # scraps: a torn remnant with dust caught in it
        img = blank()
        ax, ay = rng.uniform(11, 21), rng.uniform(11, 21)
        a0 = rng.uniform(0, math.tau)
        _web_from(img, rng, ax, ay, a0, a0 + rng.uniform(2.2, 3.4), rng.randint(4, 5), rng.uniform(7, 10),
                  rings(2.8, 3, 1.5), hole_p=0.35)
        _dust_clumps(img, rng, rng.randint(3, 6))
        out[f"cobweb_scrap_{i}"] = img.astype(np.uint8)
    return out


# =====================================================================
# MODELS
# =====================================================================

_models = {}


def tex_ref(name):
    return f"{MOD}:block/grime/{name}"


def model(tex, kind, mirror=False):
    """Name of the model drawing texture `tex` as a decal of `kind`
    (floor / ceiling / wall / sheet); created on first use."""
    name = f"{tex}_{kind}" + ("_m" if mirror else "")
    if name in _models:
        return f"{MOD}:block/grime/{name}"
    u0, u1 = (16, 0) if mirror else (0, 16)
    if kind == "floor":
        el = {"from": [0, OFF, 0], "to": [16, OFF, 16],
              "faces": {"up": {"uv": [u0, 0, u1, 16], "texture": "#d"}}}
    elif kind == "ceiling":
        el = {"from": [0, 16 - OFF, 0], "to": [16, 16 - OFF, 16],
              "faces": {"down": {"uv": [u0, 16, u1, 0], "texture": "#d"}}}
    elif kind == "wall":
        el = {"from": [0, 0, OFF], "to": [16, 16, OFF],
              "faces": {"south": {"uv": [u0, 0, u1, 16], "texture": "#d"}}}
    elif kind == "sheet":
        # chamfer across the north wall / ceiling edge: a = 8 px each way
        a = 8.0
        half = a * math.sqrt(2) / 2
        cy, cz = 16 - a / 2, a / 2
        el = {"from": [0.5, round(cy - half, 4), cz], "to": [15.5, round(cy + half, 4), cz],
              "rotation": {"origin": [8, cy, cz], "axis": "x", "angle": 45},
              "faces": {"south": {"uv": [u0, 0, u1, 16], "texture": "#d"}}}
    else:
        raise ValueError(kind)
    _models[name] = {"textures": {"particle": tex_ref(tex), "d": tex_ref(tex)}, "elements": [el]}
    return f"{MOD}:block/grime/{name}"


# =====================================================================
# BLOCKSTATES
# =====================================================================

CW = {"north": "east", "east": "south", "south": "west", "west": "north"}
CCW = {v: k for k, v in CW.items()}
Y_OF = {"north": 0, "east": 90, "south": 180, "west": 270}
FACE_INDEX = {f: i for i, f in enumerate(ALL6)}


def turn_set(sides, k):
    s = set(sides)
    for _ in range(k % 4):
        s = {CW[x] for x in s}
    return s


def mirror_set(sides):
    swap = {"east": "west", "west": "east"}
    return {swap.get(x, x) for x in sides}


def entries(texs, kind, rotations=(0,), mirrors=(False,), weight=1, base_y=0):
    out = []
    for t in texs:
        for m in mirrors:
            for r in rotations:
                e = {"model": model(t, kind, m), "weight": weight}
                y = (base_y + r * 90) % 360
                if y:
                    e["y"] = y
                out.append(e)
    return out


def fitted(texs, kind, canon, want, weight=1):
    """Floor/ceiling entries of textures whose `canon` sides (in texture
    space, row 0 = north) land on exactly the `want` sides after mirror/turn."""
    out = []
    for t in texs:
        for m in (False, True):
            for r in range(4):
                sides = turn_set(mirror_set(canon) if m else canon, r)
                if sides == set(want):
                    e = {"model": model(t, kind, m), "weight": weight}
                    if r:
                        e["y"] = r * 90
                    out.append(e)
    return out


def decorrelate(lst, face):
    """Give each face's list a different total weight (multipart reseeds the
    same random for every selector)."""
    lst = [dict(e) for e in lst]
    for e in lst:
        e["weight"] = e.get("weight", 1) * 2
    lst[0]["weight"] += FACE_INDEX[face] + 1
    return lst


def part(when, lst, face):
    lst = decorrelate(lst, face)
    return {"when": when, "apply": lst if len(lst) > 1 else lst[0]}


def wall_parts(face_lists_fn):
    """For each wall: face_lists_fn(face) -> [(extra_when, list)]."""
    out = []
    for f in HORIZ:
        for extra, lst in face_lists_fn(f):
            when = {f: "true", **extra}
            out.append(part(when, [dict(e, y=(e.get("y", 0) + Y_OF[f]) % 360) for e in lst], f))
    for p in out:          # drop y: 0
        for e in (p["apply"] if isinstance(p["apply"], list) else [p["apply"]]):
            if e.get("y") == 0:
                del e["y"]
    return out


def wall_subsets():
    """All 16 subsets of the four walls, as (set, when-dict)."""
    for mask in range(16):
        s = {HORIZ[i] for i in range(4) if mask >> i & 1}
        yield s, {h: ("true" if h in s else "false") for h in HORIZ}


def shape_of(s):
    """Classify a wall subset: (canonical texture group, canonical sides)."""
    if not s:
        return "none", set()
    if len(s) == 1:
        return "edge", {"north"}
    if len(s) == 2:
        a, b = sorted(s)
        return ("opposite", {"north", "south"}) if CW[CW[a]] == b else ("corner", {"north", "west"})
    if len(s) == 3:
        return "three", {"north", "west", "south"}
    return "ring", set(HORIZ)


def floor_aware(groups, face_key, kind, generic_rot=(0, 1, 2, 3)):
    """16 selectors for a floor (face_key='down') or ceiling ('up') whose
    texture follows the cell's dusty walls. groups: name -> texture list."""
    parts = []
    for s, wh in wall_subsets():
        shape, canon = shape_of(s)
        if shape == "none":
            lst = entries(groups["none"], kind, generic_rot, (False, True))
        else:
            lst = fitted(groups[shape], kind, canon, s)
            if shape in ("edge", "corner"):
                lst = [dict(e, weight=3) for e in lst]
        parts.append(part({face_key: "true", **wh}, lst, face_key))
    return parts


def blockstate(block, parts):
    return {"multipart": parts}


def build_blockstates(T):
    """T: texture groups by name prefix -> sorted names."""
    def g(prefix):
        return sorted(k for k in T if k.startswith(prefix + "_") and k[len(prefix) + 1:].isdigit())

    rot4, mir = (0, 1, 2, 3), (False, True)
    bs = {}

    # ---- dust: floor follows the cell's dusty walls; walls band over a dusty floor
    dust_groups = {"none": g("dust_blotch"), "edge": g("dust_edge"), "corner": g("dust_corner"),
                   "opposite": g("dust_opposite"), "three": g("dust_three"), "ring": g("dust_ring")}
    parts = floor_aware(dust_groups, "down", "floor")
    parts += wall_parts(lambda f: [({"down": "true"}, entries(g("dust_wallbase"), "wall", (0,), mir)),
                                   ({"down": "false"}, entries(g("dust_smudge"), "wall", (0,), mir))])
    parts.append(part({"up": "true"}, entries(g("dust_blotch"), "ceiling", rot4, mir), "up"))
    bs["grime_dust"] = parts

    # ---- soot
    parts = [part({"down": "true"}, entries(g("soot_floor"), "floor", rot4, mir), "down")]
    parts += wall_parts(lambda f: [({"up": "true"}, entries(g("soot_topband"), "wall", (0,), mir)),
                                   ({"up": "false"}, entries(g("soot_wall"), "wall", (0,), mir))])
    parts.append(part({"up": "true"}, entries(g("soot_ceiling"), "ceiling", rot4, mir), "up"))
    bs["grime_soot"] = parts

    # ---- water stain
    parts = [part({"down": "true"}, entries(g("water_floor"), "floor", rot4, mir), "down")]
    parts += wall_parts(lambda f: [({}, entries(g("water_wall"), "wall", (0,), mir))])
    parts.append(part({"up": "true"}, entries(g("water_ceiling"), "ceiling", rot4, mir), "up"))
    bs["grime_water_stain"] = parts

    # ---- rust (no floor)
    parts = wall_parts(lambda f: [({}, entries(g("rust_wall"), "wall", (0,), mir))])
    parts.append(part({"up": "true"}, entries(g("rust_ceiling"), "ceiling", rot4, mir), "up"))
    bs["grime_rust_streak"] = parts

    # ---- efflorescence (no floor)
    parts = wall_parts(lambda f: [({}, entries(g("efflo_wall"), "wall", (0,), mir))])
    parts.append(part({"up": "true"}, entries(g("efflo_ceiling"), "ceiling", rot4, mir), "up"))
    bs["grime_efflorescence"] = parts

    # ---- floor-only kinds
    for block, prefix in (("grime_puddle", "puddle"), ("grime_gum", "gum"), ("grime_oil", "oil")):
        bs[block] = [part({"down": "true"}, entries(g(prefix), "floor", rot4, mir), "down")]

    # ---- cobweb: anchors toward a neighbouring wall / into the ceiling corner
    def web_wall(f):
        left, right = CCW[f], CW[f]
        flat_l = entries(g("cobweb_corner"), "wall", (0,), (False,))      # anchored top-left
        flat_r = entries(g("cobweb_corner"), "wall", (0,), (True,))       # anchored top-right
        sheet = [dict(e, weight=3) for e in entries(g("cobweb_sheet"), "sheet", (0,), mir)]
        edge_flat = entries(g("cobweb_edge"), "wall", (0,), mir)
        return [({left: "true"}, flat_l),
                ({left: "false", right: "true"}, flat_r),
                ({left: "false", right: "false", "up": "true"}, sheet + flat_l + flat_r),
                ({left: "false", right: "false", "up": "false"}, flat_l + flat_r + edge_flat)]

    web_groups_ceiling = {"none": g("cobweb_scrap"), "edge": g("cobweb_edge"), "corner": g("cobweb_corner"),
                          "opposite": g("cobweb_edge"), "three": g("cobweb_corner"), "ring": g("cobweb_corner")}
    parts = []
    for s, wh in wall_subsets():     # ceiling
        shape, canon = shape_of(s)
        if shape == "none":
            lst = entries(g("cobweb_scrap"), "ceiling", rot4, mir) + entries(g("cobweb_corner"), "ceiling", rot4)
        elif shape in ("edge", "opposite"):
            lst = fitted(web_groups_ceiling["edge"], "ceiling", {"north"}, {sorted(s)[0]})
        else:                        # anchor into one corner the walls make
            corner = next(({a, CW[a]} for a in HORIZ if a in s and CW[a] in s))
            lst = fitted(g("cobweb_corner"), "ceiling", {"north", "west"}, corner)
        parts.append(part({"up": "true", **wh}, lst, "up"))
    parts += wall_parts(web_wall)
    parts.append(part({"down": "true"}, entries(g("cobweb_scrap"), "floor", rot4, mir), "down"))
    bs["cobweb_corner"] = parts
    return bs


def fix_sheet_models(bs):
    """Drop any model that no blockstate entry ended up referencing."""
    used = set()
    for parts in bs.values():
        for p in parts:
            for e in (p["apply"] if isinstance(p["apply"], list) else [p["apply"]]):
                used.add(e["model"].split("/")[-1])
    for name in list(_models):
        if name not in used:
            del _models[name]


# =====================================================================
# ITEMS
# =====================================================================

ICON_TEX = {
    "grime_dust": ("dust_corner_0", "floor"), "grime_soot": ("soot_ceiling_1", "floor"),
    "grime_water_stain": ("water_ceiling_0", "floor"), "grime_rust_streak": ("rust_wall_0", "wall"),
    "grime_efflorescence": ("efflo_wall_3", "wall"), "grime_puddle": ("puddle_0", "floor"),
    "grime_gum": ("gum_1", "floor"), "grime_oil": ("oil_0", "floor"),
    "cobweb_corner": ("cobweb_corner_1", "floor"),
}


def icon(rgba, cutout=False, swatch=196):
    """The decal over a grey swatch (a darker one for the white decals) with a
    1 px darker border, 32 px."""
    n = 32
    src = Image.fromarray(rgba)
    if src.size[0] != n:
        src = src.resize((n, n), Image.LANCZOS)
    X, Y = grid(n)
    edge = np.minimum(np.minimum(X, n - X), np.minimum(Y, n - Y))
    base = np.zeros((n, n, 4))
    base[..., :3] = (swatch - 8 * (Y / n))[..., None]
    base[..., :3] -= np.where(edge < 3, 34, 0)[..., None]
    base[..., 3] = 255 * (edge > 2)
    s_ = np.asarray(src, dtype=float)
    a = s_[..., 3:4] / 255.0
    if not cutout:
        a = np.clip(a * 1.35, 0, 1)      # read at slot size
    inside = (edge > 3)[..., None]
    out = base.copy()
    out[..., :3] = np.where(inside, s_[..., :3] * a + base[..., :3] * (1 - a), base[..., :3])
    return np.clip(out, 0, 255).astype(np.uint8)


# =====================================================================
# WRITE + VERIFY
# =====================================================================

def all_textures():
    T = {}
    for fn in (dust_textures, soot_textures, water_textures, rust_textures, efflo_textures,
               puddle_textures, gum_textures, oil_textures, cobweb_textures):
        T.update(fn())
    return T


def write_all():
    T = all_textures()
    for d in (TEX_DIR, MODEL_DIR):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    for name, rgba in T.items():
        Image.fromarray(rgba).save(os.path.join(TEX_DIR, name + ".png"))
    bs = build_blockstates(T)
    fix_sheet_models(bs)
    for name, m in _models.items():
        AC.write_json(os.path.join(MODEL_DIR, name + ".json"), m)
    for block, parts in bs.items():
        AC.write_json(os.path.join(BS_DIR, block + ".json"), blockstate(block, parts))
    lang = {}
    tools = {}
    for block, (faces, render, tool, nice) in KINDS.items():
        tex, _ = ICON_TEX[block]
        swatch = 112 if block in ("cobweb_corner", "grime_efflorescence") else 196
        Image.fromarray(icon(T[tex], cutout=render == "cutout", swatch=swatch)).save(os.path.join(ITEM_TEX_DIR, block + ".png"))
        AC.item_flat(block)
        AC.loot_nothing(block)
        lang[f"block.{MOD}.{block}"] = nice
        tools.setdefault(tool, []).append(f"{MOD}:{block}")
    AC.upsert_lang(lang)
    for tool, ids in tools.items():
        AC.insert_into_tag(f"minecraft/tags/blocks/mineable/{tool}.json", ids)
    print(f"textures {len(T)}, models {len(_models)}, blockstates {len(bs)}")


def verify():
    errors = []
    for block, (faces, render, tool, nice) in KINDS.items():
        path = os.path.join(BS_DIR, block + ".json")
        try:
            with open(path) as fh:
                bs = json.load(fh)
        except Exception as ex:   # noqa: BLE001
            errors.append(f"{block}: blockstate unreadable: {ex}")
            continue
        for p in bs["multipart"]:
            for k, v in p["when"].items():
                if k not in faces:
                    errors.append(f"{block}: when-key {k} is not a property of this block")
                if v not in ("true", "false"):
                    errors.append(f"{block}: when {k}={v}")
            apply = p["apply"] if isinstance(p["apply"], list) else [p["apply"]]
            for e in apply:
                if e.get("y", 0) not in (0, 90, 180, 270) or "x" in e:
                    errors.append(f"{block}: bad rotation {e}")
                mpath = os.path.join(ASSETS, "models", e["model"].split(":")[1] + ".json")
                if not os.path.exists(mpath):
                    errors.append(f"{block}: missing model {e['model']}")
        for f in ALL6:
            if f in faces and not any(p["when"].get(f) == "true" for p in bs["multipart"]):
                errors.append(f"{block}: allowed face {f} never drawn")
        for rel in (f"models/item/{block}.json", f"textures/item/{block}.png"):
            if not os.path.exists(os.path.join(ASSETS, rel)):
                errors.append(f"{block}: missing {rel}")
        if not os.path.exists(os.path.join(AC.DATA, MOD, "loot_tables/blocks", block + ".json")):
            errors.append(f"{block}: missing loot table")
        with open(os.path.join(ASSETS, "lang/en_us.json")) as fh:
            if f"block.{MOD}.{block}" not in json.load(fh):
                errors.append(f"{block}: missing lang")
        with open(os.path.join(AC.DATA, f"minecraft/tags/blocks/mineable/{tool}.json")) as fh:
            if f"{MOD}:{block}" not in json.load(fh)["values"]:
                errors.append(f"{block}: missing {tool} tag")
    for fn in os.listdir(MODEL_DIR):
        with open(os.path.join(MODEL_DIR, fn)) as fh:
            m = json.load(fh)
        for t in m["textures"].values():
            tp = os.path.join(ASSETS, "textures", t.split(":")[1] + ".png")
            if not os.path.exists(tp):
                errors.append(f"{fn}: missing texture {t}")
        for el in m["elements"]:
            for c in el["from"] + el["to"]:
                if not -16 <= c <= 32:
                    errors.append(f"{fn}: element out of range")
            for face in el["faces"].values():
                if "uv" not in face or any(not 0 <= c <= 16 for c in face["uv"]):
                    errors.append(f"{fn}: uv {face.get('uv')}")
                if "cullface" in face:
                    errors.append(f"{fn}: decal faces must not cull")
    for fn in os.listdir(TEX_DIR):
        img = Image.open(os.path.join(TEX_DIR, fn))
        w, h = img.size
        if w != h or w not in (16, 32, 64) or img.mode != "RGBA":
            errors.append(f"{fn}: {img.size} {img.mode}")
    if errors:
        print("\n".join(errors))
        raise SystemExit(f"verify FAILED ({len(errors)})")
    print("verify OK")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()
    if not args.verify:
        write_all()
    verify()


if __name__ == "__main__":
    main()
