package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.minecraft.server.MinecraftServer;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Depot groups — "these depots must not dispatch together" (Thomas, 2026-08-08).
 *
 * <p>MTR writes a depot's whole departure timetable in
 * {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()}: it builds a
 * sorted list of departure times for the day (from the depot's hourly frequencies, or
 * the real-time list, or the continuous-movement slots) and hands each one to a siding
 * through {@code Siding.addDeparture(long)}. Two depots serving the same corridor with
 * the same frequency therefore emit departures at the SAME instants — trains leave in
 * lockstep. This engine phase-shifts one depot's entire timetable against the other's.</p>
 *
 * <p><b>The offset.</b> Member {@code i} of a group of {@code N} gets
 * {@code round(i / N × interval)}, where {@code interval} is that depot's own mean
 * scheduled departure interval derived by
 * {@link AnalyticsRecorder#depotDepartureIntervalMillis} — the SAME reversal of MTR's
 * frequency math the analytics feature already does (shared, not re-derived). Member 0
 * is the reference and is never shifted, so a group of two puts the second depot exactly
 * half a headway behind the first.</p>
 *
 * <p><b>The wrap rule: there is none, deliberately.</b> The offset is added to EVERY
 * departure the depot writes in one generation pass, so every gap between its own
 * departures — and the repeat cycle length, {@code gameMillisPerDay × repeatDepartures},
 * which MTR computes independently of the departure values — is unchanged; the timetable
 * simply slides in phase. {@code Siding.matchDeparture} anchors the cycle on
 * {@code departures.get(0)}, which slides with it, so nothing has to be taken modulo
 * anything and no departure can fall off either end of the day. The offset is clamped to
 * {@code [0, interval)}: never negative (a departure could otherwise be pushed before
 * {@code getMillisOfGameMidnight()}), and never a whole headway (which would be a no-op
 * that merely renumbered the runs).</p>
 *
 * <p><b>Hook:</b> {@link com.stationannouncer.mixin.DepotMixin} computes the offset once
 * at the HEAD of {@code generatePlatformDirectionsAndWriteDeparturesToSidings} and a
 * {@code @Redirect} on the single {@code Siding.addDeparture} call site adds it. Both
 * bytecode-verified against MTR FABRIC-4.0.1+1.20.4.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off) — departures are written from {@code Depot.init},
 * {@code Depot.finishGeneratingPath} and {@code Simulator.setGameTime}, never per tick.
 * Reads the volatile config, the volatile immutable {@link AddonSnapshots#depotGroups()}
 * snapshot and the simulator's own data. The only mutable state is one
 * {@link ConcurrentHashMap} of last-computed offsets, kept purely so the GUI can show
 * them; it is never read by the simulation.</p>
 *
 * <p><b>Cost when idle:</b> feature disabled → one field read; no groups → one volatile
 * read + {@code isEmpty}; groups configured elsewhere → one {@code get(long)} per depot
 * generation. The per-departure redirect is one field read plus an add.</p>
 */
public final class DepotGroupEngine {
    /**
     * depot id → the offset last computed for it, in millis. Written by simulator
     * threads (one writer per depot, since a depot belongs to one simulator) and by the
     * server-thread refresh below; read on the server thread when building the S2C sync.
     * Purely informational — the simulation never reads it back.
     */
    private static final ConcurrentHashMap<Long, Long> LAST_OFFSET = new ConcurrentHashMap<>();

    private DepotGroupEngine() {
    }

    // ------------------------------------------------------------------ hooks

    /**
     * {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings} HEAD: the phase
     * offset to add to every departure this depot is about to write. {@code 0} means
     * "leave MTR's timetable exactly as it is", which is the answer for every depot that
     * is not in a multi-member group, for real-time and continuous-movement depots, and
     * whenever the feature is off.
     */
    public static long departureOffsetMillis(Depot depot, Data data) {
        // 1. Feature toggle (config object is cached after first load; field read).
        if (!AddonServerConfig.get().depotGroups.enabled) {
            return 0;
        }
        // 2. Any groups at all? (volatile read + isEmpty)
        Long2ObjectOpenHashMap<int[]> membership = AddonSnapshots.depotGroups();
        if (membership.isEmpty() || depot == null || !(data instanceof Simulator)) {
            return 0;
        }
        // 3. Is THIS depot in a group? {slot, groupSize}
        int[] slot = membership.get(depot.getId());
        if (slot == null || slot[1] < 2 || slot[0] <= 0) {
            // Not grouped, a group of one, or the reference member — no shift.
            // (The reference member's 0 is still recorded so the GUI can show "+0s".)
            if (slot != null) {
                LAST_OFFSET.put(depot.getId(), 0L);
            }
            return 0;
        }
        long offset = offsetFor((Simulator) data, depot, slot[0], slot[1]);
        LAST_OFFSET.put(depot.getId(), offset);
        return offset;
    }

    /**
     * The clamped phase offset for member {@code index} of a group of {@code size} —
     * the one place the number is computed, shared by the departure hook and by the
     * GUI-facing refresh below so the two can never disagree.
     */
    private static long offsetFor(Simulator simulator, Depot depot, int index, int size) {
        long interval = AnalyticsRecorder.depotDepartureIntervalMillis(simulator, depot);
        if (interval <= 0) {
            return 0; // not derivable (real-time timetable, continuous movement, no frequency)
        }
        long offset = Math.round((double) index / size * interval);
        if (offset <= 0) {
            return 0;
        }
        // Never a whole headway: that would be an identical timetable one run later.
        return Math.min(offset, interval - 1);
    }

    // -------------------------------------------------------- GUI-facing state

    /**
     * Server thread: the offsets the simulators last computed, for the S2C sync. Depots
     * that have not (re)generated since the group was created are simply absent, and the
     * GUI says "at the next generation".
     */
    public static Map<Long, Long> offsetsView() {
        return new java.util.HashMap<>(LAST_OFFSET);
    }

    /**
     * Server thread: recompute every grouped depot's offset, and optionally re-write those
     * depots' departure timetables so a change takes effect NOW instead of at the next
     * path generation. Hops onto each simulator thread (that is where depot frequencies,
     * {@code gameMillisPerDay} and the sidings live), then back to the server thread to
     * rebroadcast the sync. Cold path — server start and group edits only — and a no-op
     * when the feature is off, when nothing is grouped or when MTR has no simulation.
     *
     * <p><b>Why re-writing departures is safe:</b> MTR's own
     * {@code Simulator.setGameTime} calls
     * {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()} on every depot
     * at arbitrary moments (any {@code /time set}), and the method starts by clearing each
     * siding's departure list ({@code Siding.startGeneratingDepartures}), so it is
     * idempotent by construction. It is also why the boot case NEEDS this: MTR is our
     * dependency, so its SERVER_STARTED handler — which constructs the simulators and runs
     * {@code Depot.init()} — has already written the day's departures by the time our
     * store loads, and without a rewrite the stagger would not appear until something else
     * regenerated the depot. Wrapped in try/catch: a failure leaves MTR's own timetable in
     * place.</p>
     */
    public static void refreshOffsets(MinecraftServer server, boolean rewriteDepartures) {
        if (!AddonServerConfig.get().depotGroups.enabled) {
            LAST_OFFSET.clear();
            return;
        }
        Long2ObjectOpenHashMap<int[]> membership = AddonSnapshots.depotGroups();
        // Depots to touch: those grouped now, plus those we staggered before (a depot just
        // REMOVED from a group still carries the old offset in its departure list and has
        // to be re-written back to MTR's own timings). Nothing else is disturbed.
        java.util.Set<Long> mutableAffected = new java.util.HashSet<>(LAST_OFFSET.keySet());
        for (long depotId : membership.keySet()) {
            mutableAffected.add(depotId);
        }
        // Drop offsets of depots that are no longer grouped (the GUI would show a stale one).
        LAST_OFFSET.keySet().removeIf(depotId -> !membership.containsKey(depotId.longValue()));
        if (mutableAffected.isEmpty()) {
            return;
        }
        // Immutable before it crosses onto the simulator threads.
        java.util.Set<Long> affected = java.util.Set.copyOf(mutableAffected);
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            return;
        }
        int[] pending = {simulators.size()};
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    for (Depot depot : simulator.depots) {
                        if (depot == null || !affected.contains(depot.getId())) {
                            continue;
                        }
                        int[] slot = membership.get(depot.getId());
                        if (slot != null) {
                            LAST_OFFSET.put(depot.getId(),
                                    slot[1] < 2 || slot[0] <= 0 ? 0L : offsetFor(simulator, depot, slot[0], slot[1]));
                        }
                        if (rewriteDepartures) {
                            // Re-runs the departure writer; our HEAD hook resolves the
                            // (possibly now zero) offset again on the way through.
                            depot.generatePlatformDirectionsAndWriteDeparturesToSidings();
                        }
                    }
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Depot group offsets could not be applied", t);
                }
                server.execute(() -> {
                    if (--pending[0] <= 0) {
                        DepotGroupNetworking.broadcastDepotGroups(server);
                    }
                });
            });
        }
    }

    /** SERVER_STOPPED / world load. */
    public static void clearRuntimeState() {
        LAST_OFFSET.clear();
    }

    /** Formats an offset for the GUI hint, e.g. {@code "+2m 30s"} / {@code "+0s"}. */
    public static String formatOffset(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? "+" + minutes + "m " + seconds + "s" : "+" + seconds + "s";
    }
}
