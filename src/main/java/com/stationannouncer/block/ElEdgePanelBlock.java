package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * A horizontal panel that tiles into a surface of ANY size and shape (the el
 * mezzanine floor, the station-house ceiling): each cell works out which of
 * its four sides is an open edge - nothing of the same surface beside it and
 * nothing solid to butt against - and the blockstate hangs a fascia there
 * (plus a corner cap where two open sides meet). Placing by hand, /fill,
 * pastes and Axiom all come out framed, because the state is re-derived on
 * every neighbour change and when the block is added.
 */
public abstract class ElEdgePanelBlock extends Block {
    public static final BooleanProperty EDGE_NORTH = BooleanProperty.of("edge_north");
    public static final BooleanProperty EDGE_EAST = BooleanProperty.of("edge_east");
    public static final BooleanProperty EDGE_SOUTH = BooleanProperty.of("edge_south");
    public static final BooleanProperty EDGE_WEST = BooleanProperty.of("edge_west");

    protected ElEdgePanelBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(EDGE_NORTH, true).with(EDGE_EAST, true)
                .with(EDGE_SOUTH, true).with(EDGE_WEST, true));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(EDGE_NORTH, EDGE_EAST, EDGE_SOUTH, EDGE_WEST);
    }

    public static BooleanProperty edge(Direction side) {
        return switch (side) {
            case EAST -> EDGE_EAST;
            case SOUTH -> EDGE_SOUTH;
            case WEST -> EDGE_WEST;
            default -> EDGE_NORTH;
        };
    }

    /** Same surface (the plain and lit ceiling are one surface). */
    protected abstract boolean sameSurface(BlockState state);

    /** Something the surface frames INTO rather than ending at (girders, columns...). */
    protected boolean framesInto(BlockState state) {
        return false;
    }

    protected BlockState withEdges(BlockState state, WorldAccess world, BlockPos pos) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos at = pos.offset(side);
            BlockState other = world.getBlockState(at);
            boolean closed = sameSurface(other) || framesInto(other)
                    || other.isSideSolidFullSquare(world, at, side.getOpposite());
            state = state.with(edge(side), !closed);
        }
        return state;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withEdges(getDefaultState(), context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return direction.getAxis().isHorizontal() ? withEdges(state, world, pos) : state;
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.NORTH);
    }
}
