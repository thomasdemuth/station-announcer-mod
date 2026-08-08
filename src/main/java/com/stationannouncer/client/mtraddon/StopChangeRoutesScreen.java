package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Route;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the line whose temporary stop changes you want to manage. Every route
 * carries a marker when it has an active change, plus the last path-generation
 * status of the depots that run it — a temporary change only reaches the trains
 * when the depot regenerates, and a skip whose neighbours cannot be joined by
 * track shows up there as MTR's own {@code PATH_NOT_FOUND}.
 */
@Environment(EnvType.CLIENT)
public class StopChangeRoutesScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int GAP = 4;
    private static final int EDIT_WIDTH = 60;
    private static final int MAX_ROWS_TOTAL = 512;
    private static final int MARKER = 0xFFFFAA33;
    private static final int BAD = 0xFFFF5555;

    private record Row(Route route, String name, int stops) {
    }

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scrollOffset;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public StopChangeRoutesScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.stop_changes.title"));
        this.parent = parent;
        for (Route route : MinecraftClientData.getDashboardInstance().routes) {
            if (rows.size() >= MAX_ROWS_TOTAL) {
                break;
            }
            // MTR packs the direction into the name after a DOUBLE pipe
            // ("Line 1||Northbound"); show both parts so sub-routes are tellable apart.
            String[] split = AddonUi.splitLineAndDirection(route.getName());
            String label = split[1].isEmpty() ? split[0] : split[0] + " - " + split[1];
            rows.add(new Row(route, label.isEmpty() ? "#" + route.getId() : label,
                    route.getRoutePlatforms().size()));
        }
        // Lines that already carry a change float to the top, then alphabetical.
        rows.sort((a, b) -> {
            boolean aChanged = ClientStopChanges.hasChanges(a.route().getId());
            boolean bChanged = ClientStopChanges.hasChanges(b.route().getId());
            if (aChanged != bChanged) {
                return aChanged ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;
        int bottomBlock = 12 + WIDGET_HEIGHT + GAP * 2;
        int available = height - 44 - bottomBlock;
        visibleRows = Math.max(1, Math.min(Math.max(rows.size(), 1), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows)));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;

        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.stop_changes.edit"),
                            button -> {
                                if (client != null) {
                                    client.setScreen(new RouteStopChangesScreen(row.route(), row.name(), this));
                                }
                            })
                    .dimensions(left + PANEL_WIDTH - EDIT_WIDTH, rowY + 3, EDIT_WIDTH, WIDGET_HEIGHT).build());
        }

        if (rows.size() > visibleRows) {
            ButtonWidget up = ButtonWidget.builder(Text.literal("^"), button -> {
                        scrollOffset = Math.max(0, scrollOffset - 1);
                        clearAndInit();
                    })
                    .dimensions(left + PANEL_WIDTH + GAP, listTop, 14, WIDGET_HEIGHT).build();
            up.active = scrollOffset > 0;
            addDrawableChild(up);
            ButtonWidget down = ButtonWidget.builder(Text.literal("v"), button -> {
                        scrollOffset = Math.min(rows.size() - visibleRows, scrollOffset + 1);
                        clearAndInit();
                    })
                    .dimensions(left + PANEL_WIDTH + GAP, listTop + visibleRows * ROW_HEIGHT - WIDGET_HEIGHT, 14, WIDGET_HEIGHT).build();
            down.active = scrollOffset < rows.size() - visibleRows;
            addDrawableChild(down);
        }

        hintY = listTop + visibleRows * ROW_HEIGHT + 2;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, hintY + 12, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rows.size() > visibleRows && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            int newOffset = Math.max(0, Math.min(rows.size() - visibleRows, scrollOffset - (int) Math.signum(verticalAmount)));
            if (newOffset != scrollOffset) {
                scrollOffset = newOffset;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        // The board goes down BEFORE the widgets, so the per-row buttons stay on top.
        AddonUi.panel(context, listLeft, listTop, listLeft + PANEL_WIDTH,
                listTop + visibleRows * ROW_HEIGHT);
        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            if (mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                context.fill(listLeft + 1, rowY, listLeft + PANEL_WIDTH - 1, rowY + ROW_HEIGHT, AddonUi.ROW_HOVER);
            }
            context.fill(listLeft + 1, rowY + ROW_HEIGHT - 1, listLeft + PANEL_WIDTH - 1,
                    rowY + ROW_HEIGHT, AddonUi.ROW_DIVIDER);
        }
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        int textLimit = PANEL_WIDTH - EDIT_WIDTH - GAP;
        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            boolean changed = ClientStopChanges.hasChanges(row.route().getId());
            String name = (changed ? "* " : "") + row.name();
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(name, textLimit),
                    listLeft, rowY + 2, 0xFF000000 | row.route().getColor());
            String status = generationStatus(row.route());
            String subtitle = Text.translatable("gui.station_announcer.stop_changes.stops", row.stops()).getString()
                    + (changed ? " - " + Text.translatable("gui.station_announcer.stop_changes.has_changes").getString() : "")
                    + (status.isEmpty() ? "" : " - " + status);
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(subtitle, textLimit),
                    listLeft, rowY + 13, status.isEmpty() ? DisruptionsScreen.TEXT_DIM : BAD);
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.stop_changes.hint_marker"),
                listLeft, hintY, MARKER);
    }

    /** Non-empty only when a depot running this line failed its last generation. */
    private static String generationStatus(Route route) {
        try {
            for (Depot depot : route.depots) {
                if (depot != null && depot.getLastGeneratedStatus() == Depot.GeneratedStatus.PATH_NOT_FOUND) {
                    return Text.translatable("gui.station_announcer.stop_changes.path_not_found").getString();
                }
            }
        } catch (Exception ignored) {
            // dashboard data mid-sync — the marker is informational only
        }
        return "";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
