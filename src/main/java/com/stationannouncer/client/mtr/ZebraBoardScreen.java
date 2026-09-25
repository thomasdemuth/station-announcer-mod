package com.stationannouncer.client.mtr;

import com.stationannouncer.block.ZebraBoardBlockEntity;
import com.stationannouncer.mtr.ZebraLabels;
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
 * Zebra board label editor (opened with the MTR brush): the label text and
 * where on the block the plate sits. Empty text removes the label.
 */
@Environment(EnvType.CLIENT)
public class ZebraBoardScreen extends Screen {
    private static final int PANEL_WIDTH = 220;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int TEXT_FAINT = 0xFF6E6E78;

    private final ZebraBoardBlockEntity board;
    private String text;
    private ZebraBoardBlockEntity.Align align;

    private int hintY;

    public ZebraBoardScreen(ZebraBoardBlockEntity board) {
        super(Text.translatable("gui.station_announcer.zebra_label.title"));
        this.board = board;
        this.text = board.getText();
        this.align = board.getAlign();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 34;

        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.zebra_label.text"));
        field.setMaxLength(ZebraBoardBlockEntity.MAX_TEXT_LENGTH);
        field.setPlaceholder(Text.translatable("gui.station_announcer.zebra_label.text.hint")
                .formatted(Formatting.DARK_GRAY));
        field.setText(text);
        field.setChangedListener(value -> text = value);
        addDrawableChild(field);
        setInitialFocus(field);
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(ButtonWidget.builder(alignLabel(), button -> {
                    align = align.next();
                    button.setMessage(alignLabel());
                })
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;
        hintY = y;
        y += 11 + GAP;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private Text alignLabel() {
        return Text.translatable("gui.station_announcer.zebra_label.align",
                Text.translatable("gui.station_announcer.zebra_label.align."
                        + align.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(board.getPos());
        buf.writeString(text.trim(), ZebraBoardBlockEntity.MAX_TEXT_LENGTH);
        buf.writeByte(align.ordinal());
        ClientPlayNetworking.send(ZebraLabels.UPDATE_ZEBRA_LABEL_C2S, buf);
        close();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 58, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.zebra_label.hint"),
                width / 2, hintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
