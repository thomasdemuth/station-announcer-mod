package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * El kit v2 riveted plate girder (tools/gen_el2_structure.py). Runs along a
 * horizontal AXIS like a log. Over one of the street columns
 * ({@link #COLUMNS}) the cell grows the knee-brace roots ({@link #BRACED});
 * the two girder cells beside it along the axis draw the braces' outer
 * halves ({@link #BRACE_NEG}/{@link #BRACE_POS}: the neighbour toward the
 * negative / positive end of the axis stands over a column), so a brace
 * spans a block and a half and reads as the real curved gusset rather than
 * a stub. Those two flags depend on a block two cells away (diagonally
 * below), which vanilla neighbour updates never reach - a braced cell
 * refreshes its axis neighbours itself.
 */
public class ElGirderBlock extends Block {
    /** Still named "axis" with values x/z (plus the diagonals xz/zx) so old placements load. */
    public static final EnumProperty<ElRun> AXIS = EnumProperty.of("axis", ElRun.class);
    public static final BooleanProperty BRACED = BooleanProperty.of("braced");
    public static final BooleanProperty BRACE_NEG = BooleanProperty.of("brace_neg");
    public static final BooleanProperty BRACE_POS = BooleanProperty.of("brace_pos");

    /** The columns a girder braces against (filled at registration). */
    public static final Set<Block> COLUMNS = new HashSet<>();

    private final VoxelShape shapeX;
    private final VoxelShape shapeZ;
    private static final VoxelShape FULL = createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public ElGirderBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, ElRun.X).with(BRACED, false)
                .with(BRACE_NEG, false).with(BRACE_POS, false));
        this.shapeX = createCuboidShape(0.0, 0.0, 4.0, 16.0, 16.0, 12.0);
        this.shapeZ = createCuboidShape(4.0, 0.0, 0.0, 12.0, 16.0, 16.0);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS, BRACED, BRACE_NEG, BRACE_POS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        ElRun run = state.get(AXIS);
        return run == ElRun.X ? shapeX : run == ElRun.Z ? shapeZ : FULL;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return compute(getDefaultState().with(AXIS, ElRun.fromYaw(context.getPlayerYaw())),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return compute(state, world, pos);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        refreshNeighbors(world, pos, state.get(AXIS));
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!newState.isOf(this)) {
            refreshNeighbors(world, pos, state.get(AXIS));
        }
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos,
                               boolean notify) {
        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
        if (sourcePos.equals(pos.down())) {
            // a column appeared or vanished under us: our own BRACED came
            // through getStateForNeighborUpdate, the neighbours need a push
            refreshNeighbors(world, pos, state.get(AXIS));
        }
    }

    private static boolean overColumn(WorldAccess world, BlockPos pos) {
        return COLUMNS.contains(world.getBlockState(pos.down()).getBlock());
    }

    private static boolean girderAlong(BlockState state, ElRun axis) {
        return state.getBlock() instanceof ElGirderBlock && state.get(AXIS) == axis;
    }

    /** The girder's state at {@code pos} given the world around it (also used by the structure creator). */
    public static BlockState compute(BlockState state, WorldAccess world, BlockPos pos) {
        ElRun axis = state.get(AXIS);
        boolean braced = overColumn(world, pos);
        BlockPos negPos = pos.add(axis.back());
        BlockPos posPos = pos.add(axis.step());
        boolean braceNeg = !braced && girderAlong(world.getBlockState(negPos), axis) && overColumn(world, negPos);
        boolean bracePos = !braced && girderAlong(world.getBlockState(posPos), axis) && overColumn(world, posPos);
        return state.with(BRACED, braced).with(BRACE_NEG, braceNeg).with(BRACE_POS, bracePos);
    }

    private static void refreshNeighbors(World world, BlockPos pos, ElRun axis) {
        if (world.isClient) {
            return;
        }
        for (BlockPos other : new BlockPos[]{pos.add(axis.back()), pos.add(axis.step())}) {
            BlockState state = world.getBlockState(other);
            if (state.getBlock() instanceof ElGirderBlock) {
                BlockState fresh = compute(state, world, other);
                if (fresh != state) {
                    world.setBlockState(other, fresh, Block.NOTIFY_ALL);
                }
            }
        }
    }
}
