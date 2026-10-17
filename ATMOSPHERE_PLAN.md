# ATMOSPHERE_PLAN.md — grime, litter, failing lights and other lived-in details

Status: **BUILDING (2026-10-05)** — Thomas: "I like everything except F. Start building. I especially
like the construction assets." See §8 for what is built.

Thomas's ask: "details in the game — grime (trash piles, dark dust, etc.), flickering/old lights
for all the existing MTR lights, litter, and other stuff." He wants more ideas and a plan.

Everything here must live with the hard constraints already learned in this project:
- **Sodium 0.5.8 without Indium, plus Iris 1.7.2** in the play profile. No Fabric Renderer API, no
  position-dependent meshes. Variation goes through **blockstate properties / weighted variants**
  (the floors' rule), or through our own renderers (WorldRenderEvents / BERs).
- **No edits to MTR's blocks.** Anything we do to MTR's lights works from the outside: a store of
  positions plus mixins into vanilla engine code (the addon already has mixins).
- Generated assets (a `tools/gen_*.py` per family), never hand-edited.

---

## 1. The idea catalogue

Grouped by kind. ★ = what Thomas named; the rest are suggestions.

### A. Lights that have seen better days ★
| Condition | Look | Notes |
|---|---|---|
| Healthy | as now | default; nothing stored |
| Flicker: stutter | lit, drops out for 1–4 short blinks every 5–30 s | the classic "about to go" tube |
| Flicker: failing ballast | rapid irregular on/off for a few seconds, then steady, repeat | with a buzz/tick sound |
| Flicker: slow pulse | brightness breathes 60–100 % | old HID / sodium lamp |
| Struggling start | dark → several strikes → on, on a long cycle | fluorescent restrike |
| Dim | permanently ~half light level | real light level drops |
| Warm/old | yellowed lens (tint overlay), slightly dim | old tube colour |
| Dead | no glow, light level 0 | dark patch in the station |
| Train-sag | dips briefly when a train accelerates nearby | third-rail voltage sag — unique to us, uses the vehicle mixins |

Works on **every MTR light** (ceiling_light, route signs with light, etc.), **every one of ours**
(el/esi platform lamps, roof/ceiling lights, goose-necks, lamp posts, globes), and as a fallback
on **any block with luminance > 0** (other mods too).

### B. Grime ★
- **Dark dust / soot** — corner build-up on floors, darkening along wall bases, soot streaks on
  ceilings above where trains stop (brake dust).
- **Water stains** — ceiling leak rings, rust streaks running down from girders/columns,
  drip trails down tile walls.
- **Efflorescence** — white mineral crust on tile walls and concrete (very NYC).
- **Puddles** — flat glossy decal under a leak, optionally with drip particles + sound.
- **Gum spots / blackened gum** — floor speckle decal (the floors already bake some in).
- **Cobwebs** in ceiling corners of little-used passages.
- **Oil/brake-dust stains** on the trackbed and platform edges.

### C. Litter and trash ★
- **Floor litter**: crushed coffee cup, newspaper (open/folded), MetroCard, receipts, candy
  wrappers, chip bag, soda can, bottle, plastic bag, cigarette butts, pizza crust (yes, pizza rat).
  1–4 pieces per cell like sea pickles, random rotation via weighted variants.
- **Trash piles**: black garbage-bag heaps (1–3 bags), cardboard stacks, a swept-up pile with a
  broom leaning on it.
- **MTA trash can** (the old station trash can was deleted 2026-07-23 — a proper one returns):
  fill levels empty → overflowing, bag liner visible, overflow litter around it.
- **Trackbed trash** — the under-platform layer of cups and paper between the rails.

### D. Damage and wear on existing blocks
- **Tile walls**: missing tiles, cracked tiles, patched tiles (grey replacement), stained grout.
- **Concrete floors**: cracks, patched rectangles, painted-over tactile strip worn through.
- **Steel**: rust blooms and peeling paint on el columns / girders (variant property or decal).
- **Signs**: a *Wear* option in the sign editor — faded colours, scratches, stickers, a tag.
- **Posters**: torn corner, faded, graffiti on an ad; old service posters left up.

### E. Out-of-service and maintenance props
- Bagged "OUT OF ORDER" MetroCard machine and taped-off turnstile (lane really locked).
- Wet-floor sign, orange cones, plastic barriers, plywood over a broken window, scaffolding,
  work lights on a stand. **Tie-in:** a disruption can "own" its construction props the way it owns
  posters, so cleaning up a disruption takes its cones down.

### F. Things that move or react (the "alive" layer)
- **Piston wind**: an arriving train blows loose litter/paper particles along the platform and
  makes hanging things sway; a gust sound. Uses the existing vehicle hooks for arrival timing.
- **Drips**: particles + plink sound under leak stains and into puddles.
- **Rats** on the trackbed — tiny ambient mob, scurries along the rail, flees trains and players.
- **Pigeons** on el structures — perch on girders/railings, flutter off when a train passes;
  droppings decal under favourite perches.
- **Grate gusts**: street subway grates puff steam/air + rumble when a train passes underneath.
- **Sound layer** for the ambience block: fluorescent buzz (follows flickering lights),
  distant rumble, drips, escalator hum, faint PA static.

### G. The thing that ties it all together: station condition
A **Weathering tool** (Operations tab, FlatUi screen like the Earthworks creators): pick an area
(two clicks, or "this station" from MTR's station area), pick a condition preset —
**Pristine / Lived-in / Gritty / Neglected / Abandoned** — and it scatters grime, litter and light
faults at sensible places with one undo. Every preset is just densities + weights, editable and
savable like earthworks presets. Hand-placed blocks stay available for the details you place
yourself.

---

## 2. Architecture

### 2.1 Light conditions (works on MTR's lights without touching their blocks)

**Store**: `<save>/station-announcer-addon/light_conditions.json` — `pos → {condition, seed,
params}`, per dimension, synced S2C per chunk on load (like the material palette sync). Only
non-healthy lights are stored. A light that gets broken drops its entry (block-change check on
load/sync, plus a cheap validation pass).

**Two effects, kept separate because they cost very different amounts:**
1. **Visual flicker = client only, free.** A `WorldRenderEvents` renderer draws a dark (or warm
   tint) overlay over the light's **lens** in the flicker's "off" frames. Lens geometry comes from
   a table per block id (MTR ceiling_light's strip, our lamp heads, globe orbs — measured from
   their models); unknown blocks fall back to the block's outline shape inflated a hair. The
   timing is a pure function of (seed, world time) so every client sees the same blink without any
   packets. Overlay rules from past lessons: one standoff, no shared planes, draw boxes in one pass
   before any text.
2. **Real light level = vanilla light engine hook.** `ChunkBlockLightProvider
   .getLightSourceLuminance(long pos, BlockState)` is **per position** (verified by javap on the
   1.20.4 yarn jar) — a mixin there returns the condition's luminance: dead → 0, dim → ~8,
   warm → 13. Runs on server and client alike, so mobs, light-level overlays and shaders all
   agree. Only changed when the *condition* changes (a light update, like placing a torch), never
   per flicker frame.
   - Flicker that also flickers the *room* light would mean light updates + chunk-section
     rebuilds several times a second. Offered at most as a "strong flicker" option, capped to a
     few lights near the player, client-side only, and only if a spike shows it is cheap enough.
     Default flicker is lens-only.

**Train-sag**: server already sees train acceleration in the vehicle mixins; it broadcasts
"sag at platform X" and clients dip every condition-flagged *and* healthy light within N blocks
(opt-in per light, or per station). Lens-only.

**Sound**: buzz/tick loop on flickering lights, through the existing `AmbienceSoundManager`
pattern (one moving sound per nearby source, capped).

**Assigning conditions**: the Weathering tool (area, random %), plus a per-light pick with a
**Maintenance tool** item: right-click a light → small FlatUi popup (condition + speed + seed
reroll), sneak-click = cycle. Visible "repair" with the same tool.

### 2.2 Grime decals

One block family modelled on vanilla's **multiface blocks** (glow lichen / sculk vein:
`MultifaceGrowthBlock`, six face booleans in one cell) — so a single air cell next to a corner can
carry floor dust *and* wall dust *and* ceiling stain. Decals are thin quads 0.01–0.05 px off the
surface, CUTOUT/translucent, no collision, replaceable like snow layers so you can build over them.
- Per decal type a block (`grime_dust`, `grime_stain`, `grime_rust_streak`, `grime_efflorescence`,
  `grime_puddle`, `grime_gum`, `cobweb_corner` …), each with a `variant` property (0..3) chosen at
  placement from a position hash, so `/fill` and the tool give natural variety — the floors'
  `onBlockAdded` trick.
- Puddles waterloggable-looking but not water (no flow); drips are a client particle ticker.
- Atlas-bleed rule: every decal face gets explicit interior UVs (the pink-line lesson).
- Generator: `tools/gen_grime_assets.py` (texture noise lessons from the floors: quiet low octave,
  fine octave, non-wrapping blobs, profiles not ranges).

Where blockstate decals can't reach — inside a full block's face — use **variants of our own
blocks** instead (tile walls and floors already have dirty/fresh; add cracked/missing-tile/rusty
variants to the generators).

### 2.3 Litter and trash

- `litter` block: COUNT 1..4 (sea-pickle stacking), TYPE (a mixed "random" type plus specific
  ones), variant rotation by weighted blockstate variants. Floor only, no collision, breaks
  instantly, drops nothing in survival unless sneak-picked (it's trash).
- `trash_bags` (1–3, collision), `cardboard_pile`, `trash_can` (FILL 0..3, overflow model).
- Trackbed litter = the same block on ballast; the weathering tool knows "below a platform edge
  next to a rail" as a zone.

### 2.4 Weathering tool

Same skeleton as the Earthworks/Bridge creators (it worked): item in Operations, FlatUi screen,
presets + user presets file, server-side planner, `Plan` applied sliced per tick, **one undo per
player**, never replaces anything that isn't air (decals/litter) and only touches stored light
conditions for lights.
- **Placement scoring**, so it looks natural rather than sprinkled: a cell's dirt score rises with
  enclosing solids (corners > wall bases > open floor), distance from foot traffic for dust
  (needs a cheap proxy: distance from the walking line between stairs/fare control/platform edge,
  which the wayfinding layout scan already knows), under columns/girders for rust streaks, ceilings
  over the track for soot, near benches/stairs/turnstiles for litter.
- Area: two corners, or "MTR station area" (station rect from the simulator snapshot already used
  by `PaStationNames`).
- Preview: the After/Before toggle the earthworks screen has.

### 2.5 The alive layer

- **Piston wind**: client event "train arriving at platform P" (derived from the arrivals cache the
  PIDS already polls) → spawns paper/dust particles along the platform, nudges litter particles
  (litter *blocks* don't move — only particles), gust sound.
- **Rats / pigeons**: small ambient entities (like bats: no AI cost when far, despawn rules, cap per
  chunk), spawn on trackbed / el girders only near our marker blocks or inside MTR station areas.
  Gamerule/config toggle — some servers won't want mobs.

---

## 3. Phases (proposed order)

| # | Phase | Contents | Size |
|---|---|---|---|
| 0 | Spikes | light-engine mixin on server + client with Sodium/Iris; lens overlay over MTR ceiling_light; one multiface decal block rendering under Sodium | small |
| 1 | **Failing lights** | store + sync, lens flicker renderer (all patterns), real dim/dead/warm, maintenance tool, buzz sound, lens table for MTR + our lights | medium |
| 2 | **Grime decals** | 6–8 decal blocks, generator, hand placement | medium |
| 3 | **Litter & trash** | litter block, bags/cardboard, MTA trash can with fill levels | medium |
| 4 | **Weathering tool** | area + station mode, condition presets, scoring, preview, undo | large |
| 5 | Wear variants | tiles/floors/steel variants, sign editor Wear, worn posters | medium |
| 6 | Alive layer | piston wind, drips, train-sag, grate gusts, sound layer | medium |
| 7 | Critters | rats, pigeons (+ droppings) | medium |
| 8 | Maintenance props | out-of-order machine/turnstile, cones, barriers, disruption-owned props | medium |

Phases 1–3 are independent and each is usable on its own (hand placement). Phase 4 is what makes
it fast to dress a whole station, so it should not slip too far.

---

## 4. Performance budget

- Lens overlays: only for stored lights within ~64 blocks, culled by frustum; boxes in one pass.
- Light-engine hook: one hash lookup per light-source evaluation — must be a primitive
  `Long2ByteOpenHashMap`, per world, and short-circuit when the map is empty.
- Decals/litter are ordinary block models: zero per-frame cost; the blockstate count stays small
  (check *states × selectors* — the pipe lesson).
- Weathering plans run on the server thread like earthworks (82k-block plan = 30 ms), applied sliced.

## 5. Risks

- **Iris shaders** may treat our dark lens overlay oddly (overlays drawn after translucent can be
  skipped or lit differently by shader packs). Test with Thomas's shader if he uses one.
- **Light hook vs other lighting mods**: the play profile has none (vanilla engine), so fine there;
  ScalableLux/Starlight users would lose the real dim/dead effect but keep the visuals.
- **MTR ceiling lights in rows**: a whole row of ceiling_light is common; per-light assignment by hand
  is tedious → the area tool matters for lights too.
- Decals in cells the player later wants to build in: they are replaceable, so building just
  overwrites them.

## 6. What this does NOT do

- No edits to MTR assets or blockstates; no resource-pack overrides of MTR textures.
- No dynamic accumulation of litter over time (unless Thomas wants it — §7 Q6).

## 7. Questions for Thomas (answers go here, then build)

1. **Order**: lights first (as asked), or the weathering tool early so dressing a station is fast?
2. **Lights — real light changes**: dead/dim lights really darken the area (light level), or visuals
   only? And should flicker ever flicker the *room* light (costly), or the lamp only?
3. **Which lights**: MTR + ours only, or any glowing block from any mod?
4. **Placement**: hand-placed blocks, the area tool, or both?
5. **Grime scope**: floors only, or walls and ceilings too (multiface decals)?
6. **Litter**: static only, or slowly accumulating in busy stations (and cleanable)?
7. **The alive layer**: piston wind / drips / train-sag yes? Rats and pigeons yes, or no mobs?
8. **Look reference**: any photos of the grime level you want (MTA "lived-in" vs "abandoned")?

## 8. Thomas's answers (2026-10-05) and build log

**Answers** (do not re-ask): everything in §1–§6 EXCEPT section F (no piston wind, drips, rats,
pigeons, grate gusts, ambience sound layer — so no fluorescent buzz either). He especially likes the
construction props (§1 E). Unanswered details were decided as the plan proposed: real light levels
for dim/warm/dead (flicker = lamp only), any glowing block from any mod, hand placement AND the
area tool, grime on floors + walls + ceilings, litter static (no accumulation). Train-sag was listed
under A but is "moving" — NOT built, ask him.

**Built (round 1, javac-verified, NOT booted / in game):**
- `atmosphere/` common package, registered from StationAnnouncer (MTR block), own tab
  "Baker City: Atmosphere" (`AtmosphereRegistry.ENTRIES`).
- Light conditions: `LightCondition` (8 states, pure flicker math `lens(seed, ms)`, `luminance(base)`),
  `LightConditionState` (PersistentState per dimension, `data/station_announcer_lights.dat`, packed
  ordinal|salt<<8), `LightConditions` (set/setAll/inBox, relight via `checkBlock`, S2C sync full on
  join/dimension change + deltas, 30 s sweep of broken lights), `LightLuminance` (copy-on-write
  snapshots read from the lighting threads), mixin config `station_announcer.atmosphere.mixins.json`:
  `ChunkBlockLightProviderMixin` hooks BOTH `getLightSourceLuminance(long, BlockState)` and the
  initial-chunk lambda `method_51532` (javap-verified).
- `MaintenanceToolItem` (right-click light = apply / again = repair, sneak = repair, sneak-air =
  reroll rhythm, air = picker screen) + `OutOfService` (PersistentState of data cells; bagged MetroCard
  machine refuses use, taped turnstile/HEET lane stays locked: hooks in `FareLane.onRiderInLane` and
  `FareMachineBlock.onUse` (PASS for the tool)). `/atmosphere light <pos> <cond>`,
  `/atmosphere lights <from> <to> <cond> [percent]`.
- Client `client/atmosphere/`: `AtmosphereClient` (sync → client light engine snapshot),
  `AtmosphereRenderer` (lamp re-drawn 1.2 % larger at the lens light level / WARM tint via
  `VertexConsumer.quad` on the entity block layer; tool markers; bag + "OUT OF ORDER" card; caution
  tape X + "NOT IN SERVICE" card; weathering corner box), `MaintenanceScreen` (live lamp swatches),
  `WeatheringScreen`.
- Weathering Tool: `WeatherSpec` (13 layers + 5 presets: pristine/lived_in/gritty/neglected/abandoned;
  pristine + clean = cleaner), `WeatheringPlanner` (family blocks looked up BY ID; corners/wall bases,
  trackbed = cell beside a 1-high ledge or on ballast/gravel, platform edge, busy = within 2 of
  bench/turnstile/stairs/fare/pids; value-noise clusters; leak → puddle below), `Weathering`
  (sliced 6000/tick, one undo per player incl. lights; `/atmosphere weather <from> <to> [preset]`,
  `station [preset]`, `undo`), `WeatheringToolItem` (two corners / sneak = MTR station via
  `PaStationNames.area`).
- Families (built by parallel agents, merged + checked by the lead):
  - GRIME (`GrimeBlocks`, `GrimeDecalBlock extends MultifaceGrowthBlock`, gen_grime_assets.py): grime_dust,
    grime_soot, grime_water_stain, grime_rust_streak (walls+ceiling), grime_efflorescence (walls+ceiling),
    grime_puddle / grime_gum / grime_oil (floor only — those blocks have ONLY the `down` property),
    cobweb_corner. Translucent (cobweb cutout), AO on, context-aware variants (dust floor builds toward
    dusty walls in the same cell).
  - LITTER (`LitterBlocks`, gen_litter_assets.py): litter (count 1..4, 12 piece kinds), trash_bags (bags),
    cardboard_pile (layers), trash_can (facing, fill 0..3, empty hand cycles), swept_pile (broom),
    newspaper_stack.
  - CONSTRUCTION (`ConstructionBlocks`, gen_construction_assets.py): traffic_cone (count 1..3),
    wet_floor_sign (wet_floor/caution/closed), barricade, police_barricade, plastic_barrier (runs),
    caution_tape (axis, ends none/tape/wall/post, double), construction_wall (MTA blue, notice /
    no_admittance), plywood_board, scaffold, sidewalk_shed + _post + _light, work_light, caged_work_lamp.
  - WEAR (`WearBlocks`, gen_wear_assets.py): subway_tile_wall_{cracked,missing,patched,stained},
    platform_tile_floor_cracked, platform_concrete_floor_{1..4}_cracked (ConcreteFloorBlock joints).
- Sign wear: `SignSpec.Panel.wear` 0..3 (JSON "wear", Draft.panelWear), editor Sign inspector "Wear"
  segmented control, painted by `client/mtr/SignWear` at the end of `SignLayout.paint` (editor preview ==
  world), strokes clipped to the panel. Poster wear: `ServicePosterBlockEntity` NBT "Wear"; the Maintenance
  Tool on the frame cycles it (sneak = new); `SignWear.paintPoster` (yellowing, peeled corner).

**Verified on the scratch rig (2026-10-05, own server 25568 + client, world clone; screenshots in the
session scratchpad `rig/shots/`):** clean boot with the new mixin config; zero station_announcer model /
blockstate warnings with all families loaded; real light levels (predicate datapack): healthy 14 → dead 0,
dim 7, warm 12, flickers unchanged; conditions persist across restart; repair; a broken light is forgotten
by the 30 s sweep; lamp overlays for all 8 conditions on MTR ceiling_light; bagged MetroCard machine and
taped turnstile; `/atmosphere weather … neglected` on a 25×21 hall (241 decals, 25 litter, 3 light faults,
14 ms) + undo; construction scene placed through the real item code (`/rigplace`): barricade / plastic /
police runs connect, tape knots to a barricade post at waist height, cone stack, all sign variants,
construction wall variants, scaffold, shed; sign wear 0..3 in world; both tool screens at the small GUI scale.
**NOT verified:** mouse clicks in the screens, the Maintenance Tool's real right-clicks (apply / repair /
reroll / out-of-service / poster wear), the Weathering Tool's corner clicks and station mode (needs an MTR
station area), flicker motion by eye (stills only), the sign editor Wear control, poster wear look, Iris
shaders, wear blocks and decals up close, cobweb shimmer, Sodium sorting of overlapping decals. Not built:
train-sag (ask), recipes, steel rust variants, disruption-owned construction props.
- Generators: `tools/atmo_common.py` (shared upsert helpers), `tools/gen_atmosphere_assets.py`.
