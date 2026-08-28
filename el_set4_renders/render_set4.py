#!/usr/bin/env python3
"""Set-4 (street & entrance) preview renders: stair flight + side screens +
canopy, the portal with a name board, both lamps in both paints."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render_blockmodel as r

r.COLORS.update({
    "body": (40, 96, 66), "galv": (180, 183, 186), "lattice": (33, 82, 56),
    "roof": (128, 42, 38), "roof_u": (150, 56, 50), "screen": (216, 206, 186),
    "corru": (45, 100, 70), "corru_s": (168, 172, 176),
    "board": (18, 19, 21), "house": (52, 92, 64), "housec": (214, 202, 176),
    "planks": (118, 104, 88), "edge": (150, 148, 142), "soffit": (186, 178, 160),
    "exit": (24, 25, 28), "glaze": (150, 172, 178),
    "tread": (150, 152, 155), "tread_b": (222, 176, 40), "tread_t": (222, 176, 40),
    "riser": (90, 92, 95), "riser_b": (222, 176, 40), "riser_t": (222, 176, 40),
    "side": (110, 112, 115), "glow": (255, 236, 190),
})


def render(models, out, **kw):
    r.render(models, os.path.join(HERE, out), **kw)


STEPS = 4


def flight(side="el_stair_side_model", canopy="el_stair_canopy_model",
           stair="subway_stairs_plain", canopy_dy=48):
    """A STEPS-long flight ascending toward NORTH (-z): stair lane 1 block
    wide with a side screen either side and the canopy above."""
    models = []
    for i in range(STEPS):
        z = 16 * (STEPS - 1 - i)
        y = 16 * i
        if stair:
            models.append((stair, (16, y, z)))
        if side:
            models.append((side, (0, y, z)))
            models.append((side, (32, y, z)))
        if canopy:
            for x in (0, 16, 32):
                models.append((canopy, (x, y + canopy_dy, z)))
    return models


def main():
    models = flight()
    render(models, "set4_flight.png", yaw=215, pitch=14, size=760)
    render(models, "set4_flight_side.png", yaw=270, pitch=4, size=760)
    render(flight(canopy=None), "set4_flight_nocanopy.png", yaw=250, pitch=10, size=760)
    # true elevation: the screen band against the stair profile it must cover
    one = [m for m in flight() if m[0] != "el_stair_side_model"
           or m[1][0] == 32]
    render(one, "set4_elev.png", yaw=90, pitch=0, size=760)
    render([m for m in flight() if m[0] == "el_stair_canopy_model"
            and m[1][0] == 0], "set4_canopy_elev.png", yaw=90, pitch=0, size=700)
    render([m for m in flight() if m[0] == "el_stair_canopy_model"
            and m[1][0] == 0], "set4_canopy_under.png", yaw=200, pitch=-25, size=700)

    # portal: two 2-high post stacks (foot + bracket) + a header run, with the
    # el_name_board standing on the cornice in the cell ABOVE the header
    r.COLORS["lattice"] = (120, 170, 140)   # the cutout band, legible offline
    models = []
    for x in (0, 48):
        models += [("el_portal_post_shaft", (x, 0, 48)),
                   ("el_portal_post_foot", (x, 0, 48)),
                   ("el_portal_post_shaft", (x, 16, 48)),
                   ("el_portal_post_bracket", (x, 16, 48))]
    for x in (0, 16, 32, 48):
        models.append(("el_portal_header_model", (x, 32, 48)))
    models.append(("el_name_board_model", (24, 48, 48)))
    render(models, "set4_portal.png", yaw=205, pitch=12, size=760)
    render(models, "set4_portal_front.png", yaw=180, pitch=0, size=700)

    # lamps, both paints
    render([("el_lamp_gooseneck_model", (0, 0, 0))], "set4_gooseneck.png",
           yaw=215, pitch=10, size=520)
    render([("el_lamp_post_pole", (0, 0, 0)), ("el_lamp_post_pole", (0, 16, 0)),
            ("el_lamp_post_head", (0, 32, 0))], "set4_lamppost.png",
           yaw=215, pitch=8, size=520)
    render([("el_lamp_gooseneck_model_silver", (0, 0, 0))],
           "set4_gooseneck_silver.png", yaw=215, pitch=10, size=520)
    render([("el_lamp_post_pole_silver", (0, 0, 0)),
            ("el_lamp_post_pole_silver", (0, 16, 0)),
            ("el_lamp_post_head_silver", (0, 32, 0))],
           "set4_lamppost_silver.png", yaw=215, pitch=8, size=520)
    render([("el_lamp_gooseneck_model", (0, 0, 0))], "set4_gooseneck_elev.png",
           yaw=270, pitch=0, size=520)
    render([("el_lamp_post_pole", (0, 0, 0)), ("el_lamp_post_pole", (0, 16, 0)),
            ("el_lamp_post_head", (0, 32, 0))], "set4_lamppost_elev.png",
           yaw=270, pitch=0, size=520)


if __name__ == "__main__":
    main()
