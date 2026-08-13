package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.mtr.mod.block.IBlock;

/**
 * The big station departure board — the concourse board a whole hall reads.
 *
 * <p>One block, placed in a rectangle: neighbouring boards that share a facing
 * merge, and the whole area draws as a single screen with one header, one row
 * per departure and the station name along the bottom. A row is a fixed
 * physical height, so a taller board simply lists more trains — you size the
 * board to how many departures you want, the way a real one is specified.</p>
 *
 * <p>Two variants. {@code wall} sits flush against the wall behind it;
 * {@code hanging} is a free-standing case, read from both sides, sitting at the
 * same depth (z 5..11) as {@code pids_nyc_hanging} so a {@code pids_pole}
 * dropped from the ceiling lands on it in line. The case is full block height
 * because the cells stack — a shorter case with a pole stub would leave a gap
 * between the rows of a tall board.</p>
 *
 * <p><b>Every</b> block in the board carries a block entity, but only the
 * origin draws — see {@link #rectangle}. Settings are copied across the whole
 * rectangle when edited, so growing the board can never strand them.</p>
 */
public class DepartureBoardBlock extends Block implements BlockEntityProvider {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    /** Bounds on the merge scan, and therefore on one board. */
    public static final int MAX_WIDTH = 16;
    public static final int MAX_HEIGHT = 8;

    /** Free-hanging case with a pole stub, versus flush to the wall. */
    public final boolean hanging;

    private final VoxelShape[] outlines = new VoxelShape[Direction.values().length];

    public DepartureBoardBlock(Settings settings, boolean hanging) {
        super(settings);
        this.hanging = hanging;
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
        double z1 = hanging ? 5.0 : 12.0;
        double z2 = hanging ? 11.0 : 16.0;
        for (Direction facing : Direction.values()) {
            org.mtr.mapping.holder.Direction mapped = org.mtr.mapping.holder.Direction.convert(facing);
            outlines[facing.ordinal()] = IBlock.getVoxelShapeByDirection(0, 0, z1, 16, 16, z2, mapped).data;
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new DepartureBoardBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Placing against an existing board adopts its facing, so a wall of
        // them merges however the player happens to be standing.
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        for (Direction side : Direction.values()) {
            BlockState neighbor = context.getWorld().getBlockState(context.getBlockPos().offset(side));
            if (neighbor.isOf(this)) {
                facing = neighbor.get(FACING);
                break;
            }
        }
        return getDefaultState().with(FACING, facing);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()];
    }

    /** The MTR brush opens the board's settings. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof DepartureBoardBlockEntity board) {
                StationAnnouncer.GUI_OPENER.accept(board);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    // ------------------------------------------------------------- merging

    /**
     * The merged screen a block belongs to.
     *
     * @param origin the bottom-left cell in screen space — the only one that draws
     * @param width  cells across, along {@link #screenRight}
     * @param height cells tall
     */
    public record Rect(BlockPos origin, int width, int height) {
    }

    /**
     * The direction the canvas's x axis runs, i.e. the reader's right.
     *
     * <p>Counter-clockwise, not clockwise. After the renderer's
     * {@code 180 - facing.asRotation()} turn the viewer stands on local -Z with
     * their LEFT hand toward local +X, and the painter's canvas x grows from
     * there — so for a north-facing board the canvas runs east to west. Get
     * this backwards and the board's origin lands on the wrong corner and the
     * screen is built off the end of the blocks.</p>
     */
    public static Direction screenRight(Direction facing) {
        return facing.rotateYCounterclockwise();
    }

    private static boolean matches(BlockView world, BlockPos pos, Block block, Direction facing) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(block) && state.get(FACING) == facing;
    }

    /**
     * Works out the rectangle containing {@code pos}.
     *
     * <p>Walks left and down to a corner, measures the run along each axis from
     * there, then shrinks the height until every row is a full {@code width} —
     * so an L-shaped arrangement draws the largest rectangle sitting on that
     * corner rather than a torn screen. A block that is not its own rectangle's
     * origin draws nothing.</p>
     */
    public static Rect rectangle(BlockView world, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        Direction facing = state.get(FACING);
        Direction right = screenRight(facing);

        BlockPos origin = pos;
        for (int i = 0; i < MAX_WIDTH && matches(world, origin.offset(right.getOpposite()), block, facing); i++) {
            origin = origin.offset(right.getOpposite());
        }
        for (int i = 0; i < MAX_HEIGHT && matches(world, origin.down(), block, facing); i++) {
            origin = origin.down();
        }

        int width = 1;
        while (width < MAX_WIDTH && matches(world, origin.offset(right, width), block, facing)) {
            width++;
        }
        int height = 1;
        while (height < MAX_HEIGHT && matches(world, origin.up(height), block, facing)) {
            height++;
        }
        // Trim to the tallest FULL rectangle: a ragged top row would otherwise
        // stretch the screen over cells that are not there.
        rows:
        for (int y = 1; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!matches(world, origin.up(y).offset(right, x), block, facing)) {
                    height = y;
                    break rows;
                }
            }
        }
        return new Rect(origin, width, height);
    }
}
