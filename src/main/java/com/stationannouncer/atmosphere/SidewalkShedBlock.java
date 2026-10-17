package com.stationannouncer.atmosphere;

import com.stationannouncer.block.SelfSettle;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The roof of an NYC sidewalk shed: a dark-green plywood deck on steel
 * beams, placed directly ON the shed posts (beams at the bottom of the cell,
 * the deck walkable at 5/16). {@code north/east/south/west} = another shed
 * cell on that side; a side WITHOUT one gets the plywood parapet (fascia over
 * the beam ends, a wall up to 15.5/16 with a cap rail). {@code axis} = the direction the beams run (from the
 * neighbouring shed when there is one, else the player's look direction).
 * Collision: the deck slab plus the parapets.
 */
public class SidewalkShedBlock extends Block {
    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty WEST = Properties.WEST;
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    private static final VoxelShape DECK = ConstructionProps.px(0, 0, 0, 16, 5, 16);
    private static final VoxelShape[] SHAPES = new VoxelShape[16];

    static {
        for (int bits = 0; bits < 16; bits++) {
            VoxelShape s = DECK;
            if ((bits & 1) == 0) {
                s = ConstructionProps.union(s, ConstructionProps.px(0, 0, 0, 16, 15.5, 1.3));
            }
            if ((bits & 2) == 0) {
                s = ConstructionProps.union(s, ConstructionProps.px(14.7, 0, 0, 16, 15.5, 16));
            }
            if ((bits & 4) == 0) {
                s = ConstructionProps.union(s, ConstructionProps.px(0, 0, 14.7, 16, 15.5, 16));
            }
            if ((bits & 8) == 0) {
                s = ConstructionProps.union(s, ConstructionProps.px(0, 0, 0, 1.3, 15.5, 16));
            }
            SHAPES[bits] = s;
        }
    }

    public SidewalkShedBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(NORTH, false).with(EAST, false).with(SOUTH, false)
                .with(WEST, false).with(AXIS, Direction.Axis.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, AXIS);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        Direction.Axis axis = context.getHorizontalPlayerFacing().getAxis();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState n = world.getBlockState(pos.offset(d));
            if (n.isOf(this)) {
                axis = n.get(AXIS);
                break;
            }
        }
        return withConnections(getDefaultState().with(AXIS, axis), world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    public BlockState withConnections(BlockState state, BlockView world, BlockPos pos) {
        return state.with(NORTH, world.getBlockState(pos.north()).isOf(this))
                .with(EAST, world.getBlockState(pos.east()).isOf(this))
                .with(SOUTH, world.getBlockState(pos.south()).isOf(this))
                .with(WEST, world.getBlockState(pos.west()).isOf(this));
    }

    private static int bits(BlockState state) {
        return (state.get(NORTH) ? 1 : 0) | (state.get(EAST) ? 2 : 0) | (state.get(SOUTH) ? 4 : 0) | (state.get(WEST) ? 8 : 0);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[bits(state)];
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.DOWN);
    }
}
