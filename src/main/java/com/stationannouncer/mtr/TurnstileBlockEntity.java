package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Animation + lock bookkeeping for a fare lane (low turnstile lower half,
 * HEET lane_lower cell). The server stamps {@link #turnStart}/{@link #turnDir}
 * when MTR approves a passage and {@link #denyStart} when it refuses; the
 * client renderer reads them against the world clock, so nothing is ticked
 * on the client. Who unlocked the lane and when lives server-side only —
 * the block's ticker re-locks once that rider has cleared the barrier.
 */
public class TurnstileBlockEntity extends BlockEntity {
    /** Game time the current arm/rotor turn started, or -1. */
    private long turnStart = -1;
    /** +1 = the barrier turns in the FACING direction (entering), -1 = against it. */
    private int turnDir = 1;
    /** Game time of the last refused attempt (shudder animation), or -1. */
    private long denyStart = -1;

    // ---- server-only lock state (never synced)
    private long openedAt = -1;
    @Nullable
    private UUID rider;

    public TurnstileBlockEntity(BlockPos pos, BlockState state) {
        super(MtrStationDecor.TURNSTILE_BLOCK_ENTITY, pos, state);
    }

    public long turnStart() {
        return turnStart;
    }

    public int turnDir() {
        return turnDir;
    }

    public long denyStart() {
        return denyStart;
    }

    public long openedAt() {
        return openedAt;
    }

    @Nullable
    public UUID rider() {
        return rider;
    }

    /** Server: a passage was approved — remember who for re-locking, start the turn. */
    public void unlock(long now, int dir, UUID who) {
        openedAt = now;
        rider = who;
        turnStart = now;
        turnDir = dir;
        sync();
    }

    /** Server: the barrier has closed again behind the rider. */
    public void locked() {
        openedAt = -1;
        rider = null;
    }

    /** Server: a refused attempt — the arm rattles against its lock. */
    public void deny(long now) {
        denyStart = now;
        sync();
    }

    /** Dev rig only: pose the animation locally (client-side BE) for screenshots. */
    public void devPose(long turnStart, int turnDir, long denyStart) {
        this.turnStart = turnStart;
        this.turnDir = turnDir;
        this.denyStart = denyStart;
    }

    private void sync() {
        markDirty();
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("TurnStart", turnStart);
        nbt.putInt("TurnDir", turnDir);
        nbt.putLong("DenyStart", denyStart);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        turnStart = nbt.contains("TurnStart") ? nbt.getLong("TurnStart") : -1;
        turnDir = nbt.contains("TurnDir") ? nbt.getInt("TurnDir") : 1;
        denyStart = nbt.contains("DenyStart") ? nbt.getLong("DenyStart") : -1;
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }
}
