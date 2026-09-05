package com.stationannouncer.mtr;

import com.stationannouncer.block.ConcreteFloorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.mtr.mod.block.PlatformHelper;

/**
 * Platform edge: a concrete floor block with the yellow tactile strip along
 * its {@link #TRACK_SIDE}, and - the reason it lives in the MTR package - a
 * block <b>trains open their doors against</b>.
 *
 * <p>MTR decides where doors open with a single test (bytecode-verified in
 * {@code RenderVehicleHelper.canOpenDoors}, 4.0.1): the block beside a car
 * door {@code instanceof PlatformHelper}. {@link PlatformHelper} is a public
 * interface carrying only static property holders, so implementing it is the
 * whole integration; nothing else in MTR references platform blocks.
 *
 * <p>The concrete body reuses the floor family's own weighted models. Which
 * ones is decided by {@link #SLAB} and {@link #CLEAN}, which the block
 * <b>adopts from the floor it touches</b> (placement, and again whenever a
 * neighbour changes), so one item lines up with every concrete floor - the
 * joint grid is recomputed from the world position with the adopted pitch,
 * exactly as {@link ConcreteFloorBlock} does. A run of edges copies along
 * itself, so only one block of the run needs to touch a floor.
 */
public class PlatformEdgeBlock extends Block implements PlatformHelper {
    /** The side the strip (and the track) is on. Named so it cannot be confused with MTR's FACING holder. */
    public static final DirectionProperty TRACK_SIDE = Properties.HORIZONTAL_FACING;
    public static final IntProperty SLAB = IntProperty.of("slab", 1, 4);
    public static final BooleanProperty CLEAN = BooleanProperty.of("clean");
    public static final BooleanProperty JOINT_NORTH = ConcreteFloorBlock.JOINT_NORTH;
    public static final BooleanProperty JOINT_WEST = ConcreteFloorBlock.JOINT_WEST;

    public PlatformEdgeBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(TRACK_SIDE, Direction.NORTH).with(SLAB, 3)
                .with(CLEAN, false).with(JOINT_NORTH, false).with(JOINT_WEST, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(TRACK_SIDE, SLAB, CLEAN, JOINT_NORTH, JOINT_WEST);
    }

    private static BlockState withJoints(BlockState state, BlockPos pos) {
        int n = state.get(SLAB);
        return state.with(JOINT_NORTH, Math.floorMod(pos.getZ(), n) == 0)
                .with(JOINT_WEST, Math.floorMod(pos.getX(), n) == 0);
    }

    /** Copy slab pitch + palette from a floor or edge neighbour; behind the strip first. */
    private static BlockState adopt(BlockState state, WorldAccess world, BlockPos pos) {
        Direction track = state.get(TRACK_SIDE);
        Direction[] order = {track.getOpposite(), track.rotateYClockwise(),
                track.rotateYCounterclockwise(), track};
        for (Direction d : order) {
            BlockState s = adoptFrom(state, world.getBlockState(pos.offset(d)));
            if (s != null) {
                return s;
            }
        }
        return state;
    }

    private static BlockState adoptFrom(BlockState state, BlockState neighbor) {
        if (neighbor.getBlock() instanceof ConcreteFloorBlock floor) {
            return state.with(SLAB, floor.slabBlocks()).with(CLEAN, floor.isClean());
        }
        if (neighbor.getBlock() instanceof PlatformEdgeBlock) {
            return state.with(SLAB, neighbor.get(SLAB)).with(CLEAN, neighbor.get(CLEAN));
        }
        return null;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = getDefaultState().with(TRACK_SIDE, context.getHorizontalPlayerFacing());
        return withJoints(adopt(state, context.getWorld(), context.getBlockPos()), context.getBlockPos());
    }

    /** /fill, /setblock, pastes: fix up joints (and adopt) the way the floors do. */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = withJoints(adopt(state, world, pos), pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction.getAxis().isHorizontal()) {
            BlockState adopted = adoptFrom(state, neighborState);
            if (adopted != null && adopted != state) {
                return withJoints(adopted, pos);
            }
        }
        return state;
    }
}
