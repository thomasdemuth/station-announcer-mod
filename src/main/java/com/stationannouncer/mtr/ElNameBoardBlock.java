package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The elevated platform's black station name board (Bay Parkway). The name
 * auto-follows the MTR station, both faces; right-click opens the custom-name
 * screen — the named-column machinery on a board that mounts three ways.
 *
 * <p>MOUNT comes from the clicked face, the way {@link ElExitSignBlock} does
 * it: a wall gives the flush wall plate facing out of that wall, a ceiling
 * gives the hanging board, and anything you stand it on top of (a windscreen's
 * top rail, a floor) gives the standing board on its two legs. FACING for the
 * standing and hanging boards is the placer's look direction reversed — the
 * facing-decor convention used by every other decor block here — and for the
 * wall board it is the clicked side, i.e. out of the wall.
 *
 * <p>The plate itself is block-model geometry; only the letters are painted,
 * by {@code StationDecorRenderer.paintElNameBoard}, which keys the canvas
 * plane off MOUNT. Plate boxes (model px, north-authored frame):
 * <pre>
 *   standing  x 1..15  y 6..13  z 7.4..8.6
 *   hanging   x 1..15  y 5..12  z 7.4..8.6
 *   wall      x 1..15  y 5..12  z 13.8..15.0
 * </pre>
 * They are all 14 x 7 px, so the renderer's canvas size and text fitting are
 * shared; only the plate's top y and front z differ. Keep this table, the one
 * in {@code tools/gen_el_assets.py} ({@code NAME_BOARD_PLATE}) and the
 * renderer in step.
 *
 * <p><b>Merging (el_sign only, 2026-09-20):</b> boards placed side by side
 * with the same facing and mount join into ONE larger sign. LEFT / RIGHT say
 * whether the board continues toward {@code facing.rotateYCounterclockwise()}
 * (model -x) / {@code rotateYClockwise()} (model +x); a connected side runs its
 * plate and cap to the block edge, and the renderer paints one canvas across
 * the run from the segment with nothing on its LEFT. The v1
 * {@code el_name_board} shares this class but never merges — its models have
 * no connected variants (its blockstate's partial variant keys ignore the two
 * properties).
 */
public class ElNameBoardBlock extends FacingDecorBlock implements BlockEntityProvider {
    public static final EnumProperty<Mount> MOUNT = EnumProperty.of("mount", Mount.class);
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");

    public enum Mount implements StringIdentifiable {
        STANDING, WALL, HANGING;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final boolean merges;
    /** Outline per mount x (left | right << 1) x horizontal facing index. */
    private final VoxelShape[][][] shapes = new VoxelShape[Mount.values().length][4][];

    public ElNameBoardBlock(Settings settings, VoxelShape northShape) {
        this(settings, northShape, false);
    }

    public ElNameBoardBlock(Settings settings, VoxelShape northShape, boolean merges) {
        super(settings, northShape);
        this.merges = merges;
        setDefaultState(getDefaultState().with(MOUNT, Mount.STANDING).with(LEFT, false).with(RIGHT, false));
        for (int sides = 0; sides < 4; sides++) {
            // A connected side's plate runs to the block edge.
            double x0 = (sides & 1) != 0 ? 0.0 : 1.0;
            double x1 = (sides & 2) != 0 ? 16.0 : 15.0;
            // northShape is the standing board (plate + legs down to the floor).
            VoxelShape stand = sides == 0 ? northShape
                    : net.minecraft.util.shape.VoxelShapes.union(northShape, createCuboidShape(x0, 6.0, 7.2, x1, 13.6, 8.8));
            shapes[Mount.STANDING.ordinal()][sides] = FacingDecorBlock.rotations(stand);
            shapes[Mount.WALL.ordinal()][sides] = FacingDecorBlock.rotations(createCuboidShape(x0, 5.0, 13.6, x1, 12.0, 16.0));
            shapes[Mount.HANGING.ordinal()][sides] = FacingDecorBlock.rotations(net.minecraft.util.shape.VoxelShapes.union(
                    createCuboidShape(x0, 5.0, 7.2, x1, 12.6, 8.8), createCuboidShape(7.0, 12.0, 7.0, 9.0, 16.0, 9.0)));
        }
    }

    /** Whether this block joins neighbouring boards into one sign (el_sign yes, the v1 name board no). */
    public boolean merges() {
        return merges;
    }

    /** True when {@code other} is a board that continues {@code state}'s sign: same block, facing and mount. */
    public static boolean sameSign(BlockState state, BlockState other) {
        return other.getBlock() == state.getBlock()
                && state.getBlock() instanceof ElNameBoardBlock board && board.merges
                && other.get(FACING) == state.get(FACING)
                && other.get(MOUNT) == state.get(MOUNT);
    }

    private BlockState withRun(BlockState state, BlockView world, BlockPos pos) {
        if (!merges) {
            return state;
        }
        Direction facing = state.get(FACING);
        return state
                .with(LEFT, sameSign(state, world.getBlockState(pos.offset(facing.rotateYCounterclockwise()))))
                .with(RIGHT, sameSign(state, world.getBlockState(pos.offset(facing.rotateYClockwise()))));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(MOUNT, LEFT, RIGHT);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return direction.getAxis().isHorizontal() ? withRun(state, world, pos) : state;
    }

    /** /setblock, /fill and pastes never call getPlacementState: join the run here too. */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient && !oldState.isOf(this)) {
            BlockState joined = withRun(state, world, pos);
            if (joined != state) {
                world.setBlockState(pos, joined, Block.NOTIFY_ALL);
            }
        }
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int sides = (state.get(LEFT) ? 1 : 0) | (state.get(RIGHT) ? 2 : 0);
        return shapes[state.get(MOUNT).ordinal()][sides][state.get(FACING).getHorizontal()];
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction side = context.getSide();
        if (side.getAxis().isHorizontal()) {
            // clicked a wall: plate flat on it, reading out of the wall
            return withRun(getDefaultState().with(MOUNT, Mount.WALL).with(FACING, side),
                    context.getWorld(), context.getBlockPos());
        }
        Mount mount = side == Direction.DOWN ? Mount.HANGING : Mount.STANDING;
        return withRun(getDefaultState().with(MOUNT, mount)
                .with(FACING, context.getHorizontalPlayerFacing().getOpposite()),
                context.getWorld(), context.getBlockPos());
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationDecorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                StationAnnouncer.GUI_OPENER.accept(decor);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
