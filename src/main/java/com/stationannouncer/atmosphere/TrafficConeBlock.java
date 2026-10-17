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
 * An orange PVC traffic cone. Clicking a cone with another cone stacks it
 * (sea-pickle style): {@code count} 1..3 cones nested in one cell, each one
 * 2.5 px higher than the one below, the way a stack of cones sits on a
 * work truck. Breaking drops every cone in the stack.
 */
public class TrafficConeBlock extends Block {
    public static final IntProperty COUNT = IntProperty.of("count", 1, 3);
    /** How much higher each nested cone sits, in model pixels (mirrored in gen_construction_assets.py). */
    public static final double STACK_STEP = 2.5;

    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        for (int n = 1; n <= 3; n++) {
            double lift = (n - 1) * STACK_STEP;
            SHAPES[n] = ConstructionProps.union(
                    ConstructionProps.px(3.5, 0, 3.5, 12.5, 1.2 + lift, 12.5),
                    ConstructionProps.px(5.5, 0, 5.5, 10.5, 8 + lift, 10.5),
                    ConstructionProps.px(6.8, 0, 6.8, 9.2, 15.2 + lift, 9.2));
        }
    }

    public TrafficConeBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(COUNT, 1));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(COUNT);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState existing = context.getWorld().getBlockState(context.getBlockPos());
        if (existing.isOf(this)) {
            return existing.with(COUNT, Math.min(3, existing.get(COUNT) + 1));
        }
        return getDefaultState();
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        if (!context.shouldCancelInteraction() && context.getStack().isOf(asItem()) && state.get(COUNT) < 3) {
            return true;
        }
        return super.canReplace(state, context);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(COUNT)];
    }
}
