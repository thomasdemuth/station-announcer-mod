package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.operation.DepotOperationByIds;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketDepotGenerate;
import java.util.ArrayList;
import java.util.List;

/**
 * One line's temporary stop changes: per stop, "skip this stop" (trains run
 * through without stopping) and "add a stop after this one".
 *
 * <p>Both are runtime overlays on the depot's generation input — the saved route
 * is never edited — and, exactly like per-route dwell and platform groups, they
 * reach the trains at the NEXT depot path generation. The hint line says so and
 * the button at the bottom fires MTR's own {@code PacketDepotGenerate} for the
 * depots running this line, the same packet the dashboard's refresh sends.</p>
 */
@Environment(EnvType.CLIENT)
public class RouteStopChangesScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int GAP = 4;
    private static final int BUTTON_WIDTH = 54;
    private static final int MAX_DURATION_MINUTES = 1440;
    private static final int SKIPPED = 0xFFFF5555;
    private static final int ADDED = 0xFF66DD88;

    private record Row(int stopIndex, String label, long platformId) {
    }

    private final Route route;
    private final String routeName;
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private int durationMinutes;
    private int scrollOffset;
    private boolean regenerateSent;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public RouteStopChangesScreen(Route route, String routeName, Screen parent) {
        super(Text.translatable("gui.station_announcer.stop_changes.route_title"));
        this.route = route;
        this.routeName = routeName;
        this.parent = parent;
        ObjectArrayList<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
        for (int i = 0; i < routePlatforms.size(); i++) {
            Platform platform = routePlatforms.get(i).platform;
            rows.add(new Row(i, platformLabel(platform), platform == null ? 0 : platform.getId()));
        }
    }

    /** "Station - Platform", falling back to the raw platform name. */
    static String platformLabel(Platform platform) {
        if (platform == null) {
            return "?";
        }
        String stationName = platform.area == null ? "" : PlatformGroupScreen.firstLang(platform.area.getName());
        String platformName = PlatformGroupScreen.firstLang(platform.getName());
        if (stationName.isEmpty()) {
            return platformName.isEmpty() ? "#" + platform.getId() : platformName;
        }
        return platformName.isEmpty() ? stationName : stationName + " - " + platformName;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;
        int bottomBlock = 24 + (WIDGET_HEIGHT + GAP) * 3;
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
            boolean skipped = ClientStopChanges.isDisabled(route.getId(), row.stopIndex());
            long[] added = ClientStopChanges.addedAfter(route.getId(), row.stopIndex());

            addDrawableChild(ButtonWidget.builder(
                            Text.translatable(skipped
                                    ? "gui.station_announcer.stop_changes.restore"
                                    : "gui.station_announcer.stop_changes.skip"),
                            button -> {
                                sendStopChange(route.getId(), row.stopIndex(), 0, skipped, 0, durationMinutes);
                                clearAndInit();
                            })
                    .dimensions(left + PANEL_WIDTH - BUTTON_WIDTH * 2 - GAP, rowY + 3, BUTTON_WIDTH, WIDGET_HEIGHT).build());

            addDrawableChild(ButtonWidget.builder(
                            Text.translatable(added != null
                                    ? "gui.station_announcer.stop_changes.remove_add"
                                    : "gui.station_announcer.stop_changes.add"),
                            button -> {
                                if (added != null) {
                                    sendStopChange(route.getId(), row.stopIndex(), 1, true, 0, 0);
                                    clearAndInit();
                                } else if (client != null) {
                                    client.setScreen(new AddedStopPickerScreen(route, row.stopIndex(),
                                            row.platformId(), durationMinutes, this));
                                }
                            })
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
        int y2 = hintY + 22;
        addDrawableChild(new IntSlider(left, y2, PANEL_WIDTH, WIDGET_HEIGHT, 0, MAX_DURATION_MINUTES, durationMinutes,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.stop_changes.duration_off")
                        : Text.translatable("gui.station_announcer.stop_changes.duration", value),
                value -> durationMinutes = value));
        y2 += WIDGET_HEIGHT + GAP;

        ButtonWidget regenerate = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.stop_changes.regenerate"),
                        button -> requestRegeneration())
                .dimensions(left, y2, PANEL_WIDTH, WIDGET_HEIGHT).build();
        regenerate.active = !regenerateSent;
        addDrawableChild(regenerate);
        y2 += WIDGET_HEIGHT + GAP;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, y2, PANEL_WIDTH, WIDGET_HEIGHT).build());
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
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal(routeName), width / 2, titleY, 0xFF000000 | route.getColor());

        long now = System.currentTimeMillis();
        int textLimit = PANEL_WIDTH - BUTTON_WIDTH * 2 - GAP * 2;
        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            boolean skipped = ClientStopChanges.isDisabled(route.getId(), row.stopIndex());
            long[] added = ClientStopChanges.addedAfter(route.getId(), row.stopIndex());
            String name = (row.stopIndex() + 1) + ". " + row.label();
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(name, textLimit),
                    listLeft, rowY + 2, skipped ? SKIPPED : 0xFFF0F0F2);

            String subtitle;
            int color = DisruptionsScreen.TEXT_DIM;
            if (skipped) {
                long expiry = ClientStopChanges.disabledExpiry(route.getId(), row.stopIndex());
                subtitle = Text.translatable("gui.station_announcer.stop_changes.skipped").getString()
                        + remaining(expiry, now);
                color = SKIPPED;
            } else if (added != null) {
                subtitle = Text.translatable("gui.station_announcer.stop_changes.adds",
                        platformLabel(findPlatform(added[0]))).getString()
                        + remaining(added.length > 1 ? added[1] : 0, now);
                color = ADDED;
            } else {
                subtitle = "";
            }
            if (added != null && skipped) {
                subtitle = subtitle + " / " + Text.translatable("gui.station_announcer.stop_changes.adds",
                        platformLabel(findPlatform(added[0]))).getString();
            }
            if (!subtitle.isEmpty()) {
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(subtitle, textLimit),
                        listLeft, rowY + 13, color);
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.stop_changes.hint_generate"),
                listLeft, hintY, DisruptionsScreen.TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.stop_changes.hint_track"),
                listLeft, hintY + 11, DisruptionsScreen.TEXT_FAINT);
    }

    private static String remaining(long expiry, long now) {
        if (expiry <= 0) {
            return "";
        }
        long minutes = Math.max(0, (expiry - now) / 60_000L);
        return " (" + Text.translatable("gui.station_announcer.stop_changes.remaining", minutes).getString() + ")";
    }

    static Platform findPlatform(long platformId) {
        return MinecraftClientData.getDashboardInstance().platformIdMap.get(platformId);
    }

    /** Shared by this screen and the added-stop picker. */
    static void sendStopChange(long routeId, int stopIndex, int kind, boolean clear,
                               long platformId, int durationMinutes) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(routeId);
        buf.writeVarInt(stopIndex);
        buf.writeByte(kind);
        buf.writeBoolean(clear);
        buf.writeLong(platformId);
        buf.writeVarInt(Math.max(0, durationMinutes));
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_STOP_CHANGE_C2S, buf);
    }

    /** Same regeneration request the per-route dwell screen sends. */
    private void requestRegeneration() {
        try {
            LongOpenHashSet depotIds = new LongOpenHashSet();
            DepotOperationByIds operation = new DepotOperationByIds();
            for (Depot depot : route.depots) {
                if (depot != null && depotIds.add(depot.getId())) {
                    operation.addDepotId(depot.getId());
                }
            }
            if (depotIds.isEmpty()) {
                return;
            }
            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDepotGenerate(operation));
            regenerateSent = true;
            clearAndInit();
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not request depot regeneration", e);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
