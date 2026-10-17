package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.mtr.RouteBullets;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.ClientDoorObstructions;
import com.stationannouncer.client.mtraddon.ClientHoldState;
import com.stationannouncer.client.mtraddon.DrivingHud;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.ArrivalsCacheClient;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.item.ItemDriverKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link CabSnapshot} for the vehicle the player drives or guards,
 * on the client thread at the classic HUD's update rate (never per frame).
 * The path walk, next stop and signal aspects come from {@link DrivingHud}'s
 * helpers, so both HUD styles read the line the same way.
 *
 * <p><b>The run</b> (until timetable slots land, MANUAL_DRIVING_PLAN.md phase 3)
 * comes from MTR's arrivals for the platforms ahead, matched on route +
 * departure index like {@code RailroadRouteData}; a manual-siding train has no
 * departure index and therefore no timetable, which the panel shows as greyed
 * rows. Stops already called at are remembered locally with their actual times.</p>
 */
@Environment(EnvType.CLIENT)
final class CabData {
    private static final int RUN_AHEAD = 6;
    /** The stop-marker row lights up inside this distance of the next stop. */
    private static final double MARKER_RANGE = 300;

    private static long arrivalsFetchedAt;
    private static final LongArrayList ARRIVAL_PLATFORMS = new LongArrayList();
    private static ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();

    /** Calls this session made: vehicle id → visits, oldest first. */
    private static long visitsVehicleId;
    private static final List<Visit> VISITS = new ArrayList<>();
    /** Scheduled arrival last seen for each platform ahead (for the visit row once we are past it). */
    private static final Map<Long, Long> LAST_SCHEDULED = new HashMap<>();

    private record Visit(long platformId, String name, long scheduled, long actual) {
    }

    private CabData() {
    }

    static void clear() {
        CabScore.clear();
        arrivalsFetchedAt = 0;
        ARRIVAL_PLATFORMS.clear();
        arrivals = new ObjectArrayList<>();
        visitsVehicleId = 0;
        VISITS.clear();
        LAST_SCHEDULED.clear();
    }

    @Nullable
    static CabSnapshot compute(long wallNow) {
        VehicleExtension vehicle = DrivingHud.findDrivenVehicle();
        if (vehicle == null) {
            return null;
        }
        double railProgress = DrivingHud.readRailProgress(vehicle);
        if (Double.isNaN(railProgress)) {
            return null;
        }
        VehicleExtraData extra = vehicle.vehicleExtraData;
        ObjectImmutableList<PathData> path = extra.immutablePath;
        if (path.isEmpty()) {
            return null;
        }
        AddonClientConfig config = AddonClientConfig.get();
        CabSnapshot snap = new CabSnapshot();
        snap.now = wallNow + ArrivalsCacheClient.INSTANCE.getMillisOffset();
        ItemDriverKey key = VehicleRidingMovement.getValidHoldingKey(extra.getDepotId());
        snap.guardView = key != null && !key.canDrive;

        int headIndex = Utilities.getIndexFromConditionalList(path, railProgress);
        double speed = vehicle.getSpeed();
        double deceleration = Math.max(1E-9, extra.getDeceleration());
        int lookahead = MathHelper.clamp(config.hudLookaheadMeters, 100, 20_000);
        double selfPadding = 0.5 * speed * speed / deceleration + vehicle.getTransportMode().stoppingSpace;
        List<DrivingHud.WalkEntry> walk = DrivingHud.buildWalk(extra, path, headIndex, railProgress, lookahead);

        // ---- destination
        snap.routeColor = extra.getThisRouteColor() | 0xFF000000;
        String number = DrivingHud.firstLang(extra.getThisRouteNumber());
        snap.routeLabel = !number.isEmpty() ? number : RouteBullets.label(extra.getThisRouteName());
        snap.destination = DrivingHud.firstLang(extra.getThisRouteDestination());
        boolean inService = extra.getThisRouteId() != 0;
        if (!inService) {
            snap.routeColor = 0xFF5A636C;
            snap.routeLabel = "-";
            snap.destination = net.minecraft.text.Text.translatable("gui.station_announcer.cab.not_in_service").getString();
        }

        long sidingId = extra.getSidingId();
        long stoppedAt = stoppedPlatform(path, headIndex, railProgress, speed, sidingId);
        snap.atPlatform = stoppedAt != 0;
        DrivingHud.NextStop next = DrivingHud.findNextStop(extra, path, headIndex, railProgress, walk);
        List<long[]> stopsAhead = stopsAhead(path, headIndex, railProgress, walk, sidingId, next);
        if (snap.atPlatform) {
            snap.nextName = DrivingHud.stopName(extra, stoppedAt);
            if (next != null) {
                snap.afterName = DrivingHud.stopName(extra, next.platformId());
                snap.afterDistance = next.distance();
            }
        } else if (next != null) {
            snap.nextName = DrivingHud.stopName(extra, next.platformId());
            snap.nextDistance = next.distance();
            if (stopsAhead.size() > 1) {
                snap.afterName = DrivingHud.stopName(extra, stopsAhead.get(1)[0]);
                snap.afterDistance = Double.longBitsToDouble(stopsAhead.get(1)[1]);
            }
        }
        if (inService && snap.destination.isEmpty() && !stopsAhead.isEmpty()) {
            snap.destination = DrivingHud.stopName(extra, stopsAhead.get(stopsAhead.size() - 1)[0]);
        }

        // ---- signal + limits
        snap.speedKph = speed * 3600;
        snap.limitKph = vehicle.getSpeedLimitKilometersPerHour();
        MinecraftClientData data = MinecraftClientData.getInstance();
        boolean signalFound = false;
        for (DrivingHud.WalkEntry entry : walk) {
            if (entry.distance() > lookahead) {
                break;
            }
            PathData pathData = entry.pathData();
            long limit = pathData.getSpeedLimitKilometersPerHour();
            if (snap.nextLimitKph == 0 && limit > 0 && limit != snap.limitKph) {
                snap.nextLimitKph = (int) limit;
                snap.nextLimitDistance = Math.max(0, entry.distance());
            }
            IntAVLTreeSet colors = pathData.getSignalColors();
            if (!signalFound && !colors.isEmpty()) {
                signalFound = true;
                int aspect = DrivingHud.aspectOf(data, pathData, colors, entry.distance() > selfPadding);
                snap.aspect = aspect == DrivingHud.ASPECT_OCCUPIED ? CabSnapshot.ASPECT_DANGER
                        : aspect == DrivingHud.ASPECT_CAUTION ? CabSnapshot.ASPECT_CAUTION : CabSnapshot.ASPECT_CLEAR;
                snap.signalDistance = Math.max(0, entry.distance());
            }
            if (signalFound && snap.nextLimitKph != 0) {
                break;
            }
        }

        // ---- stop marker: MTR's own platform stopping details when it has them
        DoubleObjectImmutablePair<DoubleDoubleImmutablePair> stopping = vehicle.getPlatformStoppingDetails();
        if (stopping != null && stopping.leftDouble() < -STALE_OVERRUN && speed > 1E-4) {
            // MTR keeps the details of a platform the train ran through ("−68m" at speed on the
            // rig): once well past it and moving, the marker belongs to the next stop.
            stopping = null;
        }
        if (stopping != null) {
            snap.markerActive = true;
            snap.markerDistance = stopping.leftDouble();
        } else if (snap.atPlatform) {
            snap.markerActive = true;
            snap.markerDistance = 0;
        } else if (next != null && next.distance() <= MARKER_RANGE) {
            snap.markerActive = true;
            snap.markerDistance = next.distance();
        }

        snap.power = extra.getPowerLevel();
        snap.manual = extra.getIsCurrentlyManual();
        snap.doorsOpen = vehicle.persistentVehicleData.getAdjustedDoorMultiplier(extra) > 0
                || vehicle.persistentVehicleData.getDoorValue() > 0;
        snap.obstructed = ClientDoorObstructions.isObstructed(vehicle.getId());
        snap.hold = stoppedAt != 0 && ClientHoldState.isHeld(stoppedAt);

        com.stationannouncer.mtraddon.crew.CabFeed feed = CabFeedClient.forVehicle(vehicle.getId(), wallNow);
        if (feed != null) {
            applyFeed(snap, feed, wallNow);
        }
        CabScore.update(snap, feed == null ? null : feed.slot());
        if (feed != null && feed.slot() != null) {
            runFromSlot(snap, feed.slot(), feed.stops());
        } else {
            buildRun(snap, vehicle, extra, stoppedAt, stopsAhead, wallNow);
            if (feed != null && snap.run.isEmpty()) {
                // No timetable: still list the stops ahead (names only, times greyed).
                for (int i = 0; i < feed.stops().size() && i < RUN_AHEAD; i++) {
                    snap.run.add(new CabSnapshot.RunRow(feed.stops().get(i).name(), 0, 0, false, i == 0));
                }
            }
        }
        return snap;
    }

    /**
     * The server sees the whole path (the client only gets it up to the stopping point, which
     * for a manual train is wherever it stands), so its stops, next limit and signal win. Its
     * distances are up to half a second old; moving trains are corrected by the distance run since.
     */
    private static void applyFeed(CabSnapshot snap, com.stationannouncer.mtraddon.crew.CabFeed feed, long wallNow) {
        double run = snap.speedKph / 3.6 * Math.min(1000, CabFeedClient.ageMillis(wallNow)) / 1000.0;
        var stops = feed.stops();
        snap.atPlatform = feed.atPlatform();
        int nextIndex = feed.atPlatform() ? 1 : 0;
        if (feed.atPlatform() && !stops.isEmpty()) {
            snap.nextName = stops.get(0).name();
        } else if (!stops.isEmpty()) {
            snap.nextName = stops.get(0).name();
            snap.nextDistance = Math.max(0, stops.get(0).distance() - run);
        }
        int afterIndex = feed.atPlatform() ? 1 : 1;
        if (stops.size() > afterIndex) {
            snap.afterName = stops.get(afterIndex).name();
            snap.afterDistance = Math.max(0, stops.get(afterIndex).distance() - run);
        } else {
            snap.afterName = "";
            snap.afterDistance = -1;
        }
        if (feed.limitKph() > 0 && snap.limitKph <= 0) {
            snap.limitKph = feed.limitKph();
        }
        snap.nextLimitKph = feed.nextLimitKph();
        snap.nextLimitDistance = Math.max(0, feed.nextLimitDistance() - run);
        snap.aspect = feed.aspect();
        snap.signalDistance = Math.max(0, feed.signalDistance() - run);
        if (!snap.markerActive && !feed.atPlatform() && stops.size() > nextIndex - (feed.atPlatform() ? 1 : 0)
                && !stops.isEmpty() && stops.get(0).distance() - run <= MARKER_RANGE) {
            snap.markerActive = true;
            snap.markerDistance = Math.max(0, stops.get(0).distance() - run);
        }
        if (feed.atPlatform() && !stops.isEmpty()) {
            // Standing: the server's offset from the stopping mark (+ short, − overrun) — the
            // stop marker turns red on an overrun and the score judges the stop by it.
            snap.markerActive = true;
            snap.markerDistance = stops.get(0).distance();
        }
    }

    /** The run panel from the claimed timetable slot (scheduled vs actual/expected for every stop). */
    /** Metres past a platform's stopping mark after which MTR's stopping details are stale (ran through). */
    private static final double STALE_OVERRUN = 10;

    private static void runFromSlot(CabSnapshot snap, com.stationannouncer.mtraddon.crew.CabFeed.Slot slot,
                                    List<com.stationannouncer.mtraddon.crew.CabFeed.Stop> stops) {
        snap.hasTimetable = true;
        snap.runLabel = slot.label();
        snap.deviationMillis = slot.deviation();
        snap.deviationKnown = true;
        snap.slotPending = slot.state() == com.stationannouncer.mtraddon.crew.CabFeed.SLOT_PENDING;
        snap.yardTime = slot.yardTime();
        if (snap.routeLabel.equals("-") && !slot.rows().isEmpty()) {
            // Still running out of the yard ("not in service" to MTR): show the service we took.
            snap.destination = !slot.destination().isEmpty() ? slot.destination()
                    : slot.rows().get(slot.rows().size() - 1).name();
            snap.routeColor = slot.routeColor() | 0xFF000000;
            snap.routeLabel = !slot.routeNumber().isEmpty() ? slot.routeNumber() : RouteBullets.label(slot.routeName());
        }
        var rows = slot.rows();
        if (snap.nextName.isEmpty() && !snap.atPlatform && !rows.isEmpty()) {
            // Still in the siding: MTR has no path ahead yet, the slot knows the first stop (no distance).
            snap.nextName = rows.get(Math.min(Math.max(0, slot.currentRow()), rows.size() - 1)).name();
            snap.nextDistance = -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            boolean past = row.actualDeparture() != 0 || i < slot.currentRow();
            boolean current = i == slot.currentRow() && !past;
            boolean standing = current && row.actualArrival() > 0;
            long scheduled = standing ? row.scheduledDeparture() : row.scheduledArrival();
            long expected;
            if (past) {
                // Skipped stops carry -1: no actual time ("--").
                expected = row.actualDeparture() > 0 ? row.actualDeparture() : Math.max(0, row.actualArrival());
                scheduled = row.actualDeparture() > 0 ? row.scheduledDeparture() : row.scheduledArrival();
            } else if (standing) {
                expected = Math.max(row.scheduledDeparture(), snap.now);
            } else {
                expected = row.scheduledArrival() + slot.deviation();
            }
            snap.run.add(new CabSnapshot.RunRow(row.name(), scheduled, expected, past, current));
        }
        if (slot.departAt() > 0) {
            snap.departAt = slot.departAt();
            snap.dwellMillis = slot.dwellMillis();
        }
    }

    /** The platform the vehicle stands at (stopped exactly at its end), else 0 — the server engines' test. */
    private static long stoppedPlatform(ObjectImmutableList<PathData> path, int headIndex, double railProgress,
                                        double speed, long sidingId) {
        if (speed > 1E-4 || headIndex <= 0 || headIndex >= path.size()) {
            return 0;
        }
        if (Math.abs(railProgress - path.get(headIndex).getStartDistance()) > 0.01) {
            return 0;
        }
        PathData platform = path.get(headIndex - 1);
        return DrivingHud.isPlatformDwell(platform, sidingId) ? platform.getSavedRailBaseId() : 0;
    }

    /** Up to {@link #RUN_AHEAD} platform stops ahead as {platformId, distance bits}. */
    private static List<long[]> stopsAhead(ObjectImmutableList<PathData> path, int headIndex, double railProgress,
                                           List<DrivingHud.WalkEntry> walk, long sidingId,
                                           @Nullable DrivingHud.NextStop next) {
        List<long[]> stops = new ArrayList<>();
        if (next != null) {
            stops.add(new long[]{next.platformId(), Double.doubleToLongBits(next.distance())});
        }
        for (DrivingHud.WalkEntry entry : walk) {
            if (stops.size() >= RUN_AHEAD) {
                break;
            }
            PathData pathData = entry.pathData();
            if (DrivingHud.isPlatformDwell(pathData, sidingId)
                    && (stops.isEmpty() || stops.get(stops.size() - 1)[0] != pathData.getSavedRailBaseId())) {
                double length = pathData.getEndDistance() - pathData.getStartDistance();
                if (next == null || pathData.getSavedRailBaseId() != next.platformId() || stops.size() > 1) {
                    stops.add(new long[]{pathData.getSavedRailBaseId(), Double.doubleToLongBits(entry.distance() + length)});
                }
            }
        }
        return stops;
    }

    private static void buildRun(CabSnapshot snap, VehicleExtension vehicle, VehicleExtraData extra, long stoppedAt,
                                 List<long[]> stopsAhead, long wallNow) {
        if (visitsVehicleId != vehicle.getId()) {
            visitsVehicleId = vehicle.getId();
            VISITS.clear();
            LAST_SCHEDULED.clear();
        }
        long departureIndex = vehicle.getDepartureIndex();
        snap.hasTimetable = departureIndex >= 0;

        // Platforms whose times we want: the one we stand at plus the ones ahead.
        LongArrayList wanted = new LongArrayList();
        if (stoppedAt != 0) {
            wanted.add(stoppedAt);
        }
        for (long[] stop : stopsAhead) {
            if (!wanted.contains(stop[0])) {
                wanted.add(stop[0]);
            }
        }
        Map<Long, ArrivalResponse> matched = new HashMap<>();
        if (snap.hasTimetable && !wanted.isEmpty()) {
            long cache = Math.max(250, AddonClientConfig.get().hudArrivalsCacheMillis);
            if (!wanted.equals(ARRIVAL_PLATFORMS) || wallNow - arrivalsFetchedAt >= cache) {
                ARRIVAL_PLATFORMS.clear();
                ARRIVAL_PLATFORMS.addAll(wanted);
                arrivalsFetchedAt = wallNow;
                arrivals = ArrivalsCacheClient.INSTANCE.requestArrivals(ARRIVAL_PLATFORMS);
            }
            long thisRoute = extra.getThisRouteId();
            long nextRoute = extra.getNextRouteId();
            for (ArrivalResponse arrival : arrivals) {
                if (arrival.getDepartureIndex() != departureIndex
                        || (arrival.getRouteId() != thisRoute && arrival.getRouteId() != nextRoute)) {
                    continue;
                }
                ArrivalResponse previous = matched.get(arrival.getPlatformId());
                if (previous == null || arrival.getArrival() < previous.getArrival()) {
                    matched.put(arrival.getPlatformId(), arrival);
                }
            }
        }

        // Remember the call we are making now (once per platform visit).
        if (stoppedAt != 0 && (VISITS.isEmpty() || VISITS.get(VISITS.size() - 1).platformId() != stoppedAt)) {
            Long scheduled = LAST_SCHEDULED.get(stoppedAt);
            VISITS.add(new Visit(stoppedAt, DrivingHud.stopName(extra, stoppedAt), scheduled == null ? 0 : scheduled, snap.now));
            if (VISITS.size() > 8) {
                VISITS.remove(0);
            }
        }

        // Past calls (excluding the one we are making).
        for (Visit visit : VISITS) {
            if (visit.platformId() != stoppedAt) {
                snap.run.add(new CabSnapshot.RunRow(visit.name(), visit.scheduled(), visit.actual(), true, false));
            }
        }
        if (stoppedAt != 0) {
            ArrivalResponse here = matched.get(stoppedAt);
            long scheduledDeparture = here == null ? 0 : here.getDeparture() - here.getDeviation();
            long expectedDeparture = here == null ? 0 : here.getDeparture();
            snap.run.add(new CabSnapshot.RunRow(DrivingHud.stopName(extra, stoppedAt), scheduledDeparture,
                    expectedDeparture, false, true));
            if (here != null) {
                snap.departAt = expectedDeparture;
                snap.dwellMillis = Math.max(1000, here.getDeparture() - here.getArrival());
                snap.deviationMillis = here.getDeviation();
                snap.deviationKnown = true;
            }
        }
        boolean first = stoppedAt == 0;
        for (long[] stop : stopsAhead) {
            if (stop[0] == stoppedAt) {
                continue;
            }
            ArrivalResponse arrival = matched.get(stop[0]);
            long scheduled = arrival == null ? 0 : arrival.getArrival() - arrival.getDeviation();
            long expected = arrival == null ? 0 : arrival.getArrival();
            if (arrival != null) {
                LAST_SCHEDULED.put(stop[0], scheduled);
                if (!snap.deviationKnown) {
                    snap.deviationMillis = arrival.getDeviation();
                    snap.deviationKnown = true;
                }
            }
            snap.run.add(new CabSnapshot.RunRow(DrivingHud.stopName(extra, stop[0]), scheduled, expected, false, first));
            first = false;
        }
        snap.runLabel = snap.hasTimetable ? "" : "manual";
    }
}
