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

## PLANNED: curved platform blocks

Goal: platform edges that follow a curved MTR track instead of stair-stepping, with fillers where the
chord gap needs them — the Union Square / South Ferry picture end to end.

1. **`platform_edge_curved`** — the platform edge with its nosing cut along a line inside the block:
   `facing` (4) × `a` × `b` (edge offset at the block's left / right end, 0..16 px in 2 px steps, 9 × 9)
   = 324 states. Generated models: the top stepped at 1–2 px resolution (JSON can only rotate 22.5°,
   and Thomas's Sodium ignores the Fabric renderer API — no emitBlockQuads tricks), the tactile strip
   following the cut, collision to match. `PlatformHelper` so doors open. No SLAB/CLEAN adoption (it
   would multiply the state count; measure on the dedicated server if added).
2. **Curved Platform Creator** (bridge-creator pattern: two node clicks on the platform rail, rails read
   on the TSC thread, preview = the build, per-player undo). For every cell along the rail it computes
   the edge line at `CAR_HALF_WIDTH + clearance` with `GapFillers.clearance` and places a curved edge
   block with the right `a`/`b`; optionally it places **gap fillers** instead where the chord gap at a
   door would exceed a threshold (outside of the curve, near mid-car), each filler with its reach
   precomputed — so a curved platform comes out "Union Square" in one go.
3. **Curved filler plates**: a filler in a curved row keeps its straight plate (the plate lands against
   the car side, which is straight); only its body's nosing uses the curved edge's cut. Needs a
   `gap_filler` variant carrying `a`/`b` or a companion cut block — decide when building (state count:
   324 × 12 reach × 4 phase is too many for one block; likely the filler keeps a straight nosing and the
   creator only puts fillers where the cut is shallow).
4. Open questions for Thomas when we get there: platform height/clearance defaults per rolling stock
   (IRT 8'9" vs B-division 10' → half-width 1.33 vs 1.52), whether South Ferry's "first five cars only"
   (a platform shorter than the train, doors past the end stay shut) should be modelled.
