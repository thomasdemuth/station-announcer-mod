package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Connecting blocks work out their own shape when they are PLACED BY HAND
 * (getPlacementState), but /fill, /setblock, structure pastes, Axiom and the
 * creator tools never call that - and the last cell of a filled run never
 * gets a neighbour update either. Calling this from onBlockAdded makes every
 * such block settle itself through its own neighbour-update logic, so a
 * platform, wall run or stack of any size built with any tool comes out
 * connected.
 */
public final class SelfSettle {
    private SelfSettle() {
    }

    /** Re-derives the state as if the neighbour in {@code probe} had just changed. */
    public static void settle(BlockState state, World world, BlockPos pos, Direction probe) {
        if (world.isClient) {
            return;
        }
        BlockPos other = pos.offset(probe);
        BlockState wanted = state.getBlock().getStateForNeighborUpdate(state, probe, world.getBlockState(other),
                world, pos, other);
        if (wanted != state && wanted.isOf(state.getBlock())) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }
}
