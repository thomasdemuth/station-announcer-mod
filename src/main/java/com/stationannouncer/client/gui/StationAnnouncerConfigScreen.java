package com.stationannouncer.client.gui;

import com.stationannouncer.client.ClientConfig;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.DrivingHudScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's settings screen: everything a player can change on their own
 * client, in one place. Reached from Mod Menu's mod list, from the key bind
 * this mod registers, or from the driving HUD's own screen.
 *
 * <p>Two config files sit behind it — {@link ClientConfig} for the PA system
 * and {@link AddonClientConfig} for the dispatch addon — and nothing is
 * written until Done, so Cancel really does back out.</p>
 *
 * <p>Settings with a screen of their own are LINKED rather than copied: the
 * driving HUD has a dozen options and its own editor already, and duplicating
 * those controls here would leave two places to keep in step.</p>
 */
@Environment(EnvType.CLIENT)
public class StationAnnouncerConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 310;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int ROW = WIDGET_HEIGHT + GAP;
    private static final int HEADING = 14;
    private static final int TEXT_DIM = 0xFF9A9AA5;

    @Nullable
    private final Screen parent;

    // Working copies: nothing reaches the config objects until Done.
    private boolean tts;
    private boolean chime;
    private boolean actionBar;
    private String backend;
    private String voice;
    private String chimeCategory;
    private boolean holdRules;
    private boolean routeDwell;
    private boolean liftDoors;
    private boolean platformGroups;
    private boolean disruptions;
    private boolean tools;

    private int announcementsY;
    private int dispatchY;

    public StationAnnouncerConfigScreen(@Nullable Screen parent) {
        super(Text.translatable("gui.station_announcer.config.title"));
        this.parent = parent;
        ClientConfig client = ClientConfig.get();
        this.tts = client.enableTts;
        this.chime = client.enableChime;
        this.actionBar = client.useActionBar();
        this.backend = client.backend();
        this.voice = client.voice == null ? "" : client.voice;
        this.chimeCategory = client.chimeCategory == null ? "master" : client.chimeCategory;
        AddonClientConfig addon = AddonClientConfig.get();
        this.holdRules = addon.showHoldRulesButton;
        this.routeDwell = addon.showRouteDwellButton;
        this.liftDoors = addon.showLiftDoorSidesButton;
        this.platformGroups = addon.showPlatformGroupButton;
        this.disruptions = addon.showDisruptionsButton;
        this.tools = addon.showToolsButton;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int content = HEADING + 4 * ROW + HEADING + 3 * ROW + ROW + 8 + WIDGET_HEIGHT;
        int y = Math.max(30, (height - content) / 2);

        announcementsY = y;
        y += HEADING;
        addDrawableChild(onOff("speech", tts, left, y, half, value -> tts = value));
        addDrawableChild(onOff("chime", chime, left + half + GAP, y, half, value -> chime = value));
        y += ROW;

        addDrawableChild(CyclingButtonWidget.<Boolean>builder(
                        value -> Text.translatable("gui.station_announcer.config.display."
                                + (value ? "actionbar" : "chat")))
                .values(false, true)
                .initially(actionBar)
                .build(left, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.config.display"),
                        (button, value) -> actionBar = value));
        addDrawableChild(CyclingButtonWidget.<String>builder(
                        value -> Text.translatable("gui.station_announcer.config.engine." + value))
                .values("auto", "narrator", "system")
                .initially(backend)
                .tooltip(value -> Tooltip.of(Text.translatable("gui.station_announcer.config.engine." + value + ".tip")))
                .build(left + half + GAP, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.config.engine"),
                        (button, value) -> backend = value));
        y += ROW;

        addDrawableChild(CyclingButtonWidget.<String>builder(
                        value -> Text.translatable("gui.station_announcer.config.chime_category." + value))
                .values("master", "blocks", "ambient", "voice")
                .initially(chimeCategory)
                .tooltip(value -> Tooltip.of(Text.translatable(
                        "gui.station_announcer.config.chime_category." + value + ".tip")))
                .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.config.chime_category"),
                        (button, value) -> chimeCategory = value));
        y += ROW;

        TextFieldWidget voiceField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.config.voice"));
        voiceField.setMaxLength(64);
        voiceField.setPlaceholder(Text.translatable("gui.station_announcer.config.voice.hint")
                .formatted(Formatting.DARK_GRAY));
        voiceField.setText(voice);
        voiceField.setChangedListener(value -> voice = value);
        voiceField.setTooltip(Tooltip.of(Text.translatable("gui.station_announcer.config.voice.tip")));
        addDrawableChild(voiceField);
        y += ROW;

        dispatchY = y;
        y += HEADING;
        addDrawableChild(onOff("hold_rules", holdRules, left, y, half, value -> holdRules = value));
        addDrawableChild(onOff("route_dwell", routeDwell, left + half + GAP, y, half, value -> routeDwell = value));
        y += ROW;
        addDrawableChild(onOff("lift_doors", liftDoors, left, y, half, value -> liftDoors = value));
        addDrawableChild(onOff("platform_groups", platformGroups, left + half + GAP, y, half,
                value -> platformGroups = value));
        y += ROW;
        addDrawableChild(onOff("disruptions", disruptions, left, y, half, value -> disruptions = value));
        addDrawableChild(onOff("tools", tools, left + half + GAP, y, half, value -> tools = value));
        y += ROW;

        // Both of these have their own screens; link rather than duplicate.
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.station_announcer.config.driving_hud"),
                        button -> {
                            save();   // the HUD screen writes the same file
                            MinecraftClient.getInstance().setScreen(new DrivingHudScreen(this));
                        })
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.station_announcer.config.keys"),
                        button -> MinecraftClient.getInstance().setScreen(
                                new KeybindsScreen(this, MinecraftClient.getInstance().options)))
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
        y += ROW + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> {
            save();
            close();
        }).dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    /** An on/off row whose label and tooltip come from one key. */
    private CyclingButtonWidget<Boolean> onOff(String key, boolean initially, int x, int y, int width,
                                              java.util.function.Consumer<Boolean> setter) {
        String base = "gui.station_announcer.config." + key;
        return CyclingButtonWidget.onOffBuilder(initially)
                .tooltip(value -> Tooltip.of(Text.translatable(base + ".tip")))
                .build(x, y, width, WIDGET_HEIGHT, Text.translatable(base), (button, value) -> setter.accept(value));
    }

    // --------------------------------------------------------------- lifecycle

    private void save() {
        ClientConfig client = ClientConfig.get();
        client.enableTts = tts;
        client.enableChime = chime;
        client.displayMode = actionBar ? "actionbar" : "chat";
        client.ttsBackend = backend;
        client.chimeCategory = chimeCategory;
        client.voice = voice.trim();
        ClientConfig.persist();

        AddonClientConfig addon = AddonClientConfig.get();
        addon.showHoldRulesButton = holdRules;
        addon.showRouteDwellButton = routeDwell;
        addon.showLiftDoorSidesButton = liftDoors;
        addon.showPlatformGroupButton = platformGroups;
        addon.showDisruptionsButton = disruptions;
        addon.showToolsButton = tools;
        AddonClientConfig.persist();
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        int left = (width - PANEL_WIDTH) / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, announcementsY - 20, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.config.section.announcements"),
                left, announcementsY + 3, TEXT_DIM);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.config.section.dispatch"),
                left, dispatchY + 3, TEXT_DIM);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
