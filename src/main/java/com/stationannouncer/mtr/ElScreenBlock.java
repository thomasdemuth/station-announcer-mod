package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * A merging run along the platform: the el windscreens and railings.
 *
 * <p><b>Placement rule.</b> FACING is the player's look direction reversed
 * (FacingDecorBlock), so the panel plane always stands ACROSS the view — you
 * face where the screen should be and it runs left-to-right in front of you.
 * The run therefore extends along {@code FACING.rotateYClockwise()}, which is
 * the axis the models are authored along (x, in the NORTH frame the blockstate
 * rotates by y=0/90/180/270 for north/east/south/west — the same map the
 * outline shapes use, so shape and model always agree).
 *
 * <p><b>Why placement may flip 180°.</b> Every screen panel is symmetric about
 * its own plane, so facing north and facing south look identical — but they
 * are different states, and {@link #joins} demands an exact match, so a run
 * built while walking down one side and then the other would refuse to merge
 * and would close BOTH segments with their own posts (visibly doubled). Since
 * the two facings are visually indistinguishable, placement adopts a
 * neighbouring run's facing when that neighbour is the reverse of ours, i.e.
 * in the same plane. The plane the player chose is always honoured; only the
 * invisible 180° is taken from the run being extended.
 *
 * <p>Segments with the same block and facing join left/right (zebra-board
 * rule) and up/down; the multipart draws each segment's LEFT frame post always
 * plus a closing RIGHT post only at the run's end — one shared post per joint,
 * any run length — with the top rail at a stack's head and the kick at its
 * foot.
 */
public class ElScreenBlock extends FacingDecorBlock {
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    /** Same screen directly above/below: panels stack into one tall wall —
     * the top rail draws only at the stack top, the kick at its foot. */
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");

    public ElScreenBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(LEFT, false).with(RIGHT, false)
                .with(UP, false).with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LEFT, RIGHT, UP, DOWN);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) {
            return null;
        }
        state = state.with(FACING, planeFacing(state.get(FACING),
                context.getWorld(), context.getBlockPos()));
        return withConnections(state, context.getWorld(), context.getBlockPos());
    }

    /**
     * Keeps the plane the player picked but flips the (invisible) 180° when a
     * screen of this kind already stands in that same plane where this run
     * would continue — otherwise the two halves of a run built from opposite
     * sides never merge and every joint shows doubled posts.
     */
    private Direction planeFacing(Direction facing, WorldAccess world, BlockPos pos) {
        Direction reverse = facing.getOpposite();
        Direction right = facing.rotateYClockwise();
        for (BlockPos neighbor : new BlockPos[]{pos.offset(right), pos.offset(right.getOpposite()),
                                                pos.up(), pos.down()}) {
            BlockState state = world.getBlockState(neighbor);
            if (state.isOf(this) && state.get(FACING) == reverse) {
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

    private boolean joins(WorldAccess world, BlockPos pos, Direction facing) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.isOf(this) && neighbor.get(FACING) == facing;
    }

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        return state.with(RIGHT, joins(world, pos.offset(right), facing))
                .with(LEFT, joins(world, pos.offset(right.getOpposite()), facing))
                .with(UP, joins(world, pos.up(), facing))
                .with(DOWN, joins(world, pos.down(), facing));
    }
}
