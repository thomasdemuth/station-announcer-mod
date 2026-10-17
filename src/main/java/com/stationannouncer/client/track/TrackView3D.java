package com.stationannouncer.client.track;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
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
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's 3D pane: an orbit view of the tracks around the clicked node, tilt included.
 *
 * <h2>How it draws</h2>
 * Real depth-tested 3D without touching the projection matrix or the viewport: the perspective
 * projection runs on the CPU ({@link TrackView3DProjector}, pure Java, also used by the offline
 * check {@code tools/track_test/TrackView3DCheck}) and emits screen-space quads whose GUI z is
 * linear in 1/depth — exactly interpolable across a projected triangle — into the GUI's own
 * depth-tested position-colour layer ({@link RenderLayer#getGui()}, the layer {@code DrawContext.fill}
 * uses; it sets and restores every GL state itself). The pane is scissored, and the depth buffer is
 * cleared (inside the scissor) right after, so labels and any popup drawn later are never punched
 * through (same reason as {@code CreatorPreview}). z stays in (60, 3060], inside the GUI's ±10000.
 *
 * Geometry ({@link TrackView3DScene}) is rebuilt only when {@link #setScene} / {@link #setMarker}
 * actually change something; a frame only projects the cached arrays.
 *
 * <h2>Embedding</h2>
 * Call {@link #setScene} whenever the draft or the node changes (calling it every frame with the
 * same objects is cheap — it compares identities), {@link #setMarker} for the hovered distance, then
 * {@link #render} with the pane rectangle, and forward the four mouse events (they return true when
 * the pane consumed them). Controls: left-drag orbit, right-drag or shift-drag pan, wheel zoom to
 * the cursor, double-click re-frames.
 *
 * <h2>As a {@link TrackViewport}</h2>
 * {@link #project} / {@link #groundAt} use exactly the camera and pane of the last {@link #render}.
 * World y is the path height like {@code TrackPath.pointAt}; this view draws the rail TOP
 * {@link TrackView3DScene#RAIL_LIFT} above it, so both methods add that lift — a handle at a
 * pointAt() position lands on the drawn rail top, and {@code groundAt(.., planeY, ..)} intersects the
 * plane the rail top of a path at height {@code planeY} is drawn on (round trips are exact).
 */
@Environment(EnvType.CLIENT)
public final class TrackView3D implements TrackViewport {
    private static final int BACKGROUND_TOP = 0xFF19191E;
    private static final int BACKGROUND_BOTTOM = 0xFF111114;
    private static final int LABEL_BACK = 0xC0101013;
    private static final long DOUBLE_CLICK_MS = 300;
    /** Secondary accent for follower tracks (shared with {@link TrackWorldView}). */
    public static final int FOLLOWER = 0xFF4FC3C9;

    private final TrackView3DCamera camera = new TrackView3DCamera();
    private final TrackView3DProjector projector = new TrackView3DProjector();
    private TrackView3DScene scene;

    // last setScene inputs (identity comparison → no rebuild when nothing changed)
    private final List<TrackPath> lastContext = new ArrayList<>();
    private final List<TrackPath> lastFollowers = new ArrayList<>();
    private TrackPath lastSelected;
    private double lastFocusX = Double.NaN;
    private double lastFocusY = Double.NaN;
    private double lastFocusZ = Double.NaN;
    private String lastSelectedKey;
    private double marker = Double.NaN;
    private boolean needsFit = true;

    // pane rectangle from the last render (for hit tests)
    private int paneX;
    private int paneY;
    private int paneW;
    private int paneH;

    private int dragButton = -1;
    private boolean dragPan;
    private long lastClickTime;
    private double lastClickX;
    private double lastClickY;

    /**
     * @param context  every other track at/near the node (drawn grey)
     * @param selected the draft being edited (drawn highlighted, with its tilt), may be null
     * @param focusX   world point the orbit centres on (usually the clicked node) — node centre in
     *                 TrackPath coordinates: (x + 0.5, y, z + 0.5), y = the rail height like pointAt
     */
    public void setScene(List<TrackPath> context, TrackPath selected, double focusX, double focusY, double focusZ) {
        setScene(context, selected, List.of(), focusX, focusY, focusZ);
    }

    /**
     * As above, plus {@code followers}: tracks that reshape with the selected one (parallel tracks of
     * a double/triple line, or the other tracks of a multi-track edit) — drawn banked like the selected
     * track but with a secondary accent ({@link TrackView3DScene.Palette#follower}). Pass the live
     * DRAFT paths; a context path with a follower's key is hidden. Identity-compared like the rest.
     */
    public void setScene(List<TrackPath> context, TrackPath selected, List<TrackPath> followers,
                         double focusX, double focusY, double focusZ) {
        final List<TrackPath> safeContext = context == null ? List.of() : context;
        final List<TrackPath> safeFollowers = followers == null ? List.of() : followers;
        final boolean focusMoved = !(focusX == lastFocusX && focusY == lastFocusY && focusZ == lastFocusZ);
        if (scene != null && !focusMoved && selected == lastSelected && sameList(safeContext, lastContext)
            && sameList(safeFollowers, lastFollowers)) {
            return;
        }
        final String key = selected == null || selected.profile == null ? null : selected.profile.key();
        if (scene == null || focusMoved || key != null && !key.equals(lastSelectedKey) || lastSelected == null && selected != null) {
            needsFit = true;
        }
        lastContext.clear();
        lastContext.addAll(safeContext);
        lastFollowers.clear();
        lastFollowers.addAll(safeFollowers);
        lastSelected = selected;
        lastSelectedKey = key;
        lastFocusX = focusX;
        lastFocusY = focusY;
        lastFocusZ = focusZ;
        scene = TrackView3DScene.build(safeContext, selected, safeFollowers, focusX, focusY, focusZ, palette());
        scene.setMarker(marker);
    }

    /** Highlights distance {@code s} along the selected track (e.g. the hovered tilt point); NaN = none. */
    public void setMarker(double s) {
        if (Double.compare(s, marker) == 0 || Double.isNaN(s) && Double.isNaN(marker)) {
            return;
        }
        marker = s;
        if (scene != null) {
            scene.setMarker(s);
        }
    }

    /** Re-frames the scene on the next render (what a double-click does). */
    public void resetView() {
        needsFit = true;
    }

    /** True while the pane owns a mouse drag. */
    public boolean isDragging() {
        return dragButton != -1;
    }

    public void render(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY, float delta) {
        paneX = x;
        paneY = y;
        paneW = width;
        paneH = height;
        if (width < 8 || height < 8) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        final TextRenderer font = client.textRenderer;
        final double guiScale = Math.max(1, client.getWindow().getScaleFactor());
        final float pixel = (float) (1 / guiScale);

        context.draw();
        context.enableScissor(x, y, x + width, y + height);
        context.fillGradient(x, y, x + width, y + height, BACKGROUND_TOP, BACKGROUND_BOTTOM);

        if (scene != null) {
            if (needsFit) {
                camera.defaultAngle(scene.chordHeading);
                camera.fitPoints(scene.fitPoints, scene.fitPointCount, width, height, 0.84);
                needsFit = false;
            }
            final Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
            final VertexConsumer buffer = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
            final TrackView3DProjector.Sink sink = (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, argb) -> {
                buffer.vertex(matrix, x0, y0, z0).color(argb).next();
                buffer.vertex(matrix, x1, y1, z1).color(argb).next();
                buffer.vertex(matrix, x2, y2, z2).color(argb).next();
                buffer.vertex(matrix, x3, y3, z3).color(argb).next();
            };
            projector.draw(scene.mesh, camera, x, y, width, height, pixel, sink);
            projector.draw(scene.sleepers, camera, x, y, width, height, pixel,
                TrackView3DScene.sleeperMaxDepth(height, guiScale), sink);
            projector.draw(scene.markerMesh, camera, x, y, width, height, pixel, sink);
            context.draw();
            // inside the scissor: only this pane's depth is reset
            RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

            drawLabels(context, font, x, y, width, height);
            drawCompass(context, font, x + width - 13, y + 13, pixel);
            if (scene.labelA != null && !scene.selectedCloses) {
                badge(context, font, "Does not reach B", x + 4, y + 4, FlatUi.DANGER);
            }
        } else {
            final String text = "No track";
            context.drawText(font, text, x + (width - font.getWidth(text)) / 2, y + (height - 8) / 2, FlatUi.TEXT_FAINT, false);
        }

        if (FlatUi.inside(mouseX, mouseY, x, y, width, height) && dragButton == -1) {
            drawHint(context, font, x, y, width, height);
        }
        context.draw();
        context.disableScissor();
    }

    // ------------------------------------------------------------------ overlays

    private void drawLabels(DrawContext context, TextRenderer font, int x, int y, int w, int h) {
        final double[] out = new double[3];
        if (scene.labelA != null && camera.project(scene.labelA[0], scene.labelA[1], scene.labelA[2], w, h, out)) {
            // beside the anchor so the node pin stays visible
            badge(context, font, "A", (int) Math.round(x + out[0]) - font.getWidth("A") - 7, (int) Math.round(y + out[1]) - 4, FlatUi.TEXT);
        }
        if (scene.labelB != null && camera.project(scene.labelB[0], scene.labelB[1], scene.labelB[2], w, h, out)) {
            badge(context, font, "B", (int) Math.round(x + out[0]) + 4, (int) Math.round(y + out[1]) - 4, FlatUi.TEXT);
        }
        if (scene.markerHead != null && Double.isFinite(scene.markerTilt)
            && camera.project(scene.markerHead[0], scene.markerHead[1], scene.markerHead[2], w, h, out)) {
            final String text = String.format(java.util.Locale.ROOT, "%.1f°", scene.markerTilt);
            badge(context, font, text, (int) Math.round(x + out[0]) + 6, (int) Math.round(y + out[1]) - 4, scene.palette.marker);
        }
    }

    private static void badge(DrawContext context, TextRenderer font, String text, int x, int y, int color) {
        final int width = font.getWidth(text);
        context.fill(x - 2, y - 2, x + width + 2, y + 9, LABEL_BACK);
        context.drawText(font, text, x, y, color, false);
    }

    /** A small north arrow: which way −z (north) points on screen. */
    private void drawCompass(DrawContext context, TextRenderer font, int cx, int cy, float pixel) {
        final double[] dir = camera.screenDirection(0, -1);
        final float len = 7;
        final float tipX = (float) (cx + dir[0] * len);
        final float tipY = (float) (cy + dir[1] * len);
        final float tailX = (float) (cx - dir[0] * len * 0.6);
        final float tailY = (float) (cy - dir[1] * len * 0.6);
        final Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        final VertexConsumer buffer = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
        line2d(buffer, matrix, tailX, tailY, cx, cy, Math.max(1f, 2.5f * pixel), FlatUi.TEXT_FAINT);
        line2d(buffer, matrix, cx, cy, tipX, tipY, Math.max(1f, 2.5f * pixel), FlatUi.DANGER);
        context.draw();
        final int lx = Math.round((float) (cx + dir[0] * (len + 6))) - font.getWidth("N") / 2 + 1;
        final int ly = Math.round((float) (cy + dir[1] * (len + 6))) - 4;
        context.drawText(font, "N", lx, ly, FlatUi.TEXT_DIM, false);
    }

    /** A flat 2D line quad, emitted in both windings so culling can never drop it. */
    private static void line2d(VertexConsumer buffer, Matrix4f matrix, float x0, float y0, float x1, float y1, float width, int color) {
        final float dx = x1 - x0;
        final float dy = y1 - y0;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        final float nx = -dy / len * width / 2;
        final float ny = dx / len * width / 2;
        final float[][] quad = {{x0 + nx, y0 + ny}, {x0 - nx, y0 - ny}, {x1 - nx, y1 - ny}, {x1 + nx, y1 + ny}};
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 4; k++) {
                final float[] p = quad[pass == 0 ? k : 3 - k];
                buffer.vertex(matrix, p[0], p[1], 10).color(color).next();
            }
        }
    }

    private static void drawHint(DrawContext context, TextRenderer font, int x, int y, int w, int h) {
        final String text = w >= 300 ? "Drag: orbit · Right/Shift-drag: pan · Scroll: zoom · Double-click: fit"
            : w >= 200 ? "Drag orbit · RMB pan · Wheel zoom · 2×click fit"
            : "Drag · RMB pan · Wheel";
        final float scale = w >= 300 ? 1f : 0.75f;
        context.getMatrices().push();
        context.getMatrices().translate(x + 4, y + h - 4 - 8 * scale, 0);
        context.getMatrices().scale(scale, scale, 1);
        context.drawText(font, text, 0, 0, FlatUi.TEXT_FAINT, false);
        context.getMatrices().pop();
    }

    // ------------------------------------------------------------------ TrackViewport

    @Override
    public boolean project(double x, double y, double z, double[] out) {
        if (scene == null || paneW < 8 || paneH < 8) {
            return false;
        }
        if (!camera.project(x - lastFocusX, y - lastFocusY + TrackView3DScene.RAIL_LIFT, z - lastFocusZ, paneW, paneH, out)) {
            return false;
        }
        out[0] += paneX;
        out[1] += paneY;
        return true;
    }

    @Override
    public boolean groundAt(double screenX, double screenY, double planeY, double[] out) {
        if (scene == null || paneW < 8 || paneH < 8) {
            return false;
        }
        final double f = TrackView3DCamera.focal(paneH);
        final double vx = (screenX - paneX - paneW / 2.0) / f;
        final double vy = -(screenY - paneY - paneH / 2.0) / f;
        final double dx = camera.fx + camera.rx * vx + camera.ux * vy;
        final double dy = camera.fy + camera.ry * vx + camera.uy * vy;
        final double dz = camera.fz + camera.rz * vx + camera.uz * vy;
        if (Math.abs(dy) < 1e-9) {
            return false;
        }
        final double plane = planeY - lastFocusY + TrackView3DScene.RAIL_LIFT;
        final double t = (plane - camera.cy) / dy;
        // in front of the camera, and not absurdly far (a ray grazing the horizon)
        if (t <= 0 || t * Math.sqrt(dx * dx + dy * dy + dz * dz) > TrackView3DCamera.MAX_DISTANCE * 4) {
            return false;
        }
        out[0] = camera.cx + dx * t + lastFocusX;
        out[1] = camera.cz + dz * t + lastFocusZ;
        return true;
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
            camera.pan(deltaX, deltaY, Math.max(8, paneH));
        } else {
            camera.orbit(deltaX, deltaY);
        }
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
        camera.zoom(Math.pow(0.85, amount), mouseX - paneX, mouseY - paneY, paneW, paneH);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static TrackView3DScene.Palette palette() {
        final TrackView3DScene.Palette palette = new TrackView3DScene.Palette();
        palette.accent = FlatUi.ACCENT;
        palette.danger = FlatUi.DANGER;
        palette.tick = FlatUi.TEXT;
        palette.node = FlatUi.TEXT;
        palette.follower = FOLLOWER;
        return palette;
    }

    private static boolean sameList(List<TrackPath> a, List<TrackPath> b) {
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
