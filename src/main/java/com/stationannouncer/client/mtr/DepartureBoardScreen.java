package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.DepartureBoardBlockEntity;
import com.stationannouncer.mtr.MtrPids;
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

/**
 * Settings for the big concourse departure board: the header text and which
 * platforms it lists.
 *
 * <p>Saving writes to every cell of the merged board, not just the one that was
 * clicked — the server walks the rectangle. Nothing here needs to know how big
 * the board is.</p>
 */
@Environment(EnvType.CLIENT)
public class DepartureBoardScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;

    private final DepartureBoardBlockEntity board;
    private final PlatformPicker picker;
    private String title;
    private int trackRevealSeconds;
    private TextFieldWidget titleField;

    private int listLeft;
    private int titleY;
    private int headerCaptionY;
    private int platformsCaptionY;
    private int hintY;

    public DepartureBoardScreen(DepartureBoardBlockEntity board) {
        super(Text.translatable("gui.station_announcer.departure_board.title"));
        this.board = board;
        this.title = board.getTitle();
        this.trackRevealSeconds = board.getTrackRevealSeconds();
        java.util.List<Long> configured = new java.util.ArrayList<>();
        for (long id : board.getPlatformIds()) {
            configured.add(id);
        }
        this.picker = new PlatformPicker(board.getPos(), configured,
                DepartureBoardBlockEntity.MAX_PLATFORMS, PANEL_WIDTH);
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;

        int fixed = 14 + 2 * (WIDGET_HEIGHT + GAP) + 14 + 14 + 8 + WIDGET_HEIGHT;
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        headerCaptionY = y + 2;
        y += 14;
        titleField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.departure_board.header"));
        titleField.setMaxLength(DepartureBoardBlockEntity.MAX_TITLE_LENGTH);
        titleField.setText(title);
        titleField.setChangedListener(value -> title = value);
        titleField.setPlaceholder(Text.translatable("gui.station_announcer.departure_board.header_hint"));
        addDrawableChild(titleField);
        y += WIDGET_HEIGHT + GAP;

        // In minutes on the slider; stored in seconds. 0 = always show the track.
        addDrawableChild(new com.stationannouncer.client.gui.IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                0, 30, trackRevealSeconds / 60,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.departure_board.track_always")
                        : Text.translatable("gui.station_announcer.departure_board.track_reveal", value),
                value -> trackRevealSeconds = value * 60));
        y += WIDGET_HEIGHT + GAP;

        platformsCaptionY = y + 2;
        y += 14;
        listLeft = left;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        hintY = y;
        y += 11;

        y += 8;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
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

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(board.getPos());
        buf.writeString(title, DepartureBoardBlockEntity.MAX_TITLE_LENGTH);
        buf.writeVarInt(trackRevealSeconds);
        java.util.Set<Long> selected = picker.getSelected();
        int count = Math.min(selected.size(), DepartureBoardBlockEntity.MAX_PLATFORMS);
        buf.writeVarInt(count);
        int written = 0;
        for (long id : selected) {
            if (written++ >= count) {
                break;
            }
            buf.writeLong(id);
        }
        ClientPlayNetworking.send(MtrPids.UPDATE_DEPARTURE_BOARD_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.departure_board.header"),
                listLeft, headerCaptionY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.railroad_pids.platforms"),
                listLeft, platformsCaptionY, 0xFFFFFF);
        picker.render(context, textRenderer, mouseX, mouseY);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.railroad_pids.platforms_hint"),
                listLeft, hintY, TEXT_DIM);
        super.render(context, mouseX, mouseY, delta);
    }
}
