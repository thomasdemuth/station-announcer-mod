"""The car-agnostic raster kit shared by every custom train's art module.

Extracted verbatim from `m7_art.py` (2026-07-29) once the M7 was settled —
`m7_art.py` (LIRR M7) and `r62_art.py` (NYCT R62) both import from here, and
the next car (R143) should too. Nothing in this module knows about any
particular car: no palette, no livery landmarks, no donor paths, no I/O.
Canvases are pngtool's format — a list of rows, each a list of (r,g,b,a) —
but the kit itself never reads or writes a file.

Three things live here:

1. **Primitives.** `canvas/rect/hband/hline/vline/rrect/rrect_outline/disc/
   ring/line/blit/mirror_x`, all clipping, so callers may draw off the edge
   freely (art positioned in donor metres routinely lands partly outside).
   `hband` exists so that field shading can be made mechanically full-width —
   the "shading varies VERTICALLY only" rule every bodyside elevation in this
   project rests on.
2. **The 3x5 font** (`text/text_width/text_centred`). Deliberately tiny: at
   the densities these models use, side lettering is about one block long, so
   a five-pixel cap height is already the practical ceiling.
3. **The glazing MARK / FIND / CUT mechanism** (`glaze/glazing_rects/
   open_glazing/color_key/solid`, `GLAZE_ALPHA`). A pane is FILLED at partial
   alpha (`glaze`), the glass GEOMETRY is derived from those exact pixels
   (`glazing_rects`), and only then is the aperture cut (`open_glazing`).
   Marking rather than hand-typing rectangles is what keeps the glass quads
   on the holes: art and geometry come out of the same pixels. It works
   identically on openBVE donor art (whose own convention GLAZE_ALPHA is)
   and on art drawn here.
"""

# The openBVE donors' own marker for "openBVE blends this pixel". Art drawn
# in this project uses it too, so one mechanism serves donor art and drawn
# art alike — see the module docstring.
GLAZE_ALPHA = 156


# =====================================================================
# PRIMITIVES
# =====================================================================

def rgba(c):
    return c if len(c) == 4 else (c[0], c[1], c[2], 255)


def canvas(w, h, colour):
    c = rgba(colour)
    return [[c] * w for _ in range(h)]


def rect(rows, x0, y0, x1, y1, colour):
    """Fill a half-open rect [x0,x1) x [y0,y1). Coordinates may be floats."""
    c = rgba(colour)
    h, w = len(rows), len(rows[0])
    x0, x1 = int(round(x0)), int(round(x1))
    y0, y1 = int(round(y0)), int(round(y1))
    for y in range(max(0, y0), min(h, y1)):
        row = rows[y]
        for x in range(max(0, x0), min(w, x1)):
            row[x] = c


def hband(rows, y0, y1, colour):
    """A full-width horizontal band — the only kind of field shading allowed.

    Using this instead of `rect` for anything that is body tone is what makes
    the "shade vertically only" promise mechanical rather than a matter of
    care.
    """
    rect(rows, 0, y0, len(rows[0]), y1, colour)


def hline(rows, y, colour, x0=None, x1=None):
    rect(rows, 0 if x0 is None else x0, y,
         len(rows[0]) if x1 is None else x1, int(round(y)) + 1, colour)


def vline(rows, x, y0, y1, colour):
    rect(rows, x, y0, int(round(x)) + 1, y1, colour)


def _corner_cut(r, i):
    """How many pixels the i-th row in from a corner is inset, for radius r.

    A quarter-circle quantised to whole pixels. Small radii come out as the
    hand-drawn 1-2 px chamfers Minecraft art uses rather than as a blur.
    """
    if r <= 0:
        return 0
    dy = r - i - 0.5
    return int(round(r - (max(0.0, r * r - dy * dy) ** 0.5)))


def rrect(rows, x0, y0, x1, y1, radius, colour):
    """A filled rounded rectangle. Corners are quantised, never anti-aliased."""
    x0, y0, x1, y1 = (int(round(v)) for v in (x0, y0, x1, y1))
    r = min(radius, (x1 - x0) // 2, (y1 - y0) // 2)
    for y in range(y0, y1):
        i = min(y - y0, y1 - 1 - y)
        cut = _corner_cut(r, i) if i < r else 0
        rect(rows, x0 + cut, y, x1 - cut, y + 1, colour)


def rrect_outline(rows, x0, y0, x1, y1, radius, colour, width=1):
    """`width` pixels of border, drawn as two nested fills."""
    rrect(rows, x0, y0, x1, y1, radius, colour)
    return (x0 + width, y0 + width, x1 - width, y1 - width,
            max(0, radius - width))


def disc(rows, cx, cy, r, colour):
    c = rgba(colour)
    h, w = len(rows), len(rows[0])
    for y in range(max(0, int(cy - r)), min(h, int(cy + r) + 2)):
        for x in range(max(0, int(cx - r)), min(w, int(cx + r) + 2)):
            if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                rows[y][x] = c


def ring(rows, cx, cy, r_outer, r_inner, colour):
    c = rgba(colour)
    h, w = len(rows), len(rows[0])
    for y in range(max(0, int(cy - r_outer)), min(h, int(cy + r_outer) + 2)):
        for x in range(max(0, int(cx - r_outer)), min(w, int(cx + r_outer) + 2)):
            d = (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2
            if r_inner * r_inner <= d <= r_outer * r_outer:
                rows[y][x] = c


def line(rows, x0, y0, x1, y1, colour, width=1):
    """Bresenham, thickened by drawing a square nib. Wipers and hand rails."""
    c = rgba(colour)
    h, w = len(rows), len(rows[0])
    x0, y0, x1, y1 = (int(round(v)) for v in (x0, y0, x1, y1))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx0, sy0 = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        for oy in range(width):
            for ox in range(width):
                x, y = x0 + ox, y0 + oy
                if 0 <= x < w and 0 <= y < h:
                    rows[y][x] = c
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx0
        if e2 <= dx:
            err += dx
            y0 += sy0


def blit(dst, x, y, src):
    """Copy `src` over `dst` at (x, y), skipping fully transparent source px."""
    h, w = len(dst), len(dst[0])
    for j, row in enumerate(src):
        yy = y + j
        if not (0 <= yy < h):
            continue
        for i, px in enumerate(row):
            xx = x + i
            if 0 <= xx < w and px[3] != 0:
                dst[yy][xx] = px


def mirror_x(rows):
    return [list(reversed(row)) for row in rows]


# ------------------------------------------------------------------ 3x5 font

_FONT = {
    "A": ("010", "101", "111", "101", "101"),
    "B": ("110", "101", "110", "101", "110"),
    "C": ("011", "100", "100", "100", "011"),
    "D": ("110", "101", "101", "101", "110"),
    "E": ("111", "100", "110", "100", "111"),
    "F": ("111", "100", "110", "100", "100"),
    "G": ("011", "100", "101", "101", "011"),
    "H": ("101", "101", "111", "101", "101"),
    "I": ("111", "010", "010", "010", "111"),
    "J": ("001", "001", "001", "101", "010"),
    "K": ("101", "110", "100", "110", "101"),
    "L": ("100", "100", "100", "100", "111"),
    "M": ("101", "111", "111", "101", "101"),
    "N": ("101", "111", "111", "111", "101"),
    "O": ("010", "101", "101", "101", "010"),
    "P": ("110", "101", "110", "100", "100"),
    "Q": ("010", "101", "101", "110", "011"),
    "R": ("110", "101", "110", "101", "101"),
    "S": ("011", "100", "010", "001", "110"),
    "T": ("111", "010", "010", "010", "010"),
    "U": ("101", "101", "101", "101", "111"),
    "V": ("101", "101", "101", "101", "010"),
    "W": ("101", "101", "111", "111", "101"),
    "X": ("101", "101", "010", "101", "101"),
    "Y": ("101", "101", "010", "010", "010"),
    "Z": ("111", "001", "010", "100", "111"),
    "0": ("111", "101", "101", "101", "111"),
    "1": ("010", "110", "010", "010", "111"),
    "2": ("111", "001", "111", "100", "111"),
    "3": ("111", "001", "111", "001", "111"),
    "4": ("101", "101", "111", "001", "001"),
    "5": ("111", "100", "111", "001", "111"),
    "6": ("111", "100", "111", "101", "111"),
    "7": ("111", "001", "010", "010", "010"),
    "8": ("111", "101", "111", "101", "111"),
    "9": ("111", "101", "111", "001", "111"),
    "-": ("000", "000", "111", "000", "000"),
    ".": ("000", "000", "000", "000", "010"),
    " ": ("000", "000", "000", "000", "000"),
}
GLYPH_W, GLYPH_H = 3, 5


def text_width(s, scale=1, tracking=1):
    if not s:
        return 0
    return (len(s) * (GLYPH_W + tracking) - tracking) * scale


def text(rows, x, y, s, colour, scale=1, tracking=1):
    """Draw uppercase `s` with its top-left at (x, y). Unknown chars blank."""
    c = rgba(colour)
    h, w = len(rows), len(rows[0])
    cx = int(round(x))
    for ch in s.upper():
        glyph = _FONT.get(ch, _FONT[" "])
        for gy, grow in enumerate(glyph):
            for gx, bit in enumerate(grow):
                if bit != "1":
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        px, py = cx + gx * scale + sx, int(round(y)) + gy * scale + sy
                        if 0 <= px < w and 0 <= py < h:
                            rows[py][px] = c
        cx += (GLYPH_W + tracking) * scale
    return cx


def text_centred(rows, cx, y, s, colour, scale=1, tracking=1):
    text(rows, cx - text_width(s, scale, tracking) / 2.0, y, s, colour,
         scale, tracking)


# =====================================================================
# GLAZING — mark, find, cut
# =====================================================================

def glaze(rows, x0, y0, x1, y1, radius=0, tint=None):
    """Mark a pane. The RGB is only what shows if something goes wrong with
    the cut, so it is the glass tint; the ALPHA is the whole message.

    `tint` is mandatory: the kit carries no palette, so the car module passes
    its own GLASS_RGBA (each car defines one, and they deliberately match in
    alpha so different trains' glazing reads as one material in a yard).
    """
    if tint is None:
        raise TypeError("glaze() needs the car's glass tint — pass the art "
                        "module's GLASS_RGBA")
    rrect(rows, x0, y0, x1, y1, radius,
          (tint[0], tint[1], tint[2], GLAZE_ALPHA))


def glazing_rects(rows, min_px=64):
    """Bounding boxes, in uv, of every partial-alpha region in `rows`.

    Derived rather than typed: a pane is exactly the pixels that were marked,
    so the glass GEOMETRY built from these boxes cannot drift from the art.
    Each box is (u0, u1, v0, v1) with v in image convention (v=0 is the top
    row, which is what MTR's .obj loader samples), sorted left to right.

    Works on the openBVE donors unchanged — their own glazing is marked at the
    same alpha — which is what let this mechanism be proven on the M7's cab
    windscreens before any saloon windows were drawn.
    """
    h, w = len(rows), len(rows[0])
    seen = [[False] * w for _ in range(h)]
    out = []
    for sy0 in range(h):
        for sx0 in range(w):
            if seen[sy0][sx0] or not (0 < rows[sy0][sx0][3] < 255):
                continue
            stack, pts = [(sx0, sy0)], []
            seen[sy0][sx0] = True
            while stack:
                cx, cy = stack.pop()
                pts.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if (0 <= nx < w and 0 <= ny < h and not seen[ny][nx]
                            and 0 < rows[ny][nx][3] < 255):
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            if len(pts) < min_px:
                continue
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            out.append(((min(xs)) / float(w), (max(xs) + 1) / float(w),
                        (min(ys)) / float(h), (max(ys) + 1) / float(h)))
    return sorted(out)


def open_glazing(rows):
    """Every marked pane becomes a real hole.

    MTR's EXTERIOR stage is CUTOUT, where anything at alpha >= 128 reads fully
    opaque, so a marked pane left alone renders as a solid plate. Zeroing it
    cuts the aperture; art at alpha 255 (surrounds, wipers, corner chamfers)
    survives untouched and keeps sitting in front of the glass exactly as
    drawn. Call this AFTER `glazing_rects`, never before.
    """
    return [[(r, g, b, 0) if 0 < a < 255 else (r, g, b, a)
             for (r, g, b, a) in row] for row in rows]


def color_key(rows, keys, tol=8):
    """openBVE `SetDecalTransparentColor` -> alpha 0. Keys are (r,g,b)."""
    out = []
    for row in rows:
        new = []
        for (r, g, b, a) in row:
            hit = any(abs(r - kr) <= tol and abs(g - kg) <= tol
                      and abs(b - kb) <= tol for (kr, kg, kb) in keys)
            new.append((r, g, b, 0) if hit else (r, g, b, a))
        out.append(new)
    return out


def solid(colour, size=4):
    """A flat texture. Takes RGB or RGBA — the glazing needs a real alpha."""
    return canvas(size, size, colour)
