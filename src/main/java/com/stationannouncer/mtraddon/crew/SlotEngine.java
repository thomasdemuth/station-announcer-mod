package com.stationannouncer.mtraddon.crew;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.SidingSlotAccessor;
import com.stationannouncer.mixin.SidingStopTimesAccessor;
import com.stationannouncer.mixin.SidingVehiclesAccessor;
import com.stationannouncer.mixin.VehicleSchemaAccessor;
import com.stationannouncer.mtraddon.DepotGroup;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Route;
import org.mtr.core.data.Siding;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Trip;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timetable slot takeover (MANUAL_DRIVING_PLAN.md phase 3; the MTR mechanics and every hook
 * are worked out in SLOT_TAKEOVER_RESEARCH.md — read its §0 first).
 *
 * <p>A driver in a MANUAL siding's train picks one upcoming departure of an AUTOMATIC siding
 * in the same depot, in any depot running exactly the same routes, or in a depot of the same
 * depot group that runs the same routes — always stopping at the same platforms (Thomas: identical
 * routes for now). From then on:</p>
 * <ul>
 *   <li><b>H1</b> — that one occurrence of the automatic train is not deployed
 *       ({@link #suppressDeployment}, a redirect on the only {@code startUp} call in
 *       {@code Siding.simulateTrain});</li>
 *   <li><b>H2</b> — at the TAIL of every {@code simulateTrain}: on the manual siding the
 *       tracker updates the run (actual times, live lateness {@code D}); on the automatic siding
 *       {@code vehicleTimesAlongRoute[N] = floorMod(now − d_N − D, R)} makes MTR's own arrivals
 *       list the slot as real-time with lateness D, so every board shows the manual train in the
 *       slot, late when the driver is late (Thomas);</li>
 *   <li><b>H3</b> — the manual siding's own rows are hidden while it runs a slot (no double row);</li>
 *   <li><b>H4</b> — the manual train's yard start is reported to MTR as the slot's virtual yard
 *       time, so MTR's deviation is slot-relative and the automatic fallback after the
 *       manual→automatic timeout chases the slot (Thomas: "yes, chase the slot").</li>
 * </ul>
 *
 * <p><b>Policies</b> (Thomas, 2026-10-05): no-show → after {@link #NO_SHOW_GRACE_MILLIS} past the
 * yard time the claim is released and the held-back automatic train runs the slot late; timeout
 * mid-run → the slot is kept; boards clamp lateness at {@link #BOARD_CLAMP_MILLIS} below MTR's
 * ±R/2 wrap; slots are picked from the siding only; claims are runtime-only (not persisted).</p>
 *
 * <p><b>Threads.</b> Each claim is created, advanced and read on the simulator thread that owns
 * its sidings ({@code simulator.run} from the server thread for picks); the map itself is
 * concurrent so lookups from any simulator are safe. Times are the SIMULATOR clock
 * ({@code data.getCurrentMillis()}, the base of MTR's departures and arrivals). Instant Deploy's
 * day replay calls {@code simulateTrain} with {@code vehiclePositions == null}: the tracker and the
 * injection skip it, and the occurrence window keeps H1 from touching replayed occurrences.</p>
 */
public final class SlotEngine {
    public static final long NO_SHOW_GRACE_MILLIS = 120_000;
    /** Running through a stop below this speed is an overrun (tried to stop), above it a skip. */
    static final double SLOW_OVERRUN_KPH = 30;
    /** Boards: lateness injected at most this far below half the repeat interval (MTR wraps at ±R/2). */
    public static final long BOARD_CLAMP_MILLIS = 60_000;
    /** Slots closer than this to their yard time are not offered (the deploy must not race the claim). */
    private static final long MIN_LEAD_MILLIS = 20_000;
    private static final long DAY = 86_400_000L;
    public static final int MAX_OPTIONS = 12;

    /** manual siding id → claim. */
    static final ConcurrentHashMap<Long, Claim> CLAIMS = new ConcurrentHashMap<>();

    enum State { PENDING, RUNNING, REINSTATE, ENDED }

    /** One stop of the slot, from the automatic siding's stop times (offsets after ITS yard departure). */
    record RowDef(String name, long platformId, long autoArrival, long autoDeparture, long manualArrival,
                  long manualDeparture) {
    }

    static final class Claim {
        final long manualSidingId;
        final UUID driver;
        final long autoSidingId;
        final int index;
        final String label;
        /** The service's line for the cab bullet (number, else name) and its colour. */
        final String routeName;
        final String routeNumber;
        final int routeColor;
        final String destination;
        final List<RowDef> rows;
        /** The manual siding's stop times line up with the slot's by order (same baked sequence). */
        final boolean manualTimesMatch;
        /** Absolute yard time of the slot's occurrence on the automatic siding, and the manual train's. */
        long slotYard;
        long manualYard;
        State state = State.PENDING;
        long vehicleId;
        long yardDeparted;
        final long[] arrived;
        final long[] departed;
        int nextRow;
        long deviation;
        long endedAt;
        /** Last platform the train stood at that matched no later row (logged once). */
        long unmatchedPlatform;

        Claim(long manualSidingId, UUID driver, long autoSidingId, int index, String label, String routeName,
              String routeNumber, int routeColor, String destination, List<RowDef> rows,
              boolean manualTimesMatch, long slotYard, long manualYard) {
            this.manualSidingId = manualSidingId;
            this.driver = driver;
            this.autoSidingId = autoSidingId;
            this.index = index;
            this.label = label;
            this.routeName = routeName;
            this.routeNumber = routeNumber;
            this.routeColor = routeColor;
            this.destination = destination;
            this.rows = rows;
            this.manualTimesMatch = manualTimesMatch;
            this.slotYard = slotYard;
            this.manualYard = manualYard;
            this.arrived = new long[rows.size()];
            this.departed = new long[rows.size()];
        }

        long scheduledArrival(int row) {
            return slotYard + rows.get(row).autoArrival();
        }

        long scheduledDeparture(int row) {
            return slotYard + rows.get(row).autoDeparture();
        }

        boolean live() {
            return state == State.PENDING || state == State.RUNNING;
        }
    }

    /** One slot the picker offers. Times are simulator-clock millis. */
    public record Option(long autoSidingId, int index, long slotYard, long manualYard, String firstStop,
                         long firstDeparture, String routeName, String routeNumber, int routeColor,
                         String destination, boolean claimedByYou) {
    }

    private SlotEngine() {
    }

    // ===================================================================== picker

    /**
     * The next slots a manual siding's driver may take: same depot plus depot-group depots with
     * identical routes and the same stop platforms. Simulator thread.
     */
    static List<Option> listOptions(Simulator simulator, long manualSidingId, Collection<DepotGroup> groups, UUID player) {
        List<Option> options = new ArrayList<>();
        Siding manual = simulator.sidingIdMap.get(manualSidingId);
        if (manual == null || !manual.getIsManual() || manual.area == null || manual.area.getRepeatInfinitely()
                || TransportMode.values()[manual.getTransportModeOrdinal()].continuousMovement) {
            return options;
        }
        Depot manualDepot = manual.area;
        List<Trip.StopTime> manualTimes = stopTimes(manual);
        long now = simulator.getCurrentMillis();
        Claim own = CLAIMS.get(manualSidingId);
        for (Depot depot : candidateDepots(simulator, manualDepot, groups)) {
            for (Siding auto : depot.savedRails) {
                if (auto.getIsManual()) {
                    continue;
                }
                List<Trip.StopTime> autoTimes = stopTimes(auto);
                // Every candidate depot runs exactly the manual depot's route list (candidateDepots),
                // i.e. the same platforms in the same order — Thomas's "runs that stop at the same
                // platforms". The BAKED stop sequences can still differ (a siding whose path to the
                // main route crosses a route platform records an extra zero-dwell stop), so they are
                // not compared here: claim() lines the rows up by order only when they match.
                if (autoTimes.isEmpty()) {
                    continue;
                }
                LongArrayList departures = ((SidingSlotAccessor) (Object) auto).stationAnnouncer$getDepartures();
                long repeat = ((SidingSlotAccessor) (Object) auto).stationAnnouncer$getRepeatInterval(DAY);
                if (departures.isEmpty() || repeat <= 0) {
                    continue;
                }
                Trip.StopTime first = autoTimes.get(0);
                long manualFirst = manualTimes.isEmpty() ? first.startTime : manualTimes.get(0).startTime;
                for (int i = 0; i < departures.size(); i++) {
                    long raw = departures.getLong(i);
                    long occurrence = raw + Math.floorDiv(now + MIN_LEAD_MILLIS - raw + repeat - 1, repeat) * repeat;
                    boolean mine = own != null && own.live() && own.autoSidingId == auto.getId() && own.index == i;
                    if (!mine && claimed(auto.getId(), i, occurrence, repeat)) {
                        continue;
                    }
                    Route route = first.trip.route;
                    options.add(new Option(auto.getId(), i, occurrence, occurrence + first.startTime - manualFirst,
                            FeedBuilder.stationName(simulator, first.platformId), occurrence + first.endTime,
                            firstLang(route.getName()), firstLang(route.getRouteNumber()), route.getColor(),
                            destinationOf(simulator, autoTimes), mine));
                }
            }
        }
        if (options.isEmpty()) {
            // Say why, once per request: the usual cause is a depot with no automatic sidings
            // and no depot group linking it to one.
            StringBuilder why = new StringBuilder();
            for (Depot depot : candidateDepots(simulator, manualDepot, groups)) {
                int automatic = 0;
                int withDepartures = 0;
                for (Siding siding : depot.savedRails) {
                    if (!siding.getIsManual()) {
                        automatic++;
                        if (!((SidingSlotAccessor) (Object) siding).stationAnnouncer$getDepartures().isEmpty()) {
                            withDepartures++;
                        }
                    }
                }
                why.append(String.format(" [%s: %d automatic sidings, %d with departures, stop times %s vs manual %d]",
                        firstLang(depot.getName()), automatic, withDepartures, stopCounts(depot), manualTimes.size()));
            }
            // Depots elsewhere that share routes with this one (candidates for a depot group).
            LongArrayList routes = manualDepot.getRouteIds();
            for (Depot depot : simulator.depots) {
                if (depot == manualDepot) {
                    continue;
                }
                boolean overlap = false;
                for (int i = 0; i < routes.size(); i++) {
                    overlap |= depot.getRouteIds().contains(routes.getLong(i));
                }
                if (overlap) {
                    why.append(String.format(" | shares routes: %s (id %d, %s)", firstLang(depot.getName()), depot.getId(),
                            depot.getRouteIds().equals(routes) ? "identical routes" : "different route list"));
                }
            }
            StationAnnouncer.LOGGER.info("[slot] no slots for manual siding {}:{}", manualSidingId, why);
        }
        options.sort(Comparator.comparingLong(Option::firstDeparture));
        return options.size() > MAX_OPTIONS ? new ArrayList<>(options.subList(0, MAX_OPTIONS)) : options;
    }

    /**
     * Claims a slot for a driver standing in the manual siding's train while it is still in its
     * siding. Returns null on success, else a CrewProtocol message code. Simulator thread.
     */
    static Byte claim(Simulator simulator, long manualSidingId, long autoSidingId, int index, long wantedYard,
                      UUID driver, Collection<DepotGroup> groups) {
        Siding manual = simulator.sidingIdMap.get(manualSidingId);
        Siding auto = simulator.sidingIdMap.get(autoSidingId);
        if (manual == null || auto == null || !manual.getIsManual() || auto.getIsManual() || manual.area == null
                || auto.area == null || !candidateDepots(simulator, manual.area, groups).contains(auto.area)) {
            return CrewProtocol.MSG_SLOT_GONE;
        }
        for (Vehicle vehicle : ((SidingVehiclesAccessor) (Object) manual).stationAnnouncer$getVehicles()) {
            if (vehicle.getIsOnRoute()) {
                return CrewProtocol.MSG_SLOT_FROM_SIDING;
            }
        }
        LongArrayList departures = ((SidingSlotAccessor) (Object) auto).stationAnnouncer$getDepartures();
        long repeat = ((SidingSlotAccessor) (Object) auto).stationAnnouncer$getRepeatInterval(DAY);
        if (index < 0 || index >= departures.size() || repeat <= 0) {
            return CrewProtocol.MSG_SLOT_GONE;
        }
        long slotYard = nearestOccurrence(departures.getLong(index), wantedYard, repeat);
        long now = simulator.getCurrentMillis();
        if (slotYard - now < MIN_LEAD_MILLIS / 2) {
            return CrewProtocol.MSG_SLOT_GONE;
        }
        Claim existing = CLAIMS.get(manualSidingId);
        if (existing != null && existing.live() && !existing.driver.equals(driver)) {
            return CrewProtocol.MSG_SLOT_TAKEN;
        }
        if (claimed(autoSidingId, index, slotYard, repeat)
                && !(existing != null && existing.autoSidingId == autoSidingId && existing.index == index)) {
            return CrewProtocol.MSG_SLOT_TAKEN;
        }
        List<Trip.StopTime> autoTimes = stopTimes(auto);
        List<Trip.StopTime> manualTimes = stopTimes(manual);
        if (autoTimes.isEmpty()) {
            return CrewProtocol.MSG_SLOT_GONE;
        }
        if (manualTimes.isEmpty()) {
            // No generated path (seen on the rig: the depot needed "Refresh Path"): MTR would send the
            // train straight back into its siding the moment it left.
            StationAnnouncer.LOGGER.info("[slot] refused a claim on manual siding {}: its depot path is not generated", manualSidingId);
            return CrewProtocol.MSG_SLOT_NO_PATH;
        }
        boolean match = manualTimes.size() == autoTimes.size();
        List<RowDef> rows = new ArrayList<>(autoTimes.size());
        for (int i = 0; i < autoTimes.size(); i++) {
            Trip.StopTime a = autoTimes.get(i);
            Trip.StopTime m = match ? manualTimes.get(i) : null;
            rows.add(new RowDef(FeedBuilder.stationName(simulator, a.platformId), m != null ? m.platformId : a.platformId,
                    a.startTime, a.endTime, m != null ? m.startTime : 0, m != null ? m.endTime : 0));
        }
        long manualFirst = match ? manualTimes.get(0).startTime : autoTimes.get(0).startTime;
        long manualYard = slotYard + autoTimes.get(0).startTime - manualFirst;
        Route route = autoTimes.get(0).trip.route;
        String number = firstLang(route.getRouteNumber());
        String destination = destinationOf(simulator, autoTimes);
        String label = clock(simulator, slotYard + autoTimes.get(0).endTime) + " · "
                + (number.isEmpty() ? firstLang(route.getName()) : number) + " to " + destination;
        CLAIMS.put(manualSidingId, new Claim(manualSidingId, driver, autoSidingId, index, label,
                firstLang(route.getName()), number, route.getColor(), destination, rows, match, slotYard, manualYard));
        StationAnnouncer.LOGGER.info("[slot] {} claimed {} (auto siding {}, index {}, yard {} / manual yard {}, "
                        + "manualTimesMatch={}, stops auto {} / manual {})",
                driver, label, autoSidingId, index, slotYard, manualYard, match, autoTimes.size(), manualTimes.size());
        return null;
    }

    /** Releases a not-yet-started claim (the driver changed their mind). Simulator thread. */
    static boolean release(long manualSidingId, UUID driver) {
        Claim claim = CLAIMS.get(manualSidingId);
        if (claim == null || !claim.driver.equals(driver) || claim.state != State.PENDING) {
            return false;
        }
        claim.state = State.REINSTATE; // the automatic train takes its departure back (late if past)
        return true;
    }

    // ====================================================================== hooks

    /** H1: true = skip this deployment (the slot belongs to a manual run). Simulator thread. */
    public static boolean suppressDeployment(Siding siding, long departureIndex, Data data) {
        if (CLAIMS.isEmpty()) {
            return false;
        }
        long repeat = ((SidingSlotAccessor) (Object) siding).stationAnnouncer$getRepeatInterval(DAY);
        long now = data.getCurrentMillis();
        for (Claim claim : CLAIMS.values()) {
            if (claim.autoSidingId == siding.getId() && claim.index == departureIndex
                    && (claim.live() || claim.state == State.ENDED)
                    && Math.abs(now - claim.slotYard) < repeat / 2) {
                StationAnnouncer.LOGGER.info("[slot] held back the automatic train for {} ({})", claim.label, claim.state);
                return true;
            }
        }
        return false;
    }

    /** H2: TAIL of {@code Siding.simulateTrain}. Simulator thread; skipped during Instant Deploy's replay. */
    public static void afterSimulateTrain(Siding siding, Data data, boolean replay) {
        if (CLAIMS.isEmpty() || replay) {
            return;
        }
        long id = siding.getId();
        long now = data.getCurrentMillis();
        Claim asManual = CLAIMS.get(id);
        if (asManual != null) {
            track(asManual, siding, data, now);
        }
        for (Claim claim : CLAIMS.values()) {
            if (claim.autoSidingId == id) {
                serveAutoSiding(claim, siding, now);
            }
        }
    }

    /** H3: the manual siding's own board rows are hidden while it runs a slot. */
    public static boolean hideManualRows(Siding siding) {
        if (CLAIMS.isEmpty()) {
            return false;
        }
        Claim claim = CLAIMS.get(siding.getId());
        return claim != null && claim.state == State.RUNNING;
    }

    /**
     * H4: the manual train leaves its siding. Binds the claim to this vehicle and returns the
     * departure time MTR should record (the slot's virtual yard time), else {@code original}.
     */
    public static long manualStart(long vehicleId, long sidingId, long original) {
        if (CLAIMS.isEmpty()) {
            return original;
        }
        Claim claim = CLAIMS.get(sidingId);
        if (claim == null || claim.state != State.PENDING) {
            return original;
        }
        // Not RUNNING yet: startUp is a no-op while the doors' cooldown runs (MTR 4.0.1 — it only
        // closes the doors), and the train would read as "back in the siding" in the same tick.
        // track() promotes the claim once this vehicle is really on its route.
        claim.vehicleId = vehicleId;
        claim.yardDeparted = original;
        return claim.manualYard;
    }

    // ===================================================================== engine

    private static void track(Claim claim, Siding manual, Data data, long now) {
        switch (claim.state) {
            case PENDING -> {
                if (claim.vehicleId != 0 && leftSiding(claim, manual)) {
                    claim.state = State.RUNNING;
                    StationAnnouncer.LOGGER.info("[slot] {} started, {} s after its yard time", claim.label,
                            (claim.yardDeparted - claim.manualYard) / 1000);
                    trackRunning(claim, manual, data, now);
                    return;
                }
                claim.deviation = Math.max(0, now - claim.manualYard);
                if (now > claim.manualYard + NO_SHOW_GRACE_MILLIS) {
                    claim.state = State.REINSTATE;
                    CrewService.NOTICES.add(new CrewService.Notice(claim.driver, CrewProtocol.MSG_SLOT_NO_SHOW));
                    StationAnnouncer.LOGGER.info("[slot] {}: no-show, the automatic train takes it back", claim.label);
                }
            }
            case RUNNING -> trackRunning(claim, manual, data, now);
            case ENDED -> {
                if (now - claim.endedAt > 600_000) {
                    CLAIMS.remove(claim.manualSidingId, claim);
                }
            }
            default -> {
            }
        }
    }

    /** The vehicle that asked to start up (H4) has really left the siding. */
    private static boolean leftSiding(Claim claim, Siding manual) {
        for (Vehicle candidate : ((SidingVehiclesAccessor) (Object) manual).stationAnnouncer$getVehicles()) {
            if (candidate.getId() == claim.vehicleId) {
                return candidate.getIsOnRoute();
            }
        }
        return false;
    }

    private static void trackRunning(Claim claim, Siding manual, Data data, long now) {
        Vehicle vehicle = null;
        for (Vehicle candidate : ((SidingVehiclesAccessor) (Object) manual).stationAnnouncer$getVehicles()) {
            if (candidate.getId() == claim.vehicleId) {
                vehicle = candidate;
            }
        }
        if (vehicle == null || !vehicle.getIsOnRoute()) {
            end(claim, now, vehicle == null ? "train gone" : "back in the siding");
            return;
        }
        VehicleSchemaAccessor schema = (VehicleSchemaAccessor) (Object) vehicle;
        double railProgress = schema.stationAnnouncer$getRailProgress();
        double speed = schema.stationAnnouncer$getSpeed();
        long platform = FeedBuilder.platformAt(vehicle.vehicleExtraData.immutablePath, railProgress, speed, manual.getId());

        // Calls: arrival when standing at the next row's platform (or a later one: skipped stops).
        if (platform != 0) {
            boolean matched = false;
            for (int row = claim.nextRow; row < claim.rows.size(); row++) {
                if (claim.rows.get(row).platformId() == platform) {
                    if (claim.arrived[row] == 0) {
                        claim.arrived[row] = now;
                        StationAnnouncer.LOGGER.info("[slot] {}: row {} {} arrived ({} s late)", claim.label, row,
                                claim.rows.get(row).name(), (now - claim.scheduledArrival(row)) / 1000);
                    }
                    claim.nextRow = row;
                    matched = true;
                    break;
                }
            }
            if (!matched && claim.unmatchedPlatform != platform) {
                claim.unmatchedPlatform = platform; // once per platform: standing somewhere the slot does not call
                StationAnnouncer.LOGGER.info("[slot] {}: standing at platform {} ({}), not a later row (next row {} = {} / {})",
                        claim.label, platform, FeedBuilder.stationName(data, platform), claim.nextRow,
                        claim.nextRow < claim.rows.size() ? claim.rows.get(claim.nextRow).name() : "-",
                        claim.nextRow < claim.rows.size() ? claim.rows.get(claim.nextRow).platformId() : 0);
            }
        } else if (claim.nextRow < claim.rows.size() && claim.arrived[claim.nextRow] != 0
                && claim.departed[claim.nextRow] == 0 && speed > 0) {
            claim.departed[claim.nextRow] = now;
            StationAnnouncer.LOGGER.info("[slot] {}: row {} {} departed ({} s late)", claim.label, claim.nextRow,
                    claim.rows.get(claim.nextRow).name(), (now - claim.scheduledDeparture(claim.nextRow)) / 1000);
            claim.nextRow++;
        } else if (claim.nextRow < claim.rows.size() && claim.arrived[claim.nextRow] == 0
                && passed(vehicle.vehicleExtraData.immutablePath, railProgress, claim.rows.get(claim.nextRow).platformId())) {
            // Past the platform without calling. Slowly (under SLOW_OVERRUN_KPH: the driver was
            // trying to stop and overshot) = an overrun (-2, scored -3 like a stop past the mark —
            // Thomas); at speed = a skip (-1, scored -10).
            boolean slow = speed * 3600 < SLOW_OVERRUN_KPH;
            StationAnnouncer.LOGGER.info("[slot] {}: row {} {} {} (ran through at {} km/h)", claim.label, claim.nextRow,
                    claim.rows.get(claim.nextRow).name(), slow ? "overran" : "skipped", Math.round(speed * 3600));
            claim.arrived[claim.nextRow] = slow ? -2 : -1;
            claim.departed[claim.nextRow] = slow ? -2 : -1;
            claim.nextRow++;
        }
        if (claim.nextRow >= claim.rows.size()) {
            claim.deviation = claim.departed.length == 0 ? claim.deviation
                    : claim.departed[claim.rows.size() - 1] - claim.scheduledDeparture(claim.rows.size() - 1);
            return; // run complete; it ends when the train is back in its siding
        }
        int next = claim.nextRow;
        if (claim.arrived[next] != 0) {
            // Standing at a stop: late by the later of the arrival delay and the overdue departure.
            claim.deviation = Math.max(claim.arrived[next] - claim.scheduledArrival(next), now - claim.scheduledDeparture(next));
            return;
        }
        // Between stops: where the slot's train would be by now, interpolated on the manual
        // siding's own time-along-route between its stop times (exact at both ends).
        double along = manual.getTimeAlongRoute(railProgress);
        int previous = next - 1;
        RowDef to = claim.rows.get(next);
        if (claim.manualTimesMatch && along >= 0) {
            long fromManual = previous >= 0 ? claim.rows.get(previous).manualDeparture() : 0;
            long fromAuto = previous >= 0 ? claim.slotYard + claim.rows.get(previous).autoDeparture() : claim.manualYard;
            long toAuto = claim.slotYard + to.autoArrival();
            double span = Math.max(1, to.manualArrival() - fromManual);
            double fraction = Math.max(0, Math.min(1, (along - fromManual) / span));
            claim.deviation = now - Math.round(fromAuto + fraction * (toAuto - fromAuto));
        } else {
            long last = previous >= 0 && claim.departed[previous] != 0
                    ? claim.departed[previous] - claim.scheduledDeparture(previous) : claim.deviation;
            claim.deviation = Math.max(last, now - claim.scheduledArrival(next));
        }
    }

    private static void serveAutoSiding(Claim claim, Siding auto, long now) {
        SidingSlotAccessor access = (SidingSlotAccessor) (Object) auto;
        LongArrayList departures = access.stationAnnouncer$getDepartures();
        long repeat = access.stationAnnouncer$getRepeatInterval(DAY);
        if (claim.index >= departures.size() || repeat <= 0) {
            end(claim, now, "departures rewritten without this slot");
            return;
        }
        long raw = departures.getLong(claim.index);
        // Departures are rewritten every ~25 s (MTR's set_time); follow the occurrence's drift.
        long refreshed = nearestOccurrence(raw, claim.slotYard, repeat);
        if (refreshed != claim.slotYard) {
            claim.manualYard += refreshed - claim.slotYard;
            claim.slotYard = refreshed;
        }
        if (claim.state == State.REINSTATE) {
            for (Vehicle vehicle : ((SidingVehiclesAccessor) (Object) auto).stationAnnouncer$getVehicles()) {
                if (!vehicle.getIsOnRoute()) {
                    vehicle.startUp(claim.index, raw);
                    if (!vehicle.getIsOnRoute()) {
                        return; // startUp is a no-op while the door cooldown runs: try again next tick
                    }
                    StationAnnouncer.LOGGER.info("[slot] {}: the automatic train runs it, {} s late", claim.label,
                            (now - claim.slotYard) / 1000);
                    end(claim, now, "reinstated");
                    return;
                }
            }
            if (now - claim.slotYard > repeat / 2) {
                end(claim, now, "no idle automatic train to reinstate");
            }
            return;
        }
        if (!claim.live()) {
            return;
        }
        long half = repeat / 2;
        long board = Math.max(-half + BOARD_CLAMP_MILLIS, Math.min(half - BOARD_CLAMP_MILLIS, claim.deviation));
        access.stationAnnouncer$getVehicleTimesAlongRoute().put(claim.index, Math.floorMod(now - raw - board, repeat));
    }

    private static void end(Claim claim, long now, String why) {
        if (claim.state != State.ENDED) {
            claim.state = State.ENDED;
            claim.endedAt = now;
            StationAnnouncer.LOGGER.info("[slot] {} ended: {}", claim.label, why);
        }
    }

    // ======================================================================= view

    /** The slot shown in the cab feed for a manual siding's train, or null. Simulator thread. */
    static CabFeed.Slot view(long manualSidingId, long now) {
        if (CLAIMS.isEmpty()) {
            return null;
        }
        Claim claim = CLAIMS.get(manualSidingId);
        if (claim == null || claim.state == State.REINSTATE || (claim.state == State.ENDED && now - claim.endedAt > 30_000)) {
            return null;
        }
        List<CabFeed.Row> rows = new ArrayList<>(claim.rows.size());
        for (int i = 0; i < claim.rows.size(); i++) {
            rows.add(new CabFeed.Row(claim.rows.get(i).name(), claim.scheduledArrival(i), claim.scheduledDeparture(i),
                    claim.arrived[i], claim.departed[i]));
        }
        byte state = switch (claim.state) {
            case PENDING -> CabFeed.SLOT_PENDING;
            case RUNNING -> CabFeed.SLOT_RUNNING;
            default -> CabFeed.SLOT_ENDED;
        };
        int current = Math.min(claim.nextRow, claim.rows.size() - 1);
        long departAt = 0;
        long dwell = 0;
        if (claim.state == State.RUNNING && claim.nextRow < claim.rows.size() && claim.arrived[claim.nextRow] != 0) {
            departAt = claim.scheduledDeparture(claim.nextRow);
            dwell = Math.max(1000, departAt - claim.scheduledArrival(claim.nextRow));
        }
        return new CabFeed.Slot(claim.label, claim.routeName, claim.routeNumber, claim.routeColor, claim.destination,
                state, claim.manualYard, claim.deviation, rows, current, departAt, dwell);
    }

    static void clearAll() {
        CLAIMS.clear();
    }

    // ==================================================================== helpers

    static List<Trip.StopTime> stopTimes(Siding siding) {
        List<Trip.StopTime> times = new ArrayList<>();
        for (ObjectArraySet<Trip.StopTime> set : ((SidingStopTimesAccessor) (Object) siding)
                .stationAnnouncer$getPlatformTripStopTimes().values()) {
            times.addAll(set);
        }
        times.sort(Comparator.comparingLong(stopTime -> stopTime.startTime));
        return times;
    }

    /** Stop-time counts of a depot's automatic sidings, for the "no slots" log line. */
    private static String stopCounts(Depot depot) {
        StringBuilder counts = new StringBuilder();
        for (Siding siding : depot.savedRails) {
            if (!siding.getIsManual()) {
                counts.append(counts.length() == 0 ? "" : "/").append(stopTimes(siding).size());
            }
        }
        return counts.toString();
    }

    private static List<Depot> candidateDepots(Simulator simulator, Depot manualDepot, Collection<DepotGroup> groups) {
        List<Depot> depots = new ArrayList<>();
        depots.add(manualDepot);
        LongArrayList routes = manualDepot.getRouteIds();
        // Any depot running exactly the same routes is the same line (Thomas's manual depots, e.g.
        // "Line 1 - Driveable" beside "Line 1 - Armory B", are not grouped) — no group needed.
        for (Depot depot : simulator.depots) {
            if (depot != manualDepot && !depot.getRepeatInfinitely() && depot.getRouteIds().equals(routes)
                    && !depots.contains(depot)) {
                depots.add(depot);
            }
        }
        for (DepotGroup group : groups) {
            if (group.indexOf(manualDepot.getId()) < 0) {
                continue;
            }
            for (long depotId : group.depotIds()) {
                Depot depot = simulator.depotIdMap.get(depotId);
                if (depot != null && depot != manualDepot && !depots.contains(depot) && depot.getRouteIds().equals(routes)
                        && !depot.getRepeatInfinitely()) {
                    depots.add(depot);
                }
            }
        }
        return depots;
    }

    private static boolean claimed(long autoSidingId, int index, long occurrence, long repeat) {
        for (Claim claim : CLAIMS.values()) {
            if (claim.autoSidingId == autoSidingId && claim.index == index && claim.live()
                    && Math.abs(claim.slotYard - occurrence) < repeat / 2) {
                return true;
            }
        }
        return false;
    }

    private static long nearestOccurrence(long raw, long near, long repeat) {
        return raw + Math.round((near - raw) / (double) repeat) * repeat;
    }

    /** The train's head is well past this platform's segment (within the last few segments): it ran through. */
    private static boolean passed(List<PathData> path, double railProgress, long platformId) {
        if (path.isEmpty()) {
            return false;
        }
        int head = Math.min(path.size() - 1, Math.max(0, Utilities.getIndexFromConditionalList(path, railProgress)));
        for (int i = head; i >= Math.max(0, head - 6); i--) {
            PathData segment = path.get(i);
            if (segment.getSavedRailBaseId() == platformId && segment.getDwellTime() > 0) {
                return railProgress > segment.getEndDistance() + 10;
            }
        }
        return false;
    }

    private static String destinationOf(Data data, List<Trip.StopTime> times) {
        return times.isEmpty() ? "" : FeedBuilder.stationName(data, times.get(times.size() - 1).platformId);
    }

    private static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }

    /** h:mm in the server's local time (the simulator clock is epoch-based, like MTR's PIDS). */
    private static String clock(Data data, long millis) {
        java.time.LocalTime time = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
        return String.format("%d:%02d", time.getHour(), time.getMinute());
    }

    static Map<Long, Claim> claimsView() {
        return CLAIMS;
    }
}
