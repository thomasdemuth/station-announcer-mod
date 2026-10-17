import com.stationannouncer.mtr.track.TrackRailMathState;
import com.stationannouncer.track.TrackEdit;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackRegistry;
import com.stationannouncer.track.TrackSolver;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

/**
 * Exercises the RailMath mixin's logic ({@link TrackRailMathState}) against MTR 4.0.1's REAL RailMath,
 * outside the game. {@link Facade} does exactly what RailMathMixin does around a RailMath.
 *
 * Run (see TRACK_GEOMETRY_PLAN.md "Tests"):
 *   J=…/minecraft-transit-railway-FABRIC-4.0.1+1.20.4.jar
 *   javac -d OUT -cp $J src/main/java/com/stationannouncer/track/*.java \
 *       src/main/java/com/stationannouncer/mtr/track/TrackRailMathState.java src/main/java/com/stationannouncer/mtr/track/TrackSide.java
 *   java -cp OUT:$J tools/track_test/TrackRailMathStateTest.java
 */
public class TrackRailMathStateTest {
    static int checks = 0;
    static int failures = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** RailMath + state wired like the mixin: overrides first, MTR's original code otherwise. */
    static final class Facade {
        final RailMath math;
        final TrackRailMathState state;
        final long[] bounds;
        int boundsCalls;

        Facade(Position p1, Angle a1, Position p2, Angle a2, Rail.Shape shape, double verticalRadius, boolean client) {
            math = new RailMath(p1, a1, p2, a2, shape, verticalRadius);
            bounds = new long[]{math.minX, math.minY, math.minZ, math.maxX, math.maxY, math.maxZ};
            state = new TrackRailMathState(new long[]{p1.getX(), p1.getY(), p1.getZ(), p2.getX(), p2.getY(), p2.getZ()},
                a1.angleDegrees, a2.angleDegrees, shape.name(), verticalRadius, client, bounds.clone(),
                (minX, minY, minZ, maxX, maxY, maxZ) -> {
                    boundsCalls++;
                    bounds[0] = minX;
                    bounds[1] = minY;
                    bounds[2] = minZ;
                    bounds[3] = maxX;
                    bounds[4] = maxY;
                    bounds[5] = maxZ;
                });
            state.path(); // the mixin resolves eagerly at the end of the constructor
        }

        double[] position(double raw, boolean reverse) {
            final Vector custom = state.positionOrNull(raw, reverse);
            final Vector v = custom != null ? custom : math.getPosition(raw, reverse);
            return new double[]{v.x, v.y, v.z};
        }

        double length() {
            final double custom = state.lengthOrNaN();
            return custom == custom ? custom : math.getLength();
        }

        double[][] segments() throws Exception {
            final double[][] out = new double[2][];
            for (int i = 1; i <= 2; i++) {
                final double tStart = (double) field("tStart" + i);
                final double tEnd = (double) field("tEnd" + i);
                out[i - 1] = new double[]{(boolean) field("isStraight" + i) ? 1 : 0, Math.abs(tEnd - tStart), (double) field("r" + i), (boolean) field("reverseT" + i) ? 1 : 0};
            }
            return out;
        }

        Object field(String name) throws Exception {
            final Field f = RailMath.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(math);
        }

        TrackProfile importProfile() throws Exception {
            final long[] nodes = {state.ax, state.ay, state.az, state.bx, state.by, state.bz};
            return TrackRailMathState.importProfile(nodes, state.angleA, state.angleB, segments(), math.getLength(),
                (raw, reverse) -> {
                    final Vector v = math.getPosition(raw, reverse);
                    return new double[]{v.x, v.y, v.z};
                }, state.shapeName, state.verticalRadiusArg);
        }
    }

    public static void main(String[] args) throws Exception {
        noProfileIsMtr();
        profileFollowsPath();
        importSweep();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Angle angle(float degrees) {
        return Angle.fromAngle(degrees);
    }

    static double maxDiff(double[] a, double[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
    }

    // ------------------------------------------------------------------ 1

    static void noProfileIsMtr() {
        TrackRegistry.clearServer();
        TrackRegistry.clearClient();
        final Facade rail = new Facade(new Position(0, 64, 0), angle(0), new Position(40, 66, 40), angle(90), Rail.Shape.QUADRATIC, 0, false);
        check(rail.state.path() == null, "no profile → no path");
        check(rail.boundsCalls == 0, "no profile → bounds untouched");
        check(rail.length() == rail.math.getLength(), "no profile → MTR length");
        double worst = 0;
        for (int i = 0; i <= 40; i++) {
            final double d = rail.math.getLength() * i / 40;
            for (final boolean reverse : new boolean[]{false, true}) {
                final Vector mtr = rail.math.getPosition(d, reverse);
                worst = Math.max(worst, maxDiff(rail.position(d, reverse), new double[]{mtr.x, mtr.y, mtr.z}));
            }
        }
        check(worst == 0, "no profile → identical positions, worst " + worst);
    }

    // ------------------------------------------------------------------ 2

    static void profileFollowsPath() throws Exception {
        TrackRegistry.clearServer();
        TrackRegistry.clearClient();
        final Position a = new Position(0, 64, 0);
        final Position b = new Position(40, 66, 40);
        final Facade rail = new Facade(a, angle(0), b, angle(90), Rail.Shape.TWO_RADII, 10, false);
        final Facade clientRail = new Facade(a, angle(0), b, angle(90), Rail.Shape.TWO_RADII, 10, true);
        final double mtrLength = rail.math.getLength();
        final long[] mtrBounds = rail.bounds.clone();

        // exact import, saved server-side: positions follow the path = MTR's geometry
        final TrackProfile imported = rail.importProfile();
        check(imported != null, "import of a 90° curve");
        TrackRegistry.putServer("minecraft/overworld", imported);
        final TrackPath importedPath = rail.state.path();
        check(importedPath != null, "saved profile → path");
        check(clientRail.state.path() == null, "server profile is invisible to a client-side RailMath");
        check(Math.abs(rail.length() - mtrLength) < 1e-6, "import keeps MTR's length");
        double worst = 0;
        for (int i = 0; i <= 40; i++) {
            final double d = mtrLength * i / 40;
            final Vector mtr = rail.math.getPosition(d, true);
            worst = Math.max(worst, maxDiff(rail.position(d, true), new double[]{mtr.x, mtr.y, mtr.z}));
        }
        check(worst < 1e-4, "import reproduces MTR through the facade, worst " + worst);

        // an unrelated version bump must not rebuild the path
        TrackRegistry.putServer("minecraft/overworld", base(1000, 1000, 0));
        check(rail.state.path() == importedPath, "unrelated change keeps the built path");

        // a real edit: compound curve 90/40/90 (middle locked) + 4-block easements
        final TrackProfile compound = imported.copy();
        compound.pieces.clear();
        compound.pieces.add(TrackProfile.Piece.arc(60, 20, 1));
        compound.pieces.add(TrackProfile.Piece.arc(30, 20, 1));
        compound.pieces.add(TrackProfile.Piece.arc(60, 20, 1));
        compound.pieces.get(1).lockRadius = true;
        TrackSolver.Result result = TrackSolver.solve(compound);
        check(result.closes(), "compound edit closes: " + result.message());
        TrackProfile edited = result.profile();
        result = TrackSolver.solve(TrackEdit.addEasements(edited, 4));
        check(result.closes(), "eased compound closes: " + result.message());
        edited = result.profile();
        final int callsBefore = rail.boundsCalls;
        TrackRegistry.putServer("minecraft/overworld", edited);
        final TrackPath editedPath = rail.state.path();
        check(editedPath != null && editedPath != importedPath, "version bump → re-resolved to the edit");
        check(rail.boundsCalls == callsBefore + 1, "bounds rewritten once on the change");
        check(Math.abs(rail.length() - editedPath.getLength()) < 1e-12 && Math.abs(rail.length() - mtrLength) > 0.01,
            "length follows the edit (" + rail.length() + " vs MTR " + mtrLength + ")");
        worst = 0;
        for (int i = 0; i <= 40; i++) {
            final double d = editedPath.getLength() * i / 40;
            for (final boolean reverse : new boolean[]{false, true}) {
                worst = Math.max(worst, maxDiff(rail.position(d, reverse), editedPath.getPosition(d, reverse)));
            }
        }
        check(worst == 0, "positions follow TrackPath, worst " + worst);
        check(rail.bounds[0] == editedPath.minX && rail.bounds[5] == editedPath.maxZ, "bounds = path bounds");
        final double[] end = rail.position(editedPath.getLength(), false);
        check(Math.abs(end[0] - 40.5) < 0.02 && Math.abs(end[2] - 40.5) < 0.02 && Math.abs(end[1] - 66) < 1e-9, "edit still ends on node B");

        // MTR bypass: the editor's "MTR original" view
        final Vector original = TrackRailMathState.asMtr(() -> {
            check(rail.state.positionOrNull(5, false) == null, "asMtr → positionOrNull null");
            check(Double.isNaN(rail.state.lengthOrNaN()), "asMtr → lengthOrNaN NaN");
            check(rail.state.overridePath() == null, "asMtr → overridePath null");
            return rail.math.getPosition(5, false);
        });
        check(original != null && rail.state.positionOrNull(5, false) != null, "bypass ends with asMtr");

        // a client preview is seen only by client-side RailMaths
        TrackRegistry.setPreview(edited);
        check(clientRail.state.path() != null, "client preview → client path");
        TrackRegistry.clearPreviews();
        check(clientRail.state.path() == null, "preview cleared → client back to MTR");

        // node angles no longer match (rail re-angled in MTR): profile ignored, MTR bounds restored
        final TrackProfile wrongAngle = edited.copy();
        wrongAngle.angleA += 22.5;
        TrackRegistry.putServer("minecraft/overworld", wrongAngle);
        check(rail.state.path() == null, "mismatched angles → ignored");
        check(java.util.Arrays.equals(rail.bounds, mtrBounds), "mismatched angles → MTR bounds restored");
        check(rail.length() == mtrLength, "mismatched angles → MTR length");

        // a profile that does not close is never applied
        final TrackProfile open = edited.copy();
        open.pieces.get(0).length += 5;
        TrackRegistry.putServer("minecraft/overworld", open);
        check(rail.state.path() == null, "non-closing profile → ignored");

        // removal
        TrackRegistry.putServer("minecraft/overworld", edited);
        check(rail.state.path() != null, "re-saved");
        TrackRegistry.removeServer("minecraft/overworld", edited.key());
        check(rail.state.path() == null && rail.length() == mtrLength, "removed → MTR again");

        // cables never take a profile
        final Facade cable = new Facade(a, angle(0), b, angle(90), Rail.Shape.CABLE, 0, false);
        TrackRegistry.putServer("minecraft/overworld", edited);
        check(cable.state.path() == null, "cable ignores profiles");

        // a RailMath constructed while a profile is saved resolves at once (eager, like the mixin)
        final Facade late = new Facade(a, angle(0), b, angle(90), Rail.Shape.TWO_RADII, 10, false);
        check(late.boundsCalls == 1 && late.state.path() != null, "new RailMath picks up the saved profile at construction");
        check(late.state.path() == rail.state.path(), "copies of one rail share the built path");
        TrackRegistry.clearServer();
    }

    static TrackProfile base(long bx, long bz, double headingB) {
        final TrackProfile profile = new TrackProfile();
        profile.bx = bx;
        profile.bz = bz;
        profile.headingB = headingB;
        profile.pieces.add(TrackProfile.Piece.straight(bx));
        return profile;
    }

    // ------------------------------------------------------------------ 3

    static void importSweep() throws Exception {
        TrackRegistry.clearServer();
        final Random random = new Random(7);
        final Angle[] angles = Angle.values();
        final Method isValid = RailMath.class.getDeclaredMethod("isValid");
        isValid.setAccessible(true);
        int tested = 0;
        int fitted = 0;
        int failed = 0;
        for (int trial = 0; trial < 3000 && tested < 300; trial++) {
            Position p1 = new Position(random.nextInt(61) - 30, random.nextInt(7) + 60, random.nextInt(61) - 30);
            Position p2 = new Position(random.nextInt(61) - 30, random.nextInt(7) + 60, random.nextInt(61) - 30);
            if (p1.equals(p2)) {
                continue;
            }
            Angle a1 = angles[random.nextInt(angles.length)];
            Angle a2 = angles[random.nextInt(angles.length)];
            if (p1.compareTo(p2) > 0) {
                final Position p = p1;
                p1 = p2;
                p2 = p;
                final Angle t = a1;
                a1 = a2;
                a2 = t;
            }
            final Rail.Shape shape = random.nextBoolean() ? Rail.Shape.QUADRATIC : Rail.Shape.TWO_RADII;
            final Facade rail = new Facade(p1, a1, p2, a2, shape, shape == Rail.Shape.TWO_RADII ? random.nextInt(30) : 0, false);
            if (!(boolean) isValid.invoke(rail.math) || rail.math.getLength() < 0.5) {
                continue;
            }
            // skip MTR's discontinuous shapes (see TrackTest)
            boolean continuous = true;
            Vector previous = rail.math.getPosition(0, false);
            for (int i = 1; i <= 200 && continuous; i++) {
                final Vector next = rail.math.getPosition(rail.math.getLength() * i / 200, false);
                continuous = Math.hypot(next.x - previous.x, next.z - previous.z) <= rail.math.getLength() / 200 * 1.01 + 1e-9;
                previous = next;
            }
            if (!continuous) {
                continue;
            }
            tested++;
            final TrackProfile profile = rail.importProfile();
            if (profile == null) {
                failed++;
                continue;
            }
            final TrackPath path = TrackPath.build(profile, shape.name(), rail.state.verticalRadiusArg);
            check(path.closes(), "imported/fitted profile closes " + p1 + a1 + p2 + a2);
            if (Math.abs(path.getLength() - rail.math.getLength()) > 1e-3) {
                fitted++;
            }
            // and once saved, the facade applies it (matches + closes)
            TrackRegistry.putServer("sweep", profile);
            check(rail.state.path() != null, "saved import applies " + p1 + a1 + p2 + a2);
            TrackRegistry.removeServer("sweep", profile.key());
        }
        check(tested > 200, "enough rails: " + tested);
        check(failed * 50 <= tested, "imports rarely fail: " + failed + "/" + tested);
        System.out.println("import sweep: " + tested + " rails, " + fitted + " refit (kinked/unimportable), " + failed + " without a fit");
    }
}
