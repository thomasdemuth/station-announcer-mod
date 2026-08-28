#!/usr/bin/env python3
"""Coplanarity audit for the set-4 models: reports pairs of faces that lie in
the same plane, point the same way and overlap — the z-fight signature. Faces
carrying different element rotations are compared only against their own
rotation group (every stair piece shares one SLOPE rotation, so authored-space
comparison is exact for them)."""
import itertools
import json
import os

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                 "src/main/resources/assets/station_announcer/models/block")
NAMES = ["el_stair_side_model", "el_stair_canopy_model", "el_portal_post_shaft",
         "el_portal_post_foot", "el_portal_post_bracket", "el_portal_header_model",
         "el_lamp_gooseneck_model", "el_lamp_post_pole", "el_lamp_post_head"]
AX = {"north": (2, 0), "south": (2, 1), "west": (0, 0), "east": (0, 1),
      "down": (1, 0), "up": (1, 1)}


def rects(m):
    out = []
    for i, e in enumerate(m["elements"]):
        rot = json.dumps(e.get("rotation"))
        for fn in e["faces"]:
            ax, side = AX[fn]
            plane = e["from"][ax] if side == 0 else e["to"][ax]
            others = [k for k in (0, 1, 2) if k != ax]
            out.append((rot, ax, side, plane,
                        [(e["from"][k], e["to"][k]) for k in others], i, fn))
    return out


bad = 0
for n in NAMES:
    for a, b in itertools.combinations(
            rects(json.load(open(os.path.join(D, n + ".json")))), 2):
        if a[0] != b[0] or a[1] != b[1] or a[2] != b[2]:
            continue
        if abs(a[3] - b[3]) > 1e-6:
            continue
        if all(min(a[4][k][1], b[4][k][1]) - max(a[4][k][0], b[4][k][0]) > 1e-6
               for k in (0, 1)):
            bad += 1
            print(f"COPLANAR {n}: el{a[5]}.{a[6]} vs el{b[5]}.{b[6]} @ {a[3]}")
print("coplanar same-direction overlaps:", bad)
