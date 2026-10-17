import com.stationannouncer.track.TrackEdit;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackSolver;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Unit tests for com.stationannouncer.track against MTR 4.0.1's REAL RailMath (core classes from
 * the MTR jar run fine outside Minecraft). Run (see TRACK_GEOMETRY_PLAN.md):
 *   javac -d OUT -cp MTR.jar src/main/java/com/stationannouncer/track/*.java
 *   java -cp OUT:MTR.jar tools/track_test/TrackTest.java
 */
public class TrackTest {
    static int checks = 0;
    static int failures = 0;
    static int kinked = 0;
    static int unimportable = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        mtrEquivalence();
        solverCompound();
        solverLocks();
        easementsAndCant();
        heightAndTilt();
        splines();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------ 1. import == MTR

    static Object field(RailMath math, String name) throws Exception {
        final Field f = RailMath.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(math);
    }

    /** What the RailMath mixin's accessor will provide: the profile MTR's geometry imports to. */
    static TrackProfile importMtr(Position p1, Angle a1, Position p2, Angle a2, RailMath math) throws Exception {
        final List<double[]> segments = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            final double tStart = (double) field(math, "tStart" + i);
            final double tEnd = (double) field(math, "tEnd" + i);
            segments.add(new double[]{(boolean) field(math, "isStraight" + i) ? 1 : 0, Math.abs(tEnd - tStart), (double) field(math, "r" + i), (boolean) field(math, "reverseT" + i) ? 1 : 0});
        }
        final double length = math.getLength();
        final Vector s0 = math.getPosition(0, false);
        final Vector s1 = math.getPosition(Math.min(1e-4, length), false);
        final Vector e0 = math.getPosition(Math.max(0, length - 1e-4), false);
        final Vector e1 = math.getPosition(length, false);
        return TrackEdit.fromMtr(p1.getX(), p1.getY(), p1.getZ(), a1.angleDegrees, Math.atan2(s1.z - s0.z, s1.x - s0.x),
            p2.getX(), p2.getY(), p2.getZ(), a2.angleDegrees, Math.atan2(e1.z - e0.z, e1.x - e0.x), segments);
    }

    static void mtrEquivalence() throws Exception {
        final Random random = new Random(42);
        final Angle[] angles = Angle.values();
        int tested = 0;
        kinked = 0;
        for (int trial = 0; trial < 4000 && tested < 600; trial++) {
            Position p1 = new Position(random.nextInt(81) - 40, random.nextInt(9) + 60, random.nextInt(81) - 40);
            Position p2 = new Position(random.nextInt(81) - 40, random.nextInt(9) + 60, random.nextInt(81) - 40);
            if (p1.equals(p2)) {
                continue;
            }
            Angle a1 = angles[random.nextInt(angles.length)];
            Angle a2 = angles[random.nextInt(angles.length)];
            if (p1.compareTo(p2) > 0) {
                final Position p = p1;
                p1 = p2;
                p2 = p;
                final Angle a = a1;
                a1 = a2;
                a2 = a;
            }
            final Rail.Shape shape = random.nextBoolean() ? Rail.Shape.QUADRATIC : Rail.Shape.TWO_RADII;
            final double verticalRadius = shape == Rail.Shape.TWO_RADII ? random.nextInt(40) : 0;
            final RailMath math = new RailMath(p1, a1, p2, a2, shape, verticalRadius);
            final Field valid = null;
            final java.lang.reflect.Method isValid = RailMath.class.getDeclaredMethod("isValid");
            isValid.setAccessible(true);
            if (!(boolean) isValid.invoke(math) || math.getLength() < 0.5) {
                continue;
            }
            // MTR itself produces broken (discontinuous) paths for some node/angle combinations its
            // placement never creates — skip those, they are not importable by definition
            boolean continuous = true;
            Vector previous = math.getPosition(0, false);
            for (int i = 1; i <= 200 && continuous; i++) {
                final Vector next = math.getPosition(math.getLength() * i / 200, false);
                continuous = Math.hypot(next.x - previous.x, next.z - previous.z) <= math.getLength() / 200 * 1.01 + 1e-9;
                previous = next;
            }
            if (!continuous) {
                continue;
            }
            // ... and some that leave a node off its own axis (kinked): not importable either
            final Vector k0 = math.getPosition(0, false);
            final Vector k1 = math.getPosition(1e-3, false);
            final Vector k2 = math.getPosition(math.getLength() - 1e-3, false);
            final Vector k3 = math.getPosition(math.getLength(), false);
            final double hA = Math.atan2(k1.z - k0.z, k1.x - k0.x);
            final double hB = Math.atan2(k3.z - k2.z, k3.x - k2.x);
            if (Math.abs(TrackPath.wrap(TrackEdit.snapToAxis(hA, a1.angleDegrees) - hA)) > 1e-2
                || Math.abs(TrackPath.wrap(TrackEdit.snapToAxis(hB, a2.angleDegrees) - hB)) > 1e-2) {
                kinked++;
                final TrackProfile identity = importMtr(p1, a1, p2, a2, math);
                final TrackProfile fitted = TrackEdit.fitChain(identity);
                check(fitted != null, "fitChain finds a chain for kinked " + p1 + a1 + p2 + a2);
                continue;
            }
            final TrackProfile profile = importMtr(p1, a1, p2, a2, math);
            final TrackPath path = TrackPath.build(profile, shape.name(), verticalRadius);
            if (!path.closes()) {
                // the production rule: an import that does not reproduce MTR is refit from scratch
                unimportable++;
                System.out.println("unimportable MTR shape (refit): " + p1.getX() + "," + p1.getZ() + " " + a1 + " -> " + p2.getX() + "," + p2.getZ() + " " + a2 + " len " + math.getLength());
                check(TrackEdit.fitChain(profile) != null, "fitChain for unimportable shape");
                continue;
            }
            tested++;
            final String label = p1 + " " + a1 + " -> " + p2 + " " + a2 + " " + shape;
            check(Math.abs(path.getLength() - math.getLength()) < 1e-6, "length " + label + " ours=" + path.getLength() + " mtr=" + math.getLength());
            check(path.closes(), "import closes " + label + " err=" + path.positionError() + " head=" + path.headingError());
            double worst = 0;
            for (int i = 0; i <= 50; i++) {
                final double d = math.getLength() * i / 50;
                for (final boolean reverse : new boolean[]{false, true}) {
                    final Vector mtr = math.getPosition(d, reverse);
                    final double[] ours = path.getPosition(d, reverse);
                    worst = Math.max(worst, Math.max(Math.abs(mtr.x - ours[0]), Math.max(Math.abs(mtr.y - ours[1]), Math.abs(mtr.z - ours[2]))));
                }
            }
            check(worst < 1e-4, "positions " + label + " worst=" + worst);
            // sampling differs slightly from MTR's per-segment stepping: allow one block (bounds only cull)
            check(Math.abs(path.minX - math.minX) <= 1 && Math.abs(path.maxX - math.maxX) <= 1 && Math.abs(path.minZ - math.minZ) <= 1
                && Math.abs(path.maxZ - math.maxZ) <= 1 && Math.abs(path.minY - math.minY) <= 1 && Math.abs(path.maxY - math.maxY) <= 1, "bounds " + label);
            check(Math.abs(path.getMaxVerticalRadius() - math.getMaxVerticalRadius()) < 1e-9 || Double.isInfinite(math.getMaxVerticalRadius()), "max vertical radius " + label);
            // the solver must leave an already-closed import alone
            final TrackSolver.Result result = TrackSolver.solve(profile);
            check(result.closes(), "solve keeps import closed " + label);
            double drift = 0;
            for (int i = 0; i < profile.pieces.size(); i++) {
                drift = Math.max(drift, Math.abs(profile.pieces.get(i).length - result.profile().pieces.get(i).length));
            }
            check(drift < 1e-4, "solve does not move a closed import " + label + " drift=" + drift);
        }
        check(tested > 300, "enough valid MTR rails tested: " + tested);
        check(unimportable * 50 < tested, "unimportable shapes stay rare: " + unimportable);
        System.out.println("MTR equivalence: " + tested + " rails (+ " + kinked + " kinked MTR shapes refit)");
    }

    // ------------------------------------------------------------ 2. compound curves

    static TrackProfile base(long bx, long bz, double headingB) {
        final TrackProfile profile = new TrackProfile();
        profile.ax = 0;
        profile.ay = 64;
        profile.az = 0;
        profile.bx = bx;
        profile.by = 64;
        profile.bz = bz;
        profile.headingA = 0; // east
        profile.headingB = headingB;
        return profile;
    }

    static void solverCompound() {
        // a 90° right-hand curve from (0,0) heading east to (60, 60) heading south, as three arcs of different radii
        final TrackProfile profile = base(60, 60, Math.PI / 2);
        profile.pieces.add(TrackProfile.Piece.arc(90, 20, 1));
        profile.pieces.add(TrackProfile.Piece.arc(40, 30, 1));
        profile.pieces.add(TrackProfile.Piece.arc(90, 20, 1));
        profile.pieces.get(1).lockRadius = true;
        final TrackSolver.Result result = TrackSolver.solve(profile);
        check(result.closes(), "compound 3-arc closes: " + result.message());
        check(Math.abs(result.profile().pieces.get(1).radius - 40) < 1e-9, "locked radius kept");
        final TrackPath path = TrackPath.build(result.profile(), "QUADRATIC", 0);
        check(path.closes(), "compound path closes " + path.positionError());
        check(Math.abs(path.curvature(path.pieceStart(1) + 1) - 1.0 / 40) < 1e-9, "middle arc curvature");
        final double[] point = new double[5];
        path.pointAt(path.length(), point);
        check(Math.abs(point[0] - 60.5) < 0.02 && Math.abs(point[2] - 60.5) < 0.02, "ends at B centre");

        // S-curve: right then left, parallel offset
        final TrackProfile s = base(80, 20, 0);
        s.pieces.add(TrackProfile.Piece.straight(10));
        s.pieces.add(TrackProfile.Piece.arc(60, 15, 1));
        s.pieces.add(TrackProfile.Piece.straight(5));
        s.pieces.add(TrackProfile.Piece.arc(60, 15, -1));
        s.pieces.add(TrackProfile.Piece.straight(10));
        final TrackSolver.Result sResult = TrackSolver.solve(s);
        check(sResult.closes(), "S-curve closes: " + sResult.message());
    }

    static void solverLocks() {
        final TrackProfile profile = base(60, 60, Math.PI / 2);
        profile.pieces.add(TrackProfile.Piece.arc(60, 90, 1));
        profile.pieces.get(0).lockRadius = true;
        profile.pieces.get(0).lockLength = true;
        final TrackSolver.Result result = TrackSolver.solve(profile);
        check(!result.closes() && result.freeCount() == 0 && !result.message().isEmpty(), "all locked → reported, not solved");

        // dragging (pinning) a radius: the others absorb it
        final TrackProfile drag = base(60, 60, Math.PI / 2);
        drag.pieces.add(TrackProfile.Piece.straight(5));
        drag.pieces.add(TrackProfile.Piece.arc(50, 70, 1));
        drag.pieces.add(TrackProfile.Piece.straight(5));
        drag.pieces.get(1).radius = 35;
        final TrackSolver.Result dragged = TrackSolver.solve(drag, Set.of(new TrackSolver.Param(1, TrackSolver.Param.RADIUS).id()));
        check(dragged.closes(), "pinned radius drag closes: " + dragged.message());
        check(Math.abs(dragged.profile().pieces.get(1).radius - 35) < 1e-9, "pinned radius kept");
    }

    static void easementsAndCant() {
        final TrackProfile profile = base(60, 60, Math.PI / 2);
        profile.pieces.add(TrackProfile.Piece.straight(15));
        profile.pieces.add(TrackProfile.Piece.arc(40, 60, 1));
        profile.pieces.add(TrackProfile.Piece.straight(15));
        final TrackSolver.Result plain = TrackSolver.solve(profile);
        check(plain.closes(), "plain curve closes");
        final TrackProfile eased = TrackEdit.addEasements(plain.profile(), 12);
        check(eased.pieces.size() == 5 && eased.pieces.get(1).type == TrackProfile.PieceType.SPIRAL, "two spirals inserted");
        final TrackSolver.Result easedResult = TrackSolver.solve(eased);
        check(easedResult.closes(), "eased curve closes: " + easedResult.message());
        final TrackPath path = TrackPath.build(easedResult.profile(), "QUADRATIC", 0);
        final double sMid = path.pieceStart(1) + path.pieceLength(1) / 2;
        final double k = path.curvature(sMid);
        final double kArc = 1 / easedResult.profile().pieces.get(2).radius;
        check(Math.abs(k - kArc / 2) < 1e-6, "spiral curvature ramps linearly (mid = half): " + k + " vs " + kArc / 2);
        // heading continuous across joints
        double jump = 0;
        for (int i = 1; i < path.pieceCount(); i++) {
            jump = Math.max(jump, Math.abs(path.heading(path.pieceStart(i) - 1e-7) - path.heading(path.pieceStart(i) + 1e-7)));
        }
        check(jump < 1e-5, "heading continuous " + jump);

        final TrackProfile canted = TrackEdit.suggestCant(easedResult.profile(), path, 80, 10);
        final TrackPath cantPath = TrackPath.build(canted, "QUADRATIC", 0);
        final double arcMid = cantPath.pieceStart(2) + cantPath.pieceLength(2) / 2;
        final double expected = Math.min(canted.cantCap, TrackEdit.equilibriumCant(easedResult.profile().pieces.get(2).radius, 80));
        check(Math.abs(cantPath.tilt(arcMid) - expected) < 1e-6, "arc carries full cant " + cantPath.tilt(arcMid) + " vs " + expected);
        check(cantPath.tilt(arcMid) > 0, "right-hand curve → positive (right-low) tilt");
        check(Math.abs(cantPath.tilt(1)) < 1e-6, "straight start untilted");
        final double spiralMid = cantPath.pieceStart(1) + cantPath.pieceLength(1) / 2;
        check(cantPath.tilt(spiralMid) > 0 && cantPath.tilt(spiralMid) < expected, "spiral carries the ramp");
        check(Math.abs(cantPath.tilt(arcMid, true) + cantPath.tilt(cantPath.length() - arcMid)) < 1e-9, "reversed tilt flips sign");
    }

    static void heightAndTilt() {
        final TrackProfile profile = base(100, 0, 0);
        profile.by = 70;
        profile.pieces.add(TrackProfile.Piece.straight(100));
        profile.vertical = TrackProfile.VerticalMode.CUSTOM;
        profile.grades.add(new TrackProfile.GradePoint(40, 64, 10)); // flat for 40, then climb 6 over 60
        final TrackPath path = TrackPath.build(profile, "QUADRATIC", 0);
        check(Math.abs(path.height(0) - 64) < 1e-9 && Math.abs(path.height(100) - 70) < 1e-9, "custom height meets both nodes");
        check(Math.abs(path.height(20) - 64) < 1e-9, "flat section flat");
        check(Math.abs(path.height(80) - (64 + 6 * 40.0 / 60)) < 1e-9, "climb section linear");
        check(path.height(40) > 64 && path.height(40) < 64 + 0.13, "vertical curve rounds the break");
        double maxStep = 0;
        for (double s = 30; s < 50; s += 0.01) {
            maxStep = Math.max(maxStep, Math.abs(path.grade(s + 0.01) - path.grade(s)));
        }
        check(maxStep < 0.01, "grade changes smoothly through the vertical curve " + maxStep);

        // bank: right-low tilt lowers the right rail, keeps the low (pivot) rail in place, leans the top right
        final double[] lowRail = TrackPath.bank(TrackProfile.HALF_GAUGE, 0, 10);
        check(Math.abs(lowRail[0] - TrackProfile.HALF_GAUGE) < 1e-12 && Math.abs(lowRail[1]) < 1e-12, "pivot = low rail fixed");
        final double[] highRail = TrackPath.bank(-TrackProfile.HALF_GAUGE, 0, 10);
        check(highRail[1] > 0, "left (outer) rail raised for positive tilt");
        final double[] roof = TrackPath.bank(0, 4, 10);
        check(roof[0] > 0, "roof leans right (into the curve) for positive tilt");
    }

    // ------------------------------------------------------------ round 2: Bézier splines

    static double minDistance(TrackPath path, double x, double z) {
        double best = Double.MAX_VALUE;
        final double[] p = new double[5];
        for (double s = 0; s <= path.length(); s += 0.02) {
            path.pointAt(s, p);
            best = Math.min(best, Math.hypot(p[0] - x, p[2] - z));
        }
        return best;
    }

    static void splines() {
        // a closed eased 90° right-hand curve
        TrackProfile pieces = base(60, 60, Math.PI / 2);
        pieces.pieces.add(TrackProfile.Piece.straight(10));
        pieces.pieces.add(TrackProfile.Piece.arc(45, 60, 1));
        pieces.pieces.add(TrackProfile.Piece.straight(10));
        pieces = TrackSolver.solve(TrackEdit.addEasements(TrackSolver.solve(pieces).profile(), 10)).profile();
        final TrackPath piecesPath = TrackPath.build(pieces, "QUADRATIC", 0);
        check(piecesPath.closes(), "spline test source closes");

        final TrackProfile spline = TrackEdit.toSpline(pieces, piecesPath);
        final TrackPath splinePath = TrackPath.build(spline, "QUADRATIC", 0);
        check(spline.geometry == TrackProfile.Geometry.SPLINE && splinePath.isSpline(), "toSpline → spline");
        check(splinePath.closes(), "spline closes by construction");
        check(Math.abs(splinePath.length() - piecesPath.length()) < 0.01, "spline length ≈ pieces length " + splinePath.length() + " vs " + piecesPath.length());
        double worst = 0;
        final double[] p = new double[5];
        for (double d = 0; d <= piecesPath.length(); d += 1) {
            piecesPath.pointAt(d, p);
            worst = Math.max(worst, minDistance(splinePath, p[0], p[2]));
        }
        check(worst < 0.01, "toSpline keeps the shape (worst " + worst + ")");
        final double[] radii = splinePath.horizontalRadii();
        check(Math.abs(radii[0] - pieces.pieces.get(2).radius) < 0.5, "spline min radius ≈ arc radius " + radii[0]);
        final TrackProfile sane = spline.sanitized();
        check(sane != null && TrackPath.build(sane, "QUADRATIC", 0).closes(), "spline survives sanitize");

        // insert keeps the shape exactly
        final TrackProfile inserted = TrackEdit.insertAnchor(spline, splinePath, 33.3);
        final int index = TrackEdit.lastInsertedAnchor();
        final TrackPath insertedPath = TrackPath.build(inserted, "QUADRATIC", 0);
        check(index > 0 && inserted.anchors.size() == spline.anchors.size() + 1, "anchor inserted at " + index);
        check(Math.abs(insertedPath.length() - splinePath.length()) < 1e-6, "insert keeps length");
        double insertWorst = 0;
        for (double d = 0; d <= splinePath.length(); d += 0.7) {
            splinePath.pointAt(d, p);
            final double[] q = new double[5];
            insertedPath.pointAt(d, q);
            insertWorst = Math.max(insertWorst, Math.hypot(p[0] - q[0], p[2] - q[2]));
        }
        check(insertWorst < 1e-3, "insert keeps the shape exactly (" + insertWorst + ")");
        check(Math.abs(insertedPath.anchorS(index) - 33.3) < 1e-6, "inserted anchor sits at the clicked distance " + insertedPath.anchorS(index));

        // move it: still closes, passes through the new point
        final TrackProfile moved = TrackEdit.moveAnchor(inserted, index, inserted.anchors.get(index).x + 5, inserted.anchors.get(index).z - 3);
        final TrackPath movedPath = TrackPath.build(moved, "QUADRATIC", 0);
        check(movedPath.closes(), "moved spline still meets both nodes");
        check(minDistance(movedPath, moved.anchors.get(index).x, moved.anchors.get(index).z) < 0.02, "track passes through the moved anchor");
        check(TrackEdit.moveAnchor(inserted, 0, 99, 99).anchors.get(0).x == inserted.anchors.get(0).x, "end anchors can't move");
        // handles
        final TrackProfile handled = TrackEdit.setHandle(moved, 0, true, moved.anchors.get(0).x + 30, moved.anchors.get(0).z + 8);
        check(Math.abs(handled.anchors.get(0).heading - moved.anchors.get(0).heading) < 1e-12 && Math.abs(handled.anchors.get(0).handleOut - 30) < 1e-9, "end handle: length only, heading fixed");
        final TrackProfile turned = TrackEdit.setHandle(moved, index, true, moved.anchors.get(index).x + 3, moved.anchors.get(index).z + 3);
        check(Math.abs(turned.anchors.get(index).heading - Math.PI / 4) < 1e-9, "interior handle turns the anchor");
        check(TrackPath.build(turned, "QUADRATIC", 0).closes(), "still closes after a handle drag");
        // remove
        final TrackProfile removed = TrackEdit.removeAnchor(inserted, insertedPath, index);
        check(removed.anchors.size() == spline.anchors.size(), "anchor removed");
        check(TrackEdit.removeAnchor(inserted, insertedPath, 0).anchors.size() == inserted.anchors.size(), "can't remove an end");

        // parallel: companion 4 blocks to the right (outside of nothing — inside of this right-hand curve is +right)
        final TrackProfile companion = base(56, 60, Math.PI / 2);
        companion.az = 4; // A' = A + right(east)·4 = (0.5, 4.5); B' = B + right(south)·4 = (56.5, 60.5)
        final TrackProfile follower = TrackEdit.parallel(companion, splinePath, 4, false);
        check(follower != null, "parallel companion found");
        final TrackPath followerPath = TrackPath.build(follower.sanitized(), "QUADRATIC", 0);
        check(followerPath.closes(), "parallel companion closes");
        double spacing = 0;
        for (double d = 0; d <= followerPath.length(); d += 1) {
            followerPath.pointAt(d, p);
            spacing = Math.max(spacing, Math.abs(minDistance(splinePath, p[0], p[2]) - 4));
        }
        check(spacing < 0.02, "companion stays 4 blocks away (worst " + spacing + ")");
        check(TrackEdit.parallel(companion, splinePath, 3, false) == null, "wrong spacing → no companion");
        // reversed companion (stored B→A)
        final TrackProfile reversedCompanion = new TrackProfile();
        reversedCompanion.ax = 56;
        reversedCompanion.ay = 64;
        reversedCompanion.az = 60;
        reversedCompanion.bx = 0;
        reversedCompanion.by = 64;
        reversedCompanion.bz = 4;
        reversedCompanion.headingA = -Math.PI / 2;
        reversedCompanion.headingB = Math.PI;
        final TrackProfile reversedFollower = TrackEdit.parallel(reversedCompanion, splinePath, 4, true);
        check(reversedFollower != null && TrackPath.build(reversedFollower.sanitized(), "QUADRATIC", 0).closes(), "reversed companion closes");

        // auto tilt on a spline: right-hand curve → positive, within cap, flat at the straight ends
        final TrackProfile tilted = TrackEdit.autoTilt(spline, splinePath, 80);
        final TrackPath tiltedPath = TrackPath.build(tilted, "QUADRATIC", 0);
        final double mid = tiltedPath.length() / 2;
        final double expected = Math.min(tilted.cantCap, TrackEdit.equilibriumCant(45, 80));
        check(Math.abs(tiltedPath.tilt(mid) - expected) < 0.6, "auto tilt mid-curve ≈ cant (" + tiltedPath.tilt(mid) + " vs " + expected + ")");
        check(Math.abs(tiltedPath.tilt(0.5)) < 1.0 && Math.abs(tiltedPath.tilt(tiltedPath.length() - 0.5)) < 1.0, "auto tilt ~0 on the straight ends");
        check(tilted.tilts.size() <= TrackProfile.MAX_TILT_POINTS, "auto tilt within point budget " + tilted.tilts.size());
        // tilt carried to the follower
        final TrackProfile tiltedFollower = TrackEdit.parallel(companion, tiltedPath, 4, false);
        final TrackPath tiltedFollowerPath = TrackPath.build(tiltedFollower, "QUADRATIC", 0);
        check(Math.abs(tiltedFollowerPath.tilt(tiltedFollowerPath.length() / 2) - tiltedPath.tilt(mid)) < 1.0, "follower leans the same way");
        final TrackProfile tiltedReversed = TrackEdit.parallel(reversedCompanion, tiltedPath, 4, true);
        final TrackPath tiltedReversedPath = TrackPath.build(tiltedReversed, "QUADRATIC", 0);
        check(tiltedReversedPath.tilt(tiltedReversedPath.length() / 2) < -1, "reversed follower: same lean, opposite sign in its own frame");
    }
}
