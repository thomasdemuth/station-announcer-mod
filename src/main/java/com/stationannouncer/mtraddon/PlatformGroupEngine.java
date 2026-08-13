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
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
     *
     * <p>With several depots on one route this is the LAST generated depot's
     * choice, which is why {@link #APPLIED_CHOICE_BY_DEPOT} exists alongside it.
     * It is kept because {@link #effectiveStopPlatformId} (Feature 2's dwell
     * attribution) has no depot in hand — see that method's note.</p>
     */
    private static final ConcurrentHashMap<String, Long> APPLIED_CHOICE = new ConcurrentHashMap<>();

    /**
     * Per-depot applied choices, keyed {@code "<depotId>|<routeId>:<stopIndex>"}.
     * Added with per-depot rotation (2026-08-08): two depots on one route now
     * deliberately pick DIFFERENT members, so the {@code writeRouteCache} re-apply
     * must use the choice of the depot whose cache is being rebuilt, not whichever
     * depot generated last. Persisted next to the shared map.
     */
    private static final ConcurrentHashMap<String, Long> APPLIED_CHOICE_BY_DEPOT = new ConcurrentHashMap<>();

    private static volatile boolean warnedWalkMismatch;

    /**
     * Choices and counter advances made at generation START, held back until the
     * generation actually FINISHES (per depot id). {@code Depot.finishGeneratingPath}
     * is the only caller of the departures writer, so committing there means the
     * applied maps only ever describe a path that really got baked — an aborted
     * generation (blocked track, server stop, re-generate pressed again) leaves
     * them untouched and the route cache keeps matching the OLD path, which is
     * still the one the trains are on.
     */
    private static final ConcurrentHashMap<Long, PendingGeneration> PENDING = new ConcurrentHashMap<>();

    /** One generation's not-yet-committed effects. Plain maps: built and read on one thread. */
    private static final class PendingGeneration {
        final Map<String, Integer> rotation = new HashMap<>();
        final Map<String, Long> choice = new HashMap<>();
        final Map<String, Long> choiceByDepot = new HashMap<>();
        final Set<String> dropChoice = new java.util.HashSet<>();
        final Set<String> dropChoiceByDepot = new java.util.HashSet<>();
    }

    private PlatformGroupEngine() {
    }

    /**
     * One collapsed stop with its group-key attribution (route + index within
     * that route) — plus, at a route boundary, the attribution of the entry the
     * collapse swallowed. At a terminus the inbound route's last stop and the
     * outbound route's first stop are one collapsed entry; a platform group may
     * legitimately have been configured against EITHER of them, and matching
     * only the first would make a group on the outbound stop a silent no-op —
     * at exactly the place (a two-track terminal) groups are most wanted.
     */
    private static final class StopRef {
        final long routeId;
        final int indexInRoute;
        final Platform original;
        long altRouteId;
        int altIndexInRoute = -1;

        StopRef(long routeId, int indexInRoute, Platform original) {
            this.routeId = routeId;
            this.indexInRoute = indexInRoute;
            this.original = original;
        }
    }

    public static String groupKey(long routeId, int stopIndex) {
        return routeId + ":" + stopIndex;
    }

    /** {@code "<depotId>|<routeId>:<stopIndex>"} — the per-depot applied-choice key. */
    public static String depotChoiceKey(long depotId, String groupKey) {
        return depotId + "|" + groupKey;
    }

    /** The group key inside a per-depot choice key, or null when malformed. */
    public static String parseDepotChoiceKey(String key) {
        if (key == null) {
            return null;
        }
        int split = key.indexOf('|');
        if (split <= 0 || split == key.length() - 1) {
            return null;
        }
        try {
            Long.parseLong(key.substring(0, split));
        } catch (NumberFormatException e) {
            return null;
        }
        String groupKey = key.substring(split + 1);
        return parseGroupKey(groupKey) == null ? null : groupKey;
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
     *
     * <p><b>Multi-depot caveat (pre-existing, now more visible):</b> this reads the
     * SHARED per-group entry, i.e. the last generated depot's choice — the caller
     * ({@code DwellOverrideEngine.buildCollapsedStops}) walks routes, not depots, so
     * there is no depot to key on. With per-depot rotation two depots on one route
     * deliberately pick different members, so the OTHER depot's dwell segments fall
     * through Feature 2's monotonic matcher and keep the platform's own default
     * dwell. Documented rather than fixed: fixing it means threading the depot
     * through Feature 2's stop walk, which is another feature's code.</p>
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
    public static void seedRuntime(Map<String, Integer> rotation, Map<String, Long> appliedChoice,
                                   Map<String, Long> appliedChoiceByDepot) {
        ROTATION.clear();
        ROTATION.putAll(rotation);
        APPLIED_CHOICE.clear();
        APPLIED_CHOICE.putAll(appliedChoice);
        APPLIED_CHOICE_BY_DEPOT.clear();
        APPLIED_CHOICE_BY_DEPOT.putAll(appliedChoiceByDepot);
    }

    /** Store save executor: copies safe to serialize (weakly consistent is fine). */
    public static Map<String, Integer> rotationSnapshot() {
        return new HashMap<>(ROTATION);
    }

    public static Map<String, Long> appliedChoiceSnapshot() {
        return new HashMap<>(APPLIED_CHOICE);
    }

    public static Map<String, Long> appliedChoiceByDepotSnapshot() {
        return new HashMap<>(APPLIED_CHOICE_BY_DEPOT);
    }

    /** Server thread (snapshot republish): drop runtime entries for deleted groups. */
    public static void pruneRuntime(Set<String> validKeys) {
        ROTATION.keySet().retainAll(validKeys);
        APPLIED_CHOICE.keySet().retainAll(validKeys);
        // The per-depot map is keyed "<depotId>|<groupKey>", so prune by the suffix.
        APPLIED_CHOICE_BY_DEPOT.keySet().removeIf(key -> {
            String groupKey = parseDepotChoiceKey(key);
            return groupKey == null || !validKeys.contains(groupKey);
        });
    }

    /** SERVER_STOPPED. */
    public static void clearRuntimeState() {
        ROTATION.clear();
        APPLIED_CHOICE.clear();
        APPLIED_CHOICE_BY_DEPOT.clear();
        PENDING.clear();
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
                if (platform == null) {
                    continue;
                }
                if (platform.getId() != previousPlatformId) {
                    walk.add(new StopRef(route.getId(), i, platform));
                    previousPlatformId = platform.getId();
                } else if (!walk.isEmpty()) {
                    // Collapsed into the previous entry (a terminus turnback):
                    // remember this entry's attribution too, so a group keyed on
                    // either side of the boundary finds the collapsed stop. First
                    // swallowed entry wins, matching how the collapse itself works.
                    StopRef last = walk.get(walk.size() - 1);
                    if (last.altIndexInRoute < 0) {
                        last.altRouteId = route.getId();
                        last.altIndexInRoute = i;
                    }
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
     *
     * <p><b>Per-depot rotation (2026-08-08).</b> The pick is
     * {@code (counter + depotOffset) % n} rather than {@code counter % n}, where
     * {@code depotOffset} is this depot's stable index among the depots serving
     * that route (see {@link #depotOffsetForRoute}). Two depots on one route then
     * always land on different members whenever the group has at least as many
     * members as there are depots, instead of both following the same shared
     * counter. A route served by a single depot gets offset 0, so its behaviour is
     * bit-for-bit what it was before.</p>
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

        PendingGeneration pending = advanceRotation ? new PendingGeneration() : null;
        boolean runtimeChanged = false;
        long previousEffectiveId = 0;
        // Ground truth for re-apply: the platforms the baked path actually dwells
        // at. Anything the recorded choices claim beyond this is stale.
        LongOpenHashSet pathPlatforms = advanceRotation ? null : bakedPathPlatforms(depot);
        // route id → this depot's offset among the depots serving it; computed at most
        // once per route per generation (a depot has a handful of routes).
        Map<Long, Integer> depotOffsets = new HashMap<>();
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
            Object[] group = groupFor(groups, stop);
            if (group != null) {
                long[] members = (long[]) group[0];
                String key = (String) group[1];
                String depotKey = depotChoiceKey(depot.getId(), key);
                ObjectArrayList<Platform> valid = validMembers(data, stop.original, members);
                if (advanceRotation) {
                    if (valid.isEmpty()) {
                        // Group configured but nothing usable: fall back, and forget
                        // any stale choice once this generation commits.
                        pending.dropChoice.add(key);
                        pending.dropChoiceByDepot.add(depotKey);
                    } else {
                        // Counter read but NOT written: the advance sits in pending
                        // until the generation finishes, so an aborted one neither
                        // skips a member of the rotation nor records a choice for a
                        // path that never got baked.
                        int counter = ROTATION.getOrDefault(key, 0) + 1;
                        pending.rotation.put(key, counter);
                        int depotOffset = depotOffsets.computeIfAbsent(stop.routeId,
                                routeId -> depotOffsetForRoute(data, depot, routeId));
                        Platform chosen = pickNonColliding(valid, counter - 1 + depotOffset,
                                previousEffectiveId, nextOriginalId);
                        if (chosen != null) {
                            target = chosen;
                            pending.choice.put(key, chosen.getId());
                            pending.choiceByDepot.put(depotKey, chosen.getId());
                        } else {
                            pending.dropChoice.add(key);
                            pending.dropChoiceByDepot.add(depotKey);
                        }
                    }
                } else {
                    // Re-apply THIS depot's own choice; the shared entry is only the
                    // fallback for data files written before per-depot rotation.
                    Long chosenId = APPLIED_CHOICE_BY_DEPOT.get(depotKey);
                    if (chosenId == null) {
                        chosenId = APPLIED_CHOICE.get(key);
                    }
                    if (chosenId != null) {
                        // VERBATIM, verified against the PATH — never second-guessed.
                        // The cache must mirror what was baked: MTR's trip builder
                        // matches the two in lockstep, and one disagreement silently
                        // drops the timetable for everything after it (the empty-PIDS
                        // one-direction bug). The old collision guards belong to
                        // generation, where a path finder is about to run; here they
                        // could refuse a choice the path already uses.
                        if (pathPlatforms != null && !pathPlatforms.contains(chosenId.longValue())) {
                            // Stale: the baked path does not visit this platform (e.g.
                            // paths were regenerated while the feature was off). Drop
                            // it so it cannot poison the cache, now or after restart.
                            runtimeChanged |= APPLIED_CHOICE.remove(key) != null;
                            runtimeChanged |= APPLIED_CHOICE_BY_DEPOT.remove(depotKey) != null;
                        } else {
                            for (int v = 0; v < valid.size(); v++) {
                                Platform candidate = valid.get(v);
                                if (candidate.getId() == chosenId) {
                                    target = candidate;
                                    break;
                                }
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

        if (advanceRotation) {
            PENDING.put(depot.getId(), pending);
        } else if (runtimeChanged) {
            // Stale choices were dropped; persist so they stay gone after restart.
            AddonStore.requestSaveFromEngine();
        }
    }

    /**
     * {@code Depot.finishGeneratingPath} → departures writer HEAD (via
     * {@link com.stationannouncer.mixin.DepotMixin}): the generation that staged
     * {@code PENDING} actually finished, so its rotation advances and choices
     * become the applied truth. No pending entry (plain server load, group-less
     * depot, or a commit that already happened) is a cheap no-op.
     */
    public static void commitPending(Depot depot) {
        if (depot == null) {
            return;
        }
        PendingGeneration pending = PENDING.remove(depot.getId());
        if (pending == null) {
            return;
        }
        ROTATION.putAll(pending.rotation);
        APPLIED_CHOICE.putAll(pending.choice);
        APPLIED_CHOICE_BY_DEPOT.putAll(pending.choiceByDepot);
        pending.dropChoice.forEach(APPLIED_CHOICE::remove);
        pending.dropChoiceByDepot.forEach(APPLIED_CHOICE_BY_DEPOT::remove);
        // Thread-safe debounced persistence (AtomicBoolean + executor); never file I/O here.
        AddonStore.requestSaveFromEngine();
    }

    /**
     * The platforms the depot's baked main-route path actually dwells at, read
     * from the first siding that has one (all sidings of a depot share the
     * main-route stop sequence). Null when nothing is baked yet — re-apply then
     * has no truth to check against and applies nothing.
     */
    private static LongOpenHashSet bakedPathPlatforms(Depot depot) {
        // savedRails is a set, not a list; any one siding will do — all sidings
        // of a depot share the main-route stop sequence.
        for (Object rail : depot.savedRails) {
            ObjectArrayList<org.mtr.core.data.PathData> path =
                    ((com.stationannouncer.mixin.SidingPathAccessor) rail)
                            .stationAnnouncer$getPathMainRoute();
            if (path == null || path.isEmpty()) {
                continue;
            }
            LongOpenHashSet platforms = new LongOpenHashSet();
            for (int j = 0; j < path.size(); j++) {
                org.mtr.core.data.PathData segment = path.get(j);
                if (segment.getDwellTime() > 0 && segment.getSavedRailBaseId() != 0) {
                    platforms.add(segment.getSavedRailBaseId());
                }
            }
            return platforms;
        }
        return null;
    }

    /**
     * This depot's stable index among the depots that serve {@code routeId}: the number
     * of OTHER serving depots whose id sorts before ours. Sorting by MTR's own persisted
     * depot ids (random longs, assigned once at creation) makes the index deterministic
     * and stable across restarts and across generation order — deliberately NOT the
     * iteration order of {@code simulator.depots}, which is an {@code ObjectArraySet} and
     * therefore insertion-ordered, i.e. load-order dependent.
     *
     * <p>Result: with two depots on a route and a group of two, one depot takes member A
     * and the other member B, every generation. With more depots than members they wrap,
     * which is the best that can be done — the group simply is not big enough.</p>
     *
     * <p>Cost: one pass over {@code simulator.depots} (a handful of entries) per route per
     * generation, memoized by the caller. Runs on the simulator thread, reading that
     * simulator's own data.</p>
     */
    private static int depotOffsetForRoute(Data data, Depot depot, long routeId) {
        if (!(data instanceof Simulator simulator)) {
            return 0;
        }
        long thisId = depot.getId();
        int offset = 0;
        for (Depot other : simulator.depots) {
            if (other == null || other.getId() >= thisId) {
                continue;
            }
            for (int i = 0; i < other.routes.size(); i++) {
                Route route = other.routes.get(i);
                if (route != null && route.getId() == routeId) {
                    offset++;
                    break;
                }
            }
        }
        return offset;
    }

    /**
     * The group configured for a collapsed stop, under whichever of its
     * attributions the user configured it — the primary (route, index), or at a
     * route boundary the swallowed entry's. Returns the members and the KEY they
     * were found under, so the applied choice is recorded where the group lives
     * and the GUI keeps agreeing with the runtime.
     */
    private static Object[] groupFor(Long2ObjectOpenHashMap<long[][]> groups, StopRef stop) {
        long[] members = membersAt(groups, stop.routeId, stop.indexInRoute);
        if (members != null) {
            return new Object[]{members, groupKey(stop.routeId, stop.indexInRoute)};
        }
        if (stop.altIndexInRoute >= 0) {
            members = membersAt(groups, stop.altRouteId, stop.altIndexInRoute);
            if (members != null) {
                return new Object[]{members, groupKey(stop.altRouteId, stop.altIndexInRoute)};
            }
        }
        return null;
    }

    private static long[] membersAt(Long2ObjectOpenHashMap<long[][]> groups, long routeId, int indexInRoute) {
        long[][] byIndex = groups.get(routeId);
        if (byIndex == null || indexInRoute >= byIndex.length) {
            return null;
        }
        long[] members = byIndex[indexInRoute];
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
