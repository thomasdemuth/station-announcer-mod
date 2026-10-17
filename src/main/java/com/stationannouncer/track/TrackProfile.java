package com.stationannouncer.track;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom shape for ONE MTR rail: a free chain of pieces (straights, circular arcs, transition
 * spirals) between the rail's two nodes, an optional custom height profile, and tilt (cant).
 *
 * PURE JAVA on purpose (only Gson touches it) — no Minecraft, Fabric or MTR type may appear in
 * this package, so the geometry is unit-tested without a world (tools/track_test).
 *
 * <h2>Conventions (shared by every caller — read before using)</h2>
 * <ul>
 *   <li>A = the rail's canonical first node = the {@code position1} MTR passes to {@code RailMath}
 *       (Rail sorts its positions with {@code Position.compareTo}: x, then y, then z — see
 *       {@link TrackKey#canonical}). The chain always runs A → B.</li>
 *   <li>World x/z of the chain start = node block centre ({@code ax + 0.5, az + 0.5}), exactly
 *       like MTR's {@code RailMath.getPositionXZ}. Heights are block y (no +0.5), like MTR.</li>
 *   <li>Heading θ = {@code atan2(dz, dx)} in radians. Curvature κ = dθ/ds. In Minecraft (x east,
 *       z south) θ increasing is a CLOCKWISE turn seen from above = a RIGHT-hand curve, so
 *       {@code turn = +1} is a right-hand curve, {@code -1} a left-hand curve.</li>
 *   <li>"Right" of travel A→B is the vector {@code (-sin θ, cos θ)}.</li>
 *   <li>Tilt degrees: positive = banked to the RIGHT (right rail low), negative = to the left.
 *       The pivot is the LOW (inner) rail, {@link #HALF_GAUGE} from the centre line — so a
 *       right-hand curve wants positive tilt. See {@link TrackPath#bank}.</li>
 * </ul>
 */
public final class TrackProfile {
    public static final int FORMAT = 1;
    public static final double MAX_TILT_DEGREES = 45;
    public static final double MIN_RADIUS = 0.5;
    public static final double MIN_LENGTH = 0.0;
    /** Half the track gauge in blocks (standard gauge 1.435 m / 2) — the tilt pivot's offset. */
    public static final double HALF_GAUGE = 0.7175;
    public static final int MAX_PIECES = 32;
    public static final int MAX_GRADE_POINTS = 32;
    public static final int MAX_TILT_POINTS = 64;
    public static final double MAX_CHAIN_LENGTH = 4096;

    public enum PieceType { STRAIGHT, ARC, SPIRAL }

    /**
     * How the plan shape is described. PIECES = the {@link #pieces} chain (arcs/straights/spirals with
     * locks — the "Advanced" model). SPLINE = {@link #anchors}: a smooth cubic Bézier spline (the
     * default editing model since round 2) — always meets both nodes by construction.
     */
    public enum Geometry { PIECES, SPLINE }

    /**
     * A Bézier anchor (SPLINE geometry). World x/z (node centres at +0.5, like everything else),
     * the tangent heading θ (same convention as headings), and the two handle LENGTHS along it:
     * the segment before this anchor uses {@code anchor − handleIn·dir}, the one after
     * {@code anchor + handleOut·dir}. Handles are collinear (G1-smooth). The first anchor is node A
     * (heading = headingA, handleIn unused), the last is node B (heading = headingB, handleOut unused).
     */
    public static final class Anchor {
        public double x;
        public double z;
        public double heading;
        public double handleIn = 1;
        public double handleOut = 1;

        public Anchor() {
        }

        public Anchor(double x, double z, double heading, double handleIn, double handleOut) {
            this.x = x;
            this.z = z;
            this.heading = heading;
            this.handleIn = handleIn;
            this.handleOut = handleOut;
        }

        public Anchor copy() {
            return new Anchor(x, z, heading, handleIn, handleOut);
        }
    }

    /** MTR = height follows MTR's own rule for the rail's shape (with our length); CUSTOM = grade points. */
    public enum VerticalMode { MTR, CUSTOM }

    public static final class Piece {
        public PieceType type = PieceType.STRAIGHT;
        /** Length along the track, blocks. */
        public double length;
        /** ARC only. */
        public double radius = 20;
        /** ARC only: +1 right-hand, -1 left-hand. */
        public int turn = 1;
        public boolean lockLength;
        public boolean lockRadius;

        public Piece() {
        }

        public static Piece straight(double length) {
            final Piece piece = new Piece();
            piece.type = PieceType.STRAIGHT;
            piece.length = length;
            return piece;
        }

        public static Piece arc(double radius, double length, int turn) {
            final Piece piece = new Piece();
            piece.type = PieceType.ARC;
            piece.radius = radius;
            piece.length = length;
            piece.turn = turn >= 0 ? 1 : -1;
            return piece;
        }

        public static Piece spiral(double length) {
            final Piece piece = new Piece();
            piece.type = PieceType.SPIRAL;
            piece.length = length;
            return piece;
        }

        public Piece copy() {
            final Piece piece = new Piece();
            piece.type = type;
            piece.length = length;
            piece.radius = radius;
            piece.turn = turn;
            piece.lockLength = lockLength;
            piece.lockRadius = lockRadius;
            return piece;
        }

        /** Signed curvature of an ARC (0 for straights; spirals take theirs from neighbours). */
        public double curvature() {
            return type == PieceType.ARC ? turn / Math.max(MIN_RADIUS, radius) : 0;
        }

        /** Turned angle in radians (signed) for ARCs. */
        public double sweep() {
            return curvature() * length;
        }
    }

    /** A break in the grade at distance {@code s} (from A) and absolute height {@code y}; {@code curve} = vertical-curve length. */
    public static final class GradePoint {
        public double s;
        public double y;
        public double curve;

        public GradePoint() {
        }

        public GradePoint(double s, double y, double curve) {
            this.s = s;
            this.y = y;
            this.curve = curve;
        }

        public GradePoint copy() {
            return new GradePoint(s, y, curve);
        }
    }

    /** Tilt {@code degrees} at distance {@code s} from A. */
    public static final class TiltPoint {
        public double s;
        public double degrees;

        public TiltPoint() {
        }

        public TiltPoint(double s, double degrees) {
            this.s = s;
            this.degrees = degrees;
        }

        public TiltPoint copy() {
            return new TiltPoint(s, degrees);
        }
    }

    public int format = FORMAT;
    /** Node A / B block positions (canonical order, see class doc). */
    public long ax, ay, az, bx, by, bz;
    /** MTR {@code Angle.angleDegrees} at A / B as given to RailMath — the profile only applies while these match. */
    public double angleA, angleB;
    /** Travel heading (radians) leaving A and arriving at B. Fixed by the nodes. */
    public double headingA, headingB;

    public Geometry geometry = Geometry.PIECES;
    public List<Piece> pieces = new ArrayList<>();
    /** SPLINE geometry: ≥ 2 anchors, first = node A, last = node B. */
    public List<Anchor> anchors = new ArrayList<>();
    public static final int MAX_ANCHORS = 48;

    public VerticalMode vertical = VerticalMode.MTR;
    /** Interior grade points (CUSTOM mode). The ends are always (0, ay) and (length, by). */
    public List<GradePoint> grades = new ArrayList<>();

    /** Tilt at A and at B (degrees) — the node panel edits these so tilt can match across a node. */
    public double tiltA, tiltB;
    /** Interior tilt points. */
    public List<TiltPoint> tilts = new ArrayList<>();
    /** true = eased (cosine) between tilt points, false = straight-line ramps. */
    public boolean smoothTilt = true;
    /** Upper bound the "suggest cant" button uses (degrees). */
    public double cantCap = 12;

    public TrackProfile() {
    }

    public String key() {
        return TrackKey.of(ax, ay, az, bx, by, bz);
    }

    /** Chain length (PIECES) or, for SPLINE, the control-polygon length (an upper bound of the curve length). */
    public double totalLength() {
        double length = 0;
        if (geometry == Geometry.SPLINE) {
            for (int i = 0; i + 1 < anchors.size(); i++) {
                final Anchor a = anchors.get(i);
                final Anchor b = anchors.get(i + 1);
                final double x1 = a.x + Math.cos(a.heading) * a.handleOut;
                final double z1 = a.z + Math.sin(a.heading) * a.handleOut;
                final double x2 = b.x - Math.cos(b.heading) * b.handleIn;
                final double z2 = b.z - Math.sin(b.heading) * b.handleIn;
                length += Math.hypot(x1 - a.x, z1 - a.z) + Math.hypot(x2 - x1, z2 - z1) + Math.hypot(b.x - x2, b.z - z2);
            }
            return length;
        }
        for (final Piece piece : pieces) {
            length += Math.max(0, piece.length);
        }
        return length;
    }

    public boolean hasTilt() {
        if (Math.abs(tiltA) > 1e-6 || Math.abs(tiltB) > 1e-6) {
            return true;
        }
        for (final TiltPoint point : tilts) {
            if (Math.abs(point.degrees) > 1e-6) {
                return true;
            }
        }
        return false;
    }

    public TrackProfile copy() {
        final TrackProfile profile = new TrackProfile();
        profile.format = format;
        profile.ax = ax;
        profile.ay = ay;
        profile.az = az;
        profile.bx = bx;
        profile.by = by;
        profile.bz = bz;
        profile.angleA = angleA;
        profile.angleB = angleB;
        profile.headingA = headingA;
        profile.headingB = headingB;
        profile.geometry = geometry;
        pieces.forEach(piece -> profile.pieces.add(piece.copy()));
        anchors.forEach(anchor -> profile.anchors.add(anchor.copy()));
        profile.vertical = vertical;
        grades.forEach(point -> profile.grades.add(point.copy()));
        profile.tiltA = tiltA;
        profile.tiltB = tiltB;
        tilts.forEach(point -> profile.tilts.add(point.copy()));
        profile.smoothTilt = smoothTilt;
        profile.cantCap = cantCap;
        return profile;
    }

    /**
     * Clamps every value into its legal range and drops garbage (null lists from old JSON,
     * NaNs, too many points). Returns null if the profile is unusable. Call on anything read
     * from disk or the network.
     */
    public TrackProfile sanitized() {
        final TrackProfile profile = copy0();
        if (profile.geometry == null) {
            profile.geometry = Geometry.PIECES;
        }
        if (!Double.isFinite(profile.headingA) || !Double.isFinite(profile.headingB)) {
            return null;
        }
        if (profile.geometry == Geometry.SPLINE) {
            if (profile.anchors.size() < 2 || profile.anchors.size() > MAX_ANCHORS) {
                return null;
            }
            for (final Anchor anchor : profile.anchors) {
                if (!Double.isFinite(anchor.x) || !Double.isFinite(anchor.z) || !Double.isFinite(anchor.heading)
                    || !Double.isFinite(anchor.handleIn) || !Double.isFinite(anchor.handleOut)) {
                    return null;
                }
                anchor.handleIn = Math.max(0.05, Math.min(MAX_CHAIN_LENGTH, anchor.handleIn));
                anchor.handleOut = Math.max(0.05, Math.min(MAX_CHAIN_LENGTH, anchor.handleOut));
            }
            // the ends ARE the nodes, with the nodes' headings
            final Anchor first = profile.anchors.get(0);
            first.x = profile.ax + 0.5;
            first.z = profile.az + 0.5;
            first.heading = profile.headingA;
            final Anchor last = profile.anchors.get(profile.anchors.size() - 1);
            last.x = profile.bx + 0.5;
            last.z = profile.bz + 0.5;
            last.heading = profile.headingB;
            if (profile.pieces.size() > MAX_PIECES) {
                profile.pieces.clear();
            }
        } else if (profile.pieces.isEmpty() || profile.pieces.size() > MAX_PIECES) {
            return null;
        }
        for (final Piece piece : profile.pieces) {
            if (piece.type == null || !Double.isFinite(piece.length) || !Double.isFinite(piece.radius)) {
                if (profile.geometry == Geometry.SPLINE) {
                    profile.pieces.clear();
                    break;
                }
                return null;
            }
            piece.length = Math.max(MIN_LENGTH, Math.min(MAX_CHAIN_LENGTH, piece.length));
            piece.radius = Math.max(MIN_RADIUS, Math.min(100000, piece.radius));
            piece.turn = piece.turn >= 0 ? 1 : -1;
        }
        if (profile.totalLength() <= 0.01 || profile.totalLength() > MAX_CHAIN_LENGTH) {
            return null;
        }
        if (!Double.isFinite(profile.headingA) || !Double.isFinite(profile.headingB)) {
            return null;
        }
        if (profile.vertical == null) {
            profile.vertical = VerticalMode.MTR;
        }
        profile.grades.removeIf(point -> point == null || !Double.isFinite(point.s) || !Double.isFinite(point.y) || !Double.isFinite(point.curve));
        while (profile.grades.size() > MAX_GRADE_POINTS) {
            profile.grades.remove(profile.grades.size() - 1);
        }
        profile.grades.forEach(point -> {
            point.y = Math.max(-2048, Math.min(4096, point.y));
            point.curve = Math.max(0, Math.min(1024, point.curve));
        });
        profile.tilts.removeIf(point -> point == null || !Double.isFinite(point.s) || !Double.isFinite(point.degrees));
        while (profile.tilts.size() > MAX_TILT_POINTS) {
            profile.tilts.remove(profile.tilts.size() - 1);
        }
        profile.tilts.forEach(point -> point.degrees = clampTilt(point.degrees));
        profile.tiltA = clampTilt(Double.isFinite(profile.tiltA) ? profile.tiltA : 0);
        profile.tiltB = clampTilt(Double.isFinite(profile.tiltB) ? profile.tiltB : 0);
        profile.cantCap = Math.max(0, Math.min(MAX_TILT_DEGREES, Double.isFinite(profile.cantCap) ? profile.cantCap : 12));
        profile.grades.sort((a, b) -> Double.compare(a.s, b.s));
        profile.tilts.sort((a, b) -> Double.compare(a.s, b.s));
        profile.format = FORMAT;
        return profile;
    }

    private TrackProfile copy0() {
        if (pieces == null) {
            pieces = new ArrayList<>();
        }
        if (grades == null) {
            grades = new ArrayList<>();
        }
        if (tilts == null) {
            tilts = new ArrayList<>();
        }
        if (anchors == null) {
            anchors = new ArrayList<>();
        }
        pieces.removeIf(piece -> piece == null);
        anchors.removeIf(anchor -> anchor == null);
        return copy();
    }

    public static double clampTilt(double degrees) {
        return Math.max(-MAX_TILT_DEGREES, Math.min(MAX_TILT_DEGREES, degrees));
    }

    /** True when the stored node data still describes this rail (positions + MTR angles). */
    public boolean matches(long ax, long ay, long az, double angleA, long bx, long by, long bz, double angleB) {
        return this.ax == ax && this.ay == ay && this.az == az && this.bx == bx && this.by == by && this.bz == bz
            && Math.abs(this.angleA - angleA) < 0.01 && Math.abs(this.angleB - angleB) < 0.01;
    }
}
