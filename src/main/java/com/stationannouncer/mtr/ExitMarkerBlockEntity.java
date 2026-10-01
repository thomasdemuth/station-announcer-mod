package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * An exit marker's copy of its pin: which station, which exit (MTR's name,
 * e.g. "A2"; empty = not pinned yet). The world's truth is
 * {@link com.stationannouncer.wayfinding.WayfindingStore}; this copy exists
 * so the client can draw the label and so pastes carry the pin along.
 */
public class ExitMarkerBlockEntity extends BlockEntity {
    private long stationId;
    private String exitName = "";

    /** Server-only, never saved: has this block been reconciled with the store since it loaded? */
    boolean reconciled;

    public ExitMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(Wayfinding.EXIT_MARKER_BLOCK_ENTITY, pos, state);
    }

    public long getStationId() {
        return stationId;
    }

    public String getExitName() {
        return exitName;
    }

    /** Server thread. */
    void apply(long station, String exit) {
        String name = exit == null ? "" : exit;
        if (station == stationId && name.equals(exitName)) {
            return;
        }
        stationId = station;
        exitName = name;
        sync();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("Station", stationId);
        nbt.putString("Exit", exitName);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        stationId = nbt.getLong("Station");
        exitName = nbt.getString("Exit");
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

    void sync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
    }
}
