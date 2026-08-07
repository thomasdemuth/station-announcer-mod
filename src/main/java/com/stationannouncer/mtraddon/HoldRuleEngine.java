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

    /** watched platform id → {computedAtMillis, soonestUpcomingArrivalMillis (0 = none)}. */
    private static final ConcurrentHashMap<Long, long[]> ARRIVAL_CACHE = new ConcurrentHashMap<>();

    /** vehicle id → {firstHeldMillis, lastHeldMillis} for the maxHoldSeconds deadlock guard. */
    private static final ConcurrentHashMap<Long, long[]> HOLD_STATE = new ConcurrentHashMap<>();

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
            return false;
        }

        // 7. Deadlock guard: never hold one stop longer than maxHoldSeconds. The
        // state entry survives the give-up so retries (e.g. while the track ahead
        // is briefly blocked) cannot restart the timer; it only resets once the
        // vehicle has actually been gone for a while (NEW_STOP_GAP_MILLIS).
        long[] state = HOLD_STATE.get(vehicleId);
        if (state == null || now - state[1] > NEW_STOP_GAP_MILLIS) {
            state = new long[]{now, now};
            HOLD_STATE.put(vehicleId, state);
        } else {
            state[1] = now;
        }
        return now - state[0] < config.maxHoldSeconds * 1_000L;
    }

    /** Any watched platform with an arrival in {@code (now, now + seconds]}? */
    private static boolean anyWatchedApproaching(AddonSnapshots.HoldRule rule, AddonServerConfig.HoldRules config,
                                                 Data data, long now) {
        for (long watchedId : rule.watched()) {
            long soonest = soonestUpcomingArrival(watchedId, data, now, config.holdArrivalCacheMillis);
            if (soonest > now && soonest - now <= rule.seconds() * 1_000L) {
                return true;
            }
        }
        return false;
    }

    /**
     * The soonest strictly-future arrival at a platform, cached for
     * {@code cacheMillis}. 0 = no upcoming arrival (or unknown platform — a rule
     * referencing another dimension's platform simply never fires).
     */
    private static long soonestUpcomingArrival(long watchedId, Data data, long now, long cacheMillis) {
        long[] cached = ARRIVAL_CACHE.get(watchedId);
        if (cached != null && now - cached[0] < cacheMillis) {
            return cached[1];
        }
        long soonest = 0;
        Platform platform = data.platformIdMap.get(watchedId);
        if (platform != null) {
            ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
            for (Siding siding : data.sidings) {
                siding.getArrivals(now, platform, ARRIVALS_PER_SIDING, arrivals);
            }
            for (ArrivalResponse arrival : arrivals) {
                long time = arrival.getArrival();
                if (time > now && (soonest == 0 || time < soonest)) {
                    soonest = time;
                }
            }
        }
        ARRIVAL_CACHE.put(watchedId, new long[]{now, soonest});
        return soonest;
    }

    /** Server thread: called when the rule snapshot is republished and on SERVER_STOPPED. */
    public static void clearRuntimeState() {
        ARRIVAL_CACHE.clear();
        HOLD_STATE.clear();
    }
}
