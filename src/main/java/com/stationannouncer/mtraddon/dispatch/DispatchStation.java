package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.AddonSnapshots;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.HoldRuleEngine;
import com.stationannouncer.mtraddon.analytics.AnalyticsAggregator;
import com.stationannouncer.mtraddon.analytics.AnalyticsEvent;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import com.stationannouncer.wayfinding.layout.LayoutScanner;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /dispatch/api/station?dimension=N&id=<stationId>} — everything the web dispatch
 * STATION PAGE shows for one station (STATION_PAGE_PLAN.md), built on the simulator thread:
 *
 * <ul>
 *   <li>{@code station}: id, name, colour, MTR area, manual step-free flag and the layout
 *       scan's per-platform verdict;</li>
 *   <li>{@code platforms}: name, dwell, midpoint, routes, live HELD flag, its hold rule and
 *       per-route dwell overrides;</li>
 *   <li>{@code routes}: every route calling here with its previous / next stop, terminus and
 *       SCHEDULED headway ({@link AnalyticsRecorder#scheduledHeadwayMillis});</li>
 *   <li>{@code upcoming}: the real next departures per platform from MTR's own timetable
 *       ({@code Siding.getArrivals}, the call MTR's PIDS use);</li>
 *   <li>{@code departures}: this station's departures in the analytics window — actual time,
 *       deviation (scheduled = actual − deviation), dwell vs scheduled dwell;</li>
 *   <li>{@code headways}: per route and platform, the scheduled headway next to the real gaps
 *       between departures, with bunching counted like the analytics view;</li>
 *   <li>{@code exits}, {@code layout} (with paths) and {@code geometry} (the clean schematic).</li>
 * </ul>
 * Ids are decimal strings; times epoch milliseconds.
 */
public final class DispatchStation {
    private static final long ARRIVALS_PER_SIDING = 4;
    private static final int UPCOMING_PER_PLATFORM = 8;
    private static final long UPCOMING_HORIZON_MS = 60 * 60 * 1000L;
    private static final int MAX_DEPARTURES = 300;

    private DispatchStation() {
    }

    public static JsonObject build(Simulator simulator, String idParameter) {
        JsonObject root = new JsonObject();
        long now = System.currentTimeMillis();
        root.addProperty("now", now);
        long stationId;
        try {
            stationId = Long.parseLong(idParameter == null ? "" : idParameter.trim());
        } catch (NumberFormatException e) {
            root.addProperty("error", "bad station id");
            return root;
        }
        Station station = null;
        for (Station candidate : simulator.stations) {
            if (candidate.getId() == stationId) {
                station = candidate;
                break;
            }
        }
        if (station == null) {
            root.addProperty("error", "no station " + stationId + " in this dimension");
            return root;
        }

        root.add("station", stationJson(station));

        // ---- platforms + the routes calling at them
        Map<Long, AddonSnapshots.HoldRule> holdRules = AddonStore.holdRulesView();
        Map<Long, LinkedHashMap<Long, Long>> dwellOverrides = AddonStore.dwellOverridesView();
        Set<Long> held = HoldRuleEngine.heldPlatforms();
        Map<Long, Route> routes = new LinkedHashMap<>();
        List<Platform> platforms = new ArrayList<>(station.savedRails);
        platforms.sort(Comparator.comparing(Platform::getName));
        JsonArray platformsJson = new JsonArray();
        for (Platform platform : platforms) {
            JsonObject p = new JsonObject();
            p.addProperty("id", String.valueOf(platform.getId()));
            p.addProperty("name", platform.getName());
            p.addProperty("dwellMs", platform.getDwellTime());
            p.add("mid", position(platform.getMidPosition()));
            JsonArray routeIds = new JsonArray();
            platform.routes.forEach(route -> {
                routeIds.add(String.valueOf(route.getId()));
                routes.putIfAbsent(route.getId(), route);
            });
            p.add("routeIds", routeIds);
            p.addProperty("held", held.contains(platform.getId()));
            AddonSnapshots.HoldRule rule = holdRules.get(platform.getId());
            if (rule != null) {
                JsonObject r = new JsonObject();
                JsonArray watched = new JsonArray();
                for (long w : rule.watched()) {
                    watched.add(String.valueOf(w));
                }
                r.add("watched", watched);
                r.addProperty("seconds", rule.seconds());
                r.addProperty("transferSeconds", rule.transferSeconds());
                p.add("holdRule", r);
            }
            LinkedHashMap<Long, Long> overrides = dwellOverrides.get(platform.getId());
            if (overrides != null && !overrides.isEmpty()) {
                JsonObject o = new JsonObject();
                overrides.forEach((route, ms) -> o.addProperty(String.valueOf(route), ms));
                p.add("dwellOverrides", o);
            }
            platformsJson.add(p);
        }
        root.add("platforms", platformsJson);

        Set<Long> platformIds = new LinkedHashSet<>();
        platforms.forEach(platform -> platformIds.add(platform.getId()));
        JsonArray routesJson = new JsonArray();
        for (Route route : routes.values()) {
            routesJson.add(routeJson(simulator, route, platformIds));
        }
        root.add("routes", routesJson);

        // ---- the real next departures, from MTR's timetable
        JsonArray upcoming = new JsonArray();
        for (Platform platform : platforms) {
            ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
            for (Siding siding : simulator.sidings) {
                try {
                    siding.getArrivals(now, platform, ARRIVALS_PER_SIDING, arrivals);
                } catch (Throwable ignored) {
                    // one broken siding must not blank the board
                }
            }
            arrivals.sort(Comparator.comparingLong(ArrivalResponse::getDeparture));
            int count = 0;
            for (ArrivalResponse arrival : arrivals) {
                if (arrival.getDeparture() < now - 30_000 || arrival.getArrival() > now + UPCOMING_HORIZON_MS) {
                    continue;
                }
                JsonObject a = new JsonObject();
                a.addProperty("platformId", String.valueOf(platform.getId()));
                a.addProperty("routeId", String.valueOf(arrival.getRouteId()));
                a.addProperty("routeName", arrival.getRouteName());
                a.addProperty("routeNumber", arrival.getRouteNumber());
                a.addProperty("color", arrival.getRouteColor());
                a.addProperty("dest", arrival.getDestination());
                a.addProperty("arrival", arrival.getArrival());
                a.addProperty("departure", arrival.getDeparture());
                a.addProperty("deviation", arrival.getDeviation());
                a.addProperty("realtime", arrival.getRealtime());
                a.addProperty("terminating", arrival.getIsTerminating());
                a.addProperty("cars", arrival.getCarCount());
                upcoming.add(a);
                if (++count >= UPCOMING_PER_PLATFORM) {
                    break;
                }
            }
        }
        root.add("upcoming", upcoming);

        // ---- the analytics window: departures and headways at this station
        AddonServerConfig.Analytics config = AddonServerConfig.get().analytics;
        root.addProperty("analyticsEnabled", config.enabled);
        root.addProperty("windowMinutes", config.windowMinutes);
        root.addProperty("onTimeToleranceSeconds", config.onTimeToleranceSeconds);
        root.addProperty("bunchingFraction", config.bunchingFraction);
        List<AnalyticsEvent> events = new ArrayList<>();
        AnalyticsAggregator.Aggregate aggregate = AnalyticsAggregator.get(simulator.dimension);
        if (aggregate != null) {
            for (AnalyticsEvent event : aggregate.events) {
                if (!event.arrival() && event.stationId() == stationId) {
                    events.add(event);
                }
            }
        }
        JsonArray departures = new JsonArray();
        for (int i = events.size() - 1; i >= 0 && departures.size() < MAX_DEPARTURES; i--) {
            AnalyticsEvent e = events.get(i);
            JsonArray row = new JsonArray(8);
            row.add(e.atMillis());
            row.add(String.valueOf(e.platformId()));
            row.add(String.valueOf(e.routeId()));
            row.add(e.deviationMs());
            row.add(e.dwellMeasured() ? e.dwellMs() : -1);
            row.add(e.scheduledDwellMs());
            row.add(String.valueOf(e.vehicleId()));
            row.add(e.stopIndex());
            departures.add(row);
        }
        root.add("departures", departures);
        root.add("headways", headways(simulator, events, routes, config.bunchingFraction));

        // ---- exits, layout scan, schematic geometry
        root.add("exits", exits(simulator, station));
        root.addProperty("scanState", LayoutScanner.scanState(stationId));
        String layout = LayoutScanner.layoutJson(stationId);
        if (layout != null) {
            try {
                root.add("layout", JsonParser.parseString(layout));
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Station page: layout of {} unreadable ({})", stationId, t.toString());
            }
        }
        String geometry = LayoutScanner.geometryJson(stationId);
        if (geometry != null) {
            try {
                root.add("geometry", JsonParser.parseString(geometry));
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Station page: geometry of {} unreadable ({})", stationId, t.toString());
            }
        }
        return root;
    }

    private static JsonObject stationJson(Station station) {
        JsonObject s = new JsonObject();
        s.addProperty("id", String.valueOf(station.getId()));
        s.addProperty("name", station.getName());
        s.addProperty("color", station.getColor());
        JsonArray bounds = new JsonArray();
        bounds.add(station.getMinX());
        bounds.add(station.getMinY());
        bounds.add(station.getMinZ());
        bounds.add(station.getMaxX());
        bounds.add(station.getMaxY());
        bounds.add(station.getMaxZ());
        s.add("bounds", bounds);
        s.addProperty("zone", station.getZone1());
        long[] accessible = AddonStore.accessibilityView().get(station.getId());
        s.addProperty("accessible", accessible != null);
        if (accessible != null && accessible.length > 0) {
            JsonArray list = new JsonArray();
            for (long id : accessible) {
                list.add(String.valueOf(id));
            }
            s.add("accessiblePlatforms", list);
        }
        Map<Long, Boolean> computed = LayoutScanner.stepFree(station.getId());
        if (!computed.isEmpty()) {
            JsonObject c = new JsonObject();
            computed.forEach((id, value) -> c.addProperty(String.valueOf(id), value));
            s.add("layoutStepFree", c);
        }
        return s;
    }

    /** A route at this station: where it comes from, where it goes next, its terminus and scheduled headway. */
    private static JsonObject routeJson(Simulator simulator, Route route, Set<Long> here) {
        JsonObject r = new JsonObject();
        r.addProperty("id", String.valueOf(route.getId()));
        r.addProperty("name", route.getName());
        r.addProperty("number", route.getRouteNumber());
        r.addProperty("color", route.getColor());
        r.addProperty("hidden", route.getHidden());
        try {
            r.addProperty("mode", route.getTransportMode().toString().toLowerCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) {
            // mode is cosmetic
        }
        r.addProperty("schedHeadwayMs", AnalyticsRecorder.scheduledHeadwayMillis(simulator, route.getId()));
        ObjectArrayList<RoutePlatformData> stops = route.getRoutePlatforms();
        JsonArray stopNames = new JsonArray();
        int at = -1;
        for (int i = 0; i < stops.size(); i++) {
            Platform platform = stops.get(i).platform;
            Station area = platform == null ? null : platform.area;
            stopNames.add(area == null ? "" : area.getName());
            if (platform != null && at < 0 && here.contains(platform.getId())) {
                at = i;
            }
        }
        r.add("stops", stopNames);
        r.addProperty("stopIndex", at);
        if (at >= 0) {
            r.addProperty("prev", at > 0 ? stopNames.get(at - 1).getAsString() : "");
            r.addProperty("next", at + 1 < stopNames.size() ? stopNames.get(at + 1).getAsString() : "");
            r.addProperty("terminus", stopNames.isEmpty() ? "" : stopNames.get(stopNames.size() - 1).getAsString());
        }
        return r;
    }

    /**
     * Scheduled headway vs the real gaps, per route and platform: the gap between two
     * consecutive departures of the same route from the same platform (the analytics
     * view's definition), bunched when shorter than {@code bunchingFraction} × scheduled.
     */
    private static JsonArray headways(Simulator simulator, List<AnalyticsEvent> events, Map<Long, Route> routes,
                                      double bunchingFraction) {
        Map<String, List<AnalyticsEvent>> byKey = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            byKey.computeIfAbsent(e.routeId() + ":" + e.platformId(), k -> new ArrayList<>()).add(e);
        }
        Map<Long, Long> scheduledCache = new HashMap<>();
        JsonArray out = new JsonArray();
        for (Map.Entry<String, List<AnalyticsEvent>> entry : byKey.entrySet()) {
            List<AnalyticsEvent> list = entry.getValue();
            list.sort(Comparator.comparingLong(AnalyticsEvent::atMillis));
            AnalyticsEvent last = list.get(list.size() - 1);
            long scheduled = last.schedHeadwayMs() > 0 ? last.schedHeadwayMs()
                    : scheduledCache.computeIfAbsent(last.routeId(), id -> routes.containsKey(id)
                    ? AnalyticsRecorder.scheduledHeadwayMillis(simulator, id) : 0L);
            JsonArray gaps = new JsonArray();
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = 0;
            int bunched = 0;
            for (int i = 1; i < list.size(); i++) {
                long gap = list.get(i).atMillis() - list.get(i - 1).atMillis();
                JsonArray g = new JsonArray(2);
                g.add(list.get(i).atMillis());
                g.add(gap);
                gaps.add(g);
                sum += gap;
                min = Math.min(min, gap);
                max = Math.max(max, gap);
                if (scheduled > 0 && gap < scheduled * bunchingFraction) {
                    bunched++;
                }
            }
            JsonObject h = new JsonObject();
            h.addProperty("routeId", String.valueOf(last.routeId()));
            h.addProperty("platformId", String.valueOf(last.platformId()));
            h.addProperty("scheduledMs", scheduled);
            h.addProperty("departures", list.size());
            h.addProperty("lastDeparture", last.atMillis());
            if (gaps.size() > 0) {
                h.addProperty("averageMs", sum / gaps.size());
                h.addProperty("minMs", min);
                h.addProperty("maxMs", max);
            }
            h.addProperty("bunched", bunched);
            h.add("gaps", gaps);
            out.add(h);
        }
        return out;
    }

    private static JsonArray exits(Simulator simulator, Station station) {
        JsonArray exitsJson = new JsonArray();
        try {
            Map<String, JsonArray> pins = DispatchMapData.exitPins(simulator).get(station.getId());
            for (org.mtr.core.data.StationExit exit : station.getExits()) {
                JsonObject e = new JsonObject();
                e.addProperty("name", exit.getName());
                JsonArray destinations = new JsonArray();
                for (String destination : exit.getDestinations()) {
                    destinations.add(destination);
                }
                e.add("destinations", destinations);
                JsonArray exitPins = pins == null ? null : pins.get(exit.getName());
                if (exitPins != null) {
                    e.add("pins", exitPins);
                }
                exitsJson.add(e);
            }
        } catch (Throwable ignored) {
            // exits are decorative — never let them break the page
        }
        return exitsJson;
    }

    private static JsonArray position(Position position) {
        JsonArray a = new JsonArray(3);
        a.add(position.getX());
        a.add(position.getY());
        a.add(position.getZ());
        return a;
    }
}
