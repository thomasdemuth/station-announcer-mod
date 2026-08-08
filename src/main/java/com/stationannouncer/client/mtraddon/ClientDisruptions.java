package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.ArrayList;
import java.util.List;

/**
 * Client mirror of the server's disruption list (Feature 6b), replaced wholesale
 * by the {@code addon_disruptions} sync on join and after every edit, and cleared
 * on disconnect. Read only on the client thread by the management screens.
 */
@Environment(EnvType.CLIENT)
public final class ClientDisruptions {
    /** One synced disruption; {@code severity} is the ordinal of {@code Disruption.Severity}. */
    public record Entry(long id, int severity, boolean active, long startMillis, long endMillis,
                        String message, List<Long> routeIds) {
        public boolean isActiveAt(long now) {
            return active
                    && (startMillis <= 0 || now >= startMillis)
                    && (endMillis <= 0 || now < endMillis);
        }

        public boolean affects(long routeId) {
            return routeIds.contains(routeId);
        }
    }

    private static List<Entry> entries = List.of();

    private ClientDisruptions() {
    }

    public static void replace(List<Entry> newEntries) {
        List<Entry> sorted = new ArrayList<>(newEntries);
        // Most severe first, then newest — the same order the server announces in.
        sorted.sort((a, b) -> {
            int bySeverity = Integer.compare(b.severity(), a.severity());
            return bySeverity != 0 ? bySeverity : Long.compare(a.id(), b.id());
        });
        entries = List.copyOf(sorted);
    }

    public static void clear() {
        entries = List.of();
    }

    public static List<Entry> all() {
        return entries;
    }

    public static Entry byId(long id) {
        for (Entry entry : entries) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return null;
    }

    /** Whether any stored disruption mentions this line (active or not). */
    public static boolean mentions(long routeId) {
        for (Entry entry : entries) {
            if (entry.affects(routeId)) {
                return true;
            }
        }
        return false;
    }
}
