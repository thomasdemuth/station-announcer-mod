package com.stationannouncer.wayfinding.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the solver needs about one station, as plain values — PURE JAVA.
 * Built by {@link LayoutScanner} from the world (blocks) and MTR (platforms,
 * rails, lifts) and our store (exit markers); built by hand in the tests.
 *
 * <p>The region is a box of blocks: {@code palette[cells[((y * sizeZ) + z) * sizeX + x]]}
 * with (x, y, z) relative to (minX, minY, minZ) — two bytes per block (an index into the
 * palette of distinct {@link CellInfo}s) rather than an 8-byte reference, which is what lets a
 * scan cover 40 million blocks (Idlewild: 233×251 blocks and very tall). {@code surface[z * sizeX + x]}
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
    /** Palette index per block; 0 = {@link CellInfo#AIR}. Set up by {@link #allocate}. */
    public char[] cells;
    /** The distinct cells; index 0 is AIR. */
    public final List<CellInfo> palette = new ArrayList<>(List.of(CellInfo.AIR));
    private final java.util.IdentityHashMap<CellInfo, Character> paletteIndex = new java.util.IdentityHashMap<>();
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
    /**
     * Street openings a player removed ({x, y, z} block positions): the solver suggests no
     * opening within {@code LayoutSolver.DISMISSED_RADIUS} of one, so a rescan keeps them gone.
     */
    public final List<int[]> dismissedOpenings = new ArrayList<>();
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

    /** Sizes the box (every block starts as AIR). */
    public void allocate(int sx, int sy, int sz) {
        sizeX = sx;
        sizeY = sy;
        sizeZ = sz;
        cells = new char[sx * sy * sz];
    }

    /** Sets one block (relative coordinates); out-of-box writes are ignored. */
    public void set(int x, int y, int z, CellInfo cell) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return;
        }
        cells[((y * sizeZ) + z) * sizeX + x] = indexOf(cell);
    }

    private char indexOf(CellInfo cell) {
        if (cell == null || cell == CellInfo.AIR) {
            return 0;
        }
        Character index = paletteIndex.get(cell);
        if (index == null) {
            if (palette.size() >= Character.MAX_VALUE) {
                throw new IllegalStateException("more than 65535 distinct block shapes in one scan");
            }
            index = (char) palette.size();
            palette.add(cell);
            paletteIndex.put(cell, index);
        }
        return index;
    }

    public CellInfo cell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return CellInfo.AIR;
        }
        return palette.get(cells[((y * sizeZ) + z) * sizeX + x]);
    }

    public int surfaceAt(int x, int z) {
        if (surface == null || x < 0 || z < 0 || x >= sizeX || z >= sizeZ) {
            return Integer.MIN_VALUE;
        }
        return surface[z * sizeX + x];
    }
}
