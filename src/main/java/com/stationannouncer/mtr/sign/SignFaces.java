package com.stationannouncer.mtr.sign;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.PacketByteBuf;

/**
 * Everything one sign block stores: the front face's spec, whether the front
 * shows at all, and what the back face does — mirror the front (the usual
 * double-sided hanging sign), carry its own spec (an entrance sign that reads
 * differently street-side and platform-side), or stay blank.
 *
 * <p>Single-faced blocks (wall mounts) simply never draw the back.</p>
 */
public record SignFaces(boolean frontOn, SignSpec front, BackMode backMode, SignSpec back) {

    public enum BackMode {
        SAME,
        OWN,
        OFF;

        public static BackMode byName(String name) {
            for (BackMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return SAME;
        }

        public static BackMode byOrdinal(int ordinal) {
            BackMode[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SAME;
        }
    }

    public static final SignFaces EMPTY = new SignFaces(true, SignSpec.EMPTY, BackMode.SAME, SignSpec.EMPTY);

    public SignFaces {
        front = front == null ? SignSpec.EMPTY : front;
        back = back == null ? SignSpec.EMPTY : back;
        backMode = backMode == null ? BackMode.SAME : backMode;
    }

    public static SignFaces of(SignSpec front) {
        return new SignFaces(true, front, BackMode.SAME, SignSpec.EMPTY);
    }

    /** The spec the back face draws, or null when the back is blank. */
    public SignSpec backSpec() {
        return switch (backMode) {
            case SAME -> frontOn ? front : null;
            case OWN -> back;
            case OFF -> null;
        };
    }

    /** The spec the front face draws, or null when it is switched off. */
    public SignSpec frontSpec() {
        return frontOn ? front : null;
    }

    public boolean isEmpty() {
        return front.isEmpty() && (backMode != BackMode.OWN || back.isEmpty());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("frontOn", frontOn);
        json.add("front", front.toJson());
        json.addProperty("back", backMode.name());
        if (backMode == BackMode.OWN) {
            json.add("backSpec", back.toJson());
        }
        return json;
    }

    public static SignFaces fromJson(JsonObject json) {
        boolean frontOn = !json.has("frontOn") || json.get("frontOn").getAsBoolean();
        SignSpec front = json.has("front") ? SignSpec.fromJson(json.getAsJsonObject("front")) : SignSpec.EMPTY;
        BackMode mode = BackMode.byName(json.has("back") ? json.get("back").getAsString() : "");
        SignSpec back = json.has("backSpec") ? SignSpec.fromJson(json.getAsJsonObject("backSpec")) : SignSpec.EMPTY;
        return new SignFaces(frontOn, front, mode, back);
    }

    /** Parses a stored JSON string; unreadable input yields an empty sign. */
    public static SignFaces parse(String json) {
        try {
            return fromJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return EMPTY;
        }
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(toJson().toString(), SignSpec.MAX_JSON * 2);
    }

    public static SignFaces read(PacketByteBuf buf) {
        return parse(buf.readString(SignSpec.MAX_JSON * 2));
    }
}
