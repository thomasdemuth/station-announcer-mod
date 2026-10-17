package com.stationannouncer.client.track;

import com.stationannouncer.client.mtraddon.FlatUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/**
 * Anti-blocky drawing for the track editor: arbitrary-angle lines, discs and diamonds as
 * position-colour quads on {@link RenderLayer#getGui()} (the layer {@code DrawContext.fill}
 * uses), so the plan and profile views can draw curves that FlatUi's axis-aligned rectangles
 * cannot.
 *
 * <p>Every quad re-fetches the buffer (never hold a VertexConsumer across a text draw — text
 * flushes the batch; see CLAUDE.md "CRASH LESSON"). Callers {@code c.draw()} before turning a
 * scissor off so the quads are clipped. Winding: the GUI layer culls, so every quad is emitted in
 * the same screen-space winding {@code fill} uses (negative shoelace area in y-down coords).</p>
 */
@Environment(EnvType.CLIENT)
final class TrackEditorGfx {
    private TrackEditorGfx() {
    }

    static void quad(DrawContext c, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int argb) {
        final float area = (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y4 - x4 * y3) + (x4 * y1 - x1 * y4);
        final Matrix4f matrix = c.getMatrices().peek().getPositionMatrix();
        final int a = argb >>> 24;
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        final VertexConsumer buffer = c.getVertexConsumers().getBuffer(RenderLayer.getGui());
        if (area <= 0) {
            buffer.vertex(matrix, x1, y1, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x2, y2, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x3, y3, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x4, y4, 0).color(r, g, b, a).next();
        } else {
            buffer.vertex(matrix, x4, y4, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x3, y3, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x2, y2, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x1, y1, 0).color(r, g, b, a).next();
        }
    }

    /** A line segment of the given width (screen px). */
    static void line(DrawContext c, double x1, double y1, double x2, double y2, double width, int argb) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double length = Math.hypot(dx, dy);
        if (length < 1e-6) {
            return;
        }
        final double nx = -dy / length * width / 2;
        final double ny = dx / length * width / 2;
        // a little overlap along the line so consecutive segments of a polyline do not gap
        final double ex = dx / length * width * 0.25;
        final double ey = dy / length * width * 0.25;
        quad(c, (float) (x1 - ex + nx), (float) (y1 - ey + ny), (float) (x1 - ex - nx), (float) (y1 - ey - ny),
            (float) (x2 + ex - nx), (float) (y2 + ey - ny), (float) (x2 + ex + nx), (float) (y2 + ey + ny), argb);
    }

    /** A dashed line (dash and gap in px). */
    static void dashed(DrawContext c, double x1, double y1, double x2, double y2, double width, double dash, int argb) {
        final double length = Math.hypot(x2 - x1, y2 - y1);
        if (length < 1e-6) {
            return;
        }
        for (double t = 0; t < length; t += dash * 2) {
            final double t2 = Math.min(length, t + dash);
            line(c, x1 + (x2 - x1) * t / length, y1 + (y2 - y1) * t / length,
                x1 + (x2 - x1) * t2 / length, y1 + (y2 - y1) * t2 / length, width, argb);
        }
    }

    /** A filled disc (fan of quads). */
    static void disc(DrawContext c, double x, double y, double radius, int argb) {
        final int segments = radius > 4 ? 16 : 10;
        for (int i = 0; i < segments; i += 2) {
            final double a0 = 2 * Math.PI * i / segments;
            final double a1 = 2 * Math.PI * (i + 1) / segments;
            final double a2 = 2 * Math.PI * (i + 2) / segments;
            quad(c, (float) x, (float) y,
                (float) (x + Math.cos(a0) * radius), (float) (y + Math.sin(a0) * radius),
                (float) (x + Math.cos(a1) * radius), (float) (y + Math.sin(a1) * radius),
                (float) (x + Math.cos(a2) * radius), (float) (y + Math.sin(a2) * radius), argb);
        }
    }

    /** A disc with a contrasting ring (handles). */
    static void handle(DrawContext c, double x, double y, double radius, int fill, int ring) {
        disc(c, x, y, radius + 1, ring);
        disc(c, x, y, radius, fill);
    }

    static void diamond(DrawContext c, double x, double y, double radius, int argb) {
        quad(c, (float) x, (float) (y - radius), (float) (x - radius), (float) y, (float) x, (float) (y + radius), (float) (x + radius), (float) y, argb);
    }

    /** Small padlock (7 × 8 px) at x, y — closed or open shackle. */
    static void padlock(DrawContext c, int x, int y, boolean locked, int ink) {
        final int sx = locked ? x + 1 : x + 3;
        // shackle
        FlatUi.rect(c, sx, y, 5, 1, ink);
        FlatUi.rect(c, sx, y, 1, 4, ink);
        FlatUi.rect(c, sx + 4, y, 1, locked ? 4 : 2, ink);
        // body
        FlatUi.rect(c, x, y + 3, 7, 5, ink);
        FlatUi.rect(c, x + 3, y + 5, 1, 2, FlatUi.PANE);
    }

    /** Distance from a point to a segment. */
    static double segmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double l2 = dx * dx + dy * dy;
        double t = l2 > 1e-12 ? ((px - x1) * dx + (py - y1) * dy) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + dx * t), py - (y1 + dy * t));
    }

    static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
