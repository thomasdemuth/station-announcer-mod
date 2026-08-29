package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The dispatch map's satellite basemap: a vanilla-held-map-style raster of the WHOLE
 * GENERATED WORLD, scanned by {@code /dispatch satellite scan} or by the automatic
 * once-per-launch pass, and served as 256x256-sample PNG tiles.
 *
 * <p>This is {@link TerrainScanner}'s architecture a second time over, deliberately
 * duplicated rather than shared: a completely separate state machine (its own
 * {@link #active} scan, its own budget, its own save directory, its own auto queue) so
 * both can be running in the same session without either one's progress, cache or
 * command feedback touching the other. The one thing it borrows is
 * {@link TerrainScanner.ChunkIndex} — the region-header read that decides which chunks
 * exist on disk, which there is no reason to implement twice.</p>
 *
 * <p><b>Scope: what exists, never what could exist.</b> The scan covers the bounding box
 * of every chunk the dimension has on disk. A sample whose chunk is not in the existence
 * index is transparent and NEVER handed to {@code world.getChunk} — the scanner cannot
 * generate terrain, only photograph it. Tiles that contain no existing chunk at all are
 * skipped outright: they are neither sampled nor written nor indexed, so a sparse world
 * costs a sparse basemap.</p>
 *
 * <p><b>Sampling.</b> One sample per {@value #SCALE} blocks. Per sample: the surface from
 * the chunk's {@code MOTION_BLOCKING} heightmap (or, in a CEILING dimension like the
 * nether, a downward probe — see {@link #surfaceY}), that block's
 * {@link BlockState#getMapColor} and its height. Shading is vanilla's: compare the height
 * with the sample one step NORTH — higher is {@link MapColor.Brightness#HIGH}, equal
 * {@code NORMAL}, lower {@code LOW} — except on water, which is banded by depth like a
 * real map (shallow bright, deep dark). Missing/void samples and {@link MapColor#CLEAR}
 * are transparent.</p>
 *
 * <p><b>One tile at a time.</b> Sampling walks the wanted tiles in order and keeps only
 * ONE tile's buffers (two 64 KB arrays) alive — a whole-world scan cannot afford the
 * single big raster the railway-sized scan used. Each tile is preceded by a one-row
 * "seed" pass over the strip immediately north of it, so the north-shading of a tile's
 * top row is continuous with its neighbour instead of showing a seam.</p>
 *
 * <p><b>A stable tile grid.</b> The origin is established by the FIRST scan of a
 * dimension and then kept forever: every later scan measures its tiles from that same
 * corner, so tile (0,0) always names the same 512 blocks and existing tiles stay valid.
 * A world that grows toward -x/-z therefore produces NEGATIVE tile indices — legal
 * everywhere: in the file names ({@code -1_2.png}), the index, {@code satmeta} and the
 * {@code sattile} endpoint.</p>
 *
 * <p><b>Full refresh vs incremental, and resuming.</b> The manual command always does a
 * FULL refresh: every tile with chunks under it is re-sampled and the dimension's
 * directory is wiped first, so stale tiles from a bigger previous scan cannot survive.
 * The automatic pass is INCREMENTAL: it samples only tiles that do not exist yet, merges
 * them into the index and never deletes anything. That is also the resume story — a scan
 * interrupted by a server stop leaves every completed tile in the index, and the next
 * launch's diff simply asks for the rest. To make that true of a multi-hour first scan as
 * well, finished tiles are encoded and PUBLISHED IN BATCHES of {@value #FLUSH_TILES}
 * during the scan rather than only at the end, so the live map fills in as it goes.</p>
 *
 * <p><b>Threading.</b> Everything except {@link #satelliteJson(String)} and
 * {@link #tile(String, int, int)} runs on the SERVER thread, in
 * {@value #TICK_BUDGET_MILLIS} ms slices from
 * {@link com.stationannouncer.mtraddon.AddonInit}'s tick handler (the sampler reads
 * chunks, so it cannot live on a simulator thread). Region-header enumeration and PNG
 * encoding happen on daemon threads; a batch handed to the encoder is a fresh list of
 * arrays the server thread will never touch again, and only one encode runs at a time
 * ({@link #encoding}), which is what keeps the published index single-writer. The two
 * getters are called from Jetty workers and only read one volatile reference to an
 * immutable snapshot (plus, for tiles, an immutable file on disk).</p>
 *
 * <p>Nothing here is required for the map to work: with no scan ever run, the metadata
 * getter answers {@code available:false} and the frontend draws no basemap.</p>
 */
public final class SatelliteScanner {
    /** Sample spacing, blocks. One raster pixel covers a {@value #SCALE}x{@value #SCALE} cell. */
    public static final int SCALE = 2;
    /** Tile edge in samples (= PNG pixels). */
    public static final int TILE_SAMPLES = 256;
    /** Tile edge in blocks. Scan bounds always land on tile boundaries. */
    public static final int TILE_BLOCKS = TILE_SAMPLES * SCALE;
    /** Tile edge in chunks; used to ask the existence index whether a tile is worth drawing. */
    private static final int TILE_CHUNKS = TILE_BLOCKS / 16;

    /** Per-tick sampling budget. Two milliseconds of a fifty-millisecond tick. */
    private static final long TICK_BUDGET_MILLIS = 2;
    private static final long TICK_BUDGET_NANOS = TICK_BUDGET_MILLIS * 1_000_000L;
    /** Check the clock this often (in samples) while the chunk cache is hitting. */
    private static final int BUDGET_CHECK_MASK = 15;
    /**
     * Sanity guard on the tile rectangle the diff walks, not a coverage cap: coverage is
     * bounded by what exists on disk. A million tiles is a 512 000-block square.
     */
    private static final int MAX_TILE_RECT = 1_048_576;
    /** Encode and publish after this many finished tiles, so a long scan shows progress. */
    private static final int FLUSH_TILES = 64;
    /** How far below the surface the water-depth probe looks before calling it "deep". */
    private static final int MAX_WATER_PROBE = 8;
    /** A CLEAR surface (glass, a torch on a roof) looks this far down for real colour. */
    private static final int MAX_CLEAR_DESCENT = 4;
    /** How far the ceiling-dimension probe descends before giving up on a column. */
    private static final int MAX_CEILING_DESCENT = 96;
    /** A ceiling dimension's probe starts this far below its logical roof. */
    private static final int CEILING_HEADROOM = 8;
    /** "No surface here" from {@link #surfaceY}. */
    private static final int NO_SURFACE = Integer.MIN_VALUE;
    /** Vanilla's world border cap; beyond it the int grid arithmetic would overflow. */
    private static final long WORLD_LIMIT = 30_000_000L;
    /**
     * Accepted and reported for compatibility with {@code /dispatch satellite scan
     * [margin]}, but the scan's SCOPE is now the generated world; the margin only pads
     * the box a little past the outermost existing chunk.
     */
    private static final int AUTO_MARGIN = 128;

    /** Shade codes stored per sample; index into {@link #BRIGHTNESSES}. */
    private static final byte SHADE_NONE = 0;
    private static final byte SHADE_LOW = 1;
    private static final byte SHADE_NORMAL = 2;
    private static final byte SHADE_HIGH = 3;
    /** Index-aligned with the SHADE_* codes; slot 0 is never read (transparent). */
    private static final MapColor.Brightness[] BRIGHTNESSES = {
            MapColor.Brightness.NORMAL, MapColor.Brightness.LOW,
            MapColor.Brightness.NORMAL, MapColor.Brightness.HIGH
    };
    /** "No sample here" in a height row buffer. */
    private static final short NO_HEIGHT = Short.MIN_VALUE;

    /** Set on SERVER_STARTED; the scanner's own reference, independent of TerrainScanner's. */
    private static volatile MinecraftServer server;
    /** {@code <save>/station-announcer-addon/satellite}, or null before the server starts. */
    private static volatile Path root;
    /** Immutable published index, sanitized dimension directory name → result. Read cross-thread. */
    private static volatile Map<String, Satellite> index = Map.of();
    /** The scan in progress. Server thread only (volatile so {@link #status()} is honest). */
    private static volatile Scan active;
    /** Set between the command and the simulator round-trip so two clicks cannot both start. */
    private static volatile boolean pending;
    /** Set while a background thread is reading the dimension's region headers. */
    private static volatile boolean enumerating;
    /** True while the writer thread is turning a batch of finished tiles into PNGs. */
    private static volatile boolean encoding;
    /** The last background encode, joined at shutdown. */
    private static volatile Thread writer;
    /** Dimensions the once-per-launch auto pass still has to check. Server thread only. */
    private static final ArrayDeque<String> AUTO_QUEUE = new ArrayDeque<>();
    /** True while that pass is walking the queue (volatile so {@link #isIdle()} is honest). */
    private static volatile boolean autoActive;

    private SatelliteScanner() {
    }

    // ------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: remember the server and read whatever tiles were written before. */
    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        active = null;
        pending = false;
        enumerating = false;
        encoding = false;
        autoActive = false;
        AUTO_QUEUE.clear();
        Path path = minecraftServer.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("satellite").normalize();
        root = path;
        // Only the indexes are read here — never the pixels. A basemap can be hundreds of
        // megabytes of PNG; the servlet streams individual tiles straight off the disk.
        index = read(path);
        if (!index.isEmpty()) {
            int tiles = 0;
            for (Satellite entry : index.values()) {
                tiles += entry.tiles().size();
            }
            StationAnnouncer.LOGGER.info("Satellite basemap loaded ({} dimension(s), {} tiles)",
                    index.size(), tiles);
        }
    }

    /** SERVER_STOPPING: finish any queued encode on this thread, then forget everything. */
    public static void onServerStopping() {
        Thread pendingWrite = writer;
        if (pendingWrite != null) {
            try {
                // A batch is at most FLUSH_TILES PNGs, so this is a short wait. Tiles are
                // moved into place atomically and the index is written last, so a batch
                // that does not finish simply is not published — the next launch's diff
                // asks for those tiles again.
                pendingWrite.join(10_000);
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
        encoding = false;
        autoActive = false;
        AUTO_QUEUE.clear();
        index = Map.of();
        root = null;
        server = null;
    }

    /**
     * Nothing running: no scan, no simulator round-trip, no region enumeration, no tile
     * encode and no auto pass left to walk.
     * {@link com.stationannouncer.mtraddon.AddonInit} waits on the TERRAIN scanner's
     * version of this before kicking our automatic pass, so the two never sample in the
     * same tick.
     */
    public static boolean isIdle() {
        return active == null && !pending && !enumerating && !encoding && !autoActive;
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
            if (autoActive && !pending && !enumerating && !encoding) {
                pumpAuto();
            }
            return;
        }
        if (scan.tileIndex >= scan.tiles.size()) {
            drain(scan);
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        while (scan.tileIndex < scan.tiles.size()) {
            boolean loadedChunk;
            try {
                loadedChunk = sample(scan);
            } catch (Throwable t) {
                active = null;
                StationAnnouncer.LOGGER.warn("Satellite scan of {} failed while sampling", scan.dimension, t);
                report(scan, Text.literal("Satellite scan failed while reading the world — see the server log.")
                        .formatted(Formatting.RED), true);
                return;
            }
            scan.index++;
            scan.sampled++;
            if (scan.index >= Scan.SAMPLES_PER_TILE) {
                completeTile(scan);
                if (scan.ready.size() >= FLUSH_TILES) {
                    flush(scan, false);
                }
                return; // one tile boundary per tick keeps the batching predictable
            }
            // A cache miss just paid for a chunk load off disk, so re-check the clock
            // immediately rather than after another fifteen cheap samples.
            if ((loadedChunk || (scan.index & BUDGET_CHECK_MASK) == 0) && System.nanoTime() >= deadline) {
                return;
            }
        }
        drain(scan);
    }

    /**
     * Sampling is done: push whatever is still buffered at the encoder, one batch a tick,
     * and only retire the scan once the last batch has actually been written.
     */
    private static void drain(Scan scan) {
        if (!scan.ready.isEmpty()) {
            flush(scan, true);
            return;
        }
        if (encoding) {
            return; // the last batch is still being written
        }
        active = null;
        report(scan, Text.literal("Satellite scan complete: " + scan.tiles.size() + " tile(s), "
                + scan.sampled + " samples (" + scan.water + " water).").formatted(Formatting.GREEN), false);
        StationAnnouncer.LOGGER.info("Satellite scan of {} complete: {} tile(s), {} samples ({} water)",
                scan.dimension, scan.tiles.size(), scan.sampled, scan.water);
    }

    /** @return true when this sample had to fetch a new chunk. */
    private static boolean sample(Scan scan) {
        int[] tile = scan.tiles.get(scan.tileIndex);
        int row = scan.index / TILE_SAMPLES;
        int column = scan.index % TILE_SAMPLES;
        if (column == 0 && row > 0) {
            // New row: the row just finished becomes the north neighbours. Only two rows
            // are ever kept — row 0 of every tile is the SEED row, sampled one step north
            // of the tile so its top edge shades against its neighbour, not against void.
            short[] finished = scan.curRow;
            scan.curRow = scan.prevRow;
            scan.prevRow = finished;
        }
        int x = scan.stableOriginX + tile[0] * TILE_BLOCKS + column * SCALE;
        int z = scan.stableOriginZ + tile[1] * TILE_BLOCKS + (row - 1) * SCALE;
        int out = row == 0 ? -1 : (row - 1) * TILE_SAMPLES + column;
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!scan.chunks.has(chunkX, chunkZ)) {
            // Ungenerated: transparent, and — the whole point of the existence index —
            // NEVER handed to world.getChunk, which would generate it.
            if (out >= 0) {
                scan.colors[out] = 0;
                scan.shade[out] = SHADE_NONE;
            }
            scan.curRow[column] = NO_HEIGHT;
            return false;
        }
        boolean loaded = false;
        if (scan.chunk == null || chunkX != scan.chunkX || chunkZ != scan.chunkZ) {
            // The chunk is on disk, so this is a load, not a generation. Vanilla's
            // "unknown" ticket expires after a tick, so the scan does not pin the world
            // in memory as it walks it. (Same caveat as the terrain scanner: a chunk
            // saved at a PARTIAL status still has a nonzero header entry, and this call
            // finishes that one. It is a perimeter-sized minority.)
            scan.chunk = scan.world.getChunk(chunkX, chunkZ);
            scan.chunkX = chunkX;
            scan.chunkZ = chunkZ;
            loaded = true;
        }
        WorldChunk chunk = scan.chunk;
        int bottomY = scan.bottomY;
        int surfaceY = surfaceY(scan, chunk, x, z);
        if (surfaceY == NO_SURFACE) {
            if (out >= 0) {
                scan.colors[out] = 0;
                scan.shade[out] = SHADE_NONE;
            }
            scan.curRow[column] = NO_HEIGHT;
            return loaded;
        }

        int y = surfaceY;
        scan.cursor.set(x, y, z);
        BlockState state = chunk.getBlockState(scan.cursor);
        // The world (not the chunk) is the BlockView: the handful of blocks whose map
        // colour depends on a neighbour must be able to look past the chunk border.
        MapColor color = state.getMapColor(scan.world, scan.cursor);
        for (int descent = 0; color == MapColor.CLEAR && descent < MAX_CLEAR_DESCENT && y - 1 >= bottomY; descent++) {
            // A glass roof or a snow-covered slab reads as a hole otherwise. Vanilla's own
            // map renderer descends the same way; four blocks is enough for a pane or two
            // and costs nothing on the (overwhelmingly common) opaque surface.
            y--;
            scan.cursor.set(x, y, z);
            state = chunk.getBlockState(scan.cursor);
            color = state.getMapColor(scan.world, scan.cursor);
        }
        if (color == null || color == MapColor.CLEAR) {
            if (out >= 0) {
                scan.colors[out] = 0;
                scan.shade[out] = SHADE_NONE;
            }
            scan.curRow[column] = NO_HEIGHT;
            return loaded;
        }

        byte shade;
        // Fluid state rather than block state: catches source, flowing and waterlogged
        // surfaces (a fence or slab standing in water) in one test, same as the terrain scan.
        FluidState fluid = chunk.getFluidState(x, surfaceY, z);
        if (fluid.isIn(FluidTags.WATER)) {
            int probeY = surfaceY - 1;
            int probes = 0;
            while (probes < MAX_WATER_PROBE && probeY >= bottomY
                    && chunk.getFluidState(x, probeY, z).isIn(FluidTags.WATER)) {
                probeY--;
                probes++;
            }
            int depth = surfaceY - probeY; // blocks of water, surface block included
            shade = depth <= 2 ? SHADE_HIGH : depth <= 5 ? SHADE_NORMAL : SHADE_LOW;
            if (out >= 0) {
                scan.water++;
            }
        } else {
            short north = scan.prevRow[column];
            shade = north == NO_HEIGHT || y == north ? SHADE_NORMAL : y > north ? SHADE_HIGH : SHADE_LOW;
        }
        if (out >= 0) {
            scan.colors[out] = (byte) color.id;
            scan.shade[out] = shade;
        }
        scan.curRow[column] = (short) y;
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

    /** Server thread: hand the finished tile to the pending batch and start the next one. */
    private static void completeTile(Scan scan) {
        int[] tile = scan.tiles.get(scan.tileIndex);
        scan.ready.add(new Ready(tile[0], tile[1], scan.colors, scan.shade));
        // Fresh buffers: the encoder owns the ones just handed over.
        scan.colors = new byte[TILE_SAMPLES * TILE_SAMPLES];
        scan.shade = new byte[TILE_SAMPLES * TILE_SAMPLES];
        java.util.Arrays.fill(scan.prevRow, NO_HEIGHT);
        java.util.Arrays.fill(scan.curRow, NO_HEIGHT);
        scan.tileIndex++;
        scan.index = 0;
    }

    /**
     * Server thread: hand the buffered tiles to the encoder, unless one batch is still in
     * flight — in which case the tiles simply stay buffered and the next boundary (or the
     * next tick, once sampling is done) tries again. Never blocks the tick.
     */
    private static void flush(Scan scan, boolean last) {
        if (encoding || scan.ready.isEmpty()) {
            return;
        }
        Path base = root;
        if (base == null) {
            scan.ready.clear();
            return;
        }
        List<Ready> batch = scan.ready;
        scan.ready = new ArrayList<>();
        boolean wipe = scan.needsWipe;
        scan.needsWipe = false;
        long scannedAt = System.currentTimeMillis();
        String dimension = scan.dimension;
        int originX = scan.stableOriginX;
        int originZ = scan.stableOriginZ;
        long[] bbox = {scan.bboxMinX, scan.bboxMinZ, scan.bboxMaxX, scan.bboxMaxZ};
        int done = scan.tileIndex;
        int total = scan.tiles.size();
        encoding = true;
        Thread thread = new Thread(() -> encode(dimension, scannedAt, originX, originZ, batch, wipe,
                bbox, done, total, last), "station-announcer-satellite-encode");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
    }

    // ------------------------------------------------------------ start a scan

    /**
     * {@code /dispatch satellite scan}. Confirms MTR is simulating the caller's dimension,
     * then enumerates the chunks that exist on disk and rasters all of them.
     *
     * <p>Always a FULL refresh: every tile with chunks under it is re-sampled and the
     * dimension's tile directory is wiped (at the first batch) before anything new lands.
     * That is the point of the command — "the world changed, redraw it". The automatic
     * pass is the incremental one.</p>
     */
    public static int startScan(ServerCommandSource source, int marginBlocks) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            source.sendError(Text.literal("The satellite scanner is not ready yet."));
            return 0;
        }
        if (active != null || pending || enumerating || encoding) {
            source.sendError(Text.literal("A satellite scan is already running — " + status()));
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
        Simulator target = simulatorFor(dimension);
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
                    beginScan(source, world, dimension, chunks, margin, false));
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
                StationAnnouncer.LOGGER.warn("Satellite bounding-box scan failed for dimension {}",
                        simulator.dimension, t);
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
     * back on the SERVER thread. The reader itself lives in {@link TerrainScanner} —
     * there is exactly one right way to ask "which chunks exist" and no reason to write
     * it twice — but the thread and the {@link #enumerating} flag are ours, so both
     * scanners can be enumerating different dimensions at once without interfering.
     */
    private static void enumerateThen(ServerWorld world, String dimension,
                                      Consumer<TerrainScanner.ChunkIndex> handler) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            return;
        }
        enumerating = true;
        Thread thread = new Thread(() -> {
            TerrainScanner.ChunkIndex chunks;
            try {
                chunks = TerrainScanner.enumerateExistingChunks(minecraftServer, world);
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Could not enumerate the region files of {}", dimension, t);
                chunks = TerrainScanner.ChunkIndex.empty();
            }
            TerrainScanner.ChunkIndex result = chunks;
            try {
                minecraftServer.execute(() -> {
                    enumerating = false;
                    handler.accept(result);
                });
            } catch (Throwable t) {
                // The server is going away; nothing to publish to.
                enumerating = false;
            }
        }, "station-announcer-satellite-regions");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Server thread: turn the generated world into a tile list and arm the ticker.
     *
     * <p>{@code source} is null for the automatic pass — every message then goes to the
     * log instead of a chat window (see {@link #tell}). {@code incremental} picks the two
     * behaviours that differ: which tiles are sampled (missing ones only, versus all of
     * them) and what happens to the tiles already on disk (kept and merged, versus
     * deleted for a clean full refresh).</p>
     */
    private static void beginScan(ServerCommandSource source, ServerWorld world, String dimension,
                                  TerrainScanner.ChunkIndex chunks, int margin, boolean incremental) {
        if (active != null || encoding) {
            tell(source, Text.literal("A satellite scan started in the meantime — " + status()), true);
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            tell(source, Text.literal("No generated chunks on disk for " + dimension + " — nothing to scan."), true);
            return;
        }
        Satellite cached = index.get(directoryName(dimension));
        boolean reuse = cached != null && cached.scale() == SCALE && cached.tileSamples() == TILE_SAMPLES;
        if (cached != null && !reuse) {
            StationAnnouncer.LOGGER.warn("The satellite cache for {} was written at scale {} / {}-sample tiles"
                            + " (now {} / {}) — starting again from a fresh origin",
                    dimension, cached.scale(), cached.tileSamples(), SCALE, TILE_SAMPLES);
        }
        long lowX = chunks.minBlockX() - margin;
        long lowZ = chunks.minBlockZ() - margin;
        long highX = chunks.maxBlockX() + margin;
        long highZ = chunks.maxBlockZ() + margin;
        // THE ORIGIN IS ESTABLISHED ONCE AND KEPT FOREVER. Re-deriving it from a grown
        // world would shift every tile boundary and silently invalidate every PNG on
        // disk; keeping it means a world that grew toward -x/-z simply produces NEGATIVE
        // tile indices, which everything downstream accepts.
        long originX = reuse ? cached.originX() : snapDown(lowX);
        long originZ = reuse ? cached.originZ() : snapDown(lowZ);

        long tileX0 = Math.floorDiv(lowX - originX, TILE_BLOCKS);
        long tileZ0 = Math.floorDiv(lowZ - originZ, TILE_BLOCKS);
        long tileX1 = Math.floorDiv(highX - originX, TILE_BLOCKS);
        long tileZ1 = Math.floorDiv(highZ - originZ, TILE_BLOCKS);
        long neededLowX = originX + tileX0 * TILE_BLOCKS;
        long neededLowZ = originZ + tileZ0 * TILE_BLOCKS;
        long neededHighX = originX + (tileX1 + 1) * TILE_BLOCKS;
        long neededHighZ = originZ + (tileZ1 + 1) * TILE_BLOCKS;
        // Region file names are parsed off disk; a corrupt one at an absurd coordinate
        // would overflow the int grid arithmetic below, so refuse rather than wrap.
        if (Math.abs(neededLowX) > WORLD_LIMIT || Math.abs(neededLowZ) > WORLD_LIMIT
                || Math.abs(neededHighX) > WORLD_LIMIT || Math.abs(neededHighZ) > WORLD_LIMIT) {
            tell(source, Text.literal("The generated world of " + dimension + " reaches past the world limit ("
                    + neededLowX + "," + neededLowZ + " to " + neededHighX + "," + neededHighZ
                    + ") — check for a stray region file."), true);
            return;
        }
        long rectTiles = (tileX1 - tileX0 + 1) * (tileZ1 - tileZ0 + 1);
        if (rectTiles > MAX_TILE_RECT) {
            tell(source, Text.literal("That box spans " + rectTiles + " tiles of " + TILE_BLOCKS
                    + " blocks, past the " + MAX_TILE_RECT + " sanity guard. Check for a stray region file"
                    + " far from everything else."), true);
            return;
        }

        // The tile diff. An incremental pass keeps whatever the index already lists; a
        // full refresh wants every tile of the box regardless of what is on disk. Either
        // way, a tile with NO existing chunk under it is skipped entirely — never
        // sampled, never written, never indexed — which is what keeps a sparse world's
        // basemap sparse instead of thousands of transparent PNGs.
        List<int[]> wanted = new ArrayList<>();
        int skippedEmpty = 0;
        for (long tz = tileZ0; tz <= tileZ1; tz++) {
            for (long tx = tileX0; tx <= tileX1; tx++) {
                if (incremental && reuse && cached.has((int) tx, (int) tz)) {
                    continue;
                }
                long blockX = originX + tx * TILE_BLOCKS;
                long blockZ = originZ + tz * TILE_BLOCKS;
                int chunkX0 = (int) (blockX >> 4);
                int chunkZ0 = (int) (blockZ >> 4);
                if (!chunks.anyIn(chunkX0, chunkZ0, chunkX0 + TILE_CHUNKS - 1, chunkZ0 + TILE_CHUNKS - 1)) {
                    skippedEmpty++;
                    continue;
                }
                wanted.add(new int[]{(int) tx, (int) tz});
            }
        }
        if (wanted.isEmpty()) {
            // Reachable on an incremental pass over a cache that already has every tile
            // of the generated world — the whole point of the automatic scan — and on a
            // dimension whose region files hold nothing worth drawing.
            tell(source, Text.literal("The satellite basemap for " + dimension + " already covers the generated"
                    + " world (" + (cached == null ? 0 : cached.tiles().size()) + " tiles) — nothing to scan."),
                    false);
            return;
        }

        // Published bbox: the box the map now covers. An incremental pass unions it with
        // whatever the previous scans covered, since their tiles are still there.
        long bboxMinX = neededLowX;
        long bboxMinZ = neededLowZ;
        long bboxMaxX = neededHighX;
        long bboxMaxZ = neededHighZ;
        if (incremental && reuse && cached.bbox().length == 4) {
            bboxMinX = Math.min(bboxMinX, cached.bbox()[0]);
            bboxMinZ = Math.min(bboxMinZ, cached.bbox()[1]);
            bboxMaxX = Math.max(bboxMaxX, cached.bbox()[2]);
            bboxMaxZ = Math.max(bboxMaxZ, cached.bbox()[3]);
        }

        Scan scan = new Scan(world, dimension, source, incremental, chunks,
                (int) originX, (int) originZ, wanted, bboxMinX, bboxMinZ, bboxMaxX, bboxMaxZ);
        active = scan;
        int keptTiles = incremental && reuse ? cached.tiles().size() : 0;
        long samples = (long) wanted.size() * Scan.SAMPLES_PER_TILE;
        tell(source, Text.literal("Satellite scan started: " + wanted.size() + " tile(s) to draw"
                        + (keptTiles > 0 ? " (" + keptTiles + " kept)" : "")
                        + (skippedEmpty > 0 ? ", " + skippedEmpty + " empty tile(s) skipped" : "")
                        + ", " + samples + " samples over " + chunks.count() + " generated chunk(s) ("
                        + (incremental ? "incremental" : "full refresh") + ").")
                .formatted(Formatting.AQUA), false);
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
                StationAnnouncer.LOGGER.warn("Satellite auto-scan: {}", text.getString());
            } else {
                StationAnnouncer.LOGGER.info("Satellite auto-scan: {}", text.getString());
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

    /** Human-readable state for {@code /dispatch satellite status}. */
    public static String status() {
        Scan scan = active;
        if (scan != null) {
            int total = Math.max(1, scan.tiles.size());
            int percent = (int) (100L * scan.tileIndex / total);
            return "scanning " + scan.dimension + ": " + percent + "% (" + scan.tileIndex + "/" + total
                    + " tiles, " + scan.sampled + " samples, " + scan.water + " water so far)";
        }
        if (pending) {
            return "measuring the network…";
        }
        if (enumerating) {
            return "reading region files…";
        }
        if (encoding) {
            return "encoding tiles…";
        }
        if (autoActive) {
            return "automatic pass running (" + AUTO_QUEUE.size() + " dimension(s) left to check)";
        }
        Map<String, Satellite> snapshot = index;
        if (snapshot.isEmpty()) {
            return "idle, nothing scanned yet";
        }
        StringBuilder builder = new StringBuilder("idle;");
        snapshot.forEach((key, entry) -> builder.append(' ').append(entry.dimension()).append(" = ")
                .append(entry.tiles().size()).append(" tiles (")
                .append((System.currentTimeMillis() - entry.scannedAt()) / 60000L).append(" min ago)"));
        return builder.toString();
    }

    // ------------------------------------------------------- the automatic pass

    /**
     * Queues the once-per-launch automatic check: every dimension MTR is simulating gets
     * its generated world enumerated and any basemap tile that does not exist yet is
     * drawn. Called from {@link com.stationannouncer.mtraddon.AddonInit} once the terrain
     * scanner is idle; the work happens in {@link #pumpAuto}, one dimension at a time, so
     * the tick budget is never more than one scan's worth.
     */
    public static void autoScan() {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null || autoActive) {
            return;
        }
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            StationAnnouncer.LOGGER.info("Satellite auto-scan: MTR has no running simulations, nothing to check");
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
     * the next queued dimension, measures it and enumerates its region files;
     * {@link #beginScan} then decides whether anything is missing.
     */
    private static void pumpAuto() {
        MinecraftServer minecraftServer = server;
        String dimension = AUTO_QUEUE.poll();
        if (minecraftServer == null || dimension == null) {
            autoActive = false;
            AUTO_QUEUE.clear();
            if (minecraftServer != null) {
                StationAnnouncer.LOGGER.info("Satellite auto-scan pass finished");
            }
            return;
        }
        Simulator simulator = simulatorFor(dimension);
        ServerWorld world = worldFor(minecraftServer, dimension);
        if (simulator == null || world == null) {
            StationAnnouncer.LOGGER.info("Satellite auto-scan: no simulator/world pair for {}, skipping", dimension);
            return; // the next tick pumps the next dimension
        }
        measure(minecraftServer, simulator, (any, minX, minZ, maxX, maxZ) -> {
            if (!any) {
                // Unchanged rule: a dimension MTR simulates but nothing runs in is skipped
                // outright, however much of it happens to be generated.
                StationAnnouncer.LOGGER.info("Satellite auto-scan: no rails or stations in {} yet, skipping",
                        dimension);
                return;
            }
            enumerateThen(world, dimension, chunks ->
                    beginScan(null, world, dimension, chunks, AUTO_MARGIN, true));
        });
    }

    private static Simulator simulatorFor(String dimension) {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators != null) {
            for (Simulator simulator : simulators) {
                if (dimension.equals(simulator.dimension)) {
                    return simulator;
                }
            }
        }
        return null;
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

    // -------------------------------------------------------------- the getters

    /**
     * The basemap's metadata: what the frontend needs to place tiles on the map. Safe
     * from any thread — one volatile read of an immutable snapshot — and always answers,
     * {@code available:false} when that dimension was never scanned.
     *
     * <p>Tile indices are relative to the (permanent) origin and may be NEGATIVE. During
     * a long first scan this grows every {@value #FLUSH_TILES} tiles, so a client that
     * re-fetches it watches the map fill in.</p>
     */
    public static org.mtr.libraries.com.google.gson.JsonObject satelliteJson(String dimension) {
        org.mtr.libraries.com.google.gson.JsonObject json = new org.mtr.libraries.com.google.gson.JsonObject();
        Satellite entry = dimension == null ? null : index.get(directoryName(dimension));
        json.addProperty("available", entry != null);
        json.addProperty("dimension", dimension == null ? "" : dimension);
        json.addProperty("scannedAt", entry == null ? 0L : entry.scannedAt());
        json.addProperty("scale", entry == null ? SCALE : entry.scale());
        json.addProperty("tileSamples", entry == null ? TILE_SAMPLES : entry.tileSamples());
        json.addProperty("originX", entry == null ? 0 : entry.originX());
        json.addProperty("originZ", entry == null ? 0 : entry.originZ());
        org.mtr.libraries.com.google.gson.JsonArray tiles = new org.mtr.libraries.com.google.gson.JsonArray();
        org.mtr.libraries.com.google.gson.JsonArray bbox = new org.mtr.libraries.com.google.gson.JsonArray(4);
        if (entry == null) {
            for (int i = 0; i < 4; i++) {
                bbox.add(0);
            }
        } else {
            for (int[] tile : entry.tiles()) {
                org.mtr.libraries.com.google.gson.JsonArray pair =
                        new org.mtr.libraries.com.google.gson.JsonArray(2);
                pair.add(tile[0]);
                pair.add(tile[1]);
                tiles.add(pair);
            }
            for (long bound : entry.bbox()) {
                bbox.add(bound);
            }
        }
        json.add("tiles", tiles);
        json.add("bbox", bbox);
        return json;
    }

    /**
     * One tile's PNG bytes, or null when that dimension/tile was never written. Called
     * from Jetty workers: the tile files are immutable once moved into place, so a plain
     * blocking read needs no coordination with the scanner at all.
     *
     * <p>The tile is validated against the published index before the path is built, and
     * the built path is re-checked against the satellite directory — a request can never
     * reach a file the index does not list. Negative indices are ordinary (file name
     * {@code -1_2.png}); the minus sign is the only non-digit a name can contain.</p>
     */
    public static byte[] tile(String dimension, int tx, int tz) {
        if (dimension == null) {
            return null;
        }
        Path base = root;
        Satellite entry = index.get(directoryName(dimension));
        if (base == null || entry == null || !entry.has(tx, tz)) {
            return null;
        }
        Path file = base.resolve(directoryName(dimension)).resolve(tx + "_" + tz + ".png").normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not read satellite tile {}", file, e);
            return null;
        }
    }

    // ------------------------------------------------------------- tile encoding

    /**
     * Writer thread. Turns ONE BATCH of finished tiles into PNGs, merges them into the
     * dimension's tile set, writes the index and publishes the new snapshot. Only one of
     * these runs at a time ({@link #encoding}), so it is the single writer of
     * {@link #index} apart from the load at server start.
     *
     * <p>Order matters: a FULL REFRESH's FIRST batch unpublishes the dimension and
     * deletes the stale tiles (so a smaller re-scan cannot leave orphans behind); every
     * later batch — and every batch of an incremental pass — deletes nothing and merges.
     * Either way every tile is written to a temp file and moved into place, and the index
     * — the only thing {@link #tile} will serve from — is written last.</p>
     *
     * <p>Every wanted tile is written even when it is fully transparent: the index is the
     * record of what has been SCANNED, so a tile that turned out to be all void must
     * exist or every automatic pass would rescan it forever. (Tiles with no chunks under
     * them at all never get this far — {@link #beginScan} drops them.)</p>
     */
    private static void encode(String dimension, long scannedAt, int originX, int originZ,
                               List<Ready> batch, boolean wipe, long[] bbox,
                               int tilesDone, int tilesTotal, boolean last) {
        Path base = root;
        if (base == null) {
            encoding = false;
            return;
        }
        String directory = directoryName(dimension);
        Path dir = base.resolve(directory);
        int written = 0;
        try {
            Files.createDirectories(dir);
            if (wipe) {
                // Unpublish this dimension BEFORE the old tiles go: for the moment the
                // rewrite takes, the map is honestly "not scanned" rather than
                // advertising tiles that have just been deleted.
                Map<String, Satellite> without = new LinkedHashMap<>(index);
                if (without.remove(directory) != null) {
                    index = Map.copyOf(without);
                }
                clearDirectory(dir);
            }
            Satellite previous = index.get(directory);

            int[] palette = palette();
            List<int[]> tiles = new ArrayList<>(batch.size());
            int[] pixels = new int[TILE_SAMPLES * TILE_SAMPLES];
            BufferedImage image = new BufferedImage(TILE_SAMPLES, TILE_SAMPLES, BufferedImage.TYPE_INT_ARGB);
            ImageIO.setUseCache(false); // no temp-file spool for images this small

            for (Ready ready : batch) {
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = palette[((ready.colors()[i] & 0xFF) << 2) | ready.shade()[i]];
                }
                image.setRGB(0, 0, TILE_SAMPLES, TILE_SAMPLES, pixels, 0, TILE_SAMPLES);
                Path target = dir.resolve(ready.tx() + "_" + ready.tz() + ".png");
                Path temp = dir.resolve(ready.tx() + "_" + ready.tz() + ".png.tmp");
                if (!ImageIO.write(image, "png", temp.toFile())) {
                    throw new IOException("no PNG writer available");
                }
                move(temp, target);
                tiles.add(new int[]{ready.tx(), ready.tz()});
                written++;
            }

            // Merge: everything already published for this dimension plus what this batch
            // drew, deduplicated by tile key.
            Map<Long, int[]> merged = new LinkedHashMap<>();
            if (previous != null) {
                for (int[] tile : previous.tiles()) {
                    merged.put(key(tile[0], tile[1]), tile);
                }
            }
            for (int[] tile : tiles) {
                merged.put(key(tile[0], tile[1]), tile);
            }
            long[] unionBbox = bbox;
            if (previous != null && previous.bbox().length == 4) {
                unionBbox = new long[]{
                        Math.min(bbox[0], previous.bbox()[0]), Math.min(bbox[1], previous.bbox()[1]),
                        Math.max(bbox[2], previous.bbox()[2]), Math.max(bbox[3], previous.bbox()[3])};
            }
            Satellite result = make(dimension, scannedAt, SCALE, TILE_SAMPLES,
                    originX, originZ, List.copyOf(merged.values()), unionBbox);
            writeIndex(dir, result);

            Map<String, Satellite> published = new LinkedHashMap<>(index);
            published.put(directory, result);
            index = Map.copyOf(published);
            StationAnnouncer.LOGGER.info("Satellite basemap for {}: +{} tile(s), {} total ({}/{} tiles scanned{})",
                    dimension, written, result.tiles().size(), tilesDone, tilesTotal, last ? ", final batch" : "");
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not write the satellite basemap for {} ({} tiles written)",
                    dimension, written, t);
        } finally {
            encoding = false;
        }
    }

    /**
     * ARGB for every (map colour id, shade code) pair; slot 0 of each colour is
     * transparent. {@code MapColor.get} range-checks and throws past the last assigned
     * id (there are 62 in 1.20.4), which is the loop's terminator — ids are contiguous.
     */
    private static int[] palette() {
        int[] palette = new int[256 * 4];
        for (int id = 0; id < 256; id++) {
            MapColor color;
            try {
                color = MapColor.get(id);
            } catch (Throwable t) {
                break;
            }
            if (color == null || color == MapColor.CLEAR) {
                continue; // stays 0 = fully transparent
            }
            for (byte shade = SHADE_LOW; shade <= SHADE_HIGH; shade++) {
                // getRenderColor packs 0xFF | B<<16 | G<<8 | R (verified in 1.20.4
                // bytecode: it is ABGR, for the map texture's own byte order). Swap the
                // red and blue channels for BufferedImage's TYPE_INT_ARGB.
                int abgr = color.getRenderColor(BRIGHTNESSES[shade]);
                palette[(id << 2) | shade] = 0xFF000000 | ((abgr & 0xFF) << 16)
                        | (abgr & 0x0000FF00) | ((abgr >>> 16) & 0xFF);
            }
        }
        return palette;
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // Some filesystems refuse ATOMIC_MOVE with REPLACE_EXISTING; a plain replace
            // is still safe here because nothing reads a tile before the index lists it.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Drops the previous scan's tiles so a smaller re-scan cannot leave orphans behind.
     * FULL REFRESH ONLY, and only on its FIRST batch — any later call would delete
     * exactly the tiles the same scan had just published.
     */
    private static void clearDirectory(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    // ------------------------------------------------------------ persistence

    /**
     * Reads every {@code <dimension>/index.json} under the satellite directory. Plain
     * Gson (the file is ours, not MTR's wire format); a missing or damaged file just
     * means "nothing scanned yet" for that dimension.
     */
    private static Map<String, Satellite> read(Path base) {
        if (base == null || !Files.isDirectory(base)) {
            return Map.of();
        }
        Map<String, Satellite> loaded = new LinkedHashMap<>();
        try (Stream<Path> directories = Files.list(base)) {
            for (Path dir : directories.toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path file = dir.resolve("index.json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    com.google.gson.JsonObject value =
                            com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    String dimension = value.has("dimension") ? value.get("dimension").getAsString()
                            : dir.getFileName().toString();
                    long scannedAt = value.has("scannedAt") ? value.get("scannedAt").getAsLong() : 0;
                    int scale = value.has("scale") ? value.get("scale").getAsInt() : SCALE;
                    int tileSamples = value.has("tileSamples") ? value.get("tileSamples").getAsInt() : TILE_SAMPLES;
                    int originX = value.has("originX") ? value.get("originX").getAsInt() : 0;
                    int originZ = value.has("originZ") ? value.get("originZ").getAsInt() : 0;
                    long[] bbox = new long[4];
                    com.google.gson.JsonArray bboxJson = value.getAsJsonArray("bbox");
                    if (bboxJson != null && bboxJson.size() == 4) {
                        for (int i = 0; i < 4; i++) {
                            bbox[i] = bboxJson.get(i).getAsLong();
                        }
                    }
                    List<int[]> tiles = new ArrayList<>();
                    com.google.gson.JsonArray tilesJson = value.getAsJsonArray("tiles");
                    if (tilesJson != null) {
                        for (com.google.gson.JsonElement element : tilesJson) {
                            com.google.gson.JsonArray pair = element.getAsJsonArray();
                            if (pair.size() == 2) {
                                tiles.add(new int[]{pair.get(0).getAsInt(), pair.get(1).getAsInt()});
                            }
                        }
                    }
                    loaded.put(dir.getFileName().toString(), make(dimension, scannedAt, scale,
                            tileSamples, originX, originZ, List.copyOf(tiles), bbox));
                } catch (Exception e) {
                    StationAnnouncer.LOGGER.warn("Could not read {}, that dimension's basemap is ignored", file, e);
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not list {}, the satellite basemap starts empty", base, e);
            return Map.of();
        }
        return Map.copyOf(loaded);
    }

    /** Writer thread: {@code index.json} beside the tiles, written last and atomically. */
    private static void writeIndex(Path dir, Satellite entry) throws IOException {
        com.google.gson.JsonObject value = new com.google.gson.JsonObject();
        value.addProperty("dimension", entry.dimension());
        value.addProperty("scannedAt", entry.scannedAt());
        value.addProperty("scale", entry.scale());
        value.addProperty("tileSamples", entry.tileSamples());
        value.addProperty("originX", entry.originX());
        value.addProperty("originZ", entry.originZ());
        com.google.gson.JsonArray tiles = new com.google.gson.JsonArray(entry.tiles().size());
        for (int[] tile : entry.tiles()) {
            com.google.gson.JsonArray pair = new com.google.gson.JsonArray(2);
            pair.add(tile[0]);
            pair.add(tile[1]);
            tiles.add(pair);
        }
        value.add("tiles", tiles);
        com.google.gson.JsonArray bbox = new com.google.gson.JsonArray(4);
        for (long bound : entry.bbox()) {
            bbox.add(bound);
        }
        value.add("bbox", bbox);
        Path temp = dir.resolve("index.json.tmp");
        Files.writeString(temp, value.toString());
        move(temp, dir.resolve("index.json"));
    }

    /** MTR dimension ids are {@code namespace/path}; the slash cannot be a directory name. */
    private static String directoryName(String dimension) {
        StringBuilder builder = new StringBuilder(dimension.length());
        for (int i = 0; i < dimension.length(); i++) {
            char c = dimension.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' ? c : '_');
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static long snapDown(long value) {
        return Math.floorDiv(value, TILE_BLOCKS) * TILE_BLOCKS;
    }

    private static String worldId(ServerWorld world) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a world for the satellite scan", t);
            return null;
        }
    }

    /** One long per tile, so membership is a hash lookup rather than a list walk. */
    private static long key(int tx, int tz) {
        return (((long) tx) << 32) | (tz & 0xFFFFFFFFL);
    }

    private static Satellite make(String dimension, long scannedAt, int scale, int tileSamples,
                                  int originX, int originZ, List<int[]> tiles, long[] bbox) {
        Set<Long> keys = new HashSet<>(Math.max(16, tiles.size() * 2));
        for (int[] tile : tiles) {
            keys.add(key(tile[0], tile[1]));
        }
        return new Satellite(dimension, scannedAt, scale, tileSamples, originX, originZ,
                tiles, bbox, Set.copyOf(keys));
    }

    // ---------------------------------------------------------------- records

    /**
     * One dimension's tile index. Immutable once published; build it through
     * {@link #make} so {@code keys} always matches {@code tiles}.
     */
    private record Satellite(String dimension, long scannedAt, int scale, int tileSamples,
                             int originX, int originZ, List<int[]> tiles, long[] bbox, Set<Long> keys) {
        boolean has(int tx, int tz) {
            return keys.contains(key(tx, tz));
        }
    }

    /** One finished tile on its way to the encoder. The arrays belong to the writer thread. */
    private record Ready(int tx, int tz, byte[] colors, byte[] shade) {
    }

    /** Mutable scan state; only ever touched on the server thread. */
    private static final class Scan {
        /** One seed row (the strip north of the tile) plus the tile's own rows. */
        private static final int SAMPLES_PER_TILE = TILE_SAMPLES * (TILE_SAMPLES + 1);

        private final ServerWorld world;
        private final String dimension;
        /** Null for the automatic pass — see {@link #tell}. */
        private final ServerCommandSource source;
        /** Which chunks exist on disk; samples outside it never touch the world. */
        private final TerrainScanner.ChunkIndex chunks;
        /** The dimension's permanent tile origin, world coordinates. */
        private final int stableOriginX;
        private final int stableOriginZ;
        /** The tiles to draw, in order. Indices may be negative. */
        private final List<int[]> tiles;
        /** The box the published index will advertise (union with earlier scans). */
        private final long bboxMinX;
        private final long bboxMinZ;
        private final long bboxMaxX;
        private final long bboxMaxZ;
        private final int bottomY;
        /** Nether-style roof: the heightmap is useless, so probe down (see surfaceY). */
        private final boolean hasCeiling;
        private final int ceilingStartY;

        /** Map colour id per sample of the CURRENT tile; 0 (CLEAR) means transparent. */
        private byte[] colors = new byte[TILE_SAMPLES * TILE_SAMPLES];
        /** SHADE_* per sample. Decided during the scan so no height grid is ever kept. */
        private byte[] shade = new byte[TILE_SAMPLES * TILE_SAMPLES];
        private short[] prevRow = new short[TILE_SAMPLES];
        private short[] curRow = new short[TILE_SAMPLES];
        /** Finished tiles waiting for the encoder. Replaced wholesale at each flush. */
        private List<Ready> ready = new ArrayList<>();
        /** A full refresh wipes the tile directory once, at its first flush. */
        private boolean needsWipe;

        private final BlockPos.Mutable cursor = new BlockPos.Mutable();
        /** Sample index WITHIN the current tile, 0..SAMPLES_PER_TILE-1. */
        private int index;
        /** Which tile of {@link #tiles} is being sampled. */
        private int tileIndex;
        private long sampled;
        private int water;
        private WorldChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;

        private Scan(ServerWorld world, String dimension, ServerCommandSource source, boolean incremental,
                     TerrainScanner.ChunkIndex chunks, int stableOriginX, int stableOriginZ, List<int[]> tiles,
                     long bboxMinX, long bboxMinZ, long bboxMaxX, long bboxMaxZ) {
            this.world = world;
            this.dimension = dimension;
            this.source = source;
            this.chunks = chunks;
            this.stableOriginX = stableOriginX;
            this.stableOriginZ = stableOriginZ;
            this.tiles = tiles;
            this.bboxMinX = bboxMinX;
            this.bboxMinZ = bboxMinZ;
            this.bboxMaxX = bboxMaxX;
            this.bboxMaxZ = bboxMaxZ;
            this.needsWipe = !incremental;
            this.bottomY = world.getBottomY();
            DimensionType type = world.getDimension();
            this.hasCeiling = type.hasCeiling();
            this.ceilingStartY = this.bottomY + Math.max(1, type.logicalHeight() - CEILING_HEADROOM);
            java.util.Arrays.fill(this.prevRow, NO_HEIGHT);
            java.util.Arrays.fill(this.curRow, NO_HEIGHT);
        }
    }
}
