package com.stationannouncer.client.gui;

import com.stationannouncer.block.AmbienceBlockEntity;
import com.stationannouncer.net.AnnouncerNetworking;
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
import java.util.List;

/** Settings screen for the ambience block: loop selection, volume, radius. */
@Environment(EnvType.CLIENT)
public class AmbienceScreen extends Screen {
    private static final int PANEL_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;

    private final AmbienceBlockEntity ambience;
    private String sound;
    private int volume;
    private int radius;

    public AmbienceScreen(AmbienceBlockEntity ambience) {
        super(Text.translatable("gui.station_announcer.ambience.title"));
        this.ambience = ambience;
        this.sound = ambience.getSound();
        this.volume = ambience.getVolume();
        this.radius = ambience.getRadius();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 36;

        addDrawableChild(CyclingButtonWidget.<String>builder(value ->
                        Text.translatable("gui.station_announcer.ambience." + value))
                .values(List.of(AmbienceBlockEntity.SOUND_HUM, AmbienceBlockEntity.SOUND_VENT))
                .initially(sound)
                .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.ambience.sound"),
                        (button, value) -> sound = value));
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(new IntSlider(left, y, half, WIDGET_HEIGHT, 0, 100, volume,
                value -> Text.translatable("gui.station_announcer.volume", value),
                value -> volume = value));
        addDrawableChild(new IntSlider(left + half + GAP, y, half, WIDGET_HEIGHT,
                AmbienceBlockEntity.MIN_RADIUS, AmbienceBlockEntity.MAX_RADIUS, radius,
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
        buf.writeBlockPos(ambience.getPos());
        buf.writeString(sound, 16);
        buf.writeVarInt(volume);
        buf.writeVarInt(radius);
        ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_AMBIENCE_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 60, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
