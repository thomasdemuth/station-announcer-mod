package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.StationDecorBlock;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.mtr.core.data.Station;

/**
 * Renders the station decor blocks: the NYC mosaic station-name band and the
 * station-colored / named iron columns. Same emissive quad-canvas pipeline as
 * the PIDS screens; station name and color come live from MTR
 * ({@code InitClient.findStation}), with the block's custom name overriding
 * the text when set.
 */
@Environment(EnvType.CLIENT)
public class StationDecorRenderer implements BlockEntityRenderer<StationDecorBlockEntity> {
    private static final int MOSAIC_FIELD = 0xFFE8E0CE;   // cream tile field
    private static final int MOSAIC_GROUT = 0xFFCDC3AC;   // grout lines between tiles
    private static final int MOSAIC_TEXT = 0xFF17172B;    // deep navy lettering
    private static final int FALLBACK_COLOR = 0xFF1F4D3A; // classic dark green border
    private static final int BOARD_BLACK = 0xFF0C0C0E;
    private static final int TEXT_WHITE = 0xFFF5F5F5;

    /** Canvas resolution used throughout: 64 units per block. */
    private static final float UNIT = 1.0f / 64.0f;

    @Override
    public void render(StationDecorBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        net.minecraft.block.Block block = entity.getCachedState().getBlock();
        Direction facing;
        if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            // The panel plane follows the railing run: connected east/west ->
            // the run goes along X and the panel faces north/south.
            boolean alongX = entity.getCachedState().get(com.stationannouncer.block.RailingBlock.EAST)
                    || entity.getCachedState().get(com.stationannouncer.block.RailingBlock.WEST);
            facing = alongX ? Direction.NORTH : Direction.EAST;
        } else {
            facing = entity.getCachedState().contains(StationDecorBlock.FACING)
                    ? entity.getCachedState().get(StationDecorBlock.FACING)
                    : Direction.NORTH;
        }

        Station station = org.mtr.mod.InitClient.findStation(
                new org.mtr.mapping.holder.BlockPos(entity.getPos()));
        String custom = entity.getCustomName();
        String name = !custom.isEmpty() ? custom
                : station != null ? firstLang(station.getName()) : "";
        int color = 0xFF000000 | (station != null ? station.getColor() : FALLBACK_COLOR & 0xFFFFFF);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        if (block instanceof StationDecorBlock) {
            paintMosaic(matrices, vertexConsumers, name, color);
        } else if (block instanceof com.stationannouncer.mtr.StationColumnBlock) {
            paintColumnBoard(matrices, vertexConsumers, name);
        } else if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            paintRailingSign(entity, matrices, vertexConsumers, name);
        } else if (block instanceof com.stationannouncer.mtr.HoldingLightBlock holdingLight) {
            paintHoldingLight(entity, matrices, vertexConsumers, holdingLight.green);
        }
        matrices.pop();
    }

    // ------------------------------------------------------ holding lights

    /** Last known departure time per holding-light position (for the green 15 s window). */
    private static final java.util.Map<Long, Long> LAST_DEPARTURE = new java.util.HashMap<>();

    /**
     * Three round lenses on both faces of the hanging box. Yellow: lit from
     * 5 s before arrival until 3 s before departure (held trains keep it
     * lit, since MTR pushes the departure time back). Green: blinks from
     * departure until 15 s after the train has gone.
     */
    private void paintHoldingLight(StationDecorBlockEntity entity, MatrixStack matrices,
                                   VertexConsumerProvider vertexConsumers, boolean green) {
        long platformId = resolvePlatform(entity);
        boolean active = false;
        if (platformId != 0) {
            org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList ids =
                    new org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList();
            ids.add(platformId);
            long offset = org.mtr.mod.data.ArrivalsCacheClient.INSTANCE.getMillisOffset();
            long now = System.currentTimeMillis();
            Long storedDeparture = LAST_DEPARTURE.get(entity.getPos().asLong());
            for (org.mtr.core.operation.ArrivalResponse arrival
                    : org.mtr.mod.data.ArrivalsCacheClient.INSTANCE.requestArrivals(ids)) {
                long arrivalLocal = arrival.getArrival() - offset;
                long departureLocal = arrival.getDeparture() - offset;
                if (green) {
                    // Remember the upcoming departure so the window survives
                    // the arrival entry disappearing once the train leaves.
                    if (departureLocal >= now) {
                        LAST_DEPARTURE.put(entity.getPos().asLong(), departureLocal);
                        storedDeparture = departureLocal;
                    }
                } else if (now >= arrivalLocal - 5000 && now <= departureLocal - 3000) {
                    active = true;
                }
                break; // only the first (current/next) arrival matters
            }
            if (green && storedDeparture != null
                    && now >= storedDeparture && now <= storedDeparture + 15000) {
                active = (now / 400) % 2 == 0; // blinking "time to leave"
            }
        }

        int lit = green ? 0xFF38E464 : 0xFFFFC03C;
        int dark = green ? 0xFF15321D : 0xFF3A3220;
        int lens = active ? lit : dark;
        for (int side = 0; side < 2; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            // Lens face of the box (model x 3..13, y 9..13, z 6..10).
            matrices.translate(0.3125, 13.0 / 16.0, -0.125 - 0.003);
            matrices.scale(-UNIT, -UNIT, UNIT);
            CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
            for (float cx : new float[]{8, 20, 32}) {
                painter.circleBullet(cx, 8, 4.5f, lens, "", false);
                if (active) {
                    painter.circleBullet(cx, 8, 3.0f, green ? 0xFFA0FFC0 : 0xFFFFE49A, "", false);
                }
            }
            matrices.pop();
        }
    }

    /** Configured platform id (stored via the brush picker) or the closest platform; 0 = none. */
    private static long resolvePlatform(StationDecorBlockEntity entity) {
        String stored = entity.getCustomName();
        if (!stored.isEmpty()) {
            try {
                long id = Long.parseLong(stored.trim());
                if (org.mtr.mod.client.MinecraftClientData.getInstance().platformIdMap.containsKey(id)) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // fall through to auto-detection
            }
        }
        long[] found = {0};
        org.mtr.mod.InitClient.findClosePlatform(
                new org.mtr.mapping.holder.BlockPos(entity.getPos()), 8,
                platform -> {
                    if (found[0] == 0) {
                        found[0] = platform.getId();
                    }
                });
        return found[0];
    }

    // -------------------------------------------------------- railing sign

    /** Longest run of adjacent sign segments merged into one panel. */
    private static final int MAX_SIGN_RUN = 8;

    /**
     * The black station-name panel set into the entrance railing, readable
     * from both sides (panel faces at ±3/16 from the railing's center plane,
     * tucked inside the post width so nothing z-fights). Adjacent sign
     * segments merge: the run's first block draws one continuous panel
     * across all of them, so long station names get the room they need.
     */
    private void paintRailingSign(StationDecorBlockEntity entity, MatrixStack matrices,
                                  VertexConsumerProvider vertexConsumers, String autoName) {
        net.minecraft.client.world.ClientWorld world = net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        net.minecraft.util.math.BlockPos pos = entity.getPos();
        boolean alongX = entity.getCachedState().get(com.stationannouncer.block.RailingBlock.EAST)
                || entity.getCachedState().get(com.stationannouncer.block.RailingBlock.WEST);
        Direction negDir = alongX ? Direction.WEST : Direction.NORTH;
        Direction posDir = alongX ? Direction.EAST : Direction.SOUTH;

        // Only the first segment of a contiguous run draws (the merged panel).
        if (world.getBlockState(pos.offset(negDir)).getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock) {
            return;
        }
        int run = 1;
        while (run < MAX_SIGN_RUN
                && world.getBlockState(pos.offset(posDir, run)).getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock) {
            run++;
        }
        // A custom name typed on ANY segment of the run wins over the auto name.
        String custom = entity.getCustomName();
        for (int i = 1; i < run && custom.isEmpty(); i++) {
            if (world.getBlockEntity(pos.offset(posDir, i)) instanceof StationDecorBlockEntity other) {
                custom = other.getCustomName();
            }
        }
        String text = !custom.isEmpty() ? custom : (autoName.isEmpty() ? "Subway" : autoName);

        float panelWidth = 64 * run - 8;           // canvas units (1 px margin each end)
        float halfWidthBlocks = (16 * run - 2) / 32.0f;
        float centerOffset = (run - 1) / 2.0f;     // run center, in blocks from this segment

        for (int side = 0; side < 2; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            // Panel: y 6..18 (48 canvas units tall), spanning the whole run.
            matrices.translate((side == 0 ? centerOffset : -centerOffset) + halfWidthBlocks,
                    1.125, -0.1875 - 0.004);
            matrices.scale(-UNIT, -UNIT, UNIT);
            CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
            painter.quad(0, 0, panelWidth, 48, 0.0f, BOARD_BLACK);
            painter.quad(1, 1, panelWidth - 1, 47, -0.05f, 0xFF17171A);

            float center = panelWidth / 2.0f;
            float maxWidth = panelWidth - 6;
            if (painter.width(text, 12) <= maxWidth) {
                painter.textCentered(text, center, 24 - 6, 12, TEXT_WHITE);
            } else {
                java.util.List<String> lines = painter.wrap(text, 9, maxWidth);
                if (lines.size() == 1) {
                    // one very long word: shrink it onto a single line
                    float size = Math.max(5, maxWidth / Math.max(1, painter.width(text, 1)));
                    painter.textCentered(text, center, 24 - size / 2.0f, size, TEXT_WHITE);
                } else {
                    painter.textCentered(lines.get(0), center, 24 - 11, 9, TEXT_WHITE);
                    painter.textCentered(lines.get(1), center, 24 + 2, 9, TEXT_WHITE);
                }
            }
            matrices.pop();
        }
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    private static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }

    // ------------------------------------------------------------- mosaic

    /**
     * The classic mosaic frieze: cream tile field, station-color border tile
     * rows, dark lettering. The band auto-sizes to the name and extends past
     * the block edges, centered on the block.
     */
    private void paintMosaic(MatrixStack matrices, VertexConsumerProvider vertexConsumers, String name, int color) {
        String text = (name.isEmpty() ? "BAKER CITY" : name).toUpperCase();
        // The band paints directly onto the wall behind the block: 0.01 in
        // front of the wall face (local +0.5) so nothing z-fights.
        matrices.push();
        matrices.translate(0.0, 1.0, 0.5 - 0.01);
        matrices.scale(-UNIT, -UNIT, UNIT);
        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);

        float textSize = 26;
        float textWidth = painter.width(text, textSize);
        // Snap the band to whole 8-unit tiles so the border pattern always
        // fits exactly (no partial tiles, nothing hanging past the edges).
        float bandWidth = (float) (Math.ceil(Math.max(64, textWidth + 40) / 8.0) * 8.0);
        float left = -bandWidth / 2.0f;
        float right = bandWidth / 2.0f;

        // Grout backing for the whole frame, then individual border tiles
        // inset by the grout gap — the frame reads as real small tiles.
        painter.quad(left, 0, right, 64, 0.0f, darken(color));
        for (float x = left; x < right - 0.1f; x += 8) {
            painter.quad(x + 0.4f, 0.4f, x + 7.6f, 7.6f, -0.1f, color);       // top row
            painter.quad(x + 0.4f, 56.4f, x + 7.6f, 63.6f, -0.1f, color);     // bottom row
        }
        for (float y = 8; y < 56 - 0.1f; y += 8) {
            painter.quad(left + 0.4f, y + 0.4f, left + 7.6f, y + 7.6f, -0.1f, color);   // left column
            painter.quad(right - 7.6f, y + 0.4f, right - 0.4f, y + 7.6f, -0.1f, color); // right column
        }

        // Cream field with its own grout grid.
        painter.quad(left + 8, 8, right - 8, 56, -0.1f, MOSAIC_FIELD);
        for (float x = left + 8; x <= right - 8 + 0.1f; x += 8) {
            painter.quad(Math.max(x - 0.4f, left + 8), 8, Math.min(x + 0.4f, right - 8), 56, -0.2f, MOSAIC_GROUT);
        }
        for (float y = 16; y <= 48; y += 8) {
            painter.quad(left + 8, y - 0.4f, right - 8, y + 0.4f, -0.2f, MOSAIC_GROUT);
        }

        painter.textCentered(text, 0, 32 - textSize / 2.0f, textSize, MOSAIC_TEXT);
        matrices.pop();
    }

    private static int darken(int argb) {
        int r = (argb >> 16 & 0xFF) * 3 / 4;
        int g = (argb >> 8 & 0xFF) * 3 / 4;
        int b = (argb & 0xFF) * 3 / 4;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------- column

    /**
     * The black station-name board on both flange faces of a named column
     * (the column body itself, including the station-color tint, comes from
     * the block model and color provider).
     */
    private void paintColumnBoard(MatrixStack matrices, VertexConsumerProvider vertexConsumers, String name) {
        if (name.isEmpty()) {
            return;
        }
        // Name board on both wide faces (front flange face at local z -0.25).
        for (int side = 0; side < 2; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            matrices.translate(0.3125, 0.75, -0.25 - 0.01);
            matrices.scale(-UNIT, -UNIT, UNIT);
            CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
            painter.quad(0, 0, 40, 16, 0.0f, BOARD_BLACK);
            String text = name.toUpperCase();
            float size = Math.min(9, 36 / Math.max(1, painter.width(text, 1)));
            painter.textCentered(text, 20, 8 - size / 2.0f, size, TEXT_WHITE);
            matrices.pop();
        }
    }

    @Override
    public boolean rendersOutsideBoundingBox(StationDecorBlockEntity blockEntity) {
        return true; // the mosaic band extends past the block
    }
}
