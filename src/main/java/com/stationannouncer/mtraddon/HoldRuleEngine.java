package com.stationannouncer.mtraddon;

import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 1 — platform hold rules. Decides, at the moment a vehicle tries to
 * depart ({@code Vehicle.startUp}, intercepted by
 * {@link com.stationannouncer.mixin.VehicleMixin}), whether it should instead be
 * held because a train is less than N seconds away from one of the watched
 * platforms configured for the platform it is standing at.
 *
 * <p><b>Thread:</b> runs entirely on MTR's simulator threads (one per dimension).
 * It therefore reads only {@link AddonSnapshots} (volatile immutable) and the
 * simulator's own data (safe: we are on that simulator's thread). The two runtime
 * maps here are {@link ConcurrentHashMap}s because each dimension's simulator has
 * its own thread and the server thread clears them on republish/stop; any single
 * key is only ever written by the one simulator that owns its platform/vehicle.</p>
 *
 * <p><b>Cost when idle:</b> feature disabled → one volatile-ish config read; no
 * rules → one volatile read + isEmpty. Only a vehicle standing at a platform that
 * actually has a rule ever queries arrivals, and that answer is cached for
 * {@code holdArrivalCacheMillis} per watched platform — a held vehicle retries
 * {@code startUp} every tick, and multiple held vehicles share the cache.</p>
 */
public final class HoldRuleEngine {
    /** Arrivals fetched per siding per query; only the soonest upcoming one matters. */
    private static final long ARRIVALS_PER_SIDING = 3;

    /**
     * A vehicle whose hold state was last touched longer ago than this is at a
     * NEW stop — held vehicles retry every simulation tick, so any real gap means
     * it departed in between and the deadlock timer must restart.
     */
    private static final long NEW_STOP_GAP_MILLIS = 5_000;

    /** watched platform id → {computedAtMillis, soonestUpcomingArrivalMillis, latestPastArrivalMillis} (0 = none). */
    private static final ConcurrentHashMap<Long, long[]> ARRIVAL_CACHE = new ConcurrentHashMap<>();

    /** vehicle id → {firstHeldMillis, lastHeldMillis} for the maxHoldSeconds deadlock guard. */
    private static final ConcurrentHashMap<Long, long[]> HOLD_STATE = new ConcurrentHashMap<>();

    /**
     * Ruled platform id → wall-clock millis of the last tick a vehicle was actually
     * held there. Purely informational: it drives the "train being held" indicator on
     * yellow holding lights (broadcast by {@link AddonInit}'s ticker), never the
     * simulation. Wall clock rather than the simulator clock because the reader is
     * the server thread, and the two need to compare like for like.
     */
    private static final ConcurrentHashMap<Long, Long> HELD_PLATFORMS = new ConcurrentHashMap<>();

    /**
     * A held vehicle retries {@code startUp} every simulation tick, so an entry that
     * has not been refreshed within this window belongs to a train that has left (or
     * to a hold that was released).
     */
    private static final long HELD_TTL_MILLIS = 1_500;

    private HoldRuleEngine() {
    }

    /**
     * True = cancel this {@code startUp} and keep the vehicle at the platform.
     * Guard order is cheapest-first; everything before the rule lookup is
     * allocation-free.
     */
    public static boolean shouldHold(long vehicleId, VehicleExtraData vehicleExtraData, double railProgress, Data data) {
        // 1. Feature toggle (config object is cached after first load; this is a field read).
        AddonServerConfig.HoldRules config = AddonServerConfig.get().holdRules;
        if (!config.enabled) {
            return false;
        }

        // 2. Any rules at all?
        var rules = AddonSnapshots.holdRules();
        if (rules.isEmpty()) {
            return false;
        }

        // 3. Server/simulator side only — startUp also exists on clientside vehicles.
        if (!(data instanceof Simulator)) {
            return false;
        }

        // 4. Is this vehicle stopped AT a platform (not at a mid-route signal)?
        // When dwelling, railProgress sits exactly on the boundary: it equals the
        // NEXT path segment's startDistance (the same comparison simulateStopped
        // itself makes), and the PREVIOUS segment is the platform. A signal stop
        // mid-segment fails the boundary check; a non-platform node boundary fails
        // the dwell/savedRail checks. Terminus reversals adjust railProgress by the
        // vehicle length before calling startUp, so they fall through here too —
        // deliberate: hold rules apply to ordinary platform departures only.
        ObjectImmutableList<PathData> path = vehicleExtraData.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        if (index <= 0 || index >= path.size()) {
            return false;
        }
        if (railProgress != path.get(index).getStartDistance()) {
            return false;
        }
        PathData platformSegment = path.get(index - 1);
        long platformId = platformSegment.getSavedRailBaseId();
        if (platformId == 0 || platformSegment.getDwellTime() <= 0) {
            return false;
        }

        // 5. Does that platform have a rule?
        AddonSnapshots.HoldRule rule = rules.get(platformId);
        if (rule == null || rule.watched().length == 0) {
            return false;
        }

        // 6. Only now evaluate the (cached) arrival condition.
        long now = data.getCurrentMillis();
        if (!anyWatchedApproaching(rule, config, data, now)) {
            HOLD_STATE.remove(vehicleId);
            HELD_PLATFORMS.remove(platformId);
            return false;
        }

        // 7. Deadlock guard: never hold one stop longer than the cap. Rules with a
        // transfer window may legitimately run ~seconds + transferSeconds, so the
        // effective cap is maxHoldSeconds + transferSeconds — maxHoldSeconds keeps
        // its meaning ("longest wait FOR a train") regardless of the transfer
        // setting. The state entry survives the give-up so retries (e.g. while the
        // track ahead is briefly blocked) cannot restart the timer; it only resets
        // once the vehicle has actually been gone for a while (NEW_STOP_GAP_MILLIS).
        long[] state = HOLD_STATE.get(vehicleId);
        if (state == null || now - state[1] > NEW_STOP_GAP_MILLIS) {
            state = new long[]{now, now};
            HOLD_STATE.put(vehicleId, state);
        } else {
            state[1] = now;
        }
        boolean hold = now - state[0] < (config.maxHoldSeconds + rule.transferSeconds()) * 1_000L;
        if (hold) {
            HELD_PLATFORMS.put(platformId, System.currentTimeMillis());
        } else {
            // Gave up on the cap: the train is leaving, so the indicator must not linger.
            HELD_PLATFORMS.remove(platformId);
        }
        return hold;
    }

    /**
     * The platforms currently holding a train, for the holding-light indicator.
     * Called on the server thread a couple of times a second; prunes entries whose
     * vehicle stopped refreshing them (it departed) as it goes. Empty whenever the
     * feature is off, because nothing ever writes an entry then.
     */
    public static java.util.Set<Long> heldPlatforms() {
        if (HELD_PLATFORMS.isEmpty()) {
            return java.util.Set.of();
        }
        long now = System.currentTimeMillis();
        java.util.Set<Long> held = new java.util.HashSet<>();
        HELD_PLATFORMS.forEach((platformId, lastHeld) -> {
            if (now - lastHeld <= HELD_TTL_MILLIS) {
                held.add(platformId);
            } else {
                HELD_PLATFORMS.remove(platformId, lastHeld);
            }
        });
        return held;
    }

    /**
     * Any watched platform with an arrival in
     * {@code (now - transferSeconds*1000, now + seconds*1000]}? The past side of
     * the window is the transfer time: a watched train that has just landed keeps
     * the hold alive for transferSeconds so passengers can walk across, doors open
     * on both trains. Past arrivals only exist while the arrived train is still
     * dwelling (its entry then rolls over to the next run), so if it leaves early
     * the hold releases early — and self-arrivals cannot occur because the ruled
     * platform is filtered out of every watched set on save.
     */
    private static boolean anyWatchedApproaching(AddonSnapshots.HoldRule rule, AddonServerConfig.HoldRules config,
                                                 Data data, long now) {
        for (long watchedId : rule.watched()) {
            long[] times = arrivalTimes(watchedId, data, now, config.holdArrivalCacheMillis);
            long soonestUpcoming = times[1];
            long latestPast = times[2];
            if (soonestUpcoming > now && soonestUpcoming - now <= rule.seconds() * 1_000L) {
                return true;
            }
            if (rule.transferSeconds() > 0 && latestPast > 0
                    && now - latestPast < rule.transferSeconds() * 1_000L) {
                return true;
            }
        }
        return false;
    }

    /**
     * A platform's arrival times as {@code {computedAt, soonestUpcoming, latestPast}}
     * (0 = none), cached for {@code cacheMillis}. latestPast is the most recent
     * arrival at or before now — a train currently (or very recently) at the
     * watched platform. An unknown platform (e.g. a rule referencing another
     * dimension) yields no arrivals and simply never fires.
     */
    private static long[] arrivalTimes(long watchedId, Data data, long now, long cacheMillis) {
        long[] cached = ARRIVAL_CACHE.get(watchedId);
        if (cached != null && now - cached[0] < cacheMillis) {
            return cached;
        }
        long soonestUpcoming = 0;
        long latestPast = 0;
        Platform platform = data.platformIdMap.get(watchedId);
        if (platform != null) {
            ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
            for (Siding siding : data.sidings) {
                siding.getArrivals(now, platform, ARRIVALS_PER_SIDING, arrivals);
            }
            for (ArrivalResponse arrival : arrivals) {
                long time = arrival.getArrival();
                if (time > now) {
                    if (soonestUpcoming == 0 || time < soonestUpcoming) {
                        soonestUpcoming = time;
                    }
                } else if (time > latestPast) {
                    latestPast = time;
                }
            }
        }
        long[] computed = new long[]{now, soonestUpcoming, latestPast};
        ARRIVAL_CACHE.put(watchedId, computed);
        return computed;
    }

    /** Server thread: called when the rule snapshot is republished and on SERVER_STOPPED. */
    public static void clearRuntimeState() {
        ARRIVAL_CACHE.clear();
        HOLD_STATE.clear();
        HELD_PLATFORMS.clear();
    }
}
