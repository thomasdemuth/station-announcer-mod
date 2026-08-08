package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.gui.IntSlider;
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
 * Feature 4's settings screen, opened with the Driving HUD keybinding
 * (default H, rebindable in vanilla Controls). Edits
 * {@link AddonClientConfig}'s HUD fields IN PLACE so the panel previews live
 * behind the (non-pausing) screen; Done persists to disk, Cancel restores the
 * values captured at open. Everything is per-player client config — no
 * packets, no permissions.
 */
@Environment(EnvType.CLIENT)
public class DrivingHudScreen extends Screen {
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
    private final boolean oldEnabled;
    private final boolean oldShowSpeedLimits;
    private final boolean oldShowSignals;
    private final boolean oldShowNextStop;
    private final boolean oldShowOnTime;
    private final boolean oldShowDoors;
    private final int oldUpdateHz;
    private final int oldLookaheadMeters;
    private final int oldOnTimeThresholdSeconds;
    private final String oldCorner;

    private int titleY;
    private int hintY;

    public DrivingHudScreen(@Nullable Screen parent) {
        super(Text.translatable("gui.station_announcer.driving_hud.title"));
        this.parent = parent;
        AddonClientConfig config = AddonClientConfig.get();
        oldEnabled = config.hudEnabled;
        oldShowSpeedLimits = config.hudShowSpeedLimits;
        oldShowSignals = config.hudShowSignals;
        oldShowNextStop = config.hudShowNextStop;
        oldShowOnTime = config.hudShowOnTime;
        oldShowDoors = config.hudShowDoors;
        oldUpdateHz = config.hudUpdateHz;
        oldLookaheadMeters = config.hudLookaheadMeters;
        oldOnTimeThresholdSeconds = config.hudOnTimeThresholdSeconds;
        oldCorner = config.hudCorner;
    }

    @Override
    protected void init() {
        AddonClientConfig config = AddonClientConfig.get();
        int panelWidth = 2 * COLUMN_WIDTH + GAP;
        int leftX = (width - panelWidth) / 2;
        int rightX = leftX + COLUMN_WIDTH + GAP;
        int contentHeight = 6 * ROW + 8 + 14 + ROW;
        int top = Math.max(28, (height - contentHeight) / 2);
        titleY = top - 16;

        // Left column: element toggles.
        int y = top;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.hudEnabled)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.enabled"),
                        (button, value) -> config.hudEnabled = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.hudShowNextStop)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.show_next_stop"),
                        (button, value) -> config.hudShowNextStop = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.hudShowOnTime)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.show_on_time"),
                        (button, value) -> config.hudShowOnTime = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.hudShowSpeedLimits)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.show_speed_limits"),
                        (button, value) -> config.hudShowSpeedLimits = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.hudShowSignals)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.show_signals"),
                        (button, value) -> config.hudShowSignals = value));
        y += ROW;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.hudShowDoors)
                .build(leftX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.show_doors"),
                        (button, value) -> config.hudShowDoors = value));

        // Right column: tunables.
        y = top;
        addDrawableChild(new IntSlider(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT, 1, 10, config.hudUpdateHz,
                value -> Text.translatable("gui.station_announcer.driving_hud.update_hz", value),
                value -> config.hudUpdateHz = value));
        y += ROW;
        addDrawableChild(new IntSlider(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT, 250, 5000, config.hudLookaheadMeters,
                value -> Text.translatable("gui.station_announcer.driving_hud.lookahead", value),
                value -> config.hudLookaheadMeters = value));
        y += ROW;
        addDrawableChild(new IntSlider(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT, 5, 60, config.hudOnTimeThresholdSeconds,
                value -> Text.translatable("gui.station_announcer.driving_hud.threshold", value),
                value -> config.hudOnTimeThresholdSeconds = value));
        y += ROW;
        String initialCorner = CORNERS.contains(config.hudCorner) ? config.hudCorner : CORNERS.get(0);
        addDrawableChild(CyclingButtonWidget.<String>builder(
                        value -> Text.translatable("gui.station_announcer.driving_hud.corner_" + value))
                .values(CORNERS)
                .initially(initialCorner)
                .build(rightX, y, COLUMN_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.driving_hud.corner"),
                        (button, value) -> config.hudCorner = value));

        // Bottom row: Done / Cancel, spanning both columns.
        int bottomY = top + 6 * ROW + 8 + 14;
        hintY = top + 6 * ROW + 4;
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
        config.hudEnabled = oldEnabled;
        config.hudShowSpeedLimits = oldShowSpeedLimits;
        config.hudShowSignals = oldShowSignals;
        config.hudShowNextStop = oldShowNextStop;
        config.hudShowOnTime = oldShowOnTime;
        config.hudShowDoors = oldShowDoors;
        config.hudUpdateHz = oldUpdateHz;
        config.hudLookaheadMeters = oldLookaheadMeters;
        config.hudOnTimeThresholdSeconds = oldOnTimeThresholdSeconds;
        config.hudCorner = oldCorner;
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
                Text.translatable("gui.station_announcer.driving_hud.hint"), width / 2, hintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
