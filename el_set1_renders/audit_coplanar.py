#!/usr/bin/env python3
"""Flags coplanar face pairs with overlapping footprints inside each el
STRUCTURE model (the z-fighting rule from CLAUDE.md). Rotated elements are
skipped — item set 1 has none left."""
import json, os, itertools

HERE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(HERE, "..", "src/main/resources/assets/station_announcer/models/block")
NAMES = ["el_girder_green", "el_truss_green", "el_girder_brace_green",
         "el_column_shaft_solid_green", "el_column_shaft_lattice_green",
         "el_column_foot_green", "el_column_cap_green",
         "el_column_item_solid_green", "el_column_item_lattice_green",
         "el_deck_ties_model", "el_deck_plate_model"]
AX = {"north": ("z", 2), "south": ("z", 2), "west": ("x", 0),
      "east": ("x", 0), "up": ("y", 1), "down": ("y", 1)}


def rects(el):
    if el.get("rotation"):
        return []
    fr, to = el["from"], el["to"]
    out = []
    for name in el["faces"]:
        ax, i = AX[name]
        plane = fr[i] if name in ("north", "west", "down") else to[i]
        o = [j for j in range(3) if j != i]
        out.append((ax, round(plane, 4), name,
                    (fr[o[0]], to[o[0]], fr[o[1]], to[o[1]])))
    return out


bad = 0
for n in NAMES:
    m = json.load(open(os.path.join(M, n + ".json")))
    fs = [(i, r) for i, e in enumerate(m["elements"]) for r in rects(e)]
    for (i, a), (j, b) in itertools.combinations(fs, 2):
        if i == j or a[0] != b[0] or a[1] != b[1]:
            continue
        if (min(a[3][1], b[3][1]) - max(a[3][0], b[3][0]) > 1e-6
                and min(a[3][3], b[3][3]) - max(a[3][2], b[3][2]) > 1e-6):
            print(f"COPLANAR {n}: el{i}.{a[2]} vs el{j}.{b[2]} @ {a[0]}={a[1]}")
            bad += 1
print("coplanar overlaps:", bad)
