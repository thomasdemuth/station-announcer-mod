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
