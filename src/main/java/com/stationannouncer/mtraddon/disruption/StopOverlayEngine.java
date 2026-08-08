package com.stationannouncer.mtraddon.disruption;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.PlatformRouteDetailsAccessor;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.AddonSnapshots;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.reflect.Constructor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 6a — <b>temporary stop changes</b>: skip a stop (trains run through
 * without stopping) or add a stop that is not normally on the route, as a
 * RUNTIME OVERLAY on top of the saved route data.
 *
 * <p><b>The mechanism, and how it composes with Feature 5.</b> MTR's
 * {@code Depot.writeRouteCache} flattens {@code Route.getRoutePlatforms()} of all
 * the depot's routes into the private {@code platformsInRoute} list (consecutive
 * duplicate platforms collapsed), and {@code Depot.generateMainRoute} builds the
 * {@code SidingPathFinder} chain from exactly that list —
 * {@code new SidingPathFinder<>(data, platformsInRoute.get(i).platform,
 * platformsInRoute.get(i + 1).platform, i)} — while {@code Depot.tick}'s success
 * callback and {@code Depot.getVehiclePlatformRouteInfo(stopIndex)} index the same
 * list. Feature 5 already exploits this by SWAPPING the {@code platform} field of
 * individual entries; this feature goes one step further and changes the list's
 * SHAPE: a disabled stop's entry is removed (the path finder then routes straight
 * from its predecessor to its successor — which IS "runs through without
 * stopping", provided the track allows it) and an added stop's entry is inserted.
 * <b>{@code Route.getRoutePlatforms()} — the user's saved route definition — is
 * never touched</b>, and neither is anything on disk: the overlay is re-applied
 * from our own store every time MTR rebuilds the list.</p>
 *
 * <p><b>Ordering with Feature 5</b> (this is why {@code DepotMixin}'s two handlers
 * call us explicitly rather than adding separate injectors):</p>
 * <ol>
 *   <li>{@code generateMainRoute} HEAD — {@link #restorePristine} first puts the
 *       list back the way MTR built it (undoing whatever we applied last time),
 *       so {@link com.stationannouncer.mtraddon.PlatformGroupEngine}'s own
 *       walk-size sanity check still matches; then Feature 5 rotates its groups
 *       (in-place field swaps, size unchanged); then {@link #onGenerateMainRoute}
 *       re-applies this overlay on top of the swapped platforms.</li>
 *   <li>{@code writeRouteCache} TAIL — MTR just rebuilt the list from the routes,
 *       Feature 5 re-applies its choices, then {@link #onWriteRouteCache} applies
 *       this overlay so {@code getVehiclePlatformRouteInfo} (the vehicles'
 *       this/next platform info) keeps matching the baked path.</li>
 * </ol>
 *
 * <p><b>Effect timing:</b> like every path-baked addon feature (dwell overrides,
 * platform groups) a change only reaches the trains when the depot regenerates its
 * paths. The GUI says so and offers the same {@code PacketDepotGenerate} button
 * Feature 2 uses.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off) — both hooks sit inside depot path
 * generation / data sync. Reads the volatile config, the volatile immutable
 * {@link AddonSnapshots#stopOverlays()} snapshot and the simulator's own data; the
 * only mutable state is one ConcurrentHashMap of undo records (written by
 * simulator threads, cleared on the server thread).</p>
 *
 * <p><b>Cost when idle:</b> feature disabled → one field read; no overlays
 * configured → one volatile read + {@code isEmpty}; overlays configured elsewhere →
 * one {@code containsKey} per route of the depot. Both hooks are cold paths
 * (depot regeneration and data sync), never per tick.</p>
 */
public final class StopOverlayEngine {
    /**
     * Per depot id: what the list looked like before we touched it, and what we
     * left behind. {@code restorePristine} only restores when the live list is
     * still, element for element (by identity), the list we produced — anything
     * else means MTR rebuilt it in between and the record is stale.
     */
    private static final ConcurrentHashMap<Long, Applied> APPLIED = new ConcurrentHashMap<>();

    private static volatile Constructor<?> detailsConstructor;
    private static volatile boolean detailsLookupFailed;
    private static volatile boolean warnedWalkMismatch;
    private static volatile boolean warnedTooFewStops;

    private StopOverlayEngine() {
    }

    private record Applied(Object[] pristine, Object[] applied) {
    }

    /** One collapsed stop of the depot's route cycle, with its (route, index) attribution. */
    private static final class StopRef {
        final long routeId;
        final int indexInRoute;
        final Route route;
        final Platform original;

        StopRef(long routeId, int indexInRoute, Route route, Platform original) {
            this.routeId = routeId;
            this.indexInRoute = indexInRoute;
            this.route = route;
            this.original = original;
        }
    }

    // ------------------------------------------------------------------ keys

    public static String stopKey(long routeId, int stopIndex) {
        return routeId + ":" + stopIndex;
    }

    /** Parses {@code "<routeId>:<stopIndex>"}; null when malformed or out of range. */
    public static long[] parseStopKey(String key) {
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
            if (stopIndex < 0 || stopIndex > DisruptionNetworking.MAX_STOP_INDEX) {
                return null;
            }
            return new long[]{routeId, stopIndex};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- hooks

    /**
     * {@code Depot.generateMainRoute} HEAD, step 1 of 3 — undo the overlay we
     * applied last time so Feature 5 sees the list exactly as MTR built it.
     * A no-op when we never touched this depot, or when the list has been
     * rebuilt since (in which case there is nothing of ours left in it).
     */
    public static void restorePristine(Depot depot, Data data, ObjectArrayList<?> platformsInRoute) {
        if (depot == null || !(data instanceof Simulator) || APPLIED.isEmpty()) {
            return;
        }
        Applied record = APPLIED.remove(depot.getId());
        if (record == null || platformsInRoute.size() != record.applied().length) {
            return;
        }
        for (int i = 0; i < record.applied().length; i++) {
            if (platformsInRoute.get(i) != record.applied()[i]) {
                return; // stale record — MTR rebuilt the list; nothing of ours to undo
            }
        }
        ObjectArrayList<Object> list = cast(platformsInRoute);
        list.clear();
        for (Object entry : record.pristine()) {
            list.add(entry);
        }
    }

    /**
     * {@code Depot.generateMainRoute} HEAD, step 3 of 3 — apply the overlay on
     * top of Feature 5's platform choices, so the path finder chain, the sidings'
     * first/last platform and the stop count all see the temporary route.
     */
    public static void onGenerateMainRoute(Depot depot, Data data, ObjectArrayList<?> platformsInRoute) {
        apply(depot, data, platformsInRoute);
    }

    /**
     * {@code Depot.writeRouteCache} TAIL — the list was just rebuilt from the
     * routes' original platforms, so any undo record for this depot is stale;
     * drop it and re-apply the overlay on the fresh list.
     */
    public static void onWriteRouteCache(Depot depot, Data data, ObjectArrayList<?> platformsInRoute) {
        if (depot != null) {
            APPLIED.remove(depot.getId());
        }
        apply(depot, data, platformsInRoute);
    }

    /** SERVER_STOPPED / world load: drop every undo record (they hold MTR objects). */
    public static void clearRuntimeState() {
        APPLIED.clear();
        warnedWalkMismatch = false;
        warnedTooFewStops = false;
    }

    // --------------------------------------------------------------- applying

    private static void apply(Depot depot, Data data, ObjectArrayList<?> platformsInRoute) {
        // 1. Feature toggle (config object is cached after the first load; field read).
        if (!AddonServerConfig.get().stopChanges.enabled) {
            return;
        }
        // 2. Any overlays at all? (volatile read + isEmpty)
        Long2ObjectOpenHashMap<AddonSnapshots.RouteStopOverlay> overlays = AddonSnapshots.stopOverlays();
        if (overlays.isEmpty() || depot == null || !(data instanceof Simulator)) {
            return;
        }
        // 3. Allocation-free: does any route of THIS depot have an overlay?
        if (!anyConfiguredRoute(depot, overlays)) {
            return;
        }

        ObjectArrayList<StopRef> walk = buildWalk(depot);
        if (walk.size() != platformsInRoute.size()) {
            // Our replica of MTR's collapse loop no longer matches (or someone else
            // resized the list). Log once and touch nothing.
            if (!warnedWalkMismatch) {
                warnedWalkMismatch = true;
                StationAnnouncer.LOGGER.warn(
                        "Temporary stop changes: collapsed stop walk ({}) does not match platformsInRoute ({}) for depot {}; overlay disabled for safety",
                        walk.size(), platformsInRoute.size(), depot.getId());
            }
            return;
        }

        // Build the effective list. Entries keep their identity wherever possible
        // (Feature 5 may have swapped their platform field already); only ADDED
        // stops are freshly constructed.
        ObjectArrayList<Object> result = new ObjectArrayList<>();
        ObjectArrayList<Platform> resultPlatforms = new ObjectArrayList<>();
        java.util.BitSet added = new java.util.BitSet();
        boolean changed = false;

        for (int k = 0; k < walk.size(); k++) {
            StopRef stop = walk.get(k);
            Object entry = platformsInRoute.get(k);
            AddonSnapshots.RouteStopOverlay overlay = overlays.get(stop.routeId);

            boolean disabled = overlay != null && overlay.isDisabled(stop.indexInRoute);
            if (disabled) {
                changed = true;
            } else {
                Platform platform = ((PlatformRouteDetailsAccessor) entry).stationAnnouncer$getPlatform();
                result.add(entry);
                resultPlatforms.add(platform);
            }

            long addPlatformId = overlay == null ? 0 : overlay.addedAfter(stop.indexInRoute);
            if (addPlatformId != 0) {
                Platform addPlatform = data.platformIdMap.get(addPlatformId);
                if (addPlatform != null && stop.original != null
                        && addPlatform.getTransportMode() == stop.original.getTransportMode()) {
                    Object newEntry = newDetails(addPlatform, stop.route, stop.indexInRoute);
                    if (newEntry != null) {
                        added.set(result.size());
                        result.add(newEntry);
                        resultPlatforms.add(addPlatform);
                        changed = true;
                    }
                }
            }
        }

        if (!changed) {
            return;
        }

        // Re-collapse: a removal (or an insertion) can put the same platform next
        // to itself, which would make MTR build a platform→itself path finder.
        // MTR's own loop keeps the FIRST of a duplicate pair; we do the same,
        // except that an ADDED entry always loses (it is the optional one).
        for (int i = result.size() - 1; i > 0; i--) {
            Platform current = resultPlatforms.get(i);
            Platform previous = resultPlatforms.get(i - 1);
            if (current == null || previous == null || current.getId() != previous.getId()) {
                continue;
            }
            int drop = added.get(i) ? i : (added.get(i - 1) ? i - 1 : i);
            result.remove(drop);
            resultPlatforms.remove(drop);
            // Keep the added-marker bitset aligned with the shrunken list.
            java.util.BitSet shifted = new java.util.BitSet();
            for (int b = added.nextSetBit(0); b >= 0; b = added.nextSetBit(b + 1)) {
                if (b < drop) {
                    shifted.set(b);
                } else if (b > drop) {
                    shifted.set(b - 1);
                }
            }
            added.clear();
            added.or(shifted);
        }

        if (result.size() < 2) {
            // MTR would report TWO_PLATFORMS_REQUIRED; leave the real route alone.
            if (!warnedTooFewStops) {
                warnedTooFewStops = true;
                StationAnnouncer.LOGGER.warn(
                        "Temporary stop changes: applying the overlay would leave depot {} with fewer than two stops; overlay skipped",
                        depot.getId());
            }
            return;
        }

        Object[] pristine = platformsInRoute.toArray();
        ObjectArrayList<Object> list = cast(platformsInRoute);
        list.clear();
        list.addAll(result);
        APPLIED.put(depot.getId(), new Applied(pristine, list.toArray()));
    }

    private static boolean anyConfiguredRoute(Depot depot, Long2ObjectOpenHashMap<AddonSnapshots.RouteStopOverlay> overlays) {
        for (int i = 0; i < depot.routes.size(); i++) {
            Route route = depot.routes.get(i);
            if (route != null && overlays.containsKey(route.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The depot's ordered stop list with consecutive duplicate platforms collapsed
     * — the same loop {@code Depot.writeRouteCache} runs (null platforms are
     * skipped WITHOUT updating the previous id), extended to remember which
     * (route, index) added each stop. Identical in structure to
     * {@code PlatformGroupEngine.buildWalk}, deliberately kept separate so neither
     * feature can break the other.
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
                    walk.add(new StopRef(route.getId(), i, route, platform));
                    previousPlatformId = platform.getId();
                }
            }
        }
        return walk;
    }

    /**
     * Builds a {@code Depot$PlatformRouteDetails}. The class is package-private
     * with a private constructor {@code (Platform, Route, int)} (javap-verified
     * against MTR FABRIC-4.0.1+1.20.4, alongside the synthetic 4-arg bridge), so
     * neither an import nor a Mixin factory-invoker can name it — reflection with
     * a cached, accessible constructor is the only route. This runs at most a
     * couple of times per depot generation.
     *
     * <p>{@code route} and {@code platformIndex} follow MTR's own convention from
     * {@code writeRouteCache}: they describe the leg that LEADS to this stop, so
     * an inserted stop gets the anchor stop's route and the anchor's index within
     * that route.</p>
     */
    private static Object newDetails(Platform platform, Route route, int platformIndex) {
        try {
            Constructor<?> constructor = detailsConstructor;
            if (constructor == null) {
                if (detailsLookupFailed) {
                    return null;
                }
                Class<?> type = Class.forName("org.mtr.core.data.Depot$PlatformRouteDetails",
                        false, Depot.class.getClassLoader());
                constructor = type.getDeclaredConstructor(Platform.class, Route.class, int.class);
                constructor.setAccessible(true);
                detailsConstructor = constructor;
            }
            return constructor.newInstance(platform, route, platformIndex);
        } catch (Throwable t) {
            detailsLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not construct an MTR route-stop entry; temporarily ADDED stops are disabled", t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectArrayList<Object> cast(ObjectArrayList<?> list) {
        return (ObjectArrayList<Object>) list;
    }

    // ------------------------------------------------------------ validation

    /**
     * Simulator thread: may {@code platformId} be inserted after stop
     * {@code afterStopIndex} of {@code routeId}? The rule from the spec is "the
     * platform must already lie on the route's existing track path — no rerouting
     * over different track", which we check against the depot's CURRENT generated
     * path: at least one of the platform's own rails has to appear in it.
     *
     * <p>Deliberately a whole-path check rather than a per-leg one: the depot path
     * is baked from the previously effective stop list, so leg indices drift as
     * soon as another overlay is active. What this guarantees is the important
     * part — the trains already run over that platform. Where exactly the new stop
     * is spliced in is the user's choice, and an impossible splice surfaces as
     * MTR's own {@code PATH_NOT_FOUND} generation status (which the GUI shows).</p>
     */
    public static Result validateAddition(Simulator simulator, long routeId, int afterStopIndex, long platformId) {
        Route route = simulator.routeIdMap.get(routeId);
        if (route == null) {
            return Result.UNKNOWN_ROUTE;
        }
        ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
        if (afterStopIndex < 0 || afterStopIndex >= routePlatforms.size()) {
            return Result.UNKNOWN_ROUTE;
        }
        Platform anchor = routePlatforms.get(afterStopIndex).platform;
        Platform platform = simulator.platformIdMap.get(platformId);
        if (platform == null || anchor == null) {
            return Result.UNKNOWN_PLATFORM;
        }
        if (platform.getId() == anchor.getId()) {
            return Result.DUPLICATE;
        }
        if (platform.getTransportMode() != anchor.getTransportMode()) {
            return Result.WRONG_MODE;
        }
        Platform next = afterStopIndex + 1 < routePlatforms.size()
                ? routePlatforms.get(afterStopIndex + 1).platform : null;
        if (next != null && next.getId() == platform.getId()) {
            return Result.DUPLICATE;
        }

        boolean anyPath = false;
        for (int d = 0; d < route.depots.size(); d++) {
            Depot depot = route.depots.get(d);
            if (depot == null) {
                continue;
            }
            ObjectArrayList<PathData> path = depot.getPath();
            if (path.isEmpty()) {
                continue;
            }
            anyPath = true;
            for (int i = 0; i < path.size(); i++) {
                PathData segment = path.get(i);
                if (platform.containsPos(segment.getOrderedPosition1())
                        || platform.containsPos(segment.getOrderedPosition2())) {
                    return Result.OK;
                }
            }
        }
        return anyPath ? Result.NOT_ON_PATH : Result.NO_PATH;
    }

    /**
     * Outcome of {@link #validateAddition}; the C2S handler turns it into an
     * action-bar message. Ordered least → most specific: with several dimensions
     * only one simulator knows the route, so the handler reports the highest
     * ordinal it saw.
     */
    public enum Result {
        OK,
        UNKNOWN_ROUTE,
        UNKNOWN_PLATFORM,
        NO_PATH,
        NOT_ON_PATH,
        WRONG_MODE,
        DUPLICATE
    }
}
