package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
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
 * The service change posters linked to one disruption, in the flat design-tool
 * look shared with the editor: a list of poster cards (each with a thumbnail
 * of the actual poster, its title bar, timing and headline) with Edit / Copy /
 * delete, and New poster, which starts from a template filled in from the
 * disruption. Opened from the Posters action on a disruption row.
 */
@Environment(EnvType.CLIENT)
public class PosterListScreen extends Screen {
    private static final int PAD = 4;
    private static final int TOP_BAR = 22;
    private static final int ROW = 46;
    private static final float THUMB_SCALE = 0.19f;

    private final Screen parent;
    private final long disruptionId;
    private List<ServicePoster> rows = List.of();
    private int scroll;

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private final List<int[]> hits = new ArrayList<>();
    private static final int HIT_ROW = 1;
    private static final int HIT_EDIT = 2;
    private static final int HIT_COPY = 3;
    private static final int HIT_DELETE = 4;
    private static final int HIT_NEW = 5;
    private static final int HIT_DONE = 6;

    public PosterListScreen(long disruptionId, Screen parent) {
        super(Text.translatable("gui.station_announcer.posters.title"));
        this.disruptionId = disruptionId;
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = ClientPosters.forDisruption(disruptionId);
        listW = Math.min(420, width - PAD * 2);
        listX = (width - listW) / 2;
        listY = TOP_BAR + PAD;
        listH = height - listY - PAD;
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
                case HIT_ROW, HIT_EDIT -> {
                    if (r[5] < rows.size()) {
                        edit(rows.get(r[5]));
                    }
                }
                case HIT_COPY -> {
                    if (r[5] < rows.size()) {
                        ServicePoster.Builder draft = new ServicePoster.Builder(rows.get(r[5]));
                        draft.id = 0;
                        if (client != null) {
                            client.setScreen(new PosterEditScreen(draft, this));
                        }
                    }
                }
                case HIT_DELETE -> {
                    if (r[5] < rows.size()) {
                        sendDelete(rows.get(r[5]).id());
                    }
                }
                case HIT_NEW -> {
                    ClientDisruptions.Entry disruption = ClientDisruptions.byId(disruptionId);
                    if (client != null && disruption != null) {
                        client.setScreen(new PosterEditScreen(PosterLayout.template(disruption), this));
                    }
                }
                case HIT_DONE -> close();
                default -> {
                }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void edit(ServicePoster poster) {
        if (client != null) {
            client.setScreen(new PosterEditScreen(new ServicePoster.Builder(poster), this));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int viewH = listH - 18;
        int max = Math.max(0, rows.size() * ROW - viewH);
        if (max > 0 && FlatUi.inside(mouseX, mouseY, listX, listY, listW, listH)) {
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

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        rows = ClientPosters.forDisruption(disruptionId);
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);

        // Top bar.
        FlatUi.rect(c, 0, 0, width, TOP_BAR, FlatUi.PANE);
        FlatUi.rect(c, 0, TOP_BAR - 1, width, 1, FlatUi.BORDER);
        c.drawText(textRenderer, title, PAD + 4, 7, FlatUi.TEXT, false);
        ClientDisruptions.Entry disruption = ClientDisruptions.byId(disruptionId);
        if (disruption != null) {
            int x = PAD + 10 + textRenderer.getWidth(title);
            c.drawText(textRenderer, textRenderer.trimToWidth("· " + disruption.message(), Math.max(0, width - x - 150)),
                    x, 7, FlatUi.TEXT_DIM, false);
        }
        int doneW = 50;
        int newW = 80;
        int dx = width - PAD - doneW;
        int nx = dx - 4 - newW;
        FlatUi.button(c, textRenderer, "+ New poster", nx, 2, newW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY);
        hit(nx, 2, newW, FlatUi.BUTTON_HEIGHT, HIT_NEW, 0);
        FlatUi.button(c, textRenderer, "Done", dx, 2, doneW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(dx, 2, doneW, FlatUi.BUTTON_HEIGHT, HIT_DONE, 0);

        // List pane.
        FlatUi.pane(c, listX, listY, listW, listH);
        FlatUi.heading(c, textRenderer, rows.size() + (rows.size() == 1 ? " poster" : " posters"), listX + 6, listY + 5);
        int top = listY + 18;
        int viewH = listH - 18;
        if (rows.isEmpty()) {
            c.drawText(textRenderer, "No posters yet.", listX + 8, top + 8, FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, "New poster starts from this disruption's lines and message.",
                    listX + 8, top + 20, FlatUi.TEXT_FAINT, false);
            return;
        }
        c.enableScissor(listX + 1, top, listX + listW - 1, listY + listH - 1);
        for (int i = 0; i < rows.size(); i++) {
            int y = top + i * ROW - scroll;
            if (y + ROW < top || y > listY + listH) {
                continue;
            }
            drawRow(c, rows.get(i), i, y, top, mx, my);
        }
        c.disableScissor();
        FlatUi.scrollThumb(c, listX + listW, top, viewH, rows.size() * ROW, scroll);
    }

    private void drawRow(DrawContext c, ServicePoster poster, int index, int y, int clipTop, int mx, int my) {
        int x = listX + 1;
        int w = listW - 2;
        boolean hovered = FlatUi.inside(mx, my, x, y, w, ROW) && my >= clipTop && my < listY + listH;
        if (hovered) {
            FlatUi.rect(c, x, y, w, ROW, FlatUi.HOVER);
        }
        FlatUi.rect(c, x, y + ROW - 1, w, 1, FlatUi.BORDER);

        // Thumbnail of the real poster.
        int tw = Math.round(PosterLayout.WIDTH * THUMB_SCALE);
        int th = Math.round(PosterLayout.HEIGHT * THUMB_SCALE);
        int ty = y + (ROW - th) / 2;
        FlatUi.rect(c, x + 5, ty - 1, tw + 2, th + 2, 0xFF000000);
        PosterLayout.paint(new PosterLayout.GuiSurface(c, x + 6, ty, THUMB_SCALE), poster);

        int textX = x + 6 + tw + 8;
        int actionsW = 34 + 34 + 16 + 8;
        int textW = w - (textX - x) - actionsW - 6;
        int cx = textX;
        cx += FlatUi.chip(c, textRenderer, poster.kind(), cx, y + 6, 0xFF2A2A32, false) + 5;
        if (!poster.timing().isEmpty()) {
            c.drawText(textRenderer, textRenderer.trimToWidth(poster.timing(), Math.max(10, textX + textW - cx)),
                    cx, y + 8, FlatUi.TEXT_DIM, false);
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(stripTokens(poster.preview()).replace('\n', ' '), textW),
                textX, y + 20, FlatUi.TEXT, false);
        int chipX = textX;
        for (String line : poster.headerLines()) {
            var bullet = PosterLayout.lineBullet(line);
            int cw = textRenderer.getWidth(bullet.label()) + 8;
            if (chipX + cw > textX + textW) {
                break;
            }
            chipX += FlatUi.chip(c, textRenderer, bullet.label(), chipX, y + 31, bullet.color(), false) + 3;
        }
        if (poster.headerLines().isEmpty()) {
            c.drawText(textRenderer, textRenderer.trimToWidth(poster.category(), textW), chipX, y + 33, FlatUi.TEXT_FAINT, false);
        }

        int ax = x + w - actionsW;
        int ay = y + (ROW - FlatUi.BUTTON_HEIGHT) / 2;
        FlatUi.button(c, textRenderer, "Edit", ax, ay, 34, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
        hit(ax, Math.max(ay, clipTop), 34, FlatUi.BUTTON_HEIGHT, HIT_EDIT, index);
        FlatUi.button(c, textRenderer, "Copy", ax + 38, ay, 34, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
        hit(ax + 38, Math.max(ay, clipTop), 34, FlatUi.BUTTON_HEIGHT, HIT_COPY, index);
        FlatUi.iconButton(c, textRenderer, "×", ax + 76, ay + 1, 16, mx, my, FlatUi.DANGER);
        hit(ax + 76, Math.max(ay + 1, clipTop), 16, 16, HIT_DELETE, index);
        int visibleTop = Math.max(y, clipTop);
        hit(x, visibleTop, w - actionsW, Math.max(0, y + ROW - visibleTop), HIT_ROW, index);
    }

    /** Tokens read as their symbol name in a list row: {b:4} → [4]. */
    static String stripTokens(String text) {
        return text.replaceAll("\\{[bd]:([^}]*)}", "[$1]")
                .replaceAll("\\{wc}", "[wheelchair]")
                .replaceAll("\\{([<>^v])}", "$1");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    static void sendDelete(long id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(true);
        buf.writeLong(id);
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_POSTER_C2S, buf);
    }
}
