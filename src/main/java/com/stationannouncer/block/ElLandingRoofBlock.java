package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * Flat landing roof for stair landings and turn-backs: deck at y 20..23.2 and
 * frieze 8..20, which is exactly where an {@link ElStairRoofBlock}'s deck sits
 * at its cell edges - so it joins a stair roof at the FOOT of a flight in the
 * same row (the flight's roof continues off the landing roof) and at the TOP
 * of a flight from the cell one below (the landing sits over the flight's last
 * cell). Each side draws its frieze + eave unless a landing roof, or a stair
 * roof meeting it as described, is there. Assets: tools/gen_el2_stairs.py.
 */
public class ElLandingRoofBlock extends Block {
    public static final BooleanProperty EDGE_NORTH = BooleanProperty.of("edge_north");
    public static final BooleanProperty EDGE_EAST = BooleanProperty.of("edge_east");
    public static final BooleanProperty EDGE_SOUTH = BooleanProperty.of("edge_south");
    public static final BooleanProperty EDGE_WEST = BooleanProperty.of("edge_west");

    /** Deck only (y 18..24) inside a roof; the frieze band (from y 8) only where a side is an edge. */
    private static final VoxelShape DECK = Block.createCuboidShape(0, 18, 0, 16, 24, 16);
    private static final VoxelShape EDGED = Block.createCuboidShape(0, 8, 0, 16, 24, 16);

    public ElLandingRoofBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(EDGE_NORTH, true).with(EDGE_EAST, true)
                .with(EDGE_SOUTH, true).with(EDGE_WEST, true));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(EDGE_NORTH, EDGE_EAST, EDGE_SOUTH, EDGE_WEST);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        boolean edged = state.get(EDGE_NORTH) || state.get(EDGE_EAST) || state.get(EDGE_SOUTH) || state.get(EDGE_WEST);
        return edged ? EDGED : DECK;
    }

    public static BooleanProperty edge(Direction side) {
        return switch (side) {
            case EAST -> EDGE_EAST;
            case SOUTH -> EDGE_SOUTH;
            case WEST -> EDGE_WEST;
            default -> EDGE_NORTH;
        };
    }

    /** Something continues the roof across this side: another landing roof, or a stair roof meeting us at deck height. */
    private boolean joined(WorldAccess world, BlockPos pos, Direction side) {
        BlockState same = world.getBlockState(pos.offset(side));
        if (same.isOf(this)) {
            return true;
        }
        // foot of a flight rising away from us, in our row
        if (same.getBlock() instanceof ElStairRoofBlock && same.get(ElStairRoofBlock.FACING) == side) {
            return true;
        }
        // top of a flight arriving from below: its last cell is one down, ascending toward us
        BlockState below = world.getBlockState(pos.offset(side).down());
        return below.getBlock() instanceof ElStairRoofBlock
                && below.get(ElStairRoofBlock.FACING) == side.getOpposite();
    }

    private BlockState compute(BlockState state, WorldAccess world, BlockPos pos) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            state = state.with(edge(side), !joined(world, pos, side));
        }
        return state;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return compute(getDefaultState(), context.getWorld(), context.getBlockPos());
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = compute(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return compute(state, world, pos);
    }

    /** Stair roofs one below and beside us are not vanilla neighbours: they call this when they change. */
    public static void refreshAround(World world, BlockPos stairRoofPos, Direction facing) {
        for (BlockPos cell : new BlockPos[]{stairRoofPos.up().offset(facing), stairRoofPos.offset(facing.getOpposite())}) {
            BlockState state = world.getBlockState(cell);
            if (state.getBlock() instanceof ElLandingRoofBlock block) {
                BlockState fresh = block.compute(state, world, cell);
                if (fresh != state) {
                    world.setBlockState(cell, fresh, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }
}
