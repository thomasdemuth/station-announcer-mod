import com.stationannouncer.client.track.TrackView3DCamera;
import com.stationannouncer.client.track.TrackView3DProjector;
import com.stationannouncer.client.track.TrackView3DScene;
import com.stationannouncer.track.TrackEdit;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackSolver;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Offline look at the track editor's 3D pane: builds the REAL TrackView3DScene / Camera / Projector
 * on a banked S-curve plus two context tracks and rasterises the projector's depth-carrying quads
 * into a z-buffer (larger z = nearer, like the GUI), then writes a PNG.
 *
 * <pre>
 * javac -d OUT src/main/java/com/stationannouncer/track/*.java \
 *   src/main/java/com/stationannouncer/client/track/TrackView3D{Scene,Camera,Projector}.java
 * java -cp OUT tools/track_test/TrackView3DCheck.java out.png
 * </pre>
 */
public class TrackView3DCheck {
    static int failures;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static TrackProfile base(long ax, long az, long bx, long bz, double headingA, double headingB) {
        final TrackProfile p = new TrackProfile();
        p.ax = ax;
        p.ay = 64;
        p.az = az;
        p.bx = bx;
        p.by = 64;
        p.bz = bz;
        p.headingA = headingA;
        p.headingB = headingB;
        return p;
    }

    public static void main(String[] args) throws Exception {
        final String out = args.length > 0 ? args[0] : "view3d_check.png";

        // selected: S-curve (right then left) with easements and suggested cant
        TrackProfile s = base(0, 0, 80, 22, 0, 0);
        s.pieces.add(TrackProfile.Piece.straight(10));
        s.pieces.add(TrackProfile.Piece.arc(45, 15, 1));
        s.pieces.add(TrackProfile.Piece.straight(5));
        s.pieces.add(TrackProfile.Piece.arc(45, 15, -1));
        s.pieces.add(TrackProfile.Piece.straight(10));
        s = TrackSolver.solve(s).profile();
        s = TrackEdit.addEasements(s, 8);
        s = TrackSolver.solve(s).profile();
        s.cantCap = 15;
        TrackPath selected = TrackPath.build(s, "QUADRATIC", 0);
        s = TrackEdit.suggestCant(s, selected, 90, 10);
        selected = TrackPath.build(s, "QUADRATIC", 0);
        check(selected.closes(), "sample S-curve closes");

        // context: a straight arriving at A from the west, and a sharp right turn leaving A
        final TrackProfile west = base(-40, 0, 0, 0, 0, 0);
        west.pieces.add(TrackProfile.Piece.straight(40));
        final TrackPath westPath = TrackPath.build(west, "QUADRATIC", 0);
        TrackProfile turn = base(0, 0, 30, 30, 0, Math.PI / 2);
        turn.pieces.add(TrackProfile.Piece.arc(30, 47, 1));
        turn = TrackSolver.solve(turn).profile();
        final TrackPath turnPath = TrackPath.build(turn, "QUADRATIC", 0);

        // checks on the frame convention: in a right-hand curve with positive tilt the RIGHT rail is low
        final double sArc = selected.pieceStart(2) + selected.pieceLength(2) / 2;
        check(selected.curvature(sArc) > 0 && selected.tilt(sArc) > 0, "first arc is right-hand with positive tilt");

        final TrackView3DScene.Palette palette = new TrackView3DScene.Palette();
        final TrackView3DScene scene = TrackView3DScene.build(List.of(westPath, turnPath), selected, 0.5, 64, 0.5, palette);
        scene.setMarker(sArc);
        check(scene.markerHead != null && scene.markerHead[0] > 0, "marker head exists");
        System.out.println("quads " + scene.mesh.quadCount + " lines " + scene.mesh.lineCount + " fitR " + scene.fitRadius);

        // panel 1: big pane, default fit
        final BufferedImage big = panel(scene, 400, 260, 2, cam -> { cam.defaultAngle(scene.chordHeading); cam.fitPoints(scene.fitPoints, scene.fitPointCount, 400, 260, 0.86); }, "default fit 400x260 @2");
        // panel 2: small pane, default fit
        final BufferedImage small = panel(scene, 180, 150, 3, cam -> { cam.defaultAngle(scene.chordHeading); cam.fitPoints(scene.fitPoints, scene.fitPointCount, 180, 150, 0.86); }, "default fit 180x150 @3");
        // panel 3: close-up looking along the track into the right-hand arc (travel direction)
        final double[] p = new double[5];
        selected.pointAt(sArc, p);
        final BufferedImage close = panel(scene, 180, 150, 3, cam -> {
            cam.tx = p[0] - 0.5;
            cam.ty = p[1] - 64 + TrackView3DScene.RAIL_LIFT;
            cam.tz = p[2] - 0.5;
            cam.yaw = p[3] + Math.PI;  // camera behind, looking along travel
            cam.pitch = Math.toRadians(10);
            cam.distance = 9;
            cam.update();
        }, "along travel into the right curve");

        // panel 4: a draft that does not reach B (red rails + banner in game), small pane
        final TrackProfile broken = s.copy();
        broken.pieces.get(broken.pieces.size() - 1).length += 6;
        final TrackPath brokenPath = TrackPath.build(broken, "QUADRATIC", 0);
        check(!brokenPath.closes(), "lengthened draft no longer closes");
        final TrackView3DScene brokenScene = TrackView3DScene.build(List.of(westPath, turnPath), brokenPath, 0.5, 64, 0.5, palette);
        check(!brokenScene.selectedCloses, "scene knows it does not close");
        final BufferedImage red = panel(brokenScene, 180, 150, 3, cam -> {
            cam.defaultAngle(brokenScene.chordHeading);
            cam.pitch = Math.toRadians(70);
            cam.update();
            cam.fitPoints(brokenScene.fitPoints, brokenScene.fitPointCount, 180, 150, 0.86);
        }, "not closing, from above");

        // camera sanity: zoom keeps the cursor point fixed
        final TrackView3DCamera cam = new TrackView3DCamera();
        cam.fit(0, 0, 0, 20, 1.2, 0.3);
        final double[] before = new double[3];
        final double[] after = new double[3];
        final double[] q = new double[2];
        // the ground point under pixel (40, 90) of a 180x150 pane
        cam.zoom(1, 40, 90, 180, 150);
        cam.project(5, 0, 3, 180, 150, before);
        cam.zoom(0.6, before[0], before[1], 180, 150);
        cam.project(5, 0, 3, 180, 150, after);
        check(Math.abs(before[0] - after[0]) < 0.01 && Math.abs(before[1] - after[1]) < 0.01,
            "zoom to cursor keeps the point under the cursor " + before[0] + "," + before[1] + " vs " + after[0] + "," + after[1]);

        final BufferedImage sheet = new BufferedImage(big.getWidth() + small.getWidth() + close.getWidth() + 40, big.getHeight() + red.getHeight() + 30, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0x0B0B0D));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.drawImage(big, 10, 10, null);
        g.drawImage(small, 20 + big.getWidth(), 10, null);
        g.drawImage(close, 30 + big.getWidth() + small.getWidth(), 10, null);
        g.drawImage(red, 10, 20 + big.getHeight(), null);
        g.dispose();
        ImageIO.write(sheet, "png", new File(out));
        System.out.println(failures == 0 ? "OK, wrote " + out : failures + " failures, wrote " + out);
    }

    interface CamSetup {
        void apply(TrackView3DCamera cam);
    }

    static BufferedImage panel(TrackView3DScene scene, int w, int h, int guiScale, CamSetup setup, String title) {
        final int ss = 2;
        final int pw = w * guiScale * ss;
        final int ph = h * guiScale * ss;
        final int[] color = new int[pw * ph];
        final float[] depth = new float[pw * ph];
        java.util.Arrays.fill(color, scene.palette.background);
        java.util.Arrays.fill(depth, 0);
        final TrackView3DCamera cam = new TrackView3DCamera();
        setup.apply(cam);
        final float k = guiScale * ss;
        final TrackView3DProjector.Sink sink = (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, argb) -> {
            tri(color, depth, pw, ph, x0 * k, y0 * k, z0, x1 * k, y1 * k, z1, x2 * k, y2 * k, z2, argb);
            tri(color, depth, pw, ph, x0 * k, y0 * k, z0, x2 * k, y2 * k, z2, x3 * k, y3 * k, z3, argb);
        };
        final TrackView3DProjector projector = new TrackView3DProjector();
        projector.draw(scene.mesh, cam, 0, 0, w, h, 1f / guiScale, sink);
        projector.draw(scene.sleepers, cam, 0, 0, w, h, 1f / guiScale, TrackView3DScene.sleeperMaxDepth(h, guiScale), sink);
        projector.draw(scene.markerMesh, cam, 0, 0, w, h, 1f / guiScale, sink);

        final BufferedImage image = new BufferedImage(w * guiScale, h * guiScale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * guiScale; y++) {
            for (int x = 0; x < w * guiScale; x++) {
                int r = 0, gg = 0, b = 0;
                for (int j = 0; j < ss; j++) {
                    for (int i = 0; i < ss; i++) {
                        final int c = color[(y * ss + j) * pw + x * ss + i];
                        r += (c >> 16) & 255;
                        gg += (c >> 8) & 255;
                        b += c & 255;
                    }
                }
                final int n = ss * ss;
                image.setRGB(x, y, ((r / n) << 16) | ((gg / n) << 8) | (b / n));
            }
        }
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 6 * guiScale));
        // labels as the in-game pane draws them
        final double[] o = new double[3];
        g.setColor(new Color(0xEDEDF0));
        if (scene.labelA != null && cam.project(scene.labelA[0], scene.labelA[1], scene.labelA[2], w, h, o)) {
            g.drawString("A", (float) o[0] * guiScale - 2 * guiScale, (float) o[1] * guiScale);
        }
        if (scene.labelB != null && cam.project(scene.labelB[0], scene.labelB[1], scene.labelB[2], w, h, o)) {
            g.drawString("B", (float) o[0] * guiScale - 2 * guiScale, (float) o[1] * guiScale);
        }
        if (scene.markerHead != null && cam.project(scene.markerHead[0], scene.markerHead[1], scene.markerHead[2], w, h, o)) {
            g.setColor(new Color(0xFFC53D));
            g.drawString(String.format("%.1f°", scene.markerTilt), (float) o[0] * guiScale + 4 * guiScale, (float) o[1] * guiScale);
        }
        g.setColor(new Color(0x63636E));
        g.drawString(title, 4 * guiScale, (h - 4) * guiScale);
        g.dispose();
        return image;
    }

    static void tri(int[] color, float[] depth, int w, int h, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        final float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        final int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        final int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        final int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        final int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        for (int y = minY; y <= maxY; y++) {
            final float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                final float px = x + 0.5f;
                final float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area;
                final float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area;
                final float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                final float z = w0 * z0 + w1 * z1 + w2 * z2;
                final int index = y * w + x;
                if (z >= depth[index]) {   // GUI depth func is LEQUAL on depth = nearer-or-equal wins
                    depth[index] = z;
                    color[index] = argb;
                }
            }
        }
    }
}
