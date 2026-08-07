package com.stationannouncer.block;

import com.stationannouncer.ModContent;
import com.stationannouncer.entity.SeatEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * A platform bench. Benches placed side by side (same facing) merge into one
 * long bench — the legs only render at the free ends. Right-click to sit
 * down (sneak to get up); an invisible {@link SeatEntity} carries the player
 * and cleans itself up.
 */
public class BenchBlock extends FacingDecorBlock {
    /** Whether another bench continues on that side (bench-local, facing-relative). */
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");

    public BenchBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(LEFT, false).with(RIGHT, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LEFT, RIGHT);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null : withConnections(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    /** Recomputes the left/right merge flags from the neighbors along the bench axis. */
    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        return state
                .with(LEFT, connectsTo(world, pos.offset(right.getOpposite()), facing))
                .with(RIGHT, connectsTo(world, pos.offset(right), facing));
    }

    private boolean connectsTo(WorldAccess world, BlockPos pos, Direction facing) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.getBlock() instanceof BenchBlock && neighbor.get(FACING) == facing;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (player.hasVehicle() || player.isSneaking()) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        // One seat per bench block: reuse is impossible since seats discard
        // themselves the moment they are empty.
        Box benchBox = new Box(pos);
        if (!world.getEntitiesByClass(SeatEntity.class, benchBox, seat -> true).isEmpty()) {
            return ActionResult.CONSUME;
        }
        SeatEntity seat = new SeatEntity(ModContent.SEAT_ENTITY, world);
        // Calibrated against minecart seating: the passenger rides 0.6 below
        // the seat entity and their visual seat point is ~0.475 below their
        // position — this puts the butt exactly on the seat slab (y + 9/16).
        seat.setPosition(pos.getX() + 0.5, pos.getY() + 0.6875, pos.getZ() + 0.5);
        world.spawnEntity(seat);
        player.startRiding(seat);
        return ActionResult.CONSUME;
    }
}
