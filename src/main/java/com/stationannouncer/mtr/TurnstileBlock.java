package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.Map;

/**
 * A subway turnstile lane wired into MTR's ticket system. Walking through
 * the lane calls MTR's own {@code TicketSystem.passThrough} with entry AND
 * exit allowed — MTR decides which one this is from the player's travel
 * record and charges the same balance its ticket barriers use, with MTR's
 * own feedback sounds. The lane itself never blocks movement (no animation,
 * no barrier) — it is a walk-through fare checkpoint.
 *
 * <p>Cabinet on the lane's left, passage on the right; the next unit (or an
 * end cap) closes the lane. Two blocks tall; the overhead tubing (2.5 blocks)
 * bridges to the neighbor automatically via the RIGHT property.
 */
public class TurnstileBlock extends TurnstileBaseBlock {
    /** One fare event per player per lane within this window (one crossing = one charge). */
    private static final int RETRY_COOLDOWN_TICKS = 40;

    /** One lane + one player. */
    private record Crossing(long lane, java.util.UUID player) {
    }

    /** Last fare attempt per lane+player. */
    private static final Map<Crossing, Long> LAST_ATTEMPT = new HashMap<>();

    /** Drops all cooldown state (called when the server stops so nothing carries across worlds). */
    public static void clearAttempts() {
        LAST_ATTEMPT.clear();
    }

    private final VoxelShape[] lowerCollision;
    private final VoxelShape[] lowerOutline;
    private final VoxelShape[] upperShape;

    public TurnstileBlock(Settings settings) {
        super(settings);
        VoxelShape cabinet = createCuboidShape(0.0, 0.0, 1.0, 5.0, 16.0, 15.0);
        VoxelShape arm = createCuboidShape(4.0, 9.0, 6.0, 14.0, 13.0, 10.0);
        this.lowerCollision = rotations(cabinet);
        this.lowerOutline = rotations(net.minecraft.util.shape.VoxelShapes.union(cabinet, arm).simplify());
        this.upperShape = rotations(createCuboidShape(0.0, 0.0, 4.0, 5.0, 10.0, 12.0));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER
                ? rotated(upperShape, state)
                : rotated(lowerOutline, state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER
                ? rotated(upperShape, state)
                : rotated(lowerCollision, state);
    }

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (world.isClient || state.get(HALF) != DoubleBlockHalf.LOWER
                || !(entity instanceof PlayerEntity player)) {
            return;
        }
        // Runs every tick a player stands in the lane, so keep it allocation-light.
        Crossing key = new Crossing(pos.asLong(), player.getUuid());
        long time = world.getTime();
        Long last = LAST_ATTEMPT.get(key);
        if (last != null && time - last < RETRY_COOLDOWN_TICKS) {
            return;
        }
        LAST_ATTEMPT.put(key, time);
        if (LAST_ATTEMPT.size() > 512) {
            LAST_ATTEMPT.entrySet().removeIf(entry -> time - entry.getValue() > 400);
        }

        // MTR handles the fare, the travel record and all feedback sounds;
        // entry and exit are both allowed — it picks from the record.
        org.mtr.mod.data.TicketSystem.passThrough(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.BlockPos(pos),
                new org.mtr.mapping.holder.PlayerEntity(player),
                true, true,
                org.mtr.mod.SoundEvents.TICKET_BARRIER.get(),
                org.mtr.mod.SoundEvents.TICKET_BARRIER_CONCESSIONARY.get(),
                org.mtr.mod.SoundEvents.TICKET_BARRIER.get(),
                org.mtr.mod.SoundEvents.TICKET_BARRIER_CONCESSIONARY.get(),
                null, false,
                open -> {
                });
    }
}
