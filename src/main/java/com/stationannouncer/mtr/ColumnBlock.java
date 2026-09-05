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
 * The riveted iron platform column. Columns stacked vertically detect their
 * ends: the bottom block of a stack renders the diagonal base foot, the top
 * block renders the plate that plateaus into the ceiling (up/down properties
 * drive the multipart blockstate). Station-colored variants are tinted via a
 * client color provider, so the texture and rivets stay visible.
 */
public class ColumnBlock extends FacingDecorBlock {
    /** Another column continues directly above / below. */
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");

    public ColumnBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(UP, false).with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(UP, DOWN);
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

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        return state
                .with(UP, world.getBlockState(pos.up()).getBlock() instanceof ColumnBlock)
                .with(DOWN, connectsDown(world.getBlockState(pos.down())));
    }

    /** What counts as a continuation below (no foot drawn). Subclasses may widen it. */
    protected boolean connectsDown(BlockState below) {
        return below.getBlock() instanceof ColumnBlock;
    }
}
