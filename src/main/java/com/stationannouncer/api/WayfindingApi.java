package com.stationannouncer.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Station Announcer's public wayfinding API: places, exit positions and scanned
 * station layouts, plus enter/leave/exit events. For other mods (MTR-Games).
 *
 * <p><b>PURE JAVA.</b> No Minecraft, Fabric or MTR type appears anywhere in this
 * package, so a mod built with Mojang mappings, Yarn or anything else compiles
 * against the small {@code station-announcer-api-<VERSION>.jar} and runs against
 * the real mod. Players are {@link java.util.UUID}s, worlds are registry id
 * strings ({@code minecraft:overworld}), positions are numbers.</p>
 *
 * <p><b>Guard every call</b> with {@code FabricLoader.getInstance().isModLoaded("station_announcer")}
 * and keep the calls in a class that is only loaded when it is. Then check
 * {@link #VERSION} ≥ what you need and {@link #isAvailable()} (false before the
 * server has started or on a client).</p>
 *
 * <p><b>Threads:</b> the query methods are safe from any thread (immutable
 * snapshots); listeners are called on the server thread.</p>
 *
 * <p>Versioning: {@link #VERSION} only goes up, and only ever ADDS. 1 = this file.</p>
 */
public final class WayfindingApi {
    /** API version. Compare with {@code >=}. */
    public static final int VERSION = 1;

    private static volatile Backend backend;
    private static final List<WayfindingListener> LISTENERS = new CopyOnWriteArrayList<>();

    private WayfindingApi() {
    }

    /** True while a server with Station Announcer is running (the data is loaded). */
    public static boolean isAvailable() {
        Backend b = backend;
        return b != null && b.available();
    }

    // ---------------------------------------------------------------- places

    /** Every place in every world, hidden ones included (they are valid quest targets). */
    public static List<PlaceView> places() {
        Backend b = backend;
        return b == null ? List.of() : b.places();
    }

    /** Places in one world ({@code minecraft:overworld}). */
    public static List<PlaceView> places(String dimension) {
        return places().stream().filter(p -> p.dimension().equals(dimension)).toList();
    }

    /** Places of one category (landmark, park, …; see {@link #categories()}). */
    public static List<PlaceView> placesInCategory(String category) {
        return places().stream().filter(p -> p.category().equalsIgnoreCase(category)).toList();
    }

    /** A place by id, else by exact name (case-insensitive), else by a unique name prefix. */
    public static Optional<PlaceView> place(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        String q = idOrName.trim();
        List<PlaceView> all = places();
        for (PlaceView p : all) {
            if (p.id().equals(q)) {
                return Optional.of(p);
            }
        }
        for (PlaceView p : all) {
            if (p.name().equalsIgnoreCase(q)) {
                return Optional.of(p);
            }
        }
        String lower = q.toLowerCase(java.util.Locale.ROOT);
        List<PlaceView> prefix = all.stream().filter(p -> p.name().toLowerCase(java.util.Locale.ROOT).startsWith(lower)).toList();
        return prefix.size() == 1 ? Optional.of(prefix.get(0)) : Optional.empty();
    }

    /** Every place whose arrival area contains this position ({@link PlaceView#contains}). */
    public static List<PlaceView> placesAt(String dimension, double x, double y, double z) {
        return places().stream().filter(p -> p.contains(dimension, x, y, z)).toList();
    }

    /** The place categories, in the order Station Announcer shows them. */
    public static List<String> categories() {
        return List.of("landmark", "attraction", "park", "shopping", "food", "culture", "sports", "civic",
                "district", "other");
    }

    // ----------------------------------------------------------------- exits

    /** Every pinned Exit Marker in every world. */
    public static List<ExitView> exits() {
        Backend b = backend;
        return b == null ? List.of() : b.exits();
    }

    /** Pinned Exit Markers of one MTR station. */
    public static List<ExitView> exits(long stationId) {
        return exits().stream().filter(e -> e.stationId() == stationId).toList();
    }

    /**
     * An exit by station (MTR id, or name — first language, case-insensitive) and
     * exit name ("A2", case-insensitive). The first marker when an exit has several.
     */
    public static Optional<ExitView> exit(String station, String exitName) {
        if (station == null || exitName == null) {
            return Optional.empty();
        }
        String s = station.trim();
        String e = exitName.trim();
        for (ExitView exit : exits()) {
            boolean stationMatches = Long.toString(exit.stationId()).equals(s) || exit.stationName().equalsIgnoreCase(s);
            if (stationMatches && exit.name().equalsIgnoreCase(e)) {
                return Optional.of(exit);
            }
        }
        return Optional.empty();
    }

    // --------------------------------------------------------------- layouts

    /** A station's scanned layout, if it has been scanned. */
    public static Optional<StationLayoutView> layout(long stationId) {
        Backend b = backend;
        return b == null ? Optional.empty() : b.layout(stationId);
    }

    // ------------------------------------------------------------------ Map+

    /**
     * The port System Map+ is served on (MTR's web server), or 0 when it is not
     * running. Map+ lives at {@code http://<host>:<port>/dispatch/map.html}.
     */
    public static int mapPlusPort() {
        Backend b = backend;
        return b == null ? 0 : b.mapPlusPort();
    }

    /**
     * A Map+ path that opens the journey planner, e.g.
     * {@code mapPlusPath(null, "place:0a1b2c3d")} → {@code /dispatch/map.html?to=place:0a1b2c3d}.
     * Targets: {@code place:<id>}, {@code station:<MTR id or name>},
     * {@code point:<x>,<z>} (optionally {@code ,<y>}); null = leave that end open.
     * Values are URL-encoded here. Prefix with {@code http://<host>:<mapPlusPort()>}.
     */
    public static String mapPlusPath(String from, String to) {
        StringBuilder path = new StringBuilder("/dispatch/map.html");
        char sep = '?';
        if (from != null && !from.isBlank()) {
            path.append(sep).append("from=").append(encode(from));
            sep = '&';
        }
        if (to != null && !to.isBlank()) {
            path.append(sep).append("to=").append(encode(to));
        }
        return path.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value.trim(), java.nio.charset.StandardCharsets.UTF_8).replace("%3A", ":")
                .replace("%2C", ",");
    }

    // -------------------------------------------------------------- listening

    public static void addListener(WayfindingListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(WayfindingListener listener) {
        LISTENERS.remove(listener);
    }

    // ============================================================= internals

    /** Implemented by Station Announcer itself. Not for other mods. */
    public interface Backend {
        boolean available();

        List<PlaceView> places();

        List<ExitView> exits();

        Optional<StationLayoutView> layout(long stationId);

        int mapPlusPort();
    }

    /** Station Announcer's side of the API. Other mods: do not call these. */
    public static final class Internal {
        private Internal() {
        }

        public static void setBackend(Backend newBackend) {
            backend = newBackend;
        }

        public static void placeEntered(java.util.UUID player, PlaceView place) {
            for (WayfindingListener l : LISTENERS) {
                safely(() -> l.placeEntered(player, place));
            }
        }

        public static void placeLeft(java.util.UUID player, PlaceView place) {
            for (WayfindingListener l : LISTENERS) {
                safely(() -> l.placeLeft(player, place));
            }
        }

        public static void exitUsed(java.util.UUID player, ExitView exit, boolean leavingStation) {
            for (WayfindingListener l : LISTENERS) {
                safely(() -> l.exitUsed(player, exit, leavingStation));
            }
        }

        public static void placesChanged() {
            for (WayfindingListener l : LISTENERS) {
                safely(l::placesChanged);
            }
        }

        private static void safely(Runnable call) {
            try {
                call.run();
            } catch (Throwable t) {
                System.err.println("[station_announcer] a wayfinding listener failed: " + t);
                t.printStackTrace();
            }
        }
    }
}
