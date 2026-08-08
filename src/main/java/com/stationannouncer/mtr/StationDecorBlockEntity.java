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

    /** Holding-light timing: this means "use the light's own default". */
    public static final int UNSET_SECONDS = -1;
    public static final int MAX_LIGHT_SECONDS = 60;

    /**
     * Yellow holding lights only: what the light does about the dispatch addon's
     * platform hold rules (Feature 1). A held train is the one case where the
     * arrival timetable says "go" and the dispatcher says "wait", so it gets its
     * own flashing state rather than sharing the steady dwell light.
     */
    public enum HoldIndicator {
        /** Hold rules are ignored; the light follows arrivals only (the old behaviour). */
        OFF,
        /** Arrivals as usual, plus flashing for as long as a hold rule holds the train. */
        FLASH,
        /** Dark except while a train is being held — a dedicated "HOLD" indicator. */
        ONLY;

        public boolean followsHoldRules() {
            return this != OFF;
        }

        static HoldIndicator byOrdinal(int ordinal) {
            HoldIndicator[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : OFF;
        }
    }

    private String customName = "";

    /** Holding lights only: seconds before the cue to light up / to go dark again. */
    private int lightOnSeconds = UNSET_SECONDS;
    private int lightOffSeconds = UNSET_SECONDS;

    /** Yellow holding lights only: how this light reacts to platform hold rules. */
    private HoldIndicator holdIndicator = HoldIndicator.OFF;

    public StationDecorBlockEntity(BlockPos pos, BlockState state) {
        super(MtrStationDecor.DECOR_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("CustomName", customName);
        nbt.putInt("LightOnSeconds", lightOnSeconds);
        nbt.putInt("LightOffSeconds", lightOffSeconds);
        nbt.putByte("HoldIndicator", (byte) holdIndicator.ordinal());
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setCustomName(nbt.getString("CustomName"));
        // Absent on blocks placed before the timing was adjustable: those keep
        // whatever their light's built-in default is.
        setLightOnSeconds(nbt.contains("LightOnSeconds") ? nbt.getInt("LightOnSeconds") : UNSET_SECONDS);
        setLightOffSeconds(nbt.contains("LightOffSeconds") ? nbt.getInt("LightOffSeconds") : UNSET_SECONDS);
        // Absent on blocks placed before hold rules existed: those ignore them.
        setHoldIndicator(HoldIndicator.byOrdinal(nbt.getByte("HoldIndicator")));
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

    /** Seconds before the cue that the light comes on; {@link #UNSET_SECONDS} = the light's default. */
    public int getLightOnSeconds() {
        return lightOnSeconds;
    }

    public void setLightOnSeconds(int seconds) {
        this.lightOnSeconds = clampSeconds(seconds);
    }

    /** Seconds around the cue that the light goes dark again; {@link #UNSET_SECONDS} = the light's default. */
    public int getLightOffSeconds() {
        return lightOffSeconds;
    }

    public void setLightOffSeconds(int seconds) {
        this.lightOffSeconds = clampSeconds(seconds);
    }

    /** How this light reacts to platform hold rules (yellow holding lights only). */
    public HoldIndicator getHoldIndicator() {
        return holdIndicator;
    }

    public void setHoldIndicator(HoldIndicator holdIndicator) {
        this.holdIndicator = holdIndicator == null ? HoldIndicator.OFF : holdIndicator;
    }

    private static int clampSeconds(int seconds) {
        if (seconds < 0) {
            return UNSET_SECONDS;
        }
        return Math.min(seconds, MAX_LIGHT_SECONDS);
    }

    /** The configured value, or {@code fallback} when this block is left on automatic. */
    public int lightSecondsOr(int configured, int fallback) {
        return configured == UNSET_SECONDS ? fallback : configured;
    }
}
