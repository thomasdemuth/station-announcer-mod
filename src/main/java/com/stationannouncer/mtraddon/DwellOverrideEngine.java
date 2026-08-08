package com.stationannouncer.mtraddon;

import com.stationannouncer.mixin.PathDataSchemaAccessor;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Feature 2 — per-route dwell time overrides at platforms. MTR bakes each
 * platform's dwell into the generated path ({@code SidingPathFinder} creates the
 * arrival segment with {@code platform.getDwellTime()}), so an express and a
 * local calling at the same platform always dwell equally. This engine rewrites
 * the freshly generated {@code PathData.dwellTime} values — BEFORE the sidings
 * build their timetables — to the configured per-route override, so both the
 * precomputed timetable and runtime dwell see the override consistently.
 *
 * <p>Called from {@link com.stationannouncer.mixin.SidingMixin} at two points:</p>
 * <ul>
 *   <li>{@code Siding.generateRoute(...)} HEAD — runs once per siding immediately
 *       after the depot's shared main path completes; rewrites the depot path's
 *       platform-arrival segments (stops 1..n-1 of the depot's collapsed stop
 *       list; the same {@code PathData} references end up in every siding's
 *       {@code pathMainRoute} and in {@code VehicleExtraData}). Idempotent, so
 *       N sidings re-running it is fine.</li>
 *   <li>{@code Siding.finishGeneratingPath(...)} HEAD — the depot main path has
 *       no arrival segment for the FIRST stop (its dwell is baked into the last
 *       segment of the per-siding {@code pathSidingToMainRoute}); this hook
 *       rewrites that one segment before
 *       {@code generatePathDistancesAndTimeSegments()} builds the timetable.</li>
 * </ul>
 *
 * <p><b>Self-healing:</b> both hooks run only on freshly regenerated paths, which
 * MTR has just filled with each platform's default dwell — so a removed override
 * heals on the next depot regeneration with no "reset to default" pass needed.
 * Overrides are baked into MTR's saved siding paths and therefore survive server
 * restarts, exactly like MTR's own dwell times.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off). Reads only the volatile config and the
 * volatile immutable {@link AddonSnapshots#dwellOverrides()} snapshot plus the
 * simulator's own data (safe: path generation runs on that simulator's thread).
 * Stateless — no runtime caches.</p>
 *
 * <p><b>Cost when idle:</b> feature disabled → one field read; no overrides
 * configured → one volatile read + isEmpty. With overrides configured elsewhere,
 * an allocation-free scan of this depot's path bails before any real work.</p>
 */
public final class DwellOverrideEngine {

    /**
     * One entry of the depot's collapsed stop list (consecutive duplicate
     * platforms merged, exactly like {@code Depot.writeRouteCache}).
     * {@code secondaryRouteId} is non-zero only at a route boundary where the
     * same platform is both the last stop of one route and the first stop of the
     * next — the collapsed stop belongs to the earlier route, but an override
     * configured for the later route is honored as a fallback.
     *
     * <p><b>Feature 5 interplay (minimal change, 2026-08-07):</b>
     * {@code effectiveId} is the platform id this stop ACTUALLY resolves to on
     * the generated path — the applied platform-group choice from
     * {@link PlatformGroupEngine} when the stop has a group, else the original
     * platform's id. The path's dwell segments carry the SWAPPED id in
     * {@code getSavedRailBaseId()}, so both the segment↔stop matching AND the
     * override lookup key by {@code effectiveId}: a per-route dwell override on
     * a grouped stop must be configured on the member platform(s) the trains
     * really stop at. Collapse detection still uses ORIGINAL ids, mirroring
     * {@code Depot.writeRouteCache} (the group engine guarantees a swap never
     * creates a new consecutive-duplicate pair).</p>
     */
    private static final class Stop {
        final Platform platform;
        final long effectiveId;
        final long primaryRouteId;
        long secondaryRouteId;

        Stop(Platform platform, long effectiveId, long primaryRouteId) {
            this.platform = platform;
            this.effectiveId = effectiveId;
            this.primaryRouteId = primaryRouteId;
        }
    }

    private DwellOverrideEngine() {
    }

    /**
     * Rewrite the depot's freshly generated main path. Called at HEAD of
     * {@code Siding.generateRoute} — {@code depot.getPath()} is complete at that
     * point and no siding has built its timetable yet.
     */
    public static void applyToDepotPath(Depot depot) {
        // 1. Feature toggle (config object is cached after first load; field read).
        if (!AddonServerConfig.get().dwellOverrides.enabled) {
            return;
        }
        // 2. Any overrides at all? (volatile read + isEmpty)
        Long2ObjectOpenHashMap<Long2LongAVLTreeMap> overrides = AddonSnapshots.dwellOverrides();
        if (overrides.isEmpty() || depot == null) {
            return;
        }
        ObjectArrayList<PathData> path = depot.getPath();
        // 3. Allocation-free scan: does any platform in THIS depot's path have overrides?
        if (!anyConfiguredPlatform(path, overrides)) {
            return;
        }

        ObjectArrayList<Stop> stops = buildCollapsedStops(depot);
        if (stops.isEmpty()) {
            return;
        }

        // The depot main path chains platform i -> i+1 finders, so its platform
        // arrival segments correspond, in order, to stops 1..n-1 of the collapsed
        // stop list (stop 0 has no arrival segment here — see applyToSidingApproachPath).
        int stopPointer = 1;
        for (int i = 0; i < path.size(); i++) {
            PathData pathData = path.get(i);
            long platformId = pathData.getSavedRailBaseId();
            if (platformId == 0 || pathData.getDwellTime() <= 0) {
                continue;
            }
            // Monotonic match: find the next stop with this platform id. Normally
            // an exact hit at stopPointer; the scan tolerates unexpected segments
            // (defensive — an unmatched segment keeps its generated default).
            // Matches by effectiveId — the ACTUAL path platform, which is the
            // group-swapped one for Feature 5 group stops.
            int found = -1;
            for (int j = stopPointer; j < stops.size(); j++) {
                if (stops.get(j).effectiveId == platformId) {
                    found = j;
                    break;
                }
            }
            if (found < 0) {
                continue;
            }
            stopPointer = found + 1;
            applyOverride(pathData, stops.get(found), overrides);
        }
    }

    /**
     * Rewrite the arrival-at-first-platform segment of one siding's
     * siding-to-main-route path. Called at HEAD of the private
     * {@code Siding.finishGeneratingPath} — invoked from the path finders'
     * completion callbacks (never per tick), before
     * {@code generatePathDistancesAndTimeSegments()} builds the timetable.
     * Idempotent; the failure path clears the list, making this a no-op.
     */
    public static void applyToSidingApproachPath(Depot depot, ObjectArrayList<PathData> pathSidingToMainRoute) {
        if (!AddonServerConfig.get().dwellOverrides.enabled) {
            return;
        }
        Long2ObjectOpenHashMap<Long2LongAVLTreeMap> overrides = AddonSnapshots.dwellOverrides();
        if (overrides.isEmpty() || depot == null || pathSidingToMainRoute.isEmpty()) {
            return;
        }
        if (!anyConfiguredPlatform(pathSidingToMainRoute, overrides)) {
            return;
        }
        ObjectArrayList<Stop> stops = buildCollapsedStops(depot);
        if (stops.isEmpty()) {
            return;
        }
        Stop firstStop = stops.get(0);
        // Exactly one segment of this path has a saved-rail id: the arrival at the
        // depot route's first platform (the path finder's end saved rail) — which
        // is the group-swapped platform when the first stop has a Feature 5 group
        // (Depot.tick hands siding.generateRoute the swapped platformsInRoute
        // entry), hence effectiveId.
        for (int i = 0; i < pathSidingToMainRoute.size(); i++) {
            PathData pathData = pathSidingToMainRoute.get(i);
            if (pathData.getSavedRailBaseId() == firstStop.effectiveId && pathData.getDwellTime() > 0) {
                applyOverride(pathData, firstStop, overrides);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Allocation-free: any dwell segment whose platform has at least one override? */
    private static boolean anyConfiguredPlatform(ObjectArrayList<PathData> path,
                                                 Long2ObjectOpenHashMap<Long2LongAVLTreeMap> overrides) {
        for (int i = 0; i < path.size(); i++) {
            long id = path.get(i).getSavedRailBaseId();
            if (id != 0 && overrides.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The depot's ordered stop list with consecutive duplicate platforms collapsed
     * — a replica of {@code Depot.writeRouteCache}'s loop (null platforms skipped
     * without updating the previous id; a platform equal to the previous one is
     * merged into it), extended to remember which route each stop belongs to.
     */
    private static ObjectArrayList<Stop> buildCollapsedStops(Depot depot) {
        ObjectArrayList<Stop> stops = new ObjectArrayList<>();
        long previousPlatformId = 0;
        for (Route route : depot.routes) {
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
                    // Feature 5: the path stops at the group engine's applied
                    // choice for this (route, index), not necessarily here.
                    stops.add(new Stop(platform,
                            PlatformGroupEngine.effectiveStopPlatformId(route.getId(), i, platform.getId()),
                            route.getId()));
                    previousPlatformId = platform.getId();
                } else if (i == 0 && !stops.isEmpty()) {
                    // Route boundary: this route starts at the platform the previous
                    // route ended at. Remember it as the fallback attribution.
                    stops.get(stops.size() - 1).secondaryRouteId = route.getId();
                }
            }
        }
        return stops;
    }

    /**
     * Set the segment's dwell to the configured override for this stop's route,
     * if any. Values are clamped ≥ 1000 ms at the packet boundary, so fastutil's
     * absent-key default of 0 safely means "no override → keep the generated
     * platform default". Looked up by {@code effectiveId} — overrides belong to
     * the physical platform the trains actually stop at (see the Stop javadoc
     * for the Feature 5 group interplay).
     */
    private static void applyOverride(PathData pathData, Stop stop,
                                      Long2ObjectOpenHashMap<Long2LongAVLTreeMap> overrides) {
        Long2LongAVLTreeMap byRoute = overrides.get(stop.effectiveId);
        if (byRoute == null) {
            return;
        }
        long millis = byRoute.get(stop.primaryRouteId);
        if (millis <= 0 && stop.secondaryRouteId != 0) {
            millis = byRoute.get(stop.secondaryRouteId);
        }
        if (millis > 0) {
            ((PathDataSchemaAccessor) (Object) pathData).stationAnnouncer$setDwellTime(millis);
        }
    }
}
