package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client mirror of the server's temporary stop changes (Feature 6a), replaced
 * wholesale by the {@code addon_stop_changes} sync on join, after every edit and
 * after every expiry, and cleared on disconnect. Read only on the client thread
 * (the management screens, and the "this route has a temporary change" marker).
 */
@Environment(EnvType.CLIENT)
public final class ClientStopChanges {
    /** {@code "<routeId>:<stopIndex>"} → expiry epoch millis (0 = until turned off). */
    private static Map<String, Long> disabled = Map.of();
    /** {@code "<routeId>:<stopIndex>"} → {inserted platform id, expiry epoch millis}. */
    private static Map<String, long[]> added = Map.of();
    private static Set<Long> routesWithChanges = Set.of();

    private ClientStopChanges() {
    }

    public static void replace(Map<String, Long> newDisabled, Map<String, long[]> newAdded) {
        disabled = Map.copyOf(newDisabled);
        added = Map.copyOf(newAdded);
        Set<Long> routes = new HashSet<>();
        newDisabled.keySet().forEach(key -> addRoute(routes, key));
        newAdded.keySet().forEach(key -> addRoute(routes, key));
        routesWithChanges = Set.copyOf(routes);
    }

    public static void clear() {
        disabled = Map.of();
        added = Map.of();
        routesWithChanges = Set.of();
    }

    private static void addRoute(Set<Long> routes, String key) {
        int split = key.indexOf(':');
        if (split > 0) {
            try {
                routes.add(Long.parseLong(key.substring(0, split)));
            } catch (NumberFormatException ignored) {
                // malformed keys are filtered server-side; ignore defensively
            }
        }
    }

    private static String key(long routeId, int stopIndex) {
        return routeId + ":" + stopIndex;
    }

    public static boolean isDisabled(long routeId, int stopIndex) {
        return disabled.containsKey(key(routeId, stopIndex));
    }

    /** Expiry of a skipped stop (0 = until turned off), or -1 when it is not skipped. */
    public static long disabledExpiry(long routeId, int stopIndex) {
        Long expiry = disabled.get(key(routeId, stopIndex));
        return expiry == null ? -1 : expiry;
    }

    /** {@code {platformId, expiryMillis}} inserted after this stop, or null. */
    public static long[] addedAfter(long routeId, int stopIndex) {
        return added.get(key(routeId, stopIndex));
    }

    /** Marker for the management screens: does this line carry any temporary change? */
    public static boolean hasChanges(long routeId) {
        return routesWithChanges.contains(routeId);
    }

    public static int count() {
        return disabled.size() + added.size();
    }

    /** All routes with at least one change (for the overview list). */
    public static Set<Long> routesWithChanges() {
        return routesWithChanges;
    }
}
