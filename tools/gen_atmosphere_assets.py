#!/usr/bin/env python3
"""Atmosphere layer (ATMOSPHERE_PLAN.md): the tools' icons and models and the
shared lang (tab, light conditions, maintenance + weathering messages,
commands). The block families have their own generators
(gen_grime_assets.py, gen_litter_assets.py, gen_construction_assets.py).

Run from anywhere:  python3 tools/gen_atmosphere_assets.py
"""

import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import atmo_common as C

CLEAR = (0, 0, 0, 0)


def icon_maintenance():
    """A steel wrench across a fluorescent tube with a dark end cap."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    # the tube: top-left to right, slightly glowing
    for x in range(1, 13):
        px[x, 3] = (250, 252, 236, 255)
        px[x, 4] = (226, 232, 214, 255)
        px[x, 2] = (196, 206, 190, 255)
    for y in (2, 3, 4):
        px[0, y] = (70, 70, 76, 255)
        px[13, y] = (70, 70, 76, 255)
    px[14, 3] = (150, 150, 158, 255)
    # flicker marks
    for (x, y) in ((4, 0), (8, 0), (12, 1)):
        px[x, y] = (255, 214, 92, 255)
    # wrench: diagonal handle from bottom-left to the head at upper right
    steel = (176, 182, 192, 255)
    steel_dark = (112, 118, 128, 255)
    steel_hi = (226, 230, 236, 255)
    for i in range(9):
        x, y = 2 + i, 14 - i
        px[x, y] = steel
        px[x + 1, y] = steel_dark
        px[x, y - 1] = steel_hi
    # open-end head: a C ring around (12, 6.5), open toward the upper right
    import math
    for y in range(3, 11):
        for x in range(8, 16):
            dx, dy = x + 0.5 - 12.0, y + 0.5 - 6.5
            d = math.hypot(dx, dy)
            if 1.3 <= d <= 2.9 and not (dx > 0.2 and dy < -0.2):
                px[x, y] = steel_hi if dy < 0 or dx < 0 else steel_dark if d > 2.3 else steel
            elif d < 1.3:
                px[x, y] = CLEAR
    # orange grip on the handle end
    for i in range(3):
        px[2 + i, 14 - i] = (232, 116, 30, 255)
        px[3 + i, 14 - i] = (178, 80, 18, 255)
    return img


def icon_weathering():
    """A wide brush dabbing a dark grime smear, a crumpled cup beside it."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    # grime smear along the bottom
    import random
    rnd = random.Random(7)
    for x in range(1, 15):
        for y in range(11, 15):
            d = abs(y - 12.6) + max(0, abs(x - 7.5) - 5) * 0.8
            if d < 1.6 + rnd.random() * 0.8:
                v = 58 + rnd.randint(-10, 12)
                px[x, y] = (v, v - 4, v - 10, 255)
    # brush handle (wood) diagonal from upper right
    wood = (150, 102, 58, 255)
    wood_dark = (104, 68, 36, 255)
    for i in range(6):
        x, y = 13 - i, 1 + i
        px[x, y] = wood
        px[x + 1, y] = wood_dark
    # ferrule
    for (x, y) in ((7, 7), (8, 7), (6, 8), (7, 8), (8, 6)):
        px[x, y] = (196, 198, 204, 255)
    # bristles, dark-tipped from the grime
    for (x, y) in ((4, 9), (5, 9), (6, 9), (5, 10), (4, 10), (3, 10), (6, 10), (3, 11), (4, 11), (5, 11)):
        px[x, y] = (214, 196, 150, 255) if y < 11 else (88, 80, 70, 255)
    # a crushed blue-and-white cup at the right
    for (x, y, c) in ((12, 10, (236, 238, 240)), (13, 10, (64, 104, 178)), (12, 11, (64, 104, 178)),
                      (13, 11, (236, 238, 240)), (14, 11, (236, 238, 240)), (12, 12, (236, 238, 240)),
                      (13, 12, (64, 104, 178)), (14, 12, (210, 176, 70))):
        px[x, y] = c + (255,)
    return img


LANG = {
    "itemGroup.station_announcer.atmosphere": "Baker City: Atmosphere",
    "item.station_announcer.maintenance_tool": "Maintenance Tool",
    "tooltip.station_announcer.maintenance.applies": "Applies: %s",
    "tooltip.station_announcer.maintenance.use": "Right-click a light: apply (again = repair) · sneak: repair",
    "tooltip.station_announcer.maintenance.use2": "Right-click the air: choose · MetroCard machine / turnstile: out of service · poster: age it",
    "gui.station_announcer.maintenance.hint": "Click a light to apply; again or sneak to repair.",
    "gui.station_announcer.maintenance.hint2": "Sneak-click the air: new blink rhythm.",
    "msg.station_announcer.maintenance.set": "%s: %s",
    "msg.station_announcer.maintenance.reroll": "Next light gets blink rhythm #%s",
    "msg.station_announcer.maintenance.out_of_service": "%s taken out of service",
    "msg.station_announcer.maintenance.in_service": "%s back in service",
    "msg.station_announcer.maintenance.poster_wear": "Poster: %s",
    "atmosphere.station_announcer.wear.0": "new",
    "atmosphere.station_announcer.wear.1": "weathered",
    "atmosphere.station_announcer.wear.2": "worn",
    "atmosphere.station_announcer.wear.3": "tagged",
    "msg.station_announcer.maintenance.refused": "Out of service — use another one",
    "atmosphere.station_announcer.light.healthy": "Working",
    "atmosphere.station_announcer.light.healthy.desc": "Steady, as built",
    "atmosphere.station_announcer.light.stutter": "Stutter",
    "atmosphere.station_announcer.light.stutter.desc": "Now and then a few quick drop-outs",
    "atmosphere.station_announcer.light.ballast": "Failing ballast",
    "atmosphere.station_announcer.light.ballast.desc": "Steady, then seconds of rapid chatter",
    "atmosphere.station_announcer.light.pulse": "Slow pulse",
    "atmosphere.station_announcer.light.pulse.desc": "Brightness breathes up and down",
    "atmosphere.station_announcer.light.restrike": "Restriking",
    "atmosphere.station_announcer.light.restrike.desc": "Goes dark, strikes a few times, catches",
    "atmosphere.station_announcer.light.dim": "Dim",
    "atmosphere.station_announcer.light.dim.desc": "Half the light — the room really gets darker",
    "atmosphere.station_announcer.light.warm": "Old tube",
    "atmosphere.station_announcer.light.warm.desc": "Yellowed and a little weaker",
    "atmosphere.station_announcer.light.dead": "Dead",
    "atmosphere.station_announcer.light.dead.desc": "Burnt out: no light at all",
    "item.station_announcer.weathering_tool": "Weathering Tool",
    "tooltip.station_announcer.weathering.preset": "Condition: %s",
    "tooltip.station_announcer.weathering.use": "Click two corners: weather the box · sneak-click: the whole MTR station",
    "tooltip.station_announcer.weathering.use2": "Right-click the air: settings · sneak: forget the first corner",
    "gui.station_announcer.weathering.hint": "Click two corners, or sneak-click for the whole station.",
    "gui.station_announcer.weathering.hint2": "One undo per player.",
    "gui.station_announcer.weathering.custom": "Custom",
    "gui.station_announcer.weathering.preset.pristine": "Pristine",
    "gui.station_announcer.weathering.preset.lived_in": "Lived-in",
    "gui.station_announcer.weathering.preset.gritty": "Gritty",
    "gui.station_announcer.weathering.preset.neglected": "Neglected",
    "gui.station_announcer.weathering.preset.abandoned": "Abandoned",
    "gui.station_announcer.weathering.layer.dust": "Dust",
    "gui.station_announcer.weathering.layer.soot": "Soot",
    "gui.station_announcer.weathering.layer.stains": "Leaks",
    "gui.station_announcer.weathering.layer.rust": "Rust",
    "gui.station_announcer.weathering.layer.efflorescence": "Mineral bloom",
    "gui.station_announcer.weathering.layer.puddles": "Puddles",
    "gui.station_announcer.weathering.layer.gum": "Gum",
    "gui.station_announcer.weathering.layer.oil": "Oil stains",
    "gui.station_announcer.weathering.layer.cobwebs": "Cobwebs",
    "gui.station_announcer.weathering.layer.litter": "Litter",
    "gui.station_announcer.weathering.layer.trash": "Trash piles",
    "gui.station_announcer.weathering.layer.lights": "Failing lights",
    "gui.station_announcer.weathering.layer.severity": "How badly",
    "gui.station_announcer.weathering.area_heading": "Area",
    "gui.station_announcer.weathering.height": "Height",
    "gui.station_announcer.weathering.below": "Below",
    "gui.station_announcer.weathering.above": "Above",
    "gui.station_announcer.weathering.clean": "Clean the area first",
    "gui.station_announcer.weathering.undo": "Undo last",
    "msg.station_announcer.weathering.corner": "First corner %s — click the opposite corner",
    "msg.station_announcer.weathering.corner_cleared": "First corner forgotten",
    "msg.station_announcer.weathering.no_station": "No MTR station here — click two corners instead",
    "msg.station_announcer.weathering.busy": "Still weathering your last area — wait for it to finish",
    "msg.station_announcer.weathering.too_big": "Too big: %s blocks (at most %s). Pick a smaller box or lower Below/Above.",
    "msg.station_announcer.weathering.failed": "Weathering failed — see the server log",
    "msg.station_announcer.weathering.area": "%s × %s area",
    "msg.station_announcer.weathering.done": "%s → %s: %s grime decals, %s litter / trash, %s failing lights (%s old pieces cleared)",
    "msg.station_announcer.weathering.nothing": "Nothing to do here",
    "msg.station_announcer.weathering.progress": "Weathering: %s%%",
    "msg.station_announcer.weathering.undone": "Undid %s block changes and %s lights",
    "msg.station_announcer.weathering.nothing_to_undo": "Nothing to undo",
    "msg.station_announcer.weathering.undo_other_world": "Your last weathering was in another dimension",
    "commands.station_announcer.atmosphere.unknown_condition": "Unknown condition (stutter, ballast, pulse, restrike, dim, warm, dead, healthy)",
    "commands.station_announcer.atmosphere.not_a_light": "That block gives off no light",
    "commands.station_announcer.atmosphere.light_set": "Light at %s: %s",
    "commands.station_announcer.atmosphere.lights_set": "%s lights set to %s",
    "commands.station_announcer.atmosphere.not_equipment": "That is not a MetroCard machine, turnstile or HEET",
    "commands.station_announcer.atmosphere.service_out": "%s taken out of service",
    "commands.station_announcer.atmosphere.service_in": "%s back in service",
    "commands.station_announcer.atmosphere.too_big": "Area too big (2 million blocks at most)",
}


def main():
    os.makedirs(os.path.join(C.ASSETS, "textures/item"), exist_ok=True)
    icon_maintenance().save(C.tex_path("maintenance_tool", "item"))
    C.item_flat("maintenance_tool")
    icon_weathering().save(C.tex_path("weathering_tool", "item"))
    C.item_flat("weathering_tool")
    C.upsert_lang(LANG)
    verify()


def verify():
    import json
    assert os.path.exists(C.tex_path("maintenance_tool", "item"))
    json.load(open(os.path.join(C.ASSETS, "models/item/maintenance_tool.json")))
    lang = json.load(open(os.path.join(C.ASSETS, "lang/en_us.json")))
    missing = [k for k in LANG if k not in lang]
    if missing:
        sys.exit("missing lang: " + ", ".join(missing))
    print("atmosphere assets verified")


if __name__ == "__main__":
    main()
