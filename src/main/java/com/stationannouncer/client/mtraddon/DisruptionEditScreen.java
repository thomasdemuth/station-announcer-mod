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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates or edits one service disruption: the announcement text (free text, with
 * three one-click templates), the severity, the affected lines, the on/off toggle
 * and an optional start delay / duration.
 *
 * <p>Times are entered as offsets from now rather than absolute clock times —
 * "starts in N minutes", "ends after N minutes" — which is what a dispatcher
 * actually wants and avoids a date picker; the server stores the resulting epoch
 * millis. {@code 0} on the duration means "until turned off".</p>
 */
@Environment(EnvType.CLIENT)
public class DisruptionEditScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int MAX_START_MINUTES = 240;
    private static final int MAX_DURATION_MINUTES = 1440;

    private final Screen parent;
    private final long id;
    private final Set<Long> routeIds = new LinkedHashSet<>();

    private TextFieldWidget messageField;
    private String message;
    private int severity;
    private boolean active;
    private int startInMinutes;
    private int durationMinutes;

    private int titleY;
    private int hintY;

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
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int rows = 8;
        int content = rows * (WIDGET_HEIGHT + GAP) + 24;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        messageField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.disruptions.message"));
        messageField.setMaxLength(DisruptionNetworking.MAX_MESSAGE_LENGTH);
        messageField.setText(message);
        messageField.setChangedListener(value -> message = value);
        addDrawableChild(messageField);
        y += WIDGET_HEIGHT + GAP;

        // Templates — they only prefill the text field; the stored value is plain text.
        int third = (PANEL_WIDTH - GAP * 2) / 3;
        addDrawableChild(templateButton(left, y, third, "gui.station_announcer.disruptions.template.delays"));
        addDrawableChild(templateButton(left + third + GAP, y, third, "gui.station_announcer.disruptions.template.suspended"));
        addDrawableChild(templateButton(left + (third + GAP) * 2, y, PANEL_WIDTH - (third + GAP) * 2, "gui.station_announcer.disruptions.template.planned"));
        y += WIDGET_HEIGHT + GAP;

        int half = (PANEL_WIDTH - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(severityLabel(), button -> {
                    severity = (severity + 1) % 4;
                    button.setMessage(severityLabel());
                })
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(activeLabel(), button -> {
                    active = !active;
                    button.setMessage(activeLabel());
                })
                .dimensions(left + half + GAP, y, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(ButtonWidget.builder(linesLabel(), button -> {
                    if (client != null) {
                        client.setScreen(new RoutePickerScreen(routeIds, this));
                    }
                })
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT, 0, MAX_START_MINUTES, startInMinutes,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.disruptions.start_now")
                        : Text.translatable("gui.station_announcer.disruptions.start_in", value),
                value -> startInMinutes = value));
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT, 0, MAX_DURATION_MINUTES, durationMinutes,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.disruptions.duration_off")
                        : Text.translatable("gui.station_announcer.disruptions.duration", value),
                value -> durationMinutes = value));
        y += WIDGET_HEIGHT + GAP;

        hintY = y;
        y += 12;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> {
                    save();
                    close();
                })
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
    }

    private ButtonWidget templateButton(int x, int y, int buttonWidth, String key) {
        return ButtonWidget.builder(Text.translatable(key + ".label"), button -> {
                    message = Text.translatable(key).getString();
                    messageField.setText(message);
                })
                .dimensions(x, y, buttonWidth, WIDGET_HEIGHT).build();
    }

    private Text severityLabel() {
        return Text.translatable("gui.station_announcer.disruptions.severity_label",
                Text.translatable(DisruptionsScreen.severityKey(severity)));
    }

    private Text activeLabel() {
        return Text.translatable(active
                ? "gui.station_announcer.disruptions.toggle_on"
                : "gui.station_announcer.disruptions.toggle_off");
    }

    private Text linesLabel() {
        return Text.translatable("gui.station_announcer.disruptions.lines", routeIds.size());
    }

    private void save() {
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Long> routes = new ArrayList<>(routeIds);
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.disruptions.hint"),
                (width - PANEL_WIDTH) / 2, hintY, DisruptionsScreen.TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
