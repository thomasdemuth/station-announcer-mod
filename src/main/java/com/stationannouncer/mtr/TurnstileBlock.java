package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.Map;

/**
 * A subway turnstile lane wired into MTR's ticket system. Walking through
 * the lane calls MTR's own {@code TicketSystem.passThrough} — MTR decides
 * entry vs exit from the player's travel record and charges the same balance
 * its ticket barriers use, with MTR's own feedback sounds. The lane itself
 * never blocks movement (no animation, no barrier) — it is a walk-through
 * fare checkpoint.
 *
 * <p>The fare result drives the {@link #INDICATOR} lamp on the reader pillar
 * (green GO / red STOP, upper half's blockstate; a scheduled tick clears it)
 * and an action-bar balance readout, mirroring MTR's own ticket-barrier
 * pattern of state-set-in-callback plus scheduled-tick reset.
 *
 * <p>Exit-only lanes ({@code allowEntry = false}) process everyone as leaving:
 * riders with an entry record are charged normally, anyone else gets MTR's
 * "no record" reminder and a red light instead of a fare-evasion fine.
 *
 * <p>Cabinet on the lane's left, passage on the right; the next unit (or an
 * end cap) closes the lane. Two blocks tall; the overhead tubing (2.5 blocks)
 * bridges to the neighbor automatically via the RIGHT property.
 */
public class TurnstileBlock extends TurnstileBaseBlock {
    /** One fare event per player per lane within this window (one crossing = one charge). */
    private static final int RETRY_COOLDOWN_TICKS = 40;
    /** How long the GO / STOP lamp stays lit after a fare result. */
    private static final int INDICATOR_TICKS = 30;

    /** Reader-pillar lamp state, set from the fare callback on the UPPER half. */
    public static final EnumProperty<Indicator> INDICATOR = EnumProperty.of("indicator", Indicator.class);

    public enum Indicator implements StringIdentifiable {
        OFF, GO, STOP;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One lane + one player. */
    private record Crossing(long lane, java.util.UUID player) {
    }

    /** Last fare attempt per lane+player. */
    private static final Map<Crossing, Long> LAST_ATTEMPT = new HashMap<>();

    /** Drops all cooldown state (called when the server stops so nothing carries across worlds). */
    public static void clearAttempts() {
        LAST_ATTEMPT.clear();
    }

    /** False for exit-only lanes: everyone is processed as leaving the system. */
    private final boolean allowEntry;

    private final VoxelShape[] lowerCollision;
    private final VoxelShape[] lowerOutline;
    private final VoxelShape[] upperShape;

    public TurnstileBlock(Settings settings) {
        this(settings, true);
    }

    public TurnstileBlock(Settings settings, boolean allowEntry) {
        super(settings);
        this.allowEntry = allowEntry;
        setDefaultState(getDefaultState().with(INDICATOR, Indicator.OFF));
        VoxelShape cabinet = createCuboidShape(0.0, 0.0, 1.0, 5.0, 16.0, 15.0);
        VoxelShape arm = createCuboidShape(4.0, 9.0, 6.0, 14.0, 13.0, 10.0);
        this.lowerCollision = rotations(cabinet);
        this.lowerOutline = rotations(net.minecraft.util.shape.VoxelShapes.union(cabinet, arm).simplify());
        this.upperShape = rotations(createCuboidShape(0.0, 0.0, 4.0, 5.0, 10.0, 12.0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(INDICATOR);
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

        // Direction and fare are derived around MTR's own bookkeeping: a rider
        // with an entry-zone record on the scoreboard is inside the system, so
        // this pass is an exit; the fare is whatever the pass took off the
        // balance. Read BEFORE passThrough — it rewrites both.
        boolean wasInside = hasEntryRecord(world, player);
        int balanceBefore = org.mtr.mod.data.TicketSystem.getBalance(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.PlayerEntity(player));

        // MTR handles the fare, the travel record and all feedback sounds;
        // exit-only lanes remind (not fine) walk-ups with no entry record.
        org.mtr.mod.data.TicketSystem.passThrough(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.BlockPos(pos),
                new org.mtr.mapping.holder.PlayerEntity(player),
                allowEntry, true,
                org.mtr.mod.SoundEvents.TICKET_BARRIER.get(),
                org.mtr.mod.SoundEvents.TICKET_BARRIER_CONCESSIONARY.get(),
                org.mtr.mod.SoundEvents.TICKET_BARRIER.get(),
                org.mtr.mod.SoundEvents.TICKET_BARRIER_CONCESSIONARY.get(),
                allowEntry ? null : org.mtr.mod.SoundEvents.TICKET_PROCESSOR_FAIL.get(),
                !allowEntry,
                open -> onFareResult(world, pos, player, open, wasInside, balanceBefore));
    }

    /** True while MTR's scoreboard carries an entry-zone record for this rider. */
    private static boolean hasEntryRecord(World world, PlayerEntity player) {
        net.minecraft.scoreboard.Scoreboard scoreboard = world.getScoreboard();
        for (String objectiveName : new String[]{"mtr_entry_zone_1", "mtr_entry_zone_2", "mtr_entry_zone_3"}) {
            net.minecraft.scoreboard.ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
            if (objective != null) {
                net.minecraft.scoreboard.ReadableScoreboardScore score = scoreboard.getScore(player, objective);
                if (score != null && score.getScore() != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fare callback (MTR invokes it on the server thread, same as its own
     * ticket barrier which sets blockstate here). Lights the reader-pillar
     * lamp on the upper half and tells the rider what just happened:
     * "Entering <station>" or "Leaving <station> — Fare: $N", with the new
     * balance. The station comes from the same NEARBY_STATIONS request
     * {@code TicketSystem.passThrough} itself uses, so the name always agrees
     * with what MTR just charged for.
     */
    private void onFareResult(World world, BlockPos lowerPos, PlayerEntity player,
                              org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen open,
                              boolean wasInside, int balanceBefore) {
        boolean go = open == org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen.OPEN
                || open == org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen.OPEN_CONCESSIONARY;
        // Re-fetch: the callback is asynchronous and the block may be gone.
        BlockPos upperPos = lowerPos.up();
        BlockState upper = world.getBlockState(upperPos);
        if (upper.isOf(this) && upper.get(HALF) == DoubleBlockHalf.UPPER) {
            world.setBlockState(upperPos, upper.with(INDICATOR, go ? Indicator.GO : Indicator.STOP));
            world.scheduleBlockTick(upperPos, this, INDICATOR_TICKS);
        }
        if (!go || player.isRemoved()) {
            return;
        }
        int balance = org.mtr.mod.data.TicketSystem.getBalance(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.PlayerEntity(player));
        int fare = balanceBefore - balance;
        org.mtr.mod.Init.sendMessageC2S(
                org.mtr.core.servlet.OperationProcessor.NEARBY_STATIONS,
                new org.mtr.mapping.holder.MinecraftServer(world.getServer()),
                new org.mtr.mapping.holder.World(world),
                new org.mtr.core.operation.NearbyAreasRequest<org.mtr.core.data.Station, org.mtr.core.data.Platform>(
                        org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(lowerPos)), 0),
                (org.mtr.core.operation.NearbyAreasResponse response) -> {
                    if (player.isRemoved()) {
                        return;
                    }
                    String station = response.getStations().isEmpty()
                            ? null : firstLang(response.getStations().get(0).getName());
                    Text message;
                    if (wasInside) {
                        message = station == null
                                ? Text.translatable("gui.station_announcer.turnstile_exit_nostation", fare, balance)
                                : Text.translatable("gui.station_announcer.turnstile_exit", station, fare, balance);
                    } else {
                        message = station == null
                                ? Text.translatable("gui.station_announcer.turnstile_balance", balance)
                                : Text.translatable("gui.station_announcer.turnstile_enter", station, balance);
                    }
                    player.sendMessage(message, true);
                },
                org.mtr.core.operation.NearbyAreasResponse.class);
    }

    /** MTR names are "English|Other" — show the first language only. */
    private static String firstLang(String name) {
        int bar = name.indexOf('|');
        return bar < 0 ? name : name.substring(0, bar);
    }

    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (state.get(INDICATOR) != Indicator.OFF) {
            world.setBlockState(pos, state.with(INDICATOR, Indicator.OFF));
        }
    }
}
