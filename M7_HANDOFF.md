# Handoff — LIRR M7 custom MTR train (in progress)

Written 2026-07-27. Read this before touching anything under `assets/mtr/` or
`assets/station_announcer/{models,properties,textures}/vehicle/`.

Goal: custom rolling stock for Baker City — **LIRR M7 first**, then NYCT R143 and R62.
Approved plan: `~/.claude/plans/let-s-work-together-to-graceful-clarke.md`.

---

## 0. Polish pass — 2026-07-28, from the user's in-game feedback

Four targeted fixes on a train that already looks right. All four generators re-run in
order (body → interior → doors → assets), both `--check`s at **0 problems**, and the whole
52-file output byte-identical on a second run. Renders in the session scratchpad under
`polish/final/`. **Not yet verified in game** — see §6 for what to look at.

1. **Bogies moved outboard.** `BOGIE_RATIO` left `gen_m7_assets.py` and now lives in
   `tools/m7_layout.py` beside `BOGIE_RATIO_PROTOTYPE` (0.700 = 59'6" over 85'0"). Shipped
   value **0.76**, a user preference — MTR's bogies are visually shorter than a real M7
   truck, so true scale reads as sitting too far under the middle of the car. Both lengths
   follow it for free: normal ±9.88 blk (was ±9.10), mini ±5.70 (was ±5.25). **This is the
   knob to tune** — `python3 tools/m7_layout.py` prints the resulting positions. At 0.76
   the mini's generic MTR truck box overhangs the coupler face by 0.20 blk; that is
   cosmetic and reported rather than raised, but the normal car is asserted clean.
2. **The saloon windows are see-through.** The lining's painted opaque windows became real
   alpha-0 apertures, derived from the body's own pane boxes and inset inside them so the
   lining reads as the interior frame. No inner glass pane — the arithmetic is in
   M7_CONVERSION_NOTES. Full write-up there under "THE SALOON WINDOWS ARE REAL HOLES".
3. **Clean cab front.** The donor's roof-cap triangle fan (builder 9) is rebuilt as a flat,
   single-plane strip — still **mask-black**, so the black wraps up over the top of the
   nose as it always did (the roof plan keeps its ribs where it is actually roof). The two
   3D roof lamps (builders 17/18) and their keyed holes are gone. A **destination sign**
   sits in the black band along the TOP of the mask, just above the windshields and centred
   on the car: a painted recessed box on the mask plus two MTR DISPLAY parts carried by
   `m7_doors.bbmodel` — `DESTINATION` 16×2 (**scrolling**) and a `ROUTE_COLOR` bar 16×1
   above it.

   *User verdict on the first cut, from an in-game screenshot: "great!" — the display works
   and shows route text. The three refinements above (black top restored, sign moved up and
   centred, scrolling enabled) landed the same day and are again render-verified only.*
4. **No more partial-window slivers.** Every bay now draws only the panes that fit wholly
   inside it. Both the door bays and the Mini's gangway end were cutting one.

### ⭐ The one piece of new MTR law: DISPLAY parts do not work on an .obj

Verified in MTR 4.0.5 bytecode. The .obj `writeCache` overload returns immediately for any
part whose type is not NORMAL, and `ModelDisplayPart` can only be built from a Blockbench
element's `from`/`to` — there is no mesh path and no bounding-box fallback. A DISPLAY part
on an .obj is silently invisible, and its geometry is not drawn either. This is the same
family of defect as the door leaves and the FLOOR/DOORWAY parts.

So the sign lives in `m7_doors.bbmodel` and is bound by two new properties files,
`m7_display_1.json` / `_2.json`, stacked in the index's `models` array **only for ends that
have a cab** (`display_files()` in `gen_m7_assets.py`). They contain no NORMAL part, so
nothing is drawn twice — MTR's own idiom, exactly what r179 does for its head cars.
`--check` now fails any DISPLAY part sourced from an .obj. Geometry convention, the r179
reference JSON and the full enum lists are in M7_CONVERSION_NOTES.md.

### ⭐ Exterior SIDE destination signs (2026-07-28, user request)

Four per car — one beside each door on each side — on **every** variant including the
Mini and the trailer, live off the assigned route. A painted housing (black LED window in
the windows' own gasket tones) in the bodyside letterboard, plus ONE `DESTINATION`
DISPLAY element in `m7_doors.bbmodel` bound in `m7_doors.json`, so it ships with the
doors properties every vehicle already stacks. Amber `FF9900`, `"Not In Service"` when
idle, `SINGLE_LINE`+`SCROLL_NORMAL` — r179's own side-display idiom.

**What the real M7 has, honestly:** *no* amber side destination sign. Its only side
signage is the illuminated **number board** (backlit white plate, black car numerals) in
the letterboard at the cab end beside the marker lights — the feature that distinguishes
the LIRR M7 from Metro-North's M7A. The car with amber LED side destination signs is the
M7's successor, the **M9** ("digital destination signs front and sides"). So the position
is the M9's/r179's — letterboard, beside a door — and the look is amber-on-black. The
full write-up, with why it cannot go where the real number board goes (that spot only
exists on a cab car, and trailers must show a sign too), is in `m7_layout.SIDE_SIGN_*`.

Three things worth knowing before touching it:

- **Size is capped by the panel, not chosen.** The bay's flanking panel is 0.644 m; with
  a 30 mm bezel the longest whole-unit screen that fits is 9, and 8 leaves 45 mm of
  stainless each side — 0.49 × 0.12 m, a 4:1 strip. Whole units are mandatory: MTR takes
  `Math.round(to − from)` as the text canvas.
- **The signs are not mirror-symmetric about the car centre**, and cannot be: the door
  bay is translated to both door centres while only the sides are mirrored, so the sign
  sits toward the car centre at one door and toward the car end at the other. The hazard
  decals already behave this way.
- **The element is authored for the +x side only**; `bbSideSign` lists both door centres
  in BOTH position lists and the flip supplies the −x side. A flipped .bbmodel DISPLAY
  composes as `R·v + t` (`lambda$renderDisplay$41` translates then `rotateYDegrees(180)`),
  which is exactly the transform that moved the shell's one-sided bodyside — and its
  painted housing — to the other side. `gen_m7_doors.verify_side_sign()` bakes all four
  placements, checks the plate is a constant-x plane standing proud of the *body
  converter's own* profile, that its text faces outward, and that its along-car centre
  equals the housing's — the housing being re-derived through the .obj's *opposite*
  composition rule. `convert_openbve_m7.py --check` does the donor-space half.
- ⚠️ **The trap that cost one build:** a rotated display's offsets are relative to
  `origin`, so the along-car centre goes in `from[0]`/`to[0]` with `origin[2] = 0`
  (r179's arrangement). Put the centre in `origin[2]` instead and the plate lands ~16
  units INSIDE the car, invisible, with nothing logged. Details in M7_CONVERSION_NOTES.

### Where the knobs are now

| What | Where |
|---|---|
| Truck position | `m7_layout.BOGIE_RATIO` |
| Destination sign box + display sizes | `m7_layout.SIGN_*` (three tools read it) |
| Exterior side sign position, size, standoff | `m7_layout.SIDE_SIGN_*` (three tools read it) |
| How far the lining aperture insets inside the body's | `convert_m7_interior.LINING_APERTURE_INSET_{Y,Z}` |
| Which panes a bay may draw | `convert_openbve_m7.panes_in()` / `convert_m7_interior.apertures_in()` |

---

## 1. Where it stands (updated 2026-07-28, evening)

**ART RESTYLE SHIPPED (user decision): all exterior + interior textures are now
PROGRAMMATICALLY DRAWN MTR-house-style pixel art (~32–48 px/block, flat colours, LIRR
livery), replacing the processed donor photographs everywhere except the underframe
(cofres/plough/grille — kept per user decision) and MTR's own bogies.** Shared art kit:
`tools/m7_art.py` (palette constants, raster primitives, 3×5 pixel font, the
alpha-156-glazing mark/find/cut mechanism). Saloon and door windows are now REAL GLASS:
translucent panes (`m7_glass#interior_translucent`) over cutout apertures on the body,
aligned cutout holes through the bbmodel door leaves; windshields likewise (the donor
always marked glazing at alpha 156 — openBVE blended it, MTR's cutout stage painted it
opaque). The donor-credit ad card inside the saloon was replaced with neutral drawn ad
cards (user decision). Drawn exterior art ≈12 KB (was ≈235 KB of photo slices); interior
atlases 512² + 256² ≈ 9.5 KB, 3 draw batches (was 12).

**The real M7 is DONE for the exterior-first pass: geometry converted from the openBVE
donor, real livery textures, single-leaf sliding doors, synthesized BVE sound set.**

Verified on the dev rig 2026-07-28: `Loaded 431 vehicles and completed door movement
validation in 0.0 ms`, zero `Invalid model!`, zero `Vehicle doors overlapping!`, zero
station_announcer errors. Offline renders (tools/render_obj.py) confirm the car reads as
an M7: stainless tumblehome sides, 6+2+1 window rhythm, black cab mask with orange band,
roof HVAC fans, gangway diaphragm, snowplough, underframe raft, flag decal.

The body was user-verified in game 2026-07-28 and reads correctly. **The doors did not**
— they were rebuilt as a companion .bbmodel the same day (see the section below) and that
rebuild is NOT yet verified in game.

**Read `M7_CONVERSION_NOTES.md`** — it holds the empirically-verified MTR OBJ-loader spec
(units, axes, materials, the flipped-z trap, R179 door curve) and the donor inventory.
Do not re-derive any of it.

### Files that exist now

| File | What it is |
|---|---|
| `tools/convert_openbve_m7.py` | Donor CSVs → `m7.obj` + `m7.mtl` + 25 textures. `--check` cross-validates EVERY model against its OWN properties file (it reads the index and pairs them), both ways; `--assemble DIR` bakes full-car OBJs for offline preview (doorless — the leaves are not in the .obj). Run FIRST |
| `tools/convert_m7_interior.py` | AInterior → `m7_interior.obj` + `int_*.png`. Has its own `--check` / `--assemble`. Run SECOND |
| `tools/gen_m7_doors.py` | The four sliding leaves → `m7_doors.bbmodel` + `doors_box.png`. Run THIRD |
| `tools/gen_m7_assets.py` | Index + properties + definitions (r179 stacking pattern), for all three models. Run FOURTH |
| `tools/gen_m7_sounds.py` | Synthesized BVE sound set (20 oggs + sound.cfg + power/brake CSVs + sounds.json keys). Independent |
| `tools/bve_csv.py`, `tools/pngtool.py` | Shared libs (openBVE parser, dependency-free PNG IO) — reuse for R143/R62 |
| `tools/render_obj.py` | Offline software renderer for OBJ preview (see its --help) |
| `assets/mtr/mtr_custom_resources.json` | The vehicle index. 8 M7 entries, `bveSoundBaseResource: station_announcer:m7` |
| `assets/station_announcer/models/vehicle/m7.obj/.mtl` | 15 groups, 516 verts, 24 materials — the SHELL only: bodyside, roof, ends, the door sill strip. No door leaves, **no interior lining, no floor or doorway plates** (they could neither draw nor register — see below) |
| `assets/station_announcer/models/vehicle/m7_doors.bbmodel` | 19 groups: the four pocket-door leaves (5 flat height bands x 2 render stages each), plus `doorway_box` and the four FLOOR plates — the vehicle's ONLY floors and doorways — plus every DISPLAY element on the train (front destination + route colour per length, the interior next-stop screen, the exterior side sign) |
| `assets/station_announcer/models/vehicle/m7_interior.obj/.mtl` | The saloon: 9 groups (`int_window`, `int_door`, `int_seat_fwd/_rev/_zone`, `int_end_{gangway,cab}`(`_mini`)) |
| `assets/station_announcer/textures/vehicle/m7/*.png` | 25 shell + 1 door atlas + 10 `int_*` interior textures |
| `assets/station_announcer/properties/vehicle/m7_{common,end_1,end_2,cab_1,cab_2,doors,display_1,display_2,interior_common,interior_end_1,interior_end_2,interior_cab_1,interior_cab_2}.json` | Stacked per variant like r179. Each vehicle stacks **three models**: shell (common + per end), doors, interior (common + per end) — plus, on a CAB end only, the doors .bbmodel a second time carrying nothing but the `m7_display_*` DISPLAY parts |
| `tools/m7_layout.py` | **The single source of truth for the car's scale and bay layout.** Shared with the interior converter; self-verifies on import. Change the car size HERE |
| `assets/station_announcer/properties/definition/m7{,_mini}.json` | Bay layout: 26-block car end(74)/door(44)/window(30)×6, windows ±15/±45/±75, doors ±112, ends ±171; mini (15 blocks) crops the same end bay to its outer 61 units — door ±37, one window at 0 |
| `assets/station_announcer/sounds/m7/` | The synthesized sound set |

The placeholder `m7.bbmodel` + palette `m7.png` are deleted.

### The doors are a SECOND MODEL — MTR's .obj path cannot animate them

Found in game 2026-07-28 (leaves popped open ~5 blocks clear of the car, and
opened on both sides at once), then confirmed in MTR 4.0.5 bytecode:

1. **Double offset.** `ModelPropertiesPart.lambda$writeCache$12` bakes a door
   part's position offset into its own `ObjModelWrapper` *and* stores the same
   offset on the `PartDetails`; `lambda$renderNormal$25` translates by the
   latter before drawing the former. 88/16 = 5.5 blocks, twice. The .bbmodel
   path bakes its animated copy at (0,0,0) instead, so the offset is applied
   exactly once.
2. **No doorways, no floors.** The OBJ `writeCache` overload takes no
   floors/doorways sets at all, so `type: DOORWAY` / `type: FLOOR` parts in an
   .obj contribute nothing. Our client log has always said
   `[m7_cab_3] No floors or doorways found in vehicle models`, and MTR
   synthesises one car-length floor plus a 1-block doorway box every block
   along **both** sides. Side binding is geometric — `mapDoors` gives each
   door instance the nearest doorway box and only boxes that pass
   `canOpenDoors` open — so displaced leaf boxes bound to the wrong ones.

Fix: the leaves moved to `m7_doors.bbmodel` (the path 423 MTR vehicles prove),
stacked as one more entry in each vehicle's `models` array against the same
position definitions. Four one-sided groups, each in exactly ONE of
`positions` / `positionsFlipped`, which is what pins each leaf to one side.
**`tools/gen_m7_doors.py`'s docstring is the reference** — sign composition,
the box-UV unwrap, and why `shade`/`mirror_uv` are written explicitly.

**Both defects are now fully closed (2026-07-28).** `m7_doors.bbmodel` also
ships `doorway_box` (one per opening, four placements off the `door`
definition) and four FLOOR plates that tile the walkable car, so the
synthesized fallback is off and boarding happens at the doors only. The two
must ALWAYS ship together — the fallback fires when floors and doorways are
both empty, so removing one silently removes the other's effect. `--check`
asserts both exist and that both live in a .bbmodel.

### The doors are POCKET doors (user request, 2026-07-28)

The leaf slides INTO the body, in the ~1.48-unit cavity between the exterior
skin and the interior lining — closed it fills the aperture recessed behind
the rubber reveal, open it disappears behind the bodyside. The cavity LEANS
(tumblehome: |x| 22.26 at the sill, 23.30 at the waist, 22.05 at the head), so
the corridor a leaf may occupy at every height at once is only 0.23 units —
too thin for a box, and a single flat plane would sit 1.3 units deep at the
waist and 0.1 at the head. The leaf is therefore a stack of FLAT PLANES, one
per height band (5 at present), each centred in its own band's corridor, two
per band so the platform side is EXTERIOR-lit and the saloon side is
INTERIOR-lit. `pocket_bands()` derives all of it by MEASURING m7.obj and
m7_interior.obj, and raises rather than shipping a leaf that clips either, so
it re-derives itself whenever the shell or the saloon moves.

### Open items before calling the M7 finished

1. **Thomas's in-game verdict** (look, door feel, sound mix — mix constants are at the top
   of `gen_m7_sounds.py`).
2. Known risks from the conversion (watch for these in game): DOORWAY plates possibly
   rendering as grey quads in apertures; modelled gangway diaphragm may double with MTR's
   own connector (`hasGangway: true`); snowplough tips touch at coupled cab ends
   (couplingPadding 0). *(The "reads stubby" risk is gone — the car is 26 blocks now.)*
3. Then: version bump (→2.4.0), `releases/` jar, README, CLAUDE.md.
4. Later passes: the cab interior, then R143 and R62 via the same pipeline. (The saloon
   interior landed 2026-07-28 — `tools/convert_m7_interior.py`; the front destination
   display landed the same day — see §0.)

### In-game checks the polish pass still needs (2026-07-28)

Everything below is render-verified offline but NOT seen in game.

- **Bogies.** Do the trucks sit where they should at 0.76? Tune
  `m7_layout.BOGIE_RATIO` and re-run `gen_m7_assets.py` only — nothing else reads it.
- **The destination display.** The first cut was confirmed working in game. What is new and
  unverified: it moved into the band above the windshields, grew to 16×2, and now
  **scrolls** (`SINGLE_LINE` + `SCROLL_NORMAL`). Check a LONG destination actually
  marquees rather than truncating, and that a short one still reads. If a display ever goes
  blank, MTR logs nothing — check that the vehicle's `models` array really has the extra
  `m7_display_*.json` entry, and that `bbEnd1`/`bbEnd2` (not `end1`/`end2`) are the
  position definitions; the two sign conventions differ, see §2's positionsFlipped note.
- **The exterior side signs (new, 2026-07-28).** Four per car, in the letterboard beside
  each door, on every variant. Walk both sides of a cab car AND a trailer: each door
  should have exactly one, amber text on the black window, reading "Not In Service" with
  no route assigned. Check a LONG destination marquees rather than truncating on what is
  only an 8×2 canvas, and that the text sits ON the painted housing rather than beside or
  behind it. If a sign is missing entirely, the definition or the properties entry is the
  place to look (`bbSideSign` in `m7{,_mini}.json`, `side_destination_display` in
  `m7_doors.json`); if the housing is there but never lights, the element is in the model
  but the part is not bound.
- **The nose.** Black over the top again, no facetted wedge, no floating circles, and the
  grey roof ribs starting cleanly at the roofline rather than wrapping onto the front.
- **The sign's vertical room is nearly exhausted.** It sits between the windshield gaskets
  (donor y ≈ 3.228) and the roofline (3.400) — about 3.2 M units for both displays, which
  is why the destination is 2 units and not 3. Anything above 3.400 steps 0.17 m back onto
  the roof cap and its text would visibly float. `m7_layout._verify()` and the body
  converter's `--check` both guard this; do not "just make it bigger".
- **See-through windows.** From a platform: seats and the far side, not a flat panel. From
  inside: one layer of tint, not two.
- **The window sliver.** Walk the whole car on both sides — the fix is per-bay, so a miss
  would show at one seam only.
- **Doors open.** Expect ~15% of the gangway-end window nearest each door to be covered by
  the retracted leaf. That is pre-existing and documented; anything larger means something
  moved.

---

## 2. How MTR custom vehicles work

All verified by reading `minecraft-transit-railway-FABRIC-4.0.5+1.20.4.jar` with `javap` and
`unzip -p`. **This section is the expensive part — do not re-derive it.**

Jar path:
`.gradle/loom-cache/remapped_mods/net_fabricmc_yarn_1_20_4_1_20_4_build_3_v2/maven/modrinth/minecraft-transit-railway/FABRIC-4.0.5+1.20.4/minecraft-transit-railway-FABRIC-4.0.5+1.20.4.jar`

- **No Java is needed.** Custom vehicles are pure resource data.
- `CustomResourceLoader` calls
  `ResourceManagerHelper.readAllResources(mtr:mtr_custom_resources.json)` — vanilla's "read
  this path from EVERY pack". That is why **our own mod jar can contribute vehicles** by
  shipping `assets/mtr/mtr_custom_resources.json`. MTR's own copy is read separately; there
  is no conflict.
- Resource strings in the index are full namespaced identifiers
  (`CustomResourceTools.formatIdentifier`), so models/textures live under
  `station_announcer:` and only the index sits in the `mtr` namespace.
- Index top-level keys: `vehicles`, `signs`, `rails`, `objects`, `lifts`.

### Model formats — the important one

`VehicleModel` branches on the file extension:

- **`.bbmodel`** → Blockbench path. `BlockbenchElementSchema` reads
  `from`/`to`/`inflate`/`rotation`/`origin`/`uv_offset`/`shade`/`mirror_uv`/`name`/`uuid`
  — **cuboids only, no mesh support**, and **per-face `uv` dicts are ignored**: the unwrap
  is vanilla `ModelPart$Cuboid` box UV off `uv_offset` plus the element's size, and sizes
  are `Math.round(to − from)` **integers**. `resolution` is only the UV divisor, so the PNG
  may be any multiple of it (MTR's r179 declares 368 and ships 1472). `shade` defaults to
  TRUE and `mirror_uv` to false, and MTR passes vanilla `mirror = !shade || mirror_uv`.
  Full layout, and which rect is the outboard face, in `tools/gen_m7_doors.py`.
- **anything else** → `ModelResourceLoader`, which accepts **`.obj`, `.mqo`, `.mqoz`**.
  `loadModel` returns `Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel>` — **keyed by
  part name**, so OBJ `g`/`o` groups become the named parts the properties file binds to.

So a triangle mesh (like the openBVE donor) **must** go in as OBJ, and that is fully
supported for vehicles even though MTR's own 423 vehicles all happen to use `.bbmodel`.

### The properties files

`properties/vehicle/*.json`:
```
{ "modelYOffset": 1,
  "parts": [ { "names": [...], "positionDefinitions": [...],
               "renderStage": ..., "type": ...,
               "doorXMultiplier": .., "doorZMultiplier": .., "doorAnimationType": "R179" } ] }
```
- `renderStage`: `LIGHT`, `ALWAYS_ON_LIGHT`, `INTERIOR`, `INTERIOR_TRANSLUCENT`, `EXTERIOR`
- `type`: `NORMAL`, `DISPLAY`, `FLOOR`, `DOORWAY`, `SEAT`
- Display parts also take `displayType`, `displayOptions` (`SCROLL_NORMAL`, `SEVEN_SEGMENT`,
  `ALIGN_*`, `CYCLE_LANGUAGES`, …), `displayColor`, padding, `flashOn/OffTime`.

`properties/definition/*.json`:
```
{ "positionDefinitions": [ { "name": "door",
                             "positions": [{"z": -60}, {"z": 60}],
                             "positionsFlipped": [ ... ] } ] }
```

**The model is modular, and for us it has to be.** You model ONE bay of each kind and the
position definitions repeat it. A single `{"z": 0}` position is legal — so a whole-car mesh
*could* be placed once — **but then the 15-block Mini variant would need a second model.**
That is the sole reason the layout is bay-based. Keep this in mind when adapting the donor.

### Free stuff

- Bogies: reuse `mtr:models/vehicle/bogie_1.bbmodel` + `bogie_1.png` + `bogie_1.json` +
  `mtr:properties/definition/origin.json`.
- Sounds: `legacySpeedSoundBaseResource` points at a folder —
  `assets/mtr/sounds/<base>/acceleration/speed_Nx.ogg`, `deceleration/…`, `door_open.ogg`,
  `door_close.ogg`. `bveSoundBaseResource` takes BVE-style sets
  (`run*`, `motor*`, `air`, `airzero`, `flange*`, `point`, `rub`, `loop`, `doorcls`, `dooropn`).
- **R179 and R211 are already NYCT trains in MTR** — the template for ids, names, tags and
  variant structure. R179 coordinate space: x ±32, y 0..63, bays repeated at z ±40/±80/±120.

---

## 3. Decisions already taken — do not re-litigate

| Decision | Choice |
|---|---|
| Packaging | Inside the Station Announcer jar |
| First pass | Exterior shell, livery, working doors. **Saloon interior landed 2026-07-28**; destination displays and the cab interior are still later |
| Sizes | Normal **26** blocks/car, Mini **15** (was 20/10; user resized 2026-07-28) |
| Formation | Married pairs, cab at each car's outer end, **2 doors per side** |
| Model source | The openBVE M7 pack (see §4) — **Thomas has stated he has permission to use these assets** |
| Sounds | **Synthesise** an M7-style set. Do NOT use the pack's audio |
| Connections/PIDS | Unrelated — that work is finished, see CLAUDE.md v2.3.0 |

### Current M7 parameters (in `tools/gen_m7_assets.py`)

- **Everything dimensional lives in `tools/m7_layout.py`** and is derived from the donor's
  own slice lengths, not typed. `gen_m7_assets.py` imports it; so does the body converter.
- Normal 416 units = 26 blocks: end(74) door(44) window(30)×6 door(44) end(74);
  windows ±15/±45/±75, doors ±112, ends ±171. Mini 240 = 15 blocks: window 0, doors ±37,
  ends ±89.5 (the outer 61 units of the same end bay).
- `doorZMultiplier` 24 — 1.45 m of prototype travel at 16.22 units/m; R179 opens |m|−0.5.
- `BOGIE_RATIO` **now lives in `tools/m7_layout.py` and is 0.76** (user preference, 2026-07-28;
  the prototype figure sits beside it as `BOGIE_RATIO_PROTOTYPE`). ±9.88 normal, ±5.70 mini.
  (Superseded: the old 0.70 gave ±9.1 / ±5.25.)
- 8 vehicles: `m7_{trailer,cab_1,cab_2,cab_3}` at length 26, `m7_mini_*` at length 15.

### Group names the donor must be adapted into

`window`, `window_exterior`, `window_floor`, `roof`, `door`, `door_exterior`, `door_left`,
`door_right`, `door_left_exterior`, `door_right_exterior`, `doorway`, `door_floor`, `end`,
`end_exterior`, `end_floor`.

### Open trade-off, flagged to Thomas and left as-is

**RESOLVED 2026-07-28 — the user resized the car to 26/15 blocks.** The old 20-block spec
compressed the 25.65 m prototype by ~23% while MTR's ~30%-oversized cross-section stayed,
so the car read stubby. 26 blocks is essentially true scale (the donor side sums to
25.631 m), so the length squeeze is gone and only the two end bays still compress. The
cross-section is unchanged. `tools/m7_layout.py` is the single constant this was flipped
at, exactly as the note below hoped.

---

## 4. The donor assets

Root:
`/Users/thomasdemuth/openBVE/Train/LIRR & MNCR Bombardier M7 EMU Pack V1.1/Long Island Rail Road M7/2 Car M7/`

Its `readme.txt` says "Please do not redistribute this train pack." **Thomas has stated he
has permission to use these assets**, which is why work continues. If a new session needs to
re-confirm that, ask him — do not silently assume either way.
Credits: Fan Railer (Mike Kam), Manuel Alejandro Mejias Palacios, William McMorris.

### Model/ — openBVE CSV objects (plain text, parseable, with UVs and texture refs)

| File | Role |
|---|---|
| `A.csv` | **Exterior body.** 338 vertices, 76 mesh builders |
| `AInterior.csv`, `BInterior.csv` | Interiors — AInterior is converted by `tools/convert_m7_interior.py` |
| `PuertaL1.csv`, `PuertaR1.csv` | **Door leaves** |
| `Lead Truck.csv`, `Trail Truck.csv`, `Wheel.csv` | Bogies (we reuse MTR's instead, for now) |
| `minicabina.csv`, `minicabina2.csv` | Cab |
| `asiento*.csv` | Seats |
| `*.png` | Textures |
| `A.Animated` | **Names the door objects and their travel: ±1.45 m** → maps to `doorZMultiplier` |

`A.csv` extents, in **real-world metres** (openBVE convention: x across, y up from rail,
z along the car): `x −1.57..1.57`, `y −0.90..3.95`, `z −12.95..12.70`. That matches the
readme's spec sheet exactly (10'6" wide, 12'11.5" rail-to-roof, 85'0" long).

CSV commands present: `CreateMeshBuilder`, `AddVertex`, `AddFace`, `SetTextureCoordinates`,
`LoadTexture`, `SetColor`, `SetDecalTransparentColor`, `Translate`, `Rotate`, `Cube`,
`Cylinder`.

### Sound/ — 34 `.wav`, BVE naming

`motor0-3`, `run0`, `Flange0/1`, `air`, `airzero`, `rub`, `Point`, `loop`, `DoorOpn`,
`DoorCls`, `Klaxon0-2`, `emrbrake`, … These map almost 1:1 onto MTR's BVE sound prefixes.
**Not being used** — the decision is to synthesise instead.

### Not usable: the STL files

`~/Downloads/M9A Body.stl` (61,612 triangles) and `M9A Wheels (print 2x).stl` (24,006). No
UVs, no texture, no part groups, modelled solid at print scale. Would need retopology, which
is not possible in this environment. The whole placeholder is ~400 triangles for comparison.

---

## 5. Next steps, in order

1. **`tools/convert_openbve_m7.py`** — parse the openBVE CSVs → emit
   `assets/station_announcer/models/vehicle/m7.obj` + `.mtl`, one `g` group per name in the
   spec above. Handle `Translate`/`Rotate` (they apply to the current mesh builder),
   `SetTextureCoordinates` → `vt`, `LoadTexture` → material.
   - Slice `A.csv` into window / door / end bays by z so the Mini variant keeps working.
     This is the fiddly bit — see the modularity note in §2.
   - Scale metres → MTR units. Cross-section to match `HALF_WIDTH 34` / `ROOF 68`; length to
     the 26-block car.
2. **Point the index at the `.obj`** — change `MODEL_ID` in `gen_m7_assets.py`. Properties and
   position definitions carry over unchanged, which is the whole point of doing the wiring
   first.
3. **Textures** — the pack's PNGs into `assets/station_announcer/textures/vehicle/`, either
   an atlas or per-material.
4. **Synthesise the sound set** into `assets/station_announcer/sounds/m7/` and point
   `SOUND_BASE`/`bveSoundBaseResource` at it. `soundfile`/`numpy` are installed; the repo
   already synthesises its ambience loops and chimes this way.
5. Re-verify (§6), then bump the version and update `CLAUDE.md` + `README.md`.

Then R143 and R62 reuse the pipeline. R62 is the outlier (single units, different door
count) so it comes last.

---

## 6. How to verify

The dev world has **no MTR railway at all** (only `run/world/mtr/*/settings`), so trains
cannot actually be run locally. Verification is therefore log-based.

```sh
export JAVA_HOME="$HOME/Library/Application Support/minecraft/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
python3 tools/gen_m7_assets.py && ./gradlew build -q
```

Then boot the rig and grep the **client** log:

- `Loaded N vehicles and completed door movement validation` — N must be 423 + our count.
- No `MTR ... resource contains duplicated id`.
- No `BlockbenchModelValidator` / model-parse errors.

### Rig gotchas (learned the hard way — also in CLAUDE.md)

- **Start the dev server with `run_in_background`.** A server started from a foreground Bash
  call gets SIGKILLed (exit 137/144) when that call returns.
- Kill leftover `KnotServer`/`KnotClient` before rebooting, or the world `session.lock`
  blocks startup.
- `touch run/screenshot.flag` saves a screenshot to `run/screenshots/` (dev-only hook in
  `StationAnnouncerClient`).
- MTR's **Resource Pack Creator** at `http://localhost:80/creator/` is a pack-authoring
  wizard, **not** a model viewer — it will not preview an already-loaded vehicle. Do not
  sink time into it as I did.
