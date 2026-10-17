package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.WeatherSpec;
import com.stationannouncer.atmosphere.Weathering;
import com.stationannouncer.atmosphere.WeatheringToolItem;
import com.stationannouncer.client.gui.FlatPaScreen;
import com.stationannouncer.client.mtraddon.FlatUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

/**
 * Weathering Tool settings: a condition preset, the amount of every layer
 * (presets are just starting points — any slider makes it "Custom"), the
 * light failures, and the area options. Saved to the held tool on close.
 */
@Environment(EnvType.CLIENT)
public class WeatheringScreen extends FlatPaScreen {
    private static final int HIT_PRESET = 1;
    private static final int HIT_CLEAN = 2;
    private static final int HIT_UNDO = 3;
    private static final int HIT_DONE = 4;
    private static final int KEY_HEIGHT = 100;
    private static final int KEY_BELOW = 101;
    private static final int KEY_ABOVE = 102;

    private final Hand hand;
    private final WeatherSpec spec;

    public WeatheringScreen(Hand hand) {
        super(Text.translatable("item.station_announcer.weathering_tool"));
        this.hand = hand;
        MinecraftClient client = MinecraftClient.getInstance();
        ItemStack stack = client.player == null ? ItemStack.EMPTY : client.player.getStackInHand(hand);
        this.spec = WeatherSpec.read(stack);
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        renderBackground(c, mx, my, 0);
        int w = Math.min(380, width - 12);
        int h = Math.min(236, height - 6);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        FlatUi.pane(c, x, y, w, h);
        c.drawText(textRenderer, title, x + 8, y + 7, FlatUi.TEXT, false);
        c.drawText(textRenderer, trim(tr("gui.station_announcer.weathering.hint"), w - 16), x + 8, y + 18,
                FlatUi.TEXT_FAINT, false);

        // presets
        int py = y + 31;
        int px = x + 8;
        WeatherSpec.Preset current = spec.matching();
        for (WeatherSpec.Preset p : WeatherSpec.Preset.values()) {
            String label = tr("gui.station_announcer.weathering.preset." + p.id);
            int bw = textWidth(label) + 10;
            button(c, mx, my, label, px, py, bw, 14, p == current ? FlatUi.ButtonStyle.PRIMARY : FlatUi.ButtonStyle.FLAT,
                    true, HIT_PRESET, p.ordinal());
            px += bw + 3;
        }
        if (current == null) {
            c.drawText(textRenderer, tr("gui.station_announcer.weathering.custom"), px + 4, py + 3, FlatUi.TEXT_DIM, false);
        }

        // layers, two columns
        WeatherSpec.Layer[] layers = WeatherSpec.Layer.values();
        int colW = (w - 24) / 2;
        int rows = (layers.length + 1) / 2;
        int rowH = Math.max(11, Math.min(14, (h - 31 - 18 - 60) / rows));
        int ly = py + 19;
        int labelW = Math.min(78, colW / 2);
        for (int i = 0; i < layers.length; i++) {
            WeatherSpec.Layer layer = layers[i];
            int col = i < rows ? 0 : 1;
            int row = i < rows ? i : i - rows;
            int lx = x + 8 + col * (colW + 8);
            int value = spec.get(layer);
            slider(c, mx, my, layer.ordinal(), trim(tr("gui.station_announcer.weathering.layer." + layer.id), labelW - 3),
                    labelW, value, 0, 100, value + "%", lx, ly + row * rowH, colW);
        }

        // area
        int ay = ly + rows * rowH + 5;
        FlatUi.heading(c, textRenderer, tr("gui.station_announcer.weathering.area_heading"), x + 8, ay);
        ay += 11;
        int third = (w - 24) / 3;
        slider(c, mx, my, KEY_HEIGHT, tr("gui.station_announcer.weathering.height"), 40, spec.height, 1, 24,
                "+" + spec.height, x + 8, ay, third);
        slider(c, mx, my, KEY_BELOW, tr("gui.station_announcer.weathering.below"), 40, spec.stationBelow, 0, 32,
                "-" + spec.stationBelow, x + 12 + third, ay, third);
        slider(c, mx, my, KEY_ABOVE, tr("gui.station_announcer.weathering.above"), 40, spec.stationAbove, 1, 32,
                "+" + spec.stationAbove, x + 16 + 2 * third, ay, third);
        ay += 15;
        toggleRow(c, mx, my, tr("gui.station_announcer.weathering.clean"), spec.clean, x + 8, ay, Math.min(220, w - 16),
                HIT_CLEAN, 0);

        int by = y + h - 20;
        c.drawText(textRenderer, trim(tr("gui.station_announcer.weathering.hint2"), w - 150), x + 8, by + 4,
                FlatUi.TEXT_FAINT, false);
        button(c, mx, my, tr("gui.station_announcer.weathering.undo"), x + w - 132, by, 64, 15,
                FlatUi.ButtonStyle.DANGER, true, HIT_UNDO, 0);
        button(c, mx, my, tr("gui.done"), x + w - 64, by, 56, 15, FlatUi.ButtonStyle.PRIMARY, true, HIT_DONE, 0);
    }

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        switch (id) {
            case HIT_PRESET -> {
                spec.load(WeatherSpec.Preset.values()[arg]);
                save();
            }
            case HIT_CLEAN -> {
                spec.clean = !spec.clean;
                save();
            }
            case HIT_UNDO -> {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBoolean(hand == Hand.OFF_HAND);
                buf.writeByte(Weathering.ACTION_UNDO);
                ClientPlayNetworking.send(Weathering.ACTION_C2S, buf);
                close();
            }
            case HIT_DONE -> close();
            default -> {
            }
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        if (key == KEY_HEIGHT) {
            spec.height = value;
        } else if (key == KEY_BELOW) {
            spec.stationBelow = value;
        } else if (key == KEY_ABOVE) {
            spec.stationAbove = value;
        } else if (key >= 0 && key < WeatherSpec.Layer.values().length) {
            spec.set(WeatherSpec.Layer.values()[key], value);
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        save();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void removed() {
        save();
        super.removed();
    }

    private void save() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            ItemStack stack = client.player.getStackInHand(hand);
            if (stack.getItem() instanceof WeatheringToolItem) {
                spec.write(stack);
            }
        }
        if (client.getNetworkHandler() != null) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(hand == Hand.OFF_HAND);
            buf.writeNbt(spec.toNbt());
            ClientPlayNetworking.send(Weathering.UPDATE_C2S, buf);
        }
    }
}
