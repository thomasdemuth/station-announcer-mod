#!/usr/bin/env python3
"""Regenerates the station floor textures, models, blockstates and data files.

Run from anywhere:  python3 tools/gen_floor_assets.py
Add --preview to also write contact sheets into a folder (see PREVIEWS below).

WHAT THESE BLOCKS ARE
---------------------
Two NYC station floors, each in a grimy and a fresh cut:

  platform_tile_floor[_clean]        small greyish ceramic/marble tiles, 6x6
                                     to a block (~17 cm each), photos 96 St
                                     and 33 St
  platform_concrete_floor_N[_clean]  big poured slabs, N blocks to a slab
                                     (N = 2, 3, 4), photo Lorimer St

THE TWO DIFFERENT RANDOMISATION MECHANISMS (this is the interesting part)
------------------------------------------------------------------------
Grime must NOT repeat on every block, and the two floors need different
machinery to get there:

* The TILE floor uses vanilla's own weighted random variants. The blockstate
  lists every (texture variant x y-rotation) pair with a weight, and vanilla
  picks one from the block's position hash. Light grime is weighted heavily
  and the gum/stain variants rarely, so a platform reads as mostly-grubby with
  occasional filth. Rotation is free variety here because the tile grid is
  symmetric under 90 degrees: grout sits at every 8th row AND column, so a
  rotated block still lines up with its neighbours - see GROUT below for the
  one-pixel trap that hides in exactly that claim.

* The CONCRETE floor needs its joint lines on a world-aligned grid, which a
  blockstate cannot see - so `ConcreteFloorBlock` computes two joint
  properties from floorMod(x, N) / floorMod(z, N) when the block is PLACED,
  and each of the four combinations then lists its grime variants with weights
  here, exactly like the tiles.

  This was first done with a Fabric BakedModel that read the BlockPos while
  the chunk meshed (the old conduit pipe's trick). It renders as a jointless
  slab under SODIUM, which does not implement the Fabric renderer API unless
  Indium is installed - it never calls emitBlockQuads and falls back to
  getQuads, which has no position. Plain blockstate data works everywhere.

  A joint is drawn inside the block's NORTH and WEST edges only, never on both
  sides of a boundary - one groove per joint, no double-width lines.

TEXTURE RULES LEARNED HERE
--------------------------
1. Wear is drawn through `put()`, which wraps modulo the canvas, so a smear
   running off one edge continues on the other and the texture stays seamless
   against itself. GUM IS THE EXCEPTION and is kept a full radius clear of the
   edges: wrapping only helps when the neighbouring block draws the SAME
   texture, and here it usually draws a different variant, so a wrapped splat
   showed up as two half-blobs stranded either side of a block boundary.
2. The low-frequency grime field is tileable value noise (cell counts must
   DIVIDE the canvas or the wrap breaks) and is kept to a small amplitude:
   two different variants sit next to each other in a floor, and only a gentle
   field hides the seam between them. Its lowest octave stays quiet for the
   same reason - see `fractal_noise`.
3. 96 px canvases, not 16. The tile grid needs enough pixels for grout that is
   both thin and rotation-symmetric (see GROUT), and 96 is divisible by 16 so
   all four mip levels are still generated - a canvas that is not gets a
   vanilla "will not be mipmapped" warning and shimmers at distance.
4. The up face of a vanilla cube maps u->x and v->z, so texture row 0 is the
   NORTH edge and column 0 the WEST edge. That is what makes the joint rows
   above land where ConcreteFloorBlock thinks they do.
"""

import argparse
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data/station_announcer")
MODELS = os.path.join(ASSETS, "models/block")
ITEM_MODELS = os.path.join(ASSETS, "models/item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
TEXTURES = os.path.join(ASSETS, "textures/block")

MOD = "station_announcer"
SIZE = 96                    # canvas edge, px (divisible by 16 -> full mipmaps)
TILE_N = 6                   # tiles across one block
PITCH = SIZE // TILE_N       # 16 px per tile
S = SIZE / 48.0              # geometry scale; artifact sizes were tuned at 48

# WHERE THE GROUT SITS INSIDE EACH 8 px CELL, and why it is not {0}.
#
# The tile blockstate rotates blocks freely for variety, so the grout pattern
# has to survive a 90-degree turn. A rotation maps pixel column x to column
# SIZE-1-x, i.e. cell offset k to 7-k, so the set of grout offsets is only
# preserved when it equals its own mirror. Grout at {0} maps to {PITCH-1}:
# rotated blocks came out with their whole grid shifted one pixel, which read
# as stair-stepped dark seams wandering across the floor. A centred pair is
# the narrowest set that is its own mirror.
#
# This is also why the canvas is 96 px and not 48. The narrowest symmetric
# grout is TWO pixels wide, and two of a 8 px cell is a quarter of the tile -
# the floor came out looking like a waffle rather than tilework. At a 16 px
# cell the same two pixels are an eighth, which is what the photos show.
#
# It still tiles between blocks: a tile runs 14 px either way, whether it sits
# inside one block (cols 9..22) or straddles a boundary (cols 89..95 + 0..6).
GROUT = (PITCH // 2 - 1, PITCH // 2)
GROUT_EDGE = (GROUT[0] - 1, GROUT[1] + 1)   # the tile pixels flanking the grout
SLAB_SIZES = (1, 2, 3, 4)    # concrete slab widths offered as separate blocks

# grime variants per material, and the 8-slot weight table the Java model
# indexes with a position hash (so light grime is common, gum rare)
TILE_VARIANTS = 5
TILE_WEIGHTS = (5, 4, 4, 2, 1)          # per texture variant, before rotation
CONCRETE_DIRTY_VARIANTS = 4
CONCRETE_DIRTY_WEIGHTS = (4, 2, 1, 1)   # per grime variant, inside each joint combo
CONCRETE_CLEAN_VARIANTS = 2
CONCRETE_CLEAN_WEIGHTS = (1, 1)
# the preview mirrors vanilla's weighted pick with these same weights
CONCRETE_DIRTY_TABLE = (0, 0, 0, 0, 1, 1, 2, 3)
CONCRETE_CLEAN_TABLE = (0, 0, 0, 0, 1, 1, 1, 1)


# =====================================================================
# RASTER HELPERS
# =====================================================================

def clamp8(v):
    return 0 if v < 0 else (255 if v > 255 else int(v))


def shade(c, delta):
    return (clamp8(c[0] + delta), clamp8(c[1] + delta), clamp8(c[2] + delta), 255)


def mul(c, f):
    return (clamp8(c[0] * f), clamp8(c[1] * f), clamp8(c[2] * f), 255)


def mix(a, b, t):
    return (clamp8(a[0] + (b[0] - a[0]) * t),
            clamp8(a[1] + (b[1] - a[1]) * t),
            clamp8(a[2] + (b[2] - a[2]) * t), 255)


def canvas(colour):
    return [[colour] * SIZE for _ in range(SIZE)]


def put(rows, x, y, colour):
    """Write one pixel, wrapping at the edges so artifacts stay seamless."""
    rows[int(y) % SIZE][int(x) % SIZE] = colour


def get(rows, x, y):
    return rows[int(y) % SIZE][int(x) % SIZE]


def blend(rows, x, y, colour, t):
    put(rows, x, y, mix(get(rows, x, y), colour, t))


def darken(rows, x, y, f):
    put(rows, x, y, mul(get(rows, x, y), f))


def smoothstep(t):
    return t * t * (3.0 - 2.0 * t)


def value_noise(cells, rng):
    """Tileable value noise in [-1, 1]. `cells` MUST divide SIZE."""
    assert SIZE % cells == 0, cells
    grid = [[rng.uniform(-1.0, 1.0) for _ in range(cells)] for _ in range(cells)]
    step = SIZE // cells
    out = [[0.0] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        y0 = (y // step) % cells
        y1 = (y0 + 1) % cells
        ty = smoothstep((y % step) / float(step))
        for x in range(SIZE):
            x0 = (x // step) % cells
            x1 = (x0 + 1) % cells
            tx = smoothstep((x % step) / float(step))
            a = grid[y0][x0] * (1 - tx) + grid[y0][x1] * tx
            b = grid[y1][x0] * (1 - tx) + grid[y1][x1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def fractal_noise(rng, octaves=((3, 0.30), (6, 0.75), (12, 0.65), (24, 0.5), (48, 0.32))):
    """Sum of tileable octaves, normalised to roughly [-1, 1].

    The top octave matters as much as the bottom one: with only coarse
    octaves the bilinear grid of `value_noise` itself becomes visible as a
    lattice of diamonds and crosses. Fine detail hides its own scaffolding.

    NOTE how little weight the lowest octave carries. A cells=3 octave varies
    over a third of the canvas, so at full amplitude every block came out with
    its own overall tone and a floor read as a quilt of differently shaded
    squares. Kept small it contributes broad mottling without any block-scale
    step; the mid octaves carry the dirt that varies WITHIN a block.
    """
    total = [[0.0] * SIZE for _ in range(SIZE)]
    norm = sum(a for _, a in octaves)
    for cells, amp in octaves:
        n = value_noise(cells, rng)
        for y in range(SIZE):
            row, nrow = total[y], n[y]
            for x in range(SIZE):
                row[x] += nrow[x] * amp
    return [[v / norm for v in row] for row in total]


def wrap_disc(rows, cx, cy, r, colour, edge=None):
    """A filled disc that wraps across the canvas edges."""
    rr = r * r
    for dy in range(int(-r) - 1, int(r) + 2):
        for dx in range(int(-r) - 1, int(r) + 2):
            d = (dx + 0.5) ** 2 + (dy + 0.5) ** 2
            if d <= rr:
                put(rows, cx + dx, cy + dy, colour)
            elif edge is not None and d <= (r + 0.9) ** 2:
                blend(rows, cx + dx, cy + dy, edge, 0.55)


def wrap_streak(rows, x0, y0, dx, dy, length, colour, strength):
    """A soft one-pixel scuff, drawn with wrapping and fading at both ends."""
    for i in range(int(length)):
        t = i / max(1.0, length - 1.0)
        fade = 1.0 - abs(t - 0.5) * 2.0
        blend(rows, x0 + dx * i, y0 + dy * i, colour, strength * fade)


# =====================================================================
# PALETTES
# =====================================================================

#
# The low-frequency wash amplitude is deliberately SMALL. Two different
# variants sit side by side in a real floor, and each carries its own noise
# field; a strong wash makes every block boundary read as a rectangular patch
# (the first pass did exactly that). Large-scale variation across a platform
# has to come from HAVING several variants, not from gradients inside one.
#
TILE_DIRTY = dict(
    tile=(171, 169, 164), grout=(107, 104, 98),
    tone_jitter=7, speckle=9, fleck=20, fleck_density=0.09,
    lf_low=0.93, lf_high=1.04,
    stain_factor=(0.78, 0.92), stain_warm=6,
)

TILE_CLEAN = dict(
    tile=(203, 200, 193), grout=(156, 152, 144),
    tone_jitter=4, speckle=6, fleck=14, fleck_density=0.07,
    lf_low=0.975, lf_high=1.02,
    stain_factor=(0.93, 0.98), stain_warm=0,
)

CONCRETE_DIRTY = dict(
    base=(152, 150, 146),
    lf_low=0.855, lf_high=1.07,
    speckle=9, fleck=24, fleck_density=0.11, pebbles=(40, 72), pits=(56, 104),
    joint=((92, 90, 86), (126, 124, 119)),
)

CONCRETE_CLEAN = dict(
    base=(182, 180, 175),
    lf_low=0.972, lf_high=1.022,
    speckle=6, fleck=13, fleck_density=0.06, pebbles=(20, 36), pits=(24, 48),
    joint=((138, 136, 131), (196, 194, 189)),
)

#
# One profile per texture variant: how worn THAT variant is. Vanilla (tiles)
# or FloorSlabModel (concrete) then picks among them with the weights below,
# so most blocks come out lightly grubby and only the odd one carries gum.
# Expected gum per block works out around 0.25 for both dirty floors - about
# one blob every four square metres, which is roughly what the photos show.
#
TILE_PROFILES = [                     # weight
    dict(stains=1, blots=1, scuffs=2, gum=0),        # 5
    dict(stains=2, blots=1, scuffs=3, gum=0),        # 4
    dict(stains=3, blots=2, scuffs=3, gum=0),        # 4
    dict(stains=4, blots=2, scuffs=4, gum=1),        # 2
    dict(stains=6, blots=3, scuffs=5, gum=2),        # 1
]
TILE_CLEAN_PROFILES = [
    dict(stains=0, blots=0, scuffs=0, gum=0),
    dict(stains=1, blots=0, scuffs=1, gum=0),
    dict(stains=0, blots=1, scuffs=1, gum=0),
]
CONCRETE_PROFILES = [                 # table slots
    dict(blots=2, scuffs=2, drags=1, gum=0, cracks=0),        # 4/8
    dict(blots=3, scuffs=3, drags=2, gum=0, cracks=0),        # 2/8
    dict(blots=3, scuffs=4, drags=2, gum=0, cracks=0),        # 1/8
    dict(blots=4, scuffs=5, drags=3, gum=2, cracks=0),        # 1/8
]
CONCRETE_CLEAN_PROFILES = [
    dict(blots=0, scuffs=0, drags=0, gum=0, cracks=0),
    dict(blots=1, scuffs=1, drags=0, gum=0, cracks=0),
]

GUM_CORE = (84, 80, 74)
GUM_RIM = (108, 104, 97)
SCUFF_LIGHT = (198, 196, 191)
SCUFF_DARK = (94, 92, 87)


# =====================================================================
# TILE FLOOR
# =====================================================================

def tile_top(pal, seed, profile):
    rng = random.Random(seed)
    rows = canvas(pal["tile"])
    lf = fractal_noise(rng)

    # per-tile tone, so the grid reads as individual tiles rather than a
    # printed pattern, plus a warm/cool cast so the field is not just grey
    tone = {}
    cast = {}
    for ty in range(TILE_N):
        for tx in range(TILE_N):
            tone[(tx, ty)] = rng.uniform(-pal["tone_jitter"], pal["tone_jitter"])
            cast[(tx, ty)] = rng.uniform(-2.5, 2.5)
    stained = set()
    while len(stained) < profile["stains"]:
        stained.add((rng.randrange(TILE_N), rng.randrange(TILE_N)))

    for y in range(SIZE):
        for x in range(SIZE):
            on_grout = (x % PITCH in GROUT) or (y % PITCH in GROUT)
            tx = ((x + PITCH - GROUT[0]) // PITCH) % TILE_N
            ty = ((y + PITCH - GROUT[0]) // PITCH) % TILE_N
            if on_grout:
                c = shade(pal["grout"], rng.uniform(-6, 6))
            else:
                c = shade(pal["tile"], tone[(tx, ty)] + rng.uniform(-pal["speckle"], pal["speckle"]))
                w = cast[(tx, ty)]
                c = (clamp8(c[0] + w), c[1], clamp8(c[2] - w), 255)
                # marble flecks
                if rng.random() < pal["fleck_density"]:
                    c = shade(c, rng.uniform(-pal["fleck"], pal["fleck"]))
                # a 1 px darker rim inside each tile: the ambient shadow the
                # grout channel casts, and what makes tiles read as tiles
                if x % PITCH in GROUT_EDGE or y % PITCH in GROUT_EDGE:
                    c = shade(c, -5)
                if (tx, ty) in stained:
                    f = rng.uniform(*pal["stain_factor"])
                    c = mul(c, f)
                    c = (clamp8(c[0] + pal["stain_warm"]), clamp8(c[1] + pal["stain_warm"] // 2),
                         c[2], 255)
            rows[y][x] = c

    # Low-frequency grime wash - over the TILES ONLY, never the grout.
    #
    # Grout sits at column 0 and row 0, so the grout line along a shared block
    # boundary is drawn entirely by the east/south block. Let the wash touch it
    # and that one block's tone lands on the boundary line alone, outlining
    # every block in dark grout: a floor came out covered in stair-stepped
    # rectangles. Holding grout at a constant tone across all variants is what
    # makes a run of mixed variants read as one continuous grid.
    lo, hi = pal["lf_low"], pal["lf_high"]
    for y in range(SIZE):
        for x in range(SIZE):
            if x % PITCH in GROUT or y % PITCH in GROUT:
                continue
            rows[y][x] = mul(rows[y][x], lo + (hi - lo) * (lf[y][x] * 0.5 + 0.5))

    add_wear(rows, rng, profile)
    return rows


# =====================================================================
# CONCRETE FLOOR
# =====================================================================

def concrete_top(pal, seed, profile, north_joint, west_joint):
    rng = random.Random(seed)
    rows = canvas(pal["base"])
    lf = fractal_noise(rng)
    lo, hi = pal["lf_low"], pal["lf_high"]

    for y in range(SIZE):
        for x in range(SIZE):
            c = shade(pal["base"], rng.uniform(-pal["speckle"], pal["speckle"]))
            if rng.random() < pal["fleck_density"]:
                c = shade(c, rng.uniform(-pal["fleck"], pal["fleck"]))
            rows[y][x] = mul(c, lo + (hi - lo) * (lf[y][x] * 0.5 + 0.5))

    # exposed aggregate: 2 px pebbles with a lit top edge. Kept gentle - a
    # strong delta here reads as a grid of black squares, not as stone.
    for _ in range(rng.randint(*pal["pebbles"])):
        px, py = rng.randrange(SIZE), rng.randrange(SIZE)
        d = rng.uniform(-9, 7)
        n = max(2, int(round(2 * S)))
        for oy in range(n):
            for ox in range(n):
                blend(rows, px + ox, py + oy, shade(pal["base"], d), 0.6)
        for ox in range(n - 1):
            blend(rows, px + ox, py, shade(pal["base"], d + 8), 0.4)

    # air pockets: single dark pixels, the thing that makes poured concrete
    # read as concrete rather than as flat grey paint
    for _ in range(rng.randint(*pal["pits"])):
        px, py = rng.randrange(SIZE), rng.randrange(SIZE)
        c = shade(pal["base"], rng.uniform(-40, -22))
        t = rng.uniform(0.5, 0.9)
        for oy in range(max(1, int(S))):
            for ox in range(max(1, int(S))):
                blend(rows, px + ox, py + oy, c, t)

    add_wear(rows, rng, profile)

    # joints last, so nothing is drawn over the groove
    dark, lip = pal["joint"]
    n_dark, n_lip = int(round(2 * S / 2)), int(round(2 * S / 2))
    if north_joint:
        for x in range(SIZE):
            for i in range(n_dark):
                blend(rows, x, i, shade(dark, rng.uniform(-5, 5)), 0.92)
            for i in range(n_lip):
                blend(rows, x, n_dark + i, shade(lip, rng.uniform(-5, 5)), 0.80)
    if west_joint:
        for y in range(SIZE):
            for i in range(n_dark):
                blend(rows, i, y, shade(dark, rng.uniform(-5, 5)), 0.92)
            for i in range(n_lip):
                blend(rows, n_dark + i, y, shade(lip, rng.uniform(-5, 5)), 0.80)
    return rows


# =====================================================================
# SHARED WEAR (gum, scuffs, blots, cracks)
# =====================================================================

def gum_splat(rows, rng, cx, cy, r):
    """One piece of trodden-in gum.

    Two things this must NOT be, both learned the hard way:

    * **A circle.** Discs - even a disc with satellite discs bolted on - read
      as a drawn dot. The outline here is a radius modulated by three low
      harmonics of the angle, so every splat is a different lopsided blob with
      lobes and dents, the way something flattened underfoot actually spreads.
    * **Near-black.** A hard dark fill punched a hole in the floor. The fill is
      blended, not written, at well under full strength and with per-pixel
      jitter, so the concrete's own grain and mottling still show through it -
      old gum is grey-brown and part of the floor, not a sticker on top. The
      rim is softer still, and a faint dirt halo rings the whole thing.
    """
    import math

    # the lopsided outline: r * (1 + sum of three angular harmonics)
    harmonics = [(rng.uniform(0.10, 0.22), rng.randint(2, 3), rng.uniform(0, 6.283)),
                 (rng.uniform(0.06, 0.14), rng.randint(4, 5), rng.uniform(0, 6.283)),
                 (rng.uniform(0.03, 0.08), rng.randint(6, 8), rng.uniform(0, 6.283))]
    squash = rng.uniform(0.78, 1.0)          # flattened, and at a random angle
    tilt = rng.uniform(0, 6.283)
    cos_t, sin_t = math.cos(tilt), math.sin(tilt)

    core = shade(GUM_CORE, rng.uniform(-6, 10))
    rim = shade(GUM_RIM, rng.uniform(-6, 10))
    reach = int(r * 2.2) + 2
    for dy in range(-reach, reach + 1):
        for dx in range(-reach, reach + 1):
            # rotate into the splat's own frame, then squash one axis
            u = (dx + 0.5) * cos_t + (dy + 0.5) * sin_t
            v = (-(dx + 0.5) * sin_t + (dy + 0.5) * cos_t) / squash
            d = math.hypot(u, v)
            if d < 0.001:
                d = 0.001
            angle = math.atan2(v, u)
            edge = r * (1.0 + sum(a * math.sin(k * angle + p) for a, k, p in harmonics))
            if d <= edge - 1.0:
                # the body: blended, never written, so the floor grain survives
                blend(rows, cx + dx, cy + dy, shade(core, rng.uniform(-7, 7)),
                      rng.uniform(0.62, 0.78))
            elif d <= edge:
                # a soft, ragged perimeter rather than an anti-aliased ring
                blend(rows, cx + dx, cy + dy, shade(rim, rng.uniform(-8, 8)),
                      rng.uniform(0.30, 0.55))
            elif d <= edge + 2.6 * S:
                # ground-in dirt halo, fading out
                darken(rows, cx + dx, cy + dy,
                       1.0 - 0.07 * (edge + 2.6 * S - d) / (2.6 * S))


def add_wear(rows, rng, profile):
    """Everything that makes a floor look walked on. Counts come from the
    variant's profile, never from a range - that is what keeps the artifacts
    OFF most blocks instead of on every one."""
    import math

    # soft dirt shadows: wide, low contrast, no hard edge
    for _ in range(profile.get("blots", 0)):
        cx, cy = rng.randrange(SIZE), rng.randrange(SIZE)
        r = rng.uniform(6 * S, 16 * S)
        strength = rng.uniform(0.05, 0.12)
        for dy in range(int(-r) - 1, int(r) + 2):
            for dx in range(int(-r) - 1, int(r) + 2):
                d = ((dx + 0.5) ** 2 + (dy + 0.5) ** 2) ** 0.5
                if d <= r:
                    t = 1.0 - d / r
                    darken(rows, cx + dx, cy + dy, 1.0 - strength * t * t)

    for _ in range(profile.get("scuffs", 0)):
        x0, y0 = rng.randrange(SIZE), rng.randrange(SIZE)
        ang = rng.uniform(0, 6.283)
        dx, dy = math.cos(ang), math.sin(ang)
        colour = SCUFF_LIGHT if rng.random() < 0.5 else SCUFF_DARK
        wrap_streak(rows, x0, y0, dx, dy, rng.randint(int(7 * S), int(18 * S)), colour,
                    rng.uniform(0.10, 0.22))

    # Long soft drag marks - the broad grey smears a platform picks up from
    # bags, brooms and hand trucks. They must stay WIDE, straight and faint:
    # a narrow wandering line reads as a pen scribble or a crack, which is
    # exactly what the first attempt looked like.
    for _ in range(profile.get("drags", 0)):
        x, y = rng.randrange(SIZE), rng.randrange(SIZE)
        ang = rng.uniform(0, 6.283)
        strength = rng.uniform(0.035, 0.07)
        length = rng.randint(int(22 * S), int(44 * S))
        width = rng.randint(int(3 * S), int(5 * S))
        for i in range(length):
            ang += rng.uniform(-0.035, 0.035) / S
            x += math.cos(ang)
            y += math.sin(ang)
            fade = 1.0 - abs(i / (length - 1.0) - 0.5) * 2.0
            nx, ny = math.cos(ang + 1.57), math.sin(ang + 1.57)
            for w in range(width):
                off = w - (width - 1) / 2.0
                taper = 1.0 - abs(off) / (width / 2.0 + 0.5)
                darken(rows, x + nx * off, y + ny * off,
                       1.0 - strength * fade * taper)

    for _ in range(profile.get("gum", 0)):
        # Gum is the ONE artifact that must not wrap. Wrapping keeps a texture
        # seamless against ITSELF, but neighbouring blocks usually draw a
        # different variant, so a splat cut by the edge showed up as two
        # half-blobs stranded on either side of a block boundary. Everything
        # else here is soft enough that a cut is invisible.
        r = rng.uniform(2.6 * S, 3.4 * S)
        margin = int(r * 1.6) + 2
        cx = rng.randrange(margin, SIZE - margin)
        cy = rng.randrange(margin, SIZE - margin)
        gum_splat(rows, rng, cx, cy, r)

    for _ in range(profile.get("cracks", 0)):
        x, y = rng.randrange(SIZE), rng.randrange(SIZE)
        ang = rng.uniform(0, 6.283)
        for _ in range(rng.randint(16, 34)):
            ang += rng.uniform(-0.45, 0.45)
            x += math.cos(ang)
            y += math.sin(ang)
            blend(rows, x, y, (104, 101, 96), 0.45)


# =====================================================================
# SIDE / BOTTOM
# =====================================================================

def concrete_side(pal, seed, tile_lip=None):
    """The vertical face of the slab: what you see at a platform edge.

    `tile_lip` (a tile palette) adds the ceramic lip along the top rows, so a
    tiled floor shows its tile thickness rather than bare concrete.
    """
    rng = random.Random(seed)
    rows = canvas(pal["base"])
    lf = fractal_noise(rng)
    lo, hi = pal["lf_low"], pal["lf_high"]
    for y in range(SIZE):
        for x in range(SIZE):
            c = shade(pal["base"], rng.uniform(-pal["speckle"], pal["speckle"]) - 8)
            if rng.random() < pal["fleck_density"] * 0.6:
                c = shade(c, rng.uniform(-pal["fleck"], pal["fleck"]))
            rows[y][x] = mul(c, lo + (hi - lo) * (lf[y][x] * 0.5 + 0.5))

    # dirt collects along the bottom of a platform face
    if lo < 0.95:
        band = int(8 * S)
        for y in range(SIZE - band, SIZE):
            f = 1.0 - 0.14 * ((y - (SIZE - band)) / (band - 1.0))
            for x in range(SIZE):
                darken(rows, x, y, f)
        # vertical drip streaks
        for _ in range(rng.randint(int(3 * S), int(7 * S))):
            x = rng.randrange(SIZE)
            top = rng.randrange(0, SIZE // 2)
            for y in range(top, min(SIZE, top + rng.randint(int(8 * S), int(26 * S)))):
                blend(rows, x, y, (104, 100, 94), rng.uniform(0.10, 0.22))

    if tile_lip is not None:
        lip_h = int(round(3 * S))
        for y in range(0, lip_h):
            for x in range(SIZE):
                c = tile_lip["grout"] if x % PITCH in GROUT else tile_lip["tile"]
                rows[y][x] = shade(c, rng.uniform(-6, 6) - (0 if y < lip_h - 1 else 8))
        for x in range(SIZE):
            blend(rows, x, lip_h, (70, 68, 64), 0.55)
    return rows


# =====================================================================
# WRITING ASSETS
# =====================================================================

def write_png(name, rows):
    pngtool.write_png(os.path.join(TEXTURES, name + ".png"), rows)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


def cube_model(name, top, side, bottom):
    write_json(os.path.join(MODELS, name + ".json"), {
        "parent": "minecraft:block/cube_bottom_top",
        "textures": {
            "top": f"{MOD}:block/{top}",
            "side": f"{MOD}:block/{side}",
            "bottom": f"{MOD}:block/{bottom}",
        },
    })


def item_model(name, parent):
    write_json(os.path.join(ITEM_MODELS, name + ".json"),
               {"parent": f"{MOD}:block/{parent}"})


def loot_table(block_id):
    write_json(os.path.join(DATA, "loot_tables/blocks", block_id + ".json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{MOD}:{block_id}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


def ring_recipe(name, outer, centre, result, count):
    write_json(os.path.join(DATA, "recipes", name + ".json"), {
        "type": "minecraft:crafting_shaped",
        "category": "building",
        "key": {"S": {"item": outer}, "D": {"item": centre}},
        "pattern": ["SSS", "SDS", "SSS"],
        "result": {"item": f"{MOD}:{result}", "count": count},
    })


def convert_recipe(name, source, result):
    write_json(os.path.join(DATA, "recipes", name + ".json"), {
        "type": "minecraft:crafting_shapeless",
        "category": "building",
        "ingredients": [{"item": f"{MOD}:{source}"}],
        "result": {"item": f"{MOD}:{result}", "count": 1},
    })


# =====================================================================
# PLATFORM EDGE - the yellow tactile strip along the track side
# =====================================================================
#
# One block, `platform_edge`, whose concrete body is the SAME weighted floor
# models the concrete floors use (picked by the clean/joint properties the
# Java block adopts from whatever floor it is placed against), plus a strip
# element on the FACING edge: 10 model px wide (the NYC 24-inch detectable
# warning) and 0.5 px proud, the way the real cast tiles sit on the slab.
# Only the strip is rotated by the blockstate; the concrete body is world-
# aligned, so the slab joints stay on the world grid across the whole edge.
#
# The strip texture is a 96 px square like everything else here (square is
# the only shape the atlas takes for block textures); rows 0..59 are the top,
# rows 60..63 the lip the side faces sample.

STRIP_W = 10                                   # model px, of 16
STRIP_ROWS = STRIP_W * SIZE // 16              # 60 canvas rows
STRIP_DIRTY = dict(base=(184, 154, 44), dome_lo=-34, dome_hi=22, grime=0.42,
                   scuffs=6, lip=(150, 124, 30))
STRIP_CLEAN = dict(base=(212, 180, 50), dome_lo=-30, dome_hi=26, grime=0.14,
                   scuffs=1, lip=(176, 148, 36))


def strip_texture(pal, seed):
    rng = random.Random(seed)
    rows = canvas(pal["base"])
    lf = fractal_noise(rng)
    for y in range(SIZE):
        for x in range(SIZE):
            c = shade(pal["base"], rng.uniform(-6, 6))
            rows[y][x] = mul(c, 0.94 + 0.10 * (lf[y][x] * 0.5 + 0.5))

    # truncated domes on a square grid: pitch 6 (~2.4 in at this scale),
    # a 3 px dome with a lit top-left and a shadowed bottom-right rim
    pitch = 6
    for cy in range(3, STRIP_ROWS, pitch):
        for cx in range(3, SIZE, pitch):
            for oy in range(-1, 2):
                for ox in range(-1, 2):
                    if abs(ox) + abs(oy) == 2:
                        continue
                    d = pal["dome_hi"] if (ox + oy) < 0 else (pal["dome_lo"] if (ox + oy) > 0 else 4)
                    blend(rows, cx + ox, cy + oy, shade(pal["base"], d), 0.85)
            blend(rows, cx + 1, cy + 1, shade(pal["base"], pal["dome_lo"] - 10), 0.6)

    # grime: heaviest toward the track lip (row 0), where boots and the gap
    # between train and platform leave it; mild scuffs across the field
    for y in range(STRIP_ROWS):
        edge = max(0.0, 1.0 - y / 14.0)
        for x in range(SIZE):
            g = pal["grime"] * (0.35 + 0.65 * (lf[y][(x * 3) % SIZE] * 0.5 + 0.5)) * (0.5 + edge)
            if g > 0:
                blend(rows, x, y, (70, 62, 40), min(0.6, g))
    for _ in range(pal["scuffs"]):
        x0, y0 = rng.randrange(SIZE), rng.randrange(STRIP_ROWS)
        wrap_streak(rows, x0, y0, rng.choice((1, -1)), rng.uniform(-0.3, 0.3),
                    rng.randint(8, 22), (88, 78, 48), 0.35)

    # the lip rows the vertical faces sample; darker, no domes
    for y in range(STRIP_ROWS, SIZE):
        for x in range(SIZE):
            rows[y][x] = shade(pal["lip"], rng.uniform(-6, 6))
    return rows


def strip_model(name, texture):
    """The proud strip on the NORTH edge; the blockstate turns it per facing.

    Its end faces at x=0/16 are deliberately NOT cullfaced: between two edge
    blocks they sit back to back and never show, but at the end of a run the
    neighbour is a plain floor whose cube stops at y=16 - culling there would
    leave the strip open-ended.
    """
    t = f"{MOD}:block/{texture}"
    lip = [0, STRIP_W, 16, STRIP_W + 0.5]
    write_json(os.path.join(MODELS, name + ".json"), {
        "textures": {"strip": t, "particle": t},
        "elements": [{
            "from": [0, 16, 0], "to": [16, 16.5, STRIP_W],
            "faces": {
                "up": {"uv": [0, 0, 16, STRIP_W], "texture": "#strip"},
                "north": {"uv": lip, "texture": "#strip"},
                "south": {"uv": lip, "texture": "#strip"},
                "east": {"uv": [0, STRIP_W, STRIP_W, STRIP_W + 0.5], "texture": "#strip"},
                "west": {"uv": [0, STRIP_W, STRIP_W, STRIP_W + 0.5], "texture": "#strip"},
            },
        }],
    })


def platform_edge_assets():
    strip_model("platform_edge_strip", "platform_edge_strip")
    strip_model("platform_edge_strip_clean", "platform_edge_strip_clean")

    parts = []
    for clean, prefix, count, weights in (
            (False, "platform_concrete_floor", CONCRETE_DIRTY_VARIANTS, CONCRETE_DIRTY_WEIGHTS),
            (True, "platform_concrete_floor_clean", CONCRETE_CLEAN_VARIANTS, CONCRETE_CLEAN_WEIGHTS)):
        for combo in range(4):
            north, west = bool(combo & 1), bool(combo & 2)
            parts.append({
                "when": {"clean": str(clean).lower(), "joint_north": str(north).lower(),
                         "joint_west": str(west).lower()},
                "apply": [{"model": f"{MOD}:block/{prefix}_c{combo}_{g}", "weight": weights[g]}
                          for g in range(count)],
            })
        strip = "platform_edge_strip_clean" if clean else "platform_edge_strip"
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            apply = {"model": f"{MOD}:block/{strip}"}
            if y:
                apply["y"] = y
            parts.append({"when": {"clean": str(clean).lower(), "facing": facing}, "apply": apply})
    write_json(os.path.join(BLOCKSTATES, "platform_edge.json"), {"multipart": parts})

    # item: the dirty 3-slab body with the strip, as one model
    top = f"{MOD}:block/platform_concrete_floor_top_c3_0"
    side = f"{MOD}:block/platform_concrete_floor_side"
    strip = f"{MOD}:block/platform_edge_strip"
    lip = [0, STRIP_W, 16, STRIP_W + 0.5]
    write_json(os.path.join(MODELS, "platform_edge_item.json"), {
        "parent": "minecraft:block/block",
        "textures": {"top": top, "side": side, "strip": strip, "particle": side},
        "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                "up": {"texture": "#top"}, "down": {"texture": "#side"},
                "north": {"texture": "#side"}, "south": {"texture": "#side"},
                "east": {"texture": "#side"}, "west": {"texture": "#side"}}},
            {"from": [0, 16, 0], "to": [16, 16.5, STRIP_W], "faces": {
                "up": {"uv": [0, 0, 16, STRIP_W], "texture": "#strip"},
                "north": {"uv": lip, "texture": "#strip"},
                "south": {"uv": lip, "texture": "#strip"},
                "east": {"uv": [0, STRIP_W, STRIP_W, STRIP_W + 0.5], "texture": "#strip"},
                "west": {"uv": [0, STRIP_W, STRIP_W, STRIP_W + 0.5], "texture": "#strip"}}},
        ],
    })
    item_model("platform_edge", "platform_edge_item")
    loot_table("platform_edge")
    write_json(os.path.join(DATA, "recipes", "platform_edge.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "building",
        "key": {"Y": {"item": "minecraft:yellow_dye"},
                "C": {"item": f"{MOD}:platform_concrete_floor_3"}},
        "pattern": ["YYY", "CCC"],
        "result": {"item": f"{MOD}:platform_edge", "count": 6},
    })


# =====================================================================
# MAIN
# =====================================================================

def build_textures():
    """Draw every texture and return them keyed by file name (for previews)."""
    out = {}

    for pal, prefix, profiles in ((TILE_DIRTY, "platform_tile_floor", TILE_PROFILES),
                                  (TILE_CLEAN, "platform_tile_floor_clean", TILE_CLEAN_PROFILES)):
        for i, profile in enumerate(profiles):
            out[f"{prefix}_top_{i}"] = tile_top(pal, f"{prefix}/{i}", profile)

    out["platform_tile_floor_side"] = concrete_side(
        CONCRETE_DIRTY, "tile_side", tile_lip=TILE_DIRTY)
    out["platform_tile_floor_clean_side"] = concrete_side(
        CONCRETE_CLEAN, "tile_side_clean", tile_lip=TILE_CLEAN)

    for pal, prefix, profiles in ((CONCRETE_DIRTY, "platform_concrete_floor", CONCRETE_PROFILES),
                                  (CONCRETE_CLEAN, "platform_concrete_floor_clean", CONCRETE_CLEAN_PROFILES)):
        for combo in range(4):
            north, west = bool(combo & 1), bool(combo & 2)
            for g, profile in enumerate(profiles):
                # same seed across combos, so a joint only ever ADDS a groove
                # to an otherwise identical slab face
                out[f"{prefix}_top_c{combo}_{g}"] = concrete_top(
                    pal, f"{prefix}/{g}", profile, north, west)
        out[f"{prefix}_side"] = concrete_side(pal, f"{prefix}/side")

    out["platform_edge_strip"] = strip_texture(STRIP_DIRTY, "edge/dirty")
    out["platform_edge_strip_clean"] = strip_texture(STRIP_CLEAN, "edge/clean")
    return out


def build_assets(textures):
    for name, rows in textures.items():
        write_png(name, rows)

    # ---- tile floors: vanilla weighted random variants + free rotation
    for prefix, count, weights in (("platform_tile_floor", TILE_VARIANTS, TILE_WEIGHTS),
                                   ("platform_tile_floor_clean", 3, (4, 3, 2))):
        side = prefix + "_side"
        variants = []
        for i in range(count):
            model = f"{prefix}_{i}"
            cube_model(model, f"{prefix}_top_{i}", side, side)
            for y in (0, 90, 180, 270):
                entry = {"model": f"{MOD}:block/{model}", "weight": weights[i]}
                if y:
                    entry["y"] = y
                variants.append(entry)
        write_json(os.path.join(BLOCKSTATES, prefix + ".json"), {"variants": {"": variants}})
        item_model(prefix, f"{prefix}_0")
        loot_table(prefix)

    # ---- concrete floors: the joint combination is a pair of blockstate
    # properties (ConcreteFloorBlock fills them in from the world position when
    # the block is placed), and each combination lists its grime variants with
    # weights for vanilla to pick from. All of it is plain blockstate data, so
    # it renders under Sodium - which ignores the Fabric renderer API that an
    # earlier position-reading BakedModel depended on.
    for prefix, count, weights in (
            ("platform_concrete_floor", CONCRETE_DIRTY_VARIANTS, CONCRETE_DIRTY_WEIGHTS),
            ("platform_concrete_floor_clean", CONCRETE_CLEAN_VARIANTS, CONCRETE_CLEAN_WEIGHTS)):
        side = prefix + "_side"
        variants = {}
        for combo in range(4):
            north, west = bool(combo & 1), bool(combo & 2)
            entries = []
            for g in range(count):
                model = f"{prefix}_c{combo}_{g}"
                cube_model(model, f"{prefix}_top_c{combo}_{g}", side, side)
                entries.append({"model": f"{MOD}:block/{model}", "weight": weights[g]})
            key = f"joint_north={str(north).lower()},joint_west={str(west).lower()}"
            variants[key] = entries
        suffix = "_clean" if prefix.endswith("_clean") else ""
        for n in SLAB_SIZES:
            write_json(os.path.join(BLOCKSTATES, f"platform_concrete_floor_{n}{suffix}.json"),
                       {"variants": variants})

    for n in SLAB_SIZES:
        item_model(f"platform_concrete_floor_{n}", "platform_concrete_floor_c3_0")
        item_model(f"platform_concrete_floor_{n}_clean", "platform_concrete_floor_clean_c3_0")
        loot_table(f"platform_concrete_floor_{n}")
        loot_table(f"platform_concrete_floor_{n}_clean")

    platform_edge_assets()

    # ---- recipes
    ring_recipe("platform_tile_floor", "minecraft:smooth_stone", "minecraft:gray_dye",
                "platform_tile_floor", 8)
    ring_recipe("platform_tile_floor_clean", "minecraft:smooth_stone", "minecraft:white_dye",
                "platform_tile_floor_clean", 8)
    ring_recipe("platform_concrete_floor_3", "minecraft:stone", "minecraft:gray_dye",
                "platform_concrete_floor_3", 8)
    ring_recipe("platform_concrete_floor_3_clean", "minecraft:stone", "minecraft:light_gray_dye",
                "platform_concrete_floor_3_clean", 8)
    # cycle the slab width in the crafting grid rather than shipping six recipes
    for suffix in ("", "_clean"):
        for a, b in ((3, 2), (2, 1), (1, 4), (4, 3)):
            convert_recipe(f"platform_concrete_floor_{b}{suffix}_from_{a}",
                           f"platform_concrete_floor_{a}{suffix}",
                           f"platform_concrete_floor_{b}{suffix}")


# =====================================================================
# PREVIEWS - an 8x8 block field per floor, so the look can be judged
# without starting the game (the project's cheapest texture iteration)
# =====================================================================

def pick_tile_variant(x, z, count, weights):
    """Mirrors vanilla's weighted pick closely enough for a preview."""
    h = (x * 3129871) ^ (z * 116129781)
    h = (h * h * 42317861 + h * 11) & 0xFFFFFFFFFFFF
    h = (h >> 16) & 0x7FFFFFFF
    total = sum(weights[:count]) * 4
    pick = h % total
    for i in range(count):
        for rot in range(4):
            if pick < weights[i]:
                return i, rot
            pick -= weights[i]
    return 0, 0


def concrete_hash(x, z):
    h = (x * 374761393 + 1180 + z * 1274126177) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h ^= h >> 16
    return (h >> 8) & 7


def rotate(rows, quarter):
    for _ in range(quarter % 4):
        rows = [[rows[SIZE - 1 - x][y] for x in range(SIZE)] for y in range(SIZE)]
    return rows


def field(textures, kind, blocks=8, scale=1, **kw):
    out = [[(0, 0, 0, 255)] * (SIZE * blocks * scale) for _ in range(SIZE * blocks * scale)]
    for bz in range(blocks):
        for bx in range(blocks):
            if kind == "tile":
                i, rot = pick_tile_variant(bx, bz, kw["count"], kw["weights"])
                tex = rotate(textures[f"{kw['prefix']}_top_{i}"], rot)
            else:
                combo = (1 if bz % kw["slab"] == 0 else 0) | (2 if bx % kw["slab"] == 0 else 0)
                g = kw["table"][concrete_hash(bx, bz)] % kw["count"]
                tex = textures[f"{kw['prefix']}_top_c{combo}_{g}"]
            for y in range(SIZE):
                for x in range(SIZE):
                    c = tex[y][x]
                    for sy in range(scale):
                        for sx in range(scale):
                            out[(bz * SIZE + y) * scale + sy][(bx * SIZE + x) * scale + sx] = c
    return out


def write_sheet(textures, path, blocks=6):
    """One labelled contact sheet of every floor, for reviewing the art.

    Each cell is a `blocks` x `blocks` patch of real floor - variants and
    rotations picked exactly as the game will pick them - so what is on the
    sheet is what gets built. Far cheaper than a client restart per attempt.
    """
    import pixel_kit as pk

    cells = [
        ("TILED - DIRTY", dict(kind="tile", prefix="platform_tile_floor",
                               count=TILE_VARIANTS, weights=TILE_WEIGHTS)),
        ("TILED - FRESH", dict(kind="tile", prefix="platform_tile_floor_clean",
                               count=3, weights=(4, 3, 2))),
        ("CONCRETE - DIRTY - 3 BLOCK SLABS",
         dict(kind="concrete", prefix="platform_concrete_floor",
              count=CONCRETE_DIRTY_VARIANTS, table=CONCRETE_DIRTY_TABLE, slab=3)),
        ("CONCRETE - FRESH - 3 BLOCK SLABS",
         dict(kind="concrete", prefix="platform_concrete_floor_clean",
              count=CONCRETE_CLEAN_VARIANTS, table=CONCRETE_CLEAN_TABLE, slab=3)),
        ("CONCRETE - DIRTY - 2 BLOCK SLABS",
         dict(kind="concrete", prefix="platform_concrete_floor",
              count=CONCRETE_DIRTY_VARIANTS, table=CONCRETE_DIRTY_TABLE, slab=2)),
        ("CONCRETE - DIRTY - 4 BLOCK SLABS",
         dict(kind="concrete", prefix="platform_concrete_floor",
              count=CONCRETE_DIRTY_VARIANTS, table=CONCRETE_DIRTY_TABLE, slab=4)),
    ]
    cell = SIZE * blocks
    label_h, pad, scale = 28, 10, 4
    cols, rows_n = 2, (len(cells) + 1) // 2
    w = cols * cell + (cols + 1) * pad
    h = rows_n * (cell + label_h) + (rows_n + 1) * pad
    sheet = [[(24, 24, 26, 255)] * w for _ in range(h)]

    for i, (label, kw) in enumerate(cells):
        cx = pad + (i % cols) * (cell + pad)
        cy = pad + (i // cols) * (cell + label_h + pad)
        pk.text(sheet, cx, cy + 4, label, (232, 232, 228), scale=scale)
        patch = field(textures, blocks=blocks, scale=1, **kw)
        for y in range(cell):
            row = sheet[cy + label_h + y]
            prow = patch[y]
            for x in range(cell):
                row[cx + x] = prow[x]
    pngtool.write_png(path, sheet)
    print("sheet:", path)


def write_previews(textures, folder):
    os.makedirs(folder, exist_ok=True)
    jobs = [
        ("preview_tile_dirty.png", dict(kind="tile", prefix="platform_tile_floor",
                                        count=TILE_VARIANTS, weights=TILE_WEIGHTS)),
        ("preview_tile_clean.png", dict(kind="tile", prefix="platform_tile_floor_clean",
                                        count=3, weights=(4, 3, 2))),
        ("preview_concrete_dirty.png", dict(kind="concrete", prefix="platform_concrete_floor",
                                            count=CONCRETE_DIRTY_VARIANTS,
                                            table=CONCRETE_DIRTY_TABLE, slab=3)),
        ("preview_concrete_clean.png", dict(kind="concrete", prefix="platform_concrete_floor_clean",
                                            count=CONCRETE_CLEAN_VARIANTS,
                                            table=CONCRETE_CLEAN_TABLE, slab=3)),
    ]
    for name, kw in jobs:
        pngtool.write_png(os.path.join(folder, name), field(textures, **kw))
        print("preview:", os.path.join(folder, name))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", metavar="DIR",
                    help="also write 8x8-block preview fields into DIR")
    ap.add_argument("--preview-only", action="store_true",
                    help="do not touch the mod's assets, just preview")
    args = ap.parse_args()

    textures = build_textures()
    if not args.preview_only:
        build_assets(textures)
        print(f"wrote {len(textures)} textures to {TEXTURES}")
    if args.preview:
        write_previews(textures, args.preview)


if __name__ == "__main__":
    main()
