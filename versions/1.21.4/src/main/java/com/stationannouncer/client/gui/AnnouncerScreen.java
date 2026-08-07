package com.stationannouncer.client.gui;

import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.net.AnnouncerNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Configuration screen for the standalone Station Announcer block: a single
 * announcement, own volume/radius, delay, tag, chat/chime toggles and an
 * optional custom chime sound. "Done" sends the settings to the server;
 * Escape or "Cancel" discards them.
 */
@Environment(EnvType.CLIENT)
public class AnnouncerScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int ROW = WIDGET_HEIGHT + GAP;

    private final AnnouncerBlockEntity announcer;

    // Edited values live here (not in the widgets) so they survive window resizes.
    private String text;
    private int volume;
    private int delaySeconds;
    private int radius;
    private String tag;
    private boolean showChat;
    private boolean playChime;
    private String chimeSound;

    public AnnouncerScreen(AnnouncerBlockEntity announcer) {
        super(Text.translatable("gui.station_announcer.title"));
        this.announcer = announcer;
        this.text = announcer.getText();
        this.volume = announcer.getVolume();
        this.delaySeconds = announcer.getDelaySeconds();
        this.radius = announcer.getRadius();
        this.tag = announcer.getAnnouncerTag();
        this.showChat = announcer.shouldShowChat();
        this.playChime = announcer.shouldPlayChime();
        this.chimeSound = announcer.getChimeSound();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = 36;

        EditBoxWidget textBox = new EditBoxWidget(textRenderer, left, y, PANEL_WIDTH, 64,
                Text.translatable("gui.station_announcer.text.hint"),
                Text.translatable("gui.station_announcer.text"));
        textBox.setMaxLength(AbstractPaBlockEntity.MAX_TEXT_LENGTH);
        textBox.setText(text);
        textBox.setChangeListener(value -> text = value);
        addDrawableChild(textBox);
        y += 64 + GAP;

        addDrawableChild(new IntSlider(left, y, half, WIDGET_HEIGHT, 0, 100, volume,
                value -> Text.translatable("gui.station_announcer.volume", value),
                value -> volume = value));
        addDrawableChild(new IntSlider(left + half + GAP, y, half, WIDGET_HEIGHT,
                0, AbstractPaBlockEntity.MAX_DELAY_SECONDS, delaySeconds,
                value -> Text.translatable("gui.station_announcer.delay", value),
                value -> delaySeconds = value));
        y += ROW;

        addDrawableChild(new IntSlider(left, y, half, WIDGET_HEIGHT,
                AnnouncerBlockEntity.MIN_RADIUS, AnnouncerBlockEntity.MAX_RADIUS, radius,
                value -> Text.translatable("gui.station_announcer.radius", value),
                value -> radius = value));

        TextFieldWidget tagField = new TextFieldWidget(textRenderer, left + half + GAP, y, half, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.tag"));
        tagField.setMaxLength(AbstractPaBlockEntity.MAX_TAG_LENGTH);
        tagField.setPlaceholder(Text.translatable("gui.station_announcer.tag.hint").formatted(Formatting.DARK_GRAY));
        tagField.setText(tag);
        tagField.setChangedListener(value -> tag = value);
        addDrawableChild(tagField);
        y += ROW;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(showChat)
                .build(left, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.show_chat"),
                        (button, value) -> showChat = value));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(playChime)
                .build(left + half + GAP, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.play_chime"),
                        (button, value) -> playChime = value));
        y += ROW;

        addDrawableChild(ChimeDropdown.build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                chimeSound, value -> chimeSound = value));
        y += ROW + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(announcer.getPos());
        buf.writeString(text, AbstractPaBlockEntity.MAX_TEXT_LENGTH);
        buf.writeVarInt(volume);
        buf.writeVarInt(delaySeconds);
        buf.writeVarInt(radius);
        buf.writeString(tag.trim(), AbstractPaBlockEntity.MAX_TAG_LENGTH);
        buf.writeBoolean(showChat);
        buf.writeBoolean(playChime);
        buf.writeString(chimeSound.trim(), AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
        ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_ANNOUNCER_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.text").formatted(Formatting.GRAY),
                (width - PANEL_WIDTH) / 2, 26, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
