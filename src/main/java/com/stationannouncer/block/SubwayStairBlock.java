package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * NYC subway staircase: two 8 px steps per block ascending toward FACING.
 * The bottommost and topmost blocks of a run paint their leading step's
 * nosing and riser safety yellow (the photos' worn warning stripe), tracked
 * by the TOP / BOTTOM properties.
 *
 * <p>Run continuity is DIAGONAL (one up+forward / one down+backward), which
 * vanilla's neighbor updates never fire for — so placement and removal
 * explicitly recompute the two diagonal neighbors ({@link #refreshDiagonals}).
 * The modern variant's mesh risers need the cutout render layer.</p>
 */
public class SubwayStairBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /** No same-stair continues downhill: paint the lower step's nosing yellow. */
    public static final BooleanProperty BOTTOM = BooleanProperty.of("bottom");
    /** No same-stair continues uphill: paint the upper step's nosing yellow. */
    public static final BooleanProperty TOP = BooleanProperty.of("top");

    private final VoxelShape[] shapes;

    public SubwayStairBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(BOTTOM, true).with(TOP, true));
        // Ascending toward NORTH: lower half full, upper half on the north side.
        VoxelShape north = VoxelShapes.union(
                createCuboidShape(0, 0, 0, 16, 8, 16),
                createCuboidShape(0, 8, 0, 16, 16, 8)).simplify();
        this.shapes = FacingDecorBlock.rotations(north);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, BOTTOM, TOP);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(FACING).getHorizontal()];
    }

    /** The downhill continuation cell: one down, one block against the ascent. */
    private static BlockPos downhill(BlockPos pos, Direction facing) {
        return pos.down().offset(facing.getOpposite());
    }

    /** The uphill continuation cell: one up, one block along the ascent. */
    private static BlockPos uphill(BlockPos pos, Direction facing) {
        return pos.up().offset(facing);
    }

    private boolean continues(WorldAccess world, BlockPos cell, Direction facing) {
        BlockState state = world.getBlockState(cell);
        return state.isOf(this) && state.get(FACING) == facing;
    }

    private BlockState computed(WorldAccess world, BlockPos pos, Direction facing) {
        return getDefaultState().with(FACING, facing)
                .with(BOTTOM, !continues(world, downhill(pos, facing), facing))
                .with(TOP, !continues(world, uphill(pos, facing), facing));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return computed(context.getWorld(), context.getBlockPos(),
                context.getHorizontalPlayerFacing());
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        refreshDiagonals(world, pos, state.get(FACING));
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        Direction facing = state.get(FACING);
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!newState.isOf(this)) {
            refreshDiagonals(world, pos, facing);
        }
    }

    /**
     * Diagonal continuations are invisible to vanilla's six-neighbor updates,
     * so a placed/broken stair re-derives TOP/BOTTOM for the two cells its
     * run touches (and itself via getPlacementState / this call's callers).
     */
    private void refreshDiagonals(World world, BlockPos pos, Direction facing) {
        for (BlockPos cell : new BlockPos[]{downhill(pos, facing), uphill(pos, facing)}) {
            BlockState neighbor = world.getBlockState(cell);
            if (neighbor.isOf(this)) {
                BlockState fresh = computed(world, cell, neighbor.get(FACING));
                if (fresh != neighbor) {
                    world.setBlockState(cell, fresh, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }
}
