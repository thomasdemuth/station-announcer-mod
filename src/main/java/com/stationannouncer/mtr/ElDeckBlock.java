package com.stationannouncer.mtr;

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
 * The elevated structure's open tie deck: ties and stringers at the top of
 * the block, daylight between them from the street below. AXIS is the
 * direction of travel (the stringers); ties run across it. Collision is the
 * top half only, so the deck is walkable on top with clearance beneath —
 * nobody bumps an invisible wall walking under the el.
 */
public class ElDeckBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    private static final VoxelShape TOP_SLAB = createCuboidShape(0.0, 8.0, 0.0, 16.0, 16.0, 16.0);
    /** The stringers run the whole height of the block — so does the outline. */
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

    /**
     * Decks tile in both directions and are not opaque cubes, so their
     * boundary faces would coplanar-fight a neighbouring deck's — the glass
     * pattern: faces carry cullface and vanish against another deck.
     * The neighbour must be the SAME deck block: the plate deck's slab faces
     * were being culled against an open TIE deck too, which left a hole in
     * the plate wherever the two met.
     */
    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
        return stateFrom.getBlock() == state.getBlock()
                || super.isSideInvisible(state, stateFrom, direction);
    }
}
