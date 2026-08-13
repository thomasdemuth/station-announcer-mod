package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.StopMarkerBlock;
import com.stationannouncer.mtr.StopMarkerBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.ArrayList;
import java.util.List;

/**
 * Plate editor for a stop marker (opened with the MTR brush): one row per
 * plate with its colour and legend, buttons to add or drop a plate, and — on
 * wall units — the flush/bracket choice.
 */
@Environment(EnvType.CLIENT)
public class StopMarkerScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int COLOR_WIDTH = 78;

    private final StopMarkerBlockEntity marker;
    private final List<StopMarkerBlockEntity.Sign> signs = new ArrayList<>();
    private final List<TextFieldWidget> textFields = new ArrayList<>();
    private StopMarkerBlock.Style style;

    public StopMarkerScreen(StopMarkerBlockEntity marker) {
        super(Text.translatable("gui.station_announcer.stop_marker.title"));
        this.marker = marker;
        this.signs.addAll(marker.getSigns());
        this.style = StopMarkerBlock.Style.of(marker.getCachedState());
    }

    private boolean isWall() {
        return marker.getCachedState().get(StopMarkerBlock.MOUNT) == StopMarkerBlock.Mount.WALL;
    }

    @Override
    protected void init() {
        // Keep whatever has been typed so far when rows are added or removed.
        for (int i = 0; i < textFields.size() && i < signs.size(); i++) {
            signs.set(i, new StopMarkerBlockEntity.Sign(signs.get(i).color(), textFields.get(i).getText()));
        }
        textFields.clear();

        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 62;

        for (int i = 0; i < signs.size(); i++) {
            final int index = i;
            addDrawableChild(CyclingButtonWidget.<StopMarkerBlockEntity.SignColor>builder(
                            color -> Text.translatable("gui.station_announcer.stop_marker.color." + color.id))
                    .values(StopMarkerBlockEntity.SignColor.values())
                    .initially(signs.get(i).color())
                    .omitKeyText()
                    .build(left, y, COLOR_WIDTH, WIDGET_HEIGHT, Text.empty(),
                            (button, value) -> signs.set(index,
                                    new StopMarkerBlockEntity.Sign(value, signs.get(index).text()))));

            TextFieldWidget field = new TextFieldWidget(textRenderer,
                    left + COLOR_WIDTH + GAP, y, PANEL_WIDTH - COLOR_WIDTH - GAP, WIDGET_HEIGHT,
                    Text.translatable("gui.station_announcer.stop_marker.legend"));
            field.setMaxLength(StopMarkerBlockEntity.MAX_TEXT_LENGTH);
            field.setText(signs.get(i).text());
            field.setPlaceholder(Text.translatable("gui.station_announcer.stop_marker.legend.hint")
                    .formatted(Formatting.DARK_GRAY));
            textFields.add(field);
            addDrawableChild(field);
            y += WIDGET_HEIGHT + GAP;
        }

        y += GAP;
        ButtonWidget add = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.stop_marker.add"), button -> {
                            captureText();
                            signs.add(new StopMarkerBlockEntity.Sign(
                                    StopMarkerBlockEntity.SignColor.BLACK, ""));
                            clearAndInit();
                        })
                .dimensions(left, y, half, WIDGET_HEIGHT).build();
        add.active = signs.size() < StopMarkerBlockEntity.MAX_SIGNS;
        addDrawableChild(add);

        ButtonWidget remove = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.stop_marker.remove"), button -> {
                            captureText();
                            signs.remove(signs.size() - 1);
                            clearAndInit();
                        })
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build();
        remove.active = signs.size() > 1;
        addDrawableChild(remove);
        y += WIDGET_HEIGHT + GAP;

        if (isWall()) {
            addDrawableChild(CyclingButtonWidget.<StopMarkerBlock.Style>builder(
                            value -> Text.translatable(
                                    "gui.station_announcer.stop_marker." + value.name().toLowerCase()))
                    .values(StopMarkerBlock.Style.values())
                    .initially(style)
                    .tooltip(value -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable(
                            "gui.station_announcer.stop_marker." + value.name().toLowerCase() + ".tip")))
                    .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                            Text.translatable("gui.station_announcer.stop_marker.mounting"),
                            (button, value) -> style = value));
            y += WIDGET_HEIGHT + GAP;
        }

        y += GAP;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    /** Pulls the typed legends back into the sign list. */
    private void captureText() {
        for (int i = 0; i < textFields.size() && i < signs.size(); i++) {
            signs.set(i, new StopMarkerBlockEntity.Sign(signs.get(i).color(), textFields.get(i).getText()));
        }
    }

    private void saveAndClose() {
        captureText();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(marker.getPos());
        buf.writeEnumConstant(style);
        buf.writeVarInt(signs.size());
        for (StopMarkerBlockEntity.Sign sign : signs) {
            buf.writeEnumConstant(sign.color());
            buf.writeString(sign.text(), StopMarkerBlockEntity.MAX_TEXT_LENGTH);
        }
        ClientPlayNetworking.send(MtrStationDecor.UPDATE_STOP_MARKER_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 84, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
