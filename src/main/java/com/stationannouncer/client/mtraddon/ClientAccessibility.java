package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Map;

/**
 * Client-side mirror of the per-station step-free accessibility map, replaced
 * wholesale by the {@code addon_accessibility} S2C packet (join + after every
 * change) and cleared on disconnect. An entry marks the station step-free; the
 * platform array narrows it (empty = every platform).
 */
@Environment(EnvType.CLIENT)
public final class ClientAccessibility {
    /** station id → step-free platform ids (empty array = all); immutable snapshot. */
    private static Map<Long, long[]> accessibility = Map.of();

    private ClientAccessibility() {
    }

    public static boolean isAccessibleStation(long stationId) {
        return accessibility.containsKey(stationId);
    }

    /** The station's configured platform subset, or null when not step-free at all. */
    public static long[] platforms(long stationId) {
        return accessibility.get(stationId);
    }

    public static boolean isAccessiblePlatform(long stationId, long platformId) {
        long[] platforms = accessibility.get(stationId);
        if (platforms == null) {
            return false;
        }
        if (platforms.length == 0) {
            return true;
        }
        for (long platform : platforms) {
            if (platform == platformId) {
                return true;
            }
        }
        return false;
    }

    static void replace(Map<Long, long[]> newAccessibility) {
        accessibility = newAccessibility;
    }

    static void clear() {
        accessibility = Map.of();
    }
}
