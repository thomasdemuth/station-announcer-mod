"""Stair side v3 prototype: slim channel stringer, vertical boards / pickets
between sloped rails, posts every other cell, newel ends. Side cell frame:
FACING north (ascent -z), stair to the WEST, plane x 0..2.4."""
import sys
sys.path.insert(0, "/Users/thomasdemuth/Documents/Coding Projects/Station Announcer Mod/tools")
import gen_el2_stairs as S0
import gen_el2_assets as K
import render_scene as R

G, CREAM, GLASS = "#green", "#cream", "#glass"
TEX = {"green": "station_announcer:block/el2_green", "cream": "station_announcer:block/el2_cream",
       "glass": "station_announcer:block/el2_wired_glass_plain"}
member, wbox, world_y = S0.member, S0.wbox, S0.world_y
HAND = (19.2, 20.0)          # handrail / top rail (v)
FOOTRAIL = (13.0, 13.6)      # railing bottom rail
PLATE = (7.8, 12.2)          # stringer web
FLANGE = 0.5


def uvs(w, h, u0=0, v0=4):
    return {"east": [u0, v0, u0 + min(16, w), v0 + min(12, h)], "west": [u0, v0, u0 + min(16, w), v0 + min(12, h)]}


def stringer(up, down):
    els = member(0.7, 1.7, PLATE[0], PLATE[1], G, up=up, down=down)
    els += member(0.2, 2.2, PLATE[0] - FLANGE, PLATE[0], G, up=up, down=down)
    els += member(0.2, 2.2, PLATE[1], PLATE[1] + FLANGE, G, up=up, down=down)
    return els


def top_rail(up, down):
    return member(0.3, 2.1, HAND[0], HAND[1], G, up=up, down=down)


def foot_rail(up, down):
    return member(0.5, 1.9, FOOTRAIL[0], FOOTRAIL[1], G, up=up, down=down)


def vstrip(z0, z1, lo, hi, tex, x0=0.7, x1=1.7, faces=("east", "west"), u0=None):
    """World-vertical strip over world z0..z1 whose flat ends hide inside the
    sloped members lo=(v0,v1) and hi=(v0,v1): anywhere between the member's
    highest bottom point and lowest top point over the strip."""
    yb = ((2 * lo[0] - z0) + (2 * lo[1] - z1)) / 2
    yt = ((2 * hi[0] - z0) + (2 * hi[1] - z1)) / 2
    u = (z0 % 16) if u0 is None else u0
    h = yt - yb
    if tex == GLASS:
        v0 = (-yt) % 4
        uv = {f: [u, v0, u + (z1 - z0), min(16, v0 + h)] for f in faces}
    else:
        uv = {f: [u, max(0, 16 - h) if h <= 16 else 0, u + (z1 - z0), 16] for f in faces}
    for f in ("north", "south"):
        if f in uv:
            uv[f] = [4, 0, 4 + (x1 - x0), min(16, h)]
    return wbox(x0, yb, z0, x1, yt, z1, tex, faces=list(faces), uv=uv)


def pickets(lo=FOOTRAIL, hi=HAND):
    return [vstrip(zc - 0.5, zc + 0.5, lo, hi, G, faces=("north", "south", "east", "west"), u0=4)
            for zc in (18.5, 22.5, 26.5, 30.5)]


def boards(tex, lo, hi):
    return [vstrip(16 + 2 * i, 18 + 2 * i, lo, hi, tex) for i in range(8)]


def mullions(lo, hi):
    return [vstrip(zc - 0.5, zc + 0.5, lo, hi, G, x0=0.5, x1=1.9, faces=("north", "south", "east", "west"), u0=4)
            for zc in (24.5,)]


def post(y0, y1):
    return [wbox(0.2, y0, 13.8, 2.2, y1, 15.8, G, faces=["north", "south", "east", "west", "up"])]


def cell(kind, bottom, up=None, down=None, with_post=False, top=False):
    """One mid-run cell of a course. kind: railing / wall / glass / open."""
    lower = (PLATE[0], PLATE[1]) if bottom else (HAND[0] - 8, HAND[1] - 8)   # what the verticals stand in
    els = []
    if bottom:
        els += stringer(up, down)
    if kind != "open":
        els += top_rail(up, down)
    over = down != "contain"          # verticals over the downhill cell's floor
    if kind == "railing":
        els += foot_rail(up, down)
        if over:
            els += pickets()
    elif kind in ("wall", "glass") and over:
        tex = CREAM if kind == "wall" else GLASS
        els += boards(tex, lower, HAND)
        if kind == "glass":
            els += mullions(lower, HAND)
    if with_post:
        y0 = world_y(PLATE[0], 14.8) if bottom else world_y(HAND[0], 14.8) - 16
        y1 = 32.0 if top else world_y(HAND[1], 14.8) - 0.2
        els += post(y0, y1)
    return {"textures": TEX, "elements": els}


def mirrored(m):
    return {"textures": m["textures"], "elements": [K.mirror_x(e) for e in m["elements"]]}


F = 16.0                      # floor height at the top of a flight (the top tread is flush with it)
LEVEL_RAIL = (F + 13.8, F + 15.4)


def newel(z0, y0, y1, cap=True):
    els = [wbox(0.2, y0, z0, 2.2, y1, z0 + 2.0, G, faces=["north", "south", "east", "west"])]
    if cap:
        els.append(wbox(-0.1, y1, z0 - 0.3, 2.5, y1 + 0.6, z0 + 2.3, G))
    return els


def foot(kind, bottom, top=False):
    """Bottom cell of a flight: members cut at the street, a capped newel at the
    downhill edge that the rail dies into."""
    m = cell(kind, bottom, down="contain")
    y_rail = world_y(HAND[1], 14.8)
    if bottom:
        m["elements"] += newel(13.8, 0.0, 32.0 if top else y_rail + 0.4, cap=not top)
    else:
        m["elements"] += newel(13.8, world_y(HAND[0], 14.8) - 16, 32.0 if top else y_rail + 0.4, cap=not top)
    return m


def level(kind, bottom, with_post=False):
    """Top cell: the slope runs to world z 8 (where the nosing line meets the
    floor), a knuckle turns the rail level, and it dies into a capped newel at
    the head of the flight. The cell draws its own z 8..16 verticals."""
    lower = (PLATE[0], PLATE[1]) if bottom else (HAND[0] - 8, HAND[1] - 8)
    els = []
    if bottom:
        els += stringer("cover", None)
        els.append(wbox(0.2, F - 1.6, 0, 2.2, F + 0.4, 8.6, G))              # level sill closing the stringer
    low_level = (F + 0.2) if bottom else LEVEL_RAIL[0] - 16 + 0.6
    if kind != "open":
        els += top_rail("cover", None)
        els.append(wbox(0.3, LEVEL_RAIL[0], 1.0, 2.1, LEVEL_RAIL[1], 8.4, G))
        els.append(wbox(0.1, F + 13.4, 6.8, 2.3, 32.0, 9.4, G))             # knuckle over the bend
    if kind == "railing":
        els += foot_rail("cover", None)
        els.append(wbox(0.5, F + 2, 1.0, 1.9, F + 3.2, 8.4, G))
        els += pickets()
        for zc in (10.5, 14.5):
            els.append(vstrip(zc - 0.5, zc + 0.5, FOOTRAIL, HAND, G, faces=("north", "south", "east", "west"), u0=4))
        els.append(wbox(0.7, F + 3.0, 5.0, 1.7, LEVEL_RAIL[0] + 0.2, 6.0, G, faces=["north", "south", "east", "west"]))
    elif kind in ("wall", "glass"):
        tex = CREAM if kind == "wall" else GLASS
        els += boards(tex, lower, HAND)
        if kind == "glass":
            els += mullions(lower, HAND)
        for i in range(4):
            els.append(vstrip(8 + 2 * i, 10 + 2 * i, lower, HAND, tex))
        v0 = 0 if tex == CREAM else (-(LEVEL_RAIL[0] + 0.2)) % 4
        h = LEVEL_RAIL[0] + 0.2 - low_level
        els.append(wbox(0.7, low_level, 1.0, 1.7, LEVEL_RAIL[0] + 0.2, 8.0, tex, faces=["east", "west"],
                        uv={"east": [1, v0, 8, min(16, v0 + h)], "west": [1, v0, 8, min(16, v0 + h)]}))
        if kind == "glass":
            els.append(vstrip(11.5, 12.5, lower, HAND, G, x0=0.5, x1=1.9, faces=("north", "south", "east", "west"), u0=4))
    y0 = F - 1.6 if bottom else LEVEL_RAIL[0] - 16
    els += newel(-0.4, y0, LEVEL_RAIL[1] + 0.0, cap=True) if kind != "open" else newel(-0.4, y0, 32.0, cap=False)
    if with_post:
        yb = world_y(PLATE[0], 14.8) if bottom else world_y(HAND[0], 14.8) - 16
        els += post(yb, world_y(HAND[1], 14.8) - 0.2)
    return {"textures": TEX, "elements": els}


def shifted(m, dx):
    """In-cell variant: the same side on the stair's own edge."""
    import json
    out = []
    for e in m["elements"]:
        e = json.loads(json.dumps(e))
        e["from"][0] += dx
        e["to"][0] += dx
        if "rotation" in e:
            e["rotation"]["origin"][0] += dx
        out.append(e)
    return {"textures": m["textures"], "elements": out}


def clipped(m, y_max):
    """Under a slab: world-vertical strips stop at the soffit (local y_max) and
    sloped members that would rise past it are dropped."""
    out = []
    for e in m["elements"]:
        if "rotation" in e:
            v1 = e["to"][1]
            z0 = e["from"][2]
            if 2 * v1 - (v1 + z0 - 8) > y_max + 0.01 and e["to"][1] > 14:      # a rail whose high end passes the soffit
                continue
            out.append(e)
            continue
        if e["from"][1] >= y_max:
            continue
        if e["to"][1] > y_max:
            e = dict(e, to=[e["to"][0], y_max, e["to"][2]])
        out.append(e)
    return {"textures": m["textures"], "elements": out}
