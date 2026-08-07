package com.stationannouncer.block;

import com.stationannouncer.AnnouncerRegistry;
import com.stationannouncer.net.AnnouncerNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Shared brains of every PA source block (Station Announcer and PA Control
 * Box): announcement text, delay, tag, presentation flags, redstone/command
 * triggering with delayed firing, and the per-player volume/dedup broadcast.
 * Subclasses only decide which message to play ({@link #pickMessage}) and
 * where sound comes from ({@link #collectSources}).
 *
 * All triggering and radius logic runs on the logical server; clients merely
 * receive the announcement packet.
 */
public abstract class AbstractPaBlockEntity extends BlockEntity {
    public static final int MAX_TEXT_LENGTH = 512;
    public static final int MAX_TAG_LENGTH = 64;
    public static final int MAX_DELAY_SECONDS = 60;
    public static final int MAX_CHIME_SOUND_LENGTH = 128;

    protected String text = "";
    protected int delaySeconds = 0; // 0..60
    protected String announcerTag = "";
    protected boolean showChat = true;
    protected boolean playChime = true;
    /** Optional sound event id replacing the built-in chime ("" = default). */
    protected String chimeSound = "";

    /** Ticks until a pending (delayed) announcement fires; <= 0 means idle. Not persisted. */
    protected int pendingTicks = 0;

    /** One place sound is emitted from, with its own volume and reach. */
    public record SoundSource(BlockPos pos, int volumePercent, int radius) {
    }

    protected AbstractPaBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("Text", text);
        nbt.putInt("DelaySeconds", delaySeconds);
        nbt.putString("Tag", announcerTag);
        nbt.putBoolean("ShowChat", showChat);
        nbt.putBoolean("PlayChime", playChime);
        nbt.putString("ChimeSound", chimeSound);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setText(nbt.getString("Text"));
        setDelaySeconds(nbt.getInt("DelaySeconds"));
        setAnnouncerTag(nbt.getString("Tag"));
        // Default the toggles to true for blocks saved before they existed.
        setShowChat(!nbt.contains("ShowChat") || nbt.getBoolean("ShowChat"));
        setPlayChime(!nbt.contains("PlayChime") || nbt.getBoolean("PlayChime"));
        setChimeSound(nbt.getString("ChimeSound"));
    }

    // ----------------------------------------------------------- client sync

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    /** Persists and pushes the current state to all clients tracking this chunk. */
    public void sync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void setWorld(World world) {
        super.setWorld(world);
        if (world instanceof ServerWorld) {
            AnnouncerRegistry.add(this);
        }
    }

    @Override
    public void markRemoved() {
        // Covers both "block broken" and "chunk unloaded": cancel any pending
        // delayed announcement and drop out of the tag registry.
        pendingTicks = 0;
        AnnouncerRegistry.remove(this);
        super.markRemoved();
    }

    // -------------------------------------------------------------- triggering

    /**
     * Starts (or restarts) this block's announcement. If a delay is configured
     * the announcement fires after that many seconds; re-triggering while a
     * delay is pending restarts the countdown.
     */
    public void trigger() {
        if (world == null || world.isClient || isRemoved()) {
            return;
        }
        if (text.isBlank()) {
            return; // nothing to announce
        }
        int delayTicks = delaySeconds * 20;
        if (delayTicks <= 0) {
            fire();
        } else {
            pendingTicks = delayTicks;
        }
    }

    /** Server-side per-tick countdown for the delayed firing. */
    protected static void tickPending(AbstractPaBlockEntity be) {
        if (be.pendingTicks > 0 && --be.pendingTicks == 0) {
            be.fire();
        }
    }

    /** The message this firing should play, or null/blank to skip. */
    protected String pickMessage(ServerWorld world) {
        return text.trim();
    }

    /** Where the announcement is heard from; empty list = announce nothing. */
    protected abstract List<SoundSource> collectSources(ServerWorld world);

    /** Hook: a message was picked and is about to be broadcast (control box pushes it to displays). */
    protected void onFired(ServerWorld world, String message) {
    }

    /**
     * Sends the announcement to every player in range of at least one sound
     * source. A player in range of several sources gets exactly one packet,
     * carrying the loudest source's position and volume. Recipients are
     * computed at fire time, so players who joined or walked into range
     * during the delay are included.
     */
    protected final void fire() {
        if (!(world instanceof ServerWorld serverWorld) || text.isBlank()) {
            return;
        }
        String message = pickMessage(serverWorld);
        if (message == null || message.isBlank()) {
            return;
        }
        // Displays are updated even when no speaker ends up in range: the
        // screens should show what the PA is saying regardless of audio reach.
        onFired(serverWorld, message);
        List<SoundSource> sources = collectSources(serverWorld);
        if (sources.isEmpty()) {
            return;
        }
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            SoundSource best = null;
            float bestVolume = -1.0f;
            for (SoundSource source : sources) {
                double distSq = player.squaredDistanceTo(Vec3d.ofCenter(source.pos()));
                double radiusSq = (double) source.radius() * source.radius();
                if (distSq > radiusSq) {
                    continue;
                }
                // Linear falloff: full volume at the source, 25% at the edge of
                // its radius, so players just inside still hear something.
                double dist = Math.sqrt(distSq);
                float falloff = 1.0f - 0.75f * (float) (dist / source.radius());
                float volume = MathHelper.clamp(source.volumePercent() / 100.0f * falloff, 0.0f, 1.0f);
                if (volume > bestVolume) {
                    bestVolume = volume;
                    best = source;
                }
            }
            if (best != null) {
                AnnouncerNetworking.sendAnnouncement(player, message, bestVolume, best.pos(),
                        showChat, playChime, chimeSound);
            }
        }
    }

    // ------------------------------------------------------------ accessors

    public String getText() {
        return text;
    }

    public void setText(String text) {
        String value = text == null ? "" : text;
        this.text = value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(int delaySeconds) {
        this.delaySeconds = MathHelper.clamp(delaySeconds, 0, MAX_DELAY_SECONDS);
    }

    public String getAnnouncerTag() {
        return announcerTag;
    }

    public void setAnnouncerTag(String tag) {
        String value = tag == null ? "" : tag.trim();
        this.announcerTag = value.length() > MAX_TAG_LENGTH ? value.substring(0, MAX_TAG_LENGTH) : value;
    }

    public boolean shouldShowChat() {
        return showChat;
    }

    public void setShowChat(boolean showChat) {
        this.showChat = showChat;
    }

    public boolean shouldPlayChime() {
        return playChime;
    }

    public void setPlayChime(boolean playChime) {
        this.playChime = playChime;
    }

    public String getChimeSound() {
        return chimeSound;
    }

    public void setChimeSound(String chimeSound) {
        String value = chimeSound == null ? "" : chimeSound.trim();
        this.chimeSound = value.length() > MAX_CHIME_SOUND_LENGTH ? value.substring(0, MAX_CHIME_SOUND_LENGTH) : value;
    }
}
