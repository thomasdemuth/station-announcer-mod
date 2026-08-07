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
 * blocks with an aspect estimate, the next stop (name / distance / ETA), and
 * an EARLY / ON TIME / LATE schedule indicator.</p>
 *
 * <p><b>Performance contract</b> (ARCHITECTURE §6): the lookahead is computed
 * in a {@link ClientTickEvents} handler throttled to {@code hudUpdateHz}
 * (default 4 Hz) — never per frame. The {@link HudRenderCallback} only draws
 * the cached {@link Snapshot} (pre-built text rows; the only per-frame work is
 * a handful of {@code TextRenderer.getWidth} calls and fills). The schedule
 * check reuses the arrivals trick proven by {@code RailroadRouteData}: a
 * cached {@code ArrivalsCacheClient.requestArrivals} fetch (≥{@code
 * hudArrivalsCacheMillis}, default 1000 ms) for the next platform id, matching
 * our own run by {@code routeId + departureIndex}. When nobody is driving, the
 * per-update work is one small vehicle-set scan and nothing is rendered; with
 * {@code hudEnabled} off, not even that.</p>
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

    /** Cached accessor for {@code VehicleSchema.railProgress} (protected, no getter in 4.0.1). */
    @Nullable
    private static Field railProgressField;
    private static boolean railProgressLookupFailed;

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
        // the segment AFTER the platform, so every scan below naturally looks
        // at what comes next. Repeat-infinitely routes: we simply stop the walk
        // at path end rather than wrapping to getRepeatIndex1() (protected in
        // 4.0.1) — after the final stop of a looping route the lists go quiet
        // until MTR advances the path. Documented limitation.
        int headIndex = Utilities.getIndexFromConditionalList(path, railProgress);
        int scanStart = Math.max(0, headIndex + 1);

        double speed = vehicle.getSpeed(); // meters per millisecond
        double speedKph = speed * 3600;
        double deceleration = Math.max(1E-9, extra.getDeceleration());
        int lookahead = MathHelper.clamp(config.hudLookaheadMeters, 100, 20_000);
        // Our own vehicle marks blockedRailIds up to its braking padding ahead
        // (VehicleExtension "Write signals"); occupancy hits inside that zone
        // would be self-detections and are ignored.
        double selfPaddingEnd = railProgress + 0.5 * speed * speed / deceleration
                + vehicle.getTransportMode().stoppingSpace;

        List<Row> rows = new ArrayList<>();

        NextStop nextStop = config.hudShowNextStop || config.hudShowOnTime || config.hudShowSignals
                ? findNextStop(extra, path, scanStart, railProgress)
                : null;

        if (config.hudShowNextStop) {
            buildNextStopRows(rows, extra, nextStop, speed, deceleration);
        }
        if (config.hudShowOnTime) {
            buildOnTimeRow(rows, config, vehicle, extra, nextStop, now);
        }
        if (config.hudShowSpeedLimits) {
            buildSpeedLimitRows(rows, path, headIndex, scanStart, railProgress, lookahead, speedKph);
        }
        if (config.hudShowSignals) {
            buildSignalRows(rows, path, scanStart, railProgress, lookahead, selfPaddingEnd, extra, nextStop);
        }

        // Trim a trailing section gap.
        while (!rows.isEmpty() && rows.get(rows.size() - 1) == Row.GAP) {
            rows.remove(rows.size() - 1);
        }
        return rows.isEmpty() ? null : new Snapshot(rows);
    }

    // ------------------------------------------------------------- next stop

    /** The next platform the vehicle will stop at, or null (path end / siding). */
    @Nullable
    private static NextStop findNextStop(VehicleExtraData extra, ObjectImmutableList<PathData> path,
                                         int scanStart, double railProgress) {
        long sidingId = extra.getSidingId();
        for (int i = scanStart; i < path.size(); i++) {
            PathData pathData = path.get(i);
            if (pathData.getDwellTime() > 0 && pathData.getSavedRailBaseId() != 0
                    && pathData.getSavedRailBaseId() != sidingId
                    && pathData.getEndDistance() > railProgress + 0.5) {
                return new NextStop(pathData.getSavedRailBaseId(),
                        pathData.getEndDistance() - railProgress, pathData.getEndDistance());
            }
        }
        return null;
    }

    private static void buildNextStopRows(List<Row> rows, VehicleExtraData extra, @Nullable NextStop nextStop,
                                          double speed, double deceleration) {
        if (nextStop == null) {
            return;
        }
        rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.next",
                stopName(extra, nextStop.platformId())).getString(), COLOR_TEXT, 0));
        long etaSeconds = etaSeconds(nextStop.distance(), speed, deceleration);
        String distance = formatDistance(nextStop.distance());
        String detail = etaSeconds >= 0
                ? Text.translatable("gui.station_announcer.driving_hud.eta", distance, formatSeconds(etaSeconds)).getString()
                : distance;
        rows.add(new Row(detail, COLOR_FAINT, 0));
        rows.add(Row.GAP);
    }

    /**
     * Name of the platform's station (falling back to
     * {@code getNextStationName()} would be MTR's own label, but around the
     * moment the head enters the platform segment MTR already points it one
     * station further — the platform-map lookup always matches the platform
     * whose distance we display, so it is preferred, with MTR's label and the
     * bare platform name as fallbacks).
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
        if (nextStop == null || departureIndex < 0) {
            // Manual sidings run with departureIndex == -1: MTR publishes no
            // schedule for them, so there is nothing to deviate from.
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

        long routeId = extra.getThisRouteId();
        ArrivalResponse match = null;
        for (ArrivalResponse arrival : arrivals) {
            if (arrival.getRouteId() == routeId && arrival.getDepartureIndex() == departureIndex) {
                match = arrival;
                break;
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

    // ---------------------------------------------------------- speed limits

    private static void buildSpeedLimitRows(List<Row> rows, ObjectImmutableList<PathData> path,
                                            int headIndex, int scanStart, double railProgress,
                                            int lookahead, double speedKph) {
        long currentLimit = headIndex >= 0 && headIndex < path.size()
                ? path.get(headIndex).getSpeedLimitKilometersPerHour()
                : 0;
        long previousLimit = currentLimit;
        int added = 0;
        for (int i = scanStart; i < path.size() && added < MAX_SPEED_LIMIT_ENTRIES; i++) {
            PathData pathData = path.get(i);
            double distance = pathData.getStartDistance() - railProgress;
            if (distance > lookahead) {
                break;
            }
            long limit = pathData.getSpeedLimitKilometersPerHour();
            if (limit > 0 && limit != previousLimit) {
                // Amber when the limit ahead is below the current speed — the
                // driver has braking to do.
                int color = limit < speedKph ? COLOR_AMBER : COLOR_TEXT;
                rows.add(new Row(Text.translatable("gui.station_announcer.driving_hud.limit",
                        formatDistance(Math.max(0, distance)), limit).getString(), color, 0));
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

    private static void buildSignalRows(List<Row> rows, ObjectImmutableList<PathData> path,
                                        int scanStart, double railProgress, int lookahead,
                                        double selfPaddingEnd, VehicleExtraData extra,
                                        @Nullable NextStop nextStop) {
        MinecraftClientData data = MinecraftClientData.getInstance();
        int added = 0;
        int lastSignalIndex = Integer.MIN_VALUE;
        int lastAspect = -1;
        for (int i = scanStart; i < path.size() && added < MAX_SIGNAL_ENTRIES; i++) {
            PathData pathData = path.get(i);
            double distance = pathData.getStartDistance() - railProgress;
            if (distance > lookahead) {
                break;
            }
            IntAVLTreeSet signalColors = pathData.getSignalColors();
            if (signalColors.isEmpty()) {
                continue;
            }
            int aspect = aspectOf(data, pathData, signalColors,
                    pathData.getStartDistance() > selfPaddingEnd);
            // Consecutive signalled segments with the same aspect are one
            // block; a gap or an aspect change starts a new entry.
            if (i == lastSignalIndex + 1 && aspect == lastAspect) {
                lastSignalIndex = i;
                continue;
            }
            lastSignalIndex = i;
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
                    formatDistance(Math.max(0, distance)), aspectText).getString(), color, color));
            added++;
        }

        // Obstruction cue: MTR plans stops via the stopping point; when it
        // lands short of the next platform's end, something (a signal held
        // against us or a vehicle ahead) is in the way.
        if (nextStop != null && extra.getStoppingPoint() < nextStop.endDistance() - 0.5) {
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

        int textY = y + PANEL_PADDING;
        for (Row row : rows) {
            if (row == Row.GAP) {
                textY += GAP_HEIGHT;
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

    // ----------------------------------------------------------------- types

    /** One text line of the panel; {@code bulletColor} != 0 draws a small square before the text. */
    private record Row(String text, int color, int bulletColor) {
        /** Sentinel: a short vertical gap between sections. */
        static final Row GAP = new Row("", 0, 0);
    }

    private record Snapshot(List<Row> rows) {
    }

    /** The upcoming platform stop: id, distance from the head, absolute end distance on the path. */
    private record NextStop(long platformId, double distance, double endDistance) {
    }
}
