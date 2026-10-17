package com.stationannouncer.track;

import com.stationannouncer.track.TrackProfile.Piece;
import com.stationannouncer.track.TrackProfile.PieceType;

import java.util.ArrayList;
import java.util.List;

/**
 * The evaluated geometry of a {@link TrackProfile}: position, heading, curvature, height and tilt
 * at any distance along the chain, plus drop-in replacements for every public {@code RailMath}
 * query (see the "MTR facade" section). Immutable and thread-safe once built — the server's
 * simulator thread, the client render thread and the editor all read the same instance.
 *
 * Build with {@link #build(TrackProfile, String, double)}; the two MTR arguments are the rail's
 * own shape name and vertical radius, used for the height when the profile's vertical mode is
 * {@code MTR} (heights then follow MTR's exact formulas, evaluated over OUR length).
 */
public final class TrackPath {
    /** Max spacing of the spiral lookup tables (blocks). */
    private static final double TABLE_STEP = 0.05;

    public final TrackProfile profile;
    private final int count;
    private final PieceType[] type;
    private final double[] s0;
    private final double[] len;
    private final double[] x0;
    private final double[] z0;
    private final double[] th0;
    private final double[] k0;
    private final double[] k1;
    /** Spiral tables (null for other pieces): positions at i * step[p]. */
    private final double[][] tableX;
    private final double[][] tableZ;
    private final double[] tableStep;
    /** SPLINE geometry: per segment control points {x0,z0,x1,z1,x2,z2,x3,z3} and cumulative arc length at t = j/N. */
    private final boolean spline;
    private final double[][] bez;
    private final double[][] segCum;
    private double splineMinRadius;
    private double splineMaxRadius;

    private final double length;
    private final double endX;
    private final double endZ;
    private final double endHeading;
    private final double posErr;
    private final double headErr;

    // height
    private final boolean customHeight;
    private final double yStart;
    private final double yEnd;
    private final String mtrShape;
    private final double mtrVerticalRadius;
    private final double[] gradeS;
    private final double[] gradeY;
    private final double[] gradeHalf;
    private final double[] gradeSlope;

    // tilt
    private final double[] tiltS;
    private final double[] tiltD;
    private final boolean smoothTilt;
    private final boolean hasTilt;

    // MTR facade
    public final long minX;
    public final long minY;
    public final long minZ;
    public final long maxX;
    public final long maxY;
    public final long maxZ;

    private TrackPath(TrackProfile profile, String mtrShape, double mtrVerticalRadius) {
        this.profile = profile;
        spline = profile.geometry == TrackProfile.Geometry.SPLINE && profile.anchors != null && profile.anchors.size() >= 2;
        final List<Piece> pieces = spline ? List.of() : profile.pieces;
        count = spline ? profile.anchors.size() - 1 : pieces.size();
        bez = new double[spline ? count : 0][];
        segCum = new double[spline ? count : 0][];
        type = new PieceType[count];
        s0 = new double[count];
        len = new double[count];
        x0 = new double[count];
        z0 = new double[count];
        th0 = new double[count];
        k0 = new double[count];
        k1 = new double[count];
        tableX = new double[count][];
        tableZ = new double[count][];
        tableStep = new double[count];
        if (!spline) {
            curvatures(pieces, k0, k1);
        }

        double s = 0;
        double x = profile.ax + 0.5;
        double z = profile.az + 0.5;
        double theta = profile.headingA;
        if (spline) {
            double minRadius = Double.MAX_VALUE;
            double maxRadius = 0;
            final double[] eval = new double[6];
            for (int i = 0; i < count; i++) {
                final TrackProfile.Anchor a = profile.anchors.get(i);
                final TrackProfile.Anchor b = profile.anchors.get(i + 1);
                final double[] c = {a.x, a.z,
                    a.x + Math.cos(a.heading) * a.handleOut, a.z + Math.sin(a.heading) * a.handleOut,
                    b.x - Math.cos(b.heading) * b.handleIn, b.z - Math.sin(b.heading) * b.handleIn,
                    b.x, b.z};
                bez[i] = c;
                final double polygon = Math.hypot(c[2] - c[0], c[3] - c[1]) + Math.hypot(c[4] - c[2], c[5] - c[3]) + Math.hypot(c[6] - c[4], c[7] - c[5]);
                final int n = Math.max(16, Math.min(20000, (int) Math.ceil(polygon / 0.05)));
                final double[] cum = new double[n + 1];
                for (int j = 1; j <= n; j++) {
                    cum[j] = cum[j - 1] + speedIntegral(c, (j - 1) / (double) n, j / (double) n);
                }
                segCum[i] = cum;
                type[i] = PieceType.STRAIGHT; // unused in spline mode
                len[i] = cum[n];
                s0[i] = s;
                x0[i] = c[0];
                z0[i] = c[1];
                bezierEval(c, 0, eval);
                th0[i] = Math.atan2(eval[3], eval[2]);
                k0[i] = eval[5];
                bezierEval(c, 1, eval);
                k1[i] = eval[5];
                for (int j = 0; j <= 32; j++) {
                    bezierEval(c, j / 32.0, eval);
                    final double k = Math.abs(eval[5]);
                    if (k > 1e-6) {
                        minRadius = Math.min(minRadius, 1 / k);
                        maxRadius = Math.max(maxRadius, 1 / k);
                    }
                }
                s += len[i];
                x = c[6];
                z = c[7];
                theta = Math.atan2(eval[3], eval[2]);
            }
            splineMinRadius = minRadius == Double.MAX_VALUE ? 0 : minRadius;
            splineMaxRadius = maxRadius;
        }
        for (int i = 0; i < (spline ? 0 : count); i++) {
            final Piece piece = pieces.get(i);
            type[i] = piece.type;
            len[i] = Math.max(0, piece.length);
            s0[i] = s;
            x0[i] = x;
            z0[i] = z;
            th0[i] = theta;
            if (type[i] == PieceType.SPIRAL && len[i] > 0) {
                final int steps = Math.max(2, (int) Math.ceil(len[i] / TABLE_STEP));
                final double step = len[i] / steps;
                final double[] tx = new double[steps + 1];
                final double[] tz = new double[steps + 1];
                tx[0] = x;
                tz[0] = z;
                for (int j = 1; j <= steps; j++) {
                    // Simpson over [a, b] of this table step
                    final double a = (j - 1) * step;
                    final double m = a + step / 2;
                    final double b = j * step;
                    final double ha = spiralHeading(theta, k0[i], k1[i], len[i], a);
                    final double hm = spiralHeading(theta, k0[i], k1[i], len[i], m);
                    final double hb = spiralHeading(theta, k0[i], k1[i], len[i], b);
                    tx[j] = tx[j - 1] + step / 6 * (Math.cos(ha) + 4 * Math.cos(hm) + Math.cos(hb));
                    tz[j] = tz[j - 1] + step / 6 * (Math.sin(ha) + 4 * Math.sin(hm) + Math.sin(hb));
                }
                tableX[i] = tx;
                tableZ[i] = tz;
                tableStep[i] = step;
                x = tx[steps];
                z = tz[steps];
            } else {
                final double[] end = pieceEnd(type[i], x, z, theta, k0[i], len[i]);
                x = end[0];
                z = end[1];
            }
            theta = theta + (k0[i] + k1[i]) / 2 * len[i];
            s += len[i];
        }
        length = s;
        endX = x;
        endZ = z;
        endHeading = theta;
        posErr = Math.hypot(x - (profile.bx + 0.5), z - (profile.bz + 0.5));
        headErr = Math.abs(wrap(theta - profile.headingB));

        // height
        yStart = profile.ay;
        yEnd = profile.by;
        this.mtrShape = mtrShape == null ? "QUADRATIC" : mtrShape;
        customHeight = profile.vertical == TrackProfile.VerticalMode.CUSTOM;
        this.mtrVerticalRadius = Math.min(mtrVerticalRadius, maxVerticalRadius(length, yStart, yEnd));
        final List<double[]> knots = new ArrayList<>();
        knots.add(new double[]{0, yStart, 0});
        if (customHeight) {
            for (final TrackProfile.GradePoint point : profile.grades) {
                if (point.s > knots.get(knots.size() - 1)[0] + 0.05 && point.s < length - 0.05) {
                    knots.add(new double[]{point.s, point.y, Math.max(0, point.curve)});
                }
            }
        }
        knots.add(new double[]{length, yEnd, 0});
        final int kc = knots.size();
        gradeS = new double[kc];
        gradeY = new double[kc];
        gradeHalf = new double[kc];
        gradeSlope = new double[kc - 1];
        for (int i = 0; i < kc; i++) {
            gradeS[i] = knots.get(i)[0];
            gradeY[i] = knots.get(i)[1];
        }
        for (int i = 0; i < kc - 1; i++) {
            final double ds = gradeS[i + 1] - gradeS[i];
            gradeSlope[i] = ds > 1e-9 ? (gradeY[i + 1] - gradeY[i]) / ds : 0;
        }
        for (int i = 1; i < kc - 1; i++) {
            gradeHalf[i] = Math.min(knots.get(i)[2] / 2, Math.min((gradeS[i] - gradeS[i - 1]) / 2, (gradeS[i + 1] - gradeS[i]) / 2));
        }

        // tilt
        final List<double[]> tiltKnots = new ArrayList<>();
        tiltKnots.add(new double[]{0, profile.tiltA});
        for (final TrackProfile.TiltPoint point : profile.tilts) {
            if (point.s > tiltKnots.get(tiltKnots.size() - 1)[0] + 0.01 && point.s < length - 0.01) {
                tiltKnots.add(new double[]{point.s, point.degrees});
            }
        }
        tiltKnots.add(new double[]{length, profile.tiltB});
        tiltS = new double[tiltKnots.size()];
        tiltD = new double[tiltKnots.size()];
        boolean anyTilt = false;
        for (int i = 0; i < tiltKnots.size(); i++) {
            tiltS[i] = tiltKnots.get(i)[0];
            tiltD[i] = TrackProfile.clampTilt(tiltKnots.get(i)[1]);
            anyTilt |= Math.abs(tiltD[i]) > 1e-6;
        }
        smoothTilt = profile.smoothTilt;
        hasTilt = anyTilt;

        // bounds, like RailMath: centre line sampled every 0.1
        double bx0 = Double.MAX_VALUE, by0 = Double.MAX_VALUE, bz0 = Double.MAX_VALUE;
        double bx1 = -Double.MAX_VALUE, by1 = -Double.MAX_VALUE, bz1 = -Double.MAX_VALUE;
        final int samples = Math.max(1, (int) Math.ceil(length / 0.1));
        final double[] point = new double[5];
        for (int i = 0; i <= samples; i++) {
            pointAt(length * i / samples, point);
            bx0 = Math.min(bx0, point[0]);
            bx1 = Math.max(bx1, point[0]);
            by0 = Math.min(by0, point[1]);
            by1 = Math.max(by1, point[1]);
            bz0 = Math.min(bz0, point[2]);
            bz1 = Math.max(bz1, point[2]);
        }
        minX = (long) Math.floor(bx0);
        minY = (long) Math.floor(by0);
        minZ = (long) Math.floor(bz0);
        maxX = (long) Math.ceil(bx1);
        maxY = (long) Math.ceil(by1);
        maxZ = (long) Math.ceil(bz1);
    }

    /** @param mtrShape the rail's {@code Rail.Shape} name; @param mtrVerticalRadius the rail's vertical radius (both only matter in MTR height mode). */
    public static TrackPath build(TrackProfile profile, String mtrShape, double mtrVerticalRadius) {
        return new TrackPath(profile, mtrShape, mtrVerticalRadius);
    }

    // ------------------------------------------------------------------ closure

    public double length() {
        return length;
    }

    /** Distance between where the chain ends and node B's centre (blocks). */
    public double positionError() {
        return posErr;
    }

    /** Heading mismatch at B (radians, wrapped). */
    public double headingError() {
        return headErr;
    }

    /** True when the chain actually reaches B with B's heading — only such paths may be applied to a rail. */
    public boolean closes() {
        return posErr < 0.02 && headErr < 0.002;
    }

    public double endX() {
        return endX;
    }

    public double endZ() {
        return endZ;
    }

    public double endHeading() {
        return endHeading;
    }

    public int pieceCount() {
        return count;
    }

    public double pieceStart(int index) {
        return s0[index];
    }

    public double pieceLength(int index) {
        return len[index];
    }

    /** Index of the piece containing distance {@code s}. */
    public int pieceAt(double s) {
        int lo = 0;
        int hi = count - 1;
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (s0[mid] <= s) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Fills {@code out} with {x, y, z, heading, curvature} at distance {@code s} (clamped to the chain).
     * x/z are world coordinates (node centres at +0.5), y is the rail height like MTR's.
     */
    public void pointAt(double s, double[] out) {
        final double clamped = Math.max(0, Math.min(length, s));
        final int i = pieceAt(clamped);
        final double t = Math.max(0, Math.min(len[i], clamped - s0[i]));
        if (spline) {
            final double[] eval = new double[6];
            bezierEval(bez[i], paramAt(i, t), eval);
            out[0] = eval[0];
            out[1] = height(clamped);
            out[2] = eval[1];
            out[3] = Math.atan2(eval[3], eval[2]);
            out[4] = eval[5];
            return;
        }
        final double k = k0[i] + (len[i] > 0 ? (k1[i] - k0[i]) * t / len[i] : 0);
        final double heading;
        if (type[i] == PieceType.SPIRAL && tableX[i] != null) {
            heading = spiralHeading(th0[i], k0[i], k1[i], len[i], t);
            final double f = t / tableStep[i];
            final int j = Math.min(tableX[i].length - 2, (int) f);
            final double u = f - j;
            out[0] = tableX[i][j] + (tableX[i][j + 1] - tableX[i][j]) * u;
            out[2] = tableZ[i][j] + (tableZ[i][j + 1] - tableZ[i][j]) * u;
        } else {
            heading = th0[i] + k0[i] * t;
            // inline pieceEnd — this is the hot path (every vehicle, every tick)
            if (type[i] == PieceType.ARC && Math.abs(k0[i]) > 1e-12) {
                out[0] = x0[i] + (Math.sin(heading) - Math.sin(th0[i])) / k0[i];
                out[2] = z0[i] - (Math.cos(heading) - Math.cos(th0[i])) / k0[i];
            } else {
                out[0] = x0[i] + t * Math.cos(th0[i]);
                out[2] = z0[i] + t * Math.sin(th0[i]);
            }
        }
        out[1] = height(clamped);
        out[3] = heading;
        out[4] = k;
    }

    public double heading(double s) {
        if (spline) {
            final double[] point = new double[5];
            pointAt(s, point);
            return point[3];
        }
        final double clamped = Math.max(0, Math.min(length, s));
        final int i = pieceAt(clamped);
        final double t = Math.max(0, Math.min(len[i], clamped - s0[i]));
        return type[i] == PieceType.SPIRAL ? spiralHeading(th0[i], k0[i], k1[i], len[i], t) : th0[i] + k0[i] * t;
    }

    public double curvature(double s) {
        if (spline) {
            final double[] point = new double[5];
            pointAt(s, point);
            return point[4];
        }
        final double clamped = Math.max(0, Math.min(length, s));
        final int i = pieceAt(clamped);
        final double t = Math.max(0, Math.min(len[i], clamped - s0[i]));
        return k0[i] + (len[i] > 0 ? (k1[i] - k0[i]) * t / len[i] : 0);
    }

    /** Rail height (block y, like MTR) at distance s. */
    public double height(double s) {
        final double value = Math.max(0, Math.min(length, s));
        return customHeight ? customHeight(value) : mtrHeight(value);
    }

    /** dy/ds at distance s (numeric). */
    public double grade(double s) {
        final double h = 0.05;
        final double a = Math.max(0, s - h);
        final double b = Math.min(length, s + h);
        return b - a > 1e-9 ? (height(b) - height(a)) / (b - a) : 0;
    }

    public boolean hasTilt() {
        return hasTilt;
    }

    /** Tilt in degrees at distance s (positive = right side low, travelling A→B). */
    public double tilt(double s) {
        if (!hasTilt) {
            return 0;
        }
        final double value = Math.max(0, Math.min(length, s));
        int i = 0;
        while (i < tiltS.length - 2 && tiltS[i + 1] <= value) {
            i++;
        }
        final double span = tiltS[i + 1] - tiltS[i];
        double u = span > 1e-9 ? (value - tiltS[i]) / span : 0;
        u = Math.max(0, Math.min(1, u));
        if (smoothTilt) {
            u = (1 - Math.cos(Math.PI * u)) / 2;
        }
        return tiltD[i] + (tiltD[i + 1] - tiltD[i]) * u;
    }

    /**
     * Tilt as seen by something travelling the OTHER way (B→A) — its right is our left, so the sign
     * flips. Use when a vehicle/path walks the rail reversed.
     */
    public double tilt(double s, boolean reversed) {
        return reversed ? -tilt(length - s) : tilt(s);
    }

    /** Lateral offset (blocks, + = right of A→B travel) of the tilt pivot = the low rail. */
    public static double pivotOffset(double tiltDegrees) {
        return tiltDegrees > 0 ? TrackProfile.HALF_GAUGE : tiltDegrees < 0 ? -TrackProfile.HALF_GAUGE : 0;
    }

    /**
     * Banks a point given in the track's cross-section: {@code u} = lateral offset (+ right),
     * {@code h} = height above the rail top. Returns {u', h'} after rolling by {@code tiltDegrees}
     * about the low rail. Positive tilt lowers the right side and leans tall things to the right.
     */
    public static double[] bank(double u, double h, double tiltDegrees) {
        final double phi = Math.toRadians(tiltDegrees);
        final double pivot = pivotOffset(tiltDegrees);
        final double cos = Math.cos(phi);
        final double sin = Math.sin(phi);
        final double du = u - pivot;
        return new double[]{pivot + du * cos + h * sin, -du * sin + h * cos};
    }

    // ------------------------------------------------------------------ MTR facade

    /** Exactly {@code RailMath.getLength()}. */
    public double getLength() {
        return length;
    }

    /** Exactly {@code RailMath.getPosition(rawValue, reverse)} → {x, y, z}. */
    public double[] getPosition(double rawValue, boolean reverse) {
        final double clamped = Math.max(0, Math.min(length, rawValue));
        final double value = reverse ? length - clamped : clamped;
        final double[] point = new double[5];
        pointAt(value, point);
        return new double[]{point[0], point[1], point[2]};
    }

    /** For {@code RailMath.getHorizontalRadii()}: {smallest arc radius, largest arc radius}, 0 = none. */
    public double[] horizontalRadii() {
        if (spline) {
            return new double[]{splineMinRadius, splineMaxRadius};
        }
        double min = 0;
        double max = 0;
        for (final Piece piece : profile.pieces) {
            if (piece.type == PieceType.ARC && piece.length > 0) {
                min = min == 0 ? piece.radius : Math.min(min, piece.radius);
                max = Math.max(max, piece.radius);
            }
        }
        return new double[]{min, max};
    }

    /** MTR's own max-vertical-radius formula over our length. */
    public double getMaxVerticalRadius() {
        return maxVerticalRadius(length, yStart, yEnd);
    }

    public double getVerticalRadius() {
        return mtrVerticalRadius;
    }

    @FunctionalInterface
    public interface Segment {
        /** Same argument order and meaning as MTR's {@code RailMath.RenderRail#renderRail}. */
        void segment(double x1, double z1, double x2, double z2, double x3, double z3, double x4, double z4, double y1, double y2);
    }

    /**
     * Exactly the contract of {@code RailMath.render(callback, interval, offsetRadius1, offsetRadius2)}:
     * quads along the chain, corners at lateral offsets (here + = right of A→B travel; MTR's own
     * sign is "outward of the curve", which no MTR caller relies on — they pass symmetric offsets).
     */
    public void render(Segment callback, double interval, double offset1, double offset2) {
        forEachSegment(interval, (sStart, sEnd) -> {
        }, callback, offset1, offset2);
    }

    @FunctionalInterface
    public interface SegmentRange {
        void range(double sStart, double sEnd);
    }

    /**
     * {@link #render} that also reports each quad's distance range just BEFORE its callback — what a
     * tilt-aware renderer needs to look up {@link #tilt(double)} for that segment.
     */
    public void forEachSegment(double interval, SegmentRange range, Segment callback, double offset1, double offset2) {
        final int n = length < 0.5 || interval <= 0 ? Math.max(1, (int) Math.ceil(length / 0.5)) : Math.max(1, (int) Math.round(length / interval));
        final double step = length / n;
        final double[] point = new double[5];
        double p1x = 0, p1z = 0, p2x = 0, p2z = 0, py = 0;
        for (int i = 0; i <= n; i++) {
            final double s = i == n ? length : i * step;
            pointAt(s, point);
            final double rx = -Math.sin(point[3]);
            final double rz = Math.cos(point[3]);
            final double c1x = point[0] + rx * offset1;
            final double c1z = point[2] + rz * offset1;
            final double c2x = point[0] + rx * offset2;
            final double c2z = point[2] + rz * offset2;
            if (i > 0) {
                range.range((i - 1) * step, s);
                callback.segment(p1x, p1z, p2x, p2z, c2x, c2z, c1x, c1z, py, point[1]);
            }
            p1x = c1x;
            p1z = c1z;
            p2x = c2x;
            p2z = c2z;
            py = point[1];
        }
    }

    // ------------------------------------------------------------------ spline

    public boolean isSpline() {
        return spline;
    }

    /** SPLINE: distance of anchor {@code index} (0 = node A, last = node B = length). PIECES: start of piece {@code index}. */
    public double anchorS(int index) {
        return index >= count ? length : s0[Math.max(0, index)];
    }

    /** SPLINE: {segment index, Bézier parameter t} at distance s. */
    double[] splineParam(double s) {
        final double clamped = Math.max(0, Math.min(length, s));
        final int i = pieceAt(clamped);
        return new double[]{i, paramAt(i, Math.max(0, Math.min(len[i], clamped - s0[i])))};
    }

    /** SPLINE: control points {x0,z0,x1,z1,x2,z2,x3,z3} of segment i (a copy). */
    double[] controlPoints(int i) {
        return bez[i].clone();
    }

    private double paramAt(int i, double t) {
        final double[] cum = segCum[i];
        final int n = cum.length - 1;
        if (t <= 0) {
            return 0;
        }
        if (t >= cum[n]) {
            return 1;
        }
        int lo = 0;
        int hi = n;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (cum[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final double span = cum[hi] - cum[lo];
        return (lo + (span > 1e-12 ? (t - cum[lo]) / span : 0)) / n;
    }

    /** Fills {x, z, dx, dz, (unused), curvature} at parameter t. */
    static void bezierEval(double[] c, double t, double[] out) {
        final double u = 1 - t;
        final double b0 = u * u * u, b1 = 3 * u * u * t, b2 = 3 * u * t * t, b3 = t * t * t;
        out[0] = b0 * c[0] + b1 * c[2] + b2 * c[4] + b3 * c[6];
        out[1] = b0 * c[1] + b1 * c[3] + b2 * c[5] + b3 * c[7];
        final double dx = 3 * (u * u * (c[2] - c[0]) + 2 * u * t * (c[4] - c[2]) + t * t * (c[6] - c[4]));
        final double dz = 3 * (u * u * (c[3] - c[1]) + 2 * u * t * (c[5] - c[3]) + t * t * (c[7] - c[5]));
        final double ddx = 6 * (u * (c[4] - 2 * c[2] + c[0]) + t * (c[6] - 2 * c[4] + c[2]));
        final double ddz = 6 * (u * (c[5] - 2 * c[3] + c[1]) + t * (c[7] - 2 * c[5] + c[3]));
        out[2] = dx;
        out[3] = dz;
        final double speed = Math.hypot(dx, dz);
        out[4] = speed;
        out[5] = speed > 1e-12 ? (dx * ddz - dz * ddx) / (speed * speed * speed) : 0;
    }

    /** ∫ |B'(t)| dt over [a, b] (5-point Gauss–Legendre). */
    private static double speedIntegral(double[] c, double a, double b) {
        final double[] nodes = {0, -0.5384693101056831, 0.5384693101056831, -0.9061798459386640, 0.9061798459386640};
        final double[] weights = {0.5688888888888889, 0.4786286704993665, 0.4786286704993665, 0.2369268850561891, 0.2369268850561891};
        final double half = (b - a) / 2;
        final double mid = (a + b) / 2;
        final double[] eval = new double[6];
        double sum = 0;
        for (int k = 0; k < 5; k++) {
            bezierEval(c, mid + half * nodes[k], eval);
            sum += weights[k] * eval[4];
        }
        return sum * half;
    }

    // ------------------------------------------------------------------ helpers

    /** Start/end curvature of every piece; spirals blend from the previous piece to the next non-spiral piece. */
    static void curvatures(List<Piece> pieces, double[] k0, double[] k1) {
        final int n = pieces.size();
        double previous = 0;
        for (int i = 0; i < n; i++) {
            final Piece piece = pieces.get(i);
            if (piece.type == PieceType.SPIRAL) {
                double next = 0;
                for (int j = i + 1; j < n; j++) {
                    if (pieces.get(j).type != PieceType.SPIRAL) {
                        next = pieces.get(j).curvature();
                        break;
                    }
                }
                k0[i] = previous;
                k1[i] = next;
            } else {
                k0[i] = k1[i] = piece.curvature();
            }
            previous = k1[i];
        }
    }

    static double spiralHeading(double theta0, double kStart, double kEnd, double length, double t) {
        return theta0 + kStart * t + (length > 0 ? (kEnd - kStart) * t * t / (2 * length) : 0);
    }

    /** End point of a straight / arc of length t (spirals are handled by the caller). */
    static double[] pieceEnd(PieceType type, double x, double z, double theta, double k, double t) {
        if (type == PieceType.ARC && Math.abs(k) > 1e-12) {
            return new double[]{x + (Math.sin(theta + k * t) - Math.sin(theta)) / k, z - (Math.cos(theta + k * t) - Math.cos(theta)) / k};
        }
        return new double[]{x + t * Math.cos(theta), z + t * Math.sin(theta)};
    }

    public static double wrap(double angle) {
        double a = angle % (2 * Math.PI);
        if (a > Math.PI) {
            a -= 2 * Math.PI;
        } else if (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    static double maxVerticalRadius(double length, double yStart, double yEnd) {
        final double height = yEnd - yStart;
        return Math.floor((length * length + height * height) * 100.0 / Math.abs(4.0 * height)) / 100.0;
    }

    private double customHeight(double s) {
        int i = 0;
        while (i < gradeS.length - 2 && gradeS[i + 1] <= s) {
            i++;
        }
        // inside the vertical curve of knot i+1 (before it) or knot i (after it)?
        final int next = i + 1;
        if (next < gradeS.length - 1 && gradeHalf[next] > 0 && s > gradeS[next] - gradeHalf[next]) {
            return curveY(next, s);
        }
        if (i > 0 && gradeHalf[i] > 0 && s < gradeS[i] + gradeHalf[i]) {
            return curveY(i, s);
        }
        return gradeY[i] + gradeSlope[i] * (s - gradeS[i]);
    }

    private double curveY(int knot, double s) {
        final double h = gradeHalf[knot];
        final double before = gradeSlope[knot - 1];
        final double after = gradeSlope[knot];
        final double d = s - gradeS[knot] + h;
        return gradeY[knot] + before * (s - gradeS[knot]) + (after - before) * d * d / (4 * h);
    }

    /** Verbatim port of MTR 4.0.1 {@code RailMath.getPositionY} with our length. */
    private double mtrHeight(double value) {
        if (yStart == yEnd) {
            return yStart;
        }
        switch (mtrShape) {
            case "TWO_RADII": {
                if (mtrVerticalRadius <= 0) {
                    return value / length * (yEnd - yStart) + yStart;
                }
                final double height = Math.abs(yEnd - yStart);
                final double vTheta = 2.0 * Math.atan2(Math.sqrt(height * height - 4.0 * mtrVerticalRadius * height + length * length) - length, height - 4.0 * mtrVerticalRadius);
                final double curveLength = Math.sin(vTheta) * mtrVerticalRadius;
                final double curveHeight = (1.0 - Math.cos(vTheta)) * mtrVerticalRadius;
                final int sign = yStart < yEnd ? 1 : -1;
                if (value < curveLength) {
                    return sign * (mtrVerticalRadius - Math.sqrt(mtrVerticalRadius * mtrVerticalRadius - value * value)) + yStart;
                } else if (value > length - curveLength) {
                    final double r = length - value;
                    return -sign * (mtrVerticalRadius - Math.sqrt(mtrVerticalRadius * mtrVerticalRadius - r * r)) + yEnd;
                }
                return sign * ((value - curveLength) / (length - 2.0 * curveLength) * (Math.abs(yEnd - yStart) - 2.0 * curveHeight) + curveHeight) + yStart;
            }
            case "CABLE": {
                if (value < 0.5) {
                    return yStart;
                } else if (value > length - 0.5) {
                    return yEnd;
                }
                final double cableOffsetValue = value - 0.5;
                final double offsetLength = length - 1.0;
                final double posY = yStart + (yEnd - yStart) * cableOffsetValue / offsetLength;
                final double dip = offsetLength * offsetLength / 4.0 / 1000.0;
                return posY + (dip > 8.0 ? 8.0 / dip : 1.0) * (cableOffsetValue - offsetLength) * cableOffsetValue / 1000.0;
            }
            default: {
                final double intercept = length / 2.0;
                final double yChange;
                final double yInitial;
                final double offsetValue;
                if (value < intercept) {
                    yChange = (yEnd - yStart) / 2.0;
                    yInitial = yStart;
                    offsetValue = value;
                } else {
                    yChange = (yStart - yEnd) / 2.0;
                    yInitial = yEnd;
                    offsetValue = length - value;
                }
                return yChange * offsetValue * offsetValue / (intercept * intercept) + yInitial;
            }
        }
    }
}
