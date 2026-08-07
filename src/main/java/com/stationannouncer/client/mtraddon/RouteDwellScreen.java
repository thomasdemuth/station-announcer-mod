package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.mtraddon.AddonNetworking;
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
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketDepotGenerate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feature 2's editor, opened by the "Per-route dwell…" button on MTR's platform
 * screen: override THIS platform's dwell time per route. Unchecked routes use the
 * platform default (MTR's own dwell slider on the underlying screen); checked
 * routes get their own min/sec sliders mirroring MTR's ranges (0–10 min plus
 * 0–59.5 s in half-seconds, ≥ 1 s, ≤ 600 s total).
 *
 * <p>The route list is the same filtering MTR's {@code PlatformScreen} does:
 * every route in {@code MinecraftClientData.getDashboardInstance().routes} with a
 * stop at this platform. Overrides only take effect when the depot regenerates
 * its paths (dwell is baked at generation time), which the hint line says and the
 * optional "Regenerate depots serving this platform" button triggers — it sends
 * MTR's own {@code PacketDepotGenerate}, exactly what the dashboard's refresh
 * button sends.</p>
 */
@Environment(EnvType.CLIENT)
public class RouteDwellScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 34; // name line + widget line
    private static final int GAP = 4;
    private static final int TOGGLE_WIDTH = 70;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;

    /** MTR's dwell slider ranges: minutes 0..10, seconds slider in half-seconds 0..119. */
    private static final int MAX_MINUTES = 10;
    private static final int MAX_HALF_SECONDS = 119;

    /** One route calling at this platform, with its (possibly disabled) override. */
    private static final class Row {
        final Route route;
        final String displayName;
        boolean enabled;
        int minutes;
        int halfSeconds;

        Row(Route route, String displayName) {
            this.route = route;
            this.displayName = displayName;
        }

        long toMillis() {
            return minutes * 60_000L + halfSeconds * 500L;
        }

        void fromMillis(long millis) {
            minutes = (int) Math.min(MAX_MINUTES, millis / 60_000);
            halfSeconds = (int) Math.min(MAX_HALF_SECONDS, (millis % 60_000) / 500);
        }
    }

    private final Platform platform;
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scrollOffset;
    private boolean regenerateSent;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public RouteDwellScreen(Platform platform, Screen parent) {
        super(Text.translatable("gui.station_announcer.route_dwell.title"));
        this.platform = platform;
        this.parent = parent;

        // Same filtering PlatformScreen does: dashboard routes with a stop here.
        Map<Long, Long> existing = ClientDwellOverrides.get(platform.getId());
        for (Route route : MinecraftClientData.getDashboardInstance().routes) {
            for (RoutePlatformData routePlatformData : route.getRoutePlatforms()) {
                Platform stopPlatform = routePlatformData.platform;
                if (stopPlatform != null && stopPlatform.getId() == platform.getId()) {
                    Row row = new Row(route, firstLang(route.getName()));
                    Long overrideMillis = existing == null ? null : existing.get(route.getId());
                    row.enabled = overrideMillis != null;
                    row.fromMillis(overrideMillis != null ? overrideMillis : platform.getDwellTime());
                    rows.add(row);
                    break;
                }
            }
            if (rows.size() >= AddonNetworking.MAX_ROUTE_OVERRIDES) {
                break;
            }
        }
        rows.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        listLeft = left;

        // Fixed chrome: title above, then hint + regenerate + done/cancel below the list.
        int bottomBlock = 12 + (WIDGET_HEIGHT + GAP) * 2 + GAP;
        int available = height - 40 - bottomBlock;
        visibleRows = Math.max(1, Math.min(rows.size(), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, rows.size() - visibleRows));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;

        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT + 10;
            int sliderWidth = (PANEL_WIDTH - TOGGLE_WIDTH - 2 * GAP) / 2;

            addDrawableChild(ButtonWidget.builder(toggleLabel(row), button -> {
                        row.enabled = !row.enabled;
                        clearAndInit();
                    })
                    .dimensions(left, rowY, TOGGLE_WIDTH, WIDGET_HEIGHT).build());

            IntSlider minSlider = new IntSlider(left + TOGGLE_WIDTH + GAP, rowY, sliderWidth, WIDGET_HEIGHT,
                    0, MAX_MINUTES, row.minutes,
                    value -> Text.translatable("gui.station_announcer.route_dwell.min", value),
                    value -> row.minutes = value);
            minSlider.active = row.enabled;
            addDrawableChild(minSlider);

            IntSlider secSlider = new IntSlider(left + TOGGLE_WIDTH + 2 * GAP + sliderWidth, rowY, sliderWidth, WIDGET_HEIGHT,
                    0, MAX_HALF_SECONDS, row.halfSeconds,
                    value -> Text.translatable("gui.station_announcer.route_dwell.sec", value % 2 == 0 ? String.valueOf(value / 2) : String.valueOf(value / 2f)),
                    value -> row.halfSeconds = value);
            secSlider.active = row.enabled;
            addDrawableChild(secSlider);
        }

        // Scroll buttons when the list does not fit.
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

        int y2 = listTop + visibleRows * ROW_HEIGHT;
        hintY = y2 + 2;
        y2 += 12;

        ButtonWidget regenerate = ButtonWidget.builder(
                        Text.translatable(regenerateSent
                                ? "gui.station_announcer.route_dwell.regenerate_sent"
                                : "gui.station_announcer.route_dwell.regenerate"),
                        button -> requestRegeneration())
                .dimensions(left, y2, PANEL_WIDTH, WIDGET_HEIGHT).build();
        regenerate.active = !regenerateSent && !rows.isEmpty();
        addDrawableChild(regenerate);
        y2 += WIDGET_HEIGHT + GAP;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y2, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y2, half, WIDGET_HEIGHT).build());
    }

    private Text toggleLabel(Row row) {
        return Text.translatable(row.enabled
                ? "gui.station_announcer.route_dwell.override"
                : "gui.station_announcer.route_dwell.default");
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

    // ----------------------------------------------------------------- actions

    private void saveAndClose() {
        if (!rows.isEmpty()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(platform.getId());
            List<Row> enabled = new ArrayList<>();
            for (Row row : rows) {
                if (row.enabled) {
                    enabled.add(row);
                }
            }
            buf.writeVarInt(enabled.size());
            for (Row row : enabled) {
                buf.writeLong(row.route.getId());
                long millis = Math.max(AddonNetworking.MIN_DWELL_MILLIS,
                        Math.min(AddonNetworking.MAX_DWELL_MILLIS, row.toMillis()));
                buf.writeVarInt((int) millis);
            }
            ClientPlayNetworking.send(AddonNetworking.UPDATE_DWELL_OVERRIDES_C2S, buf);
        }
        close();
    }

    /**
     * Mirrors what MTR's dashboard sends to regenerate depots: a
     * {@code PacketDepotGenerate} carrying the depot ids. The depots serving this
     * platform are read off the routes' {@code depots} cache (populated by
     * {@code Depot.writeRouteCache} on the dashboard data).
     */
    private void requestRegeneration() {
        try {
            LongOpenHashSet depotIds = new LongOpenHashSet();
            DepotOperationByIds operation = new DepotOperationByIds();
            for (Row row : rows) {
                for (Depot depot : row.route.depots) {
                    if (depot != null && depotIds.add(depot.getId())) {
                        operation.addDepotId(depot.getId());
                    }
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
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.route_dwell.no_routes"),
                    listLeft, listTop + 4, TEXT_DIM);
        } else {
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
                Row row = rows.get(i);
                int nameY = listTop + (i - scrollOffset) * ROW_HEIGHT + 1;
                context.drawTextWithShadow(textRenderer, Text.literal(row.displayName),
                        listLeft, nameY, 0xFF000000 | row.route.getColor());
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.route_dwell.hint"),
                listLeft, hintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    private static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        String first = (split >= 0 ? raw.substring(0, split) : raw).trim();
        return first.isEmpty() ? raw.trim() : first;
    }
}
