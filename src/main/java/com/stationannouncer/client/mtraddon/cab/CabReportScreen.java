package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.mtraddon.FlatUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** The end-of-run report card ({@link CabScore}). FlatUi, one Close button, Esc closes. */
@Environment(EnvType.CLIENT)
public final class CabReportScreen extends Screen {
    private final CabScore.Run run;
    private int scroll;
    private int closeX;
    private int closeY;

    CabReportScreen(CabScore.Run run) {
        super(Text.translatable("gui.station_announcer.cab.report"));
        this.run = run;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fill(0, 0, width, height, 0x88000000);
        int w = Math.min(300, width - 16);
        int h = Math.min(220, height - 16);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        FlatUi.pane(c, x, y, w, h);
        FlatUi.heading(c, textRenderer, tr("gui.station_announcer.cab.report"), x + 8, y + 8);
        c.drawText(textRenderer, run.label(), x + 8, y + 20, FlatUi.TEXT_DIM, false);

        // Big score, with a rating word under it.
        String score = Integer.toString(run.score());
        int scoreColor = run.score() >= 50 ? GREEN : run.score() >= 0 ? AMBER : RED;
        c.getMatrices().push();
        c.getMatrices().translate(x + w - 12 - textRenderer.getWidth(score) * 3, y + 8, 0);
        c.getMatrices().scale(3, 3, 1);
        c.drawTextWithShadow(textRenderer, score, 0, 0, scoreColor);
        c.getMatrices().pop();
        String rating = tr(run.score() >= 80 ? "gui.station_announcer.cab.report_rating_great"
                : run.score() >= 30 ? "gui.station_announcer.cab.report_rating_good"
                : run.score() >= 0 ? "gui.station_announcer.cab.report_rating_ok"
                : "gui.station_announcer.cab.report_rating_rough");
        c.drawText(textRenderer, rating, x + w - 12 - textRenderer.getWidth(rating), y + 34, scoreColor, false);

        // Summary: each line coloured by how it went.
        int sy = y + 38;
        boolean mostlyOnTime = run.stops() > 0 && run.onTime() * 2 >= run.stops();
        stat(c, x, sy, Text.translatable("gui.station_announcer.cab.report_on_time", run.onTime(), run.stops()).getString(),
                run.stops() == 0 ? FlatUi.TEXT_DIM : mostlyOnTime ? GREEN : AMBER);
        stat(c, x, sy + 11, Text.translatable("gui.station_announcer.cab.report_worst",
                CabHud.deviation(Math.max(0, run.worstLateMillis()))).getString(), CabHud.deviationColor(Math.max(0, run.worstLateMillis())));
        stat(c, x, sy + 22, Text.translatable("gui.station_announcer.cab.report_stops", run.perfectStops(), run.overruns()).getString(),
                run.overruns() > 0 ? AMBER : run.perfectStops() > 0 ? GREEN : FlatUi.TEXT);
        stat(c, x, sy + 33, Text.translatable("gui.station_announcer.cab.report_eb_left", run.emergencyBrakes(), run.leftBehind()).getString(),
                run.emergencyBrakes() + run.leftBehind() > 0 ? RED : GREEN);
        sy += 44;
        FlatUi.rect(c, x + 8, sy + 2, w - 16, 1, FlatUi.BORDER);

        // Lines: the event on the left, its points right-aligned in green or red, alternate rows shaded.
        int listY = sy + 6;
        int listH = y + h - 28 - listY;
        int visible = Math.max(1, listH / 10);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, run.lines().size() - visible)));
        for (int i = scroll; i < run.lines().size() && i < scroll + visible; i++) {
            int ly = listY + (i - scroll) * 10;
            if (i % 2 == 0) {
                FlatUi.rect(c, x + 6, ly - 1, w - 12, 10, 0x0AFFFFFF);
            }
            String line = run.lines().get(i);
            int split = line.lastIndexOf("  ");
            String what = split > 0 ? line.substring(0, split) : line;
            String points = split > 0 ? line.substring(split + 2).trim() : "";
            boolean gain = points.startsWith("+");
            boolean loss = points.startsWith("−") || points.startsWith("-");
            c.drawText(textRenderer, what, x + 10, ly, loss ? 0xFFE0B0B0 : gain ? 0xFFB8E6C4 : FlatUi.TEXT_DIM, false);
            if (!points.isEmpty()) {
                c.drawText(textRenderer, points, x + w - 12 - textRenderer.getWidth(points), ly, gain ? GREEN : loss ? RED : FlatUi.TEXT_DIM, false);
            }
        }
        if (run.lines().size() > visible) {
            String more = (scroll + visible < run.lines().size()) ? "▼" : "▲";
            c.drawText(textRenderer, more, x + w / 2, y + h - 26, FlatUi.TEXT_FAINT, false);
        }

        closeX = x + w - 66;
        closeY = y + h - 22;
        FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.close"), closeX, closeY, 58, 16, mouseX, mouseY,
                FlatUi.ButtonStyle.PRIMARY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && FlatUi.inside(mx, my, closeX, closeY, 58, 16)) {
            close();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (int) Math.signum(vertical);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static final int GREEN = 0xFF6CE38F;
    private static final int AMBER = 0xFFFFC04D;
    private static final int RED = 0xFFFF5B4A;

    /** A summary line with a coloured lamp in front. */
    private void stat(DrawContext c, int x, int y, String text, int color) {
        FlatUi.rect(c, x + 8, y + 1, 5, 5, color);
        c.drawText(textRenderer, text, x + 17, y, FlatUi.TEXT, false);
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }
}
