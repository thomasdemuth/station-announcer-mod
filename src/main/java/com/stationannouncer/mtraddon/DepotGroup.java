package com.stationannouncer.mtraddon;

/**
 * One named <em>depot group</em>: a set of depots that must NOT dispatch together.
 *
 * <p>Depots in a group have their departures phase-offset instead of leaving at the
 * same moment — member {@code i} of a group of {@code N} shifts its whole timetable
 * by {@code (i / N) × headway}, where the headway is that depot's own mean scheduled
 * departure interval (see {@link DepotGroupEngine}). Membership order IS the offset
 * order: the first stored depot is the reference (+0), the second departs a third of
 * a headway later in a group of three, and so on.</p>
 *
 * @param id      creation-time millis, nudged forward on collision (same scheme as
 *                {@code Disruption}); the stable key in storage and on the wire
 * @param name    the label Thomas typed; free text, capped by
 *                {@code depotGroups.maxNameLength}
 * @param depotIds member depot ids IN OFFSET ORDER (index 0 = no offset)
 */
public record DepotGroup(long id, String name, long[] depotIds) {

    /** Offset slot of a depot within this group, or {@code -1} when it is not a member. */
    public int indexOf(long depotId) {
        for (int i = 0; i < depotIds.length; i++) {
            if (depotIds[i] == depotId) {
                return i;
            }
        }
        return -1;
    }

    public int size() {
        return depotIds.length;
    }

    /** Groups smaller than two members stagger nothing — the engine skips them in O(1). */
    public boolean staggers() {
        return depotIds.length >= 2;
    }
}
