package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

/**
 * Doorway course of the windscreen family: posts and a header beam, no
 * panel, no collision - the opening you walk through into a stair house or
 * a mezzanine. Doorways STACK into a taller opening: only the top one of a
 * stack draws the header ({@link #HEADER}), so two courses make a door a
 * player walks through upright. Stack a normal course on top for the
 * transom. Joins runs like any course.
 */
public class ElWallDoorwayBlock extends ElWallBlock {
    /** No doorway above: this course closes the opening with the header beam. */
    public static final BooleanProperty HEADER = BooleanProperty.of("header");

    public ElWallDoorwayBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(HEADER, true));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(HEADER);
    }

    @Override
    protected BlockState withExtras(BlockState state, WorldAccess world, BlockPos pos) {
        return state.with(HEADER, !(world.getBlockState(pos.up()).getBlock() instanceof ElWallDoorwayBlock));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }
}
