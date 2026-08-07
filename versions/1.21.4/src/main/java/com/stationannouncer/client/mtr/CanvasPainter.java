package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

/**
 * Canvas-space drawing helpers shared by the PIDS and station-decor block
 * entity renderers (x right, y down, z toward the viewer at negative values).
 * Everything is emissive: full-bright position-color quads on the debug-quad
 * layer plus polygon-offset text — the pipeline proven by the PIDS screens.
 *
 * <p>Extracted unchanged from PidsNycRenderer's inner Painter class in 1.7.
 */
@Environment(EnvType.CLIENT)
public class CanvasPainter {
    private static final int CIRCLE_SEGMENTS = 16;
    private static final int TEXT_BLACK = 0xFF101010;

    public static final int FULL_LIGHT = 0xF000F0;

    /** One-shot announcement scroll speed, canvas units/second per unit of text size. */
    public static final float SCROLL_ONCE_SPEED = 2.5f;

    private final MatrixStack matrices;
    private final VertexConsumerProvider consumers;
    private final TextRenderer font;

    public CanvasPainter(MatrixStack matrices, VertexConsumerProvider consumers) {
        this.matrices = matrices;
        this.consumers = consumers;
        this.font = MinecraftClient.getInstance().textRenderer;
    }

    public void quad(float x1, float y1, float x2, float y2, float z, int argb) {
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        quad3d(buffer, matrix, x1, y1, z, x2, y1, z, x2, y2, z, x1, y2, z, argb);
    }

    /** Proper circular route bullet with a centered label. */
    public void circleBullet(float cx, float cy, float radius, int color, String label, boolean inverted) {
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            float a1 = (float) (2 * Math.PI * i / CIRCLE_SEGMENTS);
            float a2 = (float) (2 * Math.PI * (i + 1) / CIRCLE_SEGMENTS);
            float x1 = cx + radius * MathHelper.cos(a1);
            float y1 = cy + radius * MathHelper.sin(a1);
            float x2 = cx + radius * MathHelper.cos(a2);
            float y2 = cy + radius * MathHelper.sin(a2);
            quad3d(buffer, matrix, cx, cy, -0.6f, x1, y1, -0.6f, x2, y2, -0.6f, cx, cy, -0.6f, color);
        }
        float size = radius * 1.1f;
        textCentered(label, cx, cy - size / 2.0f, size, inverted ? TEXT_BLACK : 0xFFFFFFFF);
    }

    public void text(String string, float x, float y, float size, int argb) {
        if (string == null || string.isEmpty()) {
            return;
        }
        matrices.push();
        matrices.translate(x, y, -1.2f);
        float scale = size / 8.0f;
        matrices.scale(scale, scale, scale);
        font.draw(string, 0, 0, argb, false, matrices.peek().getPositionMatrix(), consumers,
                TextRenderer.TextLayerType.POLYGON_OFFSET, 0, FULL_LIGHT);
        matrices.pop();
    }

    /**
     * Draws the string at x/y; if wider than maxWidth it scrolls
     * LED-marquee style (whole characters shift through the window, so
     * nothing ever overlaps or bleeds past the edge).
     */
    public void textScrolling(String string, float x, float y, float size, int argb, float maxWidth) {
        if (string == null || string.isEmpty() || maxWidth <= 0) {
            return;
        }
        if (width(string, size) <= maxWidth) {
            text(string, x, y, size, argb);
            return;
        }
        String looped = string + "     ";
        float total = width(looped, size);
        float speed = size * 1.5f; // canvas units per second
        float phase = (float) ((System.currentTimeMillis() / 50 * 50) % (long) (total / speed * 1000))
                * speed / 1000.0f;

        StringBuilder window = new StringBuilder();
        float skipped = 0;
        float used = 0;
        String doubled = looped + looped;
        for (int i = 0; i < doubled.length(); i++) {
            String ch = String.valueOf(doubled.charAt(i));
            float charWidth = width(ch, size);
            if (skipped + charWidth <= phase) {
                skipped += charWidth;
                continue;
            }
            if (used + charWidth > maxWidth) {
                break;
            }
            window.append(ch);
            used += charWidth;
        }
        text(window.toString(), x, y, size, argb);
    }

    /**
     * One-pass marquee for live announcements: the text enters from the
     * right edge {@code elapsedMillis} after the announcement started,
     * scrolls across and exits left (whole-character window, same
     * LED-marquee style as {@link #textScrolling}). Draws nothing once
     * the text has fully scrolled out.
     */
    public void textScrollOnce(String string, long elapsedMillis, float x, float y, float size, int argb, float maxWidth) {
        if (string == null || string.isEmpty() || maxWidth <= 0) {
            return;
        }
        float spaceWidth = Math.max(width(" ", size), 0.1f);
        String padded = " ".repeat((int) Math.ceil(maxWidth / spaceWidth)) + string;
        float total = width(padded, size);
        float phase = elapsedMillis * SCROLL_ONCE_SPEED * size / 1000.0f;
        if (phase >= total) {
            return; // fully scrolled out
        }
        StringBuilder window = new StringBuilder();
        float skipped = 0;
        float used = 0;
        for (int i = 0; i < padded.length(); i++) {
            String ch = String.valueOf(padded.charAt(i));
            float charWidth = width(ch, size);
            if (skipped + charWidth <= phase) {
                skipped += charWidth;
                continue;
            }
            if (used + charWidth > maxWidth) {
                break;
            }
            window.append(ch);
            used += charWidth;
        }
        text(window.toString(), x, y, size, argb);
    }

    public void textCentered(String string, float centerX, float y, float size, int argb) {
        text(string, centerX - width(string, size) / 2.0f, y, size, argb);
    }

    public void textRight(String string, float rightX, float y, float size, int argb) {
        text(string, rightX - width(string, size), y, size, argb);
    }

    public float width(String string, float size) {
        return font.getWidth(string) * size / 8.0f;
    }

    public List<String> wrap(String message, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : message.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(candidate, size) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    // ------------------------------------------------------- 3D primitives

    /** Solid axis-aligned box of full-bright color quads. */
    public static void box(VertexConsumer buffer, Matrix4f matrix,
                           float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        quad3d(buffer, matrix, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, argb);
        quad3d(buffer, matrix, x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2, argb);
        quad3d(buffer, matrix, x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2, argb);
        quad3d(buffer, matrix, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, argb);
        quad3d(buffer, matrix, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2, argb);
        quad3d(buffer, matrix, x1, y1, z1, x1, y1, z2, x2, y1, z2, x2, y1, z1, argb);
    }

    /** Double-sided quad (both windings) so cull state never hides it. */
    public static void quad3d(VertexConsumer buffer, Matrix4f matrix,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float cx, float cy, float cz, float dx, float dy, float dz, int argb) {
        float a = (argb >> 24 & 0xFF) / 255.0f;
        float r = (argb >> 16 & 0xFF) / 255.0f;
        float g = (argb >> 8 & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        buffer.vertex(matrix, ax, ay, az).color(r, g, b, a).next();
        buffer.vertex(matrix, bx, by, bz).color(r, g, b, a).next();
        buffer.vertex(matrix, cx, cy, cz).color(r, g, b, a).next();
        buffer.vertex(matrix, dx, dy, dz).color(r, g, b, a).next();
        buffer.vertex(matrix, dx, dy, dz).color(r, g, b, a).next();
        buffer.vertex(matrix, cx, cy, cz).color(r, g, b, a).next();
        buffer.vertex(matrix, bx, by, bz).color(r, g, b, a).next();
        buffer.vertex(matrix, ax, ay, az).color(r, g, b, a).next();
    }
}
