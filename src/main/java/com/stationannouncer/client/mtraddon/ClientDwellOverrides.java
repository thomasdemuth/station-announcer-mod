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

    private ClientDwellOverrides() {
    }

    /** The overridden routes at one platform, or null when none are configured. */
    public static Map<Long, Long> get(long platformId) {
        return overrides.get(platformId);
    }

    static void replace(Map<Long, Map<Long, Long>> newOverrides) {
        overrides = newOverrides;
    }

    static void clear() {
        overrides = Map.of();
    }
}
