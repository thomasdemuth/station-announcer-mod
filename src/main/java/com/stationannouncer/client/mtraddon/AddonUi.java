package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * The shared look of the addon's hand-drawn screens, lifted straight from this
 * project's existing custom GUIs ({@code PlatformPicker}, the PIDS settings
 * screens): dark bordered panels, list rows with hover/selection washes, coloured
 * route chips with automatically readable text, section captions, and a thin
 * scroll indicator instead of a vanilla scroll bar.
 *
 * <p>Client-only, stateless, render-thread only. Nothing here changes behaviour —
 * it exists so the disruption screens stop looking like a stack of vanilla
 * buttons.</p>
 */
@Environment(EnvType.CLIENT)
public final class AddonUi {
    public static final int PANEL_BG = 0xF0111116;
    public static final int PANEL_BORDER = 0xFF3A3A45;
    public static final int SECTION_BG = 0x50000000;
    public static final int ROW_HOVER = 0x18FFFFFF;
    public static final int ROW_SELECTED = 0x403C7DD9;
    public static final int ROW_DIVIDER = 0x18FFFFFF;
    public static final int TEXT = 0xFFF0F0F2;
    public static final int TEXT_DIM = 0xFF9A9AA5;
    public static final int TEXT_FAINT = 0xFF6E6E78;
    public static final int CHECK_BORDER = 0xFF8A8A95;
    public static final int CHECK_FILL = 0xFF3C7DD9;
    public static final int CHECK_PARTIAL = 0x903C7DD9;
    public static final int BUTTON_BG = 0xFF2A2A33;
    public static final int BUTTON_BG_HOVER = 0xFF3D3D4A;
    public static final int DANGER = 0xFFFF5555;
    public static final int OK = 0xFF66DD88;

    public static final int SCROLLBAR_WIDTH = 3;
    public static final int CHECKBOX = 11;

    private AddonUi() {
    }

    /** A dark bordered panel — the container every list sits in. */
    public static void panel(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, bottom, PANEL_BG);
        context.fill(left, top, right, top + 1, PANEL_BORDER);
        context.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
        context.fill(left, top, left + 1, bottom, PANEL_BORDER);
        context.fill(right - 1, top, right, bottom, PANEL_BORDER);
    }

    /** A caption above a section, in the muted heading colour. */
    public static void caption(DrawContext context, TextRenderer font, Text text, int x, int y) {
        context.drawTextWithShadow(font, text, x, y, TEXT_DIM);
    }

    /** Tri-state tick box: 0 = empty, 1 = partial, 2 = full. */
    public static void checkbox(DrawContext context, TextRenderer font, int x, int y, int state) {
        context.fill(x, y, x + CHECKBOX, y + CHECKBOX,
                state == 2 ? CHECK_FILL : state == 1 ? CHECK_PARTIAL : 0x40000000);
        context.fill(x, y, x + CHECKBOX, y + 1, CHECK_BORDER);
        context.fill(x, y + CHECKBOX - 1, x + CHECKBOX, y + CHECKBOX, CHECK_BORDER);
        context.fill(x, y, x + 1, y + CHECKBOX, CHECK_BORDER);
        context.fill(x + CHECKBOX - 1, y, x + CHECKBOX, y + CHECKBOX, CHECK_BORDER);
        if (state == 2) {
            context.drawTextWithShadow(font, "x", x + 3, y + 2, 0xFFFFFFFF);
        } else if (state == 1) {
            context.fill(x + 3, y + 5, x + CHECKBOX - 3, y + 6, 0xFFFFFFFF);
        }
    }

    /**
     * A coloured pill with automatically readable text — the route/line chip and
     * the severity badge use the same primitive. Returns the chip's width so
     * callers can lay several out in a row.
     */
    public static int chip(DrawContext context, TextRenderer font, String label, int x, int y, int color) {
        int chipWidth = font.getWidth(label) + 8;
        context.fill(x, y, x + chipWidth, y + 11, color);
        context.drawText(font, label, x + 4, y + 2, readableOn(color), false);
        return chipWidth;
    }

    /** A small hand-drawn inline button; returns true when the cursor is over it. */
    public static boolean inlineButton(DrawContext context, TextRenderer font, String label,
                                       int x, int y, int buttonWidth, int buttonHeight,
                                       int mouseX, int mouseY, int labelColor) {
        boolean hovered = mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + buttonHeight;
        context.fill(x, y, x + buttonWidth, y + buttonHeight, hovered ? BUTTON_BG_HOVER : BUTTON_BG);
        context.fill(x, y, x + buttonWidth, y + 1, PANEL_BORDER);
        context.fill(x, y + buttonHeight - 1, x + buttonWidth, y + buttonHeight, PANEL_BORDER);
        context.fill(x, y, x + 1, y + buttonHeight, PANEL_BORDER);
        context.fill(x + buttonWidth - 1, y, x + buttonWidth, y + buttonHeight, PANEL_BORDER);
        int textX = x + (buttonWidth - font.getWidth(label)) / 2;
        context.drawTextWithShadow(font, label, textX, y + (buttonHeight - 8) / 2, labelColor);
        return hovered;
    }

    /** The thin scroll indicator used instead of a vanilla scroll bar. */
    public static void scrollIndicator(DrawContext context, int right, int top, int viewHeight,
                                       int contentHeight, int scroll) {
        int max = Math.max(0, contentHeight - viewHeight);
        if (max <= 0) {
            return;
        }
        int trackHeight = viewHeight - 4;
        int thumb = Math.max(16, trackHeight * viewHeight / contentHeight);
        int thumbY = top + 2 + (trackHeight - thumb) * scroll / max;
        context.fill(right - 1 - SCROLLBAR_WIDTH, thumbY, right - 1, thumbY + thumb, 0x60FFFFFF);
    }

    /** Black or white, whichever reads on the given chip colour. */
    public static int readableOn(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? 0xFF101014 : 0xFFFFFFFF;
    }

    /** MTR names are "English|Other Language" — display the first segment. */
    public static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        String first = (split >= 0 ? raw.substring(0, split) : raw).trim();
        return first.isEmpty() ? raw.trim() : first;
    }

    /**
     * MTR separates the LINE from its DIRECTION with a DOUBLE pipe
     * ({@code "Line 1||Northbound"}) — a single pipe is the language separator.
     * Verified against MTR FABRIC-4.0.1+1.20.4: {@code VehicleExtension
     * .formatRouteName} is exactly {@code routeName.split("\\|\\|")[0]}.
     *
     * @return {@code [lineName, direction]}, both already reduced to their first
     * language segment; {@code direction} is empty when the route has no subtype.
     */
    public static String[] splitLineAndDirection(String rawRouteName) {
        if (rawRouteName == null) {
            return new String[]{"", ""};
        }
        String[] parts = rawRouteName.split("\\|\\|", -1);
        String line = firstLang(parts[0]);
        String direction = parts.length > 1 ? firstLang(parts[1]) : "";
        return new String[]{line, direction};
    }
}
