package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
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
 *
 * <p>IN-CELL SIDES (2026-09-20): {@link #LEFT} / {@link #RIGHT} put the el
 * stair family's stringer, railing or wall on the stair's OWN edge (the treads
 * narrow by 2.4 px on that side), so a flight needs no columns beside it - a
 * stair through a platform only needs a well of its own width. A wall under a
 * ceiling, or under an {@link ElStairUpperBlock} wall, becomes {@code wall_fill}
 * (vertical boards to the top of the cell - the triangle wall). Set with the el
 * stair course items (sneak-click a tread's outer third); a new stair copies the
 * sides of the run it continues. {@link #POST}: posts on every other cell.</p>
 */
public class SubwayStairBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /** No same-stair continues downhill: paint the lower step's nosing yellow. */
    public static final BooleanProperty BOTTOM = BooleanProperty.of("bottom");
    /** No same-stair continues uphill: paint the upper step's nosing yellow. */
    public static final BooleanProperty TOP = BooleanProperty.of("top");
    /** Modern only: closed steel construction instead of the floating one.
     *  Declared on both blocks (appendProperties runs from the super
     *  constructor, before instance fields exist) but only the modern
     *  block's blockstate maps it and only it toggles. */
    public static final BooleanProperty SOLID = BooleanProperty.of("solid");

    /** What stands on one edge of the stair, inside its own cell. */
    public enum InSide implements StringIdentifiable {
        NONE("none"), STRINGER("stringer"), RAILING("railing"), WALL("wall"), WALL_FILL("wall_fill"),
        /** The ESI theme's finish of the same four (charcoal / black panel), from the esi_stair_* items. */
        ESI_STRINGER("esi_stringer"), ESI_RAILING("esi_railing"), ESI_WALL("esi_wall"), ESI_WALL_FILL("esi_wall_fill");

        private final String name;

        InSide(String name) {
            this.name = name;
        }

        /** Something a player cannot walk through. */
        public boolean panel() {
            return this != NONE && this != STRINGER && this != ESI_STRINGER;
        }

        /** A wall of either finish (becomes / stops being a triangle fill). */
        public boolean wall() {
            return this == WALL || this == WALL_FILL || this == ESI_WALL || this == ESI_WALL_FILL;
        }

        public boolean esi() {
            return ordinal() >= ESI_STRINGER.ordinal();
        }

        /** The same side as a wall that fills its cell, or as a banded wall. */
        public InSide asWall(boolean fill) {
            return esi() ? (fill ? ESI_WALL_FILL : ESI_WALL) : (fill ? WALL_FILL : WALL);
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final EnumProperty<InSide> LEFT = EnumProperty.of("left", InSide.class);
    public static final EnumProperty<InSide> RIGHT = EnumProperty.of("right", InSide.class);
    public static final BooleanProperty POST = BooleanProperty.of("post");

    /** [left panel][right panel] -> per-facing shapes. */
    private final VoxelShape[][][] shapes = new VoxelShape[2][2][];
    private final boolean solidToggle;

    public SubwayStairBlock(Settings settings, boolean solidToggle) {
        super(settings);
        this.solidToggle = solidToggle;
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(BOTTOM, true).with(TOP, true).with(SOLID, false)
                .with(LEFT, InSide.NONE).with(RIGHT, InSide.NONE).with(POST, true));
        // Ascending toward NORTH: lower half full, upper half on the north side.
        VoxelShape steps = VoxelShapes.union(
                createCuboidShape(0, 0, 0, 16, 8, 16),
                createCuboidShape(0, 8, 0, 16, 16, 8));
        for (int left = 0; left < 2; left++) {
            for (int right = 0; right < 2; right++) {
                VoxelShape north = steps;
                if (left == 1) {
                    north = VoxelShapes.union(north, sidePanel(0, 2.4));
                }
                if (right == 1) {
                    north = VoxelShapes.union(north, sidePanel(13.6, 16));
                }
                shapes[left][right] = FacingDecorBlock.rotations(north.simplify());
            }
        }
    }

    /** An in-cell railing / wall: four steps under the rail line (16 px over the nosings, y = 40 - z). */
    private static VoxelShape sidePanel(double x0, double x1) {
        VoxelShape panel = VoxelShapes.empty();
        for (int i = 0; i < 4; i++) {
            panel = VoxelShapes.union(panel, createCuboidShape(x0, 0, 4 * i, x1, Math.min(32, 40 - 4 * i), 4 * (i + 1)));
        }
        return panel;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, BOTTOM, TOP, SOLID, LEFT, RIGHT, POST);
    }

    /** Empty-hand right-click on the modern stair toggles the closed
     *  construction; holding anything PASSes so building against a stair
     *  still works. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (!solidToggle || !player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        boolean solid = !state.get(SOLID);
        world.setBlockState(pos, state.with(SOLID, solid), Block.NOTIFY_LISTENERS);
        world.playSound(null, pos, solid
                        ? SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE
                        : SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN,
                SoundCategory.BLOCKS, 0.6f, 1.4f);
        return ActionResult.CONSUME;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(LEFT).panel() ? 1 : 0][state.get(RIGHT).panel() ? 1 : 0][state.get(FACING).getHorizontal()];
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

    /** Everything derived from the surroundings; FACING, SOLID and the chosen sides come from {@code base}. */
    private BlockState computed(BlockState base, WorldAccess world, BlockPos pos) {
        Direction facing = base.get(FACING);
        return base.with(BOTTOM, !continues(world, downhill(pos, facing), facing))
                .with(TOP, !continues(world, uphill(pos, facing), facing))
                .with(POST, StairFamily.postCell(pos, facing))
                .with(LEFT, settled(base.get(LEFT), world, pos, true))
                .with(RIGHT, settled(base.get(RIGHT), world, pos, false));
    }

    /**
     * wall <-> wall_fill: a wall fills its cell (triangle wall) under a ceiling,
     * or when the upper course right above carries a wall on the same edge -
     * a banded wall would overlap that cell's boards.
     */
    private static InSide settled(InSide side, WorldAccess world, BlockPos pos, boolean left) {
        if (!side.wall()) {
            return side;
        }
        BlockState above = world.getBlockState(pos.up());
        boolean upper = above.getBlock() instanceof ElStairUpperBlock
                && above.get(left ? ElStairUpperBlock.LEFT : ElStairUpperBlock.RIGHT) != ElStairUpperBlock.Kind.NONE;
        return side.asWall(upper || StairFamily.underCeiling(world, pos));
    }

    /**
     * An upper wall course was put over this stair on one edge: carry the wall
     * down onto the stair's own edge if that edge is still bare, so the
     * enclosure has no slot between the treads and the wall above.
     */
    public void ensureWall(World world, BlockPos pos, BlockState state, boolean left, InSide wall) {
        if (state.get(left ? LEFT : RIGHT) != InSide.NONE) {
            return;
        }
        world.setBlockState(pos, computed(state.with(left ? LEFT : RIGHT, wall), world, pos), Block.NOTIFY_ALL);
    }

    /** Sets one in-cell side (the el stair course items call this); same value again clears it. */
    public void toggleSide(World world, BlockPos pos, BlockState state, boolean left, InSide side) {
        InSide current = state.get(left ? LEFT : RIGHT);
        boolean same = current == side || (side.wall() && current.wall() && side.esi() == current.esi());
        BlockState next = computed(state.with(left ? LEFT : RIGHT, same ? InSide.NONE : side), world, pos);
        world.setBlockState(pos, next, Block.NOTIFY_ALL);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // only the cell above matters (an upper course turning a wall into a fill); ends are diagonal
        return direction == Direction.UP ? computed(state, world, pos) : state;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // neighbours decide the ascent; look direction is the fallback (sneak forces it)
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        Direction facing = StairFamily.placementAscent(context);
        BlockState base = getDefaultState().with(FACING, facing).with(SOLID, solidToggle
                && com.stationannouncer.item.SubwayStairItem.selectedSolid(context.getStack()));
        // continuing a run: carry its in-cell sides along (not when the player forces a fresh start)
        if (!StairFamily.forced(context)) {
            for (BlockPos cell : new BlockPos[]{downhill(pos, facing), uphill(pos, facing)}) {
                BlockState run = world.getBlockState(cell);
                if (run.isOf(this) && run.get(FACING) == facing) {
                    base = base.with(LEFT, run.get(LEFT)).with(RIGHT, run.get(RIGHT));
                    break;
                }
            }
        }
        return computed(base, world, pos);
    }

    /** /fill, pastes and the stair creator skip getPlacementState: settle ends/posts/fills and the run's diagonals. */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world.isClient) {
            return;
        }
        BlockState wanted = computed(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
        if (!oldState.isOf(this)) {
            refreshDiagonals(world, pos, state.get(FACING));
        }
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
                BlockState fresh = computed(neighbor, world, cell);
                if (fresh != neighbor) {
                    world.setBlockState(cell, fresh, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }
}
