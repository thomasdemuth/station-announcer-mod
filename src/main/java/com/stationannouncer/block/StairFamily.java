package com.stationannouncer.block;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * Placement rule shared by everything that ascends with a staircase (treads,
 * stringers, side courses, stair roofs, sloped handrails): THE NEIGHBOURS
 * DECIDE the ascent, the player's look direction is only the fallback, and
 * holding sneak forces the look direction (to start a run the other way on
 * purpose). Clicking a piece in from the side of a flight used to turn it 90
 * degrees, because the look direction was taken as the ascent.
 */
public final class StairFamily {
    private StairFamily() {
    }

    /** The ascent a stair-family block declares, or null when the state is not one. */
    @Nullable
    public static Direction ascentOf(BlockState state) {
        if (state.getBlock() instanceof SubwayStairBlock) {
            return state.get(SubwayStairBlock.FACING);
        }
        if (state.getBlock() instanceof StairDividerBlock) {
            return state.get(StairDividerBlock.FACING);
        }
        if (state.getBlock() instanceof ElStairSideBlock) {
            return state.get(ElStairSideBlock.FACING);
        }
        if (state.getBlock() instanceof ElStairRoofBlock) {
            return state.get(ElStairRoofBlock.FACING);
        }
        if (state.getBlock() instanceof ElStairUpperBlock) {
            return state.get(ElStairUpperBlock.FACING);
        }
        if (state.getBlock() instanceof HandrailBlock && state.get(HandrailBlock.VARIANT).sloped()) {
            return state.get(HandrailBlock.FACING);
        }
        return null;
    }

    /**
     * The ascent the flight around {@code pos} already has, or null when
     * nothing of the family is near. Order: the run's own diagonal cells (one
     * down + back, one up + forward - they must agree with their own facing),
     * then pieces BESIDE us (a piece ahead or behind at the same height says
     * nothing about a slope), then the column (below, above).
     */
    @Nullable
    public static Direction ascentNear(BlockView world, BlockPos pos) {
        for (Direction f : Direction.Type.HORIZONTAL) {
            if (ascentOf(world.getBlockState(pos.down().offset(f.getOpposite()))) == f
                    || ascentOf(world.getBlockState(pos.up().offset(f))) == f) {
                return f;
            }
        }
        for (Direction d : Direction.Type.HORIZONTAL) {
            Direction f = ascentOf(world.getBlockState(pos.offset(d)));
            if (f != null && f.getAxis() != d.getAxis()) {
                return f;
            }
        }
        for (BlockPos cell : new BlockPos[]{pos.down(), pos.up()}) {
            Direction f = ascentOf(world.getBlockState(cell));
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** How far up a wall looks for a ceiling to fill up to. */
    private static final int CEILING_REACH = 8;

    /**
     * A ceiling over this column (triangle walls): the first thing above that
     * is neither air nor one of the family's own wall cells shows a full face
     * downward.
     */
    public static boolean underCeiling(BlockView world, BlockPos pos) {
        for (int i = 1; i <= CEILING_REACH; i++) {
            BlockPos cell = pos.up(i);
            BlockState state = world.getBlockState(cell);
            if (state.isAir() || state.getBlock() instanceof ElStairSideBlock
                    || state.getBlock() instanceof ElStairUpperBlock) {
                continue;
            }
            return state.isSideSolidFullSquare(world, cell, Direction.DOWN);
        }
        return false;
    }

    /** Posts stand on every other cell of a run: parity along the ascent axis, so stacked cells line up. */
    public static boolean postCell(BlockPos pos, Direction ascent) {
        return Math.floorMod(ascent.getAxis() == Direction.Axis.Z ? pos.getZ() : pos.getX(), 2) == 0;
    }

    /** Sneak = the player insists on the look direction. */
    public static boolean forced(ItemPlacementContext context) {
        return context.getPlayer() != null && context.getPlayer().isSneaking();
    }

    /** The ascent for a new piece: neighbours first, look direction as fallback or when forced. */
    public static Direction placementAscent(ItemPlacementContext context) {
        Direction look = context.getHorizontalPlayerFacing();
        if (forced(context)) {
            return look;
        }
        Direction near = ascentNear(context.getWorld(), context.getBlockPos());
        return near != null ? near : look;
    }
}
