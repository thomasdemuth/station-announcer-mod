package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.VehicleExtraDataAccessor;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random door obstructions — a low chance that "something gets stuck in the
 * doors" when a train tries to close them at a platform, delaying departure for
 * a few seconds while the doors bounce back open.
 *
 * <p>Two trigger paths, both intercepted by
 * {@link com.stationannouncer.mixin.VehicleMixin}:</p>
 * <ul>
 *   <li><b>ATO:</b> at the first departure-ready {@code startUp} attempt of a
 *       platform stop ({@link #shouldObstructDeparture}) the chance is rolled
 *       once; if it hits, startUp is cancelled and doors are re-asserted open —
 *       a mini-hold — for a uniform-random stuck duration.</li>
 *   <li><b>Manual:</b> when the driver's door-close lands while stopped at a
 *       platform with the doors open ({@link #afterRidingUpdate} sees the door
 *       target flip open→closed inside {@code updateRidingEntities}), the same
 *       chance is rolled once per platform visit; while stuck, every close
 *       attempt is bounced straight back open, and {@code startUp} is refused
 *       too, so the driver cannot power away through the obstruction.</li>
 * </ul>
 *
 * <p><b>Thread:</b> simulator threads only, same discipline as
 * {@link HoldRuleEngine}: volatile config reads, the vehicle mutating its own
 * {@code VehicleExtraData} from inside its own simulate call, and
 * {@link ConcurrentHashMap}s whose individual keys are only written by the one
 * simulator that owns the vehicle. {@link #obstructedVehicleIds()} is the sole
 * server-thread reader. Zero work when disabled (one field read) and no per-tick
 * scans — everything hangs off calls MTR already makes.</p>
 */
public final class DoorObstructionEngine {
    /**
     * Departure attempts at one stop arrive every simulation tick; a gap larger
     * than this means the vehicle left in between, so the next attempt is a new
     * stop and rolls again (same heuristic as the hold engine's deadlock timer).
     */
    private static final long NEW_STOP_GAP_MILLIS = 5_000;

    /**
     * Hard backstop, mirroring the hold engine's deadlock cap: whatever the
     * clocks or state maps get up to, a vehicle is never refused departure by
     * this engine for longer than the configured maximum plus this grace. A
     * wedged obstruction strands a train and every rider on it, so this caps
     * the blast radius of any bug the expiry logic still hides.
     */
    private static final long BACKSTOP_GRACE_MILLIS = 10_000;

    /**
     * State stamped further than this AHEAD of the simulator clock is debris
     * from the dashboard's Instant Deploy: {@code Simulator.instantDeployDepots}
     * fast-forwards {@code currentMillis} through a FULL DAY of simulation
     * (rolling obstructions the whole way, with deadlines up to 24 h out) and
     * then rewinds the clock. Honouring such a deadline refuses departure until
     * tomorrow — Thomas's permanently-stuck train (2026-08-19, reproduced on the
     * dev rig: /mtr instantDeploy froze every obstructed train). Anything from
     * the fast-forward is therefore detected by its future timestamp and
     * discarded at the first real-time attempt.
     */
    private static final long FUTURE_SLACK_MILLIS = 30_000;

    /** ATO path: vehicle id → {lastAttemptMillis, stuckUntilMillis (0 = rolled clean), rollMillis}. */
    private static final ConcurrentHashMap<Long, long[]> AUTO_STATE = new ConcurrentHashMap<>();

    /** Manual path: vehicle id → {platformId of the roll, stuckUntilMillis (0 = rolled clean), rollMillis}. */
    private static final ConcurrentHashMap<Long, long[]> MANUAL_STATE = new ConcurrentHashMap<>();

    /**
     * Vehicle id → wall-clock millis the obstruction ends, for the S2C broadcast.
     * Written with a wall-clock deadline (sim-clock durations transplanted onto
     * {@code System.currentTimeMillis()}) because the reader is the server-thread
     * ticker, which must not compare against the simulator clock.
     */
    private static final ConcurrentHashMap<Long, Long> OBSTRUCTED = new ConcurrentHashMap<>();

    private DoorObstructionEngine() {
    }

    // -------------------------------------------------------------- ATO path

    /**
     * True = cancel this {@code startUp} (and re-assert open doors) because
     * something is stuck. Called AFTER the hold-rule check declined to hold, so
     * the once-per-stop roll happens at the first attempt that would genuinely
     * have departed — including the release moment of a Feature-1 hold, which is
     * exactly when the doors try to close.
     */
    public static boolean shouldObstructDeparture(long vehicleId, VehicleExtraData vehicleExtraData,
                                                  double railProgress, Data data) {
        AddonServerConfig.DoorObstruction config = AddonServerConfig.get().doorObstruction;
        if (!config.enabled) {
            return false;
        }
        if (!(data instanceof Simulator)) {
            return false;
        }
        if (stoppedPlatformId(vehicleExtraData, railProgress) == 0) {
            return false;
        }
        long now = data.getCurrentMillis();

        // An active manual-path obstruction also refuses startUp: the driver must
        // not be able to power away while something is stuck in the doors.
        long[] manualState = MANUAL_STATE.get(vehicleId);
        if (manualState != null && manualState[2] > now + FUTURE_SLACK_MILLIS) {
            MANUAL_STATE.remove(vehicleId);   // rolled during an instant-deploy fast-forward
            manualState = null;
        }
        if (manualState != null && manualState[1] > now && !backstopExpired(config, manualState, now)) {
            reopenAndMark(vehicleId, vehicleExtraData, manualState[1] - now);
            return true;
        }

        // Manual trains roll on the driver's door-close, not here — otherwise one
        // departure could be rolled by both paths.
        if (vehicleExtraData.getIsCurrentlyManual()) {
            return false;
        }

        long[] state = AUTO_STATE.get(vehicleId);
        if (state == null || now - state[0] > NEW_STOP_GAP_MILLIS
                || state[2] > now + FUTURE_SLACK_MILLIS) {
            // First departure-ready attempt at this stop: roll exactly once.
            state = new long[]{now, roll(config, now, vehicleId), now};
            AUTO_STATE.put(vehicleId, state);
        } else {
            // Same stop (held ticks or blocked-track retries): keep the outcome.
            state[0] = now;
        }
        if (state[1] > now && !backstopExpired(config, state, now)) {
            reopenAndMark(vehicleId, vehicleExtraData, state[1] - now);
            return true;
        }
        return false;
    }

    // ----------------------------------------------------------- manual path

    /**
     * Called at the TAIL of {@code Vehicle.updateRidingEntities} with the door
     * multiplier observed at HEAD. Reacts only when THIS call flipped the doors
     * open→closed while stationary — which, in that method, is exclusively the
     * driver's {@code manualToggleDoors} close at {@code speed == 0} (the
     * {@code speed > 0} force-close is a different branch and is left alone).
     */
    public static void afterRidingUpdate(long vehicleId, VehicleExtraData vehicleExtraData, double railProgress,
                                         double speed, Data data, int doorMultiplierBefore) {
        AddonServerConfig.DoorObstruction config = AddonServerConfig.get().doorObstruction;
        if (!config.enabled) {
            return;
        }
        if (!(data instanceof Simulator)) {
            return;
        }
        if (speed > 0) {
            // Moving = the stop is over; the next platform visit rolls afresh.
            if (!MANUAL_STATE.isEmpty()) {
                MANUAL_STATE.remove(vehicleId);
            }
            return;
        }
        if (doorMultiplierBefore <= 0 || vehicleExtraData.getDoorMultiplier() >= 0) {
            return; // not an open→closed flip, nothing was attempted
        }
        long platformId = stoppedPlatformId(vehicleExtraData, railProgress);
        if (platformId == 0) {
            return; // only platform departures get obstructions
        }
        long now = data.getCurrentMillis();
        long[] state = MANUAL_STATE.get(vehicleId);
        if (state == null || state[0] != platformId || state[2] > now + FUTURE_SLACK_MILLIS) {
            // First close attempt at this platform visit: roll exactly once.
            // (Movement clears the entry, so a loop route revisiting the same
            // platform rolls again next time around.)
            state = new long[]{platformId, roll(config, now, vehicleId), now};
            MANUAL_STATE.put(vehicleId, state);
        }
        if (state[1] > now && !backstopExpired(config, state, now)) {
            // Stuck: bounce this close attempt straight back open.
            reopenAndMark(vehicleId, vehicleExtraData, state[1] - now);
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * True when this obstruction has outlived any duration the config could
     * legitimately have produced — force-release it (and log, so a wedge is
     * visible in the log instead of on a stranded platform).
     */
    private static boolean backstopExpired(AddonServerConfig.DoorObstruction config, long[] state, long now) {
        long cap = 1_000L * Math.max(config.minSeconds, config.maxSeconds) + BACKSTOP_GRACE_MILLIS;
        if (now - state[2] < cap) {
            return false;
        }
        StationAnnouncer.LOGGER.warn(
                "Door obstruction exceeded its cap ({} ms past roll) — force-releasing; report this",
                now - state[2]);
        com.stationannouncer.mtraddon.dispatch.DispatchEvents.alert("door_backstop", "bad", 0, 0,
                "A door obstruction exceeded its cap and was force-released (" + ((now - state[2]) / 1000) + "s past roll)");
        state[1] = 0;
        return true;
    }

    /**
     * Rolls the obstruction chance; returns the sim-clock millis the doors stay
     * stuck until, or 0 for a clean close. ThreadLocalRandom because each
     * simulator thread rolls independently.
     */
    private static long roll(AddonServerConfig.DoorObstruction config, long now, long vehicleId) {
        if (config.chancePercent <= 0 || ThreadLocalRandom.current().nextInt(100) >= config.chancePercent) {
            return 0;
        }
        int min = Math.min(config.minSeconds, config.maxSeconds);
        int max = Math.max(config.minSeconds, config.maxSeconds);
        long durationMillis = 1_000L * ThreadLocalRandom.current().nextLong(min, max + 1L);
        StationAnnouncer.LOGGER.debug("Door obstruction on vehicle {} for {} ms", vehicleId, durationMillis);
        return now + durationMillis;
    }

    /** Re-asserts open doors on the stuck vehicle and records it for the S2C broadcast. */
    private static void reopenAndMark(long vehicleId, VehicleExtraData vehicleExtraData, long remainingMillis) {
        // Same safety argument as the hold mixin: the vehicle mutating its own
        // VehicleExtraData from inside its own simulate call, exactly like stock
        // simulateStopped; the doorTarget flip reaches clients through the
        // existing writeVehiclePositions -> checkForUpdate -> client.update flow.
        ((VehicleExtraDataAccessor) (Object) vehicleExtraData).stationAnnouncer$openDoors();
        OBSTRUCTED.put(vehicleId, System.currentTimeMillis() + remainingMillis);
    }

    /** Same stopped-at-a-platform test as the hold engine; returns 0 when not at one. */
    private static long stoppedPlatformId(VehicleExtraData vehicleExtraData, double railProgress) {
        ObjectImmutableList<PathData> path = vehicleExtraData.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        if (index <= 0 || index >= path.size()) {
            return 0;
        }
        if (railProgress != path.get(index).getStartDistance()) {
            return 0;
        }
        PathData platformSegment = path.get(index - 1);
        return platformSegment.getDwellTime() > 0 ? platformSegment.getSavedRailBaseId() : 0;
    }

    /**
     * The vehicles with something currently stuck in their doors, for the ticker's
     * S2C broadcast. Server thread; prunes expired entries as it goes. Empty
     * whenever the feature is off, because nothing ever writes an entry then.
     */
    public static Set<Long> obstructedVehicleIds() {
        if (OBSTRUCTED.isEmpty()) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        Set<Long> obstructed = new HashSet<>();
        OBSTRUCTED.forEach((vehicleId, until) -> {
            if (until > now) {
                obstructed.add(vehicleId);
            } else {
                OBSTRUCTED.remove(vehicleId, until);
            }
        });
        return obstructed;
    }

    /** Server thread: SERVER_STOPPED. */
    public static void clearRuntimeState() {
        AUTO_STATE.clear();
        MANUAL_STATE.clear();
        OBSTRUCTED.clear();
    }
}
