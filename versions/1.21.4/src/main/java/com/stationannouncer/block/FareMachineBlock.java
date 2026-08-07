package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
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
 * The MetroCard-style fare vending machine: a two-block-tall, 8-px-deep unit
 * that sits flush against the wall behind it (door-style multiblock — the
 * lower half is the data half, breaking either half removes both).
 *
 * <p>Decorative on its own; with MTR installed the MTR module installs
 * {@link #FARE_HANDLER}, which turns emeralds into MTR ticket balance
 * (right-click holding emeralds) and reads the balance back (right-click
 * empty-handed) — same rate as MTR's own ticket machine.
 */
public class FareMachineBlock extends FacingDecorBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** Handles a fare machine use server-side; installed by the MTR module when MTR is present. */
    public interface FareHandler {
        void use(ServerWorld world, BlockPos pos, ServerPlayerEntity player, ItemStack heldStack);
    }

    @Nullable
    public static FareHandler FARE_HANDLER = null;

    private final VoxelShape upperNorth;
    private final VoxelShape upperEast;
    private final VoxelShape upperSouth;
    private final VoxelShape upperWest;

    public FareMachineBlock(Settings settings, VoxelShape lowerNorthShape) {
        super(settings, lowerNorthShape);
        this.upperNorth = Block.createCuboidShape(1.0, 0.0, 8.0, 15.0, 14.0, 16.0);
        this.upperEast = rotateClockwise(upperNorth);
        this.upperSouth = rotateClockwise(upperEast);
        this.upperWest = rotateClockwise(upperSouth);
        setDefaultState(getDefaultState().with(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(HALF);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        BlockPos above = context.getBlockPos().up();
        if (state == null || above.getY() >= context.getWorld().getTopY() - 1
                || !context.getWorld().getBlockState(above).isReplaceable()) {
            return null; // needs two blocks of room
        }
        return state.with(HALF, DoubleBlockHalf.LOWER);
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
        if (state.get(HALF) == DoubleBlockHalf.UPPER) {
            return switch (state.get(FACING)) {
                case EAST -> upperEast;
                case SOUTH -> upperSouth;
                case WEST -> upperWest;
                default -> upperNorth;
            };
        }
        return super.getOutlineShape(state, world, pos, context);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (FARE_HANDLER != null && world instanceof ServerWorld serverWorld
                && player instanceof ServerPlayerEntity serverPlayer) {
            FARE_HANDLER.use(serverWorld, pos, serverPlayer, player.getStackInHand(hand));
        } else {
            player.sendMessage(Text.translatable("msg.station_announcer.fare.no_mtr"), true);
        }
        return ActionResult.CONSUME;
    }
}
