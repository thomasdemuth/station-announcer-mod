package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtr.ServicePosterBlockEntity;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import java.util.List;

/**
 * Opened by right-clicking a placed poster frame: every poster in the world,
 * grouped under its disruption, with a preview of the hovered one on the right.
 * Clicking a row hangs that poster (the server copies a snapshot into the block
 * entity); "Take poster down" clears the frame.
 */
@Environment(EnvType.CLIENT)
public class PosterSignPickerScreen extends Screen {
    private static final int LIST_WIDTH = 250;
    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;

    private final ServicePosterBlockEntity sign;
    private List<ServicePoster> rows = List.of();
    private int scroll;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int previewLeft;
    private int previewTop;
    private float previewScale;

    public PosterSignPickerScreen(ServicePosterBlockEntity sign) {
        super(Text.translatable("gui.station_announcer.posters.sign_title"));
        this.sign = sign;
    }

    @Override
    protected void init() {
        rows = ClientPosters.all();
        previewScale = Math.max(0.7f, Math.min(1.2f, (height - 70) / (float) PosterLayout.MAX_HEIGHT));
        int previewWidth = Math.round(PosterLayout.WIDTH * previewScale);
        int total = LIST_WIDTH + 10 + previewWidth;
        listLeft = Math.max(4, (width - total) / 2);
        previewLeft = listLeft + LIST_WIDTH + 10;

        int bottomBlock = 12 + (WIDGET_HEIGHT + GAP) * 2;
        int available = height - 40 - bottomBlock;
        visibleRows = Math.max(3, Math.min(Math.max(rows.size(), 3), available / ROW_HEIGHT));
        int listHeight = visibleRows * ROW_HEIGHT;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW_HEIGHT - listHeight)));
        int content = listHeight + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;
        previewTop = Math.max(20, (height - Math.round(PosterLayout.MAX_HEIGHT * previewScale)) / 2);
        y += listHeight + 14;

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.posters.sign_clear"),
                        button -> {
                            send(0);
                            close();
                        })
                .dimensions(listLeft, y, LIST_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(listLeft, y, LIST_WIDTH, WIDGET_HEIGHT).build());
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listLeft || mouseX >= listLeft + LIST_WIDTH
                || mouseY < listTop || mouseY >= listTop + visibleRows * ROW_HEIGHT) {
            return -1;
        }
        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        return index >= 0 && index < rows.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            send(rows.get(index).id());
            close();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listHeight = visibleRows * ROW_HEIGHT;
        int max = Math.max(0, rows.size() * ROW_HEIGHT - listHeight);
        if (max > 0 && mouseX >= listLeft && mouseX < listLeft + LIST_WIDTH
                && mouseY >= listTop && mouseY < listTop + listHeight) {
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * ROW_HEIGHT / 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void send(long posterId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(sign.getPos());
        buf.writeLong(posterId);
        ClientPlayNetworking.send(DisruptionNetworking.SET_POSTER_SIGN_C2S, buf);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        rows = ClientPosters.all();
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        int right = listLeft + LIST_WIDTH;
        int listHeight = visibleRows * ROW_HEIGHT;
        int bottom = listTop + listHeight;
        AddonUi.panel(context, listLeft, listTop, right, bottom);

        int hovered = rowAt(mouseX, mouseY);
        if (rows.isEmpty()) {
            List<String> lines = List.of(Text.translatable("gui.station_announcer.posters.sign_none").getString());
            int y = listTop + listHeight / 2 - 4;
            for (String line : lines) {
                for (var wrapped : textRenderer.wrapLines(Text.literal(line), LIST_WIDTH - 12)) {
                    context.drawTextWithShadow(textRenderer, wrapped, listLeft + 6, y, AddonUi.TEXT_FAINT);
                    y += 10;
                }
            }
        } else {
            context.enableScissor(listLeft + 1, listTop + 1, right - 1, bottom - 1);
            long lastDisruption = Long.MIN_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                int rowY = listTop + i * ROW_HEIGHT - scroll;
                ServicePoster poster = rows.get(i);
                if (rowY + ROW_HEIGHT >= listTop && rowY <= bottom) {
                    drawRow(context, poster, rowY, i == hovered, poster.disruptionId() != lastDisruption);
                }
                lastDisruption = poster.disruptionId();
            }
            context.disableScissor();
            AddonUi.scrollIndicator(context, right, listTop, listHeight, rows.size() * ROW_HEIGHT, scroll);
        }

        // Status line: what hangs here now.
        ServicePoster current = ClientPosters.byId(sign.getPosterId());
        boolean detached = current == null && sign.getSnapshot() != null;
        if (current == null) {
            current = sign.getSnapshot();
        }
        String status = current == null
                ? Text.translatable("gui.station_announcer.posters.sign_empty").getString()
                : Text.translatable("gui.station_announcer.posters.sign_current",
                PosterListScreen.stripTokens(current.preview())).getString();
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(status, LIST_WIDTH), listLeft, bottom + 2,
                AddonUi.TEXT_FAINT);
        if (detached) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.posters.sign_detached"), listLeft, bottom + 12, AddonUi.TEXT_FAINT);
        }

        // Preview: the hovered poster, else the current one.
        ServicePoster shown = hovered >= 0 ? rows.get(hovered) : current;
        int previewWidth = Math.round(PosterLayout.WIDTH * previewScale);
        int previewHeight = Math.round(PosterLayout.MAX_HEIGHT * previewScale);
        context.fill(previewLeft - 3, previewTop - 3, previewLeft + previewWidth + 3, previewTop + previewHeight + 3, 0xFF2A2A30);
        if (shown != null) {
            PosterLayout.paint(new PosterLayout.GuiSurface(context, previewLeft, previewTop, previewScale), shown);
        } else {
            context.fill(previewLeft, previewTop, previewLeft + previewWidth, previewTop + previewHeight, 0xFF3A3A40);
        }
    }

    private void drawRow(DrawContext context, ServicePoster poster, int rowY, boolean hovered, boolean firstOfGroup) {
        int right = listLeft + LIST_WIDTH;
        if (hovered) {
            context.fill(listLeft + 1, rowY, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_HOVER);
        }
        if (poster.id() == sign.getPosterId()) {
            context.fill(listLeft + 1, rowY, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_SELECTED);
        }
        context.fill(listLeft + 1, rowY + ROW_HEIGHT - 1, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_DIVIDER);

        ClientDisruptions.Entry disruption = ClientDisruptions.byId(poster.disruptionId());
        int x = listLeft + 8;
        if (disruption != null) {
            x += AddonUi.chip(context, textRenderer,
                    Text.translatable(DisruptionsScreen.severityKey(disruption.severity())).getString(),
                    x, rowY + 2, DisruptionsScreen.severityColor(disruption.severity())) + 4;
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(disruption.message(), right - x - 6),
                    x, rowY + 3, AddonUi.TEXT_DIM);
        } else {
            context.drawTextWithShadow(textRenderer, "#" + poster.disruptionId(), x, rowY + 3, AddonUi.TEXT_FAINT);
        }
        int chipX = listLeft + 8;
        for (String line : poster.headerLines()) {
            var bullet = PosterLayout.lineBullet(line);
            chipX += AddonUi.chip(context, textRenderer, bullet.label(), chipX, rowY + 13, bullet.color()) + 3;
            if (chipX > right - 80) {
                break;
            }
        }
        context.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(PosterListScreen.stripTokens(poster.preview()), right - chipX - 6),
                chipX, rowY + 14, AddonUi.TEXT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
