#!/usr/bin/env python3
"""Regenerates the zebra-board textures, models and blockstates.

Run from anywhere:  python3 tools/gen_zebra_assets.py

WHAT A ZEBRA BOARD IS
---------------------
The striped board a NYC conductor points at to confirm the train is berthed
correctly. Two variants ship here:

  zebra_board_wall     - bolted flat to the wall behind it (photo: 181 St)
  zebra_board_hanging  - slung under the ceiling on a PIDS drop pole

Both merge: boards placed side by side along their run axis join into one
continuous beam (LEFT/RIGHT booleans, exactly like the bench), and only the
free ends draw an end plate. On the hanging variant the end blocks also draw
the hanger pole, so a run reads as one beam on two poles.

MODEL SPACE
-----------
Everything is authored for FACING=north (board face toward -Z, run along X)
and rotated by the blockstate. The wall variant hugs the wall behind it at
z=16 - the same wall-side convention the PIDS and fare machine use.

Z-FIGHTING RULES (the project's hard-won ones, applied here)
------------------------------------------------------------
1. The beam parts span the full block so a run is continuous, and therefore
   NEVER draw their east/west end faces: when connected those faces would sit
   in the same world plane as the neighbour's, and when not connected the end
   plate covers them. (Same as the conduit pipe's arms.)
2. Only the end plate may touch x=0 / x=16 - it is this block's "post".
3. The panel's up/down faces are omitted: the frame rails are deeper than the
   panel, so those faces are always buried under a rail.
4. The pole's down face is omitted (it sits on the top rail's up face) and its
   up face carries cullface:up so it is dropped against the ceiling block.
5. Every face flush with the wall behind carries cullface:south.

UV RULES
--------
Beam parts use vanilla auto-UV (1:1 texels, the iteration-6 lesson). The end
plate is the "one texture = one whole face" case, so its faces carry explicit
uv slices - and they are explicit on ALL six faces because auto-UV would sample
the bolted-plate art on the 1 px rim faces. Art lives at cols 10..16 rows 3..12
(6x10 texels for a 6x10 model-pixel face); the plain rim region is cols 0..6.
"""

import json
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
MODELS = os.path.join(ASSETS, "models/block")
ITEM_MODELS = os.path.join(ASSETS, "models/item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
TEXTURES = os.path.join(ASSETS, "textures/block")

MOD = "station_announcer"
STRIPES = f"{MOD}:block/zebra_stripes"
FRAME = f"{MOD}:block/pids_frame"     # flat 26,26,28 - the same dark steel the PIDS frames use
CAP = f"{MOD}:block/zebra_cap"
POLE = f"{MOD}:block/pids_pole"

# ----------------------------------------------------------------- geometry

RAIL_TOP = (12.0, 13.0)      # y span of the top frame rail
PANEL_Y = (6.0, 12.0)        # y span of the striped panel
RAIL_BOTTOM = (5.0, 6.0)     # y span of the bottom frame rail

PLATE_Y = (4.0, 14.0)        # end plate: 1 px proud of the rails top and bottom
PLATE_THICK = 1.0            # how far the plate reaches into the block

POLE_XZ = (7.0, 9.0)         # matches the hanging PIDS's ceiling stub exactly
POLE_Y = (13.0, 16.0)

# depth spans, per variant: (rail, panel, plate)
DEPTHS = {
    "hanging": ((6.0, 10.0), (6.5, 9.5), (5.0, 11.0)),
    # the wall variant's parts all run back to z=16 and are culled there
    "wall": ((12.0, 16.0), (12.5, 16.0), (10.0, 16.0)),
}

# explicit uv slices for the end plate (see UV RULES above)
UV_PLATE_ART = [10, 3, 16, 13]   # 6 x 10 - the bolted plate face
UV_PLATE_BACK = [0, 0, 6, 10]    # 6 x 10 - plain, the ring around the beam
UV_PLATE_SIDE = [0, 0, 1, 10]    # 1 x 10 - plate thickness, front/back rim
UV_PLATE_EDGE = [0, 0, 1, 6]     # 1 x 6 - plate thickness, top/bottom rim


def face(texture, uv=None, cullface=None):
    """`texture` is a "#key" reference into the model's texture map."""
    f = {"texture": texture}
    if uv is not None:
        f["uv"] = uv
    if cullface is not None:
        f["cullface"] = cullface
    return f


def element(frm, to, faces):
    return {"from": list(frm), "to": list(to), "faces": faces}


def wall_cull(variant):
    """The wall variant's back faces sit on z=16 and are dropped by the wall."""
    return "south" if variant == "wall" else None


def beam_elements(variant):
    rail_z, panel_z, _ = DEPTHS[variant]
    cull = wall_cull(variant)

    def rail(y0, y1):
        faces = {
            "north": face("#frame"),
            "up": face("#frame"),
            "down": face("#frame"),
            "south": face("#frame", cullface=cull),
        }
        return element((0, y0, rail_z[0]), (16, y1, rail_z[1]), faces)

    panel = element((0, PANEL_Y[0], panel_z[0]), (16, PANEL_Y[1], panel_z[1]), {
        "north": face("#stripes"),
        "south": face("#stripes", cullface=cull),
    })
    return [rail(*RAIL_BOTTOM), panel, rail(*RAIL_TOP)]


def plate_element(variant, side):
    """The bolted end plate. `side` is "left" (x=0) or "right" (x=16)."""
    _, _, z = DEPTHS[variant]
    x0, x1 = (0.0, PLATE_THICK) if side == "left" else (16.0 - PLATE_THICK, 16.0)
    art, back = ("west", "east") if side == "left" else ("east", "west")
    faces = {
        art: face("#cap", uv=UV_PLATE_ART),
        back: face("#cap", uv=UV_PLATE_BACK),
        "north": face("#cap", uv=UV_PLATE_SIDE),
        "south": face("#cap", uv=UV_PLATE_SIDE, cullface=wall_cull(variant)),
        "up": face("#cap", uv=UV_PLATE_EDGE),
        "down": face("#cap", uv=UV_PLATE_EDGE),
    }
    return element((x0, PLATE_Y[0], z[0]), (x1, PLATE_Y[1], z[1]), faces)


def pole_element():
    return element((POLE_XZ[0], POLE_Y[0], POLE_XZ[0]), (POLE_XZ[1], POLE_Y[1], POLE_XZ[1]), {
        "north": face("#pole"),
        "south": face("#pole"),
        "east": face("#pole"),
        "west": face("#pole"),
        "up": face("#pole", cullface="up"),
        # no down face: it sits flush on the top rail and is never visible
    })


def model(textures, elements):
    return {
        "parent": "minecraft:block/block",
        "textures": dict(textures, particle=FRAME),
        "elements": elements,
    }


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


# ----------------------------------------------------------------- textures

def write_png(path, pixels):
    """Minimal RGBA PNG writer (no pillow on this machine)."""
    height = len(pixels)
    width = len(pixels[0])
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in pixels)
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def gen_stripes():
    """Diagonal zebra stripes.

    The horizontal period is 4 px and the block is 16 px wide, so the pattern
    is continuous across the seam between two boards. The bars run at a true
    45 degrees (one pixel per row): a shallower slope only steps every other
    row, which at this size reads as a chunky zigzag rather than a stripe.
    One px of black per 4 px period, not two: Minecraft shades the vertical
    faces down, so an even split renders as a black board with white flecks
    instead of the white board with black slashes the photos show.
    Shading varies only VERTICALLY - a horizontal gradient would visibly
    restart at every block.
    """
    white = (0xF1, 0xF0, 0xEA)
    black = (0x19, 0x19, 0x1B)
    rows = []
    for y in range(16):
        # top-lit: brightest just under the top rail, dusty toward the bottom
        shade = -6 + int(round(12 * y / 15.0))
        row = []
        for x in range(16):
            base = black if ((x + y) % 4) < 1 else white
            row.append(tuple(max(0, min(255, c - shade)) for c in base) + (255,))
        rows.append(row)
    write_png(os.path.join(TEXTURES, "zebra_stripes.png"), rows)


def gen_cap():
    """The bolted end plate. Art at cols 10..15 / rows 3..11, rest plain."""
    base = (0x24, 0x24, 0x28)
    fill = (0x2C, 0x2C, 0x31)
    lit = (0x3C, 0x3C, 0x42)
    dark = (0x15, 0x15, 0x17)
    bolt = (0x6E, 0x6E, 0x76)
    rows = [[base + (255,) for _ in range(16)] for _ in range(16)]
    for y in range(3, 13):
        for x in range(10, 16):
            if y == 3 or x == 10:
                c = lit          # bevel catching the light
            elif y == 12 or x == 15:
                c = dark         # shadowed lower/right bevel
            else:
                c = fill
            rows[y][x] = c + (255,)
    for by in (5, 10):
        for bx in (11, 14):
            rows[by][bx] = bolt + (255,)
            rows[by + 1][bx] = dark + (255,)   # bolt shadow
    write_png(os.path.join(TEXTURES, "zebra_cap.png"), rows)


# -------------------------------------------------------------- blockstates

FACING_ROTATION = {"north": 0, "east": 90, "south": 180, "west": 270}


def blockstate(variant):
    name = f"zebra_board_{variant}"
    parts = []
    for piece, condition in (("beam", None), ("cap_left", "left"), ("cap_right", "right")):
        for facing, y in FACING_ROTATION.items():
            when = {"facing": facing}
            if condition is not None:
                when[condition] = "false"
            apply = {"model": f"{MOD}:block/{name}_{piece}"}
            if y:
                apply["y"] = y
            parts.append({"when": when, "apply": apply})
    if variant == "hanging":
        # One pole per end block, and only ONE selector: a lone board has both
        # ends free, and two copies of the same model would z-fight.
        parts.append({
            "when": {"OR": [{"left": "false"}, {"right": "false"}]},
            "apply": {"model": f"{MOD}:block/{name}_pole"},
        })
    return {"multipart": parts}


# --------------------------------------------------------------------- main

def main():
    gen_stripes()
    gen_cap()

    for variant in ("wall", "hanging"):
        name = f"zebra_board_{variant}"
        textures = {"stripes": STRIPES, "frame": FRAME, "cap": CAP}
        pole_textures = dict(textures, pole=POLE)

        write_json(os.path.join(MODELS, f"{name}_beam.json"),
                   model(textures, beam_elements(variant)))
        write_json(os.path.join(MODELS, f"{name}_cap_left.json"),
                   model(textures, [plate_element(variant, "left")]))
        write_json(os.path.join(MODELS, f"{name}_cap_right.json"),
                   model(textures, [plate_element(variant, "right")]))

        # The inventory model is the whole assembly: beam, both plates and
        # (hanging only) the pole, since a single multipart piece reads as junk.
        whole = beam_elements(variant) + [plate_element(variant, "left"),
                                          plate_element(variant, "right")]
        if variant == "hanging":
            write_json(os.path.join(MODELS, f"{name}_pole.json"),
                       model(pole_textures, [pole_element()]))
            whole = whole + [pole_element()]
        write_json(os.path.join(MODELS, f"{name}.json"),
                   model(pole_textures if variant == "hanging" else textures, whole))

        write_json(os.path.join(ITEM_MODELS, f"{name}.json"),
                   {"parent": f"{MOD}:block/{name}"})
        write_json(os.path.join(BLOCKSTATES, f"{name}.json"), blockstate(variant))

    print("zebra assets written")


if __name__ == "__main__":
    main()
