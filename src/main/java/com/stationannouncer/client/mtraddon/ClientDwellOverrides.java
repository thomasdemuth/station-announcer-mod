package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Map;

/**
 * Client-side mirror of the server's per-route dwell overrides (Feature 2),
 * replaced wholesale by the {@code addon_dwell_overrides} S2C packet (join +
 * after every change) and cleared on disconnect. Read on the client thread only
 * (the route-dwell editor).
 */
@Environment(EnvType.CLIENT)
public final class ClientDwellOverrides {
    /** platform id → (route id → dwell millis); both maps immutable snapshots. */
    private static Map<Long, Map<Long, Long>> overrides = Map.of();
    /** platform id → (route id → AddonStore.DWELL_SOURCE_*); absent = unknown. */
    private static Map<Long, Map<Long, Byte>> sources = Map.of();
    private static int version;

    private ClientDwellOverrides() {
    }

    /** The overridden routes at one platform, or null when none are configured. */
    public static Map<Long, Long> get(long platformId) {
        return overrides.get(platformId);
    }

    /** Who set a route's override ({@code AddonStore.DWELL_SOURCE_*}). */
    public static byte source(long platformId, long routeId) {
        Map<Long, Byte> byRoute = sources.get(platformId);
        Byte source = byRoute == null ? null : byRoute.get(routeId);
        return source == null ? 0 : source;
    }

    static void replace(Map<Long, Map<Long, Long>> newOverrides, Map<Long, Map<Long, Byte>> newSources) {
        overrides = newOverrides;
        sources = newSources;
        version++;
    }

    /** Bumped on every sync (screens holding a copy re-read when it moves). */
    public static int version() {
        return version;
    }

    static void clear() {
        overrides = Map.of();
        sources = Map.of();
    }
}
