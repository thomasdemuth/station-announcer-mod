package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.mixin.SidingVehiclesAccessor;
import com.stationannouncer.mixin.VehicleDeviationAccessor;
import com.stationannouncer.mixin.VehicleSchemaAccessor;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-tick sampling code the {@link DispatchStreamer} enqueues via
 * {@code simulator.run(...)}. Everything in this class runs on the target dimension's
 * SIMULATOR thread and reads only that simulator's own data plus volatile config; the
 * result is plain POJOs (no live MTR references) handed back to the streamer thread,
 * which does all serialization, delta computation and socket writes.
 *
 * <p>Vehicles come from {@code simulator.sidings} → each siding's private
 * {@code vehicles} set ({@link SidingVehiclesAccessor}); protected/private schema
 * fields are read through the accessor mixins. Live signal state comes from each
 * signalled rail's public {@code iterateCurrentlyBlockedSignalColors} /
 * {@code iteratePreBlockedSignalColors} (the keys of the color→vehicleId reservation
 * maps — a read-only view, nothing is reserved by sampling). The signalled-rail list is
 * cached for {@value #SIGNAL_RAILS_REBUILD_MILLIS} ms per dimension because finding it
 * means one pass over every rail.</p>
 */
public final class DispatchSampler {
    private static final long SIGNAL_RAILS_REBUILD_MILLIS = 10_000;

    /**
     * Signalled-rail cache, keyed by dimension string. Written and read only on that
     * dimension's simulator thread; ConcurrentHashMap for safe publication + the
     * cross-thread {@link #clearCaches()} at shutdown. Holding Rail refs here is safe:
     * the map never leaves the simulator side and entries refresh every 10 s.
     */
    private static final ConcurrentHashMap<String, SignalRails> SIGNAL_RAIL_CACHE = new ConcurrentHashMap<>();

    private DispatchSampler() {
    }

    private record SignalRails(long builtAt, Rail[] rails) {
    }

    /** Complete state of one dimension at one sample instant. Plain data, no MTR refs. */
    public static final class DimensionSample {
        public final long sampledAt;
        public final List<VehicleSnapshot> vehicles;
        public final List<SignalSnapshot> signals;

        DimensionSample(long sampledAt, List<VehicleSnapshot> vehicles, List<SignalSnapshot> signals) {
            this.sampledAt = sampledAt;
            this.vehicles = vehicles;
            this.signals = signals;
        }
    }

    /** One active (on-route) vehicle. */
    public static final class VehicleSnapshot {
        // Dynamic (compared for delta emission):
        public final long id;
        public final double x;
        public final double y;
        public final double z;
        public final double speedKmh;
        public final boolean reversed;
        public final String railId;      // canonical hex id of the current rail, or null
        public final double railT;       // progress fraction 0..1 along that rail, -1 unknown
        public final boolean doorsOpen;  // door TARGET (doorMultiplier > 0)
        public final long dwellRemainingMs; // 0 when not dwelling at a platform
        public final long deviationMs;   // positive = late; only updated by MTR at stops
        public final boolean manual;
        public final int stopIndex;
        // Route-relative progress for the stringline's live tips: the platform dwell
        // segment last passed / next ahead, and the fraction of the way between them.
        // 0 ids mean "none" (before the first / after the last platform of the path).
        public final long prevPlatformId;
        public final long nextPlatformId;
        public final double platformFraction; // 0..1, 0 while at/traversing a platform
        // Static-ish (sent on full frames, on new vehicles, and when changed):
        public final long routeId;
        public final String routeName;
        public final String routeNumber;
        public final int routeColor;
        public final String destination;
        public final String nextStation;
        public final long sidingId;
        public final String sidingName;
        public final String depotName;
        public final String[] cars;

        VehicleSnapshot(long id, double x, double y, double z, double speedKmh, boolean reversed,
                        String railId, double railT, boolean doorsOpen, long dwellRemainingMs,
                        long deviationMs, boolean manual, int stopIndex,
                        long prevPlatformId, long nextPlatformId, double platformFraction,
                        long routeId, String routeName, String routeNumber, int routeColor,
                        String destination, String nextStation,
                        long sidingId, String sidingName, String depotName, String[] cars) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.speedKmh = speedKmh;
            this.reversed = reversed;
            this.railId = railId;
            this.railT = railT;
            this.doorsOpen = doorsOpen;
            this.dwellRemainingMs = dwellRemainingMs;
            this.deviationMs = deviationMs;
            this.manual = manual;
            this.stopIndex = stopIndex;
            this.prevPlatformId = prevPlatformId;
            this.nextPlatformId = nextPlatformId;
            this.platformFraction = platformFraction;
            this.routeId = routeId;
            this.routeName = routeName;
            this.routeNumber = routeNumber;
            this.routeColor = routeColor;
            this.destination = destination;
            this.nextStation = nextStation;
            this.sidingId = sidingId;
            this.sidingName = sidingName;
            this.depotName = depotName;
            this.cars = cars;
        }

        boolean dynamicEquals(VehicleSnapshot other) {
            return x == other.x && y == other.y && z == other.z && speedKmh == other.speedKmh
                    && reversed == other.reversed && railT == other.railT
                    && doorsOpen == other.doorsOpen && dwellRemainingMs == other.dwellRemainingMs
                    && deviationMs == other.deviationMs && manual == other.manual
                    && stopIndex == other.stopIndex
                    && prevPlatformId == other.prevPlatformId
                    && nextPlatformId == other.nextPlatformId
                    && platformFraction == other.platformFraction
                    && (railId == null ? other.railId == null : railId.equals(other.railId));
        }

        boolean staticEquals(VehicleSnapshot other) {
            return routeId == other.routeId && routeColor == other.routeColor
                    && sidingId == other.sidingId
                    && safeEquals(routeName, other.routeName)
                    && safeEquals(routeNumber, other.routeNumber)
                    && safeEquals(destination, other.destination)
                    && safeEquals(nextStation, other.nextStation)
                    && safeEquals(sidingName, other.sidingName)
                    && safeEquals(depotName, other.depotName)
                    && Arrays.equals(cars, other.cars);
        }

        private static boolean safeEquals(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** Live reservation state of one signalled rail; only rails with a non-empty state are sampled. */
    public static final class SignalSnapshot {
        public final String railId;
        public final long[] occupiedColors;  // currentlyBlocked reservation colors
        public final long[] reservedColors;  // preBlocked reservation colors

        SignalSnapshot(String railId, long[] occupiedColors, long[] reservedColors) {
            this.railId = railId;
            this.occupiedColors = occupiedColors;
            this.reservedColors = reservedColors;
        }

        boolean sameState(SignalSnapshot other) {
            return Arrays.equals(occupiedColors, other.occupiedColors)
                    && Arrays.equals(reservedColors, other.reservedColors);
        }
    }

    /** Runs on the simulator thread; must stay cheap (see class javadoc). */
    public static DimensionSample sample(Simulator simulator) {
        long now = System.currentTimeMillis();
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        for (Siding siding : simulator.sidings) {
            for (Vehicle vehicle : ((SidingVehiclesAccessor) (Object) siding).stationAnnouncer$getVehicles()) {
                if (!vehicle.getIsOnRoute()) {
                    continue;
                }
                vehicles.add(snapshotVehicle(vehicle, siding));
            }
        }

        List<SignalSnapshot> signals = new ArrayList<>();
        LongArrayList occupied = new LongArrayList();
        LongArrayList reserved = new LongArrayList();
        for (Rail rail : signalRails(simulator, now)) {
            occupied.clear();
            reserved.clear();
            rail.iterateCurrentlyBlockedSignalColors(occupied::add);
            rail.iteratePreBlockedSignalColors(reserved::add);
            if (!occupied.isEmpty() || !reserved.isEmpty()) {
                signals.add(new SignalSnapshot(rail.getHexId(), occupied.toLongArray(), reserved.toLongArray()));
            }
        }
        return new DimensionSample(now, vehicles, signals);
    }

    private static VehicleSnapshot snapshotVehicle(Vehicle vehicle, Siding siding) {
        VehicleSchemaAccessor schema = (VehicleSchemaAccessor) vehicle;
        VehicleExtraData extra = vehicle.vehicleExtraData;
        double railProgress = schema.stationAnnouncer$getRailProgress();
        double speed = schema.stationAnnouncer$getSpeed();
        Vector head = vehicle.getHeadPosition();

        // Current rail + progress along it, and dwell remaining. The stopped-at-platform
        // boundary logic mirrors HoldRuleEngine / simulateStopped: while dwelling,
        // railProgress sits exactly on the NEXT segment's startDistance and the PREVIOUS
        // segment is the platform carrying the dwell.
        String railId = null;
        double railT = -1;
        long dwellRemainingMs = 0;
        long prevPlatformId = 0;
        long nextPlatformId = 0;
        double platformFraction = 0;
        ObjectImmutableList<PathData> path = extra.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        if (index >= 0 && index < path.size()) {
            // Stringline live tip: the platform dwell segments bracketing the current
            // position. Walking outward from the current segment keeps this O(gap)
            // rather than O(path). While dwelling, railProgress sits on the NEXT
            // segment's start and the previous segment is the platform → fraction 0;
            // while traversing the platform segment itself both scans find it →
            // fraction 0 too (the tip parks at the station, which is what the chart
            // should show).
            double prevEnd = 0;
            double nextStart = 0;
            for (int i = index; i >= 0; i--) {
                PathData candidate = path.get(i);
                if (candidate.getDwellTime() > 0 && candidate.getSavedRailBaseId() != 0
                        && candidate.getStartDistance() <= railProgress) {
                    prevPlatformId = candidate.getSavedRailBaseId();
                    prevEnd = candidate.getEndDistance();
                    break;
                }
            }
            for (int i = index; i < path.size(); i++) {
                PathData candidate = path.get(i);
                if (candidate.getDwellTime() > 0 && candidate.getSavedRailBaseId() != 0
                        && candidate.getEndDistance() >= railProgress) {
                    nextPlatformId = candidate.getSavedRailBaseId();
                    nextStart = candidate.getStartDistance();
                    break;
                }
            }
            if (prevPlatformId != 0 && nextPlatformId != 0 && prevPlatformId != nextPlatformId
                    && nextStart > prevEnd) {
                platformFraction = Math.round(Math.min(1, Math.max(0,
                        (railProgress - prevEnd) / (nextStart - prevEnd))) * 1000.0) / 1000.0;
            }
        }
        if (index >= 0 && index < path.size()) {
            PathData segment = path.get(index);
            railId = segment.getRail().getHexId();
            double span = segment.getEndDistance() - segment.getStartDistance();
            // railT is documented as 0..1 whenever "rail" is present, so a degenerate
            // zero-length segment reports 0 rather than the -1 sentinel.
            // Rounded to 4 decimals: sub-millimetre on any real rail, and it keeps the
            // SSE frames small (this value is emitted for every vehicle every tick).
            railT = span > 0
                    ? Math.round(Math.min(1, Math.max(0, (railProgress - segment.getStartDistance()) / span)) * 10000.0) / 10000.0
                    : 0;
            if (speed == 0 && index > 0 && railProgress == segment.getStartDistance()) {
                PathData platformSegment = path.get(index - 1);
                if (platformSegment.getDwellTime() > 0 && platformSegment.getSavedRailBaseId() != 0) {
                    dwellRemainingMs = Math.max(0,
                            platformSegment.getDwellTime() - schema.stationAnnouncer$getElapsedDwellTime());
                }
            }
        }

        Depot depot = siding.area;
        ObjectImmutableList<VehicleCar> vehicleCars = extra.immutableVehicleCars;
        String[] cars = new String[vehicleCars.size()];
        for (int i = 0; i < cars.length; i++) {
            cars[i] = vehicleCars.get(i).getVehicleId();
        }

        return new VehicleSnapshot(
                vehicle.getId(),
                DispatchNetwork.round2(head.x), DispatchNetwork.round2(head.y), DispatchNetwork.round2(head.z),
                DispatchNetwork.round2(speed * 3600), // m/ms → km/h
                vehicle.getReversed(),
                railId, railT,
                extra.getDoorMultiplier() > 0,
                dwellRemainingMs,
                ((VehicleDeviationAccessor) vehicle).stationAnnouncer$getDeviation(),
                extra.getIsCurrentlyManual(),
                extra.getStopIndex(),
                prevPlatformId, nextPlatformId, platformFraction,
                extra.getThisRouteId(),
                extra.getThisRouteName(),
                extra.getThisRouteNumber(),
                extra.getThisRouteColor(),
                extra.getThisRouteDestination(),
                extra.getNextStationName(),
                siding.getId(),
                siding.getName(),
                depot == null ? "" : depot.getName(),
                cars);
    }

    private static Rail[] signalRails(Simulator simulator, long now) {
        SignalRails cached = SIGNAL_RAIL_CACHE.get(simulator.dimension);
        if (cached != null && now - cached.builtAt() < SIGNAL_RAILS_REBUILD_MILLIS) {
            return cached.rails();
        }
        List<Rail> found = new ArrayList<>();
        for (Rail rail : simulator.railIdMap.values()) {
            if (!rail.getSignalColors().isEmpty()) {
                found.add(rail);
            }
        }
        Rail[] rails = found.toArray(new Rail[0]);
        SIGNAL_RAIL_CACHE.put(simulator.dimension, new SignalRails(now, rails));
        return rails;
    }

    /** Called by the streamer at shutdown / when the last client leaves; drops the MTR refs. */
    public static void clearCaches() {
        SIGNAL_RAIL_CACHE.clear();
    }
}
