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
