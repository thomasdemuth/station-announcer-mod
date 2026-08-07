package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Station;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Short-lived caches in front of the MTR client lookups the block entity
 * renderers need. All three are far too expensive to call once per frame per
 * block, which is what the renderers used to do:
 *
 * <ul>
 *   <li>{@code InitClient.findStation} streams over every station in the world;</li>
 *   <li>{@code InitClient.findClosePlatform} streams over every platform and
 *       sorts by an approximate distance that itself hits the data store;</li>
 *   <li>{@code ArrivalsCacheClient.requestArrivals} walks the entire arrival
 *       cache (every platform in the world) and allocates a fresh list per call.</li>
 * </ul>
 *
 * <p>None of that data changes anywhere near frame rate — stations and
 * platforms are effectively static, arrivals refresh on MTR's own multi-second
 * server request cycle — so results are held briefly and shared by every
 * renderer. Countdowns stay perfectly smooth because they are computed from
 * the arrival's absolute timestamps, not from when the data was fetched.
 *
 * <p>Re-requesting arrivals every {@value #ARRIVALS_TTL_MS} ms also keeps MTR's
 * platform queue warm: it forgets a platform only after five of its own
 * (multi-second) request cycles pass with nobody asking.
 *
 * <p>Everything is keyed by block position and dropped on disconnect.
 */
@Environment(EnvType.CLIENT)
final class MtrDataCache {
    private static final long STATION_TTL_MS = 1000;
    private static final long PLATFORM_TTL_MS = 2000;
    private static final long ARRIVALS_TTL_MS = 200;

    /** Above this many entries, expired ones are swept (blocks left behind). */
    private static final int SWEEP_THRESHOLD = 256;

    private static final Map<Long, StationEntry> STATIONS = new HashMap<>();
    private static final Map<Long, PlatformEntry> PLATFORMS = new HashMap<>();
    private static final Map<Long, ArrivalsEntry> ARRIVALS = new HashMap<>();

    private MtrDataCache() {
    }

    static void clear() {
        STATIONS.clear();
        PLATFORMS.clear();
        ARRIVALS.clear();
    }

    /** The MTR station whose area contains this block, or null. */
    @Nullable
    static Station station(BlockPos pos) {
        long now = System.currentTimeMillis();
        StationEntry entry = STATIONS.get(pos.asLong());
        if (entry == null) {
            sweep(STATIONS, now);
            entry = new StationEntry();
            STATIONS.put(pos.asLong(), entry);
        } else if (now < entry.expiry) {
            return entry.station;
        }
        entry.expiry = now + STATION_TTL_MS;
        entry.station = org.mtr.mod.InitClient.findStation(new org.mtr.mapping.holder.BlockPos(pos));
        return entry.station;
    }

    /** Id of the closest platform within the radius, or 0 when there is none. */
    static long closestPlatform(BlockPos pos, int radius) {
        long now = System.currentTimeMillis();
        PlatformEntry entry = PLATFORMS.get(pos.asLong());
        if (entry == null) {
            sweep(PLATFORMS, now);
            entry = new PlatformEntry();
            PLATFORMS.put(pos.asLong(), entry);
        } else if (now < entry.expiry && entry.radius == radius) {
            return entry.platformId;
        }
        entry.expiry = now + PLATFORM_TTL_MS;
        entry.radius = radius;
        entry.platformId = 0;
        PlatformEntry target = entry;
        org.mtr.mod.InitClient.findClosePlatform(
                new org.mtr.mapping.holder.BlockPos(pos), radius,
                platform -> target.platformId = platform.getId());
        return entry.platformId;
    }

    /**
     * Arrivals for one display's platforms, keyed by the display's position.
     * The returned list is shared — read it, never modify it.
     */
    static ObjectArrayList<ArrivalResponse> arrivals(long key, LongCollection platformIds) {
        long now = System.currentTimeMillis();
        ArrivalsEntry entry = entryFor(key, now);
        if (entry.fresh && entry.singleId == NO_SINGLE_ID) {
            return entry.result;
        }
        entry.singleId = NO_SINGLE_ID;
        entry.expiry = now + ARRIVALS_TTL_MS;
        entry.result = org.mtr.mod.data.ArrivalsCacheClient.INSTANCE.requestArrivals(platformIds);
        return entry.result;
    }

    /**
     * Arrivals for a single platform: same as {@link #arrivals}, but keeps the
     * one-element id list instead of allocating one per lookup.
     */
    static ObjectArrayList<ArrivalResponse> arrivalsForPlatform(long key, long platformId) {
        long now = System.currentTimeMillis();
        ArrivalsEntry entry = entryFor(key, now);
        if (entry.fresh && entry.singleId == platformId) {
            return entry.result;
        }
        if (entry.singleIds == null || entry.singleId != platformId) {
            entry.singleIds = new LongArrayList();
            entry.singleIds.add(platformId);
        }
        entry.singleId = platformId;
        entry.expiry = now + ARRIVALS_TTL_MS;
        entry.result = org.mtr.mod.data.ArrivalsCacheClient.INSTANCE.requestArrivals(entry.singleIds);
        return entry.result;
    }

    /** The arrivals entry for this key, creating it if needed; {@code fresh} says whether it is still valid. */
    private static ArrivalsEntry entryFor(long key, long now) {
        ArrivalsEntry entry = ARRIVALS.get(key);
        if (entry == null) {
            sweep(ARRIVALS, now);
            entry = new ArrivalsEntry();
            ARRIVALS.put(key, entry);
        }
        entry.fresh = now < entry.expiry;
        return entry;
    }

    /** Drops expired entries once a map grows past the sweep threshold. */
    private static void sweep(Map<Long, ? extends Expiring> map, long now) {
        if (map.size() > SWEEP_THRESHOLD) {
            map.values().removeIf(entry -> now >= entry.expiry());
        }
    }

    private static final long NO_SINGLE_ID = Long.MIN_VALUE;

    private interface Expiring {
        long expiry();
    }

    private static final class StationEntry implements Expiring {
        long expiry;
        @Nullable
        Station station;

        @Override
        public long expiry() {
            return expiry;
        }
    }

    private static final class PlatformEntry implements Expiring {
        long expiry;
        int radius = -1;
        long platformId;

        @Override
        public long expiry() {
            return expiry;
        }
    }

    private static final class ArrivalsEntry implements Expiring {
        long expiry;
        boolean fresh;
        /** Platform id when filled by {@link #arrivalsForPlatform}, else {@link #NO_SINGLE_ID}. */
        long singleId = NO_SINGLE_ID;
        @Nullable
        LongArrayList singleIds;
        ObjectArrayList<ArrivalResponse> result = new ObjectArrayList<>();

        @Override
        public long expiry() {
            return expiry;
        }
    }
}
