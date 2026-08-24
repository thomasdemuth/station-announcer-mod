#!/usr/bin/env python3
"""Regenerates the mod icon (assets/station_announcer/icon.png, 128x128).

Run from anywhere:  python3 tools/gen_mod_icon.py

The icon is the mod in one picture: the white subway tile wall this mod
builds, its station-green band along the top, and a big NYC route bullet.

Drawn at 4x (512) and box-downsampled to 128 so the disc and the numeral get
real anti-aliasing out of a hard-edged painter.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/assets/station_announcer/icon.png")

SS = 4              # supersample factor
SIZE = 128 * SS     # working canvas

TILE = 16 * SS      # a tile reads at 16 px in the final icon
GROUT = 2 * SS

TILE_FACE = (238, 236, 230)
TILE_GROUT = (196, 193, 185)
BAND = (30, 92, 58)          # the mod's station green, a touch brighter for an icon
BAND_GROUT = (20, 64, 40)
BULLET_RED = (238, 53, 46)   # MTA line red
BULLET_RIM = (120, 22, 18)
WHITE = (255, 255, 255)


def wobble(tx, ty):
    return ((tx * 73856093) ^ (ty * 19349663)) % 11 - 5


def base_canvas():
    rows = []
    for y in range(SIZE):
        row = []
        ty = y // TILE
        band = ty < 2          # top two tile rows are the colour band
        for x in range(SIZE):
            tx = x // TILE
            on_grout = (x % TILE) < GROUT or (y % TILE) < GROUT
            if band:
                base = BAND_GROUT if on_grout else BAND
            else:
                base = TILE_GROUT if on_grout else TILE_FACE
            shade = 0 if on_grout else wobble(tx, ty)
            row.append(tuple(max(0, min(255, c + shade)) for c in base) + (255,))
        rows.append(row)
    return rows


def paint_disc(rows, cx, cy, radius, color):
    for y in range(max(0, cy - radius), min(SIZE, cy + radius + 1)):
        for x in range(max(0, cx - radius), min(SIZE, cx + radius + 1)):
            if (x - cx) ** 2 + (y - cy) ** 2 <= radius * radius:
                rows[y][x] = color + (255,)


def paint_numeral(rows, cx, cy, cap):
    """A bold geometric "1": stem, top-left flag, base serif — Helvetica-ish."""
    stem_w = int(cap * 0.26)
    top = cy - cap // 2
    bottom = cy + cap // 2
    stem_left = cx - stem_w // 2 + int(cap * 0.08)
    for y in range(top, bottom):
        for x in range(stem_left, stem_left + stem_w):
            rows[y][x] = WHITE + (255,)
    # Flag: a diagonal from the stem's top toward lower-left.
    flag_len = int(cap * 0.34)
    for i in range(flag_len):
        yy = top + int(i * 0.72)
        for x in range(stem_left - i, stem_left):
            if 0 <= yy < SIZE:
                rows[yy][x] = WHITE + (255,)
    # Base serif.
    serif_w = int(cap * 0.62)
    serif_h = int(cap * 0.10)
    for y in range(bottom - serif_h, bottom):
        for x in range(cx - serif_w // 2, cx + serif_w // 2):
            rows[y][x] = WHITE + (255,)


def downsample(rows, factor):
    out = []
    for y in range(0, SIZE, factor):
        line = []
        for x in range(0, SIZE, factor):
            r = g = b = 0
            for dy in range(factor):
                for dx in range(factor):
                    pr, pg, pb, _ = rows[y + dy][x + dx]
                    r += pr; g += pg; b += pb
            n = factor * factor
            line.append((r // n, g // n, b // n, 255))
        out.append(line)
    return out


def main():
    rows = base_canvas()
    cx = SIZE // 2
    cy = SIZE // 2 + 6 * SS      # a touch below centre: the band owns the top
    radius = 42 * SS
    # Rim first (a slightly larger dark disc), then the red disc over it —
    # the 1-supersampled-pixel ring that survives reads as the enamel edge.
    paint_disc(rows, cx, cy, radius + 2 * SS, BULLET_RIM)
    paint_disc(rows, cx, cy, radius, BULLET_RED)
    paint_numeral(rows, cx, cy, int(radius * 1.15))
    pngtool.write_png(OUT, downsample(rows, SS))
    print("icon: 128x128 written to", os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
