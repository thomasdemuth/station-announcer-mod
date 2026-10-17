package com.stationannouncer.client.mtraddon.crew;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Every sound of the crew feature, all client-side:
 * <ul>
 *   <li><b>Cab buzzer</b> — non-positional at full volume for the receiver (the
 *       buzzer is in the cab you sit in, wherever the presser is), and a quiet copy
 *       for the presser as feedback.</li>
 *   <li><b>Horn</b> — one looping {@link HornLoop} per sounding driver, following
 *       that player's entity every tick. Volume {@link #HORN_VOLUME} makes vanilla's
 *       linear rolloff reach {@code 16 × 4 = 64} blocks (the attenuation distance is
 *       fixed when the sound starts, from {@code max(volume, 1)}); releasing fades the
 *       gain over {@link #HORN_FADE_TICKS} instead of cutting it.</li>
 *   <li><b>Vigilance</b> — a looping warning tone while the server says WARNING, and
 *       the emergency-brake tone once on the trip.</li>
 * </ul>
 * Sound events are registered common-side under {@code station_announcer:*}; the
 * client only builds instances from their ids with {@link SoundEvent#of}.
 */
@Environment(EnvType.CLIENT)
final class CrewSounds {
    private static final float HORN_VOLUME = 4.0f;
    private static final int HORN_FADE_TICKS = 4;
    /** The presser's own feedback buzz: quiet, a touch higher, so it never masks a reply. */
    private static final float FEEDBACK_VOLUME = 0.25f;
    private static final float FEEDBACK_PITCH = 1.15f;

    private static final Identifier BUZZER = StationAnnouncer.id(CrewProtocol.SOUND_BUZZER);
    private static final Identifier VIGIL_WARN = StationAnnouncer.id(CrewProtocol.SOUND_VIGIL_WARN);
    private static final Identifier VIGIL_TRIP = StationAnnouncer.id(CrewProtocol.SOUND_VIGIL_TRIP);

    /** Sounding (or fading) horns by the driver's entity id. Client thread only. */
    private static final Map<Integer, HornLoop> HORNS = new HashMap<>();
    @Nullable
    private static AttachedLoop vigilanceLoop;

    private CrewSounds() {
    }

    // ---------------------------------------------------------------- buzzer

    /** A buzz from the other end of the train. */
    static void playBuzzer() {
        playFlat(BUZZER, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    /** The presser's own quiet click, so a press is never silent. */
    static void playBuzzerFeedback() {
        playFlat(BUZZER, SoundCategory.PLAYERS, FEEDBACK_VOLUME, FEEDBACK_PITCH);
    }

    // ------------------------------------------------------------------ horn

    /** Starts (replacing any existing one) or releases the horn of one driver. */
    static void horn(MinecraftClient client, int entityId, byte horn, boolean on) {
        HornLoop existing = HORNS.remove(entityId);
        if (!on) {
            if (existing != null && !existing.isDone()) {
                existing.release();
                HORNS.put(entityId, existing); // keep it reachable until the fade ends
            }
            return;
        }
        if (existing != null) {
            existing.stopNow();
            client.getSoundManager().stop(existing);
        }
        if (client.world == null) {
            return;
        }
        Entity entity = client.world.getEntityById(entityId);
        if (entity == null) {
            return; // not tracked by this client: nowhere to put the sound
        }
        HornLoop loop = new HornLoop(entityId, CrewProtocol.hornSound(horn), entity);
        HORNS.put(entityId, loop);
        client.getSoundManager().play(loop);
    }

    // ------------------------------------------------------------- vigilance

    /** Keeps the warning loop running exactly while the status says WARNING. */
    static void tickVigilance(MinecraftClient client) {
        boolean warning = CrewState.active() && CrewState.vigilanceState == CrewProtocol.VIGIL_WARNING;
        if (warning && (vigilanceLoop == null || vigilanceLoop.isDone())) {
            vigilanceLoop = new AttachedLoop(VIGIL_WARN, SoundCategory.PLAYERS, 1.0f,
                    () -> CrewState.active() && CrewState.vigilanceState == CrewProtocol.VIGIL_WARNING);
            client.getSoundManager().play(vigilanceLoop);
        } else if (!warning && vigilanceLoop != null && vigilanceLoop.isDone()) {
            vigilanceLoop = null;
        }
    }

    static void playVigilanceTrip() {
        playFlat(VIGIL_TRIP, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------- lifecycle

    /** Per-tick housekeeping: drops finished horns from the map. */
    static void tick() {
        HORNS.values().removeIf(HornLoop::isDone);
    }

    static void clear(MinecraftClient client) {
        for (HornLoop loop : HORNS.values()) {
            loop.stopNow();
            client.getSoundManager().stop(loop);
        }
        HORNS.clear();
        if (vigilanceLoop != null) {
            vigilanceLoop.stopNow();
            client.getSoundManager().stop(vigilanceLoop);
            vigilanceLoop = null;
        }
    }

    // --------------------------------------------------------------- helpers

    /** A one-shot at the listener: no position, no rolloff. */
    private static void playFlat(Identifier id, SoundCategory category, float volume, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(new PositionedSoundInstance(id, category,
                volume, pitch, SoundInstance.createRandom(), false, 0,
                SoundInstance.AttenuationType.NONE, 0, 0, 0, true));
    }

    /** A looping horn that follows one entity; {@link #release()} fades it out. */
    private static final class HornLoop extends MovingSoundInstance {
        private final int entityId;
        /** -1 while held; otherwise ticks of fade left. */
        private int fadeTicks = -1;

        HornLoop(int entityId, Identifier sound, Entity entity) {
            super(SoundEvent.of(sound), SoundCategory.NEUTRAL, SoundInstance.createRandom());
            this.entityId = entityId;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = HORN_VOLUME;
            this.attenuationType = AttenuationType.LINEAR;
            follow(entity);
        }

        private void follow(Entity entity) {
            x = entity.getX();
            y = entity.getEyeY();
            z = entity.getZ();
        }

        void release() {
            if (fadeTicks < 0) {
                fadeTicks = HORN_FADE_TICKS;
            }
        }

        void stopNow() {
            setDone();
        }

        @Override
        public void tick() {
            MinecraftClient client = MinecraftClient.getInstance();
            Entity entity = client.world == null ? null : client.world.getEntityById(entityId);
            if (entity == null || entity.isRemoved()) {
                setDone();
                return;
            }
            follow(entity);
            if (fadeTicks >= 0) {
                // Gain is clamped to 1 anyway, so the fade runs from 1 down.
                volume = (float) fadeTicks / HORN_FADE_TICKS;
                if (fadeTicks-- <= 0) {
                    setDone();
                }
            }
        }
    }

    /** A looping non-positional sound that stops itself once its condition turns false. */
    private static final class AttachedLoop extends MovingSoundInstance {
        private final BooleanSupplier keepPlaying;

        AttachedLoop(Identifier sound, SoundCategory category, float volume, BooleanSupplier keepPlaying) {
            super(SoundEvent.of(sound), category, SoundInstance.createRandom());
            this.keepPlaying = keepPlaying;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = volume;
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;
        }

        void stopNow() {
            setDone();
        }

        @Override
        public void tick() {
            if (!keepPlaying.getAsBoolean()) {
                setDone();
            }
        }
    }
}
