package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.ClientHoldRules;
import com.stationannouncer.client.mtraddon.ClientHoldState;
import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import com.stationannouncer.mtr.StationDecorBlockEntity.HoldIndicator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import java.util.List;

/**
 * Holding-light settings (opened with the MTR brush).
 *
 * <p>The platform is chosen with the shared {@link PlatformPicker} — the same
 * list the PIDS screens use, so a light and the board above it are configured
 * the same way — capped at one selection; nothing ticked means "the nearest
 * platform", which is what an unconfigured light already does. Both timing
 * edges can be left on Auto, using the light's own defaults (yellow 5 s before
 * arrival / 6 s before departure, green 3 s before departure / 15 s after).</p>
 *
 * <p>Yellow lights additionally choose what to do about the dispatch addon's
 * platform hold rules: ignore them, stay lit while a train is held, or act purely
 * as a hold indicator. Green ones do not — green means "time to leave", which a
 * hold is precisely the absence of.</p>
 *
 * <p>Everything is saved through the shared decor channel.</p>
 */
@Environment(EnvType.CLIENT)
public class HoldingLightScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;
    private static final int TEXT_HELD = 0xFFFFC03C;

    /** Slider position that means "leave it on the light's own default". */
    private static final int AUTO = StationDecorBlockEntity.UNSET_SECONDS;

    /** How far the light hunts for a platform when none is picked (as the renderer does). */
    private static final int AUTO_PLATFORM_RADIUS = 8;

    private final StationDecorBlockEntity decor;
    private final PlatformPicker picker;
    private final boolean green;
    private int onSeconds;
    private int offSeconds;
    private HoldIndicator holdIndicator;

    private int titleY;
    private int listLeft;
    private int platformCaptionY;
    private int platformHintY;
    private int holdHintY;

    public HoldingLightScreen(StationDecorBlockEntity decor) {
        super(Text.translatable("gui.station_announcer.holding_light.title"));
        this.decor = decor;
        this.green = decor.getCachedState().getBlock() instanceof com.stationannouncer.mtr.HoldingLightBlock light
                && light.green;
        this.onSeconds = decor.getLightOnSeconds();
        this.offSeconds = decor.getLightOffSeconds();
        this.holdIndicator = decor.getHoldIndicator();

        // The platform id is stored in the shared custom-name field as a string.
        long stored = storedPlatform(decor);
        this.picker = new PlatformPicker(decor.getPos(),
                stored == 0 ? List.of() : List.of(stored), 1, PANEL_WIDTH);
    }

    private static long storedPlatform(StationDecorBlockEntity decor) {
        String stored = decor.getCustomName().trim();
        if (stored.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(stored);
        } catch (NumberFormatException exception) {
            return 0; // not a platform id (a name left over from another decor block)
        }
    }

    // ------------------------------------------------------------- timing text

    private Text timingCaption(String key, int seconds) {
        int shown = seconds == AUTO ? defaultFor(key) : seconds;
        return seconds == AUTO
                ? Text.translatable("gui.station_announcer.holding_light." + key + ".auto", shown)
                : Text.translatable("gui.station_announcer.holding_light." + key, shown);
    }

    private int defaultFor(String key) {
        if (key.equals("on_delay")) {
            return green ? 3 : 5;
        }
        return green ? 15 : 6;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        boolean yellow = !green;

        int fixed = 14                                              // platform caption
                + 3 + 11                                            // selection hint
                + 2 * (WIDGET_HEIGHT + GAP)                         // both timing sliders
                + (yellow ? WIDGET_HEIGHT + GAP + 11 : 0)           // hold button + its hint
                + 8 + WIDGET_HEIGHT;                                // Done / Cancel
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        listLeft = left;
        platformCaptionY = y + 2;
        y += 14;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        platformHintY = y;
        y += 11;

        // -1 is the far-left "Auto" notch on both sliders.
        addDrawableChild(new com.stationannouncer.client.gui.IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                AUTO, StationDecorBlockEntity.MAX_LIGHT_SECONDS, onSeconds,
                value -> timingCaption("on_delay", value), value -> onSeconds = value));
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(new com.stationannouncer.client.gui.IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                AUTO, StationDecorBlockEntity.MAX_LIGHT_SECONDS, offSeconds,
                value -> timingCaption("off_delay", value), value -> offSeconds = value));
        y += WIDGET_HEIGHT + GAP;

        if (yellow) {
            addDrawableChild(CyclingButtonWidget.<HoldIndicator>builder(HoldingLightScreen::holdLabel)
                    .values(HoldIndicator.values())
                    .initially(holdIndicator)
                    .tooltip(value -> Tooltip.of(Text.translatable(
                            "gui.station_announcer.holding_light.hold." + value.name().toLowerCase() + ".tip")))
                    .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                            Text.translatable("gui.station_announcer.holding_light.hold"),
                            (button, value) -> holdIndicator = value));
            y += WIDGET_HEIGHT + GAP;
            holdHintY = y;
            y += 11;
        }
        y += 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private static Text holdLabel(HoldIndicator value) {
        return Text.translatable("gui.station_announcer.holding_light.hold." + value.name().toLowerCase());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return picker.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return picker.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // --------------------------------------------------------------- lifecycle

    private void saveAndClose() {
        long chosen = picker.getSingleSelected();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(decor.getPos());
        buf.writeString(chosen == 0 ? "" : String.valueOf(chosen), StationDecorBlockEntity.MAX_NAME_LENGTH);
        buf.writeInt(onSeconds);
        buf.writeInt(offSeconds);
        buf.writeByte(holdIndicator.ordinal());
        ClientPlayNetworking.send(MtrStationDecor.UPDATE_DECOR_C2S, buf);
        close();
    }

    // ---------------------------------------------------------------- drawing

    /** The platform this light will actually watch: the ticked one, else the nearest. */
    private long effectivePlatform() {
        long chosen = picker.getSingleSelected();
        return chosen != 0 ? chosen : MtrDataCache.closestPlatform(decor.getPos(), AUTO_PLATFORM_RADIUS);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.holding_light.platform"),
                listLeft, platformCaptionY, TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);

        long chosen = picker.getSingleSelected();
        String label = chosen == 0 ? "" : picker.labelFor(chosen);
        context.drawTextWithShadow(textRenderer, chosen == 0 || label.isEmpty()
                        ? Text.translatable("gui.station_announcer.holding_light.hint_auto")
                        : Text.translatable("gui.station_announcer.holding_light.hint_platform", label),
                listLeft, platformHintY, TEXT_FAINT);

        if (!green) {
            drawHoldHint(context);
        }
    }

    /**
     * What the hold setting will actually do here: whether the watched platform
     * has a rule at all (a light set to follow holds on a platform nobody holds
     * at would simply never light), and whether one is in force right now — which
     * doubles as the in-game check that the wiring works.
     */
    private void drawHoldHint(DrawContext context) {
        if (holdIndicator == HoldIndicator.OFF) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.holding_light.hold.hint_off"),
                    listLeft, holdHintY, TEXT_FAINT);
            return;
        }
        long platformId = effectivePlatform();
        if (platformId != 0 && ClientHoldState.isHeld(platformId)) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.holding_light.hold.hint_held"),
                    listLeft, holdHintY, TEXT_HELD);
            return;
        }
        ClientHoldRules.Rule rule = platformId == 0 ? null : ClientHoldRules.get(platformId);
        context.drawTextWithShadow(textRenderer, rule == null
                        ? Text.translatable("gui.station_announcer.holding_light.hold.hint_no_rule")
                        : Text.translatable("gui.station_announcer.holding_light.hold.hint_rule",
                                rule.watched().size()),
                listLeft, holdHintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
