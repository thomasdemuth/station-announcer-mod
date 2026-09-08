package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fare event shared by every barrier block: a rider pressing against a
 * LOCKED lane triggers MTR's own {@code TicketSystem.passThrough} — MTR
 * decides entry vs exit from the travel record, charges the balance its
 * barriers use and plays its feedback sounds. On approval the {@link Host}
 * unlocks (collision drops, the arm/rotor turns); on refusal it shows STOP
 * and rattles. The host's block-entity ticker ({@link #tickLock}) re-locks
 * once the rider's box has fully cleared the barrier plane, or 5 s after an
 * unlock nobody used — never while a body still straddles the plane, which
 * is what kept the old blocking design from ejecting players.
 */
public final class FareLane {
    /** One fare attempt per player per lane within this window. */
    private static final int RETRY_COOLDOWN_TICKS = 40;
    /** How long the GO / STOP lamps stay lit after a fare result. */
    public static final int INDICATOR_TICKS = 40;
    /** An approved but unused unlock closes again after this. */
    private static final int UNLOCK_TIMEOUT_TICKS = 100;
    /** Ticks after unlocking before the ticker will consider re-locking. */
    private static final int MIN_OPEN_TICKS = 4;
    /** How far past the barrier plane the rider's centre must be (blocks). */
    private static final double CLEAR_DISTANCE = 0.45;

    private record Crossing(long lane, java.util.UUID player) {
    }

    private static final Map<Crossing, Long> LAST_ATTEMPT = new HashMap<>();

    /** Drops all cooldown state (server stop). */
    public static void clearAttempts() {
        LAST_ATTEMPT.clear();
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("station-announcer-fare");

    private FareLane() {
    }

    /** What a barrier block must provide. All calls arrive on the server thread. */
    public interface Host {
        /** False for exit-only lanes: everyone is processed as leaving. */
        boolean allowEntry();

        /** Direction an entering rider walks (unpaid → paid). */
        Direction facing(BlockState state);

        /** Centre of the barrier plane in world coordinates (the wall the rider must clear). */
        Vec3d barrierCentre(BlockState state, BlockPos pos);

        /** Half-width of the barrier plane along the lane (blocks). */
        double barrierHalfWidth();

        boolean isOpen(BlockState state);

        void setOpen(World world, BlockPos pos, BlockState state, boolean open);

        void setIndicator(World world, BlockPos pos, TurnstileBlock.Indicator indicator, int clearTicks);

        org.mtr.mapping.holder.SoundEvent entrySound();

        org.mtr.mapping.holder.SoundEvent entrySoundConcessionary();

        org.mtr.mapping.holder.SoundEvent exitSound();

        org.mtr.mapping.holder.SoundEvent exitSoundConcessionary();
    }

    /**
     * Called from {@code onEntityCollision} of the lane cell (server only).
     * A rider inside an OPEN lane is mid-passage and is left alone.
     */
    public static void onRiderInLane(Host host, BlockState state, World world, BlockPos mutablePos, Entity entity,
                                     TurnstileBlockEntity be) {
        if (!(entity instanceof PlayerEntity player) || host.isOpen(state)) {
            return;
        }
        // onEntityCollision hands over a REUSED mutable BlockPos; MTR's fare
        // callback lands ticks later, by which time it points somewhere else.
        BlockPos pos = mutablePos.toImmutable();
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

        // Which way is this rider trying to go? Behind the barrier plane
        // (relative to FACING) means entering.
        int dir = travelDirection(host, state, pos, player);

        boolean wasInside = hasEntryRecord(world, player);
        int balanceBefore = org.mtr.mod.data.TicketSystem.getBalance(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.PlayerEntity(player));

        // MTR's only entry gate is balance >= 0, so a would-be entry that
        // cannot succeed gets its red light and fail beep now.
        if (host.allowEntry() && !wasInside && balanceBefore < 0) {
            refuse(host, world, pos, be);
            world.playSound(null, pos, org.mtr.mod.SoundEvents.TICKET_PROCESSOR_FAIL.get().data,
                    net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
            player.sendMessage(Text.translatable("gui.mtr.insufficient_balance", balanceBefore), true);
            return;
        }

        host.setIndicator(world, pos, TurnstileBlock.Indicator.WAIT, 100);
        boolean allowEntry = host.allowEntry();
        LOGGER.debug("fare attempt at {} by {}: dir={} wasInside={} balance={}", pos.toShortString(),
                player.getGameProfile().getName(), dir, wasInside, balanceBefore);
        org.mtr.mod.data.TicketSystem.passThrough(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.BlockPos(pos),
                new org.mtr.mapping.holder.PlayerEntity(player),
                allowEntry, true,
                host.entrySound(), host.entrySoundConcessionary(),
                host.exitSound(), host.exitSoundConcessionary(),
                allowEntry ? null : org.mtr.mod.SoundEvents.TICKET_PROCESSOR_FAIL.get(),
                !allowEntry,
                open -> onFareResult(host, world, pos, player, dir, open, wasInside, balanceBefore));
    }

    private static int travelDirection(Host host, BlockState state, BlockPos pos, PlayerEntity player) {
        Vec3d facing = Vec3d.of(host.facing(state).getVector());
        Vec3d rel = player.getPos().subtract(host.barrierCentre(state, pos));
        return rel.dotProduct(facing) < 0 ? 1 : -1;
    }

    private static void refuse(Host host, World world, BlockPos pos, TurnstileBlockEntity be) {
        host.setIndicator(world, pos, TurnstileBlock.Indicator.STOP, INDICATOR_TICKS);
        if (be != null) {
            be.deny(world.getTime());
        }
    }

    private static void onFareResult(Host host, World world, BlockPos pos, PlayerEntity player, int dir,
                                     org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen open,
                                     boolean wasInside, int balanceBefore) {
        boolean go = open == org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen.OPEN
                || open == org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen.OPEN_CONCESSIONARY;
        // The callback is asynchronous: re-fetch, the block may be gone.
        BlockState state = world.getBlockState(pos);
        LOGGER.debug("fare result at {}: {} (go={})", pos.toShortString(), open, go);
        if (!(world.getBlockEntity(pos) instanceof TurnstileBlockEntity be)) {
            LOGGER.warn("fare result at {} but no lane block entity", pos.toShortString());
            return;
        }
        if (!go) {
            refuse(host, world, pos, be);
            return;
        }
        host.setIndicator(world, pos, TurnstileBlock.Indicator.GO, INDICATOR_TICKS);
        host.setOpen(world, pos, state, true);
        be.unlock(world.getTime(), dir, player.getUuid());
        if (player.isRemoved()) {
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
                        org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos)), 0),
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

    /**
     * Server ticker for the lane cell: re-lock once the rider who unlocked
     * has cleared the barrier plane (or the unlock timed out), but only when
     * no body is straddling the plane.
     */
    public static void tickLock(Host host, World world, BlockPos pos, BlockState state, TurnstileBlockEntity be) {
        if (world.isClient || !host.isOpen(state)) {
            return;
        }
        long now = world.getTime();
        if (be.openedAt() < 0) {
            // Open with no record (server restart mid-passage): close once clear.
            if (planeClear(host, world, pos, state)) {
                host.setOpen(world, pos, state, false);
            }
            return;
        }
        long open = now - be.openedAt();
        if (open < MIN_OPEN_TICKS) {
            return;
        }
        boolean crossed = false;
        PlayerEntity rider = be.rider() == null ? null : world.getPlayerByUuid(be.rider());
        if (rider != null) {
            Vec3d facing = Vec3d.of(host.facing(state).getVector()).multiply(be.turnDir());
            double along = rider.getPos().subtract(host.barrierCentre(state, pos)).dotProduct(facing);
            crossed = along > CLEAR_DISTANCE;
        }
        if ((crossed || rider == null || open > UNLOCK_TIMEOUT_TICKS) && planeClear(host, world, pos, state)) {
            LOGGER.debug("re-lock at {}: crossed={} riderPresent={} openTicks={}", pos.toShortString(),
                    crossed, rider != null, open);
            host.setOpen(world, pos, state, false);
            be.locked();
        }
    }

    /** True when no entity's box overlaps the barrier plane (with a little slack). */
    private static boolean planeClear(Host host, World world, BlockPos pos, BlockState state) {
        Vec3d c = host.barrierCentre(state, pos);
        Direction facing = host.facing(state);
        double hw = host.barrierHalfWidth();
        double along = 0.15;
        Box box = facing.getAxis() == Direction.Axis.Z
                ? new Box(c.x - hw, pos.getY(), c.z - along, c.x + hw, pos.getY() + 2.0, c.z + along)
                : new Box(c.x - along, pos.getY(), c.z - hw, c.x + along, pos.getY() + 2.0, c.z + hw);
        List<Entity> inside = world.getOtherEntities(null, box, e -> !e.isSpectator());
        return inside.isEmpty();
    }

    /** True while MTR's scoreboard carries an entry-zone record for this rider (all three zones, like MTR's entered()). */
    static boolean hasEntryRecord(World world, PlayerEntity player) {
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

    /** MTR names are "English|Other" — show the first language only. */
    static String firstLang(String name) {
        int bar = name.indexOf('|');
        return bar < 0 ? name : name.substring(0, bar);
    }
}
