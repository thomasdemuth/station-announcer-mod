package com.stationannouncer.client.sound;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.AmbienceBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps one looping sound instance alive per ambience block near the player.
 * Every half second the chunks around the player are scanned for
 * {@link AmbienceBlockEntity}; each gets a repeating {@link MovingSoundInstance}
 * whose volume follows the block's settings with linear falloff (attenuation
 * is done here, not by the engine, because radii can exceed the engine's
 * 16-block rolloff). Instances end themselves when the block is gone, its
 * sound was changed, or the player leaves the radius.
 */
@Environment(EnvType.CLIENT)
public final class AmbienceSoundManager {
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int SCAN_CHUNK_RADIUS = 4; // covers the 48-block max radius

    /** Live loop per block position. */
    private static final Map<Long, AmbienceLoop> LOOPS = new HashMap<>();

    private static int scanCooldown;

    private AmbienceSoundManager() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) {
                if (!LOOPS.isEmpty()) {
                    LOOPS.clear(); // instances end themselves once the world is gone
                }
                return;
            }
            if (--scanCooldown > 0) {
                return;
            }
            scanCooldown = SCAN_INTERVAL_TICKS;
            scan(client, client.world, client.player);
        });
    }

    private static void scan(MinecraftClient client, ClientWorld world, PlayerEntity player) {
        // Drop finished loops so a block can start a fresh one later.
        if (!LOOPS.isEmpty()) {
            for (Iterator<AmbienceLoop> iterator = LOOPS.values().iterator(); iterator.hasNext(); ) {
                if (iterator.next().isDone()) {
                    iterator.remove();
                }
            }
        }
        ChunkPos center = player.getChunkPos();
        for (int cx = center.x - SCAN_CHUNK_RADIUS; cx <= center.x + SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = center.z - SCAN_CHUNK_RADIUS; cz <= center.z + SCAN_CHUNK_RADIUS; cz++) {
                if (!(world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof AmbienceBlockEntity ambience) || ambience.getVolume() <= 0
                            || LOOPS.containsKey(be.getPos().asLong())) {
                        continue;
                    }
                    // Squared compare: no sqrt and no Vec3d for every block
                    // entity in nine by nine chunks, twice a second.
                    BlockPos blockPos = be.getPos();
                    double distanceSq = player.squaredDistanceTo(
                            blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                    if (distanceSq > (double) ambience.getRadius() * ambience.getRadius()) {
                        continue;
                    }
                    AmbienceLoop loop = new AmbienceLoop(ambience);
                    LOOPS.put(be.getPos().asLong(), loop);
                    client.getSoundManager().play(loop);
                }
            }
        }
    }

    /** The repeating instance for one ambience block. */
    private static class AmbienceLoop extends MovingSoundInstance {
        private final BlockPos pos;
        private final String soundName;

        AmbienceLoop(AmbienceBlockEntity ambience) {
            super(soundFor(ambience.getSound()), SoundCategory.AMBIENT, Random.create());
            this.pos = ambience.getPos().toImmutable();
            this.soundName = ambience.getSound();
            this.repeat = true;
            this.repeatDelay = 0;
            this.x = pos.getX() + 0.5;
            this.y = pos.getY() + 0.5;
            this.z = pos.getZ() + 0.5;
            // Volume is computed here each tick; keep the engine's rolloff out of it.
            this.attenuationType = AttenuationType.NONE;
            this.volume = 0.0f;
        }

        private static SoundEvent soundFor(String sound) {
            return AmbienceBlockEntity.SOUND_VENT.equals(sound) ? ModContent.AMBIENCE_VENT : ModContent.AMBIENCE_HUM;
        }

        @Override
        public void tick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) {
                setDone();
                return;
            }
            if (!(client.world.getBlockEntity(pos) instanceof AmbienceBlockEntity ambience)
                    || !soundName.equals(ambience.getSound())) {
                setDone(); // block gone or reconfigured (a new loop will start on the next scan)
                return;
            }
            double distance = Math.sqrt(client.player.squaredDistanceTo(Vec3d.ofCenter(pos)));
            if (distance > ambience.getRadius() + 8) {
                setDone(); // well outside: stop entirely, rescanned when back in range
                return;
            }
            float falloff = 1.0f - (float) (distance / ambience.getRadius());
            volume = MathHelper.clamp(ambience.getVolume() / 100.0f * falloff, 0.0f, 1.0f) * 0.6f;
        }
    }
}
