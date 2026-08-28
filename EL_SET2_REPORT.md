# EL item set 2 — roofs & canopies (rework, 2026-08-28)

Scope: `el_canopy_flat[_silver]`, `el_canopy_gable`, `el_canopy_post[_silver]`,
`el_stair_canopy`. Assets are GENERATED — everything here lives in
`tools/gen_el_assets.py` (canopy/gable functions + the canopy blockstate
builders) and `tools/gen_el_phase3.py` (`stair_canopy`), plus
`src/main/java/com/stationannouncer/mtr/ElCanopyBlock.java`. Re-run
`python3 tools/gen_el_assets.py` after any edit; it ends with `verify()`.

Previews: `el_set2_renders/*.png`, rebuilt by
`python3 el_set2_renders/render_scenes.py`. That script does **not** hand-pick
models — it reads the generated blockstate JSON and evaluates the multipart
selectors against the connection state each cell would really have, so a wrong
`when` shows up in the picture. `tools/render_blockmodel.py` is the offline
model renderer (now with a per-pixel depth buffer; the old centroid painter's
sort drew under-deck rafters ON TOP of the deck and invented seams that were
not in the model).

---

## 1. The big one — the gable now covers a real platform

**Before:** `el_canopy_gable` was one block wide forever. Crosswise neighbours
were connected in the blockstate but changed nothing, so a 3-deep platform got
three parallel ribbon awnings with a stepped ridge line
(`el_set2_renders` git history / the "before" shape is visible in the
1-wide render).

**Now:** the whole family is **one continuous 22.5° roof**, and each cell draws
the stretch of that profile its own crosswise connections imply. Crosswise =
the two sides square to `AXIS` (for `axis=x` that is north/south).

| crosswise neighbours | profile | heights (sheet centre line, px) |
|---|---|---|
| none | the classic single-row 45° gable | eave 1.8 at each block edge, ridge 9.8, eaves overhang 1.6 px |
| one | **WING**: 22.5° rising from its open eave to the shared boundary | eave 3.17 (overhanging), boundary 9.8 |
| two | **CROWN**: 22.5° from both boundaries up to a ridge on the cell's centre line | boundary 9.8, ridge 13.11 |

Every profile hands its neighbour the **same 9.8 boundary height**, which is
what makes the family closed: nothing can gap or step, in any combination.

- 1 deep → the approved single-row gable, unchanged in character.
- 2 deep → the two wings meet in a ridge **on their shared boundary**.
- 3 deep → wing / crown / wing is ONE roof: 22.5° from the eave, straight
  through the boundary, to the ridge over the middle row, eaves overhanging
  both platform edges. This is the Marcy Av read and the target of the task
  (`gable_3wide.png`, `gable_3wide_end.png`, `gable_3wide_eye.png`).
- 4 deep → wing / crown / crown / wing: an M-roof (two ridges with a valley),
  which is a real wide-shed form; 5+ repeats the crown bay. Nothing breaks,
  and 1–3 (the realistic platform depths) are the good cases.
- The run ends close with an **open truss that matches the cell's profile**
  (45° triangle / sloped wing truss / shallow crown truss), so the end of a
  3-wide roof is one big truss across all three cells
  (`gable_3wide_axis_z.png`).

Models added: `el_canopy_gable_slope`, `el_canopy_gable_crown`,
`el_canopy_gable_end_crown`, `el_canopy_gable_end_slope`,
`el_canopy_gable_end_slope_east` (a wing truss is not z-symmetric, so the two
run ends need the two authored x slices; the 180° turn covers the mirrored
wing). `el_canopy_gable_roof` and `el_canopy_gable_end` were re-derived onto
the new numbers and gained the eave overhang. 32 states × 28 selectors — a
rounding error next to the pipe-era budgets.

**Blockstate contract, machine-checked:** for all 32 states × both axes,
exactly ONE roof model applies and exactly one truss per open run end, and no
part applies twice (scripted enumeration over the generated JSON — the check
lives in the session scratchpad; re-derivable in ten lines from
`el_set2_renders/render_scenes.py:parts_for`).

### Families no longer merge (Java)

`ElCanopyBlock.joins()` used to accept **any** `ElCanopyBlock`. A flat canopy
beside a gable therefore told the gable "you have a crosswise neighbour", and
the gable grew a wing rising to 9.8 against a deck at 1.4–3.4 — a step, or a
hole. Connections now require the same **family** (`FLAT` / `GABLE`) *and* the
same **AXIS**:

- flat + flat(_silver) merge (paints may mix in one roof);
- flat | gable meet as two roofs, each keeping its own fascia / eave / truss
  (`gable_meets_flat.png`);
- a canopy laid **across** another (different AXIS) is a second roof too —
  merging those would ask a cell to be the wing of a ridge running the wrong
  way, which no profile can satisfy.

The family is derived from the constructor's `height` (flat 3.4 / gable 4.6)
so `MtrStationDecor` needs no edit; a 3-arg constructor takes the family
explicitly and is the one a future registration should use (proposal P1).

---

## 2. Flat canopy

- **Fascia was a parapet.** The girder ran y0..5.6 against a deck of
  y1.4..3.4, i.e. 2.2 px standing PROUD of the roof: the canopy read as a
  tray. It now hangs (y0..3.2, inset 0.3 off the boundary so its outer face is
  never coplanar with the deck's own edge face; top face omitted, buried in the
  deck) and reads as an edge girder under the eave.
- **Under-ribs rebuilt as real structure.** Three x-running ribs at z 2.5/7.2/
  13.5 gave gaps of 3.1 / 4.7 inside a cell against 3.4 across a joint — an
  uneven rhythm in a field. Now two **crosswise rafters** on a pitch of 8
  (which divides 16, so the rhythm is identical inside a cell and across every
  joint — the zebra-board lesson) plus the longitudinal **centre purlin** kept
  at x/z 7..9 so the NYC PIDS' 2 px ceiling stub still lands on steel.
- **A 3×5 field is one unbroken sheet** (`flat_field_3x5.png`,
  `flat_field_3x5_under.png`). Interior boundaries are back-to-back faces with
  opposite normals — vanilla back-face culling draws exactly one, so they
  cannot z-fight; the audit below reports 0 same-facing coplanar pairs for a
  tiled pair of cells. Fascia count == open side count for all 32 states
  (scripted).

---

## 3. Canopy posts

The old bracket was a single 2.4 px box per side stopping at y15.8 — a nub
with a 0.2 px daylight gap under the canopy. Now **three stepped 45° struts
per side** (the girder knee-brace recipe): heads fanning out to x ≈ 1.7 / 3.9 /
5.9, feet all landing on the post shaft, every strut topping out at **y16.0
exactly**, so the gusset visibly carries the roof and touches it
(`post_bracket.png`, and the gussets under the decks in the 3×5 renders). The
three struts' z spans are nested (6.7 / 6.8 / 6.9) so their side faces never
share a plane where they overlap — the flaw the girder braces still have.

---

## 4. Stair canopy (phase 3)

The 45° chaining and the ascent convention are **correct** — verified, no Java
change (see §5). It was however a bare 2.2 px slab with two edge strips. Now
detailed to match the platform canopies: same standing-seam sheet, eave boards
that hang BELOW the sheet instead of standing proud of it (the flat-canopy
fascia lesson), and crosswise rafters on the same pitch of 8. The z-end faces
stay omitted, so a flight is one unbroken plane
(`stair_canopy_run.png`, `stair_canopy_under.png` — two lanes side by side).

---

## 5. Orientation audit (per block, read from placement + blockstate + model)

| block | property | where it comes from | what it means in the model |
|---|---|---|---|
| `el_canopy_flat[_silver]` | `AXIS` | `getHorizontalPlayerFacing().getAxis()` — the look axis | the RUN. Rafters lie crosswise to it; the centre purlin runs along it. `axis=z` applies the authored model at `y:90`. |
| | N/S/E/W | same-family, same-axis canopy neighbours | fascia girder on every side that is **false** |
| `el_canopy_gable` | `AXIS` | the look axis | the RIDGE direction. **Place it looking ALONG the platform** and the ridge runs away from you, wings spread to the platform edges. |
| | N/S/E/W | same-family, same-axis neighbours | the crosswise pair picks the profile (§1); the run pair opens/closes the end truss |
| `el_canopy_post[_silver]` | `FACING` | `FacingDecorBlock`: `playerFacing.getOpposite()` | the bracket pair is 180°-symmetric, so only the AXIS reads: **brackets fan along the look axis**, same rule as the canopy. Look the same way as the canopy → gussets run along the run (carrying the eave girder); look across → they carry the wings. Both are legitimate; the family stays consistent because the rule is "the axis you are looking down". |
| | `UP`/`DOWN` | `ColumnBlock`, any column above/below | brackets only on the stack's TOP block (`up=false`) |
| `el_stair_canopy` | `FACING` | `ElSlopeBlock`: `getHorizontalPlayerFacing()` (NOT opposite) | **ASCENT direction = where you are looking.** Verified end to end: the model descends toward +z (a `+45` rotation about x descends toward +z), i.e. it rises toward authored NORTH, and `platform_simple_blockstate` rotates authored-north onto FACING. So it climbs away from the player, matching `el_stair_side`. No fix needed. |

Blockstate y-rotation convention used throughout (repo rule, re-verified
against the renderer): a `y` turn steps north→east→south→west, i.e.
west→north→east→south.

---

## 6. Sizes against reality (1 block = 1 m)

| thing | model | metric | verdict |
|---|---|---|---|
| roof underside (chord bottom) above the platform, canopy on a **2**-post stack | canopy block floor = 2 blocks up | **2.0 m** | too low — a doorway height. Real subway canopies clear 2.5–3 m. |
| the same on a **3**-post stack | | **3.0 m** to the chord, 3.2 m to the wing eave | correct; this is the stack to build (`gable_3wide_eye.png`) |
| sheet thickness | 1.6 px perpendicular | ~10 cm | plausible for sheet + seam |
| flat deck | 2 px + 1.4 px rafters | 12.5 cm + 9 cm | plausible |
| gable rise, 1 row | 8 px over 8 px | 45° | steep for a canopy, but it is the approved 2026-08-28 look, and one row is a shelter rather than a shed |
| gable rise, 2–3 rows | 6.63 px per block | **22.5°** | matches the real Marcy/Bay Pkwy pitch far better |
| ridge above the eave, 3 rows | 3.17 → 13.11 px | 0.62 m over a 3 m span | right for a platform canopy |
| eave overhang | 1.6 px | 10 cm | modest; the eave board tip dips ~1 px below the canopy block, i.e. into the diagonal air block outside the cell's own footprint — deliberate (rafter-tail read), nothing to collide with in a normal build |

---

## 7. Verification done (no game, no Gradle — Python only)

- `python3 tools/gen_el_assets.py` → `verify: el blockstates + models OK`
  (every when-key against the Java properties, every model present, explicit
  uv on every face, rotation angles in ±45/±22.5 only).
- Blockstate enumeration over all 32 states × 2 axes: gable = exactly 1 roof +
  1 truss per open run end, 0 strays; flat = 1 slab + fascia on exactly the
  open sides.
- Same-facing coplanar-face audit (rotations applied, screen-independent) over
  single cells and tiled pairs: **0** pairs for the wing, the crown, wing|crown,
  wing|wing (the 2-row ridge), gable|gable along the run, flat cell, flat|flat,
  post top, and a chained stair canopy. The single-row gable and the crown each
  keep the sheets' apex end faces in one plane with the end truss's chord ends
  — both are fully **buried inside the ridge cap** (the cap was widened to
  ±1.0 px so it swallows them), which is why they are left as they are.
- Renders: 13 scenes under `el_set2_renders/`.

**Not verified:** anything in game. Java is compile-unverified (agents are
forbidden Gradle here) — the diff is one file, uses the same
`getCollisionShape` signature as five other blocks in this tree, and
`instanceof X other` patterns already appear in `AnnouncerNetworking`.

**Migration:** placed canopies keep every property (nothing was added or
removed), but their look changes — a gable that had crosswise neighbours
becomes part of one roof, and paints/axes that used to merge may now separate.
No world data is lost.

---

## 8. Proposals (NOT done — no new block ids were created)

1. **`MtrStationDecor` should use the explicit constructor**:
   `new ElCanopyBlock(settings(), 3.4, Family.FLAT)` /
   `(settings(), 9.0, Family.GABLE)`. The height-derived family is a
   documented convention, not a contract, and the gable's collision height
   (4.6) is still the old number: it is mid-slope, so you currently walk
   through the upper half of your own roof. I left collision alone rather than
   change tested behaviour from out of scope; the OUTLINE is now 14 px so the
   selection box covers the profile.
2. **`el_canopy_gable_silver`** — the galvanized rebuild gable. Every model is
   already parameterised by texture ref; it is a `pm()` loop and a
   registration.
3. **`el_canopy_valley`** (or a `GUTTER` boolean) for the 4+-deep M-roof
   valley: a proper box gutter along the crown|crown boundary would turn the
   only mediocre width into a deliberate detail.
4. **Half-width eave / cantilever piece** so a canopy can overhang a platform
   edge by half a block instead of a whole one.
5. **Glass canopy** (`el_canopy_glass`) for the modern rebuild kit — same
   geometry, cutout glazing between the purlins.
6. A canopy **downpipe** block to land the eave girder onto a post; the
   photos always have one.
