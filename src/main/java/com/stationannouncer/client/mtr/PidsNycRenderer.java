package com.stationannouncer.client.mtr;

import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.mtr.PidsBlockEntity;
import com.stationannouncer.mtr.PidsStyle;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.ArrivalsCacheClient;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the five NYC/MTA-style countdown-clock screens. All content is
 * emissive (full-bright position-color quads + text). Long text scrolls
 * LED-marquee style instead of overlapping; route bullets are true circles;
 * countdowns show minutes, switch to seconds under one minute, and the row
 * inverts and flashes white while the train is at the platform.
 */
@Environment(EnvType.CLIENT)
public class PidsNycRenderer implements BlockEntityRenderer<PidsBlockEntity> {
    private static final int BG_BLACK = 0xFF0A0A0A;
    private static final int ROW_GRAY = 0xFF2A2A2E;
    private static final int TEXT_WHITE = 0xFFF5F5F5;
    private static final int TEXT_GRAY = 0xFF9A9AA0;
    private static final int TEXT_BLACK = 0xFF101010;
    private static final int PANEL_GRAY = 0xFF1A1A1C;

    @Override
    public void render(PidsBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        Direction facing = entity.getCachedState().contains(DirectionHelper.FACING.data)
                ? entity.getCachedState().get(DirectionHelper.FACING.data)
                : Direction.NORTH;
        PidsStyle style = entity.style;

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        if (style.isHanging()) {
            // Center on the two-block-wide unit (partner block is at local +X).
            matrices.translate(0.5, 0.0, 0.0);
            drawHangingPanel(matrices, vertexConsumers, style);
        }

        // Gathered once and drawn twice: both sides of a double-sided screen
        // show the same trains and the same "Happening now" text.
        ArrivalData data = collectData(entity);
        String happeningNow = style.isDepartures() ? happeningNowText(entity) : "";

        int sides = style.isDoubleSided() ? 2 : 1;
        for (int side = 0; side < sides; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            // Each side of a double-sided screen faces the opposite way — the
            // next-train arrow must flip with it (mirrored = the back face, so
            // a fixed left/right override still points at the same physical track).
            paintScreen(entity, matrices, vertexConsumers, side == 1 ? facing.getOpposite() : facing,
                    side == 1, data, happeningNow);
            matrices.pop();
        }
        matrices.pop();
    }

    /** Body of the two-wide hanging clock (mounting poles come from the models). */
    private void drawHangingPanel(MatrixStack matrices, VertexConsumerProvider vertexConsumers, PidsStyle style) {
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getDebugQuads());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float bottom = style == PidsStyle.HANGING_MINI ? 4.0f / 16.0f : 0.0f; // mini panel is shorter
        CanvasPainter.box(buffer, matrix, -15.0f / 16.0f, bottom, -1.5f / 16.0f, 15.0f / 16.0f, 12.0f / 16.0f, 1.5f / 16.0f, PANEL_GRAY);
    }

    private void paintScreen(PidsBlockEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                             Direction screenFacing, boolean mirrored, ArrivalData data, String happeningNow) {
        PidsStyle style = entity.style;
        float screenWidth;
        float top;
        float front;
        float zShift = 0.0f;
        switch (style) {
            case ROUTE_MAP_WALL, DEPARTURES_WALL -> {
                screenWidth = 14.0f / 16.0f;
                top = 39.0f / 16.0f;               // screen spans 0.5–2.5 blocks
                front = -(1.0f / 16.0f) - 0.001f;  // 1 px panel at the back of the block
                zShift = 0.5f;
            }
            case ROUTE_MAP_STANDING, DEPARTURES_STANDING -> {
                screenWidth = 14.0f / 16.0f;
                top = 39.0f / 16.0f;
                front = -(1.5f / 16.0f) - 0.001f;
            }
            default -> { // HANGING / HANGING_MINI, already centered on the 2-wide unit
                screenWidth = 28.0f / 16.0f;
                top = 11.0f / 16.0f;
                front = -(1.5f / 16.0f) - 0.001f;
            }
        }
        float screenHeight = switch (style) {
            case HANGING -> 10.0f / 16.0f;
            case HANGING_MINI -> 6.0f / 16.0f;
            default -> 30.0f / 16.0f;
        };

        matrices.push();
        // Nameplate convention: negative X and Y scale so text reads left-to-right
        // for a viewer on the -Z (facing) side; canvas origin at their top-left.
        matrices.translate(screenWidth / 2.0f, top, zShift + front);
        float unit = screenWidth / 128.0f;
        matrices.scale(-unit, -unit, unit);
        int canvasHeight = Math.round(screenHeight / unit);

        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
        painter.quad(0, 0, 128, canvasHeight, 0.0f, BG_BLACK);

        List<ArrivalResponse> arrivals = data.arrivals();
        boolean noPlatforms = data.noPlatforms();
        switch (style) {
            case ROUTE_MAP_WALL, ROUTE_MAP_STANDING -> paintRouteMap(painter, arrivals, noPlatforms, canvasHeight);
            case DEPARTURES_WALL, DEPARTURES_STANDING ->
                    paintDepartures(painter, arrivals, happeningNow, noPlatforms, canvasHeight);
            case HANGING_MINI -> {
                if (entity.isNextTrainMode()) {
                    paintNextTrain(painter, entity, arrivals, canvasHeight, screenFacing, mirrored);
                } else {
                    paintHangingMini(painter, arrivals, noPlatforms, canvasHeight);
                }
            }
            default -> paintHanging(painter, entity, arrivals, noPlatforms, canvasHeight);
        }
        matrices.pop();
    }

    // ------------------------------------------------------------ MTR data

    private record ArrivalData(List<ArrivalResponse> arrivals, boolean noPlatforms) {
    }

    /**
     * Arrivals for this display. With no platforms configured, nearby ones are
     * auto-detected exactly like MTR's own PIDS (InitClient.findClosePlatform,
     * radius 5); noPlatforms is true only when detection finds nothing either.
     */
    private ArrivalData collectData(PidsBlockEntity entity) {
        long key = entity.getPos().asLong();
        org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection platformIds = entity.getPlatformIds();
        ObjectArrayList<ArrivalResponse> cached;
        if (platformIds.isEmpty()) {
            // Nothing configured: fall back to the closest platform, exactly
            // like MTR's own PIDS (cached — the lookup scans every platform).
            long detected = MtrDataCache.closestPlatform(entity.getPos(), 5);
            if (detected == 0) {
                return new ArrivalData(List.of(), true);
            }
            cached = MtrDataCache.arrivalsForPlatform(key, detected);
        } else {
            cached = MtrDataCache.arrivals(key, platformIds);
        }
        if (cached.isEmpty()) {
            return new ArrivalData(List.of(), false);
        }
        List<ArrivalResponse> result = new ArrayList<>(cached.size());
        long now = System.currentTimeMillis() + ArrivalsCacheClient.INSTANCE.getMillisOffset();
        for (ArrivalResponse arrival : cached) {
            if (arrival.getDeparture() - now > -2000) {
                result.add(arrival);
            }
        }
        return new ArrivalData(result, false);
    }

    private static long remainingMillis(ArrivalResponse arrival) {
        return arrival.getArrival() - ArrivalsCacheClient.INSTANCE.getMillisOffset() - System.currentTimeMillis();
    }

    private static boolean isArriving(ArrivalResponse arrival) {
        return remainingMillis(arrival) <= 500;
    }

    private static boolean flashOn() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    private static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }

    private static String bulletLabel(ArrivalResponse arrival) {
        String number = firstLang(arrival.getRouteNumber());
        if (!number.isEmpty()) {
            return number.length() > 2 ? number.substring(0, 2) : number;
        }
        String name = firstLang(arrival.getRouteName());
        return name.isEmpty() ? "?" : name.substring(0, 1);
    }

    private record Eta(String number, String unit) {
    }

    private static Eta eta(ArrivalResponse arrival) {
        long remaining = Math.max(0, remainingMillis(arrival));
        if (remaining >= 60_000) {
            return new Eta(String.valueOf(remaining / 60_000), "MIN");
        }
        return new Eta(String.valueOf(remaining / 1_000), "SEC");
    }

    // ------------------------------------------------------------- layouts

    /** Two-digit zero padding without the cost of String.format (this runs per frame, per screen). */
    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private void paintClock(CanvasPainter painter) {
        LocalTime time = LocalTime.now();
        String main = twoDigits(time.getHour()) + ":" + twoDigits(time.getMinute());
        String seconds = ":" + twoDigits(time.getSecond());
        float secondsWidth = painter.width(seconds, 7);
        float mainWidth = painter.width(main, 7);
        painter.text(main, 124 - secondsWidth - mainWidth, 4, 7, TEXT_WHITE);
        painter.text(seconds, 124 - secondsWidth, 4, 7, TEXT_GRAY);
    }

    private void paintEmpty(CanvasPainter painter, boolean noPlatforms, int canvasHeight) {
        paintClock(painter);
        String line1 = noPlatforms ? "NO NEARBY PLATFORM" : "NO SCHEDULED";
        String line2 = noPlatforms ? "USE MTR BRUSH" : "TRAINS";
        painter.textCentered(line1, 64, canvasHeight / 2.0f - 10, 7, TEXT_GRAY);
        painter.textCentered(line2, 64, canvasHeight / 2.0f + 2, 7, TEXT_GRAY);
    }

    private void paintDepartureRow(CanvasPainter painter, ArrivalResponse arrival, float y, float height,
                                   float destSize, float etaSize, float bulletRadius) {
        boolean arriving = isArriving(arrival);
        boolean inverted = arriving && flashOn();
        int rowColor = inverted ? 0xFFFFFFFF : ROW_GRAY;
        int mainColor = inverted ? TEXT_BLACK : TEXT_WHITE;
        int subColor = inverted ? 0xFF505055 : TEXT_GRAY;

        painter.quad(4, y, 124, y + height, -0.5f, rowColor);
        float centerY = y + height / 2.0f;

        float leftX = 8;
        painter.circleBullet(leftX + bulletRadius, centerY, bulletRadius,
                0xFF000000 | arrival.getRouteColor(), bulletLabel(arrival), inverted);

        Eta eta = eta(arrival);
        String number = arriving ? "0" : eta.number();
        float numberWidth = Math.max(painter.width(number, etaSize), painter.width("0", etaSize));
        float textX = leftX + bulletRadius * 2 + 5;
        float textMaxWidth = 120 - numberWidth - textX - 4;

        painter.textScrolling(firstLang(arrival.getDestination()), textX, centerY - destSize + 1,
                destSize, mainColor, textMaxWidth);
        painter.textScrolling(firstLang(arrival.getRouteName()), textX, centerY + 2,
                destSize * 0.55f, subColor, textMaxWidth);

        painter.textRight(number, 120, centerY - etaSize / 2.0f - 2, etaSize, mainColor);
        painter.textRight(arriving ? "MIN" : eta.unit(), 120, centerY + etaSize / 2.0f - 1, etaSize * 0.32f, subColor);
    }

    /**
     * The "Happening now" content. Linked to a PA Control Box, the screen
     * mirrors what the box is saying at all times: its announcement pool,
     * rotating through the entries every 8 seconds. Unlinked (or the box is
     * out of client range), the brush-configured message rows show instead.
     */
    /** Cached per display: the pool rotates every 8 s, so re-deriving it per frame is wasted work. */
    private static final java.util.Map<Long, String[]> HAPPENING_NOW = new java.util.HashMap<>();
    private static final long HAPPENING_NOW_TTL_MS = 500;
    private static long happeningNowExpiry;

    private String happeningNowText(PidsBlockEntity entity) {
        long now = System.currentTimeMillis();
        if (now >= happeningNowExpiry) {
            HAPPENING_NOW.clear();
            happeningNowExpiry = now + HAPPENING_NOW_TTL_MS;
        }
        String[] messages = HAPPENING_NOW.computeIfAbsent(entity.getPos().asLong(), key -> poolFor(entity));
        return messages[(int) ((now / 8000) % messages.length)];
    }

    /**
     * What this display should show under "Happening now": the linked control
     * box's message pool, or the brush-configured rows when there is no box,
     * no pool, or the box is out of client range.
     */
    private static String[] poolFor(PidsBlockEntity entity) {
        BlockPos boxPos = entity.getPaControlBoxPos();
        if (boxPos != null) {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world != null && world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box) {
                String[] messages = ControlBoxBlockEntity.splitMessages(box.getText());
                if (messages.length > 0) {
                    return messages;
                }
            }
        }
        return new String[]{entity.getCustomMessage()};
    }

    /** Vertical pitch of one departure row, and the message text's own line height. */
    private static final float DEPARTURE_ROW_PITCH = 45;
    private static final float MESSAGE_LINE_HEIGHT = 7.5f;
    private static final int MAX_DEPARTURE_ROWS = 4;
    /** Message lines that fit under a full four-departure board. */
    private static final int MESSAGE_LINES_AT_FULL_HEIGHT = 4;
    /** The most the message may ever occupy, even with a departure dropped. */
    private static final int MAX_MESSAGE_LINES = 5;
    /** Canvas width available to the message text. */
    private static final float MESSAGE_WIDTH = 116;

    private void paintDepartures(CanvasPainter painter, List<ArrivalResponse> arrivals, String customMessage,
                                 boolean noPlatforms, int canvasHeight) {
        if (arrivals.isEmpty()) {
            paintEmpty(painter, noPlatforms, canvasHeight);
            if (!customMessage.isEmpty()) {
                paintHappeningNow(painter, customMessage, canvasHeight, MAX_DEPARTURE_ROWS);
            }
            return;
        }
        paintClock(painter);
        // Size the message first. A long one takes the fourth departure's slot
        // rather than being quietly clipped — the announcement is the thing the
        // operator actually wrote, so it wins over the least imminent train.
        List<String> lines = customMessage.isEmpty() ? List.of() : painter.wrap(customMessage, 6, MESSAGE_WIDTH);
        int departureRows = lines.size() > MESSAGE_LINES_AT_FULL_HEIGHT
                ? MAX_DEPARTURE_ROWS - 1 : MAX_DEPARTURE_ROWS;

        int rows = Math.min(arrivals.size(), departureRows);
        float y = 16;
        for (int i = 0; i < rows; i++) {
            paintDepartureRow(painter, arrivals.get(i), y, 40, 10, 16, 11);
            y += DEPARTURE_ROW_PITCH;
        }
        if (!customMessage.isEmpty()) {
            paintHappeningNow(painter, customMessage, canvasHeight, departureRows);
        }
    }

    /**
     * The "Happening now" block, sitting directly under however many departure
     * rows were drawn. Anything still too long to fit is truncated with an
     * ellipsis rather than running off the bottom of the screen.
     */
    private void paintHappeningNow(CanvasPainter painter, String message, int canvasHeight, int departureRows) {
        float top = canvasHeight - 60 - (MAX_DEPARTURE_ROWS - departureRows) * DEPARTURE_ROW_PITCH;
        painter.quad(6, top, 122, top + 1, -0.5f, 0xFF55555A);
        painter.text("Happening now", 6, top + 5, 9, TEXT_WHITE);
        List<String> lines = painter.wrap(message, 6, MESSAGE_WIDTH);
        float y = top + 18;
        float limit = canvasHeight - 8;
        int drawn = Math.min(lines.size(), MAX_MESSAGE_LINES);
        for (int i = 0; i < drawn && y < limit; i++) {
            // Vertical truncation when we run out of rows, horizontal when a
            // single unbroken word is wider than the panel — wrap() can only
            // break on whitespace, so it hands those back over-wide.
            boolean lastRow = (i == drawn - 1 || y + MESSAGE_LINE_HEIGHT >= limit) && i < lines.size() - 1;
            String line = painter.trimToWidth(lines.get(i), 6, MESSAGE_WIDTH);
            painter.text(lastRow && !line.endsWith("…") ? line + "…" : line, 6, y, 6, TEXT_GRAY);
            y += MESSAGE_LINE_HEIGHT;
        }
    }

    /** A connecting route at a station: bullet color + short label. */
    private record Connection(int color, String label) {
    }

    /** "6" from "Line 6", "19" from "Route 19/BakerLink", else the first letter. */
    private static String routeLabel(String routeName) {
        String name = firstLang(routeName);
        // Plain scan for the first digit run — a compiled regex per call was
        // pure overhead on a method that runs per station row, per frame.
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end))) {
                    end++;
                }
                return name.substring(i, Math.min(end, i + 2));
            }
        }
        return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    /**
     * Interchanges at this stop. Every SimplifiedRoutePlatform IS an
     * InterchangeColorsForStationName — the server bakes the connecting
     * routes (current line already excluded) into the synced route data;
     * this is exactly what MTR's own RouteMapGenerator reads.
     */
    /** Interchange bullets per stop, rebuilt every {@value #CONNECTIONS_TTL_MS} ms. */
    private static final java.util.Map<SimplifiedRoutePlatform, List<Connection>> CONNECTIONS = new java.util.HashMap<>();
    private static final long CONNECTIONS_TTL_MS = 3000;
    private static long connectionsExpiry;

    /**
     * Cached {@link #buildConnections}: the route map asks for every visible
     * stop on both faces, every frame, and the answer only changes when MTR
     * pushes new route data.
     */
    private static List<Connection> connectionsFor(SimplifiedRoutePlatform stop) {
        long now = System.currentTimeMillis();
        if (now >= connectionsExpiry) {
            CONNECTIONS.clear();
            connectionsExpiry = now + CONNECTIONS_TTL_MS;
        }
        return CONNECTIONS.computeIfAbsent(stop, PidsNycRenderer::buildConnections);
    }

    private static List<Connection> buildConnections(SimplifiedRoutePlatform stop) {
        List<Connection> connections = new ArrayList<>();
        stop.forEach((color, routeNamesForColor) -> {
            StringBuilder firstName = new StringBuilder();
            routeNamesForColor.forEach(name -> {
                if (firstName.isEmpty()) {
                    firstName.append(name);
                }
            });
            connections.add(new Connection(0xFF000000 | color, routeLabel(firstName.toString())));
        });
        return connections;
    }

    private void paintRouteMap(CanvasPainter painter, List<ArrivalResponse> arrivals, boolean noPlatforms, int canvasHeight) {
        if (arrivals.isEmpty()) {
            paintEmpty(painter, noPlatforms, canvasHeight);
            return;
        }
        paintClock(painter);
        ArrivalResponse next = arrivals.get(0);
        paintDepartureRow(painter, next, 14, 40, 10, 16, 11);

        int routeColor = 0xFF000000 | next.getRouteColor();
        SimplifiedRoute route = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(next.getRouteId());
        if (route == null) {
            return;
        }
        ObjectArrayList<SimplifiedRoutePlatform> platforms = route.getPlatforms();
        int index = route.getPlatformIndex(next.getPlatformId());
        if (index < 0) {
            return;
        }
        // First row (white pill) is THIS station; the rest are the onward stops.
        List<SimplifiedRoutePlatform> upcoming = new ArrayList<>(platforms.subList(index, platforms.size()));

        float barX = 16;
        float nameX = barX + 11;
        float maxWidth = 124 - nameX - 6;
        float bottomLimit = canvasHeight - 10;
        float rowBase = 20;
        float connectionExtra = 11;

        float y = 62;
        float lastDotY = y;
        int drawn = 0;
        boolean truncated = false;
        List<Runnable> deferred = new ArrayList<>(); // draw dots/rows after the bar (bar goes underneath)
        for (int i = 0; i < upcoming.size(); i++) {
            SimplifiedRoutePlatform stop = upcoming.get(i);
            List<Connection> connections = connectionsFor(stop);
            float rowHeight = rowBase + (connections.isEmpty() ? 0 : connectionExtra);
            boolean isLast = i == upcoming.size() - 1;
            // Keep room for the "further stops" footer if we cannot fit everything.
            float limit = isLast ? bottomLimit : bottomLimit - 34;
            if (y + rowHeight > limit) {
                truncated = true;
                break;
            }
            float rowY = y;
            boolean highlight = i == 0;
            deferred.add(() -> drawStationRow(painter, barX, nameX, rowY, stop, connections, highlight, maxWidth));
            lastDotY = rowY;
            y += rowHeight;
            drawn++;
        }

        float footerDotY = -1;
        SimplifiedRoutePlatform lastStop = upcoming.get(upcoming.size() - 1);
        if (truncated) {
            painter.text("Makes further stops to:", nameX, y + 2, 5.5f, TEXT_GRAY);
            footerDotY = y + 16;
            lastDotY = footerDotY;
        }
        painter.quad(barX - 4, 54, barX + 4, lastDotY + 8, -0.5f, routeColor);
        deferred.forEach(Runnable::run);
        if (truncated) {
            drawStationRow(painter, barX, nameX, footerDotY, lastStop,
                    connectionsFor(lastStop), false, maxWidth);
        } else if (drawn == 0) {
            // Degenerate: nothing fit at all (should not happen) — draw at least the terminus.
            drawStationRow(painter, barX, nameX, 62, lastStop, List.of(), false, maxWidth);
        }
    }

    /** One station row: dot on the bar, name (pill for the next stop), connection bullets below. */
    private void drawStationRow(CanvasPainter painter, float barX, float nameX, float y,
                                SimplifiedRoutePlatform stop, List<Connection> connections,
                                boolean highlight, float maxWidth) {
        painter.quad(barX - 2.5f, y - 2.5f, barX + 2.5f, y + 2.5f, -1.0f, 0xFFFFFFFF);
        String name = firstLang(stop.getStationName());
        if (highlight) {
            // Current station: white pill, black text.
            float pillWidth = Math.min(painter.width(name, 8), maxWidth);
            painter.quad(barX + 8, y - 6, nameX + pillWidth + 6, y + 5, -0.5f, 0xFFFFFFFF);
            painter.textScrolling(name, nameX, y - 4, 8, TEXT_BLACK, maxWidth);
        } else {
            painter.textScrolling(name, nameX, y - 4, 8, TEXT_WHITE, maxWidth);
        }
        if (!connections.isEmpty()) {
            float radius = 4;
            float x = nameX + radius;
            float rowY = y + 6 + radius;
            for (Connection connection : connections) {
                if (x + radius > 124) {
                    break; // keep the right margin
                }
                painter.circleBullet(x, rowY, radius, connection.color(), connection.label(), false);
                x += radius * 2 + 2.5f;
            }
        }
    }

    /**
     * Two-wide hanging clock: next departure on top, the second below
     * (~45-unit canvas). While the linked PA Control Box is announcing, the
     * bottom row turns into the announcement scrolling by — just like a real
     * MTA platform sign.
     */
    private void paintHanging(CanvasPainter painter, PidsBlockEntity entity, List<ArrivalResponse> arrivals,
                              boolean noPlatforms, int canvasHeight) {
        String live = entity.getLiveMessage();
        long liveElapsed = Math.max(0, System.currentTimeMillis() - entity.getLiveStart());
        boolean announcing = !live.isEmpty()
                && liveElapsed * CanvasPainter.SCROLL_ONCE_SPEED * 9 / 1000.0f < 116 + painter.width(live, 9);

        if (arrivals.isEmpty() && !announcing) {
            String hint = noPlatforms ? "NO NEARBY PLATFORM" : "NO SCHEDULED TRAINS";
            painter.textCentered(hint, 64, canvasHeight / 2.0f - 3, 6, TEXT_GRAY);
            return;
        }
        if (!arrivals.isEmpty()) {
            paintDepartureRow(painter, arrivals.get(0), 1.5f, 20, 7, 10, 7);
        }
        painter.quad(6, 23.5f, 122, 24.25f, -0.5f, 0xFF55555A);
        if (announcing) {
            painter.textScrollOnce(live, liveElapsed, 6, 30, 9, TEXT_WHITE, 116);
        } else if (arrivals.size() > 1) {
            paintDepartureRow(painter, arrivals.get(1), 26, 17, 6, 8.5f, 6);
        }
    }

    /** Mini hanging clock: just the next departure (~27-unit canvas). */
    private void paintHangingMini(CanvasPainter painter, List<ArrivalResponse> arrivals, boolean noPlatforms, int canvasHeight) {
        if (arrivals.isEmpty()) {
            String hint = noPlatforms ? "NO NEARBY PLATFORM" : "NO SCHEDULED TRAINS";
            painter.textCentered(hint, 64, canvasHeight / 2.0f - 3, 6, TEXT_GRAY);
            return;
        }
        paintDepartureRow(painter, arrivals.get(0), 1.5f, canvasHeight - 3, 8, 12, 8);
    }

    /** Lit/dark state per mini in Next-train mode: {lit 0|1, time it last went dark}. */
    private static final java.util.Map<Long, long[]> NEXT_TRAIN_STATE = new java.util.HashMap<>();

    /** Drops all per-position state (called on disconnect so nothing leaks across worlds). */
    static void clearWorldState() {
        NEXT_TRAIN_STATE.clear();
        CONNECTIONS.clear();
        HAPPENING_NOW.clear();
    }

    /**
     * "Next train" indicator: lit (centered white text on black) from the
     * configured seconds before arrival until the configured seconds after,
     * with a 10 s relight cooldown so it never flickers between close trains.
     * Both edges are per-block sliders on the mini's click screen.
     */
    private void paintNextTrain(CanvasPainter painter, PidsBlockEntity entity, List<ArrivalResponse> arrivals,
                                int canvasHeight, Direction screenFacing, boolean mirrored) {
        long now = System.currentTimeMillis();
        long remaining = arrivals.isEmpty() ? Long.MAX_VALUE : remainingMillis(arrivals.get(0));
        boolean shouldLight = remaining <= entity.getNextTrainOnSeconds() * 1_000L
                && remaining > -entity.getNextTrainOffSeconds() * 1_000L;

        long[] state = NEXT_TRAIN_STATE.computeIfAbsent(entity.getPos().asLong(), key -> new long[]{0, 0});
        boolean lit = state[0] == 1;
        if (lit && !shouldLight) {
            state[0] = 0;
            state[1] = now; // went dark: 10 s cooldown before relighting
            lit = false;
        } else if (!lit && shouldLight && now - state[1] >= 10_000) {
            state[0] = 1;
            lit = true;
        }
        if (!lit) {
            return;
        }
        // Point toward the arriving train's platform, like the real mezzanine
        // signs — unless this track carries an override. Overrides are stored
        // as seen from the FRONT face; the back face mirrors left/right so both
        // faces point at the same physical direction.
        String arrow = "";
        if (!arrivals.isEmpty()) {
            arrow = switch (entity.arrowFor(arrivals.get(0).getPlatformId())) {
                case PidsBlockEntity.ARROW_LEFT -> mirrored ? "→" : "←";
                case PidsBlockEntity.ARROW_RIGHT -> mirrored ? "←" : "→";
                case PidsBlockEntity.ARROW_DOWN -> "↓";
                default -> platformArrow(entity, arrivals.get(0), screenFacing);
            };
        }
        String text = switch (arrow) {
            case "←" -> "← Next train";
            case "→" -> "Next train →";
            case "↓" -> "↓ Next train ↓";
            default -> "Next train";
        };
        painter.textCentered(text, 64, canvasHeight / 2.0f - 5, 10, TEXT_WHITE);
    }

    /**
     * Arrow from the sign toward the arriving train's platform, relative to a
     * viewer looking at the given screen side: ←/→, or ↓ when the platform is
     * roughly straight ahead/behind (e.g. right below the mezzanine).
     */
    private static String platformArrow(PidsBlockEntity entity, ArrivalResponse arrival, Direction screenFacing) {
        org.mtr.core.data.Platform platform =
                MinecraftClientData.getInstance().platformIdMap.get(arrival.getPlatformId());
        if (platform == null) {
            return "";
        }
        org.mtr.core.data.Position mid = platform.getMidPosition();
        double dx = mid.getX() + 0.5 - (entity.getPos().getX() + 0.5);
        double dz = mid.getZ() + 0.5 - (entity.getPos().getZ() + 0.5);
        Direction right = screenFacing.rotateYCounterclockwise(); // viewer's right when facing the screen
        double dot = dx * right.getOffsetX() + dz * right.getOffsetZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-3) {
            return "";
        }
        if (Math.abs(dot) < 0.35 * length) {
            return "↓";
        }
        return dot > 0 ? "→" : "←";
    }

    // ----------------------------------------------------------- primitives

    @Override
    public boolean rendersOutsideBoundingBox(PidsBlockEntity blockEntity) {
        return true; // screens extend beyond their block space
    }
}
