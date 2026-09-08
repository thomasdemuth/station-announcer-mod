package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Block entity for the station decor blocks (mosaic name sign, named
 * columns, next-train sign). Holds one synced field: an optional custom name
 * that overrides the auto-detected MTR station name ("" = automatic).
 */
public class StationDecorBlockEntity extends BlockEntity {
    public static final int MAX_NAME_LENGTH = 64;

    /** Railing signs: how many route bullets fit beside the name, and how long a route name may be. */
    public static final int MAX_ROUTE_BULLETS = 6;
    public static final int MAX_ROUTE_NAME_LENGTH = 64;

    /** Holding-light timing: this means "use the light's own default". */
    public static final int UNSET_SECONDS = -1;
    public static final int MAX_LIGHT_SECONDS = 60;

    /**
     * Yellow holding lights only: what the light does about the dispatch addon's
     * platform hold rules (Feature 1). A held train is the one case where the
     * arrival timetable says "go" and the dispatcher says "wait", so the light
     * stays lit through it instead of going out on the timetable's cue.
     *
     * <p>The constants keep their order: the mode is stored as an ordinal, so
     * renaming one is safe but reordering them would silently re-point every
     * light already placed.</p>
     */
    public enum HoldIndicator {
        /** Hold rules are ignored; the light follows arrivals only (the old behaviour). */
        OFF,
        /** Arrivals as usual, plus staying lit for as long as a hold rule holds the train. */
        HELD,
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

    /**
     * Railing signs only: whether each face of the panel carries a sign. Both
     * faces on is the default (and what every sign placed before this existed
     * did); turning one off leaves the plain railing on that side.
     */
    private boolean signFront = true;
    private boolean signBack = true;

    /** Railing signs only: the routes shown as bullets on each face, by MTR route name. */
    private List<String> frontRoutes = List.of();
    private List<String> backRoutes = List.of();

    /**
     * MTA-style sign content (the {@code mta_sign} blocks, and any of the older
     * text signs once edited in the sign editor). Null = never set: the
     * renderer then derives a sign from the legacy fields above, so every
     * placement from before the sign system draws exactly as it always did.
     */
    @Nullable
    private com.stationannouncer.mtr.sign.SignFaces sign;

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
        nbt.putBoolean("SignFront", signFront);
        nbt.putBoolean("SignBack", signBack);
        nbt.put("RoutesFront", routeList(frontRoutes));
        nbt.put("RoutesBack", routeList(backRoutes));
        if (sign != null) {
            nbt.putString("Sign", sign.toJson().toString());
        }
    }

    private static NbtList routeList(List<String> routes) {
        NbtList list = new NbtList();
        for (String route : routes) {
            list.add(NbtString.of(route));
        }
        return list;
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
        // Absent on signs placed before the faces were switchable: both are on.
        setSignFront(!nbt.contains("SignFront") || nbt.getBoolean("SignFront"));
        setSignBack(!nbt.contains("SignBack") || nbt.getBoolean("SignBack"));
        setFrontRoutes(readRoutes(nbt, "RoutesFront"));
        setBackRoutes(readRoutes(nbt, "RoutesBack"));
        sign = nbt.contains("Sign") ? com.stationannouncer.mtr.sign.SignFaces.parse(nbt.getString("Sign")) : null;
    }

    // ------------------------------------------------------------ signs

    /** The MTA-style sign on this block, or null when it was never edited as one. */
    @Nullable
    public com.stationannouncer.mtr.sign.SignFaces getSign() {
        return sign;
    }

    public void setSign(@Nullable com.stationannouncer.mtr.sign.SignFaces sign) {
        this.sign = sign;
    }

    private static List<String> readRoutes(NbtCompound nbt, String key) {
        NbtList list = nbt.getList(key, NbtElement.STRING_TYPE);
        List<String> routes = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            routes.add(list.getString(i));
        }
        return routes;
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

    // -------------------------------------------------------- railing signs

    /** Whether the panel's front face (the block's first sign side) carries a sign. */
    public boolean isSignFront() {
        return signFront;
    }

    public void setSignFront(boolean signFront) {
        this.signFront = signFront;
    }

    /** Whether the panel's back face carries a sign. */
    public boolean isSignBack() {
        return signBack;
    }

    public void setSignBack(boolean signBack) {
        this.signBack = signBack;
    }

    /** Route names shown as bullets on the front face. */
    public List<String> getFrontRoutes() {
        return frontRoutes;
    }

    public void setFrontRoutes(List<String> routes) {
        this.frontRoutes = clampRoutes(routes);
    }

    /** Route names shown as bullets on the back face. */
    public List<String> getBackRoutes() {
        return backRoutes;
    }

    public void setBackRoutes(List<String> routes) {
        this.backRoutes = clampRoutes(routes);
    }

    private static List<String> clampRoutes(List<String> routes) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        List<String> clamped = new ArrayList<>(Math.min(routes.size(), MAX_ROUTE_BULLETS));
        for (String route : routes) {
            if (clamped.size() >= MAX_ROUTE_BULLETS) {
                break;
            }
            if (route == null || route.isEmpty()) {
                continue;
            }
            clamped.add(route.length() > MAX_ROUTE_NAME_LENGTH
                    ? route.substring(0, MAX_ROUTE_NAME_LENGTH) : route);
        }
        return List.copyOf(clamped);
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
