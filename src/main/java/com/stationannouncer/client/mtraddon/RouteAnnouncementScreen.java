package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.tts.TtsManager;
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
 * The per-route announcement template editor, opened from the "Announcement…"
 * button on MTR's Edit Route screen.
 *
 * <p>Built for non-typists: every code block is INSERTABLE BY BUTTON (inserted
 * at the cursor by feeding the widget's own {@code charTyped}, so it lands
 * where the caret is, not at the end; section buttons insert the open/close
 * pair and walk the caret back inside with arrow-key presses), three one-click
 * PRESETS fill a whole template, the live preview re-renders on every change,
 * and a ▶ button SPEAKS the preview through the mod's own TTS so the wording
 * can be heard before saving.</p>
 */
@Environment(EnvType.CLIENT)
public class RouteAnnouncementScreen extends Screen {
    private static final int PANEL_WIDTH = 372;
    private static final int GLFW_KEY_LEFT = 263;
    private static final List<String> SAMPLE_INTERCHANGES = List.of("2 Local", "7 Express");

    private static final String PRESET_NYC =
            "This is a {dest}-bound {route} train. The next stop is {next}. "
                    + "[interchange]Transfer is available to {interchanges}. [/interchange]"
                    + "[terminus]This is the last stop on this train. [/terminus]"
                    + "[enroute]Stand clear of the closing doors, please.[/enroute]";
    private static final String PRESET_SIMPLE = "Next stop: {next}.";
    private static final String PRESET_TRANSFER =
            "Next stop {next}[interchange], change for {interchanges}[/interchange]. "
                    + "[terminus]All change, please — this train terminates here.[/terminus]";

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
        String keep = templateBox == null ? null : templateBox.getText();
        templateBox = new EditBoxWidget(textRenderer, left, 28, PANEL_WIDTH, 44,
                Text.translatable("gui.station_announcer.route_announcement.hint"),
                Text.translatable("gui.station_announcer.route_announcement.title"));
        templateBox.setMaxLength(AddonNetworking.MAX_TEMPLATE_LENGTH);
        String existing = keep != null ? keep : ClientAnnouncementTemplates.get(routeId);
        if (existing != null) {
            templateBox.setText(existing);
        }
        addDrawableChild(templateBox);

        // token palette — one button per code block, inserted at the caret
        int y = 86;
        token(left, y, "next", "{next}");
        token(left + 125, y, "station", "{station}");
        token(left + 250, y, "dest", "{dest}");
        y += 22;
        token(left, y, "route", "{route}");
        token(left + 125, y, "number", "{number}");
        token(left + 250, y, "interchanges", "{interchanges}");
        y += 22;
        section(left, y, "if_interchange", "[interchange]", "[/interchange]");
        section(left + 125, y, "if_terminus", "[terminus]", "[/terminus]");
        section(left + 250, y, "if_enroute", "[enroute]", "[/enroute]");

        // presets — a whole template in one click
        y += 26;
        preset(left, y, "preset_nyc", PRESET_NYC);
        preset(left + 125, y, "preset_simple", PRESET_SIMPLE);
        preset(left + 250, y, "preset_transfer", PRESET_TRANSFER);

        // hear the preview
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.route_announcement.play"),
                        button -> {
                            TtsManager.stop();
                            TtsManager.speak(previewText(), 1.0f);
                        })
                .dimensions(left + PANEL_WIDTH - 60, y + 26, 60, 20).build());

        int buttonY = height - 26;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose(templateBox.getText().trim()))
                .dimensions(left, buttonY, 118, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.station_announcer.route_announcement.clear"),
                        button -> saveAndClose(""))
                .dimensions(left + 124, buttonY, 118, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + 248, buttonY, PANEL_WIDTH - 248, 20).build());
    }

    private void token(int x, int y, String key, String insert) {
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.route_announcement." + key),
                        button -> insertAtCaret(insert))
                .dimensions(x, y, 120, 18).build());
    }

    private void section(int x, int y, String key, String open, String close) {
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.route_announcement." + key),
                        button -> {
                            insertAtCaret(open + close);
                            // walk the caret back between the tags so typing continues inside
                            for (int i = 0; i < close.length(); i++) {
                                templateBox.keyPressed(GLFW_KEY_LEFT, 0, 0);
                            }
                        })
                .dimensions(x, y, 120, 18).build());
    }

    private void preset(int x, int y, String key, String template) {
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.route_announcement." + key),
                        button -> templateBox.setText(template))
                .dimensions(x, y, 120, 18).build());
    }

    /**
     * Inserts at the caret by feeding the widget's own {@code charTyped} — the only
     * public path that respects cursor position and selection (yarn 1.20.4's
     * EditBoxWidget exposes no direct insert API).
     */
    private void insertAtCaret(String text) {
        setFocused(templateBox);
        templateBox.setFocused(true);
        for (int i = 0; i < text.length(); i++) {
            templateBox.charTyped(text.charAt(i), 0);
        }
    }

    private String previewText() {
        String template = templateBox == null ? "" : templateBox.getText();
        if (template.isBlank()) {
            return Text.translatable("gui.station_announcer.route_announcement.preview_default").getString();
        }
        return AnnouncementComposer.renderWithValues(template,
                "Union Square", "Canal Street", "Harbor North",
                routeDisplayName, "4", SAMPLE_INTERCHANGES, false);
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
                "gui.station_announcer.route_announcement.heading", routeDisplayName), left, 12, 0xFFFFFF);

        int y = 162;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.route_announcement.help4"), left, y, 0x8FA3C4);
        y += 14;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.route_announcement.preview"), left, y, 0xF5B942);
        y += 11;
        for (var line : textRenderer.wrapLines(Text.literal(previewText()), PANEL_WIDTH - 66)) {
            context.drawTextWithShadow(textRenderer, line, left, y, 0xE7ECF7);
            y += 10;
        }
    }

    @Override
    public void close() {
        TtsManager.stop();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
