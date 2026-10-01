package com.stationannouncer.client.mtraddon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.mtraddon.interline.InterlineService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Interlining screen behind Dashboard → Tools… (FlatUi, no vanilla widgets).
 *
 * <ul>
 *   <li><b>Sections</b> — every detected interline section (lines sharing consecutive
 *       platforms, one direction each): the depots feeding it, their travel time to the
 *       entry, scheduled and measured headway there, and the suggestion form (even
 *       spacing or a target headway; balance both directions / this way / the other way /
 *       options; optional dwell pads at the stop before). Apply writes the delays, the
 *       frequencies (all 24 hours, through MTR's own depot update) and the dwell.</li>
 *   <li><b>Depots</b> — a manual "delay departures by X" for any depot.</li>
 *   <li><b>Groups</b> — named depot sets; a group only preselects which depots a
 *       suggestion may move (nothing shifts on its own any more).</li>
 * </ul>
 *
 * <p>Everything shown comes from one server-built analysis (the same JSON the dispatch
 * web page reads); the server answers each request with a fresh one.</p>
 */
@Environment(EnvType.CLIENT)
public class InterlineScreen extends Screen {
    private static final int TOP = 24;
    private static final int ROW = 11;
    private static final int OK_COLOR = 0xFF43C26B;
    private static final int WARN_COLOR = 0xFFF5B942;
    private static final String[] TABS = {"Sections", "Depots", "Groups"};
    private static final String[] MODES = {"Even spacing", "Target headway"};
    private static final String[] DIRECTIONS = {"Balance", "This way", "Other way", "Options"};

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();

    private JsonObject analysis;
    private String error;
    private JsonObject suggestion;
    private int nextRequestId = 1;
    private int pendingRequest;
    private int suggestRequest;
    private String status = "";

    private int tab;
    private String selectedSection;
    private String selectedDepot;
    private String selectedGroup;
    private boolean creatingGroup;

    private int listScroll;
    private int detailScroll;
    private int listContent;
    private int detailContent;

    // Suggestion form (per section).
    private String formSection;
    private int mode;
    private int direction;
    /** What a suggestion may change: 0 depot delays, 1 platform holds, 2 both. */
    private int levers = 2;
    private static final String[] LEVERS = {"Depot delays", "Platform holds", "Both"};
    private final Set<String> chosenDepots = new LinkedHashSet<>();
    private final Map<String, Integer> weights = new HashMap<>();
    private String chosenGroup = "";
    private int candidate;

    // Group editor.
    private final Set<String> groupMembers = new LinkedHashSet<>();
    private String groupEditorFor = null;

    private FlatUi.TextBox targetBox;
    private FlatUi.TextBox delayBox;
    private FlatUi.TextBox groupNameBox;
    private String delayBoxFor;

    // Layout (recomputed each frame).
    private int listX;
    private int listW;
    private int detailX;
    private int detailW;
    private int paneTop;
    private int paneH;

    /** Dev hook command waiting for the first analysis. */
    private String pendingDev;

    public InterlineScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.interline.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (targetBox == null) {
            targetBox = new FlatUi.TextBox(textRenderer, 12, false);
            targetBox.placeholder = "e.g. 2:00";
            delayBox = new FlatUi.TextBox(textRenderer, 12, false);
            delayBox.placeholder = "e.g. 45 or 1:30";
            groupNameBox = new FlatUi.TextBox(textRenderer, 48, false);
            groupNameBox.placeholder = "Group name";
            requestAnalysis();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // ------------------------------------------------------------ networking

    private void requestAnalysis() {
        send(new JsonObject());
    }

    private int send(JsonObject params) {
        int id = nextRequestId++;
        pendingRequest = id;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(id);
        buf.writeString(params.toString(), InterlineService.MAX_REQUEST_CHARS);
        ClientPlayNetworking.send(InterlineService.REQUEST_C2S, buf);
        return id;
    }

    /** Client thread: a reply from the server (id −1 = refresh pushed after an edit). */
    public void accept(int requestId, String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            error = "Bad reply from the server";
            return;
        }
        if (requestId == pendingRequest || requestId < 0) {
            pendingRequest = 0;
        }
        JsonObject a = obj(root, "analysis");
        if (a != null) {
            if (bool(a, "ok")) {
                analysis = a;
                error = null;
            } else {
                error = str(a, "error");
            }
        }
        if (requestId < 0) {
            suggestion = null; // an edit landed; the old suggestion is stale
            delayBoxFor = null;
            groupEditorFor = null;
            status = "Applied.";
        }
        JsonObject s = obj(root, "suggestion");
        if (s != null && requestId == suggestRequest) {
            suggestion = s;
            candidate = 0;
            status = "";
        }
        if (pendingDev != null && analysis != null) {
            String command = pendingDev;
            pendingDev = null;
            devCommand(command);
        }
    }

    // ------------------------------------------------------------ dev hook

    /**
     * Dev-only (run/commands.txt): {@code #interline} opens the screen; then
     * {@code sections N} / {@code depots N} / {@code groups} pick a tab and row,
     * {@code suggest DIR [target]} runs a suggestion on the selected section
     * (DIR = balance|forward|reverse|options), {@code apply} applies it and
     * {@code delay N DURATION} sets the N-th depot's delay.
     */
    public static void devHook(net.minecraft.client.MinecraftClient client, String cmd) {
        String args = cmd.substring("#interline".length()).trim();
        if (client.currentScreen instanceof InterlineScreen screen && screen.analysis != null) {
            screen.devCommand(args);
            return;
        }
        InterlineScreen screen = new InterlineScreen(null);
        screen.pendingDev = args.isEmpty() ? null : args;
        client.setScreen(screen);
    }

    private void devCommand(String args) {
        String[] a = args.split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) {
            return;
        }
        int n = a.length > 1 ? parseIntSafe(a[1]) : 0;
        switch (a[0]) {
            case "sections" -> {
                tab = 0;
                JsonArray sections = arr(analysis, "sections");
                if (n < sections.size()) {
                    selectSection(str(sections.get(n).getAsJsonObject(), "id"));
                }
            }
            case "depots" -> {
                tab = 1;
                JsonArray depots = arr(analysis, "depots");
                if (n < depots.size()) {
                    selectedDepot = str(depots.get(n).getAsJsonObject(), "id");
                }
            }
            case "groups" -> tab = 2;
            case "suggest" -> {
                tab = 0;
                JsonObject section = findSection(selectedSection);
                if (section == null) {
                    JsonArray sections = arr(analysis, "sections");
                    section = sections.isEmpty() ? null : sections.get(0).getAsJsonObject();
                }
                if (section == null) {
                    return;
                }
                selectSection(str(section, "id"));
                ensureForm(section);
                String dir = a.length > 1 ? a[1] : "balance";
                direction = switch (dir) {
                    case "forward" -> 1;
                    case "reverse" -> 2;
                    case "options" -> 3;
                    default -> 0;
                };
                mode = a.length > 2 ? 1 : 0;
                if (a.length > 2) {
                    targetBox.load(a[2]);
                }
                suggest(section);
            }
            case "apply" -> applySuggestion();
            case "delay" -> {
                JsonArray depots = arr(analysis, "depots");
                if (n < depots.size() && a.length > 2) {
                    tab = 1;
                    selectedDepot = str(depots.get(n).getAsJsonObject(), "id");
                    saveDelay(selectedDepot, Math.max(0, parseDuration(a[2])));
                }
            }
            case "scroll" -> detailScroll = Math.max(0, n);
            default -> {
            }
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sendApply(JsonObject body) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(body.toString(), InterlineService.MAX_REQUEST_CHARS);
        ClientPlayNetworking.send(InterlineService.APPLY_C2S, buf);
        pendingRequest = -2;
        status = "Applying…";
    }

    private void sendGroup(boolean delete, long id, String name, Set<String> members) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(delete);
        buf.writeLong(id);
        String trimmed = name.length() > InterlineService.MAX_NAME_LENGTH ? name.substring(0, InterlineService.MAX_NAME_LENGTH) : name;
        buf.writeString(trimmed, InterlineService.MAX_NAME_LENGTH);
        List<Long> ids = new ArrayList<>();
        for (String member : members) {
            if (ids.size() < InterlineService.MAX_GROUP_DEPOTS) {
                ids.add(parseLong(member));
            }
        }
        buf.writeVarInt(ids.size());
        ids.forEach(buf::writeLong);
        ClientPlayNetworking.send(InterlineService.GROUP_C2S, buf);
        pendingRequest = -2;
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        tooltip = null;
        // Boxes not drawn this frame must not catch clicks at stale positions.
        targetBox.setBounds(0, 0, 0, 0);
        delayBox.setBounds(0, 0, 0, 0);
        groupNameBox.setBounds(0, 0, 0, 0);
        c.fill(0, 0, width, height, FlatUi.GROUND);
        // Top bar.
        c.drawText(textRenderer, title, 8, 8, FlatUi.TEXT, false);
        int tabsX = 8 + textRenderer.getWidth(title) + 12;
        int tabsW = Math.min(186, width - tabsX - 120);
        FlatUi.segmented(c, textRenderer, TABS, tab, tabsX, 5, tabsW, 14, mx, my);
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            int cx = tabsX + tabsW * i / TABS.length;
            hit(cx, 5, tabsW * (i + 1) / TABS.length - tabsW * i / TABS.length, 14, () -> {
                tab = index;
                listScroll = 0;
                detailScroll = 0;
            });
        }
        int doneX = width - 48;
        FlatUi.button(c, textRenderer, "Done", doneX, 5, 42, 14, mx, my, FlatUi.ButtonStyle.FLAT);
        hit(doneX, 5, 42, 14, this::close);
        FlatUi.button(c, textRenderer, "Refresh", doneX - 52, 5, 48, 14, mx, my, FlatUi.ButtonStyle.GHOST);
        hit(doneX - 52, 5, 48, 14, () -> {
            status = "";
            requestAnalysis();
        });
        String busy = pendingRequest != 0 ? "Working…" : status;
        if (!busy.isEmpty()) {
            int bw = textRenderer.getWidth(busy);
            int bx = doneX - 60 - bw;
            if (bx > tabsX + tabsW + 4) {
                c.drawText(textRenderer, busy, bx, 8, FlatUi.TEXT_DIM, false);
            }
        }

        paneTop = TOP;
        paneH = height - TOP - 6;
        listX = 6;
        listW = Math.max(118, Math.min(210, width * 3 / 10));
        detailX = listX + listW + 6;
        detailW = width - detailX - 6;
        FlatUi.pane(c, listX, paneTop, listW, paneH);
        FlatUi.pane(c, detailX, paneTop, detailW, paneH);

        if (analysis == null) {
            String message = error != null ? error : "Loading…";
            c.drawText(textRenderer, message, detailX + 8, paneTop + 8, error != null ? FlatUi.DANGER : FlatUi.TEXT_DIM, false);
            return;
        }
        if (error != null) {
            c.drawText(textRenderer, textRenderer.trimToWidth(error, detailW - 16), detailX + 8, paneTop + paneH - 12, FlatUi.DANGER, false);
        }

        // List pane.
        c.enableScissor(listX + 1, paneTop + 1, listX + listW - 1, paneTop + paneH - 1);
        int listEnd = switch (tab) {
            case 0 -> renderSectionList(c, mx, my);
            case 1 -> renderDepotList(c, mx, my);
            default -> renderGroupList(c, mx, my);
        };
        c.disableScissor();
        listContent = listEnd - (paneTop + 2 - listScroll);
        FlatUi.scrollThumb(c, listX + listW, paneTop, paneH, listContent, listScroll);

        // Detail pane.
        c.enableScissor(detailX + 1, paneTop + 1, detailX + detailW - 1, paneTop + paneH - 1);
        int detailEnd = switch (tab) {
            case 0 -> renderSectionDetail(c, mx, my);
            case 1 -> renderDepotDetail(c, mx, my);
            default -> renderGroupDetail(c, mx, my);
        };
        c.disableScissor();
        detailContent = detailEnd - (paneTop + 6 - detailScroll);
        if (tooltip != null) {
            c.drawTooltip(textRenderer, Text.literal(tooltip), mx, my);
        }
        FlatUi.scrollThumb(c, detailX + detailW, paneTop, paneH, detailContent + 6, detailScroll);
    }

    // ---- sections

    private int renderSectionList(DrawContext c, int mx, int my) {
        int y = paneTop + 2 - listScroll;
        JsonArray sections = arr(analysis, "sections");
        if (sections.isEmpty()) {
            wrap(c, "No interline sections: no two lines share consecutive platforms in the same order.",
                    listX + 6, y + 4, listW - 12, FlatUi.TEXT_DIM);
            return y + 40;
        }
        if (selectedSection == null || findSection(selectedSection) == null) {
            selectedSection = str(sections.get(0).getAsJsonObject(), "id");
        }
        for (JsonElement element : sections) {
            JsonObject section = element.getAsJsonObject();
            String id = str(section, "id");
            boolean selected = id.equals(selectedSection);
            int rowH = 22;
            listRow(c, y, rowH, selected, mx, my, () -> selectSection(id));
            c.drawText(textRenderer, textRenderer.trimToWidth(str(section, "name"), listW - 22), listX + 5, y + 3,
                    selected ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            double evenness = num(obj(section, "scheduled"), "evenness");
            dot(c, listX + listW - 12, y + 4, evennessColor(evenness, (int) num(obj(section, "scheduled"), "count")));
            int x = listX + 5;
            for (JsonElement route : arr(section, "routes")) {
                JsonObject info = route(route.getAsString());
                if (info == null || x > listX + listW - 14) {
                    continue;
                }
                x += swatch(c, info, x, y + 12) + 2;
            }
            y += rowH;
        }
        return y;
    }

    private void selectSection(String id) {
        if (!id.equals(selectedSection)) {
            selectedSection = id;
            detailScroll = 0;
        }
    }

    private int renderSectionDetail(DrawContext c, int mx, int my) {
        JsonObject section = findSection(selectedSection);
        int x = detailX + 8;
        int w = detailW - 16;
        int y = paneTop + 6 - detailScroll;
        if (section == null) {
            return y;
        }
        ensureForm(section);
        JsonObject reverse = findSection(str(section, "reverse"));

        JsonArray stations = arr(section, "stations");
        int titleW = w;
        if (reverse != null) {
            String label = "\u21c4 Other way";
            int bw = textRenderer.getWidth(label) + 10;
            FlatUi.button(c, textRenderer, label, x + w - bw, y - 2, bw, 13, mx, my, FlatUi.ButtonStyle.GHOST);
            String reverseId = str(reverse, "id");
            hitClipped(x + w - bw, y - 2, bw, 13, () -> selectSection(reverseId));
            titleW = w - bw - 6;
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(str(section, "name") + "  \u00b7  " + stations.size() + " shared stops", titleW),
                x, y, FlatUi.TEXT, false);
        y += 14;
        y = drawSectionMap(c, section, x, y, w, mx, my) + 8;

        // Feeds.
        FlatUi.heading(c, textRenderer, "Trains into the section", x, y);
        y += 11;
        JsonArray feeds = arr(section, "feeds");
        if (feeds.isEmpty()) {
            c.drawText(textRenderer, "No depot runs these lines.", x, y, FlatUi.TEXT_DIM, false);
            y += ROW;
        }
        for (JsonElement element : feeds) {
            JsonObject feed = element.getAsJsonObject();
            JsonObject depot = depot(str(feed, "depot"));
            JsonObject route = route(str(feed, "route"));
            if (depot == null) {
                continue;
            }
            int rx = x;
            if (route != null) {
                rx += swatch(c, route, rx, y) + 3;
            }
            c.drawText(textRenderer, textRenderer.trimToWidth(str(depot, "name"), w / 3), rx, y, FlatUi.TEXT, false);
            String detail;
            if (!bool(depot, "tunable")) {
                detail = str(depot, "reason");
            } else if (lng(feed, "travelMs") < 0) {
                detail = "no stop here yet";
            } else {
                detail = "every " + dur(lng(depot, "intervalMs")) + " · " + dur(lng(feed, "travelMs")) + " to entry"
                        + (lng(feed, "spreadMs") > 1500 ? " (±" + dur(lng(feed, "spreadMs") / 2) + ")" : "")
                        + " · delay +" + dur(lng(depot, "delayMs"));
            }
            int dw = textRenderer.getWidth(detail);
            int dx = Math.max(rx + textRenderer.getWidth(textRenderer.trimToWidth(str(depot, "name"), w / 3)) + 6, x + w - dw);
            c.drawText(textRenderer, textRenderer.trimToWidth(detail, x + w - dx), dx, y, FlatUi.TEXT_DIM, false);
            y += ROW;
        }
        y += 5;

        // Headways, as a picture: one tick per train reaching the first shared stop.
        FlatUi.heading(c, textRenderer, "Trains arriving at " + (stations.isEmpty() ? "the section" : stations.get(0).getAsString()), x, y);
        y += 11;
        long[] window = timelineWindow(section, reverse, null);
        y = timelineRow(c, "This way", replay(section, null), window, x, y, w, false, mx, my);
        if (reverse != null) {
            y = timelineRow(c, "Other way", replay(reverse, null), window, x, y, w, false, mx, my);
        }
        y = timelineAxis(c, window, x, y, w);
        JsonObject measured = obj(section, "measured");
        if (measured != null && num(measured, "samples") > 0) {
            String text = "Measured: avg " + dur(lng(measured, "avgMs")) + " · " + dur(lng(measured, "minMs")) + "–"
                    + dur(lng(measured, "maxMs")) + " · " + (int) num(measured, "samples") + ((int) num(measured, "samples") == 1 ? " gap" : " gaps");
            c.drawText(textRenderer, textRenderer.trimToWidth(text, w), x, y, FlatUi.TEXT_DIM, false);
        } else {
            c.drawText(textRenderer, "Measured: no departures recorded yet", x, y, FlatUi.TEXT_FAINT, false);
        }
        y += ROW + 6;

        // Suggest form.
        FlatUi.heading(c, textRenderer, "Suggest", x, y);
        y += 11;
        FlatUi.segmented(c, textRenderer, MODES, mode, x, y, Math.min(w, 200), 13, mx, my);
        segmentHits(x, y, Math.min(w, 200), 13, MODES.length, index -> mode = index);
        y += 17;
        if (mode == 1) {
            c.drawText(textRenderer, "Trunk headway", x, y + 3, FlatUi.TEXT_DIM, false);
            int bx = x + 72;
            targetBox.setBounds(bx, y, 60, 13);
            renderBox(c, targetBox, mx, my);
            String hint = hintForTarget();
            c.drawText(textRenderer, textRenderer.trimToWidth(hint, w - 140), bx + 66, y + 3, FlatUi.TEXT_FAINT, false);
            y += 17;
        }
        String[] directions = reverse == null ? new String[]{"This way", "Options"} : DIRECTIONS;
        int dirIndex = reverse == null ? (direction == 3 ? 1 : 0) : direction;
        FlatUi.segmented(c, textRenderer, directions, dirIndex, x, y, Math.min(w, 240), 13, mx, my);
        boolean hasReverse = reverse != null;
        segmentHits(x, y, Math.min(w, 240), 13, directions.length, index -> direction = hasReverse ? index : (index == 1 ? 3 : 1));
        y += 17;
        c.drawText(textRenderer, "Adjust", x, y + 3, FlatUi.TEXT_DIM, false);
        int lx = x + textRenderer.getWidth("Adjust") + 6;
        int lw = Math.min(w - (lx - x), 240);
        FlatUi.segmented(c, textRenderer, LEVERS, levers, lx, y, lw, 13, mx, my);
        segmentHits(lx, y, lw, 13, LEVERS.length, index -> levers = index);
        if (FlatUi.inside(mx, my, lx, y, lw, 13)) {
            tooltip = "Platform holds = longer dwell at a stop (before the section, or where the line turns back). "
                    + "They move one line in one direction, where a depot delay moves the depot's whole timetable.";
        }
        y += 17;

        // Depots the suggestion may move.
        c.drawText(textRenderer, "Depots it may adjust:", x, y + 1, FlatUi.TEXT_DIM, false);
        int gx = x + textRenderer.getWidth("Depots it may adjust:") + 6;
        List<String> feedingHere = feedingDepots(section, reverse);
        for (JsonElement element : arr(analysis, "groups")) {
            JsonObject group = element.getAsJsonObject();
            boolean relevant = false;
            for (String depotId : feedingHere) {
                relevant |= contains(arr(group, "depots"), depotId);
            }
            if (!relevant) {
                continue; // a group with no depot here would select nothing
            }
            String gid = str(group, "id");
            String label = str(group, "name");
            int cw = textRenderer.getWidth(label) + 8;
            if (gx + cw > x + w) {
                break;
            }
            boolean on = gid.equals(chosenGroup);
            FlatUi.chip(c, textRenderer, label, gx, y, on ? FlatUi.ACCENT_DIM : FlatUi.PANE_RAISED, false);
            hitClipped(gx, y, cw, 12, () -> applyGroupFilter(on ? "" : gid, section));
            gx += cw + 3;
        }
        y += 14;
        for (String depotId : feedingDepots(section, reverse)) {
            JsonObject depot = depot(depotId);
            if (depot == null) {
                continue;
            }
            boolean tunable = bool(depot, "tunable");
            boolean on = tunable && chosenDepots.contains(depotId);
            String label = str(depot, "name") + (tunable ? "" : " (" + str(depot, "reason") + ")");
            int before = y;
            y = checkbox(c, label, on, x + 6, y, mx, my, tunable ? () -> {
                if (!chosenDepots.remove(depotId)) {
                    chosenDepots.add(depotId);
                }
                chosenGroup = "";
            } : null);
            if (mode == 1 && tunable) {
                int share = weights.getOrDefault(depotId, 1);
                String chip = share + "× share";
                int cw = textRenderer.getWidth(chip) + 8;
                FlatUi.chip(c, textRenderer, chip, x + w - cw, before, FlatUi.PANE_RAISED, false);
                hitClipped(x + w - cw, before, cw, 12, () -> weights.put(depotId, share % 4 + 1));
            }
        }
        y += 3;
        boolean canSuggest = !chosenDepots.isEmpty() && (mode == 0 || parseDuration(targetBox.getText()) > 0);
        FlatUi.button(c, textRenderer, "Suggest", x, y, 70, 15, mx, my, FlatUi.ButtonStyle.PRIMARY, canSuggest);
        if (canSuggest) {
            hitClipped(x, y, 70, 15, () -> suggest(section));
        }
        y += 21;

        // Result.
        if (suggestion != null && str(suggestion, "section").equals(str(section, "id"))) {
            y = renderSuggestion(c, section, reverse, x, y, w, mx, my);
        } else if (suggestion != null && !bool(suggestion, "ok")) {
            y = wrap(c, str(suggestion, "error"), x, y, w, FlatUi.DANGER) + 4;
        }
        return y;
    }

    private int renderSuggestion(DrawContext c, JsonObject section, JsonObject reverse, int x, int y, int w, int mx, int my) {
        FlatUi.rect(c, x - 4, y - 3, w + 8, 1, FlatUi.BORDER);
        FlatUi.heading(c, textRenderer, "Suggestion", x, y);
        y += 11;
        for (JsonElement warning : arr(suggestion, "warnings")) {
            y = wrap(c, "⚠ " + warning.getAsString(), x, y, w, WARN_COLOR);
        }
        JsonObject target = obj(suggestion, "target");
        if (target != null && target.has("achievedMs")) {
            c.drawText(textRenderer, "Target " + dur(lng(target, "targetMs")) + " → achievable " + dur(lng(target, "achievedMs")),
                    x, y, FlatUi.TEXT, false);
            y += ROW;
        }
        if (!bool(suggestion, "unchanged")) {
            Overrides proposed = proposed();
            long[] window = timelineWindow(section, reverse, proposed);
            y = timelineRow(c, "This way", replay(section, null), window, x, y, w, false, mx, my);
            y = timelineRow(c, "\u2192 new", replay(section, proposed), window, x, y, w, true, mx, my);
            if (reverse != null) {
                y += 3;
                y = timelineRow(c, "Other way", replay(reverse, null), window, x, y, w, false, mx, my);
                y = timelineRow(c, "\u2192 new", replay(reverse, proposed), window, x, y, w, true, mx, my);
            }
            y = timelineAxis(c, window, x, y, w);
        }
        y += 3;

        // Options.
        JsonArray candidates = arr(suggestion, "candidates");
        boolean options = "options".equals(str(suggestion, "direction")) && candidates.size() > 1;
        if (options) {
            FlatUi.heading(c, textRenderer, "Options (click to choose)", x, y);
            y += 11;
            for (int i = 0; i < candidates.size(); i++) {
                JsonObject cand = candidates.get(i).getAsJsonObject();
                JsonObject stats = obj(cand, "stats");
                String text = (i + 1) + ".  this way " + pct(num(obj(stats, "fwd"), "evenness"))
                        + (stats.has("rev") ? " · other way " + pct(num(obj(stats, "rev"), "evenness")) : "");
                int index = i;
                listRowIn(c, x - 2, y - 1, w + 4, ROW, i == candidate, mx, my, () -> candidate = index);
                c.drawText(textRenderer, textRenderer.trimToWidth(text, w), x, y, i == candidate ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
                y += ROW;
            }
            y += 3;
        }

        // Changes.
        FlatUi.heading(c, textRenderer, "Changes", x, y);
        y += 11;
        JsonObject delays = chosenDelays();
        JsonObject frequencies = obj(suggestion, "frequencies");
        JsonObject headways = obj(suggestion, "headways");
        for (JsonElement id : arr(suggestion, "adjustable")) {
            JsonObject depot = depot(id.getAsString());
            if (depot == null) {
                continue;
            }
            String depotId = id.getAsString();
            long newDelay = delays != null && delays.has(depotId) ? delays.get(depotId).getAsLong() : lng(depot, "delayMs");
            StringBuilder text = new StringBuilder(str(depot, "name")).append(": delay +").append(dur(lng(depot, "delayMs")))
                    .append(" → +").append(dur(newDelay));
            if (frequencies != null && frequencies.has(depotId)) {
                long f = frequencies.get(depotId).getAsLong();
                text.append(" · frequency ").append(freqLabel(depot)).append(" → ").append(f)
                        .append(" (every ").append(dur(headways != null && headways.has(depotId) ? headways.get(depotId).getAsLong() : 0)).append(")");
            }
            y = wrap(c, text.toString(), x, y, w, FlatUi.TEXT);
        }
        JsonArray padList = arr(suggestion, "pads");
        for (JsonElement element : padList) {
            JsonObject pad = element.getAsJsonObject();
            JsonObject route = route(str(pad, "route"));
            String where = switch (str(pad, "kind")) {
                case "turnaround" -> "Hold at " + str(pad, "station") + ", where " + (route == null ? "the line" : str(route, "name")) + " turns back";
                case "origin" -> "Hold at " + str(pad, "station") + " (start of " + (route == null ? "the line" : str(route, "name")) + ")";
                default -> "Dwell at " + str(pad, "station") + " (" + (route == null ? "?" : str(route, "name")) + ")";
            };
            String text = where + ": " + dur(lng(pad, "currentDwellMs")) + " → " + dur(lng(pad, "dwellMs")) + " (+" + dur(lng(pad, "extraMs")) + ")";
            y = wrap(c, text, x, y, w, FlatUi.TEXT);
        }
        if (options && candidate > 0 && !padList.isEmpty()) {
            y = wrap(c, "The dwell changes were worked out for option 1 and are left out for this option.", x, y, w, FlatUi.TEXT_FAINT);
        }
        // Mixed frequencies: one click to put every depot on the same slider value.
        JsonArray matches = arr(suggestion, "matchOptions");
        if (!matches.isEmpty()) {
            FlatUi.heading(c, textRenderer, "Match frequencies for even spacing", x, y + 2);
            y += 13;
            int bx = x;
            for (JsonElement element : matches) {
                JsonObject match = element.getAsJsonObject();
                long headway = lng(match, "headwayMs");
                String label = "All at " + lng(match, "frequency") + " \u2192 every " + dur(headway);
                int bw = textRenderer.getWidth(label) + 10;
                if (bx + bw > x + w) {
                    bx = x;
                    y += 17;
                }
                FlatUi.button(c, textRenderer, label, bx, y, bw, 14, mx, my, FlatUi.ButtonStyle.FLAT);
                hitClipped(bx, y, bw, 14, () -> {
                    mode = 1;
                    targetBox.load(durInput(headway));
                    suggest(section);
                });
                bx += bw + 4;
            }
            y += 19;
        }
        JsonObject alt = target == null ? null : target;
        if (alt != null && alt.has("alternatives")) {
            StringBuilder text = new StringBuilder("Achievable nearby: ");
            int count = 0;
            for (JsonElement element : arr(alt, "alternatives")) {
                if (count++ > 0) {
                    text.append(", ");
                }
                text.append(dur(lng(element.getAsJsonObject(), "headwayMs")));
            }
            y = wrap(c, text.toString(), x, y, w, FlatUi.TEXT_FAINT);
        }
        y += 4;
        if (bool(suggestion, "unchanged")) {
            return y + 6;
        }
        FlatUi.button(c, textRenderer, "Apply", x, y, 70, 15, mx, my, FlatUi.ButtonStyle.PRIMARY);
        hitClipped(x, y, 70, 15, this::applySuggestion);
        c.drawText(textRenderer, textRenderer.trimToWidth("Writes delays" + (frequencies != null && !frequencies.isEmpty()
                        ? ", frequencies (all 24 hours)" : "") + (padList.isEmpty() || (options && candidate > 0) ? "" : " and dwell"),
                w - 78), x + 78, y + 4, FlatUi.TEXT_FAINT, false);
        y += 22;
        return y;
    }

    private JsonObject chosenDelays() {
        JsonArray candidates = arr(suggestion, "candidates");
        if ("options".equals(str(suggestion, "direction")) && candidate > 0 && candidate < candidates.size()) {
            return obj(candidates.get(candidate).getAsJsonObject(), "delays");
        }
        return obj(suggestion, "delays");
    }

    private void applySuggestion() {
        if (suggestion == null) {
            return;
        }
        JsonObject body = new JsonObject();
        JsonObject delays = chosenDelays();
        body.add("delays", delays == null ? new JsonObject() : delays.deepCopy());
        JsonObject frequencies = obj(suggestion, "frequencies");
        body.add("frequencies", frequencies == null ? new JsonObject() : frequencies.deepCopy());
        JsonArray dwell = new JsonArray();
        boolean options = "options".equals(str(suggestion, "direction")) && candidate > 0;
        if (!options) {
            for (JsonElement element : arr(suggestion, "pads")) {
                JsonObject pad = element.getAsJsonObject();
                JsonObject entry = new JsonObject();
                entry.addProperty("platform", str(pad, "platform"));
                entry.addProperty("route", str(pad, "route"));
                entry.addProperty("dwellMs", lng(pad, "dwellMs"));
                dwell.add(entry);
            }
        }
        body.add("dwell", dwell);
        sendApply(body);
    }

    private void suggest(JsonObject section) {
        JsonObject params = new JsonObject();
        params.addProperty("section", str(section, "id"));
        params.addProperty("mode", mode == 1 ? "target" : "even");
        params.addProperty("targetMs", mode == 1 ? parseDuration(targetBox.getText()) : 0);
        String dir = switch (direction) {
            case 1 -> "forward";
            case 2 -> "reverse";
            case 3 -> "options";
            default -> "balance";
        };
        params.addProperty("direction", dir);
        params.addProperty("levers", levers == 0 ? "delays" : levers == 1 ? "holds" : "both");
        JsonArray depots = new JsonArray();
        chosenDepots.forEach(depots::add);
        params.add("depots", depots);
        JsonObject weightsJson = new JsonObject();
        if (mode == 1) {
            chosenDepots.forEach(id -> weightsJson.addProperty(id, weights.getOrDefault(id, 1)));
        }
        params.add("weights", weightsJson);
        suggestRequest = send(params);
        status = "";
    }

    private void ensureForm(JsonObject section) {
        String id = str(section, "id");
        String reverseId = str(section, "reverse");
        if (id.equals(formSection) || (formSection != null && formSection.equals(reverseId))) {
            if (!id.equals(formSection)) {
                formSection = id; // keep the form when flipping to the other direction
            }
            return;
        }
        formSection = id;
        chosenDepots.clear();
        weights.clear();
        chosenGroup = "";
        for (String depotId : feedingDepots(section, findSection(reverseId))) {
            JsonObject depot = depot(depotId);
            if (depot != null && bool(depot, "tunable")) {
                chosenDepots.add(depotId);
            }
        }
    }

    private void applyGroupFilter(String groupId, JsonObject section) {
        chosenGroup = groupId;
        chosenDepots.clear();
        List<String> feeding = feedingDepots(section, findSection(str(section, "reverse")));
        JsonObject group = groupId.isEmpty() ? null : findGroup(groupId);
        for (String depotId : feeding) {
            JsonObject depot = depot(depotId);
            if (depot == null || !bool(depot, "tunable")) {
                continue;
            }
            if (group == null || contains(arr(group, "depots"), depotId)) {
                chosenDepots.add(depotId);
            }
        }
    }

    private List<String> feedingDepots(JsonObject section, JsonObject reverse) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement feed : arr(section, "feeds")) {
            ids.add(str(feed.getAsJsonObject(), "depot"));
        }
        if (reverse != null) {
            for (JsonElement feed : arr(reverse, "feeds")) {
                ids.add(str(feed.getAsJsonObject(), "depot"));
            }
        }
        return new ArrayList<>(ids);
    }

    private String hintForTarget() {
        long day = lng(analysis, "gameMillisPerDay");
        long target = parseDuration(targetBox.getText());
        if (day <= 0) {
            return "";
        }
        if (target <= 0) {
            return "one depot: " + dur(day / 6) + " or less";
        }
        return "= " + dur(target);
    }

    // ---- depots

    private int renderDepotList(DrawContext c, int mx, int my) {
        int y = paneTop + 2 - listScroll;
        JsonArray depots = arr(analysis, "depots");
        if (depots.isEmpty()) {
            c.drawText(textRenderer, "No depots", listX + 6, y + 4, FlatUi.TEXT_DIM, false);
            return y + 16;
        }
        if (selectedDepot == null || depot(selectedDepot) == null) {
            selectedDepot = str(depots.get(0).getAsJsonObject(), "id");
        }
        for (JsonElement element : depots) {
            JsonObject depot = element.getAsJsonObject();
            String id = str(depot, "id");
            boolean selected = id.equals(selectedDepot);
            listRow(c, y, 22, selected, mx, my, () -> {
                selectedDepot = id;
                detailScroll = 0;
            });
            c.drawText(textRenderer, textRenderer.trimToWidth(str(depot, "name"), listW - 12), listX + 5, y + 3,
                    selected ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            String sub = bool(depot, "tunable") ? "every " + dur(lng(depot, "intervalMs"))
                    + (lng(depot, "delayMs") > 0 ? " · +" + dur(lng(depot, "delayMs")) : "") : str(depot, "reason");
            c.drawText(textRenderer, textRenderer.trimToWidth(sub, listW - 12), listX + 5, y + 12, FlatUi.TEXT_FAINT, false);
            y += 22;
        }
        return y;
    }

    private int renderDepotDetail(DrawContext c, int mx, int my) {
        JsonObject depot = depot(selectedDepot);
        int x = detailX + 8;
        int w = detailW - 16;
        int y = paneTop + 6 - detailScroll;
        if (depot == null) {
            return y;
        }
        String id = str(depot, "id");
        c.drawText(textRenderer, textRenderer.trimToWidth(str(depot, "name"), w), x, y, FlatUi.TEXT, false);
        y += 12;
        int cx = x;
        for (JsonElement route : arr(depot, "routes")) {
            JsonObject info = route(route.getAsString());
            if (info != null && cx < x + w - 20) {
                cx += routeChip(c, info, cx, y) + 3;
            }
        }
        y += 16;
        if (!bool(depot, "tunable")) {
            y = wrap(c, "This depot uses a " + str(depot, "reason") + ", so its departures cannot be delayed.", x, y, w, FlatUi.TEXT_DIM);
            return y;
        }
        c.drawText(textRenderer, "Frequency " + freqLabel(depot) + " · a train every " + dur(lng(depot, "intervalMs"))
                + " · " + (int) num(depot, "sidings") + " sidings", x, y, FlatUi.TEXT_DIM, false);
        y += 16;

        FlatUi.heading(c, textRenderer, "Delay departures by", x, y);
        y += 11;
        if (!id.equals(delayBoxFor)) {
            delayBoxFor = id;
            long delay = lng(depot, "delayMs");
            delayBox.load(delay > 0 ? durInput(delay) : "");
        }
        delayBox.setBounds(x, y, 70, 14);
        renderBox(c, delayBox, mx, my);
        long typed = parseDuration(delayBox.getText());
        boolean valid = delayBox.getText().isBlank() || typed >= 0;
        FlatUi.button(c, textRenderer, "Save", x + 76, y, 44, 14, mx, my, FlatUi.ButtonStyle.PRIMARY, valid);
        if (valid) {
            hitClipped(x + 76, y, 44, 14, () -> saveDelay(id, Math.max(0, typed)));
        }
        FlatUi.button(c, textRenderer, "Clear", x + 124, y, 44, 14, mx, my, FlatUi.ButtonStyle.FLAT, lng(depot, "delayMs") > 0);
        if (lng(depot, "delayMs") > 0) {
            hitClipped(x + 124, y, 44, 14, () -> saveDelay(id, 0));
        }
        y += 18;
        long interval = lng(depot, "intervalMs");
        if (typed > 0 && interval > 0 && typed >= interval) {
            y = wrap(c, "More than one headway — the trains end up in the same place as a +" + dur(typed % interval) + " delay.",
                    x, y, w, FlatUi.TEXT_FAINT);
        }
        long applied = lng(depot, "appliedMs");
        String state = lng(depot, "delayMs") <= 0 ? "No delay." : applied == lng(depot, "delayMs") % Math.max(1, lng(analysis, "gameMillisPerDay"))
                ? "Written into today's departures." : "Waiting for the next departure rewrite.";
        y = wrap(c, state + " Seconds, m:ss or 1m30s. The whole timetable slides; the gap between this depot's own trains stays the same.",
                x, y, w, FlatUi.TEXT_FAINT);
        y += 8;

        FlatUi.heading(c, textRenderer, "Interline sections it feeds", x, y);
        y += 11;
        boolean any = false;
        for (JsonElement element : arr(analysis, "sections")) {
            JsonObject section = element.getAsJsonObject();
            boolean feeds = false;
            for (JsonElement feed : arr(section, "feeds")) {
                feeds |= str(feed.getAsJsonObject(), "depot").equals(id);
            }
            if (!feeds) {
                continue;
            }
            any = true;
            String sid = str(section, "id");
            listRowIn(c, x - 2, y - 1, w + 4, ROW, false, mx, my, () -> {
                tab = 0;
                selectSection(sid);
                listScroll = 0;
            });
            c.drawText(textRenderer, textRenderer.trimToWidth("→ " + str(section, "name"), w), x, y, FlatUi.ACCENT, false);
            y += ROW;
        }
        if (!any) {
            c.drawText(textRenderer, "None", x, y, FlatUi.TEXT_FAINT, false);
            y += ROW;
        }
        return y;
    }

    private void saveDelay(String depotId, long millis) {
        JsonObject body = new JsonObject();
        JsonObject delays = new JsonObject();
        delays.addProperty(depotId, millis);
        body.add("delays", delays);
        delayBoxFor = null;
        sendApply(body);
    }

    // ---- groups

    private int renderGroupList(DrawContext c, int mx, int my) {
        int y = paneTop + 2 - listScroll;
        listRow(c, y, 16, creatingGroup, mx, my, () -> {
            creatingGroup = true;
            selectedGroup = null;
            groupEditorFor = null;
            detailScroll = 0;
        });
        c.drawText(textRenderer, "+ New group", listX + 5, y + 4, FlatUi.ACCENT, false);
        y += 18;
        for (JsonElement element : arr(analysis, "groups")) {
            JsonObject group = element.getAsJsonObject();
            String id = str(group, "id");
            boolean selected = !creatingGroup && id.equals(selectedGroup);
            listRow(c, y, 22, selected, mx, my, () -> {
                selectedGroup = id;
                creatingGroup = false;
                detailScroll = 0;
            });
            c.drawText(textRenderer, textRenderer.trimToWidth(str(group, "name"), listW - 12), listX + 5, y + 3,
                    selected ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, arr(group, "depots").size() + " depots", listX + 5, y + 12, FlatUi.TEXT_FAINT, false);
            y += 22;
        }
        if (!creatingGroup && selectedGroup == null && !arr(analysis, "groups").isEmpty()) {
            selectedGroup = str(arr(analysis, "groups").get(0).getAsJsonObject(), "id");
        }
        return y;
    }

    private int renderGroupDetail(DrawContext c, int mx, int my) {
        int x = detailX + 8;
        int w = detailW - 16;
        int y = paneTop + 6 - detailScroll;
        JsonObject group = creatingGroup ? null : findGroup(selectedGroup);
        if (!creatingGroup && group == null) {
            y = wrap(c, "A group names depots that belong together. Groups no longer shift anything on their own: "
                    + "pick a group in a section's suggestion form to let the suggestion move only its depots.", x, y, w, FlatUi.TEXT_DIM);
            return y;
        }
        String key = creatingGroup ? "new" : str(group, "id");
        if (!key.equals(groupEditorFor)) {
            groupEditorFor = key;
            groupMembers.clear();
            groupNameBox.load(group == null ? "" : str(group, "name"));
            if (group != null) {
                for (JsonElement id : arr(group, "depots")) {
                    groupMembers.add(id.getAsString());
                }
            }
        }
        FlatUi.heading(c, textRenderer, creatingGroup ? "New group" : "Edit group", x, y);
        y += 11;
        groupNameBox.setBounds(x, y, Math.min(w, 180), 14);
        renderBox(c, groupNameBox, mx, my);
        y += 20;
        FlatUi.heading(c, textRenderer, "Depots", x, y);
        y += 11;
        for (JsonElement element : arr(analysis, "depots")) {
            JsonObject depot = element.getAsJsonObject();
            String id = str(depot, "id");
            y = checkbox(c, str(depot, "name") + (bool(depot, "tunable") ? "" : " (" + str(depot, "reason") + ")"),
                    groupMembers.contains(id), x, y, mx, my, () -> {
                        if (!groupMembers.remove(id)) {
                            groupMembers.add(id);
                        }
                    });
        }
        y += 4;
        boolean valid = !groupMembers.isEmpty();
        FlatUi.button(c, textRenderer, "Save", x, y, 50, 15, mx, my, FlatUi.ButtonStyle.PRIMARY, valid);
        if (valid) {
            long id = group == null ? 0 : parseLong(str(group, "id"));
            hitClipped(x, y, 50, 15, () -> {
                sendGroup(false, id, groupNameBox.getText(), groupMembers);
                creatingGroup = false;
                groupEditorFor = null;
            });
        }
        if (group != null) {
            long id = parseLong(str(group, "id"));
            FlatUi.button(c, textRenderer, "Delete", x + 56, y, 50, 15, mx, my, FlatUi.ButtonStyle.DANGER);
            hitClipped(x + 56, y, 50, 15, () -> {
                sendGroup(true, id, "", Set.of());
                selectedGroup = null;
                groupEditorFor = null;
            });
        }
        y += 22;
        if (group != null) {
            FlatUi.heading(c, textRenderer, "Sections two or more of its depots share", x, y);
            y += 11;
            boolean any = false;
            for (JsonElement element : arr(analysis, "sections")) {
                JsonObject section = element.getAsJsonObject();
                Set<String> members = new LinkedHashSet<>();
                for (JsonElement feed : arr(section, "feeds")) {
                    String depotId = str(feed.getAsJsonObject(), "depot");
                    if (contains(arr(group, "depots"), depotId)) {
                        members.add(depotId);
                    }
                }
                if (members.size() < 2) {
                    continue;
                }
                any = true;
                String sid = str(section, "id");
                String gid = str(group, "id");
                listRowIn(c, x - 2, y - 1, w + 4, ROW, false, mx, my, () -> {
                    tab = 0;
                    selectSection(sid);
                    JsonObject target = findSection(sid);
                    if (target != null) {
                        ensureForm(target);
                        applyGroupFilter(gid, target);
                    }
                });
                c.drawText(textRenderer, textRenderer.trimToWidth("→ Suggest for " + str(section, "name"), w), x, y, FlatUi.ACCENT, false);
                y += ROW;
            }
            if (!any) {
                c.drawText(textRenderer, "None yet", x, y, FlatUi.TEXT_FAINT, false);
                y += ROW;
            }
        }
        return y;
    }

    // ------------------------------------------------------------ pictures

    /** Text for a hover tooltip, drawn after the panes' scissor is lifted. */
    private String tooltip;

    /**
     * The section as a little line map: each line comes in from its previous stop (where
     * a suggested extra dwell would go, shown as a badge), merges into the shared stretch,
     * and runs through the shared stations (MTA-style capsules; hover for the name).
     */
    private int drawSectionMap(DrawContext c, JsonObject section, int x, int y, int w, int mx, int my) {
        JsonArray routeIds = arr(section, "routes");
        JsonArray stations = arr(section, "stations");
        int m = Math.max(1, routeIds.size());
        int n = stations.size();
        int approach = Math.min(110, Math.max(60, w * 3 / 10));
        int spread = 13;
        int top = y + 10;
        int trunkMid = top + (m - 1) * spread / 2;
        int bandTop = trunkMid - (3 * m - 1) / 2;
        int bandBottom = bandTop + 3 * m - 1;
        int x0 = x + approach;
        int x1 = x + w - 4;
        int bend = x0 - 16;
        for (int k = 0; k < routeIds.size(); k++) {
            String routeId = routeIds.get(k).getAsString();
            JsonObject route = route(routeId);
            int color = route == null ? FlatUi.TEXT_DIM : 0xFF000000 | (int) lng(route, "color");
            int yIn = top + k * spread;
            int ry = bandTop + k * 3;
            FlatUi.rect(c, x, yIn, bend - x, 2, color);
            diagonal(c, bend, yIn, x0, ry, color);
            FlatUi.rect(c, x0, ry, x1 - x0, 2, color);
            // Where this line comes from, plus any suggested extra dwell there.
            JsonObject prev = null;
            for (JsonElement element : arr(section, "prev")) {
                if (str(element.getAsJsonObject(), "route").equals(routeId)) {
                    prev = element.getAsJsonObject();
                }
            }
            JsonObject padInfo = padFor(routeId);
            long pad = padInfo == null ? 0 : lng(padInfo, "extraMs");
            int labelRight = bend - 2;
            if (pad > 0) {
                String badge = "before".equals(str(padInfo, "kind")) ? "+" + dur(pad) + " dwell"
                        : "+" + dur(pad) + " hold @ " + str(padInfo, "station");
                badge = textRenderer.trimToWidth(badge, Math.max(30, bend - x - 4));
                int bw = textRenderer.getWidth(badge) + 6;
                int bx = bend - bw - 2;
                FlatUi.rect(c, bx, yIn - 5, bw, 11, WARN_COLOR);
                c.drawText(textRenderer, badge, bx + 3, yIn - 3, 0xFF1A1A1A, false);
                labelRight = bx - 2;
            }
            String from = prev == null ? "starts here" : str(prev, "station");
            c.drawText(textRenderer, textRenderer.trimToWidth(from, Math.max(0, labelRight - x)), x, yIn - 9, FlatUi.TEXT_FAINT, false);
            if (route != null && FlatUi.inside(mx, my, x, yIn - 2, bend - x, 6)) {
                tooltip = str(route, "name") + (prev == null ? "" : " \u2014 from " + str(prev, "station") + ", dwell " + dur(lng(prev, "dwellMs")));
            }
        }
        // Stations: capsules across all the lines, names below where they fit.
        int lastLabelEnd = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int sx = n == 1 ? (x0 + x1) / 2 : x0 + 3 + (x1 - x0 - 6) * i / (n - 1);
            boolean roomy = n <= 1 || (x1 - x0 - 6) / (n - 1) >= 10;
            if (roomy || i == 0 || i == n - 1) {
                FlatUi.rect(c, sx - 3, bandTop - 3, 7, bandBottom - bandTop + 7, FlatUi.TEXT);
                FlatUi.rect(c, sx - 2, bandTop - 2, 5, bandBottom - bandTop + 5, FlatUi.PANE);
            } else {
                // Crowded: a thin tick per station keeps the stretch readable.
                FlatUi.rect(c, sx, bandTop - 3, 1, bandBottom - bandTop + 7, FlatUi.TEXT);
            }
            String name = stations.get(i).getAsString();
            if (FlatUi.inside(mx, my, sx - 4, bandTop - 4, 9, bandBottom - bandTop + 9)) {
                tooltip = name;
            }
            int tw = textRenderer.getWidth(name);
            int lx = i == 0 ? sx - 3 : i == n - 1 ? sx + 4 - tw : sx - tw / 2;
            boolean last = i == n - 1;
            if (lx > lastLabelEnd + 6 && (last || lx + tw < x1 - 40)) {
                c.drawText(textRenderer, name, lx, bandBottom + 6, FlatUi.TEXT_DIM, false);
                lastLabelEnd = lx + tw;
            }
        }
        return Math.max(top + (m - 1) * spread + 6, bandBottom + 16);
    }

    private void diagonal(DrawContext c, int ax, int ay, int bx, int by, int color) {
        int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay));
        for (int i = 0; i <= steps; i++) {
            int px = ax + (steps == 0 ? 0 : (bx - ax) * i / steps);
            int py = ay + (steps == 0 ? 0 : (by - ay) * i / steps);
            FlatUi.rect(c, px, py, 2, 2, color);
        }
    }

    /** The suggested platform hold that shifts this section route's trains, or null. */
    private JsonObject padFor(String routeId) {
        if (suggestion == null || bool(suggestion, "unchanged")
                || ("options".equals(str(suggestion, "direction")) && candidate > 0)) {
            return null;
        }
        for (JsonElement element : arr(suggestion, "pads")) {
            JsonObject pad = element.getAsJsonObject();
            if (str(pad, "forRoute").equals(routeId) || (str(pad, "forRoute").isEmpty() && str(pad, "route").equals(routeId))) {
                return pad;
            }
        }
        return null;
    }

    /** What a suggestion would change, for replaying the timetable. */
    private record Overrides(Map<String, Long> delays, Map<String, Long> frequencies, List<long[]> pads) {
    }

    private Overrides proposed() {
        Map<String, Long> delays = new HashMap<>();
        JsonObject chosen = chosenDelays();
        if (chosen != null) {
            chosen.entrySet().forEach(entry -> delays.put(entry.getKey(), entry.getValue().getAsLong()));
        }
        Map<String, Long> frequencies = new HashMap<>();
        JsonObject f = obj(suggestion, "frequencies");
        if (f != null) {
            f.entrySet().forEach(entry -> frequencies.put(entry.getKey(), entry.getValue().getAsLong()));
        }
        List<long[]> pads = new ArrayList<>();
        if (!("options".equals(str(suggestion, "direction")) && candidate > 0)) {
            for (JsonElement element : arr(suggestion, "pads")) {
                JsonObject pad = element.getAsJsonObject();
                pads.add(new long[]{parseLong(str(pad, "route")), lng(pad, "stopIndex"), lng(pad, "extraMs")});
            }
        }
        return new Overrides(delays, frequencies, pads);
    }

    /** One arrival: phase in the game day and the line's colour. */
    private record Tick(long t, int color) {
    }

    /**
     * Replays MTR's departure writer ({@link com.stationannouncer.mtraddon.interline.Timetable},
     * shared with the server's solver) for every depot feeding the section.
     */
    private List<Tick> replay(JsonObject section, Overrides overrides) {
        long day = lng(analysis, "gameMillisPerDay");
        List<Tick> ticks = new ArrayList<>();
        if (section == null || day <= 0) {
            return ticks;
        }
        for (JsonElement element : arr(section, "feeds")) {
            JsonObject feed = element.getAsJsonObject();
            String depotId = str(feed, "depot");
            JsonObject depot = depot(depotId);
            if (depot == null || !bool(depot, "tunable") || lng(feed, "travelMs") < 0) {
                continue;
            }
            long[] effective = new long[24];
            Long frequency = overrides == null ? null : overrides.frequencies().get(depotId);
            JsonArray eff = arr(depot, "effFreq");
            for (int i = 0; i < 24; i++) {
                effective[i] = frequency != null ? frequency : (i < eff.size() ? eff.get(i).getAsLong() : 0);
            }
            long delay = overrides != null && overrides.delays().containsKey(depotId)
                    ? overrides.delays().get(depotId) : lng(depot, "delayMs");
            long shift = lng(feed, "travelMs") + delay % day;
            if (overrides != null) {
                JsonArray routes = arr(depot, "routes");
                for (long[] pad : overrides.pads()) {
                    int position = -1;
                    for (int i = 0; i < routes.size(); i++) {
                        if (routes.get(i).getAsString().equals(Long.toString(pad[0]))) {
                            position = i;
                            break;
                        }
                    }
                    long routePos = lng(feed, "routePos");
                    boolean merged = pad[1] == 0 && contains(arr(depot, "mergedStarts"), Long.toString(pad[0]));
                    if (position >= 0 && !merged && (position < routePos || (position == routePos && pad[1] < lng(feed, "entryIndex")))) {
                        shift += pad[2];
                    }
                }
            }
            JsonObject route = route(str(feed, "route"));
            int color = route == null ? FlatUi.TEXT : 0xFF000000 | (int) lng(route, "color");
            for (long departure : com.stationannouncer.mtraddon.interline.Timetable.departures(effective, day)) {
                ticks.add(new Tick(Math.floorMod(departure + shift, day), color));
            }
        }
        ticks.sort((a, b) -> Long.compare(a.t, b.t));
        return ticks;
    }

    /** {start, length}: a daytime stretch four of the slowest depot's headways long. */
    private long[] timelineWindow(JsonObject section, JsonObject reverse, Overrides overrides) {
        long day = Math.max(1, lng(analysis, "gameMillisPerDay"));
        long longest = 0;
        for (JsonObject s : new JsonObject[]{section, reverse}) {
            if (s == null) {
                continue;
            }
            for (JsonElement element : arr(s, "feeds")) {
                String depotId = str(element.getAsJsonObject(), "depot");
                JsonObject depot = depot(depotId);
                if (depot == null || !bool(depot, "tunable")) {
                    continue;
                }
                Long frequency = overrides == null ? null : overrides.frequencies().get(depotId);
                long interval = frequency != null && frequency > 0
                        ? com.stationannouncer.mtraddon.interline.Timetable.headwayFor(frequency, day) : lng(depot, "intervalMs");
                longest = Math.max(longest, interval);
            }
        }
        long length = longest <= 0 ? day : Math.min(day, longest * 4);
        return new long[]{day * 8 / 24, length};
    }

    /** One strip: a tick per train, then how even the gaps are (the same measure the solver uses). */
    private int timelineRow(DrawContext c, String label, List<Tick> ticks, long[] window, int x, int y, int w,
                            boolean accent, int mx, int my) {
        long day = Math.max(1, lng(analysis, "gameMillisPerDay"));
        int labelW = Math.min(84, w / 4);
        int badgeW = 44;
        int sx = x + labelW;
        int sw = Math.max(20, w - labelW - badgeW);
        c.drawText(textRenderer, textRenderer.trimToWidth(label, labelW - 4), x, y + 1, accent ? FlatUi.ACCENT : FlatUi.TEXT_DIM, false);
        FlatUi.rect(c, sx, y, sw, 10, FlatUi.INPUT);
        FlatUi.outline(c, sx, y, sw, 10, FlatUi.BORDER);
        long[] times = new long[ticks.size()];
        for (int i = 0; i < times.length; i++) {
            Tick tick = ticks.get(i);
            times[i] = tick.t;
            long t = tick.t < window[0] ? tick.t + day : tick.t;
            if (t > window[0] + window[1]) {
                continue;
            }
            int px = sx + 1 + (int) ((t - window[0]) * (sw - 3) / Math.max(1, window[1]));
            FlatUi.rect(c, px, y + 2, 2, 6, tick.color);
        }
        var stats = com.stationannouncer.mtraddon.interline.Timetable.stats(times, day);
        if (stats.count() >= 2) {
            int color = evennessColor(stats.evenness(), stats.count());
            dot(c, sx + sw + 5, y + 2, color);
            c.drawText(textRenderer, pct(stats.evenness()), sx + sw + 13, y + 1, color, false);
            if (FlatUi.inside(mx, my, sx, y, sw + badgeW, 10)) {
                tooltip = label + ": a train every " + dur(stats.meanMs()) + " on average, gaps "
                        + dur(stats.minMs()) + "\u2013" + dur(stats.maxMs()) + " (" + evenLabel(stats.evenness()) + ")";
            }
        } else {
            c.drawText(textRenderer, "\u2014", sx + sw + 8, y + 1, FlatUi.TEXT_FAINT, false);
        }
        return y + 13;
    }

    private int timelineAxis(DrawContext c, long[] window, int x, int y, int w) {
        int labelW = Math.min(84, w / 4);
        int sx = x + labelW;
        int sw = Math.max(20, w - labelW - 44);
        String end = "+" + dur(window[1]);
        c.drawText(textRenderer, "+0s", sx, y, FlatUi.TEXT_FAINT, false);
        c.drawText(textRenderer, end, sx + sw - textRenderer.getWidth(end), y, FlatUi.TEXT_FAINT, false);
        String mid = "one tick = one train";
        int room = sw - textRenderer.getWidth("+0s") - textRenderer.getWidth(end) - 16;
        if (textRenderer.getWidth(mid) <= room) {
            c.drawText(textRenderer, mid, sx + (sw - textRenderer.getWidth(mid)) / 2, y, FlatUi.TEXT_FAINT, false);
        }
        return y + 12;
    }

    // ------------------------------------------------------------ widgets

    private void listRow(DrawContext c, int y, int h, boolean selected, int mx, int my, Runnable action) {
        listRowIn(c, listX + 1, y, listW - 2, h, selected, mx, my, action);
    }

    private void listRowIn(DrawContext c, int x, int y, int w, int h, boolean selected, int mx, int my, Runnable action) {
        boolean hovered = FlatUi.inside(mx, my, x, y, w, h) && my >= paneTop && my < paneTop + paneH;
        if (selected) {
            FlatUi.rect(c, x, y, w, h, FlatUi.SELECTED);
        } else if (hovered) {
            FlatUi.rect(c, x, y, w, h, FlatUi.HOVER);
        }
        hitClipped(x, y, w, h, action);
    }

    private int checkbox(DrawContext c, String label, boolean on, int x, int y, int mx, int my, Runnable toggle) {
        FlatUi.rect(c, x, y + 1, 8, 8, FlatUi.INPUT);
        FlatUi.outline(c, x, y + 1, 8, 8, toggle == null ? FlatUi.BORDER : FlatUi.BORDER_STRONG);
        if (on) {
            FlatUi.rect(c, x + 2, y + 3, 4, 4, FlatUi.ACCENT);
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(label, detailX + detailW - x - 30), x + 12, y + 1,
                toggle == null ? FlatUi.TEXT_FAINT : FlatUi.TEXT, false);
        if (toggle != null) {
            hitClipped(x, y, 12 + textRenderer.getWidth(label), 10, toggle);
        }
        return y + ROW;
    }

    private void segmentHits(int x, int y, int w, int h, int n, java.util.function.IntConsumer action) {
        for (int i = 0; i < n; i++) {
            int index = i;
            int cx = x + w * i / n;
            hitClipped(cx, y, x + w * (i + 1) / n - cx, h, () -> action.accept(index));
        }
    }

    private int swatch(DrawContext c, JsonObject route, int x, int y) {
        FlatUi.rect(c, x, y, 6, 6, 0xFF000000 | (int) lng(route, "color"));
        return 6;
    }

    private int routeChip(DrawContext c, JsonObject route, int x, int y) {
        String label = str(route, "number").isBlank() ? str(route, "name") : str(route, "number");
        label = textRenderer.trimToWidth(label, 70);
        return FlatUi.chip(c, textRenderer, label, x, y, 0xFF000000 | (int) lng(route, "color"), false);
    }

    private void dot(DrawContext c, int x, int y, int color) {
        FlatUi.rect(c, x + 1, y, 4, 6, color);
        FlatUi.rect(c, x, y + 1, 6, 4, color);
    }

    private void renderBox(DrawContext c, FlatUi.TextBox box, int mx, int my) {
        if (box.y + box.h > paneTop && box.y < paneTop + paneH) {
            box.render(c, mx, my);
        } else if (box.isFocused()) {
            box.setFocused(false);
        }
    }

    private int wrap(DrawContext c, String text, int x, int y, int w, int color) {
        for (var line : textRenderer.wrapLines(Text.literal(text), Math.max(20, w))) {
            c.drawText(textRenderer, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private void hit(int x, int y, int w, int h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    /** A hit inside a scrolling pane: only the part inside the pane's viewport counts. */
    private void hitClipped(int x, int y, int w, int h, Runnable action) {
        int top = Math.max(y, paneTop + 1);
        int bottom = Math.min(y + h, paneTop + paneH - 1);
        if (bottom > top) {
            hits.add(new Hit(x, top, w, bottom - top, action));
        }
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        FlatUi.TextBox[] boxes = activeBoxes();
        boolean inBox = false;
        for (FlatUi.TextBox box : boxes) {
            if (box.y + box.h > paneTop && box.y < paneTop + paneH && box.mouseClicked(mx, my, button)) {
                inBox = true;
                for (FlatUi.TextBox other : boxes) {
                    if (other != box) {
                        other.setFocused(false);
                    }
                }
            }
        }
        if (inBox) {
            return true;
        }
        for (FlatUi.TextBox box : boxes) {
            box.setFocused(false);
        }
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (FlatUi.inside(mx, my, hit.x, hit.y, hit.w, hit.h)) {
                    hit.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        for (FlatUi.TextBox box : activeBoxes()) {
            box.mouseDragged(mx, my);
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        for (FlatUi.TextBox box : activeBoxes()) {
            box.mouseReleased();
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        int step = (int) Math.round(-verticalAmount * 24);
        if (FlatUi.inside(mx, my, listX, paneTop, listW, paneH)) {
            listScroll = clamp(listScroll + step, listContent - paneH + 4);
            return true;
        }
        if (FlatUi.inside(mx, my, detailX, paneTop, detailW, paneH)) {
            detailScroll = clamp(detailScroll + step, detailContent - paneH + 12);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        for (FlatUi.TextBox box : activeBoxes()) {
            if (box.isFocused()) {
                if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_TAB) {
                    box.setFocused(false);
                    return true;
                }
                if (box.keyPressed(key, modifiers)) {
                    return true;
                }
                return true;
            }
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        for (FlatUi.TextBox box : activeBoxes()) {
            if (box.isFocused()) {
                return box.charTyped(chr);
            }
        }
        return super.charTyped(chr, modifiers);
    }

    private FlatUi.TextBox[] activeBoxes() {
        return switch (tab) {
            case 0 -> mode == 1 ? new FlatUi.TextBox[]{targetBox} : new FlatUi.TextBox[0];
            case 1 -> new FlatUi.TextBox[]{delayBox};
            default -> new FlatUi.TextBox[]{groupNameBox};
        };
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(Math.max(0, max), value));
    }

    // ------------------------------------------------------------ data helpers

    private JsonObject findSection(String id) {
        if (id == null || id.isEmpty() || analysis == null) {
            return null;
        }
        for (JsonElement element : arr(analysis, "sections")) {
            if (id.equals(str(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private JsonObject findGroup(String id) {
        if (id == null || analysis == null) {
            return null;
        }
        for (JsonElement element : arr(analysis, "groups")) {
            if (id.equals(str(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private final Map<String, JsonObject> depotCache = new LinkedHashMap<>();
    private final Map<String, JsonObject> routeCache = new LinkedHashMap<>();
    private JsonObject cachedFor;

    private void index() {
        if (cachedFor == analysis) {
            return;
        }
        cachedFor = analysis;
        depotCache.clear();
        routeCache.clear();
        for (JsonElement element : arr(analysis, "depots")) {
            depotCache.put(str(element.getAsJsonObject(), "id"), element.getAsJsonObject());
        }
        for (JsonElement element : arr(analysis, "routes")) {
            routeCache.put(str(element.getAsJsonObject(), "id"), element.getAsJsonObject());
        }
    }

    private JsonObject depot(String id) {
        index();
        return id == null ? null : depotCache.get(id);
    }

    private JsonObject route(String id) {
        index();
        return id == null ? null : routeCache.get(id);
    }

    private String freqLabel(JsonObject depot) {
        long uniform = lng(depot, "uniformFreq");
        return uniform >= 0 ? Long.toString(uniform) : "varies by hour";
    }

    private static String joinStations(JsonArray stations) {
        List<String> names = new ArrayList<>();
        for (JsonElement station : stations) {
            names.add(station.getAsString());
        }
        return String.join(", ", names);
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static int evennessColor(double evenness, int count) {
        if (count < 2) {
            return FlatUi.TEXT_FAINT;
        }
        return evenness < 0.1 ? OK_COLOR : evenness < 0.3 ? WARN_COLOR : FlatUi.DANGER;
    }

    /** RMS spread of the gaps around their mean, as a readable word + percentage. */
    private static String evenLabel(double evenness) {
        String word = evenness < 0.1 ? "even" : evenness < 0.3 ? "uneven" : "bunched";
        return word + " ±" + Math.round(evenness * 100) + "%";
    }

    private static String pct(double evenness) {
        return "±" + Math.round(evenness * 100) + "%";
    }

    static String dur(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        double seconds = millis / 1000.0;
        if (seconds < 60) {
            return seconds < 10 || Math.abs(seconds - Math.round(seconds)) > 0.05
                    ? String.format(Locale.ROOT, "%.1fs", seconds) : Math.round(seconds) + "s";
        }
        long whole = Math.round(seconds);
        return (whole / 60) + "m " + (whole % 60) + "s";
    }

    private static String durInput(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return seconds >= 60 ? (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60) : Long.toString(seconds);
    }

    /** "90", "90s", "1:30", "1m30s", "1m 30s", "2m", "1.5" → millis; -1 when unreadable. */
    static long parseDuration(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) {
            return -1;
        }
        try {
            if (s.contains(":")) {
                String[] parts = s.split(":");
                if (parts.length != 2) {
                    return -1;
                }
                return Math.round((Long.parseLong(parts[0]) * 60 + Double.parseDouble(parts[1])) * 1000);
            }
            double total = 0;
            if (s.contains("m")) {
                int m = s.indexOf('m');
                total += Double.parseDouble(s.substring(0, m)) * 60;
                s = s.substring(m + 1);
            }
            if (s.endsWith("s")) {
                s = s.substring(0, s.length() - 1);
            }
            if (!s.isEmpty()) {
                total += Double.parseDouble(s);
            }
            return total < 0 ? -1 : Math.round(total * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static JsonObject obj(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }

    private static JsonArray arr(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static long lng(JsonObject json, String key) {
        try {
            return json != null && json.has(key) ? json.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double num(JsonObject json, String key) {
        try {
            return json != null && json.has(key) ? json.get(key).getAsDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).getAsBoolean();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
