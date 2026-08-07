package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrPids;
import com.stationannouncer.mtr.RailroadPidsBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.TransportMode;

/**
 * Railroad PIDS settings, opened with the MTR brush: which platforms the board
 * watches, which kinds of line may appear as a connection on the station list,
 * and (hanging boards only) how its two screens alternate.
 *
 * <p>The platform list is {@link PlatformPicker}. Selecting nothing leaves the
 * board on auto-detect — the closest platform, exactly like MTR's own PIDS.</p>
 */
@Environment(EnvType.CLIENT)
public class RailroadPidsScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;

    private final RailroadPidsBlockEntity pids;
    private final PlatformPicker picker;
    private int modes;
    private RailroadPidsBlockEntity.DisplayMode displayMode;
    private int flipSeconds;
    /** Hanging boards have two screens, so only they get the flip controls. */
    private final boolean hanging;

    private int listLeft;
    /** Caption positions, recorded by {@link #init} so render() never re-walks the layout. */
    private int titleY;
    private int platformsCaptionY;
    private int hintY;
    private int connectionsCaptionY;

    public RailroadPidsScreen(RailroadPidsBlockEntity pids) {
        super(Text.translatable("gui.station_announcer.railroad_pids.title"));
        this.pids = pids;
        this.modes = pids.getConnectionModes();
        this.displayMode = pids.getDisplayMode();
        this.flipSeconds = pids.getFlipSeconds();
        this.hanging = pids.getCachedState().getBlock()
                instanceof com.stationannouncer.mtr.RailroadPidsHangingBlock;
        java.util.List<Long> configured = new java.util.ArrayList<>();
        for (long id : pids.getPlatformIds()) {
            configured.add(id);
        }
        this.picker = new PlatformPicker(pids.getPos(), configured,
                RailroadPidsBlockEntity.MAX_PLATFORMS, PANEL_WIDTH);
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;

        int modeRows = (Math.min(TransportMode.values().length, RailroadPidsBlockEntity.MODE_COUNT) + 1) / 2;
        int fixed = 14 + 14 + 16 + modeRows * (WIDGET_HEIGHT + GAP)
                + (hanging ? 2 * (WIDGET_HEIGHT + GAP) + 8 : 0)
                + 8 + WIDGET_HEIGHT;
        // The list is the one flexible block, so it absorbs a short window.
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        // Centre on the real content height, but never so high that the title
        // is pushed off the top of a short window.
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        platformsCaptionY = y + 2;
        y += 14;
        listLeft = left;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        hintY = y;
        y += 11;

        connectionsCaptionY = y + 4;
        y += 16;
        // Two toggles per row, in MTR's own transport-mode order so the bit
        // meanings never drift from the enum.
        TransportMode[] transportModes = TransportMode.values();
        for (int i = 0; i < transportModes.length && i < RailroadPidsBlockEntity.MODE_COUNT; i++) {
            int bit = 1 << i;
            boolean right = i % 2 == 1;
            addDrawableChild(CyclingButtonWidget.onOffBuilder((modes & bit) != 0)
                    .build(left + (right ? half + GAP : 0), y, half, WIDGET_HEIGHT,
                            modeLabel(transportModes[i]),
                            (button, value) -> modes = value ? modes | bit : modes & ~bit));
            if (right) {
                y += WIDGET_HEIGHT + GAP;
            }
        }
        if (transportModes.length % 2 == 1) {
            y += WIDGET_HEIGHT + GAP;
        }

        if (hanging) {
            y += 8;
            addDrawableChild(CyclingButtonWidget.<RailroadPidsBlockEntity.DisplayMode>builder(
                            mode -> Text.translatable("gui.station_announcer.railroad_pids.screens."
                                    + mode.name().toLowerCase(java.util.Locale.ROOT)))
                    .values(RailroadPidsBlockEntity.DisplayMode.values())
                    .initially(displayMode)
                    .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                            Text.translatable("gui.station_announcer.railroad_pids.screens"),
                            (button, value) -> displayMode = value));
            y += WIDGET_HEIGHT + GAP;
            addDrawableChild(new com.stationannouncer.client.gui.IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                    RailroadPidsBlockEntity.MIN_FLIP_SECONDS, RailroadPidsBlockEntity.MAX_FLIP_SECONDS,
                    flipSeconds,
                    value -> Text.translatable("gui.station_announcer.railroad_pids.flip_seconds", value),
                    value -> flipSeconds = value));
            y += WIDGET_HEIGHT + GAP;
        }

        y += 8;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private static Text modeLabel(TransportMode mode) {
        return Text.translatable("gui.station_announcer.railroad_pids.mode."
                + mode.name().toLowerCase(java.util.Locale.ROOT));
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

    // ------------------------------------------------------------- lifecycle

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pids.getPos());
        buf.writeInt(modes);
        buf.writeVarInt(displayMode.ordinal());
        buf.writeVarInt(flipSeconds);
        java.util.Set<Long> selected = picker.getSelected();
        int count = Math.min(selected.size(), RailroadPidsBlockEntity.MAX_PLATFORMS);
        buf.writeVarInt(count);
        int written = 0;
        for (long id : selected) {
            if (written++ >= count) {
                break;
            }
            buf.writeLong(id);
        }
        ClientPlayNetworking.send(MtrPids.UPDATE_RAILROAD_PIDS_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.railroad_pids.platforms"),
                listLeft, platformsCaptionY, TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);
        context.drawTextWithShadow(textRenderer, picker.hint(), listLeft, hintY, 0xFF6E6E78);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.railroad_pids.connections"),
                listLeft, connectionsCaptionY, TEXT_DIM);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
