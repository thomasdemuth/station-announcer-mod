"""
Reusable openBVE CSV/B3D object parser.

Used by tools/convert_openbve_m7.py and tools/convert_openbve_r62.py (and,
later, the R143 conversion). Keep it dependency-free and side-effect free — no
numpy, no PIL, no I/O beyond reading the file it is handed.

.b3d AND .csv, ONE PARSER (2026-07-28, added for the R62)
---------------------------------------------------------
The two openBVE object formats differ in EXACTLY ONE character: `.csv` writes
`Keyword, arg, arg` and `.b3d` writes `Keyword arg, arg` — the first separator
is whitespace instead of a comma. Everything else (the keyword aliases
`Vertex`/`Face`/`Face2`/`Coordinates`/`Load`/`Color`/`Transparent`, the
`[MeshBuilder]` section header, comment syntax, argument order) is already
shared and already handled below. So `parse()` autodetects on the extension and
normalises that one separator, rather than there being a second parser to keep
in step. The M7 donor is .csv; the R62 donor is .b3d.

⭐ THE R62 DONOR STORES ITS UVs NEGATIVE (v in -1..0), relying on GL texture
repeat: v_eff = v + 1. That is NOT normalised here — it is a property of that
particular pack, not of the format — and `convert_openbve_r62.py` handles it in
its own UV path. The M7 donor's v is already 0..1.

openBVE object semantics implemented here
-----------------------------------------
* `CreateMeshBuilder` opens a new mesh builder. All following geometry commands
  affect only that builder until the next `CreateMeshBuilder`.
* `AddVertex x y z [nx ny nz]` appends a vertex to the CURRENT builder.
* `AddFace  v0 v1 v2 [...]` / `AddFace2 ...` append a face to the CURRENT
  builder, indexing that builder's own vertex list. AddFace2 is double-sided.
* `Cube HalfWidth HalfHeight HalfDepth` and
  `Cylinder n UpperRadius LowerRadius Height` synthesise geometry into the
  CURRENT builder (origin-centred), with the standard openBVE UVs.
* `Translate/Rotate/Scale/Shear/Mirror` transform every vertex created so far
  in the CURRENT builder.  The `*All` variants transform every vertex created
  so far in the WHOLE FILE (all previously finished builders too).
* `SetTextureCoordinates idx u v` sets the UV of vertex `idx` of the current
  builder.
* `LoadTexture file`, `SetColor r g b [a]`, `SetEmissiveColor r g b`,
  `SetDecalTransparentColor r g b`, `SetBlendMode ...` are material state of
  the current builder.
* Commands are case-insensitive; `;` starts a comment; arguments are comma
  separated.  A comment line of the form `;;LABEL;;` immediately preceding a
  `CreateMeshBuilder` is captured as that builder's label.
"""

from __future__ import annotations

import math
import os
import re
from dataclasses import dataclass, field


# --------------------------------------------------------------------------- #
# data model
# --------------------------------------------------------------------------- #

@dataclass
class Vertex:
    x: float
    y: float
    z: float
    u: float | None = None
    v: float | None = None

    def pos(self):
        return (self.x, self.y, self.z)


@dataclass
class Face:
    indices: list[int]
    two_sided: bool = False


@dataclass
class MeshBuilder:
    index: int
    label: str | None = None          # from a ;;LABEL;; comment above it
    line_no: int = 0
    vertices: list[Vertex] = field(default_factory=list)
    faces: list[Face] = field(default_factory=list)
    texture: str | None = None
    color: tuple | None = None
    emissive: tuple | None = None
    decal_transparent: tuple | None = None
    blend_mode: str | None = None
    # raw record of transform commands seen (name, args) in order
    transforms: list[tuple] = field(default_factory=list)
    primitives: list[tuple] = field(default_factory=list)   # ('Cube', args) etc.

    # -- derived ---------------------------------------------------------- #
    @property
    def two_sided(self) -> bool:
        return any(f.two_sided for f in self.faces)

    def extents(self):
        if not self.vertices:
            return None
        xs = [v.x for v in self.vertices]
        ys = [v.y for v in self.vertices]
        zs = [v.z for v in self.vertices]
        return {
            "x": [min(xs), max(xs)],
            "y": [min(ys), max(ys)],
            "z": [min(zs), max(zs)],
        }

    def uv_extents(self):
        us = [v.u for v in self.vertices if v.u is not None]
        vs = [v.v for v in self.vertices if v.v is not None]
        if not us:
            return None
        return {"u": [min(us), max(us)], "v": [min(vs), max(vs)]}


@dataclass
class ObjectFile:
    path: str
    builders: list[MeshBuilder] = field(default_factory=list)

    def textures(self):
        return sorted({b.texture for b in self.builders if b.texture})

    def extents(self):
        pts = [v for b in self.builders for v in b.vertices]
        if not pts:
            return None
        return {
            "x": [min(v.x for v in pts), max(v.x for v in pts)],
            "y": [min(v.y for v in pts), max(v.y for v in pts)],
            "z": [min(v.z for v in pts), max(v.z for v in pts)],
        }


# --------------------------------------------------------------------------- #
# transforms
# --------------------------------------------------------------------------- #

def _translate(verts, dx, dy, dz):
    for v in verts:
        v.x += dx
        v.y += dy
        v.z += dz


def _scale(verts, sx, sy, sz):
    for v in verts:
        v.x *= sx
        v.y *= sy
        v.z *= sz


def _rotate(verts, ax, ay, az, deg):
    n = math.sqrt(ax * ax + ay * ay + az * az)
    if n == 0:
        return
    ax, ay, az = ax / n, ay / n, az / n
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    t = 1.0 - c
    m = [
        [t * ax * ax + c,       t * ax * ay - s * az, t * ax * az + s * ay],
        [t * ax * ay + s * az,  t * ay * ay + c,      t * ay * az - s * ax],
        [t * ax * az - s * ay,  t * ay * az + s * ax, t * az * az + c],
    ]
    for v in verts:
        x, y, z = v.x, v.y, v.z
        v.x = m[0][0] * x + m[0][1] * y + m[0][2] * z
        v.y = m[1][0] * x + m[1][1] * y + m[1][2] * z
        v.z = m[2][0] * x + m[2][1] * y + m[2][2] * z


def _shear(verts, dx, dy, dz, sx, sy, sz, r):
    for v in verts:
        n = r * (dx * v.x + dy * v.y + dz * v.z)
        v.x += sx * n
        v.y += sy * n
        v.z += sz * n


def _mirror(verts, mx, my, mz):
    fx, fy, fz = (-1 if mx else 1), (-1 if my else 1), (-1 if mz else 1)
    _scale(verts, fx, fy, fz)


# --------------------------------------------------------------------------- #
# primitives
# --------------------------------------------------------------------------- #

def _add_cube(mb: MeshBuilder, hw, hh, hd):
    base = len(mb.vertices)
    pts = [
        (hw, hh, -hd), (hw, -hh, -hd), (-hw, -hh, -hd), (-hw, hh, -hd),
        (hw, hh, hd), (hw, -hh, hd), (-hw, -hh, hd), (-hw, hh, hd),
    ]
    for p in pts:
        mb.vertices.append(Vertex(*p))
    quads = [(0, 1, 2, 3), (0, 4, 5, 1), (0, 3, 7, 4),
             (6, 5, 4, 7), (6, 7, 3, 2), (6, 2, 1, 5)]
    for q in quads:
        mb.faces.append(Face([base + i for i in q]))
    uvs = [(1, 0), (1, 1), (0, 1), (0, 0), (0, 0), (0, 1), (1, 1), (1, 0)]
    for i, (u, v) in enumerate(uvs):
        mb.vertices[base + i].u = float(u)
        mb.vertices[base + i].v = float(v)


def _add_cylinder(mb: MeshBuilder, n, ru, rl, h):
    n = int(abs(n))
    if n < 2:
        return
    base = len(mb.vertices)
    upper_cap = ru < 0
    lower_cap = rl < 0
    ru, rl = abs(ru), abs(rl)
    for i in range(n):
        ang = 2 * math.pi * i / n
        dx, dz = math.cos(ang), math.sin(ang)
        mb.vertices.append(Vertex(dx * ru, h / 2.0, -dz * ru))
        mb.vertices.append(Vertex(dx * rl, -h / 2.0, -dz * rl))
    for i in range(n):
        j = (i + 1) % n
        a, b = base + 2 * i, base + 2 * i + 1
        c, d = base + 2 * j, base + 2 * j + 1
        mb.faces.append(Face([a, c, d, b]))
    if upper_cap or lower_cap:
        pass  # negative radius = cap; openBVE draws caps, geometry-wise ignorable here
    # standard openBVE cylinder UVs
    for i in range(n):
        u = float(i) / n
        mb.vertices[base + 2 * i].u = u
        mb.vertices[base + 2 * i].v = 0.0
        mb.vertices[base + 2 * i + 1].u = u
        mb.vertices[base + 2 * i + 1].v = 1.0


# --------------------------------------------------------------------------- #
# parser
# --------------------------------------------------------------------------- #

_LABEL_RE = re.compile(r"^\s*;+\s*(.*?)\s*;*\s*$")

# `Keyword rest-of-line` — the .b3d spelling of `Keyword,rest-of-line`.
_B3D_RE = re.compile(r"^(\s*)([A-Za-z_][A-Za-z0-9_]*)\s+(.*)$")


def normalize_b3d(text: str) -> str:
    """.b3d source -> .csv source. Only the first separator differs.

    Section headers (`[MeshBuilder]`), comments (`;`) and blank lines pass
    through untouched; every other line gets its first run of whitespace turned
    into a comma. A line with no arguments at all has no first separator to
    convert and is already valid .csv.
    """
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith(";") or s.startswith("["):
            out.append(line)
            continue
        m = _B3D_RE.match(line)
        out.append("%s%s,%s" % (m.group(1), m.group(2), m.group(3)) if m else line)
    return "\n".join(out)


def _num(s, default=0.0):
    s = s.strip()
    if not s:
        return default
    try:
        return float(s)
    except ValueError:
        return default


def parse(path: str) -> ObjectFile:
    """Parse an openBVE object, .csv or .b3d, chosen by extension.

    This is the entry point new code should use; `parse_csv` stays as the name
    the M7 converter has always called and is the .csv-only path.
    """
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    if path.lower().endswith(".b3d"):
        text = normalize_b3d(text)
    return parse_text(text, path)


def parse_csv(path: str) -> ObjectFile:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return parse_text(fh.read(), path)


def parse_text(text: str, path: str = "<text>") -> ObjectFile:
    obj = ObjectFile(path=path)
    pending_label = None
    cur: MeshBuilder | None = None

    raw_lines = text.splitlines()

    for ln, raw in enumerate(raw_lines, start=1):
        line = raw.rstrip("\r\n")
        # capture ;;LABEL;; comments as pending builder labels
        if line.strip().startswith(";"):
            m = _LABEL_RE.match(line)
            if m and m.group(1):
                pending_label = m.group(1)
            continue
        # strip trailing comments
        if ";" in line:
            line = line.split(";", 1)[0]
        if not line.strip():
            continue

        parts = [p.strip() for p in line.split(",")]
        cmd = parts[0].lower()
        args = parts[1:]

        if cmd in ("createmeshbuilder", "[meshbuilder]"):
            cur = MeshBuilder(index=len(obj.builders), label=pending_label, line_no=ln)
            obj.builders.append(cur)
            pending_label = None
            continue

        if cur is None:
            # geometry before any CreateMeshBuilder: openBVE implies one
            cur = MeshBuilder(index=len(obj.builders), label=pending_label, line_no=ln)
            obj.builders.append(cur)
            pending_label = None

        if cmd in ("addvertex", "vertex"):
            cur.vertices.append(Vertex(_num(args[0] if len(args) > 0 else ""),
                                       _num(args[1] if len(args) > 1 else ""),
                                       _num(args[2] if len(args) > 2 else "")))
        elif cmd in ("addface", "face"):
            idx = [int(_num(a)) for a in args if a != ""]
            cur.faces.append(Face(idx, two_sided=False))
        elif cmd in ("addface2", "face2"):
            idx = [int(_num(a)) for a in args if a != ""]
            cur.faces.append(Face(idx, two_sided=True))
        elif cmd in ("settexturecoordinates", "coordinates"):
            i = int(_num(args[0]))
            if 0 <= i < len(cur.vertices):
                cur.vertices[i].u = _num(args[1] if len(args) > 1 else "")
                cur.vertices[i].v = _num(args[2] if len(args) > 2 else "")
        elif cmd in ("loadtexture", "load"):
            cur.texture = args[0] if args else None
        elif cmd in ("setcolor", "color"):
            cur.color = tuple(_num(a) for a in args[:4])
        elif cmd in ("setemissivecolor", "emissivecolor"):
            cur.emissive = tuple(_num(a) for a in args[:3])
        elif cmd in ("setdecaltransparentcolor", "transparent"):
            cur.decal_transparent = tuple(_num(a) for a in args[:3])
        elif cmd in ("setblendmode", "blendmode"):
            cur.blend_mode = ",".join(args)
        elif cmd == "cube":
            a = [_num(x) for x in args[:3]]
            while len(a) < 3:
                a.append(0.0)
            _add_cube(cur, *a)
            cur.primitives.append(("Cube", a))
        elif cmd == "cylinder":
            a = [_num(x) for x in args[:4]]
            while len(a) < 4:
                a.append(0.0)
            _add_cylinder(cur, *a)
            cur.primitives.append(("Cylinder", a))
        elif cmd in ("translate", "translateall"):
            a = [_num(x) for x in (args + ["0", "0", "0"])[:3]]
            targets = cur.vertices if cmd == "translate" else \
                [v for b in obj.builders for v in b.vertices]
            _translate(targets, *a)
            cur.transforms.append((cmd, a))
        elif cmd in ("scale", "scaleall"):
            a = [_num(x, 1.0) or 1.0 for x in (args + ["1", "1", "1"])[:3]]
            targets = cur.vertices if cmd == "scale" else \
                [v for b in obj.builders for v in b.vertices]
            _scale(targets, *a)
            cur.transforms.append((cmd, a))
        elif cmd in ("rotate", "rotateall"):
            a = [_num(x) for x in (args + ["0", "0", "0", "0"])[:4]]
            targets = cur.vertices if cmd == "rotate" else \
                [v for b in obj.builders for v in b.vertices]
            _rotate(targets, *a)
            cur.transforms.append((cmd, a))
        elif cmd in ("shear", "shearall"):
            a = [_num(x) for x in (args + ["0"] * 7)[:7]]
            targets = cur.vertices if cmd == "shear" else \
                [v for b in obj.builders for v in b.vertices]
            _shear(targets, *a)
            cur.transforms.append((cmd, a))
        elif cmd in ("mirror", "mirrorall"):
            a = [int(_num(x)) for x in (args + ["0", "0", "0"])[:3]]
            targets = cur.vertices if cmd == "mirror" else \
                [v for b in obj.builders for v in b.vertices]
            _mirror(targets, *a)
            cur.transforms.append((cmd, a))
        elif cmd in ("generatenormals", "[texture]", "[object]"):
            pass
        else:
            cur.transforms.append(("UNKNOWN:" + cmd, args))

    return obj


# --------------------------------------------------------------------------- #
# .animated parser (INI-like sections)
# --------------------------------------------------------------------------- #

def parse_animated(path: str):
    """Returns a list of (section_name, {key: value}) preserving order."""
    out = []
    cur_name, cur = None, None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith(";"):
                continue
            if ";" in line:
                line = line.split(";", 1)[0].strip()
            if not line:
                continue
            if line.startswith("[") and line.endswith("]"):
                if cur is not None:
                    out.append((cur_name, cur))
                cur_name, cur = line[1:-1], {}
                continue
            if "=" in line and cur is not None:
                k, v = line.split("=", 1)
                k = k.strip().lower()
                v = v.strip()
                if k in cur:
                    cur[k] += " | " + v
                else:
                    cur[k] = v
    if cur is not None:
        out.append((cur_name, cur))
    return out


def png_size(path: str):
    """Pixel size of a PNG without PIL."""
    try:
        with open(path, "rb") as fh:
            head = fh.read(33)
        if head[:8] != b"\x89PNG\r\n\x1a\n":
            return None
        w = int.from_bytes(head[16:20], "big")
        h = int.from_bytes(head[20:24], "big")
        return (w, h)
    except OSError:
        return None
