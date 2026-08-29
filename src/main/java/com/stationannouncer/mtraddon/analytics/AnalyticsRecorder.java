package com.stationannouncer.mtraddon.analytics;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timetable analytics — the SIMULATOR-thread half. Turns the two {@code Vehicle} hooks
 * in {@link com.stationannouncer.mixin.VehicleMixin} into arrival/departure events and
 * hands them to a background writer thread through a bounded queue.
 *
 * <p><b>Event sources</b> (both are edges of calls MTR already makes — no new scans):</p>
 * <ul>
 *   <li><b>Arrival:</b> {@link #onVehicleStopped} runs at the single point inside
 *       {@code Vehicle.simulateMoving} where the vehicle reaches its stopping point
 *       ({@code railProgress = stoppingPoint; speed = 0; updateDeviation()}). Because
 *       that branch is only ever taken on the tick a vehicle comes to rest, the hook
 *       costs NOTHING on every other moving tick. Stops that are not exactly on a
 *       platform boundary (mid-route signal stops, the run back into the siding) are
 *       filtered out by the shared stopped-at-platform test.</li>
 *   <li><b>Departure:</b> {@link #beforeStartUp} captures the stop at the HEAD of
 *       {@code Vehicle.startUp} (where the platform is still resolvable — startUp nudges
 *       {@code railProgress} forward when it commits) and {@link #afterStartUp} emits the
 *       event at the TAIL, but only when the call actually committed
 *       ({@code doorCooldown == 0}, observable as {@code speed != 0}). A hold-rule or
 *       door-obstruction cancel returns from HEAD and never reaches the TAIL, so a held
 *       train logs exactly one departure — the one it really made.</li>
 * </ul>
 *
 * <p><b>Dwell</b> is measured as {@code departure - arrival} from the in-memory open-arrival
 * map, which is the true time at the platform including hold-rule holds and door
 * obstructions. MTR's own {@code elapsedDwellTime} saturates at the scheduled dwell and is
 * only used as a fallback when no arrival was seen (server restarted mid-dwell, or the
 * feature was switched on while the train was already standing) — the event then carries
 * {@code dwellSrc: "elapsed"}.</p>
 *
 * <p><b>Thread:</b> everything public except {@link #start}/{@link #stop}/{@link #stats}
 * runs on a per-dimension SIMULATOR thread. It touches only the volatile config, the
 * simulator's own data (safe: we are on its thread), {@link ConcurrentHashMap}s whose
 * individual keys are written by exactly one simulator, and a bounded queue that is only
 * ever {@code offer}ed to. File I/O, aggregation and JSON all happen on the writer
 * thread.</p>
 *
 * <p><b>Toggle:</b> {@code analytics.enabled}. When false both hooks return after one
 * field read, no queue exists, and no thread or file is ever created.</p>
 */
public final class AnalyticsRecorder {
    /**
     * MTR depot frequencies are trains-per-4-hours: the departure interval in
     * nominal-day millis is {@code 14400000 / frequency} (verified by javap against
     * 4.0.1's {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings}).
     */
    private static final long FREQUENCY_BASE_MILLIS = 14_400_000L;
    /** A nominal MTR day, before {@code getGameMillisPerDay()} scaling. */
    private static final long MILLIS_PER_NOMINAL_DAY = 86_400_000L;
    /** One nominal hour — the slot {@code generatePlatformDirectionsAndWriteDeparturesToSidings} fills per frequency. */
    private static final long MILLIS_PER_NOMINAL_HOUR = 3_600_000L;
    /** MTR builds departures for 24 nominal hours per day. */
    private static final int HOURS_PER_DAY = 24;
    /** How long a route's derived scheduled headway is reused before recomputing. */
    private static final long HEADWAY_CACHE_MILLIS = 60_000;
    /** Departure attempts arrive every tick; a bigger gap means the vehicle left and came back. */
    private static final long NEW_STOP_GAP_MILLIS = 5_000;
    /** Open arrivals with no departure after this long are abandoned (vehicle removed/regenerated). */
    private static final long OPEN_ARRIVAL_TTL_MILLIS = 30 * 60_000L;
    /** At most one "queue full" warning per this interval, however many events were lost. */
    private static final long DROP_WARN_INTERVAL_MILLIS = 60_000;

    /** vehicle id → {arrival wall-clock millis, platform id}; one writer per key. */
    private static final ConcurrentHashMap<Long, long[]> OPEN_ARRIVALS = new ConcurrentHashMap<>();
    /** vehicle id → the stop captured at the HEAD of the current startUp attempt. */
    private static final ConcurrentHashMap<Long, Capture> PENDING_DEPARTURES = new ConcurrentHashMap<>();
    /** route id → {computedAt, scheduled headway millis (0 = not derivable)}. */
    private static final ConcurrentHashMap<Long, long[]> HEADWAY_CACHE = new ConcurrentHashMap<>();

    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong RECORDED = new AtomicLong();
    private static volatile long lastDropWarnMillis;

    /** Non-null only while the feature is running; the hooks test this before doing anything. */
    private static volatile BlockingQueue<AnalyticsEvent> queue;
    private static ScheduledExecutorService writer;

    private AnalyticsRecorder() {
    }

    /**
     * The stop a vehicle is standing at, captured at the HEAD of {@code startUp}. Fields
     * are mutable so repeated attempts at the same stop reuse one object; they are
     * volatile because the writer thread's pruner reads {@link #capturedAt}, and each
     * instance is only ever mutated by the one simulator thread that owns the vehicle.
     */
    private static final class Capture {
        final String dimension;
        final long platformId;
        final String platformName;
        final long stationId;
        final String stationName;
        final long routeId;
        final String routeName;
        final String routeNumber;
        final int routeColor;
        final long sidingId;
        final String depotName;
        final int stopIndex;
        final long scheduledDwellMs;
        volatile long capturedAt;
        volatile long elapsedDwellMs;

        Capture(String dimension, long platformId, String platformName, long stationId, String stationName,
                long routeId, String routeName, String routeNumber, int routeColor, long sidingId,
                String depotName, int stopIndex, long scheduledDwellMs, long capturedAt, long elapsedDwellMs) {
            this.dimension = dimension;
            this.platformId = platformId;
            this.platformName = platformName;
            this.stationId = stationId;
            this.stationName = stationName;
            this.routeId = routeId;
            this.routeName = routeName;
            this.routeNumber = routeNumber;
            this.routeColor = routeColor;
            this.sidingId = sidingId;
            this.depotName = depotName;
            this.stopIndex = stopIndex;
            this.scheduledDwellMs = scheduledDwellMs;
            this.capturedAt = capturedAt;
            this.elapsedDwellMs = elapsedDwellMs;
        }
    }

    // ------------------------------------------------------------------ lifecycle ----

    /**
     * SERVER_STARTED (server thread). Opens the log directory, prunes expired files,
     * replays recent history into the metric window and starts the writer thread.
     * Does nothing at all when the feature is disabled.
     */
    public static void start(Path analyticsDirectory) {
        stop();
        AddonServerConfig.Analytics config = AddonServerConfig.get().analytics;
        if (!config.enabled) {
            StationAnnouncer.LOGGER.info("Timetable analytics disabled by config (analytics.enabled=false)");
            return;
        }
        DROPPED.set(0);
        RECORDED.set(0);
        OPEN_ARRIVALS.clear();
        PENDING_DEPARTURES.clear();
        HEADWAY_CACHE.clear();
        AnalyticsStore.open(analyticsDirectory, config.retentionDays);
        AnalyticsAggregator.reset();
        // Warm the window from today's (and, near midnight, yesterday's) log so a restart
        // does not blank the dashboard. Bounded by the same window + event caps.
        AnalyticsAggregator.ingest(AnalyticsStore.replayRecent(config));
        AnalyticsAggregator.recompute(true);

        queue = new ArrayBlockingQueue<>(config.queueCapacity);
        writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "station-announcer-analytics");
            thread.setDaemon(true);
            return thread;
        });
        writer.scheduleWithFixedDelay(AnalyticsRecorder::drainAndAggregate,
                config.flushSeconds, config.flushSeconds, TimeUnit.SECONDS);
        StationAnnouncer.LOGGER.info("Timetable analytics recording to {} (retention {} days)",
                analyticsDirectory, config.retentionDays);
    }

    /** SERVER_STOPPING (server thread). Flushes whatever is queued, then tears everything down. */
    public static void stop() {
        BlockingQueue<AnalyticsEvent> pending = queue;
        queue = null; // simulator threads stop enqueuing immediately
        ScheduledExecutorService executor = writer;
        writer = null;
        if (executor != null) {
            executor.shutdownNow();
            try {
                // Make sure the writer is really out of AnalyticsStore before this thread
                // appends the tail — two writers on one file would interleave lines.
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (pending != null) {
            List<AnalyticsEvent> remaining = new ArrayList<>();
            pending.drainTo(remaining);
            AnalyticsStore.append(remaining);
        }
        AnalyticsStore.close();
        OPEN_ARRIVALS.clear();
        PENDING_DEPARTURES.clear();
        HEADWAY_CACHE.clear();
    }

    /** {@code {recorded, dropped}} since the last start — surfaced in the analytics payload. */
    public static long[] stats() {
        return new long[]{RECORDED.get(), DROPPED.get()};
    }

    public static boolean isRunning() {
        return queue != null;
    }

    // ------------------------------------------------------------- simulator hooks ----

    /**
     * The tick a vehicle came to a complete stop (called from {@code simulateMoving},
     * right after MTR refreshed the deviation). Records an ARRIVAL when the stop is
     * exactly on a platform boundary.
     */
    public static void onVehicleStopped(long vehicleId, VehicleExtraData vehicleExtraData, double railProgress,
                                        Data data, long deviationMs) {
        BlockingQueue<AnalyticsEvent> target = queue;
        if (target == null || !(data instanceof Simulator simulator)) {
            return;
        }
        int platformIndex = stoppedPlatformIndex(vehicleExtraData, railProgress);
        if (platformIndex < 0) {
            return;
        }
        PathData platformSegment = vehicleExtraData.immutablePath.get(platformIndex);
        long platformId = platformSegment.getSavedRailBaseId();
        long now = System.currentTimeMillis();

        long[] open = OPEN_ARRIVALS.get(vehicleId);
        if (open != null && open[1] == platformId && now - open[0] < NEW_STOP_GAP_MILLIS) {
            return; // already recorded this arrival (e.g. an immediate re-stop just past the boundary)
        }
        OPEN_ARRIVALS.put(vehicleId, new long[]{now, platformId});

        if (AddonServerConfig.get().analytics.logArrivals) {
            offer(target, buildArrival(now, simulator, vehicleId, vehicleExtraData, platformId, deviationMs));
        }
    }

    /**
     * HEAD of {@code startUp}: remember which platform this attempt is departing from,
     * because a committing startUp moves {@code railProgress} past the boundary. Called
     * once per simulation tick while a train's doors are closing; the capture object is
     * reused across the attempts of one stop.
     */
    public static void beforeStartUp(long vehicleId, VehicleExtraData vehicleExtraData, double railProgress,
                                     long elapsedDwellTime, Data data) {
        if (queue == null || !(data instanceof Simulator simulator)) {
            return;
        }
        int platformIndex = stoppedPlatformIndex(vehicleExtraData, railProgress);
        if (platformIndex < 0) {
            PENDING_DEPARTURES.remove(vehicleId);
            return;
        }
        PathData platformSegment = vehicleExtraData.immutablePath.get(platformIndex);
        long platformId = platformSegment.getSavedRailBaseId();
        long now = System.currentTimeMillis();

        Capture existing = PENDING_DEPARTURES.get(vehicleId);
        if (existing != null && existing.platformId == platformId && now - existing.capturedAt < NEW_STOP_GAP_MILLIS) {
            existing.capturedAt = now;
            existing.elapsedDwellMs = elapsedDwellTime;
            return;
        }

        Platform platform = simulator.platformIdMap.get(platformId);
        Station station = platform == null ? null : platform.area;
        Depot depot = simulator.depotIdMap.get(vehicleExtraData.getDepotId());
        PENDING_DEPARTURES.put(vehicleId, new Capture(
                simulator.dimension,
                platformId,
                platform == null ? "" : platform.getName(),
                station == null ? 0 : station.getId(),
                station == null ? "" : station.getName(),
                vehicleExtraData.getThisRouteId(),
                vehicleExtraData.getThisRouteName(),
                vehicleExtraData.getThisRouteNumber(),
                vehicleExtraData.getThisRouteColor(),
                vehicleExtraData.getSidingId(),
                depot == null ? "" : depot.getName(),
                vehicleExtraData.getStopIndex(),
                platformSegment.getDwellTime(),
                now,
                elapsedDwellTime));
    }

    /**
     * TAIL of {@code startUp}: only reached when nothing cancelled the call. Emits the
     * DEPARTURE event when the attempt actually committed — startUp leaves {@code speed}
     * at zero while the doors are still closing ({@code doorCooldown > 0}) and sets it to
     * {@code ACCELERATION_DEFAULT} on the tick it really pulls away.
     */
    public static void afterStartUp(long vehicleId, double speed, Data data, long deviationMs) {
        BlockingQueue<AnalyticsEvent> target = queue;
        if (target == null || speed == 0 || !(data instanceof Simulator simulator)) {
            return;
        }
        Capture capture = PENDING_DEPARTURES.remove(vehicleId);
        if (capture == null) {
            return; // departure from a siding/depot or a mid-route signal restart — not a stop
        }
        long now = System.currentTimeMillis();
        long[] open = OPEN_ARRIVALS.remove(vehicleId);
        boolean measured = open != null && open[1] == capture.platformId && open[0] <= now;
        long dwellMs = measured ? now - open[0] : Math.max(0, capture.elapsedDwellMs);

        offer(target, new AnalyticsEvent(false, now, capture.dimension, vehicleId,
                capture.platformId, capture.platformName, capture.stationId, capture.stationName,
                capture.routeId, capture.routeName, capture.routeNumber, capture.routeColor,
                capture.sidingId, capture.depotName, capture.stopIndex, deviationMs,
                dwellMs, capture.scheduledDwellMs, measured,
                scheduledHeadwayMillis(simulator, capture.routeId)));
    }

    // ------------------------------------------------------------------ internals ----

    /**
     * Index of the platform segment a vehicle is standing at, or -1 when it is not
     * stopped exactly at a platform. Identical test to {@code HoldRuleEngine} /
     * {@code DoorObstructionEngine} / {@code DispatchSampler}: while at a platform,
     * {@code railProgress} sits exactly on the NEXT segment's start distance and the
     * PREVIOUS segment carries the platform id and its dwell.
     */
    private static int stoppedPlatformIndex(VehicleExtraData vehicleExtraData, double railProgress) {
        ObjectImmutableList<PathData> path = vehicleExtraData.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        if (index <= 0 || index >= path.size()) {
            return -1;
        }
        if (railProgress != path.get(index).getStartDistance()) {
            return -1;
        }
        PathData platformSegment = path.get(index - 1);
        if (platformSegment.getDwellTime() <= 0 || platformSegment.getSavedRailBaseId() == 0) {
            return -1;
        }
        return index - 1;
    }

    /** Arrival events carry identity + deviation only; every dwell/headway field is a departure concept. */
    private static AnalyticsEvent buildArrival(long atMillis, Simulator simulator, long vehicleId,
                                               VehicleExtraData vehicleExtraData, long platformId, long deviationMs) {
        Platform platform = simulator.platformIdMap.get(platformId);
        Station station = platform == null ? null : platform.area;
        Depot depot = simulator.depotIdMap.get(vehicleExtraData.getDepotId());
        return new AnalyticsEvent(true, atMillis, simulator.dimension, vehicleId,
                platformId, platform == null ? "" : platform.getName(),
                station == null ? 0 : station.getId(), station == null ? "" : station.getName(),
                vehicleExtraData.getThisRouteId(), vehicleExtraData.getThisRouteName(),
                vehicleExtraData.getThisRouteNumber(), vehicleExtraData.getThisRouteColor(),
                vehicleExtraData.getSidingId(), depot == null ? "" : depot.getName(),
                vehicleExtraData.getStopIndex(), deviationMs,
                0, 0, true, 0);
    }

    private static void offer(BlockingQueue<AnalyticsEvent> target, AnalyticsEvent event) {
        if (target.offer(event)) {
            RECORDED.incrementAndGet();
            return;
        }
        long dropped = DROPPED.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDropWarnMillis > DROP_WARN_INTERVAL_MILLIS) {
            lastDropWarnMillis = now;
            StationAnnouncer.LOGGER.warn(
                    "Analytics event queue full ({} dropped so far) — raise analytics.queueCapacity or lower analytics.flushSeconds",
                    dropped);
        }
    }

    /**
     * Scheduled headway for a route, in real simulation milliseconds, derived from the
     * depot frequencies of every depot that runs it. MTR builds departures as
     * {@code 14400000 / frequency} nominal-day millis, then maps them onto real time with
     * {@code gameMillisPerDay / 86400000} — this reverses exactly that. When several
     * depots serve one route their rates add (combined headway = 1 / Σ rate).
     *
     * <p>Returns 0 — meaning "the aggregator should observe the headway instead" — for
     * real-time-timetable depots, continuous-movement modes (cable cars) and routes with
     * no depot frequency set. Cached per route for {@value #HEADWAY_CACHE_MILLIS} ms; the
     * scan is over {@code simulator.depots}, which is a handful of entries.</p>
     */
    public static long scheduledHeadwayMillis(Simulator simulator, long routeId) {
        if (routeId == 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long[] cached = HEADWAY_CACHE.get(routeId);
        if (cached != null && now - cached[0] < HEADWAY_CACHE_MILLIS) {
            return cached[1];
        }
        double departuresPerMillis = 0;
        int hour = simulator.getHour();
        long gameMillisPerDay = simulator.getGameMillisPerDay();
        for (Depot depot : simulator.depots) {
            if (depot.getUseRealTime() || depot.getTransportMode().continuousMovement) {
                continue;
            }
            boolean serves = false;
            for (Route route : depot.routes) {
                if (route != null && route.getId() == routeId) {
                    serves = true;
                    break;
                }
            }
            if (!serves) {
                continue;
            }
            long frequency = depot.getFrequency(hour);
            if (frequency <= 0) {
                continue;
            }
            double intervalMillis = (FREQUENCY_BASE_MILLIS / (double) frequency)
                    * gameMillisPerDay / MILLIS_PER_NOMINAL_DAY;
            if (intervalMillis > 0) {
                departuresPerMillis += 1 / intervalMillis;
            }
        }
        long headway = departuresPerMillis > 0 ? Math.round(1 / departuresPerMillis) : 0;
        HEADWAY_CACHE.put(routeId, new long[]{now, headway});
        return headway;
    }

    /**
     * <b>Shared frequency math (added for the depot-group departure stagger).</b> One
     * depot's mean scheduled departure interval, in real simulation milliseconds — i.e.
     * "how often this depot dispatches a train".
     *
     * <p>It lives here rather than in a second copy because it is the SAME reversal of
     * {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings} that
     * {@link #scheduledHeadwayMillis} already performs, and the two constants it needs
     * ({@link #FREQUENCY_BASE_MILLIS}, {@link #MILLIS_PER_NOMINAL_DAY}) must not be
     * duplicated. The difference: that method pools every depot serving a ROUTE, this one
     * answers for a single DEPOT.</p>
     *
     * <p>Derivation, straight off the 4.0.1 bytecode: for each of the 24 nominal hours the
     * depot emits departures every {@code FREQUENCY_BASE_MILLIS / getFrequency(hour)}
     * nominal millis (the hour index is the real hour when the daylight cycle runs, and the
     * current hour otherwise — mirrored here), so
     * {@code departuresPerNominalDay = Σ freq(hour) × MILLIS_PER_NOMINAL_HOUR /
     * FREQUENCY_BASE_MILLIS}. Departure values are then mapped onto real time by
     * {@code × getGameMillisPerDay() / MILLIS_PER_NOMINAL_DAY}, so the mean REAL interval
     * is simply {@code getGameMillisPerDay() / departuresPerNominalDay}. For a depot whose
     * frequency is the same all day this is exactly the single-hour formula above.</p>
     *
     * <p>Returns {@code 0} — "not derivable" — for real-time-timetable depots, for
     * continuous-movement transport modes (cable cars depart every
     * {@code CONTINUOUS_MOVEMENT_FREQUENCY} ms per siding, not by frequency) and when no
     * frequency is set at all. Callers must treat 0 as "do not stagger".</p>
     *
     * <p><b>Thread:</b> the SIMULATOR thread that owns {@code simulator} — it reads that
     * simulator's own data only. Allocation-free; 24 {@code getFrequency} reads.</p>
     */
    public static long depotDepartureIntervalMillis(Simulator simulator, Depot depot) {
        if (simulator == null || depot == null || depot.getUseRealTime()
                || depot.getTransportMode().continuousMovement) {
            return 0;
        }
        long gameMillisPerDay = simulator.getGameMillisPerDay();
        if (gameMillisPerDay <= 0) {
            return 0;
        }
        // Mirrors MTR's own hour selection inside the departure loop.
        boolean timeMoving = simulator.isTimeMoving();
        int currentHour = simulator.getHour();
        long frequencySum = 0;
        for (int i = 0; i < HOURS_PER_DAY; i++) {
            long frequency = depot.getFrequency(timeMoving ? i : currentHour);
            if (frequency > 0) {
                frequencySum += frequency;
            }
        }
        if (frequencySum <= 0) {
            return 0;
        }
        double departuresPerNominalDay =
                (double) frequencySum * MILLIS_PER_NOMINAL_HOUR / FREQUENCY_BASE_MILLIS;
        if (departuresPerNominalDay <= 0) {
            return 0;
        }
        long interval = Math.round(gameMillisPerDay / departuresPerNominalDay);
        return interval > 0 ? interval : 0;
    }

    // ------------------------------------------------------------- writer thread ----

    /** Writer thread: drain → append to the JSONL log → feed the metric window → recompute. */
    private static void drainAndAggregate() {
        try {
            BlockingQueue<AnalyticsEvent> source = queue;
            List<AnalyticsEvent> batch = new ArrayList<>();
            if (source != null) {
                source.drainTo(batch);
            }
            if (!batch.isEmpty()) {
                AnalyticsStore.append(batch);
                AnalyticsAggregator.ingest(batch);
            }
            AnalyticsAggregator.recompute(false);
            pruneOpenArrivals();
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Analytics flush failed", t);
        }
    }

    /**
     * Vehicles that arrived but never departed (removed, depot regenerated, path rebuilt)
     * would otherwise pin an entry forever. Reading the volatile timestamps of objects
     * owned by simulator threads is safe: the worst case is pruning one flush late.
     */
    private static void pruneOpenArrivals() {
        long now = System.currentTimeMillis();
        OPEN_ARRIVALS.forEach((vehicleId, arrival) -> {
            if (now - arrival[0] > OPEN_ARRIVAL_TTL_MILLIS) {
                OPEN_ARRIVALS.remove(vehicleId, arrival);
            }
        });
        PENDING_DEPARTURES.forEach((vehicleId, capture) -> {
            if (now - capture.capturedAt > OPEN_ARRIVAL_TTL_MILLIS) {
                PENDING_DEPARTURES.remove(vehicleId, capture);
            }
        });
    }
}
