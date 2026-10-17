package com.stationannouncer.mtr.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.stationannouncer.track.TrackProfile;

/** Gson for {@link TrackProfile} on disk and on the wire. Everything read is {@link TrackProfile#sanitized() sanitized}. */
public final class TrackJson {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private TrackJson() {
    }

    public static String toJson(TrackProfile profile) {
        return GSON.toJson(profile);
    }

    /** Parses + sanitizes one profile; null if unusable. */
    public static TrackProfile parse(JsonElement element) {
        try {
            final TrackProfile raw = GSON.fromJson(element, TrackProfile.class);
            return raw == null ? null : raw.sanitized();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static TrackProfile parse(String json) {
        try {
            return parse(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code "ax,ay,az;bx,by,bz"} → the six longs, or null. */
    public static long[] parseKey(String key) {
        if (key == null || key.length() > 160) {
            return null;
        }
        final String[] halves = key.split(";");
        if (halves.length != 2) {
            return null;
        }
        final long[] out = new long[6];
        for (int h = 0; h < 2; h++) {
            final String[] parts = halves[h].split(",");
            if (parts.length != 3) {
                return null;
            }
            for (int i = 0; i < 3; i++) {
                try {
                    out[h * 3 + i] = Long.parseLong(parts[i].trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return out;
    }
}
