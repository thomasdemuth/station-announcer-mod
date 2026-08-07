package com.stationannouncer.mtraddon;

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
}
