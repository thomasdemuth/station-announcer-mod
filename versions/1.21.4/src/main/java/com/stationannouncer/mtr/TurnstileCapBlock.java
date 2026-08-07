package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * The solid end housing that closes off a turnstile row (and terminates the
 * overhead tubing). Purely structural — the lanes themselves are
 * {@link TurnstileBlock}s.
 */
public class TurnstileCapBlock extends TurnstileBaseBlock {
    private final VoxelShape[] lowerShape;
    private final VoxelShape[] upperShape;

    public TurnstileCapBlock(Settings settings) {
        super(settings);
        this.lowerShape = rotations(createCuboidShape(0.0, 0.0, 1.0, 8.0, 16.0, 15.0));
        this.upperShape = rotations(createCuboidShape(0.0, 0.0, 2.0, 8.0, 8.0, 14.0));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER
                ? rotated(upperShape, state)
                : rotated(lowerShape, state);
    }
}
