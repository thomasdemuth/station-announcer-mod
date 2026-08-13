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
     * A vehicle that has not attempted {@code startUp} at the SAME platform for
     * this long has been away and come back — held (and dwelling) vehicles retry
     * every simulation tick, so a gap this large means a full lap. Moving to a
     * different platform resets the stop immediately, without waiting this out.
     */
    private static final long SAME_PLATFORM_RESET_MILLIS = 30_000;

    /** watched platform id → {computedAtMillis, soonestUpcomingArrivalMillis, latestPastArrivalMillis} (0 = none). */
    private static final ConcurrentHashMap<Long, long[]> ARRIVAL_CACHE = new ConcurrentHashMap<>();

    /**
     * vehicle id → the current stop's hold state:
     * {@code {startedMillis, lastSeenMillis, platformId, awaitedArrivalMillis, served}}.
     *
     * <p>{@code awaitedArrivalMillis} is the whole point: it is captured ONCE,
     * when the hold begins, and the train waits for THAT arrival and no other.
     * Re-reading "is anything approaching?" every tick is what used to let a
     * hold chain from one connection to the next — on a platform served every
     * minute by a rule watching two minutes ahead, something is always
     * approaching, so the train sat there until the deadlock cap fired.</p>
     */
    private static final ConcurrentHashMap<Long, long[]> HOLD_STATE = new ConcurrentHashMap<>();

    // Indices into the hold-state array above.
    private static final int STARTED = 0;
    private static final int LAST_SEEN = 1;
    private static final int PLATFORM = 2;
    private static final int AWAITED = 3;
    private static final int SERVED = 4;

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

        // 6. ONE HOLD PER STOP. Everything below hangs off this: a train waits
        // for a single connection at a platform and then goes, whatever else is
        // approaching by the time that one has landed.
        long now = data.getCurrentMillis();
        long[] state = HOLD_STATE.get(vehicleId);
        boolean sameStop = state != null && state[PLATFORM] == platformId
                && now - state[LAST_SEEN] <= SAME_PLATFORM_RESET_MILLIS;
        if (sameStop && state[SERVED] != 0) {
            // Already had its hold here. Keep the entry alive (so the door-close
            // ticks that follow cannot start a second one) and let it go.
            state[LAST_SEEN] = now;
            HELD_PLATFORMS.remove(platformId);
            return false;
        }

        // 7. Starting a hold: pick the ONE arrival to wait for, now, and commit
        // to it. Nothing that turns up later can extend this stop.
        if (!sameStop) {
            long awaited = arrivalToWaitFor(rule, config, data, now);
            if (awaited == 0) {
                HOLD_STATE.remove(vehicleId);
                HELD_PLATFORMS.remove(platformId);
                return false;
            }
            state = new long[]{now, now, platformId, awaited, 0};
            HOLD_STATE.put(vehicleId, state);
        } else {
            state[LAST_SEEN] = now;
        }

        // 8. Release once the awaited train is in and the transfer window is up.
        // The deadlock cap stays as a backstop for a connection that never comes:
        // a cancelled train simply stops being projected, which would otherwise
        // leave the awaited time sitting in the future forever.
        long releaseAt = state[AWAITED] + rule.transferSeconds() * 1_000L;
        boolean capExpired = now - state[STARTED] >= (config.maxHoldSeconds + rule.transferSeconds()) * 1_000L;
        if (now >= releaseAt || capExpired) {
            state[SERVED] = 1;
            HELD_PLATFORMS.remove(platformId);
            return false;
        }
        HELD_PLATFORMS.put(platformId, System.currentTimeMillis());
        return true;
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
     * The single arrival this stop will wait for, or 0 for "nothing worth
     * waiting for, depart now". Chosen once per stop.
     *
     * <p>The soonest watched arrival inside the rule's window wins. Failing
     * that, a watched train that has JUST landed counts, so a train pulling in
     * as we are about to leave still gets its transfer time — past arrivals
     * only exist while the arrived train is still dwelling (the entry then
     * rolls over to its next run). Self-arrivals cannot occur: the ruled
     * platform is filtered out of every watched set on save.</p>
     */
    private static long arrivalToWaitFor(AddonSnapshots.HoldRule rule, AddonServerConfig.HoldRules config,
                                         Data data, long now) {
        long best = 0;
        for (long watchedId : rule.watched()) {
            long[] times = arrivalTimes(watchedId, data, now, config.holdArrivalCacheMillis);
            long soonestUpcoming = times[1];
            long latestPast = times[2];
            if (soonestUpcoming > now && soonestUpcoming - now <= rule.seconds() * 1_000L
                    && (best == 0 || soonestUpcoming < best)) {
                best = soonestUpcoming;
            }
            if (best == 0 && rule.transferSeconds() > 0 && latestPast > 0
                    && now - latestPast < rule.transferSeconds() * 1_000L) {
                best = latestPast;
            }
        }
        return best;
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
