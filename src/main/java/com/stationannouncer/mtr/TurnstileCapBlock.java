package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Array end: the stainless end panel that closes the last lane, with a post
 * carrying the arch riser the neighbouring turnstile's arch lands on. Placed
 * in the cell on the last turnstile's lane side, facing the same way.
 */
public class TurnstileCapBlock extends TurnstileBaseBlock {
    private final VoxelShape[] lowerShape;
    private final VoxelShape[] upperShape;

    public TurnstileCapBlock(Settings settings) {
        super(settings);
        this.lowerShape = rotations(createCuboidShape(11, 0, 0, 16, 16, 16));
        this.upperShape = rotations(VoxelShapes.union(
                createCuboidShape(11, 0, 0, 16, 11, 4),
                createCuboidShape(12, 11, 0, 15, 14, 3)).simplify());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER ? rotated(upperShape, state) : rotated(lowerShape, state);
    }
}
