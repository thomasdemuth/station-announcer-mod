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
 * Platform picker for the holding lights (opened with the MTR brush): Auto,
 * or one of the platforms detected near the block. The chosen platform id is
 * stored on the block ("" = auto) through the shared decor channel.
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

    public HoldingLightScreen(StationDecorBlockEntity decor) {
        super(Text.translatable("gui.station_announcer.holding_light.title"));
        this.decor = decor;
        this.selected = decor.getCustomName();
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
        int y = height / 2 - 24;

        addDrawableChild(CyclingButtonWidget.<Option>builder(option -> Text.literal(option.label()))
                .values(options)
                .initially(current)
                .build(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.holding_light.platform"),
                        (button, value) -> selected = value.id()));
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
