package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.data.ArrivalsCacheClient;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The departure list behind both station departure boards.
 *
 * <p>{@link RailroadRouteData} already batches arrivals for a display, but its
 * {@code Departure} record carries only a time, a destination, a colour and a
 * track — enough for the hanging board's four-row list. These boards need two
 * things it does not have:
 *
 * <ul>
 *   <li>the <b>route number</b>, which is a real field on MTR's
 *       {@code ArrivalResponse} (verified against 4.0.1 — no name-parsing
 *       heuristic needed, unlike the subway PIDS's route bullets); and</li>
 *   <li>the <b>stops ahead of THIS departure</b>. The existing board resolves
 *       the station list for the one next train; here every row needs its own,
 *       looked up per arrival through {@code simplifiedRouteIdMap} and cut to
 *       the platforms after the one the train is calling at.</li>
 * </ul>
 *
 * <p>Cached per display for {@value #TTL_MS} ms. Everything is plain values —
 * no MTR types cross into the renderers, which is what lets the layout be
 * exercised without a railway.</p>
 */
@Environment(EnvType.CLIENT)
final class DepartureBoardData {
    private static final long TTL_MS = 500;
    private static final int MAX_CACHE_ENTRIES = 256;

    /** Enough for the tallest board anyone can build, and a hard bound on the work. */
    static final int MAX_DEPARTURES = 24;

    /** Stops carried per departure; the boards show far fewer than this. */
    private static final int MAX_STOPS = 24;

    private static final int AUTO_PLATFORM_RADIUS = 5;

    /**
     * One train.
     *
     * @param departureMillis on the client clock, already offset-corrected
     * @param track           the platform name, "" when the platform has none
     * @param stops           stations after this one, in order, terminus last
     */
    record Departure(long departureMillis, String destination, int color,
                     String routeNumber, String routeName, String track, List<String> stops) {
        /** Minutes until departure, rounded down; negative once it has gone. */
        long minutesOut() {
            return (departureMillis - System.currentTimeMillis()) / 60_000L;
        }
    }

    /** {@code noPlatforms} separates "nothing configured and nothing nearby" from "no trains due". */
    record Board(boolean noPlatforms, List<Departure> departures) {
        static final Board EMPTY = new Board(false, List.of());
    }

    private record Entry(long expiry, Board board) {
    }

    private static final Map<Long, Entry> CACHE = new HashMap<>();

    private DepartureBoardData() {
    }

    static void clear() {
        CACHE.clear();
    }

    static Board board(BlockPos pos, long[] configuredPlatforms) {
        long key = pos.asLong();
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
            board = build(pos, configuredPlatforms);
        } catch (Exception exception) {
            // MTR's data can be swapped underneath us mid-sync; a board that
            // blinks empty for half a second beats a render crash.
            board = Board.EMPTY;
        }
        CACHE.put(key, new Entry(now + TTL_MS, board));
        return board;
    }

    private static Board build(BlockPos pos, long[] configured) {
        long key = pos.asLong();
        ObjectArrayList<ArrivalResponse> cached;
        if (configured.length == 0) {
            long detected = MtrDataCache.closestPlatform(pos, AUTO_PLATFORM_RADIUS);
            if (detected == 0) {
                return new Board(true, List.of());
            }
            cached = MtrDataCache.arrivalsForPlatform(key, detected);
        } else {
            LongArrayList ids = new LongArrayList(configured.length);
            for (long id : configured) {
                ids.add(id);
            }
            cached = MtrDataCache.arrivals(key, ids);
        }

        long offset = ArrivalsCacheClient.INSTANCE.getMillisOffset();
        long now = System.currentTimeMillis();
        List<ArrivalResponse> upcoming = new ArrayList<>();
        for (ArrivalResponse arrival : cached) {
            // Keep a train listed for a couple of seconds past its slot so the
            // top row does not flicker away mid-glance.
            if (arrival.getDeparture() - offset - now > -2000) {
                upcoming.add(arrival);
            }
        }
        upcoming.sort((a, b) -> Long.compare(a.getDeparture(), b.getDeparture()));

        List<Departure> departures = new ArrayList<>(Math.min(upcoming.size(), MAX_DEPARTURES));
        for (int i = 0; i < upcoming.size() && departures.size() < MAX_DEPARTURES; i++) {
            ArrivalResponse arrival = upcoming.get(i);
            departures.add(new Departure(
                    arrival.getDeparture() - offset,
                    RailroadRouteData.firstLang(arrival.getDestination()),
                    0xFF000000 | arrival.getRouteColor(),
                    RailroadRouteData.firstLang(arrival.getRouteNumber()),
                    RailroadRouteData.firstLang(arrival.getRouteName()),
                    RailroadRouteData.firstLang(arrival.getPlatformName()),
                    stopsAhead(arrival)));
        }
        return new Board(false, departures);
    }

    /**
     * The stations this train calls at after the one we are standing in.
     *
     * <p>Clients never receive the full route graph — only {@code
     * simplifiedRoutes} is synced — so this walks the simplified route's
     * platform list from the index of the platform the train is at. An unknown
     * route (not yet synced) yields no stops rather than a missing row.</p>
     */
    private static List<String> stopsAhead(ArrivalResponse arrival) {
        SimplifiedRoute route = MinecraftClientData.getInstance()
                .simplifiedRouteIdMap.get(arrival.getRouteId());
        if (route == null) {
            return List.of();
        }
        ObjectArrayList<SimplifiedRoutePlatform> platforms = route.getPlatforms();
        int index = route.getPlatformIndex(arrival.getPlatformId());
        if (index < 0 || index + 1 >= platforms.size()) {
            return List.of();
        }
        int end = Math.min(platforms.size(), index + 1 + MAX_STOPS);
        List<String> stops = new ArrayList<>(end - index - 1);
        for (int i = index + 1; i < end; i++) {
            stops.add(RailroadRouteData.firstLang(platforms.get(i).getStationName()));
        }
        return stops;
    }
}
