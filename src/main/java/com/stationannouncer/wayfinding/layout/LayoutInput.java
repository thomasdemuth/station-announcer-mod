package com.stationannouncer.wayfinding.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the solver needs about one station, as plain values — PURE JAVA.
 * Built by {@link LayoutScanner} from the world (blocks) and MTR (platforms,
 * rails, lifts) and our store (exit markers); built by hand in the tests.
 *
 * <p>The region is a box of blocks: {@code cells[((y * sizeZ) + z) * sizeX + x]}
 * with (x, y, z) relative to (minX, minY, minZ). {@code surface[z * sizeX + x]}
 * is the absolute Y of the first free block above the world surface in that
 * column (Minecraft's MOTION_BLOCKING heightmap), or {@link Integer#MIN_VALUE}
 * when unknown: a floor at or above it is out in the open.</p>
 */
public final class LayoutInput {
    public long stationId;
    public String stationName = "";
    public int minX;
    public int minY;
    public int minZ;
    public int sizeX;
    public int sizeY;
    public int sizeZ;
    public CellInfo[] cells;
    public int[] surface;
    /** The MTR station area (x/z, inclusive block bounds). */
    public long areaMinX;
    public long areaMaxX;
    public long areaMinZ;
    public long areaMaxZ;
    /** How far the scanned box reaches past the MTR area (reported with the result only). */
    public int margin;
    /** Rail centreline samples ({x, y, z}, world units) every ~0.5 blocks of every rail through the region. */
    public final List<double[]> track = new ArrayList<>();
    public final List<Platform> platforms = new ArrayList<>();
    public final List<Exit> exits = new ArrayList<>();
    public final List<Lift> lifts = new ArrayList<>();
    /** Anything the reader wants reported with the result (a clipped region, unreadable chunks). */
    public final List<String> warnings = new ArrayList<>();

    /** A platform: its rail's centreline samples (the train stops alongside them). */
    public record Platform(long id, String name, List<double[]> samples) {
    }

    /** An Exit Marker pinned to this station ({@code name} = MTR's exit name, e.g. "A2"). */
    public record Exit(String name, int x, int y, int z) {
    }

    /**
     * An MTR lift: the block positions of its floors (feet level at each landing) and
     * how far from that position its landings reach (half the car plus a doorway).
     */
    public record Lift(long id, List<int[]> floors, double radius) {
    }

    public CellInfo cell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return CellInfo.AIR;
        }
        CellInfo cell = cells[((y * sizeZ) + z) * sizeX + x];
        return cell == null ? CellInfo.AIR : cell;
    }

    public int surfaceAt(int x, int z) {
        if (surface == null || x < 0 || z < 0 || x >= sizeX || z >= sizeZ) {
            return Integer.MIN_VALUE;
        }
        return surface[z * sizeX + x];
    }
}
