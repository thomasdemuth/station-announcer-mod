package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.mtr.ExitMarkerBlockEntity;
import com.stationannouncer.mtr.Wayfinding;
import com.stationannouncer.wayfinding.ExitPin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.Station;
import org.mtr.core.data.StationExit;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Exit Marker's editor (FlatUi). Left: the station's exits — MTR's own
 * data, the same list MTR's dashboard edits — with add/delete. Right: the
 * selected exit's name and street destinations, and the button that pins
 * THIS marker to it. Save writes the exits back to MTR (one
 * {@code PacketUpdateData}, exactly what MTR's station screen sends) and the
 * pin to our store; renames and deletions carry every other marker's pin along.
 *
 * <p>Players without MTR's dashboard permission see the exits read-only and
 * can still pin the marker.</p>
 */
public class ExitMarkerScreen extends Screen {
    private static final int MAX_W = 430;
    private static final int MAX_H = 262;
    private static final int LIST_W = 128;
    private static final int ROW_H = 20;
    private static final int DEST_ROW = 18;
    private static final int MAX_DESTINATIONS = 12;
    private static final int STATION_SEARCH_RADIUS = 512;

    /** One exit being edited; {@code original} is its name in MTR (null = added here). */
    private static final class Row {
        @Nullable
        final String original;
        String name;
        final List<String> destinations = new ArrayList<>();

        Row(@Nullable String original, String name) {
            this.original = original;
            this.name = name;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final ExitMarkerBlockEntity marker;
    private final boolean canEdit;

    @Nullable
    private Station station;
    private final List<Row> rows = new ArrayList<>();
    /** Snapshot of the station's exits as loaded, to know whether MTR needs an update. */
    private final List<String> loadedSignature = new ArrayList<>();
    private int selected = -1;
    @Nullable
    private Row pinRow;

    private FlatUi.TextBox nameBox;
    private final List<FlatUi.TextBox> destinationBoxes = new ArrayList<>();
    private int destinationScroll;
    private int listScroll;
    private boolean choosingStation;
    /** Exits (false) or the station's scanned walking layout (true). */
    private boolean layoutTab;
    private int layoutScroll;
    private int layoutContentHeight;
    private int layoutViewTop;
    private int layoutViewHeight;
    private String status = "";
    private int statusColor = FlatUi.TEXT_FAINT;

    private final List<Hit> hits = new ArrayList<>();
    private int left;
    private int top;
    private int w;
    private int h;
    private int destTop;
    private int destVisible;
    private int listTop;
    private int listVisible;

    public ExitMarkerScreen(ExitMarkerBlockEntity marker) {
        super(Text.translatable("gui.station_announcer.exit_marker.title"));
        this.marker = marker;
        boolean permission;
        try {
            permission = MinecraftClientData.hasPermission();
        } catch (Throwable t) {
            permission = true;
        }
        this.canEdit = permission;
    }

    @Override
    protected void init() {
        if (nameBox == null) {
            nameBox = new FlatUi.TextBox(textRenderer, 5, false);
            nameBox.placeholder = "A1";
            nameBox.onChange(text -> {
                String clean = sanitizeExitName(text);
                if (!clean.equals(text)) {
                    nameBox.load(clean);
                }
                Row row = selectedRow();
                if (row != null) {
                    row.name = clean;
                }
            });
            loadStation(initialStation());
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------ data

    @Nullable
    private Station initialStation() {
        Station byId = stationById(marker.getStationId());
        if (byId != null) {
            return byId;
        }
        try {
            Station inside = InitClient.findStation(new org.mtr.mapping.holder.BlockPos(marker.getPos()));
            if (inside != null) {
                return inside;
            }
        } catch (Throwable ignored) {
            // fall through to the nearest one
        }
        List<Station> nearby = nearbyStations();
        return nearby.isEmpty() ? null : nearby.get(0);
    }

    @Nullable
    private static Station stationById(long id) {
        if (id == 0) {
            return null;
        }
        try {
            Station station = MinecraftClientData.getInstance().stationIdMap.get(id);
            return station != null ? station : MinecraftClientData.getDashboardInstance().stationIdMap.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Stations near the marker, nearest (by distance to their area) first. */
    private List<Station> nearbyStations() {
        List<Station> out = new ArrayList<>();
        Map<Long, Double> distance = new LinkedHashMap<>();
        BlockPos pos = marker.getPos();
        try {
            for (Station station : MinecraftClientData.getInstance().stations) {
                if (station == null) {
                    continue;
                }
                double dx = Math.max(0, Math.max(station.getMinX() - pos.getX(), pos.getX() - station.getMaxX()));
                double dz = Math.max(0, Math.max(station.getMinZ() - pos.getZ(), pos.getZ() - station.getMaxZ()));
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= STATION_SEARCH_RADIUS) {
                    distance.put(station.getId(), d);
                    out.add(station);
                }
            }
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Exit marker: could not list stations", t);
        }
        out.sort((a, b) -> Double.compare(distance.get(a.getId()), distance.get(b.getId())));
        return out;
    }

    private void loadStation(@Nullable Station next) {
        station = next;
        rows.clear();
        loadedSignature.clear();
        pinRow = null;
        selected = -1;
        listScroll = 0;
        if (station != null) {
            for (StationExit exit : station.getExits()) {
                Row row = new Row(exit.getName(), exit.getName());
                row.destinations.addAll(exit.getDestinations());
                rows.add(row);
                loadedSignature.add(signature(row));
            }
            rows.sort((a, b) -> a.name.compareTo(b.name));
            if (station.getId() == marker.getStationId()) {
                for (Row row : rows) {
                    if (row.name.equals(marker.getExitName())) {
                        pinRow = row;
                    }
                }
            }
        }
        select(pinRow != null ? rows.indexOf(pinRow) : rows.isEmpty() ? -1 : 0);
        layoutScroll = 0;
        if (station != null) {
            try {
                ClientLayouts.request(com.stationannouncer.wayfinding.layout.LayoutScanner.ACTION_STATUS, station.getId());
            } catch (Throwable ignored) {
                // not connected (dev hooks): the tab just says "not scanned"
            }
        }
    }

    private static String signature(Row row) {
        return row.name + "\u0000" + String.join("\u0001", row.destinations);
    }

    @Nullable
    private Row selectedRow() {
        return selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
    }

    private void select(int index) {
        selected = index;
        destinationScroll = 0;
        Row row = selectedRow();
        nameBox.load(row == null ? "" : row.name);
        nameBox.setFocused(false);
        rebuildDestinationBoxes();
    }

    private void rebuildDestinationBoxes() {
        destinationBoxes.clear();
        Row row = selectedRow();
        if (row == null) {
            return;
        }
        for (int i = 0; i < row.destinations.size(); i++) {
            int index = i;
            FlatUi.TextBox box = new FlatUi.TextBox(textRenderer, 64, false);
            box.placeholder = Text.translatable("gui.station_announcer.exit_marker.destination_hint").getString();
            box.load(row.destinations.get(i));
            box.onChange(text -> {
                if (index < row.destinations.size()) {
                    row.destinations.set(index, text);
                }
            });
            destinationBoxes.add(box);
        }
    }

    private static String sanitizeExitName(String text) {
        StringBuilder out = new StringBuilder();
        for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9') {
                out.append(ch);
            }
        }
        return out.length() > 5 ? out.substring(0, 5) : out.toString();
    }

    /** Null when every exit is valid and unique, else what is wrong (for the status line). */
    @Nullable
    private String validationError() {
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            if (!ExitPin.validExitName(row.name)) {
                return Text.translatable("gui.station_announcer.exit_marker.bad_name",
                        row.name.isEmpty() ? "?" : row.name).getString();
            }
            if (!seen.add(row.name)) {
                return Text.translatable("gui.station_announcer.exit_marker.duplicate", row.name).getString();
            }
        }
        return null;
    }

    /** The next unused letter, for "+ Add exit". */
    private String suggestName() {
        Set<String> used = new HashSet<>();
        rows.forEach(row -> used.add(row.name));
        for (char c = 'A'; c <= 'Z'; c++) {
            if (!used.contains(String.valueOf(c))) {
                return String.valueOf(c);
            }
        }
        for (int n = 1; n < 1000; n++) {
            if (!used.contains("A" + n)) {
                return "A" + n;
            }
        }
        return "";
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        c.fill(0, 0, width, height, 0xB0000000);
        w = Math.min(MAX_W, width - 12);
        h = Math.min(MAX_H, height - 12);
        left = (width - w) / 2;
        top = (height - h) / 2;
        FlatUi.rect(c, left, top, w, h, FlatUi.GROUND);
        FlatUi.outline(c, left, top, w, h, FlatUi.BORDER_STRONG);

        int popupMx = choosingStation ? -1 : mx;
        int popupMy = choosingStation ? -1 : my;
        renderHeader(c, popupMx, popupMy);
        if (station == null) {
            String none = Text.translatable("gui.station_announcer.exit_marker.no_station").getString();
            c.drawText(textRenderer, none, left + (w - textRenderer.getWidth(none)) / 2, top + h / 2 - 4,
                    FlatUi.TEXT_DIM, false);
        } else if (layoutTab) {
            renderLayout(c, popupMx, popupMy);
        } else {
            renderList(c, popupMx, popupMy);
            renderInspector(c, popupMx, popupMy);
        }
        renderFooter(c, popupMx, popupMy);
        if (choosingStation) {
            hits.clear();
            renderStationPopup(c, mx, my);
        }
    }

    private void renderHeader(DrawContext c, int mx, int my) {
        int x = left + 10;
        int right = left + w - 10;
        int y = top + 8;
        c.drawText(textRenderer, title, x, y, FlatUi.TEXT, false);
        String name = station == null ? "—" : MarkerRenderer.firstLang(station.getName());
        int tx = x + textRenderer.getWidth(title) + 8;
        if (station != null) {
            FlatUi.rect(c, tx, y - 1, 6, 10, 0xFF000000 | station.getColor());
        }
        String changeLabel = Text.translatable("gui.station_announcer.exit_marker.change_station").getString();
        int cw = textRenderer.getWidth(changeLabel) + 12;
        String[] tabs = {Text.translatable("gui.station_announcer.exit_marker.tab_exits").getString(),
                Text.translatable("gui.station_announcer.exit_marker.tab_layout").getString()};
        int tabW = Math.max(textRenderer.getWidth(tabs[0]), textRenderer.getWidth(tabs[1])) * 2 + 20;
        int tabX = right - cw - 6 - tabW;
        FlatUi.segmented(c, textRenderer, tabs, layoutTab ? 1 : 0, tabX, y - 4, tabW, 15, mx, my);
        hits.add(new Hit(tabX, y - 4, tabW / 2, 15, () -> layoutTab = false));
        hits.add(new Hit(tabX + tabW / 2, y - 4, tabW - tabW / 2, 15, () -> layoutTab = true));
        int nameMax = tabX - 6 - (tx + 10);
        c.drawText(textRenderer, textRenderer.trimToWidth(name, Math.max(20, nameMax)), tx + 10, y, FlatUi.TEXT_DIM, false);
        FlatUi.button(c, textRenderer, changeLabel, right - cw, y - 4, cw, 15, mx, my, FlatUi.ButtonStyle.FLAT);
        hits.add(new Hit(right - cw, y - 4, cw, 15, () -> choosingStation = true));
    }

    private void renderList(DrawContext c, int mx, int my) {
        int x = left + 8;
        int y = top + 26;
        int listH = h - 26 - 32;
        FlatUi.pane(c, x, y, LIST_W, listH);
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.exit_marker.exits").getString(), x + 6, y + 5);
        listTop = y + 17;
        int addH = canEdit ? 18 : 0;
        listVisible = Math.max(1, (listH - 17 - addH - 2) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - listVisible)));
        if (rows.isEmpty()) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.exit_marker.no_exits").getString(),
                    x + 6, listTop + 4, FlatUi.TEXT_FAINT, false);
        }
        for (int i = listScroll; i < Math.min(rows.size(), listScroll + listVisible); i++) {
            Row row = rows.get(i);
            int ry = listTop + (i - listScroll) * ROW_H;
            boolean on = i == selected;
            boolean hover = FlatUi.inside(mx, my, x + 1, ry, LIST_W - 2, ROW_H);
            if (on) {
                FlatUi.rect(c, x + 1, ry, LIST_W - 2, ROW_H, FlatUi.SELECTED);
            } else if (hover) {
                FlatUi.rect(c, x + 1, ry, LIST_W - 2, ROW_H, FlatUi.HOVER);
            }
            // green exit badge with the exit's name, like the in-world pin
            String badge = row.name.isEmpty() ? "?" : row.name;
            int bw = Math.max(14, textRenderer.getWidth(badge) + 6);
            boolean valid = ExitPin.validExitName(row.name);
            FlatUi.rect(c, x + 5, ry + 3, bw, 13, valid ? 0xFF1E8E3E : FlatUi.DANGER);
            c.drawText(textRenderer, badge, x + 5 + (bw - textRenderer.getWidth(badge)) / 2, ry + 6, 0xFFFFFFFF, false);
            String destination = row.destinations.isEmpty() ? "" : row.destinations.get(0);
            int textX = x + 9 + bw;
            int textMax = LIST_W - (textX - x) - (row == pinRow ? 14 : 4);
            c.drawText(textRenderer, textRenderer.trimToWidth(destination, Math.max(8, textMax)), textX, ry + 6,
                    FlatUi.TEXT_DIM, false);
            if (row == pinRow) {
                FlatUi.rect(c, x + LIST_W - 11, ry + 7, 6, 6, 0xFF3DD68C);
            }
            int index = i;
            hits.add(new Hit(x + 1, ry, LIST_W - 2, ROW_H, () -> select(index)));
        }
        FlatUi.scrollThumb(c, x + LIST_W, listTop, listVisible * ROW_H, rows.size() * ROW_H, listScroll * ROW_H);
        if (canEdit) {
            int by = y + listH - 20;
            FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.exit_marker.add_exit").getString(),
                    x + 4, by, LIST_W - 8, 16, mx, my, FlatUi.ButtonStyle.FLAT);
            hits.add(new Hit(x + 4, by, LIST_W - 8, 16, () -> {
                Row row = new Row(null, suggestName());
                rows.add(row);
                select(rows.size() - 1);
                listScroll = Math.max(0, rows.size() - listVisible);
                nameBox.setFocused(true);
            }));
        }
    }

    private void renderInspector(DrawContext c, int mx, int my) {
        int x = left + 8 + LIST_W + 8;
        int right = left + w - 10;
        int y = top + 26;
        Row row = selectedRow();
        if (row == null) {
            c.drawText(textRenderer, Text.translatable(canEdit ? "gui.station_announcer.exit_marker.pick_or_add"
                    : "gui.station_announcer.exit_marker.pick").getString(), x, y + 6, FlatUi.TEXT_FAINT, false);
            return;
        }

        // Name
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.exit_marker.name").getString(), x, y + 2);
        nameBox.setBounds(x, y + 12, 60, 16);
        nameBox.render(c, mx, my);
        String nameHint = ExitPin.validExitName(row.name)
                ? Text.translatable("gui.station_announcer.exit_marker.name_ok").getString()
                : Text.translatable("gui.station_announcer.exit_marker.name_rule").getString();
        c.drawText(textRenderer, textRenderer.trimToWidth(nameHint, right - x - 66), x + 66, y + 16,
                ExitPin.validExitName(row.name) ? FlatUi.TEXT_FAINT : FlatUi.DANGER, false);

        // Pin button
        y += 34;
        boolean pinnedHere = row == pinRow;
        String pinLabel = pinnedHere
                ? Text.translatable("gui.station_announcer.exit_marker.pinned_here").getString()
                : Text.translatable("gui.station_announcer.exit_marker.pin_here", row.name.isEmpty() ? "?" : row.name).getString();
        int pw = Math.min(right - x - (pinnedHere ? 58 : 0), textRenderer.getWidth(pinLabel) + 16);
        boolean pinValid = ExitPin.validExitName(row.name);
        FlatUi.button(c, textRenderer, pinLabel, x, y, pw, 16, mx, my,
                pinnedHere ? FlatUi.ButtonStyle.FLAT : FlatUi.ButtonStyle.PRIMARY, pinnedHere || pinValid);
        if (!pinnedHere && pinValid) {
            hits.add(new Hit(x, y, pw, 16, () -> pinRow = row));
        }
        if (pinnedHere) {
            String unpin = Text.translatable("gui.station_announcer.exit_marker.unpin").getString();
            FlatUi.button(c, textRenderer, unpin, x + pw + 4, y, 54, 16, mx, my, FlatUi.ButtonStyle.GHOST);
            hits.add(new Hit(x + pw + 4, y, 54, 16, () -> pinRow = null));
        }

        // Destinations
        y += 24;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.exit_marker.destinations").getString(), x, y);
        y += 11;
        int bottom = top + h - 32 - (canEdit ? 22 : 0);
        destTop = y;
        destVisible = Math.max(1, (bottom - y) / DEST_ROW);
        destinationScroll = Math.max(0, Math.min(destinationScroll, Math.max(0, destinationBoxes.size() - destVisible)));
        int boxW = right - x - (canEdit ? 50 : 0);
        for (int i = 0; i < destinationBoxes.size(); i++) {
            FlatUi.TextBox box = destinationBoxes.get(i);
            if (i < destinationScroll || i >= destinationScroll + destVisible) {
                box.setBounds(-1000, -1000, 0, 0); // off-screen: never clickable
                continue;
            }
            int ry = y + (i - destinationScroll) * DEST_ROW;
            box.setBounds(x, ry, boxW, 16);
            box.render(c, mx, my);
            if (canEdit) {
                int index = i;
                int ix = x + boxW + 2;
                FlatUi.iconButton(c, textRenderer, "↑", ix, ry + 1, 14, mx, my, FlatUi.TEXT_DIM);
                hits.add(new Hit(ix, ry + 1, 14, 14, () -> moveDestination(row, index, -1)));
                FlatUi.iconButton(c, textRenderer, "↓", ix + 16, ry + 1, 14, mx, my, FlatUi.TEXT_DIM);
                hits.add(new Hit(ix + 16, ry + 1, 14, 14, () -> moveDestination(row, index, 1)));
                FlatUi.iconButton(c, textRenderer, "×", ix + 32, ry + 1, 14, mx, my, FlatUi.DANGER);
                hits.add(new Hit(ix + 32, ry + 1, 14, 14, () -> {
                    row.destinations.remove(index);
                    rebuildDestinationBoxes();
                }));
            }
        }
        if (destinationBoxes.isEmpty()) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.exit_marker.no_destinations").getString(),
                    x, y + 4, FlatUi.TEXT_FAINT, false);
        }
        FlatUi.scrollThumb(c, right + 4, y, destVisible * DEST_ROW, destinationBoxes.size() * DEST_ROW,
                destinationScroll * DEST_ROW);

        if (canEdit) {
            int by = top + h - 32 - 20;
            boolean room = row.destinations.size() < MAX_DESTINATIONS;
            String add = Text.translatable("gui.station_announcer.exit_marker.add_destination").getString();
            int aw = textRenderer.getWidth(add) + 16;
            FlatUi.button(c, textRenderer, add, x, by, aw, 16, mx, my, FlatUi.ButtonStyle.FLAT, room);
            if (room) {
                hits.add(new Hit(x, by, aw, 16, () -> {
                    row.destinations.add("");
                    rebuildDestinationBoxes();
                    destinationScroll = Math.max(0, destinationBoxes.size() - destVisible);
                    destinationBoxes.get(destinationBoxes.size() - 1).setFocused(true);
                }));
            }
            String delete = Text.translatable("gui.station_announcer.exit_marker.delete_exit").getString();
            int dw = textRenderer.getWidth(delete) + 16;
            FlatUi.button(c, textRenderer, delete, right - dw, by, dw, 16, mx, my, FlatUi.ButtonStyle.DANGER);
            hits.add(new Hit(right - dw, by, dw, 16, () -> {
                rows.remove(row);
                if (pinRow == row) {
                    pinRow = null;
                }
                select(Math.min(selected, rows.size() - 1));
            }));
        }
    }

    private void moveDestination(Row row, int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= row.destinations.size()) {
            return;
        }
        String moved = row.destinations.remove(index);
        row.destinations.add(to, moved);
        rebuildDestinationBoxes();
    }

    private void renderFooter(DrawContext c, int mx, int my) {
        int x = left + 10;
        int right = left + w - 10;
        int by = top + h - 24;
        FlatUi.rect(c, left + 1, by - 5, w - 2, 1, FlatUi.BORDER);
        String error = validationError();
        String line;
        int color;
        if (error != null) {
            line = error;
            color = FlatUi.DANGER;
        } else if (!status.isEmpty()) {
            line = status;
            color = statusColor;
        } else if (!canEdit) {
            line = Text.translatable("gui.station_announcer.exit_marker.read_only").getString();
            color = FlatUi.TEXT_FAINT;
        } else {
            line = pinRow == null ? Text.translatable("gui.station_announcer.exit_marker.status_unpinned").getString()
                    : Text.translatable("gui.station_announcer.exit_marker.status_pinned", pinRow.name).getString();
            color = pinRow == null ? 0xFFC98A00 : FlatUi.TEXT_DIM;
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(line, right - x - 136), x, by + 4, color, false);
        FlatUi.button(c, textRenderer, Text.translatable("gui.cancel").getString(), right - 128, by, 60, 16,
                mx, my, FlatUi.ButtonStyle.GHOST);
        hits.add(new Hit(right - 128, by, 60, 16, this::close));
        boolean canSave = error == null;
        FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.exit_marker.save").getString(),
                right - 60, by, 60, 16, mx, my, FlatUi.ButtonStyle.PRIMARY, canSave);
        if (canSave) {
            hits.add(new Hit(right - 60, by, 60, 16, this::save));
        }
    }

    private void renderStationPopup(DrawContext c, int mx, int my) {
        List<Station> nearby = nearbyStations();
        int pw = Math.min(240, w - 40);
        int rowsShown = Math.min(nearby.size(), 10);
        int ph = 24 + Math.max(1, rowsShown) * 16 + 6;
        int px = left + (w - pw) / 2;
        int py = top + 22;
        c.fill(left, top, left + w, top + h, 0x80000000);
        FlatUi.pane(c, px, py, pw, ph);
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.exit_marker.nearby_stations").getString(),
                px + 6, py + 6);
        hits.add(new Hit(left, top, w, h, () -> choosingStation = false)); // click outside = close
        if (nearby.isEmpty()) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.exit_marker.no_station").getString(),
                    px + 6, py + 22, FlatUi.TEXT_FAINT, false);
        }
        for (int i = 0; i < rowsShown; i++) {
            Station option = nearby.get(i);
            int ry = py + 20 + i * 16;
            boolean hover = FlatUi.inside(mx, my, px + 1, ry, pw - 2, 16);
            boolean current = station != null && option.getId() == station.getId();
            if (current) {
                FlatUi.rect(c, px + 1, ry, pw - 2, 16, FlatUi.SELECTED);
            } else if (hover) {
                FlatUi.rect(c, px + 1, ry, pw - 2, 16, FlatUi.HOVER);
            }
            FlatUi.rect(c, px + 6, ry + 4, 7, 8, 0xFF000000 | option.getColor());
            c.drawText(textRenderer, textRenderer.trimToWidth(MarkerRenderer.firstLang(option.getName()), pw - 60),
                    px + 18, ry + 4, FlatUi.TEXT, false);
            String count = option.getExits().size() + "";
            c.drawText(textRenderer, count, px + pw - 8 - textRenderer.getWidth(count), ry + 4, FlatUi.TEXT_FAINT, false);
            hits.add(new Hit(px + 1, ry, pw - 2, 16, () -> {
                choosingStation = false;
                if (station == null || option.getId() != station.getId()) {
                    loadStation(option);
                }
            }));
        }
    }

    /** Dev rig only (WayfindingClient#devHook): open on the Layout tab, scrolled down {@code scroll} px. */
    void devShowLayout(int scroll) {
        layoutTab = true;
        devScroll = scroll;
    }

    /** Dev rig only: a scroll to apply once the Layout tab has measured its content. */
    private int devScroll;

    /** Dev rig only (WayfindingClient#devHook): add or reuse exit {@code name}, pin to it, save. */
    void devAddAndPin(String name, String destination) {
        Row row = null;
        for (Row existing : rows) {
            if (existing.name.equals(name)) {
                row = existing;
            }
        }
        if (row == null) {
            row = new Row(null, name);
            rows.add(row);
        }
        if (!destination.isBlank() && !row.destinations.contains(destination)) {
            row.destinations.add(destination);
        }
        pinRow = row;
        save();
    }

    // ------------------------------------------------------------ layout tab

    /**
     * The station's scanned walking layout: status, the Scan buttons (scans run
     * only when someone presses them), step-free per platform (computed, next to
     * the manual flag, which wins), every scanned walk and the street openings
     * the scan found without an Exit Marker.
     */
    private void renderLayout(DrawContext c, int mx, int my) {
        int x = left + 10;
        int right = left + w - 10;
        int y = top + 28;
        long stationId = station.getId();
        ClientLayouts.Entry entry = ClientLayouts.entry(stationId);
        com.google.gson.JsonObject layout = entry == null ? null : entry.layout();

        // status
        String line;
        int color = FlatUi.TEXT_DIM;
        byte state = entry == null ? 0 : entry.state();
        if (state == com.stationannouncer.wayfinding.layout.LayoutScanner.STATE_QUEUED) {
            line = Text.translatable("gui.station_announcer.layout.queued").getString();
            color = 0xFFC98A00;
        } else if (state == com.stationannouncer.wayfinding.layout.LayoutScanner.STATE_SCANNING) {
            line = Text.translatable("gui.station_announcer.layout.scanning").getString();
            color = 0xFFC98A00;
        } else if (state == com.stationannouncer.wayfinding.layout.LayoutScanner.STATE_FAILED) {
            line = Text.translatable("gui.station_announcer.layout.failed", entry.message()).getString();
            color = FlatUi.DANGER;
        } else if (layout != null) {
            long ago = System.currentTimeMillis() - layout.get("scannedAt").getAsLong();
            line = Text.translatable("gui.station_announcer.layout.scanned", ago(ago), layout.get("millis").getAsLong(),
                    layout.get("nodes").getAsInt()).getString();
        } else {
            line = Text.translatable("gui.station_announcer.layout.never").getString();
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(line, right - x), x, y, color, false);

        // buttons
        y += 13;
        boolean canScan = client != null && client.player != null
                && client.player.hasPermissionLevel(com.stationannouncer.wayfinding.layout.LayoutScanner.SCAN_PERMISSION);
        int bx = x;
        if (canScan) {
            String scan = Text.translatable("gui.station_announcer.layout.scan").getString();
            int sw = textRenderer.getWidth(scan) + 16;
            FlatUi.button(c, textRenderer, scan, bx, y, sw, 16, mx, my, FlatUi.ButtonStyle.PRIMARY);
            hits.add(new Hit(bx, y, sw, 16, () -> ClientLayouts.request(
                    com.stationannouncer.wayfinding.layout.LayoutScanner.ACTION_SCAN, stationId)));
            bx += sw + 4;
            String all = Text.translatable("gui.station_announcer.layout.scan_all").getString();
            int aw = textRenderer.getWidth(all) + 16;
            FlatUi.button(c, textRenderer, all, bx, y, aw, 16, mx, my, FlatUi.ButtonStyle.FLAT);
            hits.add(new Hit(bx, y, aw, 16, () -> ClientLayouts.request(
                    com.stationannouncer.wayfinding.layout.LayoutScanner.ACTION_SCAN_ALL, stationId)));
            bx += aw + 4;
        }
        if (layout != null) {
            boolean shown = ClientLayouts.overlayShown(stationId);
            String toggle = Text.translatable(shown ? "gui.station_announcer.layout.hide_paths"
                    : "gui.station_announcer.layout.show_paths").getString();
            int tw = textRenderer.getWidth(toggle) + 16;
            FlatUi.button(c, textRenderer, toggle, bx, y, tw, 16, mx, my, FlatUi.ButtonStyle.GHOST);
            hits.add(new Hit(bx, y, tw, 16, () -> {
                if (ClientLayouts.overlayShown(stationId)) {
                    ClientLayouts.hideOverlay();
                } else {
                    ClientLayouts.showOverlay(stationId);
                }
            }));
        } else if (!canScan) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.layout.ask_op").getString(), x, y + 4,
                    FlatUi.TEXT_FAINT, false);
        }

        // scrolling details
        y += 22;
        layoutViewTop = y;
        layoutViewHeight = top + h - 32 - y;
        if (layout == null) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.layout.explain").getString(), x, y,
                    FlatUi.TEXT_FAINT, false);
            return;
        }
        if (devScroll > 0 && layoutContentHeight > 0) {
            layoutScroll = devScroll;
            devScroll = 0;
        }
        layoutScroll = Math.max(0, Math.min(layoutScroll, Math.max(0, layoutContentHeight - layoutViewHeight)));
        c.enableScissor(left + 1, layoutViewTop, left + w - 1, layoutViewTop + layoutViewHeight);
        int cy = y - layoutScroll;
        int start = cy;
        java.util.Map<String, String> names = anchorNames(layout);

        // what the scan looked at
        com.google.gson.JsonObject region = layout.getAsJsonObject("region");
        if (region != null) {
            FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.scanned_area").getString(), x, cy);
            cy += 11;
            com.google.gson.JsonArray min = region.getAsJsonArray("min");
            com.google.gson.JsonArray max = region.getAsJsonArray("max");
            String text = Text.translatable("gui.station_announcer.layout.scanned_area_row",
                    max.get(0).getAsInt() - min.get(0).getAsInt() + 1, max.get(2).getAsInt() - min.get(2).getAsInt() + 1,
                    min.get(1).getAsInt(), max.get(1).getAsInt(), region.get("margin").getAsInt()).getString();
            cy = wrapped(c, text, x, cy, right - x, FlatUi.TEXT_DIM);
            cy += 5;
        }

        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.step_free").getString(), x, cy);
        cy += 11;
        com.google.gson.JsonObject flags = layout.getAsJsonObject("platformStepFree");
        boolean manual = com.stationannouncer.client.mtraddon.ClientAccessibility.isAccessibleStation(stationId);
        if (flags == null || flags.size() == 0) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.layout.no_street").getString(), x, cy,
                    FlatUi.TEXT_FAINT, false);
            cy += 11;
        } else {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> flag : flags.entrySet()) {
                long platformId = Long.parseLong(flag.getKey());
                boolean computed = flag.getValue().getAsBoolean();
                String name = names.getOrDefault("platform:" + flag.getKey(), flag.getKey());
                c.drawText(textRenderer, name, x, cy, FlatUi.TEXT, false);
                String verdict = Text.translatable(computed ? "gui.station_announcer.layout.sf_yes"
                        : "gui.station_announcer.layout.sf_no").getString();
                c.drawText(textRenderer, verdict, x + 90, cy, computed ? 0xFF3DD68C : 0xFFC98A00, false);
                if (manual) {
                    boolean manualYes = com.stationannouncer.client.mtraddon.ClientAccessibility
                            .isAccessiblePlatform(stationId, platformId);
                    String note = Text.translatable(manualYes ? "gui.station_announcer.layout.manual_yes"
                            : "gui.station_announcer.layout.manual_no").getString();
                    boolean disagree = manualYes != computed;
                    c.drawText(textRenderer, textRenderer.trimToWidth(note + (disagree ? " ⚠" : ""), right - x - 190),
                            x + 190, cy, disagree ? FlatUi.DANGER : FlatUi.TEXT_FAINT, false);
                }
                cy += 11;
            }
            if (manual) {
                c.drawText(textRenderer, Text.translatable("gui.station_announcer.layout.manual_wins").getString(), x, cy,
                        FlatUi.TEXT_FAINT, false);
                cy += 11;
            }
        }

        // per exit / entrance: which platforms without steps, and whether by lift
        List<com.google.gson.JsonObject> ways = new ArrayList<>();
        List<com.google.gson.JsonObject> fares = new ArrayList<>();
        for (com.google.gson.JsonElement element : layout.getAsJsonArray("anchors")) {
            com.google.gson.JsonObject anchor = element.getAsJsonObject();
            String kind = anchor.get("kind").getAsString();
            if (kind.equals("exit") || kind.equals("opening")
                    || (kind.equals("fare") && anchor.has("entrance") && anchor.get("entrance").getAsBoolean())) {
                ways.add(anchor);
            }
            if (kind.equals("fare")) {
                fares.add(anchor);
            }
        }
        if (!ways.isEmpty()) {
            cy += 5;
            FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.ways_in").getString(), x, cy);
            cy += 11;
            int nameW = 0;
            for (com.google.gson.JsonObject anchor : ways) {
                String id = anchor.get("id").getAsString();
                nameW = Math.max(nameW, textRenderer.getWidth(names.getOrDefault(id, id)));
            }
            nameW = Math.min(nameW + 8, (right - x) / 2);
            for (com.google.gson.JsonObject anchor : ways) {
                String id = anchor.get("id").getAsString();
                c.drawText(textRenderer, textRenderer.trimToWidth(names.getOrDefault(id, id), nameW - 4), x, cy, FlatUi.TEXT, false);
                String access = anchor.has("stepFree") ? anchor.get("stepFree").getAsString() : null;
                boolean lift = anchor.has("lift") && anchor.get("lift").getAsBoolean();
                String verdict;
                int verdictColor;
                if (access == null) {
                    verdict = Text.translatable("gui.station_announcer.layout.way_none_reached").getString();
                    verdictColor = FlatUi.TEXT_FAINT;
                } else if (access.equals("all")) {
                    verdict = Text.translatable(lift ? "gui.station_announcer.layout.way_all_lift"
                            : "gui.station_announcer.layout.way_all").getString();
                    verdictColor = ClientLayouts.C_ALL;
                } else if (access.equals("some")) {
                    List<String> to = new ArrayList<>();
                    for (com.google.gson.JsonElement p : anchor.getAsJsonArray("stepFreeTo")) {
                        to.add(names.getOrDefault("platform:" + p.getAsString(), p.getAsString()));
                    }
                    verdict = Text.translatable(lift ? "gui.station_announcer.layout.way_some_lift"
                            : "gui.station_announcer.layout.way_some", String.join(", ", to)).getString();
                    verdictColor = ClientLayouts.C_SOME;
                } else {
                    verdict = Text.translatable("gui.station_announcer.layout.way_stairs").getString();
                    verdictColor = ClientLayouts.C_NONE;
                }
                c.drawText(textRenderer, textRenderer.trimToWidth(verdict, right - x - nameW), x + nameW, cy, verdictColor, false);
                cy += 11;
            }
        }
        cy += 5;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.fare_control").getString(), x, cy);
        cy += 11;
        if (fares.isEmpty()) {
            cy = wrapped(c, Text.translatable("gui.station_announcer.layout.no_fare").getString(), x, cy, right - x,
                    FlatUi.TEXT_FAINT);
        }
        for (com.google.gson.JsonObject fare : fares) {
            String id = fare.get("id").getAsString();
            com.google.gson.JsonArray pos = fare.getAsJsonArray("pos");
            String head = Text.translatable("gui.station_announcer.layout.fare_row", names.getOrDefault(id, id),
                    (int) Math.floor(pos.get(0).getAsDouble()), (int) Math.floor(pos.get(1).getAsDouble()),
                    (int) Math.floor(pos.get(2).getAsDouble())).getString();
            c.drawText(textRenderer, textRenderer.trimToWidth(head, right - x), x, cy, ClientLayouts.C_FARE, false);
            cy += 10;
            String detail;
            if (fare.has("entrance") && fare.get("entrance").getAsBoolean()) {
                detail = Text.translatable("gui.station_announcer.layout.fare_entrance").getString();
            } else if (fare.has("usedBy")) {
                List<String> by = new ArrayList<>();
                for (com.google.gson.JsonElement u : fare.getAsJsonArray("usedBy")) {
                    by.add(names.getOrDefault(u.getAsString(), u.getAsString()));
                }
                detail = Text.translatable("gui.station_announcer.layout.fare_used_by", String.join(", ", by)).getString();
            } else {
                detail = Text.translatable("gui.station_announcer.layout.fare_unused").getString();
            }
            c.drawText(textRenderer, textRenderer.trimToWidth(detail, right - x - 8), x + 8, cy, FlatUi.TEXT_DIM, false);
            cy += 12;
        }

        cy += 5;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.walks").getString(), x, cy);
        cy += 11;
        com.google.gson.JsonArray links = layout.getAsJsonArray("links");
        if (links.isEmpty()) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.layout.no_walks").getString(), x, cy,
                    FlatUi.TEXT_FAINT, false);
            cy += 11;
        }
        for (com.google.gson.JsonElement element : links) {
            com.google.gson.JsonObject link = element.getAsJsonObject();
            String from = names.getOrDefault(link.get("from").getAsString(), link.get("from").getAsString());
            String to = names.getOrDefault(link.get("to").getAsString(), link.get("to").getAsString());
            String head = from + " → " + to + " · " + Math.round(link.get("meters").getAsDouble()) + " m";
            c.drawText(textRenderer, textRenderer.trimToWidth(head, right - x), x, cy, FlatUi.TEXT, false);
            cy += 10;
            c.drawText(textRenderer, textRenderer.trimToWidth(legsText(link.getAsJsonArray("legs"), names), right - x - 8),
                    x + 8, cy, link.get("stepFree").getAsBoolean() ? 0xFF3DD68C : FlatUi.TEXT_DIM, false);
            cy += 10;
            if (link.has("stepFreeAlt")) {
                com.google.gson.JsonObject alt = link.getAsJsonObject("stepFreeAlt");
                String text = Text.translatable("gui.station_announcer.layout.alt",
                        Math.round(alt.get("meters").getAsDouble()), legsText(alt.getAsJsonArray("legs"), names)).getString();
                c.drawText(textRenderer, textRenderer.trimToWidth(text, right - x - 8), x + 8, cy, 0xFF6FA8FF, false);
                cy += 10;
            }
            cy += 3;
        }

        java.util.List<com.google.gson.JsonObject> openings = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : layout.getAsJsonArray("anchors")) {
            if (element.getAsJsonObject().get("kind").getAsString().equals("opening")) {
                openings.add(element.getAsJsonObject());
            }
        }
        if (!openings.isEmpty()) {
            cy += 5;
            FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.openings").getString(), x, cy);
            cy += 11;
            for (com.google.gson.JsonObject opening : openings) {
                com.google.gson.JsonArray pos = opening.getAsJsonArray("pos");
                double ox = pos.get(0).getAsDouble();
                double oy = pos.get(1).getAsDouble();
                double oz = pos.get(2).getAsDouble();
                long away = Math.round(Math.sqrt(marker.getPos().getSquaredDistance(ox, oy, oz)));
                String text = Text.translatable("gui.station_announcer.layout.opening_row",
                        (int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz), away).getString();
                c.drawText(textRenderer, text, x, cy, 0xFFF0A020, false);
                cy += 11;
            }
        }

        com.google.gson.JsonArray warnings = layout.getAsJsonArray("warnings");
        if (warnings != null && !warnings.isEmpty()) {
            cy += 5;
            FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.warnings").getString(), x, cy);
            cy += 11;
            for (com.google.gson.JsonElement warning : warnings) {
                for (net.minecraft.text.OrderedText wrapped : textRenderer.wrapLines(
                        net.minecraft.text.StringVisitable.plain(warning.getAsString()), right - x)) {
                    c.drawText(textRenderer, wrapped, x, cy, FlatUi.DANGER, false);
                    cy += 10;
                }
            }
        }
        cy += 5;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.layout.legend").getString(), x, cy);
        cy += 11;
        for (Object[] row : ClientLayouts.LEGEND) {
            ClientLayouts.drawSwatch(c, x, cy, (int) row[0], (int) row[1]);
            c.drawText(textRenderer, textRenderer.trimToWidth(Text.translatable((String) row[2]).getString(), right - x - 14),
                    x + 14, cy, FlatUi.TEXT_DIM, false);
            cy += 10;
        }
        layoutContentHeight = cy - start;
        c.disableScissor();
        FlatUi.scrollThumb(c, right + 6, layoutViewTop, layoutViewHeight, layoutContentHeight, layoutScroll);
    }

    /** Display names for a layout's anchors: "Exit A", "Platform 1", "Street entrance 2", "Fare control 1". */
    static java.util.Map<String, String> anchorNames(com.google.gson.JsonObject layout) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        int openings = 0;
        int fares = 0;
        for (com.google.gson.JsonElement element : layout.getAsJsonArray("anchors")) {
            com.google.gson.JsonObject anchor = element.getAsJsonObject();
            String kind = anchor.get("kind").getAsString();
            if (kind.equals("opening")) {
                openings++;
            } else if (kind.equals("fare")) {
                fares++;
            }
        }
        int opening = 0;
        int fare = 0;
        for (com.google.gson.JsonElement element : layout.getAsJsonArray("anchors")) {
            com.google.gson.JsonObject anchor = element.getAsJsonObject();
            String id = anchor.get("id").getAsString();
            String kind = anchor.get("kind").getAsString();
            String name = anchor.get("name").getAsString();
            names.put(id, switch (kind) {
                case "exit" -> "Exit " + name;
                case "platform" -> Text.translatable("gui.station_announcer.layout.platform", name.isEmpty() ? "?" : name).getString();
                case "fare" -> Text.translatable("gui.station_announcer.layout.fare_name").getString()
                        + (fares > 1 ? " " + ++fare : "");
                default -> Text.translatable("gui.station_announcer.layout.opening").getString()
                        + (openings > 1 ? " " + ++opening : "");
            });
        }
        return names;
    }

    /** Wrapped text; returns the y below it. */
    private int wrapped(DrawContext c, String text, int x, int y, int width, int color) {
        for (net.minecraft.text.OrderedText line : textRenderer.wrapLines(net.minecraft.text.StringVisitable.plain(text), width)) {
            c.drawText(textRenderer, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    static String legsText(com.google.gson.JsonArray legs) {
        return legsText(legs, java.util.Map.of());
    }

    /** "12 m · stairs ↓10 · fare control 2 · 4 m" — what a scanned walk takes. */
    static String legsText(com.google.gson.JsonArray legs, java.util.Map<String, String> names) {
        List<String> parts = new ArrayList<>();
        for (com.google.gson.JsonElement element : legs) {
            com.google.gson.JsonObject leg = element.getAsJsonObject();
            String kind = leg.get("kind").getAsString();
            long meters = Math.round(leg.get("meters").getAsDouble());
            long dy = Math.round(leg.get("dy").getAsDouble());
            String arrow = dy > 0 ? "↑" + dy : dy < 0 ? "↓" + (-dy) : "";
            parts.add(switch (kind) {
                case "stairs" -> Text.translatable("gui.station_announcer.layout.leg_stairs", arrow).getString();
                case "escalator" -> Text.translatable("gui.station_announcer.layout.leg_escalator", arrow).getString();
                case "lift" -> Text.translatable("gui.station_announcer.layout.leg_lift", arrow).getString();
                case "fare" -> leg.has("at") && names.containsKey(leg.get("at").getAsString())
                        ? names.get(leg.get("at").getAsString()).toLowerCase(java.util.Locale.ROOT)
                        : Text.translatable("gui.station_announcer.layout.leg_fare").getString();
                case "emergency" -> Text.translatable("gui.station_announcer.layout.leg_emergency").getString();
                default -> meters + " m";
            });
        }
        return String.join(" · ", parts);
    }

    private static String ago(long millis) {
        long minutes = millis / 60000L;
        if (minutes < 1) {
            return Text.translatable("gui.station_announcer.layout.just_now").getString();
        }
        if (minutes < 120) {
            return Text.translatable("gui.station_announcer.layout.minutes_ago", minutes).getString();
        }
        return Text.translatable("gui.station_announcer.layout.hours_ago", minutes / 60).getString();
    }

    // ------------------------------------------------------------ save

    private void save() {
        if (validationError() != null) {
            return;
        }
        long stationId = station == null ? 0 : station.getId();
        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>();
        if (station != null && canEdit) {
            Set<String> kept = new HashSet<>();
            for (Row row : rows) {
                if (row.original != null) {
                    kept.add(row.original);
                    if (!row.original.equals(row.name)) {
                        renames.put(row.original, row.name);
                    }
                }
            }
            for (StationExit exit : station.getExits()) {
                if (!kept.contains(exit.getName())) {
                    deleted.add(exit.getName());
                }
            }
            if (exitsChanged()) {
                sendExitsToMtr();
            }
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(marker.getPos());
        buf.writeLong(stationId);
        buf.writeString(pinRow == null ? "" : pinRow.name, ExitPin.MAX_EXIT_NAME);
        buf.writeVarInt(Math.min(renames.size(), Wayfinding.MAX_EXIT_EDITS));
        renames.entrySet().stream().limit(Wayfinding.MAX_EXIT_EDITS).forEach(entry -> {
            buf.writeString(entry.getKey(), ExitPin.MAX_EXIT_NAME);
            buf.writeString(entry.getValue(), ExitPin.MAX_EXIT_NAME);
        });
        buf.writeVarInt(Math.min(deleted.size(), Wayfinding.MAX_EXIT_EDITS));
        deleted.stream().limit(Wayfinding.MAX_EXIT_EDITS).forEach(name -> buf.writeString(name, ExitPin.MAX_EXIT_NAME));
        ClientPlayNetworking.send(Wayfinding.UPDATE_EXIT_MARKER_C2S, buf);
        close();
    }

    private boolean exitsChanged() {
        List<String> now = new ArrayList<>();
        for (Row row : rows) {
            now.add(signature(cleaned(row)));
        }
        List<String> before = new ArrayList<>(loadedSignature);
        now.sort(String::compareTo);
        before.sort(String::compareTo);
        return !Objects.equals(now, before);
    }

    /** A row with blank destinations dropped and the rest trimmed (what gets saved). */
    private static Row cleaned(Row row) {
        Row out = new Row(row.original, row.name);
        for (String destination : row.destinations) {
            String trimmed = destination == null ? "" : destination.trim();
            if (!trimmed.isEmpty()) {
                out.destinations.add(trimmed);
            }
        }
        return out;
    }

    /** Exactly what MTR's own station screen does on save: edit the station, send it. */
    private void sendExitsToMtr() {
        if (station == null) {
            return;
        }
        try {
            station.getExits().clear();
            for (Row row : rows) {
                Row clean = cleaned(row);
                StationExit exit = new StationExit();
                exit.setName(clean.name);
                exit.getDestinations().addAll(clean.destinations);
                station.getExits().add(exit);
            }
            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(
                    new UpdateDataRequest(MinecraftClientData.getDashboardInstance()).addStation(station)));
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Exit marker: could not send station exits to MTR", t);
            status = Text.translatable("gui.station_announcer.exit_marker.mtr_failed").getString();
            statusColor = FlatUi.DANGER;
        }
    }

    // ------------------------------------------------------------ input

    private List<FlatUi.TextBox> boxes() {
        List<FlatUi.TextBox> all = new ArrayList<>();
        if (selectedRow() != null && !choosingStation && !layoutTab) {
            all.add(nameBox);
            all.addAll(destinationBoxes);
        }
        return all;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (canEdit && button == 0) {
            FlatUi.TextBox hitBox = null;
            for (FlatUi.TextBox box : boxes()) {
                if (hitBox == null && box.mouseClicked(mx, my, button)) {
                    hitBox = box;
                }
            }
            for (FlatUi.TextBox box : boxes()) {
                box.setFocused(box == hitBox);
            }
            if (hitBox != null) {
                return true;
            }
        }
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (FlatUi.inside(mx, my, hit.x, hit.y, hit.w, hit.h)) {
                    status = "";
                    hit.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        for (FlatUi.TextBox box : boxes()) {
            box.mouseDragged(mx, my);
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        for (FlatUi.TextBox box : boxes()) {
            box.mouseReleased();
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        int step = vertical > 0 ? -1 : 1;
        if (layoutTab) {
            layoutScroll = Math.max(0, layoutScroll + step * 12);
            return true;
        }
        if (mx < left + 8 + LIST_W + 4) {
            listScroll = Math.max(0, listScroll + step);
        } else {
            destinationScroll = Math.max(0, destinationScroll + step);
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!canEdit) {
            return super.charTyped(chr, modifiers);
        }
        for (FlatUi.TextBox box : boxes()) {
            if (box.charTyped(chr)) {
                return true;
            }
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (choosingStation) {
                choosingStation = false;
                return true;
            }
            for (FlatUi.TextBox box : boxes()) {
                if (box.isFocused()) {
                    box.setFocused(false);
                    return true;
                }
            }
        }
        if (key == GLFW.GLFW_KEY_S && hasControlDown()) {
            save();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            List<FlatUi.TextBox> all = boxes();
            int focused = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).isFocused()) {
                    focused = i;
                }
            }
            if (!all.isEmpty() && canEdit) {
                int next = (focused + (hasShiftDown() ? all.size() - 1 : 1)) % all.size();
                for (int i = 0; i < all.size(); i++) {
                    all.get(i).setFocused(i == next);
                }
                return true;
            }
        }
        if (canEdit) {
            for (FlatUi.TextBox box : boxes()) {
                if (box.keyPressed(key, modifiers)) {
                    return true;
                }
            }
        }
        return super.keyPressed(key, scancode, modifiers);
    }
}
