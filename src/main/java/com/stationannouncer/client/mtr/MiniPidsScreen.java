package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrPids;
import com.stationannouncer.mtr.PidsBlockEntity;
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

/**
 * Tiny config screen for the PIDS NYC Hanging Mini (right-click without a
 * brush): one checkbox-style toggle, "Next train". When on, the display shows
 * a centered "Next train" indicator instead of the countdown row — lit while
 * the next train is a minute or less away, dark from five seconds after
 * arrival until the next train (with a ten-second relight cooldown).
 */
@Environment(EnvType.CLIENT)
public class MiniPidsScreen extends Screen {
    private static final int PANEL_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;

    private final PidsBlockEntity pids;
    private boolean nextTrainMode;

    public MiniPidsScreen(PidsBlockEntity pids) {
        super(Text.translatable("gui.station_announcer.mini.title"));
        this.pids = pids;
        this.nextTrainMode = pids.isNextTrainMode();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 24;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(nextTrainMode)
                .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.next_train"),
                        (button, value) -> nextTrainMode = value));
        y += WIDGET_HEIGHT + GAP + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pids.getPos());
        buf.writeBoolean(nextTrainMode);
        ClientPlayNetworking.send(MtrPids.UPDATE_MINI_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 48, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
