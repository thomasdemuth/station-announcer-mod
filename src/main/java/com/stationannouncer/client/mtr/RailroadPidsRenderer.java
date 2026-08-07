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
import java.util.ArrayList;
import java.util.List;

/**
 * The commuter-railroad departure board: line name and wall clock across the
 * top, the departure time and destination on a bar in the line's colour, then
 * the route ahead drawn as a station list with timed connections.
 *
 * <p>The canvas is 256 units across — twice the subway PIDS's 128 — so the
 * same physical panel holds roughly twice the text. Everything is emissive,
 * on the same quad-and-polygon-offset pipeline the other screens use.</p>
 */
@Environment(EnvType.CLIENT)
public class RailroadPidsRenderer implements BlockEntityRenderer<RailroadPidsBlockEntity> {
    /** Canvas width. Double the subway PIDS, which is the whole point of this board. */
    private static final int CANVAS_WIDTH = 256;

    private static final int BG = 0xFF0E0E11;
    private static final int HEADER_BG = 0xFF191920;
    private static final int TEXT_WHITE = 0xFFF2F2F4;
    private static final int TEXT_GRAY = 0xFF8B8B95;
    private static final int TEXT_DARK = 0xFF101014;
    private static final int PANEL_GRAY = 0xFF1A1A1C;

    // Layout, in canvas units.
    private static final float MARGIN = 10;
    private static final float RIGHT = CANVAS_WIDTH - MARGIN;
    private static final float HEADER_BOTTOM = 36;
    private static final float BAR_BOTTOM = 132;
    private static final float LIST_TOP = 148;
    private static final float LINE_X = 26;          // centre of the route line
    private static final float LINE_HALF = 3.5f;
    private static final float NAME_X = 48;
    private static final float DOT_RADIUS = 5;
    private static final float ROW_HEIGHT = 30;
    /**
     * Connection spacing. A connection belongs to the stop ABOVE it, so it
     * sits close under its own station and the next station starts well
     * clear of it — the grouping has to be readable at a glance.
     */
    private static final float CONNECTION_FIRST = 21;   // station row to first pill
    private static final float CONNECTION_SPACING = 20; // pill to pill
    private static final float CONNECTION_TRAILING = 27; // last pill to the next station
    private static final float PILL_X = 66;
    private static final float PILL_HALF_HEIGHT = 9;

    @Override
    public void render(RailroadPidsBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (!(entity.getCachedState().getBlock() instanceof RailroadPidsBlock block)) {
            return;
        }
        Direction facing = entity.getCachedState().get(RailroadPidsBlock.FACING);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));

        // Gathered once and drawn on both faces of a free-standing board.
        RailroadRouteData.Board board = RailroadRouteData.board(entity);

        int sides = block.standing ? 2 : 1;
        for (int side = 0; side < sides; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            paintScreen(matrices, vertexConsumers, block.standing, board);
            matrices.pop();
        }
        matrices.pop();
    }

    private void paintScreen(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                             boolean standing, RailroadRouteData.Board board) {
        float screenWidth = 14.0f / 16.0f;
        float screenHeight = 30.0f / 16.0f;
        float top = 39.0f / 16.0f;                                  // screen spans 0.5–2.5 blocks
        float front = -((standing ? 1.5f : 1.0f) / 16.0f) - 0.001f;
        float zShift = standing ? 0.0f : 0.5f;                      // wall panel sits at the back of the block

        matrices.push();
        // Nameplate convention: negative X and Y scale so text reads left-to-right
        // for a viewer on the -Z (facing) side; canvas origin at their top-left.
        matrices.translate(screenWidth / 2.0f, top, zShift + front);
        float unit = screenWidth / CANVAS_WIDTH;
        matrices.scale(-unit, -unit, unit);
        int canvasHeight = Math.round(screenHeight / unit);

        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
        painter.quad(0, 0, CANVAS_WIDTH, canvasHeight, 0.0f, BG);

        paintHeader(painter, board);
        if (board.hasTrain()) {
            paintDepartureBar(painter, board);
            paintStops(painter, board, canvasHeight);
        } else {
            paintEmpty(painter, board.noPlatforms(), canvasHeight);
        }
        matrices.pop();
    }

    // -------------------------------------------------------------- header

    /** Two-digit zero padding without the cost of String.format (this runs per frame, per screen). */
    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    /** Line name on the left, the real-world wall clock on the right. */
    private void paintHeader(CanvasPainter painter, RailroadRouteData.Board board) {
        painter.quad(0, 0, CANVAS_WIDTH, HEADER_BOTTOM, -0.2f, HEADER_BG);

        LocalTime time = LocalTime.now();
        String clock = twoDigits(time.getHour()) + ":" + twoDigits(time.getMinute());
        String seconds = ":" + twoDigits(time.getSecond());
        float secondsWidth = painter.width(seconds, 15);
        painter.text(seconds, RIGHT - secondsWidth, 11, 15, TEXT_GRAY);
        painter.textRight(clock, RIGHT - secondsWidth, 11, 15, TEXT_WHITE);

        // The MTR route the next train runs. No colour chip — the departure
        // bar right below is already the line's colour, and the name is worth
        // the width more than a second cue is.
        float maxWidth = RIGHT - secondsWidth - painter.width(clock, 15) - MARGIN - 8;
        painter.textFitted(board.routeName(), MARGIN, 11, 15, 9, TEXT_WHITE, maxWidth);
    }

    private void paintEmpty(CanvasPainter painter, boolean noPlatforms, int canvasHeight) {
        String line1 = noPlatforms ? "NO NEARBY PLATFORM" : "NO SCHEDULED";
        String line2 = noPlatforms ? "USE MTR BRUSH" : "TRAINS";
        painter.textCentered(line1, CANVAS_WIDTH / 2.0f, canvasHeight / 2.0f - 20, 16, TEXT_GRAY);
        painter.textCentered(line2, CANVAS_WIDTH / 2.0f, canvasHeight / 2.0f + 2, 16, TEXT_GRAY);
    }

    // ------------------------------------------------------- departure bar

    /**
     * The colour bar: when this train leaves (real-world clock time, taken
     * from its countdown so it tracks MTR's own schedule slipping) and where
     * it is going, on the line's colour.
     */
    private void paintDepartureBar(CanvasPainter painter, RailroadRouteData.Board board) {
        painter.quad(0, HEADER_BOTTOM, CANVAS_WIDTH, BAR_BOTTOM, -0.2f, board.routeColor());
        int ink = readableOn(board.routeColor());

        // Wall-clock time of departure, so a slipping schedule moves the
        // printed time rather than silently drifting from it.
        LocalTime time = LocalTime.now().plusNanos(
                Math.max(0, board.departureMillis() - System.currentTimeMillis()) * 1_000_000L);
        painter.text(twoDigits(time.getHour()) + ":" + twoDigits(time.getMinute()),
                MARGIN + 2, HEADER_BOTTOM + 8, 30, ink);
        painter.textFitted(board.destination(),
                MARGIN + 2, HEADER_BOTTOM + 44, 40, 18, ink, CANVAS_WIDTH - 2 * MARGIN - 4);
    }

    /** Black or white, whichever reads on the given background. */
    private static int readableOn(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        // Rec. 601 luma — good enough to separate light line colours (yellow,
        // cyan) from the dark ones, which is all this decides.
        return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? TEXT_DARK : 0xFFFFFFFF;
    }

    // -------------------------------------------------------- station list

    /**
     * The stops ahead. The route line is drawn first and the dots on top of
     * it, so the line passes cleanly behind every marker; whatever does not
     * fit collapses into a "Continues to …" footer naming the terminus.
     */
    private void paintStops(CanvasPainter painter, RailroadRouteData.Board board, int canvasHeight) {
        List<RailroadRouteData.Stop> stops = board.stops();
        if (stops.isEmpty()) {
            painter.textCentered("ROUTE UNAVAILABLE", CANVAS_WIDTH / 2.0f, LIST_TOP + 20, 14, TEXT_GRAY);
            return;
        }
        float bottomLimit = canvasHeight - 14;
        float footerReserve = 26;

        List<Runnable> deferred = new ArrayList<>();
        float y = LIST_TOP;
        float lastY = y;
        int drawn = 0;
        boolean truncated = false;
        for (int i = 0; i < stops.size(); i++) {
            RailroadRouteData.Stop stop = stops.get(i);
            float height = stopHeight(stop.connections().size());
            boolean isLast = i == stops.size() - 1;
            float limit = isLast ? bottomLimit : bottomLimit - footerReserve;
            if (y + height > limit) {
                truncated = true;
                break;
            }
            float rowY = y;
            boolean first = i == 0;
            boolean terminus = isLast;
            deferred.add(() -> drawStop(painter, stop, rowY, first, terminus));
            lastY = rowY;
            y += height;
            drawn++;
        }
        if (drawn == 0) {
            // Degenerate: not even one row fits (should not happen).
            painter.textCentered("Continues to " + board.terminus(), CANVAS_WIDTH / 2.0f, LIST_TOP, 14, TEXT_GRAY);
            return;
        }

        painter.quad(LINE_X - LINE_HALF, LIST_TOP - 2, LINE_X + LINE_HALF, lastY + 2, -0.4f, board.routeColor());
        deferred.forEach(Runnable::run);

        if (truncated) {
            painter.text("Continues to", NAME_X, y + 2, 13, TEXT_GRAY);
            painter.textFitted(board.terminus(), NAME_X, y + 16, 17, 11, TEXT_WHITE, RIGHT - NAME_X);
        }
    }

    /** Vertical space one stop needs, from its own row to the next stop's. */
    private static float stopHeight(int connections) {
        return connections == 0
                ? ROW_HEIGHT
                : CONNECTION_FIRST + (connections - 1) * CONNECTION_SPACING + CONNECTION_TRAILING;
    }

    /** One stop: its marker on the line, its name, and any connections under it. */
    private void drawStop(CanvasPainter painter, RailroadRouteData.Stop stop, float y, boolean first, boolean terminus) {
        if (first || terminus) {
            // Origin and terminus get the crossbar the real boards use.
            painter.quad(LINE_X - 10, y - 2.5f, LINE_X + 10, y + 2.5f, -0.6f, 0xFFFFFFFF);
        } else {
            painter.circleBullet(LINE_X, y, DOT_RADIUS, 0xFFFFFFFF, "", false);
        }
        painter.textFitted(stop.stationName(), NAME_X, y - 9, 19, 12, TEXT_WHITE, RIGHT - NAME_X);

        List<RailroadRouteData.Connection> connections = stop.connections();
        for (int i = 0; i < connections.size(); i++) {
            drawConnection(painter, connections.get(i), y, y + CONNECTION_FIRST + i * CONNECTION_SPACING);
        }
    }

    /**
     * A connection: a dashed elbow off the route line into a pill in the
     * connecting line's colour, the way the real boards draw branches.
     */
    private void drawConnection(CanvasPainter painter, RailroadRouteData.Connection connection,
                                float stopY, float y) {
        float elbowX = LINE_X + 8;
        dashedVertical(painter, elbowX, stopY + 4, y);
        dashedHorizontal(painter, elbowX, PILL_X - 2, y);

        float labelWidth = painter.width(connection.label(), 13);
        float pillWidth = Math.min(labelWidth + 16, RIGHT - PILL_X);
        pill(painter, PILL_X, y, pillWidth, connection.color());
        painter.textFitted(connection.label(), PILL_X + 8, y - 6, 13, 8,
                readableOn(connection.color()), pillWidth - 16);
    }

    private static final float DASH = 4.0f;
    private static final int ELBOW = 0xFFB4B4BE;

    private void dashedHorizontal(CanvasPainter painter, float x1, float x2, float y) {
        for (float x = x1; x < x2; x += DASH * 2) {
            painter.quad(x, y - 1.2f, Math.min(x + DASH, x2), y + 1.2f, -0.5f, ELBOW);
        }
    }

    private void dashedVertical(CanvasPainter painter, float x, float y1, float y2) {
        for (float y = y1; y < y2; y += DASH * 2) {
            painter.quad(x - 1.2f, y, x + 1.2f, Math.min(y + DASH, y2), -0.5f, ELBOW);
        }
    }

    /** Rounded pill: a bar with a half-circle cap at each end. */
    private void pill(CanvasPainter painter, float x, float centerY, float width, int color) {
        float radius = PILL_HALF_HEIGHT;
        painter.circleBullet(x + radius, centerY, radius, color, "", false);
        painter.circleBullet(x + width - radius, centerY, radius, color, "", false);
        painter.quad(x + radius, centerY - radius, x + width - radius, centerY + radius, -0.55f, color);
    }

    @Override
    public boolean rendersOutsideBoundingBox(RailroadPidsBlockEntity blockEntity) {
        return true; // the screen extends beyond its block space
    }
}
