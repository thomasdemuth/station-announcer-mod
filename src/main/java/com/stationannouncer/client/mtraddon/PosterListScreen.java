package com.stationannouncer.client.mtraddon;

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
 * The service change posters linked to one disruption: one row per poster
 * (title bar / timing / headline preview / line bullets) with inline Edit,
 * Copy and delete, plus New poster, which starts from a template filled in
 * from the disruption itself. Opened from the Posters action on a disruption
 * row; posters are also what a placed frame block picks from.
 */
@Environment(EnvType.CLIENT)
public class PosterListScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 30;
    private static final int GAP = 4;

    private static final int EDIT_WIDTH = 32;
    private static final int COPY_WIDTH = 32;
    private static final int DELETE_WIDTH = 14;
    private static final int ACTIONS_WIDTH = EDIT_WIDTH + COPY_WIDTH + DELETE_WIDTH + 8;

    private final Screen parent;
    private final long disruptionId;
    private List<ServicePoster> rows = List.of();
    private int scroll;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int summaryY;

    public PosterListScreen(long disruptionId, Screen parent) {
        super(Text.translatable("gui.station_announcer.posters.title"));
        this.disruptionId = disruptionId;
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = ClientPosters.forDisruption(disruptionId);
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        int bottomBlock = 12 + (WIDGET_HEIGHT + GAP) * 2;
        int available = height - 40 - bottomBlock;
        visibleRows = Math.max(2, Math.min(Math.max(rows.size(), 2), available / ROW_HEIGHT));
        int listHeight = visibleRows * ROW_HEIGHT;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW_HEIGHT - listHeight)));

        int content = listHeight + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;
        y += listHeight + 2;
        summaryY = y;
        y += 12;

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.posters.new"),
                        button -> {
                            ClientDisruptions.Entry disruption = ClientDisruptions.byId(disruptionId);
                            if (client != null && disruption != null) {
                                client.setScreen(new PosterEditScreen(PosterLayout.template(disruption), this));
                            }
                        })
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    // ------------------------------------------------------------ behaviour

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listLeft || mouseX >= listLeft + PANEL_WIDTH
                || mouseY < listTop || mouseY >= listTop + visibleRows * ROW_HEIGHT) {
            return -1;
        }
        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        return index >= 0 && index < rows.size() ? index : -1;
    }

    private int actionsLeft() {
        return listLeft + PANEL_WIDTH - ACTIONS_WIDTH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            ServicePoster poster = rows.get(index);
            int x = actionsLeft();
            if (mouseX >= x && mouseX < x + EDIT_WIDTH) {
                edit(poster);
                return true;
            }
            x += EDIT_WIDTH + 4;
            if (mouseX >= x && mouseX < x + COPY_WIDTH) {
                // A copy is a new poster (id 0) with the same content, opened for editing.
                ServicePoster.Builder draft = new ServicePoster.Builder(poster);
                draft.id = 0;
                if (client != null) {
                    client.setScreen(new PosterEditScreen(draft, this));
                }
                return true;
            }
            x += COPY_WIDTH + 4;
            if (mouseX >= x && mouseX < x + DELETE_WIDTH) {
                sendDelete(poster.id());
                return true;
            }
            edit(poster);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void edit(ServicePoster poster) {
        if (client != null) {
            client.setScreen(new PosterEditScreen(new ServicePoster.Builder(poster), this));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listHeight = visibleRows * ROW_HEIGHT;
        int max = Math.max(0, rows.size() * ROW_HEIGHT - listHeight);
        if (max > 0 && mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                && mouseY >= listTop && mouseY < listTop + listHeight) {
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * ROW_HEIGHT / 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // -------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        rows = ClientPosters.forDisruption(disruptionId);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        int right = listLeft + PANEL_WIDTH;
        int listHeight = visibleRows * ROW_HEIGHT;
        int bottom = listTop + listHeight;
        AddonUi.panel(context, listLeft, listTop, right, bottom);

        if (rows.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.posters.none"),
                    listLeft + PANEL_WIDTH / 2, listTop + listHeight / 2 - 4, AddonUi.TEXT_FAINT);
        } else {
            int hovered = rowAt(mouseX, mouseY);
            context.enableScissor(listLeft + 1, listTop + 1, right - 1, bottom - 1);
            for (int i = 0; i < rows.size(); i++) {
                int rowY = listTop + i * ROW_HEIGHT - scroll;
                if (rowY + ROW_HEIGHT < listTop || rowY > bottom) {
                    continue;
                }
                drawRow(context, rows.get(i), rowY, i == hovered, mouseX, mouseY);
            }
            context.disableScissor();
            AddonUi.scrollIndicator(context, right, listTop, listHeight, rows.size() * ROW_HEIGHT, scroll);
        }

        ClientDisruptions.Entry disruption = ClientDisruptions.byId(disruptionId);
        String summary = Text.translatable("gui.station_announcer.posters.summary", rows.size()).getString();
        if (disruption != null) {
            summary += " — " + textRenderer.trimToWidth(disruption.message(),
                    PANEL_WIDTH - textRenderer.getWidth(summary) - 8);
        }
        context.drawTextWithShadow(textRenderer, summary, listLeft, summaryY, AddonUi.TEXT_FAINT);
    }

    private void drawRow(DrawContext context, ServicePoster poster, int rowY, boolean hovered, int mouseX, int mouseY) {
        int right = listLeft + PANEL_WIDTH;
        if (hovered) {
            context.fill(listLeft + 1, rowY, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_HOVER);
        }
        context.fill(listLeft + 1, rowY + ROW_HEIGHT - 1, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_DIVIDER);
        context.fill(listLeft + 1, rowY, listLeft + 4, rowY + ROW_HEIGHT - 1, 0xFF3A3A45);

        int textLeft = listLeft + 8;
        int textRight = actionsLeft() - 6;

        // Line 1: title bar chip, timing, then the headline preview.
        int x = textLeft;
        x += AddonUi.chip(context, textRenderer, poster.kind(), x, rowY + 3, 0xFF202024) + 5;
        if (!poster.timing().isEmpty()) {
            String timing = poster.timing();
            context.drawTextWithShadow(textRenderer, timing, x, rowY + 4, AddonUi.TEXT_DIM);
            x += textRenderer.getWidth(timing) + 6;
        }
        context.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(stripTokens(poster.preview()), Math.max(10, textRight - x)),
                x, rowY + 4, AddonUi.TEXT);

        // Line 2: the header bullets as chips.
        int chipX = textLeft;
        for (String line : poster.headerLines()) {
            var bullet = PosterLayout.lineBullet(line);
            int chipWidth = textRenderer.getWidth(bullet.label()) + 8;
            if (chipX + chipWidth > textRight) {
                break;
            }
            chipX += AddonUi.chip(context, textRenderer, bullet.label(), chipX, rowY + 16, bullet.color()) + 3;
        }
        if (poster.headerLines().isEmpty()) {
            context.drawTextWithShadow(textRenderer, poster.category(), chipX, rowY + 18, AddonUi.TEXT_FAINT);
        }

        int actionX = actionsLeft();
        int actionY = rowY + (ROW_HEIGHT - 14) / 2;
        AddonUi.inlineButton(context, textRenderer,
                Text.translatable("gui.station_announcer.posters.edit").getString(),
                actionX, actionY, EDIT_WIDTH, 14, mouseX, mouseY, AddonUi.TEXT);
        actionX += EDIT_WIDTH + 4;
        AddonUi.inlineButton(context, textRenderer,
                Text.translatable("gui.station_announcer.posters.duplicate").getString(),
                actionX, actionY, COPY_WIDTH, 14, mouseX, mouseY, AddonUi.TEXT);
        actionX += COPY_WIDTH + 4;
        AddonUi.inlineButton(context, textRenderer, "x",
                actionX, actionY, DELETE_WIDTH, 14, mouseX, mouseY, AddonUi.DANGER);
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
