package com.stationannouncer.client.mtraddon.nav;

import com.stationannouncer.client.mtraddon.AddonClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Settings for the journey directions, in {@code DrivingHudScreen}'s style:
 * the {@link AddonClientConfig} fields are edited IN PLACE so the card previews
 * live behind this (non-pausing) screen, Done persists to disk and Cancel puts
 * back the values captured at open. Per-player client config — no packets, no
 * permissions.
 */
@Environment(EnvType.CLIENT)
public class NavSettingsScreen extends Screen {
    private static final int COLUMN_WIDTH = 150;
    private static final int GAP = 6;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW = WIDGET_HEIGHT + 4;
    private static final int TEXT_FAINT = 0xFF9A9AA5;

    private static final List<String> CORNERS =
            List.of("top_left", "top_right", "bottom_left", "bottom_right");

    @Nullable
    private final Screen parent;

    // Captured at open, restored on Cancel.
    private final boolean oldHudEnabled;
    private final String oldCorner;
    private final boolean oldWaypoint;
    private final boolean oldChat;
    private final boolean oldAlerts;
    private final boolean oldSound;

    private int titleY;
    private int hintY;

    public NavSettingsScreen(@Nullable Screen parent) {
        super(Text.translatable("gui.station_announcer.nav.title"));
        this.parent = parent;
        AddonClientConfig config = AddonClientConfig.get();
        oldHudEnabled = config.navHudEnabled;
        oldCorner = config.navHudCorner;
        oldWaypoint = config.navWaypointEnabled;
        oldChat = config.navChatEnabled;
        oldAlerts = config.navAlertsEnabled;
        oldSound = config.navSoundEnabled;
    }

    @Override
    protected void init() {
        AddonClientConfig config = AddonClientConfig.get();
        int panelWidth = 2 * COLUMN_WIDTH + GAP;
        int leftX = (width - panelWidth) / 2;
        int rightX = leftX + COLUMN_WIDTH + GAP;
        int contentHeight = 3 * ROW + 8 + 14 + ROW;
        int top = Math.max(28, (height - contentHeight) / 2);
        titleY = top - 16;

        // Left column: what is shown.
        int y = top;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.navHudEnabled)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.nav.hud_enabled"),
                        (button, value) -> config.navHudEnabled = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.navWaypointEnabled)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.nav.waypoint"),
                        (button, value) -> config.navWaypointEnabled = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.navChatEnabled)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.nav.chat"),
                        (button, value) -> config.navChatEnabled = value));

        // Right column: where, and how loud.
        y = top;
        String initialCorner = CORNERS.contains(config.navHudCorner) ? config.navHudCorner : "top_right";
        addDrawableChild(CyclingButtonWidget.<String>builder(
                        value -> Text.translatable("gui.station_announcer.nav.corner_" + value))
                .values(CORNERS)
                .initially(initialCorner)
                .build(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.nav.corner"),
                        (button, value) -> config.navHudCorner = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.navAlertsEnabled)
                .build(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.nav.alerts"),
                        (button, value) -> config.navAlertsEnabled = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.navSoundEnabled)
                .build(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.nav.sound"),
                        (button, value) -> config.navSoundEnabled = value));

        // Bottom row: Done / Cancel, spanning both columns.
        int bottomY = top + 3 * ROW + 8 + 14;
        hintY = top + 3 * ROW + 4;
        int half = (panelWidth - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(leftX, bottomY, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> revertAndClose())
                .dimensions(leftX + half + GAP, bottomY, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        AddonClientConfig.persist();
        close();
    }

    private void revertAndClose() {
        AddonClientConfig config = AddonClientConfig.get();
        config.navHudEnabled = oldHudEnabled;
        config.navHudCorner = oldCorner;
        config.navWaypointEnabled = oldWaypoint;
        config.navChatEnabled = oldChat;
        config.navAlertsEnabled = oldAlerts;
        config.navSoundEnabled = oldSound;
        close();
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
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.nav.hint"), width / 2, hintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
