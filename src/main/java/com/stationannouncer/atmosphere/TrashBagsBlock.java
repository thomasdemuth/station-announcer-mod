package com.stationannouncer.atmosphere;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * A heap of 1..3 black garbage bags with tied tops. Using bags on the heap
 * adds one (sea-pickle style); the heap's look and turn come from weighted
 * blockstate variants. Collision follows the heap height; the loot table
 * gives back one bag per bag.
 */
public class TrashBagsBlock extends Block {
    public static final int MAX_BAGS = 3;
    public static final IntProperty BAGS = IntProperty.of("bags", 1, MAX_BAGS);
    private static final VoxelShape[] SHAPES = {
            Block.createCuboidShape(3, 0, 3, 13, 9, 13),
            Block.createCuboidShape(1, 0, 1, 15, 9, 15),
            Block.createCuboidShape(0.5, 0, 0.5, 15.5, 14, 15.5),
    };

    public TrashBagsBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(BAGS, 1));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(BAGS);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState existing = ctx.getWorld().getBlockState(ctx.getBlockPos());
        if (existing.isOf(this)) {
            return existing.with(BAGS, Math.min(MAX_BAGS, existing.get(BAGS) + 1));
        }
        return getDefaultState();
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        if (!ctx.shouldCancelInteraction() && ctx.getStack().isOf(asItem()) && state.get(BAGS) < MAX_BAGS) {
            return true;
        }
        return super.canReplace(state, ctx);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(BAGS) - 1];
    }
}
