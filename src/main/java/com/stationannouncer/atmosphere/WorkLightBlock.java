package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * A tripod LED floodlight on a telescopic stand. FACING = the way the lamp
 * head aims; it is placed aiming AWAY from the player (the way they look),
 * like setting a light up to shine on what is in front of you. The head
 * stands above the block (~1.6 blocks overall). Luminance 15 via settings.
 */
public class WorkLightBlock extends FacingDecorBlock {
    private final VoxelShape[] collision;

    public WorkLightBlock(Settings settings) {
        super(settings, ConstructionProps.union(
                ConstructionProps.px(3, 0, 3, 13, 12, 13),
                ConstructionProps.px(4, 12, 4, 12, 29, 12)));
        this.collision = ConstructionProps.rotations(ConstructionProps.px(6, 0, 6, 10, 24, 10));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collision[state.get(FACING).getHorizontal()];
    }
}
