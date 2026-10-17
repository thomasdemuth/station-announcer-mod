package com.stationannouncer.atmosphere;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * The caged bulb fixture screwed to the underside of a sidewalk shed deck:
 * placed in the cell under the shed, it hangs from the block above. Light
 * comes from the block settings' luminance (13). No collision.
 */
public class ShedLightBlock extends Block {
    private static final VoxelShape SHAPE = ConstructionProps.px(5, 6.5, 5, 11, 16, 11);

    public ShedLightBlock(Settings settings) {
        super(settings);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }
}
