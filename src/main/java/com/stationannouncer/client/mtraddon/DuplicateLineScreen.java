package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
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
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;
import java.util.ArrayList;
import java.util.List;

/**
 * "Duplicate line" — clones one of MTR's routes, stops and all, so the copy can be
 * pointed at a different platform (the manual half of alternating platforms, next to the
 * automatic per-depot rotation of Feature 5).
 *
 * <p><b>How the copy reaches MTR's data.</b> Exactly the way MTR's own dashboard adds a
 * route: build a fresh {@code new Route(transportMode, MinecraftClientData
 * .getDashboardInstance())} — whose id is a fresh random long, assigned in
 * {@code NameColorDataBaseSchema}'s constructor — copy the metadata through the public
 * setters ({@code setName}, {@code setColor}, {@code setRouteNumber}, {@code setRouteType},
 * {@code setHidden}, {@code setCircularState}), append a {@code new
 * RoutePlatformData(platformId)} per stop with its custom destination, and send
 * {@code new PacketUpdateData(new UpdateDataRequest(dashboardInstance).addRoute(copy))}.
 * That is byte-for-byte the packet {@code DashboardScreen.onDoneEditingRoute} and
 * {@code onClickAddPlatformToRoute} send, so the server stores it through MTR's own
 * pipeline — no addon packet, no addon storage, nothing for MTR to be surprised by. All
 * members javap-verified against MTR FABRIC-4.0.1+1.20.4.</p>
 *
 * <p><b>Deliberately NOT auto-assigned to a depot.</b> A route only runs when it is in a
 * depot's route list, and which depot (and in what order) is a routing decision, so the
 * copy is created unassigned and the hint text says so.</p>
 *
 * <p>A stop whose platform no longer resolves on this client is skipped and counted — its
 * platform id is not readable from {@code RoutePlatformData} without the resolved
 * {@link Platform} reference, and copying an unresolvable stop would produce a broken
 * route anyway.</p>
 */
@Environment(EnvType.CLIENT)
public class DuplicateLineScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int GAP = 4;
    private static final int BUTTON_WIDTH = 74;
    private static final int MAX_ROWS_TOTAL = 512;
    private static final int OK = 0xFF66DD88;
    private static final int WARN = 0xFFFFAA33;

    private record Row(Route route, String name, int stops) {
    }

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scrollOffset;

    private String status = "";
    private int statusColor = OK;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public DuplicateLineScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.duplicate_line.title"));
        this.parent = parent;
        try {
            for (Route route : MinecraftClientData.getDashboardInstance().routes) {
                if (rows.size() >= MAX_ROWS_TOTAL) {
                    break;
                }
                rows.add(new Row(route, PlatformGroupScreen.firstLang(route.getName()),
                        route.getRoutePlatforms().size()));
            }
        } catch (Exception ignored) {
            // dashboard data mid-sync — an empty list is handled below
        }
        rows.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        int bottomBlock = 36 + WIDGET_HEIGHT + GAP * 2;
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
                            Text.translatable("gui.station_announcer.duplicate_line.duplicate"),
                            button -> duplicate(row))
                    .dimensions(left + PANEL_WIDTH - BUTTON_WIDTH, rowY + 3, BUTTON_WIDTH, WIDGET_HEIGHT).build());
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
                .dimensions(left, hintY + 36, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    /** Builds the clone and pushes it through MTR's own update pipeline. */
    private void duplicate(Row row) {
        try {
            Route source = row.route();
            Route copy = new Route(source.getTransportMode(), MinecraftClientData.getDashboardInstance());
            copy.setName(copyName(source.getName()));
            copy.setColor(source.getColor());
            copy.setRouteNumber(source.getRouteNumber());
            copy.setRouteType(source.getRouteType());
            copy.setHidden(source.getHidden());
            copy.setCircularState(source.getCircularState());

            int copied = 0;
            int skipped = 0;
            for (RoutePlatformData sourceStop : source.getRoutePlatforms()) {
                Platform platform = sourceStop.getPlatform();
                if (platform == null) {
                    skipped++;
                    continue;
                }
                RoutePlatformData stop = new RoutePlatformData(platform.getId());
                stop.setCustomDestination(sourceStop.getCustomDestination());
                copy.getRoutePlatforms().add(stop);
                // Resolves the new entry's platform reference on this client, the same
                // call MTR makes after adding a platform to a route.
                stop.writePlatformCache(copy, MinecraftClientData.getDashboardInstance().platformIdMap);
                copied++;
            }

            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(
                    new UpdateDataRequest(MinecraftClientData.getDashboardInstance()).addRoute(copy)));

            status = skipped > 0
                    ? Text.translatable("gui.station_announcer.duplicate_line.done_partial",
                    PlatformGroupScreen.firstLang(copy.getName()), copied, skipped).getString()
                    : Text.translatable("gui.station_announcer.duplicate_line.done",
                    PlatformGroupScreen.firstLang(copy.getName()), copied).getString();
            statusColor = skipped > 0 ? WARN : OK;
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not duplicate route", e);
            status = Text.translatable("gui.station_announcer.duplicate_line.failed").getString();
            statusColor = WARN;
        }
    }

    /**
     * Appends the copy suffix to EVERY language of an MTR name ("English|Other"), so the
     * copy is recognisable whichever language a sign renders.
     */
    private static String copyName(String raw) {
        String suffix = Text.translatable("gui.station_announcer.duplicate_line.copy_suffix").getString();
        if (raw == null || raw.isBlank()) {
            return suffix;
        }
        String[] parts = raw.split("\\|", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(parts[i]).append(' ').append(suffix);
        }
        return builder.toString();
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

        int textLimit = PANEL_WIDTH - BUTTON_WIDTH - GAP * 2;
        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.duplicate_line.no_routes"),
                    listLeft, listTop + 6, DisruptionsScreen.TEXT_DIM);
        } else {
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
                Row row = rows.get(i);
                int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(row.name(), textLimit),
                        listLeft, rowY + 2, 0xFF000000 | row.route().getColor());
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(
                                Text.translatable("gui.station_announcer.duplicate_line.stops", row.stops()).getString(),
                                textLimit),
                        listLeft, rowY + 13, DisruptionsScreen.TEXT_DIM);
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.duplicate_line.hint_depot"),
                listLeft, hintY, DisruptionsScreen.TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.duplicate_line.hint_edit"),
                listLeft, hintY + 11, DisruptionsScreen.TEXT_FAINT);
        if (!status.isEmpty()) {
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(status, PANEL_WIDTH),
                    listLeft, hintY + 23, statusColor);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
