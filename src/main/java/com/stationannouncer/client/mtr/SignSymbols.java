package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.PosterLayout;
import com.stationannouncer.client.mtraddon.PosterLayout.Surface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.MathHelper;

/**
 * The pictograms a sign's SYMBOL tile can show beside its arrows: the
 * accessibility glyph, ramp, elevator, escalator, stairs, do-not-enter,
 * information, bus and train.
 *
 * <p>A symbol tile is the old ARROW tile: {@code num} 0..7 is still an arrow
 * direction, and {@link #FIRST}{@code + index} is one of these — so signs
 * saved before symbols existed keep their arrows, and nothing about storage
 * or packets changed. Every glyph is authored in a 100-unit box with the
 * Surface primitives (rects, convex polygons, discs), so the editor preview
 * and the block in the world draw the same thing. Pictograms take the panel's
 * ink; the wheelchair and ramp keep their blue tile and do-not-enter its red
 * disc, as on the real signs.</p>
 */
@Environment(EnvType.CLIENT)
public final class SignSymbols {
    private SignSymbols() {
    }

    /** {@code num} of the first symbol; everything below is an arrow direction. */
    public static final int FIRST = 16;

    public static final int WHEELCHAIR = 0;
    public static final int RAMP = 1;
    public static final int ELEVATOR = 2;
    public static final int ESCALATOR = 3;
    public static final int STAIRS = 4;
    public static final int NO_ENTRY = 5;
    public static final int INFO = 6;
    public static final int BUS = 7;
    public static final int TRAIN = 8;

    public static final String[] NAMES = {"Wheelchair", "Ramp", "Elevator", "Escalator", "Stairs",
            "Do not enter", "Information", "Bus", "Train"};

    /** Inline token keys, {@code {s:KEY}} in a text tile — same order as {@link #NAMES}. */
    public static final String[] KEYS = {"wheelchair", "ramp", "elevator", "escalator", "stairs",
            "noentry", "info", "bus", "train"};

    /** The {@code num} of the symbol with this token key, or -1. */
    public static int byKey(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equalsIgnoreCase(key)) {
                return FIRST + i;
            }
        }
        return -1;
    }

    private static final int BLUE = 0xFF157AC0;
    private static final int RED = 0xFFEE352E;
    private static final int WHITE = 0xFFF7F7F7;

    public static boolean isSymbol(int num) {
        return num >= FIRST && num < FIRST + NAMES.length;
    }

    public static String name(int num) {
        return isSymbol(num) ? NAMES[num - FIRST] : "";
    }

    /**
     * Draws symbol {@code num} in the square whose top-left is x/y.
     * {@code ink} is the panel's text colour and {@code background} the panel itself
     * (what a cut-out — a bus windscreen, the "i" — shows through to).
     */
    public static void draw(Surface s, int num, float x, float y, float size, int ink, int background, int layer) {
        float u = size / 100.0f;
        switch (num - FIRST) {
            case WHEELCHAIR -> PosterLayout.wheelchair(s, x, y, size, layer, false, background);
            case RAMP -> {
                PosterLayout.roundedSquare(s, x, y, size, 10 * u, BLUE, layer);
                PosterLayout.wheelchairFigure(s, x + 8 * u, y + 22 * u, 70 * u, WHITE, layer + 2);
                s.poly(new float[]{x + 8 * u, x + 92 * u, x + 92 * u}, new float[]{y + 92 * u, y + 92 * u, y + 72 * u},
                        WHITE, layer + 2);
            }
            case ELEVATOR -> {
                frame(s, x + 10 * u, y + 10 * u, 80 * u, 7 * u, ink, layer);
                s.poly(new float[]{x + 50 * u, x + 69 * u, x + 31 * u}, new float[]{y + 23 * u, y + 45 * u, y + 45 * u}, ink, layer);
                s.poly(new float[]{x + 31 * u, x + 69 * u, x + 50 * u}, new float[]{y + 55 * u, y + 55 * u, y + 77 * u}, ink, layer);
            }
            case ESCALATOR -> {
                // The handrail band: low landing, the incline, high landing — and a rider on it.
                s.rect(x + 6 * u, y + 74 * u, x + 32 * u, y + 86 * u, ink, layer);
                line(s, x + 28 * u, y + 80 * u, x + 72 * u, y + 36 * u, 12 * u, ink, layer);
                s.rect(x + 68 * u, y + 30 * u, x + 94 * u, y + 42 * u, ink, layer);
                s.disc(x + 42 * u, y + 20 * u, 8 * u, ink, layer);
                s.rect(x + 36 * u, y + 31 * u, x + 48 * u, y + 56 * u, ink, layer);
            }
            case STAIRS -> {
                for (int i = 0; i < 4; i++) {
                    s.rect(x + (10 + 20 * i) * u, y + (74 - 15 * i) * u, x + 90 * u, y + (89 - 15 * i) * u, ink, layer);
                }
            }
            case NO_ENTRY -> {
                s.disc(x + 50 * u, y + 50 * u, 48 * u, RED, layer);
                s.rect(x + 17 * u, y + 41 * u, x + 83 * u, y + 59 * u, WHITE, layer + 1);
            }
            case INFO -> {
                s.disc(x + 50 * u, y + 50 * u, 46 * u, ink, layer);
                s.disc(x + 50 * u, y + 27 * u, 8 * u, background, layer + 1);
                s.rect(x + 43 * u, y + 41 * u, x + 57 * u, y + 78 * u, background, layer + 1);
            }
            case BUS -> {
                s.rect(x + 14 * u, y + 12 * u, x + 86 * u, y + 80 * u, ink, layer);
                s.rect(x + 22 * u, y + 20 * u, x + 78 * u, y + 48 * u, background, layer + 1);
                s.disc(x + 28 * u, y + 65 * u, 6 * u, background, layer + 1);
                s.disc(x + 72 * u, y + 65 * u, 6 * u, background, layer + 1);
                s.rect(x + 20 * u, y + 80 * u, x + 34 * u, y + 91 * u, ink, layer);
                s.rect(x + 66 * u, y + 80 * u, x + 80 * u, y + 91 * u, ink, layer);
            }
            case TRAIN -> {
                s.rect(x + 18 * u, y + 8 * u, x + 82 * u, y + 76 * u, ink, layer);
                s.rect(x + 26 * u, y + 17 * u, x + 47 * u, y + 44 * u, background, layer + 1);
                s.rect(x + 53 * u, y + 17 * u, x + 74 * u, y + 44 * u, background, layer + 1);
                s.disc(x + 31 * u, y + 61 * u, 5.5f * u, background, layer + 1);
                s.disc(x + 69 * u, y + 61 * u, 5.5f * u, background, layer + 1);
                line(s, x + 32 * u, y + 78 * u, x + 18 * u, y + 95 * u, 7 * u, ink, layer);
                line(s, x + 68 * u, y + 78 * u, x + 82 * u, y + 95 * u, 7 * u, ink, layer);
            }
            default -> {
            }
        }
    }

    /** A square outline of the given stroke, as four rects (corners shared, never overlapping). */
    private static void frame(Surface s, float x, float y, float size, float stroke, int argb, int layer) {
        s.rect(x, y, x + size, y + stroke, argb, layer);
        s.rect(x, y + size - stroke, x + size, y + size, argb, layer);
        s.rect(x, y + stroke, x + stroke, y + size - stroke, argb, layer);
        s.rect(x + size - stroke, y + stroke, x + size, y + size - stroke, argb, layer);
    }

    /** A straight stroke of the given thickness, square-ended. */
    private static void line(Surface s, float x1, float y1, float x2, float y2, float thickness, int argb, int layer) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = MathHelper.sqrt(dx * dx + dy * dy);
        if (length <= 0) {
            return;
        }
        float nx = -dy / length * thickness / 2.0f;
        float ny = dx / length * thickness / 2.0f;
        s.poly(new float[]{x1 + nx, x2 + nx, x2 - nx, x1 - nx}, new float[]{y1 + ny, y2 + ny, y2 - ny, y1 - ny}, argb, layer);
    }
}
