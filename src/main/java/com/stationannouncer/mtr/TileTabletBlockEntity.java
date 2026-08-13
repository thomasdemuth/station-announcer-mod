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
 * The black name tablet set into a tile wall. Holds one synced field: the text
 * on it, empty meaning "follow the MTR station this block stands in" — the
 * same rule the mosaic sign and the entrance sign use.
 *
 * <p>Which of the block's four tile rows the tablet sits on is a blockstate
 * property, not a field here: it is geometry, and a chunk that has never
 * loaded the block entity still needs to know where the tablet goes.</p>
 */
public class TileTabletBlockEntity extends BlockEntity {
    public static final int MAX_TEXT_LENGTH = 48;

    private String text = "";

    public TileTabletBlockEntity(BlockPos pos, BlockState state) {
        super(SubwayWalls.TILE_TABLET_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("Text", text);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setText(nbt.getString("Text"));
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

    /** The tablet's text; "" means "use the MTR station's name". */
    public String getText() {
        return text;
    }

    public void setText(String value) {
        String trimmed = value == null ? "" : value.trim();
        this.text = trimmed.length() > MAX_TEXT_LENGTH ? trimmed.substring(0, MAX_TEXT_LENGTH) : trimmed;
    }
}
