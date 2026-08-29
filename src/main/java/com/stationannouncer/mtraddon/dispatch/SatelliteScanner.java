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
import java.util.stream.Stream;

/**
 * The dispatch map's satellite basemap: a vanilla-held-map-style raster of the railway's
 * surroundings, scanned by {@code /dispatch satellite scan [marginBlocks]} or by the
 * automatic once-per-launch pass, and served as 256x256-sample PNG tiles.
 *
 * <p>This is {@link TerrainScanner}'s architecture a second time over, deliberately
 * duplicated rather than shared: a completely separate state machine (its own
 * {@link #active} scan, its own budget, its own save directory, its own auto queue) so
 * both can be running in the same session without either one's progress, cache or
 * command feedback touching the other. Only the bounding-box derivation is identical —
 * the union of every valid rail's {@link RailMath} extents and every station's
 * {@code AreaBase} corners plus a margin, asked of MTR on the owning simulator's thread
 * and carried back as plain longs.</p>
 *
 * <p><b>Sampling.</b> One sample per {@value #SCALE} blocks (16x denser than the terrain
 * scan's 8-block grid, hence its own, much larger, sample cap). Per sample: the surface
 * from the chunk's {@code MOTION_BLOCKING} heightmap, that block's
 * {@link BlockState#getMapColor} and its height. Shading is vanilla's: compare the
 * height with the sample one step NORTH — higher is {@link MapColor.Brightness#HIGH},
 * equal {@code NORMAL}, lower {@code LOW} — except on water, which is banded by depth
 * like a real map (shallow bright, deep dark). Missing/void samples and
 * {@link MapColor#CLEAR} are transparent.</p>
 *
 * <p><b>A stable tile grid.</b> The origin is established by the FIRST scan of a
 * dimension and then kept forever: every later scan measures its tiles from that same
 * corner, so tile (0,0) always names the same 512 blocks and existing tiles stay valid.
 * A network that grows toward -x/-z therefore produces NEGATIVE tile indices — legal
 * everywhere: in the file names ({@code -1_2.png}), the index, {@code satmeta} and the
 * {@code sattile} endpoint.</p>
 *
 * <p><b>Full refresh vs incremental.</b> The manual command always does a FULL refresh:
 * every tile of the needed box is re-sampled and the dimension's directory is wiped
 * first, so stale tiles from a bigger previous scan cannot survive. The automatic pass
 * is INCREMENTAL: it samples only tiles that do not exist yet, merges them into the
 * index and never deletes anything.</p>
 *
 * <p><b>Threading.</b> Everything except {@link #satelliteJson(String)} and
 * {@link #tile(String, int, int)} runs on the SERVER thread, in
 * {@value #TICK_BUDGET_MILLIS} ms slices from
 * {@link com.stationannouncer.mtraddon.AddonInit}'s tick handler (the sampler reads
 * chunks, so it cannot live on a simulator thread). PNG encoding happens once, at the
 * end of a scan, on a daemon thread over arrays nothing will touch again. The two
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

    /** Per-tick sampling budget. Two milliseconds of a fifty-millisecond tick. */
    private static final long TICK_BUDGET_MILLIS = 2;
    private static final long TICK_BUDGET_NANOS = TICK_BUDGET_MILLIS * 1_000_000L;
    /** Check the clock this often (in samples) while the chunk cache is hitting. */
    private static final int BUDGET_CHECK_MASK = 15;
    /**
     * Refuse anything bigger. 9 M samples is 6000x6000 blocks at {@value #SCALE}-block
     * spacing, ~18 MB of scan arrays and ~140 tiles — the point past which this stops
     * being a basemap and starts being a mapping project.
     */
    private static final int MAX_SAMPLES = 9_000_000;
    /** Bound on the tile rectangle the diff walks, so a stray rail cannot spin the loop. */
    private static final int MAX_TILE_RECT = 65_536;
    /** How far below the surface the water-depth probe looks before calling it "deep". */
    private static final int MAX_WATER_PROBE = 8;
    /** A CLEAR surface (glass, a torch on a roof) looks this far down for real colour. */
    private static final int MAX_CLEAR_DESCENT = 4;
    /** Vanilla's world border cap; beyond it the int grid arithmetic would overflow. */
    private static final long WORLD_LIMIT = 30_000_000L;
    /** The margin the automatic pass uses — the manual command's own default. */
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
    /** True while the writer thread is turning a finished scan into PNGs. */
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
                // Encoding a full basemap can take a few seconds; a half-written tile set
                // is still consistent (tiles are moved into place atomically, and the
                // index is written last), so this join is a courtesy, not a requirement.
                pendingWrite.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writer = null;
        active = null;
        pending = false;
        encoding = false;
        autoActive = false;
        AUTO_QUEUE.clear();
        index = Map.of();
        root = null;
        server = null;
    }

    /**
     * Nothing running: no scan, no simulator round-trip, no tile encode and no auto pass
     * left to walk. {@link com.stationannouncer.mtraddon.AddonInit} waits on the TERRAIN
     * scanner's version of this before kicking our automatic pass, so the two never
     * sample in the same tick.
     */
    public static boolean isIdle() {
        return active == null && !pending && !encoding && !autoActive;
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
            if (autoActive && !pending && !encoding) {
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
                StationAnnouncer.LOGGER.warn("Satellite scan of {} failed while sampling", scan.dimension, t);
                report(scan, Text.literal("Satellite scan failed while reading the world — see the server log.")
                        .formatted(Formatting.RED), true);
                return;
            }
            scan.index++;
            // A cache miss just paid for a (possibly generating) chunk load, so re-check
            // the clock immediately rather than after another fifteen cheap samples.
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
        if (gx == 0 && scan.index > 0) {
            // New row: the row just finished becomes the north neighbours. Only two rows
            // are ever kept, which is what lets the shading be decided during the scan
            // and the (9 M-entry) height grid never exist at all.
            short[] finished = scan.curRow;
            scan.curRow = scan.prevRow;
            scan.prevRow = finished;
        }
        if (!scan.wanted[(gz / TILE_SAMPLES) * scan.tilesX + gx / TILE_SAMPLES]) {
            // A tile this scan is not producing (it already exists on disk). Bail BEFORE
            // touching a chunk: chunk load/generation is the entire cost of a scan, and
            // an incremental pass over a grown network is mostly this branch.
            scan.colors[scan.index] = 0;
            scan.shade[scan.index] = SHADE_NONE;
            scan.curRow[gx] = NO_HEIGHT;
            return false;
        }
        int x = scan.originX + gx * SCALE;
        int z = scan.originZ + gz * SCALE;
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
        int bottomY = scan.world.getBottomY();
        // sampleHeightmap returns Heightmap.get() - 1, i.e. the y of the topmost block
        // that MOTION_BLOCKING accepts — and that predicate accepts any non-empty fluid
        // state, so for open water the answer IS the surface water block.
        int surfaceY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (surfaceY < bottomY) {
            scan.colors[scan.index] = 0;
            scan.shade[scan.index] = SHADE_NONE;
            scan.curRow[gx] = NO_HEIGHT;
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
            scan.colors[scan.index] = 0;
            scan.shade[scan.index] = SHADE_NONE;
            scan.curRow[gx] = NO_HEIGHT;
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
            scan.water++;
        } else {
            short north = scan.prevRow[gx];
            shade = north == NO_HEIGHT || y == north ? SHADE_NORMAL : y > north ? SHADE_HIGH : SHADE_LOW;
        }
        scan.colors[scan.index] = (byte) color.id;
        scan.shade[scan.index] = shade;
        scan.curRow[gx] = (short) y;
        return loaded;
    }

    // ------------------------------------------------------------ start a scan

    /**
     * {@code /dispatch satellite scan}. Asks the caller's dimension's simulator for the
     * network extents on its own thread, then hops back to begin sampling.
     *
     * <p>Always a FULL refresh: every tile of the needed box is re-sampled and the
     * dimension's tile directory is wiped first. That is the point of the command —
     * "the world changed, redraw it". The automatic pass is the incremental one.</p>
     */
    public static int startScan(ServerCommandSource source, int marginBlocks) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            source.sendError(Text.literal("The satellite scanner is not ready yet."));
            return 0;
        }
        if (active != null || pending || encoding) {
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
        measure(minecraftServer, target, (any, minX, minZ, maxX, maxZ) ->
                beginScan(source, world, dimension, any, minX, minZ, maxX, maxZ, margin, false));
        return 1;
    }

    /** What {@link #measure} hands back, on the server thread, as plain longs. */
    @FunctionalInterface
    private interface BoundsHandler {
        void handle(boolean any, long minX, long minZ, long maxX, long maxZ);
    }

    /**
     * The network's extents, asked of MTR on the owning simulator's thread and handed
     * back on the server thread. Shared by the command and the automatic pass — the
     * {@link #pending} flag is held across the hop, so neither can start while the other
     * is still measuring.
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
     * Server thread: turn the network extents into a tile set and arm the ticker.
     *
     * <p>{@code source} is null for the automatic pass — every message then goes to the
     * log instead of a chat window (see {@link #tell}). {@code incremental} picks the
     * two behaviours that differ: which tiles are sampled (missing ones only, versus all
     * of them) and what happens to the tiles already on disk (kept and merged, versus
     * deleted for a clean full refresh).</p>
     */
    private static void beginScan(ServerCommandSource source, ServerWorld world, String dimension,
                                  boolean any, long minX, long minZ, long maxX, long maxZ, int margin,
                                  boolean incremental) {
        if (active != null || encoding) {
            tell(source, Text.literal("A satellite scan started in the meantime — " + status()), true);
            return;
        }
        if (!any) {
            tell(source, Text.literal("No rails or stations in " + dimension + " yet — nothing to scan."), true);
            return;
        }
        Satellite cached = index.get(directoryName(dimension));
        boolean reuse = cached != null && cached.scale() == SCALE && cached.tileSamples() == TILE_SAMPLES;
        if (cached != null && !reuse) {
            StationAnnouncer.LOGGER.warn("The satellite cache for {} was written at scale {} / {}-sample tiles"
                            + " (now {} / {}) — starting again from a fresh origin",
                    dimension, cached.scale(), cached.tileSamples(), SCALE, TILE_SAMPLES);
        }
        // THE ORIGIN IS ESTABLISHED ONCE AND KEPT FOREVER. Re-deriving it from a grown
        // network would shift every tile boundary and silently invalidate every PNG on
        // disk; keeping it means a network that grew toward -x/-z simply produces
        // NEGATIVE tile indices, which everything downstream accepts.
        long originX = reuse ? cached.originX() : snapDown(minX - margin);
        long originZ = reuse ? cached.originZ() : snapDown(minZ - margin);

        long tileX0 = Math.floorDiv(minX - margin - originX, TILE_BLOCKS);
        long tileZ0 = Math.floorDiv(minZ - margin - originZ, TILE_BLOCKS);
        long tileX1 = Math.floorDiv(maxX + margin - originX, TILE_BLOCKS);
        long tileZ1 = Math.floorDiv(maxZ + margin - originZ, TILE_BLOCKS);
        long neededLowX = originX + tileX0 * TILE_BLOCKS;
        long neededLowZ = originZ + tileZ0 * TILE_BLOCKS;
        long neededHighX = originX + (tileX1 + 1) * TILE_BLOCKS;
        long neededHighZ = originZ + (tileZ1 + 1) * TILE_BLOCKS;
        // MTR positions are longs; block coordinates outside the vanilla world border
        // would overflow the int grid arithmetic below, so refuse rather than wrap.
        if (Math.abs(neededLowX) > WORLD_LIMIT || Math.abs(neededLowZ) > WORLD_LIMIT
                || Math.abs(neededHighX) > WORLD_LIMIT || Math.abs(neededHighZ) > WORLD_LIMIT) {
            tell(source, Text.literal("The network extends past the world limit (" + neededLowX + ","
                    + neededLowZ + " to " + neededHighX + "," + neededHighZ + ") — check for a stray rail."), true);
            return;
        }
        long rectTiles = (tileX1 - tileX0 + 1) * (tileZ1 - tileZ0 + 1);
        if (rectTiles > MAX_TILE_RECT) {
            tell(source, Text.literal("That box spans " + rectTiles + " tiles of " + TILE_BLOCKS
                    + " blocks — far past anything worth rastering. Check for a stray rail far from"
                    + " the network."), true);
            return;
        }

        // The tile diff. An incremental pass keeps whatever the index already lists; a
        // full refresh wants every tile of the box regardless of what is on disk.
        List<int[]> missing = new ArrayList<>();
        int keepMinX = Integer.MAX_VALUE;
        int keepMinZ = Integer.MAX_VALUE;
        int keepMaxX = Integer.MIN_VALUE;
        int keepMaxZ = Integer.MIN_VALUE;
        for (long tz = tileZ0; tz <= tileZ1; tz++) {
            for (long tx = tileX0; tx <= tileX1; tx++) {
                if (incremental && reuse && cached.has((int) tx, (int) tz)) {
                    continue;
                }
                missing.add(new int[]{(int) tx, (int) tz});
                keepMinX = Math.min(keepMinX, (int) tx);
                keepMaxX = Math.max(keepMaxX, (int) tx);
                keepMinZ = Math.min(keepMinZ, (int) tz);
                keepMaxZ = Math.max(keepMaxZ, (int) tz);
            }
        }
        if (missing.isEmpty()) {
            // Only reachable on an incremental pass over a cache that already has every
            // tile of the box — the whole point of the automatic scan.
            tell(source, Text.literal("The satellite basemap for " + dimension + " already covers the network ("
                    + (cached == null ? 0 : cached.tiles().size()) + " tiles) — nothing to scan."), false);
            return;
        }

        // Sample only the bounding rectangle of the MISSING tiles, not the whole box.
        int tilesX = keepMaxX - keepMinX + 1;
        int tilesZ = keepMaxZ - keepMinZ + 1;
        long width = (long) tilesX * TILE_SAMPLES;
        long height = (long) tilesZ * TILE_SAMPLES;
        long total = width * height;
        if (total > MAX_SAMPLES) {
            tell(source, Text.literal("That would be " + total + " samples (" + width + "×" + height
                    + " at one sample per " + SCALE + " blocks), over the " + MAX_SAMPLES + " cap."
                    + " Reduce the margin — or check for a stray rail far from the network."), true);
            return;
        }
        boolean[] wanted = new boolean[tilesX * tilesZ];
        for (int[] tile : missing) {
            wanted[(tile[1] - keepMinZ) * tilesX + (tile[0] - keepMinX)] = true;
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

        Scan scan = new Scan(world, dimension, source, incremental,
                (int) originX, (int) originZ, keepMinX, keepMinZ, tilesX, tilesZ, wanted,
                bboxMinX, bboxMinZ, bboxMaxX, bboxMaxZ);
        active = scan;
        int newTiles = missing.size();
        int keptTiles = incremental && reuse ? cached.tiles().size() : 0;
        tell(source, Text.literal("Satellite scan started: " + newTiles + " tile(s) to draw"
                        + (keptTiles > 0 ? " (" + keptTiles + " kept)" : "") + ", " + total + " samples over "
                        + (tilesX * TILE_BLOCKS) + "×" + (tilesZ * TILE_BLOCKS) + " blocks (margin " + margin
                        + (incremental ? ", incremental)." : ", full refresh)."))
                .formatted(Formatting.AQUA), false);
    }

    /** Everything sampled: hand the arrays to the encoder thread. */
    private static void finish(Scan scan) {
        int samples = scan.width * scan.height;
        long scannedAt = System.currentTimeMillis();
        encoding = true;
        report(scan, Text.literal("Satellite scan complete: " + samples + " samples ("
                + scan.water + " water). Encoding tiles…").formatted(Formatting.AQUA), false);
        // From here the arrays are read-only and belong to the writer thread.
        Thread thread = new Thread(() -> encode(scan, scannedAt), "station-announcer-satellite-encode");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
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
            int total = Math.max(1, scan.width * scan.height);
            int percent = (int) (100L * scan.index / total);
            return "scanning " + scan.dimension + ": " + percent + "% (" + scan.index + "/" + total
                    + " samples, " + scan.water + " water so far)";
        }
        if (pending) {
            return "measuring the network…";
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
     * its network measured and any basemap tile that does not exist yet is drawn. Called
     * from {@link com.stationannouncer.mtraddon.AddonInit} once the terrain scanner is
     * idle; the work happens in {@link #pumpAuto}, one dimension at a time, so the tick
     * budget is never more than one scan's worth.
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
     * the next queued dimension and measures it; {@link #beginScan} then decides whether
     * anything is missing.
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
                StationAnnouncer.LOGGER.info("Satellite auto-scan: no rails or stations in {} yet, skipping",
                        dimension);
                return;
            }
            beginScan(null, world, dimension, true, minX, minZ, maxX, maxZ, AUTO_MARGIN, true);
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
     * <p>Tile indices are relative to the (permanent) origin and may be NEGATIVE.</p>
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
     * Writer thread. Turns the finished scan into PNG tiles, updates the dimension's tile
     * set, writes the index and publishes the new snapshot.
     *
     * <p>Order matters: for a FULL REFRESH the stale tiles are deleted first (so a
     * smaller re-scan cannot leave orphans behind); an INCREMENTAL pass deletes nothing
     * and merges its new tiles into the existing list. Either way every tile is written
     * to a temp file and moved into place, and the index — the only thing {@link #tile}
     * will serve from — is written last.</p>
     *
     * <p>Every wanted tile is written even when it is fully transparent: the index is the
     * record of what has been SCANNED, so an empty tile must exist or every automatic
     * pass would rescan it forever. A transparent tile is a few hundred bytes.</p>
     */
    private static void encode(Scan scan, long scannedAt) {
        Path base = root;
        if (base == null) {
            encoding = false;
            return;
        }
        String directory = directoryName(scan.dimension);
        Path dir = base.resolve(directory);
        Satellite previous = index.get(directory);
        int written = 0;
        try {
            Files.createDirectories(dir);
            if (!scan.incremental) {
                // Unpublish this dimension BEFORE the old tiles go: for the few seconds
                // the rewrite takes, the map is honestly "not scanned" rather than
                // advertising tiles that have just been deleted.
                Map<String, Satellite> without = new LinkedHashMap<>(index);
                if (without.remove(directory) != null) {
                    index = Map.copyOf(without);
                }
                clearDirectory(dir);
                previous = null;
            }

            int[] palette = palette();
            List<int[]> tiles = new ArrayList<>();
            int[] pixels = new int[TILE_SAMPLES * TILE_SAMPLES];
            BufferedImage image = new BufferedImage(TILE_SAMPLES, TILE_SAMPLES, BufferedImage.TYPE_INT_ARGB);
            ImageIO.setUseCache(false); // no temp-file spool for images this small

            for (int localZ = 0; localZ < scan.tilesZ; localZ++) {
                for (int localX = 0; localX < scan.tilesX; localX++) {
                    if (!scan.wanted[localZ * scan.tilesX + localX]) {
                        continue; // an existing tile this scan deliberately did not sample
                    }
                    for (int py = 0; py < TILE_SAMPLES; py++) {
                        int rowStart = (localZ * TILE_SAMPLES + py) * scan.width + localX * TILE_SAMPLES;
                        int out = py * TILE_SAMPLES;
                        for (int px = 0; px < TILE_SAMPLES; px++) {
                            int sample = rowStart + px;
                            pixels[out + px] = palette[((scan.colors[sample] & 0xFF) << 2) | scan.shade[sample]];
                        }
                    }
                    image.setRGB(0, 0, TILE_SAMPLES, TILE_SAMPLES, pixels, 0, TILE_SAMPLES);
                    int tx = scan.tileX0 + localX;
                    int tz = scan.tileZ0 + localZ;
                    Path target = dir.resolve(tx + "_" + tz + ".png");
                    Path temp = dir.resolve(tx + "_" + tz + ".png.tmp");
                    if (!ImageIO.write(image, "png", temp.toFile())) {
                        throw new IOException("no PNG writer available");
                    }
                    move(temp, target);
                    tiles.add(new int[]{tx, tz});
                    written++;
                }
            }

            // Merge: everything that was already on disk (incremental only) plus what
            // this scan drew, deduplicated by tile key.
            Map<Long, int[]> merged = new LinkedHashMap<>();
            if (previous != null) {
                for (int[] tile : previous.tiles()) {
                    merged.put(key(tile[0], tile[1]), tile);
                }
            }
            for (int[] tile : tiles) {
                merged.put(key(tile[0], tile[1]), tile);
            }
            long[] bbox = {scan.bboxMinX, scan.bboxMinZ, scan.bboxMaxX, scan.bboxMaxZ};
            Satellite result = make(scan.dimension, scannedAt, SCALE, TILE_SAMPLES,
                    scan.stableOriginX, scan.stableOriginZ, List.copyOf(merged.values()), bbox);
            writeIndex(dir, result);

            Map<String, Satellite> published = new LinkedHashMap<>(index);
            published.put(directory, result);
            index = Map.copyOf(published);
            StationAnnouncer.LOGGER.info("Satellite basemap for {} updated: {} new tile(s), {} total ({})",
                    scan.dimension, written, result.tiles().size(),
                    scan.incremental ? "incremental" : "full refresh");
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not write the satellite basemap for {} ({} tiles written)",
                    scan.dimension, written, t);
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
     * FULL REFRESH ONLY — an incremental pass must never call this, or it would delete
     * exactly the tiles it decided it did not need to sample again.
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

    /** Mutable scan state; only ever touched on the server thread (then, once, by the encoder). */
    private static final class Scan {
        private final ServerWorld world;
        private final String dimension;
        /** Null for the automatic pass — see {@link #tell}. */
        private final ServerCommandSource source;
        /** True = merge into the existing tiles; false = full refresh (wipe first). */
        private final boolean incremental;
        /** The dimension's permanent tile origin, world coordinates. */
        private final int stableOriginX;
        private final int stableOriginZ;
        /** Tile index of this scan's own corner; may be negative. */
        private final int tileX0;
        private final int tileZ0;
        private final int tilesX;
        private final int tilesZ;
        /** Which tiles of this rectangle to actually sample, row-major over the rectangle. */
        private final boolean[] wanted;
        /** World coordinates of sample (0,0). */
        private final int originX;
        private final int originZ;
        private final int width;
        private final int height;
        /** The box the published index will advertise (union with earlier scans). */
        private final long bboxMinX;
        private final long bboxMinZ;
        private final long bboxMaxX;
        private final long bboxMaxZ;
        /** Map colour id per sample; 0 (CLEAR) means transparent. */
        private final byte[] colors;
        /** SHADE_* per sample. Decided during the scan so no height grid is ever kept. */
        private final byte[] shade;
        private short[] prevRow;
        private short[] curRow;
        private final BlockPos.Mutable cursor = new BlockPos.Mutable();
        private int index;
        private int water;
        private WorldChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;

        private Scan(ServerWorld world, String dimension, ServerCommandSource source, boolean incremental,
                     int stableOriginX, int stableOriginZ, int tileX0, int tileZ0, int tilesX, int tilesZ,
                     boolean[] wanted, long bboxMinX, long bboxMinZ, long bboxMaxX, long bboxMaxZ) {
            this.world = world;
            this.dimension = dimension;
            this.source = source;
            this.incremental = incremental;
            this.stableOriginX = stableOriginX;
            this.stableOriginZ = stableOriginZ;
            this.tileX0 = tileX0;
            this.tileZ0 = tileZ0;
            this.tilesX = tilesX;
            this.tilesZ = tilesZ;
            this.wanted = wanted;
            this.originX = stableOriginX + tileX0 * TILE_BLOCKS;
            this.originZ = stableOriginZ + tileZ0 * TILE_BLOCKS;
            this.width = tilesX * TILE_SAMPLES;
            this.height = tilesZ * TILE_SAMPLES;
            this.bboxMinX = bboxMinX;
            this.bboxMinZ = bboxMinZ;
            this.bboxMaxX = bboxMaxX;
            this.bboxMaxZ = bboxMaxZ;
            this.colors = new byte[width * height];
            this.shade = new byte[width * height];
            this.prevRow = new short[width];
            this.curRow = new short[width];
            java.util.Arrays.fill(this.prevRow, NO_HEIGHT);
            java.util.Arrays.fill(this.curRow, NO_HEIGHT);
        }
    }
}
