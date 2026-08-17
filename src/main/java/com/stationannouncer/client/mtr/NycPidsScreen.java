package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrPids;
import com.stationannouncer.mtr.PidsBlockEntity;
import com.stationannouncer.mtr.PidsStyle;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Settings for the NYC PIDS displays, opened with the MTR brush — our own
 * screen in place of MTR's, so the whole PIDS family shares one platform
 * chooser ({@link PlatformPicker}) instead of MTR's two-level filter dialog.
 *
 * <p>Only the fields these displays actually read are offered:</p>
 * <ul>
 *   <li>the platforms to watch — every style;</li>
 *   <li>the "Happening now" text — one box, departure lists only, since nothing
 *       else renders it. It wraps to at most five lines on the display;</li>
 *   <li>the mini's "Next train" mode.</li>
 * </ul>
 *
 * <p>MTR's own hide-arrival flags and display page are left strictly alone:
 * they are not sent, and the server hands the block entity's existing values
 * straight back to {@code setData} so nothing is quietly reset.</p>
 */
@Environment(EnvType.CLIENT)
public class NycPidsScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;

    /** Same cap MTR's PIDS uses for its own platform filter. */
    private static final int MAX_PLATFORMS = 32;
    private static final int MAX_MESSAGE_LENGTH = 256;

    private final PidsBlockEntity pids;
    private final PidsStyle style;
    private final PlatformPicker picker;
    private TextFieldWidget messageField;
    private String message;
    private boolean nextTrainMode;

    private int listLeft;
    private int titleY;
    private int platformsCaptionY;
    private int hintY;
    private int messagesCaptionY = -1;

    public NycPidsScreen(PidsBlockEntity pids) {
        super(Text.translatable("gui.station_announcer.nyc_pids.title"));
        this.pids = pids;
        this.style = pids.style;
        this.nextTrainMode = pids.isNextTrainMode();

        List<Long> configured = new ArrayList<>();
        pids.getPlatformIds().forEach((long id) -> configured.add(id));
        this.picker = new PlatformPicker(pids.getPos(), configured, MAX_PLATFORMS, PANEL_WIDTH);

        // One box, however many rows MTR's own storage happens to have —
        // getCustomMessage() joins whatever is already there so nothing typed
        // through MTR's screen is lost on first open.
        this.message = pids.getCustomMessage();
    }

    private boolean showsMessages() {
        return style.isDepartures();
    }

    @Override
    protected void init() {
        // The field is rebuilt on resize, so keep what was typed.
        if (messageField != null) {
            message = messageField.getText();
        }
        messageField = null;

        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        boolean withMessage = showsMessages();

        int fixed = 14 + 14
                + (withMessage ? 16 + WIDGET_HEIGHT + GAP : 0)
                + (style == PidsStyle.HANGING_MINI ? WIDGET_HEIGHT + GAP + 8 : 0)
                + 8 + WIDGET_HEIGHT;
        // The list is the one flexible block, so it absorbs a short window —
        // otherwise four message rows push Done and Cancel off the bottom.
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        listLeft = left;
        platformsCaptionY = y + 2;
        y += 14;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        hintY = y;
        y += 11;

        if (withMessage) {
            messagesCaptionY = y + 4;
            y += 16;
            messageField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                    Text.translatable("gui.station_announcer.nyc_pids.messages"));
            messageField.setMaxLength(MAX_MESSAGE_LENGTH);
            messageField.setText(message);
            addDrawableChild(messageField);
            y += WIDGET_HEIGHT + GAP;
        } else {
            messagesCaptionY = -1;
        }

        if (style == PidsStyle.HANGING_MINI) {
            y += 8;
            int toggleWidth = PANEL_WIDTH - 90 - GAP;
            addDrawableChild(CyclingButtonWidget.onOffBuilder(nextTrainMode)
                    .build(left, y, toggleWidth, WIDGET_HEIGHT,
                            Text.translatable("gui.station_announcer.next_train"),
                            (button, value) -> nextTrainMode = value));
            // Timing sliders and per-track arrows live on the mini's own
            // bare-click screen; the brush screen links there rather than
            // duplicating them, but it must be reachable from HERE — the brush
            // screen is where people look. Platforms/messages typed so far are
            // NOT saved by this jump (this screen saves on Done only), which
            // matches what Cancel already promises.
            addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.next_train.more"),
                            button -> {
                                if (client != null) {
                                    MiniPidsScreen more = new MiniPidsScreen(pids);
                                    client.setScreen(more);
                                }
                            })
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.translatable("gui.station_announcer.next_train.more.tip")))
                    .dimensions(left + toggleWidth + GAP, y, 90, WIDGET_HEIGHT).build());
            y += WIDGET_HEIGHT + GAP;
        }

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
        buf.writeBlockPos(pids.getPos());
        buf.writeBoolean(nextTrainMode);

        buf.writeString(messageField == null ? "" : messageField.getText(), MAX_MESSAGE_LENGTH);

        Set<Long> selected = picker.getSelected();
        int count = Math.min(selected.size(), MAX_PLATFORMS);
        buf.writeVarInt(count);
        int written = 0;
        for (long id : selected) {
            if (written++ >= count) {
                break;
            }
            buf.writeLong(id);
        }
        ClientPlayNetworking.send(MtrPids.UPDATE_NYC_PIDS_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.railroad_pids.platforms"),
                listLeft, platformsCaptionY, TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);
        context.drawTextWithShadow(textRenderer, picker.hint(), listLeft, hintY, TEXT_FAINT);
        if (messagesCaptionY >= 0) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.nyc_pids.messages"),
                    listLeft, messagesCaptionY, TEXT_DIM);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
