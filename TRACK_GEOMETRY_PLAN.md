# TRACK GEOMETRY — rail tilt (cant) + compound curves + track editor

Started 2026-10-02. Thomas: "add two features: rail tilt, and multiple curve radii on a track
section; right-click a track with a brush, see it in a GUI, add points to tilt it, adjust angles and
geometry; see multiple tracks at once at a node where tracks connect". Then: "work until complete,
I trust you, maybe divide into subagents".

## Thomas's answers (do not re-ask)

| Topic | Answer |
|---|---|
| Tilt effect | Rails bank visually + trains lean + "suggest cant" button. NO rider camera roll. |
| Tilt range | up to 45°. Suggest stays realistic (capped by `cantCap`, default 12°). |
| Pivot | the INNER (low) rail. |
| Geometry | CUSTOM free-form chain between MTR's two nodes; trains follow it (mixin on RailMath). |
| Pieces | arcs + straights + transition spirals (clothoids). No Bézier. |
| Height | editable in the profile strip (grade points + vertical curves), still meets both nodes. |
| Solver | lock/free per value; unlocked values are solved so the chain meets the far node; red if impossible. |
| Node headings | stay on MTR's 16-direction grid (everything between nodes is free). |
| Editor | plan + profile 2D, with an Editor / 3D switch. |
| Junction | see all tracks at the node, edit any one, plus a node panel (shared things at the node). |
| Entry | REPLACES MTR's Rail Shape Modifier screen (brush + right-click a node). Ours must still offer MTR's shape (quadratic/two radii), vertical radius, styles, flip styles. |
| Order | build everything. |

## Why we build it ourselves

MTR 4.0.1 (and every 1.20.4 release up to 4.0.5) has NO tilt. Unreleased MTR master (mtr-src/tsc)
has 7 tilt points per rail and leaning vehicles — a design reference only.

## MTR 4.0.1 facts (from the decompiled 4.0.1 jar — `scratchpad/mtr401src`, Vineflower)

- `Rail` builds ONE `RailMath` in canonical node order (`position1.compareTo(position2) <= 0`,
  x then y then z): `railMath = reversePositions ? new RailMath(p2,a2,p1,a1,…) : new RailMath(p1,a1,p2,a2,…)`.
  `railMath` is `public final`.
- RailMath = at most two pieces (arc/straight each): fields h1,k1,r1,tStart1,tEnd1,reverseT1,isStraight1 (+2).
  Piece length = |tEnd − tStart| (t is arc length for arcs AND distance for straights).
  `reverseT=false` ⇒ angle increasing ⇒ heading increasing ⇒ RIGHT-hand curve in Minecraft.
- Consumers of RailMath (all must see our path): `getPosition(raw, reverse)` (PathData → vehicles,
  RailAction), `getLength()` (SidingPathFinder, PathData, Siding length, RailAction, stats),
  `render(cb, interval, off1, off2)` (RenderRails, SavedRailBase area), `getHorizontalRadii()` (stats),
  `getVerticalRadius()/getMaxVerticalRadius()/getShape()` (PathData, RailModifierScreen),
  `minX..maxZ` final fields (Rail area at construction, MinecraftClientData culling), package-private `isValid()`.
- Brush + right-click a node → `BlockNode.onUse2` (client) → `ClientPacketHelper.openRailShapeModifierScreen(hexId)`
  → `RailModifierScreen` (shape toggle, vertical radius ±, Styles…, Flip styles). It saves through
  `PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addRail(Rail.copy(...)))`.
- MTR renders rail STYLES (3D models) per segment: `renderWithinRenderDistance` → `railMath.render(…)` →
  matrix rotateY(yaw) · rotateX(pitch) · tiny rotateZ jitter → `railResource.render(storedMatrix, light)`;
  the flat default rail via `IDrawing.drawTexture` corners. A segment callback carries ONE y per end,
  so banking needs our own drawing for tilted rails.

## Architecture

```
com.stationannouncer.track            PURE JAVA (Gson only) — unit-tested against MTR's real RailMath
  TrackProfile   data: pieces (STRAIGHT/ARC/SPIRAL, locks), vertical mode MTR|CUSTOM + grade points,
                 tiltA/tiltB + tilt points, smoothTilt, cantCap; node identity + MTR angles + headings
  TrackPath      evaluated geometry + exact RailMath facade (getPosition/getLength/render/bounds/radii…)
                 + forEachSegment (render with distance ranges) + tilt(s[,reversed]) + bank(u,h,deg)
  TrackSolver    lock/free min-norm damped Gauss-Newton (3 equations: end x, z, heading)
  TrackEdit      fromMtr (exact import), fitChain (fallback), addEasements/removeEasements/split,
                 equilibriumCant, suggestCant
  TrackKey       canonical key "ax,ay,az;bx,by,bz"
  TrackRegistry  SERVER (per dimension) / CLIENT (+ PREVIEW) maps, version counter
com.stationannouncer.mtr.track        MTR side (common)
  TrackRailMathAccess  duck interface implemented by the RailMath mixin
  TrackRails           key(rail), path(rail), editableProfile(rail), access(rail)
  (server/common agent adds: store, service, packets, cleanup, path regeneration)
com.stationannouncer.client.track     client
  ClientTrackNetworking  sendSave / sendReset / register   (server/common agent)
  TrackView3D            3D orbit pane                      (3D agent)
  TrackEditorScreen …    the editor                         (editor agent)
  tilted rail rendering                                     (rail-render agent)
  vehicle lean                                              (vehicle agent)
com.stationannouncer.mixin            mixins (RailMath, RenderRails, RenderVehicles, BlockNode/ClientPacketHelper)
```

### Conventions (also in TrackProfile's javadoc — the single source)
- Chain runs A→B, A = canonical first node; x/z world coords include the +0.5 node centre; y = block y like MTR.
- Heading θ = atan2(dz, dx); κ > 0 = θ increasing = RIGHT-hand curve; right of travel = (−sin θ, cos θ).
- Tilt + = right side low (lean right). Pivot = low rail at ±HALF_GAUGE (0.7175). A vehicle/renderer
  walking the rail B→A uses `tilt(s, true)` (sign flips).
- A profile only applies while the rail's node positions AND MTR node angles match (`TrackProfile.matches`)
  and the shape is not CABLE.
- An imported MTR shape that does not reproduce MTR (`!TrackPath.closes()` or positions differ) is
  replaced by `TrackEdit.fitChain` (MTR draws a few node/angle combinations kinked or wrapped).

### Persistence / sync / apply (server/common agent)
- Store: `<save>/station-announcer-addon/track_profiles.json` = {dimension id: {key: profile}}; atomic write.
- C2S save/reset (validated: distance to a node, permission like other MTR rail edits, sanitized,
  re-built server-side and must `closes()`); S2C full sync on join/dimension change, delta on change.
- After a change the rail must be REBUILT on both sides (bounds are final fields captured at
  construction) and every siding path containing the rail regenerated (cached distances).
- Orphans (rail gone, nodes moved/rotated) are pruned.

## Rules for every agent

- NEVER run Gradle, `runServer`, `runClient`, or touch the running rig (another session uses it;
  Gradle runs kill rig JVMs). Compile check = the javac-only script:
  `/private/tmp/claude-501/-Users-thomasdemuth-Documents-Coding-Projects-Station-Announcer-Mod/7f03f5c5-e46c-4bf7-9d6f-3efcf4c9cc3a/scratchpad/javac_all.sh`
  (whole tree, ~5 s). Core unit tests: see "Tests".
- Decompiled MTR 4.0.1 source: `…/scratchpad/mtr401src/mtr/{core,mod}` (authoritative; `mtr-src/` is
  master and has DRIFTED). Class files: `…/scratchpad/mtr401cls`. Use `javap -p -c` on them for
  bytecode-level mixin targets (lambda names, ordinals).
- Only edit files you own (listed in your brief). Do NOT edit `station_announcer.mixins.json`,
  `AddonInit`, `AddonClientInit`, `StationAnnouncerClient`, `MtrPidsClient`, `TrackProfile/Path/…`
  core files — report what you need registered (mixin class names + side, init calls) and any core
  API you need, in your final report. The coordinator wires it.
- Other Claude sessions edit this tree; foreign compile errors in files you don't own are not yours —
  report them, don't fix them.
- Match surrounding code style (4-space indent, final locals, javadoc on non-obvious things).

## Tests
```
J=~/.gradle/caches/modules-2/files-2.1/maven.modrinth/minecraft-transit-railway/FABRIC-4.0.1+1.20.4/*/minecraft-transit-railway-FABRIC-4.0.1+1.20.4.jar
javac -d OUT -cp $J src/main/java/com/stationannouncer/track/*.java
java -cp OUT:$J tools/track_test/TrackTest.java
```
2026-10-02: 4241 checks, 0 failures — 600 random real MTR rails import EXACTLY (positions ≤1e-4,
lengths 1e-6), solver keeps imports still, compound / S-curves / pinned drags close, easements ramp
curvature linearly, suggest-cant sign + ramps, custom height + vertical curves, bank pivot.

Mixin logic (server/common agent): `TrackRailMathStateTest` — compile the core + `mtr/track/TrackRailMathState.java`
+ `mtr/track/TrackSide.java` with the MTR jar, `java -cp OUT:$J tools/track_test/TrackRailMathStateTest.java`.
2026-10-02: 635 checks, 0 failures (no profile = MTR bit-for-bit; saved import = MTR ≤1e-4; edit → length/positions/
bounds follow TrackPath; unrelated version bumps keep the built path; mismatched angles / non-closing / cable /
removed → MTR + MTR bounds restored; client vs server side isolation incl. previews; asMtr bypass; 300-rail import sweep).

## BUILT — server/common (2026-10-02)

Files: `mixin/RailMathMixin` (common), `mixin/TrackSidingPathsAccessor` (common), `mtr/track/{TrackRailMathState,
TrackSide, TrackJson, TrackStore, TrackService, TrackRails}`, `client/track/ClientTrackNetworking`,
`tools/track_test/TrackRailMathStateTest.java`.

- **Lazy, no rebuild.** Every RailMath gets a `TrackRailMathState` at the end of its constructor (the constructor's own
  `render`/`getMaxVerticalRadius` calls run before that, so they stay MTR's). Queries compare `TrackRegistry.version()`
  with the version the state resolved at (one volatile long compare) and re-resolve only when it moved; a resolution
  whose profile OBJECT is unchanged keeps its path, and built paths are shared per side by key, so the many RailMath
  copies of one rail build it once. Bounds `minX..maxZ` are `@Mutable` and rewritten whenever the resolved path
  changes (MTR's own restored when it goes away). Rails are NEVER re-created by us.
- **getPosition/getLength are `@Overwrite`s** (bodies = MTR 4.0.1's, bytecode-checked): a cancellable `@Inject` would
  allocate a CallbackInfoReturnable per call for every rail. render / getHorizontalRadii (= TrackPath min/max arc
  radius) / getVerticalRadius / getMaxVerticalRadius are HEAD-cancelled only while a path applies.
  `TrackRailMathState.asMtr(...)` switches the overrides off for the current thread (how `mtrPosition` reaches MTR's
  original y — `getPositionY` calls `getLength()`).
- **Side = constructing thread** (`TrackSide`): client ⇔ built on the Minecraft client thread (recorded by
  `ClientTrackNetworking.register()` + every client tick). Verified in 4.0.1: client rails come from `runClient`
  (`RegistryClient` → `MinecraftClient::execute`), path-cache `defaultRail()`, placement/brush previews,
  RailModifierScreen — all client thread; server rails from FileLoader workers, the simulator thread
  (`OperationProcessor` re-deserialises every `update_data`) or the server thread. Weak spot: a client rail built
  off-thread by another mod would read the SERVER map (empty on a multiplayer client → MTR shape).
- **Client vehicle paths use `PathData.defaultRail()`** (MTR rebuilds them from the path's start/end positions +
  angles on every `PacketUpdateData` — `writePathCache` against an EMPTY `MinecraftClientData`); their per-node angles
  equal the real rail's (`Rail.getAngles` normalisation is symmetric), so the profile matches and client trains follow
  the custom path too.
- **MTR caches path DISTANCES, not geometry.** `PathData.start/endDistance` are cumulative rail lengths baked at depot
  generation and vehicles evaluate `getPosition(distance − startDistance)`. MTR itself never re-paths after a rail
  edit (`UpdateDataRequest.update` → `Data.sync` only re-links path caches). So after a save/reset, IF the rail's
  length changed by > 1e-4 (tilt-only / unchanged imports don't), `TrackService` regenerates every depot whose
  `Depot.getPath()` or whose sidings' `pathSidingToMainRoute`/`pathMainRouteToSiding` cross the rail, with MTR's own
  `Depot.generateDepots(simulator, depots)` (= the dashboard's generate; clears + redeploys those sidings' trains, as
  any MTR regeneration does), 40 ticks after the last change.
- **Culling boxes:** `MinecraftClientData.RailWrapper.startVector/endVector` are copied from the bounds ONCE when the
  wrapper is created and never refreshed by MTR (not even when `sync()` swaps in a re-sent rail — an MTR bug for its
  own shape edits too); `ClientTrackNetworking`'s client tick re-copies them (they are mutable `Vec3d`s) whenever the
  registry version moved.
- **Store/sync/validation:** see `TrackService`'s javadoc. Store loads at SERVER_STARTING (before MTR's
  SERVER_STARTED builds the simulators, so rails resolve as they load). Dimension ids = MTR world ids
  (`"namespace/path"` = `Simulator.dimension`). C2S save JSON ≤ 30 000 chars (vanilla C2S payload cap 32767 bytes;
  a maximal profile is ~10 KB). Orphans (rail gone, became CABLE, nodes re-angled) pruned every 10 s on the
  simulator thread, removals synced.
- **Known gaps:** a SIDING rail's `Siding` keeps the length it was created with (car capacity) — not updated;
  client editor PREVIEWS change client-side rail lengths, so client trains crossing a previewed rail jump while the
  preview is up (server paths are unchanged until Save); between a length-changing save and the regeneration (≤ 2 s)
  trains on that rail may jump once.
- Lang keys used with English fallbacks (add to en_us.json if wanted): `msg.station_announcer.track.saved`, `.reset`,
  `.rejected` ("Track shape not saved: %s"), `.rejected.{invalid,no_simulator,no_rail,cable,changed,open,far,permission}`.

## Status
- [x] Core library + tests (coordinator)
- [x] Server/common integration (RailMath mixin, store, packets, regen, cleanup) — javac + out-of-game state test only; needs wiring (below) and a rig pass
- [x] Tilted rail rendering (TrackRailRenderer + RenderRailsTiltMixin; RailBankCheck 58448/0)
- [x] Vehicle lean (5 *Lean* mixins, MixinExtras; VehicleLeanCheck 1099/0)
- [x] Editor screen (TrackEditorScreen/Draft/Gfx/DevHooks + RailModifierScreenOpenMixin; discard prompt, auto-Custom height)
- [x] 3D view (TrackView3D + Scene/Camera/Projector; offline PNG check)
- [x] Wiring (mixins json, AddonInit, AddonClientInit, #track- hook); cross-component review (2 fixes) + 3 editor fixes; gradle build green
- [ ] FIRST BOOT + in-game verification (rig was another session's — starting it was refused), version bump (3.5.0), README, deploy

## REDESIGN ROUND 2 (2026-10-04) — Thomas after a first look: "a little confusing to use and hard"

He wants: click to add points, easier auto tilt, schematic + 3D + world views, editing in all three.
Answers so far (do not re-ask):
- Views: TABS (Schematic | 3D | World), profile strip (height + tilt) stays below.
- World view: a PANE inside the editor showing the real world (orbiting camera, like the sign
  editor's "In world" view) with draggable handles.
- Clicking the track: a MODE TOOLBAR picks what a click adds (e.g. Bend · Tilt · Height).
- Tilt: ONE-CLICK "Auto tilt" button (manual otherwise).
- Bend tool = BÉZIER POINT: click the track → a point with two tangent handles (pen-tool style);
  drag point or handles, the track stays a smooth Bézier meeting both nodes.
- Multi-track = PARALLEL TRACKS FOLLOW: edit one track of a double/triple line, the others reshape
  to stay parallel at their spacing (same tilt).
- What confused him: TOO MANY PANELS, the PIECES/LOCKS model, HARD-TO-GRAB HANDLES.
- Auto tilt = REALISTIC cant (radius + speed limit, cap ~12°, ramps in/out).
- ADDED same day: Bézier curves as a piece/tool ("beizer curves and stuff to add") — reverses the
  round-1 "no Bézier"; and EDIT MULTIPLE TRACKS AT ONCE.

### Round 2 play-test (2026-10-05) — Thomas: "mostly though, really good work!!"
- Handles easy to grab ✓. Wants: number fields SCRUB like After Effects (drag the number itself;
  Shift coarse, Ctrl/Cmd fine); basic MTR settings easier to reach/see (no closed disclosure);
  World view "messy and glitchy (things un-render and I fly too fast)" → smoothing, gentler speeds,
  no culling pops, stay within loaded chunks.
- Fixed by the coordinator before that test: World camera buried underground (terrain-clip ray
  started at an orbit target below the rails) — clip now starts at rail level + 1.5.
