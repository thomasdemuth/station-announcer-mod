package com.stationannouncer.track;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide lookup the {@code RailMath} mixin reads: rail key → profile.
 *
 * TWO SIDES, because single-player runs the server and the client in one JVM, and the client
 * must never see the server's other dimensions nor the server see the client's unsaved preview:
 * <ul>
 *   <li>SERVER: every dimension's saved profiles (filled by the server store). A server-side
 *       RailMath does not know its dimension, so lookups scan the dimensions — keys include the
 *       exact node positions AND the profile re-checks MTR's node angles, so a collision needs
 *       two dimensions with a rail between identical node pairs and identical angles.</li>
 *   <li>CLIENT: the current dimension's profiles (from the server's sync packet) with the
 *       editor's live PREVIEW drafts layered on top.</li>
 * </ul>
 * Every change bumps {@link #version()}; RailMath instances re-resolve lazily when it moves.
 */
public final class TrackRegistry {
    private static final Map<String, Map<String, TrackProfile>> SERVER = new ConcurrentHashMap<>();
    private static final Map<String, TrackProfile> CLIENT = new ConcurrentHashMap<>();
    private static final Map<String, TrackProfile> PREVIEW = new ConcurrentHashMap<>();
    private static final AtomicLong VERSION = new AtomicLong(1);

    private TrackRegistry() {
    }

    public static long version() {
        return VERSION.get();
    }

    private static void bump() {
        VERSION.incrementAndGet();
    }

    public static TrackProfile lookup(String key, boolean client) {
        if (client) {
            final TrackProfile preview = PREVIEW.get(key);
            return preview != null ? preview : CLIENT.get(key);
        }
        for (final Map<String, TrackProfile> dimension : SERVER.values()) {
            final TrackProfile profile = dimension.get(key);
            if (profile != null) {
                return profile;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- server side

    public static void setServerDimension(String dimension, Map<String, TrackProfile> profiles) {
        SERVER.put(dimension, new ConcurrentHashMap<>(profiles));
        bump();
    }

    public static void putServer(String dimension, TrackProfile profile) {
        SERVER.computeIfAbsent(dimension, d -> new ConcurrentHashMap<>()).put(profile.key(), profile);
        bump();
    }

    public static void removeServer(String dimension, String key) {
        final Map<String, TrackProfile> map = SERVER.get(dimension);
        if (map != null && map.remove(key) != null) {
            bump();
        }
    }

    public static Map<String, TrackProfile> serverDimension(String dimension) {
        return Map.copyOf(SERVER.getOrDefault(dimension, Map.of()));
    }

    public static void clearServer() {
        SERVER.clear();
        bump();
    }

    // ---------------------------------------------------------------- client side

    public static void setClient(Map<String, TrackProfile> profiles) {
        CLIENT.clear();
        CLIENT.putAll(profiles);
        bump();
    }

    public static void putClient(TrackProfile profile) {
        CLIENT.put(profile.key(), profile);
        bump();
    }

    public static void removeClient(String key) {
        if (CLIENT.remove(key) != null) {
            bump();
        }
    }

    public static TrackProfile client(String key) {
        return CLIENT.get(key);
    }

    /** Editor live preview: the client draws (and client-side trains follow) this draft until cleared. */
    public static void setPreview(TrackProfile profile) {
        PREVIEW.put(profile.key(), profile);
        bump();
    }

    public static void clearPreview(String key) {
        if (PREVIEW.remove(key) != null) {
            bump();
        }
    }

    public static void clearPreviews() {
        if (!PREVIEW.isEmpty()) {
            PREVIEW.clear();
            bump();
        }
    }

    /** On disconnect. */
    public static void clearClient() {
        CLIENT.clear();
        PREVIEW.clear();
        bump();
    }
}
