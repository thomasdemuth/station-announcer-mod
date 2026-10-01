# Wayfinding — exits, places, station layouts, MTR Games

Thomas's ask (2026-09-29): finer controls for System Map+ — custom locations / points of
interest, real positions for MTR station exits, station layouts so directions can say *how*
to get there ("maybe the game can do it for us"), and an integration with his separate
**MTR-Games** mod (`~/Documents/Coding Projects/MTR-Games`, mod id `mtrquest`) so quests
can use our locations. His decisions (do not re-ask):

- Places: a **block AND a command**, shown on Map+.
- Exits: the exit block **feeds the exit editor** — create / edit / remove MTR's exits from it,
  on **our FlatUi framework**.
- Both marker blocks are **invisible unless you hold the brush, the marker, or rail tools**
  (like MTR's rail-tool overlay).
- Station layouts: **auto-pathfinding** ("if the lift is level with this platform and there are
  blocks to get there") — "deliberate and thoughtful". Design below; build after his review.
- MTR Games: **write the plan now, build it last.**

---

## 1. BUILT (2026-09-29) — exits + places + Map+   *(javac + harness + rig-verified, not deployed)*

### Data
- `wayfinding/WayfindingStore` — `<save>/station-announcer-addon/wayfinding.json`
  (`places`, `exitPins`, `tombstones`). **The store is the source of truth**; marker blocks carry
  a copy (so structure pastes keep data) and on their FIRST server tick after loading they
  *adopt* the store's record for their position, or register their own if the store has none
  (fresh placement / paste). A pasted copy of a place that still exists elsewhere gets a new id.
  Command removal of an unloaded marker tombstones its position; the block deletes itself on
  load. Immutable snapshots (volatile) for the web + simulator threads; atomic write.
- `wayfinding/Place` (record: id 8-hex, name ≤48, `PlaceCategory`, description ≤240, dimension +
  MTR world id, x/y/z, radius 0..128, hidden, blockKey|null, createdBy, updated) and
  `PlaceCategory` (landmark, attraction, park, shopping, food, culture, sports, civic,
  district, other — stored by NAME).
- `wayfinding/ExitPin` (blockKey, dims, x/y/z, stationId, exitName; empty name = unpinned).
  Exit names follow MTR's sign format `[A-Z]{1,2}[0-9]{0,3}` (`ExitPin.validExitName`).

### Blocks (`mtr/MarkerBlock` base, `mtr/Wayfinding` registration)
- `exit_marker`, `place_marker` — OPERATIONS tab. `BlockRenderType.INVISIBLE`, no collision,
  and an EMPTY outline unless the player holds a revealing item (`MarkerBlock.reveals`: MTR
  brush, either marker item, any MTR `ItemNodeModifierBase`, any `BlockNode` item) — so they
  cannot even be aimed at otherwise (vanilla light-block trick). Brush or the marker's own item
  right-click opens the editor; placing one opens its editor immediately.
- `client/mtr/MarkerRenderer` — only while revealing: post + foot plate + spinning diamond,
  billboarded label (a faint see-through copy so markers behind walls can be found), place
  radius ring on the ground. Exit colours: green pinned / amber unpinned / red orphan (the
  exit was renamed or deleted in MTR's own dashboard).
- Assets: `tools/gen_wayfinding_assets.py` (icons, particle-only block models, blockstates,
  loot, shapeless recipes paper+dye+compass → 4). Never hand-edit.

### Editors (FlatUi, no vanilla widgets)
- `ExitMarkerScreen` — left: the station's exits (MTR data) + "+ Add exit"; right: name,
  destinations (↑↓×, "+ Destination"), "Pin this marker to Exit X" / Unpin, Delete exit;
  "Station…" popup lists nearby stations. Save = (a) if exits changed, edit the client Station
  and send `PacketUpdateData(new UpdateDataRequest(getDashboardInstance()).addStation(station))`
  — exactly what MTR's `EditStationScreen.saveData` sends; (b) our `update_exit_marker` C2S:
  pin + renames + deletions, which move EVERY marker's pin along (one pass, swaps safe).
  No MTR dashboard permission (`MinecraftClientData.hasPermission()`) = exits read-only, pinning
  still allowed.
- `PlaceMarkerScreen` — name, category chips, description, arrival radius slider, Map+
  Shown/Hidden; `update_place_marker` C2S.

### `/place` (`wayfinding/PlaceCommand`)
`add <category> <name…>` (at your feet), `list [category]`, `near`, `info`, `rename`,
`category`, `describe`, `radius`, `hide|show`, `move` (command places only), `tp`, `remove`
(breaks a loaded marker block). Edits level 2; list/near/info anyone. `<place>` = id, full
name or unique prefix; tab completion quotes names with spaces.

### Map+ (`DispatchMapData` + `dispatch/map.js`)
- mapdata: top-level `places` (hidden ones omitted) and `stations[].exits[].pins` ([[x,y,z]…]).
- Map: district labels (spaced capitals over the land), exit badges (green, the exit letter)
  from zoom 0.6, place icons (category disc + white pictogram; landmarks/attractions from 0.12,
  others 0.3), place names placed AFTER station labels so they never cover a station name.
  Click priority: train > glyph > label > **exit > place** > chip > ribbon > pin.
- Place panel (shares `#stationPanel`): category chip, coords, description, nearest stations
  with walk time **and the exit used**, Plan from/to here. Station panel: pinned exits get a
  green dot and centre the map on their marker.
- Search lists places; picking one plans to/from it as a map point that keeps its `placeId`.
- **Planner**: a street walk to/from a station that has pinned exits goes THROUGH the exit
  that makes the whole walk shortest (`streetToPlatform`); the walk leg carries
  `exit {name, short, destinations, xz, role: enter|leave}`; the itinerary says
  "Leave by Exit A · Main St, Transit Museum"; the selected journey's walk bends through the
  exit badge. A station with no pins keeps the straight line.
- Demo (`?demo=1`): pins on Baker City Central A/B, four places; `tools/harness.mjs` section W
  (13 checks) — 542/542 pass, `tools/planner.mjs` all pass.

### Verified / not verified
- javac whole tree clean; harness + planner green; Map+ demo checked in the browser pane
  (exit badges, icons, district label, place panel, "Leave by Exit A", bent walk).
- Rig (world_baker, sky over Albany x 8/13 y 150 z −60): both markers draw with the brush,
  vanish with an empty hand; store file written with MTR world ids.
- NOT verified: the editors' look and clicks, the MTR exit round trip, `/place`, Map+ on the
  real network (the rig's MTR web server is port 0). Thomas took over the rig client mid-test.
- Small follow-up noted: the diamond reads small from ~10 blocks; bump `r`/`h` in
  `MarkerRenderer.diamond` (0.17/0.2 → ~0.24/0.28).

---

## 2. STATION LAYOUTS — BUILT (2026-09-29)   *(javac + unit tests + harness + rig-verified on the whole Baker City network; not deployed)*

Thomas's answers (do not re-ask): **no ladders, jumps or drops** in rider paths; the **manual
step-free flag wins**, computed fills stations without one; scans run **only on user input** —
a per-station Scan button plus **Scan all stations** (for worlds that install the mod late).

### Pieces
- `wayfinding/layout/CellInfo`, `LayoutInput`, `LayoutSolver`, `LayoutResult` — PURE JAVA (only
  Gson), so the solver is unit-tested without a world: `tools/layout_test/LayoutSolverTest.java`
  (synthetic subway: stairs + fare line; + lift; pane wall; 1-block ledge; two side platforms
  across ballast / posts / a wall top; a level-boarding platform). Run:
  `javac -d OUT -cp gson.jar src/.../layout/{CellInfo,LayoutInput,LayoutResult,LayoutSolver}.java`
  then `java -cp OUT:gson.jar tools/layout_test/LayoutSolverTest.java`.
- `wayfinding/layout/LayoutScanner` — the Minecraft side. MTR data read inside `simulator.run`;
  blocks: LOADED chunks copied on the server thread (palette copies), others from saved NBT via
  `threadedAnvilChunkStorage.getNbt` (the basemap recipe; never `getChunk`). One daemon worker,
  one station at a time. Results: `<save>/station-announcer-addon/layouts/<stationId>.json`.
- `/stationlayout scan <station> | scanall | cancel | status | show <station> | hide`
  (scans need permission level 2). C2S `layout_request`, S2C `layout_data` (gzip JSON).
- Client: `ClientLayouts` (mirror + the in-world overlay: every scanned walk as a ribbon —
  green step-free, amber stairs, blue step-free alternative; exits green posts, suggested
  openings amber — only while a revealing tool is held); Exit editor **Layout tab** (status,
  Scan / Scan all / Show paths, step-free per platform next to the manual flag with a ⚠ on
  disagreement, every walk with its steps, openings, warnings). Dev hook `#marker-layout x y z`.

### The model (see LayoutSolver's javadoc for the full rules)
- Half-block grid; a node = a standing place (floor with 1.8 clear above). Each block state →
  `CellInfo` with a FLOOR reading (boxes covering ≥1/8 block each way) and an OBSTRUCTION
  reading (any box touching the quadrant) — panes/railings block without being floors.
- Moves: 8-way, climb/drop ≤ 0.6 (no jumps/drops/ladders). > 0.26 per half block = STAIRS
  (not step-free); escalator steps (`BlockEscalatorStep`, stair-shaped) that really climb =
  ESCALATOR; lifts = virtual nodes per MTR `Lift` floor, joined to landings within
  width/depth/2 + 2.5. Walk-through: turnstile/HEET/MTR ticket-barrier lanes (FARE +4 m),
  emergency exit doors (+300 m, last resort), openable doors, fence gates, MTR lift/PSD/APG
  doors, markers. Track: floors within 1.25 of a rail centreline at rail height are removed.
- Boarding zone per platform: a band 1.25–4 blocks out, ALONGSIDE the rail only; a low
  (track-level) spot counts only when no OTHER track runs on its far side (ballast between
  tracks); patches < 8 nodes and patches that lead nowhere away from the tracks (a wall top
  between two tracks) are dropped. All three rules came from real stations (Cherry Bridge,
  Kalamazoo B).
- Street openings: where a flood from the platforms first reaches FLAT, OPEN-SKY ground within
  2 blocks of the neighbourhood's street level (median surface around the region edge), and
  either outside the MTR area or ≥ 15 m of walking from the platforms (at-grade stations);
  clustered 10 blocks apart, ≤ 8, none within 8 of an Exit Marker. Used as suggested
  entrances AND as exits for stations with no Exit Marker.
- Legs are compacted (landings fold into flights, flat escalator plates become walking,
  crumbs < 0.8 m fold into neighbours).

### Map+
- mapdata `stations[].layout` (anchors, links with legs + paths, platformStepFree) and
  `accessibleSource` (manual | layout). Exit points are tied to layout anchors; stations with
  no marker walk through their openings.
- Planner: street walks = street leg + the SCANNED inside walk (+ lift/gate seconds); a second
  step-free edge when the fastest one takes stairs; transfers between a scanned station's
  platforms use the layout (fastest + step-free alternative). Edges carry `extraSeconds`
  (planner.mjs timing rule updated).
- Itinerary: "Enter by Exit C · Cherry Lane" + "Stairs ↓8 m · Fare control · …"; transfer
  chips carry their steps; the selected journey draws the scanned path inside the station.
  Station panel: "Worked out from the station's layout scan" when computed.
- Demo: Baker City Central layout; harness section X (9 checks). 551/551 + planner green.

### Verified on the rig (world_baker, 2026-09-29)
- Scan all: 118 stations → 115 layouts in 3.4 s total (Albany 0.45 s, 180k standing places,
  226 walks); 3 honest failures (two "stations" without platforms, Idlewild's area too big —
  the message gives its size); remaining warnings are true (Cherry Bridge: nothing crosses the
  tracks to platform 2; Lakeside / Snowy Peaks Interchange: nothing built to stand on).
- In game: Exit editor saved Exit C "Cherry Lane" INTO MTR (read back from MTR's synced data)
  and pinned it; Layout tab and path overlay screenshotted; Map+ on the real network planned
  "Pin → Enter by Exit C → 14 m → Flower Light Rail" and drew the bent walk.
- Gotcha: MTR's edit permission is simply game mode creative/survival
  (`MinecraftClientData.hasPermission`) — the rig player was in spectator, so the editor was
  (correctly) read-only until `/gamemode creative`.
- NOT verified: real mouse clicks on the new buttons, the overlay's look in a busy station,
  in-game nav HUD (it does not use layouts yet — next candidate).

## 3. MTR GAMES INTEGRATION — Station Announcer side BUILT (2026-09-30); MTR-Games side handed off

**Built here:** `com.stationannouncer.api` (PURE JAVA — MTR-Games uses Mojang mappings, so no
Minecraft/Yarn type may appear): `WayfindingApi` (VERSION 1; places / placesAt / place(idOrName) /
exits / exit(station, name) / layout / mapPlusPort / mapPlusPath / listeners), records
`PlaceView` (+ `contains`, arrival radius max(radius,3)), `ExitView`, `StationLayoutView`,
`WayfindingListener` (placeEntered / placeLeft / exitUsed(leaving) / placesChanged, server
thread). Backend + tracker: `wayfinding/WayfindingApiBackend` (MTR facts cached via
`simulator.run` every 30 s; tracker twice a second; dev env logs `[wayfinding api] …`).
Jar: `tools/build_api_jar.sh` (no Gradle) or `./gradlew apiJar`. Map+ deep links
`?from=`/`?to=` = `place:<id|name>`, `station:<id|name>`, `point:x,z[,y]` (`applyDeepLink`,
harness section Y). Verified live: place enter/leave events on the rig (2026-09-30 17:43).
NOT verified live: exitUsed (needs a player walked through a marker).

**Handoff:** `MTR-Games/docs/STATION_ANNOUNCER_INTEGRATION.md` (+ `MTR-Games/libs/station-announcer-api-1.jar`)
— build setup, guarded bridge, four step types (visit_place, visit_places, use_exit,
journey_to_place), hints, web-board Directions links, editor pickers, tests. API requests from
that agent go at the bottom of that file; answer them here as VERSION 2 (additive only).

### Original plan (kept for reference)

MTR-Games today (surveyed 2026-09-29): Fabric 1.20.4, mod id `mtrquest`, hard-depends on MTR
4.0.1; quests are datapack JSON (`data/<ns>/mtrquest/quests/*.json`) or edited in game
(Quest Manager, saved as world overrides); step types `visit_station`, `ride_route`,
`ride_between`, `checkpoint`, …; locations are MTR station ids/names or its own checkpoint
blocks; no public API, no events; read-only web board on :8890.

### A. Station Announcer side — a small public API
- Package `com.stationannouncer.api` (the ONLY package MTR Games may touch), versioned
  (`WayfindingApi.VERSION = 1`), built as a tiny separate `station-announcer-api` jar so
  MTR Games never compiles against our internals.
- Reads: `places(dimension)`, `place(idOrName)`, `placesIn(category)`, `contains(place, pos)`
  (arrival radius), `exits(stationId)` with positions, later `layout(stationId)` summaries.
- Events (server thread, Fabric `Event`s): `PLACE_ENTERED / PLACE_LEFT (player, place)` from a
  cheap 10-tick proximity check; `EXIT_USED (player, station, exit, direction)` when a player
  crosses an exit marker's radius going out of / into the station area; `PLACES_CHANGED`.
- Plain immutable view records — nothing mutable crosses the boundary.

### B. MTR Games side
- Soft dependency (`suggests: station_announcer`); a bridge class loaded only when
  `FabricLoader.isModLoaded("station_announcer")` (the same guard pattern we use for MTR).
- New quest step types (validated in `QuestDefinition`, tracked in `QuestManager`):
  - `visit_place` {place, radius?} — be inside the place's radius.
  - `visit_places` {category | places[], count} — scavenger hunts ("visit 5 landmarks").
  - `use_exit` {station, exit} — leave (or enter) a station by a specific exit.
  - `journey_to_place` {place, ride: true, maxTransfers?} — arrive by transit, then walk in.
- Quest editor: a place picker (names synced from the server) and exit picker per station.
- Auto hints: "Nearest station: Baker City Central — leave by Exit A".
- Web board: a **Directions** link per step → Map+ `map.html?to=place:<id>` (needs a small
  Map+ deep-link parser for `?from=` / `?to=` with `place:<id>` or `station:<id>`).
- Races: checkpoints can be places (race between landmarks).

### C. Map+ side (optional, after B)
- Show a player's tracked quest target (MTR Games → `WayfindingApi.setPlayerTarget(player,
  placeId)`), highlighted when the map is opened with `?player=`.

### D. Order of work
1. API jar + events here (with a Java unit test of the proximity events).
2. MTR Games bridge + `visit_place` + `use_exit` (+ its mineflayer bot test: visit a place).
3. `visit_places`, `journey_to_place`, editor pickers, hints.
4. Map+ deep links + web-board Directions; quest targets on Map+.

### E. Risks
- Two mods on different Loom versions (ours 1.10.5, MTR Games 1.16.3) — the API jar is plain
  Java (no Minecraft types beyond `BlockPos`-like records) to keep that painless.
- Place names are user-editable — quests should store the place **id**; the editor shows names.
- Deleting a place a quest uses → the step shows "place missing" instead of silently passing.
