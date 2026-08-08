package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.PlatformRouteDetailsAccessor;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 5 — dynamic platform selection at stations (the feasible,
 * generation-time core per ARCHITECTURE §5.3).
 *
 * <p>MTR generates ONE shared path per depot with precomputed timetables, so a
 * per-arrival platform choice is out of reach (see the PROGRESS notes on the
 * skipped runtime switch). What IS feasible: when a depot (re)generates its main
 * route, a stop that has a configured <em>platform group</em>
 * ({@code "<routeId>:<stopIndex>" → [platformIds]}) is substituted with one group
 * member chosen by rotating a persisted per-group counter — successive
 * generations spread the depot's traffic across the group. Between generations
 * the choice is static; trains targeting an occupied platform still queue via
 * MTR's own signal blocks / {@code railBlockedDistance}, which is unchanged.</p>
 *
 * <p>Called from {@link com.stationannouncer.mixin.DepotMixin} at two points
 * (both javap-verified against MTR FABRIC-4.0.1+1.20.4):</p>
 * <ul>
 *   <li>{@code Depot.generateMainRoute} HEAD — the ONLY place the
 *       {@code SidingPathFinder} chain is built from {@code platformsInRoute}
 *       (bytecode-verified). We advance each used group's rotation counter, pick
 *       the member, and mutate the private {@code platformsInRoute} entries
 *       (via {@link PlatformRouteDetailsAccessor}) before the loop reads them.
 *       {@code Route.getRoutePlatforms()} — the user's route definition — is
 *       NEVER touched; only the depot's generation input is swapped.</li>
 *   <li>{@code Depot.writeRouteCache} TAIL — {@code Data.sync()} rebuilds
 *       {@code platformsInRoute} from the routes (originals) on every data sync;
 *       re-applying the LAST APPLIED choice (no rotation advance) keeps
 *       {@code getVehiclePlatformRouteInfo} — the vehicles' this/next platform
 *       info — consistent with the path that was actually baked.</li>
 * </ul>
 *
 * <p><b>Consequences that are correct behavior:</b> arrivals, PIDS and in-train
 * displays show the swapped platform, because the trains really stop there.
 * Feature 2's dwell-override matching keys by the ACTUAL (swapped) path platform
 * — see the note in {@link DwellOverrideEngine}.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off) — both hooks run inside depot path
 * generation / data sync. Reads the volatile config, the volatile immutable
 * {@link AddonSnapshots#platformGroups()} snapshot and the simulator's own data;
 * runtime state lives in two ConcurrentHashMaps (also read by the store's save
 * executor when persisting, and seeded/cleared on the server thread).</p>
 *
 * <p><b>Cost when idle:</b> feature disabled → one field read; no groups
 * configured → one volatile read + isEmpty; groups configured elsewhere → one
 * containsKey per depot route. These are cold paths anyway (depot generation and
 * data sync, never per tick).</p>
 */
public final class PlatformGroupEngine {
    /**
     * Rotation counter per group key, advanced once per generation that uses the
     * group. Persisted through {@link AddonStore} ({@code platformGroupRuntime})
     * so restarts do not reset the spread.
     */
    private static final ConcurrentHashMap<String, Integer> ROTATION = new ConcurrentHashMap<>();

    /**
     * The platform id most recently APPLIED per group key (what the baked path
     * actually targets). Re-applied by the writeRouteCache hook; also what
     * Feature 2 uses to attribute dwell segments. Persisted so a restarted
     * server keeps {@code platformsInRoute} consistent with the saved path.
     */
    private static final ConcurrentHashMap<String, Long> APPLIED_CHOICE = new ConcurrentHashMap<>();

    private static volatile boolean warnedWalkMismatch;

    private PlatformGroupEngine() {
    }

    /** One collapsed stop with its group-key attribution (route + index within that route). */
    private static final class StopRef {
        final long routeId;
        final int indexInRoute;
        final Platform original;

        StopRef(long routeId, int indexInRoute, Platform original) {
            this.routeId = routeId;
            this.indexInRoute = indexInRoute;
            this.original = original;
        }
    }

    public static String groupKey(long routeId, int stopIndex) {
        return routeId + ":" + stopIndex;
    }

    /** Parses {@code "<routeId>:<stopIndex>"}; null when malformed or out of range. */
    public static long[] parseGroupKey(String key) {
        if (key == null) {
            return null;
        }
        int split = key.indexOf(':');
        if (split <= 0 || split == key.length() - 1) {
            return null;
        }
        try {
            long routeId = Long.parseLong(key.substring(0, split));
            int stopIndex = Integer.parseInt(key.substring(split + 1));
            if (stopIndex < 0 || stopIndex > AddonNetworking.MAX_STOP_INDEX) {
                return null;
            }
            return new long[]{routeId, stopIndex};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ hooks

    /**
     * {@code Depot.generateMainRoute} HEAD: advance rotations and swap this
     * depot's group stops so the SidingPathFinder chain (and
     * {@code Depot.tick}'s {@code siding.generateRoute} callback, which reads
     * the same list) targets the chosen members. Also restores originals on
     * stops whose group was deleted since the last generation.
     */
    public static void onGenerateMainRoute(Depot depot, Data data, ObjectArrayList<?> platformsInRoute) {
        // 1. Feature toggle (config object is cached after first load; field read).
        if (!AddonServerConfig.get().dynamicPlatforms.enabled) {
            return;
        }
        // 2. Any groups at all? (volatile read + isEmpty)
        Long2ObjectOpenHashMap<long[][]> groups = AddonSnapshots.platformGroups();
        if (groups.isEmpty() || depot == null || !(data instanceof Simulator)) {
            return;
        }
        // 3. No sidings → generateMainRoute bails without generating; don't burn a rotation step.
        if (depot.savedRails.isEmpty()) {
            return;
        }
        // 4. Allocation-free: does any route of THIS depot have a group?
        if (!anyConfiguredRoute(depot, groups)) {
            return;
        }
        applyGroups(depot, data, platformsInRoute, groups, true);
    }

    /**
     * {@code Depot.writeRouteCache} TAIL: {@code platformsInRoute} was just
     * rebuilt from the routes (original platforms); re-apply the last applied
     * choices — WITHOUT advancing rotations — so the cached list matches the
     * baked path until the next generation.
     */
    public static void onWriteRouteCache(Depot depot, Data data, ObjectArrayList<?> platformsInRoute) {
        if (!AddonServerConfig.get().dynamicPlatforms.enabled) {
            return;
        }
        Long2ObjectOpenHashMap<long[][]> groups = AddonSnapshots.platformGroups();
        // writeRouteCache also runs on CLIENT data syncs (Depot exists in
        // MinecraftClientData); only the simulator's cache feeds generation and
        // vehicles, so bail everywhere else.
        if (groups.isEmpty() || depot == null || !(data instanceof Simulator) || APPLIED_CHOICE.isEmpty()) {
            return;
        }
        if (!anyConfiguredRoute(depot, groups)) {
            return;
        }
        applyGroups(depot, data, platformsInRoute, groups, false);
    }

    /**
     * Feature 2 interplay: the platform id a route stop ACTUALLY resolves to on
     * the generated path — the applied group choice when one exists, else the
     * original. {@link DwellOverrideEngine} matches path dwell segments (whose
     * {@code savedRailBaseId} is the swapped platform) through this.
     */
    public static long effectiveStopPlatformId(long routeId, int indexInRoute, long originalPlatformId) {
        if (!AddonServerConfig.get().dynamicPlatforms.enabled || APPLIED_CHOICE.isEmpty()) {
            return originalPlatformId;
        }
        Long chosen = APPLIED_CHOICE.get(groupKey(routeId, indexInRoute));
        return chosen == null ? originalPlatformId : chosen;
    }

    // ------------------------------------------------------------ runtime state

    /** Server thread (SERVER_STARTED): seed the persisted counters/choices from the store. */
    public static void seedRuntime(Map<String, Integer> rotation, Map<String, Long> appliedChoice) {
        ROTATION.clear();
        ROTATION.putAll(rotation);
        APPLIED_CHOICE.clear();
        APPLIED_CHOICE.putAll(appliedChoice);
    }

    /** Store save executor: copies safe to serialize (weakly consistent is fine). */
    public static Map<String, Integer> rotationSnapshot() {
        return new HashMap<>(ROTATION);
    }

    public static Map<String, Long> appliedChoiceSnapshot() {
        return new HashMap<>(APPLIED_CHOICE);
    }

    /** Server thread (snapshot republish): drop runtime entries for deleted groups. */
    public static void pruneRuntime(Set<String> validKeys) {
        ROTATION.keySet().retainAll(validKeys);
        APPLIED_CHOICE.keySet().retainAll(validKeys);
    }

    /** SERVER_STOPPED. */
    public static void clearRuntimeState() {
        ROTATION.clear();
        APPLIED_CHOICE.clear();
        warnedWalkMismatch = false;
    }

    // ---------------------------------------------------------------- internals

    private static boolean anyConfiguredRoute(Depot depot, Long2ObjectOpenHashMap<long[][]> groups) {
        for (int i = 0; i < depot.routes.size(); i++) {
            Route route = depot.routes.get(i);
            if (route != null && groups.containsKey(route.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The depot's ordered stop list with consecutive duplicate platforms
     * collapsed — a replica of {@code Depot.writeRouteCache}'s loop
     * (bytecode-verified against 4.0.1: null platforms are skipped WITHOUT
     * updating the previous id) — extended to remember which (route, index)
     * added each stop. That first occurrence is the group-key attribution; at a
     * route boundary where route B starts at route A's last platform, the
     * collapsed stop belongs to (routeA, lastIndex).
     */
    private static ObjectArrayList<StopRef> buildWalk(Depot depot) {
        ObjectArrayList<StopRef> walk = new ObjectArrayList<>();
        long previousPlatformId = 0;
        for (int r = 0; r < depot.routes.size(); r++) {
            Route route = depot.routes.get(r);
            if (route == null) {
                continue;
            }
            ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
            for (int i = 0; i < routePlatforms.size(); i++) {
                Platform platform = routePlatforms.get(i).platform;
                if (platform != null && platform.getId() != previousPlatformId) {
                    walk.add(new StopRef(route.getId(), i, platform));
                    previousPlatformId = platform.getId();
                }
            }
        }
        return walk;
    }

    /**
     * Swap group stops in {@code platformsInRoute}. In {@code advanceRotation}
     * mode (generation) counters advance and choices are recorded + persisted;
     * otherwise (route-cache rebuild) the recorded choices are re-applied as-is.
     *
     * <p>Guards baked in: a member must resolve in this simulator's
     * {@code platformIdMap}, belong to the SAME STATION as the stop's original
     * platform and share its transport mode (the authoritative semantic
     * validation — data can change any time after a group is saved); and a swap
     * is skipped when it would make two consecutive collapsed stops the same
     * platform (a platform→itself path finder). Invalid or colliding groups
     * fall back to the original platform.</p>
     */
    private static void applyGroups(Depot depot, Data data, ObjectArrayList<?> platformsInRoute,
                                    Long2ObjectOpenHashMap<long[][]> groups, boolean advanceRotation) {
        ObjectArrayList<StopRef> walk = buildWalk(depot);
        if (walk.size() != platformsInRoute.size()) {
            // Defensive: our replica no longer matches MTR's loop. Log once, touch nothing.
            if (!warnedWalkMismatch) {
                warnedWalkMismatch = true;
                StationAnnouncer.LOGGER.warn(
                        "Platform groups: collapsed stop walk ({}) does not match platformsInRoute ({}) for depot {}; dynamic platform selection disabled for safety",
                        walk.size(), platformsInRoute.size(), depot.getId());
            }
            return;
        }

        boolean runtimeChanged = false;
        long previousEffectiveId = 0;
        for (int k = 0; k < walk.size(); k++) {
            StopRef stop = walk.get(k);
            PlatformRouteDetailsAccessor entry = (PlatformRouteDetailsAccessor) platformsInRoute.get(k);
            long nextOriginalId = k + 1 < walk.size() ? walk.get(k + 1).original.getId() : 0;

            if (!advanceRotation) {
                // Rebuild mode: entries are fresh originals — verify alignment, then
                // re-apply the recorded choice if the stop (still) has a group.
                Platform current = entry.stationAnnouncer$getPlatform();
                if (current == null || current.getId() != stop.original.getId()) {
                    if (!warnedWalkMismatch) {
                        warnedWalkMismatch = true;
                        StationAnnouncer.LOGGER.warn(
                                "Platform groups: platformsInRoute entry {} does not match the expected stop for depot {}; dynamic platform selection disabled for safety",
                                k, depot.getId());
                    }
                    return;
                }
            }

            Platform target = stop.original;
            long[] members = membersFor(groups, stop);
            if (members != null) {
                String key = groupKey(stop.routeId, stop.indexInRoute);
                ObjectArrayList<Platform> valid = validMembers(data, stop.original, members);
                if (advanceRotation) {
                    if (valid.isEmpty()) {
                        // Group configured but nothing usable: fall back + forget any stale choice.
                        runtimeChanged |= APPLIED_CHOICE.remove(key) != null;
                    } else {
                        int counter = ROTATION.merge(key, 1, Integer::sum);
                        Platform chosen = pickNonColliding(valid, counter - 1, previousEffectiveId, nextOriginalId);
                        if (chosen != null) {
                            target = chosen;
                            Long previous = APPLIED_CHOICE.put(key, chosen.getId());
                            runtimeChanged |= previous == null || previous != chosen.getId();
                        } else {
                            runtimeChanged |= APPLIED_CHOICE.remove(key) != null;
                        }
                        runtimeChanged = true; // the counter advanced either way
                    }
                } else {
                    Long chosenId = APPLIED_CHOICE.get(key);
                    if (chosenId != null) {
                        for (int v = 0; v < valid.size(); v++) {
                            Platform candidate = valid.get(v);
                            if (candidate.getId() == chosenId
                                    && candidate.getId() != previousEffectiveId
                                    && candidate.getId() != nextOriginalId) {
                                target = candidate;
                                break;
                            }
                        }
                    }
                }
            }

            if (entry.stationAnnouncer$getPlatform() != target) {
                entry.stationAnnouncer$setPlatform(target);
            }
            previousEffectiveId = target.getId();
        }

        if (advanceRotation && runtimeChanged) {
            // Thread-safe debounced persistence (AtomicBoolean + executor); never file I/O here.
            AddonStore.requestSaveFromEngine();
        }
    }

    private static long[] membersFor(Long2ObjectOpenHashMap<long[][]> groups, StopRef stop) {
        long[][] byIndex = groups.get(stop.routeId);
        if (byIndex == null || stop.indexInRoute >= byIndex.length) {
            return null;
        }
        long[] members = byIndex[stop.indexInRoute];
        return members == null || members.length == 0 ? null : members;
    }

    /** Members that resolve AND are at the original platform's station with its transport mode. */
    private static ObjectArrayList<Platform> validMembers(Data data, Platform original, long[] members) {
        ObjectArrayList<Platform> valid = new ObjectArrayList<>(members.length);
        for (long memberId : members) {
            Platform member = memberId == original.getId() ? original : data.platformIdMap.get(memberId);
            if (member != null
                    && member.area != null && original.area != null
                    && member.area.getId() == original.area.getId()
                    && member.getTransportMode() == original.getTransportMode()) {
                valid.add(member);
            }
        }
        return valid;
    }

    /**
     * The rotation pick: start at {@code counter % size} and walk forward until a
     * member neither equals the previous stop's effective platform nor the next
     * stop's original one (either would collapse two stops into a
     * platform→itself path finder). Null when every member collides.
     */
    private static Platform pickNonColliding(ObjectArrayList<Platform> valid, int counter,
                                             long previousEffectiveId, long nextOriginalId) {
        int size = valid.size();
        int start = ((counter % size) + size) % size;
        for (int offset = 0; offset < size; offset++) {
            Platform candidate = valid.get((start + offset) % size);
            if (candidate.getId() != previousEffectiveId && candidate.getId() != nextOriginalId) {
                return candidate;
            }
        }
        return null;
    }
}
