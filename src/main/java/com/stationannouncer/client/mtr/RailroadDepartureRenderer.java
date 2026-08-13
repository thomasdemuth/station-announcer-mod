package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.RailroadPidsBlock;
import com.stationannouncer.mtr.RailroadPidsBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import java.time.LocalTime;
import java.util.List;

/**
 * The small railroad departure board — the portrait concourse screen listing
 * the next trains out of a terminal.
 *
 * <p>An editable title and a live clock across the top, then one departure per
 * row: the scheduled time on a chevron in the route's own colour, the
 * destination, the route number, the track and the countdown. The first two
 * departures also spell out their stopping pattern, which is the whole point
 * of the real board — you check the top two, and the rest is a running order.
 *
 * <p>The track column stays EMPTY until the board announces it (default six
 * minutes out, set per board). That is not a detail: on a real concourse
 * nothing happens until the track appears, and then everybody moves.
 */
@Environment(EnvType.CLIENT)
public class RailroadDepartureRenderer implements BlockEntityRenderer<RailroadPidsBlockEntity> {
    /** Same canvas as the other railroad boards, so text is one physical size across the family. */
    private static final int CANVAS_WIDTH = 256;

    private static final int BG = 0xFF07070C;
    private static final int HEADER_BG = 0xFFD8D8DC;
    private static final int HEADER_TEXT = 0xFF12233A;
    private static final int COLUMN_BG = 0xFF1C3A6E;
    private static final int TEXT_CYAN = 0xFF5CC8FF;
    private static final int TEXT_WHITE = 0xFFEFF6FF;
    private static final int TEXT_DIM = 0xFF7C93AD;
    /** Station names in a stopping pattern: present, but quieter than the labels. */
    private static final int TEXT_STOPS = 0xFFC6CEDA;
    private static final int RULE = 0xFF1B3352;
    private static final int TEXT_ON_BAR = 0xFF0A0A0E;

    // Layout in canvas units.
    /**
     * Quad depths, in canvas units toward the viewer.
     *
     * <p>Coplanar quads z-fight, and on a screen this dense that shows up as
     * diagonal hatching crawling over the whole board. Every filled area gets a
     * layer: background at 0, panels and bars in front of it, trim in front of
     * those. Text is drawn by CanvasPainter at -1.2, so nothing here may go
     * deeper than that or the labels disappear behind their own bar.
     */
    private static final float LAYER_PANEL = -0.2f;
    private static final float LAYER_TRIM = -0.4f;

    private static final float MARGIN = 8;
    private static final float RIGHT = CANVAS_WIDTH - MARGIN;
    private static final float HEADER_H = 34;
    private static final float COLUMN_H = 26;
    private static final float BAR_H = 30;
    private static final float ROW_GAP = 6;
    private static final float STOPS_SIZE = 11;
    private static final float STOPS_LINE = 13;
    /**
     * Left edge of the Track column. Pulled well left of ETD: at 196 the
     * "Track" caption ran straight into the right-aligned "ETD" one.
     */
    private static final float TRACK_X = 172;
    private static final float ETD_RIGHT = RIGHT;

    /** How many departures spell out their stopping pattern. */
    private static final int EXPANDED = 2;

    @Override
    public void render(RailroadPidsBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (!(entity.getCachedState().getBlock() instanceof RailroadPidsBlock)) {
            return;
        }
        Direction facing = entity.getCachedState().get(RailroadPidsBlock.FACING);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));

        DepartureBoardData.Board board = DepartureBoardData.board(entity.getPos(), entity.getPlatformIds());
        paintScreen(matrices, vertexConsumers, entity, board);

        matrices.pop();
    }

    private void paintScreen(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                             RailroadPidsBlockEntity entity, DepartureBoardData.Board board) {
        float screenWidth = 14.0f / 16.0f;
        float screenHeight = 30.0f / 16.0f;
        float top = 39.0f / 16.0f;                 // screen spans 0.5–2.5 blocks, like every wall board
        float front = -(1.0f / 16.0f) - 0.001f;

        matrices.push();
        // Nameplate convention: negative X and Y scale so text reads
        // left-to-right for a viewer on the -Z side, origin at their top-left.
        matrices.translate(screenWidth / 2.0f, top, 0.5f + front);
        float unit = screenWidth / CANVAS_WIDTH;
        matrices.scale(-unit, -unit, unit);
        int canvasHeight = Math.round(screenHeight / unit);

        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
        painter.quad(0, 0, CANVAS_WIDTH, canvasHeight, 0.0f, BG);   // the only quad at 0

        paintHeader(painter, entity);
        paintColumnHeader(painter);

        float y = HEADER_H + COLUMN_H;
        List<DepartureBoardData.Departure> departures = board.departures();
        if (departures.isEmpty()) {
            painter.textCentered(board.noPlatforms() ? "NO NEARBY PLATFORM" : "NO DEPARTURES",
                    CANVAS_WIDTH / 2.0f, y + 40, 14, TEXT_DIM);
            matrices.pop();
            return;
        }

        for (int i = 0; i < departures.size() && y + BAR_H < canvasHeight - MARGIN; i++) {
            DepartureBoardData.Departure departure = departures.get(i);
            paintRow(painter, departure, y, entity.getTrackRevealSeconds());
            y += BAR_H;
            if (i < EXPANDED) {
                y = paintStops(painter, departure, y, canvasHeight);
            }
            y += ROW_GAP;
            painter.quad(MARGIN, y - ROW_GAP / 2, RIGHT, y - ROW_GAP / 2 + 1, LAYER_PANEL, RULE);
        }
        matrices.pop();
    }

    // -------------------------------------------------------------- header

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private void paintHeader(CanvasPainter painter, RailroadPidsBlockEntity entity) {
        painter.quad(0, 0, CANVAS_WIDTH, HEADER_H, LAYER_PANEL, HEADER_BG);
        String title = entity.getTitle().isBlank() ? "Departures" : entity.getTitle();
        // The clock is reserved first: a long title shrinks rather than running under it.
        LocalTime now = LocalTime.now();
        int hour12 = now.getHour() % 12 == 0 ? 12 : now.getHour() % 12;
        String clock = hour12 + ":" + twoDigits(now.getMinute()) + (now.getHour() < 12 ? " AM" : " PM");
        float clockWidth = painter.width(clock, 16);
        painter.textRight(clock, RIGHT, HEADER_H / 2 - 8, 16, HEADER_TEXT);
        painter.textFitted(title, MARGIN, HEADER_H / 2 - 9, 18, 10, HEADER_TEXT,
                RIGHT - MARGIN - clockWidth - 10);
    }

    private void paintColumnHeader(CanvasPainter painter) {
        painter.quad(0, HEADER_H, CANVAS_WIDTH, HEADER_H + COLUMN_H, LAYER_PANEL, COLUMN_BG);
        float y = HEADER_H + COLUMN_H / 2 - 6;
        painter.text("Departing Train", MARGIN, y, 11, TEXT_WHITE);
        painter.text("Track", TRACK_X, y, 11, TEXT_WHITE);
        painter.textRight("ETD", ETD_RIGHT, y, 11, TEXT_WHITE);
    }

    // ---------------------------------------------------------------- rows

    /**
     * One departure: a coloured chevron carrying the scheduled time, then the
     * destination, the route number, the track and the countdown.
     */
    private void paintRow(CanvasPainter painter, DepartureBoardData.Departure departure,
                          float y, int trackRevealSeconds) {
        float barTop = y + 2;
        float barBottom = y + BAR_H - 4;
        float barRight = TRACK_X - 12;

        // The bar is the route's colour, with a lighter keyline so a dark route
        // still reads as a bar rather than a hole in the screen.
        painter.quad(MARGIN, barTop, barRight, barBottom, LAYER_PANEL, departure.color());
        painter.quad(MARGIN, barTop, barRight, barTop + 1, LAYER_TRIM, lighten(departure.color()));

        LocalTime time = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(departure.departureMillis()),
                java.time.ZoneId.systemDefault());
        int hour12 = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        String scheduled = hour12 + ":" + twoDigits(time.getMinute());
        float textY = barTop + (barBottom - barTop) / 2 - 7;
        painter.text(scheduled, MARGIN + 6, textY, 14, contrasting(departure.color()));

        float nameX = MARGIN + 6 + painter.width(scheduled, 14) + 12;
        // The route number replaces the "peak" badge of the real board.
        String number = departure.routeNumber().isBlank() ? "" : departure.routeNumber();
        float numberWidth = number.isEmpty() ? 0 : painter.width(number, 12) + 12;
        painter.textFitted(departure.destination(), nameX, textY, 15, 9,
                contrasting(departure.color()), barRight - nameX - numberWidth - 6);
        if (!number.isEmpty()) {
            float boxRight = barRight - 5;
            float boxLeft = boxRight - painter.width(number, 12) - 8;
            painter.quad(boxLeft, barTop + 5, boxRight, barBottom - 5, LAYER_TRIM, 0x66000000);
            painter.textCentered(number, (boxLeft + boxRight) / 2, textY + 1, 12,
                    contrasting(departure.color()));
        }

        // Track: blank until the board announces it.
        long secondsOut = (departure.departureMillis() - System.currentTimeMillis()) / 1000L;
        if (secondsOut <= trackRevealSeconds && !departure.track().isBlank()) {
            painter.text(departure.track(), TRACK_X, textY - 1, 17, TEXT_WHITE);
        }

        long minutes = departure.minutesOut();
        String etd = minutes <= 0 ? "now" : minutes + " min";
        painter.textRight(etd, ETD_RIGHT, textY, 14, TEXT_CYAN);
    }

    /** "Stops at" plus the stopping pattern, wrapped, for the top departures. */
    private float paintStops(CanvasPainter painter, DepartureBoardData.Departure departure,
                             float y, int canvasHeight) {
        if (departure.stops().isEmpty()) {
            return y;
        }
        painter.text("Stops at", MARGIN + 4, y + 2, 11, TEXT_WHITE);
        float textY = y + 2 + STOPS_LINE + 2;
        String joined = String.join(" · ", departure.stops());
        List<String> lines = painter.wrap(joined, STOPS_SIZE, RIGHT - MARGIN - 8);
        for (String line : lines) {
            if (textY + STOPS_LINE > canvasHeight - MARGIN) {
                break;
            }
            painter.text(line, MARGIN + 4, textY, STOPS_SIZE, TEXT_STOPS);
            textY += STOPS_LINE;
        }
        return textY + 2;
    }

    // --------------------------------------------------------------- color

    /** A route colour is arbitrary, so the label picks whichever of black/white reads. */
    static int contrasting(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // Rec. 601 luma - close enough, and no floating point per frame.
        return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? TEXT_ON_BAR : 0xFFFFFFFF;
    }

    private static int lighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 60);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 60);
        int b = Math.min(255, (argb & 0xFF) + 60);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
