package com.stationannouncer.wayfinding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.api.ExitView;
import com.stationannouncer.api.PlaceView;
import com.stationannouncer.api.StationLayoutView;
import com.stationannouncer.api.WayfindingApi;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import com.stationannouncer.wayfinding.layout.LayoutScanner;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Station;
import org.mtr.core.data.StationExit;
import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Station Announcer's implementation of {@link WayfindingApi}: turns the store,
 * MTR's station data and the scanned layouts into the API's plain records, and
 * runs the event tracker (place entered / left, exit used) twice a second.
 *
 * <p>MTR data (station names, exit destinations, station centres) is read inside
 * {@code simulator.run} into a cache refreshed every 30 s and whenever places or
 * pins change, so the API itself never touches MTR from a foreign thread.</p>
 */
public final class WayfindingApiBackend implements WayfindingApi.Backend {
    private static final int TRACK_EVERY_TICKS = 10;
    private static final int REFRESH_EVERY_TICKS = 600;
    /** A player within this many blocks (horizontally) of an Exit Marker is "at" the exit. */
    private static final double EXIT_RADIUS = 2.0;
    private static final double EXIT_HEIGHT = 2.5;
    /** Moving this much further from / closer to the station centre while at an exit = walked through it. */
    private static final double EXIT_MIN_TRAVEL = 1.0;

    private static final WayfindingApiBackend INSTANCE = new WayfindingApiBackend();

    /** MTR facts about one station, cached. */
    private record StationFacts(String name, double centerX, double centerZ, Map<String, List<String>> destinations) {
    }

    private static volatile Map<Long, StationFacts> facts = Map.of();
    private static volatile MinecraftServer server;
    private static int tick;

    // cached conversions (rebuilt when the store's snapshot or a layout string changes)
    private List<com.stationannouncer.wayfinding.Place> placeSource;
    private List<PlaceView> placeViews = List.of();
    private List<ExitPin> exitSource;
    private Map<Long, StationFacts> exitFacts;
    private List<ExitView> exitViews = List.of();
    private final Map<Long, Object[]> layoutCache = new ConcurrentHashMap<>(); // id -> {json String, view}

    // tracker state
    private static final Map<UUID, Set<String>> INSIDE = new HashMap<>();
    private static final Map<UUID, Map<String, PlaceView>> INSIDE_VIEWS = new HashMap<>();
    /** player -> exit marker key -> distance to the station centre when they arrived at it. */
    private static final Map<UUID, Map<String, Double>> AT_EXIT = new HashMap<>();

    private WayfindingApiBackend() {
    }

    public static void register() {
        WayfindingApi.Internal.setBackend(INSTANCE);
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            refreshFacts();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            server = null;
            INSIDE.clear();
            INSIDE_VIEWS.clear();
            AT_EXIT.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            UUID id = handler.player.getUuid();
            INSIDE.remove(id);
            INSIDE_VIEWS.remove(id);
            AT_EXIT.remove(id);
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            tick++;
            if (tick % REFRESH_EVERY_TICKS == 0) {
                refreshFacts();
            }
            if (tick % TRACK_EVERY_TICKS == 0) {
                track(s);
            }
        });
        WayfindingStore.addListener(() -> {
            refreshFacts();
            WayfindingApi.Internal.placesChanged();
        });
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            // the dev rig checks the events from the log
            WayfindingApi.addListener(new com.stationannouncer.api.WayfindingListener() {
                @Override
                public void placeEntered(UUID player, PlaceView place) {
                    StationAnnouncer.LOGGER.info("[wayfinding api] {} entered place {} ({})", player, place.name(), place.id());
                }

                @Override
                public void placeLeft(UUID player, PlaceView place) {
                    StationAnnouncer.LOGGER.info("[wayfinding api] {} left place {} ({})", player, place.name(), place.id());
                }

                @Override
                public void exitUsed(UUID player, ExitView exit, boolean leavingStation) {
                    StationAnnouncer.LOGGER.info("[wayfinding api] {} used {} exit {} ({})", player, exit.stationName(),
                            exit.name(), leavingStation ? "leaving" : "entering");
                }
            });
        }
    }

    // ============================================================== backend

    @Override
    public boolean available() {
        return server != null;
    }

    @Override
    public synchronized List<PlaceView> places() {
        List<com.stationannouncer.wayfinding.Place> source = WayfindingStore.places();
        if (source != placeSource) {
            List<PlaceView> views = new ArrayList<>(source.size());
            for (com.stationannouncer.wayfinding.Place place : source) {
                views.add(view(place));
            }
            placeViews = List.copyOf(views);
            placeSource = source;
        }
        return placeViews;
    }

    static PlaceView view(com.stationannouncer.wayfinding.Place place) {
        return new PlaceView(place.id(), place.name(), place.category().id(), place.description(), place.dimension(),
                place.x(), place.y(), place.z(), place.radius(), place.hidden(), place.isBlock());
    }

    @Override
    public synchronized List<ExitView> exits() {
        List<ExitPin> source = WayfindingStore.pins();
        Map<Long, StationFacts> currentFacts = facts;
        if (source != exitSource || currentFacts != exitFacts) {
            List<ExitView> views = new ArrayList<>();
            for (ExitPin pin : source) {
                if (!pin.pinned()) {
                    continue;
                }
                StationFacts f = currentFacts.get(pin.stationId());
                List<String> destinations = f == null ? List.of() : f.destinations().getOrDefault(pin.exitName(), List.of());
                views.add(new ExitView(pin.stationId(), f == null ? "" : f.name(), pin.exitName(), destinations,
                        pin.dimension(), pin.x(), pin.y(), pin.z()));
            }
            exitViews = List.copyOf(views);
            exitSource = source;
            exitFacts = currentFacts;
        }
        return exitViews;
    }

    @Override
    public Optional<StationLayoutView> layout(long stationId) {
        String json = LayoutScanner.layoutJson(stationId);
        if (json == null) {
            return Optional.empty();
        }
        Object[] cached = layoutCache.get(stationId);
        if (cached != null && cached[0] == json) {
            return Optional.of((StationLayoutView) cached[1]);
        }
        try {
            StationLayoutView view = parseLayout(JsonParser.parseString(json).getAsJsonObject());
            layoutCache.put(stationId, new Object[]{json, view});
            return Optional.of(view);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static StationLayoutView parseLayout(JsonObject root) {
        Map<Long, Boolean> stepFree = new HashMap<>();
        JsonObject flags = root.getAsJsonObject("platformStepFree");
        if (flags != null) {
            for (Map.Entry<String, JsonElement> e : flags.entrySet()) {
                stepFree.put(Long.parseLong(e.getKey()), e.getValue().getAsBoolean());
            }
        }
        List<StationLayoutView.Walk> walks = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("links")) {
            walks.add(walk(element.getAsJsonObject()));
        }
        List<String> warnings = new ArrayList<>();
        root.getAsJsonArray("warnings").forEach(w -> warnings.add(w.getAsString()));
        return new StationLayoutView(Long.parseLong(root.get("station").getAsString()), root.get("name").getAsString(),
                root.get("scannedAt").getAsLong(), Map.copyOf(stepFree), List.copyOf(walks), List.copyOf(warnings));
    }

    private static StationLayoutView.Walk walk(JsonObject link) {
        List<StationLayoutView.Leg> legs = new ArrayList<>();
        JsonArray legsJson = link.getAsJsonArray("legs");
        for (JsonElement element : legsJson) {
            JsonObject leg = element.getAsJsonObject();
            legs.add(new StationLayoutView.Leg(leg.get("kind").getAsString(), leg.get("meters").getAsDouble(),
                    leg.get("dy").getAsDouble()));
        }
        StationLayoutView.Walk alt = link.has("stepFreeAlt") ? walk(link.getAsJsonObject("stepFreeAlt")) : null;
        return new StationLayoutView.Walk(link.has("from") ? link.get("from").getAsString() : "",
                link.has("to") ? link.get("to").getAsString() : "", link.get("meters").getAsDouble(),
                link.get("extraSeconds").getAsDouble(), link.get("stepFree").getAsBoolean(), List.copyOf(legs), alt);
    }

    @Override
    public int mapPlusPort() {
        try {
            return com.stationannouncer.mtraddon.dispatch.DispatchRegistry.isActive() ? org.mtr.mod.Init.getServerPort() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ============================================================ MTR facts

    /** Async: station names, centres and exit destinations from every simulator. */
    private static void refreshFacts() {
        var simulators = MtrSimulators.get();
        if (simulators == null) {
            return;
        }
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    Map<Long, StationFacts> next = new HashMap<>(facts);
                    for (Station station : simulator.stations) {
                        if (station == null) {
                            continue;
                        }
                        Map<String, List<String>> destinations = new HashMap<>();
                        for (StationExit exit : station.getExits()) {
                            List<String> list = new ArrayList<>();
                            for (String destination : exit.getDestinations()) {
                                list.add(firstLang(destination));
                            }
                            destinations.put(exit.getName(), List.copyOf(list));
                        }
                        next.put(station.getId(), new StationFacts(firstLang(station.getName()),
                                (station.getMinX() + station.getMaxX() + 1) / 2.0, (station.getMinZ() + station.getMaxZ() + 1) / 2.0,
                                Map.copyOf(destinations)));
                    }
                    facts = Map.copyOf(next);
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.debug("Wayfinding API: station facts refresh failed", t);
                }
            });
        }
    }

    private static String firstLang(String name) {
        if (name == null) {
            return "";
        }
        int bar = name.indexOf('|');
        return (bar < 0 ? name : name.substring(0, bar)).trim();
    }

    // =============================================================== tracker

    /** Server thread, twice a second: place enter/leave and exit crossings for every player. */
    private static void track(MinecraftServer srv) {
        List<PlaceView> places = INSTANCE.places();
        List<ExitView> exits = INSTANCE.exits();
        Map<Long, StationFacts> currentFacts = facts;
        for (ServerPlayerEntity player : srv.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid();
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            String dimension = player.getWorld().getRegistryKey().getValue().toString();
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();

            // ---- places
            Set<String> now = new HashSet<>();
            Map<String, PlaceView> nowViews = new HashMap<>();
            for (PlaceView place : places) {
                if (place.contains(dimension, x, y, z)) {
                    now.add(place.id());
                    nowViews.put(place.id(), place);
                }
            }
            Set<String> before = INSIDE.getOrDefault(id, Set.of());
            Map<String, PlaceView> beforeViews = INSIDE_VIEWS.getOrDefault(id, Map.of());
            for (String placeId : now) {
                if (!before.contains(placeId)) {
                    WayfindingApi.Internal.placeEntered(id, nowViews.get(placeId));
                }
            }
            for (String placeId : before) {
                if (!now.contains(placeId)) {
                    WayfindingApi.Internal.placeLeft(id, beforeViews.get(placeId));
                }
            }
            if (now.isEmpty()) {
                INSIDE.remove(id);
                INSIDE_VIEWS.remove(id);
            } else {
                INSIDE.put(id, now);
                INSIDE_VIEWS.put(id, nowViews);
            }

            // ---- exits: arrive within EXIT_RADIUS, then leave it further from / closer to the station
            Map<String, Double> at = AT_EXIT.computeIfAbsent(id, k -> new HashMap<>());
            Set<String> near = new HashSet<>();
            for (ExitView exit : exits) {
                if (!exit.dimension().equals(dimension)) {
                    continue;
                }
                double dx = x - (exit.x() + 0.5);
                double dz = z - (exit.z() + 0.5);
                if (dx * dx + dz * dz > EXIT_RADIUS * EXIT_RADIUS || Math.abs(y - exit.y()) > EXIT_HEIGHT) {
                    continue;
                }
                String key = exitKey(exit);
                near.add(key);
                StationFacts f = currentFacts.get(exit.stationId());
                if (f != null) {
                    at.putIfAbsent(key, Math.hypot(x - f.centerX(), z - f.centerZ()));
                }
            }
            for (var iterator = at.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                if (near.contains(entry.getKey())) {
                    continue;
                }
                iterator.remove();
                ExitView exit = findExit(exits, entry.getKey());
                StationFacts f = exit == null ? null : currentFacts.get(exit.stationId());
                if (exit == null || f == null) {
                    continue;
                }
                double after = Math.hypot(x - f.centerX(), z - f.centerZ());
                double moved = after - entry.getValue();
                if (Math.abs(moved) >= EXIT_MIN_TRAVEL) {
                    WayfindingApi.Internal.exitUsed(id, exit, moved > 0);
                }
            }
            if (at.isEmpty()) {
                AT_EXIT.remove(id);
            }
        }
    }

    private static String exitKey(ExitView exit) {
        return exit.dimension() + "|" + exit.x() + "|" + exit.y() + "|" + exit.z();
    }

    @Nullable
    private static ExitView findExit(List<ExitView> exits, String key) {
        for (ExitView exit : exits) {
            if (exitKey(exit).equals(key)) {
                return exit;
            }
        }
        return null;
    }
}
