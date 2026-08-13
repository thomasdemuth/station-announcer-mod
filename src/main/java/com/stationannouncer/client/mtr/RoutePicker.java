package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The line chooser for entrance signs: the routes calling at this station,
 * each shown as the bullet it will become on the sign, with a checkbox.
 *
 * <p>Drawn by hand like {@link PlatformPicker} (whose layout it follows), and
 * for the same reason: a vanilla list widget cannot show a coloured disc beside
 * a label, and the disc IS the thing being chosen.</p>
 *
 * <p>Selection is ORDERED — bullets appear on the sign in the order they were
 * ticked, which is how a real sign is laid out (2 then 3, not whatever order
 * MTR happens to store its routes in).</p>
 */
@Environment(EnvType.CLIENT)
public class RoutePicker {
    public static final int ROW_HEIGHT = 18;
    public static final int MAX_VISIBLE_ROWS = 5;
    public static final int MIN_VISIBLE_ROWS = 2;

    private static final int CHECKBOX = 11;
    private static final int BULLET = 13;
    private static final int SCROLLBAR_WIDTH = 3;

    private static final int PANEL_BG = 0xF0111116;
    private static final int PANEL_BORDER = 0xFF3A3A45;
    private static final int ROW_HOVER = 0x18FFFFFF;
    private static final int ROW_SELECTED = 0x403C7DD9;
    private static final int TEXT = 0xFFF0F0F2;
    private static final int TEXT_FAINT = 0xFF6E6E78;
    private static final int CHECK_BORDER = 0xFF8A8A95;
    private static final int CHECK_FILL = 0xFF3C7DD9;

    private final List<RouteBullets.Option> routes;
    /** Insertion-ordered: the tick order is the order the bullets are drawn in. */
    private final Set<String> selected = new LinkedHashSet<>();
    private final int maxSelected;
    private final int width;

    private int left;
    private int top;
    private int scroll;
    private int visibleRows = MAX_VISIBLE_ROWS;

    public RoutePicker(BlockPos origin, int maxSelected, int width) {
        this.maxSelected = maxSelected;
        this.width = width;
        this.routes = RouteBullets.atStation(origin);
    }

    public void setPosition(int left, int top) {
        this.left = left;
        this.top = top;
    }

    /** Shrinks the list rather than letting a short window push Done off the bottom. */
    public void fitTo(int availableHeight) {
        visibleRows = Math.max(MIN_VISIBLE_ROWS, Math.min(MAX_VISIBLE_ROWS, availableHeight / ROW_HEIGHT));
        scroll = Math.min(scroll, maxScroll());
    }

    public int getHeight() {
        return ROW_HEIGHT * visibleRows;
    }

    public boolean isEmpty() {
        return routes.isEmpty();
    }

    /** The ticked routes, in tick order. */
    public List<String> getSelected() {
        return List.copyOf(selected);
    }

    /**
     * Replaces the whole selection — used when the screen switches which face
     * it is editing, so each face keeps its own list of bullets.
     */
    public void setSelected(List<String> routeNames) {
        selected.clear();
        for (String routeName : routeNames) {
            if (selected.size() >= maxSelected) {
                break;
            }
            selected.add(routeName);
        }
    }

    /**
     * How a stored route draws, whether or not it is one of the listed ones —
     * a sign carried over from another station still shows its bullets.
     */
    public static RouteBullets.Bullet bulletFor(String routeName) {
        return RouteBullets.bulletFor(routeName);
    }

    // ------------------------------------------------------------ behaviour

    private int maxScroll() {
        return Math.max(0, routes.size() * ROW_HEIGHT - getHeight());
    }

    private boolean inside(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + getHeight();
    }

    private int rowAt(double mouseX, double mouseY) {
        if (!inside(mouseX, mouseY)) {
            return -1;
        }
        int index = (int) ((mouseY - top + scroll) / ROW_HEIGHT);
        return index >= 0 && index < routes.size() ? index : -1;
    }

    /** Returns true when the click was consumed by the list. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        String routeName = routes.get(row).routeName();
        if (!selected.remove(routeName) && selected.size() < maxSelected) {
            selected.add(routeName);
        }
        return true;
    }

    /** Returns true when the scroll was consumed by the list. */
    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (!inside(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (verticalAmount * ROW_HEIGHT / 2)));
        return true;
    }

    // -------------------------------------------------------------- drawing

    public void render(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
        int right = left + width;
        int bottom = top + getHeight();
        context.fill(left, top, right, bottom, PANEL_BG);
        context.fill(left, top, right, top + 1, PANEL_BORDER);
        context.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
        context.fill(left, top, left + 1, bottom, PANEL_BORDER);
        context.fill(right - 1, top, right, bottom, PANEL_BORDER);

        if (routes.isEmpty()) {
            context.drawCenteredTextWithShadow(font,
                    Text.translatable("gui.station_announcer.railing_sign.no_routes"),
                    left + width / 2, top + getHeight() / 2 - 4, TEXT_FAINT);
            return;
        }

        int hovered = rowAt(mouseX, mouseY);
        context.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        for (int i = 0; i < routes.size(); i++) {
            int rowY = top + i * ROW_HEIGHT - scroll;
            if (rowY + ROW_HEIGHT < top || rowY > bottom) {
                continue;
            }
            drawRow(context, font, routes.get(i), rowY, i == hovered);
        }
        context.disableScissor();

        int max = maxScroll();
        if (max > 0) {
            int trackHeight = getHeight() - 4;
            int thumb = Math.max(16, trackHeight * getHeight() / (routes.size() * ROW_HEIGHT));
            int thumbY = top + 2 + (trackHeight - thumb) * scroll / max;
            context.fill(right - 1 - SCROLLBAR_WIDTH, thumbY, right - 1, thumbY + thumb, 0x60FFFFFF);
        }
    }

    private void drawRow(DrawContext context, TextRenderer font, RouteBullets.Option option, int rowY, boolean hovered) {
        int right = left + width;
        boolean checked = selected.contains(option.routeName());
        if (checked) {
            context.fill(left + 1, rowY, right - 1, rowY + ROW_HEIGHT, ROW_SELECTED);
        }
        if (hovered) {
            context.fill(left + 1, rowY, right - 1, rowY + ROW_HEIGHT, ROW_HOVER);
        }

        int boxX = left + 6;
        int boxY = rowY + 3;
        context.fill(boxX, boxY, boxX + CHECKBOX, boxY + CHECKBOX, checked ? CHECK_FILL : 0x40000000);
        context.fill(boxX, boxY, boxX + CHECKBOX, boxY + 1, CHECK_BORDER);
        context.fill(boxX, boxY + CHECKBOX - 1, boxX + CHECKBOX, boxY + CHECKBOX, CHECK_BORDER);
        context.fill(boxX, boxY, boxX + 1, boxY + CHECKBOX, CHECK_BORDER);
        context.fill(boxX + CHECKBOX - 1, boxY, boxX + CHECKBOX, boxY + CHECKBOX, CHECK_BORDER);
        if (checked) {
            context.drawTextWithShadow(font, "✔", boxX + 2, boxY + 2, 0xFFFFFFFF);
        }

        // The bullet exactly as the sign will draw it, so the choice is literal.
        drawBullet(context, font, option.bullet(), boxX + CHECKBOX + 6, rowY + 2);

        int textX = boxX + CHECKBOX + 6 + BULLET + 6;
        context.drawTextWithShadow(font, font.trimToWidth(option.displayName(), right - 7 - textX),
                textX, rowY + 5, TEXT);
    }

    /** A rounded {@value #BULLET}-pixel disc with its label, drawn in GUI space. */
    public static void drawBullet(DrawContext context, TextRenderer font, RouteBullets.Bullet bullet, int x, int y) {
        // Four fills approximate a circle at this size better than one square:
        // a full-height core column, a full-width core row, and the corners left out.
        context.fill(x + 2, y, x + BULLET - 2, y + BULLET, bullet.color());
        context.fill(x, y + 2, x + BULLET, y + BULLET - 2, bullet.color());
        context.fill(x + 1, y + 1, x + BULLET - 1, y + BULLET - 1, bullet.color());
        if (bullet.label().isEmpty() && bullet.color() == RouteBullets.NO_ENTRY_COLOR) {
            // The no-entry roundel: the white bar is the symbol, so the row
            // shows the bar rather than a blank red disc.
            context.fill(x + 2, y + BULLET / 2 - 1, x + BULLET - 2, y + BULLET / 2 + 1, 0xFFFFFFFF);
            return;
        }
        int textColor = RouteBullets.needsDarkText(bullet.color()) ? 0xFF101014 : 0xFFFFFFFF;
        int labelWidth = font.getWidth(bullet.label());
        context.drawText(font, bullet.label(), x + (BULLET - labelWidth) / 2, y + 3, textColor, false);
    }
}
