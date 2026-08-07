package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Platform;
import java.util.ArrayList;
import java.util.List;

/**
 * Holding-light settings (opened with the MTR brush): which platform drives
 * the light — Auto, or one of the platforms detected nearby — and how far
 * either side of the cue it switches. Both timings can be left on Auto, which
 * uses the light's own defaults (yellow 5 s before arrival / 6 s before
 * departure, green 3 s before departure / 15 s after it). Saved through the
 * shared decor channel.
 */
@Environment(EnvType.CLIENT)
public class HoldingLightScreen extends Screen {
    private static final int PANEL_WIDTH = 220;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 6;

    /** One selectable entry: platform id ("" = auto) and its label. */
    private record Option(String id, String label) {
    }

    private final StationDecorBlockEntity decor;
    private final List<Option> options = new ArrayList<>();
    private String selected;
    private int onSeconds;
    private int offSeconds;

    /** Slider position that means "leave it on the light's own default". */
    private static final int AUTO = StationDecorBlockEntity.UNSET_SECONDS;

    public HoldingLightScreen(StationDecorBlockEntity decor) {
        super(Text.translatable("gui.station_announcer.holding_light.title"));
        this.decor = decor;
        this.selected = decor.getCustomName();
        this.onSeconds = decor.getLightOnSeconds();
        this.offSeconds = decor.getLightOffSeconds();
    }

    /** Green lights come on before departure and linger after it; yellow brackets the dwell. */
    private boolean isGreen() {
        return decor.getCachedState().getBlock() instanceof com.stationannouncer.mtr.HoldingLightBlock light
                && light.green;
    }

    private Text timingCaption(String key, int seconds) {
        int shown = seconds == AUTO ? defaultFor(key) : seconds;
        return seconds == AUTO
                ? Text.translatable("gui.station_announcer.holding_light." + key + ".auto", shown)
                : Text.translatable("gui.station_announcer.holding_light." + key, shown);
    }

    private int defaultFor(String key) {
        boolean green = isGreen();
        if (key.equals("on_delay")) {
            return green ? 3 : 5;
        }
        return green ? 15 : 6;
    }

    @Override
    protected void init() {
        options.clear();
        options.add(new Option("", Text.translatable("gui.station_announcer.holding_light.auto").getString()));
        List<Platform> platforms = new ArrayList<>();
        org.mtr.mod.InitClient.findClosePlatform(
                new org.mtr.mapping.holder.BlockPos(decor.getPos()), 16, platforms::add);
        for (Platform platform : platforms) {
            String name = platform.getName();
            int split = name.indexOf('|');
            String label = "Platform " + (split >= 0 ? name.substring(0, split) : name).trim();
            options.add(new Option(String.valueOf(platform.getId()), label));
        }
        Option current = options.stream().filter(option -> option.id().equals(selected))
                .findFirst().orElse(options.get(0));

        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;
        int y = height / 2 - 48;

        addDrawableChild(CyclingButtonWidget.<Option>builder(option -> Text.literal(option.label()))
                .values(options)
                .initially(current)
                .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.holding_light.platform"),
                        (button, value) -> selected = value.id()));
        y += WIDGET_HEIGHT + GAP;

        // -1 is the far-left "Auto" notch on both sliders.
        addDrawableChild(new com.stationannouncer.client.gui.IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                AUTO, StationDecorBlockEntity.MAX_LIGHT_SECONDS, onSeconds,
                value -> timingCaption("on_delay", value), value -> onSeconds = value));
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(new com.stationannouncer.client.gui.IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                AUTO, StationDecorBlockEntity.MAX_LIGHT_SECONDS, offSeconds,
                value -> timingCaption("off_delay", value), value -> offSeconds = value));
        y += WIDGET_HEIGHT + GAP + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    private void saveAndClose() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(decor.getPos());
        buf.writeString(selected, StationDecorBlockEntity.MAX_NAME_LENGTH);
        buf.writeInt(onSeconds);
        buf.writeInt(offSeconds);
        ClientPlayNetworking.send(MtrStationDecor.UPDATE_DECOR_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 48, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
