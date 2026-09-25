#!/usr/bin/env python3
"""ESI THEME for the el station kit - GENERATED assets (never hand-edit the output).

The MTA's Enhanced Station Initiative (2017-2020) rebuilt the Astoria line el
stations (30 Av, 36 Av, 39 Av, Broadway, Astoria Blvd) by RE-SKINNING the
1917 steel skeleton rather than replacing it (research notes: ESI_PLAN.md):
charcoal paint on the riveted columns, girders and Warren lattice frieze, a
light silver-grey standing-seam roof over a warm WOOD-PLANK soffit with one
linear LED strip, full-height clear glass windscreen bays on a black kick
curb (some bays solid black), galvanized welded-wire MESH at the open
platform ends, L-shaped LED lamp poles, stainless-and-black mezzanines with
laminated ART GLASS windows, grey tile floors, flat black entrance canopies.

So the ESI kit is the classic kit's STRUCTURE with an ESI skin: every
`esi_*` block uses the SAME Java class, the same blockstate logic and the
same geometry as its classic `el_*` counterpart. This generator reads the
classic generators' output (run gen_el2_assets.py / gen_stair_assets.py
first - both call this at the end), copies every model a classic block uses
into models/block/esi/ with its textures remapped, and writes the handful of
ESI-only pieces (mesh bay, L-arm LED lamp head) from the classic builders.
The subway stair's in-cell sides gain esi_stringer / esi_railing / esi_wall /
esi_wall_fill values (SubwayStairBlock.InSide), set by sneak-clicking a tread
with an ESI stair course item.

Textures: most ESI textures are the classic ones RECOLOURED about their base
colour (out = new_base + (pixel - old_base)), which keeps every rivet, seam
and shading step; the materials ESI introduced (wood soffit, clear glass,
mesh, art glass, black panels, LED, grey tile) are drawn here.
"""

import hashlib
import json
import math
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_el2_assets as K

MOD = K.MOD
ESI_TEX = os.path.join(K.TEXTURES, "esi")
ESI_MODELS = os.path.join(K.MODELS, "esi")
ITEM_TEX = os.path.join(K.ASSETS, "textures/item")
LANG = os.path.join(K.ASSETS, "lang/en_us.json")
PICKAXE = os.path.join(K.ROOT, "src/main/resources/data/minecraft/tags/blocks/mineable/pickaxe.json")

CHARCOAL = (43, 45, 48)
SILVER = (169, 174, 178)
POLE_GREY = (150, 154, 156)
SATIN = (128, 132, 136)
GREEN = K.GREEN
RED = K.RED


# ---------------------------------------------------------------------------
# textures
# ---------------------------------------------------------------------------

def tex_path(name):
    return os.path.join(K.TEXTURES, name + ".png")


def recolour(src, old, new, out, gain=1.0):
    """Shift a classic texture from its base colour to a new one, keeping
    every shading delta (rivets, seams, grain) - alpha untouched."""
    im = Image.open(tex_path(src)).convert("RGBA")
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            d = ((r - old[0]) + (g - old[1]) + (b - old[2])) / 3.0 * gain
            px[x, y] = (K.clamp(new[0] + d), K.clamp(new[1] + d), K.clamp(new[2] + d), a)
    save(im, out)


def save(im, name):
    os.makedirs(ESI_TEX, exist_ok=True)
    im.save(os.path.join(ESI_TEX, name + ".png"))


def rows_image(rows):
    n = len(rows)
    im = Image.new("RGBA", (len(rows[0]), n))
    px = im.load()
    for y in range(n):
        for x in range(len(rows[0])):
            c = rows[y][x]
            px[x, y] = tuple(c) + ((255,) if len(c) == 3 else ())
    return im


def tex_soffit_wood():
    """Honey wood planks (the ESI canopy soffit), 4-texel boards along u with
    a dark joint, grain streaks and staggered butt joints."""
    rng = random.Random("esi_wood")
    base = (185, 132, 79)
    rows = K.canvas(32, base)
    for y in range(32):
        board = y // 4
        tone = rng.uniform(-10, 10)
        for x in range(32):
            grain = 5 * math.sin((x + board * 7) * 0.45) + rng.uniform(-4, 4)
            d = tone + grain
            if y % 4 == 0:
                d -= 40
            elif y % 4 == 1:
                d += 8
            if (x + board * 11) % 32 == 0:
                d -= 34
            K.put(rows, x, y, K.shade(base, d))
    return rows


def tex_panel_black():
    """Glossy black panel with a soft vertical reflection band."""
    rng = random.Random("esi_black")
    base = (26, 27, 30)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            glint = 10 * max(0.0, 1 - abs(x - 9 - y * 0.2) / 3.0)
            K.put(rows, x, y, K.shade(base, rng.uniform(-2, 2) + glint))
    return rows


def tex_glass(frit=True):
    """Clear ESI windscreen glass (cutout): almost all clear, a faint dot frit
    band and one soft glare streak - reads as big clean panes."""
    rows = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            s = (x - y // 2) % 32
            if frit and y in (13, 17) and x % 6 == 1:
                rows[y][x] = (190, 200, 204, 255)
            if s in (5, 6) and y > 3:
                rows[y][x] = (222, 234, 238, 255)
    return rows


def tex_mesh():
    """Galvanized welded-wire mesh, cutout: 1 x 3 upright openings (a 2 x 4
    texel cell), wires lit on top and shadowed below."""
    wire = (152, 158, 160, 255)
    dark = (112, 118, 120, 255)
    rows = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            if x % 2 == 0:
                rows[y][x] = wire if y % 4 else dark
            elif y % 4 == 0:
                rows[y][x] = wire
    return rows


def tex_art_glass():
    """Laminated art glass (the ESI mezzanine windows, e.g. 30 Av's geometric
    panels): tilted bands of saturated colour with dark leading lines."""
    rng = random.Random("esi_art")
    palette = [(222, 86, 60), (242, 176, 52), (64, 160, 190), (116, 180, 92),
               (180, 90, 170), (236, 220, 160), (40, 96, 170)]
    rows = K.canvas(32, (0, 0, 0))
    for y in range(32):
        for x in range(32):
            u = x * 0.8 + y * 0.45
            v = x * -0.35 + y * 0.9
            band = int(u // 7) * 3 + int(v // 9)
            c = palette[band % len(palette)]
            edge = (u % 7) < 0.9 or (v % 9) < 0.9
            d = -70 if edge else rng.uniform(-10, 10) + (18 if (x + y) % 13 == 0 else 0)
            K.put(rows, x, y, K.shade(c, d))
    return rows


def tex_led():
    rows = K.canvas(16, (238, 244, 250))
    for y in range(16):
        for x in range(16):
            d = -24 if (x in (0, 15) or y in (0, 15)) else 0
            K.put(rows, x, y, K.shade((238, 244, 250), d))
    return rows


def tex_ceiling_black():
    """ESI mezzanine ceiling: black with exposed-structure texture and a
    recessed linear LED slot every 16 texels."""
    rng = random.Random("esi_ceiling")
    base = (30, 31, 34)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            d = rng.uniform(-3, 3)
            if y % 16 in (7, 8):
                d = 200
            elif y % 16 in (6, 9):
                d = -8
            elif x % 8 == 0:
                d -= 6
            K.put(rows, x, y, K.shade(base, d))
    return rows


def tex_canopy_soffit():
    """Underside of the flat black ESI entrance canopy: black with two LED bars."""
    base = (28, 29, 32)
    rows = K.canvas(32, base)
    for y in range(32):
        for x in range(32):
            d = 0
            if 3 <= x % 16 <= 12 and y % 16 in (7, 8):
                d = 205
            K.put(rows, x, y, K.shade(base, d))
    return rows


def tex_floor_tile():
    """Mid-grey large rectangular tiles in running bond (ESI mezzanine floor):
    8 x 16 texel tiles, 1 px joints, per-tile tone."""
    rng = random.Random("esi_tile")
    base = (132, 134, 136)
    rows = K.canvas(32, base)
    tones = {}
    for y in range(32):
        row = y // 8
        for x in range(32):
            xs = (x + (8 if row % 2 else 0)) % 32
            col = xs // 16
            t = tones.setdefault((row, col), rng.uniform(-9, 9))
            joint = y % 8 == 7 or xs % 16 == 15
            K.put(rows, x, y, K.shade(base, -30 if joint else t + rng.uniform(-3, 3)))
    return rows


def build_textures():
    recolour("el2_green", GREEN, CHARCOAL, "esi_steel")
    recolour("el2_green", GREEN, POLE_GREY, "esi_pole_grey", gain=0.6)
    recolour("el2_green_panel", GREEN, SATIN, "esi_panel_steel", gain=0.7)
    recolour("el2_roof_red", RED, SILVER, "esi_roof", gain=0.8)
    recolour("el2_roof_red", RED, (34, 35, 38), "esi_roof_black", gain=0.6)
    recolour("el2_lattice", GREEN, CHARCOAL, "esi_lattice")
    recolour("el2_lattice_solid", GREEN, CHARCOAL, "esi_lattice_solid")
    recolour("el2_steel", GREEN, CHARCOAL, "esi_struct_steel")
    recolour("el2_pan", GREEN, CHARCOAL, "esi_pan")
    recolour("el2_lace", GREEN, CHARCOAL, "esi_lace")
    recolour("el2_corrugated", (92, 110, 100), (48, 50, 54), "esi_corrugated")
    for name, rows in (("esi_soffit", tex_soffit_wood()), ("esi_panel_black", tex_panel_black()),
                       ("esi_glass", tex_glass()), ("esi_glass_plain", tex_glass(frit=False)),
                       ("esi_mesh", tex_mesh()), ("esi_art_glass", tex_art_glass()),
                       ("esi_led", tex_led()), ("esi_ceiling", tex_ceiling_black()),
                       ("esi_canopy_soffit", tex_canopy_soffit()), ("esi_floor_tile", tex_floor_tile())):
        save(rows_image(rows), name)


# classic texture -> ESI texture, for every ESI block unless its spec overrides
DEFAULT_MAP = {
    "el2_green": "esi_steel", "el2_green_panel": "esi_panel_steel", "el2_roof_red": "esi_roof",
    "el2_soffit": "esi_soffit", "el2_lattice": "esi_lattice", "el2_lattice_solid": "esi_lattice_solid",
    "el2_cream": "esi_panel_black", "el2_cream_ridged": "esi_panel_steel", "el2_wired_glass": "esi_glass",
    "el2_wired_glass_plain": "esi_glass_plain", "el2_lamp": "esi_led", "el2_steel": "esi_struct_steel",
    "el2_pan": "esi_pan", "el2_lace": "esi_lace", "el2_corrugated": "esi_corrugated",
    "el2_ceiling": "esi_ceiling", "el2_troffer": "esi_led", "el2_window_glass": "esi_art_glass",
    "platform_concrete_floor_top_c0_0": "esi_floor_tile",
}


# ---------------------------------------------------------------------------
# ESI-only models (built with the classic builders)
# ---------------------------------------------------------------------------

def esi_model(name, elements, textures, near=False):
    """Write models/block/esi/<name>.json; textures map key -> esi texture name."""
    full = "esi/" + name
    if near:
        K.NEAR_SIDE.add(full)
    K.model(full, elements, {k: "esi/" + v for k, v in textures.items()})
    return full


def lamp_head_l():
    """ESI platform lamp: the grey pole cranked into an L - a square arm out
    over the platform (toward -z, the platform side of the edge-plane pole)
    and a flat rectangular LED head (Astoria line photos)."""
    g, w = "#pole", "#led"
    cz = 14.8
    return [
        K.box(7, 0, 13.8, 9, 10.5, 15.8, g, uv=[0, 0, 2, 10.5], faces=["north", "south", "east", "west"]),
        K.box(7, 9, 4, 9, 10.5, 15.8, g, uv={"up": [7, 4, 9, 16], "down": [7, 4, 9, 16], "east": [4, 5.5, 15.8, 7],
                                            "west": [4, 5.5, 15.8, 7], "south": [0, 5.5, 2, 7]},
              faces=["up", "down", "east", "west", "south"]),
        K.box(4.5, 8.2, -1, 11.5, 9.8, 7, g, uv={"up": [4.5, 0, 11.5, 8], "north": [4.5, 6, 11.5, 7.6],
                                                 "south": [4.5, 6, 11.5, 7.6], "east": [0, 6, 8, 7.6],
                                                 "west": [0, 6, 8, 7.6]},
              faces=["up", "north", "south", "east", "west"]),
        K.box(5, 7.9, -0.5, 11, 8.2, 6.5, w, uv={"down": [0, 0, 16, 16]}, faces=["down"], shade_=False),
    ]


def stack_arm(panel, course):
    """ESI full-height course: black kick curb on the bottom course, a steel
    cap on the top one, and a panel (clear glass / welded mesh) that runs
    edge to edge through every course of a stack - no rails in between."""
    curb = course in ("single", "bottom")
    cap = course in ("single", "top")
    y0 = 3.0 if curb else 0.0
    y1 = 14.8 if cap else 16.0
    els = []
    if curb:
        els.append(K.box(0, 0, 13.8, 16, 3.0, 15.8, "#curb", uv={"north": [0, 13, 16, 16], "south": [0, 13, 16, 16],
                                                                "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]},
                         faces=["north", "south", "up", "down"]))
    if cap:
        els.append(K.box(0, 14.8, 13.8, 16, 16, 15.8, "#green", uv={"north": [0, 4, 16, 5.2], "south": [0, 4, 16, 5.2],
                                                                   "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]},
                         faces=["north", "south", "up", "down"]))
    els.append(K.box(0, y0, 14.2, 16, y1, 15.4, panel, uv={"north": [0, 16 - y1, 16, 16 - y0],
                                                            "south": [16, 16 - y1, 0, 16 - y0]},
                     faces=["north", "south"]))
    return els


COURSES = ("single", "bottom", "middle", "top")


def build_models():
    os.makedirs(ESI_MODELS, exist_ok=True)
    out = {}
    tex = {"green": "esi_steel", "mesh": "esi_mesh"}
    out["mesh_arm"] = esi_model("esi_wall_mesh_arm", K.wall_arm("#mesh"), tex, near=True)
    out["mesh_ret_l"] = esi_model("esi_wall_mesh_return_left", K.wall_return("#mesh"), tex, near=True)
    out["mesh_ret_r"] = esi_model("esi_wall_mesh_return_right",
                                  [K.mirror_x(e) for e in K.wall_return("#mesh")], tex, near=True)
    out["mesh_item"] = esi_model("esi_wall_mesh_item", K.wall_left_post() + K.wall_right_post()
                                 + K.wall_arm("#mesh"), tex, near=True)
    for kind, panel_tex in (("glass", "esi_glass"), ("mesh", "esi_mesh")):
        t = {"green": "esi_steel", "curb": "esi_panel_black", "panel": panel_tex}
        out[kind + "_stack"] = {c: esi_model(f"esi_wall_{kind}_{c}", stack_arm("#panel", c), t, near=True)
                                for c in COURSES}
        out[kind + "_stack_item"] = esi_model(f"esi_wall_{kind}_stack_item", K.wall_left_post() + K.wall_right_post()
                                              + stack_arm("#panel", "single"), t, near=True)
    out["lamp_head"] = esi_model("esi_lamp_head", lamp_head_l(), {"pole": "esi_pole_grey", "led": "esi_led"})
    return out


# ---------------------------------------------------------------------------
# block specs: (esi id, classic id to derive from, extra texture remap,
#               model substitutions classic model -> esi model key, name)
# ---------------------------------------------------------------------------

def specs(m):
    return [
        # platform
        ("esi_roof", "el_roof", {}, {}, "ESI Canopy Roof"),
        ("esi_post", "el_post", {}, {}, "ESI Canopy Post"),
        ("esi_railing", "el_railing", {}, {}, "ESI Platform Railing"),
        ("esi_wall", "el_wall", {}, {}, "ESI Windscreen (Black Panel)"),
        ("esi_wall_glass", "el_wall_glass", {}, {"el_wall_glass_arm": m["glass_stack"],
                                                 "el_wall_glass_item": m["glass_stack_item"]},
         "ESI Windscreen (Clear Glass)"),
        ("esi_wall_mesh", "el_wall", {}, {"el_wall_arm": m["mesh_stack"], "el_wall_return_left": m["mesh_ret_l"],
                                          "el_wall_return_right": m["mesh_ret_r"], "el_wall_item": m["mesh_stack_item"]},
         "ESI Windscreen (Welded Mesh)"),
        ("esi_wall_steel", "el_wall_green", {}, {}, "ESI Windscreen (Satin Steel)"),
        ("esi_wall_doorway", "el_wall_doorway", {}, {}, "ESI Wall Doorway"),
        ("esi_wall_art_glass", "el_wall_window", {}, {}, "ESI Wall (Art Glass Windows)"),
        ("esi_platform_lamp", "el_platform_lamp", {"el2_green": "esi_pole_grey"}, {}, "ESI LED Lamp Pole"),
        ("esi_platform_lamp_head", "el_platform_lamp_head", {}, {"el_lamp_head": m["lamp_head"]}, "ESI LED Lamp Head"),
        ("esi_roof_light", "el_roof_light", {"el2_black": "esi_steel"}, {}, "ESI Linear LED Light"),
        # stairs
        ("esi_stair_railing", "el_stair_railing", {}, {}, "ESI Stair Railing"),
        ("esi_stair_wall", "el_stair_wall", {}, {}, "ESI Stair Wall (Black Panel)"),
        ("esi_stair_wall_glass", "el_stair_wall_glass", {}, {}, "ESI Stair Wall (Clear Glass)"),
        ("esi_stair_open", "el_stair_open", {}, {}, "ESI Stair Posts"),
        ("esi_stair_upper", "el_stair_upper", {}, {}, "ESI Stair Upper Wall"),
        ("esi_stair_roof", "el_stair_roof", {}, {}, "ESI Stair Roof"),
        ("esi_landing_roof", "el_landing_roof", {"el2_lattice_solid": "esi_panel_black", "el2_roof_red": "esi_roof_black",
                                                 "el2_soffit": "esi_canopy_soffit"}, {}, "ESI Entrance Canopy (Flat)"),
        ("esi_stair_landing", "el_stair_landing", {}, {}, "ESI Stair Landing"),
        # structure
        ("esi_street_column", "el_street_column", {}, {}, "ESI Street Column"),
        ("esi_street_column_lattice", "el_street_column_lattice", {}, {}, "ESI Street Column (Lattice)"),
        ("esi_girder_plate", "el_girder_plate", {}, {}, "ESI Plate Girder"),
        ("esi_track_deck", "el_track_deck", {}, {}, "ESI Track Deck"),
        ("esi_plate_deck", "el_plate_deck", {}, {}, "ESI Plate Deck"),
        # mezzanine
        ("esi_mezzanine_floor", "el_mezzanine_floor", {"el2_green": "esi_steel"}, {}, "ESI Mezzanine Floor"),
        ("esi_ceiling", "el_ceiling", {"el2_cream": "esi_panel_steel"}, {}, "ESI Mezzanine Ceiling"),
        ("esi_ceiling_light", "el_ceiling_light", {"el2_cream": "esi_panel_steel"}, {}, "ESI Mezzanine Ceiling (LED)"),
    ]


# ---------------------------------------------------------------------------
# copying
# ---------------------------------------------------------------------------

COPIED = {}


def load_model(name):
    with open(os.path.join(K.MODELS, name + ".json")) as fh:
        return json.load(fh)


def copy_model(name, remap):
    """Copy models/block/<name> into esi/ with its textures remapped. Copies
    that differ only in remap get a hash suffix; identical ones are shared."""
    m = load_model(name)
    used = {}
    for k, v in m.get("textures", {}).items():
        if v.startswith(f"{MOD}:block/"):
            t = v.split("/", 1)[1]
            used[t] = remap.get(t, DEFAULT_MAP.get(t))
    key = (name, tuple(sorted((k, v) for k, v in used.items() if v)))
    if key in COPIED:
        return COPIED[key]
    default_key = tuple(sorted((k, DEFAULT_MAP.get(k)) for k in used if DEFAULT_MAP.get(k)))
    tag = "" if key[1] == default_key else "__" + hashlib.md5(repr(key[1]).encode()).hexdigest()[:6]
    out_name = f"esi/{name}{tag}"
    texs = {}
    for k, v in m.get("textures", {}).items():
        if v.startswith(f"{MOD}:block/"):
            t = v.split("/", 1)[1]
            new = used.get(t)
            texs[k] = f"{MOD}:block/esi/{new}" if new else v
        else:
            texs[k] = v
    m["textures"] = texs
    if "parent" in m and m["parent"].startswith(f"{MOD}:block/"):
        m["parent"] = f"{MOD}:block/" + copy_model(m["parent"].split("/", 1)[1], remap)
    K.write_json(os.path.join(K.MODELS, out_name + ".json"), m)
    COPIED[key] = out_name
    return out_name


def remap_apply(apply, remap, subs):
    def one(a):
        a = dict(a)
        name = a["model"].split("/", 1)[1]
        a["model"] = f"{MOD}:block/" + (subs[name] if name in subs else copy_model(name, remap))
        return a
    return [one(x) for x in apply] if isinstance(apply, list) else one(apply)


def derive_blockstate(esi, classic, remap, subs):
    with open(os.path.join(K.BLOCKSTATES, classic + ".json")) as fh:
        bs = json.load(fh)
    stacked = {k: v for k, v in subs.items() if isinstance(v, dict)}
    flat = {k: v for k, v in subs.items() if not isinstance(v, dict)}
    if "variants" in bs:
        bs["variants"] = {k: remap_apply(v, remap, flat) for k, v in bs["variants"].items()}
    else:
        parts = []
        for part in bs["multipart"]:
            name = part["apply"]["model"].split("/", 1)[1] if isinstance(part["apply"], dict) else None
            if name in stacked:
                for course, model in stacked[name].items():
                    p = json.loads(json.dumps(part))
                    p.setdefault("when", {})["course"] = course
                    p["apply"]["model"] = f"{MOD}:block/{model}"
                    parts.append(p)
                continue
            part["apply"] = remap_apply(part["apply"], remap, flat)
            parts.append(part)
        bs["multipart"] = parts
    K.write_json(os.path.join(K.BLOCKSTATES, esi + ".json"), bs)


ICON_MAP = [  # classic icon colour -> ESI colour (nearest match within a radius)
    ((58, 108, 88), CHARCOAL), ((38, 78, 62), (24, 25, 28)), ((142, 54, 46), SILVER),
    ((214, 206, 186), (40, 42, 46)), ((176, 166, 144), (28, 29, 32)), ((196, 214, 220), (206, 226, 234)),
]


def recolour_icon(src, dst):
    im = Image.open(os.path.join(ITEM_TEX, src + ".png")).convert("RGBA")
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if not a:
                continue
            best = None
            for old, new in ICON_MAP:
                dist = abs(r - old[0]) + abs(g - old[1]) + abs(b - old[2])
                if dist < 70 and (best is None or dist < best[0]):
                    best = (dist, old, new)
            if best:
                _, old, new = best
                d = ((r - old[0]) + (g - old[1]) + (b - old[2])) / 3.0
                px[x, y] = (K.clamp(new[0] + d), K.clamp(new[1] + d), K.clamp(new[2] + d), a)
    os.makedirs(os.path.join(ITEM_TEX, "esi"), exist_ok=True)
    im.save(os.path.join(ITEM_TEX, "esi", dst + ".png"))


def derive_item(esi, classic, remap, subs):
    with open(os.path.join(K.ITEM_MODELS, classic + ".json")) as fh:
        item = json.load(fh)
    if "parent" in item and item["parent"].startswith(f"{MOD}:block/"):
        name = item["parent"].split("/", 1)[1]
        item["parent"] = f"{MOD}:block/" + (subs[name] if name in subs else copy_model(name, remap))
    elif item.get("textures", {}).get("layer0", "").startswith(f"{MOD}:item/"):
        src = item["textures"]["layer0"].split("/", 1)[1]
        recolour_icon(src, esi)
        item["textures"]["layer0"] = f"{MOD}:item/esi/{esi}"
    K.write_json(os.path.join(K.ITEM_MODELS, esi + ".json"), item)


def extend_stairs(block):
    """subway_stairs(_old): add esi_* in-cell sides next to every classic in-cell part."""
    path = os.path.join(K.BLOCKSTATES, block + ".json")
    with open(path) as fh:
        bs = json.load(fh)
    classic = ("stringer", "railing", "wall", "wall_fill")
    parts = []
    for part in bs["multipart"]:
        when = part.get("when", {})
        name = part["apply"]["model"].split("/", 1)[1]
        if name.startswith("subway_stairs"):
            # re-runs: drop the esi_ alternatives a previous run added, then add them again
            for side in ("left", "right"):
                if side in when:
                    when[side] = "|".join(v for v in when[side].split("|") if not v.startswith("esi_"))
        elif any(v.startswith("esi_") for side in ("left", "right") for v in when.get(side, "").split("|")):
            continue                       # a previous run's ESI side part - rebuilt below
        if name.startswith("subway_stairs"):
            # tread models narrow for ANY side: extend the alternatives in place
            for side in ("left", "right"):
                vals = when.get(side, "").split("|")
                if set(vals) & set(classic) and not any(v.startswith("esi_") for v in vals):
                    when[side] = "|".join(vals + ["esi_" + v for v in vals if v in classic])
            parts.append(part)
            continue
        parts.append(part)
        sides = [s for s in ("left", "right") if s in when and set(when[s].split("|")) <= set(classic)]
        if sides:
            dup = json.loads(json.dumps(part))
            for s in sides:
                dup["when"][s] = "|".join("esi_" + v for v in when[s].split("|"))
            dup["apply"] = remap_apply(part["apply"], {}, {})
            parts.append(dup)
    bs["multipart"] = parts
    K.write_json(path, bs)


def update_lang_tags(spec_list):
    with open(LANG) as fh:
        lang = json.load(fh)
    for esi, _c, _r, _s, name in spec_list:
        lang[f"block.station_announcer.{esi}"] = name
    lang["itemGroup.station_announcer.modern"] = "Baker City: Modern Stations (ESI)"
    with open(LANG, "w") as fh:
        json.dump(lang, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    with open(PICKAXE) as fh:
        tag = json.load(fh)
    for esi, *_ in spec_list:
        if f"{MOD}:{esi}" not in tag["values"]:
            tag["values"].append(f"{MOD}:{esi}")
    with open(PICKAXE, "w") as fh:
        json.dump(tag, fh, indent=2)
        fh.write("\n")


def build():
    build_textures()
    m = build_models()
    spec_list = specs(m)
    for esi, classic, remap, subs, _name in spec_list:
        derive_blockstate(esi, classic, remap, subs)
        derive_item(esi, classic, remap, subs)
        K.write_json(os.path.join(K.DATA, "loot_tables/blocks", esi + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{esi}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
        # the classic piece + gray dye = its ESI counterpart (and the kit stays one family)
        K.write_json(os.path.join(K.DATA, "recipes", esi + ".json"), {
            "type": "minecraft:crafting_shapeless", "category": "building",
            "ingredients": [{"item": f"{MOD}:{classic}"}, {"item": "minecraft:gray_dye"}],
            "result": {"item": f"{MOD}:{esi}", "count": 1}})
    extend_stairs("subway_stairs")
    extend_stairs("subway_stairs_old")
    update_lang_tags(spec_list)
    verify(spec_list)
    print(f"esi theme: {len(spec_list)} blocks, {len(COPIED)} copied models")


def verify(spec_list):
    problems = []
    names = [s[0] for s in spec_list] + ["subway_stairs", "subway_stairs_old"]
    for b in names:
        with open(os.path.join(K.BLOCKSTATES, b + ".json")) as fh:
            bs = json.load(fh)
        applies = []
        if "variants" in bs:
            for v in bs["variants"].values():
                applies += v if isinstance(v, list) else [v]
        else:
            for p in bs["multipart"]:
                applies += p["apply"] if isinstance(p["apply"], list) else [p["apply"]]
        for a in applies:
            name = a["model"].split("/", 1)[1]
            p = os.path.join(K.MODELS, name + ".json")
            if not os.path.exists(p):
                problems.append(f"{b}: missing model {name}")
                continue
            with open(p) as fh:
                mm = json.load(fh)
            for k, v in mm.get("textures", {}).items():
                if v.startswith(f"{MOD}:block/"):
                    t = v.split("/", 1)[1]
                    if not os.path.exists(os.path.join(K.TEXTURES, t + ".png")):
                        problems.append(f"{name}: missing texture {t}")
    if problems:
        for p in problems[:40]:
            print("ESI VERIFY:", p)
        sys.exit(1)


if __name__ == "__main__":
    build()
