package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * A niche cut into a tile wall — the recess you get out of the way in if you
 * end up on the track.
 *
 * <p>Only the back of the niche and its two jambs are solid, so the opening is
 * walk-in. Nothing closes the top or bottom either: two of these stacked make
 * one niche tall enough to stand in, and the wall blocks above and below
 * supply the lintel and floor.</p>
 */
public class AlcoveBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    /** How deep the niche is cut, in model pixels (matches the model): half a block. */
    public static final double DEPTH = 8.0;

    /** Collision by {@link Direction#getHorizontal()}: 0=south, 1=west, 2=north, 3=east. */
    private final VoxelShape[] shapes = new VoxelShape[4];

    public AlcoveBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
        // Authored facing north (opening toward -Z): the back slab plus both jambs.
        VoxelShape north = net.minecraft.util.shape.VoxelShapes.union(
                createCuboidShape(0.0, 0.0, DEPTH, 16.0, 16.0, 16.0),
                createCuboidShape(0.0, 0.0, 0.0, 1.0, 16.0, DEPTH),
                createCuboidShape(15.0, 0.0, 0.0, 16.0, 16.0, DEPTH)).simplify();
        VoxelShape east = FacingDecorBlock.rotateClockwise(north);
        VoxelShape south = FacingDecorBlock.rotateClockwise(east);
        VoxelShape west = FacingDecorBlock.rotateClockwise(south);
        shapes[Direction.SOUTH.getHorizontal()] = south;
        shapes[Direction.WEST.getHorizontal()] = west;
        shapes[Direction.NORTH.getHorizontal()] = north;
        shapes[Direction.EAST.getHorizontal()] = east;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // The niche opens toward the player placing it.
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(FACING).getHorizontal()];
    }
}
