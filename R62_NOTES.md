# R62 conversion notes — what is specific to THIS car

Written 2026-07-28 during the exterior-first pass; the **INTERIOR landed
2026-07-29** and is written up in §12. Companion to `M7_CONVERSION_NOTES.md`,
which holds the **MTR loader law** — units, axes, materials, the flipped-z
composition, the R179 door curve, the family of .obj defects that force
doors/floors/doorways/displays into a companion `.bbmodel`. **None of that is
repeated here and none of it is re-derived.** This file holds only what the R62
does differently, and it is written for the sound pass that comes next.

---

## 0. What exists

| File | What it is |
|---|---|
| `tools/r62_layout.py` | **The single source of truth for scale and bay layout.** Self-verifies on import. Run it to print the layout. Change the car HERE |
| `tools/r62_art.py` | Palette (exterior AND interior — §12), livery landmarks, motifs. Imports the raster kit from `pixel_kit` (see §7) |
| `tools/pixel_kit.py` | The car-agnostic raster kit — primitives, 3×5 font, MARK/FIND/CUT glazing — shared with the M7 (see §7) |
| `tools/convert_openbve_r62.py` | `r62.obj` + `r62.mtl` + 22 textures. `--check` cross-validates everything; `--assemble DIR` bakes whole-car preview OBJs. Run FIRST |
| `tools/convert_r62_interior.py` | `r62_interior.obj` + one atlas: the saloon, the half-cab, the strip-map board. Own `--check` / `--assemble` (`--with-shell` folds the body in). Run SECOND |
| `tools/gen_r62_doors.py` | `r62_doors.bbmodel` + `doors_box.png`: leaves, floors, doorways, and all TWELVE displays. Run THIRD |
| `tools/gen_r62_assets.py` | Properties + definitions, and a `vehicles()` provider. Run FOURTH |
| `tools/gen_vehicle_index.py` | Composes the SHARED `mtr_custom_resources.json` from every train's provider (§8) |

Shipped: `r62.obj` (6 groups, 367 verts, 394 faces, 21 materials),
`r62_interior.obj` (4 groups, 398 verts, 283 faces, ONE material),
`r62_doors.bbmodel` (20 groups), 24 textures (~108 KB in total), 14 properties
files, 1 definition file, 2 vehicles.

Pipeline: `convert_openbve_r62.py` → `convert_r62_interior.py` →
`gen_r62_doors.py` → `gen_r62_assets.py` → both `--check`s. Idempotent; both
`--check`s at **0 problems**.

**Assets are GENERATED. Never hand-edit them.**

---

## 1. ⭐ THE ONE BIG STRUCTURAL DIFFERENCE: ROTATIONAL, NOT MIRROR, SYMMETRY

The M7 is mirror-symmetric across the car: model one side, let
`positionsFlipped` supply the other. **The R62 is 180-degree ROTATIONALLY
symmetric and that rule does not apply.**

- The side rollsign is in the **+z panel of the +x side** and the **−z panel of
  the −x side**. The two panels flanking a door are not mirror images; they are
  each other rotated.
- The half-cab is diagonally opposite end to end: cab on +x at the +z end, on
  −x at the −z end. The front route roundel is on the OTHER side from the cab at
  each end.

**So every bay group models BOTH SIDES AT ONCE, and a `positionsFlipped` entry
supplies the far END of the car, not the far side.** Consequences:

| bay | 180-symmetric in itself? | placement |
|---|---|---|
| `panel` | **no** (rollsign one side, plain window the other) | one unflipped + one flipped → 2 per car |
| `door` | yes | `positions` only, 3 entries |
| `end` | **no** (cab one side, roundel the other) | one unflipped + one flipped |

**Nothing is ever placed by a symmetric both-lists definition** — that would
draw each bay twice, inside itself. This is the opposite of the M7, where
both-lists is the norm.

`--check` measures it rather than asserting it: it bakes every placement and
requires **exactly two side-rollsign faces per car, diagonally opposite**.

### The elevation exploits it

`Ext.png` is one elevation of one car side, and BOTH sides read it — the −x side
mirrored. Fitted off the donor's own UVs to zero error:

```
+x:  u = -0.069888 z + 0.500001    =>  z(u) = 7.1543 - 14.3086 u
-x:  u = +0.069888 z + 0.499999    =>  u(-x, z) = 1 - u(+x, z)
```

⭐ **That mirroring IS the rotational symmetry in texture space.** The image
column carrying the rollsign is u 0.286…0.381, which is donor z +1.71…+3.06 on
+x and −3.06…−1.71 on −x — exactly where each side's sign is. So one drawn
elevation serves the whole car, and a bay takes **two crops of it, one per
side**. `r62_layout._verify()` asserts `u_of_z(z, +1) == u_of_z(-z, -1)`; every
claim above rests on that line.

---

## 2. The .b3d format, and where the donor is used

The R62 pack is `.b3d`, not `.csv`. The two formats differ in **exactly one
character**: `.csv` writes `Keyword, arg, arg` and `.b3d` writes
`Keyword arg, arg` — the first separator is whitespace. Every keyword alias,
the `[MeshBuilder]` header, comments and argument order are already shared.

That normalisation is folded into `tools/bve_csv.py` as `normalize_b3d()` +
`parse()` (autodetects on the extension). **Do not fork the parser.**

Other donor quirks:
- **UVs are stored NEGATIVE** (v in −1..0), relying on GL repeat: `v_eff = v+1`.
  Not normalised in the parser — it is this pack's quirk, not the format's.
- Glazing is a **blue (0,0,255) colour key**, not the M7's alpha-156 marks.
- `rotateyfunction = 355` means 355 **radians** = 180.000 degrees. It is the
  pack's idiom for "place this the other way round". Not 355 degrees.

### ⭐ THE DONOR IS NOT PARSED AT BUILD TIME, AND THAT IS DELIBERATE

The M7 converter transforms donor meshes. This one does not: the R62's bodyside
is a flat plane, its nose is a ruled surface, and every feature is **rebuilt**
rather than copied. What the donor supplies is a **table of measurements**, and
a table belongs in `r62_layout.py` where three tools can read it without anyone
waiting on a 5,856-vertex parse.

The cost of baking measurements is that they can silently stop matching their
source. `check_against_donor()` closes that: when the donor is present, `--check`
parses it and asserts the bodyside plane, the extents, both uv fits and the
nose's ruled-surface property still agree. When it is absent the build still
works and the check says it was skipped.

**No donor pixel ships.** All art is drawn.

---

## 3. Layout numbers (all DERIVED in `r62_layout.py` — do not type them)

```
end(38.5) | door(21) | panel(58) | door(21) | panel(58) | door(21) | end(38.5)
    = 256 units = 16 blocks       ends ±108.75   doors 0, ±79   panels ±39.5
```

| | value | where from |
|---|---|---|
| half width | 19.418 units (1.2136 blk) | MTR's r179 body (1.387 blk @ 10'0") × 8.75/10 |
| x scale | 14.813 units/m | ...over the donor's 1.3109 m. **Within 0.2% of the M7's 14.838**, independently derived — the two trains are at one cross-car scale |
| y scale | 16.408 units/m, **one scale above and below the floor** | r179's 1.0255 blk/m. The M7 needed a second compressed factor below its floor; the R62's underframe is only 0.529 m deep and clears the rail at natural scale |
| z scale | 16.452 units/m | 256 units over the donor's 15.56 m |
| bogies | ratio **0.704**, ±5.63 blk | the donor's own `Axles = -5.48, 5.48`. No tuning needed — MTR's 4-block truck stays inboard of the coupler faces |

**END_UNITS is 38.5, not an integer, and that is fine.** Position entries are
doubles and an .obj bay has no integer constraint. The only integers MTR insists
on are **.bbmodel element sizes** (`Math.round(to − from)`, which is also the
text canvas for a display) — and every element is sized off `DOOR_UNITS` /
`LEAF_*`, which are integers by construction.

### The door bay IS the aperture

The donor's own bay boundaries **are** the door opening's edges (±0.634 for the
centre door). So unlike the M7 — whose door bay carried a flanking panel each
side — this bay has **no jamb in it at all**: just the header above, the sill
band below, the rubber reveal around the hole, and a threshold plate. The jambs
belong to the panels and ends either side.

---

## 4. Doors: two groups, not four

The M7 needed four one-sided leaf groups because its pair had no symmetry. The
R62's **is** symmetric: on +x the narrow leaf sits at +z of the meeting point,
on −x at −z. A flipped entry is exactly that turn.

```
group        positions        positionsFlipped   doorZMultiplier   opens
door_narrow  [-79, 0, +79]    [-79, 0, +79]      +10.5             10.0
door_wide    [-79, 0, +79]    [-79, 0, +79]      -11.5             11.0
```

Leaves **10 + 11 = 21 units**, tiling the aperture exactly, meeting at +0.5 —
the donor's own unequal 0.617/0.650 m pair in the same ratio, each retracting
~99% of its own width (the r179 ground truth). R179 opens by `|m| − 0.5`.

⭐ **The pocket is trivial on this car.** The M7's leaf had to be a stack of five
flat planes because tumblehome makes the skin-to-lining corridor lean. The R62's
bodyside is a **flat plane** — no tumblehome — so one plane per render stage is
exact: outboard at x 18.737 (EXTERIOR), inboard at 18.366 (INTERIOR), skin at
19.418. Clearance to the skin **0.68 units**.

### ⭐ THE RESERVED CORRIDOR: nothing inside may cross donor x 1.2299 m

`r62_layout.LINING_MAX_X_M`. The exterior pass reserved it before there was a
lining to measure, and `convert_openbve_r62.py --check` asserts the leaf stays
outboard of it. **The interior now honours it from the other side**:
`convert_r62_interior.py --check` measures the assembled car and fails on any
interior vertex outboard of the same number. The donor's own lining is at
1.193, 37 mm inboard, so this cost nothing — and the doorway reveal (the one
piece that WANTS to reach the leaf) stops exactly on the line rather than 5 mm
short of the leaf, which is the difference between a rule with an exception and
a rule.

---

## 5. The nose: a ruled bullnose, faceted

The donor models the front with **252 faces over 79 distinct z planes**. What it
actually IS, once measured, is much simpler:

- **Below the eaves (y ≤ 3.107) z depends on |x| ONLY** — a ruled bullnose,
  0.37 m deep on the centreline. So a quad spanning `[x0,x1]` between two
  CONSTANT heights is **exactly planar**, and 8 facets per half reproduce the
  plan curve to within **4 mm** (measured against the donor every run).
- **The centre is a HOLE.** No mask vertices between x ±0.4168: that span is the
  storm-door recess, with the leaf 248 mm back at z 7.277. Real returns, real
  soffit — most of the end's depth comes from it.
- **The outer 71 mm is simplified.** The donor does not ramp to the bodyside; it
  runs forward to z 7.258, doubles back to 7.1925 and only then meets the side —
  a 65 mm fold three centimetres wide. `NOSE_PLAN` replaces it with a straight
  ramp. `check_against_donor` excludes that span explicitly and says so.

⭐ **This is the M7's roof-cap lesson applied in advance.** What read as "the
weird triangle thing" on that car was a non-planar 13-triangle fan whose slivers
each took their own flat normal. Nothing here is non-planar except the fillet
(below), and nothing here can facet.

### The fillet is the one non-planar surface, and a flat cap was rejected

Its lower edge follows the nose (z varies with x); its upper edge is the roof's
leading edge at a constant donor z 7.1543. A quad between them is bilinear. The
M7's answer — a flat cap in one z plane — would leave a **horizontal shelf up to
0.37 m deep** across the top of the nose, and the R62's roof visibly rolls
forward instead. So it is built as a ruled strip on the union of both polylines'
x samples. The widest segment deviates about **0.05 m**, under one M unit, over a
quad two units tall: a fraction of a degree between the two triangles.

### Winding is COMPUTED, not hand-ordered

`quad()` takes the direction the face is supposed to point and reverses itself if
the Newell normal disagrees. A dozen bullnose facets whose "obvious" vertex order
changes sign halfway round is exactly where a reversed quad hides, and MTR culls
backfaces unconditionally — a reversed quad is simply not there.

`check_winding()` is the independent second opinion, and it checks **individual
faces whose correct normal is known from their own coordinates** (every bodyside
face must face away from the axis; every underframe-bottom face must face down),
not a net sum per group. ⭐ **A net-normal sum per group is not a winding check** —
the first version summed y-normals and went red the moment the underframe grew a
floor pan larger than the roof.

---

## 6. Displays

MTR 4.0.5 renders **no DISPLAY part on an .obj at all** (M7_CONVERSION_NOTES).
All twelve live in `r62_doors.bbmodel`.

| element | type | size | where |
|---|---|---|---|
| `side_bullet_color` | ROUTE_COLOR_ROUNDED | 4×4 | the sign box, toward the car centre |
| `side_bullet_number` | ROUTE_NUMBER | 4×4 | over the disc — the NYC bullet |
| `side_route_number` | ROUTE_NUMBER, scrolling | 11×2 | top line |
| `side_destination` | DESTINATION, scrolling | 11×2 | bottom line |
| `front_route_color` | ROUTE_COLOR_ROUNDED | 7×7 | the cab-front roundel |
| `front_route_number` | ROUTE_NUMBER | 7×7 | over it |
| `int_map_bullet_color_a/_b` | ROUTE_COLOR_ROUNDED | 3×3 | the strip map's bullet, one set per SIDE (§12) |
| `int_map_bullet_number_a/_b` | ROUTE_NUMBER | 3×3 | over it |
| `int_map_next_a/_b` | NEXT_STATION, scrolling | 18×3 | the strip map's LED insert |

### ⭐ THE TRAP THAT SHIPPED, AND HOW IT WAS FOUND (2026-07-29)

`M7_CONVERSION_NOTES` documents it — *"put the along-car centre in
`from[0]`/`to[0]` and keep `origin[2] = 0`; writing the centre into `origin[2]`
instead lands the plate that far INSIDE the car, invisible, with nothing
logged"* — and **this car's side rollsign walked into it anyway**. A rotated
element's offsets are relative to `origin`, so with the centre in `origin[2]`,
`dz = standoff − centre`, and yaw −90 turns `dz` into −x. Measured on the JSON
that shipped:

```
side_bullet_color   centre_z −6.03  ->  world x 14.9   (4.5 units INSIDE the
                                        skin, and inside the new lining)
side_route_number   centre_z +2.00  ->  world x 22.9   (2.5 proud of a housing
                                        face at 20.4)
```

Nothing in game reports it: the sign box is simply blank, and the bullet is
inside the car. It went undetected because **`check_displays()` tested the
UNROTATED element centre** — an orthographic shortcut that measures a point the
game never draws anything at — *and* because its target table listed a display
name (`side_route_bullet`) that no element has ever had, so the two bullet
fields were not checked at all. Both are fixed: the check now applies the
element's own yaw about its own origin before placing it, and the names match.
The fix to the elements is in `gen_r62_doors.side_display_element`.

**The lesson, for the next car:** a display check that does not apply the
rotation is not a display check. Every plate in this model is now placed by the
same three lines, and the interior's own `check_displays()` (§12) applies the
same rotation for the same reason.

### ⭐ MTR HAS NO "ROUTE NAME" DISPLAY TYPE

Verified in 4.0.5 bytecode. `ModelPropertiesPart.formatText` **does** compute the
route's name — but only to feed `getLondonNextStationString`. The enum is
DESTINATION, ROUTE_NUMBER, DEPARTURE_INDEX, NEXT_STATION{,\_KCR,\_MTR,\_UK},
ROUTE_COLOR, ROUTE_COLOR_ROUNDED and nothing else. `ROUTE_NUMBER` renders
`VehicleExtraData.getThisRouteNumber()` — a free string an operator sets per
route, usually the line's identity, which is what the top line of an R62's side
sign carries. **So the top line is ROUTE_NUMBER.** That is the documented choice
the brief asked for.

### ⭐ THE ONE UNPRECEDENTED THING IN THIS MODEL: `rotation: [0, 180, 0]`

A DISPLAY plate's text faces its own **−Z**. The end bay is authored with its
outward face at local **+z** — which is what keeps the car's chirality faithful
to the donor, and is not negotiable: a bay whose donor→local map has negative
determinant produces a MIRRORED car when placed unflipped, putting the cab on
the wrong side relative to the side rollsign. So the roundel's two elements carry
a 180-degree yaw, and **MTR ships no 180-degree display rotation to calibrate
against** — r179 only ever uses ∓90 (which the side sign uses, verbatim).

**The mitigation is in what is on the plate.** If a 180 yaw mirrored the text's
advance direction, a multi-character string would read backwards — so nothing
multi-character goes there. The roundel is a colour disc (no handedness at all)
with a route bullet over it, and an NYC bullet is one or two characters. A single
centred glyph reads identically either way round.

**This is the first thing to look at in game** (§10).

### The cross-model placement check

Two models, two DIFFERENT flipped-z compositions, and a sign on one side only —
so a sign error here is invisible in game (the text just hangs on bare
stainless). `check_displays()` bakes the .obj housing and the .bbmodel element
through their **own** transforms and requires the display's centre to land inside
a housing's footprint in world space. 8 placements, all verified.

```
.obj       unflipped(Z): z -> z + Z, x kept    flipped(Z): z -> -z - Z, x -> -x
.bbmodel   unflipped(Z): z -> z + Z, x kept    flipped(Z): z -> -z + Z, x -> -x
```

Hence every `bb*` definition carries the **negated z** of its .obj twin.

---

## 7. Art

Palette in `r62_art.py`. **The R62 is ONE material** — unpainted 301 stainless —
so the palette is a tonal ladder, not a set of hues.

- **BARE BRUSHED STAINLESS, end to end. There is NO black window band** — that is
  an R142/R143-and-later thing — and no painted livery of any kind. The only dark
  things on the car are the sign boxes, the lamp wells, the under-sill band and
  the underframe.
- ⭐ **TWO polished belt rails, not three.** The donor's inventory lists a
  "belt_rail_upper" at 2.040…2.116 immediately under the glass, and the first cut
  drew it polished like the others. Measured off the donor's own photograph —
  column tones at y 1.905 and 1.471 are bright, the band at 2.08 is not — it is a
  **panel step**, and drawing it bright put a third rail on a car that has two.
- **No modelled corrugation and no tumblehome**: the bodyside is a flat plane and
  every rib, rail and rivet is paint.
- Windows: big rounded rects in thick polished frames with a rivet run and a
  horizontal muntin near the top (the hopper vent).

### ⭐ THE ELEVATION MAY ONLY SHADE VERTICALLY

Every field band runs the full width of the canvas (`hband`), so every column is
bit-identical and a bay cropped out of the middle tiles at any pitch. Content
that varies along the car (a window, the sign housing, a door header) is
bay-local content, not field shading.

`--check` guards it **on the shipped crops**, in the belt band, for the door bay
— the one that repeats three times. ⭐ **The crop's own edge columns are excluded
and that is not a fudge**: the elevation draws a panel joint on every bay
boundary, so a crop's edges always carry one. That is bay-local content at a
fixed position within the bay; the rule is about the field.

Two more guards on the same family:
- **NO BAY MAY CARRY HALF A WINDOW** (`panes_in`). Checked on the pixels that
  ship: an aperture is an alpha-0 hole, so a hole touching a crop's first or last
  column *is* a window the crop cut. The M7 shipped that bug once.
- Adjacent bays must agree on the **field tone at their shared seam**.

### ⭐ THE BUG THAT COST A RENDER CYCLE: v > 1

The elevation's own bottom edge is donor y **0.9477** (`A.SKIN_BOTTOM_Y`), from
the donor's uv fit. The first cut ran the bodyside down to 0.9386 — 9 mm lower —
so `side_v` returned 1.004. That is not a harmless overshoot: `wrap_face` sees a
face spanning more than one repeat, falls back to per-vertex wrapping, and 1.004
becomes 0.004 — **so the whole car sampled a single row of the cant rail and
rendered as featureless grey**, while looking entirely plausible. Anything below
that line is underframe, drawn by its own geometry.

### Where the raster kit lives

`tools/pixel_kit.py` — the **car-agnostic** primitives, the 3×5 font, and the
whole MARK / FIND / CUT glazing mechanism, shared by `m7_art.py`, `r62_art.py`
and the four generators (which import it as `K`). Each car's art module keeps
only what is its own: palette, livery tables, motifs. `pixel_kit.glaze` takes
the tint explicitly — the kit has no palette, so a car passes its own
`GLASS_RGBA`.

(History: the kit originally lived inside `m7_art.py`, and this module first
imported it from there rather than extract it mid-session while the M7 awaited
its in-game verdict. The planned rename was done on 2026-07-29, verified
byte-identical on every shipped asset.)

---

## 8. The shared vehicle index (refactor, affects the M7)

`mtr_custom_resources.json` is a **single file** holding every custom train the
mod ships, so it cannot be owned by one train's generator. It was — and the first
run of `gen_r62_assets.py` would have replaced eight M7 vehicles with two R62s,
silently, with the M7's properties still on disk pointing at nothing.

- `tools/gen_vehicle_index.py` composes it from each train's `vehicles()`
  provider and **refuses to write duplicate ids**.
- `gen_m7_assets.py` and `gen_r62_assets.py` each still write their OWN
  properties and definitions, expose `vehicles()`, and call the composer at the
  end of `main()` — so running either alone still leaves a correct index.
- Adding a train is one line in `PROVIDERS`.

⭐ **One knock-on, already fixed:** `convert_openbve_m7.py --check` read the whole
index and pulled the R62's door groups into its own door-sign table, reporting
nine false failures about a rule that is the M7's and not the R62's. Its
`index_model_entries()` now filters to `m7*` vehicles, exactly as the R62's
filters to `r62*`. **Nothing either file writes changed** — the M7's properties,
definitions, model and textures are byte-identical, and both its checks are at
0 problems.

---

## 9. The two vehicles

| id | name | ends |
|---|---|---|
| `r62` | NYCT R62 | cab at BOTH — the real car. An R62 is a single unit |
| `r62_middle` | NYCT R62 (Blind End) | plain stainless end with the storm door: no windshield, no roundel, no lamps |

⭐ **`r62_middle` is a deliberate fiction and no such car existed.** It is here
because MTR consists are built from whatever vehicles you pick, and a ten-car
train of double-ended cabs shows a cab face at every coupling. The shell is
IDENTICAL — same nose, same anticlimber, same storm door — so it costs one extra
texture and one extra group, not a second model.

Both: `hasGangway` **false**, `hasBarrier` true (an R62 couples through open end
platforms with folding safety gates, exactly as MTR's own r179 is configured);
`couplingPadding` 0 (the anticlimbers ARE the coupler faces and interleave — the
donor's consist spacing, 15.3 m, is shorter than the body's 15.56 m for that
reason); `doors:6`; length 16.

### Sounds are borrowed, temporarily

`legacySpeedSoundBaseResource: "mtr:r179"`, count 66, door base the same, close
time 1 — copied verbatim from MTR's own `r179_trailer` entry, only namespaced.
The R62's real propulsion is **GE SCM camshaft**, a completely different noise
from the R179's AC drive, and the donor ships 60 wav files that are **analysis
reference only**. A synthesized R62 set is a later pass, the way
`gen_m7_sounds.py` did the M7's.

---

## 10. Not yet verified in game — the checklist

Everything below is render-verified offline only. Renders in the session
scratchpads under `r62_ext/final/` (exterior) and `r62_int/final/` (interior).

### The shell

1. ⭐ **The front roundel's `rotation: [0, 180, 0]`** (§6). Does the bullet
   appear, right way up, on the correct side of the nose? If the glyph is
   mirrored, the yaw is wrong — replace the ROUTE_NUMBER plate with the disc
   alone, or move the roundel onto a ∓90 side mounting.
2. **The side rollsign.** Two per car, diagonally opposite, bullet toward the car
   centre. Does a long destination marquee rather than truncate? Does the
   ROUTE_NUMBER top line carry anything on your routes, or is `routeNumber`
   empty on them (in which case swap it for DESTINATION and say so here)?
3. **Doors.** Six openings, bi-parting, unequal leaves meeting half a unit off
   centre, each retracting into the body. Only the platform side should open.
4. **The belt rails across a closed door** — the single thing the shared palette
   exists for. Any step at an opening means the leaf art and the bodyside have
   drifted apart.
5. **The nose.** No facet reading as a wedge, no shelf across the top, the
   storm-door recess reading as a recess.
6. **`r62_middle`.** Does a consist of cab + middles look right, and do the blind
   ends read as deliberate plating rather than as missing art?
7. **Roof vents** — four, one per corner, hugging the dome.
8. **Scale next to an M7 and next to MTR's r179.** The cross-section was derived
   from r179 and lands within 0.2% of the M7's; that is the claim to check.

### The interior (all new on 2026-07-29 — §12)

9. ⭐ **The side rollsign, again, and FIRST.** Its four display plates were
   mis-placed in the shipped model and are fixed (§6): the bullet was 4.5 units
   inside the car and the two text lines 2.5 units proud of the box. **If the
   sign still reads wrong, the yaw decode is wrong and everything else in this
   list that mentions a display is suspect** — including the strip map, which
   is placed by the same three lines.
10. ⭐ **The strip map.** Four per car — one on each side of each panel bay,
    high on the ad band. Painted map with ticks and station rings on the right,
    two dark wells on the left. **Does the route bullet appear in the small
    well and the next-station name in the long one?** Both are on a yaw of
    ±90; a mirrored glyph means that decode is wrong. The strip is blank with
    no route assigned, on purpose — that is not a fault.
11. **The next-station text.** 18×3 units, scrolling. Does a long name marquee
    rather than truncate, and is it legible at standing height? If it is
    unreadable the fix is `MAP_TEXT_UNITS` / the band's 3-unit height in
    `r62_layout`, and the painted art re-proportions itself.
12. **The saloon overall.** Cream lining and ceiling, INDIVIDUAL bucket seats
    alternating orange / red-orange / amber one seat at a time, dark speckled
    floor, dark recessed cabinet under every bench, poles floor-to-rail, a lit
    cove strip both sides. Does it read as an R62 or as a generic tube?
12a. ⭐ **THE SEATS THEMSELVES** (rebuilt 2026-07-30 — the thing the user asked
    for). Eight buckets a bay a side, each with a leaning back, a dipped pan
    and a raised divider rib beside it. **Do they read as seats from standing
    height in the aisle, which is where a player is?** If the ribs are too
    pronounced, `RIB_PROUD_UNITS` / `RIB_PAN_UNITS` are the two dials — a
    0.6/0.5 candidate was rendered and rejected as too subtle, so the fork is
    known and the milder answer is one edit away.
12b. **The chrome end grab bar.** A bent tube capping each bench end with the
    stanchion rising out of its corner to the overhead rail. **Nothing may
    z-fight where the three meet** — that junction is three different gauges on
    one centreline and it is the whole reason they are three.
13. **The closed door from INSIDE.** Its saloon face was repainted from grey
    stainless to the lining's cream when the interior landed. A step in tone
    at a closed doorway means the leaf art and the lining have drifted — the
    inside-the-car version of check 4.
14. **The windows, both ways.** From a platform: seats and the far lining, not
    a flat panel. From a seat: one layer of tint, not two. And **no gash
    around a window** — the lining's aperture is inset inside the shell's so
    the wall cavity (with the door pocket in it) cannot show.
15. **The doorway from inside.** No slot of daylight around the opening: the
    reveal closes the 47 mm between lining and leaf, and it stops on the
    reserved corridor. **Open the doors and watch that nothing is sliced.**
16. **The cab through the windshield.** Unlike the M7's, this one is visible
    from the platform. Expect a dark room with a console, two instrument
    blocks and a seat — a silhouette, not a cab. If it reads as an empty black
    box, the console needs moving forward, not more detail.
17. **`r62_middle` inside.** A blind end should have lining, a second bench and
    no cab bulkhead. Walk from a cab car into a middle one.
18. **The seat colour phase** (§12). All three tones appear in every bay and the
    alternation is intact; the far half of the car is out of phase with the
    donor. Only worth reporting if it actually reads as a mistake.
19. ⭐ **THE HALF-CAB IS ON THE WINDSCREEN'S SIDE** (2026-07-30). Stand on the
    platform at a cab end: the windscreen is on your LEFT and there must be a
    driver's console behind THAT glass, not behind the blank panel opposite.
    Checked numerically off the emitted files (§13), never yet in game.

### Known and accepted

- No end gates. The donor models the folding safety gates across the end
  platform (builders 13–18); MTR draws its own barrier there (`hasBarrier`), so
  they were left out of this pass. Cheap to add later if the barrier reads badly.
- The blind end's two blank panels are plain. A rivet or bolt line would sell
  them; not done.
- **No MTR `SEAT` parts.** The benches are geometry only, so riders do not sit
  on them. `ModelPropertiesPart$1.$SwitchMap` maps NORMAL / DISPLAY / FLOOR /
  DOORWAY and **SEAT is unmapped in both `writeCache` overloads** — it does
  nothing on a .bbmodel either, so there is nowhere to put one that would work.
- **No interior door-leaf window glass.** The leaf's window is a hole through
  both of its planes (a .bbmodel has nowhere to put a translucent flag), so from
  inside a closed door you see straight out. The shell's own glazing is not in
  front of it, because the leaf sits inboard of the skin.
- **The saloon has no lighting geometry** — INTERIOR renders fullbright, so the
  painted cove strip and a modelled housing are the same brightness in game.

---

## 11. Rig hazard (from CLAUDE.md, still true)

Background agents running heavy Java work SIGKILL the rig's Gradle wrappers and
sometimes its JVMs. **Nothing in this pass touched Gradle** — the whole pipeline
is pure python and needs no build to verify. Keep it that way.

---

## 12. The interior (2026-07-29; seats rebuilt and flipped 2026-07-30)

`tools/convert_r62_interior.py` → `r62_interior.obj` + one 1024×512 atlas.
**4 groups, 670 faces, 1,340 triangles, ONE material** for a whole saloon; the
whole thing is 17 KB of PNG and 55 KB of OBJ. Its own `--check` is at 0
problems and its module docstring is the reference — what is below is only what
a later pass has to KNOW.

⚠️ **THE 2026-07-30 PASS CHANGED TWO THINGS AND BOTH ARE LOAD-BEARING**: the
bench is now individual moulded buckets (below), and the model adopts the
shell's handedness flip (`r62_layout.emit_x` + a reversed face order in
`write_obj`) so the half-cab is behind the windscreen again. §13's "THE FLIP'S
KNOCK-ON IS NOT FINISHED" is closed.

### The donor, and which of its five interiors

`Cars/R62 Exterior/Interior/InteriorB.b3d` — 57 builders, 13,496 vertices, 51
photographic textures. The pack ships five and they are not variants of detail:

| file | cab arrangement | seats |
|---|---|---|
| `InteriorA` / `A1` | **FullCab** one end, HalfCab the other — the LEAD cars | bucket |
| **`InteriorB`** | **HalfCab at BOTH ends, diagonally opposite** | **bucket** |
| `InteriorC` | as B | flat benches |
| `InteriorD1` | as A | flat benches |

Our `r62` has a half-cab at each end (that is what the shell models), so B is
the only one whose cab arrangement matches, and it has the bucket seats the
user's photos show. **If the flat-bench variant is ever wanted it is a
seat-section swap, not a second model.**

⭐ **The donor is NOT parsed at build time** — the same decision as the shell's,
for the same reason. `check_against_donor()` re-parses it on `--check` and
re-measures the lining plane, the floor, the crown, the rack band, the seat
slab pitch and the half-cab's diagonal.

### ⭐ The 666-face bucket seats — and the argument that got them wrong once

Builders 53 (SeatYellow) and 56 (SeatRed) carry **666 faces each**. Measured:

* a longitudinal bench of 0.87 m colour groups, each 6 slabs of **0.145 m**;
* each slab is a 9-quad slice of the moulded bucket contour, and the contour
  varies **±15 mm across a slab** — 0.22 M units, a fifth of a pixel here;
* 3 slabs = one 0.435 m bucket, so a donor colour group is **two seats**.

⭐⭐ **THE FIRST CUT PAINTED THE BUCKETS AND THE USER REJECTED IT** ("fix the
interior of the r62 to have actual seats"). The reasoning was: a 15 mm scallop
is a fifth of a pixel here, so the bucket cannot be modelled, so paint it. **The
premise is true and the conclusion does not follow.** The 15 mm scallop is not
what a rider reads as "a seat" — what he reads is the **step from one bucket's
back to the divider beside it, 270 mm, four whole M units**, one of the largest
features in the saloon. The first cut resolved the feature nobody can see and
spent nothing on the one everybody does, and a painted scallop on a flat slab
reads as a poster of a bench. **Do not re-run that argument on the R143.**

⭐⭐⭐ **AND THE SLAB HAD ITS CUSHION FACING THE FLOOR.** `sweep()` picks each
segment's winding by pointing it away from ONE interior `solid_point`, and no
single point is inside an L-shaped section: the point that winds the seat BACK
correctly sits above the cushion, so the cushion came out inverted. MTR culls
backfaces unconditionally, so half the bench was simply not drawn — which is
most of why it read flat. **Every check was green**: the saloon-viewpoint test
passed it because the low "child on the floor" viewpoint really can see a
downward face. `sweep` now takes `solid_point=None`, meaning "the outward
direction is the walk turned −90°", which is correct segment by segment for any
section walked consistently around the material. Every seat section uses it.

### The bucket as it is now built (2026-07-30)

Per seat, **7 nominal M units** of car, in the MTR idiom — stepped boxes, no
curves. All of it in `bucket_section()` / `seat_rib()` / `bench_run()`:

| part | what it is | quads/side |
|---|---|---|
| shelf | the strip of the old flat top that survives, back to the lining | 1 |
| back | ONE leaf, leaning **1.5 units** wallward over its 7-unit height (≈11°) | 1 |
| pan | tilted, **dipping 0.6 units** from the nose to the back | 1 |
| nose | the cushion's front roll | 1 |
| **rib** | a divider **1 unit wide, 1 unit proud, 1 unit above the seat top**, at every bucket boundary | 9 |
| soffit | ONE quad for the whole bench run | 1/run |

⭐ **THE RIB IS TWO PRISMS, NOT ONE, AND THAT IS FORCED.** A seat's
cross-section is an L; an L is not convex; MTR fan-triangulates an .obj polygon
from vertex 0 (M7_CONVERSION_NOTES) so a concave end cap folds over itself. The
back prism and the pan prism split at `RIB_SPLIT_X`, and their end caps
therefore only **touch** there — which is also what keeps `coplanar_overlaps()`
quiet. The pan prism omits its own underside because the run-long soffit
already covers it; drawing it would z-fight the one plane two parts share.

⭐ **THE PITCH IS FITTED, NOT TILED.** A bench must reach both of its own ends
exactly (short of the cab bulkhead shows lining; past it is inside the cab), so
`bench_run` takes the nearest whole bucket count and divides. The car's three
runs land at **6.75 / 6.55 / 6.40** units against a nominal 7 — under half a
texel each. The first and last bucket of a run are half a rib longer, because
there is no rib at a run's end and an inset bucket would leave a slot.

⭐ **THE COLOUR ALTERNATES PER SEAT, IN THREE** — orange / red-orange / amber.
The donor changes colour every **two** seats and `check_against_donor` still
measures that, because it is a true fact about the donor; it is not what the
user's photographs of the real car show. Three rather than two because two
tones this close read as one colour with a seam. The **phase is still mirrored
about the car centre, not continued** — a flipped panel bay lands a different
tone where the donor has another. Do not "fix" it: fixing it means giving up
the rotational symmetry the whole model is built on.

### The chrome end grab bar, and the third gauge

Each bench's doorway end carries a **bent tube** — from the lining along the top
of the seat back, down past the cushion's nose, and back to the lining over the
cushion — plus a plain stainless plate behind it and the **cabinet's dark**
below. Three boxes; the two horizontal runs omit the end that butts the
vertical one, so only ONE face lands in the plane where they meet.

⭐ **ITS GAUGE IS 1.4 UNITS AGAINST THE RAIL'S 1.2 AND THE STANCHION'S 1.0, and
all three are centred on `POLE_AISLE_X`.** Three different gauges on one
centreline is what lets the aisle stanchion — which now stands **at the bench
end**, out of the bar's own corner — run up through the bar and on through the
overhead rail without a single shared plane. Same burial rule as rail-vs-pole,
applied to a third member. The bar's inboard end is buried 0.2 units **past**
the lining (still well inside `LINING_MAX_X_M`), because a tube stopping at the
wall shows an end cap no viewpoint in the saloon can see.

Two knock-on decisions the same pass forced:

* **the end plate stops at the bench soffit**, not the floor. Fourteen units of
  stainless at every bench end was the brightest thing in the saloon and read
  from the doorway as a locker door; the void under the cantilever is closed in
  the cabinet's dark instead. Its texture is cut to the panel that ships.
* **the under-seat duct is DARK** (`DUCT_FACE`/`DUCT_LIP`/`DUCT_LOUVRE`, local
  to the converter). It used to be painted as more cream wall, which put a light
  surface in the one place on the car that is always in shadow.

Three tones and the cabinet colours are defined **in `convert_r62_interior.py`,
not `r62_art`**: that module's interior palette exists so `gen_r62_doors.py` can
paint the saloon face of a sliding leaf, and a door leaf has no seat on it.

### The rest of the simplification list, with the measurement behind each

* **Poles are 1 M unit SQUARE.** The donor spends 1,500 faces on cylinders
  70 mm across; 70 mm is 1.04 M units, and a cylinder one unit across is a
  square with wasted vertices. MTR's canonical 0.4-unit inflated box was
  measured and rejected on the M7 as "a scratch" at 2.5 cm.
* ⭐ **The overhead rail is 1.2 units where the stanchion is 1.0**, and that is
  MTR's burial rule rather than a proportion: at the same gauge the two share
  their x planes exactly and every pole/rail junction is a z-fight.
  `coplanar_overlaps()` reported it the moment it existed.
* **No window reveal.** The lining is at |x| 1.193 and the reserved corridor
  starts at 1.2299: 37 mm, half an M unit. Same conclusion the M7 reached from
  the other direction.
* ⭐ **ONE ceiling profile for the whole car.** The donor drops its ceiling from
  the 3.323 crown to a flat 3.157 outboard of |z| 3.633, so its ad rack covers
  only the middle 7.3 m. **A bay-repeating model cannot carry a feature that
  stops mid-bay**, and a continuous rack is what lets the strip map and the
  lighting run the length of the car.
* **The fluorescent strip is PAINT.** The M7 built its lamps as swept housings,
  but that car had a 1.5-unit cove to hang them in; this one has the ad band
  there. INTERIOR renders CUTOUT_BRIGHT, so painted and modelled are the same
  brightness in game and the only thing a box would add is a silhouette there
  is no room for.
* Dropped entirely: under-seat heater grilles, speaker grilles, the Kawasaki
  builder's plate, the emergency-brake valve, and the donor's photographic
  wall route-map panel (builder 26) — the map moved to the ceiling as a live
  display instead.

### ⭐ THE STRIP MAP — the hybrid, and where its numbers live

The R62's line map lives in the ad/light band above the windows: donor builder
43, `CeilingAdsLight.png`, **two racks — one per side** (5 facets each, measured,
not assumed), sweeping from the crown at |x| 0.843 down to the wall top at 1.193.

It is built as a HYBRID (user decision):

* the **housing and the station ticks are painted** — a route line, tick marks,
  ringed station dots, larger terminals, interchange blobs — and **deliberately
  carry no real station names**, because the stations belong to whoever builds
  the railway;
* a route **bullet** (ROUTE_COLOR_ROUNDED + ROUTE_NUMBER) and a
  **NEXT_STATION strip** are live MTR displays, inset into painted dark wells
  exactly as the exterior rollsign's fields are.

Geometry: the band's face is **1 M unit inboard of the lining and 3 units
tall**; the map board stands **1 unit off that again**, 44 units long in a
58-unit bay. Whole units, because MTR's corpus has no relief between 0.3 and 1.
All of it is in `r62_layout` (`INT_BAND_*`, `MAP_*`, `map_board/bullet/text/
ticks()`), because **`gen_r62_doors.py` has to stand three plates on a housing
`convert_r62_interior.py` paints** — the same split, for the same reason, as the
side rollsign's.

⭐ **TWO ELEMENT SETS, ONE PER SIDE — four maps per car.** `bbPanel`'s flip
turns a +x element into a −x one *at the other end*, so a single set gives two
maps diagonally opposite and a rider on the wrong side of the aisle never sees
one. `_a` is authored on +x with yaw **+90** and `_b` on −x with yaw **−90**;
both take a NEGATIVE authored z, which in both cases means "away from the
surface, in the direction the text faces".

⭐ **`NEXT_STATION` is the right one of MTR's four.** Read out of 4.0.5's
bytecode: its case in `ModelPropertiesPart.formatText` renders the STATION NAME
ALONE — the current station while the doors are open, the next one otherwise —
with no prefix and no interchange furniture. The `_KCR` / `_MTR` / `_UK`
variants wrap it in Hong Kong or London phrasing. Scrolling
(`SINGLE_LINE` + `SCROLL_NORMAL`), because a station name is as long as the
builder made it and the strip is 18 units wide. **The default text is EMPTY on
purpose** — an unlit LED window is what a parked train has, and it is what MTR's
own s700 interior display does. A blank strip in a depot is not a bug.

### The cab is built, and the M7's reason not to does not apply here

⚠️ The M7's cab is invisible from outside because that donor's windscreens are
painted onto an opaque photographic mask. **The R62's are not**: the shell marks
the windshield as glazing and `open_glazing` cuts it to a real alpha-0 aperture
with a translucent pane over it, so a player on the platform looks straight into
the cab. It is therefore built — as a **silhouette**: a bulkhead, the partition
beside the storm-door passage, a console with two instrument blocks, and the
driver's seat. Everything in it is emitted with **both windings**, because it is
seen from in front through the windshield and from behind through the cab door's
own window.

The cab's ceiling is the wall top less 10 mm, not the donor's 3.157: a cab that
stopped 71 mm short would leave a slot over its own bulkhead that a rider can
see through, and closing it at 3.086 exactly would put the cab ceiling in the ad
band's lip plane.

### ⭐ The checks that are new, and what each one caught

| check | what it caught |
|---|---|
| `coplanar_overlaps()` | the pole/rail z-fight at every junction, and a bench end plate buried inside the end wall |
| `atlas_straddles()` | the lining running 62 mm past the elevation's own end, so its u left its atlas cell and landed on another texture |
| the saloon-viewpoint winding test | nothing — but only after `box(skip=…)` stopped emitting pole tops and rail end caps, which it correctly reported as faces MTR draws as nothing |
| `check_displays()` | (clean) — and it is the only thing that can catch a flipped-z sign error between two models |
| the pocket-clearance test | the doorway reveal reaching 5 mm into the reserved corridor |
| the bay-tiling test | a run that does not reach both seams of a REPEATED bay |
| `check_emitted_winding()` **(new 2026-07-30)** | nothing yet — but it is the only check here that applies the game's own transform, and the shell's twin caught a car that would have rendered as NOTHING |
| `check_cab_side()` **(new 2026-07-30)** | the half-cab sitting opposite the windscreen after the shell was flipped and this file was not |

⭐ **`check_emitted_winding` PARSES THE SHIPPED .OBJ, NOT THE M-SPACE MODEL**,
and that distinction is the lesson of 2026-07-30 (§13): the saloon-viewpoint
test audits faces as `quad()` wound them and is blind to everything
`to_obj_vertex` and `write_obj` then do — which is exactly where the handedness
flip lives. **A check that does not apply the game's transform is not a check.**
The shell asks "the roof must face up"; the interior has no roof, so it asks
the fact one floor down — **the ceiling must face DOWN**. Everything horizontal
in the top quarter of the saloon is crown, ad-band lip, map-board soffit or
grab-rail underside and none of it legitimately points at the sky, so a simple
majority is decisive and no per-face table has to be maintained.

`check_cab_side` takes the vertices `int_end_cab` has and `int_end_blind` does
not — those ARE the cab — and requires their x to carry the sign
`r62_layout.WINDSCREEN_X_M` gets through the same `emit_x` the shell uses. It
deliberately READS `emit_x` rather than asserting a side, because turning
`MIRROR_X` off is the documented escape hatch and reverts both models together;
the defect it exists for is one file carrying its own copy of the transform.
**Verified to bite:** over all four combinations of (mirror, face reversal),
the two checks between them pass only the two that are self-consistent, and the
real 2026-07-30 defect — this file hard-coding `-x` while `MIRROR_X` is on —
fails both.

⭐ **THE LINING STOPS WHERE THE BODYSIDE DOES** (donor z ±7.1543), and that is
not a choice: the side elevation both models read spans exactly that, so a
lining quad taken further has no texture under it. Beyond the line the shell is
nose, not side.

⭐ **THE END BAY'S OUTER END IS FREE, ITS INNER SEAM IS NOT.** The bay-tiling
rule applies to the seam against the door bay; the ceiling deliberately stops at
the roof's own leading edge, because a run to the bay edge would poke out
through the bullnose.

### A fix in the shared renderer (`tools/render_obj.py`)

⭐ **`--cull` was culling on the CAMERA-SPACE NORMAL, which is an orthographic
test.** It asks whether a face points at the camera's forward *axis* rather than
at its *position*, so it wrongly dropped every face parallel to the view
direction. Harmless for a car photographed from 20 blocks away; catastrophic for
a camera standing inside one — the `interior` view lost the walls, the ceiling
and the floor of a model whose winding was correct, which is precisely the model
a cull render exists to audit. It now culls on the sign of the projected
triangle's area, which was already being computed two lines later. Verified
against the exterior: the same faces kept from every distant view, plus the ones
it should never have dropped.

### Wiring

Three models per vehicle now, all on the SAME position definitions:

```
shell     r62.obj          r62_common + r62_{cab,blind}_1 + _2
doors     r62_doors.bbmodel  r62_doors  [+ r62_display_1/_2 on a cab end]
interior  r62_interior.obj   r62_interior_common
                             + r62_interior_{cab,blind}_1 + _2
```

The interior rides `panel` / `door` / `end1` / `end2` — the .obj definitions,
unchanged — because it is an .obj and composes a flip the same way the shell
does. `int_end_cab` and `int_end_blind` are two GROUPS, not two dressings: a
blind end has no cab bulkhead, a second bench, and lining to the end wall.

⭐ **Interior textures live in their own directory** (`textures/vehicle/r62/
interior/`) and that is load-bearing: `convert_openbve_r62.py` PRUNES every .png
in `textures/vehicle/r62` that it did not itself write, and it lists the
directory rather than walking it.

---

## 13. The exterior fix pass (2026-07-30) — the front, and three blind checks

The user's in-game screenshots plus two real-R62 reference photos produced a
six-item punch list. All six are done. What follows is only what a later pass
has to KNOW; the diagnosis of each is in the docstring of the function that
carries the fix.

### ⭐ LAW: `PartCondition` DOES work on a NORMAL part of an .obj

Verified in MTR 4.0.5 bytecode, both render paths:
`ModelPropertiesPart.lambda$addObjModelPosition$46` keys the baked
`ObjModelWrapper` map by `this.condition` **before** `RenderStage`;
`VehicleResource.lambda$queue$23` gates each condition bucket through
`matchesCondition` on the way to `MainRenderer`; and the un-optimised
`ModelPropertiesPart.render` tests it in its first four instructions.

**So lamps do NOT have to move to the doors `.bbmodel`.** They stay in the
`.obj` as their own groups (`headlights` / `tail_lights`) with
`ON_ROUTE_FORWARDS` / `ON_ROUTE_BACKWARDS` / `AT_DEPOT`, which is the r179
idiom. This is the answer to the question the brief asked to settle, and it
means the DISPLAY restriction (§6 — no DISPLAY part renders on an .obj) is
specific to DISPLAY and does not generalise to conditions.

### ⭐⭐ THREE CHECKS WERE GREEN ON A CAR THAT WOULD HAVE RENDERED AS NOTHING

The handedness flip (`r62_layout.MIRROR_X`) was landed with `emit_x` written
the wrong way round:

```
    return -units if MIRROR_X else units      # what shipped
    return units if MIRROR_X else -units      # what it means
```

ON therefore produced *the old geometry plus a face reversal it does not need*
— a car that is **not mirrored at all and is inside out** — and OFF produced
mirrored geometry with no reversal, also inside out. **Both settings were
broken.** MTR culls backfaces unconditionally, so this ships as a train that
draws nothing, and offline it looks like an X-ray: the near end vanishes and
you see the far end from inside.

Nothing reported it, because **every winding and placement check in this file
runs on the M-space model and never sees the emit stage**:

| check | why it was blind |
|---|---|
| `check_winding` | audits `model` — the faces as `quad()` wound them, before `to_obj_vertex` and before `write_obj`'s reversal |
| `check_displays` | bakes the housing from M-space vertices, so it assumes emit leaves x alone. With emit negating x, the rollsign housing and its four display plates sat on **opposite sides of the car** and it reported twelve happy placements |
| the "corner" rule | `r62_layout` has claimed since the corner fix that *"`--check` proves no face carrying the mask reaches past this"*. **It did not. No such check existed** |

⭐ **THE GENERALISED LESSON, which is the display-rotation lesson again:** a
check that does not apply the transform the game applies is not a check. §6
wrote it about rotation; it is just as true of the emit stage. Three new
guards close it, and all three are deliberately **file-to-file** — they parse
what actually ships rather than re-deriving it:

* `check_emitted_winding` — parses `r62.obj` and requires the roof to face up.
  Verified to bite: re-inverting `emit_x` reports *"169 of 173 roof faces point
  DOWN"*.
* `check_emitted_sign_side` — compares the sign housing's x in `r62.obj`
  against the side display's x in `r62_doors.bbmodel`. Same sign, or the two
  generators disagree about handedness. **This is the only thing in the build
  that can see both emit conventions at once.**
* `check_corner_carries_no_mask` — the rule §5's `CORNER_X` comment always
  claimed. It found a real leak on its first run (below).

### The six items

1. **Roof vents** — 1 unit proud on a footprint measured off `Roof.png`,
   following the roof curve. The donor models no vent at all, so every
   millimetre is invention and the first cut invented too much.
2. **Working front lights + the duplicated lamp art.** Conditions as above.
   ⭐ **The duplicates were never duplicated geometry.** `window_bezel` and
   `lamp_housing` gave every band, return and collar facet the **full-texture
   uv [0,1]×[0,1]** on the end mask — and a collar facet is about two pixels
   wide, so each of the eight reproduced the WHOLE front of the car, both
   windows and all four lamp wells, in miniature. Eight tiny masks ringing
   every lamp is exactly "the lens plus two spurious dark copies". Both now
   sample the mask **positionally** (`face_uv`), so a bezel reads as the
   stainless it is cut from and the relief does the work.
3. **The end-corner curves.** Fixed earlier by giving them their own
   content-free `corner_strip`; the missing *check* is now there, and it
   immediately caught the windshield bezel's outboard jamb reaching donor
   |x| 1.2113 against a `CORNER_X` of 1.1930 — 18 mm onto the curve, where
   `mask_u` leaves [0,1] and `wrap_face` smears the whole texture. The bezel
   is clamped to `CORNER_X`.
4. **The beak** — gone. `NOSE_BROW` runs the flat face on past the eaves and
   `FILLET_PROFILE` rolls it, instead of a straight chord meeting a vertical
   face in a crease.
5. **The front is flipped** and now reads, head-on, **windshield LEFT, storm
   door CENTRE, roundel RIGHT** — the prototype and the donor's own
   photograph. It was `emit_x` that was wrong, so items 5 and the inside-out
   bug had one cause and one fix.
6. **Front depth.** ⭐ **The storm-door recess was SEALED and the reason is a
   trap worth remembering:** `nose_xs()` unions NOSE_PLAN's knots with
   ROOF_PROFILE's, and the roof is sampled from the CENTRELINE out — so it
   contributes x 0.0 and 0.2346, two facets per side lying wholly inside the
   storm-door opening. Drawn, they plate the pocket at the nose plane: the
   deepest relief on the car flattens, and the safety chains vanish behind it.
   **Only the window showed through**, because the glazing mechanism cuts that
   as a real alpha hole through whatever is in front of it — a partial symptom
   that reads as an art bug rather than a geometry bug.
   `check_storm_door_recess` is the guard.

### Open judgement calls — ASK, do not guess (§14 of the brief)

* **Chains.** Built as three chevrons slung between the storm-door frame posts
  with a keeper strap, which is what the donor's `Chain.png` billboard draws.
  The user's photo was described as **two diagonal runs per side forming a V
  toward the anticlimber** — a different arrangement. Count, thickness
  (`CHAIN_THICK_UNITS`, currently 1.0) and sag are all taste calls.
* **Door-pocket depth** is the donor's own 248 mm. Untouched.
* **Buffer size** (`BUFFER_RADIUS_M` 0.085, `BUFFER_PROUD_M` 0.100) is pure
  invention — the donor has no buffers.

### ✅ THE FLIP'S KNOCK-ON — CLOSED (2026-07-30, same day)

This section recorded that the flip was applied in ONE emitter of three, so the
half-cab interior sat on the opposite side from the windshield. **All three now
agree**, measured on the files that ship:

| model | emit | flipped? |
|---|---|---|
| `r62.obj` | `emit_x`, x kept, faces reversed | **yes** |
| `r62_doors.bbmodel` | x as authored | **yes** (`check_emitted_sign_side`) |
| `r62_interior.obj` | `emit_x`, x kept, faces reversed | **yes** (`check_cab_side`) |

`convert_r62_interior.to_obj_vertex` reads `r62_layout.emit_x` rather than
carrying its own `-x`, and its `write_obj` reverses each face's vertex order on
the same switch. Measured on the emitted files, the shell puts the windscreen
at x **+0.568..+1.028** blocks and the interior puts the whole half-cab at
**+0.386..+1.105**, with the roundel at **−0.997..−0.578** on the other side.
Both new guards are in §12's check table.
