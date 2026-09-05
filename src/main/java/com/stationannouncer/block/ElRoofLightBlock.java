package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * Canopy light strip: hung under a roof cell, runs along FACING's axis and
 * joins the next light on that axis ({@link #FRONT} toward facing,
 * {@link #BACK} away) into one continuous fixture - end caps only at the
 * ends of a run.
 */
public class ElRoofLightBlock extends FacingDecorBlock {
    public static final BooleanProperty FRONT = BooleanProperty.of("front");
    public static final BooleanProperty BACK = BooleanProperty.of("back");

    public ElRoofLightBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(FRONT, false).with(BACK, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(FRONT, BACK);
    }

    private boolean joins(WorldAccess world, BlockPos pos, Direction.Axis axis) {
        BlockState other = world.getBlockState(pos);
        return other.isOf(this) && other.get(FACING).getAxis() == axis;
    }

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        return state.with(FRONT, joins(world, pos.offset(facing), facing.getAxis()))
                .with(BACK, joins(world, pos.offset(facing.getOpposite()), facing.getAxis()));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null : withConnections(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }
}
