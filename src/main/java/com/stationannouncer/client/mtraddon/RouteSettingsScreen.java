package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.StationAnnouncerClient;
import com.stationannouncer.client.gui.FlatPaScreen;
import com.stationannouncer.client.gui.LineStyleEditor;
import com.stationannouncer.client.gui.TrainRowsEditor;
import com.stationannouncer.client.mtr.PlatformPicker;
import com.stationannouncer.client.mtr.RouteBullets;
import com.stationannouncer.client.tts.TtsManager;
import com.stationannouncer.mtr.RouteTrainPa;
import com.stationannouncer.mtraddon.AddonNetworking;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.LineStyles;
import com.stationannouncer.mtraddon.PlatformOccupancy;
import com.stationannouncer.pa.PaText;
import com.stationannouncer.pa.TrainAnnouncements;
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
import org.mtr.core.data.RouteType;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.packet.PacketSetRouteIdHasDisabledAnnouncements;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Our replacement for MTR's Edit Route screen (FlatUi, no vanilla widgets). Left: the
 * route's stops top to bottom on a strip in the route colour — each with this route's
 * dwell there (Default / Manual / Interlining) and the platform's overlap badge. Right:
 * tabs Route (every MTR field) | Style (the whole line's bullet, or this route's own text /
 * shape / lettering) | Announcements (next-station template) | Train PA (on-board rows);
 * clicking a stop shows that stop instead (dwell, schedule check, platform group, "Open
 * platform screen").
 *
 * <p>Save: line style (when touched) writes its colour / number into every route of the
 * line, then this route's own fields go through MTR's {@code PacketUpdateData(addRoute)} —
 * exactly what MTR's screen sends on close — followed by the addon packets (route style,
 * template, train PA, groups, per-platform dwell with a depot regeneration). Esc saves,
 * like MTR's screen; Cancel discards.</p>
 *
 * <p>Temporary stop changes (skip / add a stop) are deliberately not here yet — Thomas
 * (2026-10-14): they are broken right now. Their place is {@link #drawStopInspector},
 * after the platform group.</p>
 */
@Environment(EnvType.CLIENT)
public class RouteSettingsScreen extends FlatPaScreen {
    // Hit ids stay below TrainRowsEditor.BASE (100); LineStyleEditor uses 300+.
    private static final int HIT_SAVE = 1;
    private static final int HIT_CANCEL = 2;
    private static final int HIT_MTR = 3;
    private static final int HIT_TAB = 4;
    private static final int HIT_STOP = 5;
    private static final int HIT_BACK = 6;
    private static final int HIT_TYPE = 7;
    private static final int HIT_HIDDEN = 8;
    private static final int HIT_CIRCULAR = 9;
    private static final int HIT_SWATCH = 10;
    private static final int HIT_SCOPE = 11;
    private static final int HIT_OV_SHAPE = 12;
    private static final int HIT_OV_INK = 13;
    private static final int HIT_OV_CLEAR = 14;
    private static final int HIT_ANNOUNCE = 15;
    private static final int HIT_TOKEN = 16;
    private static final int HIT_SECTION = 17;
    private static final int HIT_PRESET = 18;
    private static final int HIT_HEAR = 19;
    private static final int HIT_CHIME = 20;
    private static final int HIT_OWN_MODE = 21;
    private static final int HIT_OWN_STEP = 22;
    private static final int HIT_RESET = 23;
    private static final int HIT_GROUP_EDIT = 24;
    private static final int HIT_GROUP_CLEAR = 25;
    private static final int HIT_OPEN_PLATFORM = 26;
    private static final int HIT_MODAL_BACKDROP = 27;
    private static final int HIT_MODAL_DONE = 28;
    private static final int HIT_MODAL_CLEAR = 29;
    private static final int HIT_MODAL_SWALLOW = 30;
    private static final int HIT_TEMPLATE_CLEAR = 31;

    private static final int K_OWN = 1;

    private static final int MAX_HALF_SECONDS = 1_200;
    private static final int MIN_OWN_HALF_SECONDS = 2;
    private static final int AMBER = 0xFFE5A23A;
    private static final int TAG_DEFAULT = 0xFF3A3A44;
    private static final int TAG_INTERLINE = 0xFF7A5414;
    private static final int TAG_LEGACY = 0xFF4A4A58;

    private static final String[] TABS = {"Route", "Style", "Announce", "Train PA"};
    private static final String[] TYPE_LABELS = {"Normal", "Light rail", "High speed"};
    private static final String[] CIRCULAR_LABELS = {"Not circular", "Clockwise", "Anticlockwise"};
    private static final String[] SCOPE_LABELS = {"Whole line", "This route"};
    private static final String[] OV_SHAPES = {"Line", "Circle", "Diamond", "Square", "Hexagon"};
    private static final String[] OV_INKS = {"Line", "Auto", "White", "Black"};

    /** Next-station template tokens: label, inserted text. */
    private static final String[][] TOKENS = {{"next", "{next}"}, {"station", "{station}"}, {"dest", "{dest}"},
            {"route", "{route}"}, {"number", "{number}"}, {"interchanges", "{interchanges}"}};
    private static final String[][] SECTIONS = {{"if interchange", "[interchange]", "[/interchange]"},
            {"if terminus", "[terminus]", "[/terminus]"}, {"en route", "[enroute]", "[/enroute]"}};
    private static final String[][] PRESETS = {
            {"NYC", "This is a {dest}-bound {route} train. The next stop is {next}. "
                    + "[interchange]Transfer is available to {interchanges}. [/interchange]"
                    + "[terminus]This is the last stop on this train. [/terminus]"
                    + "[enroute]Stand clear of the closing doors, please.[/enroute]"},
            {"Simple", "Next stop: {next}."},
            {"Transfer", "Next stop {next}[interchange], change for {interchanges}[/interchange]. "
                    + "[terminus]All change, please — this train terminates here.[/terminus]"}};
    private static final List<String> SAMPLE_INTERCHANGES = List.of("2 Local", "7 Express");
    private static final PaText.Train TRAIN_SAMPLE = PaText.SAMPLE.withStation("Atlantic Avenue");
    private static final long REFRESH_MILLIS = 15_000L;

    /** Set by "MTR screen": the next EditRouteScreen init is left alone. */
    private static boolean bypassOnce;

    /** One stop of the route. */
    private record Stop(int index, Platform platform, String station, String platformName) {
    }

    private final Route route;
    private final Screen previous;
    private final Screen mtrScreen;
    private final String originalName;
    private final List<Stop> stops = new ArrayList<>();
    private final boolean circularCapable;
    private final boolean scheduleControls;

    // Route tab.
    private final FlatUi.TextBox nameBox;
    private final FlatUi.TextBox numberBox;
    private final String initialNumber;
    private final FlatUi.TextBox hexBox;
    private int routeRgb;
    private boolean routeColorEdited;
    private RouteType routeType;
    private boolean hidden;
    private Route.CircularState circular;
    private final boolean originalAnnouncementsOff;
    private boolean announcementsOff;

    // Style tab.
    private final LineStyleEditor lineEditor;
    private int scope;
    private final FlatUi.TextBox overrideText;
    private LineStyles.Shape overrideShape;
    private LineStyles.TextColor overrideInk;
    private boolean overrideTouched;

    // Announcements tab.
    private final FlatUi.TextBox templateBox;
    private boolean templateTouched;

    // Train PA tab.
    private final TrainRowsEditor trainRows;
    private boolean trainLoaded;
    private boolean chime = true;
    private String chimeSound = "";
    private boolean chimeDirty;

    // Stops: this route's dwell edits per platform (millis, -1 = platform default), groups.
    private final Map<Long, Long> editedDwell = new HashMap<>();
    private final Map<String, List<Long>> groups = new LinkedHashMap<>();
    private final Map<String, List<Long>> originalGroups = new LinkedHashMap<>();
    private final Map<Long, PlatformOccupancy.Result> results = new HashMap<>();
    private int seenScheduleVersion = -1;
    private int seenDwellVersion = -1;
    private long lastRequest;

    private int tab;
    private int selectedStop = -1;
    private int listScroll;
    private int listContent;
    private int paneScroll;
    private int paneContent;
    private int leftX, leftY, leftW, leftH;
    private int rightX, rightY, rightW, rightH;
    /** Where each segmented control was drawn (hit id → x, w), for the click → index maths. */
    private final Map<Integer, int[]> segments = new HashMap<>();

    private PlatformPicker picker;
    private String pickerKey;
    private String pickerTitle;
    private long pickerPlatform;

    public RouteSettingsScreen(Route route, Screen previous, Screen mtrScreen) {
        super(Text.literal("Route"));
        this.route = route;
        this.previous = previous;
        this.mtrScreen = mtrScreen;
        this.originalName = route.getName();
        this.scheduleControls = !route.getTransportMode().continuousMovement;
        var font = MinecraftClient.getInstance().textRenderer;

        List<RoutePlatformData> data = route.getRoutePlatforms();
        for (int i = 0; i < data.size(); i++) {
            Platform platform = data.get(i).platform;
            if (platform == null) {
                continue;
            }
            stops.add(new Stop(i, platform, platform.area == null ? "?" : IGui.formatStationName(platform.area.getName()),
                    platform.getName()));
            List<Long> members = ClientPlatformGroups.get(route.getId(), i);
            if (members != null && !members.isEmpty()) {
                groups.put(groupKey(i), new ArrayList<>(members));
                originalGroups.put(groupKey(i), new ArrayList<>(members));
            }
        }
        this.circularCapable = data.size() > 1 && data.get(0).platform != null && data.get(data.size() - 1).platform != null
                && data.get(0).platform.area != null && data.get(data.size() - 1).platform.area != null
                && data.get(0).platform.area.getId() == data.get(data.size() - 1).platform.area.getId();

        this.nameBox = new FlatUi.TextBox(font, 256, false);
        nameBox.load(route.getName());
        this.numberBox = new FlatUi.TextBox(font, 32, false);
        this.initialNumber = route.getRouteNumber() == null ? "" : route.getRouteNumber().trim();
        numberBox.load(initialNumber);
        this.routeRgb = route.getColor() & 0xFFFFFF;
        this.hexBox = new FlatUi.TextBox(font, 7, false);
        hexBox.load(hex(routeRgb));
        hexBox.onChange(value -> {
            String digits = value.startsWith("#") ? value.substring(1) : value;
            if (digits.length() == 6) {
                try {
                    routeRgb = Integer.parseInt(digits, 16);
                    routeColorEdited = true;
                } catch (NumberFormatException ignored) {
                    // still typing
                }
            }
        });
        this.routeType = route.getRouteType();
        this.hidden = route.getHidden();
        this.circular = route.getCircularState();
        boolean off;
        try {
            off = MinecraftClientData.getInstance().getRouteIdHasDisabledAnnouncements(route.getId());
        } catch (Exception e) {
            off = false;
        }
        this.originalAnnouncementsOff = off;
        this.announcementsOff = off;

        this.lineEditor = new LineStyleEditor(this, route);
        LineStyles.RouteStyle override = ClientLineStyles.routeStyle(route.getName());
        this.overrideText = new FlatUi.TextBox(font, LineStyles.MAX_LABEL_LENGTH, false);
        overrideText.load(override.label() == null ? "" : override.label());
        overrideText.onChange(value -> overrideTouched = true);
        this.overrideShape = override.shape();
        this.overrideInk = override.text();
        this.scope = override.isEmpty() ? 0 : 1;

        this.templateBox = new FlatUi.TextBox(font, AddonNetworking.MAX_TEMPLATE_LENGTH, true);
        String template = ClientAnnouncementTemplates.get(route.getId());
        templateBox.load(template == null ? "" : template);
        templateBox.placeholder = "Empty = MTR's own next-station announcement";
        templateBox.onChange(value -> templateTouched = true);

        this.trainRows = new TrainRowsEditor(this, true, List.of(), RouteSettingsScreen::trainSample,
                text -> StationAnnouncerClient.previewLocally(trainSample(text), null, 1.0f, chime, chimeSound));
    }

    static boolean consumeBypass() {
        boolean value = bypassOnce;
        bypassOnce = false;
        return value;
    }

    private static String trainSample(String raw) {
        String text = PaText.fillTrain(raw, TRAIN_SAMPLE);
        return text.contains(PaText.TIME) ? PaText.fill(text, null, PaText.clock()) : text;
    }

    private String groupKey(int stopIndex) {
        return route.getId() + ":" + stopIndex;
    }

    private String routeDisplay() {
        return IGui.formatStationName(nameBox.getText());
    }

    @Override
    protected void init() {
        if (!trainLoaded) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(route.getId());
            ClientPlayNetworking.send(RouteTrainPa.QUERY_C2S, buf);
        }
    }

    /** From RouteTrainScreen's receiver: this route's on-board rows arrived. */
    public void acceptTrainPa(long routeId, RouteTrainPa.Entry entry) {
        if (routeId != route.getId() || trainLoaded) {
            return;
        }
        chime = entry.chime();
        chimeSound = entry.chimeSound();
        trainRows.load(entry.trains().rows);
        trainLoaded = true;
    }

    @Override
    public void removed() {
        StationAnnouncerClient.stopPreview();
        TtsManager.stop();
    }

    // ================================================================ dwell + schedule

    private long storedOverride(long platformId, long routeId) {
        Map<Long, Long> stored = ClientDwellOverrides.get(platformId);
        Long millis = stored == null ? null : stored.get(routeId);
        return millis == null ? -1 : millis;
    }

    /** This route's dwell at a platform after Save (-1 = the platform default). */
    private long ownDwell(long platformId) {
        Long edited = editedDwell.get(platformId);
        return edited != null ? edited : storedOverride(platformId, route.getId());
    }

    private long plannedDwell(Platform platform, long routeId) {
        long own = routeId == route.getId() ? ownDwell(platform.getId()) : storedOverride(platform.getId(), routeId);
        return own >= 0 ? own : platform.getDwellTime();
    }

    private String tag(long platformId) {
        long own = ownDwell(platformId);
        if (own < 0) {
            return "Default";
        }
        if (editedDwell.containsKey(platformId)) {
            return "Manual";
        }
        return switch (ClientDwellOverrides.source(platformId, route.getId())) {
            case AddonStore.DWELL_SOURCE_INTERLINE -> "Interlining";
            case AddonStore.DWELL_SOURCE_MANUAL -> "Manual";
            default -> "Own";
        };
    }

    private static int tagColor(String tag) {
        return switch (tag) {
            case "Default" -> TAG_DEFAULT;
            case "Interlining" -> TAG_INTERLINE;
            case "Manual" -> FlatUi.ACCENT_DIM;
            default -> TAG_LEGACY;
        };
    }

    private void updateSchedules() {
        if (!scheduleControls) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRequest > REFRESH_MILLIS) {
            lastRequest = now;
            Set<Long> ids = new LinkedHashSet<>();
            for (Stop stop : stops) {
                ids.add(stop.platform().getId());
            }
            ClientPlatformSchedule.request(new ArrayList<>(ids));
        }
        if (seenScheduleVersion != ClientPlatformSchedule.version() || seenDwellVersion != ClientDwellOverrides.version()) {
            seenScheduleVersion = ClientPlatformSchedule.version();
            seenDwellVersion = ClientDwellOverrides.version();
            results.clear();
        }
    }

    /** The platform's occupancy with this screen's edits, or null while there is no data. */
    private PlatformOccupancy.Result result(Platform platform) {
        PlatformOccupancy.Result cached = results.get(platform.getId());
        if (cached != null) {
            return cached;
        }
        ClientPlatformSchedule.Data data = ClientPlatformSchedule.get(platform.getId());
        if (data == null) {
            return null;
        }
        ClientHoldRules.Rule rule = ClientHoldRules.get(platform.getId());
        long extra = rule == null || rule.watched().isEmpty() ? 0 : (rule.seconds() + rule.transferSeconds()) * 1_000L;
        PlatformOccupancy.Result result = PlatformOccupancy.compute(data.sidings(), data.day(), new PlatformOccupancy.Plan() {
            @Override
            public long dwell(long routeId, long bakedDwell) {
                return plannedDwell(platform, routeId);
            }

            @Override
            public long holdExtra() {
                return extra;
            }
        });
        results.put(platform.getId(), result);
        return result;
    }

    private long bakedDwell(Platform platform) {
        ClientPlatformSchedule.Data data = ClientPlatformSchedule.get(platform.getId());
        if (data == null) {
            return -1;
        }
        for (PlatformOccupancy.Siding siding : data.sidings()) {
            for (PlatformOccupancy.Stop stop : siding.stops()) {
                if (stop.routeId() == route.getId()) {
                    return stop.baked();
                }
            }
        }
        return -1;
    }

    // ================================================================ drawing

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        updateSchedules();
        segments.clear();
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);

        // Top bar.
        FlatUi.rect(c, 8, 7, 3, 11, 0xFF000000 | currentRgb());
        int buttonsW = 56 + 54 + (mtrScreen != null ? 66 : 0);
        c.drawText(textRenderer, trim(routeDisplay(), Math.max(20, width - buttonsW - 26)), 15, 9, FlatUi.TEXT, false);
        button(c, mx, my, "Save", width - 56, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.PRIMARY, true, HIT_SAVE, 0);
        button(c, mx, my, "Cancel", width - 110, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.FLAT, true, HIT_CANCEL, 0);
        if (mtrScreen != null) {
            button(c, mx, my, "MTR screen", width - 176, 4, 62, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.GHOST, true, HIT_MTR, 0);
        }

        int top = 26;
        int bottom = height - 6;
        leftW = MathHelper.clamp(Math.round(width * 0.38f), 150, 260);
        leftX = 6;
        leftY = top;
        leftH = bottom - top;
        rightX = leftX + leftW + 6;
        rightY = top;
        rightW = width - rightX - 6;
        rightH = leftH;

        FlatUi.pane(c, leftX, leftY, leftW, leftH);
        beginClip(c, leftX + 1, leftY + 1, leftW - 2, leftH - 2);
        int end = drawStops(c, mx, my, leftX + 6, leftY + 5 - listScroll, leftW - 12);
        listContent = end + listScroll - leftY + 4;
        endClip(c);
        listScroll = MathHelper.clamp(listScroll, 0, Math.max(0, listContent - leftH));
        FlatUi.scrollThumb(c, leftX + leftW - 1, leftY + 1, leftH - 2, listContent, listScroll);

        FlatUi.pane(c, rightX, rightY, rightW, rightH);
        int contentTop = rightY;
        if (selectedStop < 0) {
            int sx = rightX + 6;
            int sw = rightW - 12;
            FlatUi.segmented(c, textRenderer, TABS, tab, sx, rightY + 5, sw, 15, mx, my);
            segment(HIT_TAB, sx, sw);
            hit(sx, rightY + 5, sw, 15, HIT_TAB, 0);
            contentTop = rightY + 24;
        }
        int viewH = rightY + rightH - contentTop - 1;
        beginClip(c, rightX + 1, contentTop, rightW - 2, viewH);
        int x = rightX + 8;
        int w = rightW - 16;
        int y = contentTop + 4 - paneScroll;
        if (selectedStop >= 0 && selectedStop < stops.size()) {
            end = drawStopInspector(c, mx, my, stops.get(selectedStop), x, y, w);
        } else {
            end = switch (tab) {
                case 1 -> drawStyleTab(c, mx, my, x, y, w);
                case 2 -> drawAnnounceTab(c, mx, my, x, y, w);
                case 3 -> drawTrainTab(c, mx, my, x, y, w);
                default -> drawRouteTab(c, mx, my, x, y, w);
            };
        }
        paneContent = end + paneScroll - contentTop + 4;
        endClip(c);
        paneScroll = MathHelper.clamp(paneScroll, 0, Math.max(0, paneContent - viewH));
        FlatUi.scrollThumb(c, rightX + rightW - 1, contentTop, viewH, paneContent, paneScroll);

        if (picker != null) {
            drawPickerModal(c, mx, my);
        }
    }

    private void segment(int id, int x, int w) {
        segments.put(id, new int[]{x, w});
    }

    private int segmentIndex(int id, double mx, int count) {
        int[] s = segments.get(id);
        if (s == null) {
            return 0;
        }
        return MathHelper.clamp((int) ((mx - s[0]) * count / Math.max(1, s[1])), 0, count - 1);
    }

    private int currentRgb() {
        if (routeColorEdited) {
            return routeRgb;
        }
        return lineEditor.colorEdited() ? lineEditor.color() : routeRgb;
    }

    // ------------------------------------------------------------ stops (left)

    private int drawStops(DrawContext c, int mx, int my, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "Stops", x, y);
        String count = stops.size() + (stops.size() == 1 ? " stop" : " stops");
        c.drawText(textRenderer, count, x + w - textWidth(count), y, FlatUi.TEXT_FAINT, false);
        y += 13;
        if (stops.isEmpty()) {
            c.drawText(textRenderer, trim("No stops yet — add them on MTR's map.", w), x, y, FlatUi.TEXT_FAINT, false);
            return y + 12;
        }
        int color = 0xFF000000 | currentRgb();
        int rh = scheduleControls ? 24 : 14;
        int lineX = x + 4;
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            boolean hovered = picker == null && FlatUi.inside(mx, my, x - 3, y, w + 6, rh)
                    && my >= leftY && my < leftY + leftH;
            if (selectedStop == i) {
                FlatUi.rect(c, x - 3, y, w + 6, rh, FlatUi.SELECTED);
            } else if (hovered) {
                FlatUi.rect(c, x - 3, y, w + 6, rh, FlatUi.HOVER);
            }
            // The strip: the route line through every stop, a tick per stop.
            int top = i == 0 ? y + 5 : y;
            int bottom = i == stops.size() - 1 ? y + 6 : y + rh + 2;
            FlatUi.rect(c, lineX, top, 3, bottom - top, color);
            FlatUi.rect(c, lineX - 2, y + 3, 7, 5, 0xFF101014);
            FlatUi.rect(c, lineX - 1, y + 4, 5, 3, groups.containsKey(groupKey(stop.index())) ? AMBER : FlatUi.TEXT);

            String badge = "";
            int badgeColor = FlatUi.TEXT_FAINT;
            if (scheduleControls) {
                PlatformOccupancy.Result result = result(stop.platform());
                if (result != null) {
                    long[] counts = conflictCounts(result);
                    if (counts[0] > 0) {
                        badge = "⚠ " + counts[0];
                        badgeColor = FlatUi.DANGER;
                    } else if (counts[1] > 0) {
                        badge = "? " + counts[1];
                        badgeColor = AMBER;
                    }
                }
            }
            int badgeW = badge.isEmpty() ? 0 : textWidth(badge) + 4;
            String platform = "pl. " + stop.platformName();
            int platformW = textWidth(platform) + 6;
            c.drawText(textRenderer, trim(stop.station(), w - 14 - badgeW - platformW), x + 12, y + 2, FlatUi.TEXT, false);
            c.drawText(textRenderer, platform, x + w - badgeW - platformW + 6, y + 2, FlatUi.TEXT_FAINT, false);
            if (!badge.isEmpty()) {
                c.drawText(textRenderer, badge, x + w - badgeW + 4, y + 2, badgeColor, false);
            }
            if (scheduleControls) {
                long planned = plannedDwell(stop.platform(), route.getId());
                String dwell = PlatformSettingsScreen.formatDwell(planned);
                c.drawText(textRenderer, dwell, x + 12, y + 13, FlatUi.TEXT_DIM, false);
                String tag = tag(stop.platform().getId());
                int tx = x + 12 + textWidth(dwell) + 4;
                int tw = FlatUi.chip(c, textRenderer, tag, tx, y + 11, tagColor(tag), false);
                long baked = bakedDwell(stop.platform());
                if (baked >= 0 && Math.abs(baked - planned) >= 500) {
                    c.drawText(textRenderer, trim("· now " + PlatformSettingsScreen.formatDwell(baked),
                            Math.max(0, w - (tx - x) - tw - 4)), tx + tw + 3, y + 13, AMBER, false);
                }
            }
            hit(x - 3, y, w + 6, rh, HIT_STOP, i);
            y += rh + 2;
        }
        return y;
    }

    /** {hard, maybe} conflicts involving this route at a platform. */
    private long[] conflictCounts(PlatformOccupancy.Result result) {
        long[] out = new long[2];
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            if (conflict.blocker().routeId() == route.getId() || conflict.blocked().routeId() == route.getId()) {
                out[conflict.maybe() ? 1 : 0]++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ Route tab

    private int drawRouteTab(DrawContext c, int mx, int my, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "Name", x, y);
        y += 11;
        box(c, nameBox, x, y, w, 16, mx, my);
        y += 19;
        y = para(c, "Before || is the line (\"Line 4\"), after it this route (\"North Limited\"); | separates languages.",
                x, y, w, FlatUi.TEXT_FAINT);

        FlatUi.heading(c, textRenderer, "Colour (this route)", x, y);
        y += 11;
        if (!routeColorEdited && !hexBox.isFocused()) {
            hexBox.load(hex(currentRgb())); // follows the line colour until edited here
        }
        FlatUi.rect(c, x, y, 16, 16, 0xFF000000 | currentRgb());
        FlatUi.outline(c, x, y, 16, 16, FlatUi.BORDER_STRONG);
        box(c, hexBox, x + 20, y, 60, 16, mx, my);
        c.drawText(textRenderer, trim("Style tab colours the whole line", Math.max(0, w - 88)), x + 86, y + 4, FlatUi.TEXT_FAINT, false);
        y += 20;
        int sw = 12;
        int perRow = Math.max(4, (w + 2) / (sw + 2));
        for (int i = 0; i < LineStyleEditor.PALETTE.length; i++) {
            int sx = x + (i % perRow) * (sw + 2);
            int sy = y + (i / perRow) * (sw + 2);
            int rgb = LineStyleEditor.PALETTE[i].rgb();
            FlatUi.rect(c, sx, sy, sw, sw, 0xFF000000 | rgb);
            if (rgb == currentRgb()) {
                FlatUi.outline(c, sx - 1, sy - 1, sw + 2, sw + 2, FlatUi.TEXT);
            } else if (FlatUi.inside(mx, my, sx, sy, sw, sw)) {
                FlatUi.outline(c, sx, sy, sw, sw, FlatUi.TEXT_DIM);
            }
            hit(sx, sy, sw, sw, HIT_SWATCH, i);
        }
        y += ((LineStyleEditor.PALETTE.length + perRow - 1) / perRow) * (sw + 2) + 6;

        FlatUi.heading(c, textRenderer, "Route number", x, y);
        y += 11;
        box(c, numberBox, x, y, Math.min(w, 80), 16, mx, my);
        c.drawText(textRenderer, trim("shown on PIDS and as the bullet's auto text", Math.max(0, w - 88)),
                x + Math.min(w, 80) + 6, y + 4, FlatUi.TEXT_FAINT, false);
        y += 21;

        FlatUi.heading(c, textRenderer, "Type", x, y);
        y += 11;
        FlatUi.segmented(c, textRenderer, TYPE_LABELS, routeType.ordinal(), x, y, w, 15, mx, my);
        segment(HIT_TYPE, x, w);
        hit(x, y, w, 15, HIT_TYPE, 0);
        y += 20;
        toggleRow(c, mx, my, "Hidden from maps and lists", hidden, x, y, w, HIT_HIDDEN, 0);
        y += 16;
        if (circularCapable) {
            FlatUi.heading(c, textRenderer, "Circular", x, y);
            y += 11;
            FlatUi.segmented(c, textRenderer, CIRCULAR_LABELS, circular.ordinal(), x, y, w, 15, mx, my);
            segment(HIT_CIRCULAR, x, w);
            hit(x, y, w, 15, HIT_CIRCULAR, 0);
            y += 20;
        }
        int count = lineEditor.routeCount();
        y += 2;
        y = para(c, "Line " + lineEditor.line() + " · " + count + (count == 1 ? " route" : " routes"), x, y, w, FlatUi.TEXT_DIM);
        return y;
    }

    // ------------------------------------------------------------ Style tab

    private int drawStyleTab(DrawContext c, int mx, int my, int x, int y, int w) {
        int count = lineEditor.routeCount();
        String[] labels = {SCOPE_LABELS[0] + " (" + count + ")", SCOPE_LABELS[1]};
        FlatUi.segmented(c, textRenderer, labels, scope, x, y, w, 15, mx, my);
        segment(HIT_SCOPE, x, w);
        hit(x, y, w, 15, HIT_SCOPE, 0);
        y += 20;
        if (scope == 0) {
            y = para(c, "Applies to every route of " + lineEditor.line() + ". A route's own text, shape or lettering (This route) wins.",
                    x, y, w, FlatUi.TEXT_FAINT);
            int previewH = MathHelper.clamp(rightH - 60, 150, 230);
            FlatUi.rect(c, x - 2, y, w + 4, previewH, FlatUi.GROUND);
            lineEditor.drawPreview(c, x - 2, y, w + 4, previewH);
            y += previewH + 6;
            return lineEditor.drawInspector(c, mx, my, x, y, w);
        }

        // This route: compare its bullet with the line's.
        RouteBullets.Bullet lineBullet = lineEditor.bullet();
        RouteBullets.Bullet own = overrideBullet();
        int heroH = 46;
        FlatUi.rect(c, x, y, w, heroH, 0xFF141414);
        float r = 14;
        LineStyleEditor.drawBullet(c, lineBullet, x + w / 4f, y + heroH / 2f - 4, r);
        LineStyleEditor.drawBullet(c, own, x + w * 3 / 4f, y + heroH / 2f - 4, r);
        String lineLabel = "line";
        String ownLabel = "this route";
        c.drawText(textRenderer, lineLabel, x + w / 4 - textWidth(lineLabel) / 2, y + heroH - 10, FlatUi.TEXT_FAINT, false);
        c.drawText(textRenderer, ownLabel, x + w * 3 / 4 - textWidth(ownLabel) / 2, y + heroH - 10, FlatUi.TEXT_DIM, false);
        y += heroH + 6;
        y = para(c, "Only what you set here differs from the line — an express can wear a diamond or \"4X\".",
                x, y, w, FlatUi.TEXT_FAINT);

        FlatUi.heading(c, textRenderer, "Bullet text", x, y);
        y += 11;
        overrideText.placeholder = "same as line (" + lineBullet.label() + ")";
        box(c, overrideText, x, y, Math.min(w, 120), 16, mx, my);
        y += 21;
        FlatUi.heading(c, textRenderer, "Shape", x, y);
        y += 11;
        int shapeIndex = overrideShape == null ? 0 : overrideShape.ordinal() + 1;
        FlatUi.segmented(c, textRenderer, OV_SHAPES, shapeIndex, x, y, w, 15, mx, my);
        segment(HIT_OV_SHAPE, x, w);
        hit(x, y, w, 15, HIT_OV_SHAPE, 0);
        y += 20;
        FlatUi.heading(c, textRenderer, "Lettering", x, y);
        y += 11;
        int inkIndex = overrideInk == null ? 0 : overrideInk.ordinal() + 1;
        FlatUi.segmented(c, textRenderer, OV_INKS, inkIndex, x, y, w, 15, mx, my);
        segment(HIT_OV_INK, x, w);
        hit(x, y, w, 15, HIT_OV_INK, 0);
        y += 20;
        y = para(c, "Colour: this route's own colour is on the Route tab.", x, y, w, FlatUi.TEXT_FAINT);
        button(c, mx, my, "Same as line", x, y, 80, 15, FlatUi.ButtonStyle.GHOST, true, HIT_OV_CLEAR, 0);
        return y + 20;
    }

    private LineStyles.RouteStyle editedOverride() {
        String text = LineStyles.cleanLabel(overrideText.getText());
        return new LineStyles.RouteStyle(overrideShape, text.isEmpty() ? null : text, overrideInk);
    }

    private RouteBullets.Bullet overrideBullet() {
        RouteBullets.Bullet line = lineEditor.bullet();
        int color = 0xFF000000 | currentRgb();
        LineStyles.Style lineStyle = lineEditor.style();
        LineStyles.Style merged = editedOverride().over(new LineStyles.Style(lineStyle.shape(),
                lineStyle.label().isEmpty() ? line.label() : lineStyle.label(), lineStyle.text()));
        return new RouteBullets.Bullet(merged.labelOr(line.label()), color, merged.shape(), merged.darkText(color));
    }

    // ------------------------------------------------------------ Announcements tab

    private int drawAnnounceTab(DrawContext c, int mx, int my, int x, int y, int w) {
        toggleRow(c, mx, my, "Next-station announcements", !announcementsOff, x, y, w, HIT_ANNOUNCE, 0);
        y += 16;
        if (announcementsOff) {
            return para(c, "Off: riders hear no next-station announcement on this route.", x, y, w, FlatUi.TEXT_FAINT);
        }
        FlatUi.heading(c, textRenderer, "What riders hear", x, y);
        y += 11;
        int lines = Math.max(3, Math.min(6, templateBox.lineCount() + 1));
        box(c, templateBox, x, y, w, lines * 10 + 6, mx, my);
        y += lines * 10 + 10;

        FlatUi.heading(c, textRenderer, "Insert", x, y);
        y += 11;
        y = chipRow(c, mx, my, x, y, w, TOKENS, HIT_TOKEN, 0);
        y = chipRow(c, mx, my, x, y, w, SECTIONS, HIT_SECTION, 0);
        FlatUi.heading(c, textRenderer, "Start from", x, y);
        y += 11;
        y = chipRow(c, mx, my, x, y, w, PRESETS, HIT_PRESET, 0);
        y += 2;

        FlatUi.heading(c, textRenderer, "Preview", x, y);
        button(c, mx, my, "▶ Hear", x + w - 44, y - 3, 44, 13, FlatUi.ButtonStyle.FLAT, true, HIT_HEAR, 0);
        y += 12;
        y = para(c, previewText(), x, y, w - 4, FlatUi.TEXT);
        y = para(c, "{next} next stop · {station} this stop · {dest} last stop · [terminus]…[/terminus] only at the end.",
                x, y, w, FlatUi.TEXT_FAINT);
        button(c, mx, my, "Clear template", x, y, 86, 15, FlatUi.ButtonStyle.GHOST, true, HIT_TEMPLATE_CLEAR, 0);
        return y + 20;
    }

    /** A wrapping row of small buttons; {@code items[i][0]} is the label. */
    private int chipRow(DrawContext c, int mx, int my, int x, int y, int w, String[][] items, int id, int unused) {
        int cx = x;
        for (int i = 0; i < items.length; i++) {
            int bw = textWidth(items[i][0]) + 10;
            if (cx + bw > x + w && cx > x) {
                cx = x;
                y += 16;
            }
            button(c, mx, my, items[i][0], cx, y, bw, 13, FlatUi.ButtonStyle.FLAT, true, id, i);
            cx += bw + 3;
        }
        return y + 18;
    }

    private String previewText() {
        String template = templateBox.getText();
        if (template.isBlank()) {
            return "(MTR's own announcement)";
        }
        return AnnouncementComposer.renderWithValues(template, "Union Square", "Canal Street", "Harbor North",
                routeDisplay(), numberBox.getText().isBlank() ? "4" : numberBox.getText().trim(), SAMPLE_INTERCHANGES, false);
    }

    // ------------------------------------------------------------ Train PA tab

    private int drawTrainTab(DrawContext c, int mx, int my, int x, int y, int w) {
        if (!trainLoaded) {
            c.drawText(textRenderer, "Asking the server…", x, y, FlatUi.TEXT_FAINT, false);
            return y + 12;
        }
        y = para(c, "Spoken to everyone riding this route's trains, at every stop.", x, y, w, FlatUi.TEXT_DIM);
        toggleRow(c, mx, my, "Chime first", chime, x, y, w, HIT_CHIME, 0);
        y += 17;
        FlatUi.heading(c, textRenderer, "Announcements", x, y);
        y += 11;
        return trainRows.draw(c, mx, my, x, y, w);
    }

    // ------------------------------------------------------------ stop inspector

    private int drawStopInspector(DrawContext c, int mx, int my, Stop stop, int x, int y, int w) {
        button(c, mx, my, "← Route", x, y, 50, 14, FlatUi.ButtonStyle.GHOST, true, HIT_BACK, 0);
        String head = "Stop " + (stop.index() + 1) + " of " + route.getRoutePlatforms().size();
        c.drawText(textRenderer, head, x + w - textWidth(head), y + 3, FlatUi.TEXT_FAINT, false);
        y += 18;
        c.drawText(textRenderer, trim(stop.station(), w), x, y, FlatUi.TEXT, false);
        y += 11;
        c.drawText(textRenderer, trim("Platform " + stop.platformName(), w), x, y, FlatUi.TEXT_DIM, false);
        y += 14;
        Platform platform = stop.platform();
        long platformId = platform.getId();

        if (scheduleControls) {
            FlatUi.heading(c, textRenderer, "Dwell here (this route)", x, y);
            y += 12;
            long own = ownDwell(platformId);
            FlatUi.segmented(c, textRenderer, new String[]{"Platform default", "Own dwell"}, own >= 0 ? 1 : 0, x, y, w, 15, mx, my);
            segment(HIT_OWN_MODE, x, w);
            hit(x, y, w, 15, HIT_OWN_MODE, 0);
            y += 19;
            if (own >= 0) {
                int half = MathHelper.clamp((int) Math.round(own / 500.0), MIN_OWN_HALF_SECONDS, MAX_HALF_SECONDS);
                slider(c, mx, my, K_OWN, "", 0, half, MIN_OWN_HALF_SECONDS, MAX_HALF_SECONDS,
                        PlatformSettingsScreen.formatDwell(own), x, y, w - 30);
                FlatUi.iconButton(c, textRenderer, "−", x + w - 26, y, 12, mx, my, FlatUi.TEXT_DIM);
                hit(x + w - 26, y, 12, 12, HIT_OWN_STEP, -1);
                FlatUi.iconButton(c, textRenderer, "+", x + w - 12, y, 12, mx, my, FlatUi.TEXT_DIM);
                hit(x + w - 12, y, 12, 12, HIT_OWN_STEP, 1);
                y += 16;
                String tag = tag(platformId);
                long delta = own - platform.getDwellTime();
                String source = switch (tag) {
                    case "Interlining" -> "Set by Interlining";
                    case "Manual" -> "Set by hand";
                    default -> "Set earlier";
                };
                y = para(c, source + " · " + (delta >= 0 ? "+" : "−") + PlatformSettingsScreen.formatDwell(Math.abs(delta))
                        + " vs the platform's " + PlatformSettingsScreen.formatDwell(platform.getDwellTime()),
                        x, y, w, tag.equals("Interlining") ? AMBER : FlatUi.TEXT_DIM);
                button(c, mx, my, "Reset to default", x, y, Math.min(w, 100), 15, FlatUi.ButtonStyle.FLAT, true, HIT_RESET, 0);
                y += 19;
            } else {
                y = para(c, "Uses the platform's " + PlatformSettingsScreen.formatDwell(platform.getDwellTime()) + ".",
                        x, y, w, FlatUi.TEXT_DIM);
            }
            long baked = bakedDwell(platform);
            long planned = plannedDwell(platform, route.getId());
            if (baked >= 0 && Math.abs(baked - planned) >= 500) {
                y = para(c, "Trains still dwell " + PlatformSettingsScreen.formatDwell(baked)
                        + " here — Save regenerates the depots.", x, y, w, AMBER);
            }
            y += 2;
            y = drawStopConflicts(c, stop, x, y, w);
            y += 4;
        }

        FlatUi.heading(c, textRenderer, "Platform group", x, y);
        y += 12;
        List<Long> members = groups.get(groupKey(stop.index()));
        boolean grouped = members != null && !members.isEmpty();
        y = para(c, grouped ? "Rotates over " + platformNames(members) : "Always this platform", x, y, w,
                grouped ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        button(c, mx, my, grouped ? "Edit…" : "Make a group…", x, y, 80, 15, FlatUi.ButtonStyle.FLAT, true,
                HIT_GROUP_EDIT, selectedStop);
        if (grouped) {
            button(c, mx, my, "Clear", x + 84, y, 44, 15, FlatUi.ButtonStyle.GHOST, true, HIT_GROUP_CLEAR, selectedStop);
        }
        y += 20;

        // (Temporary stop changes — skip this stop / add one after it — go here once they work again.)

        button(c, mx, my, "Open platform screen", x, y, Math.min(w, 130), 15, FlatUi.ButtonStyle.FLAT, true,
                HIT_OPEN_PLATFORM, selectedStop);
        y += 19;
        return para(c, "All routes at this platform, its default dwell, the connection hold and the timeline.",
                x, y, w, FlatUi.TEXT_FAINT);
    }

    private int drawStopConflicts(DrawContext c, Stop stop, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "Schedule check", x, y);
        y += 12;
        PlatformOccupancy.Result result = result(stop.platform());
        if (result == null) {
            return para(c, ClientPlatformSchedule.get(stop.platform().getId()) == null ? "Loading the timetable…"
                    : "No timetabled trains.", x, y, w, FlatUi.TEXT_FAINT);
        }
        ClientPlatformSchedule.Data data = ClientPlatformSchedule.get(stop.platform().getId());
        long now = data == null ? 0 : Math.floorMod(data.simNow(), Math.max(1, result.day()));
        long routeId = route.getId();
        int shown = 0;
        int total = 0;
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            boolean blocker = conflict.blocker().routeId() == routeId;
            boolean blocked = conflict.blocked().routeId() == routeId;
            if (!blocker && !blocked) {
                continue;
            }
            total++;
            if (shown >= 5) {
                continue;
            }
            shown++;
            long in = Math.floorMod(conflict.blocked().arrive() - now, result.day());
            String when = " (in " + PlatformSettingsScreen.formatDwell(in) + ")";
            String text;
            if (blocker) {
                String other = conflict.sameRoute() ? "the next train of this route" : routeName(conflict.blocked().routeId());
                text = (conflict.maybe() ? "Might hold " : "Holds ") + other + " outside "
                        + PlatformSettingsScreen.formatDwell(conflict.waitMillis()) + when;
            } else {
                text = (conflict.maybe() ? "Might wait " : "Waits ") + PlatformSettingsScreen.formatDwell(conflict.waitMillis())
                        + " behind " + routeName(conflict.blocker().routeId()) + when;
            }
            y = para(c, (conflict.maybe() ? "? " : "⚠ ") + text, x, y, w, conflict.maybe() ? AMBER : FlatUi.DANGER);
        }
        if (total == 0) {
            y = para(c, "✓ Never waits for or blocks another train here.", x, y, w, FlatUi.OK);
        } else if (total > shown) {
            y = para(c, "… and " + (total - shown) + " more per cycle", x, y, w, FlatUi.TEXT_FAINT);
        }
        long headway = result.headway(routeId);
        long planned = plannedDwell(stop.platform(), routeId);
        if (headway > 0) {
            y = para(c, "Headway here: " + PlatformSettingsScreen.formatDwell(headway)
                    + (planned >= headway ? "  (shorter than the dwell!)" : ""), x, y, w,
                    planned >= headway ? FlatUi.DANGER : FlatUi.TEXT_FAINT);
        }
        return y;
    }

    // ------------------------------------------------------------ picker modal

    private void openGroupPicker(Stop stop) {
        Position mid = stop.platform().getMidPosition();
        BlockPos origin = new BlockPos((int) mid.getX(), (int) mid.getY(), (int) mid.getZ());
        List<Long> members = groups.getOrDefault(groupKey(stop.index()), List.of());
        picker = new PlatformPicker(origin, members.isEmpty() ? List.of(stop.platform().getId()) : members,
                AddonNetworking.MAX_GROUP_SIZE, Math.min(260, width - 40));
        pickerKey = groupKey(stop.index());
        pickerTitle = "Rotate this stop across (tick " + stop.platformName() + " to keep it):";
        pickerPlatform = stop.platform().getId();
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
            List<Long> members = new ArrayList<>();
            Platform platform = MinecraftClientData.getDashboardInstance().platformIdMap.get(pickerPlatform);
            for (long id : picker.getSelected()) {
                if (platform != null && sameStationAndMode(platform, id) && members.size() < AddonNetworking.MAX_GROUP_SIZE) {
                    members.add(id);
                }
            }
            if (members.isEmpty()) {
                groups.remove(pickerKey);
            } else {
                groups.put(pickerKey, members);
            }
        }
        picker = null;
        pickerKey = null;
    }

    private static boolean sameStationAndMode(Platform platform, long id) {
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
        if (id >= LineStyleEditor.BASE) {
            lineEditor.onHit(id, arg, mx, my);
            return;
        }
        if (id >= TrainRowsEditor.BASE) {
            trainRows.onHit(id, arg);
            return;
        }
        Stop stop = selectedStop >= 0 && selectedStop < stops.size() ? stops.get(selectedStop) : null;
        switch (id) {
            case HIT_SAVE -> saveAndClose();
            case HIT_CANCEL -> close();
            case HIT_MTR -> {
                bypassOnce = true;
                client.setScreen(mtrScreen);
            }
            case HIT_TAB -> {
                tab = segmentIndex(HIT_TAB, mx, TABS.length);
                paneScroll = 0;
            }
            case HIT_STOP -> {
                selectedStop = selectedStop == arg ? -1 : arg;
                paneScroll = 0;
            }
            case HIT_BACK -> {
                selectedStop = -1;
                paneScroll = 0;
            }
            case HIT_TYPE -> routeType = RouteType.values()[segmentIndex(HIT_TYPE, mx, RouteType.values().length)];
            case HIT_HIDDEN -> hidden = !hidden;
            case HIT_CIRCULAR -> circular = Route.CircularState.values()[segmentIndex(HIT_CIRCULAR, mx, 3)];
            case HIT_SWATCH -> {
                routeRgb = LineStyleEditor.PALETTE[arg].rgb();
                routeColorEdited = true;
                hexBox.load(hex(routeRgb));
            }
            case HIT_SCOPE -> {
                scope = segmentIndex(HIT_SCOPE, mx, 2);
                paneScroll = 0;
            }
            case HIT_OV_SHAPE -> {
                int index = segmentIndex(HIT_OV_SHAPE, mx, OV_SHAPES.length);
                overrideShape = index == 0 ? null : LineStyles.Shape.byOrdinal(index - 1);
                overrideTouched = true;
            }
            case HIT_OV_INK -> {
                int index = segmentIndex(HIT_OV_INK, mx, OV_INKS.length);
                overrideInk = index == 0 ? null : LineStyles.TextColor.byOrdinal(index - 1);
                overrideTouched = true;
            }
            case HIT_OV_CLEAR -> {
                overrideShape = null;
                overrideInk = null;
                overrideText.load("");
                overrideTouched = true;
            }
            case HIT_ANNOUNCE -> announcementsOff = !announcementsOff;
            case HIT_TOKEN -> {
                focus(templateBox);
                templateBox.insertToken(TOKENS[arg][1]);
                templateTouched = true;
            }
            case HIT_SECTION -> {
                focus(templateBox);
                templateBox.insert(SECTIONS[arg][1] + SECTIONS[arg][2]);
                int caret = templateBox.getCursor() - SECTIONS[arg][2].length();
                templateBox.select(caret, caret);
                templateTouched = true;
            }
            case HIT_PRESET -> {
                templateBox.load(PRESETS[arg][1]);
                templateTouched = true;
            }
            case HIT_TEMPLATE_CLEAR -> {
                templateBox.load("");
                templateTouched = true;
            }
            case HIT_HEAR -> {
                TtsManager.stop();
                TtsManager.speak(previewText(), 1.0f);
            }
            case HIT_CHIME -> {
                chime = !chime;
                chimeDirty = true;
            }
            case HIT_OWN_MODE -> {
                if (stop != null) {
                    boolean own = segmentIndex(HIT_OWN_MODE, mx, 2) == 1;
                    long platformId = stop.platform().getId();
                    if (own && ownDwell(platformId) < 0) {
                        editedDwell.put(platformId, Math.max(AddonNetworking.MIN_DWELL_MILLIS, stop.platform().getDwellTime()));
                    } else if (!own) {
                        editedDwell.put(platformId, -1L);
                    }
                    results.remove(platformId);
                }
            }
            case HIT_OWN_STEP -> {
                if (stop != null) {
                    long platformId = stop.platform().getId();
                    long own = ownDwell(platformId);
                    if (own >= 0) {
                        long step = (Screen.hasShiftDown() ? 5_000L : 500L) * arg;
                        editedDwell.put(platformId, MathHelper.clamp(own + step, AddonNetworking.MIN_DWELL_MILLIS,
                                AddonNetworking.MAX_DWELL_MILLIS));
                        results.remove(platformId);
                    }
                }
            }
            case HIT_RESET -> {
                if (stop != null) {
                    editedDwell.put(stop.platform().getId(), -1L);
                    results.remove(stop.platform().getId());
                }
            }
            case HIT_GROUP_EDIT -> {
                if (arg >= 0 && arg < stops.size()) {
                    openGroupPicker(stops.get(arg));
                }
            }
            case HIT_GROUP_CLEAR -> {
                if (arg >= 0 && arg < stops.size()) {
                    groups.remove(groupKey(stops.get(arg).index()));
                }
            }
            case HIT_OPEN_PLATFORM -> {
                if (arg >= 0 && arg < stops.size()) {
                    client.setScreen(new PlatformSettingsScreen(stops.get(arg).platform(), this, null));
                }
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

    @Override
    protected void onSlider(int key, int value) {
        if (key >= LineStyleEditor.BASE) {
            lineEditor.onSlider(key, value);
            return;
        }
        if (key >= TrainRowsEditor.BASE) {
            trainRows.onSlider(key, value);
            return;
        }
        if (key == K_OWN && selectedStop >= 0 && selectedStop < stops.size()) {
            long platformId = stops.get(selectedStop).platform().getId();
            editedDwell.put(platformId, value * 500L);
            results.remove(platformId);
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
        if (focused != null && focused.contains(mx, my) && focused.mouseScrolled(mx, my, vertical)) {
            return true;
        }
        int delta = (int) Math.round(-vertical * 14);
        if (FlatUi.inside(mx, my, leftX, leftY, leftW, leftH)) {
            listScroll = MathHelper.clamp(listScroll + delta, 0, Math.max(0, listContent - leftH));
        } else if (FlatUi.inside(mx, my, rightX, rightY, rightW, rightH)) {
            paneScroll = Math.max(0, paneScroll + delta);
        }
        return true;
    }

    @Override
    protected boolean onEnter() {
        if (trainRows.owns(focused)) {
            focus(null);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onEscape() {
        if (picker != null) {
            closePicker(false);
            return true;
        }
        saveAndClose(); // like MTR's Edit Route screen: closing saves
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_S && hasControlDown()) {
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

    // ================================================================ save

    private void saveAndClose() {
        try {
            save();
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not save the route settings", e);
            toast("Save failed — see the log", true);
            return;
        }
        close();
    }

    private void save() {
        MinecraftClientData dashboard = MinecraftClientData.getDashboardInstance();

        // 1. The line's style (writes its colour / number into every route of the line).
        lineEditor.save();

        // 2. This route's own fields through MTR's path (what its screen sends on close).
        String name = nameBox.getText().trim();
        if (!name.isEmpty()) {
            route.setName(name);
        }
        if (routeColorEdited) {
            route.setColor(routeRgb);
        }
        // Edited here = this route's number wins over what the line style wrote.
        String number = numberBox.getText().trim();
        if (!number.equals(initialNumber)) {
            route.setRouteNumber(number);
        }
        route.setRouteType(routeType);
        route.setHidden(hidden);
        route.setCircularState(circularCapable ? circular : Route.CircularState.NONE);
        InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(dashboard).addRoute(route)));
        if (announcementsOff != originalAnnouncementsOff) {
            MinecraftClientData.getInstance().setRouteIdHasDisabledAnnouncements(route.getId(), announcementsOff);
            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketSetRouteIdHasDisabledAnnouncements(route.getId(), announcementsOff));
        }

        // 3. This route's own bullet (keyed by name: a rename moves it).
        LineStyles.RouteStyle override = editedOverride();
        boolean renamed = !LineStyles.routeKey(originalName).equals(LineStyles.routeKey(route.getName()));
        if (overrideTouched || (renamed && !ClientLineStyles.routeStyle(originalName).isEmpty())) {
            if (renamed) {
                ClientLineStyles.sendRoute(originalName, LineStyles.RouteStyle.NONE);
            }
            ClientLineStyles.sendRoute(route.getName(), override);
        }

        // 4. Next-station template.
        if (templateTouched) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(route.getId());
            buf.writeString(templateBox.getText().trim(), AddonNetworking.MAX_TEMPLATE_LENGTH);
            ClientPlayNetworking.send(AddonNetworking.UPDATE_ANNOUNCEMENT_TEMPLATE_C2S, buf);
        }

        // 5. On-board PA.
        if (trainLoaded && (trainRows.dirty || chimeDirty)) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(route.getId());
            new RouteTrainPa.Entry(chime, chimeSound, new TrainAnnouncements(true, List.of(), trainRows.rows)).write(buf);
            ClientPlayNetworking.send(RouteTrainPa.UPDATE_C2S, buf);
        }

        // 6. Platform groups, then 7. dwell per platform (+ regeneration where anything baked changed).
        Set<Long> regenerate = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>(originalGroups.keySet());
        keys.addAll(groups.keySet());
        for (String key : keys) {
            List<Long> now = groups.getOrDefault(key, List.of());
            if (now.equals(originalGroups.getOrDefault(key, List.of()))) {
                continue;
            }
            int stopIndex = Integer.parseInt(key.substring(key.indexOf(':') + 1));
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(route.getId());
            buf.writeVarInt(stopIndex);
            buf.writeVarInt(now.size());
            for (long id : now) {
                buf.writeLong(id);
            }
            ClientPlayNetworking.send(AddonNetworking.UPDATE_PLATFORM_GROUP_C2S, buf);
            for (Stop stop : stops) {
                if (stop.index() == stopIndex) {
                    regenerate.add(stop.platform().getId());
                }
            }
        }
        for (Map.Entry<Long, Long> edit : editedDwell.entrySet()) {
            if (edit.getValue() != storedOverride(edit.getKey(), route.getId())) {
                regenerate.add(edit.getKey());
            }
        }
        for (long platformId : regenerate) {
            Map<Long, Long> stored = ClientDwellOverrides.get(platformId);
            Map<Long, long[]> overrides = new LinkedHashMap<>();
            if (stored != null) {
                stored.forEach((routeId, millis) -> overrides.put(routeId, new long[]{millis, AddonStore.DWELL_SOURCE_UNKNOWN}));
            }
            Long edit = editedDwell.get(platformId);
            if (edit != null) {
                if (edit < 0) {
                    overrides.remove(route.getId());
                } else if (edit != storedOverride(platformId, route.getId())) {
                    overrides.put(route.getId(), new long[]{edit, AddonStore.DWELL_SOURCE_MANUAL});
                }
            }
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(platformId);
            int count = Math.min(AddonNetworking.MAX_ROUTE_OVERRIDES, overrides.size());
            buf.writeVarInt(count);
            overrides.entrySet().stream().limit(count).forEach(entry -> {
                buf.writeLong(entry.getKey());
                buf.writeVarInt((int) MathHelper.clamp(entry.getValue()[0], AddonNetworking.MIN_DWELL_MILLIS,
                        AddonNetworking.MAX_DWELL_MILLIS));
                buf.writeByte((int) entry.getValue()[1]);
            });
            buf.writeBoolean(true);
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
        if (routeId == route.getId()) {
            return routeDisplay();
        }
        for (Route other : MinecraftClientData.getDashboardInstance().routes) {
            if (other.getId() == routeId) {
                return IGui.formatStationName(other.getName());
            }
        }
        return "another route";
    }

    private static String platformNames(List<Long> ids) {
        List<String> names = new ArrayList<>();
        for (long id : ids) {
            Platform other = MinecraftClientData.getDashboardInstance().platformIdMap.get(id);
            names.add(other == null ? "?" : other.getName());
        }
        return String.join(", ", names);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Dev-only (run/commands.txt): {@code #route open <name part>} opens MTR's real Edit
     * Route screen (the swap replaces it); on ours {@code tab N}, {@code stop N},
     * {@code scope N}, {@code own N secs} (dwell at the selected stop), {@code shape N}
     * (route override, 0 = line).
     */
    public static void devHook(MinecraftClient client, String cmd) {
        String[] a = cmd.trim().split("\\s+");
        if (a.length >= 3 && a[1].equals("open")) {
            String part = cmd.substring(cmd.indexOf("open") + 4).trim().toLowerCase(Locale.ROOT);
            org.mtr.mapping.mapper.ScreenExtension parent =
                    client.currentScreen instanceof org.mtr.mapping.mapper.ScreenExtension extension ? extension : null;
            for (Route candidate : MinecraftClientData.getDashboardInstance().routes) {
                if (candidate.getName().toLowerCase(Locale.ROOT).contains(part)) {
                    client.setScreen(new org.mtr.mod.screen.EditRouteScreen(candidate, parent));
                    return;
                }
            }
            StationAnnouncer.LOGGER.info("[route dev] no route matching '{}'", part);
            return;
        }
        if (!(client.currentScreen instanceof RouteSettingsScreen screen) || a.length < 2) {
            return;
        }
        try {
            switch (a[1]) {
                case "tab" -> {
                    screen.selectedStop = -1;
                    screen.tab = Integer.parseInt(a[2]);
                }
                case "stop" -> screen.selectedStop = Integer.parseInt(a[2]);
                case "scope" -> screen.scope = Integer.parseInt(a[2]);
                case "own" -> {
                    screen.selectedStop = Integer.parseInt(a[2]);
                    long platformId = screen.stops.get(screen.selectedStop).platform().getId();
                    screen.editedDwell.put(platformId, Math.round(Double.parseDouble(a[3]) * 1000));
                    screen.results.remove(platformId);
                }
                case "shape" -> {
                    int index = Integer.parseInt(a[2]);
                    screen.overrideShape = index == 0 ? null : LineStyles.Shape.byOrdinal(index - 1);
                    screen.overrideTouched = true;
                }
                case "save" -> screen.saveAndClose();
                case "scroll" -> screen.paneScroll = Integer.parseInt(a[2]);
                default -> StationAnnouncer.LOGGER.info("[route dev] unknown '{}'", cmd);
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.info("[route dev] bad command '{}': {}", cmd, e.toString());
        }
    }
}
