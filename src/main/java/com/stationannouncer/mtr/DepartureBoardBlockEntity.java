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
 * Settings for one cell of a big departure board.
 *
 * <p>Every block in a merged board carries these, and they are all kept
 * identical: {@link DepartureBoardBlock} copies the settings across the whole
 * rectangle whenever they are edited. Storing them on the origin alone would
 * look right until the board grew — adding a block to the left moves the
 * origin, and the settings would appear to vanish.</p>
 */
public class DepartureBoardBlockEntity extends BlockEntity {
    public static final int MAX_PLATFORMS = 32;
    public static final int MAX_TITLE_LENGTH = 64;

    private static final long[] NO_PLATFORMS = new long[0];

    /** Watched platforms; empty means "auto-detect the closest one". */
    private long[] platformIds = NO_PLATFORMS;

    /** Header text. Empty falls back to the operator name the renderer supplies. */
    private String title = "";

    /**
     * Seconds before departure at which the track number is announced —
     * identical semantics (and constants) to the small railroad departure
     * screen, so the two boards in one concourse agree.
     */
    private int trackRevealSeconds = RailroadPidsBlockEntity.DEFAULT_TRACK_REVEAL_SECONDS;

    public DepartureBoardBlockEntity(BlockPos pos, BlockState state) {
        super(state.getBlock() instanceof DepartureBoardBlock board && board.hanging
                ? MtrPids.DEPARTURE_BOARD_HANGING_BLOCK_ENTITY
                : MtrPids.DEPARTURE_BOARD_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLongArray("Platforms", platformIds);
        nbt.putString("Title", title);
        nbt.putInt("TrackRevealSeconds", trackRevealSeconds);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setPlatformIds(nbt.getLongArray("Platforms"));
        setTitle(nbt.getString("Title"));
        // Absent on boards placed before the reveal window existed: the default.
        setTrackRevealSeconds(nbt.contains("TrackRevealSeconds")
                ? nbt.getInt("TrackRevealSeconds") : RailroadPidsBlockEntity.DEFAULT_TRACK_REVEAL_SECONDS);
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

    /** Shared — read it, never modify it. */
    public long[] getPlatformIds() {
        return platformIds;
    }

    public void setPlatformIds(long[] ids) {
        if (ids == null || ids.length == 0) {
            platformIds = NO_PLATFORMS;
            return;
        }
        platformIds = ids.length <= MAX_PLATFORMS
                ? ids.clone()
                : java.util.Arrays.copyOf(ids, MAX_PLATFORMS);
    }

    public String getTitle() {
        return title;
    }

    /** Seconds before departure at which the track number is announced (0 = always shown). */
    public int getTrackRevealSeconds() {
        return trackRevealSeconds;
    }

    public void setTrackRevealSeconds(int seconds) {
        this.trackRevealSeconds = Math.max(RailroadPidsBlockEntity.MIN_TRACK_REVEAL_SECONDS,
                Math.min(RailroadPidsBlockEntity.MAX_TRACK_REVEAL_SECONDS, seconds));
    }

    public void setTitle(String title) {
        String value = title == null ? "" : title;
        this.title = value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }
}
