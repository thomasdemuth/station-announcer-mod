package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;

/**
 * El platform picket railing: an {@link EdgeRunBlock} at the block edge like
 * the walls (round 4, Thomas: railings at the edge, account for corners).
 * {@link #LAMP} = an el lamp pole stands on this block, so a mid-run post is
 * drawn under it and the lamp reads as the post continuing upward.
 */
public class ElRailingBlock extends EdgeRunBlock {
    public static final BooleanProperty LAMP = BooleanProperty.of("lamp");

    public ElRailingBlock(Settings settings) {
        super(settings, Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 21.0, 2.4));
        setDefaultState(getDefaultState().with(LAMP, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LAMP);
    }

    public static boolean isElRailing(BlockState state) {
        return state.getBlock() instanceof ElRailingBlock;
    }

    @Override
    protected boolean sameFamily(BlockState state) {
        return isElRailing(state);
    }

    @Override
    protected BlockState withExtras(BlockState state, WorldAccess world, BlockPos pos) {
        return state.with(LAMP, world.getBlockState(pos.up()).getBlock()
                instanceof com.stationannouncer.mtr.ElLampPoleBlock);
    }
}
