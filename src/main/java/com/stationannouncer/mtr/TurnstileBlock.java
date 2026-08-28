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
        OFF, GO, STOP, WAIT;

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

    /**
     * When each lit indicator is due to clear, keyed by the upper half's pos.
     * A scheduled tick only clears the lamp once this deadline has passed, so
     * a stale WAIT-clear tick landing while a fresh GO is showing (slow fare
     * callback) no longer wipes the GO early.
     */
    private static final Map<Long, Long> INDICATOR_DEADLINE = new HashMap<>();

    /** Drops all cooldown state (called when the server stops so nothing carries across worlds). */
    public static void clearAttempts() {
        LAST_ATTEMPT.clear();
        INDICATOR_DEADLINE.clear();
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
        // Covers the rebuilt tripod: horizontal blocking arm plus the 45°
        // through-bar whose ends reach down-forward and up-back.
        VoxelShape arm = createCuboidShape(3.0, 7.0, 3.0, 15.0, 16.0, 13.5);
        this.lowerCollision = rotations(cabinet);
        this.lowerOutline = rotations(net.minecraft.util.shape.VoxelShapes.union(cabinet, arm).simplify());
        // Pillar + cap, plus the riser pipe so the selection outline follows
        // the geometry all the way up (shapes above 16 are legal, fence-style).
        this.upperShape = rotations(net.minecraft.util.shape.VoxelShapes.union(
                createCuboidShape(0.0, 0.0, 3.5, 5.0, 11.0, 12.5),
                createCuboidShape(1.9, 11.0, 6.9, 4.1, 24.0, 9.1)).simplify());
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

        // MTR's only entry gate is balance >= 0 (bytecode-verified), so a
        // would-be entry that cannot succeed gets its red light and fail beep
        // NOW instead of after the async station lookup.
        if (allowEntry && !wasInside && balanceBefore < 0) {
            setIndicator(world, pos, Indicator.STOP, INDICATOR_TICKS);
            world.playSound(null, pos, org.mtr.mod.SoundEvents.TICKET_PROCESSOR_FAIL.get().data,
                    net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
            player.sendMessage(Text.translatable("gui.mtr.insufficient_balance", balanceBefore), true);
            return;
        }

        // Amber WAIT while MTR's async station lookup is in flight; the fare
        // callback replaces it with GO/STOP. The long clear tick only mops up
        // a WAIT orphaned by a server stop mid-lookup.
        setIndicator(world, pos, Indicator.WAIT, 100);

        // MTR handles the fare, the travel record and all feedback sounds;
        // exit-only lanes remind (not fine) walk-ups with no entry record.
        org.mtr.mod.data.TicketSystem.passThrough(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.BlockPos(pos),
                new org.mtr.mapping.holder.PlayerEntity(player),
                allowEntry, true,
                entrySound(), entrySoundConcessionary(),
                exitSound(), exitSoundConcessionary(),
                allowEntry ? null : org.mtr.mod.SoundEvents.TICKET_PROCESSOR_FAIL.get(),
                !allowEntry,
                open -> onFareResult(world, pos, player, open, wasInside, balanceBefore));
    }

    /**
     * The low turnstile beeps like MTR's ticket processors — distinct entry
     * and exit tones for free (the sound set ships in MTR, unused by us until
     * now). The HEET overrides these back to the barrier clunk: a full-height
     * rotor really does clunk.
     */
    protected org.mtr.mapping.holder.SoundEvent entrySound() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_ENTRY.get();
    }

    protected org.mtr.mapping.holder.SoundEvent entrySoundConcessionary() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_ENTRY_CONCESSIONARY.get();
    }

    protected org.mtr.mapping.holder.SoundEvent exitSound() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_EXIT.get();
    }

    protected org.mtr.mapping.holder.SoundEvent exitSoundConcessionary() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_EXIT_CONCESSIONARY.get();
    }

    /** Lights the reader-pillar lamp on the upper half and schedules its reset. */
    private void setIndicator(World world, BlockPos lowerPos, Indicator indicator, int clearTicks) {
        BlockPos upperPos = lowerPos.up();
        BlockState upper = world.getBlockState(upperPos);
        if (upper.isOf(this) && upper.get(HALF) == DoubleBlockHalf.UPPER) {
            world.setBlockState(upperPos, upper.with(INDICATOR, indicator));
            INDICATOR_DEADLINE.put(upperPos.asLong(), world.getTime() + clearTicks);
            world.scheduleBlockTick(upperPos, this, clearTicks);
        }
    }

    /** Empty-hand right-click on the unit = balance enquiry, like MTR's enquiry processor. */
    @Override
    public net.minecraft.util.ActionResult onUse(BlockState state, World world, BlockPos pos,
                                                 PlayerEntity player, net.minecraft.util.Hand hand,
                                                 net.minecraft.util.hit.BlockHitResult hit) {
        if (!player.getStackInHand(hand).isEmpty()) {
            return net.minecraft.util.ActionResult.PASS;
        }
        if (world.isClient) {
            return net.minecraft.util.ActionResult.SUCCESS;
        }
        int balance = org.mtr.mod.data.TicketSystem.getBalance(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.PlayerEntity(player));
        world.playSound(null, pos, org.mtr.mod.SoundEvents.TICKET_PROCESSOR_ENTRY.get().data,
                net.minecraft.sound.SoundCategory.BLOCKS, 0.6f, 1.0f);
        player.sendMessage(Text.translatable("msg.station_announcer.fare.balance", balance), true);
        return net.minecraft.util.ActionResult.CONSUME;
    }

    /**
     * True while MTR's scoreboard carries an entry-zone record for this rider.
     * MTR's own {@code entered()} requires ALL THREE zone scores nonzero (its
     * zone encoding guarantees every real entry sets all three), so this
     * matches that exactly rather than accepting any single nonzero score.
     */
    private static boolean hasEntryRecord(World world, PlayerEntity player) {
        net.minecraft.scoreboard.Scoreboard scoreboard = world.getScoreboard();
        for (String objectiveName : new String[]{"mtr_entry_zone_1", "mtr_entry_zone_2", "mtr_entry_zone_3"}) {
            net.minecraft.scoreboard.ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
            if (objective == null) {
                return false;
            }
            net.minecraft.scoreboard.ReadableScoreboardScore score = scoreboard.getScore(player, objective);
            if (score == null || score.getScore() == 0) {
                return false;
            }
        }
        return true;
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
        // Re-fetches inside: the callback is asynchronous and the block may be gone.
        setIndicator(world, lowerPos, go ? Indicator.GO : Indicator.STOP, INDICATOR_TICKS);
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
        if (state.get(INDICATOR) == Indicator.OFF) {
            return;
        }
        // A superseded tick (deadline moved forward by a newer set) waits its
        // turn; the newer set scheduled its own tick. A missing deadline
        // (server restart dropped the map) clears immediately.
        Long deadline = INDICATOR_DEADLINE.get(pos.asLong());
        if (deadline != null && world.getTime() < deadline) {
            return;
        }
        INDICATOR_DEADLINE.remove(pos.asLong());
        world.setBlockState(pos, state.with(INDICATOR, Indicator.OFF));
    }
}
