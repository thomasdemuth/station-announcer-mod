#!/usr/bin/env python3
"""The joint the brief asked about: the top of a stair canopy run meeting an
el_canopy_flat platform canopy placed ONE UP / ONE −z from the last stair
canopy block."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render_set4 as s

s.r.COLORS.update({"corru": (60, 130, 92), "roof": (150, 50, 44),
                   "body": (30, 74, 52), "lattice": (120, 170, 140)})

m = []
for i in range(3):
    m.append(("el_stair_canopy_model", (0, 16 * i, 16 * (2 - i))))
top_y, top_z = 32, 0                       # the last (highest) canopy block
for k in range(3):
    m.append(("el_canopy_flat_slab", (0, top_y + 16, top_z - 16 - 16 * k)))
s.render(m, "set4_canopy_junction.png", yaw=250, pitch=6, size=760)
s.render(m, "set4_canopy_junction_elev.png", yaw=270, pitch=0, size=760)
s.render([("el_lamp_gooseneck_model", (0, 0, 0))],
         "set4_gooseneck_elev.png", yaw=270, pitch=0, size=520)
