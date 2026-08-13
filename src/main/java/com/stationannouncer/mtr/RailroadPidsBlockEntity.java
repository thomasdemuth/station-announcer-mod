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
 * Settings for a Railroad PIDS: which platforms it watches, and which kinds of
 * line may appear as a connection on the station list.
 *
 * <p>Unlike the NYC PIDS blocks these are plain vanilla block entities rather
 * than MTR {@code BlockEntityBase}s — the brush opens our own screen, so there
 * is no reason to keep the configuration in MTR's storage.</p>
 */
public class RailroadPidsBlockEntity extends BlockEntity {
    /** Plenty for one board; also the cap the update packet enforces. */
    public static final int MAX_PLATFORMS = 16;

    private static final long[] NO_PLATFORMS = new long[0];

    /**
     * Connection line classes, as a bitmask over {@code TransportMode.ordinal()}
     * so the mask survives MTR adding a mode. Trains only by default: a
     * railroad board is opting IN to ferries, planes and cable cars.
     */
    public static final int MODE_COUNT = 8; // room to spare if MTR adds a mode
    public static final int DEFAULT_MODES = 1; // TransportMode.TRAIN

    /**
     * What a hanging board puts on its two screens. Ignored by the wall and
     * standing boards, which only ever draw the one.
     */
    public enum DisplayMode {
        /** Alternate on the interval. */
        FLIP,
        /** The next-train screen only. */
        NEXT_TRAIN,
        /** The four-departure list only. */
        DEPARTURES,
        /** Next train while one is close, the departure list the rest of the time. */
        AUTO;

        public static DisplayMode byOrdinal(int ordinal) {
            DisplayMode[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : FLIP;
        }
    }

    public static final int MIN_FLIP_SECONDS = 3;
    public static final int MAX_FLIP_SECONDS = 60;
    public static final int DEFAULT_FLIP_SECONDS = 14;

    public static final int MAX_TITLE_LENGTH = 64;

    /**
     * How long before departure the track number appears, in seconds.
     *
     * <p>Real boards withhold the track until the platform is committed, and
     * the crowd moves when it appears — so this is the single most important
     * setting on the departure board. Default six minutes.</p>
     */
    public static final int MIN_TRACK_REVEAL_SECONDS = 0;
    public static final int MAX_TRACK_REVEAL_SECONDS = 60 * 60;
    public static final int DEFAULT_TRACK_REVEAL_SECONDS = 6 * 60;

    /** Watched platforms; empty means "auto-detect the closest one". */
    private long[] platformIds = NO_PLATFORMS;
    private int connectionModes = DEFAULT_MODES;
    private DisplayMode displayMode = DisplayMode.FLIP;
    private int flipSeconds = DEFAULT_FLIP_SECONDS;

    /** Departure-board only: header text, and when the track is announced. */
    private String title = "";
    private int trackRevealSeconds = DEFAULT_TRACK_REVEAL_SECONDS;

    public RailroadPidsBlockEntity(BlockPos pos, BlockState state) {
        // Same settings and same class throughout — but each shape draws a
        // completely different screen, and a block entity type may only carry
        // one renderer, so each registers as its own type.
        super(typeFor(state), pos, state);
    }

    private static net.minecraft.block.entity.BlockEntityType<RailroadPidsBlockEntity> typeFor(BlockState state) {
        // RailroadDepartureBlock extends RailroadPidsBlock, so it must be
        // tested first or it would come out as an ordinary wall board.
        if (state.getBlock() instanceof RailroadDepartureBlock) {
            return MtrPids.RAILROAD_DEPARTURE_BLOCK_ENTITY;
        }
        return state.getBlock() instanceof RailroadPidsHangingBlock
                ? MtrPids.RAILROAD_HANGING_BLOCK_ENTITY
                : MtrPids.RAILROAD_PIDS_BLOCK_ENTITY;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLongArray("Platforms", platformIds);
        nbt.putInt("ConnectionModes", connectionModes);
        nbt.putInt("DisplayMode", displayMode.ordinal());
        nbt.putInt("FlipSeconds", flipSeconds);
        nbt.putString("Title", title);
        nbt.putInt("TrackRevealSeconds", trackRevealSeconds);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setPlatformIds(nbt.getLongArray("Platforms"));
        // Absent for a block placed before this field existed — and 0 is a
        // legitimate value (every class off), so contains() rather than a
        // zero check.
        connectionModes = nbt.contains("ConnectionModes") ? nbt.getInt("ConnectionModes") : DEFAULT_MODES;
        displayMode = DisplayMode.byOrdinal(nbt.getInt("DisplayMode"));
        setFlipSeconds(nbt.contains("FlipSeconds") ? nbt.getInt("FlipSeconds") : DEFAULT_FLIP_SECONDS);
        setTitle(nbt.getString("Title"));
        // Absent on boards placed before the field existed, and 0 ("always
        // show the track") is a legitimate value - so contains(), not != 0.
        setTrackRevealSeconds(nbt.contains("TrackRevealSeconds")
                ? nbt.getInt("TrackRevealSeconds") : DEFAULT_TRACK_REVEAL_SECONDS);
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

    /** Watched platform ids; empty = auto-detect. Shared — read it, never modify it. */
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

    public int getConnectionModes() {
        return connectionModes;
    }

    public void setConnectionModes(int modes) {
        this.connectionModes = modes & ((1 << MODE_COUNT) - 1);
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(DisplayMode displayMode) {
        this.displayMode = displayMode == null ? DisplayMode.FLIP : displayMode;
    }

    /** Seconds each screen holds before the board flips. */
    public int getFlipSeconds() {
        return flipSeconds;
    }

    public void setFlipSeconds(int seconds) {
        this.flipSeconds = Math.max(MIN_FLIP_SECONDS, Math.min(MAX_FLIP_SECONDS, seconds));
    }

    /** Departure board header; empty falls back to the station name. */
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        String value = title == null ? "" : title;
        this.title = value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }

    /** Seconds before departure at which the track number is announced. */
    public int getTrackRevealSeconds() {
        return trackRevealSeconds;
    }

    public void setTrackRevealSeconds(int seconds) {
        this.trackRevealSeconds = Math.max(MIN_TRACK_REVEAL_SECONDS,
                Math.min(MAX_TRACK_REVEAL_SECONDS, seconds));
    }

    /** Whether connections on the given MTR transport mode ordinal are shown. */
    public boolean showsMode(int transportModeOrdinal) {
        return transportModeOrdinal >= 0 && (connectionModes & (1 << transportModeOrdinal)) != 0;
    }
}
