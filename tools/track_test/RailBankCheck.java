import com.stationannouncer.client.track.TrackRailBank;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.Random;

/**
 * Offline proof of TrackRailRenderer's model-style roll (TrackRailBank): rebuilds MTR 4.0.1's
 * per-segment rail-style matrix (MatrixStack semantics = post-multiply, right-handed rotations)
 * with JOML, inserts our roll, and checks against TrackPath.bank and the plan's conventions:
 *
 *  A. pitch 0: every model point (u lateral, h height, s along) lands exactly where
 *     TrackPath.bank(u, h, tilt) puts it — both flip and non-flip styles, many yaws/tilts/offsets.
 *  B. any pitch: the roll is a rotation by +tilt about the FORWARD tangent through the low rail
 *     at path height (centre + right·pivotOffset), i.e. world_rolled = Q + Rot(t, φ)(world − Q).
 *  C. positive tilt keeps the right (low) rail fixed and raises the left one; negative mirrors.
 *
 * Build/run (from the project root):
 *   javac -proc:none -d OUT -cp "$(cat cp.txt)" src/main/java/com/stationannouncer/track/*.java \
 *       src/main/java/com/stationannouncer/client/track/TrackRailBank.java
 *   java -cp "OUT:$(cat cp.txt)" tools/track_test/RailBankCheck.java
 */
public class RailBankCheck {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        final Random random = new Random(7);
        final double[] yaws = {0, 0.3, Math.PI / 4, Math.PI / 2, 2.1, Math.PI, -0.7, -Math.PI / 2, -2.9};
        final double[] tilts = {-45, -20, -5, -0.5, 0.5, 5, 20, 45};
        final double[] offsets = {0, 0.3, -0.2, 1.0};
        final double[] pitches = {-0.6, -0.2, -0.05, 0.05, 0.2, 0.6};

        for (final boolean flip : new boolean[]{false, true}) {
            for (final double yaw : yaws) {
                for (final double tilt : tilts) {
                    for (final double m : offsets) {
                        // A: pitch 0 vs TrackPath.bank
                        for (int k = 0; k < 20; k++) {
                            final double u = random.nextDouble() * 4 - 2;
                            final double h = random.nextDouble() * 2 - 0.5;
                            final double s = random.nextDouble() * 2 - 1;
                            final Vector3d world = rolled(yaw, 0, flip, tilt, m, model(u, h, s, m, flip));
                            final double[] banked = TrackPath.bank(u, h, tilt);
                            final Vector3d expected = new Vector3d(-Math.sin(yaw) * banked[0] + Math.cos(yaw) * s, banked[1], Math.cos(yaw) * banked[0] + Math.sin(yaw) * s);
                            check(world.distance(expected) < 1e-9, "A flip=%s yaw=%.2f tilt=%.1f m=%.2f u=%.2f h=%.2f: %s vs %s", flip, yaw, tilt, m, u, h, world, expected);
                        }
                        // B: any pitch, rotation about the tangent through the low rail
                        for (final double pitch : pitches) {
                            final Vector3d t = new Vector3d(Math.cos(pitch) * Math.cos(yaw), Math.sin(pitch), Math.cos(pitch) * Math.sin(yaw));
                            final double up = TrackPath.pivotOffset(tilt);
                            final Vector3d q = new Vector3d(-Math.sin(yaw) * up, 0, Math.cos(yaw) * up);
                            for (int k = 0; k < 10; k++) {
                                final Vector3d v = new Vector3d(random.nextDouble() * 4 - 2, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
                                final Vector3d plain = unrolled(yaw, pitch, flip, m, v);
                                final Vector3d world = rolled(yaw, pitch, flip, tilt, m, v);
                                final Vector3d expected = new Vector3d(plain).sub(q).rotateAxis(Math.toRadians(tilt), t.x, t.y, t.z).add(q);
                                check(world.distance(expected) < 1e-9, "B flip=%s yaw=%.2f pitch=%.2f tilt=%.1f m=%.2f: %s vs %s", flip, yaw, pitch, tilt, m, world, expected);
                            }
                            // C: low rail fixed, high rail rises by 2·HALF_GAUGE·sin|φ|·cos(pitch-ish) > 0
                            final double g = TrackProfile.HALF_GAUGE;
                            // rail lines at path level: model y = m·cos(pitch) (see TrackRailBank.modelPivotY)
                            final double level = m - TrackRailBank.modelPivotY(m, pitch);
                            final Vector3d right = rolled(yaw, pitch, flip, tilt, m, model(g, level, 0, m, flip));
                            final Vector3d left = rolled(yaw, pitch, flip, tilt, m, model(-g, level, 0, m, flip));
                            final Vector3d rightPlain = unrolled(yaw, pitch, flip, m, model(g, level, 0, m, flip));
                            final Vector3d leftPlain = unrolled(yaw, pitch, flip, m, model(-g, level, 0, m, flip));
                            final Vector3d low = tilt > 0 ? right : left;
                            final Vector3d lowPlain = tilt > 0 ? rightPlain : leftPlain;
                            final Vector3d high = tilt > 0 ? left : right;
                            final Vector3d highPlain = tilt > 0 ? leftPlain : rightPlain;
                            check(low.distance(lowPlain) < 1e-9, "C low rail moved flip=%s yaw=%.2f pitch=%.2f tilt=%.1f", flip, yaw, pitch, tilt);
                            check(high.y > highPlain.y + 1e-6, "C high rail did not rise flip=%s yaw=%.2f pitch=%.2f tilt=%.1f", flip, yaw, pitch, tilt);
                            final double rise = high.y - highPlain.y;
                            final double want = 2 * g * Math.sin(Math.toRadians(Math.abs(tilt)));
                            check(Math.abs(rise - want) < 0.02 * want + 1e-9 || Math.abs(pitch) > 0.1, "C rise %.5f vs %.5f flip=%s pitch=%.2f tilt=%.1f", rise, want, flip, pitch, tilt);
                        }
                    }
                }
            }
        }

        // the flat rail's per-corner banking helper equals TrackPath.bank
        final double[] out = new double[2];
        for (int k = 0; k < 2000; k++) {
            final double u = random.nextDouble() * 4 - 2;
            final double h = random.nextDouble() * 2 - 1;
            final double tilt = random.nextDouble() * 90 - 45;
            TrackRailBank.bank(u, h, tilt, out);
            final double[] ref = TrackPath.bank(u, h, tilt);
            check(Math.abs(out[0] - ref[0]) < 1e-12 && Math.abs(out[1] - ref[1]) < 1e-12, "bank helper mismatch");
        }
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** Model-local coordinates of a point at lateral u (+ right), height h above the path, s along A→B (pitch-0 frame). */
    private static Vector3d model(double u, double h, double s, double m, boolean flip) {
        return new Vector3d(flip ? u : -u, m - h, flip ? s : -s);
    }

    /** MTR's matrix (origin = segment midpoint on the path at 0,0,0; modelYOffset m): T(0,m,0)·Ry·Rx. Jitter omitted (≈0.1°). */
    private static Matrix4d base(double yaw, double pitch, boolean flip, double m) {
        return new Matrix4d().translation(0, m, 0)
                .rotateY(Math.PI / 2 - yaw + (flip ? Math.PI : 0.0))
                .rotateX(Math.PI - pitch * (flip ? -1 : 1));
    }

    private static Vector3d unrolled(double yaw, double pitch, boolean flip, double m, Vector3d v) {
        return base(yaw, pitch, flip, m).transformPosition(new Vector3d(v));
    }

    private static Vector3d rolled(double yaw, double pitch, boolean flip, double tilt, double m, Vector3d v) {
        final double px = TrackRailBank.modelPivotX(tilt, flip);
        final double py = TrackRailBank.modelPivotY(m, pitch);
        // doubles throughout (the renderer passes floats to GraphicsHolder; that rounding is ~1e-7, irrelevant)
        final Matrix4d matrix = new Matrix4d().translation(0, m, 0)
                .rotateY(Math.PI / 2 - yaw + (flip ? Math.PI : 0.0))
                .rotateX(Math.PI - pitch * (flip ? -1 : 1))
                .translate(px, py, 0)
                .rotateZ(TrackRailBank.modelRoll(tilt, flip))
                .translate(-px, -py, 0);
        return matrix.transformPosition(new Vector3d(v));
    }

    private static void check(boolean ok, String format, Object... args) {
        checks++;
        if (!ok) {
            failures++;
            if (failures <= 20) {
                System.out.println("FAIL " + String.format(format, args));
            }
        }
    }
}
