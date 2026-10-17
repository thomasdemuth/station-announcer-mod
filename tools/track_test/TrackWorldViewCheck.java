import com.stationannouncer.client.track.TrackView3DCamera;
import com.stationannouncer.client.track.TrackWorldViewMath;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.Random;

/**
 * Offline check of the track editor World view's projection math ({@link TrackWorldViewMath}) against
 * matrices built EXACTLY the way Minecraft 1.20.4 builds them (JOML, as GameRenderer.renderWorld does:
 * projection = setPerspective(fov, fbW/fbH, 0.05, far); view = MatrixStack.multiply(Rx(pitch)) then
 * multiply(Ry(yaw + 180)); world points relative to the camera position; GUI = setOrtho(0, fbW/scale,
 * fbH/scale, 0)).
 *
 * <pre>
 * J=~/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.5/&#42;/joml-1.10.5.jar
 * javac -d OUT -cp $J src/main/java/com/stationannouncer/client/track/TrackWorldViewMath.java \
 *   src/main/java/com/stationannouncer/client/track/TrackView3DCamera.java
 * java -cp OUT:$J tools/track_test/TrackWorldViewCheck.java
 * </pre>
 */
public class TrackWorldViewCheck {
    static int checks;
    static int failures;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            if (failures < 40) {
                System.out.println("FAIL " + what);
            }
        }
    }

    static double[] cm(Matrix4f m) {
        final float[] f = new float[16];
        m.get(f);
        final double[] d = new double[16];
        for (int i = 0; i < 16; i++) {
            d[i] = f[i];
        }
        return d;
    }

    static Matrix4f mcView(float yaw, float pitch) {
        // MatrixStack.multiply(q) = positionMatrix.rotate(q); RotationAxis.POSITIVE_X.rotationDegrees = rotationX
        return new Matrix4f()
            .rotate(new Quaternionf().rotationX((float) Math.toRadians(pitch)))
            .rotate(new Quaternionf().rotationY((float) Math.toRadians(yaw + 180)));
    }

    /** Entity.getRotationVector(pitch, yaw). */
    static double[] look(double pitch, double yaw) {
        final double f = Math.toRadians(pitch);
        final double g = -Math.toRadians(yaw);
        return new double[]{Math.sin(g) * Math.cos(f), -Math.sin(f), Math.cos(g) * Math.cos(f)};
    }

    public static void main(String[] args) {
        final Random random = new Random(7);
        final int fbW = 2560;
        final int fbH = 1600;
        final double scale = 6;
        final double guiW = fbW / scale;
        final double guiH = fbH / scale;
        final float fovDeg = 70;
        final Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(fovDeg), (float) fbW / fbH, 0.05f, 512f);
        final double tanV = 1 / projection.m11();
        final double tanH = 1 / projection.m00();
        check(Math.abs(tanV - Math.tan(Math.toRadians(fovDeg) / 2)) < 1e-6, "tanV from m11");

        // 1. fallback view matrix == Minecraft's
        for (int i = 0; i < 200; i++) {
            final float yaw = (float) (random.nextDouble() * 720 - 360);
            final float pitch = (float) (random.nextDouble() * 180 - 90);
            final double[] mine = TrackWorldViewMath.viewMatrix(yaw, pitch);
            final double[] mc = cm(mcView(yaw, pitch));
            double err = 0;
            for (int k = 0; k < 16; k++) {
                err = Math.max(err, Math.abs(mine[k] - mc[k]));
            }
            check(err < 1e-5, "viewMatrix == MC view, yaw " + yaw + " pitch " + pitch + " err " + err);
        }

        // 2. orientation: what the player looks at is the centre; +x is LEFT when facing +z; up is up
        final TrackWorldViewMath frame = new TrackWorldViewMath();
        frame.set(cm(projection), cm(mcView(0, 0)), 100.5, 70, -40.5);
        final double[] out = new double[3];
        check(frame.project(100.5, 70, -30.5, guiW, guiH, out) && Math.abs(out[0] - guiW / 2) < 1e-6 && Math.abs(out[1] - guiH / 2) < 1e-6,
            "straight ahead = centre");
        check(frame.project(101.5, 70, -30.5, guiW, guiH, out) && out[0] < guiW / 2, "+x is left facing south");
        check(frame.project(100.5, 71, -30.5, guiW, guiH, out) && out[1] < guiH / 2, "+y is up");
        check(!frame.project(100.5, 70, -50.5, guiW, guiH, out), "behind = false");
        for (int i = 0; i < 300; i++) {
            final float yaw = (float) (random.nextDouble() * 360 - 180);
            final float pitch = (float) (random.nextDouble() * 170 - 85);
            frame.set(cm(projection), cm(mcView(yaw, pitch)), 0, 64, 0);
            final double[] l = look(pitch, yaw);
            check(frame.project(l[0] * 20, 64 + l[1] * 20, l[2] * 20, guiW, guiH, out)
                && Math.abs(out[0] - guiW / 2) < 0.01 && Math.abs(out[1] - guiH / 2) < 0.01,
                "look vector = centre " + yaw + "/" + pitch + " at " + out[0] + "," + out[1]);
        }

        // 3. clip() == JOML P·V·(p − cam)
        for (int i = 0; i < 300; i++) {
            final float yaw = (float) (random.nextDouble() * 360 - 180);
            final float pitch = (float) (random.nextDouble() * 170 - 85);
            final Matrix4f view = mcView(yaw, pitch);
            frame.set(cm(projection), cm(view), 10, 20, 30);
            final double px = random.nextDouble() * 80 - 30;
            final double py = random.nextDouble() * 80 - 20;
            final double pz = random.nextDouble() * 80 - 10;
            final Vector4f v = new Vector4f((float) (px - 10), (float) (py - 20), (float) (pz - 30), 1);
            new Matrix4f(projection).mul(view).transform(v);
            final double[] c = new double[4];
            frame.clip(px, py, pz, c);
            final double err = Math.max(Math.max(Math.abs(c[0] - v.x), Math.abs(c[1] - v.y)), Math.max(Math.abs(c[2] - v.z), Math.abs(c[3] - v.w)));
            check(err < 1e-3 * Math.max(1, Math.abs(v.w)), "clip == JOML err " + err);
        }

        int unreachable = 0;
        // 4. aim(): the target lands exactly on the pane centre wherever the pane is — as long as a
        //    roll-free camera can do it (|elevation| + the pane's vertical angle below 90°: looking
        //    straight down, the target can only appear BELOW the screen centre)
        for (int i = 0; i < 2000; i++) {
            final double ex = random.nextDouble() * 200 - 100;
            final double ey = 60 + random.nextDouble() * 80;
            final double ez = random.nextDouble() * 200 - 100;
            final double dist = 3 + random.nextDouble() * 300;
            final double elev = Math.toRadians(-85 + random.nextDouble() * 170);
            final double az = random.nextDouble() * Math.PI * 2;
            final double tx = ex + dist * Math.cos(elev) * Math.cos(az);
            final double ty = ey + dist * Math.sin(elev);
            final double tz = ez + dist * Math.cos(elev) * Math.sin(az);
            // pane centre anywhere in the middle 80 % of the window
            final double ndcX = random.nextDouble() * 1.6 - 0.8;
            final double ndcY = random.nextDouble() * 1.6 - 0.8;
            final double[] aim = TrackWorldViewMath.aim(tx - ex, ty - ey, tz - ez, ndcX, ndcY, tanH, tanV);
            frame.set(cm(projection), cm(mcView((float) aim[0], (float) aim[1])), ex, ey, ez);
            final boolean ok = frame.project(tx, ty, tz, guiW, guiH, out);
            if (!TrackWorldViewMath.reachable(elev, ndcX, ndcY, tanH, tanV)) {
                // too steep for an off-centre pixel without roll: must still be in front of the camera
                unreachable++;
                check(ok, "unreachable aim still in front");
                continue;
            }
            final double wantX = (ndcX + 1) / 2 * guiW;
            final double wantY = (1 - ndcY) / 2 * guiH;
            check(ok && Math.abs(out[0] - wantX) < 0.02 && Math.abs(out[1] - wantY) < 0.02,
                "aim → pane centre: got " + (ok ? out[0] + "," + out[1] : "behind") + " want " + wantX + "," + wantY + " elev " + Math.toDegrees(elev));
        }

        check(unreachable < 700, "most random aims are reachable: " + unreachable + " were not");
        // the editor clamps its orbit pitch to maxDownAngle − 1°: always reachable then
        for (int i = 0; i < 2000; i++) {
            final double ndcX = random.nextDouble() * 1.6 - 0.8;
            final double ndcY = random.nextDouble() * 1.6 - 0.8;
            final double limit = TrackWorldViewMath.maxDownAngle(ndcX, ndcY, tanH, tanV) - 1;
            final double down = Math.toRadians(Math.min(limit, random.nextDouble() * 89));
            final double az = random.nextDouble() * Math.PI * 2;
            final double dx = Math.cos(down) * Math.cos(az) * 40;
            final double dy = -Math.sin(down) * 40;
            final double dz = Math.cos(down) * Math.sin(az) * 40;
            check(TrackWorldViewMath.reachable(-down, ndcX, ndcY, tanH, tanV), "clamped pitch reachable");
            final double[] aim = TrackWorldViewMath.aim(dx, dy, dz, ndcX, ndcY, tanH, tanV);
            frame.set(cm(projection), cm(mcView((float) aim[0], (float) aim[1])), 0, 100, 0);
            final boolean ok = frame.project(dx, 100 + dy, dz, guiW, guiH, out);
            // float matrices (as in game): ~0.1 GUI px at the window's edge
            check(ok && Math.abs(out[0] - (ndcX + 1) / 2 * guiW) < 0.15 && Math.abs(out[1] - (1 - ndcY) / 2 * guiH) < 0.15,
                "clamped orbit centred on the pane (down " + Math.toDegrees(down) + ", limit " + limit + ") got "
                    + out[0] + "," + out[1] + " want " + (ndcX + 1) / 2 * guiW + "," + (1 - ndcY) / 2 * guiH + " ndc " + ndcX + "," + ndcY);
        }

        // 5. groundAt round trip (and project of the hit = the pixel)
        int roundTrips = 0;
        for (int i = 0; i < 2000; i++) {
            final float yaw = (float) (random.nextDouble() * 360 - 180);
            final float pitch = (float) (10 + random.nextDouble() * 75);
            final double cx = random.nextDouble() * 1000 - 500;
            final double cz = random.nextDouble() * 1000 - 500;
            frame.set(cm(projection), cm(mcView(yaw, pitch)), cx, 120, cz);
            final double sx = random.nextDouble() * guiW;
            final double sy = random.nextDouble() * guiH;
            final double plane = 64 + random.nextDouble() * 10;
            final double[] hit = new double[2];
            if (frame.groundAt(sx, sy, guiW, guiH, plane, 5000, hit)) {
                roundTrips++;
                final boolean ok = frame.project(hit[0], plane, hit[1], guiW, guiH, out);
                check(ok && Math.abs(out[0] - sx) < 0.01 && Math.abs(out[1] - sy) < 0.01,
                    "groundAt round trip " + sx + "," + sy + " → " + (ok ? out[0] + "," + out[1] : "behind"));
            }
        }
        check(roundTrips > 1000, "most rays hit the ground: " + roundTrips);
        frame.set(cm(projection), cm(mcView(0, -30)), 0, 100, 0); // looking UP: the ground is behind every ray at the top
        check(!frame.groundAt(guiW / 2, 0, guiW, guiH, 64, 5000, new double[2]), "ray above the horizon misses the ground");

        // 6. the reused orbit camera agrees with the real game camera (centred pane): same pixels
        for (int i = 0; i < 500; i++) {
            final TrackView3DCamera orbit = new TrackView3DCamera();
            orbit.yaw = random.nextDouble() * Math.PI * 2;
            orbit.pitch = Math.toRadians(-10 + random.nextDouble() * 95);
            orbit.distance = 5 + random.nextDouble() * 200;
            orbit.tx = random.nextDouble() * 20 - 10;
            orbit.ty = random.nextDouble() * 4;
            orbit.tz = random.nextDouble() * 20 - 10;
            orbit.update();
            final double focal = guiH / 2 / tanV;
            final double virtualH = focal / TrackView3DCamera.focal(1);
            final double virtualW = virtualH * guiW / guiH;
            // eye/target in world (focus 1000, 64, -2000)
            final double fx = 1000, fy = 64, fz = -2000;
            final double[] eye = {fx + orbitCam(orbit, 0), fy + orbitCam(orbit, 1), fz + orbitCam(orbit, 2)};
            final double[] aim = TrackWorldViewMath.aim(orbit.tx + fx - eye[0], orbit.ty + fy - eye[1], orbit.tz + fz - eye[2], 0, 0, tanH, tanV);
            frame.set(cm(projection), cm(mcView((float) aim[0], (float) aim[1])), eye[0], eye[1], eye[2]);
            for (int k = 0; k < 5; k++) {
                final double px = orbit.tx + random.nextDouble() * 30 - 15;
                final double py = orbit.ty + random.nextDouble() * 6 - 3;
                final double pz = orbit.tz + random.nextDouble() * 30 - 15;
                final double[] a = new double[3];
                final double[] b = new double[3];
                final boolean okA = orbit.project(px, py, pz, virtualW, virtualH, a);
                final boolean okB = frame.project(px + fx, py + fy, pz + fz, guiW, guiH, b);
                if (okA && okB && a[2] > 1) {
                    final double ax = a[0] - virtualW / 2;
                    final double ay = a[1] - virtualH / 2;
                    final double bx = b[0] - guiW / 2;
                    final double by = b[1] - guiH / 2;
                    check(Math.abs(ax - bx) < 0.05 && Math.abs(ay - by) < 0.05,
                        "orbit model == game camera: " + ax + "," + ay + " vs " + bx + "," + by);
                }
            }
        }

        // 7. framing through the virtual viewport keeps every point inside the pane (real camera)
        for (int i = 0; i < 300; i++) {
            final int n = 30;
            final float[] pts = new float[n * 3];
            final double len = 10 + random.nextDouble() * 200;
            final double heading = random.nextDouble() * Math.PI * 2;
            for (int k = 0; k < n; k++) {
                final double s = len * k / (n - 1);
                pts[k * 3] = (float) (Math.cos(heading) * s + Math.sin(s / 15) * 6);
                pts[k * 3 + 1] = (float) (random.nextDouble() * 3);
                pts[k * 3 + 2] = (float) (Math.sin(heading) * s);
            }
            final double paneW = 120 + random.nextDouble() * 250;
            final double paneH = 80 + random.nextDouble() * 160;
            // realistic editor panes: centre within ±25 % of the window centre
            final double paneCX = guiW / 2 + (random.nextDouble() - 0.5) * Math.min(guiW - paneW, guiW * 0.25);
            final double paneCY = guiH / 2 + (random.nextDouble() - 0.5) * Math.min(guiH - paneH, guiH * 0.25);
            final double focal = guiH / 2 / tanV;
            final double virtualH = focal / TrackView3DCamera.focal(1);
            final double virtualW = virtualH * paneW / paneH;
            final TrackView3DCamera orbit = new TrackView3DCamera();
            orbit.defaultAngle(heading);
            orbit.fitPoints(pts, n, virtualW, virtualH, 0.84 * paneH / virtualH);
            TrackWorldViewMath.fitToPane(orbit, pts, n, paneCX / guiW * 2 - 1, 1 - paneCY / guiH * 2, paneW, paneH, guiW, guiH, tanH, tanV, 0.84);
            final double[] eye = {orbitCam(orbit, 0), orbitCam(orbit, 1), orbitCam(orbit, 2)};
            final double ndcX = paneCX / guiW * 2 - 1;
            final double ndcY = 1 - paneCY / guiH * 2;
            final double[] aim = TrackWorldViewMath.aim(orbit.tx - eye[0], orbit.ty - eye[1], orbit.tz - eye[2], ndcX, ndcY, tanH, tanV);
            frame.set(cm(projection), cm(mcView((float) aim[0], (float) aim[1])), eye[0], eye[1], eye[2]);
            for (int k = 0; k < n; k++) {
                final boolean ok = frame.project(pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2], guiW, guiH, out);
                // the pane is an off-axis window, so allow a little slack over the 84 % margin
                check(ok && Math.abs(out[0] - paneCX) <= paneW * 0.45 && Math.abs(out[1] - paneCY) <= paneH * 0.45,
                    "fit keeps point in pane: " + (ok ? (out[0] - paneCX) + "," + (out[1] - paneCY) : "behind") + " pane " + paneW + "x" + paneH);
            }
        }

        System.out.println("TrackWorldViewCheck: " + checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** Camera position of a TrackView3DCamera (package-private fields) via its public projection maths. */
    static double orbitCam(TrackView3DCamera orbit, int axis) {
        final double ox = Math.cos(orbit.pitch) * Math.cos(orbit.yaw);
        final double oy = Math.sin(orbit.pitch);
        final double oz = Math.cos(orbit.pitch) * Math.sin(orbit.yaw);
        return switch (axis) {
            case 0 -> orbit.tx + orbit.distance * ox;
            case 1 -> orbit.ty + orbit.distance * oy;
            default -> orbit.tz + orbit.distance * oz;
        };
    }
}
