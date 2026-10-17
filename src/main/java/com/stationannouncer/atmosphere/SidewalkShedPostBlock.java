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
 * The green steel H-post a sidewalk shed stands on. Stackable: {@code up} /
 * {@code down} = another post above / below; the bottom of a stack gets a
 * bolted base plate on a timber pad, the top a cap plate with knee braces
 * that carries the shed's beam. {@code axis} = the direction the flanges
 * face (the player's look when placed, or the post below's).
 */
public class SidewalkShedPostBlock extends Block {
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    private static final VoxelShape COLUMN = ConstructionProps.px(5.5, 0, 5.5, 10.5, 16, 10.5);
    private static final VoxelShape BASE = ConstructionProps.px(3.5, 0, 3.5, 12.5, 1.2, 12.5);
    private static final VoxelShape CAP = ConstructionProps.px(3.5, 14.8, 3.5, 12.5, 16, 12.5);
    /** Indexed by (up ? 1 : 0) | (down ? 2 : 0). */
    private static final VoxelShape[] SHAPES = {
            ConstructionProps.union(COLUMN, BASE, CAP),
            ConstructionProps.union(COLUMN, BASE),
            ConstructionProps.union(COLUMN, CAP),
            COLUMN};

    public SidewalkShedPostBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(UP, false).with(DOWN, false).with(AXIS, Direction.Axis.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(UP, DOWN, AXIS);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        Direction.Axis axis = context.getHorizontalPlayerFacing().getAxis();
        for (BlockPos p : new BlockPos[]{pos.down(), pos.up()}) {
            BlockState n = world.getBlockState(p);
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
        return state.with(UP, world.getBlockState(pos.up()).isOf(this))
                .with(DOWN, world.getBlockState(pos.down()).isOf(this));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[(state.get(UP) ? 1 : 0) | (state.get(DOWN) ? 2 : 0)];
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.DOWN);
    }
}
