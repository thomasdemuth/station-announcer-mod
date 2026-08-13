package com.stationannouncer.client.mtr;

import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.mtr.SubwayWalls;
import com.stationannouncer.mtr.TileTabletBlock;
import com.stationannouncer.mtr.TileTabletBlockEntity;
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
 * Name-tablet settings (opened with the MTR brush): the text, and which of the
 * block's four tile rows the tablet sits on — the tablet is one tile tall, so
 * where in the block it lands is the difference between eye height and
 * knee height on a wall you have already built.
 */
@Environment(EnvType.CLIENT)
public class TileTabletScreen extends Screen {
    private static final int PANEL_WIDTH = 220;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int TEXT_FAINT = 0xFF6E6E78;

    private final TileTabletBlockEntity tablet;
    private String text;
    private int row;

    private int hintY;

    public TileTabletScreen(TileTabletBlockEntity tablet) {
        super(Text.translatable("gui.station_announcer.tile_tablet.title"));
        this.tablet = tablet;
        this.text = tablet.getText();
        this.row = tablet.getCachedState().get(TileTabletBlock.ROW);
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 34;

        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.tile_tablet.text"));
        field.setMaxLength(TileTabletBlockEntity.MAX_TEXT_LENGTH);
        field.setPlaceholder(Text.translatable("gui.station_announcer.tile_tablet.text.hint")
                .formatted(Formatting.DARK_GRAY));
        field.setText(text);
        field.setChangedListener(value -> text = value);
        addDrawableChild(field);
        y += WIDGET_HEIGHT + GAP;

        // Rows are counted from the top in the blockstate; the slider counts
        // from 1 because "row 1" reads better than "row 0" on a wall.
        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT, 1, TileTabletBlock.ROWS, row + 1,
                value -> Text.translatable("gui.station_announcer.tile_tablet.row", value),
                value -> row = value - 1));
        y += WIDGET_HEIGHT + GAP;
        hintY = y;
        y += 11 + GAP;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(tablet.getPos());
        buf.writeString(text.trim(), TileTabletBlockEntity.MAX_TEXT_LENGTH);
        buf.writeVarInt(row);
        ClientPlayNetworking.send(SubwayWalls.UPDATE_TILE_TABLET_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 58, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.tile_tablet.hint"),
                width / 2, hintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
