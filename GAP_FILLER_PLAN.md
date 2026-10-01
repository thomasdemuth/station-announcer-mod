# Gap fillers + curved platforms

Thomas (2026-09-25): "build the gap fillers first with planned curved platform blocks. use union sq
and old south ferry as examples of looks and function. include a minimum dwell time for the filler
to extend and retract. the doors should have a delayed opening until the fillers are extended."

## Reference: what the real ones do

- **14 St–Union Square (IRT Lexington, downtown platform, both tracks).** The 1904 platform is on a
  curve; once the IRT went to cars with middle doors the gap at those doors needed filling. Metal
  grate sections that "shoot out when the train arrives", automated since the 1960s (motion-sensed
  hydraulics today; new design 2004 serviceable from the platform). Loud bang as they move; trains
  dwell longer because of them. Source: Wikipedia "Platform gap filler", Atlas Obscura, whatisthis.nyc.
- **Old South Ferry outer loop (IRT, closed 2009, briefly back 2013).** Loop so tight only the first
  five cars platformed. "Mechanical moving sections that pop out against the coach": unlatched, they
  slide on sloping rails and rollers and bump to a halt against the car side; the train creeping
  forward shoved them back upslope to latch. Source: NYSkies "Loopy loop at South Ferry",
  Forgotten NY, michaelminn.net photos.
- **Interlock**: fillers must be home before the train may move (train-mounted fillers report
  "doors closed" only once retracted; platform fillers are tied to the signals).

## What was built (3.3.x dev tree, 2026-09-25)

| id | style | plate | motion |
|---|---|---|---|
| `gap_filler` | Union Square | steel grate, yellow safety edge, rubber nosing | hydraulic: level, constant speed, stops dead, bang |
| `gap_filler_loop` | South Ferry loop | worn riveted diamond plate, white painted edge | rolls out AND DOWN (1 px per 8 px travel), gathers speed, slows into the latch |

Both are platform-edge blocks (`implements PlatformHelper` → MTR opens doors against them, like
`platform_edge`), with the edge's own tactile strip, so they drop into a run of `platform_edge`.
OPERATIONS tab. Brush = settings screen.

**Files:** `mtr/GapFillerBlock` (+ `GapFillerBlockEntity`, `GapFillerPhase`, `GapFillers` = registration,
link, packet, store lifecycle), `mtraddon/GapFillerEngine` (simulator-side sequencing),
`mtraddon/GapFillerStore` (per-platform timing + registered blocks, `<save>/station-announcer-addon/
gap_fillers.json`), `client/mtr/GapFillerRenderer` (moving plate), `GapFillerScreen` (FlatUi),
`GapFillersClient`. Mixin hooks in `VehicleMixin` (+ `closeDoors` invoker on
`VehicleExtraDataAccessor`). Assets: `tools/gen_gap_filler_assets.py` (never hand-edit).

### The stop sequence (GapFillerEngine)

MTR 4.0.1 stock (bytecode): doors open from 1 s after stopping, close at `max(D/2, D − 4.2 s)`,
`startUp` refused until 4.2 s after the doors closed. `elapsedDwellTime` is CAPPED at D and late trains
catch up by shortening it — so the engine keeps its own sim-clock timeline.

At a platform with ≥1 registered filler (`GapFillerStore.active()`):

1. **EXTENDING** from the tick the train comes to rest. MTR's own `openDoors()` inside
   `simulateStopped` is **redirected** and refused → the doors stay shut (Thomas: delayed opening).
2. **EXTENDED** after `extend` → doors may open (MTR's flow, or ours from the `startUp` gate when the
   stop has outrun MTR's door window).
3. Our door close at `closeAt = stopStart + D' − retract − 3.2 s`, where
   `D' = max(D, minimumDwell, extend + 4 s + 3.2 s + retract)` → the **minimum dwell** (per platform,
   default 20 s Union / 25 s Loop, 0 = only the sequence floor). The door-obstruction roll happens
   HERE (plates are out, bouncing the doors is safe) and never again at this stop.
4. 3.2 s later (door animation) and nobody standing on a plate (block entity occupancy check, capped
   30 s) → **RETRACTING**; after `retract` → **RETRACTED** → `startUp` PROCEEDs (skipping the
   obstruction roll). With D' == D the train still leaves on time.

Holds: a hold while EXTENDED keeps the doors open (grace 1.5 s before our close resumes); a hold after
retraction started re-extends first and reopens only when out. Manual driving: fillers extend on stop,
a door open while not EXTENDED is bounced shut (and re-extends), power is refused until the doors are
closed and the fillers home; the driver's close / attempt to leave starts the retraction.
Instant Deploy: states stamped > 30 s ahead of the sim clock start the stop afresh (CLAUDE.md rule).

World side: each filler BE (every other tick) copies `GapFillerEngine.phaseFor(platform)` into the
`phase` blockstate (collision: plate solid in EXTENDED/RETRACTING), plays move/bang sounds on every
third block of a run, reports occupancy. Signals untouched for 3 s (vanished train) fall back to
RETRACTED. The renderer slides the plate toward the phase at the platform's extend/retract speed.

### Link + reach

Unlinked fillers look for the nearest platform rail IN FRONT of the edge face (≤ 4 blocks, ±3 y) every
10 s — covers item placement, /setblock, /fill, pastes. Reach (blockstate `reach` 1..12 = 2..24 px) is
automatic until set by hand: `gap = distance(face, rail centre) − 1.5 (car half-width) + chord bulge
+ 2 px press`, where the chord bulge `L²/8R` (L = 20 blocks) applies when the face is on the OUTSIDE of
a curve — a car body is a chord, its middle swings away from an outside platform, which is exactly
where the IRT's middle doors are. `GapFillers.clearance(...)` is public for the curved platform work.

### Verification status

- javac: whole tree green (rig classpath, 2026-09-25). Offline renders (render_scene.py): home / half /
  full plates both styles, strip continuous with `platform_edge`.
- NOT yet: Gradle build, rig boot (mixin apply), a real train at a filler platform (door delay,
  close timing, retraction, departure), holds + obstructions at a filler stop, manual driving, the
  brush screen clicks, sounds in game, plate lighting, reach auto on a real curve.

## BUILT (2026-09-25): curved platform edges + Curved Platform Creator

- **`curved_platform_edge`** (`mtr/CurvedPlatformEdgeBlock`, PlatformHelper): the concrete edge cut
  along a line through the block + the raised tactile strip (8 px, platform_edge_strip texture sampled
  ALONG the cut). `facing` (track side) × `cut_a`/`cut_b` 0..20 = depth −16..24 px in 2 px steps at the
  block's left/right end (values outside 0..16 still set the cut's slope; negative = strip-spill cell
  behind the cut) = 1,764 states. Drawn by the shared `client/material/ShapeModel` (Java quads per
  state via a Fabric BlockStateResolver — Sodium meshes plain getQuads) with
  `client/mtr/CurvedEdgeGeometry` (Sutherland–Hodgman clip of the cell; slanted cut face; strip band).
  Collision: 2 px strips.
- **Curved Platform Creator** (`mtr/ItemCurvedPlatformCreator` + `CurvedPlatforms.build`): stand on
  the platform, click the platform track's two nodes. Samples the rail every 0.25 block; edge =
  rail + normal × (1.5 + min(1.5, 25/R)) on the player's side (a 20-block car's middle bulges in and
  its ends swing out by ≈ 25/R, so no car clips the edge); every cell near the line gets a cut from ray
  intersections at its two face corners (shared corners → identical depths → a continuous edge);
  floor sticking into the gap is trimmed (never MTR blocks / block entities); right-click air = undo
  (in memory, per player). Dev: `/rigcurve build <player> x1 y z1 A1 x2 z2 A2` runs it on a synthetic
  MTR RailMath, `/rigcurve undo <player>`.
- Verified on the rig (screenshots 2026-09-25 19:55–19:56): radius-35 synthetic curve, 104 edge cells,
  every cut corner within 0.08 block of the true edge circle, strip + cut face continuous. Also ran on
  Sea Cliff's real 66° platform (worked, then restored from the original save — Thomas's rig setup).
  Two fixes found there: quad winding now from Newell's normal (clipped polygons with collinear first
  vertices were culled → saw-tooth holes) and the wider cut encoding (clamping bent the cut).
- **Curved gap fillers** (`mtr/CurvedGapFillerBlock`, `curved_gap_filler` / `_loop`): the curved edge's cut +
  strip with the filler slot (geometry `CurvedEdgeGeometry(slotFloor)`: base + deck nosing, slot floor/ceiling,
  inner cheeks, back wall) and the same engine / block entity / link / brush screen as the straight ones
  (`GapFillerHost` interface). The plate slides straight out of the track face (model −z) with its tip along
  the cut, so neighbouring plates stay side by side; reach lives in the BLOCK ENTITY (cut × reach × phase
  would be 85k states) and collision reads it (`dynamicBounds`); auto reach measures from the cut's middle
  × 1/cos of the cut angle. Creator modes (sneak-right-click air cycles; item NBT `FillerMode`): edges only /
  + Union Sq fillers / + South Ferry fillers — full-width cuts (depth 0..16 at both ends) become fillers.
  Verified at Sea Cliff with a real train (21 fillers linked "Sea Cliff · L", reach 4..24 px, plates out
  under the open doors along the curve; screenshots 20:29), then restored from a snapshot.
- Not done: rolling-stock-specific half widths, South Ferry's "first five cars only".
