package com.stationannouncer.block;

import com.stationannouncer.ModContent;
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
 * The label on one block of a zebra board — the white plate reading "R-160",
 * "8" or "5 Car" that tells a train operator where to stop. Empty text = a
 * plain striped block, which is what every board placed before labels existed
 * keeps drawing.
 *
 * <p>The plate belongs to one block but is free to spill onto the blocks
 * beside it, so a label wider than a metre is placed by putting it on the
 * block at its middle (or at the end it should sit flush against).</p>
 */
public class ZebraBoardBlockEntity extends BlockEntity {
    public static final int MAX_TEXT_LENGTH = 16;

    /** Where the plate sits along the block, as seen from the board's front. */
    public enum Align {
        LEFT, CENTER, RIGHT;

        public Align next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static Align byOrdinal(int ordinal) {
            Align[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CENTER;
        }
    }

    private String text = "";
    private Align align = Align.CENTER;

    public ZebraBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.ZEBRA_BOARD_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("Label", text);
        nbt.putByte("Align", (byte) align.ordinal());
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setText(nbt.getString("Label"));
        setAlign(nbt.contains("Align") ? Align.byOrdinal(nbt.getByte("Align")) : Align.CENTER);
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

    /** The label text, exactly as typed; "" = no label. */
    public String getText() {
        return text;
    }

    public void setText(String value) {
        String trimmed = value == null ? "" : value.trim();
        this.text = trimmed.length() > MAX_TEXT_LENGTH ? trimmed.substring(0, MAX_TEXT_LENGTH) : trimmed;
    }

    public Align getAlign() {
        return align;
    }

    public void setAlign(Align align) {
        this.align = align == null ? Align.CENTER : align;
    }
}
