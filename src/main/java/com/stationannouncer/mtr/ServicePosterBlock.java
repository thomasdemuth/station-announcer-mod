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
 * A wall-mounted service change poster frame: a 16 x 25 px plate across a
 * two-block stack (lower half y 1..16, upper half y 0..10), one
 * pixel proud of the wall behind it. The poster itself is painted by the block
 * entity renderer from the {@code ServicePoster} the sign was assigned — pick
 * one by right-clicking the frame.
 *
 * <p>Door-style two-block multiblock like the railroad PIDS: the lower half is
 * the data half (block entity, loot, renderer); breaking either half takes the
 * other.</p>
 */
public class ServicePosterBlock extends Block implements BlockEntityProvider {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** Plate extents in the two-block stack, in pixels from the lower block's floor. */
    public static final double PLATE_BOTTOM = 1.0;
    public static final double PLATE_TOP = 26.0;

    /** Outline shapes by [facing ordinal][lower? 1 : 0]. */
    private final VoxelShape[][] outlines = new VoxelShape[Direction.values().length][2];

    public ServicePosterBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER));
        for (Direction facing : Direction.values()) {
            org.mtr.mapping.holder.Direction mapped = org.mtr.mapping.holder.Direction.convert(facing);
            outlines[facing.ordinal()][1] = IBlock.getVoxelShapeByDirection(
                    0, PLATE_BOTTOM, 15, 16, 16, 16, mapped).data;
            outlines[facing.ordinal()][0] = IBlock.getVoxelShapeByDirection(
                    0, 0, 15, 16, PLATE_TOP - 16, 16, mapped).data;
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(HALF) == DoubleBlockHalf.LOWER ? new ServicePosterBlockEntity(pos, state) : null;
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

    /** Right-click either half: choose which poster hangs here. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            BlockPos dataPos = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
            if (world.getBlockEntity(dataPos) instanceof ServicePosterBlockEntity sign) {
                StationAnnouncer.GUI_OPENER.accept(sign);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
