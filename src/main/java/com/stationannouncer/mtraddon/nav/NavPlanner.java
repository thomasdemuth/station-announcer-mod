package com.stationannouncer.mtraddon.nav;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Server-side fastest-route planning for {@code /nav} — a time-dependent Dijkstra over
 * MTR's own route/platform data, returning a plain record tree that carries no MTR or
 * Minecraft types (so the result can cross onto the server thread, into a packet, or
 * into JSON without dragging simulator state with it).
 *
 * <h2>Where the numbers come from</h2>
 * The graph is derived exactly the way {@code DispatchMapData} derives the web
 * planner's payload, so the two never disagree:
 * <ul>
 *   <li><b>Route order</b> — {@code route.getRoutePlatforms()} → {@code .platform},
 *       skipping hidden and invalid routes and stops whose platform failed to resolve.</li>
 *   <li><b>Leg time</b> — {@code Route.durations} (MILLISECONDS: MTR fills it with the
 *       difference of two simulation timestamps in
 *       {@code Siding.generatePathDistancesAndTimeSegments}, bytecode-verified) when its
 *       size is exactly {@code platforms − 1}. Anything else — an unbaked depot, a
 *       partially generated route, or a value outside a plausible
 *       [{@value #MIN_PLAUSIBLE_LEG_SECONDS} s, {@value #MAX_PLAUSIBLE_LEG_SECONDS} s]
 *       band — falls back to straight-line midpoint distance at
 *       {@value #TRAIN_SPEED} m/s with a {@value #MIN_LEG_SECONDS} s floor.</li>
 *   <li><b>Wait to board</b> — half of
 *       {@link AnalyticsRecorder#scheduledHeadwayMillis} (the expected wait for a rider
 *       turning up at a random time). A 0 there means "not derivable" (real-time
 *       timetables, continuous movement, no frequency set) and becomes a flat
 *       {@value #UNKNOWN_HEADWAY_WAIT_SECONDS} s.</li>
 *   <li><b>Transfers</b> — between platforms of the SAME station only, 3D midpoint
 *       distance at {@value #WALK_SPEED} m/s plus a {@value #TRANSFER_BUFFER_SECONDS} s
 *       buffer.</li>
 *   <li><b>Origin</b> — walking from the caller's position to every platform within
 *       {@value #ORIGIN_WALK_RADIUS} blocks, same speed, plus
 *       {@value #WALK_BUFFER_SECONDS} s.</li>
 *   <li><b>Through-runs</b> — the rule {@code DispatchMapData.buildThroughRuns}
 *       implements: within a depot, {@code depot.routes} run back to back (INCLUDING the
 *       wrap from the last back to the first), so where route N's last platform is route
 *       N+1's first platform the rider stays seated. That is a free, wait-free edge, and
 *       the continuation is recorded in the ride leg's {@code via} list rather than
 *       becoming a transfer.</li>
 * </ul>
 *
 * <h2>Search</h2>
 * States are {@code A(route, stopIndex)} while aboard and {@code F(platform)} on foot.
 * Boarding pays the wait, riding pays only the leg time, alighting is free, and a
 * through-run is a zero-cost aboard→aboard edge — so staying on a train NEVER re-pays a
 * wait, which is the whole point of the two-layer state space. Fastest journey only: no
 * alternatives, no pareto front, no fare or comfort weighting.
 *
 * <p>{@code stepFree} is a HARD constraint on boarding, alighting, transferring and the
 * origin walk (never on staying aboard through a stop), evaluated against
 * {@code AddonStore.accessibilityView()} with its documented semantics: station id →
 * step-free platform ids, where an EMPTY array means every platform of that station.
 * A station absent from the map has no step-free platforms at all.</p>
 *
 * <p><b>Complexity:</b> the state space is {@code Σ stops + platforms + 1}; the edge
 * count is bounded by {@code Σ stops} (rides) + {@code Σ stops} (board/alight) +
 * {@code Σ platforms²} within each station (transfers) — i.e. it scales with the network,
 * not the world. The whole search is capped at {@value #MAX_LABELS} settled labels and
 * wrapped in a try/catch: a pathological or corrupt network yields {@code null}, never an
 * exception on the simulator thread.</p>
 *
 * <p><b>Thread:</b> {@link #plan} must run on the target dimension's SIMULATOR thread
 * (via {@code simulator.run(...)}), because it reads that simulator's live data. The
 * returned {@link Journey} is plain data and is safe to hand back with
 * {@code server.execute(...)}.</p>
 */
public final class NavPlanner {
    /** Walking speed used for transfers and the origin walk, blocks (= metres) per second. */
    public static final double WALK_SPEED = 4.3;
    /** Fallback train speed when a route has no usable durations. */
    public static final double TRAIN_SPEED = 12.0;
    /** Added to every transfer: finding the stairs, the platform, the right end. */
    public static final int TRANSFER_BUFFER_SECONDS = 30;
    /** Added to the origin walk for the same reason. */
    public static final int WALK_BUFFER_SECONDS = 30;
    /** How far the caller will walk to reach a first platform. */
    public static final double ORIGIN_WALK_RADIUS = 300;
    /** Floor on an estimated leg — no scheduled hop is shorter than this in practice. */
    public static final int MIN_LEG_SECONDS = 30;
    /** Below this a baked duration is treated as nonsense (probably not milliseconds). */
    public static final int MIN_PLAUSIBLE_LEG_SECONDS = 5;
    /** Above this too — two hours between consecutive stops is a broken bake, not a leg. */
    public static final int MAX_PLAUSIBLE_LEG_SECONDS = 7_200;
    /** Expected wait when the headway is not derivable at all. */
    public static final int UNKNOWN_HEADWAY_WAIT_SECONDS = 300;
    /** Safety valve: give up rather than churn on a pathological network. */
    public static final int MAX_LABELS = 200_000;
    /** The packet contract's cap; a longer journey is truncated at planning time. */
    public static final int MAX_LEGS = 24;
    /** The packet contract's cap on through-run continuations within one ride. */
    public static final int MAX_VIA = 4;
    /**
     * The packet contract's cap on a ride's resolved stop list. A ride with MORE stops
     * than this sends an EMPTY list rather than a truncated one — a partial sequence
     * would make the client count down to the wrong stop, whereas an empty one falls
     * back to the leg's {@code stops} count, which is always right.
     */
    public static final int MAX_STOP_LIST = 48;

    private NavPlanner() {
    }

    // ------------------------------------------------------------------- results

    /**
     * A walk/ride endpoint: either loose coordinates or a platform.
     *
     * <p>A platform point carries its RESOLVED world position and display name beside
     * the id, because the client can only look an id up while MTR has synced that
     * platform to it — which it has not for anything outside the player's area. A
     * position of exactly (0, 0, 0) means "not known"; {@code name} is already
     * {@link #displayName}'d and empty means "not known".</p>
     */
    public record Point(boolean platform, long platformId, double x, double y, double z, String name) {
        public static Point ofPlatform(long platformId) {
            return new Point(true, platformId, 0, 0, 0, "");
        }

        public static Point ofPlatform(long platformId, double x, double y, double z, String name) {
            return new Point(true, platformId, x, y, z, name == null ? "" : name);
        }

        public static Point ofCoords(double x, double y, double z) {
            return new Point(false, 0, x, y, z, "");
        }
    }

    /** One stop of a ride, resolved server-side so the client can count down out of sync range. */
    public record Stop(String name, double x, double y, double z) {
    }

    /** One through-run continuation inside a ride: the train becomes {@code routeId} here. */
    public record Via(long routeId, long atPlatformId, String routeName, String routeLabel,
                      int routeColor, String headsign) {
    }

    /** Marker for the three leg shapes; {@link #type()} is the packet's leg byte. */
    public interface Leg {
        int type();
    }

    public record WalkLeg(Point from, Point to, int metres) implements Leg {
        @Override
        public int type() {
            return 0;
        }
    }

    /**
     * @param routeColor 0xRRGGBB, or −1 when the route could not be resolved
     * @param headsign   where the train is going from the boarding stop ("" = unknown)
     * @param stopList   board → alight inclusive, or EMPTY when longer than
     *                   {@value #MAX_STOP_LIST}
     */
    public record RideLeg(long routeId, String routeName, String routeLabel, int routeColor, String headsign,
                          Point board, Point alight, int stops, List<Via> via,
                          List<Stop> stopList) implements Leg {
        @Override
        public int type() {
            return 1;
        }

        public long boardPlatformId() {
            return board == null ? 0 : board.platformId();
        }

        public long alightPlatformId() {
            return alight == null ? 0 : alight.platformId();
        }
    }

    public record TransferLeg(Point from, Point to, int metres) implements Leg {
        @Override
        public int type() {
            return 2;
        }
    }

    /**
     * A planned journey. {@code plannedArriveMs} is an epoch-millis estimate (0 when not
     * derivable); {@code totalSeconds} is the same figure as a duration, for chat.
     */
    public record Journey(String destination, List<Leg> legs, long plannedArriveMs, int totalSeconds) {
    }

    // --------------------------------------------------------------- graph build

    /**
     * One route, flattened: its resolved stop platforms, the seconds between them, and
     * everything the HUD needs to NAME it — {@code destinations[i]} is the headsign a
     * rider boarding at stop {@code i} sees.
     */
    private record RouteInfo(long id, String name, String label, int color, long[] platformIds,
                             int[] legSeconds, int boardWaitSeconds, String[] destinations) {
    }

    /** Everything the search needs, all primitives. */
    private static final class Graph {
        final List<RouteInfo> routes = new ArrayList<>();
        /** platform id → compact index. */
        final Map<Long, Integer> platformIndex = new HashMap<>();
        long[] platformIds = new long[0];
        double[] platformX = new double[0];
        double[] platformY = new double[0];
        double[] platformZ = new double[0];
        long[] platformStation = new long[0];
        boolean[] platformStepFree = new boolean[0];
        /** platform index → the display name a rider knows it by (its station, else itself). */
        String[] platformName = new String[0];
        /** station id → its platform indices. */
        final Map<Long, List<Integer>> stationPlatforms = new HashMap<>();
        /** platform index → the (routeIndex, stopIndex) pairs calling there, packed. */
        final Map<Integer, List<long[]>> stopsAtPlatform = new HashMap<>();
        /** route index → the route index a rider stays seated onto at its last stop, or −1. */
        int[] throughRun = new int[0];
        /** State-id layout: aboard states first (one per stop), then one per platform. */
        int[] routeStopBase = new int[0];
        int aboardStateCount;
        int stateCount;
    }

    // -------------------------------------------------------------------- public

    /**
     * Plan the fastest journey from {@code from} to any platform of {@code toStationId}.
     * SIMULATOR THREAD ONLY.
     *
     * @return the journey, or null when the destination is unknown, unreachable, or the
     *         search hit its safety cap
     */
    public static Journey plan(Simulator simulator, Position from, long toStationId, boolean stepFree) {
        try {
            return planInternal(simulator, from, toStationId, stepFree);
        } catch (Throwable throwable) {
            StationAnnouncer.LOGGER.warn("Nav planning failed in dimension {} ({})",
                    simulator == null ? "?" : simulator.dimension, throwable.toString());
            return null;
        }
    }

    private static Journey planInternal(Simulator simulator, Position from, long toStationId, boolean stepFree) {
        if (simulator == null || from == null) {
            return null;
        }
        Station destination = simulator.stationIdMap.get(toStationId);
        if (destination == null) {
            return null;
        }
        Graph graph = buildGraph(simulator, stepFree);
        if (graph.platformIds.length == 0) {
            return null;
        }

        int stateCount = graph.stateCount;
        double[] cost = new double[stateCount];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        // Predecessor state, and how we got there: 0 origin-walk, 1 board, 2 ride,
        // 3 alight, 4 transfer, 5 through-run.
        int[] previous = new int[stateCount];
        byte[] edgeKind = new byte[stateCount];
        Arrays.fill(previous, -1);

        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        boolean[] settled = new boolean[stateCount];

        // --- Origin: walk to every reachable platform.
        double originX = from.getX();
        double originY = from.getY();
        double originZ = from.getZ();
        for (int p = 0; p < graph.platformIds.length; p++) {
            if (stepFree && !graph.platformStepFree[p]) {
                continue;
            }
            double distance = distance(originX, originY, originZ,
                    graph.platformX[p], graph.platformY[p], graph.platformZ[p]);
            if (distance > ORIGIN_WALK_RADIUS) {
                continue;
            }
            int state = footState(graph, p);
            double seconds = distance / WALK_SPEED + WALK_BUFFER_SECONDS;
            if (seconds < cost[state]) {
                cost[state] = seconds;
                previous[state] = -1;
                edgeKind[state] = 0;
                push(queue, seconds, state);
            }
        }

        int goalState = -1;
        int settledLabels = 0;
        while (!queue.isEmpty()) {
            long[] entry = queue.poll();
            int state = (int) entry[1];
            if (settled[state]) {
                continue;
            }
            settled[state] = true;
            if (++settledLabels > MAX_LABELS) {
                StationAnnouncer.LOGGER.warn("Nav planning gave up after {} labels in dimension {}",
                        MAX_LABELS, simulator.dimension);
                return null;
            }
            double here = cost[state];

            if (state >= graph.aboardStateCount) {
                int platform = state - graph.aboardStateCount;
                if (graph.platformStation[platform] == toStationId) {
                    goalState = state;
                    break;
                }
                // --- Board every route calling here (never at its last stop).
                List<long[]> stops = graph.stopsAtPlatform.get(platform);
                if (stops != null) {
                    for (long[] stop : stops) {
                        int routeIndex = (int) stop[0];
                        int stopIndex = (int) stop[1];
                        RouteInfo route = graph.routes.get(routeIndex);
                        if (stopIndex >= route.platformIds().length - 1) {
                            continue; // terminus of this route: nothing to board for
                        }
                        relax(queue, cost, previous, edgeKind, settled, state,
                                graph.routeStopBase[routeIndex] + stopIndex,
                                here + route.boardWaitSeconds(), (byte) 1);
                    }
                }
                // --- Transfer within the station.
                List<Integer> siblings = graph.stationPlatforms.get(graph.platformStation[platform]);
                if (siblings != null) {
                    for (int other : siblings) {
                        if (other == platform || (stepFree && !graph.platformStepFree[other])) {
                            continue;
                        }
                        double walk = distance(graph.platformX[platform], graph.platformY[platform],
                                graph.platformZ[platform], graph.platformX[other],
                                graph.platformY[other], graph.platformZ[other]);
                        relax(queue, cost, previous, edgeKind, settled, state, footState(graph, other),
                                here + walk / WALK_SPEED + TRANSFER_BUFFER_SECONDS, (byte) 4);
                    }
                }
                continue;
            }

            // --- Aboard: ride on, alight, or stay seated across a through-run.
            int routeIndex = routeOf(graph, state);
            RouteInfo route = graph.routes.get(routeIndex);
            int stopIndex = state - graph.routeStopBase[routeIndex];
            int platform = graph.platformIndex.get(route.platformIds()[stopIndex]);
            if (!stepFree || graph.platformStepFree[platform]) {
                relax(queue, cost, previous, edgeKind, settled, state, footState(graph, platform), here, (byte) 3);
            }
            if (stopIndex < route.platformIds().length - 1) {
                relax(queue, cost, previous, edgeKind, settled, state, state + 1,
                        here + route.legSeconds()[stopIndex], (byte) 2);
            } else {
                int nextRoute = graph.throughRun[routeIndex];
                if (nextRoute >= 0) {
                    // Same physical vehicle, same seat: no wait, no transfer.
                    relax(queue, cost, previous, edgeKind, settled, state, graph.routeStopBase[nextRoute], here, (byte) 5);
                }
            }
        }

        if (goalState < 0) {
            return null;
        }
        List<Leg> legs = reconstruct(graph, previous, edgeKind, goalState, originX, originY, originZ);
        if (legs.isEmpty()) {
            return null;
        }
        int totalSeconds = (int) Math.round(cost[goalState]);
        return new Journey(displayName(destination.getName()), legs,
                System.currentTimeMillis() + totalSeconds * 1000L, totalSeconds);
    }

    // ------------------------------------------------------------------- search

    private static void relax(PriorityQueue<long[]> queue, double[] cost, int[] previous, byte[] edgeKind,
                              boolean[] settled, int fromState, int toState, double candidate, byte kind) {
        if (settled[toState] || candidate >= cost[toState]) {
            return;
        }
        cost[toState] = candidate;
        previous[toState] = fromState;
        edgeKind[toState] = kind;
        push(queue, candidate, toState);
    }

    private static void push(PriorityQueue<long[]> queue, double seconds, int state) {
        queue.add(new long[]{Double.doubleToRawLongBits(seconds), state});
    }

    private static int footState(Graph graph, int platformIndex) {
        return graph.aboardStateCount + platformIndex;
    }

    /** Which route an aboard state belongs to (binary search over the stop bases). */
    private static int routeOf(Graph graph, int state) {
        int low = 0;
        int high = graph.routes.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (graph.routeStopBase[mid] <= state) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    // ------------------------------------------------------------ reconstruction

    private static List<Leg> reconstruct(Graph graph, int[] previous, byte[] edgeKind, int goalState,
                                         double originX, double originY, double originZ) {
        // Walk the predecessor chain back to the origin, collecting (state, edge) pairs.
        // Dijkstra over non-negative weights cannot produce a predecessor cycle (a state's
        // predecessor is always already SETTLED, and settled states are never relaxed
        // again), but the guard costs nothing and turns any future bug into a null result
        // instead of a hung simulator thread.
        List<int[]> chain = new ArrayList<>();
        int state = goalState;
        int guard = graph.stateCount + 1;
        while (state >= 0 && guard-- > 0) {
            chain.add(new int[]{state, edgeKind[state]});
            int parent = previous[state];
            if (parent < 0) {
                break;
            }
            state = parent;
        }
        if (guard <= 0) {
            StationAnnouncer.LOGGER.warn("Nav reconstruction hit its guard; dropping the journey");
            return List.of();
        }
        Collections.reverse(chain);

        List<Leg> legs = new ArrayList<>();
        int boardRouteIndex = -1;
        int boardStopIndex = 0;
        int stops = 0;
        List<Via> continuations = new ArrayList<>();
        // The ride's platform sequence, board → alight inclusive, as ids; turned into
        // named + positioned Stops at the alight so the client can count stops down
        // without MTR having synced any of them.
        List<Long> sequence = new ArrayList<>();
        boolean aboard = false;

        for (int[] step : chain) {
            int current = step[0];
            int kind = step[1];
            switch (kind) {
                case 0 -> // origin walk
                        legs.add(new WalkLeg(Point.ofCoords(originX, originY, originZ),
                                platformPoint(graph, platformIdOf(graph, current)),
                                metres(originX, originY, originZ, graph, current)));
                case 1 -> { // board
                    aboard = true;
                    stops = 0;
                    continuations = new ArrayList<>();
                    sequence = new ArrayList<>();
                    boardRouteIndex = routeOf(graph, current);
                    boardStopIndex = current - graph.routeStopBase[boardRouteIndex];
                    appendStop(sequence, graph.routes.get(boardRouteIndex).platformIds()[boardStopIndex]);
                }
                case 2 -> { // ride one stop
                    stops++;
                    appendStop(sequence, platformIdOf(graph, current));
                }
                case 5 -> { // through-run: same seat, new route number
                    int routeIndex = routeOf(graph, current);
                    RouteInfo next = graph.routes.get(routeIndex);
                    if (continuations.size() < MAX_VIA) {
                        continuations.add(new Via(next.id(), next.platformIds()[0], next.name(),
                                next.label(), next.color(),
                                next.destinations().length > 0 ? next.destinations()[0] : ""));
                    }
                    // Same physical platform as the previous route's terminus, so this
                    // is a no-op unless the two disagree; appendStop drops the repeat.
                    appendStop(sequence, next.platformIds()[0]);
                }
                case 3 -> { // alight
                    if (aboard && boardRouteIndex >= 0) {
                        RouteInfo route = graph.routes.get(boardRouteIndex);
                        legs.add(new RideLeg(route.id(), route.name(), route.label(), route.color(),
                                boardStopIndex < route.destinations().length
                                        ? route.destinations()[boardStopIndex] : "",
                                platformPoint(graph, route.platformIds()[boardStopIndex]),
                                platformPoint(graph, platformIdOf(graph, current)),
                                stops, List.copyOf(continuations), stopList(graph, sequence)));
                        aboard = false;
                    }
                }
                case 4 -> { // transfer between platforms of one station
                    int parent = previous[current];
                    legs.add(new TransferLeg(platformPoint(graph, platformIdOf(graph, parent)),
                            platformPoint(graph, platformIdOf(graph, current)),
                            metresBetween(graph, parent, current)));
                }
                default -> {
                    // Unreachable: every edge writes one of the kinds above.
                }
            }
        }
        if (legs.size() > MAX_LEGS) {
            StationAnnouncer.LOGGER.warn("Nav journey had {} legs; truncated to the packet's {}",
                    legs.size(), MAX_LEGS);
            return new ArrayList<>(legs.subList(0, MAX_LEGS));
        }
        return legs;
    }

    /**
     * A platform endpoint with everything the client needs when it cannot look the id
     * up itself: the mid position (BLOCK-CENTRED, matching the client's own
     * {@code platformPosition}, so live and packet coordinates never disagree) and the
     * station display name. An id the graph does not know yields id-only.
     */
    private static Point platformPoint(Graph graph, long platformId) {
        Integer index = graph.platformIndex.get(platformId);
        if (index == null) {
            return Point.ofPlatform(platformId);
        }
        int i = index;
        return Point.ofPlatform(platformId, graph.platformX[i] + 0.5, graph.platformY[i] + 0.5,
                graph.platformZ[i] + 0.5, graph.platformName[i]);
    }

    /** Appends a stop id, dropping an immediate repeat (a through-run's handover platform). */
    private static void appendStop(List<Long> sequence, long platformId) {
        if (sequence.isEmpty() || sequence.get(sequence.size() - 1) != platformId) {
            sequence.add(platformId);
        }
    }

    /**
     * The ride's stops as named, positioned entries — or EMPTY when the ride has more
     * than {@value #MAX_STOP_LIST} of them, because a TRUNCATED sequence would have the
     * client counting down to the wrong stop while an absent one just falls back to the
     * leg's stop count.
     */
    private static List<Stop> stopList(Graph graph, List<Long> sequence) {
        if (sequence.size() < 2 || sequence.size() > MAX_STOP_LIST) {
            return List.of();
        }
        List<Stop> stops = new ArrayList<>(sequence.size());
        for (long platformId : sequence) {
            Point point = platformPoint(graph, platformId);
            stops.add(new Stop(point.name(), point.x(), point.y(), point.z()));
        }
        return List.copyOf(stops);
    }

    private static long platformIdOf(Graph graph, int state) {
        if (state >= graph.aboardStateCount) {
            return graph.platformIds[state - graph.aboardStateCount];
        }
        int routeIndex = routeOf(graph, state);
        return graph.routes.get(routeIndex).platformIds()[state - graph.routeStopBase[routeIndex]];
    }

    private static int metres(double x, double y, double z, Graph graph, int footStateId) {
        int platform = footStateId - graph.aboardStateCount;
        return (int) Math.round(distance(x, y, z,
                graph.platformX[platform], graph.platformY[platform], graph.platformZ[platform]));
    }

    private static int metresBetween(Graph graph, int fromFootState, int toFootState) {
        int a = fromFootState - graph.aboardStateCount;
        int b = toFootState - graph.aboardStateCount;
        if (a < 0 || b < 0) {
            return 0;
        }
        return (int) Math.round(distance(graph.platformX[a], graph.platformY[a], graph.platformZ[a],
                graph.platformX[b], graph.platformY[b], graph.platformZ[b]));
    }

    // -------------------------------------------------------------- graph build

    private static Graph buildGraph(Simulator simulator, boolean stepFree) {
        Graph graph = new Graph();
        Map<Long, long[]> accessibility = stepFree ? AddonStore.accessibilityView() : Map.of();

        // --- Platforms first: everything else indexes into this.
        List<Platform> platforms = new ArrayList<>();
        for (Platform platform : simulator.platforms) {
            if (platform != null && platform.getId() != 0) {
                platforms.add(platform);
            }
        }
        platforms.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        int count = platforms.size();
        graph.platformIds = new long[count];
        graph.platformX = new double[count];
        graph.platformY = new double[count];
        graph.platformZ = new double[count];
        graph.platformStation = new long[count];
        graph.platformStepFree = new boolean[count];
        graph.platformName = new String[count];
        for (int i = 0; i < count; i++) {
            Platform platform = platforms.get(i);
            Position mid = platform.getMidPosition();
            graph.platformIds[i] = platform.getId();
            graph.platformX[i] = mid.getX();
            graph.platformY[i] = mid.getY();
            graph.platformZ[i] = mid.getZ();
            // SavedRailBase.area is the owning Station (null for a platform outside one).
            Station station = platform.area;
            long stationId = station == null ? 0 : station.getId();
            graph.platformStation[i] = stationId;
            // The name a rider knows the stop by: its station, or — for a platform
            // outside any station area — the platform's own name. Resolved HERE, on
            // the simulator thread, because the client cannot do it for a platform
            // MTR has not synced to it.
            String platformDisplayName = station == null ? "" : displayName(station.getName());
            if (platformDisplayName.isEmpty()) {
                platformDisplayName = displayName(platform.getName());
            }
            graph.platformName[i] = platformDisplayName;
            graph.platformIndex.put(platform.getId(), i);
            if (stationId != 0) {
                graph.stationPlatforms.computeIfAbsent(stationId, ignored -> new ArrayList<>()).add(i);
            }
            if (stepFree) {
                long[] stepFreePlatforms = accessibility.get(stationId);
                // Documented AddonStore semantics: absent = none, EMPTY = every platform.
                graph.platformStepFree[i] = stepFreePlatforms != null
                        && (stepFreePlatforms.length == 0 || contains(stepFreePlatforms, platform.getId()));
            } else {
                graph.platformStepFree[i] = true;
            }
        }

        // --- Routes.
        Map<Long, Integer> routeIndexById = new HashMap<>();
        for (Route route : simulator.routes) {
            if (route == null || !route.isValid() || route.getHidden()) {
                continue;
            }
            RouteInfo info = buildRoute(simulator, route, graph);
            if (info == null) {
                continue;
            }
            routeIndexById.put(info.id(), graph.routes.size());
            graph.routes.add(info);
        }

        // --- State-id layout and the platform → stops index.
        int routeCount = graph.routes.size();
        graph.routeStopBase = new int[Math.max(1, routeCount)];
        int base = 0;
        for (int r = 0; r < routeCount; r++) {
            graph.routeStopBase[r] = base;
            long[] stopPlatforms = graph.routes.get(r).platformIds();
            for (int i = 0; i < stopPlatforms.length; i++) {
                Integer platformIndex = graph.platformIndex.get(stopPlatforms[i]);
                if (platformIndex != null) {
                    graph.stopsAtPlatform.computeIfAbsent(platformIndex, ignored -> new ArrayList<>())
                            .add(new long[]{r, i});
                }
            }
            base += stopPlatforms.length;
        }
        graph.aboardStateCount = base;
        graph.stateCount = base + count;

        // --- Through-runs, from depot.routes order INCLUDING the wrap.
        graph.throughRun = new int[Math.max(1, routeCount)];
        Arrays.fill(graph.throughRun, -1);
        try {
            for (Depot depot : simulator.depots) {
                if (depot == null) {
                    continue;
                }
                ObjectArrayList<Route> depotRoutes = depot.routes;
                int depotRouteCount = depotRoutes == null ? 0 : depotRoutes.size();
                if (depotRouteCount < 2) {
                    continue;
                }
                for (int i = 0; i < depotRouteCount; i++) {
                    Route fromRoute = depotRoutes.get(i);
                    Route toRoute = depotRoutes.get((i + 1) % depotRouteCount);
                    if (fromRoute == null || toRoute == null) {
                        continue;
                    }
                    Integer fromIndex = routeIndexById.get(fromRoute.getId());
                    Integer toIndex = routeIndexById.get(toRoute.getId());
                    if (fromIndex == null || toIndex == null || fromIndex.equals(toIndex)) {
                        continue;
                    }
                    long[] fromPlatforms = graph.routes.get(fromIndex).platformIds();
                    long[] toPlatforms = graph.routes.get(toIndex).platformIds();
                    if (fromPlatforms.length == 0 || toPlatforms.length == 0) {
                        continue;
                    }
                    if (fromPlatforms[fromPlatforms.length - 1] == toPlatforms[0]) {
                        // First writer wins, so a route run by two depots keeps one
                        // continuation deterministically.
                        if (graph.throughRun[fromIndex] < 0) {
                            graph.throughRun[fromIndex] = toIndex;
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            StationAnnouncer.LOGGER.warn("Nav planning: through-run walk failed ({}); planning without them",
                    throwable.toString());
            Arrays.fill(graph.throughRun, -1);
        }
        return graph;
    }

    /** One route flattened, or null when it has fewer than two resolvable stops. */
    private static RouteInfo buildRoute(Simulator simulator, Route route, Graph graph) {
        ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
        if (routePlatforms == null || routePlatforms.size() < 2) {
            return null;
        }
        // Unresolved stops are DROPPED here (unlike DispatchMapData, which keeps their
        // slot for index alignment) — a rider cannot board a platform that does not
        // exist, and dropping keeps the durations index mapping explicit below.
        int nominal = routePlatforms.size();
        long[] resolved = new long[nominal];
        int[] originalIndex = new int[nominal];
        int resolvedCount = 0;
        for (int i = 0; i < nominal; i++) {
            RoutePlatformData routePlatform = routePlatforms.get(i);
            Platform platform = routePlatform == null ? null : routePlatform.platform;
            if (platform == null || !graph.platformIndex.containsKey(platform.getId())) {
                continue;
            }
            resolved[resolvedCount] = platform.getId();
            originalIndex[resolvedCount] = i;
            resolvedCount++;
        }
        if (resolvedCount < 2) {
            return null;
        }
        long[] platformIds = Arrays.copyOf(resolved, resolvedCount);

        LongArrayList durations = route.durations;
        boolean durationsValid = durations != null && durations.size() == nominal - 1;
        int[] legSeconds = new int[resolvedCount - 1];
        for (int i = 0; i < legSeconds.length; i++) {
            int seconds = -1;
            if (durationsValid) {
                long millis = 0;
                // Sum the nominal legs the resolved pair spans, so dropping an
                // unresolvable intermediate stop keeps the time.
                for (int j = originalIndex[i]; j < originalIndex[i + 1]; j++) {
                    millis += durations.getLong(j);
                }
                long candidate = Math.round(millis / 1000.0);
                if (candidate >= MIN_PLAUSIBLE_LEG_SECONDS && candidate <= MAX_PLAUSIBLE_LEG_SECONDS) {
                    seconds = (int) candidate;
                }
            }
            if (seconds < 0) {
                seconds = estimateLegSeconds(graph, platformIds[i], platformIds[i + 1]);
            }
            legSeconds[i] = seconds;
        }

        long headwayMillis = 0;
        try {
            headwayMillis = AnalyticsRecorder.scheduledHeadwayMillis(simulator, route.getId());
        } catch (Throwable ignored) {
            // Analytics disabled or mid-reload: fall through to the flat assumption.
        }
        int waitSeconds = headwayMillis > 0
                ? (int) Math.max(1, Math.round(headwayMillis / 2000.0))
                : UNKNOWN_HEADWAY_WAIT_SECONDS;

        // Headsign per resolved stop. Route.getDestination(index) is MTR's own answer
        // (custom destination walked back from that stop, else the terminus station),
        // so a through-run's continuation reads exactly like the PIDS does.
        String[] destinations = new String[resolvedCount];
        Arrays.fill(destinations, "");
        for (int i = 0; i < resolvedCount; i++) {
            try {
                destinations[i] = displayName(route.getDestination(originalIndex[i]));
            } catch (Throwable ignored) {
                // A half-built route can throw here; an unnamed headsign is not fatal.
            }
        }
        int color = route.getColor() & 0xFFFFFF;
        return new RouteInfo(route.getId(), displayName(route.getName()),
                routeLabel(route.getRouteNumber(), route.getName()), color,
                platformIds, legSeconds, waitSeconds, destinations);
    }

    private static int estimateLegSeconds(Graph graph, long fromPlatformId, long toPlatformId) {
        Integer from = graph.platformIndex.get(fromPlatformId);
        Integer to = graph.platformIndex.get(toPlatformId);
        if (from == null || to == null) {
            return MIN_LEG_SECONDS;
        }
        double distance = distance(graph.platformX[from], graph.platformY[from], graph.platformZ[from],
                graph.platformX[to], graph.platformY[to], graph.platformZ[to]);
        return (int) Math.max(MIN_LEG_SECONDS, Math.round(distance / TRAIN_SPEED));
    }

    // -------------------------------------------------------------------- helpers

    private static boolean contains(long[] values, long needle) {
        for (long value : values) {
            if (value == needle) {
                return true;
            }
        }
        return false;
    }

    private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The short bullet label for a route. MTR carries a real {@code routeNumber} field
     * server-side, so it is preferred outright; only when it is blank does this fall back
     * to the project's client-side heuristic (first digit run, else first letter), which
     * is all {@code SimplifiedRoute} lets the client do.
     *
     * @return at most 8 characters, "" when nothing named the route
     */
    public static String routeLabel(String routeNumber, String routeName) {
        String number = displayName(routeNumber).trim();
        if (!number.isEmpty()) {
            return number.length() <= 8 ? number : number.substring(0, 8);
        }
        String name = displayName(routeName).trim();
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end))) {
                    end++;
                }
                return name.substring(i, Math.min(end, i + 2));
            }
        }
        return name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    /** MTR names are {@code "English|Other"}; only the first half is ever displayed. */
    public static String displayName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int bar = name.indexOf('|');
        return bar < 0 ? name : name.substring(0, bar);
    }
}
