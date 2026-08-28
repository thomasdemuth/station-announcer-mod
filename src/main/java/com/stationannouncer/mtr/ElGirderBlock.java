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
 * An elevated-structure girder: the riveted plate girder and the open lattice
 * truss share this class (they differ only in models). Runs along a horizontal
 * AXIS like a log; placed directly over any el/station column the model grows
 * the two curved knee braces down the column's sides ({@link #BRACED}), so a
 * bent — column, braces, cap girder — assembles itself from two placements.
 */
public class ElGirderBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    /** A column sits directly below: the knee braces render. */
    public static final BooleanProperty BRACED = BooleanProperty.of("braced");

    private final VoxelShape shapeX;
    private final VoxelShape shapeZ;

    public ElGirderBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X).with(BRACED, false));
        this.shapeX = createCuboidShape(0.0, 0.0, 5.0, 16.0, 16.0, 11.0);
        this.shapeZ = createCuboidShape(5.0, 0.0, 0.0, 11.0, 16.0, 16.0);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS, BRACED);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(AXIS) == Direction.Axis.X ? shapeX : shapeZ;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withBrace(getDefaultState()
                        .with(AXIS, context.getHorizontalPlayerFacing().getAxis()),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN ? withBrace(state, world, pos) : state;
    }

    private BlockState withBrace(BlockState state, WorldAccess world, BlockPos pos) {
        return state.with(BRACED, world.getBlockState(pos.down()).getBlock() instanceof ColumnBlock);
    }
}
