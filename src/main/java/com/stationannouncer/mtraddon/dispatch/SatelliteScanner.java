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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The dispatch map's satellite basemap: a vanilla-held-map-style raster of the railway's
 * surroundings, scanned on demand by {@code /dispatch satellite scan [marginBlocks]} and
 * served as 256x256-sample PNG tiles.
 *
 * <p>This is {@link TerrainScanner}'s architecture a second time over, deliberately
 * duplicated rather than shared: a completely separate state machine (its own
 * {@link #active} scan, its own budget, its own save directory) so both can be running
 * in the same session without either one's progress, cache or command feedback touching
 * the other. Only the bounding-box derivation is identical — the union of every valid
 * rail's {@link RailMath} extents and every station's {@code AreaBase} corners plus a
 * margin, asked of MTR on the owning simulator's thread and carried back as plain longs.</p>
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
    /** Tile edge in blocks. The scan origin is snapped to this, so tiles are always full. */
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
    /** How far below the surface the water-depth probe looks before calling it "deep". */
    private static final int MAX_WATER_PROBE = 8;
    /** A CLEAR surface (glass, a torch on a roof) looks this far down for real colour. */
    private static final int MAX_CLEAR_DESCENT = 4;
    /** Vanilla's world border cap; beyond it the int grid arithmetic would overflow. */
    private static final long WORLD_LIMIT = 30_000_000L;

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

    private SatelliteScanner() {
    }

    // ------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: remember the server and read whatever tiles were written before. */
    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        active = null;
        pending = false;
        encoding = false;
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
        index = Map.of();
        root = null;
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
            minecraftServer.execute(() -> beginScan(source, world, dimension, fAny, fMinX, fMinZ, fMaxX, fMaxZ, margin));
        });
        return 1;
    }

    /** Server thread: turn the network extents into a tile-aligned grid and arm the ticker. */
    private static void beginScan(ServerCommandSource source, ServerWorld world, String dimension,
                                  boolean any, long minX, long minZ, long maxX, long maxZ, int margin) {
        pending = false;
        if (active != null) {
            source.sendError(Text.literal("A satellite scan started in the meantime — " + status()));
            return;
        }
        if (!any) {
            source.sendError(Text.literal("No rails or stations in " + dimension + " yet — nothing to scan."));
            return;
        }
        // Snap to whole TILE_BLOCKS so every tile is complete and tile (0,0)'s corner is a
        // round world coordinate — the frontend's world↔tile arithmetic has no remainder.
        long lowX = snapDown(minX - margin);
        long lowZ = snapDown(minZ - margin);
        long highX = snapUp(maxX + margin + 1);
        long highZ = snapUp(maxZ + margin + 1);
        // MTR positions are longs; block coordinates outside the vanilla world border
        // would overflow the int grid arithmetic below, so refuse rather than wrap.
        if (Math.abs(lowX) > WORLD_LIMIT || Math.abs(lowZ) > WORLD_LIMIT
                || Math.abs(highX) > WORLD_LIMIT || Math.abs(highZ) > WORLD_LIMIT) {
            source.sendError(Text.literal("The network extends past the world limit ("
                    + lowX + "," + lowZ + " to " + highX + "," + highZ + ") — check for a stray rail."));
            return;
        }
        long width = (highX - lowX) / SCALE;
        long height = (highZ - lowZ) / SCALE;
        long total = width * height;
        if (total > MAX_SAMPLES) {
            source.sendError(Text.literal("That would be " + total + " samples (" + width + "×" + height
                    + " at one sample per " + SCALE + " blocks), over the " + MAX_SAMPLES + " cap."
                    + " Reduce the margin — or check for a stray rail far from the network."));
            return;
        }

        Scan scan = new Scan(world, dimension, source, (int) lowX, (int) lowZ, (int) width, (int) height,
                lowX, lowZ, highX, highZ);
        active = scan;
        long tiles = (width / TILE_SAMPLES) * (height / TILE_SAMPLES);
        source.sendFeedback(() -> Text.literal("Satellite scan started: " + total + " samples over "
                        + (highX - lowX) + "×" + (highZ - lowZ) + " blocks (margin " + margin + ", up to "
                        + tiles + " tiles).")
                .formatted(Formatting.AQUA), true);
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

    // -------------------------------------------------------------- the getters

    /**
     * The basemap's metadata: what the frontend needs to place tiles on the map. Safe
     * from any thread — one volatile read of an immutable snapshot — and always answers,
     * {@code available:false} when that dimension was never scanned.
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
     * reach a file the index does not list.</p>
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
     * Writer thread. Turns the finished scan into PNG tiles, replaces the dimension's
     * previous tile set, writes the index and publishes the new snapshot.
     *
     * <p>Order matters: stale tiles are deleted first (so a smaller re-scan cannot leave
     * orphans behind), every tile is written to a temp file and moved into place, and the
     * index — the only thing {@link #tile} will serve from — is written last.</p>
     */
    private static void encode(Scan scan, long scannedAt) {
        Path base = root;
        if (base == null) {
            encoding = false;
            return;
        }
        String directory = directoryName(scan.dimension);
        Path dir = base.resolve(directory);
        int written = 0;
        try {
            Files.createDirectories(dir);
            // Unpublish this dimension BEFORE the old tiles go: for the few seconds the
            // rewrite takes, the map is honestly "not scanned" rather than advertising
            // tiles that have just been deleted.
            Map<String, Satellite> without = new LinkedHashMap<>(index);
            if (without.remove(directory) != null) {
                index = Map.copyOf(without);
            }
            clearDirectory(dir);

            int[] palette = palette();
            int tilesX = scan.width / TILE_SAMPLES;
            int tilesZ = scan.height / TILE_SAMPLES;
            List<int[]> tiles = new ArrayList<>();
            int[] pixels = new int[TILE_SAMPLES * TILE_SAMPLES];
            BufferedImage image = new BufferedImage(TILE_SAMPLES, TILE_SAMPLES, BufferedImage.TYPE_INT_ARGB);
            ImageIO.setUseCache(false); // no temp-file spool for images this small

            for (int tz = 0; tz < tilesZ; tz++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    boolean anything = false;
                    for (int py = 0; py < TILE_SAMPLES; py++) {
                        int rowStart = (tz * TILE_SAMPLES + py) * scan.width + tx * TILE_SAMPLES;
                        int out = py * TILE_SAMPLES;
                        for (int px = 0; px < TILE_SAMPLES; px++) {
                            int sample = rowStart + px;
                            int argb = palette[((scan.colors[sample] & 0xFF) << 2) | scan.shade[sample]];
                            pixels[out + px] = argb;
                            anything |= argb != 0;
                        }
                    }
                    if (!anything) {
                        continue; // fully void tile: not written, not indexed, not requested
                    }
                    image.setRGB(0, 0, TILE_SAMPLES, TILE_SAMPLES, pixels, 0, TILE_SAMPLES);
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

            long[] bbox = {scan.minX, scan.minZ, scan.maxX, scan.maxZ};
            Satellite result = new Satellite(scan.dimension, scannedAt, SCALE, TILE_SAMPLES,
                    scan.originX, scan.originZ, List.copyOf(tiles), bbox);
            writeIndex(dir, result);

            Map<String, Satellite> merged = new LinkedHashMap<>(index);
            merged.put(directory, result);
            index = Map.copyOf(merged);
            StationAnnouncer.LOGGER.info("Satellite basemap for {} written: {} tiles from {} samples",
                    scan.dimension, written, scan.width * scan.height);
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

    /** Drops the previous scan's tiles so a smaller re-scan cannot leave orphans behind. */
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
                    loaded.put(dir.getFileName().toString(), new Satellite(dimension, scannedAt, scale,
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

    private static long snapUp(long value) {
        return Math.floorDiv(value + TILE_BLOCKS - 1, TILE_BLOCKS) * TILE_BLOCKS;
    }

    private static String worldId(ServerWorld world) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a world for the satellite scan", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- records

    /** One dimension's tile index. Immutable once published. */
    private record Satellite(String dimension, long scannedAt, int scale, int tileSamples,
                             int originX, int originZ, List<int[]> tiles, long[] bbox) {
        /** Membership test over the tile list; small enough (≤ a few hundred) to scan. */
        boolean has(int tx, int tz) {
            for (int[] tile : tiles) {
                if (tile[0] == tx && tile[1] == tz) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Mutable scan state; only ever touched on the server thread (then, once, by the encoder). */
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
            this.colors = new byte[width * height];
            this.shade = new byte[width * height];
            this.prevRow = new short[width];
            this.curRow = new short[width];
            java.util.Arrays.fill(this.prevRow, NO_HEIGHT);
            java.util.Arrays.fill(this.curRow, NO_HEIGHT);
        }
    }
}
