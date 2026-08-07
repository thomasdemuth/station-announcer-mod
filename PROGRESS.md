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
