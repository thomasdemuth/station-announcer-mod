# Timetable slot takeover — MTR 4.0.1 research (2026-10-05)

Research for MANUAL_DRIVING_PLAN.md §3.3 / §6.2 / §6.4. **Nothing here is built.**
Source of truth: the 4.0.1 jar (`minecraft-transit-railway-FABRIC-4.0.1+1.20.4.jar`),
`javap -p -c` plus a Vineflower decompile of `org/mtr/core/**` in the session scratchpad
(`.../8d40244b-.../scratchpad/src`; regenerate with the scratchpad `vineflower.jar` over an
`org/mtr/core` extract if gone). Offsets below are javap bytecode offsets inside the named method.
`mtr-src/tsc` (master) was not relied on.

**Correction to MANUAL_DRIVING_PLAN.md §1 fact 6:** an undeployed slot stays on PIDS **only until
its yard departure time**. After `departure + 1 s` an unpredicted row is dropped
(`missedDeparture`, see C). If we suppress the automatic train and inject nothing, the slot
disappears from every board the moment it should have left the yard.

---

## 0. Recommended design (short)

**Identity of a slot** = `(autoSidingId, departureIndex N, T_A)`. `T_A` is the absolute
yard-departure time of that one occurrence: `T_A = d_N + k·R`, where `d_N = departures.getLong(N)`
(a raw value, not epoch time) and `R = siding.getRepeatInterval(86_400_000)`.

**State.** One `SlotTakeoverEngine` per `Simulator`. All of its state is touched only on the
simulator thread. The server thread only posts `simulator.run(...)` requests and reads a volatile
immutable snapshot for packets and the HUD.

A claim records:
- the manual siding id and the driver UUID
- the manual vehicle id, bound when the train leaves the siding
- the auto siding and depot ids, `N`, `d_N`, `T_A`
- the slot timetable: one row per stop with stop index, route id, the auto siding's platform id,
  the manual siding's platform id, and the scheduled arrival and departure (absolute)
- `T_M*` (the manual train's virtual yard departure, see B)
- a state: `PENDING` → `RUNNING` → `ENDED`/`RELEASED`

**Hooks** (all `remap = false`; MixinExtras `@WrapOperation` is an option for H1/H4, since the
track work already uses it):

| # | Target (4.0.1) | Kind | Purpose |
|---|---|---|---|
| H1 | `Siding.simulateTrain(J, ObjectArrayList)V` → `Vehicle.startUp(JJ)V` at offset 373 (the **only** `startUp` call in `Siding`) | `@Redirect` | Skip the call when `this` siding, the index and this occurrence (`now` is within a window of `T_A`) match a live claim. Otherwise call through. |
| H2 | `Siding.simulateTrain` TAIL | `@Inject` | (a) On the manual siding: the tracker updates the claim's live deviation `D` from the manual vehicle, which has just been simulated. (b) On the auto siding: `vehicleTimesAlongRoute.put(N, floorMod(now − d_N − D, R))` so MTR's own `getArrivals` marks slot N *predicted* with deviation `D`. Skip both when `vehiclePositions == null` (that is Instant Deploy, see F). |
| H3 | `Siding.iterateArrivals(JJJJLorg/mtr/core/data/Siding$ArrivalConsumer;)V` (private) | `@Inject` HEAD, cancellable, handler takes only `CallbackInfo` | Cancel on a manual siding that holds a RUNNING claim, so the train is not listed twice. This covers `getArrivals`, `getDepartures` and the OBA feed in one place. |
| H4 (optional) | `Vehicle.simulateInDepot()V` → `startUp(JJ)V` at offset 87 | `@ModifyArg(index = 1)` | Replace `data.getCurrentMillis()` with `T_M*` for a claimed manual vehicle. MTR's own `Vehicle.deviation` then becomes slot-relative. That drives catch-up behaviour after the manual→ATO timeout (`deviationSpeedAdjustment`, late-dwell reduction). |
| H5 | `Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()V` TAIL (DepotMixin already hooks HEAD); `Depot.generateMainRoute` HEAD / `Siding.finishGeneratingPath` HEAD (already hooked) | `@Inject` | Revalidate claims every time departures are rewritten, which is **every ~25 s** (see A5). Release claims on path regeneration. |

New accessors:
- `Siding.departures` (`LongArrayList`)
- `Siding.vehicleTimesAlongRoute` (`Long2LongAVLTreeMap`)
- `@Invoker Siding.getRepeatInterval(J)J` (package-private)
- optionally `Siding.trips`

Existing accessors that cover the rest: `SidingStopTimesAccessor`, `SidingVehiclesAccessor`,
`VehicleSchemaAccessor` (speed / railProgress / elapsedDwellTime), `VehicleDeviationAccessor`.

**Data flow.**
1. Picker: client → C2S → server thread → `simulator.run(listSlots)` → `server.execute(send S2C)`.
2. Claim: same path. Validation and insert run on the simulator thread, which is the thread that
   deploys, so there is no race with H1.
3. Every simulator tick, H2 computes `D` and injects it. The engine publishes
   `{vehicleId → D, next stop, rows}` as a volatile snapshot, and `CrewService`'s change-only
   status push sends it to the driver. The HUD stops relying on `vehicle.getDepartureIndex()`
   (always −1 for a manual train, see C).
4. PIDS need **no client change**: arrivals for slot N come back as `realtime = true`,
   `departureIndex = N`, `deviation = D`, with the auto siding's scheduled times. So "driver late"
   shows as "late" (Thomas §6.4).

**Policies (proposed).**
- *Timeout* (§6.4/§7): the claim survives. The tracker keys on the vehicle, not on manual mode.
- *No-show*: release at `T_M* + grace` (config, e.g. 120 s) if the train never left the siding.
  The row then vanishes, which is MTR's own semantics for a missed departure.
  Optional "reinstate": `startUp(N, d_N)` on the auto siding's idle train, which runs late.
- *Abandon*: release when the vehicle disappears (clear, regenerate, siding edit). Driver leaving
  mid-run is not an abandon: MTR's timeout makes it ATO and the claim stays.
- *Contention*: first come; one claim per slot and one per manual siding.
- Claims are runtime-only. They are not persisted (see F, restarts).

---

## A. How departures reach sidings, and how a train is deployed

**A1. Generation:** `Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()` (public).
1. It builds a local `LongArrayList departures`:
   - Continuous movement: `i·8000` per siding.
   - Real-time depot: `realTimeDepartures`.
   - Otherwise, per game hour `ix` with `frequency = getFrequency(isTimeMoving ? ix : getHour())`:
     `interval = 14 400 000 / frequency` (game-ms), and each departure is
     `simulator.getMillisOfGameMidnight() + newDeparture · gameMillisPerDay / 86 400 000`.
2. `repeatDepartures = ceil(highestJourneyTime / gameMillisPerDay)`. The list is replicated for
   each repeat day.
3. Sidings are ordered with `Collections.shuffle` then `Collections.sort`. `SavedRailBase.compareTo`
   sorts by numeric name, then `name + color + id`, a total order, so the result is deterministic.
4. Every siding gets `startGeneratingDepartures()` (which clears `departures` and fills
   `tempReturnTimes` with `maxVehicles` zeros).
5. Departures are handed out round-robin via `addDeparture(departure + gameMillisPerDay·ix)`.
   This is the single call site that the existing `DepotMixin` redirect hooks, at offset 571.

**`Siding.addDeparture(J)Z`:**
- manual siding → `false` (it never gets departures)
- unlimited siding → always added
- otherwise added only if some `tempReturnTimes[i] ≤ departure`; that slot then becomes
  `departure + getJourneyTime()`

**A2. What a departure value is.** It is the time the train **leaves its siding** (time 0 of that
siding's trip timeline). It is a *raw* value, not epoch time.
- Frequency depots: the raw values lie in `[phase, phase + gameMillisPerDay·repeatDepartures)`,
  where `phase = lastSetGameMillisMidnight mod gameMillisPerDay`.
- `gameMillisPerDay` is the constant `1 200 000` (20 real minutes), sent by `Init.lambda$init$10`
  offset 34.
- Real-time depots: ms-of-day, with `R = 86 400 000`.

**Repeat interval:** `Siding.getRepeatInterval(long default)` (package-private):
- continuous movement → `8000·sidings`
- repeat-infinitely → `timeOffsetForRepeating`
- frequency depot → `gameMillisPerDay·repeatDepartures`
- real-time depot → the default (86.4 M)

**Absolute time of index i:** the occurrences are `d_i + k·R`. MTR's own matcher uses
`offset = floor((now − d_0)/R)·R` (`Siding.matchDeparture`). Next occurrence ≥ now:
`d_i + ceilDiv(now − d_i, R)·R`.

**A3. Deployment:** `Siding.simulateTrain`.
- For each vehicle, after `vehicle.simulate(...)`, if the vehicle is **not on route**:
  - The second idle train is removed (`++trainsAtDepot > 1`).
  - Otherwise, if `!pathSidingToMainRoute.isEmpty() && !getIsManual()`, it runs
    `departureIndex = matchDeparture()` (offset 274).
  - If the index is in range and (`continuousMovement` or no vehicle of this siding already has
    that index), it calls `vehicle.startUp(departureIndex, departures.getLong(departureIndex))`
    at offset 373.
- `matchDeparture()` returns the **first** `i` with `simulator.matchMillis(d_i + offset) == 0`.
  That is true iff `lastMillis < d_i + offset ≤ currentMillis` (circular over 86.4 M), i.e. the
  departure falls inside *this tick's* window.
- `Vehicle.startUp(JJ)V` always calls `closeDoors()`. It only commits if `doorCooldown == 0`: it
  sets `departureIndex` and `sidingDepartureTime`, nudges `railProgress`, and calls
  `updateDeviation()`.

**A4. Suppression hook and what happens next tick.** Skipping the offset-373 call (H1):
- The vehicle stays in the siding. `closeToDepot()` keeps `spawnTrain == false`, so no extra
  train spawns.
- Next tick the window has moved past `d_N + offset`, so `matchDeparture` never returns N again
  for this occurrence. **MTR never retries a departure.** It already silently drops one whenever
  no idle train is present or `doorCooldown > 0`.
- The idle train then takes the siding's next assigned departure normally, and occurrence
  `T_A + R` of index N deploys normally.
- So "only that departure, only once" comes for free, provided H1 compares the occurrence time
  (`|now − T_A| < a few seconds`) and not just the index.
- Edge case: if two departures of one siding fall in the same tick, MTR only ever handles the
  first. H1 could re-scan for a second matching index, but that is not worth it.

Alternatives rejected:
- Removing N from `departures` shifts every later index and breaks every claim and every client
  matching by `departureIndex`.
- A HEAD-cancel in the existing `VehicleMixin.startUp` would have to tell depot deployments from
  platform departures, and would interleave with the hold, gap-filler and obstruction logic.
- `@Redirect matchDeparture()I` → −1 is equivalent, but cruder.

**A5. Departures are rewritten every ~25 s.**
- `Init.lambda$init$13` is registered with `EventRegistry.registerStartServerTick`. Every
  `serverTick % 500 == 0` it runs `sendWorldTimeUpdate`, i.e. `lambda$init$10`, which sends
  `set_time`.
- That reaches `Simulator.setGameTime`, which records `lastSetGameMillisMidnight` and calls
  `depots.forEach(Depot::generatePlatformDirectionsAndWriteDeparturesToSidings)`.
- Indices are positional in a sorted list, so they are stable as long as frequencies, the siding
  set, journey times and realtime settings are unchanged.
- **Raw times can move.** Lag (game time slower than wall time), `/time set` and sleeping through
  the night all move the phase. So does a depot-delay change (`DepotGroupEngine`).
- Hence H5: after each rewrite, re-resolve each claim's `d_N` and `T_A` as the occurrence of N
  nearest the old `T_A`.
  - If the index is gone (list size changed and N is out of range), release.
  - If `T_A` moved more than about 60 s, notify the driver and update the HUD targets.
- (Side note for the repo: `DepotMixin.computeDepartureOffset`'s javadoc says the method only runs
  from `init`/`finishGeneratingPath`. It also runs every 25 s from `setGameTime`, so
  `PlatformGroupEngine.commitPending` runs every 25 s. Probably harmless, but the comment is
  wrong.)

## B. The per-departure timetable

**`Siding.generatePathDistancesAndTimeSegments()`** (private) runs at `init` and
`finishGeneratingPath`.
- It walks `pathSidingToMainRoute + pathMainRoute + pathMainRouteToSiding` with this siding's
  acceleration, deceleration and train length.
- It builds `timeSegments`, used by `getTimeAlongRoute(railProgress)` (public).
- It builds `trips` (`Trip(route, routeIndex, tripIndexInBlock, siding)`) and
  `platformTripStopTimes` (`Long2ObjectAVLTreeMap<ObjectArraySet<Trip$StopTime>>`).
- **Every siding builds these, including manual ones.** There is no `getIsManual` check in the
  method.

**`Trip$StopTime`** has public final fields `trip`, `startTime`, `endTime`, `platformId`,
`tripStopIndex` and `customDestination`.
- `startTime` and `endTime` are ms **after the siding departure**: arrival at the platform, and
  arrival + dwell.
- The first stop's time includes the yard run, because its dwell segment is the last entry of
  `pathSidingToMainRoute`.
- When route k ends where route k+1 starts, the duplicate is collapsed and attributed to route
  k+1 (stop index 0).

**Server-side recipe** (simulator thread) for auto siding A, index N:
```
rows = flatten(((SidingStopTimesAccessor) A).getPlatformTripStopTimes().values())
       sorted by startTime   // equals the trip/stop order; trip.tripIndexInBlock, trip.route,
                             // tripStopIndex tell you which route and stop each row is
R    = A.getRepeatInterval(86_400_000)            // invoker
d_N  = departures(A).getLong(N)
T_A  = d_N + ceilDiv(now − d_N, R)·R              // or the occurrence nearest the claim
row i: schedArr = T_A + st.startTime, schedDep = T_A + st.endTime
```
This matches what `iterateArrivals` publishes: `scheduledArrivalTime = stopTime.startTime +
R·departureOffset + departure`.

**Yard offset for the manual siding M.** M has its own stop times `a_M`/`e_M` with a different
siding→first-platform run. The driver's virtual yard departure is
**`T_M* = T_A + a_A(p1) − a_M(p1)`**, aligned on first-platform arrival. The HUD shows
"leave the yard by `T_M*`, then depart `p1` at `T_A + e_A(p1)`".
- After the first platform both sidings run the same depot `path`. Inter-stop times still differ
  slightly if M's acceleration, deceleration or train length differ from A's.
- So targets after `p1` must come from **A's** rows. Use M's rows only to interpolate between
  stops (C2).
- Map stops between A and M by order, i.e. (trip index, `tripStopIndex`), not by platform id. A
  depot-group sibling can bake different platforms (see F).

## C. Arrivals / PIDS

**C1. How rows are built.** `Siding.getArrivals(long now, Platform, long count, out)`:
- It calls private `iterateArrivals(now, platformId, 0, 86_400_000, consumer)` and builds
  `ArrivalResponse(dest, schedArr + deviation, schedDep + deviation, deviation, predicted,
  departureIndex, tripStopIndex, route, platform)`, then `setCarDetails(getVehicleCars())`.
- The core caller (`ArrivalsRequest.getArrivals`) visits every siding of every depot of every
  route at the platform. It runs on the simulator thread from the C2S queue, after
  `sidings.forEach(simulateTrain)` in `Simulator.tick`.

**`iterateArrivals`** runs only if `area != null && !departures.isEmpty()`. For each stop time at
the platform and each index `i`:
- `departureOffset = (now − R/2 − endTime − d_i)/R + 1`
- `getPredictedAndDeviation(now, isManual ? −1 : i, offset)`:
  - `tar = vehicleTimesAlongRoute.getOrDefault(i, −1)`
  - `predicted = tar ≥ 0`
  - `deviation = predicted ? circularDifference(now − R·off − d_(manual ? 0 : i), tar, R) : 0`
- It then loops over occurrences:
  - skip if `schedDep + dev < now` or `schedArr + dev > now + window`
  - skip if **not predicted and the yard departure has passed**
    (`schedArr − startTime + 1000 < now`)
  - manual sidings emit only one row per stop time

`vehicleTimesAlongRoute` (private `Long2LongAVLTreeMap`):
- It is cleared at the HEAD of every `simulateTrain` (offset 1).
- Each vehicle fills it with `put(departureIndex, getTimeAlongRoute(railProgress))` when the value
  is > 0. That time includes `elapsedDwellTime` (`Vehicle.simulate`).
- **This map is the only link between vehicles and PIDS.** Matching is purely by
  `departureIndex`; there is no `vehicleIdMap` in 4.0.1.

**C2. What PIDS show for a slot whose train never deployed:** scheduled times,
`realtime = false`, deviation 0, until `T_A + 1 s`. After that the row is gone.

**What MTR already shows for a manual train:**
- When the manual train is on route and `departures` is empty, `simulateTrain` adds
  `departures.add(vehicle.getSidingDepartureTime() − 86 400 000)` (offsets 200–229).
- It clears that again when the train is back in the siding.
- So M publishes one predicted row per stop with `departureIndex = −1`, M's own scheduled times,
  and a deviation relative to *its startup time* (`simulateInDepot` → `startUp(−1, now)`).

**Options for making slot N show the manual train:**

**Option 1 — inject into the auto siding (recommended).** H2b runs at the TAIL of A's
`simulateTrain`:
```
vehicleTimesAlongRoute(A).put(N, Math.floorMod(now − d_N − D, R))
```
- MTR then computes `circularDifference(now − R·off − d_N, v, R) = D`, wrapped into `[−R/2, R/2)`.
  The wrap holds for any `off`, because `v` is only defined mod R.
- Result: slot N stays listed past `T_A`, is `realtime`, shows `D`, uses A's exact timetable, and
  keeps `departureIndex = N`. Our own `RailroadRouteData` / dispatch clients keep working.
- H3 hides M's duplicate rows.
- Before the train leaves the yard, inject `D = max(0, now − T_M*)`: "on time" until the driver
  is late leaving, then growing lateness.
- **Never inject `tar = 0` naively.** That gives `D = now − T_A`, i.e. *early* before `T_A`.
- Stop injecting at ENDED/RELEASED.

Risks:
- Car details and platform are A's. The platform is wrong only for a group depot with different
  platform-group choices (F).
- `predicted` is per index, so every later occurrence of N in the 24 h window also inherits `D`.
  MTR's own auto trains behave the same way.
- The injection must happen after A's clear in the same tick. A TAIL hook guarantees that.

**Option 2 — use M's own rows.** Do H4 (startUp at `T_M*`) and suppress A's row N with a TAIL
filter on `getArrivals`.
- Pros: correct platforms and cars. Zero injection.
- Cons:
  - A's unpredicted row N still has to be filtered out of the output list, and the filter runs
    after MTR's count cap (you lose a row unless you bump `count`).
  - The scheduled times after `p1` are M's, not the slot's.
  - `departureIndex = −1` collides with every other manual train.
  - Between yard time and the manual departure, *neither* row exists.
  - Manual dwell is wrong: in `simulateStopped`'s manual branch, `elapsedDwellTime` never
    increments, so MTR's deviation climbs during every manual dwell and snaps back on departure.
    Our own `D` avoids this.

**Option 3 — give the manual vehicle `departureIndex = N`. Rejected, dangerous.**
`simulateTrain` (offsets 137–184) removes any on-route vehicle whose index is
`≥ departures.size()`. M's list holds at most one entry, so the train would be deleted. Its
`vehicleTimesAlongRoute` key would also land in M's map, not A's.

**Computing D (tracker, H2a, on M after its vehicles are simulated).**

Inputs per tick, all public or existing accessors: `railProgress`, `speed`, `elapsedDwellTime`,
`vehicleExtraData.immutablePath` (`PathData.getSavedRailBaseId/getDwellTime/getEndDistance`), and
`M.getTimeAlongRoute(railProgress)`. Then, with `i` the last stop departed and `i+1` the next:
- **In the yard:** `D = max(0, now − T_M*)`.
- **Moving between stops i and i+1:**
  - `f = (tarM − e_M(i)) / (a_M(i+1) − e_M(i))`
  - `D = now − (T_A + e_A(i) + f·(a_A(i+1) − e_A(i)))`
  - This is exact at both ends even when M and A have different performance.
- **Dwelling at stop j:** with actual arrival `AA`, `D = max(AA − schedArr_j, now − schedDep_j)`.
- **After a departure:** freeze the actual departure time for the report card.

Stamp nothing in sim time that gates behaviour without the future-slack guard (see F). `D` is
derived fresh every tick, so it needs none.

**HUD.** The client vehicle's `departureIndex` is −1 for a manual train
(`DrivingHud.buildOnTimeRow` bails at that line). The slot rows and `D` must come from our own
packet, not from `ArrivalsCacheClient` matching.

## D. Manual → automatic timeout

**The cooldown.** In `Vehicle.simulate`:
`manualCooldown = (isManualAllowed && anyRider.isDriver()) ? manualToAutomaticTime : max(0, manualCooldown − elapsed)`.
- It is reset by a driver-key holder *riding*, not by input.
- `manualToAutomaticTime` is in ms (`SidingScreen`: minutes·60 000 + half-seconds·500).
- `isCurrentlyManual() = !atoOverride && manualCooldown > 0`.

**At 0 the train is plain ATO on the same `immutablePath`:**
- `setNextStoppingIndex` gives a manual-allowed vehicle `nextStoppingIndexAto` = the next
  dwelling stop.
- `simulateMoving` uses the ATO speed target (× `deviationSpeedAdjustment`) and respects signals.
- `simulateStopped`'s ATO branch dwells per `PathData.getDwellTime()`. It opens doors from 1 s and
  departs at `doorCloseTime` via `startUp(departureIndex = −1, sidingDepartureTime)`.
- If the train was stopped somewhere that is not exactly a segment start (most manual stops), it
  takes the `else if railBlockedDistance < 0 → startUp` branch and simply goes, with no dwell
  there.
- At the end of the path (`repeatIndex2 == 0` and `railProgress ≥ totalDistance − …`), it runs
  `simulateInDepot()`: back in the manual siding with index −1. It never auto-redeploys, because
  manual sidings skip `matchDeparture`.

**Deviation after the timeout** is relative to `sidingDepartureTime`, i.e. startup time unless H4
sets it to `T_M*`. With H4 the ATO train recovers toward the slot (`deviationSpeedAdjustment`,
`delayedVehicleReduceDwellTimePercentage`). Without H4 it does not.

**Keeping the slot is feasible:** the tracker follows the vehicle id and is mode-agnostic, and H2
keeps injecting. If the driver re-boards with a key, `manualCooldown` resets and manual control
resumes mid-run.

**No-show:** in `simulateInDepot`, a manual train starts only if
`isCurrentlyManual() && powerLevel > 0`. A train with no driver, or a driver who never powers up,
sits there forever. MTR does nothing, so the policy is ours (§0).

## E. Listing slots for the picker

Run on the simulator thread (`simulator.run`), cache for a few seconds.

1. **The manual siding.** `M = simulator.sidingIdMap.get(id)`, `DM = M.area`. Require
   `M.getIsManual()`, `!DM.getRepeatInfinitely()` and no continuous movement.
2. **Candidate depots.** `DM`, plus every `simulator.depotIdMap.get(x)` for
   `x ∈ DepotGroup.depotIds` of any group containing `DM.getId()` (`AddonStore.depotGroupsView()`;
   use a snapshot, not the store, from the simulator thread).
   - Keep a depot iff `getRouteIds()` equals `DM.getRouteIds()`, with the same transport mode.
   - Optional stricter check: same baked stop sequence by station. This catches platform-group and
     stop-overlay differences.
3. **Candidate sidings.** For each depot's `savedRails`:
   - Keep `!isManual`, with `timeSegments` built (stop-time map non-empty).
   - Read `departures` and `R` (accessor and invoker), and the first stop time `st1` (min
     `startTime` over the stop-time map).
4. **Upcoming slots.** For each index i, the next occurrence is
   `T = d_i + ceilDiv(now + lead − d_i, R)·R`. Drop a slot if:
   - it is already claimed;
   - a vehicle of that siding is on route with `getDepartureIndex() == i`
     (`SidingVehiclesAccessor`); or
   - `T − now < minLead` (a few seconds, so H1 cannot race).

   Show the yard time `T_M* = T + a_A(p1) − a_M(p1)` (warn if it is in the past: the driver will
   start late), plus `p1` departure `T + e_A(p1)`, route names and destination.
5. Merge, sort by `p1` departure, take the next ~10.

Cost: O(sidings × departures). That is a few hundred `long`s per siding, trivial.

## F. Things that will bite

**Instant Deploy** (`Simulator.instantDeployDepots`): it replays 86 400 × `simulateTrain(1000, null)`
over a whole day, then rewinds `currentMillis`.
- `vehiclePositions == null` identifies the replay. H2 must skip there, and the claim must not
  change state from replayed time.
- H1 may still suppress during the replay. Its occurrence-time window means the replayed
  occurrence `≡ T_A − R`, which must deploy, is never touched.
- **MTR hazard:** during the replay the riders stay registered, so a *driven* manual train keeps
  `manualCooldown` up. With `vehiclePositions == null`, `railBlockedDistance` returns −1, so the
  train can be driven for a whole simulated day: it is teleported. The tracker must handle "the
  vehicle jumped or is back in the siding" (release). Consider refusing or warning about Instant
  Deploy on a depot with an active crew. The existing `FUTURE_SLACK` rule still applies to
  anything stamped in sim time.

**Regeneration.**
- `generatePathDistancesAndTimeSegments` runs `vehicles.clear()`. A regenerate removes the manual
  train with the player aboard. Indices and stop times change.
- Release on `generateMainRoute` / `finishGeneratingPath` (H5).
- `Depot.clearDepots` → `clearVehicles`: release.
- `Siding.updateData` (dashboard edit) drops idle vehicles (`removeIf(!getIsOnRoute())`). The
  manual train respawns with a **new vehicle id**, so a PENDING claim keys on the manual *siding*
  plus the driver, and binds the vehicle id only at startup from the siding.

**25 s rewrites and phase drift** (A5): revalidate, never cache raw times across rewrites.
`/time set` and sleeping shift every frequency-depot slot.

**Deviation wrap.** For frequency depots `R = 1 200 000 · repeatDepartures`, so the PIDS
deviation wraps at ±10 min (repeat 1): a driver 11 min late shows as 9 min early. MTR's auto
trains have the same limit. Our HUD should use the unwrapped `D`. Possible mitigation: clamp the
injected `D` to `R/2 − 1 s`.

**Continuous movement / repeat-infinitely:** exclude. They have no per-trip slots, and Thomas
§6.2 says lines return.

**Addon engines.**
- `DepotGroupEngine`: the delay is baked into `d_N` through the `addDeparture` redirect, so
  nothing to do. A delay change moves raw times, which the revalidation handles.
- `PlatformGroupEngine` / `StopOverlayEngine` rotate per depot:
  - same depot: identical path;
  - group sibling: the platform at a stop may differ, so map stops by order, show M's platform on
    the HUD, and note that PIDS (option 1) show A's platform.
- `DwellOverrideEngine`: baked into the shared depot path and approach paths, so it is consistent.
- `HoldRuleEngine`:
  - It counts arrival rows at watched platforms, so the injected predicted row makes holds wait
    for the manual train, as intended.
  - Its `startUp` HEAD-cancel and `GapFillerEngine`/`DoorObstructionEngine` also act on the manual
    train. That is existing behaviour; their delays simply show up in `D`.
- `AnalyticsRecorder` logs the manual run with `departureIndex −1`. If wanted, add the slot id.

**Client matching.** `routeId + departureIndex` is not unique across sidings: index N exists on
every siding. This collision already exists today; option 1 does not make it worse.

**Multiple players.** Claim insert is on the simulator thread, so it is atomic. Enforce one claim
per slot and one per manual siding. Release on driver disconnect only while PENDING; RUNNING
continues (timeout → ATO).

**Restarts.**
- The manual train persists (siding `vehicles` are serialized). Indices can shift (`setGameTime`
  phase). If offline for more than 1 h, `Simulator.tick` clears all vehicles.
- Do not persist claims. On startup the slot reverts to MTR. A driverless train goes ATO after
  the timeout and the row simply follows MTR.

**Multiple dimensions:** one engine per `Simulator`.

---

## Open questions (for Thomas or the build)

1. No-show default: plain cancel (row vanishes), or reinstate the auto train late? Grace length?
2. Confirm "timeout keeps the slot" (plan §7). Should H4 make the ATO fallback chase the slot
   (recovery), or just run MTR-native?
3. PIDS beyond ±10 min (R/2 wrap): clamp, or accept MTR's behaviour?
4. Group-depot slots whose baked platforms differ: allow them (PIDS show A's platform), or only
   offer slots whose stop sequence matches by platform?
5. Instant Deploy while a crew is aboard: block it, warn, or ignore (MTR teleports the train)?
6. May a driver claim a slot while the manual train is already out on a run, i.e. claim the next
   one? Proposed: only from the siding.
7. Should unclaimed manual runs keep MTR's native PIDS rows (index −1)? Proposed: yes, status quo.
