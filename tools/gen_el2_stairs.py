#!/usr/bin/env python3
"""El kit v2 - STAIR FAMILY (fare control / stairs section). Called from
gen_el2_assets.main(); never hand-edit the output.

The treads are the existing `subway_stairs` (two 8 px steps per block,
FACING = ascent). Beside each stair block, in the cell to its left or right,
these blocks add the sloped side enclosure; above the top course sits the
sloped roof.

  el_stair_railing    stringer + kick + pickets + handrail (bottom course)
  el_stair_wall       cream board course (stacks above any course)
  el_stair_wall_glass wired-glass course
  el_stair_roof       red standing-seam roof descending with the flight

THE SLOPE FRAME (the old kit's derivation, re-verified against subway_stairs):
a rescaled +45 rotation about x through (8,8,8) maps an authored point
(v, z_a) to  world y = v - z_a + 8,  world z = v + z_a - 8.  Consequences:
  * a member authored over z_a 0..16 tiles a flight of any length (the cell
    one up + one toward the ascent continues it exactly);
  * at a fixed WORLD z the height of a member at "v" is  y = 2v - z, i.e. one
    authored unit is two pixels of vertical height, and a member at v sits
    over world z in [v - 8, v + 8]: high members are shifted DOWNHILL by
    v - 8 px (the chain hides it, run ends must handle it - see cuts below);
  * the stair's nosing line is v = 12 (y = 24 - z), its soffit line v = 8.
Courses are 8 units tall: v 12..20, so a course's top rail (v 20) is the
next course's v 12. The rail sits 16 px above the nosings (1 m); the level-
off at the top of a flight runs 16 px over the floor (authored y tops out
at 32, the JSON limit - a 20 px rail like the platform's does not fit).

Cuts: `up=contain` keeps a member inside world z >= 0 (run stops uphill),
`up=cover` extends it uphill so its TOP edge reaches world z = 8 (the level-
off cell), `down=contain` keeps it inside world z <= 16 (foot of the
flight), `down=overhang` inside z <= 20 (roof foot). Cut faces are 45 deg
and land inside the vertical posts / level boxes, never coplanar.

Vertical members (posts, pickets, mullions) are world-frame boxes. Because
a member at v sits downhill by v - 8, the pickets a cell draws are the ones
over the DOWNHILL cell's floor (world z 16..32 of this cell) - that keeps
their authored y inside -16..32. The level-off cell draws its own.
"""

import math
import os
import random

import gen_el2_assets as K

MOD = K.MOD
SLOPE = {"origin": [8, 8, 8], "axis": "x", "angle": 45, "rescale": True}

# course band (v)
V0, V1 = 12.0, 20.0
RAIL_T = 1.4
PANEL = (V0 + RAIL_T, V1 - RAIL_T)           # 13.4 .. 18.6
STRINGER = (4.4, 8.4)
KICK = (8.4, V0)
PLANE = (0.0, 2.4)                           # panel plane x range (stair to the west)
# roof (v)
FRIEZE = (12.0, 18.0)
DECK = (18.0, 19.6)
# landing roof (world frame): frieze y 8..20, deck 20..23.2 = the stair roof's edge heights
LAND_FRIEZE = (8.0, 20.0)
LAND_DECK = (20.0, 23.2)
G = "#green"


def world_y(v, z):
    """Vertical height of slope-frame height v at world z (relative to the cell)."""
    return 2 * v - z


def sbox(x0, v0, z0, x1, v1, z1, tex, faces=None, uv=None, rot=None, shade_=None):
    """Slope-frame box. Default uv: face-sized windows, clamped to the sprite."""
    if uv is None:
        w = min(16.0, z1 - z0)
        h = min(16.0, v1 - v0)
        xw = min(16.0, x1 - x0)
        uv = {"east": [0, 0, w, h], "west": [0, 0, w, h],
              "north": [0, 0, xw, h], "south": [0, 0, xw, h],
              "up": [0, 0, xw, w], "down": [0, 0, xw, w]}
    el = K.box(x0, v0, z0, x1, v1, z1, tex, uv=uv, faces=faces, rotation=dict(SLOPE), shade_=shade_)
    if rot:
        for f, r in rot.items():
            if f in el["faces"]:
                el["faces"][f]["rotation"] = r
    return el


def member(x0, x1, v0, v1, tex, faces=None, up=None, down=None, uv=None, rot=None, shade_=None):
    """A chaining slope member over z_a 0..16 with optional end cuts. Returns
    1..2 boxes (an uphill extension is a separate box so uvs stay 1:1)."""
    z0, z1 = 0.0, 16.0
    if up == "contain":
        z0 = max(0.0, 8 - v0)              # every point at world z >= 0
    if down == "contain":
        z1 = min(16.0, 24 - v1)            # every point at world z <= 16
    elif down == "overhang":
        z1 = min(16.0, 28 - v1)            # world z <= 20
    out = []
    if z1 > z0 + 0.05:
        out.append(sbox(x0, v0, z0, x1, v1, z1, tex, faces=faces, uv=uv, rot=rot, shade_=shade_))
    if up in ("cover", "cover0"):
        ext0 = (16 if up == "cover" else 8) - v1   # top edge reaches world z = 8 / z = 0
        if ext0 < 0:
            out.append(sbox(x0, v0, ext0, x1, v1, 0.0, tex, faces=faces, uv=uv, rot=rot, shade_=shade_))
    return out


def wbox(x0, y0, z0, x1, y1, z1, tex, faces=None, uv=None, shade_=None):
    """World-frame (unrotated) box with face-sized uvs."""
    if uv is None:
        uv = {"north": [0, 0, min(16, x1 - x0), min(16, y1 - y0)],
              "south": [0, 0, min(16, x1 - x0), min(16, y1 - y0)],
              "east": [0, 0, min(16, z1 - z0), min(16, y1 - y0)],
              "west": [0, 0, min(16, z1 - z0), min(16, y1 - y0)],
              "up": [0, 0, min(16, x1 - x0), min(16, z1 - z0)],
              "down": [0, 0, min(16, x1 - x0), min(16, z1 - z0)]}
    return K.box(x0, y0, z0, x1, y1, z1, tex, uv=uv, faces=faces, shade_=shade_)


# ---------------------------------------------------------------------------
# side courses
# ---------------------------------------------------------------------------

def stringer_kick(up, down):
    """Bottom-course extras: the stringer beam under the steps and the solid
    kick plate covering the step ends up to the course's bottom rail."""
    els = []
    els += member(0.2, 2.2, STRINGER[0], STRINGER[1], G, up=up, down=down,
                  uv={"east": [0, 4, 16, 8], "west": [0, 4, 16, 8], "up": [0, 0, 2, 16],
                      "down": [0, 0, 2, 16], "north": [0, 4, 2, 8], "south": [0, 4, 2, 8]})
    els += member(0.6, 1.8, KICK[0], KICK[1], G, up=up, down=down,
                  faces=["east", "west", "north", "south"],
                  uv={"east": [0, 8, 16, 13.6], "west": [0, 8, 16, 13.6],
                      "north": [0, 8, 1.2, 13.6], "south": [0, 8, 1.2, 13.6]})
    return els


def rails(up, down):
    """Bottom and top rails of a course band."""
    els = []
    for v0, v1 in ((V0, V0 + RAIL_T), (V1 - RAIL_T, V1)):
        els += member(0.2, 2.2, v0, v1, G, up=up, down=down,
                      uv={"east": [0, 4, 16, 5.4], "west": [0, 4, 16, 5.4], "up": [0, 0, 2, 16],
                          "down": [0, 0, 2, 16], "north": [0, 4, 2, 5.4], "south": [0, 4, 2, 5.4]})
    return els


def pickets(zs, top_v=PANEL[1], bot_v=PANEL[0]):
    """World-vertical pickets at the given world z centres, feet and heads
    buried 0.8 px inside the sloped rails."""
    els = []
    for zc in zs:
        yb = world_y(bot_v, zc) - 0.8
        yt = world_y(top_v, zc) + 0.8
        els.append(wbox(0.6, yb, zc - 0.6, 1.8, yt, zc + 0.6, G,
                        faces=["north", "south", "east", "west"],
                        uv={"north": [4, 0, 5.2, min(16, yt - yb)], "south": [4, 0, 5.2, min(16, yt - yb)],
                            "east": [4, 0, 5.2, min(16, yt - yb)], "west": [4, 0, 5.2, min(16, yt - yb)]}))
    return els


def mid_rail(up, down):
    return member(0.4, 2.0, 15.6, 16.4, G, up=up, down=down,
                  uv={"east": [0, 5, 16, 5.8], "west": [0, 5, 16, 5.8], "up": [0, 0, 1.6, 16],
                      "down": [0, 0, 1.6, 16], "north": [0, 5, 1.6, 5.8], "south": [0, 5, 1.6, 5.8]})


def panel(tex, up, down, glass=False):
    """Panel between the rails (cream boards run ALONG the slope - face
    rotation 90; a 45-degree tilt of vertical battens read wrong)."""
    els = member(0.6, 1.8, PANEL[0], PANEL[1], tex, up=up, down=down,
                 faces=["east", "west"],
                 uv={"east": [0, 0, 16, 5.2], "west": [16, 0, 0, 5.2]},
                 rot={"east": 90, "west": 90})
    if glass and down != "contain":
        # world-vertical mullion over the downhill cell (see module docstring);
        # none at the foot of the flight, where the downhill cell is the street
        for zc in (24.0,):
            yb = world_y(PANEL[0], zc) - 0.6
            yt = world_y(PANEL[1], zc) + 0.6
            els.append(wbox(0.4, yb, zc - 0.6, 2.0, yt, zc + 0.6, G,
                            faces=["north", "south", "east", "west"],
                            uv={"north": [0, 3.2, 1.6, 16], "south": [0, 3.2, 1.6, 16],
                                "east": [0, 3.2, 1.2, 16], "west": [0, 3.2, 1.2, 16]}))
    return els


def down_post(bottom, top=False):
    """The post at this cell's downhill edge (z 13.6..16) on the panel plane,
    from the stringer bottom (bottom course) or the chain point (the course
    below ends at 2*20 - 13.6 = 26.4 -> 10.4 here) up to the rail top; the
    TOP course (nothing of the family above) runs its post on up to y 32 so
    it meets the roof band above (Thomas: poles must reach the roof)."""
    y0 = world_y(STRINGER[0], 16) if bottom else world_y(V1, 13.6) - 16
    y1 = 32.0 if top else world_y(V1, 13.6)
    return [wbox(0, y0, 13.6, 2.4, y1, 16, G, faces=["north", "south", "east", "west", "up"],
                 uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16],
                     "east": [4, 0, 6.4, 16], "west": [4, 0, 6.4, 16], "up": [0, 13.6, 2.4, 16]})]


def level_section(kind):
    """Top-of-flight level-off over world z 0..8 (the last tread, flush with
    the floor at y 16): rails 2..3.2 and 14..16 above the floor, pickets /
    panel between, an end post at z 0..2.4 from the floor to the rail cap,
    plus this cell's own z 8..16 verticals (normally the uphill cell's job).
    Everything tops out at y 32, the authored-coordinate limit."""
    F = 16.0
    els = [wbox(0.2, F + 14, 0, 2.2, F + 16, 8.6, G, uv={"north": [0, 4, 2, 6], "south": [0, 4, 2, 6],
                                                          "east": [0, 4, 8.6, 6], "west": [0, 4, 8.6, 6],
                                                          "up": [0, 0, 2, 8.6], "down": [0, 0, 2, 8.6]}),
           wbox(0, F, 0, 2.4, F + 15, 2.4, G, faces=["north", "south", "east", "west"],
                uv={"north": [0, 0, 2.4, 15], "south": [0, 0, 2.4, 15], "east": [4, 0, 6.4, 15],
                    "west": [4, 0, 6.4, 15]}),
           wbox(-0.6, F + 15, -0.6, 3.0, F + 16, 3.0, G, uv={"north": [4, 8, 7.6, 9], "south": [4, 8, 7.6, 9],
                                                             "east": [4, 8, 7.6, 9], "west": [4, 8, 7.6, 9],
                                                             "up": [0, 12.4, 3.6, 16], "down": [0, 12.4, 3.6, 16]}),
           # newel post where the sloped rail meets the level rail (hides the 45-degree seam)
           wbox(0, F, 6.8, 2.4, F + 16, 9.2, G, faces=["north", "south", "east", "west", "up"],
                uv={"north": [0, 0, 2.4, 16], "south": [0, 0, 2.4, 16], "east": [4, 0, 6.4, 16],
                    "west": [4, 0, 6.4, 16], "up": [0, 6.8, 2.4, 9.2]})]
    if kind == "open":
        return els[1:2] + els[3:]          # end post + newel only
    if kind == "railing":
        els.append(wbox(0.2, F + 2, 0, 2.2, F + 3.2, 8.6, G, uv={"north": [0, 5, 2, 6.2], "south": [0, 5, 2, 6.2],
                                                                  "east": [0, 5, 8.6, 6.2], "west": [0, 5, 8.6, 6.2],
                                                                  "up": [0, 0, 2, 8.6], "down": [0, 0, 2, 8.6]}))
        els.append(wbox(0.4, F + 7.4, 0, 2.0, F + 8.2, 8.6, G, uv={"north": [0, 5, 1.6, 5.8], "south": [0, 5, 1.6, 5.8],
                                                                    "east": [0, 5, 8.6, 5.8], "west": [0, 5, 8.6, 5.8],
                                                                    "up": [0, 0, 1.6, 8.6], "down": [0, 0, 1.6, 8.6]}))
        for zc in (5.5,):
            els.append(wbox(0.6, F + 3.2, zc - 0.6, 1.8, F + 14, zc + 0.6, G,
                            faces=["north", "south", "east", "west"],
                            uv={"north": [4, 0, 5.2, 10.8], "south": [4, 0, 5.2, 10.8],
                                "east": [4, 0, 5.2, 10.8], "west": [4, 0, 5.2, 10.8]}))
        els += pickets((10.5,))
    else:
        tex = "#glass" if kind == "glass" else "#cream"
        els.append(wbox(0.2, F + 2, 0, 2.2, F + 3.4, 8.6, G, uv={"north": [0, 5, 2, 6.4], "south": [0, 5, 2, 6.4],
                                                                  "east": [0, 5, 8.6, 6.4], "west": [0, 5, 8.6, 6.4],
                                                                  "up": [0, 0, 2, 8.6], "down": [0, 0, 2, 8.6]}))
        els.append(wbox(0.6, F + 3.4, 0, 1.8, F + 14, 8.6, tex, faces=["east", "west"],
                        uv={"east": [0, 5.4, 8.6, 16], "west": [8.6, 5.4, 0, 16]}))
        if kind == "glass":
            els.append(wbox(0.4, F + 3.4, 4.2, 2.0, F + 14, 5.4, G, faces=["north", "south", "east", "west"],
                            uv={"north": [0, 3.2, 1.6, 16], "south": [0, 3.2, 1.6, 16],
                                "east": [0, 3.2, 1.2, 16], "west": [0, 3.2, 1.2, 16]}))
            zc = 11.0
            yb = world_y(PANEL[0], zc) - 0.6
            yt = world_y(PANEL[1], zc) + 0.6
            els.append(wbox(0.4, yb, zc - 0.6, 2.0, yt, zc + 0.6, G, faces=["north", "south", "east", "west"],
                            uv={"north": [0, 3.2, 1.6, 16], "south": [0, 3.2, 1.6, 16],
                                "east": [0, 3.2, 1.2, 16], "west": [0, 3.2, 1.2, 16]}))
    return els


def course_arm(kind, up, down):
    """The chaining band of one course: rails + (pickets | panel). `up` is
    None / contain / cover, `down` None / contain. Pickets belong to the
    downhill cell's floor and are dropped at the foot (down=contain).
    `open` (Van Siclen Av photo): nothing but the posts - open air between
    the railing and the roof."""
    if kind == "open":
        return []
    els = rails(up, down)
    if kind == "railing":
        els += mid_rail(up, down)
        if down != "contain":
            els += pickets((19.0, 23.0, 27.0))
    else:
        tex = "#glass" if kind == "glass" else "#cream"
        els += panel(tex, up, down, glass=(kind == "glass"))
    return els


def side_assets():
    tex = {"green": "el2_green", "cream": "el2_cream", "glass": "el2_wired_glass"}
    written = {}

    def emit(name, els):
        # default AO: with ambientocclusion:false every quad samples light from the
        # neighbour in its face direction, and a post beside a solid floor went black
        K.model(name, els, tex)
        K.model(name + "_r", [K.mirror_x(e) for e in els], tex)
        written[name] = True

    cuts_up = {"n": None, "c": "contain", "l": "cover"}
    cuts_down = {"n": None, "c": "contain"}
    for kind in ("railing", "wall", "glass", "open"):
        for uk, up in cuts_up.items():
            for dk, down in cuts_down.items():
                emit(f"el_stair_{kind}_arm_{uk}{dk}", course_arm(kind, up, down))
        emit(f"el_stair_{kind}_level", level_section(kind))
    for uk, up in (("n", None), ("c", "contain")):
        for dk, down in cuts_down.items():
            emit(f"el_stair_stringer_{uk}{dk}", stringer_kick(up, down))
    emit("el_stair_post_bottom", down_post(True))
    emit("el_stair_post_chain", down_post(False))
    emit("el_stair_post_bottom_top", down_post(True, True))
    emit("el_stair_post_chain_top", down_post(False, True))
    # items: a bottom-course segment
    KINDS = (("railing", "el_stair_railing"), ("wall", "el_stair_wall"), ("glass", "el_stair_wall_glass"),
             ("open", "el_stair_open"))
    for kind, name in KINDS:
        K.model(name + "_item", course_arm(kind, None, "contain") + stringer_kick(None, "contain")
                + down_post(True), tex)

    for kind, name in KINDS:
        parts = []
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            for side, suf in (("left", ""), ("right", "_r")):
                def ap(m):
                    a = {"model": f"{MOD}:block/{m}{suf}"}
                    if rot:
                        a["y"] = rot
                    return a
                base = {"facing": facing, "side": side}
                # arm: up cut depends on end_up + level, down cut on end_down
                for level in ("true", "false"):
                    for end_up in ("true", "false"):
                        uk = "l" if level == "true" else ("c" if end_up == "true" else "n")
                        for end_down in ("true", "false"):
                            dk = "c" if end_down == "true" else "n"
                            parts.append({"when": dict(base, level=level, end_up=end_up, end_down=end_down),
                                          "apply": ap(f"el_stair_{kind}_arm_{uk}{dk}")})
                            parts.append({"when": dict(base, bottom="true", level=level, end_up=end_up,
                                                       end_down=end_down),
                                          "apply": ap(f"el_stair_stringer_{'c' if uk != 'n' else 'n'}{dk}")})
                parts.append({"when": dict(base, level="true"), "apply": ap(f"el_stair_{kind}_level")})
                parts.append({"when": dict(base, bottom="true", top="false"), "apply": ap("el_stair_post_bottom")})
                parts.append({"when": dict(base, bottom="false", top="false"), "apply": ap("el_stair_post_chain")})
                parts.append({"when": dict(base, bottom="true", top="true"), "apply": ap("el_stair_post_bottom_top")})
                parts.append({"when": dict(base, bottom="false", top="true"), "apply": ap("el_stair_post_chain_top")})
        K.write_json(os.path.join(K.BLOCKSTATES, name + ".json"), {"multipart": parts})
        K.write_json(os.path.join(K.ITEM_MODELS, name + ".json"), {
            "parent": f"{MOD}:block/{name}_item",
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -1, 0], "scale": [0.5, 0.5, 0.5]}}})
        K.write_json(os.path.join(K.DATA, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    K.write_json(os.path.join(K.DATA, "recipes/el_stair_railing.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_bars"}, "D": {"item": "minecraft:green_dye"}, "N": {"item": "minecraft:iron_ingot"}},
        "pattern": ["II ", "IDI", " NI"], "result": {"item": f"{MOD}:el_stair_railing", "count": 4}})
    K.write_json(os.path.join(K.DATA, "recipes/el_stair_wall.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"P": {"item": "minecraft:birch_planks"}, "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["IP ", "IPI", " PI"], "result": {"item": f"{MOD}:el_stair_wall", "count": 4}})
    K.write_json(os.path.join(K.DATA, "recipes/el_stair_open.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:green_dye"}},
        "pattern": ["I  ", "ID ", "  I"], "result": {"item": f"{MOD}:el_stair_open", "count": 4}})
    K.write_json(os.path.join(K.DATA, "recipes/el_stair_wall_glass.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"P": {"item": "minecraft:glass_pane"}, "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["IP ", "IPI", " PI"], "result": {"item": f"{MOD}:el_stair_wall_glass", "count": 4}})


# ---------------------------------------------------------------------------
# roof
# ---------------------------------------------------------------------------

def deck(up, down, x0=0.0, x1=16.0):
    els = member(x0, x1, DECK[0], DECK[1], {"up": "#red", "down": "#soffit", "*": G}, up=up, down=down,
                 uv={"up": [max(0, x0), 0, min(16, x1), 16], "down": [max(0, x0), 0, min(16, x1), 16],
                     "north": [0, 8, 16, 9.6], "south": [0, 8, 16, 9.6],
                     "east": [0, 8, 16, 9.6], "west": [0, 8, 16, 9.6]})
    return els


def ties(up, down):
    """Half-purlins at both crosswise block edges under the deck (z_a 0..1 and
    15..16) - one 2 px purlin per joint across the roof."""
    els = []
    for z0, z1 in ((0.0, 1.0), (15.0, 16.0)):
        zz0, zz1 = z0, z1
        if down == "contain":
            zz1 = min(zz1, 24 - DECK[0])
        if zz1 <= zz0 + 0.05:
            continue
        els.append(sbox(0, DECK[0] - 1.4, zz0, 16, DECK[0], zz1, G, faces=["down", "north", "south"],
                        uv={"down": [0, 0, 16, 1], "north": [0, 4, 16, 5.4], "south": [0, 4, 16, 5.4]}))
    return els


def frieze(up, down):
    """Lattice frieze on the outer (west) edge, from the course's top rail up
    to the deck, plus the 2 px eave overhang with a fascia board."""
    els = []
    # bottom chord: its down face would be coplanar with the wall's top rail -> omitted
    els += member(0.2, 2.2, FRIEZE[0], FRIEZE[0] + 0.8, G, up=up, down=down, faces=["east", "west", "up", "north", "south"],
                  uv={"east": [0, 4, 16, 4.8], "west": [0, 4, 16, 4.8], "up": [0, 0, 2, 16],
                      "north": [0, 4, 2, 4.8], "south": [0, 4, 2, 4.8]})
    els += member(0.2, 2.2, FRIEZE[1] - 0.8, FRIEZE[1], G, up=up, down=down,
                  uv={"east": [0, 4, 16, 4.8], "west": [0, 4, 16, 4.8], "up": [0, 0, 2, 16],
                      "down": [0, 0, 2, 16], "north": [0, 4, 2, 4.8], "south": [0, 4, 2, 4.8]})
    els += member(1.0, 1.4, FRIEZE[0] + 0.8, FRIEZE[1] - 0.8, "#lattice", up=up, down=down, faces=["east", "west"],
                  uv={"east": [0, 4, 16, 12], "west": [0, 4, 16, 12]})
    # eave overhang + fascia
    els += deck(up, down, x0=-2.0, x1=0.0)
    els += member(-2.0, -1.0, DECK[0] - 1.0, DECK[1] + 0.2, G, up=up, down=down,
                  uv={"east": [0, 6, 16, 8.8], "west": [0, 6, 16, 8.8], "up": [0, 0, 1, 16],
                      "down": [0, 0, 1, 16], "north": [0, 6, 1, 8.8], "south": [0, 6, 1, 8.8]})
    # post stub: the course post below ends at this cell's floor, the frieze
    # chord starts 8..10 px up - continue the post into the chord so the
    # roof visibly rests on it (Thomas: not just air)
    els.append(wbox(0, 0, 13.6, 2.4, 10.8, 16, G, faces=["north", "south", "east", "west"],
                    uv={"north": [0, 0, 2.4, 10.8], "south": [0, 0, 2.4, 10.8],
                        "east": [4, 0, 6.4, 10.8], "west": [4, 0, 6.4, 10.8]}))
    if up == "cover0":
        # top of a flight: the course below levels off (its rail top is y 16 here)
        # but the sloped frieze bottom rises to 24 at the boundary - fill the wedge
        els.append(wbox(0.6, 16, 0, 1.8, 24.4, 8.6, G, faces=["east", "west", "north", "down"],
                        uv={"east": [0, 4, 8.6, 12.4], "west": [0, 4, 8.6, 12.4], "north": [0, 4, 1.2, 12.4],
                            "down": [0, 0, 1.2, 8.6]}))
    if down == "overhang":
        # the foot fascia (roof_end_down, x 0..16) continued across the eave overhang
        y0 = world_y(FRIEZE[0], 20)
        y1 = world_y(DECK[1], 18.6) + 0.2
        els.append(wbox(-2.0, y0, 18.6, 0.0, y1, 20, G,
                        uv={"north": [0, 4, 2, 4 + min(12, y1 - y0)], "south": [0, 4, 2, 4 + min(12, y1 - y0)],
                            "east": [0, 4, 1.4, 4 + min(12, y1 - y0)], "west": [0, 4, 1.4, 4 + min(12, y1 - y0)],
                            "up": [0, 0, 2, 1.4], "down": [0, 0, 2, 1.4]}))
    return els


def roof_end_down():
    """Fascia plate at the foot of the flight (world frame at z 18.6..20):
    closes the deck's 45-degree cut and the frieze band."""
    y0 = world_y(FRIEZE[0], 20)      # 8
    y1 = world_y(DECK[1], 18.6) + 0.2
    return [wbox(0, y0, 18.6, 16, y1, 20, G, faces=["north", "south", "up", "down", "east", "west"],
                 uv={"north": [0, 4, 16, 4 + min(12, y1 - y0)], "south": [0, 4, 16, 4 + min(12, y1 - y0)],
                     "east": [0, 4, 1.4, 4 + min(12, y1 - y0)], "west": [0, 4, 1.4, 4 + min(12, y1 - y0)],
                     "up": [0, 0, 16, 1.4], "down": [0, 0, 16, 1.4]})]


def roof_end_up():
    """Vertical fascia at the uphill boundary (world z 0..1.4) over the frieze
    zone, y 24..32 - authored coordinates stop at 32, so the deck edge above
    it stays open (it is meant to run under a landing roof / house roof)."""
    y0 = world_y(FRIEZE[0], 0)
    return [wbox(0, y0, 0, 16, 32, 1.4, G,
                 uv={"north": [0, 4, 16, 12], "south": [0, 4, 16, 12], "east": [0, 4, 1.4, 12],
                     "west": [0, 4, 1.4, 12], "up": [0, 0, 16, 1.4], "down": [0, 0, 16, 1.4]})]


def landing_deck():
    """Flat landing roof: deck y 20..23.2 (the stair roof's height at its cell
    edges), purlin cross under it at the cell centre so signs can hang."""
    els = [wbox(0, LAND_DECK[0], 0, 16, LAND_DECK[1], 16, {"up": "#red", "down": "#soffit", "*": G},
                faces=["up", "down"], uv={"up": [0, 0, 16, 16], "down": [0, 0, 16, 16]}),
           wbox(7, LAND_DECK[0] - 1.4, 0, 9, LAND_DECK[0], 16, G, faces=["down", "east", "west"],
                uv={"down": [7, 0, 9, 16], "east": [0, 4, 16, 5.4], "west": [0, 4, 16, 5.4]}),
           wbox(0, LAND_DECK[0] - 1.4, 7, 16, LAND_DECK[0], 9, G, faces=["down", "north", "south"],
                uv={"down": [0, 7, 16, 9], "north": [0, 4, 16, 5.4], "south": [0, 4, 16, 5.4]})]
    return els


def landing_edge():
    """Frieze + eave along the NORTH edge (z 0..2.4): chords, solid lattice
    band, deck edge face, 2 px overhang with a fascia. Rotated per edge."""
    f0, f1 = LAND_FRIEZE
    els = [wbox(0, f0, 0.2, 16, f0 + 1.6, 2.2, G, uv={"north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6],
                                                        "down": [0, 0, 16, 2], "east": [0, 4, 2, 5.6], "west": [0, 4, 2, 5.6]},
                faces=["north", "south", "down", "east", "west"]),
           wbox(0, f1 - 1.6, 0.2, 16, f1, 2.2, G, uv={"north": [0, 4, 16, 5.6], "south": [0, 4, 16, 5.6],
                                                        "up": [0, 0, 16, 2], "east": [0, 4, 2, 5.6], "west": [0, 4, 2, 5.6]},
                faces=["north", "south", "up", "east", "west"]),
           wbox(0, f0 + 1.6, 1.0, 16, f1 - 1.6, 1.4, "#lattice", faces=["north", "south"],
                uv={"north": [0, 2, 16, 14], "south": [0, 2, 16, 14]}),
           # deck edge + overhang strip and fascia
           wbox(0, LAND_DECK[0], -2, 16, LAND_DECK[1], 0, {"up": "#red", "down": "#soffit", "*": G},
                faces=["up", "down"], uv={"up": [0, 0, 16, 2], "down": [0, 0, 16, 2]}),
           wbox(0, LAND_DECK[0] - 1.0, -2, 16, LAND_DECK[1] + 0.2, -1, G,
                uv={"north": [0, 6, 16, 10.4], "south": [0, 6, 16, 10.4], "up": [0, 0, 16, 1],
                    "down": [0, 0, 16, 1], "east": [0, 6, 1, 10.4], "west": [0, 6, 1, 10.4]})]
    return els


def landing_assets():
    tex = {"green": "el2_green", "red": "el2_roof_red", "soffit": "el2_soffit", "lattice": "el2_lattice_solid"}
    K.model("el_landing_roof_deck", landing_deck(), tex, ao=False)
    K.model("el_landing_roof_edge", landing_edge(), tex, ao=False)
    item = landing_deck()
    for rot in range(4):
        item += [rot_y(e, rot) for e in landing_edge()]
    K.model("el_landing_roof_item", item, tex)
    parts = [{"apply": {"model": f"{MOD}:block/el_landing_roof_deck"}}]
    for side, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        a = {"model": f"{MOD}:block/el_landing_roof_edge"}
        if rot:
            a["y"] = rot
        parts.append({"when": {f"edge_{side}": "true"}, "apply": a})
    K.write_json(os.path.join(K.BLOCKSTATES, "el_landing_roof.json"), {"multipart": parts})
    K.write_json(os.path.join(K.ITEM_MODELS, "el_landing_roof.json"), {
        "parent": f"{MOD}:block/el_landing_roof_item",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -2, 0], "scale": [0.5, 0.5, 0.5]}}})
    K.write_json(os.path.join(K.DATA, "loot_tables/blocks/el_landing_roof.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_landing_roof"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    K.write_json(os.path.join(K.DATA, "recipes/el_landing_roof.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"R": {"item": "minecraft:red_terracotta"}, "I": {"item": "minecraft:iron_bars"}},
        "pattern": ["RRR", "I I"], "result": {"item": f"{MOD}:el_landing_roof", "count": 4}})


def tex_plate():
    """Galvanized diamond plate, 32 px: short 45-degree lugs on a 4 px grid,
    alternating direction, on brushed grey. Full tile (no grip strip)."""
    rng = random.Random("plate")
    base = (158, 161, 163)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            K.put(rows, x, y, K.shade(base, rng.uniform(-6, 6)))
    for y in range(0, 32, 4):
        for x in range(0, 32, 4):
            phase = ((x + y) // 4) % 2
            for i in range(3):
                px = x + i
                py = y + (i if phase else 2 - i)
                K.put(rows, px, py, K.shade(base, 30))
                K.put(rows, px, py + 1, K.shade(base, -34))
    return rows


def landing_floor():
    """Thin steel landing deck: 1 px diamond-plate pan at the block top (flush
    with a top tread), green edge channels 4 px deep under the west and north
    edges - every cell draws its own two, so a field reads as a joist grid."""
    g = "#green"
    return [wbox(0, 15, 0, 16, 16, 16, {"up": "#plate", "down": "#gpan", "*": g},
                 uv={"up": [0, 0, 16, 16], "down": [0, 0, 16, 16], "north": [0, 4, 16, 5], "south": [0, 4, 16, 5],
                     "east": [0, 4, 16, 5], "west": [0, 4, 16, 5]}),
            wbox(0, 11, 0, 1.6, 15, 16, g, faces=["down", "east", "west", "north", "south"],
                 uv={"down": [0, 0, 1.6, 16], "east": [0, 4, 16, 8], "west": [0, 4, 16, 8],
                     "north": [0, 4, 1.6, 8], "south": [0, 4, 1.6, 8]}),
            wbox(1.6, 11, 0, 16, 15, 1.6, g, faces=["down", "north", "south", "east"],
                 uv={"down": [1.6, 0, 16, 1.6], "north": [0, 4, 14.4, 8], "south": [0, 4, 14.4, 8],
                     "east": [0, 4, 1.6, 8]})]


def landing_floor_assets():
    K.write_png("el2_plate", tex_plate())
    tex = {"green": "el2_green", "plate": "el2_plate", "gpan": "el2_green_panel"}
    K.model("el_stair_landing", landing_floor(), tex)
    K.write_json(os.path.join(K.BLOCKSTATES, "el_stair_landing.json"),
                 {"variants": {"": {"model": f"{MOD}:block/el_stair_landing"}}})
    K.write_json(os.path.join(K.ITEM_MODELS, "el_stair_landing.json"), {
        "parent": f"{MOD}:block/el_stair_landing",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [0.6, 0.6, 0.6]}}})
    K.write_json(os.path.join(K.DATA, "loot_tables/blocks/el_stair_landing.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_stair_landing"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    K.write_json(os.path.join(K.DATA, "recipes/el_stair_landing.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "B": {"item": "minecraft:iron_bars"}},
        "pattern": ["III", "B B"], "result": {"item": f"{MOD}:el_stair_landing", "count": 6}})


def entrance_sign_box():
    """Black sign box on the FACING (street) side, y 12..22: a 2 px drop
    under the hood's frieze chord (y 8 of the roof cell = 24 here). Full
    width so adjacent cells merge into one panel."""
    return [wbox(0, 12, 2, 16, 22, 5, "#black", uv={"north": [0, 0, 16, 10], "south": [0, 0, 16, 10], "east": [0, 0, 3, 10],
                                                   "west": [0, 0, 3, 10], "up": [0, 0, 16, 3], "down": [0, 0, 16, 3]})]


def entrance_sign_strap(x0):
    """One hanger strap from the box top to the chord; drawn at the run ends only."""
    return [wbox(x0, 22, 3, x0 + 1, 24.2, 4, "#green", faces=["north", "south", "east", "west"],
                 uv={"north": [4, 0, 5, 2.2], "south": [4, 0, 5, 2.2], "east": [4, 0, 5, 2.2], "west": [4, 0, 5, 2.2]})]


def entrance_sign_assets():
    tex = {"black": "el2_black", "green": "el2_green"}
    K.model("el_entrance_sign", entrance_sign_box(), tex)
    K.model("el_entrance_sign_strap_left", entrance_sign_strap(2), tex)
    K.model("el_entrance_sign_strap_right", entrance_sign_strap(13), tex)
    K.model("el_entrance_sign_item", entrance_sign_box() + entrance_sign_strap(2) + entrance_sign_strap(13), tex)
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        def ap(m):
            a = {"model": f"{MOD}:block/{m}"}
            if rot:
                a["y"] = rot
            return a
        parts.append({"when": {"facing": facing}, "apply": ap("el_entrance_sign")})
        parts.append({"when": {"facing": facing, "left": "false"}, "apply": ap("el_entrance_sign_strap_left")})
        parts.append({"when": {"facing": facing, "right": "false"}, "apply": ap("el_entrance_sign_strap_right")})
    K.write_json(os.path.join(K.BLOCKSTATES, "el_entrance_sign.json"), {"multipart": parts})
    K.write_json(os.path.join(K.ITEM_MODELS, "el_entrance_sign.json"), {
        "parent": f"{MOD}:block/el_entrance_sign_item",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -2, 0], "scale": [0.7, 0.7, 0.7]}}})
    K.write_json(os.path.join(K.DATA, "loot_tables/blocks/el_entrance_sign.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_entrance_sign"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    K.write_json(os.path.join(K.DATA, "recipes/el_entrance_sign.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "B": {"item": "minecraft:black_dye"},
                "G": {"item": "minecraft:glowstone_dust"}},
        "pattern": ["I I", "BGB"], "result": {"item": f"{MOD}:el_entrance_sign", "count": 1}})


# ---------------------------------------------------------------------------
# flat item icons (Thomas: the 3D item models filled half the screen in hand)
# ---------------------------------------------------------------------------

ICON_DIR = os.path.join(K.ASSETS, "textures/item")
GREEN_I = (58, 108, 88, 255)
GREEN_D = (38, 78, 62, 255)
RED_I = (142, 54, 46, 255)
CREAM_I = (214, 206, 186, 255)
CREAM_D = (176, 166, 144, 255)
GLASS_I = (196, 214, 220, 255)
PLATE_I = (158, 161, 163, 255)
BLACK_I = (22, 22, 24, 255)
WHITE_I = (240, 240, 240, 255)
CLEAR = (0, 0, 0, 0)


def icon_canvas():
    return [[CLEAR] * 32 for _ in range(32)]


def irect(rows, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(32, y1)):
        for x in range(max(0, x0), min(32, x1)):
            rows[y][x] = c


def iline(rows, x0, y0, x1, y1, c, w=1):
    """Bresenham-ish line with a square brush of width w."""
    n = max(abs(x1 - x0), abs(y1 - y0), 1)
    for i in range(n + 1):
        x = round(x0 + (x1 - x0) * i / n)
        y = round(y0 + (y1 - y0) * i / n)
        irect(rows, x, y, x + w, y + w, c)


def ipara(rows, x0, x1, ytop_at_x0, ytop_at_x1, h, c):
    """Sloped band: top edge from (x0, ytop_at_x0) to (x1, ytop_at_x1), h px thick."""
    for x in range(x0, x1):
        t = (x - x0) / max(1, x1 - x0 - 1)
        y = round(ytop_at_x0 + (ytop_at_x1 - ytop_at_x0) * t)
        irect(rows, x, y, x + 1, y + h, c)


def icon_stair_railing():
    r = icon_canvas()
    ipara(r, 2, 30, 26, 6, 3, GREEN_D)      # stringer
    ipara(r, 2, 30, 14, -6, 2, GREEN_I)     # top rail
    for x in (6, 11, 16, 21, 26):
        yt = round(14 + (-6 - 14) * (x - 2) / 27)
        yb = round(26 + (6 - 26) * (x - 2) / 27)
        irect(r, x, yt + 1, x + 1, yb, GREEN_I)
    return r


def icon_stair_wall(panel=CREAM_I, edge=CREAM_D):
    r = icon_canvas()
    for x in range(3, 29):
        t = (x - 3) / 25
        yt = round(15 - 18 * t) + 7
        irect(r, x, yt, x + 1, yt + 12, panel)
        if (x - 3) % 5 == 0:
            irect(r, x, yt, x + 1, yt + 12, edge)
    ipara(r, 3, 29, 22, 4, 2, GREEN_I)
    ipara(r, 3, 29, 34, 16, 2, GREEN_I)
    irect(r, 3, 21, 5, 36, GREEN_D)
    irect(r, 27, 3, 29, 18, GREEN_D)
    return r


def icon_stair_glass():
    r = icon_stair_wall(GLASS_I, (150, 170, 178, 255))
    return r


def icon_stair_open():
    r = icon_canvas()
    irect(r, 8, 8, 11, 30, GREEN_I)
    irect(r, 20, 2, 23, 22, GREEN_I)
    ipara(r, 2, 30, 26, 8, 2, GREEN_D)
    return r


def icon_stair_roof():
    r = icon_canvas()
    ipara(r, 1, 31, 24, 4, 4, RED_I)
    ipara(r, 1, 31, 28, 8, 2, GREEN_I)
    for x in range(1, 31, 5):
        y = round(24 + (4 - 24) * (x - 1) / 29)
        irect(r, x, y, x + 1, y + 4, (112, 40, 34, 255))
    return r


def icon_landing_roof():
    r = icon_canvas()
    irect(r, 1, 9, 31, 13, RED_I)
    for x in range(2, 31, 5):
        irect(r, x, 9, x + 1, 13, (112, 40, 34, 255))
    irect(r, 1, 13, 31, 15, GREEN_I)
    irect(r, 1, 15, 31, 22, GREEN_D)
    for x in range(1, 31, 6):
        iline(r, x, 21, x + 3, 15, GREEN_I)
        iline(r, x + 3, 15, x + 6, 21, GREEN_I)
    irect(r, 1, 22, 31, 24, GREEN_I)
    return r


def icon_stair_landing():
    r = icon_canvas()
    irect(r, 2, 8, 30, 18, PLATE_I)
    for y in range(8, 18, 3):
        for x in range(2, 30, 4):
            phase = ((x + y) // 3) % 2
            irect(r, x + (1 if phase else 0), y + 1, x + 2 + (1 if phase else 0), y + 2, (196, 199, 201, 255))
    irect(r, 2, 18, 30, 20, GREEN_D)
    irect(r, 2, 20, 6, 26, GREEN_I)
    irect(r, 26, 20, 30, 26, GREEN_I)
    return r


def icon_entrance_sign():
    r = icon_canvas()
    irect(r, 5, 4, 7, 10, GREEN_I)
    irect(r, 25, 4, 27, 10, GREEN_I)
    irect(r, 1, 10, 31, 24, BLACK_I)
    irect(r, 2, 11, 30, 23, (32, 32, 36, 255))
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            if dx * dx + dy * dy <= 10:
                r[17 + dy][7 + dx] = (150, 150, 156, 255)
    irect(r, 13, 15, 27, 17, WHITE_I)
    irect(r, 13, 19, 23, 20, WHITE_I)
    return r


def icon_wall(panel, edge, post):
    r = icon_canvas()
    irect(r, 2, 4, 30, 28, panel)
    for x in range(4, 30, 4):
        irect(r, x, 5, x + 1, 27, edge)
    irect(r, 2, 4, 30, 6, edge)
    irect(r, 2, 26, 30, 28, edge)
    irect(r, 1, 3, 4, 29, post)
    irect(r, 28, 3, 31, 29, post)
    return r


def icon_roof():
    r = icon_canvas()
    ipara(r, 1, 16, 14, 6, 4, RED_I)
    ipara(r, 16, 31, 6, 14, 4, RED_I)
    irect(r, 15, 5, 17, 8, GREEN_D)
    irect(r, 1, 17, 8, 19, GREEN_I)
    irect(r, 24, 17, 31, 19, GREEN_I)
    irect(r, 1, 19, 8, 25, GREEN_D)
    irect(r, 24, 19, 31, 25, GREEN_D)
    for x0 in (1, 24):
        iline(r, x0, 24, x0 + 3, 19, GREEN_I)
        iline(r, x0 + 3, 19, x0 + 6, 24, GREEN_I)
    irect(r, 1, 25, 8, 27, GREEN_I)
    irect(r, 24, 25, 31, 27, GREEN_I)
    return r


def icon_post(named=False):
    r = icon_canvas()
    irect(r, 13, 2, 19, 30, GREEN_I)
    irect(r, 15, 2, 17, 30, GREEN_D)
    irect(r, 8, 28, 24, 31, GREEN_I)
    irect(r, 6, 1, 26, 4, GREEN_I)
    iline(r, 9, 8, 13, 4, GREEN_I, 2)
    iline(r, 19, 4, 23, 8, GREEN_I, 2)
    if named:
        irect(r, 10, 12, 22, 18, BLACK_I)
        irect(r, 12, 14, 20, 16, WHITE_I)
    return r


def icon_railing(sign=False):
    r = icon_canvas()
    irect(r, 1, 6, 31, 8, GREEN_I)
    irect(r, 1, 26, 31, 28, GREEN_I)
    irect(r, 1, 4, 4, 30, GREEN_D)
    irect(r, 28, 4, 31, 30, GREEN_D)
    for x in range(7, 27, 4):
        irect(r, x, 8, x + 1, 26, GREEN_I)
    irect(r, 1, 16, 31, 17, GREEN_I)
    if sign:
        irect(r, 4, 10, 28, 22, BLACK_I)
        irect(r, 7, 15, 25, 17, WHITE_I)
    return r


def icon_wall_glass():
    r = icon_wall(GLASS_I, (150, 170, 178, 255), GREEN_D)
    for i in range(-32, 32, 8):
        iline(r, 4 + i, 6, 12 + i, 26, (120, 140, 146, 255))
        iline(r, 28 - i, 6, 20 - i, 26, (120, 140, 146, 255))
    irect(r, 1, 3, 4, 29, GREEN_D)
    irect(r, 28, 3, 31, 29, GREEN_D)
    return r


def icon_wall_sign():
    r = icon_wall(CREAM_I, CREAM_D, GREEN_D)
    irect(r, 4, 9, 28, 23, BLACK_I)
    irect(r, 8, 15, 24, 17, WHITE_I)
    return r


def icon_lamp_pole():
    r = icon_canvas()
    irect(r, 14, 1, 18, 28, GREEN_I)
    irect(r, 9, 27, 23, 31, GREEN_I)
    irect(r, 11, 25, 21, 27, GREEN_D)
    return r


def icon_lamp_head():
    r = icon_canvas()
    irect(r, 14, 18, 18, 31, GREEN_I)
    irect(r, 12, 15, 20, 18, GREEN_I)
    irect(r, 8, 12, 24, 15, GREEN_I)
    irect(r, 4, 9, 28, 12, GREEN_I)
    irect(r, 10, 15, 22, 21, (255, 244, 208, 255))
    return r


def icon_roof_light():
    r = icon_canvas()
    irect(r, 15, 2, 17, 10, BLACK_I)
    irect(r, 3, 10, 29, 16, BLACK_I)
    irect(r, 5, 16, 27, 20, (255, 244, 208, 255))
    irect(r, 4, 20, 28, 22, (255, 230, 150, 120))
    return r


def icon_el_sign():
    r = icon_canvas()
    irect(r, 15, 1, 17, 8, GREEN_I)
    irect(r, 3, 8, 29, 22, BLACK_I)
    irect(r, 6, 13, 26, 16, WHITE_I)
    irect(r, 2, 22, 30, 24, GREEN_I)
    return r


def icon_doorway():
    r = icon_canvas()
    irect(r, 1, 3, 4, 29, GREEN_D)
    irect(r, 28, 3, 31, 29, GREEN_D)
    irect(r, 1, 3, 31, 7, GREEN_I)
    return r


def icon_assets():
    os.makedirs(ICON_DIR, exist_ok=True)
    icons = {
        "el_wall_doorway": icon_doorway(),
        "el_roof": icon_roof(), "el_post": icon_post(), "el_post_named": icon_post(True),
        "el_railing": icon_railing(), "el_railing_sign": icon_railing(True),
        "el_wall": icon_wall(CREAM_I, CREAM_D, GREEN_D), "el_wall_glass": icon_wall_glass(),
        "el_wall_sign": icon_wall_sign(), "el_platform_lamp": icon_lamp_pole(),
        "el_platform_lamp_head": icon_lamp_head(), "el_roof_light": icon_roof_light(), "el_sign": icon_el_sign(),
        "el_stair_railing": icon_stair_railing(), "el_stair_wall": icon_stair_wall(),
        "el_stair_wall_glass": icon_stair_glass(), "el_stair_open": icon_stair_open(),
        "el_stair_roof": icon_stair_roof(), "el_landing_roof": icon_landing_roof(),
        "el_stair_landing": icon_stair_landing(), "el_entrance_sign": icon_entrance_sign(),
        "el_wall_cream": icon_wall(CREAM_I, CREAM_D, CREAM_D), "el_wall_green": icon_wall(GREEN_I, GREEN_D, GREEN_D),
    }
    for name, rows in icons.items():
        import pngtool
        pngtool.write_png(os.path.join(ICON_DIR, name + ".png"), rows)
        K.write_json(os.path.join(K.ITEM_MODELS, name + ".json"),
                     {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}})


def rot_y(el, quarter):
    """Rotate an unrotated world-frame element about the block centre by
    quarter*90 degrees clockwise (blockstate y rotation), for item models."""
    import copy
    e = copy.deepcopy(el)
    for _ in range(quarter):
        f, t = e["from"], e["to"]
        # (x, z) -> (16 - z, x)
        e["from"] = [16 - t[2], f[1], f[0]]
        e["to"] = [16 - f[2], t[1], t[0]]
        faces = {}
        m = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}
        for name, face in e["faces"].items():
            faces[m[name]] = face
        e["faces"] = faces
    return e


def tex_lattice_solid():
    """The Warren lattice on a dark backing (no alpha): the stair/landing
    frieze bands are too short for a see-through web to read."""
    rows = K.tex_lattice()
    back = K.shade(K.GREEN, -44)
    return [[(back if px[3] == 0 else px) for px in row] for row in rows]


def roof_assets():
    """UP: run (chains) / end (deck to the boundary + vertical fascia) / landing
    (deck to the boundary, no fascia - a landing roof continues it). DOWN: run /
    end (4 px overhang + foot fascia) / landing (cut at the boundary)."""
    tex = {"green": "el2_green", "red": "el2_roof_red", "soffit": "el2_soffit", "lattice": "el2_lattice_solid"}
    cuts_up = {"n": None, "c": "cover0"}
    cuts_down = {"n": None, "c": "overhang", "l": "contain"}
    for uk, up in cuts_up.items():
        for dk, down in cuts_down.items():
            K.model(f"el_stair_roof_deck_{uk}{dk}", deck(up, down) + ties(up, down), tex, ao=False)
            fr = frieze(up, down)
            K.model(f"el_stair_roof_frieze_{uk}{dk}", fr, tex, ao=False)
            K.model(f"el_stair_roof_frieze_{uk}{dk}_r", [K.mirror_x(e) for e in fr], tex, ao=False)
    K.model("el_stair_roof_end_down", roof_end_down(), tex, ao=False)
    K.model("el_stair_roof_end_up", roof_end_up(), tex, ao=False)
    K.model("el_stair_roof_item", deck(None, "overhang") + ties(None, "overhang") + frieze(None, "overhang")
            + [K.mirror_x(e) for e in frieze(None, "overhang")] + roof_end_down(), tex)
    parts = []
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        def ap(m):
            a = {"model": f"{MOD}:block/{m}"}
            if rot:
                a["y"] = rot
            return a
        for up, uk in (("run", "n"), ("end", "c"), ("landing", "c")):
            for down, dk in (("run", "n"), ("end", "c"), ("landing", "l")):
                w = {"facing": facing, "up": up, "down": down}
                parts.append({"when": w, "apply": ap(f"el_stair_roof_deck_{uk}{dk}")})
                parts.append({"when": dict(w, edge_left="true"), "apply": ap(f"el_stair_roof_frieze_{uk}{dk}")})
                parts.append({"when": dict(w, edge_right="true"), "apply": ap(f"el_stair_roof_frieze_{uk}{dk}_r")})
        parts.append({"when": {"facing": facing, "down": "end"}, "apply": ap("el_stair_roof_end_down")})
        parts.append({"when": {"facing": facing, "up": "end"}, "apply": ap("el_stair_roof_end_up")})
    K.write_json(os.path.join(K.BLOCKSTATES, "el_stair_roof.json"), {"multipart": parts})
    K.write_json(os.path.join(K.ITEM_MODELS, "el_stair_roof.json"), {
        "parent": f"{MOD}:block/el_stair_roof_item",
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -2, 0], "scale": [0.45, 0.45, 0.45]}}})
    K.write_json(os.path.join(K.DATA, "loot_tables/blocks/el_stair_roof.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:el_stair_roof"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    K.write_json(os.path.join(K.DATA, "recipes/el_stair_roof.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"R": {"item": "minecraft:red_terracotta"}, "I": {"item": "minecraft:iron_bars"},
                "D": {"item": "minecraft:green_dye"}},
        "pattern": ["RR ", "IRR", "DI "], "result": {"item": f"{MOD}:el_stair_roof", "count": 4}})


SIDE_PROPS = {"facing": {"north", "east", "south", "west"}, "side": {"left", "right"},
              "bottom": {"true", "false"}, "top": {"true", "false"}, "end_up": {"true", "false"},
              "end_down": {"true", "false"}, "level": {"true", "false"}}
ROOF_PROPS = {"facing": {"north", "east", "south", "west"}, "edge_left": {"true", "false"},
              "edge_right": {"true", "false"}, "up": {"run", "end", "landing"}, "down": {"run", "end", "landing"}}
LAND_PROPS = {"edge_north": {"true", "false"}, "edge_east": {"true", "false"},
              "edge_south": {"true", "false"}, "edge_west": {"true", "false"}}
SIGN_PROPS = {"facing": {"north", "east", "south", "west"}, "left": {"true", "false"}, "right": {"true", "false"}}
VERIFY = [("el_entrance_sign", SIGN_PROPS), ("el_stair_railing", SIDE_PROPS), ("el_stair_wall", SIDE_PROPS), ("el_stair_wall_glass", SIDE_PROPS),
          ("el_stair_open", SIDE_PROPS),
          ("el_stair_roof", ROOF_PROPS), ("el_landing_roof", LAND_PROPS)]


def build():
    K.write_png("el2_lattice_solid", tex_lattice_solid())
    side_assets()
    roof_assets()
    landing_assets()
    landing_floor_assets()
    entrance_sign_assets()
    icon_assets()          # last: overrides the 3D item models written above
