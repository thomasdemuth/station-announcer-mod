package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import com.stationannouncer.block.SelfSettle;
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
 * A barrier that runs in a line: the Type III road barricade, the NYPD
 * sawhorse and the water-filled plastic barrier. Neighbours of the same
 * block on the run axis join into one continuous barrier ({@code left} /
 * {@code right}, facing-relative like the zebra boards). A barrier turned
 * the other way round on the same axis still joins - all three are
 * double-sided.
 *
 * <p>The shared joint (the post / leg pair / interlock between two
 * barriers) is drawn by exactly one of the two: the block on the WORLD
 * negative side of it (−x or −z). That rule lives in the blockstate
 * (gen_construction_assets.py: left joint when facing is north|east, right
 * joint when facing is south|west), so the Java side only has to keep the
 * two flags right.
 */
public class ConstructionBarrierBlock extends FacingDecorBlock {
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");

    private final VoxelShape[] collision;

    /**
     * @param outlineNorth   the outline for FACING=north
     * @param collisionNorth the collision for FACING=north (may be taller than a block)
     */
    public ConstructionBarrierBlock(Settings settings, VoxelShape outlineNorth, VoxelShape collisionNorth) {
        super(settings, outlineNorth);
        this.collision = ConstructionProps.rotations(collisionNorth);
        setDefaultState(getDefaultState().with(LEFT, false).with(RIGHT, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LEFT, RIGHT);
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

    public BlockState withConnections(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = ConstructionProps.right(facing);
        return state
                .with(LEFT, joins(world, pos.offset(right.getOpposite()), facing))
                .with(RIGHT, joins(world, pos.offset(right), facing));
    }

    private boolean joins(BlockView world, BlockPos pos, Direction facing) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.isOf(this) && neighbor.get(FACING).getAxis() == facing.getAxis();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collision[state.get(FACING).getHorizontal()];
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.DOWN);
    }
}
