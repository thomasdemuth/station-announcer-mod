package com.stationannouncer.client.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.ElDeckBlock;
import com.stationannouncer.block.ElGirderBlock;
import com.stationannouncer.block.ElRun;
import com.stationannouncer.mtr.ColumnBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.RailShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;
import java.util.List;

/**
 * The 3D preview in the creator settings screens: a miniature of what one
 * bent spacing of the structure will look like, drawn from the real block
 * states with the vanilla block renderer (so any model change shows up here
 * too), turned slowly and draggable.
 */
@Environment(EnvType.CLIENT)
public final class CreatorPreview {
    public record Cell(BlockPos pos, BlockState state) {
    }

    private CreatorPreview() {
    }

    /** One span of the el structure: deck ribbon, one bent in the middle. */
    public static List<Cell> structure(int width, int spacing, boolean lattice, boolean girder) {
        List<Cell> cells = new ArrayList<>();
        int edge = width / 2;
        int length = Math.max(4, spacing);
        int bentX = length / 2;
        int columnY = 3;
        BlockState deck = ModContent.EL_TRACK_DECK.getDefaultState().with(ElDeckBlock.AXIS, ElRun.X);
        for (int x = 0; x < length; x++) {
            for (int z = -edge; z <= edge; z++) {
                cells.add(new Cell(new BlockPos(x, columnY + 1, z), deck));
            }
        }
        net.minecraft.block.Block columnBlock = lattice ? ModContent.EL_STREET_COLUMN_LATTICE : ModContent.EL_STREET_COLUMN;
        int[] columnZ = edge == 0 ? new int[]{0} : new int[]{-edge, edge};
        for (int z : columnZ) {
            for (int y = 0; y < columnY; y++) {
                cells.add(new Cell(new BlockPos(bentX, y, z), columnBlock.getDefaultState()
                        .with(ColumnBlock.UP, y < columnY - 1).with(ColumnBlock.DOWN, y > 0)));
            }
        }
        if (girder && edge > 0) {
            for (int z = -edge; z <= edge; z++) {
                boolean braced = Math.abs(z) == edge;
                boolean neg = !braced && Math.abs(z - 1) == edge;   // column toward -z
                boolean pos = !braced && Math.abs(z + 1) == edge;   // column toward +z
                cells.add(new Cell(new BlockPos(bentX, columnY, z), ModContent.EL_GIRDER_PLATE.getDefaultState()
                        .with(ElGirderBlock.AXIS, ElRun.Z).with(ElGirderBlock.BRACED, braced)
                        .with(ElGirderBlock.BRACE_NEG, neg).with(ElGirderBlock.BRACE_POS, pos)));
            }
        }
        return cells;
    }

    /** One span of the pillar creator: material columns under a rail line. */
    public static List<Cell> pillars(int width, int spacing, BlockState material) {
        List<Cell> cells = new ArrayList<>();
        int length = Math.max(4, spacing);
        int railY = 4;
        BlockState rail = Blocks.RAIL.getDefaultState().with(net.minecraft.block.RailBlock.SHAPE, RailShape.EAST_WEST);
        BlockState bed = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        for (int x = 0; x < length; x++) {
            cells.add(new Cell(new BlockPos(x, railY - 1, 0), bed));
            cells.add(new Cell(new BlockPos(x, railY, 0), rail));
        }
        int edge = width / 2;
        int[] columnZ = edge == 0 ? new int[]{0} : new int[]{-edge, edge};
        int pillarX = length / 2;
        for (int z : columnZ) {
            for (int y = 0; y < railY - 1; y++) {
                cells.add(new Cell(new BlockPos(pillarX, y, z), material));
            }
        }
        return cells;
    }

    /**
     * Draws the scene inside the given panel, isometric-ish, scaled to fit.
     * {@code yaw} is the turntable angle in degrees.
     */
    public static void render(DrawContext context, int x0, int y0, int w, int h, List<Cell> cells, float yaw) {
        if (cells.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minX = Math.min(minX, c.pos.getX()); maxX = Math.max(maxX, c.pos.getX());
            minY = Math.min(minY, c.pos.getY()); maxY = Math.max(maxY, c.pos.getY());
            minZ = Math.min(minZ, c.pos.getZ()); maxZ = Math.max(maxZ, c.pos.getZ());
        }
        float cx = (minX + maxX + 1) / 2f;
        float cy = (minY + maxY + 1) / 2f;
        float cz = (minZ + maxZ + 1) / 2f;
        float extent = Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
        float scale = Math.min(w, h) / (extent * 1.55f);

        context.enableScissor(x0, y0, x0 + w, y0 + h);
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x0 + w / 2f, y0 + h / 2f, 300);
        matrices.scale(scale, -scale, scale);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(28));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.translate(-cx, -cy, -cz);
        DiffuseLighting.enableGuiDepthLighting();
        BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();
        for (Cell c : cells) {
            matrices.push();
            matrices.translate(c.pos.getX(), c.pos.getY(), c.pos.getZ());
            brm.renderBlockAsEntity(c.state, matrices, context.getVertexConsumers(),
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }
        context.draw();
        DiffuseLighting.disableGuiDepthLighting();
        matrices.pop();
        context.disableScissor();
    }
}
