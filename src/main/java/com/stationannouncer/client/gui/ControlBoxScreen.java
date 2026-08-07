package com.stationannouncer.client.gui;

import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
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
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuration screen for the PA Control Box: message pool (one per line),
 * delay, tag, auto-trigger interval, chat/chime toggles, playback order and
 * custom chime sound — plus a section showing the linked speakers with an
 * "Unlink all" button. The box has no volume/radius of its own: those live on
 * each linked speaker.
 */
@Environment(EnvType.CLIENT)
public class ControlBoxScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int ROW = WIDGET_HEIGHT + 4;
    private static final int MAX_LISTED_POSITIONS = 3;

    private final ControlBoxBlockEntity box;

    // Edited values live here (not in the widgets) so they survive window resizes.
    private String text;
    private int delaySeconds;
    private String tag;
    private boolean showChat;
    private boolean playChime;
    private String chimeSound;
    private boolean randomOrder;
    private int autoMinSeconds;
    private int autoMaxSeconds;

    private int speakersTextY;
    private ButtonWidget unlinkAllButton;

    public ControlBoxScreen(ControlBoxBlockEntity box) {
        super(Text.translatable("gui.station_announcer.control_box.title"));
        this.box = box;
        this.text = box.getText();
        this.delaySeconds = box.getDelaySeconds();
        this.tag = box.getAnnouncerTag();
        this.showChat = box.shouldShowChat();
        this.playChime = box.shouldPlayChime();
        this.chimeSound = box.getChimeSound();
        this.randomOrder = box.isRandomOrder();
        this.autoMinSeconds = box.getAutoMinSeconds();
        this.autoMaxSeconds = box.getAutoMaxSeconds();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = 30;

        EditBoxWidget textBox = new EditBoxWidget(textRenderer, left, y, PANEL_WIDTH, 52,
                Text.translatable("gui.station_announcer.messages.hint"),
                Text.translatable("gui.station_announcer.messages"));
        textBox.setMaxLength(AbstractPaBlockEntity.MAX_TEXT_LENGTH);
        textBox.setText(text);
        textBox.setChangeListener(value -> text = value);
        addDrawableChild(textBox);
        y += 52 + 4;

        addDrawableChild(new IntSlider(left, y, half, WIDGET_HEIGHT,
                0, AbstractPaBlockEntity.MAX_DELAY_SECONDS, delaySeconds,
                value -> Text.translatable("gui.station_announcer.delay", value),
                value -> delaySeconds = value));

        TextFieldWidget tagField = new TextFieldWidget(textRenderer, left + half + GAP, y, half, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.tag"));
        tagField.setMaxLength(AbstractPaBlockEntity.MAX_TAG_LENGTH);
        tagField.setPlaceholder(Text.translatable("gui.station_announcer.tag.hint").formatted(Formatting.DARK_GRAY));
        tagField.setText(tag);
        tagField.setChangedListener(value -> tag = value);
        addDrawableChild(tagField);
        y += ROW;

        addDrawableChild(new IntSlider(left, y, half, WIDGET_HEIGHT,
                0, ControlBoxBlockEntity.MAX_AUTO_SECONDS, autoMinSeconds,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.auto_off")
                        : Text.translatable("gui.station_announcer.auto_min", value),
                value -> autoMinSeconds = value));
        addDrawableChild(new IntSlider(left + half + GAP, y, half, WIDGET_HEIGHT,
                0, ControlBoxBlockEntity.MAX_AUTO_SECONDS, autoMaxSeconds,
                value -> Text.translatable("gui.station_announcer.auto_max", value),
                value -> autoMaxSeconds = value));
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

        addDrawableChild(CyclingButtonWidget.onOffBuilder(
                        Text.translatable("gui.station_announcer.order.random"),
                        Text.translatable("gui.station_announcer.order.in_order"))
                .initially(randomOrder)
                .build(left, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.order"),
                        (button, value) -> randomOrder = value));

        addDrawableChild(ChimeDropdown.build(left + half + GAP, y, half, WIDGET_HEIGHT,
                chimeSound, value -> chimeSound = value));
        y += ROW;

        speakersTextY = y + 2;
        y += 22;

        int third = (PANEL_WIDTH - 2 * GAP) / 3;
        unlinkAllButton = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.unlink_all"), button -> unlinkAll())
                .dimensions(left, y, third, WIDGET_HEIGHT).build();
        unlinkAllButton.active = box.getSpeakerCount() > 0 || box.getDisplayCount() > 0;
        addDrawableChild(unlinkAllButton);
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left + third + GAP, y, third, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + 2 * (third + GAP), y, third, WIDGET_HEIGHT).build());
    }

    private void unlinkAll() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(box.getPos());
        ClientPlayNetworking.send(AnnouncerNetworking.UNLINK_ALL_C2S, buf);
        unlinkAllButton.active = false; // server will confirm via block entity sync
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(box.getPos());
        buf.writeString(text, AbstractPaBlockEntity.MAX_TEXT_LENGTH);
        buf.writeVarInt(delaySeconds);
        buf.writeString(tag.trim(), AbstractPaBlockEntity.MAX_TAG_LENGTH);
        buf.writeBoolean(showChat);
        buf.writeBoolean(playChime);
        buf.writeString(chimeSound.trim(), AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
        buf.writeBoolean(randomOrder);
        buf.writeVarInt(autoMinSeconds);
        buf.writeVarInt(autoMaxSeconds);
        ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_CONTROL_BOX_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        int left = (width - PANEL_WIDTH) / 2;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.messages").formatted(Formatting.GRAY),
                left, 20, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, cachedSpeakersLine(), left, speakersTextY, 0xA0A0A0);
    }

    private Text speakersLineCache;
    private long speakersLineExpiry;

    /** Rebuilt a few times a second rather than every frame — it copies and formats the whole speaker list. */
    private Text cachedSpeakersLine() {
        long now = System.currentTimeMillis();
        if (speakersLineCache == null || now >= speakersLineExpiry) {
            speakersLineCache = speakersLine();
            speakersLineExpiry = now + 250;
        }
        return speakersLineCache;
    }

    /** e.g. "Speakers linked: 5 — 10, 64, 3 · 12, 64, 3 · 14, 64, 3 (+2 more)". */
    private Text speakersLine() {
        List<BlockPos> speakers = box.getSpeakers();
        if (speakers.isEmpty() && box.getDisplayCount() == 0) {
            return Text.translatable("gui.station_announcer.no_speakers").formatted(Formatting.YELLOW);
        }
        String positions = speakers.stream()
                .limit(MAX_LISTED_POSITIONS)
                .map(BlockPos::toShortString)
                .collect(Collectors.joining(" · "));
        if (speakers.size() > MAX_LISTED_POSITIONS) {
            positions += " (+" + (speakers.size() - MAX_LISTED_POSITIONS) + ")";
        }
        Text line = Text.translatable("gui.station_announcer.speakers", speakers.size(), positions)
                .formatted(Formatting.GRAY);
        if (box.getDisplayCount() > 0) {
            line = Text.empty().append(line).append(
                    Text.translatable("gui.station_announcer.displays", box.getDisplayCount())
                            .formatted(Formatting.GRAY));
        }
        return line;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
