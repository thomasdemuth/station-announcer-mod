#!/usr/bin/env python3
"""Regenerates the two Railroad PIDS inventory icons.

Run from anywhere:  python3 tools/gen_railroad_pids_icons.py

They deliberately reuse the subway PIDS icons' palette and 10-px-wide portrait
screen so the whole PIDS family reads as one set; what marks a railroad board
out is the coloured departure bar under the header, which is the thing you
actually notice on the real screens. The standing variant swaps the bottom of
the frame for a pair of legs, exactly like pids_nyc_standing.
"""

import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ITEMS = os.path.join(ROOT, "src/main/resources/assets/station_announcer/textures/item")

FRAME = (0x46, 0x46, 0x4C)
SCREEN = (0x08, 0x08, 0x0A)
WHITE = (0xF0, 0xF0, 0xF5)
GRAY = (0x9A, 0x9A, 0xA2)
BAR = (0x6B, 0x4F, 0xD0)
CLEAR = None

LEFT, RIGHT = 3, 12          # frame columns, matching the subway PIDS icons


def write_png(path, pixels):
    height = len(pixels)
    width = len(pixels[0])
    rows = []
    for row in pixels:
        rows.append(b"\x00" + b"".join(
            bytes((0, 0, 0, 0)) if px is None else bytes(px + (255,)) for px in row))
    raw = b"".join(rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def blank():
    return [[CLEAR for _ in range(16)] for _ in range(16)]


def board(top, bottom):
    """A framed screen occupying rows top..bottom inclusive, with its content."""
    grid = blank()
    for y in range(top, bottom + 1):
        for x in range(LEFT, RIGHT + 1):
            edge = y in (top, bottom) or x in (LEFT, RIGHT)
            grid[y][x] = FRAME if edge else SCREEN

    inner_left, inner_right = LEFT + 1, RIGHT - 1
    y = top + 1
    # header: line name on the left, clock on the right
    grid[y][inner_left] = GRAY
    grid[y][inner_left + 1] = GRAY
    grid[y][inner_right - 1] = GRAY
    grid[y][inner_right] = GRAY

    # the departure bar — the railroad board's signature
    for row in (y + 1, y + 2):
        for x in range(inner_left, inner_right + 1):
            grid[row][x] = BAR
    for x in range(inner_left, inner_left + 3):
        grid[y + 2][x] = WHITE   # destination text on the bar

    # station list: the route line with stops hanging off it
    line_x = inner_left + 1
    for row in range(y + 4, bottom):
        grid[row][line_x] = BAR
    for row in range(y + 4, bottom, 2):
        grid[row][line_x] = WHITE
        for x in range(line_x + 2, min(line_x + 5, inner_right + 1)):
            grid[row][x] = GRAY
    return grid


def main():
    write_png(os.path.join(ITEMS, "railroad_pids_wall.png"), board(0, 15))

    # Standing: shorter screen, then two legs down to the floor.
    standing = board(0, 12)
    for row in (13, 14, 15):
        standing[row][LEFT + 2] = FRAME
        standing[row][RIGHT - 2] = FRAME
    for x in range(LEFT + 1, RIGHT):
        standing[15][x] = FRAME
    write_png(os.path.join(ITEMS, "railroad_pids_standing.png"), standing)
    print("railroad PIDS icons written")


if __name__ == "__main__":
    main()
