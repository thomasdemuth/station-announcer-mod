package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.RailroadPidsBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.ArrivalsCacheClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles everything a Railroad PIDS shows: the next train off its
 * platforms, the stops ahead of it, and the connections a passenger could
 * actually make at each of those stops.
 *
 * <p><b>How the connection times are found.</b> MTR's client-side route data
 * ({@link SimplifiedRoute}) carries no timings at all, so the arrival time of
 * <em>our</em> train at a downstream station cannot be computed from it.
 * Instead this asks MTR for the arrivals at every platform of every stop
 * ahead, in one batched request, and finds our own train among them by
 * {@code routeId + departureIndex} — the pair that identifies one specific
 * run. That gives a real arrival time per stop, and the very same response
 * set lists every other train due there, so a connection is simply another
 * route arriving in the {@value #CONNECTION_WINDOW_MS} ms after we do.</p>
 *
 * <p>A stop whose own arrival is not in the data yet shows no connections at
 * all rather than guessing — the board never claims a connection it cannot
 * stand behind.</p>
 *
 * <p>The whole assembly is cached per display for {@value #TTL_MS} ms. It
 * walks stations and allocates lists, which must not happen per frame; the
 * board shows absolute clock times rather than a countdown, so nothing about
 * it needs to update faster than that.</p>
 */
@Environment(EnvType.CLIENT)
final class RailroadRouteData {
    /** A connection is a train leaving within this long after ours pulls in. */
    static final long CONNECTION_WINDOW_MS = 120_000;

    private static final long TTL_MS = 500;

    /** Stops looked up ahead of us. Far more than fits any screen; bounds the arrivals request. */
    private static final int MAX_STOPS = 24;

    /** Connections drawn at a single stop. */
    private static final int MAX_CONNECTIONS_PER_STOP = 3;

    /** Radius of the auto-detect fallback, matching MTR's own PIDS. */
    private static final int AUTO_PLATFORM_RADIUS = 5;

    private static final int MAX_CACHE_ENTRIES = 256;

    /** A train you could change to at a stop. */
    record Connection(int color, String label) {
    }

    /** One stop ahead, with the connections available when we get there. */
    record Stop(String stationName, List<Connection> connections) {
    }

    /** One row of the hanging board's departure list. */
    record Departure(long arrivalMillis, String destination, int color, String track) {
    }

    /** How many departures the hanging board lists. */
    private static final int MAX_DEPARTURES = 4;

    /**
     * Everything one board draws, in plain values — no MTR types, so the
     * renderer is pure layout and can be exercised without a railway.
     * {@code hasTrain} false means there is nothing to show; {@code noPlatforms}
     * then separates "not configured and nothing nearby" from "configured, but
     * no trains due". {@code departureMillis} is already on the client clock.
     */
    record Board(boolean hasTrain, boolean noPlatforms, int routeColor, String routeName,
                 String destination, long departureMillis, long arrivalMillis,
                 List<Stop> stops, String terminus, List<Departure> departures) {
        static final Board EMPTY = new Board(false, false, 0, "", "", 0, 0, List.of(), "", List.of());

        static Board none(boolean noPlatforms) {
            return new Board(false, noPlatforms, 0, "", "", 0, 0, List.of(), "", List.of());
        }

        /** Milliseconds until the next train arrives; negative once it is in. */
        long untilArrival() {
            return arrivalMillis - System.currentTimeMillis();
        }
    }

    private record Entry(long expiry, Board board) {
    }

    private static final Map<Long, Entry> CACHE = new HashMap<>();

    private RailroadRouteData() {
    }

    static void clear() {
        CACHE.clear();
    }

    /** The cached board for this display, rebuilt at most every {@value #TTL_MS} ms. */
    static Board board(RailroadPidsBlockEntity entity) {
        long key = entity.getPos().asLong();
        long now = System.currentTimeMillis();
        Entry entry = CACHE.get(key);
        if (entry != null && now < entry.expiry()) {
            return entry.board();
        }
        if (CACHE.size() > MAX_CACHE_ENTRIES) {
            CACHE.values().removeIf(cached -> now >= cached.expiry());
        }
        Board board;
        try {
            board = build(entity);
        } catch (Exception exception) {
            // MTR's data can be swapped underneath us mid-sync; a board that
            // blinks "no trains" for half a second beats a render crash.
            board = Board.EMPTY;
        }
        CACHE.put(key, new Entry(now + TTL_MS, board));
        return board;
    }

    // ------------------------------------------------------------- assembly

    private static Board build(RailroadPidsBlockEntity entity) {
        BlockPos pos = entity.getPos();
        long key = pos.asLong();
        long[] configured = entity.getPlatformIds();

        ObjectArrayList<ArrivalResponse> cached;
        if (configured.length == 0) {
            long detected = MtrDataCache.closestPlatform(pos, AUTO_PLATFORM_RADIUS);
            if (detected == 0) {
                return Board.none(true);
            }
            cached = MtrDataCache.arrivalsForPlatform(key, detected);
        } else {
            LongArrayList ids = new LongArrayList(configured.length);
            for (long id : configured) {
                ids.add(id);
            }
            cached = MtrDataCache.arrivals(key, ids);
        }

        ArrivalResponse train = nextTrain(cached);
        if (train == null) {
            return Board.none(false);
        }

        SimplifiedRoute route = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(train.getRouteId());
        String routeName = firstLang(train.getRouteName());
        String destination = firstLang(train.getDestination());
        int color = 0xFF000000 | train.getRouteColor();
        long offset = ArrivalsCacheClient.INSTANCE.getMillisOffset();
        long departure = train.getDeparture() - offset;
        long arrival = train.getArrival() - offset;
        List<Departure> departures = departures(cached, offset);
        if (route == null) {
            return new Board(true, false, color, routeName, destination, departure, arrival,
                    List.of(), "", departures);
        }
        ObjectArrayList<SimplifiedRoutePlatform> platforms = route.getPlatforms();
        int index = route.getPlatformIndex(train.getPlatformId());
        if (index < 0 || index >= platforms.size()) {
            return new Board(true, false, color, routeName, destination, departure, arrival,
                    List.of(), "", departures);
        }

        List<SimplifiedRoutePlatform> ahead = new ArrayList<>(
                platforms.subList(index, Math.min(platforms.size(), index + MAX_STOPS)));
        String terminus = firstLang(platforms.get(platforms.size() - 1).getStationName());

        List<Stop> stops = buildStops(pos, train, ahead, entity.getConnectionModes());
        return new Board(true, false, color, routeName, destination, departure, arrival,
                stops, terminus, departures);
    }

    /**
     * The next few departures off this board's own platforms, soonest first.
     * The track column is MTR's platform name, which is exactly what a real
     * board prints under TRK.
     */
    private static List<Departure> departures(ObjectArrayList<ArrivalResponse> arrivals, long offset) {
        long now = System.currentTimeMillis() + offset;
        List<ArrivalResponse> upcoming = new ArrayList<>(arrivals.size());
        for (ArrivalResponse arrival : arrivals) {
            if (arrival.getDeparture() - now > -2000) {
                upcoming.add(arrival);
            }
        }
        upcoming.sort(java.util.Comparator.comparingLong(ArrivalResponse::getArrival));
        List<Departure> departures = new ArrayList<>(Math.min(upcoming.size(), MAX_DEPARTURES));
        for (int i = 0; i < upcoming.size() && departures.size() < MAX_DEPARTURES; i++) {
            ArrivalResponse arrival = upcoming.get(i);
            departures.add(new Departure(arrival.getArrival() - offset,
                    firstLang(arrival.getDestination()),
                    0xFF000000 | arrival.getRouteColor(),
                    firstLang(arrival.getPlatformName())));
        }
        return departures;
    }

    /** The soonest train that has not already left. */
    @Nullable
    private static ArrivalResponse nextTrain(ObjectArrayList<ArrivalResponse> arrivals) {
        long now = System.currentTimeMillis() + ArrivalsCacheClient.INSTANCE.getMillisOffset();
        ArrivalResponse best = null;
        for (ArrivalResponse arrival : arrivals) {
            if (arrival.getDeparture() - now <= -2000) {
                continue;
            }
            if (best == null || arrival.getArrival() < best.getArrival()) {
                best = arrival;
            }
        }
        return best;
    }

    /**
     * The stop list with its connections. One batched arrivals request covers
     * every platform of every stop ahead — MTR's arrival cache is global and
     * shared by every display, so overlapping boards on the same line cost
     * nothing extra.
     */
    private static List<Stop> buildStops(BlockPos pos, ArrivalResponse train,
                                         List<SimplifiedRoutePlatform> ahead, int modes) {
        MinecraftClientData data = MinecraftClientData.getInstance();

        // Platform ids to ask about, and the platforms belonging to each stop.
        LongArrayList request = new LongArrayList();
        LongOpenHashSet seen = new LongOpenHashSet();
        List<LongOpenHashSet> stationPlatforms = new ArrayList<>(ahead.size());
        for (SimplifiedRoutePlatform stop : ahead) {
            LongOpenHashSet ids = new LongOpenHashSet();
            ids.add(stop.getPlatformId());
            Station station = data.stationIdMap.get(stop.getStationId());
            if (station != null) {
                // savedRails is the station's platform set, linked up by
                // ClientData.sync() — a connection is usually at a DIFFERENT
                // platform of the same station, so the route's own is not enough.
                for (Platform platform : station.savedRails) {
                    ids.add(platform.getId());
                }
            }
            stationPlatforms.add(ids);
            ids.forEach(id -> {
                if (seen.add(id)) {
                    request.add(id);
                }
            });
        }

        // A separate cache key from the display's own platform request: same
        // block, different question.
        ObjectArrayList<ArrivalResponse> downstream =
                MtrDataCache.arrivals(pos.asLong() ^ 0x5A11_20ADL, request);

        List<Stop> stops = new ArrayList<>(ahead.size());
        for (int i = 0; i < ahead.size(); i++) {
            SimplifiedRoutePlatform stop = ahead.get(i);
            String name = firstLang(stop.getStationName());
            long ourArrival = i == 0
                    ? train.getArrival()
                    : arrivalOfRun(downstream, train, stop.getPlatformId());
            if (ourArrival == Long.MIN_VALUE) {
                stops.add(new Stop(name, List.of())); // not known yet — claim nothing
                continue;
            }
            stops.add(new Stop(name, connectionsAt(data, downstream, stationPlatforms.get(i),
                    train.getRouteId(), ourArrival, modes)));
        }
        return stops;
    }

    /**
     * When our specific run reaches this platform, or {@link Long#MIN_VALUE}
     * if MTR has not published it. {@code routeId + departureIndex} identifies
     * one run of one route, which is what makes this work at all.
     */
    private static long arrivalOfRun(ObjectArrayList<ArrivalResponse> arrivals, ArrivalResponse train, long platformId) {
        for (ArrivalResponse arrival : arrivals) {
            if (arrival.getPlatformId() == platformId
                    && arrival.getRouteId() == train.getRouteId()
                    && arrival.getDepartureIndex() == train.getDepartureIndex()) {
                return arrival.getArrival();
            }
        }
        return Long.MIN_VALUE;
    }

    /** Other routes calling at this station in the window after we arrive. */
    private static List<Connection> connectionsAt(MinecraftClientData data,
                                                  ObjectArrayList<ArrivalResponse> arrivals,
                                                  LongOpenHashSet platformIds, long ourRouteId,
                                                  long ourArrival, int modes) {
        List<Connection> connections = new ArrayList<>();
        LongOpenHashSet routesSeen = new LongOpenHashSet();
        for (ArrivalResponse arrival : arrivals) {
            if (connections.size() >= MAX_CONNECTIONS_PER_STOP) {
                break;
            }
            if (arrival.getRouteId() == ourRouteId || !platformIds.contains(arrival.getPlatformId())) {
                continue;
            }
            long wait = arrival.getArrival() - ourArrival;
            if (wait < 0 || wait > CONNECTION_WINDOW_MS) {
                continue;
            }
            Platform platform = data.platformIdMap.get(arrival.getPlatformId());
            if (platform == null || (modes & (1 << platform.getTransportMode().ordinal())) == 0) {
                continue;
            }
            if (!routesSeen.add(arrival.getRouteId())) {
                continue; // one bullet per line, however many of its trains are due
            }
            String label = firstLang(arrival.getRouteName());
            if (label.isEmpty()) {
                label = firstLang(arrival.getDestination());
            }
            connections.add(new Connection(0xFF000000 | arrival.getRouteColor(), label));
        }
        return connections;
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }
}
