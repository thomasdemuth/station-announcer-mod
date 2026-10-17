package com.stationannouncer.atmosphere;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * Flattened cardboard boxes, 1..3 layers (at three a half-crushed open box
 * sits on the heap). Using cardboard on the pile adds a layer; turn and print
 * come from weighted blockstate variants. Low collision.
 */
public class TrashCardboardPileBlock extends Block {
    public static final int MAX_LAYERS = 3;
    public static final IntProperty LAYERS = IntProperty.of("layers", 1, MAX_LAYERS);
    private static final VoxelShape[] COLLISION = {
            Block.createCuboidShape(1, 0, 1, 15, 1, 15),
            Block.createCuboidShape(1, 0, 1, 15, 2, 15),
            VoxelShapes.union(Block.createCuboidShape(1, 0, 1, 15, 1.5, 15),
                    Block.createCuboidShape(4, 0, 4, 12, 5.5, 12)).simplify(),
    };
    private static final VoxelShape[] OUTLINE = {
            Block.createCuboidShape(1, 0, 1, 15, 1.5, 15),
            Block.createCuboidShape(1, 0, 1, 15, 2.5, 15),
            VoxelShapes.union(Block.createCuboidShape(1, 0, 1, 15, 1.5, 15),
                    Block.createCuboidShape(2, 0, 2, 14, 8, 14)).simplify(),
    };

    public TrashCardboardPileBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(LAYERS, 1));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LAYERS);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState existing = ctx.getWorld().getBlockState(ctx.getBlockPos());
        if (existing.isOf(this)) {
            return existing.with(LAYERS, Math.min(MAX_LAYERS, existing.get(LAYERS) + 1));
        }
        return getDefaultState();
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        if (!ctx.shouldCancelInteraction() && ctx.getStack().isOf(asItem()) && state.get(LAYERS) < MAX_LAYERS) {
            return true;
        }
        return super.canReplace(state, ctx);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return OUTLINE[state.get(LAYERS) - 1];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return COLLISION[state.get(LAYERS) - 1];
    }
}
