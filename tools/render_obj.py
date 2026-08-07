#!/usr/bin/env python3
"""Offline software renderer for textured Wavefront OBJ models.

Preview a train (or any) OBJ as PNG images without launching Minecraft.
Dependencies: numpy + the standard library only (zlib/struct do the PNG I/O).

    python3 tools/render_obj.py model.obj
    python3 tools/render_obj.py model.obj -o out --size 1200x800 --views side,top
    python3 tools/render_obj.py model.obj --group Body --group DoorL
    python3 tools/render_obj.py model.obj --explode-z
    python3 tools/render_obj.py model.obj --list-groups

Conventions assumed (MTR / Minecraft rolling stock): +x across the car,
+y up, +z toward the front of the car.  Views are named accordingly:

    front              camera on +z looking down -z (sees the +z end)
    rear               camera on -z
    side               camera on +x   (nose to the left of frame)
    side-left          camera on -x   (nose to the right of frame)
    top                straight down, +z end toward the top of the image
    bottom             straight up (underframe)
    three-quarter      front-right, raised
    three-quarter-rear rear-right, raised
    interior           camera inside the model at the +z end, looking down -z

Rendering notes
    * z-buffered, perspective camera, perspective-correct UVs
    * UV v is IMAGE convention by default: v = 0 is the TOP texture row. That is
      what MTR's own .obj loader does (established in game 2026-07-28 — see
      M7_CONVERSION_NOTES.md) and what both M7 converters write, so the preview
      now matches what the game will draw. This renderer used to sample
      bottom-origin like OpenGL, which flipped every texture vertically; the
      M7 body art is nearly symmetric about its window band, which is why it
      went unnoticed. Pass --uv-origin bottom for a Blender-style export.
    * nearest-neighbour texture sampling (pixel-art friendly)
    * cutout alpha: texels with alpha < 128 are discarded (Minecraft semantics).
      Materials whose name carries an MTR "#...translucent" shader flag are
      alpha-BLENDED instead, drawn after everything opaque and never writing
      depth — so tinted glass shows what is behind it, as in game.
    * double sided by default: no backface culling, so nothing is hidden by
      winding. --cull culls like MTR does, which is what to render when the
      question is what the game will show through a window or an aperture
    * flat-shaded Lambert from a camera-relative key light over a 0.55 ambient
      floor, so every view reads without the texture washing out
"""

import argparse
import math
import os
import struct
import sys
import time
import zlib

import numpy as np


# --------------------------------------------------------------------- PNG I/O

_PNG_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def _unfilter(raw, height, stride, fbpp):
    """Reverse the PNG per-scanline filters. Returns (height, stride) uint8."""
    out = np.zeros((height, stride), dtype=np.uint8)
    prev = bytes(stride)
    pos = 0
    pad = (-stride) % fbpp
    for y in range(height):
        ftype = raw[pos]
        pos += 1
        line = raw[pos:pos + stride]
        pos += stride
        if len(line) < stride:
            raise ValueError("truncated PNG image data")

        if ftype == 0:
            cur = line
        elif ftype == 2:                                   # Up (vectorised)
            cur = (np.frombuffer(line, np.uint8)
                   + np.frombuffer(prev, np.uint8)).tobytes()
        elif ftype == 1:                                   # Sub (cumsum trick)
            a = np.frombuffer(line + bytes(pad), np.uint8).reshape(-1, fbpp)
            cur = (np.cumsum(a.astype(np.int64), axis=0) & 0xFF)
            cur = cur.astype(np.uint8).tobytes()[:stride]
        elif ftype == 3:                                   # Average
            cur = bytearray(line)
            for i in range(fbpp):
                cur[i] = (cur[i] + (prev[i] >> 1)) & 0xFF
            for i in range(fbpp, stride):
                cur[i] = (cur[i] + ((cur[i - fbpp] + prev[i]) >> 1)) & 0xFF
            cur = bytes(cur)
        elif ftype == 4:                                   # Paeth
            cur = bytearray(line)
            for i in range(fbpp):
                cur[i] = (cur[i] + prev[i]) & 0xFF
            for i in range(fbpp, stride):
                a = cur[i - fbpp]
                b = prev[i]
                c = prev[i - fbpp]
                p = a + b - c
                pa = p - a if p > a else a - p
                pb = p - b if p > b else b - p
                pc = p - c if p > c else c - p
                if pa <= pb and pa <= pc:
                    pr = a
                elif pb <= pc:
                    pr = b
                else:
                    pr = c
                cur[i] = (cur[i] + pr) & 0xFF
            cur = bytes(cur)
        else:
            raise ValueError("unsupported PNG filter type %d" % ftype)

        out[y] = np.frombuffer(cur, np.uint8)
        prev = cur
    return out


def decode_png(path):
    """Decode a non-interlaced PNG to an (h, w, 4) uint8 RGBA array."""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG file")

    pos = 8
    header = None
    palette = None
    trns = None
    idat = []
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif ctype == b"PLTE":
            palette = np.frombuffer(body, np.uint8).reshape(-1, 3)
        elif ctype == b"tRNS":
            trns = body
        elif ctype == b"IDAT":
            idat.append(body)
        elif ctype == b"IEND":
            break
    if header is None:
        raise ValueError("PNG has no IHDR")

    width, height, depth, colour, compression, filt, interlace = header
    if compression != 0 or filt != 0:
        raise ValueError("unsupported PNG compression/filter method")
    if interlace:
        raise ValueError("interlaced (Adam7) PNGs are not supported")
    if colour not in _PNG_CHANNELS:
        raise ValueError("unsupported PNG colour type %d" % colour)
    if depth not in (1, 2, 4, 8, 16):
        raise ValueError("unsupported PNG bit depth %d" % depth)
    if depth < 8 and colour not in (0, 3):
        raise ValueError("bit depth %d only valid for grey/palette PNGs" % depth)

    channels = _PNG_CHANNELS[colour]
    bits = depth * channels
    stride = (width * bits + 7) // 8
    fbpp = max(1, bits // 8)
    rows = _unfilter(zlib.decompress(b"".join(idat)), height, stride, fbpp)

    if depth == 8:
        samples = rows.reshape(height, width, channels).astype(np.uint16)
    elif depth == 16:
        samples = rows.reshape(height, width, channels, 2)[..., 0].astype(np.uint16)
    else:
        bitsarr = np.unpackbits(rows, axis=1)
        per = depth
        usable = bitsarr[:, :width * per].reshape(height, width, per)
        weights = (1 << np.arange(per - 1, -1, -1)).astype(np.uint16)
        samples = (usable * weights).sum(axis=2)[..., None].astype(np.uint16)

    maxval = (1 << depth) - 1
    out = np.zeros((height, width, 4), dtype=np.uint8)

    if colour == 3:
        if palette is None:
            raise ValueError("palette PNG without PLTE")
        idx = np.clip(samples[..., 0], 0, len(palette) - 1)
        out[..., :3] = palette[idx]
        if trns:
            alpha = np.full(len(palette), 255, np.uint8)
            alpha[:len(trns)] = np.frombuffer(trns, np.uint8)
            out[..., 3] = alpha[idx]
        else:
            out[..., 3] = 255
    else:
        # depth 16 already carries only the high byte (libpng's strip_16).
        scaled = samples if depth >= 8 else (samples * 255 // maxval)
        scaled = scaled.astype(np.uint8)
        if colour == 0:
            out[..., :3] = scaled[..., :1]
            out[..., 3] = 255
        elif colour == 4:
            out[..., :3] = scaled[..., :1]
            out[..., 3] = scaled[..., 1]
        elif colour == 2:
            out[..., :3] = scaled[..., :3]
            out[..., 3] = 255
        else:
            out[...] = scaled[..., :4]
        if trns and colour in (0, 2):
            raw16 = np.frombuffer(trns, ">u2").astype(np.int64)
            if depth == 16:
                key = (raw16 >> 8)
            elif depth == 8:
                key = raw16
            else:
                key = raw16 * 255 // maxval
            key = key.astype(np.uint8)
            if colour == 0:
                hit = out[..., 0] == key[0]
            else:
                hit = np.all(out[..., :3] == key[:3], axis=2)
            out[..., 3] = np.where(hit, 0, out[..., 3])
    return out


def decode_bmp(path):
    """Minimal uncompressed 24/32-bit BMP reader (openBVE ships a few)."""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:2] != b"BM":
        raise ValueError("not a BMP file")
    offset = struct.unpack("<I", data[10:14])[0]
    hsize = struct.unpack("<I", data[14:18])[0]
    if hsize < 40:
        raise ValueError("unsupported BMP header size %d" % hsize)
    width, height = struct.unpack("<ii", data[18:26])
    bpp = struct.unpack("<H", data[28:30])[0]
    comp = struct.unpack("<I", data[30:34])[0]
    if comp not in (0, 3) or bpp not in (24, 32):
        raise ValueError("only uncompressed 24/32-bit BMPs are supported")
    flip = height > 0
    height = abs(height)
    stride = ((width * bpp // 8) + 3) & ~3
    buf = np.frombuffer(data, np.uint8, count=stride * height, offset=offset)
    buf = buf.reshape(height, stride)[:, :width * bpp // 8]
    buf = buf.reshape(height, width, bpp // 8)
    out = np.zeros((height, width, 4), np.uint8)
    out[..., 0] = buf[..., 2]
    out[..., 1] = buf[..., 1]
    out[..., 2] = buf[..., 0]
    out[..., 3] = buf[..., 3] if bpp == 32 else 255
    return out[::-1] if flip else out


def write_png(path, pixels):
    """pixels: (h, w, 3) or (h, w, 4) uint8."""
    height, width, channels = pixels.shape
    colour = 2 if channels == 3 else 6
    body = np.concatenate(
        [np.zeros((height, 1), np.uint8), pixels.reshape(height, -1)], axis=1)

    def chunk(tag, payload):
        head = tag + payload
        return struct.pack(">I", len(payload)) + head + struct.pack(">I", zlib.crc32(head))

    blob = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, colour, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(body.tobytes(), 6))
            + chunk(b"IEND", b""))
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(blob)


_TEXTURE_CACHE = {}
MAGENTA = np.array([[[255, 0, 255, 255]]], np.uint8)


def load_texture(path):
    key = os.path.abspath(path)
    if key in _TEXTURE_CACHE:
        return _TEXTURE_CACHE[key]
    image = None
    try:
        ext = os.path.splitext(path)[1].lower()
        if ext == ".bmp":
            image = decode_bmp(path)
        else:
            image = decode_png(path)
    except FileNotFoundError:
        warn("texture not found: %s (drawing magenta)" % path)
    except Exception as exc:                                # noqa: BLE001
        warn("cannot decode %s: %s (drawing magenta)" % (path, exc))
    if image is None:
        image = MAGENTA
    _TEXTURE_CACHE[key] = image
    return image


# ---------------------------------------------------------------- OBJ / MTL

def warn(message):
    sys.stderr.write("  ! %s\n" % message)


class Material(object):
    def __init__(self, name):
        self.name = name
        self.kd = (0.78, 0.78, 0.80)
        self.map_kd = None
        self.map_d = None
        self.dissolve = 1.0
        self.texture = None      # (h, w, 4) uint8, resolved lazily
        # MTR carries its shader flag IN the material name (`name#FLAG`), and
        # every flag containing "translucent" selects a real alpha-blended
        # stage. Cutout is the default for everything else, which is what the
        # rest of this renderer models.
        self.translucent = ("#" in name
                            and "translucent" in name.split("#", 1)[1].lower())

    def resolve(self):
        if self.texture is not None:
            return self.texture
        if self.map_kd:
            image = load_texture(self.map_kd).copy()
            if self.map_d:
                mask = load_texture(self.map_d)
                if mask.shape[:2] == image.shape[:2]:
                    image[..., 3] = np.minimum(image[..., 3], mask[..., 0])
                else:
                    warn("map_d size mismatch for %s, ignored" % self.name)
            if self.dissolve < 0.5:
                image = image.copy()
                image[..., 3] = 0
        else:
            rgb = [int(max(0.0, min(1.0, c)) * 255 + 0.5) for c in self.kd]
            alpha = 0 if self.dissolve < 0.5 else 255
            image = np.array([[rgb + [alpha]]], np.uint8)
        self.texture = image
        return image


def _mtl_path(tokens, base):
    """map_Kd may carry options (-s 1 1 1 tex.png); the filename is last."""
    name = tokens[-1]
    candidate = os.path.join(base, name.replace("\\", "/"))
    if os.path.exists(candidate):
        return candidate
    # Case-insensitive fallback: exported OBJs often disagree with the disk.
    folder = os.path.dirname(candidate) or "."
    target = os.path.basename(candidate).lower()
    if os.path.isdir(folder):
        for entry in os.listdir(folder):
            if entry.lower() == target:
                return os.path.join(folder, entry)
    return candidate


def parse_mtl(path, materials):
    base = os.path.dirname(os.path.abspath(path))
    current = None
    try:
        handle = open(path, "r", errors="replace")
    except OSError:
        warn("mtllib not found: %s" % path)
        return
    with handle:
        for line in handle:
            tokens = line.split()
            if not tokens or tokens[0].startswith("#"):
                continue
            key = tokens[0].lower()
            if key == "newmtl":
                current = Material(" ".join(tokens[1:]))
                materials[current.name] = current
            elif current is None:
                continue
            elif key == "kd" and len(tokens) >= 4:
                current.kd = tuple(float(v) for v in tokens[1:4])
            elif key == "map_kd" and len(tokens) >= 2:
                current.map_kd = _mtl_path(tokens[1:], base)
            elif key == "map_d" and len(tokens) >= 2:
                current.map_d = _mtl_path(tokens[1:], base)
            elif key == "d" and len(tokens) >= 2:
                try:
                    current.dissolve = float(tokens[1])
                except ValueError:
                    pass
            elif key == "tr" and len(tokens) >= 2:
                try:
                    current.dissolve = 1.0 - float(tokens[1])
                except ValueError:
                    pass


class Mesh(object):
    def __init__(self):
        self.positions = None     # (nv, 3)
        self.uvs = None           # (nvt, 2)
        self.tri_v = None         # (nt, 3) int32
        self.tri_t = None         # (nt, 3) int32, -1 when untextured
        self.tri_mat = None       # (nt,) int32
        self.tri_grp = None       # (nt,) int32
        self.materials = []       # list[Material] parallel to tri_mat
        self.groups = []          # list[str] parallel to tri_grp


def parse_obj(path):
    base = os.path.dirname(os.path.abspath(path))
    positions = []
    uvs = []
    materials = {}
    mat_order = []
    mat_index = {}
    groups = []
    group_index = {}

    def material_id(name):
        if name not in mat_index:
            mat_index[name] = len(mat_order)
            mat_order.append(materials.get(name) or Material(name))
        return mat_index[name]

    def group_id(name):
        if name not in group_index:
            group_index[name] = len(groups)
            groups.append(name)
        return group_index[name]

    face_v = []
    face_t = []
    face_m = []
    face_g = []
    cur_mat = material_id("__default__")
    cur_grp = group_id("default")
    unresolved = set()

    with open(path, "r", errors="replace") as handle:
        for line in handle:
            if not line or line[0] == "#":
                continue
            tokens = line.split()
            if not tokens:
                continue
            key = tokens[0]
            if key == "v":
                positions.append((float(tokens[1]), float(tokens[2]), float(tokens[3])))
            elif key == "vt":
                u = float(tokens[1])
                v = float(tokens[2]) if len(tokens) > 2 else 0.0
                uvs.append((u, v))
            elif key == "f":
                verts = tokens[1:]
                if len(verts) < 3:
                    continue
                vi = []
                ti = []
                for chunk in verts:
                    parts = chunk.split("/")
                    a = int(parts[0])
                    vi.append(a - 1 if a > 0 else len(positions) + a)
                    if len(parts) > 1 and parts[1]:
                        b = int(parts[1])
                        ti.append(b - 1 if b > 0 else len(uvs) + b)
                    else:
                        ti.append(-1)
                for i in range(1, len(verts) - 1):        # fan triangulation
                    face_v.append((vi[0], vi[i], vi[i + 1]))
                    face_t.append((ti[0], ti[i], ti[i + 1]))
                    face_m.append(cur_mat)
                    face_g.append(cur_grp)
            elif key == "usemtl":
                name = " ".join(tokens[1:]) or "__default__"
                if name not in materials and name != "__default__":
                    unresolved.add(name)
                cur_mat = material_id(name)
            elif key in ("g", "o"):
                cur_grp = group_id(" ".join(tokens[1:]) or "default")
            elif key == "mtllib":
                for chunk in tokens[1:]:
                    parse_mtl(os.path.join(base, chunk), materials)
                # Late-bound: a usemtl seen before its mtllib still resolves.
                for name, mat in materials.items():
                    if name in mat_index:
                        mat_order[mat_index[name]] = mat

    for name in sorted(unresolved):
        if name not in materials:
            warn("usemtl %s has no definition (using flat Kd)" % name)

    mesh = Mesh()
    mesh.positions = (np.array(positions, np.float64)
                      if positions else np.zeros((0, 3)))
    mesh.uvs = np.array(uvs, np.float64) if uvs else np.zeros((0, 2))
    mesh.tri_v = np.array(face_v, np.int64) if face_v else np.zeros((0, 3), np.int64)
    mesh.tri_t = np.array(face_t, np.int64) if face_t else np.zeros((0, 3), np.int64)
    mesh.tri_mat = np.array(face_m, np.int64) if face_m else np.zeros((0,), np.int64)
    mesh.tri_grp = np.array(face_g, np.int64) if face_g else np.zeros((0,), np.int64)
    mesh.materials = mat_order
    mesh.groups = groups
    if len(mesh.tri_v) and (mesh.tri_v.min() < 0
                            or mesh.tri_v.max() >= len(mesh.positions)):
        raise SystemExit("%s references vertex indices outside 1..%d"
                         % (os.path.basename(path), len(mesh.positions)))
    if len(mesh.tri_t) and mesh.tri_t.max() >= len(mesh.uvs):
        warn("some faces reference missing vt indices; those UVs read as (0, 0)")
    return mesh


# ------------------------------------------------------------------- camera

VIEW_DIRS = {
    # name: (direction from model centre toward the camera, up hint)
    "front":              ((0.0, 0.0, 1.0), (0.0, 1.0, 0.0)),
    "rear":               ((0.0, 0.0, -1.0), (0.0, 1.0, 0.0)),
    "side":               ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0)),
    "side-left":          ((-1.0, 0.0, 0.0), (0.0, 1.0, 0.0)),
    "top":                ((0.0, 1.0, 0.0), (0.0, 0.0, 1.0)),
    "bottom":             ((0.0, -1.0, 0.0), (0.0, 0.0, -1.0)),
    "three-quarter":      ((0.85, 0.45, 0.95), (0.0, 1.0, 0.0)),
    "three-quarter-rear": ((0.85, 0.45, -0.95), (0.0, 1.0, 0.0)),
}
DEFAULT_VIEWS = ["front", "rear", "side", "top", "three-quarter", "three-quarter-rear"]
ALL_VIEWS = list(VIEW_DIRS) + ["interior"]


def normalise(vec):
    vec = np.asarray(vec, np.float64)
    length = np.linalg.norm(vec)
    return vec / length if length > 1e-12 else np.array([0.0, 0.0, 1.0])


class Camera(object):
    def __init__(self, eye, target, up_hint, fov_deg):
        self.eye = np.asarray(eye, np.float64)
        forward = normalise(np.asarray(target, np.float64) - self.eye)
        up_hint = normalise(up_hint)
        if abs(float(np.dot(forward, up_hint))) > 0.999:
            up_hint = np.array([0.0, 0.0, 1.0])
        right = normalise(np.cross(forward, up_hint))
        up = np.cross(right, forward)
        self.basis = np.stack([right, up, forward])         # rows
        self.tan_half = math.tan(math.radians(fov_deg) * 0.5)

    def to_view(self, points):
        return (points - self.eye) @ self.basis.T


def build_camera(name, bbox_min, bbox_max, width, height, fov):
    centre = (bbox_min + bbox_max) * 0.5
    corners = np.array([[x, y, z]
                        for x in (bbox_min[0], bbox_max[0])
                        for y in (bbox_min[1], bbox_max[1])
                        for z in (bbox_min[2], bbox_max[2])], np.float64)
    size = float(np.linalg.norm(bbox_max - bbox_min)) or 1.0

    if name == "interior":
        span = bbox_max - bbox_min
        eye = np.array([centre[0],
                        bbox_min[1] + span[1] * 0.72,
                        bbox_max[2] - span[2] * 0.08])
        target = np.array([centre[0],
                           bbox_min[1] + span[1] * 0.55,
                           bbox_min[2]])
        camera = Camera(eye, target, (0.0, 1.0, 0.0), 72.0)
        camera.near = max(1e-4, size * 1e-4)
        return camera

    direction, up_hint = VIEW_DIRS[name]
    direction = normalise(direction)
    probe = Camera(centre + direction * size * 4.0, centre, up_hint, fov)
    rel = corners - centre
    aspect = width / float(height)
    horizontal = rel @ probe.basis[0]
    vertical = rel @ probe.basis[1]
    depth = rel @ (-probe.basis[2])                          # toward the camera
    margin = 1.09
    need = np.maximum(np.abs(horizontal) * margin / (probe.tan_half * aspect),
                      np.abs(vertical) * margin / probe.tan_half)
    distance = float(np.max(need + depth))
    distance = max(distance, size * 0.05)
    camera = Camera(centre + direction * distance, centre, up_hint, fov)
    camera.near = max(1e-4, size * 1e-4)
    return camera


# --------------------------------------------------------------- rasteriser

AMBIENT = 0.55
LIGHT_VIEW = normalise((-0.42, 0.62, -1.0))                  # camera-space key
MAX_CANDIDATES = 6_000_000


def _roll_rows(arr, shift):
    """Cyclically roll each row of (n, 3, ...) so index `shift` comes first."""
    idx = (shift[:, None] + np.arange(3)[None, :]) % 3
    take = idx.reshape(idx.shape + (1,) * (arr.ndim - 2))
    return np.take_along_axis(arr, take, axis=1)


def clip_near(view_pos, uv, tri_ids, near):
    """Clip triangles against z >= near in view space. Returns (pos, uv, ids)."""
    inside = view_pos[:, :, 2] >= near
    count = inside.sum(axis=1)
    out_pos = []
    out_uv = []
    out_id = []

    whole = count == 3
    if whole.any():
        out_pos.append(view_pos[whole])
        out_uv.append(uv[whole])
        out_id.append(tri_ids[whole])

    def lerp(a_pos, a_uv, b_pos, b_uv):
        denom = b_pos[:, 2] - a_pos[:, 2]
        denom = np.where(np.abs(denom) < 1e-12, 1e-12, denom)
        t = ((near - a_pos[:, 2]) / denom)[:, None]
        t = np.clip(t, 0.0, 1.0)
        return a_pos + (b_pos - a_pos) * t, a_uv + (b_uv - a_uv) * t

    one = count == 1
    if one.any():
        pos = _roll_rows(view_pos[one], np.argmax(inside[one], axis=1))
        tex = _roll_rows(uv[one], np.argmax(inside[one], axis=1))
        a_p, a_t = pos[:, 0], tex[:, 0]
        b_p, b_t = lerp(a_p, a_t, pos[:, 1], tex[:, 1])
        c_p, c_t = lerp(a_p, a_t, pos[:, 2], tex[:, 2])
        out_pos.append(np.stack([a_p, b_p, c_p], axis=1))
        out_uv.append(np.stack([a_t, b_t, c_t], axis=1))
        out_id.append(tri_ids[one])

    two = count == 2
    if two.any():
        shift = np.argmin(inside[two], axis=1)               # outside vertex first
        pos = _roll_rows(view_pos[two], shift)
        tex = _roll_rows(uv[two], shift)
        a_p, a_t = pos[:, 0], tex[:, 0]                      # outside
        b_p, b_t = pos[:, 1], tex[:, 1]
        c_p, c_t = pos[:, 2], tex[:, 2]
        p_p, p_t = lerp(b_p, b_t, a_p, a_t)                  # on edge A-B
        q_p, q_t = lerp(c_p, c_t, a_p, a_t)                  # on edge C-A
        ids = tri_ids[two]
        out_pos.append(np.stack([p_p, b_p, c_p], axis=1))
        out_uv.append(np.stack([p_t, b_t, c_t], axis=1))
        out_id.append(ids)
        out_pos.append(np.stack([p_p, c_p, q_p], axis=1))
        out_uv.append(np.stack([p_t, c_t, q_t], axis=1))
        out_id.append(ids)

    if not out_pos:
        return (np.zeros((0, 3, 3)), np.zeros((0, 3, 2)), np.zeros((0,), np.int64))
    return (np.concatenate(out_pos), np.concatenate(out_uv), np.concatenate(out_id))


# v = 0 is the TOP texture row, matching MTR's .obj loader. Flipped by
# --uv-origin bottom for a model authored the OpenGL way. See the module
# docstring for why the default changed.
UV_ORIGIN_TOP = True
# Off by default: seeing every face is what makes this useful for spotting a
# hole or an inside-out group. Turn it on to match MTR, which culls always.
CULL_BACKFACES = False


def render(mesh, camera, width, height, background):
    colour_buf = np.empty((height, width, 3), np.uint8)
    colour_buf[:] = background
    depth_buf = np.zeros((height, width), np.float64)        # 1/z, bigger = nearer
    stats = {"tris": 0, "cand": 0}
    render.last_stats = stats
    if len(mesh.tri_v) == 0:
        return colour_buf

    view_all = camera.to_view(mesh.positions)
    near = getattr(camera, "near", 1e-3)

    # Opaque first, then the translucent stages blended over them — the same
    # order the game draws in, and the only order in which glass can show what
    # is behind it.
    order = ([i for i, m in enumerate(mesh.materials) if not m.translucent]
             + [i for i, m in enumerate(mesh.materials) if m.translucent])
    for mat_id in order:
        material = mesh.materials[mat_id]
        pick = mesh.tri_mat == mat_id
        if not pick.any():
            continue
        texture = material.resolve()
        tex_h, tex_w = texture.shape[:2]

        tri_v = mesh.tri_v[pick]
        tri_t = mesh.tri_t[pick]
        vpos = view_all[tri_v]                               # (n, 3, 3)
        has_uv = (tri_t >= 0).all(axis=1) & (tex_w > 1 or tex_h > 1)
        uv = np.zeros((len(tri_v), 3, 2))
        if mesh.uvs.size:
            safe = np.clip(tri_t, 0, len(mesh.uvs) - 1)
            uv = np.where(has_uv[:, None, None], mesh.uvs[safe], 0.0)

        keep = (vpos[:, :, 2] >= near).any(axis=1)
        if not keep.any():
            continue
        vpos = vpos[keep]
        uv = uv[keep]
        ids = np.arange(len(vpos))
        vpos, uv, ids = clip_near(vpos, uv, ids, near)
        if len(vpos) == 0:
            continue

        # Flat Lambert from the camera-space face normal, flipped toward us.
        edge1 = vpos[:, 1] - vpos[:, 0]
        edge2 = vpos[:, 2] - vpos[:, 0]
        normal = np.cross(edge1, edge2)
        length = np.linalg.norm(normal, axis=1, keepdims=True)
        normal = normal / np.where(length < 1e-12, 1.0, length)
        normal = np.where((normal[:, 2:3] > 0), -normal, normal)
        lambert = np.clip(normal @ LIGHT_VIEW, 0.0, 1.0)
        shade = AMBIENT + (1.0 - AMBIENT) * lambert          # (n,)

        inv_w = 1.0 / vpos[:, :, 2]
        aspect = width / float(height)
        ndc_x = vpos[:, :, 0] * inv_w / (camera.tan_half * aspect)
        ndc_y = vpos[:, :, 1] * inv_w / camera.tan_half
        sx = (ndc_x + 1.0) * 0.5 * width
        sy = (1.0 - ndc_y) * 0.5 * height

        area = ((sx[:, 1] - sx[:, 0]) * (sy[:, 2] - sy[:, 0])
                - (sx[:, 2] - sx[:, 0]) * (sy[:, 1] - sy[:, 0]))
        x0 = np.clip(np.floor(sx.min(axis=1)).astype(np.int64), 0, width)
        x1 = np.clip(np.ceil(sx.max(axis=1)).astype(np.int64) + 1, 0, width)
        y0 = np.clip(np.floor(sy.min(axis=1)).astype(np.int64), 0, height)
        y1 = np.clip(np.ceil(sy.max(axis=1)).astype(np.int64) + 1, 0, height)
        bw = x1 - x0
        bh = y1 - y0
        live = (bw > 0) & (bh > 0) & (np.abs(area) > 1e-9)
        if CULL_BACKFACES:
            # ⭐ THE BACKFACE TEST HAS TO BE THE SCREEN-SPACE WINDING, NOT THE
            # CAMERA-SPACE NORMAL. Culling on `cross(e1, e2).z > 0` is an
            # ORTHOGRAPHIC test: it asks whether the face points at the camera's
            # forward AXIS rather than at the camera's POSITION, so it wrongly
            # drops every face parallel to the view direction. That is fine for
            # a car photographed from 20 blocks away and catastrophic for a
            # camera standing INSIDE one — the `interior` view lost the walls,
            # the ceiling and the floor of a model whose winding was correct,
            # which is precisely the model a cull render exists to audit.
            #
            # The signed area of the projected triangle IS the right test, it
            # is already computed here, and screen y runs DOWN — which flips the
            # sign, so a front face has NEGATIVE area. Verified against the
            # exterior shell: same faces kept from every distant view as the
            # old test kept, plus the ones it should never have dropped.
            live &= area < 0.0
        if not live.any():
            continue

        sx, sy, inv_w, uv = sx[live], sy[live], inv_w[live], uv[live]
        shade, area = shade[live], area[live]
        x0, y0, bw, bh = x0[live], y0[live], bw[live], bh[live]
        stats["tris"] += int(live.sum())

        u_over_w = uv[:, :, 0] * inv_w
        v_over_w = uv[:, :, 1] * inv_w
        boxes = bw * bh
        limits = np.cumsum(boxes)
        total = int(limits[-1])
        stats["cand"] += total

        # Split into chunks that keep peak candidate memory bounded.
        bounds = [0]
        while bounds[-1] < len(boxes):
            start = bounds[-1]
            base = limits[start - 1] if start else 0
            nxt = int(np.searchsorted(limits, base + MAX_CANDIDATES, side="right"))
            bounds.append(max(nxt, start + 1))

        for lo, hi in zip(bounds[:-1], bounds[1:]):
            _raster_chunk(colour_buf, depth_buf, width, height, texture,
                          sx[lo:hi], sy[lo:hi], inv_w[lo:hi],
                          u_over_w[lo:hi], v_over_w[lo:hi], area[lo:hi],
                          shade[lo:hi], x0[lo:hi], y0[lo:hi], bw[lo:hi], bh[lo:hi],
                          material.translucent)
    render.last_stats = stats
    return colour_buf


def _raster_chunk(colour_buf, depth_buf, width, height, texture,
                  sx, sy, inv_w, u_over_w, v_over_w, area, shade,
                  x0, y0, bw, bh, blend=False):
    boxes = bw * bh
    total = int(boxes.sum())
    if total == 0:
        return
    tri = np.repeat(np.arange(len(boxes)), boxes)
    starts = np.concatenate([[0], np.cumsum(boxes)[:-1]])
    local = np.arange(total) - np.repeat(starts, boxes)
    widths = np.repeat(bw, boxes)
    px = np.repeat(x0, boxes) + local % widths
    py = np.repeat(y0, boxes) + local // widths

    cx = px + 0.5
    cy = py + 0.5
    ax, ay = sx[tri, 0], sy[tri, 0]
    bx, by = sx[tri, 1], sy[tri, 1]
    cx2, cy2 = sx[tri, 2], sy[tri, 2]
    w0 = (cx2 - bx) * (cy - by) - (cy2 - by) * (cx - bx)
    w1 = (ax - cx2) * (cy - cy2) - (ay - cy2) * (cx - cx2)
    w2 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    hit = ((w0 >= 0) & (w1 >= 0) & (w2 >= 0)) | ((w0 <= 0) & (w1 <= 0) & (w2 <= 0))
    if not hit.any():
        return

    tri = tri[hit]
    px = px[hit]
    py = py[hit]
    inv_area = 1.0 / area[tri]
    b0 = w0[hit] * inv_area
    b1 = w1[hit] * inv_area
    b2 = w2[hit] * inv_area

    iw = b0 * inv_w[tri, 0] + b1 * inv_w[tri, 1] + b2 * inv_w[tri, 2]
    good = iw > 0
    if not good.all():
        tri, px, py, b0, b1, b2, iw = (a[good] for a in (tri, px, py, b0, b1, b2, iw))
        if len(tri) == 0:
            return

    tex_h, tex_w = texture.shape[:2]
    if tex_w > 1 or tex_h > 1:
        u = (b0 * u_over_w[tri, 0] + b1 * u_over_w[tri, 1] + b2 * u_over_w[tri, 2]) / iw
        v = (b0 * v_over_w[tri, 0] + b1 * v_over_w[tri, 1] + b2 * v_over_w[tri, 2]) / iw
        tx = np.floor(u * tex_w).astype(np.int64) % tex_w
        ty = np.floor((v if UV_ORIGIN_TOP else 1.0 - v) * tex_h)
        ty = ty.astype(np.int64) % tex_h
        texel = texture[ty, tx]
    else:
        texel = np.broadcast_to(texture[0, 0], (len(tri), 4))

    solid = texel[:, 3] > 0 if blend else texel[:, 3] >= 128  # cutout semantics
    if not solid.all():
        keep = solid
        tri, px, py, iw, texel = tri[keep], px[keep], py[keep], iw[keep], texel[keep]
        if len(tri) == 0:
            return

    flat = py * width + px
    depth_flat = depth_buf.reshape(-1)
    contender = iw > depth_flat[flat]
    if not contender.any():
        return
    tri, flat, iw, texel = tri[contender], flat[contender], iw[contender], texel[contender]

    if blend:
        # Glass never writes depth — it must not hide anything drawn after it,
        # and two panes seen through each other should both tint. Resolve to
        # the NEAREST fragment per pixel in a scratch buffer, then blend once.
        scratch = np.zeros_like(depth_flat)
        np.maximum.at(scratch, flat, iw)
        winner = iw >= scratch[flat]
        if not winner.any():
            return
        flat, tri, texel = flat[winner], tri[winner], texel[winner]
        alpha = texel[:, 3:4].astype(np.float64) / 255.0
        rgb = texel[:, :3].astype(np.float64) * shade[tri][:, None]
        dst = colour_buf.reshape(-1, 3)[flat].astype(np.float64)
        mixed = dst * (1.0 - alpha) + rgb * alpha
        colour_buf.reshape(-1, 3)[flat] = np.clip(mixed, 0, 255).astype(np.uint8)
        return

    np.maximum.at(depth_flat, flat, iw)
    winner = iw >= depth_flat[flat]
    if not winner.any():
        return
    flat, tri, texel = flat[winner], tri[winner], texel[winner]

    rgb = texel[:, :3].astype(np.float64) * shade[tri][:, None]
    colour_buf.reshape(-1, 3)[flat] = np.clip(rgb, 0, 255).astype(np.uint8)


# ------------------------------------------------------------------ driving

def make_background(width, height):
    top = np.array([46, 52, 62], np.float64)
    bottom = np.array([24, 26, 31], np.float64)
    ramp = np.linspace(0.0, 1.0, height)[:, None]
    grad = top[None, :] * (1 - ramp) + bottom[None, :] * ramp
    return np.repeat(grad[:, None, :], width, axis=1).astype(np.uint8)


def filter_groups(mesh, wanted):
    names = mesh.groups
    chosen = []
    for want in wanted:
        exact = [i for i, n in enumerate(names) if n == want]
        if not exact:
            exact = [i for i, n in enumerate(names) if want.lower() in n.lower()]
        if not exact:
            warn("no group matches %r" % want)
        chosen.extend(exact)
    if not chosen:
        raise SystemExit("no groups matched %s; try --list-groups" % ", ".join(wanted))
    keep = np.isin(mesh.tri_grp, np.array(sorted(set(chosen)), np.int64))
    mesh.tri_v = mesh.tri_v[keep]
    mesh.tri_t = mesh.tri_t[keep]
    mesh.tri_mat = mesh.tri_mat[keep]
    mesh.tri_grp = mesh.tri_grp[keep]
    if not len(mesh.tri_v):
        raise SystemExit("the selected groups contain no faces")
    return sorted(set(chosen))


def explode_z(mesh):
    """Fan the groups apart along z so overlapping bays can be told apart."""
    used = sorted(set(int(g) for g in np.unique(mesh.tri_grp)))
    if len(used) < 2:
        return
    live = mesh.positions[np.unique(mesh.tri_v), 2]
    span = live.max() - live.min()
    step = 1.2 * (span if span > 1e-9 else 1.0)
    positions = mesh.positions.copy()
    moved = np.zeros(len(positions), bool)
    for rank, gid in enumerate(used):
        verts = np.unique(mesh.tri_v[mesh.tri_grp == gid])
        fresh = verts[~moved[verts]]
        positions[fresh, 2] += rank * step
        moved[fresh] = True
    mesh.positions = positions


def parse_size(text):
    try:
        w, h = text.lower().split("x")
        return max(16, int(w)), max(16, int(h))
    except Exception:
        raise SystemExit("--size must look like 1600x1000")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Render a textured Wavefront OBJ to PNG previews.")
    parser.add_argument("obj")
    parser.add_argument("-o", "--outdir", default=None,
                        help="output directory (default: next to the .obj)")
    parser.add_argument("--size", default="1600x1000", help="WIDTHxHEIGHT")
    parser.add_argument("--views", default=",".join(DEFAULT_VIEWS),
                        help="comma separated; known: %s, or 'all'" % ", ".join(ALL_VIEWS))
    parser.add_argument("--group", action="append", default=[],
                        help="render only these OBJ groups (repeatable, substring ok)")
    parser.add_argument("--explode-z", action="store_true",
                        help="offset each group along z by index * 1.2 * bbox depth")
    parser.add_argument("--fov", type=float, default=32.0,
                        help="vertical field of view in degrees (default 32)")
    parser.add_argument("--cull", action="store_true",
                        help="drop back faces, as MTR does unconditionally — "
                             "use it to see what the game will actually draw")
    parser.add_argument("--list-groups", action="store_true",
                        help="print the group/material inventory and exit")
    parser.add_argument("--uv-origin", choices=("top", "bottom"), default="top",
                        help="which texture row v=0 means. 'top' (default) is "
                             "MTR's own .obj convention; 'bottom' is OpenGL/"
                             "Blender")
    args = parser.parse_args(argv)

    global UV_ORIGIN_TOP, CULL_BACKFACES
    UV_ORIGIN_TOP = args.uv_origin == "top"
    CULL_BACKFACES = args.cull

    width, height = parse_size(args.size)
    views = ALL_VIEWS if args.views.strip() == "all" else [
        v.strip() for v in args.views.split(",") if v.strip()]
    for view in views:
        if view not in ALL_VIEWS:
            raise SystemExit("unknown view %r; known: %s" % (view, ", ".join(ALL_VIEWS)))

    start = time.time()
    mesh = parse_obj(args.obj)
    print("parsed %s: %d verts, %d uvs, %d triangles, %d groups, %d materials (%.2fs)"
          % (os.path.basename(args.obj), len(mesh.positions), len(mesh.uvs),
             len(mesh.tri_v), len(mesh.groups), len(mesh.materials),
             time.time() - start))

    if args.list_groups:
        counts = np.bincount(mesh.tri_grp, minlength=len(mesh.groups))
        print("\ngroups:")
        for i, name in enumerate(mesh.groups):
            print("  %5d tris  %s" % (counts[i], name))
        print("\nmaterials:")
        for mat in mesh.materials:
            src = mat.map_kd or ("Kd %.2f %.2f %.2f" % mat.kd)
            print("  %-28s %s" % (mat.name, src))
        return 0

    if not len(mesh.tri_v):
        raise SystemExit("the OBJ has no faces")
    if args.group:
        filter_groups(mesh, args.group)
    if args.explode_z:
        explode_z(mesh)

    live = np.unique(mesh.tri_v)
    pts = mesh.positions[live]
    bbox_min = pts.min(axis=0)
    bbox_max = pts.max(axis=0)
    print("bbox min %s  max %s  size %s"
          % (np.round(bbox_min, 3), np.round(bbox_max, 3),
             np.round(bbox_max - bbox_min, 3)))

    outdir = args.outdir or (os.path.dirname(os.path.abspath(args.obj)) or ".")
    stem = os.path.splitext(os.path.basename(args.obj))[0]
    background = make_background(width, height)

    # Every division in the rasteriser is explicitly guarded (near-plane clip,
    # degenerate-area reject, iw > 0), and OpenBLAS leaves stale FP status flags
    # after large matmuls, which numpy then reports as phantom divide/overflow.
    with np.errstate(divide="ignore", over="ignore", invalid="ignore"):
        for view in views:
            began = time.time()
            camera = build_camera(view, bbox_min, bbox_max, width, height, args.fov)
            image = render(mesh, camera, width, height, background)
            path = os.path.join(outdir, "%s_%s.png" % (stem, view))
            write_png(path, image)
            stats = getattr(render, "last_stats", {"tris": 0, "cand": 0})
            print("  %-19s %s  (%d tris, %.1fM candidate px, %.2fs)"
                  % (view, path, stats["tris"], stats["cand"] / 1e6,
                     time.time() - began))
    return 0


if __name__ == "__main__":
    sys.exit(main())
