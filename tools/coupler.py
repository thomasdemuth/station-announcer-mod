"""The ONE coupler design, shared by every custom train this mod ships.

    import coupler as C
    C.parts(root_d=15.0, knuckle_sign=+1)   # the boxes, in M units
    C.draw_plate()                          # the drawn cast-steel texture

WHY THIS MODULE EXISTS
----------------------
The M7's donor drops its coupler clutter and the R62's was never converted, so
both cars shipped with nothing between them at a coupling. Adding a coupler
twice, in two converters, would have produced two couplers — different
proportions, different palette, and two independent chances to get the
handedness wrong. So the design lives here, in **M units** (1 unit = 1/16
block), and each converter supplies only what is its own: where the assembly
roots into that car's underframe, and how M units reach the world.

Nothing here knows about any car. No donor, no palette import, no I/O.

⭐ THE COUPLING GEOMETRY MTR ACTUALLY GIVES US (4.0.5 bytecode, `Vehicle
   .getPosition` + `VehicleCar.getTotalLength`)
------------------------------------------------------------------------
`getPosition` walks the consist:

    offset += dir * car.getCouplingPadding1(i == 0)     # zeroed on the head car
    ...car centre is at offset + dir * length/2...
    offset += dir * car.getTotalLength(true, false)     # = length + padding2

so the clear distance between car *i*'s rear face and car *i+1*'s front face is
exactly `couplingPadding2(i) + couplingPadding1(i+1)`. **Both trains declare
`couplingPadding` 0, so coupled cars share ONE coupling plane and there is no
gap at all.** A coupler that projected past that plane on the car's centreline
would be inside its neighbour's coupler.

⭐ THE ANSWER IS THE ONE A REAL KNUCKLE COUPLER GIVES: INTERLOCK, DON'T BUTT
---------------------------------------------------------------------------
Every part of the assembly stops `HEAD_FACE_D` units SHORT of the plane except
the knuckle, and the knuckle is offset to one side. Because a coupler's knuckle
is on its own RIGHT looking outward (`knuckle_sign()` derives that from each
emitter's own transform rather than trusting a hand-picked sign), the two
knuckles at a joint land on OPPOSITE sides of the centreline:

    car A  ->|                     |<-  car B          (plan view, at the joint)
             head        head
          ####|                 |####
              |  [A knuckle]    |                       A knuckle: world x < 0
              |     [B knuckle] |                       B knuckle: world x > 0
              P                                         P = the coupling plane

They overlap `2 * KNUCKLE_PROUD` units along the car — so the pair reads as
engaged rather than as two things touching a wall — while staying
`2 * KNUCKLE_INSET` units apart across it, so nothing ever interpenetrates.
A's knuckle still clears B's head face by `HEAD_FACE_D - KNUCKLE_PROUD`.

`coupled_pair()` computes all three of those clearances from the table below
and is called by BOTH converters' `--check`, so the invariant cannot rot.

THE MEMBERS (all whole M units; MTR's corpus has no relief below 1 unit)
-----------------------------------------------------------------------
`d` is measured INBOARD from the coupling plane, so d = 0 is the plane and
larger d is further under the car. y is M units relative to the car FLOOR.

    knuckle   2 w x 3 h x 4 l   d -1 .. 3    one side only
    head      5 w x 4 h x 5 l   d  2 .. 7
    shank     3 w x 3 h         d  6 .. root_d
    yoke      8 w x 4 h x 6 l   d root_d-3 .. root_d+3   the draft-gear pocket

At this project's cross-car scale (~14.8 units/m, the same for both trains
because both were derived from MTR's r179) that is a 337 x 244 x 337 mm head on
a 202 mm drawbar — an AAR head is about 330 x 280 x 380 on a 140 mm shank, so
the head is prototype-sized and the shank is deliberately one step chunkier
than scale, which is the house rule for anything a player sees from 20 blocks.

VERTICAL POSITION
-----------------
`CENTRE_Y` is the axis, in M units below the floor, and it is ONE number for
both cars on purpose: 6 units below the floor is donor y 0.823 m on the M7
(prototype AAR height 0.876) and 0.744 m on the R62 (its donor's own drawbar
centre is 0.80). The assembly then occupies y -8.0 .. -4.0, which clears the
M7's underbody plate (-3.75) and the R62's anticlimber underside (-2.81) above,
and the R62's underframe floor pan (-8.68) below. Each converter asserts its
own version of that in `--check`; this module only promises the envelope.
"""

# ------------------------------------------------------------------ the table

CENTRE_Y = -6.0          # M units relative to the car floor: the coupler axis

HEAD_FACE_D = 2.0        # how far inboard of the coupling plane the head stops
KNUCKLE_PROUD = 1.0      # how far PAST the plane the knuckle reaches
KNUCKLE_LEN = 4.0        # ...and how long it is
KNUCKLE_WIDTH = 2.0      # across the car
KNUCKLE_INSET = 0.5      # its inboard edge, from the centreline
KNUCKLE_HALF_Y = 1.5

HEAD_LEN = 5.0
HEAD_HALF_X = 2.5
HEAD_HALF_Y = 2.0

SHANK_START_D = 6.0      # overlaps the head by one unit, so it cannot detach
SHANK_HALF_X = 1.5
SHANK_HALF_Y = 1.5

YOKE_LEN = 6.0
YOKE_HALF_X = 4.0
YOKE_HALF_Y = 2.0

# The shortest root a car may declare. Below this the yoke would run into the
# head and the assembly would be one lump instead of four members.
MIN_ROOT_D = YOKE_LEN / 2.0 + SHANK_START_D + 2.0        # 11


def parts(root_d, knuckle_sign):
    """The coupler as axis-aligned boxes.

    Returns [(name, x0, x1, y0, y1, d0, d1)] in M units, with `d` measured
    inboard from the coupling plane (d = 0 IS the plane, d grows under the car).
    The caller turns d into its own bay's local z, which is the only thing that
    differs between a bay whose outward face is at local -z and one at +z.

    `knuckle_sign` is +1 or -1 and comes from `knuckle_sign()` — never typed.
    `root_d` is how far inboard the assembly disappears into that car's
    underframe; the yoke straddles it, half buried.
    """
    if knuckle_sign not in (1, -1, 1.0, -1.0):
        raise ValueError("knuckle_sign must be +1 or -1, got %r" % (knuckle_sign,))
    if root_d < MIN_ROOT_D - 1e-9:
        raise ValueError("root_d %.2f is inboard of MIN_ROOT_D %.2f — the yoke "
                         "would collide with the head" % (root_d, MIN_ROOT_D))
    s = 1.0 if knuckle_sign > 0 else -1.0
    y = CENTRE_Y
    ka, kb = sorted((s * KNUCKLE_INSET, s * (KNUCKLE_INSET + KNUCKLE_WIDTH)))
    return [
        ("coupler_yoke", -YOKE_HALF_X, YOKE_HALF_X,
         y - YOKE_HALF_Y, y + YOKE_HALF_Y,
         root_d - YOKE_LEN / 2.0, root_d + YOKE_LEN / 2.0),
        ("coupler_shank", -SHANK_HALF_X, SHANK_HALF_X,
         y - SHANK_HALF_Y, y + SHANK_HALF_Y,
         SHANK_START_D, root_d),
        ("coupler_head", -HEAD_HALF_X, HEAD_HALF_X,
         y - HEAD_HALF_Y, y + HEAD_HALF_Y,
         HEAD_FACE_D, HEAD_FACE_D + HEAD_LEN),
        ("coupler_knuckle", ka, kb,
         y - KNUCKLE_HALF_Y, y + KNUCKLE_HALF_Y,
         -KNUCKLE_PROUD, KNUCKLE_LEN - KNUCKLE_PROUD),
    ]


def emit(add_box, material, root_d, knuckle_sign, z_of_d):
    """Draw the coupler through a converter's own box helper.

    `add_box(material, x0, x1, y0, y1, z0, z1)` takes M units throughout —
    each converter wraps its own metres-and-units `box()` to match — and
    `z_of_d(d)` turns an inboard distance into that bay's local z.

    Every member is a CLOSED box, so this contributes exactly zero to any
    net-normal winding sum and cannot perturb a group's existing checks.
    """
    for _name, x0, x1, y0, y1, d0, d1 in parts(root_d, knuckle_sign):
        add_box(material, x0, x1, y0, y1, z_of_d(d0), z_of_d(d1))


def knuckle_sign(outward_mz, emit_x_sign):
    """Which authored +/-x is the coupler's own RIGHT, looking outward.

    ⭐ DERIVED, NEVER TYPED — this is the R62's "a check that does not apply
    the transform is not a check" lesson, applied before the fact. The two
    converters emit through DIFFERENT maps (the M7 negates x and z, a rotation;
    the R62 keeps x, negates z and reverses winding, a mirror), and their end
    bays face opposite ways in M space (the M7 outward at local -z, the R62 at
    +z). Four sign conventions between them, and picking the wrong combination
    puts both knuckles at a joint on the SAME side, where they interpenetrate.

    The derivation, once:

      * both emitters negate z, so a bay whose outward normal is M `outward_mz`
        faces world z direction `f = -outward_mz`;
      * facing (0, 0, f) with +y up, "right" is world x of sign `-f`
        (facing south, +z, right is west, -x — check it against Minecraft);
      * world x = `emit_x_sign` * M x, so the authored M x sign wanted is
        `(-f) * emit_x_sign` = `outward_mz * emit_x_sign`.

    Both trains happen to land on +1, and that is a fact worth having derived
    rather than a coincidence worth relying on.
    """
    s = (1 if outward_mz > 0 else -1) * (1 if emit_x_sign > 0 else -1)
    return s


def coupled_pair(root_d=None, knuckle_sign=+1):
    """Measure the joint two of these make when the cars share a plane.

    Returns a dict of the three numbers that matter, all in M units:

      `overlap`   how far the two knuckles interleave along the car. > 0 means
                  the pair reads as engaged rather than as two blocks touching.
      `lateral`   the clear space between them across the car. MUST be > 0 —
                  this is the whole reason the knuckle is offset.
      `head_gap`  a knuckle's clearance to the OTHER car's head face. MUST be
                  > 0, and it is what stops the interleave being a collision.
      `plane_max` the furthest ANY non-knuckle member reaches toward the plane.
                  MUST be > 0 (i.e. everything else stops short).

    The neighbour is this same assembly turned end for end, which is exactly
    what MTR does: a consist places `end1` unflipped and `end2` flipped, so the
    joint is always end2-against-end1 and never two like ends.
    """
    ours = parts(root_d if root_d is not None else MIN_ROOT_D, knuckle_sign)
    theirs = [(n, -x1, -x0, y0, y1, d0, d1)          # 180 degrees about y
              for (n, x0, x1, y0, y1, d0, d1) in ours]
    ka = [p for p in ours if p[0] == "coupler_knuckle"][0]
    kb = [p for p in theirs if p[0] == "coupler_knuckle"][0]
    head = [p for p in theirs if p[0] == "coupler_head"][0]
    # In world terms both are measured as "distance inboard from the plane on
    # my own side", so the neighbour's d is the plane's other side: a member at
    # d lands at plane + d for them and plane - d for us.
    overlap = 2.0 * KNUCKLE_PROUD
    # Positive only when the two are DISJOINT across the car; negative is how
    # far they interpenetrate, which is the failure this exists to report.
    lateral = max(ka[1] - kb[2], kb[1] - ka[2])
    head_gap = head[5] - KNUCKLE_PROUD
    plane_max = min(d0 for (n, _x0, _x1, _y0, _y1, d0, _d1) in ours
                    if n != "coupler_knuckle")
    return {"overlap": overlap, "lateral": lateral,
            "head_gap": head_gap, "plane_max": plane_max}


# --------------------------------------------------------------------- the art
#
# ⭐ ONE TEXTURE, ONE MATERIAL, IDENTICAL BYTES IN BOTH TRAINS' DIRECTORIES.
# Every member is a box unwrapped with the whole texture on every face, so the
# art has to read as cast steel at any size and from any direction. That rules
# out anything with a top or a left: no bevel, no directional highlight, no
# label. What it leaves is a MOTTLE plus horizontal casting ribs, which is what
# a coupler body actually looks like, and which stretches without a seam.
#
# The palette sits between the M7's ANTICLIMBER_LO (80,84,91) and the R62's
# UNDER_LO (58,60,64): darker than either car's underframe, because a coupler
# is unpainted forged steel in permanent shadow under the buffer beam.

TEX_SIZE = 32
STEEL = (66, 69, 74)
STEEL_HI = (84, 87, 93)
STEEL_LO = (48, 50, 54)
STEEL_DEEP = (36, 38, 41)
BOLT = (104, 107, 113)


def _mottle(i):
    """A deterministic scatter. A plain LCG, so the art is byte-reproducible
    on every machine and every Python — `random` is not, across versions."""
    return (i * 1103515245 + 12345) & 0x7FFFFFFF


def draw_plate(kit):
    """The cast-steel plate. `kit` is tools/pixel_kit, passed in so this module
    stays import-free and the raster primitives keep their single home.

    ⭐ THE WHOLE TEXTURE LANDS ON EVERY FACE OF EVERY MEMBER, so it must read
    the same way at any size and in any orientation. That rules out a
    directional bevel, a gradient or a label — and it rules out the horizontal
    casting ribs the first cut used, which stretched into brick courses on the
    yoke's long faces. What is left is a symmetric EDGE: a dark rim with a
    lighter step just inside it, which turns each face into a distinct
    rectangle. That is what makes four abutting boxes read as four members
    rather than as one lump, and it survives every aspect ratio because it is
    measured from the edge rather than laid out across the face.
    """
    n = TEX_SIZE
    rows = kit.canvas(n, n, STEEL)

    # The rim, outermost first: shadow, then the raised face's own step.
    kit.rect(rows, 0, 0, n, n, STEEL_DEEP)
    kit.rect(rows, 1, 1, n - 1, n - 1, STEEL_HI)
    kit.rect(rows, 2, 2, n - 2, n - 2, STEEL_LO)
    kit.rect(rows, 3, 3, n - 3, n - 3, STEEL)

    # The mottle — forged steel, not sheet. Deliberately faint: two tones one
    # step either side of the field, about 12% of the interior.
    for y in range(3, n - 3):
        for x in range(3, n - 3):
            r = _mottle(y * n + x) >> 9
            if r % 17 == 0:
                rows[y][x] = kit.rgba(STEEL_LO)
            elif r % 23 == 0:
                rows[y][x] = kit.rgba(STEEL_HI)

    # Two bolt heads on the centreline, with a shadow pixel under each. Two
    # rather than four because a face may be one unit wide, and a pair centred
    # on the short axis still lands inside it.
    for cx in (n // 3, 2 * n // 3):
        kit.rect(rows, cx - 1, n // 2 - 1, cx + 1, n // 2 + 1, BOLT)
        kit.rect(rows, cx - 1, n // 2 + 1, cx + 1, n // 2 + 2, STEEL_DEEP)
    return rows


# ------------------------------------------------------------------- self-test

def _verify():
    p = dict((n, (x0, x1, y0, y1, d0, d1))
             for (n, x0, x1, y0, y1, d0, d1) in parts(MIN_ROOT_D, +1))

    # Every member is at least one M unit in every direction (MTR's corpus has
    # nothing between 0.3 and 1, so anything thinner reads as a scratch).
    for name, (x0, x1, y0, y1, d0, d1) in p.items():
        for label, size in (("width", x1 - x0), ("height", y1 - y0),
                            ("length", d1 - d0)):
            assert size >= 1.0 - 1e-9, "%s %s is %.2f units" % (name, label, size)

    # The envelope both cars are sized against.
    lo = min(v[2] for v in p.values())
    hi = max(v[3] for v in p.values())
    assert (lo, hi) == (-8.0, -4.0), (lo, hi)

    # The members overlap in a chain, so the assembly is connected.
    assert p["coupler_knuckle"][5] > p["coupler_head"][4]
    assert p["coupler_head"][5] > p["coupler_shank"][4]
    assert p["coupler_shank"][5] > p["coupler_yoke"][4]

    # The knuckle is the ONLY thing forward of the head's face, and the only
    # thing that crosses the plane.
    for name, (_x0, _x1, _y0, _y1, d0, _d1) in p.items():
        if name != "coupler_knuckle":
            assert d0 >= HEAD_FACE_D - 1e-9, name
    assert p["coupler_knuckle"][0] > 0 and p["coupler_knuckle"][1] > 0, \
        "the knuckle must sit wholly on one side of the centreline"

    # The joint.
    j = coupled_pair()
    assert j["overlap"] > 0, j
    assert j["lateral"] >= 1.0 - 1e-9, j
    assert j["head_gap"] >= 1.0 - 1e-9, j
    assert j["plane_max"] > 0, j

    # The handedness derivation, against both shipping emitters.
    assert knuckle_sign(-1, -1) == +1        # M7:  outward local -z, x negated
    assert knuckle_sign(+1, +1) == +1        # R62: outward local +z, x kept
    assert knuckle_sign(+1, -1) == -1
    assert knuckle_sign(-1, +1) == -1


_verify()


if __name__ == "__main__":
    print("coupler — one design, %d members, M units" % len(parts(15.0, +1)))
    print("  axis at y %.1f, envelope y %.1f .. %.1f"
          % (CENTRE_Y, CENTRE_Y - YOKE_HALF_Y, CENTRE_Y + YOKE_HALF_Y))
    for name, x0, x1, y0, y1, d0, d1 in parts(15.0, +1):
        print("  %-16s x %5.1f..%5.1f  y %5.1f..%5.1f  d %5.1f..%5.1f"
              % (name, x0, x1, y0, y1, d0, d1))
    j = coupled_pair(15.0, +1)
    print("  coupled joint: knuckles interleave %.1f units, %.1f apart "
          "across the car, %.1f clear of the other head; everything else "
          "stops %.1f short of the plane"
          % (j["overlap"], j["lateral"], j["head_gap"], j["plane_max"]))
