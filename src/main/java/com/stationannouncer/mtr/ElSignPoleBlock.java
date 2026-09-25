package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import com.stationannouncer.block.SelfSettle;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The standing {@code el_sign}'s leg carried down to the ground (Thomas,
 * 2026-09-25). A standing sign only has legs at the free ends of its run
 * (model x 2..3.2 on the LEFT end, 12.8..14 on the RIGHT); stacking these
 * under it continues exactly those legs to the floor, with a bolted foot
 * plate at the bottom of the stack.
 *
 * <p>SIDE says which leg(s) this cell carries - a lone sign block stands on
 * both of its own. FACING and SIDE are ADOPTED from what is directly above: a
 * standing el_sign (its free ends) or another pole, re-read on every
 * neighbour update so a stack follows its sign when the run grows or shrinks.
 * With nothing to adopt (building up from the floor) facing is the usual
 * look-reversed decor facing and the side comes from where in the block you
 * clicked: outer thirds = that leg, middle = both. Leg geometry is shared
 * with {@code tools/gen_el2_assets.py} ({@code SIGN_LEG_X}).
 */
public class ElSignPoleBlock extends FacingDecorBlock {
    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");

    public enum Side implements StringIdentifiable {
        LEFT, RIGHT, BOTH;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        boolean left() {
            return this != RIGHT;
        }

        boolean right() {
            return this != LEFT;
        }
    }

    /** Outline per side ordinal x horizontal facing index. A little fatter than the 1.2 px shaft so it can be aimed at. */
    private static final VoxelShape[][] SHAPES = new VoxelShape[Side.values().length][];

    static {
        VoxelShape left = Block.createCuboidShape(1.4, 0.0, 7.0, 3.8, 16.0, 9.0);
        VoxelShape right = Block.createCuboidShape(12.2, 0.0, 7.0, 14.6, 16.0, 9.0);
        SHAPES[Side.LEFT.ordinal()] = FacingDecorBlock.rotations(left);
        SHAPES[Side.RIGHT.ordinal()] = FacingDecorBlock.rotations(right);
        SHAPES[Side.BOTH.ordinal()] = FacingDecorBlock.rotations(VoxelShapes.union(left, right));
    }

    public ElSignPoleBlock(Settings settings) {
        super(settings, VoxelShapes.fullCube());
        setDefaultState(getDefaultState().with(SIDE, Side.BOTH).with(UP, false).with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(SIDE, UP, DOWN);
    }

    /** A standing el_sign's leg(s), or null when {@code state} carries none (not a standing sign, or a mid-run cell). */
    @Nullable
    private static Side signLegs(BlockState state) {
        if (!(state.getBlock() instanceof ElNameBoardBlock board) || !board.merges()
                || state.get(ElNameBoardBlock.MOUNT) != ElNameBoardBlock.Mount.STANDING) {
            return null;
        }
        boolean left = !state.get(ElNameBoardBlock.LEFT);
        boolean right = !state.get(ElNameBoardBlock.RIGHT);
        return left && right ? Side.BOTH : left ? Side.LEFT : right ? Side.RIGHT : null;
    }

    /** Facing + side taken from {@code source} (a pole or a standing sign), or null when it has nothing to line up with. */
    @Nullable
    private BlockState adopt(BlockState state, BlockState source) {
        if (source.getBlock() instanceof ElSignPoleBlock) {
            return state.with(FACING, source.get(FACING)).with(SIDE, source.get(SIDE));
        }
        Side legs = signLegs(source);
        return legs == null ? null : state.with(FACING, source.get(FACING)).with(SIDE, legs);
    }

    private BlockState withConnections(BlockState state, BlockView world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        return state
                .with(UP, above.getBlock() instanceof ElSignPoleBlock || signLegs(above) != null)
                .with(DOWN, world.getBlockState(pos.down()).getBlock() instanceof ElSignPoleBlock);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState state = adopt(getDefaultState(), world.getBlockState(pos.up()));
        if (state == null) {
            state = adopt(getDefaultState(), world.getBlockState(pos.down()));
        }
        if (state == null) {
            Direction facing = context.getHorizontalPlayerFacing().getOpposite();
            // model +x is facing.rotateYClockwise() (the sign's RIGHT neighbour)
            Direction right = facing.rotateYClockwise();
            Vec3d hit = context.getHitPos().subtract(Vec3d.ofBottomCenter(pos));
            double along = hit.x * right.getOffsetX() + hit.z * right.getOffsetZ();
            Side side = along < -1.0 / 6 ? Side.LEFT : along > 1.0 / 6 ? Side.RIGHT : Side.BOTH;
            state = getDefaultState().with(FACING, facing).with(SIDE, side);
        }
        return withConnections(state, world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP) {
            BlockState adopted = adopt(state, neighborState);
            if (adopted != null) {
                state = adopted;
            }
        }
        return direction.getAxis() == Direction.Axis.Y ? withConnections(state, world, pos) : state;
    }

    /** /fill, /setblock and pastes never call getPlacementState: line up with what is above. */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.UP);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(SIDE).ordinal()][state.get(FACING).getHorizontal()];
    }
}
