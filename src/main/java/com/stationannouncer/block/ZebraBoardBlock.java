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
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * A conductor's zebra board — the striped board a train berths against.
 * Boards placed side by side along the run axis (same block, same facing)
 * merge into one continuous beam: only the free ends draw an end plate, and
 * on the hanging variant only the free ends carry the drop pole.
 *
 * <p>The wall and hanging variants are separate blocks and never merge with
 * each other — they sit at different depths in the block, so a mixed run
 * would step.</p>
 */
public class ZebraBoardBlock extends FacingDecorBlock {
    /** Whether another board of the same kind continues on that side (facing-relative). */
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");

    /** Outline per facing, and the same plus the end hanger pole. */
    private final VoxelShape[] plain = new VoxelShape[4];
    private final VoxelShape[] withPole = new VoxelShape[4];

    /**
     * @param northShape the beam outline for FACING=north
     * @param poleShape  the hanger pole added on end blocks, or null for a
     *                   variant that has none (it is centred, so it needs no rotation)
     */
    public ZebraBoardBlock(Settings settings, VoxelShape northShape, @Nullable VoxelShape poleShape) {
        super(settings, northShape);
        VoxelShape shape = northShape;
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            plain[facing.getHorizontal()] = shape;
            withPole[facing.getHorizontal()] = poleShape == null
                    ? shape : VoxelShapes.union(shape, poleShape).simplify();
            shape = rotateClockwise(shape);
        }
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

    /** Recomputes the merge flags from the neighbors along the run axis. */
    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        return state
                .with(LEFT, connectsTo(world, pos.offset(right.getOpposite()), facing))
                .with(RIGHT, connectsTo(world, pos.offset(right), facing));
    }

    private boolean connectsTo(WorldAccess world, BlockPos pos, Direction facing) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.isOf(this) && neighbor.get(FACING) == facing;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        boolean end = !state.get(LEFT) || !state.get(RIGHT);
        return (end ? withPole : plain)[state.get(FACING).getHorizontal()];
    }
}
