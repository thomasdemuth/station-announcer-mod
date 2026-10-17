package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.Atmosphere;
import com.stationannouncer.atmosphere.LightCondition;
import com.stationannouncer.atmosphere.MaintenanceToolItem;
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
 * The Maintenance Tool's picker: which condition a right-click applies.
 * Each row shows a little lamp running the condition's real pattern, so you
 * pick a flicker by watching it.
 */
@Environment(EnvType.CLIENT)
public class MaintenanceScreen extends FlatPaScreen {
    private static final int HIT_ROW = 1;
    private static final int HIT_DONE = 2;
    private static final LightCondition[] ROWS = {
            LightCondition.STUTTER, LightCondition.BALLAST, LightCondition.PULSE, LightCondition.RESTRIKE,
            LightCondition.DIM, LightCondition.WARM, LightCondition.DEAD};

    private final Hand hand;
    private LightCondition selected;

    public MaintenanceScreen(Hand hand) {
        super(Text.translatable("item.station_announcer.maintenance_tool"));
        this.hand = hand;
        MinecraftClient client = MinecraftClient.getInstance();
        ItemStack stack = client.player == null ? ItemStack.EMPTY : client.player.getStackInHand(hand);
        this.selected = MaintenanceToolItem.selected(stack);
    }

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        renderBackground(c, mx, my, 0);
        int rowH = 22;
        int w = Math.min(300, width - 16);
        int h = 44 + ROWS.length * rowH + 40;
        if (h > height - 8) {
            rowH = Math.max(15, (height - 8 - 84) / ROWS.length);
            h = 44 + ROWS.length * rowH + 40;
        }
        int x = (width - w) / 2;
        int y = Math.max(4, (height - h) / 2);
        FlatUi.pane(c, x, y, w, h);
        c.drawText(textRenderer, title, x + 10, y + 9, FlatUi.TEXT, false);
        c.drawText(textRenderer, trim(Text.translatable("gui.station_announcer.maintenance.hint").getString(), w - 20),
                x + 10, y + 22, FlatUi.TEXT_DIM, false);

        long now = System.currentTimeMillis();
        int ry = y + 38;
        for (int i = 0; i < ROWS.length; i++) {
            LightCondition cond = ROWS[i];
            boolean sel = cond == selected;
            boolean hot = FlatUi.inside(mx, my, x + 6, ry, w - 12, rowH - 2);
            if (sel) {
                FlatUi.rect(c, x + 6, ry, w - 12, rowH - 2, FlatUi.SELECTED);
                FlatUi.outline(c, x + 6, ry, w - 12, rowH - 2, FlatUi.ACCENT);
            } else if (hot) {
                FlatUi.rect(c, x + 6, ry, w - 12, rowH - 2, FlatUi.HOVER);
            }
            // the lamp swatch: a tube in a dark housing, running the real pattern
            int sx = x + 12;
            int sy = ry + (rowH - 2 - 8) / 2;
            FlatUi.rect(c, sx - 1, sy - 1, 30, 10, 0xFF0C0C0E);
            float lens = cond.lens(7_919 * (i + 3), now);
            float[] t = cond.tint();
            int r = Math.round(255 * Math.min(1f, 0.10f + lens * 0.92f) * t[0]);
            int g = Math.round(255 * Math.min(1f, 0.10f + lens * 0.90f) * t[1]);
            int b = Math.round(255 * Math.min(1f, 0.12f + lens * 0.86f) * t[2]);
            FlatUi.rect(c, sx, sy, 28, 8, 0xFF000000 | r << 16 | g << 8 | b);
            int tx = sx + 36;
            c.drawText(textRenderer, Text.translatable("atmosphere.station_announcer.light." + cond.id),
                    tx, ry + (rowH > 18 ? 2 : (rowH - 10) / 2), sel ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            if (rowH > 18) {
                c.drawText(textRenderer, trim(Text.translatable("atmosphere.station_announcer.light." + cond.id + ".desc").getString(),
                        w - 12 - (tx - x) - 6), tx, ry + 11, FlatUi.TEXT_FAINT, false);
            }
            hit(x + 6, ry, w - 12, rowH - 2, HIT_ROW, i);
            ry += rowH;
        }
        int fy = y + h - 34;
        c.drawText(textRenderer, trim(Text.translatable("gui.station_announcer.maintenance.hint2").getString(), w - 20),
                x + 10, fy, FlatUi.TEXT_FAINT, false);
        button(c, mx, my, Text.translatable("gui.done").getString(), x + w - 70, y + h - 22, 60, 16,
                FlatUi.ButtonStyle.PRIMARY, true, HIT_DONE, 0);
    }

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        if (id == HIT_ROW) {
            selected = ROWS[arg];
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(hand == Hand.OFF_HAND);
            buf.writeVarInt(selected.ordinal());
            ClientPlayNetworking.send(Atmosphere.MAINTENANCE_SELECT_C2S, buf);
            // mirror locally so the tooltip/next open agree before the server's copy syncs
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                ItemStack stack = client.player.getStackInHand(hand);
                if (stack.getItem() instanceof MaintenanceToolItem) {
                    MaintenanceToolItem.select(stack, selected);
                }
            }
        } else if (id == HIT_DONE) {
            close();
        }
    }

    @Override
    protected void onSlider(int key, int value) {
    }

    @Override
    protected boolean onEnter() {
        close();
        return true;
    }
}
