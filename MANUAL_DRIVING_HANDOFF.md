# Manual driving overhaul — handoff (2026-10-05)

Read this first, then `MANUAL_DRIVING_PLAN.md` (§6 Thomas's answers = do not re-ask, §8–§10 build
logs, §9 the approved HUD) and `SLOT_TAKEOVER_RESEARCH.md` (MTR 4.0.1 departure/arrival mechanics).
Nothing of this feature is committed yet.

## 1. State at handoff

| Phase | What | State |
|---|---|---|
| 0 | Door obstructions only on crewed trains | built, booted |
| 1 | Crew roles (driver key / guard key), OPTO, buzzer B, horn G, vigilance V, buzz interlocks, `/crew status\|horn` | booted; driver role + horn **verified by Thomas** (2026-10-05 afternoon); NO two-player test (guard role, buzz codes, interlocks), vigilance trip not seen |
| 2 | Cab HUD (`client/mtraddon/cab/`), server cab feed, MTR overlay/action bar/next-station chat hidden in cab mode, per-frame speed to 0.1 | **rig-verified**, two full runs driven by Thomas (§5) |
| 3 | Timetable slots (`crew/SlotEngine`, `SidingSlotMixin`, H4 in `VehicleCrewMixin`), cab menu K → Service tab | **rig-verified** end to end (claim → leave → stops tracked → back in the siding → ended); fixes in §5 (picker, RUNNING promotion, no-path guard). H1 hold-back + no-show reinstatement **verified** (13:48–13:50, §5). NOT verified: boards (PIDS) showing a late manual / reinstated run |
| 4 | Startup ritual, cab seat (N), lean out (Left Alt), horn tab | startup works but Thomas: "isn't very fun" → being rebuilt as a cinematic by another agent; cab seat + lean out + horn tab **verified by Thomas** |
| 5 | Incidents (`crew/IncidentEngine`) | **verified by Thomas** ("they are good"); held ×2, door fault, sick passenger seen in log + HUD (§5) |
| 6 | Score (`CabScore` live + `CabReportScreen` + history), passenger crowd (`cab/CabPassengers*`) | live score + report card **rig-verified** (lines sum to the total, −395 run); crowd integrated + seen on platforms; left-behind counted ("7 left on the platform −7"); EB count + MTR-forced/ATO stop rules fixed (§5) — the last CabScore change (forced stops) is javac-only, needs a rig restart after Thomas's current run |
| — | HUD style setting, cab settings on FlatUi (`CabSettingsScreen`, H) | screen opened by Thomas on the rig (§5); Save/Cancel/Esc and "Classic options…" not exercised |

## 2. Rig recipe (this session's)

- Scripts in the session scratchpad (`/private/tmp/claude-501/-Users-thomasdemuth-Documents-Coding-Projects-Station-Announcer-Mod/8d40244b-f7f4-4d23-92db-ee562cd8fc86/scratchpad/`):
  `crewjc.sh <name> -sourcepath src/main/java <files…>` (javac over runClient's argfile classpath +
  jetbrains annotations — compile check without Gradle; QUOTE paths, they contain spaces — use
  `find … -print0 | xargs -0`), `rig_restart.sh` (kill, runServer → wait "Done", then client),
  `client_restart.sh` (client only, NEVER compiles: `-x compileJava …`), `server_restart.sh` (server
  only), `stop_server_clean.sh` (RCON `save-all flush` + `stop`, wait ≤60 s, then kill — every
  restart goes through it; a `kill -9` lost MTR siding paths), `rcon.py <command>` (RCON 25575 /
  `rigpass`, e.g. `rcon.py crew devdepots driveable`, `rcon.py list`). If the scratchpad is gone,
  recreate from these descriptions.
- World: `run/server.properties` level-name=`world_baker2` = fresh copy of Thomas's local save
  "Baker City - MTR July 2026" (2026-10-04); old setting in `server.properties.before_baker2`.
  Manual sidings in it: **Line 1 - Driveable** (siding D, ~2140 75 916; identical routes to
  **Line 1 - Armory B**), **Canarsie Tuffs** (↔ Canarsie Scotia), Liz Drive, Line 4 Drivable, Stanley Depot.
- Rig client has Thomas's 18 MTR resource packs (`run/resourcepacks`, enabled in `run/options.txt`).
  Without them custom trains have no floor boxes and cannot be boarded.
- Commands through `run/commands.txt` (one line per ~25 ticks; `/` = chat command, `#` = dev hook);
  screenshots via `touch run/screenshot.flag` → `run/screenshots/`. Logs: `run/server.log.crew`,
  `run/client.log.crew`. The rig client window is on Thomas's screen; he sometimes drives it himself
  (boarding custom trains headlessly failed — ask him to board when needed).
- Dev hooks (client): `#cab-demo run|appr|plat|held|guard|off`, `#cab-detail`, `#cab-key <binding> <ticks>`
  (holds any key binding: `train_accelerate`, `train_brake`, `train_toggle_doors`, `crew_buzzer`,
  `crew_horn`, `crew_vigilance`, `cab_lean`…), `#cab-dump` (driven vehicle raw state), `#cab-near`
  (nearby vehicles + car 0 position), `#cab-menu`, `#cab-claim` (claims the first offered slot),
  `#cab-close`. Server (dev env only): `/crew devdepots <name|manual>`, `/crew devmanual <depot>`.
- Log tags: `[slot]`, `[incident]`, `[cab-key]`, `[cab-dump]`, `[cab-near]`.
- Give the rig player a key: `/item replace entity Rig weapon.mainhand with mtr:creative_driver_key`.

## 3. Known issues / open questions

1. Arrival detection fix (platformAt, ±3 m past the end) and skipped-stop handling are untested.
2. Before the first stop the train is "Not in service" to MTR; the HUD now shows the slot's
   destination, but the bullet label is parsed from the slot label (hack) and coloured green — send
   the route number/colour in `CabFeed.Slot` instead.
3. Slot H1 ("held back the automatic train") never logged in Thomas's run: that siding's automatic
   train was already out. Verify suppression with a slot whose automatic train is in its siding, and
   check the boards show the manual run late (vehicleTimesAlongRoute injection).
4. No-show reinstatement (2 min grace → automatic train runs late) untested.
5. Two-player crew (driver + guard interlocks, buzzer codes, guard doors, guard HUD) never tested.
   A second dev client (`--username Rig2`, separate run dir) could do it.
6. Incidents untested — raise chances in `config/station-announcer-addon.json` (`crew.*Percent`) to force them.
7. Cab seat / lean: first mixin on a vanilla class (`Camera#update`); the seat inset 0.6 is a guess
   (per-train overrides `cabSeatTrains`). Gangway-at-cab-end trains, curves, turnarounds unverified.
8. Score: punctuality scored from slot rows; passengers left-behind hook = `CabScore.leftBehind(int)`.
9. DrivingHudScreen is still vanilla widgets (Thomas prefers FlatUi) and has no rows for seat/lean.
10. The old `run/world_baker` has Liz Line siding "1" made manual by `/crew devmanual` (rig copy only).
11. README "Driving" section written (docs agent); the lead added the passenger crowd, the −1 per
    passenger left behind and the two `cabPassengers*` client settings.
12. Depot groups add nothing to the slot list today: Thomas chose identical routes, and identical-route
    depots qualify without a group. Harmless; revisit if partial-route slots come back.
13. The DrivingHudScreen row for the classic crew panel was repurposed (style cycle); `crewPanelEnabled`
    is config-file only. The FlatUi settings screen (agent) should expose it.
14. README intro fixed: it now says MTR 4.x is required and the MTR integrations use mixins.
15. FlatUi settings screen `cab/CabSettingsScreen` (H opens it; "Classic options…" → old DrivingHudScreen):
    HUD, cab seat, lean, crew, read-only key caps + "Change keys…". The passenger section is wired by
    REFLECTION to `cabPassengers`/`cabPassengersMax` (hidden until those fields exist) — replace with
    direct field access once the crowd's config lands, and match the slider range to the real field.
    javac only; never opened in game.
17. SOLVED (rig agent): the lost path came from the restart script's `kill -9` of the server (MTR's
    siding file was left without its path). Scripts now stop the server via RCON first. Original note:
    Between Thomas's 12:21 and 12:41 runs, "Line 1 - Driveable" (rig copy) lost its generated main route
    (manual siding with 0 baked stop times, path = siding only → the train went straight back to the
    depot and every run ended at once). The rig agent re-ran `/mtr generate`. Find out WHY: MTR quirk on
    reboot, or one of our generation hooks (DepotMixin/SidingMixin/PlatformGroupEngine/DwellOverride, the
    new slot accessors). If a manual depot can end up path-less, the cab menu should say so instead of
    offering slots that cannot run.
16. Passenger crowd written (`cab/CabPassengers`, `PaxEntity`, `PaxPlatform`, `PaxPerson`): client-only
    OtherClientPlayerEntity people (negative ids from -1,900,000, no name tags, no collision, never
    hittable), platform side probed from blocks, doorways from MTR's `VehicleResource` doorways,
    left-behind counted at departure → `CabScore.leftBehind`, `boardingCount()` for the guard; shared
    seeds so driver and guard see the same crowd. Risk: they appear in `ClientWorld.getPlayers()`
    (minimap mods may show dots). Integration handed to the rig agent (register line, two config fields,
    settings screen reflection → direct access, guard boarding line).

## 4. Next tasks (in order)

1. Integrate the passenger crowd (its agent's integration lines), compile, boot the rig.
2. Rig verification pass (§3 items 1, 3, 4, 6, 7, startup, horn tab, report card) with screenshots;
   fix what breaks. Ask Thomas to board when the rig cannot.
3. Two-player crew test (§3 item 5).
4. Send route number/colour in the feed slot (§3 item 2); guard-HUD "N passengers still boarding"
   from `CabPassengers.boardingCount()`.
5. Cab settings on FlatUi (seat/lean/style/vigilance/startup) — replace the vanilla DrivingHudScreen rows.
6. README + CLAUDE.md for release; version bump; deploy only with `tools/deploy_jar.sh` (game closed).

## 5. Verification log (rig agent, 2026-10-05 afternoon)

Rig: `world_baker2`, scripts in the session scratchpad (new: `server_restart.sh` = server only, the
client stays up and must rejoin). Thomas drove the rig client himself for most of this pass, so
verification below is partly his live testing, observed through logs + `screenshot.flag`.

**Builds / boots**
- 12:31 full rig boot (server 2.2 s, client joined): zero mixin/injection errors, no
  station_announcer exceptions (server: only the usual `seat` data-fixer line + MTR loot noise).
- 12:38 server-only restart with the slot-picker fix (below).

- 12:39 client restart (passenger crowd integrated), 12:44 full restart (marker/startup/RUNNING fixes).

**Verified**
- Cab menu Train tab (horn per train type): renders, the chosen horn row highlighted, Test / Use
  buttons, operator note — Thomas used it live (`2026-10-05_12.35.44.png`).
- Cab menu Start up tab: three steps, Key in button, Skip (`2026-10-05_12.40.52.png`).
- Service tab offers slots again after the picker fix; Thomas claimed "12:45 · 1 to Armory".
- Route bullet + colour from the slot before the first stop: red "1", destination Armory, while
  MTR still calls the train "not in service" (`2026-10-05_12.42.32.png`).
- A claimed run leaving the siding and staying RUNNING (after the depot path was regenerated,
  below): HUD live — next stop "Woodyville 439m", signal, current + next limit, lateness −2:57,
  live score "0 pts" (`2026-10-05_12.47.50.png`); log: `manualTimesMatch=true, stops auto 38 / manual 38`.
- At the first stop (Woodyville): doors open, departure countdown "Dep 160s", stop marker "−1m" in
  RED = the new signed overrun offset (`2026-10-05_12.48.47.png`). Same shot shows two bugs, both
  fixed since (see below): no passengers on the platform, and our action-bar notice drawn over the
  drive panel.
- Cab & crew settings (H, the new FlatUi screen) opened by Thomas on the rig: HUD / Cab seat / Lean
  out / Crew / Passengers / Keys sections, scroll bar, Classic options…, Cancel, Save; passenger
  slider 0–200 (he set 60) (`2026-10-05_12.50.29.png`). The "Back from front" label truncated in its
  column → renamed "Set back". NOT verified: Save/Cancel/Esc round trip, Classic options… screen.
- Second run (12:55 claim, after the 12:52 restart): the new no-path guard fired at once
  ("refused a claim … its depot path is not generated" ×2) → Thomas pressed Refresh Path → claim
  38/38 → run RUNNING. Crowd standing on Valley Lake's platform as the train approached
  (`2026-10-05_12.56.37.png`); live score moving (−3 after an early departure + perfect stop).
- Incidents (chances raised to 30/30/20 %): log `[incident] … type 2 for 13 s`, `type 2 for 23 s`
  (held by control), `type 1 for 45 s` (door fault) on Thomas's crewed train 12:58–12:59; the train
  went on after each (no stuck state). 13:02:08 `type 3 for 40 s` (sick passenger) at Pig Plains:
  the crew notice "Sick passenger on the train" in the HUD event line (`2026-10-05_13.02.11.png`, the
  new notice routing), then the incident line "Sick passenger: doors stay open · 21s"
  (`2026-10-05_13.02.28.png`). Same first shot: the passenger crowd standing and walking on Pig
  Plains' platform beside the open train (the tolerant-stop fix working), departure "Dep 438s".

**Fixed**
- **Slot picker offered nothing** on Line 1 - Driveable after the boot ("[slot] no slots …
  Line 1 - Armory B: 5 automatic sidings, 4 with departures" every 4 s while Thomas had the
  Service tab open). `listOptions` dropped every other-depot siding whose BAKED stop sequence
  differed from the manual siding's (`samePlatforms`). Candidate depots already run the identical
  route list (= the same platforms in order, Thomas's §6.16 rule); the baked sequence can differ
  through the siding legs. Filter removed (claim() already lines rows up by order only when the
  counts match); the "no slots" line now prints stop-time counts per depot, and the claim line
  prints `manualTimesMatch` + both counts. If a Line 1 claim logs `manualTimesMatch=false`, rows
  carry the AUTOMATIC siding's platform ids and arrival detection may never hit the manual train —
  then match rows by station (`Platform.area`) instead of platform id.
- **Route number + colour in the feed slot** (§3 item 2): `CabFeed.Slot` gained routeName,
  routeNumber, routeColor, destination (write/read updated together); `SlotEngine.Claim` stores
  them; `CabData.runFromSlot` uses them (bullet = number, else `RouteBullets.label(name)`) — the
  label-parsing hack and the hard-coded green are gone.
- **Passenger crowd integrated**: `CabPassengers.register()` in AddonClientInit,
  `AddonClientConfig.cabPassengers` / `cabPassengersMax` (40), CabPassengers reads them,
  CabSettingsScreen uses them directly (slider 0–200), event line "N passengers still boarding"
  (`CabPassengers.boardingCount()`) for the guard (before "close doors, then buzz") and for a
  driver working alone, while the doors are open. No client code of ours iterates world players
  (grep: no `getPlayers()` outside the crowd), so the fake OtherClientPlayerEntity passengers
  cannot be taken for real players by the horn/crew code.
- **A claimed run ended the moment it started** ("started" + "ended: back in the siding" in the
  same second, 4× in a row while Thomas tried to leave). Two causes:
  1. H4 (`SlotEngine.manualStart`) set the claim RUNNING inside MTR's `startUp` call, but 4.0.1's
     `startUp` is a no-op while the door cooldown (4.2 s after the doors close) runs. Now the claim
     is only promoted to RUNNING by `track()` once that vehicle is really on its route.
  2. The real blocker on the rig: **"Line 1 - Driveable" had no generated path** (siding only — its
     siding had 0 baked stop times, the feed showed no stops ahead, and MTR sends a train that reaches
     the end of its path straight back into the depot). Something between Thomas's 12:21 run and the
     12:31 boot left it ungenerated (rig world copy). `/mtr generate Line 1 - Driveable` (and Thomas's
     own Refresh Path) fixed it; claims now log `manualTimesMatch=true, 38/38`. **Worth a guard:** a
     claim on a manual siding with 0 stop times could warn the driver ("depot path not generated").
- **Every stop scored "Perfect stop" and overruns were impossible**: at a platform the feed sent
  distance 0 and the client forced the marker to 0. The feed's standing stop now carries the signed
  offset from the stopping mark (+ short, − past, `FeedBuilder.standingEnd`), the HUD marker turns red
  on an overrun, CabScore judges ±0.5 m / −2 m by it.
- Start up tab: step 3 ("Brake test: apply the brake to B7 [key]") was truncated as if it had a
  button; the skip hint now points at "Cab & crew settings" (the new FlatUi screen).
- Top-left panel's next-stop row was blank in the siding (MTR has no path ahead there): it shows
  the slot's first stop (no distance) or a faint "▸ --" so the row never disappears.
- Dev hook `#cab-menu <tab>` (0 start up, 1 service, 2 train).
- **The passenger crowd never boarded a driven train**: it used MTR's exact end-point test for
  "stopped at a platform" (`CabPassengers.stoppedPlatform`), which a manually driven train never
  meets. Replaced by the server feed's tolerant test (`standingSegment`: head anywhere along the
  platform, up to 3 m past the end) and the standing SEGMENT is passed on (it was assumed to be
  `headIndex - 1`).
- **Our action-bar notices drew over the drive panel** ("Out of the cab seat: N to sit back down"):
  while the cab HUD draws, crew notices (`CrewClient.showMessage`) and the cab-seat notice go to the
  top-centre event line for 3 s (`CabHud.notice`); the action bar is used only with the classic HUD.
- **A claim on a manual siding without a generated path** is refused with a clear message
  (`MSG_SLOT_NO_PATH`: "This train's depot has no route path yet: use Refresh Path in its depot screen").
- **MTR Train Announcer blocks' chat lines drew over the drive panel** ("The elevator is located at
  the rear of the platform." — Thomas's trackside announcers): new client mixin
  `TrainAnnouncerCabMixin` (redirect of `IDrawing.narrateOrAnnounce` in
  `BlockTrainAnnouncer$BlockEntity.lambda$announce$0`, javap 4.0.1) — while the cab HUD draws the line
  goes to the HUD event line (3 s), the spoken part still plays. Notices now rank after the vigilance
  lines.
- **Stale stop marker after running through a platform** ("−68m" / "−80m" in red at 120 mph):
  MTR keeps the stopping details of the platform passed; more than 10 m past it and moving, the
  marker now belongs to the next stop.
- **Rig hazard (likely cause of the lost depot path, issue 17):** `rig_restart.sh` killed the server
  with `kill -9`. MTR writes its data files in place; the Driveable siding's file on disk was last
  written at 12:18 and appears to hold only the base fields (no `pathSidingToMainRoute`) followed by stale bytes
  of an older, longer version — so every boot loaded the siding WITHOUT its path (12:31 and 12:52
  boots both did; regenerating fixed it each time in memory only). The restart scripts now stop the
  server through RCON (`save-all flush`, `stop`, wait ≤60 s, kill only what is left —
  `scratchpad/stop_server_clean.sh`, `rcon.py`). To confirm at the next restart: claim on Driveable
  without a Refresh Path.
- **Report card / score review** (lead's question about "false skips"): the −395 run's skips were
  REAL — Thomas ran through those stations at 120+ mph (screenshots 12:56–13:07 show it, e.g. "Wet
  Paul 50m" at 40 mph then Geode River next). The 8 scored departures (Woodyville, Sparks Village,
  Pig Plains ×2, Elfwood, Deep Canyon, Patagonia, Armory) are exactly where he stood; the 6 "perfect
  stops" are a subset (Pig Plains: he stood 48 m short behind the train ahead, then crept up to the
  mark — the stop that counts is the last standstill). Lines sum to the total (−395 = −395). Added
  per-row `[slot]` lines (arrived / departed / skipped at N km/h / standing at a platform that is no
  later row) and `[score]` lines (stop offset, emergency brakes) so the next run can be audited.
- **Emergency brakes: 12** came from counting every power transition to −8, including EB held at a
  stand and pumping the handle during one stop. Now one emergency per stop, only when moving
  (> 5 km/h); vigilance trips / passenger alarms are labelled "(vigilance)" / "(passenger alarm)".
- **Rig crash 13:15:27** (`ClassNotFoundException: NameColorDataAccessor`, `AddonStore`): the rig
  restart's client Gradle task recompiled (sources had changed mid-restart) while the new server was
  already running, deleting class files the server loads lazily. `client_restart.sh` now runs
  `runClient -x compileJava -x processResources -x classes`; only `runServer` compiles.
- **Issue 17 confirmed**: after a clean RCON stop + boot the Driveable siding kept its path
  (`/crew devdepots driveable` → `stopTimes=38`, no Refresh Path needed). `devdepots` now also prints
  each siding's idle trains, stop-time count and seconds to its next departures (for picking an H1
  test slot).
- Dev hook `#cab-report [last]` opens the report card with a sample run (score = sum of its lines)
  or the newest saved run.
- Incident chances restored to defaults on the rig (the `crew` section removed again).
- **Verified by Thomas (his words via the lead):** incidents "they are good"; horn works; the
  startup ritual worked but "isn't very fun" → rebuilt as a cinematic (another agent; Thomas "loved"
  it, now making every step need input); the cab lean-out "works".
- **Cinematic startup framing** (preview mode on the Driveable siding at night in rain, GUI ≈504×285;
  sent to the lead for the cinematic agent): shot 0 with a bare `hold` = all-black frame (the default
  hold tick is the shot's last tick = fade; `hold 16` fine — `2026-10-05_13.45.32.png` vs
  `13.47.04.png`); shot 1 "Lights on" badly occluded by brick pillars / a slab / a glass pane — the
  car-side raycast passes through a narrow gap (`13.45.39.png`); shots 2 and 4 very low under-floor
  angles (`13.45.46.png`, `13.46.00.png`); shot 3 door test good but doors stay closed in the siding
  (`13.45.53.png`); shot 5 back in the cab (`13.46.07.png`). Letterbox, dots, skip hint, captions and
  the BATT/DOORS/B7 strip fit; no rotated or flipped train. Not done: a live run, doors at a platform.
- Server: before the driver has started up, a door opening at a stop no longer rolls incidents or
  sets `doorsThisStop` (the cinematic's door test; in the siding MTR runs `simulateInDepot`, which
  never reaches that code anyway).
- No-show reinstatement: `startUp` on the automatic train is now checked (a no-op during the door
  cooldown is retried next tick instead of ending the claim as "reinstated").
- **H1 on Line 1 - Armory B cannot fire as the depot is set up:** `devdepots armory b` shows every
  automatic siding with ONE train, out on the line (`idle=0`), departures 800 s apart while a round
  trip takes ~1080 s — at its departure time a siding's train is never home, so MTR has nothing to
  deploy and nothing to hold back. Testing H1 needs a depot whose train is idle at the slot's time.
- **H1 + no-show reinstatement VERIFIED (13:48–13:50, headless):** `#cab-menu 1` (Service tab,
  `2026-10-05_13.48.18.png`: six upcoming slots with yard times and countdowns, "Give back" on the
  claimed one) → `#cab-claim` took "13:49 · 1 to Armory" (Armory B siding 3, index 1), whose
  automatic train WAS home (`devdepots`: `idle=1`, departure in 20 s). Log: 13:48:54 `held back the
  automatic train for 13:49 · 1 to Armory (PENDING)` (H1) → 13:50:54 `no-show, the automatic train
  takes it back` → `the automatic train runs it, 119 s late` → `ended: reinstated`. (The H1 note above
  about Armory B is only true while its trains are out; siding 3 was home this time.) NOT verified:
  the boards (PIDS) showing that late automatic run / a late manual run.
- **Cinematic round 2** (each step waits for input, Service tab at the end; clean restart 13:51):
  shot 1 framing now clear (`13.52.35.png`, but no caption at the preview mid-shot hold); shots 2/4
  raised but the camera ~1 m from the car body (`13.52.42.png`, `13.52.49.png`); `hold prompt` OK
  (`13.52.56.png`). LIVE run walked with `#cab-cinematic` + `step` ×8 (`13.53.28`…`13.54.16.png`): key
  → lights → battery → doors open → close → brake with the live notch N→B1→B4→B7 → the cab menu opened
  on the Service tab by itself. One `Failed to load texture: minecraft:` client warning at the first
  exterior frame (empty texture id, not from CabCinematic). Depot door test cannot roll incidents
  (`tickInDepot` never calls IncidentEngine) — confirmed.
- **Cinematic round 3** (clean restart 13:59, client left running for Thomas): shot 1 hold now shows
  its caption (`14.00.21.png`); Battery (`14.00.29.png`) and Brake (`14.00.37.png`) pulled back to a
  readable distance — no occluders, no flipped train. Framing done.
- **Third run (Thomas, 14:01 claim) with the new row logs:** `row 0 Woodyville arrived (−26 s)` /
  `departed`, `row 1 Valley Lake arrived` / `departed`, then `row 2 Patagonia skipped (ran through at
  31 km/h)`, `row 3 Fey River … 29 km/h`, `row 4 … 175 km/h`, `row 5 … 200 km/h` — the arrival
  matching works; the skips are run-throughs (the speed at 10 m past the platform end is logged). The
  two 30 km/h ones look like late braking that ended > 3 m past the end (outside `platformAt`'s
  tolerance) — a genuine overrun-and-continue, not a false skip.
- **"Perfect" stops at exactly 0.00 m** (`[score] stop at Woodyville: 0.00 m`): MTR 4.0.1
  (`Vehicle.simulateMoving`) itself applies EB (−8) to a manual train that would pass a stopping point
  it must stop at (reversing points) and puts it exactly on the mark; MTR's ATO does the same.
  CabScore now tells them apart: an EB while moving with the brake key NOT held and no
  vigilance/alarm = MTR's forced stop → no EB penalty, and that stop (or any stop made while
  `!getIsCurrentlyManual()`, i.e. ATO) earns no perfect-stop points; the `[score]` line says
  "(MTR forced the stop)" / "(ATO)". Driver EBs (brake key held) and vigilance/alarm EBs still count,
  once per stop. Client change, javac green, NOT yet on the rig (Thomas was driving).

## 6. Pending at end of session (2026-10-05, lead)

- NOT yet on the rig (javac green; needs one clean `rig_restart.sh` after Thomas's current run):
  the rig agent's last CabScore change (forced-stop / ATO stops earn nothing) and the lead's slow
  run-through rule — `SlotEngine` marks a stop passed under 30 km/h as overran (-2) instead of skipped
  (-1); `CabScore` scores it −3 "Overran X, did not stop" (Thomas, plan §6 item 18). Creep-up stays as
  is (final standstill scored).
- Still unverified: PIDS showing a late manual / reinstated run, the two-player crew test, a vigilance
  trip, the settings screen's Save/Cancel/Esc/Classic options, the cinematic door shot at a platform.
- Nothing committed or deployed. All subagents finished.

