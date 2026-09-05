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
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * El stair side course (railing / cream wall / wired glass): the sloped
 * enclosure beside a {@link SubwayStairBlock} flight. Lives in the cell
 * BESIDE each stair block, one per stair block, with its panel on the
 * stair's edge; {@link #FACING} is the ascent, {@link #SIDE} says which side
 * of this block the stair is on (looking uphill). Runs chain one up + one
 * forward like the stairs, and courses stack vertically (16 px pitch).
 *
 * <p>{@link #BOTTOM}: nothing of the family below - draw the stringer and
 * kick plate. {@link #END_UP} / {@link #END_DOWN}: no continuation up+forward
 * / down+back (the 45-degree members are cut at the cell boundary).
 * {@link #LEVEL}: the stair beside is the TOP of its flight, so this cell
 * levels its rails off over the last tread at floor height and closes with a
 * post. Diagonal continuations get no vanilla neighbour update, so placement
 * and removal recompute them explicitly ({@link #refreshDiagonals}).
 * Assets: tools/gen_el2_stairs.py.
 */
public class ElStairSideBlock extends Block {
    public enum Side implements StringIdentifiable {
        LEFT("left"), RIGHT("right");

        private final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);
    public static final BooleanProperty BOTTOM = BooleanProperty.of("bottom");
    /** Nothing of the family above: the post runs on up to the roof band. */
    public static final BooleanProperty TOP = BooleanProperty.of("top");
    public static final BooleanProperty END_UP = BooleanProperty.of("end_up");
    public static final BooleanProperty END_DOWN = BooleanProperty.of("end_down");
    public static final BooleanProperty LEVEL = BooleanProperty.of("level");

    /** [side][bottom] -> per-facing shapes. */
    private final VoxelShape[][][] shapes = new VoxelShape[2][2][];

    public ElStairSideBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(SIDE, Side.LEFT)
                .with(BOTTOM, true).with(TOP, true).with(END_UP, true).with(END_DOWN, true).with(LEVEL, false));
        // authored facing NORTH with the stair to the WEST: panel plane at x 0..2.4.
        // The course band is v 12..20 on the 45-degree slope, i.e. y from
        // 24 - z to 40 - z at world z: four steps along z track it (the
        // bottom course reaches the floor line with its stringer and kick).
        for (int side = 0; side < 2; side++) {
            double x0 = side == 0 ? 0 : 13.6, x1 = side == 0 ? 2.4 : 16;
            for (int bottom = 0; bottom < 2; bottom++) {
                VoxelShape north = net.minecraft.util.shape.VoxelShapes.empty();
                for (int i = 0; i < 4; i++) {
                    double z0 = 4 * i, z1 = 4 * (i + 1);
                    double lo = bottom == 1 ? 0 : Math.max(0, 24 - z1);
                    double hi = Math.min(32, 40 - z0);
                    north = net.minecraft.util.shape.VoxelShapes.union(north,
                            Block.createCuboidShape(x0, lo, z0, x1, hi, z1));
                }
                shapes[side][bottom] = FacingDecorBlock.rotations(north.simplify());
            }
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE, BOTTOM, TOP, END_UP, END_DOWN, LEVEL);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(SIDE) == Side.LEFT ? 0 : 1][state.get(BOTTOM) ? 1 : 0][state.get(FACING).getHorizontal()];
    }

    /** Direction from this block to the stair it flanks. */
    public static Direction stairDirection(Direction facing, Side side) {
        return side == Side.LEFT ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
    }

    public static boolean isStairSide(BlockState state) {
        return state.getBlock() instanceof ElStairSideBlock;
    }

    private static boolean continues(WorldAccess world, BlockPos cell, Direction facing, Side side) {
        BlockState state = world.getBlockState(cell);
        return isStairSide(state) && state.get(FACING) == facing && state.get(SIDE) == side;
    }

    private BlockState compute(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Side side = state.get(SIDE);
        BlockState stair = world.getBlockState(pos.offset(stairDirection(facing, side)));
        BlockState below = world.getBlockState(pos.down());
        // the bottom course reads the stair beside it; stacked courses inherit
        // the level-off from the course under them (no stair beside them)
        boolean level = stair.getBlock() instanceof SubwayStairBlock
                ? stair.get(SubwayStairBlock.FACING) == facing && stair.get(SubwayStairBlock.TOP)
                : isStairSide(below) && below.get(FACING) == facing && below.get(SIDE) == side && below.get(LEVEL);
        return state.with(BOTTOM, !continues(world, pos.down(), facing, side))
                .with(TOP, !continues(world, pos.up(), facing, side))
                .with(END_UP, !continues(world, pos.up().offset(facing), facing, side))
                .with(END_DOWN, !continues(world, pos.down().offset(facing.getOpposite()), facing, side))
                .with(LEVEL, level);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        Direction facing = context.getHorizontalPlayerFacing();
        Side side = null;
        // stacking on a course: same ascent and side as the course below
        BlockState under = world.getBlockState(pos.down());
        if (isStairSide(under)) {
            return compute(getDefaultState().with(FACING, under.get(FACING)).with(SIDE, under.get(SIDE)), world, pos);
        }
        // a stair beside us decides both the ascent and the side
        for (Side candidate : Side.values()) {
            for (Direction f : Direction.Type.HORIZONTAL) {
                BlockState stair = world.getBlockState(pos.offset(stairDirection(f, candidate)));
                if (stair.getBlock() instanceof SubwayStairBlock && stair.get(SubwayStairBlock.FACING) == f) {
                    facing = f;
                    side = candidate;
                }
            }
            if (side != null) {
                break;
            }
        }
        if (side == null) {
            // no stair yet: the stair is on the side the player stands on
            Direction right = facing.rotateYClockwise();
            Vec3d rel = context.getPlayer() == null ? Vec3d.ZERO
                    : context.getPlayer().getPos().subtract(Vec3d.ofCenter(pos));
            double lateral = rel.x * right.getOffsetX() + rel.z * right.getOffsetZ();
            side = lateral > 0 ? Side.RIGHT : Side.LEFT;
        }
        return compute(getDefaultState().with(FACING, facing).with(SIDE, side), world, pos);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = compute(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return compute(state, world, pos);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        refreshDiagonals(world, pos, state);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!newState.isOf(this)) {
            refreshDiagonals(world, pos, state);
        }
    }

    private void refreshDiagonals(World world, BlockPos pos, BlockState state) {
        Direction facing = state.get(FACING);
        for (BlockPos cell : new BlockPos[]{pos.up().offset(facing), pos.down().offset(facing.getOpposite())}) {
            BlockState neighbor = world.getBlockState(cell);
            if (neighbor.getBlock() instanceof ElStairSideBlock block) {
                BlockState fresh = block.compute(neighbor, world, cell);
                if (fresh != neighbor) {
                    world.setBlockState(cell, fresh, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }
}
