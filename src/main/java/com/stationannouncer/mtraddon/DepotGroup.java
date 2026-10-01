package com.stationannouncer.mtraddon;

/**
 * One named <em>depot group</em>: depots that belong together on a shared corridor.
 *
 * <p>Since 2026-09-29 a group shifts nothing by itself (it used to stagger member
 * {@code i} of {@code N} by {@code i/N} of a headway at the depot). It now only tells
 * the interline tooling which depots a suggestion may move; the delays themselves are
 * per-depot values ({@code AddonStore.depotDelaysView}).</p>
 *
 * @param id       creation-time millis, nudged forward on collision (same scheme as
 *                 {@code Disruption}); the stable key in storage and on the wire
 * @param name     the label Thomas typed; free text, capped by
 *                 {@code depotGroups.maxNameLength}
 * @param depotIds member depot ids (order no longer matters)
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
