# ARCHITECTURE.md — MTR Dispatch Addon (inside Station Announcer)

**Read this before writing any feature code.** It is the shared context for all feature agents.
Everything here was verified against the **real MTR `FABRIC-4.0.1+1.20.4` jar** (javap) and the MTR
4.0.6 branch + Transport-Simulation-Core sources cloned at `mtr-src/mtr` and `mtr-src/tsc`.

## 0. Ground rules (from Thomas, non-negotiable)

- **Additive only.** Existing Station Announcer code (`com.stationannouncer.*` as of the
  `Baseline` commit) must not be modified except: registering new init hooks, adding entries to
  `fabric.mod.json` / mixin config, and *reusing* existing helpers (PlatformPicker, IntSlider,
  CanvasPainter, config patterns) without changing them.
- **Nothing touches MTR's own data files or jar.** All addon state lives in OUR storage
  (see §4). Mixins are allowed (that is the addon mechanism) but minimal + well-commented.
- **Fabric only, MC 1.20.4, MTR 4.0.1** (`mtr_version` in gradle.properties is already 4.0.1).
- **Every feature individually toggleable** via config; performance rules in §6 are hard
  requirements (Thomas's server is under load; he A/B tests with spark).
- **Agents must NOT run Gradle** (rig-SIGKILL hazard). Write code; the orchestrator compiles
  between agents and sends errors back to you.
- Do not attempt in-game testing. Compiling + plausible correctness is the bar; document
  limitations in PROGRESS.md.
- **Verify every mixin target with javap against the 4.0.1 jar before writing it** (see §7).
  The TSC source in `mtr-src/tsc` is *master* and has drifted from 4.0.1 (examples: 4.0.1
  `Vehicle.simulate(long, ObjectArrayList<...>, Long2LongAVLTreeMap)` vs master's
  `Long2ObjectOpenHashMap` param; 4.0.1 `Siding` has `ObjectArraySet<Vehicle> vehicles`, master
  has `Long2ObjectOpenHashMap vehicleIdMap`; master-only methods like `Siding.getVehicleById`
  do NOT exist in 4.0.1). Sources are for understanding logic; the jar is the contract.

## 1. How MTR 4 is put together

Three layers, all shaded into the single MTR jar our mod compiles against:

| Layer | Package | What it is |
|---|---|---|
| Transport Simulation Core (TSC) | `org.mtr.core.*` | The entire railway simulation: `Simulator extends Data`, `Depot`, `Siding`, `Vehicle`, `Platform`, `Route`, `Rail`, `Lift`, path finding, timetables, arrivals. Plain Java, no Minecraft types. |
| Minecraft-Mappings | `org.mtr.mapping.*` | MTR's own version-abstraction wrappers (`holder.*` wraps MC classes; `mapper.*` base classes). MTR mod code is written against these, not Yarn. |
| MTR mod | `org.mtr.mod.*` | Blocks, items, screens, renderers, packets, and the glue to TSC. |

Shaded libraries are **relocated**: fastutil is `org.mtr.libraries.it.unimi.dsi.fastutil.*`,
gson is `org.mtr.libraries.com.google.gson.*`. **Any addon code touching TSC APIs must import
the relocated packages** or it will not link. (Station Announcer's existing MTR code already
does this.)

### Threading model — critical

`Init.registerServerStarted` constructs `org.mtr.core.Main` with one `Simulator` per dimension.
Config `useThreadedSimulation` decides whether simulators tick on their **own thread** or on the
server thread (`main.manualTick()` per server tick). Communication is via message queues:

- MC → simulator: `Init.sendMessageC2S(key, server, world, data, responseConsumer, class)` —
  the response consumer is re-dispatched onto the server thread via `minecraftServer.execute`.
- simulator → MC: `main.processMessagesS2C(...)` drained in `registerEndWorldTick`, processed
  by `org.mtr.mod.servlet.MinecraftOperationProcessor`.

**Consequence: mixin code injected into TSC classes (`Vehicle`, `Siding`, `Depot`, …) may run on
a simulator thread.** It must not touch the Minecraft world, send packets directly, or read
mutable non-threadsafe addon state. Pattern: addon settings are published as **volatile
immutable snapshots** (rebuilt on the server thread whenever edited, read-only from simulator
threads); anything the mixin wants to tell Minecraft goes through a thread-safe queue drained
on a server tick.

### Client data

- `MinecraftClientData extends ClientData extends Data` — has `platformIdMap`, `stations`,
  `simplifiedRouteIdMap`, plus `vehicles` (`ObjectArraySet<VehicleExtension>`),
  `liftWrapperList`, `railWrapperList`, signal-state maps
  (`railIdToPreBlockedSignalColors`, `railIdToCurrentlyBlockedSignalColors`, `blockedRailIds`).
  `MinecraftClientData.getInstance()` = live nearby data; `getDashboardInstance()` = full data
  refreshed when dashboard screens open (full `routes` list is available there).
- Vehicles near a client are synced continuously (`Client.update(vehicle, needsUpdate,
  pathUpdateIndex)` from `Vehicle.writeVehiclePositions`), including the **entire
  `VehicleExtraData`**: `immutablePath` (each `PathData` has `getStartDistance/getEndDistance/
  getDwellTime/getSavedRailBaseId/getSpeedLimitKilometersPerHour/getSignalColors`),
  `getStoppingPoint()`, `getSpeedTarget()`, `getPowerLevel()`, `getIsCurrentlyManual()`,
  `getThisRouteId/ThisPlatformId/NextStationName/...`, and the `Vehicle` schema fields
  (`railProgress`, `speed`, `departureIndex`, `elapsedDwellTime`, …). **A driving HUD needs no
  new server data except schedule deviation.**

## 2. Simulation mechanics you must not re-derive

### Paths, dwell, timetable
- A `Depot` owns an ordered list of routes. `Depot.writeRouteCache` flattens them into
  `platformsInRoute` (consecutive duplicate platforms collapsed). `generateMainRoute` chains
  `SidingPathFinder`s between successive platforms; result is **one shared
  `Depot.path` (`getPath()`, public)** used by every siding in the depot.
- **Dwell time is baked into the path**: `SidingPathFinder.tick()` creates the arrival segment
  as `new PathData(rail, platform.getId(), platform.getDwellTime(), stopIndex+1, …)`
  (mtr-src/tsc `SidingPathFinder.java:125`). `PathData.dwellTime` is a protected field of
  the generated `PathDataSchema`; `getDwellTime()` is milliseconds.
- After the depot path completes, each `Siding.generateRoute(firstPlatform, lastPlatform,
  stopIndex, cruisingAltitude)` is called, then per-siding path finders run in `Siding.tick()`;
  when done, `Siding` builds `timeSegments` + `Trip`s + `platformTripStopTimes` in private
  `generatePathDistancesAndTimeSegments()` — the **timetable is precomputed** from path
  geometry, speed limits and each `PathData.getDwellTime()`. Departures are written by
  `Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()`.
- The `PathData` objects in `Siding.pathMainRoute` are the same object references as
  `Depot.path` entries and are also handed to `VehicleExtraData.create(...)` — mutating
  `dwellTime` on them *before* `generatePathDistancesAndTimeSegments()` runs affects both the
  timetable and runtime dwell consistently. They are also serialized with the siding, so a
  mutation survives restarts until the next depot regeneration.

### Vehicle motion, departure, signals (all in `Vehicle`, runs on simulator thread)
- `simulate(...)` dispatches to `simulateInDepot` / `simulateStopped` / `simulateMoving`.
- **Departure from a platform** (automatic trains): in `simulateStopped`, when
  `elapsedDwellTime >= doorCloseTime && railBlockedDistance(...) < 0` it calls
  **`startUp(departureIndex, sidingDepartureTime)`** — public `startUp(long, long)` is the
  single choke point for "train starts moving" (also used for signal stops mid-route and
  manual driving). Doors: `openDoors()` is re-asserted each tick while
  `DOOR_DELAY <= elapsedDwellTime < doorCloseTime`; `startUp` begins with
  `vehicleExtraData.closeDoors()` and refuses to move until `doorCooldown == 0`.
- **Signals / block occupancy**: `writeVehiclePositions` marks occupied rail segments in the
  per-simulator `vehiclePositions` maps and reserves signal blocks
  (`PathData.isSignalBlocked(id, Rail.BlockReservation.*)`). `railBlockedDistance(...)`
  returns the distance to the first obstruction (other vehicle overlap or a signal block held
  by someone else) — this is what makes trains queue outside occupied platforms **already**.
- **Deviation (late/early)**: private `long deviation` updated by `updateDeviation()` =
  `circularDifference(now - sidingDepartureTime, timeAlongRoute(railProgress), repeatInterval)`.
  Positive = late. Server-side only (client `Vehicle` has `siding == null` so it cannot compute
  this). Dwell shortening for late trains / speed-up percentage already exist on `Siding`
  (`delayedVehicleReduceDwellTimePercentage` etc.) — don't duplicate.
- **Manual driving**: driver = riding player holding a valid driver key
  (`VehicleRidingMovement.getValidHoldingKey(depotId)`); inputs travel inside
  `PacketUpdateVehicleRidingEntities` as flags on `VehicleRidingEntity`
  (`manualAccelerate/manualBrake/manualToggleDoors/manualToggleAto`), applied in
  `Vehicle.updateRidingEntities`. `PacketDriveTrain` is an empty TODO stub — ignore it.
  MTR already renders a driving HUD: `org.mtr.mod.render.DrivingGuiRenderer` (speedometer,
  notch, ATO, doors, platform stopping bar), fed by `VehicleExtension.simulate` calling
  `DrivingGuiRenderer.setVehicle(this)` when `VehicleRidingMovement.isRiding(id)`.

### Arrivals (needed by hold rules + HUD)
- Server/simulator side: `Siding.getArrivals(long currentMillis, Platform platform, long count,
  ObjectArrayList<ArrivalResponse> out)` — public in 4.0.1. A siding contributes only if it
  serves that platform (internal `platformTripStopTimes`). To get all arrivals for a platform on
  the simulator thread: iterate `simulator.sidings` (`Data.sidings`, public field) and call this.
  `ArrivalResponse` carries scheduled arrival/departure (already deviation-adjusted), the
  `deviation`, `departureIndex`, and the route. An **already-arrived** train's arrival time is in
  the past — "approaching within N s" = `0 < arrival - now <= N*1000`.
- Client side: `ArrivalsCacheClient` / `PacketFetchArrivals` — Station Announcer already uses
  this (see `RailroadRouteData` v2.3 notes in CLAUDE.md): request arrivals for platform ids,
  identify one specific run by `routeId + departureIndex`, read its `deviation`. Reuse.

### Lifts
- Server (`org.mtr.core.data.Lift`, ticked by the simulator): floors (`LiftFloor`: position +
  number + description), an instruction queue (`pressButton` with clever direction slotting),
  `railProgress` along floor-to-floor track, `stoppingCoolDown` drives a single
  `getDoorValue()` 0..1. **No concept of door sides.** `isDoubleSided` is just a flag.
- Client (`org.mtr.mod.render.RenderLifts.render`, called every frame from MTR's renderer):
  builds the cab with `new ModelLift1(height*2, width, depth, isDoubleSided)`; doorway boxes
  are hardcoded: front `-Z` always, back `+Z` if double-sided. Whether a doorway opens at the
  current floor is decided **client-side** by `RenderVehicleHelper.canOpenDoors(doorway,
  positionAndRotation, doorValue)` (checks the landing in front of the doorway). Boarding =
  `VehicleRidingMovement.startRiding(openDoorways, 0, 0, lift.getId(), 0, x, y, z, yaw)` +
  `movePlayer(...)` with floor/doorway boxes — all public statics. Player-in-lift movement is
  entirely this box system; there is no server-side lift pathfinding for players.
- Lift GUI: `LiftCustomizationScreen` (opened via `PacketOpenLiftCustomizationScreen`), floor
  config on `BlockLiftTrackFloor` via `PacketUpdateLiftTrackFloorConfig`; lift data updates go
  through the generic `PacketUpdateData` (`UpdateDataRequest.addLift`).

### GUI plumbing
- MTR screens are `mapper.ScreenExtension` subclasses wrapped into vanilla screens by the
  mapping layer (runtime pattern: `minecraftClient.getCurrentScreenMapped().data instanceof X`).
  To add a button to an MTR screen **without mixins**, use Fabric `ScreenEvents.AFTER_INIT`:
  detect the wrapper (javap `org.mtr.mapping.mapper.ScreenExtension` and its inner mapping
  class to find how to reach the extension instance from the vanilla `Screen`), check the
  extension's class, and add a vanilla `ButtonWidget` via `Screens.getButtons(screen)`. Read
  protected fields you need (e.g. `PlatformScreen.savedRailBase`) by reflection — cache the
  `Field` object. If the wrapper proves impenetrable, a tiny client-only accessor mixin is
  acceptable — document it.
- The relevant editors: `PlatformScreen` (dwell sliders; saves via
  `PacketUpdateData(new UpdateDataRequest(...).addPlatform(platform))`), `SidingScreen`,
  `EditDepotScreen`, `EditRouteScreen`, `LiftCustomizationScreen`. `DashboardScreen`/
  `WidgetMap` open them.
- Station Announcer already has reusable widgets: `PlatformPicker` (checkbox platform list
  with route chips, `fitTo()`), `IntSlider`, and the whole custom-screen pattern
  (`NycPidsScreen`, `RailroadPidsScreen`). Reuse them; do not modify them.

## 3. Addon skeleton (Feature-1 agent builds this; later agents extend)

Packages (new, alongside existing code):

```
com.stationannouncer.mtraddon            — server/common addon core
├── AddonInit            registered from StationAnnouncer.onInitialize (one added call is OK)
│                        behind FabricLoader.isModLoaded("mtr") like MtrStationDecor
├── AddonServerConfig    gson, config/station-announcer-addon.json — per-feature enable flags
│                        + tunables (see §6); loaded on server start
├── AddonStore           per-world persistent JSON: <save>/station-announcer-addon/data.json
│                        (hold rules, dwell overrides, lift door configs, platform groups);
│                        loaded on SERVER_STARTED, saved on change (debounced) + SERVER_STOPPING
├── AddonSnapshots       volatile immutable snapshots of the store for simulator threads
├── AddonNetworking      our own C2S/S2C channels (same validated pattern as
│                        AnnouncerNetworking: permission check + caps)
com.stationannouncer.client.mtraddon     — client
├── AddonClientInit      screen-event button injection, HUD registration, client config
├── AddonClientConfig    gson, HUD element toggles etc.
com.stationannouncer.mixin               — ALL mixins live here, config file
                                            station_announcer.mixins.json (new), added to
                                            fabric.mod.json "mixins"
```

- Every mixin class: javadoc header saying what it hooks, why API was insufficient, which
  thread it runs on, and the feature toggle that gates it. **Every injected behavior must
  no-op (fast, allocation-free) when its feature is disabled.**
- Mixins into `org.mtr.core.*` / relocated-lib types: use the relocated imports in signatures.
- Loom generates the refmap automatically once the mixin config is referenced in
  fabric.mod.json; `org.mtr.core.*` targets aren't MC classes so they pass through unmapped.
- C2S packets: validate permission with the same approach MTR uses for dashboard edits
  (`MinecraftClientData.hasPermission()` client-side; server-side check op level 2 like
  `ServerConfig.announcePermissionLevel` pattern) and cap all lengths/list sizes.

## 4. Storage model

Addon data is keyed by MTR's own long IDs (platform id, lift id, route id) — random longs,
globally unique in practice; a single global map is fine (document the cross-dimension
assumption). JSON via Gson, human-editable:

```json
{
  "holdRules":      { "<platformId>": { "watched": [ids], "seconds": 30 } },
  "dwellOverrides": { "<platformId>": { "<routeId>": 10000 } },
  "liftDoors":      { "<liftId>": { "front": true, "back": false, "left": true, "right": false } },
  "platformGroups": { "<routeId>:<stopIndex>": [platformIds] }
}
```

## 5. Per-feature plans

### Feature 1 — Platform hold rules
- **Hook**: `@Inject(method = "startUp(JJ)V", at = @At("HEAD"), cancellable = true)` on
  `org.mtr.core.data.Vehicle`. Guard order (cheapest first): feature enabled? → snapshot has
  any rules at all? → is this vehicle stopped at a platform? (index =
  `Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress)`;
  the *previous* `PathData` (`index - 1`) with `getDwellTime() > 0` and
  `getSavedRailBaseId() != 0` is the platform; require `railProgress ==` that segment's
  end/next start so mid-route signal stops don't match) → does that platform have a rule?
  Only then evaluate the condition. Cancelling before `closeDoors()` leaves the doors open
  while held — acceptable, note it.
- **Condition**: for each watched platform id, any arrival with
  `0 < scheduledArrival - now <= N*1000` (arrivals from iterating `simulator.sidings` →
  `getArrivals(now, platform, count, list)`; `data` field of the vehicle is the `Simulator` —
  cast guarded). Cache the per-watched-platform answer for `holdArrivalCacheMillis`
  (default 500 ms) keyed by platform id — the vehicle re-tries `startUp` every tick while
  held, and multiple held vehicles share the cache. Cache lives in a small class with
  `volatile` snapshot semantics; it runs entirely on the simulator thread.
- **Deadlock guard**: cap holds at `maxHoldSeconds` (default 120, config) per stop — track
  first-held timestamp per vehicle id in the simulator-thread cache, clear on release.
- **GUI**: button "Hold rules…" injected into MTR's `PlatformScreen` → our screen: reuse
  `PlatformPicker` for choosing watched platforms (platforms of the same station first),
  `IntSlider` for N (5–120 s), Clear button. Save via our C2S packet → `AddonStore` →
  snapshot republished.
- Chat-free, event-driven; zero work when no rules configured.

### Feature 2 — Per-line dwell at platforms
- **Hook**: rewrite `PathData.dwellTime` on the depot's freshly generated path *before*
  sidings build their timetables. Injection point: `@Inject` at HEAD of
  `Siding.generateRoute(...)` (public; called once per siding immediately after the depot main
  path completes — idempotent rewrites are fine) — from `siding.area` (the `Depot`, may be
  null-check) get `getPath()` and `routes` (public). Walk the routes' `getRoutePlatforms()`
  in depot order, collapsing consecutive duplicate platforms exactly like
  `Depot.writeRouteCache` does, producing an ordered list of (platformId → routeId); walk
  `path` entries with `getSavedRailBaseId() != 0` in order, matching them up, and set
  `dwellTime = override(platformId, routeId)` when configured, else
  `platform.getDwellTime()` (so clearing an override heals on next generation).
  Setting the field needs an accessor mixin on `PathData` (`@Mutable`/`@Accessor` on the
  protected schema field — javap `org.mtr.core.generated.data.PathDataSchema` to confirm the
  field name/type first).
- Runs on the simulator thread; reads only the volatile snapshot.
- **Persistence note**: baked dwell survives in MTR's saved siding paths; changing an override
  takes effect on next depot generation. Add "changes apply after the depot regenerates" to
  the GUI text, and (nice-to-have) trigger regeneration via the same message the dashboard
  uses (`PacketDepotGenerate` exists; from our server code use
  `Init.sendMessageC2S(OperationProcessor.GENERATE_BY_DEPOT_NAME/...)` — check what
  `PacketDepotGenerate` sends and mirror it) — optional, gate behind a GUI button, not automatic.
- **GUI**: "Per-route dwell…" button in `PlatformScreen` → our screen listing the routes that
  call at this platform (`MinecraftClientData.getDashboardInstance().routes` filtered like
  PlatformScreen does) each with an enable-checkbox + min/sec sliders mirroring MTR's dwell
  sliders; unchecked = use platform default.

### Feature 3 — Multi-sided, multi-door elevators
- **Server: no changes.** Lift motion/doors timing stay MTR's. Door sides are presentation +
  boarding, which are 100% client-side.
- **Config**: per lift id, four booleans front/back/left/right (front = MTR's `-Z` in cab
  space). Default (unconfigured lift) = MTR behavior (front + back-if-doubleSided). Which
  sides open *at a given floor* stays automatic via `RenderVehicleHelper.canOpenDoors` per
  doorway — that is exactly "doors open per-floor based on which side the landing is on".
- **Render**: mixin `RenderLifts.render(JLorg/mtr/mapping/holder/Vector3d;)V` HEAD-cancel when
  the feature is enabled, delegating to our `AddonRenderLifts` — a copy of MTR's method (it is
  ~160 lines, source available at `mtr-src/mtr/.../render/RenderLifts.java`) extended to build
  doorway boxes for each enabled side (±X boxes mirror the ±Z ones with width/depth swapped)
  and to render a cab model with door openings per configured side. Model: write
  `AddonModelLift` based on `org.mtr.mod.model.ModelLift1` source (same package structure in
  mtr-src) parameterised by door sides; reuse MTR's lift textures (`RenderLifts.getLiftResource`).
  Unconfigured lifts must render pixel-identically to stock (pass through the same code path).
- **Boarding**: pass all open doorways to `VehicleRidingMovement.startRiding` / `movePlayer`
  exactly as stock does — boarding from every configured side then just works.
- **Sync**: S2C full-map sync of `liftDoors` on join + delta on edit (tiny). Client caches in
  a static map read by the renderer (render thread only — plain HashMap fine, replaced
  wholesale on packet).
- **GUI**: "Door sides…" button injected into `LiftCustomizationScreen` → our screen with four
  toggles + a lift picker mirroring how that screen tracks the selected lift (reflect its
  fields; javap first). C2S save packet updates store + rebroadcasts.
- Known gap to document: lift *panels/buttons* remain MTR blocks placed in the world; we do
  not add per-side call buttons — landings call the cab exactly as before.

### Feature 4 — Advanced manual driving HUD (client-only)
- **No mixins.** Fabric `HudRenderCallback` (or the repo's existing render-hook style) +
  `ClientTickEvents`. Find the driven vehicle: iterate
  `MinecraftClientData.getInstance().vehicles`, `VehicleRidingMovement.isRiding(v.getId())`,
  and require `VehicleRidingMovement.getValidHoldingKey(v.vehicleExtraData.getDepotId()) != null`
  (same visibility condition as `DrivingGuiRenderer`), else render nothing.
- **Lookahead cache** recomputed on a timer (default 4 Hz, configurable; never per frame):
  walk `immutablePath` from `railProgress` forward up to `lookaheadMeters` (default 2000):
  - next speed-limit changes: consecutive `getSpeedLimitKilometersPerHour()` deltas → (distance, kph);
  - next signals: segments with non-empty `getSignalColors()`; aspect from
    `MinecraftClientData` signal maps (`railIdToCurrentlyBlockedSignalColors` /
    `railIdToPreBlockedSignalColors` via `PathData.getHexId`) + "obstruction" if
    `vehicleExtraData.getStoppingPoint()` lands before the next platform end;
  - next stop: next `PathData` with `getDwellTime() > 0 && getSavedRailBaseId() != 0` →
    name via `vehicleExtraData.getNextStationName()` (fallback: platformIdMap lookup),
    distance, naive ETA from current speed and the vehicle's deceleration.
- **On-time indicator**: reuse the arrivals trick proven in Station Announcer v2.3
  (`RailroadRouteData`): request arrivals for the next platform id through the existing
  `ArrivalsCacheClient`/`MtrDataCache`-style cached fetch (≥500 ms cache), find the entry
  matching `vehicleExtraData.getThisRouteId()` + `vehicle.getDepartureIndex()`, read
  `getDeviation()` → early / on time (|dev| ≤ threshold, default 15 s) / late, shown with
  signed seconds. If no match (manual sidings have `departureIndex == -1`), show "—" and
  document that manual-mode deviation is only available when MTR itself predicts the run.
- **Layout**: compact panel top-left (MTR's speedometer owns bottom-right); every element
  individually toggleable in a settings screen (our own, opened via a keybinding registered
  like Station Announcer does for its clients + accessible from a small gear on the panel).
  Respect `enableDrivingHud` master toggle.

### Feature 5 — Dynamic platform selection ⚠ scope honestly
TSC generates **one shared path per depot** and precomputed timetables; a truly per-arrival
platform choice invalidates path distances and every `TimeSegment` after the switch. Full
runtime re-pathing is out of reach for this pass. Build the feasible core:

1. **Platform groups + UI** (solid): store `route:stopIndex → [platformIds]`; GUI in our
   button on `EditRouteScreen` (or the platform-selection flow) to pick group members at the
   same station. Validate same station + same transport mode.
2. **Occupancy-aware queueing (already exists)**: trains targeting an occupied platform queue
   via signal blocks/`railBlockedDistance` — document that MTR provides this.
3. **Generation-time selection** (implement): when the depot path is (re)generated, our hook
   (same `Depot`/path-generation area as Feature 2 — e.g. wrap the creation of
   `SidingPathFinder`s in `Depot.generateMainRoute` via a mixin) substitutes, for a stop with
   a configured group, the group member chosen by a deterministic strategy (e.g. rotate per
   generation, or pick the platform whose approach is shortest). This distributes traffic but
   is static between generations. Verify the private method/lambda target with javap first.
4. **Runtime splice (stretch, default OFF, config `dynamicPlatforms.experimentalRuntimeSwitch`)**:
   pre-generate alternate sub-paths (prev stop → each group member → next stop) at generation
   time; at the moment a vehicle departs the *previous* stop, if its scheduled platform is
   occupied (`Rail.isBlocked` on its platform segment / vehiclePositions overlap) and an
   alternate is free, splice the pre-generated segment into a rebuilt path
   (`VehicleExtraData` has `copy(pathUpdateIndex)` and the client sync supports partial path
   updates), regenerating distances with `SidingPathFinder.generatePathDataDistances`.
   Schedule/deviation display degrades after a switch — cap the blast radius: only attempt
   when the vehicle is stopped, never for manual vehicles, wrap in try/catch that falls back
   to normal behavior. If this proves unworkable while implementing, **ship 1–3 and document
   the gap in PROGRESS.md** — that is an acceptable outcome.

## 6. Performance rules (hard requirements)

- No new per-tick scans. Hook events (`startUp`, path generation, screen open, packet).
  The only per-tick code allowed is inside existing MTR call paths we intercept, and it must
  bail out in O(1) when the feature is off or no config exists.
- Anything periodic is throttled + configurable: `holdArrivalCacheMillis` (500),
  `hudUpdateHz` (4), `hudArrivalsCacheMillis` (1000). Idle = zero work (no rules → no arrival
  queries; no HUD shown → no lookahead computed; no configured lifts → stock render path).
- Heavy work off the main threads: JSON persistence on a small executor (debounced writes);
  never file I/O on server tick or simulator tick.
- Client lookahead cached on a timer, never per frame (CanvasPainter marquee lesson).
- Every feature has an `enabled` flag in `station-announcer-addon.json` (+ client toggles in
  the client config). Defaults: features 1–4 enabled, feature 5 runtime-switch disabled.

## 7. Workflow for every agent

1. Read this file + PROGRESS.md. Read the relevant sources under `mtr-src/` (they are
   gitignored reference material — never edit or ship them).
2. **javap every MTR/TSC member you touch** against
   `.gradle/loom-cache/remapped_mods/net_fabricmc_yarn_1_20_4_1_20_4_build_3_v2/maven/modrinth/minecraft-transit-railway/FABRIC-4.0.1+1.20.4/minecraft-transit-railway-FABRIC-4.0.1+1.20.4.jar`
   (`export JAVA_HOME="$HOME/Library/Application Support/minecraft/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"; "$JAVA_HOME/bin/javap" -p -cp <jar> <class>`).
3. Implement completely. Do NOT run Gradle — the orchestrator compiles and reports back.
4. Do not refactor earlier agents' work or pre-existing Station Announcer code.
5. Append to PROGRESS.md: what you built, files touched, config keys, GUI entry points,
   thread-safety notes, known limitations / gaps.
6. The orchestrator commits after a green compile.
