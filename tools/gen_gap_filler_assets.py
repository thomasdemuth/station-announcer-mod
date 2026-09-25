#!/usr/bin/env python3
"""Gap fillers (2026-09-25): every texture, model, blockstate, item model,
loot table, recipe and sound for `gap_filler` (14 St-Union Square style) and
`gap_filler_loop` (old South Ferry loop style).

Run from anywhere:  python3 tools/gen_gap_filler_assets.py [--preview DIR]

Never hand-edit the output. The shared files (sounds.json, en_us.json, the
pickaxe tag) get their entries inserted as TEXT, idempotently, so other
features' formatting and ordering there are never touched.

Frame: authored NORTH = track side (model -z), like every facing block here;
the blockstate rotates by `facing`. The block model is the STATIC body only:
concrete below, a slot for the plate, the deck above it with the platform
edge's own tactile strip (so fillers sit in a run of `platform_edge` without a
seam). The moving plate is GapFillerRenderer's; its sprite layout is fixed
there (rows 0-1 rubber nosing, 2-7 painted edge, 8-23 an 8 px deck tile,
24-27 side steel, 28-31 bumper face; 2 texels per px across).

Geometry contract with GapFillerBlock.java:
  plate top at rest y 15 (deck y 15..16), 2 px thick, reach 2..24 px,
  loop plate drops 1 px per 8 px of travel (max 3 px) -> its slot runs down to y 10.
Contracts honoured: explicit uv on every face inside 0..16, no two
elements sharing a plane unless the buried face is omitted, square textures,
RGBA pixels (pngtool writes px[:4]).
"""

import argparse
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data")
MOD = "station_announcer"

BLOCKS = ["gap_filler", "gap_filler_loop"]
PROPS = {"facing": {"north", "east", "south", "west"},
         "reach": {str(i) for i in range(1, 13)},
         "phase": {"retracted", "extending", "extended", "retracting"}}

CONCRETE_TOP = f"{MOD}:block/platform_concrete_floor_top_c0_0"
CONCRETE_SIDE = f"{MOD}:block/platform_concrete_floor_side"
STRIP = f"{MOD}:block/platform_edge_strip"

# ---------------------------------------------------------------- palette --
RUBBER = (22, 22, 24, 255)
RUBBER_LIT = (46, 46, 50, 255)
YELLOW = (236, 188, 22, 255)
YELLOW_WORN = (196, 156, 30, 255)
YELLOW_DARK = (150, 116, 18, 255)
GRATE_GAP = (30, 31, 34, 255)
GRATE_BAR = (156, 158, 162, 255)
GRATE_BAR_LIT = (190, 192, 196, 255)
GRATE_CROSS = (120, 122, 126, 255)
STEEL_SIDE = (104, 106, 110, 255)
STEEL_SIDE_LIT = (128, 130, 134, 255)
OLD_PLATE = (92, 88, 82, 255)
OLD_PLATE_LIT = (120, 114, 104, 255)
OLD_PLATE_DARK = (66, 62, 58, 255)
RUST = (112, 70, 44, 255)
WHITE_PAINT = (222, 218, 204, 255)
WHITE_WORN = (184, 178, 162, 255)
SLOT = (30, 31, 34, 255)
SLOT_LIT = (48, 50, 54, 255)
RAIL = (132, 134, 138, 255)
RAIL_LIT = (170, 172, 176, 255)
FASCIA = (150, 152, 156, 255)
FASCIA_LIT = (186, 188, 192, 255)
BOLT = (92, 94, 98, 255)


def tex_path(name):
    return os.path.join(ASSETS, "textures/block", name + ".png")


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


# ---------------------------------------------------------------- textures --

def plate_union():
    rows = pk.canvas(32, 32, GRATE_GAP)
    # rubber nosing, lit top row
    pk.hband(rows, 0, 2, RUBBER)
    pk.hline(rows, 0, RUBBER_LIT)
    # yellow safety edge with worn flecks
    rnd = random.Random(7)
    pk.hband(rows, 2, 8, YELLOW)
    pk.hline(rows, 7, YELLOW_DARK)
    for _ in range(18):
        x, y = rnd.randrange(32), rnd.randrange(2, 7)
        rows[y][x] = YELLOW_WORN
    # grate: bearing bars run along the plate's travel (v), cross bars every 4 px
    for y in range(8, 24):
        for x in range(32):
            if x % 4 in (1, 2):
                rows[y][x] = GRATE_BAR_LIT if x % 4 == 1 else GRATE_BAR
            elif (y - 8) % 8 in (0, 1):
                rows[y][x] = GRATE_CROSS
            else:
                rows[y][x] = GRATE_GAP
    # side steel
    pk.hband(rows, 24, 28, STEEL_SIDE)
    pk.hline(rows, 24, STEEL_SIDE_LIT)
    # bumper face
    pk.hband(rows, 28, 32, RUBBER)
    pk.hline(rows, 29, RUBBER_LIT)
    return rows


def plate_loop():
    rows = pk.canvas(32, 32, OLD_PLATE)
    pk.hband(rows, 0, 2, RUBBER)
    pk.hline(rows, 0, RUBBER_LIT)
    # the loop's painted white edge, worn and rust-spotted
    rnd = random.Random(11)
    pk.hband(rows, 2, 8, WHITE_PAINT)
    pk.hline(rows, 7, WHITE_WORN)
    for _ in range(22):
        x, y = rnd.randrange(32), rnd.randrange(2, 7)
        rows[y][x] = WHITE_WORN if rnd.random() < 0.7 else RUST
    # riveted diamond plate, 8 px (16 row) tile
    for y in range(8, 24):
        for x in range(32):
            lozenge = ((x // 4) + (y - 8) // 4) % 2 == 0 and x % 4 in (1, 2) and (y - 8) % 4 in (1, 2)
            rows[y][x] = OLD_PLATE_LIT if lozenge else OLD_PLATE
        if (y - 8) % 8 == 3:
            for x in (1, 30):
                rows[y][x] = OLD_PLATE_DARK
    for _ in range(26):
        x, y = rnd.randrange(32), rnd.randrange(8, 24)
        rows[y][x] = RUST if rnd.random() < 0.4 else OLD_PLATE_DARK
    pk.hband(rows, 24, 28, OLD_PLATE_DARK)
    pk.hline(rows, 24, OLD_PLATE)
    pk.hband(rows, 28, 32, RUBBER)
    pk.hline(rows, 29, RUBBER_LIT)
    return rows


def slot_tex():
    """Slot interior, 16 px: runner rails along z on the floor, dark elsewhere."""
    rows = pk.canvas(16, 16, SLOT)
    for y in range(16):
        for x in (3, 12):
            rows[y][x] = RAIL_LIT
            rows[y][x + 1] = RAIL
        if y % 4 == 0:
            for x in range(16):
                if rows[y][x] == SLOT:
                    rows[y][x] = SLOT_LIT
    return rows


def fascia_tex(loop):
    """The deck's nosing (a 1 px tall face; row 0 is what it samples) and the cheeks."""
    base, lit = (WHITE_PAINT, WHITE_WORN) if loop else (FASCIA, FASCIA_LIT)
    rows = pk.canvas(16, 16, base)
    pk.hline(rows, 0, lit)
    for x in (2, 13):
        rows[0][x] = BOLT
    # rows 1..15: the cheeks' 0.25 px front edges sample column 0 of these
    for y in range(1, 16):
        for x in range(16):
            rows[y][x] = STEEL_SIDE if (x + y) % 5 else STEEL_SIDE_LIT
    return rows


def write_textures():
    pngtool.write_png(tex_path("gap_filler_plate"), plate_union())
    pngtool.write_png(tex_path("gap_filler_plate_loop"), plate_loop())
    pngtool.write_png(tex_path("gap_filler_slot"), slot_tex())
    pngtool.write_png(tex_path("gap_filler_fascia"), fascia_tex(False))
    pngtool.write_png(tex_path("gap_filler_fascia_loop"), fascia_tex(True))


# ------------------------------------------------------------------ models --

def face(tex, uv, cull=None):
    f = {"uv": [round(v, 4) for v in uv], "texture": tex}
    if cull:
        f["cullface"] = cull
    return f


def box(frm, to, faces):
    return {"from": frm, "to": to, "faces": faces}


def body_elements(loop):
    """The static body. Slot y SLOT_LO..15, deck 15..16, strip on top."""
    lo = 10 if loop else 12
    els = []
    # concrete below the slot; its top is the slot floor with the runner rails
    els.append(box([0, 0, 0], [16, lo, 16], {
        "down": face("#side", [0, 0, 16, 16], "down"),
        "up": face("#slot", [0, 0, 16, 16]),
        "north": face("#side", [0, 16 - lo, 16, 16]),
        "south": face("#side", [0, 16 - lo, 16, 16], "south"),
        "east": face("#side", [0, 16 - lo, 16, 16], "east"),
        "west": face("#side", [0, 16 - lo, 16, 16], "west"),
    }))
    # deck over the slot: steel nosing at the edge, slot ceiling underneath
    els.append(box([0, 15, 0], [16, 16, 16], {
        "up": face("#top", [0, 0, 16, 16]),
        "down": face("#slot", [0, 0, 16, 16]),
        "north": face("#fascia", [0, 0, 16, 1]),
        "south": face("#side", [0, 0, 16, 1], "south"),
        "east": face("#side", [0, 0, 16, 1], "east"),
        "west": face("#side", [0, 0, 16, 1], "west"),
    }))
    # slot cheeks: 0.25 px steel, so a run reads as separate sections and an
    # end block closes its slot. Up/down faces are buried in deck/base: omitted.
    h = 15 - lo
    for x0, x1, inner, outer in ((0, 0.25, "east", "west"), (15.75, 16, "west", "east")):
        els.append(box([x0, lo, 0], [x1, 15, 16], {
            inner: face("#slot", [0, 1, 16, 1 + h]),
            outer: face("#side", [0, 1, 16, 1 + h], outer),
            "north": face("#fascia", [0, 2, 0.25, 2 + h]),
        }))
    # back of the slot, 0.2 short of the block face (the neighbour's face is AT 16)
    els.append(box([0.25, lo, 15.8], [15.75, 15, 16], {
        "north": face("#slot", [0.25, 1, 15.75, 1 + h]),
        "south": face("#side", [0.25, 1, 15.75, 1 + h], "south"),
    }))
    # the platform edge's own tactile strip, identical to platform_edge_strip
    els.append(box([0, 16, 0], [16, 16.5, 10], {
        "up": face("#strip", [0, 0, 16, 10]),
        "north": face("#strip", [0, 10, 16, 10.5]),
        "south": face("#strip", [0, 10, 16, 10.5]),
        "east": face("#strip", [0, 10, 10, 10.5]),
        "west": face("#strip", [0, 10, 10, 10.5]),
    }))
    return els


def body_model(loop):
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": CONCRETE_SIDE,
            "top": CONCRETE_TOP,
            "side": CONCRETE_SIDE,
            "slot": f"{MOD}:block/gap_filler_slot",
            "fascia": f"{MOD}:block/gap_filler_fascia" + ("_loop" if loop else ""),
            "strip": STRIP,
            "plate": f"{MOD}:block/gap_filler_plate" + ("_loop" if loop else ""),
        },
        "elements": body_elements(loop),
    }


def item_model(loop):
    """The body with its plate half out, so the icon reads as a gap filler."""
    model = body_model(loop)
    top = 15 - (0.75 if loop else 0)
    model["elements"] = model["elements"] + [box([0.3, top - 2, -6], [15.7, top, 10], {
        "up": face("#plate", [0.15, 0, 15.85, 8]),
        "north": face("#plate", [0, 14, 16, 16]),
        "east": face("#plate", [0, 12, 8, 14]),
        "west": face("#plate", [0, 12, 8, 14]),
        "down": face("#plate", [0, 12, 16, 14]),
    })]
    model["display"] = {
        "gui": {"rotation": [30, 135, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
    }
    return model


def write_models():
    for name, loop in (("gap_filler", False), ("gap_filler_loop", True)):
        write_json(os.path.join(ASSETS, "models/block", name + ".json"), body_model(loop))
        write_json(os.path.join(ASSETS, "models/item", name + ".json"), item_model(loop))
        parts = []
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            apply = {"model": f"{MOD}:block/{name}"}
            if y:
                apply["y"] = y
            parts.append({"when": {"facing": facing}, "apply": apply})
        write_json(os.path.join(ASSETS, "blockstates", name + ".json"), {"multipart": parts})


# --------------------------------------------------------- loot + recipes --

def write_data():
    for name in BLOCKS:
        write_json(os.path.join(DATA, MOD, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{
                "rolls": 1,
                "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }],
        })
    # Union Square: a platform edge on a hydraulic ram (piston).
    write_json(os.path.join(DATA, MOD, "recipes", "gap_filler.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "redstone",
        "key": {"E": {"item": f"{MOD}:platform_edge"}, "P": {"item": "minecraft:piston"},
                "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["IEI", " P "],
        "result": {"item": f"{MOD}:gap_filler", "count": 2},
    })
    # South Ferry: a platform edge on sloping rails.
    write_json(os.path.join(DATA, MOD, "recipes", "gap_filler_loop.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "redstone",
        "key": {"E": {"item": f"{MOD}:platform_edge"}, "R": {"item": "minecraft:rail"},
                "I": {"item": "minecraft:iron_ingot"}},
        "pattern": ["IEI", " R "],
        "result": {"item": f"{MOD}:gap_filler_loop", "count": 2},
    })


# ------------------------------------------------------------------ sounds --

def write_sounds():
    import numpy as np
    import soundfile as sf

    rate = 44100
    rng = np.random.default_rng(5)

    def noise(n):
        return rng.standard_normal(n)

    def lowpass(x, alpha):
        y = np.empty_like(x)
        acc = 0.0
        for i, v in enumerate(x):
            acc += alpha * (v - acc)
            y[i] = acc
        return y

    def env(n, attack, release):
        e = np.ones(n)
        a = int(attack * rate)
        r = int(release * rate)
        e[:a] = np.linspace(0, 1, a)
        e[n - r:] = np.linspace(1, 0, r)
        return e

    def save(name, x):
        x = x / (np.max(np.abs(x)) + 1e-9) * 0.9
        sf.write(os.path.join(ASSETS, "sounds", name + ".ogg"), x.astype(np.float32), rate,
                 format="OGG", subtype="VORBIS")

    # Union Square: a hydraulic ram — pump whine climbing a little under load,
    # a hiss of fluid, a latch clack at the start.
    n = int(2.4 * rate)
    t = np.arange(n) / rate
    freq = 150 + 30 * np.clip(t / 2.0, 0, 1)
    phase = 2 * np.pi * np.cumsum(freq) / rate
    whine = np.sin(phase) + 0.45 * np.sin(2 * phase) + 0.2 * np.sin(3 * phase + 0.4)
    hiss = lowpass(noise(n), 0.25) - lowpass(noise(n), 0.02)
    clack = np.zeros(n)
    k = int(0.04 * rate)
    clack[:k] = noise(k) * np.exp(-np.arange(k) / (0.006 * rate))
    save("gap_filler_hydraulic", (0.55 * whine + 0.5 * hiss) * env(n, 0.08, 0.25) + 0.9 * clack)

    # South Ferry: sections rolling down sloping rails — a low rumble with roller
    # clicks that speed up as gravity takes it.
    n = int(3.0 * rate)
    t = np.arange(n) / rate
    rumble = lowpass(noise(n), 0.01) * 6
    clicks = np.zeros(n)
    pos = 0.0
    interval = 0.22
    while pos < 2.8:
        i = int(pos * rate)
        m = min(n - i, int(0.02 * rate))
        clicks[i:i + m] += noise(m) * np.exp(-np.arange(m) / (0.003 * rate)) * 0.8
        pos += interval
        interval = max(0.07, interval * 0.9)
    rattle = (lowpass(noise(n), 0.3) - lowpass(noise(n), 0.05)) * 0.25 * np.clip(t / 2.5, 0.2, 1)
    save("gap_filler_roll", (rumble + clicks + rattle) * env(n, 0.15, 0.3))

    # The bang: steel section slamming home against its stop / the car side.
    n = int(0.9 * rate)
    t = np.arange(n) / rate
    thump = np.sin(2 * np.pi * 70 * t) * np.exp(-t / 0.09)
    ring = sum(a * np.sin(2 * np.pi * f * t) * np.exp(-t / d)
               for f, a, d in ((420, 0.5, 0.25), (1130, 0.35, 0.18), (1870, 0.25, 0.12), (2760, 0.15, 0.08)))
    burst = noise(n) * np.exp(-t / 0.015)
    save("gap_filler_bang", 1.2 * thump + 0.6 * ring + 0.7 * burst)


# ------------------------------------------------- shared files (text insert) --

def insert_before_last_brace(path, key_marker, text):
    """Insert `text` (already indented JSON members, leading comma handled here)
    before the file's final closing brace/bracket unless `key_marker` is present."""
    with open(path) as fh:
        src = fh.read()
    if key_marker in src:
        return False
    end = src.rstrip().rfind("}")
    head = src[:end].rstrip()
    new = head + ",\n" + text + "\n" + src[end:]
    with open(path, "w") as fh:
        fh.write(new)
    json.loads(new)  # must still parse
    return True


def insert_into_tag(path, ids):
    with open(path) as fh:
        src = fh.read()
    missing = [i for i in ids if f'"{i}"' not in src]
    if not missing:
        return
    end = src.rfind("]")
    head = src[:end].rstrip()
    new = head + ",\n" + ",\n".join(f'    "{i}"' for i in missing) + "\n  " + src[end:]
    json.loads(new)
    with open(path, "w") as fh:
        fh.write(new)


LANG = {
    "block.station_announcer.gap_filler": "Gap Filler (Union Square)",
    "block.station_announcer.gap_filler_loop": "Gap Filler (South Ferry Loop)",
    "subtitles.station_announcer.gap_filler_move": "Gap filler moves",
    "subtitles.station_announcer.gap_filler_bang": "Gap filler slams",
    "gui.station_announcer.gap_filler.title": "Gap Filler",
    "gui.station_announcer.gap_filler.style_union": "Union Square style",
    "gui.station_announcer.gap_filler.style_loop": "South Ferry loop style",
    "gui.station_announcer.gap_filler.platform": "Platform",
    "gui.station_announcer.gap_filler.unlinked": "Not linked: no platform track in front of this edge",
    "gui.station_announcer.gap_filler.relink": "Relink",
    "gui.station_announcer.gap_filler.plate": "Plate",
    "gui.station_announcer.gap_filler.reach": "Reach",
    "gui.station_announcer.gap_filler.auto": "Auto",
    "gui.station_announcer.gap_filler.timing": "Timing (every filler on this platform)",
    "gui.station_announcer.gap_filler.extend": "Extend",
    "gui.station_announcer.gap_filler.retract": "Retract",
    "gui.station_announcer.gap_filler.min_dwell": "Minimum dwell",
    "gui.station_announcer.gap_filler.off": "off",
    "gui.station_announcer.gap_filler.summary": "Doors open after %s. Shortest stop here: %s",
    "gui.station_announcer.gap_filler.save": "Save",
    "msg.station_announcer.gap_filler.no_platform": "Gap filler: no MTR platform track in front of this edge",
    "msg.station_announcer.gap_filler.linked": "Gap filler linked to %s (reach %s px)",
}


def write_shared():
    sounds = {
        "gap_filler_hydraulic": "subtitles.station_announcer.gap_filler_move",
        "gap_filler_roll": "subtitles.station_announcer.gap_filler_move",
        "gap_filler_bang": "subtitles.station_announcer.gap_filler_bang",
    }
    members = []
    for name, subtitle in sounds.items():
        members.append(f'  "{name}": {{\n    "category": "block",\n    "subtitle": "{subtitle}",\n'
                       f'    "sounds": [\n      "{MOD}:{name}"\n    ]\n  }}')
    insert_before_last_brace(os.path.join(ASSETS, "sounds.json"), '"gap_filler_hydraulic"', ",\n".join(members))

    lang = ",\n".join(f"  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}" for k, v in LANG.items())
    insert_before_last_brace(os.path.join(ASSETS, "lang/en_us.json"), '"block.station_announcer.gap_filler"', lang)

    insert_into_tag(os.path.join(DATA, "minecraft/tags/blocks/mineable/pickaxe.json"),
                    [f"{MOD}:{b}" for b in BLOCKS])


# ------------------------------------------------------------------ verify --

def verify():
    problems = []
    for name in BLOCKS:
        bs = json.load(open(os.path.join(ASSETS, "blockstates", name + ".json")))
        for part in bs["multipart"]:
            for key, value in part["when"].items():
                if key not in PROPS or value not in PROPS[key]:
                    problems.append(f"{name}: when {key}={value} not a Java property value")
        for kind in ("block", "item"):
            model = json.load(open(os.path.join(ASSETS, "models", kind, name + ".json")))
            for el in model["elements"]:
                for fname, f in el["faces"].items():
                    uv = f.get("uv")
                    if uv is None or any(v < 0 or v > 16 for v in uv):
                        problems.append(f"{name} {kind}: face {fname} uv {uv} outside 0..16")
    for tex in ("gap_filler_plate", "gap_filler_plate_loop", "gap_filler_slot",
                "gap_filler_fascia", "gap_filler_fascia_loop"):
        if not os.path.exists(tex_path(tex)):
            problems.append(f"missing texture {tex}")
    for snd in ("gap_filler_hydraulic", "gap_filler_roll", "gap_filler_bang"):
        if not os.path.exists(os.path.join(ASSETS, "sounds", snd + ".ogg")):
            problems.append(f"missing sound {snd}")
    json.load(open(os.path.join(ASSETS, "sounds.json")))
    json.load(open(os.path.join(ASSETS, "lang/en_us.json")))
    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print("gap filler assets verified")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-sounds", action="store_true", help="skip re-synthesizing the ogg files")
    args = ap.parse_args()
    write_textures()
    write_models()
    write_data()
    if not args.no_sounds:
        write_sounds()
    write_shared()
    verify()


if __name__ == "__main__":
    main()
