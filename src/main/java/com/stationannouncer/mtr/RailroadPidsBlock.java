package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;
import org.mtr.mod.block.IBlock;

/**
 * A Railroad PIDS — the tall portrait departure board of a commuter railroad:
 * line name and clock across the top, the departure time and destination on a
 * bar in the line's colour, then the route ahead as a station list with timed
 * connections.
 *
 * <p>Two variants, both door-style two-block multiblocks whose screen spans
 * 0.5–2.5 blocks above the floor exactly like the subway PIDS: {@code wall}
 * sits flush against the wall behind it and reads from one side;
 * {@code standing} stands on its own legs and reads from both.</p>
 *
 * <p>The lower half is the data half — it carries the block entity, the loot
 * table and the renderer. Everything is configured through our own screen,
 * opened with the MTR brush.</p>
 */
public class RailroadPidsBlock extends Block implements BlockEntityProvider {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** Free-standing units draw their screen on both faces; wall units on one. */
    public final boolean standing;

    /** Outline shapes by [facing ordinal][lower? 1 : 0]. */
    private final VoxelShape[][] outlines = new VoxelShape[Direction.values().length][2];

    public RailroadPidsBlock(Settings settings, boolean standing) {
        super(settings);
        this.standing = standing;
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER));
        // Same envelope as the subway PIDS: the upper half's box runs to y=24
        // because the screen overhangs the block pair by half a block. Built
        // with MTR's helper, which handles both the rotation and the overhang.
        double z1 = standing ? 6.5 : 15.0;
        double z2 = standing ? 9.5 : 16.0;
        for (Direction facing : Direction.values()) {
            org.mtr.mapping.holder.Direction mapped = org.mtr.mapping.holder.Direction.convert(facing);
            // Lower: wall units start 8 px up (the screen's bottom edge);
            // standing units are boxed from the floor because of the legs.
            outlines[facing.ordinal()][1] = IBlock.getVoxelShapeByDirection(
                    0, standing ? 0 : 8, z1, 16, 16, z2, mapped).data;
            outlines[facing.ordinal()][0] = IBlock.getVoxelShapeByDirection(
                    0, 0, z1, 16, 24, z2, mapped).data;
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        // Only the data half carries a block entity (and therefore the renderer).
        return state.get(HALF) == DoubleBlockHalf.LOWER ? new RailroadPidsBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos above = context.getBlockPos().up();
        if (above.getY() >= context.getWorld().getTopY() - 1
                || !context.getWorld().getBlockState(above).isReplaceable()) {
            return null; // needs two blocks of room
        }
        return getDefaultState()
                .with(FACING, context.getHorizontalPlayerFacing().getOpposite())
                .with(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Breaking either half removes the other, like doors.
        DoubleBlockHalf half = state.get(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
                && (!neighborState.isOf(this) || neighborState.get(HALF) == half)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()][state.get(HALF) == DoubleBlockHalf.LOWER ? 1 : 0];
    }

    /** The MTR brush opens the board's settings; clicks on either half act on the data half. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            BlockPos dataPos = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
            if (world.getBlockEntity(dataPos) instanceof RailroadPidsBlockEntity pids) {
                StationAnnouncer.GUI_OPENER.accept(pids);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
