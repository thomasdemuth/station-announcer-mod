package com.stationannouncer.mtraddon.dispatch;

import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the static network payload for GET {@code /dispatch/api/network?dimension=N}:
 * rail polylines, stations, platforms, routes and signal placements. Everything here
 * runs on the target dimension's SIMULATOR thread (invoked from
 * {@link DispatchApiServlet#getContent} behind a 30 s {@code CachedResponse}), reading
 * only that simulator's own data — the sanctioned single-threaded-state pattern MTR's
 * SystemMapServlet uses.
 *
 * <p>All MTR ids (random longs, may exceed 2^53) are serialized as decimal strings so
 * JavaScript never loses precision; rail ids are MTR's canonical position-sorted hex
 * ids ({@code TwoPositionsBase.getHexId()}), the same keys the SSE stream uses.</p>
 */
public final class DispatchNetwork {
    /** Target spacing between polyline samples, meters. */
    private static final double SAMPLE_STEP_METERS = 4;
    /** Hard cap on samples per rail before pruning (long rails get a coarser step). */
    private static final int MAX_SAMPLES_PER_RAIL = 96;
    /** Collinearity tolerance for pruning straight runs, meters. */
    private static final double PRUNE_TOLERANCE = 0.05;

    private DispatchNetwork() {
    }

    /** Runs on the simulator thread. */
    public static JsonObject build(Simulator simulator) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", DispatchStreamer.SCHEMA_VERSION);
        root.addProperty("serverTime", System.currentTimeMillis());
        root.addProperty("dimension", simulator.dimension);
        JsonArray dimensions = new JsonArray();
        int dimensionIndex = 0;
        for (int i = 0; i < simulator.dimensions.length; i++) {
            dimensions.add(simulator.dimensions[i]);
            if (simulator.dimensions[i].equals(simulator.dimension)) {
                dimensionIndex = i;
            }
        }
        root.add("dimensions", dimensions);
        root.addProperty("dimensionIndex", dimensionIndex);

        // --- Rails: railIdMap is keyed by the canonical hex id, so every rail appears
        // exactly once (positionsToRail carries each rail twice; the map is the dedupe).
        JsonArray rails = new JsonArray();
        for (Rail rail : simulator.railIdMap.values()) {
            if (!rail.isValid()) {
                continue;
            }
            JsonObject railJson = new JsonObject();
            railJson.addProperty("id", rail.getHexId());
            railJson.addProperty("mode", rail.getTransportMode().toString().toLowerCase(Locale.ROOT));
            RailMath railMath = rail.railMath;
            double length = railMath.getLength();
            railJson.addProperty("length", round2(length));
            // speedA = travelling along the polyline direction (getPosition(d, false)),
            // speedB = the opposite direction; km/h, 0 = not traversable that way.
            railJson.addProperty("speedA", rail.getSpeedLimitKilometersPerHour(false));
            railJson.addProperty("speedB", rail.getSpeedLimitKilometersPerHour(true));
            railJson.addProperty("platform", rail.isPlatform());
            railJson.addProperty("siding", rail.isSiding());
            railJson.addProperty("canAccelerate", rail.canAccelerate());
            railJson.addProperty("canTurnBack", rail.canTurnBack());
            JsonArray signalColors = new JsonArray();
            for (int signalColor : rail.getSignalColors()) {
                signalColors.add(signalColor);
            }
            railJson.add("signalColors", signalColors);
            railJson.add("points", polyline(railMath, length));
            rails.add(railJson);
        }
        root.add("rails", rails);

        // Step-free accessibility (addon store): stationId → platform subset
        // (empty = every platform). Rides the 30 s network cache like the rest.
        java.util.Map<Long, long[]> accessibility = com.stationannouncer.mtraddon.AddonStore.accessibilityView();

        // --- Stations (AreaBase corners; names raw "Eng|Other" — frontend splits).
        JsonArray stations = new JsonArray();
        for (Station station : simulator.stations) {
            JsonObject stationJson = new JsonObject();
            stationJson.addProperty("id", String.valueOf(station.getId()));
            stationJson.addProperty("name", station.getName());
            stationJson.addProperty("color", station.getColor());
            JsonArray bounds = new JsonArray();
            bounds.add(station.getMinX());
            bounds.add(station.getMinY());
            bounds.add(station.getMinZ());
            bounds.add(station.getMaxX());
            bounds.add(station.getMaxY());
            bounds.add(station.getMaxZ());
            stationJson.add("bounds", bounds);
            JsonArray platformIds = new JsonArray();
            station.savedRails.forEach(platform -> platformIds.add(String.valueOf(platform.getId())));
            stationJson.add("platformIds", platformIds);
            long[] accessiblePlatforms = accessibility.get(station.getId());
            stationJson.addProperty("accessible", accessiblePlatforms != null);
            if (accessiblePlatforms != null && accessiblePlatforms.length > 0) {
                JsonArray accessibleJson = new JsonArray(accessiblePlatforms.length);
                for (long platform : accessiblePlatforms) {
                    accessibleJson.add(String.valueOf(platform));
                }
                stationJson.add("accessiblePlatforms", accessibleJson);
            }
            stations.add(stationJson);
        }
        root.add("stations", stations);

        // --- Platforms (position pair + midpoint via the public SavedRailBase helpers).
        JsonArray platforms = new JsonArray();
        for (Platform platform : simulator.platforms) {
            JsonObject platformJson = new JsonObject();
            platformJson.addProperty("id", String.valueOf(platform.getId()));
            platformJson.addProperty("name", platform.getName());
            platformJson.addProperty("dwellMs", platform.getDwellTime());
            Station station = platform.area;
            if (station == null) {
                platformJson.addProperty("stationId", (String) null);
            } else {
                platformJson.addProperty("stationId", String.valueOf(station.getId()));
            }
            Position position1 = platform.getRandomPosition();
            Position position2 = platform.getOtherPosition(position1);
            platformJson.add("p1", positionArray(position1));
            platformJson.add("p2", positionArray(position2));
            platformJson.add("mid", positionArray(platform.getMidPosition()));
            JsonArray routeIds = new JsonArray();
            platform.routes.forEach(route -> routeIds.add(String.valueOf(route.getId())));
            platformJson.add("routeIds", routeIds);
            long[] stationAccessible = station == null ? null : accessibility.get(station.getId());
            boolean platformAccessible = stationAccessible != null && (stationAccessible.length == 0
                    || java.util.Arrays.stream(stationAccessible).anyMatch(id -> id == platform.getId()));
            platformJson.addProperty("accessible", platformAccessible);
            platforms.add(platformJson);
        }
        root.add("platforms", platforms);

        // --- Routes, listed once; platforms reference them by id.
        JsonArray routes = new JsonArray();
        for (Route route : simulator.routes) {
            JsonObject routeJson = new JsonObject();
            routeJson.addProperty("id", String.valueOf(route.getId()));
            routeJson.addProperty("name", route.getName());
            routeJson.addProperty("number", route.getRouteNumber());
            routeJson.addProperty("color", route.getColor());
            routeJson.addProperty("hidden", route.getHidden());
            routes.add(routeJson);
        }
        root.add("routes", routes);

        return root;
    }

    /**
     * Samples the rail curve every ~{@value #SAMPLE_STEP_METERS} m (capped at
     * {@value #MAX_SAMPLES_PER_RAIL} samples), then prunes collinear points — a straight
     * rail collapses to its two endpoints, curves keep only the points that matter.
     */
    private static JsonArray polyline(RailMath railMath, double length) {
        List<Vector> samples = new ArrayList<>();
        if (length <= 0) {
            // Degenerate rail: still emit a two-point polyline so the frontend never has
            // to special-case a one-point "line".
            Vector only = railMath.getPosition(0, false);
            samples.add(only);
            samples.add(only);
        } else {
            double step = SAMPLE_STEP_METERS;
            // -1 because the endpoint is appended separately; this keeps the total at
            // MAX_SAMPLES_PER_RAIL rather than one over it.
            if (length / step > MAX_SAMPLES_PER_RAIL - 1) {
                step = length / (MAX_SAMPLES_PER_RAIL - 1);
            }
            for (double distance = 0; distance < length; distance += step) {
                samples.add(railMath.getPosition(distance, false));
            }
            samples.add(railMath.getPosition(length, false));
        }

        JsonArray points = new JsonArray();
        int lastKept = 0;
        points.add(pointArray(samples.get(0)));
        for (int i = 1; i < samples.size() - 1; i++) {
            if (perpendicularDistance(samples.get(lastKept), samples.get(i + 1), samples.get(i)) > PRUNE_TOLERANCE) {
                points.add(pointArray(samples.get(i)));
                lastKept = i;
            }
        }
        points.add(pointArray(samples.get(samples.size() - 1)));
        return points;
    }

    /** Distance of {@code point} from the line through {@code lineA}→{@code lineB}. */
    private static double perpendicularDistance(Vector lineA, Vector lineB, Vector point) {
        double abX = lineB.x - lineA.x;
        double abY = lineB.y - lineA.y;
        double abZ = lineB.z - lineA.z;
        double lengthSquared = abX * abX + abY * abY + abZ * abZ;
        if (lengthSquared < 1E-9) {
            double dx = point.x - lineA.x;
            double dy = point.y - lineA.y;
            double dz = point.z - lineA.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double apX = point.x - lineA.x;
        double apY = point.y - lineA.y;
        double apZ = point.z - lineA.z;
        // |AP x AB| / |AB|
        double crossX = apY * abZ - apZ * abY;
        double crossY = apZ * abX - apX * abZ;
        double crossZ = apX * abY - apY * abX;
        return Math.sqrt((crossX * crossX + crossY * crossY + crossZ * crossZ) / lengthSquared);
    }

    private static JsonArray pointArray(Vector vector) {
        JsonArray point = new JsonArray();
        point.add(round2(vector.x));
        point.add(round2(vector.y));
        point.add(round2(vector.z));
        return point;
    }

    private static JsonArray positionArray(Position position) {
        JsonArray point = new JsonArray();
        point.add(position.getX());
        point.add(position.getY());
        point.add(position.getZ());
        return point;
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
