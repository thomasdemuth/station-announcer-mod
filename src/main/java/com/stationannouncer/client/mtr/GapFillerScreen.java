package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.mtr.GapFillerBlock;
import com.stationannouncer.mtr.GapFillerBlockEntity;
import com.stationannouncer.mtr.GapFillers;
import com.stationannouncer.mtraddon.GapFillerEngine;
import com.stationannouncer.mtraddon.GapFillerStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * The gap filler's brush screen (FlatUi, no vanilla widgets): which platform
 * it serves (relink), how far its plate reaches (slider or "Auto" = measured
 * from the rail), and the platform's timing — extend, retract and the minimum
 * dwell — which every filler on that platform shares.
 *
 * <p>Relink and Auto act at once (the block entity syncs the result back and
 * this screen reads it live); everything else is sent on Save.</p>
 */
public class GapFillerScreen extends Screen {
    private static final int W = 284;
    private static final int H = 176;

    private final GapFillerBlockEntity filler;

    private int reach;
    private int extendMs;
    private int retractMs;
    private int minDwellMs;
    private boolean reachTouched;

    /** Which slider the mouse is dragging, -1 = none. */
    private int dragging = -1;

    // Layout, recomputed each frame.
    private int left;
    private int top;
    private final int[][] sliders = new int[4][4]; // x, y, w, h

    public GapFillerScreen(GapFillerBlockEntity filler) {
        super(Text.translatable("gui.station_announcer.gap_filler.title"));
        this.filler = filler;
        this.reach = filler.getCachedState().get(GapFillerBlock.REACH);
        this.extendMs = filler.getExtendMs();
        this.retractMs = filler.getRetractMs();
        this.minDwellMs = filler.getMinDwellMs();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, 0xB0000000);
        left = (width - W) / 2;
        top = Math.max(4, (height - H) / 2);
        FlatUi.rect(c, left, top, W, H, FlatUi.GROUND);
        FlatUi.outline(c, left, top, W, H, FlatUi.BORDER_STRONG);

        BlockState state = filler.getWorld() == null ? filler.getCachedState()
                : filler.getWorld().getBlockState(filler.getPos());
        if (!reachTouched && state.getBlock() instanceof GapFillerBlock) {
            reach = state.get(GapFillerBlock.REACH); // follow Auto / relink results until edited
        }
        boolean loop = filler.getStyle() == GapFillerBlock.Style.LOOP;

        int x = left + 10;
        int right = left + W - 10;
        int y = top + 8;
        c.drawText(textRenderer, title, x, y, FlatUi.TEXT, false);
        String style = Text.translatable(loop ? "gui.station_announcer.gap_filler.style_loop"
                : "gui.station_announcer.gap_filler.style_union").getString();
        c.drawText(textRenderer, style, right - textRenderer.getWidth(style), y, FlatUi.TEXT_DIM, false);

        // Platform
        y += 16;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.gap_filler.platform").getString(), x, y);
        y += 10;
        boolean linked = filler.getPlatformId() != 0;
        String platform = linked ? filler.getPlatformLabel()
                : Text.translatable("gui.station_announcer.gap_filler.unlinked").getString();
        c.drawText(textRenderer, textRenderer.trimToWidth(platform, W - 90), x, y + 4,
                linked ? FlatUi.TEXT : FlatUi.DANGER, false);
        FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.gap_filler.relink").getString(),
                right - 60, y, 60, 16, mx, my, FlatUi.ButtonStyle.FLAT);

        // Plate
        y += 24;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.gap_filler.plate").getString(), x, y);
        y += 11;
        slider(c, 0, Text.translatable("gui.station_announcer.gap_filler.reach").getString(),
                (reach - 1) / 11f, (reach * 2) + " px", x, y, W - 64, mx, my);
        boolean auto = filler.isReachAuto() && !reachTouched;
        FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.gap_filler.auto").getString(),
                right - 34, y - 3, 34, 14, mx, my, auto ? FlatUi.ButtonStyle.PRIMARY : FlatUi.ButtonStyle.FLAT);

        // Timing
        y += 19;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.gap_filler.timing").getString(), x, y);
        y += 11;
        int move = GapFillerStore.Settings.MAX_MOVE_MS - GapFillerStore.Settings.MIN_MOVE_MS;
        slider(c, 1, Text.translatable("gui.station_announcer.gap_filler.extend").getString(),
                (extendMs - GapFillerStore.Settings.MIN_MOVE_MS) / (float) move, seconds(extendMs), x, y, W - 20, mx, my);
        y += 15;
        slider(c, 2, Text.translatable("gui.station_announcer.gap_filler.retract").getString(),
                (retractMs - GapFillerStore.Settings.MIN_MOVE_MS) / (float) move, seconds(retractMs), x, y, W - 20, mx, my);
        y += 15;
        String dwell = minDwellMs == 0 ? Text.translatable("gui.station_announcer.gap_filler.off").getString() : seconds(minDwellMs);
        slider(c, 3, Text.translatable("gui.station_announcer.gap_filler.min_dwell").getString(),
                minDwellMs / (float) GapFillerStore.Settings.MAX_DWELL_MS, dwell, x, y, W - 20, mx, my);

        // What a stop here will look like.
        y += 15;
        long sequence = extendMs + GapFillerEngine.MIN_DOORS_OPEN_MILLIS + GapFillerEngine.DOOR_MOVE_MILLIS + retractMs;
        long shortest = Math.max(sequence, minDwellMs);
        c.drawText(textRenderer, Text.translatable("gui.station_announcer.gap_filler.summary",
                seconds(extendMs), seconds(shortest)).getString(), x, y, FlatUi.TEXT_FAINT, false);

        // Footer
        int by = top + H - 24;
        FlatUi.button(c, textRenderer, Text.translatable("gui.cancel").getString(), right - 128, by, 60, 16,
                mx, my, FlatUi.ButtonStyle.GHOST);
        FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.gap_filler.save").getString(),
                right - 60, by, 60, 16, mx, my, FlatUi.ButtonStyle.PRIMARY, linked);
    }

    /** Label, track + thumb, value text. Records the track rectangle for dragging. */
    private void slider(DrawContext c, int index, String label, float value, String text,
                        int x, int y, int w, int mx, int my) {
        int labelW = 74;
        int valueW = 40;
        c.drawText(textRenderer, label, x, y, FlatUi.TEXT_DIM, false);
        int tx = x + labelW;
        int tw = w - labelW - valueW;
        int[] r = sliders[index];
        r[0] = tx;
        r[1] = y - 2;
        r[2] = tw;
        r[3] = 12;
        float v = Math.max(0f, Math.min(1f, value));
        boolean hot = dragging == index || FlatUi.inside(mx, my, r[0], r[1], r[2], r[3]);
        FlatUi.rect(c, tx, y + 3, tw, 3, FlatUi.INPUT);
        FlatUi.rect(c, tx, y + 3, Math.round(tw * v), 3, hot ? FlatUi.ACCENT : FlatUi.ACCENT_DIM);
        int thumb = tx + Math.round((tw - 4) * v);
        FlatUi.rect(c, thumb, y, 4, 9, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        c.drawText(textRenderer, text, tx + tw + 6, y, FlatUi.TEXT, false);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int right = left + W - 10;
        for (int i = 0; i < sliders.length; i++) {
            int[] r = sliders[i];
            if (FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                dragging = i;
                drag(mx);
                return true;
            }
        }
        int platformRowY = top + 8 + 16 + 10;
        if (FlatUi.inside(mx, my, right - 60, platformRowY, 60, 16)) {
            send(GapFillers.ACTION_RELINK);
            return true;
        }
        int reachRowY = platformRowY + 24 + 11;
        if (FlatUi.inside(mx, my, right - 34, reachRowY - 3, 34, 14)) {
            reachTouched = false;
            send(GapFillers.ACTION_AUTO_REACH);
            return true;
        }
        int by = top + H - 24;
        if (FlatUi.inside(mx, my, right - 128, by, 60, 16)) {
            close();
            return true;
        }
        if (FlatUi.inside(mx, my, right - 60, by, 60, 16) && filler.getPlatformId() != 0) {
            send(GapFillers.ACTION_SAVE);
            close();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging >= 0) {
            drag(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = -1;
        return super.mouseReleased(mx, my, button);
    }

    private void drag(double mx) {
        int[] r = sliders[dragging];
        float v = (float) Math.max(0, Math.min(1, (mx - r[0]) / Math.max(1, r[2] - 4)));
        int move = GapFillerStore.Settings.MAX_MOVE_MS - GapFillerStore.Settings.MIN_MOVE_MS;
        switch (dragging) {
            case 0 -> {
                reach = 1 + Math.round(v * 11);
                reachTouched = true;
            }
            case 1 -> extendMs = snap(GapFillerStore.Settings.MIN_MOVE_MS + v * move, 250);
            case 2 -> retractMs = snap(GapFillerStore.Settings.MIN_MOVE_MS + v * move, 250);
            case 3 -> minDwellMs = snap(v * GapFillerStore.Settings.MAX_DWELL_MS, 1000);
            default -> {
            }
        }
    }

    private static int snap(float value, int step) {
        return Math.round(value / step) * step;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER && filler.getPlatformId() != 0) {
            send(GapFillers.ACTION_SAVE);
            close();
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    private void send(int action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(filler.getPos());
        buf.writeByte(action);
        buf.writeByte(reach);
        buf.writeVarInt(extendMs);
        buf.writeVarInt(retractMs);
        buf.writeVarInt(minDwellMs);
        ClientPlayNetworking.send(GapFillers.UPDATE_GAP_FILLER_C2S, buf);
    }
}
