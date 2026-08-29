package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.mtraddon.nav.NavSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * Small hub behind the addon's "Tools…" dashboard button, holding the two line/depot
 * tools that have no natural home on an MTR screen:
 *
 * <ul>
 *   <li><b>Duplicate line…</b> — clone a route so the copy can be pointed at another
 *       platform ({@link DuplicateLineScreen});</li>
 *   <li><b>Depot groups…</b> — group depots so they stagger their departures instead of
 *       dispatching together ({@link DepotGroupsScreen});</li>
 *   <li><b>Journey directions…</b> — the per-player settings for the in-game navigation
 *       card, world marker and alerts ({@link NavSettingsScreen}).</li>
 * </ul>
 *
 * <p>One button rather than two keeps MTR's dashboard layout intact: the addon's
 * "Dispatch" and "Disruptions" buttons already split the two map rows, and this one
 * splits the Options button beside them.</p>
 */
@Environment(EnvType.CLIENT)
public class DispatchToolsScreen extends Screen {
    private static final int PANEL_WIDTH = 260;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;

    private final Screen parent;
    private int titleY;
    private int hintY;

    public DispatchToolsScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.tools.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int content = WIDGET_HEIGHT * 4 + GAP * 3 + 26;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.duplicate_line.button"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new DuplicateLineScreen(this));
                            }
                        })
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.depot_groups.button"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new DepotGroupsScreen(this));
                            }
                        })
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.nav.button"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new NavSettingsScreen(this));
                            }
                        })
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        hintY = y;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, y + 26, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        int left = (width - PANEL_WIDTH) / 2;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.depot_groups.count", ClientDepotGroups.count()),
                left, hintY + 4, DisruptionsScreen.TEXT_DIM);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
