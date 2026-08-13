package com.stationannouncer.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;

/**
 * Anything that forms part of a fare-control run: the dividing walls and the
 * emergency exit door.
 *
 * <p>It exists so the two can see each other across the package split (the door
 * lives in the MTR package because it charges fares, the walls do not need MTR
 * at all) and so a run of walls meeting a door shares ONE post at the joint
 * rather than standing two side by side.</p>
 */
public interface GateSection {
    /** Every section carries a horizontal facing under this name. */
    static boolean joins(BlockState state, Direction facing) {
        if (!(state.getBlock() instanceof GateSection)) {
            return false;
        }
        Direction other = state.contains(GateWallBlock.FACING)
                ? state.get(GateWallBlock.FACING)
                : null;
        return other == facing;
    }
}
