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
  back-wall cell layout flipped. Corner posts keep stock positions/rotations
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
