package com.stationannouncer.client.mtr;

import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.mtr.MtrPids;
import com.stationannouncer.mtr.PidsBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config screen for the PIDS NYC Hanging Mini (right-click without a brush).
 *
 * <p>Beyond the "Next train" toggle, the timing window is adjustable — how
 * long before arrival the sign lights and how long after it stays lit — and
 * each track the sign watches can override its arrow. LEFT/RIGHT overrides
 * are AS SEEN FROM THE BLOCK'S FRONT FACE; the renderer mirrors them on the
 * back face, so one setting points both faces at the same physical track.
 * Auto keeps the computed bearing toward the platform, which is right whenever
 * the sign isn't tucked somewhere the crow-flies direction misleads (the far
 * side of a mezzanine wall, the top of a stair).</p>
 */
@Environment(EnvType.CLIENT)
public class MiniPidsScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int ROW = WIDGET_HEIGHT + 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;

    /** How many tracks the arrow list shows; more would push Done off small windows. */
    private static final int MAX_ARROW_ROWS = 5;

    /** The auto-detection radius the renderer itself uses when no platforms are picked. */
    private static final int AUTO_PLATFORM_RADIUS = 5;

    private final PidsBlockEntity pids;
    private boolean nextTrainMode;
    private int onSeconds;
    private int offSeconds;

    /** One row per watched track, insertion-ordered like the sign's own list. */
    private final Map<Long, Integer> arrows = new LinkedHashMap<>();
    private final List<Long> platforms = new ArrayList<>();

    private int titleY;
    private int arrowsCaptionY = Integer.MIN_VALUE;

    public MiniPidsScreen(PidsBlockEntity pids) {
        super(Text.translatable("gui.station_announcer.mini.title"));
        this.pids = pids;
        this.nextTrainMode = pids.isNextTrainMode();
        this.onSeconds = pids.getNextTrainOnSeconds();
        this.offSeconds = pids.getNextTrainOffSeconds();

        // The tracks this sign actually watches: its configured platforms, or
        // the same nearby sweep the renderer falls back to. Existing overrides
        // are listed even if their platform is not currently found — a sign
        // keeps its setting through an MTR data hiccup instead of dropping it.
        pids.getPlatformIds().forEach((long id) -> {
            if (!platforms.contains(id)) {
                platforms.add(id);
            }
        });
        if (platforms.isEmpty()) {
            try {
                org.mtr.mod.InitClient.findClosePlatform(
                        new org.mtr.mapping.holder.BlockPos(pids.getPos()), AUTO_PLATFORM_RADIUS,
                        platform -> {
                            if (!platforms.contains(platform.getId())) {
                                platforms.add(platform.getId());
                            }
                        });
            } catch (Exception ignored) {
                // MTR data mid-sync — the list just comes back short
            }
        }
        pids.getNextTrainArrows().forEach((id, dir) -> {
            if (!platforms.contains(id)) {
                platforms.add(id);
            }
            arrows.put(id, dir);
        });
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int arrowRows = Math.min(platforms.size(), MAX_ARROW_ROWS);
        int content = ROW * 3 + (arrowRows > 0 ? 16 + arrowRows * ROW : 0) + 8 + WIDGET_HEIGHT;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 18;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(nextTrainMode)
                .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.next_train"),
                        (button, value) -> nextTrainMode = value));
        y += ROW;

        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                PidsBlockEntity.MIN_ON_SECONDS, PidsBlockEntity.MAX_ON_SECONDS, onSeconds,
                value -> Text.translatable("gui.station_announcer.next_train.on", value),
                value -> onSeconds = value));
        y += ROW;
        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                0, PidsBlockEntity.MAX_OFF_SECONDS, offSeconds,
                value -> Text.translatable("gui.station_announcer.next_train.off", value),
                value -> offSeconds = value));
        y += ROW;

        if (arrowRows > 0) {
            arrowsCaptionY = y + 2;
            y += 16;
            for (int i = 0; i < arrowRows; i++) {
                long platformId = platforms.get(i);
                addDrawableChild(CyclingButtonWidget.<Integer>builder(MiniPidsScreen::arrowLabel)
                        .values(PidsBlockEntity.ARROW_AUTO, PidsBlockEntity.ARROW_LEFT,
                                PidsBlockEntity.ARROW_RIGHT, PidsBlockEntity.ARROW_DOWN)
                        .initially(arrows.getOrDefault(platformId, PidsBlockEntity.ARROW_AUTO))
                        .tooltip(value -> Tooltip.of(
                                Text.translatable("gui.station_announcer.next_train.arrow.tip")))
                        .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                                Text.translatable("gui.station_announcer.next_train.arrow",
                                        platformLabel(platformId)),
                                (button, value) -> arrows.put(platformId, value)));
                y += ROW;
            }
        }
        y += 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private static Text arrowLabel(int value) {
        return switch (value) {
            case PidsBlockEntity.ARROW_LEFT -> Text.translatable("gui.station_announcer.next_train.arrow.left");
            case PidsBlockEntity.ARROW_RIGHT -> Text.translatable("gui.station_announcer.next_train.arrow.right");
            case PidsBlockEntity.ARROW_DOWN -> Text.translatable("gui.station_announcer.next_train.arrow.down");
            default -> Text.translatable("gui.station_announcer.next_train.arrow.auto");
        };
    }

    /** "Platform 1", or the raw id when MTR has not synced that platform yet. */
    private static String platformLabel(long platformId) {
        try {
            org.mtr.core.data.Platform platform =
                    org.mtr.mod.client.MinecraftClientData.getInstance().platformIdMap.get(platformId);
            if (platform != null) {
                String name = RailroadRouteData.firstLang(platform.getName());
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // MTR data mid-sync
        }
        return "#" + platformId;
    }

    // --------------------------------------------------------------- lifecycle

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pids.getPos());
        buf.writeBoolean(nextTrainMode);
        buf.writeVarInt(onSeconds);
        buf.writeVarInt(offSeconds);
        // AUTO entries are not sent: absent = auto, and the server replaces
        // the stored map wholesale.
        List<Map.Entry<Long, Integer>> overrides = arrows.entrySet().stream()
                .filter(entry -> entry.getValue() != PidsBlockEntity.ARROW_AUTO)
                .limit(PidsBlockEntity.MAX_ARROW_OVERRIDES)
                .toList();
        buf.writeVarInt(overrides.size());
        for (Map.Entry<Long, Integer> entry : overrides) {
            buf.writeLong(entry.getKey());
            buf.writeByte(entry.getValue());
        }
        ClientPlayNetworking.send(MtrPids.UPDATE_MINI_C2S, buf);
        close();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        if (arrowsCaptionY != Integer.MIN_VALUE) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.next_train.arrows"),
                    (width - PANEL_WIDTH) / 2, arrowsCaptionY, TEXT_DIM);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
