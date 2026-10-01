package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * What the shared gap-filler code (block entity, linking, brush screen,
 * renderer) needs from a filler block: the straight {@link GapFillerBlock} and
 * the {@link CurvedGapFillerBlock} that follows a curved edge.
 */
public interface GapFillerHost {
    GapFillerBlock.Style style();

    /** The side the track is on. */
    default Direction trackSide(BlockState state) {
        return state.get(GapFillerBlock.TRACK_SIDE);
    }

    /**
     * World x/z of the middle of the edge the plate leaves from — the block's
     * track face for a straight filler, the middle of the cut for a curved one.
     */
    double[] edgeCentre(BlockState state, BlockPos pos);

    /**
     * The plate slides straight out of the block's track face; a curved cut
     * meets that direction at an angle, so the plate must travel 1/cos further
     * than the gap it bridges. 1 for a straight filler.
     */
    default double travelPerGap(BlockState state) {
        return 1.0;
    }

    /** Reach (units of 2 px) kept in the blockstate (straight) or the block entity (curved). */
    default boolean reachInState() {
        return false;
    }
}
