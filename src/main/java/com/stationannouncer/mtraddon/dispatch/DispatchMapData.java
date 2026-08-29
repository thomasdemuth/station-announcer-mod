package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.SidingPathAccessor;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the journey-planner payload for GET {@code /dispatch/api/mapdata?dimension=N}:
 * every route with its ordered platform list, its baked segment durations and the rail
 * chain of each leg, plus every station clustered into walkable "parts" with the
 * distances a transfer planner needs.
 *
 * <p>Runs on the target dimension's SIMULATOR thread (invoked from
 * {@code DispatchApiServlet.getContent} behind a 30 s {@code CachedResponse}), reading
 * only that simulator's own data — the same single-threaded-state contract
 * {@link DispatchNetwork} works under. Ids are emitted as decimal strings (MTR ids are
 * random longs and would lose precision in JavaScript); rail ids are MTR's canonical
 * position-sorted hex ids ({@code TwoPositionsBase.getHexId()}), i.e. exactly the keys
 * {@code DispatchNetwork}'s {@code rails} array uses, so the client joins leg chains to
 * geometry with no extra lookup table.</p>
 *
 * <h2>Where the numbers come from</h2>
 * <ul>
 *   <li><b>durations</b> — {@code Route.durations} (public final {@code LongArrayList},
 *       javap-verified on MTR 4.0.1) is written by
 *       {@code Siding.generatePathDistancesAndTimeSegments} and is EMPTY until a depot
 *       bakes paths. The raw array is emitted whatever its size, with
 *       {@code durationsValid} telling the client whether it lines up with the platform
 *       list ({@code size == platforms − 1}). Estimating from distance/speed when it does
 *       not is deliberately the CLIENT's job — the server never invents timings.</li>
 *   <li><b>headwayMs</b> — {@link AnalyticsRecorder#scheduledHeadwayMillis} (its own 60 s
 *       cache; requires the simulator thread, which is where we are). 0 = not derivable
 *       (real-time timetables, continuous movement, no frequency set).</li>
 *   <li><b>legs</b> — walked out of a depot's baked {@code Siding.pathMainRoute} (see
 *       {@link #depotPairChains}).</li>
 * </ul>
 */
public final class DispatchMapData {
    /** Same-part clustering: max horizontal (XZ) separation between platform midpoints, blocks. */
    private static final double PART_MAX_HORIZONTAL = 40;
    /** Same-part clustering: max vertical separation between platform midpoints, blocks. */
    private static final double PART_MAX_VERTICAL = 12;
    /** Above this many platforms a station emits a pruned distance matrix instead of every pair. */
    private static final int FULL_MATRIX_PLATFORM_LIMIT = 40;

    private DispatchMapData() {
    }

    /** Runs on the simulator thread. */
    public static JsonObject build(Simulator simulator) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", DispatchStreamer.SCHEMA_VERSION);
        root.addProperty("serverTime", System.currentTimeMillis());
        root.addProperty("dimension", simulator.dimension);
        root.add("routes", buildRoutes(simulator));
        root.add("stations", buildStations(simulator));
        return root;
    }

    // ------------------------------------------------------------------ routes

    private static JsonArray buildRoutes(Simulator simulator) {
        // One walk per depot, shared by every route that depot runs (a depot's baked path
        // covers ALL of its routes concatenated, so extracting per route would re-walk it).
        Map<Long, Map<String, List<String>>> depotLegCache = new HashMap<>();
        JsonArray routes = new JsonArray();
        for (Route route : simulator.routes) {
            if (route == null || !route.isValid()) {
                continue;
            }
            JsonObject routeJson = new JsonObject();
            routeJson.addProperty("id", String.valueOf(route.getId()));
            routeJson.addProperty("name", route.getName());
            routeJson.addProperty("number", route.getRouteNumber());
            routeJson.addProperty("color", route.getColor());
            routeJson.addProperty("hidden", route.getHidden());
            routeJson.addProperty("mode", route.getTransportMode().toString().toLowerCase(Locale.ROOT));
            try {
                fillRoute(simulator, route, routeJson, depotLegCache);
            } catch (Throwable throwable) {
                // A single bad route must never break the whole payload: keep the
                // identity fields, backfill whatever the failure left missing.
                StationAnnouncer.LOGGER.warn("Dispatch map data: route {} skipped ({})",
                        route.getId(), throwable.toString());
            }
            backfill(routeJson);
            routes.add(routeJson);
        }
        return routes;
    }

    /** Fills the derived half of a route entry; the caller owns the failure path. */
    private static void fillRoute(Simulator simulator, Route route, JsonObject routeJson,
                                  Map<Long, Map<String, List<String>>> depotLegCache) {
        JsonArray platformIds = new JsonArray();
        ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
        for (int i = 0; i < routePlatforms.size(); i++) {
            RoutePlatformData routePlatform = routePlatforms.get(i);
            Platform platform = routePlatform == null ? null : routePlatform.platform;
            // A null platform still takes its slot, so index alignment with durations and
            // legs survives an unresolved stop.
            platformIds.add(platform == null ? "0" : String.valueOf(platform.getId()));
        }
        routeJson.add("platforms", platformIds);

        JsonArray durationsJson = new JsonArray();
        LongArrayList durations = route.durations;
        for (int i = 0; i < durations.size(); i++) {
            durationsJson.add(durations.getLong(i));
        }
        routeJson.add("durations", durationsJson);
        int expectedDurations = Math.max(0, platformIds.size() - 1);
        routeJson.addProperty("durationsValid", durations.size() == expectedDurations);

        routeJson.addProperty("headwayMs", AnalyticsRecorder.scheduledHeadwayMillis(simulator, route.getId()));

        routeJson.add("legs", legsJson(route, platformIds.size(), depotLegCache));
    }

    /** Every key {@link #fillRoute} may not have reached gets a harmless default. */
    private static void backfill(JsonObject routeJson) {
        if (!routeJson.has("platforms")) {
            routeJson.add("platforms", new JsonArray());
        }
        if (!routeJson.has("durations")) {
            routeJson.add("durations", new JsonArray());
        }
        if (!routeJson.has("durationsValid")) {
            routeJson.addProperty("durationsValid", false);
        }
        if (!routeJson.has("headwayMs")) {
            routeJson.addProperty("headwayMs", 0);
        }
        if (!routeJson.has("legs")) {
            routeJson.add("legs", new JsonArray());
        }
    }

    /**
     * The rail chain of every consecutive platform pair of the route, looked up by
     * platform-id pair in the depots' baked paths. Always emitted at full length
     * (platforms − 1) once any depot has a baked path; a pair the paths don't contain
     * yields that leg with an empty rail list, so one odd leg degrades alone instead of
     * dumping the whole route (the client estimates straight-line geometry for it).
     * Entirely empty only when nothing is baked yet.
     */
    private static JsonArray legsJson(Route route, int platformCount,
                                      Map<Long, Map<String, List<String>>> depotLegCache) {
        JsonArray legs = new JsonArray();
        int expectedLegs = Math.max(0, platformCount - 1);
        if (expectedLegs == 0) {
            return legs;
        }
        ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
        ObjectArrayList<Depot> depots = route.depots;
        boolean anyPath = false;
        List<Map<String, List<String>>> pairChains = new ArrayList<>();
        for (int i = 0; i < depots.size(); i++) {
            Depot depot = depots.get(i);
            if (depot == null) {
                continue;
            }
            Map<String, List<String>> chains = depotLegCache.computeIfAbsent(depot.getId(),
                    ignored -> depotPairChains(depot));
            if (!chains.isEmpty()) {
                anyPath = true;
                pairChains.add(chains);
            }
        }
        if (!anyPath) {
            return legs;
        }
        for (int i = 0; i < expectedLegs; i++) {
            RoutePlatformData from = routePlatforms.get(i);
            RoutePlatformData to = routePlatforms.get(i + 1);
            long fromId = from == null || from.platform == null ? 0 : from.platform.getId();
            long toId = to == null || to.platform == null ? 0 : to.platform.getId();
            List<String> chain = null;
            if (fromId != 0 && toId != 0) {
                String key = fromId + ">" + toId;
                for (Map<String, List<String>> chains : pairChains) {
                    chain = chains.get(key);
                    if (chain != null) {
                        break;
                    }
                }
            }
            JsonObject leg = new JsonObject();
            JsonArray rails = new JsonArray();
            if (chain != null) {
                for (String hexId : chain) {
                    rails.add(hexId);
                }
            }
            leg.add("rails", rails);
            legs.add(leg);
        }
        return legs;
    }

    /**
     * Walks one depot's baked main-route path and hands back the ordered rail hex-id
     * chain between every CONSECUTIVE DWELL PAIR of the cycle, keyed
     * {@code "<fromPlatformId>><toPlatformId>"} and INCLUSIVE of both platform rails.
     *
     * <p>Deliberately makes no assumption about where the cycle starts or how
     * {@code depot.routes} is ordered: the baked {@code pathMainRoute} is a rotated loop
     * (observed on the dev rig — its first dwell was a mid-route platform), and MTR's own
     * lockstep walk (the empty-PIDS machinery in CLAUDE.md) operates on internal state we
     * cannot see. Matching legs by platform-id PAIR sidesteps rotation, route order and
     * collapsed termini all at once; the cost is that a route calling at the same ordered
     * pair twice over two physically different tracks keeps only the first chain
     * (first-wins, deterministic), which is fine for drawing geometry.</p>
     *
     * <p>The wrap-around pair (last dwell → first dwell through the cycle seam) is
     * stitched from the tail chain plus the head chain, so out-and-back loops contribute
     * their return leg too.</p>
     */
    private static Map<String, List<String>> depotPairChains(Depot depot) {
        Map<String, List<String>> result = new HashMap<>();
        try {
            ObjectArrayList<PathData> path = firstBakedPath(depot);
            if (path == null || path.isEmpty()) {
                return result;
            }
            List<String> headChain = new ArrayList<>(); // path start → first dwell (inclusive)
            List<String> chain = new ArrayList<>();
            long firstPlatformId = 0;
            long previousPlatformId = 0;
            for (int i = 0; i < path.size(); i++) {
                PathData segment = path.get(i);
                if (segment == null) {
                    continue;
                }
                Rail rail = segment.getRail();
                String hexId = rail == null ? null : rail.getHexId();
                if (hexId != null && (chain.isEmpty() || !hexId.equals(chain.get(chain.size() - 1)))) {
                    chain.add(hexId);
                }
                if (segment.getDwellTime() <= 0 || segment.getSavedRailBaseId() == 0) {
                    continue;
                }
                long platformId = segment.getSavedRailBaseId();
                if (previousPlatformId == 0) {
                    firstPlatformId = platformId;
                    headChain = new ArrayList<>(chain);
                } else {
                    result.putIfAbsent(previousPlatformId + ">" + platformId, new ArrayList<>(chain));
                }
                previousPlatformId = platformId;
                chain.clear();
                if (hexId != null) {
                    chain.add(hexId);
                }
            }
            // Stitch the cycle seam: last dwell → path end, then path start → first dwell.
            if (previousPlatformId != 0 && firstPlatformId != 0 && previousPlatformId != firstPlatformId) {
                List<String> wrap = new ArrayList<>(chain);
                for (String hexId : headChain) {
                    if (wrap.isEmpty() || !hexId.equals(wrap.get(wrap.size() - 1))) {
                        wrap.add(hexId);
                    }
                }
                result.putIfAbsent(previousPlatformId + ">" + firstPlatformId, wrap);
            }
        } catch (Throwable throwable) {
            StationAnnouncer.LOGGER.warn("Dispatch map data: leg extraction failed for depot {} ({})",
                    depot.getId(), throwable.toString());
        }
        // Diagnosing a route whose legs come back empty on a real network: relaunch with
        // -Dfabric.log.level=debug and compare these recorded pairs against the route's
        // nominal platform sequence (a pair can be missing legitimately — e.g. a stop the
        // rail graph cannot actually reach, observed on the dev rig's half-built Uptown).
        StationAnnouncer.LOGGER.debug("Dispatch map data: depot {} pair chains: {}", depot.getId(), result.keySet());
        return result;
    }

    /**
     * The first non-empty {@code pathMainRoute} among the depot's sidings. All sidings of
     * a depot share the main-route stop sequence, so any one of them will do — the same
     * assumption {@code PlatformGroupEngine.bakedPathPlatforms} makes. {@code Siding} is
     * final, so the mixin-injected accessor needs the {@code (Object)} hop.
     */
    private static ObjectArrayList<PathData> firstBakedPath(Depot depot) {
        for (Siding siding : depot.savedRails) {
            if (siding == null) {
                continue;
            }
            ObjectArrayList<PathData> path =
                    ((SidingPathAccessor) (Object) siding).stationAnnouncer$getPathMainRoute();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- stations

    private static JsonArray buildStations(Simulator simulator) {
        Map<Long, long[]> accessibility = AddonStore.accessibilityView();
        JsonArray stations = new JsonArray();
        for (Station station : simulator.stations) {
            if (station == null) {
                continue;
            }
            JsonObject stationJson = new JsonObject();
            stationJson.addProperty("id", String.valueOf(station.getId()));
            stationJson.addProperty("name", station.getName());
            stationJson.addProperty("color", station.getColor());
            long[] accessiblePlatforms = accessibility.get(station.getId());
            stationJson.addProperty("accessible", accessiblePlatforms != null);
            if (accessiblePlatforms != null && accessiblePlatforms.length > 0) {
                JsonArray accessibleJson = new JsonArray(accessiblePlatforms.length);
                for (long platformId : accessiblePlatforms) {
                    accessibleJson.add(String.valueOf(platformId));
                }
                stationJson.add("accessiblePlatforms", accessibleJson);
            }
            try {
                fillStation(station, accessiblePlatforms, stationJson);
            } catch (Throwable throwable) {
                StationAnnouncer.LOGGER.warn("Dispatch map data: station {} clustering failed ({})",
                        station.getId(), throwable.toString());
            }
            if (!stationJson.has("platforms")) {
                stationJson.add("platforms", new JsonArray());
            }
            if (!stationJson.has("parts")) {
                stationJson.add("parts", new JsonArray());
            }
            if (!stationJson.has("partWalks")) {
                stationJson.add("partWalks", new JsonArray());
            }
            if (!stationJson.has("platformDistances")) {
                stationJson.add("platformDistances", new JsonArray());
            }
            stations.add(stationJson);
        }
        return stations;
    }

    private static void fillStation(Station station, long[] accessiblePlatforms, JsonObject stationJson) {
        List<Platform> platforms = new ArrayList<>();
        for (Platform platform : station.savedRails) {
            if (platform != null) {
                platforms.add(platform);
            }
        }
        platforms.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        int count = platforms.size();

        // --- Per-platform facts (the planner is self-sufficient off this array).
        JsonArray platformsJson = new JsonArray();
        Position[] mids = new Position[count];
        for (int i = 0; i < count; i++) {
            Platform platform = platforms.get(i);
            mids[i] = platform.getMidPosition();
            JsonObject platformJson = new JsonObject();
            platformJson.addProperty("id", String.valueOf(platform.getId()));
            JsonArray mid = new JsonArray();
            mid.add(mids[i].getX());
            mid.add(mids[i].getY());
            mid.add(mids[i].getZ());
            platformJson.add("mid", mid);
            platformJson.addProperty("accessible", accessiblePlatforms != null
                    && (accessiblePlatforms.length == 0
                    || Arrays.stream(accessiblePlatforms).anyMatch(id -> id == platform.getId())));
            platformJson.addProperty("dwellMs", platform.getDwellTime());
            platformsJson.add(platformJson);
        }
        stationJson.add("platforms", platformsJson);
        if (count == 0) {
            return;
        }

        // --- Clustering. Union-find, shared-route unions FIRST: platforms a train can
        // call at on one route are one walkable part by definition, whatever the geometry
        // says (a long island platform easily exceeds the distance thresholds).
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }
        Map<Long, Integer> firstPlatformOfRoute = new HashMap<>();
        for (int i = 0; i < count; i++) {
            for (Route route : platforms.get(i).routes) {
                if (route == null) {
                    continue;
                }
                Integer first = firstPlatformOfRoute.putIfAbsent(route.getId(), i);
                if (first != null) {
                    union(parent, first, i);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                double dx = mids[i].getX() - mids[j].getX();
                double dz = mids[i].getZ() - mids[j].getZ();
                double dy = Math.abs(mids[i].getY() - mids[j].getY());
                if (Math.sqrt(dx * dx + dz * dz) <= PART_MAX_HORIZONTAL && dy <= PART_MAX_VERTICAL) {
                    union(parent, i, j);
                }
            }
        }

        // Parts ordered by their smallest platform id — platforms are already sorted by
        // id, so first-seen root order IS smallest-id order, and part indices are stable.
        List<List<Integer>> parts = new ArrayList<>();
        Map<Integer, Integer> partOfRoot = new HashMap<>();
        int[] partOfPlatform = new int[count];
        for (int i = 0; i < count; i++) {
            int root = find(parent, i);
            Integer partIndex = partOfRoot.get(root);
            if (partIndex == null) {
                partIndex = parts.size();
                partOfRoot.put(root, partIndex);
                parts.add(new ArrayList<>());
            }
            parts.get(partIndex).add(i);
            partOfPlatform[i] = partIndex;
        }

        JsonArray partsJson = new JsonArray();
        for (int p = 0; p < parts.size(); p++) {
            List<Integer> members = parts.get(p);
            JsonObject partJson = new JsonObject();
            partJson.addProperty("id", station.getId() + ":" + p);
            JsonArray memberIds = new JsonArray();
            double sumX = 0;
            double sumY = 0;
            double sumZ = 0;
            for (int index : members) {
                memberIds.add(String.valueOf(platforms.get(index).getId()));
                sumX += mids[index].getX();
                sumY += mids[index].getY();
                sumZ += mids[index].getZ();
            }
            partJson.add("platforms", memberIds);
            JsonArray centroid = new JsonArray();
            centroid.add(round1(sumX / members.size()));
            centroid.add(round1(sumY / members.size()));
            centroid.add(round1(sumZ / members.size()));
            partJson.add("centroid", centroid);
            partJson.addProperty("y", Math.round(sumY / members.size()));
            partsJson.add(partJson);
        }
        stationJson.add("parts", partsJson);

        // --- Part-to-part walking distance = closest pair of platform midpoints.
        JsonArray partWalks = new JsonArray();
        int[][] closestPair = new int[parts.size()][parts.size()];
        double[][] closestDistance = new double[parts.size()][parts.size()];
        for (double[] row : closestDistance) {
            Arrays.fill(row, Double.MAX_VALUE);
        }
        for (int a = 0; a < parts.size(); a++) {
            for (int b = a + 1; b < parts.size(); b++) {
                double best = Double.MAX_VALUE;
                int bestI = -1;
                int bestJ = -1;
                for (int i : parts.get(a)) {
                    for (int j : parts.get(b)) {
                        double distance = distance3d(mids[i], mids[j]);
                        if (distance < best) {
                            best = distance;
                            bestI = i;
                            bestJ = j;
                        }
                    }
                }
                closestDistance[a][b] = best;
                // Pack the winning platform pair into one int so the truncated matrix
                // below can emit it without a second search.
                closestPair[a][b] = bestI < 0 ? -1 : (bestI << 16) | bestJ;
                JsonObject walk = new JsonObject();
                walk.addProperty("a", a);
                walk.addProperty("b", b);
                walk.addProperty("dist", round1(best));
                partWalks.add(walk);
            }
        }
        stationJson.add("partWalks", partWalks);

        // --- Platform-pair matrix (transfers are platform-to-platform, not part-to-part).
        JsonArray platformDistances = new JsonArray();
        if (count <= FULL_MATRIX_PLATFORM_LIMIT) {
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    platformDistances.add(distanceEntry(platforms, mids, i, j));
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    if (partOfPlatform[i] == partOfPlatform[j]) {
                        platformDistances.add(distanceEntry(platforms, mids, i, j));
                    }
                }
            }
            for (int a = 0; a < parts.size(); a++) {
                for (int b = a + 1; b < parts.size(); b++) {
                    int packed = closestPair[a][b];
                    if (packed >= 0) {
                        platformDistances.add(distanceEntry(platforms, mids, packed >> 16, packed & 0xFFFF));
                    }
                }
            }
            stationJson.addProperty("platformDistancesTruncated", true);
        }
        stationJson.add("platformDistances", platformDistances);
    }

    private static JsonObject distanceEntry(List<Platform> platforms, Position[] mids, int i, int j) {
        JsonObject entry = new JsonObject();
        entry.addProperty("a", String.valueOf(platforms.get(i).getId()));
        entry.addProperty("b", String.valueOf(platforms.get(j).getId()));
        entry.addProperty("dist", round1(distance3d(mids[i], mids[j])));
        return entry;
    }

    private static double distance3d(Position a, Position b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            // Smaller index wins the root purely for determinism; part ORDER comes from
            // the first-seen pass below (platforms are id-sorted, so that is smallest-id).
            if (rootA < rootB) {
                parent[rootB] = rootA;
            } else {
                parent[rootA] = rootB;
            }
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
