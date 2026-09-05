package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * A run of thin panels AT THE FAR EDGE of the block, facing the placer (el
 * windscreen walls, el platform railings). FACING is the placer's look
 * reversed. Runs merge along {@code facing.rotateYClockwise()} with
 * LEFT/RIGHT (left post always, closing right post at run ends); a run built
 * from the far side flips to match an existing run in the same plane
 * ({@link #planeFacing}); CORNER_LEFT/RIGHT draw a return along a side edge
 * when the block in front belongs to a perpendicular run of the same family.
 */
public abstract class EdgeRunBlock extends FacingDecorBlock {
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    /**
     * Convex corners. Panels sit on the NEAR side of the cell outside the
     * platform, so two perpendicular runs meet at the platform's corner
     * point with no corner cell: CORNER_RIGHT means the perpendicular run's
     * end cell is diagonally ahead-right, and this cell drops its right post
     * (the other run's left post stands there); CORNER_LEFT is the mirror
     * case and only records the fact.
     */
    public static final BooleanProperty CORNER_LEFT = BooleanProperty.of("corner_left");
    public static final BooleanProperty CORNER_RIGHT = BooleanProperty.of("corner_right");
    /** Nothing of this family below: the bottom course, whose posts continue down and bolt to the platform edge. */
    public static final BooleanProperty BOTTOM = BooleanProperty.of("bottom");
    /**
     * Concave (inner) platform corner: the cell BEHIND this one holds a
     * perpendicular run whose panel line meets ours here, so this cell draws
     * a second panel along that side edge (a return) to close the corner.
     */
    public static final BooleanProperty INNER_LEFT = BooleanProperty.of("inner_left");
    public static final BooleanProperty INNER_RIGHT = BooleanProperty.of("inner_right");
    /**
     * A block placed IN the diagonal cell outside a convex platform corner:
     * the perpendicular run stands in FRONT of it (toward facing). Such a cell
     * draws nothing but a corner post at the corner point (right or left side
     * per the perpendicular run's facing) - its panel would otherwise sit a
     * whole block off the platform edge line.
     */
    public enum Corner implements net.minecraft.util.StringIdentifiable {
        NONE("none"), LEFT("left"), RIGHT("right");

        private final String name;

        Corner(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final net.minecraft.state.property.EnumProperty<Corner> CORNER_CELL =
            net.minecraft.state.property.EnumProperty.of("corner_cell", Corner.class);

    public EdgeRunBlock(Settings settings, net.minecraft.util.shape.VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(LEFT, false).with(RIGHT, false)
                .with(CORNER_LEFT, false).with(CORNER_RIGHT, false).with(BOTTOM, true)
                .with(INNER_LEFT, false).with(INNER_RIGHT, false).with(CORNER_CELL, Corner.NONE));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LEFT, RIGHT, CORNER_LEFT, CORNER_RIGHT, BOTTOM, INNER_LEFT, INNER_RIGHT, CORNER_CELL);
    }

    /** Same family (any course/kind of this run type) - the only thing a run joins. */
    protected abstract boolean sameFamily(BlockState state);

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) {
            return null;
        }
        state = state.with(FACING, planeFacing(state.get(FACING), context.getWorld(), context.getBlockPos()));
        return withConnections(state, context.getWorld(), context.getBlockPos());
    }

    private Direction planeFacing(Direction facing, WorldAccess world, BlockPos pos) {
        Direction reverse = facing.getOpposite();
        Direction right = facing.rotateYClockwise();
        for (BlockPos neighbor : new BlockPos[]{pos.offset(right), pos.offset(right.getOpposite()),
                                                pos.up(), pos.down()}) {
            BlockState state = world.getBlockState(neighbor);
            if (sameFamily(state) && state.get(FACING) == reverse) {
                return reverse;
            }
        }
        return facing;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    /**
     * A run continues into ANY edge-run block with the same facing (a wall
     * course into a railing and back), so the junction shows one post - the
     * neighbour's left post - rather than two side by side.
     */
    private static boolean joins(WorldAccess world, BlockPos pos, Direction facing) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.getBlock() instanceof EdgeRunBlock && neighbor.get(FACING) == facing;
    }

    /** Extra per-family properties (e.g. the railing's lamp post); called from withConnections. */
    protected BlockState withExtras(BlockState state, WorldAccess world, BlockPos pos) {
        return state;
    }

    protected BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        Direction left = right.getOpposite();
        // Convex platform corner: the perpendicular run's last cell sits
        // diagonally ahead of our run end; the two panels meet at a point.
        BlockState atLeft = world.getBlockState(pos.offset(left).offset(facing));
        BlockState atRight = world.getBlockState(pos.offset(right).offset(facing));
        boolean cornerLeft = sameFamily(atLeft) && atLeft.get(FACING) == right;
        // CORNER_RIGHT now means "extend the right post across the corner
        // square"; with a family block in the diagonal cell that cell's own
        // corner post fills the square instead
        boolean cornerRight = sameFamily(atRight) && atRight.get(FACING) == left
                && !sameFamily(world.getBlockState(pos.offset(right)));
        BlockState behind = world.getBlockState(pos.offset(facing.getOpposite()));
        boolean innerLeft = sameFamily(behind) && behind.get(FACING) == left;
        boolean innerRight = sameFamily(behind) && behind.get(FACING) == right;
        BlockState front = world.getBlockState(pos.offset(facing));
        Corner cornerCell = Corner.NONE;
        if (sameFamily(front)) {
            if (front.get(FACING) == right) {
                cornerCell = Corner.RIGHT;
            } else if (front.get(FACING) == left) {
                cornerCell = Corner.LEFT;
            }
        }
        return withExtras(state.with(RIGHT, joins(world, pos.offset(right), facing))
                .with(LEFT, joins(world, pos.offset(left), facing))
                .with(CORNER_LEFT, cornerLeft)
                .with(CORNER_RIGHT, cornerRight)
                .with(INNER_LEFT, innerLeft)
                .with(INNER_RIGHT, innerRight)
                .with(CORNER_CELL, cornerCell)
                .with(BOTTOM, !sameFamily(world.getBlockState(pos.down()))), world, pos);
    }
}
