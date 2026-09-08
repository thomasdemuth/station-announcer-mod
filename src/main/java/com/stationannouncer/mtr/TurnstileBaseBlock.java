package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
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
 * Shared machinery for the low fare-array units (turnstile lanes and the
 * array end cap): two-block-tall door-style multiblocks.
 *
 * <p>Frame conventions (v2, 2026-09-06): {@link #FACING} is the direction an
 * ENTERING rider walks (unpaid to paid), set from where the placer looks.
 * The cabinet stands on the rider's right; the lane opens to the rider's
 * LEFT, where the next unit's cabinet (or the end cap) closes it — that
 * neighbour is {@link #laneSide(BlockState)}.
 */
public abstract class TurnstileBaseBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    protected TurnstileBaseBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    /** Direction from this unit toward the neighbour that closes its lane (rider's left). */
    public static Direction laneSide(BlockState state) {
        return state.get(FACING).rotateYCounterclockwise();
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos pos = context.getBlockPos();
        World world = context.getWorld();
        if (pos.getY() >= world.getTopY() - 1 || !world.getBlockState(pos.up()).isReplaceable()) {
            return null; // needs two blocks of room
        }
        BlockState state = getDefaultState()
                .with(FACING, context.getHorizontalPlayerFacing())
                .with(HALF, DoubleBlockHalf.LOWER);
        return withNeighbours(state, world, pos);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        BlockState upper = state.with(HALF, DoubleBlockHalf.UPPER);
        world.setBlockState(pos.up(), withNeighbours(upper, world, pos.up()), Block.NOTIFY_ALL);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Breaking either half removes the other, like doors.
        DoubleBlockHalf half = state.get(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
                && (!neighborState.isOf(this) || neighborState.get(HALF) == half)) {
            return Blocks.AIR.getDefaultState();
        }
        return withNeighbours(state, world, pos);
    }

    /** Hook for row-dependent properties (the lane's arch join); the cap has none. */
    protected BlockState withNeighbours(BlockState state, WorldAccess world, BlockPos pos) {
        return state;
    }

    /** True when the same-half block of another fare-array unit with the same facing sits on the lane side. */
    protected static boolean joinsLaneSide(BlockState state, WorldAccess world, BlockPos pos) {
        BlockState neighbor = world.getBlockState(pos.offset(laneSide(state)));
        return neighbor.getBlock() instanceof TurnstileBaseBlock
                && neighbor.get(HALF) == state.get(HALF)
                && neighbor.get(FACING) == state.get(FACING);
    }

    /** Picks the facing-rotated shape from a NORTH/EAST/SOUTH/WEST array. */
    protected static VoxelShape rotated(VoxelShape[] shapes, BlockState state) {
        return shapes[state.get(FACING).getHorizontal()];
    }

    /** Builds the four rotations of a NORTH-frame shape (indexed by Direction.getHorizontal()). */
    public static VoxelShape[] rotations(VoxelShape north) {
        VoxelShape east = FacingDecorBlock.rotateClockwise(north);
        VoxelShape south = FacingDecorBlock.rotateClockwise(east);
        VoxelShape west = FacingDecorBlock.rotateClockwise(south);
        // Direction.getHorizontal(): 0=south, 1=west, 2=north, 3=east
        return new VoxelShape[]{south, west, north, east};
    }

    @Override
    public abstract VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context);
}
