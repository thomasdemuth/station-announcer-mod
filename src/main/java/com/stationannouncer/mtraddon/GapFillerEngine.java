package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.VehicleExtraDataAccessor;
import com.stationannouncer.mtr.GapFillerPhase;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gap fillers: the moving platform edges of 14 St–Union Square and the old
 * South Ferry loop, sequenced against MTR's trains.
 *
 * <p>A stop at a platform with fillers ({@link GapFillerStore#active()}) runs:
 * <ol>
 *   <li><b>EXTENDING</b> from the moment the train comes to rest — the doors
 *       stay SHUT; MTR's own open at 1 s is refused
 *       ({@link #mayOpenDoors}, via the redirect in VehicleMixin);</li>
 *   <li><b>EXTENDED</b>: the doors open (MTR's flow, or ours when the stop
 *       has already outrun MTR's door window) and stay open until
 *       {@code closeAt};</li>
 *   <li>the doors close — our own close, early enough that the whole
 *       sequence still ends at the scheduled departure — then, once the door
 *       animation ({@link #DOOR_MOVE_MILLIS}) is over and nobody is standing on
 *       a plate, <b>RETRACTING</b>;</li>
 *   <li><b>RETRACTED</b>: only now may {@code startUp} move the train (the
 *       real fillers are interlocked with the signals the same way).</li>
 * </ol>
 * The stop lasts at least the platform's <b>minimum dwell</b> — and never less
 * than the sequence itself needs — whatever the timetable's dwell is.
 *
 * <p>Holds and door obstructions still work: a hold that engages while the
 * fillers are out keeps the doors open; one that engages after they started
 * back re-extends them first and only then reopens the doors. An obstruction is
 * rolled at our door-close moment (the fillers are out, so bouncing the doors
 * open is safe) and never again once they are retracting.</p>
 *
 * <p>Manual driving: the fillers extend when the train stops; opening the
 * doors early (or while they retract) bounces the doors shut and sends them
 * back out; power is refused until the doors are closed and the fillers are
 * away. The driver's close — or the attempt to leave — starts the retraction.</p>
 *
 * <p><b>Threads:</b> {@link #tickStopped}, {@link #mayOpenDoors},
 * {@link #holdMayReopen} and {@link #departureGate} run on the vehicle's
 * SIMULATOR thread inside its own simulate call (the vehicle mutating its own
 * doors, exactly like stock simulateStopped). {@link #phaseFor} and
 * {@link #markOccupied} are the server thread's side, through concurrent maps.
 * <b>Clock:</b> the simulator's {@code currentMillis} — a state stamped more
 * than {@link #FUTURE_SLACK_MILLIS} ahead of it is Instant Deploy debris
 * (CLAUDE.md, 2026-08-19) and starts the stop afresh.</p>
 */
public final class GapFillerEngine {
    /** MTR's door animation length ({@code Vehicle.DOOR_MOVE_TIME}, 4.0.1 bytecode). */
    public static final long DOOR_MOVE_MILLIS = 3_200;
    /** The shortest doors-open window a stop is ever squeezed to. */
    public static final long MIN_DOORS_OPEN_MILLIS = 4_000;
    /** A tick gap longer than this means the vehicle left: the next stop starts afresh. */
    private static final long NEW_STOP_GAP_MILLIS = 3_000;
    private static final long FUTURE_SLACK_MILLIS = 30_000;
    /** A hold re-asserts every tick; this long after the last one, the hold is over. */
    private static final long HOLD_GRACE_MILLIS = 1_500;
    /** Someone on a plate delays retraction — but never for longer than this. */
    private static final long OCCUPANCY_CAP_MILLIS = 30_000;
    /** A plate counts as occupied for this long after the world last saw a body on it. */
    private static final long OCCUPIED_FRESH_MILLIS = 750;
    /** A platform signal nobody has touched for this long is a vanished train. */
    private static final long SIGNAL_STALE_MILLIS = 3_000;
    /** Dev runs only: one log line per phase change, so a stop can be read off the server log. */
    private static final boolean DEV_LOG = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();

    /** What the startUp HEAD hook should do. */
    public enum Gate {
        /** Not a gap-filler stop: carry on with the stock flow (and the obstruction roll). */
        NONE,
        /** Refuse this departure attempt. */
        CANCEL,
        /** Sequence finished — let startUp through, and skip the obstruction roll. */
        PROCEED
    }

    /** One vehicle's stop at a gap-filler platform. Written only by its simulator thread. */
    private static final class Stop {
        final long platformId;
        final long stopStart;
        final long extendMs;
        final long retractMs;
        /** Sim-clock millis at which our own door close is due. */
        final long closeAt;
        long lastSeen;
        GapFillerPhase phase = GapFillerPhase.EXTENDING;
        long phaseStart;
        /** When the doors were closed for the retraction, -1 = not yet. */
        long closedAt = -1;
        /** Last tick a hold rule kept this train, -1 = never. */
        long heldAt = -1;
        boolean doorsOpened;
        long lastSeenWall;
        /** Dev log only: the door state last reported. */
        boolean devDoorsOpen;

        Stop(long platformId, long now, GapFillerStore.Settings settings, long scheduledDwell) {
            this.platformId = platformId;
            this.stopStart = now;
            this.extendMs = settings.extendMs();
            this.retractMs = settings.retractMs();
            long sequence = extendMs + MIN_DOORS_OPEN_MILLIS + DOOR_MOVE_MILLIS + retractMs;
            long dwell = Math.max(scheduledDwell, Math.max(settings.minDwellMs(), sequence));
            this.closeAt = now + dwell - retractMs - DOOR_MOVE_MILLIS;
            this.phaseStart = now;
        }
    }

    /** What the world needs to know about one platform's fillers. */
    public static final class Signal {
        volatile GapFillerPhase phase = GapFillerPhase.RETRACTED;
        volatile long touchedWall;
    }

    private static final ConcurrentHashMap<Long, Stop> STOPS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Signal> SIGNALS = new ConcurrentHashMap<>();
    /** platform id → wall millis a body was last seen on one of its plates. */
    private static final ConcurrentHashMap<Long, Long> OCCUPIED = new ConcurrentHashMap<>();

    private GapFillerEngine() {
    }

    // ------------------------------------------------- simulator-thread side

    /**
     * HEAD of {@code Vehicle.simulateStopped}, every tick a vehicle stands
     * still. Drives the phase machine; bails out on one volatile read when no
     * platform has fillers.
     */
    public static void tickStopped(long vehicleId, VehicleExtraData vehicleExtraData, double railProgress, Data data) {
        Map<Long, GapFillerStore.Settings> active = GapFillerStore.active();
        if (active.isEmpty()) {
            if (!STOPS.isEmpty()) {
                STOPS.remove(vehicleId);
            }
            return;
        }
        if (!(data instanceof Simulator)) {
            return; // clientside vehicles run the same method
        }
        PathData platformSegment = stoppedPlatformSegment(vehicleExtraData, railProgress);
        long platformId = platformSegment == null ? 0 : platformSegment.getSavedRailBaseId();
        GapFillerStore.Settings settings = platformId == 0 ? null : active.get(platformId);
        if (settings == null) {
            STOPS.remove(vehicleId);
            return;
        }
        long now = data.getCurrentMillis();
        Stop stop = STOPS.get(vehicleId);
        if (stop == null || stop.platformId != platformId || now - stop.lastSeen > NEW_STOP_GAP_MILLIS
                || stop.lastSeen > now + FUTURE_SLACK_MILLIS) {
            stop = new Stop(platformId, now, settings, platformSegment.getDwellTime());
            STOPS.put(vehicleId, stop);
            publish(stop, GapFillerPhase.EXTENDING);
            if (DEV_LOG) {
                StationAnnouncer.LOGGER.info("[gap filler] vehicle {} stopped at platform {}: timetable dwell {} ms, close due +{} ms, doors {}",
                        vehicleId, platformId, platformSegment.getDwellTime(), stop.closeAt - now,
                        vehicleExtraData.getDoorMultiplier() > 0 ? "OPEN" : "shut");
            }
        }
        stop.lastSeen = now;
        stop.lastSeenWall = System.currentTimeMillis();
        advance(vehicleId, stop, vehicleExtraData, railProgress, data, now);
        touch(stop);
        if (DEV_LOG && (vehicleExtraData.getDoorMultiplier() > 0) != stop.devDoorsOpen) {
            stop.devDoorsOpen = !stop.devDoorsOpen;
            StationAnnouncer.LOGGER.info("[gap filler] platform {} +{} ms: doors {} (fillers {})", stop.platformId,
                    now - stop.stopStart, stop.devDoorsOpen ? "OPEN" : "SHUT", stop.phase);
        }
    }

    private static void advance(long vehicleId, Stop stop, VehicleExtraData extra, double railProgress,
                                Data data, long now) {
        boolean manual = extra.getIsCurrentlyManual();
        boolean doorsOpen = extra.getDoorMultiplier() > 0;
        switch (stop.phase) {
            case EXTENDING -> {
                if (doorsOpen) {
                    close(extra); // nobody opens onto a gap
                }
                if (now - stop.phaseStart >= stop.extendMs) {
                    setPhase(stop, GapFillerPhase.EXTENDED, now);
                }
            }
            case EXTENDED -> {
                if (doorsOpen) {
                    stop.doorsOpened = true;
                    stop.closedAt = -1; // reopened (hold, obstruction, driver): start the close afresh
                }
                if (!manual && !doorsOpen && stop.closedAt < 0 && now < stop.closeAt) {
                    // Fillers out, doors still shut: open them ourselves. MTR would in its
                    // own window, but a short timetable dwell may already have closed that
                    // window, and a red signal ahead keeps MTR from even trying startUp.
                    open(extra);
                    stop.doorsOpened = true;
                    return;
                }
                if (!manual && stop.closedAt < 0 && now >= stop.closeAt
                        && (stop.heldAt < 0 || now - stop.heldAt > HOLD_GRACE_MILLIS)) {
                    // Our door close. The fillers are out, so an obstruction bouncing
                    // the doors back open is safe here — and this is the only place
                    // it may be rolled at a gap-filler stop.
                    if (doorsOpen && DoorObstructionEngine.shouldObstructDeparture(vehicleId, extra, railProgress, data)) {
                        return;
                    }
                    close(extra);
                    stop.closedAt = now;
                    return;
                }
                if (manual && !doorsOpen && stop.doorsOpened && stop.closedAt < 0) {
                    stop.closedAt = now; // the driver shut the doors: the fillers may go
                }
                if (stop.closedAt >= 0 && !doorsOpen && now - stop.closedAt >= DOOR_MOVE_MILLIS) {
                    if (occupied(stop.platformId) && now - stop.closedAt < DOOR_MOVE_MILLIS + OCCUPANCY_CAP_MILLIS) {
                        return; // someone is standing on a plate
                    }
                    if (now - stop.closedAt >= DOOR_MOVE_MILLIS + OCCUPANCY_CAP_MILLIS) {
                        StationAnnouncer.LOGGER.warn("Gap filler on platform {} retracting after {} s occupied",
                                stop.platformId, OCCUPANCY_CAP_MILLIS / 1000);
                    }
                    setPhase(stop, GapFillerPhase.RETRACTING, now);
                }
            }
            case RETRACTING, RETRACTED -> {
                if (doorsOpen) {
                    // Doors must never stand open over a retracted edge: shut them and
                    // bring the fillers back out (a manual driver reopening, a hold).
                    close(extra);
                    stop.closedAt = -1;
                    setPhase(stop, GapFillerPhase.EXTENDING, now);
                } else if (stop.phase == GapFillerPhase.RETRACTING && now - stop.phaseStart >= stop.retractMs) {
                    setPhase(stop, GapFillerPhase.RETRACTED, now);
                }
            }
        }
    }

    /**
     * The one {@code openDoors()} call inside {@code simulateStopped} (MTR's
     * "doors open 1 s after stopping"): allowed only while the fillers are
     * fully out and our close has not come yet. True for every non-filler stop.
     */
    public static boolean mayOpenDoors(long vehicleId) {
        if (STOPS.isEmpty()) {
            return true;
        }
        Stop stop = STOPS.get(vehicleId);
        if (stop == null) {
            return true;
        }
        return stop.phase == GapFillerPhase.EXTENDED && stop.closedAt < 0 && stop.lastSeen < stop.closeAt;
    }

    /**
     * True while this vehicle's filler sequence is unfinished. VehicleMixin then
     * keeps MTR's own {@code doorCooldown} above zero, which stops the train at
     * the source: stock {@code startUp} will not move while it is non-zero, and —
     * the reason this exists — the terminus reversal in {@code simulateStopped}
     * (railProgress jumps a train length and {@code reversed} flips BEFORE
     * startUp is even called) only runs once it is zero. Cancelling startUp alone
     * would let that reversal through and strand the stop's state.
     */
    public static boolean blocksDeparture(long vehicleId) {
        if (STOPS.isEmpty()) {
            return false;
        }
        Stop stop = STOPS.get(vehicleId);
        return stop != null && stop.phase != GapFillerPhase.RETRACTED;
    }

    /**
     * A hold rule wants this train kept with its doors open. True = go ahead
     * and reopen; false = not onto a gap — the fillers are sent back out first
     * and the next held tick reopens once they are there.
     */
    public static boolean holdMayReopen(long vehicleId, Data data) {
        if (STOPS.isEmpty()) {
            return true;
        }
        Stop stop = STOPS.get(vehicleId);
        if (stop == null || stop.lastSeen != data.getCurrentMillis()) {
            return true;
        }
        long now = stop.lastSeen;
        stop.heldAt = now;
        switch (stop.phase) {
            case EXTENDED -> {
                stop.closedAt = -1;
                return true;
            }
            case RETRACTING, RETRACTED -> {
                stop.closedAt = -1;
                setPhase(stop, GapFillerPhase.EXTENDING, now);
                touch(stop);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * HEAD of {@code startUp}, after the hold check declined to hold. Decides
     * whether this departure attempt may proceed at a gap-filler stop.
     */
    public static Gate departureGate(long vehicleId, VehicleExtraData extra, Data data) {
        if (STOPS.isEmpty()) {
            return Gate.NONE;
        }
        Stop stop = STOPS.get(vehicleId);
        if (stop == null || stop.lastSeen != data.getCurrentMillis()) {
            return Gate.NONE;
        }
        long now = stop.lastSeen;
        boolean doorsOpen = extra.getDoorMultiplier() > 0;
        switch (stop.phase) {
            case EXTENDED -> {
                if (extra.getIsCurrentlyManual()) {
                    // The driver wants to go: shut the doors, start the retraction.
                    if (doorsOpen) {
                        close(extra);
                    }
                    if (stop.closedAt < 0) {
                        stop.closedAt = now;
                    }
                } else if (stop.closedAt < 0 && now < stop.closeAt) {
                    // The stop outlasts MTR's own door window (minimum dwell, or doors
                    // that opened late behind the fillers): keep them open ourselves.
                    open(extra);
                }
                return Gate.CANCEL;
            }
            case RETRACTED -> {
                return doorsOpen ? Gate.CANCEL : Gate.PROCEED;
            }
            default -> {
                return Gate.CANCEL;
            }
        }
    }

    // ---------------------------------------------------- server-thread side

    /** The phase a platform's filler blocks should show. */
    public static GapFillerPhase phaseFor(long platformId) {
        Signal signal = SIGNALS.get(platformId);
        if (signal == null) {
            return GapFillerPhase.RETRACTED;
        }
        GapFillerPhase phase = signal.phase;
        if (phase != GapFillerPhase.RETRACTED
                && System.currentTimeMillis() - signal.touchedWall > SIGNAL_STALE_MILLIS) {
            // The train vanished mid-stop (removed, depot reset): bring the plates home.
            return phase == GapFillerPhase.RETRACTING ? GapFillerPhase.RETRACTING : GapFillerPhase.RETRACTED;
        }
        return phase;
    }

    /** A filler block of this platform has somebody standing on its plate. */
    public static void markOccupied(long platformId) {
        OCCUPIED.put(platformId, System.currentTimeMillis());
    }

    /** Server thread, every few seconds: forget vehicles that left long ago. */
    public static void prune() {
        long wall = System.currentTimeMillis();
        STOPS.entrySet().removeIf(entry -> wall - entry.getValue().lastSeenWall > 60_000);
        OCCUPIED.entrySet().removeIf(entry -> wall - entry.getValue() > 60_000);
    }

    public static void clearRuntimeState() {
        STOPS.clear();
        SIGNALS.clear();
        OCCUPIED.clear();
    }

    // -------------------------------------------------------------- internals

    private static boolean occupied(long platformId) {
        Long seen = OCCUPIED.get(platformId);
        return seen != null && System.currentTimeMillis() - seen < OCCUPIED_FRESH_MILLIS;
    }

    private static void setPhase(Stop stop, GapFillerPhase phase, long now) {
        stop.phase = phase;
        stop.phaseStart = now;
        publish(stop, phase);
        if (DEV_LOG) {
            StationAnnouncer.LOGGER.info("[gap filler] platform {} +{} ms: {} (doors closed at {} ms, close due {} ms)",
                    stop.platformId, now - stop.stopStart, phase,
                    stop.closedAt < 0 ? -1 : stop.closedAt - stop.stopStart, stop.closeAt - stop.stopStart);
        }
    }

    private static void publish(Stop stop, GapFillerPhase phase) {
        Signal signal = SIGNALS.computeIfAbsent(stop.platformId, id -> new Signal());
        signal.phase = phase;
        signal.touchedWall = System.currentTimeMillis();
    }

    private static void touch(Stop stop) {
        Signal signal = SIGNALS.get(stop.platformId);
        if (signal != null) {
            signal.touchedWall = System.currentTimeMillis();
        }
    }

    private static void open(VehicleExtraData extra) {
        ((VehicleExtraDataAccessor) (Object) extra).stationAnnouncer$openDoors();
    }

    private static void close(VehicleExtraData extra) {
        ((VehicleExtraDataAccessor) (Object) extra).stationAnnouncer$closeDoors();
    }

    /** Same stopped-at-a-platform test as the hold and obstruction engines; null when not at one. */
    private static PathData stoppedPlatformSegment(VehicleExtraData vehicleExtraData, double railProgress) {
        ObjectImmutableList<PathData> path = vehicleExtraData.immutablePath;
        int index = Utilities.getIndexFromConditionalList(path, railProgress);
        if (index <= 0 || index >= path.size()) {
            return null;
        }
        if (railProgress != path.get(index).getStartDistance()) {
            return null;
        }
        PathData platformSegment = path.get(index - 1);
        return platformSegment.getDwellTime() > 0 && platformSegment.getSavedRailBaseId() != 0 ? platformSegment : null;
    }
}
