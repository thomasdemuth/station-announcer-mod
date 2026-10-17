#!/usr/bin/env python3
"""Litter & trash family (ATMOSPHERE_PLAN.md §1C / §2.3) — GENERATED assets.

Blocks (Java in src/main/java/com/stationannouncer/atmosphere/):
  litter           LitterBlock               count 1..4
  trash_bags       TrashBagsBlock            bags 1..3
  cardboard_pile   TrashCardboardPileBlock   layers 1..3
  trash_can        TrashCanBlock             facing (n/e/s/w), fill 0..3
  swept_pile       LitterSweptPileBlock      broom true/false
  newspaper_stack  LitterNewspaperStackBlock facing (n/e/s/w)

Never hand-edit the output: re-run this script.

    python3 tools/gen_litter_assets.py                 # write everything + verify
    python3 tools/gen_litter_assets.py --preview DIR   # also render preview PNGs into DIR

How the floor litter varies by position (Sodium without Indium: no
position-aware models, only vanilla weighted variants):
  * one multipart selector per SLOT (slot k shown when count > k), each slot a
    fixed spot in the cell (two jittered spots per slot),
  * each slot's apply list = every piece x 4 turns x 2 spots, the turn BAKED
    into the model around the piece's own centre (a blockstate y-rotation
    would carry the piece into another slot's quadrant),
  * vanilla's MultipartBakedModel reseeds the SAME random for every selector
    and WeightedBakedModel picks index = |nextLong| % totalWeight, so equal
    totals would put the same piece in every slot. Each slot's list gets a
    different total weight (232 / 233 / 235 / 239) by bumping one entry.
  * pieces in different slots sit at slightly different heights (SLOT_Y), so
    two overlapping flat pieces are never coplanar.
"""
import argparse
import glob
import hashlib
import json
import math
import os
import re
import shutil
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import atmo_common as AC  # noqa: E402

MOD = AC.MOD
ASSETS = AC.ASSETS
MODEL_DIR = os.path.join(ASSETS, "models/block/litter")
TEX_DIR = os.path.join(ASSETS, "textures/block/litter")
JAVA_DIR = os.path.join(AC.ROOT, "src/main/java/com/stationannouncer/atmosphere")

BLOCKS = {  # id -> Java class (for the property cross-check in verify())
    "litter": "LitterBlock",
    "trash_bags": "TrashBagsBlock",
    "cardboard_pile": "TrashCardboardPileBlock",
    "trash_can": "TrashCanBlock",
    "swept_pile": "LitterSweptPileBlock",
    "newspaper_stack": "LitterNewspaperStackBlock",
}
LANG = {
    "block.station_announcer.litter": "Litter",
    "block.station_announcer.trash_bags": "Garbage Bags",
    "block.station_announcer.cardboard_pile": "Flattened Cardboard",
    "block.station_announcer.trash_can": "Station Trash Can",
    "block.station_announcer.swept_pile": "Swept-Up Pile",
    "block.station_announcer.newspaper_stack": "Newspaper Bundle",
}

WRITTEN = []          # every file this run wrote (verify() re-reads them)
MODELS = {}           # model name (under block/) -> model dict, for verify + preview


# ---------------------------------------------------------------- textures

def _seed(*parts):
    return int(hashlib.md5("|".join(map(str, parts)).encode()).hexdigest()[:8], 16)


def smooth_noise(rng, h, w, cell):
    """Value noise in [-1, 1], bilinear over a grid of `cell` texels."""
    gh, gw = h // cell + 2, w // cell + 2
    g = rng.uniform(-1, 1, (gh, gw))
    ys = np.arange(h) / cell
    xs = np.arange(w) / cell
    y0 = ys.astype(int)
    x0 = xs.astype(int)
    fy = (ys - y0)[:, None]
    fx = (xs - x0)[None, :]
    a = g[y0][:, x0]
    b = g[y0][:, x0 + 1]
    c = g[y0 + 1][:, x0]
    d = g[y0 + 1][:, x0 + 1]
    return a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy


def fill(a, c):
    a[..., 0], a[..., 1], a[..., 2] = c
    a[..., 3] = 255


def tone(a, f, mask=None):
    """Multiply rgb by f (scalar or HxW array), optionally only where mask."""
    f = np.asarray(f, dtype=np.float32)
    if f.ndim == 2:
        f = f[..., None]
    if mask is None:
        a[..., :3] = np.clip(a[..., :3] * f, 0, 255)
    else:
        a[..., :3] = np.where(mask[..., None], np.clip(a[..., :3] * f, 0, 255), a[..., :3])


def rect(a, x0, y0, x1, y1, c, alpha=255):
    h, w = a.shape[:2]
    x0, x1 = max(0, int(round(x0))), min(w, int(round(x1)))
    y0, y1 = max(0, int(round(y0))), min(h, int(round(y1)))
    if x1 > x0 and y1 > y0:
        a[y0:y1, x0:x1, :3] = c
        a[y0:y1, x0:x1, 3] = alpha


def ellipse_mask(h, w, cx, cy, rx, ry):
    yy, xx = np.mgrid[0:h, 0:w]
    return ((xx + 0.5 - cx) / max(rx, 1e-3)) ** 2 + ((yy + 0.5 - cy) / max(ry, 1e-3)) ** 2 <= 1.0


def paint(a, mask, c):
    a[mask, 0], a[mask, 1], a[mask, 2] = c
    a[mask, 3] = 255


def grain(a, rng, amp=0.08, cell=2):
    h, w = a.shape[:2]
    tone(a, 1 + amp * smooth_noise(rng, h, w, cell))


class Tex:
    """One texture file, packed with face regions by a shelf packer.

    `region(key, w_px, h_px, painter)` allocates w_px*density x h_px*density
    texels (+1 texel gutter all round, edge-extruded so mipmaps don't bleed),
    paints it once and returns the face uv in 0..16 units. Regions are cached by
    key, so faces of equal size and look share texels.
    """

    def __init__(self, name, size, density):
        self.name, self.size, self.density = name, size, density
        self.img = np.zeros((size, size, 4), dtype=np.float32)
        self.cache = {}
        self.sky = np.zeros(size, dtype=np.int32)   # skyline packer

    @property
    def ref(self):
        return f"{MOD}:block/litter/{self.name}"

    def region(self, key, w_px, h_px, painter, density=None):
        d = density or self.density
        w = max(1, int(round(w_px * d)))
        h = max(1, int(round(h_px * d)))
        ck = (key, w, h)
        if ck in self.cache:
            return self.cache[ck]
        best = None
        for x in range(0, self.size - (w + 2) + 1):
            y = int(self.sky[x:x + w + 2].max())
            if best is None or y < best[1]:
                best = (x, y)
        if best is None or best[1] + h + 2 > self.size:
            raise RuntimeError(f"texture {self.name} full at region {key} ({w}x{h})")
        x0, y0 = best[0] + 1, best[1] + 1
        self.sky[best[0]:best[0] + w + 2] = best[1] + h + 2
        sub = np.zeros((h, w, 4), dtype=np.float32)
        painter(sub, np.random.default_rng(_seed(self.name, key, w, h)))
        self.img[y0:y0 + h, x0:x0 + w] = sub
        # gutter: extrude the region's edges one texel
        self.img[y0 - 1, x0:x0 + w] = sub[0]
        self.img[y0 + h, x0:x0 + w] = sub[-1]
        self.img[y0 - 1:y0 + h + 1, x0 - 1] = self.img[y0 - 1:y0 + h + 1, x0]
        self.img[y0 - 1:y0 + h + 1, x0 + w] = self.img[y0 - 1:y0 + h + 1, x0 + w - 1]
        s = 16.0 / self.size
        uv = [round(x0 * s, 4), round(y0 * s, 4), round((x0 + w) * s, 4), round((y0 + h) * s, 4)]
        self.cache[ck] = uv
        return uv

    def save(self):
        path = os.path.join(TEX_DIR, self.name + ".png")
        os.makedirs(TEX_DIR, exist_ok=True)
        Image.fromarray(np.clip(self.img, 0, 255).astype(np.uint8)).save(path)
        WRITTEN.append(path)


class TileTex:
    """A plain tiling texture mapped like vanilla auto-uv (by block position)."""

    def __init__(self, name, size, painter):
        self.name, self.size = name, size
        self.img = np.zeros((size, size, 4), dtype=np.float32)
        painter(self.img, np.random.default_rng(_seed(name)))

    @property
    def ref(self):
        return f"{MOD}:block/litter/{self.name}"

    def save(self):
        path = os.path.join(TEX_DIR, self.name + ".png")
        os.makedirs(TEX_DIR, exist_ok=True)
        Image.fromarray(np.clip(self.img, 0, 255).astype(np.uint8)).save(path)
        WRITTEN.append(path)


# ---------------------------------------------------------------- geometry

FACE_KEYS = {"n": "north", "s": "south", "e": "east", "w": "west", "u": "up", "d": "down"}
CW = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}


def face_dims(face, f, t):
    dx, dy, dz = t[0] - f[0], t[1] - f[1], t[2] - f[2]
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[face]


def auto_uv(face, f, t):
    x0, y0, z0 = f
    x1, y1, z1 = t
    uv = {"north": [16 - x1, 16 - y1, 16 - x0, 16 - y0], "south": [x0, 16 - y1, x1, 16 - y0],
          "west": [z0, 16 - y1, z1, 16 - y0], "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
          "up": [x0, z0, x1, z1], "down": [x0, 16 - z1, x1, 16 - z0]}[face]
    return [round(min(16, max(0, v)), 4) for v in uv]


def box(f, t, tex, paint, faces="nsewu", over=None, rot=None, uvrot=None):
    """An element with packed-region uvs.

    tex: a Tex. paint: default painter (fn(arr, rng, face)) or (key, fn) pair.
    over: {face letter: painter} overrides. rot: (axis, angle, origin).
    uvrot: {face letter: degrees}.
    """
    over = over or {}
    uvrot = uvrot or {}
    el = {"from": [round(v, 4) for v in f], "to": [round(v, 4) for v in t], "faces": {}}
    for ch in faces:
        face = FACE_KEYS[ch]
        p = over.get(ch, paint)
        if p is None:
            continue
        key, fn = p if isinstance(p, tuple) else (p.__name__, p)
        w, h = face_dims(face, f, t)
        if getattr(fn, "fixed", None):      # one shared region, stretched onto every face that uses it
            w, h = (v / tex.density for v in fn.fixed)
        uv = tex.region((key, face if fn_wants_face(fn) else ""), w, h,
                        (lambda fn_, face_: (lambda arr, rng: fn_(arr, rng, face_)))(fn, face))
        spec = {"uv": uv, "texture": "#" + tex.name}
        if ch in uvrot:
            spec["rotation"] = uvrot[ch] % 360
        el["faces"][face] = spec
    if rot:
        axis, angle, origin = rot
        el["rotation"] = {"axis": axis, "angle": angle, "origin": [round(v, 4) for v in origin]}
    el["_tex"] = {tex.name: tex.ref}
    return el


def fn_wants_face(fn):
    return getattr(fn, "per_face", False)


def per_face(fn):
    fn.per_face = True
    return fn


def tbox(f, t, tex, faces="nsewu", rot=None, alias=None):
    """An element on a TileTex with auto-style uvs (element must lie inside 0..16)."""
    alias = alias or tex.name
    el = {"from": [round(v, 4) for v in f], "to": [round(v, 4) for v in t], "faces": {}}
    for ch in faces:
        face = FACE_KEYS[ch]
        el["faces"][face] = {"uv": auto_uv(face, f, t), "texture": "#" + alias}
    if rot:
        axis, angle, origin = rot
        el["rotation"] = {"axis": axis, "angle": angle, "origin": [round(v, 4) for v in origin]}
    el["_tex"] = {alias: tex.ref}
    return el


def xform(el, quarter=0, dx=0.0, dy=0.0, dz=0.0, scale=1.0):
    """Turn an element `quarter` x 90 deg clockwise (seen from above) about the
    local origin (0, *, 0), scale it about the origin, then translate."""
    e = json.loads(json.dumps({k: v for k, v in el.items() if k != "_tex"}))
    e["_tex"] = dict(el["_tex"])
    q = quarter % 4

    def turn_pt(x, z):
        for _ in range(q):
            x, z = -z, x
        return x, z

    (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
    a = turn_pt(x0, z0)
    b = turn_pt(x1, z1)
    nx0, nx1 = sorted((a[0], b[0]))
    nz0, nz1 = sorted((a[1], b[1]))
    e["from"] = [round(nx0 * scale + dx, 4), round(y0 * scale + dy, 4), round(nz0 * scale + dz, 4)]
    e["to"] = [round(nx1 * scale + dx, 4), round(y1 * scale + dy, 4), round(nz1 * scale + dz, 4)]
    faces = {}
    for name, spec in e["faces"].items():
        n = name
        for _ in range(q):
            n = CW[n]
        if q and name in ("up", "down"):
            r = (spec.get("rotation", 0) + (90 if name == "up" else 270) * q) % 360
            if r:
                spec["rotation"] = r
            else:
                spec.pop("rotation", None)
        faces[n] = spec
    e["faces"] = faces
    if "rotation" in e:
        r = e["rotation"]
        ox, oy, oz = r["origin"]
        ox, oz = turn_pt(ox, oz)
        axis, ang = r["axis"], r["angle"]
        for _ in range(q):
            if axis == "x":
                axis = "z"
            elif axis == "z":
                axis, ang = "x", -ang
        r["axis"], r["angle"] = axis, ang
        r["origin"] = [round(ox * scale + dx, 4), round(oy * scale + dy, 4), round(oz * scale + dz, 4)]
    return e


def model(name, elements, particle, parent=None, ao=True):
    textures = {}
    els = []
    for e in elements:
        textures.update(e["_tex"])
        els.append({k: v for k, v in e.items() if k != "_tex"})
    textures["particle"] = particle
    m = {}
    if parent:
        m["parent"] = parent
    if not ao:
        m["ambientocclusion"] = False
    m["textures"] = textures
    m["elements"] = els
    MODELS["litter/" + name] = m
    return f"{MOD}:block/litter/{name}"


# ---------------------------------------------------------------- piece painters

def P(name):
    """Register a painter under a stable key (lambdas would all be '<lambda>')."""
    def deco(fn):
        fn.__name__ = name
        return fn
    return deco


ANTHORA_BLUE = (30, 74, 168)
WHITE_PAPER = (236, 234, 226)
GOLD = (214, 168, 62)


def cup_painter(name, ring_end, vase):
    """Lying Anthora cup segment: blue, a white key-pattern ring at `ring_end`
    ('west' or 'east' end of the cup's axis, which runs along x), optionally
    the white amphora with its gold bands."""
    @per_face
    @P(name)
    def cup_body(a, rng, face):
        h, w = a.shape[:2]
        fill(a, ANTHORA_BLUE)
        west_at_u0 = face != "north"            # north faces run u from east to west
        if face in ("east", "west"):
            west_at_u0 = None
        if west_at_u0 is not None:
            at_lo = (ring_end == "west") == west_at_u0
            xs = (0, 2) if at_lo else (w - 1, w - 3)
            rect(a, xs[0], 0, xs[0] + 1, h, WHITE_PAPER)
            rect(a, xs[1], 0, xs[1] + 1, h, WHITE_PAPER)
            for y in range(0, h, 2):
                rect(a, (xs[0] + xs[1]) // 2, y, (xs[0] + xs[1]) // 2 + 1, y + 1, WHITE_PAPER)
            if vase and face in ("north", "south", "up"):
                cx = w * 0.62 if at_lo else w * 0.38
                top = max(0, int(h * 0.15))
                rect(a, cx - 0.5, top, cx + 0.5, top + 1, WHITE_PAPER)
                rect(a, cx - 1.5, top + 1, cx + 1.5, min(h, top + 5), WHITE_PAPER)
                rect(a, cx - 1.5, top + 2, cx + 1.5, top + 3, GOLD)
                rect(a, cx - 2.5, top + 1, cx - 1.5, top + 3, WHITE_PAPER)
                rect(a, cx + 1.5, top + 1, cx + 2.5, top + 3, WHITE_PAPER)
        yy, xx = np.mgrid[0:h, 0:w]
        x0 = rng.uniform(0.3, 0.7) * w
        tone(a, 0.8, np.abs(xx - (x0 + 0.6 * (yy - h / 2))) < 0.6)
        if face == "up":
            tone(a, 1.0 + 0.14 * np.cos((yy + 0.5 - h / 2) / h * math.pi))
        else:
            tone(a, 1.06 - 0.3 * (yy / max(1, h - 1)))
        grain(a, rng, 0.04)
    return cup_body


cup_body_bottom = cup_painter("cup_body_bottom", "west", False)
cup_body_mouth = cup_painter("cup_body_mouth", "east", True)


@P("cup_mouth")
def cup_mouth(a, rng, face):
    h, w = a.shape[:2]
    fill(a, ANTHORA_BLUE)
    outer = ellipse_mask(h, w, w / 2, h / 2, w / 2, h / 2)
    inner = ellipse_mask(h, w, w / 2, h / 2, w / 2 - 1.2, h / 2 - 1.2)
    paint(a, outer, WHITE_PAPER)
    paint(a, inner, (62, 44, 32))
    stain = ellipse_mask(h, w, w * 0.55, h * 0.62, w * 0.25, h * 0.2) & inner
    paint(a, stain, (96, 66, 42))
    grain(a, rng, 0.06)


@P("cup_bottom")
def cup_bottom(a, rng, face):
    h, w = a.shape[:2]
    fill(a, ANTHORA_BLUE)
    paint(a, ellipse_mask(h, w, w / 2, h / 2, w / 2, h / 2), (222, 220, 210))
    paint(a, ellipse_mask(h, w, w / 2, h / 2, w / 2 - 1.3, h / 2 - 1.3), (200, 198, 190))
    grain(a, rng, 0.05)


def can_painter(body, stripe, name):
    @per_face
    @P(name)
    def can_side(a, rng, face):
        h, w = a.shape[:2]
        fill(a, body)
        yy, xx = np.mgrid[0:h, 0:w]
        wave = h * 0.55 + h * 0.12 * np.sin(xx / 6.0 * 2 * math.pi)
        paint(a, np.abs(yy + 0.5 - wave) < 0.9, stripe)
        if face == "up":
            tone(a, 1.0 + 0.22 * np.exp(-((yy + 0.5 - h * 0.45) / (h * 0.2)) ** 2))
        else:
            tone(a, 1.12 - 0.36 * (yy / max(1, h - 1)))
        grain(a, rng, 0.03)
    return can_side


can_red_side = can_painter((186, 26, 32), (238, 236, 232), "can_red_side")
can_silver_side = can_painter((182, 186, 192), (32, 70, 160), "can_silver_side")


@P("can_end")
def can_end(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (150, 152, 156))
    paint(a, ellipse_mask(h, w, w / 2, h / 2, w / 2 - 0.3, h / 2 - 0.3), (200, 202, 206))
    paint(a, ellipse_mask(h, w, w / 2, h / 2, w / 2 - 1.3, h / 2 - 1.3), (170, 172, 176))
    paint(a, ellipse_mask(h, w, w * 0.5, h * 0.36, 1.2, 0.8), (214, 216, 220))


NEWSPRINT = (214, 210, 196)


@P("news_page")
def news_page(a, rng, face):
    """Inside spread: columns of grey text, a photo, a small headline."""
    h, w = a.shape[:2]
    fill(a, NEWSPRINT)
    cols = max(2, w // 7)
    cw = w / cols
    for c in range(cols):
        x0 = c * cw + 1
        for y in range(2, h - 1, 2):
            if rng.random() < 0.88:
                rect(a, x0, y, x0 + cw - 2 - (rng.random() < 0.2) * 2, y + 1, (150, 146, 136))
    px0, py0 = rng.integers(1, max(2, w // 2)), rng.integers(3, max(4, h // 2))
    rect(a, px0, py0, px0 + w * 0.38, py0 + h * 0.32, (112, 110, 104))
    rect(a, px0 + 1, py0 + 1, px0 + w * 0.2, py0 + h * 0.18, (150, 148, 140))
    rect(a, 1, 0.5, w * 0.7, 2, (46, 44, 42))
    grain(a, rng, 0.05, 3)


@P("news_front")
def news_front(a, rng, face):
    """A front page: masthead bar, big black headline, photo."""
    h, w = a.shape[:2]
    fill(a, NEWSPRINT)
    rect(a, 1, 1, w - 1, 3, (36, 34, 34))
    rect(a, 1, 3.5, w - 1, 4, (176, 30, 34))
    rect(a, 1, 5, w * 0.92, 7.5, (30, 30, 30))
    rect(a, 1, 8.5, w * 0.6, 10, (30, 30, 30))
    rect(a, w * 0.5, 11, w - 1, h - 2, (104, 102, 96))
    rect(a, w * 0.55, 12, w * 0.8, h - 5, (140, 138, 130))
    for y in range(11, h - 1, 2):
        rect(a, 1, y, w * 0.45, y + 1, (150, 146, 136))
    grain(a, rng, 0.05, 3)


@P("paper_edge")
def paper_edge(a, rng, face):
    fill(a, (200, 196, 182))


@P("metrocard_top")
def metrocard_top(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (246, 200, 18))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, 1.0 + 0.05 * (xx / max(1, w)) - 0.05 * (yy / max(1, h)))
    # "MetroCard" wordmark: blue letter blocks in one line, then a thin text line
    x, y = 1, int(h * 0.25)
    while x < w - 1:
        rect(a, x, y, x + 1, y + 2, (24, 58, 150))
        x += 1 + (1 if rng.random() < 0.7 else 0)
    rect(a, 1, h - 2, w * 0.55, h - 1, (64, 52, 20))


@P("metrocard_edge")
def metrocard_edge(a, rng, face):
    fill(a, (226, 182, 16))


@per_face
@P("chips_bag")
def chips_bag(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (214, 64, 28))
    yy, xx = np.mgrid[0:h, 0:w]
    if face == "up":
        paint(a, ellipse_mask(h, w, w * 0.45, h * 0.5, w * 0.28, h * 0.3), (250, 204, 40))
        rect(a, w * 0.3, h * 0.45, w * 0.6, h * 0.45 + 1, (170, 30, 20))
        paint(a, ellipse_mask(h, w, w * 0.78, h * 0.6, w * 0.1, h * 0.14), (240, 180, 70))
    else:
        rect(a, 0, h * 0.35, w, h * 0.35 + 1, (250, 204, 40))
    # foil sheen + crinkles
    tone(a, 1.0 + 0.22 * np.sin(xx * 1.7 + yy * 0.9 + rng.uniform(0, 6)) * (rng.random((h, w)) < 0.5))
    tone(a, 0.75, rng.random((h, w)) < 0.05)


@P("chips_crimp")
def chips_crimp(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (196, 196, 200))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, np.where((xx + yy) % 2 == 0, 1.08, 0.86))


@P("candy")
def candy(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (116, 42, 150))
    rect(a, w * 0.2, h * 0.35, w * 0.8, h * 0.35 + 1, (246, 242, 250))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, 1.0 + 0.2 * np.sin(xx * 2.1 + yy) * (rng.random((h, w)) < 0.5))


@P("candy_twist")
def candy_twist(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (150, 70, 176))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, np.where((xx + 2 * yy) % 3 == 0, 0.78, 1.08))


@per_face
@P("butt")
def butt(a, rng, face):
    """Along u: filter (tan, speckled) then white paper then a grey ash tip."""
    h, w = a.shape[:2]
    fill(a, (214, 150, 76))
    tone(a, 0.82, rng.random((h, w)) < 0.3)
    f = int(round(w * 0.62))
    rect(a, f, 0, w, h, (232, 230, 222))
    rect(a, f - 1, 0, f, h, (200, 196, 186))
    rect(a, w - max(1, w // 7), 0, w, h, (92, 90, 88))
    if face in ("north", "south"):
        tone(a, 0.85, np.mgrid[0:h, 0:w][0] == h - 1)


@P("butt_end")
def butt_end(a, rng, face):
    fill(a, (224, 170, 96))


@P("butt_ash_end")
def butt_ash_end(a, rng, face):
    fill(a, (70, 68, 66))


@P("ash")
def ash(a, rng, face):
    h, w = a.shape[:2]
    a[...] = 0
    n = smooth_noise(rng, h, w, 3)
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot((xx + 0.5 - w / 2) / (w / 2), (yy + 0.5 - h / 2) / (h / 2))
    m = (r + 0.35 * n) < 0.75
    speck = rng.random((h, w)) < 0.55
    paint(a, m & speck, (96, 93, 89))
    paint(a, m & ~speck & (rng.random((h, w)) < 0.4), (130, 127, 122))


@P("receipt")
def receipt(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (242, 240, 234))
    for y in range(2, h - 1, 2):
        if rng.random() < 0.75:
            x1 = rng.integers(max(2, w // 2), w)
            rect(a, 1, y, x1 - 1, y + 1, (168, 166, 160))
    rect(a, w * 0.4, 0, w - 1, 1, (120, 118, 114))
    grain(a, rng, 0.03)


@P("receipt_edge")
def receipt_edge(a, rng, face):
    fill(a, (224, 222, 214))


@per_face
@P("bottle_body")
def bottle_body(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (34, 104, 46))
    yy, xx = np.mgrid[0:h, 0:w]
    l0, l1 = int(w * 0.28), int(w * 0.72)
    rect(a, l0, 0, l1, h, (226, 212, 168))
    rect(a, l0, h * 0.3, l1, h * 0.3 + 2, (182, 40, 34))
    rect(a, l0 + 1, h * 0.62, l1 - 1, h * 0.62 + 1, (90, 70, 50))
    if face == "up":
        hl = np.abs(yy + 0.5 - h * 0.42) < max(0.6, h * 0.1)
        tone(a, 1.0 + 0.15 * np.cos((yy + 0.5 - h / 2) / h * math.pi))
        tone(a, 1.9, hl & ((xx < l0) | (xx >= l1)))
    else:
        tone(a, 1.12 - 0.42 * (yy / max(1, h - 1)))
        tone(a, 1.7, (yy == 1) & ((xx < l0) | (xx >= l1)))


@P("glass")
def glass(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (30, 96, 42))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, 1.6, yy == min(1, h - 1))


@P("bottle_base")
def bottle_base(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (26, 84, 36))
    paint(a, ellipse_mask(h, w, w / 2, h / 2, w * 0.3, h * 0.3), (44, 120, 56))


@P("bottle_mouth")
def bottle_mouth(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (52, 130, 64))
    paint(a, ellipse_mask(h, w, w / 2, h / 2, w * 0.3, h * 0.3), (10, 24, 12))


@per_face
@P("crust")
def crust(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (206, 148, 72))
    yy, xx = np.mgrid[0:h, 0:w]
    n = smooth_noise(rng, h, w, 2)
    tone(a, 1.0 + 0.16 * n)
    paint(a, (rng.random((h, w)) < 0.12), (150, 92, 40))
    paint(a, (rng.random((h, w)) < 0.03), (78, 48, 24))
    if face == "south":   # the inner (bitten) side: sauce + cheese line along the top
        rect(a, 0, 0, w, 1, (232, 196, 104))
        rect(a, 0, 1, w, 2, (196, 66, 30))
    if face == "up":
        tone(a, 1.12, yy < h * 0.4)


@P("crust_end")
def crust_end(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (238, 214, 160))
    rect(a, 0, 0, w, 1, (196, 66, 30))
    tone(a, 0.92, rng.random((h, w)) < 0.3)


@per_face
@P("plastic_bag")
def plastic_bag(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (238, 238, 234))
    yy, xx = np.mgrid[0:h, 0:w]
    n = smooth_noise(rng, h, w, 3)
    tone(a, 1.0 - 0.1 * np.abs(n))
    for k in range(3):
        x0 = rng.uniform(0, w)
        sl = rng.uniform(-1.2, 1.2)
        tone(a, 0.84, np.abs(xx - x0 - sl * (yy - h / 2)) < 0.6)
    if face in ("north", "south", "up") and w >= 8:
        # red THANK YOU rows
        for row in (0.28, 0.48):
            x = int(w * 0.18)
            y = int(h * row)
            while x < w * 0.82:
                rect(a, x, y, x + 1, y + 1 + (rng.random() < 0.5), (204, 30, 36))
                x += 2
        if face == "up":
            paint(a, ellipse_mask(h, w, w * 0.5, h * 0.78, 1.6, 1.4) & ~ellipse_mask(h, w, w * 0.5, h * 0.78, 0.9, 0.7),
                  (204, 30, 36))


# ---------------------------------------------------------------- pieces (local coords, centred, y from 0)

PIECE_TEX = {}


def ptex(name, size=64, density=4):
    if name not in PIECE_TEX:
        PIECE_TEX[name] = Tex("piece_" + name, size, density)
    return PIECE_TEX[name]


def twin_x(el):
    """The element again, rotated 45 deg about its own long (x) axis: the pair
    reads as a round tube at this size (the project's octagonal-pipe trick)."""
    (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
    e = json.loads(json.dumps({k: v for k, v in el.items() if k != "_tex"}))
    e["_tex"] = dict(el["_tex"])
    e["faces"].pop("east", None)
    e["faces"].pop("west", None)
    sh = 0.08 * (z1 - z0)
    e["from"] = [x0 + 0.02, y0 + sh, z0 + sh]
    e["to"] = [x1 - 0.02, y1 - sh, z1 - sh]
    e["rotation"] = {"axis": "x", "angle": 45, "origin": [0, (y0 + y1) / 2, (z0 + z1) / 2]}
    return e


def piece_cup():
    t = ptex("cup")
    a = box((-1.9, 0, -0.95), (0.05, 1.9, 0.95), t, cup_body_bottom, faces="nswu", over={"w": cup_bottom})
    return [
        a, twin_x(a),
        box((0, 0, -1.25), (2.0, 2.0, 1.25), t, cup_body_mouth, faces="nsewu", over={"e": cup_mouth},
            rot=("y", -22.5, (0, 0, 0))),
    ]


def piece_can(side, name):
    t = ptex(name)
    a = box((-1.6, 0, -0.9), (-0.8, 1.8, 0.9), t, side, faces="nswu", over={"w": can_end})
    return [
        a, twin_x(a),
        box((-0.85, 0, -1.12), (0.85, 1.15, 1.12), t, side, faces="nsu"),
        box((0.75, 0, -0.88), (1.55, 1.75, 0.88), t, side, faces="nseu", over={"e": can_end},
            rot=("y", 22.5, (0.8, 0, 0))),
    ]


def piece_newspaper():
    t = ptex("newspaper", 64, 4)
    return [
        box((-3.6, 0, -2.6), (0.05, 0.18, 2.6), t, paper_edge, faces="nswu", over={"u": news_page}),
        box((0, 0, -2.5), (3.4, 0.16, 2.5), t, paper_edge, faces="nseud",
            over={"u": news_front, "d": news_page}, rot=("z", 22.5, (0, 0.17, 0))),
    ]


def piece_metrocard():
    t = ptex("metrocard")
    return [box((-1.35, 0, -0.85), (1.35, 0.12, 0.85), t, metrocard_edge, faces="nsewu",
                over={"u": metrocard_top})]


def piece_chips():
    t = ptex("chips")
    return [
        box((-1.45, 0, -1.1), (1.45, 1.0, 1.1), t, chips_bag, faces="nsu"),
        box((1.4, 0.1, -1.2), (1.95, 0.72, 1.2), t, chips_crimp, faces="nseu"),
        box((-1.95, 0.1, -1.2), (-1.4, 0.72, 1.2), t, chips_crimp, faces="nswu"),
        box((-0.8, 0.9, -0.7), (0.6, 1.32, 0.5), t, chips_bag, faces="nsewu", rot=("y", 22.5, (0, 0, 0))),
    ]


def piece_candy():
    t = ptex("candy")
    return [
        box((-0.8, 0, -0.5), (0.8, 0.36, 0.5), t, candy, faces="nsu"),
        box((0.75, 0, -0.62), (1.4, 0.26, 0.62), t, candy_twist, faces="nseu", rot=("y", 22.5, (0.8, 0, 0))),
        box((-1.4, 0, -0.62), (-0.75, 0.26, 0.62), t, candy_twist, faces="nswu", rot=("y", -22.5, (-0.8, 0, 0))),
    ]


def piece_butts():
    t = ptex("butts")
    els = [box((-1.55, 0.0, -1.5), (1.55, 0.03, 1.5), t, ash, faces="u")]
    for (cx, cz, thick, ang) in ((-0.7, -0.55, 0.55, 22.5), (0.75, 0.45, 0.5, -22.5), (0.55, -0.95, 0.6, 45),
                                 (-0.55, 0.85, 0.52, 0)):
        L = 1.6
        els.append(box((cx - L / 2, 0, cz - thick / 2), (cx + L / 2, thick, cz + thick / 2), t, butt, faces="nsewu",
                       over={"w": butt_end, "e": butt_ash_end}, rot=("y", ang, (cx, 0, cz)) if ang else None))
    return els


def piece_receipt():
    t = ptex("receipt")
    return [
        box((-0.6, 0, -1.7), (0.6, 0.08, 0.95), t, receipt_edge, faces="nsewu", over={"u": receipt}),
        box((-0.6, 0, 0.9), (0.6, 0.08, 2.1), t, receipt_edge, faces="nsewud",
            over={"u": receipt, "d": receipt}, rot=("x", -45, (0, 0.04, 0.9))),
    ]


def piece_bottle():
    t = ptex("bottle")
    return [
        box((-2.4, 0, -0.85), (1.0, 1.7, 0.85), t, bottle_body, faces="nswu", over={"w": bottle_base}),
        box((0.95, 0.2, -0.65), (1.6, 1.5, 0.65), t, glass, faces="nseu"),
        box((1.55, 0.5, -0.35), (2.8, 1.2, 0.35), t, glass, faces="nsu"),
        box((2.75, 0.45, -0.42), (3.05, 1.25, 0.42), t, glass, faces="nseu", over={"e": bottle_mouth}),
    ]


def piece_crust():
    t = ptex("crust")
    return [
        box((-1.2, 0, -0.42), (1.2, 0.9, 0.5), t, crust, faces="nsu"),
        box((1.0, 0, -0.4), (2.35, 0.85, 0.48), t, crust, faces="nseu", over={"e": crust_end},
            rot=("y", -22.5, (1.1, 0, 0))),
        box((-2.35, 0, -0.4), (-1.0, 0.86, 0.48), t, crust, faces="nswu", over={"w": crust_end},
            rot=("y", 22.5, (-1.1, 0, 0))),
    ]


def piece_plastic_bag():
    t = ptex("plastic_bag")
    return [
        box((-1.6, 0, -1.3), (1.4, 1.5, 1.2), t, plastic_bag, faces="nsewu"),
        box((-0.9, 0, -0.9), (1.0, 2.1, 0.8), t, plastic_bag, faces="nsewu", rot=("y", 22.5, (0, 0, 0))),
        box((1.25, 0.3, -0.75), (1.55, 1.45, -0.25), t, plastic_bag, faces="nsewu", rot=("z", -22.5, (1.3, 0.3, 0))),
        box((1.25, 0.3, 0.2), (1.55, 1.35, 0.7), t, plastic_bag, faces="nsewu", rot=("z", -45, (1.3, 0.3, 0))),
    ]


PIECES = [  # (name, builder, weight, scale) — scaled up ~1.3x so they read from standing height
    ("cup", piece_cup, 4, 1.3),
    ("can_red", lambda: piece_can(can_red_side, "can_red"), 3, 1.3),
    ("can_silver", lambda: piece_can(can_silver_side, "can_silver"), 2, 1.3),
    ("newspaper", piece_newspaper, 3, 1.0),
    ("metrocard", piece_metrocard, 2, 1.3),
    ("chips", piece_chips, 2, 1.3),
    ("candy", piece_candy, 2, 1.3),
    ("butts", piece_butts, 3, 1.15),
    ("receipt", piece_receipt, 3, 1.2),
    ("bottle", piece_bottle, 2, 1.25),
    ("crust", piece_crust, 1, 1.3),
    ("plastic_bag", piece_plastic_bag, 2, 1.3),
]
# two jittered spots per slot (block px); pieces in slot k sit at SLOT_Y[k]
SLOTS = [((4.6, 4.5), (5.3, 3.9)), ((11.3, 11.2), (10.7, 12.0)), ((11.5, 4.7), (12.0, 5.5)), ((4.7, 11.5), (4.0, 10.8))]
SLOT_Y = [0.007, 0.023, 0.041, 0.063]
SLOT_TOTAL_BUMP = [0, 1, 3, 7]   # -> totals 232 / 233 / 235 / 239


def build_litter():
    built = {name: fn() for name, fn, _, _ in PIECES}
    parts = []
    for k, spots in enumerate(SLOTS):
        entries = []
        for name, _, weight, scale in PIECES:
            for si, (cx, cz) in enumerate(spots):
                for q in range(4):
                    els = [xform(e, q, cx, SLOT_Y[k], cz, scale) for e in built[name]]
                    ref = model(f"{name}_s{k}_{si}_{q}", els, PIECE_TEX[name].ref)
                    entries.append({"model": ref, "weight": weight})
        entries[0]["weight"] += SLOT_TOTAL_BUMP[k]
        when = {"count": "|".join(str(c) for c in range(k + 1, 5))}
        parts.append({"when": when, "apply": entries} if k else {"apply": entries})
    write_blockstate("litter", {"multipart": parts})
    return built


# ---------------------------------------------------------------- shared tile textures

def _bag_plastic(a, rng):
    """Glossy black bin-bag film: big soft sheen patches, a few stretched
    wrinkles (lit ridge + dark fold), sparse specular glints. Tiles."""
    h, w = a.shape[:2]
    fill(a, (34, 34, 40))
    yy, xx = np.mgrid[0:h, 0:w]

    def wrap_noise(cell):
        n = smooth_noise(rng, h + cell * 2, w + cell * 2, cell)[:h, :w]
        return n

    sheen = wrap_noise(8)
    tone(a, 1.0 + 0.35 * sheen)
    ridge = np.zeros((h, w), bool)
    fold = np.zeros((h, w), bool)
    for _ in range(7):
        x0 = rng.uniform(0, w)
        y0 = rng.uniform(0, h)
        ang = rng.uniform(-1.2, 1.2) + (math.pi / 2 if rng.random() < 0.5 else 0)
        L = rng.uniform(6, 14)
        for t_ in np.linspace(0, L, int(L * 3)):
            x = int(x0 + math.cos(ang) * t_ + 1.2 * math.sin(t_ * 0.5)) % w
            y = int(y0 + math.sin(ang) * t_) % h
            ridge[y, x] = True
            fold[(y + 1) % h, (x + 1) % w] = True
    tone(a, 0.72, fold & ~ridge)
    tone(a, 1.6, ridge)
    glint = (sheen > 0.45) & (rng.random((h, w)) < 0.12)
    paint(a, glint, (116, 120, 134))


def _liner(a, rng):
    h, w = a.shape[:2]
    fill(a, (26, 26, 30))
    yy, xx = np.mgrid[0:h, 0:w]
    ridge = np.abs(np.sin((xx + 2.0 * smooth_noise(rng, h, w, 6)) * math.pi / 4.1))
    tone(a, 0.85 + 0.7 * ridge ** 6)


def _trash_fill(a, rng):
    """The inside of a full can seen from above: paper, cups, cans, wrappers on dark."""
    h, w = a.shape[:2]
    fill(a, (52, 50, 48))
    cols = [((222, 218, 204), 6), ((240, 240, 236), 5), (ANTHORA_BLUE, 3), ((186, 30, 36), 2),
            ((214, 64, 28), 2), ((246, 200, 18), 1), ((176, 132, 80), 3), ((30, 96, 42), 1),
            ((150, 146, 136), 3), ((26, 26, 30), 3)]
    pool = [c for c, n in cols for _ in range(n)]
    for _ in range(int(h * w / 5)):
        c = pool[rng.integers(len(pool))]
        x, y = rng.integers(0, w), rng.integers(0, h)
        bw, bh = rng.integers(2, 6), rng.integers(1, 4)
        if rng.random() < 0.5:
            bw, bh = bh, bw
        for dx in range(bw):
            for dy in range(bh):
                a[(y + dy) % h, (x + dx) % w, :3] = c
    tone(a, 1.0 + 0.12 * smooth_noise(rng, h, w, 3))
    tone(a, 0.7, rng.random((h, w)) < 0.08)


def _dust(a, rng):
    """Swept station dust: fine grey-brown grit, the odd fleck of paper/filter."""
    h, w = a.shape[:2]
    fill(a, (128, 121, 110))
    tone(a, 1.0 + 0.08 * smooth_noise(rng, h, w, 3))
    tone(a, 1.0 + 0.07 * (rng.random((h, w)) - 0.5))
    tone(a, 0.88, rng.random((h, w)) < 0.12)
    bits = [(232, 230, 222), (214, 150, 76), (176, 132, 80), (80, 76, 70), (200, 196, 182)]
    for _ in range(int(h * w / 70)):
        c = bits[rng.integers(len(bits))]
        x, y = rng.integers(0, w), rng.integers(0, h)
        a[y, x, :3] = c


BAG = TileTex("trash_bag", 32, _bag_plastic)
LINER = TileTex("trash_liner", 32, _liner)
FILL = TileTex("trash_fill", 32, _trash_fill)
DUST = TileTex("dust", 32, _dust)
TILES = [BAG, LINER, FILL, DUST]


def tile_face(el, face, tex, alias=None):
    """Point one face of an element at a TileTex with auto-style uv."""
    alias = alias or tex.name
    el["faces"][face] = {"uv": auto_uv(face, el["from"], el["to"]), "texture": "#" + alias}
    el["_tex"][alias] = tex.ref
    return el


# ---------------------------------------------------------------- trash bags

def bag_standing(cx, cz, y0, w, d, h):
    """A tied bag standing up: narrow foot, full belly, a 45-degree twin of the
    belly (the pair reads round), a tapering shoulder, neck and the knot's ears."""
    belly = y0 + h * 0.62
    top = y0 + h * 0.76
    sh = y0 + h * 0.87
    nk = sh + 1.0
    els = [
        tbox((cx - w / 2 + 0.8, y0, cz - d / 2 + 0.8), (cx + w / 2 - 0.8, y0 + 0.7, cz + d / 2 - 0.8), BAG, "nsew"),
        tbox((cx - w / 2, y0 + 0.65, cz - d / 2), (cx + w / 2, belly, cz + d / 2), BAG, "nsewu"),
        tbox((cx - w * 0.47, y0 + 0.4, cz - d * 0.47), (cx + w * 0.47, belly - 0.35, cz + d * 0.47), BAG, "nsewu",
             rot=("y", 45, (cx, 0, cz))),
        tbox((cx - w / 2 + 0.6, belly - 0.01, cz - d / 2 + 0.6), (cx + w / 2 - 0.6, top, cz + d / 2 - 0.6), BAG,
             "nsewu"),
        tbox((cx - w * 0.36, belly - 0.2, cz - d * 0.36), (cx + w * 0.36, top - 0.3, cz + d * 0.36), BAG, "nsewu",
             rot=("y", 45, (cx, 0, cz))),
        tbox((cx - w * 0.3, top - 0.01, cz - d * 0.3), (cx + w * 0.3, sh, cz + d * 0.3), BAG, "nsewu"),
        tbox((cx - 0.85, sh - 0.01, cz - 0.75), (cx + 0.85, nk, cz + 0.75), BAG, "nsewu"),
        # the knot's two ears
        tbox((cx - 0.95, nk - 0.3, cz - 0.55), (cx - 0.35, nk + 1.7, cz + 0.55), BAG, "nsewu",
             rot=("z", 22.5, (cx - 0.65, nk - 0.3, cz))),
        tbox((cx + 0.35, nk - 0.3, cz - 0.45), (cx + 0.95, nk + 1.5, cz + 0.45), BAG, "nsewu",
             rot=("z", -22.5, (cx + 0.65, nk - 0.3, cz))),
    ]
    return els


def bag_lying(cx, cz, y0, L, d, h):
    """A bag on its side, knot pointing +x."""
    x0, x1 = cx - L / 2, cx + L / 2 - 1.6
    yc = y0 + h / 2
    els = [
        tbox((x0 + 0.5, y0, cz - d / 2), (x1, y0 + h, cz + d / 2), BAG, "nsewu"),
        tbox((x0 + 0.8, y0 + h * 0.06, cz - d * 0.47), (x1 - 0.2, y0 + h * 0.94, cz + d * 0.47), BAG, "nsewu",
             rot=("x", 45, (cx, yc, cz))),
        tbox((x0, y0 + h * 0.18, cz - d * 0.36), (x0 + 0.51, y0 + h * 0.82, cz + d * 0.36), BAG, "nswu"),
        tbox((x1 - 0.01, y0 + h * 0.16, cz - d * 0.3), (x1 + 0.9, y0 + h * 0.8, cz + d * 0.3), BAG, "nseu"),
        tbox((x1 + 0.89, yc - 0.75, cz - 0.8), (x1 + 1.75, yc + 0.75, cz + 0.8), BAG, "nseu"),
        tbox((x1 + 1.6, yc - 0.45, cz - 0.95), (x1 + 3.4, yc + 0.45, cz - 0.4), BAG, "nseuw",
             rot=("y", 22.5, (x1 + 1.6, yc, cz - 0.65))),
        tbox((x1 + 1.6, yc - 0.4, cz + 0.4), (x1 + 3.2, yc + 0.4, cz + 0.95), BAG, "nseuw",
             rot=("y", -22.5, (x1 + 1.6, yc, cz + 0.65))),
    ]
    return els


def build_bags():
    heaps = {
        1: [bag_standing(8, 8, 0, 8.6, 7.4, 10.4),
            bag_lying(7.2, 8.2, 0, 12.2, 7.6, 6.4)],
        2: [bag_standing(5.6, 5.9, 0, 7.6, 6.9, 10.2) + bag_lying(7.9, 11.7, 0, 12.0, 6.5, 5.7),
            bag_standing(5.1, 5.4, 0, 7.2, 6.6, 9.6) + bag_standing(10.9, 10.6, 0, 7.8, 7.0, 10.8)],
        3: [bag_lying(7.1, 4.6, 0, 12.4, 6.7, 5.9) + bag_lying(7.8, 11.5, 0, 12.0, 6.5, 5.6)
            + bag_standing(8.2, 8.1, 4.9, 7.2, 6.4, 8.6),
            bag_standing(4.6, 11.3, 0, 7.0, 6.6, 10.6) + bag_standing(11.4, 11.2, 0, 7.4, 6.4, 10.0)
            + bag_lying(7.4, 4.3, 0, 12.6, 6.6, 6.0)],
    }
    variants = {}
    for n, opts in heaps.items():
        lst = []
        for i, els in enumerate(opts):
            ref = model(f"trash_bags_{n}_{'ab'[i]}", els, BAG.ref, parent="minecraft:block/block")
            for q in range(4):
                v = {"model": ref}
                if q:
                    v["y"] = 90 * q
                lst.append(v)
        variants[f"bags={n}"] = lst
    write_blockstate("trash_bags", {"variants": variants})
    AC.item_block("trash_bags", "litter/trash_bags_1_a")
    WRITTEN.append(os.path.join(ASSETS, "models/item/trash_bags.json"))


# ---------------------------------------------------------------- cardboard

KRAFT = (176, 132, 80)


def cardboard_print(variant):
    @P("cardboard_print_" + variant)
    def pr(a, rng, face):  # noqa: C901
        h, w = a.shape[:2]
        fill(a, KRAFT)
        tone(a, 1.0 + 0.07 * smooth_noise(rng, h, w, 4))
        tone(a, 1.0 + 0.04 * smooth_noise(rng, h, w, 1))
        # flap creases across the ends, a panel fold down the middle, slits between flaps
        for v in (int(h * 0.2), int(h * 0.8)):
            rect(a, 0, v, w, v + 1, (138, 100, 58))
        f = int(w * (0.56 if variant == "a" else 0.48))
        rect(a, f, 0, f + 1, h, (138, 100, 58))
        rect(a, f, 0, f + 1, int(h * 0.2), (70, 50, 30))
        rect(a, f, int(h * 0.8) + 1, f + 1, h, (70, 50, 30))
        # packing tape along the flap seam
        rect(a, 0, int(h * 0.2) - 2, w, int(h * 0.2) + 2, (206, 176, 120))
        tone(a, 1.08, np.zeros((h, w), bool) | (np.mgrid[0:h, 0:w][0] == int(h * 0.2) - 2))
        if variant == "a":
            # THIS SIDE UP arrows + a white shipping label
            for x in (int(w * 0.14), int(w * 0.24)):
                rect(a, x, h * 0.36, x + 1, h * 0.6, (34, 30, 26))
                rect(a, x - 1, h * 0.38, x + 2, h * 0.38 + 1, (34, 30, 26))
            rect(a, w * 0.64, h * 0.34, w * 0.92, h * 0.62, (236, 234, 228))
            for y in range(int(h * 0.38), int(h * 0.6), 2):
                rect(a, w * 0.67, y, w * 0.88, y + 1, (110, 108, 104))
            rect(a, w * 0.67, h * 0.36, w * 0.75, h * 0.36 + 1, (30, 30, 30))
        else:
            # printed brand panel + a red FRAGILE band
            rect(a, w * 0.1, h * 0.34, w * 0.42, h * 0.5, (40, 70, 150))
            rect(a, w * 0.14, h * 0.4, w * 0.38, h * 0.4 + 1, (226, 230, 240))
            rect(a, w * 0.58, h * 0.56, w * 0.9, h * 0.66, (190, 40, 36))
            rect(a, w * 0.62, h * 0.6, w * 0.86, h * 0.6 + 1, (240, 220, 210))
        # scuffs and a damp stain
        m = ellipse_mask(h, w, rng.uniform(0.2, 0.8) * w, rng.uniform(0.3, 0.7) * h, w * 0.14, h * 0.12)
        tone(a, 0.86, m)
    pr.fixed = (28, 22)
    return pr


@P("corrugated")
def corrugated(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (190, 150, 98))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, np.where(xx % 2 == 0, 1.0, 0.8))


corrugated.fixed = (24, 2)

@P("kraft_plain")
def kraft_plain(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (160, 118, 70))
    tone(a, 1.0 + 0.08 * smooth_noise(rng, h, w, 3))


kraft_plain.fixed = (12, 12)


@P("box_side")
def box_side(a, rng, face):
    h, w = a.shape[:2]
    fill(a, KRAFT)
    tone(a, 1.0 + 0.07 * smooth_noise(rng, h, w, 3))
    rect(a, w * 0.15, h * 0.3, w * 0.5, h * 0.62, (40, 70, 150))
    rect(a, w * 0.2, h * 0.42, w * 0.45, h * 0.42 + 1, (226, 230, 240))
    rect(a, 0, h * 0.1, w, h * 0.1 + 2, (206, 176, 120))


box_side.fixed = (18, 10)


@P("kraft_inside")
def kraft_inside(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (150, 112, 68))
    tone(a, 1.0 + 0.08 * smooth_noise(rng, h, w, 3))


kraft_inside.fixed = (12, 12)


CARD = Tex("cardboard", 64, 2)
PRINT_A = cardboard_print("a")
PRINT_B = cardboard_print("b")


def sheet(cx, cz, y0, w, d, pr, ang=0.0):
    t = 0.6
    return box((cx - w / 2, y0, cz - d / 2), (cx + w / 2, y0 + t, cz + d / 2), CARD, corrugated, faces="nsewu",
               over={"u": pr}, rot=("y", ang, (cx, 0, cz)) if ang else None)


def open_box(cx, cz, y0, w, d, h):
    """A half-crushed open box: four walls, flaps splayed out at 45 degrees."""
    th = 0.4
    x0, x1, z0, z1, y1 = cx - w / 2, cx + w / 2, cz - d / 2, cz + d / 2, y0 + h
    side = box_side
    els = [
        box((x0, y0, z0), (x1, y1, z0 + th), CARD, side, faces="nsu", over={"s": kraft_inside, "u": corrugated}),
        box((x0, y0, z1 - th), (x1, y1 - 0.6, z1), CARD, side, faces="nsu", over={"n": kraft_inside, "u": corrugated}),
        box((x0, y0, z0 + th), (x0 + th, y1 - 0.3, z1 - th), CARD, side, faces="ewu", over={"e": kraft_inside, "u": corrugated}),
        box((x1 - th, y0, z0 + th), (x1, y1, z1 - th), CARD, side, faces="ewu", over={"w": kraft_inside, "u": corrugated}),
        box((x0 + th, y0, z0 + th), (x1 - th, y0 + 0.3, z1 - th), CARD, kraft_inside, faces="u"),
        # flaps (hinged on the top edge of each wall)
        box((x0 + 0.1, y1 - th, z0 - 2.4), (x1 - 0.1, y1, z0 + 0.05), CARD, kraft_plain, faces="nsewud",
            over={"u": kraft_inside}, rot=("x", 45, (cx, y1, z0))),
        box((x0 + 0.1, y1 - 0.6 - th, z1 - 0.05), (x1 - 0.1, y1 - 0.6, z1 + 2.2), CARD, kraft_plain, faces="nsewud",
            over={"u": kraft_inside}, rot=("x", -22.5, (cx, y1 - 0.6, z1))),
        box((x1 - 0.05, y1 - th, z0 + 0.1), (x1 + 2.0, y1, z1 - 0.1), CARD, kraft_plain, faces="nsewud",
            over={"u": kraft_inside}, rot=("z", 45, (x1, y1, cz))),
    ]
    return els


def build_cardboard():
    s1a = [sheet(8, 8, 0, 12, 9.6, PRINT_A, 22.5)]
    s1b = [sheet(8, 8, 0, 13, 10.6, PRINT_B)]
    heaps = {
        1: [s1a, s1b],
        2: [s1a + [sheet(8.4, 7.6, 0.6, 10, 8.2, PRINT_B, -22.5)],
            s1b + [sheet(7.6, 8.4, 0.6, 11, 8.6, PRINT_A, 22.5)]],
    }
    heaps[3] = [heaps[2][0] + open_box(8, 8.3, 1.2, 7.2, 5.6, 4.2),
                heaps[2][1] + open_box(7.6, 8.0, 1.2, 6.6, 6.0, 4.6)]
    variants = {}
    for n, opts in heaps.items():
        lst = []
        for i, els in enumerate(opts):
            ref = model(f"cardboard_pile_{n}_{'ab'[i]}", els, CARD.ref, parent="minecraft:block/block")
            for q in range(4):
                v = {"model": ref}
                if q:
                    v["y"] = 90 * q
                lst.append(v)
        variants[f"layers={n}"] = lst
    write_blockstate("cardboard_pile", {"variants": variants})
    AC.item_block("cardboard_pile", "litter/cardboard_pile_2_a")
    WRITTEN.append(os.path.join(ASSETS, "models/item/cardboard_pile.json"))


# ---------------------------------------------------------------- trash can

STEEL = (124, 128, 132)


@P("can_steel")
def can_steel(a, rng, face):
    h, w = a.shape[:2]
    fill(a, STEEL)
    yy, xx = np.mgrid[0:h, 0:w]
    # pressed vertical ribs: a groove and a lit edge every 4 texels
    tone(a, np.where(xx % 4 == 0, 0.72, np.where(xx % 4 == 1, 1.14, 1.0)))
    tone(a, 1.0 + 0.06 * smooth_noise(rng, h, w, 3))
    # grime: darker and browner toward the floor, drips
    g = np.clip((yy / max(1, h - 1) - 0.55) / 0.45, 0, 1)
    tone(a, 1.0 - 0.32 * g)
    a[..., 0] = np.clip(a[..., 0] + 6 * g, 0, 255)
    for _ in range(2):
        x = rng.integers(0, w)
        y0 = rng.integers(0, h // 2)
        tone(a, 0.82, (xx == x) & (yy > y0) & (yy < y0 + rng.integers(4, 12)))
    tone(a, 0.86, rng.random((h, w)) < 0.04)


@P("can_label")
def can_label(a, rng, face):
    can_steel(a, rng, face)
    h, w = a.shape[:2]
    # the MTA sticker: white plate, blue roundel with white letters, black text lines
    y0, y1 = int(h * 0.22), int(h * 0.5)
    rect(a, 1, y0, w - 1, y1, (238, 238, 234))
    cx, cy = 5.5, (y0 + y1) / 2
    paint(a, ellipse_mask(h, w, cx, cy, 3.6, 3.6), (18, 60, 160))
    rect(a, cx - 2, cy - 0.5, cx + 2, cy + 0.5, (238, 238, 234))
    rect(a, cx - 2, cy - 1.5, cx - 1, cy - 0.5, (238, 238, 234))
    rect(a, cx + 1, cy + 0.5, cx + 2, cy + 1.5, (238, 238, 234))
    for i, y in enumerate(range(y0 + 2, y1 - 1, 2)):
        rect(a, 10, y, w - 2 - (i % 2) * 2, y + 1, (40, 40, 44))
    # recycling-blue band below the sticker
    rect(a, 0, y1 + 2, w, y1 + 4, (40, 90, 170))


@P("can_band")
def can_band(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (150, 154, 158))
    yy = np.mgrid[0:h, 0:w][0]
    tone(a, 1.0 + 0.18 * np.cos((yy + 0.5) / h * math.pi * 2))
    tone(a, 1.0 + 0.05 * smooth_noise(rng, h, w, 2))


@P("can_blue_band")
def can_blue_band(a, rng, face):
    can_steel(a, rng, face)
    h, w = a.shape[:2]
    y1 = int(h * 0.5)
    rect(a, 0, y1 + 2, w, y1 + 4, (40, 90, 170))


@P("can_foot")
def can_foot(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (56, 58, 60))
    tone(a, 1.0 + 0.08 * smooth_noise(rng, h, w, 2))


@P("liner_lip")
def liner_lip(a, rng, face):
    """Bag folded over the rim; its hem is ragged (alpha-cut) at the bottom."""
    h, w = a.shape[:2]
    _liner(a, rng)
    hem = h - 1 - (rng.random(w) < 0.5).astype(int) - (rng.random(w) < 0.2).astype(int)
    yy, xx = np.mgrid[0:h, 0:w]
    a[yy > hem[None, :], 3] = 0
    tone(a, 1.25, yy == 0)


CAN = Tex("trash_can", 64, 4)
CAN_C = (8.0, 8.0)
TAN = math.tan(math.radians(22.5))


def octa_slab(a, t, y0, y1, faces, painters, ext=0.0):
    """The NORTH side of an octagon of apothem a (local coords, centre 0,0),
    `t` thick inward. faces: which faces to keep. painters: {face letter: painter
    or TileTex}. Returns one element (local coords)."""
    s = 2 * a * TAN
    f, to = (-s / 2 - ext, y0, -a), (s / 2 + ext, y1, -a + t)
    packed = {k: v for k, v in painters.items() if not isinstance(v, TileTex)}
    first = next(iter(packed.values()), None)
    if first is not None:
        el = box(f, to, CAN, first, faces="".join(c for c in faces if c in packed), over=packed)
    else:
        el = {"from": list(f), "to": list(to), "faces": {}, "_tex": {}}
    for c in faces:
        p = painters.get(c)
        if isinstance(p, TileTex):
            tile_face(el, FACE_KEYS[c], p)
    return el


def octa_ring(a, t, y0, y1, painters, faces="nseuw", front=None):
    """Eight slabs round an octagon (block centre). painters as octa_slab with
    'n' = outer, 's' = inner. `front` replaces the outer painter on the north side."""
    els = []
    for q in (0, 2):
        for ang in (0, -45, 45):
            p = dict(painters)
            if q == 0 and ang == 0 and front:
                p["n"] = front
            # rotate the NORTH slab into place: outer face name follows the turn
            e = octa_slab(a, t, y0, y1, faces, p)
            if ang:
                e["rotation"] = {"axis": "y", "angle": ang, "origin": [0, 0, 0]}
            els.append(xform(e, q, CAN_C[0], 0, CAN_C[1]))
        if True:
            e = octa_slab(a, t, y0, y1, faces, painters)
            els.append(xform(e, q + 1, CAN_C[0], 0, CAN_C[1]))
    return els


def octa_solid(a, y0, y1, side, top, tile_top=None):
    """A solid octagon: centre bar + two wings + four chamfer slabs (their tops
    0.03 lower so no two tops are coplanar)."""
    s = 2 * a * TAN
    cx, cz = CAN_C
    els = []

    def mk(f, to, faces, over):
        packed = {k: v for k, v in over.items() if not isinstance(v, TileTex)}
        tiles = {k: v for k, v in over.items() if isinstance(v, TileTex)}
        if packed:
            first = next(iter(packed.values()))
            el = box(f, to, CAN, first, faces="".join(c for c in faces if c in packed), over=packed)
        else:
            el = {"from": [round(v, 4) for v in f], "to": [round(v, 4) for v in to], "faces": {}, "_tex": {}}
        for c, tt in tiles.items():
            if c in faces:
                tile_face(el, FACE_KEYS[c], tt)
        return el

    tp = tile_top or top
    els.append(mk((cx - s / 2, y0, cz - a), (cx + s / 2, y1, cz + a), "nsu" if side else "u",
                  {"n": side, "s": side, "u": tp} if side else {"u": tp}))
    els.append(mk((cx + s / 2, y0, cz - s / 2), (cx + a, y1, cz + s / 2), "eu" if side else "u",
                  {"e": side, "u": tp} if side else {"u": tp}))
    els.append(mk((cx - a, y0, cz - s / 2), (cx - s / 2, y1, cz + s / 2), "wu" if side else "u",
                  {"w": side, "u": tp} if side else {"u": tp}))
    T = 0.45 * a
    for q in (0, 2):
        for ang in (-45, 45):
            el = octa_slab(a, T, y0, y1 - 0.03, "nu" if side else "u", {"n": side, "u": tp} if side else {"u": tp})
            el["rotation"] = {"axis": "y", "angle": ang, "origin": [0, 0, 0]}
            els.append(xform(el, q, cx, 0, cz))
    return els


def can_body(fill_level):
    A_BODY, T = 4.8, 0.5
    els = []
    # foot / floor of the can
    els += octa_solid(4.55, 0, 1.0, can_foot, None, tile_top=LINER)
    # body walls: steel outside, liner inside
    els += octa_ring(A_BODY, T, 0.6, 11.0, {"n": can_blue_band, "s": LINER, "e": LINER, "w": LINER},
                     faces="nsew", front=can_label)
    # rolled rim band
    els += octa_ring(5.02, 0.95, 10.5, 12.2, {"n": can_band, "s": LINER, "u": LINER, "d": can_band, "e": LINER,
                                               "w": LINER}, faces="nsewud")
    # liner folded over the rim, ragged hem hanging outside
    els += octa_ring(5.18, 0.3, 10.9, 12.45, {"n": liner_lip, "u": LINER, "e": LINER, "w": LINER}, faces="neuw")
    inner = A_BODY - T - 0.05
    if fill_level == 1:
        els += octa_solid(inner, 1.0, 6.2, None, None, tile_top=FILL)
    elif fill_level >= 2:
        els += octa_solid(inner, 1.0, 11.2, None, None, tile_top=FILL)
    if fill_level == 3:
        cx, cz = CAN_C
        els += [
            tbox((cx - 3.8, 11.0, cz - 3.4), (cx + 3.6, 12.9, cz + 3.5), FILL, "nsewu", rot=("y", 22.5, (cx, 0, cz))),
            tbox((cx - 2.9, 12.6, cz - 2.6), (cx + 2.5, 14.0, cz + 2.4), FILL, "nsewu", rot=("y", -22.5, (cx, 0, cz))),
            tbox((cx - 1.6, 13.8, cz - 1.3), (cx + 1.2, 14.7, cz + 1.5), FILL, "nsewu"),
            tbox((cx + 2.2, 11.6, cz - 4.6), (cx + 4.6, 13.4, cz - 2.2), BAG, "nsewu", rot=("y", 45, (cx + 3.4, 0, cz - 3.4))),
        ]
        cup = [xform(e, 1, cx - 0.4, 14.65, cz + 0.9, 1.15) for e in piece_cup_cached()]
        can = [xform(e, 2, cx + 1.6, 13.95, cz - 1.4, 1.1) for e in piece_can_cached()]
        news = [xform(e, 3, cx - 2.6, 12.9, cz - 1.4, 1.0) for e in piece_news_cached()]
        els += cup + can + news
    return els


_CACHE = {}


def piece_cup_cached():
    return _CACHE.setdefault("cup", piece_cup())


def piece_can_cached():
    return _CACHE.setdefault("can", piece_can(can_red_side, "can_red"))


def piece_news_cached():
    return _CACHE.setdefault("news", piece_newspaper())


def build_trash_can():
    variants = {}
    refs = {}
    for f in range(4):
        refs[f] = model(f"trash_can_{f}", can_body(f), CAN.ref, parent="minecraft:block/block")
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for f in range(4):
            v = {"model": refs[f]}
            if y:
                v["y"] = y
            variants[f"facing={facing},fill={f}"] = v
    write_blockstate("trash_can", {"variants": variants})
    AC.item_block("trash_can", "litter/trash_can_2")
    WRITTEN.append(os.path.join(ASSETS, "models/item/trash_can.json"))


# ---------------------------------------------------------------- swept pile + broom

SWEPT = Tex("swept_pile", 64, 3)


@P("dust_spread")
def dust_spread(a, rng, face):
    """A ragged flat spread of dust round the heap (alpha-cut edge)."""
    h, w = a.shape[:2]
    _dust(a, rng)
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot((xx + 0.5 - w / 2) / (w / 2), (yy + 0.5 - h / 2) / (h / 2))
    keep = (r + 0.3 * smooth_noise(rng, h, w, 3)) < 0.86
    keep &= ~((r > 0.6) & (rng.random((h, w)) < 0.35))
    a[~keep, 3] = 0
    tone(a, 1.08)


@P("broom_wood")
def broom_wood(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (158, 108, 60))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, 1.0 + 0.12 * np.sin(yy * 1.3 + 2 * smooth_noise(rng, h, w, 3)))


@P("broom_handle")
def broom_handle(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (204, 162, 98))
    tone(a, 1.0 + 0.08 * smooth_noise(rng, h, w, 2))


@P("bristles")
def bristles(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (44, 40, 36))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, np.where(xx % 2 == 0, 1.25, 0.85))
    tone(a, 1.0 + 0.2 * (rng.random((h, w)) - 0.5))


@P("brace")
def brace(a, rng, face):
    fill(a, (150, 152, 156))


def swept_heap():
    """A soft heap: five thin tiers shrinking upward, alternately skewed, on a
    ragged spread of dust, with a receipt and two butts caught in it."""
    cx, cz = 6.9, 7.6
    els = [box((cx - 6.0, 0.012, cz - 5.4), (cx + 6.0, 0.03, cz + 5.4), SWEPT, dust_spread, faces="u")]
    lumps = [(0.0, 0.0, 7.0, 5.8, 0.9, 22.5), (1.2, 0.9, 4.6, 3.9, 1.55, 0), (-1.3, -0.7, 4.1, 3.4, 1.35, -22.5),
             (0.2, 0.1, 2.9, 2.5, 2.2, 45), (-2.9, 1.7, 2.6, 2.1, 0.62, 0), (3.0, -1.8, 2.3, 1.9, 0.5, 22.5),
             (-0.4, 2.4, 2.2, 1.6, 0.74, -22.5)]
    for dx, dz, w, d, hh, ang in lumps:
        x, z = cx + dx, cz + dz
        els.append(tbox((x - w / 2, 0.0, z - d / 2), (x + w / 2, hh, z + d / 2), DUST, "nsewu",
                        rot=("y", ang, (x, 0, z)) if ang else None))
    els += [xform(e, 1, cx + 1.9, 0.92, cz - 1.0, 0.9) for e in _CACHE.setdefault("receipt", piece_receipt())]
    els += [xform(e, 0, cx - 2.2, 0.66, cz + 1.2, 0.85) for e in _CACHE.setdefault("butts", piece_butts())[1:3]]
    return els


def broom():
    return [
        box((5.0, 0, 12.9), (13.6, 1.7, 14.3), SWEPT, broom_wood, faces="nsewu"),
        box((5.2, 0.08, 14.25), (13.4, 1.62, 15.7), SWEPT, bristles, faces="sewu"),
        box((10.85, 0.2, 1.2), (11.55, 0.9, 12.95), SWEPT, broom_handle, faces="nsewu"),
        box((10.6, 0.1, 11.4), (11.8, 1.1, 12.95), SWEPT, brace, faces="newu"),
    ]


def build_swept():
    heap = swept_heap()
    plain = model("swept_pile", heap, DUST.ref)
    with_b = model("swept_pile_broom", heap + broom(), DUST.ref)
    variants = {}
    for b, ref in (("false", plain), ("true", with_b)):
        variants[f"broom={b}"] = [dict({"model": ref}, **({"y": 90 * q} if q else {})) for q in range(4)]
    write_blockstate("swept_pile", {"variants": variants})


# ---------------------------------------------------------------- newspaper bundle

NEWSB = Tex("newspaper_bundle", 64, 3)


@P("bundle_front")
def bundle_front(a, rng, face):
    news_front(a, rng, face)


@P("bundle_fold")
def bundle_fold(a, rng, face):
    """The folded spine side: stacked rounded folds."""
    h, w = a.shape[:2]
    fill(a, (206, 202, 188))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, np.where(yy % 2 == 0, 1.06, 0.8))
    tone(a, 1.0 + 0.04 * smooth_noise(rng, h, w, 3))


@P("bundle_pages")
def bundle_pages(a, rng, face):
    """The open side / ends: ragged page edges, slightly shifted per paper."""
    h, w = a.shape[:2]
    fill(a, (214, 210, 196))
    yy, xx = np.mgrid[0:h, 0:w]
    tone(a, np.where(yy % 2 == 0, 1.04, 0.86))
    for y in range(0, h, 2):
        if rng.random() < 0.4:
            x = rng.integers(0, w)
            rect(a, x, y, x + rng.integers(2, 6), y + 1, (150, 146, 136))
    tone(a, 1.0 + 0.05 * smooth_noise(rng, h, w, 2))


@P("strap")
def strap(a, rng, face):
    h, w = a.shape[:2]
    fill(a, (232, 228, 214))
    tone(a, 1.0 + 0.06 * smooth_noise(rng, h, w, 1))


def bundle():
    x0, x1, z0, z1, y1 = 3.2, 12.8, 5.0, 11.0, 4.2
    els = [
        box((x0, 0, z0), (x1, y1, z1), NEWSB, bundle_pages, faces="nsewu",
            over={"u": bundle_front, "s": bundle_fold}, uvrot={"u": 180}),
        # the loose top paper, nudged off-square
        box((x0 + 0.5, y1 - 0.01, z0 + 0.35), (x1 - 0.2, y1 + 0.32, z1 - 0.15), NEWSB, bundle_pages, faces="nsewu",
            over={"u": bundle_front}, uvrot={"u": 180}),
    ]
    for sx in (5.6, 10.3):
        els += [
            box((sx, y1 + 0.31, z0 - 0.12), (sx + 0.55, y1 + 0.44, z1 + 0.12), NEWSB, strap, faces="nsewu"),
            box((sx, 0, z0 - 0.12), (sx + 0.55, y1 + 0.31, z0), NEWSB, strap, faces="new"),
            box((sx, 0, z1), (sx + 0.55, y1 + 0.31, z1 + 0.12), NEWSB, strap, faces="sew"),
        ]
    return els


def build_newspaper_stack():
    ref = model("newspaper_stack", bundle(), NEWSB.ref, parent="minecraft:block/block")
    variants = {}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        variants[f"facing={facing}"] = dict({"model": ref}, **({"y": y} if y else {}))
    write_blockstate("newspaper_stack", {"variants": variants})
    AC.item_block("newspaper_stack", "litter/newspaper_stack")
    WRITTEN.append(os.path.join(ASSETS, "models/item/newspaper_stack.json"))


# ---------------------------------------------------------------- flat item icons, loot, lang, tags

def item_icon(name, draw):
    a = np.zeros((16, 16, 4), dtype=np.float32)
    draw(a)
    path = os.path.join(ASSETS, "textures/item", name + ".png")
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(path)
    WRITTEN.append(path)
    AC.item_flat(name)
    WRITTEN.append(os.path.join(ASSETS, "models/item", name + ".json"))


def draw_litter_icon(a):
    # newspaper sheet behind, a lying Anthora cup and a MetroCard in front
    rect(a, 1, 3, 11, 10, NEWSPRINT)
    rect(a, 2, 4, 8, 5, (40, 40, 40))
    for y in (6, 8):
        rect(a, 2, y, 10, y + 1, (150, 146, 136))
    rect(a, 4, 8, 13, 13, ANTHORA_BLUE)
    rect(a, 4, 8, 13, 9, (70, 110, 200))
    for x in (5, 7, 11):
        rect(a, x, 8, x + 1, 13, WHITE_PAPER)
    rect(a, 9, 10, 10, 12, GOLD)
    rect(a, 13, 8, 15, 13, (62, 44, 32))
    rect(a, 13, 8, 15, 9, WHITE_PAPER)
    rect(a, 1, 11, 6, 15, (246, 200, 18))
    rect(a, 2, 12, 5, 13, (24, 58, 150))
    a[..., 3] = np.where(a[..., 3] > 0, 255, 0)


def draw_swept_icon(a):
    yy, xx = np.mgrid[0:16, 0:16]
    m = ((xx - 7.5) / 6.5) ** 2 + ((yy - 12) / 3.6) ** 2 <= 1
    m &= yy >= 8
    paint(a, m, (110, 104, 96))
    rng = np.random.default_rng(7)
    tone(a, np.where(rng.random((16, 16)) < 0.3, 0.75, 1.0), m)
    for (x, y, c) in ((5, 11, (232, 230, 222)), (9, 10, (214, 150, 76)), (7, 13, (176, 132, 80))):
        rect(a, x, y, x + 2, y + 1, c)
    # broom: handle diagonal + head
    for i in range(9):
        rect(a, 3 + i, 1 + i, 5 + i, 2 + i, (204, 162, 98))
        rect(a, 3 + i, 2 + i, 4 + i, 3 + i, (150, 112, 62))
    rect(a, 10, 9, 16, 11, (158, 108, 60))
    rect(a, 10, 11, 16, 13, (44, 40, 36))


def loot_counted(name, prop, n):
    fns = [{"function": "minecraft:set_count", "count": k, "add": False,
            "conditions": [{"condition": "minecraft:block_state_property", "block": f"{MOD}:{name}",
                            "properties": {prop: str(k)}}]} for k in range(2, n + 1)]
    fns.append({"function": "minecraft:explosion_decay"})
    write_json(os.path.join(AC.DATA, MOD, "loot_tables/blocks", name + ".json"),
               {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
                   {"type": "minecraft:item", "name": f"{MOD}:{name}", "functions": fns}]}]})


def build_misc():
    item_icon("litter", draw_litter_icon)
    item_icon("swept_pile", draw_swept_icon)
    AC.loot_nothing("litter")
    AC.loot_nothing("swept_pile")
    loot_counted("trash_bags", "bags", 3)
    loot_counted("cardboard_pile", "layers", 3)
    AC.loot_self("trash_can")
    AC.loot_self("newspaper_stack")
    for n in ("litter", "swept_pile", "trash_can", "newspaper_stack"):
        WRITTEN.append(os.path.join(AC.DATA, MOD, "loot_tables/blocks", n + ".json"))
    AC.upsert_lang(LANG)
    AC.insert_into_tag("minecraft/tags/blocks/mineable/pickaxe.json", [f"{MOD}:trash_can"])
    AC.insert_into_tag("minecraft/tags/blocks/mineable/axe.json", [f"{MOD}:cardboard_pile", f"{MOD}:newspaper_stack"])
    AC.insert_into_tag("minecraft/tags/blocks/mineable/hoe.json", [f"{MOD}:trash_bags"])


# ---------------------------------------------------------------- verify

ANGLES = {-45, -22.5, 0, 22.5, 45}


def java_props():
    out = {}
    for block, cls in BLOCKS.items():
        src = open(os.path.join(JAVA_DIR, cls + ".java")).read()
        props = {}
        consts = {m.group(1): int(m.group(2)) for m in re.finditer(r'static final int (\w+)\s*=\s*(\d+);', src)}

        def num(tok):
            return int(tok) if tok.isdigit() else consts[tok]
        for m in re.finditer(r'IntProperty\.of\("(\w+)",\s*(\w+),\s*(\w+)\)', src):
            props[m.group(1)] = {str(v) for v in range(num(m.group(2)), num(m.group(3)) + 1)}
        for m in re.finditer(r'BooleanProperty\.of\("(\w+)"\)', src):
            props[m.group(1)] = {"true", "false"}
        if "extends FacingDecorBlock" in src:
            props["facing"] = {"north", "east", "south", "west"}
        out[block] = props
    return out


def verify():
    errors = []
    props = java_props()
    for path in WRITTEN:
        if path.endswith(".json"):
            try:
                json.load(open(path))
            except Exception as ex:  # noqa: BLE001
                errors.append(f"{path}: bad json {ex}")

    def model_ok(ref, where):
        ns, p = ref.split(":")
        f = os.path.join(AC.ROOT, "src/main/resources/assets", ns, "models", p + ".json")
        if not os.path.exists(f):
            errors.append(f"{where}: missing model {ref}")
            return
        m = json.load(open(f))
        tex = m.get("textures", {})
        for alias, t in tex.items():
            if t.startswith("#"):
                continue
            tns, tp = t.split(":")
            if not os.path.exists(os.path.join(AC.ROOT, "src/main/resources/assets", tns, "textures", tp + ".png")):
                errors.append(f"{ref}: missing texture {t}")
        for e in m.get("elements", []):
            for v in e["from"] + e["to"]:
                if v < -16 or v > 32:
                    errors.append(f"{ref}: element out of range {e['from']} {e['to']}")
            r = e.get("rotation")
            if r and r["angle"] not in ANGLES:
                errors.append(f"{ref}: bad angle {r['angle']}")
            for fn, fc in e["faces"].items():
                if "uv" not in fc:
                    errors.append(f"{ref}: face {fn} without explicit uv")
                elif any(u < 0 or u > 16 for u in fc["uv"]):
                    errors.append(f"{ref}: uv out of 0..16 {fc['uv']}")
                if fc["texture"][1:] not in tex:
                    errors.append(f"{ref}: face texture {fc['texture']} not defined")

    checked = set()
    for block in BLOCKS:
        bs = json.load(open(os.path.join(ASSETS, "blockstates", block + ".json")))
        pr = props[block]
        refs = []
        if "variants" in bs:
            covered = set()
            for key, ap in bs["variants"].items():
                kv = dict(x.split("=") for x in key.split(",") if x)
                for k, v in kv.items():
                    if k not in pr or v not in pr[k]:
                        errors.append(f"{block}: variant key {k}={v} not a Java property value")
                covered.add(tuple(sorted(kv.items())))
                refs += [a["model"] for a in (ap if isinstance(ap, list) else [ap])]
            # every state combination must resolve
            import itertools
            names = sorted(pr)
            for combo in itertools.product(*[sorted(pr[n]) for n in names]):
                st = dict(zip(names, combo))
                if not any(all(st.get(k) == v for k, v in c) for c in covered):
                    errors.append(f"{block}: state {st} has no variant")
        for part in bs.get("multipart", []):
            for k, v in part.get("when", {}).items():
                for vv in v.split("|"):
                    if k not in pr or vv not in pr[k]:
                        errors.append(f"{block}: when {k}={vv} not a Java property value")
            refs += [a["model"] for a in part["apply"]]
        for r in refs:
            if r not in checked:
                checked.add(r)
                model_ok(r, block)
        item = os.path.join(ASSETS, "models/item", block + ".json")
        if not os.path.exists(item):
            errors.append(f"{block}: no item model")
        else:
            im = json.load(open(item))
            if "parent" in im and im["parent"].startswith(MOD):
                model_ok(im["parent"], block + " item")
        if not os.path.exists(os.path.join(AC.DATA, MOD, "loot_tables/blocks", block + ".json")):
            errors.append(f"{block}: no loot table")
    lang = json.load(open(os.path.join(ASSETS, "lang/en_us.json")))
    for k in LANG:
        if k not in lang:
            errors.append(f"lang key missing {k}")
    for t in glob.glob(os.path.join(TEX_DIR, "*.png")):
        im = Image.open(t)
        if im.size[0] != im.size[1] or im.size[0] not in (16, 32, 64):
            errors.append(f"{t}: size {im.size}")
    if errors:
        print("\n".join(errors[:60]))
        raise SystemExit(f"verify FAILED ({len(errors)} problems)")
    print(f"verify OK: {len(checked)} models, {len(BLOCKS)} blockstates")


# ---------------------------------------------------------------- writing

def write_json(path, obj):
    AC.write_json(path, obj)
    WRITTEN.append(path)


def write_blockstate(name, obj):
    write_json(os.path.join(ASSETS, "blockstates", name + ".json"), obj)


def flush_models():
    for name, m in MODELS.items():
        write_json(os.path.join(ASSETS, "models/block", name + ".json"), m)


def clean():
    for d in (MODEL_DIR, TEX_DIR):
        if os.path.isdir(d):
            shutil.rmtree(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", help="render preview PNGs into this directory")
    args = ap.parse_args()
    clean()
    build_litter()
    build_bags()
    build_cardboard()
    build_trash_can()
    build_swept()
    build_newspaper_stack()
    for t in list(PIECE_TEX.values()) + TILES + [CARD, CAN, SWEPT, NEWSB]:
        t.save()
    flush_models()
    build_misc()
    print(f"wrote {len(WRITTEN)} files, {len(MODELS)} models")
    verify()
    if args.preview:
        preview(args.preview)


def _litter_pick(bs, count, seed):
    """Emulate vanilla: one random long per position, each selector picks
    |X| % totalWeight from its own list."""
    X = seed & 0x7FFFFFFF
    out = []
    for k, part in enumerate(bs["multipart"]):
        if k >= count:
            break
        ents = part["apply"]
        idx = X % sum(e["weight"] for e in ents)
        for e in ents:
            idx -= e["weight"]
            if idx < 0:
                out.append(e["model"].split(":block/")[1])
                break
    return out


def preview(out):
    """Offline renders (tools/render_scene.py) of every block, into `out`."""
    import render_scene as R
    os.makedirs(out, exist_ok=True)
    R._model_cache.clear()
    R._tex_cache.clear()
    floor = R.cube(f"{MOD}:block/platform_concrete_floor_clean_top_c1_1")
    bs = json.load(open(os.path.join(ASSETS, "blockstates/litter.json")))

    # 1. a litter field: 5 x 4 cells, counts 1..4
    scene = [(floor, (i, -1, j), 0) for i in range(5) for j in range(4)]
    for i in range(5):
        for j in range(4):
            for m in _litter_pick(bs, 1 + (i * 3 + j) % 4, _seed("pos", i, j)):
                scene.append((m, (i, 0, j), 0))
    R.render(scene, os.path.join(out, "litter_field.png"), yaw=215, pitch=38, size=1200)

    # 2. the props on a platform: cans at each fill, bag heaps, cardboard, swept piles, bundle
    scene = [(floor, (i, -1, j), 0) for i in range(8) for j in range(3)]
    for f in range(4):
        R.place(scene, "trash_can", {"facing": "north", "fill": f}, (f * 2, 0, 0))
    for n in (1, 2, 3):
        R.place(scene, "trash_bags", {"bags": n}, (n - 1, 0, 1))
    for n in (1, 2, 3):
        R.place(scene, "cardboard_pile", {"layers": n}, (n + 2, 0, 1))
    R.place(scene, "swept_pile", {"broom": False}, (6, 0, 1))
    R.place(scene, "swept_pile", {"broom": True}, (7, 0, 1))
    R.place(scene, "newspaper_stack", {"facing": "north"}, (1, 0, 2))
    for i, c in ((3, 2), (4, 4), (5, 3), (6, 1)):
        for m in _litter_pick(bs, c, _seed("p2", i)):
            scene.append((m, (i, 0, 2), 0))
    R.render(scene, os.path.join(out, "props.png"), yaw=200, pitch=30, size=1400)
    print("previews in", out)


if __name__ == "__main__":
    main()
