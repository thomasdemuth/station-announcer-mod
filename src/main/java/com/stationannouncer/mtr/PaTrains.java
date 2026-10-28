package com.stationannouncer.mtr;

import com.stationannouncer.AnnouncerRegistry;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.mixin.SidingPathAccessor;
import com.stationannouncer.mtraddon.GapFillerEngine;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import com.stationannouncer.pa.AccessPhrase;
import com.stationannouncer.pa.PaText;
import com.stationannouncer.pa.TrainAnnouncements;
import com.stationannouncer.wayfinding.layout.LayoutScanner;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Train-timed PA announcements ({@link TrainAnnouncements}) and the server half of the
 * {@code {access}} / train tokens.
 *
 * <p><b>Where the moments come from</b> (all SIMULATOR thread, all bail out on one
 * volatile read when no PA source listens to any platform):</p>
 * <ul>
 *   <li><b>Before arrival:</b> {@code Siding.getArrivals} (what MTR's PIDS use), polled
 *       every 0.5 s through {@code simulator.run} for the listened-to platforms only.</li>
 *   <li><b>Doors open:</b> the tick a stopped train's door target flips open
 *       ({@code simulateStopped} HEAD, VehicleMixin).</li>
 *   <li><b>Doors closing:</b> predicted from the same tick: MTR closes at
 *       {@code max(dwell/2, dwell − 4.2 s)} into the stop; a gap-filler stop closes at its
 *       own time. A hold that starts later keeps the doors open past it — the row has
 *       already spoken by then.</li>
 *   <li><b>After departure:</b> {@code startUp}'s TAIL when it really pulled away.</li>
 * </ul>
 * Clientside vehicles are ignored, and so is any tick whose sim clock is ahead of the
 * wall clock or more than 5 s behind it: MTR's Instant Deploy replays a whole day of
 * simulation (and a restart catches up hours) — every stop of that replay would
 * otherwise announce.
 *
 * <p><b>Server thread:</b> events arrive through a queue, are merged by train into a
 * pending map, and each tick every listening source's matching rows fire when due
 * (event time ∓ seconds, + k × gap for repeats). A due time more than
 * {@value #LATE_MILLIS} ms in the past is skipped — except a "before" row, which may
 * still speak until the event itself (a train first predicted 40 s out still gets its
 * "60 s before arrival" message). Train announcements go out QUEUED: clients finish
 * what they are saying first.</p>
 *
 * <p><b>Snapshots</b> (simulator → anyone): per dimension, every platform's rail ends
 * in the direction trains run (from the sidings' paths) — for AUTO platform matching
 * and {@code {access}}; the next train due per listened-to platform — for train tokens
 * in ordinary messages.</p>
 */
public final class PaTrains {
    /** Platforms within this many blocks (horizontally) of a source or its speakers count as AUTO. */
    private static final double AUTO_RADIUS = 16;
    private static final double AUTO_HEIGHT = 8;
    private static final int AUTO_MAX = 8;
    /** Ordinary messages look this far for "the platform here" ({access}, train tokens). */
    private static final double CONTEXT_RADIUS = 32;
    /**
     * MTR keeps its sim clock at the wall clock, a tick behind at most (it advances to
     * {@code System.currentTimeMillis()} each tick). Instant Deploy pushes it AHEAD one
     * second per replayed step; a restart's catch-up runs far BEHIND. Either way the
     * stops being simulated are not happening now.
     */
    private static final long CLOCK_AHEAD_MILLIS = 250;
    private static final long CLOCK_BEHIND_MILLIS = 5_000;
    private static final long LATE_MILLIS = 10_000;
    /** How far ahead arrivals are tracked: the longest "before" a row can ask for, plus its repeats. */
    private static final long ARRIVAL_HORIZON_MILLIS = (TrainAnnouncements.MAX_SECONDS + 60) * 1000L;
    /** A pending event is forgotten this long after its moment (longest offset + repeats + slack). */
    private static final long PENDING_TTL_MILLIS = (TrainAnnouncements.MAX_SECONDS
            + (long) TrainAnnouncements.MAX_REPEATS * TrainAnnouncements.MAX_GAP + 60) * 1000L;
    private static final long STOP_TTL_MILLIS = 30 * 60_000L;
    private static final int POLL_TICKS = 10;
    private static final int INDEX_TICKS = 20;
    private static final int SNAPSHOT_TICKS = 200;
    private static final int ARRIVALS_PER_SIDING = 3;
    /** Dev runs log every firing ("[pa train] …") so a headless rig can see them without a listener. */
    private static final boolean DEV_LOG = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();

    /** One platform as trains run through it; {@code lines} = labels of the routes that call there. */
    record Plat(long id, String name, long stationId, double[] rear, double[] front, boolean directionKnown,
                boolean served, List<String> lines) {
    }

    /**
     * One moment of one train at one platform. {@code atWall} is wall-clock millis.
     * {@code station}: PA sources listening to the platform hear it; {@code onboard}: the
     * riders of {@code vehicleId} hear the rows of {@code routeId} ({@link RouteTrainPa}).
     */
    private record TrainEvent(String dimension, long platformId, TrainAnnouncements.Event kind, String key,
                              long atWall, PaText.Train train, @Nullable double[] rear, @Nullable double[] front,
                              boolean station, boolean onboard, long vehicleId, long routeId) {
        /** Platform-only (the arrival poll knows no vehicle). */
        TrainEvent(String dimension, long platformId, TrainAnnouncements.Event kind, String key, long atWall,
                   PaText.Train train, @Nullable double[] rear, @Nullable double[] front) {
            this(dimension, platformId, kind, key, atWall, train, rear, front, true, false, 0, 0);
        }
    }

    /** The players aboard one vehicle, refreshed by its simulator thread. */
    private record Riders(List<java.util.UUID> uuids, long atWall) {
    }

    /** A train's moment waiting for its rows to come due. Server thread only. */
    private static final class Pending {
        TrainEvent event;
        /** "sourcePos#row#repeat" already handled (fired or skipped). */
        final Set<String> done = new HashSet<>();

        Pending(TrainEvent event) {
            this.event = event;
        }
    }

    /** A stopped train at a listened-to platform. Written only by its simulator thread. */
    private static final class Stop {
        final long platformId;
        final long serial;
        final PaText.Train train;
        final double[] rear;
        final double[] front;
        final String dimension;
        final long routeId;
        final boolean station;
        final boolean onboard;
        boolean opened;
        long lastCloseWall = -1;
        long lastSeenWall;

        Stop(long platformId, long serial, PaText.Train train, double[] rear, double[] front, String dimension,
             long routeId, boolean station, boolean onboard) {
            this.routeId = routeId;
            this.station = station;
            this.onboard = onboard;
            this.platformId = platformId;
            this.serial = serial;
            this.train = train;
            this.rear = rear;
            this.front = front;
            this.dimension = dimension;
        }
    }

    // simulator → server
    private static final ConcurrentLinkedQueue<TrainEvent> EVENTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, Stop> STOPS = new ConcurrentHashMap<>();
    /** dimension → platform id → geometry; inner maps immutable. */
    private static final ConcurrentHashMap<String, Map<Long, Plat>> PLATFORMS = new ConcurrentHashMap<>();
    /** dimension → station id → line labels there and at its connected stations ({transfer}); inner maps immutable. */
    private static final ConcurrentHashMap<String, Map<Long, List<String>>> STATION_LINES = new ConcurrentHashMap<>();
    /** vehicle id → its riders (vehicles on routes with on-board announcements only). */
    private static final ConcurrentHashMap<Long, Riders> RIDERS = new ConcurrentHashMap<>();
    /** vehicle id → {path index of the stop it approaches, last post wall millis} (on-board "before arrival"). */
    private static final ConcurrentHashMap<Long, long[]> APPROACH = new ConcurrentHashMap<>();
    private static final long RIDER_REFRESH_MILLIS = 500;
    private static final long APPROACH_POST_MILLIS = 1_000;
    /** platform id → the next train due there (polled platforms only). */
    private static final ConcurrentHashMap<Long, PaText.Train> NEXT_TRAIN = new ConcurrentHashMap<>();

    // server → simulator
    private static volatile Set<Long> watched = Set.of();
    /** dimension → platforms whose arrivals are polled. */
    private static volatile Map<String, Set<Long>> polled = Map.of();

    // server thread only
    private static final Map<String, Pending> PENDING = new HashMap<>();
    /** dimension → platform → listening sources (rebuilt every second). */
    private static Map<String, Map<Long, List<AbstractPaBlockEntity>>> index = Map.of();
    /** (layout json identity, platform, direction) → phrase. */
    private static final Map<String, String> ACCESS_CACHE = new HashMap<>();
    private static final Map<Long, String> ACCESS_LAYOUT = new HashMap<>();
    private static int pollTicks;
    private static int indexTicks;
    private static int snapshotTicks;

    private PaTrains() {
    }

    public static void register() {
        PaText.serverContext = PaTrains::contextAt;
        RouteTrainPa.register();
        ServerTickEvents.END_SERVER_TICK.register(PaTrains::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    private static void clear() {
        EVENTS.clear();
        STOPS.clear();
        RIDERS.clear();
        APPROACH.clear();
        PLATFORMS.clear();
        STATION_LINES.clear();
        NEXT_TRAIN.clear();
        PENDING.clear();
        ACCESS_CACHE.clear();
        ACCESS_LAYOUT.clear();
        watched = Set.of();
        polled = Map.of();
        index = Map.of();
        pollTicks = indexTicks = snapshotTicks = 0;
    }

    // ================================================================ server

    private static void tick(MinecraftServer server) {
        if (--snapshotTicks <= 0) {
            snapshotTicks = SNAPSHOT_TICKS;
            refreshPlatforms();
        }
        if (--indexTicks <= 0) {
            indexTicks = INDEX_TICKS;
            rebuildIndex(server);
        }
        if (--pollTicks <= 0) {
            pollTicks = POLL_TICKS;
            pollArrivals();
        }
        long now = System.currentTimeMillis();
        for (TrainEvent event; (event = EVENTS.poll()) != null; ) {
            String key = event.dimension() + "|" + event.platformId() + "|" + event.kind() + "|" + event.key();
            Pending pending = PENDING.get(key);
            if (pending == null) {
                PENDING.put(key, new Pending(event));
            } else {
                pending.event = event; // a newer prediction of the same moment
            }
        }
        if (PENDING.isEmpty()) {
            return;
        }
        for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            TrainEvent event = pending.event;
            if (now > event.atWall() + PENDING_TTL_MILLIS) {
                it.remove();
                continue;
            }
            if (event.station()) {
                Map<Long, List<AbstractPaBlockEntity>> byPlatform = index.get(event.dimension());
                List<AbstractPaBlockEntity> sources = byPlatform == null ? null : byPlatform.get(event.platformId());
                if (sources != null) {
                    for (AbstractPaBlockEntity source : sources) {
                        fireDue(source, pending, now);
                    }
                }
            }
            if (event.onboard()) {
                fireOnboard(server, pending, now);
            }
        }
    }

    private static void fireDue(AbstractPaBlockEntity source, Pending pending, long now) {
        if (source.isRemoved()) {
            return;
        }
        TrainEvent event = pending.event;
        List<TrainAnnouncements.Row> rows = source.getTrains().rows;
        for (int r = 0; r < rows.size(); r++) {
            TrainAnnouncements.Row row = rows.get(r);
            if (row.event() != event.kind() || !row.enabled() || row.text().isBlank()) {
                continue;
            }
            if (speakNow(pending, source.getPos().asLong() + "#" + r, row, now)) {
                PaText.Train train = withAccess(event);
                if (DEV_LOG) {
                    StationAnnouncer.LOGGER.info("[pa train] {} {} platform {} ({}): {}", source.getPos().toShortString(),
                            row.event(), event.platformId(), event.key(), PaText.fillTrain(row.text(), train));
                }
                source.announceExternal(row.text(), train, true);
            }
        }
    }

    /**
     * Whether a row speaks this tick: marks every repeat that has come due as handled and
     * says yes for the LATEST one only when it is not stale — a train first seen late must
     * not set off a burst of back-to-back repeats. A "before" row may still speak late,
     * until the event itself.
     */
    private static boolean speakNow(Pending pending, String rowKey, TrainAnnouncements.Row row, long now) {
        TrainEvent event = pending.event;
        long base = event.atWall() + (row.event().before ? -1L : 1L) * row.seconds() * 1000L;
        long latestDue = Long.MIN_VALUE;
        for (int k = 0; k < row.repeats(); k++) {
            long due = base + (long) k * row.gapSeconds() * 1000L;
            if (now < due) {
                break; // later repeats are later still
            }
            if (pending.done.add(rowKey + "#" + k)) {
                latestDue = due;
            }
        }
        if (latestDue == Long.MIN_VALUE) {
            return false;
        }
        return now - latestDue <= LATE_MILLIS || row.event().before && now < event.atWall();
    }

    /** A route's on-board rows, spoken to the players aboard the vehicle. */
    private static void fireOnboard(MinecraftServer server, Pending pending, long now) {
        TrainEvent event = pending.event;
        RouteTrainPa.Entry entry = RouteTrainPa.get(event.routeId());
        if (entry == null) {
            return;
        }
        List<TrainAnnouncements.Row> rows = entry.trains().rows;
        for (int r = 0; r < rows.size(); r++) {
            TrainAnnouncements.Row row = rows.get(r);
            if (row.event() != event.kind() || !row.enabled() || row.text().isBlank()
                    || !speakNow(pending, "route" + event.routeId() + "#" + r, row, now)) {
                continue;
            }
            Riders riders = RIDERS.get(event.vehicleId());
            String text = PaText.fillTrain(row.text(), withAccess(event));
            if (DEV_LOG) {
                StationAnnouncer.LOGGER.info("[pa train] on board route {} vehicle {} {} ({} riders): {}", event.routeId(),
                        event.vehicleId(), row.event(), riders == null ? 0 : riders.uuids().size(), text);
            }
            if (riders == null || text.isBlank()) {
                continue;
            }
            String capped = text.length() > AbstractPaBlockEntity.MAX_TEXT_LENGTH
                    ? text.substring(0, AbstractPaBlockEntity.MAX_TEXT_LENGTH) : text;
            for (java.util.UUID uuid : riders.uuids()) {
                net.minecraft.server.network.ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    com.stationannouncer.net.AnnouncerNetworking.sendAnnouncement(player, capped, 1.0f,
                            player.getBlockPos(), true, entry.chime(), entry.chimeSound(), true);
                }
            }
        }
    }

    /** The event's train with its platform's {access} phrase filled in. */
    private static PaText.Train withAccess(TrainEvent event) {
        return event.train().withAccess(accessFor(event.dimension(), event.platformId(), event.rear(), event.front()));
    }

    /**
     * Server thread: which loaded PA sources listen to which platforms, and which platforms
     * the simulators must watch / poll. Sources with no switched-on train row are left out,
     * except that ordinary messages using train tokens get their platforms polled.
     */
    private static void rebuildIndex(MinecraftServer server) {
        Map<String, Map<Long, List<AbstractPaBlockEntity>>> next = new HashMap<>();
        Set<Long> watching = new HashSet<>();
        Map<String, Set<Long>> polling = new HashMap<>();
        AnnouncerRegistry.forEachLoaded(server, source -> {
            TrainAnnouncements trains = source.getTrains();
            boolean rows = trains.active();
            boolean tokens = PaText.usesTrainTokens(source.getText());
            if (!rows && !tokens || !(source.getWorld() instanceof ServerWorld world)) {
                return;
            }
            String dimension = dimensionOf(world);
            if (dimension == null) {
                return;
            }
            List<Long> platforms = trains.autoPlatforms ? autoPlatforms(source, dimension) : trains.platforms;
            boolean needsArrivals = tokens;
            for (TrainAnnouncements.Row row : trains.rows) {
                needsArrivals |= row.enabled() && (row.event() == TrainAnnouncements.Event.BEFORE_ARRIVAL);
            }
            for (long platformId : platforms) {
                if (rows) {
                    next.computeIfAbsent(dimension, d -> new HashMap<>())
                            .computeIfAbsent(platformId, p -> new ArrayList<>()).add(source);
                    watching.add(platformId);
                }
                if (needsArrivals) {
                    polling.computeIfAbsent(dimension, d -> new HashSet<>()).add(platformId);
                }
            }
        });
        index = next;
        if (DEV_LOG && !watching.equals(watched)) {
            StationAnnouncer.LOGGER.info("[pa train] listening to platforms {} (polling {}; snapshot {})", watching, polling,
                    PLATFORMS.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).toList());
        }
        watched = Set.copyOf(watching);
        Map<String, Set<Long>> frozen = new HashMap<>();
        polling.forEach((d, set) -> frozen.put(d, Set.copyOf(set)));
        polled = Map.copyOf(frozen);
        NEXT_TRAIN.keySet().removeIf(id -> polling.values().stream().noneMatch(set -> set.contains(id)));
        long cutoff = System.currentTimeMillis() - STOP_TTL_MILLIS;
        STOPS.values().removeIf(stop -> stop.lastSeenWall < cutoff);
        long riderCutoff = System.currentTimeMillis() - 60_000;
        RIDERS.values().removeIf(riders -> riders.atWall() < riderCutoff);
        APPROACH.values().removeIf(state -> state[1] < riderCutoff);
    }

    /** The source's AUTO platforms: near the block itself or any linked speaker, nearest first. */
    static List<Long> autoPlatforms(AbstractPaBlockEntity source, String dimension) {
        Map<Long, Plat> plats = PLATFORMS.get(dimension);
        if (plats == null || plats.isEmpty()) {
            return List.of();
        }
        List<BlockPos> points = new ArrayList<>();
        points.add(source.getPos());
        if (source instanceof ControlBoxBlockEntity box) {
            points.addAll(box.getSpeakers());
        }
        // (platform id, distance) — the id stays a long: through a double[] it lost its low digits
        List<Map.Entry<Long, Double>> found = new ArrayList<>();
        for (Plat plat : plats.values()) {
            if (!plat.served()) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (BlockPos point : points) {
                best = Math.min(best, distance(plat, point, AUTO_HEIGHT));
            }
            if (best <= AUTO_RADIUS) {
                found.add(Map.entry(plat.id(), best));
            }
        }
        found.sort(Map.Entry.comparingByValue());
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < found.size() && i < AUTO_MAX; i++) {
            out.add(found.get(i).getKey());
        }
        return out;
    }

    /** Horizontal distance from a point to the platform's rail; infinite when too far up or down. */
    private static double distance(Plat plat, BlockPos point, double maxHeight) {
        double px = point.getX() + 0.5;
        double pz = point.getZ() + 0.5;
        double[] a = plat.rear();
        double[] b = plat.front();
        double midY = (a[1] + b[1]) / 2;
        if (Math.abs(point.getY() - midY) > maxHeight) {
            return Double.MAX_VALUE;
        }
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq < 1e-6 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[2]) * dz) / lengthSq));
        double cx = a[0] + t * dx - px;
        double cz = a[2] + t * dz - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    /**
     * PaText's context for an ordinary message: the source's nearest listened-to (or
     * nearest served) platform, its next train when known, and its access phrase.
     */
    @Nullable
    private static PaText.Train contextAt(World world, BlockPos pos) {
        String dimension = world instanceof ServerWorld serverWorld ? dimensionOf(serverWorld) : null;
        Map<Long, Plat> plats = dimension == null ? null : PLATFORMS.get(dimension);
        if (plats == null) {
            return null;
        }
        Plat nearest = null;
        double best = CONTEXT_RADIUS;
        if (world.getBlockEntity(pos) instanceof AbstractPaBlockEntity source) {
            List<Long> candidates = source.getTrains().autoPlatforms ? autoPlatforms(source, dimension)
                    : source.getTrains().platforms;
            if (!candidates.isEmpty()) {
                nearest = plats.get(candidates.get(0));
            }
        }
        if (nearest == null) {
            for (Plat plat : plats.values()) {
                double d = plat.served() ? distance(plat, pos, AUTO_HEIGHT) : Double.MAX_VALUE;
                if (d < best) {
                    best = d;
                    nearest = plat;
                }
            }
        }
        if (nearest == null) {
            return null;
        }
        String access = accessFor(dimension, nearest.id(), null, null);
        PaText.Train next = NEXT_TRAIN.get(nearest.id());
        // An ordinary message's {transfer}: the station's lines other than the ones at this platform.
        Set<String> own = new HashSet<>(nearest.lines());
        if (next != null) {
            own.add(next.route());
        }
        String transfer = transferAt(dimension, nearest.stationId(), own);
        if (next == null) {
            return new PaText.Train(nearest.id(), "", "", "", false, nearest.name(), access).withTransfer(transfer);
        }
        return new PaText.Train(next.platformId(), next.route(), next.destination(), next.next(), next.terminating(),
                next.platform(), access, "", transfer, next.nextTransfer());
    }

    /** The {transfer} sentence for a station: its lines (and its connected stations'), minus {@code exclude}. */
    static String transferAt(String dimension, long stationId, java.util.Collection<String> exclude) {
        Map<Long, List<String>> stations = dimension == null || stationId == 0 ? null : STATION_LINES.get(dimension);
        List<String> lines = stations == null ? null : stations.get(stationId);
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            boolean skip = false;
            for (String own : exclude) {
                skip |= own != null && own.equalsIgnoreCase(line);
            }
            if (!skip) {
                out.add(line);
            }
        }
        return PaText.transferPhrase(out);
    }

    /**
     * The {access} phrase for a platform, "" when its station has no layout scan. Rail
     * ends from the train when given (its direction is certain), else from the snapshot.
     */
    static String accessFor(String dimension, long platformId, @Nullable double[] rear, @Nullable double[] front) {
        Map<Long, Plat> plats = PLATFORMS.get(dimension);
        Plat plat = plats == null ? null : plats.get(platformId);
        if (plat == null || plat.stationId() == 0) {
            return "";
        }
        String layout = LayoutScanner.layoutJson(plat.stationId());
        if (layout == null) {
            return "";
        }
        boolean fromTrain = rear != null && front != null;
        double[] r = fromTrain ? rear : plat.rear();
        double[] f = fromTrain ? front : plat.front();
        boolean known = fromTrain || plat.directionKnown();
        // A new scan replaces the cached JSON string: identity tells us to recompute.
        if (ACCESS_LAYOUT.get(plat.stationId()) != layout) {
            ACCESS_CACHE.keySet().removeIf(k -> k.startsWith(plat.stationId() + "/"));
            ACCESS_LAYOUT.put(plat.stationId(), layout);
        }
        String cacheKey = plat.stationId() + "/" + platformId + "/" + (known ? Math.round(f[0]) + "," + Math.round(f[2]) : "?");
        String cached = ACCESS_CACHE.get(cacheKey);
        if (cached == null) {
            try {
                cached = AccessPhrase.phrase(layout, platformId, r, f, known);
            } catch (RuntimeException e) {
                StationAnnouncer.LOGGER.debug("PA: access phrase failed for platform {}", platformId, e);
                cached = "";
            }
            ACCESS_CACHE.put(cacheKey, cached);
        }
        return cached;
    }

    @Nullable
    private static String dimensionOf(ServerWorld world) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================ snapshots

    private static void refreshPlatforms() {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null) {
            return;
        }
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    PLATFORMS.put(simulator.dimension, snapshot(simulator));
                    STATION_LINES.put(simulator.dimension, stationLines(simulator));
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.debug("PA: platform snapshot failed", t);
                }
            });
        }
    }

    /** Simulator thread: every platform with its rail ends in the direction trains run. */
    private static Map<Long, Plat> snapshot(Simulator simulator) {
        Map<Long, double[][]> ends = new HashMap<>(); // id → {rear, front, {both?}}
        for (Siding siding : simulator.sidings) {
            for (PathData segment : ((SidingPathAccessor) (Object) siding).stationAnnouncer$getPathMainRoute()) {
                long id = segment.getSavedRailBaseId();
                if (id == 0 || segment.getDwellTime() <= 0) {
                    continue;
                }
                double[] rear = vec(segment.getOrderedPosition1());
                double[] front = vec(segment.getOrderedPosition2());
                double[][] seen = ends.get(id);
                if (seen == null) {
                    ends.put(id, new double[][]{rear, front, {0}});
                } else if (close(seen[0], front) && close(seen[1], rear)) {
                    seen[2][0] = 1; // trains run both ways through it
                }
            }
        }
        Map<Long, Plat> out = new HashMap<>();
        for (Platform platform : simulator.platformIdMap.values()) {
            if (platform == null) {
                continue;
            }
            double[][] seen = ends.get(platform.getId());
            Station station = platform.area;
            String name = FareLane.firstLang(platform.getName());
            List<String> lines = new ArrayList<>();
            addLines(platform, lines);
            lines = List.copyOf(lines);
            if (seen != null) {
                out.put(platform.getId(), new Plat(platform.getId(), name, station == null ? 0 : station.getId(),
                        seen[0], seen[1], seen[2][0] == 0, true, lines));
            } else {
                double[] mid = vec(platform.getMidPosition());
                out.put(platform.getId(), new Plat(platform.getId(), name, station == null ? 0 : station.getId(),
                        mid, mid, false, false, lines));
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Simulator thread: every station's line labels, MTR's interchange rule (the routes
     * calling at the station or a station connected to it, hidden routes left out), sorted
     * numbers first ("2, 3, 4, A, C").
     */
    private static Map<Long, List<String>> stationLines(Simulator simulator) {
        Map<Long, List<String>> out = new HashMap<>();
        for (Station station : simulator.stations) {
            if (station == null) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Platform platform : station.savedRails) {
                addLines(platform, lines);
            }
            for (Station connected : station.connectedStations) {
                for (Platform platform : connected.savedRails) {
                    addLines(platform, lines);
                }
            }
            if (!lines.isEmpty()) {
                lines.sort(PaTrains::compareLines);
                out.put(station.getId(), List.copyOf(lines));
            }
        }
        return Map.copyOf(out);
    }

    /** Adds the labels of a platform's visible routes, once each (case-insensitive). */
    private static void addLines(Platform platform, List<String> into) {
        for (Route route : platform.routes) {
            if (route == null || route.getHidden()) {
                continue;
            }
            String label = routeLabel(route.getRouteNumber(), route.getName());
            if (!label.isEmpty() && into.stream().noneMatch(label::equalsIgnoreCase)) {
                into.add(label);
            }
        }
    }

    /** Numbers first and in numeric order, then the rest alphabetically. */
    private static int compareLines(String a, String b) {
        boolean na = a.chars().allMatch(Character::isDigit);
        boolean nb = b.chars().allMatch(Character::isDigit);
        if (na != nb) {
            return na ? -1 : 1;
        }
        if (na && a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    private static double[] vec(Position position) {
        return new double[]{position.getX() + 0.5, position.getY(), position.getZ() + 0.5};
    }

    private static boolean close(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 0.6 && Math.abs(a[1] - b[1]) < 0.6 && Math.abs(a[2] - b[2]) < 0.6;
    }

    // ========================================================== simulator

    private static boolean clockOk(Data data) {
        long lag = System.currentTimeMillis() - data.getCurrentMillis();
        return lag >= -CLOCK_AHEAD_MILLIS && lag <= CLOCK_BEHIND_MILLIS;
    }

    /** Server thread: asks each simulator for the arrivals at its polled platforms. */
    private static void pollArrivals() {
        Map<String, Set<Long>> byDimension = polled;
        ObjectImmutableList<Simulator> simulators = byDimension.isEmpty() ? null : MtrSimulators.get();
        if (simulators == null) {
            return;
        }
        for (Simulator simulator : simulators) {
            Set<Long> platforms = byDimension.get(simulator.dimension);
            if (platforms == null || platforms.isEmpty()) {
                continue;
            }
            simulator.run(() -> {
                try {
                    pollOn(simulator, platforms);
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.debug("PA: arrival poll failed", t);
                }
            });
        }
    }

    /** Simulator thread. */
    private static void pollOn(Simulator simulator, Set<Long> platforms) {
        if (!clockOk(simulator)) {
            return;
        }
        long now = simulator.getCurrentMillis();
        long toWall = System.currentTimeMillis() - now;
        ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
        for (long platformId : platforms) {
            Platform platform = simulator.platformIdMap.get(platformId);
            if (platform == null) {
                continue;
            }
            PaText.Train soonest = null;
            long soonestAt = Long.MAX_VALUE;
            for (Siding siding : simulator.sidings) {
                arrivals.clear();
                siding.getArrivals(now, platform, ARRIVALS_PER_SIDING, arrivals);
                double[][] ends = null;
                for (ArrivalResponse arrival : arrivals) {
                    long at = arrival.getArrival();
                    if (at < now - 5_000 || at > now + ARRIVAL_HORIZON_MILLIS) {
                        continue;
                    }
                    PaText.Train train = trainFor(simulator, arrival.getRouteId(), platform,
                            arrival.getDestination(), arrival.getIsTerminating(), arrival.getRouteNumber(), arrival.getRouteName());
                    if (watched.contains(platformId)) {
                        if (ends == null) {
                            ends = sidingEnds(siding, platformId);
                        }
                        EVENTS.add(new TrainEvent(simulator.dimension, platformId, TrainAnnouncements.Event.BEFORE_ARRIVAL,
                                "a" + siding.getId() + ":" + arrival.getDepartureIndex() + ":" + arrival.getRouteId(),
                                at + toWall, train, ends[0], ends[1]));
                    }
                    if (at >= now && at < soonestAt) {
                        soonestAt = at;
                        soonest = train;
                    }
                }
            }
            if (soonest == null) {
                NEXT_TRAIN.remove(platformId);
            } else {
                NEXT_TRAIN.put(platformId, soonest);
            }
        }
    }

    /** {rear, front} of a platform on a siding's path, or {null, null}. */
    private static double[][] sidingEnds(Siding siding, long platformId) {
        for (PathData segment : ((SidingPathAccessor) (Object) siding).stationAnnouncer$getPathMainRoute()) {
            if (segment.getSavedRailBaseId() == platformId && segment.getDwellTime() > 0) {
                return new double[][]{vec(segment.getOrderedPosition1()), vec(segment.getOrderedPosition2())};
            }
        }
        return new double[][]{null, null};
    }

    /**
     * {@code simulateStopped} HEAD, SIMULATOR thread: a train standing still. Opens a stop
     * record at a listened-to platform (or for a route with on-board announcements),
     * reports the doors opening and the predicted close.
     */
    public static void tickStopped(long vehicleId, VehicleExtraData extra, double railProgress, long elapsedDwellMillis,
                                   Data data) {
        Set<Long> watching = watched;
        Set<Long> routes = RouteTrainPa.routes();
        if (watching.isEmpty() && routes.isEmpty() || !(data instanceof Simulator simulator) || !clockOk(data)) {
            return;
        }
        int index = stoppedPlatformIndex(extra, railProgress);
        if (index < 0) {
            return;
        }
        PathData segment = extra.immutablePath.get(index);
        long platformId = segment.getSavedRailBaseId();
        boolean station = watching.contains(platformId);
        if (!station && !onWatchedRoute(extra, routes)) {
            return;
        }
        long nowWall = System.currentTimeMillis();
        Stop stop = STOPS.get(vehicleId);
        if (stop == null || stop.platformId != platformId) {
            Platform platform = simulator.platformIdMap.get(platformId);
            if (platform == null) {
                return;
            }
            StopTrain stopTrain = trainForStop(simulator, extra, platform);
            boolean onboard = routes.contains(stopTrain.routeId());
            if (!station && !onboard) {
                return;
            }
            stop = new Stop(platformId, nowWall, stopTrain.train(), vec(segment.getOrderedPosition1()),
                    vec(segment.getOrderedPosition2()), simulator.dimension, stopTrain.routeId(), station, onboard);
            STOPS.put(vehicleId, stop);
        }
        stop.lastSeenWall = nowWall;
        if (stop.onboard) {
            refreshRiders(vehicleId, extra, nowWall);
        }
        String key = "v" + vehicleId + ":" + stop.serial;
        if (!stop.opened && extra.getDoorMultiplier() > 0) {
            stop.opened = true;
            post(stop, vehicleId, TrainAnnouncements.Event.DOORS_OPEN, key, nowWall);
        }
        // Predicted close, reposted when it moves (gap fillers, a new dwell) — at most once a second otherwise.
        long dwell = segment.getDwellTime();
        long closeIn;
        long fillerClose = GapFillerEngine.plannedCloseAt(vehicleId);
        if (fillerClose >= 0) {
            closeIn = fillerClose - data.getCurrentMillis();
        } else {
            long doorCloseTime = Math.max(dwell / 2, dwell - GapFillerEngine.DOOR_MOVE_MILLIS - 1000);
            closeIn = doorCloseTime - elapsedDwellMillis;
        }
        if (closeIn >= 0 && !extra.getIsCurrentlyManual()) { // a driver closes when they like
            long closeWall = nowWall + closeIn;
            if (stop.lastCloseWall < 0 || Math.abs(closeWall - stop.lastCloseWall) > 1_000) {
                stop.lastCloseWall = closeWall;
                post(stop, vehicleId, TrainAnnouncements.Event.DOORS_CLOSING, key, closeWall);
            }
        }
    }

    private static void post(Stop stop, long vehicleId, TrainAnnouncements.Event kind, String key, long atWall) {
        EVENTS.add(new TrainEvent(stop.dimension, stop.platformId, kind, key, atWall, stop.train, stop.rear, stop.front,
                stop.station, stop.onboard, vehicleId, stop.routeId));
    }

    /** {@code startUp} TAIL, SIMULATOR thread: {@code speed != 0} = it really left. */
    public static void afterStartUp(long vehicleId, double speed, Data data) {
        if (speed == 0 || STOPS.isEmpty() || !(data instanceof Simulator)) {
            return;
        }
        Stop stop = STOPS.remove(vehicleId);
        if (stop == null || !clockOk(data)) {
            return;
        }
        post(stop, vehicleId, TrainAnnouncements.Event.AFTER_DEPARTURE, "v" + vehicleId + ":" + stop.serial,
                System.currentTimeMillis());
    }

    /**
     * {@code simulateMoving} HEAD, SIMULATOR thread: the on-board "before arrival" moment.
     * Only for a vehicle on a route with on-board announcements and with someone aboard:
     * the time to the next stop is estimated as cruise-then-brake,
     * {@code d/v + v/(2·decel)}, and reposted once a second as it firms up.
     */
    public static void tickMoving(long vehicleId, VehicleExtraData extra, double railProgress, double speed, Data data) {
        Set<Long> routes = RouteTrainPa.routes();
        if (routes.isEmpty() || speed <= 0 || !(data instanceof Simulator simulator) || !onWatchedRoute(extra, routes)) {
            return;
        }
        long nowWall = System.currentTimeMillis();
        long[] state = APPROACH.get(vehicleId);
        if (state != null && nowWall - state[1] < APPROACH_POST_MILLIS) {
            return;
        }
        // Nobody aboard = nobody to hear it (dev runs post anyway so the headless rig can time it).
        if (!clockOk(data) || !refreshRiders(vehicleId, extra, nowWall) && !DEV_LOG) {
            return;
        }
        ObjectImmutableList<PathData> path = extra.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        int stopIndex = -1;
        for (int i = Math.max(0, index); i < path.size(); i++) {
            PathData segment = path.get(i);
            if (segment.getDwellTime() > 0 && segment.getSavedRailBaseId() != 0) {
                stopIndex = i;
                break;
            }
        }
        if (stopIndex < 0) {
            return;
        }
        PathData segment = path.get(stopIndex);
        double distance = segment.getEndDistance() - railProgress;
        double decel = Math.max(1e-9, extra.getDeceleration());
        long eta = (long) (distance / speed + speed / (2 * decel));
        APPROACH.put(vehicleId, new long[]{stopIndex, nowWall});
        if (distance <= 0 || eta > ARRIVAL_HORIZON_MILLIS) {
            return;
        }
        Platform platform = simulator.platformIdMap.get(segment.getSavedRailBaseId());
        long routeId = extra.getThisRouteId();
        Route route = simulator.routeIdMap.get(routeId);
        if (platform == null || route == null || !routes.contains(routeId)) {
            return;
        }
        int at = indexOf(route, platform.getId());
        boolean terminating = at < 0 || at >= route.getRoutePlatforms().size() - 1;
        PaText.Train train = trainFor(simulator, routeId, platform, route.getDestination(Math.max(0, at)), terminating,
                route.getRouteNumber(), route.getName());
        EVENTS.add(new TrainEvent(simulator.dimension, platform.getId(), TrainAnnouncements.Event.BEFORE_ARRIVAL,
                "r" + vehicleId + ":" + stopIndex, nowWall + eta, train, vec(segment.getOrderedPosition1()),
                vec(segment.getOrderedPosition2()), false, true, vehicleId, routeId));
    }

    private static boolean onWatchedRoute(VehicleExtraData extra, Set<Long> routes) {
        return !routes.isEmpty() && (routes.contains(extra.getThisRouteId()) || routes.contains(extra.getNextRouteId())
                || routes.contains(extra.getPreviousRouteId()));
    }

    /** Re-reads who is aboard (at most twice a second); true when anyone is. */
    private static boolean refreshRiders(long vehicleId, VehicleExtraData extra, long nowWall) {
        Riders riders = RIDERS.get(vehicleId);
        if (riders == null || nowWall - riders.atWall() >= RIDER_REFRESH_MILLIS) {
            List<java.util.UUID> uuids = new ArrayList<>();
            extra.iterateRidingEntities(entity -> {
                if (entity.uuid != null && entity.isOnVehicle()) {
                    uuids.add(entity.uuid);
                }
            });
            riders = new Riders(List.copyOf(uuids), nowWall);
            RIDERS.put(vehicleId, riders);
        }
        return !riders.uuids().isEmpty();
    }

    /** Same test as AnalyticsRecorder / HoldRuleEngine: index of the platform segment it stands at, or -1. */
    private static int stoppedPlatformIndex(VehicleExtraData extra, double railProgress) {
        ObjectImmutableList<PathData> path = extra.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        if (index <= 0 || index >= path.size() || railProgress != path.get(index).getStartDistance()) {
            return -1;
        }
        PathData segment = path.get(index - 1);
        return segment.getDwellTime() <= 0 || segment.getSavedRailBaseId() == 0 ? -1 : index - 1;
    }

    /** A stopped train's token values and the route it serves this stop as. */
    private record StopTrain(PaText.Train train, long routeId) {
    }

    /**
     * A stopped vehicle's train. MTR's "this route" fields move on while a train stands
     * at a platform, so the route is picked from the vehicle's previous/this/next routes:
     * the one that calls here and carries on (at a terminus the route it is about to
     * run), else the one ending here.
     */
    private static StopTrain trainForStop(Simulator simulator, VehicleExtraData extra, Platform platform) {
        long[] candidates = {extra.getThisRouteId(), extra.getNextRouteId(), extra.getPreviousRouteId()};
        Route ending = null;
        for (long routeId : candidates) {
            Route route = routeId == 0 ? null : simulator.routeIdMap.get(routeId);
            int at = route == null ? -1 : indexOf(route, platform.getId());
            if (at < 0) {
                continue;
            }
            if (at < route.getRoutePlatforms().size() - 1) {
                return new StopTrain(trainFor(simulator, route.getId(), platform, route.getDestination(at), false,
                        route.getRouteNumber(), route.getName()), route.getId());
            }
            if (ending == null) {
                ending = route;
            }
        }
        if (ending != null) {
            return new StopTrain(trainFor(simulator, ending.getId(), platform,
                    ending.getDestination(indexOf(ending, platform.getId())), true, ending.getRouteNumber(),
                    ending.getName()), ending.getId());
        }
        return new StopTrain(new PaText.Train(platform.getId(), "", "", "", false,
                FareLane.firstLang(platform.getName()), ""), 0);
    }

    /** Builds the token values (access left empty: the server fills it at fire time). */
    private static PaText.Train trainFor(Simulator simulator, long routeId, Platform platform, String destination,
                                         boolean terminating, String routeNumber, String routeName) {
        Route route = simulator.routeIdMap.get(routeId);
        String next = "";
        String label = routeLabel(routeNumber, routeName);
        List<String> own = List.of(label);
        String nextTransfer = "";
        if (route != null && !terminating) {
            int at = indexOf(route, platform.getId());
            ObjectArrayList<RoutePlatformData> stops = route.getRoutePlatforms();
            if (at >= 0 && at + 1 < stops.size()) {
                Platform nextPlatform = stops.get(at + 1).platform;
                Station station = nextPlatform == null ? null : nextPlatform.area;
                next = station == null ? "" : FareLane.firstLang(station.getName());
                nextTransfer = station == null ? "" : transferAt(simulator.dimension, station.getId(), own);
            }
        }
        Station here = platform.area;
        String transfer = here == null ? "" : transferAt(simulator.dimension, here.getId(), own);
        return new PaText.Train(platform.getId(), label,
                FareLane.firstLang(destination == null ? "" : destination), next, terminating,
                FareLane.firstLang(platform.getName()), "", here == null ? "" : FareLane.firstLang(here.getName()),
                transfer, nextTransfer);
    }

    private static int indexOf(Route route, long platformId) {
        ObjectArrayList<RoutePlatformData> stops = route.getRoutePlatforms();
        for (int i = 0; i < stops.size(); i++) {
            Platform platform = stops.get(i).platform;
            if (platform != null && platform.getId() == platformId) {
                return i;
            }
        }
        return -1;
    }

    /** "2" when the route has a number, else its name ("Broadway Local"), first language, no "||" extras. */
    static String routeLabel(String number, String name) {
        String n = number == null ? "" : FareLane.firstLang(number).trim();
        if (!n.isEmpty()) {
            return n;
        }
        String full = name == null ? "" : name;
        int extra = full.indexOf("||");
        return FareLane.firstLang(extra < 0 ? full : full.substring(0, extra)).trim();
    }
}
