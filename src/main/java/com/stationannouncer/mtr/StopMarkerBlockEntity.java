package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The plates on a stop marker: one to four small squares, each with its own
 * background colour and a short legend (a car count like "8", a division
 * letter like "S", or "OPTO"). Edited with the MTR brush.
 *
 * <p>Only the plates live here — where the marker hangs from and whether a
 * wall unit stands off on a bracket are blockstate properties, so the pole
 * geometry comes from the model.
 */
public class StopMarkerBlockEntity extends BlockEntity {
    public static final int MAX_SIGNS = 4;
    public static final int MAX_TEXT_LENGTH = 4;

    /** Plate colours, after the real enamel signs: black car-stops, white markers, yellow OPTO. */
    public enum SignColor implements StringIdentifiable {
        BLACK("black", 0xFF0C0C0E, 0xFFF2F2F2),
        WHITE("white", 0xFFEDEDE8, 0xFF121212),
        YELLOW("yellow", 0xFFF2C21A, 0xFF141414);

        public final String id;
        /** Plate face colour. */
        public final int background;
        /** Legend colour that reads against it. */
        public final int text;

        SignColor(String id, int background, int text) {
            this.id = id;
            this.background = background;
            this.text = text;
        }

        @Override
        public String asString() {
            return id;
        }

        static SignColor byId(String id) {
            for (SignColor color : values()) {
                if (color.id.equals(id)) {
                    return color;
                }
            }
            return BLACK;
        }
    }

    /** One plate. */
    public record Sign(SignColor color, String text) {
        public Sign {
            color = color == null ? SignColor.BLACK : color;
            text = text == null ? "" : text;
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
        }
    }

    /** Top to bottom; always at least one, never more than {@link #MAX_SIGNS}. */
    private final List<Sign> signs = new ArrayList<>(List.of(new Sign(SignColor.BLACK, "")));

    public StopMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(MtrStationDecor.STOP_MARKER_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        NbtList list = new NbtList();
        for (Sign sign : signs) {
            NbtCompound entry = new NbtCompound();
            entry.putString("Color", sign.color().id);
            entry.putString("Text", sign.text());
            list.add(entry);
        }
        nbt.put("Signs", list);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        List<Sign> read = new ArrayList<>();
        NbtList list = nbt.getList("Signs", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size() && read.size() < MAX_SIGNS; i++) {
            NbtCompound entry = list.getCompound(i);
            read.add(new Sign(SignColor.byId(entry.getString("Color")), entry.getString("Text")));
        }
        setSigns(read);
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

    /** The plates, top first. Never empty. */
    public List<Sign> getSigns() {
        return signs;
    }

    /** Replaces the plate list, clamped to 1..{@link #MAX_SIGNS} entries. */
    public void setSigns(List<Sign> replacement) {
        signs.clear();
        if (replacement != null) {
            for (Sign sign : replacement) {
                if (signs.size() >= MAX_SIGNS) {
                    break;
                }
                if (sign != null) {
                    signs.add(sign);
                }
            }
        }
        if (signs.isEmpty()) {
            signs.add(new Sign(SignColor.BLACK, ""));
        }
    }
}
