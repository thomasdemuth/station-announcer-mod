package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates or edits one service disruption. Rewritten (2026-08-08) into labelled
 * sections drawn in the addon's own GUI language rather than a column of vanilla
 * widgets:
 *
 * <ol>
 *   <li><b>Affected lines</b> — the collapsible {@link LinePicker} inline (no
 *       separate screen to bounce through), with an expand/collapse-all control on
 *       the caption row and a live "n routes on m lines" hint underneath;</li>
 *   <li><b>Message</b> — a full-width field with the three templates as obvious
 *       buttons directly beneath it;</li>
 *   <li><b>Severity</b> — four clickable badges in their real board colours, so the
 *       choice is visible rather than hidden behind a cycling button, with the
 *       Active toggle beside them;</li>
 *   <li><b>Schedule</b> — start delay and duration sliders side by side.</li>
 * </ol>
 *
 * <p>Presentation only: the C2S packet, its field order and every server-side rule
 * are unchanged. The picker shrinks ({@code fitTo}) so the footer buttons stay on
 * screen at large GUI scales.</p>
 */
@Environment(EnvType.CLIENT)
public class DisruptionEditScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SMALL_HEIGHT = 18;
    private static final int GAP = 4;
    private static final int MAX_START_MINUTES = 240;
    private static final int MAX_DURATION_MINUTES = 1440;
    private static final int EXPANDER_WIDTH = 58;

    private final Screen parent;
    private final long id;
    private final Set<Long> routeIds = new LinkedHashSet<>();
    private final LinePicker picker;

    private TextFieldWidget messageField;
    private String message;
    private int severity;
    private boolean active;
    private int startInMinutes;
    private int durationMinutes;

    private int panelLeft;
    private int titleY;
    private int linesCaptionY;
    private int expanderX;
    private int expanderY;
    private int hintY;
    private int messageCaptionY;
    private int severityRowY;
    private final int[] severityX = new int[4];
    private final int[] severityWidth = new int[4];

    public DisruptionEditScreen(ClientDisruptions.Entry existing, Screen parent) {
        super(Text.translatable(existing == null
                ? "gui.station_announcer.disruptions.new_title"
                : "gui.station_announcer.disruptions.edit_title"));
        this.parent = parent;
        long now = System.currentTimeMillis();
        if (existing == null) {
            this.id = 0;
            this.message = "";
            this.severity = 1; // MINOR — the everyday case
            this.active = true;
            this.startInMinutes = 0;
            this.durationMinutes = 0;
        } else {
            this.id = existing.id();
            this.message = existing.message();
            this.severity = existing.severity();
            this.active = existing.active();
            this.startInMinutes = existing.startMillis() > now
                    ? (int) Math.min(MAX_START_MINUTES, (existing.startMillis() - now) / 60_000L) : 0;
            this.durationMinutes = existing.endMillis() > now
                    ? (int) Math.max(1, Math.min(MAX_DURATION_MINUTES, (existing.endMillis() - now) / 60_000L)) : 0;
            routeIds.addAll(existing.routeIds());
        }
        this.picker = new LinePicker(routeIds, PANEL_WIDTH);
    }

    @Override
    protected void init() {
        // The field is rebuilt on resize, so keep what was typed (NycPidsScreen's rule).
        if (messageField != null) {
            message = messageField.getText();
        }
        messageField = null;

        int left = (width - PANEL_WIDTH) / 2;
        panelLeft = left;
        int half = (PANEL_WIDTH - GAP) / 2;
        int third = (PANEL_WIDTH - GAP * 2) / 3;

        // Everything except the picker is fixed; the picker absorbs a short window.
        int fixed = 11                                  // "Affected lines" caption
                + 11                                    // picker hint
                + 11                                    // "Message" caption
                + WIDGET_HEIGHT + 3                     // message field
                + SMALL_HEIGHT + 6                      // template row
                + SMALL_HEIGHT + 6                      // severity + active row
                + WIDGET_HEIGHT + 6                     // schedule sliders
                + WIDGET_HEIGHT;                        // footer
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        linesCaptionY = y + 1;
        expanderX = left + PANEL_WIDTH - EXPANDER_WIDTH;
        expanderY = y - 2;
        y += 11;
        picker.setPosition(left, y);
        y += picker.getHeight() + 1;
        hintY = y;
        y += 11;

        messageCaptionY = y + 1;
        y += 11;
        messageField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.disruptions.message"));
        messageField.setMaxLength(DisruptionNetworking.MAX_MESSAGE_LENGTH);
        messageField.setText(message);
        messageField.setChangedListener(value -> message = value);
        addDrawableChild(messageField);
        y += WIDGET_HEIGHT + 3;

        addDrawableChild(template(left, y, third, "gui.station_announcer.disruptions.template.delays"));
        addDrawableChild(template(left + third + GAP, y, third, "gui.station_announcer.disruptions.template.suspended"));
        addDrawableChild(template(left + (third + GAP) * 2, y, PANEL_WIDTH - (third + GAP) * 2,
                "gui.station_announcer.disruptions.template.planned"));
        y += SMALL_HEIGHT + 6;

        // Severity badges are hand-drawn (see render); lay their hit boxes out here.
        severityRowY = y;
        int badgeX = left;
        for (int i = 0; i < 4; i++) {
            String label = Text.translatable(DisruptionsScreen.severityKey(i)).getString();
            severityWidth[i] = textRenderer.getWidth(label) + 14;
            severityX[i] = badgeX;
            badgeX += severityWidth[i] + 4;
        }
        addDrawableChild(ButtonWidget.builder(activeLabel(), button -> {
                    active = !active;
                    button.setMessage(activeLabel());
                })
                .dimensions(left + PANEL_WIDTH - 88, y, 88, SMALL_HEIGHT).build());
        y += SMALL_HEIGHT + 6;

        addDrawableChild(new IntSlider(left, y, half, WIDGET_HEIGHT, 0, MAX_START_MINUTES, startInMinutes,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.disruptions.start_now")
                        : Text.translatable("gui.station_announcer.disruptions.start_in", value),
                value -> startInMinutes = value));
        addDrawableChild(new IntSlider(left + half + GAP, y, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT,
                0, MAX_DURATION_MINUTES, durationMinutes,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.disruptions.duration_off")
                        : Text.translatable("gui.station_announcer.disruptions.duration", value),
                value -> durationMinutes = value));
        y += WIDGET_HEIGHT + 6;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> {
                    save();
                    close();
                })
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
    }

    private ButtonWidget template(int x, int y, int buttonWidth, String key) {
        return ButtonWidget.builder(Text.translatable(key + ".label"), button -> {
                    message = Text.translatable(key).getString();
                    if (messageField != null) {
                        messageField.setText(message);
                    }
                })
                .dimensions(x, y, buttonWidth, SMALL_HEIGHT).build();
    }

    private Text activeLabel() {
        return Text.translatable(active
                ? "gui.station_announcer.disruptions.toggle_on"
                : "gui.station_announcer.disruptions.toggle_off");
    }

    // ------------------------------------------------------------ behaviour

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (picker.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        // Expand / collapse all.
        if (mouseX >= expanderX && mouseX < expanderX + EXPANDER_WIDTH
                && mouseY >= expanderY && mouseY < expanderY + 14) {
            picker.setAllExpanded(!picker.anyExpanded());
            return true;
        }
        // Severity badges.
        if (mouseY >= severityRowY && mouseY < severityRowY + SMALL_HEIGHT) {
            for (int i = 0; i < 4; i++) {
                if (mouseX >= severityX[i] && mouseX < severityX[i] + severityWidth[i]) {
                    severity = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return picker.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void save() {
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Long> routes = picker.selectedInOrder();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(false); // upsert
        buf.writeLong(id);
        buf.writeVarInt(severity);
        buf.writeBoolean(active);
        buf.writeLong(startInMinutes <= 0 ? 0 : now + startInMinutes * 60_000L);
        buf.writeLong(durationMinutes <= 0 ? 0 : now + (startInMinutes + durationMinutes) * 60_000L);
        buf.writeString(text, DisruptionNetworking.MAX_MESSAGE_LENGTH);
        int count = Math.min(routes.size(), DisruptionNetworking.MAX_ROUTES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(routes.get(i));
        }
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_DISRUPTION_C2S, buf);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // -------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        AddonUi.caption(context, textRenderer,
                Text.translatable("gui.station_announcer.disruptions.section_lines"), panelLeft, linesCaptionY);
        AddonUi.inlineButton(context, textRenderer,
                Text.translatable(picker.anyExpanded()
                        ? "gui.station_announcer.disruptions.collapse_all"
                        : "gui.station_announcer.disruptions.expand_all").getString(),
                expanderX, expanderY, EXPANDER_WIDTH, 14, mouseX, mouseY, AddonUi.TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);
        context.drawTextWithShadow(textRenderer, picker.hint(), panelLeft, hintY, AddonUi.TEXT_FAINT);

        AddonUi.caption(context, textRenderer,
                Text.translatable("gui.station_announcer.disruptions.section_message"), panelLeft, messageCaptionY);

        for (int i = 0; i < 4; i++) {
            boolean chosen = severity == i;
            int color = chosen ? DisruptionsScreen.severityColor(i) : AddonUi.BUTTON_BG;
            boolean hovered = mouseX >= severityX[i] && mouseX < severityX[i] + severityWidth[i]
                    && mouseY >= severityRowY && mouseY < severityRowY + SMALL_HEIGHT;
            context.fill(severityX[i], severityRowY, severityX[i] + severityWidth[i],
                    severityRowY + SMALL_HEIGHT, hovered && !chosen ? AddonUi.BUTTON_BG_HOVER : color);
            if (chosen) {
                context.fill(severityX[i], severityRowY + SMALL_HEIGHT - 2,
                        severityX[i] + severityWidth[i], severityRowY + SMALL_HEIGHT, 0xFFFFFFFF);
            }
            String label = Text.translatable(DisruptionsScreen.severityKey(i)).getString();
            context.drawText(textRenderer, label,
                    severityX[i] + (severityWidth[i] - textRenderer.getWidth(label)) / 2, severityRowY + 5,
                    chosen ? AddonUi.readableOn(color) : AddonUi.TEXT_DIM, false);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
