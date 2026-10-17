package com.stationannouncer.client.mtraddon.cab;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.PathData;
import org.mtr.core.tool.Vector;
import java.util.Random;

/**
 * Where {@link CabPassengers} may put people on one platform: a grid of standing SLOTS beside
 * the platform's rail, found by probing the client world once when the platform is first
 * needed (never per tick).
 *
 * <p><b>Geometry.</b> The rail comes from the train's own path: the {@link PathData} dwell
 * segment of the platform, sampled with {@code getPosition(0..length)} (MTR's rail maths, or
 * its straight-line fallback when the client has not resolved the rail), so {@code s} runs in
 * the train's travel direction and the train stands on {@code s ∈ [length − trainLength,
 * length]} (MTR stops a train with its head at the end of the platform segment).</p>
 *
 * <p><b>Platform side.</b> Every metre along the rail, four rows at {@link #ROWS} blocks from
 * the rail centre are probed on BOTH sides: the topmost block in a small window around the rail
 * height whose collision top is 0.4–2.3 blocks above the rail (MTR platform blocks sit one block
 * above the rail; the trackbed beside a rail is level with it and therefore never counts), with
 * two free blocks above it. A side is used only when at least 35 % of the platform has floor
 * there — an island platform gets both sides, a plain track side none. A platform with no side
 * keeps an empty slot list (cached, so it is not probed again while in range).</p>
 */
@Environment(EnvType.CLIENT)
final class PaxPlatform {
    /** Lateral distances (blocks from the rail centre) of the slot rows; the last is the "back". */
    static final double[] ROWS = {1.8, 2.4, 3.0, 3.6};
    static final int BACK_ROW = ROWS.length - 1;
    /** Waiting crowds use the front rows only (the back row is for walking past). */
    static final int MAX_WAIT_ROW = 2;
    private static final double MAX_LENGTH = 400;
    private static final double MIN_RISE = 0.4;
    private static final double MAX_RISE = 2.3;
    private static final double SIDE_COVERAGE = 0.35;

    final long platformId;
    /** Shared seed (platform, vehicle, path distance): every crew client builds the same crowd. */
    final long seed;
    final double startX, startZ, midX, midY, midZ, endX, endZ;
    final double length;

    final int slotCount;
    final double[] slotX;
    final double[] slotY;
    final double[] slotZ;
    /** Yaw facing the track (degrees, Minecraft convention). */
    final float[] slotYaw;
    /** Unit vector along the rail (travel direction) at the slot. */
    final float[] slotTanX;
    final float[] slotTanZ;
    final float[] slotS;
    final byte[] slotRow;
    final boolean[] slotFootprint;
    final boolean[] taken;

    /** Waiting crowd this platform aims for (0–10). */
    int target;
    /** World-time bucket of the last trickle roll (new arrivals while waiting). */
    long trickleBucket = Long.MIN_VALUE;

    private PaxPlatform(long platformId, long seed, Vector start, Vector mid, Vector end, double length, int count) {
        this.platformId = platformId;
        this.seed = seed;
        startX = start.x;
        startZ = start.z;
        midX = mid.x;
        midY = mid.y;
        midZ = mid.z;
        endX = end.x;
        endZ = end.z;
        this.length = length;
        slotCount = count;
        slotX = new double[count];
        slotY = new double[count];
        slotZ = new double[count];
        slotYaw = new float[count];
        slotTanX = new float[count];
        slotTanZ = new float[count];
        slotS = new float[count];
        slotRow = new byte[count];
        slotFootprint = new boolean[count];
        taken = new boolean[count];
    }

    boolean isEmpty() {
        return slotCount == 0;
    }

    /** Smallest horizontal distance from the point to the platform's start, middle or end. */
    double distanceTo(double x, double z) {
        double a = sq(x - startX, z - startZ);
        double b = sq(x - midX, z - midZ);
        double c = sq(x - endX, z - endZ);
        return Math.sqrt(Math.min(a, Math.min(b, c)));
    }

    /**
     * Probes the platform beside {@code pathData}. Returns null when part of it is in an
     * unloaded chunk (retry later — the client reads air there, which would cache a false
     * "no floor"), an empty platform when it has no usable side.
     */
    @Nullable
    static PaxPlatform build(ClientWorld world, long platformId, long seed, PathData pathData, double trainLength,
                             Random random) {
        double length = pathData.getEndDistance() - pathData.getStartDistance();
        Vector start = pathData.getPosition(0);
        Vector end = pathData.getPosition(Math.max(0, length));
        Vector mid = pathData.getPosition(Math.max(0, length) / 2);
        if (!loaded(world, start) || !loaded(world, mid) || !loaded(world, end)) {
            return null;
        }
        if (!(length >= 2) || length > MAX_LENGTH) {
            return new PaxPlatform(platformId, seed, start, mid, end, Math.max(0, length), 0);
        }
        int samples = (int) Math.floor(length);
        int capacity = samples * ROWS.length * 2;
        double[] x = new double[capacity];
        double[] y = new double[capacity];
        double[] z = new double[capacity];
        float[] yaw = new float[capacity];
        float[] tanX = new float[capacity];
        float[] tanZ = new float[capacity];
        float[] along = new float[capacity];
        byte[] row = new byte[capacity];
        byte[] side = new byte[capacity];
        int[] sideHits = new int[2];
        int count = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 0; i < samples; i++) {
            double s = i + 0.5;
            Vector p = pathData.getPosition(s);
            Vector p0 = pathData.getPosition(Math.max(0, s - 0.5));
            Vector p1 = pathData.getPosition(Math.min(length, s + 0.5));
            double tx = p1.x - p0.x;
            double tz = p1.z - p0.z;
            double norm = Math.sqrt(tx * tx + tz * tz);
            if (norm < 1E-6) {
                continue;
            }
            tx /= norm;
            tz /= norm;
            // "Right" of the travel direction in Minecraft's x-east / z-south frame.
            double rx = -tz;
            double rz = tx;
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                int sign = sideIndex == 0 ? 1 : -1;
                boolean hit = false;
                for (int r = 0; r < ROWS.length; r++) {
                    double d = ROWS[r] * sign;
                    double sx = p.x + rx * d;
                    double sz = p.z + rz * d;
                    double floor = probeFloor(world, pos, sx, p.y, sz);
                    if (Double.isNaN(floor)) {
                        continue;
                    }
                    hit = true;
                    x[count] = sx;
                    y[count] = floor;
                    z[count] = sz;
                    // Face the rail: the direction back toward the centre line.
                    yaw[count] = yawOf(-rx * sign, -rz * sign);
                    tanX[count] = (float) tx;
                    tanZ[count] = (float) tz;
                    along[count] = (float) s;
                    row[count] = (byte) r;
                    side[count] = (byte) sideIndex;
                    count++;
                }
                if (hit) {
                    sideHits[sideIndex]++;
                }
            }
        }
        int needed = Math.max(2, (int) Math.ceil(samples * SIDE_COVERAGE));
        boolean[] keepSide = {sideHits[0] >= needed, sideHits[1] >= needed};
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (keepSide[side[i]]) {
                kept++;
            }
        }
        PaxPlatform platform = new PaxPlatform(platformId, seed, start, mid, end, length, kept);
        double footprintStart = length - Math.max(1, trainLength) - 1;
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (!keepSide[side[i]]) {
                continue;
            }
            platform.slotX[k] = x[i];
            platform.slotY[k] = y[i];
            platform.slotZ[k] = z[i];
            platform.slotYaw[k] = yaw[i];
            platform.slotTanX[k] = tanX[i];
            platform.slotTanZ[k] = tanZ[i];
            platform.slotS[k] = along[i];
            platform.slotRow[k] = row[i];
            platform.slotFootprint[k] = along[i] >= footprintStart;
            k++;
        }
        // Thomas: "small and varied" — 0..10, most often a handful.
        platform.target = kept == 0 ? 0 : random.nextInt(4) + random.nextInt(8);
        return platform;
    }

    /**
     * A free waiting slot (front rows), preferring where the train will stand; -1 if none.
     */
    int pickWaitingSlot(Random random) {
        if (slotCount == 0) {
            return -1;
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            int i = random.nextInt(slotCount);
            if (!taken[i] && slotRow[i] <= MAX_WAIT_ROW && (slotFootprint[i] || attempt >= 10)) {
                return i;
            }
        }
        for (int i = 0; i < slotCount; i++) {
            if (!taken[i] && slotRow[i] <= MAX_WAIT_ROW && slotFootprint[i]) {
                return i;
            }
        }
        return -1;
    }

    /** A random back-row slot (where arrivals appear and alighting passengers walk to); -1 if none. */
    int pickBackSlot(Random random) {
        if (slotCount == 0) {
            return -1;
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            int i = random.nextInt(slotCount);
            if (slotRow[i] == BACK_ROW) {
                return i;
            }
        }
        return random.nextInt(slotCount);
    }

    /** The slot nearest to a point, optionally only back-row slots; -1 if none within {@code maxDistance}. */
    int nearestSlot(double x, double z, boolean backRowOnly, double maxDistance) {
        int best = -1;
        double bestDistance = maxDistance * maxDistance;
        for (int i = 0; i < slotCount; i++) {
            if (backRowOnly && slotRow[i] != BACK_ROW) {
                continue;
            }
            double d = sq(slotX[i] - x, slotZ[i] - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ probing

    /**
     * The standing height at (x, z): the topmost colliding block in the window from two blocks
     * above the rail to one below, if its top is a plausible platform floor (0.4–2.3 above the
     * rail, not a fence-tall shape) with two blocks of head room. NaN otherwise.
     */
    private static double probeFloor(ClientWorld world, BlockPos.Mutable pos, double x, double railY, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int base = (int) Math.floor(railY);
        for (int by = base + 2; by >= base - 1; by--) {
            pos.set(bx, by, bz);
            BlockState state = world.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(world, pos);
            if (shape.isEmpty()) {
                continue;
            }
            double height = shape.getMax(Direction.Axis.Y);
            if (height > 1.01 || height < 0.1) {
                return Double.NaN; // a fence/wall or a sliver: not a floor
            }
            if (!free(world, pos.set(bx, by + 1, bz)) || !free(world, pos.set(bx, by + 2, bz))) {
                return Double.NaN;
            }
            double top = by + height;
            double rise = top - railY;
            return rise >= MIN_RISE && rise <= MAX_RISE ? top : Double.NaN;
        }
        return Double.NaN;
    }

    private static boolean free(ClientWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean loaded(ClientWorld world, Vector v) {
        return world.isChunkLoaded((int) Math.floor(v.x) >> 4, (int) Math.floor(v.z) >> 4);
    }

    /** Minecraft yaw (degrees; 0 = south, 90 = west) looking along (dx, dz). */
    static float yawOf(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    private static double sq(double dx, double dz) {
        return dx * dx + dz * dz;
    }
}
