package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.mtr.ServicePosterBlockEntity;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * Opened by right-clicking a placed poster frame: every poster in the world as
 * a list of cards grouped under their disruptions, with a large preview of the
 * hovered (else the hung) poster on the right. Clicking a card hangs it; "Take
 * poster down" clears the frame. Same flat kit as the editor.
 */
@Environment(EnvType.CLIENT)
public class PosterSignPickerScreen extends Screen {
    private static final int PAD = 4;
    private static final int TOP_BAR = 22;
    private static final int ROW = 30;
    private static final int GROUP = 14;
    private static final int LIST_WIDTH = 250;

    private final ServicePosterBlockEntity sign;
    private List<ServicePoster> rows = List.of();
    private int scroll;

    private int listX;
    private int listY;
    private int listH;
    private int previewX;
    private int previewY;
    private float previewScale;
    private int contentH;
    private final List<int[]> hits = new ArrayList<>();
    private static final int HIT_ROW = 1;
    private static final int HIT_CLEAR = 2;
    private static final int HIT_CANCEL = 3;

    public PosterSignPickerScreen(ServicePosterBlockEntity sign) {
        super(Text.translatable("gui.station_announcer.posters.sign_title"));
        this.sign = sign;
    }

    @Override
    protected void init() {
        rows = ClientPosters.all();
        listY = TOP_BAR + PAD;
        listH = height - listY - PAD;
        previewScale = Math.max(0.5f, Math.min(1.4f, (listH - 16) / (float) PosterLayout.HEIGHT));
        int previewW = Math.round(PosterLayout.WIDTH * previewScale);
        int total = LIST_WIDTH + 10 + previewW;
        listX = Math.max(PAD, (width - total) / 2);
        previewX = listX + LIST_WIDTH + 10;
        previewY = listY + 8;
    }

    private void hit(int x, int y, int w, int h, int id, int arg) {
        hits.add(new int[]{x, y, w, h, id, arg});
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int[] r : hits) {
            if (!FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                continue;
            }
            switch (r[4]) {
                case HIT_ROW -> {
                    if (r[5] < rows.size()) {
                        send(rows.get(r[5]).id());
                        close();
                    }
                }
                case HIT_CLEAR -> {
                    send(0);
                    close();
                }
                case HIT_CANCEL -> close();
                default -> {
                }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int viewH = listH - 18;
        int max = Math.max(0, contentH - viewH);
        if (max > 0 && FlatUi.inside(mouseX, mouseY, listX, listY, LIST_WIDTH, listH)) {
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * ROW / 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void send(long posterId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(sign.getPos());
        buf.writeLong(posterId);
        ClientPlayNetworking.send(DisruptionNetworking.SET_POSTER_SIGN_C2S, buf);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        rows = ClientPosters.all();
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);

        // Top bar.
        FlatUi.rect(c, 0, 0, width, TOP_BAR, FlatUi.PANE);
        FlatUi.rect(c, 0, TOP_BAR - 1, width, 1, FlatUi.BORDER);
        c.drawText(textRenderer, title, PAD + 4, 7, FlatUi.TEXT, false);
        ServicePoster current = ClientPosters.byId(sign.getPosterId());
        boolean detached = current == null && sign.getSnapshot() != null;
        if (current == null) {
            current = sign.getSnapshot();
        }
        int cancelW = 50;
        int clearW = 96;
        int cx = width - PAD - cancelW;
        int kx = cx - 4 - clearW;
        FlatUi.button(c, textRenderer, "Take poster down", kx, 2, clearW, FlatUi.BUTTON_HEIGHT, mx, my,
                ButtonStyle.DANGER, current != null);
        if (current != null) {
            hit(kx, 2, clearW, FlatUi.BUTTON_HEIGHT, HIT_CLEAR, 0);
        }
        FlatUi.button(c, textRenderer, "Cancel", cx, 2, cancelW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(cx, 2, cancelW, FlatUi.BUTTON_HEIGHT, HIT_CANCEL, 0);

        // List.
        FlatUi.pane(c, listX, listY, LIST_WIDTH, listH);
        String status = current == null ? "Empty frame"
                : detached ? "Hanging a snapshot (its disruption is gone)" : "Hanging: "
                + PosterListScreen.stripTokens(current.preview()).replace('\n', ' ');
        FlatUi.heading(c, textRenderer, textRenderer.trimToWidth(status, LIST_WIDTH - 12), listX + 6, listY + 5);
        int top = listY + 18;
        int viewH = listH - 18;
        int hoveredIndex = -1;
        if (rows.isEmpty()) {
            c.drawText(textRenderer, "No posters exist yet.", listX + 8, top + 8, FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, "Create one from a disruption on the", listX + 8, top + 20, FlatUi.TEXT_FAINT, false);
            c.drawText(textRenderer, "dashboard's Disruptions board first.", listX + 8, top + 30, FlatUi.TEXT_FAINT, false);
            contentH = 0;
        } else {
            c.enableScissor(listX + 1, top, listX + LIST_WIDTH - 1, listY + listH - 1);
            int y = top - scroll;
            long lastDisruption = Long.MIN_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                ServicePoster poster = rows.get(i);
                if (poster.disruptionId() != lastDisruption) {
                    lastDisruption = poster.disruptionId();
                    ClientDisruptions.Entry disruption = ClientDisruptions.byId(poster.disruptionId());
                    int gx = listX + 6;
                    if (disruption != null) {
                        gx += FlatUi.chip(c, textRenderer,
                                Text.translatable(DisruptionsScreen.severityKey(disruption.severity())).getString(),
                                gx, y + 1, DisruptionsScreen.severityColor(disruption.severity()), false) + 4;
                        c.drawText(textRenderer, textRenderer.trimToWidth(disruption.message(), listX + LIST_WIDTH - gx - 6),
                                gx, y + 3, FlatUi.TEXT_DIM, false);
                    } else {
                        c.drawText(textRenderer, "Disruption #" + poster.disruptionId(), gx, y + 3, FlatUi.TEXT_FAINT, false);
                    }
                    y += GROUP;
                }
                boolean hovered = FlatUi.inside(mx, my, listX + 1, y, LIST_WIDTH - 2, ROW) && my >= top && my < listY + listH;
                if (hovered) {
                    hoveredIndex = i;
                    FlatUi.rect(c, listX + 1, y, LIST_WIDTH - 2, ROW, FlatUi.HOVER);
                }
                if (poster.id() == sign.getPosterId()) {
                    FlatUi.rect(c, listX + 1, y, LIST_WIDTH - 2, ROW, FlatUi.SELECTED);
                    FlatUi.rect(c, listX + 1, y, 2, ROW, FlatUi.ACCENT);
                }
                FlatUi.rect(c, listX + 1, y + ROW - 1, LIST_WIDTH - 2, 1, FlatUi.BORDER);
                int chipX = listX + 10;
                chipX += FlatUi.chip(c, textRenderer, poster.kind(), chipX, y + 4, 0xFF2A2A32, false) + 4;
                for (String line : poster.headerLines()) {
                    var bullet = PosterLayout.lineBullet(line);
                    int cw = textRenderer.getWidth(bullet.label()) + 8;
                    if (chipX + cw > listX + LIST_WIDTH - 8) {
                        break;
                    }
                    chipX += FlatUi.chip(c, textRenderer, bullet.label(), chipX, y + 4, bullet.color(), false) + 3;
                }
                c.drawText(textRenderer, textRenderer.trimToWidth(
                                PosterListScreen.stripTokens(poster.preview()).replace('\n', ' '), LIST_WIDTH - 20),
                        listX + 10, y + 18, FlatUi.TEXT, false);
                int visibleTop = Math.max(y, top);
                int visibleBottom = Math.min(y + ROW, listY + listH - 1);
                if (visibleBottom > visibleTop) {
                    hit(listX + 1, visibleTop, LIST_WIDTH - 2, visibleBottom - visibleTop, HIT_ROW, i);
                }
                y += ROW;
            }
            c.disableScissor();
            contentH = y + scroll - top;
            FlatUi.scrollThumb(c, listX + LIST_WIDTH, top, viewH, contentH, scroll);
        }

        // Preview: the hovered poster, else the hung one.
        ServicePoster shown = hoveredIndex >= 0 ? rows.get(hoveredIndex) : current;
        int pw = Math.round(PosterLayout.WIDTH * previewScale);
        int ph = Math.round(PosterLayout.HEIGHT * previewScale);
        FlatUi.rect(c, previewX - 2, previewY - 2, pw + 4, ph + 4, 0xFF000000);
        if (shown != null) {
            PosterLayout.paint(new PosterLayout.GuiSurface(c, previewX, previewY, previewScale), shown);
        } else {
            FlatUi.rect(c, previewX, previewY, pw, ph, 0xFF26262C);
            String empty = "Hover a poster to preview it";
            c.drawText(textRenderer, empty, previewX + (pw - textRenderer.getWidth(empty)) / 2, previewY + ph / 2 - 4,
                    FlatUi.TEXT_FAINT, false);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
