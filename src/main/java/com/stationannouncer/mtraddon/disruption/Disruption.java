package com.stationannouncer.mtraddon.disruption;

/**
 * One service disruption: a message about one or more lines, with a severity and
 * an optional validity window. Persisted in the addon store, synced to clients for
 * the management screen, and — while active — announced by the PA control boxes of
 * every station the affected lines call at.
 *
 * @param id           stable identifier (creation time in millis, made unique on collision)
 * @param routeIds     affected MTR route ids (empty is possible but then nothing is announced)
 * @param message      the announcement text, exactly as spoken/shown
 * @param severity     ordering key for display selection and announcement cycling
 * @param startMillis  epoch millis the disruption becomes valid; {@code 0} = immediately
 * @param endMillis    epoch millis it stops being valid; {@code 0} = until turned off
 * @param active       the manual on/off toggle, independent of the time window
 */
public record Disruption(long id, long[] routeIds, String message, Severity severity,
                         long startMillis, long endMillis, boolean active) {

    /** Ordinal order matters: higher ordinal = more severe (used for sorting and display choice). */
    public enum Severity {
        INFO,
        MINOR,
        MAJOR,
        SEVERE;

        public static Severity fromOrdinal(int ordinal) {
            Severity[] values = values();
            return ordinal < 0 || ordinal >= values.length ? INFO : values[ordinal];
        }

        public static Severity fromName(String name) {
            if (name != null) {
                for (Severity severity : values()) {
                    if (severity.name().equalsIgnoreCase(name)) {
                        return severity;
                    }
                }
            }
            return INFO;
        }
    }

    /** Toggled on AND inside its time window. */
    public boolean isActiveAt(long now) {
        return active
                && (startMillis <= 0 || now >= startMillis)
                && (endMillis <= 0 || now < endMillis);
    }

    /** Has a finite end that has already passed (the expiry ticker deletes these). */
    public boolean hasExpired(long now) {
        return endMillis > 0 && now >= endMillis;
    }

    public boolean affects(long routeId) {
        for (long id : routeIds) {
            if (id == routeId) {
                return true;
            }
        }
        return false;
    }
}
