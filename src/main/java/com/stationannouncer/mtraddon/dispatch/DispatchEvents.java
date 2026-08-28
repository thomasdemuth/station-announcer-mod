package com.stationannouncer.mtraddon.dispatch;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The dispatch alert ring: a bounded in-memory feed of abnormal operational events
 * for the web UI's alert ticker — the "why is that train stuck" class of problem
 * surfaced instead of discovered by walking to the platform.
 *
 * <p>Producers are the addon engines (hold deadlock cap, door-obstruction backstop —
 * both on SIMULATOR threads) and the {@link DispatchStreamer}'s own detectors (very
 * late, stalled outside a station — streamer thread). Consumers are the streamer
 * (per-frame {@link #since}) and the {@code /dispatch/api/alerts} backlog endpoint
 * (Jetty workers). Everything is guarded by the class monitor; entries are immutable
 * records, so readers get safe snapshots.</p>
 *
 * <p>Alerts are best-effort operator hints, not persisted state: the ring is capped at
 * {@value #MAX_ALERTS} and cleared on server stop.</p>
 */
public final class DispatchEvents {
    private static final int MAX_ALERTS = 200;

    private static final ArrayDeque<Alert> RING = new ArrayDeque<>();
    private static long nextSeq = 1;

    private DispatchEvents() {
    }

    /**
     * One alert. {@code kind} is a stable machine key ({@code hold_cap},
     * {@code door_backstop}, {@code late}, {@code stalled}); ids are 0 when unknown.
     */
    public record Alert(long seq, long atMillis, String kind, String severity,
                        long vehicleId, long platformId, String detail) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("seq", seq);
            json.addProperty("t", atMillis);
            json.addProperty("kind", kind);
            json.addProperty("severity", severity);
            if (vehicleId != 0) {
                json.addProperty("veh", String.valueOf(vehicleId));
            }
            if (platformId != 0) {
                json.addProperty("plat", String.valueOf(platformId));
            }
            json.addProperty("detail", detail == null ? "" : detail);
            return json;
        }
    }

    /** Any thread. {@code severity}: {@code warn} or {@code bad}. */
    public static synchronized void alert(String kind, String severity,
                                          long vehicleId, long platformId, String detail) {
        RING.addLast(new Alert(nextSeq++, System.currentTimeMillis(), kind, severity,
                vehicleId, platformId, detail));
        while (RING.size() > MAX_ALERTS) {
            RING.removeFirst();
        }
    }

    /** The newest sequence number handed out (0 when empty) — a subscription baseline. */
    public static synchronized long latestSeq() {
        return nextSeq - 1;
    }

    /** Alerts newer than {@code seq}, oldest first. */
    public static synchronized List<Alert> since(long seq) {
        List<Alert> result = new ArrayList<>();
        for (Alert alert : RING) {
            if (alert.seq() > seq) {
                result.add(alert);
            }
        }
        return result;
    }

    /** The whole retained backlog as JSON, oldest first (the {@code alerts} endpoint). */
    public static synchronized JsonArray backlogJson() {
        JsonArray array = new JsonArray();
        for (Alert alert : RING) {
            array.add(alert.toJson());
        }
        return array;
    }

    /** Server thread, SERVER_STOPPING. */
    public static synchronized void clear() {
        RING.clear();
        nextSeq = 1;
    }
}
