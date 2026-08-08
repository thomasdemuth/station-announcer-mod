package com.stationannouncer.mtraddon;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;

/**
 * Volatile immutable snapshots of the addon store for MTR's <em>simulator
 * threads</em>. Mixins into TSC classes ({@code Vehicle.startUp} today, more
 * later) may run off the server thread, so they must never read the mutable
 * store directly. Instead the server thread republishes a freshly built,
 * never-again-mutated map here whenever the store changes, and simulator-thread
 * code only ever reads whatever snapshot the volatile field currently points at.
 *
 * <p>Readers MUST treat the returned maps as read-only — they are shared across
 * threads without locks and safe only because nobody writes them after publish.</p>
 */
public final class AddonSnapshots {
    /**
     * One hold rule: hold the ruled platform's trains while any {@code watched}
     * platform has an arrival within {@code seconds} — and, once a watched train
     * has landed, for a further {@code transferSeconds} so passengers can actually
     * walk across ({@code 0} = release the moment the watched train arrives).
     */
    public record HoldRule(long[] watched, int seconds, int transferSeconds) {
    }

    private static volatile Long2ObjectOpenHashMap<HoldRule> holdRules = new Long2ObjectOpenHashMap<>();

    private AddonSnapshots() {
    }

    /** Current hold rules keyed by platform id. Read-only; allocation-free {@code get(long)} lookups. */
    public static Long2ObjectOpenHashMap<HoldRule> holdRules() {
        return holdRules;
    }

    /** Server thread only: replace the published snapshot after a store mutation or load. */
    static void publishHoldRules(Map<Long, HoldRule> rules) {
        Long2ObjectOpenHashMap<HoldRule> snapshot = new Long2ObjectOpenHashMap<>(Math.max(1, rules.size()));
        rules.forEach(snapshot::put);
        holdRules = snapshot;
        // Rules changed: cached arrival answers and hold timers may describe the old rules.
        HoldRuleEngine.clearRuntimeState();
    }

    // -------------------------------------------- Feature 2: per-route dwell

    /**
     * Per-route dwell overrides: platform id → (route id → dwell millis). Inner
     * maps use fastutil's primitive default of {@code 0} for "no override" — safe
     * because stored values are clamped to ≥ 1000 ms on the way in.
     * ({@code Long2LongAVLTreeMap} because the shaded MTR jar relocates only the
     * fastutil classes MTR itself uses — there is no {@code Long2LongOpenHashMap}
     * in it. These maps are tiny and read only during depot regeneration.)
     */
    private static volatile Long2ObjectOpenHashMap<Long2LongAVLTreeMap> dwellOverrides = new Long2ObjectOpenHashMap<>();

    /** Current dwell overrides keyed by platform id. Read-only; allocation-free {@code get(long)} lookups. */
    public static Long2ObjectOpenHashMap<Long2LongAVLTreeMap> dwellOverrides() {
        return dwellOverrides;
    }

    /** Server thread only: replace the published snapshot after a store mutation or load. */
    static void publishDwellOverrides(Map<Long, ? extends Map<Long, Long>> overrides) {
        Long2ObjectOpenHashMap<Long2LongAVLTreeMap> snapshot = new Long2ObjectOpenHashMap<>(Math.max(1, overrides.size()));
        overrides.forEach((platformId, byRoute) -> {
            Long2LongAVLTreeMap inner = new Long2LongAVLTreeMap();
            byRoute.forEach(inner::put);
            snapshot.put(platformId.longValue(), inner);
        });
        dwellOverrides = snapshot;
        // No runtime caches to clear: DwellOverrideEngine is stateless — overrides
        // only take effect at the next depot path generation anyway.
    }

    // ----------------------------------------- Feature 5: platform groups

    /**
     * Platform groups: route id → array indexed by stop index (within that
     * route's platform list) → member platform ids, or null where the stop has
     * no group. The array-of-arrays shape keeps the simulator-thread lookup
     * allocation-free (one {@code get(long)} + one bounds-checked index); stop
     * indices are capped at {@link AddonNetworking#MAX_STOP_INDEX} on the way
     * in, so array sizes stay sane.
     */
    private static volatile Long2ObjectOpenHashMap<long[][]> platformGroups = new Long2ObjectOpenHashMap<>();

    /** Current platform groups keyed by route id. Read-only. */
    public static Long2ObjectOpenHashMap<long[][]> platformGroups() {
        return platformGroups;
    }

    /** Server thread only: replace the published snapshot after a store mutation or load. */
    static void publishPlatformGroups(Map<String, long[]> groups) {
        Long2ObjectOpenHashMap<long[][]> snapshot = new Long2ObjectOpenHashMap<>(Math.max(1, groups.size()));
        groups.forEach((key, members) -> {
            long[] parsed = PlatformGroupEngine.parseGroupKey(key);
            if (parsed == null || members == null || members.length == 0) {
                return;
            }
            long routeId = parsed[0];
            int stopIndex = (int) parsed[1];
            long[][] byIndex = snapshot.get(routeId);
            if (byIndex == null || byIndex.length <= stopIndex) {
                long[][] grown = new long[stopIndex + 1][];
                if (byIndex != null) {
                    System.arraycopy(byIndex, 0, grown, 0, byIndex.length);
                }
                byIndex = grown;
                snapshot.put(routeId, byIndex);
            }
            byIndex[stopIndex] = members.clone();
        });
        platformGroups = snapshot;
        // Groups changed: drop rotation counters / applied choices of deleted
        // groups so a removed group stops being re-applied to the route cache.
        PlatformGroupEngine.pruneRuntime(java.util.Set.copyOf(groups.keySet()));
    }

    // ------------------------------------------------------- depot groups

    /**
     * Depot-group membership flattened for the simulator threads: depot id →
     * {@code {offsetSlot, groupSize}}. A depot that is in no group is simply absent, so
     * the departure hook's whole cost for an ungrouped depot is one {@code get(long)}
     * that returns null. The array is never mutated after publish.
     */
    private static volatile Long2ObjectOpenHashMap<int[]> depotGroups = new Long2ObjectOpenHashMap<>();

    /** Current depot-group membership keyed by depot id. Read-only. */
    public static Long2ObjectOpenHashMap<int[]> depotGroups() {
        return depotGroups;
    }

    /**
     * Server thread only: rebuild the published membership map from the store's groups.
     * A depot listed in two groups keeps its FIRST membership (iteration order of the
     * store's LinkedHashMap, i.e. creation order) — the C2S handler already refuses to
     * create the second one, so this is only a defensive rule for hand-edited files.
     */
    static void publishDepotGroups(Map<Long, DepotGroup> groups) {
        Long2ObjectOpenHashMap<int[]> snapshot = new Long2ObjectOpenHashMap<>(Math.max(1, groups.size() * 2));
        groups.forEach((id, group) -> {
            long[] members = group.depotIds();
            for (int i = 0; i < members.length; i++) {
                if (members[i] != 0 && !snapshot.containsKey(members[i])) {
                    snapshot.put(members[i], new int[]{i, members.length});
                }
            }
        });
        depotGroups = snapshot;
    }

    // ------------------------------------- Feature 6a: temporary stop changes

    /**
     * One route's temporary stop changes, flattened into parallel arrays so the
     * simulator-thread lookup is a couple of linear scans over at most a handful
     * of entries and allocates nothing.
     *
     * @param disabled    indices (within {@code Route.getRoutePlatforms()}) of stops trains skip
     * @param addAfter    indices after which an extra stop is inserted
     * @param addPlatform the platform id inserted after {@code addAfter[i]}
     */
    public record RouteStopOverlay(int[] disabled, int[] addAfter, long[] addPlatform) {
        public boolean isDisabled(int stopIndex) {
            for (int index : disabled) {
                if (index == stopIndex) {
                    return true;
                }
            }
            return false;
        }

        /** The platform id to insert after this stop, or {@code 0} for none. */
        public long addedAfter(int stopIndex) {
            for (int i = 0; i < addAfter.length; i++) {
                if (addAfter[i] == stopIndex) {
                    return addPlatform[i];
                }
            }
            return 0;
        }
    }

    private static volatile Long2ObjectOpenHashMap<RouteStopOverlay> stopOverlays = new Long2ObjectOpenHashMap<>();

    /** Current temporary stop changes keyed by route id. Read-only. */
    public static Long2ObjectOpenHashMap<RouteStopOverlay> stopOverlays() {
        return stopOverlays;
    }

    /**
     * Server thread only: rebuild the published snapshot from the store's two
     * key→value maps ({@code "<routeId>:<stopIndex>"} keys, as persisted).
     */
    static void publishStopOverlays(Map<String, Long> disabledStops, Map<String, long[]> addedStops) {
        Map<Long, java.util.List<Integer>> disabledByRoute = new java.util.LinkedHashMap<>();
        Map<Long, java.util.List<long[]>> addedByRoute = new java.util.LinkedHashMap<>();
        disabledStops.forEach((key, expiry) -> {
            long[] parsed = com.stationannouncer.mtraddon.disruption.StopOverlayEngine.parseStopKey(key);
            if (parsed != null) {
                disabledByRoute.computeIfAbsent(parsed[0], id -> new java.util.ArrayList<>()).add((int) parsed[1]);
            }
        });
        addedStops.forEach((key, value) -> {
            long[] parsed = com.stationannouncer.mtraddon.disruption.StopOverlayEngine.parseStopKey(key);
            if (parsed != null && value != null && value.length > 0 && value[0] != 0) {
                addedByRoute.computeIfAbsent(parsed[0], id -> new java.util.ArrayList<>())
                        .add(new long[]{parsed[1], value[0]});
            }
        });

        Long2ObjectOpenHashMap<RouteStopOverlay> snapshot =
                new Long2ObjectOpenHashMap<>(Math.max(1, disabledByRoute.size() + addedByRoute.size()));
        java.util.Set<Long> routeIds = new java.util.LinkedHashSet<>(disabledByRoute.keySet());
        routeIds.addAll(addedByRoute.keySet());
        for (long routeId : routeIds) {
            java.util.List<Integer> disabled = disabledByRoute.getOrDefault(routeId, java.util.List.of());
            java.util.List<long[]> added = addedByRoute.getOrDefault(routeId, java.util.List.of());
            int[] disabledArray = new int[disabled.size()];
            for (int i = 0; i < disabledArray.length; i++) {
                disabledArray[i] = disabled.get(i);
            }
            int[] addAfter = new int[added.size()];
            long[] addPlatform = new long[added.size()];
            for (int i = 0; i < addAfter.length; i++) {
                addAfter[i] = (int) added.get(i)[0];
                addPlatform[i] = added.get(i)[1];
            }
            snapshot.put(routeId, new RouteStopOverlay(disabledArray, addAfter, addPlatform));
        }
        stopOverlays = snapshot;
    }
}
