package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client mirror of the server's platform-group map (Feature 5), keyed
 * {@code "<routeId>:<stopIndex>"} like the store. Replaced wholesale on the
 * client thread by the {@code addon_platform_groups} sync packet (join + after
 * every change; empty while the feature is disabled server-side), cleared on
 * disconnect. Read only by the platform-group screens.
 */
@Environment(EnvType.CLIENT)
public final class ClientPlatformGroups {
    private static Map<String, List<Long>> groups = new HashMap<>();

    private ClientPlatformGroups() {
    }

    static void replace(Map<String, List<Long>> newGroups) {
        groups = newGroups;
    }

    static void clear() {
        groups = new HashMap<>();
    }

    /** The configured member platform ids for one (route, stopIndex), or null. */
    public static List<Long> get(long routeId, int stopIndex) {
        return groups.get(routeId + ":" + stopIndex);
    }
}
