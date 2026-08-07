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
 * Block entity for the station decor blocks (mosaic name sign, named
 * columns, next-train sign). Holds one synced field: an optional custom name
 * that overrides the auto-detected MTR station name ("" = automatic).
 */
public class StationDecorBlockEntity extends BlockEntity {
    public static final int MAX_NAME_LENGTH = 64;

    private String customName = "";

    public StationDecorBlockEntity(BlockPos pos, BlockState state) {
        super(MtrStationDecor.DECOR_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("CustomName", customName);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setCustomName(nbt.getString("CustomName"));
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

    public void sync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
    }

    /** Custom name override; "" means "use the MTR station's name". */
    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        String value = customName == null ? "" : customName.trim();
        this.customName = value.length() > MAX_NAME_LENGTH ? value.substring(0, MAX_NAME_LENGTH) : value;
    }
}
