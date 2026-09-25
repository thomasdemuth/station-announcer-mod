package com.stationannouncer.mtr;

import com.stationannouncer.mtraddon.GapFillerEngine;
import com.stationannouncer.mtraddon.GapFillerStore;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * One gap filler's link to its MTR platform, plus mirrors of that platform's
 * timing for the renderer (plate speed) and the brush screen.
 *
 * <p>The block entity is the bridge between the simulator-side sequencing
 * and the world: every other tick it asks
 * {@link GapFillerEngine#phaseFor} what its platform's fillers are doing and
 * writes that into the blockstate ({@link GapFillerBlock#PHASE} — collision
 * follows it, the client animates from it), plays the move/bang sounds on
 * the transitions, and reports anybody standing on the extended plate so the
 * fillers never retract from under a rider.</p>
 *
 * <p>Linking is lazy and self-healing: an unlinked filler asks
 * {@link GapFillers#link} for the nearest platform every 10 s, so items,
 * /setblock, /fill and pastes all end up linked. A linked filler registers
 * itself with {@link GapFillerStore} from its first tick (idempotent), which is
 * what switches the interlock on for its platform.</p>
 */
public class GapFillerBlockEntity extends BlockEntity {
    private static final int LINK_RETRY_TICKS = 200;

    private long platformId;
    private String platformLabel = "";
    /** Reach follows the rail until the player sets it by hand. */
    private boolean reachAuto = true;
    private int extendMs;
    private int retractMs;
    private int minDwellMs;

    // server-only, never saved
    private long nextLinkAttempt;
    private long registeredPlatform;

    /**
     * Client-only animation state for the renderer: how far the plate is out
     * (0..1 of its reach; -1 = not drawn yet, snap to the state instead of
     * sliding in from nothing when the chunk loads) and the last frame time.
     */
    public float clientTravel = -1f;
    public long clientLastNanos;

    public GapFillerBlockEntity(BlockPos pos, BlockState state) {
        super(GapFillers.GAP_FILLER_BLOCK_ENTITY, pos, state);
        GapFillerStore.Settings defaults = style(state).defaults;
        extendMs = defaults.extendMs();
        retractMs = defaults.retractMs();
        minDwellMs = defaults.minDwellMs();
    }

    static GapFillerBlock.Style style(BlockState state) {
        return state.getBlock() instanceof GapFillerBlock filler ? filler.style : GapFillerBlock.Style.UNION;
    }

    // ------------------------------------------------------------ server tick

    static void serverTick(World world, BlockPos pos, BlockState state, GapFillerBlockEntity be) {
        if (!(world instanceof ServerWorld serverWorld) || ((world.getTime() + pos.asLong()) & 1) != 0) {
            return;
        }
        GapFillerPhase target = GapFillerPhase.RETRACTED;
        if (be.platformId == 0) {
            if (world.getTime() >= be.nextLinkAttempt) {
                be.nextLinkAttempt = world.getTime() + LINK_RETRY_TICKS;
                GapFillers.link(serverWorld, pos, be.reachAuto, null);
            }
        } else {
            if (be.registeredPlatform != be.platformId) {
                GapFillerStore.register(be.platformId, be.storeKey(), style(state).defaults);
                be.registeredPlatform = be.platformId;
            }
            be.mirror(GapFillerStore.settings(be.platformId, style(state).defaults));
            target = GapFillerEngine.phaseFor(be.platformId);
        }

        GapFillerPhase current = state.get(GapFillerBlock.PHASE);
        if (target != current) {
            world.setBlockState(pos, state.with(GapFillerBlock.PHASE, target), Block.NOTIFY_LISTENERS);
            be.playTransition(serverWorld, state, current, target);
        }
        if (target == GapFillerPhase.EXTENDED && be.plateOccupied(serverWorld, state)) {
            GapFillerEngine.markOccupied(be.platformId);
        }
    }

    /**
     * The move sound when the plate starts, the bang when it lands. Only every
     * third block of a run speaks (by world position), so a long platform
     * sounds like one machine rather than a pile of identical samples.
     */
    private void playTransition(ServerWorld world, BlockState state, GapFillerPhase from, GapFillerPhase to) {
        if (Math.floorMod(pos.getX() + pos.getZ(), 3) != 0) {
            return;
        }
        GapFillerBlock.Style style = style(state);
        SoundEvent sound;
        float volume;
        float pitch;
        switch (to) {
            case EXTENDING, RETRACTING -> {
                sound = style == GapFillerBlock.Style.LOOP ? GapFillers.SOUND_ROLL : GapFillers.SOUND_HYDRAULIC;
                volume = 0.7f;
                pitch = to == GapFillerPhase.RETRACTING ? 0.94f : 1f;
            }
            case EXTENDED -> {
                sound = GapFillers.SOUND_BANG;
                volume = 1f;
                pitch = style == GapFillerBlock.Style.LOOP ? 0.85f : 1f;
            }
            default -> {
                if (from != GapFillerPhase.RETRACTING) {
                    return; // an unlinked or stale filler snapping home stays quiet
                }
                sound = GapFillers.SOUND_BANG;
                volume = 0.55f;
                pitch = 1.2f;
            }
        }
        world.playSound(null, pos, sound, SoundCategory.BLOCKS, volume,
                pitch * (0.96f + 0.08f * world.random.nextFloat()));
    }

    /** Anybody standing on the plate? (It stays out while there is.) */
    private boolean plateOccupied(ServerWorld world, BlockState state) {
        Direction side = state.get(GapFillerBlock.TRACK_SIDE);
        float travel = state.get(GapFillerBlock.REACH) * 2f;
        float drop = style(state) == GapFillerBlock.Style.LOOP ? travel * GapFillerBlock.LOOP_SLOPE : 0f;
        float[] a = GapFillerBlock.rotateXZ(side, 0, -travel);
        float[] b = GapFillerBlock.rotateXZ(side, 16, 0);
        double top = pos.getY() + (GapFillerBlock.PLATE_TOP - drop) / 16.0;
        Box plate = new Box(pos.getX() + Math.min(a[0], b[0]) / 16.0, top - 0.05, pos.getZ() + Math.min(a[1], b[1]) / 16.0,
                pos.getX() + Math.max(a[0], b[0]) / 16.0, top + 0.4, pos.getZ() + Math.max(a[1], b[1]) / 16.0);
        return !world.getEntitiesByClass(LivingEntity.class, plate, entity -> !entity.isSpectator()).isEmpty();
    }

    /** Copy the platform's timing (renderer speed + screen); sync only on change. */
    private void mirror(GapFillerStore.Settings settings) {
        if (settings.extendMs() == extendMs && settings.retractMs() == retractMs && settings.minDwellMs() == minDwellMs) {
            return;
        }
        extendMs = settings.extendMs();
        retractMs = settings.retractMs();
        minDwellMs = settings.minDwellMs();
        sync();
    }

    // ---------------------------------------------------------------- linking

    /** Server thread, from {@link GapFillers#link}'s result. */
    void applyLink(long newPlatformId, String label) {
        if (newPlatformId != platformId) {
            unlink();
        }
        platformId = newPlatformId;
        platformLabel = label == null ? "" : label;
        sync();
    }

    /** Server thread: this block no longer serves its platform (broken, relinked). */
    void unlink() {
        if (registeredPlatform != 0) {
            GapFillerStore.unregister(registeredPlatform, storeKey());
            registeredPlatform = 0;
        }
    }

    void setReachAuto(boolean auto) {
        reachAuto = auto;
        markDirty();
    }

    /** Forget the platform so the next tick links afresh. */
    void requestRelink() {
        unlink();
        platformId = 0;
        platformLabel = "";
        nextLinkAttempt = 0;
        sync();
    }

    String storeKey() {
        String dimension = world == null ? "?" : world.getRegistryKey().getValue().toString();
        return dimension + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    // -------------------------------------------------------------- accessors

    public long getPlatformId() {
        return platformId;
    }

    public String getPlatformLabel() {
        return platformLabel;
    }

    public boolean isReachAuto() {
        return reachAuto;
    }

    public int getExtendMs() {
        return extendMs;
    }

    public int getRetractMs() {
        return retractMs;
    }

    public int getMinDwellMs() {
        return minDwellMs;
    }

    public GapFillerBlock.Style getStyle() {
        return style(getCachedState());
    }

    // -------------------------------------------------------------------- NBT

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("Platform", platformId);
        nbt.putString("PlatformLabel", platformLabel);
        nbt.putBoolean("ReachAuto", reachAuto);
        nbt.putInt("ExtendMs", extendMs);
        nbt.putInt("RetractMs", retractMs);
        nbt.putInt("MinDwellMs", minDwellMs);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        platformId = nbt.getLong("Platform");
        platformLabel = nbt.getString("PlatformLabel");
        reachAuto = !nbt.contains("ReachAuto") || nbt.getBoolean("ReachAuto");
        if (nbt.contains("ExtendMs")) {
            extendMs = nbt.getInt("ExtendMs");
            retractMs = nbt.getInt("RetractMs");
            minDwellMs = nbt.getInt("MinDwellMs");
        }
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
}
