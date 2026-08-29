package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Water-polygon terrain for the dispatch map, scanned on demand by
 * {@code /dispatch terrain scan [marginBlocks]}.
 *
 * <p>The scan walks the railway network's bounding box (union of every valid rail's
 * {@link RailMath} extents and every station's {@code AreaBase} corners) plus a margin,
 * samples the surface block on an {@value #GRID}-block grid, marks the ones standing in
 * water, traces the resulting mask into closed rings and simplifies them. The polygons
 * are cached to {@code <save>/station-announcer-addon/terrain.json} so the (expensive,
 * chunk-loading) scan is a one-off per world.</p>
 *
 * <p><b>Threading.</b> Everything except {@link #terrainJson(String)} runs on the SERVER
 * thread: the sampler must read chunks, so it cannot live on a simulator thread, and it
 * is spread over ticks in {@value #TICK_BUDGET_MILLIS} ms slices from
 * {@link com.stationannouncer.mtraddon.AddonInit}'s tick handler. Only the bounding-box
 * question is asked of MTR, on the owning simulator's thread, and hops straight back
 * through {@code server.execute} carrying plain longs — the same shape
 * {@code DisruptionBroadcaster.requestScan} uses. {@link #terrainJson(String)} is called
 * from the simulator thread by the dispatch servlet and only reads one volatile
 * reference to an immutable snapshot.</p>
 *
 * <p>Nothing here is required for the map to work: with no scan ever run, the getter
 * answers with an empty polygon list.</p>
 */
public final class TerrainScanner {
    /** Sample spacing, blocks. Also the resolution of every emitted polygon. */
    public static final int GRID = 8;
    /** Payload version, mirrored in the endpoint JSON. */
    public static final int SCHEMA_VERSION = 1;

    /** Per-tick sampling budget. Two milliseconds of a fifty-millisecond tick. */
    private static final long TICK_BUDGET_MILLIS = 2;
    private static final long TICK_BUDGET_NANOS = TICK_BUDGET_MILLIS * 1_000_000L;
    /** Check the clock this often (in samples) while the chunk cache is hitting. */
    private static final int BUDGET_CHECK_MASK = 7;
    /** Refuse anything bigger — a stray rail at extreme coordinates would scan forever. */
    private static final int MAX_SAMPLES = 4_000_000;
    /** Douglas-Peucker tolerance, blocks. */
    private static final double SIMPLIFY_TOLERANCE = 6;
    /** Rings smaller than this (blocks²) are puddles, not map features. */
    private static final double MIN_AREA = 256;
    /** A ring needs at least a triangle's worth of corners to be worth drawing. */
    private static final int MIN_RING_POINTS = 4;
    /** Log a warning when the one-shot polygonization takes longer than this. */
    private static final long POLYGONIZE_WARN_MILLIS = 50;
    /** Vanilla's world border cap; beyond it the int grid arithmetic would overflow. */
    private static final long WORLD_LIMIT = 30_000_000L;

    /** The only reference to the server anywhere in the addon; set on SERVER_STARTED. */
    private static volatile MinecraftServer server;
    /** {@code <save>/station-announcer-addon/terrain.json}, or null before the server starts. */
    private static volatile Path terrainPath;
    /** Immutable published snapshot, MTR dimension id → result. Read cross-thread. */
    private static volatile Map<String, Terrain> terrain = Map.of();
    /** The scan in progress. Server thread only (volatile so {@link #status()} is honest). */
    private static volatile Scan active;
    /** Set between the command and the simulator round-trip so two clicks cannot both start. */
    private static volatile boolean pending;
    /** The last background save, joined at shutdown. */
    private static volatile Thread writer;

    private TerrainScanner() {
    }

    // ------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: remember the server and read whatever was scanned before. */
    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        active = null;
        pending = false;
        Path path = minecraftServer.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("terrain.json").normalize();
        terrainPath = path;
        terrain = read(path);
        if (!terrain.isEmpty()) {
            int polygons = 0;
            for (Terrain entry : terrain.values()) {
                polygons += entry.polygons().size();
            }
            StationAnnouncer.LOGGER.info("Terrain cache loaded ({} dimension(s), {} water polygons)",
                    terrain.size(), polygons);
        }
    }

    /** SERVER_STOPPING: finish any queued write on this thread, then forget everything. */
    public static void onServerStopping() {
        Thread pendingWrite = writer;
        if (pendingWrite != null) {
            try {
                pendingWrite.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writer = null;
        active = null;
        pending = false;
        terrain = Map.of();
        terrainPath = null;
        server = null;
    }

    // ------------------------------------------------------------- the ticker

    /**
     * END_SERVER_TICK. Two field reads and a return when no scan is running, which is
     * almost always — this is why it can sit ahead of the countdown early-returns.
     */
    public static void tick() {
        Scan scan = active;
        if (scan == null) {
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int total = scan.width * scan.height;
        while (scan.index < total) {
            boolean loadedChunk;
            try {
                loadedChunk = sample(scan);
            } catch (Throwable t) {
                active = null;
                StationAnnouncer.LOGGER.warn("Terrain scan of {} failed while sampling", scan.dimension, t);
                report(scan, Text.literal("Terrain scan failed while reading the world — see the server log.")
                        .formatted(Formatting.RED), true);
                return;
            }
            scan.index++;
            // A cache miss just paid for a (possibly generating) chunk load, so re-check
            // the clock immediately rather than after another seven cheap samples.
            if ((loadedChunk || (scan.index & BUDGET_CHECK_MASK) == 0) && System.nanoTime() >= deadline) {
                return;
            }
        }
        active = null;
        finish(scan);
    }

    /** @return true when this sample had to fetch a new chunk. */
    private static boolean sample(Scan scan) {
        int gx = scan.index % scan.width;
        int gz = scan.index / scan.width;
        int x = scan.originX + gx * GRID;
        int z = scan.originZ + gz * GRID;
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        boolean loaded = false;
        if (scan.chunk == null || chunkX != scan.chunkX || chunkZ != scan.chunkZ) {
            // Full, synchronous load (generating the chunk if need be). Deliberate: this
            // is an explicit operator command and the tick budget is what pays for it.
            scan.chunk = scan.world.getChunk(chunkX, chunkZ);
            scan.chunkX = chunkX;
            scan.chunkZ = chunkZ;
            loaded = true;
        }
        WorldChunk chunk = scan.chunk;
        // sampleHeightmap returns Heightmap.get() - 1, i.e. the y of the topmost block
        // that MOTION_BLOCKING accepts — and that predicate accepts any non-empty fluid
        // state, so for open water the answer IS the surface water block (bytecode-checked
        // against 1.20.4). Test that block, not the one below it.
        int surfaceY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (surfaceY >= scan.world.getBottomY()) {
            // Fluid state rather than block state: catches source, flowing and waterlogged
            // surfaces (a fence or slab standing in water) in one test.
            FluidState fluid = chunk.getFluidState(x, surfaceY, z);
            if (fluid.isIn(FluidTags.WATER)) {
                int bit = scan.index;
                scan.mask[bit >> 6] |= 1L << (bit & 63);
                scan.water++;
            }
        }
        return loaded;
    }

    // ------------------------------------------------------------ start a scan

    /**
     * {@code /dispatch terrain scan}. Asks the caller's dimension's simulator for the
     * network extents on its own thread, then hops back to begin sampling.
     */
    public static int startScan(ServerCommandSource source, int marginBlocks) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            source.sendError(Text.literal("The terrain scanner is not ready yet."));
            return 0;
        }
        if (active != null || pending) {
            source.sendError(Text.literal("A terrain scan is already running — " + status()));
            return 0;
        }
        ServerWorld world = source.getWorld();
        if (world == null) {
            source.sendError(Text.literal("Run this from inside a world."));
            return 0;
        }
        String dimension = worldId(world);
        if (dimension == null) {
            source.sendError(Text.literal("Could not resolve this world's MTR dimension id."));
            return 0;
        }
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        Simulator target = null;
        if (simulators != null) {
            for (Simulator simulator : simulators) {
                if (dimension.equals(simulator.dimension)) {
                    target = simulator;
                    break;
                }
            }
        }
        if (target == null) {
            source.sendError(Text.literal("MTR has no running simulation for " + dimension + "."));
            return 0;
        }

        int margin = Math.max(0, Math.min(1024, marginBlocks));
        pending = true;
        source.sendFeedback(() -> Text.literal("Measuring the network in " + dimension + "…")
                .formatted(Formatting.GRAY), false);
        final Simulator simulator = target;
        simulator.run(() -> {
            long minX = Long.MAX_VALUE;
            long minZ = Long.MAX_VALUE;
            long maxX = Long.MIN_VALUE;
            long maxZ = Long.MIN_VALUE;
            boolean any = false;
            try {
                for (Rail rail : simulator.railIdMap.values()) {
                    if (!rail.isValid()) {
                        continue;
                    }
                    RailMath math = rail.railMath;
                    minX = Math.min(minX, math.minX);
                    maxX = Math.max(maxX, math.maxX);
                    minZ = Math.min(minZ, math.minZ);
                    maxZ = Math.max(maxZ, math.maxZ);
                    any = true;
                }
                for (Station station : simulator.stations) {
                    minX = Math.min(minX, station.getMinX());
                    maxX = Math.max(maxX, station.getMaxX());
                    minZ = Math.min(minZ, station.getMinZ());
                    maxZ = Math.max(maxZ, station.getMaxZ());
                    any = true;
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Terrain bounding-box scan failed for dimension {}", simulator.dimension, t);
                pending = false;
                return;
            }
            // Plain longs cross the thread boundary — no MTR object escapes.
            final long fMinX = minX;
            final long fMinZ = minZ;
            final long fMaxX = maxX;
            final long fMaxZ = maxZ;
            final boolean fAny = any;
            minecraftServer.execute(() -> beginScan(source, world, dimension, fAny, fMinX, fMinZ, fMaxX, fMaxZ, margin));
        });
        return 1;
    }

    /** Server thread: turn the network extents into a grid and arm the ticker. */
    private static void beginScan(ServerCommandSource source, ServerWorld world, String dimension,
                                  boolean any, long minX, long minZ, long maxX, long maxZ, int margin) {
        pending = false;
        if (active != null) {
            source.sendError(Text.literal("A terrain scan started in the meantime — " + status()));
            return;
        }
        if (!any) {
            source.sendError(Text.literal("No rails or stations in " + dimension + " yet — nothing to scan."));
            return;
        }
        long lowX = snapDown(minX - margin);
        long lowZ = snapDown(minZ - margin);
        long highX = snapUp(maxX + margin);
        long highZ = snapUp(maxZ + margin);
        // MTR positions are longs; block coordinates outside the vanilla world border
        // would overflow the int grid arithmetic below, so refuse rather than wrap.
        if (Math.abs(lowX) > WORLD_LIMIT || Math.abs(lowZ) > WORLD_LIMIT
                || Math.abs(highX) > WORLD_LIMIT || Math.abs(highZ) > WORLD_LIMIT) {
            source.sendError(Text.literal("The network extends past the world limit ("
                    + lowX + "," + lowZ + " to " + highX + "," + highZ + ") — check for a stray rail."));
            return;
        }
        long width = (highX - lowX) / GRID + 1;
        long height = (highZ - lowZ) / GRID + 1;
        long total = width * height;
        if (total > MAX_SAMPLES) {
            source.sendError(Text.literal("That would be " + total + " samples (" + width + "×" + height
                    + " on a " + GRID + "-block grid), over the " + MAX_SAMPLES + " cap. Reduce the margin"
                    + " — or check for a stray rail far from the network."));
            return;
        }

        Scan scan = new Scan(world, dimension, source, (int) lowX, (int) lowZ, (int) width, (int) height,
                lowX, lowZ, highX, highZ);
        active = scan;
        source.sendFeedback(() -> Text.literal("Terrain scan started: " + total + " samples over "
                        + (highX - lowX) + "×" + (highZ - lowZ) + " blocks (margin " + margin + ").")
                .formatted(Formatting.AQUA), true);
    }

    /** Everything sampled: trace, simplify, publish, save, tell the caller. */
    private static void finish(Scan scan) {
        long startedAt = System.nanoTime();
        List<int[]> polygons;
        try {
            polygons = polygonize(scan.mask, scan.width, scan.height, scan.originX, scan.originZ);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Terrain polygonization failed for dimension {}", scan.dimension, t);
            report(scan, Text.literal("Terrain scan finished but the polygon trace failed — see the server log.")
                    .formatted(Formatting.RED), true);
            return;
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        if (elapsedMillis > POLYGONIZE_WARN_MILLIS) {
            StationAnnouncer.LOGGER.warn("Terrain polygonization for {} took {} ms on the server thread ({} rings)",
                    scan.dimension, elapsedMillis, polygons.size());
        }

        Terrain result = new Terrain(scan.dimension, System.currentTimeMillis(), List.copyOf(polygons),
                new long[]{scan.minX, scan.minZ, scan.maxX, scan.maxZ});
        Map<String, Terrain> merged = new LinkedHashMap<>(terrain);
        merged.put(scan.dimension, result);
        terrain = Map.copyOf(merged);
        save(merged);

        int samples = scan.width * scan.height;
        report(scan, Text.literal("Terrain scan complete: " + polygons.size() + " water polygons from "
                + samples + " samples.").formatted(Formatting.GREEN), false);
        StationAnnouncer.LOGGER.info("Terrain scan of {} complete: {} water polygons from {} samples ({} water)",
                scan.dimension, polygons.size(), samples, scan.water);
    }

    /** A command source can be gone by the time a long scan lands; never let that throw. */
    private static void report(Scan scan, Text text, boolean error) {
        ServerCommandSource source = scan.source;
        if (source == null) {
            return;
        }
        try {
            if (error) {
                source.sendError(text);
            } else {
                source.sendFeedback(() -> text, true);
            }
        } catch (Throwable ignored) {
            // The player logged out (or the source's output is gone); the log line stands.
        }
    }

    /** Human-readable state for {@code /dispatch terrain status}. */
    public static String status() {
        Scan scan = active;
        if (scan != null) {
            int total = Math.max(1, scan.width * scan.height);
            int percent = (int) (100L * scan.index / total);
            return "scanning " + scan.dimension + ": " + percent + "% (" + scan.index + "/" + total
                    + " samples, " + scan.water + " water so far)";
        }
        if (pending) {
            return "measuring the network…";
        }
        Map<String, Terrain> snapshot = terrain;
        if (snapshot.isEmpty()) {
            return "idle, nothing scanned yet";
        }
        StringBuilder builder = new StringBuilder("idle;");
        snapshot.forEach((dimension, entry) -> builder.append(' ').append(dimension).append(" = ")
                .append(entry.polygons().size()).append(" polygons (")
                .append((System.currentTimeMillis() - entry.scannedAt()) / 60000L).append(" min ago)"));
        return builder.toString();
    }

    // -------------------------------------------------------------- the getter

    /**
     * The dispatch map's terrain layer. Safe from any thread — one volatile read of an
     * immutable snapshot — and always answers, empty when that dimension was never scanned.
     */
    public static org.mtr.libraries.com.google.gson.JsonObject terrainJson(String dimension) {
        org.mtr.libraries.com.google.gson.JsonObject root = new org.mtr.libraries.com.google.gson.JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("dimension", dimension == null ? "" : dimension);
        Map<String, Terrain> snapshot = terrain;
        Terrain entry = dimension == null ? null : snapshot.get(dimension);
        root.addProperty("scannedAt", entry == null ? 0L : entry.scannedAt());
        root.addProperty("grid", GRID);
        org.mtr.libraries.com.google.gson.JsonArray polygons = new org.mtr.libraries.com.google.gson.JsonArray();
        if (entry != null) {
            for (int[] ring : entry.polygons()) {
                org.mtr.libraries.com.google.gson.JsonArray ringJson =
                        new org.mtr.libraries.com.google.gson.JsonArray(ring.length / 2);
                for (int i = 0; i < ring.length; i += 2) {
                    org.mtr.libraries.com.google.gson.JsonArray point =
                            new org.mtr.libraries.com.google.gson.JsonArray(2);
                    point.add(ring[i]);
                    point.add(ring[i + 1]);
                    ringJson.add(point);
                }
                polygons.add(ringJson);
            }
        }
        root.add("polygons", polygons);
        return root;
    }

    // -------------------------------------------------------------- geometry

    /**
     * Traces the water mask into closed rings and simplifies them.
     *
     * <p>Each sampled point owns the {@value #GRID}-block square centred on it, so the
     * boundary of the water region is the set of cell edges with water on exactly one
     * side. Every water cell contributes its outward edges in a fixed rotational order,
     * which makes the edge set a balanced digraph: following unused edges from any start
     * always returns to that start, so the whole set decomposes into closed rings —
     * outer boundaries and island holes alike, each emitted as its own polygon (the
     * frontend fills with the even-odd rule, so no nesting information is needed).</p>
     *
     * <p>Where two water cells only touch diagonally, four boundary edges meet at one
     * lattice point; the "turn right first" preference resolves that saddle the same way
     * every time, pinching the two blobs into two separate rings.</p>
     */
    private static List<int[]> polygonize(long[] mask, int width, int height, int originX, int originZ) {
        int stride = width + 1;
        int[] edgeStart = new int[256];
        int[] edgeDir = new int[256];
        int edgeCount = 0;
        for (int gz = 0; gz < height; gz++) {
            for (int gx = 0; gx < width; gx++) {
                if (!water(mask, width, height, gx, gz)) {
                    continue;
                }
                int topLeft = gz * stride + gx;
                if (edgeCount + 4 > edgeStart.length) {
                    int grown = edgeStart.length * 2;
                    edgeStart = java.util.Arrays.copyOf(edgeStart, grown);
                    edgeDir = java.util.Arrays.copyOf(edgeDir, grown);
                }
                if (!water(mask, width, height, gx, gz - 1)) {         // north edge, +x
                    edgeStart[edgeCount] = topLeft;
                    edgeDir[edgeCount++] = 0;
                }
                if (!water(mask, width, height, gx + 1, gz)) {         // east edge, +z
                    edgeStart[edgeCount] = topLeft + 1;
                    edgeDir[edgeCount++] = 1;
                }
                if (!water(mask, width, height, gx, gz + 1)) {         // south edge, -x
                    edgeStart[edgeCount] = topLeft + stride + 1;
                    edgeDir[edgeCount++] = 2;
                }
                if (!water(mask, width, height, gx - 1, gz)) {         // west edge, -z
                    edgeStart[edgeCount] = topLeft + stride;
                    edgeDir[edgeCount++] = 3;
                }
            }
        }
        List<int[]> polygons = new ArrayList<>();
        if (edgeCount == 0) {
            return polygons;
        }

        // Outgoing edges per lattice point, as a linked list over the edge arrays. Only
        // boundary vertices get an entry, so this stays proportional to the coastline.
        Map<Integer, Integer> head = new HashMap<>();
        int[] next = new int[edgeCount];
        for (int e = edgeCount - 1; e >= 0; e--) {
            Integer previous = head.put(edgeStart[e], e);
            next[e] = previous == null ? -1 : previous;
        }

        boolean[] used = new boolean[edgeCount];
        int[] ring = new int[64];
        for (int seed = 0; seed < edgeCount; seed++) {
            if (used[seed]) {
                continue;
            }
            int startVertex = edgeStart[seed];
            int current = seed;
            int count = 0;
            while (true) {
                used[current] = true;
                if (count + 2 > ring.length) {
                    ring = java.util.Arrays.copyOf(ring, ring.length * 2);
                }
                int vertex = edgeStart[current];
                ring[count++] = originX + (vertex % stride) * GRID - GRID / 2;
                ring[count++] = originZ + (vertex / stride) * GRID - GRID / 2;
                int end = endVertex(edgeStart[current], edgeDir[current], stride);
                if (end == startVertex) {
                    break;
                }
                int chosen = pickNext(head, next, used, edgeDir, end, edgeDir[current]);
                if (chosen < 0) {
                    break; // unreachable in a balanced digraph; never spin on bad data
                }
                current = chosen;
            }
            int[] closed = simplify(java.util.Arrays.copyOf(ring, count));
            if (closed.length >= MIN_RING_POINTS * 2 && Math.abs(area(closed)) >= MIN_AREA) {
                polygons.add(closed);
            }
        }
        return polygons;
    }

    private static boolean water(long[] mask, int width, int height, int gx, int gz) {
        if (gx < 0 || gz < 0 || gx >= width || gz >= height) {
            return false; // off the scanned box reads as dry, so coastlines close on the edge
        }
        int bit = gz * width + gx;
        return (mask[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    private static int endVertex(int start, int direction, int stride) {
        return switch (direction) {
            case 0 -> start + 1;
            case 1 -> start + stride;
            case 2 -> start - 1;
            default -> start - stride;
        };
    }

    /** Prefer turning right, then straight on, then left, then back — always the same way. */
    private static final int[] TURN_ORDER = {1, 0, 3, 2};

    private static int pickNext(Map<Integer, Integer> head, int[] next, boolean[] used, int[] edgeDir,
                                int vertex, int incoming) {
        Integer first = head.get(vertex);
        if (first == null) {
            return -1;
        }
        for (int turn : TURN_ORDER) {
            int wanted = (incoming + turn) & 3;
            for (int e = first; e >= 0; e = next[e]) {
                if (!used[e] && edgeDir[e] == wanted) {
                    return e;
                }
            }
        }
        return -1;
    }

    /** Shoelace area of a closed ring given as flattened x,z pairs. */
    private static double area(int[] ring) {
        double sum = 0;
        int points = ring.length / 2;
        for (int i = 0; i < points; i++) {
            int j = (i + 1) % points;
            sum += (double) ring[i * 2] * ring[j * 2 + 1] - (double) ring[j * 2] * ring[i * 2 + 1];
        }
        return sum / 2;
    }

    /** Drop exactly-collinear corners, then Douglas-Peucker the rest. */
    private static int[] simplify(int[] ring) {
        int[] pruned = dropCollinear(ring);
        int points = pruned.length / 2;
        if (points < 3) {
            return pruned;
        }
        // Run DP over the ring opened at its first point (repeated as the last point), so
        // both anchors are fixed and the closing corner survives.
        boolean[] keep = new boolean[points + 1];
        keep[0] = true;
        keep[points] = true;
        // Disjoint sub-intervals only, so 2·points ints is the true bound; take four.
        int[] stack = new int[(points + 2) * 4];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = points;
        while (top > 0) {
            int end = stack[--top];
            int start = stack[--top];
            if (end - start < 2) {
                continue;
            }
            double worst = -1;
            int worstIndex = -1;
            double ax = coordinate(pruned, start, 0);
            double az = coordinate(pruned, start, 1);
            double bx = coordinate(pruned, end, 0);
            double bz = coordinate(pruned, end, 1);
            for (int i = start + 1; i < end; i++) {
                double distance = pointSegmentDistance(coordinate(pruned, i, 0), coordinate(pruned, i, 1),
                        ax, az, bx, bz);
                if (distance > worst) {
                    worst = distance;
                    worstIndex = i;
                }
            }
            if (worst > SIMPLIFY_TOLERANCE && worstIndex > 0) {
                keep[worstIndex] = true;
                stack[top++] = start;
                stack[top++] = worstIndex;
                stack[top++] = worstIndex;
                stack[top++] = end;
            }
        }
        int kept = 0;
        for (int i = 0; i < points; i++) {
            if (keep[i]) {
                kept++;
            }
        }
        int[] result = new int[kept * 2];
        int at = 0;
        for (int i = 0; i < points; i++) {
            if (keep[i]) {
                result[at++] = pruned[i * 2];
                result[at++] = pruned[i * 2 + 1];
            }
        }
        return result;
    }

    /** Index {@code points} wraps to point 0 — the ring's repeated closing anchor. */
    private static double coordinate(int[] ring, int index, int component) {
        int points = ring.length / 2;
        return ring[(index % points) * 2 + component];
    }

    /**
     * A traced ring is rectilinear, so long straight coastlines arrive as dozens of
     * 8-block steps. Removing them exactly first keeps Douglas-Peucker's inner loop short.
     */
    private static int[] dropCollinear(int[] ring) {
        int points = ring.length / 2;
        if (points < 3) {
            return ring;
        }
        int[] result = new int[ring.length];
        int kept = 0;
        for (int i = 0; i < points; i++) {
            int previous = (i + points - 1) % points;
            int following = (i + 1) % points;
            long ax = ring[i * 2] - ring[previous * 2];
            long az = ring[i * 2 + 1] - ring[previous * 2 + 1];
            long bx = ring[following * 2] - ring[i * 2];
            long bz = ring[following * 2 + 1] - ring[i * 2 + 1];
            if (ax * bz - az * bx != 0) {
                result[kept++] = ring[i * 2];
                result[kept++] = ring[i * 2 + 1];
            }
        }
        return kept == 0 ? ring : java.util.Arrays.copyOf(result, kept);
    }

    private static double pointSegmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0) {
            // Closed-ring anchors coincide: fall back to distance from that point.
            return Math.sqrt((px - ax) * (px - ax) + (pz - az) * (pz - az));
        }
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lengthSquared));
        double ox = px - (ax + t * dx);
        double oz = pz - (az + t * dz);
        return Math.sqrt(ox * ox + oz * oz);
    }

    private static long snapDown(long value) {
        return Math.floorDiv(value, GRID) * GRID;
    }

    private static long snapUp(long value) {
        return Math.floorDiv(value + GRID - 1, GRID) * GRID;
    }

    private static String worldId(ServerWorld world) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a world for the terrain scan", t);
            return null;
        }
    }

    // ------------------------------------------------------------ persistence

    /**
     * Reads {@code terrain.json}. Plain Gson (the file is ours, not MTR's wire format);
     * a missing or damaged file just means "nothing scanned yet".
     */
    private static Map<String, Terrain> read(Path path) {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<String, Terrain> loaded = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                com.google.gson.JsonObject value = entry.getValue().getAsJsonObject();
                long scannedAt = value.has("scannedAt") ? value.get("scannedAt").getAsLong() : 0;
                long[] bbox = new long[4];
                com.google.gson.JsonArray bboxJson = value.getAsJsonArray("bbox");
                if (bboxJson != null && bboxJson.size() == 4) {
                    for (int i = 0; i < 4; i++) {
                        bbox[i] = bboxJson.get(i).getAsLong();
                    }
                }
                List<int[]> polygons = new ArrayList<>();
                com.google.gson.JsonArray polygonsJson = value.getAsJsonArray("polygons");
                if (polygonsJson != null) {
                    for (com.google.gson.JsonElement ringElement : polygonsJson) {
                        com.google.gson.JsonArray ringJson = ringElement.getAsJsonArray();
                        int[] ring = new int[ringJson.size() * 2];
                        for (int i = 0; i < ringJson.size(); i++) {
                            com.google.gson.JsonArray point = ringJson.get(i).getAsJsonArray();
                            ring[i * 2] = point.get(0).getAsInt();
                            ring[i * 2 + 1] = point.get(1).getAsInt();
                        }
                        if (ring.length >= MIN_RING_POINTS * 2) {
                            polygons.add(ring);
                        }
                    }
                }
                loaded.put(entry.getKey(), new Terrain(entry.getKey(), scannedAt, List.copyOf(polygons), bbox));
            }
            return Map.copyOf(loaded);
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, terrain starts empty", path, e);
            return Map.of();
        }
    }

    /** Serialize on the server thread (the snapshot is ours), write off it. */
    private static void save(Map<String, Terrain> snapshot) {
        Path path = terrainPath;
        if (path == null) {
            return;
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        snapshot.forEach((dimension, entry) -> {
            com.google.gson.JsonObject value = new com.google.gson.JsonObject();
            value.addProperty("scannedAt", entry.scannedAt());
            value.addProperty("grid", GRID);
            com.google.gson.JsonArray bbox = new com.google.gson.JsonArray(4);
            for (long bound : entry.bbox()) {
                bbox.add(bound);
            }
            value.add("bbox", bbox);
            com.google.gson.JsonArray polygons = new com.google.gson.JsonArray(entry.polygons().size());
            for (int[] ring : entry.polygons()) {
                com.google.gson.JsonArray ringJson = new com.google.gson.JsonArray(ring.length / 2);
                for (int i = 0; i < ring.length; i += 2) {
                    com.google.gson.JsonArray point = new com.google.gson.JsonArray(2);
                    point.add(ring[i]);
                    point.add(ring[i + 1]);
                    ringJson.add(point);
                }
                polygons.add(ringJson);
            }
            value.add("polygons", polygons);
            root.add(dimension, value);
        });
        String json = root.toString();
        Thread thread = new Thread(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json);
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
            }
        }, "station-announcer-terrain-save");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
    }

    // ---------------------------------------------------------------- records

    /** One dimension's cached result. Immutable once published. */
    private record Terrain(String dimension, long scannedAt, List<int[]> polygons, long[] bbox) {
    }

    /** Mutable scan state; only ever touched on the server thread. */
    private static final class Scan {
        private final ServerWorld world;
        private final String dimension;
        private final ServerCommandSource source;
        private final int originX;
        private final int originZ;
        private final int width;
        private final int height;
        private final long minX;
        private final long minZ;
        private final long maxX;
        private final long maxZ;
        private final long[] mask;
        private int index;
        private int water;
        private WorldChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;

        private Scan(ServerWorld world, String dimension, ServerCommandSource source, int originX, int originZ,
                     int width, int height, long minX, long minZ, long maxX, long maxZ) {
            this.world = world;
            this.dimension = dimension;
            this.source = source;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.height = height;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.mask = new long[(width * height + 63) >> 6];
        }
    }
}
