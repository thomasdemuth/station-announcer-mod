package com.stationannouncer.track;

import com.stationannouncer.track.TrackProfile.Piece;
import com.stationannouncer.track.TrackProfile.PieceType;
import com.stationannouncer.track.TrackProfile.TiltPoint;

import java.util.ArrayList;
import java.util.List;

/** Editing operations on a profile (all return a NEW profile; run {@link TrackSolver} afterwards). */
public final class TrackEdit {
    private static final double GRAVITY = 9.81;

    private TrackEdit() {
    }

    /**
     * Builds a profile from MTR's own two-segment geometry, so opening the editor and saving
     * without changes keeps the track exactly where it is.
     *
     * @param segments up to two MTR segments as {isStraight (1/0), length, radius, reverseT (1/0)}
     *                 — {@code reverseT=false} means the angle increases = a right-hand curve here
     */
    public static TrackProfile fromMtr(long ax, long ay, long az, double angleA, double headingA,
                                       long bx, long by, long bz, double angleB, double headingB,
                                       List<double[]> segments) {
        final TrackProfile profile = new TrackProfile();
        profile.ax = ax;
        profile.ay = ay;
        profile.az = az;
        profile.bx = bx;
        profile.by = by;
        profile.bz = bz;
        profile.angleA = angleA;
        profile.angleB = angleB;
        profile.headingA = snapToAxis(headingA, angleA);
        profile.headingB = snapToAxis(headingB, angleB);
        for (final double[] segment : segments) {
            final double length = segment[1];
            if (length < 1e-6) {
                continue;
            }
            if (segment[0] != 0) {
                profile.pieces.add(Piece.straight(length));
            } else {
                profile.pieces.add(Piece.arc(Math.abs(segment[2]), length, segment[3] != 0 ? -1 : 1));
            }
        }
        if (profile.pieces.isEmpty()) {
            profile.pieces.add(Piece.straight(Math.hypot(bx - ax, bz - az)));
        }
        return profile;
    }

    /**
     * A fresh chain between the profile's nodes for when MTR's own geometry cannot be imported
     * (MTR draws some node/angle combinations kinked). Tries a few templates (arc · straight · arc,
     * arc · arc, S-bend…) and returns the first the solver closes, or null.
     */
    public static TrackProfile fitChain(TrackProfile identity) {
        final double dx = identity.bx - identity.ax;
        final double dz = identity.bz - identity.az;
        final double distance = Math.max(1, Math.hypot(dx, dz));
        final double turn = TrackPath.wrap(identity.headingB - identity.headingA);
        final int hand = turn >= 0 ? 1 : -1;
        final double[][] templates = {
            // {type...} encoded as: 0 straight, 1 arc same hand, 2 arc opposite hand ; fractions of distance
            {1, 0, 1},
            {1, 1},
            {0, 1, 0},
            {1, 0, 2},
            {2, 0, 1},
            {1, 2, 1},
            {1, 0, 1, 0, 1},
        };
        for (final double radiusFactor : new double[]{1, 0.5, 2, 0.25, 4}) {
            for (final double[] template : templates) {
                final TrackProfile profile = identity.copy();
                profile.pieces.clear();
                for (final double code : template) {
                    final double length = distance / template.length;
                    profile.pieces.add(code == 0 ? TrackProfile.Piece.straight(length)
                        : TrackProfile.Piece.arc(distance * radiusFactor, length, code == 1 ? hand : -hand));
                }
                final TrackSolver.Result result = TrackSolver.solve(profile);
                if (result.closes()) {
                    return result.profile();
                }
            }
        }
        return null;
    }

    /**
     * MTR node angles are undirected axes; the exact travel heading is the axis direction (or its
     * opposite) closest to the measured one — measuring alone is only good to ~1e-5 rad.
     */
    public static double snapToAxis(double measuredHeading, double axisDegrees) {
        final double axis = Math.toRadians(axisDegrees);
        final double forward = TrackPath.wrap(measuredHeading - axis);
        final double snapped = Math.abs(forward) <= Math.PI / 2 ? axis : axis + Math.PI;
        return measuredHeading + TrackPath.wrap(snapped - measuredHeading);
    }

    /**
     * Inserts a transition spiral of {@code length} wherever curvature jumps between two pieces
     * (straight↔arc, arc↔arc of a different radius or hand). The new spirals are LOCKED to that
     * length; the arcs/straights around them are left unlocked so the solver can make room.
     */
    public static TrackProfile addEasements(TrackProfile input, double length) {
        final TrackProfile profile = input.copy();
        final List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < profile.pieces.size(); i++) {
            final Piece piece = profile.pieces.get(i);
            if (i > 0) {
                final Piece previous = profile.pieces.get(i - 1);
                if (previous.type != PieceType.SPIRAL && piece.type != PieceType.SPIRAL
                    && Math.abs(previous.curvature() - piece.curvature()) > 1e-9) {
                    final Piece spiral = Piece.spiral(length);
                    spiral.lockLength = true;
                    pieces.add(spiral);
                }
            }
            pieces.add(piece);
        }
        profile.pieces = pieces;
        return profile;
    }

    /** Removes every spiral (their length goes back to the solver via the unlocked pieces). */
    public static TrackProfile removeEasements(TrackProfile input) {
        final TrackProfile profile = input.copy();
        profile.pieces.removeIf(piece -> piece.type == PieceType.SPIRAL);
        if (profile.pieces.isEmpty()) {
            profile.pieces.add(Piece.straight(input.totalLength()));
        }
        return profile;
    }

    /** Splits piece {@code index} into two halves of the same type (e.g. to make one arc two radii). */
    public static TrackProfile split(TrackProfile input, int index) {
        final TrackProfile profile = input.copy();
        if (index < 0 || index >= profile.pieces.size() || profile.pieces.size() >= TrackProfile.MAX_PIECES) {
            return profile;
        }
        final Piece piece = profile.pieces.get(index);
        piece.length /= 2;
        final Piece second = piece.copy();
        profile.pieces.add(index + 1, second);
        return profile;
    }

    /** Equilibrium cant angle (degrees) for a curve of radius R (blocks = metres) at v km/h. */
    public static double equilibriumCant(double radius, double speedKmh) {
        if (radius <= 0 || speedKmh <= 0) {
            return 0;
        }
        final double v = speedKmh / 3.6;
        return Math.toDegrees(Math.atan(v * v / (GRAVITY * radius)));
    }

    /**
     * Replaces the tilt with a sensible cant: every arc gets its equilibrium cant for
     * {@code speedKmh}, capped at {@code profile.cantCap} (sign follows the curve's hand); spirals
     * carry the ramp; where a straight meets an arc directly, the ramp runs over
     * {@code rampLength} blocks centred on the joint (shortened if the pieces are short).
     *
     * @param path the BUILT path of {@code input} (for piece positions)
     */
    public static TrackProfile suggestCant(TrackProfile input, TrackPath path, double speedKmh, double rampLength) {
        final TrackProfile profile = input.copy();
        final int n = profile.pieces.size();
        final double[] cant = new double[n];
        for (int i = 0; i < n; i++) {
            final Piece piece = profile.pieces.get(i);
            if (piece.type == PieceType.ARC) {
                cant[i] = Math.signum(piece.turn) * Math.min(profile.cantCap, equilibriumCant(piece.radius, speedKmh));
            }
        }
        // spirals take the cant of the next non-spiral piece at their end, of the previous at their start
        final List<TiltPoint> points = new ArrayList<>();
        final double[] startCant = new double[n];
        final double[] endCant = new double[n];
        for (int i = 0; i < n; i++) {
            if (profile.pieces.get(i).type == PieceType.SPIRAL) {
                startCant[i] = i > 0 ? endCant[i - 1] : 0;
                double next = 0;
                for (int j = i + 1; j < n; j++) {
                    if (profile.pieces.get(j).type != PieceType.SPIRAL) {
                        next = cant[j];
                        break;
                    }
                }
                endCant[i] = next;
            } else {
                startCant[i] = endCant[i] = cant[i];
            }
        }
        profile.tiltA = startCant[0];
        profile.tiltB = endCant[n - 1];
        for (int i = 1; i < n; i++) {
            final double joint = path.pieceStart(i);
            final double before = endCant[i - 1];
            final double after = startCant[i];
            if (Math.abs(before - after) < 1e-6) {
                points.add(new TiltPoint(joint, after));
                continue;
            }
            final double half = Math.min(rampLength / 2, Math.min(path.pieceLength(i - 1), path.pieceLength(i)) / 2);
            points.add(new TiltPoint(joint - half, before));
            points.add(new TiltPoint(joint + half, after));
        }
        // collapse points closer than 0.05 blocks
        final List<TiltPoint> cleaned = new ArrayList<>();
        for (final TiltPoint point : points) {
            if (point.s <= 0.05 || point.s >= path.length() - 0.05) {
                continue;
            }
            if (!cleaned.isEmpty() && point.s - cleaned.get(cleaned.size() - 1).s < 0.05) {
                cleaned.get(cleaned.size() - 1).degrees = point.degrees;
            } else {
                cleaned.add(point);
            }
        }
        while (cleaned.size() > TrackProfile.MAX_TILT_POINTS) {
            cleaned.remove(cleaned.size() - 1);
        }
        profile.tilts = cleaned;
        return profile;
    }

    // ================================================================== round 2: Bézier + parallels + auto tilt
    // (signatures are a CONTRACT for the editor agents; bodies by the coordinator)

    /** Converts any profile to SPLINE geometry with (nearly) the same shape: anchors at piece boundaries, arcs longer than 90° subdivided; circle-exact handle lengths. Tilt/height kept. Spline input → copy. */
    public static TrackProfile toSpline(TrackProfile profile, TrackPath path) {
        return TrackSpline.toSpline(profile, path);
    }

    /** SPLINE: inserts an anchor at distance {@code s} WITHOUT changing the shape (exact de Casteljau split). Returns the new profile; the new anchor's index is {@link #lastInsertedAnchor}. */
    public static TrackProfile insertAnchor(TrackProfile spline, TrackPath path, double s) {
        return TrackSpline.insertAnchor(spline, path, s);
    }

    /** Index of the anchor added by the last {@link #insertAnchor} on this thread (-1 if none). */
    public static int lastInsertedAnchor() {
        return TrackSpline.lastInserted();
    }

    /** SPLINE: removes interior anchor {@code index} (never 0 or the last); neighbours' handles re-fitted to keep the shape close. */
    public static TrackProfile removeAnchor(TrackProfile spline, TrackPath path, int index) {
        return TrackSpline.removeAnchor(spline, path, index);
    }

    /**
     * SPLINE: moves interior anchor {@code index} to (x, z), keeping its heading and handles. For the two
     * END anchors only the handle lengths may change (nodes fixed) — use {@link #setHandle}.
     */
    public static TrackProfile moveAnchor(TrackProfile spline, int index, double x, double z) {
        return TrackSpline.moveAnchor(spline, index, x, z);
    }

    /**
     * SPLINE: drags anchor {@code index}'s handle ({@code out} = the forward handle, else the back one)
     * to world point (x, z): an interior anchor turns to face it and takes that handle length (the
     * opposite handle keeps its length — smooth); an END anchor keeps the node heading and only takes
     * the projected length (≥ 0.05).
     */
    public static TrackProfile setHandle(TrackProfile spline, int index, boolean out, double x, double z) {
        return TrackSpline.setHandle(spline, index, out, x, z);
    }

    /**
     * The companion of a parallel track: {@code companion} is the other rail's identity (nodes,
     * MTR angles, headings — e.g. from TrackRails.editableProfile), {@code sourcePath} the edited
     * track's path, {@code lateral} the signed offset of the companion (+ = right of the source's
     * A→B), {@code reversed} true when the companion runs B→A relative to the source. Returns a SPLINE
     * profile following the offset curve (height + tilt copied, tilt sign flipped when reversed), or
     * null when the companion's nodes are not where the offset curve ends (± 0.3 blocks).
     */
    public static TrackProfile parallel(TrackProfile companion, TrackPath sourcePath, double lateral, boolean reversed) {
        return TrackSpline.parallel(companion, sourcePath, lateral, reversed);
    }

    /**
     * One-click realistic cant for ANY geometry: tilt follows the curvature (equilibrium cant for
     * {@code speedKmh}, capped at {@code profile.cantCap}), ramping in/out where curvature changes,
     * expressed as tilt points (smooth). Pieces geometry uses {@link #suggestCant}.
     */
    public static TrackProfile autoTilt(TrackProfile profile, TrackPath path, double speedKmh) {
        return TrackSpline.autoTilt(profile, path, speedKmh);
    }
}
