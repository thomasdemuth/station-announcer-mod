package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.RailroadPidsBlockEntity;
import com.stationannouncer.mtr.RailroadPidsHangingBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import java.time.LocalTime;
import java.util.List;

/**
 * The ceiling-hung railroad board. Two screens on one case, alternating:
 *
 * <ul>
 *   <li><b>Next train</b> — a bar in the line's colour carrying the arrival
 *       clock time, the destination and how far off it is, over the remaining
 *       stops as a wrapped list.</li>
 *   <li><b>Departures</b> — TIME / DESTINATION / ETA / TRK, four rows, each
 *       destination on a chip in its line's colour.</li>
 * </ul>
 *
 * <p>Canvas units are the same physical size as on the wall and standing
 * boards ({@link #UNITS_PER_PIXEL}), so text drawn at a given size looks
 * identical across the whole railroad family however differently shaped the
 * screens are.</p>
 */
@Environment(EnvType.CLIENT)
public class RailroadHangingRenderer implements BlockEntityRenderer<RailroadPidsBlockEntity> {
    /**
     * Canvas units per model pixel — fixed across every railroad board so
     * "size 34" means one physical size everywhere. Matches the wall board's
     * 256 units across its 14 px screen.
     */
    static final float UNITS_PER_PIXEL = 256.0f / 14.0f;

    private static final int BG = 0xFF08080B;
    private static final int CASE = 0xFF17171A;
    private static final int TEXT_WHITE = 0xFFF2F2F4;
    private static final int TEXT_GRAY = 0xFF8B8B95;
    private static final int TEXT_DARK = 0xFF0E0E12;
    private static final int HEADER_BAR = 0xFFD8D8DE;

    /** Seconds one wrapped page of the stop list holds before rolling on. */
    private static final long STOP_PAGE_MILLIS = 6000;

    /**
     * How far the screen floats off the front of the case, in blocks. Unlike
     * the wall boards — whose panel is block-model geometry in the solid pass —
     * the case here is drawn in the SAME quad layer as the screen, so the two
     * are one depth comparison apart and a hair's separation z-fights at
     * grazing angles. Well clear of the case, still far too small to see.
     */
    private static final float SCREEN_STANDOFF = 0.008f;

    /**
     * Depth exaggeration for the canvas's internal layering. A canvas unit is
     * about 1/300 of a block, so the -0.2/-0.6 offsets the painters use would
     * otherwise separate by microns and fight each other too.
     */
    private static final float DEPTH_STRETCH = 6.0f;

    @Override
    public void render(RailroadPidsBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (!(entity.getCachedState().getBlock() instanceof RailroadPidsHangingBlock)) {
            return;
        }
        Direction facing = entity.getCachedState().get(RailroadPidsHangingBlock.FACING);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        // Centre on the two-block-wide unit (the partner half is at local +X).
        matrices.translate(0.5, 0.0, 0.0);
        drawCase(matrices, vertexConsumers);

        RailroadRouteData.Board board = RailroadRouteData.board(entity);
        boolean departures = showDepartures(entity, board);

        for (int side = 0; side < 2; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            paintScreen(matrices, vertexConsumers, board, departures);
            matrices.pop();
        }
        matrices.pop();
    }

    /** Which of the two screens is up right now. */
    private static boolean showDepartures(RailroadPidsBlockEntity entity, RailroadRouteData.Board board) {
        return switch (entity.getDisplayMode()) {
            case NEXT_TRAIN -> false;
            case DEPARTURES -> true;
            // Hold the next train up while it is nearly in; otherwise the list
            // is the more useful thing to be showing.
            case AUTO -> !board.hasTrain() || board.untilArrival() > 120_000;
            default -> (System.currentTimeMillis() / (entity.getFlipSeconds() * 1000L)) % 2 == 1;
        };
    }

    /** The case: a deep box, the way the real railroad units are built. */
    private void drawCase(MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getDebugQuads());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float front = (RailroadPidsHangingBlock.BOX_FRONT - 8.0f) / 16.0f;
        float back = (RailroadPidsHangingBlock.BOX_BACK - 8.0f) / 16.0f;
        CanvasPainter.box(buffer, matrix, -1.0f, 0.0f, front,
                1.0f, RailroadPidsHangingBlock.BOX_TOP / 16.0f, back, CASE);
    }

    private void paintScreen(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                             RailroadRouteData.Board board, boolean departures) {
        float bezel = RailroadPidsHangingBlock.BEZEL / 16.0f;
        float screenWidth = 2.0f - 2 * bezel;
        float screenHeight = RailroadPidsHangingBlock.BOX_TOP / 16.0f - 2 * bezel;
        float top = RailroadPidsHangingBlock.BOX_TOP / 16.0f - bezel;
        float front = (RailroadPidsHangingBlock.BOX_FRONT - 8.0f) / 16.0f - SCREEN_STANDOFF;

        matrices.push();
        // Nameplate convention: negative X and Y scale so text reads left-to-right
        // for a viewer on the -Z (facing) side; canvas origin at their top-left.
        matrices.translate(screenWidth / 2.0f, top, front);
        float unit = (14.0f / 16.0f) / 256.0f;   // one canvas unit, in blocks
        matrices.scale(-unit, -unit, unit * DEPTH_STRETCH);
        int canvasWidth = Math.round(screenWidth / unit);
        int canvasHeight = Math.round(screenHeight / unit);

        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
        painter.quad(0, 0, canvasWidth, canvasHeight, 0.0f, BG);

        if (!board.hasTrain()) {
            painter.textCentered(board.noPlatforms() ? "No platform selected" : "No trains on this track",
                    canvasWidth / 2.0f, canvasHeight / 2.0f - 18, 36, TEXT_GRAY);
        } else if (departures) {
            paintDepartures(painter, board, canvasWidth, canvasHeight);
        } else {
            paintNextTrain(painter, board, canvasWidth, canvasHeight);
        }
        matrices.pop();
    }

    // ------------------------------------------------------------ next train

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    /** Wall-clock time something happens, given how far off it is. */
    private static String clockAt(long millis) {
        LocalTime time = LocalTime.now().plusNanos(Math.max(0, millis - System.currentTimeMillis()) * 1_000_000L);
        return twoDigits(time.getHour()) + ":" + twoDigits(time.getMinute());
    }

    /** "in 7min", counting down to the minute; "Arriving" once it is essentially in. */
    private static String eta(long untilMillis) {
        if (untilMillis <= 20_000) {
            return "Arriving";
        }
        return "in " + Math.max(1, untilMillis / 60_000) + "min";
    }

    private void paintNextTrain(CanvasPainter painter, RailroadRouteData.Board board, int width, int height) {
        float barBottom = 54;
        painter.quad(0, 0, width, barBottom, -0.2f, board.routeColor());
        int ink = readableOn(board.routeColor());

        float pad = 10;
        String time = clockAt(board.arrivalMillis());
        painter.text(time, pad, 8, 38, ink);
        String countdown = eta(board.untilArrival());
        float countdownWidth = painter.width(countdown, 32);
        painter.text(countdown, width - pad - countdownWidth, 12, 32, ink);

        float destX = pad + painter.width(time, 38) + 16;
        painter.textFitted(board.destination(), destX, 8, 38, 20, ink,
                width - pad - countdownWidth - destX - 16);

        paintStopList(painter, board, width, height, barBottom + 8);
    }

    /**
     * The remaining stops, wrapped to fill the space under the bar. A route
     * longer than fits rolls one line at a time so every stop comes round —
     * whole lines, so nothing is ever half-clipped (the canvas has no scissor).
     */
    private void paintStopList(CanvasPainter painter, RailroadRouteData.Board board,
                               int width, int height, float top) {
        List<RailroadRouteData.Stop> stops = board.stops();
        if (stops.size() <= 1) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < stops.size(); i++) {   // stop 0 is this station
            if (i > 1) {
                text.append(", ");
            }
            text.append(stops.get(i).stationName());
        }

        float size = 30;
        float lineHeight = 34;
        float pad = 10;
        List<String> lines = painter.wrap(text.toString(), size, width - 2 * pad);
        int capacity = Math.max(1, (int) ((height - top) / lineHeight));
        int first = 0;
        if (lines.size() > capacity) {
            // Roll a line at a time, pausing at the top of the list so it is
            // readable from the start rather than always mid-scroll.
            int steps = lines.size() - capacity + 1;
            first = (int) ((System.currentTimeMillis() / STOP_PAGE_MILLIS) % steps);
        }
        float y = top;
        for (int i = first; i < lines.size() && i < first + capacity; i++) {
            painter.text(lines.get(i), pad, y, size, TEXT_WHITE);
            y += lineHeight;
        }
    }

    // ------------------------------------------------------------ departures

    /** TIME / DESTINATION / ETA / TRK, four rows, chips in each line's colour. */
    private void paintDepartures(CanvasPainter painter, RailroadRouteData.Board board, int width, int height) {
        float pad = 10;
        float timeX = pad;
        float destX = 118;
        float trkRight = width - pad;
        float etaRight = trkRight - 66;

        float headerBottom = 28;
        painter.quad(0, 0, width, headerBottom, -0.2f, HEADER_BAR);
        painter.text("TIME", timeX, 5, 20, TEXT_DARK);
        painter.text("DESTINATION", destX, 5, 20, TEXT_DARK);
        painter.textRight("ETA", etaRight, 5, 20, TEXT_DARK);
        painter.textRight("TRK", trkRight, 5, 20, TEXT_DARK);

        // The ETA is right-aligned, so the chip has to stop clear of the widest
        // ETA it could print, not merely clear of the column's right edge.
        float chipLimit = etaRight - 108;

        List<RailroadRouteData.Departure> departures = board.departures();
        // Four rows always share the whole screen below the header, so the
        // board never leaves a band of dead space at the bottom.
        float rowHeight = (height - headerBottom - 8) / 4.0f;
        float y = headerBottom + 4;
        for (int i = 0; i < departures.size() && i < 4; i++) {
            RailroadRouteData.Departure departure = departures.get(i);
            float centerY = y + rowHeight / 2.0f;
            float textSize = Math.min(32, rowHeight - 12);

            painter.text(clockAt(departure.arrivalMillis()), timeX, centerY - textSize / 2.0f, textSize, TEXT_WHITE);

            // Destination on a chip in the line's colour, like the real boards.
            float chipMax = chipLimit - destX + 5;
            float chipWidth = Math.min(painter.width(departure.destination(), textSize) + 14, chipMax);
            painter.quad(destX - 5, centerY - textSize / 2.0f - 3, destX - 5 + chipWidth,
                    centerY + textSize / 2.0f + 3, -0.4f, departure.color());
            painter.textFitted(departure.destination(), destX + 2, centerY - textSize / 2.0f, textSize,
                    14, readableOn(departure.color()), chipWidth - 14);

            long until = departure.arrivalMillis() - System.currentTimeMillis();
            String etaText = until <= 20_000 ? "now" : Math.max(1, until / 60_000) + "min";
            painter.textRight(etaText, etaRight, centerY - textSize / 2.0f, textSize, TEXT_WHITE);
            painter.textRight(departure.track(), trkRight, centerY - textSize / 2.0f, textSize, TEXT_WHITE);
            y += rowHeight;
        }
        if (departures.isEmpty()) {
            painter.textCentered("No trains on this track", width / 2.0f, height / 2.0f, 30, TEXT_GRAY);
        }
    }

    /** Black or white, whichever reads on the given background. */
    private static int readableOn(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? TEXT_DARK : 0xFFFFFFFF;
    }

    @Override
    public boolean rendersOutsideBoundingBox(RailroadPidsBlockEntity blockEntity) {
        return true; // the case spans two blocks
    }
}
