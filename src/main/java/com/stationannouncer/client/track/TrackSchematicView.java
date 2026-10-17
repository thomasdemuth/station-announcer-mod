package com.stationannouncer.client.track;

import com.stationannouncer.client.mtraddon.FlatUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * The editor's top-down Schematic view: x right, z down (north up), uniform scale. It only owns the
 * transform (pan / zoom / fit), the grid and its own mouse handling for panning — the editor draws the
 * tracks and every handle on top through {@link TrackViewport}, exactly as it does over the 3D and
 * World views.
 */
@Environment(EnvType.CLIENT)
final class TrackSchematicView implements TrackViewport {
    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 64;

    private double viewX;
    private double viewZ;
    private double scale = 4;
    private boolean fitted;
    private int paneX;
    private int paneY;
    private int paneW = 1;
    private int paneH = 1;

    private int dragButton = -1;
    private double dragStartX;
    private double dragStartY;
    private double dragViewX;
    private double dragViewZ;

    void setPane(int x, int y, int w, int h) {
        paneX = x;
        paneY = y;
        paneW = Math.max(1, w);
        paneH = Math.max(1, h);
    }

    boolean fitted() {
        return fitted;
    }

    /** Frames the world box (x0..x1, z0..z1) with a small margin. */
    void fit(double x0, double x1, double z0, double z1) {
        viewX = (x0 + x1) / 2;
        viewZ = (z0 + z1) / 2;
        final double sx = (paneW - 36) / Math.max(4, x1 - x0);
        final double sz = (paneH - 36) / Math.max(4, z1 - z0);
        scale = Math.max(MIN_SCALE, Math.min(48, Math.min(sx, sz)));
        fitted = true;
    }

    void resetView() {
        fitted = false;
    }

    double scale() {
        return scale;
    }

    // ------------------------------------------------------------------ TrackViewport

    @Override
    public boolean project(double x, double y, double z, double[] out) {
        out[0] = paneX + paneW / 2.0 + (x - viewX) * scale;
        out[1] = paneY + paneH / 2.0 + (z - viewZ) * scale;
        out[2] = 0;
        return true;
    }

    @Override
    public boolean groundAt(double screenX, double screenY, double planeY, double[] out) {
        out[0] = viewX + (screenX - paneX - paneW / 2.0) / scale;
        out[1] = viewZ + (screenY - paneY - paneH / 2.0) / scale;
        return true;
    }

    @Override
    public boolean isTopDown() {
        return true;
    }

    // ------------------------------------------------------------------ drawing

    double gridStep() {
        final double[] steps = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000};
        for (final double step : steps) {
            if (step * scale >= 14) {
                return step;
            }
        }
        return 1000;
    }

    /** Background + grid (call inside the pane's scissor). */
    void drawBackground(DrawContext c) {
        FlatUi.rect(c, paneX, paneY, paneW, paneH, 0xFF17171B);
        final double step = gridStep();
        final double wx0 = viewX - paneW / 2.0 / scale;
        final double wx1 = viewX + paneW / 2.0 / scale;
        final double wz0 = viewZ - paneH / 2.0 / scale;
        final double wz1 = viewZ + paneH / 2.0 / scale;
        final double[] out = new double[3];
        for (double gx = Math.floor(wx0 / step) * step; gx <= wx1; gx += step) {
            project(gx, 0, 0, out);
            FlatUi.rect(c, (int) out[0], paneY, 1, paneH, Math.floorMod(Math.round(gx / step), 5) == 0 ? 0x16FFFFFF : 0x0AFFFFFF);
        }
        for (double gz = Math.floor(wz0 / step) * step; gz <= wz1; gz += step) {
            project(0, 0, gz, out);
            FlatUi.rect(c, paneX, (int) out[1], paneW, 1, Math.floorMod(Math.round(gz / step), 5) == 0 ? 0x16FFFFFF : 0x0AFFFFFF);
        }
    }

    /** Scale label + north arrow (outside the scissor is fine). */
    void drawLabels(DrawContext c, TextRenderer font) {
        final double step = gridStep();
        final String text = "grid " + (step >= 1 ? Integer.toString((int) step) : String.valueOf(step)) + " bl";
        c.drawText(font, text, paneX + 4, paneY + paneH - 10, FlatUi.TEXT_FAINT, false);
        c.drawText(font, "N ↑", paneX + 4, paneY + 4, FlatUi.TEXT_FAINT, false);
    }

    // ------------------------------------------------------------------ input (panning)

    boolean isDragging() {
        return dragButton != -1;
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (!FlatUi.inside(mx, my, paneX, paneY, paneW, paneH)) {
            return false;
        }
        dragButton = button;
        dragStartX = mx;
        dragStartY = my;
        dragViewX = viewX;
        dragViewZ = viewZ;
        return true;
    }

    boolean mouseDragged(double mx, double my, int button) {
        if (dragButton == -1) {
            return false;
        }
        viewX = dragViewX - (mx - dragStartX) / scale;
        viewZ = dragViewZ - (my - dragStartY) / scale;
        return true;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (dragButton == -1) {
            return false;
        }
        dragButton = -1;
        return true;
    }

    boolean mouseScrolled(double mx, double my, double amount) {
        if (!FlatUi.inside(mx, my, paneX, paneY, paneW, paneH) || amount == 0) {
            return false;
        }
        final double[] before = new double[2];
        groundAt(mx, my, 0, before);
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * (amount > 0 ? 1.25 : 0.8)));
        // keep the world point under the cursor fixed
        viewX = before[0] - (mx - paneX - paneW / 2.0) / scale;
        viewZ = before[1] - (my - paneY - paneH / 2.0) / scale;
        return true;
    }
}
