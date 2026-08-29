# PROGRESS.md — MTR Dispatch Addon

Running log, one section per feature agent. Read ARCHITECTURE.md first.

Environment: Fabric, MC 1.20.4, MTR FABRIC-4.0.1+1.20.4 (required dep), inside the
Station Announcer mod (additive only — see ARCHITECTURE.md §0).

---

## Feature Agent 1 — Addon skeleton + Feature 1 (platform hold rules) — 2026-08-07

### What was built

**A) Shared addon skeleton** (per ARCHITECTURE.md §3):

- `src/main/java/com/stationannouncer/mtraddon/AddonInit.java` — registered from
  `StationAnnouncer.onInitialize()` inside the existing `isModLoaded("mtr")` block
  (single added call, like MtrStationDecor). Wires networking, SERVER_STARTED
  (config preload + store load), SERVER_STOPPING (store flush), SERVER_STOPPED
  (engine runtime-state clear), and the join-sync hook.
- `mtraddon/AddonServerConfig.java` — gson, `config/station-announcer-addon.json`.
  Keys: `editPermissionLevel` (2), `holdRules.enabled` (true),
  `holdRules.holdArrivalCacheMillis` (500), `holdRules.maxHoldSeconds` (120).
  `get()` is a volatile read after first load because simulator threads call it on
  every startUp attempt — no synchronized getter on that path.
- `mtraddon/AddonStore.java` — per-world JSON at
  `<save>/station-announcer-addon/data.json`, §4 schema. holdRules is fully
  typed; `dwellOverrides` / `liftDoors` / `platformGroups` are preserved verbatim
  as JsonObjects for later agents to formalize (no migration needed). Mutations on
  the server thread only; writes are debounced 2 s onto a single daemon executor
  (serialization under the store lock, file I/O off-thread), plus a synchronous
  flush on SERVER_STOPPING.
- `mtraddon/AddonSnapshots.java` — volatile immutable
  `Long2ObjectOpenHashMap<HoldRule>` (relocated fastutil, allocation-free
  `get(long)`), republished by the server thread on every store change; republish
  also clears the engine's runtime caches.
- `mtraddon/AddonNetworking.java` — `addon_hold_rules` S2C full-map sync (on join
  + rebroadcast after every change), `addon_update_hold_rule` C2S (Save/Clear).
  C2S validated: op level `editPermissionLevel`, watched count capped at 16,
  seconds clamped 5–120, self/duplicate watched ids dropped server-side.
- `client/mtraddon/AddonClientInit.java`, `AddonClientConfig.java`
  (`config/station-announcer-addon-client.json`, key `showHoldRulesButton`),
  `ClientHoldRules.java` (client mirror of the rules, replaced wholesale by the
  sync packet, cleared on disconnect). Registered from StationAnnouncerClient
  inside its existing isModLoaded block (single added call).
- `src/main/java/com/stationannouncer/mixin/` + `station_announcer.mixins.json`
  (referenced from fabric.mod.json `"mixins"`). build.gradle needs nothing extra —
  loom 1.10 configures the mixin AP/refmap automatically once the config is
  referenced.

**B) Feature 1 — platform hold rules:**

- `mtraddon/HoldRuleEngine.java` + `mixin/VehicleMixin.java`. The mixin
  HEAD-cancels `org.mtr.core.data.Vehicle.startUp(JJ)V` (javap-verified against
  4.0.1, as were VehicleSchema/PathData/PathDataSchema/Utilities/Siding/Data/
  Simulator/ArrivalResponse/Platform/Position members). The mixin extends
  `VehicleSchema` to reach the protected `railProgress`/`data` fields and shadows
  the public `vehicleExtraData`.
- Guard order (cheapest first): `holdRules.enabled` → snapshot `isEmpty()` →
  `data instanceof Simulator` (startUp also exists clientside) → stopped-at-
  platform check (`Utilities.getIndexFromConditionalList`; requires
  `railProgress == path.get(index).getStartDistance()` — the exact comparison
  simulateStopped makes — with segment `index-1` having `dwellTime > 0` and
  `savedRailBaseId != 0`, so mid-route signal stops never match) → rule lookup →
  arrival condition.
- Condition: any watched platform with an arrival in `(now, now + N*1000]`,
  computed by iterating `simulator.sidings` → `getArrivals(now, platform, 3, list)`
  and cached per watched platform for `holdArrivalCacheMillis` (500 ms) in a
  ConcurrentHashMap — held vehicles retry every tick and share the cache.
- Deadlock guard: per-vehicle `{firstHeld, lastHeld}` timestamps; hold capped at
  `maxHoldSeconds`. The entry survives the give-up (so retries can't restart the
  timer) and resets only after the vehicle has been gone >5 s (a held vehicle
  retries every tick, so any real gap means it departed).

**GUI entry point:** Fabric `ScreenEvents.AFTER_INIT` adds a "Hold rules…" button
(bottom-right) to MTR's `PlatformScreen` — no mixin needed: javap showed
`ScreenExtension extends ScreenAbstractMapping extends net.minecraft...Screen`,
so the event's screen IS the PlatformScreen and plain `instanceof` works. The
edited `Platform` is read from `SavedRailScreenBase.savedRailBase` (protected)
via cached reflection; button shown only with `MinecraftClientData.hasPermission()`.
It opens `client/mtraddon/HoldRuleScreen.java`: shared `PlatformPicker` seeded at
the platform's mid position (same-station platforms, nearest-first), shared
`IntSlider` 5–120 s, Done/Cancel/Clear. Lang keys added under
`gui.station_announcer.hold_rules.*`.

### Thread-safety notes

- The mixin runs on the per-dimension SIMULATOR thread. It reads only the volatile
  config/snapshot, the simulator's own `Data` (safe: we are on that simulator's
  thread), and two ConcurrentHashMaps; it never touches the MC world or packets.
- Store mutations + snapshot publishes happen on the server thread; file I/O never
  runs on a server or simulator tick.

### Known limitations

- Cancelling before `closeDoors()` means a held train keeps its doors open —
  accepted per §5 (reads as "held for connection").
- Terminus reversals and repeat-route wraps adjust `railProgress` before calling
  startUp, so holds deliberately don't apply there; manual trains stopped exactly
  at a platform boundary ARE subject to holds (spec doesn't exempt them).
- After `maxHoldSeconds` expires the train departs on its next unblocked attempt
  and cannot be re-held until it has left the boundary (>5 s gap heuristic).
- The picker lists the edited platform itself; ticking it is dropped on save
  (client and server both filter it).
- Rules referencing another dimension's platform never fire (platformIdMap miss →
  cached "no arrival"); the GUI only offers same-station platforms anyway.
- Not compiled or in-game tested by this agent (no-Gradle rule); orchestrator to
  verify. The reflection field name `savedRailBase` and the `ScreenExtension`
  vanilla-Screen finding were verified by javap, not at runtime.

### Files touched

New: `mtraddon/{AddonInit,AddonServerConfig,AddonStore,AddonSnapshots,AddonNetworking,HoldRuleEngine}.java`,
`client/mtraddon/{AddonClientInit,AddonClientConfig,ClientHoldRules,HoldRuleScreen}.java`,
`mixin/VehicleMixin.java`, `resources/station_announcer.mixins.json`.
Modified (allowed hook-ins only): `StationAnnouncer.java` (+1 line),
`client/StationAnnouncerClient.java` (+1 line), `fabric.mod.json` (mixins entry),
`assets/station_announcer/lang/en_us.json` (+7 keys).

---

## Feature Agent 2 — Per-route dwell time overrides at platforms — 2026-08-07

### What was built

MTR bakes each platform's single dwell time into the generated path
(`SidingPathFinder` creates the arrival segment with `platform.getDwellTime()`),
so every route calling at a platform dwells equally. Feature 2 makes dwell
overridable per route at each platform (express 10 s where the local dwells
25 s), falling back to the platform default when no override exists.

**Server core** — `mtraddon/DwellOverrideEngine.java` + `mixin/SidingMixin.java`
+ `mixin/PathDataSchemaAccessor.java`:

- `SidingMixin` (target javap-verified against 4.0.1) hooks TWO cold path-
  generation points, both non-cancelling HEAD injects delegating to the engine:
  - `Siding.generateRoute(Lorg/mtr/core/data/Platform;Lorg/mtr/core/data/Platform;IJ)V`
    — called once per siding by `Depot.tick()` right after the depot's shared
    main path completes. The engine rewrites the depot path's platform-arrival
    segments before any siding copies them or builds timetables (the `PathData`
    objects are shared references into `pathMainRoute` / `VehicleExtraData`, so
    timetable and runtime dwell stay consistent). Idempotent across the depot's
    N sidings.
  - `Siding.finishGeneratingPath(Z)V` (private, javap-verified; called only from
    path-finder completion callbacks, never per tick) — covers the depot route's
    FIRST stop, whose dwell is not in the depot main path at all: it is baked
    into the last segment of the per-siding `pathSidingToMainRoute`, which only
    exists once this callback fires. The HEAD inject lands before the method
    body's `generatePathDistancesAndTimeSegments()`.
- Stop→route matching replicates `Depot.writeRouteCache`'s consecutive-
  duplicate-platform collapsing (null platforms skipped without updating the
  previous id) over `depot.routes` → ordered stop list; the depot main path's
  dwell segments (`getSavedRailBaseId() != 0 && getDwellTime() > 0`) correspond
  in order to stops 1..n-1 (each `SidingPathFinder(i → i+1)` tags only its final
  segment), matched monotonically and defensively (an unmatched segment keeps
  its generated default). Where a platform is both the last stop of one route
  and the first of the next (collapsed to one stop), the earlier route's
  override wins with the later route's as fallback.
- `PathDataSchemaAccessor`: `@Mutable @Accessor` setter for
  `PathDataSchema.dwellTime` (javap: `protected final long` — final, hence
  `@Mutable`; no setter exists anywhere in TSC).
- **Self-healing:** both hooks only ever see freshly regenerated paths already
  filled with platform defaults, so applying overrides is the only work needed —
  removing an override heals automatically on the next depot regeneration, and
  baked overrides survive restarts inside MTR's saved siding paths (same
  lifecycle as MTR's own dwell).

**Storage / snapshot / sync** (extending Agent 1's skeleton, no refactors):

- `AddonStore`: `dwellOverrides` formalized from the raw-JSON passthrough to a
  typed `Map<Long, LinkedHashMap<Long, Long>>` (platform id → route id →
  millis). Same JSON shape as §4 (`{"<platformId>": {"<routeId>": millis}}`), so
  existing files load without migration. New: `dwellOverridesView()`,
  `setDwellOverrides(platformId, map)` (empty map = clear platform),
  `dwellOverridePlatformCount()`; separate `publishDwell()` so dwell edits do
  not clear Feature 1's hold-rule runtime caches.
- `AddonSnapshots`: volatile `Long2ObjectOpenHashMap<Long2LongAVLTreeMap>`
  snapshot (allocation-free `get(long)`; inner absent-key default 0 = "no
  override", safe because values are clamped ≥ 1000 ms). NOTE: the shaded MTR
  jar does NOT contain `Long2LongOpenHashMap` (MTR relocates only the fastutil
  classes it uses) — `Long2LongAVLTreeMap` is what exists; maps are tiny and
  read only during depot regeneration.
- `AddonNetworking`: `addon_dwell_overrides` S2C full-map sync (join +
  rebroadcast after each change) and `addon_update_dwell_overrides` C2S (full
  per-platform replacement; op level `editPermissionLevel`, route count capped
  at `MAX_ROUTE_OVERRIDES` = 32, dwell clamped 1 s–600 s = MTR's own platform
  dwell ceiling: `SavedRailScreenBase.MAX_DWELL_TIME` = 1200 is HALF-seconds,
  so ARCHITECTURE's "1200 s" reading was off by 2× — we mirror MTR's real max).
- `AddonInit`: JOIN handler now also sends the dwell sync.

**Config keys** (`config/station-announcer-addon.json`):
`dwellOverrides.enabled` (default true) — when off, both mixin hooks no-op with
a single field read (they are cold paths anyway — they only run when a depot
regenerates).

**GUI entry point:** a second button "Per-route dwell…" on MTR's
`PlatformScreen` (own `ScreenEvents.AFTER_INIT` callback in `AddonClientInit`,
one slot above the Hold rules button; reuses Agent 1's cached-reflection
`readPlatform`; client toggle `showRouteDwellButton`, permission-gated like
MTR's own dashboard). Opens `client/mtraddon/RouteDwellScreen`:

- Lists the routes calling at this platform — the exact filtering
  `PlatformScreen` itself does (`MinecraftClientData.getDashboardInstance()
  .routes` → any `RoutePlatformData` at this platform), sorted by display name
  (first language segment), drawn in each route's colour.
- Per route: an Override/Default toggle + min/sec sliders mirroring MTR's dwell
  sliders (minutes 0–10; seconds slider in half-seconds 0–119, captioned as
  x.5 s). Default (unchecked) = platform default; sliders seed from the current
  override, else the platform's dwell. Scrolls (wheel + ▲▼ buttons) when the
  route list outgrows the window.
- Hint line: "Changes apply after the depot regenerates its paths" (dwell is
  baked at generation time).
- "Regenerate depots serving this platform" button — sends MTR's own
  `PacketDepotGenerate(DepotOperationByIds)` via
  `InitClient.REGISTRY_CLIENT.sendPacketToServer(...)`, exactly what the
  dashboard's refresh sends (both javap-verified against 4.0.1); depot ids
  gathered from the routes' public `depots` cache. Wrapped in try/catch and
  disabled after one send.
- Client mirror `client/mtraddon/ClientDwellOverrides.java` (replaced wholesale
  by the sync packet, cleared on disconnect).

### Thread-safety notes

- Both mixin hooks run on the per-dimension SIMULATOR thread (or the server
  thread with `useThreadedSimulation` off). The engine is stateless and reads
  only the volatile config, the volatile immutable snapshot, and the simulator's
  own depot/route/path data (safe: path generation runs on that simulator's
  thread). It mutates only `PathData.dwellTime` on paths still being assembled.
- Store mutations + snapshot publishes stay on the server thread; file I/O stays
  on the debounced executor (Agent 1's machinery, unchanged).

### Known limitations

- **Overrides apply on the next depot regeneration only** (path is baked); the
  GUI says so and offers the regenerate button. Disabling the feature likewise
  leaves already-baked overrides until the next regeneration.
- The first stop of a depot's route cycle is covered via the
  `finishGeneratingPath` hook (its dwell lives in `pathSidingToMainRoute`, not
  the depot main path). `pathMainRouteToSiding` is left untouched — no platform
  dwell lives there (its end saved rail is a siding, dwell 1 ms).
- A platform visited twice in one collapsed stop sequence is matched
  positionally (monotonic walk), so two different routes CAN have different
  overrides at the same physical platform; a route-boundary platform shared by
  two routes is one collapsed stop — the earlier route's override wins, the
  later route's is only a fallback.
- Manual sidings: dwell overrides apply to their generated paths like any other
  (departure is still driver-controlled; the override affects schedule
  computation and ATO dwell only).
- The regenerate button depends on the client-side `Route.depots` cache being
  populated on the dashboard data; if it is empty the click is a silent no-op
  (logged). MTR performs its own server-side handling of `PacketDepotGenerate`
  (same trust model as the dashboard's refresh button).
- 4.0.1's `finishGeneratingPath` body was verified by javap for the signature
  and against the (drifted) master source for call sites; if 4.0.1 ever calls it
  with a stale `pathSidingToMainRoute`, the rewrite is idempotent and clamped to
  the first stop's platform id, so the worst case is a no-op.
- Not compiled or in-game tested by this agent (no-Gradle rule); orchestrator to
  verify. Unicode ▲▼ on the scroll buttons render with vanilla's font; swap for
  "Up"/"Down" text if they show as boxes.

### Files touched

New: `mtraddon/DwellOverrideEngine.java`,
`mixin/{SidingMixin,PathDataSchemaAccessor}.java`,
`client/mtraddon/{ClientDwellOverrides,RouteDwellScreen}.java`.
Modified (extending Agent 1's skeleton as intended):
`mtraddon/{AddonServerConfig,AddonStore,AddonSnapshots,AddonNetworking,AddonInit}.java`,
`client/mtraddon/{AddonClientInit,AddonClientConfig}.java`,
`resources/station_announcer.mixins.json` (+2 entries),
`assets/station_announcer/lang/en_us.json` (+11 keys).

---

## Feature Agent 3 — Multi-sided, multi-door elevators — 2026-08-07

### What was built

MTR lifts hardcode doors on the cab's front (-Z), plus the back (+Z) when
"double-sided". Feature 3 lets each lift have doors on any of the four cab
sides (front/back/left/right, cab space before the lift's world rotation);
which sides actually OPEN at a floor stays automatic — every doorway is checked
per frame with MTR's own `RenderVehicleHelper.canOpenDoors`, exactly how stock
decides front vs back, so a side's doors open only when that floor's landing is
in front of them. Boarding works from every configured side because ALL open
doorway boxes are passed to `VehicleRidingMovement.startRiding`/`movePlayer`
exactly as stock passes its one or two.

**Server: no simulation changes at all.** Lift motion, instruction queue and
door timing remain MTR's; door sides are client presentation + boarding boxes.
The server's only involvement is storing and syncing the config.

**Render path** — `mixin/RenderLiftsMixin.java` (client section of the mixin
config) + `client/mtraddon/AddonRenderLifts.java`:

- The mixin HEAD-cancels `RenderLifts.render(JLorg/mtr/mapping/holder/Vector3d;)V`
  (javap-verified static method, `remap = false`) ONLY when the client's
  door-config map is non-empty — a single static `ClientLiftDoors.isEmpty()`
  read is the O(1) bail, and it is false both when the feature is disabled
  (server then syncs an empty map) and when no lift is configured, so MTR's
  stock path runs untouched in the idle case. The delegate is wrapped in
  catch-Throwable: on the first failure the takeover disarms for the session
  (logged) and every frame falls through to stock — the render loop cannot be
  crashed by this feature.
- `AddonRenderLifts` is a faithful adaptation of MTR 4.0.1's ~160-line method
  (read line-by-line from mtr-src; every member used — LiftWrapper.shouldRender,
  RenderVehicles.getRenderPositionAndRotation/getStoredMatrixTransformations,
  MainRenderer.WORKER_THREAD.scheduleLifts, OptimizedRenderer.renderingShadows,
  ItemLiftRefresher.findPath, the lift-refresher debug path, occlusion culling
  via the RELOCATED `org.mtr.libraries.com.logisticscraft.occlusionculling.*` —
  javap-verified public). Unconfigured lifts take logic identical to stock
  including the same `new ModelLift1(...)` cab. Configured lifts get: a doorway
  box per enabled side (±X boxes mirror the stock ±Z ones with width/depth
  swapped: `Box(±(w/2 - 0.25), 0, -0.75, ±w/2, 0, 0.75)`), an `AddonModelLift`
  cab, and an in-cab floor display above EVERY door side (stock behavior —
  front rotated 180°, back unrotated — extended with ±90° for the sides, via
  the public `RenderLifts.renderLiftDisplay`).

**Model** — `client/mtraddon/AddonModelLift.java`: `ModelLift1` could not be
extended (all parts private, door layout hardcoded in its protected render), so
this is a full copy of its geometry (every cuboid/UV/pivot verbatim from
source, which matches the 4.0.1 jar) parameterized by four door booleans,
reusing MTR's lift textures via `RenderLifts.getLiftResource`:

- Extends `ModelTrainBase` and REUSES its public final render for the stage
  scheduling (LIGHT/INTERIOR/EXTERIOR/ALWAYS_ON_LIGHT layering identical to
  stock). The base class only carries two door-value channels, so
  `renderMultiDoor(...)` maps front/back onto them and stashes the side doors'
  slide amounts (computed with the same `DoorAnimationType.getDoorAnimationZ`
  call the base makes) on the instance before delegating — safe because one
  model instance is created per lift per frame, like stock.
- Each wall renders either the stock door assembly or the stock solid-wall
  piece layout. Side-door assemblies are the stock front/back assemblies
  rotated ±90° about the cab centre (pivot translations transformed by
  (x,y,z)→(z,y,−x) since ModelPart pivots translate in the parent frame, wall
  distance switched from depth·8 to width·8); solid front walls reuse the stock
  back-wall cell layout flipped.
  **BUG FIXED 2026-08-17 (user screenshot: side doors rendered at the cab
  CENTRE, leaves converging into one overlapped mid-doorway column):** the side
  walls' yaw signs were inverted. Vanilla yaw +90° maps local +z → world +x
  (derivable from the stock corner handrail child, or edge1X vs edge2X), and
  the door assembly's wall side is local **+z** — so the −X wall needs
  −HALF_PI (like edge1X) and +X needs +HALF_PI. Swapped, the assembly lands
  12 px inboard and the leaf slide offsets point the wrong way, so opening
  drove both leaves to the middle. Same swap fixed in the door branches' extra
  edge strips (width>3 cabs; they rendered outside the cab), and the
  wall_patch a/b gating conditions were inverted in all four branches
  (patch_a = +X end on the flipped front wall, −X end on the back, front end
  on the left wall, back end on the right — each gates on the wall it returns
  onto). In-game confirmed by Thomas (doors now on their walls); three follow-ups
  fixed 2026-08-18 from his play-test:
  1. **Doorway boxes X-flipped** (`AddonRenderLifts`): the cab model renders
     through rotateY(yaw+PI)*rotateX(pitch+PI) = R_y(yaw)*diag(-1,-1,1), so
     MODEL X is negated vs the doorway-box space (transformForwards is plain
     R_y(yaw)). Stock is X-symmetric and never notices; our left/right doorway
     boxes (boarding, holograms, canOpenDoors) sat on the OPPOSITE wall from
     the rendered door — the side facing the landing stayed shut while the far
     side opened. Boxes mirrored to match the model. Displays are INSIDE the
     model matrix, so they were already consistent.
  2. **Per-side landing checks tightened** (`canOpenDoorsTight`): stock
     canOpenDoors adds a 1-block WORLD-AXIS radius around the doorway; on a
     2x2 cab that covers most of the footprint, so one landing opened every
     configured side on every floor. Configured lifts now probe only the strip
     just beyond their own cab edge (outward 1.75, sideways ±0.75, stock's ±2
     vertical); the stock block tests + setDoorValue side effect (which is
     what animates the LANDING doors) are kept verbatim, so only the correct
     side's landing doors swing. Unconfigured lifts keep stock checks.
  3. **Displays moved off door walls** (user request): configured lifts mount
     the floor display on walls WITHOUT doors (all-four-doors falls back to
     the stock front position); unconfigured lifts keep stock placement. And
     **corner z-fighting** killed via CORNER_EPS = 0.1 model px in
     AddonModelLift: adjacent door assemblies' frame posts overlap with
     coplanar faces at a shared corner, and their floor/ceiling strips share
     the y-planes — the ±X assemblies are nudged toward the cab centre with
     floor raised / ceiling lowered by the same amount.
  All compile-verified + deployed (cmp-checked into BOTH mods folders);
  awaiting in-game confirmation. Corner posts keep stock positions/rotations
  and are suppressed exactly per stock's generalized rule (an adjacent wall
  whose door spans the whole 2-block wall). Stock's `wall_patch` (side stubs
  beside a full-width door) is split into its two halves so a stub is skipped
  when it would land inside an adjacent full-width doorway (2x2 cab with
  adjacent doors).

**Storage / sync** (extending Agents 1–2's skeleton, no refactors):

- `mtraddon/LiftDoorSides.java` — shared immutable record (front/back/left/
  right) with a 4-bit wire mask.
- `AddonStore`: `liftDoors` formalized from the raw-JSON passthrough to
  `Map<Long, LiftDoorSides>`; same §4 JSON shape
  (`{"<liftId>": {"front": true, ...}}`), so existing files load unchanged.
  New: `liftDoorsView()`, `setLiftDoors(liftId, sides)` (all-off/null clears),
  `liftDoorCount()`. Deliberately NO `AddonSnapshots` entry: nothing on the
  server/simulator side ever reads door sides (pure client presentation), so
  there is no simulator-thread reader to snapshot for — the client mirror is
  the render-thread copy.
- `AddonNetworking`: `addon_lift_doors` S2C full map (join + rebroadcast after
  every change; one long + one byte per configured lift; sent EMPTY while the
  feature is disabled) and `addon_update_lift_doors` C2S (lift id + mask;
  mask 0 clears; op level `editPermissionLevel`, feature-flag checked
  server-side). `AddonInit`'s JOIN handler sends the new sync.
- `client/mtraddon/ClientLiftDoors.java` — render-thread mirror, replaced
  wholesale on the client thread by the packet, cleared on disconnect.

**Config keys** (`config/station-announcer-addon.json`):
`multiDoorLifts.enabled` (default true). Read at startup like the other
features; when off, saves are refused and the S2C sync is empty, which is what
keeps every client's mixin on the stock path (rejoin required after toggling).
Client: `showLiftDoorSidesButton` in `station-announcer-addon-client.json`.

**GUI entry point:** a "Door sides…" button on MTR's `LiftCustomizationScreen`
(same `ScreenEvents.AFTER_INIT` + vanilla-`instanceof` pattern as Agents 1–2 —
that screen IS a vanilla screen through the mapping layer; the edited `Lift` is
its `private final Lift lift` field, javap-verified, read via cached
reflection; permission-gated by `MinecraftClientData.hasPermission()`). Opens
`client/mtraddon/LiftDoorSidesScreen`: four vanilla on/off cycling buttons
seeded from the current config (default when unconfigured: front on, back =
`lift.getIsDoubleSided()`), Done/Cancel, and "Use MTR default" (clears the
config; only active when one exists). Done with all four off falls back to
front-only, per spec. Lang keys under `gui.station_announcer.lift_doors.*`.

### Thread-safety notes

- The mixin and everything it delegates to run on the RENDER thread — the same
  thread stock `RenderLifts.render` runs on; only MTR client data plus the
  `ClientLiftDoors` map (swapped wholesale on that same thread) are read. No
  simulator or server state is touched.
- Store mutations stay on the server thread; file I/O stays on Agent 1's
  debounced executor.

### Known limitations / gaps

- **Lift call buttons/panels remain MTR's blocks** — no per-side call buttons
  are added; landings call the cab exactly as before. (Documented spec gap.)
- Door sides are cab-space; which world direction "left" faces depends on the
  lift's rotation set in the customization screen. The in-cab display's ±90°
  rotation direction for side doors (left vs right wall) is derived from the
  GraphicsHolder yaw convention, not visually verified — if swapped in game,
  negate the two ±90 values in `AddonRenderLifts.renderDisplays`.
- A 2x2 cab with doors on adjacent walls suppresses the shared corner post and
  the overlapping wall-patch stubs; the doorway frames still meet at the corner
  but that extreme case (and side doors generally) has not been seen in game.
- Configured lifts always render through `AddonModelLift`, even when the chosen
  sides equal stock (front, or front+back on a double-sided lift). The solid
  walls/doors replicate stock piece-for-piece, but "pixel-identical to stock"
  is only guaranteed for UNCONFIGURED lifts, which keep `ModelLift1` and the
  stock code path.
- MTR renders a lift cab even mid-travel between floors; open-side detection
  per doorway happens only while `hasCoolDown()` (stopped at a floor), same as
  stock, so no behavior change there.
- Toggling `multiDoorLifts.enabled` requires a server restart (config is read
  once) and clients keep their last-synced map until the next sync/rejoin.
- Not compiled or in-game tested by this agent (no-Gradle rule); orchestrator
  to verify. The rotation math for side-door assemblies (pivot transform
  (x,y,z)→(z,y,−x) with rotation −π/2, and the mirrored +π/2 case) was derived
  from and cross-checked against ModelLift1's own edge/corner usage, not
  rendered.

### Files touched

New: `mtraddon/LiftDoorSides.java`,
`client/mtraddon/{ClientLiftDoors,AddonModelLift,AddonRenderLifts,LiftDoorSidesScreen}.java`,
`mixin/RenderLiftsMixin.java`.
Modified (extending the shared skeleton as intended):
`mtraddon/{AddonServerConfig,AddonStore,AddonNetworking,AddonInit}.java`,
`client/mtraddon/{AddonClientInit,AddonClientConfig}.java`,
`resources/station_announcer.mixins.json` (client section +1),
`assets/station_announcer/lang/en_us.json` (+10 keys).

---

## Feature Agent 4 — Advanced manual driving HUD — 2026-08-07

### What was built

A client-only HUD overlay (NO mixins, no server code, no packets) shown while
the player is driving: riding a vehicle AND holding a valid driver key for its
depot — the exact visibility condition of MTR's own `DrivingGuiRenderer`
(`MinecraftClientData.getInstance().vehicles` scan +
`VehicleRidingMovement.isRiding(id)` +
`VehicleRidingMovement.getValidHoldingKey(depotId) != null`, all
javap-verified). It complements MTR's speedometer (bottom-right) from a
configurable corner, default top-left. Sections, each individually toggleable:

- **Next stop** — name, distance, ETA. The upcoming platform is the first path
  segment ahead of the head index with `dwellTime > 0 && savedRailBaseId != 0`
  (excluding the siding id, like `VehicleExtension`'s stopping-details loop).
  Name comes from `platformIdMap` → `platform.area` station name FIRST, with
  `vehicleExtraData.getNextStationName()` as fallback — deliberate inversion of
  the spec's order: MTR's this/next labels flip one station ahead the moment
  the head enters the platform segment (stopIndex changes at the dwell
  segment), while the platform-map name always matches the platform whose
  distance is displayed. ETA = cruise at current speed then brake at
  `vehicleExtraData.getDeceleration()` (constant-decel time when already
  inside braking distance); "--" while effectively stationary.
- **Schedule status** — EARLY / ON TIME / LATE with signed seconds, colored
  green / white / red. Reuses the v2.3 `RailroadRouteData` trick: cached
  `ArrivalsCacheClient.INSTANCE.requestArrivals` fetch for the next platform
  id, match our run by `getThisRouteId() + vehicle.getDepartureIndex()`, read
  `getDeviation()` (positive = late). `departureIndex == -1` (manual sidings)
  or no match → "Schedule: --".
- **Speed limits** — up to 3 upcoming `getSpeedLimitKilometersPerHour()`
  changes as "in 432 m → 60 km/h"; amber when the new limit is below the
  CURRENT speed (braking due), white otherwise.
- **Signals** — up to 3 signal blocks ahead (segments with non-empty
  `getSignalColors()`; consecutive segments with the same aspect merge into
  one entry) with a colored square + CLEAR / CAUTION / OCCUPIED. Aspect from
  the same client maps MTR's wayside signals read:
  `railIdToCurrentlyBlockedSignalColors` / `railIdToPreBlockedSignalColors`
  (keyed by the canonical rail hex id — one of `PathData.getHexId(false/true)`,
  both tried) intersected with the segment's own signal colours, plus
  `blockedRailIds` occupancy via both directional ids. Self-detection guard:
  our own vehicle writes its rails into `blockedRailIds` up to its braking
  padding (`0.5·v²/decel + transportMode.stoppingSpace`, mirroring
  `VehicleExtension`'s "Write signals" block), so occupancy hits inside that
  zone are ignored. Plus an **OBSTRUCTION AHEAD** row whenever
  `vehicleExtraData.getStoppingPoint()` lands short of the next platform's end
  distance (a signal held against us or a vehicle ahead).

**Performance** (ARCHITECTURE §6): the lookahead walk runs in a
`ClientTickEvents.END_CLIENT_TICK` handler throttled to `hudUpdateHz` (default
4 Hz, clamped 1–20) over at most `hudLookaheadMeters` (default 2000) of
`immutablePath`; the `HudRenderCallback` only draws the cached snapshot of
pre-built text rows (a few `getWidth` calls + fills per frame). Arrivals fetch
cached ≥ `hudArrivalsCacheMillis` (default 1000, min 250). Nobody driving →
one small vehicle-set scan per update and nothing rendered; `hudEnabled` off →
zero work. The head index / stopped-at-platform math is
`Utilities.getIndexFromConditionalList` — the same binary search MTR itself
uses. `railProgress` (protected on `VehicleSchema`, no 4.0.1 getter) is read
via one cached reflection `Field`; on lookup failure the HUD disables itself
for the session (logged) instead of erroring per tick. The whole compute is
wrapped in try/catch (MTR data can be swapped mid-sync — RailroadRouteData
precedent).

**Settings screen** (`DrivingHudScreen`): opened by a NEW keybinding —
default **H** (vanilla and MTR both leave H unbound; MTR uses Z + arrows +
keypad), category "Station Announcer", registered with Fabric's
`KeyBindingHelper`, works in-game or over another screen. Left column: master
toggle + the four element toggles (vanilla `CyclingButtonWidget.onOffBuilder`,
same pattern as `LiftDoorSidesScreen`); right column: shared
`com.stationannouncer.client.gui.IntSlider`s for update rate (1–10 Hz),
lookahead (250–5000 m), on-time window (±5–60 s), and a corner cycler
(top_left / top_right / bottom_left / bottom_right). Edits apply live (the
HUD previews behind the non-pausing screen); Done persists via the new
`AddonClientConfig.persist()`, Cancel restores the values captured at open.
No gear button on the panel (HUD is non-interactive, per plan).

**Config keys** (`station-announcer-addon-client.json`, all new):
`hudEnabled`, `hudShowSpeedLimits`, `hudShowSignals`, `hudShowNextStop`,
`hudShowOnTime` (all true), `hudUpdateHz` (4), `hudLookaheadMeters` (2000),
`hudArrivalsCacheMillis` (1000), `hudOnTimeThresholdSeconds` (15),
`hudCorner` ("top_left"), `hudMargin` (6). Unknown `hudCorner` values render
as top-left.

### Thread-safety notes

- Everything runs on the CLIENT thread: tick handler, render callback, screen.
  All state is static and cleared by the feature's own
  `ClientPlayConnectionEvents.DISCONNECT` handler (snapshot, arrivals cache,
  update timer); the cached reflection `Field` survives (it is process-wide).
- No server/simulator state, no MTR mutation — `requestArrivals` is the same
  read MTR's own PIDS make; it also keeps MTR's platform request queue warm.

### Known limitations

- **Repeat-infinitely routes**: the lookahead simply stops at path end instead
  of wrapping to `getRepeatIndex1()` (protected in 4.0.1, and a wrap would need
  distance rebasing). After the final stop of a looping route the lists go
  quiet until MTR advances the path — chosen as the documented simpler-correct
  option offered by the spec.
- Manual-mode schedule deviation exists only when MTR itself predicts the run
  (`departureIndex >= 0`); manual sidings show "--" by design.
- The aspect estimate mirrors wayside signals: a block still held by OUR own
  signal reservation reads OCCUPIED (only `blockedRailIds` self-hits are
  filtered, since those are indistinguishable from another train by id). The
  hidden-while-a-screen-is-open rule matches `DrivingGuiRenderer` (chat is the
  exception); F1 hides it with the rest of the HUD.
- The ETA is naive (no speed-limit integration along the way) — intentional
  per spec.
- Signal-block grouping merges CONSECUTIVE same-aspect signalled segments; two
  distinct blocks that happen to touch with the same aspect read as one entry.
- Not compiled or in-game tested by this agent (no-Gradle rule); orchestrator
  to verify. Rendering positions/colors are vanilla-convention but unseen; the
  "→" and "±" glyphs render with vanilla's font (project precedent: PIDS
  arrows), swap for ASCII if they ever show as boxes.

### Files touched

New: `client/mtraddon/{DrivingHud,DrivingHudScreen}.java`.
Modified (extending the shared skeleton as intended):
`client/mtraddon/AddonClientConfig.java` (new fields + `persist()` only),
`client/mtraddon/AddonClientInit.java` (+1 line: `DrivingHud.register()`),
`assets/station_announcer/lang/en_us.json` (+31 keys).

### Update: in-game feedback fixes + door rows — 2026-08-08

**BUG "schedule not working"** — the arrivals data path was re-verified end to
end against the 4.0.1 jar + TSC sources: `departureIndex` IS synced to clients
(it is in the serialized `vehicle.json` schema), `ArrivalResponse.
getDepartureIndex()` carries the SAME per-siding departures index the vehicle
holds (`Siding.iterateArrivals` passes `getIsManual() ? -1 : departureIndex`),
both sides are `long`, and `ArrivalsCache.requestArrivals`+`tick()` fetch
standalone. Root cause of Thomas's report: driving a MANUAL-siding train —
every vehicle AND every arrival there carries `departureIndex == -1`, so the
indicator can never resolve BY DESIGN and showed a bare "Schedule: --" that
read as broken. Now `departureIndex < 0` shows "Manual - no schedule"
(new lang key `sched_manual`); "Schedule: --" remains only for
fetch-pending/no-match. Scheduled matching also hardened: the route key now
accepts `getNextRouteId()` besides `getThisRouteId()` (route-handover stop
within one depot block), preferring the thisRouteId hit.

**BUG "next stop randomly turns on/off"** — two causes fixed:
1. The walk now WRAPS repeat-infinitely routes instead of stopping at path
   end: on reaching `repeatIndex2` it continues at `repeatIndex1` with the
   distance rebased by `path[repeatIndex2].getStartDistance()` (or the last
   segment's end when `repeatIndex2 == path.size()`) — the exact
   `Vehicle.simulate` wrap math (Vehicle.java:496 + the totalDistance
   derivation). `repeatIndex1/2` are protected final schema fields with only
   protected getters in 4.0.1, read via the same cached-reflection helper as
   `railProgress` (no new mixin); on lookup failure the walk degrades to the
   old stop-at-end behavior. All three scans (next stop, limits, signals) now
   consume one shared wrapped walk with head-relative distances; the walk runs
   to the lookahead AND until one platform stop has been seen (capped at one
   full extra loop / 4096 segments / 100 km).
2. Sticky section: when a refresh finds no stop for ANY reason, the last known
   next-stop rows keep showing for a 3 s grace period before the section
   hides. Also fixed in passing: the scan previously started at headIndex+1
   and skipped the platform segment the head was already ON while pulling in
   (briefly naming the stop after next); the head segment is now checked
   first. The obstruction cue is skipped when the next stop lies past a wrap
   (`getStoppingPoint()` is a plain rail-progress value, not comparable across
   the wrap).

**ADDITION: doors row** (`hudShowDoors`, default true, settings toggle + lang
keys): OPEN (green) / OPENING n% / CLOSING n% (amber, percent = door position,
100 = fully open) / CLOSED (white), from the same two values MTR's driving GUI
reads — `persistentVehicleData.getDoorValue()` (0..1) and
`persistentVehicleData.getAdjustedDoorMultiplier(vehicleExtraData)` (both
public, javap-verified; the adjusted multiplier is what MTR's own door tick
integrates).

**ADDITION: door obstruction alert** — when
`ClientDoorObstructions.isObstructed(vehicleId)` (Feature 5-era server
feature's client mirror; read-only here) reports the driven vehicle, a
"DOORS OBSTRUCTED" row tops the panel flashing at 2 Hz (250 ms on/off; the
blink phase is a per-frame color choice over the cached snapshot flag — the
row keeps its height while dark so the panel never jumps) and the doors row
reads OBSTRUCTED (red). The alert row bypasses `hudShowDoors` (master toggle
still applies).

Cache discipline unchanged: everything above is computed in the 4 Hz snapshot;
no new per-frame work beyond the blink phase check. New/changed files this
update: `client/mtraddon/DrivingHud.java` (rewritten walk + fixes),
`client/mtraddon/DrivingHudScreen.java` (+doors toggle, 6-row column),
`client/mtraddon/AddonClientConfig.java` (+`hudShowDoors`),
`assets/station_announcer/lang/en_us.json` (+9 keys).

---

## Feature Agent 5 — Dynamic platform selection (platform groups) — 2026-08-07

### What was built

TSC generates ONE shared path per depot with precomputed timetables, so
Feature 5 was scoped per ARCHITECTURE §5: items 1–3 shipped solid, item 4
(runtime switching) deliberately skipped — see "NOT implemented" below.

**1) Platform groups + storage/sync/GUI:**

- `AddonStore`: the `platformGroups` raw-JSON passthrough (Agent 1) formalized
  to a typed `Map<String, long[]>` — key `"<routeId>:<stopIndex>"`, value the
  member platform ids. Same §4 JSON shape, so existing files load without
  migration. New: `platformGroupsView()`, `setPlatformGroup(routeId, stopIndex,
  members)` (empty clears), `platformGroupCount()`, plus a NEW persisted
  section `platformGroupRuntime` (`{"rotation": {key: n}, "choice":
  {key: platformId}}`) serialized from `PlatformGroupEngine`'s concurrent maps
  at save time, and `requestSaveFromEngine()` — a thread-safe entry point (it
  only pokes the existing debounced AtomicBoolean dirty flag) so the SIMULATOR
  thread can request persistence without touching the store maps.
- `AddonSnapshots.platformGroups()`: volatile immutable
  `Long2ObjectOpenHashMap<long[][]>` (route id → stop-index-indexed member
  arrays; allocation-free simulator-thread lookup). Publishing also prunes the
  engine's runtime entries for deleted groups — that is what stops a removed
  group from being re-applied to the route cache.
- `AddonNetworking`: `addon_platform_groups` S2C full-map sync (join + after
  every change; EMPTY while the feature is off, like the lift-door sync) and
  `addon_update_platform_group` C2S (route id + stop index + full member list;
  empty clears). Validated: op level `editPermissionLevel`, feature flag,
  `MAX_GROUP_SIZE` = 8, `MAX_STOP_INDEX` = 4096, dedupe, zero ids dropped.
  **Semantic validation split (documented honestly):** members-are-same-station
  + same-transport-mode is enforced AT USE TIME on the simulator thread in
  `PlatformGroupEngine.validMembers` (route/platform data lives on the
  simulator and can change any time after a save — there is no safe
  server-thread read of it), and the client picker is constrained to
  same-station platforms; the C2S handler does structural validation only.
- **GUI decision (per spec, documented):** `EditRouteScreen` was studied
  (source + javap against 4.0.1) — it edits ONLY route metadata (name, colour,
  type, hidden, circular); the per-stop platform list lives in the dashboard
  sidebar/map flow, so there is no "selected stop" to hang a per-stop button
  on. The honest, solid entry point is the spec's stated alternative: a
  **"Platform group…" button on MTR's `PlatformScreen`** (same
  ScreenEvents.AFTER_INIT + cached-reflection `readPlatform` pattern as
  Features 1–2, one slot above their buttons, permission-gated, client toggle
  `showPlatformGroupButton`). It opens `PlatformGroupScreen`: one row per
  (route, stopIndex) occurrence of this platform across the dashboard routes
  (route colour, "Stop N of M", live group size), each with Edit → 
  `PlatformGroupEditScreen`: the shared `PlatformPicker` seeded at this
  platform's mid position (inside a station the picker lists exactly that
  station's platforms — the constraint the group needs), preselected with the
  current group, capped at 8; Save filters to same-station+same-mode via the
  dashboard `platformIdMap`, Clear removes the group. Client mirror
  `ClientPlatformGroups` (replaced wholesale by the sync, cleared on
  disconnect).

**2) Queueing documentation (nothing to build — MTR already provides it):**
trains targeting an occupied platform already queue via MTR's signal blocks and
`Vehicle.railBlockedDistance` — `writeVehiclePositions` marks occupied rail and
reserves signal blocks, and `simulateStopped`/`startUp` refuse to move while
`railBlockedDistance ≥ 0`. Platform groups therefore compose with stock
behavior: the depot spreads traffic across the group at generation time, and
whatever still collides queues safely at runtime. The GUI says so
(`hint_queue2`: "Occupied platforms already queue via MTR's signal blocks").

**3) Generation-time selection** — `mtraddon/PlatformGroupEngine.java` +
`mixin/DepotMixin.java` + `mixin/PlatformRouteDetailsAccessor.java`:

- **Injection points (both bytecode-verified against 4.0.1, not master):**
  - `Depot.generateMainRoute(Depot$OnGenerationComplete)V` **HEAD** (private) —
    the disassembly shows this is the single place the `SidingPathFinder`
    chain is built from the private `platformsInRoute` list, so swapping the
    list entries at HEAD is sufficient AND minimal. `Depot.tick`'s completion
    callback (`siding.generateRoute(platformsInRoute.get(0)…, size, …)`) reads
    the same swapped list, so the sidings' first/last platforms stay
    consistent. This was chosen over the spec's writeRouteCache-TAIL-only
    sketch because writeRouteCache runs on EVERY `Data.sync()` (client
    included), which is the wrong moment to advance a "per generation"
    rotation.
  - `Depot.writeRouteCache(Long2ObjectOpenHashMap)V` **TAIL** (public) —
    `Data.sync()` rebuilds `platformsInRoute` from the routes' original
    platforms; this hook RE-APPLIES the last applied choices (no rotation
    advance, `data instanceof Simulator` guarded) so
    `getVehiclePlatformRouteInfo` — the vehicles' this/next-platform info —
    keeps matching the baked path between generations and across restarts
    (choices are persisted).
  - `Depot$PlatformRouteDetails` is a package-private class with
    `private final Platform platform` (javap-verified — NOT a record in 4.0.1),
    so `PlatformRouteDetailsAccessor` is an `@Mutable @Accessor` mixin that
    swaps the field in place; `route`/`platformIndex` stay untouched, and
    **`Route.getRoutePlatforms()` — the user's route definition — is never
    modified.**
- **Strategy:** per-group rotation counter (persisted in
  `platformGroupRuntime`, advanced once per generation that uses the group) →
  `validMembers[counter % n]`, with a collision guard: a swap is skipped if it
  would make two consecutive collapsed stops the same platform (which would
  create a platform→itself path finder). Member validation at swap time:
  resolvable in the simulator's `platformIdMap`, same station as the stop's
  original platform, same transport mode. Invalid/deleted groups fall back to
  the original platform; a deleted group's stops are actively restored to
  their originals on the next generation.
- **Stop attribution** replicates `Depot.writeRouteCache`'s collapse loop
  exactly (bytecode-verified: null platforms skipped WITHOUT updating the
  previous id), extended to remember which (route, index) added each collapsed
  stop; defensive size/entry alignment checks disable the feature with one
  logged warning if MTR's loop ever drifts.
- **Feature 2 interplay (checked, minimal change made):** the path's dwell
  segments carry the SWAPPED platform id in `getSavedRailBaseId()`, and the
  swapped platform's own `getDwellTime()` is what `SidingPathFinder` bakes —
  so `DwellOverrideEngine`'s stop matching AND its override lookup now key by
  a new `Stop.effectiveId` (= `PlatformGroupEngine.effectiveStopPlatformId`,
  the applied choice or the original). This is exactly the spec's "use the
  ACTUAL path platform id" resolution. **What per-route dwell means for group
  stops:** overrides belong to the physical platform trains actually stop at —
  configure the override on each group member you care about; the platform
  default likewise comes from the member actually chosen. No other Feature 1–4
  code was touched (hold rules already key by the actual path platform, so
  they work on swapped platforms unchanged).
- Arrivals/PIDS/in-train displays show the swapped platform — correct, the
  trains really stop there.

**Config keys** (`config/station-announcer-addon.json`):
`dynamicPlatforms.enabled` (default true) — gates the store saves, the S2C
sync content and both mixin hooks (single field read when off; both are cold
paths — depot regeneration and data sync, never per tick). O(1) bail chain
when idle: enabled → snapshot isEmpty → `data instanceof Simulator` → per-depot
`containsKey` per route. Client: `showPlatformGroupButton` in
`station-announcer-addon-client.json`.

### Thread-safety notes

- Both Depot hooks run on the per-dimension SIMULATOR thread (or the server
  thread with `useThreadedSimulation` off). The engine reads the volatile
  config, the volatile immutable group snapshot and the simulator's own data;
  its runtime state is two ConcurrentHashMaps (written by the simulator
  thread, seeded/cleared/pruned on the server thread, read by the store's save
  executor — weakly consistent iteration acceptable for persistence).
- The engine never mutates the store; persistence goes through
  `AddonStore.requestSaveFromEngine()` (AtomicBoolean + debounced executor —
  already thread-safe), so no file I/O and no lock contention on any tick.

### Known limitations

- **Selection is static between generations** (the honest scope): the platform
  choice rotates when the depot regenerates, not per arrival. Runtime
  occupancy is handled by MTR's existing signal queueing.
- A route shared by MULTIPLE depots shares one group key: each depot's
  generation advances the same rotation and overwrites the applied choice, so
  `platformsInRoute` re-application (and Feature 2 attribution) tracks the
  most recently generated depot. Same-station platforms bound the blast
  radius; documented rather than keyed-by-depot to keep the §4 storage shape.
- At a route boundary collapsed into one stop (route B starts where route A
  ends), the group key that takes effect is (routeA, lastIndex) — the
  occurrence that ADDS the collapsed stop. A group configured on (routeB, 0)
  for that platform never matches; the GUI still lists that occurrence
  (client can't see depot order), which can look like a dead edit. Documented,
  matches Feature 2's boundary semantics.
- If the first/last platform of the whole depot route cycle is swapped, siding
  approach/return paths regenerate toward the swapped platform (handled —
  `Depot.tick` hands `generateRoute` the swapped list), but a group member
  that only some sidings can physically reach will fail those sidings'
  path-finding for that generation (MTR reports it via the depot's generation
  status, same as any unreachable platform; next generation rotates onward).
- Choice persistence keeps `platformsInRoute` consistent with the saved baked
  path across restarts; if `data.json` is deleted (or the runtime section is
  hand-edited away) the cache shows originals until the next regeneration,
  while the baked path keeps the old swap — cosmetic only (station names
  match; platform numbers in in-train route info may differ until regenerated).
- Feature toggled off: already-baked swapped paths remain until the next
  regeneration (path is baked — same rule as dwell overrides).
- Not compiled or in-game tested by this agent (no-Gradle rule); orchestrator
  to verify. Specific compile-risk spots: the bare-`CallbackInfo` handler
  signatures on `DepotMixin` (Mixin allows omitting target args; the
  alternative needs naming the private `Depot$OnGenerationComplete` type) and
  the wildcard-typed `@Shadow ObjectArrayList<?> platformsInRoute` (field
  descriptors are erased, so it binds).

### NOT implemented (deliberate) — item 4, runtime platform switching

`dynamicPlatforms.experimentalRuntimeSwitch` was **not** built, and no config
key was added for it (a dead key would be dishonest). Why, concretely, against
the 4.0.1 bytecode:

- A runtime splice must rebuild `VehicleExtraData`'s immutable path and every
  distance-derived structure: 4.0.1's `Siding` builds `timeSegments`,
  `platformTripStopTimes` and `Trip`s in the private
  `generatePathDistancesAndTimeSegments()`; a mid-run path change invalidates
  all of them (arrivals, deviation, departure slots), and re-running them
  per-switch on the simulator thread is exactly the class of work §6 forbids.
- Pre-generating alternates (prev stop → member → next stop) requires driving
  extra `SidingPathFinder` chains outside `Depot.tick`'s pipeline and stitching
  `PathData` distance bases (`startDistance/endDistance` are baked into each
  segment); `SidingPathFinder.generatePathDataDistances` exists, but the
  spliced result must also agree with `Siding.pathMainRoute`, which every OTHER
  vehicle of the depot shares — a per-vehicle divergent path needs a private
  copy of the whole downstream path, which `VehicleExtraData.create` supports
  only at creation time in 4.0.1.
- The client partial-path sync (`pathUpdateIndex` / `VehicleExtraData.copy`)
  exists, but nothing guarantees signal-block reservations taken under the old
  path are released coherently when the path is swapped mid-dwell
  (`isSignalBlocked` reservations key off `PathData` identity).

Items 1–3 deliver the value (spread + queue) without touching any of that;
per ARCHITECTURE §5.4 shipping 1–3 and documenting this gap is the accepted
outcome.

### Files touched

New: `mtraddon/PlatformGroupEngine.java`,
`mixin/{DepotMixin,PlatformRouteDetailsAccessor}.java`,
`client/mtraddon/{ClientPlatformGroups,PlatformGroupScreen,PlatformGroupEditScreen}.java`.
Modified (extending the shared skeleton as intended):
`mtraddon/{AddonServerConfig,AddonStore,AddonSnapshots,AddonNetworking,AddonInit}.java`,
`mtraddon/DwellOverrideEngine.java` (the documented minimal Feature 2 change:
`Stop.effectiveId` used for segment matching + override lookup),
`client/mtraddon/{AddonClientInit,AddonClientConfig}.java`,
`resources/station_announcer.mixins.json` (+2 entries),
`assets/station_announcer/lang/en_us.json` (+14 keys).

### Feature Agent 1 — Update: transfer windows on hold rules (2026-08-07)

Per Thomas's play-review: a hold used to release the instant the watched train
arrived, giving passengers zero time to walk across. Hold rules now carry a
per-rule `transferSeconds` (GUI slider 0–120, "Keep holding after arrival",
default 20 for NEW rules, 0 = old release-on-arrival behavior).

- **Engine**: the arrival window widened from `(now, now + N*1000]` to
  `(now - transferSeconds*1000, now + N*1000]` — the shared per-platform arrival
  cache now stores `{computedAt, soonestUpcoming, latestPast}`. A recent past
  arrival only exists while the arrived train is still dwelling (its
  ArrivalResponse then rolls to the next run), so the hold also releases early if
  the watched train leaves early. Self-arrivals remain impossible (ruled platform
  filtered from watched sets on save).
- **Deadlock cap interplay (decision)**: the engine's effective cap is
  `maxHoldSeconds + rule.transferSeconds()` so `maxHoldSeconds` keeps meaning
  "longest wait FOR a train" independent of the transfer setting; commented at
  the cap site in HoldRuleEngine.
- **Threaded through**: `AddonSnapshots.HoldRule` (new record component),
  `AddonStore` (JSON `transferSeconds` per rule; missing field → 0, so existing
  saves load unchanged), `AddonNetworking` (C2S reads + clamps 0–120, S2C syncs;
  new `MAX_TRANSFER_SECONDS` / `DEFAULT_TRANSFER_SECONDS` constants),
  `ClientHoldRules.Rule`, `AddonClientInit` (receiver), `HoldRuleScreen` (second
  IntSlider with an explicit "Off" caption at 0), lang keys
  `gui.station_announcer.hold_rules.transfer_seconds(.off)`.
- Wire format changed (varint inserted in both channels) — fine, both sides ship
  together and the channels are this addon's own.

### Feature Agent 1 — Update: held trains now actively hold their doors open (2026-08-07)

In-game bug (Thomas): a train held ~10 s sat with doors SHUT. The original
"cancelling startUp before closeDoors() keeps doors open" claim was only true
when the hold condition was already true at the FIRST startUp attempt — stock
MTR closes doors on that first call (at doorCloseTime, ~4 s before departure)
and then retries startUp each tick until the door cooldown expires. A hold
engaging in that gap cancelled the retries but nothing reopened the doors.

Fix: every genuine platform-hold cancel in `VehicleMixin` now also calls
`VehicleExtraData.openDoors()` on the held vehicle. That method is protected in
4.0.1 (javap-verified), so a new `@Invoker` accessor mixin
`mixin/VehicleExtraDataAccessor.java` exposes it (registered in
`station_announcer.mixins.json`). Safe: it runs on the simulator thread inside
the vehicle's own simulate call — the same context from which stock
`simulateStopped` mutates its own `VehicleExtraData` — and is idempotent
(`doorTarget = true`) so per-tick re-assertion costs nothing. Client sync
verified read-only in TSC source: the doorTarget flip flows through the existing
`writeVehiclePositions` → `checkForUpdate()` (`doorTarget != oldDoorTarget` →
needsUpdate) → `client.update(...)` path — no new packets. Bonus: reopening
resets the door cooldown, so on release startUp closes the doors and waits the
full door animation before moving (no teleport-departure).

Supersedes the earlier limitation note: held trains now keep (or regain) open
doors regardless of when the hold engages, which is also the realistic
connection-hold behavior.

Files: `mixin/VehicleExtraDataAccessor.java` (new), `mixin/VehicleMixin.java`
(openDoors call + corrected javadoc), `station_announcer.mixins.json` (+1 entry).

---

## Dispatch Agent 1 — Server-side dispatch web layer (webserver hook, network API, SSE stream) — 2026-08-07

### What was built

The server side of the real-time dispatch web UI, riding on **MTR's own embedded
Jetty webserver** (same port/origin as the Transport System Map — no CORS, no
second server). Everything lives in the new package
`com.stationannouncer.mtraddon.dispatch` plus four mixins. All servlet / jetty /
gson / fastutil imports are the RELOCATED `org.mtr.libraries.*` ones; every MTR
member touched was javap-verified against the 4.0.1 jar (the TSC master source
has drifted badly in this layer — 4.0.1 ships `org.mtr.libraries.javax.servlet.*`
and `WebServlet`, not jakarta/renamed classes).

**Webserver hook** — `mixin/MainMixin.java` injects into `org.mtr.core.Main`'s
constructor at `@At(value="INVOKE", target="Lorg/mtr/core/servlet/Webserver;start()V")`
(bytecode-verified: `putfield simulators` @85 and `putfield webserver` @101 both
precede the `start()` invoke @208, and the invoke sits on the `port > 0` branch —
so with the webserver disabled the hook code simply never runs). At that instant
MTR's servlets and any `additionalWebserverSetup` are registered and Jetty has
not started; the mixin shadows the private `webserver` + `simulators` fields and
calls `DispatchWebSetup.install(...)`, which registers our four servlets and
publishes the simulators list + port into `DispatchRegistry` (a single volatile
reference to an immutable record). `Init.createWebserverSetup` is untouched;
InitClient's client-side resource-pack webserver never constructs a `Main`, so it
is cleanly ignored. The registry is cleared (and the SSE streamer shut down) in
AddonInit's SERVER_STOPPING hook; servlets answer **503** whenever the registry
is empty. The dispatch URL is logged once at install
(`Dispatch web UI available at http://localhost:{port}/dispatch/`, port from
`Init.getServerPort()`), and AddonInit's SERVER_STARTED hook logs why the UI is
unavailable when it is (webserver disabled: port -1/0, or feature off).

**Servlet paths** (Jetty exact-beats-prefix, longest-prefix-wins matching):

| Path | Class | Behavior |
|---|---|---|
| `/dispatch/*` | `DispatchStaticServlet` | static frontend from classpath `assets/station_announcer/dispatch/` (placeholder `index.html` committed — **Agent 2 replaces that directory's contents**); byte-accurate IO, MIME map for html/js/css/svg/png/ico/woff2/json/etc, `..`-traversal rejected, extensionless paths fall back to index.html (SPA-friendly). MTR's `WebServlet` was rejected after javap: its provider is `Function<String,String>` — text-only, corrupts binaries. |
| `/dispatch/api/*` | `DispatchApiServlet` (extends MTR's `ServletBase`) | JSON data endpoints; inherits dimension routing + the `simulator.run` thread hop + MTR's response envelope |
| `/dispatch/api/ping` | `DispatchPingServlet` | synchronous bootstrap JSON (no simulator hop, no envelope) |
| `/dispatch/api/stream` | `DispatchStreamServlet` | the SSE live stream (extends relocated `HttpServlet` directly) |

### THE ENDPOINT + SSE SCHEMA (build the frontend from this)

`schemaVersion` is **1** everywhere. All MTR entity ids (station/platform/route/
vehicle/siding — random longs that can exceed 2^53) are **decimal strings**; rail
ids are MTR's canonical position-sorted hex ids (string, format
`x1-y1-z1-x2-y2-z2` in padded hex), identical between the network payload and the
stream. Coordinates are world coordinates (doubles rounded to 2 decimals; block
positions as integers). Names are MTR-raw (`"English|中文"` — split on `|`
client-side). Colors are 24-bit `0xRRGGBB` ints.

**GET `/dispatch/api/ping`** → `200 application/json` (plain, no envelope), or
`503 {"error":"dispatch not available"}`:

```json
{
  "schemaVersion": 1,
  "serverTime": 1786500000000,
  "updateMillis": 333,            // the stream cadence actually in effect (clamped)
  "maxClients": 8,
  "dimensions": ["minecraft:overworld", "minecraft:the_nether"]
  // index into this array == the `dimension` query param for the other endpoints
}
```

**GET `/dispatch/api/network?dimension=N`** (N defaults to 0; `dimensions=all`
fans out like MTR's own servlets but is not intended for the frontend). Response
is wrapped in **MTR's standard ServletBase envelope**:

```json
{ "code": 200, "currentTime": 1786500000000, "text": "Success", "version": 1,
  "data": { ...payload below... } }
```

Plain `503` (no envelope) while the server is stopping. The payload is built on
the simulator thread behind a per-dimension **30 s CachedResponse** (same
throttle as SystemMapServlet), so it is at most 30 s stale — refetch when MTR's
network is edited, or just poll it slowly. Payload (`data`):

```json
{
  "schemaVersion": 1,
  "serverTime": 1786500000000,
  "dimension": "minecraft:overworld",   // this simulator's dimension string
  "dimensionIndex": 0,                  // its index (== the query param you sent)
  "dimensions": ["minecraft:overworld", "..."],

  "rails": [
    {
      "id": "3f-40-2a-41-40-9c",        // canonical hex id — the stream's key
      "mode": "train",                  // train | boat | cable_car | airplane
      "length": 152.34,                 // meters
      "speedA": 80,                     // km/h along the polyline direction (points[0] → last)
      "speedB": 0,                      // km/h opposite direction; 0 = one-way / not traversable
      "platform": false, "siding": false,
      "canAccelerate": true, "canTurnBack": false,
      "signalColors": [16711680],      // signal block colors placed on this rail ([] = none)
      "points": [[x,y,z], ...]         // polyline, 2-dec doubles; sampled every ~4 m
                                        // (≤96 samples/rail), collinear points pruned —
                                        // a straight rail is exactly 2 points
    }
  ],

  "stations": [
    { "id": "123456789", "name": "City Hall|市政厅", "color": 4474111,
      "bounds": [minX, minY, minZ, maxX, maxY, maxZ],   // AreaBase corners, ints
      "platformIds": ["987...", "..."] }
  ],

  "platforms": [
    { "id": "987654321", "name": "1", "dwellMs": 10000,
      "stationId": "123456789",        // null when the platform is outside any station
      "p1": [x,y,z], "p2": [x,y,z],    // block-position pair (ints)
      "mid": [x,y,z],                  // midpoint (ints)
      "routeIds": ["555...", "..."] }  // routes calling here (resolve in "routes")
  ],

  "routes": [
    { "id": "555555", "name": "Lexington Av Express|...", "number": "4",
      "color": 1092784, "hidden": false }
  ]
}
```

(Route objects carry exactly: `id`, `name`, `number`, `color`, `hidden`.)

**GET `/dispatch/api/stream?dimension=N`** → `text/event-stream`. Errors before
the stream opens: `400` invalid dimension, `503` registry empty or
`dispatch.maxClients` reached (a cap race after headers are committed instead
sends an in-band `event: error` with `data: {"error":"too many dispatch clients"}`
and closes). On open the server sends `: connected` + `retry: 3000`, then on the
next streamer tick a `full` event, then `delta`s. A **`full` also goes to every
client every 10th tick**; deltas are emitted even when empty (≈3 Hz keep-alive).
Native `EventSource` reconnection works; after reconnect you get a fresh `full`.

Event `full` — complete state of the dimension:

```json
{
  "schemaVersion": 1,
  "serverTime": 1786500000000,      // sample instant, server epoch ms
  "dimension": 0,                   // dimension INDEX
  "vehicles": [ VEHICLE... ],       // every on-route vehicle, each WITH route+consist
  "signals":  [ SIGNAL... ]         // every rail with a non-empty live signal state
}
```

Event `delta`:

```json
{
  "schemaVersion": 1, "serverTime": ..., "dimension": 0,
  "vehicles": [ VEHICLE... ],       // new vehicles and changed vehicles only.
                                    // route+consist blocks present ONLY on new vehicles
                                    // (or when route/consist actually changed);
                                    // otherwise dynamic fields only — keep your cache.
  "removed": ["8123..."],           // vehicle ids no longer on route
  "signals": [ SIGNAL... ],         // rails whose live signal state changed
  "signalsCleared": ["hexId", ...]  // rails whose live signal state became empty
}
```

VEHICLE object:

```json
{
  "id": "8123456789",
  "x": 1023.5, "y": 64.0, "z": -211.25,  // head position, world coords
  "kmh": 57.6,                      // speed (converted from MTR's m/ms)
  "rev": false,                     // reversed
  "rail": "3f-40-...",              // canonical hex id of the current rail (absent if unknown)
  "railT": 0.42,                    // progress fraction 0..1 along that rail (absent with "rail")
  "doors": true,                    // door TARGET open (MTR doorMultiplier > 0)
  "dwellMs": 8100,                  // dwell REMAINING at the current platform stop; 0 when moving
  "devMs": 1500,                    // schedule deviation, +late/−early. NOTE: MTR updates this
                                    // only when the vehicle stops — treat as "as of last stop"
  "manual": false,                  // driver-controlled right now
  "stop": 3,                        // MTR stopIndex (increments along the route)
  "route": {                        // ← full events, new vehicles, and static-change deltas only
    "id": "555555", "name": "...", "number": "4", "color": 1234567,
    "dest": "Crown Heights|...",    // this route's destination string
    "nextStation": "Wall St|..."    // next station name
  },
  "consist": {                      // ← same presence rule as "route"
    "sidingId": "777...", "siding": "Siding 1|...", "depot": "Main Depot|...",
    "cars": ["m7_a", "m7_b", "m7_b", "m7_a"]   // vehicle-car ids, head first
  }
}
```

SIGNAL object (live aspect — read directly from the rails' reservation maps via
the public `iterateCurrentlyBlockedSignalColors` / `iteratePreBlockedSignalColors`,
NO reservation is taken by sampling; this is exact, not approximated):

```json
{ "rail": "3f-40-...",
  "occupied": [16711680],   // signal colors currently blocked (a train holds the block)
  "reserved": [255] }       // colors pre-reserved (a train has claimed the block ahead)
```

A rail with signal colors (see the network payload's `signalColors`) that appears
in neither `signals` nor previous state is CLEAR. Render: occupied=red,
reserved=yellow, else green, per color group.

### Config keys (`config/station-announcer-addon.json`)

- `dispatch.enabled` (default **true**) — master switch; when false the Main
  mixin bails on one volatile-cached field read and no servlet is ever
  registered. Needs a server restart to change (the webserver only exists
  between server start/stop).
- `dispatch.updateMillis` (default **333**, clamped 100–5000) — SSE sampling
  cadence. Read every tick, so edits apply on next config reload/restart
  (config object is cached for the session).
- `dispatch.maxClients` (default **8**, clamped 1–64) — SSE connection cap,
  excess gets 503.

### Thread model (performance contract honored)

- **Zero clients = zero work.** `DispatchStreamer`'s single daemon thread
  (`station-announcer-dispatch-streamer`) starts on the first SSE register and
  stops on the last unregister/shutdown; while idle there are no timer ticks and
  no `simulator.run` enqueues. Static-payload requests cost one simulator-thread
  hop per 30 s per dimension (CachedResponse).
- Each streamer tick enqueues ONE `DispatchSampler.sample` Runnable per
  subscribed dimension via `simulator.run(...)`; it runs on that dimension's
  SIMULATOR thread, reads only the simulator's own data + accessor mixins, and
  returns plain POJOs (no live MTR refs cross the boundary). A bounded
  `CountDownLatch.await` means a stalled simulator costs one tick, never a hang.
- JSON serialization, delta computation and all socket writes happen on the
  streamer thread; frames are serialized once per tick per dimension and reused
  across clients. Dead connections are pruned on write failure.
- The sampler's only cache is the signalled-rail list per dimension (10 s
  rebuild; one pass over `railIdMap`), touched only on that simulator's thread,
  cleared when the streamer stops.
- Jetty worker threads only ever touch the volatile `DispatchRegistry`, the
  static-file classpath and (for SSE) the initial handshake bytes; the response
  stream stays in blocking mode (no WriteListener), which is what makes
  streamer-thread writes legal.

### Files

New: `src/main/java/com/stationannouncer/mtraddon/dispatch/{DispatchRegistry,
DispatchWebSetup,DispatchStaticServlet,DispatchPingServlet,DispatchApiServlet,
DispatchNetwork,DispatchSampler,DispatchStreamer,DispatchStreamServlet}.java`,
`src/main/java/com/stationannouncer/mixin/{MainMixin,SidingVehiclesAccessor,
VehicleSchemaAccessor,VehicleDeviationAccessor}.java`,
`src/main/resources/assets/station_announcer/dispatch/index.html` (placeholder —
Agent 2 owns this directory).
Modified (extending the shared skeleton as intended):
`mtraddon/AddonServerConfig.java` (Dispatch section + sanitize),
`mtraddon/AddonInit.java` (SERVER_STARTED availability log; SERVER_STOPPING
streamer shutdown + registry clear), `station_announcer.mixins.json` (+4 entries).

### Known limitations / risks

- **Mixin-into-constructor at INVOKE**: upstream SpongePowered Mixin historically
  restricts `@Inject` in `<init>` to RETURN/TAIL; Fabric's fork (shipped with
  Loader 0.19.3) supports arbitrary post-super injection points, which this
  relies on (per the ARCHITECTURE §7½ decision). If the runtime ever rejects it,
  the fallback is TAIL + reflective servlet registration BEFORE `start()` won't
  work — flag to the orchestrator immediately if apply fails.
- `devMs` only updates when a vehicle stops (MTR's `updateDeviation()` call
  site); between stops it is stale by design. Manual sidings report whatever MTR
  last computed.
- `dwellMs` is derived from the stopped-at-platform boundary condition (same
  logic as HoldRuleEngine); a vehicle held by Feature 1 or by a signal past its
  dwell shows `dwellMs` 0 while still stationary.
- The 30 s network cache means edits to rails/stations/routes can take up to
  30 s to appear; live data is unaffected.
- `vehicles` includes only `getIsOnRoute()` vehicles — trains parked in their
  siding are invisible until they depart (deliberate, per spec).
- Signal state is per-rail; the frontend must group rails by shared
  `signalColors` if it wants block-level rendering.
- A route's `speedA`/`speedB` direction convention is tied to the polyline
  sampling direction (`getPosition(d, false)`), not to compass or route
  direction.
- Not compiled or runtime-tested by this agent (no-Gradle rule); the orchestrator
  compiles. Runtime-only risks: the constructor injection point (above), Jetty's
  default `asyncSupported` on ServletHolder (relied upon — MTR's own ServletBase
  does the same `startAsync` through the same registration path), and SSE
  behavior behind reverse proxies (X-Accel-Buffering: no is set, but a buffering
  proxy will still break streaming).

### Dispatch backend review (Opus) — 2026-08-07

Adversarial javap-verified pass over the whole server-side dispatch layer before its
first compile. **The endpoint + SSE schema above is unchanged — every field name, type,
unit and presence rule was checked against the emitting code and matches.** Fixes:

1. **`mixin/MainMixin.java` — `@Inject` into `<init>` replaced with `@Redirect`.**
   The original relied on Fabric Mixin permitting arbitrary `@Inject` injection points
   inside a constructor, which upstream restricts. The redirect rewrites the existing
   `Webserver.start()` call site instead (fully supported after the super-constructor
   call) and performs the original call itself, so MTR's behaviour is unchanged.
   `javap -c` re-confirms `Main.<init>(Path,I,Z,Z,Consumer,String[])` contains **exactly
   one** `invokevirtual Webserver.start()V` (offset 208) — so `defaultRequire: 1` is
   satisfiable and unambiguous — with `putfield simulators` at 85 ahead of it. The
   `@Shadow @Final Webserver webserver` field is gone: the redirect hands us the
   instance directly.
2. **`dispatch/DispatchStreamer.java` — duplicate-streamer-thread race.** When the last
   client left, `stopThreadLocked()` set `running = false` and nulled `schedulerThread`
   while the old thread was still inside a tick; a reconnect in that window started a
   SECOND thread which the old loop then joined (`running` was true again), doubling
   every frame to every client. Added a `generation` counter captured by each loop;
   a superseded loop exits at its next check. Also: `stopThreadLocked()` no longer
   interrupts the streamer thread when *it* is the caller (the last client is detected
   during a tick), which previously left a bogus interrupt flag set mid-tick.
3. **`DispatchStreamer.register` + `mtraddon/AddonInit.java` — post-shutdown restart.**
   `register()` now refuses when `DispatchRegistry` is inactive, and SERVER_STOPPING
   clears the registry **before** `DispatchStreamer.shutdown()` (was the other way
   round), so a connection arriving during teardown cannot restart the thread that is
   being stopped. `shutdown()` was already idempotent and still is.
4. **`dispatch/DispatchApiServlet.java` — POST bypassed the 503 guard.** `ServletBase`
   routes GET through `doPost`, so only overriding `doGet` left a direct POST going
   straight to the simulator hop after the session was cleared. Both entry points now
   share one `unavailable(response)` check. `getContent` narrowed back to `protected`
   to match `ServletBase`'s declaration (the `public` widening was legal but gratuitous).
5. **`dispatch/DispatchSampler.java` — `railT` could be `-1` while `rail` was present**,
   contradicting the documented "0..1 whenever `rail` is present" (a zero-length path
   segment left the sentinel). Now 0 in that case, and rounded to 4 decimals — it is
   emitted for every vehicle every tick and full double precision was bloating frames.
6. **`dispatch/DispatchNetwork.java` — polyline edge cases.** A degenerate rail emitted a
   ONE-point "polyline"; it now emits two identical points so the frontend never has to
   special-case it. The sample cap was off by one (up to 97 samples for the documented
   "≤96"), fixed by dividing by `MAX_SAMPLES_PER_RAIL - 1`. The final endpoint is now
   always appended (the old `if (samples.size() > 1)` guard was dead after the first fix).

**Verified correct, do not re-litigate:** every MTR member the layer touches exists with
the used signature in the 4.0.1 jar — `ServletBase.getContent(String,String,
Object2ObjectAVLTreeMap,JsonReader,Simulator,Consumer<JsonObject>)` (protected abstract),
`ServletBase.doGet/doPost` (protected, no `throws`), `CachedResponse(Function<Simulator,
JsonObject>, long)` + `get(Simulator)`, `Webserver.addServlet(ServletHolder,String)`,
`Simulator.run(Runnable)` / `.dimension` / `.dimensions` / `.railIdMap` / `.stations` /
`.platforms` / `.routes` / `.sidings`, `Init.getServerPort()`, `Rail.getHexId()` (via
`TwoPositionsBase`) / `getSignalColors()` (`IntAVLTreeSet`) / `getSpeedLimitKilometersPerHour
(boolean)` (returns **long**) / `isValid` / `isPlatform` / `isSiding` / `canAccelerate` /
`canTurnBack` / `getTransportMode`, `RailMath.getPosition(double,boolean)` + `getLength()`,
`AreaBase.getMinX…getMaxZ` (**long**) + `savedRails`, `SavedRailBase.area` /
`getRandomPosition` / `getOtherPosition` / `getMidPosition`, `Platform.getDwellTime` /
`routes`, `Route.getRouteNumber` / `getHidden`, `NameColorDataBase.getId/getName/getColor`,
`Vehicle.getIsOnRoute/getReversed/getHeadPosition`, every `VehicleExtraData` getter used,
`Utilities.getIndexFromConditionalList(List<T>,double)`, `PathData.getRail/getStartDistance/
getEndDistance/getDwellTime/getSavedRailBaseId`, `VehicleCar.getVehicleId`. The three
accessor mixins target the right owners (`Siding.vehicles` private final —
`(Object)` bridge cast is required because `Siding` is **final**; `VehicleSchema.speed/
railProgress/elapsedDwellTime` protected; `Vehicle.deviation` private) and all four
mixins are in the common `mixins` block, not `client`.

Two specific things worth recording because they are easy to "fix" wrongly:

- **`Rail.iterateCurrentlyBlockedSignalColors` / `iteratePreBlockedSignalColors` take the
  RELOCATED fastutil `LongConsumer`, not `java.util.function.LongConsumer`** — and
  `javap -c` shows both iterate the *keySet* of the `Long2LongAVLTreeMap`s, i.e. the
  colors (the fields are named `…VehicleIds`, which is misleading). The sampler's
  `LongArrayList` + `occupied::add` is right; colors are longs here but ints in
  `Rail.getSignalColors()` — that asymmetry is MTR's, and JSON does not care.
- **The km/h conversion is `speed * 3600`, and that is CORRECT** (the review brief said
  ×3 600 000). `Utilities.kilometersPerHourToMetersPerMillisecond` is literally
  `kmh / 3600.0`, so m/ms → km/h is ×3600. Do not "correct" this.

**Could not be verified statically** (needs the orchestrator's compile + a running rig):
that Fabric's Mixin accepts the `<init>` redirect at all (bytecode says it should, and
redirects are the supported form); Jetty's `ServletHolder(Servlet)` async default (the
bytecode shows the `Source.EMBEDDED` branch setting `_asyncSupported = true`, and MTR's
own `ServletBase.startAsync` proves the path); and whether `getSpeedLimitKilometersPerHour
(false)` truly corresponds to `RailMath.getPosition(d, false)`'s direction — both use
`false`, but `getSpeedLimitKilometersPerHour` keys off the rail's own `reversePositions`
flag, so `speedA`/`speedB` may be swapped on some rails. Frontend impact is cosmetic.

## Holding lights ↔ hold rules — 2026-08-07

Reworked both holding-light UIs (they share `HoldingLightScreen`; the block variant
decides what it offers) and wired the YELLOW light into Feature 1.

**Files**
- `client/mtr/HoldingLightScreen.java` — rewritten. The platform `CyclingButtonWidget`
  (radius-16 sweep, one label per press) is gone; it now uses the shared
  `PlatformPicker` capped at one selection, so a light is configured exactly like the
  PIDS above it and sees every platform of its station, nearest-first, with the routes
  that call there. Nothing ticked = the nearest platform (what an unconfigured light
  already did). Both timing sliders unchanged. Yellow lights gain a **Hold rules**
  cycling button (Ignored / Flash while held / Only when held, each with a tooltip) and
  a live hint line underneath: no rule on this platform, the rule's watched count, or
  "a train is being held here right now".
- `client/mtr/PlatformPicker.java` — `maxSelected == 1` now REPLACES the selection on
  click instead of ignoring it (multi-select callers are untouched); added
  `getSingleSelected()` and `labelFor(id)`.
- `mtr/StationDecorBlockEntity.java` — new `HoldIndicator` enum (OFF/FLASH/ONLY) +
  `HoldIndicator` NBT byte, defaulting to OFF so existing lights behave as before.
  `MtrStationDecor`'s `update_decor` receiver reads one extra byte; `StationSignScreen`
  echoes it back unchanged, like the timings.
- `client/mtr/StationDecorRenderer.java` — while a hold is in force at the light's
  platform the lenses flash (550 ms on / 1000 ms period); in ONLY mode the arrivals
  branch is skipped entirely, so the light is dark at all other times.
- Server side: `HoldRuleEngine.HELD_PLATFORMS` + `heldPlatforms()`, the
  `addon_hold_state` S2C packet, `AddonInit`'s change-only ticker, `ClientHoldState`.
  See ARCHITECTURE.md § Feature 1.

**Verified**: `./gradlew compileJava` green. **NOT verified**: anything in game — the
GUI needs a real brush right-click, and the flashing needs a hold actually firing.

**Known gap (deliberate, not a bug)**: GREEN lights still follow MTR's timetable only,
so a green "time to leave" can light while our hold rule is holding the train (MTR's
arrival data knows nothing about the cancelled `startUp`). Suppressing green while held
is a one-line change in `paintHoldingLight` if Thomas wants it.

### Feature Agent 1 — Update: random door obstructions (2026-08-08)

New Thomas request: a low chance that "something gets stuck in the doors" when a
train tries to depart, bouncing them back open for a few seconds.

- **Config** (`doorObstruction` in `config/station-announcer-addon.json`):
  `enabled` (true), `chancePercent` (3, clamped 0–100), `minSeconds` (2),
  `maxSeconds` (6, clamped ≥ min, both ≤ 120). Volatile config reads; zero work
  when disabled.
- **`mtraddon/DoorObstructionEngine.java`** (new), simulator-thread safe with the
  same discipline as HoldRuleEngine (ConcurrentHashMaps, per-key single-writer,
  ThreadLocalRandom per simulator thread, shared stopped-at-platform test).
- **ATO path**: `VehicleMixin`'s existing `startUp` injection now consults the
  engine AFTER the hold check declines — so the once-per-stop roll lands on the
  first attempt that would genuinely have departed (including the release moment
  of a Feature-1 hold), and held ticks never roll. Stuck = cancel + re-assert
  `openDoors()` (existing `VehicleExtraDataAccessor`) for a uniform-random
  [min,max]-second window; per-vehicle `{lastAttempt, stuckUntil}` state with the
  hold engine's 5 s new-stop gap prevents re-rolls within a stop.
- **Manual path**: new HEAD/TAIL bracket around package-private
  `Vehicle.updateRidingEntities(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V`
  (javap-verified; the driver's `manualToggleDoors` close at `speed == 0` is a
  `toggleDoors()` inside it — the `speed > 0` force-close branch is left alone).
  HEAD records `getDoorMultiplier()`, TAIL hands before/after to the engine: an
  open→closed flip while stationary at a platform rolls once per platform visit
  (per-vehicle `{platformId, stuckUntil}`, reset when the vehicle moves); while
  stuck, every close attempt bounces straight back open AND `startUp` is refused
  (manual stuck window checked in `shouldObstructDeparture`), so the driver
  cannot power away through an obstruction. Manual vehicles are excluded from
  the ATO roll so no departure is ever rolled by both paths.
- **Exposure**: `DoorObstructionEngine.obstructedVehicleIds()` (wall-clock-
  deadline map, pruned on read, server thread). `AddonInit`'s existing 10-tick
  hold-state ticker now also diffs this set and broadcasts new S2C
  `addon_door_obstructions` (varint count + vehicle-id longs) on change; join
  sync included. Client: minimal `ClientDoorObstructions` static set +
  receiver in `AddonClientInit`, cleared on disconnect — the HUD agent renders it.
- Per-event logging at debug only.
- **ATO vs manual in one line**: ATO rolls when the simulation tries to depart
  (startUp), manual rolls when the driver's door-close lands
  (updateRidingEntities); both share the config, the reopen mechanism, the
  broadcast set, and the "only at platforms" guard.

Files: `mtraddon/DoorObstructionEngine.java` (new),
`client/mtraddon/ClientDoorObstructions.java` (new),
`mixin/VehicleMixin.java` (obstruction check + updateRidingEntities bracket),
`mtraddon/AddonServerConfig.java` (doorObstruction section),
`mtraddon/AddonNetworking.java` (DOOR_OBSTRUCTIONS_S2C + sync/broadcast),
`mtraddon/AddonInit.java` (ticker diff + join sync + clear),
`client/mtraddon/AddonClientInit.java` (receiver + disconnect clear).

---

## Analytics Agent — Timetable & headway analytics — 2026-08-08

Per-train arrival/departure logging on the simulator threads, an append-only JSONL log,
derived on-time/headway/dwell/bunching metrics computed entirely off-thread, a new
`/dispatch/api/analytics` endpoint, an **Analytics** view + **station heat mode** in the
dispatch board, and a `/dispatch stats [line]` chat scorecard. Additive throughout: the
only shared files touched are `AddonServerConfig` (new section), `AddonInit` (two lifecycle
calls + the command registration), `VehicleMixin` (one line in an existing callback + two
new injectors) and `DispatchApiServlet` (one new endpoint). No existing behaviour changed.

### A) Event sources — where the data comes from

Both sources are **edges of calls MTR already makes**; neither adds a scan or a tick loop.

| Event | Hook | Why it is exact |
|---|---|---|
| **DEPARTURE** | `Vehicle.startUp(JJ)V` — capture at HEAD (inside the existing hold/obstruction callback), emit at **TAIL** | `javap -c` on 4.0.1: `startUp` has a **single RETURN** (offset 252), so `@At("TAIL")` is unambiguous. A hold-rule or door-obstruction cancel returns from the HEAD callback and never reaches TAIL, so held/bounced attempts log nothing. A non-committing attempt (doors still closing, `doorCooldown > 0`) leaves `speed == 0`; the committing one sets `speed = 4.0E-6`, so `speed != 0` at TAIL is exactly "this train really pulled out". The platform is captured at HEAD because a committing `startUp` adds `ACCELERATION_DEFAULT` to `railProgress` and the boundary test would then fail. |
| **ARRIVAL** | `Vehicle.simulateMoving(JL…ObjectArrayList;I)V` at `INVOKE` → `Vehicle.updateDeviation()`, `shift = AFTER` | `javap -c`: `updateDeviation()` is invoked **exactly twice in the whole class** — once in `startUp` (offset 89) and once in `simulateMoving` (offset 726) — so the injection point inside `simulateMoving` is unique and `defaultRequire: 1` is satisfiable. That call site is the `railProgress >= stoppingPoint` branch, i.e. MTR clamps `railProgress`, sets `speed = 0` and refreshes the deviation. Because the branch is only taken on the tick a vehicle comes to rest, **the hook costs nothing on every other moving tick** — there is no per-tick analytics work anywhere. Landing AFTER the call means the logged deviation is the freshly recomputed one. |

Both are filtered by the **same stopped-at-a-platform test** the rest of the addon uses
(`HoldRuleEngine` / `DoorObstructionEngine` / `DispatchSampler`): `railProgress` must sit
exactly on the next path segment's `startDistance`, and the previous segment must carry a
`savedRailBaseId` with `dwellTime > 0`. Mid-route signal stops, terminus reversals and the
run back into the siding therefore never produce events.

**Dwell is measured, not read off MTR.** `elapsedDwellTime` saturates at the scheduled
dwell, so a train held by Feature 1 or by a door obstruction would look punctual. Dwell is
`departure timestamp − arrival timestamp` from the in-memory open-arrival map;
`elapsedDwellTime` is only the fallback when no arrival was seen (server restarted
mid-dwell, or the feature was enabled while the train was already standing), and the event
then carries `dwellSrc: "elapsed"` so it is distinguishable after the fact.

**Documented gap:** a route's FINAL approach (end of a non-repeating journey, where the
stopping point is `totalDistance − …` rather than a segment boundary) is not a platform
arrival by the test above, so it produces no arrival event; if the train nonetheless
departs from a platform there, that departure falls back to `elapsedDwellTime`. Manual
(driver-controlled) trains ARE included — `startUp` is their departure choke point too —
but their deviation is whatever MTR last computed, which for a manual siding can be stale.

### B) Storage — append-only JSONL, and why not SQLite

`<save>/station-announcer-addon/analytics/YYYY-MM-DD.jsonl`, one JSON object per line,
rotated by local calendar day, pruned to `analytics.retentionDays` (default 7) **on server
start and on each rotation**.

Justification (the decision the brief asked for):

1. **No new dependency.** SQLite needs a JDBC driver shaded into the jar; this mod ships
   with zero third-party dependencies today and MTR already shades a small universe of its
   own. Adding one for a workload that is 99 % sequential appends is a bad trade.
2. **The workload is append-only.** The log is never queried at runtime — every metric is
   served from an in-memory window. The only read is one bounded tail-replay at startup.
3. **Retention is a file delete**, not `DELETE … ; VACUUM`.
4. **Crash safety.** A process killed mid-write truncates at most the last line, which the
   parser skips. A half-written SQLite page inside the world save is a worse failure mode.
5. **Thomas can use it directly** — `grep`, `jq`, or a spreadsheet import, no tooling.

Writes are **queued and flushed on a background daemon thread**
(`station-announcer-analytics`), never on a simulator or server tick. The hand-off is a
bounded `ArrayBlockingQueue` (`analytics.queueCapacity`, default 8192): when it is full
events are **dropped and counted**, and a warning is logged at most once a minute — a
simulator tick is never blocked on I/O. The drop counter is surfaced in the payload
(`dropped`) and in the Analytics header so a saturating server is visible, not silent.

One JSONL line (~230 bytes) looks like:

```json
{"t":1786500000000,"ev":"dep","dim":"minecraft/overworld","veh":"8123456789",
 "plat":"987654321","platName":"1","sta":"123456789","staName":"City Hall|市政廳",
 "rt":"555555","rtName":"Lexington Av Express|…","rtNum":"4","rtColor":1092784,
 "sid":"777","depot":"Main Depot","stop":3,"dev":1500,
 "dwell":12500,"schedDwell":10000,"dwellSrc":"measured","schedHw":300000}
```

`ev` is `arr` or `dep`; arrival lines omit the last four keys. All MTR ids are **decimal
strings** (they exceed 2^53). Names are MTR-raw `"English|Other"`. Arrival logging can be
turned off (`analytics.logArrivals = false`) to roughly halve the log — every metric is
computed from departures, so nothing is lost but the raw arrival record.

### C) Derived metrics

Computed on the writer thread, at most every `analytics.aggregateSeconds` (30), over the
last `analytics.windowMinutes` (60) of **departure** events, and published as a volatile
immutable snapshot with its JSON payload pre-rendered.

- **On time** — `|deviation| <= analytics.onTimeToleranceSeconds` (60). MTR only refreshes
  a vehicle's deviation when it stops, so a departure's value is precisely "how late this
  train was at this stop" — the one moment the number is meaningful.
- **Dwell overrun** — `measured dwell − the dwell baked into the path`. The baked value is
  `PathData.getDwellTime()`, which **already carries Feature 2's per-route override**, so
  an express with a 10 s override is not scored against the platform's 25 s default.
  Reported signed (avg + max) — a negative average means trains are leaving early.
- **Headway** — the gap between consecutive departures of the **same line at the same
  platform**. Pooling platforms would mix directions and terminus loops, so gaps are always
  computed per `(route, platform)` and only then pooled for the line. Gaps above 6 h are
  service breaks and are excluded.
- **Scheduled headway** — derived at record time from MTR's depot frequencies, exactly
  reversing `Depot.generatePlatformDirectionsAndWriteDeparturesToSidings` (javap -c on
  4.0.1): the departure interval is `14 400 000 / getFrequency(hour)` nominal-day millis,
  mapped onto real simulation time by `× getGameMillisPerDay() / 86 400 000`. Where several
  depots run one route their rates add (`headway = 1 / Σ rate`). Cached per route for 60 s
  on the simulator thread. It returns **0 — "not derivable"** for real-time-timetable depots
  (`getUseRealTime()`), continuous-movement modes (cable cars, which depart every
  `CONTINUOUS_MOVEMENT_FREQUENCY = 8000` ms per siding rather than by frequency) and routes
  with no frequency set for the current hour. In that case the aggregator substitutes the
  **median observed gap** and the payload says `headwaySource: "observed"` (or `"none"`
  when there is not even one gap yet) — the reference is never silently mislabelled.
- **Bunching** — a gap shorter than `analytics.bunchingFraction` (0.5) of the reference
  headway. Up to 20 most recent alerts per line, each with platform, station, gap and time.
- **Per station** (the map heat view): average and max dwell overrun, on-time percentage,
  and **headway irregularity** = the coefficient of variation (stddev ÷ mean) of every gap
  observed at that station's platforms. 0 = metronomic, ≥1 = wildly uneven.

### D) `GET /dispatch/api/analytics?dimension=N`

Wrapped in **MTR's standard `ServletBase` envelope** — built with MTR's own
`org.mtr.core.integration.Response`, so it is shaped identically to `/dispatch/api/network`:

```json
{ "code": 200, "currentTime": 1786500000000, "text": "Success", "version": 1,
  "data": { …payload below… } }
```

It is answered **synchronously on the Jetty worker, before ServletBase's
`simulator.run(...)` hop** (intercepted in `doGet`/`doPost`), because the aggregate is a
volatile snapshot computed off-thread — polling it costs the simulation literally nothing.
`400 {"error":"invalid dimension"}` for a bad index, plain `503` while the registry is
empty (server stopping / dispatch disabled). `data`:

```json
{
  "schemaVersion": 1,
  "enabled": true,                    // false = analytics.enabled is off; all lists empty
  "dimension": "minecraft/overworld", // MTR's dimension string for the requested index
  "computedAt": 1786499990000,        // when this aggregate was built (compare with envelope currentTime for age)
  "windowMinutes": 60,
  "aggregateSeconds": 30,
  "onTimeToleranceSeconds": 60,
  "bunchingFraction": 0.5,
  "departures": 124,                  // departures in the window for THIS dimension
  "recorded": 271,                    // events accepted since server start (all dimensions)
  "dropped": 0,                       // events lost to a full queue since server start

  "lines": [
    {
      "id": "555555",                 // route id, decimal string
      "name": "Lexington Av Express|…",// MTR-raw, split on "|"
      "number": "4",
      "color": 1092784,               // 24-bit 0xRRGGBB
      "departures": 42,
      "onTime": 37,
      "onTimePct": 88.1,
      "avgDeviationMs": 4200,         // + late, − early
      "worstLateMs": 61000,
      "worstEarlyMs": -8000,
      "avgDwellMs": 12500,
      "avgDwellOverrunMs": 2500,      // signed: measured dwell − baked dwell
      "maxDwellOverrunMs": 9000,
      "headwaySamples": 37,
      "avgHeadwayMs": 305000,         // −1 = no samples yet (same for the next three)
      "medianHeadwayMs": 300000,
      "minHeadwayMs": 90000,
      "maxHeadwayMs": 620000,
      "refHeadwayMs": 300000,         // the reference the UI draws + bunching is measured against
      "headwaySource": "scheduled",   // "scheduled" | "observed" | "none"
      "bunching": [
        { "atMs": 1786499000000, "platformId": "987", "platform": "1",
          "stationId": "123", "station": "City Hall|…", "gapMs": 90000 }
      ],
      "series": [ [1786490000000, 300000], … ]   // [atMillis, gapMillis], oldest first, ≤240
    }
  ],

  "stations": [
    { "id": "123456789", "name": "City Hall|…", "departures": 30,
      "onTimePct": 90.0, "avgDwellOverrunMs": 2000, "maxDwellOverrunMs": 9000,
      "headwaySamples": 25, "headwayIrregularity": 0.34 }
  ]
}
```

### Frontend (dispatch board, unchanged endpoints)

- **Analytics view** — a top-bar toggle opening a full-stage overlay: per-line cards
  (on-time %, headway vs reference, dwell overrun, bunching list) each with a **canvas
  headway strip chart** (time on x, gap on y, dashed reference headway, shaded bunching
  band, sub-threshold points in red), plus a station table with heat swatches.
- **Station heat mode** — a `Heat:` selector (off / dwell overrun / headway irregularity)
  recolours the station rectangles on the existing track map, with its own legend under the
  speed/signal legend. Stations with no samples in the window stay neutral grey.
- **Polling is strictly demand-driven**: the interval (`ANALYTICS_POLL_MS = 15000`, a
  constant at the top of `app.js`) is created only while the Analytics view is open **or**
  heat mode is on, and torn down as soon as neither is true. It also skips while the tab is
  hidden. With just the map on screen the page makes **zero** analytics requests. While the
  Analytics overlay is up the map's rAF loop early-returns instead of drawing under it.
- `?demo=1` now also synthesises an analytics payload, so the whole view (and the heat map)
  can be exercised with no server. **Verified that way in a browser**: both views render,
  charts draw, heat recolours the two demo stations, zero console errors.

### E) `/dispatch stats [line]`

Permission `editPermissionLevel` (default 2 — the same gate the addon's other admin
surfaces use, matching `AnnounceCommand`'s `ServerConfig` pattern). Registered from
`AddonInit.register()`. No new item, no GUI.

```
/dispatch stats              → every line in the caller's dimension
/dispatch stats <line>       → one line, matched on route number (exact) or name (substring);
                               tab-completes from the live aggregate
```

Prints a header (window, departure count, aggregate age) and per line: on-time % coloured
green/yellow/red with the average and worst deviation; average / worst / tightest headway
against the reference and its source; average dwell and overrun; then either "no bunching"
or the alert count with the three most recent gaps (duration, station, platform). The
dimension is resolved as `"<namespace>/<path>"` from the caller's world — the exact form
MTR's `Init.getWorldId` uses to key its simulators, so it matches the recorded events.
Reads only the published snapshot: no simulator hop, no file access, no recomputation.

### F) Config keys (`config/station-announcer-addon.json`, new `analytics` section)

| Key | Default | Meaning |
|---|---|---|
| `analytics.enabled` | `true` | Master switch. **Off = zero work anywhere**: both `Vehicle` hooks return on one volatile read, no queue/thread/file is created, and the endpoint answers `enabled:false` with empty lists. |
| `analytics.retentionDays` | `7` (1–365) | Day files kept; pruned on start and on rotation. |
| `analytics.aggregateSeconds` | `30` (5–600) | Longest interval between metric recomputations. |
| `analytics.onTimeToleranceSeconds` | `60` (1–3600) | On-time threshold on `|deviation|`. |
| `analytics.bunchingFraction` | `0.5` (0.05–1.0) | Gap fraction below which a bunching alert fires. |
| `analytics.windowMinutes` | `60` (5–1440) | How much history the metrics cover. |
| `analytics.flushSeconds` | `5` (1–60) | Queue-drain / disk-append cadence. |
| `analytics.queueCapacity` | `8192` (256–262144) | Bounded hand-off queue; overflow drops + counts. |
| `analytics.maxWindowEvents` | `20000` (1000–500000) | Hard per-dimension memory cap on the window. |
| `analytics.logArrivals` | `true` | Write arrival lines too (metrics never need them). |

### Thread model

| Thread | What it does |
|---|---|
| **Simulator** (per dimension) | The two `Vehicle` hooks. Reads the volatile config, that simulator's own data, and `ConcurrentHashMap`s whose individual keys have exactly one writer; `offer`s to the bounded queue. Never touches the Minecraft world, files, or the aggregate. |
| **`station-announcer-analytics`** (daemon) | Drains the queue every `flushSeconds`, appends JSONL, folds departures into the window, recomputes + publishes the aggregate (throttled), prunes abandoned open arrivals. All file I/O and all JSON building live here. |
| **Server** | `SERVER_STARTED` opens the log dir, prunes, replays the recent tail into the window and starts the writer; `SERVER_STOPPING` shuts the writer down (with a 2 s `awaitTermination` so there is never a second writer on the file), flushes the queue tail synchronously and closes. The command reads the published snapshot. |
| **Jetty worker** | Reads the published snapshot and writes the envelope. No simulator hop. |

### Known limitations / risks

- **Not compiled or in-game tested** (no-Gradle rule). Every MTR member used was
  javap-verified against `FABRIC-4.0.1+1.20.4`; the frontend was exercised in a real
  browser via `?demo=1`.
- The `simulateMoving` injector is the one genuinely new mixin shape here. It relies on
  `updateDeviation()` being invoked exactly once inside that method (bytecode-confirmed at
  offset 726). Should MTR ever inline or duplicate that call, the injector fails loudly at
  apply time rather than misbehaving.
- **"Line" = MTR's `vehicleExtraData.getThisRouteId()`** for the vehicle at that stop — the
  same field the dispatch stream calls `route`. For a depot chaining several routes that is
  the leg the train is about to run, which is the right attribution for "a departure on
  line X", but it means the last stop of route A is attributed to route B.
- Scheduled headway is per **route across depots**, not per platform or direction. A route
  whose depots use real-time timetables or continuous movement always falls back to the
  observed median (labelled as such).
- Deviation is MTR's, so it inherits MTR's semantics: refreshed only at stops, and
  meaningless for manual sidings with `departureIndex == -1`.
- The metric window is memory-only. A restart replays at most today's + yesterday's log
  (bounded to 200 000 lines per file) and only events inside the window; history older than
  that lives in the JSONL files but is not re-aggregated. There is deliberately no
  historical query API — the log is for offline analysis.
- Day-file rotation uses the **server's local time zone**, and retention counts calendar
  days, not 24 h periods.
- Events are logged per dimension but the `recorded`/`dropped` counters are global
  (one queue serves every simulator).
- No lang keys were added — the command's output is plain literal English, like the rest of
  the dispatch tooling.

### Files

**New:** `src/main/java/com/stationannouncer/mtraddon/analytics/{AnalyticsEvent,
AnalyticsRecorder,AnalyticsStore,AnalyticsAggregator,AnalyticsCommand}.java`.

**Modified (additive only):**
`src/main/java/com/stationannouncer/mtraddon/AddonServerConfig.java` (new `Analytics`
section + clamps), `src/main/java/com/stationannouncer/mtraddon/AddonInit.java`
(command registration, `AnalyticsRecorder.start/stop` on the existing lifecycle hooks),
`src/main/java/com/stationannouncer/mixin/VehicleMixin.java` (one capture call inside the
existing `startUp` HEAD callback + two new injectors, both fully commented),
`src/main/java/com/stationannouncer/mtraddon/dispatch/DispatchApiServlet.java`
(`analytics` endpoint),
`src/main/resources/assets/station_announcer/dispatch/{index.html,style.css,app.js}`
(Analytics view, heat mode, demand-driven polling, demo payload).

**Unchanged on purpose:** `station_announcer.mixins.json` (no new mixin classes — the two
injectors live in the already-registered `VehicleMixin`), every other feature's code, and
the existing dispatch endpoints' behaviour.

---

## Disruptions Agent — Temporary stop changes + service disruptions — 2026-08-08

Two halves, both additive: a runtime **route overlay** that can skip or add a stop
without ever touching the saved route, and a **disruption** record that drives the
existing PA/sign system automatically.

### A) Temporary stop changes (6a) — the overlay mechanism

`mtraddon/disruption/StopOverlayEngine.java` + two extra calls inside the existing
`mixin/DepotMixin.java` handlers.

**How it works (bytecode-verified against MTR FABRIC-4.0.1+1.20.4).** MTR's
`Depot.writeRouteCache` flattens all the depot's `Route.getRoutePlatforms()` into
the private `platformsInRoute` list (consecutive duplicate platforms collapsed),
and `Depot.generateMainRoute` builds the path-finder chain from exactly that list
(`new SidingPathFinder<>(data, platformsInRoute.get(i).platform,
platformsInRoute.get(i + 1).platform, i)`), while `Depot.tick`'s completion callback
(`siding.generateRoute(platformsInRoute.get(0)…, size, …)`) and
`Depot.getVehiclePlatformRouteInfo(stopIndex)` index the same list. Feature 5
already swaps the `platform` FIELD of individual entries; this feature changes the
list's **shape**:

- **Skip a stop** = remove that entry. The path finder then routes straight from
  the predecessor to the successor, which IS "runs through without stopping" —
  provided the track allows it (see the failure note below).
- **Add a stop** = insert a new entry. `Depot$PlatformRouteDetails` is
  package-private with a *private* `(Platform, Route, int)` constructor (javap:
  plus the synthetic 4-arg bridge), so neither an import nor a Mixin factory
  invoker can name it — the entry is built by **reflection** with a cached,
  `setAccessible(true)` constructor (a couple of calls per depot generation, cold
  path). `route`/`platformIndex` follow MTR's own convention: they describe the leg
  LEADING to the stop, so an inserted stop gets the anchor stop's route and index.

**`Route.getRoutePlatforms()` is never modified, and nothing is written to MTR's
data files.** The overlay is re-applied from our own store every time MTR rebuilds
the list.

**Composition with Feature 5 (the part that needed care).** `PlatformGroupEngine`
sanity-checks `walk.size() == platformsInRoute.size()` and disables itself with a
warning on a mismatch — so an overlay that changes the size must never be visible
to it. Injector ordering between separate mixins is not contractual, so both
engines are now called from the *same* `DepotMixin` handlers, in an explicit order
(two added lines per handler, no behaviour change to Feature 5's own code):

1. `generateMainRoute` HEAD: `StopOverlayEngine.restorePristine` → undo last
   overlay so the list is exactly as MTR built it; `PlatformGroupEngine
   .onGenerateMainRoute` → rotate groups (in-place field swaps, size unchanged);
   `StopOverlayEngine.onGenerateMainRoute` → re-apply the overlay on top.
2. `writeRouteCache` TAIL: `PlatformGroupEngine.onWriteRouteCache` → re-apply group
   choices to the freshly rebuilt list; `StopOverlayEngine.onWriteRouteCache` →
   drop the now-stale undo record and re-apply the overlay, so
   `getVehiclePlatformRouteInfo` keeps matching the baked path between generations.

`restorePristine` only restores when the live list is still, element for element
**by identity**, the list we produced; anything else means MTR rebuilt it in
between and the record is simply dropped. Undo records live in one
`ConcurrentHashMap<depotId, {pristine[], applied[]}>`, cleared on SERVER_STOPPED
and on world load.

**Safety rails inside the overlay**
- After removals/insertions the list is **re-collapsed**: a duplicate adjacent pair
  would make MTR build a platform→itself path finder. MTR keeps the first of a pair;
  we do the same, except an ADDED entry always loses (it is the optional one).
- If the result would have fewer than two stops the overlay is skipped entirely
  (MTR would report `TWO_PLATFORMS_REQUIRED`), logged once.
- If our replica of MTR's collapse loop ever stops matching `platformsInRoute`,
  the overlay disables itself with one logged warning and touches nothing. It
  recovers automatically at the next `writeRouteCache`.

**Generation failure detection (the honest answer).** We cannot prove in advance
that a skip leaves a routable path. What we do: the "Stops…" route list shows
MTR's own `Depot.getLastGeneratedStatus() == PATH_NOT_FOUND` for the depots running
that line, so a skip whose neighbours cannot be joined by track shows up as a red
"depot path generation failed" line right where the change was made.

**Added-stop validation** (`StopOverlayEngine.validateAddition`, simulator thread,
run BEFORE anything is stored): the platform must resolve, share the anchor's
transport mode, not equal the anchor or the following stop, and **at least one of
its rails must appear in the depot's currently generated path** (`PathData
.getOrderedPosition1/2` + `Platform.containsPos`) — i.e. the trains already run
over it, no rerouting over new track. Deliberately a whole-path check rather than
a per-leg one, because the baked path's leg indices drift as soon as another
overlay is active; where exactly the stop is spliced is the user's choice, and an
impossible splice surfaces as MTR's own `PATH_NOT_FOUND`. Failures come back to
the player as an action-bar reason (`msg.station_announcer.stop_change.*`).

**Expiry.** Each change carries `expiresAtMillis` (0 = until turned off). Checked on
the addon's EXISTING `END_SERVER_TICK` handler, once a second, guarded by a volatile
`AddonStore.nextExpiryMillis` — with nothing time-limited the check is one field
comparison. On expiry the entry is removed, the snapshot republished and the S2C
sync rebroadcast; the route reverts cleanly at the next generation.

### B) Disruptions (6b) — data model

`mtraddon/disruption/Disruption.java`:
`record Disruption(long id, long[] routeIds, String message, Severity severity,
long startMillis, long endMillis, boolean active)` with
`Severity = INFO < MINOR < MAJOR < SEVERE` (ordinal order is the sort key).
`isActiveAt(now)` = toggled on AND inside its window; `startMillis`/`endMillis` are
absolute epoch millis (0 = "now" / "until turned off"), entered in the GUI as
offsets ("starts in N min", "ends after N min") so no date picker is needed.
Ids are creation-time millis, nudged forward on collision.

### C) PA / sign integration — the exact API added

Two new **public final** methods on `block/AbstractPaBlockEntity` (and nothing else
in the PA code changed — no existing path calls either of them, so every
pre-existing PA behaviour is bit-for-bit unchanged when they are unused):

- `announceExternal(String message)` — fires ONE announcement with the supplied
  text. Identical player experience to a normal firing (chime, TTS, chat,
  per-player loudest-source volume, push to linked displays via the existing
  `onFired` hook), but the block's stored pool, `MessageIndex`, delay, tag and
  presentation flags are never read or written, and its own auto-trigger keeps
  running in between. The broadcast loop is a deliberate copy of `fire()`'s —
  `fire()` was NOT refactored to delegate, precisely so its bytecode is untouched.
- `showExternalDisplayMessage(String message)` — pushes to the block's linked
  displays only (no chime/TTS/chat) through the same `onFired` hook; `""` clears
  the banner, which is how a display reverts.

Plus one additive method on `AnnouncerRegistry`:
`forEachLoaded(MinecraftServer, Consumer<AbstractPaBlockEntity>)` — visits the
loaded PA sources under the registry lock.

**No new NBT format.** Displays are updated exclusively through the existing
`ControlBoxBlockEntity.onFired` → `PaDisplay.showPaAnnouncement(String)` →
`live_message`/`live_start` path the NYC PIDS renderers already read.

### D) Broadcasting — `mtraddon/disruption/DisruptionBroadcaster.java`

Event-driven and throttled, per ARCHITECTURE §6:

- **Ticker**: runs on the addon's existing `END_SERVER_TICK` handler, once a second
  (a new countdown in `AddonInit`, no new loop). Feature off, or zero stored
  disruptions → returns after one or two field reads.
- **Affected-station scan**: on the SIMULATOR threads via `simulator.run(...)`,
  only when the disruption set changed (`AddonStore.disruptionVersion()`) or after
  `disruptions.stationRescanSeconds`. For each `Station` it checks
  `station.savedRails` → `Platform.routes` against the affected route ids and emits
  a plain `StationScope(dimension, stationId, name, minX/maxX/minZ/maxZ, servedRouteIds)`
  record (no MTR references), handed back with `server.execute`. Station bounds come
  from `AreaBase.getMinX/getMaxX/getMinZ/getMaxZ`; **Y is deliberately ignored**
  because MTR station areas are 2-D.
- **PA sweep**: `AnnouncerRegistry.forEachLoaded` is walked ONLY on ticks where at
  least one station is due to announce or the display banner needs refreshing.
  A block is inside a station when its world's MTR dimension id
  (`Init.getWorldId(new org.mtr.mapping.holder.World(world))`, cached per world
  instance) and its X/Z fall inside a scope.
- **Simulator access**: new `mixin/MainSimulatorsMixin` captures
  `org.mtr.core.Main.simulators` at the constructor's RETURN into
  `disruption/MtrSimulators`. `DispatchRegistry` was NOT reused: it is only
  populated from the `Webserver.start()` redirect, which needs MTR's webserver AND
  `dispatch.enabled` — disruptions must not depend on either. Cleared on
  SERVER_STOPPING before `Main.stop()`.

**Severity / cycling rules (the documented choices)**
- **Speech**: one disruption per station per `announceIntervalMinutes` (default 5),
  cycling through that station's applicable disruptions in **severity order**
  (SEVERE → INFO, ties by creation id) — three alerts at one station are all spoken
  over three cadences.
- **Displays**: always the **highest-severity** applicable disruption, re-pushed
  every `displayRefreshSeconds` (default 30) because the PIDS live banner is
  time-limited on the renderer side.
- Normal PA message pools are never suppressed or edited; they keep running between
  disruption announcements.

**Revert**: when a disruption expires / is toggled off / is deleted, when the last
one goes away, or when the feature is disabled, every PA source we pushed to gets
`showExternalDisplayMessage("")` and the screens fall straight back to their usual
content. The same happens per block when it stops being inside an affected station.

### E) Storage, snapshot, networking

`AddonStore` gains three JSON sections in the same style as the rest:

```json
"disabledStops": { "<routeId>:<stopIndex>": { "expiresAtMillis": 0 } },
"addedStops":    { "<routeId>:<stopIndex>": { "platformId": 42, "expiresAtMillis": 0 } },
"disruptions":   { "<id>": { "routeIds": [..], "message": "...", "severity": "MAJOR",
                             "startMillis": 0, "endMillis": 0, "active": true } }
```
plus volatile `nextExpiryMillis` / `disruptionCount` / `disruptionVersion` so the
ticker is O(1) when idle. `AddonSnapshots.stopOverlays()` publishes a volatile
immutable `Long2ObjectOpenHashMap<RouteStopOverlay>` (route id → parallel
`int[] disabled`, `int[] addAfter`, `long[] addPlatform` — allocation-free linear
scans over a handful of entries on the simulator thread).

`mtraddon/disruption/DisruptionNetworking.java` (own class, `AddonNetworking`
untouched), all validated the same way as the existing channels (op level
`editPermissionLevel`, hard wire caps, config caps):
`addon_stop_changes` (S2C), `addon_update_stop_change` (C2S),
`addon_disruptions` (S2C), `addon_update_disruption` (C2S). Both S2C maps are sent
EMPTY while their feature flag is off, and both are pushed on join and re-broadcast
after every edit and every expiry.

### F) Config keys (`config/station-announcer-addon.json`)

```
stopChanges.enabled            true    master switch for the route overlay
stopChanges.maxPerRoute        8       skips + additions together, per line
stopChanges.maxDurationMinutes 10080   longest duration a change may be given

disruptions.enabled                    true  master switch (off = zero work)
disruptions.announceIntervalMinutes    5     per-station PA cadence (1–120)
disruptions.maxActive                  16    stored disruptions (1–128)
disruptions.maxMessageLength           240   announcement text cap (16–512)
disruptions.maxRoutesPerDisruption     16    affected lines per disruption (1–64)
disruptions.stationRescanSeconds       300   affected-station rescan (30–3600)
disruptions.displayRefreshSeconds      30    PIDS banner refresh (5–600)
disruptions.includeStandaloneAnnouncers false also drive PA Announcer blocks
```
Client: `showDisruptionsButton` in `station-announcer-addon-client.json`.

### G) GUI entry points

A **"Disruptions"** button injected on MTR's `DashboardScreen` via
`ScreenEvents.AFTER_INIT` — it splits MTR's "Resource Pack Creator" button exactly
the way the addon's existing "Dispatch" button splits the "Transport System Map"
button on the row above, so the two addon buttons line up on the right of the bottom
two rows. `DashboardScreen` itself is never restructured.

- `DisruptionsScreen` — the hub: every disruption with severity colour, in-force
  state and affected lines; New / Edit / Delete; a marker line counting the active
  temporary stop changes; and the entry to the stop-change screens.
- `DisruptionEditScreen` — message field (with three one-click templates: delays /
  suspension / planned work), severity cycle, Active toggle, affected-lines button,
  "starts in" and "ends after" sliders (`IntSlider` reused).
- `RoutePickerScreen` — hand-drawn checkbox list of the dashboard's routes in their
  own colours (the shared `PlatformPicker` is platform-scoped and not reusable here).
- `StopChangeRoutesScreen` — line list, lines with a change first, marked `*`, with
  the depots' `PATH_NOT_FOUND` status shown when generation failed.
- `RouteStopChangesScreen` — one line's stops with Skip/Restore and +Stop/-Stop per
  stop, a duration slider for new changes, the "Changes apply after the depot
  regenerates its paths" hint, and a **"Regenerate depots running this line"** button
  sending MTR's own `PacketDepotGenerate(DepotOperationByIds)` — the same packet
  Feature 2's dwell screen and MTR's dashboard refresh use.
- `AddedStopPickerScreen` — picks the platform for an extra stop from every platform
  the client knows, ordered by distance from the anchor stop and capped at 200 rows
  (the shared `PlatformPicker` only covers one station / a 16-block radius, which is
  wrong here by definition).

### H) Thread model

- Both Depot hooks and the added-stop validation run on the per-dimension
  SIMULATOR thread (or the server thread with `useThreadedSimulation` off); they
  read the volatile config, the volatile immutable snapshot and the simulator's own
  data. The only mutable simulator-side state is the undo-record `ConcurrentHashMap`.
- The station scan body runs on the simulator threads and returns plain records via
  `server.execute`; everything else in the broadcaster is server-thread only.
- Store mutations, snapshot publishes and every packet build stay on the server
  thread; file I/O stays on the existing debounced executor.
- The reflective `PlatformRouteDetails` constructor is resolved once into a volatile
  field and only invoked from the simulator thread during path generation.

### I) Known limitations / gaps

- **Everything applies at the next depot generation.** Skips/additions are baked
  into the path, exactly like dwell overrides and platform groups. Removing a change
  likewise only heals on the next generation — and between an expiry and that
  regeneration, `getVehiclePlatformRouteInfo` (in-train "next station" info) is
  computed from the reverted list while the path is still the old one, so the stop
  labels can be off by one until the depot regenerates. Same class of caveat
  Feature 5 already documents.
- **We cannot prove a skip is routable.** If the skipped stop's neighbours have no
  direct track, MTR reports `PATH_NOT_FOUND`; we surface that status per line in the
  GUI rather than pre-validating (a pre-check would mean running a path finder,
  which §6 forbids on those threads).
- **A collapsed stop cannot be targeted.** Changes key on `(routeId, indexInRoute)`
  of the occurrence that ADDS the collapsed stop, so at a route boundary where
  route B starts at route A's last platform, only `(routeA, lastIndex)` matches —
  identical semantics to Features 2 and 5, and the same "looks like a dead edit"
  trap.
- **One added platform per anchor index** (the key is `<routeId>:<stopIndex>`), and
  a skip + an addition may share an anchor (they live in separate sections).
- **A line served by several depots**: the overlay is applied to every depot that
  runs the line, which is the intent, but each depot regenerates on its own schedule.
- **Per-route dwell on an added stop**: `DwellOverrideEngine` matches path dwell
  segments against the route walk, which has no entry for an added stop, so the
  extra stop always uses the platform's own default dwell. A skipped stop is handled
  correctly by its existing monotonic matching. Not changed — Feature 2's code is
  untouched.
- **Displays**: only the NYC PIDS block entity implements `PaDisplay` today, and only
  displays LINKED to an in-station PA Control Box are reached (that is the existing
  link model — there is no registry of loose PIDS). Railroad PIDS do not implement
  `PaDisplay` and are not driven.
- **Unloaded PA blocks** cannot be reverted: a control box that unloads while an
  alert banner is on it keeps that banner in its displays' NBT until it is loaded
  again and either re-announced or the renderer's banner window lapses.
- **Station bounds are X/Z only** (MTR station areas are 2-D), so a PA block far
  above or below a station but inside its footprint counts as inside it.
- **Announcement text is exactly what is typed** — no severity prefix is added, so
  the templates carry their own wording.
- Not compiled or in-game tested by this agent (no-Gradle rule). Compile-risk spots
  worth a look: the `@Inject` at `<init>` RETURN in `MainSimulatorsMixin` (Mixin only
  allows RETURN/TAIL in constructors, which is what is used), the unchecked
  `ObjectArrayList<?>` → `ObjectArrayList<Object>` cast in `StopOverlayEngine`, and
  the reflective lookup of `Depot$PlatformRouteDetails` (it degrades gracefully:
  one warning, ADDED stops disabled, skips keep working).

### J) Files

New: `mtraddon/disruption/{StopOverlayEngine,Disruption,DisruptionBroadcaster,DisruptionNetworking,MtrSimulators}.java`,
`mixin/MainSimulatorsMixin.java`,
`client/mtraddon/{ClientDisruptions,ClientStopChanges,DisruptionsScreen,DisruptionEditScreen,RoutePickerScreen,StopChangeRoutesScreen,RouteStopChangesScreen,AddedStopPickerScreen}.java`.

Modified: `mtraddon/{AddonServerConfig,AddonStore,AddonSnapshots,AddonInit}.java`,
`mixin/DepotMixin.java` (two added calls per handler + javadoc on the ordering),
`block/AbstractPaBlockEntity.java` (+2 additive public methods),
`AnnouncerRegistry.java` (+1 additive method),
`client/mtraddon/{AddonClientInit,AddonClientConfig}.java`,
`resources/station_announcer.mixins.json` (+1 entry),
`assets/station_announcer/lang/en_us.json` (+70 keys).

**Untouched on purpose:** `AddonNetworking`, `PlatformGroupEngine`,
`DwellOverrideEngine`, `HoldRuleEngine`, `DoorObstructionEngine`, the dispatch web
layer, the analytics layer, `ControlBoxBlockEntity`, `PaDisplay`, the PIDS block
entities and every renderer.

---

## Depot Rotation Agent — Duplicate line, per-depot rotation, depot groups — 2026-08-08

Thomas's ask: "trains should alternate across a platform group", plus "we need to be
able to group depots together so that they don't dispatch together". True per-train
platform choice stays infeasible (a depot bakes ONE shared main path — Feature 5's
documented gap), so this is the three feasible pieces: a **Duplicate line** tool, a
**per-depot** twist on Feature 5's rotation, and **depot groups** that phase-offset
their members' departures. Additive throughout; the only shared files touched are
`AddonServerConfig`/`AddonStore`/`AddonSnapshots`/`AddonInit` (new sections in the
existing style), `PlatformGroupEngine` (the selection line + a second runtime map),
`DepotMixin` (two new injectors, existing handlers untouched), `AnalyticsRecorder`
(one new public method, nothing existing changed) and the two client-init files.

### A) Duplicate line — how the copy reaches MTR's data

`client/mtraddon/DuplicateLineScreen.java`. **No addon packet and no addon storage**:
the clone goes through MTR's own pipeline, the same one `DashboardScreen` uses.

1. `new Route(source.getTransportMode(), MinecraftClientData.getDashboardInstance())`
   — `NameColorDataBaseSchema`'s `(TransportMode, Data)` constructor assigns
   `id = new Random().nextLong()` (javap -c verified), so the copy is a genuinely new
   route, not a second reference to the old one.
2. Metadata through the public setters: `setName`, `setColor`, `setRouteNumber`,
   `setRouteType`, `setHidden`, `setCircularState` — i.e. everything `RouteSchema`
   carries besides the stops.
3. Stops: one `new RoutePlatformData(platform.getId())` per source stop with
   `setCustomDestination(...)` copied, appended to `copy.getRoutePlatforms()`, then
   `writePlatformCache(copy, dashboard.platformIdMap)` — exactly
   `DashboardScreen.onClickAddPlatformToRoute`'s sequence. `RoutePlatformDataSchema`
   holds only `platformId` + `customDestination`, so there are no other per-stop flags
   to carry.
4. `InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new
   UpdateDataRequest(dashboardInstance).addRoute(copy)))` — byte-for-byte the packet
   `onDoneEditingRoute` sends. `UpdateDataRequest.addRoute(Route)` is public in 4.0.1
   (javap-verified), so no server-side C2S packet was needed.

The name gets the copy suffix appended **per language** ("A|B" → "A (copy)|B (copy)")
so it reads right on signs in either language. A stop whose `platform` reference does
not resolve on this client is **skipped and counted** in the result line — `platformId`
is a protected schema field with no getter, so an unresolved stop cannot be copied at
all (and would be a broken stop anyway).

**The copy is deliberately not assigned to any depot**; the screen says so
(`hint_depot`, `hint_edit`). Which depot runs a line, and in what order, is a routing
decision — and assigning it automatically would silently change a depot's timetable.

### B) Per-depot rotation

`PlatformGroupEngine.applyGroups` picked `validMembers[counter % n]` from a counter
keyed by group (`"<routeId>:<stopIndex>"`), so two depots serving one route followed
the SAME counter and picked the SAME platform. The pick is now
`validMembers[(counter + depotOffset) % n]`, where:

- **`depotOffset` = this depot's stable index among the depots serving that route** —
  computed as "how many OTHER depots that serve this route have a smaller
  `getId()`" (`depotOffsetForRoute`). **Stability guarantee:** MTR depot ids are random
  longs assigned once at creation and persisted, so the ordering is identical across
  restarts, across generation order and on every dimension. It is deliberately NOT the
  iteration order of `simulator.depots` — that is an `ObjectArraySet`, i.e.
  insertion-ordered and therefore load-order dependent.
- Memoized per route per generation in a small local map (a depot has a handful of
  routes; the scan is over `simulator.depots`, also a handful).
- **Single-depot behaviour is bit-for-bit unchanged**: with no other depot on the route
  the offset is 0. Gated by the existing `dynamicPlatforms.enabled`, as asked.
- Result: two depots + a group of ≥2 members always land on different platforms; more
  depots than members wrap, which is simply "the group is not big enough".

**One correctness fix this forced.** `APPLIED_CHOICE` (the platform each group last
resolved to, re-applied at `writeRouteCache` so `getVehiclePlatformRouteInfo` matches
the baked path) was keyed per GROUP, i.e. "last depot to generate wins". With the two
depots now deliberately differing, that would systematically mis-label one of them, so
a second map `APPLIED_CHOICE_BY_DEPOT` keyed `"<depotId>|<routeId>:<stopIndex>"` was
added; the re-apply reads it first and falls back to the shared entry (which is what
data files written before today contain). Persisted as a new
`platformGroupRuntime.choiceByDepot` object; pruned by suffix in `pruneRuntime`. The
shared map is kept because `effectiveStopPlatformId` — Feature 2's dwell attribution —
walks routes, not depots, and has no depot to key on (documented in its javadoc, and
under Limitations below).

`StopOverlayEngine`'s shared-`DepotMixin` ordering and the walk-size self-disable
contract are untouched: the change is inside `applyGroups`' member pick, the list's
size and the call order in both handlers are exactly as the Disruptions agent left
them.

### C) Depot groups — staggering departures

**The hook** (`mixin/DepotMixin.java`, two new injectors,
`mtraddon/DepotGroupEngine.java`). `javap -c` on 4.0.1's
`Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()V` shows the departure
list is a **method-local `LongArrayList`** and that `Siding.addDeparture(J)Z` is invoked
at exactly **one** call site in the entire class (offset 571; `grep` over the full
disassembly confirms a single occurrence), so:

- `@Inject` at **HEAD** resolves the depot's phase offset once into a `@Unique long`
  field — a depot belongs to one simulator and departures are only ever written from
  that simulator's thread inside this method, so no synchronization is needed;
- `@Redirect` on that unique `Siding.addDeparture(J)Z` call adds the offset and passes
  the return value straight through (MTR uses it to advance to the next siding; the
  decision is unaffected because every departure of the pass shifts by the same
  constant, and siding acceptance only ever compares departures of the same pass
  against each other via `tempReturnTimes`).

**The offset**: member `i` of a group of `N` gets `round(i / N × interval)`, where
`interval` is that depot's own mean scheduled departure interval. Member 0 is the
reference and never moves.

**The interval reuses the analytics derivation** rather than re-deriving it: a new
public `AnalyticsRecorder.depotDepartureIntervalMillis(Simulator, Depot)` sits beside
the existing `scheduledHeadwayMillis` and uses its `FREQUENCY_BASE_MILLIS` /
`MILLIS_PER_NOMINAL_DAY` constants (the brief's "reuse, don't duplicate the constant").
The difference: `scheduledHeadwayMillis` pools every depot serving a ROUTE, this one
answers for a single DEPOT. Derivation:
`departuresPerNominalDay = Σ_hour freq(hour) × 3 600 000 / FREQUENCY_BASE_MILLIS`
(the hour index mirrors MTR's own `isTimeMoving() ? i : getHour()` selection), and the
real interval is `getGameMillisPerDay() / departuresPerNominalDay`. For a depot with a
flat frequency this reduces exactly to analytics' single-hour formula. It returns
**0 = "do not stagger"** for real-time-timetable depots, continuous-movement modes
(cable cars use `CONTINUOUS_MOVEMENT_FREQUENCY`, not frequencies) and depots with no
frequency at all — those two branches of MTR's method are therefore never touched.

**THE WRAP RULE: there is none, deliberately, and that is the point.** The offset is
added to *every* departure the depot writes in one pass, so every gap between its own
departures is unchanged, and the repeat cycle length — `getRepeatInterval(0)` =
`gameMillisPerDay × repeatDepartures`, computed by MTR from journey time and the day
length, never from the departure values — is unchanged too. `Siding.matchDeparture`
anchors the cycle on `departures.getLong(0)`, which slides with the rest, so nothing
needs taking modulo anything and no departure can fall off either end of the day; the
timetable simply slides in phase. The offset is clamped to `[0, interval)`: never
negative (a departure could otherwise be pushed before `getMillisOfGameMidnight()`),
and never a whole headway (which would renumber the runs and change nothing).

**Guards, in O(1) order:** `depotGroups.enabled` → snapshot `isEmpty` →
`data instanceof Simulator` → one `get(depotId)` on the membership map → slot > 0 and
group size ≥ 2. Any failure returns 0 and the redirect hands MTR's own value on
untouched. The per-departure cost when staggering is one field read plus an add.

**Taking effect.** Departures are rewritten when a depot regenerates, when all its
sidings finish path generation, and on `Simulator.setGameTime` (any `/time set`) — so
normally an offset change lands at the next generation, which the GUI says. Two places
force it sooner, both via `DepotGroupEngine.refreshOffsets(server, true)`, which hops
onto each simulator thread and calls
`depot.generatePlatformDirectionsAndWriteDeparturesToSidings()` for the affected depots
only (grouped now, or staggered before and just removed — so a removed depot goes back
to MTR's own times):

1. **SERVER_STARTED**, right after the store loads. This one is necessary, not a
   nicety: MTR is our dependency, so its own SERVER_STARTED handler — which constructs
   the simulators and runs `Depot.init()` — has already written the day's departures
   before our store exists, and without the rewrite the stagger would not appear until
   something else regenerated the depot.
2. **After a group edit**, so Thomas hears the change immediately.

That call is a supported runtime operation: MTR itself makes it from `setGameTime` at
arbitrary moments, and it begins by clearing each siding's departure list
(`Siding.startGeneratingDepartures`), so it is idempotent by construction. It is
wrapped in try/catch — a failure leaves MTR's own timetable in place.

### D) GUI

One new dashboard button, **"Tools…"**, injected with `ScreenEvents.AFTER_INIT`: it
splits MTR's own Options button exactly the way the addon's "Dispatch" and
"Disruptions" buttons split the two map rows beside it, so `DashboardScreen` is never
restructured and MTR's layout keeps working. One button rather than two because those
rows are the only splittable space left. Permission-gated on
`MinecraftClientData.hasPermission()`, client toggle `showToolsButton`.

- `DispatchToolsScreen` — the hub: "Duplicate line…" and "Depot groups…", plus a
  configured-group count.
- `DuplicateLineScreen` — every route in its own colour with its stop count and a
  Duplicate button; a result line reporting the new name, the stops copied and any
  skipped; the two hint lines about depot assignment.
- `DepotGroupsScreen` — every group with its members and each member's computed shift
  ("Main Depot +0s, North Yard +2m 30s"), New / Edit / Delete.
- `DepotGroupEditScreen` — name field plus a hand-drawn checkbox list of the
  dashboard's depots (the shared `PlatformPicker` is platform-scoped and unusable
  here), members floated to the top in their offset order. **Tick order is offset
  order**, so each ticked row shows "slot 2 of 3" and, once the server has measured
  that depot's headway, the absolute "+2m 30s" beside it; untick and re-tick to move a
  depot to the end. A depot already in another group is drawn red and cannot be ticked
  (the server refuses it too).

### E) Storage, snapshot, networking

`AddonStore` gains one JSON section in the same style as the rest:

```json
"depotGroups": { "<id>": { "name": "Uptown pair", "depots": [111, 222] } }
```

plus the `platformGroupRuntime.choiceByDepot` object described in B. Ids are
creation-time millis nudged forward on collision (the `Disruption` scheme).
`AddonSnapshots.depotGroups()` publishes a volatile immutable
`Long2ObjectOpenHashMap<int[]>` — depot id → `{offsetSlot, groupSize}` — so the
simulator-thread lookup is one allocation-free `get(long)`; a depot listed twice by a
hand-edited file keeps its first membership.

`mtraddon/DepotGroupNetworking.java` (own class; `AddonNetworking` untouched),
validated like every other channel — op level `editPermissionLevel`, feature flag,
hard wire caps (`MAX_GROUPS` 64, `MAX_DEPOTS` 32, `MAX_NAME_LENGTH` 64) then the config
caps, dedupe, zero ids dropped, one-group-per-depot refused with an action-bar reason:

- `addon_depot_groups` (S2C) — the full list plus each member's last computed offset
  (`-1` = "not computed yet"; the client cannot derive it because `gameMillisPerDay`
  lives on the simulator). Sent on join, after every edit and again once the simulators
  answer the offset refresh. Sent EMPTY while the feature is off.
- `addon_update_depot_group` (C2S) — create / rename / re-member / delete. An emptied
  member list deletes the group. Depot ids are resolved against the railway only at USE
  time on the simulator thread, like platform-group members.

### F) Config keys (`config/station-announcer-addon.json`, new `depotGroups` section)

```
depotGroups.enabled            true   master switch (off = zero work; one field read in the hook)
depotGroups.maxGroups          16     stored groups (1–64)
depotGroups.maxDepotsPerGroup  8      member depots per group (2–32)
depotGroups.maxNameLength      48     group name cap (1–64)
```

Per-depot platform rotation has **no new key** — it is gated by the existing
`dynamicPlatforms.enabled`, as specified. Client: `showToolsButton` in
`station-announcer-addon-client.json`.

### G) Thread model

| Thread | What it does |
|---|---|
| **Simulator** (per dimension) | Both new `Depot` injectors, and the bodies of `refreshOffsets`. They read the volatile config, the volatile immutable membership snapshot and that simulator's own data. The only mutable state is `DepotGroupEngine.LAST_OFFSET` (a `ConcurrentHashMap`, one writer per depot) which exists purely so the GUI can show a number — the simulation never reads it back. |
| **Server** | Store mutations, snapshot publishes, packet building, the SERVER_STARTED / SERVER_STOPPED lifecycle, and the fan-out/fan-in around `simulator.run`. |
| **Client** | The four screens and `ClientDepotGroups`, replaced wholesale by the sync and cleared on disconnect. Route duplication happens entirely on the client thread and leaves as one MTR packet. |
| **Store executor** | Unchanged: the new JSON sections are written by the existing debounced writer. |

### H) Known limitations / gaps

- **Not compiled or in-game tested** (no-Gradle rule). Every MTR member used was
  javap-verified against `FABRIC-4.0.1+1.20.4`. Compile-risk spots worth a look: the
  `@Redirect` handler signature `(Siding, long) -> boolean` on a target owned by
  `Depot`, the `@Unique` instance field on a mixin that also `extends DepotSchema`, and
  `for (long id : Long2ObjectOpenHashMap.keySet())` in `refreshOffsets`.
- **The stagger is per depot, not per siding.** All of a depot's sidings share the shift,
  which is exactly "this depot dispatches later than that one". Two trains of the SAME
  depot still leave on MTR's own interval — that is what the interval means.
- **Depots whose interval is not derivable are not staggered**: real-time-timetable
  depots, continuous-movement modes and depots with no frequency set. They are shown in
  the GUI with "shift at next generation" and never get a number, which is honest but
  indistinguishable from "not generated yet". A dedicated "not derivable" state would
  need another wire field.
- **A frequency change does not re-stagger by itself** — the offset is recomputed from
  the new frequency the next time departures are written, which is what MTR does with
  the departures anyway.
- **Feature 2 on a multi-depot grouped stop.** `effectiveStopPlatformId` still reads the
  shared per-group choice (Feature 2's stop walk has no depot to key on), so with two
  depots picking different members, the second depot's dwell segments fall through the
  monotonic matcher and keep the platform's own default dwell. Pre-existing shape,
  newly systematic; fixing it means threading the depot through `DwellOverrideEngine`,
  which is another feature's code.
- **Duplicate line does not copy depot assignment, and cannot** — a depot's route list
  lives on the depot; assigning the copy would change that depot's timetable behind
  Thomas's back. The screen says so.
- **Duplicating relies on the dashboard's client data**, so a stop whose platform is not
  in `platformIdMap` is skipped (reported in the result line), and a client that has
  never opened the dashboard sees an empty list.
- **No confirmation dialog on Duplicate** — pressing it twice makes two copies. They are
  deletable from MTR's own Routes tab.
- **The "Tools…" button is client-toggled only**: it appears even when
  `depotGroups.enabled` is false on the server, in which case the group list syncs empty
  and edits are refused (same shape as the other addon buttons).
- A depot may be in **one group only**; the C2S handler refuses the second and the edit
  screen greys the row. Overlapping groups would fight over one depot's offset.
- **A group of one staggers nothing** (offset 0) — kept storable so a group can be built
  up over two edits.

### I) Files

**New:** `mtraddon/{DepotGroup,DepotGroupEngine,DepotGroupNetworking}.java`,
`client/mtraddon/{ClientDepotGroups,DepotGroupsScreen,DepotGroupEditScreen,
DuplicateLineScreen,DispatchToolsScreen}.java`.

**Modified (additive only):**
`mtraddon/AddonServerConfig.java` (new `depotGroups` section + clamps),
`mtraddon/AddonStore.java` (the `depotGroups` section, `platformGroupRuntime
.choiceByDepot`, load/save/view/put/remove),
`mtraddon/AddonSnapshots.java` (`depotGroups()` + `publishDepotGroups`),
`mtraddon/AddonInit.java` (receiver registration, join sync, startup offset apply,
SERVER_STOPPED clear),
`mtraddon/PlatformGroupEngine.java` (per-depot offset in the pick, the per-depot
applied-choice map and its key helpers, three-arg `seedRuntime`),
`mtraddon/analytics/AnalyticsRecorder.java` (one new public method + two constants;
nothing existing changed),
`mixin/DepotMixin.java` (a `@Unique` field, a HEAD `@Inject` and a `@Redirect`; the two
existing handlers and their documented ordering are untouched),
`client/mtraddon/{AddonClientInit,AddonClientConfig}.java`,
`assets/station_announcer/lang/en_us.json` (+33 keys).

**Unchanged on purpose:** `station_announcer.mixins.json` (no new mixin classes — both
injectors live in the already-registered `DepotMixin`), `AddonNetworking`,
`DwellOverrideEngine`, `StopOverlayEngine`, `DisruptionNetworking`, the dispatch web
layer, the analytics event pipeline and every renderer.

### Disruptions Agent — Update: hand-drawn disruption board + collapsible line picker (2026-08-08)

In-game feedback from Thomas: *"the service disruptions screen doesn't have to be
minecrafty — right now it's making it hard to manage"*, and the line picker listed
every route flat with no way to see or choose the sub-type. Both are **presentation
only** — the data model, the packets, their field order and every server behaviour
are untouched.

**New `client/mtraddon/AddonUi.java`** — the shared look, lifted from this project's
own custom screens (`PlatformPicker`, the PIDS settings screens): `panel()`,
`caption()`, tri-state `checkbox()`, coloured `chip()` with `readableOn()` text,
`inlineButton()`, `scrollIndicator()`, plus the palette constants. Also home to
`firstLang()` and `splitLineAndDirection()`.

**THE NAMING FACT (verified, do not re-derive):** MTR separates a route's **LINE**
from its **DIRECTION** with a **DOUBLE pipe** — `"Line 1||Northbound"`. A SINGLE
pipe is the language separator. Confirmed by disassembling
`org.mtr.mod.data.VehicleExtension.formatRouteName` in the 4.0.1 jar, whose entire
body is `ldc "\\|\\|"; String.split; iconst_0; aaload; areturn` — i.e.
`routeName.split("\\|\\|")[0]`. So the line name is `split("\\|\\|")[0]` and the
direction is `[1]`, each then reduced to its first language segment. Note the trap:
the pre-existing `firstLang` helpers split on the FIRST `'|'`, which happens to
yield the right line name for `"Line 1||Northbound"` but silently discards the
direction — that is why the old flat picker showed "Line 1" four times.

**New `client/mtraddon/LinePicker.java`** — a collapsible picker component in
`PlatformPicker`'s mould (dark panel, hover/selection washes, scissor-clipped
scrolling, thin scroll indicator, `fitTo()` shrink), mutating the caller's selection
set in place.
- **Grouping rule:** routes are one line when they share **both** their colour
  **and** their line name. Two different lines that share a colour do not merge;
  two same-named routes in different colours do not merge.
- **Collapsed by default**: one row per line = tri-state tick box (none / some /
  all), a `>` / `v` affordance, the colour chip carrying the line name, and a
  "n directions" count.
- **The tick box selects/clears the WHOLE line; clicking anywhere else on the
  header expands it.** Expanded rows are indented sub-rows labelled by direction,
  each independently selectable, with a colour spine tying them to their line.
- A line with a single route has no expander (its direction, if any, is shown
  inline on the row) and the whole row toggles it.
- Opens pre-expanded for any line that is only PARTIALLY selected, so an existing
  disruption shows what it actually covers.
- An "Expand / Collapse" control above the list drives `setAllExpanded`.

**`DisruptionsScreen` rewritten** into a hand-drawn board: one 30 px row per
disruption with a severity spine down its left edge, a severity badge, the message
(ellipsised), the affected lines as colour chips **collapsed to one chip per LINE**
(same grouping rule), the time window ("starts in 12 min" / "ends in 45 min"), and
**three inline actions on the row** — an ON/OFF toggle, Edit and a delete `x` —
instead of select-then-act. Clicking a row's body opens it. The toggle sends the
same upsert packet the editor sends with only `active` flipped. Scrolls with the
scissor + indicator pattern; the footer (New disruption / Temporary stop changes /
Done) stays outside the board so it can never be pushed off screen. The list is
re-read from `ClientDisruptions` each frame, so a delete or toggle disappears as
soon as the sync lands.

**`DisruptionEditScreen` rewritten** into labelled sections: **Affected lines** (the
`LinePicker` inline, with the expand/collapse control on the caption row and a live
"n route(s) on m line(s)" hint), **Announcement text** (full-width field with the
three templates as obvious buttons directly under it), **Severity** (four clickable
badges in their real board colours with the chosen one underlined — the cycling
button is gone) beside the Active toggle, and **Schedule** (the two sliders side by
side). Only the picker is flexible, so `fitTo(height - fixed - 44)` absorbs a short
window rather than pushing Done/Cancel off the bottom.

**`RoutePickerScreen` deleted** — the separate flat route screen it replaced no
longer exists; line selection happens inline in the editor.

**`StopChangeRoutesScreen` / `RouteStopChangesScreen`** got the same board
background (panel + hover + row dividers drawn BEFORE `super.render` so their
per-row vanilla buttons stay on top), and the route list now labels rows
"Line 1 - Northbound" using the double-pipe split instead of hiding the direction.

**Compromises / notes**
- Row-level actions and the severity badges are hand-drawn hit boxes, not vanilla
  widgets, so they are not keyboard-navigable or narrated. The footer, the message
  field, the templates, the Active toggle and the sliders stay vanilla widgets and
  keep full accessibility.
- `AddedStopPickerScreen` was left alone (it already drew its own panel), as was
  everything outside this feature — including the concurrently-added
  `DepotGroupsScreen` / `DuplicateLineScreen`, which keep their own layout.
- The dashboard button placement is unchanged and still does not clash: Dispatch
  splits MTR's "Transport System Map", Disruptions splits "Resource Pack Creator",
  and the other agent's "Tools…" splits "Options".
- Unused lang keys from the old layout (`disruptions.lines`, `line_stops`,
  `severity_label`, `state_active/inactive`, `hint`, `lines_title`) were left in
  `en_us.json` rather than removed, to avoid touching lines another agent may be
  editing.
- Not compiled or in-game tested (no-Gradle rule).

**Files** — new: `client/mtraddon/{AddonUi,LinePicker}.java`; rewritten:
`client/mtraddon/{DisruptionsScreen,DisruptionEditScreen}.java`; deleted:
`client/mtraddon/RoutePickerScreen.java`; lightly restyled:
`client/mtraddon/{StopChangeRoutesScreen,RouteStopChangesScreen}.java`;
`assets/station_announcer/lang/en_us.json` (+14 keys).

## Dispatch web UI round 2 — stringlines, holds/alerts, station panel (2026-08-27)

Three features on the existing dispatch stack (no schema-version bump — all additive):

**Stringlines** (pvibien.com/stringline.htm style). New endpoint
`GET /dispatch/api/stringline?dimension=N&routes=id1,id2` (simulator hop):
- `axis.routes[]` — every route with `{id,name,number,color,hidden, stations:[{plat,
  platName, sta, staName, dist}]}`; `dist` = cumulative straight-line metres between
  platform midpoints (`Route.getRoutePlatforms()` order). Cached 30 s per dimension
  like `network`.
- `deps[]` — the analytics window's departure rows for the requested routes only:
  `[veh, plat, t, dwellMs, devMs, stopIndex, routeId]` (ids as strings). Source:
  `AnalyticsAggregator.Aggregate.events`, a NEW immutable time-sorted copy of the raw
  window published with each aggregate (arrival instant = `t − dwellMs`, so departures
  alone carry both the diagonals and the flat dwell segments). Requires
  `analytics.enabled`; `analyticsEnabled`/`windowMinutes`/`now` ride along.
- Frontend: full-stage view, one badge per LINE (routes grouped on the `"||"` name
  key), both directions on one chart (other routes' platforms mapped onto the longest
  route's axis by station id), 15 min–2 h window with the right edge pinned to now,
  hover highlight + tooltip, click-through to the map, live tips driven by new SSE
  vehicle fields `pPlat`/`nPlat`/`pFrac` (platform dwell segments bracketing the
  vehicle + fraction between them, computed in DispatchSampler from the path; always
  present, "0" = none).

**Holds / obstructions / alerts on the stream.** Frames now carry `holds` (platform
ids currently holding, from `HoldRuleEngine.heldPlatforms()`) and `obstructed`
(vehicle ids, `DoorObstructionEngine.obstructedVehicleIds()`) — always on `full`,
delta only when changed (absent = unchanged). New `dispatch/DispatchEvents` ring
(cap 200, monitor-guarded, cleared on SERVER_STOPPING) feeds `alerts[]` on frames +
`GET /dispatch/api/alerts` backlog. Producers: hold deadlock-cap release (only when
the cap fired before the transfer window, i.e. the connection never came),
door-obstruction backstop force-release, and two streamer-thread detectors —
`late` (deviation crosses 3 min, edge-triggered per vehicle) and `stalled` (stopped
90 s with no dwell, hold, obstruction or manual mode — the "walked to the platform to
find a wedged train" class). UI: pulsing amber HOLD rings on the map, HELD/BLK badges
on board rows + detail panel, an alerts feed panel with unread badge; alert rows
click through to the vehicle/platform.

**Station panel.** Clicking a station area (when no train is hit) fills the detail
aside: platforms with calling-route chips, dwell and HELD state; live inbound trains
(next-station match) with deviation, click-to-select; the station's analytics
scorecard when the aggregate is loaded (one-shot fetch, no polling).

Verified: `compileJava` green; full frontend exercised in the browser in `?demo=1`
mode (stringline chart with crossing directions + dwell steps + a long-dwell outlier,
hover/tooltip/dim, badges, alerts feed, HOLD pulse, station panel with all three
sections). NOT verified against a live simulator: the stringline axis/deps payload
shapes, `parameters.get("routes")` content (bytecode says getParameterMap feeds it),
pPlat/nPlat scan cost on very long paths, and detector noise levels on a busy network.

## Dispatch web UI round 3 — variants/filtering, segment stringlines, pvibien controls, audit (2026-08-27)

All frontend (no server/API changes; jar carries the new static files).

**Line variants across the board.** New "Line / Variant" column (line name + the
part after MTR's `"||"` separator, e.g. "Northbound"), and a line-filter chip row
over the board (one chip per line among live trains, colored bullet; signature-
guarded so the 1 Hz board rebuild never flickers it). The active filter also DIMS
non-matching trains on the map to 25% (never hides them).

**Segment (interlined-trunk) stringlines.** "Segment" button in the stringline
head: click two (or more) stations on the axis gutter to bound a corridor — the
chart re-bases to that span and shows EVERY non-hidden route serving ≥2 corridor
stations (matched by station id), i.e. all services interlining over the trunk,
each in its own line colour. Selected station labels highlight; the fetch asks the
server for exactly the qualifying route ids.

**pvibien parity controls** (Thomas's screenshots): Dwell / Run / Headway / Travel
annotation toggles (dwell seconds over the flat segments, run time on the
diagonals, headway = gap to the previous same-route departure per stop from a
precomputed per-geometry list, Σ travel time at each run's end; density-guarded,
and narrowed to the hovered run while hovering) and an END-time scrub slider +
LIVE pin (right edge follows now, or a fixed instant back through the analytics
window; live tips only drawn on the live edge).

**Audit fixes:**
1. Zero-size-canvas race: the first rAF before layout sized both map canvases 0×0
   and drawImage's InvalidStateError killed the loop permanently (reproduced live).
   frame() now skips until the stage has size.
2. Stringline geometry was rebuilt from every departure row at 60 fps; now built
   once into TIME-space ([t, dist] points, headway list included) in sl.built,
   nulled at each invalidation point — per frame only t→x/dist→y mapping remains.
3. A stringline selection change during an in-flight fetch was dropped until the
   next 15 s poll (fetching guard) — refetchWanted queues it.
4. renderStringMeta wrote the DOM every frame; now only on text change.
5. Axis padding constants were duplicated between draw + trace builder; unified.
6. Big-screen readability: canvas fonts/markers scale with uiScale() (min(1.45,
   width/1600)) on the map AND the stringline; CSS media queries at 1600/2200 px
   raise DOM font sizes; station labels brighter; traces thicker.

Verified in ?demo=1 (now with an interlined "7 Local" over Canal–Grand for the
segment demo): map + HOLD pulse render, both line badges, segment corridor
resolves [Canal St, Union Sq, Grand Ave] with routes {rt1n, rt1s, rt7n} and 43
traces over 2 lines, all four annotation draws + a 30-min scrub run exception-free,
scrubbed windows drop live tips, board filter chips built. The browser pane was
hidden for part of the pass, so the segment/annotation views were verified through
state inspection rather than screenshots — worth one visual look in game.

Backlog (not built): direction arrowheads, grayscale mode, CSV export, trip-id
labels, schedule-extrapolated live tips, per-dimension alert filtering,
keyboard/narration accessibility for the hand-drawn chips.

## Dispatch web UI round 4 — consist rendering + wrong-way glide fix (2026-08-27)

**Wrong-way glide (bug, root-caused).** The interpolator slides trains along the
rail polyline by railT, but railT measures progress along the PATH SEGMENT while
the polyline has the rail's own canonical direction — a train traversing a rail
against it interpolated BACKWARDS along the curve, then snapped at the rail
boundary ("glides the wrong way and then jumps"). Fix at sample ingestion: map
both railT and 1−railT through the polyline and keep whichever lands nearer the
sample's true world position (0.5-block hysteresis so near-symmetric midpoints
don't flap). vehiclePos also now returns {rail, railT, paramDir} so downstream
consumers share the corrected parameter.

**Car-level rendering (radar.mta.info style).** The sampler snapshots per-car
lengths (`VehicleCar.getLength()`, static consist block, `carLengths` in the SSE
consist JSON). At view scale ≥1.6 with a known rail the marker becomes the real
consist: each car steps back from the head along the rail curve by its actual
length (+0.8 gap), takes its angle from the local tangent (wraps around curves),
extends linearly past rail ends via the new railDistPoint() (no snapping while a
long train straddles rails), keeps a darker nose on the leading car, per-car
doors-open stripes, selection outline, and the line-filter dimming. Travel
direction in parameter space persists across stops via rec.lastDir. Fallback to
the capsule whenever rail/consist is unknown.

Verified in ?demo=1 with the pane visible: 4-car consists render with gaps, glide
smoothly, articulate past a bend, boat renders as a single 12-block car. The
orientation fix itself cannot be reproduced in demo (demo railT is generated from
the polylines) — watch a real reversed-rail stretch in game.

## Dispatch web UI round 5 — the polish round (2026-08-27)

Thomas: "dig deep… we need to create something beautiful." Frontend-only.

**Chrome redesign:** decluttered topbar (layer checkboxes, transport-mode toggles and
station heat moved into a "Layers ▾" glass dropdown; outside-click closes), a
dispatcher clock, pulsing LIVE dot; every floating panel (legend, detail, alerts,
layers, both tooltips, the board sheet) unified on glass — translucent panel colour +
backdrop blur + one shadow/radius language; thin custom scrollbars; button hover/
active transitions with an accent glow; a faint world-aligned power-of-two grid
behind the map (64–128 px spacing at any zoom) for depth and scale.

**New interactions:** map hover tooltips (nearest train: chip, destination, speed,
deviation, HELD/DOORS flags; else station area: name + platform count + "click for
details"; DOM writes only on content change); keyboard shortcuts B/S/N/A/F + Escape
(closes the topmost overlay, then clears selection; skipped while typing);
localStorage preference persistence (layers, heat, stringline window/annotations/
grey) via savePrefs/loadPrefs, try/caught.

**Stringline additions:** grey mode (all runs neutral grey, colour on hover — for
colour-dominant lines), one direction chevron per run on its longest visible
segment, CSV export of the departure window (vehicle, route+variant, station, ISO
departure, dwell, deviation, stop index).

**Board polish:** sticky header, zebra rows, amber tint on held rows, live train
count beside the filter chips, empty-state message.

**QA found + fixed during the pass:** relocated checkboxes lost the .toggles
accent-color scope and rendered magenta; bottom-row headway labels collided with the
time axis (clamped into the plot); header wrapping stranded the stringline close
button (now pinned absolute); a loadPrefs operator-precedence wart. The recurring
"drawImage width 0" console entry was proven STALE (line number matches the pre-fix
build; document.hidden=true pauses ALL rAF in the pane — a fresh probe rAF doesn't
fire either, so the loop is paused, not dead).

**Verified end-to-end in ?demo=1:** layers dropdown, filter chips + count + variant
column + zebra board on glass, train/station/empty hover tooltip states, segment
mode via real gutter clicks (corridor Canal–Grand, green Express + magenta 7 Local
crossing, accent-highlighted bounds, correct meta), headway labels with density
guard, keyboard handler (synthetic KeyboardEvent — the CDP key path doesn't deliver
while the pane is hidden), prefs full round-trip across reload (including the
checkbox DOM), CSV generation, 2240×1260 viewport (media queries + uiScale, prefs
visibly honoured). Not screenshotted: held-row tint and grey mode (state-verified).

## Dispatch web UI round 6 — search (2026-08-27)

Thomas: search fields for stations and line selection. Frontend-only.

**Global search** in the topbar ("/" focuses it): type-ahead dropdown over three
sections — STATIONS (colour dot; jump-to: centres + zooms the map, opens the
station panel, closes any overlay), LINES (route chip; toggles the board's line
filter, which also dims the map), TRAINS (route chip + live speed; selects,
centres and opens the detail panel). Prefix matches rank above substring;
capped 5/4/4 per section. Full keyboard flow: arrows cycle (wrapping), Enter
runs the highlighted row, Escape clears + blurs; mouse hover moves the
highlight; outside click closes. Glass panel matching the design language.

**Stringline line filter:** a "Filter lines…" input before the badge row narrows
badges by name or route number as you type ("no line matches" placeholder when
empty) — for networks with more lines than the badge strip can show.

Verified in ?demo=1: typing "har" lists Harbor North (station) + three matching
trains with chips and live speeds; Enter jumps to the station, zooms, opens its
panel and clears the box (Enter verified via synthetic KeyboardEvent — the CDP
key path drops keys while the browser pane is hidden; the type path delivered).
LINES rows can't appear in the demo because the demo network payload only lists
the Express routes — real servers list every route.

## Dispatch web UI round 7 — variant toggles on the stringline (2026-08-28)

Thomas: can't toggle a line's directions/segments (Line 1||North A vs ||South A)
individually. Frontend-only.

**Variant chips**: beside the line badges, one chip per route of the ACTIVE set
(the line group, or the segment's interlined routes) labelled by the part after
the "||" separator (route-number-prefixed when the set spans lines), with the
route's colour dot. Clicking toggles that route off/on — off chips dim +
strikethrough, and the route contributes neither platforms, traces, headway
samples nor live tips. Filtering is client-side over the already-loaded deps, so
toggling is instant; the y-axis stays donated by the group's longest route
regardless of toggles, so the chart never re-scales while flipping directions.
The LAST visible variant refuses to toggle off (an empty chart reads as broken).
Selections clear on line-badge change and segment-mode toggle. Chip rendering is
signature-guarded from the draw loop.

Verified in ?demo=1: Northbound/Southbound chips appear for Demo Express;
toggling Southbound off leaves only the parallel Northbound diagonals
(screenshot), chip shows the off state, toggling back restores the crossing
pattern.

## Dispatch web UI round 8 — multi-select everywhere (2026-08-28)

Thomas: make sure multiple chips can be selected at the same time. The variant
chips were already independent toggles; the LINE badges (stringline) and the
board's line-filter chips were single-select — both are Sets now. Frontend-only.

- **Stringline line badges**: click toggles a line in/out of the chart (at least
  one always stays; double-click narrows to just that line). All selected lines
  overlay on one chart — the axis comes from the longest route across the
  selection, other lines map on by station id (the segment-mode mechanism), and
  the variant chips list every route of the union with route-number prefixes.
  `groupKey` → `groupKeys: Set`; union helper `selectedGroupRouteIds()` feeds
  activeRouteIds/axis/buildGeometry/fetch.
- **Board filter chips**: `boardFilter` → Set of line keys (empty = all). Chips
  toggle membership; "All lines" clears; map dimming, row filter, train count,
  empty-state and the search rows all honour the set.

Verified in ?demo=1: both line badges active at once → magenta 7 Local overlaid
on the green Express with combined variant chips (screenshot); board filter with
two lines in the set matches all three demo trains, cleared cleanly.

## Dispatch web UI round 9 — stringline rows, analytics line modal, sortable stations (2026-08-28)

Thomas: stringline head too cluttered (wants lines / options / variants as rows);
analytics line panels should enlarge with explanations; station list sortable.
Frontend-only.

- **Stringline head restructured into three fixed rows**: (1) title + line filter +
  line badges + meta, (2) options — Segment, annotation toggles, CSV, End scrub,
  LIVE, window, (3) VARIANTS row with the direction chips, auto-hidden when the
  selection has fewer than two routes. Close button stays absolutely pinned
  top-right so wrapping can never strand it.
- **Analytics line modal**: line cards are clickable (hover ring + ⤢ hint) and open
  a centred glass modal — 220 px headway chart, then a tile grid where EVERY metric
  carries a plain-language explanation of what it means and where the number comes
  from (on-time tolerance and bunching threshold interpolated from the server
  config; dwell overrun names holds/door obstructions/loads as causes; headway
  reference names the depot frequency sliders vs observed median), a bunching list
  with timestamps, and a how-to-read-the-chart note. Closes via ×, backdrop click
  or Escape (modal takes Escape priority over the overlays).
- **Station table sortable** on every column (th data-k + stationSort state):
  numeric columns sort WORST-FIRST on first click (that is what a dispatcher scans
  for), name ascending; second click flips; active header highlighted.

Verified in ?demo=1: three-row head reads cleanly (screenshot); modal shows the
big chart + explained tiles for Demo Express; sorting by headway irregularity
orders Baker City (0.62) before Harbor North (0.11) then flips; sorted th
highlighted; modal Escape/backdrop close.

## Dispatch web UI round 10 — scheduled-vs-actual hover ghost (2026-08-28)

Thomas: show scheduled vs actual times on the stringlines without adding clutter
to the graph or the options row. Frontend-only.

Every departure already carries its schedule deviation, so scheduled time =
actual − dev. On HOVER (and only on hover), the hovered run's schedule is
re-plotted as a dashed ghost in the run's own colour at 55% alpha — each trace
point shifted left by its stop's deviation (pts now carry [t, dist, dev]) — so
the horizontal gap between dashed and solid IS the lateness, stop by stop.
The tooltip's deviation line became "vs schedule: +Ns · dashed = scheduled".
No new toggle, no standing marks on the chart; live-tip-only entries (no
history) simply show no ghost.

Verified in ?demo=1: hovering a +20s Northbound run draws the dashed schedule
hugging the actual with the offset visible, tooltip labels it; non-hovered
state unchanged.

## Dispatch web UI round 11 — consist cross-rail curve fix (2026-08-28)

Thomas's bug: multi-car trains curve well but SNAP once the first car leaves the
curve. Root cause: the consist was laid out along the HEAD's current rail only —
after the head crossed a boundary, trailing cars were drawn on the NEW rail's
straight tangent extension instead of the curve they were still on.

Fix: each vehicle keeps a `railHistory` (ordered trail of recently traversed
rail ids, capped 8, maintained at sample ingestion). `consistChain()` builds the
head's rail plus up to 4 previous rails, orienting each link geometrically (the
previous rail must share an endpoint within 3 blocks with where the train
ENTERED the current one — that shared point is the previous rail's exit; a
bigger gap = teleport, chain stops). `chainPos()` resolves any track-distance
behind the head by walking the chain across boundaries; only past the chain's
end does it fall back to the old linear extension. Car centres AND the angle
sample points go through chainPos, so cars bend through boundaries too.

Verified by probe in ?demo=1: with history [r1,r2,r3] and the head 5 blocks
into r3, a point 30 back lands at (166,19) ON r2's curve (old code: (180,15),
the r3 tangent — the reported snap), and 70 back resolves across TWO
boundaries to (132,3). Chain orientation r3+/r2+/r1+ all correct.

## Dispatch web UI round 12 — performance headroom pass (2026-08-28)

Thomas: performance seems fine in testing — anything needed? Answer: fine at
current scale by design (delta SSE, zero-clients-zero-work, cached stringline
geometry, demand-driven polling), but three scaling cliffs would surface as the
network grows. All frontend; server side reviewed and left alone (its hot paths
are already O(vehicles)/cached).

1. **Pan/zoom no longer re-renders the network per frame.** The static layer
   (grid, rails, stations, labels) was fully re-stroked on EVERY dragged pixel.
   Now: full renders are capped at ~10/s during interaction; between them the
   cached bitmap is blitted with an offset/scale transform (derivation: dest =
   f·(src−C)+C+Δ·k·dpr), so dragging stays 60 fps regardless of network size.
   The static layer renders a 25% margin so blits don't show blank edges; a
   canvas resize nulls staticRenderedView (the bitmap clears) so a stale blit
   can never paint blank.
2. **Viewport culling.** Rails carry a precomputed world bbox (applyNetwork) and
   the static layer skips rails/station areas/labels/platform labels outside the
   viewport+margin; the dynamic layer skips painting trains >80 px off-canvas
   (their smoothing state still advances so they enter smoothly and stay
   clickable).
3. **Detail panel DOM churn.** updateDetail runs at stream rate (~3 Hz);
   both the vehicle and station panels now skip the innerHTML write when the
   rendered content is unchanged (shared lastDetailHtml signature).

Verified in ?demo=1: 30-step scripted pan at 16 ms intervals — no console
errors, map intact at the new position, staticDirty settles false with the
rendered view matching the live view once interaction stops (the transient
true mid-pan is the fast path working). Note: the demo is too small to SHOW
the wins — they are headroom for Baker City scale, not a change in look.

## Per-route announcement templates (2026-08-28)

Thomas: replace "just the checkbox to disable announcements on the route" with
text & code BLOCKS that change how the train announces the next station, per
route. The checkbox is MTR's own per-route "Disable Next Station Announcements"
(EditRouteScreen); the on-board announcement is composed client-side in
`VehicleExtension.lambda$simulate$3` — the consumer of MTR's
PacketCheckRouteIdHasDisabledAnnouncements reply — and emitted through
`IDrawing.narrateOrAnnounce(tts, chatLines)` (all javap-verified on 4.0.1).

**Template language** (AnnouncementComposer, client): text blocks + code blocks
{next} {station} {dest} {route} {number} {interchanges} and conditional sections
[interchange]…[/interchange] (next stop has connections), [terminus]…[/terminus]
/ [enroute]…[/enroute]. Names are firstLang of MTR's "Eng|Other"; interchanges
join "A, B and C" for natural speech; whitespace collapsed; unclosed tags left
alone. Substitution core (renderWithValues) is pure and desk-tested standalone
(4 cases incl. unclosed-tag safety).

**Wiring** (dwell-override pattern throughout): AddonStore.announcementTemplates
(route id → string, persisted in data.json), S2C `addon_announcement_templates`
full-map sync (join + change) into ClientAnnouncementTemplates (cleared on
disconnect), C2S `addon_update_announcement_template` (op-gated, 500-char cap).
NEW CLIENT MIXIN `VehicleExtensionAnnounceMixin` targets the synthetic lambda BY
NAME with the javap'd descriptor (pinned to MTR 4.0.1 — re-verify on any MTR
bump): template present → render + narrateOrAnnounce + cancel MTR's stock
wording; disabled checkbox still silences everything; no template → untouched.

**GUI**: "Announcement…" button injected into MTR's Edit Route screen (ScreenEvents
AFTER_INIT + reflective read of EditNameColorScreenBase.data, the readPlatform
pattern), opening RouteAnnouncementScreen: multi-line template field, token
reference, Use-MTR-Default / Done / Cancel, and a LIVE PREVIEW rendered with
sample stops as you type.

Announcements only change for riders running this mod (client-side presentation;
templates are synced server-wide). Compile green; engine desk-tested; NOT
in-game tested — needs riding a train past an announcement point: check the
button on Edit Route, save/sync, spoken+chat output, and the terminus case.

## Announcement template editor round 2 — buttons, presets, spoken preview (2026-08-28)

Thomas: blocks should be addable via buttons, as user-friendly as possible; and
add a see-AND-HEAR preview. RouteAnnouncementScreen rebuilt:

- **Every code block is a button** (two rows of six: + Next stop / + This station /
  + Destination / + Line name / + Route number / + Transfers), inserted AT THE
  CARET by feeding the widget's own charTyped (yarn 1.20.4 EditBoxWidget has no
  insert API; charTyped is the only public path that respects cursor+selection).
  The three conditional-section buttons (If transfers… / If last stop… / If en
  route…) insert the open+close pair, then walk the caret back INSIDE with
  synthetic left-arrow keyPressed calls so typing continues between the tags.
- **Three one-click presets** fill a whole template: NYC ("This is a {dest}-bound
  {route} train…" with closing-doors line), Simple ("Next stop: {next}."), UK
  ("…change for {interchanges} / all change, please").
- **▶ Hear it**: renders the live preview against the sample stops and speaks it
  through the mod's own TtsManager (stop() first so replays don't overlap;
  close() stops any preview speech). The visual preview re-renders every frame
  as before.
- init() preserves the in-progress text across window resizes (keep variable).

Compile green. NOT in-game tested — the button-insert caret behaviour and the
spoken preview both need a real GUI session.

## Station accessibility (step-free) — 2026-08-28

Thomas: mark stations "accessible" in station settings (beside zones), with
per-platform granularity; wheelchair icon on the system map; filter for
step-free journeys. (The system map itself gets its own overhaul later — this
lays the data foundation.)

**Data** (dwell-override pattern): AddonStore.accessibility — station id →
step-free platform ids, EMPTY ARRAY = every platform (the editor normalizes
all-ticked to empty). Persisted in data.json; S2C `addon_accessibility` full
map (join + change) into ClientAccessibility; C2S `addon_update_accessibility`
(op-gated, 64-platform cap).

**GUI**: "Accessibility…" button injected into MTR's Edit Station screen (the
zone screen; same reflective EditNameColorScreenBase.data read as the route
button; platforms gathered from Station.savedRails). StationAccessibilityScreen:
master toggle "Station is step-free: YES/NO", per-platform ☑ ♿ rows (scrollable
past 8), hint explaining none-ticked = all platforms, Done/Cancel.

**Dispatch web UI**: network payload stations gain `accessible` (+
`accessiblePlatforms` when a subset), platforms gain `accessible` — read from
AddonStore inside DispatchNetwork.build (rides the 30 s cache; icon appears on
the map within a minute of an edit). Frontend: ♿ prefix on accessible stations'
map labels / station-panel title / search rows / hover tooltip; a green
"Step-free accessible station" line and per-platform ♿ marks in the station
panel; and a "♿ Step-free only" toggle in the Layers panel (persisted pref)
that dims non-accessible stations to 28% and limits station SEARCH results to
step-free stations. MTR's own system-map webapp is untouched — that is the
future overhaul.

Verified in ?demo=1 (st1 accessible via pl1 only): map icon, panel badge +
platform mark, Harbor North dimmed under the filter. NOT in-game tested: the
Edit Station button, the editor screen, save/sync round-trip.

## Accessibility icon — Thomas's artwork (2026-08-28)

Thomas supplied the wheelchair icon SVG (MTA-style, three variants in one file).
Split into three assets in the dispatch frontend (served by DispatchStaticServlet,
svg is in its MIME map): access_badge.svg (white on blue rounded square — the one
in use), access_outline.svg (blue on white, bordered) and access_glyph.svg (bare
blue) kept for the system-map overhaul. The ♿ text glyph is REPLACED everywhere
in the dispatch UI: map station labels draw the badge on the canvas (preloaded
Image, onload → invalidateStatic; label backing sized icon+text, text left-
aligned after the badge), and the DOM spots (station-panel title + step-free
line + per-platform marks, search rows, hover tooltip, the Layers "Step-free
only" label) use an <img class="acc-icon"> tag. The in-game GUI keeps the text
glyph (unifont renders U+267F; a texture would need a rasterized PNG — do it if
Thomas wants the icon in-game too).

Verified in ?demo=1 (screenshots): badge on the map label, panel title, green
step-free line, platform row, search row; Harbor North stays icon-free.

## System Map+ — geographic map + journey planner (2026-08-29)

Mock-first per Thomas's rule: mock v2 approved (tools/nextmap_mock/index.html,
served by tools/nextmap_mock_server.mjs on :8792 — now REPO-served, the v1
scratchpad copy died with its session), with his two fixes applied (per-label
alignment/overlap, live pan/zoom) before any engine work.

SERVER (`/dispatch/api/mapdata`, `/dispatch/api/terrain` — both in
DispatchApiServlet.getContent, mapdata behind a 30 s per-dimension
CachedResponse):
- DispatchMapData: per-route ordered platform ids, raw Route.durations +
  durationsValid (platforms−1 when clean; a depot's later routes can carry an
  extra inbound slot — client normalizes), scheduledHeadwayMillis (now public),
  and per-leg rail hex chains. LEG EXTRACTION IS PAIR-BASED, not lockstep: the
  baked pathMainRoute is a ROTATED cycle (dev-rig observed), so chains are
  keyed by consecutive dwell-pair "<from>><to>" incl. the wrap seam; a missing
  pair degrades ONE leg to empty rails (client straight-lines it) — the dev
  rig's half-built Uptown route exercises exactly that. Station parts:
  union-find, shared-route unions FIRST (absolute), then ≤40 horizontal AND
  ≤12 vertical merges; partWalks = closest platform pair; full
  platformDistances matrix (pruned >40 platforms).
- TerrainScanner: /dispatch terrain scan [margin] (+ status) walks rails+station
  bbox+margin on the SERVER thread, 2 ms/tick budget, 8-block grid,
  MOTION_BLOCKING heightmap + FluidTags.WATER at the surface block
  (sampleHeightmap already returns the top block's y), cell-edge tracing into
  rings (islands = opposite-winding rings, client fills even-odd), DP simplify
  tol 6, persisted <save>/station-announcer-addon/terrain.json. Verified live:
  3111-sample scan ~3 s, 0 water on the (underground-network) dev world,
  1 correct polygon after placing a surface pond. Map works with 0 polygons.

FRONTEND (dispatch/map.html + map.js + map.css, shipped like the other statics):
light-paper System Map+ page reusing app.js's engine patterns (cached static
layer + rAF blit, SSE full/delta merge, interpolation). Ribbons: legs resolved
against network rail polylines (endpoint-matching orientation), same-colour
routes MERGE, distinct colours offset in screen space (constant hairline gap at
every zoom); interlining bullet-cluster chips; straight-line fallback for
empty-rail legs. Split stations: dot per part, dotted connector + walk chip.
Labels: halo + greedy declutter, candidate order from the local track direction
(vertical trunk → beside, horizontal/diagonal → above/below), chips/pins are
obstacles, second further-out candidate ring so the destination station still
names itself beside its pin. Journey selection fades the rest to 0.15 with a
soft under-glow. ?demo=1 = full synthetic acceptance harness (also headless:
scratchpad harness.mjs/planner.mjs, 43+55+32 assertions green).

PLANNER (map.js): one time-dependent multi-criteria label-correcting search,
state (platform, routeAboard) so staying aboard is free, labels
(arrival, boardings, walkMeters) with 3-way pareto pruning (cap 8/state);
fastest / fewest-transfers / least-walking read the same destination frontier;
2–4 alternatives deduped by ride signature, truthful tags. Step-free is a HARD
constraint on board/alight/transfer (riding through inaccessible platforms
stays legal). nextDeparture: LIVE (streamed vehicle progress + remaining legs
+ dwells) → SCHEDULE (headway × stable hash phase, FIFO by construction) →
flat 5 min estimate. Transfers = platformDistances × 1.4 m/s + 30 s. Worst
query 1 ms on the demo graph. NOTE: ride edges are DIRECTED — a return journey
needs the return route to exist (MTR routes are directional).

ENTRY POINTS: dashboard row now splits three ways (Transport System Map /
Dispatch / Map+, falls back to two-way when cramped); dispatch UI topbar "Map+"
link. NOT in-game tested: everything (buttons, real-network mapdata joins,
terrain scan on Baker City, live journeys); browser-verified in ?demo=1 at
1680×1000 incl. planner options, selection states, zoom/pan, label declutter.

## System Map+ round 2 — schematic lines, settings (2026-08-29, Thomas's feedback)

Thomas's decisions (do not re-ask): COLOUR ALONE defines a line; ALWAYS
schematic (no true-track toggle on this page); journey-selected = only journey
stations keep labels; dark mode = MTA-Live style. Root cause of his screenshots:
MTR routes are directional, so every line was drawn once per route per track —
doubling, braided gaps (opposite-direction legs offset to opposite sides),
express weaves, station-throat yarn balls, and "IN"/"OU" bullets (direction
suffixes in route numbers).

map.js rework: state.lines (colour → services); normalizeServiceLabel strips
direction tokens (IN/OU/…BOUND/arrows) with un-stripped fallback; drawn
segments are (colour, station-part pair) — same-colour legs on a pair get
resampled to 32 pts and AVERAGED into a centreline, ends blend-snapped to part
centroids ([1,.55,.2] over 3 samples — this un-knots throats); longer
same-colour segments ≥85% within 14 blocks of the shorter chain are SUPPRESSED
as express duplicates (coveredBy pruned to the real A→B chain, connectivity
BFS guard); cross-colour companions via sample grid (≥60% of the shorter
within 14) give stable side-by-side offsets — same colour NEVER offsets
against itself (the braid fix). Planner + vehicle interpolation still use raw
rails; drawn pucks snap ≤24 blocks onto their line's segment. Settings gear:
Light/Dark (JS palette + CSS vars, pre-paint theme apply), thickness 0.6–1.6×,
per-mode layer toggles; persisted. Demo upgraded to directional pairs at
6-block pitch + a green express pair, proving all of it; harness 102 + planner
55 green. Browser-verified both themes at 1680×1000. 2.4.33. NOT in-game
tested on Baker City.
