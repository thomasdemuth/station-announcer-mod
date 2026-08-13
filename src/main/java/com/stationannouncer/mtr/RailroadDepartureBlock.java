package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The small railroad departure board: the portrait screen on a Penn Station
 * concourse wall listing the next trains out.
 *
 * <p>Geometrically identical to {@link RailroadPidsBlock}'s wall variant — the
 * same door-style two-block multiblock with the screen spanning 0.5–2.5 blocks
 * — so everything about placement, breaking, the outline and the brush is
 * inherited. It exists as its own class purely so it can carry its own block
 * entity type, because a type may only have one renderer and this board draws a
 * completely different screen: an editable title and a clock across the top,
 * then a departure per row with its route number, destination and colour, the
 * stopping pattern spelled out for the first two only, and the track held back
 * until the board announces it.</p>
 */
public class RailroadDepartureBlock extends RailroadPidsBlock {
    public RailroadDepartureBlock(Settings settings) {
        super(settings, false);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        // Still only the data half; the entity picks its own type from the block.
        return state.get(HALF) == DoubleBlockHalf.LOWER ? new RailroadPidsBlockEntity(pos, state) : null;
    }
}
