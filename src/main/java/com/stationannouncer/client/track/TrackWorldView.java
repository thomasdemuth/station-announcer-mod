package com.stationannouncer.client.track;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.track.TrackPath;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editor's World view: a pane that shows the REAL world (blocks, terrain, the live rail preview)
 * from an orbit camera around a focus point, like the sign editor's "In world" view.
 *
 * <h2>How it works</h2>
 * The world is drawn full-window by the game from a detached camera ({@link TrackWorldViewCamera});
 * the editor paints its own panels around the pane and leaves the pane open, so the world shows
 * through it. The camera is aimed so the orbit target sits in the middle of the PANE. The GOAL
 * orbit (yaw/pitch/distance/target, zoom-to-cursor, framing) reuses {@link TrackView3DCamera}'s
 * math through a "virtual viewport" whose focal length equals the game's; input is scaled to the pane
 * (orbit 160° per pane width, ground-plane grab pan, 10 % per wheel notch). The SHOWN pose eases
 * toward the goal with critically damped springs (~130 ms; a calmer ~0.9 s fly-in from the player's
 * own view on open), and the goal is kept inside the chunks loaded around the player — see
 * {@link TrackWorldViewCamera} for why sections used to pop (occlusion culling is off while open). On top of the world this pane draws only thin overlay lines (the selected track's
 * banked centreline, followers in a secondary accent), the hovered marker, A/B badges and a hint.
 *
 * <h2>As a {@link TrackViewport}</h2>
 * {@link #project}/{@link #groundAt} run through the matrices the world was actually rendered with
 * this frame ({@link TrackWorldViewMath}, captured at {@code WorldRenderEvents.START}): projection
 * incl. FOV and its multipliers, camera rotation, camera position — so overlay handles land exactly on
 * what is on screen. World y = path height like {@code TrackPath.pointAt}; both methods add
 * {@link #RAIL_TOP} (MTR's flat rail surface above the path height), so a pointAt() handle sits on the
 * rail and round trips are exact. Screen = GUI px of the whole screen (the pane offset is implicit:
 * the world fills the window).
 *
 * <h2>Embedding (call order)</h2>
 * <ol>
 *   <li>{@link #open()} when the World tab becomes visible (also in the screen's {@code init()} if the
 *   tab is already showing — returning from a child screen). Optional: {@link #render} opens lazily.</li>
 *   <li>Every frame: {@link #setScene} (identity-compared, cheap), {@link #setMarker}, then
 *   {@link #render} with the pane rectangle — and the screen must NOT paint anything over the pane
 *   (override {@code renderBackground} to paint only around it: in 1.20.4 {@code Screen.render}
 *   calls {@code renderBackground}, which would dim the world).</li>
 *   <li>Mouse: after the editor's own handle hit tests, forward the four events (true = consumed).</li>
 *   <li>{@link #close()} when the tab is hidden and from the screen's {@code removed()}. Forgetting it
 *   is survivable: a watchdog restores everything once the pane stops being rendered for
 *   1.5 s, the screen is gone, or the world changes.</li>
 * </ol>
 * Controls: left-drag orbit, right/middle/shift-drag pan, wheel zoom to the cursor, double-click
 * re-frames.
 */
@Environment(EnvType.CLIENT)
public final class TrackWorldView implements TrackViewport {
    /** MTR 4.0.1 draws the flat rail this far above the path height ({@code RenderRails}: 0.065625). */
    public static final double RAIL_TOP = 0.065625;
    private static final long DOUBLE_CLICK_MS = 300;
    private static final double MAX_SAMPLES = 1500;
    private static final int LABEL_BACK = 0xC0101013;
    private static final double MIN_FIT_DISTANCE = 6;
    /** Spring stiffness (1/s) of the eased camera: critically damped, ~95 % settled in ≈ 4.7 / ω. */
    private static final double OMEGA = 36;
    /** Calmer spring for the fly-in from the player's view. */
    private static final double OMEGA_FLY_IN = 7;
    private static final double FLY_IN_SECONDS = 0.9;
    /** Orbit: radians per pane width / height of drag. */
    private static final double ORBIT_PER_PANE_W = Math.toRadians(160);
    private static final double ORBIT_PER_PANE_H = Math.toRadians(80);
    /** Zoom factor per wheel notch, and the most notches one event may carry (trackpads send bursts). */
    private static final double ZOOM_PER_NOTCH = 0.9;
    private static final double MAX_NOTCHES_PER_EVENT = 2;

    private final TrackWorldViewCamera game = new TrackWorldViewCamera();
    /** GOAL pose: what the user steers (orbit / pan / zoom / fit write here). */
    private final TrackView3DCamera orbit = new TrackView3DCamera();
    /** SHOWN pose (eased toward the goal every frame); {@link #place} uses this. */
    private final Spring shownYaw = new Spring();
    private final Spring shownPitch = new Spring();
    private final Spring shownLogDistance = new Spring();
    private final Spring shownTx = new Spring();
    private final Spring shownTy = new Spring();
    private final Spring shownTz = new Spring();
    private final Spring lookBlend = new Spring();
    private boolean shownValid;
    private long lastFrameNanos;
    private long openedNanos;

    // scene (identity-compared)
    private final List<TrackPath> lastContext = new ArrayList<>();
    private final List<TrackPath> lastFollowers = new ArrayList<>();
    private TrackPath lastSelected;
    private String lastSelectedKey;
    private double focusX = Double.NaN;
    private double focusY = Double.NaN;
    private double focusZ = Double.NaN;
    private boolean needsFit = true;
    /** Overlay polylines: packed absolute world xyz (banked centre at the rail top, RAIL_TOP included). */
    private double[] selectedLine;
    private boolean selectedCloses = true;
    private final List<double[]> followerLines = new ArrayList<>();
    private final List<Boolean> followerCloses = new ArrayList<>();
    /** Default framing points, relative to the focus. */
    private float[] fitPoints = new float[0];
    private int fitPointCount;
    private double chordHeading = Double.NaN;
    private double marker = Double.NaN;
    private boolean showOverlay = true;
    private boolean clipToTerrain = true;
    /** Max orbit pitch (radians) for the current pane position, see {@link TrackWorldViewMath#maxDownAngle}. */
    private double pitchLimit = Math.toRadians(89);

    // pane from the last render
    private int paneX;
    private int paneY;
    private int paneW;
    private int paneH;

    private int dragButton = -1;
    private boolean dragPan;
    private long lastClickTime;
    private double lastClickX;
    private double lastClickY;

    // ------------------------------------------------------------------ lifecycle

    /** Takes the game camera (fly-in from the player's view). Idempotent. False without a world. */
    public boolean open() {
        final boolean was = game.active();
        final boolean ok = game.open();
        if (ok && !was) {
            shownValid = false; // re-seed the eased pose from the player's view on the next render
        }
        return ok;
    }

    /** Gives the camera, HUD, perspective and culling back. Idempotent; call from {@code removed()}. */
    public void close() {
        dragButton = -1;
        shownValid = false;
        game.close();
    }

    public boolean isOpen() {
        return game.active();
    }

    /** Overlay centrelines on/off (they help when terrain hides the rails). Default on. */
    public void setOverlay(boolean on) {
        showOverlay = on;
    }

    /** Pull the camera in front of terrain between it and the target (vanilla F5 style). Default on. */
    public void setClipToTerrain(boolean on) {
        clipToTerrain = on;
    }

    // ------------------------------------------------------------------ scene

    /** Same contract as {@link TrackView3D#setScene(List, TrackPath, double, double, double)}. */
    public void setScene(List<TrackPath> context, TrackPath selected, double fx, double fy, double fz) {
        setScene(context, selected, List.of(), fx, fy, fz);
    }

    /**
     * @param context   other tracks near the node: only used to frame the view when nothing is selected
     *                  (the real rails are in the world already)
     * @param selected  the draft: overlay centreline (accent; red when it does not reach B)
     * @param followers tracks that reshape with it: overlay in the secondary accent
     * @param fx        orbit focus = node centre in path coordinates (x + 0.5, y, z + 0.5)
     */
    public void setScene(List<TrackPath> context, TrackPath selected, List<TrackPath> followers, double fx, double fy, double fz) {
        final List<TrackPath> safeContext = context == null ? List.of() : context;
        final List<TrackPath> safeFollowers = followers == null ? List.of() : followers;
        final boolean focusMoved = !(fx == focusX && fy == focusY && fz == focusZ);
        if (!focusMoved && selected == lastSelected && same(safeContext, lastContext) && same(safeFollowers, lastFollowers)) {
            return;
        }
        final String key = selected == null || selected.profile == null ? null : selected.profile.key();
        if (focusMoved || key != null && !key.equals(lastSelectedKey) || lastSelected == null && selected != null) {
            needsFit = true;
        }
        lastContext.clear();
        lastContext.addAll(safeContext);
        lastFollowers.clear();
        lastFollowers.addAll(safeFollowers);
        lastSelected = selected;
        lastSelectedKey = key;
        focusX = fx;
        focusY = fy;
        focusZ = fz;

        selectedLine = selected == null ? null : sample(selected);
        selectedCloses = selected == null || selected.closes();
        followerLines.clear();
        followerCloses.clear();
        for (final TrackPath follower : safeFollowers) {
            if (follower != null && follower != selected) {
                followerLines.add(sample(follower));
                followerCloses.add(follower.closes());
            }
        }
        buildFitPoints(safeContext, selected);
    }

    /** Highlights distance {@code s} along the selected track; NaN = none. */
    public void setMarker(double s) {
        marker = s;
    }

    /** Re-frames on the next render (double-click does this). */
    public void resetView() {
        needsFit = true;
    }

    public boolean isDragging() {
        return dragButton != -1;
    }

    // ------------------------------------------------------------------ render

    public void render(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY, float delta) {
        paneX = x;
        paneY = y;
        paneW = width;
        paneH = height;
        final MinecraftClient client = MinecraftClient.getInstance();
        final TextRenderer font = client.textRenderer;
        if (width < 8 || height < 8) {
            return;
        }
        if (!open()) {
            context.fill(x, y, x + width, y + height, 0xFF111114);
            final String text = "No world";
            context.drawText(font, text, x + (width - font.getWidth(text)) / 2, y + (height - 8) / 2, FlatUi.TEXT_FAINT, false);
            return;
        }
        final double guiW = guiWidth();
        final double guiH = guiHeight();
        final double tanV = tanHalfV();
        final double tanH = tanHalfH(tanV);
        final double focal = guiH / 2 / tanV; // GUI px per unit of view slope
        final double virtualH = focal / TrackView3DCamera.focal(1);

        final double ndcX = (x + width / 2.0) / guiW * 2 - 1;
        final double ndcY = 1 - (y + height / 2.0) / guiH * 2;
        // a roll-free camera cannot put a steeply-below target in an off-centre pane: stop the orbit short
        pitchLimit = Math.toRadians(Math.max(20, Math.min(89, TrackWorldViewMath.maxDownAngle(ndcX, ndcY, tanH, tanV) - 1)));
        if (needsFit && Double.isFinite(focusX)) {
            orbit.defaultAngle(chordHeading);
            if (fitPointCount > 0) {
                final double virtualW = virtualH * width / height;
                orbit.fitPoints(fitPoints, fitPointCount, virtualW, virtualH, 0.84 * height / virtualH);
                // the camera is turned toward the pane: refine through the real (skewed) projection
                TrackWorldViewMath.fitToPane(orbit, fitPoints, fitPointCount, ndcX, ndcY, width, height, guiW, guiH, tanH, tanV, 0.84);
            }
            orbit.distance = Math.max(orbit.distance, MIN_FIT_DISTANCE);
            orbit.update();
            needsFit = false;
        }
        clampOrbit();

        if (Double.isFinite(focusX)) {
            game.railLevel = focusY;
            updateShown();
            // orbit space = world − focus (world y: the fit points already include the rail top)
            final double yaw = shownYaw.x;
            final double pitch = shownPitch.x;
            final double distance = Math.exp(shownLogDistance.x);
            final Vec3d target = new Vec3d(focusX + shownTx.x, focusY + shownTy.x, focusZ + shownTz.x);
            final Vec3d eye = target.add(Math.cos(pitch) * Math.cos(yaw) * distance, Math.sin(pitch) * distance,
                Math.cos(pitch) * Math.sin(yaw) * distance);
            game.place(target, eye, ndcX, ndcY, tanH, tanV, lookBlend.x >= 0.999 ? 1 : lookBlend.x);
        }

        context.draw();
        context.enableScissor(x, y, x + width, y + height);
        final TrackWorldViewMath frame = TrackWorldViewCamera.FRAME;
        final double pixel = 1 / Math.max(1, client.getWindow().getScaleFactor());
        if (frame.valid() && showOverlay) {
            final Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
            final VertexConsumer buffer = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
            for (int i = 0; i < followerLines.size(); i++) {
                final int color = followerCloses.get(i) ? TrackView3D.FOLLOWER : FlatUi.DANGER;
                polyline(buffer, matrix, frame, followerLines.get(i), guiW, guiH, (float) Math.max(0.5, 2.5 * pixel), (color & 0xFFFFFF) | 0xC8000000);
            }
            if (selectedLine != null) {
                final int color = selectedCloses ? FlatUi.ACCENT : FlatUi.DANGER;
                polyline(buffer, matrix, frame, selectedLine, guiW, guiH, (float) Math.max(0.6, 3 * pixel), (color & 0xFFFFFF) | 0xD8000000);
            }
            context.draw();
        }
        if (frame.valid()) {
            drawLabels(context, font, frame, guiW, guiH);
            drawCompass(context, font, frame, guiW, guiH, x + width - 13, y + 13, pixel);
        }
        if (lastSelected != null && !selectedCloses) {
            badge(context, font, "Does not reach B", x + 4, y + 4, FlatUi.DANGER);
        }
        if (FlatUi.inside(mouseX, mouseY, x, y, width, height) && dragButton == -1) {
            drawHint(context, font, x, y, width, height);
        }
        context.draw();
        context.disableScissor();
    }

    private void drawLabels(DrawContext context, TextRenderer font, TrackWorldViewMath frame, double guiW, double guiH) {
        final double[] out = new double[3];
        if (selectedLine != null && selectedLine.length >= 6) {
            final int last = selectedLine.length - 3;
            if (frame.project(selectedLine[0], selectedLine[1] + 1.2, selectedLine[2], guiW, guiH, out)) {
                badge(context, font, "A", (int) Math.round(out[0]) - font.getWidth("A") - 7, (int) Math.round(out[1]) - 4, FlatUi.TEXT);
            }
            if (frame.project(selectedLine[last], selectedLine[last + 1] + 1.2, selectedLine[last + 2], guiW, guiH, out)) {
                badge(context, font, "B", (int) Math.round(out[0]) + 4, (int) Math.round(out[1]) - 4, FlatUi.TEXT);
            }
        }
        if (lastSelected != null && Double.isFinite(marker)) {
            final double s = Math.max(0, Math.min(lastSelected.length(), marker));
            final double[] p = centre(lastSelected, s, new double[5]);
            if (frame.project(p[0], p[1] + RAIL_TOP, p[2], guiW, guiH, out)) {
                final int cx = (int) Math.round(out[0]);
                final int cy = (int) Math.round(out[1]);
                final int color = 0xFFFFC53D;
                context.fill(cx - 1, cy - 3, cx + 2, cy + 4, color);
                context.fill(cx - 3, cy - 1, cx + 4, cy + 2, color);
                final String text = String.format(Locale.ROOT, "%.1f°", lastSelected.tilt(s));
                badge(context, font, text, cx + 6, cy - 4, color);
            }
        }
    }

    /** North arrow from two projected points around the target (exact for the current frame). */
    private void drawCompass(DrawContext context, TextRenderer font, TrackWorldViewMath frame, double guiW, double guiH, int cx, int cy, double pixel) {
        if (!Double.isFinite(focusX)) {
            return;
        }
        final double[] a = new double[3];
        final double[] b = new double[3];
        final double step = Math.max(0.5, orbit.distance * 0.05);
        final double tx = focusX + orbit.tx;
        final double ty = focusY + orbit.ty;
        final double tz = focusZ + orbit.tz;
        if (!frame.project(tx, ty, tz, guiW, guiH, a) || !frame.project(tx, ty, tz - step, guiW, guiH, b)) {
            return;
        }
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        final double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) {
            return;
        }
        dx /= len;
        dy /= len;
        final float arm = 7;
        final Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        final VertexConsumer buffer = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
        final float width = (float) Math.max(1, 2.5 * pixel);
        context.fill(cx - 11, cy - 11, cx + 12, cy + 12, 0x90101013);
        line2d(buffer, matrix, (float) (cx - dx * arm * 0.6), (float) (cy - dy * arm * 0.6), cx, cy, width, FlatUi.TEXT_FAINT);
        line2d(buffer, matrix, cx, cy, (float) (cx + dx * arm), (float) (cy + dy * arm), width, FlatUi.DANGER);
        context.draw();
    }

    private static void polyline(VertexConsumer buffer, Matrix4f matrix, TrackWorldViewMath frame, double[] line,
                                 double guiW, double guiH, float width, int color) {
        final double[] c0 = new double[4];
        final double[] c1 = new double[4];
        final double[] s0 = new double[2];
        final double[] s1 = new double[2];
        final int n = line.length / 3;
        if (n < 2) {
            return;
        }
        frame.clip(line[0], line[1], line[2], c0);
        for (int i = 1; i < n; i++) {
            frame.clip(line[i * 3], line[i * 3 + 1], line[i * 3 + 2], c1);
            final double w0 = c0[3];
            final double w1 = c1[3];
            if (w0 >= TrackWorldViewMath.MIN_W || w1 >= TrackWorldViewMath.MIN_W) {
                // clip the segment against the near plane (w = MIN_W) in homogeneous space
                final double[] a = w0 >= TrackWorldViewMath.MIN_W ? c0 : lerp(c0, c1, (TrackWorldViewMath.MIN_W - w0) / (w1 - w0));
                final double[] b = w1 >= TrackWorldViewMath.MIN_W ? c1 : lerp(c0, c1, (TrackWorldViewMath.MIN_W - w0) / (w1 - w0));
                TrackWorldViewMath.toScreen(a, guiW, guiH, s0);
                TrackWorldViewMath.toScreen(b, guiW, guiH, s1);
                line2d(buffer, matrix, (float) s0[0], (float) s0[1], (float) s1[0], (float) s1[1], width, color);
            }
            System.arraycopy(c1, 0, c0, 0, 4);
        }
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t};
    }

    /** A flat 2D line quad (slightly extended at both ends so joints close), both windings. */
    private static void line2d(VertexConsumer buffer, Matrix4f matrix, float x0, float y0, float x1, float y1, float width, int color) {
        final float dx = x1 - x0;
        final float dy = y1 - y0;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f || len > 1e5f) {
            return;
        }
        final float ux = dx / len;
        final float uy = dy / len;
        final float nx = -uy * width / 2;
        final float ny = ux * width / 2;
        final float ex = ux * width * 0.35f;
        final float ey = uy * width * 0.35f;
        final float[][] quad = {{x0 - ex + nx, y0 - ey + ny}, {x0 - ex - nx, y0 - ey - ny}, {x1 + ex - nx, y1 + ey - ny}, {x1 + ex + nx, y1 + ey + ny}};
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 4; k++) {
                final float[] p = quad[pass == 0 ? k : 3 - k];
                buffer.vertex(matrix, p[0], p[1], 0).color(color).next();
            }
        }
    }

    private static void badge(DrawContext context, TextRenderer font, String text, int x, int y, int color) {
        final int width = font.getWidth(text);
        context.fill(x - 2, y - 2, x + width + 2, y + 9, LABEL_BACK);
        context.drawText(font, text, x, y, color, false);
    }

    private static void drawHint(DrawContext context, TextRenderer font, int x, int y, int w, int h) {
        final String text = w >= 300 ? "Drag: orbit · Right/Shift-drag: pan · Scroll: zoom · Double-click: fit"
            : w >= 200 ? "Drag orbit · RMB pan · Wheel zoom · 2×click fit"
            : "Drag · RMB pan · Wheel";
        final float scale = w >= 300 ? 1f : 0.75f;
        final int textW = Math.round(font.getWidth(text) * scale);
        context.fill(x + 2, (int) (y + h - 6 - 8 * scale), x + 6 + textW, y + h - 2, LABEL_BACK);
        context.getMatrices().push();
        context.getMatrices().translate(x + 4, y + h - 4 - 8 * scale, 0);
        context.getMatrices().scale(scale, scale, 1);
        context.drawText(font, text, 0, 0, FlatUi.TEXT_DIM, false);
        context.getMatrices().pop();
    }

    // ------------------------------------------------------------------ TrackViewport

    @Override
    public boolean project(double x, double y, double z, double[] out) {
        return game.active() && TrackWorldViewCamera.FRAME.project(x, y + RAIL_TOP, z, guiWidth(), guiHeight(), out);
    }

    @Override
    public boolean groundAt(double screenX, double screenY, double planeY, double[] out) {
        return game.active() && TrackWorldViewCamera.FRAME.groundAt(screenX, screenY, guiWidth(), guiHeight(), planeY + RAIL_TOP,
            Math.max(256, maxDistance() * 4), out);
    }

    /** The pane rectangle of the last render: {x, y, w, h} in GUI px. */
    public int[] paneRect() {
        return new int[]{paneX, paneY, paneW, paneH};
    }

    // ------------------------------------------------------------------ input

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!FlatUi.inside(mouseX, mouseY, paneX, paneY, paneW, paneH) || paneW < 8) {
            return false;
        }
        if (button == 0) {
            final long now = System.currentTimeMillis();
            final boolean twice = now - lastClickTime < DOUBLE_CLICK_MS
                && Math.abs(mouseX - lastClickX) < 4 && Math.abs(mouseY - lastClickY) < 4;
            lastClickTime = twice ? 0 : now;
            lastClickX = mouseX;
            lastClickY = mouseY;
            if (twice) {
                resetView();
                return true;
            }
            dragPan = Screen.hasShiftDown();
        } else if (button == 1 || button == 2) {
            dragPan = true;
        } else {
            return false;
        }
        dragButton = button;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragButton == -1 || button != dragButton) {
            return false;
        }
        if (dragPan) {
            panGround(deltaX, deltaY);
        } else {
            orbit.yaw += deltaX * ORBIT_PER_PANE_W / Math.max(60, paneW);
            orbit.pitch += deltaY * ORBIT_PER_PANE_H / Math.max(40, paneH);
            orbit.update();
        }
        clampOrbit();
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragButton == -1 || button != dragButton) {
            return false;
        }
        dragButton = -1;
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!FlatUi.inside(mouseX, mouseY, paneX, paneY, paneW, paneH) || paneW < 8 || amount == 0) {
            return false;
        }
        final double virtualH = virtualHeight();
        final double virtualW = virtualH * paneW / Math.max(1, paneH);
        // cursor relative to the pane centre, in GUI px = virtual px (same focal length)
        final double px = mouseX - (paneX + paneW / 2.0) + virtualW / 2;
        final double py = mouseY - (paneY + paneH / 2.0) + virtualH / 2;
        final double notches = Math.max(-MAX_NOTCHES_PER_EVENT, Math.min(MAX_NOTCHES_PER_EVENT, amount));
        orbit.zoom(Math.pow(ZOOM_PER_NOTCH, notches), px, py, virtualW, virtualH);
        clampOrbit();
        return true;
    }

    /**
     * Grab-the-ground pan: the target slides over the HORIZONTAL plane (the camera never drifts up or
     * down — the 3D tab's screen-plane pan lifted the orbit pivot into the air). Sideways the point under
     * the target follows the cursor exactly; forward/back is scaled by 1 / sin(pitch), capped for
     * grazing views so a small drag never throws the camera across the map.
     */
    private void panGround(double dx, double dy) {
        final double perPixel = orbit.distance / Math.max(1e-6, guiHeight() / 2 / tanHalfV());
        final double rightX = Math.sin(orbit.yaw);
        final double rightZ = -Math.cos(orbit.yaw);
        final double forwardX = -Math.cos(orbit.yaw);
        final double forwardZ = -Math.sin(orbit.yaw);
        final double along = perPixel / Math.max(0.35, Math.sin(Math.max(0, orbit.pitch)));
        orbit.tx += -rightX * dx * perPixel + forwardX * dy * along;
        orbit.tz += -rightZ * dx * perPixel + forwardZ * dy * along;
        orbit.update();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Keeps the GOAL inside what the client actually has: chunks arrive (and vanilla's section grid is
     * centred) around the PLAYER, not the camera, so a camera wandering out of that disc looks at empty
     * or half-built sections. Target within {@link #loadedRadius} − 8 of the player, eye within the
     * radius (distance shortened along the view direction), pitch below the pane's limit.
     */
    private void clampOrbit() {
        final MinecraftClient client = MinecraftClient.getInstance();
        orbit.pitch = Math.min(orbit.pitch, pitchLimit);
        orbit.distance = Math.min(orbit.distance, maxDistance());
        if (client.player != null && Double.isFinite(focusX)) {
            final double radius = loadedRadius();
            final double px = client.player.getX() - focusX;
            final double pz = client.player.getZ() - focusZ;
            double ox = orbit.tx - px;
            double oz = orbit.tz - pz;
            final double reach = Math.sqrt(ox * ox + oz * oz);
            final double targetMax = Math.max(8, radius - 8);
            if (reach > targetMax) {
                ox *= targetMax / reach;
                oz *= targetMax / reach;
                orbit.tx = px + ox;
                orbit.tz = pz + oz;
            }
            // largest d with |o + d·c·(cos yaw, sin yaw)| <= radius
            final double c = Math.cos(orbit.pitch);
            if (c > 1e-3) {
                final double ux = Math.cos(orbit.yaw);
                final double uz = Math.sin(orbit.yaw);
                final double dot = ox * ux + oz * uz;
                final double disc = dot * dot - (ox * ox + oz * oz) + radius * radius;
                if (disc > 0) {
                    final double dMax = (-dot + Math.sqrt(disc)) / c;
                    orbit.distance = Math.min(orbit.distance, Math.max(TrackView3DCamera.MIN_DISTANCE, dMax));
                }
            }
        }
        orbit.update();
    }

    /** Horizontal radius around the player that is loaded and meshed: (view distance − 2) chunks. */
    private static double loadedRadius() {
        final MinecraftClient client = MinecraftClient.getInstance();
        return Math.max(32, (client.options.getClampedViewDistance() - 2) * 16.0);
    }

    /** Zoom-out cap: farther than the loaded disc is pointless. */
    private static double maxDistance() {
        return Math.max(40, Math.min(600, loadedRadius() * 1.5));
    }

    /**
     * Eases the SHOWN pose toward the goal (critically damped springs, frame-rate independent). The
     * terrain clip shortens the goal distance, so an obstacle pulls the camera in smoothly too. On the
     * first frame after opening the shown pose starts at the player's own eye: a calm fly-in.
     */
    private void updateShown() {
        final long now = System.nanoTime();
        final double goalLogDistance;
        {
            final Vec3d target = new Vec3d(focusX + orbit.tx, focusY + orbit.ty, focusZ + orbit.tz);
            final Vec3d unit = new Vec3d(Math.cos(orbit.pitch) * Math.cos(orbit.yaw), Math.sin(orbit.pitch),
                Math.cos(orbit.pitch) * Math.sin(orbit.yaw));
            final double distance = clipToTerrain ? game.clipDistance(target, unit, orbit.distance) : orbit.distance;
            goalLogDistance = Math.log(Math.max(TrackView3DCamera.MIN_DISTANCE * 0.5, distance));
        }
        if (!shownValid) {
            shownValid = true;
            openedNanos = now;
            lastFrameNanos = now;
            final Vec3d start = game.startEye();
            final double sx = start.x - (focusX + orbit.tx);
            final double sy = start.y - (focusY + orbit.ty);
            final double sz = start.z - (focusZ + orbit.tz);
            final double d = Math.sqrt(sx * sx + sy * sy + sz * sz);
            final boolean flyIn = d > 0.5 && d < loadedRadius();
            if (flyIn) {
                // the player's eye as an orbit about the goal target; yaw on the branch nearest the goal's
                double yaw = Math.atan2(sz, sx);
                yaw += Math.rint((orbit.yaw - yaw) / (2 * Math.PI)) * 2 * Math.PI;
                shownYaw.reset(yaw);
                shownPitch.reset(Math.asin(Math.max(-1, Math.min(1, sy / d))));
                shownLogDistance.reset(Math.log(d));
                lookBlend.reset(0);
            } else {
                shownYaw.reset(orbit.yaw);
                shownPitch.reset(orbit.pitch);
                shownLogDistance.reset(goalLogDistance);
                lookBlend.reset(1);
            }
            shownTx.reset(orbit.tx);
            shownTy.reset(orbit.ty);
            shownTz.reset(orbit.tz);
            return;
        }
        final double dt = Math.max(0, Math.min(0.1, (now - lastFrameNanos) / 1.0e9));
        lastFrameNanos = now;
        final boolean flying = (now - openedNanos) / 1.0e9 < FLY_IN_SECONDS;
        final double omega = flying ? OMEGA_FLY_IN : OMEGA;
        // the goal yaw is continuous (never wrapped), so the spring can chase it directly
        shownYaw.step(orbit.yaw, omega, dt);
        shownPitch.step(orbit.pitch, omega, dt);
        shownLogDistance.step(goalLogDistance, omega, dt);
        shownTx.step(orbit.tx, omega, dt);
        shownTy.step(orbit.ty, omega, dt);
        shownTz.step(orbit.tz, omega, dt);
        lookBlend.step(1, flying ? OMEGA_FLY_IN * 1.4 : OMEGA, dt);
        // the eased pitch must respect the pane limit too (the goal does)
        shownPitch.x = Math.min(shownPitch.x, pitchLimit);
    }

    /** One critically damped spring (exact integration — stable at any frame time). */
    private static final class Spring {
        double x;
        double v;

        void reset(double value) {
            x = value;
            v = 0;
        }

        void step(double goal, double omega, double dt) {
            if (dt <= 0) {
                return;
            }
            final double d = x - goal;
            final double e = Math.exp(-omega * dt);
            final double t = (v + omega * d) * dt;
            v = (v - omega * t) * e;
            x = goal + (d + t) * e;
            if (Math.abs(x - goal) < 1e-7 && Math.abs(v) < 1e-6) {
                x = goal;
                v = 0;
            }
        }
    }

    private static double guiWidth() {
        final var window = MinecraftClient.getInstance().getWindow();
        return window.getFramebufferWidth() / Math.max(1e-6, window.getScaleFactor());
    }

    private static double guiHeight() {
        final var window = MinecraftClient.getInstance().getWindow();
        return window.getFramebufferHeight() / Math.max(1e-6, window.getScaleFactor());
    }

    /** tan(vertical FOV / 2) of the frame on screen (falls back to the FOV option before one exists). */
    private static double tanHalfV() {
        final TrackWorldViewMath frame = TrackWorldViewCamera.FRAME;
        final double m11 = frame.valid() ? frame.projectionElement(5) : 0;
        if (m11 > 1e-6) {
            return 1 / m11;
        }
        final int fov = MinecraftClient.getInstance().options.getFov().getValue();
        return Math.tan(Math.toRadians(fov) / 2);
    }

    private static double tanHalfH(double tanV) {
        final TrackWorldViewMath frame = TrackWorldViewCamera.FRAME;
        final double m00 = frame.valid() ? frame.projectionElement(0) : 0;
        if (m00 > 1e-6) {
            return 1 / m00;
        }
        final var window = MinecraftClient.getInstance().getWindow();
        return tanV * window.getFramebufferWidth() / Math.max(1, window.getFramebufferHeight());
    }

    /** Height of the virtual TrackView3DCamera viewport whose focal length equals the game's. */
    private static double virtualHeight() {
        return guiHeight() / 2 / tanHalfV() / TrackView3DCamera.focal(1);
    }

    /** Samples a path's banked centreline at the rail top (absolute WORLD coordinates, RAIL_TOP added). */
    private static double[] sample(TrackPath path) {
        final double length = Math.max(0, path.length());
        final double step = Math.max(0.5, length / MAX_SAMPLES);
        final int n = (int) Math.ceil(length / step) + 1;
        final double[] out = new double[n * 3];
        final double[] point = new double[5];
        for (int i = 0; i < n; i++) {
            final double s = Math.min(length, i * step);
            final double[] p = centre(path, s, point);
            out[i * 3] = p[0];
            out[i * 3 + 1] = p[1] + RAIL_TOP;
            out[i * 3 + 2] = p[2];
        }
        return out;
    }

    /**
     * The banked track centre at {@code s} in pointAt coordinates (the caller's project() adds the rail
     * top): a canted track rolls about its low rail, which moves the centre sideways and up.
     */
    private static double[] centre(TrackPath path, double s, double[] point) {
        path.pointAt(s, point);
        final double tilt = path.tilt(s);
        if (tilt == 0) {
            return new double[]{point[0], point[1], point[2]};
        }
        final double[] banked = TrackPath.bank(0, 0, tilt);
        final double rx = -Math.sin(point[3]);
        final double rz = Math.cos(point[3]);
        return new double[]{point[0] + rx * banked[0], point[1] + banked[1], point[2] + rz * banked[0]};
    }

    private void buildFitPoints(List<TrackPath> context, TrackPath selected) {
        fitPointCount = 0;
        chordHeading = Double.NaN;
        if (selectedLine != null && selectedLine.length >= 3) {
            addLine(selectedLine, 240);
            final int last = selectedLine.length - 3;
            final double dx = selectedLine[last] - selectedLine[0];
            final double dz = selectedLine[last + 2] - selectedLine[2];
            chordHeading = dx * dx + dz * dz > 1e-6 ? Math.atan2(dz, dx) : Double.NaN;
            for (final double[] line : followerLines) {
                addLine(line, 120);
            }
        } else {
            for (final TrackPath path : context) {
                if (path == null) {
                    continue;
                }
                final double[] line = sample(path);
                for (int i = 0; i < line.length / 3; i += Math.max(1, line.length / 3 / 60)) {
                    final double dx = line[i * 3] - focusX;
                    final double dz = line[i * 3 + 2] - focusZ;
                    if (dx * dx + dz * dz < 24 * 24) {
                        addFitPoint(dx, line[i * 3 + 1] - focusY, dz);
                    }
                }
            }
        }
        addFitPoint(0, 0, 0);
        addFitPoint(0, 3, 0);
        if (selected == null && fitPointCount <= 2) {
            addFitPoint(-8, 0, -8);
            addFitPoint(8, 0, 8);
        }
    }

    private void addLine(double[] line, int maxPoints) {
        final int n = line.length / 3;
        final int stride = Math.max(1, n / maxPoints);
        for (int i = 0; i < n; i += stride) {
            addFitPoint(line[i * 3] - focusX, line[i * 3 + 1] - focusY, line[i * 3 + 2] - focusZ);
        }
        addFitPoint(line[(n - 1) * 3] - focusX, line[(n - 1) * 3 + 1] - focusY, line[(n - 1) * 3 + 2] - focusZ);
    }

    private void addFitPoint(double x, double y, double z) {
        if (fitPointCount * 3 + 3 > fitPoints.length) {
            fitPoints = java.util.Arrays.copyOf(fitPoints, Math.max(48, fitPoints.length * 2));
        }
        fitPoints[fitPointCount * 3] = (float) x;
        fitPoints[fitPointCount * 3 + 1] = (float) y;
        fitPoints[fitPointCount * 3 + 2] = (float) z;
        fitPointCount++;
    }

    private static boolean same(List<TrackPath> a, List<TrackPath> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }
}
