package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
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
 * The ceiling-hung commuter-railroad board: a deep LED box slung under the
 * platform canopy on a pair of drop poles, alternating between a next-train
 * screen and a four-departure list.
 *
 * <p>Two blocks wide (left half holds the data, like the subway hanging PIDS)
 * so it can be broken from either end, and its ceiling stubs sit at x/z 7..9
 * so a stack of {@code pids_pole} continues them without a step. The box is
 * noticeably deeper than the subway signs — the real railroad units are chunky
 * cases, not thin panels.</p>
 */
public class RailroadPidsHangingBlock extends Block implements BlockEntityProvider {
    /** Which half of the two-block-wide unit this is. */
    public enum Side implements StringIdentifiable {
        LEFT("left"),
        RIGHT("right");

        private final String id;

        Side(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);

    /** Box geometry in model pixels — the screen height the user asked for, in a deep case. */
    public static final float BOX_TOP = 14.0f;
    public static final float BOX_FRONT = 5.0f;
    public static final float BOX_BACK = 11.0f;
    /** Bezel between the case edge and the lit screen. */
    public static final float BEZEL = 1.0f;

    private final VoxelShape[] outlines = new VoxelShape[Direction.values().length];

    public RailroadPidsHangingBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(SIDE, Side.LEFT));
        // Case plus the ceiling stub above it; symmetric across the unit, so
        // both halves can share one shape per facing.
        VoxelShape north = VoxelShapes.union(
                createCuboidShape(0, 0, BOX_FRONT, 16, BOX_TOP, BOX_BACK),
                createCuboidShape(7, BOX_TOP, 7, 9, 16, 9)).simplify();
        VoxelShape east = FacingDecorBlock.rotateClockwise(north);
        VoxelShape south = FacingDecorBlock.rotateClockwise(east);
        VoxelShape west = FacingDecorBlock.rotateClockwise(south);
        outlines[Direction.NORTH.ordinal()] = north;
        outlines[Direction.EAST.ordinal()] = east;
        outlines[Direction.SOUTH.ordinal()] = south;
        outlines[Direction.WEST.ordinal()] = west;
        outlines[Direction.UP.ordinal()] = north;
        outlines[Direction.DOWN.ordinal()] = north;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(SIDE) == Side.LEFT ? new RailroadPidsBlockEntity(pos, state) : null;
    }

    /** Direction from this half toward its partner. */
    private static Direction partnerDirection(BlockState state) {
        Direction right = state.get(FACING).rotateYClockwise();
        return state.get(SIDE) == Side.LEFT ? right : right.getOpposite();
    }

    /** The half holding the data (and the block entity) for a click on either. */
    public static BlockPos dataPos(BlockState state, BlockPos pos) {
        return state.get(SIDE) == Side.LEFT ? pos : pos.offset(partnerDirection(state));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        BlockPos partner = context.getBlockPos().offset(facing.rotateYClockwise());
        if (!context.getWorld().getBlockState(partner).isReplaceable()) {
            return null; // needs two blocks of room
        }
        return getDefaultState().with(FACING, facing).with(SIDE, Side.LEFT);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        world.setBlockState(pos.offset(partnerDirection(state)), state.with(SIDE, Side.RIGHT), Block.NOTIFY_ALL);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Breaking either half removes the other, so the unit deletes from either end.
        if (direction == partnerDirection(state)
                && (!neighborState.isOf(this) || neighborState.get(SIDE) == state.get(SIDE))) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()];
    }

    /** The MTR brush opens the board's settings; clicks on either half act on the data half. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            if (world.getBlockEntity(dataPos(state, pos)) instanceof RailroadPidsBlockEntity pids) {
                StationAnnouncer.GUI_OPENER.accept(pids);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
