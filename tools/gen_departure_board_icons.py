#!/usr/bin/env python3
"""Regenerates the three departure-board inventory icons.

Run from anywhere:  python3 tools/gen_departure_board_icons.py

They share the PIDS family's palette and frame (see gen_railroad_pids_icons.py)
so the whole set reads as one group in the tab. What marks these out is that a
departure BOARD lists several trains: where the railroad PIDS icon has one big
coloured bar, these have a stack of short coloured rows under a pale header —
which is exactly what you see on the real screens from across a concourse.

  railroad_departure_wall   portrait, narrow, rows + a track column
  departure_board_wall      landscape, wide, full-width coloured rows
  departure_board_hanging   the same, slung under a ceiling stub
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_railroad_pids_icons import write_png, blank, FRAME, SCREEN, WHITE, GRAY

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ITEMS = os.path.join(ROOT, "src/main/resources/assets/station_announcer/textures/item")

# One colour per row, so the icon shows at a glance that a board lists a mix of
# routes - the single thing that distinguishes it from a one-train display.
ROW_COLORS = [(0xE0, 0x4B, 0x4B), (0x3E, 0x8F, 0xDE), (0xE8, 0xB0, 0x2E), (0x3F, 0xB0, 0x63)]


def framed(grid, left, right, top, bottom):
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            edge = y in (top, bottom) or x in (left, right)
            grid[y][x] = FRAME if edge else SCREEN


def portrait():
    """Narrow upright board: header, then rows with a track column at the right."""
    grid = blank()
    left, right, top, bottom = 3, 12, 0, 15
    framed(grid, left, right, top, bottom)
    inner_left, inner_right = left + 1, right - 1
    for x in range(inner_left, inner_right + 1):
        grid[top + 1][x] = WHITE                      # title bar
    row = top + 3
    for i, color in enumerate(ROW_COLORS):
        if row > bottom - 1:
            break
        for x in range(inner_left, inner_right - 1):  # bar stops short of the track
            grid[row][x] = color
        grid[row][inner_right] = GRAY                 # the track number
        row += 3
    return grid


def landscape(hanging):
    """Wide concourse board; the hanging one gains a ceiling stub and a drop."""
    grid = blank()
    top = 3 if hanging else 1
    left, right, bottom = 0, 15, 14
    framed(grid, left, right, top, bottom)
    inner_left, inner_right = left + 1, right - 1
    for x in range(inner_left, inner_right + 1):
        grid[top + 1][x] = WHITE                      # title bar
    row = top + 3
    for color in ROW_COLORS:
        if row > bottom - 2:
            break
        for x in range(inner_left, inner_right + 1):
            grid[row][x] = color
        for x in range(inner_left, inner_left + 3):
            grid[row][x] = WHITE                      # the time, in the left column
        row += 2
    for x in range(inner_left, inner_right + 1):
        grid[bottom - 1][x] = GRAY                    # station name along the bottom
    if hanging:
        for y in range(0, top):                       # the pole it hangs from
            grid[y][7] = FRAME
            grid[y][8] = FRAME
    return grid


def main():
    write_png(os.path.join(ITEMS, "railroad_departure_wall.png"), portrait())
    write_png(os.path.join(ITEMS, "departure_board_wall.png"), landscape(False))
    write_png(os.path.join(ITEMS, "departure_board_hanging.png"), landscape(True))
    print("departure board icons written")


if __name__ == "__main__":
    main()
