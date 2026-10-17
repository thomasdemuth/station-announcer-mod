package com.stationannouncer.track;

import com.stationannouncer.track.TrackProfile.Anchor;
import com.stationannouncer.track.TrackProfile.GradePoint;
import com.stationannouncer.track.TrackProfile.TiltPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Bézier spline geometry operations (round 2) — see {@link TrackEdit} for the public contract.
 *
 * Circle-exact handles: a cubic whose two handles have length {@code L·(4/3)·tan(Δθ/4)/Δθ} (L = arc
 * length, Δθ = turned angle) reproduces a circular arc to ~1e-6·R for Δθ ≤ 45°; Δθ → 0 gives the
 * familiar L/3 of a straight line. That is what makes {@link #toSpline} and {@link #parallel} near-exact.
 */
final class TrackSpline {
    private static final ThreadLocal<Integer> LAST_INSERTED = ThreadLocal.withInitial(() -> -1);
    /** Spans are subdivided so none turns more than this (radians) — keeps circle-exact handles accurate. */
    private static final double MAX_SPAN_TURN = Math.toRadians(30);
    private static final double MAX_SPAN_LENGTH = 24;
    private static final double MIN_HANDLE = 0.05;
    private static final double GRAVITY_CHECK = 1e-9;

    private TrackSpline() {
    }

    static int lastInserted() {
        return LAST_INSERTED.get();
    }

    static double handleFor(double arcLength, double turn) {
        final double a = Math.abs(turn);
        if (a < 1e-6) {
            return Math.max(MIN_HANDLE, arcLength / 3);
        }
        return Math.max(MIN_HANDLE, arcLength * (4.0 / 3.0) * Math.tan(a / 4) / a);
    }

    /** Sample a path's heading change between two distances by integrating curvature (unwrapped). */
    private static double turnBetween(TrackPath path, double sa, double sb) {
        final int n = Math.max(4, (int) Math.ceil((sb - sa) / 0.25));
        double turn = 0;
        double previous = path.heading(sa);
        for (int i = 1; i <= n; i++) {
            final double h = path.heading(sa + (sb - sa) * i / n);
            turn += TrackPath.wrap(h - previous);
            previous = h;
        }
        return turn;
    }

    /** Distances at which to place anchors: every piece/segment boundary, subdivided so spans stay short and gentle. */
    private static List<Double> spanBreaks(TrackPath path, List<Double> mustInclude) {
        final List<Double> base = new ArrayList<>();
        base.add(0.0);
        for (final double s : mustInclude) {
            if (s > 1e-3 && s < path.length() - 1e-3) {
                base.add(s);
            }
        }
        base.add(path.length());
        base.sort(Double::compare);
        final List<Double> breaks = new ArrayList<>();
        for (int i = 0; i + 1 < base.size(); i++) {
            final double a = base.get(i);
            final double b = base.get(i + 1);
            if (b - a < 1e-3) {
                continue;
            }
            final double turn = Math.abs(turnBetween(path, a, b));
            final int parts = Math.max(1, Math.max((int) Math.ceil(turn / MAX_SPAN_TURN), (int) Math.ceil((b - a) / MAX_SPAN_LENGTH)));
            for (int k = 0; k < parts; k++) {
                breaks.add(a + (b - a) * k / parts);
            }
        }
        breaks.add(path.length());
        return breaks;
    }

    /** Anchors that follow a sampled curve: positions/headings from {@code curve}, spans between {@code breaks}. */
    private static List<Anchor> fitAnchors(List<Double> breaks, Curve curve) {
        final List<Anchor> anchors = new ArrayList<>();
        final double[] p = new double[3];
        for (final double s : breaks) {
            curve.at(s, p);
            anchors.add(new Anchor(p[0], p[1], p[2], MIN_HANDLE, MIN_HANDLE));
        }
        for (int i = 0; i + 1 < breaks.size(); i++) {
            final double a = breaks.get(i);
            final double b = breaks.get(i + 1);
            final double arc = curve.length(a, b);
            final double turn = curve.turn(a, b);
            final double handle = handleFor(arc, turn);
            anchors.get(i).handleOut = handle;
            anchors.get(i + 1).handleIn = handle;
        }
        return anchors;
    }

    private interface Curve {
        /** {x, z, heading} at s. */
        void at(double s, double[] out);

        double length(double a, double b);

        double turn(double a, double b);
    }

    private static Curve curveOf(TrackPath path) {
        return new Curve() {
            final double[] point = new double[5];

            @Override
            public void at(double s, double[] out) {
                path.pointAt(s, point);
                out[0] = point[0];
                out[1] = point[2];
                out[2] = point[3];
            }

            @Override
            public double length(double a, double b) {
                return b - a;
            }

            @Override
            public double turn(double a, double b) {
                return turnBetween(path, a, b);
            }
        };
    }

    static TrackProfile toSpline(TrackProfile profile, TrackPath path) {
        if (profile.geometry == TrackProfile.Geometry.SPLINE) {
            return profile.copy();
        }
        final List<Double> boundaries = new ArrayList<>();
        for (int i = 1; i < path.pieceCount(); i++) {
            boundaries.add(path.pieceStart(i));
        }
        final TrackProfile out = profile.copy();
        out.geometry = TrackProfile.Geometry.SPLINE;
        out.anchors = fitAnchors(spanBreaks(path, boundaries), curveOf(path));
        pinEnds(out);
        return out;
    }

    static void pinEnds(TrackProfile profile) {
        final Anchor first = profile.anchors.get(0);
        first.x = profile.ax + 0.5;
        first.z = profile.az + 0.5;
        first.heading = profile.headingA;
        final Anchor last = profile.anchors.get(profile.anchors.size() - 1);
        last.x = profile.bx + 0.5;
        last.z = profile.bz + 0.5;
        last.heading = profile.headingB;
    }

    static TrackProfile insertAnchor(TrackProfile spline, TrackPath path, double s) {
        LAST_INSERTED.set(-1);
        if (spline.geometry != TrackProfile.Geometry.SPLINE || !path.isSpline() || spline.anchors.size() >= TrackProfile.MAX_ANCHORS) {
            return spline.copy();
        }
        final double[] param = path.splineParam(s);
        final int i = (int) param[0];
        final double t = param[1];
        if (t < 0.01 || t > 0.99) {
            return spline.copy();
        }
        final double[] c = path.controlPoints(i);
        // de Casteljau
        final double x01 = lerp(c[0], c[2], t), z01 = lerp(c[1], c[3], t);
        final double x12 = lerp(c[2], c[4], t), z12 = lerp(c[3], c[5], t);
        final double x23 = lerp(c[4], c[6], t), z23 = lerp(c[5], c[7], t);
        final double x012 = lerp(x01, x12, t), z012 = lerp(z01, z12, t);
        final double x123 = lerp(x12, x23, t), z123 = lerp(z12, z23, t);
        final double x = lerp(x012, x123, t), z = lerp(z012, z123, t);
        final TrackProfile out = spline.copy();
        final Anchor before = out.anchors.get(i);
        final Anchor after = out.anchors.get(i + 1);
        before.handleOut = Math.max(MIN_HANDLE, Math.hypot(x01 - c[0], z01 - c[1]));
        after.handleIn = Math.max(MIN_HANDLE, Math.hypot(c[6] - x23, c[7] - z23));
        final double heading = Math.atan2(z123 - z012, x123 - x012);
        out.anchors.add(i + 1, new Anchor(x, z, heading, Math.max(MIN_HANDLE, Math.hypot(x - x012, z - z012)), Math.max(MIN_HANDLE, Math.hypot(x123 - x, z123 - z))));
        LAST_INSERTED.set(i + 1);
        return out;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static TrackProfile removeAnchor(TrackProfile spline, TrackPath path, int index) {
        final TrackProfile out = spline.copy();
        if (spline.geometry != TrackProfile.Geometry.SPLINE || index <= 0 || index >= out.anchors.size() - 1) {
            return out;
        }
        final double sa = path.anchorS(index - 1);
        final double sb = path.anchorS(index + 1);
        out.anchors.remove(index);
        final double handle = handleFor(sb - sa, turnBetween(path, sa, sb));
        out.anchors.get(index - 1).handleOut = handle;
        out.anchors.get(index).handleIn = handle;
        return out;
    }

    static TrackProfile moveAnchor(TrackProfile spline, int index, double x, double z) {
        final TrackProfile out = spline.copy();
        if (index > 0 && index < out.anchors.size() - 1 && Double.isFinite(x) && Double.isFinite(z)) {
            out.anchors.get(index).x = x;
            out.anchors.get(index).z = z;
        }
        return out;
    }

    static TrackProfile setHandle(TrackProfile spline, int index, boolean out, double x, double z) {
        final TrackProfile result = spline.copy();
        if (index < 0 || index >= result.anchors.size() || !Double.isFinite(x) || !Double.isFinite(z)) {
            return result;
        }
        final Anchor anchor = result.anchors.get(index);
        final double vx = x - anchor.x;
        final double vz = z - anchor.z;
        final boolean end = index == 0 || index == result.anchors.size() - 1;
        if (end) {
            final double along = (vx * Math.cos(anchor.heading) + vz * Math.sin(anchor.heading)) * (out ? 1 : -1);
            final double length = Math.max(MIN_HANDLE, along);
            if (out) {
                anchor.handleOut = length;
            } else {
                anchor.handleIn = length;
            }
            return result;
        }
        final double length = Math.max(MIN_HANDLE, Math.hypot(vx, vz));
        if (Math.hypot(vx, vz) < 1e-6) {
            return result;
        }
        anchor.heading = out ? Math.atan2(vz, vx) : Math.atan2(-vz, -vx);
        if (out) {
            anchor.handleOut = length;
        } else {
            anchor.handleIn = length;
        }
        return result;
    }

    static TrackProfile parallel(TrackProfile companion, TrackPath source, double lateral, boolean reversed) {
        final double length = source.length();
        // the offset curve, in the SOURCE's direction
        final Curve offset = new Curve() {
            final double[] point = new double[5];

            @Override
            public void at(double s, double[] out) {
                source.pointAt(s, point);
                out[0] = point[0] - Math.sin(point[3]) * lateral;
                out[1] = point[2] + Math.cos(point[3]) * lateral;
                out[2] = point[3];
            }

            @Override
            public double length(double a, double b) {
                final int n = Math.max(8, (int) Math.ceil((b - a) / 0.1));
                final double[] p = new double[3];
                at(a, p);
                double px = p[0], pz = p[1], sum = 0;
                for (int i = 1; i <= n; i++) {
                    at(a + (b - a) * i / n, p);
                    sum += Math.hypot(p[0] - px, p[1] - pz);
                    px = p[0];
                    pz = p[1];
                }
                return sum;
            }

            @Override
            public double turn(double a, double b) {
                return turnBetween(source, a, b);
            }
        };
        // where the offset curve ends must be the companion's nodes
        final double[] start = new double[3];
        final double[] end = new double[3];
        offset.at(0, start);
        offset.at(length, end);
        final double cAx = companion.ax + 0.5, cAz = companion.az + 0.5, cBx = companion.bx + 0.5, cBz = companion.bz + 0.5;
        final double errStart = reversed ? Math.hypot(start[0] - cBx, start[1] - cBz) : Math.hypot(start[0] - cAx, start[1] - cAz);
        final double errEnd = reversed ? Math.hypot(end[0] - cAx, end[1] - cAz) : Math.hypot(end[0] - cBx, end[1] - cBz);
        if (errStart > 0.3 || errEnd > 0.3) {
            return null;
        }
        final List<Double> boundaries = new ArrayList<>();
        for (int i = 1; i < source.pieceCount(); i++) {
            boundaries.add(source.pieceStart(i));
        }
        final List<Double> breaks = spanBreaks(source, boundaries);
        List<Anchor> anchors = fitAnchors(breaks, offset);
        // distance map source s → companion s (offset arc length), for height/tilt points
        final double[] mapS = new double[breaks.size()];
        for (int i = 1; i < breaks.size(); i++) {
            mapS[i] = mapS[i - 1] + offset.length(breaks.get(i - 1), breaks.get(i));
        }
        final double total = mapS[mapS.length - 1];
        if (reversed) {
            final List<Anchor> flipped = new ArrayList<>();
            for (int i = anchors.size() - 1; i >= 0; i--) {
                final Anchor a = anchors.get(i);
                flipped.add(new Anchor(a.x, a.z, a.heading + Math.PI, a.handleOut, a.handleIn));
            }
            anchors = flipped;
        }
        final TrackProfile out = companion.copy();
        out.geometry = TrackProfile.Geometry.SPLINE;
        out.pieces.clear();
        out.anchors = anchors;
        pinEnds(out);
        // height: same grades, mapped onto the companion's distances, shifted by its end-height difference
        final TrackProfile src = source.profile;
        out.vertical = src.vertical;
        out.grades.clear();
        if (src.vertical == TrackProfile.VerticalMode.CUSTOM) {
            for (final GradePoint point : src.grades) {
                final double mapped = map(point.s, breaks, mapS);
                final double u = total > 0 ? mapped / total : 0;
                final double sourceBase = src.ay + (src.by - src.ay) * (point.s / Math.max(1e-9, length));
                final double companionBase = reversed ? companion.by + (companion.ay - companion.by) * u : companion.ay + (companion.by - companion.ay) * u;
                out.grades.add(new GradePoint(reversed ? total - mapped : mapped, point.y - sourceBase + companionBase, point.curve));
            }
            out.grades.sort((a, b) -> Double.compare(a.s, b.s));
        }
        // tilt: same world lean; reversed tracks see it with the opposite sign
        out.smoothTilt = src.smoothTilt;
        out.cantCap = src.cantCap;
        out.tilts.clear();
        for (final TiltPoint point : src.tilts) {
            final double mapped = map(point.s, breaks, mapS);
            out.tilts.add(new TiltPoint(reversed ? total - mapped : mapped, reversed ? -point.degrees : point.degrees));
        }
        out.tilts.sort((a, b) -> Double.compare(a.s, b.s));
        out.tiltA = reversed ? -src.tiltB : src.tiltA;
        out.tiltB = reversed ? -src.tiltA : src.tiltB;
        return out;
    }

    private static double map(double s, List<Double> breaks, double[] mapS) {
        for (int i = 0; i + 1 < breaks.size(); i++) {
            final double a = breaks.get(i);
            final double b = breaks.get(i + 1);
            if (s <= b || i + 2 == breaks.size()) {
                final double u = b - a > 1e-9 ? Math.max(0, Math.min(1, (s - a) / (b - a))) : 0;
                return mapS[i] + (mapS[i + 1] - mapS[i]) * u;
            }
        }
        return 0;
    }

    static TrackProfile autoTilt(TrackProfile profile, TrackPath path, double speedKmh) {
        if (profile.geometry != TrackProfile.Geometry.SPLINE) {
            return TrackEdit.suggestCant(profile, path, speedKmh, 10);
        }
        final double length = path.length();
        final int n = Math.max(2, (int) Math.ceil(length / 0.5));
        final double step = length / n;
        final double[] raw = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            final double k = path.curvature(i * step);
            raw[i] = Math.abs(k) < GRAVITY_CHECK ? 0 : Math.signum(k) * Math.min(profile.cantCap, TrackEdit.equilibriumCant(1 / Math.abs(k), speedKmh));
        }
        // ramp: moving average over ~8 blocks (cant runs in/out gradually where curvature changes)
        final int window = Math.max(1, (int) Math.round(4 / step));
        final double[] smooth = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - window); j <= Math.min(n, i + window); j++) {
                sum += raw[j];
                count++;
            }
            smooth[i] = sum / count;
        }
        // simplify to ≤ 60 interior points (Douglas–Peucker, growing tolerance)
        final TrackProfile out = profile.copy();
        out.smoothTilt = false;
        out.tiltA = TrackProfile.clampTilt(smooth[0]);
        out.tiltB = TrackProfile.clampTilt(smooth[n]);
        List<Integer> keep = null;
        for (double tolerance = 0.1; tolerance < 50; tolerance *= 1.6) {
            keep = simplify(smooth, tolerance);
            if (keep.size() - 2 <= TrackProfile.MAX_TILT_POINTS - 4) {
                break;
            }
        }
        out.tilts.clear();
        for (final int i : keep) {
            if (i > 0 && i < n) {
                out.tilts.add(new TiltPoint(i * step, TrackProfile.clampTilt(smooth[i])));
            }
        }
        return out;
    }

    private static List<Integer> simplify(double[] values, double tolerance) {
        final boolean[] keep = new boolean[values.length];
        keep[0] = true;
        keep[values.length - 1] = true;
        simplify(values, 0, values.length - 1, tolerance, keep);
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (keep[i]) {
                out.add(i);
            }
        }
        return out;
    }

    private static void simplify(double[] values, int a, int b, double tolerance, boolean[] keep) {
        if (b - a < 2) {
            return;
        }
        int worst = -1;
        double worstErr = tolerance;
        for (int i = a + 1; i < b; i++) {
            final double expected = values[a] + (values[b] - values[a]) * (i - a) / (double) (b - a);
            final double err = Math.abs(values[i] - expected);
            if (err > worstErr) {
                worstErr = err;
                worst = i;
            }
        }
        if (worst >= 0) {
            keep[worst] = true;
            simplify(values, a, worst, tolerance, keep);
            simplify(values, worst, b, tolerance, keep);
        }
    }
}
