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
    /** One hold rule: hold the ruled platform's trains while any {@code watched} platform has an arrival within {@code seconds}. */
    public record HoldRule(long[] watched, int seconds) {
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
}
