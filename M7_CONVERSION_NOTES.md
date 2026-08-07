# M7 conversion notes — MTR OBJ loader spec + donor inventory

> **2026-07-28 evening — texture era change:** everything below about *slicing donor
> photographs* into textures is historical. All exterior + interior art is now DRAWN
> (MTR-house-style pixel art, `tools/m7_art.py` + the two converters), except the
> underframe set (cofres/plough/grille) and MTR's bogies. The donor still supplies ALL
> geometry, the glazing positions (its alpha-156 marks), and the livery landmark
> measurements — so the donor inventory below remains the geometric ground truth.

Written 2026-07-27 from two deep investigations (bytecode + empirical loader tests, and a
full parse of the openBVE donor). This is the spec `tools/convert_openbve_m7.py` is written
against. Companion to `M7_HANDOFF.md` — read that first for the big picture.

Corrections to M7_HANDOFF.md established here:
- **`HALF_WIDTH = 34` was derived from a wrong baseline.** The R179's ±32 x-extent is its
  interior grab rails, not the body — the r179 BODY is x ±22.19 (±1.387 blocks). M7 body
  half-width should be 22.19 × 10.5/10 ≈ 23.3 bbmodel units = **1.456 blocks**.
- Donor `A.csv` y range is **+0.10..3.95 m** (the −0.90 figure came from brake-hose builders
  measured before their `Translate ,,1.2`).

Empirical test harnesses (run MTR's real loader by reflection — no Gradle needed) live at
`<scratchpad>/objloader/test/{T,T2,T4}.java`; MTR's r179 assets are extracted at
`<scratchpad>/objloader/assets_extract/`; the donor parser + INVENTORY.json + verified
texture slices at `<scratchpad>/donor/` (scratchpad =
`/private/tmp/claude-501/-Users-thomasdemuth-Documents-Station-Announcer-Mod/aa6d2819-d4f4-42e6-8a02-f505d5024e00/scratchpad`).

---

## Part 1 — How MTR 4.0.5 loads .obj vehicle models (verified, much of it empirically)

Call chain: `VehicleModel.createModel()` → extension `.obj|.mqo|.mqoz` →
`ModelResourceLoader.loadModel(modelResource, textureId, flipTextureV, provider)` →
`OptimizedModel$ObjModel.loadModel(objText, mtlFn, texFn, atlas=null, splitByGroups=true,
flipTextureV)` → shaded `de.javagl.obj` at `org.mtr.libraries.de.javagl.obj`. Any other
extension logs `Invalid model!`.

### Materials / textures
- `.mtl` IS parsed; only `map_Kd`, `Kd`, `d` used. `Kd`+`d` → flat RGBA tint
  (`R<<24|G<<16|B<<8|A`). Only ONE `mtllib` line honoured (last wins).
- `map_Kd` path resolution (`CustomResourceTools.getResourceFromSamePath`):
  contains `:` → full namespaced identifier; else same directory as the .obj.
  Path is lower-cased, `[^a-z0-9/._-]`→`_`, extension forced `png`, and **everything after
  the FIRST dot is discarded** — exactly one dot per path, ever. `../` does NOT work.
- **No `.mtl` at all → the `usemtl` name IS the texture path** (MTR's own idiom: its
  `rail.obj` has no mtllib, `usemtl rail`, `rail.png` beside it). `usemtl _` = no texture
  (white).
- Index `textureResource` for a .obj is bound to the magic material name `default.png`
  (exact match) — neither override nor fallback. Keep it pointing at a real PNG anyway
  (stored for DISPLAY parts).
- ⭐ **One .obj CAN use multiple textures**: `ObjSplitting.splitByMaterialGroups` makes one
  mesh per (group × usemtl), each with its own texture + shader. **Per-material textures,
  no atlas needed.** Cost: one draw batch per (texture, shaderType).
- Shader flags in the material name: `usemtl name#FLAG[,flipv=1]` — `exteriortranslucent`
  →TRANSLUCENT, `light`→CUTOUT_GLOWING, `always_on_light`/`lighttranslucent`
  →TRANSLUCENT_GLOWING, `interior`→CUTOUT_BRIGHT, `interior_translucent`/
  `interiortranslucent`→TRANSLUCENT_BRIGHT, else CUTOUT. **Trap:** when an MTL exists the
  lookup uses the FULL name including `#…`, so the MTL needs `newmtl glass#interior_translucent`
  verbatim. A `#` flag beats the part's `renderStage` (override only fires when source
  shader is CUTOUT).

### Part naming
- `g` and `o` both work but **only whichever keyword appears FIRST in the file** — all later
  lines using the other keyword are silently ignored. Use `g` only, one name per line.
- Repeated `g name` merges; `g a b` DUPLICATES geometry into both parts; case preserved and
  matched case-sensitively by `properties/vehicle/*.json` `names`; no `g` → part `default`;
  zero-face groups skipped; empty `g` line drops subsequent faces.

### UVs
- **V is NOT flipped by default** (`v = flipv ? 1−vt.y : vt.y`). `flipTextureV` (per model
  entry in the index, sibling of modelResource) applies `1−v`. UVs 0..1. No `vt` → (0,0).
- ⭐ **IN-GAME FINDING (2026-07-28, cost one rig cycle): MTR samples v in IMAGE convention —
  v=0 is the TOP texture row.** The first M7 build assumed OpenGL bottom-origin and baked
  `1−v`; every texture rendered upside down on an upright car (windows low on the body, cab
  mask inverted — geometry itself was fine, so it *looked* like the whole train was flipped
  until the symmetric roof art gave it away). Donor openBVE UVs are also image-convention:
  **pass v through untouched and leave flipTextureV unset.** A Blender-style bottom-origin
  export is the case that needs `flipTextureV: true`.

### Winding / culling / geometry
- **Backface culled, always** (ENTITY_CUTOUT / ENTITY_TRANSLUCENT_CULL). Single-sided —
  duplicate reversed faces where the back is visible (donor `AddFace2`).
- **CCW = front** (standard OBJ). The pipeline's two 180° rotations are det +1, never
  reverse winding.
- Quads/n-gons fan-triangulated `(0,i+1,i+2)` — convex only.
- `vn` used where present; `generateNormals()` only fills normals that are exactly (0,0,0).
  Omit `vn` → automatic flat face normals (recommended).
- renderStage → shader: EXTERIOR = CUTOUT + world light; INTERIOR = CUTOUT_BRIGHT (full
  bright); INTERIOR_TRANSLUCENT = TRANSLUCENT_BRIGHT (real alpha blend); LIGHT =
  CUTOUT_GLOWING (beacon layer); ALWAYS_ON_LIGHT = TRANSLUCENT_GLOWING, no depth write.
- Partial alpha in an EXTERIOR texture reads opaque — cutout only. Color-keyed donor pixels
  → alpha 0. Real glass → `#interior_translucent` material or INTERIOR_TRANSLUCENT part.

### Scale / axes — the biggest trap
- ⭐ **1 OBJ unit = 1 BLOCK** (bbmodel units are 1/16 block). Mesh vertices go in verbatim;
  every JSON offset (`positions` x/y/z, door multipliers) stays in 1/16-block units and is
  divided by 16 at build; `modelYOffset` is in whole blocks. `obj = bbmodel / 16`.
- ⭐ **OBJ x and z are BOTH negated relative to a .bbmodel** (load-time 180° about X +
  render-time yaw π): author the OBJ as the bbmodel layout rotated 180° about vertical,
  at 1/16 scale. Y is up as usual. Verify signs once in-game with an asymmetric test part.
- No scale/rotate/mirror knobs anywhere in the loader — bake everything.
- `modelYOffset` RAISES the model by that many blocks. r179 uses 1 (bbmodel y=0 = car
  FLOOR, 1 block above path origin). A positive position `y` moves a part DOWN (unused by
  MTR).

### Door animation
- ⭐ **DO NOT PUT DOOR LEAVES IN AN .obj (2026-07-28, cost one rig cycle).** MTR 4.0.5's
  .obj path has two door defects, both read out of the bytecode after they showed in game:
  (1) `ModelPropertiesPart.lambda$writeCache$12` bakes a door part's position offset into
  its `ObjModelWrapper` *and* stores it on the `PartDetails`, and `lambda$renderNormal$25`
  translates by the latter before drawing the former — an animating leaf jumps by its whole
  definition offset (5.5 blocks for the M7); (2) the .obj `writeCache` overload takes no
  floors/doorways sets, so `type: DOORWAY` and `type: FLOOR` parts in an .obj contribute
  NOTHING, `VehicleResource` logs `No floors or doorways found in vehicle models` and
  synthesises one car-length floor plus a 1-block doorway box every block along both sides.
  Both are unexercised upstream — no MTR vehicle ships as .obj. Doors go in a companion
  **.bbmodel** (`tools/gen_m7_doors.py`); the .obj keeps the aperture and reveal.
- Side binding is GEOMETRIC, not a flag: `ModelPropertiesPart.mapDoors` gives every door
  instance the NEAREST `DOORWAY` box (x+y+z closest-distance), and `RenderVehicles` opens
  only the boxes `RenderVehicleHelper.canOpenDoors` accepts. So a leaf modelled on one side
  and placed through exactly one of the two position lists follows that side by itself.
- Multipliers are 1/16-block units. `result = copySign(|curve(|m|, v)|, m * (flipped ? −1 : +1))`
  — multiplier sign picks direction; flipped instances auto-reverse.
- `doorXMultiplier` is only honoured by PLUG_* types — returns 0 for R179/R211/STANDARD/
  CONSTANT/MLR/BOUNCY. Leave unset for sliding doors.
- ⭐ **R179 full-open travel = |doorZMultiplier| − 0.5** (initial jog then slide; constant
  from v≥0.6). STANDARD/CONSTANT travel = m exactly.
- r179 ground truth: each leaf's multiplier ≈ its own width, signed toward its retract
  direction; `door_*_exterior` repeats the same multipliers on EXTERIOR.
- `DynamicVehicleModel.testDoors` samples v=0..1 and logs `Vehicle doors overlapping!` on
  bad multipliers — a clean load is a real check.

### DISPLAY parts — ⭐ THEY DO NOT WORK ON AN .obj (2026-07-28, bytecode-verified)

**Verdict: `type: DISPLAY` on an .obj model renders nothing, logs nothing, and its
geometry is not drawn either.** Same family as the FLOOR/DOORWAY and door defects above —
MTR's .obj path is unexercised upstream. Two independent blockers, either one fatal:

1. `ModelPropertiesPart.lambda$writeCache$13` — the per-position callback of the **.obj**
   `writeCache` overload — begins `if (this.type != PartType.NORMAL) return;`. The bbmodel
   overload (`lambda$writeCache$8`) instead switches on the type
   (`ModelPropertiesPart$1.$SwitchMap`, ordinals 1=NORMAL 2=DISPLAY 3=FLOOR 4=DOORWAY;
   SEAT is unmapped in both) and routes DISPLAY to `lambda$writeCache$3`, **the only
   writer of `displayPartDetailsList` in the jar**. On an .obj that list stays empty, and
   all four display renderers (`renderDisplay`, `renderScrollingDisplay`,
   `renderSevenSegmentDisplay`, `renderLineColor`) end in `displayPartDetailsList
   .forEach(...)` — a silent no-op. `ModelPropertiesPart.render` *is* still called, so
   this is a no-op, not a skipped call. Note the early return also happens before
   `addObjModelPosition`, so the part's **mesh never joins a material group either**.
2. Even without (1) there would be nothing to draw on. `ModelDisplayPart` is constructed
   in exactly one place — `DynamicVehicleModel.lambda$new$1`, reachable only from the
   `DynamicVehicleModel(BlockbenchModel, …)` constructor — and filled by
   `BlockbenchElement.setModelPart` purely from an element's `from`/`to`/`origin`/
   `rotation`. There is no bounding-box fallback and no mesh path. An OBJ mesh has no
   cuboid from/to.

**Correction to Part 1 above:** `textureResource`/`default.png` is **not** "stored for
DISPLAY parts". That Identifier is consumed only in `render`'s NORMAL branch
(`MainRenderer.scheduleRender`); display text uses the MC/MTR font and
`displayColorInt`/`displayColorCjkInt`. Keep `default.png` pointing at a real PNG anyway —
it is the magic material name the OBJ loader binds.

**Consequence for us:** the M7's destination sign lives in `m7_doors.bbmodel` (the
companion model that already exists for the leaves), bound through its own tiny
properties files (`m7_display_1.json` / `_2.json`) stacked in the index's `models` array
only for ends that have a cab. Those files contain **no NORMAL part**, so nothing draws
twice. `--check` now fails any DISPLAY part sourced from an .obj.

#### Display geometry convention (read out of r179 — MTR's whole DISPLAY corpus)

- A display element is a **zero-thickness plate**: `from[2] == to[2]`, `uv_offset: null`,
  `visibility: false`. r179's `front_route_number_display` is `from [-4,35,-26]`,
  `to [4,39,-26]`, origin `[0,0,0]`, no rotation.
- **The text plane is the element's MINIMUM-Z face, the text faces −Z and is read from the
  −Z side.** (`setModelPart` anchors z at `from[2] − origin[2]`; every other anchor
  component uses `to`. `lambda$renderDisplay$39` then pushes the glyphs a further
  −1/320 block.) Our end bays are authored with their outward face at local −z, so a plate
  on the cab mask faces out with no rotation — same as r179's nose.
- `width = Math.round(to[0] − from[0])`, `height = Math.round(to[1] − from[1])`, **in whole
  model pixels, and those integers ARE the text canvas.** A fractional size silently
  rounds the layout, so author the plate to land on integers.
- "Up" is Blockbench +Y. MTR negates X and Y (a 180° roll about Z), which preserves
  upright text. Aiming a display elsewhere means rotating the element — r179's side
  displays are the same −Z plate with `rotation: [0, ∓90, ±6]` about an origin on the car
  side.
- Padding is in model pixels: usable canvas is `(width − 2·displayXPadding)/16` by
  `(height − 2·displayYPadding)/16`.
- ⚠️ **`ALIGN_CENTER` is not a real `DisplayOption`.** `displayOptions` is deserialized as
  raw strings and matched with `contains(DisplayOption.X.toString())`, so MTR's own
  `"ALIGN_CENTER"` falls through to the default alignment — which happens to be centre.
  Harmless; we copy r179 verbatim anyway.
- Enums: `DisplayType` = DESTINATION, ROUTE_NUMBER, DEPARTURE_INDEX, NEXT_STATION,
  NEXT_STATION_KCR, NEXT_STATION_MTR, NEXT_STATION_UK, ROUTE_COLOR, ROUTE_COLOR_ROUNDED.
  `DisplayOption` = NONE, SINGLE_LINE, UPPER_CASE, SPACE_CJK, SCROLL_NORMAL,
  SCROLL_LIGHT_RAIL, SEVEN_SEGMENT, ALIGN_LEFT_CJK, ALIGN_RIGHT_CJK, ALIGN_LEFT,
  ALIGN_RIGHT, ALIGN_TOP, ALIGN_BOTTOM, CYCLE_LANGUAGES.
- Exact JSON keys: `displayXPadding`, `displayYPadding`, `displayColor`,
  `displayColorCjk`, `displayMaxLineHeight`, `displayCjkSizeRatio`, `displayOptions`,
  `displayPadZeros`, `displayType`, `displayDefaultText`, **`flashOnTime`/`flashOffTime`**
  (which gate NORMAL geometry visibility, not display text).
- ⭐ **Scrolling is `["SINGLE_LINE", "SCROLL_NORMAL"]`, and the two go together.** The only
  scrolling display MTR itself ships is `s700_cab_*.json`'s `interior_display_1`, and it
  declares exactly that pair — MTR does not scroll a wrapped multi-line block, so
  `SINGLE_LINE` is not optional. `UPPER_CASE`/`CYCLE_LANGUAGES` are orthogonal and may ride
  along. Our destination uses
  `["UPPER_CASE","CYCLE_LANGUAGES","SINGLE_LINE","SCROLL_NORMAL"]` on a 16×2 plate — a wide,
  short LED strip, which is the shape a marquee wants.
- r179's own head display, verbatim: `type DISPLAY`, `displayType ROUTE_NUMBER`,
  `displayColor FF0000`, `displayXPadding`/`YPadding` 0.25,
  `displayOptions ["ALIGN_CENTER","UPPER_CASE","CYCLE_LANGUAGES"]`, no `renderStage`.
  Its side pair adds `displayType DESTINATION`, `displayColor FF9900`,
  `displayDefaultText "Not In Service"`.

#### Aiming a display at the CAR SIDE — the yaw decode (2026-07-28)

A display's text always faces its element's own −Z, so a side sign has to be
**rotated**, and r179's side pair is the only worked example in the jar:
`side_destination_display_1` = `from [11.5,27.25,0.1]`, `to [27.5,30.25,0.1]`,
`origin [21.5,13,0]`, `rotation [0,-90,6]` (its `_2` twin mirrors both).

- The rotation acts on coordinates **relative to `origin`**, and
  `BlockbenchElement.setModelPart` anchors the display at
  `(−to.x+origin.x, −to.y+origin.y, from.z−origin.z)` — the *same* anchor it
  hands `addCuboid`, so **a display plate occupies exactly the space the
  element's box would have occupied**, and it can be reasoned about in plain
  Blockbench terms.
- Decoding yaw θ (`x' = dx·cosθ + dz·sinθ`, `z' = −dx·sinθ + dz·cosθ`), at
  θ = −90 the authored **X becomes the along-car axis** (so `width`, the text
  canvas, is the sign's LENGTH), the authored **dz becomes −x** (a NEGATIVE dz
  is outboard) and the −Z normal turns into **+X**, i.e. outward on the +x side.
  Checked against r179: its plate lands at x 21.4 spanning z −10..6 — 0.1
  *inboard* of its own wall at 21.5, because its sign is behind a window.
  Ours is on the skin, so ours uses a negative dz.
- ⚠️ **Both offsets are measured from `origin`, so put the along-car centre in
  `from[0]`/`to[0]` and keep `origin[2] = 0`** (r179's arrangement). Writing the
  centre into `origin[2]` instead makes `dz = standoff − centre`, and the plate
  lands that far INSIDE the car — invisible, and nothing is logged. This cost
  the M7 side signs one build; `gen_m7_doors.verify_side_sign()` is the guard.
- r179 also rolls its side plates ±6° to match a 6° tumblehome. The M7's side
  leans 3.1°, which over a 2-unit plate is 0.11 units — less than the standoff —
  so ours stay square, which also avoids Blockbench's Euler-order question
  (there is no second worked example in the jar to settle it).

#### A DISPLAY part obeys the SAME flipped composition as bbmodel geometry

`ModelPropertiesPart.lambda$renderDisplay$41` does `translate(x,y,z)` and THEN
`rotateYDegrees(flipped ? 180 : 0)`, i.e. **world = R·v + t** — the same order as
`lambda$addCube$0`/`lambda$renderNormal$25`, and the opposite of the .obj path.
That is what lets ONE authored side sign serve both sides of the car: put the
element on the +x side and list the same z entries in BOTH `positions` and
`positionsFlipped`, and the flip supplies the −x copy already facing outward
**and** already at the along-car position the shell's own flipped bodyside put
the painted housing at (bbmodel flipped → −z_local + t; obj flipped →
−z_local − t; with a symmetric ± set of door centres the two land on the same
pair). The M7 does this with `bbSideSign`; r179 instead authors a separate
element per side on positions-only definitions — both are valid.

### positionsFlipped
- `positions` → `cb(x, y, z, false)`; `positionsFlipped` → `cb(−x, y, z, true)`, and
  flipped applies a **180° rotation about Y** (a rotation, not a mirror — winding safe).
  Position entries support x/y/z doubles only.
- ⚠️ **bbmodel and OBJ paths compose translate+flip in opposite orders**: for a flipped
  entry, bbmodel lands at z_final = +z/16, OBJ lands at z_final = **−z/16** (and −x/16 → +x).
  The bbmodel half is confirmed in bytecode: `OptimizedModel$MaterialGroup.lambda$addCube$0`
  and `ModelPropertiesPart.lambda$renderNormal$25` both `translate(t)` then
  `rotateYDegrees(180)`, i.e. world = R·v + t, and `ModelPropertiesPart.addBox` agrees.
  Only matters when a flipped entry has non-zero x or z. Mitigations: keep flipped z-sets
  symmetric (r179 does: windows −80/0/80, doors ±40), or ship all four end definition
  variants (`end1`, `end1Flipped`, `end2`, `end2Flipped`) like r179 and pick what looks
  right. **Any use of a non-z-symmetric flipped entry must be verified empirically.**

### r179 structural pattern (copy it)
- Vertical: floors exactly y=0; roof top y=42 (2.625 blk); underside −4; modelYOffset 1.
- Body half-width ±22.19 units (1.387 blk); half-length 160 (10 blk); bogies ∓6 blk.
- **Side parts are modelled for ONE side only** (x>0), with positionsFlipped producing the
  other side. Full-width parts (end, head, roofs, floors) use single-position lists.
- **Cab vs non-cab cars: same model, same definitions — different properties files stacked
  in the index's `models` array**: trailer = common + end_1 + end_2; head car swaps one
  end file for head_1/head_2 (adds head, head_floor, headlights ON_ROUTE_FORWARDS,
  tail_lights inverse + AT_DEPOT, etc.). `PartCondition`: NORMAL, AT_DEPOT,
  ON_ROUTE_FORWARDS/BACKWARDS, DOORS_CLOSED/OPENED, CHRISTMAS_LIGHT_*.
- No MTR vehicle ships as .obj (only rail.obj / rail_siding.obj / teapot.obj; no .mtl in
  the jar at all — the no-MTL usemtl-is-texture idiom is the precedent).

---

## Part 2 — Donor inventory (openBVE 2-Car M7 pack)

Frame: +x = right, y up from rail, **+z = cab end**. A.csv: x ±1.57, y +0.10..3.95,
z −13.10..+13.10 m. Floor plate y 1.05, door sill 1.295, cant rail 3.40, crown 3.95.
Trucks z ±9.068. No Scale/Mirror/Shear/*All commands anywhere. 86 AddFace2 (double-sided)
faces; 91 builders use blue (0,0,255) color-key; donor has zero alpha anywhere.

### One model covers both cars
`b.csv` (lowercase!) differs from `A.csv` by 3 hunks: left side lat.png→lat2.png (LAT2 =
LAT minus the 6th saloon window — lavatory), one brake hose x sign, one COFRES cube x sign.
Both cars: cab at +z, gangway at −z. **Build exactly one exterior model.**

### The sides are single 4-quad strips (builders 10 & 15, lat.png)
u→z exactly linear: **z(u) = 12.500 − 25.020·u** (81.855 px/m on a 2048×256 texture).
Profile (y/x/v): 3.400/±1.480/0.00 · 1.900/±1.570/0.63 · 1.700/±1.560/0.72 ·
1.370/±1.500/0.83 · 1.000/±1.500/1.00 (tumblehome). Window band v 0.262..0.555
(y 2.777..2.079). **The saloon wall is a pure vertical gradient — bit-identical columns —
so slices tile perfectly at ANY pitch.** Saloon windows: 6, ~124.5 px wide, pitch 149.5 px
= 1.826 m. Only three other pieces cross bays: roof strip (builder 16, techo.png, plan-view
texture whose middle px 86..513 tiles column-identically), floor plate 19 (flat SetColor
10,10,10 — cut anywhere), equipment raft 45 (Cofres.png — does NOT tile).

### Verified texture slices (reassembly proven seamless; crops in scratchpad donor/img/)
| slice | px | u range | length | donor z |
|---|---|---|--:|---|
| cab_end | 0..396 | 0.000000..0.193359 | 4.8379 m | +12.500..+7.662 |
| door (use the REAR one) | 1468..1690 | 0.716797..0.825195 | 2.7121 m | −5.434..−8.146 |
| window | 733..883 | 0.357910..0.431152 | 1.8325 m | +3.545..+1.713 |
| rear_end | 1690..2048 | 0.825195..1.000000 | 4.3736 m | −8.146..−12.520 |

(Door slice `1457..1690` centres the opening perfectly, same seams. The cab-end 396 px are
photographic and ~9/255 darker than the synthetic wall — re-tone to the wall's vertical
gradient at the join.)

### Bay layout for the 26-block car (416 units, 16.2305 units/m)

**All of it is DERIVED in `tools/m7_layout.py`**, which self-verifies on import and is
shared by the body converter, the interior converter and the definition generator. Do not
copy these numbers anywhere — import them.
```
end(74) | door(44) | window(30)×6 | door(44) | end(74) = 416
window centres z = ±15, ±45, ±75 · door centres ±112 · end centres ±171 (span 134..208)
```
The four verified donor slices that make up a side (cab 4.8379 + door 2.7121 + 6×window
1.8325 + door 2.7121 + rear 4.3736) sum to 25.631 m against a 25.65 m prototype, so at 26
blocks the car maps at ONE scale — the ~23% squeeze the old 20-block car needed is gone.
The two end bays still compress a further ~10% because the nose and the diaphragm have to
fit inside the car end. Mini (240 units = 15 blocks): its ends are the **outer 61 units of
the same end bay, cropped at the same units/metre** — **end ±89.5, door ±37, one window
bay at 0**.

*(Superseded: the first cut was a 20-block car, end(56)/door(32)/window(24)×6, doors ±88,
ends ±132, at 12.7898 units/m. User resized it to 26/15 on 2026-07-28.)*

### Doors — single leaf per opening
PuertaL1/R1 = ONE leaf each (car's left/right side), two builders on the same 10 verts:
exterior (Puerta.png) + interior reversed winding (Puerta2.png, blue-keyed window,
SetEmissiveColor — lit at night). Leaf 1.425 m long × 1.975 m tall, follows the tumblehome,
sits 20–50 mm proud. Openings: rear centre z −6.6475 (slides −z), front centre +6.7425
(slides +z) — travel 1.45 m each, i.e. both retract toward the nearer car end. Donor has NO
hole behind the leaf (painted door) — cut the real doorway using the `GOMA NEGRA PUERTA`
5-point rubber reveal profile (builders 54–57, 0.1 m deep). Openings asymmetric by 47 mm —
symmetrise. At bay scale: leaf ≈18.2 u, travel 1.45 m ≈ 18.5 u ⇒ R179 multiplier ±19.

### Ends
Both ends nearly flat (0.17 m deep). M7frente.png (331×297): full dark cab mask, u 0..1
across car width, centre door u 0.385..0.615, v 0.18..1.00 over y 3.40..1.05; two blue-keyed
circles at top = holes for the 3D roof headlights (builders 17/18, luz.png, plus
Luces/*.animated lamp positions ±0.19/3.65 and ±0.82/1.805, red markers ±1.10/1.83).
**No destination sign exists.** M7Atras.png: stainless gangway end, blue-keyed door window.
Gangway diaphragm = builders 58–66 ("UNIDAD DE ARTICULACION"), all within z −12.97..−12.46:
top rail cylinder y 3.40..3.60, side rails x ±0.41 y 1.38..3.48, frame boxes, threshold,
jumper brackets.

### Underframe / details
COFRES raft 1.8×0.8×13.0 m, y 0.25..1.05, z ±6.50 (texture non-tiling, 119.2 px/m);
snowplough z 12.30..12.85; couplers/chains/hoses all |z|>11.5, y<1.25; logos are separate
blue-keyed geometry (builders 70/71, z 7.80..8.90, x ±1.53..1.55). Seats (later pass):
0.85 m pitch, 2+2 at x +0.945, singles at x −0.740 — maps to MTR SEAT parts.

### ⭐ A BAY MAY NEVER CARRY HALF A WINDOW (2026-07-28, user-reported)

The side elevation is drawn ONCE for the whole car and cropped per bay. A window whose
donor z straddles a bay boundary therefore got cut in half by the crop — and the half that
landed inside was still an alpha-156 mark, so `open_glazing` punched it through as a real
aperture, while `saloon_glass` (which requires `Bay.contains` at BOTH ends) refused to
build a pane for it. Result: an unglazed slot beside every door, with the neighbouring
pane's gasket right next to it reading as a doubled window frame. Exactly two slices were
affected — the door bay clipped 3.8 px off saloon pane 7, and the **Mini's** gangway end
clipped 49 of 58 px off pane 8.

The rule is now mechanical, in `convert_openbve_m7.panes_in()` and the interior's
`apertures_in()`: **a pane exists wholly inside its bay (frame margin included) or not at
all**, and each bay draws its OWN elevation with the others omitted. Both `--check`s guard
it on the pixels that ship — a transparent pixel in a slice's first or last column IS a
window the crop cut. Where a truncated Mini bay keeps a different set from the full bay it
gets its own texture, cropped to the SAME columns so `Tex.u()` never changes; identical
sets share one texture and cost nothing.

Consequence to know about: the Mini's gangway end now has ONE window instead of two. That
is the honest reading of "or not at all" — the alternative would be a narrowed pane, which
means moving a window off its donor position and teaching `saloon_glass` and the lining
about a per-bay override. Ask before doing that.

### ⭐ THE SALOON WINDOWS ARE REAL HOLES IN THE LINING TOO (2026-07-28)

The body's panes went translucent in the art restyle, but the interior lining kept its
painted, opaque windows from the era when the outside was opaque as well — so from the
platform you looked through real glass at the OPPOSITE lining's painted blue rectangle.
The lining now carries an alpha-0 aperture at every window (INTERIOR renders CUTOUT_BRIGHT,
so alpha 0 is a hole exactly as it is on the EXTERIOR skin).

Two rules came out of it:

- **The aperture positions are DERIVED from the body**, not measured again:
  `convert_m7_interior.saloon_apertures()` imports the body converter and insets its pane
  boxes. The old typed `WINDOW_U`/`WINDOW_V` table was a second measurement of the same
  thing and had already drifted ~50 mm in y. The dependency direction is the physical one:
  the lining lines the body.
- **The lining aperture is INSET inside the body's** (70 mm in z, 55 mm each in y) — more
  than the ~100 mm skin-to-lining cavity is deep. That makes the lining read as the
  window's interior frame and leaves no angle from which a ray through the exterior
  aperture can slip past the lining edge into the cavity.
- **NO INNER GLASS PANE.** The body already puts one translucent pane (alpha 76 ≈ 30%
  coverage) over every aperture. A second 100 mm behind it composites to
  1 − (1−0.30)² ≈ 51% — the saloon would read half-milky — and it would put two nearly
  coincident TRANSLUCENT_BRIGHT quads in one line of sight, which MTR gives no depth-sort
  guarantee for. A passenger still sees glass, because the body's pane is still between
  them and the outside.

Known, accepted, and reported by `--check` rather than fixed: an **OPEN** door leaf ends up
covering 3.3 of the 21.8 units of the gangway-end window nearest each door. The leaf lives
in the cavity BETWEEN skin and lining, so it occludes that window whether or not the lining
has an aperture — the see-through windows neither cause it nor can fix it. The two possible
mitigations both cost more than they buy: shortening the travel would leave the doorway
partly blocked, and narrowing the window would move it off its donor position.

### Flag list
1. Mini needs its own truncated end groups. 2. Cofres.png doesn't tile — keep it one piece
per car placed once (or per end). 3. Cab-end tonal step — re-tone. 4. AddFace2 → emit both
windings. 5. Blue key → alpha. 6. Doorway must be CUT. 7. b.csv is lowercase.
8. **Donor builder 9 (the front roof cap) is NOT converted** — it is a non-planar 13-face
triangle fan radiating from the two cab-door top corners, and it reads in game as a
facetted wedge on the nose ("the weird triangle thing"). `roof_cap()` rebuilds it as a
flat strip: one quad per `ROOF_PROFILE` segment, every vertex at donor z 12.500, so it is
one plane with one normal and cannot facet. It is textured **from the cab mask, not the
roof** — the fillet rows above `MASK_V_AT_TOP`, so the black wraps up over the top of the
nose with the body's stainless corner posts at the outside. (The first rebuild re-materialled
it to the roof plan; the user asked for the black back on 2026-07-28. The roof PLAN keeps
its ribs where it is actually roof — `roof_shell` from z 12.500 back is untouched.)
9. **Donor builders 17/18 (the two 3D roof lamps) are NOT converted** either, and the
alpha-0 discs the mask used to carry for them are gone. User decision: they read as two
floating circles. The head and marker lamps in the orange band are the real ones and stay.

---

## Part 3 — Scale decisions for the converter

- x: donor ±1.57 m → ±1.456 blk (factor 0.9274 blk/m — from r179 body ±1.387 blk × 10.5/10).
- y above floor: keep r179's vertical factor ≈1.0255 blk/m: floor (1.295 m) → 0, roof crown
  3.95 → ≈2.72 blk. Below floor: compress so the lowest underframe (0.10 m above rail)
  lands at ≈ −0.95 blk (rail is −1 blk with modelYOffset 1; nothing below rail).
- z: piecewise per bay — each donor slice range maps linearly onto its bay's units
  (12.7898 u/m nominal), bays assembled per the layout above. Each bay group is authored
  centred on z=0 in OBJ blocks (bay units / 16) and repeated by the definitions.
- OBJ axes: negate x and z from the "bbmodel frame" layout; 1 unit = 1 block; bake 1−v.
