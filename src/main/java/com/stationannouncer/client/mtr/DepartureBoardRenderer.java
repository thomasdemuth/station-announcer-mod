package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.DepartureBoardBlock;
import com.stationannouncer.mtr.DepartureBoardBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import java.time.LocalTime;
import java.util.List;

/**
 * The big concourse departure board, drawn across a merged rectangle of blocks.
 *
 * <p>Only the rectangle's origin draws; every other cell returns immediately,
 * the same way the station-name mosaic renders a whole run from its
 * min-coordinate segment. The canvas is sized from the rectangle, at a fixed
 * {@value #UNITS_PER_BLOCK} units per block, so text is one physical size no
 * matter how big the board is — and because a row is a fixed number of units,
 * a taller board simply lists more trains.</p>
 *
 * <p>Each row is filled in its route's own colour, which is what makes the real
 * board readable from across a hall: you find your line by colour, not by
 * reading. The station name sits along the bottom.</p>
 */
@Environment(EnvType.CLIENT)
public class DepartureBoardRenderer implements BlockEntityRenderer<DepartureBoardBlockEntity> {
    /** Canvas units per block. A 4-block-wide board is a 256-unit canvas. */
    private static final int UNITS_PER_BLOCK = 64;

    private static final int BG = 0xFF0A0A0F;
    private static final int HEADER_BG = 0xFFE8E8EC;
    private static final int HEADER_TEXT = 0xFF101828;
    private static final int COLUMN_BG = 0xFF20222A;
    private static final int COLUMN_TEXT = 0xFFB9C2D0;
    private static final int FOOTER_BG = 0xFF15161C;
    private static final int FOOTER_TEXT = 0xFFD8DEE9;
    private static final int RULE = 0xFF000000;

    // Vertical bands, in canvas units.
    private static final float HEADER_H = 26;
    private static final float COLUMN_H = 15;
    private static final float FOOTER_H = 18;
    private static final float ROW_H = 22;

    /** See RailroadDepartureRenderer: coplanar quads hatch, text sits at -1.2. */
    private static final float LAYER_PANEL = -0.2f;
    private static final float LAYER_TRIM = -0.4f;

    private static final float MARGIN = 5;

    @Override
    public void render(DepartureBoardBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        var state = entity.getCachedState();
        if (!(state.getBlock() instanceof DepartureBoardBlock block) || entity.getWorld() == null) {
            return;
        }
        DepartureBoardBlock.Rect rect = DepartureBoardBlock.rectangle(entity.getWorld(), entity.getPos(), state);
        if (!rect.origin().equals(entity.getPos())) {
            return; // a cell of somebody else's board
        }
        Direction facing = state.get(DepartureBoardBlock.FACING);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));

        DepartureBoardData.Board board = DepartureBoardData.board(entity.getPos(), entity.getPlatformIds());
        org.mtr.core.data.Station found = MtrDataCache.station(entity.getPos());
        String station = found == null ? "" : RailroadRouteData.firstLang(found.getName());

        // A hanging case is read from both sides; a wall board only from the front.
        int sides = block.hanging ? 2 : 1;
        for (int side = 0; side < sides; side++) {
            matrices.push();
            if (side == 1) {
                // Turning the board also mirrors which end the origin is at, so
                // the canvas has to start from the far end of the rectangle.
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
                matrices.translate(rect.width() - 1.0f, 0.0f, 0.0f);
            }
            paintScreen(matrices, vertexConsumers, block, rect, entity, board, station);
            matrices.pop();
        }
        matrices.pop();
    }

    private void paintScreen(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                             DepartureBoardBlock block, DepartureBoardBlock.Rect rect,
                             DepartureBoardBlockEntity entity, DepartureBoardData.Board board,
                             String station) {
        // Model z of the screen face: the front of the case either way.
        float faceModelZ = block.hanging ? 5.0f : 12.0f;
        // 0.004, not the 0.001 the older wall boards use: this screen's own block
        // model has a face in the SAME plane, and at 0.001 the two fought.
        float localZ = faceModelZ / 16.0f - 0.5f - 0.004f;

        float inset = 1.0f / 16.0f;                    // 1 px border around the picture
        float physicalWidth = rect.width() - 2 * inset;
        float physicalHeight = rect.height() - 2 * inset;

        matrices.push();
        // Canvas origin at the reader's top-left: local +X is their left, and
        // the origin block is the BOTTOM cell, so the top is `height` up.
        matrices.translate(0.5f - inset, rect.height() - inset, localZ);
        float unit = physicalWidth / (UNITS_PER_BLOCK * rect.width());
        matrices.scale(-unit, -unit, unit);

        float canvasWidth = UNITS_PER_BLOCK * rect.width();
        float canvasHeight = physicalHeight / unit;

        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
        painter.quad(0, 0, canvasWidth, canvasHeight, 0.0f, BG);   // the only quad at 0

        paintHeader(painter, entity, canvasWidth, station);
        paintColumns(painter, canvasWidth);
        paintFooter(painter, canvasWidth, canvasHeight, station);

        float top = HEADER_H + COLUMN_H;
        float available = canvasHeight - FOOTER_H - top;
        int rows = Math.max(0, (int) (available / ROW_H));
        List<DepartureBoardData.Departure> departures = board.departures();
        if (departures.isEmpty()) {
            painter.textCentered(board.noPlatforms() ? "NO NEARBY PLATFORM" : "NO DEPARTURES",
                    canvasWidth / 2.0f, top + 10, 13, COLUMN_TEXT);
            matrices.pop();
            return;
        }
        for (int i = 0; i < rows && i < departures.size(); i++) {
            paintRow(painter, departures.get(i), top + i * ROW_H, canvasWidth);
        }
        matrices.pop();
    }

    private void paintHeader(CanvasPainter painter, DepartureBoardBlockEntity entity,
                             float canvasWidth, String station) {
        painter.quad(0, 0, canvasWidth, HEADER_H, LAYER_PANEL, HEADER_BG);
        String title = entity.getTitle().isBlank()
                ? (station.isBlank() ? "Departures" : station)
                : entity.getTitle();
        painter.textCentered(title, canvasWidth / 2.0f, HEADER_H / 2 - 8, 17, HEADER_TEXT);
    }

    private void paintColumns(CanvasPainter painter, float canvasWidth) {
        painter.quad(0, HEADER_H, canvasWidth, HEADER_H + COLUMN_H, LAYER_PANEL, COLUMN_BG);
        float y = HEADER_H + COLUMN_H / 2 - 5;
        painter.text("Time", MARGIN, y, 10, COLUMN_TEXT);
        painter.text("Departures", destinationX(canvasWidth), y, 10, COLUMN_TEXT);
        painter.text("Stops", stopsX(canvasWidth), y, 10, COLUMN_TEXT);
        painter.textRight("Track", canvasWidth - MARGIN, y, 10, COLUMN_TEXT);
    }

    private void paintFooter(CanvasPainter painter, float canvasWidth, float canvasHeight, String station) {
        float top = canvasHeight - FOOTER_H;
        painter.quad(0, top, canvasWidth, canvasHeight, LAYER_PANEL, FOOTER_BG);
        LocalTime now = LocalTime.now();
        String clock = (now.getHour() % 12 == 0 ? 12 : now.getHour() % 12)
                + ":" + twoDigits(now.getMinute()) + ":" + twoDigits(now.getSecond())
                + (now.getHour() < 12 ? " am" : " pm");
        painter.text(clock, MARGIN, top + FOOTER_H / 2 - 5, 10, FOOTER_TEXT);
        painter.textCentered(station.isBlank() ? "" : station,
                canvasWidth / 2.0f, top + FOOTER_H / 2 - 6, 12, FOOTER_TEXT);
    }

    /** Columns are proportional so the board reads the same at any width. */
    private static float destinationX(float canvasWidth) {
        return canvasWidth * 0.14f;
    }

    private static float stopsX(float canvasWidth) {
        return canvasWidth * 0.44f;
    }

    private void paintRow(CanvasPainter painter, DepartureBoardData.Departure departure,
                          float y, float canvasWidth) {
        painter.quad(0, y, canvasWidth, y + ROW_H - 1, LAYER_PANEL, departure.color());
        painter.quad(0, y + ROW_H - 1, canvasWidth, y + ROW_H, LAYER_TRIM, RULE);

        int ink = RailroadDepartureRenderer.contrasting(departure.color());
        float textY = y + ROW_H / 2 - 7;

        LocalTime time = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(departure.departureMillis()),
                java.time.ZoneId.systemDefault());
        int hour12 = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        painter.text(hour12 + ":" + twoDigits(time.getMinute()), MARGIN, textY, 13, ink);

        float destX = destinationX(canvasWidth);
        float stopsColumn = stopsX(canvasWidth);
        painter.textFitted(departure.destination(), destX, textY, 13, 8, ink,
                stopsColumn - destX - 6);

        // A couple of intermediate calls, the way the real board teases the route.
        if (!departure.stops().isEmpty()) {
            List<String> stops = departure.stops();
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < Math.min(3, stops.size()); i++) {
                if (i > 0) {
                    joined.append(" · ");
                }
                joined.append(stops.get(i));
            }
            painter.textFitted(joined.toString(), stopsColumn, textY + 1, 11, 7, ink,
                    canvasWidth - stopsColumn - 40);
        }
        if (!departure.track().isBlank()) {
            painter.textRight(departure.track(), canvasWidth - MARGIN, textY, 13, ink);
        }
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
