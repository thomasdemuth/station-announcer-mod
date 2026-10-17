import com.stationannouncer.client.track.TrackVehicleLeanMath;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import org.mtr.core.tool.Vector;

/**
 * Offline check of the vehicle lean's distance → tilt mapping and roll sign
 * ({@code TrackVehicleLeanMath}), replaying MTR 4.0.1's own placement math:
 * <ul>
 *   <li>{@code PathData.getPosition(raw) = railMath.getPosition(raw, reversePositions)} — here the
 *       {@code TrackPath} facade, which the RailMath mixin makes identical;</li>
 *   <li>{@code Vehicle.getBogiePositions}: samples at {@code value ± c} (sign by {@code reversed});</li>
 *   <li>{@code Vehicle.getVehicleCarsAndPositions}: bogie distances per car;</li>
 *   <li>{@code PositionAndRotation}: yaw = atan2(dx, dz), pitch = atan2(dy, horizontal),
 *       {@code transformForwards = position + u.rotateX(pitch).rotateY(yaw)} with MTR's REAL
 *       {@code org.mtr.core.tool.Vector};</li>
 *   <li>the model matrix {@code T(pos)·Ry(yaw+π)·Rx(pitch+π)·[roll]} with standard rotations (JOML).</li>
 * </ul>
 * and comparing the rolled car with {@link TrackPath#bank} — the rail renderer's cross-section.
 *
 * Run (after compiling the core + TrackVehicleLeanMath into OUT, see TRACK_GEOMETRY_PLAN.md "Tests"):
 *   javac -d OUT -cp $J src/main/java/com/stationannouncer/track/*.java src/main/java/com/stationannouncer/client/track/TrackVehicleLeanMath.java
 *   java -cp OUT:$J tools/track_test/VehicleLeanCheck.java
 */
public class VehicleLeanCheck {
    static int checks;
    static int failures;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        bankEquivalence();
        curveLeansInward();
        modelFrameEquivalence();
        carFrameMatchesBogies();
        pathLookup();
        unrollInverts();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    static TrackProfile base(long bx, long by, long bz) {
        final TrackProfile profile = new TrackProfile();
        profile.ax = 0;
        profile.ay = 64;
        profile.az = 0;
        profile.bx = bx;
        profile.by = by;
        profile.bz = bz;
        profile.headingA = 0; // east
        return profile;
    }

    /** One fake MTR PathData over the whole rail. */
    record FakePathData(TrackPath path, double start, boolean reversePositions) {
        Vector position(double raw) {
            final double[] p = path.getPosition(raw, reversePositions);
            return new Vector(p[0], p[1], p[2]);
        }
    }

    /** MTR Vehicle.getBogiePositions (without the train-bound clamps): {position1, position2}. */
    static Vector[] bogieSamples(FakePathData pathData, double value, boolean vehicleReversed) {
        final double c = 1.0;
        final double value1 = value + (vehicleReversed ? -c : c);
        final double value2 = value - (vehicleReversed ? -c : c);
        return new Vector[]{pathData.position(value1 - pathData.start), pathData.position(value2 - pathData.start)};
    }

    static double yaw(Vector a, Vector b) {
        return Math.atan2(b.x - a.x, b.z - a.z);
    }

    static double pitch(Vector a, Vector b) {
        return Math.atan2(b.y - a.y, Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)));
    }

    static Vector mid(Vector a, Vector b) {
        return new Vector((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
    }

    /** Our lean + MTR transformForwards (real MTR Vector rotations). */
    static Vector forwards(Vector position, double yaw, double pitch, double tilt, double ux, double uy, double uz) {
        final double[] rolled = new double[2];
        final double phi = Math.toRadians(tilt);
        TrackVehicleLeanMath.roll(ux, uy, Math.cos(phi), Math.sin(phi), TrackVehicleLeanMath.pivotX(tilt), rolled);
        final Vector local = new Vector(rolled[0], rolled[1], uz);
        final Vector world = local.rotateX(pitch).rotateY(yaw);
        return new Vector(world.x + position.x, world.y + position.y, world.z + position.z);
    }

    static double dist(Vector a, Vector b) {
        return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z));
    }

    // ------------------------------------------------------------------ 1. == TrackPath.bank, all four direction combos

    static void bankEquivalence() {
        final TrackProfile profile = base(100, 64, 0);
        profile.pieces.add(TrackProfile.Piece.straight(100));
        profile.tiltA = 0;
        profile.tiltB = 30;
        profile.smoothTilt = false;
        final TrackPath path = TrackPath.build(profile, "QUADRATIC", 0);
        check(path.hasTilt(), "test path has tilt");
        final double[] cross = {-1.5, -0.7175, 0, 0.7175, 1.5};
        final double[] heights = {0, 0.5, 1, 3.5};
        for (final boolean pathReversed : new boolean[]{false, true}) {
            final FakePathData pathData = new FakePathData(path, 250, pathReversed);
            final TrackVehicleLeanMath.PathTilts tilts = new TrackVehicleLeanMath.PathTilts(7, new double[]{250}, new TrackPath[]{path}, new boolean[]{pathReversed});
            for (final boolean vehicleReversed : new boolean[]{false, true}) {
                for (double raw = 5; raw <= 95; raw += 15) {
                    final double d = pathData.start + raw;
                    final Vector[] samples = bogieSamples(pathData, d, vehicleReversed);
                    final Vector position = mid(samples[0], samples[1]);
                    final double yaw = yaw(samples[0], samples[1]);
                    final double pitch = pitch(samples[0], samples[1]);
                    final double tilt = tilts.tiltAt(d, vehicleReversed);

                    // track truth: sA, the A→B tilt there, and which way local +z runs along the rail
                    final double sA = pathReversed ? path.length() - raw : raw;
                    final double tiltAB = path.tilt(sA);
                    final double[] point = new double[5];
                    path.pointAt(sA, point);
                    final double rightX = -Math.sin(point[3]);
                    final double rightZ = Math.cos(point[3]);
                    final double zAlongAB = Math.sin(yaw) * Math.cos(point[3]) + Math.cos(yaw) * Math.sin(point[3]); // (+z_local)·(A→B)
                    final String tag = "pathRev=" + pathReversed + " vehRev=" + vehicleReversed + " raw=" + raw;
                    check(Math.abs(Math.abs(zAlongAB) - 1) < 1e-6, "local z along the rail " + tag);
                    check(Math.abs(tilt - Math.signum(zAlongAB) * tiltAB) < 1e-9, "tilt sign = direction of local +z along A→B " + tag + " got " + tilt + " vs " + tiltAB);
                    for (final double ux : cross) {
                        for (final double h : heights) {
                            // right of local +z is local −x; right of A→B is +u in bank()
                            final double uAB = zAlongAB > 0 ? -ux : ux;
                            final double[] banked = TrackPath.bank(uAB, h, tiltAB);
                            final Vector expected = new Vector(point[0] + rightX * banked[0], point[1] + banked[1], point[2] + rightZ * banked[0]);
                            final Vector actual = forwards(position, yaw, pitch, tilt, ux, h, 0);
                            check(dist(expected, actual) < 1e-6, "rolled car == bank() " + tag + " u=" + ux + " h=" + h + " off by " + dist(expected, actual));
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 2. right-hand curve + positive tilt leans INTO the curve, any direction

    static void curveLeansInward() {
        final TrackProfile profile = base(30, 64, 30);
        profile.pieces.add(TrackProfile.Piece.arc(30, 40, 1)); // right-hand, centre at (0.5, 30.5)
        profile.tiltA = 12;
        profile.tiltB = 12;
        final TrackPath path = TrackPath.build(profile, "QUADRATIC", 0);
        final double cx = 0.5;
        final double cz = 30.5;
        for (final boolean pathReversed : new boolean[]{false, true}) {
            final FakePathData pathData = new FakePathData(path, 0, pathReversed);
            final TrackVehicleLeanMath.PathTilts tilts = new TrackVehicleLeanMath.PathTilts(1, new double[]{0}, new TrackPath[]{path}, new boolean[]{pathReversed});
            for (final boolean vehicleReversed : new boolean[]{false, true}) {
                for (double d = 5; d <= 35; d += 10) {
                    final Vector[] samples = bogieSamples(pathData, d, vehicleReversed);
                    final Vector position = mid(samples[0], samples[1]);
                    final double yaw = yaw(samples[0], samples[1]);
                    final double tilt = tilts.tiltAt(d, vehicleReversed);
                    final Vector roof = forwards(position, yaw, 0, tilt, 0, 4, 0);
                    final Vector roofLevel = forwards(position, yaw, 0, 0, 0, 4, 0);
                    final double inwardX = cx - position.x;
                    final double inwardZ = cz - position.z;
                    final double lean = (roof.x - roofLevel.x) * inwardX + (roof.z - roofLevel.z) * inwardZ;
                    final String tag = "pathRev=" + pathReversed + " vehRev=" + vehicleReversed + " d=" + d;
                    check(lean > 0, "roof leans toward the curve centre " + tag);
                    // the inner (low) rail keeps its height, the outer rail rises
                    final Vector railL = forwards(position, yaw, 0, tilt, TrackProfile.HALF_GAUGE, 0, 0);
                    final Vector railR = forwards(position, yaw, 0, tilt, -TrackProfile.HALF_GAUGE, 0, 0);
                    final double distL = Math.hypot(railL.x - cx, railL.z - cz);
                    final double distR = Math.hypot(railR.x - cx, railR.z - cz);
                    final Vector inner = distL < distR ? railL : railR;
                    final Vector outer = distL < distR ? railR : railL;
                    check(Math.abs(inner.y - position.y) < 1e-9, "inner rail is the pivot " + tag);
                    check(outer.y > position.y + 0.25, "outer rail raised " + tag);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 3. model matrix (flipped frame) == u-frame transform

    /** Standard right-handed rotations (JOML / Minecraft's RotationAxis.POSITIVE_*). */
    static double[] rotX(double a, double[] v) {
        final double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
    }

    static double[] rotY(double a, double[] v) {
        final double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }

    static double[] rotZ(double a, double[] v) {
        final double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
    }

    static void modelFrameEquivalence() {
        final java.util.Random random = new java.util.Random(4);
        for (int i = 0; i < 400; i++) {
            final double yaw = (random.nextDouble() - 0.5) * 2 * Math.PI;
            final double pitch = (random.nextDouble() - 0.5) * 0.6;
            final double tilt = (random.nextDouble() - 0.5) * 90;
            final double oscillation = (random.nextDouble() - 0.5) * 6; // degrees, MTR's rotateZDegrees
            final double[] u = {(random.nextDouble() - 0.5) * 4, random.nextDouble() * 5, (random.nextDouble() - 0.5) * 20};
            // gangway path: transformForwards(u.rotateZ(−osc rad)) with our roll applied to that point
            final Vector oscillated = new Vector(u[0], u[1], u[2]).rotateZ(-Math.toRadians(oscillation));
            final Vector position = new Vector(0, 0, 0);
            final Vector viaVector = forwards(position, yaw, pitch, tilt, oscillated.x, oscillated.y, oscillated.z);
            // model path: Ry(yaw+π)·Rx(pitch+π)·[T(Pm)·Rz(φ)·T(−Pm)]·Rz(osc)·m, m = (−u.x, −u.y, u.z)
            final double pm = TrackVehicleLeanMath.modelPivotX(tilt);
            double[] m = {-u[0], -u[1], u[2]};
            m = rotZ(Math.toRadians(oscillation), m);
            m[0] -= pm;
            m = rotZ(Math.toRadians(tilt), m);
            m[0] += pm;
            m = rotX(pitch + Math.PI, m);
            m = rotY(yaw + Math.PI, m);
            final double error = Math.sqrt(Math.pow(m[0] - viaVector.x, 2) + Math.pow(m[1] - viaVector.y, 2) + Math.pow(m[2] - viaVector.z, 2));
            check(error < 1e-9, "model matrix roll == transformForwards roll (" + i + ") off by " + error);
        }
    }

    // ------------------------------------------------------------------ 4. car local +z agrees with its bogies' (MTR car loop)

    static void carFrameMatchesBogies() {
        final TrackProfile profile = base(30, 64, 30);
        profile.pieces.add(TrackProfile.Piece.arc(30, 40, 1));
        profile.tiltA = 10;
        profile.tiltB = 10;
        final TrackPath path = TrackPath.build(profile, "QUADRATIC", 0);
        final double carLength = 12;
        final double bogie1 = -4;
        final double bogie2 = 4;
        for (final boolean pathReversed : new boolean[]{false, true}) {
            final FakePathData pathData = new FakePathData(path, 0, pathReversed);
            final TrackVehicleLeanMath.PathTilts tilts = new TrackVehicleLeanMath.PathTilts(1, new double[]{0}, new TrackPath[]{path}, new boolean[]{pathReversed});
            for (final boolean vehicleReversed : new boolean[]{false, true}) {
                final double railProgress = vehicleReversed ? 14 : 30;
                double checkProgress = railProgress - (vehicleReversed ? carLength : 0);
                final int sign = vehicleReversed ? 1 : -1;
                final double halfLength = carLength / 2;
                final double d1 = checkProgress + sign * (halfLength + bogie1);
                final double d2 = checkProgress + sign * (halfLength + bogie2);
                final Vector[] s1 = bogieSamples(pathData, d1, vehicleReversed);
                final Vector[] s2 = bogieSamples(pathData, d2, vehicleReversed);
                final Vector p1 = mid(s1[0], s1[1]);
                final Vector p2 = mid(s2[0], s2[1]);
                final double carYaw = yaw(p1, p2);
                final double[] bogieTilts = {tilts.tiltAt(d1, vehicleReversed), tilts.tiltAt(d2, vehicleReversed)};
                final double[] bogieYaws = {yaw(s1[0], s1[1]), yaw(s2[0], s2[1])};
                final String tag = "pathRev=" + pathReversed + " vehRev=" + vehicleReversed;
                check(Math.cos(carYaw - bogieYaws[0]) > 0.9 && Math.cos(carYaw - bogieYaws[1]) > 0.9, "car +z == bogie +z " + tag);
                final double carTilt = TrackVehicleLeanMath.carTilt(carYaw, bogieTilts, bogieYaws, 2);
                check(Math.abs(carTilt - 10 * Math.signum(bogieTilts[0])) < 1e-9 && Math.abs(Math.abs(carTilt) - 10) < 1e-9, "car tilt = bogie mean " + tag);
                // a bogie whose frame points backwards contributes with flipped sign
                final double flipped = TrackVehicleLeanMath.carTilt(carYaw, new double[]{bogieTilts[0], -bogieTilts[1]}, new double[]{bogieYaws[0], bogieYaws[1] + Math.PI}, 2);
                check(Math.abs(flipped - carTilt) < 1e-9, "reversed bogie frame flipped back " + tag);
            }
        }
    }

    // ------------------------------------------------------------------ 5. multi-PathData lookup

    static void pathLookup() {
        final TrackProfile profile = base(50, 64, 0);
        profile.pieces.add(TrackProfile.Piece.straight(50));
        profile.tiltA = 6;
        profile.tiltB = 6;
        final TrackPath path = TrackPath.build(profile, "QUADRATIC", 0);
        // PathData 0: untilted 0..20, 1: our rail A→B 20..70, 2: untilted 70..90, 3: our rail B→A 90..140
        final TrackVehicleLeanMath.PathTilts tilts = new TrackVehicleLeanMath.PathTilts(3,
                new double[]{0, 20, 70, 90}, new TrackPath[]{null, path, null, path}, new boolean[]{false, false, true, true});
        check(tilts.any, "any");
        check(tilts.tiltAt(-1, false) == 0, "before the path → 0");
        check(tilts.tiltAt(10, false) == 0, "untilted PathData → 0");
        check(Math.abs(tilts.tiltAt(20, false) + 6) < 1e-9, "A→B walked, local +z backwards → −6");
        check(Math.abs(tilts.tiltAt(45, true) - 6) < 1e-9, "A→B walked, reversed vehicle → +6");
        check(tilts.tiltAt(69.999, false) != 0 && tilts.tiltAt(70, false) == 0, "boundary at 70");
        check(Math.abs(tilts.tiltAt(100, false) - 6) < 1e-9, "B→A walked, local +z backwards (= A→B) → +6");
        check(Math.abs(tilts.tiltAt(500, true) + 6) < 1e-9, "past the end uses the last PathData (MTR clamps)");
        check(!TrackVehicleLeanMath.PathTilts.none(1).any && TrackVehicleLeanMath.PathTilts.none(1).tiltAt(5, false) == 0, "none");
    }

    static void unrollInverts() {
        final double[] out = new double[2];
        final double[] back = new double[2];
        for (double tilt = -45; tilt <= 45; tilt += 7.5) {
            final double phi = Math.toRadians(tilt);
            TrackVehicleLeanMath.roll(0.3, 1.7, Math.cos(phi), Math.sin(phi), TrackVehicleLeanMath.pivotX(tilt), out);
            TrackVehicleLeanMath.unroll(out[0], out[1], Math.cos(phi), Math.sin(phi), TrackVehicleLeanMath.pivotX(tilt), back);
            check(Math.abs(back[0] - 0.3) < 1e-12 && Math.abs(back[1] - 1.7) < 1e-12, "unroll inverts roll at " + tilt);
        }
    }
}
