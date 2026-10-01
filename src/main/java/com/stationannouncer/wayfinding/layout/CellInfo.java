package com.stationannouncer.wayfinding.layout;

/**
 * What one block means to a walking rider, reduced to what the layout solver
 * needs — PURE JAVA, no Minecraft types, so the solver can be tested on
 * synthetic stations. The Minecraft side ({@link LayoutScanner}) builds one per
 * distinct block state from its collision shape and caches it.
 *
 * <p>The block is split into four QUADRANTS (a half-block grid: a stair is a
 * low half and a high half, and a rider climbs it one half at a time).
 * Quadrant {@code q = qx + 2*qz}, {@code qx, qz ∈ {0,1}} along +x / +z.</p>
 *
 * <p>Each quadrant carries TWO readings of the collision boxes:</p>
 * <ul>
 *   <li><b>floor</b> — boxes covering a real part of the quadrant (≥ 1/8 block
 *       each way): what a rider can stand on. A post or a pane is not a floor.</li>
 *   <li><b>obstruction</b> — ANY box touching the quadrant: what the rider's body
 *       would hit. A glass pane on the block's centre line, a 1 px railing on its
 *       edge, a door frame: all of them stop a rider without being a floor.</li>
 * </ul>
 */
public final class CellInfo {
    public static final byte TAG_NONE = 0;
    /** A turnstile / HEET lane: walk-through, marks fare control. */
    public static final byte TAG_FARE = 1;
    /** An emergency exit door: walk-through only as a last resort. */
    public static final byte TAG_EMERGENCY = 2;
    /** An MTR escalator step: walkable, never step-free. */
    public static final byte TAG_ESCALATOR = 3;

    public static final CellInfo AIR = passable(TAG_NONE, false);
    public static final CellInfo FULL = full(TAG_NONE);

    // floor reading
    final boolean[] floor;
    final float[] floorBottom;
    final float[] floorTop;
    // obstruction reading
    final boolean[] block;
    final float[] blockBottom;
    final float[] blockTop;
    final byte tag;
    final boolean liquid;

    private CellInfo(boolean[] floor, float[] floorBottom, float[] floorTop,
                     boolean[] block, float[] blockBottom, float[] blockTop, byte tag, boolean liquid) {
        this.floor = floor;
        this.floorBottom = floorBottom;
        this.floorTop = floorTop;
        this.block = block;
        this.blockBottom = blockBottom;
        this.blockTop = blockTop;
        this.tag = tag;
        this.liquid = liquid;
    }

    public static CellInfo full(byte tag) {
        return fromBoxes(new double[][]{{0, 0, 0, 1, 1, 1}}, tag, false);
    }

    /** No collision, but it may mean something (a turnstile lane, water). */
    public static CellInfo passable(byte tag, boolean liquid) {
        return new CellInfo(new boolean[4], new float[4], new float[4], new boolean[4], new float[4], new float[4],
                tag, liquid);
    }

    /**
     * From collision boxes in block-local units: {@code {minX, minY, minZ, maxX, maxY, maxZ}}.
     */
    public static CellInfo fromBoxes(double[][] boxes, byte tag, boolean liquid) {
        boolean[] floor = new boolean[4];
        float[] floorBottom = new float[4];
        float[] floorTop = new float[4];
        boolean[] block = new boolean[4];
        float[] blockBottom = new float[4];
        float[] blockTop = new float[4];
        for (int q = 0; q < 4; q++) {
            double x0 = (q & 1) * 0.5;
            double z0 = (q >> 1) * 0.5;
            float floorLo = Float.MAX_VALUE;
            float floorHi = -Float.MAX_VALUE;
            float blockLo = Float.MAX_VALUE;
            float blockHi = -Float.MAX_VALUE;
            for (double[] box : boxes) {
                if (box[4] - box[1] < 0.001) {
                    continue;
                }
                double ox = Math.min(box[3], x0 + 0.5) - Math.max(box[0], x0);
                double oz = Math.min(box[5], z0 + 0.5) - Math.max(box[2], z0);
                if (ox <= 0.0 || oz <= 0.0) {
                    continue;
                }
                blockLo = Math.min(blockLo, (float) box[1]);
                blockHi = Math.max(blockHi, (float) box[4]);
                if (ox >= 0.125 && oz >= 0.125) {
                    floorLo = Math.min(floorLo, (float) box[1]);
                    floorHi = Math.max(floorHi, (float) box[4]);
                }
            }
            if (floorHi > -Float.MAX_VALUE) {
                floor[q] = true;
                floorBottom[q] = floorLo;
                floorTop[q] = floorHi;
            }
            if (blockHi > -Float.MAX_VALUE) {
                block[q] = true;
                blockBottom[q] = blockLo;
                blockTop[q] = blockHi;
            }
        }
        return new CellInfo(floor, floorBottom, floorTop, block, blockBottom, blockTop, tag, liquid);
    }
}
