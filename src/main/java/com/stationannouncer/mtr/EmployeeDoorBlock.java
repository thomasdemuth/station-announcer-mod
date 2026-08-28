package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The employees-only door: a fixed two-block staff door that drops into a
 * fare-control run like any other {@link com.stationannouncer.block.GateSection}
 * — bars, grille, exit door and this all share one post at each joint.
 *
 * <p>Right-click (with anything but the brush) swings it open — quietly, no
 * alarm: staff doors are not emergency exits — and it pulls itself shut again
 * after {@value #AUTO_CLOSE_TICKS} ticks, the way a door on a closer does.
 * Open, the doorway is walk-through. Three variants share this class and
 * differ only in their models — wire-mesh panels, solid black steel, and
 * off-white painted steel that blends with the subway tile walls.</p>
 *
 * <p>Right-click WITH THE MTR BRUSH to edit the label plate; the text lives in
 * a {@link StationDecorBlockEntity} on the lower half (the shared CustomName
 * field, so the sign screen and the {@code update_decor} packet are reused
 * unchanged). A fresh door says {@value #DEFAULT_LABEL}; clearing the text
 * removes the plate entirely.</p>
 */
public class EmployeeDoorBlock extends Block implements com.stationannouncer.block.GateSection, BlockEntityProvider {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
    /** Shared posts with an adjoining wall run — see GateWallBlock. */
    public static final BooleanProperty LEFT = com.stationannouncer.block.GateWallBlock.LEFT;
    public static final BooleanProperty RIGHT = com.stationannouncer.block.GateWallBlock.RIGHT;

    /** Swings open on right-click and closes itself again a few seconds later. */
    public static final BooleanProperty OPEN = Properties.OPEN;

    public static final String DEFAULT_LABEL = "EMPLOYEES ONLY";

    /** How long an opened door stays open before pulling itself shut (4 s). */
    public static final int AUTO_CLOSE_TICKS = 80;

    private static final double HALF_THICKNESS = 3.0;

    private final VoxelShape[] outlines = new VoxelShape[Direction.values().length];

    public EmployeeDoorBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(HALF, DoubleBlockHalf.LOWER).with(OPEN, false)
                .with(LEFT, false).with(RIGHT, false));
        for (Direction facing : Direction.values()) {
            outlines[facing.ordinal()] = facing.getAxis() == Direction.Axis.X
                    ? createCuboidShape(8 - HALF_THICKNESS, 0, 0, 8 + HALF_THICKNESS, 16, 16)
                    : createCuboidShape(0, 0, 8 - HALF_THICKNESS, 16, 16, 8 + HALF_THICKNESS);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, OPEN, LEFT, RIGHT);
    }

    private BlockState withNeighbours(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        return state.with(LEFT, com.stationannouncer.block.GateSection.joins(
                        world.getBlockState(pos.offset(right.getOpposite())), facing))
                .with(RIGHT, com.stationannouncer.block.GateSection.joins(
                        world.getBlockState(pos.offset(right)), facing));
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Like the exit door: the placer's look direction is authoritative
        // (the model's front faces the placer), never inherited from a run.
        Direction facing = context.getHorizontalPlayerFacing();
        BlockPos above = context.getBlockPos().up();
        if (above.getY() >= context.getWorld().getTopY() - 1
                || !context.getWorld().getBlockState(above).isReplaceable()) {
            return null; // needs two blocks of room
        }
        return withNeighbours(getDefaultState().with(FACING, facing),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
        // A fresh door announces itself; clear the text with the brush for a
        // blank one. Written here rather than defaulted in the block entity so
        // "" stays a real, persistable choice.
        if (!world.isClient && world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
            decor.setCustomName(DEFAULT_LABEL);
            decor.sync();
        }
    }

    /** The MTR brush opens the label editor; anything else swings the door. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        BlockPos lower = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
        if (player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            if (world.isClient) {
                if (world.getBlockEntity(lower) instanceof StationDecorBlockEntity decor) {
                    StationAnnouncer.GUI_OPENER.accept(decor);
                }
                return ActionResult.SUCCESS;
            }
            return ActionResult.CONSUME;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        setOpen(world, lower, !state.get(OPEN));
        return ActionResult.CONSUME;
    }

    /** Swings both halves; opening arms the auto-close. No alarm — it is a staff door, not an exit. */
    private void setOpen(World world, BlockPos lower, boolean opening) {
        for (BlockPos cell : new BlockPos[]{lower, lower.up()}) {
            BlockState half = world.getBlockState(cell);
            if (half.getBlock() == this && half.get(OPEN) != opening) {
                world.setBlockState(cell, half.with(OPEN, opening), Block.NOTIFY_ALL);
            }
        }
        world.playSound(null, lower, opening
                        ? net.minecraft.sound.SoundEvents.BLOCK_IRON_DOOR_OPEN
                        : net.minecraft.sound.SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
        if (opening) {
            world.scheduleBlockTick(lower, this, AUTO_CLOSE_TICKS);
        }
    }

    /** The auto-close: an open door pulls itself shut. */
    @Override
    public void scheduledTick(BlockState state, net.minecraft.server.world.ServerWorld world,
                              BlockPos pos, net.minecraft.util.math.random.Random random) {
        if (state.get(HALF) == DoubleBlockHalf.LOWER && state.get(OPEN)) {
            setOpen(world, pos, false);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.get(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
                && (!neighborState.isOf(this) || neighborState.get(HALF) == half)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        return withNeighbours(state, world, pos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()];
    }

    /** Closed it is a wall with hinges; open, the doorway is walk-through. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN) ? net.minecraft.util.shape.VoxelShapes.empty()
                : outlines[state.get(FACING).ordinal()];
    }

    /** The label lives on the lower half only, like every other 2-block data half. */
    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(HALF) == DoubleBlockHalf.LOWER ? new StationDecorBlockEntity(pos, state) : null;
    }
}
