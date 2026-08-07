package com.stationannouncer.block;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A (possibly multi-part) block whose block entity is a {@link PaDisplay}.
 * Lets the Speaker Link item route a click on any part of the block to the
 * part that actually holds the data/block entity.
 */
public interface PaDisplayBlock {
    /** Position of the data-holding part for a click at {@code pos}. */
    BlockPos paDataPos(World world, BlockPos pos);
}
