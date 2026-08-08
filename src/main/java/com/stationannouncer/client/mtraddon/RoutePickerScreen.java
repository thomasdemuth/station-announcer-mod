package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Route;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ticks the lines a disruption affects. Drawn by hand rather than out of vanilla
 * widgets, in the same spirit as the shared {@code PlatformPicker} (which is
 * platform-scoped and therefore not reusable here): one row per route of
 * {@code MinecraftClientData.getDashboardInstance().routes}, in the route's own
 * colour, with a checkbox and the line's stop count.
 */
@Environment(EnvType.CLIENT)
public class RoutePickerScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int GAP = 4;
    private static final int CHECKBOX = 11;
    private static final int MAX_ROWS_TOTAL = 512;

    private static final int PANEL_BG = 0xF0111116;
    private static final int PANEL_BORDER = 0xFF3A3A45;
    private static final int ROW_HOVER = 0x18FFFFFF;
    private static final int ROW_SELECTED = 0x403C7DD9;
    private static final int CHECK_BORDER = 0xFF8A8A95;
    private static final int CHECK_FILL = 0xFF3C7DD9;

    private record Row(long routeId, String name, int color, int stops) {
    }

    private final Set<Long> selection;
    private final DisruptionEditScreen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scrollOffset;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;

    public RoutePickerScreen(Set<Long> selection, DisruptionEditScreen parent) {
        super(Text.translatable("gui.station_announcer.disruptions.lines_title"));
        this.selection = selection;
        this.parent = parent;
        for (Route route : MinecraftClientData.getDashboardInstance().routes) {
            if (rows.size() >= MAX_ROWS_TOTAL) {
                break;
            }
            rows.add(new Row(route.getId(), PlatformGroupScreen.firstLang(route.getName()),
                    route.getColor(), route.getRoutePlatforms().size()));
        }
        rows.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;
        int bottomBlock = WIDGET_HEIGHT + GAP * 2;
        int available = height - 44 - bottomBlock;
        visibleRows = Math.max(1, Math.min(Math.max(rows.size(), 1), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows)));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, listTop + visibleRows * ROW_HEIGHT + GAP, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            int index = scrollOffset + (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < rows.size()) {
                long routeId = rows.get(index).routeId();
                if (!selection.remove(routeId)) {
                    selection.add(routeId);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rows.size() > visibleRows && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            scrollOffset = Math.max(0, Math.min(rows.size() - visibleRows,
                    scrollOffset - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (client != null) {
            // setScreen re-runs the editor's init(), which rebuilds the
            // "Affected lines: N" label from the shared selection set.
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        int listHeight = visibleRows * ROW_HEIGHT;
        context.fill(listLeft - 1, listTop - 1, listLeft + PANEL_WIDTH + 1, listTop + listHeight + 1, PANEL_BORDER);
        context.fill(listLeft, listTop, listLeft + PANEL_WIDTH, listTop + listHeight, PANEL_BG);

        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.disruptions.no_routes"),
                    listLeft + 4, listTop + 6, DisruptionsScreen.TEXT_DIM);
            return;
        }

        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            boolean selected = selection.contains(row.routeId());
            boolean hovered = mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected) {
                context.fill(listLeft, rowY, listLeft + PANEL_WIDTH, rowY + ROW_HEIGHT, ROW_SELECTED);
            } else if (hovered) {
                context.fill(listLeft, rowY, listLeft + PANEL_WIDTH, rowY + ROW_HEIGHT, ROW_HOVER);
            }
            int boxY = rowY + (ROW_HEIGHT - CHECKBOX) / 2;
            context.fill(listLeft + 4, boxY, listLeft + 4 + CHECKBOX, boxY + CHECKBOX, CHECK_BORDER);
            context.fill(listLeft + 5, boxY + 1, listLeft + 3 + CHECKBOX, boxY + CHECKBOX - 1,
                    selected ? CHECK_FILL : PANEL_BG);
            context.drawTextWithShadow(textRenderer,
                    textRenderer.trimToWidth(row.name(), PANEL_WIDTH - CHECKBOX - 90),
                    listLeft + CHECKBOX + 10, rowY + 3, 0xFF000000 | row.color());
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.disruptions.line_stops", row.stops()),
                    listLeft + PANEL_WIDTH - 76, rowY + 3, DisruptionsScreen.TEXT_DIM);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
