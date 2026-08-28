package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The 45° street-stair pieces (side screen, stair canopy): FACING is the
 * ASCENT direction, taken from the player's look like the handrail slopes —
 * each block chains one up per block forward (centre y = 16 − z), so a run
 * follows any stair length.
 */
public class ElSlopeBlock extends FacingDecorBlock {
    public ElSlopeBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null
                : state.with(FACING, context.getHorizontalPlayerFacing());
    }
}
