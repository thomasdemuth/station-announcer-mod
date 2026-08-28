#!/usr/bin/env python3
"""El phase 3 (mezzanine + street + platform details) — the real generator
module, invoked from gen_el_assets.build(); `--out DIR` still writes a
standalone preview tree for offline renders. See EL_STATION_PLAN.md."""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk
from gen_el_assets import (PAINTS, f, elem, Gen, wpng, tex_lattice,
                           CREAM, CREAM_DARK, GLAZE, GLAZE_LIT, GLAZE_DARK,
                           ROOF_RED, ROOF_RED_LIT, ROOF_RED_DARK)

MOD = "station_announcer"
G = PAINTS["green"]

HOUSE_GREEN = (52, 92, 64)
HOUSE_GREEN_LIT = (66, 110, 78)
HOUSE_GREEN_DARK = (40, 74, 50)
HOUSE_GREEN_SHADOW = (30, 58, 40)
HOUSE_CREAM = (214, 202, 176)
HOUSE_CREAM_LIT = (228, 216, 190)
HOUSE_CREAM_DARK = (192, 180, 156)
HOUSE_CREAM_SHADOW = (168, 156, 134)
PLANK = (118, 104, 88)
PLANK_LIT = (134, 120, 102)
PLANK_DARK = (98, 86, 72)
PLANK_GAP = (72, 62, 52)
CONCRETE = (148, 146, 140)
CONCRETE_LIT = (162, 160, 154)
CONCRETE_DARK = (128, 126, 121)
TACTILE = (222, 168, 24)
TACTILE_DARK = (188, 138, 16)
SOFFIT = (186, 178, 160)
SOFFIT_LINE = (160, 152, 136)
BLACK = (18, 19, 21)
WHITE = (240, 240, 238)
LAMP_GLOW = (255, 236, 190)


# ---------------------------------------------------------------- textures --
def tex_house(base, lit, dark, shadow):
    """Board-and-batten siding, 32px: 8-px boards with a proud batten strip —
    vertical boards, per-column only, stack-safe."""
    rows = pk.canvas(32, 32, base)
    for x in range(32):
        m = x % 8
        tone = base
        if m in (0, 1):
            tone = lit                     # the batten catches light
        elif m == 2:
            tone = shadow                  # shadow beside the batten
        elif m == 5:
            tone = dark
        for y in range(32):
            rows[y][x] = tone
    return rows


def tex_glazing():
    """Wired glass you can actually see through: transparent field, thin
    diamond wire grid, sparse glare pixels. CUTOUT layer — visible pixels
    are opaque, everything else is empty (the vanilla-glass approach)."""
    rows = pk.canvas(16, 16, (0, 0, 0, 0))
    WIRE = (88, 96, 100, 255)
    for y in range(16):
        for x in range(16):
            if (x + y) % 8 == 0 or (x - y) % 8 == 0:
                rows[y][x] = WIRE
    for i in range(4):
        rows[2 + i][11 + i if 11 + i < 16 else 15] = (222, 230, 234, 255)
    rows[12][3] = (222, 230, 234, 255)
    rows[13][4] = (222, 230, 234, 255)
    return rows


def tex_planks():
    """Weathered platform planking, per-column boards + gaps."""
    rows = pk.canvas(16, 16, PLANK)
    tones = [PLANK, PLANK_LIT, PLANK, PLANK_DARK, PLANK, PLANK_LIT, PLANK_DARK]
    for x in range(16):
        t = tones[(x * 3) % len(tones)]
        if x % 4 == 3:
            t = PLANK_GAP
        for y in range(16):
            rows[y][x] = t
    return rows


def tex_concrete_edge():
    """Platform edge top, 16px: concrete with the yellow tactile strip in
    rows 0..5 (the track side edge)."""
    rows = pk.canvas(16, 16, CONCRETE)
    for x in range(16):
        t = (CONCRETE, CONCRETE_LIT, CONCRETE, CONCRETE_DARK)[(x * 5) % 4]
        for y in range(16):
            rows[y][x] = t
    pk.rect(rows, 0, 0, 16, 5, TACTILE)
    for x in range(0, 16, 2):
        pk.rect(rows, x, 1, x + 1, 4, TACTILE_DARK)   # truncated domes read
    pk.rect(rows, 0, 5, 16, 6, CONCRETE_DARK)
    return rows


def tex_soffit():
    """Beadboard mezzanine ceiling: fine per-column lines."""
    rows = pk.canvas(16, 16, SOFFIT)
    for x in range(16):
        t = SOFFIT_LINE if x % 4 == 0 else (SOFFIT if x % 4 != 2 else (196, 188, 170))
        for y in range(16):
            rows[y][x] = t
    return rows


def tex_exit():
    """EXIT sign sheet, 32px, four 8-row zones: plain / right / left / down
    arrows. White on black."""
    rows = pk.canvas(32, 32, BLACK)
    for zone, arrow in ((0, None), (8, "right"), (16, "left"), (24, "down")):
        pk.rect(rows, 0, zone, 32, zone + 1, (34, 35, 38))
        pk.rect(rows, 0, zone + 7, 32, zone + 8, (10, 10, 12))
        label_w = pk.text_width("EXIT")
        total = label_w + (5 if arrow else 0)
        x0 = (32 - total) // 2
        if arrow == "left":
            pk.text(rows, x0 + 5, zone + 1, "EXIT", WHITE)
            ax = x0
            pk.rect(rows, ax, zone + 3, ax + 4, zone + 4, WHITE)
            rows[zone + 2][ax + 1] = WHITE
            rows[zone + 4][ax + 1] = WHITE
        else:
            pk.text(rows, x0, zone + 1, "EXIT", WHITE)
            if arrow == "right":
                ax = x0 + label_w + 1
                pk.rect(rows, ax, zone + 3, ax + 4, zone + 4, WHITE)
                rows[zone + 2][ax + 2] = WHITE
                rows[zone + 4][ax + 2] = WHITE
            elif arrow == "down":
                ax = x0 + label_w + 1
                pk.rect(rows, ax + 1, zone + 1, ax + 2, zone + 5, WHITE)
                rows[zone + 4][ax] = WHITE
                rows[zone + 4][ax + 3] = WHITE
                rows[zone + 5][ax + 1] = WHITE
                rows[zone + 5][ax + 2] = WHITE
    return rows


TEX = {
    "house": f"{MOD}:block/el_house_green",
    "housec": f"{MOD}:block/el_house_cream",
    "glaze": f"{MOD}:block/el_glazing",
    "planks": f"{MOD}:block/el_planks",
    "edge": f"{MOD}:block/el_platform_edge",
    "soffit": f"{MOD}:block/el_soffit",
    "exit": f"{MOD}:block/el_exit_sign",
    "body": f"{MOD}:block/el_steel_green",
    "galv": f"{MOD}:block/el_steel_silver",
    "lattice": f"{MOD}:block/el_lattice_green",
    "roof": f"{MOD}:block/el_roof_red",
    "screen": f"{MOD}:block/el_windscreen",
    "corru": f"{MOD}:block/el_corrugated",
    "board": f"{MOD}:block/el_board",
    "particle": f"{MOD}:block/el_steel_green",
}

SLOPE = {"origin": [8, 8, 8], "axis": "x", "angle": 45, "rescale": True}


# ------------------------------------------------------------------ models --
def stair_side():
    """45° side screen beside a stair run (chains one block up per block
    forward, the handrail-slope arithmetic): kick, corrugated panel, top
    rail — all rotated 45 rescale about (8,8,8) so centre y = 16 − z."""
    panel = f("corru", [0, 0.25, 16, 12])
    rail = f("body", [0.25, 4.75, 15.75, 6])
    kick = f("body", [0.25, 5, 15.75, 6.4])
    return [
        elem([6.9, 2.4, 0], [9.1, 13.6, 16], {"east": panel, "west": panel},
             rotation=dict(SLOPE)),
        elem([6.7, 13.2, 0], [9.3, 15.2, 16],
             {"east": rail, "west": rail, "up": rail, "down": rail},
             rotation=dict(SLOPE)),
        elem([6.7, 0.6, 0], [9.3, 2.8, 16],
             {"east": kick, "west": kick, "down": kick}, rotation=dict(SLOPE)),
    ]


def stair_canopy():
    """45° red roof descending with the stairs, full block width; chains like
    the side screen (centre y = 16 − z, so a run follows any flight).

    Detailed to match the platform canopies: the same 1.6 px standing-seam
    sheet, an eave board along each side edge (hanging BELOW the sheet, not
    standing proud of it — the flat canopy's fascia lesson), and crosswise
    rafters on a pitch of 8 so the underside rhythm is identical inside a
    block and across a joint. Every member is rotated with the sheet, and
    the run-continuation faces (z ends) are omitted so a flight is one
    unbroken plane."""
    top = f("roof", [0, 0.25, 16, 15.75])
    under = f("corru", [0, 0.25, 16, 15.75])
    els = [elem([0, 6.9, 0], [16, 9.1, 16], {
        "up": top, "down": under,
        "east": f("roof", [0, 8.5, 16, 9.4]),
        "west": f("roof", [0, 8.5, 16, 9.4]),
    }, rotation=dict(SLOPE))]
    edge = f("roof", [0, 8.5, 16, 9.4])
    for x0 in (0.05, 14.35):
        els.append(elem([x0, 5.2, 0], [x0 + 1.6, 6.85, 16],
                        {"east": edge, "west": edge, "down": edge, "up": edge},
                        rotation=dict(SLOPE)))
    rafter = f("body", [0.25, 5, 15.75, 6.2])
    for z0 in (3.2, 11.2):
        els.append(elem([1.7, 5.5, z0], [14.3, 6.85, z0 + 1.6],
                        {"north": rafter, "south": rafter, "down": rafter},
                        rotation=dict(SLOPE)))
    return els


def portal_post_shaft():
    face = f("body", [12.4, 0.25, 15.4, 15.75])
    return [elem([6.6, 0, 6.6], [9.4, 16, 9.4],
                 {n: face for n in ("north", "south", "east", "west")})]


def portal_post_bracket():
    """Filigree scroll corner at the top of a portal post: lattice quarter
    panel + arm reaching along the header direction (FACING)."""
    lat = f("lattice", [0, 0, 6, 6])
    arm = f("body", [1, 4.75, 2.4, 10])
    return [
        elem([7.6, 9.6, 9.4], [8.4, 15.4, 15.2], {"east": lat, "west": lat}),
        elem([6.9, 14.0, 6.9], [9.1, 15.8, 15.9],
             {n: arm for n in ("north", "south", "east", "west", "up", "down")}),
    ]


def portal_header():
    """The ornamental frieze beam spanning the portal: top beam, lattice
    band, bottom rail — a merging run along x."""
    beam = f("body", [0.25, 5, 15.75, 7.8])
    latt = f("lattice", [0, 3, 16, 9])
    rail = f("body", [0.25, 5, 15.75, 6.2])
    return [
        elem([0, 12.8, 6.8], [16, 15.6, 9.2],
             {"north": beam, "south": beam, "up": beam, "down": beam}),
        elem([0, 6.8, 7.6], [16, 12.8, 8.4], {"north": latt, "south": latt}),
        elem([0, 5.4, 7.0], [16, 6.8, 9.0],
             {"north": rail, "south": rail, "up": rail, "down": rail}),
    ]


def full_cube(ref):
    face = f(ref, [0, 0, 16, 16])
    return [elem([0, 0, 0], [16, 16, 16], {
        "north": dict(face, cullface="north"), "south": dict(face, cullface="south"),
        "east": dict(face, cullface="east"), "west": dict(face, cullface="west"),
        "up": dict(face, cullface="up"), "down": dict(face, cullface="down"),
    })]


def house_wall(ref):
    return full_cube(ref)


def window_wall(ref):
    """A REAL window: sill, lintel and jambs of the wall material framing an
    opening, with a see-through wired-glass pane (cutout) in the middle.
    Both faces identical; outer faces cull against full neighbours."""
    def w(uv, cull=None):
        face = f(ref, uv)
        if cull:
            face["cullface"] = cull
        return face
    els = [
        elem([0, 0, 0], [16, 3, 16], {                     # sill
            "north": w([0, 13, 16, 16], "north"), "south": w([0, 13, 16, 16], "south"),
            "east": w([0, 13, 16, 16], "east"), "west": w([0, 13, 16, 16], "west"),
            "up": w([0, 0, 16, 16]), "down": w([0, 0, 16, 16], "down"),
        }),
        elem([0, 13, 0], [16, 16, 16], {                   # lintel
            "north": w([0, 0, 16, 3], "north"), "south": w([0, 0, 16, 3], "south"),
            "east": w([0, 0, 16, 3], "east"), "west": w([0, 0, 16, 3], "west"),
            "up": w([0, 0, 16, 16], "up"), "down": w([0, 0, 16, 16]),
        }),
        elem([0, 3, 0], [2, 13, 16], {                     # west jamb
            "north": w([0, 3, 2, 13], "north"), "south": w([14, 3, 16, 13], "south"),
            "west": w([0, 3, 16, 13], "west"), "east": w([0, 3, 16, 13]),
        }),
        elem([14, 3, 0], [16, 13, 16], {                   # east jamb
            "north": w([14, 3, 16, 13], "north"), "south": w([0, 3, 2, 13], "south"),
            "east": w([0, 3, 16, 13], "east"), "west": w([0, 3, 16, 13]),
        }),
        # the glass: centre pane, ends buried inside the frame (no up/down/
        # east/west faces — they would be coplanar with the reveals)
        elem([2, 3, 7.25], [14, 13, 8.75], {
            "north": f("glaze", [2, 3, 14, 13]),
            "south": f("glaze", [2, 3, 14, 13]),
        }),
    ]
    return els


def platform_edge():
    """Full cube: concrete top with the yellow tactile strip along the NORTH
    (track) edge, riveted girder fascia on the north face."""
    top = f("edge", [0, 0, 16, 16])
    fascia = f("body", [0.25, 0.25, 15.75, 15.75])
    side = f("edge", [0, 6, 16, 16])
    return [elem([0, 0, 0], [16, 16, 16], {
        "up": top,
        "north": dict(fascia, cullface="north"),
        "south": dict(side, cullface="south"),
        "east": dict(side, cullface="east"), "west": dict(side, cullface="west"),
        "down": f("body", [0.25, 5, 15.75, 8], cull="down"),
    })]


def soffit():
    """Ceiling slab at the top of the block."""
    face = f("soffit", [0, 0, 16, 16])
    edge = f("soffit", [0, 6.5, 16, 8])
    return [elem([0, 13, 0], [16, 16, 16], {
        "down": face, "up": dict(face, cullface="up"),
        "north": edge, "south": edge, "east": edge, "west": edge,
    })]


def exit_sign():
    """Hanging EXIT sign: plate on a stub, plain zone (arrow variants are
    uv-zone swaps in the real block)."""
    plate = f("exit", [0, 0.25, 16, 3.75])
    edge = f("exit", [0, 0.5, 1, 3.5])
    return [
        elem([7.3, 12.5, 7.6], [8.7, 16, 8.4],
             {n: f("body", [13.6, 2, 14.4, 5]) for n in ("north", "south", "east", "west")}),
        elem([2, 5.5, 7.5], [14, 12.5, 8.5], {
            "north": plate, "south": plate,
            "east": edge, "west": edge, "up": edge, "down": edge,
        }),
    ]


def lamp_gooseneck():
    """Ceiling/fascia-mounted goose-neck: stem, arm, drop, cone shade, bulb."""
    steel = f("body", [13.6, 2, 14.4, 5])
    def box(a, b):
        return elem(a, b, {n: steel for n in ("north", "south", "east", "west", "up", "down")})
    els = [box([6.9, 14.6, 6.9], [9.1, 16, 9.1]),
           box([7.2, 10.8, 7.2], [8.8, 14.6, 8.8]),
           box([7.2, 9.6, 7.2], [8.8, 11.0, 13.6]),
           box([7.2, 8.0, 12.2], [8.8, 9.8, 13.8])]
    shade = f("body", [1, 4.75, 4, 6.2])
    els.append(elem([5.9, 6.8, 10.9], [10.1, 8.2, 15.1],
                    {n: shade for n in ("north", "south", "east", "west", "up")}))
    glow = {"texture": "#exit", "uv": [13, 4.4, 15, 5.4], "shade": False}
    bulb = {n: dict(glow) for n in ("north", "south", "east", "west", "down")}
    els.append(elem([6.7, 6.2, 11.7], [9.3, 7.0, 14.3], bulb, shade=False))
    return els


def lamp_post_pole():
    steel = f("body", [13.6, 0.25, 14.4, 15.75])
    return [elem([6.9, 0, 6.9], [9.1, 16, 9.1],
                 {n: steel for n in ("north", "south", "east", "west")}),
            elem([5.6, 0, 5.6], [10.4, 1.4, 10.4],
                 {n: f("body", [1, 5, 5.8, 6.4])
                  for n in ("north", "south", "east", "west", "up")})]


def lamp_post_head():
    steel = f("body", [13.6, 2, 14.4, 8])
    els = [elem([6.9, 0, 6.9], [9.1, 8.6, 9.1],
                {n: steel for n in ("north", "south", "east", "west")})]
    arm = f("body", [1, 4.75, 2.6, 9.5])
    els.append(elem([7.2, 7.2, 8.6], [8.8, 8.8, 14.6],
                    {n: arm for n in ("north", "south", "east", "west", "up", "down")}))
    shade = f("body", [1, 4.75, 4.4, 6.4])
    els.append(elem([5.8, 6.4, 11.2], [10.2, 7.9, 15.4],
                    {n: shade for n in ("north", "south", "east", "west", "up")}))
    glow = {"texture": "#exit", "uv": [13, 4.4, 15, 5.4], "shade": False}
    els.append(elem([6.5, 5.8, 11.9], [9.5, 6.6, 14.7],
                    {n: dict(glow) for n in ("north", "south", "east", "west", "down")},
                    shade=False))
    return els


def gable_truss_end():
    """The open truss triangle for gable run ends (replaces the stepped
    plate): bottom chord, two 22.5° sloped chords, lattice web."""
    els = []
    chord = f("body", [0.25, 5, 15.75, 6.2])
    els.append(elem([0.2, 0.2, 0.8], [1.4, 1.5, 15.2],
                    {n: chord for n in ("north", "south", "east", "west", "up", "down")}))
    for z0, angle, oz in ((0.8, -22.5, 1.6), (8.0, 22.5, 14.4)):
        els.append(elem([0.2, 1.3, z0], [1.4, 2.6, z0 + 7.2],
                        {n: chord for n in ("north", "south", "east", "west", "up", "down")},
                        rotation={"origin": [0.8, 1.9, oz], "axis": "x", "angle": angle}))
    lat = f("lattice", [2, 4, 14, 8])
    els.append(elem([0.5, 1.4, 3.4], [1.1, 3.2, 12.6], {"east": lat, "west": lat}))
    return els


# ----------------------------------------------------------------- final ----
ROTS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def facing_variants(mdl):
    return {"variants": {f"facing={facing}": ({"model": f"{MOD}:block/{mdl}", "y": rot}
                                              if rot else {"model": f"{MOD}:block/{mdl}"})
                         for facing, rot in ROTS}}


def exit_sign_models(g):
    """Eight models: (ceiling|wall) x four arrow zones. The plate's back face
    flips u so the lettering reads correctly from both sides."""
    zones = {"none": 0, "right": 4, "left": 8, "down": 12}
    for arrow, v0 in zones.items():
        front = f("exit", [0, v0 + 0.25, 16, v0 + 3.75])
        back = f("exit", [16, v0 + 0.25, 0, v0 + 3.75])
        edge = f("exit", [0, v0 + 0.5, 1, v0 + 3.5])
        stub = {n: f("body", [13.6, 2, 14.4, 5])
                for n in ("north", "south", "east", "west")}
        g.model(f"el_exit_sign_hang_{arrow}", TEX, [
            elem([7.3, 12.5, 7.6], [8.7, 16, 8.4], dict(stub)),
            elem([2, 5.5, 7.5], [14, 12.5, 8.5], {
                "north": front, "south": back,
                "east": edge, "west": edge, "up": edge, "down": edge,
            }),
        ])
        g.model(f"el_exit_sign_wall_{arrow}", TEX, [
            elem([2, 4.5, 14.4], [14, 12, 15.4], {
                "north": front,
                "east": edge, "west": edge, "up": edge, "down": edge,
            }),
        ])


BLOCKS3 = ["el_stair_side", "el_stair_canopy", "el_portal_post", "el_portal_header",
           "el_house_wall_green", "el_house_wall_green_window",
           "el_house_wall_cream", "el_house_wall_cream_window",
           "el_wood_platform", "el_platform_edge", "el_soffit",
           "el_exit_sign", "el_lamp_gooseneck", "el_lamp_post", "el_lamp_head"]

FACINGS4 = {"north", "south", "east", "west"}
PROPS3 = {
    "el_stair_side": {"facing": FACINGS4}, "el_stair_canopy": {"facing": FACINGS4},
    "el_portal_post": {"facing": FACINGS4, "up": {"true", "false"}, "down": {"true", "false"}},
    "el_portal_header": {"facing": FACINGS4},
    "el_house_wall_green": {}, "el_house_wall_green_window": {},
    "el_house_wall_cream": {}, "el_house_wall_cream_window": {},
    "el_wood_platform": {}, "el_soffit": {},
    "el_platform_edge": {"facing": FACINGS4},
    "el_exit_sign": {"facing": FACINGS4, "mount": {"ceiling", "wall"},
                     "arrow": {"none", "right", "left", "down"}},
    "el_lamp_gooseneck": {"facing": FACINGS4},
    "el_lamp_post": {"facing": FACINGS4}, "el_lamp_head": {"facing": FACINGS4},
}

RECIPES3 = {
    "el_stair_side": {"type": "minecraft:crafting_shapeless", "category": "building",
                      "ingredients": [{"item": f"{MOD}:el_windscreen_corrugated"},
                                      {"item": "minecraft:iron_ingot"}],
                      "result": {"item": f"{MOD}:el_stair_side", "count": 2}},
    "el_stair_canopy": {"type": "minecraft:crafting_shapeless", "category": "building",
                        "ingredients": [{"item": f"{MOD}:el_canopy_flat"},
                                        {"item": "minecraft:red_dye"}],
                        "result": {"item": f"{MOD}:el_stair_canopy", "count": 2}},
    "el_portal_post": {"type": "minecraft:crafting_shapeless", "category": "building",
                       "ingredients": [{"item": f"{MOD}:el_canopy_post"},
                                       {"item": "minecraft:iron_bars"}],
                       "result": {"item": f"{MOD}:el_portal_post"}},
    "el_portal_header": {"type": "minecraft:crafting_shapeless", "category": "building",
                         "ingredients": [{"item": f"{MOD}:el_girder"},
                                         {"item": "minecraft:iron_bars"}],
                         "result": {"item": f"{MOD}:el_portal_header", "count": 2}},
    "el_house_wall_green": {"type": "minecraft:crafting_shapeless", "category": "building",
                            "ingredients": [{"item": "minecraft:oak_planks"}] * 4
                                           + [{"item": "minecraft:green_dye"}],
                            "result": {"item": f"{MOD}:el_house_wall_green", "count": 4}},
    "el_house_wall_cream": {"type": "minecraft:crafting_shapeless", "category": "building",
                            "ingredients": [{"item": "minecraft:oak_planks"}] * 4
                                           + [{"item": "minecraft:white_dye"}],
                            "result": {"item": f"{MOD}:el_house_wall_cream", "count": 4}},
    "el_house_wall_green_window": {"type": "minecraft:crafting_shapeless", "category": "building",
                                   "ingredients": [{"item": f"{MOD}:el_house_wall_green"},
                                                   {"item": "minecraft:glass_pane"}],
                                   "result": {"item": f"{MOD}:el_house_wall_green_window"}},
    "el_house_wall_cream_window": {"type": "minecraft:crafting_shapeless", "category": "building",
                                   "ingredients": [{"item": f"{MOD}:el_house_wall_cream"},
                                                   {"item": "minecraft:glass_pane"}],
                                   "result": {"item": f"{MOD}:el_house_wall_cream_window"}},
    "el_wood_platform": {"type": "minecraft:crafting_shapeless", "category": "building",
                         "ingredients": [{"item": "minecraft:spruce_planks"}] * 3
                                        + [{"item": "minecraft:iron_nugget"}],
                         "result": {"item": f"{MOD}:el_wood_platform", "count": 4}},
    "el_platform_edge": {"type": "minecraft:crafting_shapeless", "category": "building",
                         "ingredients": [{"item": "minecraft:stone"},
                                         {"item": "minecraft:yellow_dye"},
                                         {"item": "minecraft:iron_ingot"}],
                         "result": {"item": f"{MOD}:el_platform_edge", "count": 2}},
    "el_soffit": {"type": "minecraft:crafting_shapeless", "category": "building",
                  "ingredients": [{"item": "minecraft:oak_planks"},
                                  {"item": "minecraft:oak_planks"},
                                  {"item": "minecraft:white_dye"}],
                  "result": {"item": f"{MOD}:el_soffit", "count": 4}},
    "el_exit_sign": {"type": "minecraft:crafting_shapeless", "category": "building",
                     "ingredients": [{"item": "minecraft:iron_ingot"},
                                     {"item": "minecraft:black_dye"}],
                     "result": {"item": f"{MOD}:el_exit_sign", "count": 2}},
    "el_lamp_gooseneck": {"type": "minecraft:crafting_shapeless", "category": "building",
                          "ingredients": [{"item": "minecraft:iron_ingot"},
                                          {"item": "minecraft:iron_nugget"},
                                          {"item": "minecraft:glowstone_dust"}],
                          "result": {"item": f"{MOD}:el_lamp_gooseneck", "count": 2}},
    "el_lamp_post": {"type": "minecraft:crafting_shaped", "category": "building",
                     "key": {"N": {"item": "minecraft:iron_nugget"},
                             "I": {"item": "minecraft:iron_ingot"}},
                     "pattern": ["N", "I", "I"],
                     "result": {"item": f"{MOD}:el_lamp_post", "count": 2}},
    "el_lamp_head": {"type": "minecraft:crafting_shapeless", "category": "building",
                     "ingredients": [{"item": "minecraft:iron_ingot"},
                                     {"item": "minecraft:glowstone_dust"},
                                     {"item": "minecraft:green_dye"}],
                     "result": {"item": f"{MOD}:el_lamp_head"}},
}


def write_textures(texdir):
    green_wall = tex_house(HOUSE_GREEN, HOUSE_GREEN_LIT, HOUSE_GREEN_DARK, HOUSE_GREEN_SHADOW)
    cream_wall = tex_house(HOUSE_CREAM, HOUSE_CREAM_LIT, HOUSE_CREAM_DARK, HOUSE_CREAM_SHADOW)
    wpng(os.path.join(texdir, "el_house_green.png"), green_wall)
    wpng(os.path.join(texdir, "el_house_cream.png"), cream_wall)
    wpng(os.path.join(texdir, "el_glazing.png"), tex_glazing())
    wpng(os.path.join(texdir, "el_planks.png"), tex_planks())
    wpng(os.path.join(texdir, "el_platform_edge.png"), tex_concrete_edge())
    wpng(os.path.join(texdir, "el_soffit.png"), tex_soffit())
    wpng(os.path.join(texdir, "el_exit_sign.png"), tex_exit())


def write_models(g):
    for name, els in (
            ("el_stair_side_model", stair_side()),
            ("el_stair_canopy_model", stair_canopy()),
            ("el_portal_post_shaft", portal_post_shaft()),
            ("el_portal_post_bracket", portal_post_bracket()),
            ("el_portal_header_model", portal_header()),
            ("el_house_wall_green", house_wall("house")),
            ("el_house_wall_green_window", window_wall("house")),
            ("el_house_wall_cream", house_wall("housec")),
            ("el_house_wall_cream_window", window_wall("housec")),
            ("el_wood_platform_model", full_cube("planks")),
            ("el_platform_edge_model", platform_edge()),
            ("el_soffit_model", soffit()),
            ("el_lamp_gooseneck_model", lamp_gooseneck()),
            ("el_lamp_post_pole", lamp_post_pole()),
            ("el_lamp_post_head", lamp_post_head()),
            ("el_portal_post_item", portal_post_shaft() + portal_post_bracket()),
            ("el_lamp_post_item", lamp_post_pole())):
        g.model(name, TEX, els)
    exit_sign_models(g)


def build_final(g, assets_root, data_root, loot):
    write_textures(os.path.join(assets_root, "textures/block"))
    write_models(g)

    # blockstates
    for block, mdl in (("el_stair_side", "el_stair_side_model"),
                       ("el_stair_canopy", "el_stair_canopy_model"),
                       ("el_portal_header", "el_portal_header_model"),
                       ("el_platform_edge", "el_platform_edge_model"),
                       ("el_lamp_gooseneck", "el_lamp_gooseneck_model"),
                       ("el_lamp_post", "el_lamp_post_pole"),
                       ("el_lamp_head", "el_lamp_post_head")):
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"), facing_variants(mdl))
    for block, mdl in (("el_house_wall_green", "el_house_wall_green"),
                       ("el_house_wall_green_window", "el_house_wall_green_window"),
                       ("el_house_wall_cream", "el_house_wall_cream"),
                       ("el_house_wall_cream_window", "el_house_wall_cream_window"),
                       ("el_wood_platform", "el_wood_platform_model"),
                       ("el_soffit", "el_soffit_model")):
        g.wj(os.path.join(assets_root, "blockstates", block + ".json"),
             {"variants": {"": {"model": f"{MOD}:block/{mdl}"}}})
    parts = [{"apply": {"model": f"{MOD}:block/el_portal_post_shaft"}}]
    for facing, rot in ROTS:
        entry = {"model": f"{MOD}:block/el_portal_post_bracket"}
        if rot:
            entry["y"] = rot
        parts.append({"when": {"facing": facing, "up": "false"}, "apply": entry})
    g.wj(os.path.join(assets_root, "blockstates", "el_portal_post.json"), {"multipart": parts})
    exit_bs = {}
    for facing, rot in ROTS:
        for mount in ("ceiling", "wall"):
            for arrow in ("none", "right", "left", "down"):
                kind = "hang" if mount == "ceiling" else "wall"
                entry = {"model": f"{MOD}:block/el_exit_sign_{kind}_{arrow}"}
                if rot:
                    entry["y"] = rot
                exit_bs[f"facing={facing},mount={mount},arrow={arrow}"] = entry
    g.wj(os.path.join(assets_root, "blockstates", "el_exit_sign.json"), {"variants": exit_bs})

    # item models
    items = {"el_stair_side": "el_stair_side_model", "el_stair_canopy": "el_stair_canopy_model",
             "el_portal_post": "el_portal_post_item", "el_portal_header": "el_portal_header_model",
             "el_house_wall_green": "el_house_wall_green",
             "el_house_wall_green_window": "el_house_wall_green_window",
             "el_house_wall_cream": "el_house_wall_cream",
             "el_house_wall_cream_window": "el_house_wall_cream_window",
             "el_wood_platform": "el_wood_platform_model",
             "el_platform_edge": "el_platform_edge_model", "el_soffit": "el_soffit_model",
             "el_exit_sign": "el_exit_sign_hang_none",
             "el_lamp_gooseneck": "el_lamp_gooseneck_model",
             "el_lamp_post": "el_lamp_post_item", "el_lamp_head": "el_lamp_post_head"}
    for block, mdl in items.items():
        g.wj(os.path.join(assets_root, "models/item", block + ".json"),
             {"parent": f"{MOD}:block/{mdl}"})

    for block in BLOCKS3:
        g.wj(os.path.join(data_root, MOD, "loot_tables/blocks", block + ".json"), loot(block))
    for name, recipe in RECIPES3.items():
        g.wj(os.path.join(data_root, MOD, "recipes", name + ".json"), recipe)


# ---------------------------------------------------------------- preview ---
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    assets = os.path.join(args.out, "assets", MOD)
    g = Gen(assets, os.path.join(args.out, "data"))
    os.makedirs(os.path.join(assets, "textures/block"), exist_ok=True)
    write_textures(os.path.join(assets, "textures/block"))
    write_models(g)
    print("phase-3 preview tree written")


if __name__ == "__main__":
    main()
