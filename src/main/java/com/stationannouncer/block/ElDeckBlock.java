package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
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
    /** Still named "axis" with values x/z (plus the diagonals xz/zx) so old placements load. */
    public static final EnumProperty<ElRun> AXIS = EnumProperty.of("axis", ElRun.class);

    private static final VoxelShape TOP_SLAB = createCuboidShape(0.0, 8.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape FULL = createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    /**
     * A diagonal cell's model is a 22.6 px square turned 45 degrees, so its
     * walking surface is a diamond reaching 8 px into the four neighbours -
     * the cells a diagonal ribbon skips. Collision follows it as a stepped
     * diamond (vanilla looks one block past an entity's box for shapes that
     * overhang, the fence rule).
     */
    private static final VoxelShape DIAMOND_TOP = net.minecraft.util.shape.VoxelShapes.union(
            createCuboidShape(-6.0, 8.0, 6.0, 22.0, 16.0, 10.0),
            createCuboidShape(-2.0, 8.0, 2.0, 18.0, 16.0, 14.0),
            createCuboidShape(2.0, 8.0, -2.0, 14.0, 16.0, 18.0),
            createCuboidShape(6.0, 8.0, -6.0, 10.0, 16.0, 22.0)).simplify();
    private static final VoxelShape DIAMOND_FULL = net.minecraft.util.shape.VoxelShapes.union(
            createCuboidShape(-6.0, 0.0, 6.0, 22.0, 16.0, 10.0),
            createCuboidShape(-2.0, 0.0, 2.0, 18.0, 16.0, 14.0),
            createCuboidShape(2.0, 0.0, -2.0, 14.0, 16.0, 18.0),
            createCuboidShape(6.0, 0.0, -6.0, 10.0, 16.0, 22.0)).simplify();

    public ElDeckBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, ElRun.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(AXIS).diagonal() ? DIAMOND_FULL : FULL;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(AXIS).diagonal() ? DIAMOND_TOP : TOP_SLAB;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(AXIS, ElRun.fromYaw(context.getPlayerYaw()));
    }

    /** The pan's cullface sides vanish against another cell of the SAME deck (glass rule). */
    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
        return stateFrom.getBlock() == state.getBlock() || super.isSideInvisible(state, stateFrom, direction);
    }
}
