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
 * Everything is emissive: full-bright position-color quads on {@link #layer()}
 * plus polygon-offset text — the pipeline proven by the PIDS screens.
 *
 * <p>Extracted unchanged from PidsNycRenderer's inner Painter class in 1.7.
 */
@Environment(EnvType.CLIENT)
public class CanvasPainter {
    private static final int CIRCLE_SEGMENTS = 16;
    private static final int TEXT_BLACK = 0xFF101010;

    public static final int FULL_LIGHT = 0xF000F0;

    /**
     * The quad layer every block-entity painter draws on: vanilla's
     * {@code debug_quads} (position-colour, translucent, no culling) with ONE
     * difference — {@code hasCrumbling} is true.
     *
     * <p>Why that matters: while a block is being mined, WorldRenderer hands the
     * block-entity renderer a provider that unions each buffer with the crack
     * overlay — but only for layers whose {@code hasCrumbling()} is true, and
     * debug_quads says false. So everything drawn by our renderers (PIDS
     * screens, mosaic bands, sign panels, stop-marker plates, poster sheets, the
     * hanging PIDS cases…) showed no breaking animation at all; blocks whose
     * model is empty or only a mounting stub looked as if mining did nothing.
     * The overlay consumer projects the crack texture along each vertex's
     * normal, so {@link #quad3d} now also emits one (a no-op for this layer's
     * own position-colour buffer, which has no normal element).</p>
     */
    private static final RenderLayer CANVAS = Layers.CANVAS;

    /** The crumbling-aware position-colour quad layer for block-entity painters. */
    public static RenderLayer layer() {
        return CANVAS;
    }

    /** Subclass only to reach the protected phase builder (the usual custom-layer idiom). */
    private static final class Layers extends RenderLayer {
        static final RenderLayer CANVAS = of(
                "station_announcer_canvas",
                net.minecraft.client.render.VertexFormats.POSITION_COLOR,
                net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
                1536, true, true,
                MultiPhaseParameters.builder()
                        .program(COLOR_PROGRAM)
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .cull(DISABLE_CULLING)
                        .build(false));

        private Layers(String name, net.minecraft.client.render.VertexFormat format,
                       net.minecraft.client.render.VertexFormat.DrawMode mode, int size,
                       boolean crumbling, boolean translucent, Runnable start, Runnable end) {
            super(name, format, mode, size, crumbling, translucent, start, end);
        }
    }

    /** One-shot announcement scroll speed, canvas units/second per unit of text size. */
    public static final float SCROLL_ONCE_SPEED = 2.5f;

    private final MatrixStack matrices;
    private final VertexConsumerProvider consumers;
    private final TextRenderer font;

    /**
     * An alternative font (a resource-pack / mod font id such as MTR's
     * {@code mtr:mtr}); null = the vanilla font. Only {@link #text} and
     * {@link #width} honour it — the marquees keep the vanilla glyph cache.
     */
    @org.jetbrains.annotations.Nullable
    private net.minecraft.util.Identifier fontId;

    public CanvasPainter(MatrixStack matrices, VertexConsumerProvider consumers) {
        this.matrices = matrices;
        this.consumers = consumers;
        this.font = MinecraftClient.getInstance().textRenderer;
    }

    /** Draws and measures plain text with the given font from now on ({@code null} = vanilla). */
    public CanvasPainter withFont(@org.jetbrains.annotations.Nullable net.minecraft.util.Identifier fontId) {
        this.fontId = fontId;
        return this;
    }

    /** The string styled in the painter's font, for the Text overloads of the vanilla renderer. */
    private net.minecraft.text.Text styled(String string) {
        return net.minecraft.text.Text.literal(string)
                .setStyle(net.minecraft.text.Style.EMPTY.withFont(fontId));
    }

    public void quad(float x1, float y1, float x2, float y2, float z, int argb) {
        VertexConsumer buffer = consumers.getBuffer(layer());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        quad3d(buffer, matrix, x1, y1, z, x2, y1, z, x2, y2, z, x1, y2, z, argb);
    }

    /**
     * A prohibition roundel: a filled disc with a white bar across it, the
     * "do not enter" symbol used on entrance signs and trackside warnings.
     *
     * <p>Drawn in front of the disc rather than as a gap in it, so it reads at
     * any size — a hole would vanish once the roundel is small.</p>
     */
    public void prohibitionBullet(float cx, float cy, float radius, int color) {
        circleBullet(cx, cy, radius, color, "", false);
        float halfHeight = Math.max(1.0f, radius * 0.17f);
        float halfWidth = radius * 0.62f;
        quad(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight, -0.8f, 0xFFFFFFFF);
    }

    /** Proper circular route bullet with a centered label. */
    public void circleBullet(float cx, float cy, float radius, int color, String label, boolean inverted) {
        VertexConsumer buffer = consumers.getBuffer(layer());
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
        if (fontId != null) {
            font.draw(styled(string), 0, 0, argb, false, matrices.peek().getPositionMatrix(), consumers,
                    TextRenderer.TextLayerType.POLYGON_OFFSET, 0, FULL_LIGHT);
        } else {
            font.draw(string, 0, 0, argb, false, matrices.peek().getPositionMatrix(), consumers,
                    TextRenderer.TextLayerType.POLYGON_OFFSET, 0, FULL_LIGHT);
        }
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
        long cycleMillis = Math.max(1, (long) (total / speed * 1000));
        float phase = (float) ((System.currentTimeMillis() / 50 * 50) % cycleMillis) * speed / 1000.0f;

        // Reading the loop twice over (index wraps) is the same character
        // sequence the old looped+looped string spelled out.
        text(window(looped, 0, looped.length() * 2, size, phase, maxWidth), x, y, size, argb);
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
        int padding = (int) Math.ceil(maxWidth / spaceWidth);
        float total = padding * spaceWidth + width(string, size);
        float phase = elapsedMillis * SCROLL_ONCE_SPEED * size / 1000.0f;
        if (phase >= total) {
            return; // fully scrolled out
        }
        // The leading spaces walk the text in from the right edge; counting
        // them beats building a padded string every frame.
        text(window(string, padding, padding + string.length(), size, phase, maxWidth), x, y, size, argb);
    }

    /**
     * The characters visible in a marquee window: {@code count} characters
     * made of {@code leadingSpaces} spaces followed by {@code string}
     * (wrapping round if count runs past its end), skipping everything left
     * of {@code phase} and stopping at {@code maxWidth}. Whole characters
     * only, so nothing is ever clipped mid-glyph.
     */
    private String window(String string, int leadingSpaces, int count, float size, float phase, float maxWidth) {
        StringBuilder window = new StringBuilder();
        float skipped = 0;
        float used = 0;
        float scale = size / 8.0f;
        for (int i = 0; i < count; i++) {
            char character = i < leadingSpaces ? ' ' : string.charAt((i - leadingSpaces) % string.length());
            float charWidth = charWidth(character) * scale;
            if (skipped + charWidth <= phase) {
                skipped += charWidth;
                continue;
            }
            if (used + charWidth > maxWidth) {
                break;
            }
            window.append(character);
            used += charWidth;
        }
        return window.toString();
    }

    /**
     * Draws the string at {@code size}, shrinking it as far as
     * {@code minSize} to make it fit {@code maxWidth}, and only falling back
     * to the marquee if even that is too small. Right for headline text — a
     * destination or a line name is worth reading at a glance, which a
     * scrolling one never is.
     *
     * <p>Shrunk text keeps the vertical centre of the slot it was given, so
     * the baseline does not jump around as the text changes.</p>
     */
    public void textFitted(String string, float x, float y, float size, float minSize, int argb, float maxWidth) {
        if (string == null || string.isEmpty() || maxWidth <= 0) {
            return;
        }
        float full = width(string, size);
        if (full <= maxWidth) {
            text(string, x, y, size, argb);
            return;
        }
        float fitted = size * maxWidth / full;
        if (fitted >= minSize) {
            text(string, x, y + (size - fitted) / 2.0f, fitted, argb);
            return;
        }
        textScrolling(string, x, y + (size - minSize) / 2.0f, minSize, argb, maxWidth);
    }

    /**
     * Cuts a string down to {@code maxWidth}, marking the cut with an ellipsis.
     * {@link #wrap} only breaks on whitespace, so a single very long token comes
     * back as one over-wide line — this is what stops it running off the panel.
     */
    public String trimToWidth(String string, float size, float maxWidth) {
        if (string == null || string.isEmpty() || width(string, size) <= maxWidth) {
            return string;
        }
        float scale = size / 8.0f;
        float budget = maxWidth - charWidth('…') * scale;
        if (budget <= 0) {
            return "…";
        }
        StringBuilder trimmed = new StringBuilder();
        float used = 0;
        for (int i = 0; i < string.length(); i++) {
            float charWidth = charWidth(string.charAt(i)) * scale;
            if (used + charWidth > budget) {
                break;
            }
            trimmed.append(string.charAt(i));
            used += charWidth;
        }
        return trimmed.append('…').toString();
    }

    public void textCentered(String string, float centerX, float y, float size, int argb) {
        text(string, centerX - width(string, size) / 2.0f, y, size, argb);
    }

    public void textRight(String string, float rightX, float y, float size, int argb) {
        text(string, rightX - width(string, size), y, size, argb);
    }

    public float width(String string, float size) {
        if (fontId != null) {
            // Exact advance, not TextRenderer's rounded-up int: every word of a sign is measured on
            // its own, and a pixel of rounding per word doubled the gaps between them.
            return font.getTextHandler().getWidth(styled(string)) * size / 8.0f;
        }
        return font.getWidth(string) * size / 8.0f;
    }

    /**
     * Font width of a single character, memoized. The marquee loops ask for
     * these thousands of times a second, and every miss used to cost a
     * one-character String plus a full TextRenderer measuring pass.
     */
    private float charWidth(char character) {
        if (character < CHAR_WIDTH_CACHE.length) {
            float cached = CHAR_WIDTH_CACHE[character];
            if (cached >= 0) {
                return cached;
            }
            float measured = font.getWidth(String.valueOf(character));
            CHAR_WIDTH_CACHE[character] = measured;
            return measured;
        }
        return font.getWidth(String.valueOf(character));
    }

    /** Widths of the Latin-1 range (-1 = not measured yet); reset when resource packs reload. */
    private static final float[] CHAR_WIDTH_CACHE = new float[256];

    static {
        java.util.Arrays.fill(CHAR_WIDTH_CACHE, -1.0f);
    }

    /** Drops memoized glyph widths (the font can change with a resource pack). */
    public static void clearFontCache() {
        java.util.Arrays.fill(CHAR_WIDTH_CACHE, -1.0f);
    }

    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    public List<String> wrap(String message, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : WHITESPACE.split(message)) {
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

    /**
     * Double-sided quad (both windings) so cull state never hides it.
     *
     * <p>Each winding carries its own face normal (the vanilla crack overlay
     * needs one to pick the projection plane; the crumbling layer culls back
     * faces, so the two opposite normals give each side exactly one crack
     * pattern). The normal is computed from the already-transformed corners —
     * the overlay consumer sees view-space positions and undoes the block's
     * own matrix itself — and via the diagonals, so a fan triangle written as
     * a quad with a repeated corner still gets a proper one.</p>
     */
    public static void quad3d(VertexConsumer buffer, Matrix4f matrix,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float cx, float cy, float cz, float dx, float dy, float dz, int argb) {
        float a = (argb >> 24 & 0xFF) / 255.0f;
        float r = (argb >> 16 & 0xFF) / 255.0f;
        float g = (argb >> 8 & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        // Transform the corners once by hand (no per-quad vector allocation —
        // this runs for every quad of every screen, every frame).
        float m00 = matrix.m00(), m10 = matrix.m10(), m20 = matrix.m20(), m30 = matrix.m30();
        float m01 = matrix.m01(), m11 = matrix.m11(), m21 = matrix.m21(), m31 = matrix.m31();
        float m02 = matrix.m02(), m12 = matrix.m12(), m22 = matrix.m22(), m32 = matrix.m32();
        float pax = m00 * ax + m10 * ay + m20 * az + m30;
        float pay = m01 * ax + m11 * ay + m21 * az + m31;
        float paz = m02 * ax + m12 * ay + m22 * az + m32;
        float pbx = m00 * bx + m10 * by + m20 * bz + m30;
        float pby = m01 * bx + m11 * by + m21 * bz + m31;
        float pbz = m02 * bx + m12 * by + m22 * bz + m32;
        float pcx = m00 * cx + m10 * cy + m20 * cz + m30;
        float pcy = m01 * cx + m11 * cy + m21 * cz + m31;
        float pcz = m02 * cx + m12 * cy + m22 * cz + m32;
        float pdx = m00 * dx + m10 * dy + m20 * dz + m30;
        float pdy = m01 * dx + m11 * dy + m21 * dz + m31;
        float pdz = m02 * dx + m12 * dy + m22 * dz + m32;
        // Right-hand rule over the diagonals: (c - a) x (d - b).
        float e1x = pcx - pax, e1y = pcy - pay, e1z = pcz - paz;
        float e2x = pdx - pbx, e2y = pdy - pby, e2z = pdz - pbz;
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = MathHelper.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-12f) {
            nx /= len;
            ny /= len;
            nz /= len;
        } else {
            nx = 0.0f;
            ny = 1.0f;
            nz = 0.0f;
        }
        buffer.vertex(pax, pay, paz).color(r, g, b, a).normal(nx, ny, nz).next();
        buffer.vertex(pbx, pby, pbz).color(r, g, b, a).normal(nx, ny, nz).next();
        buffer.vertex(pcx, pcy, pcz).color(r, g, b, a).normal(nx, ny, nz).next();
        buffer.vertex(pdx, pdy, pdz).color(r, g, b, a).normal(nx, ny, nz).next();
        buffer.vertex(pdx, pdy, pdz).color(r, g, b, a).normal(-nx, -ny, -nz).next();
        buffer.vertex(pcx, pcy, pcz).color(r, g, b, a).normal(-nx, -ny, -nz).next();
        buffer.vertex(pbx, pby, pbz).color(r, g, b, a).normal(-nx, -ny, -nz).next();
        buffer.vertex(pax, pay, paz).color(r, g, b, a).normal(-nx, -ny, -nz).next();
    }
}
