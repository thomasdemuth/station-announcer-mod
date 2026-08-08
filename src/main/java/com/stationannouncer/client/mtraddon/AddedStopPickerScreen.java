package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the platform a temporary extra stop uses. The shared
 * {@code PlatformPicker} is scoped to one station (or a 16-block radius), which is
 * right for hold rules and platform groups but wrong here — the new stop is by
 * definition at a DIFFERENT station along the line. So this list covers every
 * platform the client knows, ordered by distance from the stop it will follow, and
 * capped so a huge network still renders instantly. The nearest candidates are the
 * plausible ones, which puts the platform you want at the top.
 *
 * <p>The choice is only a proposal: the server refuses it (with an action-bar
 * reason) unless the platform actually lies on the line's existing track path.</p>
 */
@Environment(EnvType.CLIENT)
public class AddedStopPickerScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int GAP = 4;
    private static final int MAX_OPTIONS = 200;

    private static final int PANEL_BG = 0xF0111116;
    private static final int PANEL_BORDER = 0xFF3A3A45;
    private static final int ROW_HOVER = 0x18FFFFFF;
    private static final int ROW_SELECTED = 0x403C7DD9;

    private record Option(long platformId, String label, int distance) {
    }

    private final Route route;
    private final int afterStopIndex;
    private final int durationMinutes;
    private final Screen parent;
    private final List<Option> options = new ArrayList<>();

    private long selected;
    private int scrollOffset;
    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public AddedStopPickerScreen(Route route, int afterStopIndex, long anchorPlatformId,
                                 int durationMinutes, Screen parent) {
        super(Text.translatable("gui.station_announcer.stop_changes.pick_title"));
        this.route = route;
        this.afterStopIndex = afterStopIndex;
        this.durationMinutes = durationMinutes;
        this.parent = parent;

        Platform anchor = RouteStopChangesScreen.findPlatform(anchorPlatformId);
        Position origin = anchor == null ? null : safeMid(anchor);
        for (Platform platform : MinecraftClientData.getDashboardInstance().platforms) {
            if (platform == null || platform.getId() == anchorPlatformId) {
                continue;
            }
            options.add(new Option(platform.getId(), RouteStopChangesScreen.platformLabel(platform),
                    distance(origin, platform)));
        }
        options.sort(Comparator.<Option>comparingInt(Option::distance).thenComparing(Option::label));
        if (options.size() > MAX_OPTIONS) {
            options.subList(MAX_OPTIONS, options.size()).clear();
        }
    }

    private static Position safeMid(Platform platform) {
        try {
            return platform.getMidPosition();
        } catch (Exception e) {
            return null;
        }
    }

    private static int distance(Position origin, Platform platform) {
        Position mid = safeMid(platform);
        if (origin == null || mid == null) {
            return Integer.MAX_VALUE;
        }
        double dx = mid.getX() - origin.getX();
        double dy = mid.getY() - origin.getY();
        double dz = mid.getZ() - origin.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;
        int bottomBlock = 12 + WIDGET_HEIGHT + GAP * 2;
        int available = height - 44 - bottomBlock;
        visibleRows = Math.max(1, Math.min(Math.max(options.size(), 1), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, options.size() - visibleRows)));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;
        hintY = listTop + visibleRows * ROW_HEIGHT + 2;

        int half = (PANEL_WIDTH - GAP) / 2;
        ButtonWidget done = ButtonWidget.builder(ScreenTexts.DONE, button -> {
                    if (selected != 0) {
                        RouteStopChangesScreen.sendStopChange(route.getId(), afterStopIndex, 1, false,
                                selected, durationMinutes);
                    }
                    close();
                })
                .dimensions(left, hintY + 12, half, WIDGET_HEIGHT).build();
        done.active = selected != 0;
        addDrawableChild(done);
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, hintY + 12, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            int index = scrollOffset + (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < options.size()) {
                long id = options.get(index).platformId();
                selected = selected == id ? 0 : id;
                clearAndInit();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (options.size() > visibleRows && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            scrollOffset = Math.max(0, Math.min(options.size() - visibleRows,
                    scrollOffset - (int) Math.signum(verticalAmount)));
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        int listHeight = visibleRows * ROW_HEIGHT;
        context.fill(listLeft - 1, listTop - 1, listLeft + PANEL_WIDTH + 1, listTop + listHeight + 1, PANEL_BORDER);
        context.fill(listLeft, listTop, listLeft + PANEL_WIDTH, listTop + listHeight, PANEL_BG);

        if (options.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.stop_changes.no_platforms"),
                    listLeft + 4, listTop + 6, DisruptionsScreen.TEXT_DIM);
        } else {
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < options.size(); i++) {
                Option option = options.get(i);
                int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
                boolean isSelected = option.platformId() == selected;
                boolean hovered = mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (isSelected) {
                    context.fill(listLeft, rowY, listLeft + PANEL_WIDTH, rowY + ROW_HEIGHT, ROW_SELECTED);
                } else if (hovered) {
                    context.fill(listLeft, rowY, listLeft + PANEL_WIDTH, rowY + ROW_HEIGHT, ROW_HOVER);
                }
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(option.label(), PANEL_WIDTH - 70),
                        listLeft + 6, rowY + 6, 0xFFF0F0F2);
                if (option.distance() != Integer.MAX_VALUE) {
                    context.drawTextWithShadow(textRenderer,
                            Text.translatable("gui.station_announcer.stop_changes.distance", option.distance()),
                            listLeft + PANEL_WIDTH - 62, rowY + 6, DisruptionsScreen.TEXT_DIM);
                }
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.stop_changes.hint_pick"),
                listLeft, hintY, DisruptionsScreen.TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
