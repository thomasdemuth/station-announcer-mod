package com.stationannouncer.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Doorway course of the windscreen family: posts and a header beam, no
 * panel, no collision - the opening you walk through into a stair house.
 * Stack a normal course on top for the transom. Joins runs like any course.
 */
public class ElWallDoorwayBlock extends ElWallBlock {
    public ElWallDoorwayBlock(Settings settings) {
        super(settings);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }
}
