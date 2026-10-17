package com.stationannouncer.atmosphere;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * Floor litter: 1..4 small pieces of rubbish in one cell (sea-pickle style:
 * using litter on litter adds a piece). Which pieces, where in the cell and
 * which way round is chosen by the weighted multipart blockstate, i.e. by
 * position, so nothing about it is stored. No collision, breaks instantly,
 * and anything else placed here simply replaces it (like grass or snow).
 * Needs a solid top face below and pops when that goes.
 */
public class LitterBlock extends Block {
    public static final int MAX_COUNT = 4;
    public static final IntProperty COUNT = IntProperty.of("count", 1, MAX_COUNT);
    private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 2, 15);

    public LitterBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(COUNT, 1));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(COUNT);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState existing = ctx.getWorld().getBlockState(ctx.getBlockPos());
        if (existing.isOf(this)) {
            return existing.with(COUNT, Math.min(MAX_COUNT, existing.get(COUNT) + 1));
        }
        return getDefaultState();
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        if (!ctx.shouldCancelInteraction() && ctx.getStack().isOf(asItem()) && state.get(COUNT) < MAX_COUNT) {
            return true;
        }
        return super.canReplace(state, ctx);
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return Block.sideCoversSmallSquare(world, pos.down(), Direction.UP);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canPlaceAt(world, pos)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }
}
