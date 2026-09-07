package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Which chunks of a dimension exist ON DISK, read from the region-file headers alone —
 * the Dynmap technique. The first 4 KiB of every {@code r.X.Z.mca} is a table of 1024
 * sector offsets; a nonzero entry means the chunk has been written at some point. Reading
 * those tables touches nothing the game cares about, loads no chunk and generates
 * nothing, which is what lets a whole-world scan decide where to look without ever
 * asking the chunk manager.
 *
 * <p>NOTE a header entry says "written", not "finished": the thin ring of proto-chunks
 * vanilla saves around every explored area is in here too. The basemap scanner reads
 * each chunk's saved {@code Status} and skips the unfinished ones — the old scanners
 * handed them to {@code world.getChunk}, which FINISHED generating them (and wrote a
 * fresh proto ring around each), so every launch grew the world and rescanned it.</p>
 *
 * <p>Immutable once built; safe to share across threads.</p>
 */
public final class ChunkIndex {
    private static final ChunkIndex EMPTY = new ChunkIndex(Map.of(), 0, 0, -1, -1, 0);

    private final Map<Long, long[]> regions;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final int count;

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

    /** How many chunks exist on disk (finished or not). */
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

    /** Does this chunk exist on disk? Any thread. */
    public boolean has(int chunkX, int chunkZ) {
        if (count == 0 || chunkX < minChunkX || chunkX > maxChunkX
                || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
            return false;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long[] bits = regions.get(regionKey(regionX, regionZ));
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

    /**
     * Any thread: {@code <dimension folder>/region/r.X.Z.mca} → index.
     *
     * <p>The dimension folder is
     * {@code DimensionType.getSaveDirectory(world.getRegistryKey(), <save root>)}
     * (bytecode-verified on 1.20.4: overworld = the save root itself, nether =
     * {@code DIM-1}, end = {@code DIM1}, anything else =
     * {@code dimensions/<namespace>/<path>}).</p>
     */
    public static ChunkIndex enumerate(MinecraftServer minecraftServer, ServerWorld world) {
        Path regionDir = DimensionType
                .getSaveDirectory(world.getRegistryKey(), minecraftServer.getSavePath(WorldSavePath.ROOT))
                .resolve("region").normalize();
        if (!Files.isDirectory(regionDir)) {
            return EMPTY;
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
            return EMPTY;
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
                minChunkX = Math.min(minChunkX, chunkX);
                maxChunkX = Math.max(maxChunkX, chunkX);
                minChunkZ = Math.min(minChunkZ, chunkZ);
                maxChunkZ = Math.max(maxChunkZ, chunkZ);
            }
            if (present > 0) {
                regions.put(regionKey(regionX, regionZ), bits);
                count += present;
            }
        }
        if (count == 0) {
            return EMPTY;
        }
        return new ChunkIndex(Map.copyOf(regions), minChunkX, minChunkZ, maxChunkX, maxChunkZ, count);
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
            // An empty (0-byte) region file is ordinary; anything shorter than a full
            // header has no chunks we can name.
            return read >= header.length;
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not read the header of {}", file, e);
            return false;
        }
    }
}
