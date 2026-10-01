# Station page + line profile (web dispatch) — plan

Thomas, 2026-10-01: "In the dispatch screen, allow me to click on a station and view the station
information in depth: a 3D model of the station with exits highlighted, platforms highlighted,
stairs, elevators, etc. and the routes, frequency (expected) vs (actual) … a depth profile for the
whole line if I want as well."

## His decisions (do not re-ask)

- **Where:** BOTH — a full-screen station page in the web dispatch app, AND a richer right-hand
  side panel (the quick look, with an "Open station" button into the page). Web only; no in-game
  screen.
- **3D model:** a **clean schematic** (no real blocks): platforms, concourses/floors, stairs,
  escalators, lifts, fare lines and exits as simple coloured shapes plus the walk paths, street
  level as a ground plane.
- **Schematic source:** the **layout scan**. An unscanned station shows a "Scan this station"
  button (operators only). Opening the page never starts a scan (his rule: scans only on a click).
- **Frequency, expected vs actual:**
  1. *Headway now* — per route and direction, scheduled headway next to the real gaps over the
     last hour, with bunching flagged.
  2. *Per-train delays* — each recent departure per platform: scheduled, actual, minutes late,
     dwell overrun.
  3. *Timetable-vs-reality strip* — stringline-style for this station only: planned departures as
     ticks, real ones as dots.
- **Extras (all four):** live boards per platform + a transfer-time matrix (fastest and
  step-free, from the scan); exits with street names, step-free / lift verdict and the scan's
  warnings, clickable to highlight in 3D; ops controls; station equipment (PA announcers + their
  messages, PIDS, holding lights, turnstiles — shown in 3D).
- **Ops editing from the web (with pairing, like interline Apply):** hold rules, dwell overrides,
  and **create/edit service posters online**. Everything else (disruptions, gap fillers,
  interline holds) read-only on the page.
- **Line depth profile:** all of — track vs ground (tunnel / at-grade / el shading), stations +
  platform depth + street level (clickable), grades and curves (+ speed limits), live trains —
  each behind a **toggle** so the user tunes what is shown.
- **Order:** 1) page shell + side panel, live boards, exits/accessibility, headways;
  2) 3D schematic; 3) line profile; 4) delays strip, equipment, ops editing.

## Phases

### Phase 1 + 2 — BUILT 2026-10-01 (page, side panel, 3D schematic) — shipped in 3.4.3, not deployed

- **Server** `mtraddon/dispatch/DispatchStation` → `GET /dispatch/api/station?dimension=N&id=…`
  (simulator thread, fresh per request): station (manual + scanned step-free), platforms (dwell,
  routes, live HELD, hold rule, dwell overrides), routes (prev/next stop, terminus, scheduled
  headway), **upcoming** = MTR's own timetable (`Siding.getArrivals`, 8 per platform, 1 h),
  **departures** = this station's analytics-window events `[t, plat, route, devMs, dwellMs|-1,
  schedDwellMs, vehicle, stopIndex]` (scheduled time = t − dev), **headways** per route+platform
  (scheduled vs real gaps, bunched < bunchingFraction × scheduled), exits (+ pins), layout (with
  paths), **geometry**, `scanState`. `POST /dispatch/api/stationscan {token, station}` — paired
  browser, operator only (LayoutScanner.SCAN_PERMISSION), queues a scan.
- **Geometry** (`LayoutSolver.geometry`, LayoutResult.geometry): standing places that belong to the
  station (reachable from a platform, not street-level open sky, inside the MTR area or ≤ 6 blocks
  from a scanned walk) merged into rectangles `[kind, x0, z0, x1, z1, y]` — kinds `platform:<id>`,
  `paid`, `free`, `stairs`, `escalator`, `fare:<n>` / `gate` — plus lifts (id, x, z, landing ys),
  track polylines, street level. Stored SEPARATELY in `layouts/geometry/<id>.json` (Map+ embeds every
  station's layout JSON, so it must stay small); stations scanned before need a rescan. Albany =
  1409 rects, biggest file 116 KB, all 115 = 2.1 MB. Unit test section 14.
- **Web** `station.js` (classic script; page + tabs Departures / Service / Exits & access /
  Operations (read-only), side-panel quick look via `StationPage.panelHtml`, double-click a
  station or `#station=<id>` opens it, Esc closes, polls 10 s, demo payload for `?demo=1`),
  `station3d.js` (ES module on **vendored three.js r160** `vendor/three/` — MIT, Thomas approved
  the download: instanced floors per kind, platforms in their line colour, lift shafts, track,
  street plane, exit/entrance posts with access caps + lift band, walks, labels with declutter,
  layer toggles, cut-away slider, hover tooltips, click an exit → its walks light up),
  `station.css`. Bullet labels: route number unless it is a direction word (IN/OUT…), then the
  shortest unique prefix of the line name (Br / F / V), like Map+.
- **Verified**: demo in the browser pane (all tabs, 3D); real Baker City on the rig — Cherry
  Bridge and Albany 3D, live boards with real-time departures, headways, exits/access (Albany's
  manual flag vs scan disagreement shows), side panel. **Not verified**: the scan button end to end
  (needs a paired operator browser), mouse orbit feel, big-screen layouts other than 1500×900.

### Phase 3 — line profile
### Phase 4 — equipment, ops editing (holds, dwell, posters)
