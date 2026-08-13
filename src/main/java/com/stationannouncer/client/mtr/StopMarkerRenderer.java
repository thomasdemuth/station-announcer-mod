package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.StopMarkerBlock;
import com.stationannouncer.mtr.StopMarkerBlockEntity;
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
import java.util.List;

/**
 * Draws a stop marker's plates: a solid 4x4 px square per sign, stacked along
 * the pole, with the legend painted on the front face over a 32-unit canvas
 * (so a 4 px plate still gets crisp lettering). Bolt heads in the corners and
 * — on the yellow OPTO boards — the black keyline from the real signs.
 *
 * <p>The legend is front-only, like the real plates: the back of each square
 * is plain plate colour.
 */
@Environment(EnvType.CLIENT)
public class StopMarkerRenderer implements BlockEntityRenderer<StopMarkerBlockEntity> {
    /** Canvas units across one plate. */
    private static final int CANVAS = 32;

    /** 32 canvas units span a 4 px plate, i.e. a quarter of a block. */
    private static final float UNIT = (float) (StopMarkerBlock.PLATE_SIZE / 16.0) / CANVAS;

    /**
     * Legend sizing. A short legend grows until it hits {@link #MAX_TEXT_SIZE}
     * so a lone "8" reads big like the real plates; a longer one is limited by
     * {@link #TEXT_WIDTH_LIMIT} instead and fills the width. The cap keeps the
     * glyph clear of the corner bolts and the OPTO keyline.
     */
    private static final float MAX_TEXT_SIZE = 22.0f;
    private static final float TEXT_WIDTH_LIMIT = 23.0f;

    @Override
    public void render(StopMarkerBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        StopMarkerBlock.Mount mount = entity.getCachedState().get(StopMarkerBlock.MOUNT);
        StopMarkerBlock.Style style = StopMarkerBlock.Style.of(entity.getCachedState());
        Direction facing = entity.getCachedState().get(StopMarkerBlock.FACING);
        List<StopMarkerBlockEntity.Sign> signs = entity.getSigns();

        double front = switch (style) {
            case BRACKET -> StopMarkerBlock.PLATE_FRONT_WALL_BRACKET;
            case BLADE -> StopMarkerBlock.PLATE_FRONT_BLADE;
            case FLUSH -> mount == StopMarkerBlock.Mount.WALL
                    ? StopMarkerBlock.PLATE_FRONT_WALL_FLUSH
                    : StopMarkerBlock.PLATE_FRONT_CEILING;
        };
        // Ceiling markers hang at the bottom of the block and the rest sit at
        // the top — except a blade, whose stack is centred on the block's
        // middle so a pole running past meets it head on.
        double stackTop = switch (style) {
            case BLADE -> StopMarkerBlock.BLADE_CENTRE + StopMarkerBlock.PLATE_SIZE * signs.size() / 2.0;
            default -> mount == StopMarkerBlock.Mount.CEILING
                    ? StopMarkerBlock.PLATE_SIZE * signs.size() : 16.0;
        };

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        // A blade plate is the ordinary plate turned a quarter turn: rotating
        // the whole frame draws it side-on to the wall without a second set of
        // geometry. The turned plate lands centred on the block's middle, which
        // is exactly where a pole beside it runs.
        if (style == StopMarkerBlock.Style.BLADE) {
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0f));
        }

        // Two passes on purpose: drawing text switches render layer, which
        // flushes the quad buffer, so a buffer reference must never be held
        // across a text draw.
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getDebugQuads());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        for (int i = 0; i < signs.size(); i++) {
            double top = stackTop - StopMarkerBlock.PLATE_SIZE * i;
            double bottom = top - StopMarkerBlock.PLATE_SIZE;
            // The plate itself: a solid box so it reads as metal from any angle.
            CanvasPainter.box(buffer, matrix,
                    local(6.0), (float) (bottom / 16.0), local(front),
                    local(10.0), (float) (top / 16.0), local(front + StopMarkerBlock.PLATE_DEPTH),
                    signs.get(i).color().background);
        }
        for (int i = 0; i < signs.size(); i++) {
            paintFace(matrices, vertexConsumers, signs.get(i),
                    stackTop - StopMarkerBlock.PLATE_SIZE * i, front);
        }
        // A blade is read from both sides — the plate hangs in the open, so a
        // blank back face would be half the sign missing.
        if (style == StopMarkerBlock.Style.BLADE) {
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            for (int i = 0; i < signs.size(); i++) {
                paintFace(matrices, vertexConsumers, signs.get(i),
                        stackTop - StopMarkerBlock.PLATE_SIZE * i, front);
            }
        }
        matrices.pop();
    }

    /** Model pixels to block-local coordinates (the block centre is the origin). */
    private static float local(double pixels) {
        return (float) (pixels / 16.0 - 0.5);
    }

    /** The legend, bolts and keyline on one plate's front face. */
    private void paintFace(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                           StopMarkerBlockEntity.Sign sign, double top, double front) {
        matrices.push();
        // Canvas origin at the viewer's top-left of the plate: negative x/y
        // scale, so translate to the plate's far edge first.
        matrices.translate(StopMarkerBlock.PLATE_SIZE / 32.0, top / 16.0, local(front) - 0.002);
        matrices.scale(-UNIT, -UNIT, UNIT);
        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);

        int bolt = darken(sign.color().background);
        for (float x : new float[]{2.5f, CANVAS - 2.5f}) {
            for (float y : new float[]{2.5f, CANVAS - 2.5f}) {
                painter.quad(x - 1.2f, y - 1.2f, x + 1.2f, y + 1.2f, -0.05f, bolt);
            }
        }
        if (sign.color() == StopMarkerBlockEntity.SignColor.YELLOW) {
            // The OPTO boards carry a black keyline just inside the edge.
            frame(painter, 3.0f, CANVAS - 3.0f, 1.0f, sign.color().text);
        }

        String text = sign.text().trim();
        if (!text.isEmpty()) {
            float width = Math.max(0.001f, painter.width(text, 1));
            float size = Math.min(MAX_TEXT_SIZE, TEXT_WIDTH_LIMIT / width);
            painter.textCentered(text, CANVAS / 2.0f, CANVAS / 2.0f - size / 2.0f, size, sign.color().text);
        }
        matrices.pop();
    }

    /** Four thin bars making a hollow rectangle. */
    private static void frame(CanvasPainter painter, float min, float max, float thickness, int argb) {
        painter.quad(min, min, max, min + thickness, -0.1f, argb);
        painter.quad(min, max - thickness, max, max, -0.1f, argb);
        painter.quad(min, min, min + thickness, max, -0.1f, argb);
        painter.quad(max - thickness, min, max, max, -0.1f, argb);
    }

    private static int darken(int argb) {
        int r = (argb >> 16 & 0xFF) * 5 / 8;
        int g = (argb >> 8 & 0xFF) * 5 / 8;
        int b = (argb & 0xFF) * 5 / 8;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    @Override
    public boolean rendersOutsideBoundingBox(StopMarkerBlockEntity blockEntity) {
        return false; // plates always stay inside their own block
    }
}
