package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * El kit v2 deck course (tools/gen_el2_structure.py): two riveted stringers
 * along AXIS (the direction of travel) with either timber ties on top (the
 * open track deck - the MTR rail node goes on its top face) or a closed
 * riveted pan (the platform framing under a concrete floor). Walkable on
 * top, clearance beneath: collision is the top half only.
 */
public class ElDeckBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    private static final VoxelShape TOP_SLAB = createCuboidShape(0.0, 8.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape FULL = createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public ElDeckBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return FULL;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return TOP_SLAB;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(AXIS, context.getHorizontalPlayerFacing().getAxis());
    }

    /** The pan's cullface sides vanish against another cell of the SAME deck (glass rule). */
    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
        return stateFrom.getBlock() == state.getBlock() || super.isSideInvisible(state, stateFrom, direction);
    }
}
