package com.stationannouncer.block;

import com.stationannouncer.ModContent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The subway entrance railing: 1.5 blocks tall, green baluster panels with a
 * serrated top rail on a dark concrete curb. Connects like a fence; chunky
 * paneled posts appear automatically at ends, corners, junctions, standalone
 * blocks — and wherever a globe lamp pole sits on top, so lamps grow out of
 * a proper post like the real entrances.
 */
public class RailingBlock extends Block {
    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty WEST = Properties.WEST;
    /** Render the chunky post at this block (ends/corners/junctions/lamp base). */
    public static final BooleanProperty POST = BooleanProperty.of("post");

    /** Outline shapes indexed by n|e|s|w|post bits. */
    private final VoxelShape[] shapes = new VoxelShape[32];

    public RailingBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState()
                .with(NORTH, false).with(EAST, false).with(SOUTH, false).with(WEST, false)
                .with(POST, true));
        VoxelShape centerThin = Block.createCuboidShape(5.0, 0.0, 5.0, 11.0, 24.0, 11.0);
        VoxelShape centerPost = Block.createCuboidShape(3.0, 0.0, 3.0, 13.0, 24.0, 13.0);
        VoxelShape armNorth = Block.createCuboidShape(6.0, 0.0, 0.0, 10.0, 24.0, 8.0);
        VoxelShape armEast = Block.createCuboidShape(8.0, 0.0, 6.0, 16.0, 24.0, 10.0);
        VoxelShape armSouth = Block.createCuboidShape(6.0, 0.0, 8.0, 10.0, 24.0, 16.0);
        VoxelShape armWest = Block.createCuboidShape(0.0, 0.0, 6.0, 8.0, 24.0, 10.0);
        for (int bits = 0; bits < 32; bits++) {
            VoxelShape shape = (bits & 16) != 0 ? centerPost : centerThin;
            if ((bits & 1) != 0) shape = VoxelShapes.union(shape, armNorth);
            if ((bits & 2) != 0) shape = VoxelShapes.union(shape, armEast);
            if ((bits & 4) != 0) shape = VoxelShapes.union(shape, armSouth);
            if ((bits & 8) != 0) shape = VoxelShapes.union(shape, armWest);
            shapes[bits] = shape;
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, POST);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withConnections(getDefaultState(), context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        boolean north = connectsTo(world.getBlockState(pos.north()));
        boolean east = connectsTo(world.getBlockState(pos.east()));
        boolean south = connectsTo(world.getBlockState(pos.south()));
        boolean west = connectsTo(world.getBlockState(pos.west()));
        boolean straight = (north && south && !east && !west) || (east && west && !north && !south);
        boolean post = !straight || lampAbove(world, pos);
        return state.with(NORTH, north).with(EAST, east).with(SOUTH, south).with(WEST, west).with(POST, post);
    }

    protected boolean connectsTo(BlockState neighbor) {
        return neighbor.getBlock() instanceof RailingBlock;
    }

    /** A globe lamp (or its pole) directly above turns this block into the lamp's base post. */
    private static boolean lampAbove(WorldAccess world, BlockPos pos) {
        Block above = world.getBlockState(pos.up()).getBlock();
        return above == ModContent.GLOBE_LAMP_POLE
                || above == ModContent.GLOBE_LAMP_GREEN
                || above == ModContent.GLOBE_LAMP_RED;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int bits = (state.get(NORTH) ? 1 : 0) | (state.get(EAST) ? 2 : 0)
                | (state.get(SOUTH) ? 4 : 0) | (state.get(WEST) ? 8 : 0)
                | (state.get(POST) ? 16 : 0);
        return shapes[bits];
    }
}
