# PA: train-timed announcements + accessibility token (2026-10-05)

Thomas asked: an announcement that says where the step-free access is ("The elevator is at the
rear of the platform" / "The ramp is at the center of the platform"), built on the station layout
scan; and per-announcement timing tied to trains (after departure, before arrival, doors open),
with a repeat count.

## Thomas's answers (do not re-ask)

- Position wording: **front / center / rear of the platform only** (no car numbers, no compass).
- Form: **a `{access}` token** usable in ANY PA message (box or announcer) **+ templates** — no
  dedicated block.
- Train triggers: **all four** live in the announcement settings for trains: **before arrival,
  doors open, doors closing, after departure**.
- Unscanned station: **say nothing** (the token fills with "" until the station has a layout scan).

## Design

### Data (`pa/TrainAnnouncements`, pure, NBT `Trains` on every PA source)
- Platforms: AUTO (MTR platforms within 16 blocks of the source or of any linked speaker) or a
  picked list (≤ 32 ids).
- Rows (≤ 8): event (BEFORE_ARRIVAL / DOORS_OPEN / DOORS_CLOSING / AFTER_DEPARTURE), seconds
  (0..300; before/after the event), repeats (1..5), gap between repeats (5..120 s), on/off, text
  (≤ 512, PaText tokens).
- C2S `update_train_pa` (pos + the whole object), validated like the other PA packets.

### Tokens (PaText)
- `{access}` — the step-free access phrase for the platform (train context: the train's platform
  and direction; otherwise the source's nearest auto platform). Built from the layout scan:
  every exit/opening/fare-entrance → platform walk that is step-free (or its step-free
  alternative); its last path point is where it lands on the platform; a `lift` leg = elevator,
  ≥ 1 block of climb without a lift = ramp, else step-free entrance. Landing projected onto the
  platform rail: thirds = front / center / rear, front = the end the train travels toward (from
  MTR's PathData ordered positions). Scanned but nothing step-free → "This platform is not
  accessible." Unscanned → "".
- Train tokens: `{route}` (route number, else name), `{dest}`, `{next}` (next stop, "" at the end),
  `{platform}`. Filled from the train in train announcements; in ordinary messages from the next
  train due at the source's platform when known, else "".

### Engine (`mtr/PaTrains`)
- Simulator thread (VehicleMixin, `simulateStopped` HEAD + `startUp` TAIL): doors-open edge,
  predicted door close (MTR: `max(dwell/2, dwell − 4.2 s)`), departure. Before-arrival from
  `Siding.getArrivals` polled every 0.5 s via `simulator.run` for watched platforms only.
  Every hook bails on one volatile read when nothing watches; ignores clientside vehicles and the
  Instant Deploy replay / catch-up (sim clock ≠ wall clock by > 30 s).
- Server thread: events queue → pending map; each tick fires rows that are due (event time ±
  seconds, + k·gap), skipping ones more than 10 s late (before-type rows may fire late until the
  event itself).
- Platform snapshot (10 s, per dimension): rail ends + travel direction per platform, for AUTO
  resolution and `{access}`.

### Client queue
- Train announcements are QUEUED (announce packet gains a trailing `queued` flag): they wait for
  the estimated end of what is playing instead of cutting it off. Everything else keeps
  latest-wins.

### UI
- PaSourceScreen right pane gains a **Trains** tab (box: Settings | Trains | Network; announcer:
  Settings | Trains): platforms (Auto / picked list), announcement rows with event, timing,
  repeats, on/off, ▶ preview, text with token buttons, preset set.
- Built-in templates using `{access}`.

## ROUND 2 — ON-BOARD, PER ROUTE (2026-10-05, Thomas: "I want these announcements for the ROUTE/TRAIN
not the station. Finish the station work and implement it for the route and train.")

- `mtr/RouteTrainPa`: per MTR route id, the same rows (+ chime switch), spoken at EVERY stop to the
  players RIDING that route's trains. `<save>/station-announcer-addon/route_train_pa.json` (rows as
  SNBT). C2S `route_train_pa_query` → S2C `route_train_pa`; C2S `route_train_pa_update`
  (editPermissionLevel). Editor: **"Train PA…"** button on MTR's Edit Route screen (above Bullet /
  Announcement…) → `client/gui/RouteTrainScreen` (FlatUi). Dev hook `#route-pa <routeId> [row]`.
- Tokens on board: `{station}` = the stop being served (arriving at / standing at / just left),
  `{next}`/`{nextstop}` = the stop after it, `{access}` = that platform in the train's direction.
- Engine (PaTrains): stop/door/departure hooks also fire for vehicles on such routes
  (route = the one that calls here and carries on); NEW `simulateMoving` HEAD hook for the on-board
  "before arrival": ETA = d/v + v/(2·decel) to the next platform segment's end, reposted once a
  second, only with riders aboard (`VehicleExtraData.iterateRidingEntities`; dev runs post anyway).
  Riders refreshed ≤ 2×/s per vehicle; the server sends each rider a queued announcement at 100 %.
- `client/gui/TrainRowsEditor` = the row list/editor shared by the station Trains tab and the route
  screen (ids ≥ 100 forwarded by the host). The existing per-route MTR next-station TEMPLATE
  ("Announcement…", ClientAnnouncementTemplates) is untouched and independent.

## Status (2026-10-05) — DEPLOYED as 3.4.7 to both play profiles for Thomas's ride test
- javac whole tree green; `tools/pa_test/AccessPhraseTest.java` 15/15 (incl. LayoutSolverTest's
  synthetic subway end to end).
- Boot + live verified on a SEPARATE scratch server (scratchpad copy-on-write clone of world_baker,
  ports 25567 / rcon 25577 / MTR web 8899 — never the shared rig): station boxes at 187 St (picked
  platform) and Medford (Nearby) fired before-arrival ×2 15 s apart, doors open ("This is a
  Pinelawn-bound 2 train. The next stop is Kransfield. The elevator is at the center of the
  platform."), doors closing 4 s before MTR's close, after departure +5 s; on-board rows for the
  Elf Line East route fired at every stop ("The next stop is Albany. The elevator is at the rear of
  the platform." 16 s before the doors opened; "This is Albany. The next stop is Oak Grove.").
  Screenshots: station Trains tab and the route editor at 426×266-ish.
- Bug found live and fixed: AUTO platform ids went through a double[] and lost their low digits.
- NOT verified: a real RIDER hearing the on-board rows (a teleported headless player is not picked
  up as riding by MTR — needs someone to walk aboard), the "Train PA…" button's spot on MTR's Edit
  Route screen, every click, the client queue by ear, TTS.
