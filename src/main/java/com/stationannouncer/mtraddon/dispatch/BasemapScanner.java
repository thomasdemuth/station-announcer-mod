package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.block.Block;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.dimension.DimensionType;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.data.RailMath;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The dispatch map's basemap: the world rastered into 512-block PNG tiles, read straight
 * out of the region files on a background thread.
 *
 * <h2>Why it reads NBT instead of chunks</h2>
 * <p>The previous two scanners (terrain polygons + satellite) walked the world through
 * {@code world.getChunk} under a 2 ms per tick budget. On a real server world that is
 * 2 million terrain samples at ~200 a second — hours, with no partial result, and the
 * satellite pass only started after it. Worse, {@code getChunk} on the proto-chunk ring
 * vanilla keeps around explored land FINISHES generating those chunks and writes a fresh
 * ring around each: one four-minute scan grew a 270,827-chunk world by 4,000 chunks, so
 * the next launch found the world "grown" and rescanned it all. Rivers never appeared.</p>
 *
 * <p>This scanner asks the chunk storage for each chunk's saved NBT
 * ({@link ThreadedAnvilChunkStorage#getNbt}, the IO worker is thread-safe and coherent
 * with pending writes), decodes the {@code MOTION_BLOCKING} heightmap and the surface
 * sections itself, and skips any chunk whose saved {@code Status} is not {@code full}.
 * Nothing is loaded, nothing is generated, no tick is spent: the whole of that world
 * takes minutes on one daemon thread, and it publishes every {@value #FLUSH_TILES}
 * tiles so the map fills in while it runs and resumes across restarts.</p>
 *
 * <h2>Products</h2>
 * <p>Per tile: {@code <tx>_<tz>.png}, the vanilla-map colouring (map colour of the
 * surface block, shaded by the slope to the north, water banded by depth), and
 * {@code <tx>_<tz>.m.png}, a CLASS MASK whose red channel is a {@code CLASS_*} code
 * (water, forest, snow, sand, grass, other land; alpha 0 where nothing exists). The
 * frontend styles the mask into the schematic map's water and woodland and draws the
 * colour tiles as the "satellite" option — one scan, both basemaps.</p>
 *
 * <p>Tiles are {@value #TILE_SAMPLES} samples square at one sample per {@value #SCALE}
 * blocks, on a per-dimension grid whose origin is fixed forever by the first scan
 * (growth toward -x/-z gives negative tile indices). The automatic pass draws only the
 * tiles the index does not have; {@code /dispatch basemap scan} is a full refresh.</p>
 */
public final class BasemapScanner {
    /** Blocks per sample. The frontend reads this from satmeta; never assume it there. */
    public static final int SCALE = 2;
    /** Samples per tile edge; one tile is TILE_SAMPLES × SCALE = 512 blocks square. */
    public static final int TILE_SAMPLES = 256;
    public static final int TILE_BLOCKS = TILE_SAMPLES * SCALE;
    private static final int TILE_CHUNKS = TILE_BLOCKS / 16;
    /** Samples per chunk edge. */
    private static final int CHUNK_SAMPLES = 16 / SCALE;
    /** Tiles published per index write during a scan (progress the map can see). */
    private static final int FLUSH_TILES = 32;
    /** Sanity guard on the tile rectangle a stray region file could ask for. */
    private static final int MAX_TILE_RECT = 1_048_576;
    private static final long WORLD_LIMIT = 30_000_000L;
    /** How far below the surface the water-depth probe looks before calling it "deep". */
    private static final int MAX_WATER_PROBE = 8;
    /** A CLEAR surface (glass, a torch on a roof) looks this far down for real colour. */
    private static final int MAX_CLEAR_DESCENT = 4;
    private static final int MAX_CEILING_DESCENT = 96;
    private static final int CEILING_HEADROOM = 8;
    private static final int AUTO_MARGIN = 128;
    /** 1.18 pre-release: the flat chunk layout ({@code sections[].block_states}) this decoder reads. */
    private static final int MIN_DATA_VERSION = 2860;
    /** How long one chunk read may take before the scan gives up on it. */
    private static final long READ_TIMEOUT_SECONDS = 60;

    private static final byte SHADE_NONE = 0;
    private static final byte SHADE_LOW = 1;
    private static final byte SHADE_NORMAL = 2;
    private static final byte SHADE_HIGH = 3;
    private static final MapColor.Brightness[] BRIGHTNESSES = {
            MapColor.Brightness.NORMAL, MapColor.Brightness.LOW,
            MapColor.Brightness.NORMAL, MapColor.Brightness.HIGH
    };

    /** Mask classes — the red channel of the {@code .m.png} tiles. */
    public static final byte CLASS_NONE = 0;
    public static final byte CLASS_WATER = 1;
    public static final byte CLASS_FOREST = 2;
    public static final byte CLASS_SNOW = 3;
    public static final byte CLASS_SAND = 4;
    public static final byte CLASS_GRASS = 5;
    public static final byte CLASS_LAND = 6;

    private static final short NO_HEIGHT = Short.MIN_VALUE;

    private static volatile MinecraftServer server;
    /** {@code <save>/station-announcer-addon/satellite}, or null before the server starts. */
    private static volatile Path root;
    /** Immutable published index, directory name → result. Read cross-thread. */
    private static volatile Map<String, Satellite> index = Map.of();
    /** The scan in progress (its worker thread owns it). */
    private static volatile Scan active;
    /** Set between a command and the simulator round-trip so two clicks cannot both start. */
    private static volatile boolean pending;
    /** Set while a background thread is reading the dimension's region headers. */
    private static volatile boolean enumerating;
    /** Dimensions the once-per-launch auto pass still has to check. Server thread only. */
    private static final ArrayDeque<String> AUTO_QUEUE = new ArrayDeque<>();
    private static volatile boolean autoActive;

    private BasemapScanner() {
    }

    // ------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: remember the server and read whatever tile indexes exist. */
    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        active = null;
        pending = false;
        enumerating = false;
        autoActive = false;
        AUTO_QUEUE.clear();
        Path path = minecraftServer.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("satellite").normalize();
        root = path;
        // Only the indexes are read here — never the pixels; the servlet streams tiles.
        index = read(path);
        if (!index.isEmpty()) {
            int tiles = 0;
            for (Satellite entry : index.values()) {
                tiles += entry.tiles().size();
            }
            StationAnnouncer.LOGGER.info("Basemap loaded ({} dimension(s), {} tiles)", index.size(), tiles);
        }
    }

    /** SERVER_STOPPING: stop the worker (it publishes only whole batches) and forget everything. */
    public static void onServerStopping() {
        Scan scan = active;
        if (scan != null) {
            scan.cancelled = true;
            Thread thread = scan.thread;
            if (thread != null) {
                try {
                    thread.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        active = null;
        pending = false;
        enumerating = false;
        autoActive = false;
        AUTO_QUEUE.clear();
        index = Map.of();
        root = null;
        server = null;
    }

    /** Nothing running: no scan, no simulator round-trip, no enumeration, no auto pass left. */
    public static boolean isIdle() {
        return active == null && !pending && !enumerating && !autoActive;
    }

    /** END_SERVER_TICK: pumps the automatic pass; a field read when nothing is queued. */
    public static void tick() {
        if (autoActive && active == null && !pending && !enumerating) {
            pumpAuto();
        }
    }

    // ------------------------------------------------------------ start a scan

    /**
     * {@code /dispatch basemap scan [margin]}: a FULL refresh of the caller's dimension —
     * every tile with chunks under it is redrawn and the stale tiles are wiped first.
     */
    public static int startScan(ServerCommandSource source, int marginBlocks) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            source.sendError(Text.literal("The basemap scanner is not ready yet."));
            return 0;
        }
        if (active != null || pending || enumerating) {
            source.sendError(Text.literal("A basemap scan is already running — " + status()));
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
        measure(minecraftServer, target, any -> {
            if (!any) {
                tell(source, Text.literal("No rails or stations in " + dimension + " yet — nothing to scan."), true);
                return;
            }
            tell(source, Text.literal("Reading the region files of " + dimension + "…")
                    .formatted(Formatting.GRAY), false);
            enumerateThen(world, dimension, chunks -> beginScan(source, world, dimension, chunks, margin, false));
        });
        return 1;
    }

    /**
     * Once per launch, a few seconds after start: every simulated dimension with rails is
     * checked and the tiles its index lacks are drawn. Cached tiles cost nothing.
     */
    public static void autoScan() {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null || autoActive) {
            return;
        }
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            StationAnnouncer.LOGGER.info("Basemap auto-scan: MTR has no running simulations, nothing to check");
            return;
        }
        AUTO_QUEUE.clear();
        for (Simulator simulator : simulators) {
            AUTO_QUEUE.add(simulator.dimension);
        }
        autoActive = true;
    }

    /** Server thread, one dimension per call, only while nothing else is running. */
    private static void pumpAuto() {
        MinecraftServer minecraftServer = server;
        String dimension = AUTO_QUEUE.poll();
        if (minecraftServer == null || dimension == null) {
            autoActive = false;
            AUTO_QUEUE.clear();
            if (minecraftServer != null) {
                StationAnnouncer.LOGGER.info("Basemap auto-scan pass finished");
            }
            return;
        }
        Simulator simulator = simulatorFor(dimension);
        ServerWorld world = worldFor(minecraftServer, dimension);
        if (simulator == null || world == null) {
            StationAnnouncer.LOGGER.info("Basemap auto-scan: no simulator/world pair for {}, skipping", dimension);
            return;
        }
        measure(minecraftServer, simulator, any -> {
            if (!any) {
                StationAnnouncer.LOGGER.info("Basemap auto-scan: no rails or stations in {} yet, skipping", dimension);
                return;
            }
            enumerateThen(world, dimension, chunks -> beginScan(null, world, dimension, chunks, AUTO_MARGIN, true));
        });
    }

    /**
     * "Is anything railway-shaped here at all?", asked of MTR on the owning simulator's
     * thread and answered on the server thread. The round-trip also proves the simulator
     * is alive.
     */
    private static void measure(MinecraftServer minecraftServer, Simulator simulator, Consumer<Boolean> handler) {
        pending = true;
        simulator.run(() -> {
            boolean any = false;
            try {
                for (Rail rail : simulator.railIdMap.values()) {
                    if (rail.isValid()) {
                        RailMath math = rail.railMath; // touched so an invalid rail cannot slip through
                        any = math != null;
                        if (any) {
                            break;
                        }
                    }
                }
                if (!any) {
                    for (Station station : simulator.stations) {
                        any = station != null;
                        if (any) {
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Basemap network check failed for dimension {}", simulator.dimension, t);
                pending = false;
                return;
            }
            final boolean fAny = any;
            minecraftServer.execute(() -> {
                pending = false;
                handler.accept(fAny);
            });
        });
    }

    /** Region headers on a daemon thread, result handed back on the server thread. */
    private static void enumerateThen(ServerWorld world, String dimension, Consumer<ChunkIndex> handler) {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            return;
        }
        enumerating = true;
        Thread thread = new Thread(() -> {
            ChunkIndex chunks;
            try {
                chunks = ChunkIndex.enumerate(minecraftServer, world);
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
                enumerating = false;
            }
        }, "station-announcer-basemap-regions");
        thread.setDaemon(true);
        thread.start();
    }

    /** Server thread: the tile diff, then the worker thread. */
    private static void beginScan(ServerCommandSource source, ServerWorld world, String dimension,
                                  ChunkIndex chunks, int margin, boolean incremental) {
        if (active != null) {
            tell(source, Text.literal("A basemap scan started in the meantime — " + status()), true);
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            tell(source, Text.literal("No generated chunks on disk for " + dimension + " — nothing to scan."), true);
            return;
        }
        Satellite cached = index.get(directoryName(dimension));
        boolean reuse = cached != null && cached.scale() == SCALE && cached.tileSamples() == TILE_SAMPLES;
        if (cached != null && !reuse) {
            StationAnnouncer.LOGGER.warn("The basemap cache for {} was written at scale {} / {}-sample tiles"
                    + " (now {} / {}) — starting again from a fresh origin",
                    dimension, cached.scale(), cached.tileSamples(), SCALE, TILE_SAMPLES);
            incremental = false;
        }
        long lowX = chunks.minBlockX() - margin;
        long lowZ = chunks.minBlockZ() - margin;
        long highX = chunks.maxBlockX() + margin;
        long highZ = chunks.maxBlockZ() + margin;
        // THE ORIGIN IS ESTABLISHED ONCE AND KEPT FOREVER: re-deriving it from a grown
        // world would shift every tile boundary and invalidate every PNG on disk.
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
        // The tile diff: an incremental pass keeps what the index lists; a full refresh
        // wants every tile of the box. Either way a tile with NO chunk under it is never
        // sampled, written or indexed.
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
            tell(source, Text.literal("The basemap for " + dimension + " already covers the generated world ("
                    + (cached == null ? 0 : cached.tiles().size()) + " tiles) — nothing to scan."), false);
            return;
        }
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
        Scan scan = new Scan(world, dimension, source, incremental, chunks, (int) originX, (int) originZ,
                wanted, new long[]{bboxMinX, bboxMinZ, bboxMaxX, bboxMaxZ});
        active = scan;
        int keptTiles = incremental && reuse ? cached.tiles().size() : 0;
        tell(source, Text.literal("Basemap scan started: " + wanted.size() + " tile(s) to draw"
                + (keptTiles > 0 ? " (" + keptTiles + " kept)" : "")
                + (skippedEmpty > 0 ? ", " + skippedEmpty + " empty tile(s) skipped" : "")
                + " over " + chunks.count() + " chunk(s) on disk ("
                + (incremental ? "incremental" : "full refresh") + ").").formatted(Formatting.AQUA), false);
        Thread thread = new Thread(() -> runScan(scan), "station-announcer-basemap");
        thread.setDaemon(true);
        scan.thread = thread;
        thread.start();
    }

    // ---------------------------------------------------------------- worker

    /** The worker thread: every wanted tile, decoded, rastered, written and published in batches. */
    private static void runScan(Scan scan) {
        long startedAt = System.nanoTime();
        List<int[]> batch = new ArrayList<>();
        try {
            if (!scan.incremental) {
                wipe(scan.dimension);
            }
            for (int i = 0; i < scan.tiles.size(); i++) {
                if (scan.cancelled) {
                    return;
                }
                int[] tile = scan.tiles.get(i);
                renderTile(scan, tile[0], tile[1]);
                if (scan.cancelled) {
                    return;
                }
                writeTile(scan, tile[0], tile[1]);
                batch.add(tile);
                scan.tilesDone = i + 1;
                if (batch.size() >= FLUSH_TILES) {
                    publish(scan, batch, false);
                    batch = new ArrayList<>();
                }
            }
            publish(scan, batch, true);
            long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
            String summary = "Basemap scan complete: " + scan.tiles.size() + " tile(s), " + scan.samples
                    + " samples (" + scan.water + " water) in " + seconds + " s"
                    + (scan.skippedProto > 0 ? ", " + scan.skippedProto + " unfinished chunk(s) skipped" : "")
                    + (scan.skippedOld > 0 ? ", " + scan.skippedOld + " pre-1.18 chunk(s) skipped" : "")
                    + ".";
            report(scan, Text.literal(summary).formatted(Formatting.GREEN), false);
            StationAnnouncer.LOGGER.info("Basemap scan of {}: {}", scan.dimension, summary);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Basemap scan of {} failed", scan.dimension, t);
            report(scan, Text.literal("Basemap scan failed — see the server log.").formatted(Formatting.RED), true);
        } finally {
            if (active == scan) {
                active = null;
            }
        }
    }

    /**
     * Rasters one tile into the scan's buffers. Chunk rows are read one ahead of the row
     * being decoded, so the IO worker's disk reads overlap our decoding.
     */
    private static void renderTile(Scan scan, int tx, int tz) throws Exception {
        int blockX0 = scan.originX + tx * TILE_BLOCKS;
        int blockZ0 = scan.originZ + tz * TILE_BLOCKS;
        int cx0 = blockX0 >> 4;
        int cz0 = blockZ0 >> 4;
        short[] north = new short[TILE_SAMPLES];
        java.util.Arrays.fill(north, NO_HEIGHT);
        java.util.Arrays.fill(scan.colors, (byte) 0);
        java.util.Arrays.fill(scan.shade, SHADE_NONE);
        java.util.Arrays.fill(scan.mask, CLASS_NONE);

        // The seed row: the last sample row of the chunk row NORTH of the tile, so the
        // tile's top edge shades against its neighbour instead of against void.
        Columns[] seed = joinRow(scan, submitRow(scan, cx0, cz0 - 1));
        for (int cx = 0; cx < TILE_CHUNKS; cx++) {
            Columns c = seed[cx];
            for (int i = 0; i < CHUNK_SAMPLES; i++) {
                north[cx * CHUNK_SAMPLES + i] = c == null ? NO_HEIGHT
                        : c.height[(CHUNK_SAMPLES - 1) * CHUNK_SAMPLES + i];
            }
        }
        CompletableFuture<Optional<NbtCompound>>[] next = submitRow(scan, cx0, cz0);
        for (int cr = 0; cr < TILE_CHUNKS; cr++) {
            if (scan.cancelled) {
                return;
            }
            Columns[] row = joinRow(scan, next);
            if (cr + 1 < TILE_CHUNKS) {
                next = submitRow(scan, cx0, cz0 + cr + 1);
            }
            for (int j = 0; j < CHUNK_SAMPLES; j++) {
                int pz = cr * CHUNK_SAMPLES + j;
                for (int cx = 0; cx < TILE_CHUNKS; cx++) {
                    Columns c = row[cx];
                    for (int i = 0; i < CHUNK_SAMPLES; i++) {
                        int px = cx * CHUNK_SAMPLES + i;
                        int out = pz * TILE_SAMPLES + px;
                        int local = j * CHUNK_SAMPLES + i;
                        if (c == null || c.height[local] == NO_HEIGHT) {
                            north[px] = NO_HEIGHT;
                            continue; // buffers were cleared: transparent, CLASS_NONE
                        }
                        short h = c.height[local];
                        byte depth = c.waterDepth[local];
                        byte shade;
                        if (depth > 0) {
                            shade = depth <= 2 ? SHADE_HIGH : depth <= 5 ? SHADE_NORMAL : SHADE_LOW;
                            scan.water++;
                        } else {
                            short n = north[px];
                            shade = n == NO_HEIGHT || h == n ? SHADE_NORMAL : h > n ? SHADE_HIGH : SHADE_LOW;
                        }
                        scan.colors[out] = c.color[local];
                        scan.shade[out] = shade;
                        scan.mask[out] = c.clazz[local];
                        north[px] = h;
                    }
                }
            }
            scan.samples += (long) CHUNK_SAMPLES * TILE_SAMPLES;
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Optional<NbtCompound>>[] submitRow(Scan scan, int cx0, int cz) {
        CompletableFuture<Optional<NbtCompound>>[] futures = new CompletableFuture[TILE_CHUNKS];
        for (int cx = 0; cx < TILE_CHUNKS; cx++) {
            if (scan.chunks.has(cx0 + cx, cz)) {
                futures[cx] = scan.storage.getNbt(new ChunkPos(cx0 + cx, cz));
            }
        }
        return futures;
    }

    private static Columns[] joinRow(Scan scan, CompletableFuture<Optional<NbtCompound>>[] futures) throws Exception {
        Columns[] row = new Columns[TILE_CHUNKS];
        for (int cx = 0; cx < TILE_CHUNKS; cx++) {
            CompletableFuture<Optional<NbtCompound>> future = futures[cx];
            if (future == null) {
                continue;
            }
            Optional<NbtCompound> nbt = future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (nbt.isEmpty()) {
                continue;
            }
            try {
                row[cx] = decode(scan, nbt.get());
            } catch (Throwable t) {
                // One damaged chunk stays transparent; the scan goes on.
                if (scan.decodeFailures++ < 5) {
                    StationAnnouncer.LOGGER.warn("Basemap: could not decode a chunk of {}", scan.dimension, t);
                }
            }
        }
        return row;
    }

    // ---------------------------------------------------------------- decoding

    /**
     * One chunk's saved NBT → its {@value #CHUNK_SAMPLES}² samples. Null when the chunk
     * is not worth drawing: unfinished (proto) status, a pre-1.18 layout, no heightmap.
     */
    private static Columns decode(Scan scan, NbtCompound nbt) {
        if (nbt.getInt("DataVersion") < MIN_DATA_VERSION) {
            scan.skippedOld++;
            return null;
        }
        String status = nbt.getString("Status");
        if (!"minecraft:full".equals(status) && !"full".equals(status)) {
            scan.skippedProto++;
            return null;
        }
        long[] heightmap = nbt.getCompound("Heightmaps").getLongArray("MOTION_BLOCKING");
        if (heightmap.length == 0) {
            return null;
        }
        PackedIntegerArray heights = new PackedIntegerArray(scan.heightBits, 256, heightmap);
        Sections sections = new Sections(scan, nbt.getList("sections", NbtElement.COMPOUND_TYPE));
        Columns columns = new Columns();
        int bottomY = scan.bottomY;
        for (int j = 0; j < CHUNK_SAMPLES; j++) {
            int lz = j * SCALE;
            for (int i = 0; i < CHUNK_SAMPLES; i++) {
                int lx = i * SCALE;
                int local = j * CHUNK_SAMPLES + i;
                columns.height[local] = NO_HEIGHT;
                // MOTION_BLOCKING stores (y of the block ABOVE the top motion-blocking or
                // fluid block) - bottomY; 0 means the column is empty.
                int stored = heights.get(lx + lz * 16);
                int topY = stored + bottomY - 1;
                if (stored <= 0) {
                    continue;
                }
                if (scan.hasCeiling) {
                    topY = ceilingSurface(scan, sections, lx, Math.min(topY, scan.ceilingStartY), lz);
                    if (topY == Integer.MIN_VALUE) {
                        continue;
                    }
                }
                BlockState state = sections.state(lx, topY, lz);
                int y = topY;
                MapColor color = mapColor(state);
                for (int descent = 0; color == MapColor.CLEAR && descent < MAX_CLEAR_DESCENT && y - 1 >= bottomY; descent++) {
                    y--;
                    state = sections.state(lx, y, lz);
                    color = mapColor(state);
                }
                if (color == null || color == MapColor.CLEAR) {
                    continue;
                }
                byte depth = 0;
                if (sections.state(lx, topY, lz).getFluidState().isIn(FluidTags.WATER)) {
                    int probeY = topY - 1;
                    int probes = 0;
                    while (probes < MAX_WATER_PROBE && probeY >= bottomY
                            && sections.state(lx, probeY, lz).getFluidState().isIn(FluidTags.WATER)) {
                        probeY--;
                        probes++;
                    }
                    depth = (byte) (topY - probeY); // blocks of water, surface block included
                }
                columns.height[local] = (short) y;
                columns.color[local] = (byte) color.id;
                columns.waterDepth[local] = depth;
                columns.clazz[local] = classOf(color, depth > 0);
            }
        }
        return columns;
    }

    /**
     * Ceiling dimensions (the nether): the heightmap answers the bedrock roof, so start
     * just under the logical roof, descend to the first air, then to the first floor.
     */
    private static int ceilingSurface(Scan scan, Sections sections, int lx, int startY, int lz) {
        int y = startY;
        int steps = 0;
        while (steps < MAX_CEILING_DESCENT && y > scan.bottomY && !sections.state(lx, y, lz).isAir()) {
            y--;
            steps++;
        }
        if (steps >= MAX_CEILING_DESCENT || y <= scan.bottomY) {
            return Integer.MIN_VALUE;
        }
        while (steps < MAX_CEILING_DESCENT && y > scan.bottomY && sections.state(lx, y, lz).isAir()) {
            y--;
            steps++;
        }
        return steps >= MAX_CEILING_DESCENT || y <= scan.bottomY ? Integer.MIN_VALUE : y;
    }

    /**
     * The map colour of a state, asked with an EMPTY view: vanilla's colours are per
     * state and never look at neighbours, and a modded block that does gets air, which
     * is safe off the server thread. Anything that throws is treated as transparent.
     */
    private static MapColor mapColor(BlockState state) {
        try {
            return state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        } catch (Throwable t) {
            return MapColor.CLEAR;
        }
    }

    /** The mask class of a sample: what the schematic basemap will style it as. */
    private static byte classOf(MapColor color, boolean water) {
        if (water) {
            return CLASS_WATER;
        }
        if (color == MapColor.DARK_GREEN) {
            return CLASS_FOREST;      // leaves, most plants: woodland reads as parkland
        }
        if (color == MapColor.WHITE) {
            return CLASS_SNOW;
        }
        if (color == MapColor.PALE_YELLOW) {
            return CLASS_SAND;
        }
        if (color == MapColor.PALE_GREEN) {
            return CLASS_GRASS;
        }
        return CLASS_LAND;
    }

    /** One chunk's decoded sample grid. */
    private static final class Columns {
        final short[] height = new short[CHUNK_SAMPLES * CHUNK_SAMPLES];
        final byte[] color = new byte[CHUNK_SAMPLES * CHUNK_SAMPLES];
        final byte[] waterDepth = new byte[CHUNK_SAMPLES * CHUNK_SAMPLES];
        final byte[] clazz = new byte[CHUNK_SAMPLES * CHUNK_SAMPLES];
    }

    /**
     * A chunk's {@code sections} list, decoded lazily: only the sections the surface
     * probes touch are ever unpacked. Palette entries go through a per-scan cache, so a
     * world of a few hundred distinct block states parses each of them once.
     */
    private static final class Sections {
        private final Scan scan;
        private final NbtCompound[] raw;
        private final Section[] decoded;
        private final int minSectionY;

        Sections(Scan scan, NbtList list) {
            this.scan = scan;
            this.minSectionY = scan.bottomY >> 4;
            int count = (scan.height >> 4) + 2;
            this.raw = new NbtCompound[count];
            this.decoded = new Section[count];
            for (int i = 0; i < list.size(); i++) {
                NbtCompound section = list.getCompound(i);
                int slot = section.getByte("Y") - minSectionY;
                if (slot >= 0 && slot < count) {
                    raw[slot] = section;
                }
            }
        }

        BlockState state(int lx, int y, int lz) {
            int slot = (y >> 4) - minSectionY;
            if (slot < 0 || slot >= raw.length || raw[slot] == null) {
                return Blocks.AIR.getDefaultState();
            }
            Section section = decoded[slot];
            if (section == null) {
                section = Section.decode(scan, raw[slot]);
                decoded[slot] = section;
            }
            return section.get(lx, y & 15, lz);
        }
    }

    private static final class Section {
        private static final Section AIR = new Section(new BlockState[]{Blocks.AIR.getDefaultState()}, null);
        private final BlockState[] palette;
        private final PackedIntegerArray data;

        private Section(BlockState[] palette, PackedIntegerArray data) {
            this.palette = palette;
            this.data = data;
        }

        static Section decode(Scan scan, NbtCompound section) {
            NbtCompound states = section.getCompound("block_states");
            NbtList paletteList = states.getList("palette", NbtElement.COMPOUND_TYPE);
            if (paletteList.isEmpty()) {
                return AIR;
            }
            BlockState[] palette = new BlockState[paletteList.size()];
            for (int i = 0; i < palette.length; i++) {
                NbtCompound entry = paletteList.getCompound(i);
                BlockState state = scan.stateCache.get(entry);
                if (state == null) {
                    try {
                        state = NbtHelper.toBlockState(scan.blocks, entry);
                    } catch (Throwable t) {
                        state = Blocks.AIR.getDefaultState();
                    }
                    scan.stateCache.put(entry.copy(), state);
                }
                palette[i] = state;
            }
            if (palette.length == 1 || !states.contains("data", NbtElement.LONG_ARRAY_TYPE)) {
                return new Section(palette, null);
            }
            int bits = Math.max(4, MathHelper.ceilLog2(palette.length));
            long[] data = states.getLongArray("data");
            try {
                return new Section(palette, new PackedIntegerArray(bits, 4096, data));
            } catch (Throwable t) {
                return new Section(palette, null); // malformed data: the palette's first entry
            }
        }

        BlockState get(int lx, int ly, int lz) {
            if (data == null) {
                return palette[0];
            }
            int i = data.get((ly * 16 + lz) * 16 + lx);
            return i >= 0 && i < palette.length ? palette[i] : palette[0];
        }
    }

    // ------------------------------------------------------------- tile output

    /** Worker thread: the two PNGs of the tile just rastered, written temp-then-move. */
    private static void writeTile(Scan scan, int tx, int tz) throws IOException {
        Path base = root;
        if (base == null) {
            throw new IOException("the basemap directory is gone (server stopping)");
        }
        Path dir = base.resolve(directoryName(scan.dimension));
        Files.createDirectories(dir);
        int n = TILE_SAMPLES * TILE_SAMPLES;
        int[] pixels = scan.pixels;
        for (int i = 0; i < n; i++) {
            pixels[i] = scan.palette[((scan.colors[i] & 0xFF) << 2) | scan.shade[i]];
        }
        scan.image.setRGB(0, 0, TILE_SAMPLES, TILE_SAMPLES, pixels, 0, TILE_SAMPLES);
        writePng(scan.image, dir, tx + "_" + tz + ".png");
        for (int i = 0; i < n; i++) {
            byte c = scan.mask[i];
            pixels[i] = c == CLASS_NONE ? 0 : 0xFF000000 | ((c & 0xFF) << 16);
        }
        scan.image.setRGB(0, 0, TILE_SAMPLES, TILE_SAMPLES, pixels, 0, TILE_SAMPLES);
        writePng(scan.image, dir, tx + "_" + tz + ".m.png");
    }

    private static void writePng(BufferedImage image, Path dir, String name) throws IOException {
        Path target = dir.resolve(name);
        Path temp = dir.resolve(name + ".tmp");
        ImageIO.setUseCache(false);
        if (!ImageIO.write(image, "png", temp.toFile())) {
            throw new IOException("no PNG writer available");
        }
        move(temp, target);
    }

    /** Full refresh: unpublish the dimension, then delete its tiles, before anything new lands. */
    private static void wipe(String dimension) throws IOException {
        String directory = directoryName(dimension);
        Map<String, Satellite> without = new LinkedHashMap<>(index);
        if (without.remove(directory) != null) {
            index = Map.copyOf(without);
        }
        Path base = root;
        if (base == null) {
            return;
        }
        Path dir = base.resolve(directory);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /**
     * Worker thread: merge a batch of written tiles into the dimension's index, write
     * {@code index.json} and publish the new snapshot. The worker is the single writer of
     * {@link #index} once the server is up, so a plain volatile swap is enough.
     */
    private static void publish(Scan scan, List<int[]> batch, boolean last) throws IOException {
        if (batch.isEmpty() && !last) {
            return;
        }
        Path base = root;
        if (base == null) {
            throw new IOException("the basemap directory is gone (server stopping)");
        }
        String directory = directoryName(scan.dimension);
        Path dir = base.resolve(directory);
        Files.createDirectories(dir);
        Satellite previous = index.get(directory);
        Map<Long, int[]> merged = new LinkedHashMap<>();
        if (previous != null) {
            for (int[] tile : previous.tiles()) {
                merged.put(key(tile[0], tile[1]), tile);
            }
        }
        for (int[] tile : batch) {
            merged.put(key(tile[0], tile[1]), tile);
        }
        long[] bbox = scan.bbox;
        if (previous != null && previous.bbox().length == 4) {
            bbox = new long[]{
                    Math.min(bbox[0], previous.bbox()[0]), Math.min(bbox[1], previous.bbox()[1]),
                    Math.max(bbox[2], previous.bbox()[2]), Math.max(bbox[3], previous.bbox()[3])};
        }
        Satellite result = make(scan.dimension, System.currentTimeMillis(), SCALE, TILE_SAMPLES,
                scan.originX, scan.originZ, List.copyOf(merged.values()), bbox);
        writeIndex(dir, result);
        Map<String, Satellite> published = new LinkedHashMap<>(index);
        published.put(directory, result);
        index = Map.copyOf(published);
        StationAnnouncer.LOGGER.info("Basemap for {}: +{} tile(s), {} total ({}/{} scanned{})",
                scan.dimension, batch.size(), result.tiles().size(), scan.tilesDone, scan.tiles.size(),
                last ? ", final batch" : "");
    }

    /**
     * ARGB for every (map colour id, shade code) pair; slot 0 of each colour is
     * transparent. {@code MapColor.get} throws past the last assigned id, which is the
     * loop's terminator.
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
                continue;
            }
            for (byte shade = SHADE_LOW; shade <= SHADE_HIGH; shade++) {
                // getRenderColor packs ABGR (verified in 1.20.4 bytecode); swap to ARGB.
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
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ----------------------------------------------------------------- reporting

    /** The command's status line. Any thread. */
    public static String status() {
        Scan scan = active;
        if (scan != null) {
            int total = Math.max(1, scan.tiles.size());
            return "scanning " + scan.dimension + ": " + (100 * scan.tilesDone / total) + "% ("
                    + scan.tilesDone + "/" + total + " tiles, " + scan.samples + " samples, "
                    + scan.water + " water so far)";
        }
        if (pending || enumerating) {
            return "starting";
        }
        int tiles = 0;
        for (Satellite entry : index.values()) {
            tiles += entry.tiles().size();
        }
        return tiles == 0 ? "idle, nothing scanned yet" : "idle, " + tiles + " tile(s) cached";
    }

    private static void report(Scan scan, Text text, boolean error) {
        tell(scan.source, text, error);
    }

    /** One message to whoever asked: chat for a command (on the server thread), the log otherwise. */
    private static void tell(ServerCommandSource source, Text text, boolean error) {
        if (source == null) {
            if (error) {
                StationAnnouncer.LOGGER.warn("Basemap auto-scan: {}", text.getString());
            } else {
                StationAnnouncer.LOGGER.info("Basemap auto-scan: {}", text.getString());
            }
            return;
        }
        MinecraftServer minecraftServer = server;
        Runnable send = () -> {
            try {
                if (error) {
                    source.sendError(text);
                } else {
                    source.sendFeedback(() -> text, true);
                }
            } catch (Throwable ignored) {
                // The player left; the log has it.
            }
        };
        if (minecraftServer == null || minecraftServer.isOnThread()) {
            send.run();
        } else {
            minecraftServer.execute(send);
        }
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

    private static ServerWorld worldFor(MinecraftServer minecraftServer, String dimension) {
        for (ServerWorld world : minecraftServer.getWorlds()) {
            if (dimension.equals(worldId(world))) {
                return world;
            }
        }
        return null;
    }

    private static String worldId(ServerWorld world) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a world for the basemap scan", t);
            return null;
        }
    }

    // -------------------------------------------------------------- the getters

    /**
     * The basemap's metadata: what the frontend needs to place tiles. Any thread; always
     * answers, {@code available:false} when that dimension was never scanned. Grows every
     * {@value #FLUSH_TILES} tiles during a scan, so a client that re-fetches it watches
     * the map fill in.
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
        json.addProperty("mask", true); // this build writes the class-mask tiles
        Scan scan = active;
        json.addProperty("scanning", scan != null && dimension != null && dimension.equals(scan.dimension));
        org.mtr.libraries.com.google.gson.JsonArray tiles = new org.mtr.libraries.com.google.gson.JsonArray();
        org.mtr.libraries.com.google.gson.JsonArray bbox = new org.mtr.libraries.com.google.gson.JsonArray(4);
        if (entry == null) {
            for (int i = 0; i < 4; i++) {
                bbox.add(0);
            }
        } else {
            for (int[] tile : entry.tiles()) {
                org.mtr.libraries.com.google.gson.JsonArray pair = new org.mtr.libraries.com.google.gson.JsonArray(2);
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
     * One tile's PNG bytes — the colour tile, or with {@code mask} the class mask — or
     * null when the index does not list it. Jetty workers call this; tile files are
     * immutable once moved into place, so a plain read needs no coordination.
     */
    public static byte[] tile(String dimension, int tx, int tz, boolean mask) {
        if (dimension == null) {
            return null;
        }
        Path base = root;
        Satellite entry = index.get(directoryName(dimension));
        if (base == null || entry == null || !entry.has(tx, tz)) {
            return null;
        }
        Path file = base.resolve(directoryName(dimension))
                .resolve(tx + "_" + tz + (mask ? ".m.png" : ".png")).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not read basemap tile {}", file, e);
            return null;
        }
    }

    // ------------------------------------------------------------ persistence

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
                    // Tiles written by the pre-2.4.55 scanner have no class mask (.m.png),
                    // and the map's water comes from the mask: an index without a single
                    // mask is dropped here, so the launch pass redraws that dimension in
                    // full instead of keeping tiles that would leave the schematic blank.
                    try (Stream<Path> tiles = Files.list(dir)) {
                        if (tiles.noneMatch(t -> t.getFileName().toString().endsWith(".m.png"))) {
                            StationAnnouncer.LOGGER.info("Basemap cache in {} predates the class mask — it will be redrawn",
                                    dir.getFileName());
                            continue;
                        }
                    }
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
            StationAnnouncer.LOGGER.warn("Could not list {}, the basemap starts empty", base, e);
            return Map.of();
        }
        return Map.copyOf(loaded);
    }

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

    private static long key(int tx, int tz) {
        return (((long) tx) << 32) | (tz & 0xFFFFFFFFL);
    }

    private static Satellite make(String dimension, long scannedAt, int scale, int tileSamples,
                                  int originX, int originZ, List<int[]> tiles, long[] bbox) {
        Set<Long> keys = new HashSet<>(Math.max(16, tiles.size() * 2));
        for (int[] tile : tiles) {
            keys.add(key(tile[0], tile[1]));
        }
        return new Satellite(dimension, scannedAt, scale, tileSamples, originX, originZ, tiles, bbox, Set.copyOf(keys));
    }

    // ---------------------------------------------------------------- records

    /** One dimension's tile index. Immutable once published; build it through {@link #make}. */
    private record Satellite(String dimension, long scannedAt, int scale, int tileSamples,
                             int originX, int originZ, List<int[]> tiles, long[] bbox, Set<Long> keys) {
        boolean has(int tx, int tz) {
            return keys.contains(key(tx, tz));
        }
    }

    /** One scan: built on the server thread, then owned by its worker thread. */
    private static final class Scan {
        private final String dimension;
        /** Null for the automatic pass — see {@link #tell}. */
        private final ServerCommandSource source;
        private final boolean incremental;
        private final ChunkIndex chunks;
        private final ThreadedAnvilChunkStorage storage;
        private final int originX;
        private final int originZ;
        private final List<int[]> tiles;
        private final long[] bbox;
        private final int bottomY;
        private final int height;
        private final int heightBits;
        private final boolean hasCeiling;
        private final int ceilingStartY;
        private final RegistryEntryLookup<Block> blocks = Registries.BLOCK.getReadOnlyWrapper();
        private final Map<NbtCompound, BlockState> stateCache = new HashMap<>();
        private final int[] palette = palette();

        private final byte[] colors = new byte[TILE_SAMPLES * TILE_SAMPLES];
        private final byte[] shade = new byte[TILE_SAMPLES * TILE_SAMPLES];
        private final byte[] mask = new byte[TILE_SAMPLES * TILE_SAMPLES];
        private final int[] pixels = new int[TILE_SAMPLES * TILE_SAMPLES];
        private final BufferedImage image = new BufferedImage(TILE_SAMPLES, TILE_SAMPLES, BufferedImage.TYPE_INT_ARGB);

        private volatile Thread thread;
        private volatile boolean cancelled;
        private volatile int tilesDone;
        private volatile long samples;
        private volatile long water;
        private int skippedProto;
        private int skippedOld;
        private int decodeFailures;

        private Scan(ServerWorld world, String dimension, ServerCommandSource source, boolean incremental,
                     ChunkIndex chunks, int originX, int originZ, List<int[]> tiles, long[] bbox) {
            this.dimension = dimension;
            this.source = source;
            this.incremental = incremental;
            this.chunks = chunks;
            this.storage = world.getChunkManager().threadedAnvilChunkStorage;
            this.originX = originX;
            this.originZ = originZ;
            this.tiles = tiles;
            this.bbox = bbox;
            this.bottomY = world.getBottomY();
            this.height = world.getHeight();
            this.heightBits = MathHelper.ceilLog2(this.height + 1);
            DimensionType type = world.getDimension();
            this.hasCeiling = type.hasCeiling();
            this.ceilingStartY = this.bottomY + Math.max(1, type.logicalHeight() - CEILING_HEADROOM);
        }
    }
}
