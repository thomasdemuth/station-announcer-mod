package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.AddonNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.List;

/**
 * The per-route announcement template editor, opened from a button injected into
 * MTR's Edit Route screen (right beside the stock "Disable Next Station
 * Announcements" checkbox this feature generalises). One multi-line field of
 * text + {@code {code}} blocks, a token reference, and a LIVE PREVIEW rendered
 * with sample values so the wording can be judged before saving. Save sends the
 * template to the server (op-gated there, synced to every client); an empty
 * field restores MTR's stock announcement for the route.
 */
@Environment(EnvType.CLIENT)
public class RouteAnnouncementScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final List<String> SAMPLE_INTERCHANGES = List.of("2 Local", "7 Express");

    private final long routeId;
    private final String routeDisplayName;
    private final Screen parent;
    private EditBoxWidget templateBox;

    public RouteAnnouncementScreen(long routeId, String routeDisplayName, Screen parent) {
        super(Text.translatable("gui.station_announcer.route_announcement.title"));
        this.routeId = routeId;
        this.routeDisplayName = routeDisplayName;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        templateBox = new EditBoxWidget(textRenderer, left, 42, PANEL_WIDTH, 56,
                Text.translatable("gui.station_announcer.route_announcement.hint"),
                Text.translatable("gui.station_announcer.route_announcement.title"));
        templateBox.setMaxLength(AddonNetworking.MAX_TEMPLATE_LENGTH);
        String existing = ClientAnnouncementTemplates.get(routeId);
        if (existing != null) {
            templateBox.setText(existing);
        }
        addDrawableChild(templateBox);

        int buttonY = height - 28;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose(templateBox.getText().trim()))
                .dimensions(left, buttonY, 116, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.station_announcer.route_announcement.clear"),
                        button -> saveAndClose(""))
                .dimensions(left + 122, buttonY, 116, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + 244, buttonY, PANEL_WIDTH - 244, 20).build());
    }

    private void saveAndClose(String template) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(routeId);
        buf.writeString(template, AddonNetworking.MAX_TEMPLATE_LENGTH);
        ClientPlayNetworking.send(AddonNetworking.UPDATE_ANNOUNCEMENT_TEMPLATE_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int left = (width - PANEL_WIDTH) / 2;
        context.drawTextWithShadow(textRenderer, Text.translatable(
                "gui.station_announcer.route_announcement.heading", routeDisplayName), left, 16, 0xFFFFFF);

        int y = 106;
        for (int i = 1; i <= 4; i++) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.route_announcement.help" + i), left, y, 0x8FA3C4);
            y += 11;
        }

        // live preview against sample values ("enroute" case, with interchanges)
        String template = templateBox == null ? "" : templateBox.getText();
        String preview = template.isBlank()
                ? Text.translatable("gui.station_announcer.route_announcement.preview_default").getString()
                : AnnouncementComposer.renderWithValues(template,
                        "Union Square", "Canal Street", "Harbor North",
                        routeDisplayName, "4", SAMPLE_INTERCHANGES, false);
        y += 6;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.route_announcement.preview"), left, y, 0xF5B942);
        y += 12;
        for (var line : textRenderer.wrapLines(Text.literal(preview), PANEL_WIDTH)) {
            context.drawTextWithShadow(textRenderer, line, left, y, 0xE7ECF7);
            y += 10;
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
