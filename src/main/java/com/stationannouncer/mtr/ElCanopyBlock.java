package com.stationannouncer.mtr;

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
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The platform canopies: a roof plane that tiles freely in both directions —
 * any platform width and length. AXIS is the run direction (ribs/ridge);
 * the four connection booleans track same-family neighbours so the flat
 * canopy hangs its riveted fascia girder on every open edge and the gable
 * closes its open run ends with the stepped end plate, all automatically.
 * Authored low in the block, so a canopy directly above its posts touches
 * them, and its underside purlins give hanging blocks (PIDS, signs) steel
 * to land on.
 */
public class ElCanopyBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    public static final BooleanProperty NORTH = BooleanProperty.of("north");
    public static final BooleanProperty SOUTH = BooleanProperty.of("south");
    public static final BooleanProperty EAST = BooleanProperty.of("east");
    public static final BooleanProperty WEST = BooleanProperty.of("west");

    private final VoxelShape shape;

    public ElCanopyBlock(Settings settings, double height) {
        super(settings);
        this.shape = createCuboidShape(0.0, 0.0, 0.0, 16.0, height, 16.0);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X)
                .with(NORTH, false).with(SOUTH, false).with(EAST, false).with(WEST, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS, NORTH, SOUTH, EAST, WEST);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shape;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withConnections(getDefaultState()
                        .with(AXIS, context.getHorizontalPlayerFacing().getAxis()),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        return state
                .with(NORTH, joins(world, pos.offset(Direction.NORTH)))
                .with(SOUTH, joins(world, pos.offset(Direction.SOUTH)))
                .with(EAST, joins(world, pos.offset(Direction.EAST)))
                .with(WEST, joins(world, pos.offset(Direction.WEST)));
    }

    private static boolean joins(WorldAccess world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() instanceof ElCanopyBlock;
    }
}
