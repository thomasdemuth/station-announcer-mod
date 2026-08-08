package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Station;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.ArrivalsCacheClient;
import org.mtr.mod.data.VehicleExtension;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 4 — advanced manual driving HUD (client-only, no mixins).
 *
 * <p>Shows, while the player is DRIVING a vehicle (riding it AND holding a
 * valid driver key for its depot — the exact visibility condition of MTR's own
 * {@code DrivingGuiRenderer}): upcoming speed-limit changes, upcoming signal
 * blocks with an aspect estimate, the next stop (name / distance / ETA), a
 * door state row (with the door-obstruction alert from the addon's server
 * feature), and an EARLY / ON TIME / LATE schedule indicator.</p>
 *
 * <p><b>Performance contract</b> (ARCHITECTURE §6): the lookahead is computed
 * in a {@link ClientTickEvents} handler throttled to {@code hudUpdateHz}
 * (default 4 Hz) — never per frame. The {@link HudRenderCallback} only draws
 * the cached {@link Snapshot} (pre-built text rows; the only per-frame work is
 * a handful of {@code TextRenderer.getWidth} calls, fills, and the blink phase
 * of the obstruction alert — a color choice, not a recomputation). The
 * schedule check reuses the arrivals trick proven by {@code RailroadRouteData}:
 * a cached {@code ArrivalsCacheClient.requestArrivals} fetch (≥{@code
 * hudArrivalsCacheMillis}, default 1000 ms) for the next platform id, matching
 * our own run by {@code routeId + departureIndex}. When nobody is driving, the
 * per-update work is one small vehicle-set scan and nothing is rendered; with
 * {@code hudEnabled} off, not even that.</p>
 *
 * <p><b>Repeat-infinitely routes</b>: the path walk wraps exactly like
 * {@code Vehicle.simulate} does (on reaching {@code repeatIndex2}, continue at
 * {@code repeatIndex1}; the wrap base is {@code path[repeatIndex2].
 * getStartDistance()} — or the last segment's end when {@code repeatIndex2 ==
 * path.size()} — mirroring how TSC derives {@code totalDistance}). The repeat
 * indices are protected schema fields with no public getter in 4.0.1, read via
 * cached reflection like {@code railProgress}. As a belt-and-braces measure
 * the next-stop section is also sticky: when a refresh finds no stop for any
 * reason, the last known one keeps showing for {@link #NEXT_STOP_GRACE_MILLIS}
 * before the section hides.</p>
 *
 * <p>All state is static, touched only on the client thread, and cleared on
 * disconnect.</p>
 */
@Environment(EnvType.CLIENT)
public final class DrivingHud {
    // ---------------------------------------------------------------- colors
    private static final int COLOR_BACKGROUND = 0xA0101014;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_FAINT = 0xFFB0B0B8;
    private static final int COLOR_GREEN = 0xFF44DD66;
    private static final int COLOR_AMBER = 0xFFFFAA00;
    private static final int COLOR_RED = 0xFFFF5555;

    private static final int PANEL_PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int GAP_HEIGHT = 3;
    private static final int BULLET_SIZE = 6;
    private static final int BULLET_INDENT = 9;
    private static final int MIN_PANEL_WIDTH = 80;

    /** Entries listed per section, so the panel never grows past a glance. */
    private static final int MAX_SPEED_LIMIT_ENTRIES = 3;
    private static final int MAX_SIGNAL_ENTRIES = 3;

    /** How long the last known next stop keeps showing when a refresh finds none. */
    private static final long NEXT_STOP_GRACE_MILLIS = 3000;

    /** Hard caps on the path walk, whatever the config says. */
    private static final int MAX_WALK_SEGMENTS = 4096;
    private static final double MAX_WALK_DISTANCE = 100_000;

    /** Obstruction alert blink: 250 ms on, 250 ms off (2 Hz). */
    private static final long BLINK_PERIOD_MILLIS = 250;

    // ------------------------------------------------------------- lifecycle
    private static KeyBinding settingsKey;

    /** What the render callback draws; null = draw nothing. Client thread only. */
    @Nullable
    private static Snapshot snapshot;
    private static long nextUpdateMillis;

    // Cached arrivals fetch for the on-time indicator (client thread only).
    private static long arrivalsFetchedAt;
    private static long arrivalsPlatformId;
    private static ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
    private static final LongArrayList ARRIVALS_REQUEST_IDS = new LongArrayList();

    // Sticky next-stop section (see class javadoc).
    @Nullable
    private static List<Row> lastNextStopRows;
    private static long lastNextStopAt;

    /** Cached accessor for {@code VehicleSchema.railProgress} (protected, no getter in 4.0.1). */
    @Nullable
    private static Field railProgressField;
    private static boolean railProgressLookupFailed;

    /** Cached accessors for {@code VehicleExtraDataSchema.repeatIndex1/2} (protected fields; getters are protected too). */
    @Nullable
    private static Field repeatIndex1Field;
    @Nullable
    private static Field repeatIndex2Field;
    private static boolean repeatIndexLookupFailed;

    private DrivingHud() {
    }

    public static void register() {
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.driving_hud_settings", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H, "key.station_announcer.category"));
        ClientTickEvents.END_CLIENT_TICK.register(DrivingHud::tick);
        HudRenderCallback.EVENT.register(DrivingHud::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    /** Drops every cache; called on disconnect (and when the feature turns off). */
    public static void clear() {
        snapshot = null;
        nextUpdateMillis = 0;
        arrivalsFetchedAt = 0;
        arrivalsPlatformId = 0;
        arrivals = new ObjectArrayList<>();
        ARRIVALS_REQUEST_IDS.clear();
        lastNextStopRows = null;
        lastNextStopAt = 0;
    }

    // ------------------------------------------------------------- tick side

    private static void tick(MinecraftClient client) {
        if (settingsKey != null) {
            while (settingsKey.wasPressed()) {
                client.setScreen(new DrivingHudScreen(client.currentScreen));
            }
        }
        AddonClientConfig config = AddonClientConfig.get();
        if (!config.hudEnabled || client.player == null) {
            if (snapshot != null) {
                clear();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextUpdateMillis) {
            return;
        }
        nextUpdateMillis = now + 1000L / MathHelper.clamp(config.hudUpdateHz, 1, 20);
        try {
            snapshot = compute(config, now);
        } catch (Exception e) {
            // MTR's client data can be swapped underneath us mid-sync; a HUD
            // that blinks off for one update beats a crash loop.
            snapshot = null;
        }
    }

    /** The driven vehicle, found exactly like {@code DrivingGuiRenderer}'s visibility condition. */
    @Nullable
    private static VehicleExtension findDrivenVehicle() {
        for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
            if (VehicleRidingMovement.isRiding(vehicle.getId())
                    && VehicleRidingMovement.getValidHoldingKey(vehicle.vehicleExtraData.getDepotId()) != null) {
                return vehicle;
            }
        }
        return null;
    }

    @Nullable
    private static Snapshot compute(AddonClientConfig config, long now) {
        VehicleExtension vehicle = findDrivenVehicle();
        if (vehicle == null) {
            return null;
        }
        double railProgress = readRailProgress(vehicle);
        if (Double.isNaN(railProgress)) {
            return null;
        }

        VehicleExtraData extra = vehicle.vehicleExtraData;
        ObjectImmutableList<PathData> path = extra.immutablePath;
        if (path.isEmpty()) {
            return null;
        }
        // Last index whose startDistance <= railProgress; -1 before the path
        // starts. When stopped exactly at a platform boundary this is already
        // the segment AFTER the platform, so the scans naturally look at what
        // comes next; while still rolling INTO a platform the head segment IS
        // the platform segment, which findNextStop checks separately.
        int headIndex = Utilities.getIndexFromConditionalList(path, railProgress);

        double speed = vehicle.getSpeed(); // meters per millisecond
        double speedKph = speed * 3600;
        double deceleration = Math.max(1E-9, extra.getDeceleration());
        int lookahead = MathHelper.clamp(config.hudLookaheadMeters, 100, 20_000);
        // Our own vehicle marks blockedRailIds up to its braking padding ahead
        // (VehicleExtension "Write signals"); occupancy hits inside that zone
        // would be self-detections and are ignored. Distance is relative to
        // the head, so it works for wrapped segments too.
        double selfPaddingLength = 0.5 * speed * speed / deceleration
                + vehicle.getTransportMode().stoppingSpace;

        List<WalkEntry> walk = buildWalk(extra, path, headIndex, railProgress, lookahead);

        List<Row> rows = new ArrayList<>();

        boolean obstructed = ClientDoorObstructions.isObstructed(vehicle.getId());
        if (obstructed) {
            // The alert outranks every toggle except the master one.
            rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.doors_obstructed_alert")
                    .getString(), COLOR_RED, COLOR_RED, true));
            rows.add(Row.GAP);
        }

        NextStop nextStop = findNextStop(extra, path, headIndex, railProgress, walk);

        if (config.hudShowNextStop) {
            buildNextStopRows(rows, extra, nextStop, speed, deceleration, now);
        }
        if (config.hudShowOnTime) {
            buildOnTimeRow(rows, config, vehicle, extra, nextStop, now);
        }
        if (config.hudShowDoors) {
            buildDoorsRow(rows, vehicle, extra, obstructed);
        }
        if (config.hudShowSpeedLimits) {
            buildSpeedLimitRows(rows, path, headIndex, walk, lookahead, speedKph);
        }
        if (config.hudShowSignals) {
            buildSignalRows(rows, walk, lookahead, selfPaddingLength, extra, nextStop);
        }

        // Trim a trailing section gap.
        while (!rows.isEmpty() && rows.get(rows.size() - 1) == Row.GAP) {
            rows.remove(rows.size() - 1);
        }
        return rows.isEmpty() ? null : new Snapshot(rows);
    }

    // ------------------------------------------------------------- path walk

    /**
     * The segments ahead of the head, each with its distance from the head,
     * wrapping through the repeat indices exactly like {@code Vehicle.simulate}
     * (see class javadoc). The walk covers at least the lookahead and keeps
     * going until it has also seen one platform stop (so the next stop is
     * found regardless of the configured lookahead), bounded by one full extra
     * loop / {@link #MAX_WALK_SEGMENTS} / {@link #MAX_WALK_DISTANCE}.
     */
    private static List<WalkEntry> buildWalk(VehicleExtraData extra, ObjectImmutableList<PathData> path,
                                             int headIndex, double railProgress, int lookahead) {
        int repeatIndex1 = 0;
        int repeatIndex2 = 0;
        long[] repeatIndices = readRepeatIndices(extra);
        if (repeatIndices != null) {
            repeatIndex1 = (int) repeatIndices[0];
            repeatIndex2 = (int) repeatIndices[1];
        }
        // The wrap base mirrors how TSC derives totalDistance for repeating
        // routes: path[repeatIndex2].getStartDistance(), or the very end of
        // the path when repeatIndex2 lands one past it.
        boolean repeats = repeatIndex2 > 0 && repeatIndex1 >= 0
                && repeatIndex1 < repeatIndex2 && repeatIndex1 < path.size() && repeatIndex2 <= path.size();
        double wrapBase = 0;
        double loopStart = 0;
        if (repeats) {
            wrapBase = repeatIndex2 < path.size()
                    ? path.get(repeatIndex2).getStartDistance()
                    : path.get(path.size() - 1).getEndDistance();
            loopStart = path.get(repeatIndex1).getStartDistance();
            if (wrapBase - loopStart <= 0) {
                repeats = false; // degenerate loop — never wrap
            }
        }

        List<WalkEntry> walk = new ArrayList<>();
        long sidingId = extra.getSidingId();
        boolean dwellSeen = false;
        int i = Math.max(0, headIndex + 1);
        double offset = -railProgress; // entry distance = startDistance + offset
        int wraps = 0;
        while (walk.size() < MAX_WALK_SEGMENTS) {
            if (i >= path.size() || (repeats && i >= repeatIndex2)) {
                if (!repeats || wraps >= 1) {
                    break; // path end, or one full extra loop already walked
                }
                offset += wrapBase - loopStart;
                i = repeatIndex1;
                wraps++;
                continue;
            }
            PathData pathData = path.get(i);
            double distance = pathData.getStartDistance() + offset;
            if (distance > MAX_WALK_DISTANCE || (distance > lookahead && dwellSeen)) {
                break;
            }
            walk.add(new WalkEntry(pathData, distance, wraps > 0));
            if (isPlatformDwell(pathData, sidingId)) {
                dwellSeen = true;
            }
            i++;
        }
        return walk;
    }

    private static boolean isPlatformDwell(PathData pathData, long sidingId) {
        return pathData.getDwellTime() > 0 && pathData.getSavedRailBaseId() != 0
                && pathData.getSavedRailBaseId() != sidingId;
    }

    // ------------------------------------------------------------- next stop

    /**
     * The next platform the vehicle will stop at, or null. The head segment
     * itself is checked first: while rolling into a platform the head is
     * already ON the platform segment, and that platform — not the one after —
     * is still the next stop until the head passes its end.
     */
    @Nullable
    private static NextStop findNextStop(VehicleExtraData extra, ObjectImmutableList<PathData> path,
                                         int headIndex, double railProgress, List<WalkEntry> walk) {
        long sidingId = extra.getSidingId();
        if (headIndex >= 0 && headIndex < path.size()) {
            PathData head = path.get(headIndex);
            if (isPlatformDwell(head, sidingId) && head.getEndDistance() > railProgress + 0.5) {
                return new NextStop(head.getSavedRailBaseId(),
                        head.getEndDistance() - railProgress, head.getEndDistance(), false);
            }
        }
        for (WalkEntry entry : walk) {
            PathData pathData = entry.pathData();
            if (isPlatformDwell(pathData, sidingId)) {
                double length = pathData.getEndDistance() - pathData.getStartDistance();
                return new NextStop(pathData.getSavedRailBaseId(),
                        entry.distance() + length, pathData.getEndDistance(), entry.wrapped());
            }
        }
        return null;
    }

    private static void buildNextStopRows(List<Row> rows, VehicleExtraData extra, @Nullable NextStop nextStop,
                                          double speed, double deceleration, long now) {
        if (nextStop == null) {
            // Sticky grace: a refresh that finds no stop (repeat-wrap edges,
            // data mid-sync, …) keeps the last known section briefly instead
            // of blinking the panel section in and out.
            if (lastNextStopRows != null && now - lastNextStopAt < NEXT_STOP_GRACE_MILLIS) {
                rows.addAll(lastNextStopRows);
            } else {
                lastNextStopRows = null;
            }
            return;
        }
        List<Row> section = new ArrayList<>(3);
        section.add(new Row(Text.translatable("gui.station_announcer.driving_hud.next",
                stopName(extra, nextStop.platformId())).getString(), COLOR_TEXT, 0));
        long etaSeconds = etaSeconds(nextStop.distance(), speed, deceleration);
        String distance = formatDistance(nextStop.distance());
        String detail = etaSeconds >= 0
                ? Text.translatable("gui.station_announcer.driving_hud.eta", distance, formatSeconds(etaSeconds)).getString()
                : distance;
        section.add(new Row(detail, COLOR_FAINT, 0));
        section.add(Row.GAP);
        rows.addAll(section);
        lastNextStopRows = List.copyOf(section);
        lastNextStopAt = now;
    }

    /**
     * Name of the platform's station. The platform-map lookup comes first
     * because it always matches the platform whose distance we display; MTR's
     * own {@code getNextStationName()} label points one station further for
     * the stretch where the head is already on the platform segment. Falls
     * back to MTR's label, then the bare platform name.
     */
    private static String stopName(VehicleExtraData extra, long platformId) {
        Platform platform = MinecraftClientData.getInstance().platformIdMap.get(platformId);
        if (platform != null) {
            Station station = platform.area;
            if (station != null && !station.getName().isEmpty()) {
                return firstLang(station.getName());
            }
        }
        if (!extra.getNextStationName().isEmpty()) {
            return firstLang(extra.getNextStationName());
        }
        return platform != null ? firstLang(platform.getName()) : "?";
    }

    /**
     * Naive ETA: cruise at the current speed, then brake at the vehicle's
     * deceleration; when already inside braking distance, the constant-decel
     * arrival time. Negative = unknown (effectively stationary).
     */
    private static long etaSeconds(double distanceMeters, double speed, double deceleration) {
        if (speed < 1E-4) {
            return -1; // < 0.36 km/h — no meaningful estimate while stopped
        }
        double brakingDistance = speed * speed / (2 * deceleration);
        double etaMillis = distanceMeters > brakingDistance
                ? (distanceMeters - brakingDistance) / speed + speed / deceleration
                : Math.sqrt(2 * Math.max(0, distanceMeters) / deceleration);
        return Math.round(etaMillis / 1000);
    }

    // ------------------------------------------------------------ on-time row

    private static void buildOnTimeRow(List<Row> rows, AddonClientConfig config, VehicleExtension vehicle,
                                       VehicleExtraData extra, @Nullable NextStop nextStop, long now) {
        long departureIndex = vehicle.getDepartureIndex();
        if (departureIndex < 0) {
            // Manual sidings run every vehicle with departureIndex == -1 and
            // publish their arrivals the same way — there is no schedule to
            // deviate from. Say so instead of a bare dash.
            rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.sched_manual").getString(),
                    COLOR_FAINT, 0));
            rows.add(Row.GAP);
            return;
        }
        if (nextStop == null) {
            rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.sched_unknown").getString(),
                    COLOR_FAINT, 0));
            rows.add(Row.GAP);
            return;
        }

        long cacheMillis = Math.max(250, config.hudArrivalsCacheMillis);
        if (nextStop.platformId() != arrivalsPlatformId || now - arrivalsFetchedAt >= cacheMillis) {
            arrivalsPlatformId = nextStop.platformId();
            arrivalsFetchedAt = now;
            ARRIVALS_REQUEST_IDS.clear();
            ARRIVALS_REQUEST_IDS.add(nextStop.platformId());
            arrivals = ArrivalsCacheClient.INSTANCE.requestArrivals(ARRIVALS_REQUEST_IDS);
        }

        // One specific run is identified by routeId + departureIndex — the
        // same pair RailroadRouteData matches on. thisRouteId is the primary
        // key; nextRouteId covers the stop where one route hands over to the
        // next within the same depot block.
        long thisRouteId = extra.getThisRouteId();
        long nextRouteId = extra.getNextRouteId();
        ArrivalResponse match = null;
        for (ArrivalResponse arrival : arrivals) {
            if (arrival.getDepartureIndex() == departureIndex
                    && (arrival.getRouteId() == thisRouteId || arrival.getRouteId() == nextRouteId)) {
                match = arrival;
                if (arrival.getRouteId() == thisRouteId) {
                    break;
                }
            }
        }
        if (match == null) {
            rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.sched_unknown").getString(),
                    COLOR_FAINT, 0));
            rows.add(Row.GAP);
            return;
        }

        long deviationSeconds = Math.round(match.getDeviation() / 1000.0); // positive = late
        int threshold = Math.max(1, config.hudOnTimeThresholdSeconds);
        String signed = (deviationSeconds >= 0 ? "+" : "-") + Math.abs(deviationSeconds) + " s";
        String key;
        int color;
        if (Math.abs(deviationSeconds) <= threshold) {
            key = "gui.station_announcer.driving_hud.on_time";
            color = COLOR_TEXT;
        } else if (deviationSeconds < 0) {
            key = "gui.station_announcer.driving_hud.early";
            color = COLOR_GREEN;
        } else {
            key = "gui.station_announcer.driving_hud.late";
            color = COLOR_RED;
        }
        rows.add(new Row(Text.translatable(key, signed).getString(), color, 0));
        rows.add(Row.GAP);
    }

    // -------------------------------------------------------------- doors row

    /**
     * Door state from the same two values MTR's own driving GUI reads: the
     * adjusted door multiplier (target/direction) and the persistent 0..1
     * animation value. The percent shown while moving is the door position
     * (100 = fully open).
     */
    private static void buildDoorsRow(List<Row> rows, VehicleExtension vehicle, VehicleExtraData extra,
                                      boolean obstructed) {
        double doorValue = vehicle.persistentVehicleData.getDoorValue();
        int doorMultiplier = vehicle.persistentVehicleData.getAdjustedDoorMultiplier(extra);
        String key;
        int color;
        boolean showPercent = false;
        if (obstructed) {
            key = "gui.station_announcer.driving_hud.doors_obstructed";
            color = COLOR_RED;
        } else if (doorMultiplier > 0) {
            if (doorValue >= 1) {
                key = "gui.station_announcer.driving_hud.doors_open";
                color = COLOR_GREEN;
            } else {
                key = "gui.station_announcer.driving_hud.doors_opening";
                color = COLOR_AMBER;
                showPercent = true;
            }
        } else if (doorValue <= 0) {
            key = "gui.station_announcer.driving_hud.doors_closed";
            color = COLOR_TEXT;
        } else {
            key = "gui.station_announcer.driving_hud.doors_closing";
            color = COLOR_AMBER;
            showPercent = true;
        }
        String text = showPercent
                ? Text.translatable(key, Math.round(doorValue * 100)).getString()
                : Text.translatable(key).getString();
        rows.add(new Row(text, color, 0));
        rows.add(Row.GAP);
    }

    // ---------------------------------------------------------- speed limits

    private static void buildSpeedLimitRows(List<Row> rows, ObjectImmutableList<PathData> path,
                                            int headIndex, List<WalkEntry> walk,
                                            int lookahead, double speedKph) {
        long previousLimit = headIndex >= 0 && headIndex < path.size()
                ? path.get(headIndex).getSpeedLimitKilometersPerHour()
                : 0;
        int added = 0;
        for (WalkEntry entry : walk) {
            if (added >= MAX_SPEED_LIMIT_ENTRIES || entry.distance() > lookahead) {
                break;
            }
            long limit = entry.pathData().getSpeedLimitKilometersPerHour();
            if (limit > 0 && limit != previousLimit) {
                // Amber when the limit ahead is below the current speed — the
                // driver has braking to do.
                int color = limit < speedKph ? COLOR_AMBER : COLOR_TEXT;
                rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.limit",
                        formatDistance(Math.max(0, entry.distance())), limit).getString(), color, 0));
                added++;
            }
            if (limit > 0) {
                previousLimit = limit;
            }
        }
        if (added > 0) {
            rows.add(Row.GAP);
        }
    }

    // -------------------------------------------------------------- signals

    private static void buildSignalRows(List<Row> rows, List<WalkEntry> walk, int lookahead,
                                        double selfPaddingLength, VehicleExtraData extra,
                                        @Nullable NextStop nextStop) {
        MinecraftClientData data = MinecraftClientData.getInstance();
        int added = 0;
        boolean previousWasSignal = false;
        int lastAspect = -1;
        for (WalkEntry entry : walk) {
            if (added >= MAX_SIGNAL_ENTRIES || entry.distance() > lookahead) {
                break;
            }
            PathData pathData = entry.pathData();
            IntAVLTreeSet signalColors = pathData.getSignalColors();
            if (signalColors.isEmpty()) {
                previousWasSignal = false;
                continue;
            }
            int aspect = aspectOf(data, pathData, signalColors, entry.distance() > selfPaddingLength);
            // Consecutive signalled segments with the same aspect are one
            // block; a gap or an aspect change starts a new entry.
            if (previousWasSignal && aspect == lastAspect) {
                continue;
            }
            previousWasSignal = true;
            lastAspect = aspect;
            String aspectText = Text.translatable(switch (aspect) {
                case ASPECT_OCCUPIED -> "gui.station_announcer.driving_hud.aspect_occupied";
                case ASPECT_CAUTION -> "gui.station_announcer.driving_hud.aspect_caution";
                default -> "gui.station_announcer.driving_hud.aspect_clear";
            }).getString();
            int color = switch (aspect) {
                case ASPECT_OCCUPIED -> COLOR_RED;
                case ASPECT_CAUTION -> COLOR_AMBER;
                default -> COLOR_GREEN;
            };
            rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.signal",
                    formatDistance(Math.max(0, entry.distance())), aspectText).getString(), color, color));
            added++;
        }

        // Obstruction cue: MTR plans stops via the stopping point; when it
        // lands short of the next platform's end, something (a signal held
        // against us or a vehicle ahead) is in the way. The stopping point is
        // a plain rail-progress value, so it is only comparable to a next stop
        // on THIS side of a repeat wrap.
        if (nextStop != null && !nextStop.wrapped()
                && extra.getStoppingPoint() < nextStop.endDistance() - 0.5) {
            rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.obstruction").getString(),
                    COLOR_RED, COLOR_RED));
            added++;
        }
        if (added > 0) {
            rows.add(Row.GAP);
        }
    }

    private static final int ASPECT_CLEAR = 0;
    private static final int ASPECT_CAUTION = 1;
    private static final int ASPECT_OCCUPIED = 2;

    /**
     * Aspect estimate for one signalled segment, from the same client maps
     * MTR's wayside signals read. The colour maps are keyed by the rail's
     * canonical hex id — which is one of the segment's two directional ids, so
     * both are tried. {@code blockedRailIds} holds directional occupancy
     * written by every synced vehicle (including ours: hence the self-padding
     * guard). Like the wayside signals, a block our own reservation holds
     * still reads occupied.
     */
    private static int aspectOf(MinecraftClientData data, PathData pathData,
                                IntAVLTreeSet signalColors, boolean beyondSelfZone) {
        String forwardId = pathData.getHexId(false);
        String reverseId = pathData.getHexId(true);
        if (anyColorMatch(data.railIdToCurrentlyBlockedSignalColors.get(forwardId), signalColors)
                || anyColorMatch(data.railIdToCurrentlyBlockedSignalColors.get(reverseId), signalColors)
                || (beyondSelfZone
                        && (data.blockedRailIds.contains(forwardId) || data.blockedRailIds.contains(reverseId)))) {
            return ASPECT_OCCUPIED;
        }
        if (anyColorMatch(data.railIdToPreBlockedSignalColors.get(forwardId), signalColors)
                || anyColorMatch(data.railIdToPreBlockedSignalColors.get(reverseId), signalColors)) {
            return ASPECT_CAUTION;
        }
        return ASPECT_CLEAR;
    }

    private static boolean anyColorMatch(@Nullable LongArrayList blockedColors, IntAVLTreeSet signalColors) {
        if (blockedColors == null) {
            return false;
        }
        for (int i = 0; i < blockedColors.size(); i++) {
            if (signalColors.contains((int) blockedColors.getLong(i))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ rendering

    private static void render(DrawContext context, float tickDelta) {
        Snapshot current = snapshot;
        if (current == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden
                || (client.currentScreen != null && !(client.currentScreen instanceof ChatScreen))) {
            return;
        }
        TextRenderer textRenderer = client.textRenderer;

        List<Row> rows = current.rows();
        int panelWidth = MIN_PANEL_WIDTH;
        int panelHeight = 2 * PANEL_PADDING;
        for (Row row : rows) {
            if (row == Row.GAP) {
                panelHeight += GAP_HEIGHT;
                continue;
            }
            int indent = row.bulletColor() != 0 ? BULLET_INDENT : 0;
            panelWidth = Math.max(panelWidth, indent + textRenderer.getWidth(row.text()));
            panelHeight += LINE_HEIGHT;
        }
        panelWidth += 2 * PANEL_PADDING;

        int margin = MathHelper.clamp(AddonClientConfig.get().hudMargin, 0, 64);
        String corner = AddonClientConfig.get().hudCorner;
        boolean rightSide = "top_right".equals(corner) || "bottom_right".equals(corner);
        boolean bottomSide = "bottom_left".equals(corner) || "bottom_right".equals(corner);
        int x = rightSide ? context.getScaledWindowWidth() - panelWidth - margin : margin;
        int y = bottomSide ? context.getScaledWindowHeight() - panelHeight - margin : margin;

        context.fill(x, y, x + panelWidth, y + panelHeight, COLOR_BACKGROUND);

        // 2 Hz blink phase for alert rows — a per-frame color choice off the
        // cached snapshot, not a recomputation. The row keeps its height while
        // dark so the panel never jumps.
        boolean blinkOn = (System.currentTimeMillis() / BLINK_PERIOD_MILLIS) % 2 == 0;

        int textY = y + PANEL_PADDING;
        for (Row row : rows) {
            if (row == Row.GAP) {
                textY += GAP_HEIGHT;
                continue;
            }
            if (row.blink() && !blinkOn) {
                textY += LINE_HEIGHT;
                continue;
            }
            int textX = x + PANEL_PADDING;
            if (row.bulletColor() != 0) {
                int bulletY = textY + (LINE_HEIGHT - 2 - BULLET_SIZE) / 2;
                context.fill(textX, bulletY, textX + BULLET_SIZE, bulletY + BULLET_SIZE, row.bulletColor());
                textX += BULLET_INDENT;
            }
            context.drawTextWithShadow(textRenderer, row.text(), textX, textY, row.color());
            textY += LINE_HEIGHT;
        }
    }

    // ------------------------------------------------------------ formatting

    private static String formatDistance(double meters) {
        return meters >= 1000 ? String.format("%.1f km", meters / 1000) : Math.round(meters) + " m";
    }

    private static String formatSeconds(long seconds) {
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + " s";
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    private static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }

    // ------------------------------------------------------------ reflection

    private static double readRailProgress(VehicleExtension vehicle) {
        try {
            if (railProgressField == null) {
                if (railProgressLookupFailed) {
                    return Double.NaN;
                }
                railProgressField = org.mtr.core.generated.data.VehicleSchema.class.getDeclaredField("railProgress");
                railProgressField.setAccessible(true);
            }
            return railProgressField.getDouble(vehicle);
        } catch (Exception e) {
            railProgressLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read railProgress; driving HUD disabled", e);
            return Double.NaN;
        }
    }

    /** {@code [repeatIndex1, repeatIndex2]}, or null when unreadable (walk then simply stops at path end). */
    @Nullable
    private static long[] readRepeatIndices(VehicleExtraData extra) {
        try {
            if (repeatIndex1Field == null || repeatIndex2Field == null) {
                if (repeatIndexLookupFailed) {
                    return null;
                }
                Class<?> schema = org.mtr.core.generated.data.VehicleExtraDataSchema.class;
                repeatIndex1Field = schema.getDeclaredField("repeatIndex1");
                repeatIndex1Field.setAccessible(true);
                repeatIndex2Field = schema.getDeclaredField("repeatIndex2");
                repeatIndex2Field.setAccessible(true);
            }
            return new long[]{repeatIndex1Field.getLong(extra), repeatIndex2Field.getLong(extra)};
        } catch (Exception e) {
            repeatIndexLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read repeat indices; driving HUD will not wrap looping routes", e);
            return null;
        }
    }

    // ----------------------------------------------------------------- types

    /**
     * One text line of the panel; {@code bulletColor} != 0 draws a small
     * square before the text; {@code blink} rows flash at 2 Hz.
     */
    private record Row(String text, int color, int bulletColor, boolean blink) {
        Row(String text, int color, int bulletColor) {
            this(text, color, bulletColor, false);
        }

        /** Sentinel: a short vertical gap between sections. */
        static final Row GAP = new Row("", 0, 0);
    }

    private record Snapshot(List<Row> rows) {
    }

    /** One path segment ahead of the head: {@code distance} is from the head to its start. */
    private record WalkEntry(PathData pathData, double distance, boolean wrapped) {
    }

    /**
     * The upcoming platform stop: id, distance from the head to the stopping
     * point, absolute end distance on the path (only meaningful when not
     * {@code wrapped}), and whether it lies past a repeat wrap.
     */
    private record NextStop(long platformId, double distance, double endDistance, boolean wrapped) {
    }
}
