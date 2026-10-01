# INTERLINE PLAN — depot delays, interline sections, headway targets (2026-09-29)

Status: BUILT 2026-09-29 (all five phases), javac-green + solver harness + live web API on the
rig's real Baker City network; in-game screen NOT yet seen (rig was in use by another session).
Replaces the automatic depot-group stagger. See "BUILT" at the end.

## Why the old depot groups are not enough

`DepotGroupEngine` shifts member i of N by `i/N × interval` at DEPOT DEPARTURE. Spacing that
matters is at the SHARED SECTION, and each depot's travel time to it differs (depot A 4 min
away, depot B 11 min away → evenly spaced at the depots, bunched on the trunk). Offsets must
be computed from arrival times at the section entry platform.

Data source: `Siding.platformTripStopTimes` (platform id → `Trip.StopTime`, times relative to
the departure) — what PIDS arrivals come from. Seen in TSC master; **javap-verify against
4.0.1 before use** (TSC has drifted).

## Thomas's decisions (do not re-ask)

- **Directions:** all three — default "balance both" (minimise the worst spacing across both
  directions), a direction picker ("make this one perfect"), and a candidate list (several
  delays with the spacing each gives per direction).
- **Plus dwell suggestions:** suggest extra dwell at the station BEFORE the section, per route.
  A depot delay moves both directions together; a dwell pad on one route moves only that
  route in that direction — this is how the second direction gets fixed. Applied through the
  existing per-route dwell overrides (Feature 2, `DwellOverrideEngine`).
- **UI:** both — in-game FlatUi screen (Tools… hub) AND a dispatch web view.
- **Apply:** writes frequencies for ALL 24 hours + delays (+ accepted dwell pads).
- **Groups:** suggest-only. No automatic stagger any more; existing groups migrate their
  current computed shift into a manual delay so nothing moves on upgrade.

## Phases

1. **Manual per-depot delay.** New store section `depotDelays: {depotId: millis}`; the existing
   `DepotMixin` HEAD/@Redirect hook reads it instead of the i/N slot. Delays ≥ one headway
   wrap (shown as the effective value). Migration: each grouped depot's `LAST_OFFSET` becomes
   its delay. Groups keep membership only.
2. **Section detection** (simulator thread, cold path). Maximal runs of consecutive shared
   platform ids between routes, same direction (each direction = its own section). Per
   section: feeding depots, route(s), time from depot departure to entry platform (from
   stop times), interval at entry. Measured headway at the entry platform from analytics.
   Assumption: platform-shared only; express-vs-local on shared TRACK is not a section.
3. **Solver** (pure class, no MTR types, harness-testable).
   - Group mode: delays so arrival phases at the entry are k·H/N; one depot pinned at +0,
     minimum total delay.
   - Target mode: target trunk headway X + optional weights (default equal) → per-depot
     interval → snap to MTR-representable frequencies (interval = gameMillisPerDay /
     (24 × f/4)) → real resulting headway shown → delays → per-direction residuals → dwell
     pads at the stop before the section to fix the other direction.
   - Warns when even spacing is impossible (non-integer frequency ratios, e.g. 4 + 6 min).
4. **In-game screen** (FlatUi, replaces DepotGroupsScreen/EditScreen): sections list, depot
   delays, target form, suggestion view with per-direction spacing, before/after frequency
   table, Apply. Frequencies go through MTR's own client depot update packet
   (`UpdateDataRequest` — the Duplicate Line pattern; verify `addDepot` in 4.0.1). Then the
   Refresh-Offsets rewrite so departures change immediately.
5. **Dispatch web view:** sections highlighted on Map+, per-section measured vs scheduled
   headway, suggestions. Apply from the web only if the dispatch UI already has an
   authenticated edit path (check first).

## Known limits to state in the UI

- A depot delay is whole-day; per-hour frequency patterns get overwritten by Apply (show
  before/after). Per-hour delays possible later (the redirect sees each departure's time).
- Scheduled times only; holds, door obstructions and gap-filler minimum dwell add runtime
  delay — measured headway shows it.
- Real-time-timetable and continuous-movement depots are not tunable.
- Dwell pads lengthen that route's cycle.
- Platform groups + dwell overrides on multi-depot stops have the known
  `effectiveStopPlatformId` gap (PROGRESS.md, depot rotation §H) — check it before relying on
  dwell pads at a grouped stop.

## BUILT (2026-09-29)

**Server** (`mtraddon/interline/`):
- `InterlineModel` (plain records), `Timetable` (exact replica of MTR 4.0.1's departure writer:
  per nominal hour f>0, interval 14 400 000/f, `max(hourStart, last+interval)`, mapped
  `n·day/86 400 000` → real headway `day/(6f)`, 200 s/f on the 20-min day; `arrivals()` with
  delays + pads; `stats()` = RMS gap spread/mean, gaps > 4× median are service breaks).
- `InterlineAnalyzer` (simulator thread): pairwise maximal shared platform runs → sections
  (merged by platform sequence; reverse paired by reversed station sequence); feeds' travel time
  = median `Trip.StopTime.startTime` across sidings (`SidingStopTimesAccessor`), falling back to
  a platform group's member platforms (terminal alternation files stop times under the member);
  prev stop + its current dwell; scheduled stats; measured headway from analytics departures.
- `InterlineSolver` (pure): coordinate descent over each adjustable depot's delay within one
  headway, seeded from the current delays + ideal-slot orderings AND from the optima of all three
  objectives (this way / other way / balance — one objective alone stalls on plateaus); rebase to
  the smallest total delay; dwell pads (1 s steps ≤ `maxDwellPadSeconds`, best single pad first,
  then descent; small per-second cost so the shortest pad wins; in balance mode the one-way-perfect
  delay sets + pads are tried too). Target mode enumerates uniform slider values (≤3 depots: all
  combos) with penalties for unequal frequencies (0.15) and intervals that do not divide the day
  (0.04 — f=7 gives two trains 1 ms apart at midnight).
- `InterlineService`: analysis cache, single daemon solver thread, C2S `interline_request` /
  `interline_apply` / `interline_group`, S2C `interline_reply` (JSON), Apply = delays (store) +
  dwell (Feature 2 overrides, then `Depot.generateDepots` because dwell is baked) + frequencies via
  MTR's own `PacketUpdateData` path (`PacketRequestResponseInvoker` → `runServerOutbound(world,
  null)`, exactly what `sendDirectlyToServerRail` does), then a departure rewrite 40 ticks later.
- `DepotGroupEngine` = per-depot delays (`AddonStore.depotDelays`, snapshot `Long2LongOpenHashMap`
  from MC's fastutil — MTR's shaded one lacks it). One-time migration converts the old i/N stagger
  into delays (`depotDelaysMigrated`). **Found live: MtrSimulators is still null at our
  SERVER_STARTED** — the old boot-time stagger rewrite was silently a no-op; startup now retries
  every second for 2 min. (The rig world's migration ran before this fix and dropped "Line 1"'s
  stagger — the live analysis shows it bunched ±100%; the solver fixes it with +100 s.)
- Web: `api/interline` (simulator thread, caches) + `api/interlinesuggest` (Jetty worker, cached
  analysis). `dispatch/interline.html|js|css`, linked from the dispatch top bar; `?demo=1` uses
  real solver output dumped by the scratchpad `DemoDump.java`.

**Client**: `InterlineScreen` (FlatUi; Sections / Depots / Groups; suggest form, options, Apply;
manual delay box; group editor) replaces DepotGroupsScreen/EditScreen/ClientDepotGroups and
DepotGroupNetworking. Dev hook `#interline [sections N | depots N | groups | suggest DIR [target] |
apply | delay N DURATION]`.

**Verified**: javac whole tree; solver harness (2 depots: every mode reaches ±0% both ways with
15 s delay + 10 s pad; 4 mixed-frequency depots: 0.74 → 0.05 in ~0.3 s); web page in demo mode
(browser pane) and against the rig server's real network (25 sections, live suggestions).
**Not verified**: the in-game screen (rig busy), Apply end to end (frequency write through MTR,
dwell regeneration, delayed rewrite), the platform-group fallback and startup retry (written after
the rig server was built), mixins beyond "the server booted with them".

## ROUND 2 (2026-09-30) — in-game test, visuals, platform holds, mixed frequencies, web in the dispatch screen

Thomas's asks (do not re-ask): pictures over text; the web view inside the DISPATCH screen and
able to apply; "platform holds" = longer dwell at a platform (NOT hold rules) so a line that
loops back from the same depot can be moved in one direction; lines with different frequencies
must still be manageable. Web apply = /navpair pairing (answered).

- **In-game visuals** (`InterlineScreen`): section map (lines from their previous stop merging
  into the shared stretch, station capsules / ticks when crowded, hover names, amber hold badges)
  and arrival strips (one tick per train over a daytime window, now vs → new, ±% badge from
  `Timetable.stats`). Text comparison lines removed.
- **Platform holds**: `InterlineModel.HoldSite` (before / turnaround / origin); `levers` =
  delays | holds | both (in game: "Adjust" segmented control; web: same). **Merged route
  boundaries**: when a depot's route starts at the platform its previous route ends at, MTR runs
  ONE stop whose dwell belongs to the earlier route (DwellOverrideEngine primary/secondary) — the
  analyzer offers it once (as the earlier route's hold, dwell = its override, else the later
  route's, else the platform's) and `Depot.mergedStartRoutes` makes padShift ignore a hold at the
  later route's first stop for that depot (another depot running the route alone still gets it).
  Found live: two "separate" holds at Sahara fought and the later one never took effect.
  "Both" also tries "today's delays + holds" and keeps the cheaper answer.
- **Scoring bug fixed**: service breaks were gaps > 4× the MEDIAN gap, so trains packed in tight
  groups hid the long gaps between groups and scored as perfect (the solver exploited it on the
  real network). Now > 4× the day's AVERAGE gap (Java + JS).
- **Mixed frequencies**: `badness = evenness + 0.1·(max/mean − 1)` (prefers the shortest longest
  wait when perfect is impossible), an explanatory warning, and `matchOptions` (every depot on one
  slider value: each value in use + the one between) → one-click target suggestions in both UIs.
- **Dispatch screen** (`index.html` + `interline.js` IIFE + `interline.css`, `interline.html` now
  redirects): right-hand panel; map overlay via `window.ilDrawOverlay` (dims the network, draws
  the section's real rails from Map+ `mapdata` legs in concentric line colours, dashed approaches,
  station rings, pulsing hold markers) and `window.ilFocusRoutes` (app.js dims other trains);
  bottom sheet = timetable stringline through the section (approach + stations, now dashed /
  suggested solid, gap labels red when bunched, zoom, hover). Depots tab can set a delay. Apply:
  `POST api/interlineapply` + `GET api/interlineauth`, token from /navpair (shared with Map+ via
  `sa_mapplus_prefs.navToken`), player must have `editPermissionLevel` (op list checked offline
  too); config `depotGroups.webApply` (default true). `maxDwellPadSeconds` default 200.
- **Rig-verified 2026-09-30**: migration retry (Line 1 → +100 s delay); in-game screen, map,
  strips, suggest, Apply with dwell regeneration (Markoe Desert ±64% → ±0% with +32 s at Sailey);
  target Apply incl. MTR frequency write (Line 2 → slider 2 all day, ±0%); web pairing + Apply of
  a platform hold on the loop section Alabama → Beverly (±49% → ±0%, every 66.5–66.9 s).
- **Rig world state after the tests** (a copy, not Thomas's world): Line 1 delay +100 s, Line 2
  depots at slider 2 + 187 delayed 2.1 s, Liz Line dwell 67 s at Sailey, Line 4 Local & Limited
  delay +42.2 s + dwell 211 s at the merged Sahara stop, and a now-dead 200 s override for the
  later route at that Sahara platform (only "Line 4 Drivable" trains see it).
- Not verified: clicking the in-game match buttons (the hook cannot click; the same request path
  was verified from the web), the web page on a phone-width screen.
