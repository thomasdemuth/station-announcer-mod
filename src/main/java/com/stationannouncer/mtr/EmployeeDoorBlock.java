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
 * <p>It never opens. There is no OPEN state, no swing model and no doorway:
 * this is the locked door you walk PAST in a station, not through, which is
 * exactly why it exists as scenery. Three variants share this class and differ
 * only in their models — wire-mesh panels, solid black steel, and off-white
 * painted steel that blends with the subway tile walls.</p>
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

    public static final String DEFAULT_LABEL = "EMPLOYEES ONLY";

    private static final double HALF_THICKNESS = 3.0;

    private final VoxelShape[] outlines = new VoxelShape[Direction.values().length];

    public EmployeeDoorBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(HALF, DoubleBlockHalf.LOWER).with(LEFT, false).with(RIGHT, false));
        for (Direction facing : Direction.values()) {
            outlines[facing.ordinal()] = facing.getAxis() == Direction.Axis.X
                    ? createCuboidShape(8 - HALF_THICKNESS, 0, 0, 8 + HALF_THICKNESS, 16, 16)
                    : createCuboidShape(0, 0, 8 - HALF_THICKNESS, 16, 16, 8 + HALF_THICKNESS);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, LEFT, RIGHT);
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
        // Placed into an ironwork run, it adopts the run's facing.
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        for (Direction side : Direction.values()) {
            BlockState neighbour = context.getWorld().getBlockState(context.getBlockPos().offset(side));
            if (neighbour.getBlock() instanceof com.stationannouncer.block.GateSection
                    && neighbour.contains(FACING)) {
                facing = neighbour.get(FACING);
                break;
            }
        }
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

    /** Only the MTR brush does anything: it opens the label editor. The door itself never opens. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        BlockPos lower = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
        if (world.isClient) {
            if (world.getBlockEntity(lower) instanceof StationDecorBlockEntity decor) {
                StationAnnouncer.GUI_OPENER.accept(decor);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
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

    /** Always solid — a locked door is a wall with hinges. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()];
    }

    /** The label lives on the lower half only, like every other 2-block data half. */
    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(HALF) == DoubleBlockHalf.LOWER ? new StationDecorBlockEntity(pos, state) : null;
    }
}
