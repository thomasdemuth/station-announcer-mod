package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.minecraft.server.MinecraftServer;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.simulation.Simulator;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-depot departure delays — "delay this depot's departures by X" (Thomas, 2026-09-29).
 *
 * <p>MTR writes a depot's whole departure timetable in
 * {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()} and hands each
 * departure to a siding through {@code Siding.addDeparture(long)}.
 * {@link com.stationannouncer.mixin.DepotMixin} resolves this depot's delay once at the
 * HEAD of that method and adds it to every departure at the single {@code addDeparture}
 * call site, so the whole timetable slides: the gaps between the depot's own trains and
 * MTR's repeat cycle are untouched, only the phase moves.</p>
 *
 * <p><b>History.</b> Until 2026-09-29 depot GROUPS shifted member {@code i} of {@code N}
 * by {@code i/N × headway} automatically. That spaced trains at the DEPOT, but what
 * matters is the spacing where lines share track, and each depot is a different distance
 * from it — the interline tooling ({@code mtraddon.interline}) now works the delays out
 * at the shared section and SUGGESTS them; groups only say which depots belong together.
 * {@link #startup} converts the old automatic shift into stored delays once per world, so
 * upgrading moves no train.</p>
 *
 * <p><b>The value applied.</b> The stored delay modulo the game day, for depots whose
 * departures come from frequency sliders. Real-time-timetable depots and continuous
 * movement (cable cars) are never shifted. A delay of more than one headway is legal: it
 * lands on the same phase as {@code delay mod headway} and the GUI says so.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off). Reads the volatile config and the volatile
 * immutable {@link AddonSnapshots#depotDelays()} snapshot. The only mutable state is one
 * {@link ConcurrentHashMap} of the delays last applied, read by the analyzer and GUI to
 * tell "applied" from "waiting for the next rewrite".</p>
 *
 * <p><b>Cost when idle:</b> feature disabled → one field read; no delays → one volatile
 * read + {@code isEmpty}; otherwise one {@code get(long)} per depot generation. The
 * per-departure redirect is one field read plus an add.</p>
 */
public final class DepotGroupEngine {
    /**
     * depot id → the delay last written into its departures, in millis. Written by
     * simulator threads (one writer per depot) and by the refresh below; purely
     * informational — the simulation never reads it back.
     */
    private static final ConcurrentHashMap<Long, Long> LAST_APPLIED = new ConcurrentHashMap<>();

    private DepotGroupEngine() {
    }

    // ------------------------------------------------------------------ hooks

    /**
     * {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings} HEAD: the delay
     * to add to every departure this depot is about to write. {@code 0} leaves MTR's
     * timetable exactly as it is.
     */
    public static long departureOffsetMillis(Depot depot, Data data) {
        if (!AddonServerConfig.get().depotGroups.enabled || depot == null || !(data instanceof Simulator simulator)) {
            return 0;
        }
        Long2LongOpenHashMap delays = AddonSnapshots.depotDelays();
        long applied = delays.isEmpty() ? 0 : appliedDelay(simulator, depot, delays.get(depot.getId()));
        if (applied > 0 || LAST_APPLIED.containsKey(depot.getId())) {
            LAST_APPLIED.put(depot.getId(), applied);
        }
        return applied;
    }

    /**
     * The delay actually written for a stored value: 0 for depots whose timetable is not
     * built from frequency sliders, else the value modulo the game day (a full day is a
     * no-op, and keeping the shift under one day keeps every departure inside MTR's own
     * repeat window).
     */
    public static long appliedDelay(Simulator simulator, Depot depot, long storedMillis) {
        if (storedMillis <= 0 || !isShiftable(simulator, depot)) {
            return 0;
        }
        long day = simulator.getGameMillisPerDay();
        return day > 0 ? storedMillis % day : 0;
    }

    /** Frequency-slider depots only: real-time timetables and continuous movement are never shifted. */
    public static boolean isShiftable(Simulator simulator, Depot depot) {
        return AnalyticsRecorder.depotDepartureIntervalMillis(simulator, depot) > 0;
    }

    /** Any thread: the delay last written into a depot's departures, or -1 when never written. */
    public static long lastApplied(long depotId) {
        Long value = LAST_APPLIED.get(depotId);
        return value == null ? -1 : value;
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * SERVER_STARTED, after the store loaded. MTR (our dependency) has already written
     * today's departures before our store existed, so the delays must be written once
     * now. First, once per world, the old automatic group stagger is converted into
     * stored delays so upgrading does not move a single train.
     */
    public static void startup(MinecraftServer server) {
        startup(server, 0);
    }

    /**
     * MTR builds its simulators during ITS server-start handling and our capture of them
     * ({@code MainSimulatorsMixin}) is not always in place when our SERVER_STARTED runs —
     * found on the rig 2026-09-29, where the list was still null. So wait for them (once a
     * second, for up to two minutes) before migrating or rewriting anything.
     */
    private static void startup(MinecraftServer server, int attempt) {
        if (!AddonServerConfig.get().depotGroups.enabled) {
            return;
        }
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            if (attempt < 120) {
                com.stationannouncer.mtraddon.interline.InterlineService.schedule(20, () -> startup(server, attempt + 1));
            }
            return;
        }
        if (AddonStore.depotDelaysMigrated()) {
            refreshOffsets(server, Set.of());
            return;
        }
        Map<Long, DepotGroup> groups = AddonStore.depotGroupsView();
        if (groups.isEmpty()) {
            AddonStore.markDepotDelaysMigrated();
            refreshOffsets(server, Set.of());
            return;
        }
        // Legacy slot of every grouped depot: member i of N (first membership wins).
        Map<Long, int[]> slots = new HashMap<>();
        for (DepotGroup group : groups.values()) {
            long[] members = group.depotIds();
            for (int i = 0; i < members.length; i++) {
                slots.putIfAbsent(members[i], new int[]{i, members.length});
            }
        }
        Map<Long, int[]> frozenSlots = Map.copyOf(slots);
        Map<Long, Long> legacy = new ConcurrentHashMap<>();
        int[] pending = {simulators.size()};
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    for (Depot depot : simulator.depots) {
                        int[] slot = depot == null ? null : frozenSlots.get(depot.getId());
                        if (slot == null || slot[0] <= 0 || slot[1] < 2) {
                            continue;
                        }
                        long interval = AnalyticsRecorder.depotDepartureIntervalMillis(simulator, depot);
                        long offset = interval <= 0 ? 0 : Math.min(interval - 1, Math.round((double) slot[0] / slot[1] * interval));
                        if (offset > 0) {
                            legacy.put(depot.getId(), offset);
                        }
                    }
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Depot group stagger could not be migrated", t);
                }
                server.execute(() -> {
                    if (--pending[0] > 0) {
                        return;
                    }
                    Map<Long, Long> existing = AddonStore.depotDelaysView();
                    Map<Long, Long> toStore = new HashMap<>();
                    legacy.forEach((depotId, offset) -> {
                        if (!existing.containsKey(depotId)) {
                            toStore.put(depotId, offset);
                        }
                    });
                    AddonStore.setDepotDelays(toStore);
                    AddonStore.markDepotDelaysMigrated();
                    StationAnnouncer.LOGGER.info("Converted the old depot-group stagger into {} manual depot delay(s)", toStore.size());
                    refreshOffsets(server, Set.of());
                });
            });
        }
    }

    /**
     * Server thread: re-write the departure timetables of every delayed depot (plus the
     * ones in {@code alsoDepots}, e.g. a delay just cleared, or a depot whose frequency
     * just changed), so a change takes effect NOW instead of at the next path
     * generation. Hops onto each simulator thread and back.
     *
     * <p><b>Why re-writing departures is safe:</b> MTR's own {@code Simulator.setGameTime}
     * calls {@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings()} on every
     * depot at arbitrary moments (any {@code /time set}), and the method starts by
     * clearing each siding's departure list, so it is idempotent by construction. Wrapped
     * in try/catch: a failure leaves MTR's own timetable in place.</p>
     *
     * @param after optional callback run on the server thread once every simulator answered
     */
    public static void refreshOffsets(MinecraftServer server, Set<Long> alsoDepots, Runnable after) {
        if (!AddonServerConfig.get().depotGroups.enabled) {
            LAST_APPLIED.clear();
            if (after != null) {
                after.run();
            }
            return;
        }
        Set<Long> mutable = new HashSet<>(alsoDepots);
        mutable.addAll(AddonStore.depotDelaysView().keySet());
        // A depot delayed before but no longer in the store still carries the old shift.
        LAST_APPLIED.forEach((depotId, applied) -> {
            if (applied > 0) {
                mutable.add(depotId);
            }
        });
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (mutable.isEmpty() || simulators == null || simulators.isEmpty()) {
            if (after != null) {
                after.run();
            }
            return;
        }
        Set<Long> affected = Set.copyOf(mutable);
        int[] pending = {simulators.size()};
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    for (Depot depot : simulator.depots) {
                        if (depot != null && affected.contains(depot.getId())) {
                            // Re-runs the departure writer; our HEAD hook resolves the delay again.
                            depot.generatePlatformDirectionsAndWriteDeparturesToSidings();
                        }
                    }
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Depot delays could not be applied", t);
                }
                server.execute(() -> {
                    if (--pending[0] <= 0 && after != null) {
                        after.run();
                    }
                });
            });
        }
    }

    public static void refreshOffsets(MinecraftServer server, Set<Long> alsoDepots) {
        refreshOffsets(server, alsoDepots, null);
    }

    /** SERVER_STOPPED / world load. */
    public static void clearRuntimeState() {
        LAST_APPLIED.clear();
    }

    /** Formats a delay for messages, e.g. {@code "+2m 30s"} / {@code "+0s"}. */
    public static String formatOffset(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? "+" + minutes + "m " + seconds + "s" : "+" + seconds + "s";
    }
}
