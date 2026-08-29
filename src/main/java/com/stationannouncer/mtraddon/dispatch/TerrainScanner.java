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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Water-polygon terrain for the dispatch map, scanned on demand by
 * {@code /dispatch terrain scan} or by the automatic once-per-launch pass.
 *
 * <p><b>Scope: the whole generated world, Dynmap-style.</b> The scan no longer walks a
 * box derived from the railway — it walks the bounding box of every chunk that ALREADY
 * EXISTS ON DISK, and it never asks the game to make a chunk that does not. Existence is
 * decided by {@link ChunkIndex}, read straight out of the dimension's {@code region/*.mca}
 * headers on a background thread before the scan starts; a sample whose chunk is not in
 * that set is answered "not water" without touching {@code world.getChunk} at all. What
 * remains is a surface sample on an {@value #GRID}-block grid, a water mask, and a ring
 * trace simplified into polygons, cached to
 * {@code <save>/station-announcer-addon/terrain.json}.</p>
 *
 * <p><b>Threading.</b> Sampling runs on the SERVER thread (it reads chunks) in
 * {@value #TICK_BUDGET_MILLIS} ms slices from {@link com.stationannouncer.mtraddon.AddonInit}'s
 * tick handler. The region-header enumeration is file IO and runs on a daemon thread,
 * handing its immutable {@link ChunkIndex} back through {@code server.execute}. The
 * bounding-box question is asked of MTR on the owning simulator's thread and hops back
 * the same way, carrying plain longs. {@link #terrainJson(String)} is called from the
 * simulator thread by the dispatch servlet and only reads one volatile reference to an
 * immutable snapshot.</p>
 *
 * <p><b>Automatic pass.</b> With {@code dispatch.autoScan} on (the default),
 * {@link com.stationannouncer.mtraddon.AddonInit} calls {@link #autoScan()} a few seconds
 * after the server starts. It walks every simulated dimension one at a time and scans
 * only where the cached {@code bbox} does not already contain the generated world — with
 * {@value #CONTAIN_SLACK} blocks of hysteresis on every side, so the handful of chunks a
 * play session generates at the edge of the map does not force a full re-scan every
 * launch. Dimensions MTR is not simulating (no rails, no stations) are skipped as before.</p>
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
    private static final int BUDGET_CHECK_MASK = 63;
    /**
     * Sanity guard, not a coverage cap: the grid is bounded by what exists on disk, and
     * this only exists so a corrupt region name at an absurd coordinate cannot ask for a
     * mask the heap cannot hold. 134 M samples is a 92 000-block square (a 5 800-chunk
     * square) and a 16 MB mask.
     */
    private static final int MAX_SAMPLES = 134_217_728;
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
    /**
     * Hysteresis on the automatic containment check, blocks. The generated world grows by
     * a chunk or two whenever anybody walks anywhere; without slack every launch would
     * re-scan the entire map to pick up 16 blocks of new coastline.
     */
    private static final int CONTAIN_SLACK = 64;

    /** How far the ceiling-dimension probe descends before giving up on a column. */
    private static final int MAX_CEILING_DESCENT = 96;
    /** A ceiling dimension's probe starts this far below its logical roof. */
    private static final int CEILING_HEADROOM = 8;
    /** "No surface here" from {@link #surfaceY}. */
    private static final int NO_SURFACE = Integer.MIN_VALUE;

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
    /** Set while a background thread is reading the dimension's region headers. */
    private static volatile boolean enumerating;
    /** The last background save, joined at shutdown. */
    private static volatile Thread writer;

    /**
     * Accepted and reported for compatibility with {@code /dispatch terrain scan [margin]},
     * but the scan's SCOPE is now the generated world; the margin only pads the box a
     * little past the outermost existing chunk.
     */
    private static final int AUTO_MARGIN = 128;
    /** Dimensions the once-per-launch auto pass still has to check. Server thread only. */
    private static final java.util.ArrayDeque<String> AUTO_QUEUE = new java.util.ArrayDeque<>();
    /** True while that pass is walking the queue (volatile so {@link #isIdle()} is honest). */
    private static volatile boolean autoActive;

    private TerrainScanner() {
    }

    // ------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: remember the server and read whatever was scanned before. */
    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        active = null;
        pending = false;
        enumerating = false;
        autoActive = false;
        AUTO_QUEUE.clear();
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
        // An enumeration thread may still be walking region headers; it publishes only
        // through server.execute, which will not run again, so abandoning it is safe.
        enumerating = false;
        autoActive = false;
        AUTO_QUEUE.clear();
        terrain = Map.of();
        terrainPath = null;
        server = null;
    }

    /**
     * Nothing running: no scan, no simulator round-trip, no region enumeration in flight
     * and no auto pass left to walk. The satellite auto-scan waits on this so the two
     * never sample in the same tick, and
     * {@link com.stationannouncer.mtraddon.AddonInit} polls it once a tick.
     */
    public static boolean isIdle() {
        return active == null && !pending && !enumerating && !autoActive;
    }

    // ------------------------------------------------------------- the ticker

    /**
     * END_SERVER_TICK. Two field reads and a return when no scan is running, which is
     * almost always — this is why it can sit ahead of the countdown early-returns.
     */
    public static void tick() {
        Scan scan = active;
        if (scan == null) {
            // The auto pass is pumped from here rather than chained off finish(): waiting
            // for "nothing is running" each tick is what lets a manual scan cut in front
            // of it without either one having to know about the other.
            if (autoActive && !pending && !enumerating) {
                pumpAuto();
            }
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
            // A cache miss just paid for a chunk load off disk, so re-check the clock
            // immediately rather than after another run of cheap samples. Samples over
            // chunks that do not exist never load anything and are the fast path — hence
            // the wide check mask, which whole-world scanning made worth widening.
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
        if (!scan.chunks.has(chunkX, chunkZ)) {
            // Ungenerated: transparent, and — the whole point of the existence index —
            // NEVER handed to world.getChunk, which would generate it.
            return false;
        }
        boolean loaded = false;
        if (scan.chunk == null || chunkX != scan.chunkX || chunkZ != scan.chunkZ) {
            // The chunk is on disk, so this is a load, not a generation. Vanilla's
            // "unknown" ticket expires after a tick, so the scan does not pin the world
            // in memory as it walks it.
            // CAVEAT (inherent to the region-header technique): a chunk saved at a
            // PARTIAL status — the thin ring of proto-chunks vanilla writes around any
            // explored area — has a nonzero header entry too, and getChunk(FULL)
            // finishes generating that one. It is a perimeter-sized minority and it is
            // work vanilla would do the moment anybody walked there; deciding otherwise
            // would mean decompressing every chunk's NBT just to read its status.
            scan.chunk = scan.world.getChunk(chunkX, chunkZ);
            scan.chunkX = chunkX;
            scan.chunkZ = chunkZ;
            loaded = true;
        }
        WorldChunk chunk = scan.chunk;
        int surfaceY = surfaceY(scan, chunk, x, z);
        if (surfaceY != NO_SURFACE) {
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

    /**
     * The y of the block a map would draw for this column, or {@link #NO_SURFACE}.
     *
     * <p>In an ordinary dimension that is the {@code MOTION_BLOCKING} heightmap: its
     * predicate accepts any non-empty fluid state, so for open water the answer IS the
     * surface water block (bytecode-checked against 1.20.4).</p>
     *
     * <p>In a CEILING dimension (the nether) the heightmap answers the bedrock roof, so
     * this uses Dynmap's technique instead: start just under the logical roof, descend to
     * the first air block, then keep descending to the first non-air below it — the floor
     * a player would be standing on. A column with no air (or no floor under the air)
     * within {@value #MAX_CEILING_DESCENT} steps is left transparent rather than painted
     * as roof.</p>
     */
    private static int surfaceY(Scan scan, WorldChunk chunk, int x, int z) {
        int heightmapY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (!scan.hasCeiling) {
            return heightmapY < scan.bottomY ? NO_SURFACE : heightmapY;
        }
        int y = Math.min(heightmapY, scan.ceilingStartY);
        int steps = 0;
        while (steps < MAX_CEILING_DESCENT && y > scan.bottomY
                && !chunk.getBlockState(scan.cursor.set(x, y, z)).isAir()) {
            y--;
            steps++;
        }
        if (steps >= MAX_CEILING_DESCENT || y <= scan.bottomY) {
            return NO_SURFACE; // solid all the way down: no cavern to draw
        }
        while (steps < MAX_CEILING_DESCENT && y > scan.bottomY
                && chunk.getBlockState(scan.cursor.set(x, y, z)).isAir()) {
            y--;
            steps++;
        }
        return steps >= MAX_CEILING_DESCENT || y <= scan.bottomY ? NO_SURFACE : y;
    }

    // ------------------------------------------------------------ start a scan

    /**
     * {@code /dispatch terrain scan}. Confirms MTR is simulating the caller's dimension,
     * then enumerates the chunks that exist on disk and scans all of them.
     */
    public static int startScan(ServerCommandSource source, int marginBlocks) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            source.sendError(Text.literal("The terrain scanner is not ready yet."));
            return 0;
        }
        if (active != null || pending || enumerating) {
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
        source.sendFeedback(() -> Text.literal("Measuring the network in " + dimension + "…")
                .formatted(Formatting.GRAY), false);
        measure(minecraftServer, target, (any, minX, minZ, maxX, maxZ) -> {
            if (!any) {
                tell(source, Text.literal("No rails or stations in " + dimension + " yet — nothing to scan."), true);
                return;
            }
            tell(source, Text.literal("Reading the region files of " + dimension + "…")
                    .formatted(Formatting.GRAY), false);
            enumerateThen(world, dimension, chunks ->
                    beginScan(source, world, dimension, chunks, margin));
        });
        return 1;
    }

    /** What {@link #measure} hands back, on the server thread, as plain longs. */
    @FunctionalInterface
    private interface BoundsHandler {
        void handle(boolean any, long minX, long minZ, long maxX, long maxZ);
    }

    /**
     * The network's extents, asked of MTR on the owning simulator's thread and handed
     * back on the server thread. The box itself is no longer the scan's scope — only the
     * {@code any} flag still matters, as the "is anything railway-shaped here at all"
     * gate — but the round-trip is kept because it is also what proves the simulator is
     * alive and answering.
     */
    private static void measure(MinecraftServer minecraftServer, Simulator simulator, BoundsHandler handler) {
        pending = true;
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
            minecraftServer.execute(() -> {
                pending = false;
                handler.handle(fAny, fMinX, fMinZ, fMaxX, fMaxZ);
            });
        });
    }

    /**
     * Reads the dimension's region-file headers on a daemon thread and hands the result
     * back on the SERVER thread. File IO for a big world is thousands of 4 KiB reads —
     * milliseconds of wall clock, but not something to spend a tick on.
     */
    static void enumerateThen(ServerWorld world, String dimension, Consumer<ChunkIndex> handler) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            return;
        }
        enumerating = true;
        Thread thread = new Thread(() -> {
            ChunkIndex chunks;
            try {
                chunks = enumerateExistingChunks(minecraftServer, world);
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Could not enumerate the region files of {}", dimension, t);
                chunks = ChunkIndex.empty();
            }
            ChunkIndex result = chunks;
            try {
                minecraftServer.execute(() -> {
                    enumerating = false;
                    handler.accept(result);
                });
            } catch (Throwable t) {
                // The server is going away; nothing to publish to.
                enumerating = false;
            }
        }, "station-announcer-terrain-regions");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Server thread: turn the generated world into a grid and arm the ticker.
     *
     * <p>{@code source} is null for the automatic pass — every message then goes to the
     * log instead of a chat window (see {@link #tell}).</p>
     */
    private static void beginScan(ServerCommandSource source, ServerWorld world, String dimension,
                                  ChunkIndex chunks, int margin) {
        if (active != null) {
            tell(source, Text.literal("A terrain scan started in the meantime — " + status()), true);
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            tell(source, Text.literal("No generated chunks on disk for " + dimension + " — nothing to scan."), true);
            return;
        }
        long lowX = snapDown(chunks.minBlockX() - margin);
        long lowZ = snapDown(chunks.minBlockZ() - margin);
        long highX = snapUp(chunks.maxBlockX() + margin);
        long highZ = snapUp(chunks.maxBlockZ() + margin);
        // Region file names are parsed off disk; a corrupt one at an absurd coordinate
        // would overflow the int grid arithmetic below, so refuse rather than wrap.
        if (Math.abs(lowX) > WORLD_LIMIT || Math.abs(lowZ) > WORLD_LIMIT
                || Math.abs(highX) > WORLD_LIMIT || Math.abs(highZ) > WORLD_LIMIT) {
            tell(source, Text.literal("The generated world of " + dimension + " reaches past the world limit ("
                    + lowX + "," + lowZ + " to " + highX + "," + highZ + ") — check for a stray region file."), true);
            return;
        }
        long width = (highX - lowX) / GRID + 1;
        long height = (highZ - lowZ) / GRID + 1;
        long total = width * height;
        if (total > MAX_SAMPLES) {
            tell(source, Text.literal("The generated world of " + dimension + " spans " + width + "×" + height
                    + " samples on a " + GRID + "-block grid, past the " + MAX_SAMPLES + " sanity guard."
                    + " Check for a stray region file far from everything else."), true);
            return;
        }

        Scan scan = new Scan(world, dimension, source, chunks, (int) lowX, (int) lowZ,
                (int) width, (int) height, lowX, lowZ, highX, highZ);
        active = scan;
        tell(source, Text.literal("Terrain scan started: " + chunks.count() + " generated chunk(s), "
                        + total + " samples over " + (highX - lowX) + "×" + (highZ - lowZ) + " blocks.")
                .formatted(Formatting.AQUA), false);
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
        tell(scan.source, text, error);
    }

    /**
     * One message to whoever asked for the scan. A manual scan has a command source and
     * gets chat (broadcast to ops, as before); the automatic pass has NONE — its source
     * is null and the server log is the only audience, at info for progress and warn for
     * refusals.
     */
    private static void tell(ServerCommandSource source, Text text, boolean error) {
        if (source == null) {
            if (error) {
                StationAnnouncer.LOGGER.warn("Terrain auto-scan: {}", text.getString());
            } else {
                StationAnnouncer.LOGGER.info("Terrain auto-scan: {}", text.getString());
            }
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

    // ------------------------------------------------------- the automatic pass

    /**
     * Queues the once-per-launch automatic check: every dimension MTR is simulating gets
     * its generated world measured and, if the cached water polygons do not already cover
     * it, re-scanned. Called from {@link com.stationannouncer.mtraddon.AddonInit} a few
     * seconds after SERVER_STARTED; the work itself happens in {@link #pumpAuto}, one
     * dimension at a time, so the tick budget is never more than one scan's worth.
     */
    public static void autoScan() {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null || autoActive) {
            return;
        }
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            StationAnnouncer.LOGGER.info("Terrain auto-scan: MTR has no running simulations, nothing to check");
            return;
        }
        AUTO_QUEUE.clear();
        for (Simulator simulator : simulators) {
            AUTO_QUEUE.add(simulator.dimension);
        }
        autoActive = true;
    }

    /**
     * Server thread, one dimension per call, only while nothing else is running. Takes
     * the next queued dimension and measures it; the decision lands in
     * {@link #autoDecide} once the simulator and the region files have both answered.
     */
    private static void pumpAuto() {
        MinecraftServer minecraftServer = server;
        String dimension = AUTO_QUEUE.poll();
        if (minecraftServer == null || dimension == null) {
            autoActive = false;
            AUTO_QUEUE.clear();
            if (minecraftServer != null) {
                StationAnnouncer.LOGGER.info("Terrain auto-scan pass finished");
            }
            return;
        }
        Simulator simulator = null;
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators != null) {
            for (Simulator candidate : simulators) {
                if (dimension.equals(candidate.dimension)) {
                    simulator = candidate;
                    break;
                }
            }
        }
        ServerWorld world = worldFor(minecraftServer, dimension);
        if (simulator == null || world == null) {
            StationAnnouncer.LOGGER.info("Terrain auto-scan: no simulator/world pair for {}, skipping", dimension);
            return; // the next tick pumps the next dimension
        }
        measure(minecraftServer, simulator, (any, minX, minZ, maxX, maxZ) -> {
            if (!any) {
                // Unchanged rule: a dimension MTR simulates but nothing runs in is skipped
                // outright, however much of it happens to be generated.
                StationAnnouncer.LOGGER.info("Terrain auto-scan: no rails or stations in {} yet, skipping", dimension);
                return;
            }
            enumerateThen(world, dimension, chunks -> autoDecide(world, dimension, chunks));
        });
    }

    /**
     * Server thread: scan this dimension, or skip it because the cache already covers the
     * generated world.
     *
     * <p>The cached {@code bbox} is the box that scan actually walked. If it contains the
     * box the generated world needs now — allowing {@value #CONTAIN_SLACK} blocks of
     * slack on every side, so ordinary play at the edge of the map does not trigger a
     * full re-scan — every polygon the map would draw is already cached. Otherwise the
     * whole dimension is re-scanned: traced polygons are not mergeable across scans (a
     * ring that crossed the old edge would have been closed against it).</p>
     */
    private static void autoDecide(ServerWorld world, String dimension, ChunkIndex chunks) {
        if (chunks == null || chunks.isEmpty()) {
            StationAnnouncer.LOGGER.info("Terrain auto-scan: no generated chunks on disk for {}, skipping", dimension);
            return;
        }
        long lowX = snapDown(chunks.minBlockX() - AUTO_MARGIN);
        long lowZ = snapDown(chunks.minBlockZ() - AUTO_MARGIN);
        long highX = snapUp(chunks.maxBlockX() + AUTO_MARGIN);
        long highZ = snapUp(chunks.maxBlockZ() + AUTO_MARGIN);
        Terrain cached = terrain.get(dimension);
        if (cached != null && covers(cached.bbox(), lowX, lowZ, highX, highZ)) {
            long[] bbox = cached.bbox();
            StationAnnouncer.LOGGER.info("Terrain cache covers the generated world of {} (cached {},{}..{},{}"
                            + " contains needed {},{}..{},{} within {} blocks) — skipping the automatic scan",
                    dimension, bbox[0], bbox[1], bbox[2], bbox[3], lowX, lowZ, highX, highZ, CONTAIN_SLACK);
            return;
        }
        if (cached == null) {
            StationAnnouncer.LOGGER.info("Terrain auto-scan: {} has no cached water polygons — scanning"
                    + " {} chunk(s) over {},{}..{},{}", dimension, chunks.count(), lowX, lowZ, highX, highZ);
        } else {
            long[] bbox = cached.bbox();
            StationAnnouncer.LOGGER.info("Terrain auto-scan: the generated world of {} has grown outside the"
                            + " cached box ({},{}..{},{} → {},{}..{},{}) — re-scanning {} chunk(s)",
                    dimension, bbox[0], bbox[1], bbox[2], bbox[3], lowX, lowZ, highX, highZ, chunks.count());
        }
        beginScan(null, world, dimension, chunks, AUTO_MARGIN);
    }

    /**
     * True when the cached scanned box encloses the box the generated world needs now,
     * to within {@value #CONTAIN_SLACK} blocks on every side.
     */
    private static boolean covers(long[] bbox, long lowX, long lowZ, long highX, long highZ) {
        // An all-zero bbox is a pre-bbox cache file (or a damaged one): treat as no cover.
        if (bbox == null || bbox.length != 4 || (bbox[0] == 0 && bbox[1] == 0 && bbox[2] == 0 && bbox[3] == 0)) {
            return false;
        }
        return bbox[0] - CONTAIN_SLACK <= lowX && bbox[1] - CONTAIN_SLACK <= lowZ
                && bbox[2] + CONTAIN_SLACK >= highX && bbox[3] + CONTAIN_SLACK >= highZ;
    }

    /** The ServerWorld whose MTR dimension id is {@code dimension}, or null. */
    private static ServerWorld worldFor(MinecraftServer minecraftServer, String dimension) {
        for (ServerWorld world : minecraftServer.getWorlds()) {
            if (dimension.equals(worldId(world))) {
                return world;
            }
        }
        return null;
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
        if (enumerating) {
            return "reading region files…";
        }
        if (autoActive) {
            return "automatic pass running (" + AUTO_QUEUE.size() + " dimension(s) left to check)";
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

    // ------------------------------------------------- existing-chunk enumeration

    /**
     * Which chunks a dimension has ON DISK, read out of its region files' headers.
     *
     * <p>A region file's first 4 KiB is the locations table: 1024 big-endian ints, one
     * per chunk of the 32x32 region, in {@code (localZ * 32 + localX)} order. A nonzero
     * entry means that chunk has been written. That is the whole of Dynmap's
     * "what exists" question, and it costs one 4 KiB read per region — no NBT, no
     * decompression, and above all no chunk generation.</p>
     *
     * <p>Storage is one {@code long[32]} per region — a bitmap row per local z — in a
     * hash map keyed by region coordinate. 256 bytes per fully-populated region, so a
     * million-chunk world (≈1 000 regions) costs about a quarter of a megabyte plus map
     * overhead. Reads go through a one-entry region cache because the scanners walk in
     * row-major order and stay inside one region for 32 chunks at a time.</p>
     *
     * <p>Built on a background thread, then read ONLY from the server thread during a
     * scan (the cache field is why: it is deliberately not synchronized).</p>
     */
    public static final class ChunkIndex {
        private static final ChunkIndex EMPTY =
                new ChunkIndex(Map.of(), 0, 0, -1, -1, 0);

        private final Map<Long, long[]> regions;
        private final int minChunkX;
        private final int minChunkZ;
        private final int maxChunkX;
        private final int maxChunkZ;
        private final int count;

        /** One-entry region cache. Server thread only. */
        private long[] cachedBits;
        private int cachedRegionX;
        private int cachedRegionZ;
        private boolean cachedValid;

        private ChunkIndex(Map<Long, long[]> regions, int minChunkX, int minChunkZ,
                           int maxChunkX, int maxChunkZ, int count) {
            this.regions = regions;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX;
            this.maxChunkZ = maxChunkZ;
            this.count = count;
        }

        public static ChunkIndex empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return count == 0;
        }

        /** How many chunks exist on disk. */
        public int count() {
            return count;
        }

        public int minBlockX() {
            return minChunkX << 4;
        }

        public int minBlockZ() {
            return minChunkZ << 4;
        }

        public int maxBlockX() {
            return (maxChunkX << 4) + 15;
        }

        public int maxBlockZ() {
            return (maxChunkZ << 4) + 15;
        }

        /** Does this chunk exist on disk? Server thread only (see the one-entry cache). */
        public boolean has(int chunkX, int chunkZ) {
            if (count == 0 || chunkX < minChunkX || chunkX > maxChunkX
                    || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                return false;
            }
            int regionX = chunkX >> 5;
            int regionZ = chunkZ >> 5;
            if (!cachedValid || regionX != cachedRegionX || regionZ != cachedRegionZ) {
                cachedBits = regions.get(regionKey(regionX, regionZ));
                cachedRegionX = regionX;
                cachedRegionZ = regionZ;
                cachedValid = true;
            }
            long[] bits = cachedBits;
            return bits != null && ((bits[chunkZ - (regionZ << 5)] >>> (chunkX - (regionX << 5))) & 1L) != 0;
        }

        /** Does ANY chunk in this inclusive chunk rectangle exist? Used to skip empty tiles. */
        public boolean anyIn(int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
            if (count == 0 || chunkX1 < minChunkX || chunkX0 > maxChunkX
                    || chunkZ1 < minChunkZ || chunkZ0 > maxChunkZ) {
                return false;
            }
            int x0 = Math.max(chunkX0, minChunkX);
            int z0 = Math.max(chunkZ0, minChunkZ);
            int x1 = Math.min(chunkX1, maxChunkX);
            int z1 = Math.min(chunkZ1, maxChunkZ);
            for (int regionZ = z0 >> 5; regionZ <= (z1 >> 5); regionZ++) {
                int baseZ = regionZ << 5;
                int localZ0 = Math.max(z0, baseZ) - baseZ;
                int localZ1 = Math.min(z1, baseZ + 31) - baseZ;
                for (int regionX = x0 >> 5; regionX <= (x1 >> 5); regionX++) {
                    long[] bits = regions.get(regionKey(regionX, regionZ));
                    if (bits == null) {
                        continue;
                    }
                    int baseX = regionX << 5;
                    int localX0 = Math.max(x0, baseX) - baseX;
                    int localX1 = Math.min(x1, baseX + 31) - baseX;
                    // Width 32 shifts by 32, which is legal on a long, so no special case.
                    long mask = ((1L << (localX1 - localX0 + 1)) - 1) << localX0;
                    for (int localZ = localZ0; localZ <= localZ1; localZ++) {
                        if ((bits[localZ] & mask) != 0) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static long regionKey(int regionX, int regionZ) {
            return (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
        }
    }

    /**
     * Background thread: {@code <dimension folder>/region/r.X.Z.mca} → {@link ChunkIndex}.
     *
     * <p>The dimension folder is
     * {@code DimensionType.getSaveDirectory(world.getRegistryKey(), <save root>)}
     * (bytecode-verified on 1.20.4: overworld = the save root itself, nether =
     * {@code DIM-1}, end = {@code DIM1}, anything else =
     * {@code dimensions/<namespace>/<path>}).</p>
     */
    static ChunkIndex enumerateExistingChunks(MinecraftServer minecraftServer, ServerWorld world) {
        Path regionDir = DimensionType
                .getSaveDirectory(world.getRegistryKey(), minecraftServer.getSavePath(WorldSavePath.ROOT))
                .resolve("region").normalize();
        if (!Files.isDirectory(regionDir)) {
            return ChunkIndex.empty();
        }
        Map<Long, long[]> regions = new HashMap<>();
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        int count = 0;
        byte[] header = new byte[4096];
        List<Path> files;
        try (Stream<Path> stream = Files.list(regionDir)) {
            files = stream.toList();
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not list {}", regionDir, e);
            return ChunkIndex.empty();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!name.startsWith("r.") || !name.endsWith(".mca")) {
                continue;
            }
            String[] parts = name.split("\\.");
            if (parts.length != 4) {
                continue;
            }
            int regionX;
            int regionZ;
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!readHeader(file, header)) {
                continue;
            }
            long[] bits = new long[32];
            int present = 0;
            for (int i = 0; i < 1024; i++) {
                int at = i << 2;
                // Three-byte sector offset plus a one-byte sector count; any nonzero
                // entry means the chunk has been written to this region.
                if ((header[at] | header[at + 1] | header[at + 2] | header[at + 3]) == 0) {
                    continue;
                }
                int localX = i & 31;
                int localZ = i >> 5;
                bits[localZ] |= 1L << localX;
                present++;
                int chunkX = (regionX << 5) + localX;
                int chunkZ = (regionZ << 5) + localZ;
                if (chunkX < minChunkX) {
                    minChunkX = chunkX;
                }
                if (chunkX > maxChunkX) {
                    maxChunkX = chunkX;
                }
                if (chunkZ < minChunkZ) {
                    minChunkZ = chunkZ;
                }
                if (chunkZ > maxChunkZ) {
                    maxChunkZ = chunkZ;
                }
            }
            if (present > 0) {
                regions.put(ChunkIndex.regionKey(regionX, regionZ), bits);
                count += present;
            }
        }
        if (count == 0) {
            return ChunkIndex.empty();
        }
        return new ChunkIndex(regions, minChunkX, minChunkZ, maxChunkX, maxChunkZ, count);
    }

    /** Reads exactly the 4 KiB locations table; a short or unreadable file is skipped. */
    private static boolean readHeader(Path file, byte[] header) {
        try (InputStream in = Files.newInputStream(file)) {
            int read = 0;
            while (read < header.length) {
                int got = in.read(header, read, header.length - read);
                if (got < 0) {
                    break;
                }
                read += got;
            }
            if (read < header.length) {
                // An empty (0-byte) region file is ordinary; anything shorter than a full
                // header has no chunks we can name.
                return false;
            }
            return true;
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not read the header of {}", file, e);
            return false;
        }
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
        /** Which chunks exist on disk; samples outside it never touch the world. */
        private final ChunkIndex chunks;
        private final int originX;
        private final int originZ;
        private final int width;
        private final int height;
        private final long minX;
        private final long minZ;
        private final long maxX;
        private final long maxZ;
        private final long[] mask;
        private final int bottomY;
        /** Nether-style roof: the heightmap is useless, so probe down (see surfaceY). */
        private final boolean hasCeiling;
        private final int ceilingStartY;
        private final BlockPos.Mutable cursor = new BlockPos.Mutable();
        private int index;
        private int water;
        private WorldChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;

        private Scan(ServerWorld world, String dimension, ServerCommandSource source, ChunkIndex chunks,
                     int originX, int originZ, int width, int height,
                     long minX, long minZ, long maxX, long maxZ) {
            this.world = world;
            this.dimension = dimension;
            this.source = source;
            this.chunks = chunks;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.height = height;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.mask = new long[(width * height + 63) >> 6];
            this.bottomY = world.getBottomY();
            DimensionType type = world.getDimension();
            this.hasCeiling = type.hasCeiling();
            this.ceilingStartY = this.bottomY + Math.max(1, type.logicalHeight() - CEILING_HEADROOM);
        }
    }
}
