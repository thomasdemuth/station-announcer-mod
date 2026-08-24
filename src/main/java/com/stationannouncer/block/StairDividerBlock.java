package com.stationannouncer.block;

import com.stationannouncer.ModContent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The black steel stringer beam that divides a wide subway staircase into
 * lanes. Ascends toward FACING like the stairs beside it.
 *
 * <p>Three jobs beyond the plain diagonal (all Thomas's in-game feedback):
 * the run's ends stop CLEANLY — {@code BOTTOM} swaps in a model whose beam
 * is clipped flush to the floor with a vertical foot, {@code TOP} one that
 * ends flush with the landing under a level cap (the full 45° rescaled beam
 * overshoots its block by 3.5 px at both corners, which mid-run blocks use
 * to bridge to their diagonal neighbors, but a free end read as a floating
 * blade). And it DETECTS THE STAIRS BESIDE IT: {@code LEFT}/{@code RIGHT}
 * record whether a same-facing stair sits on that side (and which style),
 * so the model extends that stair's treads all the way to the plate.</p>
 *
 * <p>End continuity is diagonal, like the stairs — placement/removal
 * refresh the two diagonal cells explicitly; the side wings ride ordinary
 * six-neighbor updates.</p>
 */
public class StairDividerBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty BOTTOM = BooleanProperty.of("bottom");
    public static final BooleanProperty TOP = BooleanProperty.of("top");
    public static final EnumProperty<Side> LEFT = EnumProperty.of("left", Side.class);
    public static final EnumProperty<Side> RIGHT = EnumProperty.of("right", Side.class);

    /** What kind of stair (if any) leans against this side of the plate. */
    public enum Side implements StringIdentifiable {
        NONE("none"), MODERN("modern"), OLD("old");

        private final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    private final VoxelShape[] shapes;

    public StairDividerBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(BOTTOM, true).with(TOP, true)
                .with(LEFT, Side.NONE).with(RIGHT, Side.NONE));
        this.shapes = FacingDecorBlock.rotations(
                createCuboidShape(6.5, 0, 0, 9.5, 16, 16));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, BOTTOM, TOP, LEFT, RIGHT);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(FACING).getHorizontal()];
    }

    private boolean continues(WorldAccess world, BlockPos cell, Direction facing) {
        BlockState state = world.getBlockState(cell);
        return state.isOf(this) && state.get(FACING) == facing;
    }

    private static Side sideOf(WorldAccess world, BlockPos pos, Direction facing, Direction toward) {
        BlockState neighbor = world.getBlockState(pos.offset(toward));
        if (neighbor.getBlock() instanceof SubwayStairBlock
                && neighbor.get(SubwayStairBlock.FACING) == facing) {
            return neighbor.isOf(ModContent.SUBWAY_STAIRS_OLD) ? Side.OLD : Side.MODERN;
        }
        return Side.NONE;
    }

    private BlockState computed(WorldAccess world, BlockPos pos, Direction facing) {
        return getDefaultState().with(FACING, facing)
                .with(BOTTOM, !continues(world, pos.down().offset(facing.getOpposite()), facing))
                .with(TOP, !continues(world, pos.up().offset(facing), facing))
                .with(LEFT, sideOf(world, pos, facing, facing.rotateYCounterclockwise()))
                .with(RIGHT, sideOf(world, pos, facing, facing.rotateYClockwise()));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return computed(context.getWorld(), context.getBlockPos(),
                context.getHorizontalPlayerFacing());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Side wings follow ordinary neighbor updates; ends are re-derived
        // too (cheap, and it heals after odd edits).
        return computed(world, pos, state.get(FACING));
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

    private void refreshDiagonals(World world, BlockPos pos, Direction facing) {
        for (BlockPos cell : new BlockPos[]{
                pos.down().offset(facing.getOpposite()), pos.up().offset(facing)}) {
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
