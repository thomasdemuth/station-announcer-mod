package com.stationannouncer.mtr;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.mtr.mod.block.PlatformHelper;

/**
 * A platform edge that follows a CURVED track: the concrete platform with its
 * edge cut along a straight line through the block, and the yellow tactile
 * strip running along that cut — so a curved platform reads as one smooth
 * curve instead of a staircase of block edges.
 *
 * <p>Frame: authored NORTH = track side (model z = 0), rotated by
 * {@link #TRACK_SIDE}. The cut line runs from depth {@code a} at the block's
 * left end (model x = 0) to depth {@code b} at its right end (x = 16), depths
 * measured from the track face into the block. Stored as {@link #CUT_A} /
 * {@link #CUT_B} = depth/2 + 8 (0..20 → −16..24 px in 2 px steps; beyond 0..16 the
 * line lies outside the block at that end but still sets the cut's slope inside it). A negative
 * depth means the edge line lies in the next cell toward the track: this
 * block is then whole platform, carrying only the part of the 8 px tactile
 * strip that spills over the cell boundary. Four facings cover every slope
 * (a steeper line is shallow in the neighbouring facing). The Curved Platform
 * Creator computes all of this from the rail.</p>
 *
 * <p>Implements {@link PlatformHelper} like {@link PlatformEdgeBlock}, so MTR
 * trains open their doors against it. Drawn by the client's ShapeModel (JSON
 * cannot cut a block along an arbitrary line); collision follows the cut in
 * 2 px strips.</p>
 */
public class CurvedPlatformEdgeBlock extends Block implements PlatformHelper {
    public static final DirectionProperty TRACK_SIDE = Properties.HORIZONTAL_FACING;
    public static final IntProperty CUT_A = IntProperty.of("cut_a", 0, 20);
    public static final IntProperty CUT_B = IntProperty.of("cut_b", 0, 20);
    /** Width of the tactile strip behind the cut (px). */
    public static final float STRIP = 8f;
    private static final int STRIPS = 8;

    /** [facing][cut a][cut b]. */
    private final VoxelShape[][][] shapes = new VoxelShape[4][21][21];

    public CurvedPlatformEdgeBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(TRACK_SIDE, Direction.NORTH).with(CUT_A, 8).with(CUT_B, 8));
        for (Direction side : Direction.Type.HORIZONTAL) {
            for (int a = 0; a <= 20; a++) {
                for (int b = 0; b <= 20; b++) {
                    shapes[side.getHorizontal()][a][b] = buildShape(side, depthPx(a), depthPx(b));
                }
            }
        }
    }

    /** Stored cut value → depth in px from the track face (negative = in front of the block). */
    public static float depthPx(int cut) {
        return (cut - 8) * 2f;
    }

    /** Depth (px) → stored cut value, clamped. */
    public static int cutFor(float depthPx) {
        return Math.max(0, Math.min(20, Math.round(depthPx / 2f) + 8));
    }

    /** The body behind the cut, in 2 px strips (shared with {@link CurvedGapFillerBlock}). */
    static VoxelShape buildShape(Direction side, float da, float db) {
        VoxelShape shape = VoxelShapes.empty();
        for (int i = 0; i < STRIPS; i++) {
            float x0 = 16f * i / STRIPS;
            float x1 = 16f * (i + 1) / STRIPS;
            float mid = (x0 + x1) / 2f;
            float depth = Math.max(0f, da + (db - da) * mid / 16f);
            if (depth >= 15.99f) {
                continue;
            }
            shape = VoxelShapes.union(shape, GapFillerBlock.rotated(side, x0, 0, depth, x1, 16, 16));
        }
        return shape.isEmpty() ? VoxelShapes.cuboid(0.4, 0, 0.4, 0.6, 0.1, 0.6) : shape.simplify();
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(TRACK_SIDE, CUT_A, CUT_B);
    }

    /** By hand it places straight (flush with the block face), like a platform edge; the creator cuts it. */
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(TRACK_SIDE, context.getHorizontalPlayerFacing());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(TRACK_SIDE).getHorizontal()][state.get(CUT_A)][state.get(CUT_B)];
    }
}
