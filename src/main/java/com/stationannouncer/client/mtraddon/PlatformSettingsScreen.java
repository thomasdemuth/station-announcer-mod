package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.gui.FlatPaScreen;
import com.stationannouncer.client.mtr.PlatformPicker;
import com.stationannouncer.mtraddon.AddonNetworking;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.PlatformOccupancy;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Our replacement for MTR's platform screen (FlatUi, no vanilla widgets): platform number,
 * the platform's default dwell, every route's dwell here (tagged Default / Manual /
 * Interlining, so the holds Interlining applied are visible and editable in the one place
 * people look for dwell), the connection hold rule, platform groups per stop, and a
 * schedule check — the platform's occupancy over the game day from the sidings' real
 * departures and stop times ({@link ClientPlatformSchedule}), re-timed live while the
 * sliders move, with red where a train would arrive while another still dwells (amber =
 * only if the hold rule holds its full window).
 *
 * <p>Save writes the number / default dwell through MTR's own {@code PacketUpdateData}
 * (exactly what MTR's screen sends on close), then the hold rule, groups and per-route
 * dwell through the addon packets; the dwell packet asks the server to regenerate the
 * depots calling here when anything baked into the path changed. Esc saves too, like
 * MTR's screen; Cancel discards.</p>
 */
@Environment(EnvType.CLIENT)
public class PlatformSettingsScreen extends FlatPaScreen {
    private static final int HIT_SAVE = 1;
    private static final int HIT_CANCEL = 2;
    private static final int HIT_MTR = 3;
    private static final int HIT_ROW = 4;
    private static final int HIT_HOLD_ROW = 5;
    private static final int HIT_DEFAULT_STEP = 6;
    private static final int HIT_OWN_MODE = 7;
    private static final int HIT_OWN_STEP = 8;
    private static final int HIT_RESET = 9;
    private static final int HIT_HOLD_TOGGLE = 10;
    private static final int HIT_HOLD_CHOOSE = 11;
    private static final int HIT_GROUP_EDIT = 12;
    private static final int HIT_GROUP_CLEAR = 13;
    private static final int HIT_WINDOW = 14;
    private static final int HIT_REFRESH = 15;
    private static final int HIT_LANE = 16;
    private static final int HIT_MODAL_BACKDROP = 17;
    private static final int HIT_MODAL_DONE = 18;
    private static final int HIT_MODAL_CLEAR = 19;
    private static final int HIT_MODAL_SWALLOW = 20;
    private static final int HIT_LEFT_PANE = 21;

    private static final int K_DEFAULT = 1;
    private static final int K_OWN = 2;
    private static final int K_HOLD_WINDOW = 3;
    private static final int K_HOLD_TRANSFER = 4;

    /** Half-second slider steps, MTR's own dwell range (0.5 s – 600 s); ours floor at 1 s. */
    private static final int MAX_HALF_SECONDS = 1_200;
    private static final int MIN_OWN_HALF_SECONDS = 2;

    private static final int AMBER = 0xFFE5A23A;
    private static final int TAG_DEFAULT = 0xFF3A3A44;
    private static final int TAG_INTERLINE = 0xFF7A5414;
    private static final int TAG_LEGACY = 0xFF4A4A58;
    private static final String[] WINDOW_LABELS = {"5 min", "10 min", "Cycle"};
    private static final long[] WINDOW_MILLIS = {300_000L, 600_000L, 0L};
    private static final long REFRESH_MILLIS = 10_000L;

    /** Set by "MTR screen": the next PlatformScreen init is left alone. */
    private static boolean bypassOnce;

    /** One route calling here, with its stops (for platform groups) and its dwell state. */
    private static final class Row {
        final Route route;
        final String name;
        final List<Integer> stopIndices = new ArrayList<>();
        final List<Integer> stopCounts = new ArrayList<>();
        final boolean hadOwn;
        final long storedMillis;
        final byte storedSource;
        boolean own;
        int ownHalfSeconds;
        boolean edited;

        Row(Route route, String name, Long storedMillis, byte storedSource, long defaultMillis) {
            this.route = route;
            this.name = name;
            this.hadOwn = storedMillis != null;
            this.storedMillis = storedMillis == null ? 0 : storedMillis;
            this.storedSource = storedSource;
            this.own = hadOwn;
            this.ownHalfSeconds = halfSeconds(hadOwn ? storedMillis : defaultMillis, MIN_OWN_HALF_SECONDS);
        }

        long ownMillis() {
            return edited || !hadOwn ? ownHalfSeconds * 500L : storedMillis;
        }
    }

    private final Platform platform;
    private final Screen previous;
    private final Screen mtrScreen;
    private final boolean scheduleControls;
    private final String stationName;
    private final List<Row> rows = new ArrayList<>();
    private final Map<Long, Row> rowsByRoute = new HashMap<>();
    private final FlatUi.TextBox numberBox;

    private final long originalDefaultMillis;
    private int defaultHalfSeconds;
    private boolean defaultEdited;

    // Hold rule (one per platform).
    private final ClientHoldRules.Rule originalRule;
    private boolean holdOn;
    private int holdSeconds;
    private int holdTransfer;
    private final Set<Long> holdWatched = new LinkedHashSet<>();

    // Platform groups: "routeId:stopIndex" → members (edited copies).
    private final Map<String, List<Long>> groups = new LinkedHashMap<>();
    private final Map<String, List<Long>> originalGroups = new LinkedHashMap<>();

    /** -1 = hold rule, -2 = overview, ≥ 0 = route row. */
    private int selected = -2;
    private int listScroll;
    private int listContent;
    private int inspectorScroll;
    private int inspectorContent;
    private int leftX, leftY, leftW, leftH;
    private int rightX, rightY, rightW, rightH;

    // Schedule.
    private int windowIndex = 1;
    private long lastRequest;
    private int seenVersion = -1;
    private long planKey;
    private PlatformOccupancy.Result result;
    private ClientPlatformSchedule.Data data;
    private int laneX, laneW, laneTop, laneH, lanesTop;
    private long windowStart, windowLength;

    // Modal platform picker (hold-rule watch list or a group's members).
    private PlatformPicker picker;
    private String pickerKey; // null = hold rule, else the group key
    private String pickerTitle;

    public PlatformSettingsScreen(Platform platform, Screen previous, Screen mtrScreen) {
        super(Text.literal("Platform"));
        this.platform = platform;
        this.previous = previous;
        this.mtrScreen = mtrScreen;
        this.scheduleControls = !platform.getTransportMode().continuousMovement;
        this.stationName = platform.area == null ? "" : IGui.formatStationName(platform.area.getName());
        this.numberBox = new FlatUi.TextBox(MinecraftClient.getInstance().textRenderer, 10, false);
        numberBox.load(platform.getName());

        this.originalDefaultMillis = platform.getDwellTime();
        this.defaultHalfSeconds = halfSeconds(originalDefaultMillis, 1);

        Map<Long, Long> stored = ClientDwellOverrides.get(platform.getId());
        for (Route route : MinecraftClientData.getDashboardInstance().routes) {
            Row row = null;
            List<RoutePlatformData> stops = route.getRoutePlatforms();
            for (int i = 0; i < stops.size(); i++) {
                Platform stopPlatform = stops.get(i).platform;
                if (stopPlatform == null || stopPlatform.getId() != platform.getId()) {
                    continue;
                }
                if (row == null) {
                    Long millis = stored == null ? null : stored.get(route.getId());
                    row = new Row(route, IGui.formatStationName(route.getName()), millis,
                            ClientDwellOverrides.source(platform.getId(), route.getId()), originalDefaultMillis);
                }
                row.stopIndices.add(i);
                row.stopCounts.add(stops.size());
                String key = route.getId() + ":" + i;
                List<Long> members = ClientPlatformGroups.get(route.getId(), i);
                if (members != null && !members.isEmpty()) {
                    groups.put(key, new ArrayList<>(members));
                    originalGroups.put(key, new ArrayList<>(members));
                }
            }
            if (row != null && rows.size() < AddonNetworking.MAX_ROUTE_OVERRIDES) {
                rows.add(row);
                rowsByRoute.put(route.getId(), row);
            }
        }
        rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        this.originalRule = ClientHoldRules.get(platform.getId());
        this.holdOn = originalRule != null && !originalRule.watched().isEmpty();
        this.holdSeconds = originalRule == null ? 30 : originalRule.seconds();
        this.holdTransfer = originalRule == null ? AddonNetworking.DEFAULT_TRANSFER_SECONDS : originalRule.transferSeconds();
        if (originalRule != null) {
            holdWatched.addAll(originalRule.watched());
        }
    }

    /** Consumed by the screen swap: true once after "MTR screen" was pressed. */
    static boolean consumeBypass() {
        boolean value = bypassOnce;
        bypassOnce = false;
        return value;
    }

    // ================================================================ data

    private long defaultMillis() {
        return defaultEdited ? defaultHalfSeconds * 500L : originalDefaultMillis;
    }

    /** The dwell a route will get here after Save. */
    private long plannedDwell(long routeId) {
        Row row = rowsByRoute.get(routeId);
        if (row == null) {
            Map<Long, Long> stored = ClientDwellOverrides.get(platform.getId());
            Long millis = stored == null ? null : stored.get(routeId);
            return millis != null ? millis : defaultMillis();
        }
        return row.own ? row.ownMillis() : defaultMillis();
    }

    private long holdExtraMillis() {
        return holdOn && !holdWatched.isEmpty() ? (holdSeconds + holdTransfer) * 1_000L : 0;
    }

    /** Re-times the day when the reply or any edit changed. */
    private void updateResult() {
        long now = System.currentTimeMillis();
        if (now - lastRequest > REFRESH_MILLIS) {
            lastRequest = now;
            ClientPlatformSchedule.request(platform.getId());
        }
        ClientPlatformSchedule.Data fresh = ClientPlatformSchedule.get(platform.getId());
        long key = Objects.hash(defaultMillis(), holdExtraMillis());
        for (Row row : rows) {
            key = key * 31 + (row.own ? row.ownMillis() : -1);
        }
        if (fresh == null || (ClientPlatformSchedule.version() == seenVersion && key == planKey && result != null)) {
            data = fresh;
            return;
        }
        data = fresh;
        seenVersion = ClientPlatformSchedule.version();
        planKey = key;
        long extra = holdExtraMillis();
        result = PlatformOccupancy.compute(fresh.sidings(), fresh.day(), new PlatformOccupancy.Plan() {
            @Override
            public long dwell(long routeId, long bakedDwell) {
                return plannedDwell(routeId);
            }

            @Override
            public long holdExtra() {
                return extra;
            }
        });
    }

    /** {hard as blocker, hard as blocked, maybe, worst wait caused} for one route. */
    private long[] conflictCounts(long routeId) {
        long[] out = new long[4];
        if (result == null) {
            return out;
        }
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            boolean blocker = conflict.blocker().routeId() == routeId;
            boolean blocked = conflict.blocked().routeId() == routeId;
            if (!blocker && !blocked) {
                continue;
            }
            if (conflict.maybe()) {
                out[2]++;
            } else {
                if (blocker) {
                    out[0]++;
                    out[3] = Math.max(out[3], conflict.waitMillis());
                }
                if (blocked && !blocker) {
                    out[1]++;
                }
            }
        }
        return out;
    }

    /** Whether trains on this route still run the old (baked) dwell here. */
    private long bakedDwell(long routeId) {
        if (data == null) {
            return -1;
        }
        for (PlatformOccupancy.Siding siding : data.sidings()) {
            for (PlatformOccupancy.Stop stop : siding.stops()) {
                if (stop.routeId() == routeId) {
                    return stop.baked();
                }
            }
        }
        return -1;
    }

    // ================================================================ drawing

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        updateResult();
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);

        // Top bar.
        c.drawText(textRenderer, "Platform", 8, 9, FlatUi.TEXT, false);
        int x = 8 + textWidth("Platform") + 6;
        box(c, numberBox, x, 5, 34, 16, mx, my);
        x += 40;
        int buttonsW = 50 + 4 + 50 + 4 + 62;
        c.drawText(textRenderer, trim(stationName, Math.max(10, width - x - buttonsW - 16)), x, 9, FlatUi.TEXT_DIM, false);
        button(c, mx, my, "Save", width - 56, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.PRIMARY, true, HIT_SAVE, 0);
        button(c, mx, my, "Cancel", width - 110, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.FLAT, true, HIT_CANCEL, 0);
        if (mtrScreen != null) {
            button(c, mx, my, "MTR screen", width - 176, 4, 62, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.GHOST, true, HIT_MTR, 0);
        }

        int top = 26;
        int timelineH = scheduleControls ? MathHelper.clamp(Math.round(height * 0.34f), 66, 140) : 0;
        int bodyBottom = height - 6 - (timelineH > 0 ? timelineH + 6 : 0);
        leftW = MathHelper.clamp(Math.round(width * 0.44f), 180, 300);
        leftX = 6;
        leftY = top;
        leftH = bodyBottom - top;
        rightX = leftX + leftW + 6;
        rightY = top;
        rightW = width - rightX - 6;
        rightH = leftH;

        FlatUi.pane(c, leftX, leftY, leftW, leftH);
        beginClip(c, leftX + 1, leftY + 1, leftW - 2, leftH - 2);
        int end = drawList(c, mx, my, leftX + 6, leftY + 5 - listScroll, leftW - 12);
        listContent = end + listScroll - leftY + 4;
        endClip(c);
        listScroll = MathHelper.clamp(listScroll, 0, Math.max(0, listContent - leftH));
        FlatUi.scrollThumb(c, leftX + leftW - 1, leftY + 1, leftH - 2, listContent, listScroll);

        FlatUi.pane(c, rightX, rightY, rightW, rightH);
        beginClip(c, rightX + 1, rightY + 1, rightW - 2, rightH - 2);
        end = drawInspector(c, mx, my, rightX + 8, rightY + 6 - inspectorScroll, rightW - 16);
        inspectorContent = end + inspectorScroll - rightY + 4;
        endClip(c);
        inspectorScroll = MathHelper.clamp(inspectorScroll, 0, Math.max(0, inspectorContent - rightH));
        FlatUi.scrollThumb(c, rightX + rightW - 1, rightY + 1, rightH - 2, inspectorContent, inspectorScroll);

        if (timelineH > 0) {
            int ty = bodyBottom + 6;
            FlatUi.pane(c, 6, ty, width - 12, timelineH);
            drawTimeline(c, mx, my, 6, ty, width - 12, timelineH);
        }

        if (picker != null) {
            drawPickerModal(c, mx, my);
        }
    }

    // ------------------------------------------------------------ left list

    private int drawList(DrawContext c, int mx, int my, int x, int y, int w) {
        if (scheduleControls) {
            FlatUi.heading(c, textRenderer, "Default dwell", x, y);
            y += 12;
            slider(c, mx, my, K_DEFAULT, "", 0, defaultHalfSeconds, 1, MAX_HALF_SECONDS,
                    formatDwell(defaultMillis()), x, y, w - 30);
            stepButtons(c, mx, my, x + w - 26, y, HIT_DEFAULT_STEP);
            y += 15;
            c.drawText(textRenderer, trim("Routes without their own dwell use this", w), x, y, FlatUi.TEXT_FAINT, false);
            y += 13;
        } else {
            c.drawText(textRenderer, trim("Continuous movement: no dwell or timetable", w), x, y, FlatUi.TEXT_DIM, false);
            y += 13;
        }

        FlatUi.heading(c, textRenderer, "Routes here", x, y);
        y += 12;
        if (rows.isEmpty()) {
            c.drawText(textRenderer, "No routes call at this platform", x, y, FlatUi.TEXT_FAINT, false);
            y += 12;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int rh = scheduleControls ? 24 : 13;
            boolean hovered = picker == null && FlatUi.inside(mx, my, x - 3, y, w + 6, rh) && my >= leftY && my < leftY + leftH;
            if (selected == i) {
                FlatUi.rect(c, x - 3, y, w + 6, rh, FlatUi.SELECTED);
            } else if (hovered) {
                FlatUi.rect(c, x - 3, y, w + 6, rh, FlatUi.HOVER);
            }
            FlatUi.rect(c, x, y + 2, 3, rh - 4, 0xFF000000 | row.route.getColor());
            String badge = "";
            int badgeColor = FlatUi.TEXT_FAINT;
            if (scheduleControls && result != null) {
                long[] counts = conflictCounts(row.route.getId());
                if (counts[0] + counts[1] > 0) {
                    badge = "⚠ " + (counts[0] + counts[1]);
                    badgeColor = FlatUi.DANGER;
                } else if (counts[2] > 0) {
                    badge = "? " + counts[2];
                    badgeColor = AMBER;
                } else if (result.visits().stream().anyMatch(visit -> visit.routeId() == row.route.getId())) {
                    badge = "✓";
                    badgeColor = FlatUi.OK;
                } else {
                    badge = "—"; // no timetabled stops here (real-time / continuous depot)
                }
            }
            int badgeW = badge.isEmpty() ? 0 : textWidth(badge) + 4;
            c.drawText(textRenderer, trim(row.name, w - 10 - badgeW), x + 7, y + 3, FlatUi.TEXT, false);
            if (!badge.isEmpty()) {
                c.drawText(textRenderer, badge, x + w - badgeW + 4, y + 3, badgeColor, false);
            }
            if (scheduleControls) {
                long planned = plannedDwell(row.route.getId());
                String dwell = formatDwell(planned);
                c.drawText(textRenderer, dwell, x + 7, y + 13, FlatUi.TEXT_DIM, false);
                int tx = x + 7 + textWidth(dwell) + 5;
                String tag = tagLabel(row);
                int tagW = FlatUi.chip(c, textRenderer, tag, tx, y + 11, tagColor(row), false);
                long baked = bakedDwell(row.route.getId());
                if (baked >= 0 && Math.abs(baked - planned) >= 500) {
                    c.drawText(textRenderer, trim("· now " + formatDwell(baked), Math.max(0, w - (tx - x) - tagW - 6)),
                            tx + tagW + 4, y + 13, AMBER, false);
                }
            }
            hit(x - 3, y, w + 6, rh, HIT_ROW, i);
            y += rh + 2;
        }

        if (scheduleControls) {
            y += 4;
            FlatUi.heading(c, textRenderer, "Connection hold", x, y);
            y += 12;
            int rh = 14;
            boolean hovered = picker == null && FlatUi.inside(mx, my, x - 3, y, w + 6, rh);
            if (selected == -1) {
                FlatUi.rect(c, x - 3, y, w + 6, rh, FlatUi.SELECTED);
            } else if (hovered) {
                FlatUi.rect(c, x - 3, y, w + 6, rh, FlatUi.HOVER);
            }
            String summary = holdOn && !holdWatched.isEmpty()
                    ? "Holds ≤ " + holdSeconds + " s for " + holdWatched.size() + " platform" + (holdWatched.size() == 1 ? "" : "s")
                    + (holdTransfer > 0 ? ", +" + holdTransfer + " s" : "")
                    : "Off — trains leave on time";
            c.drawText(textRenderer, trim(summary, w - 4), x + 2, y + 3, holdOn ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hit(x - 3, y, w + 6, rh, HIT_HOLD_ROW, 0);
            y += rh + 2;
        }
        return y;
    }

    private void stepButtons(DrawContext c, int mx, int my, int x, int y, int id) {
        FlatUi.iconButton(c, textRenderer, "−", x, y, 12, mx, my, FlatUi.TEXT_DIM);
        hit(x, y, 12, 12, id, -1);
        FlatUi.iconButton(c, textRenderer, "+", x + 14, y, 12, mx, my, FlatUi.TEXT_DIM);
        hit(x + 14, y, 12, 12, id, 1);
    }

    private String tagLabel(Row row) {
        if (!row.own) {
            return "Default";
        }
        if (row.edited || !row.hadOwn) {
            return "Manual";
        }
        return switch (row.storedSource) {
            case AddonStore.DWELL_SOURCE_INTERLINE -> "Interlining";
            case AddonStore.DWELL_SOURCE_MANUAL -> "Manual";
            default -> "Own";
        };
    }

    private int tagColor(Row row) {
        return switch (tagLabel(row)) {
            case "Default" -> TAG_DEFAULT;
            case "Interlining" -> TAG_INTERLINE;
            case "Manual" -> FlatUi.ACCENT_DIM;
            default -> TAG_LEGACY;
        };
    }

    // ------------------------------------------------------------ inspector

    private int drawInspector(DrawContext c, int mx, int my, int x, int y, int w) {
        if (selected >= 0 && selected < rows.size()) {
            return drawRouteInspector(c, mx, my, rows.get(selected), x, y, w);
        }
        if (selected == -1) {
            return drawHoldInspector(c, mx, my, x, y, w);
        }
        return drawOverview(c, x, y, w);
    }

    private int drawOverview(DrawContext c, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "This platform", x, y);
        y += 13;
        y = para(c, rows.size() + (rows.size() == 1 ? " route calls here." : " routes call here."), x, y, w, FlatUi.TEXT_DIM);
        if (!scheduleControls) {
            return para(c, "Continuous-movement platforms have no dwell or timetable.", x, y, w, FlatUi.TEXT_FAINT);
        }
        if (result == null) {
            return para(c, data == null ? "Loading the timetable…" : "No timetabled trains stop here.", x, y, w, FlatUi.TEXT_FAINT);
        }
        int hard = 0;
        int maybe = 0;
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            if (conflict.maybe()) {
                maybe++;
            } else {
                hard++;
            }
        }
        y = para(c, result.visits().size() + " stops per " + formatDwell(result.day()) + " timetable cycle.", x, y, w, FlatUi.TEXT_DIM);
        if (hard == 0 && maybe == 0) {
            y = para(c, "✓ No train has to wait for this platform.", x, y, w, FlatUi.OK);
        } else {
            if (hard > 0) {
                y = para(c, "⚠ " + hard + " time" + (hard == 1 ? "" : "s") + " per cycle a train arrives while another still dwells.",
                        x, y, w, FlatUi.DANGER);
            }
            if (maybe > 0) {
                y = para(c, "? " + maybe + " more if the hold rule waits its full window.", x, y, w, AMBER);
            }
        }
        y += 4;
        y = para(c, "Pick a route to change its dwell, or the connection hold.", x, y, w, FlatUi.TEXT_FAINT);
        if (data != null && !data.note().isEmpty()) {
            y = para(c, data.note(), x, y, w, FlatUi.TEXT_FAINT);
        }
        return y;
    }

    private int drawRouteInspector(DrawContext c, int mx, int my, Row row, int x, int y, int w) {
        FlatUi.rect(c, x, y, 3, 10, 0xFF000000 | row.route.getColor());
        c.drawText(textRenderer, trim(row.name, w - 6), x + 6, y + 1, FlatUi.TEXT, false);
        y += 15;

        if (scheduleControls) {
            FlatUi.heading(c, textRenderer, "Dwell at this platform", x, y);
            y += 12;
            String[] labels = {"Platform default", "Own dwell"};
            FlatUi.segmented(c, textRenderer, labels, row.own ? 1 : 0, x, y, w, 15, mx, my);
            hit(x, y, w, 15, HIT_OWN_MODE, 0);
            y += 19;
            if (row.own) {
                slider(c, mx, my, K_OWN, "", 0, row.ownHalfSeconds, MIN_OWN_HALF_SECONDS, MAX_HALF_SECONDS,
                        formatDwell(row.ownMillis()), x, y, w - 30);
                stepButtons(c, mx, my, x + w - 26, y, HIT_OWN_STEP);
                y += 16;
                long delta = row.ownMillis() - defaultMillis();
                String source = switch (tagLabel(row)) {
                    case "Interlining" -> "Set by Interlining";
                    case "Manual" -> "Set by hand";
                    default -> "Set earlier";
                };
                y = para(c, source + " · " + (delta >= 0 ? "+" : "−") + formatDwell(Math.abs(delta)) + " vs the default",
                        x, y, w, tagLabel(row).equals("Interlining") ? AMBER : FlatUi.TEXT_DIM);
                button(c, mx, my, "Reset to default", x, y, Math.min(w, 100), 15, FlatUi.ButtonStyle.FLAT, true, HIT_RESET, 0);
                y += 19;
            } else {
                y = para(c, "Uses the platform's " + formatDwell(defaultMillis()) + ".", x, y, w, FlatUi.TEXT_DIM);
            }
            long baked = bakedDwell(row.route.getId());
            long planned = plannedDwell(row.route.getId());
            if (baked >= 0 && Math.abs(baked - planned) >= 500) {
                y = para(c, "Trains still dwell " + formatDwell(baked) + " here — Save regenerates the depots.", x, y, w, AMBER);
            }
            if (result != null) {
                long headway = result.headway(row.route.getId());
                if (headway > 0) {
                    y = para(c, "Headway here: " + formatDwell(headway) + (planned >= headway ? "  (shorter than the dwell!)" : ""),
                            x, y, w, planned >= headway ? FlatUi.DANGER : FlatUi.TEXT_DIM);
                }
            }
            y += 4;
            y = drawRouteConflicts(c, row, x, y, w);
            y += 4;
        }

        FlatUi.heading(c, textRenderer, "Platform group", x, y);
        y += 12;
        for (int k = 0; k < row.stopIndices.size(); k++) {
            int stopIndex = row.stopIndices.get(k);
            String key = row.route.getId() + ":" + stopIndex;
            List<Long> members = groups.get(key);
            String label = (row.stopIndices.size() > 1 ? "Stop " + (stopIndex + 1) + " of " + row.stopCounts.get(k) + ": " : "")
                    + (members == null || members.isEmpty() ? "always this platform" : "rotates over " + platformNames(members));
            y = para(c, label, x, y, w - 4, members == null || members.isEmpty() ? FlatUi.TEXT_DIM : FlatUi.TEXT);
            button(c, mx, my, members == null || members.isEmpty() ? "Make a group…" : "Edit…", x, y, 70, 15,
                    FlatUi.ButtonStyle.FLAT, true, HIT_GROUP_EDIT, encodeStop(selected, k));
            if (members != null && !members.isEmpty()) {
                button(c, mx, my, "Clear", x + 74, y, 44, 15, FlatUi.ButtonStyle.GHOST, true, HIT_GROUP_CLEAR, encodeStop(selected, k));
            }
            y += 19;
        }
        y = para(c, "Groups and dwell changes apply when the depots regenerate (Save does it).", x, y, w, FlatUi.TEXT_FAINT);
        return y;
    }

    private int drawRouteConflicts(DrawContext c, Row row, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "Schedule check", x, y);
        y += 12;
        if (result == null) {
            return para(c, data == null ? "Loading the timetable…" : "No timetabled trains.", x, y, w, FlatUi.TEXT_FAINT);
        }
        long routeId = row.route.getId();
        int shown = 0;
        int total = 0;
        long now = data == null ? 0 : Math.floorMod(data.simNow(), Math.max(1, result.day()));
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            boolean blocker = conflict.blocker().routeId() == routeId;
            boolean blocked = conflict.blocked().routeId() == routeId;
            if (!blocker && !blocked) {
                continue;
            }
            total++;
            if (shown >= 6) {
                continue;
            }
            shown++;
            String when = " (" + formatIn(Math.floorMod(conflict.blocked().arrive() - now, result.day())) + ")";
            String text;
            if (blocker) {
                String other = conflict.sameRoute() ? "the next train of this route" : routeName(conflict.blocked().routeId());
                text = (conflict.maybe() ? "Might hold " : "Holds ") + other + " outside " + formatDwell(conflict.waitMillis()) + when;
            } else {
                text = (conflict.maybe() ? "Might wait " : "Waits ") + formatDwell(conflict.waitMillis()) + " behind "
                        + routeName(conflict.blocker().routeId()) + when;
            }
            y = para(c, (conflict.maybe() ? "? " : "⚠ ") + text, x, y, w, conflict.maybe() ? AMBER : FlatUi.DANGER);
        }
        if (total == 0) {
            y = para(c, "✓ Never waits for or blocks another train here.", x, y, w, FlatUi.OK);
        } else if (total > shown) {
            y = para(c, "… and " + (total - shown) + " more per cycle", x, y, w, FlatUi.TEXT_FAINT);
        }
        return y;
    }

    private int drawHoldInspector(DrawContext c, int mx, int my, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "Connection hold", x, y);
        y += 13;
        toggleRow(c, mx, my, "Hold departures for connections", holdOn, x, y, w, HIT_HOLD_TOGGLE, 0);
        y += 17;
        if (!holdOn) {
            return para(c, "When on, a train ready to leave waits while a train is about to arrive at one of the watched platforms.",
                    x, y, w, FlatUi.TEXT_FAINT);
        }
        slider(c, mx, my, K_HOLD_WINDOW, "Approach", 52, holdSeconds, AddonNetworking.MIN_HOLD_WINDOW_SECONDS,
                AddonNetworking.MAX_HOLD_WINDOW_SECONDS, holdSeconds + " s", x, y, w);
        y += 15;
        slider(c, mx, my, K_HOLD_TRANSFER, "Transfer", 52, holdTransfer, 0, AddonNetworking.MAX_TRANSFER_SECONDS,
                holdTransfer == 0 ? "Off" : holdTransfer + " s", x, y, w);
        y += 17;
        FlatUi.heading(c, textRenderer, "Watched platforms", x, y);
        y += 12;
        if (holdWatched.isEmpty()) {
            y = para(c, "None yet — the rule does nothing until one is ticked.", x, y, w, AMBER);
        } else {
            y = para(c, platformNames(new ArrayList<>(holdWatched)), x, y, w, FlatUi.TEXT);
        }
        button(c, mx, my, "Choose platforms…", x, y, Math.min(w, 110), 15, FlatUi.ButtonStyle.FLAT, true, HIT_HOLD_CHOOSE, 0);
        y += 20;
        y = para(c, "Worst case a train here waits " + (holdSeconds + holdTransfer)
                + " s longer than its dwell. The schedule shows that as amber \"maybe\" overlaps.", x, y, w, FlatUi.TEXT_FAINT);
        return y;
    }

    // ------------------------------------------------------------ timeline

    private void drawTimeline(DrawContext c, int mx, int my, int x, int y, int w, int h) {
        int hx = x + 6;
        FlatUi.heading(c, textRenderer, "Schedule", hx, y + 5);
        int segX = hx + textWidth("Schedule") + 8;
        FlatUi.segmented(c, textRenderer, WINDOW_LABELS, windowIndex, segX, y + 3, 120, 13, mx, my);
        hit(segX, y + 3, 120, 13, HIT_WINDOW, 0);
        int legendX = segX + 128;
        legendX = legend(c, legendX, y + 6, 0xFF8A8A96, "dwell");
        legendX = legend(c, legendX, y + 6, FlatUi.DANGER, "overlap");
        if (holdExtraMillis() > 0) {
            legend(c, legendX, y + 6, AMBER, "hold maybe");
        }
        button(c, mx, my, "Refresh", x + w - 50, y + 3, 44, 13, FlatUi.ButtonStyle.GHOST, true, HIT_REFRESH, 0);

        int labelW = MathHelper.clamp(w / 6, 50, 110);
        laneX = x + 6 + labelW;
        laneW = w - 12 - labelW;
        lanesTop = y + 20;
        int lanesBottom = y + h - 22;
        if (data == null || result == null) {
            c.drawText(textRenderer, data == null ? "Loading the timetable…" : "No timetabled trains stop here.",
                    laneX, lanesTop + 4, FlatUi.TEXT_FAINT, false);
            if (data != null && !data.note().isEmpty()) {
                c.drawText(textRenderer, trim(data.note(), laneW), laneX, lanesTop + 16, FlatUi.TEXT_FAINT, false);
            }
            return;
        }
        long day = Math.max(1, result.day());
        long now = Math.floorMod(data.simNow(), day);
        windowLength = WINDOW_MILLIS[windowIndex] == 0 ? day : Math.min(day, WINDOW_MILLIS[windowIndex]);
        windowStart = now - windowLength / 10;

        List<Long> lanes = new ArrayList<>();
        for (Row row : rows) {
            lanes.add(row.route.getId());
        }
        for (PlatformOccupancy.Visit visit : result.visits()) {
            if (!lanes.contains(visit.routeId())) {
                lanes.add(visit.routeId());
            }
        }
        int n = Math.max(1, lanes.size());
        laneH = Math.max(3, Math.min(12, (lanesBottom - lanesTop) / n));
        laneTop = lanesTop;

        // Lanes: label, background, selection.
        for (int i = 0; i < lanes.size(); i++) {
            long routeId = lanes.get(i);
            int ly = laneTop + i * laneH;
            Row row = rowsByRoute.get(routeId);
            boolean sel = row != null && selected == rows.indexOf(row);
            FlatUi.rect(c, laneX, ly, laneW, laneH - 1, sel ? 0xFF262E3E : (i % 2 == 0 ? 0xFF1A1A1F : 0xFF1C1C21));
            if (laneH >= 8) {
                int color = row == null ? 0xFF808080 : 0xFF000000 | row.route.getColor();
                FlatUi.rect(c, x + 6, ly + 1, 2, laneH - 3, color);
                c.drawText(textRenderer, trim(row == null ? routeName(routeId) : row.name, labelW - 8),
                        x + 10, ly + (laneH - 8) / 2, sel ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            }
            if (row != null && picker == null) {
                hit(x + 6, ly, w - 12, laneH, HIT_LANE, rows.indexOf(row));
            }
        }

        // Minute grid + labels (relative to now).
        long minute = 60_000L;
        long step = windowLength > 12 * minute ? 2 * minute : minute;
        long firstTick = (long) Math.ceil((windowStart - now) / (double) step) * step + now;
        int axisY = laneTop + lanes.size() * laneH + 1;
        for (long t = firstTick; t <= windowStart + windowLength; t += step) {
            int tx = timeX(t);
            FlatUi.rect(c, tx, laneTop, 1, axisY - laneTop, 0x18FFFFFF);
            long rel = Math.round((t - now) / (double) minute);
            String label = rel == 0 ? "now" : (rel > 0 ? "+" : "−") + Math.abs(rel) + "m";
            int lw = textWidth(label);
            if (tx - lw / 2 > laneX && tx + lw / 2 < laneX + laneW) {
                c.drawText(textRenderer, label, tx - lw / 2, axisY + 2, FlatUi.TEXT_FAINT, false);
            }
        }

        c.enableScissor(laneX, laneTop, laneX + laneW, axisY);
        long extra = holdExtraMillis();
        PlatformOccupancy.Visit hovered = null;
        for (PlatformOccupancy.Visit visit : result.visits()) {
            int lane = lanes.indexOf(visit.routeId());
            int ly = laneTop + lane * laneH;
            Row row = rowsByRoute.get(visit.routeId());
            int color = row == null ? 0xFF8A8A96 : 0xFF000000 | row.route.getColor();
            for (long k = -1; k <= 1; k++) {
                long a = visit.arrive() + k * day;
                long b = visit.leave() + k * day;
                if (b + extra < windowStart || a > windowStart + windowLength) {
                    continue;
                }
                int x0 = timeX(a);
                int x1 = Math.max(x0 + 1, timeX(b));
                FlatUi.rect(c, x0, ly + 1, x1 - x0, laneH - 3, color);
                if (extra > 0) {
                    int x2 = Math.max(x1, timeX(b + extra));
                    FlatUi.rect(c, x1, ly + laneH / 2 - 1, x2 - x1, Math.max(1, laneH / 4), 0x99E5A23A);
                }
                if (picker == null && mx >= x0 - 1 && mx <= x1 + 1 && my >= ly && my < ly + laneH) {
                    hovered = visit;
                }
            }
        }
        // Overlaps, drawn over the blocker's bar from the blocked train's arrival.
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            int lane = lanes.indexOf(conflict.blocker().routeId());
            int ly = laneTop + lane * laneH;
            for (long k = -1; k <= 1; k++) {
                long a = conflict.blocked().arrive() + k * day;
                long b = a + conflict.waitMillis();
                if (b < windowStart || a > windowStart + windowLength) {
                    continue;
                }
                int x0 = timeX(a);
                int x1 = Math.max(x0 + 2, timeX(b));
                FlatUi.rect(c, x0, ly, x1 - x0, laneH - 1, conflict.maybe() ? 0xCCE5A23A : 0xEEE5484D);
            }
        }
        c.disableScissor();
        int nowX = timeX(now);
        FlatUi.rect(c, nowX, laneTop - 2, 1, axisY - laneTop + 2, FlatUi.TEXT);

        // Footer: hover detail, else the note.
        int footY = y + h - 10;
        String footer;
        int footColor = FlatUi.TEXT_DIM;
        if (hovered != null) {
            footer = describe(hovered, now, day);
            footColor = FlatUi.TEXT;
        } else if (!data.note().isEmpty()) {
            footer = data.note();
        } else {
            footer = "Bars = time at the platform, from the real timetable, re-timed as you edit.";
            footColor = FlatUi.TEXT_FAINT;
        }
        c.drawText(textRenderer, trim(footer, w - 12), x + 6, footY, footColor, false);
    }

    private int legend(DrawContext c, int x, int y, int color, String label) {
        FlatUi.rect(c, x, y + 1, 6, 6, color);
        c.drawText(textRenderer, label, x + 9, y, FlatUi.TEXT_FAINT, false);
        return x + 9 + textWidth(label) + 8;
    }

    private int timeX(long t) {
        return laneX + (int) Math.round((t - windowStart) * (double) laneW / Math.max(1, windowLength));
    }

    private String describe(PlatformOccupancy.Visit visit, long now, long day) {
        StringBuilder text = new StringBuilder(routeName(visit.routeId()));
        text.append(" · ").append(formatIn(Math.floorMod(visit.arrive() - now, day)))
                .append(" · dwell ").append(formatDwell(visit.dwell()));
        if (!visit.depotName().isEmpty()) {
            text.append(" · ").append(visit.depotName());
        }
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            if (conflict.blocker() == visit) {
                text.append(conflict.maybe() ? " · might hold " : " · holds ").append(routeName(conflict.blocked().routeId()))
                        .append(' ').append(formatDwell(conflict.waitMillis()));
                break;
            }
            if (conflict.blocked() == visit) {
                text.append(conflict.maybe() ? " · might wait " : " · waits ").append(formatDwell(conflict.waitMillis()));
                break;
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------ picker modal

    private void openPicker(String key, String title, List<Long> preselected, int max) {
        Position mid = platform.getMidPosition();
        BlockPos origin = new BlockPos((int) mid.getX(), (int) mid.getY(), (int) mid.getZ());
        picker = new PlatformPicker(origin, preselected, max, Math.min(260, width - 40));
        pickerKey = key;
        pickerTitle = title;
    }

    private void drawPickerModal(DrawContext c, int mx, int my) {
        beginModal(c, HIT_MODAL_BACKDROP);
        int pw = Math.min(260, width - 40) + 16;
        picker.fitTo(height - 90);
        int ph = 18 + picker.getHeight() + 28;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        FlatUi.rect(c, px, py, pw, ph, FlatUi.PANE);
        FlatUi.outline(c, px, py, pw, ph, FlatUi.BORDER_STRONG);
        hit(px, py, pw, ph, HIT_MODAL_SWALLOW, 0);
        c.drawText(textRenderer, trim(pickerTitle, pw - 16), px + 8, py + 6, FlatUi.TEXT, false);
        picker.setPosition(px + 8, py + 18);
        picker.render(c, textRenderer, mx, my);
        int by = py + 18 + picker.getHeight() + 6;
        button(c, mx, my, "Done", px + pw - 58, by, 50, 16, FlatUi.ButtonStyle.PRIMARY, true, HIT_MODAL_DONE, 0);
        button(c, mx, my, "Clear", px + pw - 112, by, 50, 16, FlatUi.ButtonStyle.FLAT, true, HIT_MODAL_CLEAR, 0);
        endModal(c);
    }

    private void closePicker(boolean apply) {
        if (picker == null) {
            return;
        }
        if (apply) {
            List<Long> chosen = new ArrayList<>(picker.getSelected());
            if (pickerKey == null) {
                holdWatched.clear();
                for (long id : chosen) {
                    if (id != platform.getId() && holdWatched.size() < AddonNetworking.MAX_WATCHED) {
                        holdWatched.add(id);
                    }
                }
            } else {
                List<Long> members = new ArrayList<>();
                for (long id : chosen) {
                    if (sameStationAndMode(id) && members.size() < AddonNetworking.MAX_GROUP_SIZE) {
                        members.add(id);
                    }
                }
                if (members.isEmpty()) {
                    groups.remove(pickerKey);
                } else {
                    groups.put(pickerKey, members);
                }
            }
        }
        picker = null;
        pickerKey = null;
    }

    private boolean sameStationAndMode(long id) {
        if (id == platform.getId()) {
            return true;
        }
        try {
            Platform other = MinecraftClientData.getDashboardInstance().platformIdMap.get(id);
            return other != null && other.area != null && platform.area != null
                    && other.area.getId() == platform.area.getId()
                    && other.getTransportMode() == platform.getTransportMode();
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ input

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        switch (id) {
            case HIT_SAVE -> saveAndClose();
            case HIT_CANCEL -> close();
            case HIT_MTR -> {
                bypassOnce = true;
                client.setScreen(mtrScreen);
            }
            case HIT_ROW -> {
                if (selected != arg) {
                    selected = arg;
                    inspectorScroll = 0;
                }
            }
            case HIT_LANE -> {
                selected = arg;
                inspectorScroll = 0;
            }
            case HIT_HOLD_ROW -> {
                selected = -1;
                inspectorScroll = 0;
            }
            case HIT_LEFT_PANE, 0 -> {
            }
            case HIT_DEFAULT_STEP -> {
                defaultHalfSeconds = MathHelper.clamp(defaultHalfSeconds + arg * (hasShiftDown() ? 10 : 1), 1, MAX_HALF_SECONDS);
                defaultEdited = true;
            }
            case HIT_OWN_MODE -> {
                Row row = selectedRow();
                if (row != null) {
                    boolean own = (mx - (rightX + 8)) >= (rightW - 16) / 2.0;
                    if (own && !row.own) {
                        row.own = true;
                        if (!row.hadOwn) {
                            row.ownHalfSeconds = halfSeconds(defaultMillis(), MIN_OWN_HALF_SECONDS);
                        }
                    } else if (!own) {
                        row.own = false;
                    }
                }
            }
            case HIT_OWN_STEP -> {
                Row row = selectedRow();
                if (row != null) {
                    row.ownHalfSeconds = MathHelper.clamp(halfSeconds(row.ownMillis(), MIN_OWN_HALF_SECONDS)
                            + arg * (hasShiftDown() ? 10 : 1), MIN_OWN_HALF_SECONDS, MAX_HALF_SECONDS);
                    row.edited = true;
                }
            }
            case HIT_RESET -> {
                Row row = selectedRow();
                if (row != null) {
                    row.own = false;
                }
            }
            case HIT_HOLD_TOGGLE -> holdOn = !holdOn;
            case HIT_HOLD_CHOOSE -> openPicker(null, "Hold here while a train is about to arrive at:",
                    new ArrayList<>(holdWatched), AddonNetworking.MAX_WATCHED);
            case HIT_GROUP_EDIT -> {
                Row row = rows.get(arg >> 8);
                int stopIndex = row.stopIndices.get(arg & 0xFF);
                String key = row.route.getId() + ":" + stopIndex;
                List<Long> members = groups.getOrDefault(key, List.of());
                openPicker(key, "Rotate " + row.name + " across (tick this one to keep it):",
                        members.isEmpty() ? List.of(platform.getId()) : members, AddonNetworking.MAX_GROUP_SIZE);
            }
            case HIT_GROUP_CLEAR -> {
                Row row = rows.get(arg >> 8);
                groups.remove(row.route.getId() + ":" + row.stopIndices.get(arg & 0xFF));
            }
            case HIT_WINDOW -> windowIndex = MathHelper.clamp((int) ((mx - lastSegX()) * WINDOW_LABELS.length / 120), 0,
                    WINDOW_LABELS.length - 1);
            case HIT_REFRESH -> {
                lastRequest = 0;
                toast("Refreshing the timetable…", false);
            }
            case HIT_MODAL_DONE -> closePicker(true);
            case HIT_MODAL_CLEAR -> {
                picker.getSelected().clear();
                closePicker(true);
            }
            case HIT_MODAL_BACKDROP -> closePicker(false);
            default -> {
            }
        }
    }

    private int lastSegX() {
        return 6 + 6 + textWidth("Schedule") + 8;
    }

    @Override
    protected void onSlider(int key, int value) {
        switch (key) {
            case K_DEFAULT -> {
                defaultHalfSeconds = value;
                defaultEdited = true;
            }
            case K_OWN -> {
                Row row = selectedRow();
                if (row != null) {
                    row.ownHalfSeconds = value;
                    row.edited = true;
                }
            }
            case K_HOLD_WINDOW -> holdSeconds = value;
            case K_HOLD_TRANSFER -> holdTransfer = value;
            default -> {
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (picker != null && picker.mouseClicked(mx, my)) {
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (picker != null) {
            picker.mouseScrolled(mx, my, vertical);
            return true;
        }
        int delta = (int) Math.round(-vertical * 14);
        if (FlatUi.inside(mx, my, leftX, leftY, leftW, leftH)) {
            listScroll = MathHelper.clamp(listScroll + delta, 0, Math.max(0, listContent - leftH));
            return true;
        }
        if (FlatUi.inside(mx, my, rightX, rightY, rightW, rightH)) {
            inspectorScroll = MathHelper.clamp(inspectorScroll + delta, 0, Math.max(0, inspectorContent - rightH));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    protected boolean onEscape() {
        if (picker != null) {
            closePicker(false);
            return true;
        }
        // Like MTR's platform screen: closing saves.
        saveAndClose();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_S && hasControlDown() && focused == null) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(previous);
        }
    }

    private Row selectedRow() {
        return selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
    }

    // ================================================================ save

    private void saveAndClose() {
        try {
            save();
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not save the platform settings", e);
            toast("Save failed — see the log", true);
            return;
        }
        close();
    }

    private void save() {
        // 1. Number + default dwell through MTR's own path (what its screen sends on close).
        String number = numberBox.getText().trim();
        boolean numberChanged = !number.equals(platform.getName());
        boolean defaultChanged = defaultEdited && defaultMillis() != originalDefaultMillis;
        if (numberChanged || defaultChanged) {
            platform.setName(number);
            if (defaultChanged) {
                platform.setDwellTime(defaultMillis());
            }
            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(
                    new UpdateDataRequest(MinecraftClientData.getDashboardInstance()).addPlatform(platform)));
        }

        // 2. Hold rule.
        List<Long> watched = new ArrayList<>(holdWatched);
        boolean ruleOn = holdOn && !watched.isEmpty();
        boolean ruleChanged = originalRule == null ? ruleOn
                : !ruleOn || originalRule.seconds() != holdSeconds || originalRule.transferSeconds() != holdTransfer
                || !new LinkedHashSet<>(originalRule.watched()).equals(holdWatched);
        if (ruleChanged) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(platform.getId());
            buf.writeVarInt(ruleOn ? holdSeconds : 0);
            buf.writeVarInt(holdTransfer);
            buf.writeVarInt(ruleOn ? watched.size() : 0);
            if (ruleOn) {
                for (long id : watched) {
                    buf.writeLong(id);
                }
            }
            ClientPlayNetworking.send(AddonNetworking.UPDATE_HOLD_RULE_C2S, buf);
        }

        // 3. Platform groups that changed.
        boolean groupsChanged = false;
        Set<String> keys = new LinkedHashSet<>(originalGroups.keySet());
        keys.addAll(groups.keySet());
        for (String key : keys) {
            List<Long> now = groups.getOrDefault(key, List.of());
            if (now.equals(originalGroups.getOrDefault(key, List.of()))) {
                continue;
            }
            groupsChanged = true;
            int colon = key.indexOf(':');
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(Long.parseLong(key.substring(0, colon)));
            buf.writeVarInt(Integer.parseInt(key.substring(colon + 1)));
            buf.writeVarInt(now.size());
            for (long id : now) {
                buf.writeLong(id);
            }
            ClientPlayNetworking.send(AddonNetworking.UPDATE_PLATFORM_GROUP_C2S, buf);
        }

        // 4. Per-route dwell (+ regenerate when anything baked changed).
        Map<Long, Long> stored = ClientDwellOverrides.get(platform.getId());
        Map<Long, long[]> overrides = new LinkedHashMap<>(); // route → {millis, source}
        boolean overridesChanged = false;
        for (Row row : rows) {
            if (row.own) {
                long millis = MathHelper.clamp(row.ownMillis(), AddonNetworking.MIN_DWELL_MILLIS, AddonNetworking.MAX_DWELL_MILLIS);
                boolean edited = row.edited || !row.hadOwn;
                overrides.put(row.route.getId(), new long[]{millis, edited ? AddonStore.DWELL_SOURCE_MANUAL : AddonStore.DWELL_SOURCE_UNKNOWN});
                if (!row.hadOwn || millis != row.storedMillis) {
                    overridesChanged = true;
                }
            } else if (row.hadOwn) {
                overridesChanged = true;
            }
        }
        if (stored != null) {
            // Overrides for routes this screen does not list (should not happen) survive.
            stored.forEach((routeId, millis) -> {
                if (!rowsByRoute.containsKey(routeId) && overrides.size() < AddonNetworking.MAX_ROUTE_OVERRIDES) {
                    overrides.put(routeId, new long[]{millis, AddonStore.DWELL_SOURCE_UNKNOWN});
                }
            });
        }
        boolean regenerate = overridesChanged || defaultChanged || groupsChanged;
        if (overridesChanged || regenerate) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(platform.getId());
            buf.writeVarInt(overrides.size());
            overrides.forEach((routeId, value) -> {
                buf.writeLong(routeId);
                buf.writeVarInt((int) value[0]);
                buf.writeByte((int) value[1]);
            });
            buf.writeBoolean(regenerate);
            ClientPlayNetworking.send(AddonNetworking.UPDATE_DWELL_OVERRIDES_C2S, buf);
        }
    }

    // ================================================================ helpers

    private int para(DrawContext c, String text, int x, int y, int w, int color) {
        for (var line : textRenderer.wrapLines(Text.literal(text), Math.max(20, w))) {
            c.drawText(textRenderer, line, x, y, color, false);
            y += 10;
        }
        return y + 2;
    }

    private String routeName(long routeId) {
        Row row = rowsByRoute.get(routeId);
        if (row != null) {
            return row.name;
        }
        try {
            for (Route route : MinecraftClientData.getDashboardInstance().routes) {
                if (route.getId() == routeId) {
                    return IGui.formatStationName(route.getName());
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "another route";
    }

    private String platformNames(List<Long> ids) {
        List<String> names = new ArrayList<>();
        for (long id : ids) {
            Platform other = MinecraftClientData.getDashboardInstance().platformIdMap.get(id);
            names.add(other == null ? "?" : other.getName());
        }
        return String.join(", ", names);
    }

    private static int encodeStop(int rowIndex, int occurrence) {
        return (rowIndex << 8) | (occurrence & 0xFF);
    }

    private static int halfSeconds(long millis, int min) {
        return MathHelper.clamp((int) Math.round(millis / 500.0), min, MAX_HALF_SECONDS);
    }

    /** "40 s", "40.5 s", "3m 31s". */
    static String formatDwell(long millis) {
        if (millis < 100_000) {
            long half = Math.round(millis / 500.0);
            return (half % 2 == 0 ? String.valueOf(half / 2) : (half / 2) + ".5") + " s";
        }
        long seconds = Math.round(millis / 1000.0);
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /** "in 2m 10s" / "now". */
    private static String formatIn(long millis) {
        long seconds = Math.round(millis / 1000.0);
        if (seconds <= 0) {
            return "now";
        }
        return seconds < 60 ? "in " + seconds + "s" : "in " + (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /**
     * Dev-only (run/commands.txt), after {@code #use} opened MTR's dashboard:
     * {@code #platform open <station part> [platform number]} opens MTR's real
     * {@code PlatformScreen} (the swap then replaces it); on our screen
     * {@code select N | select hold | own N seconds | default seconds | window N}.
     */
    public static void devHook(MinecraftClient client, String cmd) {
        String[] a = cmd.trim().split("\\s+");
        if (a.length >= 3 && a[1].equals("open")) {
            String part = a[2].toLowerCase(java.util.Locale.ROOT);
            org.mtr.mapping.mapper.ScreenExtension parent =
                    client.currentScreen instanceof org.mtr.mapping.mapper.ScreenExtension extension ? extension : null;
            for (Platform candidate : MinecraftClientData.getDashboardInstance().platforms) {
                String station = candidate.area == null ? "" : candidate.area.getName().toLowerCase(java.util.Locale.ROOT);
                if (station.contains(part) && (a.length < 4 || candidate.getName().equals(a[3]))) {
                    client.setScreen(new org.mtr.mod.screen.PlatformScreen(candidate, candidate.getTransportMode(), parent));
                    return;
                }
            }
            StationAnnouncer.LOGGER.info("[platform dev] no platform at a station matching '{}'", part);
            return;
        }
        if (!(client.currentScreen instanceof PlatformSettingsScreen screen) || a.length < 2) {
            return;
        }
        try {
            switch (a[1]) {
                case "select" -> screen.selected = a[2].equals("hold") ? -1 : Integer.parseInt(a[2]);
                case "own" -> {
                    Row row = screen.rows.get(Integer.parseInt(a[2]));
                    row.own = true;
                    row.edited = true;
                    row.ownHalfSeconds = halfSeconds(Math.round(Double.parseDouble(a[3]) * 1000), MIN_OWN_HALF_SECONDS);
                    screen.selected = Integer.parseInt(a[2]);
                }
                case "default" -> {
                    screen.defaultHalfSeconds = halfSeconds(Math.round(Double.parseDouble(a[2]) * 1000), 1);
                    screen.defaultEdited = true;
                }
                case "window" -> screen.windowIndex = MathHelper.clamp(Integer.parseInt(a[2]), 0, WINDOW_LABELS.length - 1);
                case "hold" -> {
                    screen.holdOn = true;
                    screen.selected = -1;
                }
                case "save" -> screen.saveAndClose();
                case "dump" -> {
                    if (screen.data != null) {
                        for (PlatformOccupancy.Siding siding : screen.data.sidings()) {
                            StringBuilder stops = new StringBuilder();
                            for (PlatformOccupancy.Stop stop : siding.stops()) {
                                stops.append(' ').append(stop);
                            }
                            StationAnnouncer.LOGGER.info("[platform dev] siding {} depot {} repeat {} phases {} stops{}",
                                    siding.sidingId(), siding.depotName(), siding.repeat(),
                                    java.util.Arrays.toString(siding.phases()), stops);
                        }
                    }
                    if (screen.result != null) {
                        for (PlatformOccupancy.Conflict conflict : screen.result.conflicts()) {
                            StationAnnouncer.LOGGER.info("[platform dev] conflict {}", conflict);
                        }
                    }
                }
                default -> StationAnnouncer.LOGGER.info("[platform dev] unknown '{}'", cmd);
            }
            StationAnnouncer.LOGGER.info("[platform dev] {} → rows={} conflicts={}", cmd, screen.rows.size(),
                    screen.result == null ? "none yet" : screen.result.conflicts().size());
        } catch (Exception e) {
            StationAnnouncer.LOGGER.info("[platform dev] bad command '{}': {}", cmd, e.toString());
        }
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    public static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        String first = (split >= 0 ? raw.substring(0, split) : raw).trim();
        return first.isEmpty() ? raw.trim() : first;
    }
}
