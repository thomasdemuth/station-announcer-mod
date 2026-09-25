#!/usr/bin/env python3
"""Textured offline renderer for block-model scenes (numpy + PIL): vanilla
JSON elements incl. single-axis rotations with rescale, explicit or auto uv,
face uv rotation, alpha cutout, orthographic camera, per-pixel depth buffer.
Good enough to judge a model family's LOOK without a client.

A scene is a list of (model, (cx, cy, cz), quarter_turns) where model is a
model name under models/block or an in-memory dict {"textures", "elements"},
the offset is in BLOCKS and quarter_turns is the blockstate y rotation / 90.

    import render_scene as R
    R.render(scene, "out.png", yaw=215, pitch=22, size=1100)
"""
import json
import math
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "src/main/resources/assets")
FACE_SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
FALLBACK = {"minecraft:block/stone": (125, 125, 125), "minecraft:block/smooth_stone": (160, 160, 160)}
_tex_cache = {}
_model_cache = {}


def load_model(name):
    if isinstance(name, dict):
        return name
    if name not in _model_cache:
        ns, path = name.split(":") if ":" in name else ("station_announcer", "block/" + name)
        with open(os.path.join(ASSETS, ns, "models", path + ".json")) as f:
            m = json.load(f)
        parent = m.get("parent")
        if "elements" not in m and parent and not parent.startswith("minecraft:") and not parent.startswith("block/"):
            p = load_model(parent)
            m = {"textures": {**p.get("textures", {}), **m.get("textures", {})}, "elements": p.get("elements", [])}
        _model_cache[name] = m
    return _model_cache[name]


def texture(ref, textures):
    seen = 0
    while ref.startswith("#") and seen < 8:
        ref = textures.get(ref[1:], "")
        seen += 1
    if ref not in _tex_cache:
        ns, path = ref.split(":") if ":" in ref else ("minecraft", ref)
        f = os.path.join(ASSETS, ns, "textures", path + ".png")
        if os.path.exists(f):
            img = Image.open(f).convert("RGBA")
            w = img.size[0]
            _tex_cache[ref] = np.asarray(img.crop((0, 0, w, w)), dtype=np.float32)   # first frame
        else:
            c = FALLBACK.get(ref, (200, 60, 200))
            _tex_cache[ref] = np.full((16, 16, 4), c + (255,), dtype=np.float32)
    return _tex_cache[ref]


def rot_point(p, rot):
    if not rot:
        return p
    ox, oy, oz = rot["origin"]
    a = math.radians(rot["angle"])
    c, s = math.cos(a), math.sin(a)
    x, y, z = p[0] - ox, p[1] - oy, p[2] - oz
    k = (1.0 / abs(c)) if rot.get("rescale") else 1.0
    if rot["axis"] == "x":
        y, z = (y * c - z * s), (y * s + z * c)
        y, z = y * k, z * k
    elif rot["axis"] == "y":
        x, z = (x * c + z * s), (-x * s + z * c)
        x, z = x * k, z * k
    else:
        x, y = (x * c - y * s), (x * s + y * c)
        x, y = x * k, y * k
    return (x + ox, y + oy, z + oz)


def face_quads(e):
    """[(face, [TL, TR, BR, BL] world corners, uv)] in vanilla's corner order."""
    x0, y0, z0 = e["from"]
    x1, y1, z1 = e["to"]
    corners = {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }
    auto = {
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0], "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0], "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
        "up": [x0, z0, x1, z1], "down": [x0, 16 - z1, x1, 16 - z0],
    }
    out = []
    for name, fc in e.get("faces", {}).items():
        pts = [rot_point(p, e.get("rotation")) for p in corners[name]]
        out.append((name, pts, fc.get("uv", auto[name]), fc.get("rotation", 0), fc["texture"]))
    return out


def turn(p, k):
    x, y, z = p
    for _ in range(k % 4):
        x, z = 16 - z, x
    return (x, y, z)


def render(scene, out_png, yaw=215.0, pitch=24.0, size=1100, bg=(178, 205, 240), ground=None, supersample=2):
    S = size * supersample
    ya, pa = math.radians(yaw), math.radians(pitch)

    def proj(p):
        x, y, z = p
        X = x * math.cos(ya) + z * math.sin(ya)
        Z = -x * math.sin(ya) + z * math.cos(ya)
        return (X, y * math.cos(pa) - Z * math.sin(pa), y * math.sin(pa) + Z * math.cos(pa))

    quads = []
    for entry in scene:
        m = load_model(entry[0])
        off = [c * 16 for c in entry[1]]
        k = entry[2] if len(entry) > 2 else 0
        texs = m.get("textures", {})
        for e in m.get("elements", []):
            for name, pts, uv, frot, tref in face_quads(e):
                world = [tuple(a + b for a, b in zip(turn(p, k), off)) for p in pts]
                quads.append((name, [proj(p) for p in world], uv, frot, texture(tref, texs), e.get("shade", True)))
    allp = [p for q in quads for p in q[1]]
    xs = [p[0] for p in allp]
    ys = [p[1] for p in allp]
    span = max(max(xs) - min(xs), max(ys) - min(ys)) or 1
    sc = (S - 60 * supersample) / span
    ox, oy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2

    img = np.empty((S, S, 3), dtype=np.float32)
    img[:] = bg
    zbuf = np.full((S, S), -1e9, dtype=np.float32)
    for name, pp, uv, frot, tex, shaded in quads:
        P = np.array([[(p[0] - ox) * sc + S / 2, S / 2 - (p[1] - oy) * sc, p[2]] for p in pp], dtype=np.float64)
        tl, tr, bl = P[0], P[1], P[3]
        A = np.array([[tr[0] - tl[0], bl[0] - tl[0]], [tr[1] - tl[1], bl[1] - tl[1]]])
        if abs(np.linalg.det(A)) < 1e-6:
            continue
        inv = np.linalg.inv(A)
        x0 = max(0, int(math.floor(P[:, 0].min())))
        x1 = min(S - 1, int(math.ceil(P[:, 0].max())))
        y0 = max(0, int(math.floor(P[:, 1].min())))
        y1 = min(S - 1, int(math.ceil(P[:, 1].max())))
        if x1 < x0 or y1 < y0:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        dx, dy = gx - tl[0], gy - tl[1]
        s = inv[0, 0] * dx + inv[0, 1] * dy
        t = inv[1, 0] * dx + inv[1, 1] * dy
        inside = (s >= 0) & (s <= 1) & (t >= 0) & (t <= 1)
        if not inside.any():
            continue
        depth = tl[2] + s * (tr[2] - tl[2]) + t * (bl[2] - tl[2])
        # face uv rotation turns the texture clockwise on the face
        for _ in range((frot // 90) % 4):
            s, t = t, 1 - s
        n = tex.shape[0]
        u = (uv[0] + s * (uv[2] - uv[0])) / 16.0 * n
        v = (uv[1] + t * (uv[3] - uv[1])) / 16.0 * n
        ui = np.clip(u.astype(np.int64), 0, n - 1)
        vi = np.clip(v.astype(np.int64), 0, n - 1)
        texel = tex[vi, ui]
        sub_z = zbuf[y0:y1 + 1, x0:x1 + 1]
        ok = inside & (texel[..., 3] > 25) & (depth > sub_z + 1e-4)
        shade = FACE_SHADE[name] if shaded else 1.0
        sub = img[y0:y1 + 1, x0:x1 + 1]
        sub[ok] = texel[..., :3][ok] * shade
        sub_z[ok] = depth[ok]
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    if supersample > 1:
        out = out.resize((size, size), Image.LANCZOS)
    out.save(out_png)
    return out_png


def cube(tex_ref, y1=16):
    """A plain full block (or slab of height y1) for floors and slabs in a scene."""
    faces = {f: {"texture": "#t"} for f in ("north", "south", "east", "west", "up", "down")}
    return {"textures": {"t": tex_ref}, "elements": [{"from": [0, 0, 0], "to": [16, y1, 16], "faces": faces}]}


def resolve(block, props):
    """Blockstate lookup: [(model, quarter_turns)] for a block id + property dict
    (variants or multipart with AND / OR conditions; y rotation only)."""
    with open(os.path.join(ASSETS, "station_announcer", "blockstates", block + ".json")) as f:
        bs = json.load(f)
    props = {k: str(v).lower() for k, v in props.items()}

    def match(when):
        if "OR" in when:
            return any(match(w) for w in when["OR"])
        if "AND" in when:
            return all(match(w) for w in when["AND"])
        return all(props.get(k) in str(v).split("|") for k, v in when.items())

    def applied(ap):
        ap = ap[0] if isinstance(ap, list) else ap
        return (ap["model"], (ap.get("y", 0) // 90) % 4)

    out = []
    if "variants" in bs:
        for key, ap in bs["variants"].items():
            want = dict(kv.split("=") for kv in key.split(",") if kv)
            if all(props.get(k) == v for k, v in want.items()):
                out.append(applied(ap))
                break
    for part in bs.get("multipart", []):
        if "when" not in part or match(part["when"]):
            out.append(applied(part["apply"]))
    return out


def place(scene, block, props, pos):
    for model, k in resolve(block, props):
        scene.append((model, pos, k))
