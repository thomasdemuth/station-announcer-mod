package com.stationannouncer.client.gui;

import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.net.AnnouncerNetworking;
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
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Per-speaker settings: volume and sound radius, plus a status line showing
 * which PA Control Box the speaker is linked to (and whether that link is
 * healthy). Linking itself is done with the Speaker Link item.
 */
@Environment(EnvType.CLIENT)
public class SpeakerScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;

    private final SpeakerBlockEntity speaker;

    private int volume;
    private int radius;

    public SpeakerScreen(SpeakerBlockEntity speaker) {
        super(Text.translatable("gui.station_announcer.speaker.title"));
        this.speaker = speaker;
        this.volume = speaker.getVolume();
        this.radius = speaker.getRadius();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = 60;

        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT, 0, 100, volume,
                value -> Text.translatable("gui.station_announcer.volume", value),
                value -> volume = value));
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                SpeakerBlockEntity.MIN_RADIUS, SpeakerBlockEntity.MAX_RADIUS, radius,
                value -> Text.translatable("gui.station_announcer.radius", value),
                value -> radius = value));
        y += WIDGET_HEIGHT + GAP + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(speaker.getPos());
        buf.writeVarInt(volume);
        buf.writeVarInt(radius);
        ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_SPEAKER_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, linkStatus(), width / 2, 40, 0xFFFFFF);
    }

    private Text linkStatus() {
        BlockPos boxPos = speaker.getControlBoxPos();
        String posText = boxPos == null ? "" : boxPos.toShortString();
        return switch (speaker.getClientLinkState()) {
            case SpeakerBlockEntity.LINK_OK ->
                    Text.translatable("gui.station_announcer.speaker.linked", posText).formatted(Formatting.GREEN);
            case SpeakerBlockEntity.LINK_BROKEN ->
                    Text.translatable("gui.station_announcer.speaker.link_broken", posText).formatted(Formatting.RED);
            case SpeakerBlockEntity.LINK_NOT_LOADED ->
                    Text.translatable("gui.station_announcer.speaker.link_not_loaded", posText).formatted(Formatting.YELLOW);
            default ->
                    Text.translatable("gui.station_announcer.speaker.not_linked").formatted(Formatting.GRAY);
        };
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
