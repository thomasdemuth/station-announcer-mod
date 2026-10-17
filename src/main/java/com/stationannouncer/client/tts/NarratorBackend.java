package com.stationannouncer.client.tts;

import com.mojang.text2speech.Narrator;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Speaks through Minecraft's bundled narrator library
 * ({@code com.mojang.text2speech.Narrator}). Its API has no volume parameter,
 * so the block volume acts as an on/off threshold here (the manager already
 * filtered inaudible calls).
 *
 * <p>{@link Narrator#getNarrator()} builds a NEW native engine on every call
 * (NSSpeechSynthesizer / SAPI SpVoice / flite plus its executor thread) — vanilla
 * calls it once and keeps the instance. So does this backend: one engine for the
 * session, created on first use and destroyed when the client stops. (Calling it
 * per announcement leaked an engine each time, and {@link #stop()} cleared a fresh
 * idle engine instead of the one speaking, so nothing was ever interrupted.)</p>
 */
@Environment(EnvType.CLIENT)
class NarratorBackend {
    private boolean warned;
    /** The session's engine; null until first use. Client thread only. */
    private Narrator narrator;

    private Narrator narrator() {
        if (narrator == null) {
            // Returns Narrator.EMPTY (inactive) when the platform has no narrator;
            // that is kept too, so a missing narrator is probed once, not per call.
            narrator = Narrator.getNarrator();
        }
        return narrator;
    }

    /** @return true if the narrator is available and accepted the text. */
    boolean say(String text, float volume) {
        try {
            Narrator narrator = narrator();
            if (narrator != null && narrator.active()) {
                narrator.clear();
                narrator.say(text, true);
                return true;
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                StationAnnouncer.LOGGER.warn("Minecraft narrator unavailable, falling back to system TTS", t);
            }
        }
        return false;
    }

    void stop() {
        try {
            if (narrator != null && narrator.active()) {
                narrator.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Releases the native engine (client shutdown). */
    void destroy() {
        Narrator current = narrator;
        narrator = null;
        if (current != null) {
            try {
                current.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}
