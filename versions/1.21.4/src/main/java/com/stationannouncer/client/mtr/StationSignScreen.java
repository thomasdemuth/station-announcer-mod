package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Name screen for the mosaic band and named columns: one text field. Empty
 * means "follow the MTR station this block is inside".
 */
@Environment(EnvType.CLIENT)
public class StationSignScreen extends Screen {
    private static final int PANEL_WIDTH = 220;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;

    private final StationDecorBlockEntity decor;
    private String customName;

    public StationSignScreen(StationDecorBlockEntity decor) {
        super(Text.translatable("gui.station_announcer.sign.title"));
        this.decor = decor;
        this.customName = decor.getCustomName();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 24;

        TextFieldWidget nameField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.sign.name"));
        nameField.setMaxLength(StationDecorBlockEntity.MAX_NAME_LENGTH);
        nameField.setPlaceholder(Text.translatable("gui.station_announcer.sign.name.hint")
                .formatted(Formatting.DARK_GRAY));
        nameField.setText(customName);
        nameField.setChangedListener(value -> customName = value);
        addDrawableChild(nameField);
        y += WIDGET_HEIGHT + GAP + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(decor.getPos());
        buf.writeString(customName.trim(), StationDecorBlockEntity.MAX_NAME_LENGTH);
        ClientPlayNetworking.send(MtrStationDecor.UPDATE_DECOR_C2S, buf);
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
