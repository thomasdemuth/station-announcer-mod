package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.TileTabletBlock;
import com.stationannouncer.mtr.TileTabletBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.mtr.core.data.Station;

/**
 * Draws the black name tablet on a tile wall: one letter per tile, white on
 * black, the way a platform wall spells its station out at eye height.
 *
 * <p>The tablet is not a bar laid over the wall — it is drawn as INDIVIDUAL
 * BLACK TILES replacing white ones. Each letter gets a tile face of its own,
 * inset by the same grout gap the wall texture uses, so the wall's grout runs
 * between the letters and the name reads as tilework rather than as a sign
 * hung on top of it.</p>
 *
 * <p>The word is centred on the block and snapped to the tile grid, free to
 * overflow past the block's own edges — the same thing the mosaic band does —
 * so one block placed at the middle of where you want the name is enough.</p>
 */
@Environment(EnvType.CLIENT)
public class TileTabletRenderer implements BlockEntityRenderer<TileTabletBlockEntity> {
    /** Canvas units per block, as everywhere else in this mod. */
    private static final float UNIT = 1.0f / 64.0f;

    /** 64 canvas units to a block, four tiles to a block. */
    private static final float TILE = 64.0f / TileTabletBlock.ROWS;

    private static final int TABLET_BLACK = 0xFF0B0B0D;
    private static final int TEXT_WHITE = 0xFFF4F4F0;

    /**
     * The grout gap around each tile face, in canvas units. The wall texture is
     * 32 px to a block with a 1 px line, and 64 canvas units to a block, so one
     * texture pixel is two canvas units — this is that line, exactly.
     */
    private static final float GROUT = 2.0f;

    /** The black face of one tile, once the grout is taken off it. */
    private static final float FACE = TILE - GROUT;

    /** The gap left around a letter's INK, the same on all four sides. */
    private static final float MARGIN = 1.5f;

    /**
     * Vanilla draws a glyph in an 8-unit box whose top 7 units are the ink of
     * an upper-case letter — the eighth is the descender space under the
     * baseline. Sizing by the box rather than by the ink is what left the
     * letters small and sitting high in their tiles, so the type size is
     * derived from the cap height wanted, not the other way round.
     */
    private static final float CAP_RATIO = 7.0f / 8.0f;

    private static final float CAP_HEIGHT = FACE - 2 * MARGIN;
    private static final float LETTER_SIZE = CAP_HEIGHT / CAP_RATIO;

    /**
     * A vanilla capital is 5 ink columns by 7 rows, so at one size it cannot
     * have the same margin on all four sides of a square tile. Widening the
     * whole alphabet by that very ratio — 7/5 — makes a standard capital
     * square, so its side margins come out equal to its top and bottom.
     *
     * <p>One factor for every letter, not one per letter: stretching each
     * glyph to fill on its own would blow an I up into a bar. Narrow glyphs
     * stay narrow and simply keep a wider margin, which is what type does.</p>
     */
    private static final float STRETCH = 7.0f / 5.0f;

    /** Ink columns in a standard capital, out of the glyph's 8-unit box. */
    private static final float CAP_INK_COLUMNS = 5.0f;

    /** The tablet stands this far off the wall so it never z-fights the tile face. */
    private static final float STANDOFF = 0.002f;

    @Override
    public void render(TileTabletBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        String text = tabletText(entity);
        if (text.isEmpty()) {
            return;
        }
        Direction facing = entity.getCachedState().get(TileTabletBlock.FACING);
        int row = entity.getCachedState().get(TileTabletBlock.ROW);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        // Canvas origin at the block's top-left as the viewer sees it; the face
        // is the -Z side of the block once rotated.
        matrices.translate(0.5, 1.0, -0.5 - STANDOFF);
        matrices.scale(-UNIT, -UNIT, UNIT);
        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);

        // One tile per letter, and the word snapped to the tile grid so its
        // letters sit on tiles rather than across grout lines.
        float wordWidth = text.length() * TILE;
        float left = Math.round((64.0f - wordWidth) / 2.0f / TILE) * TILE;
        float top = row * TILE;

        for (int i = 0; i < text.length(); i++) {
            // One black tile per letter, inset by the grout so the wall's own
            // grid shows between them.
            float tileLeft = left + i * TILE;
            painter.quad(tileLeft + GROUT, top + GROUT, tileLeft + TILE, top + TILE, 0.0f, TABLET_BLACK);

            // Centre the letter's INK, not its type box: a glyph's measured
            // width includes the blank column that separates it from the next
            // one, and centring on that would push every letter a little left.
            String letter = String.valueOf(text.charAt(i));
            float ink = (painter.width(letter, LETTER_SIZE) - LETTER_SIZE / 8.0f) * STRETCH;
            matrices.push();
            // Widen about the letter's own left edge, then place that edge so
            // the stretched ink sits centred in the tile.
            matrices.translate(tileLeft + GROUT + (FACE - ink) / 2.0f, top + GROUT + MARGIN, 0.0f);
            matrices.scale(STRETCH, 1.0f, 1.0f);
            painter.text(letter, 0, 0, LETTER_SIZE, TEXT_WHITE);
            matrices.pop();
        }
        matrices.pop();
    }

    /**
     * One-entry memo for the upper-casing. A wall of tablets all spell the same
     * station, and this runs once per tablet per frame — the same reason the
     * mosaic and column boards memoize theirs.
     */
    private static String lastInput;
    private static String lastUpper = "";

    private static String upperCase(String name) {
        if (!name.equals(lastInput)) {
            lastInput = name;
            lastUpper = name.toUpperCase();
        }
        return lastUpper;
    }

    /** The typed text, else the station this wall stands in, upper-cased as the real tablets are. */
    private static String tabletText(TileTabletBlockEntity entity) {
        String custom = entity.getText();
        if (!custom.isEmpty()) {
            return upperCase(custom);
        }
        Station station = MtrDataCache.station(entity.getPos());
        return station == null ? "" : upperCase(RailroadRouteData.firstLang(station.getName()));
    }

    @Override
    public boolean rendersOutsideBoundingBox(TileTabletBlockEntity blockEntity) {
        return true; // a name wider than a metre spills past its own block
    }
}
