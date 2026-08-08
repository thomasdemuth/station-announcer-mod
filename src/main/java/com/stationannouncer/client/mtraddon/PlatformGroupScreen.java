package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 5's overview, opened by the "Platform group…" button on MTR's
 * platform screen: every (route, stop) at THIS platform, each with an Edit
 * button that opens the {@link PlatformGroupEditScreen} member picker.
 *
 * <p>Placement decision (documented per spec): MTR's {@code EditRouteScreen}
 * edits only route metadata (name, colour, type, hidden, circular — verified
 * against the 4.0.1 jar and source); the per-stop platform list lives in the
 * dashboard's sidebar/map flow, so there is no "selected stop" to hang a
 * button on there. The honest, solid entry point is the platform screen —
 * groups are configured per (route, stopIndex) FOR the platform being edited,
 * exactly like Features 1–2's buttons.</p>
 *
 * <p>The route list is the same filtering the other addon screens use: every
 * route in {@code MinecraftClientData.getDashboardInstance().routes} with a
 * stop at this platform — one row PER OCCURRENCE, because a route calling here
 * twice has two independent stop indices (and therefore two group keys).</p>
 */
@Environment(EnvType.CLIENT)
public class PlatformGroupScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int GAP = 4;
    private static final int EDIT_WIDTH = 60;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;
    private static final int MAX_ROWS = 64;

    /** One (route, stop index) occurrence of this platform. */
    static final class Row {
        final Route route;
        final int stopIndex;
        final int stopCount;
        final String displayName;

        Row(Route route, int stopIndex, int stopCount, String displayName) {
            this.route = route;
            this.stopIndex = stopIndex;
            this.stopCount = stopCount;
            this.displayName = displayName;
        }
    }

    private final Platform platform;
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scrollOffset;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public PlatformGroupScreen(Platform platform, Screen parent) {
        super(Text.translatable("gui.station_announcer.platform_groups.title"));
        this.platform = platform;
        this.parent = parent;

        // One row per occurrence of this platform in each dashboard route.
        for (Route route : MinecraftClientData.getDashboardInstance().routes) {
            ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
            for (int i = 0; i < routePlatforms.size() && rows.size() < MAX_ROWS; i++) {
                Platform stopPlatform = routePlatforms.get(i).platform;
                if (stopPlatform != null && stopPlatform.getId() == platform.getId()) {
                    rows.add(new Row(route, i, routePlatforms.size(), firstLang(route.getName())));
                }
            }
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        rows.sort((a, b) -> {
            int byName = a.displayName.compareToIgnoreCase(b.displayName);
            return byName != 0 ? byName : Integer.compare(a.stopIndex, b.stopIndex);
        });
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        // Fixed chrome: title above, then two hint lines + Done below the list.
        int bottomBlock = 24 + WIDGET_HEIGHT + GAP;
        int available = height - 40 - bottomBlock;
        visibleRows = Math.max(1, Math.min(rows.size(), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, rows.size() - visibleRows));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;

        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.platform_groups.edit"),
                            button -> {
                                if (client != null) {
                                    client.setScreen(new PlatformGroupEditScreen(
                                            row.route, row.stopIndex, row.displayName, platform, this));
                                }
                            })
                    .dimensions(left + PANEL_WIDTH - EDIT_WIDTH, rowY + 3, EDIT_WIDTH, WIDGET_HEIGHT).build());
        }

        // Scroll buttons when the list does not fit (same pattern as RouteDwellScreen).
        if (rows.size() > visibleRows) {
            ButtonWidget up = ButtonWidget.builder(Text.literal("▲"), button -> {
                        scrollOffset = Math.max(0, scrollOffset - 1);
                        clearAndInit();
                    })
                    .dimensions(left + PANEL_WIDTH + GAP, listTop, 14, WIDGET_HEIGHT).build();
            up.active = scrollOffset > 0;
            addDrawableChild(up);
            ButtonWidget down = ButtonWidget.builder(Text.literal("▼"), button -> {
                        scrollOffset = Math.min(rows.size() - visibleRows, scrollOffset + 1);
                        clearAndInit();
                    })
                    .dimensions(left + PANEL_WIDTH + GAP, listTop + visibleRows * ROW_HEIGHT - WIDGET_HEIGHT, 14, WIDGET_HEIGHT).build();
            down.active = scrollOffset < rows.size() - visibleRows;
            addDrawableChild(down);
        }

        hintY = listTop + visibleRows * ROW_HEIGHT + 2;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, hintY + 22, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rows.size() > visibleRows && mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH + 20
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
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
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.platform_groups.no_routes"),
                    listLeft, listTop + 4, TEXT_DIM);
        } else {
            int textLimit = PANEL_WIDTH - EDIT_WIDTH - GAP;
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
                Row row = rows.get(i);
                int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(row.displayName, textLimit),
                        listLeft, rowY + 2, 0xFF000000 | row.route.getColor());
                // Live group size, so returning from the edit screen reflects the save.
                List<Long> group = ClientPlatformGroups.get(row.route.getId(), row.stopIndex);
                Text subtitle = group == null || group.isEmpty()
                        ? Text.translatable("gui.station_announcer.platform_groups.stop", row.stopIndex + 1, row.stopCount)
                                .append(" — ").append(Text.translatable("gui.station_announcer.platform_groups.no_group"))
                        : Text.translatable("gui.station_announcer.platform_groups.stop", row.stopIndex + 1, row.stopCount)
                                .append(" — ").append(Text.translatable("gui.station_announcer.platform_groups.group_size", group.size()));
                context.drawTextWithShadow(textRenderer, subtitle, listLeft, rowY + 13, TEXT_DIM);
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.platform_groups.hint_queue"),
                listLeft, hintY, TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.platform_groups.hint_queue2"),
                listLeft, hintY + 11, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        String first = (split >= 0 ? raw.substring(0, split) : raw).trim();
        return first.isEmpty() ? raw.trim() : first;
    }
}
