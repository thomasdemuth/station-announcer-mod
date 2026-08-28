#!/usr/bin/env python3
"""Regenerates the fare-control gate textures: the emergency exit door and the
two ornate dividing walls.

Run from anywhere:  python3 tools/gen_gate_assets.py [--preview DIR]

All three are CUTOUT textures — the ironwork is solid, everything between the
bars is a real hole, so you see the mezzanine through them exactly as in the
photos. That means they need getCutoutMipped() at registration, like the
platform barrier's mesh.

The two walls share the same frame (posts at the block edges, top and bottom
rails at the same heights) so a run of scrollwork can meet a run of grille and
the ironwork lines up across the joint.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool
import pixel_kit as pk

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
TEXTURES = os.path.join(ROOT, "src/main/resources/assets/station_announcer/textures/block")

N = 64                       # wall textures (32 quantised the scrollwork into blobs)
DOOR = 64                    # the door carries lettering, so it needs the room

IRON = (26, 27, 25, 255)     # the near-black the whole MTA gate family is painted
IRON_LIT = (58, 60, 56, 255) # top-lit edge, so bars read as round-ish
IRON_DARK = (12, 12, 11, 255)
RED = (196, 26, 30, 255)
RED_DARK = (150, 16, 20, 255)
WHITE = (240, 240, 238, 255)
STEEL = (168, 170, 172, 255)
STEEL_LIT = (214, 216, 218, 255)
HOLE = (0, 0, 0, 0)


def canvas(size):
    return [[HOLE] * size for _ in range(size)]


def vbar(rows, x, y0, y1, w=2):
    """A vertical bar with a lit left edge."""
    for y in range(y0, y1):
        for i in range(w):
            rows[y][x + i] = IRON_LIT if i == 0 else IRON


def hbar(rows, y, x0, x1, h=2):
    for j in range(h):
        for x in range(x0, x1):
            rows[y + j][x] = IRON_LIT if j == 0 else IRON


def frame(rows, size):
    """Posts at both block edges and rails top and bottom - the shared frame."""
    post = max(3, size // 16)
    vbar(rows, 0, 0, size, post)
    vbar(rows, size - post, 0, size, post)
    hbar(rows, 0, 0, size, post)
    hbar(rows, size - post, 0, size, post)


def arc(rows, cx, cy, r, colour, thickness=1.6):
    """A ring, quantised - the scrollwork is built from these."""
    for y in range(max(0, int(cy - r - 2)), min(len(rows), int(cy + r + 3))):
        for x in range(max(0, int(cx - r - 2)), min(len(rows[0]), int(cx + r + 3))):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if abs(d - r) <= thickness / 2.0:
                rows[y][x] = colour


def grille():
    """Photo 3: the flat woven mesh that fences off most fare-control areas."""
    rows = canvas(N)
    # fine weave - 1 px strands on a 4 px pitch, so it reads as mesh not bars
    for y in range(4, N - 4):
        for x in range(4, N - 4):
            if x % 8 == 0 or y % 8 == 0:
                rows[y][x] = IRON if (x % 16 == 0 or y % 16 == 0) else IRON_DARK
    frame(rows, N)
    return rows


def scroll():
    """Photo 1: the ornate wrought-iron panel.

    The motif is centred on the block EDGES as well as the middle, so the rings
    are cut in half by the joint and each half completes against its neighbour -
    a run reads as one continuous piece of ironwork. Putting the whole motif in
    the middle instead (the first attempt) stamped an identical medallion on
    every block, which read as wallpaper rather than wrought iron.
    """
    rows = canvas(N)
    mid = N / 2.0
    band_top, band_bottom = 18, N - 18     # the decorative band, kept CLEAR

    # Uprights only above and below the band. Running them through it chopped
    # the arcs into dashes and the panel read as brambles, not ironwork.
    for x in range(10, N - 9, 11):
        vbar(rows, x, 4, band_top, 3)
        vbar(rows, x, band_bottom, N - 4, 3)
    hbar(rows, band_top - 2, 4, N - 4, 3)
    hbar(rows, band_bottom - 1, 4, N - 4, 3)

    # Rings last, so nothing crosses them. Centred on the block EDGES as well
    # as the middle: the edge halves complete against the neighbour, so a run
    # reads as one continuous piece rather than a medallion stamped per block.
    for cx in (0.0, mid, float(N)):
        arc(rows, cx, mid, 13.0, IRON, 3.4)
    frame(rows, N)
    return rows


def door():
    """The emergency exit door face: red header, push-bar sign, bar, panel.

    One texture for the whole leaf - the "one texture = one whole face" case,
    so the model UV-slices it rather than relying on auto-UV.
    """
    rows = canvas(DOOR)
    for y in range(DOOR):                  # the black leaf
        for x in range(DOOR):
            rows[y][x] = IRON
    for x in range(DOOR):                  # frame edges
        rows[0][x] = IRON_LIT
        rows[DOOR - 1][x] = IRON_DARK

    # EMERGENCY EXIT header
    for y in range(2, 12):
        for x in range(2, DOOR - 2):
            rows[y][x] = RED if y > 2 else RED_DARK
    pk.text_centred(rows, DOOR / 2.0, 5, "EMERGENCY EXIT", WHITE, scale=1)

    # "Push Bar for Emergency Exit / Alarm Will Sound", cut to what fits.
    # The 3x5 font is 4 px per character, so 16 characters is the entire 64 px
    # texture with no margin - the full wording had to lose its middle.
    for y in range(20, 35):
        for x in range(4, DOOR - 4):
            rows[y][x] = RED if y > 20 else RED_DARK
    pk.text_centred(rows, DOOR / 2.0, 22, "PUSH BAR", WHITE, scale=1)
    pk.text_centred(rows, DOOR / 2.0, 28, "ALARM SOUNDS", WHITE, scale=1)

    # the stainless push bar
    for y in range(39, 44):
        for x in range(5, DOOR - 5):
            rows[y][x] = STEEL_LIT if y == 36 else STEEL
    # a scuffed lower panel - these doors take a beating
    for y in range(47, DOOR - 3):
        for x in range(5, DOOR - 5):
            rows[y][x] = IRON_DARK if (x + y) % 11 else IRON
    return rows


# =====================================================================
# MODELS / BLOCKSTATES
# =====================================================================

import json

ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
MOD = "station_announcer"


def wj(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


def panel_model(name, texture, y0, y1, cap_top, cap_bottom):
    """A thin upright panel on the block centre plane, 4 px thick.

    The panel's own top and bottom faces are omitted when the run continues
    through them: two stacked cells would otherwise put coincident faces in the
    same plane and z-fight, the platform barrier's lesson.
    """
    faces = {
        "north": {"texture": "#panel", "uv": [0, 16 - y1, 16, 16 - y0]},
        "south": {"texture": "#panel", "uv": [0, 16 - y1, 16, 16 - y0]},
    }
    if cap_top:
        faces["up"] = {"texture": "#panel", "uv": [0, 0, 16, 4]}
    if cap_bottom:
        faces["down"] = {"texture": "#panel", "uv": [0, 0, 16, 4]}
    elements = [{"from": [0, y0, 6], "to": [16, y1, 10], "faces": faces}]
    wj(os.path.join(ASSETS, "models/block", name + ".json"), {
        "parent": "minecraft:block/block",
        "textures": {"panel": f"{MOD}:block/{texture}", "particle": f"{MOD}:block/{texture}"},
        "elements": elements,
    })


def stacking_blockstate(name, model_base):
    """FACING x UP x DOWN -> which cap the cell draws."""
    variants = {}
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for up in ("true", "false"):
            for down in ("true", "false"):
                suffix = ("_mid" if up == "true" and down == "true"
                          else "_top" if down == "true"
                          else "_bottom" if up == "true" else "_single")
                entry = {"model": f"{MOD}:block/{model_base}{suffix}"}
                if rot:
                    entry["y"] = rot
                variants[f"facing={facing},up={up},down={down}"] = entry
    return variants


def elem(frm, to, tex, faces=None, cull=None, uv=None):
    """One box. Faces default to all six on `tex`; `faces` limits which."""
    want = faces or ("north", "south", "east", "west", "up", "down")
    out = {}
    for f in want:
        face = {"texture": tex}
        if uv:
            face["uv"] = uv
        if cull and f in cull:
            face["cullface"] = cull[f]
        out[f] = face
    return {"from": list(frm), "to": list(to), "faces": out}


def piece(name, elements, textures):
    wj(os.path.join(ASSETS, "models/block", name + ".json"), {
        "parent": "minecraft:block/block",
        "textures": textures,
        "elements": elements,
    })


IRON_TEX = f"{MOD}:block/gate_iron"
STEEL_TEX = f"{MOD}:block/turnstile_steel"

# The run sits on the block centre plane. Posts are chunkier than the infill so
# the frame reads as structure and the bars as filling - flat slabs of uniform
# depth were exactly what made the first attempt look like a sticker.
POST_Z = (4.5, 11.5)
RAIL_Z = (6.0, 10.0)
BAR_Z = (6.75, 9.25)

# UPRIGHT SPACING. Pitch 4 divides 16, and the boundary upright STRADDLES x=0
# (-0.8..0.8) rather than sitting inside the block. That is the only way the
# gap is the same everywhere: sit it at 0..1.6 instead and the gap across a
# block joint comes out 1.6 against 2.4 inside, which is exactly the uneven
# spacing the first version had (its bars ran 4.2..13.6, so a joint gapped 6.6
# against 1.0). An element outside 0..16 needs explicit uv or it samples off
# the sprite - the railing-top lesson.
BAR_PITCH = 4.0
BAR_HALF = 0.8


def write_gate_pieces():
    """Posts, rails and infill as separate multipart pieces.

    Multipart rather than one model per state: the frame is 5 independent
    decisions (left post, right post, top rail, bottom rail, infill) and baking
    all 16 combinations of each variant would be 32 near-identical models.
    """
    iron = {"iron": IRON_TEX, "particle": IRON_TEX}

    # A post spans the full block height so a stack reads as one upright, and
    # never draws its top or bottom face - they would be coplanar with the
    # post above and z-fight.
    # The post is the boundary upright. Same WIDTH as the bars - a fatter post
    # pinches the gaps either side of it and the spacing stops being uniform -
    # so it is distinguished by DEPTH instead, standing proud front and back.
    piece("gate_post", [elem((-BAR_HALF, 0, POST_Z[0]), (BAR_HALF, 16, POST_Z[1]),
                             "#iron", ("north", "south", "east", "west"),
                             uv=[0, 0, 3, 16])], iron)

    for name, y0, y1 in (("gate_rail_bottom", 0.5, 2.5), ("gate_rail_top", 13.5, 15.5)):
        # Rails span the whole block and omit their end faces: connected, those
        # sit in the same plane as the neighbour's; unconnected, a post covers
        # them. (The zebra board's rule.)
        piece(name, [elem((0, y0, RAIL_Z[0]), (16, y1, RAIL_Z[1]), "#iron",
                          ("north", "south", "up", "down"))], iron)

    # Balusters: four uprights, full height so a stack is continuous. No top or
    # bottom faces for the same reason as the post.
    # Uprights on the same pitch as the post, so the whole run is one rhythm.
    # The boundary one is the post, so the infill draws the other three.
    bars = []
    for i in range(1, 4):
        cx = i * BAR_PITCH
        bars.append(elem((cx - BAR_HALF, 0, BAR_Z[0]), (cx + BAR_HALF, 16, BAR_Z[1]), "#iron",
                         ("north", "south", "east", "west")))
    piece("gate_bars_infill", bars, iron)

    # ---- corner pieces (fence logic): where a run TURNS, the cell renders as
    # half-arms meeting at a square centre post instead of a flat panel. The
    # arm is authored pointing WEST (x 0..8) and the blockstate rotates it to
    # whichever sides connect; its inner end buries inside the centre post
    # (4.5..11.5 both axes) and its outer end stops at the boundary, where the
    # neighbouring straight cell's panel or post takes over.
    piece("gate_corner_post", [elem((POST_Z[0], 0, POST_Z[0]), (POST_Z[1], 16, POST_Z[1]),
                                    "#iron", ("north", "south", "east", "west"),
                                    uv=[0, 0, 7, 16])], iron)
    for name, y0, y1 in (("gate_arm_rail_bottom", 0.5, 2.5), ("gate_arm_rail_top", 13.5, 15.5)):
        piece(name, [elem((0, y0, RAIL_Z[0]), (8, y1, RAIL_Z[1]), "#iron",
                          ("north", "south", "up", "down"))], iron)
    # one upright per half-arm, on the same pitch as the run
    piece("gate_arm_bars_infill",
          [elem((BAR_PITCH - BAR_HALF, 0, BAR_Z[0]), (BAR_PITCH + BAR_HALF, 16, BAR_Z[1]),
                "#iron", ("north", "south", "east", "west"))], iron)
    piece("gate_arm_grille_infill",
          [elem((0, 0, 7.5), (8, 16, 8.5), "#mesh", ("north", "south"))],
          {"mesh": f"{MOD}:block/gate_grille", "particle": IRON_TEX})

    # The grille variant fills the same frame with a mesh panel, recessed
    # slightly so the frame still stands proud of it.
    # Full block width: at 3..13 it left a 3 px hole at each end and the run
    # read as separate panels floating between posts.
    piece("gate_grille_infill",
          [elem((0, 0, 7.5), (16, 16, 8.5), "#mesh", ("north", "south"))],
          {"mesh": f"{MOD}:block/gate_grille", "particle": IRON_TEX})

    write_door_leaf()


MESH_TEX = f"{MOD}:block/gate_grille"
SIGN_HEADER_TEX = f"{MOD}:block/gate_door_sign_header"
SIGN_PUSHBAR_TEX = f"{MOD}:block/gate_door_sign_pushbar"

# The door leaf hinges at the shared post on the left block edge and swings
# toward the UNPAID side (model north) - you push your way out.
#
# NAME COLLISION, found 2026-08-17: this used to be called HINGE_X/HINGE_Z, and
# the track gate's constants of the same name are defined LATER at module scope
# - so swing() has always run on the gate's hinge line (3.0), never the 0.0
# written here, and every shipped door open model is the x=3 swing. Renaming
# both keeps that geometry exactly as it is in game rather than changing an
# unrelated block; 3.0 is the value in force, hinging on the post's inner face.
DOOR_HINGE_X, DOOR_HINGE_Z = 3.0, 8.0

# Face remap for a 90-degree swing about the hinge: north ends up facing west.
SWING_FACES = {"north": "west", "west": "south", "south": "east", "east": "north",
               "up": "up", "down": "down"}


def swing(element):
    """The same box, swung 90 degrees open about the hinge.

    A real rotation of the closed geometry - the previous "open" model was a
    separately-authored slab of different proportions, which is exactly why it
    read as the door warping rather than opening. The swung leaf pokes out of
    the block front the way a real door swings across the walkway; every face
    already carries explicit uv, which out-of-block elements require.
    """
    x0, y0, z0 = element["from"]
    x1, y1, z1 = element["to"]
    # point map: x' = hx + (z - hz), z' = hz - (x - hx)
    ax, az = DOOR_HINGE_X + (z0 - DOOR_HINGE_Z), DOOR_HINGE_Z - (x0 - DOOR_HINGE_X)
    bx, bz = DOOR_HINGE_X + (z1 - DOOR_HINGE_Z), DOOR_HINGE_Z - (x1 - DOOR_HINGE_X)
    swung = {"from": [min(ax, bx), y0, min(az, bz)], "to": [max(ax, bx), y1, max(az, bz)]}
    swung["faces"] = {SWING_FACES[f]: dict(face) for f, face in element["faces"].items()}
    return swung


def swing_about(element, hx, hz):
    """Rotate a box 90 degrees about a vertical hinge line, faces and all.

    Same point map as the exit door's swing, but with the hinge passed in so
    the track gate can use its own. Boxes leave the 0..16 range when they swing
    - legal, and the reason every face here already carries explicit uv.
    """
    x0, y0, z0 = element["from"]
    x1, y1, z1 = element["to"]
    ax, az = hx + (z0 - hz), hz - (x0 - hx)
    bx, bz = hx + (z1 - hz), hz - (x1 - hx)
    swung = {"from": [min(ax, bx), y0, min(az, bz)],
             "to": [max(ax, bx), y1, max(az, bz)],
             "faces": {SWING_FACES[f]: dict(face) for f, face in element["faces"].items()}}
    return swung


def face_set(tex, uv, sides=("north", "south", "east", "west", "up", "down")):
    return {f: {"texture": tex, "uv": list(uv)} for f in sides}


def door_half_elements(upper):
    """One half of the leaf, from the user's photos: a black frame holding a
    mesh window up top and a solid steel kick panel below, with the two red
    signs and the crash bar mounted PROUD on the paid side (south) - you read
    them as you push your way out.

    The leaf spans 0.5..15.5 so its ends bury inside the 4.5..11.5-deep posts
    (never coplanar with a post face, never a gap - the second-pass lesson)."""
    stile_uv = [0, 0, 2, 16]
    rail_uv = [0, 0, 13, 2]
    e = []
    # stiles both sides, full height of the half
    e.append({"from": [0.5, 0, 6.5], "to": [2.5, 16, 9.5], "faces": face_set("#iron", stile_uv)})
    e.append({"from": [13.5, 0, 6.5], "to": [15.5, 16, 9.5], "faces": face_set("#iron", stile_uv)})
    if upper:
        # mesh window between the stiles, one cutout plane
        e.append({"from": [2.5, 0, 7.6], "to": [13.5, 14.5, 8.4],
                  "faces": face_set("#mesh", [1, 1, 12, 15], ("north", "south"))})
        # EMERGENCY EXIT header, a plate proud on the paid side
        plate = face_set("#iron", [0, 0, 13, 4])
        plate["south"] = {"texture": "#header", "uv": [0, 0, 16, 16]}
        e.append({"from": [1.5, 10.5, 9.5], "to": [14.5, 14.5, 10.5], "faces": plate})
        # top rail
        e.append({"from": [2.5, 14.5, 6.5], "to": [13.5, 16, 9.5], "faces": face_set("#iron", rail_uv)})
    else:
        # solid stainless kick panel - the bottom 13 px of the photos
        e.append({"from": [2.5, 0, 7], "to": [13.5, 13, 9], "faces": face_set("#steel", [1, 2, 15, 15])})
        # rail behind the push bar zone
        e.append({"from": [2.5, 13, 7], "to": [13.5, 16, 9], "faces": face_set("#iron", [0, 0, 13, 3])})
        # push-bar instruction sign, proud, immediately above the bar
        plate = face_set("#iron", [0, 0, 12, 3])
        plate["south"] = {"texture": "#pushsign", "uv": [0, 0, 16, 16]}
        e.append({"from": [2, 12.8, 9.5], "to": [14, 15.8, 10.3], "faces": plate})
        # the crash bar on two brackets, proud of everything else
        e.append({"from": [3, 13 - 3.2, 9.5], "to": [4.5, 13 - 1.2, 11.8],
                  "faces": face_set("#steel", [0, 0, 2, 2])})
        e.append({"from": [11.5, 13 - 3.2, 9.5], "to": [13, 13 - 1.2, 11.8],
                  "faces": face_set("#steel", [0, 0, 2, 2])})
        e.append({"from": [1.5, 13 - 3.4, 11.8], "to": [14.5, 13 - 0.9, 13.8],
                  "faces": face_set("#steel", [0, 4, 16, 7])})
    return e


def write_alarm_lamp():
    for state in ("off", "on"):
        tex = f"{MOD}:block/gate_alarm_lamp_{state}"
        box = {"from": [-1.8, 11, 4.2], "to": [1.8, 14.2, 11.8],
               "faces": face_set("#lamp", [2, 2, 14, 14])}
        if state == "on":
            # a sounding strobe reads lit from every angle; the block also
            # emits light while ALARM (luminance in MtrStationDecor)
            box["shade"] = False
        piece("gate_alarm_lamp_" + state, [box], {"lamp": tex, "particle": tex})


def write_door_leaf():
    textures = {"iron": IRON_TEX, "mesh": MESH_TEX, "steel": STEEL_TEX,
                "header": SIGN_HEADER_TEX, "pushsign": SIGN_PUSHBAR_TEX,
                "particle": IRON_TEX}
    for half, upper in (("lower", False), ("upper", True)):
        closed = door_half_elements(upper)
        piece("gate_door_leaf_" + half, closed, textures)
        piece("gate_door_leaf_open_" + half, [swing(e) for e in closed], textures)
    write_alarm_lamp()


def rot(model, y):
    entry = {"model": f"{MOD}:block/{model}"}
    if y:
        entry["y"] = y
    return entry


FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def multipart(infill_model, leaf=False):
    """The shared frame, plus whatever fills it.

    Walls carry a BEND state (fence logic, 2026-08-18): where the run turns a
    corner, the flat panel is replaced by half-arms meeting at a square centre
    post. Every straight part is therefore gated on bend=none, and the arm
    parts below draw the corner. The doors have no bend property.
    """
    parts = []
    arm_infill = None if leaf else ("gate_arm_grille_infill" if "grille" in infill_model
                                    else "gate_arm_bars_infill")
    for facing, y in FACINGS:
        base = {"facing": facing}
        straight = base if leaf else dict(base, bend="none")
        # Every section draws its LEFT post; only a right-hand end closes the
        # run. That is what makes a joint one post instead of two.
        # The POST condition exists only on the walls. The door has no such
        # property - a doorway always needs its hinge post - and conditioning
        # on a property the block lacks makes the CLIENT reject the whole
        # blockstate file: every door rendered as the purple missing-model box.
        parts.append({"when": dict(straight, **({} if leaf else {"post": "true"})),
                      "apply": rot("gate_post", y)})
        parts.append({"when": dict(straight, right="false"), "apply": rot("gate_post_right", y)})
        if not leaf:
            parts.append({"when": dict(straight, down="false"), "apply": rot("gate_rail_bottom", y)})
            parts.append({"when": dict(straight, up="false"), "apply": rot("gate_rail_top", y)})
        if leaf:
            for half in ("lower", "upper"):
                parts.append({"when": dict(base, half=half, open="false"),
                              "apply": rot("gate_door_leaf_" + half, y)})
                parts.append({"when": dict(base, half=half, open="true"),
                              "apply": rot("gate_door_leaf_open_" + half, y)})

        else:
            parts.append({"when": dict(straight), "apply": rot(infill_model, y)})

        if leaf:
            continue
        # ---- the corner: centre post + an arm per connected side. The arm
        # model is authored toward WEST; +90 steps go west->north->east->south
        # (vanilla blockstate y rotation), and the run's left for facing=north
        # IS west, so left/right arms are +0/+180 off the facing rotation and
        # the bend arm +90 (front, toward the facing) or +270 (back).
        parts.append({"when": dict(base, bend="front|back"),
                      "apply": rot("gate_corner_post", y)})
        arms = (("front", {"bend": "front"}, 90), ("back", {"bend": "back"}, 270),
                ("left", {"bend": "front|back", "left": "true"}, 0),
                ("right", {"bend": "front|back", "right": "true"}, 180))
        for _, cond, off in arms:
            arm_rot = (y + off) % 360
            when = dict(base, **cond)
            parts.append({"when": when, "apply": rot(arm_infill, arm_rot)})
            parts.append({"when": dict(when, down="false"), "apply": rot("gate_arm_rail_bottom", arm_rot)})
            parts.append({"when": dict(when, up="false"), "apply": rot("gate_arm_rail_top", arm_rot)})
    return parts


def write_models():
    write_gate_pieces()
    # the closing post is the same box mirrored to the far edge
    piece("gate_post_right", [elem((16 - BAR_HALF, 0, POST_Z[0]), (16 + BAR_HALF, 16, POST_Z[1]),
                                   "#iron", ("north", "south", "east", "west"),
                                   uv=[0, 0, 3, 16])],
          {"iron": IRON_TEX, "particle": IRON_TEX})

    for name, infill in (("gate_scroll", "gate_bars_infill"), ("gate_grille", "gate_grille_infill")):
        wj(os.path.join(ASSETS, "blockstates", name + ".json"), {"multipart": multipart(infill)})
        wj(os.path.join(ASSETS, "models/item", name + ".json"),
           {"parent": f"{MOD}:block/{name}_item"})
        # a standalone item model: frame plus infill, both ends closed
        piece(name + "_item",
              [elem((0, 0, POST_Z[0]), (3, 16, POST_Z[1]), "#iron"),
               elem((13, 0, POST_Z[0]), (16, 16, POST_Z[1]), "#iron"),
               elem((0, 0.5, RAIL_Z[0]), (16, 2.5, RAIL_Z[1]), "#iron"),
               elem((0, 13.5, RAIL_Z[0]), (16, 15.5, RAIL_Z[1]), "#iron")]
              + ([elem((3, 0, 7.5), (13, 16, 8.5), "#mesh", ("north", "south"))]
                 if infill.startswith("gate_grille") else
                 [elem((3.0 + i * 2.6 + 1.2, 2, BAR_Z[0]), (3.0 + i * 2.6 + 2.8, 14, BAR_Z[1]), "#iron")
                  for i in range(4)]),
              {"iron": IRON_TEX, "mesh": f"{MOD}:block/gate_grille", "particle": IRON_TEX})

    wj(os.path.join(ASSETS, "blockstates", "emergency_exit_door.json"),
       {"multipart": multipart(None, leaf=True)})
    pngtool.write_png(os.path.join(ROOT,
        "src/main/resources/assets/station_announcer/textures/item/emergency_exit_door.png"),
        door_icon())
    wj(os.path.join(ASSETS, "models/item", "emergency_exit_door.json"),
       {"parent": "minecraft:item/generated",
        "textures": {"layer0": f"{MOD}:item/emergency_exit_door"}})
    print("3D gate geometry written")


# Every when-key must be a property the Java block declares - one unknown key
# makes the client reject the whole file (the 2.4.8 purple-box bug).
BLOCK_PROPS = {
    "gate_scroll": {"facing", "up", "down", "left", "right", "post", "bend"},
    "gate_grille": {"facing", "up", "down", "left", "right", "post", "bend"},
    "emergency_exit_door": {"facing", "half", "open", "powered", "alarm", "left", "right"},
    "track_warning_sign_wall": {"facing"},
    "track_warning_sign_gate": {"facing", "open", "hinge"},
    "employee_door_mesh": {"facing", "half", "open", "left", "right"},
    "employee_door_black": {"facing", "half", "open", "left", "right"},
    "employee_door_white": {"facing", "half", "open", "left", "right"},
}


def verify_blockstates():
    problems = []
    for name, props in BLOCK_PROPS.items():
        bs = json.load(open(os.path.join(ASSETS, "blockstates", name + ".json")))
        for key, entry in bs.get("variants", {}).items():
            unknown = {kv.split("=")[0] for kv in key.split(",") if kv} - props
            if unknown:
                problems.append(f"{name}: variant keys {sorted(unknown)} not on the block")
            model = entry["model"].split(":")[1]
            if not os.path.exists(os.path.join(ASSETS, "models", model + ".json")):
                problems.append(f"{name}: missing model {model}")
        for part in bs.get("multipart", []):
            when = part["when"]
            for clause in (when.get("OR", [when]) if "OR" in when else [when]):
                unknown = set(clause.keys()) - props
                if unknown:
                    problems.append(f"{name}: when-keys {sorted(unknown)} not on the block")
            model = part["apply"]["model"].split(":")[1]
            if not os.path.exists(os.path.join(ASSETS, "models", model + ".json")):
                problems.append(f"{name}: missing model {model}")
    if problems:
        raise SystemExit("BLOCKSTATE CONTRACT VIOLATIONS:\n  " + "\n  ".join(problems))
    print("blockstate contract verified")


def iron():
    """Plain black ironwork for posts, rails and balusters.

    Shading varies VERTICALLY only - a horizontal band would repeat on every
    stacked block, the globe-pole lesson. The lit column down one side is what
    gives a square bar a rounded read at a glance.
    """
    rows = canvas(16)
    for y in range(16):
        for x in range(16):
            base = 26 + (y % 4 == 0) * 3
            rows[y][x] = (base, base + 1, base - 1, 255)
    for y in range(16):
        rows[y][3] = (54, 56, 52, 255)      # highlight
        rows[y][4] = (40, 42, 39, 255)
        rows[y][12] = (14, 14, 13, 255)     # shadowed side
    return rows


def sign_plate(w, h, lines, scale=1):
    """A red sign plate with white lettering and a darker rim.

    Its own texture rather than a slice of a shared plate: the plates are
    separate 3D elements now, and at 8 px per model pixel the FULL wording
    fits - the old 64 px plate could only carry "PUSH BAR / ALARM SOUNDS".
    """
    rows = [[RED] * w for _ in range(h)]
    for x in range(w):
        rows[0][x] = RED_DARK
        rows[h - 1][x] = RED_DARK
    for y in range(h):
        rows[y][0] = RED_DARK
        rows[y][w - 1] = RED_DARK
    n = len(lines)
    for i, line in enumerate(lines):
        y = round((h - n * 6 * scale - (n - 1) * 2) / 2 + i * (6 * scale + 2))
        pk.text_centred(rows, w / 2.0, y, line, WHITE, scale=scale)
    return rows


def alarm_lamp(lit):
    """The strobe above the door: dark maroon at rest, blazing when sounding."""
    rows = canvas(16)
    for y in range(16):
        for x in range(16):
            rows[y][x] = (255, 64, 58, 255) if lit else (96, 22, 24, 255)
    if lit:
        for y in range(4, 9):
            for x in range(5, 11):
                rows[y][x] = (255, 236, 230, 255)   # hot core
    else:
        for x in range(16):
            rows[0][x] = (120, 34, 36, 255)         # a lens glint so it reads as a lamp
    return rows


def door_icon():
    """Flat 16 px inventory icon, like a vanilla door's - the 3D lower-half
    model the slot showed before read as half a door."""
    rows = canvas(16)
    for y in range(0, 16):
        for x in range(3, 13):
            rows[y][x] = IRON
    for y in range(1, 3):
        for x in range(4, 12):
            rows[y][x] = RED               # EMERGENCY EXIT header
    for y in range(4, 7):
        for x in range(4, 12):
            if (x + y) % 2 == 0:
                rows[y][x] = (70, 72, 68, 255)   # mesh
    for x in range(4, 12):
        rows[8][x] = RED                   # push-bar sign
        rows[10][x] = STEEL                # the bar
        rows[11][x] = STEEL_LIT
    for y in range(12, 15):
        for x in range(4, 12):
            rows[y][x] = (150, 152, 154, 255)    # kick panel
    return rows


def warning_sign():
    """The trackside "Do not enter or cross tracks" plate.

    White prohibition roundel over red, then the wording. The roundel is drawn
    as a ring with a slash rather than a filled disc with a hole: at 64 px a
    hole closes up, and the ring survives the mip chain.
    """
    rows = canvas(64)
    for y in range(64):
        for x in range(64):
            rows[y][x] = RED
    # THE BLACK TOP CAP. Every one of these plates has it, and it is what the
    # box edges sample: a vertical strip taken from the plate's left edge comes
    # out black at the top and red below, which is exactly the side of the box
    # in the photos. Keeping it in the texture rather than as geometry means
    # one 64 px plate skins the front, both sides and the cap.
    for y in range(CAP_ROWS):
        for x in range(64):
            rows[y][x] = IRON if y else IRON_LIT
    for x in range(64):                    # a darker rim so the plate has an edge
        rows[63][x] = RED_DARK
    for y in range(CAP_ROWS, 64):
        rows[y][0] = RED_DARK
        rows[y][63] = RED_DARK

    # the roundel, below the cap
    cx, cy, r = 32.0, 23.0, 11.0
    arc(rows, cx, cy, r, WHITE, 3.0)
    for i in range(-int(r), int(r) + 1):    # the slash
        for t in (-1, 0, 1):
            x = int(cx + i * 0.7071) + t
            y = int(cy - i * 0.7071)
            if 0 <= x < 64 and 0 <= y < 64 and ((x - cx) ** 2 + (y - cy) ** 2) <= (r - 1) ** 2:
                rows[y][x] = WHITE

    pk.text_centred(rows, 32, 38, "DO NOT ENTER", WHITE, scale=1)
    pk.text_centred(rows, 32, 46, "OR CROSS", WHITE, scale=1)
    pk.text_centred(rows, 32, 54, "TRACKS", WHITE, scale=1)
    return rows


# Cap depth in texture rows, and the same measurement in uv units (4 rows = 1 uv).
CAP_ROWS = 6
CAP_UV = CAP_ROWS / 4.0


SIGN_TEX = f"{MOD}:block/track_warning_sign"

# The plate box: 14 x 14 face, standing ONE PIXEL proud of whatever it is on.
# It is a sign screwed flat to the wall, not a cabinet: 3.5 px read as a box.
PLATE = dict(x0=1.0, x1=15.0, y0=1.0, y1=15.0, depth=1.0)

# Hinge geometry for the gate variant, in the FACING=north frame: a slim steel
# post at the left edge, the plate hung off it on two visible hinges. The post
# is 2 px wide - the same gauge as the ironwork it stands in a run with.
POST = dict(x0=0.0, x1=2.0, z0=4.5, z1=10.5)
LEAF = dict(x0=2.0, x1=14.5, y0=2.5, y1=14.5, z0=6.75, z1=9.25)
GATE_HINGE_X, GATE_HINGE_Z = 2.0, 8.0

# Mirroring a gate across the block's centre line gives the right-hinged
# variant: geometry mirrors and east/west face names swap, but the uv stays AS
# AUTHORED. Face uv is oriented to the viewer's screen, not to geometry x, so
# moving a box does not turn its texture - and swapping the u range is exactly
# what MIRRORS the lettering (learned in game: the first cut flipped u and the
# right-hand gate read "RETNE TON OD"). The swung-open model mirrors too, so
# it opens the other way.
MIRROR_FACES = {"north": "north", "south": "south", "up": "up", "down": "down",
                "east": "west", "west": "east"}


def mirror_x(element):
    x0, y0, z0 = element["from"]
    x1, y1, z1 = element["to"]
    return {"from": [16 - x1, y0, z0], "to": [16 - x0, y1, z1],
            "faces": {MIRROR_FACES[name]: dict(face)
                      for name, face in element["faces"].items()}}


def sign_faces(depth, front="north", back=None, back_cull=None):
    """Faces for a sign box, every edge taking a real slice of the plate.

    The edges are NOT a separate flat texture: a vertical strip cut from the
    plate's own left margin is black across the cap and red below it, which is
    what the side of the box actually looks like. The previous version
    stretched pids_frame over all four edges, and that is what made it read as
    a floating panel rather than a box screwed to the wall.
    """
    faces = {front: {"texture": "#sign", "uv": [0, 0, 16, 16]}}
    # left-margin strip, as deep as the box and as tall as the plate
    edge_uv = [0.5, 0.5, 0.5 + depth, 15.5]
    faces["east"] = {"texture": "#sign", "uv": list(edge_uv)}
    faces["west"] = {"texture": "#sign", "uv": list(edge_uv)}
    # the cap wraps over the top; the underside is plain red
    faces["up"] = {"texture": "#sign", "uv": [1, 0, 15, CAP_UV]}
    faces["down"] = {"texture": "#sign", "uv": [1, 16 - CAP_UV, 15, 16]}
    if back:
        faces[back] = {"texture": "#sign", "uv": [16, 0, 0, 16]}
    elif back_cull:
        faces[back_cull[0]] = {"texture": "#sign", "uv": [2, 2, 14, 14],
                               "cullface": back_cull[1]}
    return faces


WHITE_TEX = f"{MOD}:block/employee_door_paint"

EMPLOYEE_STYLES = ("mesh", "black", "white")


def employee_paint():
    """Off-white painted steel for the tiled variant.

    The tone sits between the tile walls' white tile and their grout so the
    door reads as part of the same wall. Shading varies VERTICALLY only (the
    globe-pole rule): the leaf models span two stacked blocks and share this
    texture, so any horizontal band would repeat at the half seam.
    """
    rows = canvas(16)
    for y in range(16):
        for x in range(16):
            rows[y][x] = (224, 222, 215, 255)
    for y in range(16):
        rows[y][3] = (238, 236, 230, 255)    # sheen column
        rows[y][4] = (231, 229, 223, 255)
        rows[y][12] = (207, 205, 198, 255)   # shadowed side
        rows[y][13] = (215, 213, 206, 255)
    return rows


def employee_leaf_elements(upper, style):
    """One half of the employees-only door leaf.

    Same bones as the emergency exit door (stiles burying inside the
    4.5..11.5-deep posts, leaf 0.5..15.5): kick rail, two panels with a lock
    rail at the half seam, top rail. The
    panels are wire mesh (cutout, top AND bottom - the user's spec), solid
    black, or solid painted steel depending on the variant; the geometry is
    shared so the three doors line up in a mixed run.
    """
    frame = "#frame"
    e = []
    # stiles both sides, full height of the half
    e.append({"from": [0.5, 0, 6.5], "to": [2.5, 16, 9.5], "faces": face_set(frame, [0, 0, 2, 16])})
    e.append({"from": [13.5, 0, 6.5], "to": [15.5, 16, 9.5], "faces": face_set(frame, [0, 0, 2, 16])})
    if upper:
        rails = ((0, 1.5), (14, 16))     # lock rail's upper lip + top rail
        panel_y = (1.5, 14)
    else:
        rails = ((0, 2.5), (14.5, 16))   # kick rail + lock rail's lower lip
        panel_y = (2.5, 14.5)
    for y0, y1 in rails:
        e.append({"from": [2.5, y0, 6.5], "to": [13.5, y1, 9.5],
                  "faces": face_set(frame, [0, 0, 11, max(1, y1 - y0)])})
    y0, y1 = panel_y
    if style == "mesh":
        # one cutout plane, like the exit door's window
        e.append({"from": [2.5, y0, 7.6], "to": [13.5, y1, 8.4],
                  "faces": face_set("#mesh", [1, 1, 12, 13], ("north", "south"))})
    else:
        e.append({"from": [2.5, y0, 7], "to": [13.5, y1, 9],
                  "faces": face_set(frame, [1, 2, 15, 14])})
    return e


def employee_door_icon(style):
    """Flat 16 px inventory icon per variant (2-tall models don't fit slots)."""
    if style == "white":
        body, frame_c = (224, 222, 215, 255), (196, 194, 187, 255)
    else:
        body, frame_c = (32, 33, 31, 255), (20, 20, 19, 255)
    rows = canvas(16)
    for y in range(0, 16):
        for x in range(3, 13):
            rows[y][x] = body
    for y in range(0, 16):                  # stiles
        rows[y][3] = frame_c
        rows[y][12] = frame_c
    for x in range(3, 13):                  # rails: top, lock, kick
        rows[0][x] = frame_c
        rows[7][x] = frame_c
        rows[8][x] = frame_c
        rows[15][x] = frame_c
    if style == "mesh":
        for y0, y1 in ((1, 7), (9, 15)):
            for y in range(y0, y1):
                for x in range(4, 12):
                    if (x + y) % 2 == 0:
                        rows[y][x] = (70, 72, 68, 255)
    # the little label plate at eye level
    for x in range(5, 11):
        rows[3][x] = (233, 231, 226, 255)
        rows[4][x] = (233, 231, 226, 255)
    return rows


def write_employee_doors():
    """Three staff doors sharing the gates' post rhythm.

    Each leaf has a closed model and a true 90-degree swing() of the same
    elements (the exit door's hinge and point map, so a mixed run swings
    identically). The white variant carries white posts too, so it blends into
    a tiled wall the way the user asked; the mesh and black ones keep the
    ironwork posts of the run they stand in.
    """
    # white post pair: the shared gate_post geometry re-skinned
    white = {"iron": WHITE_TEX, "particle": WHITE_TEX}
    piece("gate_post_white", [elem((-BAR_HALF, 0, POST_Z[0]), (BAR_HALF, 16, POST_Z[1]),
                                   "#iron", ("north", "south", "east", "west"),
                                   uv=[0, 0, 3, 16])], white)
    piece("gate_post_white_right", [elem((16 - BAR_HALF, 0, POST_Z[0]), (16 + BAR_HALF, 16, POST_Z[1]),
                                         "#iron", ("north", "south", "east", "west"),
                                         uv=[0, 0, 3, 16])], white)

    for style in EMPLOYEE_STYLES:
        frame_tex = WHITE_TEX if style == "white" else IRON_TEX
        textures = {"frame": frame_tex, "mesh": MESH_TEX, "particle": frame_tex}
        for half, upper in (("lower", False), ("upper", True)):
            closed = employee_leaf_elements(upper, style)
            piece(f"employee_door_{style}_{half}", closed, textures)
            piece(f"employee_door_{style}_open_{half}", [swing(e) for e in closed], textures)

        post = "gate_post_white" if style == "white" else "gate_post"
        post_right = "gate_post_white_right" if style == "white" else "gate_post_right"
        parts = []
        for facing, y in FACINGS:
            base = {"facing": facing}
            # The door always carries its left post (a doorway needs its frame;
            # conditioning on the walls' POST property would purple-box the
            # client - the 2.4.8 lesson), and closes the run when nothing joins
            # on the right, same as every other GateSection.
            parts.append({"when": dict(base), "apply": rot(post, y)})
            parts.append({"when": dict(base, right="false"), "apply": rot(post_right, y)})
            for half in ("lower", "upper"):
                parts.append({"when": dict(base, half=half, open="false"),
                              "apply": rot(f"employee_door_{style}_{half}", y)})
                parts.append({"when": dict(base, half=half, open="true"),
                              "apply": rot(f"employee_door_{style}_open_{half}", y)})
        wj(os.path.join(ASSETS, "blockstates", f"employee_door_{style}.json"), {"multipart": parts})

        pngtool.write_png(os.path.join(ROOT,
            f"src/main/resources/assets/station_announcer/textures/item/employee_door_{style}.png"),
            employee_door_icon(style))
        wj(os.path.join(ASSETS, "models/item", f"employee_door_{style}.json"),
           {"parent": "minecraft:item/generated",
            "textures": {"layer0": f"{MOD}:item/employee_door_{style}"}})
    pngtool.write_png(os.path.join(TEXTURES, "employee_door_paint.png"), employee_paint())
    print("employee door models written")


def write_warning_models():
    """The wall plate is a box proud of the wall; the gate is a hinged leaf."""
    textures = {"sign": SIGN_TEX, "iron": IRON_TEX, "steel": STEEL_TEX,
                "particle": SIGN_TEX}

    # ---- wall variant: a box screwed to the wall behind it
    piece("track_warning_sign_wall", [{
        "from": [PLATE["x0"], PLATE["y0"], 16 - PLATE["depth"]],
        "to": [PLATE["x1"], PLATE["y1"], 16],
        "faces": sign_faces(PLATE["depth"], back_cull=("south", "south")),
    }], textures)

    # ---- gate variant: hinge post (fixed) + leaf (swings)
    # A 2 px post takes a 2 px slice of the iron texture, centred on its lit
    # column - stretching the whole 16 px width over it flattens the bar.
    post_faces = face_set("#iron", [3, 0, 5, 16], ("north", "south", "east", "west"))
    post_faces.update(face_set("#iron", [3, 0, 5, 11], ("up", "down")))
    post = {"from": [POST["x0"], 0, POST["z0"]],
            "to": [POST["x1"], 16, POST["z1"]],
            "faces": post_faces}

    def hinge(y):
        return {"from": [POST["x1"] - 0.5, y, LEAF["z0"] - 0.5],
                "to": [LEAF["x0"] + 1.5, y + 1.5, LEAF["z1"] + 0.5],
                "faces": face_set("#steel", [0, 0, 2, 2])}

    leaf = {"from": [LEAF["x0"], LEAF["y0"], LEAF["z0"]],
            "to": [LEAF["x1"], LEAF["y1"], LEAF["z1"]],
            "faces": sign_faces(LEAF["z1"] - LEAF["z0"], back="south")}

    swinging = [leaf, hinge(4.0), hinge(11.0)]
    closed = [post] + swinging
    opened = [post] + [swing_about(e, GATE_HINGE_X, GATE_HINGE_Z) for e in swinging]
    piece("track_warning_sign_gate", closed, textures)
    piece("track_warning_sign_gate_open", opened, textures)
    # right-hand gate: the same gate mirrored, post at the other edge
    piece("track_warning_sign_gate_right", [mirror_x(e) for e in closed], textures)
    piece("track_warning_sign_gate_right_open", [mirror_x(e) for e in opened], textures)

    wj(os.path.join(ASSETS, "blockstates", "track_warning_sign_wall.json"), {"variants": {
        f"facing={d}": ({"model": f"{MOD}:block/track_warning_sign_wall"} if r == 0
                        else {"model": f"{MOD}:block/track_warning_sign_wall", "y": r})
        for d, r in FACINGS}})
    wj(os.path.join(ASSETS, "blockstates", "track_warning_sign_gate.json"), {"variants": {
        f"facing={d},hinge={h},open={o}":
            ({"model": f"{MOD}:block/track_warning_sign_gate{hand}{suffix}"} if r == 0 else
             {"model": f"{MOD}:block/track_warning_sign_gate{hand}{suffix}", "y": r})
        for d, r in FACINGS
        for h, hand in (("left", ""), ("right", "_right"))
        for o, suffix in (("false", ""), ("true", "_open"))}})
    for name in ("track_warning_sign_wall", "track_warning_sign_gate"):
        wj(os.path.join(ASSETS, "models/item", name + ".json"),
           {"parent": f"{MOD}:block/{name}"})
    print("warning sign + gate models written")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", metavar="DIR")
    args = ap.parse_args()

    # No scroll texture any more: the ornate panel became real bars, so the
    # only infill texture left is the grille's mesh. The door is an assembly of
    # mesh/steel/iron with two sign plates - the painted 64 px leaf is gone.
    out = {"gate_grille": grille(),
           "gate_door_sign_header": sign_plate(128, 40, ["EMERGENCY EXIT"], scale=2),
           "gate_door_sign_pushbar": sign_plate(128, 32,
               ["PUSH BAR FOR EMERGENCY EXIT", "ALARM WILL SOUND"]),
           "gate_alarm_lamp_off": alarm_lamp(False),
           "gate_alarm_lamp_on": alarm_lamp(True)}
    for name, rows in out.items():
        pngtool.write_png(os.path.join(TEXTURES, name + ".png"), rows)
    pngtool.write_png(os.path.join(TEXTURES, "track_warning_sign.png"), warning_sign())
    pngtool.write_png(os.path.join(TEXTURES, "gate_iron.png"), iron())
    print("wrote", ", ".join(out), ", track_warning_sign")
    write_models()
    write_employee_doors()
    write_warning_models()
    # AFTER both writers: verifying before the warning-sign blockstates are
    # rewritten would only ever check the previous run's file.
    verify_blockstates()

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        # a 3-wide run of each wall, so the joint between blocks is visible
        for name in ("gate_grille", "gate_scroll"):
            tile = out[name]
            w = len(tile[0])
            sheet = [[HOLE] * (w * 3) for _ in range(w)]
            for y in range(w):
                for i in range(3):
                    for x in range(w):
                        sheet[y][i * w + x] = tile[y][x]
            pngtool.write_png(os.path.join(args.preview, f"prev_{name}.png"),
                              pngtool.scale_nn(sheet, 8, 8))
        pngtool.write_png(os.path.join(args.preview, "prev_emergency_exit_door.png"),
                          pngtool.scale_nn(out["emergency_exit_door"], 8, 8))
        print("previews in", args.preview)


if __name__ == "__main__":
    main()
