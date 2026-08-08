package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The platform chooser shared by every PIDS settings screen: a scrolling list
 * of the platforms a display could watch, each with a checkbox and the routes
 * that actually call there.
 *
 * <p>Drawn by hand rather than built out of vanilla widgets. Choosing a
 * platform means knowing what stops at it, so each row carries its routes as
 * coloured chips, and the list is ordered by distance from the display — the
 * track you are standing over is the one you almost always mean, so it is
 * always the first row.</p>
 *
 * <p>Every platform of the station the display stands in is offered, not just
 * the ones within arm's reach: a concourse board is nowhere near the tracks it
 * announces. Only a display outside any station area falls back to sweeping a
 * radius.</p>
 */
@Environment(EnvType.CLIENT)
public class PlatformPicker {
    private static final int SEARCH_RADIUS = 16;

    /** Two text lines per row: the platform, then the routes calling there. */
    public static final int ROW_HEIGHT = 26;
    public static final int MAX_VISIBLE_ROWS = 4;
    public static final int MIN_VISIBLE_ROWS = 2;

    private static final int CHECKBOX = 11;
    private static final int SCROLLBAR_WIDTH = 3;

    private static final int PANEL_BG = 0xF0111116;
    private static final int PANEL_BORDER = 0xFF3A3A45;
    private static final int ROW_HOVER = 0x18FFFFFF;
    private static final int ROW_SELECTED = 0x403C7DD9;
    private static final int TEXT = 0xFFF0F0F2;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;
    private static final int CHECK_BORDER = 0xFF8A8A95;
    private static final int CHECK_FILL = 0xFF3C7DD9;

    /** A route calling at a platform. */
    private record RouteChip(String label, int color) {
    }

    /** One selectable platform. */
    private record Option(long id, String label, int distance, List<RouteChip> routes) {
    }

    private final List<Option> platforms = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final int maxSelected;
    private final int width;

    private int left;
    private int top;
    private int scroll;
    /** How tall the list is drawn; screens shrink it when they would overflow. */
    private int visibleRows = MAX_VISIBLE_ROWS;

    /**
     * @param origin       the display, for distance ordering and station lookup
     * @param preselected  platform ids already configured
     * @param maxSelected  cap on how many may be ticked
     */
    public PlatformPicker(BlockPos origin, Iterable<Long> preselected, int maxSelected, int width) {
        this.maxSelected = maxSelected;
        this.width = width;
        for (long id : preselected) {
            selected.add(id);
        }
        platforms.addAll(findPlatforms(origin));
    }

    /** Call from the screen's init() once the layout position is known. */
    public void setPosition(int left, int top) {
        this.left = left;
        this.top = top;
    }

    /**
     * Fits the list into the space a screen has left. The list scrolls, so
     * losing a row costs nothing — whereas letting the screen overrun a short
     * window pushes Done and Cancel off the bottom where they cannot be reached.
     */
    public void fitTo(int availableHeight) {
        visibleRows = Math.max(MIN_VISIBLE_ROWS,
                Math.min(MAX_VISIBLE_ROWS, availableHeight / ROW_HEIGHT));
        scroll = Math.min(scroll, maxScroll());
    }

    public int getHeight() {
        return ROW_HEIGHT * visibleRows;
    }

    public Set<Long> getSelected() {
        return selected;
    }

    public boolean isEmpty() {
        return platforms.isEmpty();
    }

    /** The row label for a platform id, or "" when that platform is not listed. */
    public String labelFor(long id) {
        for (Option option : platforms) {
            if (option.id() == id) {
                return option.label();
            }
        }
        return "";
    }

    /** The single ticked platform, or 0 — for the one-of pickers. */
    public long getSingleSelected() {
        for (long id : selected) {
            return id;
        }
        return 0;
    }

    // ------------------------------------------------------------ MTR data

    private static List<Option> findPlatforms(BlockPos here) {
        org.mtr.mapping.holder.BlockPos pos = new org.mtr.mapping.holder.BlockPos(here);
        List<Platform> found = new ArrayList<>();
        boolean withinStation = false;
        try {
            Station station = org.mtr.mod.InitClient.findStation(pos);
            if (station != null) {
                found.addAll(station.savedRails);
                withinStation = true;
            }
        } catch (Exception ignored) {
            // MTR data mid-sync — the radius sweep below still works
        }
        if (found.isEmpty()) {
            withinStation = false;
            org.mtr.mod.InitClient.findClosePlatform(pos, SEARCH_RADIUS, found::add);
        }

        Map<Long, List<RouteChip>> routes = routesByPlatform();
        List<Option> options = new ArrayList<>(found.size());
        for (Platform platform : found) {
            options.add(new Option(platform.getId(), label(platform, !withinStation),
                    distanceTo(here, platform), routes.getOrDefault(platform.getId(), List.of())));
        }
        options.sort(Comparator.comparingInt(Option::distance));
        return options;
    }

    private static int distanceTo(BlockPos here, Platform platform) {
        try {
            Position mid = platform.getMidPosition();
            double dx = mid.getX() - here.getX();
            double dy = mid.getY() - here.getY();
            double dz = mid.getZ() - here.getZ();
            return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        } catch (Exception exception) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Which routes call at each platform. Built from {@code simplifiedRoutes} —
     * clients never receive the full route graph, and every SimplifiedRoute
     * already lists the platforms it stops at, so this is the one reliable
     * client-side source.
     */
    private static Map<Long, List<RouteChip>> routesByPlatform() {
        Map<Long, List<RouteChip>> map = new HashMap<>();
        try {
            for (SimplifiedRoute route : org.mtr.mod.client.MinecraftClientData.getInstance().simplifiedRoutes) {
                RouteChip chip = new RouteChip(RailroadRouteData.firstLang(route.getName()),
                        0xFF000000 | route.getColor());
                if (chip.label().isEmpty()) {
                    continue;
                }
                for (SimplifiedRoutePlatform stop : route.getPlatforms()) {
                    List<RouteChip> chips = map.computeIfAbsent(stop.getPlatformId(), key -> new ArrayList<>());
                    // A route calls at a platform once for our purposes, however
                    // many times it appears in the stop list.
                    if (chips.stream().noneMatch(existing -> existing.label().equals(chip.label()))) {
                        chips.add(chip);
                    }
                }
            }
        } catch (Exception ignored) {
            // MTR data mid-sync — rows just render without their route chips
        }
        return map;
    }

    /** "Platform 1", prefixed with the station when the list spans several. */
    private static String label(Platform platform, boolean includeStation) {
        String platformName = RailroadRouteData.firstLang(platform.getName());
        String shortName = platformName.isEmpty() ? "?" : platformName;
        String stationName = platform.area == null ? "" : RailroadRouteData.firstLang(platform.area.getName());
        return includeStation && !stationName.isEmpty()
                ? stationName + " — " + shortName
                : "Platform " + shortName;
    }

    // ------------------------------------------------------------ behaviour

    private int maxScroll() {
        return Math.max(0, platforms.size() * ROW_HEIGHT - getHeight());
    }

    private boolean inside(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + getHeight();
    }

    /** The row under the cursor, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (!inside(mouseX, mouseY)) {
            return -1;
        }
        int index = (int) ((mouseY - top + scroll) / ROW_HEIGHT);
        return index >= 0 && index < platforms.size() ? index : -1;
    }

    /** Returns true when the click was consumed by the list. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        long id = platforms.get(row).id();
        if (selected.remove(id)) {
            return true; // ticking the chosen row again clears it (back to automatic)
        }
        if (maxSelected == 1) {
            // One-of pickers (the holding lights): picking a row REPLACES the
            // choice, rather than doing nothing until the old one is unticked.
            selected.clear();
            selected.add(id);
        } else if (selected.size() < maxSelected) {
            selected.add(id);
        }
        return true;
    }

    /** Returns true when the scroll was consumed by the list. */
    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (!inside(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (verticalAmount * ROW_HEIGHT / 2)));
        return true;
    }

    // -------------------------------------------------------------- drawing

    public void render(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
        int right = left + width;
        int bottom = top + getHeight();
        context.fill(left, top, right, bottom, PANEL_BG);
        context.fill(left, top, right, top + 1, PANEL_BORDER);
        context.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
        context.fill(left, top, left + 1, bottom, PANEL_BORDER);
        context.fill(right - 1, top, right, bottom, PANEL_BORDER);

        if (platforms.isEmpty()) {
            context.drawCenteredTextWithShadow(font,
                    Text.translatable("gui.station_announcer.railroad_pids.no_platforms"),
                    left + width / 2, top + getHeight() / 2 - 4, TEXT_FAINT);
            return;
        }

        int hovered = rowAt(mouseX, mouseY);
        context.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        for (int i = 0; i < platforms.size(); i++) {
            int rowY = top + i * ROW_HEIGHT - scroll;
            if (rowY + ROW_HEIGHT < top || rowY > bottom) {
                continue;
            }
            drawRow(context, font, platforms.get(i), rowY, i == hovered);
        }
        context.disableScissor();

        int max = maxScroll();
        if (max > 0) {
            // A thin unobtrusive indicator rather than a vanilla scroll bar.
            int trackHeight = getHeight() - 4;
            int thumb = Math.max(16, trackHeight * getHeight() / (platforms.size() * ROW_HEIGHT));
            int thumbY = top + 2 + (trackHeight - thumb) * scroll / max;
            context.fill(right - 1 - SCROLLBAR_WIDTH, thumbY, right - 1, thumbY + thumb, 0x60FFFFFF);
        }
    }

    private void drawRow(DrawContext context, TextRenderer font, Option option, int rowY, boolean hovered) {
        int right = left + width;
        boolean checked = selected.contains(option.id());
        if (checked) {
            context.fill(left + 1, rowY, right - 1, rowY + ROW_HEIGHT, ROW_SELECTED);
        }
        if (hovered) {
            context.fill(left + 1, rowY, right - 1, rowY + ROW_HEIGHT, ROW_HOVER);
        }

        int boxX = left + 7;
        int boxY = rowY + 4;
        context.fill(boxX, boxY, boxX + CHECKBOX, boxY + CHECKBOX, checked ? CHECK_FILL : 0x40000000);
        context.fill(boxX, boxY, boxX + CHECKBOX, boxY + 1, CHECK_BORDER);
        context.fill(boxX, boxY + CHECKBOX - 1, boxX + CHECKBOX, boxY + CHECKBOX, CHECK_BORDER);
        context.fill(boxX, boxY, boxX + 1, boxY + CHECKBOX, CHECK_BORDER);
        context.fill(boxX + CHECKBOX - 1, boxY, boxX + CHECKBOX, boxY + CHECKBOX, CHECK_BORDER);
        if (checked) {
            context.drawTextWithShadow(font, "✔", boxX + 2, boxY + 2, 0xFFFFFFFF);
        }

        int textX = boxX + CHECKBOX + 7;
        String distance = option.distance() == Integer.MAX_VALUE ? "" : option.distance() + "m";
        int distanceWidth = font.getWidth(distance);
        context.drawTextWithShadow(font,
                font.trimToWidth(option.label(), right - 8 - distanceWidth - textX), textX, rowY + 4, TEXT);
        context.drawTextWithShadow(font, distance, right - 7 - distanceWidth, rowY + 4, TEXT_DIM);

        // Second line: what actually calls here, so the choice is informed.
        if (option.routes().isEmpty()) {
            context.drawTextWithShadow(font,
                    Text.translatable("gui.station_announcer.railroad_pids.no_routes"),
                    textX, rowY + 15, TEXT_FAINT);
            return;
        }
        int chipX = textX;
        int limit = right - 8;
        for (int i = 0; i < option.routes().size(); i++) {
            RouteChip chip = option.routes().get(i);
            int chipWidth = font.getWidth(chip.label()) + 8;
            if (chipX + chipWidth > limit) {
                context.drawTextWithShadow(font, "+" + (option.routes().size() - i), chipX, rowY + 15, TEXT_FAINT);
                break;
            }
            context.fill(chipX, rowY + 13, chipX + chipWidth, rowY + 24, chip.color());
            context.drawText(font, chip.label(), chipX + 4, rowY + 15, readableOn(chip.color()), false);
            chipX += chipWidth + 3;
        }
    }

    /** Black or white, whichever reads on the given chip colour. */
    private static int readableOn(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? 0xFF101014 : 0xFFFFFFFF;
    }

    /** The caption to show under the list: what the current selection means. */
    public Text hint() {
        return getSelected().isEmpty()
                ? Text.translatable("gui.station_announcer.railroad_pids.auto_hint")
                : Text.translatable("gui.station_announcer.railroad_pids.selected", getSelected().size());
    }
}
