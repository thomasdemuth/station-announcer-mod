package com.stationannouncer.wayfinding;

import com.google.gson.JsonObject;

/**
 * Where one MTR station exit actually is: an Exit Marker block pinned to
 * {@code (stationId, exitName)}. MTR's own {@code StationExit} is only a name
 * and a list of street names, so the position lives here, keyed by the marker
 * block. Several markers may pin the same exit (a big entrance with two
 * stairs); an empty {@link #exitName} is a marker not pinned to anything yet.
 *
 * <p>Exit names are MTR's ("A", "B2"). If an exit is renamed in MTR's own
 * dashboard the pin keeps the old name and shows as orphaned (red in the
 * world, left off the map) until it is re-pinned — renames made in OUR exit
 * editor carry the pins along.</p>
 */
public record ExitPin(String blockKey, String dimension, String mtrDim, int x, int y, int z,
                      long stationId, String exitName) {
    public static final int MAX_EXIT_NAME = 8;

    public ExitPin {
        exitName = exitName == null ? "" : exitName.trim();
        mtrDim = mtrDim == null ? "" : mtrDim;
    }

    public boolean pinned() {
        return stationId != 0 && !exitName.isEmpty();
    }

    public ExitPin withExit(long station, String name) {
        return new ExitPin(blockKey, dimension, mtrDim, x, y, z, station, name);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("block", blockKey);
        json.addProperty("dimension", dimension);
        json.addProperty("mtrDim", mtrDim);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("station", Long.toString(stationId));
        json.addProperty("exit", exitName);
        return json;
    }

    public static ExitPin fromJson(JsonObject json) {
        return new ExitPin(json.get("block").getAsString(),
                json.has("dimension") ? json.get("dimension").getAsString() : "minecraft:overworld",
                json.has("mtrDim") ? json.get("mtrDim").getAsString() : "",
                json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt(),
                Long.parseLong(json.get("station").getAsString()),
                json.has("exit") ? json.get("exit").getAsString() : "");
    }

    /**
     * MTR's exit naming: one or two capital letters, optionally followed by up
     * to three digits ("A", "B2", "AA12"). MTR's station-exit signs split the
     * name into that letter and number, so anything else would draw wrongly.
     */
    public static boolean validExitName(String name) {
        return name != null && name.matches("[A-Z]{1,2}[0-9]{0,3}");
    }
}
