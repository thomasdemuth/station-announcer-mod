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
 */
@Environment(EnvType.CLIENT)
class NarratorBackend {
    private boolean warned;

    /** @return true if the narrator is available and accepted the text. */
    boolean say(String text, float volume) {
        try {
            Narrator narrator = Narrator.getNarrator();
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
            Narrator narrator = Narrator.getNarrator();
            if (narrator != null && narrator.active()) {
                narrator.clear();
            }
        } catch (Throwable ignored) {
        }
    }
}
