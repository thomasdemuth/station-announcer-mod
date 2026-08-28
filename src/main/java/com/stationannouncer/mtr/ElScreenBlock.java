package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
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
 * A merging run along the platform: the el windscreens and railings. Segments
 * with the same block and facing join left/right (zebra-board rule), and the
 * multipart draws each segment's LEFT frame post always plus a closing RIGHT
 * post only at the run's end — one shared post per joint, any run length.
 */
public class ElScreenBlock extends FacingDecorBlock {
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    /** Same screen directly above/below: panels stack into one tall wall —
     * the top rail draws only at the stack top, the kick at its foot. */
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");

    public ElScreenBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(LEFT, false).with(RIGHT, false)
                .with(UP, false).with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LEFT, RIGHT, UP, DOWN);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null
                : withConnections(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    private boolean joins(WorldAccess world, BlockPos pos, Direction facing) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.isOf(this) && neighbor.get(FACING) == facing;
    }

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        return state.with(RIGHT, joins(world, pos.offset(right), facing))
                .with(LEFT, joins(world, pos.offset(right.getOpposite()), facing))
                .with(UP, joins(world, pos.up(), facing))
                .with(DOWN, joins(world, pos.down(), facing));
    }
}
