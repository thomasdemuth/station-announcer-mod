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
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/**
 * A looping ambience source (station hum / air vent). Purely configuration:
 * which loop, how loud, how far. The actual looping sound is a client-side
 * concern (AmbienceSoundManager scans for these block entities and keeps one
 * sound instance alive per block).
 */
public class AmbienceBlockEntity extends BlockEntity {
    public static final int MIN_RADIUS = 4;
    public static final int MAX_RADIUS = 48;
    public static final String SOUND_HUM = "hum";
    public static final String SOUND_VENT = "vent";

    private String sound = SOUND_HUM;
    private int volume = 60; // 0..100 (%)
    private int radius = 16; // 4..48 blocks

    public AmbienceBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.AMBIENCE_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("Sound", sound);
        nbt.putInt("Volume", volume);
        nbt.putInt("Radius", radius);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setSound(nbt.getString("Sound"));
        setVolume(nbt.contains("Volume") ? nbt.getInt("Volume") : 60);
        setRadius(nbt.contains("Radius") ? nbt.getInt("Radius") : 16);
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

    public String getSound() {
        return sound;
    }

    public void setSound(String sound) {
        this.sound = SOUND_VENT.equals(sound) ? SOUND_VENT : SOUND_HUM;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = MathHelper.clamp(volume, 0, 100);
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = MathHelper.clamp(radius, MIN_RADIUS, MAX_RADIUS);
    }

    /** Applies a settings update (from the GUI packet), persists and syncs. */
    public void applySettings(String sound, int volume, int radius) {
        setSound(sound);
        setVolume(volume);
        setRadius(radius);
        sync();
    }
}
