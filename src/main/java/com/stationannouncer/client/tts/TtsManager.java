package com.stationannouncer.client.tts;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Single entry point for client-side text-to-speech. Chooses between the
 * Minecraft narrator library (ships with the game, cross-platform) and the
 * operating system's own voice, per client config. Never used on the server.
 */
@Environment(EnvType.CLIENT)
public final class TtsManager {
    /** Below this effective volume the announcement is considered inaudible. */
    public static final float AUDIBLE_THRESHOLD = 0.05f;

    private static final NarratorBackend NARRATOR = new NarratorBackend();
    private static final SystemTtsBackend SYSTEM = new SystemTtsBackend();

    private TtsManager() {
    }

    /**
     * Speaks {@code text} at {@code volume} (0..1). The narrator backend has no
     * volume control, so volume acts as an audibility threshold there; the
     * system backend scales true output volume.
     */
    public static void speak(String text, float volume) {
        if (!ClientConfig.get().enableTts || text.isBlank() || volume < AUDIBLE_THRESHOLD) {
            return;
        }
        try {
            switch (ClientConfig.get().backend()) {
                case "narrator" -> NARRATOR.say(text, volume);
                case "system" -> SYSTEM.say(text, volume);
                default -> { // auto: prefer the built-in narrator, fall back to the OS voice
                    if (!NARRATOR.say(text, volume)) {
                        SYSTEM.say(text, volume);
                    }
                }
            }
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Text-to-speech failed", t);
        }
    }

    /** Cancels any speech in progress (used on disconnect). */
    public static void stop() {
        try {
            NARRATOR.stop();
            SYSTEM.stop();
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.debug("Stopping TTS failed", t);
        }
    }
}
