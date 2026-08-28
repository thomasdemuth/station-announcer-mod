package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.mtraddon.analytics.AnalyticsAggregator;
import com.stationannouncer.mtraddon.analytics.AnalyticsEvent;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Data for the stringline (time–distance) view: per-route station axes plus the
 * departure events to draw the train traces from.
 *
 * <p><b>Axis</b> ({@link #buildAxis}): every route's ordered platform list
 * ({@code Route.getRoutePlatforms()}, the same order the trip runs) with a cumulative
 * distance per stop — straight-line metres between platform midpoints, which spaces the
 * axis like the real network without needing the baked path. Runs on the SIMULATOR
 * thread and is cached behind a 30 s {@code CachedResponse} like the network payload.</p>
 *
 * <p><b>Traces</b> ({@link #depsJson}): the analytics window's DEPARTURE events for the
 * requested routes, straight from {@link AnalyticsAggregator.Aggregate#events} (a
 * published immutable snapshot — readable from any thread). Each departure carries its
 * measured dwell, so the frontend reconstructs the arrival instant as
 * {@code t − dwellMs} and gets both the diagonal run segments and the flat dwell
 * segments from one event stream. No new recording machinery: the stringline rides the
 * analytics window that already exists.</p>
 */
public final class DispatchStringline {
    private DispatchStringline() {
    }

    /** Simulator thread (ServletBase contract); cached by the servlet. */
    public static JsonObject buildAxis(Simulator simulator) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", DispatchStreamer.SCHEMA_VERSION);
        root.addProperty("dimension", simulator.dimension);
        JsonArray routes = new JsonArray();
        for (Route route : simulator.routes) {
            JsonObject routeJson = new JsonObject();
            routeJson.addProperty("id", String.valueOf(route.getId()));
            routeJson.addProperty("name", route.getName());
            routeJson.addProperty("number", route.getRouteNumber());
            routeJson.addProperty("color", route.getColor());
            routeJson.addProperty("hidden", route.getHidden());
            JsonArray stations = new JsonArray();
            double cumulative = 0;
            Position previous = null;
            for (RoutePlatformData routePlatform : route.getRoutePlatforms()) {
                Platform platform = routePlatform.platform;
                if (platform == null) {
                    continue;
                }
                Position mid = platform.getMidPosition();
                if (previous != null) {
                    cumulative += Math.hypot(mid.getX() - previous.getX(), mid.getZ() - previous.getZ());
                }
                previous = mid;
                Station station = platform.area;
                JsonObject stop = new JsonObject();
                stop.addProperty("plat", String.valueOf(platform.getId()));
                stop.addProperty("platName", platform.getName());
                stop.addProperty("sta", station == null ? "0" : String.valueOf(station.getId()));
                stop.addProperty("staName", station == null ? "" : station.getName());
                stop.addProperty("dist", Math.round(cumulative));
                stations.add(stop);
            }
            routeJson.add("stations", stations);
            routes.add(routeJson);
        }
        root.add("routes", routes);
        return root;
    }

    /**
     * Departure rows for the requested routes as compact arrays
     * {@code [vehicleId, platformId, tMillis, dwellMs, deviationMs, stopIndex, routeId]}
     * (ids as strings — they can exceed 2^53), oldest first. Empty when analytics is
     * off or the window has nothing yet.
     */
    public static JsonArray depsJson(String dimension, String routesParameter) {
        JsonArray rows = new JsonArray();
        AnalyticsAggregator.Aggregate aggregate = AnalyticsAggregator.get(dimension);
        if (aggregate == null || routesParameter == null || routesParameter.isEmpty()) {
            return rows;
        }
        Set<Long> wanted = new HashSet<>();
        for (String token : routesParameter.split(",")) {
            try {
                wanted.add(Long.parseLong(token.trim()));
            } catch (NumberFormatException ignored) {
                // Skip malformed ids; the rest of the list still filters.
            }
        }
        for (AnalyticsEvent event : aggregate.events) {
            if (!wanted.contains(event.routeId())) {
                continue;
            }
            JsonArray row = new JsonArray();
            row.add(String.valueOf(event.vehicleId()));
            row.add(String.valueOf(event.platformId()));
            row.add(event.atMillis());
            row.add(event.dwellMs());
            row.add(event.deviationMs());
            row.add(event.stopIndex());
            row.add(String.valueOf(event.routeId()));
            rows.add(row);
        }
        return rows;
    }
}
