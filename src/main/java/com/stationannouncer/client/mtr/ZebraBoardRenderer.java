package com.stationannouncer.client.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.FacingDecorBlock;
import com.stationannouncer.block.ZebraBoardBlock;
import com.stationannouncer.block.ZebraBoardBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * Draws a zebra board's label: a white plate with black lettering laid over
 * the stripes between the rails, the way "R-160" and "8" are stuck onto the
 * real boards.
 *
 * <p>The plate is sized to its text and may spill onto the neighbouring board
 * blocks, but never past the run's end plates: it is pushed back inside the
 * run, and a label longer than the whole run is condensed to fit. The hanging
 * board is double-sided, so it carries the label on both faces at the same
 * spot along the beam.</p>
 */
@Environment(EnvType.CLIENT)
public class ZebraBoardRenderer implements BlockEntityRenderer<ZebraBoardBlockEntity> {
    /** Canvas units per block, as everywhere else in this mod. */
    private static final float UNIT = 1.0f / 64.0f;
    /** Canvas units per model pixel. */
    private static final float PX = 4.0f;

    /** The striped panel between the rails, model y 6..12, as canvas y from the block top. */
    private static final float PLATE_TOP = (16 - 12) * PX;
    private static final float PLATE_BOTTOM = (16 - 6) * PX;
    private static final float PLATE_HEIGHT = PLATE_BOTTOM - PLATE_TOP;

    /** Cap height of the lettering: ~2/3 of the plate, as on the photos. */
    private static final float CAP = 16.0f;
    private static final float TEXT_SIZE = CAP / SignLayout.CAP_PER_SIZE;
    private static final float TEXT_TOP = PLATE_TOP + (PLATE_HEIGHT - CAP) / 2.0f - SignLayout.TOP_PER_SIZE * TEXT_SIZE;
    /** Space either side of the text; a one-character label comes out about square. */
    private static final float PAD = 5.0f;
    private static final float MIN_WIDTH = PLATE_HEIGHT;

    /** The 1 px end plate at a free end of the run. */
    private static final float END_PLATE = 1 * PX;
    /** How far along the run a plate may look for room (blocks each way). */
    private static final int MAX_RUN = 8;

    /** Panel front planes (model z), and how far off them the plate is drawn. */
    private static final float WALL_FRONT_Z = 12.5f;
    private static final float HANGING_FRONT_Z = 6.5f;
    private static final float STANDOFF = 0.004f;

    private static final int PLATE_WHITE = 0xF2F2EE;
    private static final int INK_BLACK = 0x121212;

    @Override
    public void render(ZebraBoardBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        String text = entity.getText();
        BlockState state = entity.getCachedState();
        if (text.isEmpty() || !(state.getBlock() instanceof ZebraBoardBlock)) {
            return;
        }
        Direction facing = state.get(FacingDecorBlock.FACING);
        boolean hanging = state.isOf(ModContent.ZEBRA_BOARD_HANGING);

        // Room along the run, in front-face canvas x (0 = the viewer's left
        // edge of this block, which is the block's RIGHT-property side).
        World world = entity.getWorld();
        int rightRun = world == null ? 0 : runLength(world, entity.getPos(), state, facing.rotateYClockwise());
        int leftRun = world == null ? 0 : runLength(world, entity.getPos(), state, facing.rotateYCounterclockwise());
        float minX = -64.0f * rightRun + END_PLATE;
        float maxX = 64.0f + 64.0f * leftRun - END_PLATE;

        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers).withFont(SignLayout.FONT);
        float textWidth = painter.width(text, TEXT_SIZE);
        float width = Math.min(Math.max(MIN_WIDTH, textWidth + 2 * PAD), maxX - minX);
        float left = switch (entity.getAlign()) {
            case LEFT -> Math.max(0.0f, minX);
            case RIGHT -> Math.min(64.0f, maxX) - width;
            case CENTER -> 32.0f - width / 2.0f;
        };
        left = Math.max(minX, Math.min(left, maxX - width));
        // Condense (never trim) a label wider than the room it has.
        float condense = Math.min(1.0f, (width - 2 * PAD) / textWidth);

        float shade = shade(light, tickDelta);
        int plate = 0xFF000000 | tint(PLATE_WHITE, shade);
        int ink = 0xFF000000 | tint(INK_BLACK, shade);

        float frontZ = (hanging ? HANGING_FRONT_Z : WALL_FRONT_Z) / 16.0f - 0.5f;
        paintFace(matrices, painter, facing, 0.0f, frontZ, left, width, text, textWidth, condense, plate, ink);
        if (hanging) {
            // The back face, seen from behind: same stretch of beam, mirrored canvas.
            paintFace(matrices, painter, facing, 180.0f, frontZ, 64.0f - left - width, width,
                    text, textWidth, condense, plate, ink);
        }
    }

    private static void paintFace(MatrixStack matrices, CanvasPainter painter, Direction facing, float extraYaw,
                                  float frontZ, float left, float width, String text, float textWidth,
                                  float condense, int plate, int ink) {
        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation() + extraYaw));
        // Canvas origin at the block's top-left as the viewer sees it, on the panel front.
        matrices.translate(0.5, 1.0, frontZ - STANDOFF);
        matrices.scale(-UNIT, -UNIT, UNIT);

        painter.quad(left, PLATE_TOP, left + width, PLATE_BOTTOM, 0.0f, plate);
        matrices.push();
        matrices.translate(left + width / 2.0f - textWidth * condense / 2.0f, TEXT_TOP, 0.0f);
        matrices.scale(condense, 1.0f, 1.0f);
        painter.text(text, 0.0f, 0.0f, TEXT_SIZE, ink);
        matrices.pop();
        matrices.pop();
    }

    /** Boards continuing from {@code pos} toward {@code side} (same block, same facing), capped. */
    private static int runLength(World world, BlockPos pos, BlockState state, Direction side) {
        Direction facing = state.get(FacingDecorBlock.FACING);
        BlockPos.Mutable cursor = pos.mutableCopy();
        int count = 0;
        while (count < MAX_RUN) {
            BlockState next = world.getBlockState(cursor.move(side));
            if (!next.isOf(state.getBlock()) || next.get(FacingDecorBlock.FACING) != facing) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * The canvas layer is full-bright; a printed label must not glow on a dark
     * platform, so darken it with the light at the board the way the stripes
     * around it are.
     */
    private static float shade(int light, float tickDelta) {
        float block = LightmapTextureManager.getBlockLightCoordinates(light) / 15.0f;
        float sky = LightmapTextureManager.getSkyLightCoordinates(light) / 15.0f;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            sky *= client.world.getSkyBrightness(tickDelta);
        }
        return 0.3f + 0.7f * Math.max(block, sky);
    }

    private static int tint(int rgb, float shade) {
        int r = Math.round((rgb >> 16 & 0xFF) * shade);
        int g = Math.round((rgb >> 8 & 0xFF) * shade);
        int b = Math.round((rgb & 0xFF) * shade);
        return r << 16 | g << 8 | b;
    }

    @Override
    public boolean rendersOutsideBoundingBox(ZebraBoardBlockEntity blockEntity) {
        return true; // a label wider than a metre spills onto the next board blocks
    }
}
