package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

/** El windscreen wall course (panel / wired glass / name band): an {@link EdgeRunBlock} of the wall family. */
public class ElWallBlock extends EdgeRunBlock {
    public ElWallBlock(Settings settings) {
        super(settings, Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 2.4));
    }

    public static boolean isElWall(BlockState state) {
        return state.getBlock() instanceof ElWallBlock;
    }

    @Override
    protected boolean sameFamily(BlockState state) {
        return isElWall(state);
    }
}
