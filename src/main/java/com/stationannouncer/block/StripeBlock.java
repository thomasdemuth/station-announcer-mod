package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * A band that stands slightly shorter than the block it fills, while still
 * taking up that block entirely.
 *
 * <p>The gap at the top is what makes it read as a painted band set into a
 * wall rather than as another course of it — but the block still occupies its
 * whole space, so a run of these keeps the wall on grid and anything standing
 * on one is at full height rather than dropping a pixel into it.</p>
 */
public class StripeBlock extends Block {
    private final VoxelShape outline;

    /**
     * @param height how tall the visible band is, in model pixels
     */
    public StripeBlock(Settings settings, double height) {
        super(settings);
        this.outline = createCuboidShape(0.0, 0.0, 0.0, 16.0, height, 16.0);
    }

    /** The highlight follows what you can see. */
    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outline;
    }

    /** Collision does not: the block fills its space like any other wall block. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }
}
