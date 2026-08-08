package com.stationannouncer.mtraddon.analytics;

import com.google.gson.JsonObject;

/**
 * One logged timetable event: a train arriving at, or departing from, a platform.
 *
 * <p>Immutable and free of MTR references by design — it is created on a SIMULATOR
 * thread (inside {@link AnalyticsRecorder}, off the {@code Vehicle} hooks) and handed
 * to the analytics writer thread through a bounded queue, so nothing may keep a live
 * MTR object alive across that boundary. Ids are plain longs; names are MTR-raw
 * {@code "English|Other"} strings that the UI splits on {@code |}.</p>
 *
 * <p>The on-disk form is one JSON object per line (JSONL) — see
 * {@link #toJson()} for the exact key set, and PROGRESS.md for the documented schema.
 * Keys are short because this file is append-only and grows with every stop.</p>
 *
 * @param arrival        true = arrival at the platform, false = departure from it
 * @param atMillis       wall-clock ({@code System.currentTimeMillis()}) instant of the event
 * @param dimension      MTR dimension string ({@code "minecraft/overworld"} form)
 * @param vehicleId      MTR vehicle id
 * @param platformId     MTR platform id the event happened at
 * @param platformName   platform name (raw)
 * @param stationId      station owning the platform, 0 when the platform is outside any station
 * @param stationName    station name (raw), empty when unknown
 * @param routeId        the route the vehicle is running (MTR's "this route" for the leg ahead)
 * @param routeName      route name (raw)
 * @param routeNumber    route number/short code
 * @param routeColor     24-bit route colour
 * @param sidingId       siding (train set) the vehicle belongs to
 * @param depotName      depot name (raw), empty when unknown
 * @param stopIndex      MTR stop index along the depot's route chain
 * @param deviationMs    schedule deviation at the event, positive = late
 * @param dwellMs        DEPARTURE only: measured dwell at this platform (0 on arrivals)
 * @param scheduledDwellMs DEPARTURE only: the dwell baked into the path (already includes
 *                       Feature 2's per-route override), 0 when unknown
 * @param dwellMeasured  DEPARTURE only: true when dwell came from the matching arrival event,
 *                       false when it fell back to MTR's {@code elapsedDwellTime}
 * @param schedHeadwayMs DEPARTURE only: scheduled headway for this route derived from depot
 *                       frequencies, 0 when not derivable (then the aggregator observes it)
 */
public record AnalyticsEvent(
        boolean arrival,
        long atMillis,
        String dimension,
        long vehicleId,
        long platformId,
        String platformName,
        long stationId,
        String stationName,
        long routeId,
        String routeName,
        String routeNumber,
        int routeColor,
        long sidingId,
        String depotName,
        int stopIndex,
        long deviationMs,
        long dwellMs,
        long scheduledDwellMs,
        boolean dwellMeasured,
        long schedHeadwayMs) {

    /** The JSONL representation. Departure-only fields are omitted on arrival events. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("t", atMillis);
        json.addProperty("ev", arrival ? "arr" : "dep");
        json.addProperty("dim", dimension);
        json.addProperty("veh", Long.toString(vehicleId));
        json.addProperty("plat", Long.toString(platformId));
        json.addProperty("platName", platformName);
        json.addProperty("sta", Long.toString(stationId));
        json.addProperty("staName", stationName);
        json.addProperty("rt", Long.toString(routeId));
        json.addProperty("rtName", routeName);
        json.addProperty("rtNum", routeNumber);
        json.addProperty("rtColor", routeColor);
        json.addProperty("sid", Long.toString(sidingId));
        json.addProperty("depot", depotName);
        json.addProperty("stop", stopIndex);
        json.addProperty("dev", deviationMs);
        if (!arrival) {
            json.addProperty("dwell", dwellMs);
            json.addProperty("schedDwell", scheduledDwellMs);
            json.addProperty("dwellSrc", dwellMeasured ? "measured" : "elapsed");
            json.addProperty("schedHw", schedHeadwayMs);
        }
        return json;
    }

    /** Parses one JSONL line back into an event; returns null when the line is unusable. */
    public static AnalyticsEvent fromJson(JsonObject json) {
        try {
            boolean arrival = "arr".equals(string(json, "ev"));
            return new AnalyticsEvent(
                    arrival,
                    json.get("t").getAsLong(),
                    string(json, "dim"),
                    parseLong(json, "veh"),
                    parseLong(json, "plat"),
                    string(json, "platName"),
                    parseLong(json, "sta"),
                    string(json, "staName"),
                    parseLong(json, "rt"),
                    string(json, "rtName"),
                    string(json, "rtNum"),
                    json.has("rtColor") ? json.get("rtColor").getAsInt() : 0,
                    parseLong(json, "sid"),
                    string(json, "depot"),
                    json.has("stop") ? json.get("stop").getAsInt() : 0,
                    json.has("dev") ? json.get("dev").getAsLong() : 0,
                    json.has("dwell") ? json.get("dwell").getAsLong() : 0,
                    json.has("schedDwell") ? json.get("schedDwell").getAsLong() : 0,
                    "measured".equals(string(json, "dwellSrc")),
                    json.has("schedHw") ? json.get("schedHw").getAsLong() : 0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    /** Ids are written as decimal STRINGS (they can exceed 2^53 and must survive JS parsers). */
    private static long parseLong(JsonObject json, String key) {
        String value = string(json, key);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
