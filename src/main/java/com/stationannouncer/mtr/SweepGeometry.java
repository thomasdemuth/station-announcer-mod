package com.stationannouncer.mtr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure geometry of the Structure Sweeper (no Minecraft types, unit-tested by
 * {@code tools/sweep_test/SweepGeometryTest.java}).
 *
 * <p>The sweep maps WORLD → TEMPLATE: every world column near the track is
 * given a distance along the track ({@code s}), a sideways offset
 * ({@code u}, + = right of travel) and the rail height there. Walking world
 * columns (instead of pushing template blocks out onto the curve) is what
 * makes the result gap-free and double-free: the outside of a curve
 * stretches, the inside squeezes, and every cell is filled exactly once.</p>
 *
 * <p>A column takes the sample whose normal plane passes closest to its
 * centre (smallest |longitudinal offset|). On a straight that is the exact
 * projection; on a curve it is the nearest normal, which keeps a column on
 * one consistent side even on the inside of a tight bend.</p>
 */
public final class SweepGeometry {
    /** What the sweep follows: a rail (via RailMath) or a straight line. */
    public interface PathFn {
        double length();

        /** World position {x, y, z} at a distance along the path (clamped). */
        double[] at(double distance);
    }

    /** One world column the sweep fills. */
    public static final class Column {
        public final int x;
        public final int z;
        /** Distance along the path of the column centre. */
        public double s;
        /** Sideways offset of the column centre, + = right of travel. */
        public double u;
        /** Horizontal unit tangent at the chosen sample. */
        public double tx, tz;
        double score = Double.MAX_VALUE;

        Column(int x, int z) {
            this.x = x;
            this.z = z;
        }

        /** Unit perpendicular, pointing right of travel. */
        public double px() {
            return -tz;
        }

        public double pz() {
            return tx;
        }
    }

    /** How template length maps onto the track. */
    public enum Mode {
        /** The template every L blocks; the last bay may be cut short. */
        REPEAT,
        /** A whole number of copies, each slightly stretched/squeezed, filling node to node. */
        STRETCH,
        /** One copy stretched over the whole selection. */
        ONCE
    }

    public static final double STEP = 0.25;
    private static final double LATERAL_STEP = 0.35;

    private SweepGeometry() {
    }

    /** A straight path between two points. */
    public record Line(double ax, double ay, double az, double bx, double by, double bz) implements PathFn {
        @Override
        public double length() {
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public double[] at(double distance) {
            double len = length();
            double t = len <= 0 ? 0 : Math.max(0, Math.min(1, distance / len));
            return new double[]{ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t};
        }
    }

    /**
     * A path sampled into points (what the server sends the client for the
     * preview, and what it then builds along, so preview == build).
     */
    public static final class Polyline implements PathFn {
        public final double[] x, y, z;
        private final double[] dist;

        public Polyline(double[] x, double[] y, double[] z) {
            this.x = x;
            this.y = y;
            this.z = z;
            dist = new double[x.length];
            for (int i = 1; i < x.length; i++) {
                double dx = x[i] - x[i - 1], dy = y[i] - y[i - 1], dz = z[i] - z[i - 1];
                dist[i] = dist[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        /** Samples a path every {@code step} blocks (both ends included). */
        public static Polyline sample(PathFn path, double step) {
            double length = path.length();
            int n = Math.max(2, (int) Math.ceil(length / step) + 1);
            double[] x = new double[n], y = new double[n], z = new double[n];
            for (int i = 0; i < n; i++) {
                double[] p = path.at(length * i / (n - 1));
                x[i] = p[0];
                y[i] = p[1];
                z[i] = p[2];
            }
            return new Polyline(x, y, z);
        }

        public int size() {
            return x.length;
        }

        @Override
        public double length() {
            return dist[dist.length - 1];
        }

        @Override
        public double[] at(double d) {
            if (d <= 0) {
                return new double[]{x[0], y[0], z[0]};
            }
            int n = x.length;
            if (d >= dist[n - 1]) {
                return new double[]{x[n - 1], y[n - 1], z[n - 1]};
            }
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (dist[mid] <= d) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double seg = dist[hi] - dist[lo];
            double t = seg <= 0 ? 0 : (d - dist[lo]) / seg;
            return new double[]{x[lo] + (x[hi] - x[lo]) * t, y[lo] + (y[hi] - y[lo]) * t, z[lo] + (z[hi] - z[lo]) * t};
        }
    }

    /**
     * Every world column whose centre lies within {@code [uMin, uMax]} of the
     * path (sideways) and within its length, keyed by
     * {@code BlockPos.asLong}-style packing of (x, z) ({@link #key}).
     */
    public static Map<Long, Column> columns(PathFn path, double uMin, double uMax) {
        Map<Long, Column> out = new HashMap<>();
        double length = path.length();
        if (length <= 1e-6 || uMax < uMin) {
            return out;
        }
        List<double[]> samples = new ArrayList<>();
        for (double d = 0; d <= length + 1e-9; d += STEP) {
            samples.add(sample(path, Math.min(d, length), length));
        }
        double lo = uMin - 1.0, hi = uMax + 1.0;
        for (double[] smp : samples) {
            double d = smp[0], cx = smp[1], cz = smp[3], tx = smp[4], tz = smp[5];
            double px = -tz, pz = tx;
            for (double l = lo; l <= hi + 1e-9; l += LATERAL_STEP) {
                int x = (int) Math.floor(cx + px * l);
                int z = (int) Math.floor(cz + pz * l);
                consider(out, x, z, d, cx, cz, tx, tz);
                // the neighbours too: on the outside of a curve the lateral
                // walk can step over a column the nearest normal belongs to
                consider(out, x + 1, z, d, cx, cz, tx, tz);
                consider(out, x, z + 1, d, cx, cz, tx, tz);
            }
        }
        out.values().removeIf(c -> c.s < 0 || c.s > length || c.u < uMin || c.u > uMax);
        return out;
    }

    private static void consider(Map<Long, Column> out, int x, int z, double d, double cx, double cz, double tx, double tz) {
        double dx = x + 0.5 - cx;
        double dz = z + 0.5 - cz;
        double lon = dx * tx + dz * tz;
        double score = Math.abs(lon);
        if (score > STEP * 3) {
            return;
        }
        Column c = out.computeIfAbsent(key(x, z), k -> new Column(x, z));
        if (score < c.score) {
            c.score = score;
            c.s = d + lon;
            c.u = dx * -tz + dz * tx;
            c.tx = tx;
            c.tz = tz;
        }
    }

    /** {distance, x, y, z, tx, tz} with a horizontal unit tangent. */
    private static double[] sample(PathFn path, double d, double length) {
        double[] c = path.at(d);
        double a0 = Math.max(0, d - STEP), a1 = Math.min(length, d + STEP);
        double[] a = path.at(a0);
        double[] b = path.at(a1);
        double tx = b[0] - a[0], tz = b[2] - a[2];
        double len = Math.sqrt(tx * tx + tz * tz);
        if (len < 1e-9) {
            tx = 1;
            tz = 0;
        } else {
            tx /= len;
            tz /= len;
        }
        return new double[]{d, c[0], c[1], c[2], tx, tz};
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Template cell index along its length (0 … L−1) for a distance along a
     * path of {@code pathLength}; {@code phase} shifts REPEAT copies.
     */
    public static int templateS(double s, double pathLength, int templateLength, Mode mode, double phase) {
        if (templateLength <= 0) {
            return 0;
        }
        double t;
        switch (mode) {
            case STRETCH -> {
                int copies = Math.max(1, (int) Math.round(pathLength / templateLength));
                t = pathLength <= 0 ? 0 : s * copies * templateLength / pathLength;
            }
            case ONCE -> t = pathLength <= 0 ? 0 : s * templateLength / pathLength;
            default -> t = s + phase;
        }
        int i = (int) Math.floor(t);
        if (mode == Mode.ONCE) {
            return Math.max(0, Math.min(templateLength - 1, i));
        }
        i %= templateLength;
        return i < 0 ? i + templateLength : i;
    }

    /** Copies of the template that STRETCH puts on a path, and their stretch factor. */
    public static double stretchFactor(double pathLength, int templateLength) {
        int copies = Math.max(1, (int) Math.round(pathLength / Math.max(1, templateLength)));
        return pathLength / ((double) copies * Math.max(1, templateLength));
    }

    /** Sideways template cell (0 = the rail's own cell) for an offset {@code u}. */
    public static int templateU(double u) {
        return (int) Math.floor(u + 0.5);
    }

    /**
     * Which of the four quarter turns (0 = east, 1 = south, 2 = west,
     * 3 = north — clockwise from east) is closest to a horizontal direction.
     */
    public static int quarter(double tx, double tz) {
        double angle = Math.toDegrees(Math.atan2(tz, tx)); // 0 = +x (east), 90 = +z (south)
        return (int) Math.floorMod(Math.round(angle / 90.0), 4L);
    }
}
